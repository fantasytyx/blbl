package blbl.cat3399.feature.settings

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.core.view.doOnPreDraw
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.databinding.ActivitySettingsBinding
import blbl.cat3399.feature.player.AudioBalanceLevel
import java.util.Locale

class SettingsRenderer(
    private val activity: SettingsActivity,
    private val binding: ActivitySettingsBinding,
    private val state: SettingsState,
    private val sections: List<String>,
    private val leftAdapter: SettingsLeftAdapter,
    private val rightAdapter: SettingsEntryAdapter,
    private val onSectionShown: (String) -> Unit,
) {
    private var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private val deviceCodecSupportValue: String by lazy { detectHardDecoderSupportValue() }

    fun installFocusListener() {
        if (focusListener != null) return
        focusListener =
            android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null) return@OnGlobalFocusChangeListener
                when {
                    newFocus == binding.btnBack -> {
                        state.pendingRestoreBack = false
                    }

                    FocusTreeUtils.isDescendantOf(newFocus, binding.recyclerLeft) -> {
                        val holder = binding.recyclerLeft.findContainingViewHolder(newFocus) ?: return@OnGlobalFocusChangeListener
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@OnGlobalFocusChangeListener
                        state.lastFocusedLeftIndex = pos
                        if (state.pendingRestoreLeftIndex == pos) state.pendingRestoreLeftIndex = null
                    }

                    FocusTreeUtils.isDescendantOf(newFocus, binding.recyclerRight) -> {
                        val itemView = binding.recyclerRight.findContainingItemView(newFocus) ?: newFocus
                        val id = itemView.tag as? SettingId
                        if (id != null) state.lastFocusedRightId = id
                        if (state.pendingRestoreRightId == id) state.pendingRestoreRightId = null
                    }
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    fun uninstallFocusListener() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
    }

    fun showSection(index: Int, keepScroll: Boolean = index == state.currentSectionIndex, focusId: SettingId? = null) {
        val lm = binding.recyclerRight.layoutManager as? LinearLayoutManager
        val scrollAnchor =
            if (keepScroll && lm != null) {
                val firstVisible = lm.findFirstVisibleItemPosition()
                if (firstVisible != RecyclerView.NO_POSITION) {
                    val anchorView = lm.findViewByPosition(firstVisible) ?: binding.recyclerRight.getChildAt(0)
                    if (anchorView != null) {
                        val anchorPos =
                            binding.recyclerRight.getChildAdapterPosition(anchorView).takeIf { it != RecyclerView.NO_POSITION }
                                ?: firstVisible
                        val anchorOffset = lm.getDecoratedTop(anchorView) - binding.recyclerRight.paddingTop
                        anchorPos to anchorOffset
                    } else {
                        null
                    }
                } else {
                    null
                }
            } else {
                null
            }

        state.currentSectionIndex = index
        val sectionName = sections.getOrNull(index)
        val entries = buildEntriesForSection(sectionName)
        rightAdapter.submit(entries)
        onSectionShown(sectionName.orEmpty())

        state.pendingRestoreRightId = focusId
        val token = ++state.focusRequestToken
        binding.recyclerRight.doOnPreDraw {
            if (token != state.focusRequestToken) return@doOnPreDraw
            if (keepScroll && lm != null) {
                scrollAnchor?.let { (position, offset) ->
                    lm.scrollToPositionWithOffset(position, offset)
                }
            }
            restorePendingFocus()
        }
    }

    fun refreshSection(focusId: SettingId? = null) {
        showSection(state.currentSectionIndex, focusId = focusId)
    }

    fun refreshAboutSectionKeepPosition() {
        if (sections.getOrNull(state.currentSectionIndex) != "关于应用") return
        showSection(state.currentSectionIndex, keepScroll = true, focusId = state.lastFocusedRightId)
    }

    fun ensureInitialFocus() {
        if (activity.currentFocus != null) return
        if (restorePendingFocus()) return
        focusLeftAt(state.lastFocusedLeftIndex.coerceAtLeast(0))
    }

    fun restorePendingFocus(): Boolean {
        if (state.pendingRestoreBack) {
            state.pendingRestoreBack = false
            binding.btnBack.requestFocus()
            return true
        }

        val rightId = state.pendingRestoreRightId ?: state.lastFocusedRightId
        if (rightId != null) {
            if (focusRightById(rightId)) return true
        }

        val leftIndex = state.pendingRestoreLeftIndex ?: state.lastFocusedLeftIndex
        if (focusLeftAt(leftIndex)) {
            return true
        }

        binding.btnBack.requestFocus()
        return true
    }

    fun focusSectionTab(index: Int): Boolean {
        val count = leftAdapter.itemCount
        if (count <= 0) return false
        val safeIndex =
            index.takeIf { it in 0 until count }
                ?: state.lastFocusedLeftIndex.takeIf { it in 0 until count }
                ?: 0
        return focusLeftAt(safeIndex)
    }

    fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    private fun buildEntriesForSection(sectionName: String?): List<SettingEntry> {
        val prefs = BiliClient.prefs
        return when (sectionName) {
            "通用设置" ->
                listOf(
                    SettingEntry(SettingId.ImageQuality, "图片质量", prefs.imageQuality, null),
                    SettingEntry(SettingId.ThemePreset, "主题", SettingsText.themePresetText(prefs.themePreset), null),
                    SettingEntry(SettingId.UserAgent, "User-Agent", prefs.userAgent.take(60), null),
                    SettingEntry(SettingId.Ipv4OnlyEnabled, "是否只允许使用IPV4", if (prefs.ipv4OnlyEnabled) "开" else "关", null),
                    SettingEntry(SettingId.GaiaVgate, "风控验证", gaiaVgateStatusText(), "播放被拦截后可在此手动完成人机验证"),
                    SettingEntry(SettingId.ClearCache, "清理缓存", cacheSizeText(), null),
                    SettingEntry(SettingId.ClearLogin, "清除登录", if (BiliClient.cookies.hasSessData()) "已登录" else "未登录", null),
                )

            "页面设置" ->
                listOf(
                    SettingEntry(SettingId.StartupPage, "启动默认页", SettingsText.startupPageText(prefs.startupPage), null),
                    SettingEntry(SettingId.GridSpanCount, "每行卡片数量", SettingsText.gridSpanText(prefs.gridSpanCount), null),
                    SettingEntry(
                        SettingId.DynamicGridSpanCount,
                        "动态页每行卡片数量",
                        SettingsText.gridSpanText(prefs.dynamicGridSpanCount),
                        null,
                    ),
                    SettingEntry(SettingId.PgcGridSpanCount, "番剧/电视剧每行卡片数量", SettingsText.gridSpanText(prefs.pgcGridSpanCount), null),
                    SettingEntry(SettingId.UiScaleFactor, "界面大小", SettingsText.uiScaleFactorText(prefs.uiScaleFactor), null),
                    SettingEntry(SettingId.FullscreenEnabled, "以全屏模式运行", if (prefs.fullscreenEnabled) "开" else "关", null),
                    SettingEntry(SettingId.TabSwitchFollowsFocus, "tab跟随焦点切换", if (prefs.tabSwitchFollowsFocus) "开" else "关", null),
                    SettingEntry(
                        SettingId.MainBackFocusScheme,
                        "返回键焦点策略",
                        SettingsText.mainBackFocusSchemeText(prefs.mainBackFocusScheme),
                        null,
                    ),
                )

            "播放设置" ->
                listOf(
                    SettingEntry(SettingId.PlayerPreferredQn, "默认画质", SettingsText.qnText(prefs.playerPreferredQn), null),
                    SettingEntry(
                        SettingId.PlayerPreferredQnPortrait,
                        "默认画质（竖屏）",
                        SettingsText.qnText(prefs.playerPreferredQnPortrait),
                        null,
                    ),
                    SettingEntry(SettingId.PlayerPreferredAudioId, "默认音轨", SettingsText.audioText(prefs.playerPreferredAudioId), null),
                    SettingEntry(SettingId.PlayerSpeed, "默认播放速度", String.format(java.util.Locale.US, "%.2fx", prefs.playerSpeed), null),
                    SettingEntry(
                        SettingId.PlayerHoldSeekSpeed,
                        "长按快进倍率",
                        String.format(java.util.Locale.US, "%.2fx", prefs.playerHoldSeekSpeed),
                        null,
                    ),
                    SettingEntry(SettingId.PlayerHoldSeekMode, "长按快进模式", SettingsText.holdSeekModeText(prefs.playerHoldSeekMode), null),
                    SettingEntry(SettingId.PlayerAutoResumeEnabled, "自动跳到上次播放位置", if (prefs.playerAutoResumeEnabled) "开" else "关", null),
                    SettingEntry(
                        SettingId.PlayerAutoSkipSegmentsEnabled,
                        "自动跳过片段（空降助手）",
                        if (prefs.playerAutoSkipSegmentsEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(SettingId.PlayerOpenDetailBeforePlay, "播放前打开详情页", if (prefs.playerOpenDetailBeforePlay) "开" else "关", null),
                    SettingEntry(SettingId.PlayerPlaybackMode, "播放模式", SettingsText.playbackModeText(prefs.playerPlaybackMode), null),
                    SettingEntry(SettingId.SubtitlePreferredLang, "字幕语言", SettingsText.subtitleLangText(prefs.subtitlePreferredLang), null),
                    SettingEntry(SettingId.SubtitleTextSizeSp, "字幕字体大小", prefs.subtitleTextSizeSp.toInt().toString(), null),
                    SettingEntry(
                        SettingId.SubtitleBottomPaddingFraction,
                        "字幕底部间距",
                        SettingsText.subtitleBottomPaddingText(prefs.subtitleBottomPaddingFraction),
                        null,
                    ),
                    SettingEntry(
                        SettingId.SubtitleBackgroundOpacity,
                        "字幕背景透明度",
                        SettingsText.subtitleBackgroundOpacityText(prefs.subtitleBackgroundOpacity),
                        null,
                    ),
                    SettingEntry(SettingId.SubtitleEnabledDefault, "默认开启字幕", if (prefs.subtitleEnabledDefault) "开" else "关", null),
                    SettingEntry(SettingId.PlayerPreferredCodec, "视频编码", prefs.playerPreferredCodec, null),
                    SettingEntry(SettingId.PlayerOsdButtons, "OSD按钮显示", SettingsText.playerOsdButtonsText(prefs.playerOsdButtons), null),
                    SettingEntry(SettingId.PlayerDoubleBackToExit, "按两次退出键才退出播放器", if (prefs.playerDoubleBackToExit) "开" else "关", null),
                    SettingEntry(
                        SettingId.PlayerDownKeyOsdFocusTarget,
                        "下键呼出OSD后焦点",
                        SettingsText.downKeyOsdFocusTargetText(prefs.playerDownKeyOsdFocusTarget),
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerPersistentBottomProgressEnabled,
                        "底部常驻进度条",
                        if (prefs.playerPersistentBottomProgressEnabled) "开" else "关",
                        null,
                    ),
                    SettingEntry(
                        SettingId.PlayerVideoShotPreviewSize,
                        "缩略图显示",
                        SettingsText.videoShotPreviewSizeText(prefs.playerVideoShotPreviewSize),
                        null,
                    ),
                )

            "其他设置" ->
                listOf(
                    SettingEntry(SettingId.PlayerRenderView, "渲染视图", SettingsText.renderViewText(prefs.playerRenderViewType), null),
                    SettingEntry(SettingId.PlayerEngineKind, "播放器内核", SettingsText.playerEngineText(prefs.playerEngineKind), null),
                    SettingEntry(
                        SettingId.PlayerCustomShortcuts,
                        "自定义播放快捷键",
                        prefs.playerCustomShortcuts.let { if (it.isEmpty()) "未设置" else "已设置 ${it.size} 个" },
                        "播放时按指定按键切换播放设置（再按一次切回上次值）",
                    ),
                    SettingEntry(
                        SettingId.PlayerAudioBalance,
                        "音频平衡",
                        AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel).label,
                        null,
                    ),
                    SettingEntry(SettingId.PlayerCdnPreference, "CDN线路", SettingsText.cdnText(prefs.playerCdnPreference), null),
                    SettingEntry(
                        SettingId.LiveHighBitrateEnabled,
                        "提高直播码率",
                        if (prefs.liveHighBitrateEnabled) "开" else "关",
                        "如果直播遇到问题,请关闭此功能",
                    ),
                    SettingEntry(SettingId.PlayerDebugEnabled, "显示视频调试信息", if (prefs.playerDebugEnabled) "开" else "关", null),
                )

            "弹幕设置" ->
                listOf(
                    SettingEntry(SettingId.DanmakuEnabled, "弹幕开关", if (prefs.danmakuEnabled) "开" else "关", null),
                    SettingEntry(
                        SettingId.DanmakuOpacity,
                        "弹幕透明度",
                        String.format(java.util.Locale.US, "%.2f", prefs.danmakuOpacity),
                        null,
                    ),
                    SettingEntry(SettingId.DanmakuTextSizeSp, "弹幕字体大小", prefs.danmakuTextSizeSp.toInt().toString(), null),
                    SettingEntry(SettingId.DanmakuArea, "弹幕占屏比", SettingsText.areaText(prefs.danmakuArea), null),
                    SettingEntry(SettingId.DanmakuSpeed, "弹幕速度", prefs.danmakuSpeed.toString(), null),
                    SettingEntry(SettingId.DanmakuFollowBiliShield, "跟随B站弹幕屏蔽", if (prefs.danmakuFollowBiliShield) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAiShieldEnabled, "智能云屏蔽", if (prefs.danmakuAiShieldEnabled) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAiShieldLevel, "智能云屏蔽等级", SettingsText.aiLevelText(prefs.danmakuAiShieldLevel), null),
                    SettingEntry(SettingId.DanmakuAllowScroll, "允许滚动弹幕", if (prefs.danmakuAllowScroll) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowTop, "允许顶部悬停弹幕", if (prefs.danmakuAllowTop) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowBottom, "允许底部悬停弹幕", if (prefs.danmakuAllowBottom) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowColor, "允许彩色弹幕", if (prefs.danmakuAllowColor) "开" else "关", null),
                    SettingEntry(SettingId.DanmakuAllowSpecial, "允许特殊弹幕", if (prefs.danmakuAllowSpecial) "开" else "关", null),
                )

            "关于应用" ->
                listOf(
                    SettingEntry(SettingId.AppVersion, "版本", BuildConfig.VERSION_NAME, null),
                    SettingEntry(SettingId.ProjectUrl, "项目地址", SettingsConstants.PROJECT_URL, null),
                    SettingEntry(SettingId.QqGroup, "QQ交流群", SettingsConstants.QQ_GROUP, null),
                    SettingEntry(SettingId.LogTag, "日志标签", "BLBL", "用于 Logcat 过滤"),
                    SettingEntry(SettingId.ExportLogs, "导出日志", "选择文件夹", null),
                    SettingEntry(SettingId.UploadLogs, "上传日志", "点击上传", "打包并上传日志zip到开发者（含设备/版本等元数据）"),
                    aboutUpdateEntry(),
                )

            "设备信息" ->
                listOf(
                    SettingEntry(SettingId.DeviceCpu, "CPU", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), null),
                    SettingEntry(SettingId.DeviceModel, "设备", "${Build.MANUFACTURER} ${Build.MODEL}", null),
                    SettingEntry(SettingId.DeviceSystem, "系统", "Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}", null),
                    SettingEntry(SettingId.DeviceScreen, "屏幕", SettingsText.screenText(activity.resources), null),
                    SettingEntry(SettingId.DeviceRam, "RAM", SettingsText.ramText(activity), null),
                    SettingEntry(SettingId.DeviceDecoder, "硬件解码器", deviceCodecSupportValue, null),
                )

            else -> emptyList()
        }
    }

    private fun detectHardDecoderSupportValue(): String {
        val support = runCatching { queryHardDecoderSupport() }.getOrNull() ?: return "-"
        return "H264 ${markSupport(support.h264)} / H265 ${markSupport(support.h265)} / AV1 ${markSupport(support.av1)}"
    }

    private fun markSupport(supported: Boolean): String = if (supported) "✓" else "✗"

    private fun queryHardDecoderSupport(): HardDecoderSupport {
        var h264 = false
        var h265 = false
        var av1 = false
        for (codecInfo in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (codecInfo.isEncoder) continue
            if (!isHardwareDecoder(codecInfo)) continue
            for (mime in codecInfo.supportedTypes) {
                when (mime.lowercase(Locale.US)) {
                    "video/avc" -> h264 = true
                    "video/hevc" -> h265 = true
                    "video/av01", "video/av1" -> av1 = true
                }
            }
            if (h264 && h265 && av1) break
        }
        return HardDecoderSupport(h264 = h264, h265 = h265, av1 = av1)
    }

    private fun isHardwareDecoder(codecInfo: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (codecInfo.isAlias) return false
            return codecInfo.isHardwareAccelerated
        }
        val name = codecInfo.name.lowercase(Locale.US)
        if (name.startsWith("omx.google.")) return false
        if (name.startsWith("c2.android.")) return false
        if (name.startsWith("c2.google.")) return false
        if (name.contains(".sw.")) return false
        if (name.contains("software")) return false
        if (name.contains("ffmpeg")) return false
        return true
    }

    private data class HardDecoderSupport(
        val h264: Boolean,
        val h265: Boolean,
        val av1: Boolean,
    )

    private fun gaiaVgateStatusText(): String {
        val now = System.currentTimeMillis()
        val tokenCookie = BiliClient.cookies.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val voucherOk = !BiliClient.prefs.gaiaVgateVVoucher.isNullOrBlank()
        return when {
            tokenOk -> "已通过"
            voucherOk -> "待验证"
            else -> "无"
        }
    }

    private fun cacheSizeText(): String {
        val size = state.cacheSizeBytes ?: return "-"
        return SettingsText.formatBytes(size)
    }

    private fun aboutUpdateEntry(): SettingEntry {
        val currentVersion = BuildConfig.VERSION_NAME
        val title = "检查更新"
        val defaultDesc = "检查新版本并下载安装"
        return when (val checkState = state.testUpdateCheckState) {
            TestUpdateCheckState.Idle -> SettingEntry(SettingId.CheckUpdate, title, "点击检查", defaultDesc)
            TestUpdateCheckState.Checking -> SettingEntry(SettingId.CheckUpdate, title, "检查中…", "正在获取最新版本号…")

            is TestUpdateCheckState.Latest ->
                SettingEntry(
                    SettingId.CheckUpdate,
                    title,
                    "已是最新版",
                    "当前：$currentVersion / 最新：${checkState.latestVersion}",
                )

            is TestUpdateCheckState.UpdateAvailable ->
                SettingEntry(SettingId.CheckUpdate, title, "新版本 ${checkState.latestVersion}", "当前：$currentVersion，点击更新")

            is TestUpdateCheckState.Error -> {
                val msg = checkState.message.trim().take(80)
                val desc = if (msg.isBlank()) "检查失败，点击重试" else "检查失败，点击重试（$msg）"
                SettingEntry(SettingId.CheckUpdate, title, "检查失败", desc)
            }
        }
    }

    private fun focusRightById(id: SettingId): Boolean {
        val pos = rightAdapter.indexOfId(id)
        if (pos == RecyclerView.NO_POSITION) return false
        val holder = binding.recyclerRight.findViewHolderForAdapterPosition(pos)
        if (holder?.itemView?.requestFocus() == true) return true
        return focusRightAt(pos)
    }

    private fun focusRightAt(position: Int): Boolean {
        if (position < 0 || position >= rightAdapter.itemCount) return false
        val token = ++state.focusRequestToken
        val layoutManager = binding.recyclerRight.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()
            if (position < first || position > last) {
                layoutManager.scrollToPositionWithOffset(position, 0)
            }
        }
        binding.recyclerRight.doOnPreDraw {
            if (token != state.focusRequestToken) return@doOnPreDraw
            binding.recyclerRight.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLeftAt(position: Int): Boolean {
        if (position < 0 || position >= leftAdapter.itemCount) return false
        val token = ++state.focusRequestToken
        val holder = binding.recyclerLeft.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) return true
        binding.recyclerLeft.scrollToPosition(position)
        binding.recyclerLeft.doOnPreDraw {
            if (token != state.focusRequestToken) return@doOnPreDraw
            binding.recyclerLeft.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }
}
