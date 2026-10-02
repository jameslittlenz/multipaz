package org.multipaz.idv.cms

/**
 * Rewrites BER indefinite lengths as definite ones, so DER-only decoders can read the result.
 *
 * Passports may encode the outer layers of `EF.SOD` (the `ContentInfo` and its `[0]` content) with
 * indefinite lengths, which BER allows: a length octet of `0x80`, with the contents closed by two
 * zero octets. Elements with definite lengths are copied byte for byte, so anything signed inside
 * them keeps its exact encoding.
 */
internal object BerLengths {
    private const val MAX_DEPTH = 32

    /** Returns [bytes] with every indefinite length made definite. */
    fun toDefinite(bytes: ByteArray): ByteArray {
        val output = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val (element, next) = readElement(bytes, offset, 0)
            output.add(element)
            offset = next
        }
        return concat(output)
    }

    /** Returns the contents of the single element in [bytes], with indefinite lengths made definite. */
    fun contents(bytes: ByteArray): ByteArray {
        val (element, end) = readElement(bytes, 0, 0)
        if (end != bytes.size) throw CmsException("Unexpected data after the BER element")
        // The element now has a definite length: skip its identifier and length octets.
        var position = 1
        if (element[0].toInt() and 0x1F == 0x1F) {
            while (element[position++].toInt() and 0x80 != 0) Unit
        }
        val lengthOctet = element[position++].toInt() and 0xFF
        if (lengthOctet and 0x80 != 0) position += lengthOctet and 0x7F
        return element.copyOfRange(position, element.size)
    }

    /** Reads the element at [offset], returning it with definite lengths and the offset after it. */
    private fun readElement(bytes: ByteArray, offset: Int, depth: Int): Pair<ByteArray, Int> {
        if (depth > MAX_DEPTH) throw CmsException("BER nesting too deep")
        var position = offset
        val tagStart = position
        val firstTagOctet = byteAt(bytes, position++)
        if (firstTagOctet and 0x1F == 0x1F) {
            while (byteAt(bytes, position++) and 0x80 != 0) Unit
        }
        val identifier = bytes.copyOfRange(tagStart, position)
        val lengthOctet = byteAt(bytes, position++)
        if (lengthOctet != 0x80) {
            // Definite length: copy the whole element as it is.
            val length = if (lengthOctet and 0x80 == 0) {
                lengthOctet
            } else {
                val count = lengthOctet and 0x7F
                if (count > 4) throw CmsException("BER length too long")
                var value = 0L
                repeat(count) { value = (value shl 8) or byteAt(bytes, position++).toLong() }
                if (value > Int.MAX_VALUE) throw CmsException("BER length too large")
                value.toInt()
            }
            val end = position + length
            if (end > bytes.size || end < position) throw CmsException("BER element runs past the end")
            return Pair(bytes.copyOfRange(tagStart, end), end)
        }
        if (firstTagOctet and 0x20 == 0) {
            throw CmsException("Indefinite length on a primitive BER element")
        }
        // Indefinite length: read children up to the end-of-contents octets (00 00).
        val children = mutableListOf<ByteArray>()
        while (true) {
            if (byteAt(bytes, position) == 0 && byteAt(bytes, position + 1) == 0) {
                position += 2
                break
            }
            val (child, next) = readElement(bytes, position, depth + 1)
            children.add(child)
            position = next
        }
        val content = concat(children)
        return Pair(concat(listOf(identifier, definiteLength(content.size), content)), position)
    }

    private fun byteAt(bytes: ByteArray, position: Int): Int {
        if (position >= bytes.size) throw CmsException("BER data ends unexpectedly")
        return bytes[position].toInt() and 0xFF
    }

    private fun definiteLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        length < 0x1000000 -> byteArrayOf(0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf(
            0x84.toByte(), (length shr 24).toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte()
        )
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val result = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(result, offset)
            offset += part.size
        }
        return result
    }
}
