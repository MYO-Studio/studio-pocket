// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StateStoreTest {
    @Test
    fun writesAtomicallyAndReadsBack() {
        val root = Files.createTempDirectory("myo").toFile()
        val store = StateStore(root)
        assertNull(store.read("bedtime"))
        assertTrue(store.write(PlaybackState("bedtime", 1, 4200L, StateStore.now())))
        assertEquals(1, store.read("bedtime")!!.trackIndex)
        assertEquals(4200L, store.read("bedtime")!!.positionMs)
        assertFalse(File(root, "state/bedtime.json.part").exists())
        assertTrue(store.write(PlaybackState("bedtime", 2, 1L, StateStore.now())))
        assertEquals(2, store.read("bedtime")!!.trackIndex)
        root.deleteRecursively()
    }

    @Test
    fun writeReplacesWithoutDeletingFirst() {
        val root = Files.createTempDirectory("myo").toFile()
        val store = StateStore(root)
        assertTrue(store.write(PlaybackState("bedtime", 1, 10L, StateStore.now())))
        assertTrue(store.write(PlaybackState("bedtime", 3, 20L, StateStore.now())))
        assertTrue(File(root, "state/bedtime.json").isFile)
        assertEquals(3, store.read("bedtime")!!.trackIndex)
        assertFalse(File(root, "state/bedtime.json.part").exists())
        root.deleteRecursively()
    }

    @Test
    fun writeReturnsFalseWithoutThrowingWhenTheTargetCannotBeReplaced() {
        val root = Files.createTempDirectory("myo").toFile()
        // A non-empty directory in the target's place: rename fails, and so does the fallback delete.
        File(root, "state/bedtime.json").mkdirs()
        File(root, "state/bedtime.json/keep").writeText("x")
        assertFalse(StateStore(root).write(PlaybackState("bedtime", 1, 10L, StateStore.now())))
        assertTrue(File(root, "state/bedtime.json/keep").exists())
        root.deleteRecursively()
    }

    @Test
    fun readReturnsNullWhenTheFileCannotBeRead() {
        val root = Files.createTempDirectory("myo").toFile()
        File(root, "state/bedtime.json").mkdirs()
        assertNull(StateStore(root).read("bedtime"))
        root.deleteRecursively()
    }

    @Test
    fun ignoresCorruptState() {
        val root = Files.createTempDirectory("myo").toFile()
        File(root, "state").mkdirs()
        File(root, "state/bedtime.json").writeText("{ nope")
        assertNull(StateStore(root).read("bedtime"))
        root.deleteRecursively()
    }

    @Test
    fun createsTheDeviceMarkerOnce() {
        val root = Files.createTempDirectory("myo").toFile()
        val store = StateStore(root)
        assertTrue(store.ensureDeviceMarker("0.2.0"))
        val first = File(root, "device.json").readText()
        assertTrue(first.contains("\"studiopocket-device\""))
        assertTrue(store.ensureDeviceMarker("0.2.0"))
        assertEquals(first, File(root, "device.json").readText())
        root.deleteRecursively()
    }

    @Test
    fun keepsPlayerVersionCurrentWithoutChangingTheIdentity() {
        val root = Files.createTempDirectory("myo").toFile()
        // As the desktop writes it: no playerVersion.
        File(root, "device.json").writeText("""{"format":"studiopocket-device","formatVersion":1,"deviceId":"abc-123","createdAt":"2026-10-18T12:00:00Z","createdBy":"studio-pocket-desktop/0.1.0"}""")
        assertTrue(StateStore(root).ensureDeviceMarker("0.5.0"))
        val marker = org.json.JSONObject(File(root, "device.json").readText())
        assertEquals("abc-123", marker.getString("deviceId"))
        assertEquals("2026-10-18T12:00:00Z", marker.getString("createdAt"))
        assertEquals("studio-pocket-desktop/0.1.0", marker.getString("createdBy"))
        assertEquals("0.5.0", marker.getString("playerVersion"))
        assertFalse(File(root, "device.json.part").exists())
        val written = File(root, "device.json").readText()
        assertTrue(StateStore(root).ensureDeviceMarker("0.5.0"))
        assertEquals(written, File(root, "device.json").readText())
        // A failed version lookup ("?") never replaces a real version.
        assertTrue(StateStore(root).ensureDeviceMarker("?"))
        assertEquals(written, File(root, "device.json").readText())
        // A marker it cannot read is never overwritten: it is the player's identity.
        File(root, "device.json").writeText("{ nope")
        assertTrue(StateStore(root).ensureDeviceMarker("0.5.0"))
        assertEquals("{ nope", File(root, "device.json").readText())
        root.deleteRecursively()
    }
}
