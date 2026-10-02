package org.multipaz.samples.validatopia.wallet.passport

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.IsoDepCardService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.LDSFileUtil
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.COMFile
import org.multipaz.samples.validatopia.shared.idv.ChipAccessProtocol
import org.multipaz.samples.validatopia.shared.idv.PassportAccessKey
import org.multipaz.samples.validatopia.shared.idv.PassportChipRead
import org.multipaz.util.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.Security

/** Reading a passport chip failed. [message] is suitable for showing to the user. */
class PassportChipException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Which part of a chip read is under way, for progress display. */
enum class ChipReadStep(val description: String) {
    WAITING("Hold the back of your phone flat against the passport's photo page"),
    CONNECTING("Connecting to the passport chip…"),
    READING_DETAILS("Reading your details…"),
    READING_PHOTO("Reading your photo…"),
    READING_SIGNATURE("Reading the chip's signature…"),
}

/**
 * Reads EF.COM, EF.SOD, EF.DG1 and EF.DG2 from a passport chip with JMRTD, unlocking the chip
 * with PACE where it supports it and Basic Access Control otherwise.
 *
 * While reading, NFC reader mode is on, which also stops the wallet from answering NFC taps as a
 * card (host card emulation); it's switched off again when [read] returns.
 */
class PassportChipReader(private val activity: Activity) {

    /**
     * Waits for a passport to be tapped and reads it.
     *
     * @param onProgress called with each step, and with the fraction of the photo read so far.
     * @throws PassportChipException if NFC is off or missing, or the chip couldn't be read.
     */
    suspend fun read(
        key: PassportAccessKey,
        onProgress: (step: ChipReadStep, photoFraction: Float) -> Unit,
    ): PassportChipRead {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: throw PassportChipException("This phone doesn't have NFC, so it can't read passport chips")
        if (!adapter.isEnabled) {
            throw PassportChipException("NFC is switched off. Switch it on in Settings, then try again")
        }
        ensureFullBouncyCastle()
        onProgress(ChipReadStep.WAITING, 0f)
        val tag = CompletableDeferred<Tag>()
        adapter.enableReaderMode(
            activity,
            { tag.complete(it) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
        try {
            val isoDep = IsoDep.get(tag.await())
                ?: throw PassportChipException("That isn't a passport chip. Try again with the photo page")
            return withContext(Dispatchers.IO) { readChip(isoDep, key, onProgress) }
        } finally {
            adapter.disableReaderMode(activity)
        }
    }

    private fun readChip(
        isoDep: IsoDep,
        key: PassportAccessKey,
        onProgress: (step: ChipReadStep, photoFraction: Float) -> Unit,
    ): PassportChipRead {
        onProgress(ChipReadStep.CONNECTING, 0f)
        // Reading DG2 takes a while, and some chips are slow to answer each command.
        isoDep.timeout = ISO_DEP_TIMEOUT_MILLIS
        val cardService = IsoDepCardService(isoDep)
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false,
        )
        try {
            service.open()
            val bacKey = BACKey(key.documentNumber, key.birthDate, key.expiryDate)
            val protocol = if (tryPace(service, bacKey)) {
                service.sendSelectApplet(true)
                ChipAccessProtocol.PACE
            } else {
                service.sendSelectApplet(false)
                try {
                    service.doBAC(bacKey)
                } catch (e: CardServiceException) {
                    throw PassportChipException(
                        "The passport chip didn't accept the passport number, date of birth and expiry date. " +
                            "Check them and try again",
                        e,
                    )
                }
                ChipAccessProtocol.BAC
            }

            onProgress(ChipReadStep.READING_DETAILS, 0f)
            val dataGroups = try {
                COMFile(service.getInputStream(PassportService.EF_COM, PassportService.DEFAULT_MAX_BLOCKSIZE))
                    .tagList.map { LDSFileUtil.lookupDataGroupNumberByTag(it) }
            } catch (e: Exception) {
                // EF.COM is deprecated in LDS 1.8; nothing here depends on it.
                Logger.w(TAG, "Couldn't read EF.COM", e)
                emptyList()
            }
            val dg1 = readFile(service, PassportService.EF_DG1) {}
            onProgress(ChipReadStep.READING_PHOTO, 0f)
            val dg2 = readFile(service, PassportService.EF_DG2) { onProgress(ChipReadStep.READING_PHOTO, it) }
            onProgress(ChipReadStep.READING_SIGNATURE, 1f)
            val sod = readFile(service, PassportService.EF_SOD) {}
            return PassportChipRead(protocol, sod, dg1, dg2, dataGroups)
        } catch (e: PassportChipException) {
            throw e
        } catch (e: CardServiceException) {
            throw PassportChipException(connectionLostMessage(isoDep), e)
        } catch (e: IOException) {
            throw PassportChipException(connectionLostMessage(isoDep), e)
        } finally {
            service.close()
        }
    }

    /** Tries PACE with each PACE protocol EF.CardAccess lists, returning `true` once one works. */
    private fun tryPace(service: PassportService, bacKey: BACKey): Boolean {
        val cardAccess = try {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        } catch (e: Exception) {
            // No EF.CardAccess: the chip only does BAC.
            Logger.i(TAG, "No EF.CardAccess, so no PACE: ${e.message}")
            return false
        }
        val paceKey = try {
            PACEKeySpec.createMRZKey(bacKey)
        } catch (e: GeneralSecurityException) {
            Logger.w(TAG, "Couldn't derive the PACE key", e)
            return false
        }
        for (info in cardAccess.securityInfos.filterIsInstance<PACEInfo>()) {
            try {
                service.doPACE(paceKey, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), info.parameterId)
                return true
            } catch (e: Exception) {
                Logger.w(TAG, "PACE with ${info.protocolOIDString} failed", e)
            }
        }
        return false
    }

    private fun readFile(service: PassportService, fileId: Short, onFraction: (Float) -> Unit): ByteArray {
        val input = service.getInputStream(fileId, PassportService.DEFAULT_MAX_BLOCKSIZE)
        val length = input.length
        val output = ByteArrayOutputStream(length)
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            if (length > 0) onFraction(output.size().toFloat() / length)
        }
        return output.toByteArray()
    }

    private fun connectionLostMessage(isoDep: IsoDep): String =
        if (isoDep.isConnected) {
            "The passport chip stopped responding. Try again"
        } else {
            "The phone lost contact with the passport chip. Keep the phone still on the photo page and try again"
        }

    companion object {
        private const val TAG = "PassportChipReader"
        private const val ISO_DEP_TIMEOUT_MILLIS = 10_000
        private const val READ_CHUNK = 1024

        /**
         * Android ships a cut-down BouncyCastle as the "BC" provider, which lacks algorithms PACE
         * needs. Replace it with the full one; the platform's other providers keep their order.
         */
        private fun ensureFullBouncyCastle() {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) {
                Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
