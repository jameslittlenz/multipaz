package org.multipaz.samples.validatopia.shared.wallet

import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tstr
import org.multipaz.claim.MdocClaim
import org.multipaz.documenttype.knowntypes.PhotoID
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailsRowsTest {
    private fun claim(name: String, value: DataItem) = MdocClaim(
        displayName = name,
        attribute = null,
        docType = PhotoID.PHOTO_ID_DOCTYPE,
        namespaceName = PhotoID.ISO_23220_2_NAMESPACE,
        dataElementName = name,
        value = value,
    )

    @Test
    fun ageThresholdsAreGroupedWhereTheFirstOneWas() {
        val familyName = claim("family_name", Tstr("Holder"))
        val over21 = claim("age_over_21", Simple.TRUE)
        val birthDate = claim("birth_date", Tstr("2000-01-01"))
        val over65 = claim("age_over_65", Simple.FALSE)
        val over18 = claim("age_over_18", Simple.TRUE)

        assertEquals(
            listOf(
                DetailsRow.Single(familyName),
                DetailsRow.AgeOverGroup(
                    listOf(AgeOver(18, isOver = true), AgeOver(21, isOver = true), AgeOver(65, isOver = false))
                ),
                DetailsRow.Single(birthDate),
            ),
            DetailsRow.of(listOf(familyName, over21, birthDate, over65, over18)),
        )
    }

    @Test
    fun noGroupWithoutAgeThresholds() {
        val familyName = claim("family_name", Tstr("Holder"))
        val ageInYears = claim("age_in_years", Tstr("26"))
        assertEquals(
            listOf(DetailsRow.Single(familyName), DetailsRow.Single(ageInYears)),
            DetailsRow.of(listOf(familyName, ageInYears)),
        )
    }

    @Test
    fun nonBooleanAgeThresholdStaysOnItsOwn() {
        val malformed = claim("age_over_18", Tstr("yes"))
        val over21 = claim("age_over_21", Simple.TRUE)
        assertEquals(
            listOf(DetailsRow.Single(malformed), DetailsRow.AgeOverGroup(listOf(AgeOver(21, isOver = true)))),
            DetailsRow.of(listOf(malformed, over21)),
        )
    }
}
