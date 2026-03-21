@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package blbl.cat3399.feature.player

import android.content.Intent
import android.util.TypedValue
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.Player
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.feature.player.engine.BlblPlayerEngine
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.engine.PlayerEngineKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.util.Locale

internal object PlayerSettingKeys {
    const val PLAYER_ENGINE = "player_engine"
    const val RESOLUTION = "resolution"
    const val AUDIO_TRACK = "audio_track"
    const val CODEC = "codec"
    const val PLAYBACK_SPEED = "playback_speed"
    const val AUDIO_BALANCE = "audio_balance"
    const val PLAYBACK_MODE = "playback_mode"
    const val SUBTITLE_LANG = "subtitle_lang"
    const val SUBTITLE_TEXT_SIZE = "subtitle_text_size"
    const val SUBTITLE_BOTTOM_PADDING = "subtitle_bottom_padding"
    const val SUBTITLE_BACKGROUND_OPACITY = "subtitle_background_opacity"
    const val DANMAKU_OPACITY = "danmaku_opacity"
    const val DANMAKU_TEXT_SIZE = "danmaku_text_size"
    const val DANMAKU_SPEED = "danmaku_speed"
    const val DANMAKU_AREA = "danmaku_area"
    const val DEBUG_INFO = "debug_info"
    const val PERSISTENT_BOTTOM_PROGRESS = "persistent_bottom_progress"
}

internal fun PlayerActivity.handleSettingsItemClick(item: PlayerSettingsAdapter.SettingItem) {
    when (item.key) {
        PlayerSettingKeys.PLAYER_ENGINE -> showPlayerEngineDialog()
        PlayerSettingKeys.RESOLUTION -> showResolutionDialog()
        PlayerSettingKeys.AUDIO_TRACK -> showAudioDialog()
        PlayerSettingKeys.CODEC -> showCodecDialog()
        PlayerSettingKeys.PLAYBACK_SPEED -> showSpeedDialog()
        PlayerSettingKeys.AUDIO_BALANCE -> showAudioBalanceDialog()
        PlayerSettingKeys.PLAYBACK_MODE -> showPlaybackModeDialog()
        PlayerSettingKeys.SUBTITLE_LANG -> showSubtitleLangDialog()
        PlayerSettingKeys.SUBTITLE_TEXT_SIZE -> showSubtitleTextSizeDialog()
        PlayerSettingKeys.SUBTITLE_BOTTOM_PADDING -> showSubtitleBottomPaddingDialog()
        PlayerSettingKeys.SUBTITLE_BACKGROUND_OPACITY -> showSubtitleBackgroundOpacityDialog()
        PlayerSettingKeys.DANMAKU_OPACITY -> showDanmakuOpacityDialog()
        PlayerSettingKeys.DANMAKU_TEXT_SIZE -> showDanmakuTextSizeDialog()
        PlayerSettingKeys.DANMAKU_SPEED -> showDanmakuSpeedDialog()
        PlayerSettingKeys.DANMAKU_AREA -> showDanmakuAreaDialog()
        PlayerSettingKeys.DEBUG_INFO -> {
            session = session.copy(debugEnabled = !session.debugEnabled)
            updateDebugOverlay()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
        }

        PlayerSettingKeys.PERSISTENT_BOTTOM_PROGRESS -> {
            val appPrefs = BiliClient.prefs
            appPrefs.playerPersistentBottomProgressEnabled = !appPrefs.playerPersistentBottomProgressEnabled
            updatePersistentBottomProgressBarVisibility()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
        }

        else -> AppToast.show(this, "暂未实现：${item.title}")
    }
}

