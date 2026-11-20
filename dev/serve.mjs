/**
 * Dev server for the Recordly Android renderer.
 *
 * This is the executable specification for the Kotlin server (RecordlyServer.kt).
 * It must behave identically in three ways that matter:
 *
 *   1. Serve the built renderer from an origin the page treats as same-origin.
 *   2. Inject the bridge shim into index.html BEFORE the module script runs,
 *      so `window.electronAPI` exists when React mounts.
 *   3. Serve media with HTTP Range support, or video seeking breaks.
 *
 * Usage:
 *   node dev/serve.mjs [--root recordly-upstream/dist] [--port 4400]
 */

import { createServer } from "node:http";
import { createReadStream, statSync, existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { extname, join, normalize, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = resolve(fileURLToPath(new URL(".", import.meta.url)));
const PROJECT_ROOT = resolve(HERE, "..");

function arg(name, fallback) {
	const index = process.argv.indexOf(`--${name}`);
	return index === -1 ? fallback : process.argv[index + 1];
}

const ROOT = resolve(arg("root", join(PROJECT_ROOT, "recordly-upstream", "dist")));
const PORT = Number(arg("port", "4400"));
// Serve a shell that already has the bridge inlined (Android asset layout).
const NO_INJECT = process.argv.includes("--no-inject");
const BRIDGE_FILE = join(PROJECT_ROOT, "bridge", "bridge.js");
const FIXTURES = join(PROJECT_ROOT, "recordly-upstream", "tests", "ui", "fixtures");
const SHIM_TAG = '<script src="/__bridge/bridge.js"></script>';

const MIME = {
	".html": "text/html; charset=utf-8",
	".js": "text/javascript; charset=utf-8",
	".mjs": "text/javascript; charset=utf-8",
	".css": "text/css; charset=utf-8",
	".json": "application/json; charset=utf-8",
	".wasm": "application/wasm",
	".svg": "image/svg+xml",
	".png": "image/png",
	".jpg": "image/jpeg",
	".jpeg": "image/jpeg",
	".webp": "image/webp",
	".gif": "image/gif",
	".ico": "image/x-icon",
	".woff": "font/woff",
	".woff2": "font/woff2",
	".ttf": "font/ttf",
	".otf": "font/otf",
	".mp4": "video/mp4",
	".webm": "video/webm",
	".m4a": "audio/mp4",
	".mp3": "audio/mpeg",
	".txt": "text/plain; charset=utf-8",
	".map": "application/json; charset=utf-8",
};

function contentType(file) {
	return MIME[extname(file).toLowerCase()] || "application/octet-stream";
}

/** Resolve a URL path inside a root, refusing traversal. */
function safeResolve(root, urlPath) {
	const decoded = decodeURIComponent(urlPath.split("?")[0].split("#")[0]);
	const candidate = normalize(join(root, decoded));
	const prefix = root.endsWith(sep) ? root : root + sep;
	if (candidate !== root && !candidate.startsWith(prefix)) return null;
	return candidate;
}

async function serveHtml(response, file) {
	const html = await readFile(file, "utf8");
	if (NO_INJECT) {
		const body = Buffer.from(html, "utf8");
		response.writeHead(200, {
			"Content-Type": MIME[".html"],
			"Content-Length": body.length,
			"Cache-Control": "no-store",
		});
		response.end(body);
		return;
	}
	// Inject before the first module script so the bridge exists pre-React.
	let out;
	if (html.includes("</head>")) {
		out = html.replace("</head>", `  ${SHIM_TAG}\n  </head>`);
	} else {
		out = SHIM_TAG + html;
	}
	const body = Buffer.from(out, "utf8");
	response.writeHead(200, {
		"Content-Type": MIME[".html"],
		"Content-Length": body.length,
		"Cache-Control": "no-store",
	});
	response.end(body);
}

function serveRange(request, response, file, size) {
	const range = request.headers.range;
	const type = contentType(file);

	if (!range) {
		response.writeHead(200, {
			"Content-Type": type,
			"Content-Length": size,
			"Accept-Ranges": "bytes",
		});
		createReadStream(file).pipe(response);
		return;
	}

	// Supports a single range: "bytes=start-end" / "bytes=start-" / "bytes=-suffix"
	const match = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
	if (!match) {
		response.writeHead(416, { "Content-Range": `bytes */${size}` });
		response.end();
		return;
	}

	const hasStart = match[1] !== "";
	const hasEnd = match[2] !== "";
	let start;
	let end;

	if (hasStart) {
		start = Number(match[1]);
		end = hasEnd ? Number(match[2]) : size - 1;
	} else {
		const suffix = Number(match[2]);
		start = Math.max(0, size - suffix);
		end = size - 1;
	}

	if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) {
		response.writeHead(416, { "Content-Range": `bytes */${size}` });
		response.end();
		return;
	}

	end = Math.min(end, size - 1);
	response.writeHead(206, {
		"Content-Type": type,
		"Content-Length": end - start + 1,
		"Content-Range": `bytes ${start}-${end}/${size}`,
		"Accept-Ranges": "bytes",
	});
	createReadStream(file, { start, end }).pipe(response);
}

const server = createServer(async (request, response) => {
	try {
		const url = request.url || "/";

		// 1. The shim itself.
		if (url.split("?")[0] === "/__bridge/bridge.js") {
			const body = await readFile(BRIDGE_FILE);
			response.writeHead(200, {
				"Content-Type": MIME[".js"],
				"Content-Length": body.length,
				"Cache-Control": "no-store",
			});
			response.end(body);
			return;
		}

		// 2. Test fixtures (sample video, background) from the upstream repo.
		if (url.startsWith("/fixtures/")) {
			const file = safeResolve(FIXTURES, url.slice("/fixtures".length));
			if (!file || !existsSync(file)) {
				response.writeHead(404).end("not found");
				return;
			}
			serveRange(request, response, file, statSync(file).size);
			return;
		}

		// 3. Built renderer.
		let file = safeResolve(ROOT, url);

		if (file && existsSync(file) && statSync(file).isDirectory()) {
			file = join(file, "index.html");
		}
		if (!file || !existsSync(file)) {
			// SPA fallback: unknown paths get index.html, not a 404 page.
			const fallback = join(ROOT, "index.html");
			if (existsSync(fallback)) {
				await serveHtml(response, fallback);
				return;
			}
			response.writeHead(404).end("not found");
			return;
		}

		const stats = statSync(file);
		if (extname(file).toLowerCase() === ".html") {
			await serveHtml(response, file);
			return;
		}

		response.setHeader("Cache-Control", "no-store");
		serveRange(request, response, file, stats.size);
	} catch (error) {
		console.error("[serve] error:", error);
		if (!response.headersSent) response.writeHead(500);
		response.end("server error");
	}
});

server.listen(PORT, "127.0.0.1", () => {
	console.log(`renderer root : ${ROOT}`);
	console.log(`shim          : ${BRIDGE_FILE}`);
	console.log(`listening     : http://127.0.0.1:${PORT}/?windowType=editor`);
});
