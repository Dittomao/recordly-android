package app.recordly.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The floating recorder setup, rendered with the actual Sileo component.
 *
 * A native Canvas copy of Sileo's silhouette was never the interaction: the
 * product's fluid shape comes from two SVG rectangles in one Gaussian-blur and
 * alpha-threshold filter, moved by a 600 ms spring. That implementation is
 * upstream React, so the overlay is a small transparent [WebView] that loads
 * only [recorder.html] — not the editor renderer — positioned at the top center
 * of the foreground activity's root container.
 *
 * Keeping the surface inside the attached activity root avoids Android
 * WindowManager move animations when expanding horizontally, avoids WebView
 * height-resize stalls, and avoids Android's 0.8 alpha clamp on untouchable
 * system alert windows — while selective touch forwarding lets taps beside the
 * compact pill and outside taps when expanded reach the app underneath.
 *
 * The setup is removed before capture starts. Pause/stop remain in the
 * foreground notification; see `android/README.md`.
 */
object SileoOverlay {

    private const val TAG = "SileoOverlay"

    private const val READY_TIMEOUT_MS = 12_000L
    private const val START_DELAY_MS = 480L

    /** Compact pill width including horizontal shadow/padding margin. */
    private const val COMPACT_DP = 182
    private const val FIXED_WEBVIEW_HEIGHT_DP = 360
    private const val INITIAL_BODY_HEIGHT_DP = 248

    private val main = Handler(Looper.getMainLooper())

    private class Host(
        val activityRoot: FrameLayout,
        val root: OverlayRoot,
        val webView: WebView,
        val baseUrl: String,
        /** Width of the expanded body, in pixels. */
        val bodyWidth: Int,
        /** Total WebView height, in pixels. */
        val webViewHeight: Int,
    ) {
        var ready = false
        var expanded = false
        val finished = AtomicBoolean(false)
    }

    private var activityRef: WeakReference<Activity>? = null
    private var activityRootRef: WeakReference<FrameLayout>? = null

    @Volatile
    private var host: Host? = null

    @Volatile
    private var starting = false

    private var pendingStartRunnable: Runnable? = null

    fun attach(activity: Activity, root: FrameLayout) {
        val bind = Runnable {
            activityRef = WeakReference(activity)
            activityRootRef = WeakReference(root)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) bind.run() else main.post(bind)
    }

    fun detach(activity: Activity) {
        val clean = Runnable {
            if (activityRef?.get() === activity) {
                pendingStartRunnable?.let { main.removeCallbacks(it) }
                pendingStartRunnable = null
                starting = false
                if (CaptureSession.phase == CaptureSession.Phase.IDLE && !CaptureSession.isStarting()) {
                    CaptureSession.clearOverlayTake()
                }
                hideLocked()
                activityRef = null
                activityRootRef = null
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) clean.run() else main.post(clean)
    }

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = host != null

    fun isStarting(): Boolean = starting

    private fun isActivityForeground(): Boolean =
        (activityRef?.get() as? ComponentActivity)?.lifecycle?.currentState
            ?.isAtLeast(Lifecycle.State.STARTED) == true

    /** Routes Activity Back to an open overlay before leaving or backing out of the app. */
    fun handleBack(): Boolean {
        val current = host ?: return false
        main.post {
            if (host !== current || current.finished.get()) return@post
            current.webView.evaluate("window.dispatchEvent(new Event('tapswoop-recorder-back'))")
        }
        return true
    }

    /** Dismisses a leftover setup after the permission is revoked in Settings. */
    fun hideIfPermissionMissing(context: Context) {
        if (!canDraw(context)) hide()
    }

    /**
     * Shows the setup if **Display over other apps** is granted. The view is
     * attached on the main thread; callers get an optimistic answer and the HUD
     * fallback covers a missing host.
     */
    fun show(context: Context, baseUrl: String): Boolean {
        if (!canDraw(context)) return false
        if (starting || CaptureSession.isStarting() || CaptureSession.phase != CaptureSession.Phase.IDLE) {
            return true
        }
        main.post {
            if (starting || CaptureSession.isStarting() || CaptureSession.phase != CaptureSession.Phase.IDLE) {
                return@post
            }
            // A second press must not open the body; only the pill's own tap
            // does that. Refresh the compact dismiss timer if it is still shut.
            val current = host
            if (current != null) {
                if (!current.expanded && !current.finished.get()) {
                    current.webView.evaluate("window.dispatchEvent(new Event('tapswoop-recorder-ping'))")
                }
                return@post
            }
            showLocked(baseUrl)
        }
        return true
    }

    fun hide() {
        main.post { hideLocked() }
    }

    fun openPermissionSettings(context: Context) {
        val intent = android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    // ------------------------------------------------------------------ //
    // Host view
    // ------------------------------------------------------------------ //

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLocked(baseUrl: String) {
        if (starting || CaptureSession.isStarting() || CaptureSession.phase != CaptureSession.Phase.IDLE) {
            return
        }
        val activity = activityRef?.get()
        val activityRoot = activityRootRef?.get()
        if (activity == null || activityRoot == null || activity.isFinishing || activity.isDestroyed) {
            fallbackToHud("The floating recorder is unavailable on this screen")
            return
        }

        val screenWidth = activityRoot.width.takeIf { it > 0 }
            ?: activity.resources.displayMetrics.widthPixels
        val bodyWidth = minOf(dp(activity, 374), screenWidth - dp(activity, 24))
            .coerceAtLeast(dp(activity, 260))
        val compactWidth = minOf(dp(activity, COMPACT_DP), bodyWidth)
            .coerceAtLeast(dp(activity, 160))
        val compactHeight = dp(activity, 64)
        val initialExpandedHeight = dp(activity, INITIAL_BODY_HEIGHT_DP)
        val webViewHeight = dp(activity, FIXED_WEBVIEW_HEIGHT_DP)
        val topMarginPx = dp(activity, 12)

        val webView = WebView(activity).apply {
            settings.configureForOverlay()
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnLongClickListener { true }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "recorder console: ${message.message()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = !request.url.toString().startsWith(baseUrl)

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.w(TAG, "recorder WebView renderer exited (crashed=${detail.didCrash()})")
                    fallbackToHud("The floating recorder could not load")
                    return true
                }
            }
        }

        lateinit var root: OverlayRoot
        root = OverlayRoot(
            context = activity,
            bodyWidth = bodyWidth,
            topMarginPx = topMarginPx,
            compactHeightPx = compactHeight,
            initialCompactWidthPx = compactWidth,
            initialExpandedHeightPx = initialExpandedHeight,
            onOutside = {
                host?.let { active ->
                    active.expanded = false
                    root.updateGeometry(compactWidth = null, expandedHeight = null, expanded = false)
                }
                webView.evaluate("window.dispatchEvent(new Event('tapswoop-recorder-outside'))")
            },
        ).apply {
            elevation = dp(activity, 16).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                webView,
                FrameLayout.LayoutParams(
                    bodyWidth,
                    webViewHeight,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ).apply {
                    topMargin = topMarginPx
                },
            )
        }

