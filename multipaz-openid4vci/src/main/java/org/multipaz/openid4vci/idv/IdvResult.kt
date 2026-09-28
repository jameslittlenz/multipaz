package org.multipaz.openid4vci.idv

import org.multipaz.cbor.DataItem

/**
 * The outcome of [IdentityProofing.proof] or [IdentityProofing.proofPersona].
 *
 * @property accepted whether a Photo ID can be issued from this evidence.
 * @property flags concerns found during proofing (e.g. passive-authentication or face-match
 *   failures); a non-empty list doesn't necessarily mean [accepted] is `false` (e.g. demo mode
 *   may accept an untrusted CSCA but still flag it).
 * @property faceScore the face-match similarity score, if a selfie was matched against a portrait.
 * @property systemOfRecordData CBOR data ready for [org.multipaz.openid4vci.credential.CredentialFactory.mint],
 *   present iff [accepted] is `true`.
 */
data class IdvResult(
    val accepted: Boolean,
    val flags: List<String>,
    val faceScore: Double? = null,
    val systemOfRecordData: DataItem? = null,
)
