// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import {
    DEVICE_FILE, DEVICE_FORMAT, FORMAT_VERSION, ID_PATTERN, PLAYLISTS_DIR, ROOT_DIR,
    checkLibrary, type DeviceMarker, type LibraryCheck,
} from '@studio-pocket/library-format';
import { applySync, type ApplyOptions, type ApplyResult, type ProgressEvent } from './apply.js';
import { writeAtomic } from './device.js';
import { placePlaylists, VOLUMES, type Placement, type Volume } from './placement.js';
import { planTransfer, type PlaylistAction, type SyncPlan, type TransferRequest } from './plan.js';
import type { SyncFs } from './fs.js';

/**
 * One library across the player's internal storage and its memory card
 * (docs/architecture/library-format.md, "Memory card"). Each volume keeps its own
 * STUDIOPOCKET/ and library.json and is written by planTransfer/applySync exactly as before;
 * this layer decides which volume each playlist goes to, moves playlists that no longer fit
 * where they are, and keeps every resume position on the internal storage.
 */
export interface PlayerRoots {
    /** `<internal volume>/STUDIOPOCKET` */
    internal: string;
    /** `<card volume>/STUDIOPOCKET`, when the player's card is connected. */
    card?: string | null;
}

export const rootsOf = (r: PlayerRoots): Partial<Record<Volume, string>> => (r.card ? { internal: r.internal, card: r.card } : { internal: r.internal });

/** The player's library as one: internal playlists first, then the card's; a duplicate shows the internal copy. */
export interface PlayerCheck {
    merged: LibraryCheck;
    perVolume: Partial<Record<Volume, LibraryCheck>>;
    /** The volume each shown playlist is read from. */
    location: Map<string, Volume>;
}

export function mergeChecks(perVolume: Partial<Record<Volume, LibraryCheck>>): PlayerCheck {
    const internal = perVolume.internal ?? null;
    const card = perVolume.card ?? null;
    const location = new Map<string, Volume>();
    const playlists: LibraryCheck['playlists'] = [];
    for (const v of VOLUMES) {
        for (const p of perVolume[v]?.playlists ?? []) {
            const at = playlists.findIndex((o) => o.id === p.id);
            if (at === -1) { location.set(p.id, v); playlists.push(p); continue; }
            // A complete copy wins over an incomplete one (as on the player, which only shows complete ones); otherwise internal's.
            if (!playlists[at]!.ok && p.ok) { location.set(p.id, v); playlists[at] = p; }
        }
    }
    const indexes = VOLUMES.map((v) => perVolume[v]?.index ?? null).filter((i) => i !== null);
    const first = indexes[0] ?? null;
    const index = first && { ...first, playlists: indexes.flatMap((i) => i.playlists).filter((e, n, all) => all.findIndex((o) => o.id === e.id) === n) };
    const problems = [...(internal?.problems ?? []), ...(card?.problems ?? []).map((p) => `memory card: ${p}`)];
    const merged: LibraryCheck = {
        index,
        syncLockPresent: VOLUMES.some((v) => perVolume[v]?.syncLockPresent),
        playlists,
        groups: internal?.groups ?? null,
        recent: internal?.recent ?? null,
        problems,
    };
    return { merged, perVolume, location };
}

export async function checkPlayer(fs: SyncFs, roots: PlayerRoots, options: { verifyHashes?: boolean } = {}): Promise<PlayerCheck> {
    const perVolume: Partial<Record<Volume, LibraryCheck>> = {};
    for (const [v, root] of Object.entries(rootsOf(roots)) as [Volume, string][]) perVolume[v] = await checkLibrary(fs, root, options);
    return mergeChecks(perVolume);
}

/**
 * Writes `<volume>/STUDIOPOCKET/device.json` as the memory card of the player `deviceId`, replacing
 * whatever marker was there (a card moved from another player). Its playlists stay where they are.
 */
