package app.recordly.android

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Process-wide host for everything that must exist exactly once: the installed
 * renderer, the media registry and the loopback server.
 *
 * This is not premature structure. Android can start the activity more than
 * once — a second launch intent arriving while the first instance is still in
 * `onCreate` is observed on an emulator — and per-activity startup would then
 * re-copy ~75 MB of renderer (deleting the tree the first server is already
 * serving) and open a second port. Both instances end up pointing their
 * WebViews at a server whose document root was just replaced.
 *
 * The server deliberately lives for the life of the process rather than of an
 * activity: it is daemon-threaded, holds one socket, and its content only
 * changes when the app is reinstalled.
 */
object AppHost {

    class Host(
        val rendererRoot: File,
        val media: MediaRegistry,
        val baseUrl: String,
        /** Per-process secret; sensitive loopback routes require it. */
        val token: String,
    )

    private val lock = Any()

    @Volatile
    private var host: Host? = null

    /**
     * The application context, once anything has installed the host.
     *
     * Capture outlives the activity that started it — a foreground service has
     * to, and an orientation change can replace the activity under a live
     * recording — so the components that run after it need a context that is
     * not the activity's. Null before first launch, which callers treat as "the
     * app is not running".
     */
    @Volatile
    private var appContext: Context? = null

    fun applicationContext(): Context? = appContext

    /** Root of everything served at `/media/`. */
    fun mediaRoot(context: Context): File =
        File(context.applicationContext.filesDir, "media")

    /** Where finished recordings live, i.e. `/media/recordings/`. */
    fun recordingsDir(context: Context): File =
        File(mediaRoot(context), "recordings").apply { mkdirs() }

    /** Installs, starts and returns the host. Safe to call from any thread. */
    fun ensure(context: Context): Host {
        host?.let { return it }
        synchronized(lock) {
            host?.let { return it }

            val app = context.applicationContext
            appContext = app
            ExportStreams.install(app)
            val rendererRoot = RendererAssets.install(app)
            // Per-process capability secret. Everything served under /media/
            // and the export write channel require it, so another app on the
            // device cannot read recordings off the loopback port.
            val token = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            val media = MediaRegistry(
                root = File(app.filesDir, "media"),
                indexFile = File(app.filesDir, "media-index.json"),
                token = token,
            )
            val server = RecordlyServer(
                webRoot = rendererRoot,
                mediaResolver = { path -> media.resolve(path) },
                token = token,
                // The export channel writes into the same process-wide store
                // the bridge's open/close/finalize methods use.
                exportWriter = { streamId, offset, body, length ->
                    ExportStreams.write(streamId, offset, body, length)
                },
            )
            server.start()

            return Host(rendererRoot, media, server.baseUrl, token).also { host = it }
        }
    }
}
