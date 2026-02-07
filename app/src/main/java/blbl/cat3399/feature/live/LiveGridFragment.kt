package blbl.cat3399.feature.live

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.FragmentLiveGridBinding
import blbl.cat3399.ui.RefreshKeyHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LiveGridFragment : Fragment(), LivePageFocusTarget, RefreshKeyHandler {
    private var _binding: FragmentLiveGridBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LiveRoomAdapter

    private var initialLoadTriggered: Boolean = false
    private var lastSpanCountContentWidthPx: Int = -1

    private val source: String by lazy { requireArguments().getString(ARG_SOURCE) ?: SRC_RECOMMEND }
    private val enableTabFocus: Boolean by lazy { requireArguments().getBoolean(ARG_ENABLE_TAB_FOCUS, true) }

    private val loadedRoomIds = HashSet<Long>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false

    private var page: Int = 1
    private var requestToken: Int = 0

    private var pendingFocusFirstCardFromTab: Boolean = false
    private var pendingFocusFirstCardFromContentSwitch: Boolean = false
    private var dpadGridController: DpadGridController? = null
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreAttemptsLeft: Int = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveGridBinding.inflate(inflater, container, false)
        AppLog.d("LiveGrid", "onCreateView src=$source t=${SystemClock.uptimeMillis()}")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!::adapter.isInitialized) {
            adapter =
                LiveRoomAdapter { position, room ->
                    if (!room.isLive) {
                        Toast.makeText(requireContext(), "未开播", Toast.LENGTH_SHORT).show()
                        return@LiveRoomAdapter
                    }
                    // Restore focus to the clicked card after returning from LivePlayerActivity.
                    pendingRestorePosition = position
                    // Returning from another Activity can take a few frames before RecyclerView lays out children.
                    pendingRestoreAttemptsLeft = 24
                    startActivity(
                        Intent(requireContext(), LivePlayerActivity::class.java)
                            .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                            .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                            .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
                    )
                }
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
                    if (isLoadingMore || endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) loadNextPage()
                }
            },
        )
        binding.recycler.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            val widthChanged = (right - left) != (oldRight - oldLeft)
            if (widthChanged) updateRecyclerSpanCountIfNeeded()
        }
        binding.recycler.post { updateRecyclerSpanCountIfNeeded() }
        dpadGridController?.release()
        dpadGridController =
            DpadGridController(
                recyclerView = binding.recycler,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            return if (enableTabFocus) {
                                focusSelectedTabIfAvailable()
                            } else {
                                focusBackButtonIfAvailable()
                            }
                        }

                        override fun onLeftEdge(): Boolean {
                            if (!enableTabFocus) {
                                focusBackButtonIfAvailable()
                                return true
                            }
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            if (enableTabFocus) switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { _binding != null && isResumed },
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
    }

    override fun onResume() {
        super.onResume()
        if (this::adapter.isInitialized) adapter.invalidateSizing()
        updateRecyclerSpanCountIfNeeded(force = true)
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
        loadedRoomIds.clear()
        endReached = false
        isLoadingMore = false
        page = 1
        requestToken++
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        isLoadingMore = true
        val startAt = SystemClock.uptimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fetched =
                    when (source) {
                        SRC_FOLLOWING -> {
                            val res = BiliApi.liveFollowing(page = page, pageSize = 10)
                            if (!res.hasMore) endReached = true
                            res.items
                        }
                        else -> BiliApi.liveRecommend(page = page)
                    }

                if (token != requestToken) return@launch

                val filtered = fetched.filter { loadedRoomIds.add(it.roomId) }

                if (filtered.isNotEmpty()) {
                    if (page == 1) adapter.submit(filtered) else adapter.append(filtered)
                }
                _binding?.recycler?.post {
                    restoreFocusIfNeeded()
                    maybeConsumePendingFocusFirstCard()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }

                if (source == SRC_RECOMMEND) {
                    // Conservative end guard.
                    if (fetched.isEmpty() || (filtered.isEmpty() && page >= 8)) {
                        // Conservative end guard.
                        endReached = true
                    }
                }

                page++
                AppLog.i("LiveGrid", "load ok src=$source page=${page - 1} add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("LiveGrid", "load failed src=$source page=$page", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (isRefresh && token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun spanCountForWidth(): Int {
        val override = blbl.cat3399.core.net.BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return autoSpanCountForWidthDp(widthDp)
    }

    private fun autoSpanCountForWidthDp(widthDp: Float): Int {
        val override = blbl.cat3399.core.net.BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val uiScale = UiScale.factor(requireContext())
        val minCardWidthDp = 210f * uiScale
        val auto = (widthDp / minCardWidthDp).toInt()
        return auto.coerceIn(2, 6)
    }

    private fun updateRecyclerSpanCountIfNeeded(force: Boolean = false) {
        val b = _binding ?: return
        val recycler = b.recycler
        val lm = recycler.layoutManager as? GridLayoutManager ?: return
        val contentWidthPx = (recycler.width - recycler.paddingLeft - recycler.paddingRight).coerceAtLeast(0)
        if (contentWidthPx <= 0) return
        if (!force && contentWidthPx == lastSpanCountContentWidthPx) return
        lastSpanCountContentWidthPx = contentWidthPx

        val dm = resources.displayMetrics
        val contentWidthDp = contentWidthPx / dm.density
        val span = autoSpanCountForWidthDp(contentWidthDp)
        if (span != lm.spanCount) lm.spanCount = span
    }

    override fun requestFocusFirstCardFromTab(): Boolean {
        pendingFocusFirstCardFromTab = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    override fun requestFocusFirstCardFromContentSwitch(): Boolean {
        pendingFocusFirstCardFromContentSwitch = true
        if (!isResumed) return true
        return maybeConsumePendingFocusFirstCard()
    }

    private fun maybeConsumePendingFocusFirstCard(): Boolean {
        if (!pendingFocusFirstCardFromTab && !pendingFocusFirstCardFromContentSwitch) return false
        if (!isAdded || _binding == null) return false
        if (!isResumed) return false

        val focused = activity?.currentFocus
        if (focused != null && focused != binding.recycler && isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstCardFromTab = false
            pendingFocusFirstCardFromContentSwitch = false
            return false
        }

        val parentView = parentFragment?.view
        val tabLayout =
            parentView?.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout)
        if (pendingFocusFirstCardFromTab) {
            if (focused == null || tabLayout == null || !isDescendantOf(focused, tabLayout)) {
                pendingFocusFirstCardFromTab = false
            }
        }

        if (!this::adapter.isInitialized) return false
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return true
        }

        val recycler = binding.recycler
        recycler.post outer@{
            if (_binding == null) return@outer
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstCardFromTab = false
                pendingFocusFirstCardFromContentSwitch = false
                return@outer
            }
            recycler.scrollToPosition(0)
            recycler.post inner@{
                if (_binding == null) return@inner
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                pendingFocusFirstCardFromTab = false
                pendingFocusFirstCardFromContentSwitch = false
            }
        }
        return true
    }

    private fun restoreFocusIfNeeded(): Boolean {
        // Mirror the working "MyFavFoldersFragment"/"MyBangumiFollowFragment" restore pattern:
        // keep a pending position, scrollToPosition, then requestFocus on that ViewHolder.
        val pos = pendingRestorePosition ?: return false
        if (_binding == null) return false
        if (!this::adapter.isInitialized) return false

        val itemCount = adapter.itemCount
        if (pos < 0 || (itemCount > 0 && pos >= itemCount)) {
            pendingRestorePosition = null
            pendingRestoreAttemptsLeft = 0
            return false
        }

        val recycler = binding.recycler
        // Do not "give up" just because some other view currently holds focus (e.g. app menu / back button).
        // Returning from another Activity can reset focus unexpectedly; our goal is to bring it back to the grid.

        // If data isn't ready yet, keep pending. Make sure focus doesn't "disappear" completely.
        if (itemCount <= 0) {
            // Keep focus inside this page so the user doesn't accidentally navigate to other pages.
            focusBackButtonIfAvailable() || focusSelectedTabIfAvailable() || recycler.requestFocus()
            return false
        }

        // Fast-path: already laid out.
        recycler.findViewHolderForAdapterPosition(pos)?.itemView?.let { target ->
            if (target.requestFocus()) {
                pendingRestorePosition = null
                pendingRestoreAttemptsLeft = 0
                return true
            }
            // requestFocus can fail briefly right after returning from another Activity; keep pending and retry.
        }

        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            recycler.scrollToPosition(pos)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
                val vh = recycler.findViewHolderForAdapterPosition(pos)
                if (vh != null) {
                    if (vh.itemView.requestFocus()) {
                        pendingRestorePosition = null
                        pendingRestoreAttemptsLeft = 0
                        return@innerPost
                    }
                    // If focus request fails, keep pending and retry below.
                }

                // Still not laid out (vh == null) OR requestFocus failed; retry a few times.
                pendingRestoreAttemptsLeft--
                if (pendingRestoreAttemptsLeft <= 0) {
                    // Fallback: focus something visible (prefer the first card) so focus highlight shows.
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true ||
                        focusBackButtonIfAvailable() ||
                        focusSelectedTabIfAvailable() ||
                        recycler.requestFocus()
                    pendingRestorePosition = null
                    pendingRestoreAttemptsLeft = 0
                } else {
                    recycler.postDelayed({ restoreFocusIfNeeded() }, 16L)
                }
            }
        }
        return true
    }

    private fun focusSelectedTabIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        tabStrip.getChildAt(pos)?.requestFocus() ?: return false
        return true
    }

    private fun focusBackButtonIfAvailable(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val back = parentView.findViewById<View?>(blbl.cat3399.R.id.btn_back) ?: return false
        return back.requestFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        val parentView = parentFragment?.view ?: return false
        val tabLayout = parentView.findViewById<com.google.android.material.tabs.TabLayout?>(blbl.cat3399.R.id.tab_layout) ?: return false
        if (tabLayout.tabCount <= 1) return false
        val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= tabLayout.tabCount) return false
        tabLayout.getTabAt(next)?.select() ?: return false
        tabLayout.post {
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
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
        tabLayout.getTabAt(prev)?.select() ?: return false
        tabLayout.post {
            (parentFragment as? LiveGridTabSwitchFocusHost)?.requestFocusCurrentPageFirstCardFromContentSwitch()
                ?: tabStrip.getChildAt(prev)?.requestFocus()
        }
        return true
    }

    override fun onDestroyView() {
        initialLoadTriggered = false
        dpadGridController?.release()
        dpadGridController = null
        lastSpanCountContentWidthPx = -1
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_SOURCE = "source"
        private const val ARG_ENABLE_TAB_FOCUS = "enable_tab_focus"

        const val SRC_RECOMMEND = "recommend"
        const val SRC_FOLLOWING = "following"

        fun newRecommend() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_RECOMMEND) } }

        fun newFollowing() = LiveGridFragment().apply { arguments = Bundle().apply { putString(ARG_SOURCE, SRC_FOLLOWING) } }
    }
}
