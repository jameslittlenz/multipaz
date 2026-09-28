package org.multipaz.openid4vci.server

import org.multipaz.openid4vci.admin.AdminAuth
import org.multipaz.openid4vci.credential.CredentialFactoryAgeVerification
import org.multipaz.openid4vci.credential.CredentialFactoryDigitalPaymentCredential
import org.multipaz.openid4vci.credential.CredentialFactoryDigitalPaymentCredentialSdJwt
import org.multipaz.openid4vci.credential.CredentialFactoryMdl
import org.multipaz.openid4vci.credential.CredentialFactoryMdocPid
import org.multipaz.openid4vci.credential.CredentialFactoryRegistry
import org.multipaz.openid4vci.credential.CredentialFactorySdjwtPid
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaLoyalty
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaMovieTicket
import org.multipaz.openid4vci.credential.CredentialFactoryUtopiaNaturalization
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.server.common.ServerConfiguration
import org.multipaz.server.common.runServer

/**
 * Main entry point to launch the server.
 *
 * Build and start the server using
 *
 * ```
 * ./gradlew multipaz-openid4vci-server:run
 * ```
 *
 * or with a System of Record back-end:
 *
 * ```
 * ./gradlew multipaz-openid4vci-server:run --args="-param enrollment_server_url=http://localhost:8004 -param system_of_record_url=http://localhost:8004"
 * ```
 */
class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runServer(
                args = args,
                // Superseded by AdminAuth's Argon2id/TOTP account system (Component E); the old
                // single shared 'admin_password' is no longer read anywhere in this module.
                needAdminPassword = false,
                checkConfiguration = ::checkConfiguration,
                environmentInitializer = {
                    AdminAuth.ensureBootstrapped(BackendEnvironment.getInterface(Configuration::class)!!)

                    val credentialFactoryRegistry = CredentialFactoryRegistry(
                        listOf(
                            CredentialFactoryMdl(),
                            CredentialFactoryMdocPid(),
                            CredentialFactorySdjwtPid(),
                            CredentialFactoryUtopiaNaturalization(),
                            CredentialFactoryUtopiaMovieTicket(),
                            CredentialFactoryAgeVerification(),
                            CredentialFactoryUtopiaLoyalty(),
                            CredentialFactoryDigitalPaymentCredential(),
                            CredentialFactoryDigitalPaymentCredentialSdJwt(),
                        )
                    )
                    credentialFactoryRegistry.initialize()
                    add(CredentialFactoryRegistry::class, credentialFactoryRegistry)
                }
            ) { serverEnvironment ->
                configureRouting(serverEnvironment)
            }
        }

        private fun checkConfiguration(configuration: ServerConfiguration) {
            val supportClientAssertion = configuration.getValue("support_client_assertion") != "false"
            val supportClientAttestation = configuration.getValue("support_client_attestation") != "false"
            if (!supportClientAssertion && !supportClientAttestation) {
                throw IllegalArgumentException("No client authentication methods supported")
            }
        }
    }
}