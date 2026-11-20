package app.recordly.android

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.nio.ByteBuffer

/**
 * Appends one recording to another without re-encoding.
 *
 * Upstream's `import-recording` normalizes both sides with ffmpeg and then
 * concatenates them. Android has no ffmpeg, but it does not need one for the
 * case this feature is actually used in: adding a recording made by this app to
 * a timeline whose source was also made by this app. Both files already share
 * an encoder configuration, so the MP4 samples can be copied straight into a
 * new container with the second file's timestamps shifted by the first file's
 * duration.
 *
 * The result is one immutable source, as upstream describes: the editor and the
 * exporter both read this file, and clip regions address the appended half from
 * `sourceStartMs` onwards. Cursor telemetry from both halves is merged into one
 * sidecar so auto-zoom survives the append.
 *
 * What this deliberately refuses:
 *  - A resolution, codec or codec-configuration mismatch. Concatenating those
 *    at the sample level produces a file that decodes as garbage after the
 *    join; a clear error is better than a corrupt export.
 *  - A recording whose duration cannot be read, because the join point would
 *    be a guess.
 *
 * The sample copy assumes presentation order. Our captures are encoded with
 * `max-b-frames = 0`, so they satisfy that; an imported file that reorders
 * frames is not detected and is not the intended input. Upstream's normalize
 * step is what would have handled it.
 */
internal object RecordingImport {

    private const val TAG = "RecordingImport"
    private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024

    class Result(
        val file: File,
        val sourceStartMs: Long,
        val durationMs: Long,
        val totalDurationMs: Long,
    )

    class Failure(message: String) : Exception(message)

    /**
     * Concatenates [current] and [recording] into a new file.
     *
     * [isCancelled] is polled between samples. The caller is responsible for
     * deleting [output] when this throws.
     */
    fun combine(
        current: File,
        recording: File,
        output: File,
        isCancelled: () -> Boolean = { false },
    ): Result {
        val first = Input.open(current)
        val second = try {
            Input.open(recording)
        } catch (error: Throwable) {
            // `Input.open` releases its own extractor, but the first one is
            // ours and would otherwise leak.
            first.close()
            throw error
        }
        try {
            requireCompatible(first, second)

            if (first.durationMs <= 0L || second.durationMs <= 0L) {
                throw Failure("One of the recordings has no readable duration")
            }
            val sourceStartMs = first.durationMs
            val durationMs = second.durationMs
            val offsetUs = sourceStartMs * 1000L
            output.parentFile?.mkdirs()
            if (output.exists() && !output.delete()) {
                throw Failure("Could not replace the previous import file")
            }

            val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var started = false
            try {
                val videoTrack = muxer.addTrack(first.videoFormat)
                // Either side may carry microphone audio. One AAC configuration
                // serves both because the check above proved they agree.
                val audioFormat = first.audioFormat ?: second.audioFormat
                val audioTrack = audioFormat?.let { muxer.addTrack(it) }
                muxer.start()
                started = true

                writeInput(
                    input = first,
                    muxer = muxer,
                    videoTrack = videoTrack,
                    audioTrack = audioTrack,
                    offsetUs = 0L,
                    isCancelled = isCancelled,
                )
                writeInput(
                    input = second,
                    muxer = muxer,
                    videoTrack = videoTrack,
                    audioTrack = audioTrack,
                    offsetUs = offsetUs,
                    isCancelled = isCancelled,
                )
                muxer.stop()
                started = false
            } finally {
                if (started) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }

            writeSidecar(current, recording, output, sourceStartMs)
            return Result(output, sourceStartMs, durationMs, sourceStartMs + durationMs)
        } finally {
            first.close()
            second.close()
        }
    }

    // ------------------------------------------------------------------ //
    // Compatibility
    // ------------------------------------------------------------------ //

