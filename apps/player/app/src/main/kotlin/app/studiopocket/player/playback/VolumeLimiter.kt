// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import android.bluetooth.BluetoothA2dp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.studiopocket.player.EventLog
import app.studiopocket.player.settings.SettingsStore
import app.studiopocket.player.settings.VolumeCap

/**
 * Holds the music stream at or below the parent-set cap ([SettingsStore.volumeCap]). Owned by
 * [PlaybackService] so it works with the screen off and whatever is in front. The Y2 keeps a separate
 * music volume per output (speaker, wired, Bluetooth), so plugging in headphones or connecting
 * Bluetooth can jump above the cap: every volume change (any stream; [enforce] is cheap and only acts
 * above the cap) and route change clamps at once and again [RECHECK_MS] later, after the route's
 * volume has switched. With no cap it never touches the volume.
 */
class VolumeLimiter(private val context: Context, private val store: SettingsStore) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val recheck = Runnable { enforce() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            enforce()
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, RECHECK_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        })
        enforce()
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(recheck)
        context.unregisterReceiver(receiver)
    }

    /** Sets the music stream down to the cap if it is above it; otherwise does nothing. */
    fun enforce() = enforce(audio, store.volumeCap)

    companion object {
        private const val TAG = "StudioPocket"
        const val RECHECK_MS = 500L

        // Hidden in API 19's AudioManager; the string is stable across 4.x (hardware probe H5).
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        /** For callers without a running limiter (the settings page before the service binds). */
        fun enforce(audio: AudioManager, cap: Int) {
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = VolumeCap.target(current, cap) ?: return
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "volume capped $current -> $target")
            EventLog.shared.add("volume capped $current -> $target")
        }
    }
}
