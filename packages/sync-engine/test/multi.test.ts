// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { checkLibrary } from '@studio-pocket/library-format/node';
import { NoRoomError } from '../src/placement.js';
import { applyAcross, checkPlayer, markAsCard, planAcross, type PlayerRoots } from '../src/multi.js';
import { ensureDeviceMarker, inspectDevice, nodeSyncFs } from '../src/node.js';
import { sourceFromFixtures } from './helpers.js';
import type { SourceLibrary } from '../src/source.js';

let internalVol: string;
let cardVol: string;
let roots: PlayerRoots;
const exists = (p: string) => stat(p).then(() => true, () => false);
const KB = 1024;

/** Free space per volume, as the test decides; `null` = unknown (room for anything). */
let free: { internal: number | null; card: number | null };
const freeBytes = async (dir: string) => (dir.startsWith(cardVol) ? free.card : free.internal);
const opts = () => ({ freeBytes, reserveBytes: 0 });

const send = async (source: SourceLibrary, remove: string[] = [], r: PlayerRoots = roots) => {
    const plan = await planAcross(nodeSyncFs, r, { send: source.playlists, remove }, opts());
    return { plan, result: await applyAcross(nodeSyncFs, plan, { generator: 'test', ...opts() }) };
};
const ids = async (root: string) => (await checkLibrary(root)).index?.playlists.map((p) => p.id) ?? [];
/** Everything a first send of the playlist copies (audio, icons, cover). */
const sizeOf = async (id: string) => (await planAcross(nodeSyncFs, { internal: roots.internal }, { send: (await sourceFromFixtures([id])).playlists, remove: [] })).bytesToCopy;

beforeEach(async () => {
    internalVol = await mkdtemp(path.join(os.tmpdir(), 'myo-int-'));
    cardVol = await mkdtemp(path.join(os.tmpdir(), 'myo-card-'));
    const marker = await ensureDeviceMarker(internalVol, 'test');
    await markAsCard(nodeSyncFs, cardVol, marker.deviceId, 'test');
    roots = { internal: path.join(internalVol, 'STUDIOPOCKET'), card: path.join(cardVol, 'STUDIOPOCKET') };
    free = { internal: null, card: null };
});
afterEach(async () => {
    await rm(internalVol, { recursive: true, force: true });
    await rm(cardVol, { recursive: true, force: true });
});

describe('pairing', () => {
    it('the card marker carries the internal deviceId and says it is the card', async () => {
        const internal = await inspectDevice(internalVol);
        const card = await inspectDevice(cardVol);
        expect(card.marker).toMatchObject({ deviceId: internal.marker!.deviceId, storage: 'card' });
        expect(internal.marker!.storage).toBeUndefined();
    });
});

describe('placement on the player', () => {
    it('with room on internal, everything goes there and the card is left alone', async () => {
        const { plan } = await send(await sourceFromFixtures(['bedtime', 'songs']));
        expect(Object.keys(plan.plans)).toEqual(['internal']);
        expect(await ids(roots.internal)).toEqual(['bedtime', 'songs']);
        expect(await exists(path.join(roots.card!, 'library.json'))).toBe(false);
    });

    it('spills onto the card when internal is full, and the merged view shows both', async () => {
        free = { internal: (await sizeOf('bedtime')) + KB, card: null };
        await send(await sourceFromFixtures(['bedtime', 'songs']));
        expect(await ids(roots.internal)).toEqual(['bedtime']);
        expect(await ids(roots.card!)).toEqual(['songs']);
        const check = await checkPlayer(nodeSyncFs, roots);
        expect(check.merged.playlists.map((p) => [p.id, p.ok])).toEqual([['bedtime', true], ['songs', true]]);
        expect(check.location.get('songs')).toBe('card');
        expect(check.merged.index?.playlists.map((p) => p.id)).toEqual(['bedtime', 'songs']);
        // No state/ on the card: resume positions live on internal.
        expect(await exists(path.join(roots.card!, 'state'))).toBe(false);
    });

    it('refuses with totals when nothing fits, before writing anything', async () => {
        free = { internal: KB, card: KB };
        await expect(planAcross(nodeSyncFs, roots, { send: (await sourceFromFixtures(['bedtime'])).playlists, remove: [] }, opts())).rejects.toBeInstanceOf(NoRoomError);
        expect(await exists(path.join(roots.internal, 'playlists/bedtime'))).toBe(false);
    });

    it('without a card it is the old single-volume sync', async () => {
        const { plan } = await send(await sourceFromFixtures(['bedtime']), [], { internal: roots.internal });
        expect(Object.keys(plan.plans)).toEqual(['internal']);
        expect(await ids(roots.internal)).toEqual(['bedtime']);
    });
});

