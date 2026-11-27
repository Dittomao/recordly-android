/** Verify the first-run TapSwoop tour in the packaged Android renderer. */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const root = fileURLToPath(new URL("..", import.meta.url));
const out = join(root, "dev", "verification", "onboarding");
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const port = Number(process.env.ONBOARDING_TEST_PORT || 4488);
const origin = `http://127.0.0.1:${port}`;
const seenKey = "recordly.tapswoop.onboarding.v1.seen";
const server = spawn(process.execPath, ["dev/serve.mjs", "--port", String(port), "--root", "android/app/src/main/assets/renderer"], { cwd: root });
const testHost = `
const api = window.electronAPI;
const media = location.origin + "/fixtures/filmstrip.mp4";
const now = Date.now();
window.__tourTest = { hud: 0, openedSettings: 0, trusted: false, microphone: false };
window.__recordlyHost = {};
api.getCurrentVideoPath = async () => ({ success: true, path: null });
api.listProjectFiles = async () => ({ success: true, entries: [{
  name: "Metro walkthrough", path: "/projects/Metro.recordly", createdAt: now, updatedAt: now,
  thumbnailPath: location.origin + "/fixtures/recording-thumbnail.jpg",
  isCurrent: false, isInProjectsDirectory: true,
}] });
api.listRecordings = async () => ({ success: true, value: [{
  name: "Clock tutorial.mp4", path: "/recordings/Clock tutorial.mp4", url: media,
  bytes: 47000, createdAt: now,
}] });
api.getAccessibilityPermissionStatus = async () => ({ success: true, trusted: window.__tourTest.trusted, prompted: false });
api.requestAccessibilityPermission = async () => {
  window.__tourTest.openedSettings++;
  window.__tourTest.trusted = true;
  return { success: true, trusted: true, prompted: true };
};
api.getMediaPermissionStatus = async () => ({
  success: true, microphone: window.__tourTest.microphone ? "granted" : "denied", camera: "denied",
});
api.requestMediaPermission = async (device) => {
  if (device !== "microphone") return { success: true, granted: false };
  window.__tourTest.microphone = true;
  return { success: true, granted: true };
};
api.showRecordingHud = async () => { window.__tourTest.hud++; return { success: true }; };
`;
const stageNames = ["Record", "Focus", "Cut Room", "Ready"];

