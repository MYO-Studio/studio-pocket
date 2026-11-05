// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageListingTest {
    @Test
    fun listsDirectoriesFirstThenFilesAndTruncates() {
        val root = Files.createTempDirectory("storage").toFile()
        File(root, "zeta.mp3").writeText("x")
        File(root, "Alpha").mkdir()
        File(root, "beta").mkdir()
        File(root, "gamma.txt").writeText("x")
        assertEquals(listOf("  d Alpha", "  d beta", "  f gamma.txt", "  … 1 more"), StorageListing.describe(root, limit = 3))
        root.deleteRecursively()
    }

    @Test
    fun reportsAMissingRoot() {
        val missing = File("/definitely/not/here")
        assertEquals(listOf("  (missing) /definitely/not/here"), StorageListing.describe(missing))
    }
}
