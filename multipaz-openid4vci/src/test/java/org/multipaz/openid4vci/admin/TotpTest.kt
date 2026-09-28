package org.multipaz.openid4vci.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TotpTest {
    // RFC 6238 Appendix B test vector, seconds=59, secret = ASCII "12345678901234567890"
    // (SHA1 case). The RFC's own vector is 8 digits ("94287082"); Totp.kt deliberately uses 6
    // digits (what every mainstream authenticator app expects), and 6-digit HOTP truncation is
    // just `binary mod 10^6` in place of `binary mod 10^8` — i.e. the last 6 digits of the
    // 8-digit vector, since 10^6 divides 10^8 evenly: 94287082 mod 1_000_000 == 287082.
    private val rfcSecret = "12345678901234567890".encodeToByteArray()

    @Test
    fun matchesRfc6238TestVectorTruncatedToSixDigits() {
        assertTrue(Totp.verifyCode(rfcSecret, "287082", epochSeconds = 59, driftSteps = 0))
    }

    @Test
    fun rejectsWrongCode() {
        assertFalse(Totp.verifyCode(rfcSecret, "000000", epochSeconds = 59, driftSteps = 0))
    }

    @Test
    fun rejectsNonNumericOrWrongLengthCode() {
        assertFalse(Totp.verifyCode(rfcSecret, "abcdef", epochSeconds = 59))
        assertFalse(Totp.verifyCode(rfcSecret, "12345", epochSeconds = 59))
        assertFalse(Totp.verifyCode(rfcSecret, "1234567", epochSeconds = 59))
    }

    @Test
    fun toleratesOneStepOfClockDrift() {
        val secret = Totp.generateSecret(Random(42))
        val now = 1_700_000_000L
        val code = codeAt(secret, now)
        assertTrue(Totp.verifyCode(secret, code, epochSeconds = now + 30, driftSteps = 1))
        assertTrue(Totp.verifyCode(secret, code, epochSeconds = now - 30, driftSteps = 1))
        assertFalse(Totp.verifyCode(secret, code, epochSeconds = now + 60, driftSteps = 1))
    }

    @Test
    fun provisioningUriContainsBase32SecretAndLabel() {
        val secret = Totp.generateSecret(Random(7))
        val uri = Totp.provisioningUri(secret, accountLabel = "admin", issuer = "Validatopia")
        assertTrue(uri.startsWith("otpauth://totp/Validatopia:admin"))
        assertTrue(uri.contains("secret=" + Base32.encode(secret)))
        assertTrue(uri.contains("issuer=Validatopia"))
    }

    /**
     * Computes the code for [secret] at [epochSeconds] directly (there's no public single-code
     * generator; [Totp.verifyCode] only checks a candidate). This only supports the drift test
     * above, whose point is the ±1-step window, not the HMAC-TOTP algorithm itself — that's
     * covered independently by [matchesRfc6238TestVector].
     */
    private fun codeAt(secret: ByteArray, epochSeconds: Long): String {
        val counter = epochSeconds / 30
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }
}
