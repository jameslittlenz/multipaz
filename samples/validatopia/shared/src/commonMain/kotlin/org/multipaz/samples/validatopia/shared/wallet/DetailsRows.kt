package org.multipaz.samples.validatopia.shared.wallet

import org.multipaz.cbor.Simple
import org.multipaz.claim.Claim
import org.multipaz.claim.MdocClaim

/**
 * One `age_over_NN` claim: whether the holder is [age] or older.
 *
 * @property age the threshold, the `NN` in the data element's name.
 * @property isOver the claim's value.
 */
data class AgeOver(val age: Int, val isOver: Boolean)

/** A row of the holder's own details list: one claim, or all the age thresholds together. */
sealed class DetailsRow {
    /** A claim shown on its own, as its display name and rendered value. */
    data class Single(val claim: Claim) : DetailsRow()

    /** Every `age_over_NN` claim, youngest threshold first, shown as one field of badges. */
    data class AgeOverGroup(val ages: List<AgeOver>) : DetailsRow()

    companion object {
        private val AGE_OVER = Regex("age_over_(\\d{2})")

        /**
         * [claims] in order, with the `age_over_NN` claims gathered into one [AgeOverGroup] where
         * the first of them was. An `age_over_NN` claim whose value isn't a boolean stays a
         * [Single], so a malformed value is still shown rather than dropped.
         */
        fun of(claims: List<Claim>): List<DetailsRow> {
            val ages = claims.associateWith { it.ageOver() }
            val group = AgeOverGroup(ages.values.filterNotNull().sortedBy { it.age })
            var groupPlaced = false
            return buildList {
                for (claim in claims) {
                    if (ages[claim] == null) {
                        add(Single(claim))
                    } else if (!groupPlaced) {
                        add(group)
                        groupPlaced = true
                    }
                }
            }
        }

        private fun Claim.ageOver(): AgeOver? {
            if (this !is MdocClaim) return null
            val age = AGE_OVER.matchEntire(dataElementName)?.groupValues?.get(1)?.toInt() ?: return null
            return when (value) {
                Simple.TRUE -> AgeOver(age, isOver = true)
                Simple.FALSE -> AgeOver(age, isOver = false)
                else -> null
            }
        }
    }
}
