package blbl.cat3399.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoApiParseTest {
    private class MapJsonObj(
        private val values: Map<String, Any?>,
    ) : VideoApi.JsonObj {
        override fun optString(name: String, fallback: String): String {
            val v = values[name] ?: return fallback
            return when (v) {
                is String -> v
                is Number -> v.toString()
                is Boolean -> v.toString()
                else -> v.toString()
            }
        }

        override fun optLong(name: String): Long = optLong(name, 0L)

        override fun optLong(name: String, fallback: Long): Long {
            val v = values[name] ?: return fallback
            return when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull() ?: fallback
                is Boolean -> if (v) 1L else 0L
                else -> fallback
            }
        }

        override fun optInt(name: String, fallback: Int): Int {
            val v = values[name] ?: return fallback
            return when (v) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull() ?: fallback
                is Boolean -> if (v) 1 else 0
                else -> fallback
            }
        }

        override fun optBoolean(name: String, fallback: Boolean): Boolean {
            val v = values[name] ?: return fallback
            return when (v) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v.trim().equals("true", ignoreCase = true)
                else -> fallback
            }
        }

        override fun optJSONObject(name: String): VideoApi.JsonObj? {
            val v = values[name] ?: return null
            return when (v) {
                is VideoApi.JsonObj -> v
                is Map<*, *> ->
                    MapJsonObj(
                        v.entries.associate { (k, value) -> k?.toString().orEmpty() to value },
                    )
                else -> null
            }
        }
    }

    @Test
    fun parseVideoCard_should_parse_owner_stat_and_duration_text() {
        val card =
            VideoApi.parseVideoCard(
                MapJsonObj(
                    mapOf(
                        "bvid" to "BV1xx411c7mD",
                        "cid" to 100L,
                        "title" to "Hello",
                        "pic" to "https://i.example.com/cover.jpg",
                        "duration_text" to "1:02",
                        "owner" to
                            mapOf(
                                "name" to "uploader",
                                "face" to "https://i.example.com/face.jpg",
                                "mid" to 123L,
                            ),
                        "stat" to
                            mapOf(
                                "view" to 456L,
                                "danmaku" to 7L,
                            ),
                        "pubdate" to 1700000000L,
                    ),
                ),
            )

        assertNotNull(card)
        val c = card!!
        assertEquals("BV1xx411c7mD", c.bvid)
        assertEquals(100L, c.cid)
        assertEquals("Hello", c.title)
        assertEquals("https://i.example.com/cover.jpg", c.coverUrl)
        assertEquals(62, c.durationSec)
        assertEquals("uploader", c.ownerName)
        assertEquals(123L, c.ownerMid)
        assertEquals(456L, c.view)
        assertEquals(7L, c.danmaku)
        assertEquals(1700000000L, c.pubDate)
    }

    @Test
    fun parseVideoCard_should_skip_blank_bvid_and_fallback_to_play_dm_and_cover() {
        val skipped =
            VideoApi.parseVideoCard(
                MapJsonObj(
                    mapOf(
                        "bvid" to "   ",
                        "title" to "ignored",
                    ),
                ),
            )
        assertNull(skipped)

        val card =
            VideoApi.parseVideoCard(
                MapJsonObj(
                    mapOf(
                        "bvid" to "BV1xx411c7mD",
                        "cover" to "https://i.example.com/cover.jpg",
                        "duration" to 10,
                        "stat" to
                            mapOf(
                                "view" to 0,
                                "play" to 99L,
                                "danmaku" to 0,
                                "dm" to 12L,
                            ),
                    ),
                ),
            )

        assertNotNull(card)
        val c = card!!
        assertEquals("https://i.example.com/cover.jpg", c.coverUrl)
        assertEquals(10, c.durationSec)
        assertEquals(99L, c.view)
        assertEquals(12L, c.danmaku)
    }
}
