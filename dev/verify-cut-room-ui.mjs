/** Exercise the packaged Android renderer in Chromium, with fixture media and a test bridge. */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile, mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const root = fileURLToPath(new URL("..", import.meta.url));
const out = join(root, "dev", "verification", "cut-room");
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const port = Number(process.env.CUT_ROOM_TEST_PORT || 4487);
const origin = `http://127.0.0.1:${port}`;
const server = spawn(process.execPath, ["dev/serve.mjs", "--port", String(port), "--root", "android/app/src/main/assets/renderer"], { cwd: root });
const testHost = `
const api = window.electronAPI;
api.setAppSetting("recordly.tapswoop.onboarding.v1.seen", true);
const now = Date.now();
const media = location.origin + "/fixtures/filmstrip.mp4";
const raw = { name: "Clock tutorial.mp4", path: "/recordings/Clock tutorial.mp4", url: media, bytes: 47000, createdAt: now };
window.__recordlyHost = {}; // enable touch presentation after the browser-only bridge booted
const projects = ["Metro walkthrough", "Settings guide"].map((name, i) => ({
	name, path: "/projects/" + name + ".recordly", createdAt: now - i, updatedAt: now - i,
	thumbnailPath: location.origin + "/fixtures/recording-thumbnail.jpg",
	isCurrent: false, isInProjectsDirectory: true,
}));
window.__cutRoomTest = { record: 0, picker: 0, open: 0 };
api.getCurrentVideoPath = async () => ({ success: true, path: null });
api.listProjectFiles = async () => ({ success: true, entries: projects });
api.listRecordings = async () => ({ success: true, value: [raw] });
api.showRecordingHud = async () => { window.__cutRoomTest.record++; return { success: true }; };
api.openVideoFilePicker = async () => {
	window.__cutRoomTest.picker++;
	return { success: true, path: media };
};
api.openProjectFileAtPath = async (path) => {
	window.__cutRoomTest.open++;
	return { success: true, path, project: { version: 2, videoPath: media, editor: {} } };
};
`;

async function withinViewport(page, locator, label) {
	const rect = await locator.boundingBox();
	const viewport = page.viewportSize();
	assert(rect && rect.x >= 0 && rect.x + rect.width <= viewport.width + 1 && rect.y >= 0 &&
		rect.y + rect.height <= viewport.height + 1, `${label} exceeds the viewport: ${JSON.stringify(rect)}`);
}

async function dragTouch(cdp, x, y, endX, endY = y) {
	await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y }] });
	for (let step = 1; step <= 6; step++) {
		await cdp.send("Input.dispatchTouchEvent", {
			type: "touchMove",
			touchPoints: [{ x: x + (endX - x) * step / 6, y: y + (endY - y) * step / 6 }],
		});
	}
	await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
}

async function verifyPlayheadTracking(page, editor, screen, ruler) {
	const [first, second] = await Promise.all(
		[0, 1].map((index) => screen.locator('[data-variant="clip"]').nth(index).boundingBox()),
	);
	assert(first && second, "Expected two clips with a cut to scrub across");
	const start = first.x;
	const end = second.x + second.width;
	const seam = (first.x + first.width + second.x) / 2;
	const line = editor.locator('[data-testid="timeline-playhead"] > div');
	const y = ruler.y + 8;
	await page.touchscreen.tap(start + (seam - start) * 0.5, ruler.y + 20);
	const { x: initialX } = await line.boundingBox();
	const anchor = await line.evaluate((node) => node.style.left);
	const cdp = await page.context().newCDPSession(page);
	try {
		// Grab slightly off center and drift downward while scrubbing. Neither
		// should move the handle away from the finger or lose pointer capture.
		const grabOffset = 4;
		await cdp.send("Input.dispatchTouchEvent", {
			type: "touchStart", touchPoints: [{ x: initialX + grabOffset, y }],
		});
		for (const [index, expectedX] of [
			initialX + 10, seam - 8, seam, seam + 8, end, seam, start, end,
		].entries()) {
			const x = expectedX + (expectedX === start ? -6 : expectedX === end ? 6 : grabOffset);
			await cdp.send("Input.dispatchTouchEvent", {
				type: "touchMove", touchPoints: [{ x, y: y + 12 + index * 5 }],
			});
			await page.evaluate(() => new Promise((resolve) =>
				requestAnimationFrame(() => requestAnimationFrame(resolve))));
			const actual = (await line.boundingBox())?.x;
			assert(actual !== undefined && Math.abs(actual - expectedX) <= 2,
				`Drag step ${index}: expected the playhead at ${expectedX.toFixed(1)}px, got ${actual?.toFixed(1)}px`);
		}
		await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
		await page.waitForFunction(() => document.querySelector(".cut-time strong")?.textContent === "0:06.0");
		assert(Math.abs((await line.boundingBox()).x - end) <= 2, "Release must commit the timeline endpoint");
		assert.equal(await line.evaluate((node) => node.style.left), anchor,
			"Moving the playhead must not change the layout anchor");
		await dragTouch(cdp, end, y, start - 6);
		await page.waitForFunction(() => document.querySelector(".cut-time strong")?.textContent === "0:00.0");
		assert(Math.abs((await line.boundingBox()).x - start) <= 2,
			"A quick reverse drag and release must commit the start");
	} finally {
		await cdp.detach();
	}
}

