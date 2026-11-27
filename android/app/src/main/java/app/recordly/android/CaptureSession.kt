package app.recordly.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the one screen-capture session the app can have at a time.
 *
 * This is the piece that makes MediaProjection usable from the bridge. The
 * renderer asks `startNativeScreenRecording`, which must block until the user
 * has answered the system consent dialog — and that dialog needs an **Activity**,
 * while the bridge talks to the service from a worker thread and outlives any
 * single activity instance. So the activity registers its launchers here, the
 * service reports its lifecycle here, and [start]/[stop] hand back futures the
 * bridge can await without knowing about either.
 *
 * The state machine is deliberately small: IDLE → RECORDING ⇄ PAUSED → IDLE,
 * with FINALIZING as the window where the muxer is being closed and segments
 * joined. Everything that can be asked twice (stop, pause on a paused session)
 * answers from state rather than acting twice — the renderer's stop path can
 * legitimately race the notification's stop action.
 */
object CaptureSession {

    private const val TAG = "CaptureSession"

    /** Consent can legitimately take a while; the user is reading a dialog. */
    private const val CONSENT_TIMEOUT_S = 600L

    /** Remuxing a long recording is disk-bound; a minute is generous. */
    private const val STOP_TIMEOUT_S = 60L

    /**
     * How long a system-initiated stop waits for the renderer to drive it.
     *
     * Long enough for a healthy page to answer `onStopRecordingFromTray`
     * (it is a single synchronous JS callback), short enough that a dead page
     * does not leave the projection and its notification alive.
     */
    private const val FALLBACK_STOP_DELAY_MS = 2_500L

    enum class Phase { IDLE, RECORDING, PAUSED, FINALIZING }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** The finished file of the last capture, for recovery paths. */
    @Volatile
    var lastOutput: File? = null
        private set

    /**
     * Video from a take that finished with nobody waiting for it.
     *
     * Only the fallback and interrupted paths can produce one: a normal stop
     * hands its path straight to the renderer, and offering it again would open
     * the same recording twice. See [recoverAsync].
     */
    @Volatile
    private var recoverable: File? = null

    /**
     * Set when the native recorder overlay ends a take.
     *
     * The overlay never loaded the renderer's HUD surface, so no page handler
     * will adopt the finished file. The activity claims it once capture stops.
     */
    @Volatile
    private var openEditorOnStop = false

    /** Installed by [NativeBridge]; the only route to the renderer. */
    @Volatile
    var emitter: ((String, JSONObject) -> Unit)? = null

    // --- activity wiring (set by MainActivity, cleared with it) ---------- //

    /**
     * What capture needs from the single activity.
     *
     * A data class rather than individual fields because the activity swaps the
     * whole set in and out together, and a half-attached set is the kind of bug
     * that only shows up after an orientation change.
     */
    class Hooks(
        /** Runtime permission prompt (notifications, microphone). */
        val requestPermissions: (Array<String>) -> Unit,
        /** The system MediaProjection consent dialog. */
        val requestProjection: () -> Unit,
        /** Load the recording surface (`?windowType=hud-overlay`). */
        val showHud: () -> Unit,
        /** Load the editor surface (`?windowType=editor`). */
        val showEditor: () -> Unit,
        /** Capture began: keep our UI out of the recording. */
        val onRecordingStarted: () -> Unit,
        /** Capture is over, file written. */
        val onRecordingStopped: () -> Unit,
        /**
         * The overlay asked to end the take and open the editor.
         *
         * The normal renderer stop path owns its own handoff; this is only for
         * a stop initiated by the native recorder overlay, where the page never
         * saw the HUD surface.
         */
        val onRecordingFinished: ((File) -> Unit)? = null,
        /**
         * Brings the app's task forward before the overlay requests projection.
         *
         * The setup can be open over another app; Android will not show a
         * MediaProjection consent from a background activity.
         */
        val bringAppToFront: (() -> Unit)? = null,
    )

    @Volatile
    private var hooks: Hooks? = null

    // --- in-flight requests --------------------------------------------- //

    private val lock = Any()
    private var pendingStart: CompletableFuture<JSONObject>? = null
    private var pendingMicrophone = false
    private var pendingPermissions: CompletableFuture<Map<String, Boolean>>? = null
    private var pendingStop: CompletableFuture<JSONObject>? = null
    private var pendingConsent: CountDownLatch? = null

