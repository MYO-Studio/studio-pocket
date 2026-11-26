// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import { describe, expect, it } from 'vitest';
import { bundledManifest, bundledManifests } from '../src/index.js';

describe('bundled firmware manifests', () => {
    it('ships the four known stock builds, parsed and validated', () => {
        expect(bundledManifests().map((m) => m.firmwareVersion).sort()).toEqual(['3.2.0-fm', '3.2.1', '3.2.4', '3.2.8']);
    });

    it('identifies 3.2.4 by its build number and launcher hash only: it has no published package', () => {
        const m = bundledManifest('3.2.4');
        expect(m.source).toBeUndefined();
        expect(m.platform.customBuildVersion).toBe('1787108138');
        expect(m.stockLauncher).toMatchObject({ path: '/system/priv-app/MyLauncher.apk', package: 'com.innioasis.y2', sizeBytes: 61822056, sha256: 'ff19eaa288d3a13a5a630bb813d8f4f881acbaef9e43a709f4c4ca194e27e7ba' });
    });

    it('identifies 3.2.8 by its build number and launcher hash only, with the 3.2.4 layout', () => {
        const m = bundledManifest('3.2.8');
        expect(m.source).toBeUndefined();
        expect(m.platform.customBuildVersion).toBe('1792752654');
        expect(m.platform.buildFingerprint).toBe('Y2/Y2/Y2:4.4.2/KOT49H/1792752654:user/test-keys');
        expect(m.stockLauncher).toMatchObject({ path: '/system/priv-app/MyLauncher.apk', package: 'com.innioasis.y2', versionName: '3.2.9', sizeBytes: 61823158, sha256: '9aae687ff2d71913dd1bb649f644a47365f7fb45fa63452b1db922a60b0dca49' });
        expect(m.partitions).toEqual(bundledManifest('3.2.4').partitions);
    });

    it('gives every bundled build a distinct build number', () => {
        const builds = bundledManifests().map((m) => m.platform.customBuildVersion);
        expect(new Set(builds).size).toBe(builds.length);
    });

    it('looks a build up by version and refuses an unknown one', () => {
        expect(bundledManifest('3.2.1').firmwareVersion).toBe('3.2.1');
        expect(() => bundledManifest('9.9.9')).toThrow(/no bundled manifest for firmware 9\.9\.9/);
    });
});
