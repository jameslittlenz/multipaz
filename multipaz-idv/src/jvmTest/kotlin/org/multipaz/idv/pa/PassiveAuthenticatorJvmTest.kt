package org.multipaz.idv.pa

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.setUpBouncyCastleIfNeeded
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RSASSA-PSS coverage that only runs on the JVM: see the comment above
 * [PassiveAuthenticatorTest.endToEndRsa] for why the JVM/iOS behavior differs for PSS
 * specifically.
 */
class PassiveAuthenticatorJvmTest {
    @BeforeTest
    fun setup() = setUpBouncyCastleIfNeeded()

    @Test
    fun endToEndRsaPss() = runTest {
        val cscaKey = Crypto.createRsaPrivateKey(2048)
        val csca = SyntheticPassportFactory.createCsca(cscaKey, Algorithm.PS256)
        val dsKey = Crypto.createRsaPrivateKey(2048)
        val ds = SyntheticPassportFactory.createDocumentSigner(
            cscaCertificate = csca,
            cscaPrivateKey = cscaKey,
            cscaSignatureAlgorithm = Algorithm.PS256,
            documentSignerPrivateKey = dsKey,
        )
        val passport = SyntheticPassportFactory.createPassport(
            documentSignerCertificate = ds,
            documentSignerPrivateKey = dsKey,
            documentSignerSignatureAlgorithm = Algorithm.PS256,
            issuingState = "XVA",
            primaryIdentifier = "SAMPLE",
            secondaryIdentifier = "JORDAN",
            documentNumber = "PA1234567",
            nationality = "XVA",
            birthDate = LocalDate(1990, 1, 1),
            sex = MrzSex.UNSPECIFIED,
            expiryDate = LocalDate(2030, 1, 1),
        )
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(csca)),
        )
        assertTrue(result.trusted, "Expected trusted, got flags ${result.flags}")
    }
}
