// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import java.util.Locale

/**
 * The settings area's pages. Back on a page goes to its [parent]; MAIN has none (Back closes settings).
 * LICENCES lists the licence texts under About; LICENCE_TEXT shows one of them.
 */
enum class SettingsPage {
    MAIN, BLUETOOTH, BT_DEVICE, VOLUME, SLEEP, SCREEN, DATETIME, ABOUT, LICENCES, LICENCE_TEXT;

    val parent: SettingsPage?
        get() = when (this) {
            MAIN -> null
            BT_DEVICE -> BLUETOOTH
            LICENCES -> ABOUT
            LICENCE_TEXT -> LICENCES
            else -> MAIN
        }
}

/** Text for the About page's info rows. Pure, so it is unit-tested on the JVM. */
object AboutText {
    fun library(playlists: Int, formatVersion: Int): String =
        "Library: $playlists ${if (playlists == 1) "playlist" else "playlists"} (format v$formatVersion)"

    /** The player's own storage: "Storage: 3.2 GB free of 7.4 GB", or "Storage: —" when it is not mounted (null sizes). */
    fun storage(freeBytes: Long?, totalBytes: Long?): String =
        if (freeBytes == null || totalBytes == null) "Storage: —" else "Storage: ${bytes(freeBytes)} free of ${bytes(totalBytes)}"

    /**
     * The memory card: "Card: none" when none is mounted, "Card: 12 GB free of 29 GB" when it is this
     * player's, "Card: not in use, 12 GB free" when it is mounted but not paired (another player's, or
     * its device.json cannot be read or written).
     */
    fun card(mounted: Boolean, paired: Boolean, freeBytes: Long?, totalBytes: Long?): String {
        if (!mounted) return "Card: none"
        val sized = freeBytes != null && totalBytes != null
        return when {
            paired && sized -> "Card: ${bytes(freeBytes!!)} free of ${bytes(totalBytes!!)}"
            paired -> "Card: in use"
            sized -> "Card: not in use, ${bytes(freeBytes!!)} free"
            else -> "Card: not in use"
        }
    }

    fun battery(percent: Int): String = if (percent < 0) "Battery: ?" else "Battery: $percent%"

    /** Binary units, one decimal below 10 ("3.2 GB", "14 GB", "512 MB"). */
    fun bytes(n: Long): String {
        if (n < 1024) return "$n B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = n / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        val number = if (value < 10) String.format(Locale.US, "%.1f", value) else Math.round(value).toString()
        return "$number ${units[unit]}"
    }
}
