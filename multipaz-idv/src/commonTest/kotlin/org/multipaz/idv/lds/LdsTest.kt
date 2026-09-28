package org.multipaz.idv.lds

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LdsTest {
    @Test
    fun dg1RoundTrip() {
        val mrz = "P<XVASAMPLE<<JORDAN<<<<<<<<<<<<<<<<<<<<<<<<<\n" +
                "PA12345670XVA9003049M3003040<<<<<<<<<<<<<<08"
        val dg1 = Lds.buildDG1(mrz)
        assertEquals(mrz, Lds.parseDG1(dg1))
    }

    @Test
    fun dg1RejectsWrongTag() {
        // A bare OCTET STRING, not wrapped in the application-tagged EF.DG1 template.
        val notDg1 = byteArrayOf(0x04, 0x03, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte())
        assertFailsWith<LdsException> { Lds.parseDG1(notDg1) }
    }

    @Test
    fun dg2RoundTrip() {
        val portrait = "fake JPEG bytes for a test portrait".encodeToByteArray()
        val dg2 = Lds.buildDG2(portrait)
        assertContentEquals(portrait, Lds.parseDG2(dg2))
    }

    @Test
    fun dg2RoundTripWithEmptyImage() {
        val dg2 = Lds.buildDG2(ByteArray(0))
        assertContentEquals(ByteArray(0), Lds.parseDG2(dg2))
    }
}
