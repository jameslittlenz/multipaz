package org.multipaz.samples.validatopia.shared.idv

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzTd3

/**
 * What a passport chip's access control (BAC or PACE with the MRZ) needs: the document number,
 * date of birth and expiry date as printed in the MRZ.
 *
 * @property documentNumber the document number, without filler characters.
 * @property birthDate the date of birth as `YYMMDD`.
 * @property expiryDate the expiry date as `YYMMDD`.
 * @property nationality the holder's nationality (ICAO alpha-3), if read from a scanned MRZ.
 * @property mrzLine2 the second MRZ line, if read from a scanned MRZ.
 */
data class PassportAccessKey(
    val documentNumber: String,
    val birthDate: String,
    val expiryDate: String,
    val nationality: String? = null,
    val mrzLine2: String? = null,
) {
    /**
     * Compares this key, read from the printed MRZ, with the MRZ stored on the chip (DG1).
     *
     * @return the names of the fields that differ; empty if they all agree.
     */
    fun mismatchesWith(chip: MrzTd3): List<String> {
        val chipLine2 = chip.raw.lines().last()
        return buildList {
            if (documentNumber != chip.documentNumber) add("document number")
            if (birthDate != chipLine2.substring(13, 19)) add("date of birth")
            if (expiryDate != chipLine2.substring(21, 27)) add("expiry date")
            if (nationality != null && nationality != chip.nationality) add("nationality")
            if (mrzLine2 != null && mrzLine2 != chipLine2) add("machine-readable zone")
        }
    }

    companion object {
        private val MRZ_CHARACTERS = Regex("[A-Z0-9<]+")

        // OCR confusions between letters and digits.
        private val LETTER_TO_DIGIT = mapOf(
            'O' to '0', 'Q' to '0', 'D' to '0', 'U' to '0', 'I' to '1', 'L' to '1', 'T' to '1',
            'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8',
        )
        private val DIGIT_TO_LETTER = mapOf(
            '0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '6' to 'G', '8' to 'B',
        )

        // How OCR reads the MRZ's "<" filler, besides "C" and "E" (see parseWithCorrections()).
        // "K" is left alone here: it's a letter document numbers can contain.
        private val FILLER_LOOKALIKES = setOf('«', '‹', '(', '[', '{', '＜', '〈', 'く')

        private val FILLER_LETTERS = setOf('K', 'C', 'E')

        // Positions in a TD3 second line that always hold digits: the check digits and the dates.
        private val DIGIT_POSITIONS = listOf(9) + (13..19) + (21..27) + listOf(43)

        // Bounds the letter/digit combinations tried for a document number.
        private const val MAX_AMBIGUOUS_CHARACTERS = 6

        /**
         * Builds a key from details typed in by the user.
         *
         * @throws IllegalArgumentException if [documentNumber] is empty, longer than 9 characters,
         *   or has characters other than letters and digits.
         */
        fun fromManualEntry(documentNumber: String, birthDate: LocalDate, expiryDate: LocalDate): PassportAccessKey {
            val number = documentNumber.trim().uppercase().filterNot { it.isWhitespace() }
            require(number.isNotEmpty() && number.length <= 9 && number.all { it in 'A'..'Z' || it in '0'..'9' }) {
                "A passport number is 1 to 9 letters and digits"
            }
            return PassportAccessKey(number, yymmdd(birthDate), yymmdd(expiryDate))
        }

        /**
         * Finds a passport's (TD3) second MRZ line in OCR output and returns its key, or `null` if
         * nothing passes all of the line's check digits.
         *
         * Only the second line is needed: it carries everything access control uses, and all of it
         * is covered by check digits, so a misread can't slip through. Names, on the first line,
         * come from the chip instead.
         *
         * OCR often splits the line into pieces, reads the `<` filler as another character, or
         * confuses letters and digits, so this tries every 44-character stretch of the text with
         * those misreadings undone, keeping only one whose check digits all agree.
         */
        fun fromOcrText(text: String): PassportAccessKey? {
            val lines = text.lines().map { normalize(it) }.filter { it.isNotEmpty() }
            // Each line by itself, then the whole text joined, for a line OCR split in pieces.
            val candidates = lines + lines.joinToString("")
            for (candidate in candidates) {
                for (start in 0..candidate.length - Mrz.LINE_LENGTH) {
                    val window = candidate.substring(start, start + Mrz.LINE_LENGTH)
                    parseWithCorrections(window)?.let { return it }
                }
            }
            return null
        }

        private fun normalize(line: String): String =
            line.uppercase().filterNot { it.isWhitespace() }.map { if (it in FILLER_LOOKALIKES) '<' else it }.joinToString("")

        private fun parseWithCorrections(window: String): PassportAccessKey? {
            val chars = window.toCharArray()
            for (position in DIGIT_POSITIONS) {
                LETTER_TO_DIGIT[chars[position]]?.let { chars[position] = it }
            }
            // The personal number field and its check digit are usually all filler; "K", "C" and
            // "E" there are misread fillers when most of the rest of the field is filler too.
            val personal = 28..42
            if (personal.count { chars[it] == '<' } >= personal.count() / 2) {
                for (position in personal) {
                    if (chars[position] in FILLER_LETTERS) chars[position] = '<'
                }
            }
            // A document number shorter than 9 characters ends in filler, often misread as "K".
            var end = 8
            while (end > 0 && chars[end] == 'K') end--
            val fillerFixed = chars.copyOf()
            for (position in end + 1..8) fillerFixed[position] = '<'
            // Filler first: at position 8 the check digits weigh "K" (20) and "<" (0) alike, so they
            // can't tell them apart, and a trailing "K" is far more often misread filler.
            for (line in listOf(fillerFixed.concatToString(), chars.concatToString()).distinct()) {
                if (!MRZ_CHARACTERS.matches(line)) {
                    continue
                }
                parseLine2(line)?.let { return it }
                parseWithDocumentNumberVariants(line)?.let { return it }
            }
            return null
        }

        /** Retries [line] with each letter/digit reading of its document number's ambiguous characters. */
        private fun parseWithDocumentNumberVariants(line: String): PassportAccessKey? {
            val ambiguous = (0 until 9).filter { line[it] in LETTER_TO_DIGIT || line[it] in DIGIT_TO_LETTER }
            if (ambiguous.isEmpty() || ambiguous.size > MAX_AMBIGUOUS_CHARACTERS) {
                return null
            }
            for (mask in 1 until (1 shl ambiguous.size)) {
                val chars = line.toCharArray()
                for ((bit, position) in ambiguous.withIndex()) {
                    if (mask and (1 shl bit) != 0) {
                        val c = chars[position]
                        chars[position] = LETTER_TO_DIGIT[c] ?: DIGIT_TO_LETTER[c] ?: c
                    }
                }
                parseLine2(chars.concatToString())?.let { return it }
            }
            return null
        }

        private fun parseLine2(line: String): PassportAccessKey? {
            val documentNumber = line.substring(0, 9)
            val birthDate = line.substring(13, 19)
            val expiryDate = line.substring(21, 27)
            val composite = line.substring(0, 10) + line.substring(13, 20) + line.substring(21, 43)
            val valid = Mrz.checkDigit(documentNumber) == line[9] &&
                Mrz.checkDigit(birthDate) == line[19] &&
                Mrz.checkDigit(expiryDate) == line[27] &&
                Mrz.checkDigit(composite) == line[43] &&
                birthDate.all { it.isDigit() } && expiryDate.all { it.isDigit() }
            if (!valid) {
                return null
            }
            return PassportAccessKey(
                documentNumber = documentNumber.trimEnd('<'),
                birthDate = birthDate,
                expiryDate = expiryDate,
                nationality = line.substring(10, 13),
                mrzLine2 = line,
            )
        }

        private fun yymmdd(date: LocalDate): String =
            (date.year % 100).toString().padStart(2, '0') +
                date.month.number.toString().padStart(2, '0') +
                date.day.toString().padStart(2, '0')
    }
}
