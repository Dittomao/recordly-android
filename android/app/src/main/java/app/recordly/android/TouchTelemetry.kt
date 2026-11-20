package app.recordly.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Turns taps into the telemetry the renderer's auto-zoom already understands.
 *
 * The renderer's contract is `CursorTelemetryPoint[]` (see
 * `src/components/video-editor/types.ts`): `timeMs`, normalized `cx`/`cy`, and
 * an optional `interactionType`. Auto-zoom only builds a region from *explicit*
 * presses — a `click` paired with a matching `mouseup` via `mouseButton` — so a
 * bare list of positions is not enough (see `cursorInteractionCandidates.ts`).
 *
 * Android gives us no cursor. What it gives us, without taking the touch stream
 * from the recorded app, is the accessibility stream:
 *
 *   TYPE_TOUCH_INTERACTION_START  → press time
 *   TYPE_VIEW_CLICKED             → bounds of the view that was tapped
 *   TYPE_TOUCH_INTERACTION_END    → release time
 *
 * The tapped **view's center** is used as the focus, not a finger coordinate.
 * That is the better signal for a zoom target anyway: it is the thing the user
 * meant to hit, and it does not depend on where inside the target a thumb
 * landed. The cost is that a tap on a large surface resolves to its center —
 * acceptable for v1 and visible in the recordings.
 *
 * Observe-only, by design. `setMotionEventSources` would let us see real
 * coordinates, but it takes input away from the app being recorded.
 */
object TouchTelemetry {
    private const val TAG = "TouchTelemetry"

    /** A tap deeper than this into the past is not the press behind a click event. */
    private const val PRESS_MATCH_WINDOW_MS = 1200L

    /**
     * How long to wait for a real release (`TYPE_TOUCH_INTERACTION_END`) after a
     * click before synthesizing one.
     *
     * This matters more than it looks. `TYPE_VIEW_CLICKED` is reliable — every
     * View-based app sends it — but `TYPE_TOUCH_INTERACTION_END` is emitted by
     * the input pipeline and is not guaranteed at all: on the API 37 emulator it
     * never arrives, for our own app or anyone else's. Waiting for it
     * unconditionally meant taps produced events but *no samples*, which is a
     * silent zero. So the release is a fallback, not a requirement.
     */
    private const val RELEASE_GRACE_MS = 220L

    /**
     * Duration given to a tap whose press we never saw. A view click fires on
     * release, so the button was down for *some* time before it; 80 ms is short
     * enough to read as a tap and long enough that the renderer's press
     * animation has a real interval to animate over.
     */
    private const val SYNTHETIC_HOLD_MS = 80L

    /**
     * Uptime of a sample, kept alongside `timeMs` so the origin can be moved
     * once the first encoded frame's timestamp is known. Stripped from
     * [TouchTelemetry.samples].
     */
    private const val KEY_RAW_UPTIME = "_u"
    private const val KEY_PAUSED_TIME = "_p"

    private val lock = Any()
    private val samples = ArrayList<JSONObject>()

    private var sessionStartUptime = 0L
    private var started = false

    /**
     * Pause state. A recording pause removes a span from the video, so it has to
     * remove the same span from the telemetry or every sample after the resume
     * drifts late by the paused duration. Upstream does exactly this
     * (`ipc/cursor/telemetry.ts`: `elapsed = now - start - accumulatedPaused`),
     * and it is the reason pause/resume has an IPC method of its own.
     */
    private var paused = false
    private var pauseStartedUptime = 0L
    private var pausedTotalMs = 0L

    private var pressDownUptime = 0L
    private var pendingClick: PendingClick? = null

    /** Used to close a click when no release event ever shows up. Set by the service. */
    private var handler: Handler? = null

    private val releaseGrace = Runnable { synchronized(lock) { flushLocked() } }

    /** Screen size the bounds are normalized against; set by the service. */
    private var screenWidth = 1
    private var screenHeight = 1