    private fun requireCompatible(first: Input, second: Input) {
        val a = first.videoFormat
        val b = second.videoFormat
        if (mimeOf(a) != mimeOf(b)) {
            throw Failure("Recordings use different video codecs and cannot be combined on Android")
        }
        val width = formatInt(a, MediaFormat.KEY_WIDTH)
        val height = formatInt(a, MediaFormat.KEY_HEIGHT)
        val otherWidth = formatInt(b, MediaFormat.KEY_WIDTH)
        val otherHeight = formatInt(b, MediaFormat.KEY_HEIGHT)
        if (width != otherWidth || height != otherHeight) {
            throw Failure(
                "Recordings must have the same resolution to be combined. " +
                    "This one is ${width}x$height and the new one is ${otherWidth}x$otherHeight.",
            )
        }
        if (!sameBytes(a, b, "csd-0") || !sameBytes(a, b, "csd-1")) {
            throw Failure("Recordings use different encoder settings and cannot be combined on Android")
        }

        val aAudio = first.audioFormat
        val bAudio = second.audioFormat
        if (aAudio != null && bAudio != null) {
            if (mimeOf(aAudio) != mimeOf(bAudio) ||
                formatInt(aAudio, MediaFormat.KEY_SAMPLE_RATE) !=
                formatInt(bAudio, MediaFormat.KEY_SAMPLE_RATE) ||
                formatInt(aAudio, MediaFormat.KEY_CHANNEL_COUNT) !=
                formatInt(bAudio, MediaFormat.KEY_CHANNEL_COUNT) ||
                !sameBytes(aAudio, bAudio, "csd-0")
            ) {
                throw Failure("Recordings use different audio settings and cannot be combined on Android")
            }
        }
    }

    private fun sameBytes(a: MediaFormat, b: MediaFormat, key: String): Boolean {
        val left = a.getByteBuffer(key)?.let(::bytesOf) ?: return b.getByteBuffer(key) == null
        val right = b.getByteBuffer(key)?.let(::bytesOf) ?: return false
        return left.contentEquals(right)
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        val bytes = ByteArray(copy.remaining())
        copy.get(bytes)
        return bytes
    }

    private fun mimeOf(format: MediaFormat): String? =
        format.getString(MediaFormat.KEY_MIME)

    private fun formatInt(format: MediaFormat, key: String): Int =
        if (format.containsKey(key)) format.getInteger(key) else 0

    // ------------------------------------------------------------------ //
    // Muxing
    // ------------------------------------------------------------------ //

    /**
     * Copies every sample of [input], video and audio interleaved by their
     * presentation time. [offsetUs] shifts the whole stream onto the joined
     * timeline.
     */
    private fun writeInput(
        input: Input,
        muxer: MediaMuxer,
        videoTrack: Int,
        audioTrack: Int?,
        offsetUs: Long,
        isCancelled: () -> Boolean,
    ) {
        val video = TrackReader(input.file, input.videoTrack, offsetUs)
        val audio = try {
            input.audioTrack
                ?.takeIf { audioTrack != null }
                ?.let { TrackReader(input.file, it, offsetUs) }
        } catch (error: Throwable) {
            video.release()
            throw error
        }
        try {
            val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
            val info = MediaCodec.BufferInfo()
            val lastPts = longArrayOf(-1L, -1L)

            while (true) {
                if (isCancelled()) throw Failure("Import cancelled")
                val nextVideo = video.pendingTimeUs()
                val nextAudio = audio?.pendingTimeUs() ?: Long.MAX_VALUE
                if (nextVideo == Long.MAX_VALUE && nextAudio == Long.MAX_VALUE) break

                val useAudio = audio != null && nextAudio < nextVideo
                val reader = if (useAudio) audio!! else video
                val track = if (useAudio) audioTrack!! else videoTrack
                val slot = if (useAudio) 1 else 0

                val size = reader.read(buffer)
                if (size < 0) {
                    reader.advance()
                    continue
                }
                val rawPts = reader.sampleTimeUs
                val flags = reader.sampleFlags
                reader.advance()
                if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue

                // Each half must open on a sync frame: a gap-free sample stream
                // still decodes to garbage if an appended half starts on a
                // non-keyframe.
                if (track == videoTrack && reader.firstSample &&
                    flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0
                ) {
                    throw Failure("${input.file.name} does not start on a keyframe")
                }
                reader.firstSample = false

                val pts = maxOf(rawPts, lastPts[slot] + 1L)
                info.offset = 0
                info.size = size
                info.presentationTimeUs = pts
                info.flags = flags
                muxer.writeSampleData(track, buffer, info)
                lastPts[slot] = pts
            }
        } finally {
            video.release()
            audio?.release()
        }
    }

    /**
     * One extractor positioned on one track. `muxer` is injected because the
     * readers share the output that [writeInput] owns.
     */
    private class TrackReader(file: File, trackIndex: Int, private val offsetUs: Long) {
        private val extractor = MediaExtractor().apply { setDataSource(file.path) }

