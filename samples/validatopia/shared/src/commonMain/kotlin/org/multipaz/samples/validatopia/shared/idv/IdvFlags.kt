package org.multipaz.samples.validatopia.shared.idv

/** Plain-language explanations of the flags the issuer reports for a passport check. */
object IdvFlags {
    /** Explains [flag] to the passport's holder. Unknown flags are shown as they are. */
    fun describe(flag: String): String = when (flag) {
        "FACE_MATCH_BELOW_THRESHOLD" ->
            "Your selfie didn't match the photo on the passport chip closely enough. Try again in even light, " +
                "facing the camera, without glasses or a hat."
        "NO_FACE_IN_SELFIE" -> "The issuer couldn't find a face in your selfie."
        "NO_FACE_IN_PORTRAIT" -> "The issuer couldn't find a face in the passport chip's photo."
        "SELFIE_UNREADABLE" -> "The issuer couldn't read your selfie."
        "PORTRAIT_UNREADABLE", "PORTRAIT_UNAVAILABLE" -> "The issuer couldn't read the photo on the passport chip."
        "FACE_MATCHER_UNAVAILABLE" -> "The issuer can't compare faces at the moment."
        "DOCUMENT_EXPIRED" -> "The passport has expired."
        "UNTRUSTED_CSCA" -> "The issuer doesn't trust the country that issued this passport."
        "SIGNATURE_INVALID", "CHAIN_INVALID", "HASH_MISMATCH", "MALFORMED_SOD" ->
            "The passport chip's data didn't pass the issuer's authenticity check."
        "DOCUMENT_SIGNER_VALIDITY" -> "The passport chip's signing certificate isn't valid at the moment."
        "ALGORITHM_UNSUPPORTED_ON_DEVICE" -> "The passport chip uses cryptography the issuer doesn't support."
        "MALFORMED_DG1" -> "The issuer couldn't read the passport chip's personal details."
        "ACTIVE_AUTH_NOT_IMPLEMENTED" -> "The issuer requires a chip check this wallet doesn't support yet."
        "PASSPORT_ISSUANCE_DISABLED" -> "The issuer isn't accepting passports at the moment."
        else -> flag
    }
}
