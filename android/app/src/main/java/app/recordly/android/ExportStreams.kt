package app.recordly.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary files the renderer streams an export into, and the move that
 * publishes one.
 *
 * The exporter runs in the WebView and produces the MP4 as bytes; those bytes
 * cannot cross the JSON bridge (`JSON.stringify` turns a typed array into an
 * index-keyed object). They arrive instead as POST bodies to
 * `/__recordly/export`, which calls [write] with the socket stream — so a
 * chunk is copied from the network straight into the file and never held in
 * memory.
 *
 * This mirrors `electron/ipc/export/exportStream.ts`: one session per stream id,
 * absolute-position writes (the renderer may issue them concurrently), a
 * high-water mark reported on close, and an abort that leaves no file behind.
 *
 * Sessions live under `files/export-temp`, which is *not* served: a partially
 * written export must not be reachable at a URL. [finalize] is the only way a
 * file leaves it.
 */
object ExportStreams {

    private const val TAG = "ExportStreams"

    /** Same allowlist as upstream: a short, harmless file extension. */
    private val EXTENSION = Regex("^[a-z0-9]{1,8}$")
    private val ILLEGAL_NAME_CHARS = Regex("[<>:\"/\\\\|?*\\p{Cntrl}]")
    private const val COPY_BUFFER = 64 * 1024
    private const val TEMP_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private val sessions = ConcurrentHashMap<String, Session>()