async function pageWithinViewport(page, locator, label) {
	const rect = await locator.boundingBox();
	const viewport = page.viewportSize();
	assert(rect && rect.x >= -1 && rect.x + rect.width <= viewport.width + 1 &&
		rect.y >= -1 && rect.y + rect.height <= viewport.height + 1,
	`${label} exceeds the viewport: ${JSON.stringify(rect)}`);
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
	const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHROMIUM_CHANNEL || "chrome" });
	try {
		for (const [width, height] of [[360, 720], [390, 790], [430, 850]]) {
			const page = await browser.newPage({
				viewport: { width, height }, hasTouch: true, isMobile: true, reducedMotion: "no-preference",
			});
			page.setDefaultTimeout(12_000);
			const errors = [];
			page.on("pageerror", (error) => errors.push(error.message));
			await page.addInitScript((key) => {
				if (sessionStorage.getItem("tour-cleared")) return;
				localStorage.removeItem(`setting:${key}`);
				sessionStorage.setItem("tour-cleared", "1");
			}, seenKey);
			await page.route("**/__bridge/bridge.js", (route) => route.fulfill({
				contentType: "application/javascript",
				body: `${shim}\n(() => {${testHost}})();`,
			}));
			await page.goto(`${origin}/?windowType=editor&onboardingPreview=0`);
			const tour = page.locator("[data-tapswoop-onboarding]");
			await tour.waitFor();
			assert.equal(await tour.getByRole("heading", { level: 1 }).count(), 1);
			const primary = tour.getByRole("button", { name: "Get started", exact: true });
			await pageWithinViewport(page, primary, "Get started");
			assert((await primary.boundingBox()).height >= 44);
			assert.equal(await tour.locator(".cut-onboarding-count").textContent(), "1 / 4");
			await page.keyboard.press("Escape");
			await page.waitForTimeout(400);
			assert(await tour.isVisible(), "Escape must not dismiss required onboarding");
			assert.equal(await tour.locator(".cut-onboarding-count").textContent(), "1 / 4");
			for (const [index, name] of stageNames.entries()) {
				assert.equal(await tour.locator(".cut-onboarding-stage").count(), 1);
				assert.equal(await tour.locator(".cut-demo").count(), 1);
				if (index > 0) {
					assert.equal(await tour.locator(".cut-onboarding-count").textContent(), `${index + 1} / 4`);
				}
				await page.screenshot({ path: join(out, `tour-${index + 1}-${width}.png`), animations: "disabled" });
				const overflow = await tour.evaluate((node) => {
					const stage = node.querySelector(".cut-onboarding-scroll");
					return stage ? stage.scrollWidth - stage.clientWidth : 0;
				});
				assert(overflow <= 1, `${name} scrolls horizontally by ${overflow}px`);
				if (index < 3) {
					const next = index === 0 ? "Get started" : "Continue";
					await tour.getByRole("button", { name: next, exact: true }).tap();
					await page.waitForFunction((expected) =>
						document.querySelector(".cut-onboarding-count")?.textContent === expected,
					`${index + 2} / 4`);
				}
			}
			const ready = page.locator(".cut-onboarding-permissions");
			const start = tour.getByRole("button", { name: "Start recording", exact: true });
			const explore = tour.getByRole("button", { name: "Explore library", exact: true });
			await ready.getByText("Tap focus", { exact: true }).waitFor();
			await ready.getByText("Required for automatic zooms", { exact: false }).waitFor();
			await ready.getByText("Confirm", { exact: true }).waitFor();
			assert.equal(await ready.getByText(/Optional/).count(), 0);
			assert.equal(await tour.getByRole("button", { name: "Skip", exact: true }).count(), 0,
				"Onboarding must not offer Skip");
			assert(await start.isDisabled(), "Start recording must wait for required setup");
			assert(await explore.isDisabled(), "Explore library must wait for required setup");
			await ready.getByRole("button", { name: "Allow microphone", exact: true }).tap();
			await ready.getByText("Allowed", { exact: true }).waitFor();
			assert(await start.isDisabled(), "Tap focus is still required");
			assert.equal(await page.evaluate(() => window.__tourTest.microphone), true);
			await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange")));
			await page.waitForTimeout(1000);
			assert.equal(await tour.locator(".cut-onboarding-count").textContent(), "4 / 4",
				"Returning from system settings must not restart the tour");
			await ready.getByRole("button", { name: "Turn on tap focus", exact: true }).tap();
			await ready.getByText("On", { exact: true }).waitFor();
			assert.equal(await page.evaluate(() => window.__tourTest.openedSettings), 1);
			assert(await start.isDisabled(), "Screen sharing confirmation is still required");
			await ready.getByRole("button", { name: "Confirm screen sharing for each take", exact: true }).tap();
			await ready.getByText("Confirmed", { exact: true }).waitFor();
			assert(!(await start.isDisabled()), "Start recording unlocks after all three checks");
			assert(!(await explore.isDisabled()), "Explore library unlocks after all three checks");
			await pageWithinViewport(page, start, "Start recording");
			await pageWithinViewport(page, explore, "Explore library");
			await page.screenshot({ path: join(out, `tour-4-ready-${width}.png`), animations: "disabled" });
			await start.tap();
			await tour.waitFor({ state: "hidden" });
			await page.waitForFunction(() => window.__tourTest.hud === 1);
			assert.equal(await page.evaluate((key) => localStorage.getItem(`setting:${key}`), seenKey), "true");
			await page.reload();
			await page.locator(".cut-dashboard").waitFor();
			await page.waitForTimeout(1100);
			assert.equal(await page.locator("[data-tapswoop-onboarding]").count(), 0, "Tour repeats on reload");
			await page.evaluate(() => window.dispatchEvent(new Event("recordly:tapswoop-onboarding")));
			await tour.waitFor();
			assert.equal(await tour.locator(".cut-onboarding-count").textContent(), "1 / 4");
			assert.equal(await tour.getByRole("button", { name: "Skip", exact: true }).count(), 0);
			await tour.getByRole("button", { name: "Get started", exact: true }).tap();
			await page.waitForFunction(() => document.querySelector(".cut-onboarding-count")?.textContent === "2 / 4");
			await tour.locator("[data-cut-back]").tap();
			await page.waitForFunction(() => document.querySelector(".cut-onboarding-count")?.textContent === "1 / 4");
			await page.reload();
			await page.locator(".cut-dashboard").waitFor();
			await page.waitForTimeout(1100);
			assert.equal(await page.locator("[data-tapswoop-onboarding]").count(), 0);
			const home = page.locator(".cut-dashboard");
			await home.getByRole("button", { name: "Settings", exact: true }).tap();
			const settings = page.getByRole("dialog", { name: "Settings", exact: true });
			await settings.getByRole("tab", { name: "Dev", exact: true }).tap();
			await settings.getByRole("button", { name: "Take the tour", exact: true }).tap();
			await tour.waitFor();
			assert.equal(await tour.locator(".cut-onboarding-count").textContent(), "1 / 4");
			assert.equal(await tour.getByRole("button", { name: "Skip", exact: true }).count(), 0);
			await page.reload();
			await page.locator(".cut-dashboard").waitFor();
			assert.deepEqual(errors, []);
			await page.close();
			console.log(`${width} × ${height}: tour pages, required setup, finish, replay, settings replay and Android Back passed`);
		}
		const reduced = await browser.newPage({
			viewport: { width: 390, height: 790 }, hasTouch: true, isMobile: true, reducedMotion: "reduce",
		});
		reduced.setDefaultTimeout(12_000);
		await reduced.addInitScript((key) => localStorage.removeItem(`setting:${key}`), seenKey);
		await reduced.route("**/__bridge/bridge.js", (route) => route.fulfill({
			contentType: "application/javascript",
			body: `${shim}\n(() => {${testHost}})();`,
		}));
		await reduced.goto(`${origin}/?windowType=editor&onboardingPreview=0`);
		const reducedTour = reduced.locator("[data-tapswoop-onboarding]");
		await reducedTour.waitFor();
		await reducedTour.getByRole("button", { name: "Get started", exact: true }).tap();
		await reducedTour.locator(".cut-demo-tap").waitFor();
		assert.equal(await reducedTour.locator(".cut-demo-tap").evaluate((node) => getComputedStyle(node).animationName), "none");
		await reduced.screenshot({ path: join(out, "tour-reduced-390.png") });
		console.log("Reduced motion removes looped demo motion while keeping the tour readable.");
		await reduced.close();
		const dark = await browser.newPage({
			viewport: { width: 390, height: 790 }, hasTouch: true, isMobile: true, reducedMotion: "no-preference",
		});
		dark.setDefaultTimeout(12_000);
		await dark.addInitScript((key) => {
			if (sessionStorage.getItem("tour-cleared")) return;
			localStorage.removeItem(`setting:${key}`);
			localStorage.setItem("setting:recordly.theme", "dark");
			sessionStorage.setItem("tour-cleared", "1");
		}, seenKey);
		await dark.route("**/__bridge/bridge.js", (route) => route.fulfill({
			contentType: "application/javascript",
			body: `${shim}\n(() => {${testHost}})();`,
		}));
		await dark.goto(`${origin}/?windowType=editor&onboardingPreview=0`);
		const darkTour = dark.locator("[data-tapswoop-onboarding]");
		await darkTour.waitFor();
		assert.equal(await dark.locator("html").getAttribute("data-theme"), "dark");
		assert.equal(await darkTour.evaluate((node) => getComputedStyle(node).backgroundColor), "rgb(24, 35, 39)");
		await dark.screenshot({ path: join(out, "tour-dark-390.png") });
		console.log("Dark theme keeps the tour on the Cut Room palette.");
		await dark.close();
	} finally {
		await browser.close();
	}
} finally {
	server.kill();
}
