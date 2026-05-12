package website.msdnna.budget_app.data.sync

import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReachabilityGateTest {

    @Test
    fun `parseHostPort defaults http to 80`() {
        val addr = ReachabilityGate.parseHostPort("http://example.com")
        assertThat(addr).isNotNull()
        assertThat(addr!!.hostString).isEqualTo("example.com")
        assertThat(addr.port).isEqualTo(80)
    }

    @Test
    fun `parseHostPort defaults https to 443`() {
        val addr = ReachabilityGate.parseHostPort("https://example.com")
        assertThat(addr!!.port).isEqualTo(443)
    }

    @Test
    fun `parseHostPort respects explicit port`() {
        val addr = ReachabilityGate.parseHostPort("http://10.0.0.1:8082")
        assertThat(addr!!.hostString).isEqualTo("10.0.0.1")
        assertThat(addr.port).isEqualTo(8082)
    }

    @Test
    fun `parseHostPort returns null for garbage input`() {
        assertThat(ReachabilityGate.parseHostPort("not a url")).isNull()
        assertThat(ReachabilityGate.parseHostPort("")).isNull()
    }

    @Test
    fun `probeOnce returns Online when target port accepts connections`() {
        // Открываем локальный ServerSocket и ждём connect — TCP-handshake завершится.
        val server = ServerSocket(0)
        try {
            val state = ReachabilityGate.probeOnce("http://127.0.0.1:${server.localPort}")
            assertThat(state).isEqualTo(ReachabilityGate.State.Online)
        } finally {
            server.close()
        }
    }

    @Test
    fun `probeOnce returns Offline when port is refused`() {
        // Свободный порт без слушателя → connect refused → Offline.
        val freePort = ServerSocket(0).use { it.localPort }
        val state = ReachabilityGate.probeOnce("http://127.0.0.1:$freePort")
        assertThat(state).isEqualTo(ReachabilityGate.State.Offline)
    }

    @Test
    fun `probeOnce returns Offline on unknown host`() {
        val state = ReachabilityGate.probeOnce("http://this-host-does-not-resolve.invalid:8080")
        assertThat(state).isEqualTo(ReachabilityGate.State.Offline)
    }

    @Test
    fun `setServerUrl resets state to Unknown when URL changes`() {
        // Ставим заведомо мёртвый адрес — состояние подбросится setServerUrl.
        ReachabilityGate.setServerUrl("http://127.0.0.1:1/")
        // Меняем — должен вернуться в Unknown.
        ReachabilityGate.setServerUrl("http://example.org:8082")
        assertThat(ReachabilityGate.state.value).isEqualTo(ReachabilityGate.State.Unknown)
    }
}
