package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.data.SibianJson
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import debating.ground.app.room.RoomDialer
import debating.ground.app.room.RoomEvents
import debating.ground.app.room.RoomSocket
import debating.ground.app.room.inviteLink
import debating.ground.app.room.link
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 多人讨论的流程：用假的房间连接，模拟后端发来的消息，看本地状态和发出去的操作对不对
@RunWith(RobolectricTestRunner::class)
class RoomFlowTest {
    private class FakeRoom : RoomDialer {
        val urls = mutableListOf<String>()
        val sent = mutableListOf<JsonObject>()
        var events: RoomEvents? = null
        var open = false

        override fun dial(url: String, events: RoomEvents): RoomSocket {
            urls += url
            this.events = events
            open = false
            return object : RoomSocket {
                override fun send(text: String): Boolean {
                    if (!open) return false
                    sent += SibianJson.parseToJsonElement(text).jsonObject
                    return true
                }

                override fun close() {
                    open = false
                }
            }
        }

        fun accept() {
            open = true
            events!!.opened()
        }

        fun server(json: String) = events!!.message(json)
    }

    private val backend = "https://api.example.dev"
    private val roomId = "r".repeat(22)
    private val launched = mutableListOf<suspend () -> Unit>()

    private fun view(status: String, cursor: Int = 0, entries: String = "[]", extra: String = "") = """
        {"id":"$roomId","status":"$status","setup":{"mode":"roundtable","concept":"机会成本","topic":"周末打工的代价","keyPoints":["放弃的最佳选择","隐性代价"],
        "personas":[{"id":"p1","name":"苏珩","title":"追问者"}],"opening":"开始吧","seats":[],"stages":[],"turns":[],"turnSeconds":0},
        "members":[{"id":"host","name":"小明","host":true,"online":true},{"id":"me","name":"小红","host":false,"online":true}],
        "entries":$entries,"covered":[],"cursor":$cursor,"speaker":null,"aiBusy":null,"error":"","aiCalls":0,"expiresAt":1$extra}
    """.trimIndent()

    @Test
    fun joiningTalkingAskingAndLeaving() {
        val room = FakeRoom()
        val l = Learning(AppState(), Ai(demoTypingMs = 0), roomDialer = room, launch = { launched += it })
        // 打开邀请链接：停在加入页，写名字再加入
        assertTrue(l.rooms.openJoin(inviteLink(backend, roomId, "inv")))
        assertEquals("join", l.state.ui.screen)
        l.rooms.join("  ")
        assertEquals("写下你在讨论里显示的名字。", l.state.ui.toast)
        l.rooms.join("小红")
        assertEquals("wss://api.example.dev/api/rooms/$roomId/ws", room.urls.single())
        room.accept()
        assertEquals(mapOf("type" to "hello", "invite" to "inv", "name" to "小红"), room.sent.last().mapValues { it.value.jsonPrimitive.content })
        // 后端欢迎：进讨论页，房间状态映射成本地讨论
        room.server("""{"type":"welcome","memberId":"me","token":"tok","room":${view("live")}}""")
        val s = l.state.session!!
        assertEquals("session", l.state.ui.screen)
        assertEquals("room-$roomId", s.id)
        assertEquals("tok", s.link!!.token)
        assertFalse(s.link!!.host)
        assertEquals("小红", l.state.config.name)
        // 圆桌是群聊：随时可以说
        l.setDraft("机会成本是放弃的最好选择")
        CoroutineScope(Dispatchers.Unconfined).launch { l.send("机会成本是放弃的最好选择") }
        val say = room.sent.last { it["type"]!!.jsonPrimitive.content == "say" }
        assertEquals("机会成本是放弃的最好选择", say["text"]!!.jsonPrimitive.content)
        assertEquals("", l.state.session!!.draft)
        // 个人请求：结果只回给自己
        var asked = false
        CoroutineScope(Dispatchers.Unconfined).launch {
            l.hint()
            asked = true
        }
        val ask = room.sent.last()
        assertEquals("ask", ask["type"]!!.jsonPrimitive.content)
        assertEquals("hint", ask["purpose"]!!.jsonPrimitive.content)
        assertEquals("正在想一条提示", l.state.ui.busy)
        room.server("""{"type":"answer","reqId":"${ask["reqId"]!!.jsonPrimitive.content}","ok":true,"result":{"text":"想想时间"}}""")
        assertTrue(asked)
        assertEquals("想想时间", l.state.ui.hint)
        assertNull(l.state.ui.busy)
        // 有人发了快捷短语
        room.server("""{"type":"quick","memberId":"host","name":"小明","text":"等一等"}""")
        assertEquals("小明：等一等", l.state.ui.toast)
        // 断线：自动重连，凭成员凭据回来（先丢掉“正在输入”的计时）
        launched.clear()
        room.events!!.closed()
        assertFalse(l.state.session!!.link!!.connected)
        assertEquals(1, launched.size)
        kotlinx.coroutines.runBlocking { launched.removeAt(0)() }
        assertEquals(2, room.urls.size)
        room.accept()
        assertEquals("resume", room.sent.last()["type"]!!.jsonPrimitive.content)
        assertEquals("tok", room.sent.last()["token"]!!.jsonPrimitive.content)
        // 房间关闭：记下原因，不再重连
        room.server("""{"type":"closed","reason":"这个房间已经结束。"}""")
        assertEquals("这个房间已经结束。", l.state.session!!.link!!.closed)
        assertEquals("这个房间已经结束。", l.state.ui.toast)
    }

    @Test
    fun finishingTheRoomGeneratesMyOwnReview() {
        val room = FakeRoom()
        val l = Learning(AppState(), Ai(demoTypingMs = 0), roomDialer = room, launch = { launched += it })
        l.rooms.openJoin(inviteLink(backend, roomId, "inv"))
        l.rooms.join("小红")
        room.accept()
        val mine = """[{"id":"m1","kind":"msg","who":"learner","name":"小红","text":"我的理解","source":"human","memberId":"me"}]"""
        room.server("""{"type":"welcome","memberId":"me","token":"tok","room":${view("live", entries = mine)}}""")
        assertEquals(1, l.state.session!!.learnerTurns)
        // 房主结束讨论：进复盘页，用我自己的发言生成复盘
        room.server("""{"type":"state","room":${view("finished", entries = mine)}}""")
        assertEquals("review", l.state.ui.screen)
        assertTrue(l.state.session!!.finished)
        CoroutineScope(Dispatchers.Unconfined).launch { launched.removeAt(0)() }
        val ask = room.sent.last()
        assertEquals("review", ask["purpose"]!!.jsonPrimitive.content)
        room.server(
            """{"type":"answer","reqId":"${ask["reqId"]!!.jsonPrimitive.content}","ok":true,"result":{"data":{"oneLine":"一句话","points":[],"misconceptions":[],"quiz":[]},"model":"m"}}""",
        )
        assertEquals("一句话", l.state.session!!.review!!.oneLine)
        assertEquals("room-$roomId", l.state.library.single().sessionId)
    }

    @Test
    fun aBrokenInviteSaysSo() {
        val l = Learning(AppState(), Ai(demoTypingMs = 0), roomDialer = FakeRoom())
        assertFalse(l.rooms.openJoin("https://example.com/#join=不完整"))
        assertEquals("邀请链接不完整，请让房主重新发一次。", l.state.ui.toast)
    }
}
