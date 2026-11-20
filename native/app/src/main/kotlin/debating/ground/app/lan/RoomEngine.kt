package debating.ground.app.lan

import android.content.Context
import android.os.Build
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import debating.ground.app.ai.AiFailure
import debating.ground.app.data.SibianJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// 局域网房间（#148）的规则：和 Worker、电脑上的本机服务是同一份 RoomCore（打包成 assets/sibian-room.js），
// 在 JS 引擎里跑。引擎里只有语言本身：要做的事以“效果”返回，由 LanHost 去做（发消息、定闹钟、调 AI）。

/** 引擎每次返回的：要做的事、这次调用的结果（开房间时是邀请码等）、出错的原因。 */
data class EngineOut(val effects: List<JsonObject>, val result: JsonElement?, val error: String)

/** 房间规则所在的引擎：参数和结果都是 JSON。调用一个接一个来，不会同时进来两个。 */
interface RoomEngine {
    suspend fun call(name: String, args: List<JsonElement>): EngineOut

    fun close()
}

fun parseEngineOut(raw: String): EngineOut {
    val o = runCatching { SibianJson.parseToJsonElement(raw) as JsonObject }.getOrElse { throw AiFailure("房间出错了，请重新开一个。") }
    val effects = (o["effects"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val error = (o["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return EngineOut(effects, o["result"]?.takeIf { it !is JsonNull }, error)
}

/** 调用时的那一行代码：参数整体转成 JSON 字符串，作为字符串字面量传进去，不拼接任何未转义的内容。 */
fun engineCall(name: String, args: List<JsonElement>): String = "sibianRoom.call(${JsonPrimitive(name)}, ${JsonPrimitive(JsonArray(args).toString())})"

/** 系统 WebView 提供的独立 JS 沙箱（和“直接连接”的提示词一样）。它不是网页：没有网络、没有存储。 */
class SandboxRoomEngine(private val context: Context) : RoomEngine {
    private var sandbox: JavaScriptSandbox? = null
    private var isolate: JavaScriptIsolate? = null

    override suspend fun call(name: String, args: List<JsonElement>): EngineOut = withContext(Dispatchers.IO) {
        val iso = isolate ?: start()
        val raw = try {
            iso.evaluateJavaScriptAsync(engineCall(name, args)).get()
        } catch (e: Exception) {
            close()
            throw AiFailure("房间出错了，请重新开一个。", e)
        }
        parseEngineOut(raw)
    }

    private fun start(): JavaScriptIsolate {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !JavaScriptSandbox.isSupported()) {
            throw AiFailure("这台手机的系统不支持当局域网房主（需要 Android 8 以上和较新的“Android System WebView”）。")
        }
        return try {
            val sb = JavaScriptSandbox.createConnectedInstanceAsync(context.applicationContext).get()
            sandbox = sb
            // 房间规则要等 AI 的结果：需要引擎能返回 Promise
            if (!sb.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_PROMISE_RETURN)) {
                throw AiFailure("这台手机的“Android System WebView”太旧了，不能当局域网房主。请在应用商店更新它。")
            }
            val iso = sb.createIsolate()
            val script = context.assets.open("sibian-room.js").bufferedReader().use { it.readText() }
            iso.evaluateJavaScriptAsync(script).get()
            isolate = iso
            iso
        } catch (e: AiFailure) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw AiFailure("没能启动房间，请重试；还不行的话，更新“Android System WebView”。", e)
        }
    }

    override fun close() {
        runCatching { isolate?.close() }
        runCatching { sandbox?.close() }
        isolate = null
        sandbox = null
    }
}
