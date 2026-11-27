/**
 * Android host adapter — loaded by RecordlyServer immediately before bridge.js.
 *
 * `__recordlyHost` is the object injected with WebView.addJavascriptInterface.
 * Its calls are synchronous across the bridge (JNI call from the JS thread), so
 * we expose it as the three primitives bridge.js expects and nothing more.
 *
 * This file is deliberately tiny: bridge.js stays platform-neutral and testable
 * in Chrome, and this stays trivially auditable.
 */
(function () {
	"use strict";

	var host = window.__recordlyHost;
	if (!host) {
		return;
	}

	// Android Back dismisses Cut Room overlays without navigating the WebView.
	// Escape lets React Aria close a focused child popover first (e.g. Language).
	window.__recordlyDismissCutRoomOverlay = function () {
		var overlays = document.querySelectorAll("[data-cut-overlay]");
		var top = overlays[overlays.length - 1];
		if (!top) return false;
		if (top.hasAttribute("data-exiting")) return true;
		var back = top.querySelector("[data-cut-back]");
		if (back) {
			back.click();
			return true;
		}
		var focused = document.activeElement || top;
		var escape = new KeyboardEvent("keydown", { key: "Escape", code: "Escape", bubbles: true, cancelable: true });
		if (focused.dispatchEvent(escape)) {
			var close = top.querySelector("[data-slot='modal-close-trigger'], [data-cut-close]");
			if (close) close.click();
		}
		return true;
	};

	// The recorder uses Android's default microphone through AudioRecord.
	// WebView device labels otherwise trigger a getUserMedia request, whose
	// denial leaves the upstream microphone picker empty. Preserve browser
	// cameras while presenting the input that the native recorder actually uses.
	var mediaDevices = window.navigator && window.navigator.mediaDevices;
	if (mediaDevices && typeof mediaDevices.enumerateDevices === "function") {
		try {
			var inputs = JSON.parse(host.getSync("getAndroidMediaDevices", "[]"));
			if (Array.isArray(inputs) && inputs.length > 0) {
				var enumerate = mediaDevices.enumerateDevices.bind(mediaDevices);
				mediaDevices.enumerateDevices = async function () {
					var browserDevices = await enumerate().catch(function () { return []; });
					return browserDevices.filter(function (device) { return device.kind !== "audioinput"; })
						.concat(inputs.map(function (input) {
							return Object.assign({}, input, { toJSON: function () { return Object.assign({}, input); } });
						}));
				};
			}
		} catch (_) {
			// Older hosts keep the browser's device enumeration.
		}
	}

	// The HUD bar is a desktop row: a 120px source selector plus record, mic,
	// countdown, home and hide controls. At phone widths it is wider than the
	// viewport and is right-aligned, so the record button starts off-screen
	// (measured: 449px bar at x=-38 on a 411px viewport). Android records the
	// display and exposes one synthetic source, so the selector is not a choice
	// here. Cap the bar and let it scroll horizontally; the record control is
	// first, so it stays on screen. Injected from the host adapter to keep
	// upstream `src/` untouched. Scope to the HUD document so this wins over
	// the desktop CSS module even when that stylesheet is inserted later.
	var HUD_STYLE_ID = "recordly-android-hud";
	if (typeof document !== "undefined" && !document.getElementById(HUD_STYLE_ID)) {
		var hudStyle = document.createElement("style");
		hudStyle.id = HUD_STYLE_ID;
		hudStyle.textContent = [
			"@media (max-width: 520px) {",
			"  html.hud-overlay-window [data-hud-surface] {",
			"    max-width: calc(100vw - 16px);",
			"    gap: 6px;",
			"    padding: 6px 8px;",
			"    overflow-x: auto;",
			"    overflow-y: hidden;",
			"    scrollbar-width: none;",
			"    touch-action: pan-x;",
			"  }",
			"  html.hud-overlay-window [data-hud-surface]::-webkit-scrollbar { display: none; }",
			"}",
		].join("\n");
		(document.head || document.documentElement).appendChild(hudStyle);
	}

	window.__recordlyNative = {
		invoke: function (id, method, argsJson) {
			host.invoke(String(id), String(method), String(argsJson));
		},
		getSync: function (method, argsJson) {
			var raw = host.getSync(String(method), String(argsJson));
			return raw === undefined || raw === null ? null : String(raw);
		},
		subscribe: function (channel) {
			host.subscribe(String(channel));
		},
	};
})();
