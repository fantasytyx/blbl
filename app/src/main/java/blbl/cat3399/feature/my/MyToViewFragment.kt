package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyToViewFragment : Fragment(), MyTabSwitchFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var lastUiScaleFactor: Float? = null
    private var initialLoadTriggered: Boolean = false
    private var requestToken: Int = 0
    private var pendingFocusFirstItemFromTabSwitch: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                VideoCardAdapter(
                    onClick = { card, pos ->
                        val playlistItems =
                            adapter.snapshot().map {
                                PlayerPlaylistItem(
                                    bvid = it.bvid,
                                    cid = it.cid,
                                    title = it.title,
                                )
                            }
                        val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "MyToView")
                        if (BiliClient.prefs.playerOpenDetailBeforePlay) {
                            startActivity(
                                Intent(requireContext(), VideoDetailActivity::class.java)
                                    .putExtra(VideoDetailActivity.EXTRA_BVID, card.bvid)
                                    .putExtra(VideoDetailActivity.EXTRA_CID, card.cid ?: -1L)
                                    .apply { card.aid?.let { putExtra(VideoDetailActivity.EXTRA_AID, it) } }
                                    .putExtra(VideoDetailActivity.EXTRA_TITLE, card.title)
                                    .putExtra(VideoDetailActivity.EXTRA_COVER_URL, card.coverUrl)
                                    .apply {
                                        card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_NAME, it) }
                                        card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_AVATAR, it) }
                                        card.ownerMid?.takeIf { it > 0L }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_MID, it) }
                                    }
                                    .putExtra(VideoDetailActivity.EXTRA_PLAYLIST_TOKEN, token)
                                    .putExtra(VideoDetailActivity.EXTRA_PLAYLIST_INDEX, pos),
                            )
                        } else {
                            startActivity(
                                Intent(requireContext(), PlayerActivity::class.java)
                                    .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                                    .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                                    .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                            )
                        }
                    },
                    onLongClick = { card, _ ->
                        openUpDetailFromVideoCard(card)
                        true
                    },
                )
        }
        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth(resources))
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedMyTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevMyTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextMyTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = false

                        override fun loadMore() = Unit
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.swipeRefresh.setOnRefreshListener { reload() }
        lastUiScaleFactor = UiScale.factor(requireContext())
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) {
            val old = lastUiScaleFactor
            val now = UiScale.factor(requireContext())
            lastUiScaleFactor = now
            if (old != null && old != now) adapter.invalidateSizing()
        }
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        reload()
        return true
    }

    override fun requestFocusFirstItemFromTabSwitch(): Boolean {
        pendingFocusFirstItemFromTabSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstItemFromTabSwitch()
    }

    private fun maybeConsumePendingFocusFirstItemFromTabSwitch(): Boolean {
        if (!pendingFocusFirstItemFromTabSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false
        if (!this::adapter.isInitialized) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemFromTabSwitch = false
            return false
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                pendingFocusFirstItemFromTabSwitch = false
            }
        }
        return true
    }

    private fun maybeTriggerInitialLoad() {
        if (initialLoadTriggered) return
        if (!this::adapter.isInitialized) return
        if (adapter.itemCount != 0) {
            initialLoadTriggered = true
            return
        }
        if (binding.swipeRefresh.isRefreshing) return
        binding.swipeRefresh.isRefreshing = true
        reload()
        initialLoadTriggered = true
    }

    private fun reload() {
        val token = ++requestToken
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = BiliApi.toViewList()
                if (token != requestToken) return@launch
                adapter.submit(list)
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) {
                    maybeConsumePendingFocusFirstItemFromTabSwitch()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyToView", "load failed", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }
}
