package debating.ground.app.speech

import android.util.Base64
import debating.ground.app.data.SibianJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 后端发来的语音输入事件（shared/speech.ts 的 ListenEvent）。 */
sealed interface Heard {
    data object Ready : Heard
    data class Text(val text: String, val final: Boolean) : Heard
    data class Error(val message: String) : Heard

    companion object {
        fun parse(raw: String): Heard? {
            val o = runCatching { SibianJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return when (str("type")) {
                "ready" -> Ready
                "text" -> Text(str("text").orEmpty(), (o["final"] as? JsonPrimitive)?.content == "true")
                "error" -> Error(str("message") ?: "语音输入出错了。")
                else -> null
            }
        }
    }
}

/** 到后端的连接：发 JSON 文本，收到的事件交给 onEvent。 */
interface ListenLink {
    fun send(text: String)
    fun close()
}

/** 打开连接。事件和断线都要回到主线程再交给 handle。 */
fun interface ListenConnect {
    fun open(handle: (Heard) -> Unit): ListenLink
}

/** 麦克风：开始录音，每一帧 PCM 交给 onFrame（任意线程）；返回停止录音的函数。打不开时抛异常。 */
fun interface Mic {
    fun open(onFrame: (ByteArray) -> Unit): () -> Unit
}

enum class Phase { Starting, Listening, Finishing }

/**
 * 语音输入（网页版 src/lib/listen.ts）：录音 → 16k PCM 帧 → 后端 /api/stt → 讯飞 → 边说边把文字交给 onText。
 * 不会自动发送：说完由用户自己看一眼、改一改再发。录音只在内存里转发，不保存。
 */
class Listen(private val scope: CoroutineScope, private val connect: ListenConnect, private val mic: Mic, private val password: () -> String) {
    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> get() = _phase

    /** 开始听的时刻（毫秒），界面显示已录几秒。 */
    var startedAt = 0L
        private set

    private var link: ListenLink? = null
    private var stopMic: (() -> Unit)? = null
    private val queue = ArrayDeque<String>()
    private var onText: ((String) -> Unit)? = null
    private var onEnd: ((String?) -> Unit)? = null
    private val timers = mutableListOf<Job>()

    fun start(text: (String) -> Unit, end: (String?) -> Unit) {
        if (_phase.value != null) return
        _phase.value = Phase.Starting
        startedAt = System.currentTimeMillis()
        queue.clear()
        onText = text
        onEnd = end
        try {
            val l = connect.open { scope.launch { heard(it) } }
            link = l
            // 访问口令放在第一条消息里（和网页版一样，不放进地址）
            l.send(
                buildJsonObject {
                    put("type", "start")
                    put("password", password())
                }.toString(),
            )
            stopMic = mic.open { frame ->
                val audio = Base64.encodeToString(frame, Base64.NO_WRAP)
                scope.launch {
                    when (_phase.value) {
                        Phase.Listening -> sendAudio(audio)

                        Phase.Starting -> queue.addLast(audio)

                        // 后端还没准备好：先攒着
                        else -> {}
                    }
                }
            }
            // 讯飞单次最长 60 秒，留一点余量自动结束
            timers += scope.launch {
                delay(ListenSpec.MAX_MS - 2000)
                stop()
            }
        } catch (e: Exception) {
            finish(e.message?.takeIf { it.isNotEmpty() } ?: "无法开始语音输入。")
        }
    }

    private fun sendAudio(audio: String) = link?.send(
        buildJsonObject {
            put("type", "audio")
            put("audio", audio)
        }.toString(),
    )

    /** 说完了：先关麦克风，再等最后的结果。 */
    fun stop() {
        val p = _phase.value ?: return
        if (p == Phase.Finishing) return
        stopMic?.invoke()
        stopMic = null
        if (p == Phase.Starting) return cancel()
        _phase.value = Phase.Finishing
        link?.send("""{"type":"end"}""")
        timers += scope.launch {
            delay(12_000)
            finish("识别结果没有回来，请再说一次。")
        }
    }

    fun cancel() {
        if (_phase.value == null) return
        link?.send("""{"type":"cancel"}""")
        finish()
    }

    internal fun heard(e: Heard) {
        if (_phase.value == null) return
        when (e) {
            Heard.Ready -> if (_phase.value == Phase.Starting) {
                _phase.value = Phase.Listening
                while (queue.isNotEmpty()) sendAudio(queue.removeFirst())
            }

            is Heard.Error -> finish(e.message)

            is Heard.Text -> {
                onText?.invoke(e.text)
                if (e.final) finish()
            }
        }
    }

    private fun finish(error: String? = null) {
        timers.forEach { it.cancel() }
        timers.clear()
        stopMic?.invoke()
        stopMic = null
        link?.close()
        link = null
        val end = onEnd
        _phase.value = null
        queue.clear()
        onText = null
        onEnd = null
        end?.invoke(error)
    }
}
