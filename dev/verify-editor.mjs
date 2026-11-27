/**
 * Regression checks for the editor-side bridge flows against the running
 * debug app on the test emulator.
 *
 *   node dev/verify-editor.mjs
 *
 * Requires the app to be open (any project) on the API 37 emulator. Checks:
 *   1. the recording library is not empty,
 *   2. two same-format recordings concatenate and report the joined duration,
 *   3. a File uploads through the wallpaper byte channel and is served back.
 *
 * It leaves no import scratch file: `finishRecordingImport("")` discards every
 * pending output. A wallpaper copy is left behind, as a normal user action
 * would.
 */
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { appProcessId, shell, forward } from "./adb.mjs";

const packageName = "app.tapswoop.android.debug";
const require = createRequire(import.meta.url);
const { chromium } = require("../recordly-upstream/node_modules/playwright");

const pid = await appProcessId(packageName);
assert.ok(pid, "Start Recordly on the emulator first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222", { timeout: 10_000 });
try {
	const page = browser
		.contexts()
		.flatMap(context => context.pages())
		.find(candidate => candidate.url().startsWith("http://127.0.0.1:"));
	assert.ok(page, "Recordly renderer not found");

	const result = await page.evaluate(async () => {
		const api = window.electronAPI;
		const list = await api.listRecordings();
		const entries = list.value || [];
		const metadata = [];
		for (const entry of entries) {
			const probe = await api.probeNativeVideoMetadata(entry.path);
			if (probe.success) metadata.push({ ...entry, ...probe.metadata });
		}

		const first = metadata.find(
			item => item.duration > 1 && metadata.some(other => other !== item &&
				other.width === item.width && other.height === item.height && other.duration > 1),
		);
		const second = first && metadata.find(other => other !== first &&
			other.width === first.width && other.height === first.height && other.duration > 1);

		let imported = null;
		if (first && second) {
			const current = await api.getCurrentVideoPath();
			const base = current.success ? current.path : first.path;
			const importedResult = await api.importRecording(base, second.path);
			if (importedResult.success) {
				const value = importedResult.value;
				const expected = value.sourceStartMs + value.durationMs;
				imported = {
					...value,
					expectedTotalMs: expected,
					deltaMs: Math.abs(value.totalDurationMs - expected),
					probe: await api.probeNativeVideoMetadata(value.path),
				};
				// Leave no scratch file behind; the renderer would call this on
				// cancel. An empty keep-path discards every pending output.
				await api.finishRecordingImport("");
				// The import made the joined file the active source; point it
				// back at the real one now that the scratch copy is gone.
				await api.setCurrentVideoPath(base, { preserveProjectPath: true });
			} else {
				imported = { error: importedResult.error };
			}
		}

		let wallpaper = null;
		const wallpaperSource = metadata.find(item => item.url);
		if (wallpaperSource) {
			const blob = await (await fetch(wallpaperSource.url)).blob();
			const file = new File([blob], "verify-wallpaper.mp4", { type: "video/mp4" });
			const uploaded = await api.importWallpaperVideo(file);
			const local = uploaded.success ? await api.getLocalMediaUrl(uploaded.path) : null;
			wallpaper = { uploaded, local };
		}

		return {
			recordingCount: entries.length,
			imported,
			wallpaper,
			methods: window.__recordlyBridge?.methods.length,
		};
	});

	assert.ok(result.recordingCount > 0, "Recording library is empty");
	assert.ok(result.imported && !result.imported.error, "importRecording failed");
	assert.ok(result.imported.deltaMs <= 250, `Join duration drifted by ${result.imported.deltaMs} ms`);
	assert.ok(result.imported.probe.success, "Joined file cannot be probed");
	assert.ok(
		Math.abs(result.imported.probe.metadata.duration * 1000 - result.imported.totalDurationMs) < 500,
		"Joined file duration does not match the reported total",
	);
	assert.ok(result.wallpaper?.uploaded?.success, "Wallpaper upload failed");
	assert.ok(result.wallpaper?.local?.success, "Wallpaper was not served back");

	// The bridge has no delete-wallpaper method; remove the verifier's copy so
	// repeated runs do not accumulate files the user never chose.
	await shell(["run-as", packageName, "rm", "-f", result.wallpaper.uploaded.path]);

	console.log(JSON.stringify(result, null, 2));
} finally {
	await browser.close();
}
