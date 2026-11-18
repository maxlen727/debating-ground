package debating.ground.app.ai

import debating.ground.app.data.SibianJson
import kotlinx.serialization.json.JsonArray
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

// 直接连接服务商（Chat Completions 接口）。和后端的 server/ai.ts 一致：
// 提示词由共用的 JS（Prompts）组装和清洗，这里只负责发请求、读流式输出、给出和后端一样的错误说明。
// API Key 只在这里用来设置请求头，不进入 JS、不进存档、不进错误信息（AGENTS.md 约定 1 的直连例外）。

/** 整理后的接口设置：和 server/ai.ts 的 normalizeConfig 一样（FixturesTest 用网页版的结果逐条比对）。 */
data class DirectConfig(val endpoint: String, val model: String, val key: String)

private const val HIDDEN = "[密钥已隐藏]"

fun normalizeDirect(baseURL: String, model: String, key: String): DirectConfig {
    val u = runCatching { URI(baseURL.trim()).also { require(it.scheme != null && !it.host.isNullOrEmpty()) } }.getOrElse {
        throw AiFailure("请填写完整的 API 地址。")
    }
    val scheme = u.scheme.lowercase()
    val host = u.host.lowercase()
    val local = host in setOf("localhost", "127.0.0.1", "[::1]")
    if ((scheme != "https" && !(scheme == "http" && local)) || u.rawUserInfo != null || !u.rawQuery.isNullOrEmpty() || !u.rawFragment.isNullOrEmpty()) {
        throw AiFailure("API 地址需使用 HTTPS；仅本机接口可用 HTTP。地址不能包含账号、密钥或查询参数。")
    }
    var path = (u.rawPath ?: "").trimEnd('/')
    if (!path.endsWith("/chat/completions")) path += "/chat/completions"
    // 和浏览器的 URL 一样：默认端口不写出来
    val port = if (u.port == -1 || (scheme == "https" && u.port == 443) || (scheme == "http" && u.port == 80)) "" else ":${u.port}"
    val k = key.trim()
    val m = model.trim()
    if (k.isEmpty() || k.length > 4096 || k.any { it == '\r' || it == '\n' || it == '\u0000' }) throw AiFailure("请填写有效的 API Key。")
    if (m.isEmpty() || m.length > 200 || m.any { it == '\r' || it == '\n' || it == '\u0000' }) throw AiFailure("请填写服务商提供的模型名称。")
    return DirectConfig("$scheme://$host$port$path", m, k)
}

/**
 * 流式显示时能给用户看的部分：标记行（[[要点:…]]、[[术语:…]]）只给系统读，从第一个 [[ 起都不显示；
 * 末尾可能是标记或密钥开头的几个字先扣住，等确定不是再放出来。和 server/ai.ts 的 visibleText 一样。
 */
fun visibleText(raw: String, key: String = ""): String {
    var v = raw
    val cut = v.indexOf("[[")
    if (cut >= 0) {
        v = v.substring(0, cut)
    } else if (v.endsWith("[")) {
        v = v.dropLast(1)
    }
    if (key.isNotEmpty()) {
        v = v.replace(key, HIDDEN)
        for (k in minOf(key.length - 1, v.length) downTo 1) {
            if (v.endsWith(key.substring(0, k))) {
                v = v.dropLast(k)
                break
            }
        }
    }
    return v.trim()
}

private const val UNREACHABLE = "无法连接 API，请检查地址、网络或服务商状态。为保护密钥，不跟随接口重定向。"
private const val MAX_BYTES = 2_000_000
private const val MAX_TEXT = 20_000
private val LABELS = mapOf(401 to "密钥无效或已过期", 403 to "接口拒绝访问，请检查权限", 404 to "接口地址或模型不存在", 429 to "额度不足或请求过于频繁")

class DirectBackend(private val config: DirectConfig, private val prompts: Prompts, private val connectTimeoutMs: Int = 15_000) {
    /** 生成一次：用共用的 JS 组装提示词，调用服务商，再用 JS 清洗结果。[onText] 不为空时请求流式输出。 */
    suspend fun generate(purpose: String, payload: JsonElement, onText: ((String) -> Unit)?): JsonObject {
        val request = prompts.call("buildRequest", JsonPrimitive(purpose), payload) as? JsonObject ?: throw AiFailure("生成请求时出错了，请重试。")
        val text = complete(request["messages"] ?: JsonArray(emptyList()), onText)
        val result = buildJsonObject {
            put("text", text)
            put("model", config.model)
        }
        return prompts.call("finalize", request, result) as? JsonObject ?: throw AiFailure("生成请求时出错了，请重试。")
    }

    /** 测试连接：会真的调用一次模型（和网页版“连接并测试”一样）。 */
    suspend fun test() {
        val request = prompts.call("buildRequest", JsonPrimitive("test"), JsonObject(emptyMap())) as? JsonObject ?: throw AiFailure("生成请求时出错了，请重试。")
        complete(request["messages"] ?: JsonArray(emptyList()), null)
    }

