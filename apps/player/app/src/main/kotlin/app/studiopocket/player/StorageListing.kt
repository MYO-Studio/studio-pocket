// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import java.io.File

/** Lists the top-level entries of a storage root, directories first, bounded for the small panel. */
object StorageListing {
    private const val MAX_ENTRIES = 12

    fun describe(root: File, limit: Int = MAX_ENTRIES): List<String> {
        if (!root.exists()) return listOf("  (missing) $root")
        if (!root.canRead()) return listOf("  (unreadable) $root")
        val entries = root.listFiles() ?: return listOf("  (empty or unreadable) $root")
        val sorted = entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val shown = sorted.take(limit).map { "  ${if (it.isDirectory) "d" else "f"} ${it.name}" }
        val more = sorted.size - shown.size
        return if (more > 0) shown + "  … $more more" else shown
    }
}