internal fun PlayerActivity.refreshSettings(adapter: PlayerSettingsAdapter) {
    val prefs = BiliClient.prefs
    val restoreFocusKey = currentSettingsFocusKey()
    val subtitleSupported = player?.capabilities?.subtitlesSupported == true
    val items =
        buildList {
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.RESOLUTION,
                    title = "分辨率",
                    subtitle = resolutionSubtitle(),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.AUDIO_TRACK,
                    title = "音轨",
                    subtitle = audioSubtitle(),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.CODEC,
                    title = "视频编码",
                    subtitle = session.preferCodec,
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.PLAYBACK_SPEED,
                    title = "播放速度",
                    subtitle = String.format(Locale.US, "%.2fx", session.playbackSpeed),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.AUDIO_BALANCE,
                    title = "音频平衡",
                    subtitle = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel).label,
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.PLAYBACK_MODE,
                    title = "播放模式",
                    subtitle = playbackModeSubtitle(),
                ),
            )
            if (subtitleSupported) {
                add(
                    PlayerSettingsAdapter.SettingItem(
                        key = PlayerSettingKeys.SUBTITLE_LANG,
                        title = "字幕语言",
                        subtitle = subtitleLangSubtitle(),
                    ),
                )
                add(
                    PlayerSettingsAdapter.SettingItem(
                        key = PlayerSettingKeys.SUBTITLE_TEXT_SIZE,
                        title = "字幕字体大小",
                        subtitle = session.subtitleTextSizeSp.toInt().toString(),
                    ),
                )
                add(
                    PlayerSettingsAdapter.SettingItem(
                        key = PlayerSettingKeys.SUBTITLE_BOTTOM_PADDING,
                        title = "字幕底部间距",
                        subtitle = "${(session.subtitleBottomPaddingFraction * 100f).roundToInt()}%",
                    ),
                )
                add(
                    PlayerSettingsAdapter.SettingItem(
                        key = PlayerSettingKeys.SUBTITLE_BACKGROUND_OPACITY,
                        title = "字幕背景透明度",
                        subtitle = String.format(Locale.US, "%.2f", session.subtitleBackgroundOpacity),
                    ),
                )
            }
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.DANMAKU_OPACITY,
                    title = "弹幕透明度",
                    subtitle = String.format(Locale.US, "%.2f", session.danmaku.opacity),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.DANMAKU_TEXT_SIZE,
                    title = "弹幕字体大小",
                    subtitle = session.danmaku.textSizeSp.toInt().toString(),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.DANMAKU_SPEED,
                    title = "弹幕速度",
                    subtitle = session.danmaku.speedLevel.toString(),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.DANMAKU_AREA,
                    title = "弹幕区域",
                    subtitle = areaText(session.danmaku.area),
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.DEBUG_INFO,
                    title = "调试信息",
                    subtitle = if (session.debugEnabled) "开" else "关",
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.PERSISTENT_BOTTOM_PROGRESS,
                    title = "底部常驻进度条",
                    subtitle = if (prefs.playerPersistentBottomProgressEnabled) "开" else "关",
                ),
            )
            add(
                PlayerSettingsAdapter.SettingItem(
                    key = PlayerSettingKeys.PLAYER_ENGINE,
                    title = "播放器内核",
                    subtitle = playerEngineSubtitle(),
                ),
            )
        }
    adapter.submit(
        items,
        onCommitted = {
            val key = restoreFocusKey ?: return@submit
            restoreSettingsPanelFocusByKey(key)
        },
    )
}

private fun PlayerActivity.playerEngineSubtitle(): String {
    val kind = player?.kind ?: session.engineKind
    return when (kind) {
        PlayerEngineKind.IjkPlayer -> "IjkPlayer"
        PlayerEngineKind.ExoPlayer -> "ExoPlayer"
    }
}

private fun PlayerActivity.restartForEngineSwitch(picked: PlayerEngineKind) {
    val engine = player ?: return

    val resumePosMs = engine.currentPosition.coerceAtLeast(0L)
    val resumePlayWhenReady = engine.playWhenReady
    val sessionJson = session.copy(engineKind = picked).toEngineSwitchJsonString()

    val restart =
        Intent(this, PlayerActivity::class.java).apply {
            val bvid = currentBvid.takeIf { it.isNotBlank() } ?: intent.getStringExtra(PlayerActivity.EXTRA_BVID).orEmpty()
            if (bvid.isNotBlank()) putExtra(PlayerActivity.EXTRA_BVID, bvid)
            val cid = currentCid.takeIf { it > 0L } ?: intent.getLongExtra(PlayerActivity.EXTRA_CID, -1L).takeIf { it > 0L }
            if (cid != null) putExtra(PlayerActivity.EXTRA_CID, cid)
            currentEpId?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_EP_ID, it) }
            currentAid?.takeIf { it > 0L }?.let { putExtra(PlayerActivity.EXTRA_AID, it) }
            pageListToken?.takeIf { it.isNotBlank() }?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, it) }
            pageListIndex.takeIf { it >= 0 }?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, it) }
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_POSITION_MS, resumePosMs)
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_RESUME_PLAY_WHEN_READY, resumePlayWhenReady)
            putExtra(PlayerActivity.EXTRA_ENGINE_SWITCH_SESSION_JSON, sessionJson)
        }

    startActivity(restart)
    finish()
}

