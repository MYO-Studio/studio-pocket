// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import { describe, expect, it } from 'vitest';
import { InsufficientStorageError } from '../src/apply.js';
import { NoRoomError, placePlaylists, type PlacementItem } from '../src/placement.js';

const item = (id: string, size: number, on: PlacementItem['on'] = [], cost?: Partial<PlacementItem['cost']>): PlacementItem => ({ id, on, cost: { internal: size, card: size, ...cost } });
const where = (p: ReturnType<typeof placePlaylists>) => Object.fromEntries([...p.target.entries()].sort());

describe('placement', () => {
    it('fills internal first, largest first, then the card', () => {
        const p = placePlaylists({ items: [item('a', 30), item('b', 60), item('c', 30)], free: { internal: 100, card: 1000 }, reserveBytes: 10 });
        // 90 room on internal: b (60) then a (30) fit; c goes to the card.
        expect(where(p)).toEqual({ a: 'internal', b: 'internal', c: 'card' });
        expect(p.bytes).toEqual({ internal: 90, card: 30 });
        expect(p.moves).toEqual([]);
    });

    it('keeps a playlist where it is when its update fits there', () => {
        const p = placePlaylists({ items: [item('a', 50, ['card'], { card: 5 })], free: { internal: 1000, card: 20 }, reserveBytes: 10 });
        expect(where(p)).toEqual({ a: 'card' });
        expect(p.moves).toEqual([]);
    });

    it('an unchanged playlist stays put even on a full volume', () => {
        const p = placePlaylists({ items: [item('a', 50, ['internal'], { internal: 0 })], free: { internal: 0, card: 0 }, reserveBytes: 16 });
        expect(where(p)).toEqual({ a: 'internal' });
    });

    it('moves a playlist whose update no longer fits where it is', () => {
        const p = placePlaylists({ items: [item('a', 500, ['internal'], { internal: 200 })], free: { internal: 100, card: 1000 }, reserveBytes: 0 });
        expect(where(p)).toEqual({ a: 'card' });
        expect(p.moves).toEqual([{ id: 'a', from: 'internal', to: 'card' }]);
    });

    it('on both volumes (an interrupted move), the internal copy wins and the other is a move', () => {
        const p = placePlaylists({ items: [item('a', 50, ['internal', 'card'], { internal: 0, card: 0 })], free: { internal: 100, card: 100 }, reserveBytes: 0 });
        expect(where(p)).toEqual({ a: 'internal' });
        expect(p.moves).toEqual([{ id: 'a', from: 'card', to: 'internal' }]);
    });

    it('without a card everything goes to internal', () => {
        const p = placePlaylists({ items: [item('a', 50), item('b', 50)], free: { internal: null }, reserveBytes: 0 });
        expect(where(p)).toEqual({ a: 'internal', b: 'internal' });
    });

    it('reports what does not fit with totals across both volumes', () => {
        let error: unknown;
        try {
            placePlaylists({ items: [item('a', 80), item('b', 80)], free: { internal: 100, card: 50 }, reserveBytes: 10 });
        } catch (e) {
            error = e;
        }
        expect(error).toBeInstanceOf(NoRoomError);
        expect(error).toBeInstanceOf(InsufficientStorageError);
        expect(error).toMatchObject({ unplaced: ['b'], free: 150, need: 180 });
    });
});
