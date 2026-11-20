package app.recordly.android

/**
 * Marker wrapped around the inlined bridge source in index.html.
 *
 * Defined once because two independent places depend on it agreeing with
 * itself: [RendererAssets] writes it, [RecordlyServer] looks for it as a
 * tripwire. If they drifted apart the check would silently stop working.
 */
internal const val BRIDGE_MARKER = "/* recordly-android-bridge */"
