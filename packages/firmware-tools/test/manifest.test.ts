// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import { describe, expect, it } from 'vitest';
import { ManifestError, targetPartition, validateManifest } from '../src/manifest.js';
import { loadManifest, nodePlatform } from '../src/node.js';
import { prepareStock, StockError } from '../src/stock.js';

const manifestPath = new URL('../src/manifests/y2-stock-3.2.1.json', import.meta.url).pathname;
const deviceOnlyPath = new URL('../src/manifests/y2-stock-3.2.4.json', import.meta.url).pathname;

describe('firmware manifest', () => {
    it('loads the stock 3.2.1 manifest and only ANDROID is touched', async () => {
        const manifest = await loadManifest(manifestPath);
        expect(manifest.platform.soc).toBe('MT6582');
        expect(targetPartition(manifest)).toMatchObject({ name: 'ANDROID', index: 16, physicalStart: '0x5180000', size: '0x33400000' });
        expect(manifest.partitions.filter((p) => p.studioPocketTouches)).toHaveLength(1);
    });

    it('refuses a manifest that touches a protected partition', async () => {
        const manifest = await loadManifest(manifestPath);
        manifest.partitions.find((p) => p.name === 'PRELOADER')!.studioPocketTouches = true;
        expect(() => validateManifest(manifest)).toThrow(ManifestError);
    });

    it('refuses a manifest with no target', async () => {
        const manifest = await loadManifest(manifestPath);
        for (const p of manifest.partitions) p.studioPocketTouches = false;
        expect(() => validateManifest(manifest)).toThrow(/exactly one partition/);
    });

    it('accepts a manifest with no source package only when the build number and launcher hash identify it', async () => {
        const manifest = await loadManifest(deviceOnlyPath);
        expect(manifest.source).toBeUndefined();
        expect(() => validateManifest(manifest)).not.toThrow();
        const noHash = structuredClone(manifest);
        delete noHash.stockLauncher.sha256;
        expect(() => validateManifest(noHash)).toThrow(/stockLauncher\.sha256/);
        const noBuild = structuredClone(manifest);
        delete noBuild.platform.customBuildVersion;
        expect(() => validateManifest(noBuild)).toThrow(/customBuildVersion/);
    });

    it('still requires the package hashes when a source package is named', async () => {
        const manifest = await loadManifest(manifestPath);
        delete manifest.files['system.img'];
        expect(() => validateManifest(manifest)).toThrow(/files\.system\.img/);
    });

    it('refuses to check a firmware package against a manifest that has none', async () => {
        const manifest = await loadManifest(deviceOnlyPath);
        await expect(prepareStock(nodePlatform, '/nonexistent/rom_y2.zip', manifest, '/nonexistent/work')).rejects.toThrow(StockError);
        await expect(prepareStock(nodePlatform, '/nonexistent/rom_y2.zip', manifest, '/nonexistent/work')).rejects.toThrow(/read the player itself/);
    });
});
