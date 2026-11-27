package app.recordly.android

import android.content.Context
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
import kotlin.math.roundToInt

/**
 * Appends one recording to another. Compatible inputs take the lossless
 * MP4 sample-copy path; mixed formats are decoded and encoded into one track.
 *
 * The result is one immutable source, as upstream describes: the editor and the
 * exporter both read this file, and clip regions address the appended half from
 * `sourceStartMs` onwards. Cursor telemetry from both halves is merged into one
 * sidecar so auto-zoom survives the append.
 *
 * Re-encoding keeps the first clip's display size. Other aspect ratios are
 * fitted, never stretched or cropped. Both paths leave the originals intact.
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

    /** Sample order or boundaries made the otherwise compatible remux unsafe. */
    private class NeedsTranscode : Exception()

    /**
     * Concatenates [current] and [recording] into a new file.
     *
     * [isCancelled] is polled between samples. The caller is responsible for
     * deleting [output] when this throws.
     */
    fun combine(
        context: Context,
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
            if (first.durationMs <= 0L || second.durationMs <= 0L) {
                throw Failure("One of the recordings has no readable duration")
            }
            output.parentFile?.mkdirs()
            clearOutput(output)
            val remuxed = if (canRemux(first, second)) {
                try {
                    remux(first, second, output, isCancelled)
                    true
                } catch (_: NeedsTranscode) {
                    clearOutput(output)
                    false
                }
            } else {
                false
            }

            val result: Result
            val firstFit: FrameFit
            val secondFit: FrameFit
            if (remuxed) {
                result = Result(output, first.durationMs, second.durationMs, first.durationMs + second.durationMs)
                firstFit = FrameFit.IDENTITY
                secondFit = FrameFit.IDENTITY
            } else {
                val canvas = first.displaySize.even()
                val hasAudio = first.audioTrack != null || second.audioTrack != null
                val sourceStartMs = RecordingTranscoder.append(
                    context, current, recording, output, canvas.width, canvas.height, hasAudio, isCancelled,
                )
                val converted = Input.open(output)
                val totalMs = try {
                    if (converted.displaySize != canvas || (hasAudio && converted.audioTrack == null)) {
                        throw Failure("The converted recordings did not preserve the project size or audio")
                    }
                    converted.durationMs
                } finally {
                    converted.close()
                }
                if (sourceStartMs <= 0L || totalMs <= sourceStartMs) {
                    throw Failure("The converted recordings did not produce a complete timeline")
                }
                result = Result(output, sourceStartMs, totalMs - sourceStartMs, totalMs)
                firstFit = FrameFit.forCanvas(first.displaySize, canvas)
                secondFit = FrameFit.forCanvas(second.displaySize, canvas)
            }
            if (isCancelled()) throw Failure("Import cancelled")
            writeSidecar(current, recording, output, result.sourceStartMs, firstFit, secondFit)
            return result
        } finally {
            first.close()
            second.close()
        }
    }

    private fun clearOutput(output: File) {
        if (output.exists() && !output.delete()) throw Failure("Could not replace the temporary import file")
    }

    private fun remux(first: Input, second: Input, output: File, isCancelled: () -> Boolean) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            muxer.setOrientationHint(first.rotationDegrees)
            val videoTrack = muxer.addTrack(first.videoFormat)
            val audioTrack = first.audioFormat?.let { muxer.addTrack(it) }
            muxer.start()
            started = true
            writeInput(first, muxer, videoTrack, audioTrack, 0L, isCancelled)
            writeInput(second, muxer, videoTrack, audioTrack, first.durationMs * 1000L, isCancelled)
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ------------------------------------------------------------------ //
    // Compatibility
    // ------------------------------------------------------------------ //

    private fun canRemux(first: Input, second: Input): Boolean {
        val a = first.videoFormat
        val b = second.videoFormat
        // Bake rotation into the pixels for the editor's WebCodecs path.
        if (mimeOf(a) != mimeOf(b) || first.rotationDegrees != 0 || second.rotationDegrees != 0) return false
        if (VIDEO_FORMAT_KEYS.any { formatInt(a, it) != formatInt(b, it) }) return false
        if (VIDEO_CONFIG_KEYS.any { !sameBytes(a, b, it) }) return false

        val aAudio = first.audioFormat
        val bAudio = second.audioFormat
        // Use the transcoder for a missing track so silence is explicit and
        // does not shift a later clip's first audio packet to time zero.
        if (aAudio == null || bAudio == null) return aAudio == null && bAudio == null
        return mimeOf(aAudio) == mimeOf(bAudio) &&
            formatInt(aAudio, MediaFormat.KEY_SAMPLE_RATE) == formatInt(bAudio, MediaFormat.KEY_SAMPLE_RATE) &&
            formatInt(aAudio, MediaFormat.KEY_CHANNEL_COUNT) == formatInt(bAudio, MediaFormat.KEY_CHANNEL_COUNT) &&
            sameBytes(aAudio, bAudio, "csd-0")
    }

    private val VIDEO_FORMAT_KEYS = listOf(
        MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
        MediaFormat.KEY_COLOR_STANDARD, MediaFormat.KEY_COLOR_RANGE, MediaFormat.KEY_COLOR_TRANSFER,
        MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT,
    )
    private val VIDEO_CONFIG_KEYS = listOf("csd-0", "csd-1", "csd-2")

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
                if (flags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0) {
                    throw Failure("A protected recording cannot be appended")
                }

                // Each half must open on a sync frame: a gap-free sample stream
                // still decodes to garbage if an appended half starts on a
                // non-keyframe. Do not rewrite B-frame timestamps to make
                // presentation order look monotonic.
                val sync = flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                if ((track == videoTrack && reader.firstSample && !sync) ||
                    flags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0 ||
                    rawPts <= lastPts[slot]
                ) {
                    throw NeedsTranscode()
                }
                reader.firstSample = false

                info.offset = 0
                info.size = size
                info.presentationTimeUs = rawPts
                info.flags = if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(track, buffer, info)
                lastPts[slot] = rawPts
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
        val rotationDegrees: Int,
    ) {
        val displaySize: FrameSize
            get() {
                val width = formatInt(videoFormat, MediaFormat.KEY_WIDTH)
                val height = formatInt(videoFormat, MediaFormat.KEY_HEIGHT)
                val sarX = formatInt(videoFormat, MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH).coerceAtLeast(1)
                val sarY = formatInt(videoFormat, MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT).coerceAtLeast(1)
                val displayWidth = (width * sarX.toDouble() / sarY).roundToInt().coerceAtLeast(1)
                return if (rotationDegrees == 90 || rotationDegrees == 270) FrameSize(height, displayWidth)
                else FrameSize(displayWidth, height)
            }

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
                    if (formatInt(videoFormat, MediaFormat.KEY_WIDTH) <= 0 ||
                        formatInt(videoFormat, MediaFormat.KEY_HEIGHT) <= 0
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
                        rotationDegrees = formatInt(videoFormat, MediaFormat.KEY_ROTATION),
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

    private data class FrameSize(val width: Int, val height: Int) {
        fun even() = FrameSize((width and -2).coerceAtLeast(2), (height and -2).coerceAtLeast(2))
    }

    /** Same centered fit as the presentation effect; coordinates are normalized. */
    private data class FrameFit(val x: Double, val y: Double) {
        companion object {
            val IDENTITY = FrameFit(1.0, 1.0)

            fun forCanvas(source: FrameSize, canvas: FrameSize): FrameFit {
                val scale = minOf(canvas.width.toDouble() / source.width, canvas.height.toDouble() / source.height)
                return FrameFit(source.width * scale / canvas.width, source.height * scale / canvas.height)
            }
        }

        fun apply(sample: JSONObject) {
            remap(sample, "cx", x)
            remap(sample, "cy", y)
        }

        private fun remap(sample: JSONObject, key: String, size: Double) {
            if (size == 1.0) return
            val coordinate = sample.optDouble(key, Double.NaN)
            if (coordinate.isFinite()) sample.put(key, (1.0 - size) / 2.0 + coordinate * size)
        }
    }

    // ------------------------------------------------------------------ //
    // Cursor telemetry
    // ------------------------------------------------------------------ //

    /**
     * One sidecar for the joined file: fit coordinates to the output canvas
     * and shift the second half by the actual join time.
     */
    private fun writeSidecar(
        current: File,
        recording: File,
        output: File,
        sourceStartMs: Long,
        firstFit: FrameFit,
        secondFit: FrameFit,
    ) {
        val merged = JSONArray()
        fun appendSamples(file: File, offsetMs: Long, fit: FrameFit) {
            val samples = readSamples(file)
            for (index in 0 until samples.length()) {
                val sample = samples.optJSONObject(index) ?: continue
                if (offsetMs != 0L) sample.put("timeMs", sample.optLong("timeMs", 0L) + offsetMs)
                fit.apply(sample)
                merged.put(sample)
            }
        }
        appendSamples(current, 0L, firstFit)
        appendSamples(recording, sourceStartMs, secondFit)
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
