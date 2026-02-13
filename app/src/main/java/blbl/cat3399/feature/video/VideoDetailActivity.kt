package blbl.cat3399.feature.video

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.ActivityStackLimiter
import blbl.cat3399.core.ui.BackButtonSizingHelper
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.GridSpanPolicy
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.UiScale
import blbl.cat3399.core.util.parseBangumiRedirectUrl
import blbl.cat3399.databinding.ActivityVideoDetailBinding
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.my.BangumiDetailActivity
import blbl.cat3399.feature.player.PlayerActivity
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class VideoDetailActivity : BaseActivity() {
    private lateinit var binding: ActivityVideoDetailBinding

    private lateinit var headerAdapter: VideoDetailHeaderAdapter
    private lateinit var recommendAdapter: VideoCardAdapter
    private lateinit var concatAdapter: ConcatAdapter

    private var loadJob: Job? = null
    private var requestToken: Int = 0

    private var bvid: String = ""
    private var cid: Long? = null
    private var aid: Long? = null

    private var ownerMid: Long? = null
    private var ownerName: String? = null
    private var ownerAvatar: String? = null
    private var coverUrl: String? = null
    private var title: String? = null
    private var desc: String? = null

    private var playlistToken: String? = null
    private var playlistIndex: Int? = null

    private var currentParts: List<PlayerPlaylistItem> = emptyList()
    private var currentUgcSeasonTitle: String? = null
    private var currentUgcSeasonItems: List<PlayerPlaylistItem> = emptyList()
    private var currentUgcSeasonIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityStackLimiter.register(group = ACTIVITY_STACK_GROUP, activity = this, maxDepth = ACTIVITY_STACK_MAX_DEPTH)

        bvid = intent.getStringExtra(EXTRA_BVID).orEmpty().trim()
        cid = intent.getLongExtra(EXTRA_CID, -1L).takeIf { it > 0L }
        aid = intent.getLongExtra(EXTRA_AID, -1L).takeIf { it > 0L }
        playlistToken = intent.getStringExtra(EXTRA_PLAYLIST_TOKEN)?.trim()?.takeIf { it.isNotBlank() }
        playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, -1).takeIf { it >= 0 }

        title = intent.getStringExtra(EXTRA_TITLE)?.trim()?.takeIf { it.isNotBlank() }
        coverUrl = intent.getStringExtra(EXTRA_COVER_URL)?.trim()?.takeIf { it.isNotBlank() }
        ownerName = intent.getStringExtra(EXTRA_OWNER_NAME)?.trim()?.takeIf { it.isNotBlank() }
        ownerAvatar = intent.getStringExtra(EXTRA_OWNER_AVATAR)?.trim()?.takeIf { it.isNotBlank() }
        ownerMid = intent.getLongExtra(EXTRA_OWNER_MID, -1L).takeIf { it > 0L }

        if (bvid.isBlank() && aid == null) {
            Toast.makeText(this, "缺少 bvid/aid", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (savedInstanceState != null) {
            initUi()
            load()
            return
        }

        showLoadingUi()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        lifecycleScope.launch {
            try {
                val viewData = fetchViewData()
                val bangumiRedirect = parseBangumiRedirectUrl(viewData.optString("redirect_url", ""))
                if (bangumiRedirect != null) {
                    startActivity(
                        Intent(this@VideoDetailActivity, BangumiDetailActivity::class.java)
                            .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, false)
                            .apply {
                                bangumiRedirect.epId?.let { epId ->
                                    putExtra(BangumiDetailActivity.EXTRA_EP_ID, epId)
                                    putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, epId)
                                }
                                bangumiRedirect.seasonId?.let { seasonId ->
                                    putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, seasonId)
                                }
                            },
                    )
                    finish()
                    return@launch
                }

                initUi()
                load(prefetchedViewData = viewData)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                Toast.makeText(this@VideoDetailActivity, t.message ?: "加载失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        if (!this::binding.isInitialized) return
        applyUiMode()
        if (this::headerAdapter.isInitialized) headerAdapter.invalidateSizing()
        if (this::recommendAdapter.isInitialized) recommendAdapter.invalidateSizing()
    }

    override fun onDestroy() {
        ActivityStackLimiter.unregister(group = ACTIVITY_STACK_GROUP, activity = this)
        super.onDestroy()
    }

    private fun applyUiMode() {
        val sidebarScale = UiScale.factor(this, BiliClient.prefs.sidebarSize)
        BackButtonSizingHelper.applySidebarSizing(
            view = binding.btnBack,
            resources = resources,
            sidebarScale = sidebarScale,
        )
    }

    private fun showLoadingUi() {
        val root =
            FrameLayout(this).apply {
                setBackgroundResource(R.color.blbl_bg)
                addView(
                    ProgressBar(this@VideoDetailActivity),
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        gravity = Gravity.CENTER
                    },
                )
            }
        setContentView(root)
    }

    private fun initUi() {
        binding = ActivityVideoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)
        applyUiMode()

        binding.btnBack.setOnClickListener { finish() }

        headerAdapter =
            VideoDetailHeaderAdapter(
                onPlayClick = { playCurrentFromHeader() },
                onUpClick = { openUpDetail() },
                onPartClick = { _, index -> playPart(index) },
                onSeasonClick = { _, index -> playSeasonItem(index) },
            )

        recommendAdapter =
            VideoCardAdapter(
                onClick = { card, _ ->
                    val nextBvid = card.bvid.trim()
                    if (nextBvid.isBlank()) return@VideoCardAdapter
                    startActivity(
                        Intent(this, VideoDetailActivity::class.java)
                            .putExtra(EXTRA_BVID, nextBvid)
                            .putExtra(EXTRA_TITLE, card.title)
                            .putExtra(EXTRA_COVER_URL, card.coverUrl)
                            .apply {
                                card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_OWNER_NAME, it) }
                                card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(EXTRA_OWNER_AVATAR, it) }
                                card.ownerMid?.takeIf { it > 0L }?.let { putExtra(EXTRA_OWNER_MID, it) }
                            },
                    )
                },
            )

        concatAdapter =
            ConcatAdapter(
                ConcatAdapter.Config.Builder()
                    .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
                    .build(),
                headerAdapter,
                recommendAdapter,
            )

        binding.recycler.adapter = concatAdapter
        val spanCount = spanCountForWidth()
        val lm = GridLayoutManager(this, spanCount)
        lm.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (position == 0) spanCount else 1
                }
            }
        binding.recycler.layoutManager = lm
        (binding.recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.recycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnKeyListener { v, keyCode, event ->
                        if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        if (keyCode != KeyEvent.KEYCODE_DPAD_UP) return@setOnKeyListener false

                        val holder = binding.recycler.findContainingViewHolder(v) ?: return@setOnKeyListener false
                        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnKeyListener false
                        if (pos <= 0) return@setOnKeyListener false
                        if (pos > spanCount) return@setOnKeyListener false
                        if (binding.recycler.canScrollVertically(-1)) return@setOnKeyListener false
                        return@setOnKeyListener headerAdapter.requestFocusPlay()
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {
                    view.setOnKeyListener(null)
                }
            },
        )

        binding.swipeRefresh.setOnRefreshListener { load() }

        headerAdapter.update(
            title = title,
            desc = desc,
            coverUrl = coverUrl,
            upName = ownerName,
            upAvatar = ownerAvatar,
            seasonTitle = currentUgcSeasonTitle,
            parts = currentParts,
            seasonItems = currentUgcSeasonItems,
            seasonIndex = currentUgcSeasonIndex,
        )

        binding.recycler.post { headerAdapter.requestFocusPlay() }
    }

    private suspend fun fetchViewData(): JSONObject =
        withContext(Dispatchers.IO) {
            val json =
                if (bvid.isNotBlank()) {
                    BiliApi.view(bvid)
                } else {
                    BiliApi.view(aid ?: 0L)
                }
            val code = json.optInt("code", 0)
            if (code != 0) {
                val msg = json.optString("message", json.optString("msg", "加载失败")).ifBlank { "加载失败" }
                throw IllegalStateException(msg)
            }
            json.optJSONObject("data") ?: JSONObject()
        }

    private fun load(prefetchedViewData: JSONObject? = null) {
        val codeToken = ++requestToken
        loadJob?.cancel()
        loadJob = null

        binding.swipeRefresh.isRefreshing = true

        loadJob =
            lifecycleScope.launch {
                try {
                    val viewData = prefetchedViewData ?: fetchViewData()
                    if (codeToken != requestToken) return@launch

                    val bangumiRedirect = parseBangumiRedirectUrl(viewData.optString("redirect_url", ""))
                    if (bangumiRedirect != null) {
                        startActivity(
                            Intent(this@VideoDetailActivity, BangumiDetailActivity::class.java)
                                .putExtra(BangumiDetailActivity.EXTRA_IS_DRAMA, false)
                                .apply {
                                    bangumiRedirect.epId?.let { epId ->
                                        putExtra(BangumiDetailActivity.EXTRA_EP_ID, epId)
                                        putExtra(BangumiDetailActivity.EXTRA_CONTINUE_EP_ID, epId)
                                    }
                                    bangumiRedirect.seasonId?.let { seasonId ->
                                        putExtra(BangumiDetailActivity.EXTRA_SEASON_ID, seasonId)
                                    }
                                },
                        )
                        finish()
                        return@launch
                    }

                    val resolvedBvid = viewData.optString("bvid", "").trim().takeIf { it.isNotBlank() } ?: bvid
                    val resolvedAid = viewData.optLong("aid").takeIf { it > 0L } ?: aid
                    val resolvedCid = cid ?: viewData.optLong("cid").takeIf { it > 0L }

                    bvid = resolvedBvid
                    aid = resolvedAid
                    cid = resolvedCid

                    title = viewData.optString("title", "").trim().takeIf { it.isNotBlank() } ?: title
                    desc = viewData.optString("desc", "").trim()
                    coverUrl = viewData.optString("pic", "").trim().takeIf { it.isNotBlank() } ?: coverUrl

                    val owner = viewData.optJSONObject("owner")
                    ownerMid = owner?.optLong("mid")?.takeIf { it > 0L } ?: ownerMid
                    ownerName = owner?.optString("name", "")?.trim()?.takeIf { it.isNotBlank() } ?: ownerName
                    ownerAvatar = owner?.optString("face", "")?.trim()?.takeIf { it.isNotBlank() } ?: ownerAvatar

                    currentParts = parseMultiPagePlaylistFromView(viewData, bvid = resolvedBvid, aid = resolvedAid)

                    val ugcSeason = viewData.optJSONObject("ugc_season")
                    currentUgcSeasonTitle = ugcSeason?.optString("title", "")?.trim()?.takeIf { it.isNotBlank() }
                    currentUgcSeasonItems = emptyList()
                    currentUgcSeasonIndex = null
                    if (ugcSeason != null) {
                        val itemsFromView = parseUgcSeasonPlaylistFromView(ugcSeason)
                        if (itemsFromView.isNotEmpty()) {
                            currentUgcSeasonItems = itemsFromView
                        } else {
                            val seasonId = ugcSeason.optLong("id").takeIf { it > 0L }
                            val mid =
                                ugcSeason.optLong("mid").takeIf { it > 0L }
                                    ?: ownerMid
                            if (seasonId != null && mid != null) {
                                val json =
                                    withContext(Dispatchers.IO) {
                                        runCatching { BiliApi.seasonsArchivesList(mid = mid, seasonId = seasonId, pageSize = 200) }.getOrNull()
                                    }
                                if (json != null) currentUgcSeasonItems = parseUgcSeasonPlaylistFromArchivesList(json)
                            }
                        }
                    }
                    currentUgcSeasonIndex =
                        pickPlaylistIndexForCurrentMedia(
                            list = currentUgcSeasonItems,
                            bvid = resolvedBvid,
                            aid = resolvedAid,
                            cid = resolvedCid,
                        ).takeIf { it >= 0 }

                    val related =
                        withContext(Dispatchers.IO) {
                            runCatching { BiliApi.archiveRelated(bvid = resolvedBvid, aid = resolvedAid) }.getOrDefault(emptyList())
                        }
                    if (codeToken != requestToken) return@launch

                    headerAdapter.update(
                        title = title,
                        desc = desc,
                        coverUrl = coverUrl,
                        upName = ownerName,
                        upAvatar = ownerAvatar,
                        seasonTitle = currentUgcSeasonTitle,
                        parts = currentParts,
                        seasonItems = currentUgcSeasonItems,
                        seasonIndex = currentUgcSeasonIndex,
                    )
                    recommendAdapter.submit(related)
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    AppLog.e("VideoDetail", "load failed bvid=$bvid aid=$aid", t)
                    Toast.makeText(this@VideoDetailActivity, t.message ?: "加载失败", Toast.LENGTH_SHORT).show()
                } finally {
                    if (codeToken == requestToken) binding.swipeRefresh.isRefreshing = false
                }
            }
    }

    private fun parseMultiPagePlaylistFromView(viewData: JSONObject, bvid: String, aid: Long?): List<PlayerPlaylistItem> {
        val pages = viewData.optJSONArray("pages") ?: return emptyList()
        if (pages.length() <= 1) return emptyList()
        val out = ArrayList<PlayerPlaylistItem>(pages.length())
        for (i in 0 until pages.length()) {
            val obj = pages.optJSONObject(i) ?: continue
            val pageCid = obj.optLong("cid").takeIf { it > 0L } ?: continue
            val page = obj.optInt("page").takeIf { it > 0 } ?: (i + 1)
            val part = obj.optString("part", "").trim()
            val title =
                if (part.isBlank()) {
                    "P$page"
                } else {
                    "P$page $part"
                }
            out.add(
                PlayerPlaylistItem(
                    bvid = bvid,
                    cid = pageCid,
                    aid = aid,
                    title = title,
                ),
            )
        }
        return out
    }

    private fun parseUgcSeasonPlaylistFromView(ugcSeason: JSONObject): List<PlayerPlaylistItem> {
        val sections = ugcSeason.optJSONArray("sections") ?: return emptyList()
        val out = ArrayList<PlayerPlaylistItem>(ugcSeason.optInt("ep_count").coerceAtLeast(0))
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val eps = section.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val ep = eps.optJSONObject(j) ?: continue
                val arc = ep.optJSONObject("arc") ?: JSONObject()
                val bvid = ep.optString("bvid", "").trim().ifBlank { arc.optString("bvid", "").trim() }
                if (bvid.isBlank()) continue
                val cid = ep.optLong("cid").takeIf { it > 0L } ?: arc.optLong("cid").takeIf { it > 0L }
                val aid = ep.optLong("aid").takeIf { it > 0L } ?: arc.optLong("aid").takeIf { it > 0L }
                val title =
                    ep.optString("title", "").trim().takeIf { it.isNotBlank() }
                        ?: arc.optString("title", "").trim().takeIf { it.isNotBlank() }
                out.add(
                    PlayerPlaylistItem(
                        bvid = bvid,
                        cid = cid,
                        aid = aid,
                        title = title,
                    ),
                )
            }
        }
        return out
    }

    private fun pickPlaylistIndexForCurrentMedia(list: List<PlayerPlaylistItem>, bvid: String, aid: Long?, cid: Long?): Int {
        val safeBvid = bvid.trim()
        if (cid != null && cid > 0) {
            val byCid = list.indexOfFirst { it.cid == cid }
            if (byCid >= 0) return byCid
        }
        if (aid != null && aid > 0) {
            val byAid = list.indexOfFirst { it.aid == aid }
            if (byAid >= 0) return byAid
        }
        if (safeBvid.isNotBlank()) {
            val byBvid = list.indexOfFirst { it.bvid == safeBvid }
            if (byBvid >= 0) return byBvid
        }
        return -1
    }

    private fun parseUgcSeasonPlaylistFromArchivesList(json: JSONObject): List<PlayerPlaylistItem> {
        val archives = json.optJSONObject("data")?.optJSONArray("archives") ?: return emptyList()
        val out = ArrayList<PlayerPlaylistItem>(archives.length())
        for (i in 0 until archives.length()) {
            val obj = archives.optJSONObject(i) ?: continue
            val bvid = obj.optString("bvid", "").trim()
            if (bvid.isBlank()) continue
            val aid = obj.optLong("aid").takeIf { it > 0L }
            val title = obj.optString("title", "").trim().takeIf { it.isNotBlank() }
            out.add(
                PlayerPlaylistItem(
                    bvid = bvid,
                    aid = aid,
                    title = title,
                ),
            )
        }
        return out
    }

    private fun playCurrentFromHeader() {
        val safeBvid = bvid.trim()
        if (safeBvid.isBlank() && aid == null) {
            Toast.makeText(this, "缺少 bvid", Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, safeBvid)
                .putExtra(PlayerActivity.EXTRA_CID, cid ?: -1L)
                .apply { aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .apply { playlistToken?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, it) } }
                .apply { playlistIndex?.let { putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, it) } },
        )
    }

    private fun playPart(index: Int) {
        val list = currentParts
        if (list.isEmpty() || index !in list.indices) return
        val picked = list[index]
        val safeBvid = picked.bvid.trim()
        if (safeBvid.isBlank()) return

        val token = PlayerPlaylistStore.put(items = list, index = index, source = "VideoDetail:multi_page:$safeBvid")
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, safeBvid)
                .putExtra(PlayerActivity.EXTRA_CID, picked.cid ?: -1L)
                .apply { picked.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index),
        )
    }

    private fun playSeasonItem(index: Int) {
        val list = currentUgcSeasonItems
        if (list.isEmpty() || index !in list.indices) return
        val picked = list[index]
        val safeBvid = picked.bvid.trim()
        if (safeBvid.isBlank()) return

        val token = PlayerPlaylistStore.put(items = list, index = index, source = "VideoDetail:ugc_season:$safeBvid")
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_BVID, safeBvid)
                .putExtra(PlayerActivity.EXTRA_CID, picked.cid ?: -1L)
                .apply { picked.aid?.let { putExtra(PlayerActivity.EXTRA_AID, it) } }
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
                .putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, index),
        )
    }

    private fun openUpDetail() {
        val mid = ownerMid?.takeIf { it > 0L }
        if (mid == null) {
            Toast.makeText(this, "未获取到 UP 主信息", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, UpDetailActivity::class.java)
                .putExtra(UpDetailActivity.EXTRA_MID, mid)
                .apply {
                    ownerName?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                    ownerAvatar?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                },
        )
    }

    private fun spanCountForWidth(): Int {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        return GridSpanPolicy.fixedSpanCountForWidthDp(
            widthDp = widthDp,
            overrideSpanCount = BiliClient.prefs.gridSpanCount,
        )
    }

    companion object {
        const val EXTRA_BVID: String = "bvid"
        const val EXTRA_CID: String = "cid"
        const val EXTRA_AID: String = "aid"
        const val EXTRA_TITLE: String = "title"
        const val EXTRA_COVER_URL: String = "cover_url"
        const val EXTRA_OWNER_NAME: String = "owner_name"
        const val EXTRA_OWNER_AVATAR: String = "owner_avatar"
        const val EXTRA_OWNER_MID: String = "owner_mid"
        const val EXTRA_PLAYLIST_TOKEN: String = "playlist_token"
        const val EXTRA_PLAYLIST_INDEX: String = "playlist_index"

        private const val ACTIVITY_STACK_GROUP: String = "video_detail_flow"
        private const val ACTIVITY_STACK_MAX_DEPTH: Int = 3
    }
}
