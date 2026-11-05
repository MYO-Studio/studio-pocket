// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

/**
 * Linux scan codes from the stock /system/usr/keylayout/mtk-kpd.kl, used only to label
 * what the hardware sent. Measured on a Y2 on 2026-10-19: ring left 103 DPAD_UP, ring right
 * 108 DPAD_DOWN, back-arrow button 105 DPAD_LEFT, forward-arrow button 106 DPAD_RIGHT,
 * centre 28 ENTER (not DPAD_CENTER), back 158 BACK, play/pause 164 MEDIA_PLAY_PAUSE.
 * Volume and power are passed to Android and not logged.
 */
object Y2Keys {
    private val scanCodeLabels = mapOf(
        28 to "CENTRE",
        103 to "RING_LEFT",
        105 to "ARROW_BACK",
        106 to "ARROW_FWD",
        108 to "RING_RIGHT",
        114 to "VOLUME_DOWN",
        115 to "VOLUME_UP",
        116 to "POWER",
        158 to "BACK",
        164 to "PLAY_PAUSE"
    )

    fun label(scanCode: Int): String = scanCodeLabels[scanCode] ?: "scan$scanCode"

    /** One log line per key event: "DOWN DPAD_UP scan103 rep0". */
    fun describe(action: String, keyCodeName: String, scanCode: Int, repeat: Int): String =
        "$action $keyCodeName ${label(scanCode)} rep$repeat"
}
