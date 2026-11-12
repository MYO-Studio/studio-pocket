// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent

/**
 * Turns the screen on for the player's own play/pause button. The arrows, centre and back wake the
 * screen through the key layout the installer marks (firmware-tools keylayout.ts); the ring does
 * not, as it turns too easily by accident. Android 4.4 never lets a media key turn the screen on,
 * whatever its flags: with the screen off it hands play/pause to apps as a MEDIA_BUTTON broadcast
 * instead ([KeyBroadcastReceiver]). Like the other buttons, that first press only wakes the screen;
 * it does not also pause or play.
 */
object ScreenWake {
    /** The Y2's play/pause button (Y2Keys.kt). Headset and Bluetooth media keys carry other scan codes and never wake it. */
    const val PLAY_PAUSE_SCAN_CODE = 164
    /** Long enough for the screen to come on; after that it follows the normal screen-off timeout. */
    private const val HOLD_MS = 1_000L

    /** Pure, for the JVM tests: a fresh press of the player's play/pause while the screen is off. */
    fun shouldWake(screenOn: Boolean, action: Int, repeatCount: Int, scanCode: Int): Boolean =
        !screenOn && action == KeyEvent.ACTION_DOWN && repeatCount == 0 && scanCode == PLAY_PAUSE_SCAN_CODE

    /** Wakes the screen when [event] calls for it; true when it did. */
    fun onMediaButton(context: Context, event: KeyEvent): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION") // isScreenOn is the API 19 call; isInteractive arrived in API 20.
        if (!shouldWake(power.isScreenOn, event.action, event.repeatCount, event.scanCode)) return false
        // SCREEN_BRIGHT_WAKE_LOCK with ACQUIRE_CAUSES_WAKEUP is how an API 19 app turns the screen on
        // from the background; ON_AFTER_RELEASE leaves it on for the usual timeout once released.
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "StudioPocket:wake",
        )
        lock.acquire(HOLD_MS)
        return true
    }
}
