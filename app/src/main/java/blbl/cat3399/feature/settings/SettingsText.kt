package blbl.cat3399.feature.settings

import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources
import java.util.Locale
import kotlin.math.roundToInt

object SettingsText {
    fun audioText(id: Int): String =
        when (id) {
            30251 -> "Hi-Res 无损"
            30250 -> "杜比全景声"
            30280 -> "192K"
            30232 -> "132K"
            30216 -> "64K"
            else -> id.toString()
        }

    fun subtitleLangText(code: String): String =
        when (code) {
            "auto" -> "自动"
            "zh-Hans" -> "中文(简体)"
            "zh-Hant" -> "中文(繁体)"
            "en" -> "English"
            "ja" -> "日本語"
            "ko" -> "한국어"
            else -> code
        }

    fun areaText(area: Float): String =
        when {
            area >= 0.99f -> "不限"
            area >= 0.78f -> "4/5"
            area >= 0.71f -> "3/4"
            area >= 0.62f -> "2/3"
            area >= 0.55f -> "3/5"
            area >= 0.45f -> "1/2"
            area >= 0.36f -> "2/5"
            area >= 0.29f -> "1/3"
            area >= 0.22f -> "1/4"
            else -> "1/5"
        }

    fun aiLevelText(level: Int): String = if (level == 0) "默认(3)" else level.coerceIn(1, 10).toString()

    fun gridSpanText(span: Int): String = if (span <= 0) "自动" else span.toString()

    fun startupPageText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_CATEGORY -> "分类"
            blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_DYNAMIC -> "动态"
            blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_LIVE -> "直播"
            blbl.cat3399.core.prefs.AppPrefs.STARTUP_PAGE_MY -> "我的"
            else -> "推荐"
        }

    fun sidebarSizeText(prefValue: String): String =
        when (prefValue) {
            blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_SMALL -> "小"
            blbl.cat3399.core.prefs.AppPrefs.SIDEBAR_SIZE_LARGE -> "大"
            else -> "中"
        }

    fun cdnText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_CDN_MCDN -> "mcdn"
            else -> "bilivideo"
        }

    fun holdSeekModeText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_HOLD_SEEK_MODE_SCRUB -> "拖动进度条"
            else -> "倍率加速"
        }

    fun renderViewText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_RENDER_VIEW_TEXTURE_VIEW -> "TextureView"
            else -> "SurfaceView"
        }

    fun downKeyOsdFocusTargetText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_PREV -> "上一个"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_NEXT -> "下一个"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE -> "字幕"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU -> "弹幕"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_UP -> "UP主"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIKE -> "点赞"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_COIN -> "投币"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_FAV -> "收藏"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL -> "列表面板"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED -> "更多设置"
            else -> "播放/暂停"
        }

    fun playerOsdButtonsText(buttons: List<String>): String {
        val enabled = buttons.toSet()
        val labels =
            buildList {
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PREV)) add("上一个")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_PLAY_PAUSE)) add("播放/暂停")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_NEXT)) add("下一个")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_SUBTITLE)) add("字幕")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_DANMAKU)) add("弹幕")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COMMENTS)) add("评论")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_UP)) add("UP主")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIKE)) add("点赞")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_COIN)) add("投币")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_FAV)) add("收藏")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_LIST_PANEL)) add("列表")
                if (enabled.contains(blbl.cat3399.core.prefs.AppPrefs.PLAYER_OSD_BTN_ADVANCED)) add("更多设置")
            }
        if (labels.isEmpty()) return "无"
        if (labels.size <= 4) return labels.joinToString(separator = " / ")
        return labels.take(3).joinToString(separator = " / ") + " 等${labels.size}项"
    }

    fun screenText(resources: Resources): String {
        val uiDm = resources.displayMetrics
        val width = uiDm.widthPixels
        val height = uiDm.heightPixels

        val sysDm = Resources.getSystem().displayMetrics
        val sysScale = sysDm.density
        val scaleText =
            if (sysScale.isFinite() && sysScale > 0f) {
                val x100 = (sysScale * 100f).roundToInt().toFloat() / 100f
                val x10 = (x100 * 10f).roundToInt().toFloat() / 10f
                val show =
                    when {
                        kotlin.math.abs(x100 - x100.toInt()) < 0.001f -> x100.toInt().toString()
                        kotlin.math.abs(x100 - x10) < 0.001f -> String.format(Locale.US, "%.1f", x100)
                        else -> String.format(Locale.US, "%.2f", x100)
                    }
                "${show}x"
            } else {
                "-"
            }

        // Settings -> Device Info -> Screen: show only resolution + system display scaling.
        return "${width}x${height} $scaleText"
    }

    fun ramText(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "-"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem.takeIf { it > 0 } ?: return "-"
        val avail = mi.availMem.coerceAtLeast(0)
        return "总${formatBytes(total)} 可用${formatBytes(avail)}"
    }

    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    fun playbackModeText(code: String): String =
        when (code) {
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_LOOP_ONE -> "循环该视频"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_PAGE_LIST -> "播放视频列表"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_PARTS_LIST -> "播放合集/分P视频"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_RECOMMEND -> "播放推荐视频"
            blbl.cat3399.core.prefs.AppPrefs.PLAYER_PLAYBACK_MODE_EXIT -> "退出播放器"
            else -> "什么都不做"
        }

    fun qnText(qn: Int): String =
        when (qn) {
            16 -> "360P 流畅"
            32 -> "480P 清晰"
            64 -> "720P 高清"
            74 -> "720P60 高帧率"
            80 -> "1080P 高清"
            112 -> "1080P+ 高码率"
            116 -> "1080P60 高帧率"
            120 -> "4K 超清"
            127 -> "8K 超高清"
            125 -> "HDR 真彩色"
            126 -> "杜比视界"
            129 -> "HDR Vivid"
            6 -> "240P 极速"
            100 -> "智能修复"
            else -> qn.toString()
        }
}
