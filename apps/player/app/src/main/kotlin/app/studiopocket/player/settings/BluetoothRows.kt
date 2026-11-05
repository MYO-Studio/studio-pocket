// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/** A paired or found Bluetooth device, as the Bluetooth page shows it. */
data class BtDevice(val address: String, val name: String, val connected: Boolean)

/**
 * What the Bluetooth page shows. [enabled] counts a radio that is turning off as still on, so
 * [switching] with [enabled] means turning off and without it turning on. [message] is the footnote.
 */
data class BtState(
    val enabled: Boolean,
    val switching: Boolean,
    val scanning: Boolean,
    val paired: List<BtDevice>,
    val found: List<BtDevice>,
    val message: String?,
)

/**
 * The Bluetooth page: power, then (when on) the connected device, the other paired devices, Find
 * headphones and what it found, and always the stock Android screen last. Pure, so it is unit-tested.
 */
fun bluetoothRows(s: BtState): List<SettingsRow> {
    val power = when {
        s.switching -> if (s.enabled) "Turning off…" else "Turning on…"
        s.enabled -> "Bluetooth: On"
        else -> "Bluetooth: Off"
    }
    val rows = arrayListOf(SettingsRow("bt.power", power, enabled = !s.switching))
    if (s.enabled && !s.switching) {
        s.paired.sortedByDescending { it.connected }.forEach {
            rows += SettingsRow("bt.device:${it.address}", if (it.connected) "${it.name} – connected" else it.name)
        }
        rows += SettingsRow("bt.scan", if (s.scanning) "Searching…" else "Find headphones", enabled = !s.scanning)
        val pairedAddresses = s.paired.map { it.address }.toSet()
        s.found.filter { it.address !in pairedAddresses }.forEach { rows += SettingsRow("bt.pair:${it.address}", "Pair ${it.name}") }
    }
    rows += SettingsRow("bt.stock", "Android Bluetooth screen")
    return rows
}

/** One paired device's page: Connect or Disconnect, then Forget. */
fun deviceRows(d: BtDevice): List<SettingsRow> = listOf(
    if (d.connected) SettingsRow("bt.disconnect:${d.address}", "Disconnect") else SettingsRow("bt.connect:${d.address}", "Connect"),
    SettingsRow("bt.forget:${d.address}", "Forget"),
)

/** Headphones and speakers: `BluetoothClass.Device.Major.AUDIO_VIDEO`. */
fun isAudioClass(majorDeviceClass: Int): Boolean = majorDeviceClass == AUDIO_VIDEO

private const val AUDIO_VIDEO = 0x0400
