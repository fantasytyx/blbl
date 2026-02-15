package blbl.cat3399.feature.video

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.paging.appliedOrNull
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class VideoGridFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private data class PagingKey(
        val page: Int,
        val recommendFetchRow: Int,
    )

    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: VideoCardAdapter
    private var lastUiScaleFactor: Float? = null
    private var preDrawListener: android.view.ViewTreeObserver.OnPreDrawListener? = null
    private var firstDrawLogged: Boolean = false
    private var initialLoadTriggered: Boolean = false

    private val source: String by lazy { requireArguments().getString(ARG_SOURCE) ?: SRC_POPULAR }
    private val rid: Int by lazy { requireArguments().getInt(ARG_RID, 0) }

    private val loadedBvids = HashSet<String>()
    private val paging = PagedGridStateMachine(initialKey = PagingKey(page = 1, recommendFetchRow = 1))

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        AppLog.d("VideoGrid", "onCreateView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        AppLog.d("VideoGrid", "onViewCreated source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        if (!::adapter.isInitialized) {
            adapter =
                VideoCardAdapter(
                    onClick = { card, pos ->
                        AppLog.i("VideoGrid", "click bvid=${card.bvid} cid=${card.cid}")
                        val playlistItems =
                            adapter.snapshot().map {
                                PlayerPlaylistItem(
                                    bvid = it.bvid,
                                    cid = it.cid,
                                    title = it.title,
                                )
                            }
                        val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "VideoGrid:$source/$rid")
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
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForWidth())
        (binding.recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = paging.snapshot()
                    if (s.isLoading || s.endReached) return

                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return

                    if (total - lastVisible - 1 <= 8) {
                        AppLog.d("VideoGrid", "near end source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
                        loadNextPage()
                    }
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
                            focusSelectedTabIfAvailable()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !paging.snapshot().endReached

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
        lastUiScaleFactor = UiScale.factor(requireContext())

        if (preDrawListener == null) {
            preDrawListener =
                android.view.ViewTreeObserver.OnPreDrawListener {
                    if (!firstDrawLogged) {
                        firstDrawLogged = true
                        AppLog.d(
                            "VideoGrid",
                            "first preDraw source=$source rid=$rid t=${SystemClock.uptimeMillis()}",
                        )
                    }
                    true
                }
            binding.recycler.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("VideoGrid", "onResume source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        if (this::adapter.isInitialized) {
            val old = lastUiScaleFactor
            val now = UiScale.factor(requireContext())
            lastUiScaleFactor = now
            if (old != null && old != now) adapter.invalidateSizing()
        }
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        maybeTriggerInitialLoad()
        maybeConsumePendingFocusFirstCard()
    }

    override fun handleRefreshKey(): Boolean {
        val b = _binding ?: return false
        if (!isResumed) return false
        if (b.swipeRefresh.isRefreshing) return true
        b.swipeRefresh.isRefreshing = true
        resetAndLoad()
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
        resetAndLoad()
        initialLoadTriggered = true
    }

    private fun resetAndLoad() {
        AppLog.d("VideoGrid", "resetAndLoad source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        paging.reset()
        loadedBvids.clear()
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        val startSnap = paging.snapshot()
        if (startSnap.isLoading || startSnap.endReached) return
        val startGen = startSnap.generation
        val startKey = startSnap.nextKey
        val startAt = SystemClock.uptimeMillis()
        AppLog.d(
            "VideoGrid",
            "loadNextPage start source=$source rid=$rid page=${startKey.page} refresh=$isRefresh t=$startAt",
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result =
                    paging.loadNextPage(
                        isRefresh = isRefresh,
                        fetch = { key ->
                            val ps = 24
                            when (source) {
                                SRC_RECOMMEND -> BiliApi.recommend(freshIdx = key.page, ps = ps, fetchRow = key.recommendFetchRow)
                                SRC_REGION -> BiliApi.regionLatest(rid = rid, pn = key.page, ps = ps)
                                else -> BiliApi.popular(pn = key.page, ps = ps)
                            }
                        },
                        reduce = { key, cards ->
                            if (cards.isEmpty()) {
                                PagedGridStateMachine.Update(
                                    items = emptyList(),
                                    nextKey = key,
                                    endReached = true,
                                )
                            } else {
                                val seen = HashSet<String>(cards.size)
                                val filtered =
                                    cards.filter {
                                        if (loadedBvids.contains(it.bvid)) return@filter false
                                        seen.add(it.bvid)
                                    }
                                val nextKey =
                                    if (source == SRC_RECOMMEND) {
                                        key.copy(
                                            page = key.page + 1,
                                            recommendFetchRow = key.recommendFetchRow + cards.size,
                                        )
                                    } else {
                                        key.copy(page = key.page + 1)
                                    }
                                PagedGridStateMachine.Update(
                                    items = filtered,
                                    nextKey = nextKey,
                                    endReached = filtered.isEmpty(),
                                )
                            }
                        },
                    )

                val applied = result.appliedOrNull() ?: return@launch
                applied.items.forEach { loadedBvids.add(it.bvid) }
                val endDueToEmptyFetch = applied.items.isEmpty() && paging.snapshot().nextKey == startKey
                    if (endDueToEmptyFetch) return@launch
                    if (applied.items.isNotEmpty()) {
                        if (applied.isRefresh) adapter.submit(applied.items) else adapter.append(applied.items)
                    }
                    _binding?.let { b ->
                        b.recycler.postIfAlive(isAlive = { _binding === b && isResumed }) {
                            maybeConsumePendingFocusFirstCard()
                            dpadGridController?.consumePendingFocusAfterLoadMore()
                        }
                    }
                    AppLog.i(
                        "VideoGrid",
                        "load ok source=$source rid=$rid page=${startKey.page} add=${applied.items.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms",
                    )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("VideoGrid", "load failed source=$source rid=$rid page=${startKey.page}", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (isRefresh && paging.snapshot().generation == startGen) _binding?.swipeRefresh?.isRefreshing = false
                AppLog.d(
                    "VideoGrid",
                    "loadNextPage end source=$source rid=$rid page=${paging.snapshot().nextKey.page} refresh=$isRefresh t=${SystemClock.uptimeMillis()}",
                )
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        pendingFocusFirstCardFromTab = false
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            rememberFocusedAdapterPositionFromView(focused)
            clearPendingFocusFlags()
            return false
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (!this::adapter.isInitialized) return false
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val b = _binding ?: return false
        val recycler = b.recycler
        val isUiAlive = { _binding === b && isResumed }
        recycler.postIfAlive(isAlive = isUiAlive) {
            val vh = recycler.findViewHolderForAdapterPosition(targetPosition)
            if (vh != null) {
                vh.itemView.requestFocus()
                lastFocusedAdapterPosition = targetPosition
                clearPendingFocusFlags()
                return@postIfAlive
            }
            recycler.scrollToPosition(targetPosition)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
                lastFocusedAdapterPosition = targetPosition
                clearPendingFocusFlags()
            }
        }
        return true
    }

    private fun resolvePendingFocusTarget(itemCount: Int): Int {
        if (pendingFocusFirstCardFromTab) return 0
        if (!pendingFocusFirstCardFromContentSwitch) return 0
        val saved = lastFocusedAdapterPosition ?: return 0
        return saved.coerceIn(0, itemCount - 1)
    }

    private fun clearPendingFocusFlags() {
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
    }

    private fun captureCurrentFocusedAdapterPosition() {
        val recycler = _binding?.recycler ?: return
        val focused = activity?.currentFocus ?: return
        rememberFocusedAdapterPositionFromView(focused, recycler)
    }

    private fun rememberFocusedAdapterPositionFromView(
        focusedView: View,
        recycler: RecyclerView = binding.recycler,
    ) {
        val holder = recycler.findContainingViewHolder(focusedView) ?: return
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        lastFocusedAdapterPosition = position
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= tabLayout.tabCount) return false
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(next)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? VideoGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(next)?.requestFocus()
        }
        return true
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val prev = cur - 1
        if (prev < 0) return false
        captureCurrentFocusedAdapterPosition()
        tabLayout.getTabAt(prev)?.select() ?: return false
        tabLayout.postIfAttached {
            (parentFragment as? VideoGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        AppLog.d("VideoGrid", "onDestroyView source=$source rid=$rid t=${SystemClock.uptimeMillis()}")
        preDrawListener?.let { listener ->
            if (binding.recycler.viewTreeObserver.isAlive) {
                binding.recycler.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
        preDrawListener = null
        firstDrawLogged = false
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_RID = "rid"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_POPULAR = "popular"
        const val SRC_REGION = "region"

        fun newRecommend() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }
        fun newPopular() = VideoGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_POPULAR) } }
        fun newRegion(rid: Int) = VideoGridFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE, SRC_REGION)
                putInt(ARG_RID, rid)
            }
        }
    }
}
