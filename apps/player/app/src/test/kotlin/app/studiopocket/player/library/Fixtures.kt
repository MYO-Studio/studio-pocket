// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import java.io.File

/** The shared fixtures from packages/library-format; Gradle runs unit tests with the module dir as cwd. */
object Fixtures {
    val root: File = File("../../../packages/library-format/fixtures/library/STUDIOPOCKET").canonicalFile
    val invalid: File = File("../../../packages/library-format/fixtures/invalid").canonicalFile
}
