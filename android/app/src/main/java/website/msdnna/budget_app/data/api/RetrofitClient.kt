package website.msdnna.budget_app.data.api

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    @Volatile private var service: ApiService? = null
    @Volatile private var currentBaseUrl: String = ""

    // Set this to inject the JWT token into every request.
    @Volatile var authToken: String = ""

    // Called (on an OkHttp background thread) when any request returns 401.
    // Consumers must post to the main thread themselves if they update UI/state.
    @Volatile var onUnauthorized: (() -> Unit)? = null

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
                val token = authToken
                val request = if (token.isNotBlank()) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                val response = chain.proceed(request)
                if (response.code == 401) {
                    onUnauthorized?.invoke()
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

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
