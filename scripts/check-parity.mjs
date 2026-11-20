/**
 * Parity gate for the Electron API seam.
 *
 *   node scripts/check-parity.mjs [--strict] [--json] [--skip-assets]
 *
 * Three files describe the same contract and drift independently:
 *   - electronapi-inventory.csv — the 234 methods of upstream's preload;
 *   - bridge/bridge.js         — what the renderer actually receives;
 *   - NativeBridge.kt          — what Android actually answers.
 *
 * Kotlin gaps are expected: unimplemented methods fall back to the shim's
 * neutral values, so they are reported, not failed. What must fail is structural
 * drift — an inventory name the shim never exposes, an event channel the shim
 * forgets, a bridge/Kotlin method nobody can account for, or a generated asset
 * copy that lags its source. Those are the failures that turn into a renderer
 * TypeError or a silent feature loss.
 *
 * Exit codes: 0 clean (or non-strict), 1 strict mismatch, 2 unreadable input.
 */
import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const ARGS = new Set(process.argv.slice(2));

/**
 * Methods that legitimately exist only on Android. Anything else outside the
 * inventory is drift; add here only with a comment explaining why.
 */
const ANDROID_ONLY = new Set([
	// Per-process loopback capability secret; read once by bridge.js.
	"getBridgeToken",
	// Native microphone enumeration, consumed by bridge/android.js before the
	// renderer asks for device labels.
	"getAndroidMediaDevices",
	// Second half of the wallpaper upload: upstream's preload takes a File;
	// here the bytes arrive over the export channel and this seals the temp file.
	"finishWallpaperImport",
]);

function read(relative) {
	const path = resolve(ROOT, relative);
	if (!existsSync(path)) fail(`missing ${relative}`);
	return readFileSync(path, "utf8");
}

function fail(message) {
	console.error(`parity: ${message}`);
	process.exit(2);
}

/** RFC 4180 enough for this file: quoted fields, doubled quotes, commas. */
function parseCsv(text) {
	const rows = [];
	let row = [];
	let field = "";
	let quoted = false;
	for (let i = 0; i < text.length; i++) {
		const char = text[i];
		if (quoted) {
			if (char === '"' && text[i + 1] === '"') {
				field += '"';
				i++;
			} else if (char === '"') {
				quoted = false;
			} else {
				field += char;
			}
		} else if (char === '"') {
			quoted = true;
		} else if (char === ",") {
			row.push(field);
			field = "";
		} else if (char === "\n") {
			row.push(field);
			rows.push(row);
			row = [];
			field = "";
		} else if (char !== "\r") {
			field += char;
		}
	}
	if (field.length > 0 || row.length > 0) {
		row.push(field);
		rows.push(row);
	}
	return rows.filter(entry => entry.some(value => value.trim() !== ""));
}

function stringArray(source, name) {
	const pattern = new RegExp(`var ${name} = \\[([\\s\\S]*?)\\];`);
	const match = pattern.exec(source);
	if (!match) fail(`bridge.js has no ${name} array`);
	return [...match[1].matchAll(/"([^"]+)"/g)].map(entry => entry[1]);
}

function devDefaultKeys(source) {
	const start = source.indexOf("var DEV_DEFAULTS = {");
	if (start < 0) fail("bridge.js has no DEV_DEFAULTS object");
	const end = source.indexOf("\n\t};", start);
	if (end < 0) fail("bridge.js DEV_DEFAULTS has no closing brace");
	const body = source.slice(start, end);
	return [...body.matchAll(/^\t\t([A-Za-z0-9_]+):/gm)].map(entry => entry[1]);
}

