package app.recordly.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * The floating recorder setup, rendered with the actual Sileo component.
 *
 * A native Canvas copy of Sileo's silhouette was never the interaction: the
 * product's fluid shape comes from two SVG rectangles in one Gaussian-blur and
 * alpha-threshold filter, moved by a 600 ms spring. That implementation is
 * upstream React, so the overlay is a small transparent [WebView] that loads
 * only [recorder.html] — not the editor renderer — inside a window exactly as
 * large as the surface.
 *
 * The native half owns what a web page cannot: the app-over-app window, system
 * Back, and a watch-only outside touch so a tap reaches the app underneath
 * while the expanded setup folds back to its pill. The window is placed at the
 * top from its first frame; it never flies in from the bottom, which would
 * fight the pill's own Dynamic Island-style entrance.
 *
 * The setup is removed before capture starts. A visible secure overlay leaves a
 * black cutout in MediaProjection output on the API 37 emulator, and an ordinary
 * overlay is recorded. Pause/stop therefore remain in the foreground
 * notification; see `android/README.md`.
 */
object SileoOverlay {

    private const val TAG = "SileoOverlay"

    private const val READY_TIMEOUT_MS = 12_000L
    private const val START_DELAY_MS = 480L

    /** Compact window width; the pill is narrower, with its shadow padding. */
    private const val COMPACT_DP = 190

    private val main = Handler(Looper.getMainLooper())

    private class Host(
        val manager: WindowManager,
        val root: OverlayRoot,
        val webView: WebView,
        val params: WindowManager.LayoutParams,
        val baseUrl: String,
        /** Width of the expanded body, in pixels. */
        val bodyWidth: Int,
        /** Width of the visible compact window, in pixels. */
        val compactWidth: Int,
    ) {
        var ready = false
        val finished = AtomicBoolean(false)
    }

    @Volatile
    private var host: Host? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isShowing(): Boolean = host != null

    /** Dismisses a leftover setup after the permission is revoked in Settings. */
    fun hideIfPermissionMissing(context: Context) {
        if (!canDraw(context)) hide()
    }

