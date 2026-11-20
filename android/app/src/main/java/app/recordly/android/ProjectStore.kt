package app.recordly.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Project library on local storage.
 *
 * Layout, per project:
 *   projects/<id>.recordly.json   the renderer document, with portable media URLs
 *   projects/<id>.meta.json       name, timestamps, thumbnail path
 *   media/thumbs/<id>.png         cached preview frame
 *
 * Keeping the renderer's document byte-for-byte separate from our metadata
 * means `loadProjectFile` can hand back precisely what was saved, and metadata
 * changes (rename, thumbnail refresh) never rewrite a user's project.
 */
class ProjectStore(
    context: Context,
    private val root: File,
    private val media: MediaRegistry,
    private val baseUrlProvider: () -> String,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("recordly.projects", Context.MODE_PRIVATE)

    init {
        root.mkdirs()
        media.ownedFile(THUMBS).mkdirs()
    }

    // ------------------------------------------------------------------ //
    // Current project
    // ------------------------------------------------------------------ //

    fun currentPath(): String? = prefs.getString(KEY_CURRENT, null)

    fun rootPath(): String = root.path

    fun setCurrentPath(path: String?) {
        if (path == null) prefs.edit().remove(KEY_CURRENT).apply()
        else prefs.edit().putString(KEY_CURRENT, path).apply()
    }

    // ------------------------------------------------------------------ //
    // Library
    // ------------------------------------------------------------------ //

    @Synchronized
    fun list(): JSONObject {
        val current = currentPath()
        val files = root.listFiles { file ->
            file.isFile && file.name.endsWith(BODY_SUFFIX)
        } ?: emptyArray()

        val entries = JSONArray()
        for (file in files.sortedByDescending { it.lastModified() }) {
            val id = idOf(file) ?: continue
            val meta = readMeta(id)
            val revision = revision(file)
            val cached = meta.optString("thumbnailPath", "")
                .takeIf {
                    it.isNotEmpty() && meta.optString("thumbnailRevision", "") == revision &&
                        media.ownedFile(it).isFile
                }
            val thumb = cached ?: ensureVideoThumbnail(file, id, meta, revision).orEmpty()
            val entry = JSONObject()
            entry.put("path", file.path)
            entry.put("name", meta.optString("name", defaultName(file)))
            entry.put("createdAt", meta.optLong("createdAt", file.lastModified()))
            entry.put("updatedAt", meta.optLong("updatedAt", file.lastModified()))
            entry.put(
                "thumbnailPath",
                if (thumb.isEmpty()) JSONObject.NULL else media.urlFor(baseUrl(), thumb),
            )
            entry.put("isCurrent", file.path == current)
            entry.put("isInProjectsDirectory", true)
            entries.put(entry)
        }

        val result = JSONObject()
        result.put("success", true)
        result.put("projectsDir", root.path)
        result.put("projects", entries)
        // `entries` is the field the library UI reads; `projects` is kept because
        // upstream's own test harness used both names.
        result.put("entries", entries)
        return result
    }

    /** A source-frame fallback for WebViews that cannot compose a preview. */
    private fun ensureVideoThumbnail(
        file: File,
        id: String,
        meta: JSONObject,
        expectedRevision: String?,
    ): String? {
        if (expectedRevision == null) return null
        val project = readBody(file) ?: return null
        val video = resolveProjectMedia(project.optString("videoPath", "")) ?: return null
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(video.path)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (error: Exception) {
            Log.w(TAG, "could not decode a thumbnail for ${video.name}", error)
            null
        } finally {
            runCatching { retriever.release() }
        } ?: return null
        val output = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val sourceWidth = minOf(frame.width, frame.height * THUMB_WIDTH / THUMB_HEIGHT)
            val sourceHeight = minOf(frame.height, frame.width * THUMB_HEIGHT / THUMB_WIDTH)
            val source = Rect(
                (frame.width - sourceWidth) / 2,
                (frame.height - sourceHeight) / 2,
                (frame.width + sourceWidth) / 2,
                (frame.height + sourceHeight) / 2,
            )
            Canvas(output).drawBitmap(
                frame, source, Rect(0, 0, THUMB_WIDTH, THUMB_HEIGHT),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            ByteArrayOutputStream().use { stream ->
                if (output.compress(Bitmap.CompressFormat.PNG, 100, stream)) stream.toByteArray()
                else null
            }
        } finally {
            output.recycle()
            frame.recycle()
        } ?: return null
        if (revision(file) != expectedRevision) return null
        val relative = "$THUMBS/$id.png"
        return try {
            writeAtomically(media.ownedFile(relative), bytes)
            meta.put("thumbnailPath", relative)
            meta.put("thumbnailRevision", expectedRevision)
            writeMeta(id, meta)
            relative
        } catch (error: Exception) {
            Log.w(TAG, "could not cache a thumbnail for ${file.name}", error)
            null
        }
    }

    @Synchronized
    fun save(
        data: JSONObject,
        suggestedName: String?,
        existingPath: String?,
        thumbnailDataUrl: String?,
    ): JSONObject {
        val existing = existingPath?.let { projectFile(it) }
        if (existingPath != null && existing == null) {
            return JSONObject().apply {
                put("success", false)
                put("message", "Project is outside Recordly's library or no longer exists")
            }
        }
        val id = existing?.let { idOf(it) } ?: newId()
        val file = File(root, "$id$BODY_SUFFIX")

        val now = System.currentTimeMillis()
        val meta = readMeta(id)
        if (!meta.has("createdAt")) meta.put("createdAt", now)
        // Upstream's `save-project-file` ignores `suggestedName` once a trusted
        // existing path is given — re-saving must never rename a project. A
        // missing argument also arrives as the literal strings "null"/
        // "undefined" through `optString`, which must never become a title.
        val proposed = suggestedName?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("undefined", true) }
        val name = (if (existing == null) proposed else null)
            ?: meta.optString("name", "").takeIf { it.isNotEmpty() }
            ?: data.optString("name", "").takeIf { it.isNotEmpty() }
            ?: defaultName(file)
        meta.put("name", name)
        meta.put("updatedAt", now)
        // Saving a project settles its look: if it was migrated on open, the
        // look is now in the body; either way later user edits must win.
        meta.put(LOOK_MIGRATION_FLAG, true)
        meta.put(MOTION_MIGRATION_FLAG, true)

        val thumbnailPath = thumbnailDataUrl?.let { writeThumbnail(id, it) }

        return try {
            // Deep copy so the caller's object is untouched, then drop the
            // ephemeral origin: what lands on disk must not remember a port.
            val portable = JSONObject(data.toString())
            // A new file is a distinct project, including imports and Save As
            // copies. Reusing the source ID makes later saves ambiguous.
            if (existing == null) portable.put("projectId", id)
            rewriteStrings(portable) { value -> stripOriginFrom(value) }
            // Taps are presented as touches, never as a cursor sprite. Applying
            // it on save keeps the document self-describing for reopens and for
            // projects authored on desktop with `showCursor: true`.
            TouchPresentation.applyToProject(portable)
            writeAtomically(file, portable.toString())
            val currentRevision = revision(file)
            val retainedThumbnail = meta.optString("thumbnailPath", "").takeIf {
                it.isNotEmpty() && meta.optString("thumbnailRevision", "") == currentRevision &&
                    media.ownedFile(it).isFile
            }
            val savedThumbnail = thumbnailPath ?: retainedThumbnail
            if (savedThumbnail != null) {
                meta.put("thumbnailPath", savedThumbnail)
                meta.put("thumbnailRevision", currentRevision)
            } else {
                meta.remove("thumbnailPath")
                meta.remove("thumbnailRevision")
            }
            writeMeta(id, meta)
            if (savedThumbnail == null) media.ownedFile("$THUMBS/$id.png").delete()
            setCurrentPath(file.path)

            JSONObject().apply {
                put("success", true)
                put("path", file.path)
                put("projectId", id)
                put("name", name)
                put("updatedAt", now)
            }
        } catch (error: Exception) {
            Log.w(TAG, "save failed", error)
            JSONObject().apply {
                put("success", false)
                put("message", error.message ?: "Could not save project")
            }
        }
    }

    fun create(data: JSONObject, thumbnailDataUrl: String?): JSONObject =
        save(data, suggestedName = null, existingPath = null, thumbnailDataUrl = thumbnailDataUrl)

    /**
     * Save As. `mode = "rename"` renames the current project in place;
     * `"copy"` (the default) writes a new project and leaves the original.
     */
    fun saveNamed(
        data: JSONObject,
        projectName: String,
        thumbnailDataUrl: String?,
        mode: String?,
    ): JSONObject {
        val target = if (mode == "rename") currentPath() else null
        val result = save(data, suggestedName = projectName, existingPath = target, thumbnailDataUrl)
        // `save` deliberately ignores `suggestedName` for an existing file, but
        // an explicit rename is exactly the case where the name must change.
        if (mode == "rename" && result.optBoolean("success")) {
            target?.let { path -> idOf(File(path))?.let { id ->
                val meta = readMeta(id)
                meta.put("name", projectName)
                writeMeta(id, meta)
                result.put("name", projectName)
            } }
        }
        return result
    }

    fun load(path: String): JSONObject {
        val file = projectFile(path)
        if (file == null) {
            return JSONObject().apply {
                put("success", false)
                put("path", path)
                put("message", "Project file not found in Recordly's library")
            }
        }
        val project = readBody(file) ?: return JSONObject().apply {
            put("success", false)
            put("path", path)
            put("message", "Project file is not valid JSON")
        }
        // Existing projects (and imports from desktop) may still ask for a
        // cursor. The loaded document is what the editor applies to its state,
        // so coercion has to happen here, not only on the next save.
        TouchPresentation.applyToProject(project, legacyMotion = needsTouchMotionMigration(file))
        applyTouchLookMigration(file, project)
        setCurrentPath(file.path)
        return JSONObject().apply {
            put("success", true)
            put("project", project)
            put("path", file.path)
            put("recovered", false)
        }
    }

    fun loadCurrent(): JSONObject {
        // Upstream's wording; the renderer surfaces `message` when there is no
        // active project to load.
        val path = currentPath() ?: return JSONObject().apply {
            put("success", false)
            put("message", "No active project")
        }
        return load(path)
    }

    @Synchronized
    fun rename(path: String, name: String): JSONObject {
        val file = projectFile(path) ?: return JSONObject().apply {
            put("success", false)
            // `useProjectOpenActions` reads `result.error`, not `message`.
            put("error", "Unknown project")
        }
        val id = idOf(file) ?: return JSONObject().put("success", false).put("error", "Unknown project")
        val meta = readMeta(id)
        meta.put("name", name)
        meta.put("updatedAt", System.currentTimeMillis())
        writeMeta(id, meta)
        return JSONObject().apply {
            put("success", true)
            put("path", file.path)
            put("name", name)
        }
    }

    @Synchronized
    fun trash(paths: JSONArray, options: JSONObject? = null): JSONObject {
        val deleted = JSONArray()
        val errors = JSONArray()
        val sourcesDeleted = JSONArray()
        val sourcesKept = JSONArray()
        val includeSources = options?.optBoolean("includeSources", false) == true
        val selected = mutableListOf<Pair<String, File>>()

        for (index in 0 until paths.length()) {
            val path = paths.optString(index, "")
            if (path.isEmpty()) continue
            val file = projectFile(path)
            if (file == null) {
                errors.put("Project is outside Recordly's library or no longer exists")
                continue
            }
            selected.add(path to file)
        }

        // Read ownership before deleting any project body. A source used by a
        // project that survives, or listed as protected by the renderer, must
        // not be removed even when the selected projects own it.
        val owners = if (includeSources) {
            selected.flatMapTo(LinkedHashSet()) { sourcePathsForProject(it.second) }
        } else {
            emptySet()
        }
        val preserved = if (includeSources) {
            protectedSourcePaths(options?.optJSONArray("protectedSources")).also { kept ->
                val selectedPaths = selected.mapNotNullTo(mutableSetOf()) {
                    runCatching { it.second.canonicalPath }.getOrNull()
                }
                for (file in projectFiles()) {
                    val canonical = runCatching { file.canonicalPath }.getOrNull()
                    if (canonical != null && canonical !in selectedPaths) {
                        kept.addAll(sourcePathsForProject(file))
                    }
                }
            }
        } else {
            emptySet()
        }

        for ((path, file) in selected) {
            val id = idOf(file)
            val removed = file.delete()
            if (removed) {
                deleted.put(path)
                if (id != null) {
                    File(root, "$id$META_SUFFIX").delete()
                    media.ownedFile("$THUMBS/$id.png").delete()
                }
                if (path == currentPath()) setCurrentPath(null)
            } else {
                // `errors` is a string list upstream; the renderer joins it.
                errors.put("Could not delete ${file.name}")
            }
        }

        if (includeSources) {
            for (source in owners) {
                val file = File(source)
                if (source in preserved) {
                    sourcesKept.put(source)
                } else if (!file.exists()) {
                    // Already gone: nothing to report as deleted.
                } else {
                    deleteSourceFiles(file)
                    if (file.exists()) {
                        errors.put("Could not delete ${file.name}")
                    } else {
                        sourcesDeleted.put(source)
                    }
                }
            }
        }

        return JSONObject().apply {
            put("success", errors.length() == 0)
            put("deleted", deleted)
            put("errors", errors)
            put("sourcesDeleted", sourcesDeleted)
            put("sourcesKept", sourcesKept)
        }
    }

    /** A library preview does not change the active project or capture session. */
    fun preview(path: String): JSONObject {
        val file = projectFile(path)
            ?: return JSONObject().put("success", false).put("error", "Project is not in the library")
        val before = revision(file)
            ?: return JSONObject().put("success", false).put("error", "Could not read the project")
        val project = readBody(file)
            ?: return JSONObject().put("success", false).put("error", "Invalid project file")
        TouchPresentation.applyToProject(project, legacyMotion = needsTouchMotionMigration(file))
        applyTouchLookMigration(file, project)
        if (before != revision(file)) {
            return JSONObject().put("success", false).put("error", "Project changed while loading its preview")
        }
        val video = resolveProjectMedia(project.optString("videoPath", ""))
            ?: return JSONObject().put("success", false).put("error", "Project video is unavailable")
        val videoRelative = media.expose(video)
            ?: return JSONObject().put("success", false).put("error", "Project video is unavailable")
        val webcamRaw = project.optJSONObject("editor")?.optJSONObject("webcam")
            ?.optString("sourcePath", "") ?: ""
        val webcam = resolveProjectMedia(webcamRaw)
        val webcamUrl = webcam?.let { media.expose(it) }?.let { media.urlFor(baseUrl(), it) }
        return JSONObject().apply {
            put("success", true)
            put("value", JSONObject().apply {
                put("project", project)
                put("revision", before)
                put("videoUrl", media.urlFor(baseUrl(), videoRelative))
                put("webcamUrl", webcamUrl ?: JSONObject.NULL)
                // Mirrors `canonicalMediaSources` in the Electron host: every
                // resolvable media reference in the document, including
                // wallpaper and audio sources, so library cleanup never deletes
                // a file a project still uses.
                put("sourcePaths", collectSourcePaths(project, video))
                put("sourcePathsComplete", true)
            })
        }
    }

    /** Store a renderer-composed preview only for the exact project it read. */
    @Synchronized
    fun cacheThumbnail(path: String, expectedRevision: String, dataUrl: String): JSONObject {
        val file = projectFile(path)
            ?: return JSONObject().put("success", false).put("error", "Project is not in the library")
        val id = idOf(file)
            ?: return JSONObject().put("success", false).put("error", "Invalid project path")
        if (revision(file) != expectedRevision) {
            return JSONObject().put("success", true).put("value", JSONObject.NULL)
        }
        val relative = writeThumbnail(id, dataUrl)
            ?: return JSONObject().put("success", false).put("error", "Invalid project thumbnail")
        val meta = readMeta(id)
        meta.put("thumbnailPath", relative)
        meta.put("thumbnailRevision", expectedRevision)
        writeMeta(id, meta)
        return JSONObject().put("success", true).put("value", media.ownedFile(relative).path)
    }

    /** Bring a project chosen through Android's document picker into the library. */
    @Synchronized
    fun importSelected(source: File, displayName: String): JSONObject {
        val project = try {
            JSONTokener(source.readText().removePrefix("\uFEFF")).nextValue() as? JSONObject
        } catch (error: Throwable) {
            null
        }
        if (project == null || project.optJSONObject("editor") == null) {
            return JSONObject().put("success", false).put("error", "Invalid Recordly project file")
        }
        // Imports may carry a loopback port from a previous app process.
        rewriteStrings(project) { value -> attachOrigin(value) }
        if (resolveProjectMedia(project.optString("videoPath", "")) == null) {
            return JSONObject().put("success", false).put(
                "error", "Import this project's source video into Recordly before opening it",
            )
        }
        val name = displayName.replace(Regex("(?i)\\.(?:recordly\\.json|recordly|openscreen)$"), "")
            .ifBlank { "Imported project" }
        val saved = save(project, name, null, null)
        if (!saved.optBoolean("success")) return saved
        val path = saved.optString("path")
        val loaded = readBody(File(path))
            ?: return JSONObject().put("success", false).put("error", "Imported project could not be read")
        return JSONObject().apply {
            put("success", true)
            put("kind", "project")
            put("path", path)
            put("project", loaded)
            put("recovered", false)
        }
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private fun baseUrl(): String = baseUrlProvider()

    private fun newId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(6)
        return "$stamp-$suffix"
    }

    private fun idOf(file: File): String? {
        val name = file.name
        return if (name.endsWith(BODY_SUFFIX)) name.removeSuffix(BODY_SUFFIX) else null
    }

    private fun defaultName(file: File): String = idOf(file) ?: "Untitled project"

    /** All project operations are confined to our own library directory. */
    private fun projectFile(path: String): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val library = runCatching { root.canonicalFile }.getOrNull() ?: return null
        if (file.parentFile != library || !file.name.endsWith(BODY_SUFFIX)) return null
        return file.takeIf { it.isFile || File(it.path + ".bak").isFile }
    }

    private fun writeAtomically(file: File, text: String) =
        writeAtomically(file, text.toByteArray(Charsets.UTF_8))

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            if (stream != null) atomic.failWrite(stream)
            throw error
        }
    }

    private fun revision(file: File): String? = runCatching {
        val bytes = AtomicFile(file).openRead().use { it.readBytes() }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }.getOrNull()

    private fun resolveProjectMedia(raw: String): File? {
        if (raw.isEmpty()) return null
        val mediaPath = when {
            raw.startsWith(baseUrl() + MediaRegistry.MEDIA_PREFIX) ->
                raw.removePrefix(baseUrl() + MediaRegistry.MEDIA_PREFIX).substringBefore('?')
            raw.startsWith(MediaRegistry.MEDIA_PREFIX) ->
                raw.removePrefix(MediaRegistry.MEDIA_PREFIX).substringBefore('?')
            else -> null
        }
        val candidate = if (mediaPath != null) {
            val decoded = runCatching {
                URLDecoder.decode(mediaPath.replace("+", "%2B"), "UTF-8")
            }.getOrNull() ?: return null
            media.resolve(decoded)
        } else {
            File(raw)
        }
        val file = runCatching { candidate?.canonicalFile }.getOrNull() ?: return null
        val owned = runCatching { media.ownedFile("").canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.path.startsWith(owned.path + File.separator) }
    }

    /**
     * Every media file the project references.
     *
     * Same rule as `canonicalMediaSources.ts`: walk every string in the
     * document, keep those ending in a playable audio/video extension, and
     * resolve them against this app's media root. Only this host's loopback
     * media URLs are treated as files; external URLs, blobs and data URLs are
     * not. Missing references are dropped, exactly as upstream drops ENOENT.
     */
    private fun collectSourcePaths(project: JSONObject, video: File?): JSONArray {
        val paths = LinkedHashSet<String>()

        /**
         * Report the path in the app's own spelling, not the canonical one.
         * `/data/user/0/...` resolves to `/data/data/...`; a project reference
         * spelled the canonical way would not match the library's recording
         * paths, and the cleanup scan compares these strings.
         */
        fun addSource(file: File) {
            appPathOf(file)?.let(paths::add)
        }

        video?.let(::addSource)

        fun collect(value: Any?) {
            when (value) {
                is String -> {
                    val raw = value.trim()
                    if (raw.isEmpty()) return
                    val localUrl = raw.startsWith(baseUrl() + MediaRegistry.MEDIA_PREFIX)
                    if (EXTERNAL_SCHEME.containsMatchIn(raw) && !localUrl) return
                    if (!SOURCE_MEDIA.matches(raw)) return
                    val file = resolveProjectMedia(raw) ?: return
                    addSource(file)
                }
                is JSONArray -> for (index in 0 until value.length()) collect(value.opt(index))
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) collect(value.opt(keys.next()))
                }
            }
        }

        collect(project)
        return JSONArray(paths)
    }

    /** The library's spelling of a media file: `/data/user/0/...`, never `/data/data/...`. */
    private fun appPathOf(file: File): String? {
        val exposed = media.expose(file) ?: return null
        return if (exposed.startsWith(MediaRegistry.LINK_PREFIX)) {
            file.path
        } else {
            media.ownedFile(exposed).path
        }
    }

    private fun needsTouchMotionMigration(file: File): Boolean =
        idOf(file)?.let { !readMeta(it).optBoolean(MOTION_MIGRATION_FLAG) } ?: false

    /**
     * Projects saved before the Android floating frame have padding/radius zero.
     * On first open, apply the touch look in memory so old recordings also
     * export with the wallpaper composition. A save writes both the look and
     * [LOOK_MIGRATION_FLAG], so a user's later choice (including full-frame)
     * is respected. A custom wallpaper is preserved.
     */
    private fun applyTouchLookMigration(file: File, project: JSONObject) {
        val id = idOf(file) ?: return
        val meta = readMeta(id)
        if (meta.optBoolean(LOOK_MIGRATION_FLAG, false)) return
        val editor = project.optJSONObject("editor") ?: return
        val padding = editor.optJSONObject("padding")
        val padValues = if (padding == null) {
            listOf(0.0)
        } else {
            PADDING_SIDES.map { padding.optDouble(it, 0.0) }
        }
        val uniform = padValues.distinct().size == 1
        val pad = padValues.first()
        val radius = editor.optDouble("borderRadius", 0.0)
        val radiusDefault = !radius.isFinite() || radius <= PREVIOUS_TOUCH_RADIUS
        // Zero is a pre-look project, 20 was the first Android default, and a
        // project carrying the v2 marker with uniform 75 is the second. A
        // different padding or corner radius is a user choice and is kept.
        val olderDefault = uniform && radiusDefault && (
            pad == 0.0 ||
                pad == 20.0 ||
                (meta.optBoolean(PREVIOUS_LOOK_FLAG, false) && pad == 75.0)
            )
        if (!olderDefault) return
        val customWallpaper = editor.optString("wallpaper", "")
        TouchPresentation.applyLook(editor)
        if (customWallpaper.isNotEmpty() && customWallpaper != DEFAULT_WALLPAPER_PATH) {
            editor.put("wallpaper", customWallpaper)
        }
    }

    /** Every media reference a project owns, in library spelling. */
    private fun sourcePathsForProject(file: File): Set<String> {
        val project = readBody(file) ?: return emptySet()
        val video = resolveProjectMedia(project.optString("videoPath", ""))
        val sources = collectSourcePaths(project, video)
        return buildSet {
            for (index in 0 until sources.length()) {
                sources.optString(index).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    /**
     * `review-project-deletion`: count the distinct source files the selected
     * projects own and how many are protected.
     *
     * A source is preserved when another library project references it or when
     * the renderer lists it as protected (the open editor, a saved project).
     */
    @Synchronized
    fun reviewDeletion(paths: JSONArray, protectedSources: JSONArray?): JSONObject {
        val selected = mutableSetOf<File>()
        for (index in 0 until paths.length()) {
            val file = projectFile(paths.optString(index, ""))
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "A selected project is outside Recordly's library")
                }
            selected.add(file)
        }
        val owners = selected.flatMapTo(LinkedHashSet()) { sourcePathsForProject(it) }
        val preserved = protectedSourcePaths(protectedSources)
        // Compare canonical paths: `projectFile` canonicalizes, `listFiles` does
        // not, and a File object's equality is its path string.
        val selectedPaths = selected.mapNotNullTo(mutableSetOf()) {
            runCatching { it.canonicalPath }.getOrNull()
        }
        for (file in projectFiles()) {
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            if (canonical != null && canonical !in selectedPaths) {
                preserved.addAll(sourcePathsForProject(file))
            }
        }
        return JSONObject().apply {
            put("success", true)
            put("sourceCount", owners.size)
            put("preservedSourceCount", owners.count { it in preserved })
        }
    }

    private fun projectFiles(): List<File> =
        (root.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(BODY_SUFFIX) }

    private fun protectedSourcePaths(protectedSources: JSONArray?): MutableSet<String> {
        val preserved = mutableSetOf<String>()
        if (protectedSources == null) return preserved
        for (index in 0 until protectedSources.length()) {
            val raw = protectedSources.optString(index, "")
            if (raw.isEmpty()) continue
            val file = resolveProjectMedia(raw) ?: runCatching { File(raw).canonicalFile }.getOrNull()
            if (file != null && file.isFile) appPathOf(file)?.let(preserved::add)
        }
        return preserved
    }

    /** Deletes a source and every sidecar this host writes beside it. */
    private fun deleteSourceFiles(file: File) {
        val base = file.path.substringBeforeLast('.')
        val targets = buildList {
            add(file.path)
            add(file.path + CURSOR_SIDECAR_SUFFIX)
            for (suffix in SOURCE_SIDECAR_SUFFIXES) add(base + suffix)
        }
        for (target in targets) {
            val candidate = File(target)
            if (candidate.exists() && !candidate.delete()) {
                Log.w(TAG, "could not delete ${candidate.path}")
            }
        }
    }

    private fun readBody(file: File): JSONObject? = try {
        val body = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = JSONTokener(body).nextValue() as? JSONObject
        if (parsed != null) {
            rewriteStrings(parsed) { value -> attachOrigin(value) }
        }
        parsed
    } catch (error: Exception) {
        Log.w(TAG, "unreadable project ${file.name}", error)
        null
    }

    // ------------------------------------------------------------------ //
    // Loopback origin portability
    // ------------------------------------------------------------------ //
    //
    // Media URLs handed to the renderer point at the loopback server, whose
    // port is chosen per process. A project that remembered that port would be
    // orphaned by the next launch. So the origin is stripped before writing and
    // re-attached against the CURRENT origin on load. This walks parsed values
    // rather than the raw text on purpose: the renderer serializes with escaped
    // slashes (`http:\/\/…`), which any text-level pattern would have to
    // special-case. Only `http://127.0.0.1` / `http://localhost` are touched;
    // external URLs are left alone.

    /**
     * Persist local media as an origin-less, token-less path.
     *
     * The token is a per-process capability secret. A project that remembered
     * one would resolve to a 403 after the next launch, so it is stripped here
     * and re-issued by [attachOrigin] when the project is read. External URLs
     * are untouched: their query parameters are not ours to rewrite.
     */
    private fun stripOriginFrom(value: String): String {
        val loopback = LOOPBACK_ORIGIN_PREFIX.find(value)
        if (loopback != null && loopback.range.first == 0) {
            val relative = value.removePrefix(loopback.value)
            return if (relative.startsWith(MediaRegistry.MEDIA_PREFIX)) {
                media.deauthorize(relative)
            } else {
                relative
            }
        }
        return if (value.startsWith(MediaRegistry.MEDIA_PREFIX)) {
            media.deauthorize(value)
        } else {
            value
        }
    }

    private fun attachOrigin(value: String): String {
        val base = runCatching { baseUrlProvider() }.getOrDefault("")
        if (base.isEmpty()) return value

        // A file written by an older build still carries its dead port: rebase
        // it onto the origin serving this process.
        val loopback = LOOPBACK_ORIGIN_PREFIX.find(value)
        val local = when {
            loopback != null && loopback.range.first == 0 ->
                base + value.removePrefix(loopback.value)
            value.startsWith(MediaRegistry.MEDIA_PREFIX) -> base + value
            else -> return value
        }
        // Re-issue the current token. `authorize` replaces a stale one, so files
        // written before the token existed load the same way as new ones.
        return if (local.startsWith(base + MediaRegistry.MEDIA_PREFIX)) {
            media.authorize(local)
        } else {
            local
        }
    }

    /** Applies [transform] to every string value in [node], in place. */
    private fun rewriteStrings(node: Any?, transform: (String) -> String) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = node.opt(key)) {
                        is String -> {
                            val next = transform(value)
                            if (next != value) node.put(key, next)
                        }
                        else -> rewriteStrings(value, transform)
                    }
                }
            }
            is JSONArray -> {
                for (index in 0 until node.length()) {
                    when (val value = node.opt(index)) {
                        is String -> {
                            val next = transform(value)
                            if (next != value) node.put(index, next)
                        }
                        else -> rewriteStrings(value, transform)
                    }
                }
            }
        }
    }

    private fun readMeta(id: String): JSONObject {
        val file = File(root, "$id$META_SUFFIX")
        if (!file.isFile && !File(file.path + ".bak").isFile) return JSONObject()
        return try {
            val body = AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONTokener(body).nextValue() as? JSONObject ?: JSONObject()
        } catch (error: Exception) {
            JSONObject()
        }
    }

    private fun writeMeta(id: String, meta: JSONObject) {
        writeAtomically(File(root, "$id$META_SUFFIX"), meta.toString())
    }

    /** Accepts a `data:image/png;base64,...` URL and stores the bytes. */
    private fun writeThumbnail(id: String, dataUrl: String): String? {
        if (!dataUrl.startsWith("data:image/png;base64,", ignoreCase = true)) return null
        val comma = dataUrl.indexOf(',')
        val payload = dataUrl.substring(comma + 1)
        val bytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "invalid thumbnail data URL", error)
            return null
        }
        if (bytes.size < PNG_SIGNATURE.size ||
            !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) return null
        val relative = "$THUMBS/$id.png"
        return try {
            writeAtomically(media.ownedFile(relative), bytes)
            relative
        } catch (error: Exception) {
            Log.w(TAG, "cannot write thumbnail", error)
            null
        }
    }

    private companion object {
        const val TAG = "ProjectStore"
        const val KEY_CURRENT = "currentProjectPath"
        const val BODY_SUFFIX = ".recordly.json"
        const val META_SUFFIX = ".meta.json"
        const val THUMBS = "thumbs"
        const val THUMB_WIDTH = 640
        const val THUMB_HEIGHT = 480

        /** Playable media a project may reference (`canonicalMediaSources.ts`). */
        val SOURCE_MEDIA = Regex(
            ".*\\.(mp4|mov|webm|mkv|m4v|wav|m4a|mp3|ogg|flac)$",
            RegexOption.IGNORE_CASE,
        )
        val EXTERNAL_SCHEME = Regex("^(https?|blob|data):", RegexOption.IGNORE_CASE)

        /** `http://127.0.0.1:39983/media/x.mp4` → `/media/x.mp4`. */
        val LOOPBACK_ORIGIN_PREFIX =
            Regex("""^https?://(?:127\.0\.0\.1|localhost)(?::\d+)?(?=[/?#]|$)""")
        const val CURSOR_SIDECAR_SUFFIX = ".cursor.json"
        const val LOOK_MIGRATION_FLAG = "touchFrameMigratedV3"
        const val MOTION_MIGRATION_FLAG = "touchMotionMigratedV1"
        const val PREVIOUS_LOOK_FLAG = "touchFrameMigratedV2"
        const val PREVIOUS_TOUCH_RADIUS = 3.0
        const val DEFAULT_WALLPAPER_PATH = "/wallpapers/tahoe-light.jpg"
        val PADDING_SIDES = arrayOf("top", "bottom", "left", "right")

        /** Sidecars `discardFiles.ts` removes beside a source recording. */
        val SOURCE_SIDECAR_SUFFIXES = arrayOf(
            ".recording-diagnostics.json",
            ".recordly-session.json",
            ".system.m4a",
            ".mic.m4a",
            ".system.wav",
            ".mic.wav",
            ".system.wav.json",
            ".mic.wav.json",
        )
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
    }
}
