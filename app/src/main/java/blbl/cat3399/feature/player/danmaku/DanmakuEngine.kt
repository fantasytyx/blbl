package blbl.cat3399.feature.player.danmaku

import android.graphics.Paint
import blbl.cat3399.core.emote.ReplyEmotePanelRepository
import blbl.cat3399.core.model.Danmaku
import java.util.Arrays
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

class DanmakuEngine {
    enum class Kind {
        SCROLL,
        TOP,
        BOTTOM,
    }

    class Active(
        val danmaku: Danmaku,
        val kind: Kind,
        val lane: Int,
        val textWidth: Float,
        val pxPerMs: Float,
        val durationMs: Int,
        val startTimeMs: Int,
    ) {
        var x: Float = 0f
        var yTop: Float = 0f
    }

    private var danmakus: MutableList<Danmaku> = mutableListOf()
    private var index = 0
    private val active = ArrayList<Active>()
    private val pending = ArrayDeque<Pending>()
    private var lastNowMs: Int = 0
    private val fontMetrics = Paint.FontMetrics()
    private val measuredTextWidth = IdentityHashMap<Danmaku, Float>()
    private var widthCacheTextSize: Float = Float.NaN
    private var widthCacheOutlinePad: Float = Float.NaN
    private var widthCacheEmoteVersion: Int = 0
    private var scrollLaneLast: Array<Active?> = emptyArray()
    private var scrollLaneLastTail: FloatArray = FloatArray(0)
    private var topLaneLast: Array<Active?> = emptyArray()
    private var bottomLaneLast: Array<Active?> = emptyArray()

    fun setDanmakus(list: List<Danmaku>) {
        danmakus = list.sortedBy { it.timeMs }.toMutableList()
        index = 0
        active.clear()
        pending.clear()
        lastNowMs = 0
        clearMeasureCache()
    }

    fun appendDanmakus(list: List<Danmaku>) {
        if (list.isEmpty()) return
        if (danmakus.isEmpty()) {
            setDanmakus(list)
            return
        }
        val sorted = list.sortedBy { it.timeMs }
        val last = danmakus.lastOrNull()?.timeMs ?: Int.MIN_VALUE
        if (sorted.first().timeMs >= last) {
            danmakus.addAll(sorted)
            return
        }
        // Fallback: merge & reset (rare for live mode).
        danmakus.addAll(sorted)
        danmakus.sortBy { it.timeMs }
        index = 0
        active.clear()
        pending.clear()
        lastNowMs = 0
        clearMeasureCache()
    }

    fun appendDanmakusSorted(list: List<Danmaku>) {
        if (list.isEmpty()) return
        if (danmakus.isEmpty()) {
            // setDanmakus will sort anyway, but list is already sorted.
            danmakus = list.toMutableList()
            index = 0
            active.clear()
            pending.clear()
            lastNowMs = 0
            return
        }

        val last = danmakus.lastOrNull()?.timeMs ?: Int.MIN_VALUE
        if (list.first().timeMs >= last) {
            danmakus.addAll(list)
            return
        }

        // Rare (seek/concurrent loads): keep correctness; will clear actives.
        danmakus.addAll(list)
        danmakus.sortBy { it.timeMs }
        index = 0
        active.clear()
        pending.clear()
        lastNowMs = 0
        clearMeasureCache()
    }

    fun trimToMax(maxItems: Int) {
        if (maxItems <= 0) return
        val drop = danmakus.size - maxItems
        if (drop <= 0) return
        danmakus = danmakus.drop(drop).toMutableList()
        index = (index - drop).coerceAtLeast(0)
        clearMeasureCache()
    }

