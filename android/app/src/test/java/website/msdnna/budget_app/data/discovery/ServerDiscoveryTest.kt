package website.msdnna.budget_app.data.discovery

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerDiscoveryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `probe returns DiscoveredServer when health is ok and version is present`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"app":"msdnna-budget"}"""))
        server.enqueue(MockResponse().setBody("""{"api":"1.15.0","android_latest":"1.30.0","android_min_required":"1.0.0"}"""))

        val result = ServerDiscovery.probe(host = server.hostName, port = server.port, ssl = false)

        assertThat(result).isNotNull()
        assertThat(result!!.host).isEqualTo("${server.hostName}:${server.port}")
        assertThat(result.ssl).isFalse()
        assertThat(result.apiVersion).isEqualTo("1.15.0")

        val healthCall: RecordedRequest = server.takeRequest()
        assertThat(healthCall.path).isEqualTo("/api/health")
        val versionCall: RecordedRequest = server.takeRequest()
        assertThat(versionCall.path).isEqualTo("/api/version")
    }

    @Test
    fun `probe still returns server when version endpoint fails`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"app":"msdnna-budget"}"""))
        server.enqueue(MockResponse().setResponseCode(500))

        val result = ServerDiscovery.probe(server.hostName, server.port, ssl = false)
        assertThat(result).isNotNull()
        assertThat(result!!.apiVersion).isEqualTo("")
    }

    @Test
    fun `probe rejects unknown app`() {
        server.enqueue(MockResponse().setBody("""{"ok":true,"app":"other-app"}"""))

        val result = ServerDiscovery.probe(server.hostName, server.port, ssl = false)
        assertThat(result).isNull()
    }

    @Test
    fun `probe rejects when ok=false`() {
        server.enqueue(MockResponse().setBody("""{"ok":false,"app":"msdnna-budget"}"""))

        val result = ServerDiscovery.probe(server.hostName, server.port, ssl = false)
        assertThat(result).isNull()
    }

    @Test
    fun `probe returns null on 404`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = ServerDiscovery.probe(server.hostName, server.port, ssl = false)
        assertThat(result).isNull()
    }

    @Test
    fun `probe returns null on empty body`() {
        server.enqueue(MockResponse().setBody(""))

        val result = ServerDiscovery.probe(server.hostName, server.port, ssl = false)
        assertThat(result).isNull()
    }

    @Test
    fun `probe returns null when port is unreachable`() {
        // Не задаём enqueue — но и не валидно: лучше использовать порт, где
        // нет слушателя. Грузим mock на другом, а probe — на стороне.
        server.shutdown() // освободить порт
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        val result = ServerDiscovery.probe("127.0.0.1", freePort, ssl = false)
        assertThat(result).isNull()
        // Снова поднимем mock чтобы @After.shutdown() не упал — но Mock уже
        // выключен. Достаточно: переинициализируем, чтобы tearDown был чистым.
        server = MockWebServer().also { it.start() }
    }
}
