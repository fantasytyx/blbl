package blbl.cat3399.feature.player.danmaku

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import blbl.cat3399.core.emote.EmoteBitmapLoader
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.feature.player.danmaku.model.DanmakuCacheState
import blbl.cat3399.feature.player.danmaku.model.DanmakuItem
import blbl.cat3399.feature.player.danmaku.model.DanmakuEmoteSegment
import blbl.cat3399.feature.player.danmaku.model.DanmakuKind
import blbl.cat3399.feature.player.danmaku.model.RenderSnapshot
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * AkDanmaku-inspired engine:
 * - Runs act/update on ActionThread.
 * - Draw runs on main thread using an immutable RenderSnapshot (double-buffered).
 *
 * CacheManager integration is added in a later step; this first version keeps a fast direct-draw path.
 */
internal class DanmakuEngine(
    private val displayMetrics: DisplayMetrics,
    private val cacheManager: CacheManager,
) {
    private val density: Float = displayMetrics.density.takeIf { it.isFinite() && it > 0f } ?: 1f
    // ---- Data ----
    private var items: MutableList<DanmakuItem> = mutableListOf()
    private var index: Int = 0
    private val active: ArrayList<DanmakuItem> = ArrayList(64)
    private val pending: ArrayDeque<PendingSpawn> = ArrayDeque()

    // Monotonic time within a session (action thread).
    private var lastNowMs: Int = 0

    // ---- Viewport / Config (action thread writes; main reads) ----
    @Volatile private var viewportWidth: Int = 0
    @Volatile private var viewportHeight: Int = 0
    @Volatile private var viewportTopInsetPx: Int = 0
    @Volatile private var viewportBottomInsetPx: Int = 0

    @Volatile private var config: DanmakuConfig = DanmakuConfig(enabled = true, opacity = 1f, textSizeSp = 18f, speedLevel = 4, area = 1f)

    @Volatile private var textSizePx: Float = sp(18f)
    @Volatile private var strokeWidthPx: Float = 4f
    @Volatile private var outlinePadPx: Float = 2f

    @Volatile private var cacheStyleGeneration: Int = 0

    @Volatile private var lastDrawCachedCount: Int = 0
    @Volatile private var lastDrawFallbackCount: Int = 0

    fun lastDrawCachedCount(): Int = lastDrawCachedCount

    fun lastDrawFallbackCount(): Int = lastDrawFallbackCount

    // ---- Time (main writes; action reads) ----
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var currentUiFrameId: Int = 0

    // ---- Render snapshot (double buffer) ----
    private val snapshotA = RenderSnapshot()
    private val snapshotB = RenderSnapshot()
    @Volatile private var latestSnapshot: RenderSnapshot = snapshotA

    // ---- Layout scratch (action thread only) ----
    private val actionFontMetrics = Paint.FontMetrics()
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private var laneLastScroll: Array<DanmakuItem?> = emptyArray()
    private var laneLastScrollTail: FloatArray = FloatArray(0)
    private var laneLastTop: Array<DanmakuItem?> = emptyArray()
    private var laneLastBottom: Array<DanmakuItem?> = emptyArray()

    // ---- Draw (main thread only) ----
    private val drawFontMetrics = Paint.FontMetrics()
    private val drawFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        isSubpixelText = true
    }
    private val drawStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        isSubpixelText = true
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Smooth-first by default for TVs (may be adjusted by overload strategies later).
        isFilterBitmap = true
    }
    private val emotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val emotePlaceholderFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emotePlaceholderStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density) // ~1dp
    }
    private val emoteTmpRectF = RectF()

    fun updateViewport(width: Int, height: Int, topInsetPx: Int, bottomInsetPx: Int) {
        viewportWidth = width.coerceAtLeast(0)
        viewportHeight = height.coerceAtLeast(0)
        viewportTopInsetPx = topInsetPx.coerceAtLeast(0)
        viewportBottomInsetPx = bottomInsetPx.coerceAtLeast(0)
    }

    fun updateConfig(newConfig: DanmakuConfig) {
        config = newConfig
        val tsPx = sp(newConfig.textSizeSp).coerceAtLeast(1f)
        val oldTs = textSizePx
        textSizePx = tsPx
        strokeWidthPx = 4f
        outlinePadPx = max(1f, strokeWidthPx / 2f)
        actionPaint.textSize = tsPx

        if (oldTs != tsPx) {
            cacheStyleGeneration++
            // Invalidate current caches to avoid mixing sizes.
            val releaseAt = currentUiFrameId + 1
            for (a in active) {
                val bmp = a.cacheBitmap
                if (bmp != null) {
                    cacheManager.enqueueRelease(bmp, releaseAtFrameId = releaseAt)
                    a.cacheBitmap = null
                }
                a.cacheState = DanmakuCacheState.Init
                a.cacheGeneration = -1
            }
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, displayMetrics)

    fun stepTime(positionMs: Long, uiFrameId: Int) {
        currentPositionMs = positionMs.coerceAtLeast(0L)
        currentUiFrameId = uiFrameId
    }

    fun currentPositionMs(): Long = currentPositionMs

    fun drainReleasedBitmaps(uiFrameId: Int) {
        cacheManager.drainReleasedBitmaps(uiFrameId)
    }

    fun preAct() {
        // Reserved for future: cache prefetch / op coalescing.
    }

    fun act() {
        val cfg = config
        if (!cfg.enabled) {
            clearActives()
            publishEmptySnapshot()
            return
        }

        val width = viewportWidth
        val height = viewportHeight
        if (width <= 0 || height <= 0) {
            clearActives()
            publishEmptySnapshot()
            return
        }

        val outlinePad = outlinePadPx
        val rawNowMs = currentPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val nowMs = if (rawNowMs >= lastNowMs) rawNowMs else lastNowMs
        lastNowMs = nowMs

        val topInset = viewportTopInsetPx.coerceIn(0, height)
        val bottomInset = viewportBottomInsetPx.coerceIn(0, height - topInset)
        val availableHeight = (height - topInset - bottomInset).coerceAtLeast(0)

        actionPaint.textSize = textSizePx
        actionPaint.getFontMetrics(actionFontMetrics)
        val textBoxHeight = (actionFontMetrics.descent - actionFontMetrics.ascent) + outlinePad * 2f
        val laneHeight = max(18f, textBoxHeight * 1.15f)
        val usableHeight = (availableHeight * cfg.area.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
        val laneCount = max(1, (usableHeight / laneHeight).toInt())

        val rollingDurationMs = computeRollingDurationMs(speedLevel = cfg.speedLevel)
        val fixedDurationMs = FIXED_DURATION_MS

        pruneExpired(width, nowMs)
        skipOld(nowMs, rollingDurationMs)
        dropIfLagging(nowMs)

        ensureLaneBuffers(laneCount)
        Arrays.fill(laneLastScroll, 0, laneCount, null)
        Arrays.fill(laneLastScrollTail, 0, laneCount, Float.NEGATIVE_INFINITY)
        Arrays.fill(laneLastTop, 0, laneCount, null)
        Arrays.fill(laneLastBottom, 0, laneCount, null)
        for (a in active) {
            if (a.lane !in 0 until laneCount) continue
            when (a.kind) {
                DanmakuKind.SCROLL -> {
                    val cur = laneLastScroll[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) laneLastScroll[a.lane] = a
                }
                DanmakuKind.TOP -> {
                    val cur = laneLastTop[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) laneLastTop[a.lane] = a
                }
                DanmakuKind.BOTTOM -> {
                    val cur = laneLastBottom[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) laneLastBottom[a.lane] = a
                }
            }
        }
        for (lane in 0 until laneCount) {
            val a = laneLastScroll[lane] ?: continue
            val x = scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
            laneLastScrollTail[lane] = x + a.textWidthPx
        }

        val marginPx = max(12f, (textSizePx + outlinePad * 2f) * 0.6f)

        fun kindOf(d: Danmaku): DanmakuKind =
            when (d.mode) {
                5 -> DanmakuKind.TOP
                4 -> DanmakuKind.BOTTOM
                else -> DanmakuKind.SCROLL
            }

        fun trySpawnScroll(item: DanmakuItem, textWidth: Float): Boolean {
            if (item.data.text.isBlank()) return true
            val pxNew = (width + textWidth) / rollingDurationMs.toFloat()
            for (lane in 0 until laneCount) {
                val prev = laneLastScroll[lane]
                if (prev == null) {
                    activate(item, DanmakuKind.SCROLL, lane, textWidth, pxNew, rollingDurationMs, startTimeMs = nowMs)
                    laneLastScroll[lane] = item
                    laneLastScrollTail[lane] = width.toFloat() + textWidth
                    return true
                }
                val tailPrev = laneLastScrollTail[lane]
                if (isScrollLaneAvailable(width.toFloat(), nowMs, prev, tailPrev, pxNew, marginPx)) {
                    activate(item, DanmakuKind.SCROLL, lane, textWidth, pxNew, rollingDurationMs, startTimeMs = nowMs)
                    laneLastScroll[lane] = item
                    laneLastScrollTail[lane] = width.toFloat() + textWidth
                    return true
                }
            }
            return false
        }

        fun trySpawnFixed(kind: DanmakuKind, item: DanmakuItem, textWidth: Float): Boolean {
            if (item.data.text.isBlank()) return true
            val lanes =
                when (kind) {
                    DanmakuKind.TOP -> laneLastTop
                    DanmakuKind.BOTTOM -> laneLastBottom
                    else -> return false
                }
            for (lane in 0 until laneCount) {
                val prev = lanes[lane]
                if (prev == null) {
                    activate(item, kind, lane, textWidth, pxPerMs = 0f, durationMs = fixedDurationMs, startTimeMs = nowMs)
                    lanes[lane] = item
                    return true
                }
                val elapsedPrev = nowMs - prev.startTimeMs
                if (elapsedPrev >= prev.durationMs) {
                    activate(item, kind, lane, textWidth, pxPerMs = 0f, durationMs = fixedDurationMs, startTimeMs = nowMs)
                    lanes[lane] = item
                    return true
                }
            }
            return false
        }

        // Retry pending items first.
        if (pending.isNotEmpty()) {
            val pendingCount = pending.size
            var processed = 0
            var i = 0
            while (i < pendingCount && pending.isNotEmpty()) {
                val p = pending.removeFirst()
                i++
                if (p.nextTryMs > nowMs) {
                    pending.addLast(p)
                    continue
                }
                if (processed >= MAX_PENDING_RETRY_PER_FRAME) {
                    pending.addLast(p)
                    continue
                }
                processed++
                val ok =
                    when (p.kind) {
                        DanmakuKind.SCROLL -> trySpawnScroll(p.item, p.textWidthPx)
                        DanmakuKind.TOP -> trySpawnFixed(DanmakuKind.TOP, p.item, p.textWidthPx)
                        DanmakuKind.BOTTOM -> trySpawnFixed(DanmakuKind.BOTTOM, p.item, p.textWidthPx)
                    }
                if (ok) continue
                val age = nowMs - p.firstTryMs
                if (age <= MAX_DELAY_MS) {
                    p.nextTryMs = nowMs + DELAY_STEP_MS
                    pending.addLast(p)
                }
            }
        }

        // Spawn new items.
        var spawnAttempts = 0
        while (index < items.size && items[index].timeMs() <= nowMs) {
            if (spawnAttempts >= MAX_SPAWN_PER_FRAME) break
            val item = items[index]
            index++
            spawnAttempts++
            if (item.data.text.isBlank()) continue
            val textWidth = measureTextWidth(item, outlinePad)
            val kind = kindOf(item.data)
            val ok =
                when (kind) {
                    DanmakuKind.SCROLL -> trySpawnScroll(item, textWidth)
                    DanmakuKind.TOP -> trySpawnFixed(DanmakuKind.TOP, item, textWidth)
                    DanmakuKind.BOTTOM -> trySpawnFixed(DanmakuKind.BOTTOM, item, textWidth)
                }
            if (ok) continue
            enqueuePending(kind = kind, item = item, textWidth = textWidth, nowMs = nowMs)
        }

        // Update positions.
        val maxYTop = (topInset + usableHeight - textBoxHeight).toFloat().coerceAtLeast(topInset.toFloat())
        for (a in active) {
            when (a.kind) {
                DanmakuKind.SCROLL -> {
                    // Position is computed into render snapshot; keep a.textWidthPx updated.
                }
                else -> Unit
            }
            a.textWidthPx = a.textWidthPx.coerceAtLeast(0f)
        }

        // Request cache builds for visible items (budgeted).
        val style =
            CacheStyle(
                textSizePx = textSizePx,
                strokeWidthPx = strokeWidthPx,
                outlinePadPx = outlinePad,
                generation = cacheStyleGeneration,
            )
        val releaseAtFrameId = currentUiFrameId + 1
        var requested = 0
        if (cacheManager.queueDepth() < MAX_CACHE_QUEUE_DEPTH) {
            for (a in active) {
                if (requested >= MAX_CACHE_REQUESTS_PER_FRAME) break
                val bmp = a.cacheBitmap
                val hasValidCache = bmp != null && !bmp.isRecycled && a.cacheGeneration == style.generation
                if (hasValidCache) continue
                if (a.cacheState == DanmakuCacheState.Rendering) continue
                if (!emotesReadyOrPrefetch(a)) continue
                a.cacheState = DanmakuCacheState.Rendering
                cacheManager.requestBuildCache(
                    item = a,
                    textWidthPx = a.textWidthPx,
                    style = style,
                    releaseAtFrameId = releaseAtFrameId,
                )
                requested++
                if (cacheManager.queueDepth() >= MAX_CACHE_QUEUE_DEPTH) break
            }
        }

        // Publish snapshot.
        val out = writableSnapshot()
        out.ensureCapacity(active.size)
        out.positionMs = nowMs.toLong()
        out.count = 0
        out.pendingCount = pending.size
        out.nextAtMs = items.getOrNull(index)?.timeMs()

        for (a in active) {
            val iOut = out.count
            val x =
                when (a.kind) {
                    DanmakuKind.SCROLL -> scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
                    DanmakuKind.TOP -> centerX(width = width, contentWidth = a.textWidthPx)
                    DanmakuKind.BOTTOM -> centerX(width = width, contentWidth = a.textWidthPx)
                }
            val yTop =
                when (a.kind) {
                    DanmakuKind.SCROLL -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                    DanmakuKind.TOP -> (topInset.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                    DanmakuKind.BOTTOM -> (maxYTop - laneHeight * a.lane).coerceAtLeast(topInset.toFloat())
                }
            out.items[iOut] = a
            out.x[iOut] = x
            out.yTop[iOut] = yTop
            out.textWidth[iOut] = a.textWidthPx
            out.count = iOut + 1
        }

        latestSnapshot = out
    }

    fun renderSnapshot(): RenderSnapshot = latestSnapshot

    fun draw(canvas: Canvas, snapshot: RenderSnapshot, config: DanmakuConfig) {
        val cfg = config
        if (!cfg.enabled) return

        // Update draw paints lazily (main thread).
        val ts = textSizePx
        if (drawFill.textSize != ts) {
            drawFill.textSize = ts
            drawStroke.textSize = ts
        }
        if (drawStroke.strokeWidth != strokeWidthPx) {
            drawStroke.strokeWidth = strokeWidthPx
        }

        val outlinePad = outlinePadPx
        val opacityAlpha = (cfg.opacity * 255f).roundToInt().coerceIn(0, 255)
        bitmapPaint.alpha = opacityAlpha
        emotePaint.alpha = opacityAlpha
        run {
            val fillA = ((opacityAlpha * 0x22) / 255).coerceIn(0, 255)
            val strokeA = ((opacityAlpha * 0x66) / 255).coerceIn(0, 255)
            emotePlaceholderFill.color = (fillA shl 24) or 0x000000
            emotePlaceholderStroke.color = (strokeA shl 24) or 0xFFFFFF
        }

        drawFill.getFontMetrics(drawFontMetrics)
        val baselineOffset = outlinePad - drawFontMetrics.ascent
        val emoteSizePx = (drawFontMetrics.descent - drawFontMetrics.ascent).coerceAtLeast(1f)
        val styleGen = cacheStyleGeneration

        var cachedDrawn = 0
        var fallbackDrawn = 0
        for (i in 0 until snapshot.count) {
            val item = snapshot.items[i] ?: continue
            val x = snapshot.x[i]
            val yTop = snapshot.yTop[i]
            val bmp = item.cacheBitmap
            if (bmp != null && !bmp.isRecycled && item.cacheGeneration == styleGen) {
                canvas.drawBitmap(bmp, x, yTop, bitmapPaint)
                cachedDrawn++
                continue
            }
            fallbackDrawn++
            drawTextDirect(
                canvas = canvas,
                item = item,
                x = x,
                yTop = yTop,
                outlinePad = outlinePad,
                baselineOffset = baselineOffset,
                opacityAlpha = opacityAlpha,
                emoteSizePx = emoteSizePx,
            )
        }
        lastDrawCachedCount = cachedDrawn
        lastDrawFallbackCount = fallbackDrawn
    }

    fun setDanmakus(list: List<Danmaku>) {
        clearActives()
        items =
            list
                .sortedBy { it.timeMs }
                .mapTo(ArrayList(list.size.coerceAtLeast(0))) { DanmakuItem(it) }
        index = 0
        lastNowMs = 0
        publishEmptySnapshot()
    }

    fun appendDanmakus(list: List<Danmaku>, alreadySorted: Boolean) {
        if (list.isEmpty()) return
        if (items.isEmpty()) {
            setDanmakus(list)
            return
        }
        val newItems =
            if (alreadySorted) {
                list
            } else {
                list.sortedBy { it.timeMs }
            }
        val lastTime = items.lastOrNull()?.timeMs() ?: Int.MIN_VALUE
        if (newItems.firstOrNull()?.timeMs ?: Int.MIN_VALUE >= lastTime) {
            for (d in newItems) items.add(DanmakuItem(d))
            return
        }
        // Rare: merge & reset.
        for (d in newItems) items.add(DanmakuItem(d))
        items.sortBy { it.timeMs() }
        index = 0
        clearActives()
        pending.clear()
        lastNowMs = 0
        publishEmptySnapshot()
    }

    fun trimToMax(maxItems: Int) {
        if (maxItems <= 0) return
        val drop = items.size - maxItems
        if (drop <= 0) return
        items = items.subList(drop, items.size).toMutableList()
        index = (index - drop).coerceAtLeast(0)
    }

    fun trimToTimeRange(minTimeMs: Long, maxTimeMs: Long) {
        if (items.isEmpty()) return
        val min = minTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val max = maxTimeMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (max <= min) return

        val start = lowerBound(min)
        val end = lowerBound(max)
        if (start <= 0 && end >= items.size) return
        if (start >= end) {
            items.clear()
            index = 0
            clearActives()
            pending.clear()
            lastNowMs = 0
            publishEmptySnapshot()
            return
        }
        items = items.subList(start, end).toMutableList()
        index = (index - start).coerceIn(0, items.size)
        // Drop pending outside range.
        if (pending.isNotEmpty()) {
            val keep = ArrayDeque<PendingSpawn>(pending.size)
            while (pending.isNotEmpty()) {
                val p = pending.removeFirst()
                val t = p.item.timeMs()
                if (t in min until max) keep.addLast(p)
            }
            pending.addAll(keep)
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        index = lowerBound(pos)
        clearActives()
        pending.clear()
        lastNowMs = pos
        publishEmptySnapshot()
    }

    fun clear() {
        clearActives()
        pending.clear()
        publishEmptySnapshot()
    }

    fun release() {
        clear()
    }

    private fun clearActives() {
        val releaseAt = currentUiFrameId + 1
        for (a in active) {
            releaseItemCache(a, releaseAtFrameId = releaseAt)
        }
        active.clear()
    }

    private fun publishEmptySnapshot() {
        val out = writableSnapshot()
        out.clear()
        latestSnapshot = out
    }

    private fun writableSnapshot(): RenderSnapshot = if (latestSnapshot === snapshotA) snapshotB else snapshotA

    private fun pruneExpired(width: Int, nowMs: Int) {
        if (active.isEmpty()) return
        val size = active.size
        var write = 0
        val releaseAt = currentUiFrameId + 1
        for (read in 0 until size) {
            val a = active[read]
            val elapsed = nowMs - a.startTimeMs
            var keep = elapsed < a.durationMs
            if (keep && a.kind == DanmakuKind.SCROLL) {
                val x = scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
                keep = x + a.textWidthPx >= 0f
            }
            if (!keep) {
                releaseItemCache(a, releaseAtFrameId = releaseAt)
                continue
            }
            if (write != read) active[write] = a
            write++
        }
        if (write < size) {
            active.subList(write, size).clear()
        }
    }

    private fun releaseItemCache(item: DanmakuItem, releaseAtFrameId: Int) {
        val bmp = item.cacheBitmap
        if (bmp != null) {
            cacheManager.enqueueRelease(bmp, releaseAtFrameId = releaseAtFrameId)
            item.cacheBitmap = null
        }
        item.cacheState = DanmakuCacheState.Init
        item.cacheGeneration = -1
    }

    private fun emotesReadyOrPrefetch(item: DanmakuItem): Boolean {
        val text = item.data.text
        if (!text.contains('[')) return true
        val segments =
            item.emoteSegments
                ?: run {
                    val parsed = parseEmoteSegments(text) ?: return true
                    item.emoteSegments = parsed
                    parsed
                }
        var ready = true
        for (seg in segments) {
            if (seg !is DanmakuEmoteSegment.Emote) continue
            val bmp = EmoteBitmapLoader.getCached(seg.url)
            if (bmp != null && !bmp.isRecycled) continue
            ready = false
            EmoteBitmapLoader.prefetch(seg.url)
        }
        return ready
    }

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isScrollLaneAvailable(
        width: Float,
        nowMs: Int,
        front: DanmakuItem,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        val elapsedPrev = nowMs - front.startTimeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true
        if (tailPrev + marginPx > width) return false
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true
        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun activate(
        item: DanmakuItem,
        kind: DanmakuKind,
        lane: Int,
        textWidth: Float,
        pxPerMs: Float,
        durationMs: Int,
        startTimeMs: Int,
    ) {
        item.kind = kind
        item.lane = lane
        item.textWidthPx = textWidth
        item.pxPerMs = pxPerMs
        item.durationMs = durationMs
        item.startTimeMs = startTimeMs
        active.add(item)
    }

    private fun skipOld(nowMs: Int, rollingDurationMs: Int) {
        val ignoreBefore = nowMs - rollingDurationMs
        while (index < items.size && items[index].timeMs() < ignoreBefore) {
            index++
        }
    }

    private fun dropIfLagging(nowMs: Int) {
        val dropBefore = nowMs - MAX_CATCH_UP_LAG_MS
        while (index < items.size && items[index].timeMs() < dropBefore) {
            index++
        }
    }

    private fun enqueuePending(kind: DanmakuKind, item: DanmakuItem, textWidth: Float, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(
            PendingSpawn(
                kind = kind,
                item = item,
                textWidthPx = textWidth,
                nextTryMs = nowMs + DELAY_STEP_MS,
                firstTryMs = nowMs,
            ),
        )
    }

    private data class PendingSpawn(
        val kind: DanmakuKind,
        val item: DanmakuItem,
        val textWidthPx: Float,
        var nextTryMs: Int,
        val firstTryMs: Int,
    )

    private fun ensureLaneBuffers(laneCount: Int) {
        if (laneLastScroll.size < laneCount) laneLastScroll = arrayOfNulls(laneCount)
        if (laneLastScrollTail.size < laneCount) laneLastScrollTail = FloatArray(laneCount)
        if (laneLastTop.size < laneCount) laneLastTop = arrayOfNulls(laneCount)
        if (laneLastBottom.size < laneCount) laneLastBottom = arrayOfNulls(laneCount)
    }

    private fun measureTextWidth(item: DanmakuItem, outlinePad: Float): Float {
        val text = item.data.text
        if (text.isBlank()) return outlinePad * 2f
        if (!text.contains('[')) return actionPaint.measureText(text) + outlinePad * 2f
        return measureTextWidthWithEmotes(text = text, paint = actionPaint, outlinePad = outlinePad)
    }

    private fun measureTextWidthWithEmotes(text: String, paint: Paint, outlinePad: Float): Float {
        paint.getFontMetrics(actionFontMetrics)
        val emoteSizePx = (actionFontMetrics.descent - actionFontMetrics.ascent).coerceAtLeast(1f)

        var w = 0f
        var i = 0
        while (i < text.length) {
            val open = text.indexOf('[', startIndex = i)
            if (open < 0) {
                w += paint.measureText(text, i, text.length)
                break
            }
            val close = text.indexOf(']', startIndex = open + 1)
            if (close < 0) {
                w += paint.measureText(text, i, text.length)
                break
            }
            if (open > i) {
                w += paint.measureText(text, i, open)
            }
            val token = text.substring(open, close + 1)
            val url = ReplyEmotePanelRepository.urlForToken(token)
            if (url != null && url.startsWith("http")) {
                w += emoteSizePx
            } else {
                w += paint.measureText(text, open, close + 1)
            }
            i = close + 1
        }
        return w + outlinePad * 2f
    }

    private fun parseEmoteSegments(text: String): List<DanmakuEmoteSegment>? {
        var i = 0
        var lastTextStart = 0
        var hasEmote = false
        val out = ArrayList<DanmakuEmoteSegment>(8)
        while (i < text.length) {
            val open = text.indexOf('[', startIndex = i)
            if (open < 0) break
            val close = text.indexOf(']', startIndex = open + 1)
            if (close < 0) break
            val token = text.substring(open, close + 1)
            val url = ReplyEmotePanelRepository.urlForToken(token)
            if (url != null && url.startsWith("http")) {
                hasEmote = true
                if (open > lastTextStart) out.add(DanmakuEmoteSegment.Text(start = lastTextStart, end = open))
                out.add(DanmakuEmoteSegment.Emote(url = url))
                lastTextStart = close + 1
            }
            i = close + 1
        }
        if (!hasEmote) return null
        if (lastTextStart < text.length) out.add(DanmakuEmoteSegment.Text(start = lastTextStart, end = text.length))
        return out
    }

    private fun drawTextDirect(
        canvas: Canvas,
        item: DanmakuItem,
        x: Float,
        yTop: Float,
        outlinePad: Float,
        baselineOffset: Float,
        opacityAlpha: Int,
        emoteSizePx: Float,
    ) {
        val text = item.data.text
        if (text.isBlank()) return

        val rgb = item.data.color and 0xFFFFFF
        val strokeAlpha = ((opacityAlpha * 0xCC) / 255).coerceIn(0, 255)
        drawStroke.color = (strokeAlpha shl 24) or 0x000000
        drawFill.color = (opacityAlpha shl 24) or rgb

        val textX = x + outlinePad
        val baseline = yTop + baselineOffset

        val segments =
            item.emoteSegments
                ?: run {
                    val parsed = if (text.contains('[')) parseEmoteSegments(text) else null
                    if (parsed != null) item.emoteSegments = parsed
                    parsed
                }
        if (segments == null) {
            canvas.drawText(text, textX, baseline, drawStroke)
            canvas.drawText(text, textX, baseline, drawFill)
            return
        }

        val emoteTop = yTop + outlinePad
        val r = (emoteSizePx * 0.18f).coerceIn(2f, 10f)
        var cursorX = textX
        for (seg in segments) {
            when (seg) {
                is DanmakuEmoteSegment.Text -> {
                    if (seg.end > seg.start) {
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, drawStroke)
                        canvas.drawText(text, seg.start, seg.end, cursorX, baseline, drawFill)
                        cursorX += drawFill.measureText(text, seg.start, seg.end)
                    }
                }
                is DanmakuEmoteSegment.Emote -> {
                    val bmp = EmoteBitmapLoader.getCached(seg.url)
                    if (bmp != null) {
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawBitmap(bmp, null, emoteTmpRectF, emotePaint)
                    } else {
                        // Prefetch is throttled elsewhere (later step). For now, do a best-effort prefetch.
                        EmoteBitmapLoader.prefetch(seg.url)
                        emoteTmpRectF.set(cursorX, emoteTop, cursorX + emoteSizePx, emoteTop + emoteSizePx)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderFill)
                        canvas.drawRoundRect(emoteTmpRectF, r, r, emotePlaceholderStroke)
                    }
                    cursorX += emoteSizePx
                }
            }
        }
    }

    private fun lowerBound(pos: Int): Int {
        var l = 0
        var r = items.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (items[m].timeMs() < pos) l = m + 1 else r = m
        }
        return l
    }

    private fun centerX(width: Int, contentWidth: Float): Float {
        if (width <= 0) return 0f
        val x = (width.toFloat() - contentWidth) / 2f
        return x.coerceAtLeast(0f)
    }

    private fun computeRollingDurationMs(speedLevel: Int): Int {
        // Keep the speed scale aligned with the project's previous implementation.
        // (User feedback: new 10 ~= old 4 was too slow.)
        val speed = speedMultiplier(speedLevel)
        val duration = (DEFAULT_ROLLING_DURATION_MS / speed).toInt()
        return duration.coerceIn(MIN_ROLLING_DURATION_MS, MAX_ROLLING_DURATION_MS)
    }

    private fun speedMultiplier(level: Int): Float =
        when (min(10, max(1, level))) {
            1 -> 0.6f
            2 -> 0.8f
            3 -> 0.9f
            4 -> 1.0f
            5 -> 1.2f
            6 -> 1.4f
            7 -> 1.6f
            8 -> 1.9f
            9 -> 2.2f
            else -> 2.6f
        }

    private companion object {
        private const val DEFAULT_ROLLING_DURATION_MS = 6_000f
        private const val MIN_ROLLING_DURATION_MS = 2_000
        private const val MAX_ROLLING_DURATION_MS = 20_000

        private const val FIXED_DURATION_MS = 4_000

        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
        private const val MAX_SPAWN_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_PER_FRAME = 48
        private const val MAX_CATCH_UP_LAG_MS = 1_200

        private const val MAX_CACHE_REQUESTS_PER_FRAME = 8
        private const val MAX_CACHE_QUEUE_DEPTH = 48
    }
}
