// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.ui

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The one background thread the UI decodes covers and icons on; serial so decodes never compete on the Y2's chip. */
object Decoders {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ui-decode").also { it.isDaemon = true } }
}
