package org.multipaz.samples.validatopia.shared.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.nfc.MdocHandoverType
import org.multipaz.mdoc.nfc.MdocReaderNfcHandoverOptions
import org.multipaz.mdoc.nfc.scanMdocReader
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.NfcTagReader
import org.multipaz.samples.validatopia.shared.transport.ValidatopiaTransport
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.util.Constants
import org.multipaz.util.fromBase64Url

/**
 * A Photo ID response together with what's needed to verify it.
 *
 * @property deviceResponse the holder's response.
 * @property sessionTranscript the session transcript it's bound to.
 */
class PhotoIdReadResult(
    val deviceResponse: DeviceResponse,
    val sessionTranscript: DataItem,
)

/** The holder ended the session, or sent something other than a response. */
class PhotoIdReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The ISO/IEC 18013-5 reader flow for one Photo ID request: session encryption, one
 * request/response exchange, then session termination. Engagement (QR scan or NFC tap) and
 * transport creation happen before this, in the app.
 */
object PhotoIdReader {
    /**
     * Reads a Photo ID from a wallet showing an ISO/IEC 18013-5 QR engagement (`mdoc:…`), over BLE.
     *
     * @param useCase what to ask for.
     * @param qrCode the scanned QR code's text.
     * @param readerKey the reader-authentication key, or `null` to send an unsigned request.
     * @param onConnected called once connected, while the holder reviews the request.
     * @throws PhotoIdReadException if the QR code isn't an mdoc engagement, the connection fails (for
     *   example Bluetooth is off), or the holder didn't send a response.
     */
    @Throws(PhotoIdReadException::class, CancellationException::class)
    suspend fun readQr(
        useCase: PhotoIdUseCase,
        qrCode: String,
        readerKey: AsymmetricKey.X509Compatible?,
        onConnected: () -> Unit = {},
    ): PhotoIdReadResult = reportingFailures {
        if (!qrCode.startsWith(QR_PREFIX)) {
            throw PhotoIdReadException("That QR code isn't a Photo ID sharing code")
        }
        val encodedDeviceEngagement = try {
            ByteString(qrCode.removePrefix(QR_PREFIX).fromBase64Url())
        } catch (e: IllegalArgumentException) {
            throw PhotoIdReadException("That QR code is damaged", e)
        }
        val transport = createTransportForQrEngagement(encodedDeviceEngagement, ValidatopiaTransport.options)
        onConnected()
        read(useCase, encodedDeviceEngagement, Simple.NULL, transport, readerKey)
    }

    /**
     * Reads a Photo ID from a wallet phone tapped against this one (this phone is the NFC reader),
     * with negotiated handover to BLE.
     *
     * @param useCase what to ask for.
     * @param nfcReader the NFC reader to scan with.
     * @param readerKey the reader-authentication key, or `null` to send an unsigned request.
     * @param message the instruction shown while scanning, where the platform shows one (iOS).
     * @param onEngaged called once the tap has handed over to BLE, while the holder reviews the request.
     * @return the response, or `null` if scanning was dismissed.
     * @throws PhotoIdReadException if the tap, the connection or the exchange fails, or the holder
     *   didn't send a response.
     */
    @Throws(PhotoIdReadException::class, CancellationException::class)
    suspend fun readNfc(
        useCase: PhotoIdUseCase,
        nfcReader: NfcTagReader,
        readerKey: AsymmetricKey.X509Compatible?,
        message: String,
        onEngaged: () -> Unit = {},
    ): PhotoIdReadResult? = reportingFailures {
        readNfcUnchecked(useCase, nfcReader, readerKey, message, onEngaged)
    }

    private suspend fun readNfcUnchecked(
        useCase: PhotoIdUseCase,
        nfcReader: NfcTagReader,
        readerKey: AsymmetricKey.X509Compatible?,
        message: String,
        onEngaged: () -> Unit,
    ): PhotoIdReadResult? = nfcReader.scanMdocReader(
        message = message,
        options = ValidatopiaTransport.options,
        handoverOptions = MdocReaderNfcHandoverOptions(),
        selectConnectionMethod = { it.firstOrNull() },
        negotiatedHandoverConnectionMethods = ValidatopiaTransport.bleConnectionMethods(),
    ) { scan ->
        onEngaged()
        read(
            useCase = useCase,
            encodedDeviceEngagement = scan.encodedDeviceEngagement,
            handover = scan.handover,
            transport = scan.transport,
            readerKey = readerKey,
            insertSequenceNumbers = scan.type == MdocHandoverType.V2_HANDOVER,
        )
    }

