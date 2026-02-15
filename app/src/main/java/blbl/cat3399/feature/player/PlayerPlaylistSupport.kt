package blbl.cat3399.feature.player

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class PlaylistParsed(
    val items: List<PlayerPlaylistItem>,
    val uiCards: List<VideoCard>,
)

internal fun parseUgcSeasonPlaylistFromViewWithUiCards(ugcSeason: JSONObject): PlaylistParsed {
    val sections = ugcSeason.optJSONArray("sections") ?: return PlaylistParsed(emptyList(), emptyList())
    val cap = ugcSeason.optInt("ep_count").coerceAtLeast(0)
    val outItems = ArrayList<PlayerPlaylistItem>(cap)
    val outCards = ArrayList<VideoCard>(cap)

    fun parseDurationSec(obj: JSONObject): Int {
        val byInt = obj.optInt("duration", 0).takeIf { it > 0 }
        if (byInt != null) return byInt
        val text = obj.optString("duration_text", obj.optString("duration", "0:00"))
        return BiliApi.parseDuration(text)
    }

    for (i in 0 until sections.length()) {
        val section = sections.optJSONObject(i) ?: continue
        val eps = section.optJSONArray("episodes") ?: continue
        for (j in 0 until eps.length()) {
            val ep = eps.optJSONObject(j) ?: continue
            val arc = ep.optJSONObject("arc") ?: JSONObject()
            val bvid = ep.optString("bvid", "").trim().ifBlank { arc.optString("bvid", "").trim() }
            if (bvid.isBlank()) continue
            val cid = ep.optLong("cid").takeIf { it > 0 } ?: arc.optLong("cid").takeIf { it > 0 }
            val aid = ep.optLong("aid").takeIf { it > 0 } ?: arc.optLong("aid").takeIf { it > 0 }
            val rawTitle =
                ep.optString("title", "").trim().takeIf { it.isNotBlank() }
                    ?: arc.optString("title", "").trim().takeIf { it.isNotBlank() }
            val title = rawTitle ?: "视频 ${outItems.size + 1}"
            outItems.add(
                PlayerPlaylistItem(
                    bvid = bvid,
                    cid = cid,
                    aid = aid,
                    title = rawTitle,
                ),
            )

            val cover =
                arc.optString("pic", arc.optString("cover", "")).trim().ifBlank {
                    ep.optString("cover", ep.optString("pic", "")).trim()
                }
            val ownerObj = arc.optJSONObject("owner") ?: ep.optJSONObject("owner") ?: JSONObject()
            val ownerName = ownerObj.optString("name", "").trim()
            val ownerFace = ownerObj.optString("face", "").trim().takeIf { it.isNotBlank() }
            val ownerMid = ownerObj.optLong("mid").takeIf { it > 0 }
            val statObj = arc.optJSONObject("stat") ?: JSONObject()
            val view =
                statObj.optLong("view").takeIf { it > 0 }
                    ?: statObj.optLong("play").takeIf { it > 0 }
            val danmaku =
                statObj.optLong("danmaku").takeIf { it > 0 }
                    ?: statObj.optLong("dm").takeIf { it > 0 }
            val pubDate =
                arc.optLong("pubdate").takeIf { it > 0 }
                    ?: ep.optLong("pubdate").takeIf { it > 0 }

            outCards.add(
                VideoCard(
                    bvid = bvid,
                    cid = cid,
                    aid = aid,
                    title = title,
                    coverUrl = cover,
                    durationSec = parseDurationSec(arc),
                    ownerName = ownerName,
                    ownerFace = ownerFace,
                    ownerMid = ownerMid,
                    view = view,
                    danmaku = danmaku,
                    pubDate = pubDate,
                    pubDateText = null,
                ),
            )
        }
    }

    return PlaylistParsed(items = outItems, uiCards = outCards)
}

internal fun parseUgcSeasonPlaylistFromView(ugcSeason: JSONObject): List<PlayerPlaylistItem> {
    return parseUgcSeasonPlaylistFromViewWithUiCards(ugcSeason).items
}

