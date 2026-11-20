package app.recordly.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Screen recording: MediaProjection → VirtualDisplay → MediaCodec(H.264) → MP4.
 *
 * Three Android rules shape this class, and each one costs a rewrite if broken:
 *
 *  1. `startForeground` must run **before** `getMediaProjection`. On API 34+ the
 *     consent token is bound to the foreground service; obtaining the projection
 *     first yields a projection that dies the moment the activity stops.
 *  2. Only **one** `VirtualDisplay` may be created per projection (API 34+). So
 *     pausing cannot tear the display down and recreate it; it detaches the
 *     encoder surface (`setSurface(null)`) and leaves the display alive.
 *  3. Because the display survives, every pause/resume cycle needs a **new**
 *     encoder: a MediaCodec cannot be restarted, and an encoder's timeline
 *     cannot skip a span. Each cycle is one "segment" — its own codec, surface
 *     and muxer — and the segments are joined on stop.
 *
 * A segment is also the unit of timestamp bookkeeping. Every frame's
 * presentation time comes from the producer in the monotonic clock, and the
 * joined timeline drops the paused spans, which is exactly what the telemetry
 * clock does ([TouchTelemetry.pauseClock]). That is what keeps a tap landing on
 * the frame it was made over, even across a pause.
 *
 * Each segment can also carry an AAC microphone stream. Both tracks are joined
 * against the first screen frame's clock, with the same paused spans removed.
 */
class ScreenCaptureService : Service() {

