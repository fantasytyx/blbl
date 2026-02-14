package blbl.cat3399.feature.player

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.content.ContextCompat
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.AppPrefs
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityPlayerBinding
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Auto-scale UI based on the effective 16:9 video content size inside [playerView].
 *
 * Baseline is the "known good" tuning target:
 * - 1920x1080 with density x1.50 -> content height ~= 720dp.
 */
object PlayerContentAutoScale {
    private const val BASELINE_CONTENT_HEIGHT_DP = 720f

    // Conservative scaling curve: reduces sensitivity for large screens.
    private const val EXPONENT = 0.99f

    private const val MIN_AUTO_SCALE = 0.85f
    private const val MAX_AUTO_SCALE = 1.35f

    fun factor(playerView: View, density: Float): Float {
        val d = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val widthDp = playerView.width / d
        val heightDp = playerView.height / d
        if (!widthDp.isFinite() || !heightDp.isFinite() || widthDp <= 0f || heightDp <= 0f) return 1f

        // 16:9 content is limited by the smaller of view height and (view width * 9/16).
        val contentHeightDp = min(heightDp, widthDp * 9f / 16f)
        if (contentHeightDp <= 0f) return 1f

        val raw = contentHeightDp / BASELINE_CONTENT_HEIGHT_DP
        val scaled = raw.pow(EXPONENT)
        return scaled.coerceIn(MIN_AUTO_SCALE, MAX_AUTO_SCALE)
    }
}

object PlayerOsdSizing {
    private enum class SizeTier {
        SMALL,
        MEDIUM,
        LARGE,
    }

    fun applyTheme(activity: Activity) {
        val tier = tierFromPref(BiliClient.prefs.sidebarSize)
        val overlay =
            when (tier) {
                SizeTier.SMALL -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Small
                SizeTier.MEDIUM -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Medium
                SizeTier.LARGE -> R.style.ThemeOverlay_Blbl_PlayerOsd_Normal_Large
            }
        activity.theme.applyStyle(overlay, true)
    }

    fun applyToViews(activity: Activity, binding: ActivityPlayerBinding, scale: Float = 1.0f) {
        val s = scale.takeIf { it.isFinite() && it > 0f } ?: 1.0f
        fun scaled(v: Int): Int = (v * s).roundToInt()

        val targetSize = scaled(activity.themeDimenPx(R.attr.playerOsdButtonTargetSize)).coerceAtLeast(1)
        val padTransport = scaled(activity.themeDimenPx(R.attr.playerOsdPadTransport)).coerceAtLeast(0)
        val padNormal = scaled(activity.themeDimenPx(R.attr.playerOsdPadNormal)).coerceAtLeast(0)
        val padSmall = scaled(activity.themeDimenPx(R.attr.playerOsdPadSmall)).coerceAtLeast(0)
        val gap = scaled(activity.themeDimenPx(R.attr.playerOsdGap)).coerceAtLeast(0)

        listOf(binding.btnPrev, binding.btnPlayPause, binding.btnNext).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padTransport, padTransport, padTransport, padTransport)
            setEndMargin(btn, gap)
        }
        listOf(binding.btnSubtitle, binding.btnDanmaku, binding.btnComments, binding.btnUp).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padNormal, padNormal, padNormal, padNormal)
            setEndMargin(btn, gap)
        }
        listOf(binding.btnLike, binding.btnCoin, binding.btnFav, binding.btnPlaylist).forEach { btn ->
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padSmall, padSmall, padSmall, padSmall)
            setEndMargin(btn, gap)
        }
        run {
            val btn = binding.btnAdvanced
            setSize(btn, targetSize, targetSize)
            btn.setPadding(padSmall, padSmall, padSmall, padSmall)
            setEndMargin(btn, 0)
        }
    }

    private fun tierFromPref(prefValue: String): SizeTier {
        return when (prefValue) {
            AppPrefs.SIDEBAR_SIZE_SMALL -> SizeTier.SMALL
            AppPrefs.SIDEBAR_SIZE_LARGE -> SizeTier.LARGE
            else -> SizeTier.MEDIUM
        }
    }

    private fun Activity.themeDimenPx(attr: Int): Int {
        val out = TypedValue()
        if (!theme.resolveAttribute(attr, out, true)) return 0
        return if (out.resourceId != 0) resources.getDimensionPixelSize(out.resourceId)
        else TypedValue.complexToDimensionPixelSize(out.data, resources.displayMetrics)
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }

    private fun setEndMargin(view: View, marginEndPx: Int) {
        val lp = view.layoutParams as? MarginLayoutParams ?: return
        if (lp.marginEnd == marginEndPx) return
        lp.marginEnd = marginEndPx
        view.layoutParams = lp
    }
}