internal fun parseMultiPagePlaylistFromViewWithUiCards(viewData: JSONObject, bvid: String, aid: Long?): PlaylistParsed {
    val pages = viewData.optJSONArray("pages") ?: return PlaylistParsed(emptyList(), emptyList())
    if (pages.length() <= 1) return PlaylistParsed(emptyList(), emptyList())

    val outItems = ArrayList<PlayerPlaylistItem>(pages.length())
    val outCards = ArrayList<VideoCard>(pages.length())

    val cover = viewData.optString("pic", viewData.optString("cover", "")).trim()
    val ownerObj = viewData.optJSONObject("owner") ?: JSONObject()
    val ownerName = ownerObj.optString("name", "").trim()
    val ownerFace = ownerObj.optString("face", "").trim().takeIf { it.isNotBlank() }
    val ownerMid = ownerObj.optLong("mid").takeIf { it > 0 }
    val statObj = viewData.optJSONObject("stat") ?: JSONObject()
    val viewCount =
        statObj.optLong("view").takeIf { it > 0 }
            ?: statObj.optLong("play").takeIf { it > 0 }
    val danmakuCount =
        statObj.optLong("danmaku").takeIf { it > 0 }
            ?: statObj.optLong("dm").takeIf { it > 0 }
    val pubDate = viewData.optLong("pubdate").takeIf { it > 0 }

    fun parseDurationSec(pageObj: JSONObject): Int {
        val byInt = pageObj.optInt("duration", 0).takeIf { it > 0 }
        if (byInt != null) return byInt
        val text = pageObj.optString("duration_text", pageObj.optString("duration", "0:00"))
        return BiliApi.parseDuration(text)
    }

    for (i in 0 until pages.length()) {
        val obj = pages.optJSONObject(i) ?: continue
        val cid = obj.optLong("cid").takeIf { it > 0 } ?: continue
        val page = obj.optInt("page").takeIf { it > 0 } ?: (i + 1)
        val part = obj.optString("part", "").trim()
        val title =
            if (part.isBlank()) {
                "P$page"
            } else {
                "P$page $part"
            }
        outItems.add(
            PlayerPlaylistItem(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
            ),
        )
        outCards.add(
            VideoCard(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
                coverUrl = cover,
                durationSec = parseDurationSec(obj),
                ownerName = ownerName,
                ownerFace = ownerFace,
                ownerMid = ownerMid,
                view = viewCount,
                danmaku = danmakuCount,
                pubDate = pubDate,
                pubDateText = null,
            ),
        )
    }

    return PlaylistParsed(items = outItems, uiCards = outCards)
}

internal fun parseMultiPagePlaylistFromView(viewData: JSONObject, bvid: String, aid: Long?): List<PlayerPlaylistItem> {
    return parseMultiPagePlaylistFromViewWithUiCards(viewData, bvid = bvid, aid = aid).items
}

internal fun parseUgcSeasonPlaylistFromArchivesListWithUiCards(json: JSONObject): PlaylistParsed {
    val archives = json.optJSONObject("data")?.optJSONArray("archives") ?: return PlaylistParsed(emptyList(), emptyList())
    val outItems = ArrayList<PlayerPlaylistItem>(archives.length())
    val outCards = ArrayList<VideoCard>(archives.length())

    fun parseDurationSec(obj: JSONObject): Int {
        val byInt = obj.optInt("duration", 0).takeIf { it > 0 }
        if (byInt != null) return byInt
        val text = obj.optString("duration_text", obj.optString("duration", "0:00"))
        return BiliApi.parseDuration(text)
    }

    for (i in 0 until archives.length()) {
        val obj = archives.optJSONObject(i) ?: continue
        val bvid = obj.optString("bvid", "").trim()
        if (bvid.isBlank()) continue
        val aid = obj.optLong("aid").takeIf { it > 0 }
        val cid = obj.optLong("cid").takeIf { it > 0 }
        val title = obj.optString("title", "").trim().takeIf { it.isNotBlank() }
        val cover = obj.optString("pic", obj.optString("cover", "")).trim()

        val ownerObj = obj.optJSONObject("owner") ?: JSONObject()
        val ownerName =
            ownerObj.optString("name", "").trim().ifBlank {
                obj.optString("author", "").trim()
            }
        val ownerFace = ownerObj.optString("face", "").trim().takeIf { it.isNotBlank() }
        val ownerMid =
            ownerObj.optLong("mid").takeIf { it > 0 }
                ?: obj.optLong("mid").takeIf { it > 0 }

        val statObj = obj.optJSONObject("stat") ?: JSONObject()
        val view =
            statObj.optLong("view").takeIf { it > 0 }
                ?: statObj.optLong("play").takeIf { it > 0 }
        val danmaku =
            statObj.optLong("danmaku").takeIf { it > 0 }
                ?: statObj.optLong("dm").takeIf { it > 0 }
        val pubDate = obj.optLong("pubdate").takeIf { it > 0 }

        outItems.add(
            PlayerPlaylistItem(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title,
            ),
        )
        outCards.add(
            VideoCard(
                bvid = bvid,
                cid = cid,
                aid = aid,
                title = title ?: "视频 ${outItems.size}",
                coverUrl = cover,
                durationSec = parseDurationSec(obj),
                ownerName = ownerName,
                ownerFace = ownerFace,
                ownerMid = ownerMid,
                view = view,
                danmaku = danmaku,
                pubDate = pubDate,
                pubDateText = null,
            ),
        )
    }
    return PlaylistParsed(items = outItems, uiCards = outCards)
}

