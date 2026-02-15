package blbl.cat3399.feature.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.SingleChoiceDialog
import java.util.Locale
import android.util.TypedValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlin.math.abs
import kotlinx.coroutines.launch

internal object PlayerSettingKeys {
    const val RESOLUTION = "resolution"
    const val AUDIO_TRACK = "audio_track"
    const val CODEC = "codec"
    const val PLAYBACK_SPEED = "playback_speed"
    const val PLAYBACK_MODE = "playback_mode"
    const val SUBTITLE_LANG = "subtitle_lang"
    const val SUBTITLE_TEXT_SIZE = "subtitle_text_size"
    const val DANMAKU_OPACITY = "danmaku_opacity"
    const val DANMAKU_TEXT_SIZE = "danmaku_text_size"
    const val DANMAKU_SPEED = "danmaku_speed"
    const val DANMAKU_AREA = "danmaku_area"
    const val DEBUG_INFO = "debug_info"
    const val PERSISTENT_BOTTOM_PROGRESS = "persistent_bottom_progress"
}

internal fun PlayerActivity.handleSettingsItemClick(item: PlayerSettingsAdapter.SettingItem) {
    when (item.key) {
        PlayerSettingKeys.RESOLUTION -> showResolutionDialog()
        PlayerSettingKeys.AUDIO_TRACK -> showAudioDialog()
        PlayerSettingKeys.CODEC -> showCodecDialog()
        PlayerSettingKeys.PLAYBACK_SPEED -> showSpeedDialog()
        PlayerSettingKeys.PLAYBACK_MODE -> showPlaybackModeDialog()
        PlayerSettingKeys.SUBTITLE_LANG -> showSubtitleLangDialog()
        PlayerSettingKeys.SUBTITLE_TEXT_SIZE -> showSubtitleTextSizeDialog()
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
    adapter.submit(
        listOf(
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.RESOLUTION,
                title = "分辨率",
                subtitle = resolutionSubtitle(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.AUDIO_TRACK,
                title = "音轨",
                subtitle = audioSubtitle(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.CODEC,
                title = "视频编码",
                subtitle = session.preferCodec,
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.PLAYBACK_SPEED,
                title = "播放速度",
                subtitle = String.format(Locale.US, "%.2fx", session.playbackSpeed),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.PLAYBACK_MODE,
                title = "播放模式",
                subtitle = playbackModeSubtitle(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.SUBTITLE_LANG,
                title = "字幕语言",
                subtitle = subtitleLangSubtitle(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.SUBTITLE_TEXT_SIZE,
                title = "字幕字体大小",
                subtitle = session.subtitleTextSizeSp.toInt().toString(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.DANMAKU_OPACITY,
                title = "弹幕透明度",
                subtitle = String.format(Locale.US, "%.2f", session.danmaku.opacity),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.DANMAKU_TEXT_SIZE,
                title = "弹幕字体大小",
                subtitle = session.danmaku.textSizeSp.toInt().toString(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.DANMAKU_SPEED,
                title = "弹幕速度",
                subtitle = session.danmaku.speedLevel.toString(),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.DANMAKU_AREA,
                title = "弹幕区域",
                subtitle = areaText(session.danmaku.area),
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.DEBUG_INFO,
                title = "调试信息",
                subtitle = if (session.debugEnabled) "开" else "关",
            ),
            PlayerSettingsAdapter.SettingItem(
                key = PlayerSettingKeys.PERSISTENT_BOTTOM_PROGRESS,
                title = "底部常驻进度条",
                subtitle = if (prefs.playerPersistentBottomProgressEnabled) "开" else "关",
            ),
        ),
    )
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
    SingleChoiceDialog.show(
        context = this,
        title = "分辨率",
        items = options,
        checkedIndex = currentIndex,
        neutralText = "自动",
        onNeutral = {
            session = session.copy(targetQn = 0)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            reloadStream(keepPosition = true)
        },
        negativeText = "取消",
    ) { which, _ ->
        val qn = docQns.getOrNull(which) ?: return@show
        session = session.copy(targetQn = qn)
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

    SingleChoiceDialog.show(
        context = this,
        title = "音轨",
        items = options,
        checkedIndex = currentIndex,
        neutralText = "默认",
        onNeutral = {
            session = session.copy(targetAudioId = 0)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            reloadStream(keepPosition = true)
        },
        negativeText = "取消",
    ) { which, _ ->
        val id = docIds.getOrNull(which) ?: return@show
        session = session.copy(targetAudioId = id)
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        reloadStream(keepPosition = true)
    }
}

internal fun PlayerActivity.showCodecDialog() {
    val options = arrayOf("AVC", "HEVC", "AV1")
    val current = options.indexOf(session.preferCodec).coerceAtLeast(0)
    SingleChoiceDialog.show(
        context = this,
        title = "视频编码",
        items = options.toList(),
        checkedIndex = current,
        negativeText = "取消",
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
    SingleChoiceDialog.show(
        context = this,
        title = "播放速度",
        items = options.toList(),
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val selected = options.getOrNull(which) ?: "1.00x"
        val v = selected.removeSuffix("x").toFloatOrNull() ?: 1.0f
        session = session.copy(playbackSpeed = v)
        player?.setPlaybackSpeed(v)
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.isPgcLikePlayback(): Boolean {
    val epId = currentEpId
    if (epId != null && epId > 0L) return true
    val src = playlistSource?.trim().orEmpty()
    if (src.startsWith("Bangumi:")) return true
    return false
}

internal fun PlayerActivity.resolvedPlaybackMode(): String {
    val prefs = BiliClient.prefs
    val override = session.playbackModeOverride
    if (override != null) return override
    // PGC (番剧/影视) 默认始终按“播放下一个”处理，不受全局默认播放模式影响。
    if (isPgcLikePlayback()) return AppPrefs.PLAYER_PLAYBACK_MODE_NEXT
    return prefs.playerPlaybackMode
}

internal fun PlayerActivity.playbackModeLabel(code: String): String =
    when (code) {
        AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环当前"
        AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> "播放下一个"
        AppPrefs.PLAYER_PLAYBACK_MODE_CURRENT_LIST -> "始终播放当前列表"
        AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> "播放推荐视频"
        AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
        else -> "什么都不做"
    }

internal fun PlayerActivity.playbackModeSubtitle(): String {
    return playbackModeLabel(resolvedPlaybackMode())
}

internal fun PlayerActivity.applyPlaybackMode(exo: ExoPlayer) {
    exo.repeatMode =
        when (resolvedPlaybackMode()) {
            AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
}

internal fun PlayerActivity.showPlaybackModeDialog() {
    val exo = player ?: return
    val items =
        listOf(
            "循环当前",
            "播放下一个",
            "始终播放当前列表",
            "播放推荐视频",
            "什么都不做",
            "退出播放器",
        )
    val currentLabel = playbackModeLabel(resolvedPlaybackMode())
    val checked = items.indexOf(currentLabel).coerceAtLeast(0)
    SingleChoiceDialog.show(
        context = this,
        title = "播放模式",
        items = items,
        checkedIndex = checked,
        negativeText = "取消",
        neutralText = "默认",
        onNeutral = {
            session = session.copy(playbackModeOverride = null)
            applyPlaybackMode(exo)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        },
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        session =
            when {
                chosen.startsWith("循环") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE)
                chosen.startsWith("播放下一个") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_NEXT)
                chosen.startsWith("始终播放当前列表") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_CURRENT_LIST)
                chosen.startsWith("播放推荐") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND)
                chosen.startsWith("退出") -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_EXIT)
                else -> session.copy(playbackModeOverride = AppPrefs.PLAYER_PLAYBACK_MODE_NONE)
            }
        applyPlaybackMode(exo)
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
    val exo = player ?: return
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
    SingleChoiceDialog.show(
        context = this,
        title = "字幕语言（本次播放）",
        items = items,
        checkedIndex = checked,
        negativeText = "取消",
        neutralText = "默认",
        onNeutral = {
            session = session.copy(subtitleLangOverride = null)
            applyAndReload()
        },
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        session =
            when {
                chosen.startsWith("自动") -> session.copy(subtitleLangOverride = "auto")
                else -> {
                    val code = subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
                    session.copy(subtitleLangOverride = code)
                }
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
    SingleChoiceDialog.show(
        context = this,
        title = "字幕字体大小",
        items = items.toList(),
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = (options.getOrNull(which) ?: session.subtitleTextSizeSp.toInt()).toFloat()
        session = session.copy(subtitleTextSizeSp = v)
        applySubtitleTextSize()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}

internal fun PlayerActivity.configureSubtitleView() {
    val subtitleView = binding.playerView.findViewById<SubtitleView>(androidx.media3.ui.R.id.exo_subtitles) ?: return
    // Move subtitles slightly up from the very bottom.
    subtitleView.setBottomPaddingFraction(0.16f)
    // Make background more transparent while keeping readability.
    subtitleView.setStyle(
        CaptionStyleCompat(
            /* foregroundColor= */ 0xFFFFFFFF.toInt(),
            /* backgroundColor= */ 0x22000000,
            /* windowColor= */ 0x00000000,
            /* edgeType= */ CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            /* edgeColor= */ 0xCC000000.toInt(),
            /* typeface= */ null,
        ),
    )
    applySubtitleTextSize()
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
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕透明度",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
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
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕字体大小",
        items = items.toList(),
        checkedIndex = current,
        negativeText = "取消",
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
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕速度(1~10)",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = options.getOrNull(which) ?: session.danmaku.speedLevel
        session = session.copy(danmaku = session.danmaku.copy(speedLevel = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuAreaDialog() {
    val options = listOf(
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
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕区域",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = options.getOrNull(which)?.first ?: session.danmaku.area
        session = session.copy(danmaku = session.danmaku.copy(area = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}
