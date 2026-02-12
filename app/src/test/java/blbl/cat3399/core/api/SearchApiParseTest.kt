package blbl.cat3399.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchApiParseTest {
    private class MapJsonObj(
        private val values: Map<String, Any?>,
    ) : SearchApi.JsonObj {
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
    }

    @Test
    fun parseSearchVideoCards_should_strip_html_and_parse_duration() {
        val card =
            SearchApi.parseSearchVideoCard(
                MapJsonObj(
                    mapOf(
                        "bvid" to "BV1xx411c7mD",
                        "title" to "<em class=\"keyword\">Hello</em> World",
                        "pic" to "https://i.example.com/cover.jpg",
                        "duration" to "1:02",
                        "author" to "uploader",
                        "mid" to 123L,
                        "play" to 456L,
                        "video_review" to 7L,
                        "pubdate" to 1700000000L,
                    ),
                ),
            )

        assertNotNull(card)
        val c = card!!
        assertEquals("BV1xx411c7mD", c.bvid)
        assertEquals("Hello World", c.title)
        assertEquals(62, c.durationSec)
        assertEquals(123L, c.ownerMid)
    }

    @Test
    fun parseSearchUsers_should_set_live_flags_and_skip_blank_name() {
        val live =
            SearchApi.parseSearchUser(
                MapJsonObj(
                    mapOf(
                        "mid" to 1L,
                        "uname" to "<em>Foo</em>",
                        "upic" to "https://i.example.com/avatar.jpg",
                        "usign" to "sign",
                        "room_id" to 999L,
                        "is_live" to 1,
                    ),
                ),
            )
        assertNotNull(live)
        val u = live!!
        assertEquals(1L, u.mid)
        assertEquals("Foo", u.name)
        assertTrue(u.isLive)
        assertEquals(999L, u.liveRoomId)

        val skipped =
            SearchApi.parseSearchUser(
                MapJsonObj(
                    mapOf(
                        "mid" to 2L,
                        "uname" to "   ",
                    ),
                ),
            )
        assertEquals(null, skipped)
    }
}
