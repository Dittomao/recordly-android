/** Verify the editor on a visible Windows Android emulator and save its actual screen. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { setTimeout as delay } from "node:timers/promises";
import { appProcessId, execOut, forward, shell } from "./adb.mjs";

const serial = process.env.RECORDLY_DEVICE || "emulator-5554";
assert.match(serial, /^emulator-\d+$/, "Use an emulator, not a physical device");
const port = serial.split("-")[1];
const visibleWindow = execFileSync("powershell.exe", [
	"-NoProfile", "-Command",
	`(Get-Process 'qemu-system-*' -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle -like "Android Emulator*:${port}" }).MainWindowTitle`,
], { encoding: "utf8" }).trim();
assert.ok(visibleWindow, "Start the selected emulator without -no-window");
console.log(visibleWindow);

const pkg = "app.tapswoop.android.debug";
const pid = await appProcessId(pkg);
assert.ok(pid, "Open a test project in the Android editor first");
await forward(9370, `localabstract:webview_devtools_remote_${pid}`);
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const browser = await chromium.connectOverCDP("http://127.0.0.1:9370");
const out = new URL("./verification/cut-room/visible/", import.meta.url);
await mkdir(out, { recursive: true });
const capture = process.argv.includes("--record");
const deviceVideo = `/data/local/tmp/tapswoop-visual-${process.pid}.mp4`;
let recording;

async function shot(name) {
	await delay(700); // Let the preview decode and leave the result visible on screen.
	await writeFile(new URL(`${name}.png`, out), await execOut(["screencap", "-p"]));
}

try {
	const page = browser.contexts().flatMap((c) => c.pages())
		.find((p) => p.url().includes("windowType=editor"));
	assert.ok(page, "Open the editor on the emulator before running this check");
	page.setDefaultTimeout(15_000);
	const editor = page.locator(".cut-editor");
	// A cold WebView can take longer than the default timeout to mount the preview after an install.
	await editor.locator('.editor-preview-frame canvas:not([aria-hidden="true"])').waitFor({ timeout: 45_000 });
	const ruler = editor.locator(".cut-timeline-ruler");
	const cap = editor.getByTestId("playhead-cap");
	const track = await editor.locator('[data-timeline-row="row-clip"] .cut-track-body').boundingBox();
	assert.ok(track, "Use a test project with footage and at least one zoom");
	if (capture) {
		recording = shell(["screenrecord", "--time-limit", "24", "--size", "540x1200", deviceVideo]);
		await delay(600);
	}

	const axis = await ruler.boundingBox();
	assert.ok(axis);
	await page.mouse.click(track.x + track.width * 0.25, axis.y + 29);
	const grip = await cap.boundingBox();
	assert.ok(grip);
	const cdp = await page.context().newCDPSession(page);
	const dragY = grip.y + 8;
	await cdp.send("Input.dispatchTouchEvent", {
		type: "touchStart", touchPoints: [{ x: grip.x + grip.width / 2, y: dragY }],
	});
	try {
		for (let index = 1; index <= 12; index++) {
			const x = grip.x + grip.width / 2 +
				(track.x + track.width * 0.63 - grip.x - grip.width / 2) * index / 12;
			await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [{ x, y: dragY }] });
			await delay(35);
		}
		const bubble = await cap.locator(".timeline-playhead-thumb").last().boundingBox();
		const label = await ruler.locator(".cut-time-tick > span").first().boundingBox();
		assert(bubble && label && bubble.y + bubble.height <= label.y, "Ruler text overlaps the drag timestamp");
		assert.equal(await editor.getByTestId("timeline-hover-guide").count(), 0,
			"A stale hover guide must not look like a second playhead");
		const zoomBlocks = await editor.locator('[data-variant="zoom"] .timeline-block').evaluateAll((blocks) =>
			blocks.map((node) => {
				const icon = node.querySelector(".zoom-icon");
				return {
					width: node.clientWidth,
					solid: getComputedStyle(node).backgroundImage === "none",
					icon: icon && getComputedStyle(icon).display !== "none" ? icon.getBoundingClientRect().width : 0,
				};
			}));
		assert(zoomBlocks.length && zoomBlocks.every(({ width, solid, icon }) =>
			solid && (width < 22 ? icon === 0 : Math.abs(icon - 12) <= 0.5)),
		`Clipped zoom details: ${JSON.stringify(zoomBlocks)}`);
		await shot("timeline");
		console.log("PASS: compact zooms and separate ruler text while dragging");
	} finally {
		await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
		await cdp.detach();
	}

	const back = async () => assert.equal((await shell(["input", "keyevent", "KEYCODE_BACK"])).code, 0);
	await editor.getByRole("button", { name: /^Aspect ratio:/ }).click();
	const aspect = page.getByRole("dialog", { name: "Aspect ratio", exact: true });
	const lastStandard = aspect.locator(".aspect-ratio-picker__groups > .aspect-ratio-picker__grid .aspect-ratio-picker__option").last();
	assert.equal((await lastStandard.textContent())?.trim(), "9:16");
	const [aspectBody, preset] = await Promise.all([
		aspect.locator(".aspect-ratio-picker__body").boundingBox(),
		lastStandard.boundingBox(),
	]);
	assert(aspectBody && preset && preset.y + preset.height <= aspectBody.y + aspectBody.height,
		"Portrait preset should fit without scrolling at normal phone height");
	const cover = await aspect.locator(".aspect-ratio-picker__option").filter({ hasText: "3:1" })
		.locator(".aspect-ratio-picker__shape").boundingBox();
	assert(cover && Math.abs(cover.width / cover.height - 3) < 0.1, "The 3:1 preset preview is distorted");
	await shot("aspect");
	await back();
	await aspect.waitFor({ state: "hidden" });
	console.log("PASS: standard frame ratios visible and preset thumbnails keep their proportions");

	await editor.getByRole("button", { name: "Export", exact: true }).click();
	const exportMenu = page.locator(".cut-export-menu");
	const saveHeight = await page.getByRole("row", { name: "Save to device", exact: true }).evaluate((node) => node.offsetHeight);
	assert(saveHeight >= 44 && saveHeight <= 56, `Expected a compact local-save row; got ${saveHeight}px`);
	const action = await exportMenu.getByRole("button", { name: "Export Video", exact: true }).boundingBox();
	assert(action && action.y + action.height <= await page.evaluate(() => innerHeight),
		"Export action is outside the viewport");
	await shot("export");
	await back();
	await exportMenu.waitFor({ state: "hidden" });
	console.log("PASS: compact local destination, visible export action and working hardware Back");

	const preview = editor.locator(".cut-preview-mat");
	const dock = editor.locator(".cut-edit-dock");
	const timeline = editor.getByTestId("timeline-scroll");
	const inspector = editor.locator(".cut-inline-inspector");
	// The app keeps a tool panel open between runs; start from the timeline so the first tap opens it.
	if (await inspector.isVisible()) {
		await back();
		await inspector.waitFor({ state: "detached" });
	}
	const previewBounds = await preview.boundingBox();
	const dockBounds = await dock.boundingBox();
	async function openPanel(name) {
		await editor.getByRole("button", { name, exact: true }).click();
		await inspector.getByRole("heading", { name, exact: true }).waitFor();
		assert.deepEqual(await inspector.boundingBox(), dockBounds, `${name} must stay in the timeline slot`);
		assert.deepEqual(await preview.boundingBox(), previewBounds, `${name} must not move the preview`);
		assert(await timeline.isHidden());
		assert.equal(await page.locator(".cut-motion-overlay:visible").count(), 0, `${name} must not dim the preview`);
	}

	await shot("inline-before");
	await openPanel("Look");
	assert.equal(await inspector.getByRole("button", { name: "Frame", exact: true }).getAttribute("aria-pressed"), "true");
	assert(await inspector.locator(".cut-inspector-tabs").evaluate((node) => node.scrollWidth <= node.clientWidth + 1),
		"Every Look tab must fit without sideways scrolling");
	const body = await inspector.locator(".cut-inspector-scroll").boundingBox();
	const scrollArea = inspector.locator(".cut-inspector-scroll");
	for (const name of ["Padding", "Corner radius"]) {
		const slider = page.getByRole("slider", { name, exact: true });
		const hit = await slider.boundingBox();
		assert(body && hit && hit.height >= 44 && hit.y >= body.y && hit.y + hit.height <= body.y + body.height,
			`${name} should be in view on opening Look`);
	}
	await scrollArea.evaluate((node) => node.scrollTo(0, node.scrollHeight));
	const removeBackground = inspector.getByRole("switch", { name: "Remove background" });
	await removeBackground.waitFor();
	assert(await removeBackground.isVisible(), "Remove background should stay reachable inside the panel");
	await scrollArea.evaluate((node) => node.scrollTo(0, 0));
	await shot("inline-look");
	await inspector.getByRole("button", { name: "Background", exact: true }).click();
	await inspector.locator('button.wallpaper-tile:has(img)').first().waitFor();
	await shot("inline-background");
	assert.deepEqual(await preview.boundingBox(), previewBounds);
	console.log("PASS: Padding and Corner radius are visible on opening Look; Background opens separately");
	const time = editor.locator(".cut-time strong");
	const beforePlay = await time.textContent();
	await editor.getByRole("button", { name: "Play", exact: true }).click();
	await page.waitForFunction((value) => document.querySelector(".cut-time strong")?.textContent !== value, beforePlay);
	await editor.getByRole("button", { name: "Pause", exact: true }).click();
	assert(await inspector.isVisible(), "Using playback must not close the panel");
	await openPanel("Audio");
	await inspector.getByRole("slider", { name: "Preview volume" }).waitFor();
	await shot("inline-audio");
	await openPanel("Clips");
	await inspector.locator("[data-recording-library]").waitFor();
	await shot("inline-clips");
	await back();
	await inspector.waitFor({ state: "detached" });
	await timeline.waitFor();
	assert.deepEqual(await preview.boundingBox(), previewBounds);
	console.log("PASS: Look, Audio and Clips replace only the timeline, playback works, and Android Back restores the timeline");
} finally {
	await browser.close();
	if (recording) {
		try {
			const result = await recording;
			assert.equal(result.code, 0, result.stderr);
			await writeFile(new URL("editor-tests.mp4", out), await execOut(["cat", deviceVideo]));
		} finally {
			await shell(["rm", "-f", deviceVideo]);
		}
	}
}
