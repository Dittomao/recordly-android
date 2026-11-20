/**
 * Recordly bridge shim.
 *
 * Implements `window.electronAPI` — the single seam between the Recordly
 * renderer and the host — for Android.
 *
 * Two backends:
 *   1. NATIVE  — inside the Android WebView, `window.__recordlyNative` is
 *                injected by the Kotlin host via addJavascriptInterface.
 *                Calls are async request/response; events are pushed back.
 *   2. DEV     — in a desktop browser (no native object), returns the same
 *                neutral values upstream's own UI tests use, so the editor
 *                can be developed and driven in Chrome.
 *
 * Contract notes:
 *   - `on*` methods take a callback and return an unsubscribe function.
 *     Callers store and invoke the returned cleanup, so this exact shape
 *     must be preserved.
 *   - Method names and argument order must match electron/preload.ts.
 *     Never rename, never change arity.
 */
(function () {
	"use strict";

	var native = window.__recordlyNative || null;
	var HAS_NATIVE = !!native;

	/* ------------------------------------------------------------------ *
	 * Native transport
	 * ------------------------------------------------------------------ */

	var pending = new Map();
	var sequence = 0;
	var nativeFailureStreak = 0;

	/** Matches `src/lib/exporter/muxer.ts`; large enough to keep overhead low. */
	var EXPORT_CHUNK_BYTES = 16 * 1024 * 1024;

	function invokeNative(method, args) {
		return new Promise(function (resolve, reject) {
			var id = String(++sequence);
			pending.set(id, { resolve: resolve, reject: reject });
			try {
				native.invoke(id, method, JSON.stringify(args));
			} catch (error) {
				pending.delete(id);
				reject(error);
			}
		});
	}

	/** Called from Kotlin when a native call completes. */
	window.__recordlyNativeResolve = function (id, payloadJson) {
		var entry = pending.get(id);
		if (!entry) return;
		pending.delete(id);
		nativeFailureStreak = 0;
		var payload;
		try {
			payload = JSON.parse(payloadJson);
		} catch (error) {
			entry.reject(error);
			return;
		}
		if (payload && payload.__error) {
			entry.reject(new Error(payload.__error));
			return;
		}
		entry.resolve(payload);
	};

	/** Called from Kotlin when a native call fails. */
	window.__recordlyNativeReject = function (id, message) {
		var entry = pending.get(id);
		if (!entry) return;
		pending.delete(id);
		nativeFailureStreak++;
		entry.reject(new Error(message || "native call failed"));
	};

	/* ------------------------------------------------------------------ *
	 * Event bus
	 * ------------------------------------------------------------------ */

	var listeners = new Map();
	var subscribedChannels = new Set();

	function addListener(channel, callback) {
		var set = listeners.get(channel);
		if (!set) {
			set = new Set();
			listeners.set(channel, set);
		}
		set.add(callback);

		if (HAS_NATIVE && !subscribedChannels.has(channel)) {
			subscribedChannels.add(channel);
			try {
				native.subscribe(channel);
			} catch (error) {
				console.error("[bridge] native subscribe failed:", channel, error);
			}
		}

		return function unsubscribe() {
			set.delete(callback);
		};
	}

	/** Called from Kotlin to deliver an event payload. */
	window.__recordlyNativeEmit = function (channel, payloadJson) {
		var set = listeners.get(channel);
		if (!set || set.size === 0) return;
		var payload;
		try {
			payload = JSON.parse(payloadJson);
		} catch (error) {
			payload = undefined;
		}
		set.forEach(function (callback) {
			try {
				callback(payload);
			} catch (error) {
				console.error("[bridge] listener threw on " + channel + ":", error);
			}
		});
	};

	/* ------------------------------------------------------------------ *
	 * Dev-time defaults (no native backend)
	 *
	 * Mirrors recordly-upstream/tests/ui/bridge.ts so the editor behaves
	 * the same in a desktop browser as it does under upstream's UI suite.
	 * ------------------------------------------------------------------ */

	var platform = window.__recordlyPlatform || (HAS_NATIVE ? "android" : "android");
	var videoFixture = window.__recordlyDevVideo || "fixtures/preview.mp4";

	var devPreferences = {
		success: true,
		showInDock: true,
		hideWhileRecording: false,
		launchAtLogin: false,
		launchAtLoginSupported: false,
		platform: platform,
	};

	var devState = {
		currentVideoPath: location.origin + "/" + videoFixture,
		recordingSession: null,
		editorMode: false,
		hasUnsavedChanges: false,
		countdownDelay: 3,
		projectsDirectory: "/projects",
		recordingsDirectory: "/recordings",
	};

	var devSource = {
		id: "screen:0:0",
		name: "Entire screen",
		thumbnail: "",
		display_id: "0",
	};

	function ok() {
		return { success: true };
	}

	var DEV_DEFAULTS = {
		/* auth storage — dev keeps it in memory via localStorage */
		authStorageAvailable: function () {
			return true;
		},
		authStorageMigrate: function () {
			return undefined;
		},
		authStorageGetItem: function (key) {
			return localStorage.getItem("dev-auth:" + key);
		},
		authStorageSetItem: function (key, value) {
			localStorage.setItem("dev-auth:" + key, value);
		},
		authStorageRemoveItem: function (key) {
			localStorage.removeItem("dev-auth:" + key);
		},
		getPendingAuthCallbackUrl: function () {
			return null;
		},
		ackAuthCallbackUrl: ok,

		/* settings */
		getAppSetting: function (key) {
			return localStorage.getItem("setting:" + key);
		},
		getAppSettingWithStatus: function (key) {
			var value = localStorage.getItem("setting:" + key);
			return { success: true, found: value !== null, value: value };
		},
		setAppSetting: function (key, value) {
			if (value === null || value === undefined) localStorage.removeItem("setting:" + key);
			else localStorage.setItem("setting:" + key, String(value));
			return true;
		},
		getApplicationPreferences: function () {
			return devPreferences;
		},
		setApplicationPreferences: function (patch) {
			devPreferences = Object.assign({}, devPreferences, patch);
			return devPreferences;
		},
		getRecordingPreferences: function () {
			return {
				success: true,
				microphoneEnabled: false,
				webcamEnabled: false,
				systemAudioEnabled: true,
			};
		},
		setRecordingPreferences: ok,
		getCountdownDelay: function () {
			return { success: true, delay: devState.countdownDelay };
		},
		setCountdownDelay: function (delay) {
			devState.countdownDelay = delay;
			return { success: true, delay: delay };
		},
		getActiveCountdown: function () {
			return { success: true, seconds: devState.countdownDelay };
		},
		cancelCountdown: function () {
			return { success: true };
		},
		getRecordingAudioLabConfig: function () {
			return { success: true, enabled: false };
		},
		getShortcuts: function () {
			return { success: true, shortcuts: {} };
		},
		saveShortcuts: ok,

		/* app identity */
		getPlatform: function () {
			return platform;
		},
		getAppVersion: function () {
			return window.__recordlyAppVersion || "0.0.0-dev";
		},
		getAssetBasePath: function () {
			return location.origin + "/";
		},
		listAssetDirectory: function () {
			// Upstream resolves `{success, files}`; `wallpapers.ts` reads `files`.
			return { success: true, files: [] };
		},
		openExternalUrl: ok,

		/* window chrome — none on Android */
		getWindowChrome: function () {
			return { trafficLightsVisible: false };
		},
		showProjectDashboard: function () {
			return undefined;
		},

		/* permissions */
		getMediaPermissionStatus: function () {
			return { success: true, microphone: "not-determined", camera: "denied" };
		},
		openMediaPermissionPreferences: ok,
		getScreenRecordingPermissionStatus: function () {
			return { success: true, status: "granted" };
		},
		getAccessibilityPermissionStatus: function () {
			return { success: true, trusted: false };
		},
		requestAccessibilityPermission: function () {
			return { success: false, trusted: false, prompted: false };
		},
		openAccessibilityPreferences: ok,
		openScreenRecordingPreferences: ok,
		showRecordingPermissions: ok,
		onRecordingPermissionsRequested: null,

		/* sources */
		getSources: function () {
			return [devSource];
		},
		getSelectedSource: function () {
			return devSource;
		},
		selectSource: ok,

		/* recording session lifecycle */
		reserveRecordingSession: function () {
			return { success: true, message: "", token: "dev-reservation" };
		},
		releaseRecordingStartup: function () {
			return undefined;
		},
		setRecordingState: function () {
			return undefined;
		},
		finishRecordingStartup: function () {
			return undefined;
		},
		getCurrentRecordingSession: function () {
			return { success: true, session: devState.recordingSession };
		},
		setCurrentRecordingSession: ok,
		getCurrentVideoPath: function () {
			return { success: true, path: devState.currentVideoPath };
		},
		setCurrentVideoPath: function (path) {
			devState.currentVideoPath = path;
			return { success: true };
		},
		getRecordedVideoPath: function () {
			return { success: true, path: devState.currentVideoPath };
		},
		clearCurrentVideoPath: ok,
		finishRecordingImport: ok,
		finishRecordingEditor: ok,
		cancelRecordingImport: ok,
		// The loading-editor handshake. Upstream opens a second window; here the
		// editor is the window, so the handshake is already complete. It must
		// answer `{}` — a present `error` field makes the renderer abort the
		// whole initial load.
		waitForRecordingEditor: function () {
			return {};
		},
		openLoadingRecordingEditor: ok,
		switchToEditor: function () {
			return undefined;
		},
		setHasUnsavedChanges: ok,
		setRecordingsRemoved: ok,
		getEditorMode: function () {
			return devState.editorMode;
		},
		getEditorStartupView: function () {
			return "editor";
		},
		getRecordingThumbnail: function () {
			return { success: false, error: "Unavailable" };
		},
		cacheProjectThumbnail: ok,

		/* telemetry — the sidecar the editor consumes */
		getCursorTelemetry: function () {
			return { success: true, samples: [] };
		},
		setCursorTelemetry: ok,
		getVideoAudioFallbackPaths: function () {
			return { success: true, paths: [] };
		},
		pauseCursorCapture: ok,
		resumeCursorCapture: ok,

		/* projects */
		getProjectsDirectory: function () {
			return { success: true, path: devState.projectsDirectory };
		},
		getRecordingsDirectory: function () {
			return { success: true, path: devState.recordingsDirectory };
		},
		openProjectsDirectory: ok,
		openRecordingsFolder: ok,
		chooseRecordingsDirectory: function () {
			return { success: true, path: devState.recordingsDirectory, canceled: true };
		},
		listProjectFiles: function () {
			return { success: true, projects: [], entries: [] };
		},
		listRecordings: function () {
			return { success: true, recordings: [] };
		},
		loadProjectFile: function () {
			return { success: false, error: "No project" };
		},
		loadCurrentProjectFile: function () {
			return { success: false };
		},
		createProjectFile: function () {
			return {
				success: true,
				path: devState.projectsDirectory + "/Untitled Project.recordly",
				projectId: "dev-project",
			};
		},
		saveProjectFile: function (_data, _name, projectPath) {
			return {
				success: true,
				path: projectPath || devState.projectsDirectory + "/Untitled Project.recordly",
				projectId: "dev-project",
			};
		},
		getProjectPreview: function () {
			return { success: false, error: "Preview unavailable" };
		},
		trashProjectFiles: function (paths) {
			return { success: true, deleted: paths || [], errors: [] };
		},
		deleteRecordingFile: ok,
		findDuplicateRecordingSources: function () {
			return { success: true, matches: [] };
		},
		relinkProjectRecording: function () {
			return { success: false, error: "Unavailable" };
		},
		importRecording: function () {
			return { success: false, canceled: true };
		},
		importWallpaperVideo: function () {
			return { success: false, canceled: true };
		},
		generateWallpaperThumbnail: function () {
			return { success: false, error: "Unavailable" };
		},

		/* hud overlay — an Android activity, not a window */
		showRecordingHud: ok,
		hudOverlayRendererReady: ok,
		hudOverlaySetIgnoreMouse: ok,
		hudOverlaySetWebcamPreviewVisible: ok,
		getHudOverlayMousePassthroughSupported: function () {
			return { success: true, supported: false };
		},
		getHudOverlayCaptureProtection: function () {
			return { success: true, enabled: false, supported: false };
		},
		setHudOverlayCaptureProtection: function (enabled) {
			return { success: true, enabled: !!enabled };
		},
		// Upstream's own UI test bridge answers `false`, which makes the renderer
		// inline a full-screen sign-in dialog over the editor. On Android there
		// is no second window and no cloud sign-in in this build, so that dialog
		// would be a permanent, unsolvable curtain. `true` means "the host
		// handled it" and leaves the editor usable.
		showOnboardingWindow: function () {
			return true;
		},
		closeOnboardingWindow: function () {
			return true;
		},

		/* updates — nothing to update on a sideloaded APK */
		getUpdateStatusSummary: function () {
			return { status: "idle", currentVersion: window.__recordlyAppVersion || "0.0.0", availableVersion: null };
		},
		checkForAppUpdates: ok,
		getAutomaticUpdateDownloadsEnabled: function () {
			return false;
		},
		setAutomaticUpdateDownloadsEnabled: function (enabled) {
			return { success: true, enabled: !!enabled };
		},
		getExperimentalUpdatesEnabled: function () {
			return false;
		},
		setExperimentalUpdatesEnabled: function (enabled) {
			return { success: true, enabled: !!enabled };
		},
		getCurrentUpdateToastPayload: function () {
			return null;
		},
		dismissUpdateToast: ok,
		previewUpdateToast: ok,
		downloadAvailableUpdate: ok,
		downloadReleaseVersion: ok,
		deferDownloadedUpdate: ok,
		installDownloadedUpdate: ok,
		listReleaseVersions: function () {
			return { success: true, releases: [] };
		},
		skipUpdateVersion: ok,

		/* captions / speech models */
		getWhisperSmallModelStatus: function () {
			return { success: true, exists: false };
		},
		getParakeetModelStatus: function () {
			return { success: true, exists: false };
		},
		getCaptionModelStatus: function () {
			return { success: true, exists: false, path: null };
		},

		/* announcements */
		getAnnouncements: function () {
			return { success: true, announcements: [] };
		},
	};

	/* Methods that take a callback and return an unsubscribe function. */
	/*
	 * Every callable `electron/preload.ts` exposes. The renderer calls these as
	 * `window.electronAPI.foo(...)` with no guarding, so a missing key is not a
	 * degradation — it is a `TypeError` that takes down whatever component
	 * mounted at that moment. The surface is therefore defined in full, even
	 * where Android has no meaningful answer yet; those paths resolve through
	 * the documented soft-failure shape instead of throwing.
	 *
	 * Regenerate from upstream's preload if it changes: names, not behaviour,
	 * are what this list encodes.
	 */
	var EVENT_CHANNELS = [
	"onAuthCallbackUrl", "onCaptionModelDownloadProgress",
	"onCapturePickerWindowResized", "onCloudShareProgress", "onCountdownTick",
	"onCursorStateChanged", "onEditorModeChanged", "onMenuLoadProject",
	"onMenuSaveProject", "onMenuSaveProjectAs", "onNativeStaticLayoutExportProgress",
	"onOnboardingIntroShown", "onParakeetModelDownloadProgress",
	"onProjectThumbnailReady", "onRecordingInterrupted",
	"onRecordingPermissionsRequested", "onRecordingSessionChanged",
	"onRecordingStateChanged", "onRequestSaveBeforeClose", "onSelectedSourceChanged",
	"onStopRecordingFromTray", "onUpdateReadyToast", "onUpdateToastStateChanged",
	"onWhisperSmallModelDownloadProgress", "onWindowChromeChanged",
	"onWindowFullscreenChanged",
];

	var METHOD_NAMES = [
	"ackAuthCallbackUrl", "appendExportDiagnostic", "appendRecordingFileStream",
	"authStorageAvailable", "authStorageGetItem", "authStorageMigrate",
	"authStorageRemoveItem", "authStorageSetItem", "cacheProjectThumbnail",
	"cancelCountdown", "cancelGifOptimization", "cancelRecordingImport",
	"capturePickerReady", "checkForAppUpdates", "chooseRecordingsDirectory",
	"clearCurrentVideoPath", "closeExportStream", "closeOnboardingWindow",
	"closePermissionAccessory", "closeRecordingFileStream", "cloudBilling",
	"cloudBillingCancel", "cloudShareBeginExport", "cloudShareCancel",
	"cloudShareManage", "cloudShareUpdateToken", "cloudShareUpload",
	"completeCapturePick", "createProjectFile", "deferDownloadedUpdate",
	"deleteCaptionModel", "deleteParakeetModel", "deleteRecordingFile",
	"deleteWhisperSmallModel", "discardExportedTemp", "discardRecordingFileStream",
	"dismissUpdateToast", "downloadAvailableUpdate", "downloadCaptionModel",
	"downloadParakeetModel", "downloadReleaseVersion", "downloadWhisperSmallModel",
	"finalizeExportedVideo", "findDuplicateRecordingSources", "finishRecordingEditor",
	"finishRecordingImport", "finishRecordingStartup", "finishWallpaperImport",
	"generateAutoCaptions",
	"generateWallpaperThumbnail", "getAccessibilityPermissionStatus",
	"getActiveCountdown", "getAnnouncements", "getAppSetting",
	"getAppSettingWithStatus", "getAppVersion", "getApplicationPreferences",
	"getAssetBasePath", "getAutomaticUpdateDownloadsEnabled", "getCaptionModelStatus",
	"getCapturePickerContext", "getCountdownDelay", "getCurrentRecordingSession",
	"getCurrentUpdateToastPayload", "getCurrentVideoPath", "getCursorTelemetry",
	"getDesktopWallpaper", "getEditorMode", "getEditorStartupView",
	"getExperimentalUpdatesEnabled", "getExportDiagnostics", "getExportHardwareInfo",
	"getExportRuntimeInfo", "getHudOverlayCaptureProtection",
	"getHudOverlayMousePassthroughSupported", "getLastNativeCaptureDiagnostics",
	"getLinuxWindowSystem", "getLocalMediaUrl", "getMediaPermissionStatus",
	"getNativeExportCapabilities", "getParakeetModelStatus",
	"getPendingAuthCallbackUrl", "getPlatform", "getProjectPreview",
	"getProjectsDirectory", "getRecordedVideoPath", "getRecordingAudioLabConfig",
	"getRecordingPreferences", "getRecordingThumbnail", "getRecordingsDirectory",
	"getScreenRecordingPermissionStatus", "getSelectedSource", "getShortcuts",
	"getSources", "getSystemCursorAssets", "getUpdateStatusSummary",
	"getVideoAudioFallbackPaths", "getWhisperSmallModelStatus", "getWindowChrome",
	"hideOsCursor", "hudOverlayClose", "hudOverlayDrag", "hudOverlayHide",
	"hudOverlayRendererReady", "hudOverlaySetIgnoreMouse",
	"hudOverlaySetInteractiveBounds", "hudOverlaySetSourceSelectionActive",
	"hudOverlaySetWebcamPreviewVisible", "importRecording", "importWallpaperVideo",
	"installDownloadedUpdate", "isNativeWindowsCaptureAvailable",
	"isWindowFullscreen", "listAssetDirectory", "listProjectFiles", "listRecordings",
	"listReleaseVersions", "loadCurrentProjectFile", "loadProjectFile",
	"muxExportedVideoAudio", "muxExportedVideoAudioFromPath",
	"muxNativeWindowsRecording", "nativeStaticLayoutExport",
	"nativeStaticLayoutExportCancel", "nativeVideoExportCancel",
	"nativeVideoExportFinish", "nativeVideoExportStart",
	"nativeVideoExportWriteFrame", "nativeVideoExportWriteFrames",
	"openAccessibilityPreferences", "openAudioFilePicker", "openExportStream",
	"openExternalUrl", "openLoadingRecordingEditor", "openMediaPermissionPreferences",
	"openProjectFileAtPath", "openProjectsDirectory", "openRecordingFileStream",
	"openRecordingsFolder", "openScreenRecordingPreferences", "openSourceSelector",
	"openVideoFilePicker", "openWhisperExecutablePicker", "openWhisperModelPicker",
	"optimizeGif", "pauseCursorCapture", "pauseNativeScreenRecording",
	"pickCaptureTarget", "previewUpdateToast", "probeNativeVideoMetadata",
	"readLocalFile", "recoverNativeScreenRecording", "recoverRecordingOwner",
	"releaseRecordingStartup", "relinkProjectRecording", "renameLibraryProject",
	"reportOnboardingIntro", "requestAccessibilityPermission",
	"requestMediaPermission", "reserveRecordingSession", "resizeCaptureWindow",
	"resumeCursorCapture", "resumeNativeScreenRecording", "revealInFolder",
	"reviewProjectDeletion", "saveExportedVideo", "saveProjectFile",
	"saveProjectFileNamed", "saveShortcuts", "selectSource", "setAppLocale",
	"setAppSetting", "setApplicationPreferences",
	"setAutomaticUpdateDownloadsEnabled", "setCountdownDelay",
	"setCurrentRecordingSession", "setCurrentVideoPath", "setCursorScale",
	"setCursorTelemetry", "setExperimentalUpdatesEnabled", "setExportInProgress",
	"setHasUnsavedChanges", "setHudOverlayCaptureProtection",
	"setRecordingPreferences", "setRecordingState", "setRecordingsRemoved",
	"showOnboardingWindow", "showProjectDashboard", "showRecordingHud",
	"showRecordingPermissions", "showSourceHighlight", "skipUpdateVersion",
	"startCountdown", "startFfmpegRecording", "startNativeScreenRecording",
	"startPermissionAccessoryDrag", "stopFfmpegRecording",
	"stopNativeScreenRecording", "storeMicrophoneSidecar", "storeRecordedVideo",
	"suggestExportFileName", "switchToEditor", "trashProjectFiles",
	"trashRecordingSources", "waitForRecordingEditor", "windowControl",
	"writeClipboardText", "writeExportStreamChunk", "writeExportedVideoToPath",
];

	/* ------------------------------------------------------------------ *
	 * Public API assembly
	 * ------------------------------------------------------------------ */

	/*
	 * These four are synchronous in electron/preload.ts (ipcRenderer.sendSync):
	 * callers read the result directly, without awaiting. Everything else
	 * returns a Promise. Getting this wrong breaks startup immediately —
	 * a plain object returned where a Promise is expected throws
	 * "... .then is not a function".
	 */
	var SYNC_METHODS = {
		getEditorStartupView: true,
		getAppSetting: true,
		getAppSettingWithStatus: true,
		setAppSetting: true,
	};

	/**
	 * Methods whose arguments carry raw bytes.
	 *
	 * JSON.stringify turns an ArrayBuffer into `{}` and a Uint8Array into a
	 * giant index-keyed object, so these must never travel the JSON transport:
	 * the call would appear to succeed and produce a corrupt file. Export bytes
	 * use the HTTP channel below. Native audio muxing still has no byte channel,
	 * so those calls fail explicitly instead of producing a corrupt file.
	 */
	var BINARY_METHODS = {
		muxExportedVideoAudio: true,
		muxExportedVideoAudioFromPath: true,
	};

	/**
	 * Methods whose RESULT is raw bytes.
	 *
	 * Native answers `{ success: true, url }` and the bytes are fetched from the
	 * loopback server here, in the renderer, which keeps the JSON transport
	 * text-only. The caller (assetPath.ts) requires a real `Uint8Array` in
	 * `data`; a string or object would be silently misread as pixel data.
	 */
	var URL_BYTES_METHODS = {
		generateWallpaperThumbnail: true,
		readLocalFile: true,
	};

	function hydrateBytes(payload) {
		if (!payload || !payload.success || !payload.url) return payload;
		return fetch(payload.url)
			.then(function (response) {
				if (!response.ok) throw new Error("bytes " + response.status);
				return response.arrayBuffer();
			})
			.then(function (buffer) {
				return { success: true, data: new Uint8Array(buffer) };
			})
			.catch(function (error) {
				console.warn("[bridge] could not fetch result bytes for " + payload.url, error);
				return { success: false, error: "bytes unavailable" };
			});
	}

	/**
	 * The loopback server's shared secret. Fetched once from the native host
	 * and cached for the page's lifetime; dev mode has no token.
	 */
	var cachedBridgeToken;
	function bridgeToken() {
		if (!HAS_NATIVE) return "";
		if (cachedBridgeToken === undefined) {
			try {
				cachedBridgeToken = invokeNativeSync("getBridgeToken", []);
			} catch (error) {
				cachedBridgeToken = null;
			}
		}
		return cachedBridgeToken == null ? "" : String(cachedBridgeToken);
	}

	function tokenQuery() {
		var token = bridgeToken();
		return token ? "&token=" + encodeURIComponent(token) : "";
	}

	/** Keep typed arrays off the JSON bridge and write them as raw HTTP bodies. */
	function postExportChunk(streamId, position, bytes) {
		var url =
			"/__recordly/export?stream=" +
			encodeURIComponent(String(streamId)) +
			"&offset=" +
			encodeURIComponent(String(position)) +
			tokenQuery();
		return fetch(url, {
			method: "POST",
			headers: { "Content-Type": "application/octet-stream" },
			body: bytes,
		})
			.then(function (response) {
				return response.json().catch(function () {
					return {
						success: false,
						error: "export write failed (" + response.status + ")",
					};
				});
			})
			.catch(function (error) {
				return { success: false, error: "export write failed: " + String(error) };
			});
	}

	function exportBytes(value) {
		if (value instanceof ArrayBuffer) return new Uint8Array(value);
		if (ArrayBuffer.isView(value)) {
			return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
		}
		throw new TypeError("Export data must be an ArrayBuffer or typed array");
	}

	/** Whole-buffer saves use the same open, write, close and finalize path. */
	async function bufferToExportTemp(buffer, fileName) {
		var extension = String(fileName).split(".").pop().toLowerCase();
		var opened = await api.openExportStream({ extension: extension });
		if (!opened || !opened.success || !opened.streamId) {
			throw new Error((opened && opened.error) || "Failed to open export stream");
		}
		try {
			var written = await postExportChunk(opened.streamId, 0, exportBytes(buffer));
			if (!written || !written.success) {
				throw new Error((written && written.error) || "Failed to write export stream");
			}
			var closed = await api.closeExportStream(opened.streamId);
			if (!closed || !closed.success || !closed.tempPath) {
				throw new Error((closed && closed.error) || "Failed to close export stream");
			}
			return closed.tempPath;
		} catch (error) {
			try {
				await api.closeExportStream(opened.streamId, { abort: true });
			} catch (ignored) {
				// Preserve the original write error.
			}
			throw error;
		}
	}

	var WALLPAPER_VIDEO_EXTENSIONS = /^(webm|mp4|mov|m4v|avi|mkv)$/;

	var BYTE_CHANNEL_METHODS = {
		writeExportStreamChunk: function (streamId, position, chunk) {
			return postExportChunk(streamId, position, exportBytes(chunk));
		},

		/*
		 * Upstream's preload receives a `File` from `webUtils.getPathForFile`.
		 * That cannot cross the JSON transport — a File has no JSON form — so
		 * the bytes go over the same HTTP channel exports use, and the native
		 * side moves the finished temp file into the app's wallpaper store.
		 */
		importWallpaperVideo: function (file) {
			if (!file || typeof file.slice !== "function" || typeof file.size !== "number" || file.size <= 0) {
				return Promise.resolve({ success: false, error: "Unsupported wallpaper file" });
			}
			var name = String(file.name || "wallpaper.mp4");
			var extension = name.split(".").pop().toLowerCase();
			if (!WALLPAPER_VIDEO_EXTENSIONS.test(extension)) {
				return Promise.resolve({ success: false, error: "Unsupported wallpaper video format" });
			}
			return api
				.openExportStream({ extension: extension })
				.then(function (opened) {
					if (!opened || !opened.success || !opened.streamId) {
						throw new Error((opened && opened.error) || "Failed to open the wallpaper stream");
					}
					var streamId = opened.streamId;
					var upload = Promise.resolve();
					for (var offset = 0; offset < file.size; offset += EXPORT_CHUNK_BYTES) {
						upload = upload.then(
							(function (position) {
								return function () {
									return file
										.slice(position, position + EXPORT_CHUNK_BYTES)
										.arrayBuffer()
										.then(function (buffer) {
											return postExportChunk(streamId, position, new Uint8Array(buffer));
										})
										.then(function (written) {
											if (!written || !written.success) {
												throw new Error((written && written.error) || "Failed to write the wallpaper");
											}
										});
									};
							})(offset),
						);
					}
					return upload
						.then(function () {
							return api.finishWallpaperImport(streamId, name);
						})
						.then(function (finished) {
							if (!finished || !finished.success) {
								throw new Error((finished && finished.error) || "Failed to save the wallpaper");
							}
							return finished;
						})
						.catch(function (error) {
							return api
								.closeExportStream(streamId, { abort: true })
								.catch(function () {})
								.then(function () {
									return { success: false, error: String(error && error.message ? error.message : error) };
								});
						});
				})
				.catch(function (error) {
					return { success: false, error: String(error && error.message ? error.message : error) };
				});
		},
		saveExportedVideo: async function (buffer, fileName, captionSidecar) {
			try {
				var tempPath = await bufferToExportTemp(buffer, fileName);
				return await api.finalizeExportedVideo({
					tempPath: tempPath,
					fileName: fileName,
					outputPath: null,
					captionSidecar: captionSidecar,
				});
			} catch (error) {
				return { success: false, error: String(error), message: String(error) };
			}
		},
		writeExportedVideoToPath: async function (buffer, outputPath, captionSidecar) {
			try {
				var path = String(outputPath);
				var tempPath = await bufferToExportTemp(buffer, path);
				return await api.finalizeExportedVideo({
					tempPath: tempPath,
					fileName: path.split(/[\\/]/).pop(),
					outputPath: path,
					captionSidecar: captionSidecar,
				});
			} catch (error) {
				return { success: false, error: String(error), message: String(error) };
			}
		},
	};

	var api = {};
	var warned = new Set();

	function invokeNativeSync(method, args) {
		// addJavascriptInterface calls are synchronous on the WebView JS thread.
		var json = native.getSync(method, JSON.stringify(args));
		return json === null || json === undefined || json === "" ? null : JSON.parse(json);
	}

	function makeMethod(name) {
		return function () {
			var args = Array.prototype.slice.call(arguments);

			if (HAS_NATIVE) {
				if (BYTE_CHANNEL_METHODS[name]) {
					return Promise.resolve()
						.then(function () {
							return BYTE_CHANNEL_METHODS[name].apply(null, args);
						})
						.catch(function (error) {
							return { success: false, error: String(error) };
						});
				}
				if (BINARY_METHODS[name]) {
					var binaryFallback = DEV_DEFAULTS[name];
					console.warn("[bridge] " + name + " carries bytes and needs the byte channel");
					return Promise.resolve(
						binaryFallback
							? binaryFallback.apply(null, args)
							: { success: false, error: "binary transport unavailable: " + name },
					);
				}
				if (SYNC_METHODS[name]) {
					try {
						return invokeNativeSync(name, args);
					} catch (error) {
						console.warn("[bridge] native sync failed for " + name, error);
						return DEV_DEFAULTS[name] ? DEV_DEFAULTS[name].apply(null, args) : null;
					}
				}
				return invokeNative(name, args)
					.then(function (payload) {
						return URL_BYTES_METHODS[name] ? hydrateBytes(payload) : payload;
					})
					.catch(function (error) {
						// A native failure must not white-screen the editor: fall back
						// to the dev default so the UI degrades instead of crashing.
						if (DEV_DEFAULTS[name]) {
							console.warn("[bridge] native failed for " + name + ", using default:", error && error.message);
							return DEV_DEFAULTS[name].apply(null, args);
						}
						// No default either: answer with a failure the caller can
						// read rather than throwing where nothing handles it. This is
						// what "defined but not implemented on Android" looks like.
						if (!warned.has(name)) {
							warned.add(name);
							console.warn("[bridge] no Android implementation for " + name);
						}
						return { success: false, error: "unsupported on Android: " + name };
					});
			}

			var fallback = DEV_DEFAULTS[name];
			if (fallback) {
				var value = fallback.apply(null, args);
				if (SYNC_METHODS[name]) return value;
				if (value && typeof value.then === "function") return value;
				return Promise.resolve(value);
			}

			if (!warned.has(name)) {
				warned.add(name);
				console.warn("[bridge] no implementation for " + name);
			}
			return SYNC_METHODS[name] ? null : Promise.resolve({ success: false, error: "unimplemented: " + name });
		};
	}

	// The whole upstream surface, not just what has an implementation here.
	// A missing key is a TypeError inside whichever component mounted.
	var surface = METHOD_NAMES.slice();
	Object.keys(DEV_DEFAULTS).forEach(function (name) {
		if (surface.indexOf(name) === -1) surface.push(name);
	});
	surface.forEach(function (name) {
		if (EVENT_CHANNELS.indexOf(name) !== -1) return;
		api[name] = makeMethod(name);
	});

	EVENT_CHANNELS.forEach(function (channel) {
		api[channel] = function (callback) {
			if (typeof callback !== "function") return function () {};
			return addListener(channel, callback);
		};
	});

	window.electronAPI = api;

	window.__recordlyBridge = {
		hasNative: HAS_NATIVE,
		platform: platform,
		methods: Object.keys(api),
		emit: function (channel, payload) {
			window.__recordlyNativeEmit(channel, JSON.stringify(payload === undefined ? null : payload));
		},
		dev: DEV_DEFAULTS,
		state: devState,
	};

	/* Surface unhandled native rejections rather than letting them vanish. */
	if (HAS_NATIVE) {
		window.addEventListener("error", function (event) {
			if (event && event.error) console.error("[bridge] uncaught:", event.error);
		});
	}
})();
