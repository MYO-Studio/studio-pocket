// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicenceTextTest {
    /** One unit per character, so widths are easy to reason about. */
    private val chars: (String) -> Float = { it.length.toFloat() }

    @Test fun paragraphsJoinHardWrappedProseOnly() {
        val text = "Title\n\n" +
            "   This line is long enough to count as hard-wrapped prose, so\n" +
            "   it carries on here.\n\n\n" +
            "Version: 1.0\nLicence: MIT\n"
        assertEquals(
            listOf("Title", "", "This line is long enough to count as hard-wrapped prose, so it carries on here.", "", "Version: 1.0", "Licence: MIT"),
            TextLayout.paragraphs(text),
        )
    }

    @Test fun paragraphsKeepALongLineBeforeACapitalisedOne() {
        val text = "Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors\nLicence: Apache"
        assertEquals(2, TextLayout.paragraphs(text).size)
    }

    @Test fun wrapBreaksAtWordsAndSplitsLongWords() {
        assertEquals(listOf("one two", "three"), TextLayout.wrap("one two three", 8f, chars))
        assertEquals(listOf("abcd", "efgh", "ij"), TextLayout.wrap("abcdefghij", 4f, chars))
        assertEquals(listOf("a", "", "b"), TextLayout.wrap("a\n\nb", 10f, chars))
    }

    @Test fun scrollMovesAScreenLessOneLineAndClamps() {
        assertEquals(10, TextLayout.scroll(0, 1, 100, 11))
        assertEquals(0, TextLayout.scroll(10, -1, 100, 11))
        assertEquals(0, TextLayout.scroll(0, -1, 100, 11))
        assertEquals(89, TextLayout.scroll(85, 1, 100, 11))
        assertEquals(0, TextLayout.scroll(0, 1, 5, 11))
    }

    @Test fun keysAndPosition() {
        assertEquals(-1, TextLayout.keyDelta("KEYCODE_DPAD_UP"))
        assertEquals(1, TextLayout.keyDelta("KEYCODE_DPAD_RIGHT"))
        assertEquals(1, TextLayout.keyDelta("KEYCODE_ENTER"))
        assertEquals(0, TextLayout.keyDelta("KEYCODE_BACK"))
        assertEquals("Lines 11–21 of 100", TextLayout.position(10, 100, 11))
        assertNull(TextLayout.position(0, 11, 11))
    }

    @Test fun everyLicenceDocIsAnAssetAndTheApacheTextIsCanonical() {
        val assets = File("src/main/assets")
        LicenceDocs.ALL.forEach { assertTrue("${it.asset} missing", File(assets, it.asset).isFile) }
        assertEquals("Apache License", File(assets, "licenses/APACHE-2.0.txt").readLines().first { it.isNotBlank() }.trim())
        assertEquals(LicenceDocs.ALL[2], LicenceDocs.byRowId("licence:apache"))
        // The player's own licence comes first, canonical and complete.
        assertEquals("licence:gpl", "licence:${LicenceDocs.ALL[0].id}")
        val gpl = File(assets, "licenses/GPL-3.0.txt").readText()
        assertEquals("GNU GENERAL PUBLIC LICENSE", gpl.lines().first { it.isNotBlank() }.trim())
        assertTrue(gpl.contains("Version 3, 29 June 2007") && gpl.contains("END OF TERMS AND CONDITIONS"))
        assertNull(LicenceDocs.byRowId("about.licences"))
    }
}
