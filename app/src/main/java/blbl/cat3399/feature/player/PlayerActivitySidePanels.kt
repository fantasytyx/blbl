package blbl.cat3399.feature.player

import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val COMMENT_TYPE_VIDEO = 1
internal const val COMMENT_SORT_NEW = 0
internal const val COMMENT_SORT_HOT = 1

internal fun PlayerActivity.isSettingsPanelVisible(): Boolean = binding.settingsPanel.visibility == View.VISIBLE

internal fun PlayerActivity.isCommentsPanelVisible(): Boolean = binding.commentsPanel.visibility == View.VISIBLE

internal fun PlayerActivity.isCommentThreadVisible(): Boolean = binding.recyclerCommentThread.visibility == View.VISIBLE

internal fun PlayerActivity.isSidePanelVisible(): Boolean = isSettingsPanelVisible() || isCommentsPanelVisible()

internal fun PlayerActivity.initSidePanels() {
    binding.btnSettingsPanelBack.setOnClickListener { hideSettingsPanel() }
    binding.btnCommentsPanelBack.setOnClickListener { onCommentsBackPressed() }

    binding.chipCommentSortHot.setOnClickListener { applyCommentSort(COMMENT_SORT_HOT) }
    binding.chipCommentSortNew.setOnClickListener { applyCommentSort(COMMENT_SORT_NEW) }
    updateCommentSortUi()

    val pool = RecyclerView.RecycledViewPool()
    binding.recyclerComments.setRecycledViewPool(pool)
    binding.recyclerCommentThread.setRecycledViewPool(pool)
    binding.recyclerComments.setHasFixedSize(true)
    binding.recyclerCommentThread.setHasFixedSize(true)
    binding.recyclerComments.itemAnimator = null
    binding.recyclerCommentThread.itemAnimator = null

    run {
        val adapter =
            PlayerCommentsAdapter { item ->
                if (!isCommentsPanelVisible()) return@PlayerCommentsAdapter
                if (isCommentThreadVisible()) return@PlayerCommentsAdapter
                if (item.replyCount <= 0) {
                    Toast.makeText(this, "暂无更多回复", Toast.LENGTH_SHORT).show()
                    return@PlayerCommentsAdapter
                }
                openCommentThread(rootRpid = item.rpid)
            }
        binding.recyclerComments.adapter = adapter
        binding.recyclerComments.layoutManager = LinearLayoutManager(this)
        binding.recyclerComments.clearOnScrollListeners()
        binding.recyclerComments.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (!isCommentsPanelVisible() || isCommentThreadVisible()) return
                    if (commentsFetchJob?.isActive == true || commentsEndReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 6) {
                        loadMoreComments()
                    }
                }
            },
        )
    }

    run {
        val adapter = PlayerCommentsAdapter { }
        binding.recyclerCommentThread.adapter = adapter
        binding.recyclerCommentThread.layoutManager = LinearLayoutManager(this)
        binding.recyclerCommentThread.clearOnScrollListeners()
        binding.recyclerCommentThread.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy <= 0) return
                    if (!isCommentsPanelVisible() || !isCommentThreadVisible()) return
                    if (commentThreadFetchJob?.isActive == true || commentThreadEndReached) return
                    val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = lm.findLastVisibleItemPosition()
                    val total = adapter.itemCount
                    if (total <= 0) return
                    if (total - lastVisible - 1 <= 6) {
                        loadMoreCommentThread()
                    }
                }
            },
        )
    }
}

internal fun PlayerActivity.toggleSettingsPanel() {
    if (isSettingsPanelVisible()) {
        hideSettingsPanel()
    } else {
        showSettingsPanel()
    }
}

internal fun PlayerActivity.showSettingsPanel() {
    binding.commentsPanel.visibility = View.GONE
    binding.settingsPanel.visibility = View.VISIBLE
    setControlsVisible(true)
    focusSettingsPanel()
}

internal fun PlayerActivity.hideSettingsPanel() {
    binding.settingsPanel.visibility = View.GONE
    setControlsVisible(true)
    focusAdvancedControl()
}

internal fun PlayerActivity.toggleCommentsPanel() {
    if (isCommentsPanelVisible()) {
        hideCommentsPanel()
    } else {
        showCommentsPanel()
    }
}

