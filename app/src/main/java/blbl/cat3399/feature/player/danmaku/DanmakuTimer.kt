package blbl.cat3399.feature.player.danmaku

import kotlin.math.abs

/**
 * AkDanmaku-style timer:
 * - Uses System.nanoTime() for smooth advancement.
 * - Soft-corrects towards raw player position to reduce visible jitter.
 * - Hard-syncs on seek/discontinuity or large drift.
 */
internal class DanmakuTimer {
    @Volatile
    private var lastFrameNanos: Long = 0L

    @Volatile
    private var smoothPositionMs: Double = 0.0

    @Volatile
    private var lastSeekSerial: Int = 0

    fun reset(positionMs: Long, nowNanos: Long, seekSerial: Int) {
        lastFrameNanos = nowNanos
        smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        lastSeekSerial = seekSerial
    }

    fun currentPositionMs(): Long = smoothPositionMs.toLong()

    fun step(
        nowNanos: Long,
        rawPositionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        seekSerial: Int,
    ): Long {
        val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
        val lastNanos = lastFrameNanos

        if (lastNanos == 0L || seekSerial != lastSeekSerial) {
            reset(positionMs = rawPositionMs, nowNanos = nowNanos, seekSerial = seekSerial)
            return rawPositionMs
        }

        val dtNanos = (nowNanos - lastNanos).coerceAtLeast(0L)
        lastFrameNanos = nowNanos
        lastSeekSerial = seekSerial

        if (!isPlaying) {
            smoothPositionMs = raw
            return rawPositionMs
        }

        val speed =
            playbackSpeed
                .takeIf { it.isFinite() && it > 0f }
                ?.toDouble()
                ?: 1.0

        if (dtNanos > 0L) {
            val dtMs = dtNanos.toDouble() / 1_000_000.0
            smoothPositionMs += dtMs * speed
        }

        // Keep relative lead small to avoid "danmaku too early".
        val maxAllowed = raw + MAX_LEAD_MS
        if (smoothPositionMs > maxAllowed) {
            smoothPositionMs = maxAllowed
            return smoothPositionMs.toLong()
        }

        // Hard sync if we fall too far behind (seek/discontinuity/jank).
        val diff = raw - smoothPositionMs
        if (diff >= HARD_SYNC_THRESHOLD_MS) {
            smoothPositionMs = raw
            return rawPositionMs
        }

        // Soft catch-up if behind.
        if (diff > 0.0) {
            smoothPositionMs += diff * CORRECTION_GAIN
            if (smoothPositionMs > maxAllowed) smoothPositionMs = maxAllowed
        }

        // Clamp for safety.
        if (!smoothPositionMs.isFinite() || abs(smoothPositionMs) > 1e15) {
            smoothPositionMs = raw
        }
        if (smoothPositionMs < 0.0) smoothPositionMs = 0.0
        return smoothPositionMs.toLong()
    }

    private companion object {
        private const val HARD_SYNC_THRESHOLD_MS = 250.0
        private const val MAX_LEAD_MS = 48.0
        private const val CORRECTION_GAIN = 0.12
    }
}

