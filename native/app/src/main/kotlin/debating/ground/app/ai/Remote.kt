package debating.ground.app.ai

import debating.ground.app.data.SibianJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors

// 连接自己部署的后端（Cloudflare Worker）：和网页版 src/lib/api.ts 的远程部分一样的接口和错误文案。
// 访问口令只放在请求头里，不进讨论记录和导出（约定 1）。

/** 给用户看的失败原因。AI 失败不悄悄降级（约定 2）：调用方显示它，讨论进度不变。 */
class AiFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 后端地址：只接受 HTTPS（本机地址可以用 HTTP），不能带账号、口令或查询参数。返回整理后的地址。 */
fun checkRemoteUrl(raw: String): String {
    val u = runCatching { URI(raw.trim()).also { require(it.scheme != null && it.host != null) } }.getOrElse {
        throw AiFailure("请填写完整的后端地址，例如 https://debating-ground-api.你的子域.workers.dev")
    }
    val local = u.host in setOf("localhost", "127.0.0.1", "[::1]", "::1")
    if (u.scheme != "https" && !(u.scheme == "http" && local)) throw AiFailure("后端地址需要使用 HTTPS。")
    if (u.userInfo != null || u.rawQuery != null || u.rawFragment != null) throw AiFailure("后端地址不能包含账号、口令或查询参数。")
    val port = if (u.port == -1) "" else ":${u.port}"
    return "${u.scheme}://${u.host}$port${(u.rawPath ?: "").trimEnd('/')}"
}

/** 后端的状态（GET /api/config），不调用模型、不产生费用。 */
data class BackendStatus(val model: String, val ttsConfigured: Boolean, val sttConfigured: Boolean)

// 网络请求是阻塞调用，协程的取消和超时管不到它：放到单独的线程里跑，取消或超时时直接断开连接，
// 阻塞的读取立刻抛错退出，调用方马上拿到“已取消”“超时”（网页版用 AbortController 做同样的事）
private val io = Executors.newCachedThreadPool { r -> Thread(r, "sibian-http").apply { isDaemon = true } }

internal suspend fun <T> blocking(c: HttpURLConnection, work: () -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { c.disconnect() }
    io.execute {
        val result = runCatching(work)
        c.disconnect()
        if (cont.isActive) cont.resumeWith(result)
    }
}

private const val NO_RESPONSE = "后端没有返回有效响应，请确认地址指向思辨场后端。"
private const val UNREACHABLE = "连不上你的后端。请检查地址是否正确、后端是否已部署，以及手机能不能上网。"

class RemoteBackend(private val url: String, private val password: String, private val connectTimeoutMs: Int = 15_000) {
    private fun open(path: String, method: String, accept: String = "application/json"): HttpURLConnection {
        if (url.isEmpty()) throw AiFailure("还没有连接你的后端，请在“设置”里填写后端地址和访问口令。")
        val c = URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        // 为保护口令，不跟随重定向（和网页版、后端调用服务商时的做法一样）
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Authorization", "Bearer $password")
        return c
    }

    private fun connect(c: HttpURLConnection, body: String?): Int = try {
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        c.responseCode
    } catch (e: IOException) {
        throw AiFailure(UNREACHABLE, e)
    }

    // 普通响应：JSON；不是 2xx 就取后端给的 error 说明
    private fun readJson(c: HttpURLConnection, status: Int): JsonObject {
        val text = runCatching { (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val data = text?.let { runCatching { SibianJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: throw AiFailure(NO_RESPONSE)
        if (status !in 200..299) {
            val err = (data["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            throw AiFailure(if (err.isNullOrEmpty()) "请求失败。" else err)
        }
        return data
    }

    suspend fun status(): BackendStatus {
        val c = open("/api/config", "GET")
        return blocking(c) {
            val d = readJson(c, connect(c, null))
            fun flag(k: String) = (d[k] as? JsonPrimitive)?.content == "true"
            BackendStatus((d["model"] as? JsonPrimitive)?.content.orEmpty(), flag("ttsConfigured"), flag("sttConfigured"))
        }
    }

    /** 测试连接：会真的调用一次模型（和网页版“连接并测试”一样）。 */
    suspend fun test(): JsonObject {
        val c = open("/api/test", "POST")
        return blocking(c) { readJson(c, connect(c, "{}")) }
    }

    /**
     * 生成一次。[onText] 不为空时请求流式输出：每收到新文字回调一次到目前为止的全文。
     * 后端不认识流式、直接返回 JSON 时，按普通请求处理。失败时抛 [AiFailure]，已显示的半截文字由调用方撤回。
     * [onText] 在网络线程上调用，界面要自己切回主线程。
     */
    suspend fun generate(purpose: String, payload: JsonElement, onText: ((String) -> Unit)?): JsonObject {
        val stream = onText != null
        val body = buildJsonObject {
            put("purpose", purpose)
            put("payload", payload)
            if (stream) put("stream", true)
        }.toString()
        val c = open("/api/generate", "POST", if (stream) "text/event-stream" else "application/json")
        return blocking(c) {
            val status = connect(c, body)
            val sse = (c.contentType ?: "").contains("text/event-stream")
            if (!stream || !sse || status !in 200..299) {
                readJson(c, status)
            } else {
                readEvents(BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)), onText!!)
            }
        }
    }

    // SSE：事件之间空一行；event 行是名字，data 行是 JSON
    private fun readEvents(reader: BufferedReader, onText: (String) -> Unit): JsonObject {
        val text = StringBuilder()
        var name: String? = null
        var data: String? = null
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: IOException) {
                throw AiFailure("连接在生成过程中断开了，本次内容未采用，请重试。", e)
            } ?: break
            when {
                line.startsWith("event: ") -> name = line.removePrefix("event: ")

                line.startsWith("data: ") -> data = line.removePrefix("data: ")

                line.isEmpty() -> {
                    val n = name
                    val raw = data
                    name = null
                    data = null
                    if (n == null || raw == null) continue
                    // 事件坏了就当作这次生成失败：不显示解析器的报错，也不采用半截内容
                    val event = runCatching { SibianJson.parseToJsonElement(raw) as JsonObject }.getOrElse {
                        throw AiFailure("后端返回的内容不完整，本次内容未采用，请重试。", it)
                    }
                    when (n) {
                        "delta" -> (event["t"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                            text.append(it.content)
                            onText(text.toString())
                        }

                        "done" -> return event

                        "error" -> throw AiFailure((event["error"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: "请求失败。")
                    }
                }
            }
        }
        throw AiFailure("连接在生成过程中断开了，本次内容未采用，请重试。")
    }
}
