/**
 * Proves that the loaned loopback token never persists in a project and that a
 * loaded project is re-issued the current token.
 *
 *   node dev/verify-token.mjs
 *
 * Requires the debug app to be running on the emulator. It saves a synthetic
 * project whose media URLs carry a deliberately stale token, reads the file
 * through `run-as`, loads it through the bridge, and restores the project that
 * was current before the test.
 */
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { forward, shell } from "./adb.mjs";

const packageName = "app.recordly.android.debug";
const STALE = "stale-token-value";
const require = createRequire(import.meta.url);
const { chromium } = require("../recordly-upstream/node_modules/playwright");

const pid = (await shell(["pidof", packageName])).stdout.trim().split(/\s+/)[0];
assert.ok(pid, "Start Recordly on the emulator first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222", { timeout: 10_000 });
try {
	const page = browser
		.contexts()
		.flatMap(context => context.pages())
		.find(candidate => candidate.url().startsWith("http://127.0.0.1:"));
	assert.ok(page, "Recordly renderer not found");

	const before = await page.evaluate(async () => {
		const list = await window.electronAPI.listProjectFiles();
		const entries = list.projects || list.entries || [];
		return { current: entries.find(entry => entry.isCurrent)?.path || null };
	});

	const saved = await page.evaluate(async stale => {
		const token = JSON.parse(window.__recordlyNative.getSync("getBridgeToken", "[]"));
		const result = await window.electronAPI.saveProjectFile(
			{
				version: 1,
				videoPath: `/media/recordings/token-test.mp4?token=${stale}&v=2`,
				editor: {
					background: { type: "image", src: `/media/wallpapers/token-test.jpg?token=${stale}` },
					note: "https://example.com/watch?token=external",
				},
			},
			"token-persistence-test",
		);
		return { token, result };
	}, STALE);

	assert.ok(saved.result?.success, `saveProjectFile failed: ${JSON.stringify(saved.result)}`);
	assert.ok(saved.token, "the host returned no bridge token");

	// The persisted document must be portable: no per-process token, other query
	// parameters intact, external URLs untouched. org.json escapes `/` as `\/`,
	// so normalize before matching.
	const persistedRaw = (await shell(["run-as", packageName, "cat", saved.result.path])).stdout;
	const persisted = persistedRaw.replace(/\\\//g, "/");
	assert.ok(!persisted.includes(STALE), "the stale token was persisted");
	assert.ok(!persisted.includes("recordings/token-test.mp4?token="), "a media URL kept its token");
	assert.ok(persisted.includes("recordings/token-test.mp4?v=2"), "an unrelated query parameter was dropped");
	assert.ok(persisted.includes("https://example.com/watch?token=external"), "an external URL was rewritten");

	// Loading must re-issue the current token, including for the stale-token URL.
	const loaded = await page.evaluate(path => window.electronAPI.loadProjectFile(path), saved.result.path);
	assert.ok(loaded?.success, `loadProjectFile failed: ${JSON.stringify(loaded)}`);
	const document = JSON.stringify(loaded.project);
	assert.ok(!document.includes(STALE), "the stale token survived the load");
	assert.ok(document.includes(`token=${saved.token}`), "the current token was not attached");
	assert.ok(document.includes("token-test.mp4?v=2&token="), "the media query was not preserved and authorized");
	assert.ok(document.includes("https://example.com/watch?token=external"), "an external URL was rewritten on load");

	await page.evaluate(
		async ({ path, previous }) => {
			await window.electronAPI.trashProjectFiles([path]);
			if (previous) await window.electronAPI.openProjectFileAtPath(previous);
		},
		{ path: saved.result.path, previous: before.current },
	);

	console.log(
		JSON.stringify(
			{
				ok: true,
				persistedPath: saved.result.path,
				tokenLength: saved.token.length,
				restoredProject: before.current,
			},
			null,
			2,
		),
	);
} finally {
	await browser.close();
}
