package app.recordly.android

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Loopback HTTP server for the Recordly renderer.
 *
 * Why a server at all, rather than WebViewAssetLoader or file://:
 *
 *   - The renderer was written for `http://localhost` under Vite. Its media
 *     pipeline (WebCodecs, wasm, fetch-based muxing) needs a secure context and
 *     a real origin; `file://` is an opaque origin and WebCodecs refuses to run.
 *   - Video seeking requires real HTTP Range support. An InputStream over
 *     `assets/` cannot seek, so we serve from a copy of the renderer on disk
 *     and use RandomAccessFile.
 *   - Same-origin media means no CORS, no blob:// plumbing, and `videoPath` can
 *     be handed to the renderer as a URL exactly as upstream's own test
 *     harness does.
 *
 * The behaviour here is a port of `dev/serve.mjs`, which is the executable
 * specification: same routes, same injection point, same range semantics.
 */
class RecordlyServer(
    private val webRoot: File,
    /**
     * Maps a `/media/...` path (already prefix-stripped) to a file on disk.
     * Backed by [MediaRegistry] so recordings, exports and files imported from
     * outside the app all resolve without copying multi-gigabyte videos.
     */
    private val mediaResolver: (String) -> File?,
    /**
     * Shared secret required on `/media/` and the export channel. Empty
     * disables the check, which keeps tests and dev tooling token-free.
     */
    private val token: String = "",
    /**
     * Export chunk sink: `(streamId, offset, body, length) -> result`.
     *
     * The export bytes never travel through the JSON bridge — a typed array
     * stringifies to an index-keyed object — so the renderer POSTs them here
     * and this hands the socket stream to [ExportStreams].
     */
    private val exportWriter: ((String, Long, InputStream, Long) -> JSONObject)? = null,
) {
    private val socket: ServerSocket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool: ExecutorService = Executors.newFixedThreadPool(POOL_SIZE) { runnable ->
        Thread(runnable, "recordly-http").apply { isDaemon = true }
    }

    private var started = false
    private var warnedMissingBridge = false

    /** e.g. `http://127.0.0.1:41234` — no trailing slash. */
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: IOException) {
                    break
                }
                try {
                    pool.execute { handleSafely(client) }
                } catch (rejected: RuntimeException) {
                    runCatching { client.close() }
                }
            }
        }, "recordly-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "listening on $baseUrl (root=$webRoot)")
    }

    fun stop() {
        runCatching { socket.close() }
        pool.shutdownNow()
        runCatching { pool.awaitTermination(500, TimeUnit.MILLISECONDS) }
        Log.i(TAG, "stopped")
    }

    // ------------------------------------------------------------------ //
    // Request loop
    // ------------------------------------------------------------------ //

    private fun handleSafely(client: Socket) {
        try {
            client.soTimeout = ioTimeoutMs
            client.use { serve(it) }
        } catch (error: IOException) {
            // Client hung up mid-write (video scrubbing does this constantly).
        } catch (error: Throwable) {
            Log.w(TAG, "request failed", error)
        }
    }

    private fun serve(client: Socket) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val request = try {
            readRequest(input)
        } catch (error: HeaderTooLargeException) {
            writeStatus(output, 431)
            runCatching { output.flush() }
            return
        } ?: return
        val path = decodePath(request.target.substringBefore('?').substringBefore('#'))

        try {
            // Renderer assets are public on the loopback origin; user media and
            // the write channel are not.
            if (requiresAuth(path) && !isAuthorized(request)) {
                writeStatus(output, 403)
                return
            }
            when {
                // The export byte channel. Checked before the GET gate because
                // it is the only POST this server accepts.
                path == EXPORT_STREAM_PATH ->
                    serveExportChunk(output, request, input)

                request.method != "GET" && request.method != "HEAD" ->
                    writeStatus(output, 405)

                path.startsWith(MEDIA_PREFIX) ->
                    serveFile(output, request, mediaResolver(path.removePrefix(MEDIA_PREFIX)))

                else -> serveStatic(output, request, path)
            }
        } finally {
            runCatching { output.flush() }
        }
    }

    /** Only user data and the export channel need the token. */
    private fun requiresAuth(path: String): Boolean =
        path == EXPORT_STREAM_PATH || path.startsWith(MEDIA_PREFIX)

    private fun isAuthorized(request: Request): Boolean {
        if (token.isEmpty()) return true
        val provided = queryParams(request.target)["token"]
            ?: request.headers["x-recordly-token"]
            ?: return false
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8),
        )
    }

    /**
     * `POST /__recordly/export?stream=<id>&offset=<n>` with the chunk as the
     * raw body.
     *
     * The body is copied by [exportWriter] straight from the socket, so the
     * only limit on chunk size is memory in the renderer, not here. The reply
     * is JSON so the shim can report the writer's own error.
     */
    private fun serveExportChunk(
        output: OutputStream,
        request: Request,
        input: InputStream,
    ) {
        if (request.method != "POST") {
            writeStatus(output, 405)
            return
        }
        val writer = exportWriter
        if (writer == null) {
            writeJson(output, 503, JSONObject().put("success", false).put("error", "Export channel unavailable"))
            return
        }
        val query = queryParams(request.target)
        val streamId = query["stream"]
        val offset = query["offset"]?.toLongOrNull()
        val length = request.headers["content-length"]?.toLongOrNull()
        if (streamId.isNullOrEmpty() || offset == null || length == null || length < 0) {
            // Without a length the body cannot be delimited; refusing is the
            // only option that does not corrupt the stream.
            writeJson(output, 400, JSONObject().put("success", false).put("error", "Invalid export write request"))
            return
        }
        val result = try {
            writer(streamId, offset, input, length)
        } catch (error: Throwable) {
            Log.w(TAG, "export chunk write failed", error)
            JSONObject().put("success", false).put("error", error.message ?: "Export write failed")
        }
        writeJson(output, if (result.optBoolean("success", false)) 200 else 409, result)
    }

    private fun serveStatic(output: OutputStream, request: Request, path: String) {
        val file = resolveUnder(webRoot, path)

        if (file == null || !file.exists()) {
            // SPA fallback: an unknown path is a client route, not a missing file.
            val index = File(webRoot, "index.html")
            if (index.isFile) serveShell(output, request, index) else writeStatus(output, 404)
            return
        }

        if (file.isDirectory) {
            val index = File(file, "index.html")
            if (index.isFile) serveShell(output, request, index) else writeStatus(output, 404)
            return
        }

        if (file.name.endsWith(".html", ignoreCase = true)) {
            serveShell(output, request, file)
            return
        }

        // Vite emits content-hashed filenames, so these are safe to cache hard.
        serveFile(
            output,
            request,
            file,
            if (path.startsWith("/assets/")) CACHE_IMMUTABLE else CACHE_NEVER,
        )
    }

    // ------------------------------------------------------------------ //
    // Shell injection
    // ------------------------------------------------------------------ //

    /**
     * Serves index.html verbatim.
     *
     * The bridge is inlined into this file at install time by [RendererAssets],
     * so there is no injection here and no chance of serving a shell without
     * `window.electronAPI`. The marker check is a tripwire: if it ever fires,
     * the installed shell is wrong and the renderer *will* crash on boot.
     */
    private fun serveShell(output: OutputStream, request: Request, file: File) {
        val html = try {
            file.readText()
        } catch (error: IOException) {
            writeStatus(output, 500)
            return
        }

        if (!html.contains(BRIDGE_MARKER) && !warnedMissingBridge) {
            warnedMissingBridge = true
            Log.e(TAG, "served shell has no inlined bridge: ${file.path}")
        }

        val body = html.toByteArray(Charsets.UTF_8)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "text/html; charset=utf-8"
        headers["Content-Length"] = body.size.toString()
        headers["Cache-Control"] = CACHE_NEVER
        writeHeaders(output, 200, headers)

        if (request.method != "HEAD") output.write(body)
    }

    // ------------------------------------------------------------------ //
    // File serving with Range
    // ------------------------------------------------------------------ //

    private fun serveFile(
        output: OutputStream,
        request: Request,
        file: File?,
        cacheControl: String? = null,
    ) {
        if (file == null || !file.isFile) {
            writeStatus(output, 404)
            return
        }

        val size = file.length()
        val type = mimeType(file.name)
        val range = request.range

        // A zero-length file has no satisfiable range; answering 200 with an
        // empty body is the only correct reply.
        if (size == 0L) {
            writeHeaders(
                output,
                200,
                linkedMapOf("Content-Type" to type, "Content-Length" to "0"),
            )
            return
        }

        val start: Long
        val end: Long

        if (range != null) {
            val parsed = parseRange(range, size)
            if (parsed == null) {
                writeHeaders(
                    output,
                    416,
                    linkedMapOf("Content-Range" to "bytes */$size", "Accept-Ranges" to "bytes"),
                )
                return
            }
            start = parsed.first
            end = parsed.second
            writeHeaders(output, 206, rangeHeaders(type, start, end, size, cacheControl))
        } else {
            start = 0L
            end = size - 1
            writeHeaders(output, 200, rangeHeaders(type, start, end, size, cacheControl))
        }

        if (request.method == "HEAD" || size == 0L) return
        stream(file, start, end, output)
    }

    private fun rangeHeaders(
        type: String,
        start: Long,
        end: Long,
        size: Long,
        cacheControl: String?,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = type
        headers["Content-Length"] = (end - start + 1).toString()
        headers["Content-Range"] = "bytes $start-$end/$size"
        headers["Accept-Ranges"] = "bytes"
        if (cacheControl != null) headers["Cache-Control"] = cacheControl
        return headers
    }

    /** Streams `[start, end]` inclusive. Seeks; never reads the whole file. */
    private fun stream(file: File, start: Long, end: Long, output: OutputStream) {
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(start)
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = end - start + 1
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = handle.read(buffer, 0, want)
                if (read <= 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    // ------------------------------------------------------------------ //
    // HTTP plumbing
    // ------------------------------------------------------------------ //

    private class Request(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    ) {
        val range: String? get() = headers["range"]
    }

    /**
     * Reads the request head and stops exactly at the blank line.
     *
     * This reads byte-by-byte rather than through a [java.io.BufferedReader]:
     * a reader pulls body bytes into its character buffer, which is harmless
     * while every request is a bodyless GET but silently corrupts the first
     * chunk of an MP4 on the POST export channel. The delimiter is CRLFCRLF,
     * which is also what lets the body be read from the same stream afterwards.
     */
    private fun readRequest(input: InputStream): Request? {
        val head = readHeaderBlock(input) ?: return null
        val lines = String(head, Charsets.ISO_8859_1).split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null

        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        return Request(parts[0].uppercase(), parts[1], headers)
    }

    /** Raised when a client sends an unbounded request head. */
    private class HeaderTooLargeException : Exception()

    /** The raw bytes up to and including the terminating blank line. */
    private fun readHeaderBlock(input: InputStream): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream(512)
        var matched = 0
        while (true) {
            val value = input.read()
            if (value < 0) return if (buffer.size() == 0) null else buffer.toByteArray()
            buffer.write(value)
            if (buffer.size() > MAX_HEADER_BYTES) throw HeaderTooLargeException()
            matched = when {
                value == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                value == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                else -> 0
            }
            if (matched == 4) return buffer.toByteArray()
        }
    }

    /** `?a=1&b=2` → map, with `+` decoded as space (correct for a query). */
    private fun queryParams(target: String): Map<String, String> {
        val query = target.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val params = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = if (pair.contains('=')) pair.substringAfter('=') else ""
            params[decodeQuery(key)] = decodeQuery(value)
        }
        return params
    }

    private fun decodeQuery(raw: String): String = try {
        URLDecoder.decode(raw, "UTF-8")
    } catch (error: IllegalArgumentException) {
        raw
    }

    private fun writeStatus(output: OutputStream, status: Int) {
        writeHeaders(output, status, mapOf("Content-Length" to "0"))
    }

    /** The export channel's reply: the writer's own result, verbatim. */
    private fun writeJson(output: OutputStream, status: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        writeHeaders(
            output,
            status,
            linkedMapOf(
                "Content-Type" to "application/json; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
                "Cache-Control" to CACHE_NEVER,
            ),
        )
        output.write(bytes)
    }

    private fun writeHeaders(output: OutputStream, status: Int, headers: Map<String, String>) {
        val builder = StringBuilder(256)
        builder.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        builder.append("Connection: close\r\n")
        for ((key, value) in headers) {
            builder.append(key).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        416 -> "Range Not Satisfiable"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> "Internal Server Error"
    }

    /**
     * Parses a single `bytes=` range. Multi-range responses are not supported —
     * browsers never ask for them from a `<video>` element, and returning the
     * first range of a multipart request would be wrong rather than merely
     * incomplete, so anything unfamiliar becomes a 416.
     */
    private fun parseRange(header: String, size: Long): Pair<Long, Long>? {
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null

        val spec = value.substring("bytes=".length).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            val start = (size - suffix).coerceAtLeast(0L)
            if (size == 0L) return null
            return start to (size - 1)
        }

        val start = startText.toLongOrNull() ?: return null
        if (start < 0L || start >= size) return null

        val end = if (endText.isEmpty()) {
            size - 1
        } else {
            (endText.toLongOrNull() ?: return null).coerceAtMost(size - 1)
        }
        if (end < start) return null
        return start to end
    }

    /** `+` is a literal plus in a path segment; URLDecoder would eat it. */
    private fun decodePath(raw: String): String = try {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (error: IllegalArgumentException) {
        raw
    }

    /** Resolves a URL path inside a root, refusing traversal. */
    private fun resolveUnder(root: File, urlPath: String): File? {
        val rootCanonical = try {
            root.canonicalFile
        } catch (error: IOException) {
            return null
        }
        val candidate = try {
            File(rootCanonical, urlPath.trimStart('/')).canonicalFile
        } catch (error: IOException) {
            return null
        }
        val prefix = rootCanonical.path + File.separator
        val path = candidate.path
        return if (path == rootCanonical.path || path.startsWith(prefix)) candidate else null
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "RecordlyServer"
        const val POOL_SIZE = 8
        const val BUFFER_SIZE = 64 * 1024
        const val ioTimeoutMs = 20_000
        const val MAX_HEADER_BYTES = 32 * 1024
        const val MEDIA_PREFIX = "/media/"

        /** POST target of the export byte channel. Never served as a file. */
        const val EXPORT_STREAM_PATH = "/__recordly/export"
        const val CACHE_NEVER = "no-store"
        const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
    }
}


