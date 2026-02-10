package blbl.cat3399.feature.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemPlayerCommentBinding

class PlayerCommentsAdapter(
    private val onClick: (Item) -> Unit,
) : RecyclerView.Adapter<PlayerCommentsAdapter.Vh>() {
    data class ReplyPreview(
        val userName: String,
        val message: String,
    )

    data class Item(
        val key: String,
        val rpid: Long,
        val oid: Long,
        val type: Int,
        val mid: Long,
        val userName: String,
        val avatarUrl: String?,
        val message: String,
        val ctimeSec: Long,
        val likeCount: Long,
        val replyCount: Int,
        val replyPreviews: List<ReplyPreview> = emptyList(),
        val contextTag: String? = null,
        val canOpenThread: Boolean = false,
        val isThreadRoot: Boolean = false,
    )

    private val items = ArrayList<Item>()

    init {
        setHasStableIds(true)
    }

    fun setItems(list: List<Item>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun appendItems(list: List<Item>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemPlayerCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) = holder.bind(items[position], onClick)

    class Vh(private val binding: ItemPlayerCommentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Item, onClick: (Item) -> Unit) {
            val ctx = binding.root.context
            val previewUserColor = ContextCompat.getColor(ctx, R.color.blbl_blue)
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    if (item.isThreadRoot) R.color.player_comment_thread_root_bg else R.color.blbl_surface,
                ),
            )

            binding.tvContextTag.text = item.contextTag.orEmpty()
            binding.tvContextTag.visibility = if (item.contextTag.isNullOrBlank()) View.GONE else View.VISIBLE

            binding.tvUser.text = item.userName.ifBlank { "-" }
            binding.tvTime.text = Format.pubDateText(item.ctimeSec)
            binding.tvMessage.text = item.message.ifBlank { "-" }

            val likeText = Format.count(item.likeCount)
            binding.tvLike.text = "赞 $likeText"

            run {
                val previews = item.replyPreviews.take(2)
                if (previews.isNotEmpty()) {
                    binding.rowReplyPreview.visibility = View.VISIBLE
                    binding.tvReplyPreview1.text = buildReplyPreviewText(previews[0], previewUserColor)
                    if (previews.size >= 2) {
                        binding.tvReplyPreview2.text = buildReplyPreviewText(previews[1], previewUserColor)
                        binding.tvReplyPreview2.visibility = View.VISIBLE
                    } else {
                        binding.tvReplyPreview2.text = ""
                        binding.tvReplyPreview2.visibility = View.GONE
                    }
                } else {
                    binding.rowReplyPreview.visibility = View.GONE
                    binding.tvReplyPreview1.text = ""
                    binding.tvReplyPreview2.text = ""
                    binding.tvReplyPreview2.visibility = View.GONE
                }
            }

            if (item.canOpenThread && item.replyCount > 0) {
                val rc = Format.count(item.replyCount.toLong())
                binding.tvReply.text = "查看全部 $rc 条回复"
                binding.tvReply.visibility = View.VISIBLE
            } else {
                binding.tvReply.text = ""
                binding.tvReply.visibility = View.GONE
            }

            ImageLoader.loadInto(binding.ivAvatar, item.avatarUrl)

            binding.root.setOnClickListener { onClick(item) }
        }

        private fun buildReplyPreviewText(preview: ReplyPreview, userColor: Int): CharSequence {
            val u = preview.userName.ifBlank { "-" }
            val m = preview.message.ifBlank { "-" }
            val s = "$u：$m"
            val ssb = SpannableStringBuilder(s)
            ssb.setSpan(ForegroundColorSpan(userColor), 0, u.length.coerceAtMost(s.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }
    }
}
