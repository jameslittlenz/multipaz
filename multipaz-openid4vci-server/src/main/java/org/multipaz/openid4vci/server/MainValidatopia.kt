package org.multipaz.openid4vci.server

import org.multipaz.idv.backend.PassportIdentityProofing
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FakeFaceMatcher
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.openid4vci.credential.CredentialFactoryPhotoId
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.runServer

/**
 * Entry point for the Validatopia Photo ID issuer profile.
 *
 * Build and run using
 *
 * ```
 * ./gradlew multipaz-openid4vci-server:run --args="-param main_class=org.multipaz.openid4vci.server.MainValidatopia"
 * ```
 *
 * Registers only [CredentialFactoryPhotoId] (no PID/mDL/Utopia factories, since several of them
 * don't require key attestation) and wires up [IdentityProofing] with [FakeFaceMatcher] — the
 * real ONNX-based face matcher and its model-download pipeline are deferred (see
 * `docs/validatopia/PLAN.md`'s Component C). Shipping `personas.json` and portraits from
 * `/app/data/personas/` (Component G, the container profile) is deferred to M3, so this profile
 * starts with no personas; [PersonaStore.EMPTY] makes `/idv/personas` correctly report none rather
 * than fail.
 */
class MainValidatopia {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(
                args = args,
                needAdminPassword = true,
                checkConfiguration = {},
                environmentInitializer = {
                    val credentialFactoryRegistry = CredentialFactoryRegistry(
                        listOf(CredentialFactoryPhotoId())
                    )
                    credentialFactoryRegistry.initialize()
                    add(CredentialFactoryRegistry::class, credentialFactoryRegistry)

                    val testCsca = ValidatopiaTestCsca.getOrCreate()
                    add(
                        IdentityProofing::class,
                        PassportIdentityProofing(
                            faceMatcher = FakeFaceMatcher(),
                            testCsca = testCsca,
                            personaStore = PersonaStore.EMPTY,
                        )
                    )
                }
            ) { serverEnvironment ->
                configureRouting(serverEnvironment)
            }
        }
    }
}