/** The `when (method)` branches of both dispatch functions, not other `when`s. */
function kotlinDispatchNames(source) {
	const names = new Set();
	for (const fn of ["dispatchSync", "dispatch"]) {
		const start = source.indexOf(`private fun ${fn}(`);
		if (start < 0) fail(`NativeBridge.kt has no ${fn} function`);
		const open = source.indexOf("= when (method) {", start);
		if (open < 0) fail(`NativeBridge.kt ${fn} is not a when(method)`);
		// Both functions close with a four-space brace; nested blocks are deeper.
		const close = source.indexOf("\n    }", open);
		if (close < 0) fail(`NativeBridge.kt ${fn} has no closing brace`);
		const body = source.slice(open, close);
		// Dispatch branches sit at exactly eight spaces. A looser pattern also
		// picks up nested `when (device)` branches inside a branch, which are not
		// methods.
		for (const match of body.matchAll(/^ {8}"([A-Za-z0-9_]+)"\s*->/gm)) names.add(match[1]);
	}
	return names;
}

function duplicates(values) {
	const seen = new Set();
	const repeated = new Set();
	for (const value of values) {
		if (seen.has(value)) repeated.add(value);
		seen.add(value);
	}
	return [...repeated];
}

function sorted(values) {
	return [...values].sort((a, b) => a.localeCompare(b));
}

function diff(left, right) {
	return sorted([...left].filter(value => !right.has(value)));
}

// --------------------------------------------------------------------------
// Load the three sources
// --------------------------------------------------------------------------

const inventoryRows = parseCsv(read("electronapi-inventory.csv"));
const header = inventoryRows[0].map(value => value.trim());
const methodColumn = header.indexOf("method");
const categoryColumn = header.indexOf("category");
if (header[0] !== "method" || methodColumn < 0 || categoryColumn < 0) {
	fail(`unexpected CSV header: ${inventoryRows[0].join(",")}`);
}
const inventory = inventoryRows.slice(1).map(row => ({
	method: row[methodColumn].trim(),
	category: (row[categoryColumn] || "UNKNOWN").trim(),
}));
const inventoryNames = new Set(inventory.map(entry => entry.method));
const inventoryEvents = new Set(inventory.filter(entry => /^on[A-Z]/.test(entry.method)).map(entry => entry.method));
const categories = new Map(inventory.map(entry => [entry.method, entry.category]));

const bridgeSource = read("bridge/bridge.js");
const methodNames = stringArray(bridgeSource, "METHOD_NAMES");
const eventChannels = stringArray(bridgeSource, "EVENT_CHANNELS");
const devDefaults = devDefaultKeys(bridgeSource);
const bridgeSurface = new Set([...methodNames, ...devDefaults, ...eventChannels]);

const kotlinNames = kotlinDispatchNames(read("android/app/src/main/java/app/recordly/android/NativeBridge.kt"));

// --------------------------------------------------------------------------
// Compare
// --------------------------------------------------------------------------

const missingFromBridge = diff(inventoryNames, bridgeSurface);
const missingEvents = diff(inventoryEvents, new Set(eventChannels));
const bridgeExtras = sorted([...bridgeSurface].filter(name => !inventoryNames.has(name) && !ANDROID_ONLY.has(name)));
const kotlinExtras = sorted([...kotlinNames].filter(name => !inventoryNames.has(name) && !ANDROID_ONLY.has(name)));
const unimplemented = diff(inventoryNames, kotlinNames);

const duplicateFailures = [
	...duplicates(methodNames).map(name => `METHOD_NAMES: ${name}`),
	...duplicates(eventChannels).map(name => `EVENT_CHANNELS: ${name}`),
	...duplicates(devDefaults).map(name => `DEV_DEFAULTS: ${name}`),
];

/**
 * The generated asset copy is what actually ships. A stale copy is invisible in
 * review and reproducible only by remembering an undocumented step, so strict
 * mode treats it as drift.
 */
const assetFailures = [];
if (!ARGS.has("--skip-assets")) {
	for (const name of ["bridge.js", "android.js"]) {
		const source = resolve(ROOT, "bridge", name);
		const installed = resolve(ROOT, "android", "app", "src", "main", "assets", "renderer", "bridge-host", name);
		if (!existsSync(installed)) {
			assetFailures.push(`missing generated asset bridge-host/${name}`);
		} else if (readFileSync(source, "utf8") !== readFileSync(installed, "utf8")) {
			assetFailures.push(`stale generated asset bridge-host/${name} (run: node scripts/build-renderer.mjs --skip-build)`);
		}
	}
}

