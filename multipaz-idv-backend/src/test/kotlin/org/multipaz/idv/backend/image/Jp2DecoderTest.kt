package org.multipaz.idv.backend.image

import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class Jp2DecoderTest {
    @Test
    fun jpegPassesThroughUnchanged() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        assertSame(jpeg, Jp2Decoder.toJpeg(jpeg))
    }

    @Test
    fun jpeg2000IsRejectedRatherThanMisrendered() {
        val jp2 = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A, 1, 2, 3
        )
        assertFailsWith<Jp2DecoderException> { Jp2Decoder.toJpeg(jp2) }
    }

    @Test
    fun unknownFormatIsRejected() {
        assertFailsWith<Jp2DecoderException> { Jp2Decoder.toJpeg(byteArrayOf(1, 2, 3, 4)) }
    }
}