    // --- projection ------------------------------------------------------ //

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Called when we stop it ourselves and when the user revokes capture
            // from the system UI. Only the latter is news: the take has already
            // ended, so it is recovered rather than stopped.
            if (stopping) return
            Log.i(TAG, "projection revoked by the system")
            CaptureSession.onCaptureInterrupted("Screen capture was stopped by the system.")
        }
    }

    // --- segments -------------------------------------------------------- //

    private val segments = ArrayList<Segment>()

    @Volatile
    private var recording = false

    @Volatile
    private var paused = false

    @Volatile
    private var stopping = false

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    private var microphoneEnabled = false
    private var microphoneUnavailableNotified = false

    /** Where the joined timeline starts: the first frame's presentation time. */
    @Volatile
    private var timelineBasePtsUs = -1L

    private val originAligned = AtomicBoolean(false)

    private val finalizer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "recordly-capture").apply { isDaemon = true }
    }

    /**
     * Serialises everything that touches the encoder, muxer and projection.
     *
     * Those calls arrive from three places with no ordering between them: the
     * main thread (`ACTION_START`), the finalizer thread (`ACTION_STOP`,
     * notification pause) and the bridge's worker threads (`pause()`/`resume()`
     * straight from the renderer). A pause can legitimately arrive a few
     * milliseconds after start — the warm-start path does exactly that — so the
     * lock is not defensive, it is the ordering.
     */
    private val captureLock = Any()

    // ------------------------------------------------------------------ //
    // Lifecycle
    // ------------------------------------------------------------------ //

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)

            /** Finalize now. Reached only from [CaptureSession.stopAsync]. */
            ACTION_STOP -> handleStopAction()

            /**
             * The notification's Stop is a request to the renderer, not a
             * command to this service: the page owns the end of a take.
             */
            ACTION_STOP_FROM_NOTIFICATION -> CaptureSession.onStopFromTray()

            // A pause waits for the encoder to flush EOS, which can take a few
            // seconds; the main thread is not where that belongs.
            ACTION_TOGGLE_PAUSE -> finalizer.execute {
                if (paused) CaptureSession.resume() else CaptureSession.pause()
            }

            else -> Log.w(TAG, "unknown action ${intent?.action}")
        }
        // A restart would try to reuse a dead projection token; the recording is
        // unrecoverable once the process is gone, so there is nothing to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (recording) {
            // The projection dies with this service, so finish the file now on
            // a background thread rather than lose every segment on disk.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                finalizer.execute { runCatching { stopInternal() } }
            } else {
                runCatching { stopInternal() }
            }
        } else {
            teardown()
        }
        finalizer.shutdown()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) = synchronized(captureLock) {
        startCaptureLocked(intent)
    }

    private fun startCaptureLocked(intent: Intent) {
        if (recording || stopping) {
            CaptureSession.onCaptureFailed("A recording is already in progress")
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            CaptureSession.onCaptureFailed("The screen capture permission was lost")
            stopSelf()
            return
        }

        microphoneEnabled = intent.getBooleanExtra(EXTRA_MICROPHONE, false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        microphoneUnavailableNotified = false
        timelineBasePtsUs = -1L
        originAligned.set(false)
        // Android requires the projection to be tied to a live foreground
        // service. If the notification cannot start, continuing would leave a
        // recording with no reachable stop control (and no projection on 14+).
        if (!showNotification(starting = true)) {
            CaptureSession.onCaptureFailed("Could not start the recording service")
            stopSelf()
            return
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val obtained = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (error: Throwable) {
            Log.e(TAG, "getMediaProjection failed", error)
            null
        }
        if (obtained == null) {
            CaptureSession.onCaptureFailed("Could not start screen capture")
            stopSelf()
            return
        }
        projection = obtained
        obtained.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = displayMetrics()
        width = metrics.widthPixels and 1.inv()
        height = metrics.heightPixels and 1.inv()
        densityDpi = metrics.densityDpi

        // Segment files are scratch; a failed run must not accumulate them.
        segmentDir.deleteRecursively()
        segmentDir.mkdirs()

        val display = try {
            obtained.createVirtualDisplay(
                "recordly-capture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                Handler(Looper.getMainLooper()),
            )
        } catch (error: Throwable) {
            Log.e(TAG, "createVirtualDisplay failed", error)
            null
        }
        if (display == null) {
            runCatching { obtained.stop() }
            projection = null
            CaptureSession.onCaptureFailed("Could not mirror the screen")
            stopSelf()
            return
        }
        virtualDisplay = display

        // Telemetry and capture share this boundary; the origin is corrected to
        // the first encoded frame's timestamp once the encoder reports it.
        TouchTelemetry.startSession()
        TouchTelemetry.setScreenSize(width, height)

        val first = startSegment(pauseOffsetMs = 0L)
        if (first == null) {
            TouchTelemetry.stopSession()
            teardown()
            CaptureSession.onCaptureFailed("Could not start the video encoder")
            stopSelf()
            return
        }

        recording = true
        paused = false
        stopping = false
        showNotification()
        CaptureSession.onCaptureStarted(microphoneEnabled)
        Log.i(TAG, "capture started ${width}x$height @ $densityDpi (microphone=$microphoneEnabled)")
    }

    /**
     * The display's real size, not the app's window.
     *
     * `resources.displayMetrics` follows the window on API 30+ (window size
     * classes), which would crop the recording to our own activity. The display
     * is what is being mirrored.
     */
    private fun displayMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        val bounds = try {
            val wm = getSystemService(WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm?.maximumWindowMetrics?.bounds else null
        } catch (error: Throwable) {
            Log.w(TAG, "maximumWindowMetrics unavailable", error)
            null
        }
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            dm.widthPixels = bounds.width()
            dm.heightPixels = bounds.height()
            dm.densityDpi = resources.displayMetrics.densityDpi
            return dm
        }
        @Suppress("DEPRECATION")
        val display: Display? = getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        display?.getRealMetrics(dm)
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm.widthPixels = resources.displayMetrics.widthPixels
            dm.heightPixels = resources.displayMetrics.heightPixels
            dm.densityDpi = resources.displayMetrics.densityDpi
        }
        return dm
    }

    // ------------------------------------------------------------------ //
    // Pause / resume
    // ------------------------------------------------------------------ //

    private fun pauseInternal(): Boolean = synchronized(captureLock) {
        pauseInternalLocked()
    }

    private fun pauseInternalLocked(): Boolean {
        if (!recording || paused) return false
        paused = true
        // Same instant for the telemetry clock and for the next segment's
        // offset, so the two timelines stay in lockstep.
        val boundary = SystemClock.uptimeMillis()
        TouchTelemetry.pauseClock(boundary)
        endCurrentSegment()
        // Detaching after the EOS: frames already in the pipeline are encoded,
        // nothing new enters.
        runCatching { virtualDisplay?.surface = null }
        showNotification()
        Log.i(TAG, "capture paused")
        return true
    }

    private fun resumeInternal(): String? = synchronized(captureLock) {
        resumeInternalLocked()
    }

    private fun resumeInternalLocked(): String? {
        if (!recording || !paused) return null
        val offset = TouchTelemetry.resumeClock(SystemClock.uptimeMillis())
        val segment = startSegment(offset)
        if (segment == null) {
            // Stay paused rather than claim a resume that produces no video.
            TouchTelemetry.pauseClock()
            return "Could not restart the video encoder"
        }
        paused = false
        showNotification()
        Log.i(TAG, "capture resumed (segment ${segment.index})")
        return null
    }

    // ------------------------------------------------------------------ //
    // Stop / finalize
    // ------------------------------------------------------------------ //

    private fun handleStopAction() {
        if (stopping) return
        stopping = true
        showNotification(finishing = true)
        finalizer.execute { stopInternal() }
    }

    /**
     * Closes the encoder, joins the segments and writes the cursor sidecar.
     * Runs off the main thread; always ends by reporting to [CaptureSession].
     */
    private fun stopInternal() = synchronized(captureLock) {
        stopInternalLocked()
    }

    private fun stopInternalLocked() {
        stopping = true
        recording = false
        paused = false
        TouchTelemetry.stopSession()
        endCurrentSegment()
        teardown()

        val target = outputFile()
        val written = try {
            joinSegments(target)
        } catch (error: Throwable) {
            Log.e(TAG, "could not join recording segments", error)
            false
        }

        if (!written) {
            target.delete()
            CaptureSession.onCaptureStopped(null)
            stopSelf()
            return
        }

        writeSidecar(target)
        Log.i(TAG, "recording written: ${target.path} (${target.length()} bytes)")
        CaptureSession.onCaptureStopped(target)
        notifySaved(target)
        stopSelf()
    }

    /** Releases the projection and display; safe to call twice. */
    private fun teardown() = synchronized(captureLock) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection?.let { active ->
            runCatching { active.unregisterCallback(projectionCallback) }
            runCatching { active.stop() }
        }
        projection = null
    }

    private fun endCurrentSegment() {
        val segment = synchronized(segments) { segments.lastOrNull() } ?: return
        segment.endRequested = true
        // The drain thread signals EOS and flushes the muxer. Ten seconds is
        // several frame times past generous; a slow device should still finish,
        // and a wedged segment is handled below without throwing.
        //
        // Keep the microphone running until the video has flushed: it used to
        // stop at the same instant as the EOS request, while the encoder still
        // emitted queued frames, leaving the video 100-300 ms longer than the
        // audio. The join drops audio past the final frame.
        if (!segment.finished.await(SEGMENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)) {
            Log.w(TAG, "segment ${segment.index} did not drain in time")
        }
        segment.microphone?.let { microphone ->
            microphone.requestStop()
            if (!microphone.finished.await(SEGMENT_DRAIN_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "microphone segment ${segment.index} did not drain in time")
            }
            if (microphone.failure != null) {
                // A cleanup/EOS failure does not invalidate the AAC samples that
                // were already muxed; validate the file instead of disabling
                // the microphone for the rest of the take.
                Log.w(TAG, "microphone segment ${segment.index} did not finish cleanly", microphone.failure)
            }
            if (!microphone.hasSamples()) notifyMicrophoneUnavailable()
        }
    }

    private fun outputFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = AppHost.recordingsDir(this)
        var candidate = File(dir, "$RECORDING_PREFIX$stamp.mp4")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$RECORDING_PREFIX$stamp-$index.mp4")
            index++
        }
        return candidate
    }

    /**
     * Copies encoded video and audio into one MP4 without re-encoding. Always
     * rebases the first screen frame to zero, including a take with one segment.
     */
    private fun joinSegments(target: File): Boolean {
        val (usable, stalled) = synchronized(segments) {
            segments.filter { it.finished.count == 0L && it.sampleCount > 0 } to
                segments.count { it.sampleCount > 0 && it.finished.count != 0L }
        }
        if (usable.isEmpty()) {
            Log.w(TAG, "no encoded samples; nothing to write")
            return false
        }
        target.parentFile?.mkdirs()

        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val buffer = java.nio.ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var writtenSamples = 0
        try {
            val videoTrack = muxer.addTrack(trackFormat(usable.first().file, "video/"))
            val audioSource = usable.firstNotNullOfOrNull { segment ->
                segment.microphone?.takeIf { it.hasSamples() }
            }
            val audioTrack = audioSource?.let { muxer.addTrack(trackFormat(it.file, "audio/")) }
            muxer.start()
            muxerStarted = true
            val lastOutUs = LongArray(if (audioTrack == null) 1 else 2) { -1L }
            // MediaMuxer has no per-sample duration for the final video sample:
            // it repeats the previous sample interval. After a long static
            // stretch that made the container ~0.8 s longer than the audio and
            // the real content. Keeping the last encoded sample here lets us
            // append one short duplicate at a normal frame interval, bounding
            // the final duration without re-encoding.
            var lastVideoSample: ByteArray? = null
            var lastVideoPtsUs = -1L
            var lastVideoFlags = 0
            if (usable.first().firstVideoPtsUs < 0L) return false
            timelineBasePtsUs = usable.first().firstVideoPtsUs - usable.first().pauseOffsetMs * 1000L
            for (segment in usable) {
                val readers = ArrayList<EncodedTrack>()
                try {
                    readers.add(EncodedTrack(
                        segment.file, "video/", videoTrack, segment.firstVideoPtsUs, segment.pauseOffsetMs,
                    ))
                    if (audioTrack != null) {
                        segment.microphone?.takeIf { it.hasSamples() }?.let { microphone ->
                            readers.add(EncodedTrack(
                                microphone.file, "audio/", audioTrack,
                                microphone.firstSamplePtsUs, segment.pauseOffsetMs,
                            ))
                        }
                    }
                    // Interleave the tracks by their shared presentation clock.
                    // selectTrack already selects the first sample; never advance
                    // before writing it (short segments can contain one frame).
                    val videoReader = readers.firstOrNull { it.outputTrack == videoTrack }
                    while (true) {
                        val reader = readers.filter { it.extractor.sampleTime >= 0L }
                            .minByOrNull { it.joinedTimeUs() } ?: break
                        val ptsUs = reader.joinedTimeUs()
                        if (ptsUs < 0L) {
                            // Microphone startup may precede the first screen
                            // frame. Drop its preroll instead of shifting video.
                            reader.extractor.advance()
                            continue
                        }
                        buffer.clear()
                        val size = reader.extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val track = reader.outputTrack
                        val joinedUs = max(ptsUs, lastOutUs[track] + 1L)
                        // The microphone runs through the video drain so the
                        // audio covers the final frame; anything past it is
                        // dropped here instead of extending the container.
                        val videoExhausted =
                            videoReader == null || videoReader.extractor.sampleTime < 0L
                        if (track == audioTrack && videoExhausted && lastVideoPtsUs >= 0L &&
                            joinedUs > lastVideoPtsUs + AUDIO_TAIL_TOLERANCE_US
                        ) {
                            reader.extractor.advance()
                            continue
                        }
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = joinedUs
                        info.flags = reader.extractor.sampleFlags
                        muxer.writeSampleData(track, buffer, info)
                        if (track == videoTrack) {
                            writtenSamples++
                            val duplicate = buffer.duplicate()
                            duplicate.position(info.offset)
                            duplicate.limit(info.offset + size)
                            lastVideoSample = ByteArray(size).also(duplicate::get)
                            lastVideoPtsUs = joinedUs
                            lastVideoFlags = info.flags
                        }
                        lastOutUs[track] = joinedUs
                        reader.extractor.advance()
                    }
                } finally {
                    readers.forEach { runCatching { it.extractor.release() } }
                }
            }
            // Bound the final frame's duration to one frame interval. The
            // duplicate adds at most ~33 ms to the file; without it the player
            // sees the last static frame held for the whole tail (measured
            // 0.86 s), and the editor treats that as extra video with no audio.
            val tail = lastVideoSample
            if (tail != null && lastVideoPtsUs >= 0L) {
                val intervalUs = 1_000_000L / TARGET_FRAME_RATE
                val tailBuffer = java.nio.ByteBuffer.wrap(tail)
                info.offset = 0
                info.size = tail.size
                info.presentationTimeUs = lastVideoPtsUs + intervalUs
                info.flags = lastVideoFlags
                muxer.writeSampleData(videoTrack, tailBuffer, info)
                writtenSamples++
            }
            // A failed stop can leave an MP4 without its index. Propagate that
            // failure so it cannot be reported as a saved recording.
            muxer.stop()
            muxerStarted = false
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
        if (writtenSamples == 0) return false
        if (stalled > 0) {
            // Do not delete the only copy of the frames that missed the deadline.
            // The scratch directory is process-local and the next take clears
            // it, but it survives long enough to be pulled for diagnosis.
            Log.w(
                TAG,
                "$stalled segment(s) missed the drain deadline; keeping scratch at ${segmentDir.path}",
            )
        }
        cleanupSegments(keepFiles = stalled > 0)
        return target.length() > 0L
    }

    private fun MicrophoneCapture.hasSamples(): Boolean {
        if (sampleCount <= 0 || !file.isFile || file.length() <= 0L) return false
        // A cleanup or EOS failure can leave a valid AAC track behind. Check the
        // file instead of trusting `failure`, which also records muxer.release
        // problems that do not affect the samples.
        return runCatching { trackFormat(file, "audio/") }.isSuccess
    }

    private fun trackFormat(file: File, mimePrefix: String): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true }
        } finally {
            extractor.release()
        }
    }

    private inner class EncodedTrack(
        file: File,
        mimePrefix: String,
        val outputTrack: Int,
        private val captureFirstPtsUs: Long,
        private val pauseOffsetMs: Long,
    ) {
        val extractor = MediaExtractor()
        private val fileFirstPtsUs: Long

        init {
            try {
                extractor.setDataSource(file.path)
                val track = (0 until extractor.trackCount).first { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith(mimePrefix) == true
                }
                extractor.selectTrack(track)
                fileFirstPtsUs = extractor.sampleTime
            } catch (error: Throwable) {
                extractor.release()
                throw error
            }
        }

        fun joinedTimeUs(): Long = extractor.sampleTime - fileFirstPtsUs +
            captureFirstPtsUs - timelineBasePtsUs - pauseOffsetMs * 1000L
    }

    private fun cleanupSegments(keepFiles: Boolean = false) {
        synchronized(segments) { segments.clear() }
        if (!keepFiles) runCatching { segmentDir.deleteRecursively() }
    }

    /**
     * The cursor sidecar, beside the video at `<video>.cursor.json`.
     *
     * This is the same shape the bridge writes (`{version, samples}`) and the
     * same place `getCursorTelemetry` looks. It is written here rather than by
     * the renderer because the samples only exist natively, and because the
     * editor asks for them before anything else has had a chance to save.
     */
    private fun writeSidecar(video: File) {
        val samples = TouchTelemetry.samples()
        val body = JSONObject().apply {
            put("version", 1)
            put("samples", samples)
        }
        try {
            File(video.path + ".cursor.json").writeText(body.toString())
            Log.i(TAG, "cursor sidecar: ${samples.length()} samples")
        } catch (error: Throwable) {
            Log.w(TAG, "could not write cursor sidecar", error)
        }
    }

    // ------------------------------------------------------------------ //
    // Encoding
    // ------------------------------------------------------------------ //

    private inner class Segment(val index: Int, val pauseOffsetMs: Long) {
        val file = File(segmentDir, "segment-$index.mp4")

        lateinit var codec: MediaCodec
        lateinit var surface: Surface
        lateinit var muxer: MediaMuxer

        var trackIndex = -1
        var muxerStarted = false

        @Volatile
        var sampleCount = 0

        @Volatile
        var firstVideoPtsUs = -1L

        var microphone: MicrophoneCapture? = null

        @Volatile
        var endRequested = false

        val finished = CountDownLatch(1)
    }

    private fun encoderFormat(): MediaFormat = MediaFormat.createVideoFormat(
        MediaFormat.MIMETYPE_VIDEO_AVC,
        width,
        height,
    ).apply {
        setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate())
        setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FRAME_RATE)
        // A second between keyframes: the editor seeks while trimming, and each
        // pause boundary starts a new file so it starts on one anyway.
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }

    /**
     * Scaled from upstream's desktop figure by pixel count.
     *
     * `BITRATE_BASE` is 18 Mbps for a desktop 1920×1080 at 60 fps
     * (`recorderCaptureOptions.ts`). This keeps the same bits-per-pixel, clamps
     * to what a phone encoder will accept, and never goes below the quality
     * floor that makes text legible after the editor re-encodes on export.
     */
    private fun bitrate(): Int {
        val pixels = width.toLong() * height.toLong()
        val scaled = (18_000_000L * pixels / (1920L * 1080L)).coerceIn(8_000_000L, 24_000_000L)
        return scaled.toInt()
    }

    private fun startSegment(pauseOffsetMs: Long): Segment? {
        val index = synchronized(segments) { segments.size }
        val segment = Segment(index, pauseOffsetMs)
        segment.file.delete()
        segment.file.parentFile?.mkdirs()

        return try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            val muxer = MediaMuxer(segment.file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            segment.codec = codec
            segment.surface = surface
            segment.muxer = muxer
            codec.start()
            if (microphoneEnabled) {
                segment.microphone = try {
                    MicrophoneCapture.start(File(segmentDir, "microphone-$index.m4a"))
                } catch (error: Throwable) {
                    Log.w(TAG, "could not start the microphone", error)
                    microphoneEnabled = false
                    notifyMicrophoneUnavailable()
                    null
                }
            }
            synchronized(segments) { segments.add(segment) }

            Thread({ drain(segment) }, "recordly-encoder-$index").apply {
                isDaemon = true
            }.start()

            // If the display refuses the surface, the segment still exists and
            // is closed by the pause/stop path; failing the whole start here
            // would leak both.
            runCatching { virtualDisplay?.surface = surface }
                .onFailure { Log.e(TAG, "cannot attach the encoder surface", it) }
            segment
        } catch (error: Throwable) {
            Log.e(TAG, "cannot start encoder segment $index", error)
            segment.microphone?.requestStop()
            runCatching { segment.codec.stop() }
            runCatching { segment.codec.release() }
            runCatching { segment.surface.release() }
            runCatching { segment.muxer.release() }
            null
        }
    }

    /**
     * Drains one encoder until EOS, writing samples straight into its muxer.
     *
     * Runs on its own thread so a pause can wait for a clean finish: the caller
     * sets [Segment.endRequested] and joins, which guarantees the file is
     * complete (moov written) before anything reads it.
     */
    private fun drain(segment: Segment) {
        val info = MediaCodec.BufferInfo()
        var eosSignalled = false
        var eosDeadline = 0L
        try {
            while (true) {
                if (!eosSignalled && segment.endRequested) {
                    // Required for surface input: queuing an empty buffer into
                    // an input surface is not possible.
                    runCatching { segment.codec.signalEndOfInputStream() }
                    eosSignalled = true
                    eosDeadline = SystemClock.uptimeMillis() + SEGMENT_DRAIN_TIMEOUT_MS
                }
                if (eosSignalled && SystemClock.uptimeMillis() > eosDeadline) {
                    Log.w(TAG, "segment ${segment.index}: no EOS from the encoder")
                    break
                }
                when (val status = segment.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        segment.trackIndex = segment.muxer.addTrack(segment.codec.outputFormat)
                        segment.muxer.start()
                        segment.muxerStarted = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (status >= 0) {
                        val buffer = segment.codec.getOutputBuffer(status)
                        if (info.size > 0 && buffer != null && segment.muxerStarted &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            if (segment.firstVideoPtsUs < 0L) segment.firstVideoPtsUs = info.presentationTimeUs
                            alignTimeline(segment, info.presentationTimeUs)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            segment.muxer.writeSampleData(segment.trackIndex, buffer, info)
                            segment.sampleCount += 1
                        }
                        segment.codec.releaseOutputBuffer(status, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "encoder segment ${segment.index} failed", error)
        } finally {
            runCatching { segment.codec.stop() }
            runCatching { segment.codec.release() }
            runCatching { segment.surface.release() }
            runCatching { if (segment.muxerStarted) segment.muxer.stop() }
            runCatching { segment.muxer.release() }
            segment.finished.countDown()
        }
    }

    /**
     * Points the telemetry clock at the instant the first frame was captured.
     *
     * Frame timestamps come from the producer in the monotonic clock, in µs. The
     * frame we are holding was captured `nowNanos/1000 - pts` ago, so its wall
     * time is that much before now — no assumption about which clock the
     * timestamp is on, only that both advance at the same rate.
     */
    private fun alignTimeline(segment: Segment, ptsUs: Long) {
        if (!originAligned.compareAndSet(false, true)) return
        val capturedAtUptime = SystemClock.uptimeMillis() - (System.nanoTime() / 1000 - ptsUs) / 1000
        TouchTelemetry.alignOriginTo(capturedAtUptime - segment.pauseOffsetMs)
        Log.i(TAG, "timeline aligned to first frame at uptime $capturedAtUptime")
    }

    // ------------------------------------------------------------------ //
    // Notification
    // ------------------------------------------------------------------ //

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = "Shown while Recordly is recording your screen"
                        setShowBadge(false)
                        enableVibration(false)
                    },
            )
        }
        if (manager.getNotificationChannel(SAVED_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    SAVED_CHANNEL_ID,
                    "Saved recordings",
                    // The one notification worth a sound: it arrives when the
                    // user is in another app and is how they learn the take is
                    // over and the editor is ready.
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Tells you when a recording has finished"
                    setShowBadge(true)
                },
            )
        }
    }

    /**
     * Capture must be stoppable after the user leaves Recordly for the app being
     * recorded, which makes the notification the only reachable control. Pause
     * acts on the service; stop is routed through [CaptureSession], so the
     * renderer still ends the session and opens the editor.
     */
    private fun showNotification(starting: Boolean = false, finishing: Boolean = false): Boolean {
        val text = when {
            finishing -> "Finishing the recording…"
            starting -> "Starting…"
            paused -> "Paused"
            else -> "Recording your screen"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("Recordly")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())

        if (!starting && !finishing) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) "Resume" else "Pause",
                    serviceIntent(ACTION_TOGGLE_PAUSE, 1),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    serviceIntent(ACTION_STOP_FROM_NOTIFICATION, 2),
                ).build(),
            )
        }

        val notification = builder.build()
        return try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    (if (microphoneEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0),
            )
            true
        } catch (error: Throwable) {
            Log.e(TAG, "startForeground failed", error)
            false
        }
    }

    private fun notifyMicrophoneUnavailable() {
        if (microphoneUnavailableNotified) return
        microphoneUnavailableNotified = true
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                this, "Microphone unavailable. Continuing screen recording without audio.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * The way back into the app.
     *
     * A recording ends while the user is in whatever they were recording, and
     * the foreground notification that was their only control disappears with
     * the service. Without this they are left in another app with no signal that
     * anything happened. Tapping it opens Recordly, which by then has already
     * been reloaded into the editor with this recording loaded.
     */
    private fun notifySaved(file: File) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val builder = Notification.Builder(this, SAVED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle("Recording saved")
            .setContentText(file.name)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        runCatching { manager.notify(SAVED_NOTIFICATION_ID, builder.build()) }
            .onFailure { Log.w(TAG, "cannot post the saved notification", it) }
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, ScreenCaptureService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // ------------------------------------------------------------------ //
    // Shared entry points
    // ------------------------------------------------------------------ //

    private val segmentDir: File
        get() = File(filesDir, "capture")

    companion object {
        private const val TAG = "ScreenCapture"

        const val ACTION_START = "app.recordly.android.action.START_CAPTURE"
        const val ACTION_STOP = "app.recordly.android.action.STOP_CAPTURE"
        const val ACTION_STOP_FROM_NOTIFICATION = "app.recordly.android.action.STOP_FROM_NOTIFICATION"
        const val ACTION_TOGGLE_PAUSE = "app.recordly.android.action.TOGGLE_PAUSE"

        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_MICROPHONE = "capturesMicrophone"

        private const val CHANNEL_ID = "recordly-capture"
        private const val SAVED_CHANNEL_ID = "recordly-saved"
        private const val NOTIFICATION_ID = 0x5245
        private const val SAVED_NOTIFICATION_ID = 0x5246

        private const val TARGET_FRAME_RATE = 60
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val SEGMENT_DRAIN_TIMEOUT_S = 10L
        private const val SEGMENT_DRAIN_TIMEOUT_MS = 3_000L
        private const val AUDIO_TAIL_TOLERANCE_US = 40_000L
        private const val MAX_SAMPLE_BYTES = 8 * 1024 * 1024

        /** Upstream names recordings `recording-<stamp>`; exports rely on it. */
        const val RECORDING_PREFIX = "recording-"

        /**
         * `@Volatile` because the bridge's worker threads read it: without the
         * happens-before edge a resume issued milliseconds after the service
         * started could legally observe `null` and report "the capture service
         * is not running" while the service was encoding.
         */
        @Volatile
        private var instance: ScreenCaptureService? = null

        /** Starts capture with a freshly granted projection token. */
        fun begin(context: Context, resultCode: Int, data: Intent, capturesMicrophone: Boolean = false) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_MICROPHONE, capturesMicrophone)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Update notification state without starting a second capture. */
        fun updateNotification(context: Context) {
            instance?.showNotification()
        }

        /**
         * Asks the live service to stop. The service keeps running until the
         * file is written, so this only kicks off finalization; the result
         * arrives at [CaptureSession.onCaptureStopped].
         */
        fun requestStop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
                .onFailure {
                    // Background service start refused: the service is already
                    // foregrounded, so this happens only if it died underneath.
                    Log.w(TAG, "could not reach the capture service", it)
                    CaptureSession.onCaptureStopped(null)
                }
        }

        fun pauseCapture(): Boolean = instance?.pauseInternal() ?: false

        fun resumeCapture(): String? {
            val service = instance ?: return "The capture service is not running"
            // `null` from resumeInternal means success; an Elvis expression
            // would mistake that success for a missing service.
            return service.resumeInternal()
        }

        fun isRecording(): Boolean = instance?.recording == true
    }
}
