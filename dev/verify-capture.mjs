/** Run a microphone take on the isolated emulator, including notification pause/resume/stop. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { shell, execOut, forward } from './adb.mjs';

const packageName = 'app.recordly.android.debug';
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
    const node = nodes.find(node => node.text === text || node['content-desc'] === text);
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

const pid = (await shell(['pidof', packageName])).stdout.trim().split(/\s+/)[0];
assert.ok(pid, 'Start Recordly on the emulator first');
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP('http://127.0.0.1:9222', { timeout: 10_000 });
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
    if (!page.url().includes('windowType=hud-overlay')) {
        for (let attempt = 0; attempt < 3 && !page.url().includes('windowType=hud-overlay'); attempt++) {
            await page.evaluate(() => { setTimeout(() => window.electronAPI.showRecordingHud(), 100); });
            try {
                await page.waitForURL(/windowType=hud-overlay/, { timeout: 8_000 });
            } catch {
                // A stale native surface flag can make the first request a no-op.
            }
            page = browser.contexts().flatMap(context => context.pages())
                .find(candidate => candidate.url().startsWith('http://127.0.0.1:'));
        }
        assert.ok(page.url().includes('windowType=hud-overlay'), 'Could not open the recording HUD');
    }
    const preferences = await page.evaluate(() => window.electronAPI.getRecordingPreferences());
    assert.equal(preferences.microphoneEnabled, true, 'Select Default microphone before running this test');
    const closeToast = page.getByRole('button', { name: 'Close', exact: true });
    if (await closeToast.count()) await closeToast.click({ timeout: 10_000 });
    await shell(['logcat', '-c']);
    await page.getByRole('button', { name: 'Record', exact: true }).click({ timeout: 10_000 });

    const consentDeadline = Date.now() + 30_000;
    while (Date.now() < consentDeadline) {
        const nodes = await androidNodes();
        const find = text => nodes.find(node => node.text === text);
        const next = find('While using the app') || find('Allow') || find('Share screen') ||
            find('Share entire screen') || find('Share one app');
        if (next) await tapNode(next);
        if (/capture started/.test(await captureLog())) break;
        await delay(300);
    }
    await waitForLog(/capture started .*microphone=true/);
    console.log('Native microphone and screen capture started');
    // The upstream recorder warms the encoder, pauses for the countdown, then resumes.
    await waitForLog(/capture resumed/);
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
    completed = true;
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
