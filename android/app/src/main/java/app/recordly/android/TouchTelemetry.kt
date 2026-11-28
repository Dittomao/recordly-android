package app.recordly.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
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
     * `TYPE_VIEW_CLICKED` confirms an activation where an app emits it.
     * `TYPE_TOUCH_INTERACTION_END` is emitted by
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
    private const val SEMANTIC_DUPLICATE_MS = 150L
    private const val FOCUS_SUPPRESS_MS = 450L

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
    private var collectingSinceUptime = 0L
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
    private var lastAcceptedClick: PendingClick? = null

    /** Used to close a click when no release event ever shows up. Set by the service. */
    private var handler: Handler? = null

    private val releaseGrace = Runnable { synchronized(lock) { flushLocked() } }

    /** Screen size the bounds are normalized against; set by the service. */
    private var screenWidth = 1
    private var screenHeight = 1

    private class PendingClick(
        val cx: Double,
        val cy: Double,
        val atUptime: Long,
        val source: String?,
        val semantic: Boolean,
    )

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
            collectingSinceUptime = sessionStartUptime
            started = true
            paused = false
            pauseStartedUptime = 0L
            pausedTotalMs = 0L
            pressDownUptime = 0L
            pendingClick = null
            lastAcceptedClick = null
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
            lastAcceptedClick = null
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
            collectingSinceUptime = atUptime
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
    fun isCollecting(): Boolean = synchronized(lock) { started && !paused }

    fun currentPausedTotalMs(): Long = synchronized(lock) { pausedTotalMs }

    /** Only accept a horizontal navigation/tab item inside a bounded bar strip. */
    fun isHorizontalNavigationTab(tab: Rect, bar: Rect): Boolean = synchronized(lock) {
        if (tab.isEmpty || bar.isEmpty) return@synchronized false
        val maxTabHeight = (screenHeight / 6).coerceAtLeast(24)
        val maxBarHeight = (screenHeight / 4).coerceAtLeast(1)
        val minBarWidth = (screenWidth * 0.42).toInt()
        bar.width() >= minBarWidth &&
            bar.height() in tab.height()..maxBarHeight &&
            tab.width() in 24..(screenWidth * 0.65).toInt() &&
            tab.height() in 24..maxTabHeight &&
            bar.width() >= (tab.width() * 1.55).toInt() &&
            tab.top >= bar.top - 12 &&
            tab.bottom <= bar.bottom + 12
    }

    /** Backwards-compatible alias kept clear for bottom bar checks. */
    fun isBottomNavigationTab(tab: Rect, bar: Rect): Boolean = synchronized(lock) {
        bar.width() >= screenWidth * 0.8 &&
            bar.top >= screenHeight * 0.75 && bar.bottom >= screenHeight * 0.85 &&
            isHorizontalNavigationTab(tab, bar)
    }

    /**
     * Reject empty, mostly-offscreen slide-transition boxes, and giant root
     * containers that would zoom onto `(0.5, 0.5)` instead of a real control.
     */
    fun normalizeInteractiveBounds(raw: Rect): Rect? = synchronized(lock) {
        if (raw.isEmpty || screenWidth <= 1 || screenHeight <= 1) return@synchronized null
        val clipped = Rect(raw)
        if (!clipped.intersect(0, 0, screenWidth, screenHeight)) return@synchronized null
        val rawArea = raw.width().toLong() * raw.height().toLong()
        val clippedArea = clipped.width().toLong() * clipped.height().toLong()
        if (rawArea <= 0L || clippedArea * 4L < rawArea) return@synchronized null
        val oversizedContainer =
            clipped.width() >= (screenWidth * 0.90).toInt() &&
                clipped.height() >= (screenHeight * 0.72).toInt()
        if (oversizedContainer) return@synchronized null
        clipped
    }

    /** Accept real launcher app shortcuts/dock icons, never widgets or cards. */
    fun isLauncherIcon(icon: Rect): Boolean = synchronized(lock) {
        val clipped = normalizeInteractiveBounds(icon) ?: return@synchronized false
        clipped.top >= 40 &&
            clipped.width() in (screenWidth / 12).coerceAtLeast(36)..(screenWidth / 2) &&
            clipped.height() in (screenHeight / 20).coerceAtLeast(40)..(screenHeight / 4)
    }

    /** Limit legacy bottom-dock recognition to bounded dock icons. */
    fun isDockIcon(icon: Rect): Boolean = synchronized(lock) {
        isLauncherIcon(icon) &&
            icon.top >= screenHeight * 0.8 &&
            icon.width() <= screenWidth / 3 &&
            icon.height() <= screenHeight / 5
    }

    /**
     * Records one accessibility event. Called on the service thread; must stay
     * cheap because it runs for every event while recording.
     */
    fun onEvent(event: AccessibilityEvent, bounds: () -> Rect?) {
        if (!isCollecting()) return
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
                acceptClick(at, event.packageName?.toString(), bounds() ?: return, semantic = false)
            }

            // A swipe is not an interaction. Touch framing is tap-anchored, and
            // emitting a scroll position used to look like cursor motion to the
            // dwell/camera heuristics. The scroll stays in the video; it adds no
            // telemetry.
        }
    }

    /** An app-specific activation with verified bounds, but no physical down/up event. */
    fun onSemanticActivation(
        event: AccessibilityEvent,
        rect: Rect,
        onlyIfNoRecentTap: Boolean = false,
    ) = onSemanticActivation(event.eventTime, event.packageName?.toString(), rect, onlyIfNoRecentTap)

    fun onSemanticActivation(
        atUptime: Long,
        source: String?,
        rect: Rect,
        onlyIfNoRecentTap: Boolean = false,
    ) {
        val at = atUptime.takeIf { it > 0 } ?: android.os.SystemClock.uptimeMillis()
        acceptClick(at, source, rect, semantic = true, onlyIfNoRecentTap = onlyIfNoRecentTap)
    }

    private fun acceptClick(
        at: Long,
        source: String?,
        rect: Rect,
        semantic: Boolean,
        onlyIfNoRecentTap: Boolean = false,
    ) {
        if (rect.isEmpty) return
        synchronized(lock) {
            if (!started || paused) return
            val effectiveAt = if (semantic && at < collectingSinceUptime) {
                if (collectingSinceUptime - at <= 500L) collectingSinceUptime else return
            } else {
                at
            }
            val cx = (rect.exactCenterX().toDouble() / screenWidth).coerceIn(0.0, 1.0)
            val cy = (rect.exactCenterY().toDouble() / screenHeight).coerceIn(0.0, 1.0)
            val previous = lastAcceptedClick
            if (onlyIfNoRecentTap && previous != null && effectiveAt - previous.atUptime in 0L..FOCUS_SUPPRESS_MS) {
                return
            }
            // Some app versions emit both signals for one control activation. Do
            // not deduplicate two ordinary clicks, even at the same location.
            if (previous != null && (semantic || previous.semantic) && source == previous.source &&
                effectiveAt - previous.atUptime in 0L..SEMANTIC_DUPLICATE_MS &&
                abs(cx - previous.cx) < 0.03 && abs(cy - previous.cy) < 0.03
            ) return
            flushLocked()
            val click = PendingClick(cx, cy, effectiveAt, source, semantic)
            lastAcceptedClick = click
            pendingClick = click
            if (semantic) flushLocked(releaseUptime = effectiveAt)
        }
        if (!semantic) handler?.postDelayed(releaseGrace, RELEASE_GRACE_MS)
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
            !pending.semantic && pressDownUptime in 1..clickAt &&
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastChromeMenuWindowId: Int? = null
    private var homeGeneration = 0
    private var visibleHomeIcons = emptyMap<String, Rect>()
    private var homePackageName: String? = null
    private var homeEnteredAt = 0L
    private var homeClickSeen = false
    private var dockSplash: DockSplash? = null
    private var lastWindowPackage: String? = null
    private var lastActivityClass: String? = null
    private var lastExternalWindowOpenAt = 0L
    private var pendingChromePopupStartAt = 0L
    private var lastFocusedPackage: String? = null
    private var lastFocusedRect: Rect? = null
    private var lastFocusedAt = 0L
    private var cachedHomePackages: Set<String>? = null
    private var cachedLaunchLabels: Map<String, String>? = null

    private data class DockSplash(val target: String, val atUptime: Long)

    override fun onServiceConnected() {
        super.onServiceConnected()
        val metrics = resources.displayMetrics
        TouchTelemetry.setScreenSize(metrics.widthPixels, metrics.heightPixels)
        TouchTelemetry.attach(handler)
        // The session boundary belongs to capture, not to this service: taps
        // only become telemetry while a recording is running, and their times
        // are relative to the first encoded frame. Connecting here used to open
        // a session so the spike could observe taps; that now produces a
        // timeline that no video is aligned to.
        Log.i("TelemetryService", "connected, screen ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Keep display bounds current cleanly across rotation if any.
        val metrics = resources.displayMetrics
        TouchTelemetry.setScreenSize(metrics.widthPixels, metrics.heightPixels)
        // Even bounds and control IDs are read only during active capture.
        if (!TouchTelemetry.isCollecting()) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                clearHome()
                pendingChromePopupStartAt = 0L
                lastChromeMenuWindowId = null
                lastFocusedPackage = null
                lastFocusedRect = null
                lastFocusedAt = 0L
            }
            return
        }
        // Our own controls and the system UI are not part of the demo. The
        // notification's Pause/Stop taps used to become zoom regions at the end
        // of every take; they are recorded in the video, not as interactions.
        val source = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateWindowTransitions(event, source)
            handleHomeTransition(event, source)
            imeOpenedInputBounds(event, source)?.let { (at, targetPkg, rect) ->
                TouchTelemetry.onSemanticActivation(at, targetPkg, rect, onlyIfNoRecentTap = true)
            }
        }
        if (source != null && (source == packageName || source in SYSTEM_PACKAGES)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            -> TouchTelemetry.onEvent(event) { null }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            -> {
                if (source != null && source in resolveHomePackages()) {
                    homeClickSeen = true
                }
                TouchTelemetry.onEvent(event) { boundsOf(event) }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED ->
                focusedInputBounds(event, source)?.let { rect ->
                    TouchTelemetry.onSemanticActivation(event, rect, onlyIfNoRecentTap = true)
                }

            AccessibilityEvent.TYPE_VIEW_SELECTED ->
                selectedTabBounds(event)?.let { TouchTelemetry.onSemanticActivation(event, it) }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (source == CHROME) {
                chromeMenuButtonBounds(event)?.let { (at, rect) ->
                    TouchTelemetry.onSemanticActivation(at, CHROME, rect)
                }
            }
        }
    }

    private fun clearHome() {
        homeGeneration++
        visibleHomeIcons = emptyMap()
        homePackageName = null
        homeEnteredAt = 0L
        homeClickSeen = false
        dockSplash = null
    }

    private fun updateWindowTransitions(event: AccessibilityEvent, source: String?) {
        val cls = event.className?.toString() ?: return
        if (source == null || source == packageName || source in SYSTEM_PACKAGES) return
        if (source == CHROME && (cls.endsWith("TabbedActivity") || cls.endsWith("CustomTabActivity"))) {
            lastChromeMenuWindowId = null
        }
        val looksLikeActivity = '.' in cls &&
            !cls.startsWith("android.widget.") &&
            !cls.startsWith("android.view.") &&
            !cls.startsWith("android.inputmethodservice.")
        if (source != lastWindowPackage || (looksLikeActivity && cls.endsWith("Activity") && cls != lastActivityClass)) {
            if (source != lastWindowPackage) {
                lastChromeMenuWindowId = null
                lastFocusedPackage = null
                lastFocusedRect = null
                lastFocusedAt = 0L
            }
            lastWindowPackage = source
            if (looksLikeActivity) lastActivityClass = cls
            val at = event.eventTime
            if (at > 0L) lastExternalWindowOpenAt = at
        }
    }

    private fun resolveHomePackages(): Set<String> {
        cachedHomePackages?.let { return it }
        val resolved = linkedSetOf<String>().apply {
            addAll(KNOWN_HOME_PACKAGES)
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val entries = packageManager.queryIntentActivities(intent, 0)
            for (info in entries) {
                info.activityInfo?.packageName?.let(::add)
            }
        }
        cachedHomePackages = resolved
        return resolved
    }

    private fun resolveLaunchLabels(): Map<String, String> {
        cachedLaunchLabels?.let { return it }
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val entries = pm.queryIntentActivities(intent, 0)
        val map = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        for (info in entries) {
            val pkg = info.activityInfo?.packageName ?: continue
            val label = info.loadLabel(pm)?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val existing = map[label]
            if (existing != null && existing != pkg) {
                ambiguous.add(label)
            } else {
                map[label] = pkg
            }
        }
        for (label in ambiguous) map.remove(label)
        cachedLaunchLabels = map
        return map
    }

    private fun isHomeActivityClass(source: String?, eventClass: String?): Boolean {
        if (source == null || eventClass == null) return false
        if (source !in resolveHomePackages()) return false
        if (eventClass == OXYGEN_HOME_ACTIVITY || eventClass == PIXEL_HOME_ACTIVITY) return true
        return (eventClass.endsWith(".Launcher") || eventClass.endsWith("LauncherActivity") ||
            eventClass.contains("Launcher", ignoreCase = false)) &&
            !eventClass.contains("Recents", ignoreCase = true)
    }

    /**
     * Launchers such as OxygenOS Home and Android 16 Pixel Launcher sometimes open
     * an app via gesture/touch hooks without emitting `TYPE_VIEW_CLICKED`. Remember
     * uniquely labeled Home/dock icon positions while Home is active and consume one
     * only when the target app opens directly from Home without a normal click.
     */
    private fun handleHomeTransition(event: AccessibilityEvent, source: String?) {
        val eventClass = event.className?.toString()
        // Ignore Home Google Search widget layout noise and outgoing Play Store pane updates.
        if ((source == GOOGLE_SEARCH && eventClass == "android.widget.FrameLayout") ||
            (source == PLAY_STORE && eventClass == "android.view.ViewGroup")
        ) return
        if (source != null && isHomeActivityClass(source, eventClass)) {
            clearHome()
            val generation = homeGeneration
            val windowId = event.windowId
            homePackageName = source
            homeEnteredAt = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
            visibleHomeIcons = readHomeIcons(windowId, source)
            // Always schedule one settle refresh so page switches and "Apps list"
            // drawer transitions update visible icon bounds cleanly.
            handler.postDelayed({
                if (generation == homeGeneration && TouchTelemetry.isCollecting()) {
                    val refreshed = readHomeIcons(windowId, source)
                    if (refreshed.isNotEmpty()) visibleHomeIcons = refreshed
                }
            }, 140L)
            return
        }
        // Recents / non-home windows or already-clicked launcher launches must not synthesize another tap.
        if (homePackageName == null || visibleHomeIcons.isEmpty()) return
        if (homeClickSeen) {
            clearHome()
            return
        }
        val splash = dockSplash
        val at = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        if (source != null && source in visibleHomeIcons &&
            eventClass == "android.widget.FrameLayout" &&
            (splash == null || splash.target == source)
        ) {
            if (splash == null) dockSplash = DockSplash(source, at)
            return
        }
        val isSplashMatch = splash != null && splash.target == source &&
            at - splash.atUptime in 0L..DOCK_SPLASH_TIMEOUT_MS
        val isDirectLaunch = source != null && source in visibleHomeIcons &&
            eventClass != "android.widget.FrameLayout"
        val icon = if (isSplashMatch || isDirectLaunch) visibleHomeIcons[source] else null
        val launcherPkg = homePackageName
        val activationAt = if (splash != null && splash.target == source) {
            (splash.atUptime - 80L).coerceAtLeast(homeEnteredAt)
        } else {
            (at - LAUNCH_TRANSITION_DELAY_MS).coerceAtLeast(homeEnteredAt)
        }
        clearHome()
        if (icon != null && launcherPkg != null) {
            TouchTelemetry.onSemanticActivation(activationAt, launcherPkg, icon)
        }
    }

    private fun readHomeIcons(windowId: Int, homePkg: String): Map<String, Rect> {
        val root = rootInActiveWindow
            ?.takeIf { it.windowId == windowId && it.packageName?.toString() == homePkg }
            ?: return emptyMap()
        val labels = resolveLaunchLabels()
        if (labels.isEmpty()) return emptyMap()
        val matched = HashMap<String, Rect>()
        val duplicated = HashSet<String>()
        var visited = 0

        fun collectChildLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
            val own = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: node.contentDescription?.toString()?.trim()
                    ?.removePrefix(PIXEL_PREDICTED_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
            if (own != null) return own
            if (depth >= 2) return null
            val childCount = node.childCount.coerceAtMost(6)
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    val found = collectChildLabel(child, depth + 1)
                    if (found != null) return found
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
            return null
        }

        fun visit(node: AccessibilityNodeInfo) {
            if (visited++ >= MAX_HOME_ICON_NODES) return
            if (node.isClickable && node.isVisibleToUser) {
                val rect = Rect().also(node::getBoundsInScreen)
                if (TouchTelemetry.isLauncherIcon(rect)) {
                    val directText = node.text?.toString()?.trim()
                    val directDesc = node.contentDescription?.toString()?.trim()
                        ?.removePrefix(PIXEL_PREDICTED_PREFIX)?.trim()
                    val labelCandidate = listOfNotNull(directText, directDesc)
                        .firstOrNull { it.isNotEmpty() }
                        ?: collectChildLabel(node)
                    val pkg = labelCandidate?.let { labels[it] }
                    if (pkg != null && pkg !in duplicated) {
                        val existing = matched[pkg]
                        if (existing != null && existing != rect) {
                            matched.remove(pkg)
                            duplicated.add(pkg)
                        } else {
                            matched[pkg] = rect
                        }
                    }
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                try {
                    visit(child)
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
        }

        try {
            visit(root)
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
        return matched
    }

    /**
     * Recognize selected horizontal navigation tabs and segmented bars across apps
     * (bottom bars, Material `TabLayout`, Compose `NavigationBar`/`TabRow`), while
     * rejecting initial launch selections, sliders/progress indicators, and ViewPager swipes.
     */
    private fun selectedTabBounds(event: AccessibilityEvent): Rect? {
        val cls = event.className?.toString()
        if (cls != null && (
                cls == "androidx.viewpager.widget.ViewPager" ||
                    cls == "androidx.viewpager2.widget.ViewPager2" ||
                    cls.endsWith("ProgressBar") ||
                    cls.endsWith("SeekBar") ||
                    cls.endsWith("RatingBar")
                )
        ) {
            return null
        }
        val at = event.eventTime
        if (at > 0L && lastExternalWindowOpenAt > 0L &&
            at - lastExternalWindowOpenAt in 0L until TAB_SETTLE_AFTER_WINDOW_MS
        ) {
            return null
        }
        val source = event.source ?: return null
        var selectedNode: AccessibilityNodeInfo? = null
        val ancestors = ArrayList<AccessibilityNodeInfo>(3)
        return try {
            selectedNode = resolveSelectedTabNode(event, source) ?: return null
            val rawTab = Rect().also(selectedNode::getBoundsInScreen)
            val tab = TouchTelemetry.normalizeInteractiveBounds(rawTab) ?: return null
            var cur = selectedNode.parent
            repeat(3) {
                val parent = cur ?: return@repeat
                ancestors.add(parent)
                val barRect = Rect().also(parent::getBoundsInScreen)
                if (TouchTelemetry.isHorizontalNavigationTab(tab, barRect)) return tab
                cur = parent.parent
            }
            null
        } finally {
            for (node in ancestors) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
            if (selectedNode != null && selectedNode !== source) {
                @Suppress("DEPRECATION")
                selectedNode.recycle()
            }
            @Suppress("DEPRECATION")
            source.recycle()
        }
    }

    private fun resolveSelectedTabNode(
        event: AccessibilityEvent,
        source: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? {
        if (source.isSelected && source.isVisibleToUser) return source
        if (source.isScrollable) return null
        val count = source.childCount
        if (count <= 0) return null
        val idx = event.currentItemIndex
        if (idx in 0 until count) {
            val child = source.getChild(idx)
            if (child != null) {
                if (child.isVisibleToUser && !child.isScrollable) return child
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        var found: AccessibilityNodeInfo? = null
        for (i in 0 until count.coerceAtMost(12)) {
            val child = source.getChild(i) ?: continue
            if (child.isSelected && child.isVisibleToUser && !child.isScrollable) {
                if (found != null) {
                    @Suppress("DEPRECATION")
                    found.recycle()
                    @Suppress("DEPRECATION")
                    child.recycle()
                    return null
                }
                found = child
            } else {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return found
    }

    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: return false
        return cls == "android.widget.EditText" ||
            cls == "android.widget.AutoCompleteTextView" ||
            cls == "android.widget.MultiAutoCompleteTextView" ||
            cls == "androidx.appcompat.widget.SearchView"
    }

    private fun acceptFocusedInputRect(at: Long, pkg: String?, rect: Rect): Rect? {
        val previousRect = lastFocusedRect
        if (pkg == lastFocusedPackage && previousRect != null &&
            at - lastFocusedAt in 0L..FOCUS_REPEAT_WINDOW_MS &&
            abs(rect.centerX() - previousRect.centerX()) <= 24 &&
            abs(rect.centerY() - previousRect.centerY()) <= 24
        ) {
            return null
        }
        lastFocusedPackage = pkg
        lastFocusedRect = Rect(rect)
        lastFocusedAt = at
        return rect
    }

    private fun focusedInputBounds(event: AccessibilityEvent, sourcePkg: String?): Rect? {
        val at = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        if (lastExternalWindowOpenAt > 0L && at - lastExternalWindowOpenAt in 0L..FOCUS_WINDOW_SETTLE_MS) {
            return null
        }
        val node = event.source ?: return null
        return try {
            if (!node.isFocused || !node.isVisibleToUser || !node.isEnabled || !isTextInputNode(node)) {
                return null
            }
            val raw = Rect().also(node::getBoundsInScreen)
            val rect = TouchTelemetry.normalizeInteractiveBounds(raw) ?: return null
            acceptFocusedInputRect(at, sourcePkg, rect)
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    private fun imeOpenedInputBounds(
        event: AccessibilityEvent,
        sourcePkg: String?,
    ): Triple<Long, String, Rect>? {
        val cls = event.className?.toString() ?: return null
        if (cls != "android.inputmethodservice.SoftInputWindow") return null
        if (event.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED != 0) {
            return null
        }
        val at = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in appWindows) {
            val root = window.root ?: continue
            var focused: AccessibilityNodeInfo? = null
            try {
                focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                if (!focused.isVisibleToUser || !focused.isEnabled || !isTextInputNode(focused)) continue
                val focusedPkg = focused.packageName?.toString() ?: root.packageName?.toString() ?: continue
                if (focusedPkg == packageName || focusedPkg in SYSTEM_PACKAGES || focusedPkg == sourcePkg) continue
                val rect = TouchTelemetry.normalizeInteractiveBounds(Rect().also(focused::getBoundsInScreen))
                    ?: continue
                val accepted = acceptFocusedInputRect(at, focusedPkg, rect) ?: continue
                return Triple(at, focusedPkg, accepted)
            } finally {
                @Suppress("DEPRECATION")
                focused?.recycle()
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return null
    }

    /**
     * Chrome opens the overflow via a touch listener without emitting a click
     * for its button. Track popup opening time so the tap timestamp aligns with
     * the press rather than waiting until `app_menu_list` finishes laying out.
     */
    private fun chromeMenuButtonBounds(event: AccessibilityEvent): Pair<Long, Rect>? {
        if (event.className?.toString() != "android.widget.FrameLayout" ||
            event.windowId == lastChromeMenuWindowId
        ) return null
        val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val popup = applicationWindows.firstOrNull { it.id == event.windowId }?.root ?: return null
        val popupRect = Rect().also(popup::getBoundsInScreen)
        val hasMenuList = popup.packageName?.toString() == CHROME &&
            popup.findAccessibilityNodeInfosByViewId("$CHROME:id/app_menu_list").isNotEmpty()
        if (!hasMenuList) {
            if (event.windowId == lastChromeMenuWindowId) lastChromeMenuWindowId = null
            val maxW = (resources.displayMetrics.widthPixels * 0.9).toInt()
            val maxH = resources.displayMetrics.heightPixels / 2
            if (!popupRect.isEmpty && popupRect.width() < maxW && popupRect.height() in 1 until maxH) {
                pendingChromePopupStartAt = event.eventTime
            }
            return null
        }
        for (window in applicationWindows) {
            if (window.id == event.windowId) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() != CHROME) continue
            // The popup may obscure its own anchor; the toolbar view still has
            // the correct bounds even if Android reports it as not visible.
            val button = root.findAccessibilityNodeInfosByViewId("$CHROME:id/menu_button")
                .firstOrNull { it.isClickable } ?: continue
            val bounds = TouchTelemetry.normalizeInteractiveBounds(Rect().also(button::getBoundsInScreen))
                ?: continue
            lastChromeMenuWindowId = event.windowId
            val popupStart = pendingChromePopupStartAt
            pendingChromePopupStartAt = 0L
            val at = event.eventTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
            val activatedAt = if (popupStart in 1L..at && at - popupStart <= 500L) {
                popupStart - 60L
            } else {
                at - CHROME_MENU_OPEN_DELAY_MS
            }
            return activatedAt.coerceAtLeast(1L) to bounds
        }
        return null
    }

    private fun boundsOf(event: AccessibilityEvent): Rect? {
        val node = event.source ?: return null
        return try {
            node.getBoundsInScreen(boundsRect)
            val normalized = TouchTelemetry.normalizeInteractiveBounds(boundsRect)
            if (normalized != null) return normalized
            if (node.childCount == 1) {
                val child = node.getChild(0) ?: return null
                try {
                    if (child.isVisibleToUser && (child.isClickable || child.isFocusable)) {
                        child.getBoundsInScreen(boundsRect)
                        return TouchTelemetry.normalizeInteractiveBounds(boundsRect)
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    child.recycle()
                }
            }
            null
        } finally {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val CHROME = "com.android.chrome"
        private const val PLAY_STORE = "com.android.vending"
        private const val OXYGEN_HOME = "com.android.launcher"
        private const val PIXEL_HOME = "com.google.android.apps.nexuslauncher"
        private const val AOSP_LAUNCHER = "com.android.launcher3"
        private const val GOOGLE_SEARCH = "com.google.android.googlequicksearchbox"
        private const val OXYGEN_HOME_ACTIVITY = "$OXYGEN_HOME.Launcher"
        private const val PIXEL_HOME_ACTIVITY = "$PIXEL_HOME.NexusLauncherActivity"
        private const val PIXEL_PREDICTED_PREFIX = "Predicted app:"
        private const val DOCK_SPLASH_TIMEOUT_MS = 3500L
        private const val LAUNCH_TRANSITION_DELAY_MS = 220L
        private const val CHROME_MENU_OPEN_DELAY_MS = 220L
        private const val TAB_SETTLE_AFTER_WINDOW_MS = 150L
        private const val FOCUS_WINDOW_SETTLE_MS = 350L
        private const val FOCUS_REPEAT_WINDOW_MS = 2000L
        private const val MAX_HOME_ICON_NODES = 1500
        private val KNOWN_HOME_PACKAGES = setOf(OXYGEN_HOME, PIXEL_HOME, AOSP_LAUNCHER)

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
