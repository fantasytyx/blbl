package blbl.cat3399.feature.player

import android.view.KeyEvent
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerPlaybackModes
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.feature.player.engine.ExoPlayerEngine
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.abs
import kotlinx.coroutines.launch

private class PlayerCustomShortcutToggleMemory {
    val playbackSpeedPrevByKey = HashMap<Int, Float>()
    val targetQnPrevByKey = HashMap<Int, Int>()
    val targetAudioIdPrevByKey = HashMap<Int, Int>()
    val codecPrevByKey = HashMap<Int, String>()
    val playbackModeOverridePrevByKey = HashMap<Int, String?>()
    val subtitleLangOverridePrevByKey = HashMap<Int, String?>()
    val subtitleTextSizePrevByKey = HashMap<Int, Float>()
    val danmakuOpacityPrevByKey = HashMap<Int, Float>()
    val danmakuTextSizePrevByKey = HashMap<Int, Float>()
    val danmakuSpeedLevelPrevByKey = HashMap<Int, Int>()
    val danmakuAreaPrevByKey = HashMap<Int, Float>()
}

private val shortcutToggleMemoryByPlayer = WeakHashMap<PlayerActivity, PlayerCustomShortcutToggleMemory>()

private fun PlayerActivity.shortcutToggleMemory(): PlayerCustomShortcutToggleMemory {
    return shortcutToggleMemoryByPlayer.getOrPut(this) { PlayerCustomShortcutToggleMemory() }
}

private fun sameFloat(a: Float, b: Float): Boolean {
    if (!a.isFinite() || !b.isFinite()) return false
    return abs(a - b) < 0.0001f
}

private fun audioIdHintText(id: Int): String = if (id > 0) audioLabel(id) else "默认"

private fun qnHintText(qn: Int): String = if (qn > 0) qnLabel(qn) else "默认"

internal fun PlayerActivity.dispatchPlayerCustomShortcutIfNeeded(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    if (event.repeatCount != 0) return false

    val keyCode = event.keyCode
    if (keyCode <= 0 || keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
    if (PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)) return false
    if (
        !PlayerCustomShortcutInputPolicy.canDispatchInVod(
            hasInteractiveOsd = osdMode != PlayerActivity.OsdMode.Hidden,
            hasSidePanel = isSidePanelVisible(),
            hasBottomCardPanel = isBottomCardPanelVisible(),
        )
    ) {
        return false
    }

    val binding = BiliClient.prefs.playerCustomShortcuts.firstOrNull { it.keyCode == keyCode } ?: return false
    noteUserInteraction()
    applyPlayerCustomShortcut(keyCode = keyCode, action = binding.action)
    return true
}

