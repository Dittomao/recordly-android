package app.recordly.android

import android.os.Build
import org.json.JSONObject

/**
 * Presentation policy for touch telemetry.
 *
 * Recordly's renderer draws a mouse cursor sprite from `CursorTelemetryPoint`
 * samples. Android has no pointer: the samples come from accessibility click
 * events, so drawing an arrow at the tap is wrong twice over — it is a cursor
 * the user never used, and it is not how a touch reads on screen.
 *
 * Upstream separates the two visuals: `showCursor` gates the sprite, while
 * `cursorClickEffect` draws independently at the press position. Turning the
 * sprite off and enabling a click effect therefore yields a touch indicator —
 * an expanding, fading pulse at the tap — with no renderer patch:
 *
 *   showCursor              false   (no arrow in preview or export)
 *   cursorClickEffect       echo    (concentric rings at the tap)
 *   cursorClickEffectColor  #2563EB (visible on light and dark UI)
 *   cursorIdleHide.enabled  false   (see below)
 *   connectZooms            false   (each tap gets its own zoom in/out)
 *   zoomClassicMode         true    (frame the tap, do not follow a cursor)
 *   zoomMotionBlur          0.20    (subtle shutter blur, only in motion)
 *
 * Idle hide exists to fade an unattended *cursor*. With tap-only telemetry
 * every tap closes an idle interval, so the container opacity is 0 at the
 * instant of the tap and fades in over 150 ms — the pulse would start after
 * the finger. Disabling idle hide makes the pulse begin with the tap.
 *
 * Touch framing has no continuous pointer to follow. Connected zooms keep the
 * camera between nearby taps instead of zooming out, and automatic framing
 * follows move samples (including a swipe's scroll position). Classic framing
 * anchors every auto region on the tapped view and gives each tap a complete
 * zoom in and out.
 *
 * The click effect is a platform policy, like hiding the sprite: a white or
 * gray pulse disappears on a light notification card, and Recordly's desktop
 * click effects are cursor decoration rather than touch feedback. Other stored
 * effects are normalised to `echo` on load/save for that reason.
 *
 * These are applied where editor settings enter Kotlin — saved projects and the
 * editor-preferences app setting — so both restored projects and new takes see
 * them, and they survive a renderer reload.
 */
object TouchPresentation {

    /** `editorPreferences.ts`'s `EDITOR_PREFERENCES_STORAGE_KEY`. */
    const val EDITOR_PREFERENCES_KEY = "recordly.editor.preferences"

    /**
     * One-time marker for the floating-frame look. Existing installs get the
     * touch composition once; later user changes are respected.
     */
    const val LOOK_MIGRATION_KEY = "recordly.android.touchFrameMigrated.v3"

    private const val TOUCH_WALLPAPER = "/wallpapers/sonoma-dark.jpg"
    // 35 on Recordly's 0-100 padding scale is 7% per side, so the captured
    // screen occupies ~86% of the frame. That is the measured width of the
    // Windows reference composition (132..1787 of 1920).
    private const val TOUCH_PADDING = 35
    private const val TOUCH_RADIUS = 3

    private const val TOUCH_EFFECT = "echo"
    private const val TOUCH_COLOR = "#2563EB"
    private const val TOUCH_ZOOM_BLUR = 0.20
    private const val MOTION_PREFS_FLAG = "_androidTouchMotionV1"

