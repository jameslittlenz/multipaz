package org.multipaz.idv.backend.persona

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A dummy test identity, matching `docs/validatopia/PLAN.md`'s `personas.json` schema.
 *
 * @property sex ISO/IEC 5218 code: `0` not known, `1` male, `2` female.
 */
@Serializable
data class Persona(
    val id: String,
    @SerialName("given_name") val givenName: String,
    @SerialName("family_name") val familyName: String,
    @SerialName("birth_date") val birthDate: LocalDate,
    val sex: Int,
    val nationality: String,
    @SerialName("document_number") val documentNumber: String,
    @SerialName("expiry_date") val expiryDate: LocalDate,
    val portrait: String,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("resident_address") val residentAddress: String? = null,
)
