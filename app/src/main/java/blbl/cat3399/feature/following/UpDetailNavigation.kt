package blbl.cat3399.feature.following

import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.AppToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun Fragment.openUpDetailFromVideoCard(card: VideoCard) {
    val mid = card.ownerMid?.takeIf { it > 0L }
    if (mid != null) {
        startUpDetail(mid = mid, card = card)
        return
    }

    val safeAid = card.aid?.takeIf { it > 0L }
    if (card.bvid.isBlank() && safeAid == null) {
        context?.let { AppToast.show(it, "未获取到 UP 主信息") }
        return
    }

    lifecycleScope.launch {
        try {
            val json = if (card.bvid.isNotBlank()) BiliApi.view(card.bvid) else BiliApi.view(safeAid ?: 0L)
            val code = json.optInt("code", 0)
            if (code != 0) {
                val msg = json.optString("message", json.optString("msg", ""))
                throw BiliApiException(apiCode = code, apiMessage = msg)
            }
            val owner = json.optJSONObject("data")?.optJSONObject("owner")
            val viewMid = owner?.optLong("mid") ?: 0L
            if (viewMid <= 0L) {
                context?.let { AppToast.show(it, "未获取到 UP 主信息") }
                return@launch
            }
            startUpDetail(mid = viewMid, card = card)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context?.let { AppToast.show(it, "未获取到 UP 主信息") }
        }
    }
}

private fun Fragment.startUpDetail(mid: Long, card: VideoCard) {
    startActivity(
        Intent(requireContext(), UpDetailActivity::class.java)
            .putExtra(UpDetailActivity.EXTRA_MID, mid)
            .apply {
                card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
            },
    )
}
