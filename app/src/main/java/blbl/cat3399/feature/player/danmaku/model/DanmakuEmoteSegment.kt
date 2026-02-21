package blbl.cat3399.feature.player.danmaku.model

internal sealed interface DanmakuEmoteSegment {
    data class Text(
        val start: Int,
        val end: Int,
    ) : DanmakuEmoteSegment

    data class Emote(
        val url: String,
    ) : DanmakuEmoteSegment
}

