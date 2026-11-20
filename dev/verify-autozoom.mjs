/**
 * Auto-zoom verification for touch input.
 *
 *   node dev/verify-autozoom.mjs          # assert tap/swipe policy
 *   node dev/verify-autozoom.mjs --report # record and print regions only
 *
 * Records a take while tapping three Settings rows and swiping the list once,
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
	await shell(["uiautomator", "dump", "/sdcard/recordly-window.xml"]);
	const xml = (await execOut(["cat", "/sdcard/recordly-window.xml"])).toString();
	return [...xml.matchAll(/<node\b([^>]*)>/g)].map((match) =>
		Object.fromEntries(
			[...match[1].matchAll(/([\w:-]+)="([^"]*)"/g)].map((attribute) => [
				attribute[1],
				decode(attribute[2]),
			]),
		),
	);
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

async function tapText(text) {
	const nodes = await androidNodes();
	const node = nodes.find((candidate) => candidate.text === text || candidate["content-desc"] === text);
	assert.ok(
		node,
		`Android control not found: ${text}; visible: ${nodes.map((candidate) => candidate.text).filter(Boolean)}`,
	);
	return tapNode(node);
}

async function tapFirst(labels) {
	for (let attempt = 0; attempt < 12; attempt++) {
		const nodes = await androidNodes();
		for (const label of labels) {
			const node = nodes.find((candidate) => candidate.text === label);
			if (node) return tapNode(node);
		}
		await delay(500);
	}
	throw new Error(`No Settings row found among: ${labels.join(", ")}`);
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
	await delay(400);
	await shell(["cmd", "statusbar", "expand-notifications"]);
	await delay(800);
	let fromNotification = true;
	try {
		await tapText("Stop");
	} catch {
		fromNotification = false;
		await shell(["cmd", "statusbar", "collapse"]);
		await page.evaluate(() => window.electronAPI.stopNativeScreenRecording());
	}
	const log = await waitForLog(/recording written: (.+\.mp4) \(/, 30_000);
	const sourcePath = log.match(/recording written: (.+\.mp4) \(/)[1];
	await shell(["cmd", "statusbar", "collapse"]);
	if (fromNotification) {
		try {
			await page.waitForURL(/windowType=editor/, { timeout: 12_000 });
		} catch {
			await page.evaluate(() => window.electronAPI.switchToEditor());
			await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
		}
	} else {
		await page.evaluate(() => window.electronAPI.switchToEditor());
		await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
	}
	return sourcePath;
}

await enableTelemetry();
await delay(1_500);
const pid = (await shell(["pidof", packageName])).stdout.trim().split(/\s+/)[0];
assert.ok(pid, "Start Recordly on the emulator first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222", { timeout: 10_000 });
let completed = false;
let page = null;
try {
	await shell(["am", "start", "-n", `${packageName}/app.recordly.android.MainActivity`]);
	await delay(1_000);
	page = browser
		.contexts()
		.flatMap((context) => context.pages())
		.find((candidate) => candidate.url().startsWith("http://127.0.0.1:"));
	assert.ok(page, "Recordly renderer not found");
	if (!page.url().includes("windowType=hud-overlay")) {
		for (let attempt = 0; attempt < 3 && !page.url().includes("windowType=hud-overlay"); attempt++) {
			await page.evaluate(() => {
				setTimeout(() => window.electronAPI.showRecordingHud(), 100);
			});
			try {
				await page.waitForURL(/windowType=hud-overlay/, { timeout: 8_000 });
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
	await page.getByRole("button", { name: "Record", exact: true }).click({ timeout: 10_000 });
	const consentDeadline = Date.now() + 30_000;
	while (Date.now() < consentDeadline) {
		const nodes = await androidNodes();
		const find = (text) => nodes.find((node) => node.text === text);
		const next =
			find("While using the app") ||
			find("Allow") ||
			find("Share screen") ||
			find("Share entire screen") ||
			find("Share one app");
		if (next) await tapNode(next);
		if (/capture started/.test(await captureLog())) break;
		await delay(300);
	}
	await waitForLog(/capture resumed/);
	await delay(1_500);

	// Three taps, each on a different Settings row.
	await shell(["cmd", "statusbar", "collapse"]);
	await shell(["am", "start", "-a", "android.settings.SETTINGS"]);
	await delay(3_500);
	// Settings restores its scroll position across launches; start at the top
	// so the same rows are available on every run.
	await shell(["input", "swipe", "540", "600", "540", "2000", "400"]);
	await delay(1_000);
	const tap1 = await tapFirst(["Network & internet", "Connected devices", "Battery"]);
	await delay(500);
	await shell(["input", "keyevent", "KEYCODE_BACK"]);
	await delay(500);
	const tap2 = await tapFirst(["Battery", "Display & touch", "Storage"]);
	await delay(500);
	await shell(["input", "keyevent", "KEYCODE_BACK"]);
	await delay(500);

	// A swipe with no tap. It must not produce a zoom region.
	const swipeStartedAt = Date.now();
	await shell(["input", "swipe", "540", "1700", "540", "700", "400"]);
	await delay(1_200);

	// A fourth tap after the swipe.
	const tap3 = await tapFirst([
		"Storage",
		"Sound & vibration",
		"Display & touch",
		"Apps",
		"Security & privacy",
		"Location",
		"Passwords & passkeys",
		"Safety & emergency",
	]);
	await delay(500);
	await shell(["input", "keyevent", "KEYCODE_BACK"]);
	await delay(700);

	const sourcePath = await stopRecording(page);
	completed = true;
	await delay(3_000);

	// Autosave writes the generated regions shortly after the editor hydrates.
	// The first matching file can be an autosave from before the regions landed,
	// so keep polling until the live/newest project actually contains them.
	let project = null;
	const sourceName = sourcePath.split("/").pop();
	for (let attempt = 0; attempt < 25; attempt++) {
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
			mergedGapMs: project.editor?.connectedZoomGapMs,
		},
		taps: [tap1, tap2, tap3],
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
		})),
		checks,
	};
	await writeFile(new URL("autozoom-report.json", outputDir), JSON.stringify(summary, null, 2));
	console.log(JSON.stringify(summary, null, 2));

	if (!reportOnly) {
		const clicks = (telemetry.samples || []).filter(
			(sample) => sample.interactionType === "click",
		);
		assert.ok(clicks.length >= 3, `expected three taps, got ${clicks.length}`);
		assert.equal(
			regions.length,
			clicks.length,
			`expected one zoom region per tap; ${clicks.length} taps, ${regions.length} regions`,
		);
		for (const click of clicks) {
			const region = regions.find(
				(candidate) => click.timeMs >= candidate.startMs && click.timeMs <= candidate.endMs,
			);
			assert.ok(region, `tap at ${click.timeMs}ms is not covered by a zoom region`);
			// Touch has no cursor: the zoom must start with the tap, not the
			// desktop one-second pre-roll.
			const leadMs = click.timeMs - region.startMs;
			assert.ok(
				leadMs >= 50 && leadMs <= 700,
				`tap at ${click.timeMs}ms starts zooming ${leadMs}ms early (expected 50-700ms)`,
			);
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
		for (const region of regions) {
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
