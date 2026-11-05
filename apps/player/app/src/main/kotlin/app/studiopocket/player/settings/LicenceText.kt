// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/**
 * The licence texts About > Licences shows, read from the APK's assets: first the GPL-3.0, Studio
 * Pocket's own licence (a copy goes with every copy of the player), then THIRD-PARTY.txt, which names every
 * bundled component, and the texts it refers to; scripts/check-apk-notices.sh fails a release whose APK lacks these files.
 */
object LicenceDocs {
    data class Doc(val id: String, val label: String, val asset: String)

    val ALL = listOf(
        Doc("gpl", "GNU General Public License 3", "licenses/GPL-3.0.txt"),
        Doc("third-party", "Third-party notices", "licenses/THIRD-PARTY.txt"),
        Doc("apache", "Apache License 2.0", "licenses/APACHE-2.0.txt"),
        Doc("ofl", "SIL Open Font License 1.1", "fonts/LICENSE-Nunito.txt"),
    )

    fun byRowId(rowId: String): Doc? = ALL.firstOrNull { rowId == "licence:${it.id}" }
}

/** Line layout and scrolling for the licence text page. Pure, so it is unit-tested on the JVM. */
object TextLayout {
    /** A line at least this long (indentation included) is taken as hard-wrapped prose. */
    private const val PROSE = 50

    /**
     * The text as display paragraphs: leading indentation dropped, runs of blank lines kept as one
     * paragraph break, and a long line joined with the next when that one carries on in lower case
     * (licence files are hard-wrapped at about 75 columns, which would leave a short tail on every
     * other line of the narrow screen). Headings, fields and short lines stay on their own.
     */
    fun paragraphs(text: String): List<String> {
        val out = ArrayList<String>()
        var current: StringBuilder? = null
        var previous = ""
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) {
                current?.let { out.add(it.toString()) }
                current = null
                if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                previous = ""
                continue
            }
            val c = current
            if (c != null && previous.length >= PROSE && line[0].isLowerCase()) c.append(' ').append(line)
            else { c?.let { out.add(it.toString()) }; current = StringBuilder(line) }
            previous = raw.trimEnd()
        }
        current?.let { out.add(it.toString()) }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    /** [paragraphs] word-wrapped to [maxWidth] as measured by [measure]; a word wider than a line is split. */
    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        val lines = ArrayList<String>()
        for (p in paragraphs(text)) {
            if (p.isEmpty()) { lines.add(""); continue }
            var line = ""
            for (word in p.split(' ')) {
                if (word.isEmpty()) continue
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate) <= maxWidth) { line = candidate; continue }
                if (line.isNotEmpty()) lines.add(line)
                var rest = word
                while (measure(rest) > maxWidth && rest.length > 1) {
                    var cut = rest.length - 1
                    while (cut > 1 && measure(rest.substring(0, cut)) > maxWidth) cut--
                    lines.add(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                line = rest
            }
            if (line.isNotEmpty()) lines.add(line)
        }
        return lines
    }

    /** -1 for ring/arrow back, 1 for forward, 0 for any other key. */
    fun keyDelta(keyName: String): Int = when (keyName) {
        "KEYCODE_DPAD_UP", "KEYCODE_DPAD_LEFT" -> -1
        "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_RIGHT", "KEYCODE_DPAD_CENTER", "KEYCODE_ENTER" -> 1
        else -> 0
    }

    /** The top line after moving [pages] screens from [top]; a screen overlaps the last by one line. */
    fun scroll(top: Int, pages: Int, lineCount: Int, visible: Int): Int {
        val step = maxOf(1, visible - 1)
        val last = maxOf(0, lineCount - visible)
        return (top + pages * step).coerceIn(0, last)
    }

    /** "Lines 13–24 of 340" (an en dash), or null when everything fits on one screen. */
    fun position(top: Int, lineCount: Int, visible: Int): String? =
        if (lineCount <= visible) null else "Lines ${top + 1}–${minOf(lineCount, top + visible)} of $lineCount"
}
