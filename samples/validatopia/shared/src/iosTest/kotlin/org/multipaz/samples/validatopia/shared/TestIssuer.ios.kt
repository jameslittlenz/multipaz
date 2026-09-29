package org.multipaz.samples.validatopia.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv

/**
 * On iOS the issuer can't run in-process (it's JVM-only), so Gradle starts a real
 * `MainValidatopia` on localhost before the simulator tests and passes its URL in
 * `VALIDATOPIA_TEST_ISSUER_URL` (see this module's `build.gradle.kts`). The simulator shares the
 * host's network, so everything on the wallet and verifier side runs natively against it.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun runWithTestIssuer(block: suspend (issuerUrl: String, httpClient: HttpClient) -> Unit) {
    val issuerUrl = getenv(ISSUER_URL_VARIABLE)?.toKString()
        ?: throw IllegalStateException(
            "$ISSUER_URL_VARIABLE isn't set. Run these tests with ./gradlew " +
                ":samples:validatopia:shared:iosSimulatorArm64Test, which starts the issuer."
        )
    runBlocking {
        HttpClient(Darwin) { followRedirects = false }.use { httpClient ->
            block(issuerUrl, httpClient)
        }
    }
}

private const val ISSUER_URL_VARIABLE = "VALIDATOPIA_TEST_ISSUER_URL"
