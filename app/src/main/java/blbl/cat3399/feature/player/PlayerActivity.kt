package blbl.cat3399.feature.player

import android.content.Intent
import android.net.Uri
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.FocusFinder
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.MarginLayoutParams
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.CaptionStyleCompat
import blbl.cat3399.BlblApp
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.api.SponsorBlockApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.DanmakuShield
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.ActivityStackLimiter
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.DoubleBackToExitHandler
import blbl.cat3399.core.ui.FocusReturn
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.SingleChoiceDialog
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.util.Format as BlblFormat
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.player.danmaku.DanmakuSessionSettings
import blbl.cat3399.feature.settings.SettingsActivity
import blbl.cat3399.databinding.ActivityPlayerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : BaseActivity() {
    internal lateinit var binding: ActivityPlayerBinding
    internal var player: ExoPlayer? = null
    internal val sidePanelFocusReturn = FocusReturn()
    internal var debugJob: kotlinx.coroutines.Job? = null
    internal val debug = PlayerDebugMetrics()
    internal var progressJob: kotlinx.coroutines.Job? = null
    internal var autoResumeJob: kotlinx.coroutines.Job? = null
    internal var autoResumeHintTimeoutJob: kotlinx.coroutines.Job? = null
    internal var autoResumeHintVisible: Boolean = false
    internal var autoSkipFetchJob: kotlinx.coroutines.Job? = null
    internal var autoSkipHintVisible: Boolean = false
    private var reportProgressJob: kotlinx.coroutines.Job? = null
    internal var autoHideJob: kotlinx.coroutines.Job? = null
    internal var seekOsdHideJob: kotlinx.coroutines.Job? = null
    internal var holdSeekJob: kotlinx.coroutines.Job? = null
    internal var seekHintJob: kotlinx.coroutines.Job? = null
    internal var keyScrubEndJob: kotlinx.coroutines.Job? = null
    internal var scrubbing: Boolean = false
    internal var lastInteractionAtMs: Long = 0L
    private var finishOnBackKeyUp: Boolean = false
    internal var holdPrevSpeed: Float = 1.0f
    internal var holdPrevPlayWhenReady: Boolean = false
    internal var holdScrubPreviewPosMs: Long? = null
    internal var loadJob: kotlinx.coroutines.Job? = null
    internal var lastEndedActionAtMs: Long = 0L
    internal var playbackUncaughtHandler: CoroutineExceptionHandler? = null
    internal var actionLiked: Boolean = false
    internal var actionCoinCount: Int = 0
    internal var actionFavored: Boolean = false
    internal var likeActionJob: kotlinx.coroutines.Job? = null
    internal var coinActionJob: kotlinx.coroutines.Job? = null
    internal var favDialogJob: kotlinx.coroutines.Job? = null
    internal var favApplyJob: kotlinx.coroutines.Job? = null
    internal var socialStateFetchJob: kotlinx.coroutines.Job? = null
    internal var socialStateFetchToken: Int = 0

    internal var commentSort: Int = COMMENT_SORT_HOT
    internal var commentsFetchJob: kotlinx.coroutines.Job? = null
    internal var commentsFetchToken: Int = 0
    internal var commentsPage: Int = 1
    internal var commentsTotalCount: Int = -1
    internal var commentsEndReached: Boolean = false
    internal val commentsItems: ArrayList<PlayerCommentsAdapter.Item> = ArrayList()

    internal var commentThreadRootRpid: Long = 0L
    internal var commentThreadReturnFocusRpid: Long = 0L
    internal var commentThreadFetchJob: kotlinx.coroutines.Job? = null
    internal var commentThreadFetchToken: Int = 0
    internal var commentThreadPage: Int = 1
    internal var commentThreadTotalCount: Int = -1
    internal var commentThreadEndReached: Boolean = false
    internal val commentThreadItems: ArrayList<PlayerCommentsAdapter.Item> = ArrayList()

    internal var commentsDpadController: DpadGridController? = null
    internal var commentThreadDpadController: DpadGridController? = null

    private val doubleBackToExit by lazy {
        DoubleBackToExitHandler(context = this, windowMs = BACK_DOUBLE_PRESS_WINDOW_MS) {
            if (osdMode != OsdMode.Hidden) setControlsVisible(false)
        }
    }

    internal var smartSeekDirection: Int = 0
    internal var smartSeekStreak: Int = 0
    internal var smartSeekLastAtMs: Long = 0L
    internal var smartSeekTotalMs: Long = 0L
    private var tapSeekActiveDirection: Int = 0
    private var tapSeekActiveUntilMs: Long = 0L
    internal var keySeekHoldDetectJob: kotlinx.coroutines.Job? = null
    internal var keySeekPendingKeyCode: Int = 0
    internal var keySeekPendingDirection: Int = 0
    internal var riskControlBypassHintShown: Boolean = false
    internal var seekOsdToken: Long = 0L
    internal var transientSeekOsdVisible: Boolean = false
    internal var bottomBarFullConstraints: ConstraintSet? = null
    internal var bottomBarSeekConstraints: ConstraintSet? = null
    private var fixedAutoScale: Float? = null
    private var fixedAutoScaleWindowWidth: Int = -1
    private var fixedAutoScaleWindowHeight: Int = -1

    internal enum class OsdMode {
        Hidden,
        Full,
        SeekTransient,
    }

    internal var osdMode: OsdMode = OsdMode.Hidden

    internal var currentBvid: String = ""
    internal var currentCid: Long = -1L
    internal var currentEpId: Long? = null
    internal var currentAid: Long? = null
    internal var currentUpMid: Long = 0L
    internal var currentUpName: String? = null
    internal var currentUpAvatar: String? = null

    internal var playlistToken: String? = null
    internal var playlistSource: String? = null
    internal var playlistItems: List<PlayerPlaylistItem> = emptyList()
    internal var playlistIndex: Int = -1
    internal var playlistUgcSeasonId: Long? = null
    internal var playlistUgcSeasonTitle: String? = null
    internal lateinit var session: PlayerSessionSettings

    internal data class RelatedVideosCache(
        val bvid: String,
        val items: List<VideoCard>,
    )

    internal var relatedVideosCache: RelatedVideosCache? = null
    internal var relatedVideosFetchJob: kotlinx.coroutines.Job? = null
    internal var relatedVideosFetchToken: Int = 0
    internal var subtitleAvailabilityKnown: Boolean = false
    internal var subtitleAvailable: Boolean = false
    internal var subtitleConfig: MediaItem.SubtitleConfiguration? = null
    internal var subtitleItems: List<SubtitleItem> = emptyList()
    internal var lastAvailableQns: List<Int> = emptyList()
    internal var lastAvailableAudioIds: List<Int> = emptyList()
    private var danmakuSegmentSizeMs: Int = DANMAKU_DEFAULT_SEGMENT_MS
    private var danmakuSegmentTotal: Int = 0
    internal var danmakuShield: DanmakuShield? = null
    internal val danmakuLoadedSegments = LinkedHashSet<Int>()
    private val danmakuLoadingSegments = HashSet<Int>()
    internal val danmakuSegmentItems = LinkedHashMap<Int, List<blbl.cat3399.core.model.Danmaku>>()
    private var danmakuLoadJob: kotlinx.coroutines.Job? = null
    private var danmakuLoadGeneration: Int = 0
    private var lastDanmakuPrefetchAtMs: Long = 0L
    internal var playbackConstraints: PlaybackConstraints = PlaybackConstraints()
    internal var decodeFallbackAttempted: Boolean = false
    internal var lastPickedDash: Playable.Dash? = null
    internal var autoResumeToken: Int = 0
    internal var autoResumeCancelledByUser: Boolean = false
    internal var autoSkipToken: Int = 0
    internal var autoSkipSegments: List<SkipSegment> = emptyList()
    internal val autoSkipHandledSegmentIds = HashSet<String>()
    internal var autoSkipPending: PendingAutoSkip? = null
    internal var autoSkipMarkersDirty: Boolean = true
    internal var autoSkipMarkersDurationMs: Long = -1L
    internal var autoSkipMarkersShown: Boolean = false
    internal var reportToken: Int = 0
    internal var lastReportAtMs: Long = 0L
    internal var lastReportedProgressSec: Long = -1L
    internal var currentViewDurationMs: Long? = null
    private var exitCleanupRequested: Boolean = false
    private var exitCleanupReason: String? = null
    private var exitTraceStartAtMs: Long = 0L
    private var exitTraceStartTrigger: String? = null
    @Volatile
    private var exitTracePauseAtMs: Long = 0L

    @Volatile
    private var exitTraceStopAtMs: Long = 0L

    @Volatile
    private var exitTraceDestroyAtMs: Long = 0L

    private var exitTraceStallWatchJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var exitTraceStallSampled: Boolean = false
    private var exitTraceNavCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var exitTraceNavTarget: WeakReference<Activity>? = null
    @Volatile
    private var exitTraceNavTargetResumedLogged: Boolean = false

    @Volatile
    private var exitTraceNavTargetFirstPreDrawLogged: Boolean = false
    private var decoderReleaseRequestedOnStopReason: String? = null
    private var resumeAfterDecoderRelease: Boolean = false
    private var resumeAfterDecoderReleasePositionMs: Long = 0L
    private var resumeExpiredUrlReloadArmed: Boolean = false
    private var resumeExpiredUrlReloadAttempted: Boolean = false

    private fun exitTraceStart(trigger: String) {
        if (exitTraceStartAtMs != 0L) return
        val now = SystemClock.elapsedRealtime()
        exitTraceStartAtMs = now
        exitTraceStartTrigger = trigger
        startExitStallWatcherIfNeeded()
        registerExitNavCallbacksIfNeeded()
        AppLog.i(
            "Player",
            "$EXIT_TRACE_PREFIX dt=+0ms inst=${System.identityHashCode(this)} start=$trigger " +
                "bvid=${currentBvid.takeLast(8)} aid=${currentAid ?: -1} cid=$currentCid thread=${Thread.currentThread().name}",
        )
    }

    private fun exitTraceLog(stage: String, extra: String = "") {
        val now = SystemClock.elapsedRealtime()
        val dtText =
            if (exitTraceStartAtMs > 0L) {
                "dt=+${now - exitTraceStartAtMs}ms"
            } else {
                "dt=?"
            }
        val suffix = if (extra.isBlank()) "" else " $extra"
        AppLog.i(
            "Player",
            "$EXIT_TRACE_PREFIX $dtText inst=${System.identityHashCode(this)} " +
                "bvid=${currentBvid.takeLast(8)} aid=${currentAid ?: -1} cid=$currentCid $stage$suffix",
        )
    }

    private fun startExitStallWatcherIfNeeded() {
        if (exitTraceStallWatchJob != null) return
        if (exitTraceStartAtMs <= 0L) return
        exitTraceStallWatchJob =
            lifecycleScope.launch(Dispatchers.Default) {
                // Only sample when exit is "unexpectedly" slow. Use a generous threshold so we don't
                // disturb normal exits.
                val thresholdMs = 600L
                delay(thresholdMs)
                if (exitTraceStartAtMs <= 0L) return@launch
                if (exitTraceStopAtMs != 0L) return@launch
                if (exitTraceDestroyAtMs != 0L) return@launch
                if (exitTraceStallSampled) return@launch
                exitTraceStallSampled = true

                val now = SystemClock.elapsedRealtime()
                val dt = (now - exitTraceStartAtMs).coerceAtLeast(0L)
                val phase =
                    when {
                        exitTracePauseAtMs == 0L -> "wait_onPause"
                        exitTraceStopAtMs == 0L -> "wait_onStop"
                        else -> "wait_unknown"
                    }
                val main = Looper.getMainLooper().thread
                val state = main.state.toString()
                val top = captureThreadStackTop(main, maxFrames = 14)
                exitTraceLog(
                    "stall:$phase",
                    "waited=${dt}ms start=${exitTraceStartTrigger.orEmpty()} mainState=$state top=$top",
                )
            }
    }

    private fun captureThreadStackTop(thread: Thread, maxFrames: Int): String {
        val frames =
            runCatching { thread.stackTrace.toList() }
                .getOrDefault(emptyList())
                .asSequence()
                // Skip the top internal frames when possible to show real work.
                .filterNot { f ->
                    val c = f.className
                    c.startsWith("java.lang.Thread") ||
                        c.startsWith("dalvik.system.VMStack") ||
                        c.startsWith("kotlinx.coroutines")
                }
                .take(maxFrames.coerceAtLeast(1))
                .toList()

        if (frames.isEmpty()) return "(empty)"
        // Keep the string compact to stay within logcat line limits.
        return frames.joinToString(" <- ") { f ->
            val cls = f.className.substringAfterLast('.')
            "$cls.${f.methodName}:${f.lineNumber}"
        }
    }

    private fun registerExitNavCallbacksIfNeeded() {
        if (exitTraceNavCallbacks != null) return
        if (exitTraceStartAtMs <= 0L) return

        val cb =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    if (exitTraceStartAtMs <= 0L) return
                    if (activity === this@PlayerActivity) return
                    if (exitTraceNavTargetResumedLogged) return
                    exitTraceNavTargetResumedLogged = true
                    exitTraceNavTarget = WeakReference(activity)

                    exitTraceLog(
                        "nav:target:resumed",
                        "activity=${activity.javaClass.simpleName} targetInst=${System.identityHashCode(activity)}",
                    )

                    // Log the first visible draw pass of the target activity. This helps separate:
                    // - time spent before the target can even draw (renderer/GPU), vs
                    // - time spent inside target layout/measure/draw work.
                    val decor = activity.window?.decorView ?: return
                    val observer = decor.viewTreeObserver
                    observer.addOnPreDrawListener(
                        object : android.view.ViewTreeObserver.OnPreDrawListener {
                            override fun onPreDraw(): Boolean {
                                if (!decor.viewTreeObserver.isAlive) return true
                                decor.viewTreeObserver.removeOnPreDrawListener(this)
                                if (exitTraceStartAtMs <= 0L) return true
                                if (exitTraceNavTargetFirstPreDrawLogged) return true
                                exitTraceNavTargetFirstPreDrawLogged = true
                                exitTraceLog(
                                    "nav:target:first_preDraw",
                                    "activity=${activity.javaClass.simpleName} targetInst=${System.identityHashCode(activity)}",
                                )
                                unregisterExitNavCallbacks(reason = "target_first_preDraw")
                                return true
                            }
                        },
                    )
                }
            }

        exitTraceNavCallbacks = cb
        runCatching { application.registerActivityLifecycleCallbacks(cb) }
            .onSuccess { exitTraceLog("nav:callbacks:register") }
            .onFailure { exitTraceNavCallbacks = null }
    }

    private fun unregisterExitNavCallbacks(reason: String) {
        val cb = exitTraceNavCallbacks ?: return
        exitTraceNavCallbacks = null
        runCatching { application.unregisterActivityLifecycleCallbacks(cb) }
        exitTraceLog("nav:callbacks:unregister", "reason=$reason")
    }

    internal class PlaybackTrace(private val id: String) {
        private val startMs = SystemClock.elapsedRealtime()
        private var lastMs = startMs

        fun log(stage: String, extra: String = "") {
            val now = SystemClock.elapsedRealtime()
            val total = now - startMs
            val delta = now - lastMs
            lastMs = now
            val suffix = if (extra.isBlank()) "" else " $extra"
            // Keep tag consistent with existing Player logs; AppLog already prefixes with "BLBL/".
            AppLog.i("Player", "traceId=$id +${total}ms (+${delta}ms) $stage$suffix")
        }
    }

    internal var trace: PlaybackTrace? = null
    internal var traceFirstFrameLogged: Boolean = false

    private fun requestDecoderReleaseOnStop(reason: String) {
        if (reason.isBlank()) return
        decoderReleaseRequestedOnStopReason = reason
        trace?.log("exo:releaseOnStop:request", "reason=$reason")
    }

    private fun releaseDecoderNowForBackground(reason: String) {
        val exo = player ?: return
        if (exitCleanupRequested || isFinishing || isDestroyed) return
        val pos = exo.currentPosition.coerceAtLeast(0L)
        trace?.log("exo:releaseOnStop:do", "reason=$reason pos=${pos}ms")

        // Stop progress reporting before stopping the player (stop() resets currentPosition).
        stopReportProgressLoop(flush = false, reason = "nav_$reason")
        enqueueExitProgressReport(reason = "nav_$reason")

        resumeAfterDecoderRelease = true
        resumeAfterDecoderReleasePositionMs = pos

        // Detach the surface early so codecs can be released immediately.
        if (::binding.isInitialized) {
            binding.playerView.player = null
        }
        exo.playWhenReady = false
        exo.stop()
    }

    private fun requestExitCleanup(reason: String) {
        if (reason.isNotBlank()) exitTraceStart("cleanup:$reason")
        if (exitCleanupRequested) return
        exitCleanupRequested = true
        exitCleanupReason = reason
        trace?.log("activity:exit:request", "reason=$reason")
        exitTraceLog(
            "exitCleanup:request",
            "reason=$reason thread=${Thread.currentThread().name} bvid=${currentBvid.takeLast(8)} aid=${currentAid ?: -1} cid=$currentCid",
        )

        val t0 = SystemClock.elapsedRealtime()
        val tPause = SystemClock.elapsedRealtime()
        player?.pause()
        val pauseCostMs = SystemClock.elapsedRealtime() - tPause
        exitTraceLog("exitCleanup:pause", "cost=${pauseCostMs}ms")
        exitTraceLog("exitCleanup:detachView", "deferred=1")

        val tStop = SystemClock.elapsedRealtime()
        stopReportProgressLoop(flush = false, reason = reason)
        val stopCostMs = SystemClock.elapsedRealtime() - tStop
        exitTraceLog("exitCleanup:stopReportLoop", "cost=${stopCostMs}ms")

        val tEnq = SystemClock.elapsedRealtime()
        enqueueExitProgressReport(reason = reason)
        val enqCostMs = SystemClock.elapsedRealtime() - tEnq
        exitTraceLog("exitCleanup:enqueueExitReport", "cost=${enqCostMs}ms")

        val totalCostMs = SystemClock.elapsedRealtime() - t0
        exitTraceLog("exitCleanup:done", "totalCost=${totalCostMs}ms")
    }

    private fun enqueueExitProgressReport(reason: String) {
        val trace = trace
        val exo = player ?: return
        val progressSec = (exo.currentPosition.coerceAtLeast(0L) / 1000L)

        val tChecks0 = SystemClock.elapsedRealtime()
        val shouldHistory = shouldReportHistoryNow()
        val tChecks1 = SystemClock.elapsedRealtime()
        val shouldHeartbeat = shouldReportPgcHeartbeatNow()
        val tChecks2 = SystemClock.elapsedRealtime()
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog(
                "exitReport:check",
                "reason=$reason history=${if (shouldHistory) 1 else 0} heartbeat=${if (shouldHeartbeat) 1 else 0} " +
                    "costHistory=${tChecks1 - tChecks0}ms costHeartbeat=${tChecks2 - tChecks1}ms total=${tChecks2 - tChecks0}ms",
            )
        }
        if (!shouldHistory && !shouldHeartbeat) return

        val cid = currentCid
        val aid = currentAid
        val epId = currentEpId
        val seasonId = parseSeasonIdFromPlaylistSource()

        if (shouldHistory && aid != null) trace?.log("report:history:enqueue", "sec=$progressSec reason=$reason")
        if (shouldHeartbeat) trace?.log("report:heartbeat:enqueue", "sec=$progressSec reason=$reason")
        BlblApp.launchIo {
            if (shouldHistory && aid != null) {
                runCatching {
                    BiliApi.historyReport(aid = aid, cid = cid, progressSec = progressSec, platform = "android")
                }.onSuccess {
                    trace?.log("report:history", "ok=1 sec=$progressSec reason=$reason")
                }.onFailure {
                    trace?.log("report:history", "ok=0 sec=$progressSec reason=$reason")
                }
            }
            if (shouldHeartbeat) {
                runCatching {
                    BiliApi.webHeartbeat(
                        aid = aid,
                        bvid = currentBvid,
                        cid = cid,
                        epId = epId,
                        seasonId = seasonId,
                        playedTimeSec = progressSec,
                        type = 4,
                        playType = 0,
                    )
                }.onSuccess {
                    trace?.log("report:heartbeat", "ok=1 sec=$progressSec reason=$reason")
                }.onFailure {
                    trace?.log("report:heartbeat", "ok=0 sec=$progressSec reason=$reason")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerOsdSizing.applyTheme(this)
        ActivityStackLimiter.register(group = ACTIVITY_STACK_GROUP, activity = this, maxDepth = ACTIVITY_STACK_MAX_DEPTH)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        recomputeFixedAutoScaleIfWindowChanged(force = false)
        PlayerUiMode.applyVideo(this, binding, fixedAutoScale = fixedAutoScale)
        binding.topBar.setBackgroundResource(R.drawable.bg_player_top_scrim_strong)
        ensureBottomBarConstraintSets()

        // Re-apply only when real window size changes; keep panel open/close from affecting OSD scale.
        binding.playerView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (exitCleanupRequested || isFinishing) return@addOnLayoutChangeListener
            val w = r - l
            val h = b - t
            val ow = or - ol
            val oh = ob - ot
            if (w <= 0 || h <= 0 || (w == ow && h == oh)) return@addOnLayoutChangeListener
            if (recomputeFixedAutoScaleIfWindowChanged(force = false)) {
                PlayerUiMode.applyVideo(this, binding, fixedAutoScale = fixedAutoScale)
            }
        }

        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
        binding.progressPersistentBottom.max = SEEK_MAX
        updatePersistentBottomProgressBarVisibility()
        binding.tvSeekHint.visibility = View.GONE
        binding.btnBack.setOnClickListener { finish() }
        binding.tvOnline.text = "-人正在观看"
        binding.llTitleMeta.visibility = View.VISIBLE
        binding.tvViewCount.text = "-"
        binding.tvPubdate.text = ""
        binding.tvPubdate.visibility = View.GONE

        val bvid = intent.getStringExtra(EXTRA_BVID).orEmpty()
        val cidExtra = intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 }
        val epIdExtra = intent.getLongExtra(EXTRA_EP_ID, -1L).takeIf { it > 0 }
        val aidExtra = intent.getLongExtra(EXTRA_AID, -1L).takeIf { it > 0 }
        playlistToken = intent.getStringExtra(EXTRA_PLAYLIST_TOKEN)?.trim()?.takeIf { it.isNotBlank() }
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1)
        val playlistIndexExtra = playlistIndex
        playlistToken?.let { token ->
            val p = PlayerPlaylistStore.get(token)
            if (p != null && p.items.isNotEmpty()) {
                playlistSource = p.source
                playlistItems = p.items
                val idx = playlistIndex.takeIf { it in playlistItems.indices } ?: p.index
                playlistIndex = idx.coerceIn(0, playlistItems.lastIndex)
                PlayerPlaylistStore.updateIndex(token, playlistIndex)
            }
        }
        if (epIdExtra != null) {
            AppLog.i(
                "Player",
                "CONTINUE_DEBUG intentPgc bvid=${bvid.takeLast(8)} cidExtra=${cidExtra ?: -1L} epIdExtra=$epIdExtra " +
                    "aidExtra=${aidExtra ?: -1L} token=${playlistToken?.takeLast(6).orEmpty()} " +
                    "idxExtra=$playlistIndexExtra idxResolved=$playlistIndex src=${playlistSource.orEmpty()}",
            )
        }
        trace =
            PlaybackTrace(
                buildString {
                    val token = bvid.takeLast(8).ifBlank { aidExtra?.toString(16) ?: "unknown" }
                    append(token)
                    append('-')
                    append((System.currentTimeMillis() and 0xFFFF).toString(16))
                },
            ).also { it.log("activity:onCreate") }
        currentBvid = bvid
        currentEpId = epIdExtra
        currentAid = aidExtra
        if (currentBvid.isBlank() && currentAid == null) {
            AppToast.show(this, "缺少 bvid/aid")
            finish()
            return
        }

        val prefs = BiliClient.prefs
        session = PlayerSessionSettings(
            playbackSpeed = prefs.playerSpeed,
            preferCodec = prefs.playerPreferredCodec,
            preferAudioId = prefs.playerPreferredAudioId,
            preferredQn = prefs.playerPreferredQn,
            targetQn = 0,
            playbackModeOverride = null,
            subtitleEnabled = prefs.subtitleEnabledDefault,
            subtitleLangOverride = null,
            subtitleTextSizeSp = prefs.subtitleTextSizeSp,
            danmaku = DanmakuSessionSettings(
                enabled = prefs.danmakuEnabled,
                opacity = prefs.danmakuOpacity,
                textSizeSp = prefs.danmakuTextSizeSp,
                speedLevel = prefs.danmakuSpeed,
                area = prefs.danmakuArea,
            ),
            debugEnabled = prefs.playerDebugEnabled,
        )

        val exo =
            ExoPlayer.Builder(this)
                .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                .build()
        player = exo
        binding.playerView.player = exo
        trace?.log("exo:created")
        binding.danmakuView.setPositionProvider { exo.currentPosition }
        binding.danmakuView.setIsPlayingProvider { exo.isPlaying }
        binding.danmakuView.setPlaybackSpeedProvider { exo.playbackParameters.speed }
        binding.danmakuView.setConfigProvider { session.danmaku.toConfig() }
        configureSubtitleView()
        exo.setPlaybackSpeed(session.playbackSpeed)
        applyPlaybackMode(exo)
        // Subtitle enabled state follows session (default from global prefs).
        applySubtitleEnabled(exo)
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("Player", "onPlayerError", error)
                val httpCode = findHttpResponseCode(error)
                trace?.log("exo:error", "type=${error.errorCodeName} http=${httpCode ?: -1}")
                if (maybeReloadExpiredUrlAfterResume(error, httpCode)) return
                val picked = lastPickedDash
                if (
                    picked != null &&
                    !decodeFallbackAttempted &&
                    picked.shouldAttemptDolbyFallback() &&
                    isLikelyCodecUnsupported(error)
                ) {
                    val nextConstraints = nextPlaybackConstraintsForDolbyFallback(picked)
                    if (nextConstraints != null) {
                        decodeFallbackAttempted = true
                        playbackConstraints = nextConstraints
                        AppToast.show(this@PlayerActivity, "杜比/无损解码失败，尝试回退到普通轨道…")
                        reloadStream(keepPosition = true, resetConstraints = false)
                        return
                    }
                }
                AppToast.show(this@PlayerActivity, "播放失败：${error.errorCodeName}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                restartAutoHideTimer()
                // DanmakuView stops its own vsync loop when playback is paused; kick it on state changes.
                binding.danmakuView.invalidate()
                if (isPlaying) {
                    startReportProgressLoop()
                } else {
                    // Avoid flushing on every pause (and also avoid duplicate flushes when `onStop()` calls `pause()`).
                    stopReportProgressLoop(flush = false, reason = "pause")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgressUi()
                val state =
                    when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> playbackState.toString()
                    }
                trace?.log("exo:state", "state=$state pos=${exo.currentPosition}ms")
                if (playbackState == Player.STATE_BUFFERING && debug.lastPlaybackState != Player.STATE_BUFFERING && exo.playWhenReady) {
                    debug.rebufferCount++
                }
                debug.lastPlaybackState = playbackState
                if (playbackState == Player.STATE_READY && resumeExpiredUrlReloadArmed) {
                    resumeExpiredUrlReloadArmed = false
                    trace?.log("exo:resumeReload:disarm", "state=READY")
                }
                if (playbackState == Player.STATE_ENDED) {
                    stopReportProgressLoop(flush = true, reason = "ended")
                    handlePlaybackEnded(exo)
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Rely on ExoPlayer discontinuity callbacks to re-sync danmaku.
                // Avoid doing "big jump" heuristics inside DanmakuView (which can be triggered by UI jank).
                binding.danmakuView.notifySeek(newPosition.positionMs)
                requestDanmakuSegmentsForPosition(newPosition.positionMs, immediate = true)
            }

        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                debug.videoDecoderName = decoderName
            }

            override fun onVideoInputFormatChanged(eventTime: EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                debug.videoInputWidth = format.width.takeIf { it > 0 }
                debug.videoInputHeight = format.height.takeIf { it > 0 }
                debug.videoInputFps = format.frameRate.takeIf { it > 0f }
            }

            override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
                debug.droppedFramesTotal += droppedFrames.toLong().coerceAtLeast(0L)
            }

            override fun onVideoFrameProcessingOffset(eventTime: EventTime, totalProcessingOffsetUs: Long, frameCount: Int) {
                val now = eventTime.realtimeMs
                val last = debug.renderFpsLastAtMs
                debug.renderFpsLastAtMs = now
                if (last == null) return
                val deltaMs = now - last
                if (deltaMs <= 0L || deltaMs > 60_000L) return
                val frames = frameCount.coerceAtLeast(0)
                if (frames == 0) return
                debug.renderFps = (frames * 1000f) / deltaMs.toFloat()
            }

            override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
                if (traceFirstFrameLogged) return
                traceFirstFrameLogged = true
                trace?.log("exo:firstFrame", "pos=${exo.currentPosition}ms")
            }
        })

        val settingsAdapter = PlayerSettingsAdapter { item -> handleSettingsItemClick(item) }
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
                                if (pos == 0) return@setOnKeyListener true
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (binding.recyclerSettings.adapter?.itemCount ?: 0) - 1
                                if (pos == last) return@setOnKeyListener true
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_RIGHT -> true

                            else -> false
                        }
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )
        refreshSettings(settingsAdapter)
        updateDebugOverlay()
        initSidePanels()

        initControls(exo)
        applyActionButtonsVisibility()

        val uncaughtHandler =
            CoroutineExceptionHandler { _, t ->
                AppLog.e("Player", "uncaught", t)
                AppToast.showLong(this@PlayerActivity, "播放失败：${t.message}")
                finish()
            }
        playbackUncaughtHandler = uncaughtHandler
        startPlayback(
            bvid = bvid,
            cidExtra = cidExtra,
            epIdExtra = epIdExtra,
            aidExtra = aidExtra,
            initialTitle = playlistItems.getOrNull(playlistIndex)?.title,
        )
    }

    internal data class PlayFetchResult(
        val json: JSONObject,
        val playable: Playable,
    )

    internal fun requestOnlineWatchingText(bvid: String, cid: Long) {
        // Must not crash the player: always swallow any network/parse errors.
        binding.tvOnline.text = "-人正在观看"
        lifecycleScope.launch {
            val countText =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val json = BiliApi.onlineTotal(bvid = bvid, cid = cid)
                        if (json.optInt("code", 0) != 0) return@runCatching "-"
                        val data = json.optJSONObject("data") ?: return@runCatching "-"
                        val showSwitch = data.optJSONObject("show_switch") ?: JSONObject()
                        val totalEnabled = showSwitch.optBoolean("total", true)
                        val total = data.optString("total", "")
                        val countEnabled = showSwitch.optBoolean("count", true)
                        val count = data.optString("count", "")
                        when {
                            totalEnabled && total.isNotBlank() -> total
                            countEnabled && count.isNotBlank() -> count
                            else -> "-"
                        }
                    }.getOrDefault("-")
                }
            binding.tvOnline.text = "${countText}人正在观看"
        }
    }

    private fun extractVVoucher(json: JSONObject): String? {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: return null
        return data.optString("v_voucher", "").trim().takeIf { it.isNotBlank() }
    }

    private fun recordVVoucher(vVoucher: String) {
        val prefs = BiliClient.prefs
        prefs.gaiaVgateVVoucher = vVoucher
        prefs.gaiaVgateVVoucherSavedAtMs = System.currentTimeMillis()
    }

    private suspend fun requestPlayJson(
        bvid: String,
        aid: Long?,
        cid: Long,
        epId: Long?,
        qn: Int,
        fnval: Int,
        tryLook: Boolean,
    ): JSONObject {
        val safeBvid = bvid.trim()
        val safeAid = aid?.takeIf { it > 0 }
        val safeEpId = epId?.takeIf { it > 0 }

        if (safeEpId != null) {
            return if (tryLook) {
                BiliApi.pgcPlayUrlTryLook(
                    bvid = safeBvid.takeIf { it.isNotBlank() },
                    aid = safeAid,
                    cid = cid,
                    epId = safeEpId,
                    qn = qn,
                    fnval = fnval,
                )
            } else {
                BiliApi.pgcPlayUrl(
                    bvid = safeBvid.takeIf { it.isNotBlank() },
                    aid = safeAid,
                    cid = cid,
                    epId = safeEpId,
                    qn = qn,
                    fnval = fnval,
                )
            }
        }

        if (safeBvid.isBlank()) error("bvid required")
        return if (tryLook) {
            BiliApi.playUrlDashTryLook(bvid = safeBvid, cid = cid, qn = qn, fnval = fnval)
        } else {
            BiliApi.playUrlDash(bvid = safeBvid, cid = cid, qn = qn, fnval = fnval)
        }
    }

    private fun trackHasAnyUrl(obj: JSONObject): Boolean {
        val base =
            obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))
                .trim()
        if (base.isNotBlank()) return true
        val backup = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
        for (i in 0 until backup.length()) {
            val u = backup.optString(i, "").trim()
            if (u.isNotBlank()) return true
        }
        return false
    }

    private fun hasAnyPlayableUrl(json: JSONObject): Boolean {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: return false
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()
            val dolbyAudios = dash.optJSONObject("dolby")?.optJSONArray("audio") ?: JSONArray()
            val flacAudio = dash.optJSONObject("flac")?.optJSONObject("audio")
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(v)) return true
            }
            for (i in 0 until audios.length()) {
                val a = audios.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(a)) return true
            }
            for (i in 0 until dolbyAudios.length()) {
                val a = dolbyAudios.optJSONObject(i) ?: continue
                if (trackHasAnyUrl(a)) return true
            }
            if (flacAudio != null && trackHasAnyUrl(flacAudio)) return true
        }

        val durl = data.optJSONArray("durl") ?: JSONArray()
        for (i in 0 until durl.length()) {
            val obj = durl.optJSONObject(i) ?: continue
            val url = obj.optString("url", "").trim()
            if (url.isNotBlank()) return true
        }
        return false
    }

    private fun shouldAttemptTryLookFallback(playJson: JSONObject): Boolean {
        // try_look is only a risk-control fallback: only use it when we truly cannot get any playable URL.
        return !hasAnyPlayableUrl(playJson)
    }

    internal suspend fun loadPlayableWithTryLookFallback(
        bvid: String,
        aid: Long?,
        cid: Long,
        epId: Long?,
        qn: Int,
        fnval: Int,
        constraints: PlaybackConstraints,
    ): PlayFetchResult {
        val primaryJson =
            try {
                requestPlayJson(
                    bvid = bvid,
                    aid = aid,
                    cid = cid,
                    epId = epId,
                    qn = qn,
                    fnval = fnval,
                    tryLook = false,
                )
            } catch (t: Throwable) {
                val e = t as? BiliApiException
                if (e != null && isRiskControl(e)) {
                    val fallbackJson =
                        requestPlayJson(
                            bvid = bvid,
                            aid = aid,
                            cid = cid,
                            epId = epId,
                            qn = qn,
                            fnval = fnval,
                            tryLook = true,
                        )
                    fallbackJson.put("__blbl_risk_control_bypassed", true)
                    fallbackJson.put("__blbl_risk_control_code", e.apiCode)
                    fallbackJson.put("__blbl_risk_control_message", e.apiMessage)
                    val playable = pickPlayable(fallbackJson, constraints)
                    return PlayFetchResult(json = fallbackJson, playable = playable)
                }
                throw t
            }

        return try {
            val playable = pickPlayable(primaryJson, constraints)
            PlayFetchResult(json = primaryJson, playable = playable)
        } catch (t: Throwable) {
            if (!shouldAttemptTryLookFallback(primaryJson)) throw t

            extractVVoucher(primaryJson)?.let { recordVVoucher(it) }

            val fallbackJson =
                requestPlayJson(
                    bvid = bvid,
                    aid = aid,
                    cid = cid,
                    epId = epId,
                    qn = qn,
                    fnval = fnval,
                    tryLook = true,
                )
            fallbackJson.put("__blbl_risk_control_bypassed", true)
            fallbackJson.put("__blbl_risk_control_code", -352)
            fallbackJson.put("__blbl_risk_control_message", "fallback try_look after no playable stream")

            return try {
                val playable = pickPlayable(fallbackJson, constraints)
                PlayFetchResult(json = fallbackJson, playable = playable)
            } catch (t2: Throwable) {
                AppLog.w("Player", "try_look fallback failed", t2)
                throw BiliApiException(apiCode = -352, apiMessage = "风控拦截：未返回可播放地址（已尝试 try_look 兜底失败）")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("window:focus", "hasFocus=${if (hasFocus) 1 else 0}")
        }
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
    }

    override fun onResume() {
        super.onResume()
        PlayerOsdSizing.applyTheme(this)
        recomputeFixedAutoScaleIfWindowChanged(force = false)
        PlayerUiMode.applyVideo(this, binding, fixedAutoScale = fixedAutoScale)
        applyActionButtonsVisibility()
        updatePersistentBottomProgressBarVisibility()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode

        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (finishOnBackKeyUp) {
                    finishOnBackKeyUp = false
                    exitTraceStart("back:up")
                    exitTraceLog(
                        "back:up exit=1",
                        "osd=$osdMode sidePanel=${if (isSidePanelVisible()) 1 else 0} thread=${Thread.currentThread().name}",
                    )
                    requestExitCleanup(reason = "back")
                    trace?.log("activity:finish", "via=back")
                    finish()
                }
                return true
            }

            if (isSeekKey(keyCode)) {
                // Always stop any pending "tap vs hold" decision when the key is released.
                // Capture before clearing so a tap can commit the step seek.
                val pendingDir = keySeekPendingDirection.takeIf { keySeekPendingKeyCode == keyCode } ?: 0
                if (keySeekPendingKeyCode == keyCode) clearKeySeekPending()

                if (holdSeekJob != null) {
                    stopHoldSeek()
                    return true
                }

                if (pendingDir != 0) {
                    showSeekOsd()
                    smartSeek(direction = pendingDir, showControls = false, hintKind = SeekHintKind.Step)
                    return true
                }
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
                if (isSidePanelVisible()) {
                    if (!isSettingsPanelVisible() && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)) {
                        showSettingsPanel()
                    }
                    return true
                }
                if (
                    osdMode == OsdMode.Hidden &&
                    (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)
                ) {
                    showSettingsPanel()
                    return true
                }
                setControlsVisible(true)
                focusFirstControl()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                val cancelledAutoResume = autoResumeHintVisible
                val cancelledAutoSkip = autoSkipHintVisible || autoSkipPending != null
                if (cancelledAutoResume) cancelPendingAutoResume(reason = "back")
                if (cancelledAutoSkip) cancelPendingAutoSkip(reason = "back", markIgnored = true)
                if (cancelledAutoResume || cancelledAutoSkip) {
                    finishOnBackKeyUp = false
                    exitTraceLog(
                        "back:down action=cancel_hint",
                        "resume=${if (cancelledAutoResume) 1 else 0} skip=${if (cancelledAutoSkip) 1 else 0} osd=$osdMode sidePanel=${if (isSidePanelVisible()) 1 else 0}",
                    )
                    return true
                }
                finishOnBackKeyUp = false
                if (isSidePanelVisible()) {
                    exitTraceLog(
                        "back:down action=side_panel",
                        "settings=${if (isSettingsPanelVisible()) 1 else 0} comments=${if (isCommentsPanelVisible()) 1 else 0} thread=${if (isCommentThreadVisible()) 1 else 0}",
                    )
                    return onSidePanelBackPressed()
                }
                if (osdMode != OsdMode.Hidden) {
                    exitTraceLog("back:down action=hide_osd", "osd=$osdMode")
                    setControlsVisible(false)
                    return true
                }
                val enabled = BiliClient.prefs.playerDoubleBackToExit
                val willExit = doubleBackToExit.shouldExit(enabled = enabled)
                finishOnBackKeyUp = willExit
                exitTraceLog(
                    "back:down action=exit_check",
                    "doubleBack=${if (enabled) 1 else 0} willExitOnUp=${if (willExit) 1 else 0}",
                )
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isSidePanelVisible()) return super.dispatchKeyEvent(event)
                // TV-style shortcut: when OSD is hidden, UP directly opens the playlist (video list)
                // instead of first bringing up the OSD.
                if (osdMode == OsdMode.Hidden) {
                    val hasPlaylist = playlistItems.isNotEmpty() && playlistIndex in playlistItems.indices
                    if (hasPlaylist) {
                        showPlaylistDialog()
                        return true
                    }
                }
                setControlsVisible(true)
                if (!binding.seekProgress.isFocused) {
                    focusSeekBar()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isSidePanelVisible()) return super.dispatchKeyEvent(event)
                if (binding.seekProgress.isFocused) {
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (osdMode == OsdMode.Hidden) {
                    setControlsVisible(true)
                    focusDownKeyOsdTargetControl()
                    return true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> {
                if (!isSidePanelVisible() && !hasControlsFocus()) {
                    if (osdMode == OsdMode.Hidden) player?.pause()
                    setControlsVisible(true)
                    focusFirstControl()
                    return true
                }
                if (osdMode == OsdMode.Hidden && !isSidePanelVisible()) {
                    player?.pause()
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

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playNextByPlaybackMode(userInitiated = true)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playPrev(userInitiated = true)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && isSidePanelVisible()) {
                    val panelRoot =
                        when {
                            isCommentsPanelVisible() -> binding.commentsPanel
                            isSettingsPanelVisible() -> binding.settingsPanel
                            else -> null
                        }
                    val focusedView = currentFocus
                    val hasLeftFocusInPanel =
                        if (panelRoot != null && focusedView != null) {
                            FocusFinder.getInstance()
                                .findNextFocus(panelRoot, focusedView, View.FOCUS_LEFT) != null
                        } else {
                            false
                        }
                    // Only treat LEFT as "back" when the focus cannot move further left inside the panel.
                    if (!hasLeftFocusInPanel) return onSidePanelBackPressed()
                    return super.dispatchKeyEvent(event)
                }
                if (isSidePanelVisible()) return super.dispatchKeyEvent(event)
                if (osdMode == OsdMode.Full && binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (osdMode == OsdMode.Full && (binding.topBar.hasFocus() || binding.bottomBar.hasFocus())) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    showSeekOsd()
                    clearKeySeekPending()
                    // Long-press LEFT: always do preview-scrub rewind.
                    startHoldScrub(direction = -1, showControls = false)
                    return true
                }

                showSeekOsd()
                beginKeySeekPending(keyCode = keyCode, direction = -1, showControls = false)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> {
                if (isSidePanelVisible()) return super.dispatchKeyEvent(event)
                if (osdMode == OsdMode.Full && binding.seekProgress.isFocused) return super.dispatchKeyEvent(event)
                if (osdMode == OsdMode.Full && (binding.topBar.hasFocus() || binding.bottomBar.hasFocus())) return super.dispatchKeyEvent(event)

                if (event.repeatCount > 0) {
                    showSeekOsd()
                    clearKeySeekPending()
                    startHoldSeek(direction = +1, showControls = false)
                    return true
                }

                showSeekOsd()
                beginKeySeekPending(keyCode = keyCode, direction = +1, showControls = false)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        exitTraceStopAtMs = SystemClock.elapsedRealtime()
        if (exitCleanupRequested || isFinishing) {
            exitTraceStart("onStop")
            exitTraceLog(
                "lifecycle:onStop",
                "isFinishing=${if (isFinishing) 1 else 0} changingConfig=${if (isChangingConfigurations) 1 else 0} cleanupReason=${exitCleanupReason.orEmpty()}",
            )
        }
        trace?.log("activity:onStop")
        val releaseReason = decoderReleaseRequestedOnStopReason
        decoderReleaseRequestedOnStopReason = null
        super.onStop()
        player?.pause()
        if ((exitCleanupRequested || isFinishing) && !isChangingConfigurations) {
            val tDetach = SystemClock.elapsedRealtime()
            if (::binding.isInitialized && binding.playerView.player != null) {
                binding.playerView.player = null
            }
            val detachCostMs = SystemClock.elapsedRealtime() - tDetach
            if (exitTraceStartAtMs > 0L) {
                exitTraceLog("exitCleanup:detachView:onStop", "cost=${detachCostMs}ms")
            }
        }
        if (releaseReason != null && !isChangingConfigurations) {
            releaseDecoderNowForBackground(reason = releaseReason)
        } else {
            val flush = !isFinishing && !isChangingConfigurations && !exitCleanupRequested
            stopReportProgressLoop(flush = flush, reason = if (flush) "stop" else "stop_skip")
        }
    }

    override fun onStart() {
        super.onStart()
        val exo = player ?: return
        if (!resumeAfterDecoderRelease) return
        if (exitCleanupRequested || isFinishing || isDestroyed) return

        val pos = resumeAfterDecoderReleasePositionMs.coerceAtLeast(0L)
        resumeAfterDecoderRelease = false
        resumeAfterDecoderReleasePositionMs = 0L
        trace?.log("exo:releaseOnStop:resume", "pos=${pos}ms")

        if (::binding.isInitialized && binding.playerView.player == null) {
            binding.playerView.player = exo
        }
        resumeExpiredUrlReloadArmed = true
        resumeExpiredUrlReloadAttempted = false
        exo.playWhenReady = false
        exo.prepare()
        if (pos > 0L) exo.seekTo(pos)
    }

    override fun onPause() {
        exitTracePauseAtMs = SystemClock.elapsedRealtime()
        if (exitCleanupRequested || isFinishing || exitTraceStartAtMs > 0L) {
            exitTraceStart("onPause")
            exitTraceLog(
                "lifecycle:onPause",
                "isFinishing=${if (isFinishing) 1 else 0} changingConfig=${if (isChangingConfigurations) 1 else 0} cleanupReason=${exitCleanupReason.orEmpty()}",
            )
        }
        trace?.log("activity:onPause")
        super.onPause()
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("lifecycle:onPause:afterSuper")
        }
    }

    override fun finish() {
        exitTraceStart("finish()")
        exitTraceLog(
            "finish:call",
            "isFinishing=${if (isFinishing) 1 else 0} cleanupRequested=${if (exitCleanupRequested) 1 else 0} cleanupReason=${exitCleanupReason.orEmpty()}",
        )
        requestExitCleanup(reason = "finish")
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("finish:beforeSuper", "isFinishing=${if (isFinishing) 1 else 0}")
        }
        super.finish()
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("finish:afterSuper", "isFinishing=${if (isFinishing) 1 else 0}")
        }
        applyCloseTransitionNoAnim()
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("finish:afterTransition")
        }
    }

    override fun onDestroy() {
        val t0 = SystemClock.elapsedRealtime()
        exitTraceDestroyAtMs = t0
        if (exitCleanupRequested || isFinishing || exitTraceStartAtMs > 0L) {
            exitTraceStart("onDestroy")
            exitTraceLog(
                "lifecycle:onDestroy:start",
                "isFinishing=${if (isFinishing) 1 else 0} changingConfig=${if (isChangingConfigurations) 1 else 0} cleanupReason=${exitCleanupReason.orEmpty()} player=${if (player != null) 1 else 0}",
            )
        }
        trace?.log("activity:onDestroy:start")
        debugJob?.cancel()
        progressJob?.cancel()
        autoResumeJob?.cancel()
        autoResumeHintTimeoutJob?.cancel()
        reportProgressJob?.cancel()
        autoHideJob?.cancel()
        seekOsdHideJob?.cancel()
        holdSeekJob?.cancel()
        seekHintJob?.cancel()
        keyScrubEndJob?.cancel()
        relatedVideosFetchJob?.cancel()
        commentsFetchJob?.cancel()
        commentThreadFetchJob?.cancel()
        loadJob?.cancel()
        cancelDanmakuLoading(reason = "destroy")
        loadJob = null
        dismissAutoResumeHint()
        stopReportProgressLoop(flush = false, reason = "destroy")
        trace?.log("exo:detachView")
        binding.playerView.player = null
        val preReleaseCostMs = SystemClock.elapsedRealtime() - t0
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("onDestroy:preRelease", "cost=${preReleaseCostMs}ms")
        }
        trace?.log("exo:release:start")
        val releaseStart = SystemClock.elapsedRealtime()
        player?.release()
        val releaseCostMs = SystemClock.elapsedRealtime() - releaseStart
        trace?.log("exo:release:done")
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("exo:release:done", "cost=${releaseCostMs}ms thread=${Thread.currentThread().name}")
        }
        player = null
        playlistToken?.let(PlayerPlaylistStore::remove)
        ActivityStackLimiter.unregister(group = ACTIVITY_STACK_GROUP, activity = this)
        trace?.log("activity:onDestroy:beforeSuper")
        val totalCostMs = SystemClock.elapsedRealtime() - t0
        if (exitTraceStartAtMs > 0L) {
            exitTraceLog("lifecycle:onDestroy:beforeSuper", "totalCost=${totalCostMs}ms")
        }
        unregisterExitNavCallbacks(reason = "destroy")
        super.onDestroy()
    }

    private fun initControls(exo: ExoPlayer) {
        val detector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (isSidePanelVisible()) return true
                        val w = binding.playerView.width.toFloat()
                        if (w <= 0f) return true
                        val dir = edgeDirection(e.x, w)
                        if (dir == 0) {
                            binding.btnPlayPause.performClick()
                            return true
                        }
                        if (osdMode != OsdMode.Hidden) {
                            setControlsVisible(false)
                            return true
                        }

                        showSeekOsd()
                        smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                        tapSeekActiveDirection = dir
                        tapSeekActiveUntilMs = SystemClock.uptimeMillis() + TAP_SEEK_ACTIVE_MS
                        return true
                    }

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        if (isSidePanelVisible()) return onSidePanelBackPressed()
                        if (osdMode != OsdMode.Hidden) {
                            setControlsVisible(false)
                            return true
                        }

                        val now = SystemClock.uptimeMillis()
                        val w = binding.playerView.width.toFloat()
                        if (w > 0f && now <= tapSeekActiveUntilMs) {
                            val dir = edgeDirection(e.x, w)
                            if (dir != 0 && dir == tapSeekActiveDirection) {
                                showSeekOsd()
                                smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                                tapSeekActiveUntilMs = now + TAP_SEEK_ACTIVE_MS
                                return true
                            }
                        }

                        setControlsVisible(true)
                        return true
                    }
                },
            )
        binding.playerView.setOnTouchListener { v, event ->
            val handled = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && handled) v.performClick()
            handled
        }

        binding.btnAdvanced.setOnClickListener {
            toggleSettingsPanel()
        }

        binding.btnLike.setOnClickListener { onLikeButtonClicked() }
        binding.btnCoin.setOnClickListener { onCoinButtonClicked() }
        binding.btnFav.setOnClickListener { onFavButtonClicked() }

        binding.btnPlayPause.setOnClickListener {
            if (exo.isPlaying) exo.pause() else exo.play()
            setControlsVisible(true)
        }
        binding.btnPrev.setOnClickListener {
            playPrev(userInitiated = true)
            setControlsVisible(true)
        }
        binding.btnNext.setOnClickListener {
            playNextByPlaybackMode(userInitiated = true)
            setControlsVisible(true)
        }

        binding.btnRecommend.setOnClickListener {
            showRecommendDialog()
            setControlsVisible(true)
        }

        binding.btnPlaylist.setOnClickListener {
            showPlaylistDialog()
            setControlsVisible(true)
        }

        binding.btnDanmaku.setOnClickListener {
            session = session.copy(danmaku = session.danmaku.copy(enabled = !session.danmaku.enabled))
            binding.danmakuView.invalidate()
            updateDanmakuButton()
            setControlsVisible(true)
        }

        binding.btnComments.setOnClickListener {
            toggleCommentsPanel()
        }

        binding.btnSubtitle.setOnClickListener {
            toggleSubtitles(exo)
            setControlsVisible(true)
        }

        binding.btnUp.setOnClickListener {
            val mid = currentUpMid
            if (mid <= 0L) {
                AppToast.show(this, "未获取到 UP 主信息")
                return@setOnClickListener
            }
            // When jumping into the UP video list and potentially starting another PlayerActivity,
            // release decoder resources from the current player to avoid `ERROR_CODE_DECODER_INIT_FAILED`.
            requestDecoderReleaseOnStop(reason = "up_detail")
            startActivity(
                Intent(this, UpDetailActivity::class.java)
                    .putExtra(UpDetailActivity.EXTRA_MID, mid)
                    .apply {
                        currentUpName?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                        currentUpAvatar?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                    },
            )
            setControlsVisible(true)
        }

        binding.seekProgress.max = SEEK_MAX
        binding.seekProgress.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    cancelPendingAutoResume(reason = "user_seek")
                    cancelPendingAutoSkip(reason = "user_seek", markIgnored = true)
                    scrubbing = true
                    noteUserInteraction()
                    if (seekBar?.isPressed != true) scheduleKeyScrubEnd()

                    val duration = exo.duration.takeIf { it > 0 }
                    if (duration != null) {
                        val previewPos = (duration * progress) / SEEK_MAX
                        binding.tvTime.text = "${formatHms(previewPos)} / ${formatHms(duration)}"
                    }

                    if (binding.seekProgress.isFocused && duration != null) {
                        val seekTo = duration * progress / SEEK_MAX
                        exo.seekTo(seekTo)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    cancelPendingAutoResume(reason = "user_seek")
                    cancelPendingAutoSkip(reason = "user_seek", markIgnored = true)
                    scrubbing = true
                    keyScrubEndJob?.cancel()
                    setControlsVisible(true)
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = exo.duration.takeIf { it > 0 } ?: return
                    val progress = seekBar?.progress ?: return
                    val seekTo = duration * progress / SEEK_MAX
                    exo.seekTo(seekTo)
                    binding.tvTime.text = "${formatHms(seekTo)} / ${formatHms(duration)}"
                    requestDanmakuSegmentsForPosition(seekTo, immediate = true)
                    scrubbing = false
                    keyScrubEndJob?.cancel()
                    setControlsVisible(true)
                    lifecycleScope.launch { reportProgressOnce(force = true, reason = "user_seek_end") }
                }
            },
        )

        updatePlayPauseIcon(exo.isPlaying)
        updateSubtitleButton()
        updateDanmakuButton()
        updateUpButton()
        updatePlaylistControls()
        // Do not auto-show OSD when opening the player; user interaction will bring it up.
        setControlsVisible(false)
        startProgressLoop()
    }

    internal fun applyUpInfo(viewData: JSONObject) {
        val owner =
            viewData.optJSONObject("owner")
                ?: viewData.optJSONObject("up_info")
                ?: JSONObject()
        currentUpMid = owner.optLong("mid").takeIf { it > 0L } ?: 0L
        currentUpName = owner.optString("name", "").trim().takeIf { it.isNotBlank() }
        currentUpAvatar = owner.optString("face", "").trim().takeIf { it.isNotBlank() }
        updateUpButton()
    }

    internal fun applyTitleMeta(viewData: JSONObject) {
        val viewCount =
            viewData
                .optJSONObject("stat")
                ?.optLong("view", -1L)
                ?.takeIf { it >= 0L }

        if (viewCount != null) {
            binding.llViewMeta.visibility = View.VISIBLE
            binding.tvViewCount.text = BlblFormat.count(viewCount)
        } else {
            binding.llViewMeta.visibility = View.GONE
        }

        val pubDateSec = viewData.optLong("pubdate", 0L).takeIf { it > 0L }
        val pubDateText = pubDateSec?.let { BlblFormat.pubDateText(it) }.orEmpty()
        binding.tvPubdate.text = pubDateText
        binding.tvPubdate.visibility = if (pubDateText.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun shouldReportHistoryNow(): Boolean {
        if (!BiliClient.cookies.hasSessData()) return false
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) return false
        val aid = currentAid ?: return false
        if (aid <= 0L) return false
        val cid = currentCid
        if (cid <= 0L) return false
        return true
    }

    private fun parseSeasonIdFromPlaylistSource(): Long? {
        val src = playlistSource?.trim().orEmpty()
        if (!src.startsWith("Bangumi:")) return null
        return src.removePrefix("Bangumi:").trim().toLongOrNull()?.takeIf { it > 0 }
    }

    private fun shouldReportPgcHeartbeatNow(): Boolean {
        if (!BiliClient.cookies.hasSessData()) return false
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) return false
        val cid = currentCid
        if (cid <= 0L) return false
        val epId = currentEpId ?: return false
        if (epId <= 0L) return false
        val hasArchiveId = (currentAid ?: 0L) > 0L || currentBvid.isNotBlank()
        if (!hasArchiveId) return false
        return true
    }

    private fun shouldReportAnyProgressNow(): Boolean {
        return shouldReportHistoryNow() || shouldReportPgcHeartbeatNow()
    }

    private fun startReportProgressLoop() {
        if (reportProgressJob != null) return
        if (!shouldReportAnyProgressNow()) return
        val token = reportToken
        reportProgressJob =
            lifecycleScope.launch {
                delay(2_000)
                while (isActive && token == reportToken) {
                    reportProgressOnce(force = false, reason = "loop")
                    delay(15_000)
                }
            }
    }

    internal fun stopReportProgressLoop(flush: Boolean, reason: String) {
        reportProgressJob?.cancel()
        reportProgressJob = null
        if (flush) lifecycleScope.launch { reportProgressOnce(force = true, reason = reason) }
    }

    private suspend fun reportProgressOnce(force: Boolean, reason: String) {
        if (!shouldReportAnyProgressNow()) return
        val token = reportToken
        val exo = player ?: return
        val cid = currentCid
        val aid = currentAid
        val epId = currentEpId
        val seasonId = parseSeasonIdFromPlaylistSource()
        val progressSec = (exo.currentPosition.coerceAtLeast(0L) / 1000L)
        if (token != reportToken) return

        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (now - lastReportAtMs < 14_000L) return
            if (progressSec == lastReportedProgressSec) return
        }

        val shouldHistory = shouldReportHistoryNow()
        val shouldHeartbeat = shouldReportPgcHeartbeatNow()
        var anyOk = false

        if (shouldHistory && aid != null) {
            runCatching {
                BiliApi.historyReport(aid = aid, cid = cid, progressSec = progressSec, platform = "android")
            }.onSuccess {
                anyOk = true
                trace?.log("report:history", "ok=1 sec=$progressSec reason=$reason")
            }.onFailure {
                trace?.log("report:history", "ok=0 sec=$progressSec reason=$reason")
            }
        }

        if (shouldHeartbeat) {
            runCatching {
                BiliApi.webHeartbeat(
                    aid = aid,
                    bvid = currentBvid,
                    cid = cid,
                    epId = epId,
                    seasonId = seasonId,
                    playedTimeSec = progressSec,
                    type = 4,
                    playType = 0,
                )
            }.onSuccess {
                anyOk = true
                trace?.log("report:heartbeat", "ok=1 sec=$progressSec reason=$reason")
            }.onFailure {
                trace?.log("report:heartbeat", "ok=0 sec=$progressSec reason=$reason")
            }
        }

        if (anyOk) {
            lastReportAtMs = now
            lastReportedProgressSec = progressSec
        }
    }

    internal fun updateDanmakuButton() {
        binding.btnDanmaku.imageTintList = null
        binding.btnDanmaku.isSelected = session.danmaku.enabled
    }

    internal fun updateSubtitleButton() {
        if (!subtitleAvailabilityKnown) {
            binding.btnSubtitle.isEnabled = true
            binding.btnSubtitle.alpha = 1.0f
            binding.btnSubtitle.imageTintList =
                ContextCompat.getColorStateList(this, blbl.cat3399.R.color.player_button_tint)
            return
        }
        if (!subtitleAvailable) {
            binding.btnSubtitle.isEnabled = false
            binding.btnSubtitle.alpha = 0.35f
            binding.btnSubtitle.imageTintList =
                ContextCompat.getColorStateList(this, blbl.cat3399.R.color.player_button_tint)
            return
        }

        binding.btnSubtitle.isEnabled = true
        binding.btnSubtitle.alpha = 1.0f
        val colorRes =
            if (session.subtitleEnabled) {
                blbl.cat3399.R.color.blbl_blue
            } else {
                blbl.cat3399.R.color.player_button_tint
            }
        binding.btnSubtitle.imageTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun toggleSubtitles(exo: ExoPlayer) {
        if (!subtitleAvailabilityKnown) {
            AppToast.show(this, "字幕信息加载中")
            return
        }
        if (!subtitleAvailable) {
            AppToast.show(this, "该视频暂无字幕")
            return
        }
        session = session.copy(subtitleEnabled = !session.subtitleEnabled)
        applySubtitleEnabled(exo)
        updateSubtitleButton()
    }

    internal fun applySubtitleEnabled(exo: ExoPlayer) {
        val disable = (!subtitleAvailable) || (!session.subtitleEnabled)
        exo.trackSelectionParameters =
            exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disable)
                .build()
    }

    private fun Playable.Dash.shouldAttemptDolbyFallback(): Boolean =
        isDolbyVision || audioKind == DashAudioKind.DOLBY || audioKind == DashAudioKind.FLAC

    private fun nextPlaybackConstraintsForDolbyFallback(picked: Playable.Dash): PlaybackConstraints? {
        if (picked.isDolbyVision && playbackConstraints.allowDolbyVision) return playbackConstraints.copy(allowDolbyVision = false)
        if (picked.audioKind == DashAudioKind.DOLBY && playbackConstraints.allowDolbyAudio) return playbackConstraints.copy(allowDolbyAudio = false)
        if (picked.audioKind == DashAudioKind.FLAC && playbackConstraints.allowFlacAudio) return playbackConstraints.copy(allowFlacAudio = false)
        return null
    }

    private fun isLikelyCodecUnsupported(error: PlaybackException): Boolean {
        val name = error.errorCodeName.uppercase(Locale.US)
        return name.contains("DECOD") || name.contains("DECODER") || name.contains("FORMAT")
    }

    private fun maybeReloadExpiredUrlAfterResume(error: PlaybackException, httpCode: Int?): Boolean {
        if (!resumeExpiredUrlReloadArmed) return false
        if (resumeExpiredUrlReloadAttempted) return false
        if (!isLikelyExpiredUrlError(error, httpCode)) return false
        resumeExpiredUrlReloadAttempted = true
        resumeExpiredUrlReloadArmed = false
        trace?.log("exo:resumeReload", "http=${httpCode ?: -1} type=${error.errorCodeName}")
        AppToast.show(this@PlayerActivity, "播放地址已过期，正在刷新…")
        reloadStream(keepPosition = true, resetConstraints = false, autoPlay = false)
        return true
    }

    private fun isLikelyExpiredUrlError(error: PlaybackException, httpCode: Int?): Boolean {
        if (httpCode != null && httpCode in setOf(403, 404, 410)) return true
        if (error.errorCode != PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) return false
        val msg = (error.cause?.message ?: error.message ?: "").lowercase(Locale.US)
        return msg.contains("403") || msg.contains("404") || msg.contains("410") || msg.contains("forbidden")
    }

    private fun findHttpResponseCode(t: Throwable?): Int? {
        var cur = t ?: return null
        repeat(12) {
            val code = (cur as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code != null) return code
            cur = cur.cause ?: return null
        }
        return null
    }

    private val deviceSupportsDolbyVision: Boolean by lazy {
        hasDecoder(MimeTypes.VIDEO_DOLBY_VISION)
    }

    private fun hasDecoder(mimeType: String): Boolean =
        try {
            MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false).isNotEmpty()
        } catch (_: Throwable) {
            false
        }

    private fun selectCdnUrlsFromTrack(obj: JSONObject, preference: String): List<String> {
        val candidates = buildList {
            val base =
                obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))
                    .trim()
            if (base.isNotBlank()) add(base)
            val backup = obj.optJSONArray("backupUrl") ?: obj.optJSONArray("backup_url") ?: JSONArray()
            for (i in 0 until backup.length()) {
                val u = backup.optString(i, "").trim()
                if (u.isNotBlank()) add(u)
            }
        }.distinct()
        if (candidates.isEmpty()) return emptyList()

        fun hostOf(url: String): String =
            runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("").lowercase(Locale.US)

        fun isMcdn(url: String): Boolean {
            val host = hostOf(url)
            return host.contains("mcdn") && host.contains("bilivideo")
        }

        fun isBilivideo(url: String): Boolean {
            val host = hostOf(url)
            return host.contains("bilivideo") && !isMcdn(url)
        }

        val preferred =
            when (preference) {
                AppPrefs.PLAYER_CDN_MCDN -> candidates.filter(::isMcdn)
                AppPrefs.PLAYER_CDN_BILIVIDEO -> candidates.filter(::isBilivideo)
                else -> emptyList()
            }
        if (preferred.isEmpty()) return candidates
        val rest = candidates.filterNot { preferred.contains(it) }
        return preferred + rest
    }

    private suspend fun pickPlayable(json: JSONObject, constraints: PlaybackConstraints): Playable {
        val data = json.optJSONObject("data") ?: json.optJSONObject("result") ?: JSONObject()
        val vVoucher = data.optString("v_voucher", "").trim()
        if (vVoucher.isNotBlank()) {
            recordVVoucher(vVoucher)
        }
        val dash = data.optJSONObject("dash")
        var dashVideoUrlForFallback: String? = null
        var dashVideoMetaForFallback: Playable.VideoOnly? = null
        if (dash != null) {
            val videos = dash.optJSONArray("video") ?: JSONArray()
            val audios = dash.optJSONArray("audio") ?: JSONArray()
            val dolby = dash.optJSONObject("dolby")
            val flac = dash.optJSONObject("flac")

            fun baseUrls(obj: JSONObject): List<String> =
                selectCdnUrlsFromTrack(obj, preference = BiliClient.prefs.playerCdnPreference)

            val preferCodecid = when (session.preferCodec) {
                "HEVC" -> 12
                "AV1" -> 13
                else -> 7
            }

            fun qnOf(v: JSONObject): Int {
                val id = v.optInt("id", 0)
                if (id > 0) return id
                return v.optInt("quality", 0).takeIf { it > 0 } ?: 0
            }

            fun isDolbyVisionTrack(v: JSONObject): Boolean {
                if (qnOf(v) == 126) return true
                val mime = v.optString("mimeType", v.optString("mime_type", "")).lowercase(Locale.US)
                if (mime.contains("dolby-vision")) return true
                val codecs = v.optString("codecs", "").lowercase(Locale.US)
                return codecs.startsWith("dvhe") || codecs.startsWith("dvh1") || codecs.contains("dovi")
            }

            val rawVideoItems = buildList {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    if (baseUrls(v).isEmpty()) continue
                    val qn = qnOf(v)
                    if (qn <= 0) continue
                    add(v)
                }
            }

            val videoItems =
                rawVideoItems.filterNot { v ->
                    isDolbyVisionTrack(v) && (!constraints.allowDolbyVision || !deviceSupportsDolbyVision)
                }

            val availableQns = videoItems.map { qnOf(it) }.filter { it > 0 }.distinct()

            val desiredQn = session.targetQn.takeIf { it > 0 } ?: session.preferredQn
            val pickedQn =
                when {
                    availableQns.contains(desiredQn) -> desiredQn
                    availableQns.isNotEmpty() -> availableQns.maxBy { qnRank(it) }
                    else -> 0
                }

            val candidatesByQn = if (pickedQn > 0) videoItems.filter { qnOf(it) == pickedQn } else videoItems
            val candidates =
                when {
                    candidatesByQn.isNotEmpty() -> candidatesByQn
                    videoItems.isNotEmpty() -> {
                        if (pickedQn > 0) {
                            AppLog.w("Player", "wanted qn=$pickedQn but no DASH track matched; fallback to best available")
                        }
                        videoItems
                    }
                    else -> emptyList()
                }

            var bestVideo: JSONObject? = null
            var bestScore = -1L
            for (v in candidates) {
                val codecid = v.optInt("codecid", 0)
                val qn = qnOf(v)
                val bandwidth = v.optLong("bandwidth", 0L)
                val okCodec = (codecid == preferCodecid)
                val score =
                    (qnRank(qn).toLong() * 1_000_000_000_000L) +
                        bandwidth +
                        (if (okCodec) 10_000_000_000L else 0L)
                if (score > bestScore) {
                    bestScore = score
                    bestVideo = v
                }
            }
            val picked = bestVideo
            if (picked == null) {
                AppLog.w("Player", "no DASH video track picked; fallback to durl if possible")
            } else {
                val videoUrlCandidates = baseUrls(picked)
                val videoUrl = videoUrlCandidates.firstOrNull().orEmpty()
                val pickedQnFinal = qnOf(picked)
                val pickedCodecid = picked.optInt("codecid", 0)
                val pickedIsDolbyVision = isDolbyVisionTrack(picked)
                dashVideoUrlForFallback = videoUrl
                dashVideoMetaForFallback =
                    Playable.VideoOnly(
                        videoUrl = videoUrl,
                        videoUrlCandidates = videoUrlCandidates,
                        qn = pickedQnFinal,
                        codecid = pickedCodecid,
                        isDolbyVision = pickedIsDolbyVision,
                    )

                data class AudioCandidate(val obj: JSONObject, val kind: DashAudioKind, val id: Int, val bandwidth: Long)

                val allAudioCandidates = buildList<AudioCandidate> {
                    for (i in 0 until audios.length()) {
                        val a = audios.optJSONObject(i) ?: continue
                        if (baseUrls(a).isEmpty()) continue
                        add(AudioCandidate(a, DashAudioKind.NORMAL, a.optInt("id", 0), a.optLong("bandwidth", 0L)))
                    }
                    val dolbyAudios = dolby?.optJSONArray("audio")
                    if (dolbyAudios != null && constraints.allowDolbyAudio) {
                        for (i in 0 until dolbyAudios.length()) {
                            val a = dolbyAudios.optJSONObject(i) ?: continue
                            if (baseUrls(a).isEmpty()) continue
                            add(AudioCandidate(a, DashAudioKind.DOLBY, a.optInt("id", 0), a.optLong("bandwidth", 0L)))
                        }
                    }
                    val flacAudio = flac?.optJSONObject("audio")
                    if (flacAudio != null && constraints.allowFlacAudio && baseUrls(flacAudio).isNotEmpty()) {
                        add(AudioCandidate(flacAudio, DashAudioKind.FLAC, flacAudio.optInt("id", 0), flacAudio.optLong("bandwidth", 0L)))
                    }
                }

                val desiredAudioId = session.targetAudioId.takeIf { it > 0 } ?: session.preferAudioId
                val audioPool =
                    when (desiredAudioId) {
                        30250 -> allAudioCandidates.filter { it.kind == DashAudioKind.DOLBY }.ifEmpty { allAudioCandidates }
                        30251 -> allAudioCandidates.filter { it.kind == DashAudioKind.FLAC }.ifEmpty { allAudioCandidates }
                        else -> allAudioCandidates.filter { it.kind == DashAudioKind.NORMAL }.ifEmpty { allAudioCandidates }
                    }

                val audioPicked =
                    audioPool.maxWithOrNull(
                        compareBy<AudioCandidate> { it.bandwidth }.thenBy { if (it.id == desiredAudioId) 1 else 0 },
                    )
                if (audioPicked == null) {
                    AppLog.w("Player", "no DASH audio track picked; fallback to durl if possible (or video-only if durl missing)")
                } else {
                    val audioUrlCandidates = baseUrls(audioPicked.obj)
                    val audioUrl = audioUrlCandidates.firstOrNull().orEmpty()
                    return Playable.Dash(
                        videoUrl = videoUrl,
                        audioUrl = audioUrl,
                        videoUrlCandidates = videoUrlCandidates,
                        audioUrlCandidates = audioUrlCandidates,
                        qn = pickedQnFinal,
                        codecid = pickedCodecid,
                        audioId = audioPicked.id,
                        audioKind = audioPicked.kind,
                        isDolbyVision = pickedIsDolbyVision,
                    )
                }
            }
        }

        // Fallback: try durl (progressive) if dash missing.
        val durlObj = data.optJSONArray("durl")?.optJSONObject(0)
        val urlCandidates =
            if (durlObj != null) {
                selectCdnUrlsFromTrack(durlObj, preference = BiliClient.prefs.playerCdnPreference)
            } else {
                emptyList()
            }
        val url = urlCandidates.firstOrNull().orEmpty()
        if (url.isNotBlank()) return Playable.Progressive(url = url, urlCandidates = urlCandidates)

        val cid = currentCid.takeIf { it > 0 }
            ?: intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0 }
            ?: error("cid missing for fallback")
        val bvid = currentBvid.ifBlank { intent.getStringExtra(EXTRA_BVID).orEmpty() }
        // Extra fallback: request MP4 directly (avoid deprecated fnval=0).
        val fallbackJson =
            requestPlayJson(
                bvid = bvid,
                aid = currentAid,
                cid = cid,
                epId = currentEpId,
                qn = 127,
                fnval = 1,
                tryLook = false,
            )
        val fallbackData = fallbackJson.optJSONObject("data") ?: fallbackJson.optJSONObject("result") ?: JSONObject()
        val fallbackObj = fallbackData.optJSONArray("durl")?.optJSONObject(0)
        val fallbackUrlCandidates =
            if (fallbackObj != null) {
                selectCdnUrlsFromTrack(fallbackObj, preference = BiliClient.prefs.playerCdnPreference)
            } else {
                emptyList()
            }
        val fallbackUrl = fallbackUrlCandidates.firstOrNull().orEmpty()
        if (fallbackUrl.isNotBlank()) return Playable.Progressive(url = fallbackUrl, urlCandidates = fallbackUrlCandidates)

        // If server returns DASH video without any audio tracks, allow video-only playback as a last resort.
        // (We still prefer progressive durl when available because it usually contains audio.)
        val dashVideoOnly = dashVideoMetaForFallback
        if (dashVideoOnly != null && !dashVideoUrlForFallback.isNullOrBlank()) {
            return dashVideoOnly
        }

        error("No playable url in playurl response")
    }

    internal fun createCdnFactory(kind: DebugStreamKind, urlCandidates: List<String>? = null): DataSource.Factory {
        val listener =
            object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    val host = dataSpec.uri.host?.trim().orEmpty()
                    if (host.isBlank()) return
                    when (kind) {
                        DebugStreamKind.VIDEO -> debug.videoTransferHost = host
                        DebugStreamKind.AUDIO -> debug.audioTransferHost = host
                        DebugStreamKind.MAIN -> debug.videoTransferHost = host
                    }
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {}

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            }

        val upstream = OkHttpDataSource.Factory(BiliClient.cdnOkHttp).setTransferListener(listener)
        val uris =
            urlCandidates
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .map { Uri.parse(it) }
        if (uris.size <= 1) return upstream
        return CdnFailoverDataSourceFactory(upstreamFactory = upstream, state = CdnFailoverState(kind = kind, candidates = uris))
    }

    internal fun buildMerged(
        videoFactory: DataSource.Factory,
        audioFactory: DataSource.Factory,
        videoUrl: String,
        audioUrl: String,
        subtitle: MediaItem.SubtitleConfiguration?,
    ): MediaSource {
        val subs = listOfNotNull(subtitle)
        val videoSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, videoFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(videoUrl)).setSubtitleConfigurations(subs).build(),
                )
        val audioSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(this, audioFactory))
                .createMediaSource(
                    MediaItem.Builder().setUri(Uri.parse(audioUrl)).build(),
                )
        return MergingMediaSource(videoSource, audioSource)
    }

    internal fun buildProgressive(factory: DataSource.Factory, url: String, subtitle: MediaItem.SubtitleConfiguration?): MediaSource {
        val subs = listOfNotNull(subtitle)
        val item =
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setSubtitleConfigurations(subs)
                .build()
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(this, factory)).createMediaSource(item)
    }

    internal fun reloadStream(keepPosition: Boolean, resetConstraints: Boolean = true, autoPlay: Boolean = true) {
        val exo = player ?: return
        val cid = currentCid
        val bvid = currentBvid
        if (cid <= 0 || bvid.isBlank()) return
        val pos = exo.currentPosition
        lifecycleScope.launch {
            try {
                val (qn, fnval) = playUrlParamsForSession()
                if (resetConstraints) {
                    playbackConstraints = PlaybackConstraints()
                    decodeFallbackAttempted = false
                    lastPickedDash = null
                }
                val (playJson, playable) =
                    loadPlayableWithTryLookFallback(
                        bvid = bvid,
                        aid = currentAid,
                        cid = cid,
                        epId = currentEpId,
                        qn = qn,
                        fnval = fnval,
                        constraints = playbackConstraints,
                    )
                showRiskControlBypassHintIfNeeded(playJson)
                lastAvailableQns = parseDashVideoQnList(playJson)
                lastAvailableAudioIds = parseDashAudioIdList(playJson, constraints = playbackConstraints)
                when (playable) {
	                    is Playable.Dash -> {
	                        lastPickedDash = playable
	                        debug.cdnHost = runCatching { Uri.parse(playable.videoUrl).host }.getOrNull()
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
	                        val mainFactory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = playable.videoUrlCandidates)
	                        exo.setMediaSource(buildProgressive(mainFactory, playable.videoUrl, subtitleConfig))
	                        applyResolutionFallbackIfNeeded(requestedQn = session.targetQn, actualQn = playable.qn)
	                    }
                    is Playable.Progressive -> {
                        lastPickedDash = null
	                        session = session.copy(actualAudioId = 0)
	                        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
	                        debug.cdnHost = runCatching { Uri.parse(playable.url).host }.getOrNull()
	                        val mainFactory = createCdnFactory(DebugStreamKind.MAIN, urlCandidates = playable.urlCandidates)
	                        exo.setMediaSource(buildProgressive(mainFactory, playable.url, subtitleConfig))
	                    }
	                }
                exo.prepare()
                applySubtitleEnabled(exo)
                if (keepPosition) exo.seekTo(pos)
                exo.playWhenReady = autoPlay
            } catch (t: Throwable) {
                AppLog.e("Player", "reloadStream failed", t)
                if (!handlePlayUrlErrorIfNeeded(t)) {
                    AppToast.show(this@PlayerActivity, "切换失败：${t.message}")
                }
            }
        }
    }

    internal suspend fun prepareSubtitleConfig(
        viewData: JSONObject,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace?,
    ): MediaItem.SubtitleConfiguration? {
        trace?.log("subtitle:items:start")
        val items = fetchSubtitleItems(viewData, bvid, cid, trace)
        trace?.log("subtitle:items:done", "count=${items.size}")
        subtitleItems = items
        val chosen = pickSubtitleItem(items) ?: return null
        trace?.log("subtitle:download:start", "lan=${chosen.lan}")
        return buildSubtitleConfigFromItem(chosen, bvid, cid, trace)
    }

    private suspend fun buildSubtitleConfigFromItem(
        item: SubtitleItem,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace? = null,
    ): MediaItem.SubtitleConfiguration? {
        val subtitleJson = runCatching { BiliClient.getJson(item.url) }.getOrNull() ?: return null
        val body = subtitleJson.optJSONArray("body") ?: subtitleJson.optJSONObject("data")?.optJSONArray("body") ?: return null

        val vtt = buildWebVtt(body)
        if (vtt.isBlank()) return null

        val safeLan = item.lan.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(cacheDir, "sub_${bvid}_${cid}_${safeLan}.vtt")
        runCatching { file.writeText(vtt, Charsets.UTF_8) }.getOrElse { return null }
        trace?.log("subtitle:download:done", "vttBytes=${vtt.toByteArray(Charsets.UTF_8).size}")

        return MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(item.lan)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    private suspend fun fetchSubtitleItems(
        viewData: JSONObject,
        bvid: String,
        cid: Long,
        trace: PlaybackTrace?,
    ): List<SubtitleItem> {
        trace?.log("subtitle:playerWbiV2:start")
        val playerJson = runCatching { BiliApi.playerWbiV2(bvid = bvid, cid = cid) }.getOrNull()
        trace?.log("subtitle:playerWbiV2:done", "ok=${playerJson != null}")
        val data = playerJson?.optJSONObject("data")
        val needLogin = data?.optBoolean("need_login_subtitle") ?: false
        val list = data?.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
        if (list.length() == 0 && needLogin && !BiliClient.cookies.hasSessData()) {
            return emptyList()
        }
        if (list.length() == 0) {
            // Fallback: try older view payload (some responses may include it).
            val legacy = viewData.optJSONObject("subtitle")?.optJSONArray("list") ?: JSONArray()
            if (legacy.length() == 0) return emptyList()
            return buildList {
                for (i in 0 until legacy.length()) {
                    val it = legacy.optJSONObject(i) ?: continue
                    val url = it.optString("subtitle_url", it.optString("subtitleUrl", "")).trim()
                    if (url.isBlank()) continue
                    val lan = it.optString("lan", "")
                    val doc = it.optString("lan_doc", it.optString("language", lan))
                    add(SubtitleItem(lan = lan.ifBlank { "unknown" }, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
                }
            }
        }
        return buildList {
            for (i in 0 until list.length()) {
                val it = list.optJSONObject(i) ?: continue
                val url = it.optString("subtitle_url", "").trim()
                val lan = it.optString("lan", "").trim()
                val doc = it.optString("lan_doc", "").trim()
                if (url.isBlank() || lan.isBlank()) continue
                add(SubtitleItem(lan = lan, lanDoc = doc.ifBlank { lan }, url = normalizeUrl(url)))
            }
        }
    }

    internal suspend fun buildSubtitleConfigFromCurrentSelection(bvid: String, cid: Long): MediaItem.SubtitleConfiguration? {
        if (bvid.isBlank() || cid <= 0) return null
        val chosen = pickSubtitleItem(subtitleItems) ?: return null
        return buildSubtitleConfigFromItem(chosen, bvid, cid)
    }

    internal data class DanmakuMeta(
        val shield: DanmakuShield,
        val segmentTotal: Int,
        val segmentSizeMs: Int,
    )

    internal suspend fun prepareDanmakuMeta(cid: Long, aid: Long?, trace: PlaybackTrace? = null): DanmakuMeta {
        trace?.log("danmakuMeta:prepare:start", "cid=$cid aid=${aid ?: -1}")
        return withContext(Dispatchers.IO) {
            val prefs = BiliClient.prefs
            val followBili = prefs.danmakuFollowBiliShield
            if (session.debugEnabled) {
                AppLog.d("Player", "danmakuMeta cid=$cid aid=${aid ?: -1} followBili=$followBili hasSess=${BiliClient.cookies.hasSessData()}")
            }
            val dmView = if (followBili && BiliClient.cookies.hasSessData()) {
                val t0 = SystemClock.elapsedRealtime()
                runCatching { BiliApi.dmWebView(cid, aid) }
                    .onFailure { AppLog.w("Player", "dmWebView failed", it) }
                    .getOrNull()
                    .also {
                        val cost = SystemClock.elapsedRealtime() - t0
                        trace?.log("danmakuMeta:dmWebView", "ok=${it != null} cost=${cost}ms")
                    }
            } else {
                null
            }
            val setting = dmView?.setting
            val shield = DanmakuShield(
                allowScroll = prefs.danmakuAllowScroll && (setting?.allowScroll ?: true),
                allowTop = prefs.danmakuAllowTop && (setting?.allowTop ?: true),
                allowBottom = prefs.danmakuAllowBottom && (setting?.allowBottom ?: true),
                allowColor = prefs.danmakuAllowColor && (setting?.allowColor ?: true),
                allowSpecial = prefs.danmakuAllowSpecial && (setting?.allowSpecial ?: true),
                aiEnabled = prefs.danmakuAiShieldEnabled || (setting?.aiEnabled ?: false),
                aiLevel = maxOf(prefs.danmakuAiShieldLevel, setting?.aiLevel ?: 0),
            )
            val segmentTotal = dmView?.segmentTotal?.takeIf { it > 0 } ?: 0
            val segmentSizeMs = dmView?.segmentPageSizeMs?.takeIf { it > 0 }?.toInt() ?: DANMAKU_DEFAULT_SEGMENT_MS
            AppLog.i(
                "Player",
                "danmaku cid=$cid segTotal=$segmentTotal segSizeMs=$segmentSizeMs followBili=$followBili hasDmSetting=${setting != null}",
            )
            DanmakuMeta(shield = shield, segmentTotal = segmentTotal, segmentSizeMs = segmentSizeMs).also {
                trace?.log("danmakuMeta:prepare:done")
            }
        }
    }

    internal fun applyDanmakuMeta(meta: DanmakuMeta) {
        cancelDanmakuLoading(reason = "meta_update")
        danmakuShield = meta.shield
        danmakuSegmentTotal = meta.segmentTotal
        danmakuSegmentSizeMs = meta.segmentSizeMs.coerceAtLeast(1)
        danmakuLoadedSegments.clear()
        danmakuSegmentItems.clear()
    }

    internal fun requestDanmakuSegmentsForPosition(positionMs: Long, immediate: Boolean) {
        val debug = session.debugEnabled
        if (danmakuShield == null) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: shield=null")
            return
        }
        if (!session.danmaku.enabled) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: disabled")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!immediate && now - lastDanmakuPrefetchAtMs < DANMAKU_PREFETCH_INTERVAL_MS) {
            if (debug) AppLog.d("Player", "danmaku prefetch skipped: interval")
            return
        }
        lastDanmakuPrefetchAtMs = now

        val cid = currentCid.takeIf { it > 0 } ?: return
        val segSize = danmakuSegmentSizeMs.coerceAtLeast(1)
        val targetSeg = (positionMs / segSize).toInt() + 1
        if (targetSeg <= 0) return

        val toLoad = buildList {
            add(targetSeg)
            for (i in 1..DANMAKU_PREFETCH_SEGMENTS) add(targetSeg + i)
        }.filter { canLoadSegment(it) }

        if (toLoad.isEmpty()) return

        if (debug) {
            AppLog.d(
                "Player",
                "danmaku prefetch cid=$cid pos=${positionMs}ms segSizeMs=$segSize targetSeg=$targetSeg toLoad=${toLoad.joinToString()} segTotal=$danmakuSegmentTotal",
            )
        }

        val loadGeneration = danmakuLoadGeneration
        val requestPositionMs = positionMs
        val requestCid = cid
        val requestSegments = toLoad.toList()
        val debugEnabled = debug

        danmakuLoadJob?.cancel()
        danmakuLoadJob =
            lifecycleScope.launch(Dispatchers.IO) {
                val shield = danmakuShield
                val loadedSegmentItems = LinkedHashMap<Int, List<blbl.cat3399.core.model.Danmaku>>()
                val loaded = ArrayList<Int>(requestSegments.size)
                try {
                    for (seg in requestSegments) {
                        val t0 = SystemClock.elapsedRealtime()
                        val list = runCatching { BiliApi.dmSeg(requestCid, seg) }.getOrNull()
                        val cost = SystemClock.elapsedRealtime() - t0
                        if (list == null) {
                            if (debugEnabled) AppLog.d("Player", "danmaku seg=$seg fetch failed cost=${cost}ms")
                            continue
                        }
                        val before = list.size
                        val filtered = if (shield != null) list.filter(shield::allow) else list
                        val sorted = if (filtered.size <= 1) filtered else filtered.sortedBy { it.timeMs }
                        val after = sorted.size
                        if (debugEnabled) {
                            val minMs = sorted.firstOrNull()?.timeMs ?: -1
                            val maxMs = sorted.lastOrNull()?.timeMs ?: -1
                            AppLog.d(
                                "Player",
                                "danmaku seg=$seg items=$before kept=$after range=${minMs}..${maxMs}ms pos=${requestPositionMs}ms cost=${cost}ms",
                            )
                        }
                        if (before > 0 && after == 0 && debugEnabled) {
                            AppLog.d("Player", "danmaku seg=$seg filteredAll (shield)")
                        }
                        loadedSegmentItems[seg] = sorted
                        loaded.add(seg)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (loadGeneration != danmakuLoadGeneration || currentCid != requestCid) {
                            danmakuLoadingSegments.removeAll(requestSegments)
                            return@withContext
                        }
                        danmakuLoadingSegments.removeAll(requestSegments)
                        danmakuLoadedSegments.addAll(loaded)
                        if (loaded.isEmpty()) return@withContext

                        var appendedAny = false
                        for ((seg, items) in loadedSegmentItems) {
                            danmakuSegmentItems[seg] = items
                            if (items.isEmpty()) continue
                            if (debugEnabled) {
                                AppLog.d("Player", "danmaku append seg=$seg count=${items.size}")
                            }
                            binding.danmakuView.appendDanmakus(items, alreadySorted = true)
                            appendedAny = true
                        }

                        trimDanmakuCacheIfNeeded(requestPositionMs)

                        if (!appendedAny && debugEnabled) {
                            AppLog.d("Player", "danmaku loadedSegs=${loaded.joinToString()} but no new items (after filter)")
                        }
                    }
                }
            }
    }

    internal fun cancelDanmakuLoading(reason: String) {
        danmakuLoadGeneration++
        danmakuLoadJob?.cancel()
        danmakuLoadJob = null
        danmakuLoadingSegments.clear()
        if (::session.isInitialized && session.debugEnabled) {
            AppLog.d("Player", "danmaku loading canceled reason=$reason generation=$danmakuLoadGeneration")
        }
    }

    private fun canLoadSegment(segmentIndex: Int): Boolean {
        if (segmentIndex <= 0) return false
        if (danmakuSegmentTotal > 0 && segmentIndex > danmakuSegmentTotal) return false
        if (danmakuLoadedSegments.contains(segmentIndex)) return false
        if (danmakuLoadingSegments.contains(segmentIndex)) return false
        danmakuLoadingSegments.add(segmentIndex)
        return true
    }

    private fun trimDanmakuCacheIfNeeded(positionMs: Long) {
        if (danmakuLoadedSegments.size <= DANMAKU_CACHE_SEGMENTS) return
        val segSize = danmakuSegmentSizeMs.coerceAtLeast(1)
        val currentSeg = (positionMs / segSize).toInt() + 1
        val minSeg = (currentSeg - DANMAKU_CACHE_SEGMENTS / 2).coerceAtLeast(1)
        val maxSeg = minSeg + DANMAKU_CACHE_SEGMENTS - 1
        val keepSegs = danmakuLoadedSegments.filter { it in minSeg..maxSeg }.toSet()
        if (keepSegs.size == danmakuLoadedSegments.size) return
        danmakuLoadedSegments.retainAll(keepSegs)
        val it = danmakuSegmentItems.entries.iterator()
        while (it.hasNext()) {
            val seg = it.next().key
            if (seg !in keepSegs) it.remove()
        }

        // Keep DanmakuView/Engine memory bounded as well, without clearing currently running items.
        val minTimeMs = (minSeg - 1L) * segSize.toLong()
        val maxTimeMs = maxSeg.toLong() * segSize.toLong()
        binding.danmakuView.trimToTimeRange(minTimeMs = minTimeMs, maxTimeMs = maxTimeMs)
    }

    internal fun resolutionSubtitle(): String {
        val qn =
            session.actualQn.takeIf { it > 0 }
                ?: session.targetQn.takeIf { it > 0 }
                ?: session.preferredQn
        return qnLabel(qn)
    }

    internal fun audioSubtitle(): String {
        val id =
            session.actualAudioId.takeIf { it > 0 }
                ?: session.targetAudioId.takeIf { it > 0 }
                ?: session.preferAudioId
        return audioLabel(id)
    }

    internal fun playUrlParamsForSession(): Pair<Int, Int> {
        // Always request the highest; B 站会根据登录/会员权限返回实际可用清晰度。
        val qn = 127
        var fnval = 4048 // all available DASH video
        fnval = fnval or 128 // 4K
        fnval = fnval or 1024 // 8K
        fnval = fnval or 64 // HDR (may be ignored if not allowed)
        fnval = fnval or 512 // Dolby Vision (may be ignored if not allowed)
        return qn to fnval
    }

    internal fun applyResolutionFallbackIfNeeded(requestedQn: Int, actualQn: Int) {
        var changed = false
        if (actualQn > 0 && session.actualQn != actualQn) {
            session = session.copy(actualQn = actualQn)
            changed = true
        }

        if (requestedQn > 0 && actualQn > 0 && requestedQn != actualQn) {
            val fallbackQn = lastAvailableQns.maxByOrNull { qnRank(it) } ?: actualQn
            if (session.targetQn != fallbackQn) {
                session = session.copy(targetQn = fallbackQn)
                changed = true
            }
        }

        if (changed) {
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    internal fun applyAudioFallbackIfNeeded(requestedAudioId: Int, actualAudioId: Int) {
        var changed = false
        if (actualAudioId > 0 && session.actualAudioId != actualAudioId) {
            session = session.copy(actualAudioId = actualAudioId)
            changed = true
        }

        if (requestedAudioId > 0 && actualAudioId > 0 && requestedAudioId != actualAudioId) {
            if (session.targetAudioId != actualAudioId) {
                session = session.copy(targetAudioId = actualAudioId)
                changed = true
            }
        }

        if (changed) {
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
        }
    }

    internal fun parseDashVideoQnList(playJson: JSONObject): List<Int> {
        val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return emptyList()
        val dash = data.optJSONObject("dash") ?: return emptyList()
        val videos = dash.optJSONArray("video") ?: return emptyList()
        val list = ArrayList<Int>(videos.length())

        fun baseUrl(obj: JSONObject): String =
            obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))

        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (baseUrl(v).isBlank()) continue
            val qn = v.optInt("id", 0).takeIf { it > 0 } ?: v.optInt("quality", 0)
            if (qn > 0) list.add(qn)
        }
        return list.distinct().sortedBy { qnRank(it) }
    }

    internal fun parseDashAudioIdList(playJson: JSONObject, constraints: PlaybackConstraints): List<Int> {
        val data = playJson.optJSONObject("data") ?: playJson.optJSONObject("result") ?: return emptyList()
        val dash = data.optJSONObject("dash") ?: return emptyList()
        val out = ArrayList<Int>(8)

        fun baseUrl(obj: JSONObject): String =
            obj.optString("baseUrl", obj.optString("base_url", obj.optString("url", "")))

        val audios = dash.optJSONArray("audio") ?: JSONArray()
        for (i in 0 until audios.length()) {
            val a = audios.optJSONObject(i) ?: continue
            if (baseUrl(a).isBlank()) continue
            val id = a.optInt("id", 0).takeIf { it > 0 } ?: continue
            out.add(id)
        }

        if (constraints.allowDolbyAudio) {
            val dolbyAudios = dash.optJSONObject("dolby")?.optJSONArray("audio") ?: JSONArray()
            for (i in 0 until dolbyAudios.length()) {
                val a = dolbyAudios.optJSONObject(i) ?: continue
                if (baseUrl(a).isBlank()) continue
                val id = a.optInt("id", 0).takeIf { it > 0 } ?: continue
                out.add(id)
            }
        }

        if (constraints.allowFlacAudio) {
            val flacAudio = dash.optJSONObject("flac")?.optJSONObject("audio")
            if (flacAudio != null && baseUrl(flacAudio).isNotBlank()) {
                val id = flacAudio.optInt("id", 0).takeIf { it > 0 } ?: 0
                if (id > 0) out.add(id)
            }
        }

        return out.distinct()
    }

    companion object {
        private const val EXIT_TRACE_PREFIX = "EXIT_TRACE"
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_EP_ID = "ep_id"
        const val EXTRA_AID = "aid"
        const val EXTRA_PLAYLIST_TOKEN = "playlist_token"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"
        private const val ACTIVITY_STACK_GROUP: String = "player_up_flow"
        private const val ACTIVITY_STACK_MAX_DEPTH: Int = 3
        internal const val SEEK_MAX = 10_000
        internal const val AUTO_HIDE_MS = 4_000L
        internal const val EDGE_TAP_THRESHOLD = 0.4f
        private const val TAP_SEEK_ACTIVE_MS = 1_200L
        internal const val SMART_SEEK_WINDOW_MS = 900L
        internal const val HOLD_SCRUB_TICK_MS = 120L
        internal const val HOLD_SCRUB_TRAVERSE_MS = 10_000L
        internal const val HOLD_SCRUB_SHORT_VIDEO_THRESHOLD_MS = 40_000L
        internal const val HOLD_SCRUB_SHORT_SPEED_MS_PER_S = 4_000L
        private const val BACK_DOUBLE_PRESS_WINDOW_MS = 2_500L
        internal const val SEEK_HINT_HIDE_DELAY_MS = 900L
        internal const val SEEK_OSD_HIDE_DELAY_MS = 1_500L
        internal const val AUTO_SKIP_START_WINDOW_MS = 1_000L
        internal const val AUTO_SKIP_DELAY_MS = 2_000L
        internal const val KEY_SCRUB_END_DELAY_MS = 800L
        private const val DANMAKU_DEFAULT_SEGMENT_MS = 6 * 60 * 1000
        private const val DANMAKU_PREFETCH_SEGMENTS = 2
        private const val DANMAKU_PREFETCH_INTERVAL_MS = 1_000L
        private const val DANMAKU_CACHE_SEGMENTS = 20
    }
}
