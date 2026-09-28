package org.multipaz.openid4vci.idv

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

/**
 * Evidence submitted by the wallet to `/idv/evidence`: the passport chip read plus a selfie for
 * server-side face matching.
 *
 * The wallet has already checked the OCR'd MRZ against DG1's MRZ and run on-device liveness
 * before submitting this; the server re-derives every identity claim from [dg1] itself rather
 * than trusting anything the wallet asserts about the holder.
 *
 * @property sessionId the id returned by `/idv/start`, binding this evidence to that session's
 *   attested client.
 * @property sod the raw bytes of `EF.SOD`.
 * @property dg1 the raw bytes of `EF.DG1`.
 * @property dg2 the raw bytes of `EF.DG2`.
 * @property selfie the liveness-checked selfie image, for server-side face matching against DG2.
 */
@CborSerializable
data class PassportEvidence(
    val sessionId: String,
    val sod: ByteString,
    val dg1: ByteString,
    val dg2: ByteString,
    val selfie: ByteString,
) {
    companion object
}
