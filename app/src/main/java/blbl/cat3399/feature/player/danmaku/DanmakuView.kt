package blbl.cat3399.feature.player.danmaku

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import java.util.IdentityHashMap
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
        isFilterBitmap = true
    }

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
    private var configProvider: (() -> DanmakuConfig)? = null
    private var lastPositionMs: Long = 0L
    private var lastDrawUptimeMs: Long = 0L
    private var lastPositionChangeUptimeMs: Long = 0L
    private var lastLayoutConfig: DanmakuConfig? = null
    private var drawFrameId: Int = 0
    private var warmupFrames: Int = 0
    private var cachedTopInsetPx: Int = dp(2f)
    private var cachedBottomInsetPx: Int = dp(52f)
    private var insetsDirty: Boolean = true

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
        val textSizePx: Float,
        val outlinePad: Float,
        val speedLevel: Int,
        val area: Float,
        val topInsetPx: Int,
        val bottomInsetPx: Int,
    )

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
        val updateAvgMs: Float,
        val updateMaxMs: Float,
        val drawAvgMs: Float,
        val drawMaxMs: Float,
    )

    fun setDebugEnabled(enabled: Boolean) {
        if (debugEnabled == enabled) return
        debugEnabled = enabled
        debugStats.reset()
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
            updateAvgMs = debugStats.avgUpdateMs(),
            updateMaxMs = debugStats.maxUpdateMs(),
            drawAvgMs = debugStats.avgDrawMs(),
            drawMaxMs = debugStats.maxDrawMs(),
        )
    }

    fun setPositionProvider(provider: () -> Long) {
        positionProvider = provider
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
        latestTick.set(params)
        val handler = ensureEngineHandler()
        scheduleTickIfNeeded(handler)
    }

    private fun scheduleTickIfNeeded(handler: Handler) {
        if (!tickPending.compareAndSet(false, true)) return
        handler.post { runTickOnce(handler) }
    }

    private fun runTickOnce(handler: Handler) {
        try {
            val params = latestTick.getAndSet(null) ?: return
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
            return
        }

        val prevPositionMs = lastPositionMs
        val positionMs = provider()
        val now = SystemClock.uptimeMillis()
        if (lastDrawUptimeMs == 0L) lastDrawUptimeMs = now
        if (lastPositionChangeUptimeMs == 0L) lastPositionChangeUptimeMs = now

        if (positionMs != prevPositionMs) {
            lastPositionChangeUptimeMs = now
        }
        lastPositionMs = positionMs
        lastDrawUptimeMs = now

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
        val topInsetPx = safeTopInsetPx()
        val bottomInsetPx = safeBottomInsetPx()

        val snapshotBeforeDraw = renderSnapshot
        requestEngineTick(
            TickParams(
                width = width,
                height = height,
                positionMs = positionMs,
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
            val x = snapshotBeforeDraw.activeX.getOrNull(i) ?: continue
            val yTop = snapshotBeforeDraw.activeYTop.getOrNull(i) ?: continue
            val textWidth = snapshotBeforeDraw.activeTextWidth.getOrNull(i) ?: continue
            val cached = bitmapCache[d]
            if (cached != null) {
                cached.lastDrawFrameId = frameId
                cached.lastUseUptimeMs = now
                canvas.drawBitmap(cached.bitmap, x, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }

            if (requested < MAX_BITMAP_REQUESTS_PER_FRAME && rendering[d] != true) {
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
        }

        // If playback time hasn't moved for a while, stop the loop to avoid wasting 60fps while paused/buffering.
        // PlayerActivity kicks `invalidate()` on resume/play state changes.
        if (now - lastPositionChangeUptimeMs >= STOP_WHEN_IDLE_MS) {
            postInvalidateDelayed(IDLE_POLL_MS)
            return
        }

        // Keep vsync loop while we have active danmaku; otherwise schedule lazily.
        if (activeCount > 0 || snapshotBeforeDraw.pendingCount > 0) {
            postInvalidateOnAnimation()
            return
        }
        val nextAt = snapshotBeforeDraw.nextAtMs
        if (nextAt != null && nextAt <= positionMs + 250) {
            postInvalidateOnAnimation()
            return
        }
        if (nextAt != null && nextAt > positionMs) {
            val delay = (nextAt - positionMs).coerceAtMost(750L)
            postInvalidateDelayed(delay)
            return
        }
        if (warmupFrames > 0) {
            warmupFrames--
            postInvalidateOnAnimation()
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

    private fun drawTextFallback(
        canvas: Canvas,
        danmaku: Danmaku,
        x: Float,
        yTop: Float,
        outlinePad: Float,
        baselineOffset: Float,
        opacityAlpha: Int,
    ) {
        if (danmaku.text.isBlank()) return

        val rgb = danmaku.color and 0xFFFFFF
        val strokeAlpha = ((opacityAlpha * 0xCC) / 255).coerceIn(0, 255)
        stroke.color = (strokeAlpha shl 24) or 0x000000
        fill.color = (opacityAlpha shl 24) or rgb

        val textX = x + outlinePad
        val baseline = yTop + baselineOffset
        canvas.drawText(danmaku.text, textX, baseline, stroke)
        canvas.drawText(danmaku.text, textX, baseline, fill)
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
    ): CachedBitmap {
        val textBoxHeight = (fontMetrics.descent - fontMetrics.ascent) + outlinePad * 2f
        val w = max(1, ceil(textWidth.toDouble()).toInt())
        val h = max(1, ceil(textBoxHeight.toDouble()).toInt())

        val pooled = bitmapPool.acquire(w, h)
        val bmp = pooled ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (pooled != null) debugStats.bitmapReused.incrementAndGet() else debugStats.bitmapCreated.incrementAndGet()
        bmp.eraseColor(Color.TRANSPARENT)
        canvas.setBitmap(bmp)

        val color = (0xFF000000.toInt() or (danmaku.color and 0xFFFFFF))
        fill.color = color

        val x = outlinePad
        val baseline = outlinePad - fontMetrics.ascent
        canvas.drawText(danmaku.text, x, baseline, stroke)
        canvas.drawText(danmaku.text, x, baseline, fill)
        canvas.setBitmap(null)

        return CachedBitmap(bitmap = bmp).apply {
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
        postInvalidateOnAnimation()
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
        clearBitmaps(recycleMode = BitmapRecycleMode.ASYNC)
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

        private const val PREFETCH_WINDOW_MS = 550
        private const val PREFETCH_MAX_CANDIDATES_PER_FRAME = 18
        private const val MAX_PREFETCH_REQUESTS_PER_FRAME = 4
        private const val PREFETCH_MAX_QUEUE_DEPTH = 64

        private const val BITMAP_IDLE_EVICT_MS = 1_200L
        private const val MAX_BITMAP_CACHE_ITEMS = 220
        private const val MAX_EVICT_PER_FRAME = 12

        private val recycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
