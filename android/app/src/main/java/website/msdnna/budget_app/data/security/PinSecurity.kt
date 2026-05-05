package website.msdnna.budget_app.data.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinSecurity {
    private const val ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 32

    data class StoredPin(val saltBase64: String, val hashBase64: String)

    fun hash(pin: String, saltBase64: String? = null): StoredPin {
        val saltBytes = if (saltBase64 != null) {
            Base64.decode(saltBase64, Base64.NO_WRAP)
        } else {
            ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        }
        val spec = PBEKeySpec(pin.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hashBytes = factory.generateSecret(spec).encoded
        return StoredPin(
            saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP),
            hashBase64 = Base64.encodeToString(hashBytes, Base64.NO_WRAP),
        )
    }

    fun verify(pin: String, storedSaltBase64: String, storedHashBase64: String): Boolean {
        val candidate = hash(pin, storedSaltBase64).hashBase64
        return constantTimeEquals(candidate, storedHashBase64)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
