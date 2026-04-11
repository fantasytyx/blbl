package blbl.cat3399.feature.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.databinding.ViewPlayerTouchOverlayBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun PlayerActivity.initTouchGestures() {
    if (!BiliClient.prefs.playerTouchGesturesEnabled) {
        releaseTouchGestures()
        return
    }
    if (touchController != null) return
    touchController =
        PlayerTouchController(this, requireTouchOverlayBinding()).also { controller ->
            controller.install()
        }
}

internal fun PlayerActivity.isTouchLocked(): Boolean = touchController?.isTouchLocked() == true

internal fun PlayerActivity.onTouchOverlayStateChanged() {
    touchController?.onControlsStateChanged()
}

internal fun PlayerActivity.releaseTouchGestures() {
    touchController?.release()
    touchController = null
    tapSeekActiveDirection = 0
    tapSeekActiveUntilMs = 0L
}

private fun PlayerActivity.requireTouchOverlayBinding(): ViewPlayerTouchOverlayBinding {
    val existing = findViewById<View>(R.id.player_touch_overlay_root)
    if (existing != null) return ViewPlayerTouchOverlayBinding.bind(existing)
    return ViewPlayerTouchOverlayBinding.bind(binding.playerTouchOverlayStub.inflate())
}

internal class PlayerTouchController(
    private val activity: PlayerActivity,
    private val overlayBinding: ViewPlayerTouchOverlayBinding,
) {
    private enum class TouchGestureMode {
        None,
        Seek,
        Brightness,
        Volume,
        Blocked,
    }

    private val binding: blbl.cat3399.databinding.ActivityPlayerBinding
        get() = activity.binding

    private val touchSlopPx = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    private val displayDensity = activity.resources.displayMetrics.density
    private val seekActivationThresholdPx =
        maxOf(
            touchSlopPx * PlayerActivity.TOUCH_GESTURE_SEEK_START_THRESHOLD_MULTIPLIER,
            displayDensity * PlayerActivity.TOUCH_GESTURE_SEEK_START_MIN_DP,
        )
    private val verticalActivationThresholdPx =
        maxOf(
            touchSlopPx * PlayerActivity.TOUCH_GESTURE_VERTICAL_START_THRESHOLD_MULTIPLIER,
            displayDensity * PlayerActivity.TOUCH_GESTURE_VERTICAL_START_MIN_DP,
        )
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val tapDetector =
        GestureDetector(
            activity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (tapSuppressed || boostActive || gestureMode != TouchGestureMode.None) return true
                    if (activity.isSidePanelVisible()) return true
                    val width = gestureLayerWidth()
                    if (width <= 0f) return true
                    val dir = activity.edgeDirection(e.x, width)
                    if (dir == 0) {
                        binding.btnPlayPause.performClick()
                        return true
                    }
                    if (activity.osdMode != PlayerActivity.OsdMode.Hidden) {
                        activity.setControlsVisible(false)
                        return true
                    }

                    activity.showSeekOsd()
                    activity.smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                    activity.tapSeekActiveDirection = dir
                    activity.tapSeekActiveUntilMs = android.os.SystemClock.uptimeMillis() + touchTapSeekActiveMs
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (tapSuppressed || boostActive || gestureMode != TouchGestureMode.None) return true
                    if (touchLocked) {
                        toggleLockedButtonVisibility()
                        return true
                    }
                    if (activity.isSidePanelVisible()) return activity.onSidePanelBackPressed()
                    if (activity.osdMode != PlayerActivity.OsdMode.Hidden) {
                        activity.setControlsVisible(false)
                        return true
                    }

                    val now = android.os.SystemClock.uptimeMillis()
                    val width = gestureLayerWidth()
                    if (width > 0f && now <= activity.tapSeekActiveUntilMs) {
                        val dir = activity.edgeDirection(e.x, width)
                        if (dir != 0 && dir == activity.tapSeekActiveDirection) {
                            activity.showSeekOsd()
                            activity.smartSeek(direction = dir, showControls = false, hintKind = SeekHintKind.Step)
                            activity.tapSeekActiveUntilMs = now + touchTapSeekActiveMs
                            return true
                        }
                    }

                    activity.setControlsVisible(true)
                    return true
                }
            },
        )

    private var gestureMode: TouchGestureMode = TouchGestureMode.None
    private var pointerDown = false
    private var tapSuppressed = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var pendingRightEdgeBoostJob: Job? = null
    private var boostActive = false
    private var boostPrevSpeed = 1.0f
    private var boostPrevPlayWhenReady = false
    private var touchSeekStartPosMs = 0L
    private var touchSeekDurationMs = 0L
    private var touchSeekBufferedPosMs = 0L
    private var volumeStart = 0
    private var brightnessStart = 0.5f
    private var touchLocked = false
    private var lockedButtonVisible = false
    private var lockUiHideJob: Job? = null

    fun install() {
        overlayBinding.root.visibility = View.VISIBLE
        overlayBinding.touchGestureLayer.visibility = View.VISIBLE
        overlayBinding.touchGestureLayer.setOnTouchListener(this::onTouch)
        overlayBinding.btnTouchLock.setOnClickListener {
            if (touchLocked) {
                unlockTouch()
            } else {
                lockTouch()
            }
        }
        updateLockUi()
    }

    fun isTouchLocked(): Boolean = touchLocked

    fun onControlsStateChanged() {
        if (!touchLocked) {
            lockedButtonVisible = false
            lockUiHideJob?.cancel()
        }
        updateLockUi()
    }

    fun onStop() {
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob = null
        finishActiveGesture(commitSeek = false)
        stopBoostPlayback()
        pointerDown = false
        tapSuppressed = false
    }

    fun release() {
        onStop()
        lockUiHideJob?.cancel()
        overlayBinding.touchGestureLayer.setOnTouchListener(null)
        overlayBinding.touchGestureLayer.visibility = View.GONE
        overlayBinding.btnTouchLock.setOnClickListener(null)
        overlayBinding.btnTouchLock.visibility = View.GONE
        overlayBinding.root.visibility = View.GONE
    }

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        if (touchLocked) {
            handleLockedTouch(event)
            return true
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            beginTracking(event)
        } else if (!pointerDown && event.actionMasked != MotionEvent.ACTION_CANCEL) {
            return false
        }

        val detectorHandled = tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!activity.isSidePanelVisible()) scheduleRightEdgeBoostIfNeeded()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tapSuppressed = true
                pendingRightEdgeBoostJob?.cancel()
                finishActiveGesture(commitSeek = false)
                stopBoostPlayback()
                gestureMode = TouchGestureMode.Blocked
            }

            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_UP -> {
                finishTouch(cancelled = false)
                if (detectorHandled && !tapSuppressed) v.performClick()
            }

            MotionEvent.ACTION_CANCEL -> finishTouch(cancelled = true)
        }
        return true
    }

    private fun handleLockedTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDown = true
                tapSuppressed = false
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                lastX = event.x
                lastY = event.y
                if (!tapSuppressed && hasExceededTouchSlop(event.x, event.y)) {
                    tapSuppressed = true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!tapSuppressed) toggleLockedButtonVisibility()
                pointerDown = false
                tapSuppressed = false
            }

            MotionEvent.ACTION_CANCEL -> {
                pointerDown = false
                tapSuppressed = false
            }
        }
    }

    private fun beginTracking(event: MotionEvent) {
        pointerDown = true
        tapSuppressed = false
        gestureMode = TouchGestureMode.None
        downX = event.x
        downY = event.y
        lastX = event.x
        lastY = event.y
        pendingRightEdgeBoostJob?.cancel()
    }

    private fun handleMove(event: MotionEvent) {
        lastX = event.x
        lastY = event.y

        if (boostActive) return

        if (activity.isSidePanelVisible()) {
            if (!tapSuppressed && hasExceededTouchSlop(event.x, event.y)) {
                tapSuppressed = true
            }
            pendingRightEdgeBoostJob?.cancel()
            return
        }

        val dx = event.x - downX
        val dy = event.y - downY
        val absDx = abs(dx)
        val absDy = abs(dy)
        if (absDx > touchSlopPx || absDy > touchSlopPx) {
            pendingRightEdgeBoostJob?.cancel()
            tapSuppressed = true
        }

        when (gestureMode) {
            TouchGestureMode.Seek -> updateSeekGesture(event.x)
            TouchGestureMode.Brightness -> updateBrightnessGesture(event.y)
            TouchGestureMode.Volume -> updateVolumeGesture(event.y)
            TouchGestureMode.Blocked -> Unit
            TouchGestureMode.None -> {
                val directionRatio = PlayerActivity.TOUCH_GESTURE_DIRECTION_RATIO
                if (absDx >= seekActivationThresholdPx && absDx >= absDy * directionRatio) {
                    if (!startSeekGesture()) {
                        gestureMode = TouchGestureMode.Blocked
                    } else {
                        updateSeekGesture(event.x)
                    }
                    return
                }

                val width = gestureLayerWidth().coerceAtLeast(1f)
                val sideThreshold = PlayerActivity.TOUCH_GESTURE_SIDE_VERTICAL_THRESHOLD
                val verticalGestureEligible = downX <= width * sideThreshold || downX >= width * (1f - sideThreshold)
                if (verticalGestureEligible && absDy >= verticalActivationThresholdPx && absDy >= absDx * directionRatio) {
                    if (downX <= width * sideThreshold) {
                        startBrightnessGesture()
                        updateBrightnessGesture(event.y)
                    } else {
                        startVolumeGesture()
                        updateVolumeGesture(event.y)
                    }
                    return
                }

                if (absDx > touchSlopPx * PlayerActivity.TOUCH_GESTURE_BLOCK_THRESHOLD_MULTIPLIER ||
                    absDy > touchSlopPx * PlayerActivity.TOUCH_GESTURE_BLOCK_THRESHOLD_MULTIPLIER
                ) {
                    gestureMode = TouchGestureMode.Blocked
                }
            }
        }
    }

    private fun finishTouch(cancelled: Boolean) {
        pointerDown = false
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob = null

        if (boostActive) {
            stopBoostPlayback()
        }
        finishActiveGesture(commitSeek = !cancelled)

        if (cancelled) {
            tapSuppressed = false
        }
    }

    private fun finishActiveGesture(commitSeek: Boolean) {
        when (gestureMode) {
            TouchGestureMode.Seek -> finishSeekGesture(commitSeek = commitSeek)
            TouchGestureMode.Brightness,
            TouchGestureMode.Volume,
            -> activity.scheduleHideSeekHint()

            TouchGestureMode.Blocked,
            TouchGestureMode.None,
            -> Unit
        }
        gestureMode = TouchGestureMode.None
    }

    private fun startSeekGesture(): Boolean {
        val engine = activity.player ?: return false
        val duration = engine.duration.takeIf { it > 0 } ?: activity.currentViewDurationMs ?: return false
        activity.cancelPendingAutoResume(reason = "user_seek")
        activity.cancelPendingAutoSkip(reason = "user_seek", markIgnored = true)
        activity.cancelPendingAutoNext(reason = "user_seek", markCancelledByUser = false)
        activity.scrubbing = true
        activity.keyScrubPendingSeekToMs = null
        activity.keyScrubEndJob?.cancel()
        touchSeekDurationMs = duration
        touchSeekBufferedPosMs = engine.bufferedPosition.coerceAtLeast(0L)
        touchSeekStartPosMs = engine.currentPosition.coerceIn(0L, duration)
        activity.holdScrubPreviewPosMs = touchSeekStartPosMs
        gestureMode = TouchGestureMode.Seek
        activity.noteUserInteraction()
        activity.showSeekOsd(
            posMs = touchSeekStartPosMs,
            durationMs = touchSeekDurationMs,
            bufferedPosMs = touchSeekBufferedPosMs,
        )
        return true
    }

    private fun updateSeekGesture(x: Float) {
        val width = gestureLayerWidth().coerceAtLeast(1f)
        val preview =
            (touchSeekStartPosMs + computeSeekDeltaMs(dx = x - downX, width = width, durationMs = touchSeekDurationMs))
                .coerceIn(0L, touchSeekDurationMs)
        if (preview == activity.holdScrubPreviewPosMs) return
        activity.holdScrubPreviewPosMs = preview
        activity.showSeekOsd(
            posMs = preview,
            durationMs = touchSeekDurationMs,
            bufferedPosMs = touchSeekBufferedPosMs,
        )
    }

    private fun finishSeekGesture(commitSeek: Boolean) {
        val engine = activity.player
        val target = activity.holdScrubPreviewPosMs ?: touchSeekStartPosMs
        activity.holdScrubPreviewPosMs = null
        activity.scrubbing = false
        if (commitSeek && engine != null) {
            engine.seekTo(target)
            activity.requestDanmakuSegmentsForPosition(target, immediate = true)
            activity.requestReportProgressOnce(reason = "user_seek_end")
            activity.showSeekOsd(
                posMs = target,
                durationMs = touchSeekDurationMs,
                bufferedPosMs = engine.bufferedPosition.coerceAtLeast(0L),
            )
        } else if (!commitSeek) {
            activity.showSeekOsd()
        }
        activity.restartAutoHideTimer()
    }

    private fun startBrightnessGesture() {
        gestureMode = TouchGestureMode.Brightness
        brightnessStart = readCurrentBrightness()
        activity.noteUserInteraction()
    }

    private fun updateBrightnessGesture(y: Float) {
        val height = overlayBinding.touchGestureLayer.height.coerceAtLeast(1)
        val delta = ((downY - y) / height.toFloat()).coerceIn(-1f, 1f)
        val brightness =
            (brightnessStart + delta).coerceIn(
                PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS,
                1.0f,
            )
        val attrs = activity.window.attributes
        attrs.screenBrightness = brightness
        activity.window.attributes = attrs
        val percent = (brightness * 100f).roundToInt().coerceIn(0, 100)
        activity.showSeekHint(activity.getString(R.string.player_touch_brightness_fmt, percent), hold = true)
    }

    private fun startVolumeGesture() {
        gestureMode = TouchGestureMode.Volume
        volumeStart = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        activity.noteUserInteraction()
    }

    private fun updateVolumeGesture(y: Float) {
        val manager = audioManager ?: return
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val height = overlayBinding.touchGestureLayer.height.coerceAtLeast(1)
        val deltaSteps = ((downY - y) / height.toFloat() * maxVolume.toFloat()).roundToInt()
        val volume = (volumeStart + deltaSteps).coerceIn(0, maxVolume)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        val percent = (volume * 100f / maxVolume.toFloat()).roundToInt().coerceIn(0, 100)
        activity.showSeekHint(activity.getString(R.string.player_touch_volume_fmt, percent), hold = true)
    }

    private fun scheduleRightEdgeBoostIfNeeded() {
        val width = gestureLayerWidth()
        if (width <= 0f) return
        val threshold = PlayerActivity.TOUCH_GESTURE_EDGE_LONG_PRESS_THRESHOLD
        if (downX < width * (1f - threshold)) return
        pendingRightEdgeBoostJob?.cancel()
        pendingRightEdgeBoostJob =
            activity.lifecycleScope.launch {
                delay(longPressTimeoutMs)
                if (!pointerDown || tapSuppressed || gestureMode != TouchGestureMode.None || boostActive) return@launch
                if (hasExceededTouchSlop(lastX, lastY)) return@launch
                startBoostPlayback()
            }
    }

    private fun startBoostPlayback() {
        val engine = activity.player ?: return
        val speed = activity.holdSeekSpeed()
        boostPrevSpeed = engine.playbackSpeed
        boostPrevPlayWhenReady = engine.playWhenReady
        boostActive = true
        tapSuppressed = true
        engine.setPlaybackSpeed(speed)
        engine.playWhenReady = true
        activity.noteUserInteraction()
        activity.showSeekHint(
            activity.getString(R.string.player_touch_boost_fmt, activity.holdSeekSpeedText(speed)),
            hold = true,
        )
        overlayBinding.touchGestureLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stopBoostPlayback() {
        if (!boostActive) return
        boostActive = false
        activity.player?.let { engine ->
            engine.setPlaybackSpeed(boostPrevSpeed)
            engine.playWhenReady = boostPrevPlayWhenReady
        }
        activity.scheduleHideSeekHint()
    }

    private fun lockTouch() {
        if (touchLocked) {
            showLockedButtonTemporarily()
            return
        }
        touchLocked = true
        pendingRightEdgeBoostJob?.cancel()
        finishActiveGesture(commitSeek = false)
        stopBoostPlayback()
        activity.setControlsVisible(false)
        activity.showSeekHint(activity.getString(R.string.player_touch_locked), hold = false)
        showLockedButtonTemporarily()
    }

    private fun unlockTouch() {
        if (!touchLocked) return
        touchLocked = false
        lockedButtonVisible = false
        lockUiHideJob?.cancel()
        updateLockUi()
        activity.showSeekHint(activity.getString(R.string.player_touch_unlocked), hold = false)
        activity.setControlsVisible(true)
        activity.noteUserInteraction()
    }

    private fun toggleLockedButtonVisibility() {
        if (!touchLocked) return
        if (lockedButtonVisible) {
            lockedButtonVisible = false
            lockUiHideJob?.cancel()
            updateLockUi()
        } else {
            showLockedButtonTemporarily()
        }
    }

    private fun showLockedButtonTemporarily() {
        if (!touchLocked) return
        lockedButtonVisible = true
        updateLockUi()
        lockUiHideJob?.cancel()
        lockUiHideJob =
            activity.lifecycleScope.launch {
                delay(PlayerActivity.TOUCH_LOCK_UI_HIDE_DELAY_MS)
                lockedButtonVisible = false
                updateLockUi()
            }
    }

    private fun updateLockUi() {
        val visible =
            if (touchLocked) {
                lockedButtonVisible
            } else {
                activity.osdMode == PlayerActivity.OsdMode.Full &&
                    !activity.isSidePanelVisible() &&
                    !activity.isBottomCardPanelVisible() &&
                    binding.commentImageViewer.visibility != View.VISIBLE
            }
        overlayBinding.btnTouchLock.visibility = if (visible) View.VISIBLE else View.GONE
        overlayBinding.btnTouchLock.setImageResource(
            if (touchLocked) R.drawable.ic_player_lock else R.drawable.ic_player_unlock,
        )
        overlayBinding.btnTouchLock.contentDescription =
            activity.getString(
                if (touchLocked) R.string.player_touch_unlock else R.string.player_touch_lock,
            )
    }

    private fun gestureLayerWidth(): Float {
        return overlayBinding.touchGestureLayer.width.toFloat().takeIf { it > 0f }
            ?: binding.playerView.width.toFloat()
    }

    private fun hasExceededTouchSlop(x: Float, y: Float): Boolean {
        return abs(x - downX) > touchSlopPx || abs(y - downY) > touchSlopPx
    }

    private fun computeSeekDeltaMs(dx: Float, width: Float, durationMs: Long): Long {
        val fullWidthMs =
            (durationMs.toDouble() * PlayerActivity.TOUCH_GESTURE_SEEK_RATIO.toDouble())
                .roundToInt()
                .toLong()
                .coerceIn(
                    PlayerActivity.TOUCH_GESTURE_SEEK_MIN_FULL_WIDTH_MS,
                    PlayerActivity.TOUCH_GESTURE_SEEK_MAX_FULL_WIDTH_MS,
                )
        return (fullWidthMs.toDouble() * (dx.toDouble() / width.toDouble())).roundToInt().toLong()
    }

    private fun readCurrentBrightness(): Float {
        val fromWindow = activity.window.attributes.screenBrightness
        if (fromWindow >= 0f) return fromWindow.coerceIn(PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS, 1.0f)
        val fromSystem =
            runCatching {
                Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull()
        if (fromSystem != null) {
            return (fromSystem / 255f).coerceIn(PlayerActivity.TOUCH_GESTURE_MIN_BRIGHTNESS, 1.0f)
        }
        return 0.5f
    }

    private companion object {
        private const val touchTapSeekActiveMs = 1_200L
    }
}
