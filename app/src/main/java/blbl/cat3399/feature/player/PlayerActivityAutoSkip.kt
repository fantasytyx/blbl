package blbl.cat3399.feature.player

import android.os.SystemClock
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.SponsorBlockApi
import blbl.cat3399.core.net.BiliClient
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun PlayerActivity.autoSkipCategoryLabel(category: String?): String {
    val c = category?.trim().orEmpty()
    return when (c) {
        "" -> "此"
        "sponsor" -> "赞助/广告"
        "selfpromo" -> "推广"
        "exclusive_access" -> "品牌合作"
        "interaction" -> "三连提醒"
        "poi_highlight" -> "精彩时刻"
        "intro" -> "片头"
        "outro" -> "片尾"
        "preview" -> "预览"
        "padding" -> "填充内容"
        "filler" -> "离题"
        "music_offtopic" -> "非音乐"
        else -> c
    }
}

internal fun PlayerActivity.updateProgressUi() {
    val exo = player ?: return
    val duration = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    val pos = exo.currentPosition.coerceAtLeast(0L)
    val uiPos = holdScrubPreviewPosMs ?: pos
    val bufPos = exo.bufferedPosition.coerceAtLeast(0L)

    val uiScrubbing = scrubbing || holdScrubPreviewPosMs != null
    if (!uiScrubbing) {
        binding.tvTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
        binding.tvSeekOsdTime.text = "${formatHms(pos)} / ${formatHms(duration)}"
    }

    val enabled = duration > 0
    binding.seekProgress.isEnabled = enabled
    binding.progressPersistentBottom.isEnabled = enabled
    binding.progressSeekOsd.isEnabled = enabled
    if (enabled) {
        val bufferedProgress =
            ((bufPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX)
                .toInt()
                .coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.seekProgress.secondaryProgress = bufferedProgress
        binding.progressPersistentBottom.secondaryProgress = bufferedProgress
        binding.progressSeekOsd.secondaryProgress = bufferedProgress

        if (!uiScrubbing) {
            val p = ((pos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
            binding.seekProgress.progress = p
        }
        val pNow = ((uiPos.toDouble() / duration.toDouble()) * PlayerActivity.SEEK_MAX).toInt().coerceIn(0, PlayerActivity.SEEK_MAX)
        binding.progressPersistentBottom.progress = pNow
        binding.progressSeekOsd.progress = pNow
    } else {
        binding.seekProgress.secondaryProgress = 0
        binding.progressPersistentBottom.secondaryProgress = 0
        binding.progressPersistentBottom.progress = 0
        binding.progressSeekOsd.secondaryProgress = 0
        binding.progressSeekOsd.progress = 0
    }
    requestDanmakuSegmentsForPosition(pos, immediate = false)
    val markerDurationMs = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs ?: 0L
    maybeUpdateAutoSkipSegmentMarkers(durationMs = markerDurationMs)
    maybeTickAutoSkipSegments(posMs = pos)
}

internal fun PlayerActivity.cancelPendingAutoResume(reason: String) {
    if (reason == "back" || reason == "user_seek") autoResumeCancelledByUser = true
    dismissAutoResumeHint()
    autoResumeJob?.cancel()
    autoResumeJob = null
    trace?.log("resume:cancel", "reason=$reason")
}

internal fun PlayerActivity.showAutoResumeHint(targetMs: Long) {
    dismissAutoResumeHint()
    val timeText = formatHms(targetMs.coerceAtLeast(0L))
    val msg = "将要跳到上次播放位置（$timeText），按返回取消"
    autoResumeHintVisible = true
    // Reuse the existing bottom "seek hint" component for consistent look & feel.
    showSeekHint(msg, hold = true)
    // Keep the hint visible until either:
    // - user cancels (back / user seek), or
    // - auto-resume seek happens.
    autoResumeHintTimeoutJob?.cancel()
    autoResumeHintTimeoutJob = null
}

internal fun PlayerActivity.dismissAutoResumeHint() {
    if (!autoResumeHintVisible) return
    autoResumeHintVisible = false
    autoResumeHintTimeoutJob?.cancel()
    autoResumeHintTimeoutJob = null
    seekHintJob?.cancel()
    binding.tvSeekHint.visibility = View.GONE
}

internal fun PlayerActivity.cancelPendingAutoSkip(reason: String, markIgnored: Boolean) {
    autoSkipPending?.let { pending ->
        if (markIgnored) {
            autoSkipHandledSegmentIds.add(pending.segment.id)
        }
    }
    autoSkipPending = null
    dismissAutoSkipHint()
    trace?.log("skipseg:cancel", "reason=$reason ignored=${if (markIgnored) 1 else 0}")
}

internal fun PlayerActivity.showAutoSkipHint(segment: SkipSegment) {
    dismissAutoSkipHint()
    val label = autoSkipCategoryLabel(segment.category)
    val range = "${formatHms(segment.startMs)}→${formatHms(segment.endMs)}"
    val msg = "将要跳过${label}片段（$range），按返回取消"
    autoSkipHintVisible = true
    showSeekHint(msg, hold = true)
}

internal fun PlayerActivity.dismissAutoSkipHint() {
    if (!autoSkipHintVisible) return
    autoSkipHintVisible = false
    seekHintJob?.cancel()
    binding.tvSeekHint.visibility = View.GONE
}

internal fun PlayerActivity.maybeUpdateAutoSkipSegmentMarkers(durationMs: Long) {
    val enabled = BiliClient.prefs.playerAutoSkipSegmentsEnabled
    if (!enabled || durationMs <= 0L || autoSkipSegments.isEmpty()) {
        if (autoSkipMarkersShown) {
            binding.seekProgress.clearSegments()
            binding.progressPersistentBottom.clearSegments()
            binding.progressSeekOsd.clearSegments()
            autoSkipMarkersShown = false
        }
        autoSkipMarkersDirty = false
        autoSkipMarkersDurationMs = durationMs
        return
    }

    if (!autoSkipMarkersDirty && autoSkipMarkersShown && autoSkipMarkersDurationMs == durationMs) return
    autoSkipMarkersDurationMs = durationMs
    autoSkipMarkersDirty = false

    val durF = durationMs.toFloat()
    if (durF <= 0f) return
    val marks =
        autoSkipSegments.mapNotNull { seg ->
            val start = seg.startMs.coerceAtLeast(0L)
            val end = seg.endMs.coerceAtLeast(0L)
            if (end <= start) return@mapNotNull null
            val s = (start.toFloat() / durF).coerceIn(0f, 1f)
            val e = (end.toFloat() / durF).coerceIn(0f, 1f)
            if (e <= s) return@mapNotNull null
            SegmentMark(startFraction = s, endFraction = e)
        }
    binding.seekProgress.setSegments(marks)
    binding.progressPersistentBottom.setSegments(marks)
    binding.progressSeekOsd.setSegments(marks)
    autoSkipMarkersShown = marks.isNotEmpty()
}

internal fun PlayerActivity.maybeTickAutoSkipSegments(posMs: Long) {
    if (!BiliClient.prefs.playerAutoSkipSegmentsEnabled) return
    val exo = player ?: return
    if (!exo.playWhenReady) return
    if (scrubbing) return
    if (autoResumeHintVisible) return

    val now = SystemClock.elapsedRealtime()
    autoSkipPending?.let { pending ->
        if (pending.token != autoSkipToken) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }
        if (autoSkipHandledSegmentIds.contains(pending.segment.id)) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }
        if (now < pending.dueAtElapsedMs) return

        val seg = pending.segment
        if (posMs < seg.startMs) return
        if (posMs >= seg.endMs) {
            autoSkipPending = null
            dismissAutoSkipHint()
            return
        }

        val durationMs = exo.duration.takeIf { it > 0 }
        val targetMs = durationMs?.let { seg.endMs.coerceIn(0L, (it - 500L).coerceAtLeast(0L)) } ?: seg.endMs
        autoSkipHandledSegmentIds.add(seg.id)
        autoSkipPending = null
        dismissAutoSkipHint()
        trace?.log("skipseg:seek", "to=${targetMs}ms id=${seg.id} cat=${seg.category ?: ""} src=${seg.source}")
        exo.seekTo(targetMs)
        showSeekHint("已跳过${autoSkipCategoryLabel(seg.category)}片段", hold = false)
        return
    }

    if (autoSkipSegments.isEmpty()) return
    val windowEndMs = posMs + PlayerActivity.AUTO_SKIP_START_WINDOW_MS
    val candidate =
        autoSkipSegments.firstOrNull { seg ->
            if (autoSkipHandledSegmentIds.contains(seg.id)) return@firstOrNull false
            when {
                posMs >= seg.startMs && posMs < seg.endMs -> true
                seg.startMs in posMs..windowEndMs -> true
                seg.startMs > windowEndMs -> false
                else -> false
            }
        } ?: return

    autoSkipPending = PendingAutoSkip(token = autoSkipToken, segment = candidate, dueAtElapsedMs = now + PlayerActivity.AUTO_SKIP_DELAY_MS)
    trace?.log("skipseg:pending", "id=${candidate.id} cat=${candidate.category ?: ""} src=${candidate.source}")
    showAutoSkipHint(candidate)
}

internal fun PlayerActivity.maybeStartAutoSkipSegments(
    playJson: JSONObject,
    bvid: String,
    cid: Long,
    playbackToken: Int,
) {
    if (!BiliClient.prefs.playerAutoSkipSegmentsEnabled) return
    if (playbackToken != autoSkipToken) return
    if (bvid.isBlank() || cid <= 0L) return

    autoSkipFetchJob?.cancel()
    autoSkipFetchJob = null

    val clipSegments = extractClipInfoSegmentsFromPlayJson(playJson)
    setAutoSkipSegments(playbackToken, clipSegments)

    autoSkipFetchJob =
        lifecycleScope.launch {
            val sbSegments =
                withContext(Dispatchers.IO) {
                    runCatching { SponsorBlockApi.skipSegments(bvid = bvid, cid = cid) }.getOrNull().orEmpty()
                }
            if (!isActive) return@launch
            if (playbackToken != autoSkipToken) return@launch

            val merged = LinkedHashMap<String, SkipSegment>(clipSegments.size + sbSegments.size)
            for (seg in clipSegments) merged[seg.id] = seg
            for (sb in sbSegments) {
                val id =
                    sb.uuid?.takeIf { it.isNotBlank() }?.let { "sb:$it" }
                        ?: "sb:${sb.category.orEmpty()}:${sb.startMs}-${sb.endMs}"
                merged[id] =
                    SkipSegment(
                        id = id,
                        startMs = sb.startMs,
                        endMs = sb.endMs,
                        category = sb.category,
                        source = "sponsorblock",
                    )
            }
            setAutoSkipSegments(playbackToken, merged.values.toList())
        }
}

internal fun PlayerActivity.extractClipInfoSegmentsFromPlayJson(playJson: JSONObject): List<SkipSegment> {
    val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return emptyList()
    val arr = data.optJSONArray("clip_info_list") ?: data.optJSONArray("clipInfoList") ?: return emptyList()
    val out = ArrayList<SkipSegment>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val clipType = obj.optString("clipType", obj.optString("clip_type", "")).trim()
        val category =
            when (clipType) {
                "CLIP_TYPE_OP" -> "intro"
                "CLIP_TYPE_ED" -> "outro"
                else -> "clip"
            }
        val startRaw = obj.optDouble("start", Double.NaN)
        val endRaw = obj.optDouble("end", Double.NaN)
        if (!startRaw.isFinite() || !endRaw.isFinite()) continue
        val startMs = normalizeClipTimeToMs(startRaw).coerceAtLeast(0L)
        val endMs = normalizeClipTimeToMs(endRaw).coerceAtLeast(0L)
        if (endMs <= startMs) continue
        val id = "pgc:$category:$startMs-$endMs"
        out.add(SkipSegment(id = id, startMs = startMs, endMs = endMs, category = category, source = "pgc_clip"))
    }
    return out
}

