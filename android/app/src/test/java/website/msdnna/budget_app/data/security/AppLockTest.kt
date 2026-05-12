package website.msdnna.budget_app.data.security

import android.os.SystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLockTest {

    @Before
    fun resetLock() {
        // AppLock — object (singleton); явный сброс между тестами.
        AppLock.lock()
    }

    @Test
    fun `unlock flips state to true`() {
        assertThat(AppLock.isUnlocked.value).isFalse()
        AppLock.unlock()
        assertThat(AppLock.isUnlocked.value).isTrue()
    }

    @Test
    fun `resume within timeout keeps app unlocked`() {
        AppLock.unlock()
        AppLock.onPause()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))

        AppLock.onResume(timeoutSec = 5, hasPin = true)
        assertThat(AppLock.isUnlocked.value).isTrue()
    }

    @Test
    fun `resume after timeout locks the app`() {
        AppLock.unlock()
        AppLock.onPause()

        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))

        AppLock.onResume(timeoutSec = 5, hasPin = true)
        assertThat(AppLock.isUnlocked.value).isFalse()
    }

    @Test
    fun `resume with hasPin=false leaves state untouched`() {
        AppLock.unlock()
        AppLock.onPause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))

        AppLock.onResume(timeoutSec = 5, hasPin = false)

        // Without PIN мы НЕ ставим автоматически unlock в onResume (защита от
        // гонки при первом ON_RESUME до загрузки DataStore) — но и не запираем.
        assertThat(AppLock.isUnlocked.value).isTrue()
    }

    @Test
    fun `resume without prior onPause is a no-op`() {
        AppLock.unlock()
        // pause не вызывали
        AppLock.onResume(timeoutSec = 0, hasPin = true)
        assertThat(AppLock.isUnlocked.value).isTrue()
    }

    @Test
    fun `unlock clears backgroundedAt so a subsequent stale resume does not lock`() {
        AppLock.unlock()
        AppLock.onPause()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        // Пользователь явно разблокировал по PIN — это сбрасывает backgroundedAt.
        AppLock.unlock()
        assertThat(AppLock.isUnlocked.value).isTrue()

        // Дальнейший onResume без нового onPause не должен запирать.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        AppLock.onResume(timeoutSec = 5, hasPin = true)
        assertThat(AppLock.isUnlocked.value).isTrue()
    }

    @Suppress("UnusedPrivateProperty")
    private val systemClockReference = SystemClock::class // keep import live
}
