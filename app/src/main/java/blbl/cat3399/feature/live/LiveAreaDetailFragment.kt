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
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.FragmentLiveAreaDetailBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LiveAreaDetailFragment : Fragment() {
    private var _binding: FragmentLiveAreaDetailBinding? = null
    private val binding get() = _binding!!

    private val parentAreaId: Int by lazy { requireArguments().getInt(ARG_PARENT_AREA_ID, 0) }
    private val areaId: Int by lazy { requireArguments().getInt(ARG_AREA_ID, 0) }
    private val parentTitle: String by lazy { requireArguments().getString(ARG_PARENT_TITLE).orEmpty() }
    private val areaTitle: String by lazy { requireArguments().getString(ARG_AREA_TITLE).orEmpty() }

    private lateinit var adapter: LiveRoomAdapter
    private val loadedRoomIds = HashSet<Long>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var page: Int = 1
    private var requestToken: Int = 0
    private var lastSpanCountContentWidthPx: Int = -1

    private var initialLoadTriggered: Boolean = false
    private var pendingFocusFirstItem: Boolean = false
    private var pendingRestorePosition: Int? = null
    private var pendingRestoreAttemptsLeft: Int = 0
    private var dpadGridController: DpadGridController? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLiveAreaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvTitle.text =
            when {
                parentTitle.isBlank() -> areaTitle
                areaTitle.isBlank() -> parentTitle
                parentTitle == areaTitle -> areaTitle
                else -> "$parentTitle · $areaTitle"
            }
        applyBackButtonSizing()

        if (!::adapter.isInitialized) {
            adapter =
                LiveRoomAdapter { position, room ->
                    if (!room.isLive) {
                        Toast.makeText(requireContext(), "未开播", Toast.LENGTH_SHORT).show()
                        return@LiveRoomAdapter
                    }
                    pendingRestorePosition = position
                    pendingRestoreAttemptsLeft = 24
                    // Prioritize restoring to the clicked card after return.
                    pendingFocusFirstItem = false
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

        // Keep focus inside this detail page and provide a consistent edge behavior, similar to favorites detail.
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
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        if (savedInstanceState == null) {
            pendingFocusFirstItem = true
            binding.recycler.requestFocus()
        }
    }

    override fun onDestroyView() {
        dpadGridController?.release()
        dpadGridController = null
        _binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        applyBackButtonSizing()
        updateRecyclerSpanCountIfNeeded(force = true)
        maybeTriggerInitialLoad()
        // Make sure focus stays within this detail page on return.
        binding.root.post {
            if (_binding == null || !isAdded) return@post
            val cur = activity?.currentFocus
            if (cur == null || !isDescendantOf(cur, binding.root)) {
                binding.btnBack.requestFocus()
            }
        }
        binding.recycler.post {
            restoreFocusIfNeeded()
            maybeFocusFirstItem()
        }
    }

    private fun applyBackButtonSizing() {
        val b = _binding ?: return
        val sidebarScale = UiScale.factor(requireContext(), BiliClient.prefs.sidebarSize)
        fun px(id: Int): Int = b.root.resources.getDimensionPixelSize(id)
        fun scaledPx(id: Int): Int = (px(id) * sidebarScale).roundToInt().coerceAtLeast(0)

        val sizePx =
            scaledPx(blbl.cat3399.R.dimen.sidebar_settings_size_tv).coerceAtLeast(1)
        val padPx =
            scaledPx(blbl.cat3399.R.dimen.sidebar_settings_padding_tv)

        val lp = b.btnBack.layoutParams
        if (lp.width != sizePx || lp.height != sizePx) {
            lp.width = sizePx
            lp.height = sizePx
            b.btnBack.layoutParams = lp
        }
        if (
            b.btnBack.paddingLeft != padPx ||
            b.btnBack.paddingTop != padPx ||
            b.btnBack.paddingRight != padPx ||
            b.btnBack.paddingBottom != padPx
        ) {
            b.btnBack.setPadding(padPx, padPx, padPx, padPx)
        }
    }

    companion object {
        private const val ARG_PARENT_AREA_ID = "parent_area_id"
        private const val ARG_PARENT_TITLE = "parent_title"
        private const val ARG_AREA_ID = "area_id"
        private const val ARG_AREA_TITLE = "area_title"

        fun newInstance(parentAreaId: Int, parentTitle: String, areaId: Int, areaTitle: String): LiveAreaDetailFragment =
            LiveAreaDetailFragment().apply {
                arguments =
                    Bundle().apply {
                        putInt(ARG_PARENT_AREA_ID, parentAreaId)
                        putString(ARG_PARENT_TITLE, parentTitle)
                        putInt(ARG_AREA_ID, areaId)
                        putString(ARG_AREA_TITLE, areaTitle)
                    }
            }
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

    private fun spanCountForWidth(): Int {
        val override = BiliClient.prefs.gridSpanCount
        if (override > 0) return override.coerceIn(1, 6)
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return autoSpanCountForWidthDp(widthDp)
    }

    private fun autoSpanCountForWidthDp(widthDp: Float): Int {
        val override = BiliClient.prefs.gridSpanCount
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

    private fun resetAndLoad() {
        loadedRoomIds.clear()
        endReached = false
        isLoadingMore = false
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
        val startAt = SystemClock.uptimeMillis()
        lifecycleScope.launch {
            try {
                val pageSize = 30
                val fetched =
                    BiliApi.liveAreaRooms(
                        parentAreaId = parentAreaId,
                        areaId = areaId,
                        page = page,
                        pageSize = pageSize,
                    )
                if (token != requestToken) return@launch
                if (fetched.isEmpty() || fetched.size < pageSize) endReached = true
                val filtered = fetched.filter { loadedRoomIds.add(it.roomId) }
                if (filtered.isNotEmpty()) {
                    if (page == 1) adapter.submit(filtered) else adapter.append(filtered)
                }
                _binding?.recycler?.post { dpadGridController?.consumePendingFocusAfterLoadMore() }
                restoreFocusIfNeeded()
                maybeFocusFirstItem()
                page++
                AppLog.i("LiveAreaDetail", "load ok pid=$parentAreaId aid=$areaId page=${page - 1} add=${filtered.size} total=${adapter.itemCount} cost=${SystemClock.uptimeMillis() - startAt}ms")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("LiveAreaDetail", "load failed pid=$parentAreaId aid=$areaId page=$page", t)
                context?.let { Toast.makeText(it, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show() }
            } finally {
                if (isRefresh && token == requestToken) _binding?.swipeRefresh?.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun restoreFocusIfNeeded() {
        val pos = pendingRestorePosition ?: return
        val b = _binding ?: return
        if (!::adapter.isInitialized) return
        if (pos < 0 || pos >= adapter.itemCount) return
        val recycler = b.recycler

        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            recycler.scrollToPosition(pos)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
                val vh = recycler.findViewHolderForAdapterPosition(pos)
                if (vh != null && vh.itemView.requestFocus()) {
                    pendingRestorePosition = null
                    pendingRestoreAttemptsLeft = 0
                    return@innerPost
                }

                pendingRestoreAttemptsLeft--
                if (pendingRestoreAttemptsLeft <= 0) {
                    recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true || b.btnBack.requestFocus()
                    pendingRestorePosition = null
                    pendingRestoreAttemptsLeft = 0
                } else {
                    recycler.postDelayed({ restoreFocusIfNeeded() }, 16L)
                }
            }
        }
    }

    private fun maybeFocusFirstItem() {
        if (pendingRestorePosition != null) return
        if (!pendingFocusFirstItem) return
        val b = _binding ?: return
        if (!::adapter.isInitialized) return
        if (adapter.itemCount <= 0) return

        val recycler = b.recycler
        val focused = activity?.currentFocus
        if (focused != null && isDescendantOf(focused, recycler)) {
            pendingFocusFirstItem = false
            return
        }

        recycler.post outerPost@{
            if (_binding == null) return@outerPost
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                pendingFocusFirstItem = false
                return@outerPost
            }
            recycler.scrollToPosition(0)
            recycler.post innerPost@{
                if (_binding == null) return@innerPost
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() == true || b.btnBack.requestFocus()
                pendingFocusFirstItem = false
            }
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == ancestor) return true
            current = current.parent as? View
        }
        return false
    }
}