    /**
     * Picks the connection method the reader should use from a QR device engagement and creates
     * its transport. BLE is the only method the Validatopia wallet offers.
     *
     * @throws PhotoIdReadException if the engagement offers no usable connection method.
     */
    @Throws(PhotoIdReadException::class, CancellationException::class)
    suspend fun createTransportForQrEngagement(
        encodedDeviceEngagement: ByteString,
        options: MdocTransportOptions,
    ): MdocTransport {
        val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement.toByteArray()))
        val connectionMethods = MdocConnectionMethod.disambiguate(
            deviceEngagement.connectionMethods,
            MdocRole.MDOC_READER
        )
        val connectionMethod = connectionMethods.firstOrNull()
            ?: throw PhotoIdReadException("The wallet's QR code offers no connection method this reader supports")
        return MdocTransportFactory.Default.createTransport(connectionMethod, MdocRole.MDOC_READER, options)
    }

    /**
     * Sends [useCase]'s request over [transport] and waits for the response.
     *
     * @param useCase what to ask for.
     * @param encodedDeviceEngagement the device engagement from the QR code or NFC handover.
     * @param handover the handover structure (`null` CBOR for QR engagement).
     * @param transport a transport to the holder, not yet opened.
     * @param readerKey the reader-authentication key, or `null` to send an unsigned request.
     * @param insertSequenceNumbers whether the session uses sequence numbers (NFC handover v2).
     * @throws PhotoIdReadException if the holder didn't send a response.
     */
    @Throws(PhotoIdReadException::class, CancellationException::class)
    suspend fun read(
        useCase: PhotoIdUseCase,
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        transport: MdocTransport,
        readerKey: AsymmetricKey.X509Compatible?,
        insertSequenceNumbers: Boolean = false,
    ): PhotoIdReadResult {
        val deviceEngagement = DeviceEngagement.fromDataItem(Cbor.decode(encodedDeviceEngagement.toByteArray()))
        val eDeviceKey = deviceEngagement.eDeviceKey
        val eReaderKey = Crypto.createEcPrivateKey(eDeviceKey.curve)
        val sessionTranscript = buildCborArray {
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(encodedDeviceEngagement.toByteArray())))
            add(Tagged(Tagged.ENCODED_CBOR, Bstr(Cbor.encode(eReaderKey.publicKey.toCoseKey().toDataItem()))))
            add(handover)
        }
        val sessionEncryption = SessionEncryption(
            role = MdocRole.MDOC_READER,
            eSelfKey = eReaderKey,
            remotePublicKey = eDeviceKey,
            encodedSessionTranscript = Cbor.encode(sessionTranscript),
            insertSequenceNumbers = insertSequenceNumbers,
        )
        val deviceRequest = useCase.buildRequest(sessionTranscript, readerKey)
        try {
            transport.open(eDeviceKey)
            transport.sendMessage(
                sessionEncryption.encryptMessage(
                    messagePlaintext = Cbor.encode(deviceRequest.toDataItem()),
                    statusCode = null
                )
            )
            val sessionData = transport.waitForMessage()
            if (sessionData.isEmpty()) {
                throw PhotoIdReadException("The wallet ended the session without responding")
            }
            val (message, status) = sessionEncryption.decryptMessage(sessionData)
            if (message == null) {
                throw PhotoIdReadException(
                    if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                        "The holder declined to share"
                    } else {
                        "The wallet sent no response (status $status)"
                    }
                )
            }
            transport.sendMessage(
                SessionEncryption.encodeStatus(
                    statusCode = Constants.SESSION_DATA_STATUS_SESSION_TERMINATION,
                    sequenceNumber = if (insertSequenceNumbers) sessionEncryption.nextSequenceNumber else null,
                )
            )
            return PhotoIdReadResult(
                deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(message)),
                sessionTranscript = sessionTranscript,
            )
        } finally {
            withContext(NonCancellable) {
                transport.close()
            }
        }
    }

    /**
     * Runs one of the reader flows the apps call directly, including from Swift, where an exception
     * not listed in `@Throws` is fatal. Transport, NFC and parsing failures of every kind become a
     * [PhotoIdReadException] carrying the underlying reason; cancellation passes through.
     */
    private suspend fun <T> reportingFailures(block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException || e is PhotoIdReadException) throw e
        val reason = listOfNotNull(e.message, e.cause?.message).distinct().joinToString(": ")
        throw PhotoIdReadException(reason.ifEmpty { "Reading the Photo ID failed" }, e)
    }

    private const val QR_PREFIX = "mdoc:"
}
