package website.msdnna.budget_app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Покрывает онлайновый snapshot `/api/categories/limits-progress`:
 *   • первый успешный ответ заполняет state;
 *   • второй пустой/ошибочный — не сбрасывает прошлый snapshot;
 *   • clear() — обнуляет.
 *
 * RetrofitClient кэширует Service по baseURL — каждый MockWebServer запускается
 * на уникальном порту, поэтому соседние тесты не дерутся за один и тот же кэш.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LimitsProgressRepositoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Очищаем кросс-тестовый state, чтобы прошлые прогоны не подсвечивались.
        LimitsProgressRepository.clear()
    }

    @After
    fun tearDown() {
        LimitsProgressRepository.clear()
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun `refresh populates state on 200`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "period": "2026-05",
                  "total_limit": 50000,
                  "total_spent": 12345,
                  "total_percent": 24.69,
                  "categories": []
                }
                """.trimIndent(),
            ),
        )

        LimitsProgressRepository.refresh(baseUrl(), month = "2026-05")
        val s = LimitsProgressRepository.state.value
        assertThat(s).isNotNull()
        assertThat(s!!.period).isEqualTo("2026-05")
        assertThat(s.totalLimit).isEqualTo(50000.0)
        assertThat(s.totalSpent).isEqualTo(12345.0)
    }

    @Test
    fun `refresh keeps last snapshot on server error`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"period":"2026-05","total_limit":1,"total_spent":0,"total_percent":0,"categories":[]}
                """.trimIndent(),
            ),
        )
        LimitsProgressRepository.refresh(baseUrl())
        assertThat(LimitsProgressRepository.state.value).isNotNull()

        server.enqueue(MockResponse().setResponseCode(500))
        LimitsProgressRepository.refresh(baseUrl())
        // Stale snapshot stays; the bar simply doesn't move.
        assertThat(LimitsProgressRepository.state.value).isNotNull()
    }

    @Test
    fun `clear nukes the snapshot`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"period":"2026-05","total_limit":1,"total_spent":0,"total_percent":0,"categories":[]}
                """.trimIndent(),
            ),
        )
        LimitsProgressRepository.refresh(baseUrl())
        assertThat(LimitsProgressRepository.state.value).isNotNull()
        LimitsProgressRepository.clear()
        assertThat(LimitsProgressRepository.state.value).isNull()
    }
}
