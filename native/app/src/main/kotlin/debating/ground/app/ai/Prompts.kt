package debating.ground.app.ai

import android.content.Context
import android.os.Build
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import debating.ground.app.data.SibianJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 共用的提示词：server/ai.ts 的 buildRequest 和 finalize（打包成 assets/sibian-ai.js）。
 * 提示词是产品的核心，只在一个地方写；原生版不在 Kotlin 里再写一份。
 */
interface Prompts {
    /** 调用 JS 里的 [name]，参数和结果都是 JSON。失败时抛 [AiFailure]，说明和后端的一样。 */
    suspend fun call(name: String, vararg args: JsonElement): JsonElement
}

/** 解析 JS 入口（scripts/native-ai-entry.ts）的返回：成功是 {"ok": 结果}，失败是 {"error": 原因}。 */
fun parsePromptResult(raw: String): JsonElement {
    val o = runCatching { SibianJson.parseToJsonElement(raw) as JsonObject }.getOrElse { throw AiFailure("生成请求时出错了，请重试。") }
    (o["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { throw AiFailure(it.content) }
    return o["ok"] ?: throw AiFailure("生成请求时出错了，请重试。")
}

/** 调用 JS 时的那一行代码：参数先整体转成 JSON 字符串，再作为字符串字面量传进去，不拼接任何未转义的内容。 */
fun promptCall(name: String, args: List<JsonElement>): String = "sibian.run(${JsonPrimitive(name)}, ${JsonPrimitive(JsonArray(args).toString())})"

/**
 * 在系统 WebView 提供的独立 JS 沙箱（androidx.javascriptengine）里执行。它不是网页：没有网络、没有存储，
 * 只能算。API Key 从不传进来。第一次用时才启动，之后复用；设备不支持时说明原因，不悄悄换成别的来源。
 */
class SandboxPrompts(private val context: Context) : Prompts {
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null

    override suspend fun call(name: String, vararg args: JsonElement): JsonElement = lock.withLock {
        withContext(Dispatchers.IO) {
            val iso = isolate ?: start()
            val out = try {
                iso.evaluateJavaScriptAsync(promptCall(name, args.toList())).get()
            } catch (e: Exception) {
                // 沙箱崩了（比如 WebView 正在更新）：下次重新启动
                close()
                throw AiFailure("生成请求时出错了，请重试。", e)
            }
            parsePromptResult(out)
        }
    }

    private fun start(): JavaScriptIsolate {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !JavaScriptSandbox.isSupported()) {
            throw AiFailure("这台设备的系统不支持直接连接服务商（需要 Android 8 以上和较新的“Android System WebView”）。请改用自己部署的后端。")
        }
        return try {
            val sb = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get()
            sandbox = sb
            val iso = sb.createIsolate()
            val script = context.assets.open("sibian-ai.js").bufferedReader().use { it.readText() }
            iso.evaluateJavaScriptAsync(script).get()
            isolate = iso
            iso
        } catch (e: Exception) {
            close()
            throw AiFailure("没能启动生成提示词的 JS 引擎，请重试；还不行的话，更新“Android System WebView”或改用自己部署的后端。", e)
        }
    }

    private fun close() {
        runCatching { isolate?.close() }
        runCatching { sandbox?.close() }
        isolate = null
        sandbox = null
    }
}
