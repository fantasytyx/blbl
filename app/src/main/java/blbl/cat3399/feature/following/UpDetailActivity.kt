package blbl.cat3399.feature.following

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.ActivityStackLimiter
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.FocusTreeUtils
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityUpDetailBinding
import blbl.cat3399.feature.login.QrLoginActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.video.VideoDetailActivity
import blbl.cat3399.feature.video.VideoCardAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class UpDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityUpDetailBinding
    private lateinit var adapter: VideoCardAdapter

    private val mid: Long by lazy { intent.getLongExtra(EXTRA_MID, 0L) }

    private var isFollowed: Boolean = false
    private var followActionInFlight: Boolean = false
    private var loadedInitialInfo: Boolean = false

    private val loadedBvids = HashSet<String>()
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var nextPage: Int = 1
    private var requestToken: Int = 0

    private var pendingFocusFirstItemAfterLoad: Boolean = false
    private var dpadGridController: DpadGridController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityStackLimiter.register(group = ACTIVITY_STACK_GROUP, activity = this, maxDepth = ACTIVITY_STACK_MAX_DEPTH)
        binding = ActivityUpDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        if (mid <= 0L) {
            AppToast.show(this, "无效的 UP 主 mid")
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFollow.setOnClickListener { onFollowClicked() }
        binding.btnFollow.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (adapter.itemCount > 0) {
                    focusGridAt(0)
                    return@setOnKeyListener true
                }
            }
            false
        }

        // Prefill header from list item to avoid empty UI.
        binding.tvName.text = intent.getStringExtra(EXTRA_NAME).orEmpty()
        binding.tvSign.text = intent.getStringExtra(EXTRA_SIGN).orEmpty()
        binding.tvSign.isVisible = binding.tvSign.text.isNotBlank()
        val avatar = intent.getStringExtra(EXTRA_AVATAR)
        blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivAvatar, blbl.cat3399.core.image.ImageUrl.avatar(avatar))

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
                    val token = PlayerPlaylistStore.put(items = playlistItems, index = pos, source = "UpDetail:$mid")
                    if (BiliClient.prefs.playerOpenDetailBeforePlay) {
                        startActivity(
                            Intent(this, VideoDetailActivity::class.java)
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
                            Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_BVID, card.bvid)
                                .putExtra(PlayerActivity.EXTRA_CID, card.cid ?: -1L)
                                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, pos),
                        )
                    }
                },
            )
        binding.recycler.setHasFixedSize(true)
        binding.recycler.layoutManager = GridLayoutManager(this, spanCountForWidth())
        binding.recycler.adapter = adapter
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
                    if (total - lastVisible - 1 <= 8) loadMoreFeed()
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
                            if (binding.btnFollow.isVisible) {
                                binding.btnFollow.requestFocus()
                            } else {
                                binding.btnBack.requestFocus()
                            }
                            return true
                        }

                        override fun onLeftEdge(): Boolean {
                            // Allow default focus-search to reach header widgets if appropriate.
                            return false
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadMoreFeed()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { !isFinishing && !isDestroyed },
                        enableCenterLongPressToLongClick = true,
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }
        resetAndLoad()
    }

    private fun applyUiMode() {
        val sidebarScale = UiScale.factor(this, BiliClient.prefs.sidebarSize)
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    override fun onDestroy() {
        dpadGridController?.release()
        dpadGridController = null
        ActivityStackLimiter.unregister(group = ACTIVITY_STACK_GROUP, activity = this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()
        adapter.invalidateSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        if (!binding.swipeRefresh.isRefreshing && adapter.itemCount == 0) {
            resetAndLoad()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (hasFocus) maybeConsumePendingFocusFirstItemAfterLoad()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
            if (binding.swipeRefresh.isRefreshing) return true
            resetAndLoad()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (adapter.itemCount > 0) {
            focusGridAt(0)
            return
        }
        if (binding.btnFollow.isVisible) {
            binding.btnFollow.requestFocus()
            return
        }
        binding.btnBack.requestFocus()
    }

    private fun resetAndLoad() {
        requestToken++
        loadedBvids.clear()
        nextPage = 1
        endReached = false
        isLoadingMore = false
        pendingFocusFirstItemAfterLoad = true
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        binding.swipeRefresh.isRefreshing = true
        loadHeader()
        loadMoreFeed(isRefresh = true)
    }

    private fun loadHeader() {
        val token = requestToken
        lifecycleScope.launch {
            try {
                val info = BiliApi.spaceAccInfo(mid)
                if (token != requestToken) return@launch
                loadedInitialInfo = true
                binding.tvName.text = info.name
                binding.tvSign.text = info.sign.orEmpty()
                binding.tvSign.isVisible = !info.sign.isNullOrBlank()
                blbl.cat3399.core.image.ImageLoader.loadInto(binding.ivAvatar, blbl.cat3399.core.image.ImageUrl.avatar(info.faceUrl))
                isFollowed = info.isFollowed
                updateFollowUi()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w("UpDetail", "loadHeader failed mid=$mid", t)
                if (!loadedInitialInfo) {
                    binding.tvName.text = binding.tvName.text.takeIf { it.isNotBlank() } ?: "加载失败"
                }
            }
        }
    }

    private fun loadMoreFeed(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val token = requestToken
        val targetPage = nextPage.coerceAtLeast(1)
        isLoadingMore = true
        lifecycleScope.launch {
            try {
                val page =
                    BiliApi.spaceArcSearchPage(
                        mid = mid,
                        pn = targetPage,
                        ps = 30,
                    )
                if (token != requestToken) return@launch

                nextPage = targetPage + 1
                endReached = page.items.isEmpty() || !page.hasMore

                val filtered = page.items.filter { loadedBvids.add(it.bvid) }
                if (isRefresh) adapter.submit(filtered) else adapter.append(filtered)

                binding.recycler.post {
                    maybeConsumePendingFocusFirstItemAfterLoad()
                    dpadGridController?.consumePendingFocusAfterLoadMore()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.e("UpDetail", "loadFeed failed mid=$mid page=$targetPage", t)
                AppToast.show(this@UpDetailActivity, "加载失败，可查看 Logcat(标签 BLBL)")
            } finally {
                if (token == requestToken) binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private fun onFollowClicked() {
        if (!BiliClient.cookies.hasSessData()) {
            startActivity(Intent(this, QrLoginActivity::class.java))
            AppToast.show(this, "登录后才能关注")
            return
        }
        if (followActionInFlight) return
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()
        if (selfMid != null && selfMid == mid) return

        val wantFollow = !isFollowed
        followActionInFlight = true
        updateFollowUi()
        lifecycleScope.launch {
            try {
                BiliApi.modifyRelation(fid = mid, act = if (wantFollow) 1 else 2, reSrc = 11)
                isFollowed = wantFollow
                AppToast.show(this@UpDetailActivity, if (wantFollow) "已关注" else "已取关")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                AppLog.w("UpDetail", "modifyRelation failed mid=$mid wantFollow=$wantFollow", t)
                val raw =
                    (t as? blbl.cat3399.core.api.BiliApiException)?.apiMessage?.takeIf { it.isNotBlank() }
                        ?: t.message.orEmpty()
                val msg =
                    when (raw) {
                        "missing_csrf" -> "登录态不完整，请重新登录"
                        else -> raw
                    }
                AppToast.show(this@UpDetailActivity, if (msg.isBlank()) "操作失败" else msg)
            } finally {
                followActionInFlight = false
                updateFollowUi()
                // Keep in sync in case server side changed.
                loadHeader()
            }
        }
    }

    private fun updateFollowUi() {
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()
        val isSelf = selfMid != null && selfMid == mid
        binding.btnFollow.isVisible = !isSelf
        if (isSelf) return

        binding.btnFollow.isEnabled = !followActionInFlight
        binding.btnFollow.text = if (isFollowed) "已关注" else "关注"

        val bg =
            ContextCompat.getColor(
                this,
                if (isFollowed) R.color.blbl_surface else R.color.blbl_purple,
            )
        val fg =
            ContextCompat.getColor(
                this,
                if (isFollowed) R.color.blbl_text_secondary else R.color.blbl_text,
            )
        binding.btnFollow.backgroundTintList = ColorStateList.valueOf(bg)
        binding.btnFollow.setTextColor(fg)
    }

    private fun focusGridAt(position: Int) {
        val recycler = binding.recycler
        recycler.post outer@{
            if (isFinishing || isDestroyed) return@outer
            val vh = recycler.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                vh.itemView.requestFocus()
                return@outer
            }
            recycler.scrollToPosition(position)
            recycler.post inner@{
                if (isFinishing || isDestroyed) return@inner
                recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() ?: recycler.requestFocus()
            }
        }
    }

    private fun maybeConsumePendingFocusFirstItemAfterLoad() {
        if (!pendingFocusFirstItemAfterLoad) return
        if (!hasWindowFocus()) return
        val focused = currentFocus
        if (focused != null && focused != binding.recycler && FocusTreeUtils.isDescendantOf(focused, binding.recycler)) {
            pendingFocusFirstItemAfterLoad = false
            return
        }
        if (adapter.itemCount <= 0) return
        pendingFocusFirstItemAfterLoad = false
        focusGridAt(0)
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    private fun isNavKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            -> true

            else -> false
        }
    }

    companion object {
        const val EXTRA_MID: String = "mid"
        const val EXTRA_NAME: String = "name"
        const val EXTRA_AVATAR: String = "avatar"
        const val EXTRA_SIGN: String = "sign"
        private const val ACTIVITY_STACK_GROUP: String = "player_up_flow"
        private const val ACTIVITY_STACK_MAX_DEPTH: Int = 3
    }
}