async function watchOverlays(page) {
	await page.evaluate(() => {
		window.__cutRoomTest.animations = [];
		document.addEventListener("animationstart", (event) => {
			if (!["cut-overlay-fade", "cut-panel-arrive"].includes(event.animationName)) return;
			const animation = event.target.getAnimations().find(({ animationName }) => animationName === event.animationName);
			if (!animation) return;
			window.__cutRoomTest.animations.push({
				name: event.animationName,
				duration: animation.effect.getTiming().duration,
				direction: animation.effect.getTiming().direction,
				properties: Object.keys(animation.effect.getKeyframes()[0]).filter(
					(key) => !["offset", "computedOffset", "easing", "composite"].includes(key),
				),
			});
		});
	});
}

try {
	await new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error("Renderer server did not listen")), 6000);
		server.once("error", reject);
		server.once("exit", (code) => reject(new Error(`Renderer server exited: ${code}`)));
		server.stdout.on("data", (chunk) => {
			if (String(chunk).includes("listening")) {
				clearTimeout(timer);
				resolve();
			}
		});
	});
	await mkdir(out, { recursive: true });
	const shim = await readFile(join(root, "bridge", "bridge.js"), "utf8");
	const androidAdapter = await readFile(join(root, "bridge", "android.js"), "utf8");
	const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHROMIUM_CHANNEL || "chrome" });
	try {
		const regressions = [];
		for (const [width, height] of [[360, 720], [390, 790], [430, 850]]) {
			const page = await browser.newPage({
				viewport: { width, height }, hasTouch: true, isMobile: true, reducedMotion: "no-preference",
			});
			page.setDefaultTimeout(12_000);
			const errors = [];
			page.on("pageerror", (error) => errors.push(error.message));
			await page.route("**/__bridge/bridge.js", (route) => route.fulfill({
				contentType: "application/javascript",
				body: `${shim}\n(() => {${testHost}})();\n${androidAdapter}`,
			}));
			await page.goto(`${origin}/?windowType=editor`);
			const home = page.locator(".dashboard-surface.cut-dashboard");
			await home.getByRole("heading", { name: "Project library" }).waitFor();
			await page.waitForTimeout(1000);
			assert.equal(await page.locator("[data-tapswoop-onboarding]").count(), 0, "Tour should be marked seen");
			const record = home.getByRole("button", { name: "New recording" });
			const importButton = home.getByRole("button", { name: "Import", exact: true });
			const tabs = home.getByRole("group", { name: "Library", exact: true });
			for (const [button, label] of [[record, "Record"], [importButton, "Import"]]) {
				await withinViewport(page, button, label);
				assert((await button.boundingBox()).height >= 44);
				assert((await button.boundingBox()).y < (await tabs.boundingBox()).y);
			}
			const face = record.locator(".cut-record-face");
			assert.equal((await face.boundingBox()).width, 50);
			assert.equal(await face.evaluate((node) => getComputedStyle(node).outlineWidth), "2px");
			assert.equal(await face.evaluate((node) => getComputedStyle(node).transitionDuration.split(", ")[0]), "0.3s");
			await home.getByText("Metro walkthrough", { exact: true }).waitFor();
			await home.locator(".cut-project-row img").first().waitFor();
			const search = home.getByRole("searchbox", { name: "Find in library" });
			await search.fill("Settings");
			await home.getByText("Settings guide", { exact: true }).waitFor();
			assert.equal(await home.getByText("Metro walkthrough", { exact: true }).count(), 0,
				`search=${await search.inputValue()} seen=${await page.evaluate(() => localStorage.getItem("setting:recordly.tapswoop.onboarding.v1.seen"))}`);
			await watchOverlays(page);
			const reduced = width === 360;
			if (reduced) await page.emulateMedia({ reducedMotion: "reduce" });
			const settingsButton = home.getByRole("button", { name: "Settings", exact: true });
			await settingsButton.tap();
			const settings = page.getByRole("dialog", { name: "Settings", exact: true });
			await settings.getByRole("radio", { name: "System", exact: true }).waitFor();
			assert.equal(await settings.evaluate((node) => getComputedStyle(node.closest(".cut-motion-overlay")).backdropFilter), "none");
			assert.equal(await settings.locator(".app-settings-body").evaluate((node) => getComputedStyle(node).paddingInlineStart), "16px");
			await settings.getByRole("tab", { name: "Motion", exact: true }).tap();
			await settings.locator('[role="tabpanel"][data-settings-section="motion"]').waitFor();
			await settings.getByRole("tab", { name: "General", exact: true }).tap();
			await page.screenshot({ path: join(out, `settings-${width}.png`), animations: "disabled" });
			await settings.getByRole("button", { name: "Close", exact: true }).tap();
			await settings.waitFor({ state: "detached" });
			assert.equal(await search.inputValue(), "Settings");
			const animations = await page.evaluate(() => window.__cutRoomTest.animations);
			assert(animations.some(({ name, duration }) => name === "cut-overlay-fade" && duration === (reduced ? 100 : 220)));
			assert(animations.some(({ name, duration, direction }) => name === "cut-overlay-fade" && direction === "reverse" && duration === (reduced ? 80 : 150)));
			assert.equal(animations.some(({ name, direction }) => name === "cut-panel-arrive" && direction === "reverse"), !reduced);
			assert(animations.every(({ properties }) => properties.every((property) => property === "opacity" || property === "transform")));
			if (reduced) await page.emulateMedia({ reducedMotion: "no-preference" });
			await search.fill("");
			await home.getByRole("button", { name: "Recordings", exact: true }).tap();
			await home.getByText("Clock tutorial.mp4", { exact: true }).waitFor();
			await home.getByRole("button", { name: "Projects", exact: true }).tap();
			await home.getByText("Metro walkthrough", { exact: true }).waitFor();
			await page.screenshot({ path: join(out, `home-${width}.png`) });
			if (width === 390) {
				await home.getByRole("button", { name: "Library navigation", exact: true }).tap();
				const drawer = page.getByRole("dialog", { name: "Library navigation", exact: true });
				await drawer.getByRole("heading", { name: "Library", exact: true }).waitFor();
				await drawer.getByRole("button", { name: "Settings", exact: true }).tap();
				await settings.getByRole("tab", { name: "General" }).waitFor();
				await page.keyboard.press("Escape");
				await settings.waitFor({ state: "hidden" });
				const options = home.getByRole("button", { name: "Options for Metro walkthrough" });
				await options.tap();
				const menu = page.getByRole("menu", { name: "Options for Metro walkthrough", exact: true });
				await menu.getByRole("menuitem", { name: "Rename", exact: true }).waitFor();
				await page.keyboard.press("Escape");
				await menu.waitFor({ state: "hidden" });
				await home.getByRole("button", { name: "Open Metro walkthrough", exact: true }).tap();
				await home.waitFor({ state: "hidden" });
				assert.equal(await page.evaluate(() => window.__cutRoomTest.open), 1);
				await page.reload();
				await home.getByRole("heading", { name: "Project library" }).waitFor();
			}
			const importBefore = await importButton.boundingBox();
			await face.evaluate((node) => {
				node.addEventListener("transitionrun", (event) => {
					if (event.propertyName !== "width" || event.target !== node) return;
					const widthTransition = node.getAnimations().find((animation) => animation.transitionProperty === "width");
					const end = widthTransition?.effect?.getKeyframes().at(-1)?.width;
					if (end === "140px") {
						const { x, y, width, height } = document.querySelector(".cut-import").getBoundingClientRect();
						window.__cutRoomTest.reveal = { x, y, width, height };
					}
				});
			});
			await record.tap();
			await page.waitForFunction(() => window.__cutRoomTest.record === 1);
			assert.deepEqual(await page.evaluate(() => window.__cutRoomTest.reveal), importBefore);
			assert.deepEqual(await importButton.boundingBox(), importBefore);
			await importButton.tap();
			await home.waitFor({ state: "hidden" });
			assert.equal(await page.evaluate(() => window.__cutRoomTest.picker), 1);
			const editor = page.locator(".cut-editor");
			const screen = editor.locator('[data-timeline-row="row-clip"]');
			await screen.waitFor();
			const zoom = editor.locator('[data-timeline-row="row-zoom"]');
			assert.equal(await screen.locator(".cut-track-label").textContent(), "Screen");
			assert.equal(await zoom.locator(".cut-track-label").textContent(), "Zoom");
			const row = screen.locator(".cut-track-body");
			const [box, zoomBox] = await Promise.all([row.boundingBox(), zoom.boundingBox()]);
			assert(box && zoomBox && box.y < zoomBox.y);
			await withinViewport(page, editor.getByRole("button", { name: "Clips", exact: true }), "Clips");
			const editorHeader = editor.locator(".editor-header");
			assert.equal((await editorHeader.boundingBox())?.height, 44, "Use one compact editor header row");
			for (const name of ["Home", "Rename project", "Export"]) {
				const button = editorHeader.getByRole("button", { name, exact: true });
				const hit = await button.boundingBox();
				assert(hit && hit.width >= 44 && hit.height >= 44, `${name} must keep a 44px touch target: ${JSON.stringify(hit)}`);
				await withinViewport(page, button, name);
			}
			assert.equal(await editorHeader.getByText("Source aspect", { exact: true }).count(), 0,
				"The existing aspect picker does not need a duplicate label in the header");
			const [nameButton, exportButton, exportLabel] = await Promise.all([
				editorHeader.getByRole("button", { name: "Rename project" }).boundingBox(),
				editorHeader.getByRole("button", { name: "Export", exact: true }).boundingBox(),
				editorHeader.locator(".cut-export-label").boundingBox(),
			]);
			assert(nameButton && exportButton && nameButton.x + nameButton.width + 7 <= exportButton.x,
				"The project name needs a gap before Export");
			assert(exportButton && exportLabel && exportButton.width <= 72 && exportLabel.height < exportButton.height,
				"Export should look compact without shrinking its touch target");
			const format = editor.locator(".cut-format-tools");
			const [formatRow, aspectButton, cropButton] = await Promise.all([
				format.boundingBox(),
				format.getByRole("button", { name: "Aspect ratio: Auto" }).boundingBox(),
				format.getByRole("button", { name: "Crop", exact: true }).boundingBox(),
			]);
			assert(formatRow && aspectButton && cropButton && Math.abs(aspectButton.x - formatRow.x - 4) <= 1 &&
				cropButton.x > aspectButton.x + aspectButton.width && cropButton.x < formatRow.x + formatRow.width / 2,
				"Aspect ratio and Crop should sit together at the start of the preview toolbar");
			const railButtons = await editor.locator(".cut-tool-rail button").all();
			assert.equal(railButtons.length, 5);
			for (const railButton of railButtons) {
				const hit = await railButton.boundingBox();
				assert(hit && hit.width >= 44 && hit.height >= 44, "Each rail tool needs a full touch target");
			}
			await withinViewport(page, editor.getByRole("button", { name: "Fit preview" }), "Fit preview");
			await editor.locator('.editor-preview-frame canvas:not([aria-hidden="true"])').waitFor({ timeout: 12000 });
			assert(await editor.getByRole("button", { name: "Split here" }).isDisabled(),
				"Split here must be disabled when the playhead is at the start of the clip");
			await page.getByText("Media imported", { exact: true }).waitFor({ state: "hidden" });
			const layout = await page.evaluate(() => {
				const rect = (node) => node ? node.getBoundingClientRect() : null;
				const scroll = document.querySelector('[data-testid="timeline-scroll"]');
				const panel = document.querySelector("[data-timeline-panel]");
				const title = document.querySelector(".cut-editor-identity button > span");
				const clip = document.querySelector('[data-timeline-item][data-variant="clip"]');
				const playhead = document.querySelector('[data-testid="timeline-playhead"] > div');
				return {
					scrollWidth: scroll?.scrollWidth,
					clientWidth: scroll?.clientWidth,
					panelHeight: panel ? rect(panel).height : 0,
					titleWhiteSpace: title ? getComputedStyle(title).whiteSpace : null,
					clipLeft: clip ? rect(clip).left : null,
					playheadLeft: playhead ? rect(playhead).left : null,
					toolRailBottom: rect(document.querySelector(".cut-tool-rail"))?.bottom,
					viewportHeight: innerHeight,
				};
			});
			assert(layout.scrollWidth <= layout.clientWidth + 1, `Timeline overflows horizontally: ${JSON.stringify(layout)}`);
			assert(layout.panelHeight < 160, `Timeline leaves a dead strip: ${JSON.stringify(layout)}`);
			assert.equal(layout.titleWhiteSpace, "nowrap", "Project title must stay on one line");
			assert(Math.abs(layout.clipLeft - layout.playheadLeft) <= 1, `Playhead is not at the clip start: ${JSON.stringify(layout)}`);
			assert(layout.toolRailBottom <= layout.viewportHeight, "Tool rail extends below the viewport");
			await page.screenshot({ path: join(out, `editor-${width}.png`) });
			const ruler = editor.locator(".cut-timeline-ruler");
			const scrub = await ruler.boundingBox();
			assert(scrub);
			const [bubble, tickText] = await Promise.all([
				editor.locator(".timeline-playhead-thumb").last().boundingBox(),
				ruler.locator(".cut-time-tick > span").first().boundingBox(),
			]);
			assert(bubble && tickText && bubble.y + bubble.height <= tickText.y,
				"Playhead timestamp and ruler text must have separate vertical space");
			const time = editor.locator(".cut-time strong");
			if (width === 360) {
				const check = (condition, message) => { if (!condition) regressions.push(message); };
				const cdp = await page.context().newCDPSession(page);
				const cap = await editor.getByTestId("playhead-cap").boundingBox();
				await dragTouch(cdp, cap.x + cap.width / 2, cap.y + cap.height / 2, scrub.x + scrub.width * 0.65);
				check((await time.textContent()) !== "0:00.0", "Dragging the playhead by touch must seek");
				await page.touchscreen.tap(scrub.x + scrub.width * 0.25, scrub.y + 15);
				const tappedTime = await time.textContent();
				await dragTouch(cdp, scrub.x + scrub.width * 0.35, scrub.y + 21, scrub.x + scrub.width * 0.8);
				check((await time.textContent()) !== tappedTime, "Dragging the timeline ruler by touch must seek");
				await cdp.detach();

				await page.setViewportSize({ width: 320, height: 390 });
				await editor.getByRole("button", { name: "Crop", exact: true }).tap();
				const crop = editor.locator("[data-inline-crop]");
				await crop.waitFor();
				await page.screenshot({ path: join(out, "crop-short.png"), animations: "disabled" });
				const done = await crop.getByRole("button", { name: "Done", exact: true }).boundingBox();
				const cropBounds = await crop.boundingBox();
				const cropSurface = await crop.getByTestId("crop-surface").boundingBox();
				check(done && cropBounds &&
					done.x + done.width <= cropBounds.x + cropBounds.width &&
					done.y + done.height <= cropBounds.y + cropBounds.height &&
					cropSurface && cropSurface.height >= 96,
					"Crop actions and footage must fit on a short viewport");
				const selection = crop.getByTestId("crop-selection");
				const initial = await selection.boundingBox();
				assert(initial && initial.width > 100);
				const cropCdp = await page.context().newCDPSession(page);
				await dragTouch(cropCdp, initial.x + initial.width - 1, initial.y + initial.height / 2,
					initial.x + initial.width * 0.7);
				await cropCdp.detach();
				assert((await selection.boundingBox()).width < initial.width - 25, "Crop edge must respond to a finger drag");
				const back = () => page.evaluate(() => window.__recordlyDismissCutRoomOverlay());
				await crop.getByRole("button", { name: "Aspect ratio", exact: true }).tap();
				const ratio = page.getByRole("dialog", { name: "Aspect ratio", exact: true });
				await ratio.waitFor();
				assert(await back(), "Android Back must close the crop ratio dialog");
				await ratio.waitFor({ state: "detached" });
				assert(await crop.isVisible(), "Back must keep the crop open when dismissing its ratio dialog");
				assert(await back(), "Android Back must cancel the crop");
				await crop.waitFor({ state: "detached" });
				await page.setViewportSize({ width, height });
				await editor.getByRole("button", { name: "Crop", exact: true }).tap();
				await crop.waitFor();
				assert.equal(await selection.evaluate((node) => node.style.width), "100%",
					"Canceling the crop must restore the original crop geometry");
				await crop.getByRole("button", { name: "Done", exact: true }).tap();
				await crop.waitFor({ state: "detached" });
				await editor.getByRole("button", { name: "Aspect ratio: Auto", exact: true }).tap();
				await ratio.waitFor();
				const pickerBody = ratio.locator(".aspect-ratio-picker__body");
				const coverShape = pickerBody.locator(".aspect-ratio-picker__option").filter({ hasText: "3:1" })
					.locator(".aspect-ratio-picker__shape");
				const cover = await coverShape.boundingBox();
				assert(cover && Math.abs(cover.width / cover.height - 3) < 0.1, "The 3:1 preview must be drawn at 3:1");
				const portraitPreset = pickerBody.locator(".aspect-ratio-picker__groups > .aspect-ratio-picker__grid .aspect-ratio-picker__option").last();
				assert.equal((await portraitPreset.textContent())?.trim(), "9:16");
				const [pickerBounds, portraitBounds] = await Promise.all([pickerBody.boundingBox(), portraitPreset.boundingBox()]);
				assert(pickerBounds && portraitBounds &&
					portraitBounds.y + portraitBounds.height <= pickerBounds.y + pickerBounds.height,
					"All standard frame ratios must be visible in the phone-height picker before scrolling");
				await page.screenshot({ path: join(out, `aspect-${width}.png`), animations: "disabled" });
				assert(await back(), "Android Back must close the aspect-ratio picker");
				await ratio.waitFor({ state: "detached" });
				await editor.getByRole("button", { name: "Rename project", exact: true }).tap();
				const projectName = editor.getByRole("textbox", { name: "Project name", exact: true });
				await projectName.fill("Unsaved draft");
				assert(await back(), "Android Back must cancel inline rename");
				await projectName.waitFor({ state: "detached" });
				assert.notEqual(await editor.getByRole("button", { name: "Rename project" }).textContent(), "Unsaved draft");
			}
			const startTime = await time.textContent();
			await page.touchscreen.tap(scrub.x + scrub.width * 0.55, scrub.y + 14);
			await page.waitForFunction((before) => document.querySelector(".cut-time strong")?.textContent !== before, startTime);
			const pausedTime = await time.textContent();
			await editor.getByRole("button", { name: "Play", exact: true }).tap();
			await page.waitForFunction((before) => document.querySelector(".cut-time strong")?.textContent !== before, pausedTime);
			await editor.getByRole("button", { name: "Pause", exact: true }).tap();
			await editor.getByRole("button", { name: "Split here" }).tap();
			await screen.locator('[data-timeline-item][data-variant="clip"]').nth(1).waitFor();
			if (width === 360) await verifyPlayheadTracking(page, editor, screen, scrub);
			await editor.getByRole("button", { name: "Undo", exact: true }).tap();
			await screen.locator('[data-timeline-item][data-variant="clip"]').nth(1).waitFor({ state: "detached" });
			const zoomLane = await zoom.locator(".cut-track-body").boundingBox();
			assert(zoomLane);
			const beforeLaneTap = await time.textContent();
			await page.touchscreen.tap(zoomLane.x + zoomLane.width * 0.25, zoomLane.y + zoomLane.height / 2);
			assert.equal(await zoom.locator('[data-timeline-item][data-variant="zoom"]').count(), 0,
				"Touching the empty Zoom lane to scrub must not create a centered zoom");
			await page.waitForFunction((before) => document.querySelector(".cut-time strong")?.textContent !== before, beforeLaneTap);
			await editor.getByRole("button", { name: "Zoom", exact: true }).tap();
			await editor.getByRole("button", { name: "Add zoom", exact: true }).tap();
			const zoomBlock = zoom.locator('[data-timeline-item][data-variant="zoom"] .timeline-block').first();
			await zoomBlock.waitFor();
			assert.equal(await zoomBlock.evaluate((node) => getComputedStyle(node).backgroundImage), "none",
				"Phone zoom blocks must use their solid presentation");
			await page.screenshot({ path: join(out, `editor-${width}.png`) });
			const preview = editor.locator(".cut-preview-mat");
			const previewBounds = await preview.boundingBox();
			const dockBounds = await editor.locator(".cut-edit-dock").boundingBox();
			const checkInlinePanel = async (name) => {
				const panel = editor.locator(".cut-inline-inspector").and(editor.getByRole("region", { name, exact: true }));
				await panel.waitFor();
				assert.deepEqual(await panel.boundingBox(), dockBounds, `${name} should replace only the timeline and its actions`);
				assert.deepEqual(await preview.boundingBox(), previewBounds, `${name} moved or resized the preview`);
				assert(await editor.getByTestId("timeline-scroll").isHidden(), "Hide the timeline while inspecting, without unmounting");
				assert.equal(await page.locator(".cut-motion-overlay:visible").count(), 0, `${name} must not dim the preview`);
				assert.equal(await preview.evaluate((node) => {
					const { x, y, width, height } = node.getBoundingClientRect();
					return node.contains(document.elementFromPoint(x + width / 2, y + height / 2));
				}), true, "The preview must remain reachable");
				return panel;
			};
			await editor.getByRole("button", { name: "Settings", exact: true }).tap();
			const zoomPanel = await checkInlinePanel("Zoom");
			await zoomPanel.getByRole("button", { name: "Close", exact: true }).tap();
			await editor.getByTestId("timeline-scroll").waitFor();
			await editor.getByRole("button", { name: "Clips", exact: true }).tap();
			const clips = await checkInlinePanel("Clips");
			await clips.locator("[data-recording-library] [data-recording-path]").first().waitFor();
			// Switching directly to another tool shouldn't require dismissing a sheet.
			await editor.getByRole("button", { name: "Look", exact: true }).tap();
			await clips.waitFor({ state: "hidden" });
			const look = await checkInlinePanel("Look");
			const frame = look.getByRole("button", { name: "Frame", exact: true });
			const background = look.getByRole("button", { name: "Background", exact: true });
			assert.equal(await frame.getAttribute("aria-pressed"), "true", "Look should open on Frame");
			await withinViewport(page, background, "Background tab");
			if (width >= 360) {
				const tabs = look.locator(".cut-inspector-tabs");
				assert(await tabs.evaluate((node) => node.scrollWidth <= node.clientWidth + 1),
					"Every Look tab should be readable without sideways scrolling");
			}
			const scroller = look.locator(".cut-inspector-scroll");
			const scrollBox = await scroller.boundingBox();
			const padding = look.getByRole("slider", { name: "Padding", exact: true });
			const radius = look.getByRole("slider", { name: "Corner radius", exact: true });
			const labels = await look.locator(".cut-frame-setting-label").allTextContents();
			assert.deepEqual(labels.slice(0, 4), ["Padding", "Corner radius", "Shadow", "Blur"],
				"Frame settings must stack in reading order");
			const [paddingHit, radiusHit] = await Promise.all([padding.boundingBox(), radius.boundingBox()]);
			assert(paddingHit && scrollBox && paddingHit.height >= 44 &&
				paddingHit.y >= scrollBox.y && paddingHit.y + paddingHit.height <= scrollBox.y + scrollBox.height,
				`Padding must be usable on opening Look: ${JSON.stringify({ paddingHit, scrollBox })}`);
			assert(radiusHit && paddingHit && radiusHit.y >= paddingHit.y + paddingHit.height,
				"Corner radius must sit below Padding");
			if (!scrollBox || radiusHit.y + radiusHit.height > scrollBox.y + scrollBox.height) {
				await radius.scrollIntoViewIfNeeded();
				const scrolled = await radius.boundingBox();
				assert(scrolled && scrolled.y >= scrollBox.y && scrolled.y + scrolled.height <= scrollBox.y + scrollBox.height,
					"Corner radius must be reachable by scrolling");
				await scroller.evaluate((node) => node.scrollTo(0, 0));
			}
			assert.equal(await scroller.evaluate((node) => node.scrollTop), 0);
			await page.screenshot({ path: join(out, `frame-controls-${width}.png`), animations: "disabled" });
			await scroller.evaluate((node) => node.scrollTo(0, node.scrollHeight));
			const removeBackground = look.getByRole("switch", { name: "Remove background" });
			await removeBackground.waitFor();
			assert(await removeBackground.isVisible(), "Remove background should stay reachable inside the panel");
			await scroller.evaluate((node) => node.scrollTo(0, 0));
			if (width === 360) {
				const beforePadding = await preview.screenshot({ animations: "disabled" });
				const initialPadding = await padding.getAttribute("aria-valuenow");
				await padding.press(initialPadding === "100" ? "Home" : "End");
				assert.notEqual(await padding.getAttribute("aria-valuenow"), initialPadding);
				assert(await look.isVisible(), "Frame changes must not close Look");
				assert(!beforePadding.equals(await preview.screenshot({ animations: "disabled" })), "Padding must update the preview while editing");
				const initialRadius = await radius.getAttribute("aria-valuenow");
				await radius.press(initialRadius === "50" ? "Home" : "End");
				assert.notEqual(await radius.getAttribute("aria-valuenow"), initialRadius);
				await look.getByRole("button", { name: "Advanced", exact: true }).tap();
				const link = look.getByRole("switch", { name: "Link padding sides" });
				const linkBox = await link.boundingBox();
				assert(linkBox && scrollBox && linkBox.y + linkBox.height <= scrollBox.y + scrollBox.height,
					"Advanced must reveal the padding link without scrolling");
				await link.press("Space");
				await look.getByRole("slider", { name: "Top", exact: true }).waitFor();
				await link.press("Space");
				await padding.waitFor();
			}
			await background.tap();
			assert.equal(await background.getAttribute("aria-pressed"), "true");
			await radius.waitFor({ state: "detached" });
			if (width === 360) {
				const choices = look.locator('.wallpaper-tile[aria-pressed="false"]:has(img)');
				await choices.first().waitFor();
				const wallpaper = await choices.first().getAttribute("aria-label");
				const before = await preview.screenshot({ animations: "disabled" });
				const tile = look.getByRole("button", { name: wallpaper, exact: true });
				await tile.tap();
				await page.waitForFunction((name) => [...document.querySelectorAll('.wallpaper-tile[aria-pressed="true"]')]
					.some((node) => node.getAttribute("aria-label") === name), wallpaper);
				await page.waitForTimeout(450);
				assert(!before.equals(await preview.screenshot({ animations: "disabled" })), "The wallpaper edit should render with Look still open");
				await page.screenshot({ path: join(out, "inline-look-360.png"), animations: "disabled" });
				const beforePlay = await time.textContent();
				await editor.getByRole("button", { name: "Play", exact: true }).tap();
				await page.waitForFunction((before) => document.querySelector(".cut-time strong")?.textContent !== before, beforePlay);
				await editor.getByRole("button", { name: "Pause", exact: true }).tap();
				assert(await look.isVisible(), "Playback must remain available with Look open");
				await editor.getByRole("button", { name: "Crop", exact: true }).tap();
				await editor.locator("[data-inline-crop]").waitFor();
				assert(await page.evaluate(() => window.__recordlyDismissCutRoomOverlay()), "Back should close Crop before the editor panel");
				await editor.locator("[data-inline-crop]").waitFor({ state: "detached" });
				await checkInlinePanel("Look");
			}
			await frame.tap();
			await radius.waitFor();
			assert.equal(await scroller.evaluate((node) => node.scrollTop), 0, "Switching back to Frame must return to the top");
			await editor.getByRole("button", { name: "Audio", exact: true }).tap();
			const audio = await checkInlinePanel("Audio");
			const volume = audio.getByRole("slider", { name: "Preview volume" });
			await volume.fill("0.5");
			assert(await page.evaluate(() => window.__recordlyDismissCutRoomOverlay()), "Android Back should restore the timeline");
			await audio.waitFor({ state: "hidden" });
			await editor.getByTestId("timeline-scroll").waitFor();
			await editor.getByRole("button", { name: "Audio", exact: true }).tap();
			assert.equal(await volume.inputValue(), "0.5");
			await editor.getByRole("button", { name: "Trim", exact: true }).tap();
			await audio.waitFor({ state: "hidden" });
			await editor.getByTestId("timeline-scroll").waitFor();
			await screen.locator('[data-timeline-item][data-variant="clip"]').first().tap();
			await editor.getByRole("button", { name: "Settings", exact: true }).tap();
			const trimPanel = await checkInlinePanel("Trim");
			await trimPanel.getByRole("button", { name: "Close", exact: true }).tap();
			await editor.getByTestId("timeline-scroll").waitFor();
			await editor.getByRole("button", { name: "Export", exact: true }).tap();
			await page.getByRole("row", { name: "Save to device" }).waitFor();
			const destinationHeight = await page.getByRole("row", { name: "Save to device" }).evaluate((node) => node.offsetHeight);
			assert(destinationHeight >= 44 && destinationHeight <= 56,
				`Expected a compact, touchable local-save row; got ${destinationHeight}px`);
			assert.equal(await page.getByRole("row", { name: "Create share link" }).count(), 0);
			const menu = page.locator(".publish-menu");
			assert.notEqual(await menu.evaluate((node) => getComputedStyle(node).backgroundColor), "rgba(0, 0, 0, 0)",
				"Export menu must have an opaque surface");
			await withinViewport(page, page.getByRole("button", { name: "Export Video" }), "Export Video");
			assert(await page.evaluate(() => window.__recordlyDismissCutRoomOverlay()), "Android Back must close Export");
			await menu.waitFor({ state: "detached" });
			assert.deepEqual(errors, []);
			await page.close();
			console.log(`${width} × ${height}: Home, library search, record/import, playback, scrub, split/undo, zoom, clips, look, audio, and export controls passed`);
		}
		assert.deepEqual(regressions, []);
	} finally { await browser.close(); }
} finally { server.kill(); }