internal fun parseUgcSeasonPlaylistFromArchivesList(json: JSONObject): List<PlayerPlaylistItem> {
    return parseUgcSeasonPlaylistFromArchivesListWithUiCards(json).items
}

internal fun pickPlaylistIndexForCurrentMedia(list: List<PlayerPlaylistItem>, bvid: String, aid: Long?, cid: Long?): Int {
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

internal fun isMultiPagePlaylist(list: List<PlayerPlaylistItem>, currentBvid: String): Boolean {
    if (list.size < 2) return false
    val bvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return false
    return list.all { it.bvid == bvid && (it.cid ?: 0L) > 0L }
}

data class PlayerPlaylist(
    val items: List<PlayerPlaylistItem>,
    val uiCards: List<VideoCard>,
    val source: String?,
    val createdAtMs: Long,
    var index: Int,
)

object PlayerPlaylistStore {
    private const val MAX_PLAYLISTS = 30

    private val store = ConcurrentHashMap<String, PlayerPlaylist>()
    private val order = ArrayDeque<String>()
    private val lock = Any()

    fun put(
        items: List<PlayerPlaylistItem>,
        index: Int,
        source: String? = null,
        uiCards: List<VideoCard> = emptyList(),
    ): String {
        val outItems = ArrayList<PlayerPlaylistItem>(items.size)
        val outCards = ArrayList<VideoCard>(items.size)
        val hasCards = uiCards.isNotEmpty()

        for (i in items.indices) {
            val item = items[i]
            if (item.bvid.isBlank()) continue
            outItems.add(item)
            if (hasCards) {
                val card = uiCards.getOrNull(i)
                if (card != null) {
                    outCards.add(card)
                } else {
                    val fallbackTitle =
                        item.title?.trim()?.takeIf { it.isNotBlank() }
                            ?: "视频 ${outItems.size}"
                    outCards.add(
                        VideoCard(
                            bvid = item.bvid,
                            cid = item.cid,
                            aid = item.aid,
                            epId = item.epId,
                            title = fallbackTitle,
                            coverUrl = "",
                            durationSec = 0,
                            ownerName = "",
                            ownerFace = null,
                            ownerMid = null,
                            view = null,
                            danmaku = null,
                            pubDate = null,
                            pubDateText = null,
                        ),
                    )
                }
            }
        }

        val safeIndex = index.coerceIn(0, (outItems.size - 1).coerceAtLeast(0))
        val token = UUID.randomUUID().toString()
        val playlist =
            PlayerPlaylist(
                items = outItems,
                uiCards = if (hasCards) outCards else emptyList(),
                source = source,
                createdAtMs = System.currentTimeMillis(),
                index = safeIndex,
            )
        store[token] = playlist
        synchronized(lock) {
            order.addLast(token)
            trimLocked()
        }
        AppLog.d(
            "PlayerPlaylistStore",
            "put size=${outItems.size} cards=${if (hasCards) outCards.size else 0} idx=$safeIndex source=${source.orEmpty()} token=${token.take(8)}",
        )
        return token
    }

    fun get(token: String): PlayerPlaylist? {
        if (token.isBlank()) return null
        return store[token]
    }

    fun updateIndex(token: String, index: Int) {
        if (token.isBlank()) return
        val p = store[token] ?: return
        p.index = index.coerceIn(0, (p.items.size - 1).coerceAtLeast(0))
    }

    fun remove(token: String) {
        if (token.isBlank()) return
        store.remove(token)
        synchronized(lock) {
            order.remove(token)
        }
    }

    private fun trimLocked() {
        while (order.size > MAX_PLAYLISTS) {
            val oldest = order.removeFirstOrNull() ?: break
            store.remove(oldest)
        }
    }
}