private fun PlayerActivity.applyPlayerCustomShortcut(keyCode: Int, action: PlayerCustomShortcutAction) {
    val memory = shortcutToggleMemory()
    when (action) {
        is PlayerCustomShortcutAction.OpenVideoList -> {
            if (!showListPanelFromShortcut(target = action.target)) {
                showSeekHint("视频列表：暂无", hold = false)
            }
        }

        PlayerCustomShortcutAction.OpenComments -> {
            showCommentsPanel()
        }

        PlayerCustomShortcutAction.OpenSettings -> {
            showSettingsPanel()
        }

        PlayerCustomShortcutAction.TogglePlayPause -> {
            togglePlayPause(showControls = false, showHint = true)
        }

        PlayerCustomShortcutAction.PlayPrevious -> {
            playPrevByPlaybackMode(userInitiated = true)
        }

        PlayerCustomShortcutAction.PlayNext -> {
            playNextByPlaybackMode(userInitiated = true)
        }

        PlayerCustomShortcutAction.OpenVideoDetail -> {
            openCurrentMediaDetail()
        }

        PlayerCustomShortcutAction.OpenUpDetail -> {
            openCurrentUpDetail()
        }

        PlayerCustomShortcutAction.Like -> {
            onLikeButtonClicked(showControls = false)
        }

        PlayerCustomShortcutAction.Coin -> {
            onCoinButtonClicked(showControls = false)
        }

        PlayerCustomShortcutAction.Fav -> {
            onFavButtonClicked(showControls = false)
        }

        PlayerCustomShortcutAction.ToggleSubtitles -> {
            val engine = player ?: return showSeekHint("字幕：未就绪", hold = false)
            val exo = (engine as? ExoPlayerEngine)?.exoPlayer
            if (exo == null) {
                showSeekHint("字幕：当前内核不支持", hold = false)
                return
            }
            if (!subtitleAvailabilityKnown) {
                showSeekHint("字幕：加载中", hold = false)
                return
            }
            if (!subtitleAvailable) {
                showSeekHint("字幕：暂无", hold = false)
                return
            }
            session = session.copy(subtitleEnabled = !session.subtitleEnabled)
            applySubtitleEnabled(exo)
            updateSubtitleButton()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            val state = if (session.subtitleEnabled) "开" else "关"
            showSeekHint("字幕：$state", hold = false)
        }

        PlayerCustomShortcutAction.ToggleDanmaku -> {
            setDanmakuEnabled(!session.danmaku.enabled)
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            val state = if (session.danmaku.enabled) "开" else "关"
            showSeekHint("弹幕：$state", hold = false)
        }

        PlayerCustomShortcutAction.ToggleDebugOverlay -> {
            session = session.copy(debugEnabled = !session.debugEnabled)
            updateDebugOverlay()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            val state = if (session.debugEnabled) "开" else "关"
            showSeekHint("调试信息：$state", hold = false)
        }

        PlayerCustomShortcutAction.TogglePersistentBottomProgress -> {
            val prefs = BiliClient.prefs
            prefs.playerPersistentBottomProgressEnabled = !prefs.playerPersistentBottomProgressEnabled
            updatePersistentBottomProgressBarVisibility()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            val state = if (prefs.playerPersistentBottomProgressEnabled) "开" else "关"
            showSeekHint("底部进度条：$state", hold = false)
        }

        is PlayerCustomShortcutAction.SetPlaybackSpeed -> {
            val target = action.speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4.0f) ?: 1.0f
            val current = session.playbackSpeed
            val next =
                if (sameFloat(current, target)) {
                    memory.playbackSpeedPrevByKey[keyCode] ?: target
                } else {
                    memory.playbackSpeedPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(playbackSpeed = next)
            player?.setPlaybackSpeed(next)
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("播放速度：${String.format(Locale.US, "%.2fx", next)}", hold = false)
        }

        is PlayerCustomShortcutAction.SetResolutionQn -> {
            val target = action.qn.takeIf { it > 0 } ?: return
            val current = session.targetQn
            val next =
                if (current == target) {
                    memory.targetQnPrevByKey[keyCode] ?: target
                } else {
                    memory.targetQnPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(targetQn = next)
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("分辨率：${qnHintText(next)}", hold = false)
            reloadStream(keepPosition = true)
        }

        is PlayerCustomShortcutAction.SetAudioId -> {
            val target = action.audioId.takeIf { it > 0 } ?: return
            val current = session.targetAudioId
            val next =
                if (current == target) {
                    memory.targetAudioIdPrevByKey[keyCode] ?: target
                } else {
                    memory.targetAudioIdPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(targetAudioId = next)
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("音轨：${audioIdHintText(next)}", hold = false)
            reloadStream(keepPosition = true)
        }

        is PlayerCustomShortcutAction.SetCodec -> {
            val target = action.codec.trim().ifBlank { return }
            val current = session.preferCodec
            val next =
                if (current == target) {
                    memory.codecPrevByKey[keyCode] ?: target
                } else {
                    memory.codecPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(preferCodec = next)
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("视频编码：$next", hold = false)
            reloadStream(keepPosition = true)
        }

        is PlayerCustomShortcutAction.SetPlaybackMode -> {
            val engine = player ?: return
            val mode = action.mode.trim()
            val normalized = PlayerPlaybackModes.normalize(mode)
            val currentOverride = session.playbackModeOverride
            val nextOverride =
                if (currentOverride == normalized) {
                    if (memory.playbackModeOverridePrevByKey.containsKey(keyCode)) {
                        memory.playbackModeOverridePrevByKey[keyCode]
                    } else {
                        normalized
                    }
                } else {
                    memory.playbackModeOverridePrevByKey[keyCode] = currentOverride
                    normalized
                }
            session = session.copy(playbackModeOverride = nextOverride)
            applyPlaybackMode(engine)
            updatePlaylistControls()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("播放模式：${playbackModeLabel(resolvedPlaybackMode())}", hold = false)
        }

        is PlayerCustomShortcutAction.SetSubtitleLang -> {
            val engine = player ?: return showSeekHint("字幕：未就绪", hold = false)
            val exo = (engine as? ExoPlayerEngine)?.exoPlayer
            if (exo == null) {
                showSeekHint("字幕：当前内核不支持", hold = false)
                return
            }

            val rawTarget = action.lang.trim()
            val targetOverride =
                if (rawTarget.equals(PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT, ignoreCase = true) || rawTarget.isBlank()) {
                    null
                } else {
                    rawTarget
                }

            val currentOverride = session.subtitleLangOverride
            val same =
                when {
                    currentOverride == null && targetOverride == null -> true
                    currentOverride == null || targetOverride == null -> false
                    else -> currentOverride.equals(targetOverride, ignoreCase = true)
                }
            val nextOverride =
                if (same) {
                    if (memory.subtitleLangOverridePrevByKey.containsKey(keyCode)) {
                        memory.subtitleLangOverridePrevByKey[keyCode]
                    } else {
                        targetOverride
                    }
                } else {
                    memory.subtitleLangOverridePrevByKey[keyCode] = currentOverride
                    targetOverride
                }
            session = session.copy(subtitleLangOverride = nextOverride)

            showSeekHint("字幕语言：${subtitleLangHintText(nextOverride)}", hold = false)

            // Rebuild subtitle config file and reload stream.
            val bvid = currentBvid
            val cid = currentCid
            if (bvid.isBlank() || cid <= 0L) return
            lifecycleScope.launch {
                subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = bvid, cid = cid)
                subtitleAvailabilityKnown = true
                subtitleAvailable = subtitleConfig != null
                applySubtitleEnabled(exo)
                updateSubtitleButton()
                (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
                reloadStream(keepPosition = true)
            }
        }

        is PlayerCustomShortcutAction.SetSubtitleTextSize -> {
            val target = action.textSizeSp.takeIf { it.isFinite() }?.coerceIn(10f, 60f) ?: 26f
            val current = session.subtitleTextSizeSp
            val next =
                if (sameFloat(current, target)) {
                    memory.subtitleTextSizePrevByKey[keyCode] ?: target
                } else {
                    memory.subtitleTextSizePrevByKey[keyCode] = current
                    target
                }
            session = session.copy(subtitleTextSizeSp = next)
            applySubtitleTextSize()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("字幕大小：${next.toInt()}", hold = false)
        }

        is PlayerCustomShortcutAction.SetDanmakuOpacity -> {
            val target = action.opacity.takeIf { it.isFinite() }?.coerceIn(0.05f, 1.0f) ?: 1.0f
            val current = session.danmaku.opacity
            val next =
                if (sameFloat(current, target)) {
                    memory.danmakuOpacityPrevByKey[keyCode] ?: target
                } else {
                    memory.danmakuOpacityPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(danmaku = session.danmaku.copy(opacity = next))
            binding.danmakuView.invalidate()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("弹幕透明度：${String.format(Locale.US, "%.2f", next)}", hold = false)
        }

        is PlayerCustomShortcutAction.SetDanmakuTextSize -> {
            val target = action.textSizeSp.takeIf { it.isFinite() }?.coerceIn(10f, 60f) ?: 18f
            val current = session.danmaku.textSizeSp
            val next =
                if (sameFloat(current, target)) {
                    memory.danmakuTextSizePrevByKey[keyCode] ?: target
                } else {
                    memory.danmakuTextSizePrevByKey[keyCode] = current
                    target
                }
            session = session.copy(danmaku = session.danmaku.copy(textSizeSp = next))
            binding.danmakuView.invalidate()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("弹幕大小：${next.toInt()}", hold = false)
        }

        is PlayerCustomShortcutAction.SetDanmakuSpeed -> {
            val target = action.speedLevel.coerceIn(1, 10)
            val current = session.danmaku.speedLevel
            val next =
                if (current == target) {
                    memory.danmakuSpeedLevelPrevByKey[keyCode] ?: target
                } else {
                    memory.danmakuSpeedLevelPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(danmaku = session.danmaku.copy(speedLevel = next))
            binding.danmakuView.invalidate()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("弹幕速度：$next", hold = false)
        }

        is PlayerCustomShortcutAction.SetDanmakuArea -> {
            val target = action.area.takeIf { it.isFinite() }?.coerceIn(0.05f, 1.0f) ?: 1.0f
            val current = session.danmaku.area
            val next =
                if (sameFloat(current, target)) {
                    memory.danmakuAreaPrevByKey[keyCode] ?: target
                } else {
                    memory.danmakuAreaPrevByKey[keyCode] = current
                    target
                }
            session = session.copy(danmaku = session.danmaku.copy(area = next))
            binding.danmakuView.invalidate()
            (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
            showSeekHint("弹幕区域：${areaText(next)}", hold = false)
        }
    }
}

private fun subtitleLangHintText(code: String?): String {
    val v = code?.trim().orEmpty()
    if (v.isBlank() || v.equals(PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT, ignoreCase = true)) return "跟随全局"
    return when (v) {
        "auto" -> "自动"
        "zh-Hans" -> "中文(简体)"
        "zh-Hant" -> "中文(繁体)"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> v
    }
}