export async function markAsCard(fs: SyncFs, volume: string, deviceId: string, createdBy: string): Promise<DeviceMarker> {
    const root = fs.join(volume, ROOT_DIR);
    await fs.mkdirp(root);
    const marker: DeviceMarker = { format: DEVICE_FORMAT, formatVersion: FORMAT_VERSION, deviceId, createdAt: new Date().toISOString(), createdBy, storage: 'card' };
    await writeAtomic(fs, fs.join(root, DEVICE_FILE), JSON.stringify(marker, null, 2) + '\n');
    return marker;
}

export interface AcrossPlan {
    roots: PlayerRoots;
    /** One plan per volume that has work (sends placed there and removals of what is there). */
    plans: Partial<Record<Volume, SyncPlan>>;
    placement: Placement;
    /** Old copies to remove after every volume's plan has been applied (moves, and duplicates of unsent playlists). */
    cleanup: { volume: Volume; ids: string[] }[];
    bytesToCopy: number;
    /**
     * Every planned playlist action across the volumes with work, one per id: what this transfer
     * does to each (a playlist untouched on one volume and sent to the other shows as sent).
     */
    playlists: PlaylistAction[];
    /** The merged view of the player before the transfer. */
    before: PlayerCheck;
}

/** Playlist folders on a volume, complete or not (`.part` folders and macOS `._` files excluded). */
async function foldersOn(fs: SyncFs, root: string): Promise<Set<string>> {
    return new Set((await fs.readdir(fs.join(root, PLAYLISTS_DIR))).filter((e) => e.isDir && ID_PATTERN.test(e.name)).map((e) => e.name));
}

export interface AcrossOptions {
    verifyHashes?: boolean;
    reserveBytes?: number;
    freeBytes?: (dir: string) => Promise<number | null>;
}

export const DEFAULT_RESERVE_BYTES = 16 * 1024 * 1024;

/**
 * Plans a transfer across the player's volumes. Throws NoRoomError when the sent playlists cannot
 * all be placed. Without a card this is planTransfer on the internal root plus the free-space check.
 */
export async function planAcross(fs: SyncFs, roots: PlayerRoots, request: TransferRequest, options: AcrossOptions = {}): Promise<AcrossPlan> {
    const sendIds = new Set(request.send.map((p) => p.manifest.id));
    const both = request.remove.filter((id) => sendIds.has(id));
    if (both.length) throw new Error(`cannot both send and remove: ${both.join(', ')}`);
    const byVolume = rootsOf(roots);
    const volumes = Object.keys(byVolume) as Volume[];
    const reserveBytes = options.reserveBytes ?? DEFAULT_RESERVE_BYTES;

    // What each playlist would cost on each volume, and where everything is now.
    const trial: Partial<Record<Volume, SyncPlan>> = {};
    const folders: Partial<Record<Volume, Set<string>>> = {};
    const free: Partial<Record<Volume, number | null>> = {};
    for (const v of volumes) {
        trial[v] = await planTransfer(fs, byVolume[v]!, { send: request.send, remove: [] }, options);
        folders[v] = await foldersOn(fs, byVolume[v]!);
        free[v] = await (options.freeBytes ?? fs.freeBytes)(byVolume[v]!);
    }
    const before = mergeChecks(Object.fromEntries(volumes.map((v) => [v, trial[v]!.deviceState])));
    const items = request.send.map((p) => {
        const id = p.manifest.id;
        const cost = { internal: 0, card: 0 } as Record<Volume, number>;
        for (const v of volumes) cost[v] = trial[v]!.playlists.find((a) => a.id === id)!.bytesToCopy;
        return { id, on: volumes.filter((v) => folders[v]!.has(id)), cost };
    });
    const placement = placePlaylists({ items, free, reserveBytes });

    // Duplicates of playlists not sent this time: the internal copy is the one the player shows;
    // the card's goes once the internal one is complete.
    const cleanup = new Map<Volume, Set<string>>();
    const addCleanup = (v: Volume, id: string) => { if (!cleanup.has(v)) cleanup.set(v, new Set()); cleanup.get(v)!.add(id); };
    for (const m of placement.moves) addCleanup(m.from, m.id);
    if (folders.card) {
        // Only an exact duplicate (same manifest on both): if the copies differ, nothing says which is
        // newer, so both stay until the playlist is next sent.
        const okInternal = new Set(before.perVolume.internal?.playlists.filter((p) => p.ok).map((p) => p.id) ?? []);
        const sha = (v: Volume, id: string) => before.perVolume[v]?.index?.playlists.find((e) => e.id === id)?.manifestSha256;
        for (const id of folders.card) {
            if (sendIds.has(id) || request.remove.includes(id) || !okInternal.has(id)) continue;
            const a = sha('internal', id);
            if (a !== undefined && a === sha('card', id)) addCleanup('card', id);
        }
    }

    const plans: Partial<Record<Volume, SyncPlan>> = {};
    for (const v of volumes) {
        const send = request.send.filter((p) => placement.target.get(p.manifest.id) === v);
        const remove = request.remove.filter((id) => folders[v]!.has(id));
        if (!send.length && !remove.length) continue;
        plans[v] = send.length === request.send.length && !remove.length ? trial[v]! : await planTransfer(fs, byVolume[v]!, { send, remove }, options);
    }
    const bytesToCopy = VOLUMES.reduce((n, v) => n + (plans[v]?.bytesToCopy ?? 0), 0);
    const actions = new Map<string, PlaylistAction>();
    for (const a of VOLUMES.flatMap((v) => plans[v]?.playlists ?? [])) {
        if (!actions.has(a.id) || actions.get(a.id)!.untouched) actions.set(a.id, a);
    }
    return { roots, plans, placement, cleanup: [...cleanup.entries()].map(([volume, ids]) => ({ volume, ids: [...ids].sort() })), bytesToCopy, playlists: [...actions.values()], before };
}