describe('moves and removals', () => {
    it('moves a playlist that outgrew internal to the card and keeps its resume position', async () => {
        await send(await sourceFromFixtures(['bedtime']));
        await writeFile(path.join(roots.internal, 'state/bedtime.json'), '{"kept":true}');
        // The update needs a recopy; internal has no room for it any more.
        const source = await sourceFromFixtures(['bedtime']);
        const track = source.playlists[0]!.tracks[0]!;
        const bigger = path.join(internalVol, '..', `${path.basename(internalVol)}-ch1.mp3`);
        const bytes = Buffer.concat([await readFile(track.localPath), Buffer.alloc(2 * KB)]);
        await writeFile(bigger, bytes);
        track.localPath = bigger;
        track.entry.sizeBytes = bytes.length;
        track.entry.sha256 = (await import('node:crypto')).createHash('sha256').update(bytes).digest('hex');
        free = { internal: KB, card: null };
        const { plan, result } = await send(source);
        await rm(bigger, { force: true });
        expect(plan.placement.moves).toEqual([{ id: 'bedtime', from: 'internal', to: 'card' }]);
        expect(result.moved).toEqual([{ id: 'bedtime', from: 'internal', to: 'card' }]);
        expect(result.removed).toEqual([]);
        expect(await ids(roots.internal)).toEqual([]);
        expect(await ids(roots.card!)).toEqual(['bedtime']);
        expect(await exists(path.join(roots.internal, 'playlists/bedtime'))).toBe(false);
        expect(await readFile(path.join(roots.internal, 'state/bedtime.json'), 'utf8')).toBe('{"kept":true}');
    });

    it('removing a card playlist deletes its state on internal', async () => {
        free = { internal: (await sizeOf('bedtime')) + KB, card: null };
        await send(await sourceFromFixtures(['bedtime', 'songs']));
        await writeFile(path.join(roots.internal, 'state/songs.json'), '{}');
        const { result } = await send({ generator: 'test', playlists: [] }, ['songs']);
        expect(result.removed).toEqual(['songs']);
        expect(await ids(roots.card!)).toEqual([]);
        expect(await ids(roots.internal)).toEqual(['bedtime']);
        expect(await exists(path.join(roots.internal, 'state/songs.json'))).toBe(false);
    });

    it('a duplicate left by an interrupted move is resolved by the next transfer, internal copy kept', async () => {
        // Same playlist complete on both volumes, as if a move to internal stopped before the card copy went.
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.card! });
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.internal });
        expect((await checkPlayer(nodeSyncFs, roots)).location.get('songs')).toBe('internal');
        const { result } = await send(await sourceFromFixtures(['bedtime']));
        expect(result.removed).toEqual([]);
        expect(await ids(roots.card!)).toEqual([]);
        expect(await ids(roots.internal)).toEqual(['bedtime', 'songs']);
    });

    it('a duplicate whose copies differ is kept until the playlist is next sent', async () => {
        const changed = await sourceFromFixtures(['songs']);
        changed.playlists[0]!.manifest.title = 'Songs (newer)';
        await send(changed, [], { internal: roots.card! });
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.internal });
        await send(await sourceFromFixtures(['bedtime']));
        expect(await ids(roots.card!)).toEqual(['songs']);
        expect(await ids(roots.internal)).toEqual(['bedtime', 'songs']);
    });

    it('the merged view shows a complete copy over an incomplete one', async () => {
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.card! });
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.internal });
        const manifest = path.join(roots.internal, 'playlists/songs/manifest.json');
        const parsed = JSON.parse(await readFile(manifest, 'utf8')) as { tracks: { file: string }[] };
        await rm(path.join(roots.internal, 'playlists/songs', parsed.tracks[0]!.file));
        const check = await checkPlayer(nodeSyncFs, roots);
        expect(check.location.get('songs')).toBe('card');
        expect(check.merged.playlists.find((p) => p.id === 'songs')!.ok).toBe(true);
    });

    it('a removal and a move cleanup still run on a full volume', async () => {
        free = { internal: (await sizeOf('bedtime')) + KB, card: null };
        await send(await sourceFromFixtures(['bedtime', 'songs']));
        free = { internal: 0, card: 0 };
        // The engine's default 16 MB reserve applies here: a plan with nothing to copy must not need it.
        const plan = await planAcross(nodeSyncFs, roots, { send: [], remove: ['songs'] }, { freeBytes });
        const result = await applyAcross(nodeSyncFs, plan, { generator: 'test', freeBytes });
        expect(result.removed).toEqual(['songs']);
        expect(await ids(roots.card!)).toEqual([]);
    });

    it('a sent playlist on both volumes stays on internal and the card copy goes', async () => {
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.card! });
        await send(await sourceFromFixtures(['songs']), [], { internal: roots.internal });
        const { plan, result } = await send(await sourceFromFixtures(['songs']));
        expect(plan.placement.target.get('songs')).toBe('internal');
        expect(result.moved).toEqual([{ id: 'songs', from: 'card', to: 'internal' }]);
        expect(await exists(path.join(roots.card!, 'playlists/songs'))).toBe(false);
    });
});
