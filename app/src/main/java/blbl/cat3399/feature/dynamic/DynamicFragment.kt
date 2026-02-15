package blbl.cat3399.feature.dynamic

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentDynamicBinding
import blbl.cat3399.databinding.FragmentDynamicLoginBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DynamicFragment : Fragment(), RefreshKeyHandler {
    private var _bindingLogin: FragmentDynamicLoginBinding? = null
    private var _binding: FragmentDynamicBinding? = null

    private lateinit var followAdapter: FollowingAdapter
    private lateinit var videoAdapter: VideoCardAdapter

    private val loggedIn: Boolean
        get() = BiliClient.cookies.hasSessData()

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var nextOffset: String? = null
    private var nextPage: Int = 1
    private var requestToken: Int = 0
    private var dynamicGridController: DpadGridController? = null
    private var lastUiScaleFactor: Float? = null

    private var userMid: Long = 0L
    private var followPage: Int = 1
    private var followIsLoadingMore: Boolean = false
    private var followEndReached: Boolean = false
    private var followRequestToken: Int = 0
    private val loadedFollowMids = HashSet<Long>()
    private var followingListController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        if (!loggedIn) {
            _bindingLogin = FragmentDynamicLoginBinding.inflate(inflater, container, false)
            return _bindingLogin!!.root
        }
        _binding = FragmentDynamicBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!loggedIn) {
            _bindingLogin?.btnLogin?.setOnClickListener {
                startActivity(Intent(requireContext(), QrLoginActivity::class.java))
            }
            return
        }

        val binding = _binding ?: return

        followAdapter = FollowingAdapter(::onFollowingClicked)
        binding.recyclerFollowing.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerFollowing.adapter = followAdapter
        binding.recyclerFollowing.clearOnScrollListeners()
        binding.recyclerFollowing.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (followIsLoadingMore || followEndReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val totalCount = followAdapter.itemCount
                    if (totalCount <= 0) return
                    if (totalCount - lastVisible - 1 <= 6) loadMoreFollowings()
                }
            },
        )
        followingListController?.release()
        followingListController =
            DpadGridController(
                recyclerView = binding.recyclerFollowing,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            // No header/tab in Dynamic. Keep focus inside the list.
                            return false
                        }

                        override fun onLeftEdge(): Boolean {
                            // Let MainActivity handle entering sidebar when appropriate.
                            return false
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !followEndReached

                        override fun loadMore() {
                            loadMoreFollowings()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        consumeRightEdge = false,
                    ),
            ).also { it.install() }
        applyUiMode()
        lastUiScaleFactor = UiScale.factor(requireContext())

        videoAdapter =
            VideoCardAdapter(
                onClick = { card, pos ->
                    val playlistItems =
                        videoAdapter.snapshot().map {
                            PlayerPlaylistItem(
                                bvid = it.bvid,
                                cid = it.cid,
                                title = it.title,
                            )
                    }
                    val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "Dynamic")
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
        binding.recyclerDynamic.setHasFixedSize(true)
        binding.recyclerDynamic.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        binding.recyclerDynamic.adapter = videoAdapter
        (binding.recyclerDynamic.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        dynamicGridController?.release()
        dynamicGridController =
            DpadGridController(
                recyclerView = binding.recyclerDynamic,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            // No header/tab in Dynamic. Keep focus inside the grid.
                            return false
                        }

                        override fun onLeftEdge(): Boolean {
                            return focusSelectedFollowingIfAvailable()
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadMoreFeed()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }
        binding.recyclerDynamic.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || endReached) return
                    if (binding.swipeRefresh.isRefreshing) return

                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = videoAdapter.itemCount
                    if (total <= 0) return

                    if (total - lastVisible - 1 <= 8) {
                        loadMoreFeed()
                    }
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener { loadAll(resetFeed = true) }
        binding.swipeRefresh.isRefreshing = true
        loadAll(resetFeed = true)
    }

    private var followItems: List<FollowingAdapter.FollowingUi> = emptyList()
    private var selectedMid: Long = FollowingAdapter.MID_ALL

    private fun onFollowingClicked(following: FollowingAdapter.FollowingUi) {
        selectedMid = following.mid
        followAdapter.submit(followItems, selected = selectedMid)
        resetAndLoadFeed()
    }

    private fun loadAll(resetFeed: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nav = BiliApi.nav()
                val data = nav.optJSONObject("data")
                val mid = data?.optLong("mid") ?: 0L
                val isLogin = data?.optBoolean("isLogin") ?: false
                AppLog.i("Dynamic", "nav isLogin=$isLogin mid=$mid")
                if (!isLogin || mid <= 0) {
                    _binding?.swipeRefresh?.isRefreshing = false
                    context?.let { AppToast.show(it, "登录态失效，请重新登录") }
                    return@launch
                }

                userMid = mid
                resetAndLoadFollowings()
                if (resetFeed) resetAndLoadFeed() else loadMoreFeed()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load failed", t)
                _binding?.swipeRefresh?.isRefreshing = false
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (!resetFeed) _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private suspend fun resetAndLoadFollowings() {
        if (_binding == null) return
        if (!this::followAdapter.isInitialized) return
        if (userMid <= 0) return

        loadedFollowMids.clear()
        followPage = 1
        followEndReached = false
        followIsLoadingMore = false
        followingListController?.clearPendingFocusAfterLoadMore()
        val token = ++followRequestToken
        if (selectedMid == 0L) selectedMid = FollowingAdapter.MID_ALL
        followItems = listOf(FollowingAdapter.FollowingUi(FollowingAdapter.MID_ALL, "所有", null, isAll = true))
        followAdapter.submit(followItems, selected = selectedMid)

        followIsLoadingMore = true
        try {
            val res = BiliApi.followingsPage(vmid = userMid, pn = followPage, ps = 50)
            if (token != followRequestToken) return

            val filtered = res.items.filter { loadedFollowMids.add(it.mid) }
            val uiItems = filtered.map { f -> FollowingAdapter.FollowingUi(f.mid, f.name, f.avatarUrl) }
            if (uiItems.isEmpty()) {
                followEndReached = true
                context?.let { AppToast.show(it, "暂无关注") }
                return
            }

            followItems = followItems + uiItems
            if (selectedMid != FollowingAdapter.MID_ALL && followItems.none { it.mid == selectedMid }) {
                selectedMid = FollowingAdapter.MID_ALL
            }
            followAdapter.submit(followItems, selected = selectedMid)
            followPage += 1
            followEndReached = !res.hasMore
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.e("Dynamic", "load followings failed page=$followPage", t)
            context?.let { AppToast.show(it, "关注列表加载失败，可查看 Logcat(标签 BLBL)") }
        } finally {
            if (token == followRequestToken) {
                followIsLoadingMore = false
            }
        }
    }

    private fun loadMoreFollowings() {
        if (followIsLoadingMore || followEndReached) return
        if (userMid <= 0) return
        val token = followRequestToken
        val targetPage = followPage.coerceAtLeast(1)
        followIsLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res = BiliApi.followingsPage(vmid = userMid, pn = targetPage, ps = 50)
                if (token != followRequestToken) return@launch

                val filtered = res.items.filter { loadedFollowMids.add(it.mid) }
                val uiItems = filtered.map { f -> FollowingAdapter.FollowingUi(f.mid, f.name, f.avatarUrl) }
                if (uiItems.isNotEmpty()) {
                    followItems = followItems + uiItems
                    followAdapter.append(uiItems)
                }
                followPage = targetPage + 1
                followEndReached = !res.hasMore
                _binding?.let { b ->
                    b.recyclerFollowing.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        followingListController?.consumePendingFocusAfterLoadMore()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load followings failed page=$targetPage", t)
            } finally {
                if (token == followRequestToken) followIsLoadingMore = false
            }
        }
    }

    private fun resetAndLoadFeed() {
        dynamicGridController?.clearPendingFocusAfterLoadMore()
        loadedBvids.clear()
        nextOffset = null
        nextPage = 1
        endReached = false
        isLoadingMore = false
        requestToken++
        videoAdapter.submit(emptyList())
        _binding?.swipeRefresh?.isRefreshing = true
        loadMoreFeed()
    }

    private fun loadMoreFeed() {
        if (isLoadingMore || endReached) return
        if (selectedMid == 0L) {
            endReached = true
            _binding?.swipeRefresh?.isRefreshing = false
            return
        }
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (selectedMid == FollowingAdapter.MID_ALL) {
                    val page = BiliApi.dynamicAllVideo(offset = nextOffset)
                    if (token != requestToken) return@launch
                    nextOffset = page.nextOffset
                    endReached = nextOffset == null
                    val filtered = page.items.filter { loadedBvids.add(it.bvid) }
                    videoAdapter.append(filtered)
                } else {
                    val targetPage = nextPage.coerceAtLeast(1)
                    val page =
                        BiliApi.spaceArcSearchPage(
                            mid = selectedMid,
                            pn = targetPage,
                            ps = 30,
                        )
                    if (token != requestToken) return@launch

                    nextPage = targetPage + 1
                    endReached = !page.hasMore
                    val filtered = page.items.filter { loadedBvids.add(it.bvid) }
                    videoAdapter.append(filtered)
                }
                _binding?.let { b ->
                    b.recyclerDynamic.postIfAlive(isAlive = { _binding === b && isResumed }) {
                        dynamicGridController?.consumePendingFocusAfterLoadMore()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("Dynamic", "load feed failed mid=$selectedMid", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val prefs = BiliClient.prefs
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.dynamicSpanCountForWidthDp(
            widthDp = widthDp,
            dynamicOverrideSpanCount = prefs.dynamicGridSpanCount,
            globalOverrideSpanCount = prefs.gridSpanCount,
        )
    }

    private fun focusSelectedFollowingIfAvailable(): Boolean {
        val b = _binding ?: return false
        val recyclerFollowing = b.recyclerFollowing
        val isUiAlive = { _binding === b && isResumed }
        recyclerFollowing.postIfAlive(isAlive = isUiAlive) {
            val recycler = recyclerFollowing
            val selectedChild =
                (0 until recycler.childCount)
                    .map { recycler.getChildAt(it) }
                    .firstOrNull { it?.isSelected == true }
            if (selectedChild != null) {
                selectedChild.requestFocus()
                return@postIfAlive
            }

            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@postIfAlive
            }

            if ((recycler.adapter?.itemCount ?: 0) <= 0) {
                recycler.requestFocus()
                return@postIfAlive
            }

            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
            }
        }
        return true
    }

    override fun onDestroyView() {
        _bindingLogin = null
        dynamicGridController?.release()
        dynamicGridController = null
        followingListController?.release()
        followingListController = null
        followRequestToken++
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        val old = lastUiScaleFactor
        val now = UiScale.factor(requireContext())
        lastUiScaleFactor = now
        if (old == null || old != now) {
            applyUiMode()
            if (this::videoAdapter.isInitialized) videoAdapter.invalidateSizing()
            if (this::followAdapter.isInitialized) followAdapter.invalidateSizing()
        }
        (_binding?.recyclerDynamic?.layoutManager as? GridLayoutManager)?.let { lm ->
            val desired = spanCountForWidth()
            if (lm.spanCount != desired) lm.spanCount = desired
        }
    }

    override fun handleRefreshKey(): Boolean {
        if (!isResumed) return false
        if (!loggedIn) {
            AppToast.show(requireContext(), "请先登录")
            _bindingLogin?.btnLogin?.requestFocus()
            return true
        }

        val b = _binding ?: return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        loadAll(resetFeed = true)
        return true
    }

    private fun applyUiMode() {
        val binding = _binding ?: return
        val uiScale = UiScale.factor(requireContext())

        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun scaledPx(id: Int): Int = (px(id) * uiScale).roundToInt().coerceAtLeast(0)

        val width =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_panel_width_tv,
            )
        val margin =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_panel_margin_tv,
            )
        val padding =
            scaledPx(
                blbl.cat3399.R.dimen.dynamic_following_list_padding_tv,
            )

        val cardLp = binding.cardFollowing.layoutParams
        var changed = false
        if (cardLp.width != width.coerceAtLeast(1)) {
            cardLp.width = width.coerceAtLeast(1)
            changed = true
        }
        val mlp = cardLp as? ViewGroup.MarginLayoutParams
        if (mlp != null && (mlp.leftMargin != margin || mlp.topMargin != margin || mlp.rightMargin != margin || mlp.bottomMargin != margin)) {
            mlp.setMargins(margin, margin, margin, margin)
            changed = true
        }
        if (changed) binding.cardFollowing.layoutParams = cardLp

        if (binding.recyclerFollowing.paddingLeft != padding || binding.recyclerFollowing.paddingTop != padding || binding.recyclerFollowing.paddingRight != padding || binding.recyclerFollowing.paddingBottom != padding) {
            binding.recyclerFollowing.setPadding(padding, padding, padding, padding)
        }
    }

    companion object {
        fun newInstance() = DynamicFragment()
    }
}
