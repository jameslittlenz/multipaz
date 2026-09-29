package org.multipaz.samples.validatopia.shared.wallet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.multipaz.document.Document
import org.multipaz.provisioning.ProvisioningModel
import org.multipaz.provisioning.openid4vci.OpenID4VCIBackend
import org.multipaz.provisioning.openid4vci.OpenID4VCIClientPreferences
import org.multipaz.util.Logger

/**
 * Issues the documents that come with a Photo ID: the Driver Licence, Gym Membership and Age
 * Verification the issuer offers alongside it after the same identity proofing.
 *
 * The Photo ID itself is redeemed on the provisioning model the wallet's UI follows. The other
 * offers are redeemed one after another on [backgroundProvisioningModel], a second model that no
 * UI follows: the provisioning sheets cancel their model shortly after each document is issued,
 * which would cancel the next redemption too.
 *
 * @param backgroundProvisioningModel a provisioning model used only by this class.
 */
class ValidatopiaIssuance(private val backgroundProvisioningModel: ProvisioningModel) {
    /** Progress in issuing the documents that come with a Photo ID. */
    sealed class State {
        /** Nothing is being issued, and the last batch succeeded. */
        data object Idle : State()

        /** [remaining] documents are still to be issued. */
        data class Issuing(val remaining: Int) : State()

        /** Some documents couldn't be issued; [message] says how many and why. */
        data class Failed(val message: String) : State()
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    private val lock = Mutex()

    /** The current progress, for the home screen. */
    val state: StateFlow<State> get() = mutableState.asStateFlow()

    /**
     * Waits for [photoId] to be issued, then redeems [offers] one after another. If the Photo ID
     * isn't issued (the user cancelled, or it failed and the provisioning UI said why), nothing
     * else is. A failure to issue one of [offers] doesn't stop the others.
     *
     * @param photoId the Photo ID being redeemed on the provisioning model the UI follows.
     * @param offers the offers that came with it, after the Photo ID's own.
     */
    @Throws(CancellationException::class)
    suspend fun issueAfterPhotoId(
        photoId: Deferred<Document>,
        offers: List<String>,
        clientPreferences: OpenID4VCIClientPreferences,
        backend: OpenID4VCIBackend,
    ) {
        try {
            photoId.await()
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) throw e
            return
        }
        if (offers.isEmpty()) {
            return
        }
        lock.withLock {
            val failures = mutableListOf<String>()
            for ((index, offer) in offers.withIndex()) {
                mutableState.value = State.Issuing(remaining = offers.size - index)
                try {
                    backgroundProvisioningModel.launchOpenID4VCIProvisioning(
                        offerUri = offer,
                        clientPreferences = clientPreferences,
                        backend = backend,
                    ).await()
                } catch (e: Exception) {
                    if (!currentCoroutineContext().isActive) {
                        backgroundProvisioningModel.cancel()
                        mutableState.value = State.Idle
                        throw e
                    }
                    Logger.e(TAG, "Issuing document ${index + 1} of ${offers.size} failed", e)
                    failures.add(e.message ?: e.toString())
                }
            }
            mutableState.value = if (failures.isEmpty()) {
                State.Idle
            } else {
                State.Failed("Couldn't add ${failures.size} of ${offers.size} documents: ${failures.first()}")
            }
        }
    }

    /** Clears a [State.Failed] once the user has seen it. */
    fun dismissFailure() {
        if (mutableState.value is State.Failed) {
            mutableState.value = State.Idle
        }
    }

    private companion object {
        const val TAG = "ValidatopiaIssuance"
    }
}
