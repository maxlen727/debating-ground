package debating.ground.app

import debating.ground.app.data.Config
import debating.ground.app.data.Plan
import debating.ground.app.data.Session
import debating.ground.app.data.SibianJson
import debating.ground.app.room.RoomLink
import debating.ground.app.room.RoomSetup
import debating.ground.app.room.RoomView
import debating.ground.app.room.deadlineOf
import debating.ground.app.room.inviteLink
import debating.ground.app.room.link
import debating.ground.app.room.mySpeakingTurn
import debating.ground.app.room.parseInvite
import debating.ground.app.room.setupFrom
import debating.ground.app.room.speakerLabel
import debating.ground.app.room.toSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// 多人房间的纯逻辑：用网页版 src/lib/room.ts 跑出来的结果（room-cases.json）逐条比对
@RunWith(RobolectricTestRunner::class)
class RoomTest {
    private val cases = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/room-cases.json")!!.toURI()).readText()).jsonObject
    private val now = 1763366400000L // 和生成时的 NOW 一样

    private fun session(e: kotlinx.serialization.json.JsonElement) = SibianJson.decodeFromJsonElement(Session.serializer(), e)

    // 比较时房间部分按结构比（JSON 字段顺序、默认值写不写不重要）
    private fun assertSame(label: String, expected: Session, actual: Session) {
        assertEquals("$label 的房间", expected.link, actual.link)
        assertEquals(label, expected.copy(room = null), actual.copy(room = null))
    }

    @Test
    fun roomStateMapsToTheSameSessionAsTheWeb() {
        for ((i, c) in (cases.getValue("sessions") as JsonArray).withIndex()) {
            val o = c.jsonObject
            val view = SibianJson.decodeFromJsonElement(RoomView.serializer(), o.getValue("view"))
            val prev = o.getValue("prev").takeIf { it != JsonNull }?.let(::session)
            val expected = session(o.getValue("session"))
            // 网页版用 link 加上房间状态得到 room；原生版传入同样的 link
            val base = expected.link!!.let {
                RoomLink(it.backend, it.roomId, it.token, it.invite, it.memberId, it.host)
                    .copy(connected = it.connected, closed = it.closed, reflections = it.reflections)
            }
            val actual = toSession(view, prev, base, now)
            assertSame("第 ${i + 1} 个房间", expected.copy(room = expected.withDeadline(actual)), actual)
            assertEquals("第 ${i + 1} 个房间：轮到我吗", o.getValue("mySpeakingTurn").jsonPrimitive.boolean, mySpeakingTurn(actual))
            assertEquals("第 ${i + 1} 个房间：轮到谁", o.getValue("speakerLabel").jsonPrimitive.content, speakerLabel(actual))
            assertEquals("第 ${i + 1} 个房间：截止时间", o.getValue("deadline").jsonPrimitive.long, deadlineOf(view, now + 100_000))
        }
    }

    // 网页版生成时的“现在”一直在走（每次读时间加一秒），截止时间按各自的时钟算；这里只比别的字段
    private fun Session.withDeadline(actual: Session) =
        SibianJson.encodeToJsonElement(RoomLink.serializer(), link!!.copy(turnDeadline = actual.link!!.turnDeadline))

    @Test
    fun invitesParseLikeTheWeb() {
        for (c in cases.getValue("invites") as JsonArray) {
            val o = c.jsonObject
            val hash = o.getValue("hash").jsonPrimitive.content
            val p = o.getValue("parsed")
            val got = parseInvite(hash)
            if (p == JsonNull) {
                assertNull(hash, got)
            } else {
                val e = p.jsonObject
                assertEquals(
                    hash,
                    listOf(e["backend"], e["roomId"], e["invite"]).map {
                        it!!.jsonPrimitive.content
                    },
                    listOf(got!!.backend, got.roomId, got.invite),
                )
            }
        }
        // 完整的邀请链接也认
        val url = (cases.getValue("links") as JsonArray)[0].jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("inv", parseInvite(url)?.invite)
    }

    @Test
    fun inviteLinksAreTheSameAsTheWeb() {
        for (c in cases.getValue("links") as JsonArray) {
            val o = c.jsonObject
            val l = o.getValue("link").jsonObject
            fun s(k: String) = l.getValue(k).jsonPrimitive.content
            assertEquals(o.getValue("url").jsonPrimitive.content, inviteLink(s("backend"), s("roomId"), s("invite"), o.getValue("base").jsonPrimitive.content))
        }
    }

    @Test
    fun setupsAreTheSameAsTheWeb() {
        for (c in cases.getValue("setups") as JsonArray) {
            val o = c.jsonObject
            val plan = SibianJson.decodeFromJsonElement(Plan.serializer(), o.getValue("plan"))
            val config = SibianJson.decodeFromJsonElement(Config.serializer(), o.getValue("config"))
            assertEquals(SibianJson.decodeFromJsonElement(RoomSetup.serializer(), o.getValue("setup")), setupFrom(plan, config))
        }
    }

    @Test
    fun aSessionWithoutARoomHasNoLink() {
        assertNull(Session(id = "s", mode = "socratic", concept = "c", topic = "t").link)
        assertNull(Session(id = "s", mode = "socratic", concept = "c", topic = "t", room = JsonObject(emptyMap())).link)
    }
}
