package org.multipaz.mdoc.transport

import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported

/**
 * Whether a Core Bluetooth manager in [state] can still become powered on without the user doing
 * anything: it's still starting up ([CBManagerStateUnknown], including while the permission prompt
 * is showing) or resetting. Any other state than powered on is settled, and no further state
 * update arrives until the user changes something, so waiting for power-on would never end.
 */
internal fun isBluetoothStateTransient(state: CBManagerState): Boolean =
    state == CBManagerStateUnknown || state == CBManagerStateResetting

/** Why Bluetooth can't be used in a settled [state], for an error shown to the user. */
internal fun bluetoothUnavailableMessage(state: CBManagerState): String = when (state) {
    CBManagerStatePoweredOn -> "Bluetooth is on"
    CBManagerStatePoweredOff -> "Bluetooth is turned off"
    CBManagerStateUnauthorized -> "This app isn't allowed to use Bluetooth"
    CBManagerStateUnsupported -> "This device doesn't support Bluetooth LE"
    else -> "Bluetooth isn't available (state $state)"
}
