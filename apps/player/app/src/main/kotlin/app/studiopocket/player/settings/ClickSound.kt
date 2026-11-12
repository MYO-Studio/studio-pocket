// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

/**
 * The click on each ring step and button press. It is not ours: the Y2's window policy
 * (PhoneWindowManager.interceptKeyBeforeQueueing in the stock 3.2.0 android.policy.jar) calls
 * AudioManager.playSoundEffect(FX_KEY_CLICK) on every DPAD, Centre, Back, Home, Menu and Play key down,
 * and AudioManager only plays it while Settings.System.SOUND_EFFECTS_ENABLED is non-zero. So the
 * setting is that system value. Pure, so it is unit-tested on the JVM.
 */
object ClickSound {
    val LABELS = listOf("Off", "On")

    /** The row index for the system value (absent reads as 0, as AudioManager does). */
    fun toIndex(systemValue: Int): Int = if (systemValue != 0) 1 else 0

    /** The system value for row [index]. */
    fun fromIndex(index: Int): Int = if (index == 1) 1 else 0
}
