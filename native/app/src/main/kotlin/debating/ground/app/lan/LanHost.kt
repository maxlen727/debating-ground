package debating.ground.app.lan

import debating.ground.app.ai.AiFailure
import debating.ground.app.data.SibianJson
import debating.ground.app.room.CreatedRoom
import debating.ground.app.room.RoomSetup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// 安卓手机当局域网房主（#148）：手机上开一个只给房间连接和网页版界面的服务（WsServer），
// 房间规则在 JS 引擎里跑（RoomEngine），AI 用房主的“直接连接”（generate）。
// 引擎每次返回要做的事，这里去做：发消息、断开连接、定闹钟、调 AI，AI 的结果再交回引擎。
// 同学凭邀请码进房，拿不到 API Key：Key 只在“直接连接”的加密存储和请求头里，从不进引擎，也不发给任何连接。

const val LAN_PORT = 4174
private val ROOM_PATH = Regex("^/api/rooms/([A-Za-z0-9_-]{22})/ws$")

class LanHost(
    private val engine: RoomEngine,
    /** 用房主的连接调一次 AI，返回和后端一样的结果（text、covered、terms、data、model）。 */
    private val generate: suspend (purpose: String, payload: JsonElement) -> JsonObject,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** 测试里绑回环地址、用随机端口。 */
    private val bind: InetAddress? = null,
    private val port: Int = LAN_PORT,
    /** 网页版界面的文件（相对 dist/），给没装 App 的同学的浏览器（#151）。 */
    private val files: (name: String) -> ByteArray? = { null },
) {
    private var server: WsServer? = null
    private val socks = ConcurrentHashMap<String, WsConn>()
    private val alarms = ConcurrentHashMap<String, Job>()
    private val rooms = ConcurrentHashMap.newKeySet<String>()
    private val nextSock = AtomicLong()

    // 引擎同一时间只处理一件事，按到达的顺序：每条连接上的消息不会乱序
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch { for (job in queue) runCatching { job() } }
    }

    /** 正在监听的端口（还没开房间时是 -1）。 */
    val listening get() = server?.port ?: -1

    val roomCount get() = rooms.size

    private fun entropy() = JsonArray(ByteArray(512).also { random.nextBytes(it) }.map { JsonPrimitive(it.toInt() and 0xff) })

    private suspend fun call(name: String, vararg args: JsonElement): EngineOut {
        // JsonArray 本身也是 List：用 listOf 包一层，随机数才是一个参数，而不是被摊开
        val out = engine.call(name, args.toList() + listOf(JsonPrimitive(now()), entropy()))
        out.effects.forEach(::apply)
        return out
    }

    private fun enqueue(job: suspend () -> Unit) {
        queue.trySend(job)
    }

    /** 开一个房间（第一次开时启动服务）。返回邀请码和房主凭据，和 Worker 的 /api/rooms 一样。 */
    suspend fun create(roomId: String, setup: RoomSetup, hostName: String): CreatedRoom {
        if (server == null) {
            val s = WsServer(::route, files)
            s.start(port, bind)
            server = s
        }
        val done = CompletableDeferred<EngineOut>()
        enqueue {
            done.complete(
                runCatching { call("create", JsonPrimitive(roomId), SibianJson.encodeToJsonElement(RoomSetup.serializer(), setup), JsonPrimitive(hostName)) }
                    .getOrElse { EngineOut(emptyList(), null, it.message ?: "没能创建房间。") },
            )
        }
        val out = done.await()
        if (out.error.isNotEmpty() || out.result == null) throw AiFailure(out.error.ifEmpty { "没能创建房间。" })
        rooms += roomId
        return runCatching { SibianJson.decodeFromJsonElement(CreatedRoom.serializer(), out.result) }.getOrElse { throw AiFailure("没能创建房间。") }
    }

    private fun route(path: String): WsHandler? {
        val roomId = ROOM_PATH.find(path)?.groupValues?.get(1) ?: return null
        if (roomId !in rooms) return null
        val id = "s" + nextSock.incrementAndGet()
        return object : WsHandler {
            override fun onOpen(conn: WsConn) {
                socks[id] = conn
                enqueue { call("open", JsonPrimitive(roomId), JsonPrimitive(id)) }
            }

            override fun onMessage(text: String) = enqueue { call("message", JsonPrimitive(roomId), JsonPrimitive(id), JsonPrimitive(text)) }

            override fun onClose() {
                socks.remove(id)
                enqueue { call("close", JsonPrimitive(roomId), JsonPrimitive(id)) }
            }
        }
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    // 引擎要做的事
    private fun apply(e: JsonObject) {
        when (str(e, "kind")) {
            "send" -> socks[str(e, "sock")]?.send(str(e, "data"))

            "close" -> socks.remove(str(e, "sock"))?.close()

            "alarm" -> {
                val room = str(e, "room")
                val at = runCatching { e.getValue("at").jsonPrimitive.long }.getOrNull() ?: return
                alarms.remove(room)?.cancel()
                alarms[room] = scope.launch {
                    delay((at - now()).coerceAtLeast(0))
                    enqueue { call("alarm", JsonPrimitive(room)) }
                }
            }

            // AI 不占着引擎：结果回来以后再排队交回去，这期间别人照常发言
            "generate" -> {
                val req = str(e, "req")
                val purpose = str(e, "purpose")
                val payload = e["payload"] ?: JsonObject(emptyMap())
                scope.launch {
                    val result = runCatching { generate(purpose, payload) }
                    enqueue {
                        result.fold(
                            { call("resolve", JsonPrimitive(req), JsonPrimitive(true), it) },
                            { call("resolve", JsonPrimitive(req), JsonPrimitive(false), JsonPrimitive(it.message ?: "AI 请求失败。")) },
                        )
                    }
                }
            }

            // 房间到期或结束后删除：没有房间了就停掉服务
            "gone" -> {
                val room = str(e, "room")
                alarms.remove(room)?.cancel()
                rooms.remove(room)
                if (rooms.isEmpty()) stopServer()
            }
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        socks.values.forEach { runCatching { it.close() } }
        socks.clear()
    }

    /** 不当房主了（App 退出、换成别的连接方式）：关掉服务和所有房间。 */
    fun stop() {
        stopServer()
        rooms.clear()
        alarms.values.forEach { it.cancel() }
        alarms.clear()
        engine.close()
        scope.cancel()
    }
}
