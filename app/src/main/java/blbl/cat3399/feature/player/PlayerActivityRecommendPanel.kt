package blbl.cat3399.feature.player

import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.feature.video.VideoCardAdapter

internal fun PlayerActivity.isRecommendPanelVisible(): Boolean = binding.recommendPanel.visibility == View.VISIBLE

internal fun PlayerActivity.initRecommendPanel() {
    binding.recommendScrim.setOnClickListener { hideRecommendPanel(restoreFocus = true) }
    binding.recommendPanel.setOnClickListener { hideRecommendPanel(restoreFocus = true) }

    binding.recyclerRecommend.layoutManager =
        LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    binding.recyclerRecommend.itemAnimator = null
    binding.recyclerRecommend.adapter =
        VideoCardAdapter(
            onClick = { card, _ ->
                playRecommendPanelCard(card)
            },
            onLongClick = null,
            fixedItemWidthTvDimen = R.dimen.player_recommend_card_width_tv,
            fixedItemMarginTvDimen = R.dimen.player_recommend_card_margin_tv,
        )

    binding.recyclerRecommend.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                view.setOnKeyListener { v, keyCode, event ->
                    if (!isRecommendPanelVisible()) return@setOnKeyListener false
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            hideRecommendPanel(restoreFocus = true)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // Keep focus from escaping to the player view / other controls.
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            val holder = binding.recyclerRecommend.findContainingViewHolder(v)
                            val pos =
                                holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: run {
                                        // Be conservative: never allow LEFT to bubble while the panel is visible.
                                        return@setOnKeyListener true
                                    }

                            if (pos <= 0) return@setOnKeyListener true
                            focusRecommendPosition(pos - 1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            val last = (binding.recyclerRecommend.adapter?.itemCount ?: 0) - 1
                            val holder = binding.recyclerRecommend.findContainingViewHolder(v)
                            val pos =
                                holder?.bindingAdapterPosition?.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: run {
                                        // Be conservative: never allow RIGHT to bubble while the panel is visible.
                                        return@setOnKeyListener true
                                    }

                            if (pos >= last) return@setOnKeyListener true
                            focusRecommendPosition(pos + 1)
                            true
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
}

internal fun PlayerActivity.showRecommendPanel(items: List<VideoCard>) {
    if (items.isEmpty()) return
    setControlsVisible(true)
    (binding.recyclerRecommend.adapter as? VideoCardAdapter)?.submit(items)
    binding.recommendScrim.visibility = View.VISIBLE
    binding.recommendPanel.visibility = View.VISIBLE
    binding.recyclerRecommend.scrollToPosition(0)
    binding.recyclerRecommend.post { focusRecommendPosition(0) }
}

internal fun PlayerActivity.hideRecommendPanel(restoreFocus: Boolean) {
    if (!isRecommendPanelVisible() && binding.recommendScrim.visibility != View.VISIBLE) return
    binding.recommendScrim.visibility = View.GONE
    binding.recommendPanel.visibility = View.GONE
    (binding.recyclerRecommend.adapter as? VideoCardAdapter)?.submit(emptyList())
    if (restoreFocus) {
        setControlsVisible(true)
        binding.btnRecommend.post { binding.btnRecommend.requestFocus() }
    }
}

private fun PlayerActivity.playRecommendPanelCard(card: VideoCard) {
    val bvid = card.bvid.trim()
    if (bvid.isBlank()) return
    hideRecommendPanel(restoreFocus = false)
    startPlayback(
        bvid = bvid,
        cidExtra = card.cid?.takeIf { it > 0 },
        epIdExtra = null,
        aidExtra = null,
        initialTitle = card.title.takeIf { it.isNotBlank() },
    )
    setControlsVisible(true)
    binding.btnRecommend.post { binding.btnRecommend.requestFocus() }
}

internal fun PlayerActivity.ensureRecommendPanelFocus() {
    if (!isRecommendPanelVisible()) return
    val focused = currentFocus
    val inPanel = focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recommendPanel)
    if (inPanel) return
    binding.recyclerRecommend.post { focusRecommendPosition(0) }
}

private fun PlayerActivity.focusRecommendPosition(position: Int) {
    val adapter = binding.recyclerRecommend.adapter ?: return
    val count = adapter.itemCount
    if (position !in 0 until count) return

    binding.recyclerRecommend.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        ?: run {
            binding.recyclerRecommend.scrollToPosition(position)
            binding.recyclerRecommend.postIfAlive(
                isAlive = { isRecommendPanelVisible() && binding.recyclerRecommend.isAttachedToWindow },
            ) {
                binding.recyclerRecommend.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        }
}