        activityRoot.addView(
            root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val entry = Host(
            activityRoot = activityRoot,
            root = root,
            webView = webView,
            baseUrl = baseUrl,
            bodyWidth = bodyWidth,
            webViewHeight = webViewHeight,
        )
        host = entry

        webView.addJavascriptInterface(RecorderBridge(entry), "TapswoopRecorder")
        webView.loadUrl("$baseUrl/recorder.html")

        // A page that never reports ready would otherwise stay open silently.
        main.postDelayed({
            if (host === entry && !entry.ready) {
                Log.w(TAG, "recorder page did not become ready")
                hideLocked()
                CaptureSession.showHud()
            }
        }, READY_TIMEOUT_MS)
    }

    private fun hideLocked() {
        val current = host ?: return
        if (!current.finished.compareAndSet(false, true)) return
        host = null
        runCatching { current.activityRoot.removeView(current.root) }
        runCatching { current.webView.stopLoading() }
        current.root.removeAllViews()
        runCatching { current.webView.destroy() }
    }

    private fun fallbackToHud(message: String) {
        hideLocked()
        main.post {
            AppHost.applicationContext()?.let {
                Toast.makeText(it, message, Toast.LENGTH_LONG).show()
            }
            CaptureSession.showHud()
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------ //
    // Page bridge
    // ------------------------------------------------------------------ //

    private class RecorderBridge(private val entry: Host) {

        @JavascriptInterface
        fun ready() {
            main.post {
                if (host !== entry) return@post
                entry.ready = true
                // The pill is already at the top. This only tells the page
                // which phase it is in; there is no window travel to animate.
                entry.webView.evaluateJavascript(
                    "window.dispatchEvent(new Event('tapswoop-recorder-arrived'))",
                    null,
                )
            }
        }

        @JavascriptInterface
        fun prepare(expandedHeightCss: Double) {
            main.post {
                if (host !== entry) return@post
                val context = entry.root.context
                val density = context.resources.displayMetrics.density
                if (expandedHeightCss > 0.0) {
                    val height = (expandedHeightCss * density).roundToInt()
                        .coerceIn(dp(context, 64), entry.webViewHeight)
                    entry.root.updateGeometry(compactWidth = null, expandedHeight = height, expanded = entry.expanded)
                }
            }
        }

        @JavascriptInterface
        fun resize(heightCss: Double, pillCss: Double, expanded: Boolean) {
            main.post {
                if (host !== entry) return@post
                val context = entry.root.context
                val density = context.resources.displayMetrics.density
                val newHeight = if (expanded && heightCss > 0.0) {
                    (heightCss * density).roundToInt().coerceIn(dp(context, 64), entry.webViewHeight)
                } else {
                    null
                }
                val newCompact = if (!expanded && pillCss > 0.0) {
                    ((pillCss + 24.0) * density).roundToInt().coerceIn(dp(context, 160), entry.bodyWidth)
                } else {
                    null
                }
                entry.expanded = expanded
                entry.root.updateGeometry(compactWidth = newCompact, expandedHeight = newHeight, expanded = expanded)
            }
        }

        @JavascriptInterface
        fun microphone(): Boolean =
            SettingsStore(entry.root.context).preferredMicrophoneEnabled(defaultValue = true)

        @JavascriptInterface
        fun setMicrophone(enabled: Boolean) {
            SettingsStore(entry.root.context)
                .mergeRecordingPreference(SettingsStore.KEY_MICROPHONE_ENABLED, enabled)
        }

        @JavascriptInterface
        fun start(microphone: Boolean) {
            main.post { startRecording(entry, microphone) }
        }

        @JavascriptInterface
        fun close() {
            main.post { hideLocked() }
        }

        private fun startRecording(entry: Host, microphone: Boolean) {
            if (host !== entry || starting) return
            val context = entry.root.context.applicationContext
            starting = true
            SettingsStore(context).mergeRecordingPreference(SettingsStore.KEY_MICROPHONE_ENABLED, microphone)
            CaptureSession.markOverlayTake()
            CaptureSession.bringAppToFront()
            hideLocked()
            val runnable = object : Runnable {
                override fun run() {
                    if (pendingStartRunnable !== this) return
                    pendingStartRunnable = null
                    val request = try {
                        CaptureSession.start(context, microphone)
                    } catch (error: Throwable) {
                        starting = false
                        CaptureSession.clearOverlayTake()
                        Toast.makeText(
                            context,
                            error.message ?: "Could not start the recording",
                            Toast.LENGTH_LONG,
                        ).show()
                        if (isActivityForeground()) show(context, entry.baseUrl)
                        return
                    }
                    request.whenComplete { result, error ->
                        val success = error == null && result?.optBoolean("success", false) == true
                        if (success) {
                            starting = false
                            return@whenComplete
                        }
                        CaptureSession.clearOverlayTake()
                        val canceled = result?.optBoolean("canceled", false) == true
                        main.post {
                            starting = false
                            if (!canceled) {
                                Toast.makeText(
                                    context,
                                    result?.optString("error").orEmpty()
                                        .ifBlank { error?.message ?: "Could not start the recording" },
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            if (isActivityForeground()) show(context, entry.baseUrl)
                        }
                    }
                }
            }
            pendingStartRunnable = runnable
            main.postDelayed(runnable, START_DELAY_MS)
        }
    }
}

/**
 * Full-bounds transparent overlay container inside the activity root.
 *
 * While compact, only touches within the centered pill bounds are delivered to
 * the overlay [WebView]; taps anywhere else pass straight through to the app
 * underneath. While expanded, touches inside the card go to its controls, and
 * touches outside fold the card while still reaching the app underneath.
 */
private class OverlayRoot(
    context: Context,
    private val bodyWidth: Int,
    private val topMarginPx: Int,
    private val compactHeightPx: Int,
    initialCompactWidthPx: Int,
    initialExpandedHeightPx: Int,
    private val onOutside: () -> Unit,
) : FrameLayout(context) {

    private var compactWidthPx = initialCompactWidthPx
    private var expandedHeightPx = initialExpandedHeightPx
    private var expandedState = false
    private var forwardingTouch = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updateGeometry(compactWidth: Int?, expandedHeight: Int?, expanded: Boolean) {
        if (compactWidth != null) compactWidthPx = compactWidth
        if (expandedHeight != null) expandedHeightPx = expandedHeight
        expandedState = expanded
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val viewLeft = (width - bodyWidth) / 2f
                val viewTop = topMarginPx.toFloat()
                val inside = if (expandedState) {
                    event.x >= viewLeft && event.x <= viewLeft + bodyWidth &&
                        event.y >= viewTop && event.y <= viewTop + expandedHeightPx
                } else {
                    val half = compactWidthPx / 2f
                    val centerX = width / 2f
                    event.x >= centerX - half && event.x <= centerX + half &&
                        event.y >= viewTop && event.y <= viewTop + compactHeightPx
                }
                if (!inside) {
                    forwardingTouch = false
                    if (expandedState) {
                        onOutside()
                    }
                    return false
                }
                forwardingTouch = true
                return super.dispatchTouchEvent(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!forwardingTouch) return false
                forwardingTouch = false
                return super.dispatchTouchEvent(event)
            }

            else -> {
                if (!forwardingTouch) return false
                return super.dispatchTouchEvent(event)
            }
        }
    }
}

private fun WebSettings.configureForOverlay() {
    javaScriptEnabled = true
    domStorageEnabled = false
    allowFileAccess = false
    allowContentAccess = false
    setSupportZoom(false)
    builtInZoomControls = false
    displayZoomControls = false
    mediaPlaybackRequiresUserGesture = true
    cacheMode = WebSettings.LOAD_NO_CACHE
    userAgentString = "$userAgentString TapSwoopRecorder"
}

private fun WebView.evaluate(script: String) {
    runCatching { evaluateJavascript(script, null) }
}
