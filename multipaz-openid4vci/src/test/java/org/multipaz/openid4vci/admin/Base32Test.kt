package org.multipaz.openid4vci.admin

import org.junit.Assert.assertEquals
import org.junit.Test

class Base32Test {
    // RFC 4648 test vectors (section 10), which use base32 on ASCII "f", "fo", "foo", ...
    @Test
    fun matchesRfc4648TestVectors() {
        assertEquals("", Base32.encode("".encodeToByteArray()))
        assertEquals("MY======", Base32.encode("f".encodeToByteArray()))
        assertEquals("MZXQ====", Base32.encode("fo".encodeToByteArray()))
        assertEquals("MZXW6===", Base32.encode("foo".encodeToByteArray()))
        assertEquals("MZXW6YQ=", Base32.encode("foob".encodeToByteArray()))
        assertEquals("MZXW6YTB", Base32.encode("fooba".encodeToByteArray()))
        assertEquals("MZXW6YTBOI======", Base32.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun decodeInvertsEncode() {
        for (input in listOf("", "f", "fo", "foo", "foob", "fooba", "foobar", "TOTP shared secret bytes")) {
            val bytes = input.encodeToByteArray()
            assertEquals(input, Base32.decode(Base32.encode(bytes)).decodeToString())
        }
    }
}