internal fun PlayerActivity.showCommentsPanel() {
    binding.settingsPanel.visibility = View.GONE
    binding.commentsPanel.visibility = View.VISIBLE
    showCommentsRoot()
    setControlsVisible(true)
    ensureCommentsLoaded()
    focusCommentsPanel()
}

internal fun PlayerActivity.hideCommentsPanel() {
    binding.commentsPanel.visibility = View.GONE
    setControlsVisible(true)
    binding.btnComments.post { binding.btnComments.requestFocus() }
}

internal fun PlayerActivity.onSidePanelBackPressed(): Boolean {
    if (isCommentThreadVisible()) {
        showCommentsRoot()
        focusCommentsPanel()
        return true
    }
    if (isCommentsPanelVisible()) {
        hideCommentsPanel()
        return true
    }
    if (isSettingsPanelVisible()) {
        hideSettingsPanel()
        return true
    }
    return false
}

internal fun PlayerActivity.onCommentsBackPressed() {
    if (isCommentThreadVisible()) {
        showCommentsRoot()
        focusCommentsPanel()
    } else {
        hideCommentsPanel()
    }
}

internal fun PlayerActivity.showCommentsRoot() {
    binding.recyclerComments.visibility = View.VISIBLE
    binding.recyclerCommentThread.visibility = View.GONE
    binding.rowCommentSort.visibility = View.VISIBLE
    binding.tvCommentsPanelTitle.text = getString(R.string.player_panel_comments)
    commentThreadRootRpid = 0L
}

internal fun PlayerActivity.openCommentThread(rootRpid: Long) {
    val safeRoot = rootRpid.takeIf { it > 0L } ?: return
    commentThreadRootRpid = safeRoot
    binding.recyclerComments.visibility = View.GONE
    binding.recyclerCommentThread.visibility = View.VISIBLE
    binding.rowCommentSort.visibility = View.GONE
    binding.tvCommentsPanelTitle.text = getString(R.string.player_panel_comment_thread)
    reloadCommentThread()
    focusCommentThread()
}

internal fun PlayerActivity.focusCommentsPanel() {
    binding.recyclerComments.post {
        val child = binding.recyclerComments.getChildAt(0)
        if (child != null) {
            child.requestFocus()
            return@post
        }
        if (commentsItems.isEmpty()) {
            binding.chipCommentSortHot.requestFocus()
        } else {
            binding.recyclerComments.requestFocus()
        }
    }
}

internal fun PlayerActivity.focusCommentThread() {
    binding.recyclerCommentThread.post {
        val child = binding.recyclerCommentThread.getChildAt(0)
        (child ?: binding.recyclerCommentThread).requestFocus()
    }
}

internal fun PlayerActivity.applyCommentSort(sort: Int) {
    if (commentSort == sort) return
    commentSort = sort
    updateCommentSortUi()
    if (isCommentsPanelVisible() && !isCommentThreadVisible()) {
        reloadComments()
    }
}

internal fun PlayerActivity.updateCommentSortUi() {
    val selected = ContextCompat.getColor(this, R.color.blbl_text)
    val unselected = ContextCompat.getColor(this, R.color.blbl_text_secondary)

    val hotSelected = commentSort == COMMENT_SORT_HOT
    val newSelected = commentSort == COMMENT_SORT_NEW

    binding.chipCommentSortHot.isSelected = hotSelected
    binding.chipCommentSortNew.isSelected = newSelected
    binding.chipCommentSortHot.setTextColor(if (hotSelected) selected else unselected)
    binding.chipCommentSortNew.setTextColor(if (newSelected) selected else unselected)
}

internal fun PlayerActivity.ensureCommentsLoaded() {
    val aid = currentAid?.takeIf { it > 0L }
    if (aid == null) {
        binding.tvCommentsHint.text = getString(R.string.player_comment_no_aid)
        binding.tvCommentsHint.visibility = View.VISIBLE
        return
    }
    if (commentsItems.isNotEmpty()) return
    if (commentsTotalCount == 0) {
        binding.tvCommentsHint.text = getString(R.string.player_comment_empty)
        binding.tvCommentsHint.visibility = View.VISIBLE
        return
    }
    reloadComments()
}

