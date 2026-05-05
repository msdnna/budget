package website.msdnna.budget_app.ui.viewmodels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException

// Retries the block up to [attempts] times on transient network errors.
// Does NOT retry on HTTP errors (4xx/5xx) or coroutine cancellation.
internal suspend fun <T> withRetry(
    attempts: Int = 3,
    delayMs: Long = 1000L,
    block: suspend () -> T
): T {
    var lastError: Exception? = null
    repeat(attempts) { i ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            throw e
        } catch (e: Exception) {
            lastError = e
            if (i < attempts - 1) delay(delayMs)
        }
    }
    throw lastError!!
}
