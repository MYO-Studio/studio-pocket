// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

/**
 * Tiny fixed-size log shared between the activity and the broadcast receiver.
 * Pure Kotlin so the ordering and capacity rules are unit-testable off-device.
 */
class EventLog(private val capacity: Int = 6) {
    private val entries = ArrayDeque<String>()
    private val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun add(entry: String) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
        listeners.toList().forEach { it() }
    }

    /** Newest first, so the latest key press is always the top line on screen. */
    @Synchronized
    fun snapshot(): List<String> = entries.reversed()

    @Synchronized
    fun addListener(listener: () -> Unit) { listeners += listener }

    @Synchronized
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    companion object {
        /** Process-wide instance; the manifest receiver has no other way to reach the activity. */
        val shared = EventLog()
    }
}
