package app.recordly.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong

/** The slow path: one encoder produces a continuous, compatible video track. */
@OptIn(UnstableApi::class)
internal object RecordingTranscoder {
    private const val TAG = "RecordingTranscoder"
    private const val CANCEL_CHECK_MS = 100L

    /** Returns the actual start of [appended] on the encoded timeline, in ms. */
    fun append(
        context: Context,
        current: File,
        appended: File,
        output: File,
        width: Int,
        height: Int,
        hasAudio: Boolean,
        isCancelled: () -> Boolean,
    ): Long {
        val items = listOf(current, appended).mapIndexed { index, file ->
            val fit = Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT)
            val media = MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(index.toString()).build()
            EditedMediaItem.Builder(media)
                .setEffects(Effects(emptyList(), listOf(fit)))
                .build()
        }
        // Including audio on the sequence fills missing parts with silence.
        // Media3 also resamples changes in ordinary mono/stereo input formats.
        val sequence = if (hasAudio) EditedMediaItemSequence.withAudioAndVideoFrom(items)
        else EditedMediaItemSequence.withVideoFrom(items)
        val composition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val thread = HandlerThread("recordly-import").apply { start() }
        val handler = Handler(thread.looper)
        val done = CompletableFuture<ExportResult>()
        val firstDurationUs = AtomicLong(0L)
        handler.post {
            if (isCancelled()) {
                done.completeExceptionally(RecordingImport.Failure("Import cancelled"))
                return@post
            }
            try {
                val loaders = ExoPlayerAssetLoader.Factory(
                    context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT,
                )
                val transformer = Transformer.Builder(context)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setPortraitEncodingEnabled(true)
                    .setAssetLoaderFactory { item, looper, listener, settings ->
                        // Observe the duration at the same seam Media3 uses
                        // to place the second clip, without re-probing it.
                        val timed = object : AssetLoader.Listener by listener {
                            override fun onDurationUs(durationUs: Long) {
                                if (item.mediaItem.mediaId == "0") firstDurationUs.set(durationUs)
                                listener.onDurationUs(durationUs)
                            }
                        }
                        loaders.createAssetLoader(item, looper, timed, settings)
                    }
                    // Do not quietly reduce the current project's resolution.
                    .setEncoderFactory(DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            done.completeExceptionally(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, output.path)
                handler.post(object : Runnable {
                    override fun run() {
                        if (done.isDone) return
                        if (isCancelled()) {
                            runCatching { transformer.cancel() }
                                .onFailure { Log.w(TAG, "Could not cancel mixed-format append cleanly", it) }
                            done.completeExceptionally(RecordingImport.Failure("Import cancelled"))
                        } else {
                            handler.postDelayed(this, CANCEL_CHECK_MS)
                        }
                    }
                })
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }
        val result = try {
            done.get()
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is RecordingImport.Failure) throw cause
            Log.w(TAG, "Could not transcode recordings into ${width}x$height H.264", cause)
            throw RecordingImport.Failure(
                "Android could not convert these recordings at ${width}×$height. " +
                    "The device must support decoding both clips and encoding the project size.",
            )
        } finally {
            thread.quitSafely()
        }
        if (isCancelled()) throw RecordingImport.Failure("Import cancelled")
        val durationUs = firstDurationUs.get()
        if (durationUs <= 0L || result.videoFrameCount <= 0) {
            throw RecordingImport.Failure("The converted recordings have no readable join point")
        }
        return (durationUs + 500L) / 1000L
    }
}
