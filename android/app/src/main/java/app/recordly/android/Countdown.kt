package app.recordly.android

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import kotlin.math.ceil

/**
 * The pre-recording countdown, driven natively.
 *
 * Upstream runs this in the main process (`countdownController.ts`) and pushes
 * `countdown-tick` to every window; the recorder button renders the remaining
 * seconds, and `startNativeRecorder` awaits this before resuming a warm-started
 * capture. The unit is **seconds** — `countdownDelay` comes straight from
 * `get-countdown-delay`, which stores 3 by default — so a millisecond reading
 * here would make every take start after 3 ms instead of 3 s.
 *
 * Only one countdown can run at a time; a second request is answered with an
 * error rather than queued, as upstream does.
 */
object Countdown {

    // The shim subscribes to channels by their `electronAPI` name
    // (`onCountdownTick`), so the emitted channel must match that name. The IPC
    // name `countdown-tick` is what upstream's preload translates to the API
    // name, and `NativeBridge.emit` drops channels nobody subscribed to.
    const val EVENT_TICK = "onCountdownTick"

    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())

    /** The running countdown, if any. Guarded by [lock]. */
    private var active: Session? = null

    /** `{success:true, seconds}` where seconds is null when nothing is running. */
    fun active(): JSONObject {
        val seconds = synchronized(lock) { active?.remaining }
        return JSONObject().apply {
            put("success", true)
            put("seconds", seconds ?: JSONObject.NULL)
        }
    }

    /** `{success:true}` when cancelled; upstream answers the same either way. */
    fun cancel(): JSONObject {
        val session = synchronized(lock) { active }
        session?.finish(cancelled = true)
        return JSONObject().put("success", true)
    }

    /**
     * Counts down [seconds] (fractional values round up, as upstream's
     * `Math.ceil`), invoking [sink] with `countdown-tick` and the remaining
     * whole seconds. Resolves `{success:true}` at zero, or
     * `{success:false, cancelled:true}` when [cancel] wins.
     */
    fun start(seconds: Double, sink: (String, Any?) -> Unit): CompletableFuture<JSONObject> {
        val future = CompletableFuture<JSONObject>()
        if (!seconds.isFinite() || seconds < 0) {
            future.complete(failure("Invalid countdown delay"))
            return future
        }
        val total = ceil(seconds).toInt()
        if (total == 0) {
            // Upstream finishes a zero-length countdown without a tick.
            future.complete(JSONObject().put("success", true))
            return future
        }
        val session = Session(future, sink, total)
        synchronized(lock) {
            if (active != null) {
                future.complete(failure("Countdown already in progress"))
                return future
            }
            active = session
        }
        session.begin()
        return future
    }

    private fun failure(message: String) = JSONObject().apply {
        put("success", false)
        put("error", message)
    }

    private class Session(
        private val future: CompletableFuture<JSONObject>,
        private val sink: (String, Any?) -> Unit,
        total: Int,
    ) {
        @Volatile
        var remaining: Int = total
            private set

        private var done = false

        fun begin() {
            sink(EVENT_TICK, remaining)
            handler.postDelayed(tick, TICK_MS)
        }

        /**
         * One tick. `postDelayed` schedules a single run, so the next second
         * has to be scheduled from inside it — without this the countdown
         * decrements once and stalls, which hangs `startNativeRecorder` on its
         * `await startCountdown(...)` forever.
         *
         * Declared as an object (not a lambda val) because it references
         * itself; Kotlin cannot infer the type of a self-referential lambda.
         */
        private val tick: Runnable = object : Runnable {
            override fun run() {
                if (done) return
                remaining -= 1
                if (remaining <= 0) {
                    finish(cancelled = false)
                } else {
                    sink(EVENT_TICK, remaining)
                    handler.postDelayed(this, TICK_MS)
                }
            }
        }

        /**
         * Ends the countdown. The final tick carries 0, which is what
         * `settings.ts` sends for `null` (`send("countdown-tick", remaining ?? 0)`)
         * — the UI clears the number on it.
         */
        fun finish(cancelled: Boolean) {
            synchronized(this@Session) {
                if (done) return
                done = true
            }
            handler.removeCallbacks(tick)
            synchronized(lock) { if (active === this) active = null }
            sink(EVENT_TICK, 0)
            future.complete(
                JSONObject().apply {
                    put("success", !cancelled)
                    if (cancelled) put("cancelled", true)
                },
            )
        }

        companion object {
            const val TICK_MS = 1_000L
        }
    }
}
