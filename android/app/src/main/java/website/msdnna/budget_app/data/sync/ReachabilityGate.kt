package website.msdnna.budget_app.data.sync

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ReachabilityGate"

/**
 * Single TCP probe in front of the API HTTP client.
 *
 * Why: when the API is unreachable the regular `callTimeout=30s` waits the full
 * timeout per request. With 5+ concurrent cold-start requests this stacks into
 * a frozen UI. The gate runs one fast `Socket.connect(host:port, 3s)` and
 * distinguishes:
 *   - Instant rejection (ConnectException / NoRouteToHost / UnknownHost /
 *     PortUnreachable) → [State.Offline]. All in-flight and subsequent HTTP
 *     calls short-circuit via the OkHttp interceptor; ViewModels fall back to
 *     Room.
 *   - Slow handshake / `SocketTimeoutException` → [State.Online]. The server
 *     might just be slow — let the regular 30s callTimeout do its job.
 *   - Successful connect → [State.Online].
 *
 * Refresh triggers (kicked from UI):
 *   - App start, foreground resume, AppLock unlock, overlay transitions,
 *     after every CRUD (piggybacks on `SyncWorker.enqueue`).
 *
 * Thread-safety: a single in-flight probe is allowed at a time; concurrent
 * callers join the same [Job].
 */
object ReachabilityGate {
    enum class State { Unknown, Online, Offline }

    private val _state = MutableStateFlow(State.Unknown)
    val state: StateFlow<State> = _state

    @Volatile private var serverUrl: String = ""

    @Volatile private var lastProbeAt: Long = 0L

    @Volatile private var inFlight: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PROBE_TIMEOUT_MS = 3_000
    private const val INTERCEPTOR_WAIT_MS = 3_500L
    private const val MIN_REPROBE_MS = 1_500L

    /** Update the server URL and reset state on change (forces a fresh probe). */
    fun setServerUrl(url: String) {
        val trimmed = url.trimEnd('/')
        if (serverUrl != trimmed) {
            serverUrl = trimmed
            _state.value = State.Unknown
            lastProbeAt = 0L
        }
    }

    /** Are we currently considered offline? Conservative: unknown ≠ offline. */
    val isOffline: Boolean get() = _state.value == State.Offline

    /**
     * Trigger a probe. Returns the [Job] of the currently running probe (or a
     * brand-new one). Coalesces concurrent callers. Skips no-op refresh if a
     * recent probe completed within [MIN_REPROBE_MS] (unless [force]).
     */
    fun refresh(force: Boolean = false): Job {
        inFlight?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (!force && _state.value != State.Unknown && now - lastProbeAt < MIN_REPROBE_MS) {
            return scope.launch { /* skipped */ }
        }
        val url = serverUrl
        if (url.isBlank()) return scope.launch { /* nothing to probe */ }

        val job = scope.launch {
            val result = runCatching { probeOnce(url) }
                .getOrElse { State.Online } // any unexpected error → optimistic
            lastProbeAt = SystemClock.elapsedRealtime()
            _state.value = result
            inFlight = null
            Log.i(TAG, "probe $url → $result")
        }
        inFlight = job
        return job
    }

    /**
     * Used by the OkHttp interceptor: block briefly waiting for the first probe
     * to resolve [State.Unknown]. Returns the final state (or current state on
     * timeout). Runs on an OkHttp dispatcher thread, never the main thread.
     */
    fun awaitFirstBlocking(timeoutMs: Long = INTERCEPTOR_WAIT_MS): State {
        val current = _state.value
        if (current != State.Unknown) return current
        // Gate not configured yet (e.g. ConnectScreen pre-login flow) — let the
        // request through with the regular OkHttp timeout.
        if (serverUrl.isBlank()) return State.Unknown
        refresh()
        return runBlocking {
            withTimeoutOrNull(timeoutMs) {
                _state.first { it != State.Unknown }
            } ?: _state.value
        }
    }

    private fun probeOnce(url: String): State {
        val addr = parseHostPort(url) ?: return State.Online // unparseable → optimistic
        return Socket().use { sock ->
            try {
                sock.connect(addr, PROBE_TIMEOUT_MS)
                State.Online
            } catch (_: ConnectException) {
                // RST / Connection refused — server explicitly rejected.
                State.Offline
            } catch (_: NoRouteToHostException) {
                State.Offline
            } catch (_: UnknownHostException) {
                State.Offline
            } catch (_: PortUnreachableException) {
                State.Offline
            } catch (_: SocketTimeoutException) {
                // Slow / hanging — give the regular HTTP timeout a chance.
                State.Online
            } catch (_: IOException) {
                // Conservative: classify generic IO as offline to avoid 30s hangs.
                State.Offline
            }
        }
    }

    private fun parseHostPort(url: String): InetSocketAddress? = try {
        val u = URI(url)
        val host = u.host ?: return null
        val port = when {
            u.port > 0 -> u.port
            u.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        InetSocketAddress(host, port)
    } catch (_: Exception) {
        null
    }
}
