package blbl.cat3399.feature.my

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentMyFavFolderDetailBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MyFavFolderDetailFragment : Fragment(), RefreshKeyHandler {
    private var _binding: FragmentMyFavFolderDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var lastUiScaleFactor: Float? = null

    private val mediaId: Long by lazy { requireArguments().getLong(ARG_MEDIA_ID) }
    private val title: String by lazy { requireArguments().getString(ARG_TITLE).orEmpty() }

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var pendingFocusFirstItem: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyFavFolderDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStackImmediate() }
        binding.tvTitle.text = title.ifBlank { getString(R.string.my_fav_default_title) }

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
                        val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "MyFavFolderDetail:$mediaId")
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
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            binding.btnBack.requestFocus()
                            return true
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }

        lastUiScaleFactor = UiScale.factor(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val old = lastUiScaleFactor
        val now = UiScale.factor(requireContext())
        lastUiScaleFactor = now
        applyBackButtonSizing()
        if (this::adapter.isInitialized && old != null && old != now) adapter.invalidateSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth(resources)
    }

    private fun applyBackButtonSizing() {
        val sidebarScale = UiScale.factor(requireContext(), BiliClient.prefs.sidebarSize)
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad()
        return true
    }

    private fun resetAndLoad() {
        loadedBvids.clear()
        isLoadingMore = false
        endReached = false
        page = 1
        requestToken++
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = BiliApi.favFolderResources(mediaId = mediaId, pn = page, ps = 20)
                if (token != requestToken) return@launch
                val filtered = res.items.filter { loadedBvids.add(it.bvid) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)
                maybeFocusFirstItem()
                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) { dpadGridController?.consumePendingFocusAfterLoadMore() }
                if (!res.hasMore || filtered.isEmpty()) endReached = true
                page++
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("MyFavDetail", "load failed mediaId=$mediaId", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (!pendingFocusFirstItem) return
        if (_binding == null) return
        if (adapter.itemCount <= 0) return

        val focused = activity?.currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItem = false
            return
        }

        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItem = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                pendingFocusFirstItem = false
            }
        }
    }

    override fun onDestroyView() {
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"

        fun newInstance(mediaId: Long, title: String): MyFavFolderDetailFragment =
            MyFavFolderDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_MEDIA_ID, mediaId)
                    putString(ARG_TITLE, title)
                }
            }
    }
}
