package blbl.cat3399.feature.live

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.databinding.DialogLiveChatBinding
import blbl.cat3399.feature.player.PlayerContentAutoScale
import blbl.cat3399.feature.player.PlayerOsdSizing
import blbl.cat3399.feature.player.PlayerSettingsAdapter
import blbl.cat3399.feature.player.PlayerUiMode
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class LivePlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var autoHideJob: Job? = null
    private var debugJob: Job? = null
    private var autoFailoverJob: Job? = null
    private var finishOnBackKeyUp: Boolean = false
    private var controlsVisible: Boolean = false
    private var lastInteractionAtMs: Long = 0L
    private var autoFailoverWindowStartAtMs: Long = 0L
    private var autoFailoverSwitchCount: Int = 0
    private var autoFailoverLastSwitchAtMs: Long = 0L
    private var autoFailoverInFlight: Boolean = false
    private var fixedAutoScale: Float? = null
    private var fixedAutoScaleWindowWidth: Int = -1
    private var fixedAutoScaleWindowHeight: Int = -1

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (controlsVisible) setControlsVisible(false)
        }
    }

    private var roomId: Long = 0L
    private var realRoomId: Long = 0L
    private var roomTitle: String = ""
    private var roomUname: String = ""

    private var session: LiveSession = LiveSession()

    private var lastPlay: BiliApi.LivePlayUrl? = null
    private var lastLiveStatus: Int = 0

    private val chatItems = ArrayDeque<LiveChatAdapter.Item>()
    private val chatMax = 200

    private var messageClient: LiveMessageClient? = null
    private var liveDanmakuBaseUptimeMs: Long = 0L
    private var liveDanmakuLastAppendMs: Int = Int.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        recomputeFixedAutoScaleIfWindowChanged(force = false)
        PlayerUiMode.applyLive(this, binding, fixedAutoScale = fixedAutoScale)

        // Re-apply only when real window size changes; keep panel open/close from affecting OSD scale.
        binding.playerView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (isFinishing) return@addOnLayoutChangeListener
            val w = r - l
            val h = b - t
            val ow = or - ol
            val oh = ob - ot
            if (w <= 0 || h <= 0 || (w == ow && h == oh)) return@addOnLayoutChangeListener
            if (recomputeFixedAutoScaleIfWindowChanged(force = false)) {
                PlayerUiMode.applyLive(this, binding, fixedAutoScale = fixedAutoScale)
            }
        }

        roomId = intent.getLongExtra(EXTRA_ROOM_ID, 0L)
        roomTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        roomUname = intent.getStringExtra(EXTRA_UNAME).orEmpty()
        if (roomId <= 0L) {
            AppToast.show(this, "缺少 room_id")
            finish()
            return
        }

        // Live: no seek bar.
        binding.seekProgress.visibility = View.GONE
        binding.tvTime.visibility = View.GONE
        binding.btnSubtitle.visibility = View.GONE
        binding.tvSeekHint.visibility = View.GONE
        binding.btnPrev.visibility = View.GONE
        binding.btnNext.visibility = View.GONE
        binding.tvOnline.visibility = View.GONE
        binding.llTitleMeta.visibility = View.GONE
        binding.btnUp.visibility = View.GONE
        binding.btnLike.visibility = View.GONE
        binding.btnCoin.visibility = View.GONE
        binding.btnFav.visibility = View.GONE
        binding.btnRecommend.visibility = View.GONE

        binding.btnBack.setOnClickListener { finish() }

        val exo =
            ExoPlayer.Builder(this)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
        player = exo
        binding.playerView.player = exo
        liveDanmakuBaseUptimeMs = SystemClock.elapsedRealtime()
        liveDanmakuLastAppendMs = Int.MIN_VALUE
        binding.danmakuView.setPositionProvider { liveDanmakuPositionMs() }
        binding.danmakuView.setIsPlayingProvider { exo.isPlaying }
        binding.danmakuView.setPlaybackSpeedProvider { exo.playbackParameters.speed }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }

        exo.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    AppLog.e("LivePlayer", "onPlayerError", error)
                    if (tryAutoFailoverOnError(error)) return
                    AppToast.show(this@LivePlayerActivity, "播放失败：${error.errorCodeName}")
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayPauseIcon(isPlaying)
                    noteUserInteraction()
                }
            },
        )

        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) p.pause() else p.play()
            setControlsVisible(true)
        }
        binding.btnAdvanced.setOnClickListener {
            val willShow = binding.settingsPanel.visibility != View.VISIBLE
            binding.settingsPanel.visibility = if (willShow) View.VISIBLE else View.GONE
            setControlsVisible(true)
            if (willShow) {
                focusSettingsPanel()
            } else {
                focusAdvancedControl()
            }
        }
        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }

        binding.playerView.setOnClickListener {
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                binding.settingsPanel.visibility = View.GONE
                setControlsVisible(true)
                focusFirstControl()
                return@setOnClickListener
            }
            toggleControls()
        }

        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()

        setupSettingsPanel()
        // Live default: keep OSD hidden. User brings it up via D-pad / OK / Menu keys.
        binding.settingsPanel.visibility = View.GONE
        setControlsVisible(false)
        updateDanmakuButton()

        lifecycleScope.launch { loadAndPlay(initial = true) }
    }

    override fun onResume() {
        super.onResume()
        PlayerOsdSizing.applyTheme(this)
        recomputeFixedAutoScaleIfWindowChanged(force = false)
        PlayerUiMode.applyLive(this, binding, fixedAutoScale = fixedAutoScale)
    }

    private fun recomputeFixedAutoScaleIfWindowChanged(force: Boolean): Boolean {
        val windowWidth = binding.root.width
        val windowHeight = binding.root.height
        val playerWidth = binding.playerView.width
        val playerHeight = binding.playerView.height
        if (windowWidth <= 0 || windowHeight <= 0 || playerWidth <= 0 || playerHeight <= 0) return false
        if (!force && fixedAutoScale != null && windowWidth == fixedAutoScaleWindowWidth && windowHeight == fixedAutoScaleWindowHeight) {
            return false
        }
        fixedAutoScale = PlayerContentAutoScale.factor(binding.playerView, resources.displayMetrics.density)
        fixedAutoScaleWindowWidth = windowWidth
        fixedAutoScaleWindowHeight = windowHeight
        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun onDestroy() {
        val t0 = SystemClock.elapsedRealtime()
        AppLog.i("LivePlayer", "activity:onDestroy:start")
        messageClient?.close()
        messageClient = null
        debugJob?.cancel()
        autoFailoverJob?.cancel()
        autoFailoverInFlight = false
        autoHideJob?.cancel()
        binding.playerView.player = null
        val releaseStart = SystemClock.elapsedRealtime()
        player?.release()
        val releaseCostMs = SystemClock.elapsedRealtime() - releaseStart
        AppLog.i("LivePlayer", "exo:release:done cost=${releaseCostMs}ms")
        player = null
        val totalCostMs = SystemClock.elapsedRealtime() - t0
        AppLog.i("LivePlayer", "activity:onDestroy:beforeSuper cost=${totalCostMs}ms")
        super.onDestroy()
    }

    override fun finish() {
        if (::binding.isInitialized) {
            binding.playerView.player = null
        }
        super.finish()
        applyCloseTransitionNoAnim()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_UP) {
            if (isExitKey(keyCode)) {
                if (finishOnBackKeyUp) {
                    finishOnBackKeyUp = false
                    finish()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (isInteractionKey(keyCode)) noteUserInteraction()

        when (keyCode) {
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return true
                setControlsVisible(true)
                focusFirstControl()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                // handled below (shared exit logic)
            }
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            -> {
                // handled below (shared exit logic)
            }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                setControlsVisible(true)
                if (!hasControlsFocus()) {
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (binding.settingsPanel.visibility != View.VISIBLE && !hasControlsFocus()) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (!controlsVisible && binding.settingsPanel.visibility != View.VISIBLE) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> {
                binding.btnPlayPause.performClick()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                if (binding.settingsPanel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                if (!controlsVisible) {
                    setControlsVisible(true)
                    // Align with UP/DOWN/CENTER behavior: show OSD and move focus to controls in one press.
                    focusFirstControl()
                    return true
                }
                if (!hasControlsFocus()) {
                    focusFirstControl()
                    return true
                }
            }
        }
        if (isExitKey(keyCode)) {
            finishOnBackKeyUp = false
            if (binding.settingsPanel.visibility == View.VISIBLE) {
                binding.settingsPanel.visibility = View.GONE
                setControlsVisible(true)
                focusAdvancedControl()
                return true
            }
            if (controlsVisible) {
                setControlsVisible(false)
                return true
            }
            finishOnBackKeyUp = doubleBackToExit.shouldExit(enabled = BiliClient.prefs.playerDoubleBackToExit)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupSettingsPanel() {
        val settingsAdapter =
            PlayerSettingsAdapter { item ->
                when (item.title) {
                    "清晰度" -> showQualityDialog()
                    "线路选择" -> showLineDialog()
                    else -> AppToast.show(this, "暂未实现：${item.title}")
                }
            }
        binding.recyclerSettings.adapter = settingsAdapter
        binding.recyclerSettings.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerSettings.addOnChildAttachStateChangeListener(
            object : androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerSettings.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != androidx.recyclerview.widget.RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0 && !binding.recyclerSettings.canScrollVertically(-1)) return@setOnKeyListener true
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (binding.recyclerSettings.adapter?.itemCount ?: 0) - 1
                                if (pos == last && !binding.recyclerSettings.canScrollVertically(1)) return@setOnKeyListener true
                                false
                            }

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
        refreshSettings()
        updateDebugOverlay()
    }

    private fun refreshSettings() {
        val p = lastPlay
        val qn = session.targetQn.takeIf { it > 0 } ?: p?.currentQn ?: LIVE_QN_ORIGINAL
        val qLabel = liveQnLabel(qn, p)
        val lineLabel =
            p?.lines
                ?.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                ?.let { "线路 ${it.order}" }
                ?: "自动"
        val list =
            listOf(
                PlayerSettingsAdapter.SettingItem("清晰度", qLabel),
                PlayerSettingsAdapter.SettingItem("线路选择", lineLabel),
            )
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.submit(list)
    }

    private fun toggleControls() {
        val willShow = !controlsVisible
        if (!willShow) binding.settingsPanel.visibility = View.GONE
        setControlsVisible(willShow)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val show = visible || binding.settingsPanel.visibility == View.VISIBLE
        binding.topBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.bottomBar.visibility = if (show) View.VISIBLE else View.GONE

        restartAutoHideTimer()
    }

    private fun restartAutoHideTimer() {
        autoHideJob?.cancel()
        if (!controlsVisible) return
        if (binding.settingsPanel.visibility == View.VISIBLE) return
        val token = lastInteractionAtMs
        autoHideJob =
            lifecycleScope.launch {
                delay(AUTO_HIDE_MS)
                if (token != lastInteractionAtMs) return@launch
                if (binding.settingsPanel.visibility == View.VISIBLE) return@launch
                if (controlsVisible) setControlsVisible(false)
            }
    }

    private fun focusFirstControl(): Boolean {
        if (binding.btnPlayPause.visibility == View.VISIBLE) return binding.btnPlayPause.requestFocus()
        return binding.btnBack.requestFocus()
    }

    private fun focusAdvancedControl(): Boolean {
        return binding.btnAdvanced.requestFocus()
    }

    private fun focusSettingsPanel() {
        binding.recyclerSettings.post {
            val child = binding.recyclerSettings.getChildAt(0)
            if (child != null) {
                child.requestFocus()
                return@post
            }

            binding.recyclerSettings.scrollToPosition(0)
            binding.recyclerSettings.post {
                val first = binding.recyclerSettings.getChildAt(0)
                (first ?: binding.recyclerSettings).requestFocus()
            }
        }
    }

    private fun hasControlsFocus(): Boolean {
        if (binding.settingsPanel.visibility == View.VISIBLE) return true
        return binding.topBar.hasFocus() || binding.bottomBar.hasFocus()
    }

    private fun noteUserInteraction() {
        lastInteractionAtMs = SystemClock.uptimeMillis()
        restartAutoHideTimer()
    }

    private fun isInteractionKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
            -> true
            else -> false
        }
    }

    private fun isExitKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE, // some TV remotes map “Exit” to ESC
            KeyEvent.KEYCODE_BUTTON_B, // gamepad B
            -> true
            else -> false
        }
    }

    private fun shouldAutoFailoverOnError(error: PlaybackException): Boolean {
        // Limit to IO/network errors where switching CDN host is likely to help.
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> true
            else -> false
        }
    }

    private fun tryAutoFailoverOnError(error: PlaybackException): Boolean {
        if (!shouldAutoFailoverOnError(error)) return false
        if (isFinishing || isDestroyed) return false
        if (autoFailoverInFlight) return false

        val play = lastPlay ?: return false
        val lines = play.lines
        if (lines.size <= 1) return false

        val nowMs = SystemClock.elapsedRealtime()
        if (autoFailoverWindowStartAtMs <= 0L || nowMs - autoFailoverWindowStartAtMs > AUTO_FAILOVER_WINDOW_MS) {
            autoFailoverWindowStartAtMs = nowMs
            autoFailoverSwitchCount = 0
        }
        if (nowMs - autoFailoverLastSwitchAtMs < AUTO_FAILOVER_MIN_INTERVAL_MS) return false

        // Avoid endless loops if the room is not playable (region/permission/not live).
        val maxSwitches = (lines.size - 1).coerceIn(1, AUTO_FAILOVER_MAX_SWITCHES)
        if (autoFailoverSwitchCount >= maxSwitches) return false

        val currentIndex = (session.lineOrder - 1).coerceIn(0, lines.lastIndex)
        val nextIndex = (currentIndex + 1) % lines.size
        if (nextIndex == currentIndex) return false

        val fromOrder = lines[currentIndex].order
        val toOrder = lines[nextIndex].order
        AppLog.w("LivePlayer", "autoFailover error=${error.errorCodeName} line=$fromOrder -> $toOrder")

        autoFailoverSwitchCount += 1
        autoFailoverLastSwitchAtMs = nowMs
        autoFailoverInFlight = true
        autoFailoverJob?.cancel()

        // Stop current load attempts to avoid duplicate error callbacks while we are switching.
        runCatching { player?.stop() }

        session = session.copy(lineOrder = nextIndex + 1)
        refreshSettings()
        AppToast.show(this, "线路 $fromOrder 失败，尝试线路 $toOrder")

        autoFailoverJob =
            lifecycleScope.launch {
                try {
                    loadAndPlay(initial = false)
                } finally {
                    autoFailoverInFlight = false
                }
            }
        return true
    }

    private suspend fun loadAndPlay(initial: Boolean) {
        val exo = player ?: return
        try {
            val info = BiliApi.liveRoomInfo(roomId)
            realRoomId = info.roomId
            lastLiveStatus = info.liveStatus

            val title = info.title.ifBlank { roomTitle }
            binding.tvTitle.text =
                buildString {
                    append(title.ifBlank { "直播间 $realRoomId" })
                    if (roomUname.isNotBlank()) append(" · ").append(roomUname)
                }

            val qn = session.targetQn.takeIf { it > 0 } ?: 150
            val play = BiliApi.livePlayUrl(realRoomId, qn)
            lastPlay = play
            if (play.lines.isNotEmpty()) {
                // If saved lineOrder becomes out of range (API may return fewer lines), fall back to line 1.
                val safeLineOrder = session.lineOrder.takeIf { it in 1..play.lines.size } ?: 1
                if (safeLineOrder != session.lineOrder) session = session.copy(lineOrder = safeLineOrder)
            }
            refreshSettings()

            val pickedLine =
                play.lines.getOrNull((session.lineOrder - 1).coerceAtLeast(0))
                    ?: play.lines.firstOrNull()
            if (pickedLine == null) error("No playable live url")

            val factory = OkHttpDataSource.Factory(BiliClient.cdnOkHttp)
            val mediaSourceFactory = DefaultMediaSourceFactory(factory)
            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.parse(pickedLine.url)))
            exo.setMediaSource(mediaSource)
            exo.prepare()
            exo.playWhenReady = true

            if (initial) connectDanmaku()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e("LivePlayer", "loadAndPlay failed", t)
            val e = t as? BiliApiException
            val msg = e?.let { "B 站返回：${it.apiCode} / ${it.apiMessage}" } ?: (t.message ?: "未知错误")
            AppToast.showLong(this, msg)
        }
    }

    private fun connectDanmaku() {
        messageClient?.close()
        messageClient = null
        val rid = realRoomId.takeIf { it > 0 } ?: return
        AppLog.i("LiveDanmaku", "connect room=$rid")

        messageClient =
            LiveMessageClient(
                roomId = rid,
                onDanmaku = { ev ->
                    runOnUiThread(
                        Runnable {
                            appendLiveDanmakuEvent(ev)
                        },
                    )
                },
                onSuperChat = { ev ->
                    runOnUiThread(
                        Runnable {
                            val title = "SC ¥${ev.price} · ${ev.user.ifBlank { "匿名" }}"
                            pushChatItem(LiveChatAdapter.Item(title = title, body = ev.message))
                        },
                    )
                },
                onStatus = { msg ->
                    runOnUiThread(
                        Runnable {
                            AppLog.d("LiveWs", msg)
                            pushChatItem(LiveChatAdapter.Item(title = "系统", body = msg))
                        },
                    )
                },
            )

        lifecycleScope.launch {
            runCatching { messageClient?.connect() }
                .onFailure { AppLog.w("LiveWs", "connect failed", it) }
        }
    }

    private fun liveDanmakuPositionMs(): Long {
        val base = liveDanmakuBaseUptimeMs
        if (base <= 0L) return 0L
        val posMs = SystemClock.elapsedRealtime() - base
        return posMs.coerceIn(0L, Int.MAX_VALUE.toLong())
    }

    private fun liveDanmakuAppendTimeMs(): Int {
        val nowMs = liveDanmakuPositionMs().toInt()
        val lastMs = liveDanmakuLastAppendMs
        val safeMs = if (lastMs == Int.MIN_VALUE) nowMs else nowMs.coerceAtLeast(lastMs)
        liveDanmakuLastAppendMs = safeMs
        return safeMs
    }

    private fun appendLiveDanmakuEvent(ev: LiveMessageClient.LiveDanmakuEvent) {
        if (!session.danmaku.enabled) return
        if (player == null) return
        val timeMs = liveDanmakuAppendTimeMs()

        val d =
            Danmaku(
                timeMs = timeMs,
                mode = 1,
                text = ev.text,
                color = ev.color,
                fontSize = 25,
                weight = 0,
            )
        binding.danmakuView.appendDanmakus(listOf(d), maxItems = 2000, alreadySorted = true)
        pushChatItem(LiveChatAdapter.Item(title = "弹幕", body = ev.text))
    }

    private fun pushChatItem(item: LiveChatAdapter.Item) {
        chatItems.addLast(item)
        while (chatItems.size > chatMax) chatItems.removeFirst()
    }

    private fun showQualityDialog() {
        val play = lastPlay ?: run {
            AppToast.show(this, "暂无可用清晰度")
            return
        }
        val available = play.acceptQn.ifEmpty { play.qnDesc.keys.sortedDescending() }.distinct()
        if (available.isEmpty()) {
            AppToast.show(this, "暂无可用清晰度")
            return
        }
        val optionsAvailable = available.sortedWith(compareBy({ it != LIVE_QN_ORIGINAL }, { -it }))
        val options = optionsAvailable.map { q -> liveQnLabel(q, play) }
        val current = session.targetQn.takeIf { it > 0 } ?: play.currentQn
        val checked =
            optionsAvailable.indexOf(current).takeIf { it >= 0 }
                ?: optionsAvailable.indexOf(LIVE_QN_ORIGINAL).takeIf { it >= 0 }
                ?: 0
        SingleChoiceDialog.show(
            context = this,
            title = "清晰度",
            items = options,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val picked = optionsAvailable.getOrNull(which) ?: return@show
            session = session.copy(targetQn = picked)
            session = session.copy(lineOrder = 1) // reset line
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showLineDialog() {
        val play = lastPlay ?: run {
            AppToast.show(this, "暂无可用线路")
            return
        }
        val lines = play.lines
        if (lines.isEmpty()) {
            AppToast.show(this, "暂无可用线路")
            return
        }
        val options = lines.map { "线路 ${it.order}" }
        val checked = (session.lineOrder - 1).coerceIn(0, lines.size - 1)
        SingleChoiceDialog.show(
            context = this,
            title = "线路",
            items = options,
            checkedIndex = checked,
            negativeText = "取消",
        ) { which, _ ->
            val picked = lines.getOrNull(which) ?: return@show
            session = session.copy(lineOrder = picked.order)
            refreshSettings()
            lifecycleScope.launch { loadAndPlay(initial = false) }
        }
    }

    private fun showChatDialog() {
        val dialogBinding = DialogLiveChatBinding.inflate(layoutInflater)
        val adapter = LiveChatAdapter()
        dialogBinding.recycler.adapter = adapter
        dialogBinding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        adapter.submit(chatItems.toList().asReversed())
        MaterialAlertDialogBuilder(this)
            .setTitle("弹幕 / SC")
            .setView(dialogBinding.root)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val icon = if (isPlaying) blbl.cat3399.R.drawable.ic_player_pause else blbl.cat3399.R.drawable.ic_player_play
        binding.btnPlayPause.setImageResource(icon)
    }

    private fun updateDanmakuButton() {
        binding.btnDanmaku.imageTintList = null
        binding.btnDanmaku.isSelected = session.danmaku.enabled
    }

    private fun updateDebugOverlay() {
        val enabled = session.debugEnabled
        binding.tvDebug.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.danmakuView.setDebugEnabled(enabled)
        debugJob?.cancel()
        if (!enabled) return
        val exo = player ?: return
        debugJob =
            lifecycleScope.launch {
                while (isActive) {
                    val play = lastPlay
                    binding.tvDebug.text =
                        buildString {
                            append("room=").append(realRoomId)
                            append(" qn=").append(play?.currentQn ?: 0)
                            append(" line=").append(session.lineOrder)
                            append(" pos=").append(exo.currentPosition).append("ms")
                            buildDebugDisplayText()?.let { disp ->
                                append('\n')
                                append("disp=").append(disp)
                            }
                            runCatching { binding.danmakuView.getDebugStats() }.getOrNull()?.let { dm ->
                                append('\n')
                                append("dm=").append(if (dm.configEnabled) "on" else "off")
                                append(" fps=").append(String.format(Locale.US, "%.1f", dm.drawFps))
                                append(" act=").append(dm.lastFrameActive)
                                append(" pend=").append(dm.lastFramePending)
                                append(" hit=").append(dm.lastFrameCachedDrawn).append('/').append(dm.lastFrameActive)
                                append(" fb=").append(dm.lastFrameFallbackDrawn)
                                append(" q=").append(dm.queueDepth)
                                if (dm.invalidateFull) {
                                    append(" inv=full")
                                } else {
                                    append(" inv=").append(dm.invalidateTopPx).append('-').append(dm.invalidateBottomPx)
                                }
                            }
                        }
                    delay(500)
                }
            }
    }

    private fun buildDebugDisplayText(): String? {
        val display = binding.root.display ?: return null
        val hz = display.refreshRate.takeIf { it > 0f }
        if (Build.VERSION.SDK_INT < 23) {
            return hz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
        }
        val mode = display.mode
        val w = mode.physicalWidth.takeIf { it > 0 } ?: 0
        val h = mode.physicalHeight.takeIf { it > 0 } ?: 0
        val mhz = mode.refreshRate.takeIf { it > 0f } ?: hz
        val hzText = mhz?.let { String.format(Locale.US, "%.0fHz", it) } ?: "-"
        if (w <= 0 || h <= 0) return hzText
        return "${w}x${h}@${hzText}"
    }

    private fun liveQnLabel(qn: Int, play: BiliApi.LivePlayUrl?): String {
        val fromApi = play?.qnDesc?.get(qn)?.takeIf { it.isNotBlank() }
        if (fromApi != null) return fromApi
        return when (qn) {
            30000 -> "杜比"
            20000 -> "4K"
            10000 -> "原画"
            400 -> "蓝光"
            250 -> "超清"
            150 -> "高清"
            80 -> "流畅"
            else -> qn.toString()
        }
    }

    private data class LiveSession(
        val targetQn: Int = LIVE_QN_ORIGINAL,
        val lineOrder: Int = 1,
        val danmaku: DanmakuSessionSettings =
            DanmakuSessionSettings(
                enabled = BiliClient.prefs.danmakuEnabled,
                opacity = BiliClient.prefs.danmakuOpacity,
                textSizeSp = BiliClient.prefs.danmakuTextSizeSp,
                speedLevel = BiliClient.prefs.danmakuSpeed,
                area = BiliClient.prefs.danmakuArea,
            ),
        val debugEnabled: Boolean = false,
    )

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UNAME = "uname"

        private const val LIVE_QN_ORIGINAL = 10_000
        private const val AUTO_HIDE_MS = 4_000L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 2_500L
        private const val AUTO_FAILOVER_WINDOW_MS = 12_000L
        private const val AUTO_FAILOVER_MIN_INTERVAL_MS = 1_200L
        private const val AUTO_FAILOVER_MAX_SWITCHES = 4
    }
}
