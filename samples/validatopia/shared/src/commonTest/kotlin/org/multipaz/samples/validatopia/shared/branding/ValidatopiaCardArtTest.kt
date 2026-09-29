package org.multipaz.samples.validatopia.shared.branding

import org.multipaz.documenttype.knowntypes.AgeVerification
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.utopia.knowntypes.Loyalty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidatopiaCardArtTest {
    // WCAG 2.2 AA requires 4.5:1 for normal text. The holder's name is drawn in the title color.
    private val minTextContrast = 4.5

    @Test
    fun titlesMeetWcagAaContrast() {
        for (style in ValidatopiaCardArt.all) {
            for ((name, color) in listOf("title" to style.title, "subtitle" to style.subtitle)) {
                val ratio = ContrastRatio.of(color, style.background)
                assertTrue(ratio >= minTextContrast, "$name $color on ${style.background} has contrast $ratio")
            }
        }
    }

    @Test
    fun eachDocumentTypeHasItsOwnColors() {
        val styles = listOf(
            PhotoID.PHOTO_ID_DOCTYPE,
            DrivingLicense.MDL_DOCTYPE,
            Loyalty.LOYALTY_DOCTYPE,
            AgeVerification.AV_DOCTYPE,
        ).map { ValidatopiaCardArt.styleFor(it) }
        assertEquals(
            listOf(ValidatopiaCardArt.photoId, ValidatopiaCardArt.driverLicence,
                ValidatopiaCardArt.gymMembership, ValidatopiaCardArt.ageVerification),
            styles,
        )
        assertEquals(4, styles.map { it.background to it.hills }.toSet().size)
        assertEquals(ValidatopiaCardArt.unknown, ValidatopiaCardArt.styleFor("org.example.unknown"))
        assertEquals(ValidatopiaCardArt.unknown, ValidatopiaCardArt.styleFor(null as String?))
    }

    @Test
    fun shortNameIsFirstGivenNameAndFamilyInitial() {
        assertEquals("Claudia H.", ValidatopiaCardArt.shortName("Claudia", "Hill"))
        assertEquals("Mary S.", ValidatopiaCardArt.shortName("Mary Anne", "smith"))
        assertEquals("Test T.", ValidatopiaCardArt.shortName("TEST", "TRAVELLER"))
        assertEquals("Claudia", ValidatopiaCardArt.shortName("Claudia", null))
        assertEquals("Claudia", ValidatopiaCardArt.shortName(" Claudia ", " "))
        assertNull(ValidatopiaCardArt.shortName(" ", "Hill"))
    }
}