internal fun PlayerActivity.normalizeClipTimeToMs(value: Double): Long {
    // B站 clip_info_list 的 start/end 常见为秒（支持小数），但也可能直接是毫秒。
    return if (value >= 10_000.0) value.toLong() else (value * 1000.0).toLong()
}

internal fun PlayerActivity.setAutoSkipSegments(token: Int, segments: List<SkipSegment>) {
    if (token != autoSkipToken) return
    autoSkipSegments = segments.sortedBy { it.startMs }
    autoSkipMarkersDirty = true
    trace?.log("skipseg:set", "count=${autoSkipSegments.size}")
}

internal fun PlayerActivity.extractResumeCandidateFromPlayJson(playJson: JSONObject): ResumeCandidate? {
    val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return null
    val time = data.optLong("last_play_time", -1L).takeIf { it > 0 } ?: return null
    val hint =
        when {
            time >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
            else -> RawTimeUnitHint.UNKNOWN
        }
    return ResumeCandidate(rawTime = time, rawTimeUnitHint = hint, source = "playurl")
}

internal fun PlayerActivity.extractResumeCandidateFromPlayerWbiV2(playerJson: JSONObject): ResumeCandidate? {
    val data = playerJson.optJSONObject("data") ?: return null
    val time = data.optLong("last_play_time", -1L).takeIf { it > 0 } ?: return null
    val hint =
        when {
            time >= 10_000L -> RawTimeUnitHint.MILLIS_LIKELY
            else -> RawTimeUnitHint.UNKNOWN
        }
    return ResumeCandidate(rawTime = time, rawTimeUnitHint = hint, source = "playerWbiV2")
}

