/**
 * Proves tap telemetry reaches a saved recording's sidecar.
 *
 *   node dev/verify-telemetry.mjs
 *
 * Enables the app's observe-only AccessibilityService, records a take, taps
 * real Settings rows while the screen is being recorded, stops the take, and
 * asserts the sidecar contains click/mouseup pairs with normalized positions.
 * Emulator only: it launches and taps system UI.
 */
import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { shell, execOut, forward } from "./adb.mjs";

const packageName = "app.recordly.android.debug";
const serviceName = `${packageName}/app.recordly.android.TelemetryAccessibilityService`;
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
	const result = await shell([
		"input",
		"tap",
		String(Math.round((bounds[0] + bounds[2]) / 2)),
		String(Math.round((bounds[1] + bounds[3]) / 2)),
	]);
	assert.equal(result.code, 0, result.stderr);
}

async function tapText(text) {
	const nodes = await androidNodes();
	const node = nodes.find((candidate) => candidate.text === text || candidate["content-desc"] === text);
	assert.ok(
		node,
		`Android control not found: ${text}; visible: ${nodes.map((candidate) => candidate.text).filter(Boolean)}`,
	);
	await tapNode(node);
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

await enableTelemetry();
await delay(1_500);
const pid = (await shell(["pidof", packageName])).stdout.trim().split(/\s+/)[0];
assert.ok(pid, "Start Recordly on the emulator first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222", { timeout: 10_000 });
let completed = false;
let page = null;
try {
	// A previous take can leave the task backgrounded; a hidden page cannot be
	// clicked. Bring the existing task to the front without recreating it.
	await shell(["am", "start", "-n", `${packageName}/app.recordly.android.MainActivity`]);
	await delay(1_000);
	page = browser
		.contexts()
		.flatMap((context) => context.pages())
		.find((candidate) => candidate.url().startsWith("http://127.0.0.1:"));
	assert.ok(page, "Recordly renderer not found");
	if (!page.url().includes("windowType=hud-overlay")) {
		await page.evaluate(() => {
			setTimeout(() => window.electronAPI.showRecordingHud(), 100);
		});
		await page.waitForURL(/windowType=hud-overlay/, { timeout: 20_000 });
		page = browser
			.contexts()
			.flatMap((context) => context.pages())
			.find((candidate) => candidate.url().startsWith("http://127.0.0.1:"));
	}
	await page.waitForTimeout(500);
	const status = await page.evaluate(() => window.electronAPI.getAccessibilityPermissionStatus());
	assert.equal(status.trusted, true, "the telemetry accessibility service is not enabled");

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
	await delay(2_000);

	// Real UI clicks while the display is recorded. Settings rows are ordinary
	// views, so observe-only accessibility delivers TYPE_VIEW_CLICKED. A failed
	// earlier run can leave the shade expanded; close it before launching.
	await shell(["cmd", "statusbar", "collapse"]);
	await delay(500);
	await shell(["am", "start", "-a", "android.settings.SETTINGS"]);
	await delay(2_500);
	// Settings restores its scroll position across launches.
	await shell(["input", "swipe", "540", "600", "540", "2000", "400"]);
	await delay(1_000);
	await tapText("Network & internet");
	await delay(1_200);
	await shell(["input", "keyevent", "KEYCODE_BACK"]);
	await delay(1_200);
	await tapText("Battery");
	await delay(1_200);
	await shell(["input", "keyevent", "KEYCODE_BACK"]);
	await delay(1_000);

	// Prefer the foreground notification, as a user stops a take. The shade can
	// present the Recordly card collapsed, so fall back to the bridge stop and
	// an explicit surface switch; telemetry is the subject of this verifier,
	// and verify-capture already exercises the notification controls.
	await shell(["cmd", "statusbar", "expand-notifications"]);
	await delay(800);
	let stoppedFromNotification = true;
	try {
		await tapText("Stop");
	} catch {
		stoppedFromNotification = false;
		await shell(["cmd", "statusbar", "collapse"]);
		await page.evaluate(() => window.electronAPI.stopNativeScreenRecording());
	}
	const log = await waitForLog(/recording written: (.+\.mp4) \(/, 30_000);
	const sourcePath = log.match(/recording written: (.+\.mp4) \(/)[1];
	await shell(["cmd", "statusbar", "collapse"]);
	if (stoppedFromNotification) {
		await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
	} else {
		await page.evaluate(() => window.electronAPI.switchToEditor());
		await page.waitForURL(/windowType=editor/, { timeout: 20_000 });
	}
	completed = true;

	const telemetry = await page.evaluate(
		(path) => window.electronAPI.getCursorTelemetry(path),
		sourcePath,
	);
	assert.ok(telemetry?.success, `telemetry failed: ${JSON.stringify(telemetry)}`);
	const samples = telemetry.samples || [];
	assert.ok(samples.length > 0, "the recording has no cursor sidecar samples");
	const clicks = samples.filter((sample) => sample.interactionType === "click");
	const ups = samples.filter((sample) => sample.interactionType === "mouseup");
	assert.ok(clicks.length >= 2, `expected at least two taps, got ${clicks.length}`);
	assert.equal(clicks.length, ups.length, "each tap should emit a click and a mouseup");
	for (const sample of [...clicks, ...ups]) {
		assert.ok(
			sample.cx >= 0 && sample.cx <= 1 && sample.cy >= 0 && sample.cy <= 1,
			`normalized position out of range: ${JSON.stringify(sample)}`,
		);
		assert.equal(sample.mouseButton, 1, `touch sample lacks the left-button marker: ${JSON.stringify(sample)}`);
	}
	for (let index = 1; index < samples.length; index++) {
		assert.ok(
			samples[index].timeMs >= samples[index - 1].timeMs,
			"sidecar timestamps are not monotonic",
		);
	}

	await writeFile(new URL("telemetry-samples.json", outputDir), JSON.stringify(samples, null, 2));
	await writeFile(
		new URL("telemetry-capture.json", outputDir),
		JSON.stringify(
			{
				sourcePath,
				sampleCount: samples.length,
				clicks: clicks.length,
				firstClick: clicks[0],
				lastClick: clicks[clicks.length - 1],
			},
			null,
			2,
		),
	);
	await writeFile(
		new URL("recording-with-taps.mp4", outputDir),
		await execOut(["run-as", packageName, "cat", sourcePath]),
	);
	console.log(
		JSON.stringify(
			{
				ok: true,
				sourcePath,
				sampleCount: samples.length,
				clicks: clicks.length,
				firstClick: clicks[0],
				lastClick: clicks[clicks.length - 1],
			},
			null,
			2,
		),
	);
} finally {
	if (!completed && /capture started/.test(await captureLog())) {
		// A failed verification must not leave the emulator recording indefinitely.
		await page?.evaluate(() => window.electronAPI.stopNativeScreenRecording()).catch(() => {});
		await delay(2_000);
		if (/capture started/.test(await captureLog())) {
			await shell([
				"am",
				"startservice",
				"-n",
				`${packageName}/app.recordly.android.ScreenCaptureService`,
				"-a",
				"app.recordly.android.action.STOP_CAPTURE",
			]);
		}
	}
	await browser.close();
}
