package org.multipaz.idv.pa

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.PrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.idv.cms.OID_LDS_SECURITY_OBJECT
import org.multipaz.idv.cms.SignedData
import org.multipaz.idv.lds.DataGroupHash
import org.multipaz.idv.lds.Lds
import org.multipaz.idv.lds.LdsSecurityObject
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.setUpBouncyCastleIfNeeded
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.idv.synthetic.SyntheticPassportProfile
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class PassiveAuthenticatorTest {
    @BeforeTest
    fun setup() = setUpBouncyCastleIfNeeded()

    private data class Chain(
        val cscaCertificate: X509Cert,
        val documentSignerCertificate: X509Cert,
        val documentSignerPrivateKey: PrivateKey,
        val documentSignerAlgorithm: Algorithm,
    )

    private suspend fun buildChain(
        keyAlgorithm: Algorithm,
        curve: EcCurve? = null,
        keySizeBits: Int = 2048,
        documentSignerValidFrom: Instant = Clock.System.now() - 1.days,
        documentSignerValidUntil: Instant = Clock.System.now() + 1095.days,
    ): Chain {
        val cscaKey = if (curve != null) Crypto.createEcPrivateKey(curve) else Crypto.createRsaPrivateKey(keySizeBits)
        val csca = SyntheticPassportFactory.createCsca(cscaKey, keyAlgorithm)
        val dsKey = if (curve != null) Crypto.createEcPrivateKey(curve) else Crypto.createRsaPrivateKey(keySizeBits)
        val ds = SyntheticPassportFactory.createDocumentSigner(
            cscaCertificate = csca,
            cscaPrivateKey = cscaKey,
            cscaSignatureAlgorithm = keyAlgorithm,
            documentSignerPrivateKey = dsKey,
            validFrom = documentSignerValidFrom,
            validUntil = documentSignerValidUntil,
        )
        return Chain(csca, ds, dsKey, keyAlgorithm)
    }

    private suspend fun buildPassport(
        chain: Chain,
        profile: SyntheticPassportProfile = SyntheticPassportProfile.DEFAULT,
    ) = SyntheticPassportFactory.createPassport(
        documentSignerCertificate = chain.documentSignerCertificate,
        documentSignerPrivateKey = chain.documentSignerPrivateKey,
        documentSignerSignatureAlgorithm = chain.documentSignerAlgorithm,
        issuingState = "XVA",
        primaryIdentifier = "SAMPLE",
        secondaryIdentifier = "JORDAN",
        documentNumber = "PA1234567",
        nationality = "XVA",
        birthDate = LocalDate(1990, 1, 1),
        sex = MrzSex.UNSPECIFIED,
        expiryDate = LocalDate(2030, 1, 1),
        profile = profile,
    )

    private fun assertOnlyFlag(result: PassiveAuthenticationResult, flag: PassiveAuthenticationFlag) {
        assertEquals(setOf(flag), result.flags)
        assertTrue(!result.trusted)
    }

    @Test
    fun validPassportIsTrusted() = runTest {
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain)
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertTrue(result.trusted)
        assertTrue(result.flags.isEmpty())
        assertEquals(mapOf(1 to true, 2 to true), result.dataGroupHashMatches)
        assertEquals(chain.documentSignerCertificate, result.documentSignerCertificate)
    }

    @Test
    fun newZealandLayoutIsTrusted() = runTest {
        // Indefinite-length SOD, DG2 feature points and DG12-DG15 hashed alongside DG1 and DG2.
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain, SyntheticPassportProfile.NZL)
        assertEquals(0x80.toByte(), passport.sod[5])
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertTrue(result.trusted, result.details.joinToString())
        assertEquals(2, Lds.parseDG2Face(passport.dg2).featurePointCount)
    }

    @Test
    fun tamperedDg1IsFlagged() = runTest {
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain)
        val tamperedDg1 = Lds.buildDG1(Lds.parseDG1(passport.dg1).replace("SAMPLE", "TAMPER"))
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to tamperedDg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.HASH_MISMATCH)
        assertEquals(false, result.dataGroupHashMatches[1])
        assertEquals(true, result.dataGroupHashMatches[2])
    }

    @Test
    fun tamperedDg2IsFlagged() = runTest {
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain)
        val tamperedDg2 = Lds.buildDG2("a different portrait entirely".encodeToByteArray())
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to tamperedDg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.HASH_MISMATCH)
    }

    @Test
    fun wrongDocumentSignerKeyIsFlagged() = runTest {
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain)
        // Re-sign the same content with an unrelated key while still embedding the real DS
        // certificate: simulates a Document Signer certificate that doesn't match the key that
        // actually produced the signature.
        val impostorKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val ldsSecurityObject = LdsSecurityObject.build(
            Algorithm.SHA256,
            listOf(
                DataGroupHash(1, Crypto.digest(Algorithm.SHA256, passport.dg1)),
                DataGroupHash(2, Crypto.digest(Algorithm.SHA256, passport.dg2)),
            )
        )
        val forgedSod = SignedData.build(
            eContentType = OID_LDS_SECURITY_OBJECT,
            eContent = ldsSecurityObject,
            digestAlgorithm = Algorithm.SHA256,
            signerCertificate = chain.documentSignerCertificate,
            signingKey = impostorKey,
            signatureAlgorithm = Algorithm.ES256,
        )
        val result = PassiveAuthenticator.authenticate(
            sod = forgedSod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.SIGNATURE_INVALID)
    }

    @Test
    fun expiredDocumentSignerIsFlagged() = runTest {
        val now = Clock.System.now()
        val chain = buildChain(
            Algorithm.ES256, curve = EcCurve.P256,
            documentSignerValidFrom = now - 800.days,
            documentSignerValidUntil = now - 400.days,
        )
        val passport = buildPassport(chain)
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
            at = now,
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.DOCUMENT_SIGNER_VALIDITY)
    }

    @Test
    fun unknownCscaIsFlagged() = runTest {
        val chain = buildChain(Algorithm.ES256, curve = EcCurve.P256)
        val passport = buildPassport(chain)
        val unrelatedCsca = SyntheticPassportFactory.createCsca(
            Crypto.createEcPrivateKey(EcCurve.P256), Algorithm.ES256,
            subject = X500Name.fromName("CN=A Different Country's CSCA,C=ZZ"),
        )
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(unrelatedCsca)),
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.UNTRUSTED_CSCA)
    }

    private fun endToEnd(keyAlgorithm: Algorithm, curve: EcCurve? = null) = runTest {
        val chain = buildChain(keyAlgorithm, curve = curve)
        val passport = buildPassport(chain)
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
        )
        assertTrue(result.trusted, "Expected trusted for $keyAlgorithm/$curve, got flags ${result.flags}")
    }

    @Test fun endToEndEcdsaP256() = endToEnd(Algorithm.ES256, EcCurve.P256)
    @Test fun endToEndEcdsaP384() = endToEnd(Algorithm.ES384, EcCurve.P384)
    @Test fun endToEndRsa() = endToEnd(Algorithm.RS256)

    // PS256 (RSASSA-PSS) is deliberately not in this common suite: verifying it end-to-end needs
    // Document Signer -> CSCA *chain* validation to support PSS, and on iOS that goes through
    // SwiftBridge.verifySignature's OID map, which (unlike the JVM's native X509Certificate.verify
    // and unlike this library's own direct SOD-vs-DS-key check, see SignedDataTest) doesn't have an
    // RSASSA-PSS branch. See PassiveAuthenticatorJvmTest for JVM-only coverage of this combination.

    @Test
    fun endToEndEcdsaBrainpoolOnJvm() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.BRAINPOOLP256R1)) {
            println("Skipping brainpool test: platform does not support the curve")
            return@runTest
        }
        endToEnd(Algorithm.ESB256, EcCurve.BRAINPOOLP256R1)
    }

    @Test
    fun brainpoolIsFlaggedUnsupportedWhenNotAvailableOnDevice() = runTest {
        if (!Crypto.supportedCurves.contains(EcCurve.BRAINPOOLP256R1)) {
            println("Skipping: platform does not support the curve even for chain construction")
            return@runTest
        }
        val chain = buildChain(Algorithm.ESB256, curve = EcCurve.BRAINPOOLP256R1)
        val passport = buildPassport(chain)
        // Simulate iOS, where CryptoKit doesn't support brainpool curves at all.
        val iosLikeSupportedCurves = setOf(EcCurve.P256, EcCurve.P384, EcCurve.P521)
        val result = PassiveAuthenticator.authenticate(
            sod = passport.sod,
            dataGroups = mapOf(1 to passport.dg1, 2 to passport.dg2),
            cscaStore = CscaStore.from(listOf(chain.cscaCertificate)),
            supportedCurves = iosLikeSupportedCurves,
        )
        assertOnlyFlag(result, PassiveAuthenticationFlag.ALGORITHM_UNSUPPORTED_ON_DEVICE)
    }
}
