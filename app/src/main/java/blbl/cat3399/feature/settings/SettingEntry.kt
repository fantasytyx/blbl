package blbl.cat3399.feature.settings

/**
 * Stable id for settings entries.
 *
 * Must remain stable across copy/text changes; do NOT use [SettingEntry.title] to drive behavior.
 */
enum class SettingId(
    val key: String,
) {
    // 通用设置
    ImageQuality("image_quality"),
    UserAgent("user_agent"),
    Ipv4OnlyEnabled("ipv4_only_enabled"),
    GaiaVgate("gaia_vgate"),
    ClearCache("clear_cache"),
    ClearLogin("clear_login"),

    // 页面设置
    StartupPage("startup_page"),
    GridSpanCount("grid_span_count"),
    DynamicGridSpanCount("dynamic_grid_span_count"),
    PgcGridSpanCount("pgc_grid_span_count"),
    SidebarSize("sidebar_size"),
    FullscreenEnabled("fullscreen_enabled"),
    TabSwitchFollowsFocus("tab_switch_follows_focus"),

    // 播放设置
    PlayerPreferredQn("player_preferred_qn"),
    PlayerPreferredQnPortrait("player_preferred_qn_portrait"),
    PlayerPreferredAudioId("player_preferred_audio_id"),
    PlayerCdnPreference("player_cdn_preference"),
    PlayerSpeed("player_speed"),
    PlayerHoldSeekSpeed("player_hold_seek_speed"),
    PlayerHoldSeekMode("player_hold_seek_mode"),
    PlayerAutoResumeEnabled("player_auto_resume_enabled"),
    PlayerAutoSkipSegmentsEnabled("player_auto_skip_segments_enabled"),
    PlayerOpenDetailBeforePlay("player_open_detail_before_play"),
    PlayerPlaybackMode("player_playback_mode"),
    SubtitlePreferredLang("subtitle_preferred_lang"),
    SubtitleTextSizeSp("subtitle_text_size_sp"),
    SubtitleEnabledDefault("subtitle_enabled_default"),
    PlayerPreferredCodec("player_preferred_codec"),
    PlayerRenderView("player_render_view"),
    PlayerOsdButtons("player_osd_buttons"),
    PlayerDebugEnabled("player_debug_enabled"),
    PlayerDoubleBackToExit("player_double_back_to_exit"),
    PlayerDownKeyOsdFocusTarget("player_down_key_osd_focus_target"),
    PlayerPersistentBottomProgressEnabled("player_persistent_bottom_progress_enabled"),

    // 弹幕设置
    DanmakuEnabled("danmaku_enabled"),
    DanmakuOpacity("danmaku_opacity"),
    DanmakuTextSizeSp("danmaku_text_size_sp"),
    DanmakuArea("danmaku_area"),
    DanmakuSpeed("danmaku_speed"),
    DanmakuFollowBiliShield("danmaku_follow_bili_shield"),
    DanmakuAiShieldEnabled("danmaku_ai_shield_enabled"),
    DanmakuAiShieldLevel("danmaku_ai_shield_level"),
    DanmakuAllowScroll("danmaku_allow_scroll"),
    DanmakuAllowTop("danmaku_allow_top"),
    DanmakuAllowBottom("danmaku_allow_bottom"),
    DanmakuAllowColor("danmaku_allow_color"),
    DanmakuAllowSpecial("danmaku_allow_special"),

    // 关于应用
    AppVersion("app_version"),
    ProjectUrl("project_url"),
    QqGroup("qq_group"),
    LogTag("log_tag"),
    ExportLogs("export_logs"),
    CheckUpdate("check_update"),

    // 设备信息
    DeviceCpu("device_cpu"),
    DeviceModel("device_model"),
    DeviceSystem("device_system"),
    DeviceScreen("device_screen"),
    DeviceRam("device_ram"),
}

data class SettingEntry(
    val id: SettingId,
    val title: String,
    val value: String,
    val desc: String?,
)
