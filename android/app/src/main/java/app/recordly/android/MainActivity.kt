package app.recordly.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

/**
 * Single-activity host for the Recordly renderer.
 *
 * There is deliberately no Compose and no second activity: the entire UI is the
 * upstream web app, and everything native exists to serve it. Startup order is
 * load-bearing:
 *
 *   1. [AppHost] installs the renderer from assets onto disk (Range needs a
 *      real file) and starts the loopback server — once per process
 *   2. attach the JS interface
 *   3. load the app
 *
 * The bridge object must be attached before `loadUrl`, because the shim is
 * inlined in the document head and snapshots `window.__recordlyNative` while
 * parsing.
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val settingsStore by lazy { SettingsStore(this) }
    private val chromeThemeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsStore.PREF_THEME) applyWindowChrome()
    }
    private var bridge: NativeBridge? = null

    /** Origin of the installed renderer; every surface is a path under it. */
    private var baseUrl = ""

    /**
     * Which surface the WebView is showing.
     *
     * Switching surfaces is a reload, so it must not be done speculatively: the
     * dashboard asks for the editor it is already in, and reloading would throw
     * away whatever the user had open.
     */
    @Volatile
    private var surface = SURFACE_EDITOR

    /**
     * Activity results come back here rather than at the call site because the
     * dialogs are started from the bridge's worker threads, minutes apart from
     * where the answer is consumed. Registered as fields so they survive
     * activity recreation (an orientation change during the consent dialog).
     */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            CaptureSession.onConsentResult(result.resultCode, result.data)
        }

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            CaptureSession.onPermissionsResult(granted)
        }

    /**
     * One microphone prompt at a time, shared with the onboarding setup.
     * Capture asks through its own launcher; this exists so the page can show
     * the real grant before the user starts a take.
     */
    private var microphoneRequest: CompletableFuture<Boolean>? = null
    private val microphoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneRequest?.complete(granted)
            microphoneRequest = null
        }

    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            DocumentPicker.onResult(applicationContext, result.resultCode, result.data)
        }

    /**
     * The page's own `<input type="file">` (image and video wallpapers).
     *
     * `WebChromeClient.onShowFileChooser` is the only way a WebView can open a
     * picker for it. Without an override the input silently does nothing, which
     * is what it looked like before this was added.
     */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null
            if (callback == null) return@registerForActivityResult
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }
            val uris = ArrayList<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) uris.add(clip.getItemAt(index).uri)
            }
            callback.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = WebView(this).apply {
            settings.configureForApp()
            webChromeClient = consoleClient()
            webViewClient = navigationClient()
            // Recording deliberately backgrounds the app, and the renderer is
            // what drives the stop path: `onStopRecordingFromTray` arrives as an
            // evaluateJavascript call. Chromium's default policy waives a hidden
            // renderer and suspends its JS, so the tray event sat unprocessed
            // until the app came back — and the take ended via the native
            // fallback instead of the intended sequence. Keeping the renderer
            // IMPORTANT and un-waived while hidden is what lets the stop, the
            // session finalization and the editor handoff run in the background.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        root = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        // The activity is edge-to-edge, so without insets the page header sits
        // under the status bar (the Home icon overlapped the clock) and the
        // timeline sits under the gesture bar. Pad the container, not the
        // WebView, so its layout viewport shrinks to the safe area.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
        setContentView(root)
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        settingsStore.registerListener(chromeThemeListener)
        applyWindowChrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (SileoOverlay.handleBack()) return
                // Close the current Cut Room overlay before leaving the WebView.
                webView.evaluateJavascript(
                    "Boolean(window.__recordlyDismissCutRoomOverlay?.())",
                ) { handled ->
                    if (handled == "true" || isFinishing || isDestroyed) return@evaluateJavascript
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        })

        attachCaptureHooks()
        SileoOverlay.attach(this, root)
        DocumentPicker.attach(this) { intent ->
            runOnUiThread {
                try {
                    documentLauncher.launch(intent)
                } catch (error: Exception) {
                    DocumentPicker.onLaunchError(error.message ?: "Could not open the file picker")
                }
            }
        }

        Thread({
            try {
                val host = AppHost.ensure(this)
                runOnUiThread { attachBridge(host.baseUrl, host.media, host.rendererRoot, host.token) }
            } catch (error: Throwable) {
                Log.e(TAG, "startup failed", error)
                runOnUiThread { showFatal(error) }
            }
        }, "recordly-startup").start()
    }

    override fun onDestroy() {
        settingsStore.unregisterListener(chromeThemeListener)
        microphoneRequest?.complete(false)
        microphoneRequest = null
        // An uncancelled callback leaves the page's input permanently busy.
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        CaptureSession.detach()
        SileoOverlay.detach(this)
        DocumentPicker.detach(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        // The loopback server is owned by [AppHost] and outlives individual
        // activity instances on purpose; it is freed with the process.
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWindowChrome()
    }

    override fun onResume() {
        super.onResume()
        // Revoking "Display over other apps" from system settings removes the
        // window on some builds and leaves our state pointing at it on others.
        SileoOverlay.hideIfPermissionMissing(this)
    }

    override fun onStop() {
        super.onStop()
        // The setup is a pre-recording surface, not a persistent widget: it
        // appears only from Home's New recording and must not follow the user
        // out of TapSwoop or greet them on the next launch. A live capture is
        // owned by the foreground service and keeps its notification.
        if (!CaptureSession.isRecording()) SileoOverlay.hide()
    }

    /** Prompt for the microphone from the activity that can show the dialog. */
    private fun requestMicrophoneAccess(): CompletableFuture<Boolean> {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            return CompletableFuture.completedFuture(true)
        }
        microphoneRequest?.let { return it }
        val result = CompletableFuture<Boolean>()
        microphoneRequest = result
        try {
            microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } catch (error: Exception) {
            microphoneRequest = null
            result.completeExceptionally(error)
        }
        return result
    }

    /** The native safe-area padding must carry the same color as the web app. */
    private fun applyWindowChrome() {
        val light = when (settingsStore.get(SettingsStore.PREF_THEME)) {
            "light" -> true
            "dark" -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
                Configuration.UI_MODE_NIGHT_YES
        }
        val ground = getColor(if (light) R.color.cut_room_light_ground else R.color.cut_room_dark_ground)
        root.setBackgroundColor(ground)
        webView.setBackgroundColor(ground)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    // ------------------------------------------------------------------ //
    // Capture integration
    // ------------------------------------------------------------------ //

    private fun attachCaptureHooks() {
        CaptureSession.attach(
            CaptureSession.Hooks(
                requestPermissions = { permissions -> permissionsLauncher.launch(permissions) },
                requestProjection = {
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    // Prefer a full-display request so users cannot
                    // accidentally choose single-app capture, which excludes
                    // system UI. Android still enforces its capture protections.
                    val intent =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            runCatching {
                                manager.createScreenCaptureIntent(
                                    MediaProjectionConfig.createConfigForDefaultDisplay()
                                )
                            }.getOrElse { manager.createScreenCaptureIntent() }
                        } else {
                            manager.createScreenCaptureIntent()
                        }
                    projectionLauncher.launch(intent)
                },
                showHud = { navigateTo(SURFACE_HUD) },
                showEditor = { navigateTo(SURFACE_EDITOR) },
                // Both fire from the capture service, whose stop path runs on
                // its own thread; window flags and task ordering are main-thread
                // work.
                onRecordingStarted = { runOnUiThread { enterRecordingChrome() } },
                onRecordingStopped = {
                    runOnUiThread {
                        SileoOverlay.hide()
                        exitRecordingChrome()
                        returnToForeground()
                    }
                },
                onRecordingFinished = { file ->
                    runOnUiThread {
                        SileoOverlay.hide()
                        if (isFinishing || isDestroyed) {
                            Log.w(TAG, "recording finished after the activity was destroyed")
                            CaptureSession.retainRecoverable(file)
                            return@runOnUiThread
                        }
                        val current = bridge
                        if (current == null) {
                            Log.w(TAG, "recording finished without a bridge; leaving it for recovery")
                            CaptureSession.retainRecoverable(file)
                            return@runOnUiThread
                        }
                        current.adoptRecording(file)
                        // The Home lives inside the editor surface, so setting
                        // the same surface is a no-op. Reload explicitly so the
                        // editor hydrates from the adopted recording.
                        surface = SURFACE_EDITOR
                        webView.post { webView.loadUrl(surfaceUrl(SURFACE_EDITOR)) }
                    }
                },
                bringAppToFront = { runOnUiThread { returnToForeground() } },
            ),
        )
    }

    /**
     * Records start with Recordly *as* the app being recorded, and whatever is
     * on screen when the take ends is what the user sees next.
     *
     * `FLAG_SECURE` is the only way to keep our own surface out of the capture:
     * a secure window is omitted from MediaProjection output, so returning to
     * Recordly mid-recording leaves a black span instead of a recording of the
     * recorder. Moving the task back keeps the first frame of the recording from
     * being our own UI.
     */
    private fun enterRecordingChrome() {
        if (hudCaptureProtectionEnabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // `false`, not `true`: the non-root flag makes the call a no-op when
        // Recordly is the root of its task, which is the normal cold-start
        // case. The recorder then stays on screen and records itself.
        moveTaskToBack(false)
    }

    /**
     * The dashboard's "Hide recording controls from captured video" setting,
     * default true. Only an explicit `false` — the "record my own app" mode —
     * lets our UI be captured.
     */
    private fun hudCaptureProtectionEnabled(): Boolean =
        (SettingsStore(this).get(SettingsStore.PREF_HUD_CAPTURE_PROTECTION) as? Boolean) ?: true

    private fun exitRecordingChrome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /**
     * Brings Recordly back after a take ends.
     *
     * The recording was started from this app and then backgrounded, so ending
     * it is the user saying they are done — the editor should be in front of
     * them, as it is on desktop. A background *activity start* is refused on
     * Android 10+, but reordering our own task is allowed, which is what this
     * uses. If even that is refused, the "Recording saved" notification is the
     * way back.
     */
    @Suppress("DEPRECATION")
    private fun returnToForeground() {
        val task = taskId
        if (task <= 0) return
        runCatching {
            (getSystemService(android.app.ActivityManager::class.java))
                ?.moveTaskToFront(task, android.app.ActivityManager.MOVE_TASK_NO_USER_ACTION)
        }.onFailure { Log.i(TAG, "cannot bring the task forward: ${it.message}") }
    }

    /**
     * Reloads the single WebView into one of the renderer's surfaces.
     *
     * Electron gives the recorder a window of its own
     * (`?windowType=hud-overlay`) plus the editor window. Android has one
     * WebView, so switching surfaces is a reload — and the reload is what the
     * post-recording handoff needs anyway: the editor hydrates from the current
     * video path on mount (`useInitialEditorSource`).
     */
    private fun navigateTo(target: String) {
        if (baseUrl.isEmpty()) {
            Log.w(TAG, "navigation before the renderer is ready: $target")
            return
        }
        if (target == surface) return
        surface = target
        webView.post { webView.loadUrl(surfaceUrl(target)) }
    }

    /**
     * One of the renderer's surfaces.
     *
     * `onboardingPreview=0` matters because the renderer is built as a
     * development build (see `recordly-upstream/vite.android.config.mjs`), and that build
     * otherwise opens the onboarding preview tour on every surface.
     */
    private fun surfaceUrl(target: String): String =
        "$baseUrl/?windowType=$target&onboardingPreview=0"

    // ------------------------------------------------------------------ //
    // Bridge wiring
    // ------------------------------------------------------------------ //

    private fun attachBridge(
        baseUrl: String,
        registry: MediaRegistry,
        assetRoot: File,
        token: String,
    ) {
        this.baseUrl = baseUrl
        val host = NativeBridge(
            context = this,
            baseUrl = { baseUrl },
            media = registry,
            assetRoot = assetRoot,
            onResolve = { id, payloadJson ->
                postJs(
                    "window.__recordlyNativeResolve(" +
                        JSONObject.quote(id) + "," + JSONObject.quote(payloadJson) + ")",
                )
            },
            onReject = { id, message ->
                postJs(
                    "window.__recordlyNativeReject(" +
                        JSONObject.quote(id) + "," + JSONObject.quote(message) + ")",
                )
            },
            onEmit = { channel, payloadJson ->
                postJs(
                    "window.__recordlyNativeEmit(" +
                        JSONObject.quote(channel) + "," + JSONObject.quote(payloadJson) + ")",
                )
            },
            tokenProvider = { token },
            requestMicrophonePermission = ::requestMicrophoneAccess,
        )
        bridge = host
        // The capture service outlives page reloads, so it reaches the page
        // through whichever bridge is current.
        CaptureSession.emitter = { channel, payload -> host.emit(channel, payload) }
        // Bind before loadUrl: the interface must exist while the document is
        // being parsed, and the shell snapshots it in the head.
        webView.addJavascriptInterface(host, HOST_NAME)
        // A take finalized by the native fallback has nobody to open it. Doing
        // that here, before the first load, means the editor hydrates into the
        // recording exactly as it would after a normal stop.
        CaptureSession.takeRecoverable()?.let { orphan ->
            Log.i(TAG, "adopting a recording that finished without the renderer: ${orphan.name}")
            host.adoptRecording(orphan)
        }
        webView.loadUrl(surfaceUrl(SURFACE_EDITOR))
    }

    private fun postJs(script: String) {
        webView.post { webView.evaluateJavascript(script, null) }
    }

    // ------------------------------------------------------------------ //
    // WebView configuration
    // ------------------------------------------------------------------ //

    private fun consoleClient(): WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(
                WEB_TAG,
                "${message.message()} @ ${message.sourceId()}:${message.lineNumber()}",
            )
            return true
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            // The renderer uses one hidden input at a time; answering the old
            // callback first means a second request cannot be stranded.
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = callback

            // `accept` mixes MIME types (`image/*`) with extensions (`.mp4`).
            // Intent extras take MIME types only; an extension passed as one
            // makes the picker reject the filter and show nothing.
            val accepted = params.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.contains('/') }
                .distinct()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                type = accepted.firstOrNull() ?: "*/*"
                if (accepted.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, accepted.toTypedArray())
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    params.mode == FileChooserParams.MODE_OPEN_MULTIPLE,
                )
            }
            return try {
                fileChooserLauncher.launch(intent)
                true
            } catch (error: Throwable) {
                Log.w(TAG, "cannot open the page file picker", error)
                fileChooserCallback = null
                callback.onReceiveValue(null)
                false
            }
        }
    }

    private fun navigationClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            // Track what actually loaded, not only what we asked for: a page
            // that reloaded itself would otherwise leave `surface` stale, and
            // the next `navigateTo` to the same surface would be a no-op.
            url.substringAfter("windowType=", "")
                .substringBefore('&')
                .takeIf { it.isNotEmpty() }
                ?.let { surface = it }
            // Definitive boot diagnostic: if electronAPI is undefined here, the
            // inlined bridge did not execute and the renderer is about to die.
            view.evaluateJavascript(BOOT_PROBE) { result ->
                Log.i(TAG, "boot probe: $result")
            }
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            // The renderer must never navigate off the loopback origin; an
            // external link opens in the browser instead of replacing the app.
            val url = request.url
            val expectedPort = android.net.Uri.parse(baseUrl).port
            val local = url.scheme == "http" && url.host == "127.0.0.1" &&
                expectedPort > 0 && url.port == expectedPort
            if (!local) {
                Log.i(TAG, "blocking external navigation: $url")
            }
            return !local
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            // Returning true keeps the app alive; the WebView itself is dead.
            Log.e(TAG, "renderer process gone (crashed=${detail.didCrash()})")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebSettings.configureForApp() {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        cacheMode = WebSettings.LOAD_NO_CACHE
        useWideViewPort = true
        loadWithOverviewMode = false
        textZoom = 100
        setSupportZoom(false)
    }

    // ------------------------------------------------------------------ //
    // Failure surface
    // ------------------------------------------------------------------ //

    private fun showFatal(error: Throwable) {
        val message = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            text = "${getString(R.string.startup_error, getString(R.string.app_name))}\n\n${error.javaClass.simpleName}: ${error.message}"
            setPadding(48, 96, 48, 48)
            textSize = 14f
        }
        setContentView(message)
        Log.e(TAG, "fatal", error)
    }

    private companion object {
        const val TAG = "Recordly"
        const val WEB_TAG = "RecordlyWeb"
        const val HOST_NAME = "__recordlyHost"

        /** The renderer's two surfaces; Electron hosts each in its own window. */
        const val SURFACE_EDITOR = "editor"
        const val SURFACE_HUD = "hud-overlay"

        /**
         * Reports what actually exists in the page after load. `electronAPI`
         * absent ⇒ the inlined bridge never ran. `recordlyHost` absent ⇒
         * addJavascriptInterface did not take. Method count ⇒ the shim built
         * its full surface.
         */
        const val BOOT_PROBE =
            "JSON.stringify({" +
                "electronAPI: typeof window.electronAPI," +
                "recordlyHost: typeof window.__recordlyHost," +
                "recordlyNative: typeof window.__recordlyNative," +
                "methods: window.__recordlyBridge ? window.__recordlyBridge.methods.length : -1," +
                "inlineBridge: document.documentElement.innerHTML.indexOf('recordly-android-bridge') >= 0" +
                "})"
    }
}
