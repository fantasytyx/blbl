package blbl.cat3399.feature.following

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.tv.RemoteKeys
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.DpadGridController
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.databinding.ActivityFollowingListBinding
import blbl.cat3399.feature.login.QrLoginActivity
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class FollowingListActivity : BaseActivity() {
    private lateinit var binding: ActivityFollowingListBinding
    private lateinit var adapter: FollowingGridAdapter

    private var vmid: Long = 0L
    private var forceLoginUi: Boolean = false
    private var page: Int = 1
    private var isLoadingMore: Boolean = false
    private var endReached: Boolean = false
    private var total: Int = 0
    private val loadedMids = HashSet<Long>()

    private var dpadGridController: DpadGridController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFollowingListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogin.setOnClickListener { startActivity(Intent(this, QrLoginActivity::class.java)) }

        adapter =
            FollowingGridAdapter { following ->
                startActivity(
                    Intent(this, UpDetailActivity::class.java)
                        .putExtra(UpDetailActivity.EXTRA_MID, following.mid)
                        .putExtra(UpDetailActivity.EXTRA_NAME, following.name)
                        .putExtra(UpDetailActivity.EXTRA_AVATAR, following.avatarUrl)
                        .putExtra(UpDetailActivity.EXTRA_SIGN, following.sign),
                )
            }

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
                    val totalCount = adapter.itemCount
                    if (totalCount <= 0) return
                    if (totalCount - lastVisible - 1 <= 12) loadNextPage()
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
                            // Allow default focus-search (e.g. to back button if the system picks it).
                            return false
                        }

                        override fun onRightEdge() = Unit

                        override fun canLoadMore(): Boolean = !endReached

                        override fun loadMore() {
                            loadNextPage()
                        }
                    },
                config =
                    DpadGridController.Config(
                        isEnabled = { !isFinishing && !isDestroyed },
                    ),
            ).also { it.install() }

        binding.swipeRefresh.setOnRefreshListener { resetAndLoad() }

        refreshLoginUi()
        if (binding.loginContainer.isVisible) {
            binding.btnLogin.requestFocus()
        } else {
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    private fun applyUiMode() {
        val sidebarScale = UiScale.factor(this, BiliClient.prefs.sidebarSize)
        fun px(id: Int): Int = resources.getDimensionPixelSize(id)
        fun scaledPx(id: Int): Int = (px(id) * sidebarScale).roundToInt().coerceAtLeast(0)

        val sizePx =
            scaledPx(blbl.cat3399.R.dimen.sidebar_settings_size_tv).coerceAtLeast(1)
        val padPx =
            scaledPx(blbl.cat3399.R.dimen.sidebar_settings_padding_tv)

        val lp = binding.btnBack.layoutParams
        if (lp.width != sizePx || lp.height != sizePx) {
            lp.width = sizePx
            lp.height = sizePx
            binding.btnBack.layoutParams = lp
        }
        if (
            binding.btnBack.paddingLeft != padPx ||
            binding.btnBack.paddingTop != padPx ||
            binding.btnBack.paddingRight != padPx ||
            binding.btnBack.paddingBottom != padPx
        ) {
            binding.btnBack.setPadding(padPx, padPx, padPx, padPx)
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()
        adapter.invalidateSizing()
        (binding.recycler.layoutManager as? GridLayoutManager)?.spanCount = spanCountForWidth()
        // If user just came back from login, allow re-checking.
        forceLoginUi = false
        refreshLoginUi()
        if (!binding.loginContainer.isVisible && adapter.itemCount == 0 && !binding.swipeRefresh.isRefreshing) {
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && RemoteKeys.isRefreshKey(event.keyCode)) {
            if (binding.loginContainer.isVisible) {
                Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
                binding.btnLogin.requestFocus()
                return true
            }
            if (binding.swipeRefresh.isRefreshing) return true
            binding.swipeRefresh.isRefreshing = true
            resetAndLoad()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null && isNavKey(event.keyCode)) {
            ensureInitialFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshLoginUi() {
        val loggedIn = !forceLoginUi && BiliClient.cookies.hasSessData()
        binding.loginContainer.isVisible = !loggedIn
        binding.swipeRefresh.isVisible = loggedIn
        if (!loggedIn) {
            binding.swipeRefresh.isRefreshing = false
            vmid = 0L
        }
    }

    private fun ensureInitialFocus() {
        if (currentFocus != null) return
        if (binding.loginContainer.isVisible) {
            binding.btnLogin.requestFocus()
            return
        }
        if (adapter.itemCount <= 0) {
            binding.recycler.requestFocus()
            return
        }
        focusGridAt(0)
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

    private fun resetAndLoad() {
        loadedMids.clear()
        page = 1
        total = 0
        endReached = false
        isLoadingMore = false
        dpadGridController?.clearPendingFocusAfterLoadMore()
        adapter.submit(emptyList())
        loadNextPage(isRefresh = true)
    }

    private fun loadNextPage(isRefresh: Boolean = false) {
        if (isLoadingMore || endReached) return
        val currentPage = page
        isLoadingMore = true
        lifecycleScope.launch {
            try {
                val mid = ensureUserMid() ?: return@launch
                val res = BiliApi.followingsPage(vmid = mid, pn = currentPage, ps = 50)
                total = res.total
                if (res.items.isEmpty()) {
                    endReached = true
                    if (isRefresh) Toast.makeText(this@FollowingListActivity, "暂无关注", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val filtered = res.items.filter { loadedMids.add(it.mid) }
                if (currentPage == 1) adapter.submit(filtered) else adapter.append(filtered)
                page = currentPage + 1
                endReached = !res.hasMore
                binding.recycler.post { dpadGridController?.consumePendingFocusAfterLoadMore() }
            } catch (t: Throwable) {
                AppLog.e("FollowingList", "load failed page=$currentPage", t)
                Toast.makeText(this@FollowingListActivity, "加载失败，可查看 Logcat(标签 BLBL)", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefresh.isRefreshing = false
                isLoadingMore = false
            }
        }
    }

    private suspend fun ensureUserMid(): Long? {
        if (vmid > 0) return vmid
        return runCatching {
            val nav = BiliApi.nav()
            val data = nav.optJSONObject("data")
            val isLogin = data?.optBoolean("isLogin") ?: false
            val mid = data?.optLong("mid") ?: 0L
            if (!isLogin || mid <= 0) {
                forceLoginUi = true
                refreshLoginUi()
                Toast.makeText(this, "登录态失效，请重新登录", Toast.LENGTH_SHORT).show()
                null
            } else {
                vmid = mid
                mid
            }
        }.getOrElse {
            AppLog.w("FollowingList", "nav failed", it)
            null
        }
    }

    private fun spanCountForWidth(): Int {
        return followingSpanCountForWidth(this)
    }

    override fun onDestroy() {
        dpadGridController?.release()
        dpadGridController = null
        super.onDestroy()
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
}
