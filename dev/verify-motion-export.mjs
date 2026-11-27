/**
 * Export the test take left open by verify-autozoom.mjs and check the actual
 * MP4 frame cadence. Requires the debug emulator, Playwright and ffprobe.
 */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { appProcessId, execOut, forward, shell } from "./adb.mjs";

const pkg = "app.tapswoop.android.debug";
assert.match(process.env.RECORDLY_DEVICE || "emulator-5554", /^emulator-/, "Use a test emulator");
const pid = await appProcessId(pkg);
assert.ok(pid, "Open Recordly on the test emulator first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");
const output = new URL("./verification/android-motion-parity.mp4", import.meta.url);
try {
	const page = browser.contexts().flatMap((context) => context.pages())
		.find((candidate) => candidate.url().includes("windowType=editor"));
	assert.ok(page, "Open the verified auto-zoom take in the editor first");
	const editor = await page.evaluate(async () => {
		const { project } = await window.electronAPI.loadCurrentProjectFile();
		const {
			zoomRegions, zoomMotionBlur, mp4FrameRate, cursorClickEffect,
			cursorClickEffectDurationMs, showCursor,
		} = project?.editor ?? {};
		return {
			zoomRegions, zoomMotionBlur, mp4FrameRate, cursorClickEffect,
			cursorClickEffectDurationMs, showCursor,
		};
	});
	assert.ok(editor.zoomRegions?.length >= 2, "Open a test take with multiple zooms");
	assert.ok(editor.zoomRegions.every(({ fitTransitions }) => fitTransitions));
	assert.equal(editor.zoomMotionBlur, 0.2, "Use the approved default camera blur for this test");
	assert.equal(editor.cursorClickEffect, "echo");
	assert.equal(editor.cursorClickEffectDurationMs, 420);
	assert.equal(editor.showCursor, false);
	assert.equal(editor.mp4FrameRate, 60);

	const action = page.getByRole("button", { name: "Export Video" });
	if (!(await action.isVisible())) {
		if (!(await page.getByRole("row", { name: "Save to device" }).isVisible())) {
			await page.getByRole("button", { name: "Export", exact: true }).click();
		}
		const local = page.getByRole("row", { name: "Save to device" });
		if ((await local.getAttribute("aria-selected")) !== "true") await local.click();
	}

	// Observe the return from the real save bridge; export and publication
	// still run through the editor UI and the original native method.
	const binding = await page.evaluateHandle(() => {
		const api = window.electronAPI;
		const names = ["finalizeExportedVideo", "saveExportedVideo"];
		const original = Object.fromEntries(names.map((name) => [name, api[name]]));
		const state = { saved: null, restore: () => Object.assign(api, original) };
		for (const name of names) {
			api[name] = async (...args) => {
				const saved = await original[name](...args);
				if (saved?.success) state.saved = saved;
				return saved;
			};
		}
		return state;
	});
	let saved;
	try {
		await action.click();
		saved = await (await page.waitForFunction((state) => state.saved, binding, {
			timeout: 300_000,
		})).jsonValue();
	} finally {
		await binding.evaluate((state) => state.restore()).catch(() => {});
		await binding.dispose();
	}
	await mkdir(new URL("./verification/", import.meta.url), { recursive: true });
	const video = await execOut(["run-as", pkg, "cat", saved.path]);
	await writeFile(output, video);
	const probe = JSON.parse(execFileSync("ffprobe", [
		"-v", "error", "-select_streams", "v:0", "-show_frames", "-show_streams",
		"-show_entries", "frame=best_effort_timestamp_time:stream=codec_name,width,height,avg_frame_rate",
		"-of", "json", fileURLToPath(output),
	], { encoding: "utf8" }));
	const [stream] = probe.streams;
	assert.equal(stream.codec_name, "h264");
	assert.equal(stream.avg_frame_rate, "60/1");
	const times = probe.frames.map(({ best_effort_timestamp_time }) =>
		Number(best_effort_timestamp_time),
	);
	assert.ok(times.length > 60);
	let maxFrameIntervalErrorMs = 0;
	for (let index = 1; index < times.length; index++) {
		const interval = (times[index] - times[index - 1]) * 1000;
		maxFrameIntervalErrorMs = Math.max(maxFrameIntervalErrorMs, Math.abs(interval - 1000 / 60));
	}
	assert.ok(maxFrameIntervalErrorMs < 0.1, `Uneven 60 fps cadence: ${maxFrameIntervalErrorMs} ms`);
	const report = { stream, frames: times.length, maxFrameIntervalErrorMs, editor, bytes: video.length };
	await writeFile(
		new URL("./verification/android-motion-parity-export.json", import.meta.url),
		JSON.stringify(report, null, 2),
	);
	console.log(JSON.stringify(report, null, 2));
} finally {
	await browser.close();
}
