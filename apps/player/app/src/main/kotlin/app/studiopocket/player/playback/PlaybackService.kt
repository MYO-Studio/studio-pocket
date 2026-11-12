// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.playback

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.studiopocket.player.library.LoadedPlaylist
import app.studiopocket.player.library.PlaybackState
import app.studiopocket.player.library.PlayMode
import app.studiopocket.player.library.StateStore
import app.studiopocket.player.settings.SettingsStore
import java.io.IOException

/**
 * Plays one playlist with the platform MediaPlayer (MP3/AAC on Android 4.4). Keeps a partial
 * wake lock while playing so audio continues with the screen off, pauses when headphones are
 * pulled or audio focus is lost, skips unreadable tracks, and persists the position to
 * STUDIOPOCKET/state/<playlist>.json every few seconds and on every pause/stop. Owns the
 * [VolumeLimiter] so the parent's volume cap holds with the screen off, and the one-off
 * [SleepTimer] (fades the player's own gain over the last 10 s, then pauses, or stops at a track change).
 */
class PlaybackService : Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {
    enum class Status { IDLE, PREPARING, PLAYING, PAUSED, STOPPED }

    interface Listener {
        fun onPlaybackChanged(service: PlaybackService)
    }

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val handler = Handler()
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateStore: StateStore? = null
    private var listeners = mutableListOf<Listener>()
    private var consecutiveErrors = 0
    private var pendingSeekMs = 0L
    private val sleepTimer = SleepTimer()
    private var ducked = false

    var playlist: LoadedPlaylist? = null; private set
    /** The loaded playlist's play mode, read from its saved state when it starts. */
    var mode = PlayMode.NORMAL; private set
    private val shuffle = ShuffleOrder()
    var trackIndex = 0; private set
    var status = Status.IDLE; private set
    var lastError: String? = null; private set
    lateinit var volumeLimiter: VolumeLimiter; private set
    val sleepMinutesLeft: Int? get() = sleepTimer.minutesLeft(SystemClock.elapsedRealtime())

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val saveTick = object : Runnable {
        override fun run() {
            if (status == Status.PLAYING) {
                saveState()
                notifyListeners()
                handler.postDelayed(this, SAVE_INTERVAL_MS)
            }
        }
    }

    private val sleepTick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (!sleepTimer.active) return
            if (sleepTimer.isDue(now)) { fireSleepTimer(); return }
            if (status == Status.PLAYING) applyGain()
            handler.postDelayed(this, SLEEP_TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudioPocket:playback")
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        volumeLimiter = VolumeLimiter(this, SettingsStore(this)).also { it.start() }
    }