export interface AcrossResult {
    perVolume: Partial<Record<Volume, ApplyResult>>;
    written: string[];
    /** Playlists removed from the player (not counting moves). */
    removed: string[];
    moved: { id: string; from: Volume; to: Volume }[];
    skipped: ApplyResult['skipped'];
    warnings: string[];
}

/**
 * Applies an AcrossPlan: the card's plan, then the internal one, each with applySync's guarantees
 * (every resume position stays on internal); then old copies of moved playlists are removed,
 * keeping their state. A move interrupted before that leaves the playlist on both volumes; the
 * player shows the internal copy and the next transfer removes the extra one.
 */
export async function applyAcross(fs: SyncFs, plan: AcrossPlan, options: ApplyOptions): Promise<AcrossResult> {
    const byVolume = rootsOf(plan.roots);
    const stateRoot = plan.roots.internal;
    const perVolume: Partial<Record<Volume, ApplyResult>> = {};
    const result: AcrossResult = { perVolume, written: [], removed: [], moved: [], skipped: [], warnings: [] };
    let offset = 0;
    for (const v of ['card', 'internal'] as const) {
        const p = plan.plans[v];
        if (!p) continue;
        const base = offset;
        const { onProgress } = options;
        const applied = await applySync(fs, p, {
            ...options, stateRoot,
            ...(onProgress ? { onProgress: (e: ProgressEvent) => onProgress({ ...e, bytesDone: base + e.bytesDone, bytesTotal: plan.bytesToCopy }) } : {}),
        });
        offset += p.bytesToCopy;
        perVolume[v] = applied;
        result.written.push(...applied.written);
        result.removed.push(...applied.removed);
        result.skipped.push(...applied.skipped);
        result.warnings.push(...applied.warnings);
    }
    const skipped = new Set(result.skipped.map((s) => s.id));
    for (const { volume, ids } of plan.cleanup) {
        // An old copy goes only once its replacement is complete; otherwise it is all the player has.
        const safe = ids.filter((id) => !skipped.has(id));
        if (!safe.length) continue;
        const removal = await planTransfer(fs, byVolume[volume]!, { send: [], remove: safe });
        const { onProgress: _quiet, ...rest } = options;
        const applied = await applySync(fs, removal, { ...rest, stateRoot, keepState: new Set(safe) });
        result.warnings.push(...applied.warnings);
        for (const id of applied.removed) {
            const move = plan.placement.moves.find((m) => m.id === id && m.from === volume);
            if (move) result.moved.push(move);
        }
    }
    return result;
}
