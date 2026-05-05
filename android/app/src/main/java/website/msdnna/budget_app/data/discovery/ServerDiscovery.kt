package website.msdnna.budget_app.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import website.msdnna.budget_app.data.model.HealthResponse
import website.msdnna.budget_app.data.model.VersionResponse
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

data class DiscoveredServer(
    val host: String,        // "192.168.1.42:8082"
    val ssl: Boolean,
    val apiVersion: String,  // empty if /api/version was unreachable but /api/health passed
) {
    val url: String get() = "${if (ssl) "https" else "http"}://$host"
}

object ServerDiscovery {
    // (port, ssl) pairs probed per host, ordered by likelihood for this app's
    // typical deployments. Plain http on 8082 is the default behind frontend nginx.
    private val PROBES: List<Pair<Int, Boolean>> = listOf(
        8082 to false,
        8080 to false,
        80   to false,
        443  to true,
        8443 to true,
    )

    private const val EXPECTED_APP = "msdnna-budget"
    private const val MAX_CONCURRENT = 40

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(800,  TimeUnit.MILLISECONDS)
        .writeTimeout(500, TimeUnit.MILLISECONDS)
        .callTimeout(2000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val gson = Gson()

    /**
     * Computes the list of host IPs to probe in the active network's IPv4 subnet.
     * Falls back to a /24 around the device IP when the actual prefix is wider
     * than /24, since scanning a /16 (65k hosts) is impractical.
     * Returns null if no IPv4 link is available (e.g. mobile-only, no network).
     */
    fun localHosts(context: Context): List<String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = cm.activeNetwork ?: return null
        val props = cm.getLinkProperties(active) ?: return null
        val link = props.linkAddresses.firstOrNull { la ->
            val a = la.address
            a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress
        } ?: return null

        val ipBytes = link.address.address
        val ipInt = ((ipBytes[0].toInt() and 0xff) shl 24) or
                    ((ipBytes[1].toInt() and 0xff) shl 16) or
                    ((ipBytes[2].toInt() and 0xff) shl 8)  or
                    (ipBytes[3].toInt() and 0xff)

        val prefix = link.prefixLength.coerceIn(16, 32)
        val effectivePrefix = if (prefix < 24) 24 else prefix
        val mask = if (effectivePrefix == 0) 0 else (-1 shl (32 - effectivePrefix))
        val network = ipInt and mask
        val broadcast = network or mask.inv()

        val out = ArrayList<String>(254)
        var h = network + 1
        while (h < broadcast) {
            if (h != ipInt) out += intToIp(h)
            h++
        }
        return out
    }

    private fun intToIp(v: Int): String =
        "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"

    /**
     * Emits each reachable budget-go server as it's discovered. Cancel by
     * cancelling the collecting coroutine.
     */
    fun discover(context: Context): Flow<DiscoveredServer> = channelFlow {
        val hosts = localHosts(context) ?: return@channelFlow
        val semaphore = Semaphore(MAX_CONCURRENT)
        val jobs = ArrayList<Job>(hosts.size * PROBES.size)
        for (host in hosts) {
            for ((port, ssl) in PROBES) {
                jobs += launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        val result = probe(host, port, ssl)
                        if (result != null) send(result)
                    }
                }
            }
        }
        jobs.joinAll()
    }.flowOn(Dispatchers.IO)

    private fun probe(host: String, port: Int, ssl: Boolean): DiscoveredServer? {
        val baseUrl = "${if (ssl) "https" else "http"}://$host:$port"
        val healthReq = Request.Builder().url("$baseUrl/api/health").get().build()
        val health: HealthResponse = try {
            client.newCall(healthReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body.string()
                if (body.isEmpty()) return null
                gson.fromJson(body, HealthResponse::class.java) ?: return null
            }
        } catch (_: Exception) {
            return null
        }
        if (!health.ok || health.app != EXPECTED_APP) return null

        val verReq = Request.Builder().url("$baseUrl/api/version").get().build()
        val apiVersion: String = try {
            client.newCall(verReq).execute().use { resp ->
                if (!resp.isSuccessful) ""
                else gson.fromJson(resp.body.string(), VersionResponse::class.java)
                    ?.api.orEmpty()
            }
        } catch (_: Exception) { "" }

        return DiscoveredServer(host = "$host:$port", ssl = ssl, apiVersion = apiVersion)
    }
}
