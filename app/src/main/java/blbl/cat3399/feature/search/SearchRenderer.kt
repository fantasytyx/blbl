package blbl.cat3399.feature.search

import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.paging.PagedGridStateMachine
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.ui.doOnPreDrawIfAlive
import blbl.cat3399.core.ui.enableDpadTabFocus
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.databinding.FragmentSearchBinding
import blbl.cat3399.feature.following.FollowingGridAdapter
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.live.LiveRoomAdapter
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

class SearchRenderer(
    private val fragment: SearchFragment,
    private val binding: FragmentSearchBinding,
    private val state: SearchState,
    private val interactor: SearchInteractor,
) {
    private val viewContext: Context = binding.root.context
    private var released: Boolean = false

    val keyAdapter: SearchKeyAdapter =
        SearchKeyAdapter { key ->
            interactor.onKeyClicked(key)
        }

    val suggestAdapter: SearchSuggestAdapter =
        SearchSuggestAdapter { keyword ->
            interactor.onKeywordClicked(keyword)
        }

    val hotAdapter: SearchHotAdapter =
        SearchHotAdapter { keyword ->
            interactor.onKeywordClicked(keyword)
        }

    lateinit var videoAdapter: VideoCardAdapter

    val mediaAdapter: BangumiFollowAdapter =
        BangumiFollowAdapter { position, season ->
            state.pendingRestoreMediaPos = position
            val isDrama = state.tabForIndex(state.currentTabIndex) == SearchTab.Media
            fragment.openBangumiDetail(season = season, isDrama = isDrama)
        }

    val liveAdapter: LiveRoomAdapter =
        LiveRoomAdapter { _, room ->
            if (!room.isLive) {
                AppToast.show(viewContext, "未开播")
                return@LiveRoomAdapter
            }
            fragment.startActivity(
                Intent(viewContext, LivePlayerActivity::class.java)
                    .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                    .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                    .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
            )
        }

    val userAdapter: FollowingGridAdapter =
        FollowingGridAdapter { following ->
            fun openProfile() {
                fragment.startActivity(
                    Intent(viewContext, UpDetailActivity::class.java)
                        .putExtra(UpDetailActivity.EXTRA_MID, following.mid)
                        .putExtra(UpDetailActivity.EXTRA_NAME, following.name)
                        .putExtra(UpDetailActivity.EXTRA_AVATAR, following.avatarUrl)
                        .putExtra(UpDetailActivity.EXTRA_SIGN, following.sign),
                )
            }

            fun openLive() {
                val rid = following.liveRoomId.takeIf { it > 0L } ?: return
                fragment.startActivity(
                    Intent(viewContext, LivePlayerActivity::class.java)
                        .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, rid)
                        .putExtra(LivePlayerActivity.EXTRA_TITLE, "")
                        .putExtra(LivePlayerActivity.EXTRA_UNAME, following.name),
                )
            }

            if (following.isLive && following.liveRoomId > 0L) {
                blbl.cat3399.core.ui.SingleChoiceDialog.show(
                    context = viewContext,
                    title = viewContext.getString(R.string.search_user_live_actions_title, following.name),
                    items =
                        listOf(
                            viewContext.getString(R.string.search_user_action_enter_live),
                            viewContext.getString(R.string.search_user_action_open_profile),
                        ),
                    checkedIndex = 0,
                    negativeText = viewContext.getString(android.R.string.cancel),
                ) { which, _ ->
                    when (which) {
                        0 -> openLive()
                        else -> openProfile()
                    }
                }
            } else {
                openProfile()
            }
        }

    private var resultsGridController: DpadGridController? = null
    private var pendingTabTextScaleFix: Boolean = false

    init {
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
                    val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "Search")
                    if (BiliClient.prefs.playerOpenDetailBeforePlay) {
                        fragment.startActivity(
                            Intent(viewContext, VideoDetailActivity::class.java)
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
                        fragment.startActivity(
                            Intent(viewContext, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                                .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                        )
                    }
                },
                onLongClick = { card, _ ->
                    fragment.openUpDetailFromVideoCard(card)
                    true
                },
            )
    }

    fun setupInput() {
        setupQueryInput()

        binding.recyclerKeys.adapter = keyAdapter
        binding.recyclerKeys.layoutManager = GridLayoutManager(viewContext, 6)
        (binding.recyclerKeys.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        keyAdapter.submit(KEYS)
        binding.recyclerKeys.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.onFocusChangeListener =
                        View.OnFocusChangeListener { v, hasFocus ->
                            if (!hasFocus) return@OnFocusChangeListener
                            val holder = binding.recyclerKeys.findContainingViewHolder(v) ?: return@OnFocusChangeListener
                            val pos =
                                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: return@OnFocusChangeListener
                            state.lastFocusedKeyPos = pos
                        }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.onFocusChangeListener = null
                }
            },
        )

        binding.recyclerSuggest.adapter = suggestAdapter
        binding.recyclerSuggest.layoutManager =
            StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        (binding.recyclerSuggest.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerSuggest.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerSuggest.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                focusLastKey()
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0) return@setOnKeyListener true
                                // Top edge: don't escape to sidebar.
                                if (!binding.recyclerSuggest.canScrollVertically(-1)) {
                                    val lm =
                                        binding.recyclerSuggest.layoutManager as? StaggeredGridLayoutManager
                                            ?: return@setOnKeyListener false
                                    val first = IntArray(lm.spanCount)
                                    lm.findFirstVisibleItemPositions(first)
                                    if (first.any { it == pos }) return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                val last = (binding.recyclerSuggest.adapter?.itemCount ?: 0) - 1
                                if (pos != last) return@setOnKeyListener false
                                if (binding.btnClearHistory.visibility == View.VISIBLE) {
                                    binding.btnClearHistory.requestFocus()
                                    return@setOnKeyListener true
                                }
                                // Bottom edge: don't escape to sidebar.
                                true
                            }

                            else -> false
                        }
                    }

                    view.onFocusChangeListener =
                        View.OnFocusChangeListener { v, hasFocus ->
                            if (!hasFocus) return@OnFocusChangeListener
                            val holder = binding.recyclerSuggest.findContainingViewHolder(v) ?: return@OnFocusChangeListener
                            val pos =
                                holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                    ?: return@OnFocusChangeListener
                            state.lastFocusedSuggestPos = pos
                        }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                    view.onFocusChangeListener = null
                }
            },
        )

        binding.recyclerHot.adapter = hotAdapter
        binding.recyclerHot.layoutManager =
            StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
        (binding.recyclerHot.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerHot.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        val holder = binding.recyclerHot.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos =
                            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                                ?: return@setOnKeyListener false

                        when (keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (!focusHistoryAt(pos) && !focusLastHistory()) focusLastKey()
                                true
                            }

                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (pos == 0) return@setOnKeyListener true
                                // Top edge: don't escape to sidebar.
                                if (!binding.recyclerHot.canScrollVertically(-1)) {
                                    val lm =
                                        binding.recyclerHot.layoutManager as? StaggeredGridLayoutManager
                                            ?: return@setOnKeyListener false
                                    val first = IntArray(lm.spanCount)
                                    lm.findFirstVisibleItemPositions(first)
                                    if (first.any { it == pos }) return@setOnKeyListener true
                                }
                                false
                            }

                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                // Bottom edge: don't escape to sidebar.
                                val last = (binding.recyclerHot.adapter?.itemCount ?: 0) - 1
                                if (pos != last) return@setOnKeyListener false
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

        binding.btnClear.setOnClickListener {
            interactor.setQuery("")
        }
        binding.btnClear.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            binding.tvQuery.requestFocus()
            true
        }

        binding.btnBackspace.setOnClickListener {
            val query = state.query
            if (query.isNotEmpty()) interactor.setQuery(query.dropLast(1))
        }
        binding.btnBackspace.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            binding.tvQuery.requestFocus()
            true
        }

        binding.btnSearch.setOnClickListener { interactor.performSearch() }
        binding.btnSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@setOnKeyListener false
            // Bottom edge: don't escape to sidebar.
            true
        }

        binding.btnClearHistory.setOnClickListener {
            interactor.clearHistory()
        }
        binding.btnClearHistory.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> focusLastHistoryItem()
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    focusLastKey()
                    true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Bottom edge: don't escape to sidebar.
                    true
                }

                else -> false
            }
        }

        updateQueryUi()
        updateMiddleUi(history = emptyList(), extra = emptyList())
        updateClearHistoryButton(state.query)
    }

    private fun setupQueryInput() {
        val input = binding.tvQuery

        var imeEditMode = false

        input.setOnEditorActionListener { _, actionId, event ->
            val isEnter =
                event != null &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || isEnter) {
                interactor.performSearch()
                true
            } else {
                false
            }
        }

        input.doAfterTextChanged {
            if (state.ignoreQueryTextChanges) return@doAfterTextChanged
            interactor.onQueryTextChangedFromIme(it?.toString().orEmpty())
        }

        fun enterImeEditMode() {
            if (isResultsVisible()) showInput()
            imeEditMode = true

            input.showSoftInputOnFocus = true
            input.isCursorVisible = true
            input.isLongClickable = true
            input.setTextIsSelectable(true)

            input.requestFocus()
            input.setSelection(input.text?.length ?: 0)
            // Some TV input methods won't show the IME synchronously; post for reliability.
            input.postIfAlive(isAlive = { !released }) {
                if (!input.isFocused) input.requestFocus()
                input.setSelection(input.text?.length ?: 0)
                showIme(input)
            }
        }

        fun exitImeEditMode() {
            imeEditMode = false
            input.showSoftInputOnFocus = false
            input.isCursorVisible = false
            input.isLongClickable = false
            input.setTextIsSelectable(false)
            hideIme(input)
        }

        input.apply {
            // Default mode: on-screen keyboard + DPAD navigation.
            // Allow DPAD focus/click on the input, but keep IME disabled unless explicitly opened.
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = false
            isLongClickable = false
            setTextIsSelectable(false)
            showSoftInputOnFocus = false
        }

        input.setOnClickListener { enterImeEditMode() }
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) exitImeEditMode()
        }
        input.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    enterImeEditMode()
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (imeEditMode) return@setOnKeyListener false
                    // Prevent focus escaping to sidebar.
                    if (binding.panelInput.visibility == View.VISIBLE) {
                        focusLastKey()
                        true
                    } else {
                        false
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    // Top edge: don't escape to sidebar.
                    true
                }

                else -> false
            }
        }
    }

    fun setupResults() {
        binding.recyclerResults.adapter = adapterForTab(state.currentTabIndex)
        binding.recyclerResults.setHasFixedSize(true)
        binding.recyclerResults.layoutManager = GridLayoutManager(viewContext, spanCountForCurrentTab())
        (binding.recyclerResults.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        resultsGridController?.release()
        resultsGridController =
            DpadGridController(
                recyclerView = binding.recyclerResults,
                callbacks =
                    object : DpadGridController.Callbacks {
                        override fun onTopEdge(): Boolean {
                            focusSelectedTab()
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            return switchToPrevTabFromContentEdge()
                        }

                        override fun onRightEdge() {
                            switchToNextTabFromContentEdge()
                        }

                        override fun canLoadMore(): Boolean = !pagingForCurrentTab().snapshot().endReached

                        override fun loadMore() {
                            interactor.loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = {
                            fragment.isResumed && isResultsVisible()
                        },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }

        binding.recyclerResults.clearOnScrollListeners()
        binding.recyclerResults.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    val s = pagingForCurrentTab().snapshot()
                    if (s.isLoading || s.endReached) return
                    val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = recyclerView.adapter?.itemCount ?: 0
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 8) interactor.loadNextPage()
                }
            },
        )

        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_video))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_bangumi))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_media))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_live))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.search_tab_user))

        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !released }) {
            tabLayout.enableDpadTabFocus(selectOnFocus = false)
            val tabStrip = tabLayout.getChildAt(0) as? ViewGroup ?: return@postIfAlive
            for (i in 0 until tabStrip.childCount) {
                tabStrip.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            binding.tvQuery.requestFocus()
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            focusFirstResultCardFromTab()
                            true
                        }

                        else -> false
                    }
                }
            }
        }

        binding.tabLayout.addOnTabSelectedListener(
            object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    interactor.onTabSelected(tab.position)
                    scheduleTabTextScaleFix()
                }

                override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    scheduleTabTextScaleFix()
                }

                override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                    interactor.onTabReselected(tab.position)
                    scheduleTabTextScaleFix()
                }
            },
        )

        binding.btnSort.setOnClickListener { interactor.showSortDialog() }
        binding.btnSort.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false
            binding.tvQuery.requestFocus()
            true
        }
        updateSortUi()

        binding.swipeRefresh.setOnRefreshListener { interactor.resetAndLoad() }
    }

    private fun scheduleTabTextScaleFix() {
        if (released) return
        if (pendingTabTextScaleFix) return
        pendingTabTextScaleFix = true
        val tabLayout = binding.tabLayout
        tabLayout.doOnPreDrawIfAlive(isAlive = { !released }) {
            pendingTabTextScaleFix = false
            enforceTabTextScale()
        }
        tabLayout.invalidate()
    }

    private fun enforceTabTextScale() {
        if (released) return
        val scale = UiScale.factor(viewContext)
        val basePx =
            viewContext.obtainStyledAttributes(R.style.TextAppearance_Blbl_Tab, intArrayOf(android.R.attr.textSize)).run {
                try {
                    getDimension(0, 0f)
                } finally {
                    recycle()
                }
            }
        if (basePx <= 0f) return
        val expectedPx = (basePx * scale).coerceAtLeast(1f)

        fun findFirstTextView(view: View): TextView? {
            if (view is TextView) return view
            val group = view as? ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                val found = findFirstTextView(group.getChildAt(i))
                if (found != null) return found
            }
            return null
        }

        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until tabStrip.childCount) {
            val tabView = tabStrip.getChildAt(i)
            val tv = findFirstTextView(tabView) ?: continue
            if (tv.textSize != expectedPx) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, expectedPx)
        }
    }

    fun onResume() {
        videoAdapter.invalidateSizing()
        keyAdapter.invalidateSizing()
        suggestAdapter.invalidateSizing()
        hotAdapter.invalidateSizing()
        mediaAdapter.invalidateSizing()
        liveAdapter.invalidateSizing()
        userAdapter.invalidateSizing()

        applyUiScale()
        (binding.recyclerResults.layoutManager as? GridLayoutManager)?.spanCount = spanCountForCurrentTab()
        maybeConsumePendingFocusFirstResultCardFromTabSwitch()
        restoreMediaFocusIfNeeded()
        scheduleTabTextScaleFix()
    }

    fun onShown() {
        // When SearchFragment is hidden via FragmentTransaction.hide(), it stays resumed.
        // Popping the detail fragment makes it visible again without triggering onResume().
        maybeConsumePendingFocusFirstResultCardFromTabSwitch()
        restoreMediaFocusIfNeeded()
        scheduleTabTextScaleFix()
    }

    fun release() {
        if (released) return
        released = true
        pendingTabTextScaleFix = false
        resultsGridController?.release()
        resultsGridController = null
    }

    fun isResultsVisible(): Boolean = binding.panelResults.visibility == View.VISIBLE

    fun showInput() {
        binding.panelResults.visibility = View.GONE
        binding.panelInput.visibility = View.VISIBLE
    }

    fun showResults() {
        binding.panelInput.visibility = View.GONE
        binding.panelResults.visibility = View.VISIBLE
    }

    fun setRefreshing(refreshing: Boolean) {
        binding.swipeRefresh.isRefreshing = refreshing
    }

    fun scrollResultsToTop() {
        binding.recyclerResults.scrollToPosition(0)
    }

    fun clearPendingFocusAfterLoadMore() {
        resultsGridController?.clearPendingFocusAfterLoadMore()
    }

    fun onResultsApplied() {
        binding.recyclerResults.postIfAlive(isAlive = { !released }) {
            maybeConsumePendingFocusFirstResultCardFromTabSwitch()
            resultsGridController?.consumePendingFocusAfterLoadMore()
        }
    }

    fun updateQueryUi() {
        val hintText = state.defaultHint ?: viewContext.getString(R.string.tab_search)
        binding.tvQuery.hint = hintText
        updateQueryAlpha(query = state.query)

        val current = binding.tvQuery.text?.toString().orEmpty()
        if (current == state.query) return
        state.ignoreQueryTextChanges = true
        binding.tvQuery.setText(state.query)
        if (binding.tvQuery.hasFocus()) {
            binding.tvQuery.setSelection(binding.tvQuery.text?.length ?: 0)
        }
        state.ignoreQueryTextChanges = false
    }

    fun updateQueryAlpha(query: String) {
        binding.tvQuery.alpha = if (query.isBlank()) 0.65f else 1f
    }

    fun updateMiddleUi(history: List<String>, extra: List<String>) {
        val merged = LinkedHashMap<String, String>()
        for (s in history) {
            val key = s.trim().lowercase()
            if (key.isBlank()) continue
            if (merged[key] == null) merged[key] = s
        }
        for (s in extra) {
            val key = s.trim().lowercase()
            if (key.isBlank()) continue
            if (merged[key] == null) merged[key] = s
        }
        val list = merged.values.toList()
        binding.recyclerSuggest.visibility = if (list.isNotEmpty()) View.VISIBLE else View.INVISIBLE
        suggestAdapter.submit(list)
    }

    fun updateClearHistoryButton(term: String) {
        val show = term.isBlank() && state.history.isNotEmpty()
        binding.btnClearHistory.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun hideImeAndClearQueryFocusIfNeeded() {
        if (!binding.tvQuery.hasFocus()) return
        hideIme(binding.tvQuery)
        binding.tvQuery.clearFocus()
    }

    private fun showIme(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideIme(view: View) {
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun focusFirstKey() {
        val recycler = binding.recyclerKeys
        recycler.postIfAlive(isAlive = { !released }) {
            recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: recycler.postIfAlive(isAlive = { !released }) {
                    recycler.scrollToPosition(0)
                    recycler.postIfAlive(isAlive = { !released }) {
                        recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
        }
    }

    private fun focusSelectedTab(): Boolean {
        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val pos = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val tabView = tabStrip.getChildAt(pos) ?: return false
        tabView.requestFocus()
        return true
    }

    private fun focusFirstResultCardFromTab(): Boolean {
        if (!isResultsVisible()) return false
        state.pendingFocusFirstResultCardFromTabSwitch = true
        if (!fragment.isResumed) return true
        return maybeConsumePendingFocusFirstResultCardFromTabSwitch()
    }

    private fun requestFocusResultsContentFromTabSwitch(): Boolean {
        if (!isResultsVisible()) return false
        return requestFocusFirstResultCardFromTabSwitch()
    }

    private fun requestFocusFirstResultCardFromTabSwitch(): Boolean {
        state.pendingFocusFirstResultCardFromTabSwitch = true
        if (!fragment.isResumed) return true
        return maybeConsumePendingFocusFirstResultCardFromTabSwitch()
    }

    private fun maybeConsumePendingFocusFirstResultCardFromTabSwitch(): Boolean {
        if (!state.pendingFocusFirstResultCardFromTabSwitch) return false
        if (!fragment.isAdded || !fragment.isResumed) return false
        if (!isResultsVisible()) {
            state.pendingFocusFirstResultCardFromTabSwitch = false
            return false
        }

        val focused = fragment.activity?.currentFocus
        if (focused != null && FocusTreeUtils.isDescendantOf(focused, binding.recyclerResults) && focused != binding.recyclerResults) {
            state.pendingFocusFirstResultCardFromTabSwitch = false
            return false
        }

        val adapter = binding.recyclerResults.adapter
        if (adapter == null || adapter.itemCount <= 0) {
            binding.recyclerResults.requestFocus()
            return true
        }

        val recycler = binding.recyclerResults
        val isUiAlive = { !released && fragment.isAdded && fragment.isResumed }
        recycler.postIfAlive(isAlive = isUiAlive) {
            val vh = recycler.findViewHolderForAdapterPosition(0)
            if (vh != null) {
                vh.itemView.requestFocus()
                state.pendingFocusFirstResultCardFromTabSwitch = false
                return@postIfAlive
            }
            recycler.scrollToPosition(0)
            recycler.postIfAlive(isAlive = isUiAlive) {
                recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() ?: recycler.requestFocus()
                state.pendingFocusFirstResultCardFromTabSwitch = false
            }
        }
        return true
    }

    private fun switchToNextTabFromContentEdge(): Boolean {
        if (!isResultsVisible()) return false
        if (binding.tabLayout.tabCount <= 1) return false
        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val next = cur + 1
        if (next >= binding.tabLayout.tabCount) return false
        binding.tabLayout.getTabAt(next)?.select() ?: return false
        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !released }) {
            requestFocusResultsContentFromTabSwitch() || (tabStrip.getChildAt(next)?.requestFocus() == true)
        }
        return true
    }

    private fun switchToPrevTabFromContentEdge(): Boolean {
        if (!isResultsVisible()) return false
        if (binding.tabLayout.tabCount <= 1) return false
        val tabStrip = binding.tabLayout.getChildAt(0) as? ViewGroup ?: return false
        val cur = binding.tabLayout.selectedTabPosition.takeIf { it >= 0 } ?: 0
        val prev = cur - 1
        if (prev < 0) return false
        binding.tabLayout.getTabAt(prev)?.select() ?: return false
        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !released }) {
            requestFocusResultsContentFromTabSwitch() || (tabStrip.getChildAt(prev)?.requestFocus() == true)
        }
        return true
    }

    private fun focusKeyAt(pos: Int): Boolean {
        val count = binding.recyclerKeys.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val safePos = pos.coerceIn(0, count - 1)
        val recycler = binding.recyclerKeys
        recycler.scrollToPosition(safePos)
        recycler.postIfAlive(isAlive = { !released }) {
            recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastKey(): Boolean = focusKeyAt(state.lastFocusedKeyPos).also { if (!it) focusFirstKey() }

    private fun focusHistoryAt(pos: Int): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val safePos = pos.coerceIn(0, count - 1)
        val recycler = binding.recyclerSuggest
        recycler.scrollToPosition(safePos)
        recycler.postIfAlive(isAlive = { !released }) {
            recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus()
        }
        return true
    }

    private fun focusLastHistory(): Boolean = focusHistoryAt(state.lastFocusedSuggestPos)

    private fun focusLastHistoryItem(): Boolean {
        val count = binding.recyclerSuggest.adapter?.itemCount ?: return false
        if (count <= 0) return false
        val last = count - 1
        val recycler = binding.recyclerSuggest
        recycler.scrollToPosition(last)
        recycler.postIfAlive(isAlive = { !released }) {
            recycler.findViewHolderForAdapterPosition(last)?.itemView?.requestFocus()
        }
        return true
    }

    fun focusSelectedTabAfterShow() {
        val tabLayout = binding.tabLayout
        tabLayout.postIfAlive(isAlive = { !released }) {
            if (isResultsVisible()) {
                focusSelectedTab()
            }
        }
    }

    fun switchTab(pos: Int) {
        if (!isResultsVisible()) return
        binding.recyclerResults.adapter = adapterForTab(pos)
        (binding.recyclerResults.layoutManager as? GridLayoutManager)?.spanCount = spanCountForTab(pos)
        binding.recyclerResults.scrollToPosition(0)
        updateSortUi()

        binding.tvResultsPlaceholder.visibility = View.GONE
        binding.swipeRefresh.visibility = View.VISIBLE
    }

    fun clearResultsForTab(index: Int) {
        when (state.tabForIndex(index)) {
            SearchTab.Video -> videoAdapter.submit(emptyList())
            SearchTab.Bangumi -> mediaAdapter.submit(emptyList())
            SearchTab.Media -> mediaAdapter.submit(emptyList())
            SearchTab.Live -> liveAdapter.submit(emptyList())
            SearchTab.User -> userAdapter.submit(emptyList())
        }
    }

    private fun adapterForTab(index: Int): RecyclerView.Adapter<*> =
        when (state.tabForIndex(index)) {
            SearchTab.Video -> videoAdapter
            SearchTab.Bangumi -> mediaAdapter
            SearchTab.Media -> mediaAdapter
            SearchTab.Live -> liveAdapter
            SearchTab.User -> userAdapter
        }

    private fun spanCountForTab(index: Int): Int =
        when (state.tabForIndex(index)) {
            SearchTab.Bangumi,
            SearchTab.Media,
            -> spanCountForBangumi()

            else -> spanCountForWidth()
        }

    private fun spanCountForCurrentTab(): Int = spanCountForTab(state.currentTabIndex)

    private fun spanCountForBangumi(): Int {
        return BiliClient.prefs.pgcGridSpanCount.coerceIn(1, 6)
    }

    private fun spanCountForWidth(): Int {
        val dm = binding.root.resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    private fun pagingForCurrentTab(): PagedGridStateMachine<Int> = state.pagingForTab(state.currentTabIndex)

    fun updateSortUi() {
        when (state.tabForIndex(state.currentTabIndex)) {
            SearchTab.Video -> {
                binding.btnSort.visibility = View.VISIBLE
                binding.tvSort.text = viewContext.getString(state.currentVideoOrder.labelRes)
            }

            SearchTab.Bangumi,
            SearchTab.Media,
            -> {
                // Keep layout space so TabLayout width doesn't jump across tabs.
                binding.btnSort.visibility = View.INVISIBLE
            }

            SearchTab.Live -> {
                binding.btnSort.visibility = View.VISIBLE
                binding.tvSort.text = viewContext.getString(state.currentLiveOrder.labelRes)
            }

            SearchTab.User -> {
                binding.btnSort.visibility = View.VISIBLE
                binding.tvSort.text = viewContext.getString(state.currentUserOrder.labelRes)
            }
        }
    }

    private fun restoreMediaFocusIfNeeded() {
        val pos = state.pendingRestoreMediaPos ?: return
        if (!fragment.isResumed) return
        val tab = state.tabForIndex(state.currentTabIndex)
        if (tab != SearchTab.Bangumi && tab != SearchTab.Media) return
        if (!isResultsVisible()) return
        val adapter = binding.recyclerResults.adapter ?: return
        if (adapter.itemCount <= 0) return
        val safePos = pos.coerceIn(0, adapter.itemCount - 1)

        val recycler = binding.recyclerResults
        recycler.postIfAlive(isAlive = { !released }) {
            val focusedNow = recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus() == true
            if (focusedNow) {
                state.pendingRestoreMediaPos = null
                return@postIfAlive
            }
            recycler.scrollToPosition(safePos)
            recycler.postIfAlive(isAlive = { !released }) {
                val focusedAfterScroll = recycler.findViewHolderForAdapterPosition(safePos)?.itemView?.requestFocus() == true
                if (focusedAfterScroll) state.pendingRestoreMediaPos = null
            }
        }
    }

    fun applyUiScale() {
        val newScale = UiScale.factor(viewContext)
        val oldScale = state.lastAppliedUiScale ?: 1.0f
        if (newScale == oldScale) return

        fun rescalePx(valuePx: Int): Int = (valuePx.toFloat() / oldScale * newScale).roundToInt()
        fun rescalePxF(valuePx: Float): Float = (valuePx / oldScale * newScale)

        fun rescaleLayoutSize(view: View, width: Boolean = true, height: Boolean = true) {
            val lp = view.layoutParams ?: return
            var changed = false
            if (width && lp.width > 0) {
                val w = rescalePx(lp.width).coerceAtLeast(1)
                if (lp.width != w) {
                    lp.width = w
                    changed = true
                }
            }
            if (height && lp.height > 0) {
                val h = rescalePx(lp.height).coerceAtLeast(1)
                if (lp.height != h) {
                    lp.height = h
                    changed = true
                }
            }
            if (changed) view.layoutParams = lp
        }

        fun rescaleMargins(view: View, start: Boolean = true, top: Boolean = true, end: Boolean = true, bottom: Boolean = true) {
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            var changed = false
            if (start) {
                val ms = rescalePx(lp.marginStart).coerceAtLeast(0)
                if (lp.marginStart != ms) {
                    lp.marginStart = ms
                    changed = true
                }
            }
            if (top) {
                val mt = rescalePx(lp.topMargin).coerceAtLeast(0)
                if (lp.topMargin != mt) {
                    lp.topMargin = mt
                    changed = true
                }
            }
            if (end) {
                val me = rescalePx(lp.marginEnd).coerceAtLeast(0)
                if (lp.marginEnd != me) {
                    lp.marginEnd = me
                    changed = true
                }
            }
            if (bottom) {
                val mb = rescalePx(lp.bottomMargin).coerceAtLeast(0)
                if (lp.bottomMargin != mb) {
                    lp.bottomMargin = mb
                    changed = true
                }
            }
            if (changed) view.layoutParams = lp
        }

        fun rescalePadding(view: View, left: Boolean = true, top: Boolean = true, right: Boolean = true, bottom: Boolean = true) {
            val l = if (left) rescalePx(view.paddingLeft).coerceAtLeast(0) else view.paddingLeft
            val t = if (top) rescalePx(view.paddingTop).coerceAtLeast(0) else view.paddingTop
            val r = if (right) rescalePx(view.paddingRight).coerceAtLeast(0) else view.paddingRight
            val btm = if (bottom) rescalePx(view.paddingBottom).coerceAtLeast(0) else view.paddingBottom
            if (l != view.paddingLeft || t != view.paddingTop || r != view.paddingRight || btm != view.paddingBottom) {
                view.setPadding(l, t, r, btm)
            }
        }

        fun rescaleTextSize(textView: TextView) {
            val px = rescalePxF(textView.textSize).coerceAtLeast(1f)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        }

        fun rescaleCard(card: MaterialCardView) {
            val radius = (card.radius / oldScale * newScale).coerceAtLeast(0f)
            if (card.radius != radius) card.radius = radius
            val stroke = (card.strokeWidth.toFloat() / oldScale * newScale).roundToInt().coerceAtLeast(0)
            if (card.strokeWidth != stroke) card.strokeWidth = stroke
        }

        fun findFirstTextView(view: View): TextView? {
            if (view is TextView) return view
            val group = view as? ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                val found = findFirstTextView(group.getChildAt(i))
                if (found != null) return found
            }
            return null
        }

        rescaleLayoutSize(binding.ivSearch, width = true, height = true)
        rescaleMargins(binding.ivSearch, start = true, top = true, end = false, bottom = false)

        rescaleLayoutSize(binding.tvQuery, width = false, height = true)
        rescaleMargins(binding.tvQuery, start = true, top = false, end = true, bottom = false)
        rescalePadding(binding.tvQuery, left = true, top = true, right = true, bottom = true)
        rescaleTextSize(binding.tvQuery)

        rescaleMargins(binding.panelInput, start = false, top = true, end = false, bottom = false)
        rescaleMargins(binding.panelResults, start = false, top = true, end = false, bottom = false)

        rescaleMargins(binding.panelKeyboard, start = true, top = false, end = true, bottom = false)
        rescaleMargins(binding.recyclerKeys, start = false, top = true, end = false, bottom = false)

        listOf(binding.btnClear, binding.btnBackspace, binding.btnSearch, binding.btnClearHistory, binding.btnSort).forEach(::rescaleCard)

        listOf(binding.btnClear, binding.btnBackspace, binding.btnSearch, binding.btnClearHistory, binding.btnSort).forEach { btn ->
            rescaleLayoutSize(btn, width = false, height = true)
        }
        rescaleMargins(binding.btnClear, start = false, top = false, end = true, bottom = false)
        rescaleMargins(binding.btnSearch, start = false, top = true, end = false, bottom = false)
        rescaleMargins(binding.btnClearHistory, start = false, top = true, end = false, bottom = false)
        rescaleMargins(binding.btnSort, start = false, top = false, end = true, bottom = false)

        (binding.btnClear.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (binding.btnBackspace.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (binding.btnSearch.getChildAt(0) as? TextView)?.let(::rescaleTextSize)
        (binding.btnClearHistory.getChildAt(0) as? TextView)?.let(::rescaleTextSize)

        rescaleMargins(binding.panelHistory, start = false, top = false, end = true, bottom = false)

        rescalePadding(binding.recyclerSuggest, left = false, top = true, right = false, bottom = false)
        rescalePadding(binding.recyclerHot, left = false, top = true, right = false, bottom = false)
        rescaleMargins(binding.recyclerHot, start = false, top = false, end = true, bottom = false)

        rescaleMargins(binding.tabLayout, start = true, top = false, end = true, bottom = false)
        enforceTabTextScale()

        run {
            val sortContainer = binding.btnSort.getChildAt(0) as? ViewGroup
            if (sortContainer != null) {
                rescalePadding(sortContainer, left = true, top = false, right = true, bottom = false)
                (sortContainer.getChildAt(0) as? ImageView)?.let { icon ->
                    rescaleLayoutSize(icon, width = true, height = true)
                    rescaleMargins(icon, start = false, top = false, end = true, bottom = false)
                }
            }
            rescaleTextSize(binding.tvSort)
        }

        rescaleMargins(binding.swipeRefresh, start = true, top = false, end = true, bottom = false)
        rescalePadding(binding.recyclerResults, left = false, top = true, right = false, bottom = false)

        rescaleMargins(binding.tvResultsPlaceholder, start = false, top = true, end = false, bottom = false)
        rescaleTextSize(binding.tvResultsPlaceholder)

        state.lastAppliedUiScale = newScale
    }

    companion object {
        private val KEYS =
            listOf(
                "A", "B", "C", "D", "E", "F",
                "G", "H", "I", "J", "K", "L",
                "M", "N", "O", "P", "Q", "R",
                "S", "T", "U", "V", "W", "X",
                "Y", "Z", "1", "2", "3", "4",
                "5", "6", "7", "8", "9", "0",
            )
    }
}