    /** 调用 Chat Completions，返回模型说的话（密钥已隐藏）。和 server/ai.ts 的 complete 一样，FixturesTest 逐条比对。 */
    suspend fun complete(messages: JsonElement, onText: ((String) -> Unit)?): String {
        val c = URL(config.endpoint).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = connectTimeoutMs
        // 为保护密钥，绝不跟随服务商的重定向
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer ${config.key}")
        val body = buildJsonObject {
            put("model", config.model)
            put("messages", messages)
            put("stream", onText != null)
        }.toString()
        return blocking(c) {
            val status = try {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
            } catch (e: IOException) {
                throw AiFailure(UNREACHABLE, e)
            }
            if (status in 300..399) throw AiFailure("接口返回了重定向。为保护密钥，不跟随重定向，请填写服务商文档里的准确地址。")
            if (status !in 200..299) throw AiFailure("API 返回 $status：${LABELS[status] ?: "服务商暂时无法完成请求，请检查接口配置或稍后重试"}。")
            val streaming = onText != null && (c.contentType ?: "").contains("text/event-stream")
            val input = try {
                c.inputStream
            } catch (e: IOException) {
                throw AiFailure("接口没有返回内容。", e)
            }
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            var out: String
            var finish: String?
            try {
                if (streaming) {
                    val (text, reason) = readStream(reader, onText!!)
                    out = text
                    finish = reason
                } else {
                    val raw = readLimited(reader)
                    val choice = runCatching { SibianJson.parseToJsonElement(raw) as JsonObject }.getOrElse {
                        throw AiFailure("接口没有返回有效 JSON，请确认支持 Chat Completions 协议。")
                    }.let { ((it["choices"] as? JsonArray)?.firstOrNull() as? JsonObject) }
                    out = ((choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    finish = (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (onText != null) visibleText(out, config.key).takeIf { it.isNotEmpty() }?.let(onText)
                }
            } catch (e: AiFailure) {
                throw e
            } catch (e: IOException) {
                throw AiFailure(UNREACHABLE, e)
            }
            if (finish == "length") throw AiFailure("模型输出被截断，本次内容未采用；请调整服务商输出限制后重试。")
            if (out.isBlank()) throw AiFailure("模型没有返回可用文本；请检查模型是否支持文本 Chat Completions。")
            if (out.length > MAX_TEXT) throw AiFailure("生成内容过长，本次未采用。")
            out.trim().replace(config.key, HIDDEN)
        }
    }

    private fun readLimited(reader: BufferedReader): String {
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var bytes = 0
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, n)
            bytes += n
            if (bytes > MAX_BYTES) throw AiFailure("API 响应过大。")
        }
        return sb.toString()
    }

    // SSE：每个 data 行是一段增量；收到新的可显示文字就回调一次（只在变化时）
    private fun readStream(reader: BufferedReader, onText: (String) -> Unit): Pair<String, String?> {
        val out = StringBuilder()
        var finish: String? = null
        var shown = ""
        var bytes = 0
        while (true) {
            val line = reader.readLine() ?: break
            bytes += line.length + 1
            if (bytes > MAX_BYTES) throw AiFailure("API 响应过大。")
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val event = runCatching { SibianJson.parseToJsonElement(payload) as JsonObject }.getOrNull() ?: continue
            if (event["error"] != null && event["error"] !is kotlinx.serialization.json.JsonNull) {
                throw AiFailure("服务商在生成过程中报错，本次内容未采用，请重试。")
            }
            val choice = (event["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ((choice?.get("delta") as? JsonObject)?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.let { out.append(it.content) }
            (choice?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let { finish = it }
            if (out.length > MAX_TEXT) throw AiFailure("生成内容过长，本次未采用。")
            val v = visibleText(out.toString(), config.key)
            if (v != shown) {
                shown = v
                onText(v)
            }
        }
        return out.toString() to finish
    }
}

/** 直接连接的设置和调用：读写加密保存的设置，按设置建出 [DirectBackend]。 */
class DirectAccess(private val store: DirectStore, private val prompts: Prompts) {
    @Volatile private var cached: DirectSettings? = null

    @Volatile private var loaded = false

    fun settings(): DirectSettings? {
        if (!loaded) {
            cached = store.load()
            loaded = true
        }
        return cached
    }

    fun backend(): DirectBackend? = settings()?.let { DirectBackend(normalizeDirect(it.baseURL, it.model, it.key), prompts) }

    /**
     * 保存设置并测试一次（会调用一次模型）。[key] 留空表示沿用已保存的。先检查、保存，再测试：
     * 测试没通过时设置仍然保存，用户改一处就能重试（和网页版的本机服务一样）。返回整理后的模型名。
     */
    suspend fun connect(baseURL: String, model: String, key: String): String {
        val config = normalizeDirect(baseURL, model, key.ifBlank { settings()?.key.orEmpty() })
        val saved = DirectSettings(baseURL.trim(), config.model, config.key)
        store.save(saved)
        cached = saved
        loaded = true
        DirectBackend(config, prompts).test()
        return config.model
    }

    fun clear() {
        store.clear()
        cached = null
        loaded = true
    }
}