internal fun PlayerActivity.normalizeResumePositionMs(raw: Long, hint: RawTimeUnitHint, durationMs: Long?): Long? {
    if (raw <= 0) return null
    val dur = durationMs?.takeIf { it > 0 }
    if (dur != null) {
        return when {
            raw in 1..dur -> raw
            raw * 1000 in 1..dur -> raw * 1000
            else -> raw
        }
    }
    return when (hint) {
        RawTimeUnitHint.MILLIS_LIKELY -> raw
        RawTimeUnitHint.SECONDS_LIKELY -> raw * 1000
        RawTimeUnitHint.UNKNOWN -> if (raw >= 10_000L) raw else raw * 1000
    }
}

internal fun PlayerActivity.shouldAutoResumeTo(positionMs: Long, durationMs: Long?): Boolean {
    if (positionMs < 5_000L) return false
    val dur = durationMs?.takeIf { it > 0 } ?: return true
    return positionMs < (dur - 10_000L).coerceAtLeast(0L)
}

internal fun PlayerActivity.maybeScheduleAutoResume(
    playJson: JSONObject,
    bvid: String,
    cid: Long,
    playbackToken: Int,
) {
    if (!BiliClient.prefs.playerAutoResumeEnabled) return
    if (autoResumeCancelledByUser) return
    if (playbackToken != autoResumeToken) return
    val exo = player ?: return

    val strictCidMatch = isMultiPagePlaylist(partsListItems, currentBvid)
    extractResumeCandidateFromPlayJson(playJson)?.let { cand ->
        val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: JSONObject()
        val lastCid = data.optLong("last_play_cid", -1L).takeIf { it > 0 }
        when {
            lastCid != null && lastCid != cid -> Unit
            strictCidMatch && lastCid == null -> Unit
            else -> {
                scheduleAutoResume(exo = exo, candidate = cand, playbackToken = playbackToken)
                return
            }
        }
    }

    autoResumeJob?.cancel()
    autoResumeJob =
        lifecycleScope.launch {
            val playerJson = runCatching { BiliApi.playerWbiV2(bvid = bvid, cid = cid) }.getOrNull() ?: return@launch
            if (!isActive) return@launch
            if (playbackToken != autoResumeToken) return@launch
            if (autoResumeCancelledByUser) return@launch
            val data = playerJson.optJSONObject("data") ?: JSONObject()
            val lastCid = data.optLong("last_play_cid", -1L).takeIf { it > 0 }
            if (lastCid != null && lastCid != cid) return@launch
            if (strictCidMatch && lastCid == null) return@launch
            val cand = extractResumeCandidateFromPlayerWbiV2(playerJson) ?: return@launch
            scheduleAutoResume(exo = exo, candidate = cand, playbackToken = playbackToken)
        }
}

