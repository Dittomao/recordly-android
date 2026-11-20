package app.recordly.android

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch

/** One microphone segment. Its timestamps use the same monotonic clock as screen frames. */
internal class MicrophoneCapture private constructor(
    val file: File,
    private val recorder: AudioRecord,
    private val codec: MediaCodec,
    private val muxer: MediaMuxer,
) {
    val finished = CountDownLatch(1)

    @Volatile
    var sampleCount = 0
        private set

    /** Kept separately because an MP4 reader may normalize a track's first timestamp. */
    @Volatile
    var firstSamplePtsUs = -1L
        private set

    @Volatile
    var failure: Throwable? = null
        private set

    @Volatile
    private var stopping = false

    @Volatile
    private var stopAtUs = Long.MAX_VALUE

    private val info = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxerStarted = false

    /** Stops the source immediately and lets the worker finish the AAC stream. */
    fun requestStop(atUs: Long = System.nanoTime() / 1000L) {
        stopAtUs = atUs
        stopping = true
        // Unblocks a read without releasing resources underneath the worker.
        runCatching { recorder.stop() }
    }

    private fun encode() {
        val pcm = ByteArray(FRAMES_PER_READ * BYTES_PER_FRAME)
        val timestamp = AudioTimestamp()
        var framesRead = 0L
        var originUs = -1L
        var endPtsUs = 0L
        try {
            while (!stopping) {
                val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (stopping) break
                    if (count < 0) throw IOException("Microphone read failed ($count)")
                    continue
                }
                val frames = count / BYTES_PER_FRAME
                if (originUs < 0L) {
                    originUs = if (
                        recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                        AudioRecord.SUCCESS
                    ) {
                        timestamp.nanoTime / 1000L - timestamp.framePosition * 1_000_000L / SAMPLE_RATE
                    } else {
                        // Some devices do not expose AudioTimestamp during startup.
                        System.nanoTime() / 1000L - frames * 1_000_000L / SAMPLE_RATE
                    }
                }
                val ptsUs = originUs + framesRead * 1_000_000L / SAMPLE_RATE
                framesRead += frames
                val stopBoundary = stopAtUs
                val usableFrames = if (stopBoundary == Long.MAX_VALUE) frames else minOf(
                    frames.toLong(),
                    (stopBoundary - ptsUs).coerceAtLeast(0L) * SAMPLE_RATE / 1_000_000L,
                ).toInt()
                if (usableFrames > 0) {
                    queuePcm(pcm, usableFrames * BYTES_PER_FRAME, ptsUs)
                    endPtsUs = ptsUs + usableFrames * 1_000_000L / SAMPLE_RATE
                }
                drainOutput()
            }

            val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
            var eosQueued = false
            var eosReceived = false
            while (!eosReceived && SystemClock.elapsedRealtime() < deadline) {
                if (!eosQueued) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        codec.queueInputBuffer(index, 0, 0, endPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }
                eosReceived = drainOutput(DEQUEUE_TIMEOUT_US)
            }
            if (!eosReceived) throw IOException("Microphone encoder did not finish")
        } catch (error: Throwable) {
            failure = error
            Log.e(TAG, "microphone segment failed", error)
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }.onFailure { failure = it }
            }
            runCatching { muxer.release() }
            finished.countDown()
            Log.i(TAG, "microphone segment finished: $sampleCount samples")
        }
    }

    private fun queuePcm(pcm: ByteArray, count: Int, ptsUs: Long) {
        val deadline = SystemClock.elapsedRealtime() + DRAIN_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: throw IOException("Missing AAC input buffer")
                input.clear()
                if (input.remaining() < count) throw IOException("AAC input buffer is too small")
                input.put(pcm, 0, count)
                codec.queueInputBuffer(index, 0, count, ptsUs, 0)
                return
            }
            drainOutput()
        }
        throw IOException("Microphone encoder stopped accepting audio")
    }

    /** Only this worker touches the muxer. Returns true after codec EOS. */
    private fun drainOutput(timeoutUs: Long = 0L): Boolean {
        while (true) {
            when (val index = codec.dequeueOutputBuffer(info, timeoutUs)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw IOException("AAC format changed during recording")
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                else -> if (index >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (info.size > 0 && buffer != null && muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            if (firstSamplePtsUs < 0L) firstSamplePtsUs = info.presentationTimeUs
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(trackIndex, buffer, info)
                            sampleCount++
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    companion object {
        private const val TAG = "MicrophoneCapture"
        private const val SAMPLE_RATE = 48_000
        private const val BYTES_PER_FRAME = 2
        private const val FRAMES_PER_READ = 1024
        private const val BIT_RATE = 128_000
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val DRAIN_TIMEOUT_MS = 3_000L

        /** Called before the capture activity backgrounds, while microphone access is available. */
        fun start(file: File): MicrophoneCapture {
            var recorder: AudioRecord? = null
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minimum <= 0) throw IOException("This device does not support microphone recording")
                recorder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minimum * 2, FRAMES_PER_READ * BYTES_PER_FRAME * 4))
                    .build()
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IOException("Could not initialize the microphone")
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
                    .apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_READ * BYTES_PER_FRAME)
                    }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                codec.start()
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw IOException("Could not start the microphone")
                }
                val capture = MicrophoneCapture(file, recorder, codec, muxer)
                Thread({ capture.encode() }, "recordly-microphone").apply { isDaemon = true }.start()
                return capture
            } catch (error: Throwable) {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { muxer?.release() }
                file.delete()
                throw error
            }
        }
    }
}
