package org.multipaz.openid4vci.server

import org.multipaz.idv.backend.PassportIdentityProofing
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.backend.face.FakeFaceMatcher
import org.multipaz.idv.backend.persona.PersonaStore
import org.multipaz.idv.backend.persona.PersonaStoreException
import org.multipaz.idv.backend.persona.PersonaStorePersistence
import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.openid4vci.credential.CredentialFactoryPhotoId
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.idv.IdentityProofing
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.runServer
import org.multipaz.util.Logger
import java.io.File

/**
 * Entry point for the Validatopia Photo ID issuer profile.
 *
 * Build and run using
 *
 * ```
 * ./gradlew :multipaz-openid4vci-server:run -PmainClass=org.multipaz.openid4vci.server.MainValidatopia --args="..."
 * ```
 *
 * (`-PmainClass` overrides `build.gradle.kts`'s `application.mainClass`, which otherwise runs
 * [Main]; a `-param main_class=...` program argument, as this doc comment previously suggested,
 * doesn't do anything — `ServerConfiguration` never reads such a key.)
 *
 * Registers only [CredentialFactoryPhotoId] (no PID/mDL/Utopia factories, since several of them
 * don't require key attestation) and wires up [IdentityProofing] with [FakeFaceMatcher] — the
 * real ONNX-based face matcher and its model-download pipeline are deferred (see
 * `docs/validatopia/PLAN.md`'s Component C).
 *
 * Personas are loaded from [PersonaStorePersistence] (populated by an admin upload, or by
 * [seedPersonasIfNeeded] from `personas_seed_dir` on first boot — see `start-servers.sh`, which
 * points that at the placeholder personas baked into the container image, Component G); if
 * neither has ever run, [PersonaStore.EMPTY] makes `/idv/personas` correctly report none rather
 * than fail.
 */
class MainValidatopia {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(
                args = args,
                // Superseded by AdminAuth's Argon2id/TOTP account system (Component E); the old
                // single shared 'admin_password' is no longer read anywhere in this module.
                needAdminPassword = false,
                checkConfiguration = {},
                environmentInitializer = {
                    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
                    // Refuses to proceed (see AdminAuth.ensureBootstrapped's doc comment) if this
                    // would bootstrap an admin account with an empty password on a non-loopback
                    // base_url; start-servers.sh additionally refuses to launch the JVM at all in
                    // that case.
                    AdminAuth.ensureBootstrapped(configuration)
                    seedPersonasIfNeeded(configuration)

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

        /**
         * Seeds the persona store from `personas_seed_dir` (a directory containing `personas.json`
         * and its referenced portrait JPEGs) if nothing has been persisted yet. No-ops if the
         * config value isn't set, the directory is unreadable, or personas already exist — an
         * admin's own upload always wins and is never overwritten by this.
         */
        private suspend fun seedPersonasIfNeeded(configuration: Configuration) {
            if (PersonaStorePersistence.load() != null) {
                return
            }
            val seedDir = configuration.getValue("personas_seed_dir")?.let { File(it) } ?: return
            val personasJsonFile = File(seedDir, "personas.json")
            if (!personasJsonFile.canRead()) {
                return
            }
            val portraits = seedDir.listFiles { file -> file.extension.lowercase() in JPEG_EXTENSIONS }
                ?.associate { it.name to it.readBytes() }
                ?: emptyMap()
            try {
                PersonaStorePersistence.save(personasJsonFile.readText(), portraits)
                Logger.i(TAG, "Seeded personas from '${seedDir.absolutePath}'")
            } catch (e: PersonaStoreException) {
                Logger.e(TAG, "Not seeding personas from '${seedDir.absolutePath}': ${e.message}")
            }
        }

        private const val TAG = "MainValidatopia"
        private val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
    }
}