internal fun PlayerActivity.scheduleAutoResume(exo: ExoPlayer, candidate: ResumeCandidate, playbackToken: Int) {
    if (autoResumeCancelledByUser) return
    autoResumeJob?.cancel()
    dismissAutoResumeHint()
    trace?.log("resume:pending", "src=${candidate.source} raw=${candidate.rawTime}")

    val delayMs = 3_000L
    val showAtMs = SystemClock.elapsedRealtime()
    val seekNotBeforeAtMs = showAtMs + delayMs
    val previewDurationMs = exo.duration.takeIf { it > 0 } ?: currentViewDurationMs
    val previewTargetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, previewDurationMs)
    if (previewTargetMs == null) return
    if (!shouldAutoResumeTo(previewTargetMs, previewDurationMs)) return
    showAutoResumeHint(targetMs = previewTargetMs)

    autoResumeJob =
        lifecycleScope.launch {
            // Seeking too early (while the beginning is still buffering) can cause some long videos to get stuck
            // with a black screen. Wait until the player becomes READY, then apply the minimum delay.
            val readyDeadlineAtMs = SystemClock.elapsedRealtime() + 30_000L
            while (isActive) {
                if (autoResumeCancelledByUser) return@launch
                if (playbackToken != autoResumeToken) return@launch
                val p = player ?: return@launch
                if (p !== exo) return@launch
                val state = p.playbackState
                if (state == Player.STATE_READY) break
                if (state == Player.STATE_ENDED) return@launch
                if (SystemClock.elapsedRealtime() >= readyDeadlineAtMs) return@launch
                delay(50L)
            }

            val remainMs = (seekNotBeforeAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (remainMs > 0) delay(remainMs)
            if (!isActive) return@launch
            if (autoResumeCancelledByUser) return@launch
            if (playbackToken != autoResumeToken) return@launch
            val p = player ?: return@launch
            if (p !== exo) return@launch

            val durationMs = p.duration.takeIf { it > 0 } ?: currentViewDurationMs
            val targetMs = normalizeResumePositionMs(candidate.rawTime, candidate.rawTimeUnitHint, durationMs) ?: return@launch
            if (!shouldAutoResumeTo(targetMs, durationMs)) return@launch
            val clamped = durationMs?.let { dur -> targetMs.coerceIn(0L, (dur - 500L).coerceAtLeast(0L)) } ?: targetMs
            trace?.log("resume:seek", "to=${clamped}ms src=${candidate.source}")
            dismissAutoResumeHint()
            p.seekTo(clamped)
        }
}
