package org.multipaz.openid4vci.admin

import org.multipaz.crypto.Crypto
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * TOTP, RFC 6238 on top of HMAC-SHA1 (RFC 4226): 30-second steps, 6-digit codes. This is the
 * profile every mainstream authenticator app supports, and is what Component E of
 * `docs/validatopia/PLAN.md` means by "mandatory TOTP" for admin accounts.
 */
object Totp {
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val SECRET_BYTES = 20
    private val CODE_PATTERN = Regex("\\d{$DIGITS}")

    /** A fresh, random 160-bit shared secret for a new TOTP enrollment. */
    fun generateSecret(random: Random = Crypto.secureRandom): ByteArray = random.nextBytes(SECRET_BYTES)

    /**
     * An `otpauth://totp/...` URI for manual entry into an authenticator app.
     *
     * Rendering this as a scannable QR code is deferred (see the M3 summary): doing so through
     * the server's existing generic `/qr` endpoint would put the secret in a URL that ends up in
     * nginx's access log, and there's no in-page QR library on this no-build-step admin site.
     * [accountLabel] and [issuer] identify the account/server in the authenticator app's list.
     */
    fun provisioningUri(secret: ByteArray, accountLabel: String, issuer: String): String {
        val encodedIssuer = urlEncode(issuer)
        val encodedLabel = urlEncode(accountLabel)
        return "otpauth://totp/$encodedIssuer:$encodedLabel" +
            "?secret=${Base32.encode(secret)}" +
            "&issuer=$encodedIssuer" +
            "&algorithm=SHA1" +
            "&digits=$DIGITS" +
            "&period=$STEP_SECONDS"
    }

    /** Verifies [code] against [secret], allowing [driftSteps] steps of clock drift either way. */
    fun verifyCode(secret: ByteArray, code: String, epochSeconds: Long, driftSteps: Int = 1): Boolean {
        if (!CODE_PATTERN.matches(code)) {
            return false
        }
        val counter = epochSeconds / STEP_SECONDS
        var matched = false
        // Check every window regardless of an early match, so the time taken does not reveal
        // which (if any) window matched.
        for (drift in -driftSteps..driftSteps) {
            if (constantTimeEquals(generateCode(secret, counter + drift), code)) {
                matched = true
            }
        }
        return matched
    }

    private fun generateCode(secret: ByteArray, counter: Long): String {
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(data)
        val offset = hash[hash.size - 1].toInt() and 0xf
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        val otp = binary % 1_000_000
        return otp.toString().padStart(DIGITS, '0')
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) {
            return false
        }
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
