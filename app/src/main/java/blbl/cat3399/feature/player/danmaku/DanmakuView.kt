package blbl.cat3399.feature.player.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import blbl.cat3399.core.emote.EmoteBitmapLoader
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val engineWorker = EngineWorker()
    private var engineThread: HandlerThread? = null
    private var engineHandler: Handler? = null
    private val tickPending = AtomicBoolean(false)
    private val latestTick = AtomicReference<TickParams?>(null)
    @Volatile private var renderSnapshot: RenderSnapshot = RenderSnapshot.EMPTY

    private val bitmapPool = BitmapPool(maxBytes = defaultBitmapPoolMaxBytes())
    private val debugStats = DebugStatsCollector()
    @Volatile private var debugEnabled: Boolean = false
    private val paceLogger = PaceLogger()

    // Tick pacing metrics (only used when debug is enabled; aggregated in logcat).
    private val tickPaceRequested = AtomicLong(0L)
    private val tickPaceExecuted = AtomicLong(0L)
    private val tickPaceCoalesced = AtomicLong(0L)
    private val tickPaceDelayCount = AtomicLong(0L)
    private val tickPaceDelayTotalMs = AtomicLong(0L)
    private val tickPaceDelayMaxMs = AtomicLong(0L)

    private class CachedBitmap(
        val bitmap: Bitmap,
    ) {
        var lastDrawFrameId: Int = 0
        var lastUseUptimeMs: Long = 0L
    }

    private enum class BitmapRecycleMode {
        SYNC,
        ASYNC,
    }

    private var bitmapCache = IdentityHashMap<Danmaku, CachedBitmap>()
    private val rendering = IdentityHashMap<Danmaku, Boolean>()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Disable bitmap filtering to reduce shimmer during horizontal motion on some displays.
        isFilterBitmap = false
    }
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val emotePlaceholderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emotePlaceholderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f).toFloat().coerceAtLeast(1f)
    }
    private val emoteTmpRectF = RectF()
    private var lastReplyEmoteVersion: Int = 0

    private sealed interface EmoteSegment {
        data class Text(
            val start: Int,
            val end: Int,
        ) : EmoteSegment

        data class Emote(
            val token: String,
            val url: String,
        ) : EmoteSegment
    }

    private var emoteSegmentsCache = IdentityHashMap<Danmaku, List<EmoteSegment>>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = sp(18f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
        textSize = fill.textSize
        typeface = Typeface.DEFAULT_BOLD
    }
    private val fontMetrics = Paint.FontMetrics()

    private var bitmapRenderScope: CoroutineScope? = null
    private var bitmapRenderQueue: Channel<BitmapRequest>? = null
    private var bitmapRenderGeneration: Int = 0

    private var positionProvider: (() -> Long)? = null
    private var isPlayingProvider: (() -> Boolean)? = null
    private var playbackSpeedProvider: (() -> Float)? = null
    private var configProvider: (() -> DanmakuConfig)? = null
    private var lastPositionMs: Long = 0L
    private val smoothClock = SmoothClock()
    private var lastDrawUptimeMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L
    private var lastLayoutConfig: DanmakuConfig? = null
    private var drawFrameId: Int = 0
    private var warmupFrames: Int = 0
    private var cachedTopInsetPx: Int = dp(2f)
    private var cachedBottomInsetPx: Int = dp(52f)
    private var insetsDirty: Boolean = true
    private var lastAreaInvalidateTopPx: Int = 0
    private var lastAreaInvalidateBottomPx: Int = 0
    private var lastInvalidateTopPx: Int = 0
    private var lastInvalidateBottomPx: Int = 0
    private var lastInvalidateFull: Boolean = true

    private data class RenderSnapshot(
        val positionMs: Long,
        val activeDanmakus: Array<Danmaku?>,
        val activeX: FloatArray,
        val activeYTop: FloatArray,
        val activeTextWidth: FloatArray,
        val activeCount: Int,
        val pendingCount: Int,
        val nextAtMs: Int?,
        val prefetchDanmakus: Array<Danmaku?>,
        val prefetchTextWidth: FloatArray,
        val prefetchCount: Int,
    ) {
        companion object {
            val EMPTY =
                RenderSnapshot(
                    positionMs = 0L,
                    activeDanmakus = emptyArray(),
                    activeX = FloatArray(0),
                    activeYTop = FloatArray(0),
                    activeTextWidth = FloatArray(0),
                    activeCount = 0,
                    pendingCount = 0,
                    nextAtMs = null,
                    prefetchDanmakus = emptyArray(),
                    prefetchTextWidth = FloatArray(0),
                    prefetchCount = 0,
                )
        }
    }

    private data class TickParams(
        val width: Int,
        val height: Int,
        val positionMs: Long,
        val requestedAtUptimeMs: Long,
        val textSizePx: Float,
        val outlinePad: Float,
        val speedLevel: Int,
        val area: Float,
        val topInsetPx: Int,
        val bottomInsetPx: Int,
    )

    private class SmoothClock {
        // A smooth playback clock to reduce visible jitter caused by stepwise position updates.
        // - Prevents large lead over raw time to avoid "danmaku too early".
        // - On each frame: advance by dt * speed when playing, then gently correct towards raw position.
        // - Snaps to raw on pauses/seeks/large drift to keep sync.
        private var lastUptimeMs: Long = 0L
        private var smoothPositionMs: Double = 0.0

        fun reset(positionMs: Long, nowUptimeMs: Long) {
            lastUptimeMs = nowUptimeMs
            smoothPositionMs = positionMs.coerceAtLeast(0L).toDouble()
        }

        fun currentPositionMs(): Long = smoothPositionMs.toLong()

        fun update(
            nowUptimeMs: Long,
            rawPositionMs: Long,
            isPlaying: Boolean,
            playbackSpeed: Float,
        ): Long {
            val raw = rawPositionMs.coerceAtLeast(0L).toDouble()
            val last = lastUptimeMs
            if (last == 0L) {
                lastUptimeMs = nowUptimeMs
                smoothPositionMs = raw
                return rawPositionMs
            }

            val dtMs = (nowUptimeMs - last).coerceAtLeast(0L)
            lastUptimeMs = nowUptimeMs
            if (!isPlaying) {
                // When paused/buffering, follow the raw clock to avoid drift (raw typically stops too).
                smoothPositionMs = raw
                return rawPositionMs
            }

            val speed =
                playbackSpeed
                    .takeIf { it.isFinite() && it > 0f }
                    ?.toDouble()
                    ?: 1.0
            if (dtMs > 0L) {
                smoothPositionMs += dtMs.toDouble() * speed
            }

            // If we drift too far behind, snap forward (seek/discontinuity/jank). We do NOT snap backwards:
            // if we lead raw time, clamp and/or pause advances until raw catches up.
            val diff = raw - smoothPositionMs
            if (diff >= HARD_SYNC_THRESHOLD_MS) {
                smoothPositionMs = raw
                return rawPositionMs
            }

            val maxAllowed = raw + MAX_LEAD_MS
            if (smoothPositionMs > maxAllowed) {
                smoothPositionMs = maxAllowed
                return smoothPositionMs.toLong()
            }

            // Soft catch-up when behind (bounded).
            if (diff > 0.0) {
                smoothPositionMs += diff * CORRECTION_GAIN
                if (smoothPositionMs > maxAllowed) smoothPositionMs = maxAllowed
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

    data class DebugStats(
        val viewAttached: Boolean,
        val configEnabled: Boolean,
        val lastPositionMs: Long,
        val drawFps: Float,
        val lastFrameActive: Int,
        val lastFramePending: Int,
        val lastFrameCachedDrawn: Int,
        val lastFrameFallbackDrawn: Int,
        val lastFrameRequestsActive: Int,
        val lastFrameRequestsPrefetch: Int,
        val cacheItems: Int,
        val renderingItems: Int,
        val queueDepth: Int,
        val poolItems: Int,
        val poolBytes: Long,
        val poolMaxBytes: Long,
        val bitmapCreated: Long,
        val bitmapReused: Long,
        val bitmapPutToPool: Long,
        val bitmapRecycled: Long,
        val invalidateFull: Boolean,
        val invalidateTopPx: Int,
        val invalidateBottomPx: Int,
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val drawAvgMs: Float,
        val drawMaxMs: Float,
    )

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        debugStats.reset()
        paceLogger.reset()
        tickPaceRequested.set(0L)
        tickPaceExecuted.set(0L)
        tickPaceCoalesced.set(0L)
        tickPaceDelayCount.set(0L)
        tickPaceDelayTotalMs.set(0L)
        tickPaceDelayMaxMs.set(0L)
    }

    fun getDebugStats(): DebugStats {
        val pool = bitmapPool.snapshot()
        val qDepth = debugStats.queueDepth()
        val now = SystemClock.uptimeMillis()
        return DebugStats(
            viewAttached = isAttachedToWindow,
            configEnabled = configProvider?.invoke()?.enabled == true,
            lastPositionMs = lastPositionMs,
            drawFps = debugStats.drawFps(now),
            lastFrameActive = debugStats.lastFrameActive,
            lastFramePending = debugStats.lastFramePending,
            lastFrameCachedDrawn = debugStats.lastFrameCachedDrawn,
            lastFrameFallbackDrawn = debugStats.lastFrameFallbackDrawn,
            lastFrameRequestsActive = debugStats.lastFrameRequestsActive,
            lastFrameRequestsPrefetch = debugStats.lastFrameRequestsPrefetch,
            cacheItems = bitmapCache.size,
            renderingItems = rendering.size,
            queueDepth = qDepth,
            poolItems = pool.count,
            poolBytes = pool.bytes,
            poolMaxBytes = pool.maxBytes,
            bitmapCreated = debugStats.bitmapCreated.get(),
            bitmapReused = debugStats.bitmapReused.get(),
            bitmapPutToPool = debugStats.bitmapPutToPool.get(),
            bitmapRecycled = debugStats.bitmapRecycled.get(),
            invalidateFull = lastInvalidateFull,
            invalidateTopPx = lastInvalidateTopPx,
            invalidateBottomPx = lastInvalidateBottomPx,
            updateAvgMs = debugStats.avgUpdateMs(),
            updateMaxMs = debugStats.maxUpdateMs(),
            drawAvgMs = debugStats.avgDrawMs(),
            drawMaxMs = debugStats.maxDrawMs(),
        )
    }

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
    }

    fun setIsPlayingProvider(provider: () -> Boolean) {
        isPlayingProvider = provider
    }

    fun setPlaybackSpeedProvider(provider: () -> Float) {
        playbackSpeedProvider = provider
    }

    fun setConfigProvider(provider: () -> DanmakuConfig) {
        configProvider = provider
    }

    private fun ensureEngineHandler(): Handler {
        val existing = engineHandler
        if (existing != null) return existing
        val thread = HandlerThread("DanmakuEngine").apply { start() }
        engineThread = thread
        return Handler(thread.looper).also { engineHandler = it }
    }

    private fun postToEngine(block: () -> Unit) {
        ensureEngineHandler().post(block)
    }

    private fun requestEngineTick(params: TickParams) {
        if (!isAttachedToWindow) return
        if (debugEnabled) tickPaceRequested.incrementAndGet()
        latestTick.set(params)
        val handler = ensureEngineHandler()
        scheduleTickIfNeeded(handler)
    }

    private fun scheduleTickIfNeeded(handler: Handler) {
        if (!tickPending.compareAndSet(false, true)) {
            if (debugEnabled) tickPaceCoalesced.incrementAndGet()
            return
        }
        handler.post { runTickOnce(handler) }
    }

    private fun runTickOnce(handler: Handler) {
        try {
            val params = latestTick.getAndSet(null) ?: return
            recordTickPace(params)
            engineWorker.tick(params)
        } catch (t: Throwable) {
            AppLog.w("DanmakuView", "engine tick crashed", t)
        } finally {
            tickPending.set(false)
            if (latestTick.get() != null) {
                scheduleTickIfNeeded(handler)
            }
        }
    }

    private fun stopEngineThread() {
        latestTick.set(null)
        tickPending.set(false)
        renderSnapshot = RenderSnapshot.EMPTY
        warmupFrames = 0
        engineHandler?.removeCallbacksAndMessages(null)
        engineHandler = null
        engineThread?.quitSafely()
        engineThread = null
    }

    fun setDanmakus(list: List<Danmaku>) {
        AppLog.i("DanmakuView", "setDanmakus size=${list.size}")
        renderSnapshot = RenderSnapshot.EMPTY
        warmupFrames = WARMUP_FRAMES
        postToEngine { engineWorker.setDanmakus(list) }
        clearBitmaps()
        invalidate()
    }

    fun appendDanmakus(list: List<Danmaku>, maxItems: Int = 0, alreadySorted: Boolean = false) {
        if (list.isEmpty()) return
        warmupFrames = WARMUP_FRAMES
        postToEngine {
            if (alreadySorted) engineWorker.appendDanmakusSorted(list) else engineWorker.appendDanmakus(list)
            if (maxItems > 0) engineWorker.trimToMax(maxItems)
        }
        invalidate()
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        warmupFrames = WARMUP_FRAMES
        postToEngine { engineWorker.trimToTimeRange(min, max) }
        invalidate()
    }

    fun notifySeek(positionMs: Long) {
        renderSnapshot = RenderSnapshot.EMPTY
        warmupFrames = WARMUP_FRAMES
        postToEngine { engineWorker.seekTo(positionMs) }
        lastPositionMs = positionMs
        lastDrawUptimeMs = SystemClock.uptimeMillis()
        smoothClock.reset(positionMs = positionMs, nowUptimeMs = lastDrawUptimeMs)
        lastPositionChangeUptimeMs = lastDrawUptimeMs
        clearBitmaps()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val provider = positionProvider ?: return
        val config = configProvider?.invoke() ?: defaultConfig()
        if (!config.enabled) {
            clearBitmaps()
            lastLayoutConfig = null
            renderSnapshot = RenderSnapshot.EMPTY
            warmupFrames = 0
            paceLogger.reset()
            smoothClock.reset(positionMs = 0L, nowUptimeMs = 0L)
            return
        }

        ReplyEmotePanelRepository.init(context)
        val replyEmoteVersion = ReplyEmotePanelRepository.version()
        if (replyEmoteVersion != lastReplyEmoteVersion) {
            lastReplyEmoteVersion = replyEmoteVersion
            clearBitmaps()
            emoteSegmentsCache.clear()
        }

        val prevRawPositionMs = lastPositionMs
        val rawPositionMs = provider()
        val now = SystemClock.uptimeMillis()
        if (lastDrawUptimeMs == 0L) lastDrawUptimeMs = now
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now

        if (rawPositionMs != prevRawPositionMs) {
            lastPositionChangeUptimeMs = now
        }
        lastPositionMs = rawPositionMs
        lastDrawUptimeMs = now

        val isPlaying =
            runCatching { isPlayingProvider?.invoke() }.getOrNull()
                ?: (now - lastPositionChangeUptimeMs < STOP_WHEN_IDLE_MS)
        val playbackSpeed =
            runCatching { playbackSpeedProvider?.invoke() }.getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: 1f
        val prevSmoothPositionMs = smoothClock.currentPositionMs()
        val positionMs =
            smoothClock.update(
                nowUptimeMs = now,
                rawPositionMs = rawPositionMs,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
            )

        when (decideLayoutReset(lastLayoutConfig, config)) {
            LayoutReset.NONE -> Unit
            LayoutReset.SEEK_ONLY -> {
                renderSnapshot = RenderSnapshot.EMPTY
                warmupFrames = WARMUP_FRAMES
                postToEngine { engineWorker.seekTo(positionMs) }
            }
            LayoutReset.SEEK_AND_CLEAR_BITMAPS -> {
                renderSnapshot = RenderSnapshot.EMPTY
                warmupFrames = WARMUP_FRAMES
                postToEngine { engineWorker.seekTo(positionMs) }
                clearBitmaps()
            }
        }
        lastLayoutConfig = config

        val textSizePx = sp(config.textSizeSp)
        fill.textSize = textSizePx
        stroke.textSize = textSizePx

        val outlinePad = max(1f, stroke.strokeWidth / 2f)
        val opacityAlpha = (config.opacity * 255).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha
        emotePaint.alpha = opacityAlpha
        run {
            val fillA = ((opacityAlpha * 0x22) / 255).coerceIn(0, 255)
            val strokeA = ((opacityAlpha * 0x66) / 255).coerceIn(0, 255)
            emotePlaceholderFill.color = (fillA shl 24) or 0x000000
            emotePlaceholderStroke.color = (strokeA shl 24) or 0xFFFFFF
        }
        val topInsetPx = safeTopInsetPx()
        val bottomInsetPx = safeBottomInsetPx()
        val invTop = topInsetPx.coerceIn(0, height.coerceAtLeast(0))
        val availableHeight = (height - topInsetPx - bottomInsetPx).coerceAtLeast(0)
        val invBottomRaw = topInsetPx + (availableHeight.toFloat() * config.area.coerceAtLeast(0f)).toInt()
        val invBottom = invBottomRaw.coerceIn(invTop, height.coerceAtLeast(0))
        lastAreaInvalidateTopPx = invTop
        lastAreaInvalidateBottomPx = invBottom

        val snapshotBeforeDraw = renderSnapshot
        if (debugEnabled) {
            paceLogger.onFrame(
                nowUptimeMs = now,
                rawPositionMs = rawPositionMs,
                prevRawPositionMs = prevRawPositionMs,
                smoothPositionMs = positionMs,
                prevSmoothPositionMs = prevSmoothPositionMs,
                snapshotPositionMs = snapshotBeforeDraw.positionMs,
            )
        }
        requestEngineTick(
            TickParams(
                width = width,
                height = height,
                positionMs = positionMs,
                requestedAtUptimeMs = now,
                textSizePx = textSizePx,
                outlinePad = outlinePad,
                speedLevel = config.speedLevel,
                area = config.area,
                topInsetPx = topInsetPx,
                bottomInsetPx = bottomInsetPx,
            ),
        )

        drawFrameId++
        val frameId = drawFrameId
        fill.getFontMetrics(fontMetrics)
        val baselineOffset = outlinePad - fontMetrics.ascent
        val tDrawStartNs = if (debugEnabled) SystemClock.elapsedRealtimeNanos() else 0L
        var requested = 0
        var requestedPrefetch = 0
        var fallback = 0
        var cachedDrawn = 0
        val activeCount = snapshotBeforeDraw.activeCount
        for (i in 0 until activeCount) {
            val d = snapshotBeforeDraw.activeDanmakus.getOrNull(i) ?: continue
            val x = (snapshotBeforeDraw.activeX.getOrNull(i) ?: continue).roundToInt().toFloat()
            val yTop = (snapshotBeforeDraw.activeYTop.getOrNull(i) ?: continue).roundToInt().toFloat()
            val textWidth = snapshotBeforeDraw.activeTextWidth.getOrNull(i) ?: continue
            val cached = bitmapCache[d]
            if (cached != null) {
                cached.lastDrawFrameId = frameId
                cached.lastUseUptimeMs = now
                canvas.drawBitmap(cached.bitmap, x, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }

            val emoteSegments = emoteSegmentsFor(d)
            val emotesReady = emoteSegments?.let(::areEmoteBitmapsReadyAndPrefetch) ?: true
            if (requested < MAX_BITMAP_REQUESTS_PER_FRAME && rendering[d] != true && emotesReady) {
                requested++
                rendering[d] = true
                ensureBitmapRenderer()
                val queued =
                    bitmapRenderQueue
                        ?.trySend(
                            BitmapRequest(
                                danmaku = d,
                                textWidth = textWidth,
                                outlinePad = outlinePad,
                                textSizePx = textSizePx,
                                generation = bitmapRenderGeneration,
                            ),
                        )?.isSuccess == true
                if (queued) {
                    debugStats.queueEnqueued.incrementAndGet()
                    debugStats.requestsActive.incrementAndGet()
                } else {
                    debugStats.requestsDropped.incrementAndGet()
                    rendering.remove(d)
                }
            }
            if (fallback < MAX_FALLBACK_TEXT_PER_FRAME) {
                fallback++
                drawTextFallback(
                    canvas,
                    d,
                    x = x,
                    yTop = yTop,
                    outlinePad = outlinePad,
                    baselineOffset = baselineOffset,
                    opacityAlpha = opacityAlpha,
                )
            }
        }

        // Low-priority prefetch: warm bitmaps for danmaku that will enter soon, using remaining budget.
        if (requested < MAX_BITMAP_REQUESTS_PER_FRAME) {
            val left = MAX_BITMAP_REQUESTS_PER_FRAME - requested
            var prefetched = 0
            val queueDepth = debugStats.queueDepth()
            val prefetchBudget =
                if (queueDepth >= PREFETCH_MAX_QUEUE_DEPTH) {
                    0
                } else {
                    left.coerceAtMost(MAX_PREFETCH_REQUESTS_PER_FRAME)
                }
            for (i in 0 until snapshotBeforeDraw.prefetchCount) {
                if (prefetched >= prefetchBudget) break
                val d = snapshotBeforeDraw.prefetchDanmakus.getOrNull(i) ?: continue
                val tw = snapshotBeforeDraw.prefetchTextWidth.getOrNull(i) ?: continue
                if (d.text.isBlank()) continue
                if (bitmapCache.containsKey(d)) continue
                if (rendering[d] == true) continue
                val emoteSegments = emoteSegmentsFor(d)
                val emotesReady = emoteSegments?.let(::areEmoteBitmapsReadyAndPrefetch) ?: true
                if (!emotesReady) continue
                rendering[d] = true
                ensureBitmapRenderer()
                val currentQueueDepth = debugStats.queueDepth()
                if (currentQueueDepth >= PREFETCH_MAX_QUEUE_DEPTH) {
                    rendering.remove(d)
                    break
                }
                val queued =
                    bitmapRenderQueue
                        ?.trySend(
                            BitmapRequest(
                                danmaku = d,
                                textWidth = tw,
                                outlinePad = outlinePad,
                                textSizePx = textSizePx,
                                generation = bitmapRenderGeneration,
                            ),
                        )?.isSuccess == true
                if (queued) {
                    requestedPrefetch++
                    debugStats.queueEnqueued.incrementAndGet()
                    debugStats.requestsPrefetch.incrementAndGet()
                } else {
                    debugStats.requestsDropped.incrementAndGet()
                    rendering.remove(d)
                    continue
                }
                prefetched++
            }
        }

        // Recycle bitmaps that are no longer active.
        trimBitmapCache(frameId, nowUptimeMs = now)
        val tDrawEndNs = if (debugEnabled) SystemClock.elapsedRealtimeNanos() else 0L
        if (debugEnabled) {
            val drawNs = (tDrawEndNs - tDrawStartNs).coerceAtLeast(0L)
            debugStats.recordDraw(
                nowUptimeMs = now,
                drawNs = drawNs,
                active = activeCount,
                pending = snapshotBeforeDraw.pendingCount,
                cachedDrawn = cachedDrawn,
                fallbackDrawn = fallback,
                requestsActive = requested,
                requestsPrefetch = requestedPrefetch,
            )
            paceLogger.onDrawCost(drawNs = drawNs)
        }

        // If playback time hasn't moved for a while, stop the loop to avoid wasting 60fps while paused/buffering.
        // PlayerActivity kicks `invalidate()` on resume/play state changes.
        if (now - lastPositionChangeUptimeMs >= STOP_WHEN_IDLE_MS) {
            postInvalidateDelayedDanmakuArea(delayMs = IDLE_POLL_MS.toLong())
            if (debugEnabled) {
                paceLogger.maybeLog(nowUptimeMs = now, rawPositionMs = rawPositionMs, smoothPositionMs = positionMs, snapshot = snapshotBeforeDraw)
            }
            return
        }

        // Keep vsync loop while we have active danmaku; otherwise schedule lazily.
        if (activeCount > 0 || snapshotBeforeDraw.pendingCount > 0) {
            postInvalidateOnAnimationDanmakuArea()
            if (debugEnabled) {
                paceLogger.maybeLog(nowUptimeMs = now, rawPositionMs = rawPositionMs, smoothPositionMs = positionMs, snapshot = snapshotBeforeDraw)
            }
            return
        }
        val nextAt = snapshotBeforeDraw.nextAtMs
        if (nextAt != null && nextAt <= positionMs + 250) {
            postInvalidateOnAnimationDanmakuArea()
            if (debugEnabled) {
                paceLogger.maybeLog(nowUptimeMs = now, rawPositionMs = rawPositionMs, smoothPositionMs = positionMs, snapshot = snapshotBeforeDraw)
            }
            return
        }
        if (nextAt != null && nextAt > positionMs) {
            val delay = (nextAt - positionMs).coerceAtMost(750L)
            postInvalidateDelayedDanmakuArea(delayMs = delay)
            if (debugEnabled) {
                paceLogger.maybeLog(nowUptimeMs = now, rawPositionMs = rawPositionMs, smoothPositionMs = positionMs, snapshot = snapshotBeforeDraw)
            }
            return
        }
        if (warmupFrames > 0) {
            warmupFrames--
            postInvalidateOnAnimationDanmakuArea()
        }
        if (debugEnabled) {
            paceLogger.maybeLog(nowUptimeMs = now, rawPositionMs = rawPositionMs, smoothPositionMs = positionMs, snapshot = snapshotBeforeDraw)
        }
    }

    private fun postInvalidateOnAnimationDanmakuArea() {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            lastInvalidateFull = true
            lastInvalidateTopPx = 0
            lastInvalidateBottomPx = 0
            postInvalidateOnAnimation()
            return
        }

        val top = lastAreaInvalidateTopPx.coerceIn(0, h)
        var bottom = lastAreaInvalidateBottomPx.coerceIn(top, h)
        if (bottom <= top) bottom = (top + 1).coerceAtMost(h)

        val full = top <= 0 && bottom >= h
        lastInvalidateFull = full
        lastInvalidateTopPx = top
        lastInvalidateBottomPx = bottom
        if (full) {
            postInvalidateOnAnimation()
        } else {
            postInvalidateOnAnimation(0, top, w, bottom)
        }
    }

    private fun postInvalidateDelayedDanmakuArea(delayMs: Long) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        if (w <= 0 || h <= 0) {
            lastInvalidateFull = true
            lastInvalidateTopPx = 0
            lastInvalidateBottomPx = 0
            postInvalidateDelayed(delayMs)
            return
        }

        val top = lastAreaInvalidateTopPx.coerceIn(0, h)
        var bottom = lastAreaInvalidateBottomPx.coerceIn(top, h)
        if (bottom <= top) bottom = (top + 1).coerceAtMost(h)

        val full = top <= 0 && bottom >= h
        lastInvalidateFull = full
        lastInvalidateTopPx = top
        lastInvalidateBottomPx = bottom
        if (full) {
            postInvalidateDelayed(delayMs)
        } else {
            postInvalidateDelayed(delayMs, 0, top, w, bottom)
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun safeTopInsetPx(): Int {
        if (insetsDirty) updateCachedInsets()
        return cachedTopInsetPx
    }

    private fun safeBottomInsetPx(): Int {
        if (insetsDirty) updateCachedInsets()
        return cachedBottomInsetPx
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private val insetsListener = ViewTreeObserver.OnGlobalLayoutListener { insetsDirty = true }

    private fun updateCachedInsets() {
        // Use real window insets when possible (full-screen players may have 0 status-bar inset).
        val insetTop =
            ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.top
                ?: runCatching {
                    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (id > 0) resources.getDimensionPixelSize(id) else 0
                }.getOrDefault(0)
        // Keep a tiny padding to avoid clipping at the very top.
        cachedTopInsetPx = insetTop + dp(2f)
        // Avoid player controller area; conservative default.
        cachedBottomInsetPx = dp(52f)
        insetsDirty = false
    }

    private fun emoteSegmentsFor(danmaku: Danmaku): List<EmoteSegment>? {
        // Fast-path: no token marker.
        val text = danmaku.text
        if (!text.contains('[')) return null
        if (ReplyEmotePanelRepository.version() <= 0) return null

        val cached = emoteSegmentsCache[danmaku]
        if (cached != null) return cached

        val parsed = parseEmoteSegments(text) ?: return null
        if (emoteSegmentsCache.size >= MAX_EMOTE_SEGMENTS_CACHE_ITEMS) {
            emoteSegmentsCache.clear()
        }
        emoteSegmentsCache[danmaku] = parsed
        return parsed
    }

    private fun areEmoteBitmapsReadyAndPrefetch(segments: List<EmoteSegment>): Boolean {
        var ready = true
        for (seg in segments) {
            if (seg !is EmoteSegment.Emote) continue
            val bmp = EmoteBitmapLoader.getCached(seg.url)
            if (bmp != null) continue
            ready = false
            EmoteBitmapLoader.prefetch(seg.url)
        }
        return ready
    }

    private fun parseEmoteSegments(text: String): List<EmoteSegment>? {
        var i = 0
        var lastTextStart = 0
        var hasEmote = false
        val out = ArrayList<EmoteSegment>(8)
        while (i < text.length) {
            val open = text.indexOf('[', startIndex = i)
            if (open < 0) break
            val close = text.indexOf(']', startIndex = open + 1)
            if (close < 0) break

            val token = text.substring(open, close + 1)
            val url = ReplyEmotePanelRepository.urlForToken(token)
            if (url != null && url.startsWith("http")) {
                hasEmote = true
                if (open > lastTextStart) out.add(EmoteSegment.Text(start = lastTextStart, end = open))
                out.add(EmoteSegment.Emote(token = token, url = url))
                lastTextStart = close + 1
            }
            i = close + 1
        }
        if (!hasEmote) return null
        if (lastTextStart < text.length) out.add(EmoteSegment.Text(start = lastTextStart, end = text.length))
        return out
    }

    private fun drawTextFallback(
        canvas: Canvas,
        danmaku: Danmaku,
        x: Float,
        yTop: Float,
        outlinePad: Float,
        baselineOffset: Float,
        opacityAlpha: Int,
    ) {
        val text = danmaku.text
        if (text.isBlank()) return

        val rgb = danmaku.color and 0xFFFFFF
        val strokeAlpha = ((opacityAlpha * 0xCC) / 255).coerceIn(0, 255)
        stroke.color = (strokeAlpha shl 24) or 0x000000
        fill.color = (opacityAlpha shl 24) or rgb

        val textX = (x + outlinePad).roundToInt().toFloat()
        val baseline = (yTop + baselineOffset).roundToInt().toFloat()
        val segments = emoteSegmentsFor(danmaku)
        if (segments == null) {
            canvas.drawText(text, textX, baseline, stroke)
            canvas.drawText(text, textX, baseline, fill)
            return
        }

        val emoteSizePx = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
        val emoteTop = (yTop + outlinePad).roundToInt().toFloat()
        val r = (emoteSizePx * 0.18f).coerceIn(2f, 10f)
        var cursorX = textX
        for (seg in segments) {
            when (seg) {
                is EmoteSegment.Text -> {
                    if (seg.end > seg.start) {
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, stroke)
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, fill)
                        cursorX += fill.measureText(text, seg.start, seg.end)
                    }
                }
                is EmoteSegment.Emote -> {
                    val bmp = EmoteBitmapLoader.getCached(seg.url)
                    if (bmp != null) {
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawBitmap(bmp, null, emoteTmpRectF, emotePaint)
                    } else {
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderFill)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderStroke)
                    }
                    cursorX += emoteSizePx
                }
            }
        }
    }

    private fun trimBitmapCache(frameId: Int, nowUptimeMs: Long) {
        if (bitmapCache.isEmpty()) return
        val removeBefore = nowUptimeMs - BITMAP_IDLE_EVICT_MS
        val toRelease = ArrayList<Bitmap>()
        val it = bitmapCache.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val cached = e.value
            val keep = cached.lastDrawFrameId == frameId || cached.lastUseUptimeMs >= removeBefore
            if (keep) continue
            toRelease.add(cached.bitmap)
            it.remove()
        }
        evictOldestIfNeeded(frameId = frameId, toRelease = toRelease)
        if (toRelease.isNotEmpty()) {
            releaseBitmapsAsync(toRelease)
        }
    }

    private fun clearBitmaps() {
        clearBitmaps(recycleMode = BitmapRecycleMode.SYNC)
    }

    private fun recycleBitmapsFromCache(cache: IdentityHashMap<Danmaku, CachedBitmap>) {
        val overflow = ArrayList<Bitmap>()
        for (cached in cache.values) {
            val bmp = cached.bitmap
            if (bmp.isRecycled) continue
            val pooled = bitmapPool.tryPut(bmp)
            if (pooled) {
                debugStats.bitmapPutToPool.incrementAndGet()
            } else {
                overflow.add(bmp)
            }
        }
        cache.clear()
        if (overflow.isNotEmpty()) {
            debugStats.bitmapRecycled.addAndGet(overflow.size.toLong())
            recycleScope.launch { overflow.forEach(::recycleBitmapQuietly) }
        }
    }

    private fun clearBitmaps(recycleMode: BitmapRecycleMode) {
        bitmapRenderGeneration++
        stopBitmapRenderer()
        rendering.clear()
        val cache = bitmapCache
        if (cache.isEmpty()) return
        bitmapCache = IdentityHashMap()
        when (recycleMode) {
            BitmapRecycleMode.SYNC -> recycleBitmapsFromCache(cache)
            BitmapRecycleMode.ASYNC -> recycleScope.launch { recycleBitmapsFromCache(cache) }
        }
    }

    private fun evictOldestIfNeeded(frameId: Int, toRelease: MutableList<Bitmap>) {
        if (bitmapCache.size <= MAX_BITMAP_CACHE_ITEMS) return
        var needEvict = bitmapCache.size - MAX_BITMAP_CACHE_ITEMS
        // Avoid long stalls in onDraw; evict gradually.
        needEvict = needEvict.coerceAtMost(MAX_EVICT_PER_FRAME)
        repeat(needEvict) {
            var oldestKey: Danmaku? = null
            var oldestUse = Long.MAX_VALUE
            for ((k, v) in bitmapCache) {
                if (v.lastDrawFrameId == frameId) continue
                if (v.lastUseUptimeMs < oldestUse) {
                    oldestUse = v.lastUseUptimeMs
                    oldestKey = k
                }
            }
            val key = oldestKey ?: return
            val removed = bitmapCache.remove(key) ?: return
            toRelease.add(removed.bitmap)
        }
    }

    private fun stopBitmapRenderer() {
        bitmapRenderQueue?.close()
        bitmapRenderScope?.cancel()
        bitmapRenderQueue = null
        bitmapRenderScope = null
    }

    private enum class LayoutReset {
        NONE,
        SEEK_ONLY,
        SEEK_AND_CLEAR_BITMAPS,
    }

    private fun decideLayoutReset(prev: DanmakuConfig?, now: DanmakuConfig): LayoutReset {
        if (prev == null) return LayoutReset.NONE
        if (prev.textSizeSp != now.textSizeSp) return LayoutReset.SEEK_AND_CLEAR_BITMAPS
        if (prev.speedLevel != now.speedLevel) return LayoutReset.SEEK_ONLY
        if (prev.area != now.area) return LayoutReset.SEEK_ONLY
        return LayoutReset.NONE
    }

    private data class BitmapRequest(
        val danmaku: Danmaku,
        val textWidth: Float,
        val outlinePad: Float,
        val textSizePx: Float,
        val generation: Int,
    )

    private fun ensureBitmapRenderer() {
        if (bitmapRenderScope != null) return

        val queue = Channel<BitmapRequest>(capacity = BITMAP_QUEUE_CAPACITY)
        bitmapRenderQueue = queue
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        bitmapRenderScope = scope
        repeat(BITMAP_RENDER_WORKERS) {
            scope.launch {
                val renderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
                val renderStroke =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        typeface = Typeface.DEFAULT_BOLD
                        color = 0xCC000000.toInt()
                    }
                val renderEmotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
                val tmpRectF = RectF()
                val fm = Paint.FontMetrics()
                val renderCanvas = Canvas()
                try {
                    for (req in queue) {
                        debugStats.queueDequeued.incrementAndGet()
                        val created =
                            runCatching {
                                renderFill.textSize = req.textSizePx
                                renderStroke.textSize = req.textSizePx
                                renderFill.getFontMetrics(fm)
                                renderToBitmap(
                                    danmaku = req.danmaku,
                                    textWidth = req.textWidth,
                                    outlinePad = req.outlinePad,
                                    fontMetrics = fm,
                                    canvas = renderCanvas,
                                    fill = renderFill,
                                    stroke = renderStroke,
                                    emotePaint = renderEmotePaint,
                                    tmpRectF = tmpRectF,
                                )
                            }.getOrNull()
                        if (created == null) {
                            post { rendering.remove(req.danmaku) }
                            continue
                        }
                        post { commitRenderedBitmap(req, created) }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.w("DanmakuView", "bitmap renderer crashed", t)
                }
            }
        }
    }

    private fun renderToBitmap(
        danmaku: Danmaku,
        textWidth: Float,
        outlinePad: Float,
        fontMetrics: Paint.FontMetrics,
        canvas: Canvas,
        fill: Paint,
        stroke: Paint,
        emotePaint: Paint,
        tmpRectF: RectF,
    ): CachedBitmap? {
        val textBoxHeight = (fontMetrics.descent - fontMetrics.ascent) + outlinePad * 2f
        val w = max(1, ceil(textWidth.toDouble()).toInt())
        val h = max(1, ceil(textBoxHeight.toDouble()).toInt())

        val pooled = bitmapPool.acquire(w, h)
        val outBitmap = pooled ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (pooled != null) debugStats.bitmapReused.incrementAndGet() else debugStats.bitmapCreated.incrementAndGet()
        outBitmap.eraseColor(Color.TRANSPARENT)
        canvas.setBitmap(outBitmap)

        val color = (0xFF000000.toInt() or (danmaku.color and 0xFFFFFF))
        fill.color = color

        val x = outlinePad
        val baseline = outlinePad - fontMetrics.ascent
        val text = danmaku.text
        val segments = parseEmoteSegments(text)
        if (segments == null) {
            canvas.drawText(text, x, baseline, stroke)
            canvas.drawText(text, x, baseline, fill)
        } else {
            val emoteSizePx = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)
            val emoteTop = outlinePad
            var cursorX = x
            for (seg in segments) {
                when (seg) {
                    is EmoteSegment.Text -> {
                        if (seg.end > seg.start) {
                            canvas.drawText(text, seg.start, seg.end, cursorX, baseline, stroke)
                            canvas.drawText(text, seg.start, seg.end, cursorX, baseline, fill)
                            cursorX += fill.measureText(text, seg.start, seg.end)
                        }
                    }
                    is EmoteSegment.Emote -> {
                        val emoteBmp =
                            EmoteBitmapLoader.getCached(seg.url)
                                ?: run {
                                    canvas.setBitmap(null)
                                    val pooledBack = bitmapPool.tryPut(outBitmap)
                                    if (!pooledBack) recycleBitmapQuietly(outBitmap)
                                    return null
                                }
                        tmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawBitmap(emoteBmp, null, tmpRectF, emotePaint)
                        cursorX += emoteSizePx
                    }
                }
            }
        }
        canvas.setBitmap(null)

        return CachedBitmap(bitmap = outBitmap).apply {
            lastUseUptimeMs = SystemClock.uptimeMillis()
        }
    }

    private fun commitRenderedBitmap(req: BitmapRequest, created: CachedBitmap) {
        rendering.remove(req.danmaku)
        if (!isAttachedToWindow || req.generation != bitmapRenderGeneration) {
            releaseBitmapAsync(created.bitmap)
            return
        }

        val existing = bitmapCache[req.danmaku]
        if (existing != null) {
            releaseBitmapAsync(created.bitmap)
            return
        }

        created.lastUseUptimeMs = SystemClock.uptimeMillis()
        bitmapCache[req.danmaku] = created
        postInvalidateOnAnimationDanmakuArea()
    }

    private fun releaseBitmapAsync(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        recycleScope.launch {
            val pooled = bitmapPool.tryPut(bitmap)
            if (pooled) {
                debugStats.bitmapPutToPool.incrementAndGet()
            } else {
                debugStats.bitmapRecycled.incrementAndGet()
                recycleBitmapQuietly(bitmap)
            }
        }
    }

    private fun releaseBitmapsAsync(bitmaps: List<Bitmap>) {
        if (bitmaps.isEmpty()) return
        recycleScope.launch {
            for (b in bitmaps) {
                if (b.isRecycled) continue
                val pooled = bitmapPool.tryPut(b)
                if (pooled) {
                    debugStats.bitmapPutToPool.incrementAndGet()
                } else {
                    debugStats.bitmapRecycled.incrementAndGet()
                    recycleBitmapQuietly(b)
                }
            }
        }
    }

    private fun recycleBitmapQuietly(bitmap: Bitmap) {
        runCatching {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    override fun onDetachedFromWindow() {
        stopEngineThread()
        paceLogger.reset()
        smoothClock.reset(positionMs = 0L, nowUptimeMs = 0L)
        clearBitmaps(recycleMode = BitmapRecycleMode.ASYNC)
        emoteSegmentsCache.clear()
        recycleScope.launch { bitmapPool.clear() }
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnGlobalLayoutListener(insetsListener)
        }
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.addOnGlobalLayoutListener(insetsListener)
        }
        insetsDirty = true
    }

    private fun defaultConfig(): DanmakuConfig {
        val prefs = BiliClient.prefs
        return DanmakuConfig(
            enabled = prefs.danmakuEnabled,
            opacity = prefs.danmakuOpacity,
            textSizeSp = prefs.danmakuTextSizeSp,
            speedLevel = prefs.danmakuSpeed,
            area = prefs.danmakuArea,
        )
    }

    private companion object {
        private const val STOP_WHEN_IDLE_MS = 450L
        private const val IDLE_POLL_MS = 250L
        private const val WARMUP_FRAMES = 3
        private const val MAX_BITMAP_REQUESTS_PER_FRAME = 8
        private const val MAX_FALLBACK_TEXT_PER_FRAME = 16
        private const val BITMAP_QUEUE_CAPACITY = 96
        private const val BITMAP_RENDER_WORKERS = 2
        private const val MAX_EMOTE_SEGMENTS_CACHE_ITEMS = 1_500

        private const val PREFETCH_WINDOW_MS = 550
        private const val PREFETCH_MAX_CANDIDATES_PER_FRAME = 18
        private const val MAX_PREFETCH_REQUESTS_PER_FRAME = 4
        private const val PREFETCH_MAX_QUEUE_DEPTH = 64

        private const val BITMAP_IDLE_EVICT_MS = 1_200L
        private const val MAX_BITMAP_CACHE_ITEMS = 220
        private const val MAX_EVICT_PER_FRAME = 12

        private val recycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    private fun recordTickPace(params: TickParams) {
        if (!debugEnabled) return
        val now = SystemClock.uptimeMillis()
        val delay = (now - params.requestedAtUptimeMs).coerceAtLeast(0L)
        tickPaceExecuted.incrementAndGet()
        tickPaceDelayCount.incrementAndGet()
        tickPaceDelayTotalMs.addAndGet(delay)
        updateMax(tickPaceDelayMaxMs, delay)
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val cur = target.get()
            if (value <= cur) return
            if (target.compareAndSet(cur, value)) return
        }
    }

    private inner class PaceLogger {
        private var windowStartUptimeMs: Long = 0L
        private var lastLogUptimeMs: Long = 0L
        private var lastDrawUptimeMs: Long = 0L

        private var frameCount: Long = 0L

        private var drawIntervalCount: Long = 0L
        private var drawIntervalSumMs: Long = 0L
        private var drawIntervalMinMs: Long = Long.MAX_VALUE
        private var drawIntervalMaxMs: Long = 0L

        private var rawDeltaCount: Long = 0L
        private var rawDeltaSumAbsMs: Long = 0L
        private var rawDeltaMaxAbsMs: Long = 0L
        private var rawDeltaZeroCount: Long = 0L
        private var rawDeltaBackCount: Long = 0L
        private var rawDeltaJumpCount: Long = 0L
        private var rawDeltaJumpMaxAbsMs: Long = 0L

        private var smoothDeltaCount: Long = 0L
        private var smoothDeltaSumAbsMs: Long = 0L
        private var smoothDeltaMaxAbsMs: Long = 0L
        private var smoothDeltaZeroCount: Long = 0L

        private var driftCount: Long = 0L
        private var driftSumAbsMs: Long = 0L
        private var driftMaxAbsMs: Long = 0L

        private var snapAgeCount: Long = 0L
        private var snapAgeSumMs: Long = 0L
        private var snapAgeMaxMs: Long = 0L
        private var snapAgeNegCount: Long = 0L
        private var snapSameCount: Long = 0L
        private var lastSnapPosMs: Long? = null

        private var drawCostCount: Long = 0L
        private var drawCostSumNs: Long = 0L
        private var drawCostMaxNs: Long = 0L

        fun reset() {
            windowStartUptimeMs = 0L
            lastLogUptimeMs = 0L
            lastDrawUptimeMs = 0L

            frameCount = 0L

            drawIntervalCount = 0L
            drawIntervalSumMs = 0L
            drawIntervalMinMs = Long.MAX_VALUE
            drawIntervalMaxMs = 0L

            rawDeltaCount = 0L
            rawDeltaSumAbsMs = 0L
            rawDeltaMaxAbsMs = 0L
            rawDeltaZeroCount = 0L
            rawDeltaBackCount = 0L
            rawDeltaJumpCount = 0L
            rawDeltaJumpMaxAbsMs = 0L

            smoothDeltaCount = 0L
            smoothDeltaSumAbsMs = 0L
            smoothDeltaMaxAbsMs = 0L
            smoothDeltaZeroCount = 0L

            driftCount = 0L
            driftSumAbsMs = 0L
            driftMaxAbsMs = 0L

            snapAgeCount = 0L
            snapAgeSumMs = 0L
            snapAgeMaxMs = 0L
            snapAgeNegCount = 0L
            snapSameCount = 0L
            lastSnapPosMs = null

            drawCostCount = 0L
            drawCostSumNs = 0L
            drawCostMaxNs = 0L
        }

        fun onFrame(
            nowUptimeMs: Long,
            rawPositionMs: Long,
            prevRawPositionMs: Long,
            smoothPositionMs: Long,
            prevSmoothPositionMs: Long,
            snapshotPositionMs: Long,
        ) {
            if (windowStartUptimeMs == 0L) windowStartUptimeMs = nowUptimeMs
            frameCount++

            if (lastDrawUptimeMs != 0L) {
                val dt = (nowUptimeMs - lastDrawUptimeMs).coerceAtLeast(0L)
                drawIntervalCount++
                drawIntervalSumMs += dt
                if (dt < drawIntervalMinMs) drawIntervalMinMs = dt
                if (dt > drawIntervalMaxMs) drawIntervalMaxMs = dt
            }
            lastDrawUptimeMs = nowUptimeMs

            if (prevRawPositionMs != 0L) {
                val d = rawPositionMs - prevRawPositionMs
                val absD = abs(d)
                // Typical playback deltas are within ~0..40ms; use a generous threshold to exclude seeks.
                if (absD <= 250L) {
                    rawDeltaCount++
                    rawDeltaSumAbsMs += absD
                    if (absD > rawDeltaMaxAbsMs) rawDeltaMaxAbsMs = absD
                    if (d == 0L) rawDeltaZeroCount++
                    if (d < 0L) rawDeltaBackCount++
                } else {
                    rawDeltaJumpCount++
                    if (absD > rawDeltaJumpMaxAbsMs) rawDeltaJumpMaxAbsMs = absD
                }
            }

            if (snapshotPositionMs != 0L) {
                val age = smoothPositionMs - snapshotPositionMs
                if (age < 0L) {
                    snapAgeNegCount++
                } else if (age <= 2_500L) {
                    snapAgeCount++
                    snapAgeSumMs += age
                    if (age > snapAgeMaxMs) snapAgeMaxMs = age
                }

                val last = lastSnapPosMs
                if (last != null && last == snapshotPositionMs) {
                    snapSameCount++
                }
                lastSnapPosMs = snapshotPositionMs
            }

            if (prevSmoothPositionMs != 0L) {
                val d = smoothPositionMs - prevSmoothPositionMs
                val absD = abs(d)
                if (absD <= 250L) {
                    smoothDeltaCount++
                    smoothDeltaSumAbsMs += absD
                    if (absD > smoothDeltaMaxAbsMs) smoothDeltaMaxAbsMs = absD
                    if (d == 0L) smoothDeltaZeroCount++
                }
            }

            val drift = smoothPositionMs - rawPositionMs
            val absDrift = abs(drift)
            if (absDrift <= 2_500L) {
                driftCount++
                driftSumAbsMs += absDrift
                if (absDrift > driftMaxAbsMs) driftMaxAbsMs = absDrift
            }
        }

        fun onDrawCost(drawNs: Long) {
            // Ignore huge outliers from app backgrounding or debugger pauses.
            if (drawNs < 0L || drawNs > 500_000_000L) return
            drawCostCount++
            drawCostSumNs += drawNs
            if (drawNs > drawCostMaxNs) drawCostMaxNs = drawNs
        }

        fun maybeLog(nowUptimeMs: Long, rawPositionMs: Long, smoothPositionMs: Long, snapshot: RenderSnapshot) {
            val last = lastLogUptimeMs
            if (last != 0L && nowUptimeMs - last < 1_000L) return
            if (windowStartUptimeMs == 0L) return

            val spanMs = nowUptimeMs - windowStartUptimeMs
            if (spanMs < 900L) return
            lastLogUptimeMs = nowUptimeMs

            val dtAvg = if (drawIntervalCount > 0L) drawIntervalSumMs.toDouble() / drawIntervalCount.toDouble() else 0.0
            val dtMin = if (drawIntervalMinMs == Long.MAX_VALUE) 0L else drawIntervalMinMs

            val rawAvgAbs = if (rawDeltaCount > 0L) rawDeltaSumAbsMs.toDouble() / rawDeltaCount.toDouble() else 0.0
            val rawZeroPct = if (rawDeltaCount > 0L) (rawDeltaZeroCount.toDouble() * 100.0 / rawDeltaCount.toDouble()) else 0.0
            val smoothAvgAbs = if (smoothDeltaCount > 0L) smoothDeltaSumAbsMs.toDouble() / smoothDeltaCount.toDouble() else 0.0
            val smoothZeroPct =
                if (smoothDeltaCount > 0L) (smoothDeltaZeroCount.toDouble() * 100.0 / smoothDeltaCount.toDouble()) else 0.0
            val driftAvgAbs = if (driftCount > 0L) driftSumAbsMs.toDouble() / driftCount.toDouble() else 0.0
            val snapAgeAvg = if (snapAgeCount > 0L) snapAgeSumMs.toDouble() / snapAgeCount.toDouble() else 0.0
            val snapSamePct = if (frameCount > 0L) (snapSameCount.toDouble() * 100.0 / frameCount.toDouble()) else 0.0
            val drawCostAvgMs =
                if (drawCostCount > 0L) {
                    (drawCostSumNs.toDouble() / drawCostCount.toDouble()) / 1_000_000.0
                } else {
                    0.0
                }
            val drawCostMaxMs = drawCostMaxNs.toDouble() / 1_000_000.0

            val hz = display?.refreshRate?.takeIf { it > 0f }
            val hzText =
                if (hz != null) {
                    // Most devices report near-integers (e.g. 59.94); keep 1 decimal for visibility.
                    String.format(Locale.US, "%.1f", hz)
                } else {
                    "-"
                }

            val tickReq = tickPaceRequested.getAndSet(0L)
            val tickExec = tickPaceExecuted.getAndSet(0L)
            val tickCoal = tickPaceCoalesced.getAndSet(0L)
            val tickDelayN = tickPaceDelayCount.getAndSet(0L)
            val tickDelayTotal = tickPaceDelayTotalMs.getAndSet(0L)
            val tickDelayMax = tickPaceDelayMaxMs.getAndSet(0L)
            val tickDelayAvg = if (tickDelayN > 0L) (tickDelayTotal.toDouble() / tickDelayN.toDouble()) else 0.0

            AppLog.d(
                "DanmakuPace",
                buildString {
                    append("span=").append(spanMs).append("ms")
                    append(" raw=").append(rawPositionMs).append("ms")
                    append(" sm=").append(smoothPositionMs).append("ms")
                    append(" diff=").append(smoothPositionMs - rawPositionMs).append("ms")
                    append(" act=").append(snapshot.activeCount)
                    append(" pend=").append(snapshot.pendingCount)
                    append(" q=").append(debugStats.queueDepth())
                    append(" hz=").append(hzText)
                    append(" inv=")
                    if (lastInvalidateFull) {
                        append("full")
                    } else {
                        append(lastInvalidateTopPx).append('-').append(lastInvalidateBottomPx)
                    }
                    append(" area=").append(lastAreaInvalidateTopPx).append('-').append(lastAreaInvalidateBottomPx)
                    append(" | drawΔ=")
                    append(String.format(Locale.US, "%.1f", dtAvg))
                    append("ms(").append(dtMin).append('-').append(drawIntervalMaxMs).append(')')
                    append(" cost=")
                    append(String.format(Locale.US, "%.2f", drawCostAvgMs))
                    append("ms max=").append(String.format(Locale.US, "%.0f", drawCostMaxMs))
                    append(" rawΔ=")
                    append(String.format(Locale.US, "%.1f", rawAvgAbs))
                    append("ms max=").append(rawDeltaMaxAbsMs)
                    append(" z=").append(String.format(Locale.US, "%.0f", rawZeroPct)).append('%')
                    if (rawDeltaBackCount > 0L) append(" back=").append(rawDeltaBackCount)
                    if (rawDeltaJumpCount > 0L) append(" jump=").append(rawDeltaJumpCount).append(" max=").append(rawDeltaJumpMaxAbsMs)
                    append(" smΔ=")
                    append(String.format(Locale.US, "%.1f", smoothAvgAbs))
                    append("ms max=").append(smoothDeltaMaxAbsMs)
                    if (smoothDeltaCount > 0L) {
                        append(" z=").append(String.format(Locale.US, "%.0f", smoothZeroPct)).append('%')
                    }
                    append(" drift=")
                    append(String.format(Locale.US, "%.1f", driftAvgAbs))
                    append("ms max=").append(driftMaxAbsMs)
                    append(" snapAge=")
                    append(String.format(Locale.US, "%.1f", snapAgeAvg))
                    append("ms max=").append(snapAgeMaxMs)
                    if (snapAgeNegCount > 0L) append(" neg=").append(snapAgeNegCount)
                    append(" same=").append(String.format(Locale.US, "%.0f", snapSamePct)).append('%')
                    append(" tick r/e/c=").append(tickReq).append('/').append(tickExec).append('/').append(tickCoal)
                    append(" dly=")
                    append(String.format(Locale.US, "%.1f", tickDelayAvg))
                    append("ms max=").append(tickDelayMax)
                },
            )

            // Reset window counters but keep lastDrawUptimeMs and lastSnapPosMs for continuity.
            windowStartUptimeMs = nowUptimeMs
            frameCount = 0L

            drawIntervalCount = 0L
            drawIntervalSumMs = 0L
            drawIntervalMinMs = Long.MAX_VALUE
            drawIntervalMaxMs = 0L

            rawDeltaCount = 0L
            rawDeltaSumAbsMs = 0L
            rawDeltaMaxAbsMs = 0L
            rawDeltaZeroCount = 0L
            rawDeltaBackCount = 0L
            rawDeltaJumpCount = 0L
            rawDeltaJumpMaxAbsMs = 0L

            smoothDeltaCount = 0L
            smoothDeltaSumAbsMs = 0L
            smoothDeltaMaxAbsMs = 0L
            smoothDeltaZeroCount = 0L

            driftCount = 0L
            driftSumAbsMs = 0L
            driftMaxAbsMs = 0L

            snapAgeCount = 0L
            snapAgeSumMs = 0L
            snapAgeMaxMs = 0L
            snapAgeNegCount = 0L
            snapSameCount = 0L

            drawCostCount = 0L
            drawCostSumNs = 0L
            drawCostMaxNs = 0L
        }
    }

    private inner class EngineWorker {
        private val engine = DanmakuEngine()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val fm = Paint.FontMetrics()
        private var activeDanmakusBufferA: Array<Danmaku?> = emptyArray()
        private var activeXBufferA: FloatArray = FloatArray(0)
        private var activeYTopBufferA: FloatArray = FloatArray(0)
        private var activeTextWidthBufferA: FloatArray = FloatArray(0)
        private var prefetchDanmakusBufferA: Array<Danmaku?> = emptyArray()
        private var prefetchTextWidthBufferA: FloatArray = FloatArray(0)
        private var activeDanmakusBufferB: Array<Danmaku?> = emptyArray()
        private var activeXBufferB: FloatArray = FloatArray(0)
        private var activeYTopBufferB: FloatArray = FloatArray(0)
        private var activeTextWidthBufferB: FloatArray = FloatArray(0)
        private var prefetchDanmakusBufferB: Array<Danmaku?> = emptyArray()
        private var prefetchTextWidthBufferB: FloatArray = FloatArray(0)
        private var publishUseA: Boolean = true

        fun setDanmakus(list: List<Danmaku>) {
            engine.setDanmakus(list)
        }

        fun appendDanmakus(list: List<Danmaku>) {
            engine.appendDanmakus(list)
        }

        fun appendDanmakusSorted(list: List<Danmaku>) {
            engine.appendDanmakusSorted(list)
        }

        fun trimToMax(maxItems: Int) {
            engine.trimToMax(maxItems)
        }

        fun trimToTimeRange(minTimeMs: Int, maxTimeMs: Int) {
            engine.trimToTimeRange(minTimeMs, maxTimeMs)
        }

        fun seekTo(positionMs: Long) {
            engine.seekTo(positionMs)
        }

        fun tick(params: TickParams) {
            paint.textSize = params.textSizePx
            paint.getFontMetrics(fm)

            val t0 = if (debugEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            val active = engine.update(
                width = params.width,
                height = params.height,
                positionMs = params.positionMs,
                paint = paint,
                outlinePaddingPx = params.outlinePad,
                speedLevel = params.speedLevel,
                area = params.area,
                topInsetPx = params.topInsetPx,
                bottomInsetPx = params.bottomInsetPx,
            )
            val t1 = if (debugEnabled) SystemClock.elapsedRealtimeNanos() else 0L
            if (debugEnabled) {
                debugStats.recordUpdate(
                    updateNs = (t1 - t0).coerceAtLeast(0L),
                )
            }

            val useA = publishUseA
            val activeCount = active.size
            ensureActiveCapacity(activeCount, useA)
            val activeDanmakus = if (useA) activeDanmakusBufferA else activeDanmakusBufferB
            val activeX = if (useA) activeXBufferA else activeXBufferB
            val activeYTop = if (useA) activeYTopBufferA else activeYTopBufferB
            val activeTextWidth = if (useA) activeTextWidthBufferA else activeTextWidthBufferB
            for (i in 0 until activeCount) {
                val a = active[i]
                activeDanmakus[i] = a.danmaku
                activeX[i] = a.x
                activeYTop[i] = a.yTop
                activeTextWidth[i] = a.textWidth
            }

            val candidates =
                engine.peekUpcoming(
                    nowMs = params.positionMs.toInt(),
                    windowMs = PREFETCH_WINDOW_MS,
                    maxCount = PREFETCH_MAX_CANDIDATES_PER_FRAME,
                )
            ensurePrefetchCapacity(candidates.size, useA)
            val prefetchDanmakus = if (useA) prefetchDanmakusBufferA else prefetchDanmakusBufferB
            val prefetchTextWidth = if (useA) prefetchTextWidthBufferA else prefetchTextWidthBufferB
            var prefetchCount = 0
            for (d in candidates) {
                if (d.text.isBlank()) continue
                val tw = engine.measureTextWidth(danmaku = d, paint = paint, outlinePaddingPx = params.outlinePad)
                prefetchDanmakus[prefetchCount] = d
                prefetchTextWidth[prefetchCount] = tw
                prefetchCount++
            }

            publishUseA = !publishUseA

            renderSnapshot =
                RenderSnapshot(
                    positionMs = params.positionMs,
                    activeDanmakus = activeDanmakus,
                    activeX = activeX,
                    activeYTop = activeYTop,
                    activeTextWidth = activeTextWidth,
                    activeCount = activeCount,
                    pendingCount = engine.pendingCount(),
                    nextAtMs = engine.nextDanmakuTimeMs(),
                    prefetchDanmakus = prefetchDanmakus,
                    prefetchTextWidth = prefetchTextWidth,
                    prefetchCount = prefetchCount,
                )
        }

        private fun ensureActiveCapacity(required: Int, useA: Boolean) {
            if (required <= 0) return
            if (useA) {
                if (activeDanmakusBufferA.size < required) activeDanmakusBufferA = arrayOfNulls(required)
                if (activeXBufferA.size < required) activeXBufferA = FloatArray(required)
                if (activeYTopBufferA.size < required) activeYTopBufferA = FloatArray(required)
                if (activeTextWidthBufferA.size < required) activeTextWidthBufferA = FloatArray(required)
            } else {
                if (activeDanmakusBufferB.size < required) activeDanmakusBufferB = arrayOfNulls(required)
                if (activeXBufferB.size < required) activeXBufferB = FloatArray(required)
                if (activeYTopBufferB.size < required) activeYTopBufferB = FloatArray(required)
                if (activeTextWidthBufferB.size < required) activeTextWidthBufferB = FloatArray(required)
            }
        }

        private fun ensurePrefetchCapacity(required: Int, useA: Boolean) {
            if (required <= 0) return
            if (useA) {
                if (prefetchDanmakusBufferA.size < required) prefetchDanmakusBufferA = arrayOfNulls(required)
                if (prefetchTextWidthBufferA.size < required) prefetchTextWidthBufferA = FloatArray(required)
            } else {
                if (prefetchDanmakusBufferB.size < required) prefetchDanmakusBufferB = arrayOfNulls(required)
                if (prefetchTextWidthBufferB.size < required) prefetchTextWidthBufferB = FloatArray(required)
            }
        }
    }

    private class BitmapPool(
        private val maxBytes: Long,
        private val maxCount: Int = 72,
    ) {
        private val pool = ArrayDeque<Bitmap>()
        private var pooledBytes: Long = 0L

        @Synchronized
        fun acquire(minWidth: Int, minHeight: Int): Bitmap? {
            if (pool.isEmpty()) return null
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                if (b.isRecycled) {
                    it.remove()
                    continue
                }
                if (!isReusable(b, minWidth, minHeight)) continue
                it.remove()
                pooledBytes -= b.allocationByteCount.toLong()
                return b
            }
            return null
        }

        @Synchronized
        fun tryPut(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled) return true
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes <= 0L) return false
            if (bytes > maxBytes) return false
            if (pool.size >= maxCount) return false
            if (pooledBytes + bytes > maxBytes) return false
            pool.addLast(bitmap)
            pooledBytes += bytes
            return true
        }

        @Synchronized
        fun clear() {
            val it = pool.iterator()
            while (it.hasNext()) {
                val b = it.next()
                it.remove()
                runCatching {
                    if (!b.isRecycled) b.recycle()
                }
            }
            pooledBytes = 0L
        }

        @Synchronized
        fun snapshot(): PoolSnapshot = PoolSnapshot(count = pool.size, bytes = pooledBytes, maxBytes = maxBytes)

        private fun isReusable(bitmap: Bitmap, minWidth: Int, minHeight: Int): Boolean {
            if (bitmap.config != Bitmap.Config.ARGB_8888) return false
            if (bitmap.width < minWidth || bitmap.height < minHeight) return false
            val dw = bitmap.width - minWidth
            val dh = bitmap.height - minHeight
            // Avoid reusing a huge bitmap for a tiny danmaku, which wastes memory/bandwidth.
            return dw <= 48 && dh <= 24
        }
    }

    private data class PoolSnapshot(
        val count: Int,
        val bytes: Long,
        val maxBytes: Long,
    )

    private class DebugStatsCollector {
        val bitmapCreated = AtomicLong()
        val bitmapReused = AtomicLong()
        val bitmapPutToPool = AtomicLong()
        val bitmapRecycled = AtomicLong()

        val queueEnqueued = AtomicLong()
        val queueDequeued = AtomicLong()

        val requestsActive = AtomicLong()
        val requestsPrefetch = AtomicLong()
        val requestsDropped = AtomicLong()

        private val lastDrawAtMs = AtomicLong()
        @Volatile private var smoothedDrawFps: Float = 0f

        private val updateNsTotal = AtomicLong()
        private val updateNsMax = AtomicLong()
        private val drawNsTotal = AtomicLong()
        private val drawNsMax = AtomicLong()
        private val updateCount = AtomicLong()
        private val drawCount = AtomicLong()

        @Volatile var lastFrameActive: Int = 0
        @Volatile var lastFramePending: Int = 0
        @Volatile var lastFrameCachedDrawn: Int = 0
        @Volatile var lastFrameFallbackDrawn: Int = 0
        @Volatile var lastFrameRequestsActive: Int = 0
        @Volatile var lastFrameRequestsPrefetch: Int = 0

        fun reset() {
            bitmapCreated.set(0L)
            bitmapReused.set(0L)
            bitmapPutToPool.set(0L)
            bitmapRecycled.set(0L)
            queueEnqueued.set(0L)
            queueDequeued.set(0L)
            requestsActive.set(0L)
            requestsPrefetch.set(0L)
            requestsDropped.set(0L)
            lastDrawAtMs.set(0L)
            smoothedDrawFps = 0f
            updateNsTotal.set(0L)
            updateNsMax.set(0L)
            drawNsTotal.set(0L)
            drawNsMax.set(0L)
            updateCount.set(0L)
            drawCount.set(0L)
            lastFrameActive = 0
            lastFramePending = 0
            lastFrameCachedDrawn = 0
            lastFrameFallbackDrawn = 0
            lastFrameRequestsActive = 0
            lastFrameRequestsPrefetch = 0
        }

        fun recordUpdate(
            updateNs: Long,
        ) {
            updateCount.incrementAndGet()
            updateNsTotal.addAndGet(updateNs)
            updateMax(updateNsMax, updateNs)
        }

        fun recordDraw(
            nowUptimeMs: Long,
            drawNs: Long,
            active: Int,
            pending: Int,
            cachedDrawn: Int,
            fallbackDrawn: Int,
            requestsActive: Int,
            requestsPrefetch: Int,
        ) {
            updateDrawFps(nowUptimeMs)
            drawCount.incrementAndGet()
            drawNsTotal.addAndGet(drawNs)
            updateMax(drawNsMax, drawNs)
            lastFrameActive = active
            lastFramePending = pending
            lastFrameCachedDrawn = cachedDrawn
            lastFrameFallbackDrawn = fallbackDrawn
            lastFrameRequestsActive = requestsActive
            lastFrameRequestsPrefetch = requestsPrefetch
        }

        fun drawFps(nowUptimeMs: Long): Float {
            val last = lastDrawAtMs.get()
            if (last == 0L) return 0f
            if (nowUptimeMs - last > 1_000L) return 0f
            return smoothedDrawFps
        }

        private fun updateDrawFps(nowUptimeMs: Long) {
            val prev = lastDrawAtMs.getAndSet(nowUptimeMs)
            if (prev == 0L) return
            val deltaMs = nowUptimeMs - prev
            if (deltaMs <= 0L) return
            val inst = 1000f / deltaMs.toFloat()
            val cur = smoothedDrawFps
            smoothedDrawFps = if (cur <= 0f) inst else (cur * 0.85f + inst * 0.15f)
        }

        fun queueDepth(): Int {
            val d = queueEnqueued.get() - queueDequeued.get()
            return d.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

        fun avgUpdateMs(): Float {
            val n = updateCount.get().coerceAtLeast(1L)
            return (updateNsTotal.get().toDouble() / n / 1_000_000.0).toFloat()
        }

        fun maxUpdateMs(): Float = (updateNsMax.get().toDouble() / 1_000_000.0).toFloat()

        fun avgDrawMs(): Float {
            val n = drawCount.get().coerceAtLeast(1L)
            return (drawNsTotal.get().toDouble() / n / 1_000_000.0).toFloat()
        }

        fun maxDrawMs(): Float = (drawNsMax.get().toDouble() / 1_000_000.0).toFloat()

        private fun updateMax(target: AtomicLong, value: Long) {
            while (true) {
                val cur = target.get()
                if (value <= cur) return
                if (target.compareAndSet(cur, value)) return
            }
        }
    }

    private fun defaultBitmapPoolMaxBytes(): Long {
        val dm = resources.displayMetrics
        val screenBytes = dm.widthPixels.toLong() * dm.heightPixels.toLong() * 4L
        val min = 12L * 1024L * 1024L
        val max = 64L * 1024L * 1024L
        // Raise pool headroom for dense danmaku scenes; still clamp to avoid runaway memory use.
        return (screenBytes * 3L).coerceIn(min, max)
    }
}
