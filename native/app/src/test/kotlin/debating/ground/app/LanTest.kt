package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.data.Config
import debating.ground.app.data.Persona
import debating.ground.app.data.Plan
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import debating.ground.app.lan.EngineOut
import debating.ground.app.lan.LanHost
import debating.ground.app.lan.LanRooms
import debating.ground.app.lan.RoomEngine
import debating.ground.app.lan.engineCall
import debating.ground.app.lan.parseEngineOut
import debating.ground.app.lan.randomRoomId
import debating.ground.app.room.CreatedRoom
import debating.ground.app.room.RoomDialer
import debating.ground.app.room.RoomEvents
import debating.ground.app.room.RoomSetup
import debating.ground.app.room.RoomSocket
import debating.ground.app.room.inviteLink
import debating.ground.app.room.isLanBackend
import debating.ground.app.room.isPrivateIPv4
import debating.ground.app.room.link
import debating.ground.app.room.parseInvite
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

// 局域网房间（#148）：手机上的 WebSocket 服务、房主（LanHost）把引擎的“效果”落到实处，以及邀请链接和开房间的流程。
// 真正的房间规则在 JS 引擎里（tests/native-room.test.ts 在 Node 里验证过）；这里用一个按脚本回话的假引擎。
// 邀请链接用到 android.util.Base64，所以跑在 Robolectric 上
@RunWith(RobolectricTestRunner::class)
class LanTest {
    /** 假引擎：开房间给邀请码；收到消息原样回给发的人；“ai”就请 Kotlin 调 AI；AI 结果回来发给所有人。 */
    private class FakeEngine : RoomEngine {
        val calls = CopyOnWriteArrayList<String>()
        private val socks = CopyOnWriteArrayList<String>()

        override suspend fun call(name: String, args: List<JsonElement>): EngineOut {
            calls += name
            // 最后两个参数总是时间和随机数
            assertTrue(args.last() is kotlinx.serialization.json.JsonArray)
            fun arg(i: Int) = args[i].jsonPrimitive.content
            fun send(sock: String, data: String) = buildJsonObject {
                put("kind", "send")
                put("sock", sock)
                put("data", data)
            }
            return when (name) {
                "create" -> EngineOut(
                    listOf(
                        buildJsonObject {
                            put("kind", "alarm")
                            put("room", arg(0))
                            put("at", args[args.size - 2].jsonPrimitive.content.toLong() + 50)
                        },
                    ),
                    buildJsonObject {
                        put("roomId", arg(0))
                        put("invite", "inv")
                        put("hostToken", "tok")
                        put("expiresAt", 1)
                    },
                    "",
                )

                "open" -> {
                    socks += arg(1)
                    EngineOut(emptyList(), null, "")
                }

                "message" -> EngineOut(
                    if (arg(2) == "ai") {
                        listOf(
                            buildJsonObject {
                                put("kind", "generate")
                                put("req", "1")
                                put("purpose", "discuss")
                                put("payload", buildJsonObject { put("x", 1) })
                            },
                        )
                    } else {
                        listOf(send(arg(1), "echo:" + arg(2)))
                    },
                    null,
                    "",
                )

                "resolve" -> EngineOut(
                    socks.map {
                        send(
                            it,
                            "ai:" + args[0].jsonPrimitive.content + ":" + args[1].jsonPrimitive.content + ":" + args[2].toString(),
                        )
                    },
                    null,
                    "",
                )

                else -> EngineOut(emptyList(), null, "")
            }
        }

        override fun close() {}
    }

    private val setup = RoomSetup(mode = "roundtable", concept = "机会成本", topic = "代价", keyPoints = listOf("a", "b"))

