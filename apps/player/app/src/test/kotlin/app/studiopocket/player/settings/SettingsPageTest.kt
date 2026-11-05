// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPageTest {
    @Test
    fun parents() {
        assertNull(SettingsPage.MAIN.parent)
        assertEquals(SettingsPage.BLUETOOTH, SettingsPage.BT_DEVICE.parent)
        listOf(SettingsPage.BLUETOOTH, SettingsPage.VOLUME, SettingsPage.SLEEP, SettingsPage.SCREEN, SettingsPage.ABOUT)
            .forEach { assertEquals(SettingsPage.MAIN, it.parent) }
        assertEquals(SettingsPage.ABOUT, SettingsPage.LICENCES.parent)
        assertEquals(SettingsPage.LICENCES, SettingsPage.LICENCE_TEXT.parent)
    }

    @Test
    fun aboutText() {
        assertEquals("Library: 0 playlists (format v1)", AboutText.library(0, 1))
        assertEquals("Library: 1 playlist (format v1)", AboutText.library(1, 1))
        assertEquals("Library: 12 playlists (format v1)", AboutText.library(12, 1))
        assertEquals("Storage: —", AboutText.storage(null, null))
        assertEquals("Storage: 3.2 GB free of 14 GB", AboutText.storage(3_435_973_837L, 15_032_385_536L))
        assertEquals("Battery: 84%", AboutText.battery(84))
        assertEquals("Battery: ?", AboutText.battery(-1))
    }

    @Test
    fun bytes() {
        assertEquals("512 B", AboutText.bytes(512))
        assertEquals("1.0 KB", AboutText.bytes(1024))
        assertEquals("512 MB", AboutText.bytes(512L * 1024 * 1024))
        assertEquals("1023 MB", AboutText.bytes(1023L * 1024 * 1024))
        assertEquals("1.0 GB", AboutText.bytes(1024L * 1024 * 1024))
    }
}
