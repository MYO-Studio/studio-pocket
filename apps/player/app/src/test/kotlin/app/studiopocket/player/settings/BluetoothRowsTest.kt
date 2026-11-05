// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRowsTest {
    private val buds = BtDevice("AA:01", "Buds", connected = false)
    private val cans = BtDevice("AA:02", "Cans", connected = true)
    private val newOnes = BtDevice("AA:03", "New Ones", connected = false)

    private fun state(
        enabled: Boolean = true,
        scanning: Boolean = false,
        paired: List<BtDevice> = emptyList(),
        found: List<BtDevice> = emptyList(),
    ) = BtState(enabled, switching = false, scanning = scanning, paired = paired, found = found, message = null)

    @Test
    fun offShowsOnlyPowerAndStock() {
        val rows = bluetoothRows(state(enabled = false, paired = listOf(buds), found = listOf(newOnes)))
        assertEquals(listOf("bt.power", "bt.stock"), rows.map { it.id })
        assertEquals("Bluetooth: Off", rows[0].label)
        assertEquals("Android Bluetooth screen", rows[1].label)
    }

    @Test
    fun connectedDeviceComesFirst() {
        val rows = bluetoothRows(state(paired = listOf(buds, cans)))
        assertEquals(listOf("bt.power", "bt.device:AA:02", "bt.device:AA:01", "bt.scan", "bt.stock"), rows.map { it.id })
        assertEquals("Bluetooth: On", rows[0].label)
        assertEquals("Cans – connected", rows[1].label)
        assertEquals("Buds", rows[2].label)
    }

    @Test
    fun foundDevicesBecomePairRows() {
        val rows = bluetoothRows(state(paired = listOf(buds), found = listOf(newOnes)))
        assertEquals(listOf("bt.power", "bt.device:AA:01", "bt.scan", "bt.pair:AA:03", "bt.stock"), rows.map { it.id })
        assertEquals("Pair New Ones", rows[3].label)
    }

    @Test
    fun scanningDisablesFind() {
        val idle = bluetoothRows(state()).single { it.id == "bt.scan" }
        assertEquals("Find headphones", idle.label)
        assertTrue(idle.enabled)
        val busy = bluetoothRows(state(scanning = true)).single { it.id == "bt.scan" }
        assertEquals("Searching…", busy.label)
        assertFalse(busy.enabled)
    }

    @Test
    fun deviceRowsOfferDisconnectWhenConnected() {
        assertEquals(listOf("bt.disconnect:AA:02", "bt.forget:AA:02"), deviceRows(cans).map { it.id })
        assertEquals(listOf("bt.connect:AA:01", "bt.forget:AA:01"), deviceRows(buds).map { it.id })
    }

    @Test
    fun audioClassFilter() {
        assertTrue(isAudioClass(0x0400))
        assertFalse(isAudioClass(0x0200))
    }
}
