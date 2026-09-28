package org.multipaz.openid4vci.admin

/**
 * RFC 4648 base32 encoding, used to render a [Totp] secret in the form authenticator apps
 * (Google Authenticator, Authy, 1Password, ...) expect for manual entry.
 */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val output = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0L
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toLong() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                output.append(ALPHABET[((buffer shr bitsLeft) and 0x1f).toInt()])
            }
        }
        if (bitsLeft > 0) {
            output.append(ALPHABET[((buffer shl (5 - bitsLeft)) and 0x1f).toInt()])
        }
        while (output.length % 8 != 0) {
            output.append('=')
        }
        return output.toString()
    }

    /** Inverse of [encode]. Used by tests that need to derive a TOTP code from a displayed secret. */
    fun decode(encoded: String): ByteArray {
        val clean = encoded.trim().trimEnd('=').uppercase()
        var buffer = 0L
        var bitsLeft = 0
        val bytes = mutableListOf<Byte>()
        for (c in clean) {
            val index = ALPHABET.indexOf(c)
            require(index >= 0) { "Invalid base32 character '$c'" }
            buffer = (buffer shl 5) or index.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                bytes.add(((buffer shr bitsLeft) and 0xff).toByte())
            }
        }
        return bytes.toByteArray()
    }
}
