package blbl.cat3399.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.log.LogExporter
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.update.TestApkUpdater
import blbl.cat3399.feature.risk.GaiaVgateActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

class SettingsInteractionHandler(
    private val activity: SettingsActivity,
    private val state: SettingsState,
    private val gaiaVgateLauncher: ActivityResultLauncher<Intent>,
    private val exportLogsLauncher: ActivityResultLauncher<Uri?>,
) {
    lateinit var renderer: SettingsRenderer

    private var testUpdateJob: Job? = null
    private var testUpdateCheckJob: Job? = null
    private var exportLogsJob: Job? = null
    private var clearCacheJob: Job? = null
    private var cacheSizeJob: Job? = null

    fun onSectionShown(sectionName: String) {
        when (sectionName) {
            "通用设置" -> updateCacheSize(force = false)
            "关于应用" -> ensureTestUpdateChecked(force = false, refreshUi = false)
        }
    }

    fun onGaiaVgateResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val token =
            result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)?.trim()?.takeIf { it.isNotBlank() }
                ?: return
        upsertGaiaVtokenCookie(token)
        val prefs = BiliClient.prefs
        prefs.gaiaVgateVVoucher = null
        prefs.gaiaVgateVVoucherSavedAtMs = -1L
        AppToast.show(activity, "验证成功，已写入风控票据")
        renderer.refreshSection(SettingId.GaiaVgate)
    }

    fun onExportLogsSelected(uri: Uri?) {
        if (uri == null) return
        exportLogsToTreeUri(uri)
    }

    private fun exportLogsToTreeUri(uri: Uri) {
        exportLogsJob?.cancel()
        exportLogsJob =
            activity.lifecycleScope.launch {
                AppToast.show(activity, "正在导出日志…")
                runCatching {
                    withContext(Dispatchers.IO) {
                        LogExporter.exportToTreeUri(activity, uri)
                    }
                }.onSuccess { result ->
                    AppToast.showLong(activity, "已导出：${result.fileName}（${result.includedFiles}个文件）")
                }.onFailure { t ->
                    AppLog.w("Settings", "export logs failed", t)
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                    AppToast.showLong(activity, "导出失败：$msg")
                }
            }
    }

    private fun exportLogsToLocalFile() {
        exportLogsJob?.cancel()
        exportLogsJob =
            activity.lifecycleScope.launch {
                AppToast.show(activity, "正在导出日志到本地…")
                runCatching {
                    withContext(Dispatchers.IO) {
                        LogExporter.exportToLocalFile(activity)
                    }
                }.onSuccess { result ->
                    val path = result.file.absolutePath
                    AppToast.showLong(activity, "无法选择文件夹，已导出到本地：${result.fileName}（${result.includedFiles}个文件）\n路径：$path")
                }.onFailure { t ->
                    AppLog.w("Settings", "export logs (local) failed", t)
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                    AppToast.showLong(activity, "导出失败：$msg")
                }
            }
    }

    private fun canOpenDocumentTree(): Boolean {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        return intent.resolveActivity(activity.packageManager) != null
    }

    fun onEntryClicked(entry: SettingEntry) {
        val prefs = BiliClient.prefs
        state.pendingRestoreRightId = entry.id
        when (entry.id) {
            SettingId.ImageQuality -> {
                val next =
                    when (prefs.imageQuality) {
                        "small" -> "medium"
                        "medium" -> "large"
                        else -> "small"
                    }
                prefs.imageQuality = next
                AppToast.show(activity, "图片质量：$next")
                renderer.refreshSection(entry.id)
            }

            SettingId.UserAgent -> showUserAgentDialog(state.currentSectionIndex, entry.id)
            SettingId.GaiaVgate -> showGaiaVgateDialog(state.currentSectionIndex, entry.id)
            SettingId.ClearCache -> showClearCacheDialog(state.currentSectionIndex, entry.id)
            SettingId.ClearLogin -> showClearLoginDialog(state.currentSectionIndex, entry.id)
            SettingId.ExportLogs -> {
                if (!canOpenDocumentTree()) {
                    exportLogsToLocalFile()
                    return
                }
                try {
                    exportLogsLauncher.launch(null)
                } catch (e: ActivityNotFoundException) {
                    AppLog.w("Settings", "OpenDocumentTree not supported; fallback to local export", e)
                    exportLogsToLocalFile()
                } catch (t: Throwable) {
                    AppLog.w("Settings", "open export logs picker failed; fallback to local export", t)
                    exportLogsToLocalFile()
                }
            }

            SettingId.FullscreenEnabled -> {
                prefs.fullscreenEnabled = !prefs.fullscreenEnabled
                Immersive.apply(activity, prefs.fullscreenEnabled)
                AppToast.show(activity, "全屏：${if (prefs.fullscreenEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.TabSwitchFollowsFocus -> {
                prefs.tabSwitchFollowsFocus = !prefs.tabSwitchFollowsFocus
                AppToast.show(activity, "tab跟随焦点切换：${if (prefs.tabSwitchFollowsFocus) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.StartupPage -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_HOME to "推荐",
                        blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_CATEGORY to "分类",
                        blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_DYNAMIC to "动态",
                        blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_LIVE to "直播",
                        blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_MY to "我的",
                    )
                showChoiceDialog(
                    title = "启动默认页",
                    items = options.map { it.second },
                    current = SettingsText.startupPageText(prefs.startupPage),
                ) { selected ->
                    val key =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_HOME
                    prefs.startupPage = key
                    AppToast.show(activity, "启动默认页：$selected（下次启动生效）")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.SidebarSize -> {
                val options = listOf("小", "中", "大")
                showChoiceDialog(
                    title = "界面大小",
                    items = options,
                    current = SettingsText.sidebarSizeText(prefs.sidebarSize),
                ) { selected ->
                    prefs.sidebarSize =
                        when (selected) {
                            "小" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL
                            "大" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE
                            else -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_MEDIUM
                        }
                    AppToast.show(activity, "界面大小：$selected")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.GridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.gridSpanCount),
                ) { selected ->
                    prefs.gridSpanCount = (selected.toIntOrNull() ?: 4).coerceIn(1, 6)
                    AppToast.show(activity, "每行卡片：${SettingsText.gridSpanText(prefs.gridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DynamicGridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "动态页每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.dynamicGridSpanCount),
                ) { selected ->
                    prefs.dynamicGridSpanCount = (selected.toIntOrNull() ?: 3).coerceIn(1, 6)
                    AppToast.show(activity, "动态每行：${SettingsText.gridSpanText(prefs.dynamicGridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PgcGridSpanCount -> {
                val options = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                showChoiceDialog(
                    title = "番剧/电视剧每行卡片数量",
                    items = options,
                    current = SettingsText.gridSpanText(prefs.pgcGridSpanCount),
                ) { selected ->
                    prefs.pgcGridSpanCount = (selected.toIntOrNull() ?: 6).coerceIn(1, 6)
                    AppToast.show(activity, "番剧每行：${SettingsText.gridSpanText(prefs.pgcGridSpanCount)}")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuEnabled -> {
                prefs.danmakuEnabled = !prefs.danmakuEnabled
                AppToast.show(activity, "弹幕：${if (prefs.danmakuEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.SubtitleEnabledDefault -> {
                prefs.subtitleEnabledDefault = !prefs.subtitleEnabledDefault
                AppToast.show(activity, "默认字幕：${if (prefs.subtitleEnabledDefault) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

            SettingId.SubtitleTextSizeSp -> {
                val options = (10..60 step 2).toList()
                showChoiceDialog(
                    title = "字幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.subtitleTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.subtitleTextSizeSp = (selected.toIntOrNull() ?: 26).toFloat().coerceIn(10f, 60f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuOpacity -> {
                val options = (20 downTo 1).map { it / 20f }
                showChoiceDialog(
                    title = "弹幕透明度",
                    items = options.map { String.format(Locale.US, "%.2f", it) },
                    current = String.format(Locale.US, "%.2f", prefs.danmakuOpacity),
                ) { selected ->
                    prefs.danmakuOpacity = selected.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: prefs.danmakuOpacity
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuTextSizeSp -> {
                val options = (10..60 step 2).toList()
                showChoiceDialog(
                    title = "弹幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.danmakuTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.danmakuTextSizeSp = (selected.toIntOrNull() ?: 18).toFloat().coerceIn(10f, 60f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuArea -> {
                val options =
                    listOf(
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
                showChoiceDialog(
                    title = "弹幕占屏比",
                    items = options.map { it.second },
                    current = SettingsText.areaText(prefs.danmakuArea),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: 1.0f
                    prefs.danmakuArea = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuSpeed -> {
                val options = (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "弹幕速度(1~10)",
                    items = options,
                    current = prefs.danmakuSpeed.toString(),
                ) { selected ->
                    prefs.danmakuSpeed = (selected.toIntOrNull() ?: 4).coerceIn(1, 10)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuFollowBiliShield -> {
                prefs.danmakuFollowBiliShield = !prefs.danmakuFollowBiliShield
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAiShieldEnabled -> {
                prefs.danmakuAiShieldEnabled = !prefs.danmakuAiShieldEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAiShieldLevel -> {
                val options = listOf("默认(3)") + (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = SettingsText.aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = if (selected.startsWith("默认")) 0 else (selected.toIntOrNull() ?: 0)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.DanmakuAllowScroll -> {
                prefs.danmakuAllowScroll = !prefs.danmakuAllowScroll
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowTop -> {
                prefs.danmakuAllowTop = !prefs.danmakuAllowTop
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowBottom -> {
                prefs.danmakuAllowBottom = !prefs.danmakuAllowBottom
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowColor -> {
                prefs.danmakuAllowColor = !prefs.danmakuAllowColor
                renderer.refreshSection(entry.id)
            }

            SettingId.DanmakuAllowSpecial -> {
                prefs.danmakuAllowSpecial = !prefs.danmakuAllowSpecial
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPreferredQn -> {
                val options =
                    listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129).map { it to SettingsText.qnText(it) }
                showChoiceDialog(
                    title = "默认画质",
                    items = options.map { it.second },
                    current = SettingsText.qnText(prefs.playerPreferredQn),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQn = qn
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredQnPortrait -> {
                val options =
                    listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129).map { it to SettingsText.qnText(it) }
                showChoiceDialog(
                    title = "默认画质（竖屏）",
                    items = options.map { it.second },
                    current = SettingsText.qnText(prefs.playerPreferredQnPortrait),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQnPortrait = qn
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredAudioId -> {
                val options = listOf(30251, 30250, 30280, 30232, 30216)
                val optionLabels = options.map { SettingsText.audioText(it) }
                showChoiceDialog(
                    title = "默认音轨",
                    items = optionLabels,
                    current = SettingsText.audioText(prefs.playerPreferredAudioId),
                ) { selected ->
                    val id = options.getOrNull(optionLabels.indexOfFirst { it == selected }.takeIf { it >= 0 } ?: -1)
                    if (id != null) prefs.playerPreferredAudioId = id
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerCdnPreference -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO to "bilivideo（默认）",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN to "mcdn（部分网络更快/更慢）",
                    )
                val checked = options.indexOfFirst { it.first == prefs.playerCdnPreference }.coerceAtLeast(0)
                showChoiceDialog(
                    title = "CDN线路",
                    items = options.map { it.second },
                    checkedIndex = checked,
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO
                    prefs.playerCdnPreference = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerSpeed -> {
                val options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                showChoiceDialog(
                    title = "默认播放速度",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerSpeed = v.coerceIn(0.25f, 3.0f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerHoldSeekSpeed -> {
                val options = listOf(1.5f, 2.0f, 3.0f, 4.0f)
                showChoiceDialog(
                    title = "长按快进倍率",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerHoldSeekSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerHoldSeekSpeed = v.coerceIn(1.5f, 4.0f)
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerHoldSeekMode -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SPEED to "倍率加速",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB to "拖动进度条",
                    )
                val labels = options.map { it.second }
                val checked = options.indexOfFirst { it.first == prefs.playerHoldSeekMode }.coerceAtLeast(0)
                SingleChoiceDialog.show(
                    context = activity,
                    title = "长按快进模式",
                    items = labels,
                    checkedIndex = checked,
                    negativeText = "取消",
                ) { which, _ ->
                    val value =
                        options.getOrNull(which)?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SPEED
                    prefs.playerHoldSeekMode = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerAutoResumeEnabled -> {
                prefs.playerAutoResumeEnabled = !prefs.playerAutoResumeEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerAutoSkipSegmentsEnabled -> {
                prefs.playerAutoSkipSegmentsEnabled = !prefs.playerAutoSkipSegmentsEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerOpenDetailBeforePlay -> {
                prefs.playerOpenDetailBeforePlay = !prefs.playerOpenDetailBeforePlay
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerPlaybackMode -> {
                val options = listOf("循环当前", "播放下一个", "始终播放当前列表", "播放推荐视频", "什么都不做", "退出播放器")
                showChoiceDialog(
                    title = "播放模式（全局默认）",
                    items = options,
                    current = SettingsText.playbackModeText(prefs.playerPlaybackMode),
                ) { selected ->
                    prefs.playerPlaybackMode =
                        when (selected) {
                            "循环当前" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE
                            "播放下一个" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NEXT
                            "始终播放当前列表" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_CURRENT_LIST
                            "播放推荐视频" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND
                            "退出播放器" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NONE
                        }
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.SubtitlePreferredLang -> {
                val options =
                    listOf(
                        "auto" to "自动",
                        "zh-Hans" to "中文(简体)",
                        "zh-Hant" to "中文(繁体)",
                        "en" to "English",
                        "ja" to "日本語",
                        "ko" to "한국어",
                    )
                showChoiceDialog(
                    title = "字幕语言",
                    items = options.map { it.second },
                    current = SettingsText.subtitleLangText(prefs.subtitlePreferredLang),
                ) { selected ->
                    val code = options.firstOrNull { it.second == selected }?.first ?: "auto"
                    prefs.subtitlePreferredLang = code
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPreferredCodec -> {
                val options = listOf("AVC", "HEVC", "AV1")
                showChoiceDialog(
                    title = "视频编码(偏好)",
                    items = options,
                    current = prefs.playerPreferredCodec,
                ) { selected ->
                    prefs.playerPreferredCodec = selected
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerActionButtons -> showPlayerActionButtonsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.PlayerDebugEnabled -> {
                prefs.playerDebugEnabled = !prefs.playerDebugEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerDoubleBackToExit -> {
                prefs.playerDoubleBackToExit = !prefs.playerDoubleBackToExit
                renderer.refreshSection(entry.id)
            }

            SettingId.PlayerDownKeyOsdFocusTarget -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE to "播放/暂停",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV to "上一个",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT to "下一个",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE to "字幕",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU to "弹幕",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP to "UP主",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE to "点赞",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN to "投币",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV to "收藏",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_RECOMMEND to "推荐视频",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PLAYLIST to "播放列表",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED to "更多设置",
                    )
                showChoiceDialog(
                    title = "下键呼出OSD后焦点",
                    items = options.map { it.second },
                    current = SettingsText.downKeyOsdFocusTargetText(prefs.playerDownKeyOsdFocusTarget),
                ) { selected ->
                    val value =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
                    prefs.playerDownKeyOsdFocusTarget = value
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerPersistentBottomProgressEnabled -> {
                prefs.playerPersistentBottomProgressEnabled = !prefs.playerPersistentBottomProgressEnabled
                renderer.refreshSection(entry.id)
            }

            SettingId.ProjectUrl -> showProjectDialog()

            SettingId.QqGroup -> {
                copyToClipboard(label = "QQ交流群", text = SettingsConstants.QQ_GROUP, toastText = "已复制群号：${SettingsConstants.QQ_GROUP}")
            }

            SettingId.CheckUpdate -> {
                when (val checkState = state.testUpdateCheckState) {
                    TestUpdateCheckState.Checking -> {
                        AppToast.show(activity, "正在检查更新…")
                    }

                    is TestUpdateCheckState.UpdateAvailable -> {
                        startTestUpdateDownload(latestVersionHint = checkState.latestVersion)
                    }

                    else -> ensureTestUpdateChecked(force = true, refreshUi = true)
                }
            }

            else -> AppLog.i("Settings", "click id=${entry.id.key} title=${entry.title}")
        }
    }

    private fun showChoiceDialog(title: String, items: List<String>, current: String, onPicked: (String) -> Unit) {
        val checked = items.indexOf(current).takeIf { it >= 0 } ?: 0
        showChoiceDialog(
            title = title,
            items = items,
            checkedIndex = checked,
            onPicked = onPicked,
        )
    }

    private fun showChoiceDialog(title: String, items: List<String>, checkedIndex: Int, onPicked: (String) -> Unit) {
        val checked = checkedIndex.takeIf { it in items.indices } ?: 0
        SingleChoiceDialog.show(
            context = activity,
            title = title,
            items = items,
            checkedIndex = checked,
            negativeText = "取消",
        ) { _, label ->
            onPicked(label)
        }
    }

    private fun showPlayerActionButtonsDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val options =
            listOf(
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_LIKE to "点赞",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_COIN to "投币",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_FAV to "收藏",
            )
        val keys = options.map { it.first }
        val labels = options.map { it.second }.toTypedArray()

        val selected = prefs.playerActionButtons.toMutableSet()
        val checked = BooleanArray(keys.size) { idx -> selected.contains(keys[idx]) }

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle("点赞投币收藏是否显示")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val key = keys[which]
                    if (isChecked) selected.add(key) else selected.remove(key)
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    prefs.playerActionButtons = keys.filter { selected.contains(it) }
                    renderer.showSection(sectionIndex, focusId = focusId)
                }
                .create()

        dialog.setOnShowListener { dialog.listView?.requestFocus() }
        dialog.show()
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val input =
            EditText(activity).apply {
                setText(prefs.userAgent)
                setSelection(text.length)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL
                minLines = 3
            }
        MaterialAlertDialogBuilder(activity)
            .setTitle("User-Agent")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val ua = input.text?.toString().orEmpty().trim()
                if (ua.isBlank()) {
                    AppToast.show(activity, "User-Agent 不能为空")
                    return@setPositiveButton
                }
                prefs.userAgent = ua
                AppToast.show(activity, "已更新 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            }
            .setNeutralButton("重置默认") { _, _ ->
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                AppToast.show(activity, "已重置 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusId: SettingId) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("清除登录")
            .setMessage("将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？")
            .setPositiveButton("确定清除") { _, _ ->
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                BiliClient.prefs.gaiaVgateVVoucher = null
                BiliClient.prefs.gaiaVgateVVoucherSavedAtMs = -1L
                AppToast.show(activity, "已清除 Cookie")
                renderer.showSection(sectionIndex, focusId = focusId)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearCacheDialog(sectionIndex: Int, focusId: SettingId) {
        if (clearCacheJob?.isActive == true) {
            AppToast.show(activity, "清理中…")
            return
        }
        if (testUpdateJob?.isActive == true) {
            AppToast.show(activity, "下载中，稍后再试")
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("清理缓存")
            .setMessage("确定清理缓存？")
            .setPositiveButton("清理") { _, _ -> startClearCache(sectionIndex, focusId) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startClearCache(sectionIndex: Int, focusId: SettingId) {
        cacheSizeJob?.cancel()
        val view = activity.layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "清理中…"

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle("清理中")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> clearCacheJob?.cancel() }
                .setCancelable(false)
                .show()

        clearCacheJob =
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(activity.cacheDir, activity.externalCacheDir)
                        for (dir in dirs) {
                            for (child in (dir.listFiles() ?: emptyArray())) {
                                currentCoroutineContext().ensureActive()
                                runCatching { child.deleteRecursively() }
                            }
                        }
                    }

                    dialog.dismiss()
                    AppToast.show(activity, "已清理缓存")
                    state.cacheSizeBytes = 0L
                    renderer.showSection(sectionIndex, focusId = focusId)
                    updateCacheSize(force = true)
                } catch (_: CancellationException) {
                    dialog.dismiss()
                    AppToast.show(activity, "已取消")
                } catch (t: Throwable) {
                    AppLog.w("Settings", "clear cache failed: ${t.message}", t)
                    dialog.dismiss()
                    AppToast.showLong(activity, "清理失败")
                }
            }
    }

    private fun updateCacheSize(force: Boolean) {
        if (!force && state.cacheSizeBytes != null) return
        if (cacheSizeJob?.isActive == true) return
        cacheSizeJob =
            activity.lifecycleScope.launch {
                val size =
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(activity.cacheDir, activity.externalCacheDir)
                        dirs.sumOf { dirChildrenSizeBytes(it) }.coerceAtLeast(0L)
                    }
                val old = state.cacheSizeBytes
                state.cacheSizeBytes = size
                if (old != size) {
                    renderer.showSection(state.currentSectionIndex, keepScroll = true)
                }
            }
    }

    private fun dirChildrenSizeBytes(dir: File): Long {
        val children = dir.listFiles() ?: return 0L
        var total = 0L
        val stack = ArrayDeque<File>(children.size)
        for (child in children) stack.add(child)
        while (stack.isNotEmpty()) {
            val file = stack.removeLast()
            if (!file.exists()) continue
            if (file.isFile) {
                total += file.length().coerceAtLeast(0L)
            } else {
                val nested = file.listFiles() ?: continue
                for (n in nested) stack.add(n)
            }
        }
        return total.coerceAtLeast(0L)
    }

    private fun ensureTestUpdateChecked(force: Boolean, refreshUi: Boolean = true) {
        if (testUpdateJob?.isActive == true) return
        if (testUpdateCheckJob?.isActive == true) return
        if (state.testUpdateCheckState is TestUpdateCheckState.Checking) return

        val now = System.currentTimeMillis()
        val last = state.testUpdateCheckedAtMs
        val hasFreshResult =
            !force &&
                last > 0 &&
                now - last < SettingsConstants.UPDATE_CHECK_TTL_MS &&
                state.testUpdateCheckState !is TestUpdateCheckState.Idle &&
                state.testUpdateCheckState !is TestUpdateCheckState.Checking
        if (hasFreshResult) return

        state.testUpdateCheckState = TestUpdateCheckState.Checking
        if (refreshUi) renderer.refreshAboutSectionKeepPosition()

        testUpdateCheckJob =
            activity.lifecycleScope.launch {
                try {
                    val latest = TestApkUpdater.fetchLatestVersionName()
                    val current = BuildConfig.VERSION_NAME
                    state.testUpdateCheckState =
                        if (TestApkUpdater.isRemoteNewer(latest, current)) {
                            TestUpdateCheckState.UpdateAvailable(latest)
                        } else {
                            TestUpdateCheckState.Latest(latest)
                        }
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                } catch (_: CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    state.testUpdateCheckState = TestUpdateCheckState.Error(t.message ?: "检查失败")
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                }
                renderer.refreshAboutSectionKeepPosition()
            }
    }

    private fun startTestUpdateDownload(latestVersionHint: String? = null) {
        if (testUpdateJob?.isActive == true) {
            AppToast.show(activity, "正在下载更新…")
            return
        }

        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("需要授权安装")
                .setMessage("更新需要允许“安装未知应用”。现在去设置开启吗？")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        val intent =
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:${activity.packageName}"))
                        activity.startActivity(intent)
                    }.onFailure {
                        AppToast.show(activity, "无法打开系统设置")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val now = System.currentTimeMillis()
        val cooldownLeftMs = TestApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            AppToast.show(activity, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）")
            return
        }

        val view = activity.layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "检查更新…"

        val dialog =
            MaterialAlertDialogBuilder(activity)
                .setTitle("下载更新")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> testUpdateJob?.cancel() }
                .setCancelable(false)
                .show()

        testUpdateJob =
            activity.lifecycleScope.launch {
                try {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val latestVersion = latestVersionHint ?: TestApkUpdater.fetchLatestVersionName()
                    if (!TestApkUpdater.isRemoteNewer(latestVersion, currentVersion)) {
                        state.testUpdateCheckState = TestUpdateCheckState.Latest(latestVersion)
                        state.testUpdateCheckedAtMs = System.currentTimeMillis()
                        renderer.refreshAboutSectionKeepPosition()
                        activity.runOnUiThread {
                            dialog.dismiss()
                            AppToast.show(activity, "已是最新版（当前：$currentVersion）")
                        }
                        return@launch
                    }

                    state.testUpdateCheckState = TestUpdateCheckState.UpdateAvailable(latestVersion)
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                    renderer.refreshAboutSectionKeepPosition()

                    activity.runOnUiThread {
                        tvStatus.text = "准备下载…（最新：$latestVersion）"
                        progress.isIndeterminate = true
                    }

                    TestApkUpdater.markStarted(now)
                    val apkFile =
                        TestApkUpdater.downloadApkToCache(
                            context = activity,
                            url = TestApkUpdater.TEST_APK_URL,
                        ) { dlState ->
                            activity.runOnUiThread {
                                when (dlState) {
                                    TestApkUpdater.Progress.Connecting -> {
                                        progress.isIndeterminate = true
                                        tvStatus.text = "连接中…"
                                    }

                                    is TestApkUpdater.Progress.Downloading -> {
                                        val pct = dlState.percent
                                        if (pct != null) {
                                            progress.isIndeterminate = false
                                            progress.progress = pct.coerceIn(0, 100)
                                            tvStatus.text = "下载中… ${pct.coerceIn(0, 100)}% ${dlState.hint}"
                                        } else {
                                            progress.isIndeterminate = true
                                            tvStatus.text = "下载中… ${dlState.hint}"
                                        }
                                    }
                                }
                            }
                        }

                    activity.runOnUiThread {
                        tvStatus.text = "准备安装…"
                        progress.isIndeterminate = true
                    }
                    TestApkUpdater.installApk(activity, apkFile)
                } catch (_: CancellationException) {
                    activity.runOnUiThread {
                        dialog.dismiss()
                        AppToast.show(activity, "已取消更新")
                    }
                } catch (t: Throwable) {
                    AppLog.w("TestUpdate", "update failed: ${t.message}")
                    activity.runOnUiThread {
                        dialog.dismiss()
                        AppToast.showLong(activity, "更新失败：${t.message ?: "未知错误"}")
                    }
                }
            }
    }

    private fun showProjectDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("项目地址")
            .setMessage(SettingsConstants.PROJECT_URL)
            .setPositiveButton("打开") { _, _ -> openUrl(SettingsConstants.PROJECT_URL) }
            .setNeutralButton("复制") { _, _ ->
                copyToClipboard(label = "项目地址", text = SettingsConstants.PROJECT_URL, toastText = "已复制项目地址")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)))
        }.onFailure {
            AppToast.show(activity, "无法打开链接")
        }
    }

    private fun copyToClipboard(label: String, text: String, toastText: String? = null) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            AppToast.show(activity, "无法访问剪贴板")
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        AppToast.show(activity, toastText ?: "已复制：$text")
    }

    private fun upsertGaiaVtokenCookie(token: String) {
        val expiresAt = System.currentTimeMillis() + 12 * 60 * 60 * 1000L
        val cookie =
            Cookie.Builder()
                .name("x-bili-gaia-vtoken")
                .value(token)
                .domain("bilibili.com")
                .path("/")
                .expiresAt(expiresAt)
                .secure()
                .build()
        BiliClient.cookies.upsert(cookie)
    }

    private fun showGaiaVgateDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val now = System.currentTimeMillis()
        val tokenCookie = BiliClient.cookies.getCookie("x-bili-gaia-vtoken")
        val tokenOk = tokenCookie != null && tokenCookie.expiresAt > now
        val expiresAt = tokenCookie?.expiresAt ?: -1L

        val vVoucher = prefs.gaiaVgateVVoucher.orEmpty().trim()
        val hasVoucher = vVoucher.isNotBlank()
        val savedAt = prefs.gaiaVgateVVoucherSavedAtMs

        val msg =
            buildString {
                append("用于处理播放接口返回 v_voucher 的人机验证（极验）。")
                append("\n\n")
                append("当前票据：")
                append(if (tokenOk) "有效" else "无/已过期")
                if (tokenOk && expiresAt > 0L) {
                    append("\n")
                    append("过期时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", expiresAt))
                }
                append("\n\n")
                append("v_voucher：")
                append(if (hasVoucher) "已记录" else "暂无")
                if (hasVoucher && savedAt > 0L) {
                    append("\n")
                    append("记录时间：").append(android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", savedAt))
                }
            }

        MaterialAlertDialogBuilder(activity)
            .setTitle("风控验证")
            .setMessage(msg)
            .setPositiveButton(if (hasVoucher) "开始验证" else "粘贴 v_voucher") { _, _ ->
                if (hasVoucher) {
                    gaiaVgateLauncher.launch(
                        Intent(activity, GaiaVgateActivity::class.java)
                            .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher),
                    )
                } else {
                    showGaiaVgateVoucherDialog(sectionIndex, focusId)
                }
            }
            .setNeutralButton("编辑 v_voucher") { _, _ ->
                showGaiaVgateVoucherDialog(sectionIndex, focusId)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showGaiaVgateVoucherDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val input =
            EditText(activity).apply {
                setText(prefs.gaiaVgateVVoucher.orEmpty())
                setSelection(text.length)
                hint = "粘贴 v_voucher"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            }
        MaterialAlertDialogBuilder(activity)
            .setTitle("编辑 v_voucher")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text?.toString().orEmpty().trim()
                prefs.gaiaVgateVVoucher = v.takeIf { it.isNotBlank() }
                prefs.gaiaVgateVVoucherSavedAtMs = if (v.isNotBlank()) System.currentTimeMillis() else -1L
                AppToast.show(activity, if (v.isNotBlank()) "已保存 v_voucher" else "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            }
            .setNeutralButton("清除") { _, _ ->
                prefs.gaiaVgateVVoucher = null
                prefs.gaiaVgateVVoucherSavedAtMs = -1L
                AppToast.show(activity, "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
