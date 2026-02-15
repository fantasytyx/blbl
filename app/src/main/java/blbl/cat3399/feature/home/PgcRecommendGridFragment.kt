package blbl.cat3399.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.BangumiSeason
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.TabSwitchFocusTarget
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.postIfAttached
import blbl.cat3399.databinding.FragmentVideoGridBinding
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.my.MyBangumiDetailFragment
import blbl.cat3399.feature.video.VideoGridTabSwitchFocusHost
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PgcRecommendGridFragment : Fragment(), RefreshKeyHandler, TabSwitchFocusTarget {
    private var _binding: FragmentVideoGridBinding? = null
    private val binding get() = _binding!!

    private val kind: Int by lazy { requireArguments().getInt(ARG_KIND) }

    private lateinit var adapter: BangumiFollowAdapter
    private var lastUiScaleFactor: Float? = null
    private val loadedSeasonIds = HashSet<Long>()

    private var cursor: String? = null
    private var hasNext: Boolean = true
    private var isLoadingMore: Boolean = false
    private var requestToken: Int = 0
    private var initialLoadTriggered: Boolean = false

    private var pendingRestorePosition: Int? = null

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var lastFocusedAdapterPosition: Int? = null
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVideoGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                BangumiFollowAdapter { position, season ->
                    pendingRestorePosition = position
                    openBangumiDetail(season)
                }
        }

        binding.recycler.adapter = adapter
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(requireContext(), spanCountForPgc())
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recycler.clearOnScrollListeners()
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

                        override fun canLoadMore(): Boolean = hasNext

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }
        binding.recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (isLoadingMore || !hasNext) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val last = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - last - 1 <= 8) loadNextPage()
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
        lastUiScaleFactor = UiScale.factor(requireContext())
    }

    override fun onResume() {
        super.onResume()
        val old = lastUiScaleFactor
        val now = UiScale.factor(requireContext())
        lastUiScaleFactor = now
        if (old != null && old != now) adapter.invalidateSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForPgc()
        maybeTriggerInitialLoad()
        restoreFocusIfNeeded()
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

    private fun spanCountForPgc(): Int = BiliClient.prefs.pgcGridSpanCount.coerceIn(1, 6)

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        pendingFocusFirstCardFromContentSwitch = false
        pendingRestorePosition = null
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
        if (!this::adapter.isInitialized) return false
        if (pendingRestorePosition != null) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            rememberFocusedAdapterPositionFromView(focused)
            clearPendingFocusFlags()
            return false
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !FocusTreeUtils.isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val targetPosition = resolvePendingFocusTarget(itemCount = adapter.itemCount)
        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            val vh = recycler.findViewHolderForAdapterPosition(targetPosition)
            if (vh != null) {
                vh.itemView.requestFocus()
                lastFocusedAdapterPosition = targetPosition
                clearPendingFocusFlags()
                return@postIfAlive
            }
            recycler.scrollToPosition(targetPosition)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus() ?: recycler.requestFocus()
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
        isLoadingMore = false
        cursor = null
        hasNext = true
        loadedSeasonIds.clear()
        requestToken++
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || !hasNext) return
        val token = requestToken
        isLoadingMore = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val res =
                    when (kind) {
                        KIND_BANGUMI -> BiliApi.pgcBangumiPage(cursor = cursor)
                        else -> BiliApi.pgcCinemaTabPage(cursor = cursor)
                    }
                if (token != requestToken) return@launch

                cursor = res.nextCursor
                hasNext = res.hasNext

                val filtered = res.items.filter { loadedSeasonIds.add(it.seasonId) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)

                _binding?.recycler?.postIfAlive(isAlive = { _binding != null }) {
                    maybeConsumePendingFocusFirstCard()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }
                restoreFocusIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("PgcRecommend", "load failed kind=$kind cursor=$cursor", t)
                context?.let { AppToast.show(it, "加载失败，可查看 Logcat(标签 BLBL)") }
            } finally {
                if (token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun openBangumiDetail(season: BangumiSeason) {
        val host = parentFragment ?: return
        val fm = host.parentFragmentManager
        if (!isAdded || fm.isStateSaved) return

        val isDrama = kind == KIND_CINEMA
        fm.beginTransaction()
            .setReorderingAllowed(true)
            .setMaxLifecycle(host, Lifecycle.State.STARTED)
            .hide(host)
            .add(
                R.id.main_container,
                MyBangumiDetailFragment.newInstance(
                    seasonId = season.seasonId,
                    isDrama = isDrama,
                    continueEpId = null,
                    continueEpIndex = null,
                ),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        if (_binding == null) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = binding.recycler
        recycler.postIfAlive(isAlive = { _binding != null }) {
            recycler.scrollToPosition(pos)
            recycler.postIfAlive(isAlive = { _binding != null }) {
                recycler.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                pendingRestorePosition = null
            }
        }
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
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
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(R.id.tab_layout) ?: return false
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
        initialLoadTriggered = false
        pendingRestorePosition = null
        pendingFocusFirstCardFromTab = false
        pendingFocusFirstCardFromContentSwitch = false
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_KIND = "kind"

        private const val KIND_BANGUMI = 1
        private const val KIND_CINEMA = 2

        fun newBangumi(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_BANGUMI) }
        }

        fun newCinema(): PgcRecommendGridFragment = PgcRecommendGridFragment().apply {
            arguments = Bundle().apply { putInt(ARG_KIND, KIND_CINEMA) }
        }
    }
}
