/**
 * Auto-zoom verification for touch input.
 *
 *   node dev/verify-autozoom.mjs          # assert tap/swipe policy
 *   node dev/verify-autozoom.mjs --report # record and print regions only
 *
 * Records a take while tapping four Clock timer keys and swiping the display,
 * then reads the project's generated zoom regions. A tap must produce its own
 * zoom in/out; a swipe must produce none.
 */
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { shell, execOut, forward } from "./adb.mjs";

const packageName = "app.recordly.android.debug";
const serviceName = `${packageName}/app.recordly.android.TelemetryAccessibilityService`;
const reportOnly = process.argv.includes("--report");
assert.match(process.env.RECORDLY_DEVICE || "emulator-5554", /^emulator-/, "Use a test emulator");
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const outputDir = new URL("./verification/", import.meta.url);
await mkdir(outputDir, { recursive: true });
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const decode = (text) =>
	text
		.replaceAll("&quot;", '"')
		.replaceAll("&amp;", "&")
		.replaceAll("&lt;", "<")
		.replaceAll("&gt;", ">")
		.replaceAll("&apos;", "'");

async function androidNodes() {
	let lastError;
	for (let attempt = 0; attempt < 5; attempt++) {
		try {
			await shell(["rm", "-f", "/sdcard/recordly-window.xml"]);
			const dump = await shell(["uiautomator", "dump", "/sdcard/recordly-window.xml"]);
			if (dump.code === 0) {
				const xml = (await execOut(["cat", "/sdcard/recordly-window.xml"])).toString();
				if (xml.includes("<hierarchy")) {
					return [...xml.matchAll(/<node\b([^>]*)>/g)].map((match) =>
						Object.fromEntries(
							[...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)].map((attribute) => [
								attribute[1],
								decode(attribute[2]),
							]),
						),
					);
				}
			}
		} catch (error) {
			lastError = error;
		}
		await delay(400);
	}
	throw new Error(`Could not read the Android window: ${lastError ?? "empty hierarchy"}`);
}

async function tapNode(node) {
	const bounds = node.bounds.match(/\d+/g).map(Number);
	assert.equal(bounds.length, 4, `bad bounds: ${node.bounds}`);
	const result = await shell([
		"input",
		"tap",
		String(Math.round((bounds[0] + bounds[2]) / 2)),
		String(Math.round((bounds[1] + bounds[3]) / 2)),
	]);
	assert.equal(result.code, 0, result.stderr);
	return { x: Math.round((bounds[0] + bounds[2]) / 2), y: Math.round((bounds[1] + bounds[3]) / 2) };
}

async function dismissAnr(nodes) {
	const wait = nodes.find((node) => node.text === "Wait");
	const close = nodes.find((node) => node.text === "Close app");
	if (!wait || !close) return false;
	await tapNode(wait);
	await delay(1_000);
	return true;
}

async function tapText(text, snapshot) {
	let nodes = snapshot ?? await androidNodes();
	if (await dismissAnr(nodes)) nodes = await androidNodes();
	const node = nodes.find((candidate) => candidate.text === text || candidate["content-desc"] === text);
	assert.ok(
		node,
		`Android control not found: ${text}; visible: ${nodes.map((candidate) => candidate.text).filter(Boolean)}`,
	);
	return tapNode(node);
}

async function showClock() {
	const result = await shell([
		"am",
		"start",
		"-n",
		"com.google.android.deskclock/com.android.deskclock.DeskClock",
	]);
	assert.equal(result.code, 0, result.stderr);
}

async function enableTelemetry() {
	const existing = (await shell(["settings", "get", "secure", "enabled_accessibility_services"])).stdout.trim();
	const set = new Set(existing.split(":").filter(Boolean));
	set.add(serviceName);
	await shell(["settings", "put", "secure", "enabled_accessibility_services", [...set].join(":")]);
	await shell(["settings", "put", "secure", "accessibility_enabled", "1"]);
}

async function captureLog() {
	return (
		await shell([
			"logcat",
			"-d",
			"-v",
			"time",
			"-s",
			"CaptureSession",
			"ScreenCapture",
			"TelemetryAccessibilityService",
			"Telemetry",
		])
	).stdout;
}

async function waitForLog(pattern, timeout = 20_000) {
	const deadline = Date.now() + timeout;
	while (Date.now() < deadline) {
		const log = await captureLog();
		if (pattern.test(log)) return log;
		await delay(500);
	}
	throw new Error(`Capture did not reach ${pattern}\n${await captureLog()}`);
}