    private fun client(url: String): Pair<WebSocket, LinkedBlockingQueue<String>> {
        val got = LinkedBlockingQueue<String>()
        val ws = OkHttpClient().newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    got += text
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    got += "failed:${response?.code}"
                }
            },
        )
        return ws to got
    }

    @Test
    fun hostServesTheRoomOverWebSocketAndRunsTheEngineEffects() = runBlocking {
        val engine = FakeEngine()
        val prompts = CopyOnWriteArrayList<String>()
        val host = LanHost(
            engine,
            generate = { purpose, payload ->
                prompts += "$purpose $payload"
                buildJsonObject { put("text", "嘉宾接话") }
            },
            bind = InetAddress.getLoopbackAddress(),
            port = 0,
        )
        val roomId = "r".repeat(22)
        val created = host.create(roomId, setup, "房主")
        assertEquals(CreatedRoom(roomId, "inv", "tok", 1), created)
        val port = host.listening
        assertTrue(port > 0)

        val (ws, got) = client("ws://127.0.0.1:$port/api/rooms/$roomId/ws")
        ws.send("你好")
        assertEquals("echo:你好", got.poll(5, TimeUnit.SECONDS))
        // 引擎要 AI：Kotlin 用房主的连接调，结果交回引擎，再发给大家
        ws.send("ai")
        assertEquals("ai:1:true:{\"text\":\"嘉宾接话\"}", got.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("discuss {\"x\":1}"), prompts.toList())
        // 闹钟到点（开房间时定在 50 毫秒后）
        var waited = 0
        while ("alarm" !in engine.calls && waited++ < 100) Thread.sleep(20)
        assertTrue("alarm" in engine.calls)

        // 只有房间的连接：别的路径、不存在的房间都是 404；浏览器打开链接看到一页说明
        val (_, other) = client("ws://127.0.0.1:$port/api/rooms/${"z".repeat(22)}/ws")
        assertEquals("failed:404", other.poll(5, TimeUnit.SECONDS))
        val page = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute()
        assertEquals(200, page.code)
        assertTrue(page.body!!.string().contains("思辨场 App"))
        assertEquals(404, OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$port/api/generate").build()).execute().code)

        ws.close(1000, null)
        host.stop()
    }

    @Test
    fun engineCallsAreQuotedAndResultsParsed() {
        // 参数整体作为一个字符串字面量传进去，里面的引号、换行都转义了
        assertEquals("""sibianRoom.call("message", "[\"a\\\"b\\n\"]")""", engineCall("message", listOf(JsonPrimitive("a\"b\n"))))
        val out = parseEngineOut("""{"effects":[{"kind":"send","sock":"s1","data":"x"}],"result":null,"error":"房间已满"}""")
        assertEquals(1, out.effects.size)
        assertNull(out.result)
        assertEquals("房间已满", out.error)
    }

    @Test
    fun lanInvitesAcceptOnlyPrivateHttpAddresses() {
        for (ip in listOf("10.0.0.2", "172.16.5.1", "172.31.255.255", "192.168.1.20")) assertTrue(ip, isPrivateIPv4(ip))
        for (ip in listOf("8.8.8.8", "172.32.0.1", "192.169.0.1", "127.0.0.1", "1.2.3", "300.1.1.1")) assertFalse(ip, isPrivateIPv4(ip))
        val lan = inviteLink("http://192.168.1.20:4174", "r".repeat(22), "c", "http://192.168.1.20:4174/")
        assertEquals("http://192.168.1.20:4174", parseInvite(lan)!!.backend)
        assertNull(parseInvite(inviteLink("http://203.0.113.5:4174", "r".repeat(22), "c")))
        assertTrue(isLanBackend("http://192.168.1.20:4174"))
        assertFalse(isLanBackend("https://api.example.dev"))
        assertTrue(Regex("^[A-Za-z0-9_-]{22}$").matches(randomRoomId()))
    }

    private class FakeLan(val address: String?) : LanRooms {
        val created = mutableListOf<String>()

        override fun address() = address

        override suspend fun create(roomId: String, setup: RoomSetup, hostName: String): CreatedRoom {
            created += "$roomId ${setup.mode} $hostName"
            return CreatedRoom(roomId, "inv", "tok", 1)
        }
    }

    private class Dialer : RoomDialer {
        val urls = mutableListOf<String>()

        override fun dial(url: String, events: RoomEvents): RoomSocket {
            urls += url
            return object : RoomSocket {
                override fun send(text: String) = false

                override fun close() {}
            }
        }
    }

    private val plan = Plan(
        mode = "roundtable",
        concept = "机会成本",
        topic = "周末打工的代价",
        keyPoints = listOf("放弃的最佳选择", "隐性代价"),
        opening = "开始吧",
        personas = listOf(Persona("r0", "苏珩", "追问者")),
    )

    @Test
    fun withDirectConnectionTheRoomOpensOnThisPhone() = runBlocking {
        val lan = FakeLan("192.168.1.5")
        val dialer = Dialer()
        val l = Learning(AppState(config = Config(aiMode = "api"), plan = plan), Ai(demoTypingMs = 0), roomDialer = dialer, lan = lan)
        l.inviteFriends("小明")
        assertEquals(1, lan.created.size)
        val link = l.state.session!!.link!!
        assertEquals("http://192.168.1.5:4174", link.backend)
        assertTrue(link.host)
        assertTrue(dialer.urls.single().startsWith("ws://192.168.1.5:4174/api/rooms/"))
        // 没连 Wi-Fi：说清楚
        val off = Learning(AppState(config = Config(aiMode = "api"), plan = plan), Ai(demoTypingMs = 0), roomDialer = Dialer(), lan = FakeLan(null))
        off.inviteFriends("小明")
        assertTrue(off.state.ui.toast.contains("连上 Wi-Fi"))
        assertNull(off.state.session)
    }
}
