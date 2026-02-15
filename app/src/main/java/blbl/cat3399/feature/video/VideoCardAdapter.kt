package blbl.cat3399.feature.video

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemVideoCardBinding
import kotlin.math.roundToInt

class VideoCardAdapter(
    private val onClick: (VideoCard, Int) -> Unit,
    private val onLongClick: ((VideoCard, Int) -> Boolean)? = null,
    private val fixedItemWidthTvDimen: Int? = null,
    private val fixedItemMarginTvDimen: Int? = null,
) : RecyclerView.Adapter<VideoCardAdapter.Vh>() {
    private val items = ArrayList<VideoCard>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<VideoCard>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<VideoCard>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun snapshot(): List<VideoCard> = items.toList()

    override fun getItemId(position: Int): Long = items[position].stableKey().hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding, fixedItemWidthTvDimen, fixedItemMarginTvDimen)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick, onLongClick)

    override fun getItemCount(): Int = items.size

    class Vh(
        private val binding: ItemVideoCardBinding,
        private val fixedItemWidthTvDimen: Int?,
        private val fixedItemMarginTvDimen: Int?,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var lastUiScale: Float? = null
        private var baseMsView: Int? = null
        private var baseMsDanmakuIcon: Int? = null
        private var baseMsDanmakuText: Int? = null
        private var baseMsPubdate: Int? = null

        fun bind(item: VideoCard, onClick: (VideoCard, Int) -> Unit, onLongClick: ((VideoCard, Int) -> Boolean)?) {
            val uiScale = UiScale.factor(binding.root.context)
            if (lastUiScale != uiScale) {
                applySizing(uiScale)
                lastUiScale = uiScale
            }

            binding.tvTitle.text = item.title
            binding.tvSubtitle.text =
                item.pubDateText
                    ?: if (item.ownerName.isBlank()) "" else "UP ${item.ownerName}"
            val pubDateText = item.pubDate?.let { Format.pubDateText(it) }.orEmpty()
            binding.tvPubdate.text = pubDateText
            binding.tvPubdate.isVisible = pubDateText.isNotBlank()
            binding.tvDuration.text = Format.duration(item.durationSec)
            binding.tvView.text = Format.count(item.view)
            binding.tvDanmaku.text = Format.count(item.danmaku)
            binding.tvChargeBadge.isVisible = item.isChargingArc
            ImageLoader.loadInto(binding.ivCover, ImageUrl.cover(item.coverUrl))

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
                onClick(item, pos)
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnLongClickListener false
                onLongClick?.invoke(item, pos) ?: false
            }
        }

        private fun applySizing(uiScale: Float) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)

            fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
            fun scaledPxF(id: Int): Float = pxF(id) * uiScale

            if (fixedItemWidthTvDimen != null) {
                val w = scaledPx(fixedItemWidthTvDimen).coerceAtLeast(1)
                val rootLp =
                    (binding.root.layoutParams as? MarginLayoutParams)
                        ?: MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                if (rootLp.width != w) {
                    rootLp.width = w
                    binding.root.layoutParams = rootLp
                }
            }

            val margin = scaledPx(fixedItemMarginTvDimen ?: R.dimen.video_card_margin_tv)
            val rootLp = binding.root.layoutParams as? MarginLayoutParams
            if (rootLp != null) {
                if (rootLp.leftMargin != margin || rootLp.topMargin != margin || rootLp.rightMargin != margin || rootLp.bottomMargin != margin) {
                    rootLp.setMargins(margin, margin, margin, margin)
                    binding.root.layoutParams = rootLp
                }
            }

            val textMargin = scaledPx(R.dimen.video_card_text_margin_tv)
            (binding.tvChargeBadge.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvChargeBadge.layoutParams = lp
                }
            }
            (binding.tvDuration.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvDuration.layoutParams = lp
                }
            }
            (binding.llStats.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.llStats.layoutParams = lp
                }
            }
            (binding.tvTitle.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.topMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.setMargins(textMargin, textMargin, textMargin, textMargin)
                    binding.tvTitle.layoutParams = lp
                }
            }
            (binding.llSubtitle.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != textMargin || lp.rightMargin != textMargin || lp.bottomMargin != textMargin) {
                    lp.leftMargin = textMargin
                    lp.rightMargin = textMargin
                    lp.bottomMargin = textMargin
                    binding.llSubtitle.layoutParams = lp
                }
            }

            val padH = scaledPx(R.dimen.video_card_duration_padding_h_tv)
            val padV = scaledPx(R.dimen.video_card_duration_padding_v_tv)
            binding.tvChargeBadge.setPadding(padH, padV, padH, padV)
            binding.tvDuration.setPadding(padH, padV, padH, padV)
            binding.llStats.setPadding(padH, padV, padH, padV)

            run {
                // "Closer to the left edge by 50%": the visible inset is (margin + inner padding),
                // so shift left by 50% of that total inset (more noticeable than margin-only).
                val insetX = textMargin + padH
                val shiftX = -insetX * 0.5f
                binding.llStats.translationX = shiftX
                binding.tvDuration.translationX = shiftX

                // "Move down 20%": apply 20% of overlay height (and a tiny baseline from margin)
                // so the change is visible across different UI scales.
                fun applyShiftY() {
                    val overlayH = maxOf(binding.llStats.height, binding.tvDuration.height)
                    val shiftY = (textMargin + padV) * 0.2f + overlayH * 0.2f
                    binding.llStats.translationY = shiftY
                    binding.tvDuration.translationY = shiftY
                }

                if (binding.llStats.height > 0 || binding.tvDuration.height > 0) {
                    applyShiftY()
                } else {
                    binding.root.post { applyShiftY() }
                }
            }

            binding.tvChargeBadge.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_duration_text_size_tv),
            )
            binding.tvDuration.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_duration_text_size_tv),
            )
            binding.tvView.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_stat_text_size_tv),
            )
            binding.tvDanmaku.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_stat_text_size_tv),
            )
            binding.tvTitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_title_text_size_tv),
            )
            binding.tvSubtitle.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_subtitle_text_size_tv),
            )
            binding.tvPubdate.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_subtitle_text_size_tv),
            )

            val iconSize = scaledPx(R.dimen.video_card_stat_icon_size_tv)
            val playLp = binding.ivStatPlay.layoutParams
            if (playLp.width != iconSize || playLp.height != iconSize) {
                playLp.width = iconSize
                playLp.height = iconSize
                binding.ivStatPlay.layoutParams = playLp
            }
            val danLp = binding.ivStatDanmaku.layoutParams
            if (danLp.width != iconSize || danLp.height != iconSize) {
                danLp.width = iconSize
                danLp.height = iconSize
                binding.ivStatDanmaku.layoutParams = danLp
            }

            fun scaleMarginStart(view: android.view.View, basePx: Int?, minPx: Int = 0): Int? {
                val lp = view.layoutParams as? MarginLayoutParams ?: return basePx
                val base = basePx ?: lp.marginStart
                val ms = (base * uiScale).roundToInt().coerceAtLeast(minPx)
                if (lp.marginStart != ms) {
                    lp.marginStart = ms
                    view.layoutParams = lp
                }
                return base
            }
            // These are defined as hard-coded dp in XML; scale them to match the UI size preset.
            baseMsView = scaleMarginStart(binding.tvView, baseMsView)
            baseMsDanmakuIcon = scaleMarginStart(binding.ivStatDanmaku, baseMsDanmakuIcon)
            baseMsDanmakuText = scaleMarginStart(binding.tvDanmaku, baseMsDanmakuText)
            baseMsPubdate = scaleMarginStart(binding.tvPubdate, baseMsPubdate)
        }
    }
}
