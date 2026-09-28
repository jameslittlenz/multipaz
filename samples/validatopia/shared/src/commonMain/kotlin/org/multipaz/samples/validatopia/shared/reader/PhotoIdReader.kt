package org.multipaz.samples.validatopia.shared.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.samples.validatopia.shared.usecase.PhotoIdUseCase
import org.multipaz.util.Constants

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
     * Picks the connection method the reader should use from a QR device engagement and creates
     * its transport. BLE is the only method the Validatopia wallet offers.
     *
     * @throws PhotoIdReadException if the engagement offers no usable connection method.
     */
    @Throws(PhotoIdReadException::class)
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
}
