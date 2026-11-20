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
        /**
         * "Hide recording controls from captured video"
         * (`get-`/`set-hud-overlay-capture-protection`).
         *
         * Read by [MainActivity] when capture starts and written by the bridge,
         * so the key lives here rather than in either of them.
         */
        const val PREF_HUD_CAPTURE_PROTECTION = "recordly.hud.captureProtection"
    }
}