        var firstSample = true

        init {
            extractor.selectTrack(trackIndex)
        }

        val sampleTimeUs: Long get() = extractor.sampleTime + offsetUs
        val sampleFlags: Int get() = extractor.sampleFlags

        fun pendingTimeUs(): Long = if (extractor.sampleTime < 0L) Long.MAX_VALUE else sampleTimeUs

        fun read(buffer: ByteBuffer): Int {
            buffer.clear()
            return extractor.readSampleData(buffer, 0)
        }

        fun advance() {
            extractor.advance()
        }

        fun release() {
            runCatching { extractor.release() }
        }
    }

    // ------------------------------------------------------------------ //
    // Input inspection
    // ------------------------------------------------------------------ //

    private class Input private constructor(
        val file: File,
        val extractor: MediaExtractor,
        val videoTrack: Int,
        val videoFormat: MediaFormat,
        val audioTrack: Int?,
        val audioFormat: MediaFormat?,
        val durationMs: Long,
    ) {
        fun close() {
            runCatching { extractor.release() }
        }

        companion object {
            fun open(file: File): Input {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    var videoTrack = -1
                    var audioTrack = -1
                    var videoFormat: MediaFormat? = null
                    var audioFormat: MediaFormat? = null
                    for (index in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(index)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (videoTrack < 0 && mime.startsWith("video/")) {
                            videoTrack = index
                            videoFormat = format
                        } else if (audioTrack < 0 && mime.startsWith("audio/")) {
                            audioTrack = index
                            audioFormat = format
                        }
                    }
                    if (videoTrack < 0 || videoFormat == null) {
                        throw Failure("${file.name} has no video track")
                    }
                    if (!videoFormat.containsKey(MediaFormat.KEY_WIDTH) ||
                        !videoFormat.containsKey(MediaFormat.KEY_HEIGHT)
                    ) {
                        throw Failure("${file.name} has no usable video format")
                    }
                    return Input(
                        file = file,
                        extractor = extractor,
                        videoTrack = videoTrack,
                        videoFormat = videoFormat,
                        audioTrack = audioTrack.takeIf { it >= 0 },
                        audioFormat = audioFormat,
                        durationMs = durationMs(file),
                    )
                } catch (error: Throwable) {
                    runCatching { extractor.release() }
                    throw error
                }
            }

            private fun durationMs(file: File): Long {
                val retriever = MediaMetadataRetriever()
                return try {
                    retriever.setDataSource(file.path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                } catch (error: Throwable) {
                    Log.w(TAG, "cannot read the duration of ${file.name}", error)
                    0L
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Cursor telemetry
    // ------------------------------------------------------------------ //

    /**
     * One sidecar for the joined file: the first half unchanged, the second
     * half shifted by [sourceStartMs]. Dimensions are identical (enforced
     * above), so normalised coordinates need no rescaling.
     */
    private fun writeSidecar(
        current: File,
        recording: File,
        output: File,
        sourceStartMs: Long,
    ) {
        val merged = JSONArray()
        val firstHalf = readSamples(current)
        for (index in 0 until firstHalf.length()) {
            val sample = firstHalf.optJSONObject(index) ?: continue
            merged.put(JSONObject(sample.toString()))
        }
        val secondHalf = readSamples(recording)
        for (index in 0 until secondHalf.length()) {
            val sample = secondHalf.optJSONObject(index) ?: continue
            merged.put(
                JSONObject(sample.toString()).apply {
                    put("timeMs", optLong("timeMs", 0L) + sourceStartMs)
                },
            )
        }
        val body = JSONObject().apply {
            put("version", 1)
            put("samples", merged)
        }
        try {
            File(output.path + ".cursor.json").writeText(body.toString())
        } catch (error: Throwable) {
            // Telemetry is an enhancement; a failed sidecar must not discard a
            // successfully joined video.
            Log.w(TAG, "cannot write the merged cursor sidecar", error)
        }
    }

    private fun readSamples(video: File): JSONArray {
        val sidecar = File(video.path + ".cursor.json")
        if (!sidecar.isFile) return JSONArray()
        return try {
            when (val parsed = JSONTokener(sidecar.readText()).nextValue()) {
                is JSONArray -> parsed
                is JSONObject -> parsed.optJSONArray("samples") ?: JSONArray()
                else -> JSONArray()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "unreadable cursor sidecar for ${video.name}", error)
            JSONArray()
        }
    }
}
