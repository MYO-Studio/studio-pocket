// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.settings

import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import app.studiopocket.player.BuildConfigCompat
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import app.studiopocket.player.library.LibraryFormat
import app.studiopocket.player.playback.PlaybackService
import app.studiopocket.player.playback.SleepTimer
import app.studiopocket.player.playback.VolumeLimiter

/**
 * The hidden settings area: which [SettingsPage] is showing, its rows, and what each row does.
 * [SettingsNav] turns keys into actions; this applies them. Each page is a `xxxRows()` /
 * `onXxx(action)` pair, wired into [rows] and [onKey]. The activity renders after every key;
 * [onChanged] is for changes that arrive between keys. [onDiagnostics] opens the diagnostics screen.
 * [wrapText] lays a licence text out into screen lines and [textVisible] is how many fit at once
 * (both from the screen that draws LICENCE_TEXT).
 */
class SettingsController(
    private val activity: Activity,
    private val onChanged: () -> Unit,
    private val onDiagnostics: () -> Unit,
    private val wrapText: (String) -> List<String> = { it.lines() },
    private val textVisible: Int = 12,
) {
    var page = SettingsPage.MAIN; private set
    val nav = SettingsNav()
    var service: PlaybackService? = null
    private val store = SettingsStore(activity)
    private val bluetooth = BluetoothController(activity) { onBluetoothChanged() }
    /** The device the BT_DEVICE page is for. */
    private var btAddress: String? = null

    /** Set by the activity; shown on About. */
    var playlistCount = 0
    var batteryPercent = -1

    /** The LICENCE_TEXT page's document, its screen lines and the first line shown. */
    private var doc: LicenceDocs.Doc? = null
    var textLines: List<String> = emptyList(); private set
    var textTop = 0; private set

    /** The selection on each page we went down from, restored on the way back up. */
    private val returnSelection = ArrayList<Int>()

    fun open() = show(SettingsPage.MAIN)

    fun close() = show(SettingsPage.MAIN)

    /** Applies one key (ACTION_DOWN, no repeats). Returns false when Back on MAIN closed settings. */
    fun onKey(keyName: String): Boolean {
        // The text page has no rows: the ring and arrows (and Centre) scroll it a screen at a time.
        if (page == SettingsPage.LICENCE_TEXT) {
            val delta = TextLayout.keyDelta(keyName)
            if (delta != 0) { textTop = TextLayout.scroll(textTop, delta, textLines.size, textVisible); return true }
        }
        when (val action = nav.onKey(keyName, rows())) {
            SettingsNav.Action.None, SettingsNav.Action.Redraw -> Unit
            SettingsNav.Action.Back -> if (!up()) { close(); return false }
            else -> when (page) {
                SettingsPage.MAIN -> onMain(action)
                SettingsPage.BLUETOOTH -> onBluetooth(action)
                SettingsPage.BT_DEVICE -> onBtDevice(action)
                SettingsPage.VOLUME -> onVolume(action)
                SettingsPage.SLEEP -> onSleep(action)
                SettingsPage.SCREEN -> onScreen(action)
                SettingsPage.DATETIME -> onDateTime(action)
                SettingsPage.ABOUT -> onAbout(action)
                SettingsPage.LICENCES -> onLicences(action)
                SettingsPage.LICENCE_TEXT -> Unit
            }
        }
        return true
    }

    fun title(): String = when (page) {
        SettingsPage.MAIN -> "Settings"
        SettingsPage.BLUETOOTH -> "Bluetooth"
        SettingsPage.BT_DEVICE -> btDevice()?.name ?: "Bluetooth device"
        SettingsPage.VOLUME -> "Sound"
        SettingsPage.SLEEP -> "Sleep timer"
        SettingsPage.SCREEN -> "Screen"
        SettingsPage.DATETIME -> "Date & time"
        SettingsPage.ABOUT -> "About"
        SettingsPage.LICENCES -> "Licences"
        SettingsPage.LICENCE_TEXT -> doc?.label ?: "Licence"
    }

    fun rows(): List<SettingsRow> = when (page) {
        SettingsPage.MAIN -> mainRows()
        SettingsPage.BLUETOOTH -> bluetoothRows(bluetooth.state)
        SettingsPage.BT_DEVICE -> btDevice()?.let(::deviceRows).orEmpty()
        SettingsPage.VOLUME -> volumeRows()
        SettingsPage.SLEEP -> sleepRows()
        SettingsPage.SCREEN -> screenRows()
        SettingsPage.DATETIME -> dateTimeRows()
        SettingsPage.ABOUT -> aboutRows()
        SettingsPage.LICENCES -> LicenceDocs.ALL.map { SettingsRow("licence:${it.id}", it.label) }
        SettingsPage.LICENCE_TEXT -> emptyList()
    }

    fun footnote(): String? = when (page) {
        SettingsPage.BLUETOOTH, SettingsPage.BT_DEVICE -> bluetooth.state.message
        SettingsPage.VOLUME -> "Headphone buttons may still go louder than the limit"
        SettingsPage.DATETIME -> "Setting the time turns automatic time off"
        SettingsPage.SLEEP -> service?.sleepMinutesLeft?.let { "Stops in $it min" }
        SettingsPage.LICENCES -> "Studio Pocket ${BuildConfigCompat.versionName(activity)} is GPL-3.0-only"
        SettingsPage.LICENCE_TEXT -> TextLayout.position(textTop, textLines.size, textVisible)
        else -> null
    }

    /** The only place the page changes, so a page can stop work (scans, previews) on the way out. */
    private fun show(next: SettingsPage, keepStack: Boolean = false) {
        if (!keepStack) returnSelection.clear()
        page = next
        nav.reset()
        // BT_DEVICE is part of the Bluetooth page: the scan and receivers carry on between the two.
        if (next == SettingsPage.BLUETOOTH || next == SettingsPage.BT_DEVICE) bluetooth.start() else bluetooth.stop()
    }

    /** Up one level, restoring the selection we left. False on MAIN, which has no parent. */
    private fun up(): Boolean {
        val parent = page.parent ?: return false
        val selection = if (returnSelection.isEmpty()) 0 else returnSelection.removeAt(returnSelection.lastIndex)
        show(parent, keepStack = true)
        nav.selected = selection
        return true
    }

    /** Down one level, remembering where we were. */
    private fun enter(child: SettingsPage) {
        returnSelection.add(nav.selected)
        show(child, keepStack = true)
    }

    // --- main ---

    private fun mainRows() = listOf(
        SettingsRow("main.bluetooth", "Bluetooth"),
        SettingsRow("main.volume", "Sound"),
        SettingsRow("main.sleep", "Sleep timer"),
        SettingsRow("main.screen", "Screen"),
        SettingsRow("main.datetime", "Date & time"),
        SettingsRow("main.about", "About"),
        SettingsRow("main.stock", "Bluetooth (Android screen)"),
    )

    private fun onMain(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Open) return
        when (action.rowId) {
            "main.bluetooth" -> enter(SettingsPage.BLUETOOTH)
            "main.volume" -> enter(SettingsPage.VOLUME)
            "main.sleep" -> enter(SettingsPage.SLEEP)
            "main.screen" -> enter(SettingsPage.SCREEN)
            "main.about" -> enter(SettingsPage.ABOUT)
            "main.stock" -> openStockBluetooth()
            "main.datetime" -> enter(SettingsPage.DATETIME)
        }
    }

    /** Only the Bluetooth screen, never the whole Settings app. */
    private fun openStockBluetooth() = openStock(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth")

    /** One stock Android screen, never the whole Settings app. Back on it returns to our settings. */
    private fun openStock(action: String, what: String) {
        try {
            activity.startActivity(Intent(action))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no $what settings screen: $e")
        }
    }

    // --- bluetooth ---

    private fun onBluetooth(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Open) return
        val id = action.rowId
        when {
            id == "bt.power" -> bluetooth.setEnabled(!bluetooth.state.enabled)
            id == "bt.scan" -> bluetooth.scan()
            id == "bt.stock" -> openStockBluetooth()
            id.startsWith("bt.device:") -> { btAddress = id.substringAfter(':'); enter(SettingsPage.BT_DEVICE) }
            id.startsWith("bt.pair:") -> bluetooth.pair(id.substringAfter(':'))
        }
    }

    private fun onBtDevice(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Open) return
        val id = action.rowId
        val address = id.substringAfter(':')
        when {
            id.startsWith("bt.connect:") -> bluetooth.connect(address)
            id.startsWith("bt.disconnect:") -> bluetooth.disconnect(address)
            id.startsWith("bt.forget:") -> if (bluetooth.forget(address)) up()
        }
    }

    /** The BT_DEVICE page's device while it is still paired. */
    private fun btDevice(): BtDevice? = bluetooth.state.paired.firstOrNull { it.address == btAddress }

    /** A device page whose device went away (forgotten, or the radio turned off) goes back to the list. */
    private fun onBluetoothChanged() {
        if (page == SettingsPage.BT_DEVICE && btDevice() == null) up()
        onChanged()
    }

    // --- volume ---

    private val audio: AudioManager get() = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun volumeRows(): List<SettingsRow> {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return listOf(
            SettingsRow("volume.limit", "Volume limit", RowValue(VolumeCap.toIndex(store.volumeCap, max), VolumeCap.labels(max))),
            SettingsRow(
                "sound.click", "Click sound",
                RowValue(ClickSound.toIndex(readSystem(Settings.System.SOUND_EFFECTS_ENABLED, 0)), ClickSound.LABELS),
            ),
        )
    }

    /**
     * The click is written as it is previewed, so the next ring step already sounds (or doesn't).
     * The cap is saved and clamped at once; the service's limiter holds it from then on.
     */
    private fun onVolume(action: SettingsNav.Action) {
        val clickIndex = when {
            action is SettingsNav.Action.Preview && action.rowId == "sound.click" -> action.index
            action is SettingsNav.Action.Commit && action.rowId == "sound.click" -> action.index
            else -> null
        }
        if (clickIndex != null) { writeClickSound(ClickSound.fromIndex(clickIndex)); return }
        if (action !is SettingsNav.Action.Commit || action.rowId != "volume.limit") return
        store.volumeCap = VolumeCap.fromIndex(action.index, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        // Before the service binds, clamp directly; its limiter enforces again when it starts.
        val s = service
        if (s != null) s.volumeLimiter.enforce() else VolumeLimiter.enforce(audio, store.volumeCap)
    }

    // --- sleep ---

    private fun sleepRows(): List<SettingsRow> {
        val left = service?.sleepMinutesLeft
        val index = if (left == null) 0 else nearestOption(left)
        return listOf(SettingsRow("sleep.timer", "Stop playing after", RowValue(index, SleepTimer.OPTIONS.map { if (it == 0) "Off" else "$it min" })))
    }

    /** While running the row shows the smallest option ≥ the minutes left (the largest if none is). */
    private fun nearestOption(minutesLeft: Int): Int =
        SleepTimer.OPTIONS.indexOfFirst { it >= minutesLeft && it > 0 }.takeIf { it >= 0 } ?: SleepTimer.OPTIONS.lastIndex

    /** Saving the row unchanged leaves a running countdown alone rather than restarting it. */
    private fun onSleep(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Commit || action.rowId != "sleep.timer") return
        val s = service ?: return
        if (s.sleepMinutesLeft?.let(::nearestOption) == action.index) return
        s.setSleepTimer(SleepTimer.OPTIONS[action.index])
    }

    // --- screen ---

    private fun screenRows(): List<SettingsRow> {
        val brightness = readSystem(Settings.System.SCREEN_BRIGHTNESS, ScreenSteps.BRIGHTNESS[2])
        val timeout = readSystem(Settings.System.SCREEN_OFF_TIMEOUT, ScreenSteps.TIMEOUT_MS[2])
        return listOf(
            SettingsRow(
                "screen.brightness", "Brightness",
                RowValue(ScreenSteps.nearest(ScreenSteps.BRIGHTNESS, brightness), listOf("1", "2", "3", "4", "5")),
            ),
            SettingsRow(
                "screen.timeout", "Screen off after",
                RowValue(ScreenSteps.nearest(ScreenSteps.TIMEOUT_MS, timeout), ScreenSteps.TIMEOUT_LABELS),
            ),
        )
    }

    private fun onScreen(action: SettingsNav.Action) {
        when (action) {
            is SettingsNav.Action.Preview -> if (action.rowId == "screen.brightness") writeBrightness(action.index)
            is SettingsNav.Action.Commit -> when (action.rowId) {
                "screen.brightness" -> writeBrightness(action.index)
                "screen.timeout" -> writeSystem(Settings.System.SCREEN_OFF_TIMEOUT, ScreenSteps.TIMEOUT_MS[action.index])
            }
            else -> Unit
        }
    }

    /** Manual mode first, then the value, as the hardware probe did (it shows live). */
    private fun writeBrightness(index: Int) {
        val value = ScreenSteps.BRIGHTNESS[index]
        writeSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        writeSystem(Settings.System.SCREEN_BRIGHTNESS, value)
    }

    /** As the stock Settings app does: the system value, then the click sounds loaded or let go. */
    private fun writeClickSound(value: Int) {
        if (readSystem(Settings.System.SOUND_EFFECTS_ENABLED, 0) == value) return
        writeSystem(Settings.System.SOUND_EFFECTS_ENABLED, value)
        if (value != 0) audio.loadSoundEffects() else audio.unloadSoundEffects()
    }

    private fun readSystem(name: String, default: Int): Int =
        Settings.System.getInt(activity.contentResolver, name, default)

    /** A refused write (no WRITE_SETTINGS) is logged, never fatal. */
    private fun writeSystem(name: String, value: Int) {
        try {
            Settings.System.putInt(activity.contentResolver, name, value)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write $name: $e")
        }
    }

    // --- date & time ---

    private fun dateTimeRows(): List<SettingsRow> {
        val now = Calendar.getInstance()
        val is24 = DateFormat.is24HourFormat(activity)
        val zones = DateTimeRows.zones(TimeZone.getDefault().id)
        return listOf(
            SettingsRow("dt.hour", "Hour", RowValue(DateTimeRows.index(DateTimeRows.Field.HOUR, now), DateTimeRows.hours(is24))),
            SettingsRow("dt.minute", "Minute", RowValue(DateTimeRows.index(DateTimeRows.Field.MINUTE, now), DateTimeRows.minutes())),
            SettingsRow("dt.day", "Day", RowValue(DateTimeRows.index(DateTimeRows.Field.DAY, now), DateTimeRows.days(now))),
            SettingsRow("dt.month", "Month", RowValue(DateTimeRows.index(DateTimeRows.Field.MONTH, now), DateTimeRows.MONTHS)),
            SettingsRow("dt.year", "Year", RowValue(DateTimeRows.index(DateTimeRows.Field.YEAR, now), DateTimeRows.years())),
            SettingsRow("dt.24h", "24-hour clock", RowValue(if (is24) 1 else 0, DateTimeRows.ON_OFF)),
            SettingsRow("dt.zone", "Time zone", RowValue(DateTimeRows.zoneIndex(zones, TimeZone.getDefault()), zones.map { it.label })),
        )
    }

    /** Values are written when committed (Centre or Back), not while the ring previews them. */
    private fun onDateTime(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Commit) return
        val field = when (action.rowId) {
            "dt.hour" -> DateTimeRows.Field.HOUR
            "dt.minute" -> DateTimeRows.Field.MINUTE
            "dt.day" -> DateTimeRows.Field.DAY
            "dt.month" -> DateTimeRows.Field.MONTH
            "dt.year" -> DateTimeRows.Field.YEAR
            else -> null
        }
        when {
            field != null -> {
                val now = Calendar.getInstance()
                if (DateTimeRows.index(field, now) != action.index) setClock(DateTimeRows.withChoice(now, field, action.index).timeInMillis)
            }
            action.rowId == "dt.24h" -> writeSystem(Settings.System.TIME_12_24, if (action.index == 1) 24 else 12)
            action.rowId == "dt.zone" -> {
                val zone = DateTimeRows.zones(TimeZone.getDefault().id).getOrNull(action.index) ?: return
                if (zone.id != TimeZone.getDefault().id) setZone(zone.id)
            }
        }
    }

    /**
     * Turns automatic time off (it would put the clock back), then sets it. Both need the system
     * permissions a /system/priv-app APK holds (WRITE_SECURE_SETTINGS, SET_TIME); a refusal is logged.
     */
    private fun setClock(millis: Long) {
        try {
            Settings.Global.putInt(activity.contentResolver, Settings.Global.AUTO_TIME, 0)
            (activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setTime(millis)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot set the clock: $e")
        }
    }

    private fun setZone(id: String) {
        try {
            Settings.Global.putInt(activity.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0)
            (activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setTimeZone(id)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot set the time zone: $e")
        }
    }

    // --- about ---

    private fun aboutRows(): List<SettingsRow> {
        val storage = runCatching {
            if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) null
            else StatFs(Environment.getExternalStorageDirectory().path).let { it.availableBytes to it.totalBytes }
        }.getOrNull()
        return listOf(
            SettingsRow("about.version", "Studio Pocket ${BuildConfigCompat.versionName(activity)}", enabled = false),
            SettingsRow("about.android", "Android ${Build.DISPLAY}", enabled = false),
            SettingsRow("about.library", AboutText.library(playlistCount, LibraryFormat.VERSION), enabled = false),
            SettingsRow("about.storage", AboutText.storage(storage?.first, storage?.second), enabled = false),
            SettingsRow("about.battery", AboutText.battery(batteryPercent), enabled = false),
            SettingsRow("about.licences", "Licences"),
            SettingsRow("about.diagnostics", "Diagnostics"),
        )
    }

    private fun onAbout(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Open) return
        when (action.rowId) {
            "about.licences" -> enter(SettingsPage.LICENCES)
            "about.diagnostics" -> onDiagnostics()
        }
    }

    // --- licences ---

    private fun onLicences(action: SettingsNav.Action) {
        if (action !is SettingsNav.Action.Open) return
        val next = LicenceDocs.byRowId(action.rowId) ?: return
        doc = next
        textLines = wrapText(readAsset(next.asset))
        textTop = 0
        enter(SettingsPage.LICENCE_TEXT)
    }

    /** A missing asset shows as a one-line message rather than failing (check-apk-notices.sh keeps them in release APKs). */
    private fun readAsset(path: String): String =
        try {
            activity.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: IOException) {
            Log.w(TAG, "licence asset $path unavailable: $e")
            "$path is missing from this build."
        }

    companion object {
        private const val TAG = "StudioPocket"
    }
}