/**
 * Shared UI scaling logic for both video and live players.
 *
 * IMPORTANT: Keep behavior identical to the original in-Activity implementations.
 * This file exists to reduce the size/complexity of PlayerActivity/LivePlayerActivity.
 */
internal object PlayerUiMode {
    fun applyVideo(activity: Activity, binding: ActivityPlayerBinding, fixedAutoScale: Float? = null) {
        val density = activity.resources.displayMetrics.density
        val autoScale =
            fixedAutoScale?.takeIf { it.isFinite() && it > 0f }
                ?: PlayerContentAutoScale.factor(binding.playerView, density)

        // Use UiScale for device + user preference; additionally fine-tune by actual 16:9 content size.
        val uiScale =
            (UiScale.factor(activity, BiliClient.prefs.sidebarSize) * autoScale)
                .coerceIn(0.80f, 1.45f)
        val sidebarScale =
            UiScale.factor(activity, BiliClient.prefs.sidebarSize).coerceIn(0.60f, 1.40f)

        fun px(id: Int): Int = activity.resources.getDimensionPixelSize(id)
        fun pxF(id: Int): Float = activity.resources.getDimension(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
        fun scaledPxF(id: Int): Float = pxF(id) * uiScale
        fun scaledSidebarPx(id: Int): Int = (px(id) * sidebarScale).roundToInt().coerceAtLeast(0)

        val topPadH = scaledPx(R.dimen.player_top_bar_padding_h_tv)
        val topPadV = scaledPx(R.dimen.player_top_bar_padding_v_tv)
        val topPadTopExtra =
            scaledPx(
                R.dimen.player_top_bar_padding_top_extra_tv,
            )
        val topPadTop = topPadV + topPadTopExtra
        val topPadBottom = topPadV
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadTop ||
            binding.topBar.paddingBottom != topPadBottom
        ) {
            binding.topBar.setPadding(topPadH, topPadTop, topPadH, topPadBottom)
        }

        val topBtnSize = scaledPx(R.dimen.player_top_button_size_tv).coerceAtLeast(1)
        val topBtnPad = scaledPx(R.dimen.player_top_button_padding_tv)
        val backBtnSize = scaledSidebarPx(R.dimen.sidebar_settings_size_tv).coerceAtLeast(1)
        val backBtnPad = scaledSidebarPx(R.dimen.sidebar_settings_padding_tv)
        BackButtonSizingHelper.applySizeAndPadding(
            view = binding.btnBack,
            sizePx = backBtnSize,
            paddingPx = backBtnPad,
        )
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_title_text_size_tv),
        )
        (binding.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(R.dimen.player_title_margin_start_tv)
            val me = scaledPx(R.dimen.player_title_margin_end_tv)
            if (lp.marginStart != ms || lp.marginEnd != me) {
                lp.marginStart = ms
                lp.marginEnd = me
                binding.tvTitle.layoutParams = lp
            }
        }

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_online_text_size_tv),
        )

        binding.tvClock.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_clock_text_size_tv),
        )
        (binding.tvClock.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_clock_margin_end_tv)
            if (lp.topMargin != 0 || lp.marginEnd != me) {
                lp.topMargin = 0
                lp.marginEnd = me
                binding.tvClock.layoutParams = lp
            }
        }
        (binding.titleRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_clock_margin_start_tv)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.titleRow.layoutParams = lp
            }
        }

        run {
            val ms = scaledPx(R.dimen.player_title_margin_start_tv)
            val me = scaledPx(R.dimen.player_title_margin_end_tv)
            val mt = scaledPx(R.dimen.player_title_meta_margin_top_tv)
            (binding.llTitleMeta.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.marginStart != ms || lp.marginEnd != me || lp.topMargin != mt) {
                    lp.marginStart = ms
                    lp.marginEnd = me
                    lp.topMargin = mt
                    binding.llTitleMeta.layoutParams = lp
                }
            }
            val pb = scaledPx(R.dimen.player_title_meta_padding_bottom_tv)
            if (binding.llTitleMeta.paddingBottom != pb) {
                binding.llTitleMeta.setPadding(
                    binding.llTitleMeta.paddingLeft,
                    binding.llTitleMeta.paddingTop,
                    binding.llTitleMeta.paddingRight,
                    pb,
                )
            }
            val metaTextSizePx = scaledPxF(R.dimen.player_online_text_size_tv)
            binding.tvViewCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaTextSizePx)
            binding.tvPubdate.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaTextSizePx)
            val metaIconSize =
                scaledPx(R.dimen.video_card_stat_icon_size_tv)
                    .coerceAtLeast(1)
            setSize(binding.ivOnlineIcon, metaIconSize, metaIconSize)
            setSize(binding.ivViewIcon, metaIconSize, metaIconSize)
        }

        val bottomPadV = scaledPx(R.dimen.player_bottom_bar_padding_v_tv)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }
        if (binding.seekOsdContainer.paddingTop != bottomPadV || binding.seekOsdContainer.paddingBottom != bottomPadV) {
            binding.seekOsdContainer.setPadding(
                binding.seekOsdContainer.paddingLeft,
                bottomPadV,
                binding.seekOsdContainer.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_seekbar_touch_height_tv).coerceAtLeast(1)
            val mb = scaledPx(R.dimen.player_seekbar_margin_bottom_tv)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }
        run {
            binding.seekProgress.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.seekbar_player_progress)
            val trackHeight =
                scaledPx(
                    R.dimen.player_seekbar_track_height,
                ).coerceAtLeast(1)
            binding.seekProgress.setTrackHeightPx(trackHeight)
        }

        (binding.progressPersistentBottom.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(
                    R.dimen.player_persistent_progress_height,
                ).coerceAtLeast(1)
            if (lp.height != height) {
                lp.height = height
                binding.progressPersistentBottom.layoutParams = lp
            }
        }
        run {
            binding.progressPersistentBottom.progressDrawable =
                ContextCompat.getDrawable(activity, R.drawable.progress_player_persistent)
        }

        (binding.progressSeekOsd.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_seek_osd_progress_height).coerceAtLeast(1)
            val mh = scaledPx(R.dimen.player_seek_osd_margin_h)
            val mb = scaledPx(R.dimen.player_seek_osd_time_margin_bottom)
            if (lp.height != height || lp.marginStart != mh || lp.marginEnd != mh || lp.bottomMargin != mb) {
                lp.height = height
                lp.marginStart = mh
                lp.marginEnd = mh
                lp.bottomMargin = mb
                binding.progressSeekOsd.layoutParams = lp
            }
        }
        run {
            binding.progressSeekOsd.progressDrawable = ContextCompat.getDrawable(activity, R.drawable.progress_player_seek_osd)
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height = scaledPx(R.dimen.player_controls_row_height_tv).coerceAtLeast(1)
            val ms = scaledPx(R.dimen.player_controls_row_margin_start_tv)
            val me = scaledPx(R.dimen.player_controls_row_margin_end_tv)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }

        // OSD tier already comes from prefs via theme overlay; only normalize device/resolution and
        // fine-tune by actual 16:9 content size.
        PlayerOsdSizing.applyToViews(activity, binding, scale = UiScale.deviceFactor(activity) * autoScale)

        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_time_text_size_tv),
        )
        (binding.tvTime.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_time_margin_end_tv)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.tvTime.layoutParams = lp
            }
        }

        binding.tvSeekOsdTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_time_text_size_tv),
        )
        (binding.tvSeekOsdTime.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(R.dimen.player_seek_osd_margin_h)
            val mb = scaledPx(R.dimen.player_seek_osd_margin_bottom)
            if (lp.marginEnd != me || lp.bottomMargin != mb) {
                lp.marginEnd = me
                lp.bottomMargin = mb
                binding.tvSeekOsdTime.layoutParams = lp
            }
        }

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(R.dimen.player_seek_hint_text_size_tv),
        )
        val hintPadH = scaledPx(R.dimen.player_seek_hint_padding_h_tv)
        val hintPadV = scaledPx(R.dimen.player_seek_hint_padding_v_tv)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
        (binding.tvSeekHint.layoutParams as? MarginLayoutParams)?.let { lp ->
            val ms = scaledPx(R.dimen.player_seek_hint_margin_start_tv)
            val mb = scaledPx(R.dimen.player_seek_hint_margin_bottom_tv)
            if (lp.marginStart != ms || lp.bottomMargin != mb) {
                lp.marginStart = ms
                lp.bottomMargin = mb
                binding.tvSeekHint.layoutParams = lp
            }
        }
    }

    fun applyLive(activity: Activity, binding: ActivityPlayerBinding, fixedAutoScale: Float? = null) {
        val density = activity.resources.displayMetrics.density
        val autoScale =
            fixedAutoScale?.takeIf { it.isFinite() && it > 0f }
                ?: PlayerContentAutoScale.factor(binding.playerView, density)

        val uiScale =
            (UiScale.factor(activity, BiliClient.prefs.sidebarSize) * autoScale)
                .coerceIn(0.80f, 1.45f)
        val sidebarScale =
            UiScale.factor(activity, BiliClient.prefs.sidebarSize).coerceIn(0.60f, 1.40f)

        fun px(id: Int): Int = activity.resources.getDimensionPixelSize(id)
        fun pxF(id: Int): Float = activity.resources.getDimension(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
        fun scaledPxF(id: Int): Float = pxF(id) * uiScale
        fun scaledSidebarPx(id: Int): Int = (px(id) * sidebarScale).roundToInt().coerceAtLeast(0)

        val topPadH = scaledPx(blbl.cat3399.R.dimen.player_top_bar_padding_h_tv)
        val topPadV = scaledPx(blbl.cat3399.R.dimen.player_top_bar_padding_v_tv)
        if (
            binding.topBar.paddingLeft != topPadH ||
            binding.topBar.paddingRight != topPadH ||
            binding.topBar.paddingTop != topPadV ||
            binding.topBar.paddingBottom != topPadV
        ) {
            binding.topBar.setPadding(topPadH, topPadV, topPadH, topPadV)
        }

        val topBtnSize =
            scaledPx(blbl.cat3399.R.dimen.player_top_button_size_tv).coerceAtLeast(1)
        val topBtnPad = scaledPx(blbl.cat3399.R.dimen.player_top_button_padding_tv)
        val backBtnSize =
            scaledSidebarPx(
                blbl.cat3399.R.dimen.sidebar_settings_size_tv,
            ).coerceAtLeast(1)
        val backBtnPad =
            scaledSidebarPx(
                blbl.cat3399.R.dimen.sidebar_settings_padding_tv,
            )
        BackButtonSizingHelper.applySizeAndPadding(
            view = binding.btnBack,
            sizePx = backBtnSize,
            paddingPx = backBtnPad,
        )
        setSize(binding.btnSettings, topBtnSize, topBtnSize)
        binding.btnSettings.setPadding(topBtnPad, topBtnPad, topBtnPad, topBtnPad)

        binding.tvTitle.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_title_text_size_tv),
        )

        binding.tvOnline.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_online_text_size_tv),
        )

        binding.tvClock.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_clock_text_size_tv),
        )
        (binding.tvClock.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(blbl.cat3399.R.dimen.player_clock_margin_end_tv)
            if (lp.topMargin != 0 || lp.marginEnd != me) {
                lp.topMargin = 0
                lp.marginEnd = me
                binding.tvClock.layoutParams = lp
            }
        }
        (binding.titleRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val me = scaledPx(blbl.cat3399.R.dimen.player_clock_margin_start_tv)
            if (lp.marginEnd != me) {
                lp.marginEnd = me
                binding.titleRow.layoutParams = lp
            }
        }

        val bottomPadV = scaledPx(blbl.cat3399.R.dimen.player_bottom_bar_padding_v_tv)
        if (binding.bottomBar.paddingTop != bottomPadV || binding.bottomBar.paddingBottom != bottomPadV) {
            binding.bottomBar.setPadding(
                binding.bottomBar.paddingLeft,
                bottomPadV,
                binding.bottomBar.paddingRight,
                bottomPadV,
            )
        }

        (binding.seekProgress.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(
                    blbl.cat3399.R.dimen.player_seekbar_touch_height_tv,
                ).coerceAtLeast(1)
            val mb = scaledPx(blbl.cat3399.R.dimen.player_seekbar_margin_bottom_tv)
            if (lp.height != height || lp.bottomMargin != mb) {
                lp.height = height
                lp.bottomMargin = mb
                binding.seekProgress.layoutParams = lp
            }
        }
        run {
            binding.seekProgress.progressDrawable =
                ContextCompat.getDrawable(activity, blbl.cat3399.R.drawable.seekbar_player_progress)
            val trackHeight =
                scaledPx(
                    blbl.cat3399.R.dimen.player_seekbar_track_height,
                ).coerceAtLeast(1)
            binding.seekProgress.setTrackHeightPx(trackHeight)
        }

        (binding.controlsRow.layoutParams as? MarginLayoutParams)?.let { lp ->
            val height =
                scaledPx(blbl.cat3399.R.dimen.player_controls_row_height_tv).coerceAtLeast(1)
            val ms = scaledPx(blbl.cat3399.R.dimen.player_controls_row_margin_start_tv)
            val me = scaledPx(blbl.cat3399.R.dimen.player_controls_row_margin_end_tv)
            if (lp.height != height || lp.marginStart != ms || lp.marginEnd != me) {
                lp.height = height
                lp.marginStart = ms
                lp.marginEnd = me
                binding.controlsRow.layoutParams = lp
            }
        }

        PlayerOsdSizing.applyToViews(activity, binding, scale = UiScale.deviceFactor(activity) * autoScale)

        binding.tvTime.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_time_text_size_tv),
        )

        binding.tvSeekHint.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            scaledPxF(blbl.cat3399.R.dimen.player_seek_hint_text_size_tv),
        )
        val hintPadH = scaledPx(blbl.cat3399.R.dimen.player_seek_hint_padding_h_tv)
        val hintPadV = scaledPx(blbl.cat3399.R.dimen.player_seek_hint_padding_v_tv)
        if (
            binding.tvSeekHint.paddingLeft != hintPadH ||
            binding.tvSeekHint.paddingRight != hintPadH ||
            binding.tvSeekHint.paddingTop != hintPadV ||
            binding.tvSeekHint.paddingBottom != hintPadV
        ) {
            binding.tvSeekHint.setPadding(hintPadH, hintPadV, hintPadH, hintPadV)
        }
    }

    private fun setSize(view: View, widthPx: Int, heightPx: Int) {
        val lp = view.layoutParams ?: return
        if (lp.width == widthPx && lp.height == heightPx) return
        lp.width = widthPx
        lp.height = heightPx
        view.layoutParams = lp
    }
}
