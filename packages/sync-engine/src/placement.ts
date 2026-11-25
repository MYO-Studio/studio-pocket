// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import type { DeviceStorage } from '@studio-pocket/library-format';
import { InsufficientStorageError } from './apply.js';

/**
 * Which of the player's volumes each sent playlist goes to (docs/architecture/library-format.md, "Memory card").
 * Pure: the caller measures, this decides.
 *   1. A playlist already on a volume stays there if its new bytes fit (internal first when on both).
 *   2. Otherwise it moves to the other volume if it fits there in full.
 *   3. New playlists, largest first: internal while they fit, then the card.
 * Removals are not counted as freed space (FAT frees lazily), as in applySync.
 */
export type Volume = DeviceStorage;
export const VOLUMES: readonly Volume[] = ['internal', 'card'];

export interface PlacementItem {
    id: string;
    /** Volumes the playlist's folder is on now (usually none or one; both after an interrupted move). */
    on: readonly Volume[];
    /** Bytes that would be copied if it were placed on each volume (only what is missing or changed there). */
    cost: Record<Volume, number>;
}

export interface PlacementInput {
    items: readonly PlacementItem[];
    /** Free bytes per volume; null = unknown (treated as room for anything); a volume that is absent is not listed. */
    free: Partial<Record<Volume, number | null>>;
    /** Kept free on every volume. */
    reserveBytes: number;
}

export interface Placement {
    /** Where each sent playlist ends up. */
    target: Map<string, Volume>;
    /** Sent playlists whose old copy must be removed from `from` once the new one is complete. */
    moves: { id: string; from: Volume; to: Volume }[];
    /** Bytes to copy per volume. */
    bytes: Record<Volume, number>;
}

/** Thrown when the playlists cannot all be placed; `need` and `free` are totals across the volumes. */
export class NoRoomError extends InsufficientStorageError {
    constructor(message: string, public readonly need: number, public readonly free: number, public readonly unplaced: string[]) {
        super(message);
    }
}

export function placePlaylists(input: PlacementInput): Placement {
    const present = VOLUMES.filter((v) => v in input.free);
    const room: Record<Volume, number> = { internal: -Infinity, card: -Infinity };
    for (const v of present) {
        const free = input.free[v];
        room[v] = free === null || free === undefined ? Infinity : free - input.reserveBytes;
    }
    const target = new Map<string, Volume>();
    const bytes: Record<Volume, number> = { internal: 0, card: 0 };
    const unplaced: PlacementItem[] = [];
    const put = (item: PlacementItem, v: Volume): boolean => {
        // A playlist with nothing to copy always fits where it already is.
        if (!present.includes(v) || (item.cost[v] > 0 && item.cost[v] > room[v])) return false;
        room[v] -= item.cost[v];
        bytes[v] += item.cost[v];
        target.set(item.id, v);
        return true;
    };

    const existing = input.items.filter((i) => i.on.some((v) => present.includes(v)));
    const fresh = input.items.filter((i) => !existing.includes(i)).sort((a, b) => b.cost.internal - a.cost.internal || a.id.localeCompare(b.id));
    for (const item of existing) {
        const here = VOLUMES.filter((v) => item.on.includes(v) && present.includes(v));
        const there = VOLUMES.filter((v) => !here.includes(v));
        if (![...here, ...there].some((v) => put(item, v))) unplaced.push(item);
    }
    for (const item of fresh) {
        if (!VOLUMES.some((v) => put(item, v))) unplaced.push(item);
    }

    if (unplaced.length) {
        const free = present.reduce((n, v) => n + Math.max(0, input.free[v] ?? 0), 0);
        const need = [...target.entries()].reduce((n, [id, v]) => n + input.items.find((i) => i.id === id)!.cost[v], 0)
            + unplaced.reduce((n, i) => n + Math.min(...present.map((v) => i.cost[v])), 0) + input.reserveBytes * present.length;
        throw new NoRoomError(`no room for ${unplaced.map((i) => i.id).join(', ')}: need about ${need} bytes, ${free} free across ${present.join(' and ')}`, need, free, unplaced.map((i) => i.id));
    }
    const moves: Placement['moves'] = [];
    for (const item of input.items) {
        const to = target.get(item.id)!;
        for (const from of item.on) if (from !== to && present.includes(from)) moves.push({ id: item.id, from, to });
    }
    return { target, moves, bytes };
}
