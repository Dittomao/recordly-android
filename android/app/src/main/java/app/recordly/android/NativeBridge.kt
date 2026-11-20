package app.recordly.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The native half of `window.electronAPI`.
 *
 * Transport contract with `bridge/bridge.js`:
 *
 *   invoke(id, method, argsJson)  fire-and-forget; the reply arrives later via
 *                                 `window.__recordlyNativeResolve(id, resultJson)`
 *                                 or `__recordlyNativeReject(id, message)`.
 *   getSync(method, argsJson)     blocking; returns the result JSON directly.
 *                                 Only the four methods Electron implemented with
 *                                 `sendSync` may use it.
 *   subscribe(channel)            event channels the page has listeners for.
 *
 * Anything not implemented here throws, and the shim then falls back to the
 * neutral values upstream's own Playwright harness uses. That is deliberate:
 * a method we have not verified against the real renderer is safer left to the
 * known-good fallback than answered with a guessed shape.
 */
class NativeBridge(
    context: Context,
    private val baseUrl: () -> String,
    private val media: MediaRegistry,
    /** Installed renderer root, i.e. what `/` is served from. */
    private val assetRoot: File,
    private val onResolve: (id: String, payloadJson: String) -> Unit,
    private val onReject: (id: String, message: String) -> Unit,
    private val onEmit: (channel: String, payloadJson: String) -> Unit,
    /** Shared secret for protected loopback routes; empty outside the app host. */
    private val tokenProvider: () -> String = { "" },
) {
    private val appContext: Context = context.applicationContext
    private val settings = SettingsStore(context)
    private val projects = ProjectStore(
        context = context,
        root = File(context.filesDir, "projects"),
        media = media,
        baseUrlProvider = baseUrl,
    )

    // Recordings live UNDER the media root on purpose. Anything outside it can
    // only be reached through the link index (`linked/<hash>`), which makes the
    // app's own output depend on a persisted side table, gives the URL no file
    // extension, and dies with the index. Inside the root they are plain owned
    // files: `/media/recordings/<name>.mp4`, served with the right type and
    // stable across restarts.
    private val recordingsDir = media.ownedFile("recordings").apply { mkdirs() }
    private val exportsDir = media.ownedFile("exports").apply { mkdirs() }

    private val worker: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "recordly-bridge").apply { isDaemon = true }
    }
    private val subscriptions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var currentVideo: File? = null

    @Volatile
    private var currentSession: JSONObject? = null

    private var reservationCounter = 0

    /** Outputs of [importRecording] that the renderer has not accepted yet. */
    private val pendingImports: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Set by `cancelRecordingImport`; cleared at the start of every import. */
    @Volatile
    private var importCancelled = false

    /** Only one concatenation writes at a time; each is disk-bound. */
    private val importLock = Any()

    init {
        migrateLegacyRecordings(File(context.filesDir, "recordings"))
    }

    /**
     * Older builds wrote into `files/recordings`, outside the media root. Those
     * files are still referenced by saved projects, so move them in rather than
     * letting projects silently lose their source. Link-index entries that
     * point at the old location are repointed first, otherwise an existing
     * project would keep resolving to a deleted file.
     */
    private fun migrateLegacyRecordings(legacy: File) {
        if (!legacy.isDirectory) return
        val files = legacy.listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            val target = File(recordingsDir, file.name)
            if (!target.exists()) {
                val copied = runCatching { file.copyTo(target, overwrite = false) }
                    .onFailure { Log.w(TAG, "cannot migrate recording ${file.name}", it) }
                    .isSuccess
                if (!copied) continue
            }
            if (!target.isFile) continue
            media.repoint(file, target)
            file.delete()
        }
        legacy.delete()
    }

    // ------------------------------------------------------------------ //
    // JavaScript entry points
    // ------------------------------------------------------------------ //

    @JavascriptInterface
    fun invoke(id: String, method: String, argsJson: String) {
        worker.execute {
            val result = try {
                dispatch(method, parseArgs(argsJson))
            } catch (error: Throwable) {
                Log.d(TAG, "unhandled method: $method (${error.message})")
                onResolve(id, rejectPayload(error.message ?: "unimplemented: $method"))
                return@execute
            }
            // Capture answers slowly and sometimes minutes later (the consent
            // dialog is the user's to answer). A future keeps a pool thread
            // free while that is outstanding.
            if (result is java.util.concurrent.CompletableFuture<*>) {
                result.whenComplete { value, error ->
                    if (error != null) {
                        onReject(id, error.message ?: "request failed")
                    } else {
                        resolve(id, value)
                    }
                }
            } else {
                resolve(id, result)
            }
        }
    }

    private fun resolve(id: String, value: Any?) {
        val payload = toJson(value)
        if (payload != null) onResolve(id, payload) else onReject(id, "no result")
    }

    @JavascriptInterface
    fun getSync(method: String, argsJson: String): String? = try {
        toJson(dispatchSync(method, parseArgs(argsJson)))
    } catch (error: Throwable) {
        Log.w(TAG, "sync method failed: $method", error)
        null
    }

    @JavascriptInterface
    fun subscribe(channel: String) {
        subscriptions.add(channel)
    }

    /**
     * Opens a take that finished with no renderer to hand it to.
     *
     * Only a stop by the native fallback can leave one: the recording is
     * finished and saved, but the page that would have called
     * `setCurrentVideoPath` never ran. Called from [MainActivity] before the
     * first load, so the editor hydrates straight into that recording — the
     * same outcome as the renderer's own post-stop handoff.
     */
    fun adoptRecording(file: File) {
        currentVideo = file
        currentSession = sessionFor(file, hideOverlayCursor = false)
        projects.setCurrentPath(null)
    }

    // ------------------------------------------------------------------ //
    // Sync surface — four preload methods plus Android transport metadata
    // ------------------------------------------------------------------ //

    private fun dispatchSync(method: String, args: JSONArray): Any? = when (method) {
        // The shim reads this once to authorize /media and export requests.
        "getBridgeToken" -> tokenProvider()
        // The native recorder uses Android's default audio routing. Publishing
        // this device avoids a WebView getUserMedia prompt just to label the
        // microphone picker; recording permission is requested on capture start.
        "getAndroidMediaDevices" -> JSONArray().apply {
            if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) {
                put(JSONObject().apply {
                    put("deviceId", "default")
                    put("kind", "audioinput")
                    put("label", "Default microphone")
                    put("groupId", "android-default")
                })
            }
        }
        // preload returns "home" | "editor". One activity hosts both; the
        // dashboard lives inside the editor app and opens itself when there is
        // no current video, so the editor is always the entry point.
        "getEditorStartupView" -> "editor"

        // preload unwraps {success, value} to the raw value before the renderer
        // sees it, so the same unwrapping happens here.
        "getAppSetting" -> {
            val key = args.optString(0, "")
            if (settings.found(key)) {
                val value = TouchPresentation.coerceSetting(key, settings.get(key))
                migrateTouchLook(key, value)
            } else if (TouchPresentation.isEditorPreferencesKey(key)) {
                // Seed touch presentation on first run; otherwise the renderer
                // would apply its desktop defaults (cursor sprite on).
                TouchPresentation.seed()
            } else {
                null
            }
        }

        "getAppSettingWithStatus" -> {
            val key = args.optString(0, "")
            val found = settings.found(key)
            JSONObject().apply {
                put("success", true)
                put("found", found)
                put(
                    "value",
                    (if (found) migrateTouchLook(
                        key,
                        TouchPresentation.coerceSetting(key, settings.get(key)),
                    ) else null)
                        ?: JSONObject.NULL,
                )
            }
        }

        // preload returns a plain boolean.
        "setAppSetting" -> {
            val key = args.optString(0, "")
            // Persist the coerced blob so a renderer write that still carries
            // `showCursor: true` cannot undo the touch presentation on reload.
            settings.set(key, TouchPresentation.coerceSetting(key, args.opt(1)))
            if (TouchPresentation.isEditorPreferencesKey(key)) {
                // A renderer save means the editor owns its look from here on;
                // do not re-apply the one-time floating-frame migration.
                settings.set(TouchPresentation.LOOK_MIGRATION_KEY, true)
            }
            true
        }

        else -> throw UnsupportedOperationException("not a sync method: $method")
    }

    // ------------------------------------------------------------------ //
    // Async surface
    // ------------------------------------------------------------------ //

    private fun dispatch(method: String, args: JSONArray): Any? = when (method) {
        // Upstream returns null when there is no separate asset root; the
        // renderer then resolves assets against the page origin, which is us.
        "getAssetBasePath" -> null

        "listAssetDirectory" -> listAssetDirectory(args.optString(0, ""))

        "getPlatform" -> PLATFORM

        "getWindowChrome" -> JSONObject().apply {
            put("customControls", false)
            put("maximized", false)
            put("trafficLightsVisible", false)
        }

        // --- current video -------------------------------------------- //

        // Electron answers with an absolute filesystem path, and the renderer
        // treats it as one: `fromFileUrl` then `getLocalMediaUrl`. Returning a
        // served URL here would work for playback but would persist a URL —
        // which embeds this run's port — into every saved project.
        // Upstream answers `{success:false}` — no `path` — when nothing is open.
        "getCurrentVideoPath" -> {
            val file = currentVideo
            if (file == null) {
                JSONObject().put("success", false)
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("path", file.path)
                }
            }
        }

        // Sets the video AND the session, as upstream does: the recorder hands
        // over a bare path and the editor reads the session back to find the
        // webcam offset and presentation flags.
        "setCurrentVideoPath" -> {
            val file = resolveMediaArg(args.optString(0, "")).also { resolved ->
                if (resolved == null) Log.w(TAG, "setCurrentVideoPath: unresolved input")
            }
            val options = args.optJSONObject(1)
            currentVideo = file
            currentSession = file?.let {
                sessionFor(it, options?.optBoolean(HIDE_OVERLAY_CURSOR, false) == true)
            }
            // The open editor listens for this to re-read the session's assets
            // after a take is adopted (useInitialEditorSource); it ignores
            // events whose `videoPath` is not the file it is showing.
            currentSession?.let { emit(EVENT_SESSION_CHANGED, it) }
            // Without this the editor hydrates the project that was open before
            // the recording instead of the recording.
            if (options?.optBoolean(PRESERVE_PROJECT_PATH, false) != true) {
                projects.setCurrentPath(null)
            }
            JSONObject().apply {
                put("success", true)
                put("webcamPath", JSONObject.NULL)
            }
        }

        "clearCurrentVideoPath" -> {
            currentVideo = null
            currentSession = null
            projects.setCurrentPath(null)
            JSONObject().put("success", true)
        }

        // Upstream answers `{success:false}` when there is no session at all.
        "getCurrentRecordingSession" -> {
            val session = currentSession
            if (session == null) {
                JSONObject().put("success", false)
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("session", session)
                }
            }
        }

        "setCurrentRecordingSession" -> {
            val session = args.optJSONObject(0)
            val options = args.optJSONObject(1)
            val video = session?.optString("videoPath")?.takeIf { it.isNotEmpty() }
                ?.let { resolveMediaArg(it) }
            currentVideo = video
            currentSession = session?.let {
                JSONObject(it.toString()).apply {
                    // Keep the field set even when only a path was resolvable, so
                    // the editor never reads a half-populated session.
                    put("videoPath", video?.path ?: optString("videoPath", ""))
                }
            }
            currentSession?.let { emit(EVENT_SESSION_CHANGED, it) }
            if (options?.optBoolean(PRESERVE_PROJECT_PATH, false) != true) {
                projects.setCurrentPath(null)
            }
            JSONObject().put("success", true)
        }

        "getLocalMediaUrl" -> {
            val file = runCatching { File(args.optString(0, "")).canonicalFile }.getOrNull()
                ?.takeIf { it.isFile && readableByRenderer(it) }
            val url = file?.let { urlFor(it) }
            JSONObject().apply {
                put("success", url != null)
                put("url", url ?: JSONObject.NULL)
            }
        }

        // --- cursor telemetry (the auto-zoom input) -------------------- //

        "getCursorTelemetry" -> readTelemetry(args.optString(0, "").takeIf { it.isNotEmpty() })

        "setCursorTelemetry" -> writeTelemetry(
            args.optString(0, "").takeIf { it.isNotEmpty() },
            args.optJSONArray(1),
        )

        // --- projects --------------------------------------------------- //

        "listProjectFiles" -> projects.list()

        "saveProjectFile" -> projects.save(
            data = args.optJSONObject(0) ?: JSONObject(),
            suggestedName = args.optString(1, "").takeIf { it.isNotEmpty() },
            existingPath = args.optString(2, "").takeIf { it.isNotEmpty() },
            thumbnailDataUrl = args.optString(3, "").takeIf { it.isNotEmpty() },
        )

        "saveProjectFileNamed" -> projects.saveNamed(
            data = args.optJSONObject(0) ?: JSONObject(),
            projectName = args.optString(1, "Untitled project"),
            thumbnailDataUrl = args.optString(2, "").takeIf { it.isNotEmpty() },
            mode = args.optString(3, "").takeIf { it.isNotEmpty() },
        )

        "createProjectFile" -> projects.create(
            data = args.optJSONObject(0) ?: JSONObject(),
            thumbnailDataUrl = args.optString(1, "").takeIf { it.isNotEmpty() },
        )

        "loadCurrentProjectFile" -> projects.loadCurrent()

        // Upstream opens a file dialog here and takes no argument. Android has
        // no dialog: an explicit path (used by the verification harness and by
        // callers that already resolved one) loads that project, otherwise the
        // current project is returned, matching `load-current-project-file`.
        "loadProjectFile" -> {
            val path = args.optString(0, "")
                .takeIf { it.isNotEmpty() && !it.equals("null", true) }
            if (path != null) projects.load(path) else projects.loadCurrent()
        }

        // `relink-project-recording`: the project points at a recording that is
        // no longer on this device. Pick a replacement with SAF, verify it is
        // long enough for the saved edits, then persist the new path and return
        // the loaded project so the renderer can apply it.
        "relinkProjectRecording" -> {
            val projectPath = args.optString(0, "")
            val loaded = projects.load(projectPath)
            if (!loaded.optBoolean("success")) {
                loaded
            } else {
                val document = loaded.optJSONObject("project")
                val missing = document?.optString("videoPath", "").orEmpty()
                if (document == null || missing.isEmpty()) {
                    failure("The project has no recording to reconnect")
                } else if (runCatching { File(missing).isFile }.getOrDefault(false)) {
                    failure("The project's recording is already available. Open the project again.")
                } else {
                    DocumentPicker.openVideo(includeProjects = false).thenApply { selected ->
                        if (!selected.optBoolean("success")) {
                            JSONObject().apply {
                                put("success", false)
                                put("canceled", selected.optBoolean("canceled", false))
                                put("error", selected.optString("error", "No replacement recording was chosen"))
                            }
                        } else {
                            relinkProject(projectPath, document, selected.optString("path", ""))
                        }
                    }
                }
            }
        }

        "openProjectFileAtPath" -> projects.load(args.optString(0, ""))

        "getProjectPreview" -> projects.preview(args.optString(0, ""))

        "cacheProjectThumbnail" -> projects.cacheThumbnail(
            path = args.optString(0, ""),
            expectedRevision = args.optString(1, ""),
            dataUrl = args.optString(2, ""),
        )

        "openVideoFilePicker" -> DocumentPicker.openVideo(
            args.optJSONObject(0)?.optBoolean("includeProjects", false) == true,
        ).thenApply { selected ->
            if (selected.optBoolean("success") && selected.optString("kind") == "project") {
                val imported = projects.importSelected(
                    File(selected.optString("path")),
                    selected.optString("name", ""),
                )
                File(selected.optString("path")).delete()
                imported
            } else {
                if (selected.optBoolean("success")) projects.setCurrentPath(null)
                selected
            }
        }

        "openAudioFilePicker" -> DocumentPicker.openAudio()

        "renameLibraryProject" -> projects.rename(
            path = args.optString(0, ""),
            name = args.optString(1, "Untitled project"),
        )

        "trashProjectFiles" -> projects.trash(
            args.optJSONArray(0) ?: JSONArray(),
            args.optJSONObject(1),
        )

        "reviewProjectDeletion" -> projects.reviewDeletion(
            paths = args.optJSONArray(0) ?: JSONArray(),
            protectedSources = args.optJSONArray(1),
        )

        // Library cleanup and the Clips panel delete raw sources, not projects.
        "trashRecordingSources" -> trashRecordingSources(
            paths = args.optJSONArray(0) ?: JSONArray(),
            protectedSources = args.optJSONArray(1),
        )

        "findDuplicateRecordingSources" -> findDuplicateRecordingSources()

        // Android keeps recordings in app-private storage; there is no folder
        // to choose. Reporting `canceled` keeps the settings row honest and
        // avoids the error the renderer raises for a failed change.
        "chooseRecordingsDirectory" -> JSONObject().apply {
            put("success", false)
            put("canceled", true)
            put("path", recordingsDir.path)
            put("message", "Recordings are stored inside Recordly on Android.")
        }

        // The renderer shows its permission banner when this event arrives. The
        // actual runtime prompts happen when a take starts (CaptureSession), so
        // this only has to notify the page.
        "showRecordingPermissions" -> {
            emit("onRecordingPermissionsRequested", JSONObject())
            JSONObject().put("success", true)
        }

        "getProjectsDirectory" -> JSONObject().apply {
            put("success", true)
            put("path", projects.rootPath())
        }

        "getRecordingsDirectory" -> JSONObject().apply {
            put("success", true)
            put("path", recordingsDir.path)
            put("isDefault", true)
        }

        // --- recording preferences ------------------------------------- //

        // Upstream answers with FLAT keys, not a nested `preferences` object:
        // `useRecorderPreferences.ts` reads `result.microphoneEnabled` directly.
        "getRecordingPreferences" -> {
            val stored = settings.get(PREF_RECORDING) as? JSONObject
            JSONObject().apply {
                put("success", true)
                put("microphoneEnabled", stored?.optBoolean("microphoneEnabled", false) ?: false)
                put("systemAudioEnabled", stored?.optBoolean("systemAudioEnabled", false) ?: false)
                put("webcamEnabled", stored?.optBoolean("webcamEnabled", false) ?: false)
                for (deviceKey in DEVICE_KEYS) {
                    val id = stored?.optString(deviceKey).orEmpty()
                    if (id.isNotEmpty()) put(deviceKey, id)
                }
            }
        }

        // `set-recording-preferences` receives a partial patch and merges it.
        "setRecordingPreferences" -> {
            args.optJSONObject(0)?.let { patch -> mergePreferences(patch) }
            JSONObject().put("success", true)
        }

        // Stored separately from the recording preferences, as upstream keeps
        // them in their own file. The default is 3 seconds.
        "getCountdownDelay" -> JSONObject().apply {
            put("success", true)
            put("delay", (settings.get(PREF_COUNTDOWN_DELAY) as? Number)?.toInt() ?: 3)
        }

        "setCountdownDelay" -> {
            settings.set(PREF_COUNTDOWN_DELAY, args.optDouble(0, 3.0))
            JSONObject().put("success", true)
        }

        // No environment variable exists to override the profile here, so this
        // is `getBrowserMicrophoneProfileFromEnv()` with nothing set — which is
        // also the default it falls back to.
        "getRecordingAudioLabConfig" -> JSONObject().apply {
            put("browserMicrophoneProfile", "no-agc")
            put("requestedBrowserMicrophoneProfile", JSONObject.NULL)
        }

        // --- capture: source selection ---------------------------------- //
        // Android has exactly one capturable surface: the device display.
        // MediaProjection captions the whole screen, not an app or a window, so
        // the selector upstream shows for desktop screens/windows has one row,
        // and it is selected by default — `prepareRecordingStart` refuses to
        // run without a selected source, so the renderer never reaches the
        // consent dialog otherwise.

        "getSources" -> JSONArray().put(screenSource())

        "getSelectedSource" -> screenSource()

        // Echoing the source back is what upstream does after validating it;
        // the selector compares `returned.id === source.id`.
        "selectSource" -> screenSource()

        // The HUD's "pick on screen" button. Upstream opens a picker window
        // over every display; with a single display there is nothing to pick,
        // so this answers with the only target. `source` is what the caller
        // syncs into its selection, and `canceled:false` keeps it from
        // treating the answer as a dismissal.
        "pickCaptureTarget" -> JSONObject().apply {
            put("success", true)
            put("canceled", false)
            put("source", screenSource())
        }

        "showSourceHighlight" -> JSONObject().put("success", true)

        // Routes `startNativeRecorder` into the native path. With the browser
        // capture path selected instead, the renderer would call
        // `getDisplayMedia`, which a WebView cannot do.
        "isNativeWindowsCaptureAvailable" -> JSONObject().put("available", true)

        // --- capture: recording ---------------------------------------- //

        "startNativeScreenRecording" -> {
            val options = args.optJSONObject(1)
            CaptureSession.start(
                appContext,
                wantsMicrophone = options?.optBoolean("capturesMicrophone", false) ?: false,
            )
        }

        "stopNativeScreenRecording" -> CaptureSession.stopAsync()

        // A second pause or resume is a no-op upstream, not an error: the HUD
        // and the countdown path can both land here for the same boundary.
        "pauseNativeScreenRecording" -> CaptureSession.pause()

        "resumeNativeScreenRecording" -> CaptureSession.resume()

        // A take that ended without the renderer's stop path (the system
        // revoked the projection) is finalized natively; this waits for that
        // file and hands back its path so the interrupted handler can adopt it.
        "recoverNativeScreenRecording" -> CaptureSession.recoverAsync()

        "deleteRecordingFile" -> deleteRecording(args.optString(0, ""))

        // --- appending a recording to the open timeline ------------------ //
        // Upstream rewrites both sources with ffmpeg and concatenates them.
        // Both sides here were encoded by this app, so the same operation is a
        // sample-level remux: copy both MP4 sample streams into one container,
        // shifting the second by the first's duration. See [RecordingImport].

        "importRecording" -> importRecording(
            currentRaw = args.optString(0, ""),
            recordingRaw = args.optString(1, ""),
        )

        // The renderer calls this twice per successful import: once to discard
        // abandoned candidates (keep = the one it accepted) and once with
        // `commit = true` to stop tracking the accepted file as temporary.
        "finishRecordingImport" -> finishRecordingImport(
            keepPath = args.optString(0, ""),
            commit = args.optBoolean(1, false),
        )

        "cancelRecordingImport" -> {
            importCancelled = true
            JSONObject().put("success", true)
        }

        // --- recording library and media inspection -------------------- //

        // `RecordingLibraryEntry[]`, newest first. `url` is what the library
        // plays from; `path` is what it persists into a project.
        "listRecordings" -> JSONObject().apply {
            put("success", true)
            put("value", recordingEntries(args.optBoolean(0, false)))
        }

        // Companion audio played alongside the video. Native microphone audio
        // is already in the MP4 and needs no separate fallback source.
        "getVideoAudioFallbackPaths" -> audioFallbackPaths(args.optString(0, ""))

        // ffprobe answers these three questions upstream; MediaExtractor and
        // MediaMetadataRetriever answer them here without a subprocess.
        "probeNativeVideoMetadata" -> probeVideoMetadata(args.optString(0, ""))

        // --- files, thumbnails, desktop background --------------------- //

        // The renderer asks for a *data URL* (`thumbnail.ts` base64-encodes the
        // JPEG), so the still is extracted and cropped here and travels inline.
        "getRecordingThumbnail" -> {
            val thumbnail = recordingThumbnail(args.optString(0, ""))
            JSONObject().apply {
                put("success", thumbnail != null)
                put("value", thumbnail ?: JSONObject.NULL)
                if (thumbnail == null) put("error", "Recording has no preview frame")
            }
        }

        // "Reveal in folder" does not exist on Android; opening the file in
        // whatever app claims it is the closest honest behaviour, and the only
        // one a user can act on.
        "revealInFolder" -> {
            val file = runCatching { File(args.optString(0, "")).canonicalFile }.getOrNull()
            if (file == null || !file.isFile) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "File is no longer available")
                }
            } else {
                val opened = openWithSystem(file)
                JSONObject().apply {
                    put("success", opened)
                    // Two causes share this answer: nothing claims the type,
                    // or the file is outside what the FileProvider shares.
                    if (!opened) put("error", "This file cannot be opened from Recordly")
                }
            }
        }

        // There is no file manager entry point for app-private storage, and
        // the recordings/projects screens *are* the library here. The failure
        // is truthful rather than a silent no-op that looks broken.
        "openRecordingsFolder" -> JSONObject().apply {
            put("success", false)
            put("path", recordingsDir.path)
            put("message", "Recordly keeps recordings inside the app on Android; open them from the library.")
        }

        "openProjectsDirectory" -> JSONObject().apply {
            put("success", false)
            put("path", projects.rootPath())
            put("message", "Recordly keeps projects inside the app on Android.")
        }

        // Bytes for the editor's asset pipeline. Like the wallpaper thumbnail,
        // this answers with a served URL and the shim fetches the bytes, so the
        // JSON transport stays text-only.
        "readLocalFile" -> {
            val file = runCatching { File(args.optString(0, "")).canonicalFile }.getOrNull()
                ?.takeIf { it.isFile && readableByRenderer(it) }
            val url = file?.let { urlFor(it) }
            JSONObject().apply {
                put("success", url != null)
                put("url", url ?: JSONObject.NULL)
                if (url == null) put("error", "File is outside Recordly's approved locations")
            }
        }

        // The system wallpaper, snapshotted to one immutable PNG that the
        // editor can reference like any other wallpaper. Absent on devices or
        // policy profiles that refuse the drawable, which the editor already
        // treats as "no desktop wallpaper".
        "getDesktopWallpaper" -> desktopWallpaper()

        // --- export ---------------------------------------------------- //
        // The bytes themselves never come through here: the shim POSTs them to
        // the loopback server (`/__recordly/export`), which hands the socket
        // stream to ExportStreams. These four calls are the file's lifecycle.

        "openExportStream" -> ExportStreams.open(
            args.optJSONObject(0)?.optString("extension") ?: "mp4",
        )

        "closeExportStream" -> ExportStreams.close(
            streamId = args.optString(0, ""),
            abort = args.optJSONObject(1)?.optBoolean("abort", false) == true,
        )

        "finalizeExportedVideo" -> ExportStreams.finalize(
            payload = args.optJSONObject(0) ?: JSONObject(),
            exportsDir = exportsDir,
        )

        "discardExportedTemp" -> ExportStreams.discard(args.optString(0, ""))

        // A video wallpaper chosen through the renderer's own file input. Its
        // bytes arrived over the export channel; this moves the finished temp
        // file into app storage and answers with an absolute path, exactly as
        // `import-wallpaper-video` does on desktop.
        "finishWallpaperImport" -> finishWallpaperImport(
            streamId = args.optString(0, ""),
            name = args.optString(1, ""),
        )

        // Library removal is a rename into a hidden bundle, so Undo can put the
        // recording back with its sidecars. The bundle is deleted when the next
        // removal happens (and swept on startup if the process died between),
        // which is this host's equivalent of upstream's Trash.
        "setRecordingsRemoved" -> setRecordingsRemoved(
            args.optJSONArray(0),
            args.optBoolean(1, false),
        )

        // --- app plumbing ---------------------------------------------- //

        "getAppVersion" -> {
            @Suppress("DEPRECATION")
            val installed = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            installed.versionName.orEmpty()
        }

        // Upstream restricts this to web pages and its two support addresses;
        // the allowlist is kept, because everything else is a way to launch an
        // arbitrary intent from renderer-controlled text.
        "openExternalUrl" -> {
            val raw = args.optString(0, "")
            val scheme = runCatching { Uri.parse(raw).scheme?.lowercase() }.getOrNull()
            val allowed = (scheme == "http" || scheme == "https") || raw in SUPPORT_MAILTO
            if (!allowed) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Blocked non-HTTP URL: ${scheme ?: "none"}")
                }
            } else {
                val opened = openSettings(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                JSONObject().apply {
                    put("success", opened)
                    if (!opened) put("error", "No app can open this link")
                }
            }
        }

        "writeClipboardText" -> {
            val clipboard = appContext.getSystemService(android.content.ClipboardManager::class.java)
            runCatching {
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Recordly", args.optString(0, "")),
                )
            }.onFailure { Log.w(TAG, "cannot write to the clipboard", it) }
            null
        }

        // The app window is always full screen: there is no chrome to hide.
        "isWindowFullscreen" -> false

        // `isHudInEditorMode`: an editor surface is open and nothing is being
        // captured. The app *is* the editor when idle.
        "getEditorMode" -> !CaptureSession.isRecording()

        // Only rendered when `getWindowChrome` says `customControls`, which is
        // false here — no title bar exists to minimize or close. Answered so a
        // stray call cannot throw.
        "windowControl" -> JSONObject().put("success", true)

        // `setEditorModeChanged`-style bookkeeping for the close prompt, which
        // does not exist on Android.
        "setHasUnsavedChanges" -> JSONObject().put("success", true)

        "setExportInProgress" -> JSONObject().put("success", true)

        // Not a Linux desktop; only consulted after `getPlatform` says linux.
        "getLinuxWindowSystem" -> null

        // Key bindings are stored whole, exactly as `get-`/`save-shortcuts`
        // does with its JSON file; merging with defaults is the renderer's job.
        "getShortcuts" -> settings.get(PREF_SHORTCUTS)

        "saveShortcuts" -> {
            settings.set(PREF_SHORTCUTS, args.opt(0))
            JSONObject().put("success", true)
        }

        // Desktop presence settings. `hideWhileRecording` is the one that maps
        // to Android directly: this app always leaves the foreground while a
        // take runs, because that is what makes the capture show the user's
        // work rather than the recorder. The dock/taskbar toggle has no
        // Android equivalent and is stored without effect.
        "getApplicationPreferences" -> {
            val stored = storedPreferences()
            applicationPreferencesOf(
                stored.optBoolean("showInDock", true),
                stored.optBoolean("hideWhileRecording", true),
            )
        }

        "setApplicationPreferences" -> {
            val patch = args.optJSONObject(0)
            val keys = patch?.keys()?.asSequence()?.toList() ?: emptyList()
            when {
                patch == null || keys.any { it !in APPLICATION_KEYS || patch.opt(it) !is Boolean } ->
                    applicationPreferencesOf(
                        storedPreferences().optBoolean("showInDock", true),
                        storedPreferences().optBoolean("hideWhileRecording", true),
                        "Invalid application preferences.",
                    )

                patch.optBoolean("launchAtLogin", false) ->
                    applicationPreferencesOf(
                        storedPreferences().optBoolean("showInDock", true),
                        storedPreferences().optBoolean("hideWhileRecording", true),
                        "Launch at login is unavailable.",
                    )

                else -> {
                    val stored = JSONObject(storedPreferences().toString())
                    keys.forEach { key -> stored.put(key, patch.optBoolean(key)) }
                    settings.set(PREF_APPLICATION, stored)
                    applicationPreferencesOf(
                        stored.optBoolean("showInDock", true),
                        stored.optBoolean("hideWhileRecording", true),
                    )
                }
            }
        }

        "getLastNativeCaptureDiagnostics" -> JSONObject().apply {
            put("success", false)
        }

        // The delay is in SECONDS (`countdownController.start(seconds)`), and
        // the recorder button draws the ticks, so they have to be emitted.
        "startCountdown" -> Countdown.start(args.optDouble(0, 0.0)) { channel, payload ->
            emit(channel, payload)
        }

        "cancelCountdown" -> Countdown.cancel()

        "getActiveCountdown" -> Countdown.active()

        // --- capture: permissions -------------------------------------- //
        // Reported truthfully, which for screen capture means "granted": there
        // is no standing grant on Android, and the consent dialog appears at
        // the start of every take. Claiming "denied" here would make the
        // renderer refuse to record.

        "getScreenRecordingPermissionStatus" -> JSONObject().apply {
            put("success", true)
            put("status", "granted")
        }

        "getAccessibilityPermissionStatus" -> JSONObject().apply {
            put("success", true)
            // Truthful, unlike upstream's off-darwin `true`: auto-zoom is
            // driven by this service, and its state is what the banner reports.
            put("trusted", TelemetryAccessibilityService.isEnabled())
            put("prompted", false)
        }

        "requestAccessibilityPermission" -> {
            // Android cannot grant this programmatically; the user toggles the
            // service on its own settings page, so send them there.
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            JSONObject().apply {
                put("success", true)
                put("trusted", TelemetryAccessibilityService.isEnabled())
                put("prompted", false)
            }
        }

        // Upstream answers "unknown" off darwin because its only readers are
        // darwin-gated. Here the grant is real and cheap to read, and the
        // native permission banner needs it, so it is answered truthfully.
        "getMediaPermissionStatus" -> JSONObject().apply {
            put("success", true)
            put("microphone", permissionState(Manifest.permission.RECORD_AUDIO))
            put("camera", permissionState(Manifest.permission.CAMERA))
        }

        "requestMediaPermission" -> {
            // The argument is a device name ("microphone" | "camera"), not an
            // Android permission. Prompting needs a foreground activity; the
            // microphone is requested when a take starts, and the camera is
            // not used by this host, so this reports the standing grant.
            val permission = when (args.optString(0, "")) {
                "microphone" -> Manifest.permission.RECORD_AUDIO
                "camera" -> Manifest.permission.CAMERA
                else -> null
            }
            if (permission == null) {
                JSONObject().apply {
                    put("success", false)
                    put("error", "Invalid media permission device")
                }
            } else {
                JSONObject().apply {
                    put("success", true)
                    put("granted", permissionState(permission) == "granted")
                }
            }
        }

        "openScreenRecordingPreferences" -> {
            // Capture is controlled by the notification, so its settings are
            // the ones that can silently break it.
            openSettings(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName),
            )
            JSONObject().put("success", true)
        }

        "openAccessibilityPreferences" -> {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            JSONObject().put("success", true)
        }

        "openMediaPermissionPreferences" -> {
            // Android keeps per-permission grants on the app's own page; there
            // is no microphone- or camera-specific settings screen.
            openSettings(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", appContext.packageName, null),
                ),
            )
            JSONObject().put("success", true)
        }

        // --- cursor capture clock -------------------------------------- //
        // Upstream trims telemetry at these boundaries; here the same clock
        // drives both the sidecar times and the video segment offsets.

        "pauseCursorCapture" -> {
            TouchTelemetry.pauseClock()
            JSONObject().put("success", true)
        }

        "resumeCursorCapture" -> {
            TouchTelemetry.resumeClock()
            JSONObject().put("success", true)
        }

        // --- recording session lifecycle -------------------------------- //

        "reserveRecordingSession" -> JSONObject().apply {
            put("success", true)
            put("message", "")
            put("token", "android-${++reservationCounter}")
        }

        "releaseRecordingStartup" -> JSONObject().put("success", true)

        "finishRecordingStartup" -> JSONObject().put("success", true)

        "setRecordingState" -> {
            val recording = args.optBoolean(0, false)
            emit(EVENT_RECORDING_STATE, JSONObject().put("recording", recording))
            JSONObject().apply {
                put("success", true)
                put("recording", recording)
            }
        }

        // --- onboarding ------------------------------------------------ //
        // Upstream opens a dedicated Electron window for onboarding. Android
        // has no second window, and this build has no cloud sign-in
        // (`configured` is false), so there is nothing to show. Answering
        // `false` is NOT neutral: the renderer reads it as "the host could not
        // open a window" and paints the sign-in dialog INLINE on top of the
        // editor, covering the whole UI. `true` means the host handled it.
        "showOnboardingWindow" -> true

        "closeOnboardingWindow" -> true

        // --- recording → editor handoff -------------------------------- //
        // Upstream captures in one window and hands the result to a second,
        // dedicated editor window; these three calls are that handshake.
        // `waitForRecordingEditor` is answered empty on purpose: the renderer
        // reads `error` off it and treats a present value as a fatal load
        // failure (`useInitialEditorSource` throws it), which would replace the
        // whole editor with "We couldn't open this file".
        "waitForRecordingEditor" -> JSONObject()

        // Upstream opens the loading editor as its own window and, crucially,
        // clears the current project path — otherwise the editor would hydrate
        // the project that was open before the take instead of the new video.
        "openLoadingRecordingEditor" -> {
            projects.setCurrentPath(null)
            JSONObject().put("success", true)
        }

        // The two ways the recorder hands over. Both are a surface switch, which
        // is also the hydration trigger: `setCurrentVideoPath` has already run,
        // so the reloaded editor opens the recording.
        "finishRecordingEditor" -> {
            CaptureSession.showEditor()
            null
        }

        "switchToEditor" -> {
            CaptureSession.showEditor()
            null
        }

        // --- recording surfaces ---------------------------------------- //
        // Each of these is a separate Electron window; here they are surfaces of
        // the one WebView, chosen by reload.

        "showRecordingHud" -> {
            CaptureSession.showHud()
            JSONObject().put("success", true)
        }

        "showProjectDashboard" -> {
            CaptureSession.showEditor()
            JSONObject().put("success", true)
        }

        // The selector is a one-row list (see `getSources`), so there is nothing
        // to choose. Answering success keeps the caller from treating this as a
        // failure it should surface.
        "openSourceSelector" -> JSONObject().put("success", true)

        // --- recorder HUD ---------------------------------------------- //
        // Upstream's HUD is a transparent, always-on-top window with regions
        // that ignore the mouse. Here it is an ordinary full-screen page, so
        // the window-manipulation calls have nothing to do. They still answer
        // success: the callers fire them without awaiting, and a rejection
        // would surface as an error the user cannot act on.

        "hudOverlayRendererReady" -> null
        "hudOverlaySetIgnoreMouse" -> null
        "hudOverlaySetSourceSelectionActive" -> null
        "hudOverlayDrag" -> null
        "hudOverlaySetInteractiveBounds" -> null
        "hudOverlaySetWebcamPreviewVisible" -> null

        // Click-through regions need a native transparent window; a WebView
        // filling the screen receives every touch.
        "getHudOverlayMousePassthroughSupported" -> JSONObject().apply {
            put("success", true)
            put("supported", false)
        }

        // Leaving the recording surface. After a take `switchToEditor` has
        // already done this and the switch is idempotent; on the cancel path
        // this is the only signal, and the dashboard is the right destination.
        "hudOverlayClose" -> {
            CaptureSession.showEditor()
            JSONObject().put("success", true)
        }

        // "Minimize the HUD" cannot leave a floating bar behind there; the
        // nearest equivalent is returning to the app, from where "Record new"
        // reopens this surface.
        "hudOverlayHide" -> {
            CaptureSession.showEditor()
            JSONObject().put("success", true)
        }

        // Real on Android: FLAG_SECURE omits the window from the capture, which
        // is exactly what upstream's `setContentProtection` achieves. Disabling
        // it is the "record my own app" mode.
        "getHudOverlayCaptureProtection" -> JSONObject().apply {
            put("success", true)
            put("enabled", hudCaptureProtectionEnabled())
        }

        "setHudOverlayCaptureProtection" -> {
            val enabled = args.optBoolean(0, true)
            settings.set(SettingsStore.PREF_HUD_CAPTURE_PROTECTION, enabled)
            JSONObject().apply {
                put("success", true)
                put("enabled", enabled)
            }
        }

        // Upstream resolves the bare boolean `true`.
        "setAppLocale" -> true

        // Upstream resolves with the suggested BASENAME, or null when the name is
        // unusable — not a `{success, path}` envelope. `useExportRunner.ts` feeds
        // the value straight into the export session as a file name.
        "suggestExportFileName" -> suggestExportName(args.optString(0, ""))

        // `assetPath.ts` passes a web path (`/wallpapers/<name>`) because there
        // is no file:// asset base here, and expects raw JPEG bytes back. Bytes
        // cannot cross the JSON transport, so this writes the frame under the
        // media root and answers with a URL; the shim fetches it into the real
        // Uint8Array the renderer requires. Without this the renderer re-asks on
        // every render of the wallpaper list — it only caches on success.
        "generateWallpaperThumbnail" -> generateWallpaperThumbnail(args.optString(0, ""))

        else -> throw UnsupportedOperationException(method)
    }

    // ------------------------------------------------------------------ //
    // Capture: sources, permissions, recording files
    // ------------------------------------------------------------------ //

    /**
     * The one capturable surface, in the shape `sources.ts` builds for a
     * display: `{id, name, originalName, display_id, thumbnail, appIcon,
     * sourceType}`.
     *
     * `thumbnail` is null and stays null: a display cannot be captured before
     * the MediaProjection consent dialog is accepted, which only happens at
     * `startNativeScreenRecording`, and nothing on the win32 path draws it —
     * `SourceSelector` renders the overlay picker there, which reads only `id`.
     */
    private fun screenSource(): JSONObject = JSONObject().apply {
        put("id", SCREEN_SOURCE_ID)
        put("name", SCREEN_SOURCE_NAME)
        put("originalName", SCREEN_ORIGINAL_NAME)
        put("display_id", SCREEN_DISPLAY_ID)
        put("thumbnail", JSONObject.NULL)
        put("appIcon", JSONObject.NULL)
        put("sourceType", "screen")
    }

    /**
     * The session `set-current-video-path` synthesises when the recording has
     * no manifest of its own — which is every take here: the only sidecar this
     * build writes is the cursor telemetry, and it is read back by path.
     */
    private fun sessionFor(video: File, hideOverlayCursor: Boolean): JSONObject =
        JSONObject().apply {
            put("videoPath", video.path)
            put("webcamPath", JSONObject.NULL)
            put("timeOffsetMs", 0)
            put("hideOverlayCursorByDefault", hideOverlayCursor)
        }

    /**
     * `"granted" | "denied"`, the two values this host can distinguish.
     *
     * A runtime permission that has never been asked for reads as "denied";
     * the system permission dialog is the only place it can become "granted",
     * and it appears when a take asks for the microphone.
     */
    private fun permissionState(permission: String): String =
        if (appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }

    /**
     * Opens one of the app's own settings screens.
     *
     * This runs on a bridge thread with the application context, so the intent
     * has to start a new task. Both destinations accept that: the app's details
     * page and its notification settings are system activities.
     */
    private fun openSettings(intent: Intent): Boolean = runCatching {
        appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Log.w(TAG, "cannot open ${intent.action}", it)
    }.isSuccess

    /**
     * `trash-recording-sources`: delete library recordings and their sidecars,
     * refusing paths outside the recordings directory and anything the renderer
     * listed as protected (a project reference or the open editor's source).
     */
    private fun trashRecordingSources(paths: JSONArray, protectedSources: JSONArray?): JSONObject {
        if (CaptureSession.isRecording()) {
            return failure("Stop recording before deleting source files")
        }
        val dir = runCatching { recordingsDir.canonicalFile }.getOrNull()
            ?: return failure("Recordings directory is unavailable")
        val protected = mutableSetOf<String>()
        currentVideo?.let { video -> runCatching { video.canonicalPath }.getOrNull()?.let(protected::add) }
        if (protectedSources != null) {
            for (index in 0 until protectedSources.length()) {
                val raw = protectedSources.optString(index, "")
                if (raw.isEmpty()) continue
                runCatching { File(raw).canonicalPath }.getOrNull()?.let(protected::add)
            }
        }

        val deleted = JSONArray()
        val errors = JSONArray()
        for (index in 0 until paths.length()) {
            val raw = paths.optString(index, "")
            if (raw.isEmpty()) continue
            val file = runCatching { File(raw).canonicalFile }.getOrNull()
            if (file == null || file.parentFile != dir || !file.isFile) {
                errors.put("Not a library recording: $raw")
                continue
            }
            if (file.path in protected) {
                errors.put("Kept ${file.name}: used by a saved project or the current editor")
                continue
            }
            discardRecordingFiles(file)
            if (file.exists()) {
                errors.put("Could not delete ${file.name}")
            } else {
                deleted.put(raw)
            }
        }
        return JSONObject().apply {
            put("success", true)
            put("value", JSONObject().apply {
                put("deleted", deleted)
                put("errors", errors)
            })
        }
    }

    /**
     * `find-duplicate-recording-sources`: content hashes, never names or dates.
     * Size buckets keep hashing to candidates that could plausibly match.
     */
    private fun findDuplicateRecordingSources(): JSONObject {
        if (CaptureSession.isRecording()) {
            return failure("Stop recording before scanning source files")
        }
        val extensions = setOf("mp4", "mov", "webm", "mkv", "m4v", "wav", "m4a", "mp3", "ogg", "flac")
        val bySize = mutableMapOf<Long, MutableList<File>>()
        for (file in recordingsDir.listFiles() ?: emptyArray()) {
            if (!file.isFile || file.length() <= 0L) continue
            if (file.extension.lowercase() !in extensions) continue
            bySize.getOrPut(file.length()) { mutableListOf() }.add(file)
        }
        val groups = JSONArray()
        for ((_, files) in bySize) {
            if (files.size < 2) continue
            val byHash = mutableMapOf<String, MutableList<File>>()
            for (file in files) {
                val hash = sha256(file) ?: continue
                byHash.getOrPut(hash) { mutableListOf() }.add(file)
            }
            for (matches in byHash.values) {
                if (matches.size < 2) continue
                groups.put(JSONArray(matches.map { it.path }))
            }
        }
        return JSONObject().apply {
            put("success", true)
            put("value", groups)
        }
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }.getOrNull()

    /** Persists the relinked path and returns the project shape the renderer applies. */
    private fun relinkProject(projectPath: String, document: JSONObject, selectedRaw: String): JSONObject {
        val selected = runCatching { File(selectedRaw).canonicalFile }.getOrNull()
            ?: return failure("The selected recording is not readable")
        if (!selected.isFile) return failure("The selected recording is not readable")

        val metadata = probeVideoMetadata(selected.path).optJSONObject("metadata")
            ?: return failure("The selected recording is not a playable video")
        val durationSeconds = metadata.optDouble("duration", 0.0)
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) {
            return failure("The selected recording is not a playable video")
        }
        val frameRate = metadata.optDouble("frameRate", 60.0)
            .takeIf { it.isFinite() && it > 0.0 } ?: 60.0
        val toleranceMs = minOf(50.0, 1000.0 / frameRate)
        if (durationSeconds * 1000.0 + toleranceMs < requiredDurationMs(document)) {
            return failure("The selected recording is shorter than the project's edits require")
        }

        val updated = JSONObject(document.toString())
        updated.put("videoPath", selected.path)
        val saved = projects.save(
            data = updated,
            suggestedName = null,
            existingPath = projectPath,
            thumbnailDataUrl = null,
        )
        if (!saved.optBoolean("success")) return saved
        return projects.load(projectPath)
    }

    /**
     * `requiredRecordingDurationMs`: the furthest timeline point the saved
     * clips and trims reference, so a shorter replacement cannot silently
     * truncate edits.
     */
    private fun requiredDurationMs(document: JSONObject): Double {
        var required = 0.0
        fun visit(value: Any?) {
            when (value) {
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                is JSONObject -> {
                    val start = value.optDouble("startMs", Double.NaN)
                    val end = value.optDouble("endMs", Double.NaN)
                    if (start.isFinite() && end.isFinite()) {
                        val sourceStart = value.optDouble("sourceStartMs", start)
                        val speed = value.optDouble("speed", 1.0)
                            .takeIf { it.isFinite() && it > 0.0 } ?: 1.0
                        val computed = sourceStart + maxOf(0.0, end - start) * speed
                        if (computed.isFinite()) required = maxOf(required, computed)
                    }
                    val sourceMax = value.optDouble("sourceMaxMs", Double.NaN)
                    if (sourceMax.isFinite()) required = maxOf(required, sourceMax)
                    val keys = value.keys()
                    while (keys.hasNext()) visit(value.opt(keys.next()))
                }
            }
        }
        val editor = document.optJSONObject("editor")
        visit(editor?.opt("clipRegions"))
        visit(editor?.opt("trimRegions"))
        return required
    }

    /**
     * Mirrors `delete-recording-file`: only a file this app wrote, inside the
     * recordings directory, may be deleted, and the deletion takes the sidecars
     * with it. Canonical paths are compared so `..` cannot escape the directory.
     */
    private fun deleteRecording(raw: String): JSONObject {
        fun rejection(): JSONObject {
            Log.w(TAG, "refusing to delete '$raw': not an app-generated recording")
            return JSONObject().apply {
                put("success", false)
                put("error", DELETE_REJECTED)
            }
        }

        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return rejection()
        val dir = runCatching { recordingsDir.canonicalFile }.getOrNull() ?: return rejection()
        if (!file.path.startsWith(dir.path + File.separator)) return rejection()
        if (!file.name.startsWith(RECORDING_PREFIX)) return rejection()

        discardRecordingFiles(file)
        val current = currentVideo?.let { runCatching { it.canonicalFile }.getOrNull() }
        if (current != null && current.path == file.path) {
            currentVideo = null
            currentSession = null
        }
        return JSONObject().put("success", true)
    }

    /**
     * The file set `discardFiles.ts` removes, with the sidecar suffixes kept
     * whole rather than trimmed to what this build writes today: the next
     * increment adds audio tracks, and a partial delete now would orphan them.
     */
    private fun discardRecordingFiles(video: File) {
        val base = video.path.substringBeforeLast('.')
        val targets = buildList {
            add(video.path)
            add(video.path + CURSOR_SIDECAR_SUFFIX)
            for (suffix in DISCARD_SUFFIXES) add(base + suffix)
        }
        for (target in targets) {
            val file = File(target)
            if (file.exists() && !file.delete()) Log.w(TAG, "could not delete ${file.path}")
        }
    }

    // ------------------------------------------------------------------ //
    // Appending a recording to the open timeline
    // ------------------------------------------------------------------ //

    /**
     * `import-recording`: join [recordingRaw] after [currentRaw] into a new
     * immutable source, in the shape `RecordingImportResult` describes.
     *
     * The output lives under `.recordly-media`, exactly as upstream's normalized
     * copy does: it is a scratch artifact referenced by the project, not a
     * library recording, and must not appear in the library listing.
     */
    private fun importRecording(currentRaw: String, recordingRaw: String): JSONObject {
        if (currentRaw.isEmpty() || recordingRaw.isEmpty()) {
            return failure("Invalid recording selection")
        }
        if (CaptureSession.isRecording()) {
            return failure("Stop recording before adding a video")
        }
        val current = resolveMediaArg(currentRaw)
            ?: return failure("The current video is no longer available")
        val recording = resolveMediaArg(recordingRaw)
            ?: return failure("The selected recording is no longer available")

        synchronized(importLock) {
            val dir = media.ownedFile(IMPORT_DIR).apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val output = File(dir, "import-$stamp-${java.util.UUID.randomUUID().toString().take(6)}.mp4")
            importCancelled = false

            val result = try {
                RecordingImport.combine(current, recording, output) { importCancelled }
            } catch (error: RecordingImport.Failure) {
                discardImportOutput(output)
                return failure(error.message ?: "Could not combine the recordings")
            } catch (error: Throwable) {
                Log.w(TAG, "could not combine recordings", error)
                discardImportOutput(output)
                return failure(error.message ?: "Could not combine the recordings")
            }

            val url = urlFor(result.file)
            if (url == null) {
                discardImportOutput(output)
                return failure("Could not expose the combined video")
            }

            pendingImports.add(output.path)
            // The editor's telemetry lookups fall back to the active source;
            // the joined file owns the merged sidecar, so make it current.
            currentVideo = output
            currentSession = sessionFor(output, hideOverlayCursor = false)

            return JSONObject().apply {
                put("success", true)
                put("value", JSONObject().apply {
                    put("path", result.file.path)
                    put("url", url)
                    put("sourceStartMs", result.sourceStartMs)
                    put("durationMs", result.durationMs)
                    put("totalDurationMs", result.totalDurationMs)
                })
            }
        }
    }

    /**
     * `finish-recording-import`: drop every temporary output except [keepPath].
     * A `commit` call only stops tracking the kept file — it was accepted by
     * the project and must survive.
     */
    private fun finishRecordingImport(keepPath: String, commit: Boolean): JSONObject {
        for (path in pendingImports.toList()) {
            if (path == keepPath) {
                if (commit) pendingImports.remove(path)
                continue
            }
            discardImportOutput(File(path))
            pendingImports.remove(path)
        }
        return JSONObject().put("success", true)
    }

    private fun discardImportOutput(file: File) {
        if (file.exists() && !file.delete()) Log.w(TAG, "could not delete ${file.name}")
        val sidecar = File(file.path + CURSOR_SIDECAR_SUFFIX)
        if (sidecar.exists()) sidecar.delete()
    }

    // ------------------------------------------------------------------ //
    // Recording library and media inspection
    // ------------------------------------------------------------------ //

    /** One sidecar naming scheme, e.g. macOS `.system.m4a` + `.mic.m4a`. */
    private class CompanionLayout(
        val platform: String,
        val systemSuffix: String,
        val micSuffix: String,
    )

    /** A layout that has at least one usable file for this recording. */
    private class CompanionCandidate(
        val platform: String,
        val systemPath: String,
        val micPath: String,
        val usable: List<String>,
    )

    /**
     * Mirrors `listRecordings` in `recording/library.ts`: media files in the
     * recordings directory, newest first.
     *
     * `includeSources` widens the listing to audio sidecars, which only the
     * deletion review asks for — the library itself shows videos.
     */
    private fun recordingEntries(includeSources: Boolean): JSONArray {
        val files = recordingsDir.listFiles() ?: return JSONArray()
        val result = JSONArray()
        files.asSequence()
            .filter { it.isFile && it.length() > 0L }
            .filter { file ->
                if (includeSources) SOURCE_MEDIA.matches(file.name)
                else RECORDING_MEDIA.matches(file.name) && !WEBCAM_TAGGED.containsMatchIn(file.name)
            }
            .sortedByDescending { it.lastModified() }
            .forEach { file ->
                result.put(
                    JSONObject().apply {
                        put("path", file.path)
                        put("name", file.name)
                        put("bytes", file.length())
                        put("createdAt", file.lastModified())
                        put("url", urlFor(file) ?: JSONObject.NULL)
                    },
                )
            }
        return result
    }

    /**
     * Mirrors `getCompanionAudioFallbackInfo`, including its preference for a
     * macOS-style system/mic pair over the inline track.
     *
     * A recording whose container has no audio track uses whatever sidecars
     * exist. Once the video carries audio, the microphone sidecar is a
     * *separate* track and the video is returned with it, except when both mac
     * sidecars exist — then the sidecars are authoritative and the inline track
     * is left out, which is what upstream does.
     */
    private fun audioFallbackPaths(raw: String): JSONObject {
        fun result(paths: List<String>, delays: JSONObject) = JSONObject().apply {
            put("success", true)
            put("paths", JSONArray(paths))
            put("startDelayMsByPath", delays)
        }

        val video = resolveMediaArg(raw)
        if (video == null) {
            return JSONObject().apply {
                put("success", false)
                put("paths", JSONArray())
                put("startDelayMsByPath", JSONObject())
                put("error", "Invalid recording source")
            }
        }

        val base = video.path.substringBeforeLast('.')
        val candidates = COMPANION_LAYOUTS.mapNotNull { layout ->
            val system = File(base + layout.systemSuffix)
            val mic = File(base + layout.micSuffix)
            val usable = listOf(system, mic)
                .filter { it.isFile && it.length() > 0L }
                .map { it.path }
            if (usable.isEmpty()) null
            else CompanionCandidate(layout.platform, system.path, mic.path, usable)
        }

        val paths = when {
            candidates.isEmpty() -> emptyList()
            !hasAudioTrack(video) -> candidates.flatMap { it.usable }.distinct()
            else -> {
                val macSystem = candidates.any {
                    it.platform == "mac" && it.usable.contains(it.systemPath)
                }
                val macMicOnly = candidates
                    .filter { it.platform == "mac" && !it.usable.contains(it.systemPath) }
                    .flatMap { candidate -> candidate.usable.filter { it == candidate.micPath } }
                    .distinct()
                when {
                    !macSystem && macMicOnly.isNotEmpty() ->
                        // Browser mic audio supplements the MP4; native mic-only
                        // audio duplicates it, so only the former keeps the video.
                        if (macMicOnly.any { it.endsWith(".mic.wav") }) {
                            listOf(video.path) + macMicOnly
                        } else {
                            macMicOnly
                        }

                    macSystem -> candidates
                        .filter { it.platform == "mac" }
                        .flatMap { it.usable }
                        .distinct()

                    else -> {
                        val micOnly = candidates
                            .flatMap { candidate ->
                                candidate.usable.filter { it == candidate.micPath }
                            }
                            .distinct()
                        if (micOnly.isEmpty()) emptyList() else listOf(video.path) + micOnly
                    }
                }
            }
        }

        val delays = JSONObject()
        for (path in paths) {
            companionStartDelayMs(path)?.let { delays.put(path, it) }
        }
        return result(paths, delays)
    }

    /** `{startDelayMs}` written beside a sidecar by the mic fallback recorder. */
    private fun companionStartDelayMs(companionPath: String): Long? {
        val metadata = File(companionPath + ".json")
        if (!metadata.isFile) return null
        return try {
            val parsed = JSONTokener(metadata.readText()).nextValue() as? JSONObject ?: return null
            val delay = parsed.optDouble("startDelayMs", Double.NaN)
            if (!delay.isFinite() || delay < 0) null else Math.round(delay)
        } catch (error: Exception) {
            null
        }
    }

    private fun hasAudioTrack(file: File): Boolean = extractorTracks(file)
        .any { (mime, _) -> mime.startsWith("audio/") }

    /**
     * MIME type and format per track, or an empty list when the container
     * cannot be read. Callers treat that as "no audio", which is the safe
     * answer: it leaves the video's own (absent) track as the only source.
     */
    private fun extractorTracks(file: File): List<Pair<String, android.media.MediaFormat>> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = runCatching { extractor.getTrackFormat(index) }.getOrNull()
                val mime = format?.getString(android.media.MediaFormat.KEY_MIME) ?: return@mapNotNull null
                mime to format
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cannot read tracks of ${file.name}: ${error.message}")
            emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * The subset of ffprobe's report that the renderer actually reads, in the
     * same units: dimensions in encoded pixels and duration in seconds.
     *
     * `frameRate` falls back to 60 exactly as `metadata.ts` does, and
     * `frameRateSource` says which one it is so the export diagnostics do not
     * present a guess as a measurement.
     */
    private fun probeVideoMetadata(raw: String): JSONObject {
        fun failure(message: String) = JSONObject().apply {
            put("success", false)
            put("error", message)
        }

        val file = resolveMediaArg(raw) ?: return failure("Unknown or unreadable video")
        val retriever = android.media.MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(file.path)
            val width = retriever.extractLong(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractLong(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val durationMs = retriever.extractLong(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            if (width <= 0 || height <= 0 || durationMs <= 0) {
                return failure("Unable to parse native video metadata from ${file.name}")
            }
            val reportedRate =
                retriever.extractDouble(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.takeIf { it > 0 }
            val tracks = extractorTracks(file)
            val videoMime = tracks.firstOrNull { (mime, _) -> mime.startsWith("video/") }
            val audioMime = tracks.firstOrNull { (mime, _) -> mime.startsWith("audio/") }

            JSONObject().apply {
                put("width", width)
                put("height", height)
                put("duration", durationMs / 1000.0)
                put("mediaStartTime", 0)
                put("streamStartTime", 0)
                put("streamDuration", durationMs / 1000.0)
                put("frameRate", reportedRate ?: 60.0)
                put("frameRateSource", if (reportedRate != null) "metadata" else "fallback")
                put("codec", videoMime?.first ?: "unknown")
                put("hasAudio", audioMime != null)
                audioMime?.first?.let { put("audioCodec", it) }
                audioMime?.second
                    ?.takeIf { it.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE) }
                    ?.let { put("audioSampleRate", it.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)) }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "metadata probe failed for ${file.name}", error)
            return failure(error.message ?: "Could not read the video")
        } finally {
            runCatching { retriever.release() }
        }

        return JSONObject().apply {
            put("success", true)
            put("metadata", metadata)
        }
    }

    private fun android.media.MediaMetadataRetriever.extractLong(key: Int): Long =
        extractMetadata(key)?.trim()?.toLongOrNull() ?: 0L

    private fun android.media.MediaMetadataRetriever.extractDouble(key: Int): Double? =
        extractMetadata(key)?.trim()?.toDoubleOrNull()

    // ------------------------------------------------------------------ //
    // Library removal with undo
    // ------------------------------------------------------------------ //

    /**
     * Mirrors `setRecordingsRemoved`: removal renames the recording and every
     * sidecar that belongs to it into a hidden bundle inside the recordings
     * directory, and the inverse restores them.
     *
     * Staging by rename rather than deleting is what makes the library's undo
     * instant. A bundle is only deleted when a *later* removal happens, so the
     * undo survives until the user has clearly moved on — the same lifetime
     * upstream gives its Trash entries.
     */
    private fun setRecordingsRemoved(paths: JSONArray?, removed: Boolean): JSONObject {
        val selected = paths?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optString(index, "").takeIf { it.isNotEmpty() }
            }
        } ?: emptyList()
        if (selected.isEmpty() || selected.size != (paths?.length() ?: 0)) {
            return failure("Invalid recording selection")
        }

        val root = runCatching { recordingsDir.canonicalFile }.getOrNull()
            ?: return failure("Recording is outside the Videos library")
        val files = selected.map { raw ->
            val file = runCatching { File(raw).canonicalFile }.getOrNull()
                ?: return failure("Recording is outside the Videos library")
            if (file.parentFile?.path != root.path || !RECORDING_MEDIA.matches(file.name)) {
                return failure("Recording is outside the Videos library")
            }
            file
        }
        val key = files.map { it.path }.sorted().joinToString("\u0000")

        if (!removed) {
            val batch = undoBatches[key]
                ?: return failure("This removal can no longer be undone.")
            for (file in batch.files) {
                if (file.exists()) {
                    return failure("A file with this name already exists. Restore it another way.")
                }
            }
            val restored = mutableListOf<File>()
            try {
                for (file in batch.files) {
                    move(File(batch.bundle, file.name), file)
                    restored += file
                }
            } catch (error: Throwable) {
                // Put back what was already restored, so a failed undo leaves
                // the bundle intact instead of half-applied.
                restored.forEach { runCatching { move(it, File(batch.bundle, it.name)) } }
                return failure(error.message ?: "Could not restore the recordings")
            }
            undoBatches.remove(key)
            batch.bundle.deleteRecursively()
            return JSONObject().apply {
                put("success", true)
                put("value", JSONObject.NULL)
            }
        }

        for (file in files) {
            if (!file.isFile) return failure("Recording is missing or is not a regular file")
        }

        // A sidecar belongs to exactly one recording; adjacent recordings'
        // sidecars are never pulled in.
        val names = files.map { it.name }
        val moving = (recordingsDir.listFiles() ?: emptyArray())
            .filter { it.isFile && names.any { video -> belongsToRecording(it.name, video) } }

        sweepTrashBundles()
        val bundle = File(recordingsDir, ".recordly-trash-" + java.util.UUID.randomUUID())
        if (!bundle.mkdirs()) return failure("Could not stage the recordings")
        val moved = mutableListOf<File>()
        try {
            for (file in moving) {
                move(file, File(bundle, file.name))
                moved += file
            }
        } catch (error: Throwable) {
            moved.forEach { runCatching { move(File(bundle, it.name), it) } }
            bundle.deleteRecursively()
            return failure(error.message ?: "Could not stage the recordings")
        }
        undoBatches[key] = TrashBatch(bundle, moved)
        return JSONObject().apply {
            put("success", true)
            put("value", JSONObject.NULL)
        }
    }

    /** `belongsToRecording` in `recording/library.ts`. */
    private fun belongsToRecording(name: String, videoName: String): Boolean {
        val stem = videoName.substringBeforeLast('.')
        return name == videoName ||
            name == "$videoName$CURSOR_SIDECAR_SUFFIX" ||
            (name.startsWith("$stem.") && COMPANION_SOURCE.matches(name)) ||
            name == "$stem.recordly-session.json" ||
            name == "$stem.recording-session.json" ||
            (name.startsWith("$stem-webcam.") && VIDEO_MEDIA.matches(name))
    }

    /**
     * Deletes every staged bundle and forgets its undo entry.
     *
     * Scanning the directory rather than trusting only the in-memory map is
     * deliberate: a bundle outlives the process (the recording is gone from
     * the library either way), and this is what reclaims it.
     */
    private fun sweepTrashBundles() {
        undoBatches.clear()
        (recordingsDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith(TRASH_PREFIX) }
            .forEach { bundle ->
                if (!bundle.deleteRecursively()) {
                    Log.w(TAG, "could not delete the staged bundle ${bundle.name}")
                }
            }
    }

    /** Renames across the same filesystem; falls back to copy + delete. */
    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        if (!source.delete()) Log.w(TAG, "cannot remove ${source.name} after moving it")
    }

    private fun failure(message: String): JSONObject = JSONObject().apply {
        put("success", false)
        put("error", message)
    }

    private class TrashBatch(val bundle: File, val files: List<File>)

    // ------------------------------------------------------------------ //
    // Files, thumbnails, desktop background
    // ------------------------------------------------------------------ //

    /**
     * A 160×90 JPEG of the recording's first usable frame, as a data URL.
     *
     * Mirrors `recording/thumbnail.ts`: the still is scaled to *cover* the
     * target and centre-cropped (ffmpeg's `force_original_aspect_ratio=increase,
     * crop=160:90`), cached under a key of path + mtime + size, and returned
     * inline because the renderer puts it straight into an `img`/`poster`.
     */
    private fun recordingThumbnail(raw: String): String? {
        val file = resolveMediaArg(raw) ?: return null
        val recordings = runCatching { recordingsDir.canonicalFile }.getOrNull() ?: return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.path.startsWith(recordings.path + File.separator)) return null
        if (!VIDEO_MEDIA.matches(canonical.name)) return null

        val key = MediaRegistry.digest(
            "${canonical.path}:${canonical.lastModified()}:${canonical.length()}",
        )
        val cache = media.ownedFile("recording-thumbnails").apply { mkdirs() }
        val cached = File(cache, "$key.jpg")
        val jpeg = if (cached.isFile && cached.length() > 0L) {
            runCatching { cached.readBytes() }.getOrNull()
        } else {
            null
        } ?: extractThumbnail(canonical)?.also { bytes ->
            runCatching { cached.writeBytes(bytes) }
                .onFailure { Log.w(TAG, "cannot cache the thumbnail for ${canonical.name}", it) }
        } ?: return null

        return "data:image/jpeg;base64," +
            android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
    }

    /** Scales the frame to cover [THUMBNAIL_WIDTH]×[THUMBNAIL_HEIGHT], then centre-crops. */
    private fun extractThumbnail(file: File): ByteArray? {
        val frame = decodeVideoFrame(file) ?: return null
        val scale = maxOf(
            THUMBNAIL_WIDTH.toFloat() / frame.width,
            THUMBNAIL_HEIGHT.toFloat() / frame.height,
        )
        val scaledWidth = (frame.width * scale).roundToInt().coerceAtLeast(THUMBNAIL_WIDTH)
        val scaledHeight = (frame.height * scale).roundToInt().coerceAtLeast(THUMBNAIL_HEIGHT)
        val scaled = android.graphics.Bitmap.createScaledBitmap(frame, scaledWidth, scaledHeight, true)
        val cropped = android.graphics.Bitmap.createBitmap(
            scaled,
            (scaledWidth - THUMBNAIL_WIDTH) / 2,
            (scaledHeight - THUMBNAIL_HEIGHT) / 2,
            THUMBNAIL_WIDTH,
            THUMBNAIL_HEIGHT,
        )
        val out = java.io.ByteArrayOutputStream()
        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 62, out)
        cropped.recycle()
        if (scaled !== frame) scaled.recycle()
        if (cropped !== frame) frame.recycle()
        return out.toByteArray()
    }

    /** Only `files/media` and the installed renderer tree may be read on demand. */
    private fun readableByRenderer(file: File): Boolean {
        val roots = listOfNotNull(
            runCatching { AppHost.mediaRoot(appContext).canonicalFile }.getOrNull(),
            runCatching { assetRoot.canonicalFile }.getOrNull(),
        )
        return roots.any { root -> file.path.startsWith(root.path + File.separator) }
    }

    /**
     * Hands a produced file to another app.
     *
     * The FileProvider grants read access to the media root only, so anything
     * else is refused rather than copied out of the sandbox.
     */
    private fun openWithSystem(file: File): Boolean {
        if (!readableByRenderer(file)) {
            Log.w(TAG, "refusing to share a file outside the media root: ${file.path}")
            return false
        }
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                file,
            )
        }.onFailure { Log.w(TAG, "cannot build a share URI for ${file.name}", it) }
            .getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeTypeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return openSettings(intent)
    }

    private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    /**
     * The system wallpaper as a PNG under the media root, keyed by its own
     * content so every call after the first is a cache hit.
     *
     * `WallpaperManager.drawable` is refused on some devices and profiles; that
     * is answered as `{success:false}` and the editor keeps its own background.
     */
    private fun desktopWallpaper(): JSONObject {
        fun failure() = JSONObject().put("success", false)

        val manager = appContext.getSystemService(android.app.WallpaperManager::class.java)
            ?: return failure()
        val drawable = runCatching { manager.drawable }.getOrNull() ?: return failure()
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return failure()

        val scale = minOf(1f, MAX_WALLPAPER_EDGE.toFloat() / maxOf(width, height))
        val bitmap = android.graphics.Bitmap.createBitmap(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(canvas)

        val bytes = java.io.ByteArrayOutputStream().also {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        bitmap.recycle()
        if (bytes.isEmpty()) return failure()

        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val dir = media.ownedFile("desktop-wallpapers").apply { mkdirs() }
        val target = File(dir, "$digest.png")
        if (!target.isFile || target.length() != bytes.size.toLong()) {
            runCatching { target.writeBytes(bytes) }
                .onFailure { Log.w(TAG, "cannot store the desktop wallpaper", it) }
        }
        return JSONObject().apply {
            put("success", true)
            put("path", target.path)
        }
    }

    // ------------------------------------------------------------------ //
    // Media plumbing
    // ------------------------------------------------------------------ //

    /** Accepts a URL we served, a `file://` URL, or a plain path. */
    private fun resolveMediaArg(raw: String): File? {
        if (raw.isEmpty()) return null

        val candidate = when {
            raw.startsWith("http://127.0.0.1") || raw.startsWith("http://localhost") -> {
                val path = raw.substringAfter("://").substringAfter('/', "").substringBefore('?')
                media.resolve(java.net.URLDecoder.decode(path.removePrefix("media/"), "UTF-8"))
                    ?: return null
            }
            raw.startsWith("file://") -> File(java.net.URI(raw).path ?: return null)
            else -> File(raw)
        }
        return candidate.takeIf { it.isFile }
    }

    private fun urlFor(file: File): String? {
        val relative = media.expose(file) ?: return null
        return media.urlFor(baseUrl(), relative)
    }

    /**
     * Extracts one frame of a bundled wallpaper video as JPEG.
     *
     * The argument is whatever the renderer holds: a web path under the
     * installed renderer (`/wallpapers/x.mp4`), a `file://` URL, or a plain
     * filesystem path. The answer carries a URL instead of bytes so that the
     * JSON transport stays text-only.
     */
    private fun generateWallpaperThumbnail(raw: String): JSONObject {
        fun failure(message: String) = JSONObject().apply {
            put("success", false)
            put("error", message)
        }

        val source = resolveAssetArg(raw) ?: return failure("Unknown asset").also {
            Log.w(TAG, "wallpaper thumbnail: cannot resolve '$raw'")
        }
        if (!source.isFile) return failure("Unknown asset").also {
            Log.w(TAG, "wallpaper thumbnail: '${source.path}' is not a file")
        }

        val thumbs = media.ownedFile("wallpaper-thumbs").apply { mkdirs() }
        val cached = File(thumbs, MediaRegistry.digest(source.path) + ".jpg")
        if (!cached.isFile || cached.length() == 0L) {
            val written = try {
                writeFrame(source, cached)
            } catch (error: Throwable) {
                Log.w(TAG, "thumbnail extraction failed for ${source.name}", error)
                false
            }
            if (!written) return failure("Could not decode ${source.name}")
        }

        val url = urlFor(cached) ?: return failure("Could not expose thumbnail")
        return JSONObject().apply {
            put("success", true)
            put("url", url)
        }
    }

    /** A downscaled JPEG of [source]: stills decode directly, videos via a frame. */
    private fun writeFrame(source: File, target: File): Boolean {
        val bitmap = decodeStill(source) ?: decodeVideoFrame(source) ?: return false
        val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_THUMBNAIL_EDGE) {
            val ratio = MAX_THUMBNAIL_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height)
            android.graphics.Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        java.io.FileOutputStream(target).use { out ->
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        }
        return target.length() > 0L
    }

    /** Decodes with the sample size that lands nearest [MAX_THUMBNAIL_EDGE]. */
    private fun decodeStill(source: File): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_THUMBNAIL_EDGE) sample *= 2

        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        return android.graphics.BitmapFactory.decodeFile(source.path, options)
    }

    /** First frame with a usable image. */
    private fun decodeVideoFrame(source: File): android.graphics.Bitmap? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.path)
            THUMBNAIL_TIMES_US.asSequence()
                .mapNotNull { timeUs ->
                    retriever.getFrameAtTime(
                        timeUs,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }
                .firstOrNull()
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Bundled assets arrive as `/wallpapers/x.mp4`; resolve inside the app copy. */
    private fun resolveAssetArg(raw: String): File? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("http://127.0.0.1") || raw.startsWith("http://localhost")) {
            val path = raw.substringAfter("://").substringAfter('/', "").substringBefore('?')
            return MediaRegistry.resolveUnder(assetRoot, path)
        }
        if (raw.startsWith("file://")) {
            return runCatching { File(java.net.URI(raw).path ?: return null) }.getOrNull()
        }
        // A web path (`/wallpapers/x.jpg`) is ALSO an "absolute" File on
        // Android, so existence decides: a real file wins, otherwise treat the
        // leading slash as the asset root.
        val direct = File(raw)
        if (direct.isAbsolute && direct.isFile) return direct
        return MediaRegistry.resolveUnder(assetRoot, raw.trimStart('/'))
    }

    private fun telemetrySidecar(explicit: String?): File? {
        val target = explicit?.let { resolveMediaArg(it) } ?: currentVideo ?: return null
        return File(target.path + CURSOR_SIDECAR_SUFFIX)
    }

    private fun readTelemetry(explicit: String?): JSONObject {
        val sidecar = telemetrySidecar(explicit)
        if (sidecar == null || !sidecar.isFile) {
            // No sidecar yet. Only an in-progress take may borrow the live
            // session: falling back for an older/imported video handed it the
            // most recent take's taps and generated zooms at the wrong times.
            val live = if (CaptureSession.isRecording()) TouchTelemetry.samples() else JSONArray()
            return JSONObject().apply {
                put("success", true)
                put("samples", live)
            }
        }

        return try {
            val parsed = JSONTokener(sidecar.readText()).nextValue()
            // Upstream accepts either a bare array or {samples:[...]}; we write
            // the latter and tolerate both on read.
            val samples = when (parsed) {
                is JSONArray -> parsed
                is JSONObject -> parsed.optJSONArray("samples") ?: JSONArray()
                else -> JSONArray()
            }
            JSONObject().apply {
                put("success", true)
                put("samples", samples)
            }
        } catch (error: Exception) {
            Log.w(TAG, "unreadable cursor sidecar", error)
            JSONObject().apply {
                put("success", false)
                put("samples", JSONArray())
            }
        }
    }

    private fun writeTelemetry(explicit: String?, samples: JSONArray?): JSONObject {
        val sidecar = telemetrySidecar(explicit)
        if (sidecar == null) {
            return JSONObject().apply {
                put("success", false)
                put("samples", JSONArray())
                put("message", "No video path available for cursor telemetry")
            }
        }
        val body = JSONObject().apply {
            put("version", 1)
            put("samples", samples ?: JSONArray())
        }
        return try {
            sidecar.writeText(body.toString())
            // Upstream resolves with the samples it just stored, not a count.
            JSONObject().apply {
                put("success", true)
                put("samples", samples ?: JSONArray())
            }
        } catch (error: Exception) {
            Log.w(TAG, "cannot write cursor sidecar", error)
            JSONObject().apply {
                put("success", false)
                put("samples", JSONArray())
                put("message", error.message ?: "Could not save cursor telemetry")
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Events
    // ------------------------------------------------------------------ //

    /** Called by native components (capture service, telemetry) to notify JS. */
    fun emit(channel: String, payload: Any?) {
        if (!subscriptions.contains(channel)) return
        val json = toJson(payload) ?: "null"
        onEmit(channel, json)
    }

    // ------------------------------------------------------------------ //
    // JSON helpers
    // ------------------------------------------------------------------ //

    private fun parseArgs(argsJson: String): JSONArray = try {
        JSONTokener(argsJson).nextValue() as? JSONArray ?: JSONArray()
    } catch (error: Exception) {
        JSONArray()
    }

    /** Serialises to the JSON text the shim parses. `null` means "no payload". */
    private fun toJson(value: Any?): String? = when (value) {
        null -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long, is Double, is Float -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun rejectPayload(message: String): String =
        JSONObject().put("__error", message).toString()

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(dir, "$stem-$index$suffix")
            index++
        }
        return candidate
    }

    /**
     * Mirrors `suggest-export-file-name`: Electron rejects a name containing
     * path or control characters, or not ending in .mp4/.gif, and otherwise
     * returns the basename of an unused path.
     */
    private fun suggestExportName(fileName: String): String? {
        if (fileName.isEmpty()) return null
        if (ILLEGAL_NAME_CHARS.containsMatchIn(fileName)) return null
        if (EXPORT_SUFFIXES.none { fileName.endsWith(it, ignoreCase = true) }) return null
        exportsDir.mkdirs()
        return uniqueFile(exportsDir, fileName).name
    }

    /**
     * `import-wallpaper-video`: store a video chosen through the renderer's
     * file input and return its path.
     *
     * The shim uploaded the `File` to the export byte channel, so this closes
     * that stream and moves the temp file under the media root. The editor
     * resolves the path through `getLocalMediaUrl` when it draws the
     * background, which keeps the wallpaper same-origin with the video.
     */
    private fun finishWallpaperImport(streamId: String, name: String): JSONObject {
        if (streamId.isEmpty() || name.isEmpty()) {
            return failure("Invalid wallpaper import")
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        if (extension !in WALLPAPER_VIDEO_EXTENSIONS) {
            ExportStreams.close(streamId, abort = true)
            return failure("Unsupported wallpaper video format")
        }
        val closed = ExportStreams.close(streamId, abort = false)
        if (!closed.optBoolean("success", false)) return closed
        val raw = closed.optString("tempPath", "")
        if (raw.isEmpty()) return failure("The wallpaper stream has no file")

        val temp = File(raw)
        if (!temp.isFile || temp.length() == 0L) {
            temp.delete()
            return failure("The selected video is empty")
        }
        val dir = media.ownedFile(WALLPAPER_DIR).apply { mkdirs() }
        val target = File(dir, "wallpaper-${java.util.UUID.randomUUID()}.$extension")
        try {
            move(temp, target)
        } catch (error: Throwable) {
            Log.w(TAG, "cannot store the imported wallpaper", error)
            target.delete()
            return failure(error.message ?: "Could not save the wallpaper")
        }
        if (extractorTracks(target).none { (mime, _) -> mime.startsWith("video/") }) {
            target.delete()
            return failure("That file is not a readable video")
        }
        return JSONObject().apply {
            put("success", true)
            put("path", target.path)
        }
    }

    /**
     * Applies the floating-frame look once. Existing installs keep their cursor
     * and zoom policy but get the composition that the Android output needs to
     * match Recordly on Windows; after this runs, user look changes win.
     */
    private fun migrateTouchLook(key: String, value: Any?): Any? {
        if (!TouchPresentation.isEditorPreferencesKey(key) ||
            settings.found(TouchPresentation.LOOK_MIGRATION_KEY)
        ) {
            return value
        }
        val editor = (value as? JSONObject)?.let { JSONObject(it.toString()) }
            ?: TouchPresentation.seed()
        TouchPresentation.applyLook(editor)
        settings.set(key, editor)
        settings.set(TouchPresentation.LOOK_MIGRATION_KEY, true)
        return editor
    }

    private fun storedPreferences(): JSONObject =
        settings.get(PREF_APPLICATION) as? JSONObject ?: JSONObject()

    /** `preferences()` in `desktopPresence.ts`, with Android's answers. */
    private fun applicationPreferencesOf(
        showInDock: Boolean,
        hideWhileRecording: Boolean,
        error: String? = null,
    ): JSONObject = JSONObject().apply {
        put("success", error == null)
        put("showInDock", showInDock)
        put("hideWhileRecording", hideWhileRecording)
        // Android never starts an app at login without a launcher entry the
        // user created, so the option is reported as unsupported rather than
        // stored and ignored.
        put("launchAtLogin", false)
        put("launchAtLoginSupported", false)
        put("platform", PLATFORM)
        if (error != null) put("error", error)
    }

    /** Default true, as upstream's `hudOverlayHiddenFromCapture`. */
    private fun hudCaptureProtectionEnabled(): Boolean =
        (settings.get(SettingsStore.PREF_HUD_CAPTURE_PROTECTION) as? Boolean) ?: true

    /** `{...existing, ...patch}`, matching `recordingPreferencesStore.update`. */
    private fun mergePreferences(patch: JSONObject) {
        val current = settings.get(PREF_RECORDING)
        val merged = if (current is JSONObject) JSONObject(current.toString()) else JSONObject()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            merged.put(key, patch.opt(key))
        }
        settings.set(PREF_RECORDING, merged)
    }

    /**
     * Mirrors `list-asset-directory`: file names one level below the served
     * root. `wallpapers.ts` uses it to discover bundled wallpapers; the path is
     * confined to the installed renderer tree.
     */
    private fun listAssetDirectory(relativeDir: String): JSONObject {
        val normalised = relativeDir.replace('\\', '/').trimStart('/')
        val dir = MediaRegistry.resolveUnder(assetRoot, normalised)
        if (dir == null || !dir.isDirectory) {
            return JSONObject().apply {
                put("success", false)
                put("error", "Invalid asset directory")
            }
        }
        val files = (dir.listFiles { file -> file.isFile } ?: emptyArray())
            .map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        return JSONObject().apply {
            put("success", true)
            put("files", JSONArray(files))
        }
    }

    private companion object {
        const val TAG = "NativeBridge"
        const val PREF_RECORDING = "recordly.recording.preferences"
        const val PREF_COUNTDOWN_DELAY = "recordly.countdown.delay"
        const val PREF_SHORTCUTS = "recordly.shortcuts"
        const val PREF_APPLICATION = "recordly.application.preferences"

        /** Keys `set-application-preferences` accepts. */
        val APPLICATION_KEYS = setOf("showInDock", "hideWhileRecording", "launchAtLogin")

        /** The two addresses upstream lets through `open-external-url`. */
        val SUPPORT_MAILTO = setOf(
            "mailto:support@recordly.dev",
            "mailto:youngchen3442@gmail.com",
        )
        const val EVENT_RECORDING_STATE = "onRecordingStateChanged"
        // Subscribed as `electronAPI.onRecordingSessionChanged`, not the IPC
        // name `recording-session-changed`; `emit` only delivers to the former.
        const val EVENT_SESSION_CHANGED = "onRecordingSessionChanged"

        /**
         * What the renderer is told about the host.
         *
         * `"win32"` is not cosmetic: it is the one platform on which
         * `startNativeRecorder` asks `isNativeWindowsCaptureAvailable` and then
         * calls `startNativeScreenRecording`. Any other value sends capture
         * down the browser path, which starts with `getDisplayMedia` — a call a
         * WebView cannot serve.
         */
        const val PLATFORM = "win32"

        // `shouldUseNativeWindowsCaptureForSource` routes on the `screen:`
        // prefix; the rest matches `sources.ts`'s display shape.
        const val SCREEN_SOURCE_ID = "screen:0:0"
        const val SCREEN_DISPLAY_ID = "0"

        // `sources.ts` labels the primary display "Screen 1 (Primary)" and
        // keeps Electron's own name as `originalName`.
        const val SCREEN_SOURCE_NAME = "Screen 1 (Primary)"
        const val SCREEN_ORIGINAL_NAME = "Entire screen"

        // Option keys of `set-current-video-path` and
        // `set-current-recording-session`.
        const val PRESERVE_PROJECT_PATH = "preserveProjectPath"
        const val HIDE_OVERLAY_CURSOR = "hideOverlayCursorByDefault"

        const val CURSOR_SIDECAR_SUFFIX = ".cursor.json"
        const val RECORDING_PREFIX = "recording-"

        /**
         * Scratch space for joined sources, inside the media root so the
         * server can serve them, but below `recordings/` so the library
         * (which lists only direct children) never shows them.
         */
        const val IMPORT_DIR = "recordings/.recordly-media"

        /** Videos chosen as custom backgrounds (`importWallpaperVideo`). */
        const val WALLPAPER_DIR = "imported-wallpapers"
        val WALLPAPER_VIDEO_EXTENSIONS = setOf("webm", "mp4", "mov", "m4v", "avi", "mkv")
        const val DELETE_REJECTED = "Only auto-generated recordings can be deleted"

        // `isRecording` in `recording/library.ts`: playable media, minus the
        // webcam takes that are never library entries.
        //
        // These are used with `Regex.matches`, which requires the ENTIRE input
        // to match — a pattern anchored only at the end silently matches nothing
        // but a bare ".mp4". The leading `.*` is what makes "file.mp4" match.
        val RECORDING_MEDIA = Regex(".*\\.(mp4|mov|webm|mkv|m4v)$", RegexOption.IGNORE_CASE)
        val SOURCE_MEDIA =
            Regex(".*\\.(mp4|mov|webm|mkv|m4v|wav|m4a|mp3|ogg|flac)$", RegexOption.IGNORE_CASE)
        val WEBCAM_TAGGED = Regex("[.-]webcam[.-]", RegexOption.IGNORE_CASE)

        // `COMPANION_AUDIO_LAYOUTS` in `electron/ipc/constants.ts`.
        val COMPANION_LAYOUTS = listOf(
            CompanionLayout("mac", ".system.m4a", ".mic.m4a"),
            CompanionLayout("win", ".system.wav", ".mic.wav"),
            // The native system recording may accompany a Chromium mic fallback.
            CompanionLayout("mac", ".system.m4a", ".mic.wav"),
            CompanionLayout("mac", ".system.webm", ".mic.webm"),
        )

        // Sibling files `discardFiles.ts` removes alongside the video. The
        // `.cursor.json` sidecar is keyed on the full video path, these on the
        // extension-less base.
        val DISCARD_SUFFIXES = arrayOf(
            ".recording-diagnostics.json",
            ".recordly-session.json",
            ".system.m4a",
            ".mic.m4a",
            ".system.wav",
            ".mic.wav",
            ".system.wav.json",
            ".mic.wav.json",
        )

        val DEVICE_KEYS = arrayOf("microphoneDeviceId", "webcamDeviceId")
        val ILLEGAL_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\p{Cntrl}]")
        val EXPORT_SUFFIXES = listOf(".mp4", ".gif")

        // Try the opening frame first, then a little way in: many wallpaper
        // loops start on a hard cut that decodes to a near-black frame.
        val THUMBNAIL_TIMES_US = longArrayOf(0L, 500_000L, 1_000_000L)
        const val MAX_THUMBNAIL_EDGE = 640

        /** `recording/thumbnail.ts`'s ffmpeg crop target. */
        const val THUMBNAIL_WIDTH = 160
        const val THUMBNAIL_HEIGHT = 90

        /** Longest edge of the desktop-wallpaper snapshot. */
        const val MAX_WALLPAPER_EDGE = 1600

        /** Playable media a library thumbnail can be taken from. */
        val VIDEO_MEDIA = Regex(".*\\.(mp4|mov|webm|mkv|m4v)$", RegexOption.IGNORE_CASE)

        /** Companion audio a recording may have written beside itself. */
        val COMPANION_SOURCE =
            Regex(".*\\.(?:system|mic|microphone|audio|webcam)\\.(?:wav|webm|mp4|m4a)(?:\\.json)?$", RegexOption.IGNORE_CASE)

        const val TRASH_PREFIX = ".recordly-trash-"

        /**
         * Removal batches, process-wide on purpose: the web layer is reloaded
         * on every surface switch, and an undo that vanished on reload would
         * strand the user's files in a hidden directory.
         */
        val undoBatches = ConcurrentHashMap<String, TrashBatch>()
    }
}