internal fun PlayerActivity.reloadComments() {
    val aid = currentAid?.takeIf { it > 0L }
    if (aid == null) {
        Toast.makeText(this, getString(R.string.player_comment_no_aid), Toast.LENGTH_SHORT).show()
        return
    }

    commentsFetchJob?.cancel()
    commentsFetchJob = null
    val token = ++commentsFetchToken
    commentsPage = 1
    commentsTotalCount = -1
    commentsEndReached = false
    commentsItems.clear()
    (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())

    binding.tvCommentsHint.text = getString(R.string.player_comment_loading)
    binding.tvCommentsHint.visibility = View.VISIBLE

    commentsFetchJob =
        lifecycleScope.launch {
            try {
                val data =
                    withContext(Dispatchers.IO) {
                        BiliApi.commentPage(
                            type = COMMENT_TYPE_VIDEO,
                            oid = aid,
                            sort = commentSort,
                            pn = 1,
                            ps = 20,
                            noHot = 1,
                        )
                    }
                if (token != commentsFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch

                val (totalCount, list) =
                    withContext(Dispatchers.Default) {
                        val page = data.optJSONObject("page") ?: JSONObject()
                        val count = page.optInt("count", -1).takeIf { it >= 0 } ?: -1
                        val replies = data.optJSONArray("replies") ?: JSONArray()
                        val items = parseReplyList(replies, oid = aid, canOpenThread = true)
                        count to items
                    }
                if (token != commentsFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch

                commentsTotalCount = totalCount

                commentsItems.addAll(list)
                commentsEndReached =
                    list.isEmpty() ||
                        (commentsTotalCount >= 0 && commentsItems.size >= commentsTotalCount)

                (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.setItems(commentsItems)

                if (commentsItems.isEmpty()) {
                    binding.tvCommentsHint.text = getString(R.string.player_comment_empty)
                    binding.tvCommentsHint.visibility = View.VISIBLE
                } else {
                    binding.tvCommentsHint.visibility = View.GONE
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: getString(R.string.player_comment_load_failed))
                Toast.makeText(this@reloadComments, msg, Toast.LENGTH_SHORT).show()
                if (commentsItems.isEmpty()) {
                    binding.tvCommentsHint.text = getString(R.string.player_comment_load_failed)
                    binding.tvCommentsHint.visibility = View.VISIBLE
                }
            } finally {
                if (token == commentsFetchToken) commentsFetchJob = null
            }
        }
}

internal fun PlayerActivity.loadMoreComments() {
    val aid = currentAid?.takeIf { it > 0L } ?: return
    if (commentsFetchJob?.isActive == true || commentsEndReached) return
    val nextPage = commentsPage + 1
    val token = ++commentsFetchToken

    commentsFetchJob =
        lifecycleScope.launch {
            try {
                val data =
                    withContext(Dispatchers.IO) {
                        BiliApi.commentPage(
                            type = COMMENT_TYPE_VIDEO,
                            oid = aid,
                            sort = commentSort,
                            pn = nextPage,
                            ps = 20,
                            noHot = 1,
                        )
                    }
                if (token != commentsFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch

                val (totalCount, list) =
                    withContext(Dispatchers.Default) {
                        val page = data.optJSONObject("page") ?: JSONObject()
                        val count = page.optInt("count", commentsTotalCount).takeIf { it >= 0 } ?: commentsTotalCount
                        val replies = data.optJSONArray("replies") ?: JSONArray()
                        val items = parseReplyList(replies, oid = aid, canOpenThread = true)
                        count to items
                    }
                if (token != commentsFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch

                commentsTotalCount = totalCount
                if (list.isEmpty()) {
                    commentsEndReached = true
                    return@launch
                }
                commentsPage = nextPage
                commentsItems.addAll(list)
                commentsEndReached =
                    commentsTotalCount >= 0 && commentsItems.size >= commentsTotalCount
                (binding.recyclerComments.adapter as? PlayerCommentsAdapter)?.appendItems(list)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: getString(R.string.player_comment_load_failed))
                Toast.makeText(this@loadMoreComments, msg, Toast.LENGTH_SHORT).show()
            } finally {
                if (token == commentsFetchToken) commentsFetchJob = null
            }
        }
}

internal fun PlayerActivity.reloadCommentThread() {
    val aid = currentAid?.takeIf { it > 0L } ?: return
    val root = commentThreadRootRpid.takeIf { it > 0L } ?: return

    commentThreadFetchJob?.cancel()
    commentThreadFetchJob = null
    val token = ++commentThreadFetchToken
    commentThreadPage = 1
    commentThreadTotalCount = -1
    commentThreadEndReached = false
    commentThreadItems.clear()

    binding.tvCommentsHint.text = getString(R.string.player_comment_loading)
    binding.tvCommentsHint.visibility = View.VISIBLE
    (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.setItems(emptyList())

    commentThreadFetchJob =
        lifecycleScope.launch {
            try {
                val data =
                    withContext(Dispatchers.IO) {
                        BiliApi.commentRepliesPage(
                            type = COMMENT_TYPE_VIDEO,
                            oid = aid,
                            rootRpid = root,
                            pn = 1,
                            ps = 20,
                        )
                    }
                if (token != commentThreadFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch
                if (commentThreadRootRpid != root) return@launch

                val contextTagRoot = getString(R.string.player_comment_thread_root)
                val (totalCount, rootItemKeyed, replyItemsKeyed) =
                    withContext(Dispatchers.Default) {
                        val page = data.optJSONObject("page") ?: JSONObject()
                        val count = page.optInt("count", -1).takeIf { it >= 0 } ?: -1

                        val rootObj = data.optJSONObject("root")
                        val rootItem =
                            rootObj
                                ?.let { parseReplyItem(it, oid = aid, contextTag = contextTagRoot, canOpenThread = false) }
                                ?.let { it.copy(key = "thread_root:${it.rpid}", isThreadRoot = true) }

                        val replies = data.optJSONArray("replies") ?: JSONArray()
                        val list = parseReplyList(replies, oid = aid, canOpenThread = false)
                        val keyed = list.map { it.copy(key = "thread:${it.rpid}") }

                        Triple(count, rootItem, keyed)
                    }
                if (token != commentThreadFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch
                if (commentThreadRootRpid != root) return@launch

                commentThreadTotalCount = totalCount
                if (rootItemKeyed != null) commentThreadItems.add(rootItemKeyed)
                commentThreadItems.addAll(replyItemsKeyed)

                val loadedReplies = (commentThreadItems.size - (if (rootItemKeyed != null) 1 else 0)).coerceAtLeast(0)
                commentThreadEndReached =
                    replyItemsKeyed.isEmpty() ||
                        (commentThreadTotalCount >= 0 && loadedReplies >= commentThreadTotalCount)

                (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.setItems(commentThreadItems)

                if (commentThreadItems.isEmpty()) {
                    binding.tvCommentsHint.text = getString(R.string.player_comment_thread_empty)
                    binding.tvCommentsHint.visibility = View.VISIBLE
                } else {
                    binding.tvCommentsHint.visibility = View.GONE
                }
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: getString(R.string.player_comment_load_failed))
                Toast.makeText(this@reloadCommentThread, msg, Toast.LENGTH_SHORT).show()
                if (commentThreadItems.isEmpty()) {
                    binding.tvCommentsHint.text = getString(R.string.player_comment_load_failed)
                    binding.tvCommentsHint.visibility = View.VISIBLE
                }
            } finally {
                if (token == commentThreadFetchToken) commentThreadFetchJob = null
            }
        }
}

internal fun PlayerActivity.loadMoreCommentThread() {
    val aid = currentAid?.takeIf { it > 0L } ?: return
    val root = commentThreadRootRpid.takeIf { it > 0L } ?: return
    if (commentThreadFetchJob?.isActive == true || commentThreadEndReached) return

    val nextPage = commentThreadPage + 1
    val token = ++commentThreadFetchToken

    commentThreadFetchJob =
        lifecycleScope.launch {
            try {
                val data =
                    withContext(Dispatchers.IO) {
                        BiliApi.commentRepliesPage(
                            type = COMMENT_TYPE_VIDEO,
                            oid = aid,
                            rootRpid = root,
                            pn = nextPage,
                            ps = 20,
                        )
                    }
                if (token != commentThreadFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch
                if (commentThreadRootRpid != root) return@launch

                val list =
                    withContext(Dispatchers.Default) {
                        val replies = data.optJSONArray("replies") ?: JSONArray()
                        parseReplyList(replies, oid = aid, canOpenThread = false).map { it.copy(key = "thread:${it.rpid}") }
                    }
                if (token != commentThreadFetchToken) return@launch
                if (currentAid?.takeIf { it > 0L } != aid) return@launch
                if (commentThreadRootRpid != root) return@launch

                if (list.isEmpty()) {
                    commentThreadEndReached = true
                    return@launch
                }

                commentThreadPage = nextPage
                commentThreadItems.addAll(list)

                val rootCount = if (commentThreadItems.firstOrNull()?.key?.startsWith("thread_root:") == true) 1 else 0
                val loadedReplies = (commentThreadItems.size - rootCount).coerceAtLeast(0)
                commentThreadEndReached =
                    commentThreadTotalCount >= 0 && loadedReplies >= commentThreadTotalCount

                (binding.recyclerCommentThread.adapter as? PlayerCommentsAdapter)?.appendItems(list)
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val e = t as? BiliApiException
                val msg = e?.apiMessage?.takeIf { it.isNotBlank() } ?: (t.message ?: getString(R.string.player_comment_load_failed))
                Toast.makeText(this@loadMoreCommentThread, msg, Toast.LENGTH_SHORT).show()
            } finally {
                if (token == commentThreadFetchToken) commentThreadFetchJob = null
            }
        }
}

private fun parseReplyList(arr: JSONArray, oid: Long, canOpenThread: Boolean): List<PlayerCommentsAdapter.Item> {
    if (arr.length() <= 0) return emptyList()
    val out = ArrayList<PlayerCommentsAdapter.Item>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val item = parseReplyItem(obj, oid = oid, contextTag = null, canOpenThread = canOpenThread) ?: continue
        out.add(item)
    }
    return out
}

private fun parseReplyItem(
    obj: JSONObject,
    oid: Long,
    contextTag: String?,
    canOpenThread: Boolean,
): PlayerCommentsAdapter.Item? {
    val rpid = obj.optLong("rpid", 0L).takeIf { it > 0L } ?: return null
    val member = obj.optJSONObject("member") ?: JSONObject()
    val mid =
        member.optString("mid", "").trim().toLongOrNull()
            ?: member.optLong("mid", 0L)
    val uname = member.optString("uname", "").trim()
    val avatar = member.optString("avatar", "").trim().takeIf { it.isNotBlank() }
    val content = obj.optJSONObject("content") ?: JSONObject()
    val message = content.optString("message", "").trim()
    val ctime = obj.optLong("ctime", 0L).takeIf { it > 0L } ?: 0L
    val like = obj.optLong("like", 0L).coerceAtLeast(0L)
    val replyCount = obj.optInt("count", 0).coerceAtLeast(0)
    val replyPreviews =
        if (canOpenThread && replyCount > 0) {
            val arr = obj.optJSONArray("replies") ?: JSONArray()
            parseReplyPreviewList(arr)
        } else {
            emptyList()
        }

    return PlayerCommentsAdapter.Item(
        key = rpid.toString(),
        rpid = rpid,
        oid = oid,
        type = COMMENT_TYPE_VIDEO,
        mid = mid,
        userName = uname,
        avatarUrl = avatar,
        message = message,
        ctimeSec = ctime,
        likeCount = like,
        replyCount = replyCount,
        replyPreviews = replyPreviews,
        contextTag = contextTag,
        canOpenThread = canOpenThread,
    )
}

private fun parseReplyPreviewList(arr: JSONArray, limit: Int = 2): List<PlayerCommentsAdapter.ReplyPreview> {
    if (arr.length() <= 0) return emptyList()
    val max = minOf(limit.coerceAtLeast(0), arr.length())
    if (max <= 0) return emptyList()
    val out = ArrayList<PlayerCommentsAdapter.ReplyPreview>(max)
    for (i in 0 until max) {
        val obj = arr.optJSONObject(i) ?: continue
        val member = obj.optJSONObject("member") ?: JSONObject()
        val uname = member.optString("uname", "").trim()
        val content = obj.optJSONObject("content") ?: JSONObject()
        val message = content.optString("message", "").trim()
        if (uname.isBlank() && message.isBlank()) continue
        out.add(PlayerCommentsAdapter.ReplyPreview(userName = uname, message = message))
    }
    return out
}
