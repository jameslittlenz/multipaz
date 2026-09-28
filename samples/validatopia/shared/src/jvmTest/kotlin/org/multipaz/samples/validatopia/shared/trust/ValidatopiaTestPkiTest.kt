package org.multipaz.samples.validatopia.shared.trust

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.multipaz.asn1.OID
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509Cert
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards against [ValidatopiaTestPki] drifting from the key set the issuer is deployed with,
 * `multipaz-server-deployment/validatopia-test-keys/` (both are written by
 * `ValidatopiaTestKeysGenerator`).
 */
class ValidatopiaTestPkiTest {
    private val keysDir = File("../../../multipaz-server-deployment/validatopia-test-keys")

    private fun pem(name: String) = X509Cert.fromPem(File(keysDir, name).readText())

    @Test
    fun bundledAnchorsMatchDeployedKeys() {
        assertEquals(pem("validatopia_iaca.pem"), ValidatopiaTrust.iacaCertificate)
        assertEquals(pem("validatopia_test_csca.pem"), ValidatopiaTrust.testCscaCertificate)
        assertEquals(pem("validatopia_reader_root.pem"), ValidatopiaTrust.readerRootCertificate)
        assertEquals(
            AsymmetricKey.parseExplicit(File(keysDir, "validatopia-reader-key.json").readText()),
            ValidatopiaTrust.readerKey(),
        )
    }

    @Test
    fun serverConfigurationUsesTheSameAnchors() {
        val config = Json.parseToJsonElement(File(keysDir, "validatopia-keys.conf").readText()).jsonObject
        val issuerKey = AsymmetricKey.parseExplicit(
            config.getValue("root_identities").jsonObject.getValue("credential_signing")
        ) as AsymmetricKey.X509Certified
        assertEquals(ValidatopiaTrust.iacaCertificate, issuerKey.certChain.certificates.single())
        val csca = config.getValue("validatopia_test_csca").jsonObject.getValue("csca_cert").jsonPrimitive.content
        assertEquals(ValidatopiaTrust.testCscaCertificate, X509Cert.fromPem(csca))
    }

    @Test
    fun readerCertificateChainsToBundledRoot() {
        val chain = ValidatopiaTrust.readerKey().certChain.certificates
        assertEquals(2, chain.size)
        assertEquals(ValidatopiaTrust.readerRootCertificate, chain.last())
        assertEquals(
            ValidatopiaTrust.VERIFIER_DISPLAY_NAME,
            chain.first().subject.components[OID.COMMON_NAME.oid]?.value,
        )
    }
}
