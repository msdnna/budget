package website.msdnna.budget_app.ui.viewmodels

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RetryUtilsTest {

    @Test
    fun `returns immediately when block succeeds`() = runTest {
        var calls = 0
        val result = withRetry {
            calls += 1
            "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `retries transient errors up to attempts and returns success`() = runTest {
        var calls = 0
        val result = withRetry(attempts = 3, delayMs = 0L) {
            calls += 1
            if (calls < 3) throw IOException("transient") else "ok"
        }
        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(3)
    }

    @Test(expected = HttpException::class)
    fun `does not retry HttpException`() = runTest {
        var calls = 0
        try {
            withRetry(attempts = 3, delayMs = 0L) {
                calls += 1
                throw httpException(500)
            }
        } finally {
            assertThat(calls).isEqualTo(1)
        }
    }

    @Test
    fun `throws last error after attempts exhausted`() = runTest {
        var calls = 0
        val thrown = runCatching {
            withRetry(attempts = 2, delayMs = 0L) {
                calls += 1
                throw IOException("err-$calls")
            }
        }.exceptionOrNull()

        assertThat(calls).isEqualTo(2)
        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown!!.message).isEqualTo("err-2")
    }

    private fun httpException(code: Int): HttpException {
        val body = "".toResponseBody("text/plain".toMediaType())
        return HttpException(Response.error<Any>(code, body))
    }
}
