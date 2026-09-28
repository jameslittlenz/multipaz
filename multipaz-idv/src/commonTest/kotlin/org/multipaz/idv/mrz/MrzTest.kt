package org.multipaz.idv.mrz

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MrzTest {
    // The canonical ICAO 9303 Part 4 worked example (fictional "Utopia" issuer/nationality "UTO").
    private val icaoExampleLine1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private val icaoExampleLine2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    private val icaoExampleToday = LocalDate(2015, 6, 1)

    @Test
    fun checkDigitMatchesIcaoExample() {
        assertEquals('6', Mrz.checkDigit("L898902C3"))
        assertEquals('2', Mrz.checkDigit("740812"))
        assertEquals('9', Mrz.checkDigit("120415"))
        assertEquals('1', Mrz.checkDigit("ZE184226B<<<<<"))
        assertEquals('0', Mrz.checkDigit("L898902C3674081221204159ZE184226B<<<<<1"))
    }

    @Test
    fun parsesIcaoExample() {
        val mrz = Mrz.parseTd3("$icaoExampleLine1\n$icaoExampleLine2", today = icaoExampleToday)
        assertEquals("P", mrz.documentCode)
        assertEquals("UTO", mrz.issuingState)
        assertEquals("ERIKSSON", mrz.primaryIdentifier)
        assertEquals("ANNA MARIA", mrz.secondaryIdentifier)
        assertEquals("L898902C3", mrz.documentNumber)
        assertEquals("UTO", mrz.nationality)
        assertEquals(LocalDate(1974, 8, 12), mrz.birthDate)
        assertEquals(MrzSex.FEMALE, mrz.sex)
        assertEquals(LocalDate(2012, 4, 15), mrz.expiryDate)
        assertEquals("ZE184226B", mrz.personalNumber)
    }

    @Test
    fun parsesSingleLineForm() {
        val mrz = Mrz.parseTd3(icaoExampleLine1 + icaoExampleLine2, today = icaoExampleToday)
        assertEquals("ERIKSSON", mrz.primaryIdentifier)
    }

    @Test
    fun rejectsTamperedCheckDigit() {
        val tampered = icaoExampleLine2.replaceRange(9, 10, "9")
        assertFailsWith<MrzException> { Mrz.parseTd3("$icaoExampleLine1\n$tampered", today = icaoExampleToday) }
    }

    @Test
    fun buildAndParseRoundTrip() {
        val built = Mrz.buildTd3(
            documentCode = "P",
            issuingState = "XVA",
            primaryIdentifier = "SAMPLE",
            secondaryIdentifier = "JORDAN TAYLOR",
            documentNumber = "PA1234567",
            nationality = "XVA",
            birthDate = LocalDate(1990, 3, 4),
            sex = MrzSex.UNSPECIFIED,
            expiryDate = LocalDate(2030, 3, 4),
        )
        val reparsed = Mrz.parseTd3(built.raw, today = LocalDate(2024, 1, 1))
        assertEquals(built.documentCode, reparsed.documentCode)
        assertEquals(built.issuingState, reparsed.issuingState)
        assertEquals(built.primaryIdentifier, reparsed.primaryIdentifier)
        assertEquals(built.secondaryIdentifier, reparsed.secondaryIdentifier)
        assertEquals(built.documentNumber, reparsed.documentNumber)
        assertEquals(built.nationality, reparsed.nationality)
        assertEquals(built.birthDate, reparsed.birthDate)
        assertEquals(built.sex, reparsed.sex)
        assertEquals(built.expiryDate, reparsed.expiryDate)
    }

    @Test
    fun resolveBirthYearNeverProducesAFutureDate() {
        val today = LocalDate(2026, 6, 1)
        assertEquals(2020, Mrz.resolveBirthYear(20, today))
        assertEquals(1999, Mrz.resolveBirthYear(99, today))
    }

    @Test
    fun resolveExpiryYearRollsOverWhenTooFarInThePast() {
        val today = LocalDate(2030, 1, 1)
        assertEquals(2029, Mrz.resolveExpiryYear(29, today))
        // A raw reading of 2001 would be 29 years in the past; that's implausible for an expiry
        // date, so it must actually mean 2101.
        assertEquals(2101, Mrz.resolveExpiryYear(1, today))
    }
}