    /**
     * Coerces an `editor` object (a project's `editor` node or a preferences
     * blob). Unknown keys are untouched so upstream fields round-trip.
     */
    fun applyToEditor(editor: JSONObject, legacyMotion: Boolean = false) {
        editor.put("showCursor", false)
        editor.put("loopCursor", false)
        editor.put("connectZooms", false)
        editor.put("zoomClassicMode", true)
        // Recordly's default Focused preset: 200 ms in, 200 / 0.7 ms out.
        // Its visible curve matches the Windows reference. The shared
        // compositor samples the spring by timestamp in preview and export.
        editor.put("zoomInDurationMs", 200.0)
        editor.put("zoomOutDurationMs", 285.7142857142857)
        // Older Android builds forced 0 even if the user enabled blur. Upgrade
        // that old default once, then preserve the user's saved intensity.
        val blur = editor.optDouble("zoomMotionBlur")
        editor.put(
            "zoomMotionBlur",
            if (!blur.isFinite() || (legacyMotion && blur == 0.0)) TOUCH_ZOOM_BLUR else blur,
        )
        editor.put("cursorMotionBlur", 0.0)

        // Match Recordly's own high-quality export path: FHD is the 1080p-class
        // preset for the project aspect, quality bitrate (0.3 bits/pixel/frame),
        // 60 fps. This is the same recipe as the Windows reference export.
        // The emulator's WebCodecs encoder only supports 720p-class H.264, so it
        // defaults to HD (576x1280 portrait) instead of failing at FHD.
        editor.put("advancedExportSettings", true)
        editor.put("exportQuality", if (isEmulator()) "hd" else "medium")
        editor.put("exportEncodingMode", "quality")
        editor.put("mp4FrameRate", 60)
        editor.put("cursorClickEffect", TOUCH_EFFECT)
        editor.put("cursorClickEffectColor", TOUCH_COLOR)
        editor.put("cursorClickEffectScale", 1.2)
        editor.put("cursorClickEffectOpacity", 0.9)
        editor.put("cursorClickEffectDurationMs", 420)

        val idleHide = editor.optJSONObject("cursorIdleHide") ?: JSONObject()
        idleHide.put("enabled", false)
        if (!idleHide.has("idleThresholdMs")) idleHide.put("idleThresholdMs", 2000)
        if (!idleHide.has("fadeDelayMs")) idleHide.put("fadeDelayMs", 500)
        if (!idleHide.has("fadeDurationMs")) idleHide.put("fadeDurationMs", 300)
        if (!idleHide.has("movementTolerancePx")) idleHide.put("movementTolerancePx", 2)
        editor.put("cursorIdleHide", idleHide)
    }

    /** Normalises a `.recordly` document in place; a document without `editor` is a no-op. */
    fun applyToProject(document: JSONObject, legacyMotion: Boolean = false) {
        document.optJSONObject("editor")?.let { applyToEditor(it, legacyMotion) }
    }

    /**
     * The AOSP emulator's software H.264 encoder advertises only 720p-class
     * WebCodecs support. Real phones encode 1080p60 in hardware, so the higher
     * FHD default is kept there.
     */
    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("sdk_gphone") ||
            model.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk")
    }

    /**
     * The floating-frame composition: the capture is inset on a wallpaper with
     * rounded corners and a shadow, so auto-zoom has a device-like frame to
     * move over. Applied once to existing preferences; new installs start with
     * it from [seed].
     */
    fun applyLook(editor: JSONObject) {
        editor.put("wallpaper", TOUCH_WALLPAPER)
        editor.put(
            "padding",
            JSONObject().apply {
                put("top", TOUCH_PADDING)
                put("bottom", TOUCH_PADDING)
                put("left", TOUCH_PADDING)
                put("right", TOUCH_PADDING)
                put("linked", true)
            },
        )
        editor.put("borderRadius", TOUCH_RADIUS)
        editor.put("borderRadiusUnit", "percent")
        editor.put("shadowIntensity", 0.6)
        editor.put("backgroundBlur", 0)
    }

    fun isEditorPreferencesKey(key: String): Boolean = key == EDITOR_PREFERENCES_KEY

    /**
     * Coerces a stored settings value on its way through the sync bridge.
     * Non-object values (including `null`, which clears a setting) pass through
     * unchanged; only the editor-preferences blob is rewritten.
     */
    fun coerceSetting(key: String, value: Any?, loading: Boolean = false): Any? {
        if (!isEditorPreferencesKey(key) || value !is JSONObject) return value
        applyToEditor(value, legacyMotion = loading && !value.optBoolean(MOTION_PREFS_FLAG))
        // Renderer preference snapshots only write known keys; keep the
        // migration marker here rather than making it part of their contract.
        value.put(MOTION_PREFS_FLAG, true)
        return value
    }

    /**
     * The value reported when no editor preferences have been stored yet, so a
     * fresh install starts with touch presentation instead of falling back to
     * the renderer's desktop defaults.
     */
    fun seed(): JSONObject = JSONObject().also {
        applyToEditor(it)
        applyLook(it)
    }
}
