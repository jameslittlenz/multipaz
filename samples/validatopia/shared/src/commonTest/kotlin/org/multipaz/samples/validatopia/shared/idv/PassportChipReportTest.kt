package org.multipaz.samples.validatopia.shared.idv

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.idv.mrz.MrzSex
import org.multipaz.idv.pa.CscaStore
import org.multipaz.idv.synthetic.SyntheticPassportFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassportChipReportTest {
    @Test
    fun describesTheChipWithoutPersonalData() = runTest {
        val cscaKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val csca = SyntheticPassportFactory.createCsca(cscaKey, Algorithm.ESP384)
        val dsKey = Crypto.createEcPrivateKey(EcCurve.P256)
        val ds = SyntheticPassportFactory.createDocumentSigner(csca, cscaKey, Algorithm.ESP384, dsKey)
        val passport = SyntheticPassportFactory.createPassport(
            documentSignerCertificate = ds,
            documentSignerPrivateKey = dsKey,
            documentSignerSignatureAlgorithm = Algorithm.ESP256,
            issuingState = "NZL",
            primaryIdentifier = "HILL",
            secondaryIdentifier = "CLAUDIA",
            documentNumber = "LA123456",
            nationality = "NZL",
            birthDate = LocalDate(2002, 1, 1),
            sex = MrzSex.FEMALE,
            expiryDate = LocalDate(2035, 1, 1),
            portraitBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3),
        )
        val read = PassportChipRead(ChipAccessProtocol.PACE, passport.sod, passport.dg1, passport.dg2, listOf(1, 2, 14))
        val report = PassportChipReport.create(read, CscaStore.from(listOf(csca)))
        val rows = report.rows.toMap()

        assertEquals("PACE", rows["Access control"])
        assertEquals("present", rows["DG14 (Chip Authentication)"])
        assertEquals("absent", rows["DG15 (Active Authentication)"])
        assertEquals("passed", rows["Passive authentication"])
        assertEquals("EC P256", rows["Document Signer key"])
        assertTrue(rows["Matching CSCA"]!!.endsWith("(EC P384)"))
        assertEquals("JPEG, declared JPEG", rows["DG2 image format"])

        val text = report.toText()
        for (personal in listOf("HILL", "CLAUDIA", "LA123456", "020101", "350101")) {
            assertFalse(personal in text, "report contains '$personal'")
        }
    }
}
