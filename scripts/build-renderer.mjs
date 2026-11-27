/**
 * Builds the Recordly renderer for Android and installs it into the APK assets.
 *
 *   node scripts/build-renderer.mjs [--skip-build]
 *
 * Upstream ships a renderer-only build mode; we use it rather than forking
 * vite.config.ts, so upstream merges stay clean.
 */

import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = resolve(fileURLToPath(new URL(".", import.meta.url)));
const PROJECT_ROOT = resolve(HERE, "..");
const UPSTREAM = join(PROJECT_ROOT, "recordly-upstream");
const DIST = join(UPSTREAM, "dist");
const TARGET = join(PROJECT_ROOT, "android", "app", "src", "main", "assets", "renderer");
const BRIDGE_SOURCE = join(PROJECT_ROOT, "bridge", "bridge.js");
const HOST_SOURCE = join(PROJECT_ROOT, "bridge", "android.js");
const STRINGS_SOURCE = join(PROJECT_ROOT, "android", "app", "src", "main", "res", "values", "strings.xml");

const skipBuild = process.argv.includes("--skip-build");

function run(command, args, cwd) {
	const strings = readFileSync(STRINGS_SOURCE, "utf8");
	const appName = strings.match(/<string name="app_name">([A-Za-z0-9 ]+)<\/string>/)?.[1].trim();
	if (!appName) throw new Error(`Missing Android app_name in ${STRINGS_SOURCE}`);
	console.log(`> ${command} ${args.join(" ")}`);
	execFileSync(command, args, {
		cwd,
		stdio: "inherit",
		env: { ...process.env, RECORDLY_RENDERER_ONLY: "1", RECORDLY_ANDROID_DISPLAY_NAME: appName },
	});
}

function dirSize(dir) {
	let total = 0;
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const full = join(dir, entry.name);
		total += entry.isDirectory() ? dirSize(full) : statSync(full).size;
	}
	return total;
}

if (!skipBuild) {
	// The wrapper lives beside upstream's Vite config so its dependencies resolve
	// from upstream's node_modules on Windows as well as Unix.
	run(
		process.execPath,
		[join(UPSTREAM, "node_modules", "vite", "bin", "vite.js"), "build", "--mode", "ui", "--config", "vite.android.config.mjs", "--configLoader", "runner"],
		UPSTREAM,
	);
}

if (!existsSync(DIST)) {
	console.error(`error: ${DIST} not found — run without --skip-build first.`);
	process.exit(1);
}

// Replace, never merge: stale hashed chunks would otherwise accumulate forever.
rmSync(TARGET, { recursive: true, force: true });
mkdirSync(TARGET, { recursive: true });
cpSync(DIST, TARGET, { recursive: true });

// NOT `__bridge`: AAPT silently drops asset entries whose names begin with an
// underscore, so the folder never reaches the APK and the install step fails.
const BRIDGE_DIR = "bridge-host";

mkdirSync(join(TARGET, BRIDGE_DIR), { recursive: true });
cpSync(BRIDGE_SOURCE, join(TARGET, BRIDGE_DIR, "bridge.js"));
cpSync(HOST_SOURCE, join(TARGET, BRIDGE_DIR, "android.js"));

// Prove the folder survives packaging instead of discovering it on device.
if (!existsSync(join(TARGET, BRIDGE_DIR, "android.js")) || !existsSync(join(TARGET, BRIDGE_DIR, "bridge.js"))) {
	console.error("error: bridge sources did not land in the asset tree");
	process.exit(1);
}

const mb = (dirSize(TARGET) / (1024 * 1024)).toFixed(1);
console.log(`\nrenderer installed: ${TARGET}`);
console.log(`bridge installed  : ${join(TARGET, BRIDGE_DIR)}`);
console.log(`assets size       : ${mb} MB`);