    fun trimToTimeRange(minTimeMs: Int, maxTimeMs: Int) {
        if (danmakus.isEmpty()) return
        if (maxTimeMs <= minTimeMs) return

        val start = lowerBound(minTimeMs)
        val end = lowerBound(maxTimeMs)
        if (start <= 0 && end >= danmakus.size) return
        if (start >= end) {
            danmakus.clear()
            index = 0
            active.clear()
            pending.clear()
            lastNowMs = 0
            clearMeasureCache()
            return
        }

        danmakus = danmakus.subList(start, end).toMutableList()
        index = (index - start).coerceIn(0, danmakus.size)
        clearMeasureCache()

        // Drop pending outside the range to avoid useless retries.
        if (pending.isNotEmpty()) {
            val keep = ArrayDeque<Pending>(pending.size)
            while (pending.isNotEmpty()) {
                val p = pending.removeFirst()
                val t = p.danmaku.timeMs
                if (t in minTimeMs until maxTimeMs) keep.addLast(p)
            }
            pending.addAll(keep)
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.toInt()
        index = lowerBound(pos)
        active.clear()
        pending.clear()
        lastNowMs = pos
        clearMeasureCache()
    }

    fun nextDanmakuTimeMs(): Int? = danmakus.getOrNull(index)?.timeMs

    fun peekUpcoming(nowMs: Int, windowMs: Int, maxCount: Int): List<Danmaku> {
        if (danmakus.isEmpty() || maxCount <= 0 || windowMs <= 0) return emptyList()
        val endMs = nowMs + windowMs
        val out = ArrayList<Danmaku>(min(maxCount, 16))
        var i = index
        while (i < danmakus.size && out.size < maxCount) {
            val d = danmakus[i]
            if (d.timeMs > endMs) break
            out.add(d)
            i++
        }
        return out
    }

    fun hasActive(): Boolean = active.isNotEmpty()

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun activeCount(): Int = active.size

    fun pendingCount(): Int = pending.size

    fun measureTextWidth(danmaku: Danmaku, paint: Paint, outlinePaddingPx: Float): Float {
        val outlinePad = outlinePaddingPx.coerceAtLeast(0f)
        val emoteVersion = ReplyEmotePanelRepository.version()
        ensureMeasureCacheStyle(textSizePx = paint.textSize, outlinePad = outlinePad, emoteVersion = emoteVersion)
        measuredTextWidth[danmaku]?.let { return it }
        val measured = measureTextWidthWithEmotes(text = danmaku.text, paint = paint, outlinePad = outlinePad)
        if (measuredTextWidth.size >= MAX_TEXT_WIDTH_CACHE_ITEMS) {
            measuredTextWidth.clear()
        }
        measuredTextWidth[danmaku] = measured
        return measured
    }

    private fun measureTextWidthWithEmotes(text: String, paint: Paint, outlinePad: Float): Float {
        if (text.isBlank()) return outlinePad * 2f
        if (!text.contains('[')) return paint.measureText(text) + outlinePad * 2f

        // Emotes are rendered as square images with the same height as the text.
        paint.getFontMetrics(fontMetrics)
        val emoteSizePx = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1f)

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

    fun update(
        width: Int,
        height: Int,
        positionMs: Long,
        paint: Paint,
        outlinePaddingPx: Float,
        speedLevel: Int,
        area: Float,
        topInsetPx: Int,
        bottomInsetPx: Int,
    ): List<Active> {
        // Make time monotonic within a session to avoid jitter removing/respawning items.
        val rawNowMs = positionMs.toInt()
        val nowMs = if (rawNowMs >= lastNowMs) rawNowMs else lastNowMs
        lastNowMs = nowMs

        val safeTop = topInsetPx.coerceIn(0, height)
        val safeBottom = bottomInsetPx.coerceIn(0, height - safeTop)
        val availableHeight = (height - safeTop - safeBottom).coerceAtLeast(0)
        val outlinePad = outlinePaddingPx.coerceAtLeast(0f)
        val emoteVersion = ReplyEmotePanelRepository.version()
        ensureMeasureCacheStyle(textSizePx = paint.textSize, outlinePad = outlinePad, emoteVersion = emoteVersion)
        paint.getFontMetrics(fontMetrics)
        val textBoxHeight = (fontMetrics.descent - fontMetrics.ascent) + outlinePad * 2f
        val laneHeight = max(18f, textBoxHeight * 1.15f)
        val usableHeight = (availableHeight * area).toInt().coerceAtLeast(0)
        val laneCount = max(1, (usableHeight / laneHeight).toInt())

        val baseDurationMs = (6_000f / speedMultiplier(speedLevel)).toInt().coerceIn(2_000, 20_000)
        val fixedDurationMs = FIXED_DURATION_MS

        pruneExpired(width, nowMs)
        skipOld(nowMs, baseDurationMs)
        dropIfLagging(nowMs)

        val marginPx = max(12f, (paint.textSize + outlinePad * 2f) * 0.6f)

        // Build per-lane "last spawned" snapshot at current time.
        ensureLaneBuffers(laneCount)
        Arrays.fill(scrollLaneLast, 0, laneCount, null)
        Arrays.fill(scrollLaneLastTail, 0, laneCount, Float.NEGATIVE_INFINITY)
        Arrays.fill(topLaneLast, 0, laneCount, null)
        Arrays.fill(bottomLaneLast, 0, laneCount, null)
        for (a in active) {
            if (a.lane !in 0 until laneCount) continue
            when (a.kind) {
                Kind.SCROLL -> {
                    val cur = scrollLaneLast[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) {
                        scrollLaneLast[a.lane] = a
                    }
                }
                Kind.TOP -> {
                    val cur = topLaneLast[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) {
                        topLaneLast[a.lane] = a
                    }
                }
                Kind.BOTTOM -> {
                    val cur = bottomLaneLast[a.lane]
                    if (cur == null || a.startTimeMs > cur.startTimeMs) {
                        bottomLaneLast[a.lane] = a
                    }
                }
            }
        }
        for (lane in 0 until laneCount) {
            val a = scrollLaneLast[lane] ?: continue
            val x = scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
            scrollLaneLastTail[lane] = x + a.textWidth
        }

        fun trySpawnScroll(d: Danmaku, textWidth: Float): Boolean {
            if (d.text.isBlank()) return true
            val pxNew = (width + textWidth) / baseDurationMs.toFloat()

            for (lane in 0 until laneCount) {
                val prev = scrollLaneLast[lane]
                if (prev == null) {
                    val a = Active(d, Kind.SCROLL, lane, textWidth, pxNew, baseDurationMs, startTimeMs = nowMs)
                    active.add(a)
                    scrollLaneLast[lane] = a
                    scrollLaneLastTail[lane] = width.toFloat() + textWidth
                    return true
                }
                val tailPrev = scrollLaneLastTail[lane]
                if (isScrollLaneAvailable(width.toFloat(), nowMs, prev, tailPrev, pxNew, marginPx)) {
                    val a = Active(d, Kind.SCROLL, lane, textWidth, pxNew, baseDurationMs, startTimeMs = nowMs)
                    active.add(a)
                    scrollLaneLast[lane] = a
                    scrollLaneLastTail[lane] = width.toFloat() + textWidth
                    return true
                }
            }
            return false
        }

        fun trySpawnFixed(kind: Kind, d: Danmaku, textWidth: Float): Boolean {
            if (d.text.isBlank()) return true
            val lanes = when (kind) {
                Kind.TOP -> topLaneLast
                Kind.BOTTOM -> bottomLaneLast
                else -> return false
            }
            for (lane in 0 until laneCount) {
                val prev = lanes[lane]
                if (prev == null) {
                    val a = Active(d, kind, lane, textWidth, pxPerMs = 0f, durationMs = fixedDurationMs, startTimeMs = nowMs)
                    active.add(a)
                    lanes[lane] = a
                    return true
                }
                val elapsedPrev = nowMs - prev.startTimeMs
                if (elapsedPrev >= prev.durationMs) {
                    val a = Active(d, kind, lane, textWidth, pxPerMs = 0f, durationMs = fixedDurationMs, startTimeMs = nowMs)
                    active.add(a)
                    lanes[lane] = a
                    return true
                }
            }
            return false
        }

        fun kindOf(d: Danmaku): Kind =
            when (d.mode) {
                5 -> Kind.TOP
                4 -> Kind.BOTTOM
                else -> Kind.SCROLL
            }

        // Retry pending danmakus first (delay a bit to avoid overlaps).
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
                        Kind.SCROLL -> trySpawnScroll(p.danmaku, p.textWidth)
                        Kind.TOP -> trySpawnFixed(Kind.TOP, p.danmaku, p.textWidth)
                        Kind.BOTTOM -> trySpawnFixed(Kind.BOTTOM, p.danmaku, p.textWidth)
                    }
                if (ok) continue

                val age = nowMs - p.firstTryMs
                if (age <= MAX_DELAY_MS) {
                    p.nextTryMs = nowMs + DELAY_STEP_MS
                    pending.addLast(p)
                }
            }
        }

        // Spawn: only spawn items whose timestamp <= current position (no early spawn).
        var spawnAttempts = 0
        while (index < danmakus.size && danmakus[index].timeMs <= nowMs) {
            if (spawnAttempts >= MAX_SPAWN_PER_FRAME) break
            val d = danmakus[index]
            index++
            spawnAttempts++
            if (d.text.isBlank()) continue
            val tw = measureTextWidth(danmaku = d, paint = paint, outlinePaddingPx = outlinePad)
            val kind = kindOf(d)
            val ok =
                when (kind) {
                    Kind.SCROLL -> trySpawnScroll(d, tw)
                    Kind.TOP -> trySpawnFixed(Kind.TOP, d, tw)
                    Kind.BOTTOM -> trySpawnFixed(Kind.BOTTOM, d, tw)
                }
            if (ok) continue
            enqueuePending(kind = kind, danmaku = d, textWidth = tw, nowMs = nowMs)
        }

        val maxYTop = (safeTop + usableHeight - textBoxHeight).toFloat().coerceAtLeast(safeTop.toFloat())
        for (a in active) {
            when (a.kind) {
                Kind.SCROLL -> {
                    a.x = scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
                    a.yTop = (safeTop.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                }
                Kind.TOP -> {
                    a.x = centerX(width = width, contentWidth = a.textWidth)
                    a.yTop = (safeTop.toFloat() + laneHeight * a.lane).coerceAtMost(maxYTop)
                }
                Kind.BOTTOM -> {
                    a.x = centerX(width = width, contentWidth = a.textWidth)
                    a.yTop = (maxYTop - laneHeight * a.lane).coerceAtLeast(safeTop.toFloat())
                }
            }
        }

        return active
    }

    private fun pruneExpired(width: Int, nowMs: Int) {
        if (active.isEmpty()) return
        val size = active.size
        var write = 0
        for (read in 0 until size) {
            val a = active[read]
            val elapsed = nowMs - a.startTimeMs
            var keep = elapsed < a.durationMs
            if (keep && a.kind == Kind.SCROLL) {
                val x = scrollX(width, nowMs, a.startTimeMs, a.pxPerMs)
                keep = x + a.textWidth >= 0f
            }
            if (!keep) continue
            if (write != read) {
                active[write] = a
            }
            write++
        }
        if (write < size) {
            active.subList(write, size).clear()
        }
    }

    private fun scrollX(width: Int, nowMs: Int, startTimeMs: Int, pxPerMs: Float): Float {
        val elapsed = (nowMs - startTimeMs).coerceAtLeast(0)
        return width.toFloat() - elapsed * pxPerMs
    }

    private fun isScrollLaneAvailable(
        width: Float,
        nowMs: Int,
        front: Active,
        tailPrev: Float,
        pxNew: Float,
        marginPx: Float,
    ): Boolean {
        val elapsedPrev = nowMs - front.startTimeMs
        val prevRemaining = front.durationMs - elapsedPrev
        if (prevRemaining <= 0) return true

        // Condition A: the previous danmaku's tail must be fully inside the screen.
        if (tailPrev + marginPx > width) return false

        // Condition B: if the new danmaku is faster, it must not catch up before the previous one exits.
        val pxPrev = front.pxPerMs
        if (pxNew <= pxPrev) return true

        val gap0 = (width - tailPrev - marginPx).coerceAtLeast(0f)
        val maxSafe = (pxNew - pxPrev) * prevRemaining
        return gap0 >= maxSafe
    }

    private fun skipOld(nowMs: Int, baseDurationMs: Int) {
        val ignoreBefore = nowMs - baseDurationMs
        while (index < danmakus.size && danmakus[index].timeMs < ignoreBefore) {
            index++
        }
    }

    private fun dropIfLagging(nowMs: Int) {
        // If we fall far behind (usually due to extremely dense danmaku + rendering jank),
        // drop older items to avoid backlog bursts.
        val dropBefore = nowMs - MAX_CATCH_UP_LAG_MS
        while (index < danmakus.size && danmakus[index].timeMs < dropBefore) {
            index++
        }
    }

    private fun enqueuePending(kind: Kind, danmaku: Danmaku, textWidth: Float, nowMs: Int) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(
            Pending(
                kind = kind,
                danmaku = danmaku,
                textWidth = textWidth,
                nextTryMs = nowMs + DELAY_STEP_MS,
                firstTryMs = nowMs,
            ),
        )
    }

    private data class Pending(
        val kind: Kind,
        val danmaku: Danmaku,
        val textWidth: Float,
        var nextTryMs: Int,
        val firstTryMs: Int,
    )

    private fun ensureLaneBuffers(laneCount: Int) {
        if (scrollLaneLast.size < laneCount) {
            scrollLaneLast = arrayOfNulls(laneCount)
        }
        if (scrollLaneLastTail.size < laneCount) {
            scrollLaneLastTail = FloatArray(laneCount)
        }
        if (topLaneLast.size < laneCount) {
            topLaneLast = arrayOfNulls(laneCount)
        }
        if (bottomLaneLast.size < laneCount) {
            bottomLaneLast = arrayOfNulls(laneCount)
        }
    }

    private fun speedMultiplier(level: Int): Float = when (min(10, max(1, level))) {
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

    private fun lowerBound(pos: Int): Int {
        var l = 0
        var r = danmakus.size
        while (l < r) {
            val m = (l + r) ushr 1
            if (danmakus[m].timeMs < pos) l = m + 1 else r = m
        }
        return l
    }

    private fun centerX(width: Int, contentWidth: Float): Float {
        if (width <= 0) return 0f
        val x = (width.toFloat() - contentWidth) / 2f
        return x.coerceAtLeast(0f)
    }

    private fun ensureMeasureCacheStyle(textSizePx: Float, outlinePad: Float, emoteVersion: Int) {
        if (widthCacheTextSize == textSizePx && widthCacheOutlinePad == outlinePad && widthCacheEmoteVersion == emoteVersion) return
        widthCacheTextSize = textSizePx
        widthCacheOutlinePad = outlinePad
        widthCacheEmoteVersion = emoteVersion
        measuredTextWidth.clear()
    }

    private fun clearMeasureCache() {
        measuredTextWidth.clear()
    }

    private companion object {
        private const val DELAY_STEP_MS = 220
        private const val MAX_DELAY_MS = 1_600
        private const val MAX_PENDING = 260
        private const val MAX_SPAWN_PER_FRAME = 48
        private const val MAX_PENDING_RETRY_PER_FRAME = 48
        private const val MAX_CATCH_UP_LAG_MS = 1_200
        private const val MAX_TEXT_WIDTH_CACHE_ITEMS = 6_000

        private const val FIXED_DURATION_MS = 4_000
    }
}
