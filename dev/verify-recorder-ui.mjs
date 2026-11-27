/**
 * Exercise the packaged Sileo recorder surface in Chromium.
 *
 * The renderer half of the floating setup: the actual Sileo component, the
 * shared Record control, compact arrival, tap-to-expand, the 20 s idle fold,
 * outside-tap fold, Back dismissal, reduced motion and small-phone
 * containment. The Android window (spring entrance, watch-outside-touch,
 * capture handoff) is covered by the emulator check.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir } from "node:fs/promises";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

const root = fileURLToPath(new URL("..", import.meta.url));
const out = join(root, "dev", "verification", "recorder");
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const port = Number(process.env.RECORDER_TEST_PORT || 4489);
const origin = `http://127.0.0.1:${port}`;
const server = spawn(process.execPath, ["dev/serve.mjs", "--port", String(port), "--root", "android/app/src/main/assets/renderer"], { cwd: root });

const nativeShim = `
window.__rec = { resizes: [], start: null, closed: false, ready: false };
window.TapswoopRecorder = {
  ready() {
    window.__rec.ready = true;
  },
  resize(height, pill, expanded) {
    window.__rec.resizes.push({ height, pill, expanded });
  },
  start(microphone) {
    window.__rec.start = { microphone, at: performance.now() };
  },
  close() {
    window.__rec.closed = true;
  },
};
`;

async function wait(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

async function open(browser, width, height, reducedMotion = "no-preference", query = "") {
	const page = await browser.newPage({
		viewport: { width, height }, hasTouch: true, isMobile: true, reducedMotion,
	});
	page.setDefaultTimeout(12_000);
	const errors = [];
	page.on("pageerror", (error) => errors.push(error.message));
	page.on("console", (message) => {
		if (message.type() === "error") errors.push(message.text());
	});
	await page.addInitScript(nativeShim);
	await page.goto(`${origin}/recorder.html${query}`);
	const toast = page.locator("[data-sileo-toast]");
	await toast.waitFor();
	await page.waitForFunction(() => window.__rec.ready === true);
	// The boot pill owns the single entrance; the Sileo swap happens after it.
	await page.waitForFunction(
		() => document.querySelector(".recorder-stage")?.getAttribute("data-booted") === "true",
	);
	assert.equal(await page.locator("#boot").count(), 0, "Boot pill must hand off");
	return { page, toast, errors };
}

/** Native arrival leaves the compact pill; it must not auto-expand. */
async function arrive(page) {
	await page.evaluate(() => window.dispatchEvent(new Event("tapswoop-recorder-arrived")));
	await page.waitForFunction(
		() => document.querySelector(".recorder-stage")?.getAttribute("data-phase") === "setup",
	);
	assert.equal(
		await page.locator("[data-sileo-toast]").getAttribute("data-expanded"),
		"false",
		"Arrival must stay compact",
	);
}

async function expand(page) {
	await page.locator("[data-sileo-header-stack]").tap();
	await page.waitForFunction(
		() => document.querySelector("[data-sileo-toast]")?.getAttribute("data-expanded") === "true",
	);
}