    private class PendingClick(val cx: Double, val cy: Double, val atUptime: Long)

    val lastEventAt = AtomicLong(0L)

    /** The handler the release fallback is posted on; the service's own looper. */
    fun attach(handler: Handler) {
        this.handler = handler
    }

    /** Full display size, in the orientation the taps happened in. */
    fun setScreenSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenWidth = width
            screenHeight = height
        }
    }

    /**
     * Begins a recording session. Called when capture starts; for the spike it
     * is also called when the accessibility service connects, so the taps made
     * right after enabling it land in a fresh session.
     */
    fun startSession() {
        handler?.removeCallbacks(releaseGrace)
        synchronized(lock) {
            samples.clear()
            sessionStartUptime = android.os.SystemClock.uptimeMillis()
            started = true
            paused = false
            pauseStartedUptime = 0L
            pausedTotalMs = 0L
            pressDownUptime = 0L
            pendingClick = null
        }
        // The session origin is what a capture timestamp gets aligned against:
        // `screenrecord` starts at a known uptime, and sample times are relative
        // to this one, so printing it is what makes the two line up.
        Log.i(TAG, "session started at uptime $sessionStartUptime")
    }

    /** Stops and closes any tap still waiting for its release. */
    fun stopSession() = synchronized(lock) {
        flushLocked()
        started = false
        paused = false
        pauseStartedUptime = 0L
    }

    fun isStarted(): Boolean = synchronized(lock) { started }

    fun isPaused(): Boolean = synchronized(lock) { paused }

    fun sampleCount(): Int = synchronized(lock) { samples.size }

    /**
     * Ends the clock at [atUptime] and stops accepting samples.
     *
     * Idempotent, because both the pause control and `pause-cursor-capture`
     * arrive within the same tick and either may be the first to mean it. The
     * first caller wins the boundary, which is what keeps the video segment
     * offset and the telemetry offset derived from the same instant.
     *
     * A pending tap is closed first: a press that straddles the pause would
     * otherwise produce a `mouseup` after the resume, i.e. a hold spanning a
     * cut that the video no longer contains.
     */
    fun pauseClock(atUptime: Long = android.os.SystemClock.uptimeMillis()): Long =
        synchronized(lock) {
            if (!started || paused) return@synchronized pausedTotalMs
            flushLocked(releaseUptime = atUptime)
            paused = true
            pauseStartedUptime = atUptime
            pausedTotalMs
        }

    /**
     * Restarts the clock at [atUptime] and returns the total paused time so far.
     *
     * The capture service uses the return value as the pause offset of the next
     * video segment, so telemetry and video are re-based by the same number
     * rather than by two independently measured ones.
     */
    fun resumeClock(atUptime: Long = android.os.SystemClock.uptimeMillis()): Long =
        synchronized(lock) {
            if (!started || !paused) return@synchronized pausedTotalMs
            pausedTotalMs += max(0L, atUptime - pauseStartedUptime)
            pauseStartedUptime = 0L
            paused = false
            pausedTotalMs
        }

    /**
     * Moves the session origin to the moment the first video frame was actually
     * captured.
     *
     * The session is opened when the encoder starts, but the first frame only
     * reaches it one compositor round later. Anything captured in that window
     * would sit `lag` ms early against the video, so the origin is corrected to
     * the frame's own timestamp once the encoder reports it. A warm-start pause
     * can finish before that frame arrives; each sample keeps its own pause
     * offset so rebasing remains valid across that boundary.
     */
    fun alignOriginTo(originUptime: Long) = synchronized(lock) {
        if (!started) return@synchronized
        sessionStartUptime = originUptime
        for (sample in samples) {
            val raw = sample.optLong(KEY_RAW_UPTIME, Long.MIN_VALUE)
            if (raw != Long.MIN_VALUE) {
                sample.put("timeMs", max(0L, raw - originUptime - sample.optLong(KEY_PAUSED_TIME, 0L)))
            }
        }
    }

    /** The samples collected so far, in the renderer's own shape. */
    fun samples(): JSONArray = synchronized(lock) {
        // The raw uptime is bookkeeping for [alignOriginTo] and is not part of
        // the renderer's `CursorTelemetryPoint`; it must not reach the sidecar.
        JSONArray().apply {
            for (sample in samples) {
                put(JSONObject(sample.toString()).apply {
                    remove(KEY_RAW_UPTIME)
                    remove(KEY_PAUSED_TIME)
                })
            }
        }
    }

    /**
     * True while taps should be recorded: a session is open and not paused.
     */
    private fun active(): Boolean = synchronized(lock) { started && !paused }

    /**
     * Records one accessibility event. Called on the service thread; must stay
     * cheap because it runs for every event while recording.
     */
    fun onEvent(event: AccessibilityEvent, bounds: () -> Rect?) {
        if (!active()) return
        val at = event.eventTime.let { if (it > 0) it else android.os.SystemClock.uptimeMillis() }
        lastEventAt.set(at)

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ->
                synchronized(lock) { if (started && !paused) pressDownUptime = at }

            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ->
                synchronized(lock) { flushLocked(releaseUptime = at) }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            -> {
                val rect = bounds() ?: return
                if (rect.width() <= 0 || rect.height() <= 0) return
                val cx = (rect.exactCenterX().toDouble() / screenWidth).coerceIn(0.0, 1.0)
                val cy = (rect.exactCenterY().toDouble() / screenHeight).coerceIn(0.0, 1.0)
                synchronized(lock) {
                    if (!started || paused) return
                    // Two taps inside the grace window: close the first now, or
                    // the second would replace it and the first would vanish.
                    flushLocked()
                    pendingClick = PendingClick(cx, cy, at)
                }
                handler?.postDelayed(releaseGrace, RELEASE_GRACE_MS)
            }

            // A swipe is not an interaction. Touch framing is tap-anchored, and
            // emitting a scroll position used to look like cursor motion to the
            // dwell/camera heuristics. The scroll stays in the video; it adds no
            // telemetry.
        }
    }

    /**
     * Emits the press/release pair for the tap in hand.
     *
     * Called either when a real release arrives, or — far more often — from the
     * grace timeout, in which case the release time is the click itself and the
     * press is back-dated. The output is always a *pair*: an explicit click plus
     * its `mouseup`. That is what the renderer needs to build a zoom region
     * (`getCursorPressIntervals` → `detectInteractionCandidates`); a lone click
     * also works there, but pairing costs nothing and gives the press animation
     * a real interval.
     *
     * Caller holds [lock].
     */
    private fun flushLocked(releaseUptime: Long? = null) {
        if (!started || paused) return
        val pending = pendingClick ?: return
        pendingClick = null

        val clickAt = pending.atUptime
        val down = if (
            pressDownUptime in 1..clickAt &&
            clickAt - pressDownUptime <= PRESS_MATCH_WINDOW_MS
        ) pressDownUptime else clickAt - SYNTHETIC_HOLD_MS
        val up = (releaseUptime ?: clickAt).coerceAtLeast(down + 1)

        samples.add(point(down, pending.cx, pending.cy, "click", button = 1))
        samples.add(point(up, pending.cx, pending.cy, "mouseup", button = 1))
        pressDownUptime = 0L
    }

    private fun point(
        uptime: Long,
        cx: Double,
        cy: Double,
        kind: String,
        button: Int? = null,
    ): JSONObject = JSONObject().apply {
        // Session-relative, minus any paused span, clamped at zero: a stray
        // event from before the session began must not land before the first
        // frame. This is upstream's `getCursorCaptureElapsedMs` exactly.
        put("timeMs", elapsedForLocked(uptime))
        put(KEY_RAW_UPTIME, uptime)
        put(KEY_PAUSED_TIME, pausedTotalMs)
        put("cx", (cx * 10_000).toInt() / 10_000.0)
        put("cy", (cy * 10_000).toInt() / 10_000.0)
        put("interactionType", kind)
        if (button != null) put("mouseButton", button)
    }

    /**
     * Video time of a wall-clock instant, in ms.
     *
     * `now - origin - pausedSoFar`, where the pause in progress (if the instant
     * falls inside one) has not been added to [pausedTotalMs] yet.
     */
    private fun elapsedForLocked(uptime: Long): Long {
        val activePause =
            if (paused && uptime > pauseStartedUptime) uptime - pauseStartedUptime else 0L
        return max(0L, uptime - sessionStartUptime - pausedTotalMs - activePause)
    }

    fun clear() = synchronized(lock) { samples.clear() }
}

