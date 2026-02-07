package blbl.cat3399.feature.player

import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.SingleChoiceDialog
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun PlayerActivity.pickSubtitleItem(items: List<SubtitleItem>): SubtitleItem? {
    if (items.isEmpty()) return null
    val prefs = BiliClient.prefs
    val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
    if (preferred == "auto" || preferred.isBlank()) return items.first()
    return items.firstOrNull { it.lan.equals(preferred, ignoreCase = true) } ?: items.first()
}

internal fun PlayerActivity.subtitleLangSubtitle(): String {
    if (subtitleItems.isEmpty()) return "无/未加载"
    val prefs = BiliClient.prefs
    val preferred = session.subtitleLangOverride ?: prefs.subtitlePreferredLang
    return resolveSubtitleLang(preferred)
}

internal fun PlayerActivity.resolveSubtitleLang(code: String): String {
    if (subtitleItems.isEmpty()) return "无"
    if (code == "auto" || code.isBlank()) {
        val first = subtitleItems.first()
        return "自动：${first.lanDoc}"
    }
    val found = subtitleItems.firstOrNull { it.lan.equals(code, ignoreCase = true) } ?: subtitleItems.first()
    return "${found.lanDoc}"
}

internal fun PlayerActivity.showSubtitleLangDialog() {
    val exo = player ?: return
    if (subtitleItems.isEmpty()) {
        Toast.makeText(this, "该视频暂无字幕", Toast.LENGTH_SHORT).show()
        return
    }
    val prefs = BiliClient.prefs
    val items =
        buildList {
            add("自动（取第一个）")
            subtitleItems.forEach { add(it.lanDoc) }
        }
    val effective = (session.subtitleLangOverride ?: prefs.subtitlePreferredLang).trim()
    val currentLabel =
        when {
            effective.equals("auto", ignoreCase = true) || effective.isBlank() -> "自动（取第一个）"
            else -> subtitleItems.firstOrNull { it.lan.equals(effective, ignoreCase = true) }?.lanDoc ?: subtitleItems.first().lanDoc
        }
    val checked = items.indexOf(currentLabel).coerceAtLeast(0)
    val applyAndReload = {
        lifecycleScope.launch {
            subtitleConfig = buildSubtitleConfigFromCurrentSelection(bvid = currentBvid, cid = currentCid)
            subtitleAvailabilityKnown = true
            subtitleAvailable = subtitleConfig != null
            applySubtitleEnabled(exo)
            refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
            updateSubtitleButton()
            reloadStream(keepPosition = true)
        }
    }
    SingleChoiceDialog.show(
        context = this,
        title = "字幕语言（本次播放）",
        items = items,
        checkedIndex = checked,
        negativeText = "取消",
        neutralText = "默认",
        onNeutral = {
            session = session.copy(subtitleLangOverride = null)
            applyAndReload()
        },
    ) { which, _ ->
        val chosen = items.getOrNull(which).orEmpty()
        session =
            when {
                chosen.startsWith("自动") -> session.copy(subtitleLangOverride = "auto")
                else -> {
                    val code = subtitleItems.firstOrNull { it.lanDoc == chosen }?.lan ?: subtitleItems.first().lan
                    session.copy(subtitleLangOverride = code)
                }
            }
        applyAndReload()
    }
}

internal fun PlayerActivity.showSubtitleTextSizeDialog() {
    val options = (10..60 step 2).toList()
    val items = options.map { it.toString() }.toTypedArray()
    val current =
        options.indices.minByOrNull { abs(options[it].toFloat() - session.subtitleTextSizeSp) }
            ?: options.indexOf(26).takeIf { it >= 0 }
            ?: 0
    SingleChoiceDialog.show(
        context = this,
        title = "字幕字体大小(sp)",
        items = items.toList(),
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = (options.getOrNull(which) ?: session.subtitleTextSizeSp.toInt()).toFloat()
        session = session.copy(subtitleTextSizeSp = v)
        applySubtitleTextSize()
        (binding.recyclerSettings.adapter as? PlayerSettingsAdapter)?.let { refreshSettings(it) }
    }
}
