// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import app.studiopocket.player.library.GroupDef
import app.studiopocket.player.library.GroupList
import app.studiopocket.player.library.Groups
import app.studiopocket.player.library.LibraryFormat
import app.studiopocket.player.library.LibraryLoader
import app.studiopocket.player.library.LoadedLibrary
import app.studiopocket.player.library.LoadedPlaylist
import app.studiopocket.player.library.PlaylistGroup
import app.studiopocket.player.library.PlaylistProgress
import app.studiopocket.player.library.RecentStore
import app.studiopocket.player.library.StateStore
import app.studiopocket.player.playback.PlaybackService
import app.studiopocket.player.settings.SettingsController
import app.studiopocket.player.settings.SettingsPage
import app.studiopocket.player.settings.SettingsStore
import app.studiopocket.player.settings.VolumeCap
import app.studiopocket.player.ui.Artwork
import app.studiopocket.player.ui.CoverThumbs
import app.studiopocket.player.ui.BackHold
import app.studiopocket.player.ui.GroupsScreen
import app.studiopocket.player.ui.LibraryScreen
import app.studiopocket.player.ui.NowPlayingScreen
import app.studiopocket.player.ui.PixelIcon
import app.studiopocket.player.ui.PlayerNav
import app.studiopocket.player.ui.PlayerView
import app.studiopocket.player.ui.RingLag
import app.studiopocket.player.ui.SeekHold
import app.studiopocket.player.ui.SettingsScreen
import app.studiopocket.player.ui.TextScreen
import app.studiopocket.player.ui.Ui
import app.studiopocket.player.ui.VolumeOverlay
import java.io.File

/**
 * The launcher. A Canvas-drawn [PlayerView] shows the groups, the card carousel and Now Playing
 * (ported from the author's earlier Y1 player) and the hidden settings; the diagnostics screen (under Settings >
 * About) stays a plain text layout. Everything is driven by hardware keys through [PlayerNav];
 * holding Back for 3 s on the root screen opens settings ([BackHold], [SettingsController]).
 * Reloads the library and groups whenever storage comes back from USB, and records each playlist
 * it starts in recent.json.
 */
class MainActivity : Activity(), PlaybackService.Listener {
    private val handler = Handler()
    private var nav = PlayerNav(0)
    private var library: LoadedLibrary = LoadedLibrary.empty("loading")
    private var groupDefs: List<GroupDef> = emptyList()
    private var groups: GroupList = GroupList.EMPTY
    /** The recent.json order the groups were last built from. */
    private var recentIds: List<String> = emptyList()
    private var service: PlaybackService? = null
    private var storageAvailable = true
    private var batteryPercent = -1
    private var charging = false
    private var batteryLine = "battery: ?"
    private lateinit var view: PlayerView
    private lateinit var diagnostics: View
    private val libraryScreen = LibraryScreen()
    private val groupsScreen = GroupsScreen()
    private val nowPlaying = NowPlayingScreen()
    private val settingsScreen = SettingsScreen()
    private val textScreen = TextScreen()
    private val backHold = BackHold()
    private val settingsStore = SettingsStore(this)
    private val settings = SettingsController(this, { render() }, { nav.showDiagnostics(); render() }, textScreen::wrap, TextScreen.VISIBLE)
    /** Whether the current Back press started on the root screen; a hold that began elsewhere never opens settings. */
    private var backFromHome = false
    /** After the hold fires, the rest of that Back press (repeats and the UP) is ignored. */
    private var swallowBack = false
    /** Late ring steps dropped since the last key that was acted on, logged once with that key. */
    private var droppedRingSteps = 0
    private val seekHold = SeekHold()
    /** Saved cover-bar positions by playlist id (null: never played), for the current cover-flow visit. */
    private val progressCache = HashMap<String, Float?>()
    /** The screen the last render drew, so arriving at the cover flow re-reads the saved positions. */
    private var lastRendered: PlayerNav.Screen? = null
    private val logListener: () -> Unit = { handler.post { if (nav.screen == PlayerNav.Screen.DIAGNOSTICS) renderDiagnostics() } }