async function stopRecording(page) {
	await shell(["cmd", "statusbar", "collapse"]);
	// The recording backgrounds Recordly. Bring its single task back before
	// asking Playwright to wait for a stable, visible HUD control.
	await shell(["am", "start", "-W", "-n", `${packageName}/app.recordly.android.MainActivity`]);
	// Click the HUD's Stop control. This runs the renderer's full stop
	// sequence, which sets the new video as current and opens the editor.
	// Calling stopNativeScreenRecording directly only stops the native
	// service, so the editor has no new project to load.
	await page.getByRole("button", { name: "Stop", exact: true }).click({ timeout: 60_000 });
	const log = await waitForLog(/recording written: (.+\.mp4) \(/, 60_000);
	const sourcePath = log.match(/recording written: (.+\.mp4) \(/)[1];
	await shell(["cmd", "statusbar", "collapse"]);
	await page.waitForURL(/windowType=editor/, { timeout: 60_000 });
	return sourcePath;
}

await enableTelemetry();
// Choose the Timer tab before starting capture. Otherwise the saved Clock tab
// changes the fixture or introduces a fifth click into tap telemetry.
await showClock();
await delay(2_000);
const clockNodes = await androidNodes();
if (!clockNodes.some((node) => node.text === "1")) {
	await tapText("Timers");
	await delay(1_000);
}
await shell(["am", "start", "-n", `${packageName}/app.recordly.android.MainActivity`]);
await delay(2_000);
let pid = "";
for (let attempt = 0; attempt < 20; attempt++) {
	pid = (await shell(["pidof", packageName])).stdout.trim().split(/\s+/)[0];
	if (pid) break;
	await delay(500);
}
assert.ok(pid, "Could not start Recordly on the emulator");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
let browser;
for (let attempt = 0; attempt < 5; attempt++) {
	try {
		browser = await chromium.connectOverCDP("http://127.0.0.1:9222", { timeout: 30_000 });
		break;
	} catch (error) {
		if (attempt === 4) throw error;
		await delay(2_000);
	}
}
assert.ok(browser, "Could not connect to the Recordly WebView");
let completed = false;
let page = null;
try {
	await delay(1_000);
	page = browser
		.contexts()
		.flatMap((context) => context.pages())
		.find((candidate) => candidate.url().startsWith("http://127.0.0.1:"));
	assert.ok(page, "Recordly renderer not found");
	if (!page.url().includes("windowType=hud-overlay")) {
		for (let attempt = 0; attempt < 3 && !page.url().includes("windowType=hud-overlay"); attempt++) {
			await page
				.evaluate(() => {
					setTimeout(() => window.electronAPI.showRecordingHud(), 100);
				})
				.catch(() => {
					// The HUD switch reloads the WebView; on a slow emulator the
					// navigation can begin before evaluate returns its reply.
				});
			try {
				await page.waitForURL(/windowType=hud-overlay/, { timeout: 20_000 });
			} catch {
				// Retry: a stale native surface flag can make the first request a
				// no-op immediately after a take.
			}
			page = browser
				.contexts()
				.flatMap((context) => context.pages())
				.find((candidate) => candidate.url().startsWith("http://127.0.0.1:"));
		}
		assert.ok(page.url().includes("windowType=hud-overlay"), "Could not open the recording HUD");
	}
	await page.waitForTimeout(500);

	await shell(["logcat", "-c"]);
	await page.getByRole("button", { name: "Record", exact: true }).click({ timeout: 90_000 });
	const consentDeadline = Date.now() + 60_000;
	let sourceSheetOpened = false;
	while (Date.now() < consentDeadline) {
		const nodes = await androidNodes();
		if (await dismissAnr(nodes)) continue;
		const find = (text) => nodes.find((node) => node.text === text);
		const tap = async (node) => {
			if (node) await tapNode(node);
			return Boolean(node);
		};
		// Android 16's sheet defaults to "Share one app" behind a spinner.
		// Open it once, pick "Share entire screen", then confirm with Next/Share.
		// Older images expose "Share screen" directly.
		const oneApp = find("Share one app");
		const entireScreen = find("Share entire screen");
		let acted = false;
		if (find("While using the app")) {
			acted = await tap(find("While using the app"));
		} else if (find("Allow")) {
			acted = await tap(find("Allow"));
		} else if (oneApp && entireScreen) {
			acted = await tap(entireScreen);
			sourceSheetOpened = false;
		} else if (oneApp && !sourceSheetOpened) {
			acted = await tap(oneApp);
			sourceSheetOpened = true;
		} else if (find("Next")) {
			acted = await tap(find("Next"));
			sourceSheetOpened = false;
		} else if (find("Share screen")) {
			acted = await tap(find("Share screen"));
		} else if (find("Share")) {
			acted = await tap(find("Share"));
		} else if (find("Start now")) {
			acted = await tap(find("Start now"));
		} else if (entireScreen) {
			acted = await tap(entireScreen);
			sourceSheetOpened = false;
		}
		if (/capture started/.test(await captureLog())) break;
		await delay(acted ? (sourceSheetOpened ? 1_500 : 600) : 300);
	}
	await waitForLog(/capture resumed/);
	await delay(1_500);

	// Four taps around a swipe on the Clock timer keypad. Clock is far
	// lighter than system Settings, which ANRs under emulator load.
	await shell(["cmd", "statusbar", "collapse"]);
	await showClock();
	await delay(5_000);
	const sizeOutput = (await shell(["wm", "size"])).stdout;
	const screen =
		sizeOutput.match(/Override size:\s*(\d+)x(\d+)/) ||
		sizeOutput.match(/Physical size:\s*(\d+)x(\d+)/);
	const swipeX = screen ? Math.round(Number(screen[1]) / 2) : 540;
	const screenHeight = screen ? Number(screen[2]) : 2400;
	// The Timer layout is fixed. Reuse one bounds snapshot: dumping the UI
	// between each tap adds seconds and never exercises the compact transitions.
	let keypad = await androidNodes();
	if (await dismissAnr(keypad)) keypad = await androidNodes();
	const tap1 = await tapText("1", keypad);
	await delay(1_200);
	const tap2 = await tapText("5", keypad);
	await delay(500);

	// A swipe with no tap, between the keypad taps. It must not zoom.
	const swipeStartedAt = Date.now();
	await shell([
		"input",
		"swipe",
		String(swipeX),
		String(Math.round(screenHeight * 0.27)),
		String(swipeX),
		String(Math.round(screenHeight * 0.14)),
		"400",
	]);
	await delay(1_500);

	const tap3 = await tapText("9", keypad);
	await delay(1_200);
	const tap4 = await tapText("0", keypad);
	await delay(1_200);
	const taps = [tap1, tap2, tap3, tap4];

	const sourcePath = await stopRecording(page);
	completed = true;
	await delay(3_000);

	// Autosave writes the generated regions shortly after the editor hydrates.
	// The first matching file can be an autosave from before the regions landed,
	// so keep polling until the live/newest project actually contains them.
	let project = null;
	const sourceName = sourcePath.split("/").pop();
	for (let attempt = 0; attempt < 90; attempt++) {
		const candidates = [];
		const current = await page.evaluate(() => window.electronAPI.loadCurrentProjectFile());
		if (current?.project) candidates.push(current.project);
		const list = await page.evaluate(() => window.electronAPI.listProjectFiles());
		const newest = (list.projects || []).sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))[0];
		if (newest) {
			const loaded = await page.evaluate(
				(path) => window.electronAPI.loadProjectFile(path),
				newest.path,
			);
			if (loaded?.project) candidates.push(loaded.project);
		}
		const matching = candidates.filter((candidate) =>
			candidate.videoPath?.includes(sourceName),
		);
		if (matching.length) {
			project =
				matching.find((candidate) => (candidate.editor?.zoomRegions || []).length > 0) ||
				matching[0];
			if ((project.editor?.zoomRegions || []).length > 0) break;
		}
		await delay(1_000);
	}
	assert.ok(project, "No autosaved project for the new recording");

	const telemetry = await page.evaluate(
		(path) => window.electronAPI.getCursorTelemetry(path),
		sourcePath,
	);
	const regions = project.editor?.zoomRegions || [];
	const clickSamples = (telemetry.samples || []).filter(
		(sample) => sample.interactionType === "click",
	);
	const scales = { 1: 1.25, 2: 1.5, 3: 1.8 };
	const clampAt = (value, scale) =>
		Math.max(1 / (2 * scale), Math.min(value, 1 - 1 / (2 * scale)));
	const errorAt = (click, depth) =>
		Math.max(
			Math.abs(clampAt(click.cx, scales[depth]) - click.cx),
			Math.abs(clampAt(click.cy, scales[depth]) - click.cy),
		);
	const checks = clickSamples.map((click) => {
		const region = regions.find(
			(candidate) => click.timeMs >= candidate.startMs && click.timeMs <= candidate.endMs,
		);
		return {
			tapMs: click.timeMs,
			leadMs: region ? click.timeMs - region.startMs : null,
			depth: region?.depth ?? null,
			renderedFocusError: region ? Number(errorAt(click, region.depth).toFixed(3)) : null,
		};
	});
	const summary = {
		sourcePath,
		editor: {
			connectZooms: project.editor?.connectZooms,
			zoomClassicMode: project.editor?.zoomClassicMode,
			zoomInDurationMs: project.editor?.zoomInDurationMs,
			zoomOutDurationMs: project.editor?.zoomOutDurationMs,
			zoomMotionBlur: project.editor?.zoomMotionBlur,
			tapPulseDurationMs: project.editor?.cursorClickEffectDurationMs,
			mergedGapMs: project.editor?.connectedZoomGapMs,
		},
		taps,
		swipeStartedAt,
		samples: telemetry.samples?.map((sample) => ({
			timeMs: sample.timeMs,
			cx: sample.cx,
			cy: sample.cy,
			interactionType: sample.interactionType,
		})),
		regions: regions.map((region) => ({
			startMs: region.startMs,
			endMs: region.endMs,
			depth: region.depth,
			focus: region.focus,
			fitTransitions: region.fitTransitions,
		})),
		checks,
	};
	await writeFile(new URL("autozoom-report.json", outputDir), JSON.stringify(summary, null, 2));
	console.log(JSON.stringify(summary, null, 2));

	if (!reportOnly) {
		const clicks = (telemetry.samples || []).filter(
			(sample) => sample.interactionType === "click",
		);
		assert.equal(clicks.length, taps.length, "expected one click per keypad tap and none for the swipe");
		assert.equal(
			regions.length,
			clicks.length,
			`expected one zoom region per tap; ${clicks.length} taps, ${regions.length} regions`,
		);
		assert.ok(regions.slice(0, -1).some(({ startMs, endMs }) => endMs - startMs < 1800),
			"the fixture did not exercise a compact zoom before another tap");
		for (const [index, click] of clicks.entries()) {
			if (screen) {
				const expected = taps[index];
				assert.ok(Math.abs(click.cx - expected.x / Number(screen[1])) < 0.04);
				assert.ok(Math.abs(click.cy - expected.y / Number(screen[2])) < 0.04);
			}
			const region = regions.find(
				(candidate) => click.timeMs >= candidate.startMs && click.timeMs <= candidate.endMs,
			);
			assert.ok(region, `tap at ${click.timeMs}ms is not covered by a zoom region`);
			// Touch has no cursor: the zoom must start with the tap, not the
			// desktop one-second pre-roll.
			const leadMs = click.timeMs - region.startMs;
			assert.equal(leadMs, 200, `tap at ${click.timeMs}ms does not have the Android 200 ms lead`);
			assert.equal(region.fitTransitions, true, "saved auto-zoom must retain fitted transitions");
			// Depth is chosen so the rendered focus can reach the tap rather
			// than clamping an edge tap back toward the centre.
			const expectedDepth = [1, 2, 3].find((depth) => errorAt(click, depth) <= 0.1 + 1e-6) ?? 3;
			assert.equal(
				region.depth,
				expectedDepth,
				`tap at (${click.cx}, ${click.cy}) chose depth ${region.depth}, expected ${expectedDepth}`,
			);
			const focusError = errorAt(click, region.depth);
			// A target in the last few percent of the screen cannot be centred
			// without a ~9x zoom (or uncovering the stage), so the touch policy
			// caps at 1.8x and accepts up to a quarter-frame offset. The ripple
			// still marks the tapped view; the camera is pinned to that edge.
			assert.ok(
				focusError <= 0.25,
				`tap at (${click.cx}, ${click.cy}) renders ${focusError.toFixed(3)} away from centre at depth ${region.depth}`,
			);
		}
		for (const [index, region] of regions.entries()) {
			if (index > 0) assert.ok(region.startMs - regions[index - 1].endMs >= 100);
			assert.ok(
				region.endMs - region.startMs >= 800,
				`zoom region ${region.startMs}-${region.endMs} is too short to see in and out`,
			);
		}
	}
} finally {
	if (!completed && /capture started/.test(await captureLog())) {
		await page?.evaluate(() => window.electronAPI.stopNativeScreenRecording()).catch(() => {});
	}
	await browser.close();
}
