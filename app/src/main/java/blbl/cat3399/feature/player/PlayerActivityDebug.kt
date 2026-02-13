package blbl.cat3399.feature.player

import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PlayerDebugMetrics {
    var cdnHost: String? = null
    var videoTransferHost: String? = null
    var audioTransferHost: String? = null
    var videoDecoderName: String? = null
    var videoInputWidth: Int? = null
    var videoInputHeight: Int? = null
    var videoInputFps: Float? = null
    var droppedFramesTotal: Long = 0L
    var rebufferCount: Int = 0
    var lastPlaybackState: Int = Player.STATE_IDLE
    var renderFps: Float? = null
    var renderFpsLastAtMs: Long? = null
    var renderedFramesLastCount: Int? = null
    var renderedFramesLastAtMs: Long? = null

    fun reset() {
        cdnHost = null
        videoTransferHost = null
        audioTransferHost = null
        videoDecoderName = null
        videoInputWidth = null
        videoInputHeight = null
        videoInputFps = null
        droppedFramesTotal = 0L
        rebufferCount = 0
        lastPlaybackState = Player.STATE_IDLE
        renderFps = null
        renderFpsLastAtMs = null
        renderedFramesLastCount = null
        renderedFramesLastAtMs = null
    }
}

internal fun PlayerActivity.updateDebugOverlay() {
    val enabled = session.debugEnabled
    binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
    binding.danmakuView.setDebugEnabled(enabled)
    debugJob?.cancel()
    if (!enabled) return
    val exo = player ?: return
    debugJob =
        lifecycleScope.launch {
            while (isActive) {
                binding.tvDebug.text = buildDebugText(exo)
                delay(500)
            }
        }
}

private fun PlayerActivity.buildDebugText(exo: ExoPlayer): String {
    updateDebugVideoStatsFromCounters(exo)
    val sb = StringBuilder()
    val state =
        when (exo.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> exo.playbackState.toString()
        }
    sb.append("state=").append(state)
    sb.append(" playing=").append(exo.isPlaying)
    sb.append(" pwr=").append(exo.playWhenReady)
    sb.append('\n')

    sb.append("pos=").append(exo.currentPosition).append("ms")
    sb.append(" buf=").append(exo.bufferedPosition).append("ms")
    sb.append(" spd=").append(String.format(Locale.US, "%.2f", exo.playbackParameters.speed))
    sb.append('\n')

    val trackFormat = pickSelectedVideoFormat(exo)
    val res = buildDebugResolutionText(exo.videoSize, debug.videoInputWidth, debug.videoInputHeight, trackFormat)
    sb.append("res=").append(res)
    val fps =
        formatDebugFps(debug.renderFps)
            ?: formatDebugFps(debug.videoInputFps ?: trackFormat?.frameRate)
            ?: "-"
    sb.append(" fps=").append(fps)
    val cdnVideo = debug.videoTransferHost?.trim().takeIf { !it.isNullOrBlank() }
    val cdnAudio = debug.audioTransferHost?.trim().takeIf { !it.isNullOrBlank() }
    val cdnPicked = cdnVideo ?: debug.cdnHost?.trim().takeIf { !it.isNullOrBlank() } ?: "-"
    val cdnHost =
        if (!cdnAudio.isNullOrBlank() && !cdnVideo.isNullOrBlank() && cdnAudio != cdnVideo) {
            "v=$cdnVideo a=$cdnAudio"
        } else {
            cdnPicked
        }
    if (cdnHost.length <= 42) {
        sb.append(" cdn=").append(cdnHost)
        sb.append('\n')
    } else {
        sb.append('\n')
        sb.append("cdn=").append(cdnHost)
        sb.append('\n')
    }

    buildDebugDisplayText()?.let { disp ->
        sb.append("disp=").append(disp)
        sb.append('\n')
    }

    sb.append("decoder=").append(shortenDebugValue(debug.videoDecoderName ?: "-", maxChars = 64))
    sb.append('\n')

    sb.append("dropped=").append(debug.droppedFramesTotal)
    sb.append(" rebuffer=").append(debug.rebufferCount)

    runCatching { binding.danmakuView.getDebugStats() }.getOrNull()?.let { dm ->
        sb.append('\n')
        sb.append("dm=").append(if (dm.configEnabled) "on" else "off")
        sb.append(" fps=").append(String.format(Locale.US, "%.1f", dm.drawFps))
        sb.append(" act=").append(dm.lastFrameActive)
        sb.append(" pend=").append(dm.lastFramePending)
        sb.append(" hit=").append(dm.lastFrameCachedDrawn).append('/').append(dm.lastFrameActive)
        sb.append(" fb=").append(dm.lastFrameFallbackDrawn)
        sb.append(" q=").append(dm.queueDepth)
        if (dm.invalidateFull) {
            sb.append(" inv=full")
        } else {
            sb.append(" inv=").append(dm.invalidateTopPx).append('-').append(dm.invalidateBottomPx)
        }
        sb.append('\n')

        val poolMb = dm.poolBytes.toDouble() / (1024.0 * 1024.0)
        val poolMaxMb = dm.poolMaxBytes.toDouble() / (1024.0 * 1024.0)
        sb.append("bmp cache=").append(dm.cacheItems)
        sb.append(" rendering=").append(dm.renderingItems)
        sb.append(" pool=").append(dm.poolItems)
        sb.append('(')
            .append(String.format(Locale.US, "%.1f", poolMb))
            .append('/')
            .append(String.format(Locale.US, "%.0f", poolMaxMb))
            .append("MB)")
        sb.append(" new=").append(dm.bitmapCreated)
        sb.append(" reuse=").append(dm.bitmapReused)
        sb.append(" put=").append(dm.bitmapPutToPool)
        sb.append(" rec=").append(dm.bitmapRecycled)
        sb.append('\n')

        sb.append("dm ms upd=")
            .append(String.format(Locale.US, "%.2f", dm.updateAvgMs))
            .append('/')
            .append(String.format(Locale.US, "%.2f", dm.updateMaxMs))
        sb.append(" draw=")
            .append(String.format(Locale.US, "%.2f", dm.drawAvgMs))
            .append('/')
            .append(String.format(Locale.US, "%.2f", dm.drawMaxMs))
        sb.append(" req=").append(dm.lastFrameRequestsActive).append('+').append(dm.lastFrameRequestsPrefetch)
    }
    return sb.toString()
}

