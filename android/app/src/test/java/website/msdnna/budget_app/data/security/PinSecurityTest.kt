package website.msdnna.budget_app.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinSecurityTest {

    @Test
    fun `hash with reused salt produces deterministic hashBase64`() {
        val first = PinSecurity.hash("1234")
        val second = PinSecurity.hash("1234", first.saltBase64)
        assertThat(second.hashBase64).isEqualTo(first.hashBase64)
        assertThat(second.saltBase64).isEqualTo(first.saltBase64)
    }

    @Test
    fun `hash with default salt produces different salts on subsequent calls`() {
        val a = PinSecurity.hash("1234")
        val b = PinSecurity.hash("1234")
        assertThat(a.saltBase64).isNotEqualTo(b.saltBase64)
        // Different salts → different hashes даже для одного PIN.
        assertThat(a.hashBase64).isNotEqualTo(b.hashBase64)
    }

    @Test
    fun `verify accepts correct PIN`() {
        val stored = PinSecurity.hash("4321")
        assertThat(PinSecurity.verify("4321", stored.saltBase64, stored.hashBase64)).isTrue()
    }

    @Test
    fun `verify rejects wrong PIN`() {
        val stored = PinSecurity.hash("4321")
        assertThat(PinSecurity.verify("4322", stored.saltBase64, stored.hashBase64)).isFalse()
    }

    @Test
    fun `verify rejects mismatched salt`() {
        val a = PinSecurity.hash("0000")
        val b = PinSecurity.hash("0000")
        assertThat(PinSecurity.verify("0000", a.saltBase64, b.hashBase64)).isFalse()
    }
}
