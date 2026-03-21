package blbl.cat3399.core.prefs

import android.content.Context
import android.provider.Settings
import org.json.JSONArray
import java.util.UUID
import kotlin.math.roundToInt

class AppPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("blbl_prefs", Context.MODE_PRIVATE)

    var disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, value).apply()

    var webRefreshToken: String?
        get() = prefs.getString(KEY_WEB_REFRESH_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_WEB_REFRESH_TOKEN, value?.trim()).apply()

    var webCookieRefreshCheckedEpochDay: Long
        get() = prefs.getLong(KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY, value).apply()

    var biliTicketCheckedEpochDay: Long
        get() = prefs.getLong(KEY_BILI_TICKET_CHECKED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_BILI_TICKET_CHECKED_EPOCH_DAY, value).apply()

    var sidebarSize: String
        get() = prefs.getString(KEY_SIDEBAR_SIZE, SIDEBAR_SIZE_MEDIUM) ?: SIDEBAR_SIZE_MEDIUM
        set(value) = prefs.edit().putString(KEY_SIDEBAR_SIZE, value).apply()

    var uiScaleFactor: Float
        get() {
            if (prefs.contains(KEY_UI_SCALE_FACTOR)) {
                return normalizeUiScaleFactor(prefs.getFloat(KEY_UI_SCALE_FACTOR, UI_SCALE_FACTOR_DEFAULT))
            }
            // Legacy fallback (kept for migration): sidebar_size small/medium/large -> 0.90/1.00/1.10.
            return when (sidebarSize) {
                SIDEBAR_SIZE_SMALL -> 0.90f
                SIDEBAR_SIZE_LARGE -> 1.10f
                else -> UI_SCALE_FACTOR_DEFAULT
            }
        }
        set(value) = prefs.edit().putFloat(KEY_UI_SCALE_FACTOR, normalizeUiScaleFactor(value)).apply()

    var themePreset: String
        get() {
            val raw = prefs.getString(KEY_THEME_PRESET, THEME_PRESET_DEFAULT) ?: THEME_PRESET_DEFAULT
            val v = raw.trim()
            return when (v) {
                THEME_PRESET_DEFAULT,
                THEME_PRESET_TV_PINK,
                -> v
                else -> THEME_PRESET_DEFAULT
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    THEME_PRESET_TV_PINK -> THEME_PRESET_TV_PINK
                    else -> THEME_PRESET_DEFAULT
                }
            prefs.edit().putString(KEY_THEME_PRESET, normalized).apply()
        }

    var startupPage: String
        get() = prefs.getString(KEY_STARTUP_PAGE, STARTUP_PAGE_HOME)?.trim()?.takeIf { it.isNotBlank() } ?: STARTUP_PAGE_HOME
        set(value) {
            val v = value.trim().takeIf { it.isNotBlank() } ?: STARTUP_PAGE_HOME
            prefs.edit().putString(KEY_STARTUP_PAGE, v).apply()
        }

    var userAgent: String
        get() = prefs.getString(KEY_UA, DEFAULT_UA) ?: DEFAULT_UA
        set(value) = prefs.edit().putString(KEY_UA, value).apply()

    var ipv4OnlyEnabled: Boolean
        get() = prefs.getBoolean(KEY_IPV4_ONLY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_IPV4_ONLY_ENABLED, value).apply()

    var deviceBuvid: String
        get() = prefs.getString(KEY_DEVICE_BUVID, null) ?: generateBuvid().also { prefs.edit().putString(KEY_DEVICE_BUVID, it).apply() }
        set(value) = prefs.edit().putString(KEY_DEVICE_BUVID, value.trim()).apply()

    /**
     * Stable per-device UUID for diagnostics (e.g. log uploads).
     *
     * - Pref-backed (memory): once created/derived, keep using it.
     * - Prefer deriving from ANDROID_ID (stable across reinstall on most devices).
     * - Fallback to random UUID when ANDROID_ID is unavailable/invalid.
     */
    var deviceUuid: String
        get() {
            val cached =
                prefs.getString(KEY_DEVICE_UUID, null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { isValidUuid(it) }
            if (cached != null) return cached

            val derived = deriveDeviceUuid()
            prefs.edit().putString(KEY_DEVICE_UUID, derived).apply()
            return derived
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_UUID, value.trim()).apply()

    var buvidActivatedMid: Long
        get() = prefs.getLong(KEY_BUVID_ACTIVATED_MID, 0L)
        set(value) = prefs.edit().putLong(KEY_BUVID_ACTIVATED_MID, value).apply()

    var buvidActivatedEpochDay: Long
        get() = prefs.getLong(KEY_BUVID_ACTIVATED_EPOCH_DAY, -1L)
        set(value) = prefs.edit().putLong(KEY_BUVID_ACTIVATED_EPOCH_DAY, value).apply()

    var imageQuality: String
        get() = prefs.getString(KEY_IMAGE_QUALITY, "medium") ?: "medium"
        set(value) = prefs.edit().putString(KEY_IMAGE_QUALITY, value).apply()

    var danmakuEnabled: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ENABLED, value).apply()

    var danmakuAllowTop: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_TOP, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_TOP, value).apply()

    var danmakuAllowBottom: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_BOTTOM, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_BOTTOM, value).apply()

    var danmakuAllowScroll: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_SCROLL, value).apply()

    var danmakuAllowColor: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_COLOR, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_COLOR, value).apply()

    var danmakuAllowSpecial: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_ALLOW_SPECIAL, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_ALLOW_SPECIAL, value).apply()

    var danmakuAiShieldEnabled: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_AI_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_AI_ENABLED, value).apply()

    var danmakuAiShieldLevel: Int
        get() = prefs.getInt(KEY_DANMAKU_AI_LEVEL, 0)
        set(value) = prefs.edit().putInt(KEY_DANMAKU_AI_LEVEL, value.coerceIn(0, 10)).apply()

    var danmakuFollowBiliShield: Boolean
        get() = prefs.getBoolean(KEY_DANMAKU_FOLLOW_BILI_SHIELD, true)
        set(value) = prefs.edit().putBoolean(KEY_DANMAKU_FOLLOW_BILI_SHIELD, value).apply()

    var danmakuOpacity: Float
        get() = prefs.getFloat(KEY_DANMAKU_OPACITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_OPACITY, value).apply()

    var danmakuTextSizeSp: Float
        get() = prefs.getFloat(KEY_DANMAKU_TEXT_SIZE_SP, 18f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_TEXT_SIZE_SP, value).apply()

    var danmakuSpeed: Int
        get() = prefs.getInt(KEY_DANMAKU_SPEED, 4)
        set(value) = prefs.edit().putInt(KEY_DANMAKU_SPEED, value).apply()

    var danmakuArea: Float
        get() = prefs.getFloat(KEY_DANMAKU_AREA, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_DANMAKU_AREA, value).apply()

    var playerPreferredQn: Int
        get() = prefs.getInt(KEY_PLAYER_PREFERRED_QN, 80)
        set(value) = prefs.edit().putInt(KEY_PLAYER_PREFERRED_QN, value).apply()

    var playerPreferredQnPortrait: Int
        get() {
            if (!prefs.contains(KEY_PLAYER_PREFERRED_QN_PORTRAIT)) return playerPreferredQn
            return prefs.getInt(KEY_PLAYER_PREFERRED_QN_PORTRAIT, playerPreferredQn)
        }
        set(value) = prefs.edit().putInt(KEY_PLAYER_PREFERRED_QN_PORTRAIT, value).apply()

    var playerPreferredCodec: String
        get() = prefs.getString(KEY_PLAYER_CODEC, "AVC") ?: "AVC"
        set(value) = prefs.edit().putString(KEY_PLAYER_CODEC, value).apply()

    var playerRenderViewType: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_RENDER_VIEW, PLAYER_RENDER_VIEW_SURFACE_VIEW) ?: PLAYER_RENDER_VIEW_SURFACE_VIEW
            val v = raw.trim()
            return when (v) {
                PLAYER_RENDER_VIEW_SURFACE_VIEW,
                PLAYER_RENDER_VIEW_TEXTURE_VIEW,
                -> v

                else -> PLAYER_RENDER_VIEW_SURFACE_VIEW
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_RENDER_VIEW_SURFACE_VIEW,
                    PLAYER_RENDER_VIEW_TEXTURE_VIEW,
                    -> v

                    else -> PLAYER_RENDER_VIEW_SURFACE_VIEW
                }
            prefs.edit().putString(KEY_PLAYER_RENDER_VIEW, normalized).apply()
        }

    var playerEngineKind: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_ENGINE_KIND, PLAYER_ENGINE_EXO) ?: PLAYER_ENGINE_EXO
            val v = raw.trim()
            return when (v) {
                PLAYER_ENGINE_EXO,
                PLAYER_ENGINE_IJK,
                -> v
                else -> PLAYER_ENGINE_EXO
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_ENGINE_IJK -> PLAYER_ENGINE_IJK
                    else -> PLAYER_ENGINE_EXO
                }
            prefs.edit().putString(KEY_PLAYER_ENGINE_KIND, normalized).apply()
        }

    var playerPreferredAudioId: Int
        get() = prefs.getInt(KEY_PLAYER_AUDIO_ID, 30280)
        set(value) = prefs.edit().putInt(KEY_PLAYER_AUDIO_ID, value).apply()

    var playerCdnPreference: String
        get() = prefs.getString(KEY_PLAYER_CDN_PREFERENCE, PLAYER_CDN_BILIVIDEO) ?: PLAYER_CDN_BILIVIDEO
        set(value) = prefs.edit().putString(KEY_PLAYER_CDN_PREFERENCE, value).apply()

    /**
     * When enabled, try to rewrite live m3u8 urls to remove Bilibili's transcoding suffix
     * (e.g. `_2500`, `_bluray`) in order to fetch the origin stream and get higher bitrate.
     *
     * Note: Some rooms/CDNs may reject the rewritten url (403/404) or have unstable playlists.
     */
    var liveHighBitrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE_HIGH_BITRATE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_HIGH_BITRATE_ENABLED, value).apply()

    var subtitlePreferredLang: String
        get() = prefs.getString(KEY_SUBTITLE_LANG, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_SUBTITLE_LANG, value).apply()

    var subtitleEnabledDefault: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLE_ENABLED_DEFAULT, value).apply()

    var subtitleTextSizeSp: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_TEXT_SIZE_SP, 26f)
            if (!v.isFinite()) return 26f
            return v.coerceIn(10f, 60f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(10f, 60f) else 26f
            prefs.edit().putFloat(KEY_SUBTITLE_TEXT_SIZE_SP, v).apply()
        }

    var subtitleBottomPaddingFraction: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_BOTTOM_PADDING_FRACTION, SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT)
            if (!v.isFinite()) return SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT
            return v.coerceIn(0f, 0.30f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(0f, 0.30f) else SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT
            prefs.edit().putFloat(KEY_SUBTITLE_BOTTOM_PADDING_FRACTION, v).apply()
        }

    var subtitleBackgroundOpacity: Float
        get() {
            val v = prefs.getFloat(KEY_SUBTITLE_BACKGROUND_OPACITY, SUBTITLE_BACKGROUND_OPACITY_DEFAULT)
            if (!v.isFinite()) return SUBTITLE_BACKGROUND_OPACITY_DEFAULT
            return v.coerceIn(0f, 1.0f)
        }
        set(value) {
            val v = if (value.isFinite()) value.coerceIn(0f, 1.0f) else SUBTITLE_BACKGROUND_OPACITY_DEFAULT
            prefs.edit().putFloat(KEY_SUBTITLE_BACKGROUND_OPACITY, v).apply()
        }

    var playerSpeed: Float
        get() = prefs.getFloat(KEY_PLAYER_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PLAYER_SPEED, value).apply()

    var playerHoldSeekSpeed: Float
        get() {
            val v = prefs.getFloat(KEY_PLAYER_HOLD_SEEK_SPEED, PLAYER_HOLD_SEEK_SPEED_DEFAULT)
            if (!v.isFinite()) return PLAYER_HOLD_SEEK_SPEED_DEFAULT
            return v.coerceIn(1.5f, 4.0f)
        }
        set(value) = prefs.edit().putFloat(KEY_PLAYER_HOLD_SEEK_SPEED, value.coerceIn(1.5f, 4.0f)).apply()

    var playerHoldSeekMode: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_HOLD_SEEK_MODE, PLAYER_HOLD_SEEK_MODE_SPEED) ?: PLAYER_HOLD_SEEK_MODE_SPEED
            val v = raw.trim()
            return when (v) {
                PLAYER_HOLD_SEEK_MODE_SPEED,
                PLAYER_HOLD_SEEK_MODE_SCRUB,
                PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME,
                -> v

                else -> PLAYER_HOLD_SEEK_MODE_SPEED
            }
        }
        set(value) {
            val v =
                when (value) {
                    PLAYER_HOLD_SEEK_MODE_SPEED,
                    PLAYER_HOLD_SEEK_MODE_SCRUB,
                    PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME,
                    -> value

                    else -> PLAYER_HOLD_SEEK_MODE_SPEED
                }
            prefs.edit().putString(KEY_PLAYER_HOLD_SEEK_MODE, v).apply()
        }

    var playerAutoResumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_RESUME_ENABLED, value).apply()

    var playerAutoSkipSegmentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED, value).apply()

    var playerOpenDetailBeforePlay: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY, value).apply()

    var fullscreenEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    var tabSwitchFollowsFocus: Boolean
        get() = prefs.getBoolean(KEY_TAB_SWITCH_FOLLOWS_FOCUS, true)
        set(value) = prefs.edit().putBoolean(KEY_TAB_SWITCH_FOLLOWS_FOCUS, value).apply()

    /**
     * Main page (Home/Category/Live/My) "Back" key focus-return scheme.
     *
     * Applied when focus is inside a page content area:
     * - Tab pages: focus is inside the ViewPager content.
     * - Dynamic page: focus is inside the page root (no tabs).
     *
     * Schemes:
     * - [MAIN_BACK_FOCUS_SCHEME_A] (Default): content -> focus current tab; tab -> focus sidebar.
     * - [MAIN_BACK_FOCUS_SCHEME_B]: content -> go to tab0 content; when already at tab0 content -> focus sidebar.
     * - [MAIN_BACK_FOCUS_SCHEME_C]: content -> focus sidebar.
     *
     * Notes:
     * - Search has its own back behavior (input/results panels).
     * - App-level navigation (return to startup page / exit) is still handled by MainActivity when unconsumed.
     */
    var mainBackFocusScheme: String
        get() {
            val raw = prefs.getString(KEY_MAIN_BACK_FOCUS_SCHEME, MAIN_BACK_FOCUS_SCHEME_A) ?: MAIN_BACK_FOCUS_SCHEME_A
            val v = raw.trim()
            return when (v) {
                MAIN_BACK_FOCUS_SCHEME_A,
                MAIN_BACK_FOCUS_SCHEME_B,
                MAIN_BACK_FOCUS_SCHEME_C,
                -> v
                else -> MAIN_BACK_FOCUS_SCHEME_A
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    MAIN_BACK_FOCUS_SCHEME_A,
                    MAIN_BACK_FOCUS_SCHEME_B,
                    MAIN_BACK_FOCUS_SCHEME_C,
                    -> v
                    else -> MAIN_BACK_FOCUS_SCHEME_A
                }
            prefs.edit().putString(KEY_MAIN_BACK_FOCUS_SCHEME, normalized).apply()
        }

    var playerDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DEBUG, value).apply()

    var playerDoubleBackToExit: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_DOUBLE_BACK_TO_EXIT, true)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_DOUBLE_BACK_TO_EXIT, value).apply()

    var playerDownKeyOsdFocusTarget: String
        get() {
            val raw =
                prefs.getString(KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET, PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE)
                    ?: PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
            val value = raw.trim()
            val normalized =
                when (value) {
                    PLAYER_DOWN_KEY_OSD_FOCUS_RECOMMEND_LEGACY,
                    PLAYER_DOWN_KEY_OSD_FOCUS_PLAYLIST_LEGACY,
                    -> PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL

                    else -> value
                }
            return when (normalized) {
                PLAYER_DOWN_KEY_OSD_FOCUS_PREV,
                PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE,
                PLAYER_DOWN_KEY_OSD_FOCUS_NEXT,
                PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE,
                PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU,
                PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS,
                PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL,
                PLAYER_DOWN_KEY_OSD_FOCUS_UP,
                PLAYER_DOWN_KEY_OSD_FOCUS_LIKE,
                PLAYER_DOWN_KEY_OSD_FOCUS_COIN,
                PLAYER_DOWN_KEY_OSD_FOCUS_FAV,
                PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL,
                PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED,
                -> normalized

                else -> PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
            }
        }
        set(value) {
            val next =
                when (value) {
                    PLAYER_DOWN_KEY_OSD_FOCUS_PREV,
                    PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_NEXT,
                    PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU,
                    PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS,
                    PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL,
                    PLAYER_DOWN_KEY_OSD_FOCUS_UP,
                    PLAYER_DOWN_KEY_OSD_FOCUS_LIKE,
                    PLAYER_DOWN_KEY_OSD_FOCUS_COIN,
                    PLAYER_DOWN_KEY_OSD_FOCUS_FAV,
                    PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL,
                    PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED,
                    -> value

                    else -> PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE
                }
            prefs.edit().putString(KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET, next).apply()
        }

    var playerPersistentBottomProgressEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, false)
        set(value) = prefs.edit().putBoolean(KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS, value).apply()

    var playerVideoShotPreviewSize: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE, PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM)
                ?: PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
            val v = raw.trim()
            return when (v) {
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM,
                PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE,
                -> v
                else -> PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM,
                    PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE,
                    -> v
                    else -> PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM
                }
            prefs.edit().putString(KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE, normalized).apply()
        }

    var playerAudioBalanceLevel: String
        get() {
            val raw = prefs.getString(KEY_PLAYER_AUDIO_BALANCE_LEVEL, PLAYER_AUDIO_BALANCE_OFF) ?: PLAYER_AUDIO_BALANCE_OFF
            val v = raw.trim()
            return when (v) {
                PLAYER_AUDIO_BALANCE_OFF,
                PLAYER_AUDIO_BALANCE_LOW,
                PLAYER_AUDIO_BALANCE_MEDIUM,
                PLAYER_AUDIO_BALANCE_HIGH,
                -> v

                else -> PLAYER_AUDIO_BALANCE_OFF
            }
        }
        set(value) {
            val v = value.trim()
            val normalized =
                when (v) {
                    PLAYER_AUDIO_BALANCE_LOW -> PLAYER_AUDIO_BALANCE_LOW
                    PLAYER_AUDIO_BALANCE_MEDIUM -> PLAYER_AUDIO_BALANCE_MEDIUM
                    PLAYER_AUDIO_BALANCE_HIGH -> PLAYER_AUDIO_BALANCE_HIGH
                    else -> PLAYER_AUDIO_BALANCE_OFF
                }
            prefs.edit().putString(KEY_PLAYER_AUDIO_BALANCE_LEVEL, normalized).apply()
        }

    var playerPlaybackMode: String
        get() = prefs.getString(KEY_PLAYER_PLAYBACK_MODE, PLAYER_PLAYBACK_MODE_NONE) ?: PLAYER_PLAYBACK_MODE_NONE
        set(value) = prefs.edit().putString(KEY_PLAYER_PLAYBACK_MODE, value).apply()

    var playerOsdButtons: List<String>
        get() {
            // IMPORTANT:
            // - If the key doesn't exist yet, user never configured OSD -> return our default set.
            // - If the key exists (even if empty), respect it and only normalize (e.g. keep Play/Pause).
            if (!prefs.contains(KEY_PLAYER_OSD_BUTTONS)) return DEFAULT_PLAYER_OSD_BUTTONS
            val stored = loadStringList(KEY_PLAYER_OSD_BUTTONS)
            val normalized = normalizePlayerOsdButtons(stored)
            return migratePlayerOsdDetailButtonIfNeeded(normalized)
        }
        set(value) {
            saveStringList(KEY_PLAYER_OSD_BUTTONS, normalizePlayerOsdButtons(value))
            // Once user manually configures OSD buttons, never force-enable new buttons again.
            prefs.edit().putBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, true).apply()
        }

    internal var playerCustomShortcuts: List<PlayerCustomShortcut>
        get() = PlayerCustomShortcutsStore.parse(prefs.getString(KEY_PLAYER_CUSTOM_SHORTCUTS, null))
        set(value) {
            if (value.isEmpty()) {
                prefs.edit().remove(KEY_PLAYER_CUSTOM_SHORTCUTS).apply()
            } else {
                prefs.edit().putString(KEY_PLAYER_CUSTOM_SHORTCUTS, PlayerCustomShortcutsStore.serialize(value)).apply()
            }
        }

    var gridSpanCount: Int
        get() {
            val stored = prefs.getInt(KEY_GRID_SPAN, 4)
            val span = if (stored <= 0) 4 else stored
            return span.coerceIn(1, 6)
        }
        set(value) {
            val span = if (value <= 0) 4 else value
            prefs.edit().putInt(KEY_GRID_SPAN, span.coerceIn(1, 6)).apply()
        }

    var dynamicGridSpanCount: Int
        get() = prefs.getInt(KEY_DYNAMIC_GRID_SPAN, 3)
        set(value) = prefs.edit().putInt(KEY_DYNAMIC_GRID_SPAN, value).apply()

    var pgcGridSpanCount: Int
        get() {
            val stored = prefs.getInt(KEY_PGC_GRID_SPAN, 6)
            val span = if (stored <= 0) 6 else stored
            return span.coerceIn(1, 6)
        }
        set(value) {
            val span = if (value <= 0) 6 else value
            prefs.edit().putInt(KEY_PGC_GRID_SPAN, span.coerceIn(1, 6)).apply()
        }

    var pgcEpisodeOrderReversed: Boolean
        get() = prefs.getBoolean(KEY_PGC_EPISODE_ORDER_REVERSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PGC_EPISODE_ORDER_REVERSED, value).apply()

    var searchHistory: List<String>
        get() = loadStringList(KEY_SEARCH_HISTORY)
        set(value) = saveStringList(KEY_SEARCH_HISTORY, value)

    var gaiaVgateVVoucher: String?
        get() = prefs.getString(KEY_GAIA_VGATE_V_VOUCHER, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_GAIA_VGATE_V_VOUCHER, value?.trim()).apply()

    var gaiaVgateVVoucherSavedAtMs: Long
        get() = prefs.getLong(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, -1L)
        set(value) = prefs.edit().putLong(KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS, value).apply()

    fun addSearchHistory(keyword: String, maxSize: Int = 20) {
        val k = keyword.trim()
        if (k.isBlank()) return
        val old = searchHistory
        val out = ArrayList<String>(old.size + 1)
        out.add(k)
        for (item in old) {
            if (item.equals(k, ignoreCase = true)) continue
            out.add(item)
            if (out.size >= maxSize) break
        }
        searchHistory = out
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    private fun loadStringList(key: String): List<String> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotBlank()) out.add(s)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveStringList(key: String, value: List<String>) {
        val arr = JSONArray()
        for (s in value) {
            val v = s.trim()
            if (v.isNotBlank()) arr.put(v)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun migratePlayerOsdDetailButtonIfNeeded(normalized: List<String>): List<String> {
        if (prefs.getBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, false)) return normalized
        // Requirement: auto-enable the new "Detail" button even for users who previously customized OSD.
        // Do it only once so the user can later disable it in Settings.
        prefs.edit().putBoolean(KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED, true).apply()
        if (normalized.contains(PLAYER_OSD_BTN_DETAIL)) return normalized

        val migrated = normalized + PLAYER_OSD_BTN_DETAIL
        saveStringList(KEY_PLAYER_OSD_BUTTONS, migrated)
        return migrated
    }

    private fun normalizePlayerOsdButtons(value: List<String>): List<String> {
        val out = ArrayList<String>(value.size + 1)
        val seen = HashSet<String>(value.size + 1)
        for (raw in value) {
            val key = raw.trim()
            if (key.isBlank()) continue
            if (!PLAYER_OSD_BUTTON_KEYS.contains(key)) continue
            if (seen.add(key)) out.add(key)
        }
        if (!seen.contains(PLAYER_OSD_BTN_PLAY_PAUSE)) {
            out.add(0, PLAYER_OSD_BTN_PLAY_PAUSE)
        }
        return out
    }

    private fun normalizeUiScaleFactor(value: Float): Float {
        val v = if (value.isFinite()) value else UI_SCALE_FACTOR_DEFAULT
        val clamped = v.coerceIn(UI_SCALE_FACTOR_MIN, UI_SCALE_FACTOR_MAX)
        val scaled = (clamped * 100f).roundToInt()
        val step = (UI_SCALE_FACTOR_STEP * 100f).roundToInt().coerceAtLeast(1)
        val snapped = ((scaled + step / 2) / step) * step
        return (snapped / 100f).coerceIn(UI_SCALE_FACTOR_MIN, UI_SCALE_FACTOR_MAX)
    }

    companion object {
        const val STARTUP_PAGE_HOME = "home"
        const val STARTUP_PAGE_CATEGORY = "category"
        const val STARTUP_PAGE_DYNAMIC = "dynamic"
        const val STARTUP_PAGE_LIVE = "live"
        const val STARTUP_PAGE_MY = "my"

        const val SIDEBAR_SIZE_SMALL = "small"
        const val SIDEBAR_SIZE_MEDIUM = "medium"
        const val SIDEBAR_SIZE_LARGE = "large"

        const val UI_SCALE_FACTOR_MIN = 0.70f
        const val UI_SCALE_FACTOR_MAX = 1.40f
        const val UI_SCALE_FACTOR_STEP = 0.05f
        const val UI_SCALE_FACTOR_DEFAULT = 1.00f

        const val THEME_PRESET_DEFAULT = "default"
        const val THEME_PRESET_TV_PINK = "tv_pink"

        // Main page back focus schemes for "Settings -> Page Settings".
        const val MAIN_BACK_FOCUS_SCHEME_A = "A"
        const val MAIN_BACK_FOCUS_SCHEME_B = "B"
        const val MAIN_BACK_FOCUS_SCHEME_C = "C"

        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val KEY_WEB_REFRESH_TOKEN = "web_refresh_token"
        private const val KEY_WEB_COOKIE_REFRESH_CHECKED_EPOCH_DAY = "web_cookie_refresh_checked_epoch_day"
        private const val KEY_BILI_TICKET_CHECKED_EPOCH_DAY = "bili_ticket_checked_epoch_day"

        private const val KEY_UA = "ua"
        private const val KEY_IPV4_ONLY_ENABLED = "ipv4_only_enabled"
        private const val KEY_DEVICE_BUVID = "device_buvid"
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_BUVID_ACTIVATED_MID = "buvid_activated_mid"
        private const val KEY_BUVID_ACTIVATED_EPOCH_DAY = "buvid_activated_epoch_day"
        private const val KEY_SIDEBAR_SIZE = "sidebar_size"
        private const val KEY_UI_SCALE_FACTOR = "ui_scale_factor"
        private const val KEY_THEME_PRESET = "theme_preset"
        private const val KEY_STARTUP_PAGE = "startup_page"
        private const val KEY_IMAGE_QUALITY = "image_quality"
        private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
        private const val KEY_DANMAKU_ALLOW_TOP = "danmaku_allow_top"
        private const val KEY_DANMAKU_ALLOW_BOTTOM = "danmaku_allow_bottom"
        private const val KEY_DANMAKU_ALLOW_SCROLL = "danmaku_allow_scroll"
        private const val KEY_DANMAKU_ALLOW_COLOR = "danmaku_allow_color"
        private const val KEY_DANMAKU_ALLOW_SPECIAL = "danmaku_allow_special"
        private const val KEY_DANMAKU_AI_ENABLED = "danmaku_ai_enabled"
        private const val KEY_DANMAKU_AI_LEVEL = "danmaku_ai_level"
        private const val KEY_DANMAKU_FOLLOW_BILI_SHIELD = "danmaku_follow_bili_shield"
        private const val KEY_DANMAKU_OPACITY = "danmaku_opacity"
        private const val KEY_DANMAKU_TEXT_SIZE_SP = "danmaku_text_size_sp"
        private const val KEY_DANMAKU_SPEED = "danmaku_speed"
        private const val KEY_DANMAKU_AREA = "danmaku_area"
        private const val KEY_PLAYER_PREFERRED_QN = "player_preferred_qn"
        private const val KEY_PLAYER_PREFERRED_QN_PORTRAIT = "player_preferred_qn_portrait"
        private const val KEY_PLAYER_CODEC = "player_codec"
        private const val KEY_PLAYER_RENDER_VIEW = "player_render_view"
        private const val KEY_PLAYER_ENGINE_KIND = "player_engine_kind"
        private const val KEY_PLAYER_AUDIO_ID = "player_audio_id"
        private const val KEY_PLAYER_CDN_PREFERENCE = "player_cdn_preference"
        private const val KEY_LIVE_HIGH_BITRATE_ENABLED = "live_high_bitrate_enabled"
        private const val KEY_SUBTITLE_LANG = "subtitle_lang"
        private const val KEY_SUBTITLE_ENABLED_DEFAULT = "subtitle_enabled_default"
        private const val KEY_SUBTITLE_TEXT_SIZE_SP = "subtitle_text_size_sp"
        private const val KEY_SUBTITLE_BOTTOM_PADDING_FRACTION = "subtitle_bottom_padding_fraction"
        private const val KEY_SUBTITLE_BACKGROUND_OPACITY = "subtitle_background_opacity"
        private const val KEY_PLAYER_SPEED = "player_speed"
        private const val KEY_PLAYER_HOLD_SEEK_SPEED = "player_hold_seek_speed"
        private const val KEY_PLAYER_HOLD_SEEK_MODE = "player_hold_seek_mode"
        private const val KEY_PLAYER_AUTO_RESUME_ENABLED = "player_auto_resume_enabled"
        private const val KEY_PLAYER_AUTO_SKIP_SEGMENTS_ENABLED = "player_auto_skip_segments_enabled"
        private const val KEY_PLAYER_OPEN_DETAIL_BEFORE_PLAY = "player_open_detail_before_play"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_TAB_SWITCH_FOLLOWS_FOCUS = "tab_switch_follows_focus"
        private const val KEY_MAIN_BACK_FOCUS_SCHEME = "main_back_focus_scheme"
        private const val KEY_PLAYER_DEBUG = "player_debug_enabled"
        private const val KEY_PLAYER_DOUBLE_BACK_TO_EXIT = "player_double_back_on_ended"
        private const val KEY_PLAYER_DOWN_KEY_OSD_FOCUS_TARGET = "player_down_key_osd_focus_target"
        private const val KEY_PLAYER_PERSISTENT_BOTTOM_PROGRESS = "player_persistent_bottom_progress"
        private const val KEY_PLAYER_VIDEOSHOT_PREVIEW_SIZE = "player_videoshot_preview_size"
        private const val KEY_PLAYER_AUDIO_BALANCE_LEVEL = "player_audio_balance_level"
        private const val KEY_PLAYER_PLAYBACK_MODE = "player_playback_mode"
        private const val KEY_PLAYER_OSD_BUTTONS = "player_osd_buttons"
        private const val KEY_PLAYER_OSD_BUTTONS_DETAIL_MIGRATED = "player_osd_buttons_detail_migrated"
        private const val KEY_PLAYER_CUSTOM_SHORTCUTS = "player_custom_shortcuts"
        private const val KEY_GRID_SPAN = "grid_span"
        private const val KEY_DYNAMIC_GRID_SPAN = "dynamic_grid_span"
        private const val KEY_PGC_GRID_SPAN = "pgc_grid_span"
        private const val KEY_PGC_EPISODE_ORDER_REVERSED = "pgc_episode_order_reversed"
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val KEY_GAIA_VGATE_V_VOUCHER = "gaia_vgate_v_voucher"
        private const val KEY_GAIA_VGATE_V_VOUCHER_SAVED_AT_MS = "gaia_vgate_v_voucher_saved_at_ms"

        // PC browser UA is used to reduce CDN 403 for media resources.
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

        const val PLAYER_CDN_BILIVIDEO = "bilivideo"
        const val PLAYER_CDN_MCDN = "mcdn"

        const val PLAYER_RENDER_VIEW_SURFACE_VIEW = "surface_view"
        const val PLAYER_RENDER_VIEW_TEXTURE_VIEW = "texture_view"

        const val PLAYER_ENGINE_EXO = "exoplayer"
        const val PLAYER_ENGINE_IJK = "ijkplayer"

        const val PLAYER_AUDIO_BALANCE_OFF = "off"
        const val PLAYER_AUDIO_BALANCE_LOW = "low"
        const val PLAYER_AUDIO_BALANCE_MEDIUM = "medium"
        const val PLAYER_AUDIO_BALANCE_HIGH = "high"

        const val PLAYER_PLAYBACK_MODE_NONE = "none"
        const val PLAYER_PLAYBACK_MODE_LOOP_ONE = "loop_one"
        const val PLAYER_PLAYBACK_MODE_EXIT = "exit"
        const val PLAYER_PLAYBACK_MODE_PAGE_LIST = "page_list"
        const val PLAYER_PLAYBACK_MODE_PARTS_LIST = "parts_list"
        const val PLAYER_PLAYBACK_MODE_RECOMMEND = "recommend"

        const val PLAYER_HOLD_SEEK_MODE_SPEED = "speed"
        const val PLAYER_HOLD_SEEK_MODE_SCRUB = "scrub"
        const val PLAYER_HOLD_SEEK_MODE_SCRUB_FIXED_TIME = "scrub_fixed_time"
        const val PLAYER_HOLD_SEEK_SPEED_DEFAULT = 3.0f

        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_OFF = "off"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_SMALL = "small"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_MEDIUM = "medium"
        const val PLAYER_VIDEOSHOT_PREVIEW_SIZE_LARGE = "large"

        private const val SUBTITLE_BOTTOM_PADDING_FRACTION_DEFAULT = 0.16f
        private const val SUBTITLE_BACKGROUND_OPACITY_DEFAULT = 34f / 255f

        const val PLAYER_OSD_BTN_PREV = "prev"
        const val PLAYER_OSD_BTN_PLAY_PAUSE = "play_pause"
        const val PLAYER_OSD_BTN_NEXT = "next"
        const val PLAYER_OSD_BTN_SUBTITLE = "subtitle"
        const val PLAYER_OSD_BTN_DANMAKU = "danmaku"
        const val PLAYER_OSD_BTN_COMMENTS = "comments"
        const val PLAYER_OSD_BTN_DETAIL = "detail"
        const val PLAYER_OSD_BTN_UP = "up"
        const val PLAYER_OSD_BTN_LIKE = "like"
        const val PLAYER_OSD_BTN_COIN = "coin"
        const val PLAYER_OSD_BTN_FAV = "fav"
        const val PLAYER_OSD_BTN_LIST_PANEL = "list_panel"
        const val PLAYER_OSD_BTN_ADVANCED = "advanced"

        val DEFAULT_PLAYER_OSD_BUTTONS: List<String> =
            listOf(
                PLAYER_OSD_BTN_PLAY_PAUSE,
                PLAYER_OSD_BTN_NEXT,
                PLAYER_OSD_BTN_SUBTITLE,
                PLAYER_OSD_BTN_DANMAKU,
                PLAYER_OSD_BTN_COMMENTS,
                PLAYER_OSD_BTN_DETAIL,
                PLAYER_OSD_BTN_UP,
                PLAYER_OSD_BTN_LIST_PANEL,
                PLAYER_OSD_BTN_ADVANCED,
            )

        private val PLAYER_OSD_BUTTON_KEYS: Set<String> =
            setOf(
                PLAYER_OSD_BTN_PREV,
                PLAYER_OSD_BTN_PLAY_PAUSE,
                PLAYER_OSD_BTN_NEXT,
                PLAYER_OSD_BTN_SUBTITLE,
                PLAYER_OSD_BTN_DANMAKU,
                PLAYER_OSD_BTN_COMMENTS,
                PLAYER_OSD_BTN_DETAIL,
                PLAYER_OSD_BTN_UP,
                PLAYER_OSD_BTN_LIKE,
                PLAYER_OSD_BTN_COIN,
                PLAYER_OSD_BTN_FAV,
                PLAYER_OSD_BTN_LIST_PANEL,
                PLAYER_OSD_BTN_ADVANCED,
            )

        const val PLAYER_DOWN_KEY_OSD_FOCUS_PREV = "prev"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_PLAY_PAUSE = "play_pause"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_NEXT = "next"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_SUBTITLE = "subtitle"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_DANMAKU = "danmaku"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_COMMENTS = "comments"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_DETAIL = "detail"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_UP = "up"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_LIKE = "like"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_COIN = "coin"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_FAV = "fav"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_LIST_PANEL = "list_panel"
        const val PLAYER_DOWN_KEY_OSD_FOCUS_ADVANCED = "advanced"

        private const val PLAYER_DOWN_KEY_OSD_FOCUS_RECOMMEND_LEGACY = "recommend"
        private const val PLAYER_DOWN_KEY_OSD_FOCUS_PLAYLIST_LEGACY = "playlist"

        private fun generateBuvid(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            val md5 = java.security.MessageDigest.getInstance("MD5").digest(bytes)
            val hex = buildString(md5.size * 2) { md5.forEach { append(String.format(java.util.Locale.US, "%02x", it)) } }
            return "XY${hex[2]}${hex[12]}${hex[22]}$hex"
        }

        private fun isValidUuid(text: String): Boolean {
            return runCatching {
                UUID.fromString(text.trim())
                true
            }.getOrDefault(false)
        }
    }

    private fun deriveDeviceUuid(): String {
        val androidId =
            runCatching {
                Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (!androidId.isNullOrBlank()) {
            val name = "blbl:device_uuid:$androidId"
            return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
        }

        return UUID.randomUUID().toString()
    }
}
