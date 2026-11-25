// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

package app.studiopocket.player.library

import java.io.File
import java.io.IOException

/**
 * The player's two volumes (docs/architecture/library-format.md, "Memory card"): its own storage
 * (Environment.getExternalStorageDirectory, unchanged) and optionally a memory card, each with its own
 * STUDIOPOCKET/. Free of Android types: the activity passes in SECONDARY_STORAGE and
 * Environment.getStorageState, so discovery is unit-tested on the JVM.
 */
object Volumes {
    /** Environment.MEDIA_MOUNTED and MEDIA_MOUNTED_READ_ONLY, copied so this file needs no Android types. */
    const val MOUNTED = "mounted"
    const val MOUNTED_READ_ONLY = "mounted_ro"
    /** Where the Y2's microSD mounts when SECONDARY_STORAGE does not say (still to be confirmed on hardware). */
    const val FALLBACK_CARD = "/storage/sdcard1"

    fun isMounted(state: String?): Boolean = state == MOUNTED || state == MOUNTED_READ_ONLY

    /** SECONDARY_STORAGE's entries (split on ':') in order, then [FALLBACK_CARD]; blanks and repeats dropped. */
    fun cardCandidates(secondaryStorage: String?): List<File> =
        ((secondaryStorage ?: "").split(':').map { it.trim() }.filter { it.isNotEmpty() } + FALLBACK_CARD).distinct().map { File(it) }

    /**
     * The first mounted candidate that is not the internal volume ([internalBase]), compared by canonical
     * path so a symlink to internal never reads as a card. [stateOf] is Environment.getStorageState (null
     * or anything but mounted means not usable). Without a mounted card the result still names the first
     * other candidate and its state, for diagnostics.
     */
    fun findCard(internalBase: File, candidates: List<File>, stateOf: (File) -> String?): CardVolume {
        val others = candidates.filter { !sameDir(it, internalBase) }
        val states = others.map { it to (stateOf(it) ?: "unknown") }
        val mounted = states.firstOrNull { isMounted(it.second) }
        if (mounted != null) return CardVolume(mounted.first, mounted.second)
        val first = states.firstOrNull() ?: return CardVolume(null, "absent")
        return CardVolume(first.first, first.second)
    }

    fun sameDir(a: File, b: File): Boolean = canonical(a) == canonical(b)

    /** True when [file] is [base] or inside it (canonical paths, falling back to absolute ones). */
    fun isUnder(file: File, base: File): Boolean {
        val f = canonical(file)
        val b = canonical(base).trimEnd('/')
        return f == b || f.startsWith("$b/")
    }

    private fun canonical(f: File): String = try { f.canonicalPath } catch (e: IOException) { f.absolutePath }
}

/** A card candidate: [path] is the volume (the mount point), [state] its storage state. */
data class CardVolume(val path: File?, val state: String) {
    val mounted: Boolean get() = path != null && Volumes.isMounted(state)
    /** The card's STUDIOPOCKET/, only while it is mounted. */
    val root: File? get() = if (mounted) File(path, LibraryFormat.ROOT_DIR) else null
}

/** Whether a mounted card belongs to this player; only a [paired] card's playlists are shown. */
enum class Pairing(val paired: Boolean, val label: String) {
    PAIRED(true, "yes"),
    MARKED(true, "yes (marked now)"),
    NO_INTERNAL_MARKER(false, "no (player has no device.json)"),
    UNREADABLE(false, "no (card device.json unreadable)"),
    NOT_A_CARD(false, "no (card device.json is not a card's)"),
    FOREIGN(false, "no (another player's card)"),
    WRITE_FAILED(false, "no (could not mark the card)"),
}