    /**
     * Shows the setup if **Display over other apps** is granted. The window is
     * added on the main thread; callers get an optimistic answer and the HUD
     * fallback covers an add failure.
     */
    fun show(context: Context, baseUrl: String): Boolean {
        if (!canDraw(context)) return false
        val app = context.applicationContext
        main.post {
            // A second press must not open the body; only the pill's own tap
            // does that. The existing setup is simply left as it is.
            if (host != null) return@post
            showLocked(app, baseUrl)
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
    // Window
    // ------------------------------------------------------------------ //

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLocked(context: Context, baseUrl: String) {
        val manager = context.getSystemService(WindowManager::class.java)
        if (manager == null) {
            fallbackToHud("The floating recorder is unavailable on this device")
            return
        }
        val screenWidth = usableWidth(manager, context)
        val bodyWidth = minOf(dp(context, 374), screenWidth - dp(context, 24))
            .coerceAtLeast(dp(context, 260))
        // The compact pill is the whole window. It sits at the top from the
        // first frame and only grows when the user opens it, so the compact
        // surface never covers the app with a full-width transparent strip.
        val compactWidth = minOf(dp(context, COMPACT_DP), bodyWidth)
            .coerceAtLeast(dp(context, 160))
        val pillHeight = dp(context, 64)
        val top = dp(context, 12)
        val params = WindowManager.LayoutParams(
            compactWidth,
            pillHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = top
            // The page paints a Sileo-shaped pill at the top before React is
            // ready. The window is visible immediately; the pill's own entry
            // is the only entrance.
            alpha = 1f
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }

        val webView = WebView(context).apply {
            settings.configureForOverlay()
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
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

        val root = OverlayRoot(
            context = context,
            onOutside = { current ->
                current?.evaluate("window.dispatchEvent(new Event('tapswoop-recorder-outside'))")
            },
            onBack = { current ->
                if (current == null) {
                    hideLocked()
                } else {
                    current.evaluate("window.dispatchEvent(new Event('tapswoop-recorder-back'))")
                }
            },
        ).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // The WebView is always laid out at the expanded width. The compact
        // window reveals only its centre, so opening the setup widens the
        // window instead of reflowing the page under the pointer. That reflow
        // was the remaining wobble when the pill was tapped.
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                bodyWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                leftMargin = (compactWidth - bodyWidth) / 2
            },
        )

        val added = runCatching { manager.addView(root, params) }.isSuccess
        if (!added) {
            webView.destroy()
            fallbackToHud("The floating recorder could not appear")
            return
        }

        val entry = Host(manager, root, webView, params, baseUrl, bodyWidth, compactWidth)
        host = entry
        root.requestFocus()
        webView.requestFocus()

        webView.addJavascriptInterface(RecorderBridge(entry), "TapswoopRecorder")
        webView.loadUrl("$baseUrl/recorder.html")

        // A page that never reports ready would otherwise leave an invisible
        // window capturing touches at the top of the screen.
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
        runCatching { current.manager.removeView(current.root) }
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

    private fun usableWidth(manager: WindowManager, context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return manager.currentWindowMetrics.bounds.width()
        }
        @Suppress("DEPRECATION")
        return context.resources.displayMetrics.widthPixels
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
        fun resize(heightCss: Double, pillCss: Double, expanded: Boolean) {
            main.post {
                if (host !== entry) return@post
                val context = entry.root.context
                val density = context.resources.displayMetrics.density
                // heightCss == 0 is the open pre-widen: width only. That keeps
                // a rapid fold -> open from snapping the height back first.
                val height = if (heightCss > 0.0) {
                    (heightCss * density).roundToInt()
                        .coerceIn(dp(context, 64), dp(context, 720))
                } else {
                    entry.params.height
                }
                // The page itself never changes width. The window clips the
                // fixed-width WebView while compact and uncovers it when open;
                // the child offset keeps the visible centre exactly in place.
                val width = if (expanded) entry.bodyWidth else entry.compactWidth
                val webViewLeft = (width - entry.bodyWidth) / 2
                var viewChanged = false
                val lp = entry.webView.layoutParams as FrameLayout.LayoutParams
                if (lp.leftMargin != webViewLeft) {
                    lp.leftMargin = webViewLeft
                    entry.webView.layoutParams = lp
                    viewChanged = true
                }
                var windowChanged = false
                if (entry.params.width != width) {
                    entry.params.width = width
                    windowChanged = true
                }
                if (heightCss > 0.0 && entry.params.height != height) {
                    entry.params.height = height
                    windowChanged = true
                }
                if (windowChanged) {
                    runCatching {
                        entry.manager.updateViewLayout(entry.root, entry.params)
                    }
                }
                if (viewChanged) entry.root.requestLayout()
            }
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
            if (host !== entry) return
            CaptureSession.markOverlayTake()
            // Android will not show MediaProjection consent from a background
            // activity, and the setup can float over any app.
            CaptureSession.bringAppToFront()
            hideLocked()
            main.postDelayed({
                val request = try {
                    CaptureSession.start(entry.root.context, microphone)
                } catch (error: Throwable) {
                    CaptureSession.clearOverlayTake()
                    Toast.makeText(
                        entry.root.context,
                        error.message ?: "Could not start the recording",
                        Toast.LENGTH_LONG,
                    ).show()
                    show(entry.root.context, entry.baseUrl)
                    return@postDelayed
                }
                request.whenComplete { result, error ->
                    val success = error == null && result?.optBoolean("success", false) == true
                    if (success) return@whenComplete
                    CaptureSession.clearOverlayTake()
                    val canceled = result?.optBoolean("canceled", false) == true
                    main.post {
                        if (!canceled) {
                            Toast.makeText(
                                entry.root.context,
                                result?.optString("error").orEmpty()
                                    .ifBlank { error?.message ?: "Could not start the recording" },
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        show(entry.root.context, entry.baseUrl)
                    }
                }
            }, START_DELAY_MS)
        }
    }
}

/**
 * The window's root. Touches outside are *observed* (FLAG_WATCH_OUTSIDE_TOUCH)
 * and still delivered to the app underneath; system Back is routed to the
 * Sileo page so its collapse animation runs before the window is removed.
 */
private class OverlayRoot(
    context: Context,
    private val onOutside: (WebView?) -> Unit,
    private val onBack: (WebView?) -> Unit,
) : FrameLayout(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun webView(): WebView? = (getChildAt(0) as? WebView)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside(webView())
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            onBack(webView())
            return true
        }
        return super.dispatchKeyEvent(event)
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
