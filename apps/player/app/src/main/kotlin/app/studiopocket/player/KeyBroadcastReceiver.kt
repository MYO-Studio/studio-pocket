// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Records MEDIA_BUTTON and the Innioasis vendor key broadcast so the diagnostics screen shows which
 * arrive, and wakes the screen for the player's own play/pause button ([ScreenWake]).
 */
class KeyBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val keyEvent = extras?.get(Intent.EXTRA_KEY_EVENT) as? KeyEvent
        if (intent.action == Intent.ACTION_MEDIA_BUTTON && keyEvent != null && ScreenWake.onMediaButton(context, keyEvent)) {
            EventLog.shared.add("screen woken by play/pause")
        }
        val detail = if (keyEvent != null) {
            Y2Keys.describe(
                if (keyEvent.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP",
                KeyEvent.keyCodeToString(keyEvent.keyCode),
                keyEvent.scanCode,
                keyEvent.repeatCount
            )
        } else {
            extras?.keySet()?.joinToString(",") { "$it=${extras.get(it)}" } ?: "no extras"
        }
        EventLog.shared.add("bcast ${intent.action?.substringAfterLast('.')} $detail")
    }
}
