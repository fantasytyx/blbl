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
import blbl.cat3399.core.emote.EmoteSpannable
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.note.NoteImageRepository
import blbl.cat3399.core.util.Format
import blbl.cat3399.databinding.ItemPlayerCommentBinding

class PlayerCommentsAdapter(
    private val onClick: (Item) -> Unit,
) : RecyclerView.Adapter<PlayerCommentsAdapter.Vh>() {
    data class ReplyPreview(
        val userName: String,
        val message: String,
        val emotes: Map<String, String> = emptyMap(),
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
        val emotes: Map<String, String> = emptyMap(),
        val pictures: List<String> = emptyList(),
        val noteCvid: Long = 0L,
        val ctimeSec: Long,
        val likeCount: Long,
        val replyCount: Int,
        val replyPreviews: List<ReplyPreview> = emptyList(),
        val contextTag: String? = null,
        val canOpenThread: Boolean = false,
        val isThreadRoot: Boolean = false,
        val isUp: Boolean = false,
    )

    private val items = ArrayList<Item>()
    private val requestedNotePictures = HashSet<Long>()

    init {
        setHasStableIds(true)
    }

    fun setItems(list: List<Item>) {
        items.clear()
        items.addAll(list)
        requestedNotePictures.clear()
        notifyDataSetChanged()
    }

    fun appendItems(list: List<Item>) {
        if (list.isEmpty()) return
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun updatePictures(rpid: Long, pictures: List<String>) {
        if (pictures.isEmpty()) return
        val idx = items.indexOfFirst { it.rpid == rpid }
        if (idx !in items.indices) return
        val current = items[idx]
        if (current.pictures == pictures) return
        items[idx] = current.copy(pictures = pictures)
        notifyItemChanged(idx)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Vh {
        val binding = ItemPlayerCommentBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Vh(binding)
    }

    override fun onBindViewHolder(holder: Vh, position: Int) {
        val item = items[position]
        maybeRequestNotePictures(item)
        holder.bind(item, onClick)
    }

    private fun maybeRequestNotePictures(item: Item) {
        if (item.pictures.isNotEmpty()) return
        if (item.noteCvid <= 0L) return
        if (!requestedNotePictures.add(item.rpid)) return
        NoteImageRepository.load(item.noteCvid) { urls ->
            updatePictures(item.rpid, urls)
        }
    }

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
            binding.tvUpBadge.visibility = if (item.isUp) View.VISIBLE else View.GONE
            binding.tvTime.text = Format.pubDateText(item.ctimeSec)
            val blankFallback = if (item.pictures.isNotEmpty() || item.noteCvid > 0L) "" else "-"
            EmoteSpannable.setText(binding.tvMessage, item.message, item.emotes, blankFallback = blankFallback)
            bindPictures(item.pictures)

            run {
                val previews = item.replyPreviews.take(2)
                if (previews.isNotEmpty()) {
                    binding.rowReplyPreview.visibility = View.VISIBLE
                    bindReplyPreviewText(binding.tvReplyPreview1, previews[0], previewUserColor)
                    if (previews.size >= 2) {
                        bindReplyPreviewText(binding.tvReplyPreview2, previews[1], previewUserColor)
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
                binding.rowMeta.visibility = View.VISIBLE
            } else {
                binding.tvReply.text = ""
                binding.tvReply.visibility = View.GONE
                binding.rowMeta.visibility = View.GONE
            }

            ImageLoader.loadInto(binding.ivAvatar, item.avatarUrl)

            binding.root.setOnClickListener { onClick(item) }
        }

        private fun bindPictures(pictures: List<String>) {
            val urls = pictures.take(3).filter { it.isNotBlank() }
            if (urls.isEmpty()) {
                binding.rowPictures.visibility = View.GONE
                ImageLoader.loadInto(binding.ivPicture1, null)
                ImageLoader.loadInto(binding.ivPicture2, null)
                ImageLoader.loadInto(binding.ivPicture3, null)
                binding.ivPicture1.visibility = View.GONE
                binding.ivPicture2.visibility = View.GONE
                binding.ivPicture3.visibility = View.GONE
                return
            }

            binding.rowPictures.visibility = View.VISIBLE
            val v1 = urls.getOrNull(0)
            val v2 = urls.getOrNull(1)
            val v3 = urls.getOrNull(2)

            if (v1 != null) {
                binding.ivPicture1.visibility = View.VISIBLE
                ImageLoader.loadInto(binding.ivPicture1, v1)
            } else {
                binding.ivPicture1.visibility = View.GONE
                ImageLoader.loadInto(binding.ivPicture1, null)
            }

            if (v2 != null) {
                binding.ivPicture2.visibility = View.VISIBLE
                ImageLoader.loadInto(binding.ivPicture2, v2)
            } else {
                binding.ivPicture2.visibility = View.GONE
                ImageLoader.loadInto(binding.ivPicture2, null)
            }

            if (v3 != null) {
                binding.ivPicture3.visibility = View.VISIBLE
                ImageLoader.loadInto(binding.ivPicture3, v3)
            } else {
                binding.ivPicture3.visibility = View.GONE
                ImageLoader.loadInto(binding.ivPicture3, null)
            }
        }

        private fun bindReplyPreviewText(view: android.widget.TextView, preview: ReplyPreview, userColor: Int) {
            val u = preview.userName.ifBlank { "-" }
            val m = preview.message.ifBlank { "-" }
            val s = "$u：$m"
            val ssb = SpannableStringBuilder(s)
            ssb.setSpan(ForegroundColorSpan(userColor), 0, u.length.coerceAtMost(s.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            view.setTag(R.id.tag_emote_text_key, s)
            EmoteSpannable.applyEmotes(view, ssb, start = 0, end = ssb.length, emotes = preview.emotes)
            view.setText(ssb, android.widget.TextView.BufferType.SPANNABLE)
        }
    }
}
