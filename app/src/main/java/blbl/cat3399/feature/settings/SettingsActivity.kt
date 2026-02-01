package blbl.cat3399.feature.settings

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.update.TestApkUpdater
import blbl.cat3399.databinding.ActivitySettingsBinding
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
import kotlin.math.roundToInt

class SettingsActivity : BaseActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var currentSectionIndex: Int = -1
    private lateinit var leftAdapter: SettingsLeftAdapter
    private lateinit var rightAdapter: SettingsEntryAdapter
    private var testUpdateJob: Job? = null
    private var testUpdateCheckJob: Job? = null
    private var testUpdateCheckState: TestUpdateCheckState = TestUpdateCheckState.Idle
    private var testUpdateCheckedAtMs: Long = -1L
    private var clearCacheJob: Job? = null
    private var cacheSizeJob: Job? = null
    private var cacheSizeBytes: Long? = null
    private var focusListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private var lastFocusedLeftIndex: Int = 0
    private var lastFocusedRightTitle: String? = null
    private var pendingRestoreRightTitle: String? = null
    private var pendingRestoreLeftIndex: Int? = null
    private var pendingRestoreBack: Boolean = false
    private var focusRequestToken: Int = 0

    private sealed interface TestUpdateCheckState {
        data object Idle : TestUpdateCheckState

        data object Checking : TestUpdateCheckState

        data class Latest(val latestVersion: String) : TestUpdateCheckState

        data class UpdateAvailable(val latestVersion: String) : TestUpdateCheckState

        data class Error(val message: String) : TestUpdateCheckState
    }

    private val gaiaVgateLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val token = result.data?.getStringExtra(GaiaVgateActivity.EXTRA_GAIA_VTOKEN)?.trim()?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            upsertGaiaVtokenCookie(token)
            val prefs = BiliClient.prefs
            prefs.gaiaVgateVVoucher = null
            prefs.gaiaVgateVVoucherSavedAtMs = -1L
            Toast.makeText(this, "验证成功，已写入风控票据", Toast.LENGTH_SHORT).show()
            refreshSection("风控验证")
        }

    private val sections = listOf(
        "通用设置",
        "页面设置",
        "播放设置",
        "弹幕设置",
        "关于应用",
        "设备信息",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnBack.setOnClickListener { finish() }

        leftAdapter = SettingsLeftAdapter { index -> showSection(index, keepScroll = false) }
        binding.recyclerLeft.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeft.adapter = leftAdapter
        leftAdapter.submit(sections, selected = 0)

        binding.recyclerRight.layoutManager = LinearLayoutManager(this)
        binding.recyclerRight.itemAnimator = null
        rightAdapter = SettingsEntryAdapter { entry -> onEntryClicked(entry) }
        binding.recyclerRight.adapter = rightAdapter
        showSection(0)

        focusListener =
            android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
                if (newFocus == null) return@OnGlobalFocusChangeListener
                when {
                    newFocus == binding.btnBack -> {
                        pendingRestoreBack = false
                    }

                    isDescendantOf(newFocus, binding.recyclerLeft) -> {
                        val holder = binding.recyclerLeft.findContainingViewHolder(newFocus) ?: return@OnGlobalFocusChangeListener
                        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@OnGlobalFocusChangeListener
                        lastFocusedLeftIndex = pos
                        if (pendingRestoreLeftIndex == pos) pendingRestoreLeftIndex = null
                    }

                    isDescendantOf(newFocus, binding.recyclerRight) -> {
                        val itemView = binding.recyclerRight.findContainingItemView(newFocus) ?: newFocus
                        val title = (itemView.tag as? String)?.takeIf { it.isNotBlank() }
                        if (title != null) lastFocusedRightTitle = title
                        if (pendingRestoreRightTitle == title) pendingRestoreRightTitle = null
                    }
                }
            }.also { binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(it) }
    }

    override fun onResume() {
        super.onResume()
        applyUiMode()
        ensureInitialFocus()
    }

    override fun onDestroy() {
        focusListener?.let { binding.root.viewTreeObserver.removeOnGlobalFocusChangeListener(it) }
        focusListener = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) restorePendingFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyUiMode() {
        val uiScale = UiScale.factor(this, BiliClient.prefs.sidebarSize)
        val widthPx = (dp(360f) * uiScale).roundToInt().coerceAtLeast(1)
        val lp = binding.recyclerLeft.layoutParams
        if (lp.width != widthPx) {
            lp.width = widthPx
            binding.recyclerLeft.layoutParams = lp
        }

        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)

        val backSizePx =
            scaledPx(
                blbl.cat3399.R.dimen.sidebar_settings_size_tv,
            ).coerceAtLeast(1)
        val backPadPx =
            scaledPx(
                blbl.cat3399.R.dimen.sidebar_settings_padding_tv,
            )

        val backLp = binding.btnBack.layoutParams
        if (backLp.width != backSizePx || backLp.height != backSizePx) {
            backLp.width = backSizePx
            backLp.height = backSizePx
            binding.btnBack.layoutParams = backLp
        }
        if (
            binding.btnBack.paddingLeft != backPadPx ||
            binding.btnBack.paddingTop != backPadPx ||
            binding.btnBack.paddingRight != backPadPx ||
            binding.btnBack.paddingBottom != backPadPx
        ) {
            binding.btnBack.setPadding(backPadPx, backPadPx, backPadPx, backPadPx)
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (restorePendingFocus()) return
        focusLeftAt(lastFocusedLeftIndex.coerceAtLeast(0))
    }

    private fun dp(valueDp: Float): Int {
        val dm = resources.displayMetrics
        return (valueDp * dm.density).toInt()
    }

    private fun isNavKey(keyCode: Int): Boolean {
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

    private fun showSection(index: Int, keepScroll: Boolean = index == currentSectionIndex, focusTitle: String? = null) {
        val lm = binding.recyclerRight.layoutManager as? LinearLayoutManager
        val firstVisible = if (keepScroll) lm?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION else RecyclerView.NO_POSITION
        val topOffset =
            if (keepScroll && firstVisible != RecyclerView.NO_POSITION) {
                (binding.recyclerRight.getChildAt(0)?.top ?: 0) - binding.recyclerRight.paddingTop
            } else {
                0
            }
        currentSectionIndex = index

        val prefs = BiliClient.prefs
        val entries = when (sections.getOrNull(index)) {
            "通用设置" -> listOf(
                SettingEntry("图片质量", prefs.imageQuality, null),
                SettingEntry("User-Agent", prefs.userAgent.take(60), null),
                SettingEntry("风控验证", gaiaVgateStatusText(), "播放被拦截后可在此手动完成人机验证"),
                SettingEntry("清理缓存", cacheSizeText(), null),
                SettingEntry("清除登录", if (BiliClient.cookies.hasSessData()) "已登录" else "未登录", null),
            )

            "页面设置" -> listOf(
                SettingEntry("每行卡片数量", gridSpanText(prefs.gridSpanCount), null),
                SettingEntry("动态页每行卡片数量", gridSpanText(prefs.dynamicGridSpanCount), null),
                SettingEntry("番剧/电视剧每行卡片数量", gridSpanText(prefs.pgcGridSpanCount), null),
                SettingEntry("界面大小", sidebarSizeText(prefs.sidebarSize), null),
                SettingEntry("以全屏模式运行", if (prefs.fullscreenEnabled) "开" else "关", null),
            )

            "播放设置" -> listOf(
                SettingEntry("默认画质", qnText(prefs.playerPreferredQn), null),
                SettingEntry("默认音轨", audioText(prefs.playerPreferredAudioId), null),
                SettingEntry("CDN线路", cdnText(prefs.playerCdnPreference), null),
                SettingEntry("默认播放速度", String.format(Locale.US, "%.2fx", prefs.playerSpeed), null),
                SettingEntry("自动跳到上次播放位置", if (prefs.playerAutoResumeEnabled) "开" else "关", null),
                SettingEntry("自动跳过片段（空降助手）", if (prefs.playerAutoSkipSegmentsEnabled) "开" else "关", null),
                SettingEntry("播放前打开详情页", if (prefs.playerOpenDetailBeforePlay) "开" else "关", "点击视频卡片后先进入详情页"),
                SettingEntry("播放模式", playbackModeText(prefs.playerPlaybackMode), null),
                SettingEntry("字幕语言", subtitleLangText(prefs.subtitlePreferredLang), null),
                SettingEntry("默认开启字幕", if (prefs.subtitleEnabledDefault) "开" else "关", null),
                SettingEntry("视频编码", prefs.playerPreferredCodec, null),
                SettingEntry("控制栏按钮", playerActionButtonsText(prefs.playerActionButtons), null),
                SettingEntry("显示视频调试信息", if (prefs.playerDebugEnabled) "开" else "关", null),
                SettingEntry("按两次退出键才退出播放器", if (prefs.playerDoubleBackToExit) "开" else "关", null),
                SettingEntry("底部常驻进度条", if (prefs.playerPersistentBottomProgressEnabled) "开" else "关", null),
            )

            "弹幕设置" -> listOf(
                SettingEntry("弹幕开关", if (prefs.danmakuEnabled) "开" else "关", null),
                SettingEntry("弹幕透明度", String.format("%.2f", prefs.danmakuOpacity), null),
                SettingEntry("弹幕字体大小", prefs.danmakuTextSizeSp.toInt().toString(), null),
                SettingEntry("弹幕占屏比", areaText(prefs.danmakuArea), null),
                SettingEntry("弹幕速度", prefs.danmakuSpeed.toString(), null),
                SettingEntry("跟随B站弹幕屏蔽", if (prefs.danmakuFollowBiliShield) "开" else "关", null),
                SettingEntry("智能云屏蔽", if (prefs.danmakuAiShieldEnabled) "开" else "关", null),
                SettingEntry("智能云屏蔽等级", aiLevelText(prefs.danmakuAiShieldLevel), null),
                SettingEntry("允许滚动弹幕", if (prefs.danmakuAllowScroll) "开" else "关", null),
                SettingEntry("允许顶部悬停弹幕", if (prefs.danmakuAllowTop) "开" else "关", null),
                SettingEntry("允许底部悬停弹幕", if (prefs.danmakuAllowBottom) "开" else "关", null),
                SettingEntry("允许彩色弹幕", if (prefs.danmakuAllowColor) "开" else "关", null),
                SettingEntry("允许特殊弹幕", if (prefs.danmakuAllowSpecial) "开" else "关", null),
            )

            "关于应用" -> {
                ensureTestUpdateChecked(force = false, refreshUi = false)
                listOf(
                    SettingEntry("版本", BuildConfig.VERSION_NAME, null),
                    SettingEntry("项目地址", PROJECT_URL, null),
                    SettingEntry("QQ交流群", QQ_GROUP, null),
                    SettingEntry("日志标签", "BLBL", "用于 Logcat 过滤"),
                    aboutUpdateEntry(),
                )
            }

            "设备信息" -> listOf(
                SettingEntry("CPU", Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), null),
                SettingEntry("设备", "${Build.MANUFACTURER} ${Build.MODEL}", null),
                SettingEntry("系统", "Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}", null),
                SettingEntry("屏幕", screenText(), null),
                SettingEntry("RAM", ramText(), null),
            )

            else -> emptyList()
        }
        rightAdapter.submit(entries)
        if (sections.getOrNull(index) == "通用设置") updateCacheSize(force = false)
        pendingRestoreRightTitle = focusTitle
        val token = ++focusRequestToken
        binding.recyclerRight.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            if (keepScroll && lm != null && firstVisible != RecyclerView.NO_POSITION) {
                lm.scrollToPositionWithOffset(firstVisible, topOffset)
            }
            restorePendingFocus()
        }
    }

    private fun refreshSection(focusTitle: String? = null) {
        showSection(currentSectionIndex, focusTitle = focusTitle)
    }

    private fun onEntryClicked(entry: SettingEntry) {
        val prefs = BiliClient.prefs
        pendingRestoreRightTitle = entry.title
        when (entry.title) {
            "图片质量" -> {
                val next = when (prefs.imageQuality) {
                    "small" -> "medium"
                    "medium" -> "large"
                    else -> "small"
                }
                prefs.imageQuality = next
                Toast.makeText(this, "图片质量：$next", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "User-Agent" -> showUserAgentDialog(currentSectionIndex, entry.title)
            "风控验证" -> showGaiaVgateDialog(currentSectionIndex, entry.title)
            "清理缓存" -> showClearCacheDialog(currentSectionIndex, entry.title)
            "清除登录" -> showClearLoginDialog(currentSectionIndex, entry.title)

            "以全屏模式运行" -> {
                prefs.fullscreenEnabled = !prefs.fullscreenEnabled
                Immersive.apply(this, prefs.fullscreenEnabled)
                Toast.makeText(this, "全屏：${if (prefs.fullscreenEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "界面大小" -> {
                val options = listOf("小", "中", "大")
                showChoiceDialog(
                    title = "界面大小",
                    items = options,
                    current = sidebarSizeText(prefs.sidebarSize),
                ) { selected ->
                    prefs.sidebarSize =
                        when (selected) {
                            "小" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL
                            "大" -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE
                            else -> blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_MEDIUM
                        }
                    Toast.makeText(this, "界面大小：$selected", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "每行卡片数量" -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.gridSpanCount),
                ) { selected ->
                    prefs.gridSpanCount = (selected.toIntOrNull() ?: 4).coerceIn(1, 6)
                    Toast.makeText(this, "每行卡片：${gridSpanText(prefs.gridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "动态页每行卡片数量" -> {
                val options = listOf("1", "2", "3", "4", "5", "6")
                showChoiceDialog(
                    title = "动态页每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.dynamicGridSpanCount),
                ) { selected ->
                    prefs.dynamicGridSpanCount = (selected.toIntOrNull() ?: 3).coerceIn(1, 6)
                    Toast.makeText(this, "动态每行：${gridSpanText(prefs.dynamicGridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "番剧/电视剧每行卡片数量" -> {
                val options = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
                showChoiceDialog(
                    title = "番剧/电视剧每行卡片数量",
                    items = options,
                    current = gridSpanText(prefs.pgcGridSpanCount),
                ) { selected ->
                    prefs.pgcGridSpanCount = (selected.toIntOrNull() ?: 6).coerceIn(1, 6)
                    Toast.makeText(this, "番剧每行：${gridSpanText(prefs.pgcGridSpanCount)}", Toast.LENGTH_SHORT).show()
                    refreshSection(entry.title)
                }
            }

            "弹幕开关" -> {
                prefs.danmakuEnabled = !prefs.danmakuEnabled
                Toast.makeText(this, "弹幕：${if (prefs.danmakuEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "默认开启字幕" -> {
                prefs.subtitleEnabledDefault = !prefs.subtitleEnabledDefault
                Toast.makeText(this, "默认字幕：${if (prefs.subtitleEnabledDefault) "开" else "关"}", Toast.LENGTH_SHORT).show()
                refreshSection(entry.title)
            }

            "弹幕透明度" -> {
                val options = (20 downTo 1).map { it / 20f }
                showChoiceDialog(
                    title = "弹幕透明度",
                    items = options.map { String.format(Locale.US, "%.2f", it) },
                    current = String.format(Locale.US, "%.2f", prefs.danmakuOpacity),
                ) { selected ->
                    prefs.danmakuOpacity = selected.toFloatOrNull()?.coerceIn(0.05f, 1.0f) ?: prefs.danmakuOpacity
                    refreshSection(entry.title)
                }
            }

            "弹幕字体大小" -> {
                val options = (10..60 step 2).toList()
                showChoiceDialog(
                    title = "弹幕字体大小(sp)",
                    items = options.map { it.toString() },
                    current = prefs.danmakuTextSizeSp.toInt().toString(),
                ) { selected ->
                    prefs.danmakuTextSizeSp = (selected.toIntOrNull() ?: 18).toFloat().coerceIn(10f, 60f)
                    refreshSection(entry.title)
                }
            }

            "弹幕占屏比" -> {
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
                showChoiceDialog(
                    title = "弹幕占屏比",
                    items = options.map { it.second },
                    current = areaText(prefs.danmakuArea),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first ?: 1.0f
                    prefs.danmakuArea = value
                    refreshSection(entry.title)
                }
            }

            "弹幕速度" -> {
                val options = (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "弹幕速度(1~10)",
                    items = options,
                    current = prefs.danmakuSpeed.toString(),
                ) { selected ->
                    prefs.danmakuSpeed = (selected.toIntOrNull() ?: 4).coerceIn(1, 10)
                    refreshSection(entry.title)
                }
            }

            "跟随B站弹幕屏蔽" -> {
                prefs.danmakuFollowBiliShield = !prefs.danmakuFollowBiliShield
                refreshSection(entry.title)
            }

            "智能云屏蔽" -> {
                prefs.danmakuAiShieldEnabled = !prefs.danmakuAiShieldEnabled
                refreshSection(entry.title)
            }

            "智能云屏蔽等级" -> {
                val options = listOf("默认(3)") + (1..10).map { it.toString() }
                showChoiceDialog(
                    title = "智能云屏蔽等级",
                    items = options,
                    current = aiLevelText(prefs.danmakuAiShieldLevel),
                ) { selected ->
                    prefs.danmakuAiShieldLevel = if (selected.startsWith("默认")) 0 else (selected.toIntOrNull() ?: 0)
                    refreshSection(entry.title)
                }
            }

            "允许滚动弹幕" -> {
                prefs.danmakuAllowScroll = !prefs.danmakuAllowScroll
                refreshSection(entry.title)
            }

            "允许顶部悬停弹幕" -> {
                prefs.danmakuAllowTop = !prefs.danmakuAllowTop
                refreshSection(entry.title)
            }

            "允许底部悬停弹幕" -> {
                prefs.danmakuAllowBottom = !prefs.danmakuAllowBottom
                refreshSection(entry.title)
            }

            "允许彩色弹幕" -> {
                prefs.danmakuAllowColor = !prefs.danmakuAllowColor
                refreshSection(entry.title)
            }

            "允许特殊弹幕" -> {
                prefs.danmakuAllowSpecial = !prefs.danmakuAllowSpecial
                refreshSection(entry.title)
            }

            "默认画质" -> {
                val options =
                    listOf(16, 32, 64, 74, 80, 100, 112, 116, 120, 125, 126, 127, 129).map { it to qnText(it) }
                showChoiceDialog(
                    title = "默认画质",
                    items = options.map { it.second },
                    current = qnText(prefs.playerPreferredQn),
                ) { selected ->
                    val qn = options.firstOrNull { it.second == selected }?.first
                    if (qn != null) prefs.playerPreferredQn = qn
                    refreshSection(entry.title)
                }
            }

            "默认音轨" -> {
                val options = listOf(30251, 30250, 30280, 30232, 30216)
                val optionLabels = options.map { audioText(it) }
                showChoiceDialog(
                    title = "默认音轨",
                    items = optionLabels,
                    current = audioText(prefs.playerPreferredAudioId),
                ) { selected ->
                    val id = options.getOrNull(optionLabels.indexOfFirst { it == selected }.takeIf { it >= 0 } ?: -1)
                    if (id != null) prefs.playerPreferredAudioId = id
                    refreshSection(entry.title)
                }
            }

            "CDN线路" -> {
                val options = listOf(
                    blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO to "bilivideo（默认）",
                    blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN to "mcdn（部分网络更快/更慢）",
                )
                showChoiceDialog(
                    title = "CDN线路",
                    items = options.map { it.second },
                    current = cdnText(prefs.playerCdnPreference),
                ) { selected ->
                    val value = options.firstOrNull { it.second == selected }?.first
                        ?: blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_BILIVIDEO
                    prefs.playerCdnPreference = value
                    refreshSection(entry.title)
                }
            }

            "默认播放速度" -> {
                val options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                showChoiceDialog(
                    title = "默认播放速度",
                    items = options.map { String.format(Locale.US, "%.2fx", it) },
                    current = String.format(Locale.US, "%.2fx", prefs.playerSpeed),
                ) { selected ->
                    val v = selected.removeSuffix("x").toFloatOrNull()
                    if (v != null) prefs.playerSpeed = v.coerceIn(0.25f, 3.0f)
                    refreshSection(entry.title)
                }
            }

            "自动跳到上次播放位置" -> {
                prefs.playerAutoResumeEnabled = !prefs.playerAutoResumeEnabled
                refreshSection(entry.title)
            }

            "自动跳过片段（空降助手）" -> {
                prefs.playerAutoSkipSegmentsEnabled = !prefs.playerAutoSkipSegmentsEnabled
                refreshSection(entry.title)
            }

            "播放前打开详情页" -> {
                prefs.playerOpenDetailBeforePlay = !prefs.playerOpenDetailBeforePlay
                refreshSection(entry.title)
            }

            "播放模式" -> {
                val options = listOf("循环当前", "播放下一个", "播放推荐视频", "什么都不做", "退出播放器")
                showChoiceDialog(
                    title = "播放模式（全局默认）",
                    items = options,
                    current = playbackModeText(prefs.playerPlaybackMode),
                ) { selected ->
                    prefs.playerPlaybackMode =
                        when (selected) {
                            "循环当前" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE
                            "播放下一个" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NEXT
                            "播放推荐视频" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND
                            "退出播放器" -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT
                            else -> blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NONE
                        }
                    refreshSection(entry.title)
                }
            }

            "字幕语言" -> {
                val options = listOf(
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
                    current = subtitleLangText(prefs.subtitlePreferredLang),
                ) { selected ->
                    val code = options.firstOrNull { it.second == selected }?.first ?: "auto"
                    prefs.subtitlePreferredLang = code
                    refreshSection(entry.title)
                }
            }

            "视频编码" -> {
                val options = listOf("AVC", "HEVC", "AV1")
                showChoiceDialog(
                    title = "视频编码(偏好)",
                    items = options,
                    current = prefs.playerPreferredCodec,
                ) { selected ->
                    prefs.playerPreferredCodec = selected
                    refreshSection(entry.title)
                }
            }

            "控制栏按钮" -> showPlayerActionButtonsDialog(sectionIndex = currentSectionIndex, focusTitle = entry.title)

            "显示视频调试信息" -> {
                prefs.playerDebugEnabled = !prefs.playerDebugEnabled
                refreshSection(entry.title)
            }

            "按两次退出键才退出播放器" -> {
                prefs.playerDoubleBackToExit = !prefs.playerDoubleBackToExit
                refreshSection(entry.title)
            }

            "底部常驻进度条" -> {
                prefs.playerPersistentBottomProgressEnabled = !prefs.playerPersistentBottomProgressEnabled
                refreshSection(entry.title)
            }

            "项目地址" -> showProjectDialog()

            "QQ交流群" -> {
                copyToClipboard(label = "QQ交流群", text = QQ_GROUP, toastText = "已复制群号：$QQ_GROUP")
            }

            "检查更新" -> {
                when (val state = testUpdateCheckState) {
                    TestUpdateCheckState.Checking -> {
                        Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
                    }

                    is TestUpdateCheckState.UpdateAvailable -> {
                        startTestUpdateDownload(latestVersionHint = state.latestVersion)
                    }

                    else -> ensureTestUpdateChecked(force = true, refreshUi = true)
                }
            }

            else -> AppLog.i("Settings", "click ${entry.title}")
        }
    }

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

    private fun showGaiaVgateDialog(sectionIndex: Int, focusTitle: String) {
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

        MaterialAlertDialogBuilder(this)
            .setTitle("风控验证")
            .setMessage(msg)
            .setPositiveButton(if (hasVoucher) "开始验证" else "粘贴 v_voucher") { _, _ ->
                if (hasVoucher) {
                    gaiaVgateLauncher.launch(
                        Intent(this, GaiaVgateActivity::class.java)
                            .putExtra(GaiaVgateActivity.EXTRA_V_VOUCHER, vVoucher),
                    )
                } else {
                    showGaiaVgateVoucherDialog(sectionIndex, focusTitle)
                }
            }
            .setNeutralButton("编辑 v_voucher") { _, _ ->
                showGaiaVgateVoucherDialog(sectionIndex, focusTitle)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showGaiaVgateVoucherDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val input = EditText(this).apply {
            setText(prefs.gaiaVgateVVoucher.orEmpty())
            setSelection(text.length)
            hint = "粘贴 v_voucher"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("编辑 v_voucher")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text?.toString().orEmpty().trim()
                prefs.gaiaVgateVVoucher = v.takeIf { it.isNotBlank() }
                prefs.gaiaVgateVVoucherSavedAtMs = if (v.isNotBlank()) System.currentTimeMillis() else -1L
                Toast.makeText(this, if (v.isNotBlank()) "已保存 v_voucher" else "已清除 v_voucher", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNeutralButton("清除") { _, _ ->
                prefs.gaiaVgateVVoucher = null
                prefs.gaiaVgateVVoucherSavedAtMs = -1L
                Toast.makeText(this, "已清除 v_voucher", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
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

    private fun restorePendingFocus(): Boolean {
        if (pendingRestoreBack) {
            pendingRestoreBack = false
            binding.btnBack.requestFocus()
            return true
        }

        val title = pendingRestoreRightTitle ?: lastFocusedRightTitle
        if (!title.isNullOrBlank()) {
            if (focusRightByTitle(title)) return true
        }

        val leftIndex = pendingRestoreLeftIndex ?: lastFocusedLeftIndex
        if (focusLeftAt(leftIndex)) {
            return true
        }

        binding.btnBack.requestFocus()
        return true
    }

    private fun focusRightByTitle(title: String): Boolean {
        val pos = rightAdapter.indexOfTitle(title)
        if (pos == RecyclerView.NO_POSITION) return false
        val holder = binding.recyclerRight.findViewHolderForAdapterPosition(pos)
        if (holder?.itemView?.requestFocus() == true) return true
        return focusRightAt(pos)
    }

    private fun focusRightAt(position: Int): Boolean {
        if (position < 0 || position >= rightAdapter.itemCount) return false
        val token = ++focusRequestToken
        val layoutManager = binding.recyclerRight.layoutManager as? LinearLayoutManager
        if (layoutManager != null) {
            val first = layoutManager.findFirstVisibleItemPosition()
            val last = layoutManager.findLastVisibleItemPosition()
            if (position < first || position > last) {
                layoutManager.scrollToPositionWithOffset(position, 0)
            }
        }
        binding.recyclerRight.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            binding.recyclerRight.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLeftAt(position: Int): Boolean {
        if (position < 0 || position >= leftAdapter.itemCount) return false
        val token = ++focusRequestToken
        val holder = binding.recyclerLeft.findViewHolderForAdapterPosition(position)
        if (holder?.itemView?.requestFocus() == true) return true
        binding.recyclerLeft.scrollToPosition(position)
        binding.recyclerLeft.doOnPreDraw {
            if (token != focusRequestToken) return@doOnPreDraw
            binding.recyclerLeft.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
        return true
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun showProjectDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("项目地址")
            .setMessage(PROJECT_URL)
            .setPositiveButton("打开") { _, _ -> openUrl(PROJECT_URL) }
            .setNeutralButton("复制") { _, _ ->
                copyToClipboard(label = "项目地址", text = PROJECT_URL, toastText = "已复制项目地址")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setData(Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(label: String, text: String, toastText: String? = null) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(this, "无法访问剪贴板", Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, toastText ?: "已复制：$text", Toast.LENGTH_SHORT).show()
    }

    private fun refreshAboutSectionKeepPosition() {
        if (sections.getOrNull(currentSectionIndex) != "关于应用") return
        showSection(currentSectionIndex, keepScroll = true, focusTitle = lastFocusedRightTitle)
    }

    private fun ensureTestUpdateChecked(force: Boolean, refreshUi: Boolean = true) {
        if (testUpdateJob?.isActive == true) return
        if (testUpdateCheckJob?.isActive == true) return
        if (testUpdateCheckState is TestUpdateCheckState.Checking) return

        val now = System.currentTimeMillis()
        val last = testUpdateCheckedAtMs
        val hasFreshResult =
            !force &&
                last > 0 &&
                now - last < UPDATE_CHECK_TTL_MS &&
                testUpdateCheckState !is TestUpdateCheckState.Idle &&
                testUpdateCheckState !is TestUpdateCheckState.Checking
        if (hasFreshResult) return

        testUpdateCheckState = TestUpdateCheckState.Checking
        if (refreshUi) refreshAboutSectionKeepPosition()

        testUpdateCheckJob =
            lifecycleScope.launch {
                try {
                    val latest = TestApkUpdater.fetchLatestVersionName()
                    val current = BuildConfig.VERSION_NAME
                    testUpdateCheckState =
                        if (TestApkUpdater.isRemoteNewer(latest, current)) {
                            TestUpdateCheckState.UpdateAvailable(latest)
                        } else {
                            TestUpdateCheckState.Latest(latest)
                        }
                    testUpdateCheckedAtMs = System.currentTimeMillis()
                } catch (_: CancellationException) {
                    return@launch
                } catch (t: Throwable) {
                    testUpdateCheckState = TestUpdateCheckState.Error(t.message ?: "检查失败")
                    testUpdateCheckedAtMs = System.currentTimeMillis()
                }
                refreshAboutSectionKeepPosition()
            }
    }

    private fun aboutUpdateEntry(): SettingEntry {
        val currentVersion = BuildConfig.VERSION_NAME
        val title = "检查更新"
        val defaultDesc = "从内置直链下载 APK 并覆盖安装（限速）"
        return when (val state = testUpdateCheckState) {
            TestUpdateCheckState.Idle -> SettingEntry(title, "点击检查", defaultDesc)
            TestUpdateCheckState.Checking -> SettingEntry(title, "检查中…", "正在获取最新版本号…")

            is TestUpdateCheckState.Latest -> {
                SettingEntry(title, "已是最新版", "当前：$currentVersion / 最新：${state.latestVersion}")
            }

            is TestUpdateCheckState.UpdateAvailable -> {
                SettingEntry(title, "新版本 ${state.latestVersion}", "当前：$currentVersion，点击更新")
            }

            is TestUpdateCheckState.Error -> {
                val msg = state.message.trim().take(80)
                val desc = if (msg.isBlank()) "检查失败，点击重试" else "检查失败，点击重试（$msg）"
                SettingEntry(title, "检查失败", desc)
            }
        }
    }

    private fun startTestUpdateDownload(latestVersionHint: String? = null) {
        if (testUpdateJob?.isActive == true) {
            Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要授权安装")
                .setMessage("更新需要允许“安装未知应用”。现在去设置开启吗？")
                .setPositiveButton("去设置") { _, _ ->
                    runCatching {
                        val intent =
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }.onFailure {
                        Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val now = System.currentTimeMillis()
        val cooldownLeftMs = TestApkUpdater.cooldownLeftMs(now)
        if (cooldownLeftMs > 0) {
            Toast.makeText(this, "操作太频繁，请稍后再试（${(cooldownLeftMs / 1000).coerceAtLeast(1)}s）", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "检查更新…"

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle("下载更新")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> testUpdateJob?.cancel() }
                .setCancelable(false)
                .show()

        testUpdateJob =
            lifecycleScope.launch {
                try {
                    val currentVersion = BuildConfig.VERSION_NAME
                    val latestVersion = latestVersionHint ?: TestApkUpdater.fetchLatestVersionName()
                    if (!TestApkUpdater.isRemoteNewer(latestVersion, currentVersion)) {
                        testUpdateCheckState = TestUpdateCheckState.Latest(latestVersion)
                        testUpdateCheckedAtMs = System.currentTimeMillis()
                        refreshAboutSectionKeepPosition()
                        runOnUiThread {
                            dialog.dismiss()
                            Toast.makeText(this@SettingsActivity, "已是最新版（当前：$currentVersion）", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    testUpdateCheckState = TestUpdateCheckState.UpdateAvailable(latestVersion)
                    testUpdateCheckedAtMs = System.currentTimeMillis()
                    refreshAboutSectionKeepPosition()

                    runOnUiThread {
                        tvStatus.text = "准备下载…（最新：$latestVersion）"
                        progress.isIndeterminate = true
                    }

                    TestApkUpdater.markStarted(now)
                    val apkFile =
                        TestApkUpdater.downloadApkToCache(
                            context = this@SettingsActivity,
                            url = TestApkUpdater.TEST_APK_URL,
                        ) { state ->
                            runOnUiThread {
                                when (state) {
                                    is TestApkUpdater.Progress.Connecting -> {
                                        tvStatus.text = "连接中…"
                                        progress.isIndeterminate = true
                                    }

                                    is TestApkUpdater.Progress.Downloading -> {
                                        val percent = state.percent
                                        progress.isIndeterminate = percent == null
                                        if (percent != null) progress.progress = percent
                                        tvStatus.text = state.hint
                                    }
                                }
                            }
                        }

                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "下载完成，准备安装…", Toast.LENGTH_SHORT).show()
                    }

                    TestApkUpdater.installApk(this@SettingsActivity, apkFile)
                } catch (_: CancellationException) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "已取消更新", Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    AppLog.w("TestUpdate", "update failed: ${t.message}")
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity, "更新失败：${t.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
    }

    private fun showChoiceDialog(title: String, items: List<String>, current: String, onPicked: (String) -> Unit) {
        val checked = items.indexOf(current).coerceAtLeast(0)
        SingleChoiceDialog.show(
            context = this,
            title = title,
            items = items,
            checkedIndex = checked,
            negativeText = "取消",
        ) { _, label ->
            onPicked(label)
        }
    }

    private fun showPlayerActionButtonsDialog(sectionIndex: Int, focusTitle: String) {
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
            MaterialAlertDialogBuilder(this)
                .setTitle("控制栏按钮")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val key = keys[which]
                    if (isChecked) selected.add(key) else selected.remove(key)
                }
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    prefs.playerActionButtons = keys.filter { selected.contains(it) }
                    showSection(sectionIndex, focusTitle = focusTitle)
                }
                .create()

        dialog.setOnShowListener { dialog.listView?.requestFocus() }
        dialog.show()
    }

    private fun showUserAgentDialog(sectionIndex: Int, focusTitle: String) {
        val prefs = BiliClient.prefs
        val input = EditText(this).apply {
            setText(prefs.userAgent)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_NORMAL
            minLines = 3
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("User-Agent")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val ua = input.text?.toString().orEmpty().trim()
                if (ua.isBlank()) {
                    Toast.makeText(this, "User-Agent 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.userAgent = ua
                Toast.makeText(this, "已更新 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNeutralButton("重置默认") { _, _ ->
                prefs.userAgent = blbl.cat3399.core.prefs.AppPrefs.DEFAULT_UA
                Toast.makeText(this, "已重置 User-Agent", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearLoginDialog(sectionIndex: Int, focusTitle: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清除登录")
            .setMessage("将清除 Cookie（SESSDATA 等），需要重新登录。确定继续吗？")
            .setPositiveButton("确定清除") { _, _ ->
                BiliClient.cookies.clearAll()
                BiliClient.prefs.webRefreshToken = null
                BiliClient.prefs.webCookieRefreshCheckedEpochDay = -1L
                BiliClient.prefs.biliTicketCheckedEpochDay = -1L
                BiliClient.prefs.gaiaVgateVVoucher = null
                BiliClient.prefs.gaiaVgateVVoucherSavedAtMs = -1L
                Toast.makeText(this, "已清除 Cookie", Toast.LENGTH_SHORT).show()
                showSection(sectionIndex, focusTitle = focusTitle)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showClearCacheDialog(sectionIndex: Int, focusTitle: String) {
        if (clearCacheJob?.isActive == true) {
            Toast.makeText(this, "清理中…", Toast.LENGTH_SHORT).show()
            return
        }
        if (testUpdateJob?.isActive == true) {
            Toast.makeText(this, "下载中，稍后再试", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("清理缓存")
            .setMessage("确定清理缓存？")
            .setPositiveButton("清理") { _, _ -> startClearCache(sectionIndex, focusTitle) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startClearCache(sectionIndex: Int, focusTitle: String) {
        cacheSizeJob?.cancel()
        val view = layoutInflater.inflate(blbl.cat3399.R.layout.dialog_test_update_progress, null, false)
        val tvStatus = view.findViewById<TextView>(blbl.cat3399.R.id.tv_status)
        val progress = view.findViewById<LinearProgressIndicator>(blbl.cat3399.R.id.progress)
        progress.isIndeterminate = true
        progress.max = 100
        tvStatus.text = "清理中…"

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle("清理中")
                .setView(view)
                .setNegativeButton("取消") { _, _ -> clearCacheJob?.cancel() }
                .setCancelable(false)
                .show()

        clearCacheJob =
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(cacheDir, externalCacheDir)
                        for (dir in dirs) {
                            for (child in (dir.listFiles() ?: emptyArray())) {
                                currentCoroutineContext().ensureActive()
                                runCatching { child.deleteRecursively() }
                            }
                        }
                    }

                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "已清理缓存", Toast.LENGTH_SHORT).show()
                    cacheSizeBytes = 0L
                    showSection(sectionIndex, focusTitle = focusTitle)
                    updateCacheSize(force = true)
                } catch (_: CancellationException) {
                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "已取消", Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    AppLog.w("Settings", "clear cache failed: ${t.message}", t)
                    dialog.dismiss()
                    Toast.makeText(this@SettingsActivity, "清理失败", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun cacheSizeText(): String {
        val size = cacheSizeBytes ?: return "-"
        return formatBytes(size)
    }

    private fun updateCacheSize(force: Boolean) {
        if (!force && cacheSizeBytes != null) return
        if (cacheSizeJob?.isActive == true) return
        cacheSizeJob =
            lifecycleScope.launch {
                val size =
                    withContext(Dispatchers.IO) {
                        val dirs = listOfNotNull(cacheDir, externalCacheDir)
                        dirs.sumOf { dirChildrenSizeBytes(it) }.coerceAtLeast(0L)
                    }
                val old = cacheSizeBytes
                cacheSizeBytes = size
                if (sections.getOrNull(currentSectionIndex) == "通用设置" && old != size) {
                    showSection(currentSectionIndex, keepScroll = true)
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

    private fun audioText(id: Int): String = when (id) {
        30251 -> "Hi-Res 无损"
        30250 -> "杜比全景声"
        30280 -> "192K"
        30232 -> "132K"
        30216 -> "64K"
        else -> id.toString()
    }

    private fun subtitleLangText(code: String): String = when (code) {
        "auto" -> "自动"
        "zh-Hans" -> "中文(简体)"
        "zh-Hant" -> "中文(繁体)"
        "en" -> "English"
        "ja" -> "日本語"
        "ko" -> "한국어"
        else -> code
    }

    private fun areaText(area: Float): String = when {
        area >= 0.99f -> "不限"
        area >= 0.78f -> "4/5"
        area >= 0.71f -> "3/4"
        area >= 0.62f -> "2/3"
        area >= 0.55f -> "3/5"
        area >= 0.45f -> "1/2"
        area >= 0.36f -> "2/5"
        area >= 0.29f -> "1/3"
        area >= 0.22f -> "1/4"
        else -> "1/5"
    }

    private fun aiLevelText(level: Int): String = if (level == 0) "默认(3)" else level.coerceIn(1, 10).toString()

    private fun gridSpanText(span: Int): String = if (span <= 0) "自动" else span.toString()

    private fun sidebarSizeText(prefValue: String): String = when (prefValue) {
        blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL -> "小"
        blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE -> "大"
        else -> "中"
    }

    private fun cdnText(code: String): String = when (code) {
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN -> "mcdn"
        else -> "bilivideo"
    }

    private fun playerActionButtonsText(buttons: List<String>): String {
        val enabled = buttons.toSet()
        val labels =
            buildList {
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_LIKE)) add("点赞")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_COIN)) add("投币")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_ACTION_BTN_FAV)) add("收藏")
            }
        return if (labels.isEmpty()) "无" else labels.joinToString(separator = " / ")
    }

    private fun screenText(): String {
        val uiDm = resources.displayMetrics
        val width = uiDm.widthPixels
        val height = uiDm.heightPixels

        val sysDm = Resources.getSystem().displayMetrics
        val sysScale = sysDm.density
        val scaleText =
            if (sysScale.isFinite() && sysScale > 0f) {
                val x100 = (sysScale * 100f).roundToInt().toFloat() / 100f
                val x10 = (x100 * 10f).roundToInt().toFloat() / 10f
                val show =
                    when {
                        kotlin.math.abs(x100 - x100.toInt()) < 0.001f -> x100.toInt().toString()
                        kotlin.math.abs(x100 - x10) < 0.001f -> String.format(Locale.US, "%.1f", x100)
                        else -> String.format(Locale.US, "%.2f", x100)
                    }
                "${show}x"
            } else {
                "-"
            }

        // Settings -> Device Info -> Screen: show only resolution + system display scaling.
        return "${width}x${height} $scaleText"
    }

    private fun ramText(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "-"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem.takeIf { it > 0 } ?: return "-"
        val avail = mi.availMem.coerceAtLeast(0)
        return "总${formatBytes(total)} 可用${formatBytes(avail)}"
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun playbackModeText(code: String): String = when (code) {
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环当前"
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_NEXT -> "播放下一个"
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> "播放推荐视频"
        blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
        else -> "什么都不做"
    }

    private fun qnText(qn: Int): String = when (qn) {
        16 -> "360P 流畅"
        32 -> "480P 清晰"
        64 -> "720P 高清"
        74 -> "720P60 高帧率"
        80 -> "1080P 高清"
        112 -> "1080P+ 高码率"
        116 -> "1080P60 高帧率"
        120 -> "4K 超清"
        127 -> "8K 超高清"
        125 -> "HDR 真彩色"
        126 -> "杜比视界"
        129 -> "HDR Vivid"
        6 -> "240P 极速"
        100 -> "智能修复"
        else -> qn.toString()
    }

    companion object {
        private const val PROJECT_URL = "https://github.com/cat3399/blbl"
        private const val QQ_GROUP = "1080221910"
        private const val UPDATE_CHECK_TTL_MS = 60_000L
    }
}
