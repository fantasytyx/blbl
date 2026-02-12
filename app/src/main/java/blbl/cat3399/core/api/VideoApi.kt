package blbl.cat3399.core.api

import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.Danmaku
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import blbl.cat3399.proto.dm.DmSegMobileReply
import blbl.cat3399.proto.dmview.DmWebViewReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object VideoApi {
    private const val TAG = "BiliApi"

    internal interface JsonObj {
        fun optString(name: String, fallback: String): String

        fun optLong(name: String): Long

        fun optLong(name: String, fallback: Long): Long

        fun optInt(name: String, fallback: Int): Int

        fun optBoolean(name: String, fallback: Boolean): Boolean

        fun optJSONObject(name: String): JsonObj?
    }

    private class OrgJsonObj(
        private val obj: JSONObject,
    ) : JsonObj {
        override fun optString(name: String, fallback: String): String = obj.optString(name, fallback)

        override fun optLong(name: String): Long = obj.optLong(name)

        override fun optLong(name: String, fallback: Long): Long = obj.optLong(name, fallback)

        override fun optInt(name: String, fallback: Int): Int = obj.optInt(name, fallback)

        override fun optBoolean(name: String, fallback: Boolean): Boolean = obj.optBoolean(name, fallback)

        override fun optJSONObject(name: String): JsonObj? {
            val nested = obj.optJSONObject(name) ?: return null
            return OrgJsonObj(nested)
        }
    }

    suspend fun toViewList(): List<VideoCard> {
        val url = "https://api.bilibili.com/x/v2/history/toview"
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun spaceLikeVideoList(vmid: Long): List<VideoCard> {
        val mid = vmid.takeIf { it > 0 } ?: error("space_like_video_invalid_vmid")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/space/like/video",
                mapOf("vmid" to mid.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val dataAny = json.opt("data")
        val list =
            when (dataAny) {
                is JSONObject -> dataAny.optJSONArray("list") ?: JSONArray()
                is JSONArray -> dataAny
                else -> JSONArray()
            }
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun recommend(
        freshIdx: Int = 1,
        ps: Int = 20,
        fetchRow: Int = 1,
    ): List<VideoCard> {
        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrl(
                path = "/x/web-interface/wbi/index/top/feed/rcmd",
                params =
                    mapOf(
                        "ps" to ps.toString(),
                        "fresh_idx" to freshIdx.toString(),
                        "fresh_idx_1h" to freshIdx.toString(),
                        "fetch_row" to fetchRow.toString(),
                        "feed_version" to "V8",
                    ),
                keys = keys,
            )
        val json = BiliClient.getJson(url)
        val items = json.optJSONObject("data")?.optJSONArray("item") ?: JSONArray()
        AppLog.d(TAG, "recommend items=${items.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(items) }
    }

    suspend fun popular(pn: Int = 1, ps: Int = 20): List<VideoCard> {
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/popular",
                mapOf("pn" to pn.toString(), "ps" to ps.toString()),
            )
        val json = BiliClient.getJson(url)
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        AppLog.d(TAG, "popular list=${list.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun regionLatest(
        rid: Int,
        pn: Int = 1,
        ps: Int = 20,
    ): List<VideoCard> {
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/dynamic/region",
                mapOf("rid" to rid.toString(), "pn" to pn.toString(), "ps" to ps.toString()),
            )
        val json = BiliClient.getJson(url)
        val archives = json.optJSONObject("data")?.optJSONArray("archives") ?: JSONArray()
        AppLog.d(TAG, "region rid=$rid archives=${archives.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(archives) }
    }

    suspend fun view(bvid: String): JSONObject {
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/view",
                mapOf("bvid" to bvid),
            )
        return BiliClient.getJson(url)
    }

    suspend fun view(aid: Long): JSONObject {
        val safeAid = aid.takeIf { it > 0 } ?: error("aid required")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/view",
                mapOf("aid" to safeAid.toString()),
            )
        return BiliClient.getJson(url)
    }

    suspend fun commentPage(
        type: Int,
        oid: Long,
        sort: Int = 1,
        pn: Int = 1,
        ps: Int = 20,
        noHot: Int = 1,
    ): JSONObject {
        val safeType = type.takeIf { it > 0 } ?: error("type required")
        val safeOid = oid.takeIf { it > 0L } ?: error("oid required")
        val safePn = pn.coerceAtLeast(1)
        val safePs = ps.coerceIn(1, 20)
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/reply",
                mapOf(
                    "type" to safeType.toString(),
                    "oid" to safeOid.toString(),
                    "sort" to sort.toString(),
                    "pn" to safePn.toString(),
                    "ps" to safePs.toString(),
                    "nohot" to noHot.toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    suspend fun commentRepliesPage(
        type: Int,
        oid: Long,
        rootRpid: Long,
        pn: Int = 1,
        ps: Int = 20,
    ): JSONObject {
        val safeType = type.takeIf { it > 0 } ?: error("type required")
        val safeOid = oid.takeIf { it > 0L } ?: error("oid required")
        val safeRoot = rootRpid.takeIf { it > 0L } ?: error("root required")
        val safePn = pn.coerceAtLeast(1)
        val safePs = ps.coerceIn(1, 49)
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/v2/reply/reply",
                mapOf(
                    "type" to safeType.toString(),
                    "oid" to safeOid.toString(),
                    "root" to safeRoot.toString(),
                    "pn" to safePn.toString(),
                    "ps" to safePs.toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json.optJSONObject("data") ?: JSONObject()
    }

    suspend fun archiveRelated(bvid: String, aid: Long? = null): List<VideoCard> {
        val safeBvid = bvid.trim()
        val safeAid = aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")

        val params =
            buildMap {
                if (safeAid != null) put("aid", safeAid.toString())
                if (safeBvid.isNotBlank()) put("bvid", safeBvid)
            }
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/web-interface/archive/related",
                params,
            )
        val json = BiliClient.getJson(url)
        val list = json.optJSONArray("data") ?: JSONArray()
        AppLog.d(TAG, "archiveRelated items=${list.length()}")
        return withContext(Dispatchers.Default) { parseVideoCards(list) }
    }

    suspend fun seasonsArchivesList(
        mid: Long,
        seasonId: Long,
        pageNum: Int = 1,
        pageSize: Int = 200,
        sortReverse: Boolean = false,
    ): JSONObject {
        val safeMid = mid.takeIf { it > 0 } ?: error("mid required")
        val safeSeasonId = seasonId.takeIf { it > 0 } ?: error("seasonId required")
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list",
                mapOf(
                    "mid" to safeMid.toString(),
                    "season_id" to safeSeasonId.toString(),
                    "sort_reverse" to sortReverse.toString(),
                    "page_num" to pageNum.toString(),
                    "page_size" to pageSize.toString(),
                    "web_location" to "333.999",
                ),
            )
        return BiliClient.getJson(url, headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true), noCookies = true)
    }

    suspend fun favResourceDeal(
        rid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
    ) {
        if (rid <= 0L) error("fav_resource_deal_invalid_rid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")
        if (addMediaIds.isEmpty() && delMediaIds.isEmpty()) return

        val url = "https://api.bilibili.com/x/v3/fav/resource/deal"
        val form =
            buildMap {
                put("rid", rid.toString())
                put("type", "2")
                put("csrf", csrf)
                put("platform", "web")
                put("add_media_ids", addMediaIds.distinct().joinToString(","))
                put("del_media_ids", delMediaIds.distinct().joinToString(","))
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun archiveLike(
        bvid: String? = null,
        aid: Long? = null,
        like: Boolean,
    ) {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")

        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureBuvidActiveOncePerDay()
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/web-interface/archive/like"
        val form =
            buildMap {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                put("like", if (like) "1" else "2")
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun coinAdd(
        bvid: String? = null,
        aid: Long? = null,
        multiply: Int = 1,
        selectLike: Boolean = false,
    ) {
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0L }
        if (safeBvid.isBlank() && safeAid == null) throw BiliApiException(apiCode = -400, apiMessage = "missing_video_id")
        val mul = multiply.coerceIn(1, 2)

        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureBuvidActiveOncePerDay()
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/web-interface/coin/add"
        val form =
            buildMap {
                if (safeBvid.isNotBlank()) put("bvid", safeBvid) else put("aid", safeAid.toString())
                put("multiply", mul.toString())
                put("select_like", if (selectLike) "1" else "0")
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun onlineTotal(bvid: String, cid: Long): JSONObject {
        val url =
            BiliClient.withQuery(
                "https://api.bilibili.com/x/player/online/total",
                mapOf("bvid" to bvid, "cid" to cid.toString()),
            )
        return BiliClient.getJson(url, headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true), noCookies = true)
    }

    suspend fun playUrlDash(
        bvid: String,
        cid: Long,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject {
        WebCookieMaintainer.ensureHealthyForPlay()
        val keys = BiliClient.ensureWbiKeys()
        val hasSessData = BiliClient.cookies.hasSessData()
        @Suppress("UNUSED_VARIABLE")
        val requestedFnval = fnval
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to "1",
                "voice_balance" to "1",
                "web_location" to "1315873",
                "gaia_source" to "pre-load",
                "isGaiaAvoided" to "true",
            )
        BiliClient.cookies.getCookieValue("x-bili-gaia-vtoken")?.trim()?.takeIf { it.isNotBlank() }?.let {
            params["gaia_vtoken"] = it
        }
        if (!hasSessData) {
            params["try_look"] = "1"
        }
        return requestPlayUrl(
            path = "/x/player/wbi/playurl",
            params = params,
            keys = keys,
            headersProvider = { url -> BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true) },
            noCookies = true,
        )
    }

    suspend fun playUrlDashTryLook(
        bvid: String,
        cid: Long,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject {
        WebCookieMaintainer.ensureHealthyForPlay()
        val keys = BiliClient.ensureWbiKeys()
        @Suppress("UNUSED_VARIABLE")
        val requestedFnval = fnval
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to "1",
                "voice_balance" to "1",
                "web_location" to "1315873",
                "gaia_source" to "pre-load",
                "isGaiaAvoided" to "true",
                "try_look" to "1",
            )
        return requestPlayUrl(
            path = "/x/player/wbi/playurl",
            params = params,
            keys = keys,
            headersProvider = { url -> BiliApi.piliWebHeaders(targetUrl = url, includeCookie = false) },
            noCookies = true,
        )
    }

    suspend fun pgcPlayUrl(
        bvid: String? = null,
        aid: Long? = null,
        cid: Long? = null,
        epId: Long? = null,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureDailyMaintenance()
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")
        val params =
            mutableMapOf(
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to "1",
                "from_client" to "BROWSER",
                "drm_tech_type" to "2",
            )
        if (safeBvid.isNotBlank()) params["bvid"] = safeBvid
        safeAid?.let { params["avid"] = it.toString() }
        BiliClient.cookies.getCookieValue("x-bili-gaia-vtoken")?.trim()?.takeIf { it.isNotBlank() }?.let {
            params["gaia_vtoken"] = it
        }
        cid?.takeIf { it > 0 }?.let { params["cid"] = it.toString() }
        epId?.takeIf { it > 0 }?.let { params["ep_id"] = it.toString() }

        suspend fun request(params: Map<String, String>, includeCookie: Boolean): JSONObject {
            val url = BiliClient.withQuery("https://api.bilibili.com/pgc/player/web/playurl", params)
            val json =
                BiliClient.getJson(
                    url,
                    headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = includeCookie),
                    noCookies = true,
                )
            val code = json.optInt("code", 0)
            if (code != 0) {
                val msg = json.optString("message", json.optString("msg", ""))
                throw BiliApiException(apiCode = code, apiMessage = msg)
            }
            val result = json.optJSONObject("result") ?: JSONObject()
            if (json.optJSONObject("data") == null) json.put("data", result)
            return json
        }

        return request(params = params, includeCookie = true)
    }

    suspend fun pgcPlayUrlTryLook(
        bvid: String? = null,
        aid: Long? = null,
        cid: Long? = null,
        epId: Long? = null,
        qn: Int = 80,
        fnval: Int = 16,
    ): JSONObject {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        WebCookieMaintainer.ensureDailyMaintenance()
        val safeBvid = bvid?.trim().orEmpty()
        val safeAid = aid?.takeIf { it > 0 }
        if (safeBvid.isBlank() && safeAid == null) error("bvid or aid required")
        val params =
            mutableMapOf(
                "qn" to qn.toString(),
                "fnver" to "0",
                "fnval" to fnval.toString(),
                "fourk" to "1",
                "from_client" to "BROWSER",
                "drm_tech_type" to "2",
                "try_look" to "1",
            )
        if (safeBvid.isNotBlank()) params["bvid"] = safeBvid
        safeAid?.let { params["avid"] = it.toString() }
        cid?.takeIf { it > 0 }?.let { params["cid"] = it.toString() }
        epId?.takeIf { it > 0 }?.let { params["ep_id"] = it.toString() }

        val url = BiliClient.withQuery("https://api.bilibili.com/pgc/player/web/playurl", params)
        val json =
            BiliClient.getJson(
                url,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = false),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val result = json.optJSONObject("result") ?: JSONObject()
        if (json.optJSONObject("data") == null) json.put("data", result)
        return json
    }

    suspend fun playerWbiV2(bvid: String, cid: Long): JSONObject {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        val keys = BiliClient.ensureWbiKeys()
        val params =
            mutableMapOf(
                "bvid" to bvid,
                "cid" to cid.toString(),
            )
        val url = BiliClient.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
        return try {
            BiliClient.getJson(url, headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true), noCookies = true)
        } catch (t: Throwable) {
            // Final fallback: noCookies + try_look=1.
            params["try_look"] = "1"
            val fallbackUrl = BiliClient.signedWbiUrl(path = "/x/player/wbi/v2", params = params, keys = keys)
            BiliClient.getJson(
                fallbackUrl,
                headers = BiliApi.piliWebHeaders(targetUrl = fallbackUrl, includeCookie = false),
                noCookies = true,
            )
        }
    }

    suspend fun historyReport(
        aid: Long,
        cid: Long,
        progressSec: Long,
        platform: String = "android",
    ) {
        if (aid <= 0L) error("history_report_invalid_aid")
        if (cid <= 0L) error("history_report_invalid_cid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/v2/history/report"
        val form =
            buildMap {
                put("aid", aid.toString())
                put("cid", cid.toString())
                put("progress", progressSec.coerceAtLeast(0L).toString())
                put("platform", platform)
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun webHeartbeat(
        aid: Long? = null,
        bvid: String? = null,
        cid: Long? = null,
        epId: Long? = null,
        seasonId: Long? = null,
        playedTimeSec: Long,
        type: Int,
        subType: Int? = null,
        playType: Int = 0,
    ) {
        val safeAid = aid?.takeIf { it > 0 }
        val safeBvid = bvid?.trim()?.takeIf { it.isNotBlank() }
        val safeCid = cid?.takeIf { it > 0 }
        val safeEpId = epId?.takeIf { it > 0 }
        val safeSeasonId = seasonId?.takeIf { it > 0 }
        if (safeAid == null && safeBvid == null) error("heartbeat_missing_aid_bvid")
        if (safeCid == null) error("heartbeat_missing_cid")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")

        val url = "https://api.bilibili.com/x/click-interface/web/heartbeat"
        val form =
            buildMap {
                safeAid?.let { put("aid", it.toString()) }
                safeBvid?.let { put("bvid", it) }
                put("cid", safeCid.toString())
                safeEpId?.let { put("epid", it.toString()) }
                safeSeasonId?.let { put("sid", it.toString()) }
                put("played_time", playedTimeSec.coerceAtLeast(0L).toString())
                put("type", type.toString())
                subType?.takeIf { it > 0 }?.let { put("sub_type", it.toString()) }
                put("dt", "2")
                put("play_type", playType.toString())
                put("csrf", csrf)
            }
        val json =
            BiliClient.postFormJson(
                url,
                form = form,
                headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true),
                noCookies = true,
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    suspend fun dmSeg(cid: Long, segmentIndex: Int): List<Danmaku> {
        try {
            val url =
                BiliClient.withQuery(
                    "https://api.bilibili.com/x/v2/dm/web/seg.so",
                    mapOf(
                        "type" to "1",
                        "oid" to cid.toString(),
                        "segment_index" to segmentIndex.toString(),
                    ),
                )
            val bytes = BiliClient.getBytes(url, headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true), noCookies = true)
            val reply = DmSegMobileReply.parseFrom(bytes)
            val list =
                reply.elemsList.mapNotNull { e ->
                    val text = e.content ?: return@mapNotNull null
                    Danmaku(
                        timeMs = e.progress,
                        mode = e.mode,
                        text = text,
                        color = e.color.toInt(),
                        fontSize = e.fontsize,
                        weight = e.weight,
                    )
                }
            AppLog.d(TAG, "dmSeg cid=$cid seg=$segmentIndex bytes=${bytes.size} size=${list.size} state=${reply.state}")
            return list
        } catch (t: Throwable) {
            AppLog.w(TAG, "dmSeg failed cid=$cid seg=$segmentIndex", t)
            throw t
        }
    }

    suspend fun dmWebView(cid: Long, aid: Long? = null): BiliApi.DanmakuWebView {
        val params =
            mutableMapOf(
                "type" to "1",
                "oid" to cid.toString(),
            )
        if (aid != null && aid > 0) params["pid"] = aid.toString()
        val url = BiliClient.withQuery("https://api.bilibili.com/x/v2/dm/web/view", params)
        val bytes = BiliClient.getBytes(url, headers = BiliApi.piliWebHeaders(targetUrl = url, includeCookie = true), noCookies = true)
        val reply = DmWebViewReply.parseFrom(bytes)

        val seg = reply.dmSge
        val segTotal = seg.total.coerceAtLeast(0).toInt()
        val pageSizeMs = seg.pageSize.coerceAtLeast(0)

        val setting =
            if (reply.hasDmSetting()) {
                val s = reply.dmSetting
                val aiLevel =
                    when (s.aiLevel) {
                        0 -> 3 // 0 表示默认等级（通常为 3）
                        else -> s.aiLevel.coerceIn(0, 10)
                    }
                BiliApi.DanmakuWebSetting(
                    dmSwitch = s.dmSwitch,
                    allowScroll = s.blockscroll,
                    allowTop = s.blocktop,
                    allowBottom = s.blockbottom,
                    allowColor = s.blockcolor,
                    allowSpecial = s.blockspecial,
                    aiEnabled = s.aiSwitch,
                    aiLevel = aiLevel,
                )
            } else {
                null
            }
        AppLog.d(TAG, "dmWebView cid=$cid segTotal=$segTotal pageSizeMs=$pageSizeMs hasSetting=${setting != null}")
        return BiliApi.DanmakuWebView(
            segmentTotal = segTotal,
            segmentPageSizeMs = pageSizeMs,
            count = reply.count,
            setting = setting,
        )
    }

    internal fun parseVideoCard(obj: JsonObj): VideoCard? {
        val bvid = obj.optString("bvid", "")
        if (bvid.isBlank()) return null
        val owner = obj.optJSONObject("owner")
        val stat = obj.optJSONObject("stat")
        return VideoCard(
            bvid = bvid,
            cid = obj.optLong("cid").takeIf { it > 0 },
            title = obj.optString("title", ""),
            coverUrl = obj.optString("pic", obj.optString("cover", "")),
            durationSec = obj.optInt("duration", BiliApi.parseDuration(obj.optString("duration_text", "0:00"))),
            ownerName = owner?.optString("name", "").orEmpty(),
            ownerFace = owner?.optString("face", "")?.takeIf { it.isNotBlank() },
            ownerMid = owner?.optLong("mid")?.takeIf { it > 0 },
            view =
                stat?.optLong("view")?.takeIf { it > 0 }
                    ?: stat?.optLong("play")?.takeIf { it > 0 },
            danmaku =
                stat?.optLong("danmaku")?.takeIf { it > 0 }
                    ?: stat?.optLong("dm")?.takeIf { it > 0 },
            pubDate = obj.optLong("pubdate").takeIf { it > 0 },
            pubDateText = null,
        )
    }

    private fun parseVideoCards(arr: JSONArray): List<VideoCard> {
        val out = ArrayList<VideoCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseVideoCard(OrgJsonObj(obj))?.let { out.add(it) }
        }
        return out
    }

    private suspend fun requestPlayUrl(
        path: String,
        params: Map<String, String>,
        keys: blbl.cat3399.core.net.WbiSigner.Keys,
        headersProvider: ((String) -> Map<String, String>)? = null,
        noCookies: Boolean = false,
    ): JSONObject {
        val url = BiliClient.signedWbiUrl(path = path, params = params, keys = keys)
        val json = BiliClient.getJson(url, headers = headersProvider?.invoke(url) ?: emptyMap(), noCookies = noCookies)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        return json
    }
}