internal fun PlayerActivity.showPlayerEngineDialog() {
    val currentKind = player?.kind ?: session.engineKind
    val items = listOf("ExoPlayer", "IjkPlayer")
    val checked = if (currentKind == PlayerEngineKind.IjkPlayer) 1 else 0
    showSettingsSingleChoiceDialog(
        title = "播放器内核",
        items = items,
        checkedIndex = checked,
    ) { which, _ ->
        val picked = if (which == 1) PlayerEngineKind.IjkPlayer else PlayerEngineKind.ExoPlayer
        if (picked == currentKind) return@showSettingsSingleChoiceDialog
        if (picked == PlayerEngineKind.IjkPlayer) {
            IjkPlayerPluginUi.ensureInstalled(this) {
                restartForEngineSwitch(picked)
            }
        } else {
            restartForEngineSwitch(picked)
        }
    }
}

private fun PlayerActivity.currentSettingsFocusKey(): String? {
    if (!isSettingsPanelVisible()) return null
    val focused = currentFocus ?: return null
    val holder = binding.recyclerSettings.findContainingViewHolder(focused) ?: return null
    val pos = holder.bindingAdapterPosition
    if (pos == RecyclerView.NO_POSITION) return null
    val adapter = binding.recyclerSettings.adapter as? PlayerSettingsAdapter ?: return null
    return adapter.currentList.getOrNull(pos)?.key
}

private fun PlayerActivity.restoreSettingsPanelFocusByKey(key: String): Boolean {
    if (!isSettingsPanelVisible()) return false
    val rv = binding.recyclerSettings
    val adapter = rv.adapter as? PlayerSettingsAdapter ?: return false
    val targetPos = adapter.currentList.indexOfFirst { it.key == key }
    if (targetPos !in 0 until adapter.itemCount) return false

    fun requestFocus(view: View?): Boolean {
        val v = view ?: return false
        if (!v.isAttachedToWindow || !v.isShown || !v.isEnabled || !v.isFocusable) return false
        return v.requestFocus()
    }

    if (requestFocus(rv.findViewHolderForAdapterPosition(targetPos)?.itemView)) return true
    rv.scrollToPosition(targetPos)
    if (requestFocus(rv.findViewHolderForAdapterPosition(targetPos)?.itemView)) return true
    if (requestFocus(rv.getChildAt(0))) return true
    return false
}

private inline fun PlayerActivity.showSettingsSingleChoiceDialog(
    title: CharSequence,
    items: List<String>,
    checkedIndex: Int,
    crossinline onPicked: (index: Int, label: String) -> Unit,
) {
    val restoreFocusKey = currentSettingsFocusKey()
    AppPopup.singleChoice(
        context = this,
        title = title,
        items = items,
        checkedIndex = checkedIndex,
        onRestoreFocus = {
            val key = restoreFocusKey ?: return@singleChoice false
            restoreSettingsPanelFocusByKey(key)
        },
    ) { which, label ->
        onPicked(which, label)
    }
}

internal fun PlayerActivity.showResolutionDialog() {
    // Follow docs: qn list for resolution/framerate.
    // Keep the full list so user can force-pick even if the server later falls back.
    val docQns = listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
    val available = lastAvailableQns.toSet()
    val options =
        docQns.map { qn ->
            val label = qnLabel(qn)
            if (available.contains(qn)) "${label}（可用）" else label
        }

    val currentQn =
        session.actualQn.takeIf { it > 0 }
            ?: session.targetQn.takeIf { it > 0 }
            ?: session.preferredQn
    val currentIndex = docQns.indexOfFirst { it == currentQn }.takeIf { it >= 0 } ?: 0
    showSettingsSingleChoiceDialog(
        title = "分辨率",
        items = options,
        checkedIndex = currentIndex,
    ) { which, _ ->
        val qn = docQns.getOrNull(which) ?: return@showSettingsSingleChoiceDialog
        session =
            if (qn == session.preferredQn) {
                session.copy(targetQn = 0)
            } else {
                session.copy(targetQn = qn)
            }
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        reloadStream(keepPosition = true)
    }
}

