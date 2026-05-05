package website.msdnna.budget_app.data.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AppLock {
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked

    private var backgroundedAt: Long? = null

    fun unlock() {
        _isUnlocked.value = true
        backgroundedAt = null
    }

    fun lock() {
        _isUnlocked.value = false
        backgroundedAt = null
    }

    fun onPause() {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    fun onResume(timeoutSec: Int, hasPin: Boolean) {
        // Do NOT auto-unlock on `!hasPin` here. Lifecycle replays ON_RESUME to
        // observers right after subscription, before the loader has finished
        // reading PIN from DataStore — so hasPin would briefly be false and
        // wrongly unlock a PIN-protected app. Initial unlock-when-no-PIN is
        // handled in the loader effect; runtime PIN-disable explicitly calls
        // unlock() in the settings handler.
        if (!hasPin) return
        val bg = backgroundedAt ?: return
        val elapsed = SystemClock.elapsedRealtime() - bg
        if (elapsed >= timeoutSec * 1000L) {
            _isUnlocked.value = false
        }
        backgroundedAt = null
    }
}
