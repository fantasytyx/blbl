package blbl.cat3399.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.log.LogExporter
import blbl.cat3399.core.log.LogUploadClient
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.prefs.PlayerCustomShortcut
import blbl.cat3399.core.prefs.PlayerCustomShortcutAction
import blbl.cat3399.core.prefs.PlayerCustomShortcutsStore
import blbl.cat3399.core.theme.LauncherAliasManager
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupAction
import blbl.cat3399.core.ui.popup.PopupActionRole
import blbl.cat3399.core.update.ApkUpdater
import blbl.cat3399.feature.player.engine.IjkPlayerPluginUi
import blbl.cat3399.feature.player.AudioBalanceLevel
import blbl.cat3399.feature.risk.GaiaVgateActivity
import blbl.cat3399.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray

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
    private var uploadLogsJob: Job? = null
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
                val nowMs = System.currentTimeMillis()
                val deviceUuid = BiliClient.prefs.deviceUuid
                val metaJson = buildUploadMetaJson(nowMs = nowMs, deviceUuid = deviceUuid)
                runCatching {
                    withContext(Dispatchers.IO) {
                        LogExporter.exportToTreeUri(
                            context = activity,
                            treeUri = uri,
                            nowMs = nowMs,
                            extras =
                                listOf(
                                    LogExporter.ZipExtra(
                                        path = "meta.json",
                                        bytes = metaJson.toByteArray(Charsets.UTF_8),
                                    ),
                                ),
                        )
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
                val nowMs = System.currentTimeMillis()
                val deviceUuid = BiliClient.prefs.deviceUuid
                val metaJson = buildUploadMetaJson(nowMs = nowMs, deviceUuid = deviceUuid)
                runCatching {
                    withContext(Dispatchers.IO) {
                        LogExporter.exportToLocalFile(
                            context = activity,
                            nowMs = nowMs,
                            extras =
                                listOf(
                                    LogExporter.ZipExtra(
                                        path = "meta.json",
                                        bytes = metaJson.toByteArray(Charsets.UTF_8),
                                    ),
                                ),
                        )
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

    private fun showUploadLogsDialog() {
        if (uploadLogsJob?.isActive == true) {
            AppToast.show(activity, "正在上传…")
            return
        }

        AppPopup.confirm(
            context = activity,
            title = "上传日志",
            message =
                "将日志上传给开发者便于排查问题。\n\n" +
                    "反馈问题时请带上上传成功后显示的文件名。",
            positiveText = "上传",
            negativeText = "取消",
            cancelable = true,
            onPositive = { startUploadLogs() },
        )
    }

    private fun startUploadLogs() {
        uploadLogsJob?.cancel()
        val popup =
            AppPopup.progress(
                context = activity,
                title = "上传日志",
                status = "准备中…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { uploadLogsJob?.cancel() },
            )

        uploadLogsJob =
            activity.lifecycleScope.launch {
                var exportedFile: File? = null
                try {
                    val nowMs = System.currentTimeMillis()
                    val deviceUuid = BiliClient.prefs.deviceUuid
                    val epochSeconds = (nowMs / 1000L).coerceAtLeast(0L)
                    val deviceId8 = deviceUuid.replace("-", "").take(8).ifBlank { "unknown00" }
                    val fileName = "${epochSeconds}-${deviceId8}.zip"
                    val metaJson = buildUploadMetaJson(nowMs = nowMs, deviceUuid = deviceUuid)

                    popup?.updateProgress(null)
                    popup?.updateStatus("打包中…")
                    val export =
                        withContext(Dispatchers.IO) {
                            LogExporter.exportToLocalFile(
                                context = activity,
                                nowMs = nowMs,
                                fileNameOverride = fileName,
                                extras =
                                    listOf(
                                        LogExporter.ZipExtra(
                                            path = "meta.json",
                                            bytes = metaJson.toByteArray(Charsets.UTF_8),
                                        ),
                                    ),
                            )
                        }
                    exportedFile = export.file

                    currentCoroutineContext().ensureActive()
                    popup?.updateProgress(0)
                    popup?.updateStatus("上传中… 0%")
                    var lastPct = -1
                    var lastUpdateAtMs = 0L
                    withContext(Dispatchers.IO) {
                        LogUploadClient.uploadZip(
                            file = export.file,
                            fileName = export.fileName,
                            onProgress = { sentBytes, totalBytes ->
                                if (totalBytes <= 0L) return@uploadZip
                                val pct = ((sentBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (pct == lastPct && now - lastUpdateAtMs < 80L) return@uploadZip
                                lastPct = pct
                                lastUpdateAtMs = now
                                val hint = "${SettingsText.formatBytes(sentBytes)}/${SettingsText.formatBytes(totalBytes)}"
                                popup?.updateProgress(pct)
                                popup?.updateStatus("上传中… ${pct}% $hint")
                            },
                        )
                    }

                    popup?.dismiss()
                    showUploadLogsSuccessPopup(
                        fileName = export.fileName,
                    )
                } catch (_: CancellationException) {
                    popup?.dismiss()
                } catch (t: Throwable) {
                    popup?.dismiss()
                    AppLog.w("Settings", "upload logs failed", t)
                    val msg = t.message?.takeIf { it.isNotBlank() } ?: "未知错误"
                    AppToast.showLong(activity, "上传失败：$msg")
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        exportedFile?.let { runCatching { it.delete() } }
                    }
                }
            }
    }

    private fun showUploadLogsSuccessPopup(
        fileName: String,
    ) {
        val body = "文件：$fileName"

        AppPopup.custom(
            context = activity,
            title = "上传成功",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "复制文件名") {
                        copyToClipboard(label = "日志文件名", text = fileName, toastText = "已复制文件名")
                    },
                ),
            preferredActionRole = PopupActionRole.NEUTRAL,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = body
                tv
            },
        )
    }

    private fun formatUploadTimestamp(nowMs: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return runCatching { sdf.format(Date(nowMs)) }.getOrNull()?.takeIf { it.isNotBlank() } ?: nowMs.toString()
    }

    private fun buildUploadMetaJson(
        nowMs: Long,
        deviceUuid: String,
    ): String {
        val tzId = runCatching { java.util.TimeZone.getDefault().id }.getOrNull().orEmpty()
        val locale = runCatching { Locale.getDefault() }.getOrNull()
        val localeTag = runCatching { locale?.toLanguageTag() }.getOrNull().orEmpty()
        val prefs = BiliClient.prefs

        val json =
            JSONObject()
                .put("schema", 1)
                .put("device_uuid", deviceUuid)
                .put("export_at_ms", nowMs)
                .put("export_at", formatUploadTimestamp(nowMs))
                .put("time_zone", tzId)
                .put("locale", localeTag)
                .put(
                    "app",
                    JSONObject()
                        .put("package", BuildConfig.APPLICATION_ID)
                        .put("version_name", BuildConfig.VERSION_NAME)
                        .put("version_code", BuildConfig.VERSION_CODE)
                        .put("build_type", BuildConfig.BUILD_TYPE)
                        .put("debug", BuildConfig.DEBUG),
                )
                .put(
                    "device",
                    JSONObject()
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("model", Build.MODEL)
                        .put("sdk_int", Build.VERSION.SDK_INT)
                        .put("release", Build.VERSION.RELEASE)
                        .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
                )
                .put(
                    "account",
                    JSONObject()
                        .put("is_logged_in", BiliClient.cookies.hasSessData()),
                )
                .put("screen", buildUploadScreenJson())
                .put("prefs", buildUploadPrefsJson(prefs))

        return json.toString(2)
    }

    private fun buildUploadScreenJson(): JSONObject {
        val res = activity.resources
        val dm = res.displayMetrics
        val cfg = res.configuration

        val orientation =
            when (cfg.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                else -> "undefined"
            }
        val nightMode =
            when (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> "yes"
                Configuration.UI_MODE_NIGHT_NO -> "no"
                else -> "undefined"
            }

        val sysDm = android.content.res.Resources.getSystem().displayMetrics
        val scaledDensity = dm.density * cfg.fontScale
        val systemScaledDensity = sysDm.density * cfg.fontScale

        return JSONObject()
            .put("width_px", dm.widthPixels)
            .put("height_px", dm.heightPixels)
            .put("density", dm.density)
            .put("scaled_density", scaledDensity)
            .put("density_dpi", dm.densityDpi)
            .put("xdpi", dm.xdpi)
            .put("ydpi", dm.ydpi)
            .put("font_scale", cfg.fontScale)
            .put("screen_width_dp", cfg.screenWidthDp)
            .put("screen_height_dp", cfg.screenHeightDp)
            .put("smallest_screen_width_dp", cfg.smallestScreenWidthDp)
            .put("orientation", orientation)
            .put("night_mode", nightMode)
            .put(
                "system_display_scale",
                JSONObject()
                    .put("density", sysDm.density)
                    .put("scaled_density", systemScaledDensity)
                    .put("density_dpi", sysDm.densityDpi),
            )
    }

    private fun buildUploadPrefsJson(prefs: AppPrefs): JSONObject {
        val osdButtons = JSONArray()
        for (b in prefs.playerOsdButtons) osdButtons.put(b)

        return JSONObject()
            .put(
                "ui",
                JSONObject()
                    .put("theme_preset", prefs.themePreset)
                    .put("ui_scale_factor", prefs.uiScaleFactor)
                    .put("sidebar_size", prefs.sidebarSize)
                    .put("startup_page", prefs.startupPage)
                    .put("image_quality", prefs.imageQuality)
                    .put("fullscreen_enabled", prefs.fullscreenEnabled)
                    .put("tab_switch_follows_focus", prefs.tabSwitchFollowsFocus)
                    .put("main_back_focus_scheme", prefs.mainBackFocusScheme)
                    .put("grid_span", prefs.gridSpanCount)
                    .put("dynamic_grid_span", prefs.dynamicGridSpanCount)
                    .put("pgc_grid_span", prefs.pgcGridSpanCount)
                    .put("pgc_episode_order_reversed", prefs.pgcEpisodeOrderReversed),
            )
            .put(
                "network",
                JSONObject()
                    .put("ipv4_only_enabled", prefs.ipv4OnlyEnabled)
                    .put("user_agent", prefs.userAgent)
                    .put("player_cdn_preference", prefs.playerCdnPreference),
            )
            .put(
                "danmaku",
                JSONObject()
                    .put("enabled", prefs.danmakuEnabled)
                    .put("allow_top", prefs.danmakuAllowTop)
                    .put("allow_bottom", prefs.danmakuAllowBottom)
                    .put("allow_scroll", prefs.danmakuAllowScroll)
                    .put("allow_color", prefs.danmakuAllowColor)
                    .put("allow_special", prefs.danmakuAllowSpecial)
                    .put("follow_bili_shield", prefs.danmakuFollowBiliShield)
                    .put("ai_shield_enabled", prefs.danmakuAiShieldEnabled)
                    .put("ai_shield_level", prefs.danmakuAiShieldLevel)
                    .put("opacity", prefs.danmakuOpacity)
                    .put("text_size_sp", prefs.danmakuTextSizeSp)
                    .put("speed", prefs.danmakuSpeed)
                    .put("area", prefs.danmakuArea),
            )
            .put(
                "player",
                JSONObject()
                    .put("preferred_qn", prefs.playerPreferredQn)
                    .put("preferred_qn_portrait", prefs.playerPreferredQnPortrait)
                    .put("preferred_codec", prefs.playerPreferredCodec)
                    .put("render_view_type", prefs.playerRenderViewType)
                    .put("engine_kind", prefs.playerEngineKind)
                    .put("preferred_audio_id", prefs.playerPreferredAudioId)
                    .put("subtitle_lang", prefs.subtitlePreferredLang)
                    .put("subtitle_enabled_default", prefs.subtitleEnabledDefault)
                    .put("subtitle_text_size_sp", prefs.subtitleTextSizeSp)
                    .put("subtitle_bottom_padding_fraction", prefs.subtitleBottomPaddingFraction)
                    .put("subtitle_background_opacity", prefs.subtitleBackgroundOpacity)
                    .put("speed", prefs.playerSpeed)
                    .put("hold_seek_speed", prefs.playerHoldSeekSpeed)
                    .put("hold_seek_mode", prefs.playerHoldSeekMode)
                    .put("auto_resume_enabled", prefs.playerAutoResumeEnabled)
                    .put("auto_skip_segments_enabled", prefs.playerAutoSkipSegmentsEnabled)
                    .put("open_detail_before_play", prefs.playerOpenDetailBeforePlay)
                    .put("debug_enabled", prefs.playerDebugEnabled)
                    .put("double_back_to_exit", prefs.playerDoubleBackToExit)
                    .put("down_key_osd_focus_target", prefs.playerDownKeyOsdFocusTarget)
                    .put("persistent_bottom_progress_enabled", prefs.playerPersistentBottomProgressEnabled)
                    .put("playback_mode", prefs.playerPlaybackMode)
                    .put("osd_buttons", osdButtons),
            )
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

            SettingId.ThemePreset -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_DEFAULT to "默认",
                        blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_TV_PINK to "小电视粉",
                    )
                showChoiceDialog(
                    title = "主题",
                    items = options.map { it.second },
                    current = SettingsText.themePresetText(prefs.themePreset),
                ) { selected ->
                    val key =
                        options.firstOrNull { it.second == selected }?.first
                            ?: blbl.cat3399.core.prefs.AppPrefs.THEME_PRESET_DEFAULT

                    if (prefs.themePreset == key) {
                        AppToast.show(activity, "主题：$selected")
                        return@showChoiceDialog
                    }

                    prefs.themePreset = key
                    LauncherAliasManager.sync(activity.applicationContext, key)
                    AppToast.show(activity, "主题：$selected（已应用）")
                    restartToMainForThemePreset()
                }
            }

            SettingId.UserAgent -> showUserAgentDialog(state.currentSectionIndex, entry.id)
            SettingId.Ipv4OnlyEnabled -> {
                prefs.ipv4OnlyEnabled = !prefs.ipv4OnlyEnabled
                AppToast.show(activity, "是否只允许使用IPV4：${if (prefs.ipv4OnlyEnabled) "开" else "关"}")
                runCatching { BiliClient.apiOkHttp.connectionPool.evictAll() }
                runCatching { BiliClient.cdnOkHttp.connectionPool.evictAll() }
                runCatching { ApkUpdater.evictConnections() }
                renderer.refreshSection(entry.id)
            }
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

            SettingId.UploadLogs -> {
                showUploadLogsDialog()
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

            SettingId.MainBackFocusScheme -> {
                val options =
                    listOf(
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_A to "回到当前所属Tab",
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_B to "回到Tab0内容区",
                        blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_C to "回到侧边栏",
                    )
                showChoiceDialog(
                    title = "返回键焦点策略",
                    items = options.map { it.second },
                    current = SettingsText.mainBackFocusSchemeText(prefs.mainBackFocusScheme),
                ) { selected ->
                    val key = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.MAIN_BACK_FOCUS_SCHEME_A
                    prefs.mainBackFocusScheme = key
                    AppToast.show(activity, "返回键焦点策略：$selected")
                    renderer.refreshSection(entry.id)
                }
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

            SettingId.UiScaleFactor -> {
                val factors = (70..140 step 5).map { it / 100f }
                val items = factors.map { SettingsText.uiScaleFactorText(it) }
                showChoiceDialog(
                    title = "界面大小",
                    items = items,
                    current = SettingsText.uiScaleFactorText(prefs.uiScaleFactor),
                ) { selected ->
                    val factor = factors.getOrNull(items.indexOf(selected)) ?: prefs.uiScaleFactor
                    prefs.uiScaleFactor = factor
                    AppToast.show(activity, "界面大小：$selected")
                    // Accept "recreate to apply" to keep UI scale management centralized and reduce per-module sizing code.
                    activity.recreate()
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

            SettingId.SubtitleBottomPaddingFraction -> {
                val options = (0..30 step 2).toList()
                val items = options.map { "${it}%" }
                val checked =
                    options.indices.minByOrNull { kotlin.math.abs(options[it] / 100f - prefs.subtitleBottomPaddingFraction) }
                        ?: 0
                showChoiceDialog(
                    title = "字幕底部间距(占屏比%)",
                    items = items,
                    checkedIndex = checked,
                ) { selected ->
                    val percent = selected.removeSuffix("%").toIntOrNull() ?: options.getOrNull(checked) ?: 16
                    prefs.subtitleBottomPaddingFraction = percent / 100f
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.SubtitleBackgroundOpacity -> {
                val options = (20 downTo 0).map { it / 20f }.toMutableList()
                val defaultOpacity = 34f / 255f
                if (options.none { kotlin.math.abs(it - defaultOpacity) < 0.005f }) options.add(defaultOpacity)
                val ordered = options.distinct().sortedDescending()
                val items = ordered.map { String.format(Locale.US, "%.2f", it) }
                val checked = ordered.indices.minByOrNull { kotlin.math.abs(ordered[it] - prefs.subtitleBackgroundOpacity) } ?: 0
                showChoiceDialog(
                    title = "字幕背景透明度",
                    items = items,
                    checkedIndex = checked,
                ) { selected ->
                    val value = selected.toFloatOrNull() ?: ordered.getOrNull(checked) ?: prefs.subtitleBackgroundOpacity
                    prefs.subtitleBackgroundOpacity = value.coerceIn(0f, 1.0f)
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
                val options = (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = SettingsText.aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = (selected.toIntOrNull() ?: 3).coerceIn(1, 10)
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
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME to "固定时间拖动进度条",
                    )
                val labels = options.map { it.second }
                val checked = options.indexOfFirst { it.first == prefs.playerHoldSeekMode }.coerceAtLeast(0)
                AppPopup.singleChoice(
                    context = activity,
                    title = "长按快进模式",
                    items = labels,
                    checkedIndex = checked,
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
                val options = listOf("播放视频列表", "播放合集/分P视频", "播放推荐视频", "循环该视频", "什么都不做", "退出播放器")
                showChoiceDialog(
                    title = "播放模式（全局默认）",
                    items = options,
                    current = SettingsText.playbackModeText(prefs.playerPlaybackMode),
                ) { selected ->
                    prefs.playerPlaybackMode =
                        when (selected) {
                            "循环该视频" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE
                            "播放视频列表" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST
                            "播放合集/分P视频" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST
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

            SettingId.PlayerRenderView -> {
                val options = listOf("SurfaceView", "TextureView")
                showChoiceDialog(
                    title = "渲染视图",
                    items = options,
                    current = SettingsText.renderViewText(prefs.playerRenderViewType),
                ) { selected ->
                    prefs.playerRenderViewType =
                        when (selected) {
                            "TextureView" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_SURFACE_VIEW
                        }
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerEngineKind -> {
                val options = listOf("ExoPlayer", "IjkPlayer")
                showChoiceDialog(
                    title = "播放器内核",
                    items = options,
                    current = SettingsText.playerEngineText(prefs.playerEngineKind),
                ) { selected ->
                    val value =
                        when (selected) {
                            "IjkPlayer" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_IJK
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_EXO
                        }
                    if (value == blbl.cat3399.core.prefs.AppPrefs.PLAYER_ENGINE_IJK) {
                        IjkPlayerPluginUi.ensureInstalled(activity) {
                            prefs.playerEngineKind = value
                            AppToast.show(activity, "播放器内核：$selected（下次播放生效）")
                            renderer.refreshSection(entry.id)
                        }
                        return@showChoiceDialog
                    }

                    if (prefs.playerEngineKind == value) {
                        AppToast.show(activity, "播放器内核：$selected")
                        return@showChoiceDialog
                    }

                    prefs.playerEngineKind = value
                    AppToast.show(activity, "播放器内核：$selected（下次播放生效）")
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerAudioBalance -> {
                val options = AudioBalanceLevel.ordered
                val current = AudioBalanceLevel.fromPrefValue(prefs.playerAudioBalanceLevel)
                val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                showChoiceDialog(
                    title = "音频平衡",
                    items = options.map { it.label },
                    checkedIndex = checked,
                ) { selected ->
                    val picked = options.firstOrNull { it.label == selected } ?: AudioBalanceLevel.Off
                    prefs.playerAudioBalanceLevel = picked.prefValue
                    renderer.refreshSection(entry.id)
                }
            }

            SettingId.PlayerOsdButtons -> showPlayerOsdButtonsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.PlayerCustomShortcuts -> showPlayerCustomShortcutsDialog(sectionIndex = state.currentSectionIndex, focusId = entry.id)

            SettingId.LiveHighBitrateEnabled -> {
                prefs.liveHighBitrateEnabled = !prefs.liveHighBitrateEnabled
                AppToast.show(activity, "提高直播码率：${if (prefs.liveHighBitrateEnabled) "开" else "关"}")
                renderer.refreshSection(entry.id)
            }

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
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS to "评论",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL to "视频详情页",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP to "UP主",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE to "点赞",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN to "投币",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV to "收藏",
                        blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL to "列表面板",
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

            SettingId.PlayerVideoShotPreviewSize -> {
                val options =
                    listOf(
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF to "不显示",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL to "小",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM to "中",
                        AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE to "大",
                    )
                val checked = options.indexOfFirst { it.first == prefs.playerVideoShotPreviewSize }.coerceAtLeast(0)
                showChoiceDialog(
                    title = "缩略图显示",
                    items = options.map { it.second },
                    checkedIndex = checked,
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: AppPrefs.PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
                    prefs.playerVideoShotPreviewSize = value
                    renderer.refreshSection(entry.id)
                }
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

    private fun restartToMainForThemePreset() {
        val intent =
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
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
        AppPopup.singleChoice(
            context = activity,
            title = title,
            items = items,
            checkedIndex = checked,
        ) { _, label ->
            onPicked(label)
        }
    }

    private fun showPlayerOsdButtonsDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        val options =
            listOf(
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PREV to "上一个",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PLAY_PAUSE to "播放/暂停",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_NEXT to "下一个",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_SUBTITLE to "字幕",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DANMAKU to "弹幕",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COMMENTS to "评论",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DETAIL to "视频详情页",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_UP to "UP主",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIKE to "点赞",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COIN to "投币",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_FAV to "收藏",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIST_PANEL to "列表",
                blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_ADVANCED to "更多设置",
            )
        val keys = options.map { it.first }
        val labels = options.map { it.second }.toTypedArray()

        val selected = prefs.playerOsdButtons.toSet()
        val checked = BooleanArray(keys.size) { idx -> selected.contains(keys[idx]) }
        AppPopup.multiChoice(
            context = activity,
            title = "OSD按钮显示",
            items = labels.toList(),
            checked = checked,
            onChanged = { finalChecked ->
                prefs.playerOsdButtons =
                    keys.filterIndexed { idx, _ ->
                        idx in finalChecked.indices && finalChecked[idx]
                    }
            },
            onDismiss = {
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showPlayerCustomShortcutsDialog(sectionIndex: Int, focusId: SettingId) {
        fun keyLabel(keyCode: Int): String {
            val raw = runCatching { KeyEvent.keyCodeToString(keyCode) }.getOrNull()?.trim().orEmpty()
            if (raw.isBlank()) return keyCode.toString()
            val text = raw.removePrefix("KEYCODE_")
            return when {
                text.startsWith("NUMPAD_") && text.length == "NUMPAD_0".length -> "小键盘${text.last()}"
                text.length == 1 && text[0] in '0'..'9' -> text
                else -> text
            }
        }

        fun actionLabel(action: PlayerCustomShortcutAction): String {
            return when (action) {
                PlayerCustomShortcutAction.ToggleSubtitles -> "字幕：开/关"
                PlayerCustomShortcutAction.ToggleDanmaku -> "弹幕：开/关"
                PlayerCustomShortcutAction.ToggleDebugOverlay -> "调试信息：开/关"
                PlayerCustomShortcutAction.TogglePersistentBottomProgress -> "底部常驻进度条：开/关"
                is PlayerCustomShortcutAction.SetPlaybackSpeed -> "播放速度：${String.format(Locale.US, "%.2fx", action.speed)}"
                is PlayerCustomShortcutAction.SetResolutionQn -> "分辨率：${SettingsText.qnText(action.qn)}"
                is PlayerCustomShortcutAction.SetAudioId -> "音轨：${SettingsText.audioText(action.audioId)}"
                is PlayerCustomShortcutAction.SetCodec -> "视频编码：${action.codec}"
                is PlayerCustomShortcutAction.SetPlaybackMode -> "播放模式：${SettingsText.playbackModeText(action.mode)}"
                is PlayerCustomShortcutAction.SetSubtitleLang -> {
                    val lang = action.lang.trim()
                    if (lang.equals(PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT, ignoreCase = true)) {
                        "字幕语言：跟随全局"
                    } else {
                        "字幕语言：${SettingsText.subtitleLangText(lang)}"
                    }
                }
                is PlayerCustomShortcutAction.SetSubtitleTextSize -> "字幕大小：${action.textSizeSp.toInt()}"
                is PlayerCustomShortcutAction.SetDanmakuOpacity -> "弹幕透明度：${String.format(Locale.US, "%.2f", action.opacity)}"
                is PlayerCustomShortcutAction.SetDanmakuTextSize -> "弹幕大小：${action.textSizeSp.toInt()}"
                is PlayerCustomShortcutAction.SetDanmakuSpeed -> "弹幕速度：${action.speedLevel}"
                is PlayerCustomShortcutAction.SetDanmakuArea -> "弹幕区域：${SettingsText.areaText(action.area)}"
            }
        }

        fun bindingLabel(binding: PlayerCustomShortcut): String =
            "${keyLabel(binding.keyCode)} → ${actionLabel(binding.action)}"

        fun loadShortcuts(): List<PlayerCustomShortcut> = BiliClient.prefs.playerCustomShortcuts

        fun upsert(binding: PlayerCustomShortcut) {
            val prefs = BiliClient.prefs
            prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.upsert(prefs.playerCustomShortcuts, binding)
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        fun removeBinding(keyCode: Int) {
            val prefs = BiliClient.prefs
            prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.remove(prefs.playerCustomShortcuts, keyCode)
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        fun clearAll() {
            BiliClient.prefs.playerCustomShortcuts = PlayerCustomShortcutsStore.clear()
            renderer.refreshSection(SettingId.PlayerCustomShortcuts)
        }

        fun optionsTitle(actionType: String): String =
            when (actionType) {
                PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED -> "播放速度"
                PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN -> "分辨率"
                PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID -> "音轨"
                PlayerCustomShortcutAction.TYPE_SET_CODEC -> "视频编码"
                PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE -> "播放模式"
                PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG -> "字幕语言"
                PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE -> "字幕字体大小"
                PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY -> "弹幕透明度"
                PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE -> "弹幕字体大小"
                PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED -> "弹幕速度"
                PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA -> "弹幕区域"
                else -> actionType
            }

        data class ActionOption(
            val type: String,
            val label: String,
            val requiresValue: Boolean,
        )

        class ShortcutItemVh(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvLabel: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_label)
            private val tvCheck: TextView = itemView.findViewById(blbl.cat3399.R.id.tv_check)

            fun bind(label: String, onClick: () -> Unit) {
                tvLabel.text = label
                tvCheck.visibility = View.GONE
                itemView.setOnClickListener { onClick() }
                itemView.setOnKeyListener { _, keyCode, event ->
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        ->
                            if (event.action == KeyEvent.ACTION_UP) {
                                onClick()
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }
            }
        }

        class ShortcutListAdapter(
            private val list: List<PlayerCustomShortcut>,
            private val onItemClick: (PlayerCustomShortcut) -> Unit,
        ) : RecyclerView.Adapter<ShortcutItemVh>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutItemVh {
                val view = LayoutInflater.from(parent.context).inflate(blbl.cat3399.R.layout.item_popup_choice, parent, false)
                return ShortcutItemVh(view)
            }

            override fun onBindViewHolder(holder: ShortcutItemVh, position: Int) {
                val item = list.getOrNull(position) ?: return
                holder.bind(
                    label = bindingLabel(item),
                    onClick = { onItemClick(item) },
                )
            }

            override fun getItemCount(): Int = list.size
        }

        class Controller {
            fun showManager(focusKeyCode: Int? = null) {
                var replacing = false
                val items = loadShortcuts()

                AppPopup.custom(
                    context = activity,
                    title = "自定义播放快捷键",
                    cancelable = true,
                    actions =
                        listOf(
                            PopupAction(
                                role = PopupActionRole.NEUTRAL,
                                text = "清空",
                                dismissOnClick = false,
                            ) {
                                if (items.isEmpty()) {
                                    AppToast.show(activity, "暂无快捷键")
                                    return@PopupAction
                                }
                                replacing = true
                                showClearConfirm(focusKeyCode = focusKeyCode)
                            },
                            PopupAction(
                                role = PopupActionRole.NEUTRAL,
                                text = "删除",
                                dismissOnClick = false,
                            ) {
                                if (items.isEmpty()) {
                                    AppToast.show(activity, "暂无快捷键")
                                    return@PopupAction
                                }
                                replacing = true
                                showDeletePicker(focusKeyCode = focusKeyCode)
                            },
                            PopupAction(
                                role = PopupActionRole.NEGATIVE,
                                text = "关闭",
                            ),
                            PopupAction(
                                role = PopupActionRole.POSITIVE,
                                text = "新增",
                                dismissOnClick = false,
                            ) {
                                replacing = true
                                showKeyCapture()
                            },
                        ),
                    preferredActionRole = PopupActionRole.POSITIVE,
                    autoFocus = true,
                    onDismiss = {
                        if (!replacing) renderer.showSection(sectionIndex, focusId = focusId)
                    },
                ) { dialogContext ->
                    val recycler =
                        (LayoutInflater.from(dialogContext).inflate(blbl.cat3399.R.layout.view_popup_choice_list, null, false) as RecyclerView).apply {
                            layoutManager = LinearLayoutManager(dialogContext)
                            itemAnimator = null
                        }

                    recycler.adapter =
                        ShortcutListAdapter(items) { picked ->
                            replacing = true
                            showActionPicker(keyCode = picked.keyCode, currentAction = picked.action)
                        }

                    if (items.isNotEmpty()) {
                        val focusIndex =
                            focusKeyCode?.let { key ->
                                items.indexOfFirst { it.keyCode == key }.takeIf { it >= 0 }
                            } ?: 0
                        recycler.scrollToPosition(focusIndex)
                        recycler.post {
                            val holder = recycler.findViewHolderForAdapterPosition(focusIndex)
                            (holder?.itemView ?: recycler.getChildAt(0))?.requestFocus()
                        }
                    }

                    recycler
                }
            }

            private fun showKeyCapture() {
                var forward = false
                var captureView: TextView? = null
                AppPopup.custom(
                    context = activity,
                    title = "请按下要绑定的按键",
                    cancelable = true,
                    actions = emptyList(),
                    preferredActionRole = null,
                    autoFocus = false,
                    onModalAttached = {
                        captureView?.post { captureView?.requestFocus() }
                    },
                    onDismiss = {
                        if (!forward) showManager()
                    },
                ) { dialogContext ->
                    val tv =
                        LayoutInflater.from(dialogContext)
                            .inflate(blbl.cat3399.R.layout.view_player_custom_shortcut_key_capture, null, false) as TextView
                    captureView = tv
                    tv.text = "请按下要绑定的按键\n（返回键取消）"
                    tv.setOnKeyListener { _, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (event.repeatCount > 0) return@setOnKeyListener true

                        // Let the popup host handle these as "cancel/back".
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                            return@setOnKeyListener false
                        }

                        if (keyCode == KeyEvent.KEYCODE_UNKNOWN || keyCode <= 0) return@setOnKeyListener true
                        if (PlayerCustomShortcutsStore.isForbiddenKeyCode(keyCode)) {
                            val msg =
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    -> "确认键不允许绑定，请换用其他按键"
                                    else -> "该按键不允许绑定"
                                }
                            AppToast.show(activity, msg)
                            return@setOnKeyListener true
                        }

                        val existing = loadShortcuts().firstOrNull { it.keyCode == keyCode }?.action
                        forward = true
                        showActionPicker(keyCode = keyCode, currentAction = existing)
                        true
                    }
                    tv
                }
            }

            private fun showActionPicker(keyCode: Int, currentAction: PlayerCustomShortcutAction?) {
                var forward = false
                val options =
                    listOf(
                        ActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_SUBTITLES, "字幕 开/关", requiresValue = false),
                        ActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_DANMAKU, "弹幕 开/关", requiresValue = false),
                        ActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_DEBUG_OVERLAY, "调试信息 开/关", requiresValue = false),
                        ActionOption(PlayerCustomShortcutAction.TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS, "底部常驻进度条 开/关", requiresValue = false),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED, "播放速度", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN, "分辨率", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID, "音轨", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_CODEC, "视频编码", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE, "播放模式", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG, "字幕语言", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE, "字幕字体大小", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY, "弹幕透明度", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE, "弹幕字体大小", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED, "弹幕速度", requiresValue = true),
                        ActionOption(PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA, "弹幕区域", requiresValue = true),
                    )

                val checked =
                    options.indexOfFirst { it.type == currentAction?.type }
                        .takeIf { it >= 0 } ?: 0

                AppPopup.singleChoice(
                    context = activity,
                    title = "选择动作（${keyLabel(keyCode)}）",
                    items = options.map { it.label },
                    checkedIndex = checked,
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = keyCode)
                    },
                ) { which, _ ->
                    val picked = options.getOrNull(which) ?: return@singleChoice
                    if (picked.requiresValue) {
                        forward = true
                        showValuePicker(keyCode = keyCode, actionType = picked.type, currentAction = currentAction)
                        return@singleChoice
                    }

                    val action =
                        when (picked.type) {
                            PlayerCustomShortcutAction.TYPE_TOGGLE_SUBTITLES -> PlayerCustomShortcutAction.ToggleSubtitles
                            PlayerCustomShortcutAction.TYPE_TOGGLE_DANMAKU -> PlayerCustomShortcutAction.ToggleDanmaku
                            PlayerCustomShortcutAction.TYPE_TOGGLE_DEBUG_OVERLAY -> PlayerCustomShortcutAction.ToggleDebugOverlay
                            PlayerCustomShortcutAction.TYPE_TOGGLE_PERSISTENT_BOTTOM_PROGRESS -> PlayerCustomShortcutAction.TogglePersistentBottomProgress
                            else -> null
                        } ?: return@singleChoice

                    forward = true
                    upsert(PlayerCustomShortcut(keyCode = keyCode, action = action))
                    showManager(focusKeyCode = keyCode)
                }
            }

            private fun showValuePicker(keyCode: Int, actionType: String, currentAction: PlayerCustomShortcutAction?) {
                var forward = false
                val title = "${keyLabel(keyCode)} → ${optionsTitle(actionType)}"

                fun cancelBackToActionPicker() {
                    if (!forward) showActionPicker(keyCode = keyCode, currentAction = currentAction)
                }

                when (actionType) {
                    PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_SPEED -> {
                        val options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f)
                        val items = options.map { String.format(Locale.US, "%.2fx", it) }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetPlaybackSpeed)?.speed
                        val checked = options.indices.minByOrNull { idx -> kotlin.math.abs(options[idx] - (current ?: 1.0f)) } ?: 2
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetPlaybackSpeed(speed = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_RESOLUTION_QN -> {
                        val options = listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129)
                        val items = options.map { SettingsText.qnText(it) }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetResolutionQn)?.qn
                        val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetResolutionQn(qn = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_AUDIO_ID -> {
                        val options = listOf(30251, 30250, 30280, 30232, 30216)
                        val items = options.map { SettingsText.audioText(it) }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetAudioId)?.audioId
                        val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetAudioId(audioId = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_CODEC -> {
                        val options = listOf("AVC", "HEVC", "AV1")
                        val items = options.toList()
                        val current = (currentAction as? PlayerCustomShortcutAction.SetCodec)?.codec
                        val checked = options.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetCodec(codec = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_PLAYBACK_MODE -> {
                        val options =
                            listOf(
                                AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND),
                                AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST),
                                AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST),
                                AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE),
                                AppPrefs.PLAYER_PLAYBACK_MODE_NONE to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_NONE),
                                AppPrefs.PLAYER_PLAYBACK_MODE_EXIT to SettingsText.playbackModeText(AppPrefs.PLAYER_PLAYBACK_MODE_EXIT),
                            )
                        val items = options.map { it.second }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetPlaybackMode)?.mode
                        val checked = options.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val mode = options.getOrNull(which)?.first ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetPlaybackMode(mode = mode)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_LANG -> {
                        val options =
                            listOf(
                                PlayerCustomShortcutAction.SUBTITLE_LANG_DEFAULT to "跟随全局",
                                "auto" to SettingsText.subtitleLangText("auto"),
                                "zh-Hans" to SettingsText.subtitleLangText("zh-Hans"),
                                "zh-Hant" to SettingsText.subtitleLangText("zh-Hant"),
                                "en" to SettingsText.subtitleLangText("en"),
                                "ja" to SettingsText.subtitleLangText("ja"),
                                "ko" to SettingsText.subtitleLangText("ko"),
                            )
                        val items = options.map { it.second }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetSubtitleLang)?.lang
                        val checked = options.indexOfFirst { it.first == current }.takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val lang = options.getOrNull(which)?.first ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetSubtitleLang(lang = lang)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_SUBTITLE_TEXT_SIZE -> {
                        val options = (10..60 step 2).toList()
                        val items = options.map { it.toString() }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetSubtitleTextSize)?.textSizeSp
                        val checked =
                            options.indices.minByOrNull { idx -> kotlin.math.abs(options[idx].toFloat() - (current ?: 26f)) }
                                ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val sp = (options.getOrNull(which) ?: return@singleChoice).toFloat()
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetSubtitleTextSize(textSizeSp = sp)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_DANMAKU_OPACITY -> {
                        val options = (20 downTo 1).map { it / 20f }
                        val items = options.map { String.format(Locale.US, "%.2f", it) }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuOpacity)?.opacity
                        val checked =
                            options.indices.minByOrNull { idx -> kotlin.math.abs(options[idx] - (current ?: 1f)) }
                                ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetDanmakuOpacity(opacity = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_DANMAKU_TEXT_SIZE -> {
                        val options = (10..60 step 2).toList()
                        val items = options.map { it.toString() }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuTextSize)?.textSizeSp
                        val checked =
                            options.indices.minByOrNull { idx -> kotlin.math.abs(options[idx].toFloat() - (current ?: 18f)) }
                                ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val sp = (options.getOrNull(which) ?: return@singleChoice).toFloat()
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetDanmakuTextSize(textSizeSp = sp)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_DANMAKU_SPEED -> {
                        val options = (1..10).toList()
                        val items = options.map { it.toString() }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuSpeed)?.speedLevel
                        val checked = options.indexOf(current).takeIf { it >= 0 } ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetDanmakuSpeed(speedLevel = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }

                    PlayerCustomShortcutAction.TYPE_SET_DANMAKU_AREA -> {
                        val options = listOf(1.0f, 0.8f, 0.75f, 2f / 3f, 0.6f, 0.5f, 0.4f, 1f / 3f, 0.25f, 0.2f)
                        val items = options.map { SettingsText.areaText(it) }
                        val current = (currentAction as? PlayerCustomShortcutAction.SetDanmakuArea)?.area
                        val checked =
                            options.indices.minByOrNull { idx -> kotlin.math.abs(options[idx] - (current ?: 1f)) }
                                ?: 0
                        AppPopup.singleChoice(
                            context = activity,
                            title = title,
                            items = items,
                            checkedIndex = checked,
                            onDismiss = { cancelBackToActionPicker() },
                        ) { which, _ ->
                            val v = options.getOrNull(which) ?: return@singleChoice
                            forward = true
                            upsert(PlayerCustomShortcut(keyCode = keyCode, action = PlayerCustomShortcutAction.SetDanmakuArea(area = v)))
                            showManager(focusKeyCode = keyCode)
                        }
                        return
                    }
                }

                AppToast.show(activity, "未知动作：$actionType")
                showActionPicker(keyCode = keyCode, currentAction = currentAction)
            }

            private fun showDeletePicker(focusKeyCode: Int?) {
                var forward = false
                val items = loadShortcuts()
                val labels = items.map { bindingLabel(it) }
                val checked = focusKeyCode?.let { k -> items.indexOfFirst { it.keyCode == k }.takeIf { it >= 0 } } ?: 0
                AppPopup.singleChoice(
                    context = activity,
                    title = "删除快捷键",
                    items = labels.ifEmpty { listOf("暂无快捷键") },
                    checkedIndex = checked,
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = focusKeyCode)
                    },
                ) { which, _ ->
                    val picked = items.getOrNull(which) ?: return@singleChoice
                    forward = true
                    removeBinding(picked.keyCode)
                    showManager()
                }
            }

            private fun showClearConfirm(focusKeyCode: Int?) {
                var forward = false
                AppPopup.confirm(
                    context = activity,
                    title = "清空快捷键",
                    message = "确定清空所有自定义播放快捷键？",
                    positiveText = "清空",
                    negativeText = "取消",
                    cancelable = true,
                    onPositive = {
                        forward = true
                        clearAll()
                        showManager()
                    },
                    onNegative = {
                        forward = true
                        showManager(focusKeyCode = focusKeyCode)
                    },
                    onDismiss = {
                        if (!forward) showManager(focusKeyCode = focusKeyCode)
                    },
                )
            }
        }

        Controller().showManager()
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        AppPopup.input(
            context = activity,
            title = "User-Agent",
            initial = prefs.userAgent,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL,
            minLines = 3,
            positiveText = "保存",
            negativeText = "取消",
            neutralText = "重置默认",
            validate = { text ->
                val ua = text.trim()
                if (ua.isBlank()) {
                    AppToast.show(activity, "User-Agent 不能为空")
                    false
                } else {
                    true
                }
            },
            onPositive = { text ->
                val ua = text.trim()
                prefs.userAgent = ua
                AppToast.show(activity, "已更新 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
            onNeutral = {
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                AppToast.show(activity, "已重置 User-Agent")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusId: SettingId) {
        AppPopup.confirm(
            context = activity,
            title = "清除登录",
            message = "将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？",
            positiveText = "确定清除",
            negativeText = "取消",
            cancelable = true,
            onPositive = {
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                BiliClient.prefs.gaiaVgateVVoucher = null
                BiliClient.prefs.gaiaVgateVVoucherSavedAtMs = -1L
                AppToast.show(activity, "已清除 Cookie")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
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

        AppPopup.confirm(
            context = activity,
            title = "清理缓存",
            message = "确定清理缓存？",
            positiveText = "清理",
            negativeText = "取消",
            cancelable = true,
            onPositive = { startClearCache(sectionIndex, focusId) },
        )
    }

    private fun startClearCache(sectionIndex: Int, focusId: SettingId) {
        cacheSizeJob?.cancel()
        val popup =
            AppPopup.progress(
                context = activity,
                title = "清理中",
                status = "清理中…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { clearCacheJob?.cancel() },
            )

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

                    popup?.dismiss()
                    AppToast.show(activity, "已清理缓存")
                    state.cacheSizeBytes = 0L
                    renderer.showSection(sectionIndex, focusId = focusId)
                    updateCacheSize(force = true)
                } catch (_: CancellationException) {
                    popup?.dismiss()
                    AppToast.show(activity, "已取消")
                } catch (t: Throwable) {
                    AppLog.w("Settings", "clear cache failed: ${t.message}", t)
                    popup?.dismiss()
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
                    val latest = ApkUpdater.fetchLatestVersionName()
                    val current = BuildConfig.VERSION_NAME
                    state.testUpdateCheckState =
                        if (ApkUpdater.isRemoteNewer(latest, current)) {
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

        val now = System.currentTimeMillis()
        val cooldownLeftMs = ApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            AppToast.show(activity, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）")
            return
        }

        val popup =
            AppPopup.progress(
                context = activity,
                title = "下载更新",
                status = "检查更新…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { testUpdateJob?.cancel() },
            )

        testUpdateJob =
            activity.lifecycleScope.launch {
                try {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val latestVersion = latestVersionHint ?: ApkUpdater.fetchLatestVersionName()
                    if (!ApkUpdater.isRemoteNewer(latestVersion, currentVersion)) {
                        state.testUpdateCheckState = TestUpdateCheckState.Latest(latestVersion)
                        state.testUpdateCheckedAtMs = System.currentTimeMillis()
                        renderer.refreshAboutSectionKeepPosition()
                        popup?.dismiss()
                        AppToast.show(activity, "已是最新版（当前：$currentVersion）")
                        return@launch
                    }

                    state.testUpdateCheckState = TestUpdateCheckState.UpdateAvailable(latestVersion)
                    state.testUpdateCheckedAtMs = System.currentTimeMillis()
                    renderer.refreshAboutSectionKeepPosition()

                    popup?.updateStatus("准备下载…（最新：$latestVersion）")
                    popup?.updateProgress(null)

                    ApkUpdater.markStarted(now)
                    val apkFile =
                        ApkUpdater.downloadApkToCache(
                            context = activity,
                            url = ApkUpdater.TEST_APK_URL,
                        ) { dlState ->
                            when (dlState) {
                                ApkUpdater.Progress.Connecting -> {
                                    popup?.updateProgress(null)
                                    popup?.updateStatus("连接中…")
                                }

                                is ApkUpdater.Progress.Downloading -> {
                                    val pct = dlState.percent
                                    if (pct != null) {
                                        popup?.updateProgress(pct.coerceIn(0, 100))
                                        popup?.updateStatus("下载中… ${pct.coerceIn(0, 100)}% ${dlState.hint}")
                                    } else {
                                        popup?.updateProgress(null)
                                        popup?.updateStatus("下载中… ${dlState.hint}")
                                    }
                                }
                            }
                        }

                    popup?.updateStatus("准备安装…")
                    popup?.updateProgress(null)
                    popup?.dismiss()
                    ApkUpdater.installApk(activity, apkFile)
                } catch (_: CancellationException) {
                    popup?.dismiss()
                    AppToast.show(activity, "已取消更新")
                } catch (t: Throwable) {
                    AppLog.w("TestUpdate", "update failed: ${t.message}")
                    popup?.dismiss()
                    AppToast.showLong(activity, "更新失败：${t.message ?: "未知错误"}")
                }
            }
    }

    private fun showProjectDialog() {
        AppPopup.custom(
            context = activity,
            title = "项目地址",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "复制") {
                        copyToClipboard(label = "项目地址", text = SettingsConstants.PROJECT_URL, toastText = "已复制项目地址")
                    },
                    PopupAction(role = PopupActionRole.POSITIVE, text = "打开") { openUrl(SettingsConstants.PROJECT_URL) },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = SettingsConstants.PROJECT_URL
                tv
            },
        )
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

        AppPopup.custom(
            context = activity,
            title = "风控验证",
            cancelable = true,
            actions =
                listOf(
                    PopupAction(role = PopupActionRole.NEGATIVE, text = "关闭"),
                    PopupAction(role = PopupActionRole.NEUTRAL, text = "编辑 v_voucher") {
                        showGaiaVgateVoucherDialog(sectionIndex, focusId)
                    },
                    PopupAction(role = PopupActionRole.POSITIVE, text = if (hasVoucher) "开始验证" else "粘贴 v_voucher") {
                        if (hasVoucher) {
                            gaiaVgateLauncher.launch(
                                Intent(activity, GaiaVgateActivity::class.java)
                                    .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher),
                            )
                        } else {
                            showGaiaVgateVoucherDialog(sectionIndex, focusId)
                        }
                    },
                ),
            preferredActionRole = PopupActionRole.POSITIVE,
            content = { dialogContext ->
                val tv =
                    android.view.LayoutInflater.from(dialogContext)
                        .inflate(blbl.cat3399.R.layout.view_popup_message, null, false) as TextView
                tv.text = msg
                tv
            },
        )
    }

    private fun showGaiaVgateVoucherDialog(sectionIndex: Int, focusId: SettingId) {
        val prefs = BiliClient.prefs
        AppPopup.input(
            context = activity,
            title = "编辑 v_voucher",
            initial = prefs.gaiaVgateVVoucher.orEmpty(),
            hint = "粘贴 v_voucher",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            minLines = 1,
            positiveText = "保存",
            negativeText = "取消",
            neutralText = "清除",
            onPositive = { text ->
                val v = text.trim()
                prefs.gaiaVgateVVoucher = v.takeIf { it.isNotBlank() }
                prefs.gaiaVgateVVoucherSavedAtMs = if (v.isNotBlank()) System.currentTimeMillis() else -1L
                AppToast.show(activity, if (v.isNotBlank()) "已保存 v_voucher" else "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
            onNeutral = {
                prefs.gaiaVgateVVoucher = null
                prefs.gaiaVgateVVoucherSavedAtMs = -1L
                AppToast.show(activity, "已清除 v_voucher")
                renderer.showSection(sectionIndex, focusId = focusId)
            },
        )
    }
}
