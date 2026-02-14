package blbl.cat3399.feature.search

import blbl.cat3399.core.paging.PagedGridStateMachine

class SearchState {
    var defaultHint: String? = null
    var query: String = ""
    var history: List<String> = emptyList()

    var ignoreQueryTextChanges: Boolean = false

    var lastFocusedKeyPos: Int = 0
    var lastFocusedSuggestPos: Int = 0

    var lastAppliedUiScale: Float? = null

    var currentTabIndex: Int = SearchTab.Video.index

    var currentVideoOrder: VideoOrder = VideoOrder.TotalRank
    var currentLiveOrder: LiveOrder = LiveOrder.Online
    var currentUserOrder: UserOrder = UserOrder.Default

    var pendingFocusFirstResultCardFromTabSwitch: Boolean = false
    var pendingRestoreMediaPos: Int? = null

    var refreshUiToken: Int = 0

    private val tabHasMemory: BooleanArray = BooleanArray(SearchTab.entries.size)

    val loadedBvids: HashSet<String> = HashSet()
    val loadedBangumiSeasonIds: HashSet<Long> = HashSet()
    val loadedMediaSeasonIds: HashSet<Long> = HashSet()
    val loadedRoomIds: HashSet<Long> = HashSet()
    val loadedMids: HashSet<Long> = HashSet()

    val videoPaging: PagedGridStateMachine<Int> = PagedGridStateMachine(initialKey = 1)
    val bangumiPaging: PagedGridStateMachine<Int> = PagedGridStateMachine(initialKey = 1)
    val mediaPaging: PagedGridStateMachine<Int> = PagedGridStateMachine(initialKey = 1)
    val livePaging: PagedGridStateMachine<Int> = PagedGridStateMachine(initialKey = 1)
    val userPaging: PagedGridStateMachine<Int> = PagedGridStateMachine(initialKey = 1)

    fun tabForIndex(index: Int): SearchTab = SearchTab.forIndex(index)

    fun pagingForTab(index: Int): PagedGridStateMachine<Int> =
        when (tabForIndex(index)) {
            SearchTab.Video -> videoPaging
            SearchTab.Bangumi -> bangumiPaging
            SearchTab.Media -> mediaPaging
            SearchTab.Live -> livePaging
            SearchTab.User -> userPaging
        }

    fun hasMemoryForTab(index: Int): Boolean {
        return index in tabHasMemory.indices && tabHasMemory[index]
    }

    fun markMemoryForTab(index: Int) {
        if (index !in tabHasMemory.indices) return
        tabHasMemory[index] = true
    }

    fun clearAllTabMemories() {
        tabHasMemory.fill(false)
    }

    fun clearLoadedForTab(index: Int) {
        when (tabForIndex(index)) {
            SearchTab.Video -> loadedBvids.clear()
            SearchTab.Bangumi -> loadedBangumiSeasonIds.clear()
            SearchTab.Media -> loadedMediaSeasonIds.clear()
            SearchTab.Live -> loadedRoomIds.clear()
            SearchTab.User -> loadedMids.clear()
        }
    }
}
