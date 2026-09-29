package org.multipaz.samples.validatopia.shared.transport

import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethodBle
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.UUID

/**
 * The ISO/IEC 18013-5 transport settings every Validatopia app uses, so wallets and verifiers on
 * Android and iOS always offer each other the same thing.
 */
object ValidatopiaTransport {
    /** BLE with GATT for the session, advertising L2CAP support in the engagement. */
    val options = MdocTransportOptions(bleUseL2CAP = false, bleUseL2CAPInEngagement = true)

    /**
     * The BLE connection methods offered in a QR engagement or NFC negotiated handover: both mdoc
     * central client mode and mdoc peripheral server mode, sharing one service UUID, so either
     * side's radio stack can take the central role.
     *
     * @param uuid the BLE service UUID, fresh for each engagement.
     */
    fun bleConnectionMethods(uuid: UUID = UUID.randomUUID()): List<MdocConnectionMethod> = listOf(
        MdocConnectionMethodBle(
            supportsPeripheralServerMode = false,
            supportsCentralClientMode = true,
            peripheralServerModeUuid = null,
            centralClientModeUuid = uuid,
        ),
        MdocConnectionMethodBle(
            supportsPeripheralServerMode = true,
            supportsCentralClientMode = false,
            peripheralServerModeUuid = uuid,
            centralClientModeUuid = null,
        ),
    )
}