/**
 * The service itself is a thin adapter: it reads the event's source bounds and
 * hands the event to [TouchTelemetry], which owns all the state.
 */
class TelemetryAccessibilityService : AccessibilityService() {

    private val boundsRect = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        val metrics = resources.displayMetrics
        TouchTelemetry.setScreenSize(metrics.widthPixels, metrics.heightPixels)
        TouchTelemetry.attach(Handler(Looper.getMainLooper()))
        // The session boundary belongs to capture, not to this service: taps
        // only become telemetry while a recording is running, and their times
        // are relative to the first encoded frame. Connecting here used to open
        // a session so the spike could observe taps; that now produces a
        // timeline that no video is aligned to.
        Log.i("TelemetryService", "connected, screen ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Our own controls and the system UI are not part of the demo. The
        // notification's Pause/Stop taps used to become zoom regions at the end
        // of every take; they are recorded in the video, not as interactions.
        val source = event.packageName?.toString()
        if (source != null && (source == packageName || source in SYSTEM_PACKAGES)) return

        val type = event.eventType
        if (type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            type == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END
        ) {
            // Press/release timing only; no node and no bounds needed.
            TouchTelemetry.onEvent(event) { null }
            return
        }
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            type != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        ) {
            // Scroll, focus and window-state events do not drive tap zoom.
            return
        }

