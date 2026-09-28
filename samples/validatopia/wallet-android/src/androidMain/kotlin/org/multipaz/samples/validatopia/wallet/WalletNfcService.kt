package org.multipaz.samples.validatopia.wallet

import android.content.Context
import kotlinx.io.bytestring.ByteString
import org.multipaz.compose.mdoc.CombinedNfcService
import org.multipaz.compose.mdoc.MdocNdefService
import org.multipaz.compose.mdoc.MdocNfcDataTransferService
import org.multipaz.compose.mdoc.MdocNfcV2Service
import org.multipaz.compose.mdoc.NfcApduService
import org.multipaz.compose.prompt.PresentmentActivity
import org.multipaz.crypto.EcCurve
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.Nfc

/**
 * Presents the Photo ID when the phone is tapped on a reader, even if the app isn't open.
 * Consent is always asked for, in [PresentmentActivity].
 */
class WalletNfcService : CombinedNfcService() {
    override fun buildServices(): Map<ByteString, NfcApduService> = mapOf(
        Nfc.NDEF_APPLICATION_ID to WalletMdocNdefService(this, ::sendResponseApdu),
        Nfc.MDOC_NFC_ENGAGEMENT_V2_AID to WalletMdocNfcV2Service(this, ::sendResponseApdu),
        Nfc.ISO_MDOC_NFC_DATA_TRANSFER_APPLICATION_ID to MdocNfcDataTransferService(this, ::sendResponseApdu),
    )
}

private suspend fun preparePresentment(context: Context) = WalletModel.get(context).presentmentSource.also { source ->
    if (!PresentmentActivity.presentmentModel.isActive) {
        PresentmentActivity.presentmentModel.reset(source = source, preselectedDocuments = emptyList())
    }
}

private val TRANSPORT_OPTIONS = MdocTransportOptions(bleUseL2CAP = false, bleUseL2CAPInEngagement = true)

// No preference: take the connection methods in the order the reader offers them.
private val NEGOTIATED_HANDOVER_ORDER = emptyList<String>()

private class WalletMdocNdefService(
    private val context: Context,
    sendResponse: (ByteArray) -> Unit,
) : MdocNdefService(context, sendResponse) {
    override suspend fun getSettings(): Settings = Settings(
        source = preparePresentment(context),
        promptModel = PresentmentActivity.promptModel,
        presentmentModel = PresentmentActivity.presentmentModel,
        activityClass = PresentmentActivity::class.java,
        sessionEncryptionCurve = EcCurve.P256,
        useNegotiatedHandover = true,
        negotiatedHandoverPreferredOrder = NEGOTIATED_HANDOVER_ORDER,
        staticHandoverBleCentralClientModeEnabled = true,
        staticHandoverBlePeripheralServerModeEnabled = false,
        staticHandoverNfcDataTransferEnabled = false,
        transportOptions = TRANSPORT_OPTIONS,
    )
}

private class WalletMdocNfcV2Service(
    private val context: Context,
    sendResponse: (ByteArray) -> Unit,
) : MdocNfcV2Service(context, sendResponse) {
    override suspend fun getSettings(): Settings = Settings(
        source = preparePresentment(context),
        promptModel = PresentmentActivity.promptModel,
        presentmentModel = PresentmentActivity.presentmentModel,
        activityClass = PresentmentActivity::class.java,
        sessionEncryptionCurve = EcCurve.P256,
        useNegotiatedHandover = true,
        negotiatedHandoverPreferredOrder = NEGOTIATED_HANDOVER_ORDER,
        transportOptions = TRANSPORT_OPTIONS,
    )
}
