// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Player settings that Android doesn't own, in the app's private SharedPreferences (on /data, so
 * they survive USB syncs, when the FAT volume goes away). Safe to construct before the context is
 * attached: the preferences are opened on first use.
 */
class SettingsStore(private val context: Context) {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The volume cap in music-stream steps, or [VolumeCap.NONE]. Written synchronously. */
    var volumeCap: Int
        get() = prefs.getInt(KEY_VOLUME_CAP, VolumeCap.NONE)
        set(value) { prefs.edit().putInt(KEY_VOLUME_CAP, value).commit() }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_VOLUME_CAP = "volume_cap"
    }
}
