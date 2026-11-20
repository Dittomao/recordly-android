import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import vm from "node:vm";

const source = readFileSync(new URL("./bridge.js", import.meta.url), "utf8");

function createBridge({ handleNative, fetch, token }) {
	const calls = [];
	const storage = new Map();
	const window = {
		__recordlyPlatform: "win32",
		addEventListener() {},
		__recordlyNative: {
			invoke(id, method, argsJson) {
				const args = JSON.parse(argsJson);
				calls.push({ method, args });
				Promise.resolve(handleNative(method, args)).then((result) => {
					window.__recordlyNativeResolve(id, JSON.stringify(result));
				});
			},
			getSync(method) {
				if (method === "getBridgeToken" && token) return JSON.stringify(token);
				return "null";
			},
			subscribe() {},
		},
	};
	vm.runInNewContext(source, {
		window,
		location: { origin: "http://127.0.0.1:41234" },
		localStorage: {
			getItem: (key) => storage.get(key) ?? null,
			setItem: (key, value) => storage.set(key, value),
			removeItem: (key) => storage.delete(key),
		},
		fetch,
		console: { warn() {}, error() {} },
		ArrayBuffer,
		Uint8Array,
	});
	return { api: window.electronAPI, calls };
}

test("export chunks reach the HTTP byte channel without JSON serialization", async () => {
	const posts = [];
	const { api, calls } = createBridge({
		handleNative: () => { throw new Error("binary call crossed the JSON bridge"); },
		fetch: async (url, options) => {
			posts.push({ url, options });
			return { status: 200, json: async () => ({ success: true }) };
		},
	});
	const sourceBytes = new Uint8Array([99, 1, 2, 3, 99]);
	const result = await api.writeExportStreamChunk("stream id", 17, sourceBytes.subarray(1, 4));
	assert.equal(result.success, true);
	assert.deepEqual(calls, []);
	assert.equal(posts[0].url, "/__recordly/export?stream=stream%20id&offset=17");
	assert.equal(posts[0].options.method, "POST");
	assert.deepEqual([...posts[0].options.body], [1, 2, 3]);
});

test("export chunks carry the loopback token when native is present", async () => {
	const posts = [];
	const { api } = createBridge({
		token: "abc123",
		handleNative: () => { throw new Error("binary call crossed the JSON bridge"); },
		fetch: async (url, options) => {
			posts.push({ url, options });
			return { status: 200, json: async () => ({ success: true }) };
		},
	});
	await api.writeExportStreamChunk("s", 0, new Uint8Array([1]));
	assert.equal(posts[0].url, "/__recordly/export?stream=s&offset=0&token=abc123");
});

test("whole-buffer saves open, upload, close, and finalize", async () => {
	const posts = [];
	const { api, calls } = createBridge({
		handleNative: (method) => {
			if (method === "openExportStream") {
				return { success: true, streamId: "s1", tempPath: "/tmp/s1.mp4" };
			}
			if (method === "closeExportStream") {
				return { success: true, tempPath: "/tmp/s1.mp4" };
			}
			if (method === "finalizeExportedVideo") {
				return { success: true, path: "/exports/movie.mp4" };
			}
			throw new Error(`unexpected method: ${method}`);
		},
		fetch: async (url, options) => {
			posts.push({ url, options });
			return { status: 200, json: async () => ({ success: true }) };
		},
	});
	const result = await api.saveExportedVideo(new Uint8Array([4, 5, 6]).buffer, "movie.mp4");
	assert.equal(result.path, "/exports/movie.mp4");
	assert.deepEqual(calls.map(({ method }) => method), [
		"openExportStream", "closeExportStream", "finalizeExportedVideo",
	]);
	assert.deepEqual([...posts[0].options.body], [4, 5, 6]);
	assert.equal(calls[2].args[0].fileName, "movie.mp4");
});

test("a failed upload aborts its temporary stream", async () => {
	const { api, calls } = createBridge({
		handleNative: (method) => method === "openExportStream"
			? { success: true, streamId: "s2", tempPath: "/tmp/s2.mp4" }
			: { success: true, tempPath: null },
		fetch: async () => ({ status: 409, json: async () => ({ success: false, error: "disk full" }) }),
	});
	const result = await api.saveExportedVideo(new Uint8Array([7]).buffer, "movie.mp4");
	assert.equal(result.success, false);
	assert.match(result.error, /disk full/);
	assert.deepEqual(calls.map(({ method }) => method), ["openExportStream", "closeExportStream"]);
	assert.deepEqual(calls[1].args, ["s2", { abort: true }]);
});

test("a wallpaper File uploads through the byte channel instead of JSON", async () => {
	const bytes = new Uint8Array([9, 8, 7, 6]);
	const posts = [];
	const { api, calls } = createBridge({
		handleNative: (method) => {
			if (method === "openExportStream") {
				return { success: true, streamId: "wall-1", tempPath: "/tmp/wall-1.mp4" };
			}
			if (method === "finishWallpaperImport") {
				return { success: true, path: "/data/wallpapers/wallpaper-1.mp4" };
			}
			throw new Error(`unexpected method: ${method}`);
		},
		fetch: async (url, options) => {
			posts.push({ url, options });
			return { status: 200, json: async () => ({ success: true }) };
		},
	});
	const file = {
		name: "background.mp4",
		size: bytes.byteLength,
		slice(start, end) {
			const chunk = bytes.subarray(start, end);
			return {
				arrayBuffer: async () =>
					chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength),
			};
		},
	};
	const result = await api.importWallpaperVideo(file);
	assert.equal(result.success, true, JSON.stringify(result));
	assert.equal(result.path, "/data/wallpapers/wallpaper-1.mp4");
	assert.deepEqual(calls.map(({ method }) => method), [
		"openExportStream", "finishWallpaperImport",
	]);
	assert.deepEqual([...posts[0].options.body], [9, 8, 7, 6]);
	assert.equal(posts[0].url, "/__recordly/export?stream=wall-1&offset=0");
	assert.deepEqual(calls[1].args, ["wall-1", "background.mp4"]);
});

test("readLocalFile fetches native URL bytes into result.data", async () => {
	const bytes = new Uint8Array([10, 20, 30]);
	const { api } = createBridge({
		handleNative: () => ({ success: true, url: "http://127.0.0.1:41234/media/image.png" }),
		fetch: async () => ({ ok: true, arrayBuffer: async () => bytes.buffer }),
	});
	const result = await api.readLocalFile("/private/image.png");
	assert.equal(result.success, true);
	assert.ok(result.data instanceof Uint8Array);
	assert.deepEqual([...result.data], [10, 20, 30]);
});
