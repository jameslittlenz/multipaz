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

    @Test
    fun dg2SkipsFeaturePoints() {
        val portrait = "fake JPEG bytes for a test portrait".encodeToByteArray()
        val dg2 = Lds.buildDG2(portrait, featurePointCount = 3)
        assertContentEquals(portrait, Lds.parseDG2(dg2))
    }

    @Test
    fun dg2FaceDescribesTheRecord() {
        val portrait = "fake JPEG bytes for a test portrait".encodeToByteArray()
        val face = Lds.parseDG2Face(Lds.buildDG2(portrait, featurePointCount = 2))
        assertContentEquals(portrait, face.image)
        assertEquals(1, face.templateCount)
        assertEquals(1, face.imageCount)
        assertEquals(2, face.featurePointCount)
        assertEquals(Dg2Face.IMAGE_DATA_TYPE_JPEG, face.imageDataType)
    }

    @Test
    fun dg2IgnoresBytesAfterTheFacialRecord() {
        // A second facial image, or padding, after the first one mustn't end up in the portrait.
        val portrait = "fake JPEG bytes for a test portrait".encodeToByteArray()
        val dg2 = Lds.buildDG2(portrait + byteArrayOf(9, 9, 9), featurePointCount = 1)
        val record = dg2.copyOf()
        // Shrink the facial record length (general header 14 bytes + its own 4-byte length) by 3.
        val lengthOffset = indexOfFace(record) + 14
        record[lengthOffset + 3] = (record[lengthOffset + 3] - 3).toByte()
        assertContentEquals(portrait, Lds.parseDG2(record))
    }

    @Test
    fun dg2RejectsFeaturePointsOverrunningTheRecord() {
        val dg2 = Lds.buildDG2(ByteArray(4), featurePointCount = 1)
        // Claim 200 feature points, far more than the record holds.
        val countOffset = indexOfFace(dg2) + 14 + 4
        dg2[countOffset + 1] = 200.toByte()
        assertFailsWith<LdsException> { Lds.parseDG2(dg2) }
    }

    private fun indexOfFace(dg2: ByteArray): Int {
        val magic = byteArrayOf('F'.code.toByte(), 'A'.code.toByte(), 'C'.code.toByte(), 0)
        return (0..dg2.size - magic.size).first { i -> magic.indices.all { dg2[i + it] == magic[it] } }
    }
}
