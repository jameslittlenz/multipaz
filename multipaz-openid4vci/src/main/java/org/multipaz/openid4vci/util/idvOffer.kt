package org.multipaz.openid4vci.util

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.openid4vci.credential.CredentialFactory
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.idv.IdvResult
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.handler.SimpleCipher
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

private const val IDV_OFFER_URL_SCHEMA = "haip-vci"

/**
 * Creates a pre-authorized offer for every registered credential configuration that is
 * [CredentialFactory.offeredAfterIdentityProofing], in registration order, each carrying
 * [result]'s already-verified system-of-record data (encrypted at rest via [SimpleCipher], per
 * `IssuanceState.systemOfRecordData`). Each offer names a single configuration, since wallets
 * redeem only the first `credential_configuration_ids` entry of an offer.
 *
 * As with the generic `preauthorizedOffer()` flow, `clientId` and `clientAttestationKey` are left
 * unset here and are captured from the first `/token` redemption instead (see the
 * `initialPreauthorized` handling in `request/token.kt`) — the offer itself, once returned only to
 * the wallet that completed identity proofing, is what's single-use and short-lived.
 *
 * @param result must have `accepted == true` and non-null `systemOfRecordData`.
 */
suspend fun createIdvOffers(
    result: IdvResult,
    offerTtlSeconds: Long,
): List<String> {
    check(result.accepted && result.systemOfRecordData != null) {
        "createIdvOffers requires an accepted IdvResult with system-of-record data"
    }
    val cipher = BackendEnvironment.getInterface(SimpleCipher::class)!!
    val encryptedData = ByteString(cipher.encrypt(Cbor.encode(result.systemOfRecordData!!)))
    val registry = BackendEnvironment.getInterface(CredentialFactoryRegistry::class)!!
    val expiresIn = offerTtlSeconds.seconds
    val offers = mutableListOf<String>()
    for ((configId, factory) in registry.byId) {
        if (!factory.offeredAfterIdentityProofing) {
            continue
        }
        val state = IssuanceState(
            clientId = null,
            scope = factory.scope,
            clientAttestationKey = null,
            dpopKey = null,
            redirectUri = null,
            codeChallenge = null,
            configurationId = configId,
            authorized = Clock.System.now(),
            systemOfRecordData = encryptedData,
        )
        val id = IssuanceState.createIssuanceState(state, Clock.System.now() + expiresIn)
        offers.add(generatePreauthorizedOffer(IDV_OFFER_URL_SCHEMA, id, state, expiresIn))
    }
    return offers
}
