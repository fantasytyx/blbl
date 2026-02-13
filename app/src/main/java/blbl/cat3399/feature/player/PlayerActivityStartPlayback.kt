package blbl.cat3399.feature.player

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.util.parseBangumiRedirectUrl
import blbl.cat3399.feature.my.BangumiDetailActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.withContext

internal fun PlayerActivity.resetPlaybackStateForNewMedia(exo: ExoPlayer) {
    traceFirstFrameLogged = false
    lastAvailableQns = emptyList()
    lastAvailableAudioIds = emptyList()
    session = session.copy(actualQn = 0)
    session = session.copy(actualAudioId = 0)
    currentViewDurationMs = null
    debug.reset()
    subtitleAvailabilityKnown = false
    subtitleAvailable = false
    subtitleConfig = null
    subtitleItems = emptyList()
    currentUpMid = 0L
    currentUpName = null
    currentUpAvatar = null
    danmakuShield = null
    cancelDanmakuLoading(reason = "new_media")
    danmakuLoadedSegments.clear()
    danmakuSegmentItems.clear()
    binding.danmakuView.setDanmakus(emptyList())
    binding.danmakuView.notifySeek(0L)

    likeActionJob?.cancel()
    likeActionJob = null
    coinActionJob?.cancel()
    coinActionJob = null
    favDialogJob?.cancel()
    favDialogJob = null
    favApplyJob?.cancel()
    favApplyJob = null
    actionLiked = false
    actionCoinCount = 0
    actionFavored = false
    updateActionButtonsUi()

    relatedVideosFetchJob?.cancel()
    relatedVideosFetchJob = null
    relatedVideosFetchToken++
    relatedVideosCache = null

    commentsFetchJob?.cancel()
    commentsFetchJob = null
    commentsFetchToken++
    commentsPage = 1
    commentsTotalCount = -1
    commentsEndReached = false
    commentsItems.clear()

    commentThreadFetchJob?.cancel()
    commentThreadFetchJob = null
    commentThreadFetchToken++
    commentThreadRootRpid = 0L
    commentThreadPage = 1
    commentThreadTotalCount = -1
    commentThreadEndReached = false
    commentThreadItems.clear()

    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.GONE
    binding.recyclerComments.visibility = View.VISIBLE
    binding.recyclerCommentThread.visibility = View.GONE
    binding.rowCommentSort.visibility = View.VISIBLE
    binding.tvCommentsPanelTitle.text = getString(blbl.cat3399.R.string.player_panel_comments)
    binding.tvCommentsHint.visibility = View.GONE
    (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())
    (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())

    playbackConstraints = PlaybackConstraints()
    decodeFallbackAttempted = false
    lastPickedDash = null
    exo.stop()
    applySubtitleEnabled(exo)
    applyPlaybackMode(exo)
    updateSubtitleButton()
    updateDanmakuButton()
    updateUpButton()
    (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
}

internal fun PlayerActivity.startPlayback(
    bvid: String?,
    cidExtra: Long?,
    epIdExtra: Long?,
    aidExtra: Long?,
    initialTitle: String?,
) {
    val exo = player ?: return
    val safeBvid = bvid?.trim().orEmpty()
    val safeAid = aidExtra?.takeIf { it > 0 }
    if (safeBvid.isBlank() && safeAid == null) return

    cancelPendingAutoResume(reason = "new_media")
    autoResumeToken++
    autoResumeCancelledByUser = false
    cancelPendingAutoSkip(reason = "new_media", markIgnored = false)
    autoSkipFetchJob?.cancel()
    autoSkipFetchJob = null
    autoSkipSegments = emptyList()
    autoSkipHandledSegmentIds.clear()
    autoSkipPending = null
    binding.seekProgress.clearSegments()
    binding.progressPersistentBottom.clearSegments()
    autoSkipMarkersDirty = true
    autoSkipMarkersDurationMs = -1L
    autoSkipMarkersShown = false
    autoSkipToken++
    stopReportProgressLoop(flush = false, reason = "new_media")
    reportToken++
    lastReportAtMs = 0L
    lastReportedProgressSec = -1L

    loadJob?.cancel()
    loadJob = null

    currentBvid = safeBvid
    currentEpId = epIdExtra
    currentAid = safeAid
    currentCid = -1L

    trace =
        PlayerActivity.PlaybackTrace(
            buildString {
                val token = safeBvid.takeLast(8).ifBlank { safeAid?.toString(16) ?: "unknown" }
                append(token)
                append('-')
                append((System.currentTimeMillis() and 0xFFFF).toString(16))
            },
    )

    binding.tvTitle.text = initialTitle?.takeIf { it.isNotBlank() } ?: "-"
    binding.tvOnline.text = "-人正在观看"
    binding.tvViewCount.text = "-"
    binding.llViewMeta.visibility = View.VISIBLE
    binding.tvPubdate.text = ""
    binding.tvPubdate.visibility = View.GONE
    resetPlaybackStateForNewMedia(exo)

    updatePlaylistControls()

    val handler =
        playbackUncaughtHandler
            ?: CoroutineExceptionHandler { _, throwable ->
                AppLog.e("Player", "uncaught", throwable)
                Toast.makeText(this@startPlayback, "播放失败：${throwable.message}", Toast.LENGTH_LONG).show()
                finish()
            }

    loadJob =
        lifecycleScope.launch(handler) {
            try {
                trace?.log("view:start")
                val viewJson =
                    async(Dispatchers.IO) {
                        runCatching {
                            if (safeBvid.isNotBlank()) {
                                BiliApi.view(safeBvid)
                            } else {
                                BiliApi.view(safeAid ?: 0L)
                            }
                        }.getOrNull()
                    }
                val viewData = viewJson.await()?.optJSONObject("data") ?: JSONObject()
                trace?.log("view:done")

                val bangumiRedirect = parseBangumiRedirectUrl(viewData.optString("redirect_url", ""))
                val isAlreadyPgc =
                    currentEpId != null ||
                        playlistSource?.trim().orEmpty().startsWith("Bangumi:")
                if (bangumiRedirect != null && !isAlreadyPgc) {
                    startActivity(
                        Intent(this@startPlayback, BangumiDetailActivity::class.java)
                            .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, false)
                            .apply {
                                bangumiRedirect.epId?.let { epId ->
                                    putExtra(BangumiDetailActivity.EXTRA_EP_ID, epId)
                                    putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, epId)
                                }
                                bangumiRedirect.seasonId?.let { seasonId ->
                                    putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, seasonId)
                                }
                            },
                    )
                    finish()
                    return@launch
                }

                val title = viewData.optString("title", "")
                if (title.isNotBlank()) binding.tvTitle.text = title
                currentViewDurationMs = viewData.optLong("duration", -1L).takeIf { it > 0 }?.times(1000L)
                applyUpInfo(viewData)
                applyTitleMeta(viewData)

                val resolvedBvid =
                    viewData.optString("bvid", "").trim().takeIf { it.isNotBlank() }
                        ?: safeBvid
                if (resolvedBvid.isNotBlank()) currentBvid = resolvedBvid

                val cid = cidExtra ?: viewData.optLong("cid").takeIf { it > 0 } ?: error("cid missing")
                val aid = viewData.optLong("aid").takeIf { it > 0 }
                currentAid = currentAid ?: aid ?: safeAid
                currentCid = cid
                if (isCommentsPanelVisible() && !isCommentThreadVisible()) ensureCommentsLoaded()
                AppLog.i("Player", "start bvid=$resolvedBvid cid=$cid")
                trace?.log("cid:resolved", "cid=$cid aid=${aid ?: -1}")

                playlistUgcSeasonId = null
                playlistUgcSeasonTitle = null
                maybeOverridePlaylistWithUgcSeason(viewData, bvid = resolvedBvid)
                maybeOverridePlaylistWithMultiPage(viewData, bvid = resolvedBvid)

                requestOnlineWatchingText(bvid = resolvedBvid, cid = cid)
                applyPerVideoPreferredQn(viewData, cid = cid)

                val playJob =
                    async {
                        val (qn, fnval) = playUrlParamsForSession()
                        trace?.log("playurl:start", "qn=$qn fnval=$fnval")
                        playbackConstraints = PlaybackConstraints()
                        decodeFallbackAttempted = false
                        lastPickedDash = null
                        loadPlayableWithTryLookFallback(
                            bvid = resolvedBvid,
                            aid = currentAid,
                            cid = cid,
                            epId = currentEpId,
                            qn = qn,
                            fnval = fnval,
                            constraints = playbackConstraints,
                        ).also { trace?.log("playurl:done") }
                    }
                val dmJob =
                    async(Dispatchers.IO) {
                        trace?.log("danmakuMeta:start")
                        prepareDanmakuMeta(cid, currentAid ?: aid, trace)
                            .also { trace?.log("danmakuMeta:done", "segTotal=${it.segmentTotal} segMs=${it.segmentSizeMs}") }
                    }
                val subJob =
                    async(Dispatchers.IO) {
                        trace?.log("subtitle:start")
                        prepareSubtitleConfig(viewData, resolvedBvid, cid, trace)
                            .also { trace?.log("subtitle:done", "ok=${it != null}") }
                    }

                trace?.log("playurl:await")
                val (playJson, playable) = playJob.await()
                trace?.log("playurl:awaitDone")
                showRiskControlBypassHintIfNeeded(playJson)
                lastAvailableQns = parseDashVideoQnList(playJson)
                lastAvailableAudioIds = parseDashAudioIdList(playJson, constraints = playbackConstraints)
                trace?.log("subtitle:await")
                subtitleConfig = subJob.await()
                trace?.log("subtitle:awaitDone", "ok=${subtitleConfig != null}")
                subtitleAvailabilityKnown = true
                subtitleAvailable = subtitleConfig != null
                (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                applySubtitleEnabled(exo)

                trace?.log("exo:setMediaSource:start")
                when (playable) {
                    is Playable.Dash -> {
                        lastPickedDash = playable
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        AppLog.i(
                            "Player",
                            "picked DASH qn=${playable.qn} codecid=${playable.codecid} dv=${playable.isDolbyVision} a=${playable.audioKind}(${playable.audioId}) video=${playable.videoUrl}",
                        )
                        val videoFactory = createCdnFactory(DebugStreamKind.VIDEO, urlCandidates = playable.videoUrlCandidates)
                        val audioFactory = createCdnFactory(DebugStreamKind.AUDIO, urlCandidates = playable.audioUrlCandidates)
                        exo.setMediaSource(buildMerged(videoFactory, audioFactory, playable.videoUrl, playable.audioUrl, subtitleConfig))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                        applyAudioFallbackIfNeeded(requestedAudioId = session.targetAudioId, actualAudioId = playable.audioId)
                    }

                    is Playable.VideoOnly -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
                        AppLog.i(
                            "Player",
                            "picked VideoOnly qn=${playable.qn} codecid=${playable.codecid} dv=${playable.isDolbyVision} video=${playable.videoUrl}",
                        )
                        val mainFactory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = playable.videoUrlCandidates)
                        exo.setMediaSource(buildProgressive(mainFactory, playable.videoUrl, subtitleConfig))
                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
                    }

                    is Playable.Progressive -> {
                        lastPickedDash = null
                        session = session.copy(actualAudioId = 0)
                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                        debug.cdnHost = runCatching { Uri.parse(playable.url).host }.getOrNull()
                        AppLog.i("Player", "picked Progressive url=${playable.url}")
                        val mainFactory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = playable.urlCandidates)
                        exo.setMediaSource(buildProgressive(mainFactory, playable.url, subtitleConfig))
                    }
                }
                trace?.log("exo:setMediaSource:done")
                trace?.log("exo:prepare")
                exo.prepare()
                trace?.log("exo:playWhenReady")
                exo.playWhenReady = true
                updateSubtitleButton()
                maybeScheduleAutoResume(
                    playJson = playJson,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoResumeToken,
                )
                maybeStartAutoSkipSegments(
                    playJson = playJson,
                    bvid = resolvedBvid,
                    cid = cid,
                    playbackToken = autoSkipToken,
                )

                trace?.log("danmakuMeta:await")
                val dmMeta = dmJob.await()
                trace?.log("danmakuMeta:awaitDone")
                applyDanmakuMeta(dmMeta)
                requestDanmakuSegmentsForPosition(exo.currentPosition.coerceAtLeast(0L), immediate = true)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) return@launch
                AppLog.e("Player", "start failed", throwable)
                if (!handlePlayUrlErrorIfNeeded(throwable)) {
                    Toast.makeText(this@startPlayback, "加载播放信息失败：${throwable.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
}

internal fun PlayerActivity.shouldKeepExternalPlaylistFixed(): Boolean {
    if (playlistToken.isNullOrBlank()) return false
    val src = playlistSource?.trim().orEmpty()
    if (src.isBlank()) return false
    return src == "MyToView" || src.startsWith("MyFavFolderDetail:")
}

internal suspend fun PlayerActivity.maybeOverridePlaylistWithUgcSeason(viewData: JSONObject, bvid: String) {
    if (shouldKeepExternalPlaylistFixed()) return
    val ugcSeason = viewData.optJSONObject("ugc_season") ?: return
    val seasonId = ugcSeason.optLong("id").takeIf { it > 0 } ?: return
    val seasonTitle = ugcSeason.optString("title", "").trim().takeIf { it.isNotBlank() }

    fun apply(items: List<PlayerPlaylistItem>, index: Int) {
        if (items.isEmpty() || index !in items.indices) return
        playlistToken?.let(PlayerPlaylistStore::remove)
        playlistToken = null
        playlistSource = null
        playlistItems = items
        playlistIndex = index
        playlistUgcSeasonId = seasonId
        playlistUgcSeasonTitle = seasonTitle
        updatePlaylistControls()
    }

    val aid = currentAid
    val cid = currentCid.takeIf { it > 0 }

    val itemsFromView = parseUgcSeasonPlaylistFromView(ugcSeason)
    val idxFromView = pickPlaylistIndexForCurrentMedia(itemsFromView, bvid = bvid, aid = aid, cid = cid)
    if (idxFromView >= 0) {
        apply(itemsFromView, idxFromView)
        return
    }

    val mid =
        ugcSeason.optLong("mid").takeIf { it > 0 }
            ?: viewData.optJSONObject("owner")?.optLong("mid")?.takeIf { it > 0 }
            ?: return

    val json =
        withContext(Dispatchers.IO) {
            runCatching { BiliApi.seasonsArchivesList(mid = mid, seasonId = seasonId, pageSize = 200) }.getOrNull()
        } ?: return
    val itemsFromApi = parseUgcSeasonPlaylistFromArchivesList(json)
    val idxFromApi = pickPlaylistIndexForCurrentMedia(itemsFromApi, bvid = bvid, aid = aid, cid = cid)
    if (idxFromApi >= 0) apply(itemsFromApi, idxFromApi)
}

internal fun PlayerActivity.maybeOverridePlaylistWithMultiPage(viewData: JSONObject, bvid: String) {
    if (shouldKeepExternalPlaylistFixed()) return
    if (playlistUgcSeasonId != null) return
    val pages = viewData.optJSONArray("pages") ?: return
    if (pages.length() <= 1) return

    val aid = currentAid ?: viewData.optLong("aid").takeIf { it > 0 }
    val cid = currentCid.takeIf { it > 0 }

    fun apply(items: List<PlayerPlaylistItem>, index: Int) {
        if (items.isEmpty() || index !in items.indices) return
        playlistToken?.let(PlayerPlaylistStore::remove)
        playlistToken = null
        playlistSource = null
        playlistItems = items
        playlistIndex = index
        updatePlaylistControls()
    }

    val itemsFromView = parseMultiPagePlaylistFromView(viewData, bvid = bvid, aid = aid)
    if (itemsFromView.size <= 1) return
    val idx = pickPlaylistIndexForCurrentMedia(itemsFromView, bvid = bvid, aid = aid, cid = cid)
    val safeIndex = if (idx in itemsFromView.indices) idx else 0
    apply(itemsFromView, safeIndex)
}

internal fun PlayerActivity.handlePlaybackEnded(exo: ExoPlayer) {
    val now = android.os.SystemClock.uptimeMillis()
    if (now - lastEndedActionAtMs < 350) return
    lastEndedActionAtMs = now

    when (resolvedPlaybackMode()) {
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> {
            exo.seekTo(0)
            exo.playWhenReady = true
            exo.play()
        }

        AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> {
            playNext(userInitiated = false)
        }

        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> {
            playRecommendedNext(userInitiated = false)
        }

        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> {
            finish()
        }

        else -> Unit
    }
}

internal fun PlayerActivity.applyPerVideoPreferredQn(viewData: JSONObject, cid: Long) {
    val prefs = BiliClient.prefs

    var dim: JSONObject? = null
    val pages = viewData.optJSONArray("pages")
    if (pages != null) {
        for (i in 0 until pages.length()) {
            val page = pages.optJSONObject(i) ?: continue
            if (page.optLong("cid") != cid) continue
            dim = page.optJSONObject("dimension")
            if (dim != null) break
        }
    }
    dim = dim ?: viewData.optJSONObject("dimension")

    val width = dim?.optInt("width", 0) ?: 0
    val height = dim?.optInt("height", 0) ?: 0
    val rotate = dim?.optInt("rotate", 0) ?: 0
    val (effectiveW, effectiveH) =
        if (rotate == 1) {
            height to width
        } else {
            width to height
        }

    val isPortraitVideo = (effectiveW > 0 && effectiveH > 0 && effectiveH > effectiveW)
    val preferredQn = if (isPortraitVideo) prefs.playerPreferredQnPortrait else prefs.playerPreferredQn
    if (session.preferredQn != preferredQn) {
        session = session.copy(preferredQn = preferredQn)
    }
}

internal fun PlayerActivity.handlePlayUrlErrorIfNeeded(t: Throwable): Boolean {
    val e = t as? BiliApiException ?: return false
    if (!isRiskControl(e)) return false

    val prefs = BiliClient.prefs
    val savedVoucher = prefs.gaiaVgateVVoucher
    val savedAt = prefs.gaiaVgateVVoucherSavedAtMs
    val hasSavedVoucher = !savedVoucher.isNullOrBlank()

    // Keep this non-blocking: never show modal dialogs or auto-jump away from playback.
    // Users can choose to go to Settings manually if needed.
    val msg =
        buildString {
            append("B 站返回：").append(e.apiCode).append(" / ").append(e.apiMessage)
            if (e.apiCode == -352 && hasSavedVoucher) {
                append("\n")
                append("已记录 v_voucher，可到“设置 -> 风控验证”手动完成人机验证后重试。")
                if (savedAt > 0L) {
                    append("\n")
                    append("记录时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
                }
            } else {
                append("\n")
                append("可能触发风控，建议重新登录或稍后重试。")
            }
        }
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    return true
}

internal fun PlayerActivity.showRiskControlBypassHintIfNeeded(playJson: JSONObject) {
    if (riskControlBypassHintShown) return
    if (!playJson.optBoolean("__blbl_risk_control_bypassed", false)) return
    riskControlBypassHintShown = true

    val code = playJson.optInt("__blbl_risk_control_code", 0)
    val message = playJson.optString("__blbl_risk_control_message", "")
    val msg =
        buildString {
            append("B 站返回：").append(code).append(" / ").append(message)
            append("\n\n")
            append("你的账号或网络环境可能触发风控，建议重新登录或稍后重试。")
            append("\n")
            append("如持续出现，请向作者反馈日志。")
        }
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

internal fun PlayerActivity.isRiskControl(e: BiliApiException): Boolean {
    if (e.apiCode == -412 || e.apiCode == -352) return true
    val m = e.apiMessage
    return m.contains("风控") || m.contains("拦截") || m.contains("风险")
}
