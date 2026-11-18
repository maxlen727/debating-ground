package debating.ground.app.room

import android.os.Handler
import android.os.Looper
import debating.ground.app.data.SibianJson
import debating.ground.app.speech.Heard
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

// 和房间的连接：WebSocket 到房主后端的 /api/rooms/{id}/ws。不带访问口令（同学没有口令），
// 凭邀请码加入、凭成员凭据重连。流程在 flow/Rooms.kt，这里只管收发。

/** 后端发来的消息（shared/room.ts 的 ServerMessage）。 */
sealed interface ServerMessage {
    data class Welcome(val memberId: String, val token: String, val room: RoomView) : ServerMessage
    data class State(val room: RoomView) : ServerMessage
    data class Answer(val reqId: String, val ok: Boolean, val result: JsonObject?, val error: String) : ServerMessage
    data class Error(val message: String) : ServerMessage
    data class Stream(val id: String, val text: String) : ServerMessage
    data class Audio(val entryId: String, val seq: Int, val last: Boolean, val audio: String, val mime: String) : ServerMessage
    data class Quick(val memberId: String, val name: String, val text: String) : ServerMessage
    data class HeardMsg(val event: Heard) : ServerMessage
    data class Closed(val reason: String) : ServerMessage

    companion object {
        fun parse(raw: String): ServerMessage? = runCatching {
            val o = SibianJson.parseToJsonElement(raw) as JsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            fun view(e: JsonElement?) = SibianJson.decodeFromJsonElement(RoomView.serializer(), e!!)
            when (str("type")) {
                "welcome" -> Welcome(str("memberId"), str("token"), view(o["room"]))

                "state" -> State(view(o["room"]))

                "answer" -> Answer(str("reqId"), (o["ok"] as? JsonPrimitive)?.content == "true", o["result"] as? JsonObject, str("error"))

                "error" -> Error(str("message"))

                "stream" -> Stream(str("id"), str("text"))

                "audio" -> Audio(
                    str("entryId"),
                    (o["seq"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                    (o["last"] as? JsonPrimitive)?.content == "true",
                    str("audio"),
                    str("mime").ifEmpty { "audio/mpeg" },
                )

                "quick" -> Quick(str("memberId"), str("name"), str("text"))

                "heard" -> Heard.parse(o["event"].toString())?.let { HeardMsg(it) }

                "closed" -> Closed(str("reason"))

                else -> null
            }
        }.getOrNull()
    }
}

/** 一条连接。send 在没连上时返回 false。 */
interface RoomSocket {
    fun send(text: String): Boolean
    fun close()
}

/** 连接的事件，都在主线程上回调；closed 只回调一次。 */
interface RoomEvents {
    fun opened()
    fun message(text: String)
    fun closed()
}

fun interface RoomDialer {
    fun dial(url: String, events: RoomEvents): RoomSocket
}

fun wsURL(backend: String, roomId: String) = backend.replaceFirst(Regex("^http"), "ws") + "/api/rooms/$roomId/ws"

private val client by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build() }

/** 安卓上的实现：OkHttp。不带 Origin（不是浏览器），后端对没有来源的连接不做跨域检查。 */
class OkHttpRoomDialer : RoomDialer {
    private val main = Handler(Looper.getMainLooper())

    override fun dial(url: String, events: RoomEvents): RoomSocket {
        var open = false
        var done = false
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    main.post {
                        open = true
                        events.opened()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    main.post { events.message(text) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish()

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                private fun finish() {
                    main.post {
                        open = false
                        if (!done) {
                            done = true
                            events.closed()
                        }
                    }
                }
            },
        )
        return object : RoomSocket {
            override fun send(text: String) = open && socket.send(text)

            override fun close() {
                open = false
                done = true // 自己关的：不回调 closed
                socket.close(1000, null)
            }
        }
    }
}