    /** Remux/finalize must never run on the main thread. */
    private val finalizer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "recordly-finalize").apply { isDaemon = true }
    }

    fun attach(hooks: Hooks) {
        this.hooks = hooks
    }

    fun detach() {
        hooks = null
        // An activity destroyed mid-dialog will never deliver a result.
        finishStart(failure("The app left the foreground before capture started"))
        completePermissions(emptyMap())
        // Release the consent watchdog (and the single finalizer thread that
        // awaits it); the dialog result will never arrive.
        synchronized(lock) {
            pendingConsent?.countDown()
            pendingConsent = null
        }
    }

    fun showHud(): Boolean {
        val action = hooks?.showHud ?: return false
        action()
        return true
    }

    fun showEditor(): Boolean {
        val action = hooks?.showEditor ?: return false
        action()
        return true
    }

    fun bringAppToFront() {
        hooks?.bringAppToFront?.invoke()
    }

    fun isRecording(): Boolean = phase == Phase.RECORDING || phase == Phase.PAUSED

    fun isStarting(): Boolean = synchronized(lock) { pendingStart != null }

    // ------------------------------------------------------------------ //
    // Start
    // ------------------------------------------------------------------ //

    /**
     * Asks for the runtime permissions capture needs, then for MediaProjection
     * consent, then starts the service. Resolves with the renderer's own result
     * shape: `{success:true}` or `{success:false, userNotified, error}`.
     */
    fun start(context: Context, wantsMicrophone: Boolean): CompletableFuture<JSONObject> {
        synchronized(lock) {
            if (phase != Phase.IDLE) {
                return CompletableFuture.completedFuture(failure("A recording is already in progress"))
            }
            pendingStart?.let {
                return CompletableFuture.completedFuture(failure("Capture is already starting"))
            }
            val future = CompletableFuture<JSONObject>()
            pendingStart = future
            pendingMicrophone = wantsMicrophone
            // A new take owns the stop/answer window. Without this, a stop
            // issued after a failed start (or by a stale page) returned the
            // previous recording. The previous file stays on disk; it is just
            // no longer an answer for this take.
            lastOutput = null
            recoverable = null
            Log.i(TAG, "start requested (microphone=$wantsMicrophone)")

            val app = context.applicationContext
            val wanted = mutableListOf<String>()
            // Notifications are how capture is stopped once the user is in
            // another app, so ask for them before they matter.
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            if (wantsMicrophone &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                wanted += Manifest.permission.RECORD_AUDIO
            }

            if (wanted.isEmpty()) {
                requestConsentLocked()
            } else {
                requestRuntimePermissions(wanted.toTypedArray(), onDone = { requestConsentLocked() })
            }
            return future
        }
    }

    /** Must be called while holding [lock]. */
    private fun requestConsentLocked() {
        val launch = hooks?.requestProjection
        if (launch == null) {
            finishStart(failure("The app is not in the foreground"))
            return
        }
        pendingConsent = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                launch()
            } catch (error: Throwable) {
                Log.e(TAG, "cannot show the capture consent dialog", error)
                synchronized(lock) {
                    pendingConsent?.countDown()
                    pendingConsent = null
                }
                finishStart(failure("Could not open the screen capture dialog"))
            }
        }
        // The dialog's result arrives on the main thread; this latch only
        // guards against a launcher that silently never runs.
        finalizer.execute {
            val latch = synchronized(lock) { pendingConsent }
            if (latch != null && !latch.await(CONSENT_TIMEOUT_S, TimeUnit.SECONDS)) {
                synchronized(lock) {
                    if (pendingConsent === latch) pendingConsent = null
                }
                finishStart(failure("Screen capture permission timed out"))
            }
        }
    }

    private fun requestRuntimePermissions(
        permissions: Array<String>,
        onDone: () -> Unit,
    ) {
        val launch = hooks?.requestPermissions
        if (launch == null) {
            onDone()
            return
        }
        val future = CompletableFuture<Map<String, Boolean>>()
        synchronized(lock) { pendingPermissions = future }
        Handler(Looper.getMainLooper()).post {
            try {
                launch(permissions)
            } catch (error: Throwable) {
                Log.w(TAG, "permission request failed", error)
                completePermissions(emptyMap())
            }
        }
        future.whenComplete { granted, _ ->
            val microphoneDenied = permissions.contains(Manifest.permission.RECORD_AUDIO) &&
                granted[Manifest.permission.RECORD_AUDIO] != true
            if (microphoneDenied) {
                // Capture can still proceed without a microphone; the renderer
                // shows the audio state itself, and a denied mic must not block
                // a screen recording the user asked for.
                Log.i(TAG, "microphone denied; recording video only")
                notifyUser("Microphone permission was denied. Recording the screen without audio.")
            }
            onDone()
        }
    }

    fun onPermissionsResult(granted: Map<String, Boolean>) = completePermissions(granted)

    private fun completePermissions(granted: Map<String, Boolean>) {
        val future = synchronized(lock) {
            pendingPermissions.also { pendingPermissions = null }
        }
        future?.complete(granted)
    }

    /**
     * Called by the activity with the system dialog's answer. A successful
     * result starts the foreground service, which owns the projection from
     * here on.
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        val wantsMicrophone = synchronized(lock) {
            pendingConsent?.countDown()
            pendingConsent = null
            if (pendingStart == null) return
            pendingMicrophone
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            // `canceled` distinguishes a dismissed dialog from a real failure:
            // the renderer stops cleanly for the former and surfaces the latter.
            finishStart(failure("Screen capture permission was denied").put("canceled", true))
            return
        }
        val context = AppHost.applicationContext()
        if (context == null) {
            finishStart(failure("The app is not running"))
            return
        }
        try {
            ScreenCaptureService.begin(
                context, resultCode, data,
                capturesMicrophone = wantsMicrophone &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        } catch (error: Throwable) {
            Log.e(TAG, "cannot start the capture service", error)
            finishStart(failure(error.message ?: "Could not start screen capture"))
        }
    }

    /** The service is encoding; the start request is satisfied. */
    fun onCaptureStarted(microphoneActive: Boolean = false) {
        phase = Phase.RECORDING
        finishStart(JSONObject().apply {
            put("success", true)
            put("microphoneActive", microphoneActive)
            put("microphoneFallbackRequired", false)
        })
        hooks?.onRecordingStarted?.invoke()
    }

    /** The service could not start capture, with a message for the user. */
    fun onCaptureFailed(message: String) {
        phase = Phase.IDLE
        finishStart(failure(message))
        finishStop(failure(message))
        emit(EVENT_RECORDING_STATE, JSONObject().put("recording", false))
    }

    private fun finishStart(result: JSONObject) {
        val future = synchronized(lock) {
            pendingMicrophone = false
            pendingStart.also { pendingStart = null }
        } ?: return
        if (!result.optBoolean("success", false)) {
            notifyUser(result.optString("error", "Could not start the recording"))
        }
        future.complete(result)
    }

    // ------------------------------------------------------------------ //
    // Pause / resume
    // ------------------------------------------------------------------ //

    fun pause(): JSONObject {
        if (phase != Phase.RECORDING) {
            return if (phase == Phase.PAUSED) JSONObject().put("success", true)
            else failure("No screen recording is active")
        }
        val started = ScreenCaptureService.pauseCapture()
        if (!started) {
            Log.w(TAG, "pause was refused by the capture service (phase=$phase)")
            return failure("Could not pause the screen recording")
        }
        phase = Phase.PAUSED
        Log.i(TAG, "paused")
        return JSONObject().put("success", true)
    }

    fun resume(): JSONObject {
        if (phase != Phase.PAUSED) {
            return if (phase == Phase.RECORDING) JSONObject().put("success", true)
            else failure("No screen recording is active")
        }
        val error = ScreenCaptureService.resumeCapture()
        if (error != null) {
            Log.w(TAG, "resume failed: $error (phase=$phase)")
            return failure(error)
        }
        phase = Phase.RECORDING
        Log.i(TAG, "resumed")
        return JSONObject().put("success", true)
    }

    /**
     * The renderer's pause/resume boundary in wall-clock milliseconds. Converted
     * here because the telemetry clock is session-relative uptime.
     */
    fun markCursorPaused() = TouchTelemetry.pauseClock()

    fun markCursorResumed() = TouchTelemetry.resumeClock()

    // ------------------------------------------------------------------ //
    // Stop
    // ------------------------------------------------------------------ //

    /**
     * Finalizes the capture and resolves with `{success:true, path}` — the
     * absolute path of the MP4, which is also what the cursor sidecar sits
     * beside. Safe to call twice: the second caller gets the same answer.
     */
    fun stop(): JSONObject = try {
        stopAsync().get(STOP_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (error: Exception) {
        Log.e(TAG, "finalize timed out", error)
        failure("Timed out while finishing the recording")
    }

    /** Non-blocking [stop], for callers that must not hold a thread. */
    fun stopAsync(): CompletableFuture<JSONObject> {        val future = synchronized(lock) {
            if (phase == Phase.IDLE) {
                return CompletableFuture.completedFuture(
                    lastOutput?.let { ok(it) } ?: failure("No screen recording is active"),
                )
            }
            if (pendingStop != null) return@synchronized pendingStop
            CompletableFuture<JSONObject>().also { pendingStop = it }
        } ?: return CompletableFuture.completedFuture(failure("No screen recording is active"))

        if (phase != Phase.FINALIZING) phase = Phase.FINALIZING
        val context = AppHost.applicationContext()
        if (context == null) {
            synchronized(lock) { pendingStop = null }
            return CompletableFuture.completedFuture(failure("The app is not running"))
        }
        ScreenCaptureService.requestStop(context)
        return future
    }

    /** Called by the service once the file is on disk. */
    fun onCaptureStopped(file: File?) {
        phase = Phase.IDLE
        if (file != null) lastOutput = file
        val result = if (file != null) ok(file) else failure("The recording produced no video")
        // A take nobody was waiting for — or an overlay take whose activity
        // detached before it could open the editor — stays offerable on launch.
        val adopted = finishStop(result)
        val openEditor = openEditorOnStop
        openEditorOnStop = false
        val finishHook = if (openEditor) hooks?.onRecordingFinished else null
        if (file != null && ((!adopted && !openEditor) || (openEditor && finishHook == null))) {
            recoverable = file
        }
        emit(EVENT_RECORDING_STATE, JSONObject().put("recording", false))
        hooks?.onRecordingStopped?.invoke()
        if (openEditor && file != null) finishHook?.invoke(file)
    }

    /** Preserves an overlay take for launch recovery if the activity could not open it. */
    fun retainRecoverable(file: File) {
        synchronized(lock) {
            if (phase == Phase.IDLE && recoverable == null) {
                recoverable = file
            }
        }
    }

    /**
     * Ends a take started from the native overlay and asks the activity to open
     * the editor with the finished file.
     *
     * Kept separate from [stopAsync] so the renderer's own stop path, which
     * performs its handoff in the page, is unchanged.
     */
    fun stopForEditor(): CompletableFuture<JSONObject> {
        openEditorOnStop = true
        return stopAsync()
    }

    /**
     * Marks a take as started from the native overlay.
     *
     * If the user ends it from the system notification instead, the activity
     * still knows to open the finished file rather than dropping the user back
     * on the Home with no handoff.
     */
    fun markOverlayTake() {
        openEditorOnStop = true
    }

    /** The overlay start was cancelled; the normal HUD path owns the next take. */
    fun clearOverlayTake() {
        openEditorOnStop = false
    }

    /** Returns whether an outstanding [stopAsync] caller received the file. */
    private fun finishStop(result: JSONObject): Boolean {
        val future = synchronized(lock) {
            pendingStop.also { pendingStop = null }
        } ?: return false
        future.complete(result)
        return result.optBoolean("success", false)
    }

    // ------------------------------------------------------------------ //
    // System-initiated stop (notification action, projection revoked)
    // ------------------------------------------------------------------ //

    /**
     * The user tapped Stop in the notification.
     *
     * This is a *request*, not a stop. The renderer owns the end of a take: it
     * calls `stopNativeScreenRecording`, loads the editor with the result and
     * writes the session, which is also what brings the user back to the app.
     * Stopping natively here would leave a finished file with nobody to open
     * it. [scheduleFallbackStop] covers the case where the page cannot answer.
     */
    fun onStopFromTray() {
        if (!isRecording()) return
        if (openEditorOnStop) {
            // Overlay takes stay on the editor surface (which has no tray-stop
            // listener), so stop right away instead of waiting out the fallback.
            stopAsync()
            return
        }
        emit(EVENT_STOP_FROM_TRAY, JSONObject())
        scheduleFallbackStop()
    }

    /**
     * The system revoked the projection — another app started a capture, or the
     * user stopped it from the status bar.
     *
     * This is not the same event as [onStopFromTray]: the renderer's handler for
     * it adopts the file through [recoverAsync] instead of the normal stop path,
     * because the take has already ended and its latency budget is gone.
     */
    fun onCaptureInterrupted(message: String) {
        if (!isRecording()) return
        if (openEditorOnStop) {
            notifyUser(message)
            stopAsync()
            return
        }
        emit(
            EVENT_RECORDING_INTERRUPTED,
            JSONObject().apply {
                put("reason", "capture-unavailable")
                put("message", message)
            },
        )
        scheduleFallbackStop()
    }

    /**
     * Finalizes without waiting for the renderer, after a grace period.
     *
     * Both system-initiated paths need this: the recorded page may be gone (a
     * WebView can be reclaimed while the user is in another app), and a
     * foreground service that keeps a dead projection alive would hold the
     * notification and the file forever.
     */
    private fun scheduleFallbackStop() {
        finalizer.execute {
            Thread.sleep(FALLBACK_STOP_DELAY_MS)
            if (isRecording()) {
                Log.i(TAG, "renderer did not stop capture; finalizing directly")
                runCatching { stop() }
            }
        }
    }

    /**
     * Hands the renderer a take that ended without its stop path.
     *
     * Called from the `onRecordingInterrupted` handler. The finalize may still be
     * running (the interruption emit and this request are milliseconds apart, but
     * joining several segments is disk-bound), so this waits on the same future
     * rather than racing it to an empty answer. Once the recording has been
     * adopted it is not offered again.
     */
    /**
     * The orphaned take, if there is one, and forget it.
     *
     * Startup-only and synchronous: it runs before the first page load, so the
     * editor can open on the recording instead of the dashboard. A caller that
     * can wait (the interrupted handler) uses [recoverAsync].
     */
    fun takeRecoverable(): File? = synchronized(lock) {
        if (phase != Phase.IDLE) return null
        recoverable.also { recoverable = null }
    }

    fun recoverAsync(): CompletableFuture<JSONObject> {
        synchronized(lock) {
            if (phase != Phase.IDLE) {
                return stopAsync().thenApply { result ->
                    if (!result.optBoolean("success", false)) {
                        JSONObject().put("success", false)
                    } else {
                        JSONObject().apply {
                            put("success", true)
                            put("path", result.optString("path", ""))
                        }
                    }
                }
            }
            // Losing the race to the fallback stop lands here: the file is
            // already written, and this is the only caller that should get it.
            val file = recoverable
            recoverable = null
            return CompletableFuture.completedFuture(
                if (file != null) {
                    JSONObject().apply {
                        put("success", true)
                        put("path", file.path)
                    }
                } else {
                    JSONObject().put("success", false)
                },
            )
        }
    }

    private fun emit(channel: String, payload: JSONObject) {
        emitter?.invoke(channel, payload)
    }

    // ------------------------------------------------------------------ //
    // Shared result shapes
    // ------------------------------------------------------------------ //

    fun ok(file: File): JSONObject = JSONObject().apply {
        put("success", true)
        // `useRecorderStop` hands this straight to `setCurrentVideoPath`, and
        // every other path in the app (sidecar, project, telemetry) is keyed on
        // the absolute file path — the loopback URL embeds a port that changes
        // between runs.
        put("path", file.path)
        put("durationMs", CaptureMath.durationMs(file))
        // Microphone audio is captured and muxed natively. A browser fallback
        // requires getUserMedia and cannot keep recording in a background WebView.
        put("microphoneFallbackRequired", false)
    }

    fun failure(message: String): JSONObject = JSONObject().apply {
        put("success", false)
        // The renderer treats `success:false` on the Windows path as "try the
        // browser API instead", which cannot work inside a WebView. Claiming
        // the user was told is what stops that dead-end fallback; [notifyUser]
        // is where the claim comes true.
        put("userNotified", true)
        put("error", message)
        put("message", message)
    }

    /** Native feedback for paths where the renderer only logs. */
    private fun notifyUser(message: String) {
        val context = AppHost.applicationContext() ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    const val EVENT_RECORDING_STATE = "onRecordingStateChanged"
    const val EVENT_STOP_FROM_TRAY = "onStopRecordingFromTray"
    const val EVENT_RECORDING_INTERRUPTED = "onRecordingInterrupted"

    /** Kept here so the service and the bridge agree on what "done" means. */
    private object CaptureMath {
        /** Duration is read from the file the muxer just wrote, not inferred. */
        fun durationMs(file: File): Long {
            val retriever = android.media.MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } catch (error: Throwable) {
                0L
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /** True when the accessibility service that feeds taps is enabled. */
    fun isTapServiceEnabled(): Boolean = TelemetryAccessibilityService.isEnabled()

    /** Used by the bridge to report the projection state honestly. */
    fun mediaProjectionManager(): MediaProjectionManager? =
        AppHost.applicationContext()?.getSystemService(MediaProjectionManager::class.java)
}