internal fun PlayerActivity.showAudioDialog() {
    val docIds = listOf(30251, 30250, 30280, 30232, 30216)
    val available = lastAvailableAudioIds.toSet()
    val options =
        docIds.map { id ->
            val label = audioLabel(id)
            if (available.contains(id)) "${label}（可用）" else label
        }

    val currentId =
        session.actualAudioId.takeIf { it > 0 }
            ?: session.targetAudioId.takeIf { it > 0 }
            ?: session.preferAudioId
    val currentIndex = docIds.indexOfFirst { it == currentId }.takeIf { it >= 0 } ?: 0

    showSettingsSingleChoiceDialog(
        title = "音轨",
        items = options,
        checkedIndex = currentIndex,
    ) { which, _ ->
        val id = docIds.getOrNull(which) ?: return@showSettingsSingleChoiceDialog
        session =
            if (id == session.preferAudioId) {
                session.copy(targetAudioId = 0)
            } else {
                session.copy(targetAudioId = id)
            }
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        reloadStream(keepPosition = true)
    }
}

internal fun PlayerActivity.showCodecDialog() {
    val options = arrayOf("AVC", "HEVC", "AV1")
    val current = options.indexOf(session.preferCodec).coerceAtLeast(0)
    showSettingsSingleChoiceDialog(
        title = "视频编码",
        items = options.toList(),
        checkedIndex = current,
    ) { which, _ ->
        val selected = options.getOrNull(which) ?: "AVC"
        session = session.copy(preferCodec = selected)
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        reloadStream(keepPosition = true)
    }
}

