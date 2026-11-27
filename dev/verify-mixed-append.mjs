/** Validate mixed resolution, codec settings, audio, and touch coordinates on the test emulator. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { basename, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { appProcessId, execOut, forward, push, shell } from './adb.mjs';

assert.match(process.env.RECORDLY_DEVICE || 'emulator-5554', /^emulator-/, 'Use a test emulator');
const pkg = 'app.tapswoop.android.debug';
const verification = fileURLToPath(new URL('./verification/', import.meta.url));
await mkdir(verification, { recursive: true });
const work = await mkdtemp(join(verification, 'mixed-append-'));
const tag = basename(work);
const remoteDir = `/data/user/0/${pkg}/files/media/recordings`;
const remoteFiles = [];
const localFiles = {};
const sourceHashes = new Map();
const { chromium } = createRequire(import.meta.url)('../recordly-upstream/node_modules/playwright');

function ffmpeg(...args) {
    return execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', ...args]);
}

function fixture(name, color, size, rate, profile, audio) {
    const path = join(work, `${name}.mp4`);
    ffmpeg('-f', 'lavfi', '-i', `color=${color}:size=${size}:rate=${rate}:duration=2`,
        ...(audio ? ['-f', 'lavfi', '-i', `sine=frequency=${audio.frequency}:sample_rate=${audio.rate}:duration=2`] : []),
        '-c:v', 'libx264', '-threads', '2', '-pix_fmt', 'yuv420p', '-profile:v', profile,
        '-bf', profile === 'baseline' ? '0' : '2',
        ...(audio ? ['-c:a', 'aac', '-ac', String(audio.channels)] : ['-an']),
        '-movflags', '+faststart', path);
    localFiles[name] = path;
}

async function command(...args) {
    const result = await shell(args);
    assert.equal(result.code, 0, result.stderr || result.stdout);
    return result.stdout;
}

async function installFixture(name, point) {
    const staged = `/data/local/tmp/${tag}-${name}.mp4`;
    const path = `${remoteDir}/${tag}-${name}.mp4`;
    remoteFiles.push(path, `${path}.cursor.json`);
    await push(localFiles[name], staged);
    try {
        await command('run-as', pkg, 'cp', staged, path);
    } finally {
        await shell(['rm', '-f', staged]);
    }
    if (point) {
        const json = JSON.stringify({ version: 1, samples: [point] });
        await command('run-as', pkg, 'sh', '-c', `printf '%s' '${json}' > '${path}.cursor.json'`);
    }
    sourceHashes.set(path, (await command('run-as', pkg, 'sha256sum', path)).trim());
    return path;
}

function pixel(file, time, x, y) {
    const rgb = ffmpeg('-ss', String(time), '-i', file, '-frames:v', '1',
        '-vf', `scale=360:640,crop=2:2:${x}:${y},format=rgb24`, '-f', 'rawvideo', '-');
    assert.equal(rgb.length, 12, 'Could not sample a decoded frame');
    return [...rgb.subarray(0, 3)];
}

function tone(file, time) {
    const pcm = ffmpeg('-ss', String(time), '-i', file, '-t', '0.2', '-vn', '-ac', '1', '-ar', '8000', '-f', 's16le', '-');
    assert.equal(pcm.length, 3200, 'Could not sample a decoded audio track');
    let crossings = 0;
    let energy = 0;
    for (let i = 0; i < pcm.length; i += 2) {
        const sample = pcm.readInt16LE(i);
        energy += (sample / 32768) ** 2;
        if (i > 0 && sample >= 0 && pcm.readInt16LE(i - 2) < 0) crossings++;
    }
    return { rms: Math.sqrt(energy / (pcm.length / 2)), frequency: crossings / 0.2 };
}

const reading = bytes => JSON.parse(bytes.toString());
const red = (rgb) => rgb[0] > 180 && rgb[1] < 50 && rgb[2] < 50;
const blue = (rgb) => rgb[2] > 180 && rgb[0] < 50 && rgb[1] < 50;
const black = (rgb) => rgb.every(value => value < 30);
const toneNear = (sample, hz) => sample.rms > 0.04 && Math.abs(sample.frequency - hz) < 30;
const closeTo = (actual, expected, tolerance = 0.003) => Math.abs(actual - expected) <= tolerance;

fixture('portrait', 'red', '360x640', 30, 'baseline', { frequency: 440, rate: 44100, channels: 1 });
fixture('landscape', 'blue', '640x360', 24, 'high', { frequency: 880, rate: 48000, channels: 2 });
fixture('same-size-high', 'blue', '360x640', 30, 'high', { frequency: 880, rate: 44100, channels: 1 });
fixture('silent', 'red', '360x640', 30, 'baseline');

const pid = await appProcessId(pkg);
assert.match(pid, /^\d+$/, 'Start the Recordly debug app on the emulator');
await forward(9223, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9223', { timeout: 30_000 });
const page = browser.contexts().flatMap(context => context.pages())
    .find(candidate => candidate.url().startsWith('http://127.0.0.1:'));
let previous;
const results = [];
try {
    assert.ok(page, 'Recordly renderer not found');
    await page.waitForFunction(() => typeof window.electronAPI?.importRecording === 'function',
        undefined, { timeout: 30_000 });
    previous = await page.evaluate(() => window.electronAPI.getCurrentVideoPath());
    const portrait = await installFixture('portrait', { timeMs: 250, cx: 0.25, cy: 0.25, type: 'click' });
    const landscape = await installFixture('landscape', { timeMs: 500, cx: 0.25, cy: 0.25, type: 'click' });
    const sameSize = await installFixture('same-size-high');
    const silent = await installFixture('silent');

    async function append(name, first, second) {
        const result = await page.evaluate(([a, b]) => window.electronAPI.importRecording(a, b), [first, second]);
        assert.equal(result.success, true, result.error);
        const media = result.value;
        assert.match(media.path, /\/recordings\/\.recordly-media\/import-[a-z0-9-]+\.mp4$/i);
        remoteFiles.push(media.path, `${media.path}.cursor.json`);
        const accepted = await page.evaluate(path => window.electronAPI.finishRecordingImport(path, true), media.path);
        assert.equal(accepted.success, true, accepted.error);
        const output = join(work, `${name}-joined.mp4`);
        await writeFile(output, await execOut(['run-as', pkg, 'cat', media.path]));
        const sidecar = reading(await execOut(['run-as', pkg, 'cat', `${media.path}.cursor.json`]));
        const seam = media.sourceStartMs / 1000;
        assert.ok(closeTo(seam, 2, 0.06) && closeTo(media.totalDurationMs, 4000, 150), JSON.stringify(media));
        assert.equal(media.sourceStartMs + media.durationMs, media.totalDurationMs);
        const probe = await page.evaluate(path => window.electronAPI.probeNativeVideoMetadata(path), media.path);
        assert.equal(probe.success, true, probe.error);
        const { width, height, duration } = probe.metadata;
        assert.ok(width === 360 && height === 640, JSON.stringify(probe.metadata));
        assert.ok(closeTo(duration * 1000, media.totalDurationMs, 150));
        results.push({ name, joinMs: media.sourceStartMs, durationMs: media.totalDurationMs, output });
        return { output, seam, sidecar };
    }

    const mixed = await append('mixed-resolution', portrait, landscape);
    assert.ok(red(pixel(mixed.output, 0.7, 180, 48)), 'Base clip did not fill its original portrait canvas');
    assert.ok(blue(pixel(mixed.output, mixed.seam + 0.7, 180, 320)), 'Appended clip did not decode');
    assert.ok(black(pixel(mixed.output, mixed.seam + 0.7, 180, 48)), 'Landscape clip was not letterboxed');
    assert.ok(toneNear(tone(mixed.output, 0.6), 440), 'Base audio is missing or distorted');
    assert.ok(toneNear(tone(mixed.output, mixed.seam + 0.6), 880), 'Appended audio is missing or distorted');
    const [originalTap, appendedTap] = mixed.sidecar.samples;
    assert.equal(originalTap.timeMs, 250);
    assert.equal(appendedTap.timeMs, Math.round(mixed.seam * 1000) + 500);
    assert.ok(closeTo(originalTap.cy, 0.25) && closeTo(appendedTap.cx, 0.25));
    assert.ok(closeTo(appendedTap.cy, 0.5 - 0.25 * (360 / 640) ** 2), JSON.stringify(appendedTap));

    const same = await append('same-size-settings', portrait, sameSize);
    assert.ok(red(pixel(same.output, 0.7, 180, 48)) && blue(pixel(same.output, same.seam + 0.7, 180, 48)));

    const silence = await append('silent-first', silent, sameSize);
    assert.ok(tone(silence.output, 0.6).rms < 0.003, 'A silent clip gained non-silent audio');
    assert.ok(toneNear(tone(silence.output, silence.seam + 0.6), 880), 'Audio after a silent clip did not survive');

    const lossless = await append('compatible', portrait, portrait);
    function frameHashes(file) {
        return ffmpeg('-i', file, '-map', '0:v:0', '-f', 'framemd5', '-').toString()
            .split('\n').filter(line => line.trim() && !line.startsWith('#')).map(line => line.split(',').at(-1).trim());
    }
    const originalFrames = frameHashes(localFiles.portrait);
    assert.deepEqual(frameHashes(lossless.output), [...originalFrames, ...originalFrames]);
    for (const [path, hash] of sourceHashes) {
        assert.equal((await command('run-as', pkg, 'sha256sum', path)).trim(), hash, 'An input was modified');
    }
    console.log(JSON.stringify(results, null, 2));
} finally {
    if (page && previous) {
        await page.evaluate(path =>
            window.electronAPI.setCurrentVideoPath(path, { preserveProjectPath: true }), previous.path ?? ''
        ).catch(() => {});
    }
    if (remoteFiles.length) await shell(['run-as', pkg, 'rm', '-f', ...remoteFiles]);
    await browser.close();
}