    private val root: File get() = File(Environment.getExternalStorageDirectory(), LibraryFormat.ROOT_DIR)
    private val stateStore: StateStore get() = StateStore(root)
    private val recentStore: RecentStore get() = RecentStore(root)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as PlaybackService.LocalBinder).service.also { it.addListener(this@MainActivity) }
            settings.service = service
            render()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; settings.service = null }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    batteryPercent = if (level < 0) -1 else level * 100 / maxOf(scale, 1)
                    charging = plugged != 0
                    batteryLine = "battery: $batteryPercent% plugged=$plugged"
                    settings.batteryPercent = batteryPercent
                    if (nav.screen != PlayerNav.Screen.DIAGNOSTICS) render()
                    return
                }
                Intent.ACTION_MEDIA_MOUNTED -> { storageAvailable = true; reloadLibrary() }
                else -> {
                    storageAvailable = false
                    service?.stop()
                    leaveSettings()
                    nav.showRoot()
                    reloadLibrary()
                }
            }
            EventLog.shared.add("media ${intent.action?.substringAfterLast('.')} ${intent.data?.path}")
        }
    }

    /** Waking the screen shows home, not settings: settings closes when the screen goes off. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (inSettings()) { leaveSettings(); nav.closeSettings(); render() }
        }
    }

    /** Settings (and diagnostics under it) go home after two minutes without a key. */
    private val autoClose = Runnable {
        if (inSettings()) {
            EventLog.shared.add("settings auto-close")
            leaveSettings()
            nav.closeSettings()
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        Ui.font(this)
        setContentView(R.layout.activity_main)
        val frame = findViewById<FrameLayout>(R.id.root_frame)
        diagnostics = findViewById(R.id.screen_diagnostics)
        view = PlayerView(this)
        frame.addView(view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        EventLog.shared.add("start ${intent?.action?.substringAfterLast('.')}")
        startService(Intent(this, PlaybackService::class.java))
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onDestroy() {
        settings.close()   // stops the Bluetooth page's receivers and profile proxy if it was open
        unregisterReceiver(screenOffReceiver)
        handler.removeCallbacks(autoClose)
        service?.removeListener(this)
        unbindService(connection)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        EventLog.shared.addListener(logListener)
        registerReceiver(storageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_SHARED)
            addAction(Intent.ACTION_MEDIA_EJECT); addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        })
        registerReceiver(storageReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        storageAvailable = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
        // Back from the stock Bluetooth screen: USB may have taken storage while our receiver was off,
        // and the idle timer was held while that screen was in front.
        if (!storageAvailable && inSettings()) { leaveSettings(); nav.closeSettings() }
        if (inSettings()) restartAutoClose()
        reloadLibrary()
    }

    override fun onPause() {
        unregisterReceiver(storageReceiver)
        handler.removeCallbacks(autoClose)   // never closes settings behind the stock Bluetooth screen
        EventLog.shared.removeListener(logListener)
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(browseTimeout)
        handler.removeCallbacks(hideVolume)
        view.volume.hide()
        seekHold.cancel()
        nav.endBrowse()
        super.onPause()
    }

    private fun reloadLibrary() {
        library = if (storageAvailable) LibraryLoader(root).load() else LoadedLibrary.empty("Connected to computer")
        if (storageAvailable) stateStore.ensureDeviceMarker(BuildConfigCompat.versionName(this))
        Artwork.clear()
        PixelIcon.clear()
        progressCache.clear()
        CoverThumbs.clear()
        settings.playlistCount = library.playlists.size
        groupDefs = if (storageAvailable) Groups.load(root) else emptyList()
        refreshGroups(readRecent = nav.flat || nav.screen == PlayerNav.Screen.GROUPS)
        Log.i(TAG, "library: ${library.playlists.size} playlists, ${library.hidden.size} hidden, problem=${library.problem}; groups: ${groups.groups.size} shown of ${groupDefs.size} defined, flat=${groups.flat}")
        library.hidden.forEach { (id, why) -> Log.w(TAG, "hidden $id: $why") }
        service?.playlist?.let { playing -> if (library.playlists.none { it.id == playing.id }) service?.stop() }
        render()
    }

    /**
     * Rebuilds the visible groups from the library, groups.json and recent.json. Called on reload and
     * when a cover flow opens or closes. Recent is re-read ([readRecent]) as a cover flow opens or
     * closes but not while one is showing (a reload on resume keeps its order), so it never
     * reorders under the child's thumb.
     */
    private fun refreshGroups(readRecent: Boolean) {
        if (!storageAvailable) recentIds = emptyList()
        else if (readRecent) recentIds = recentStore.read()
        groups = Groups.build(library.playlists, groupDefs, recentIds, root)
        nav.setGroups(groups.groups.map { PlayerNav.Group(it.key, it.playlists.size) }, groups.flat)
    }

    private val openGroup: PlaylistGroup get() = groups.groups[nav.group]

    /** The playlist the service has (playing or paused), if any. */
    private val currentPlaylist: LoadedPlaylist?
        get() = service?.let { s -> s.playlist?.takeIf { s.status == PlaybackService.Status.PLAYING || s.status == PlaybackService.Status.PAUSED } }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        // A ring step that sat in the input queue behind a fast spin is dropped unlogged ([RingLag]).
        if (RingLag.drop(name, event.eventTime, SystemClock.uptimeMillis())) { droppedRingSteps++; return true }
        if (droppedRingSteps > 0) { EventLog.shared.add("ring: dropped $droppedRingSteps late steps"); droppedRingSteps = 0 }
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            EventLog.shared.add(Y2Keys.describe(if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP", name, event.scanCode, event.repeatCount))
        }
        if (event.action == KeyEvent.ACTION_DOWN && inSettings()) restartAutoClose()   // any key, volume included
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        // While we are in front, Volume keys (and their repeats) set the music volume themselves and show
        // our own readout instead of Android's popup, stopping at the volume cap. On the diagnostics screen
        // they go to the system. Screen-off presses go straight to the system; the service's VolumeLimiter
        // clamps those.
        if (volumeKey && nav.screen != PlayerNav.Screen.DIAGNOSTICS) {
            if (event.action == KeyEvent.ACTION_DOWN) changeVolume(up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (VolumeCap.blocksUp(audio.getStreamVolume(AudioManager.STREAM_MUSIC), settingsStore.volumeCap)) return true
        }
        if (volumeKey || event.keyCode == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (down && event.repeatCount == 0) { backFromHome = nav.isRoot; swallowBack = false }
            if (swallowBack) {
                if (event.action == KeyEvent.ACTION_UP) swallowBack = false
                return true
            }
        }
        if (backHold.onKey(name, down, event.repeatCount, event.eventTime, event.downTime) && backFromHome && nav.isRoot) {
            swallowBack = true
            EventLog.shared.add("settings open")
            nav.openSettings()
            settings.open()
            restartAutoClose()
            render()
            return true
        }
        // Next/Previous on the player: skip on release, scrub while held ([SeekHold]).
        val seekDirection = SeekHold.direction(name)
        if (seekDirection != 0 && (seekHold.active || nav.screen == PlayerNav.Screen.PLAYER)) {
            onSeekKey(event, name, seekDirection)
            return true
        }
        if (!down || event.repeatCount > 0) return true
        onNavKey(name)
        return true
    }

    /** One Next/Previous event on the player screen. */
    private fun onSeekKey(event: KeyEvent, name: String, direction: Int) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                // Only a playing or paused track can scrub; otherwise a hold still skips on release.
                val s = service?.takeIf { it.status == PlaybackService.Status.PLAYING || it.status == PlaybackService.Status.PAUSED }
                seekHold.press(direction, event.downTime, s?.positionMs ?: 0L, s?.durationMs ?: 0L)
            } else if (seekHold.active && seekHold.hold(event.eventTime)) {
                if (nav.endBrowse()) handler.removeCallbacks(browseTimeout)
                render()
            }
            KeyEvent.ACTION_UP -> when (val r = seekHold.release(event.isCanceled)) {
                is SeekHold.Release.Skip -> onNavKey(name)
                is SeekHold.Release.Seek -> { service?.seekTo(r.positionMs); render() }
                SeekHold.Release.None -> render()
            }
        }
    }

    /** A key press (down, not a repeat) for the navigation model and what it asks for. */
    private fun onNavKey(name: String) {
        val screen = nav.screen
        val s = service
        val action = nav.onKey(name, hasPlayback = currentPlaylist != null, playingTrack = s?.trackIndex ?: 0, trackCount = s?.playlist?.manifest?.tracks?.size ?: 0)
        handler.removeCallbacks(browseTimeout)
        if (nav.browsing != null) handler.postDelayed(browseTimeout, BROWSE_IDLE_MS)
        if (screen == PlayerNav.Screen.SETTINGS && action == PlayerNav.Action.None) {
            if (!settings.onKey(name)) { leaveSettings(); nav.closeSettings() }
        }
        when (action) {
            is PlayerNav.Action.PlaySelected -> openGroup.playlists.getOrNull(action.index)?.let { p ->
                // Recent only records what playback was asked to start: not before the service is bound.
                service?.let { it.play(p, stateStore); recentStore.recordPlayed(p.id) }
            }
            is PlayerNav.Action.OpenedGroup -> {
                refreshGroups(readRecent = true)
                val playing = currentPlaylist?.id
                nav.focusPlaylist(openGroup.playlists.indexOfFirst { it.id == playing })
            }
            PlayerNav.Action.BackToGroups -> refreshGroups(readRecent = true)
            PlayerNav.Action.TogglePause -> service?.togglePause()
            PlayerNav.Action.NextTrack -> service?.next()
            PlayerNav.Action.PreviousTrack -> service?.previous()
            is PlayerNav.Action.PlayTrack -> service?.playTrack(action.index)
            PlayerNav.Action.Redraw, PlayerNav.Action.None -> Unit
        }
        render()
    }

    /** One Volume key press: a step on the music stream with no system UI or sound, then our readout. */
    private fun changeVolume(up: Boolean) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val cap = settingsStore.volumeCap
        val target = VolumeCap.step(current, max, cap, up)
        if (target != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        view.volume.show(target, max, if (cap == VolumeCap.NONE) -1 else cap)
        view.invalidate()
        handler.removeCallbacks(hideVolume)
        handler.postDelayed(hideVolume, VolumeOverlay.SHOW_MS)
    }

    private val hideVolume = Runnable { view.volume.hide(); view.invalidate() }

    private fun inSettings() = nav.screen == PlayerNav.Screen.SETTINGS || nav.screen == PlayerNav.Screen.DIAGNOSTICS

    private fun restartAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, SETTINGS_IDLE_MS)
    }

    /** Resets the settings pages and stops the idle timer; the caller moves [nav] home. */
    private fun leaveSettings() {
        settings.close()
        handler.removeCallbacks(autoClose)
    }

    override fun onBackPressed() { /* a launcher never finishes on BACK; handled in dispatchKeyEvent */ }

    override fun onPlaybackChanged(service: PlaybackService) { render() }

    // --- rendering ---

    private fun render() {
        renderScreen()
        lastRendered = nav.screen
    }

    private fun renderScreen() {
        diagnostics.visibility = if (nav.screen == PlayerNav.Screen.DIAGNOSTICS) View.VISIBLE else View.GONE
        view.visibility = if (nav.screen == PlayerNav.Screen.DIAGNOSTICS) View.GONE else View.VISIBLE
        when (nav.screen) {
            PlayerNav.Screen.GROUPS -> renderGroups()
            PlayerNav.Screen.HOME -> renderHome()
            PlayerNav.Screen.PLAYER -> renderPlayer()
            PlayerNav.Screen.SETTINGS -> renderSettings()
            PlayerNav.Screen.DIAGNOSTICS -> renderDiagnostics()
        }
    }

    private fun renderGroups() {
        val s = service
        groupsScreen.update(GroupsScreen.State(
            groups = groups,
            library = library,
            storageAvailable = storageAvailable,
            playingTitle = currentPlaylist?.title,
            playing = s?.status == PlaybackService.Status.PLAYING,
            batteryPercent = batteryPercent,
            charging = charging,
        ))
        groupsScreen.select(nav.group)
        view.screen = groupsScreen
    }

    private fun renderHome() {
        val s = service
        val playing = currentPlaylist
        val group = openGroup
        libraryScreen.update(LibraryScreen.State(
            library = library,
            playlists = group.playlists,
            groupKey = group.key,
            groupLabel = if (groups.flat) null else "‹ ${group.title}",
            storageAvailable = storageAvailable,
            playingTitle = playing?.title,
            playing = s?.status == PlaybackService.Status.PLAYING,
            batteryPercent = batteryPercent,
            charging = charging,
            progress = progressFor(group.playlists),
        ))
        libraryScreen.select(nav.selected)
        view.screen = libraryScreen
    }

    /**
     * The cover bars for [playlists]: live from the service for its playlist, else from each saved
     * state. Saved states are read once per visit to the cover flow ([progressCache] is cleared on the
     * way in and when the library reloads), not on every ring step.
     */
    private fun progressFor(playlists: List<LoadedPlaylist>): Map<String, Float> {
        if (lastRendered != PlayerNav.Screen.HOME) progressCache.clear()
        val s = service
        val live = s?.playlist?.takeIf { s.status == PlaybackService.Status.PLAYING || s.status == PlaybackService.Status.PAUSED }
        val result = HashMap<String, Float>()
        for (p in playlists) {
            val f = if (p.id == live?.id) {
                PlaylistProgress.fraction(p.manifest.tracks, s.trackIndex, s.positionMs)
            } else if (storageAvailable) {
                if (!progressCache.containsKey(p.id)) progressCache[p.id] = PlaylistProgress.fraction(p.manifest.tracks, stateStore.read(p.id))
                progressCache[p.id]
            } else null
            if (f != null) result[p.id] = f
        }
        return result
    }

    private fun renderPlayer() {
        val s = service
        val status = when (s?.status) {
            PlaybackService.Status.PLAYING -> NowPlayingScreen.Status.PLAYING
            PlaybackService.Status.PAUSED -> NowPlayingScreen.Status.PAUSED
            PlaybackService.Status.PREPARING -> NowPlayingScreen.Status.PREPARING
            else -> NowPlayingScreen.Status.STOPPED
        }
        val browsing = nav.browsing?.takeIf { s?.playlist?.manifest?.tracks?.indices?.contains(it) == true }
        nowPlaying.update(NowPlayingScreen.State(
            playlist = s?.playlist,
            trackIndex = s?.trackIndex ?: 0,
            title = s?.currentTrackTitle ?: "",
            positionMs = if (seekHold.scrubbing) seekHold.previewMs else s?.positionMs ?: 0L,
            durationMs = s?.durationMs ?: 0L,
            status = status,
            error = s?.lastError,
            sleepMinutesLeft = s?.sleepMinutesLeft,
            batteryPercent = batteryPercent,
            charging = charging,
            browsing = browsing,
            scrub = if (seekHold.scrubbing) seekHold.direction else 0,
        ))
        view.screen = nowPlaying
        handler.removeCallbacks(progressTick)
        if (s?.status == PlaybackService.Status.PLAYING) handler.postDelayed(progressTick, 1000)
        else if (s?.sleepMinutesLeft != null) handler.postDelayed(progressTick, 15_000) // paused with a timer: keep the minutes current
    }

    private fun renderSettings() {
        if (settings.page == SettingsPage.LICENCE_TEXT) {
            textScreen.update(TextScreen.State(settings.title(), settings.textLines, settings.textTop, settings.footnote()))
            view.screen = textScreen
            return
        }
        val sn = settings.nav
        settingsScreen.update(SettingsScreen.State(settings.title(), settings.rows(), sn.selected, sn.editing, sn.editIndex, settings.footnote()))
        view.screen = settingsScreen
    }

    private val progressTick = Runnable { if (nav.screen == PlayerNav.Screen.PLAYER) renderPlayer() }
    /** A ring browse left alone: back to the playing track. */
    private val browseTimeout = Runnable { if (nav.endBrowse()) render() }

    private fun renderDiagnostics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val touch = packageManager.hasSystemFeature("android.hardware.touchscreen")
        findViewById<TextView>(R.id.diag_device).text = listOf(
            "${Build.DISPLAY} android ${Build.VERSION.RELEASE} api ${Build.VERSION.SDK_INT} ${Build.MODEL}/${Build.DEVICE}",
            "panel ${metrics.widthPixels}x${metrics.heightPixels} dpi ${metrics.densityDpi} density ${metrics.density} touch=$touch",
            "pkg $packageName v${BuildConfigCompat.versionName(this)}  $batteryLine"
        ).joinToString("\n")
        val lines = EventLog.shared.snapshot()
        findViewById<TextView>(R.id.diag_keys).text = if (lines.isEmpty()) getString(R.string.waiting_for_key) else lines.joinToString("\n")
        val primary = Environment.getExternalStorageDirectory()
        val secondary = System.getenv("SECONDARY_STORAGE")?.split(':')?.firstOrNull()
        val storage = mutableListOf("storage $primary [${Environment.getExternalStorageState()}] secondary=${secondary ?: "-"}")
        storage += "library: ${library.playlists.size} shown, ${library.hidden.size} hidden${library.problem?.let { " – $it" } ?: ""}"
        storage += "groups: ${groupDefs.size} defined, ${groups.groups.size} shown${if (groups.flat) " (flat)" else ""}; recent: ${groups.groups.firstOrNull { it.kind == PlaylistGroup.Kind.RECENT }?.playlists?.size ?: 0}"
        library.hidden.take(3).forEach { (id, why) -> storage += "  hidden $id: $why" }
        storage += StorageListing.describe(root, 6)
        findViewById<TextView>(R.id.diag_storage).text = storage.joinToString("\n")
    }

    companion object {
        private const val TAG = "StudioPocket"
        private const val SETTINGS_IDLE_MS = 120_000L
        /** How long a ring browse on the player screen waits for centre before showing the playing track again. */
        private const val BROWSE_IDLE_MS = 5_000L
    }
}

/** versionName via PackageManager so the activity has no BuildConfig dependency. */
object BuildConfigCompat {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
