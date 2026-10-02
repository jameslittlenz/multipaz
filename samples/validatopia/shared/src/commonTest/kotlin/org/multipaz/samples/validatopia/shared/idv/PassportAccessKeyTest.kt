package org.multipaz.samples.validatopia.shared.idv

import kotlinx.datetime.LocalDate
import org.multipaz.idv.mrz.Mrz
import org.multipaz.idv.mrz.MrzSex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PassportAccessKeyTest {
    // The ICAO 9303 part 4 specimen passport.
    private val specimenLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val specimenLine2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    @Test
    fun findsTheSecondLineAmongOtherText() {
        val ocr = "PASSPORT\nNew Zealand\n$specimenLine1\n$specimenLine2\n"
        val key = PassportAccessKey.fromOcrText(ocr)!!
        assertEquals("L898902C3", key.documentNumber)
        assertEquals("740812", key.birthDate)
        assertEquals("120415", key.expiryDate)
        assertEquals("UTO", key.nationality)
        assertEquals(specimenLine2, key.mrzLine2)
    }

    @Test
    fun toleratesSpacesGuillemetsAndLettersReadForDigits() {
        // OCR read "<" as "«", inserted spaces, and read 0 as O and 1 as I in the dates.
        val misread = "L898902C36UTO74O8I22F12O4159ZE184226B«<<<<1O"
        val key = PassportAccessKey.fromOcrText(misread.chunked(11).joinToString(" "))!!
        assertEquals(specimenLine2, key.mrzLine2)
    }

    @Test
    fun joinsALineSplitIntoPiecesAndUndoesMisreads() {
        // An 8-character document number, so the line has filler after it and in the personal
        // number field.
        val mrz = Mrz.buildTd3(
            documentCode = "P",
            issuingState = "NZL",
            primaryIdentifier = "HILL",
            secondaryIdentifier = "CLAUDIA",
            documentNumber = "LO123456",
            nationality = "NZL",
            birthDate = LocalDate(1980, 1, 1),
            sex = MrzSex.FEMALE,
            expiryDate = LocalDate(2030, 1, 1),
        )
        val line2 = mrz.raw.lines().last()
        // OCR read the document number's "O" as "0", its trailing filler as "K", some personal
        // number fillers as "K" and "«", then split the line in three.
        val misread = line2.replaceRange(1, 2, "0").replaceRange(8, 9, "K").replaceRange(30, 33, "K«C")
        val ocr = "P<NZLHILL<<CLAUDIA\n" + misread.substring(0, 15) + "\n" + misread.substring(15, 30) +
            " \n" + misread.substring(30)
        val key = PassportAccessKey.fromOcrText(ocr)!!
        assertEquals("LO123456", key.documentNumber)
        assertEquals("800101", key.birthDate)
        assertEquals("300101", key.expiryDate)
        assertEquals(line2, key.mrzLine2)
    }

    @Test
    fun rejectsALineWithAWrongCheckDigit() {
        assertNull(PassportAccessKey.fromOcrText(specimenLine2.replaceRange(9, 10, "5")))
        assertNull(PassportAccessKey.fromOcrText(specimenLine1))
    }

    @Test
    fun manualEntryFormatsDates() {
        val key = PassportAccessKey.fromManualEntry(" la123456 ", LocalDate(1974, 8, 12), LocalDate(2032, 4, 5))
        assertEquals("LA123456", key.documentNumber)
        assertEquals("740812", key.birthDate)
        assertEquals("320405", key.expiryDate)
        assertFailsWith<IllegalArgumentException> {
            PassportAccessKey.fromManualEntry("LA-123", LocalDate(1974, 8, 12), LocalDate(2032, 4, 5))
        }
    }

    @Test
    fun comparesWithTheChipMrz() {
        val chip = Mrz.buildTd3(
            documentCode = "P",
            issuingState = "NZL",
            primaryIdentifier = "HILL",
            secondaryIdentifier = "CLAUDIA",
            documentNumber = "LA123456",
            nationality = "NZL",
            birthDate = LocalDate(2002, 1, 1),
            sex = MrzSex.FEMALE,
            expiryDate = LocalDate(2035, 1, 1),
        )
        val scanned = PassportAccessKey.fromOcrText(chip.raw)!!
        assertTrue(scanned.mismatchesWith(chip).isEmpty())
        val typed = PassportAccessKey.fromManualEntry("LA123456", LocalDate(2002, 1, 1), LocalDate(2035, 1, 1))
        assertTrue(typed.mismatchesWith(chip).isEmpty())
        assertEquals(listOf("nationality", "machine-readable zone"), scanned.copy(nationality = "AUS").let {
            it.copy(mrzLine2 = it.mrzLine2!!.replaceRange(10, 13, "AUS"))
        }.mismatchesWith(chip))
    }
}