object CardPairing {
    /**
     * Pairs the card whose STUDIOPOCKET/ is [cardRoot] with the player whose STUDIOPOCKET/ is
     * [internalRoot]. A card with no device.json is marked as this player's (same deviceId,
     * "storage": "card"); an existing marker is only ever read, never changed, so a card that is
     * another player's or cannot be read is left exactly as it is. Write failures go to [log].
     */
    fun pair(internalRoot: File, cardRoot: File, log: (String) -> Unit = {}): Pairing {
        val internal = read(File(internalRoot, LibraryFormat.DEVICE_FILE))?.takeIf { it.storage == Storage.INTERNAL }
        val file = File(cardRoot, LibraryFormat.DEVICE_FILE)
        if (file.exists()) {
            val card = read(file) ?: return Pairing.UNREADABLE
            if (card.storage != Storage.CARD) return Pairing.NOT_A_CARD
            if (internal == null) return Pairing.NO_INTERNAL_MARKER
            return if (card.deviceId == internal.deviceId) Pairing.PAIRED else Pairing.FOREIGN
        }
        if (internal == null) return Pairing.NO_INTERNAL_MARKER
        return if (mark(cardRoot, file, internal.deviceId, log)) Pairing.MARKED else Pairing.WRITE_FAILED
    }

    private fun read(file: File): DeviceMarker? = try {
        LibraryParser.parseDeviceMarker(file.readText())
    } catch (e: FormatException) {
        null
    } catch (e: IOException) {
        null
    }

    /** .part + rename, like StateStore's marker; the target does not exist, so the rename only creates. */
    private fun mark(cardRoot: File, file: File, deviceId: String, log: (String) -> Unit): Boolean {
        if (!cardRoot.isDirectory && !cardRoot.mkdirs()) { log("card: cannot create $cardRoot"); return false }
        val partial = File(cardRoot, LibraryFormat.DEVICE_FILE + LibraryFormat.PARTIAL_SUFFIX)
        return try {
            partial.writeText(LibraryParser.serializeDeviceMarker(deviceId, StateStore.now(), null, Storage.CARD))
            if (partial.renameTo(file)) true else { partial.delete(); log("card: cannot rename device.json.part"); false }
        } catch (e: Exception) {
            partial.delete()
            log("card: cannot write device.json: ${e.message}")
            false
        }
    }
}

/**
 * One library across both volumes: each root is loaded on its own ([LibraryLoader]); internal's
 * playlists come first in index order, then the card's, and a duplicate id keeps the first (during an
 * interrupted move the internal copy). State, recent and groups stay on internal; this only merges
 * what is shown.
 */
object MergedLibrary {
    fun load(internalRoot: File, cardRoot: File?): LoadedLibrary = merge(loadRoot(internalRoot), cardRoot?.let { loadRoot(it) })

    /** [LibraryLoader] skips the lock when there is no library.json yet; the desktop's first sync of a card is still a sync. */
    private fun loadRoot(root: File): LoadedLibrary {
        val library = LibraryLoader(root).load()
        return if (!library.syncInProgress && File(root, LibraryFormat.SYNC_LOCK_FILE).exists()) library.copy(syncInProgress = true) else library
    }

    /**
     * [card] is null when no paired card is mounted. With nothing to show, the problem is internal's,
     * else the card's; a card that merely has no library yet is not a problem when internal has one
     * (the screen then says "Add some…" as before rather than "No library yet").
     */
    fun merge(internal: LoadedLibrary, card: LoadedLibrary?): LoadedLibrary {
        if (card == null) return internal
        val seen = HashSet<String>()
        val playlists = (internal.playlists + card.playlists).filter { seen.add(it.id) }
        val problem = if (playlists.isNotEmpty()) null else internal.problem ?: card.problem?.takeUnless { it == LibraryLoader.NO_LIBRARY_YET }
        return LoadedLibrary(
            playlists = playlists,
            hidden = internal.hidden + card.hidden.map { (id, why) -> id to "card: $why" },
            syncInProgress = internal.syncInProgress || card.syncInProgress,
            problem = problem,
        )
    }
}
