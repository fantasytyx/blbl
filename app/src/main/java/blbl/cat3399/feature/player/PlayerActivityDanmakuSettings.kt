package blbl.cat3399.feature.player

import android.widget.Toast
import blbl.cat3399.core.ui.SingleChoiceDialog
import java.util.Locale

internal fun PlayerActivity.showDanmakuOpacityDialog() {
    val options = (20 downTo 1).map { it / 20f }
    val items = options.map { String.format(Locale.US, "%.2f", it) }
    val current = options.indices.minByOrNull { kotlin.math.abs(options[it] - session.danmaku.opacity) } ?: 0
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕透明度",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = options.getOrNull(which) ?: session.danmaku.opacity
        session = session.copy(danmaku = session.danmaku.copy(opacity = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuTextSizeDialog() {
    val options = (10..60 step 2).toList()
    val items = options.map { it.toString() }.toTypedArray()
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].toFloat() - session.danmaku.textSizeSp) }
            ?: options.indexOf(18).takeIf { it >= 0 }
            ?: 0
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕字体大小(sp)",
        items = items.toList(),
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = (options.getOrNull(which) ?: session.danmaku.textSizeSp.toInt()).toFloat()
        session = session.copy(danmaku = session.danmaku.copy(textSizeSp = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuSpeedDialog() {
    val options = (1..10).toList()
    val items = options.map { it.toString() }
    val current = options.indexOf(session.danmaku.speedLevel).let { if (it >= 0) it else 3 }
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕速度(1~10)",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = options.getOrNull(which) ?: session.danmaku.speedLevel
        session = session.copy(danmaku = session.danmaku.copy(speedLevel = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}

internal fun PlayerActivity.showDanmakuAreaDialog() {
    val options = listOf(
        (1f / 5f) to "1/5",
        0.25f to "1/4",
        (1f / 3f) to "1/3",
        (2f / 5f) to "2/5",
        0.50f to "1/2",
        (3f / 5f) to "3/5",
        (2f / 3f) to "2/3",
        0.75f to "3/4",
        (4f / 5f) to "4/5",
        1.00f to "不限",
    )
    val items = options.map { it.second }
    val current =
        options.indices.minByOrNull { kotlin.math.abs(options[it].first - session.danmaku.area) }
            ?: options.lastIndex
    SingleChoiceDialog.show(
        context = this,
        title = "弹幕区域",
        items = items,
        checkedIndex = current,
        negativeText = "取消",
    ) { which, _ ->
        val v = options.getOrNull(which)?.first ?: session.danmaku.area
        session = session.copy(danmaku = session.danmaku.copy(area = v))
        binding.danmakuView.invalidate()
        refreshSettings(binding.recyclerSettings.adapter as PlayerSettingsAdapter)
    }
}