    /**
     * High-water mark of the last successful close, keyed by temp path.
     *
     * `finalize` is a separate IPC call, so without this it cannot tell a fully
     * streamed export from a truncated one. The entry is consumed on finalize.
     */
    private val closedSizes = ConcurrentHashMap<String, Long>()

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        closedSizes.clear()
        // A process kill between open and close leaves temp files behind. They
        // are never served, but they are also never reused; sweep day-old ones.
        runCatching {
            val dir = File(context.filesDir, TEMP_DIR)
            val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    /** `{success, streamId, tempPath}` — the temp file is created but empty. */
    fun open(extension: String): JSONObject {
        val context = appContext ?: return failure("Recordly is not running")
        if (!EXTENSION.matches(extension)) {
            return failure("Invalid export stream extension: $extension")
        }
        val dir = File(context.filesDir, TEMP_DIR).apply { mkdirs() }
        val streamId = UUID.randomUUID().toString()
        val temp = File(dir, "$streamId.$extension")
        sessions[streamId] = Session(temp)
        return JSONObject().apply {
            put("success", true)
            put("streamId", streamId)
            put("tempPath", temp.path)
        }
    }

    /**
     * Copies exactly [length] bytes from [input] into the stream at [offset].
     *
     * Reading exactly the advertised length is what keeps the HTTP framing
     * honest: the socket's next bytes belong to whoever speaks next.
     */
    fun write(streamId: String, offset: Long, input: InputStream, length: Long): JSONObject {
        val session = sessions[streamId] ?: return failure("Export stream not found: $streamId")
        if (offset < 0 || length < 0) return failure("Invalid export write position")
        synchronized(session) {
            if (session.aborted) return failure("Export stream was aborted")
            val handle = session.handle ?: return failure("Export stream is closed")
            return try {
                handle.seek(offset)
                var remaining = length
                val buffer = ByteArray(COPY_BUFFER)
                while (remaining > 0L) {
                    val want = minOf(remaining, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) return failure("Export write made no progress")
                    handle.write(buffer, 0, read)
                    remaining -= read
                }
                session.bytesWritten += length
                session.watermark = maxOf(session.watermark, offset + length)
                JSONObject().put("success", true)
            } catch (error: Throwable) {
                Log.w(TAG, "export write failed", error)
                failure(error.message ?: "Export write failed")
            }
        }
    }

    /**
     * Closes the stream. An aborted close deletes the temp file and answers
     * `tempPath: null`, so a caller can never reuse a path that no longer
     * exists; a normal close reports the high-water mark, as upstream does.
     */
    fun close(streamId: String, abort: Boolean): JSONObject {
        val session = sessions.remove(streamId)
            ?: return failure("Export stream not found: $streamId")
        synchronized(session) {
            session.aborted = session.aborted || abort
            runCatching { session.handle?.close() }
            session.handle = null
        }
        if (abort) {
            session.file.delete()
            closedSizes.remove(
                runCatching { session.file.canonicalPath }.getOrDefault(session.file.path),
            )
            return JSONObject().apply {
                put("success", true)
                put("tempPath", JSONObject.NULL)
                put("bytesWritten", 0)
            }
        }
        closedSizes[runCatching { session.file.canonicalPath }.getOrDefault(session.file.path)] =
            session.watermark
        return JSONObject().apply {
            put("success", true)
            put("tempPath", session.file.path)
            put("bytesWritten", session.watermark)
        }
    }

    /** Deletes a temp file the renderer decided not to save. */
    fun discard(tempPath: String): JSONObject {
        val context = appContext ?: return failure("Recordly is not running")
        val file = withinTemp(context, tempPath)
            ?: return failure("Temp path is not an app-managed export temp")
        closedSizes.remove(runCatching { file.canonicalPath }.getOrDefault(file.path))
        if (file.exists() && !file.delete()) return failure("Could not delete the export temp file")
        return JSONObject().put("success", true)
    }

    /**
     * Moves a finished temp file to its destination.
     *
     * On desktop `finalize-exported-video` opens a save dialog; Android has no
     * file picker here, so the destination is a unique name in the app's export
     * directory (which the media server exposes, so the editor can play it
     * back). The file is then copied into MediaStore under
     * `Movies/Recordly`, which is how it becomes visible to the rest of the
     * device — the copy is deliberate, because the app's own copy keeps a
     * stable path for "save again"/reveal while the gallery entry is what the
     * user actually opens. A failed gallery publish never fails the export.
     */
    fun finalize(payload: JSONObject, exportsDir: File): JSONObject {
        val context = appContext ?: return failure("Recordly is not running")
        val tempRaw = payload.optString("tempPath", "")
        val fileName = payload.optString("fileName", "")
        if (tempRaw.isEmpty() || fileName.isEmpty()) {
            return failure("Invalid finalize-exported-video payload")
        }
        val temp = withinTemp(context, tempRaw)
            ?: return failure("Temp path is not an app-managed export temp")
        if (!temp.isFile) return failure("Exported video temp file is missing: ${temp.path}")
        val expectedBytes = closedSizes.remove(
            runCatching { temp.canonicalPath }.getOrDefault(temp.path),
        ) ?: 0L
        if (temp.length() <= 0L) return failure("The exported video is empty")
        if (expectedBytes > 0L && temp.length() < expectedBytes) {
            return failure("The exported video was truncated (${temp.length()} of $expectedBytes bytes)")
        }

        // `optString` renders JSON null as the literal "null". Without this,
        // `outputPath: null` — what the renderer sends when it is not saving to
        // a smoke-test path — becomes `File("null")`, which resolves relative
        // to the process directory and is then rejected as outside app storage.
        val outputPath = payload.optString("outputPath", "")
            .takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("undefined", true) }
        val destination = try {
            if (outputPath != null) {
                val target = File(outputPath).canonicalFile
                // The page controls this string. Keep it inside the export
                // folder so an export can never overwrite projects, the
                // renderer, or the media index.
                val exportsRoot = exportsDir.canonicalFile
                val allowed = exportsRoot.path + File.separator
                if (!target.path.startsWith(allowed)) {
                    return failure("This build can only save exports into Recordly's export folder")
                }
                target
            } else {
                exportsDir.mkdirs()
                uniqueFile(exportsDir, sanitizeName(fileName))
            }
        } catch (error: Throwable) {
            return failure(error.message ?: "Could not resolve the export destination")
        }

        try {
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
            move(temp, destination)
        } catch (error: Throwable) {
            Log.w(TAG, "cannot move the export into place", error)
            return JSONObject().apply {
                put("success", false)
                put("canceled", false)
                put("message", "Failed to save exported video")
                put("error", error.message ?: "Could not save the export")
            }
        }

        val captionError = writeCaptionSidecars(destination, payload.optJSONObject("captionSidecar"))
        publishToGallery(context, destination)

        return JSONObject().apply {
            put("success", true)
            put("path", destination.path)
            put("canceled", false)
            put(
                "message",
                if (captionError == null) EXPORTED_MESSAGE
                else "$EXPORTED_MESSAGE Captions could not be saved alongside the video.",
            )
            put("captionSidecarFailed", captionError != null)
        }
    }

    // ------------------------------------------------------------------ //
    // Caption sidecars (`exportCaptionSidecars.ts`)
    // ------------------------------------------------------------------ //

    /**
     * Writes the requested `.srt`/`.vtt` beside the export. Returns an error
     * message when any requested format failed — one failed format does not
     * stop the other.
     */
    private fun writeCaptionSidecars(video: File, payload: JSONObject?): String? {
        if (payload == null) return null
        val format = payload.optString("format", "")
        if (format != "srt" && format != "vtt" && format != "both") return null
        val cues = payload.optJSONArray("cues") ?: JSONArray()
        val name = video.name
        val stem = if (name.contains('.')) name.substringBeforeLast('.') else name
        val base = File(video.parentFile, stem)

        var failure: String? = null
        for (extension in if (format == "both") listOf("srt", "vtt") else listOf(format)) {
            val target = File("${base.path}.$extension")
            val body = if (extension == "srt") serializeSrt(cues) else serializeVtt(cues)
            try {
                if (target.exists()) throw IllegalStateException("${target.name} already exists")
                target.writeText(body)
            } catch (error: Throwable) {
                Log.w(TAG, "cannot write caption sidecar ${target.name}", error)
                failure = error.message ?: "Could not write ${target.name}"
            }
        }
        return failure
    }