const strictFailures = [
	...missingFromBridge.map(name => `inventory method not exposed by bridge: ${name}`),
	...missingEvents.map(name => `inventory event channel missing from bridge: ${name}`),
	...bridgeExtras.map(name => `bridge exposes a name outside the inventory: ${name}`),
	...kotlinExtras.map(name => `Kotlin dispatches a name outside the inventory: ${name}`),
	...duplicateFailures.map(name => `duplicate bridge name: ${name}`),
	...assetFailures,
];

const byCategory = new Map();
for (const entry of inventory) {
	if (kotlinNames.has(entry.method)) continue;
	const bucket = byCategory.get(entry.category) || [];
	bucket.push(entry.method);
	byCategory.set(entry.category, bucket);
}

const report = {
	inventory: {
		methods: inventory.length,
		eventChannels: inventoryEvents.size,
		categories: Object.fromEntries(
			[...new Set(inventory.map(entry => entry.category))]
				.sort()
				.map(category => [category, inventory.filter(entry => entry.category === category).length]),
		),
	},
	bridge: {
		methods: methodNames.length,
		devDefaults: devDefaults.length,
		eventChannels: eventChannels.length,
		surface: bridgeSurface.size,
		extras: bridgeExtras,
	},
	kotlin: {
		dispatched: kotlinNames.size,
		implementedFromInventory: inventoryNames.size - unimplemented.length,
		unimplemented: unimplemented.length,
		unimplementedByCategory: Object.fromEntries([...byCategory].map(([category, names]) => [category, names.length])),
		extraNames: kotlinExtras,
	},
	failures: {
		missingFromBridge,
		missingEvents,
		duplicateNames: duplicateFailures,
		staleAssets: assetFailures,
	},
};

if (ARGS.has("--json")) {
	console.log(JSON.stringify({ ...report, strictFailures }, null, 2));
} else {
	console.log("Recordly bridge parity");
	console.log(`  inventory : ${report.inventory.methods} methods (${report.inventory.eventChannels} events) ` +
		Object.entries(report.inventory.categories).map(([key, value]) => `${key} ${value}`).join(", "));
	console.log(`  bridge    : ${report.bridge.methods} methods + ${report.bridge.devDefaults} defaults + ` +
		`${report.bridge.eventChannels} events = ${report.bridge.surface} names`);
	console.log(`  kotlin    : ${report.kotlin.dispatched} dispatched, ` +
		`${report.kotlin.implementedFromInventory}/${report.inventory.methods} inventory methods implemented, ` +
		`${report.kotlin.unimplemented} on fallback`);
	if (report.kotlin.unimplementedByCategory && Object.keys(report.kotlin.unimplementedByCategory).length > 0) {
		console.log(`              fallback: ${Object.entries(report.kotlin.unimplementedByCategory)
			.map(([key, value]) => `${key} ${value}`).join(", ")}`);
	}
	if (report.bridge.extras.length > 0) console.log(`  android-only bridge names: ${report.bridge.extras.join(", ")}`);
	if (report.kotlin.extraNames.length > 0) console.log(`  android-only kotlin names: ${report.kotlin.extraNames.join(", ")}`);

	if (strictFailures.length === 0) {
		console.log("\nOK — no structural drift.");
	} else {
		console.log(`\n${strictFailures.length} structural problem(s):`);
		for (const failure of strictFailures) console.log(`  - ${failure}`);
		if (ARGS.has("--strict")) {
			console.log("\nSTRICT: failing.");
			process.exitCode = 1;
		} else {
			console.log("\n(re-run with --strict to fail on these)");
		}
	}
}

if (ARGS.has("--strict") && strictFailures.length > 0) process.exitCode = 1;
