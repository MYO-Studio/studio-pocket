// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphsTest {
    @Test
    fun everyGlyphIs16By16AndSymmetricWhereItShouldBe() {
        for (glyph in listOf(Glyphs.CLOCK, Glyphs.GRID, Glyphs.FOLDER)) {
            assertEquals(16, glyph.size)
            for (row in glyph) {
                assertEquals(row, 16, row.length)
                assertTrue(row, row.all { it == '#' || it == '.' })
            }
        }
        assertEquals(Glyphs.GRID.toList(), Glyphs.GRID.reversed())
        assertEquals(Glyphs.GRID.toList(), Glyphs.GRID.map { it.reversed() })
        // The clock face (without its hands) is a symmetric ring.
        val face = Glyphs.CLOCK.mapIndexed { y, row -> if (y in 4..8) row.substring(0, 4) + "........" + row.substring(12) else row }
        assertEquals(face, face.reversed())
        assertEquals(face, face.map { it.reversed() })
    }
}
