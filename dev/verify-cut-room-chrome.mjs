/** Check system bars, Settings and Android Back on a running TapSwoop build. */
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { appProcessId, execOut, forward, shell } from "./adb.mjs";

const pkg = "app.tapswoop.android.debug";
const out = fileURLToPath(new URL("./verification/cut-room-polish/", import.meta.url));
const pid = await appProcessId(pkg);
assert(pid, "Open TapSwoop Home on the selected device first");
await forward(9222, `localabstract:webview_devtools_remote_${pid}`);
await mkdir(out, { recursive: true });
const { chromium } = createRequire(import.meta.url)("../recordly-upstream/node_modules/playwright");
const browser = await chromium.connectOverCDP("http://127.0.0.1:9222");

async function screenshot(label) {
	const png = await execOut(["screencap", "-p"]);
	assert.equal(png.toString("ascii", 12, 16), "IHDR");
	const path = join(out, `${process.env.RECORDLY_DEVICE || "emulator-5554"}-${label}.png`);
	await writeFile(path, png);
	return { path, width: png.readUInt32BE(16), height: png.readUInt32BE(20) };
}

async function verifyBars(theme) {
	const { path, width, height } = await screenshot(`home-${theme}`);
	const expected = theme === "light" ? [231, 238, 240] : [24, 35, 39];
	const pixels = execFileSync("ffmpeg", [
		"-v", "error", "-i", path, "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
	], { maxBuffer: width * height * 3 + 4_096 });
	const read = (x, y) => [...pixels.subarray((width * y + x) * 3, (width * y + x) * 3 + 3)];
	for (const [area, y] of [["status bar", 12], ["navigation bar", height - 12]]) {
		const rgb = read(Math.round(width * .23), y);
		assert(rgb.every((channel, index) => Math.abs(channel - expected[index]) <= 3),
			`${theme} ${area}: expected ${expected}, got ${rgb}`);
	}
}

try {
	const page = browser.contexts().flatMap((context) => context.pages())
		.find((candidate) => candidate.url().includes("windowType=editor"));
	assert(page);
	page.setDefaultTimeout(12_000);
	const home = page.locator(".cut-dashboard.dashboard-surface");
	const settings = page.getByRole("dialog", { name: "Settings", exact: true });
	const setting = (choice) => settings.getByRole("radio", { name: choice, exact: true });
	const open = async () => {
		await home.getByRole("button", { name: "Settings", exact: true }).click();
		await settings.waitFor();
	};
	const back = async () => {
		const result = await shell(["input", "keyevent", "KEYCODE_BACK"]);
		assert.equal(result.code, 0, result.stderr);
	};
	const close = async () => {
		await back();
		await settings.waitFor({ state: "detached" });
		await home.getByRole("heading", { name: "Project library", exact: true }).waitFor();
	};
	const choose = async (theme) => {
		await settings.getByText(theme, { exact: true }).click();
		assert(await setting(theme).isChecked());
	};
	await open();
	const original = await settings.locator('.theme-appearance-picker input[type="radio"]:checked').getAttribute("value");
	assert(["light", "dark", "system"].includes(original));
	try {
		await settings.locator(".settings-language-trigger").click();
		const languages = page.getByRole("listbox");
		await languages.waitFor();
		await back();
		await languages.waitFor({ state: "detached" });
		assert(await settings.isVisible(), "Back should close the language list before Settings");
		for (const theme of ["light", "dark"]) {
			await choose(theme[0].toUpperCase() + theme.slice(1));
			assert.equal(await page.locator("html").getAttribute("data-theme"), theme);
			await settings.locator(".cut-theme-choice").first().waitFor();
			await screenshot(`settings-${theme}`);
			await close();
			await verifyBars(theme);
			await open();
		}
	} finally {
		if (!(await settings.isVisible())) await open();
		await choose(original[0].toUpperCase() + original.slice(1));
		await close();
	}
	await home.getByRole("button", { name: "Library navigation", exact: true }).click();
	const drawer = page.getByRole("dialog", { name: "Library navigation", exact: true });
	await drawer.waitFor();
	await back();
	await drawer.waitFor({ state: "detached" });
	console.log("System bars match in light and dark; Android Back closes the language list, Settings and library drawer in order.");
} finally {
	await browser.close();
}