    private fun serializeSrt(cues: JSONArray): String {
        val body = StringBuilder()
        for (index in 0 until cues.length()) {
            val cue = cues.optJSONObject(index) ?: continue
            if (index > 0) body.append("\n\n")
            body.append(index + 1).append('\n')
            body.append(timestamp(cue.optLong("startMs", 0L), ',')).append(" --> ")
            body.append(timestamp(cue.optLong("endMs", 0L), ',')).append('\n')
            body.append(cue.optString("text", ""))
        }
        return body.toString()
    }

    private fun serializeVtt(cues: JSONArray): String {
        val body = StringBuilder("WEBVTT\n\n")
        for (index in 0 until cues.length()) {
            val cue = cues.optJSONObject(index) ?: continue
            if (index > 0) body.append("\n\n")
            body.append(timestamp(cue.optLong("startMs", 0L), '.')).append(" --> ")
            body.append(timestamp(cue.optLong("endMs", 0L), '.')).append('\n')
            body.append(cue.optString("text", ""))
        }
        return body.toString()
    }

    /** `HH:MM:SS,mmm` for SRT and `HH:MM:SS.mmm` for VTT. */
    private fun timestamp(valueMs: Long, separator: Char): String {
        val ms = valueMs.coerceAtLeast(0L)
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1000
        val millis = ms % 1000
        return "%02d:%02d:%02d%c%03d".format(hours, minutes, seconds, separator, millis)
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    /**
     * Makes the finished file visible outside the app.
     *
     * API 29+ MediaStore: insert pending, stream the bytes in, then clear the
     * pending flag so no half-written video is ever indexed.
     */
    private fun publishToGallery(context: Context, file: File) {
        val isGif = file.name.endsWith(".gif", ignoreCase = true)
        val collection =
            if (isGif) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (isGif) "image/gif" else "video/mp4",
            )
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (isGif) "Pictures/Recordly" else "Movies/Recordly")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = try {
            resolver.insert(collection, values) ?: run {
                Log.w(TAG, "MediaStore refused the export entry")
                return
            }
        } catch (error: Throwable) {
            Log.w(TAG, "cannot insert into MediaStore", error)
            return
        }
        try {
            val wrote = resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } != null
            if (!wrote) throw IllegalStateException("MediaStore stream unavailable")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        } catch (error: Throwable) {
            Log.w(TAG, "cannot publish the export to the gallery", error)
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** Moves across filesystems if the rename is refused. */
    private fun move(source: File, target: File) {
        if (source.renameTo(target)) return
        source.copyTo(target, overwrite = true)
        if (!source.delete()) Log.w(TAG, "cannot remove the export temp after moving it")
    }

    /** A canonical file inside the temp directory, or null. */
    private fun withinTemp(context: Context, raw: String): File? {
        val dir = try {
            File(context.filesDir, TEMP_DIR).canonicalFile
        } catch (error: Throwable) {
            return null
        }
        val candidate = try {
            File(raw).canonicalFile
        } catch (error: Throwable) {
            return null
        }
        val prefix = dir.path + File.separator
        return if (candidate.path.startsWith(prefix)) candidate else null
    }

    private fun sanitizeName(fileName: String): String {
        val cleaned = fileName.replace(ILLEGAL_NAME_CHARS, "").trim()
        return if (cleaned.isEmpty()) "Recordly export.mp4" else cleaned
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists()) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            candidate = File(dir, "$stem-$index$suffix")
            index++
        }
        return candidate
    }

    /**
     * Failures carry both keys: `error` is the machine-readable one, and
     * `message` is what `useExportDialogActions` appends to "Failed to save
     * video". Without `message` the UI hides the cause entirely.
     */
    private fun failure(message: String) = JSONObject().apply {
        put("success", false)
        put("error", message)
        put("message", message)
    }

    private class Session(val file: File) {
        var handle: RandomAccessFile? = RandomAccessFile(file, "rw")
        var aborted: Boolean = false
        var bytesWritten: Long = 0
        var watermark: Long = 0
    }

    private const val TEMP_DIR = "export-temp"
    private const val EXPORTED_MESSAGE = "Video exported successfully"
}