internal fun PlayerActivity.showSpeedDialog() {
    val options = arrayOf("0.50x", "0.75x", "1.00x", "1.25x", "1.50x", "2.00x")
    val current = options.indexOf(String.format(Locale.US, "%.2fx", session.playbackSpeed)).let { if (it >= 0) it else 2 }
    showSettingsSingleChoiceDialog(
        title = "播放速度",
        items = options.toList(),
        checkedIndex = current,
    ) { which, _ ->
        val selected = options.getOrNull(which) ?: "1.00x"
        val v = selected.removeSuffix("x").toFloatOrNull() ?: 1.0f
        session = session.copy(playbackSpeed = v)
        player?.setPlaybackSpeed(v)
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showAudioBalanceDialog() {
    val prefs = BiliClient.prefs
    val options = AudioBalanceLevel.ordered
    val current = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel)
    val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0

    showSettingsSingleChoiceDialog(
        title = "音频平衡",
        items = options.map { it.label },
        checkedIndex = checked,
    ) { which, _ ->
        val picked = options.getOrNull(which) ?: AudioBalanceLevel.Off
        prefs.playerAudioBalanceLevel = picked.prefValue
        val engine = player
        if (engine is ExoPlayerEngine) {
            engine.setAudioBalanceLevel(picked)
            AppToast.show(this, "音频平衡：${picked.label}")
        } else {
            AppToast.show(this, "当前播放器内核不支持音频平衡")
        }
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}

internal fun PlayerActivity.isPgcLikePlayback(): Boolean {
    val epId = currentEpId
    if (epId != null && epId > 0L) return true
    val src = pageListSource?.trim().orEmpty()
    if (src.startsWith("Bangumi:")) return true
    return false
}

internal fun PlayerActivity.resolvedPlaybackMode(): String {
    val prefs = BiliClient.prefs
    val override = session.playbackModeOverride
    val raw =
        override
            ?: if (isPgcLikePlayback()) {
                // PGC (番剧/影视) 默认始终按“播放视频列表”处理，不受全局默认播放模式影响。
                AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST
            } else {
                prefs.playerPlaybackMode
            }

    return when (raw) {
        AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
        -> raw

        else -> AppPrefs.PLAYER_PLAYBACK_MODE_NONE
    }
}

internal fun PlayerActivity.playbackModeLabel(code: String): String =
    when (code) {
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环该视频"
        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> "播放视频列表"
        AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> "播放合集/分P视频"
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> "播放推荐视频"
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
        else -> "什么都不做"
    }

internal fun PlayerActivity.playbackModeSubtitle(): String {
    return playbackModeLabel(resolvedPlaybackMode())
}

internal fun PlayerActivity.applyPlaybackMode(engine: BlblPlayerEngine) {
    engine.repeatMode =
        when (resolvedPlaybackMode()) {
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
}

internal fun PlayerActivity.showPlaybackModeDialog() {
    val engine = player ?: return
    val items =
        listOf(
            "播放视频列表",
            "播放合集/分P视频",
            "播放推荐视频",
            "循环该视频",
            "什么都不做",
            "退出播放器",
        )
    val currentLabel = playbackModeLabel(resolvedPlaybackMode())
    val checked = items.indexOf(currentLabel).coerceAtLeast(0)
    showSettingsSingleChoiceDialog(
        title = "播放模式",
        items = items,
        checkedIndex = checked,
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        val pickedCode =
            when {
                chosen.startsWith("循环") -> AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE
                chosen.startsWith("播放视频列表") -> AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST
                chosen.startsWith("播放合集/分P") -> AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST
                chosen.startsWith("播放推荐") -> AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND
                chosen.startsWith("退出") -> AppPrefs.PLAYER_PLAYBACK_MODE_EXIT
                else -> AppPrefs.PLAYER_PLAYBACK_MODE_NONE
            }
        val defaultCode =
            run {
                val prefs = BiliClient.prefs
                val rawDefault =
                    if (isPgcLikePlayback()) {
                        AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST
                    } else {
                        prefs.playerPlaybackMode
                    }
                when (rawDefault) {
                    AppPrefs.PLAYER_PLAYBACK_MODE_NONE,
                    AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE,
                    AppPrefs.PLAYER_PLAYBACK_MODE_EXIT,
                    AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST,
                    AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST,
                    AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND,
                    -> rawDefault

                    else -> AppPrefs.PLAYER_PLAYBACK_MODE_NONE
                }
            }
        session =
            if (pickedCode == defaultCode) {
                session.copy(playbackModeOverride = null)
            } else {
                session.copy(playbackModeOverride = pickedCode)
            }
        applyPlaybackMode(engine)
        updatePlaylistControls()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.pickSubtitleItem(items: List<SubtitleItem>): SubtitleItem? {
    if (items.isEmpty()) return null
    val prefs = BiliClient.prefs
    val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
    if (preferred == "auto" || preferred.isBlank()) return items.first()
    return items.firstOrNull { it.lan.equals(preferred, ignoreCase = true) } ?: items.first()
}

internal fun PlayerActivity.subtitleLangSubtitle(): String {
    if (subtitleItems.isEmpty()) return "无/未加载"
    val prefs = BiliClient.prefs
    val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
    return resolveSubtitleLang(preferred)
}

internal fun PlayerActivity.resolveSubtitleLang(code: String): String {
    if (subtitleItems.isEmpty()) return "无"
    if (code == "auto" || code.isBlank()) {
        val first = subtitleItems.first()
        return "自动：${first.lanDoc}"
    }
    val found = subtitleItems.firstOrNull { it.lan.equals(code, ignoreCase = true) } ?: subtitleItems.first()
    return "${found.lanDoc}"
}

internal fun PlayerActivity.showSubtitleLangDialog() {
    val exo = (player as? ExoPlayerEngine)?.exoPlayer
    if (exo == null) {
        AppToast.show(this, "当前播放器内核不支持字幕")
        return
    }
    if (subtitleItems.isEmpty()) {
        AppToast.show(this, "该视频暂无字幕")
        return
    }
    val autoLabel = "自动（取第一个）"
    val prefs = BiliClient.prefs
    val items =
        buildList {
            add(autoLabel)
            subtitleItems.forEach { add(it.lanDoc) }
        }
    val effective = (session.subtitleLangOverride ?: prefs.subtitlePreferredLang).trim()
    val currentLabel =
        when {
            effective.equals("auto", ignoreCase = true) || effective.isBlank() -> autoLabel
            else -> subtitleItems.firstOrNull { it.lan.equals(effective, ignoreCase = true) }?.lanDoc ?: subtitleItems.first().lanDoc
        }
    val checked = items.indexOf(currentLabel).coerceAtLeast(0)
    val applyAndReload = {
        lifecycleScope.launch {
            subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = currentBvid, cid = currentCid)
            subtitleAvailabilityKnown = true
            subtitleAvailable = subtitleConfig != null
            applySubtitleEnabled(exo)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            updateSubtitleButton()
            reloadStream(keepPosition = true)
        }
    }
    showSettingsSingleChoiceDialog(
        title = "字幕语言（本次播放）",
        items = items,
        checkedIndex = checked,
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        val pickedCode =
            when {
                chosen.startsWith("自动") -> "auto"
                else -> subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
            }
        val defaultCode =
            prefs.subtitlePreferredLang
                .trim()
                .ifBlank { "auto" }
        session =
            if (pickedCode.equals(defaultCode, ignoreCase = true)) {
                session.copy(subtitleLangOverride = null)
            } else {
                session.copy(subtitleLangOverride = pickedCode)
            }
        applyAndReload()
    }
}

internal fun PlayerActivity.showSubtitleTextSizeDialog() {
    val options = (10..60 step 2).toList()
    val items = options.map { it.toString() }.toTypedArray()
    val current =
        options.indices.minByOrNull { abs(options[it].toFloat() - session.subtitleTextSizeSp) }
            ?: options.indexOf(26).takeIf { it >= 0 }
            ?: 0
    showSettingsSingleChoiceDialog(
        title = "字幕字体大小",
        items = items.toList(),
        checkedIndex = current,
    ) { which, _ ->
        val v = (options.getOrNull(which) ?: session.subtitleTextSizeSp.toInt()).toFloat()
        session = session.copy(subtitleTextSizeSp = v)
        applySubtitleTextSize()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}

internal fun PlayerActivity.showSubtitleBottomPaddingDialog() {
    val options = (0..30 step 2).toList()
    val items = options.map { "${it}%" }
    val current =
        options.indices.minByOrNull { abs(options[it] / 100f - session.subtitleBottomPaddingFraction) }
            ?: options.indexOf(16).takeIf { it >= 0 }
            ?: 0
    showSettingsSingleChoiceDialog(
        title = "字幕底部间距",
        items = items,
        checkedIndex = current,
    ) { which, _ ->
        val percent = options.getOrNull(which) ?: return@showSettingsSingleChoiceDialog
        session = session.copy(subtitleBottomPaddingFraction = (percent / 100f).coerceIn(0f, 0.30f))
        applySubtitleStyle()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}

internal fun PlayerActivity.showSubtitleBackgroundOpacityDialog() {
    val options = (20 downTo 0).map { it / 20f }.toMutableList()
    val defaultOpacity = 34f / 255f
    if (options.none { abs(it - defaultOpacity) < 0.005f }) options.add(defaultOpacity)
    val ordered = options.distinct().sortedDescending()
    val items = ordered.map { String.format(Locale.US, "%.2f", it) }
    val current = ordered.indices.minByOrNull { abs(ordered[it] - session.subtitleBackgroundOpacity) } ?: 0
    showSettingsSingleChoiceDialog(
        title = "字幕背景透明度",
        items = items,
        checkedIndex = current,
    ) { which, _ ->
        val v = ordered.getOrNull(which) ?: return@showSettingsSingleChoiceDialog
        session = session.copy(subtitleBackgroundOpacity = v.coerceIn(0f, 1.0f))
        applySubtitleStyle()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}

internal fun PlayerActivity.configureSubtitleView() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    applySubtitleStyle(subtitleView)
    applySubtitleTextSize()
}

internal fun PlayerActivity.applySubtitleStyle() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    applySubtitleStyle(subtitleView)
}

private fun PlayerActivity.applySubtitleStyle(subtitleView: SubtitleView) {
    val bottomPaddingFraction =
        session.subtitleBottomPaddingFraction
            .let { if (it.isFinite()) it else 0.16f }
            .coerceIn(0f, 0.30f)
    // Prefer padding-based positioning to work consistently across different cue defaults.
    // Keep SubtitleView's own bottomPaddingFraction at 0 to avoid double-applying spacing.
    subtitleView.setBottomPaddingFraction(0f)
    applySubtitlePaddingFraction(subtitleView, bottomPaddingFraction)

    val bgOpacity =
        session.subtitleBackgroundOpacity
            .let { if (it.isFinite()) it else (34f / 255f) }
            .coerceIn(0f, 1.0f)
    val alpha = (bgOpacity * 255f).roundToInt().coerceIn(0, 255)
    val backgroundColor = (alpha shl 24)

    // Make background more transparent while keeping readability.
    subtitleView.setStyle(
        CaptionStyleCompat(
            /* foregroundColor= */ 0xFFFFFFFF.toInt(),
            /* backgroundColor= */ backgroundColor,
            /* windowColor= */ 0x00000000,
            /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor= */ 0xCC000000.toInt(),
            /* typeface= */ null,
        ),
    )
}

private fun PlayerActivity.applySubtitlePaddingFraction(subtitleView: SubtitleView, fraction: Float) {
    val basePadding =
        (subtitleView.getTag(blbl.cat3399.R.id.tag_player_subtitle_base_padding) as? IntArray)
            ?.takeIf { it.size == 4 }
            ?: intArrayOf(
                subtitleView.paddingLeft,
                subtitleView.paddingTop,
                subtitleView.paddingRight,
                subtitleView.paddingBottom,
            ).also { subtitleView.setTag(blbl.cat3399.R.id.tag_player_subtitle_base_padding, it) }

    val h = binding.playerView.height.takeIf { it > 0 } ?: subtitleView.height
    if (h <= 0) {
        subtitleView.post { applySubtitlePaddingFraction(subtitleView, fraction) }
        return
    }
    val extraBottomPx = (h * fraction.coerceIn(0f, 0.30f)).roundToInt().coerceAtLeast(0)
    subtitleView.setPadding(
        /* left= */ basePadding[0],
        /* top= */ basePadding[1],
        /* right= */ basePadding[2],
        /* bottom= */ basePadding[3] + extraBottomPx,
    )
}

internal fun PlayerActivity.applySubtitleTextSize() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    val sizeSp =
        session.subtitleTextSizeSp
            .let { if (it.isFinite()) it else 26f }
            .coerceIn(10f, 60f)
    subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

internal fun PlayerActivity.showDanmakuOpacityDialog() {
    val options = (20 downTo 1).map { it / 20f }
    val items = options.map { String.format(Locale.US, "%.2f", it) }
    val current = options.indices.minByOrNull { kotlin.math.abs(options[it] - session.danmaku.opacity) } ?: 0
    showSettingsSingleChoiceDialog(
        title = "弹幕透明度",
        items = items,
        checkedIndex = current,
    ) { which, _ ->
        val v = options.getOrNull(which) ?: session.danmaku.opacity
        session = session.copy(danmaku = session.danmaku.copy(opacity = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuTextSizeDialog() {
    val options = (10..60 step 2).toList()
    val items = options.map { it.toString() }.toTypedArray()
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].toFloat() - session.danmaku.textSizeSp) }
            ?: options.indexOf(18).takeIf { it >= 0 }
            ?: 0
    showSettingsSingleChoiceDialog(
        title = "弹幕字体大小",
        items = items.toList(),
        checkedIndex = current,
    ) { which, _ ->
        val v = (options.getOrNull(which) ?: session.danmaku.textSizeSp.toInt()).toFloat()
        session = session.copy(danmaku = session.danmaku.copy(textSizeSp = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuSpeedDialog() {
    val options = (1..10).toList()
    val items = options.map { it.toString() }
    val current = options.indexOf(session.danmaku.speedLevel).let { if (it >= 0) it else 3 }
    showSettingsSingleChoiceDialog(
        title = "弹幕速度(1~10)",
        items = items,
        checkedIndex = current,
    ) { which, _ ->
        val v = options.getOrNull(which) ?: session.danmaku.speedLevel
        session = session.copy(danmaku = session.danmaku.copy(speedLevel = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuAreaDialog() {
    val options = listOf(
        (1f / 6f) to "1/6",
        (1f / 5f) to "1/5",
        0.25f to "1/4",
        (1f / 3f) to "1/3",
        (2f / 5f) to "2/5",
        0.50f to "1/2",
        (3f / 5f) to "3/5",
        (2f / 3f) to "2/3",
        0.75f to "3/4",
        (4f / 5f) to "4/5",
        1.00f to "不限",
    )
    val items = options.map { it.second }
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].first - session.danmaku.area) }
            ?: options.lastIndex
    showSettingsSingleChoiceDialog(
        title = "弹幕区域",
        items = items,
        checkedIndex = current,
    ) { which, _ ->
        val v = options.getOrNull(which)?.first ?: session.danmaku.area
        session = session.copy(danmaku = session.danmaku.copy(area = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}