try {
	await new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error("Recorder server did not listen")), 6000);
		server.once("error", reject);
		server.stdout.once("data", () => { clearTimeout(timer); resolve(); });
	});
	await mkdir(out, { recursive: true });
	const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHROMIUM_CHANNEL || "chrome" });
	try {
		for (const [width, height] of [[360, 720], [390, 790], [430, 850]]) {
			const { page, toast, errors } = await open(browser, width, height);
			const tag = `${width}x${height}`;
			try {
				// A freshly opened surface is a pill only.
				assert.equal(await toast.getAttribute("data-expanded"), "false");
				assert((await page.locator("[data-sileo-body]").evaluate((node) => node.getBoundingClientRect().height)) < 1);
				assert.equal(await page.locator("[data-sileo-content]").getAttribute("data-visible"), "false");
				assert.equal(await page.locator("[data-sileo-content]").getAttribute("inert"), "");
				const box = await toast.boundingBox();
				assert(box && box.width <= width - 24 && box.x >= 0 && box.x + box.width <= width + 1,
					`${tag}: pill exceeds the phone width ${JSON.stringify(box)}`);

				// Native arrival: still the compact pill, not an open card.
				await arrive(page);
				assert((await page.locator("[data-sileo-body]").evaluate((node) => node.getBoundingClientRect().height)) < 1,
					`${tag}: arrival body opened by itself`);

				// Tapping the pill is what opens the real Sileo spring.
				await expand(page);
				const samples = [];
				for (let index = 0; index < 8; index++) {
					samples.push(await page.locator("[data-sileo-body]").evaluate((node) => node.getBoundingClientRect().height));
					await wait(70);
				}
				const settled = await page.locator("[data-sileo-body]").evaluate((node) => node.getBoundingClientRect().height);
				assert(settled > 120, `${tag}: expanded body ${settled}`);
				assert(samples[0] < settled && samples.some((value) => value > 0 && value < settled),
					`${tag}: expected a spring, sampled ${samples.join(",")}`);
				await page.waitForFunction(
					() => document.querySelector("[data-sileo-content]")?.getAttribute("data-visible") === "true",
				);

				// All setup controls are reachable, inside the surface, and at
				// least 44px tall where they are ordinary buttons.
				for (const [name, locator] of [
					["Microphone", page.locator(".recorder-mic")],
					["New recording", page.getByRole("button", { name: "New recording" })],
					["Cancel", page.getByRole("button", { name: "Cancel" })],
				]) {
					const target = await locator.boundingBox();
					assert(target, `${tag}: ${name} missing`);
					assert(target.x >= 0 && target.x + target.width <= width + 1, `${tag}: ${name} clipped horizontally`);
					assert(target.height >= 43.5, `${tag}: ${name} is ${target.height}px tall`);
				}
				assert.equal(await page.locator(".recorder-mic").getAttribute("aria-pressed"), "true");
				await page.screenshot({ path: join(out, `recorder-${width}.png`), animations: "disabled" });

				// The whole visible pill is the fold control, including its
				// 8px padding; tap its edge rather than the centre.
				const pillButton = page.locator("[data-sileo-header-stack]");
				const pillBox = await pillButton.boundingBox();
				assert(pillBox && pillBox.height >= 39.5, `${tag}: pill hit area ${pillBox?.height}`);
				await pillButton.tap({ position: { x: 3, y: 3 } });
				await page.waitForFunction(
					() => document.querySelector("[data-sileo-toast]")?.getAttribute("data-expanded") === "false",
				);
				await wait(900);
				const last = await page.evaluate(() => window.__rec.resizes.at(-1));
				assert(last && last.expanded === false, `${tag}: no collapsed resize report`);
				assert(last.height < 120, `${tag}: collapsed window ${last.height}`);

				// Outside touch (native ACTION_OUTSIDE) folds an open body.
				await expand(page);
				await page.evaluate(() => window.dispatchEvent(new Event("tapswoop-recorder-outside")));
				await page.waitForFunction(
					() => document.querySelector("[data-sileo-toast]")?.getAttribute("data-expanded") === "false",
				);

				// Mic is a real toggle; the Record control reveals and starts
				// with the chosen microphone state.
				await expand(page);
				await page.waitForFunction(
					() => document.querySelector("[data-sileo-content]")?.getAttribute("data-visible") === "true",
				);
				await page.locator(".recorder-mic").tap();
				await page.waitForFunction(
					() => document.querySelector(".recorder-mic")?.getAttribute("aria-pressed") === "false",
				);
				assert.match(await page.locator(".recorder-mic").innerText(), /Mic\s+off/);
				const record = page.getByRole("button", { name: "New recording" });
				await record.tap();
				await page.waitForFunction(() => window.__rec.start !== null, null, { timeout: 4000 });
				assert.deepEqual(
					await page.evaluate(() => ({ microphone: window.__rec.start.microphone })),
					{ microphone: false },
				);
				await page.waitForFunction(
					() => document.querySelector("[data-sileo-toast]")?.getAttribute("data-expanded") === "false",
				);
				assert(errors.length === 0, `${tag}: console errors ${errors.join(" | ")}`);

				// Cancel and Back both dismiss through the native close call.
				for (const [label, dismiss] of [
					["Cancel", async () => {
						await expand(page);
						await page.getByRole("button", { name: "Cancel" }).tap();
					}],
					["Back", () => page.evaluate(() => window.dispatchEvent(new Event("tapswoop-recorder-back")))],
				]) {
					await page.reload();
					await page.waitForFunction(() => window.__rec.ready === true);
					await arrive(page);
					await dismiss();
					await page.waitForFunction(() => window.__rec.closed === true, null, { timeout: 3000 });
					assert.equal(await page.evaluate(() => window.__rec.closed), true, `${tag}: ${label} did not close`);
				}
			} finally {
				await page.close();
			}
		}

		// Reduced motion keeps the state change and drops the travel.
		const { page } = await open(browser, 390, 790, "reduce");
		try {
			await arrive(page);
			await expand(page);
			await wait(60);
			const height = await page.locator("[data-sileo-body]").evaluate((node) => node.getBoundingClientRect().height);
			assert(height > 120, `reduced motion body ${height}`);
		} finally {
			await page.close();
		}

		// A compact pill folds an open body after the idle timeout; any touch
		// inside the setup resets that timer. The verifier shortens it.
		const idle = await open(browser, 390, 790, "no-preference", "?idleMs=1200");
		try {
			await arrive(idle.page);
			await expand(idle.page);
			await wait(700);
			assert.equal(await idle.toast.getAttribute("data-expanded"), "true", "Idle timeout fired too early");
			await idle.page.locator(".recorder-mic").tap();
			await idle.page.waitForFunction(
				() => document.querySelector(".recorder-mic")?.getAttribute("aria-pressed") === "false",
			);
			await wait(700);
			assert.equal(await idle.toast.getAttribute("data-expanded"), "true", "Interaction must reset the idle timer");
			await wait(800);
			await idle.page.waitForFunction(
				() => document.querySelector("[data-sileo-toast]")?.getAttribute("data-expanded") === "false",
			);
			await wait(900);
			const collapsed = await idle.page.evaluate(() => window.__rec.resizes.at(-1));
			assert(collapsed && collapsed.expanded === false && collapsed.height < 120,
				`Idle fold did not shrink the window: ${JSON.stringify(collapsed)}`);
			assert(idle.errors.length === 0, `Idle test console errors ${idle.errors.join(" | ")}`);
		} finally {
			await idle.page.close();
		}

		console.log("Recorder UI: compact arrival, tap-to-expand, 20 s idle fold, controls, Back, reduced motion and phone containment pass.");
	} finally {
		await browser.close();
	}
} finally {
	server.kill();
}