private fun PlayerActivity.buildDebugDisplayText(): String? {
    val display = binding.root.display ?: return null
    val hz = display.refreshRate.takeIf { it > 0f }
    if (Build.VERSION.SDK_INT < 23) {
        return hz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
    }
    val mode = display.mode
    val w = mode.physicalWidth.takeIf { it > 0 } ?: 0
    val h = mode.physicalHeight.takeIf { it > 0 } ?: 0
    val mhz = mode.refreshRate.takeIf { it > 0f } ?: hz
    val hzText = mhz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
    if (w <= 0 || h <= 0) return hzText
    return "${w}x${h}@${hzText}"
}

private fun PlayerActivity.updateDebugVideoStatsFromCounters(exo: ExoPlayer) {
    val nowMs = SystemClock.elapsedRealtime()
    val counters = exo.videoDecoderCounters ?: return
    counters.ensureUpdated()

    // Dropped frames: keep the max to avoid going backwards across updates.
    debug.droppedFramesTotal = maxOf(debug.droppedFramesTotal, counters.droppedBufferCount.toLong())

    // Render fps: derive from rendered output buffers between overlay updates.
    val count = counters.renderedOutputBufferCount
    val lastCount = debug.renderedFramesLastCount
    val lastAt = debug.renderedFramesLastAtMs
    debug.renderedFramesLastCount = count
    debug.renderedFramesLastAtMs = nowMs

    if (lastCount == null || lastAt == null) return
    val deltaMs = nowMs - lastAt
    val deltaFrames = count - lastCount
    if (deltaMs <= 0L || deltaMs > 10_000L) return
    if (deltaFrames <= 0) return
    val instantFps = (deltaFrames * 1000f) / deltaMs.toFloat()
    debug.renderFps = debug.renderFps?.let { it * 0.7f + instantFps * 0.3f } ?: instantFps
}

private fun buildDebugResolutionText(vs: VideoSize, fallbackWidth: Int?, fallbackHeight: Int?, trackFormat: Format?): String {
    val w = vs.width.takeIf { it > 0 } ?: fallbackWidth ?: 0
    val h = vs.height.takeIf { it > 0 } ?: fallbackHeight ?: 0
    if (w > 0 && h > 0) return "${w}x${h}"
    val tw = trackFormat?.width?.takeIf { it > 0 } ?: 0
    val th = trackFormat?.height?.takeIf { it > 0 } ?: 0
    return if (tw > 0 && th > 0) "${tw}x${th}" else "-"
}

private fun formatDebugFps(fps: Float?): String? {
    val v = fps?.takeIf { it > 0f } ?: return null
    val rounded = v.roundToInt().toFloat()
    return if (abs(v - rounded) < 0.05f) rounded.toInt().toString() else String.format(Locale.US, "%.1f", v)
}

private fun shortenDebugValue(value: String, maxChars: Int): String {
    val v = value.trim()
    if (v.length <= maxChars) return v
    return v.take(maxChars - 1) + "…"
}

private fun pickSelectedVideoFormat(exo: ExoPlayer): Format? {
    val tracks = exo.currentTracks
    for (g in tracks.groups) {
        if (!g.isSelected) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSelected(i)) continue
            val f = g.getTrackFormat(i)
            val mime = f.sampleMimeType ?: ""
            if (mime.startsWith("video/")) return f
        }
    }
    return null
}