    override fun onDestroy() {
        handler.removeCallbacks(sleepTick)
        handler.removeCallbacks(saveTick)
        volumeLimiter.stop()
        unregisterReceiver(noisyReceiver)
        release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    val positionMs: Long get() = if (seekingToMs >= 0L) seekingToMs else try { player?.takeIf { status == Status.PLAYING || status == Status.PAUSED }?.currentPosition?.toLong() ?: pendingSeekMs } catch (e: IllegalStateException) { 0L }
    val durationMs: Long get() = try { player?.takeIf { status == Status.PLAYING || status == Status.PAUSED }?.duration?.toLong() ?: (playlist?.manifest?.tracks?.getOrNull(trackIndex)?.durationMs ?: 0L) } catch (e: IllegalStateException) { 0L }
    val currentTrackTitle: String get() = playlist?.manifest?.tracks?.getOrNull(trackIndex)?.title ?: ""

    /** Starts (or resumes) a playlist. Resume position comes from the state store when present. */
    fun play(target: LoadedPlaylist, store: StateStore) {
        stateStore = store
        if (playlist?.id == target.id && (status == Status.PAUSED || status == Status.PLAYING)) {
            if (status == Status.PAUSED) resume()
            return
        }
        stopInternal(save = true)
        playlist = target
        val saved = store.read(target.id)
        mode = saved?.mode ?: PlayMode.NORMAL
        shuffle.reset()
        trackIndex = saved?.trackIndex?.takeIf { it in target.manifest.tracks.indices } ?: 0
        pendingSeekMs = if (saved?.trackIndex == trackIndex) saved.positionMs else 0L
        consecutiveErrors = 0
        startTrack()
    }

    fun togglePause() {
        when (status) {
            Status.PLAYING -> pause()
            Status.PAUSED -> resume()
            Status.STOPPED, Status.IDLE -> playlist?.let { p -> stateStore?.let { play(p, it) } }
            Status.PREPARING -> Unit
        }
    }

    fun pause() {
        if (status != Status.PLAYING) return
        player?.pause()
        status = Status.PAUSED
        saveState()
        releaseWakeLock()
        handler.removeCallbacks(saveTick)
        notifyListeners()
    }

    fun resume() {
        if (status != Status.PAUSED) return
        if (!requestFocus()) return
        volumeLimiter.enforce()
        acquireWakeLock()
        expireSleepIfDue()
        applyGain()
        player?.start()
        status = Status.PLAYING
        handler.post(saveTick)
        notifyListeners()
    }

    /** Starts a one-off countdown from now; 0 turns it off. */
    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepTick)
        sleepTimer.start(minutes, SystemClock.elapsedRealtime())
        if (sleepTimer.active) handler.postDelayed(sleepTick, SLEEP_TICK_MS)
        applyGain()
        notifyListeners()
    }

    /** Pauses only if playing (saving the resume position); paused, stopped or finished it just clears. */
    private fun fireSleepTimer() {
        if (status == Status.PLAYING) pause()
        handler.removeCallbacks(sleepTick)
        sleepTimer.cancel()
        applyGain()
        notifyListeners()
    }

    /** An overdue timer (the uptime Handler stalls in deep sleep) is cleared before we start, so Play never begins silent. */
    private fun expireSleepIfDue() {
        if (sleepDue()) fireSleepTimer()
    }

    private fun sleepDue() = sleepTimer.isDue(SystemClock.elapsedRealtime())

    /** The player's own gain: the sleep fade times the focus duck. Separate from the stream volume the limiter caps. */
    private fun applyGain() {
        val g = sleepTimer.gain(SystemClock.elapsedRealtime()) * (if (ducked) DUCK_GAIN else 1f)
        try { player?.setVolume(g, g) } catch (e: IllegalStateException) { /* player released */ }
    }

    fun next() = skip(+1)
    fun previous() = skip(-1)

    private fun skip(delta: Int) {
        val p = playlist ?: return
        val size = p.manifest.tracks.size
        val shuffleTarget = if (mode != PlayMode.SHUFFLE) null else if (delta > 0) shuffle.next(trackIndex, size) else shuffle.previous()
        applyChange(p, TrackChange.skip(trackIndex, delta, size, status == Status.PLAYING, sleepDue(), mode, shuffleTarget) ?: return)
    }

    /**
     * Sets the loaded playlist's play mode and saves it with the playlist's state at once: the live
     * position while playing or paused, else the saved state with only the mode changed.
     */
    fun setMode(next: PlayMode) {
        val p = playlist ?: return
        if (next == mode) return
        mode = next
        if (next == PlayMode.SHUFFLE) shuffle.reset()
        val state = if (status == Status.PLAYING || status == Status.PAUSED) PlaybackState(p.id, trackIndex, positionMs, StateStore.now(), mode = next)
            else stateStore?.read(p.id)?.copy(mode = next, updatedAt = StateStore.now()) ?: PlaybackState(p.id, trackIndex, 0L, StateStore.now(), mode = next)
        stateStore?.write(state)
        notifyListeners()
    }

    /**
     * The position a seek asked for until the player says it got there, so the progress bar does not
     * jump back to the old position for a moment. -1 when no seek is under way.
     */
    private var seekingToMs = -1L

    /** Moves the playing or paused track to [ms] (clamped to the track), as a scrub on Next/Previous asks. */
    fun seekTo(ms: Long) {
        val p = playlist ?: return
        val mp = player ?: return
        if (status != Status.PLAYING && status != Status.PAUSED) return
        val target = try { ms.coerceIn(0L, maxOf(0L, mp.duration.toLong() - 1L)) } catch (e: IllegalStateException) { return }
        seekingToMs = target
        mp.setOnSeekCompleteListener { if (player === it) { seekingToMs = -1L; notifyListeners() } }
        try { mp.seekTo(target.toInt()) } catch (e: IllegalStateException) { seekingToMs = -1L; return }
        stateStore?.write(PlaybackState(p.id, trackIndex, target, StateStore.now(), mode = mode))
        notifyListeners()
    }

    /** Plays track [index] of the loaded playlist from its start (the ring browses, the centre button picks). */
    fun playTrack(index: Int) {
        val p = playlist ?: return
        if (mode == PlayMode.SHUFFLE && index != trackIndex) shuffle.moved(trackIndex)
        applyChange(p, TrackChange.jump(index, p.manifest.tracks.size, status == Status.PLAYING, sleepDue()) ?: return)
    }

    private fun applyChange(p: LoadedPlaylist, change: TrackChange) {
        if (!change.play) { stopAt(p, change.index); return }
        trackIndex = change.index
        pendingSeekMs = 0L
        startTrack()
    }

    /** Stops and forgets the playlist (used when the storage goes away). */
    fun stop() {
        stopInternal(save = true)
        status = Status.STOPPED
        notifyListeners()
    }

    private fun startTrack() {
        val p = playlist ?: return
        val file = p.trackFile(trackIndex)
        release()
        status = Status.PREPARING
        notifyListeners()
        val mp = MediaPlayer()
        seekingToMs = -1L
        player = mp
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
        mp.setOnCompletionListener(this)
        mp.setOnErrorListener(this)
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare() // local files; synchronous prepare is quick and simpler on API 19
        } catch (e: IOException) {
            onTrackFailed("cannot open ${file.name}: ${e.message}")
            return
        } catch (e: IllegalStateException) {
            onTrackFailed("player state error on ${file.name}")
            return
        }
        if (!requestFocus()) {
            status = Status.PAUSED
            notifyListeners()
            return
        }
        if (pendingSeekMs > 0 && pendingSeekMs < mp.duration) mp.seekTo(pendingSeekMs.toInt())
        pendingSeekMs = 0L
        volumeLimiter.enforce()
        expireSleepIfDue()
        applyGain() // a fresh player starts at 1f, even mid-fade
        acquireWakeLock()
        mp.start()
        status = Status.PLAYING
        lastError = null
        consecutiveErrors = 0
        saveState()
        handler.removeCallbacks(saveTick)
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        notifyListeners()
    }

    private fun onTrackFailed(message: String) {
        Log.w(TAG, message)
        lastError = message
        consecutiveErrors++
        val size = playlist?.manifest?.tracks?.size ?: 0
        if (consecutiveErrors >= size) {
            stopInternal(save = false)
            status = Status.STOPPED
            notifyListeners()
        } else {
            skip(+1)
        }
    }

    /** Plays the next track, or stops: at the end of the playlist (Play starts from the top), or when the sleep timer is due (Play starts the next track). */
    override fun onCompletion(mp: MediaPlayer) {
        val p = playlist ?: return
        val size = p.manifest.tracks.size
        val shuffleNext = if (mode == PlayMode.SHUFFLE) shuffle.next(trackIndex, size) else 0
        val change = TrackChange.afterCompletion(trackIndex, size, sleepDue(), mode, shuffleNext)
        // Only Normal ends: the other modes play on, so only it can be finished.
        if (!change.play) { stopAt(p, change.index, finished = mode == PlayMode.NORMAL && trackIndex + 1 >= size); return }
        trackIndex = change.index
        pendingSeekMs = 0L
        startTrack()
    }

    /**
     * Stops so the next Play starts track [index] from the top. Saved explicitly rather than the
     * player's end-of-track position, which would seek into the wrong track. STOPPED, not PAUSED:
     * there is no player left to resume. A due sleep timer is cleared, restoring full gain.
     */
    private fun stopAt(p: LoadedPlaylist, index: Int, finished: Boolean = false) {
        trackIndex = index
        pendingSeekMs = 0L
        // [finished]: the playlist played to its end; the cover shows it finished until it plays again.
        stateStore?.write(PlaybackState(p.id, index, 0L, StateStore.now(), finished, mode))
        stopInternal(save = false)
        status = Status.STOPPED
        if (sleepDue()) fireSleepTimer() else notifyListeners()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        onTrackFailed("MediaPlayer error $what/$extra on $currentTrackTitle")
        return true
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { ducked = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { ducked = true; applyGain() }
            AudioManager.AUDIOFOCUS_GAIN -> { ducked = false; applyGain() }
        }
    }

    private fun requestFocus(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val granted = am.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) ducked = false // a granted request sends no GAIN callback
        return granted
    }

    private fun saveState() {
        val p = playlist ?: return
        stateStore?.write(PlaybackState(p.id, trackIndex, positionMs, StateStore.now(), mode = mode))
    }

    private fun stopInternal(save: Boolean) {
        if (save && (status == Status.PLAYING || status == Status.PAUSED)) saveState()
        handler.removeCallbacks(saveTick)
        release()
        releaseWakeLock()
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocus(this)
        ducked = false
    }

    private fun release() {
        player?.let { mp ->
            try { mp.reset() } catch (e: IllegalStateException) { /* already released */ }
            mp.release()
        }
        player = null
    }

    private fun acquireWakeLock() { wakeLock?.takeIf { !it.isHeld }?.acquire() }
    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.release() }

    private fun notifyListeners() { listeners.toList().forEach { it.onPlaybackChanged(this) } }

    companion object {
        private const val TAG = "StudioPocket"
        private const val SAVE_INTERVAL_MS = 5_000L
        private const val SLEEP_TICK_MS = 500L
        private const val DUCK_GAIN = 0.3f
    }
}
