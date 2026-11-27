package app.recordly.android

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * `getAppSetting` / `setAppSetting` storage.
 *
 * The four synchronous bridge methods read through here. Values are JSON-encoded
 * so that objects (editor preferences, export history) round-trip unchanged.
 */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("recordly.settings", Context.MODE_PRIVATE)

    fun found(key: String): Boolean = prefs.contains(key)

    fun get(key: String): Any? {
        val raw = prefs.getString(key, null) ?: return null
        return decode(raw)
    }

    fun set(key: String, value: Any?) {
        if (value == null || value == JSONObject.NULL) {
            prefs.edit().remove(key).apply()
            return
        }
        prefs.edit().putString(key, encode(value)).apply()
    }

    /** Raw JSON for a key, or `null` when absent. Used by the sync transport. */
    fun getJson(key: String): String? = prefs.getString(key, null)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long, is Double, is Float -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun decode(raw: String): Any? = try {
        val parsed = JSONTokener(raw).nextValue()
        if (parsed == JSONObject.NULL) null else parsed
    } catch (error: JSONException) {
        raw
    }

    companion object {
        const val PREF_THEME = "recordly.theme"

        /**
         * "Hide recording controls from captured video"
         * (`get-`/`set-hud-overlay-capture-protection`).
         *
         * Read by [MainActivity] when capture starts and written by the bridge,
         * so the key lives here rather than in either of them.
         */
        const val PREF_HUD_CAPTURE_PROTECTION = "recordly.hud.captureProtection"

        /** Recording device preferences shared between the bridge and Sileo setup. */
        const val PREF_RECORDING = "recordly.recording.preferences"
        const val KEY_MICROPHONE_ENABLED = "microphoneEnabled"
    }

    fun mergeRecordingPreference(key: String, value: Any?) {
        val current = get(PREF_RECORDING)
        val merged = if (current is JSONObject) JSONObject(current.toString()) else JSONObject()
        merged.put(key, value ?: JSONObject.NULL)
        set(PREF_RECORDING, merged)
    }

    fun preferredMicrophoneEnabled(defaultValue: Boolean = true): Boolean {
        val stored = get(PREF_RECORDING) as? JSONObject ?: return defaultValue
        return if (stored.has(KEY_MICROPHONE_ENABLED)) {
            stored.optBoolean(KEY_MICROPHONE_ENABLED, defaultValue)
        } else {
            defaultValue
        }
    }
}
