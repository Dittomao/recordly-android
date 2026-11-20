package app.recordly.android

import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Maps `/media/<path>` to files on disk.
 *
 * Two cases:
 *   - Files the app owns (recordings, exports, thumbnails) live under
 *     `mediaRoot` and are addressed by their path relative to it.
 *   - Files the user imported from elsewhere (Downloads, another app's folder)
 *     must not be copied — they can be gigabytes — so they are *linked*:
 *     `linked/<sha256-prefix>` resolves through a persisted index.
 *
 * Exposing a file to the renderer therefore never implies duplicating it.
 */
class MediaRegistry(
    private val root: File,
    private val indexFile: File,
    /** Required by the loopback server on `/media/` URLs; empty disables it. */
    private val token: String = "",
) {
    private val links = LinkedHashMap<String, String>()
    private val lock = Any()

    init {
        root.mkdirs()
        loadIndex()
    }

    /** A file the app owns, addressed relative to [root]. */
    fun ownedFile(relativePath: String): File = File(root, relativePath)

    /**
     * Resolves a `/media/...` path. Returns `null` for anything outside the
     * root or absent from the link index.
     */
    fun resolve(relativePath: String): File? {
        if (relativePath.startsWith(LINK_PREFIX)) {
            val linkToken = relativePath.removePrefix(LINK_PREFIX).substringBefore('/')
            val target = synchronized(lock) { links[linkToken] } ?: return null
            val file = File(target)
            return if (file.isFile) file else null
        }
        return resolveUnder(root, relativePath)
    }

    /** Makes [file] reachable and returns its `/media/...` path. */
    fun expose(file: File): String? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        if (!canonical.isFile) return null

        val rootCanonical = runCatching { root.canonicalFile }.getOrNull()
        if (rootCanonical != null) {
            val prefix = rootCanonical.path + File.separator
            if (canonical.path.startsWith(prefix)) {
                return canonical.path.removePrefix(prefix).replace(File.separatorChar, '/')
            }
        }

        val linkToken = digest(canonical.path)
        // The extension rides along in the token so the URL describes the file
        // it points at (`linked/a2281488d0a15a54.mp4`). The server types by
        // resolved file name, not URL, but the renderer and any future
        // byte-channel consumer see a real file name either way.
        val key = linkToken + extensionOf(canonical.name)
        synchronized(lock) {
            if (links[key] != canonical.path) {
                links[key] = canonical.path
                saveIndex()
            }
        }
        return LINK_PREFIX + key
    }

    fun urlFor(baseUrl: String, relativePath: String): String =
        authorize("$baseUrl$MEDIA_PREFIX${encodePath(relativePath)}")

    /**
     * Adds the current capability token to a local media URL, replacing any
     * stale one. `ProjectStore` calls this when a project is loaded, because the
     * token is per-process and must never be persisted.
     *
     * Only media URLs are passed here; an external URL's `token` query is not
     * ours to touch.
     */
    fun authorize(url: String): String {
        val stripped = deauthorize(url)
        if (token.isEmpty()) return stripped
        val encoded = java.net.URLEncoder.encode(token, "UTF-8")
        val fragmentAt = stripped.indexOf('#')
        val body = if (fragmentAt >= 0) stripped.substring(0, fragmentAt) else stripped
        val fragment = if (fragmentAt >= 0) stripped.substring(fragmentAt) else ""
        val separator = if (body.contains('?')) '&' else '?'
        return "$body$separator" + "token=" + encoded + fragment
    }

    /** Removes only the `token` query parameter, preserving every other one. */
    fun deauthorize(url: String): String {
        val queryAt = url.indexOf('?')
        if (queryAt < 0) return url
        val fragmentAt = url.indexOf('#', queryAt)
        val beforeFragment = if (fragmentAt >= 0) url.substring(0, fragmentAt) else url
        val fragment = if (fragmentAt >= 0) url.substring(fragmentAt) else ""
        val head = beforeFragment.substring(0, queryAt)
        val params = beforeFragment.substring(queryAt + 1).split('&').filter { it.isNotEmpty() }
        val kept = params.filterNot { it.substringBefore('=') == "token" }
        if (kept.size == params.size) return url
        val query = kept.joinToString("&")
        return head + (if (query.isEmpty()) "" else "?$query") + fragment
    }

    /**
     * Moves every link that pointed at [from] to [to].
     *
     * Used when a file is relocated (e.g. an old recordings directory being
     * folded into the media root). Tokens stay valid, so a project that
     * references one keeps resolving instead of failing after the move.
     */
    fun repoint(from: File, to: File) {
        val oldPath = runCatching { from.canonicalPath }.getOrNull() ?: from.path
        val newPath = runCatching { to.canonicalPath }.getOrNull() ?: to.path
        if (oldPath == newPath) return
        synchronized(lock) {
            var changed = false
            for ((key, value) in links) {
                if (value == oldPath || value == from.path) {
                    links[key] = newPath
                    changed = true
                }
            }
            if (changed) saveIndex()
        }
    }

    private fun encodePath(path: String): String = path.split('/').joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }

    /** A short, URL-safe suffix, or "" when the name has nothing usable. */
    private fun extensionOf(name: String): String {
        val raw = name.substringAfterLast('.', "")
        if (raw.isEmpty() || raw.length > 8) return ""
        if (!raw.all { it.isLetterOrDigit() }) return ""
        return "." + raw.lowercase()
    }

    private fun loadIndex() {
        if (!indexFile.isFile) return
        try {
            val parsed = JSONObject(indexFile.readText())
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = parsed.optString(key, "")
                if (value.isNotEmpty()) links[key] = value
            }
        } catch (error: Exception) {
            Log.w(TAG, "discarding unreadable media index", error)
        }
    }

    private fun saveIndex() {
        val atomic = AtomicFile(indexFile)
        var stream: java.io.FileOutputStream? = null
        try {
            indexFile.parentFile?.mkdirs()
            val json = JSONObject()
            for ((key, value) in links) json.put(key, value)
            stream = atomic.startWrite()
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: IOException) {
            // A crash or partial write must not leave a truncated index that
            // the next launch discards wholesale.
            runCatching { stream?.let(atomic::failWrite) }
            Log.w(TAG, "cannot persist media index", error)
        }
    }

    companion object {
        private const val TAG = "MediaRegistry"
        const val LINK_PREFIX = "linked/"
        const val MEDIA_PREFIX = "/media/"

        fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return bytes.take(8).joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }

        /** Resolves a URL path inside a root, refusing traversal. */
        fun resolveUnder(root: File, relativePath: String): File? {
            val rootCanonical = runCatching { root.canonicalFile }.getOrNull() ?: return null
            val candidate = runCatching {
                File(rootCanonical, relativePath.trimStart('/')).canonicalFile
            }.getOrNull() ?: return null
            val prefix = rootCanonical.path + File.separator
            return if (candidate.path == rootCanonical.path || candidate.path.startsWith(prefix)) {
                candidate
            } else {
                null
            }
        }
    }
}
