package blbl.cat3399.feature.following

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.model.Following
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ItemFollowingGridBinding
import kotlin.math.roundToInt

class FollowingGridAdapter(
    private val onClick: (Following) -> Unit,
) : RecyclerView.Adapter<FollowingGridAdapter.Vh>() {
    private val items = ArrayList<Following>()

    init {
        setHasStableIds(true)
    }

    fun invalidateSizing() {
        if (itemCount <= 0) return
        notifyItemRangeChanged(0, itemCount)
    }

    fun submit(list: List<Following>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Following>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemId(position: Int): Long = items[position].mid

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemFollowingGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class Vh(private val binding: ItemFollowingGridBinding) : RecyclerView.ViewHolder(binding.root) {
        private var lastUiScale: Float? = null

        fun bind(item: Following, onClick: (Following) -> Unit) {
            val uiScale = UiScale.factor(binding.root.context)
            if (lastUiScale != uiScale) {
                applySizing(uiScale)
                lastUiScale = uiScale
            }

            binding.tvName.text = item.name
            binding.tvSign.text = item.sign.orEmpty()
            binding.tvSign.isVisible = !item.sign.isNullOrBlank()
            binding.tvBadgeLive.isVisible = item.isLive

            ImageLoader.loadInto(binding.ivAvatar, ImageUrl.avatar(item.avatarUrl))
            binding.root.setOnClickListener { onClick(item) }
        }

        private fun applySizing(uiScale: Float) {
            fun px(id: Int): Int = binding.root.resources.getDimensionPixelSize(id)
            fun pxF(id: Int): Float = binding.root.resources.getDimension(id)
            fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)
            fun scaledPxF(id: Int): Float = pxF(id) * uiScale

            val margin = scaledPx(R.dimen.following_grid_item_margin_tv)
            (binding.root.layoutParams as? MarginLayoutParams)?.let { lp ->
                if (lp.leftMargin != margin || lp.topMargin != margin || lp.rightMargin != margin || lp.bottomMargin != margin) {
                    lp.setMargins(margin, margin, margin, margin)
                    binding.root.layoutParams = lp
                }
            }

            val pad = scaledPx(R.dimen.following_grid_item_padding_tv)
            if (binding.root.paddingLeft != pad || binding.root.paddingTop != pad || binding.root.paddingRight != pad || binding.root.paddingBottom != pad) {
                binding.root.setPadding(pad, pad, pad, pad)
            }

            val avatarSize =
                scaledPx(R.dimen.following_grid_avatar_size_tv).coerceAtLeast(1)
            val avatarLp = binding.flAvatar.layoutParams
            if (avatarLp.width != avatarSize || avatarLp.height != avatarSize) {
                avatarLp.width = avatarSize
                avatarLp.height = avatarSize
                binding.flAvatar.layoutParams = avatarLp
            }

            (binding.tvBadgeLive.layoutParams as? MarginLayoutParams)?.let { lp ->
                val m = scaledPx(R.dimen.video_card_duration_padding_v_tv)
                if (lp.leftMargin != m || lp.topMargin != m || lp.rightMargin != m || lp.bottomMargin != m) {
                    lp.setMargins(m, m, m, m)
                    binding.tvBadgeLive.layoutParams = lp
                }
            }
            val badgePadH = scaledPx(R.dimen.video_card_duration_padding_h_tv)
            val badgePadV = scaledPx(R.dimen.video_card_duration_padding_v_tv)
            if (
                binding.tvBadgeLive.paddingLeft != badgePadH ||
                binding.tvBadgeLive.paddingTop != badgePadV ||
                binding.tvBadgeLive.paddingRight != badgePadH ||
                binding.tvBadgeLive.paddingBottom != badgePadV
            ) {
                binding.tvBadgeLive.setPadding(badgePadH, badgePadV, badgePadH, badgePadV)
            }
            binding.tvBadgeLive.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.video_card_duration_text_size_tv),
            )

            (binding.tvName.layoutParams as? MarginLayoutParams)?.let { lp ->
                val mt = scaledPx(R.dimen.following_grid_name_margin_top_tv)
                if (lp.topMargin != mt) {
                    lp.topMargin = mt
                    binding.tvName.layoutParams = lp
                }
            }
            binding.tvName.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.following_grid_name_text_size_tv),
            )

            (binding.tvSign.layoutParams as? MarginLayoutParams)?.let { lp ->
                val mt = scaledPx(R.dimen.following_grid_sign_margin_top_tv)
                if (lp.topMargin != mt) {
                    lp.topMargin = mt
                    binding.tvSign.layoutParams = lp
                }
            }
            binding.tvSign.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                scaledPxF(R.dimen.following_grid_sign_text_size_tv),
            )
        }
    }
}