        // Only bounds are read; the node is dropped immediately.
        val rect = boundsOf(event)
        if (DEBUG_EVENTS) {
            Log.d(
                "TelemetryService",
                "event ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "pkg=${event.packageName} win=${event.windowId} " +
                    "cls=${event.className} bounds=$rect " +
                    "text=${event.text} desc=${event.contentDescription}",
            )
        }
        TouchTelemetry.onEvent(event) { rect }
    }

    private fun boundsOf(event: AccessibilityEvent): Rect? {
        val node = event.source ?: return null
        return try {
            node.getBoundsInScreen(boundsRect)
            Rect(boundsRect)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        /**
         * Windows that are chrome, not the demo: notification shade controls,
         * permission dialogs, and the system's own UI.
         */
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "android",
        )

        /**
         * One log line per accessibility event. Off by default: during capture
         * this is a line per event, which is noise. Flip it on first when a tap
         * is not producing the sample it should.
         */
        private const val DEBUG_EVENTS = false

        /**
         * True when the user has enabled this service in system settings.
         *
         * The renderer asks (`getAccessibilityPermissionStatus`) so it can tell
         * the user why auto-zoom will be empty, so the answer has to come from
         * the setting rather than from whether we happen to be connected: the
         * setting is what survives a force-stop, and it is also what the user
         * can observe.
         */
        fun isEnabled(): Boolean {
            val context = AppHost.applicationContext() ?: return false
            val target = android.content.ComponentName(
                context,
                TelemetryAccessibilityService::class.java,
            )
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':')
                .mapNotNull { android.content.ComponentName.unflattenFromString(it.trim()) }
                .any { it.packageName == target.packageName && it.className == target.className }
        }
    }
}
