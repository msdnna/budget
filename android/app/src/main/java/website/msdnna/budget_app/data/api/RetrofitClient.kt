package website.msdnna.budget_app.data.api

import com.google.gson.Gson
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import website.msdnna.budget_app.data.model.RefreshRequest
import website.msdnna.budget_app.data.model.RefreshResponse
import website.msdnna.budget_app.data.sync.ReachabilityGate

object RetrofitClient {
    @Volatile private var service: ApiService? = null

    @Volatile private var currentBaseUrl: String = ""

    // Access JWT, injected into the Authorization header of every request.
    @Volatile var authToken: String = ""

    // Long-lived refresh JWT — sent to `/auth/refresh` when an access call
    // hits 401, so the user doesn't get bounced to the login screen as
    // long as the refresh is still alive (30 days from issue, server side).
    @Volatile var refreshToken: String = ""

    // Called (on an OkHttp background thread) when refresh fails or the
    // server returns 401 for a non-recoverable reason (no refresh token,
    // refresh itself is invalid). Consumers should post to the main
    // thread before mutating UI/state.
    @Volatile var onUnauthorized: (() -> Unit)? = null

    // Called after a successful silent refresh so the host activity can
    // persist the new pair of tokens into its DataStore-backed preferences.
    @Volatile var onTokensRefreshed: ((access: String, refresh: String) -> Unit)? = null

    private val refreshLock = Any()
    private val gson = Gson()

    // Lightweight OkHttp client used only for `/auth/refresh` — it skips
    // the auth interceptor (which would recurse on us) and the
    // reachability gate (refresh should fire even when other calls just
    // got 401 from a stale access token).
    @Volatile private var refreshHttpClient: OkHttpClient? = null

    private fun Request.withAuth(token: String): Request =
        if (token.isNotBlank()) newBuilder().header("Authorization", "Bearer $token").build()
        else this

    // Exchanges the current refresh token for a fresh pair. Returns the
    // new access token on success, or null if refresh wasn't possible
    // (no refresh token stored, server said 401/4xx, or network failed).
    // Synchronised so concurrent 401s share a single refresh attempt.
    @Suppress("ReturnCount")
    private fun tryRefresh(baseUrl: String): String? {
        val current = refreshToken
        if (current.isBlank()) return null
        synchronized(refreshLock) {
            // Another thread may have rotated tokens while we were
            // waiting on the lock — short-circuit if so.
            if (refreshToken != current) return authToken.ifBlank { null }
            val client = refreshHttpClient ?: return null
            val bodyJson = gson.toJson(RefreshRequest(current))
            val req = Request.Builder()
                .url(baseUrl + "auth/refresh")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    val payload = resp.body?.string() ?: return null
                    val parsed = gson.fromJson(payload, RefreshResponse::class.java) ?: return null
                    if (parsed.token.isBlank()) return null
                    authToken = parsed.token
                    if (parsed.refreshToken.isNotBlank()) {
                        refreshToken = parsed.refreshToken
                    }
                    onTokensRefreshed?.invoke(parsed.token, parsed.refreshToken)
                    parsed.token
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    fun getService(serverUrl: String): ApiService {
        val apiUrl = buildApiUrl(serverUrl)
        if (service == null || apiUrl != currentBaseUrl) {
            synchronized(this) {
                if (service == null || apiUrl != currentBaseUrl) {
                    currentBaseUrl = apiUrl
                    service = buildService(apiUrl)
                }
            }
        }
        return service!!
    }

    // Invalidates the cached service so the next call to getService() rebuilds it.
    // Call this after changing authToken if you want a fresh OkHttp client.
    fun reset() {
        synchronized(this) { service = null }
    }

    private fun buildApiUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("/api")) "$trimmed/" else "$trimmed/api/"
    }

    private fun buildService(baseUrl: String): ApiService {
        // Single host (the budget API) so the default per-host cap of 5 is the real ceiling.
        // Bump it so concurrent screen loads don't queue against each other.
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 10 }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .addInterceptor { chain ->
                // Reachability gate: short-circuit when a fast TCP probe has
                // determined the server is unreachable (Connection refused /
                // reset / unknown host). Avoids 30s callTimeout pile-ups on
                // cold start when the API is down.
                when (ReachabilityGate.awaitFirstBlocking()) {
                    ReachabilityGate.State.Offline ->
                        throw IOException("offline: reachability gate")
                    else -> Unit
                }
                chain.proceed(chain.request())
            }
            .addInterceptor { chain ->
                // First attempt with current access token.
                val firstReq = chain.request().withAuth(authToken)
                var response = chain.proceed(firstReq)

                // 401 on a non-refresh call → silently exchange refresh
                // token for a new pair, then replay the original request
                // once. /auth/refresh itself short-circuits to avoid loops.
                if (response.code == 401 && !firstReq.url.encodedPath.endsWith("/auth/refresh")) {
                    val newAccess = tryRefresh(baseUrl)
                    if (newAccess != null) {
                        response.close()
                        response = chain.proceed(chain.request().withAuth(newAccess))
                    } else if (refreshToken.isNotBlank() || authToken.isNotBlank()) {
                        // Refresh failed — wipe and signal the host.
                        authToken = ""
                        refreshToken = ""
                        onUnauthorized?.invoke()
                    } else {
                        onUnauthorized?.invoke()
                    }
                }
                response
            }
            // connectTimeout 5s was too aggressive on cold start: the very first
            // request often needs a fresh TCP+TLS handshake while the device is
            // still ramping up, which manifested as Statistics never loading
            // until the user manually retried.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        refreshHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
