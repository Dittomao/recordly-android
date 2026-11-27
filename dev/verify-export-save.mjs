/** Verify Save to device and retry without re-rendering on the test emulator. */
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { appProcessId, forward, shell } from './adb.mjs';

assert.match(process.env.RECORDLY_DEVICE || 'emulator-5554', /^emulator-/, 'Use a test emulator');
const fixture = process.argv[2];
assert.ok(fixture, 'Usage: node dev/verify-export-save.mjs <test-fixture.mp4>');
const bytes = await readFile(fixture);
assert.ok(bytes.length > 8 && bytes.length <= 2 * 1024 * 1024, 'Use a small, non-sensitive MP4 fixture');
assert.equal(bytes.toString('ascii', 4, 8), 'ftyp', 'Expected an MP4 fixture');

const packageName = 'app.tapswoop.android.debug';
const fileName = `verify-export-${randomUUID()}.mp4`;
const exportsRoot = `/data/user/0/${packageName}/files/media/exports`;
const blocker = `${exportsRoot}/${fileName}.blocker`;
const galleryPath = `/sdcard/Movies/TapSwoop/${fileName}`;
const expectedHash = createHash('sha256').update(bytes).digest('hex');
const { chromium } = createRequire(import.meta.url)('../recordly-upstream/node_modules/playwright');

async function command(...args) {
    const reply = await shell(args);
    assert.equal(reply.code, 0, reply.stderr || reply.stdout);
    return reply.stdout;
}

function galleryRows() {
    return command('content', 'query', '--uri', 'content://media/external/video/media',
        '--projection', '_display_name:relative_path:is_pending:_size', '--where', `_display_name='${fileName}'`);
}

const pid = await appProcessId(packageName);
assert.match(pid, /^\d+$/, 'Start Recordly on the emulator first');
const port = 9223;
await forward(port, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { timeout: 20_000 });
let page;
let tempPath;
let blockerCreated = false;
try {
    page = browser.contexts().flatMap(context => context.pages())
        .find(candidate => candidate.url().startsWith('http://127.0.0.1:'));
    assert.ok(page, 'Recordly renderer not found');
    const expectedPath = `${exportsRoot}/${fileName}`;
    assert.match(await galleryRows(), /No result found/i, 'Fixture name already exists in MediaStore');
    // The destination is under the allowed export root, but its parent is
    // a file. This makes the private rename fail after gallery copy.
    await command('run-as', packageName, 'touch', blocker);
    blockerCreated = true;
    tempPath = await page.evaluate(async encoded => {
        const api = window.electronAPI;
        const data = Uint8Array.from(atob(encoded), char => char.charCodeAt(0));
        const opened = await api.openExportStream({ extension: 'mp4' });
        if (!opened.success) throw new Error(opened.error);
        try {
            const write = await api.writeExportStreamChunk(opened.streamId, 0, data);
            if (!write.success) throw new Error(write.error);
            const closed = await api.closeExportStream(opened.streamId);
            if (!closed.success || !closed.tempPath) throw new Error(closed.error);
            return closed.tempPath;
        } catch (error) {
            await api.closeExportStream(opened.streamId, { abort: true });
            throw error;
        }
    }, bytes.toString('base64'));

    const finalize = outputPath => page.evaluate(payload =>
        window.electronAPI.finalizeExportedVideo(payload), { tempPath, fileName, outputPath });
    const failure = await finalize(`${blocker}/${fileName}`);
    assert.equal(failure.success, false, 'Save unexpectedly succeeded with an unusable destination');
    const keptHash = (await command('run-as', packageName, 'sha256sum', tempPath)).split(/\s+/)[0];
    assert.equal(keptHash, expectedHash, 'A failed save changed or removed the rendered bytes');
    assert.match(await galleryRows(), /No result found/i, 'Failed save left a gallery entry');

    const saved = await finalize(null);
    assert.equal(saved.success, true, saved.message || saved.error);
    assert.equal(saved.path, expectedPath);
    const [privateHash, galleryHash] = await Promise.all([
        command('run-as', packageName, 'sha256sum', saved.path),
        command('sha256sum', galleryPath),
    ]);
    assert.equal(privateHash.split(/\s+/)[0], expectedHash);
    assert.equal(galleryHash.split(/\s+/)[0], expectedHash);
    const rows = await galleryRows();
    assert.equal(rows.match(/^Row:/gm)?.length, 1, 'Retry must publish exactly one gallery entry');
    assert.match(rows, /relative_path=Movies\/TapSwoop\//);
    assert.match(rows, /is_pending=0/);
    assert.equal((await shell(['run-as', packageName, 'test', '-e', tempPath])).code, 1, 'Temp still exists after saving');
    console.log(JSON.stringify({ retrySaved: true, galleryEntries: 1, bytesVerified: bytes.length }));
} finally {
    if (tempPath) {
        await page.evaluate(path => window.electronAPI.discardExportedTemp(path), tempPath).catch(() => {});
    }
    if (blockerCreated) {
        await shell(['run-as', packageName, 'rm', '-f', blocker, `${exportsRoot}/${fileName}`]);
        await shell(['rm', '-f', galleryPath]);
    }
    await browser.close();
}
