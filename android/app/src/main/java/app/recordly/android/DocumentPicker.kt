package app.recordly.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Copies a user-selected SAF document into app storage for seekable media URLs. */
object DocumentPicker {
    private enum class Kind { VIDEO, AUDIO }

    private data class Request(
        val kind: Kind,
        val includeProjects: Boolean,
        val result: CompletableFuture<JSONObject>,
    )

    private val lock = Any()
    private var owner: Any? = null
    private var launcher: ((Intent) -> Unit)? = null
    private var pending: Request? = null
    private val copies = Executors.newSingleThreadExecutor { task ->
        Thread(task, "recordly-document-copy").apply { isDaemon = true }
    }

    fun attach(owner: Any, launch: (Intent) -> Unit) = synchronized(lock) {
        this.owner = owner
        launcher = launch
    }

    fun detach(owner: Any) = synchronized(lock) {
        if (this.owner === owner) {
            this.owner = null
            launcher = null
        }
    }

    fun openVideo(includeProjects: Boolean): CompletableFuture<JSONObject> =
        open(Kind.VIDEO, includeProjects)

    fun openAudio(): CompletableFuture<JSONObject> = open(Kind.AUDIO, false)

    private fun open(kind: Kind, includeProjects: Boolean): CompletableFuture<JSONObject> {
        val result = CompletableFuture<JSONObject>()
        val launch: (Intent) -> Unit
        synchronized(lock) {
            if (pending != null) {
                result.complete(failure("A file picker is already open"))
                return result
            }
            launch = launcher ?: run {
                result.complete(failure("The app is not in the foreground"))
                return result
            }
            pending = Request(kind, includeProjects, result)
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            type = when {
                kind == Kind.AUDIO -> "audio/*"
                includeProjects -> "*/*"
                else -> "video/*"
            }
        }
        try {
            launch(intent)
        } catch (error: Throwable) {
            synchronized(lock) { if (pending?.result === result) pending = null }
            result.complete(failure(error.message ?: "Could not open the file picker"))
        }
        return result
    }

    fun onResult(context: Context, resultCode: Int, data: Intent?) {
        val request = synchronized(lock) { pending.also { pending = null } } ?: return
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            request.result.complete(JSONObject().put("success", false).put("canceled", true))
            return
        }
        copies.execute {
            val response = try {
                copyDocument(context.applicationContext, uri, request)
            } catch (error: Throwable) {
                failure(error.message ?: "Could not import the selected file")
            }
            request.result.complete(response)
        }
    }

    /** Also handles failures raised after the Activity posts the launch to UI. */
    fun onLaunchError(message: String) {
        val request = synchronized(lock) { pending.also { pending = null } } ?: return
        request.result.complete(failure(message))
    }

    private fun copyDocument(context: Context, uri: Uri, request: Request): JSONObject {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            } ?: return failure("The selected document has no file name")
        val lower = displayName.lowercase()
        val isProject = request.kind == Kind.VIDEO && request.includeProjects &&
            PROJECT_SUFFIXES.any { lower.endsWith(it) }
        val extension = lower.substringAfterLast('.', "")
        val accepted = when {
            isProject -> true
            request.kind == Kind.VIDEO -> extension in VIDEO_EXTENSIONS
            else -> extension in AUDIO_EXTENSIONS
        }
        if (!accepted) return failure("The selected file type is not supported")

        val dir = File(AppHost.mediaRoot(context), "imports").apply { mkdirs() }
        val target = File(dir, "import-${UUID.randomUUID()}.$extension")
        try {
            val input = resolver.openInputStream(uri)
                ?: return failure("Could not read the selected document")
            input.use { source ->
                FileOutputStream(target).use { output ->
                    source.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (target.length() == 0L) {
                target.delete()
                return failure("The selected document is empty")
            }
            return JSONObject().apply {
                put("success", true)
                put("path", target.path)
                put("name", displayName)
                if (request.kind == Kind.VIDEO) put("kind", if (isProject) "project" else "media")
                if (!isProject) put("extension", extension)
            }
        } catch (error: Throwable) {
            target.delete()
            return failure(error.message ?: "Could not copy the selected document")
        }
    }

    private fun failure(message: String): JSONObject = JSONObject().apply {
        put("success", false)
        put("canceled", false)
        put("error", message)
        put("message", message)
    }

    private val VIDEO_EXTENSIONS = setOf("webm", "mp4", "mov", "m4v", "avi", "mkv")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "aac", "m4a", "flac", "ogg")
    private val PROJECT_SUFFIXES = setOf(".recordly", ".openscreen", ".recordly.json")
}
