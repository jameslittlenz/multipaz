package org.multipaz.samples.validatopia.shared.idv

import kotlinx.datetime.LocalDate
import org.multipaz.idv.backend.csca.ValidatopiaTestCsca
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import org.multipaz.samples.validatopia.shared.runWithPassportTestIssuer
import org.multipaz.samples.validatopia.shared.runWithTestIssuer
import org.multipaz.securearea.software.SoftwareSecureArea
import org.multipaz.storage.ephemeral.EphemeralStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PassportIdvClientTest {
    @Test
    fun passportEvidenceGetsOneOfferPerDocument() = runWithPassportTestIssuer { issuerUrl, httpClient, testCsca ->
        val idvClient = IdvClient(issuerUrl, httpClient, DevWalletBackend.create(), SoftwareSecureArea.create(EphemeralStorage()))
        val sessionId = idvClient.startPassportSession()
        val offers = idvClient.submitPassportEvidence(sessionId, chipRead(testCsca, LocalDate(2035, 1, 1)), SELFIE)
        assertEquals(4, offers.size)
    }

    @Test
    fun rejectionCarriesTheIssuersFlags() = runWithPassportTestIssuer { issuerUrl, httpClient, testCsca ->
        val idvClient = IdvClient(issuerUrl, httpClient, DevWalletBackend.create(), SoftwareSecureArea.create(EphemeralStorage()))
        val sessionId = idvClient.startPassportSession()
        val error = assertFailsWith<IdvRejectedException> {
            idvClient.submitPassportEvidence(sessionId, chipRead(testCsca, LocalDate(2020, 1, 1)), SELFIE)
        }
        assertEquals(listOf("DOCUMENT_EXPIRED"), error.flags)
    }

    @Test
    fun passportPathSwitchedOffIsUnavailable() = runWithTestIssuer { issuerUrl, httpClient ->
        val idvClient = IdvClient(issuerUrl, httpClient, DevWalletBackend.create(), SoftwareSecureArea.create(EphemeralStorage()))
        assertFailsWith<IdvUnavailableException> { idvClient.startPassportSession() }
    }

    private suspend fun chipRead(testCsca: ValidatopiaTestCsca, expiry: LocalDate): PassportChipRead {
        val passport = SyntheticPassportFactory.createPassport(
            documentSignerCertificate = testCsca.documentSignerCertificate,
            documentSignerPrivateKey = testCsca.documentSignerPrivateKey,
            documentSignerSignatureAlgorithm = testCsca.signatureAlgorithm,
            issuingState = "NZL",
            primaryIdentifier = "HILL",
            secondaryIdentifier = "CLAUDIA",
            documentNumber = "LA123456",
            nationality = "NZL",
            birthDate = LocalDate(2002, 1, 1),
            sex = MrzSex.FEMALE,
            expiryDate = expiry,
            portraitBytes = SELFIE,
        )
        return PassportChipRead(ChipAccessProtocol.BAC, passport.sod, passport.dg1, passport.dg2, listOf(1, 2))
    }

    companion object {
        private val SELFIE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
    }
}
