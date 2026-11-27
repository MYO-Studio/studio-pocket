// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Ashworth Digital Ltd
// This file is part of Studio Pocket. It is licensed under GPL-3.0-only, or under a
// commercial licence from Ashworth Digital Ltd (see COPYING.commercial).

import type { DownloadAgentPin } from './da.js';
import { identifyDeviceImage } from './device.js';
import { InstallerError, keepTail, lastErrorLine, relativeToDeviceDir, runPlan, sessionFiles, type DeviceReadCallbacks } from './installer.js';
import { measureLayout, windowForManifest, type DeviceLayout } from './layout.js';
import type { FirmwareManifest } from './manifest.js';
import { readWindowPlan } from './mtk.js';
import type { FirmwarePlatform } from './platform.js';

/**
 * A read-only capture of a player's system partition for adding support for a firmware this tool
 * does not know yet. It reads exactly the window read-device reads (the same mtkclient `ro` line,
 * the same addresses), so it touches nothing on the player, and it never refuses on identity:
 * where read-device stops at an unknown build and keeps nothing, a capture keeps the system image
 * and a report (layout, build, stock launcher) in a folder of its own, outside the units/ the
 * installer uses. Nothing is read outside that window: not NVRAM/PROTECT (the player's own
 * calibration and IDs) and not the user's data partition.
 */

export const CAPTURE_REPORT_FILE = 'capture.json';
/** The raw window, kept only when the system filesystem could not be cut out of it. */
export const CAPTURE_WINDOW_FILE = 'system-window.img';
export const CAPTURE_SYSTEM_FILE = 'system.img';

export interface CaptureInputs {
    /** Where the download agent is kept (tools/da/), as for read-device. */
    firmwareDir: string;
    /** An empty or missing folder that receives the capture. */
    outDir: string;
    /** Give the read window (they must agree) and say whether the build is a known one. */
    manifests: FirmwareManifest[];
    daPin?: DownloadAgentPin;
}

export interface CaptureFile {
    name: string;
    sizeBytes: number;
    sha256: string;
}

export interface CaptureReport {
    capturedAt: string;
    /** The read, in mtkclient user-area offsets, and the manifests that gave it. */
    window: { start: string; length: string; from: string[] };
    emmcCid: string | null;
    layout: DeviceLayout | null;
    identity: {
        build: string;
        buildIncremental: string;
        displayId: string;
        fingerprint: string;
        fmRadio: boolean;
        launcher: { path: string; sizeBytes: number; sha256: string } | null;
        /** The known firmware this build matched, or null for a new one. */
        knownAs: string | null;
        problems: string[];
    } | null;
    /** Why the layout or identity could not be worked out; the raw window is kept instead. */
    analysisError: string | null;
    files: CaptureFile[];
    mtkLog: string[];
}

const hex = (n: number) => `0x${n.toString(16)}`;
const message = (error: unknown) => (error instanceof Error ? error.message : String(error));

export async function captureDevice(p: FirmwarePlatform, inputs: CaptureInputs, cb: DeviceReadCallbacks): Promise<CaptureReport> {
    const { firmwareDir, outDir, manifests } = inputs;
    if (!manifests.length) throw new InstallerError('no firmware manifests to take the read window from');
    const windows = manifests.map(windowForManifest);
    const window = windows[0]!;
    if (windows.some((w) => w.start !== window.start || w.length !== window.length)) {
        throw new InstallerError('the firmware manifests disagree about the read window');
    }
    if ((await p.stat(outDir)).exists && (await p.readdir(outDir)).length) {
        throw new InstallerError(`${outDir} is not empty: give capture-device a new folder`);
    }
    await p.mkdirp(outDir);

    const mtkFiles = await sessionFiles(p, firmwareDir, cb.log, inputs.daPin);
    const windowFile = p.join(outDir, CAPTURE_WINDOW_FILE);
    cb.stage('read');
    cb.log(`reading ${hex(window.length)} bytes from ${hex(window.start)} (read-only; the same window read-device reads)`);
    const { result, lines, emmcCid } = await runPlan(p, { id: 'capture', dir: outDir, lines: readWindowPlan(window.start, window.length, relativeToDeviceDir(CAPTURE_WINDOW_FILE)), ...mtkFiles }, (progress, line) => {
        if (progress?.phase === 'read' && progress.percent !== null) cb.progress('Reading the player', progress.percent, 100);
        cb.mtk?.(progress, line);
    });
    const windowStat = await p.stat(windowFile);
    if (result.code !== 0 || !windowStat.exists || windowStat.size < window.length) {
        await p.remove(windowFile).catch(() => undefined);
        throw new InstallerError(`could not read the player (mtkclient exit ${result.code}): ${lastErrorLine(lines)}`);
    }

    let layout: DeviceLayout | null = null;
    let identity: CaptureReport['identity'] = null;
    let analysisError: string | null = null;
    const systemFile = p.join(outDir, CAPTURE_SYSTEM_FILE);
    try {
        cb.stage('measure');
        layout = await measureLayout(p, windowFile, window.start);
        cb.log(`system partition at ${layout.hex.androidStart} + ${layout.hex.androidSize}; end marker ${layout.endMarker ? 'present' : 'MISSING'}`);
        cb.stage('extract');
        await p.copyRange(windowFile, layout.android.start - window.start, layout.android.size, systemFile, (bytes, total) => cb.progress('Copying the system image out of the read', bytes, total));
        cb.stage('identify');
        const found = await identifyDeviceImage(p, systemFile, manifests);
        identity = {
            build: found.build, buildIncremental: found.buildIncremental, displayId: found.displayId, fingerprint: found.fingerprint,
            fmRadio: found.fmRadio, launcher: found.launcher, knownAs: found.manifest?.firmwareVersion ?? null, problems: found.problems,
        };
        cb.log(`build ${found.build || '?'} (${found.displayId || '?'}): ${identity.knownAs ? `known as ${identity.knownAs}` : 'a firmware this tool does not know yet'}`);
        await p.remove(windowFile);
    } catch (error) {
        analysisError = message(error);
        cb.log(`could not analyse the read (${analysisError}); the raw window is kept`);
        await p.remove(systemFile).catch(() => undefined);
    }

    const files: CaptureFile[] = [];
    for (const name of [CAPTURE_SYSTEM_FILE, CAPTURE_WINDOW_FILE]) {
        const file = p.join(outDir, name);
        const st = await p.stat(file);
        if (!st.exists) continue;
        files.push({ name, sizeBytes: st.size, sha256: await p.sha256File(file, (bytes) => cb.progress(`Fingerprinting ${name}`, bytes, st.size)) });
    }
    const report: CaptureReport = {
        capturedAt: new Date().toISOString(),
        window: { start: hex(window.start), length: hex(window.length), from: manifests.map((m) => m.firmwareVersion) },
        emmcCid, layout, identity, analysisError, files, mtkLog: keepTail(lines),
    };
    await p.writeText(p.join(outDir, CAPTURE_REPORT_FILE), JSON.stringify(report, null, 2) + '\n');
    cb.stage('done');
    return report;
}
