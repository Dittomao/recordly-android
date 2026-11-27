/** Verify microphone pause/resume or system interruption on an isolated emulator. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { appProcessId, shell, execOut, forward } from './adb.mjs';

const packageName = 'app.tapswoop.android.debug';
const probePackage = 'app.recordly.systemuirepro';
const interrupt = process.argv.includes('--interrupt');
assert.match(process.env.RECORDLY_DEVICE || 'emulator-5554', /^emulator-/, 'Use a test emulator');
const { chromium } = createRequire(import.meta.url)('../recordly-upstream/node_modules/playwright');
const outputDir = new URL('./verification/', import.meta.url);
await mkdir(outputDir, { recursive: true });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const decode = text => text.replaceAll('&quot;', '"').replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&apos;', "'");

async function androidNodes() {
    await shell(['uiautomator', 'dump', '/sdcard/recordly-window.xml']);
    const xml = (await execOut(['cat', '/sdcard/recordly-window.xml'])).toString();
    return [...xml.matchAll(/<node\b([^>]*)>/g)].map(match =>
        Object.fromEntries([...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)]
            .map(attribute => [attribute[1], decode(attribute[2])])));
}

async function tapNode(node) {
    const bounds = node.bounds.match(/\d+/g).map(Number);
    const result = await shell(['input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)),
        String(Math.round((bounds[1] + bounds[3]) / 2))]);
    assert.equal(result.code, 0, result.stderr);
}

async function tapText(text) {
    const nodes = await androidNodes();
    const node = nodes.find(node => [node.text, node['content-desc']]
        .some(label => label?.toLowerCase() === text.toLowerCase()));
    assert.ok(node, `Android control not found: ${text}; visible: ${nodes.map(node => node.text).filter(Boolean)}`);
    await tapNode(node);
}

async function captureLog() {
    return (await shell(['logcat', '-d', '-v', 'time', '-s', 'CaptureSession', 'ScreenCapture', 'MicrophoneCapture'])).stdout;
}

async function waitForLog(pattern, timeout = 20_000, minimumCount = 1) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
        const log = await captureLog();
        if (log.split('\n').filter(line => pattern.test(line)).length >= minimumCount) return log;
        await delay(500);
    }
    throw new Error(`Capture did not reach ${pattern}\n${await captureLog()}`);
}

async function notificationAction(page, label, method) {
    try {
        await tapText(label);
        return true;
    } catch {
        // The shade may present the Recordly card collapsed, hiding its action
        // buttons. The bridge is the same control the renderer uses.
        await shell(['cmd', 'statusbar', 'collapse']);
        await page.evaluate(name => window.electronAPI[name](), method);
        return false;
    }
}

async function openHud(page) {
    if (!page.url().includes('windowType=hud-overlay')) {
        for (let attempt = 0; attempt < 3 && !page.url().includes('windowType=hud-overlay'); attempt++) {
            await page.evaluate(() => { setTimeout(() => window.electronAPI.showRecordingHud(), 100); });
            try {
                await page.waitForURL(/windowType=hud-overlay/, { timeout: 8_000 });
            } catch {
                // A stale native surface flag can make the first request a no-op.
            }
        }
    }
    assert.ok(page.url().includes('windowType=hud-overlay'), 'Could not open the recording HUD');
    const closeToast = page.getByRole('button', { name: 'Close', exact: true });
    if (await closeToast.count()) await closeToast.click({ timeout: 10_000 });
}

async function confirmProjection(hasStarted) {
    const deadline = Date.now() + 30_000;
    let confirmedProjection = false;
    let sourceMenuOpened = false;
    while (Date.now() < deadline && !await hasStarted()) {
        const nodes = await androidNodes();
        const find = text => nodes.find(node => node.text === text);
        const permission = find('While using the app') || find('Allow');
        const entire = find('Share entire screen');
        const oneApp = find('Share one app');
        const confirm = find('Next') || find('Share screen') || find('Start now') || find('Share');
        if (permission) await tapNode(permission);
        else if (oneApp && entire) {
            await tapNode(entire);
            sourceMenuOpened = false;
        } else if (oneApp && !sourceMenuOpened) {
            await tapNode(oneApp);
            sourceMenuOpened = true;
        } else if (confirm && !oneApp) {
            await tapNode(confirm);
            confirmedProjection = true;
        } else if (entire) {
            await tapNode(entire);
            sourceMenuOpened = false;
        }
        await delay(300);
    }
    assert.ok(confirmedProjection, 'No fresh MediaProjection consent was observed');
    assert.ok(await hasStarted(), 'The capture did not start after consent');
}

async function beginTake(page, requireMicrophone) {
    await shell(['logcat', '-c']);
    await page.getByRole('button', { name: 'Record', exact: true }).click({ timeout: 10_000 });
    const started = requireMicrophone ? /capture started .*microphone=true/ : /capture started/;
    await confirmProjection(async () => started.test(await captureLog()));
    // The upstream recorder warms the encoder, pauses for the countdown, then resumes.
    await waitForLog(/capture resumed/);
}

async function inspectFinishedTake(page, filename) {
    const log = await waitForLog(/recording written: (.+\.mp4) \(/, 30_000);
    const path = log.match(/recording written: (.+\.mp4) \(/)[1];
    assert.equal((log.match(/recording written:/g) || []).length, 1, 'More than one output was written');
    await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
    await page.waitForFunction(async expected => {
        const current = await window.electronAPI.getCurrentVideoPath();
        return current.path === expected && [...document.querySelectorAll('video')]
            .some(video => !video.error && video.readyState >= 2 && video.duration > 0 && video.videoWidth > 0);
    }, path, { timeout: 20_000 });
    const projection = await shell(['dumpsys', 'media_projection']);
    assert.equal(projection.code, 0, projection.stderr);
    assert.match(projection.stdout, /Media Projection:\s*null/, 'Projection was not released');
    await writeFile(new URL(filename, outputDir), log);
    return path;
}

async function verifyInterruption(page) {
    const probeApk = await shell(['pm', 'path', probePackage]);
    assert.equal(probeApk.code, 0, 'Install the standalone System UI Capture Repro APK on the emulator first');
    await beginTake(page, false);
    await delay(4_000);
    // Only one projection can own the display. A separately consented test
    // session makes Android (not Recordly) revoke the first session.
    const probe = await shell(['am', 'start', '-W', '-n', `${probePackage}/.ProbeActivity`]);
    assert.match(probe.stdout, /Status: ok/, probe.stderr || probe.stdout);
    try {
        await tapText('Start 18-second capture');
        await confirmProjection(async () => (await shell(['dumpsys', 'media_projection'])).stdout.includes(probePackage));
        await waitForLog(/projection revoked by the system/);
        await waitForLog(/recording written:/, 30_000);
    } finally {
        await shell(['am', 'force-stop', probePackage]);
    }
    await shell(['am', 'start', '-W', '-n', `${packageName}/app.recordly.android.MainActivity`]);
    const recoveredPath = await inspectFinishedTake(page, 'capture-interrupted.log');
    assert.doesNotMatch(await captureLog(), /startForeground failed/,
        'Finalizing the interrupted take tried to start a foreground capture again');
    await openHud(page);
    const record = page.getByRole('button', { name: 'Record', exact: true });
    await record.waitFor({ state: 'visible' });
    const button = await record.evaluate(element => {
        const { left, right } = element.getBoundingClientRect();
        return { left, right, viewport: innerWidth };
    });
    assert.ok(button.left >= 0 && button.right <= button.viewport,
        `Record button is clipped after interruption: ${JSON.stringify(button)}`);
    await writeFile(new URL('capture-retry-hud-after.png', outputDir), await execOut(['screencap', '-p']));
    await beginTake(page, false);
    await delay(3_000);
    await shell(['am', 'start', '-W', '-n', `${packageName}/app.recordly.android.MainActivity`]);
    await page.getByRole('button', { name: 'Stop', exact: true }).click({ timeout: 15_000 });
    const retryPath = await inspectFinishedTake(page, 'capture-interrupted-retry.log');
    assert.notEqual(retryPath, recoveredPath, 'Retry reopened the interrupted take');
    console.log(JSON.stringify({ interrupted: recoveredPath, retry: retryPath, freshConsent: true }, null, 2));
}

async function verifyMicrophone(page, dialogs) {
    const preferences = await page.evaluate(() => window.electronAPI.getRecordingPreferences());
    assert.equal(preferences.microphoneEnabled, true, 'Select Default microphone before running this test');
    await beginTake(page, true);
    console.log('Native microphone and screen capture started');
    await delay(4_000);
    const pauseCount = (await captureLog()).split('\n').filter(line => /capture paused/.test(line)).length;
    const resumeCount = (await captureLog()).split('\n').filter(line => /capture resumed/.test(line)).length;
    await shell(['cmd', 'statusbar', 'expand-notifications']);
    await notificationAction(page, 'Pause', 'pauseNativeScreenRecording');
    await waitForLog(/capture paused/, 20_000, pauseCount + 1);
    const pausedLog = await captureLog();
    assert.ok(/microphone segment finished: [1-9]\d* samples/.test(pausedLog), 'No encoded microphone samples');
    await delay(1_000);
    await notificationAction(page, 'Resume', 'resumeNativeScreenRecording');
    await waitForLog(/capture resumed/, 20_000, resumeCount + 1);
    await shell(['cmd', 'statusbar', 'collapse']);
    await delay(4_000);
    await shell(['cmd', 'statusbar', 'expand-notifications']);
    const stoppedFromNotification = await notificationAction(page, 'Stop', 'stopNativeScreenRecording');
    const log = await waitForLog(/recording written: (.+\.mp4) \(/, 30_000);
    const sourcePath = log.match(/recording written: (.+\.mp4) \(/)[1];
    assert.ok(!/microphone segment failed|could not start the microphone/.test(log), log);
    await writeFile(new URL('recording-with-microphone.mp4', outputDir),
        await execOut(['run-as', packageName, 'cat', sourcePath]));
    await writeFile(new URL('recording-with-microphone.cursor.json', outputDir),
        await execOut(['run-as', packageName, 'cat', `${sourcePath}.cursor.json`]));
    await writeFile(new URL('capture.log', outputDir), log);
    // Core reliability: the final video sample must not stretch the container
    // past the audio, and microphone capture must stay at the higher bitrate.
    const localVideo = fileURLToPath(new URL('recording-with-microphone.mp4', outputDir));
    const probe = JSON.parse(execFileSync('ffprobe', [
        '-v', 'error', '-show_streams', '-of', 'json', localVideo,
    ], { encoding: 'utf8' }));
    const videoStream = probe.streams.find(stream => stream.codec_type === 'video');
    const audioStream = probe.streams.find(stream => stream.codec_type === 'audio');
    assert.ok(videoStream && audioStream, 'recording is missing a video or audio stream');
    const avSync = {
        videoDuration: Number(videoStream.duration),
        audioDuration: Number(audioStream.duration),
        deltaMs: Math.round((Number(videoStream.duration) - Number(audioStream.duration)) * 1000),
        audioBitrate: Number(audioStream.bit_rate),
    };
    assert.ok(Math.abs(avSync.deltaMs) <= 250,
        `A/V durations diverged by ${avSync.deltaMs} ms: ${JSON.stringify(avSync)}`);
    assert.ok(avSync.audioBitrate >= 115_000,
        `microphone bitrate is only ${avSync.audioBitrate} bps`);
    await shell(['cmd', 'statusbar', 'collapse']);
    if (!stoppedFromNotification) {
        await page.evaluate(() => window.electronAPI.switchToEditor());
    }
    await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
    const result = await page.evaluate(async () => ({
        url: location.href,
        currentVideo: await window.electronAPI.getCurrentVideoPath(),
        text: document.body.innerText.slice(0, 3000),
        videos: [...document.querySelectorAll('video')].map(video => ({
            src: video.currentSrc, width: video.videoWidth, height: video.videoHeight,
            duration: video.duration, readyState: video.readyState, error: video.error?.message,
        })),
    }));
    result.sourcePath = sourcePath;
    result.dialogs = dialogs;
    result.avSync = avSync;
    assert.equal(dialogs.length, 0, `Unexpected dialogs: ${dialogs}`);
    await writeFile(new URL('capture-result.json', outputDir), JSON.stringify(result, null, 2));
    await writeFile(new URL('editor-after-capture.png', outputDir), await execOut(['screencap', '-p']));
    console.log(JSON.stringify(result, null, 2));
    console.log(log.split('\n').slice(-30).join('\n'));
}

const pid = await appProcessId(packageName);
assert.ok(pid, 'Start Recordly on the emulator first');
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222', { timeout: 30_000 });
const dialogs = [];
let completed = false;
let page = null;
try {
    // A previous take can leave the task behind Settings or the launcher; a
    // backgrounded WebView cannot receive the Record click.
    await shell(['am', 'start', '-n', `${packageName}/app.recordly.android.MainActivity`]);
    await delay(1_000);
    await shell(['cmd', 'statusbar', 'collapse']);
    await delay(500);
    page = browser.contexts().flatMap(context => context.pages())
        .find(page => page.url().startsWith('http://127.0.0.1:'));
    assert.ok(page, 'Recordly renderer not found');
    page.on('dialog', async dialog => { dialogs.push(dialog.message()); await dialog.accept(); });
    // A previous failed verification can leave a take running; the HUD then
    // shows Stop/Discard instead of Record. Stop it before opening the HUD.
    await page.evaluate(() => window.electronAPI.stopNativeScreenRecording().catch(() => {})).catch(() => {});
    await delay(1_500);
    page = browser.contexts().flatMap(context => context.pages())
        .find(candidate => candidate.url().startsWith('http://127.0.0.1:'));
    await openHud(page);
    if (interrupt) await verifyInterruption(page);
    else await verifyMicrophone(page, dialogs);
    completed = true;
} finally {
    if (!completed && /capture started/.test(await captureLog())) {
        // A failed verification must not leave the emulator recording indefinitely.
        await page?.evaluate(() => window.electronAPI.stopNativeScreenRecording()).catch(() => {});
        await delay(2_000);
        if (/capture started/.test(await captureLog())) {
            await shell(['am', 'startservice', '-n', `${packageName}/app.recordly.android.ScreenCaptureService`,
                '-a', 'app.recordly.android.action.STOP_CAPTURE']);
        }
    }
    await browser.close();
}
