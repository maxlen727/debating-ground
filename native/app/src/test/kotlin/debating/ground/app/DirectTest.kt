package debating.ground.app

import com.sun.net.httpserver.HttpServer
import debating.ground.app.ai.Ai
import debating.ground.app.ai.AiFailure
import debating.ground.app.ai.AiOutcome
import debating.ground.app.ai.DirectAccess
import debating.ground.app.ai.DirectBackend
import debating.ground.app.ai.DirectConfig
import debating.ground.app.ai.MemoryDirectStore
import debating.ground.app.ai.Prompts
import debating.ground.app.ai.normalizeDirect
import debating.ground.app.ai.parsePromptResult
import debating.ground.app.ai.promptCall
import debating.ground.app.ai.visibleText
import debating.ground.app.data.Config
import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

// 直接连接服务商：地址检查、流式显示过滤、调用和错误文案，都用网页版 server/ai.ts 跑出来的结果（direct-cases.json）逐条比对。
// 提示词由共用的 JS 组装，这里用假的 Prompts 代替（JS 打包文件本身由 tests/native-ai.test.ts 检查）。
class DirectTest {
    private val cases = SibianJson.parseToJsonElement(
        File(javaClass.classLoader!!.getResource("fixtures/direct-cases.json")!!.toURI()).readText(),
    ).jsonObject
    private val key = cases.getValue("key").jsonPrimitive.content

    private lateinit var server: HttpServer
    private var reply: Triple<Int, String, String> = Triple(200, "application/json", "{}")
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>() // Authorization 头、请求体

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            bodies += (ex.requestHeaders.getFirst("Authorization") ?: "") to ex.requestBody.readBytes().decodeToString()
            val (status, type, body) = reply
            ex.responseHeaders.add("Content-Type", type)
            if (status in 300..399) ex.responseHeaders.add("Location", "https://evil.example")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.write(bytes)
            ex.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val endpoint get() = "http://127.0.0.1:${server.address.port}/v1/chat/completions"

    @Test fun visibleTextMatchesTheBackend() {
        for (c in cases.getValue("visible").jsonArray.map { it.jsonObject }) {
            val raw = c.getValue("raw").jsonPrimitive.content
            assertEquals("visibleText($raw)", c.getValue("out").jsonPrimitive.content, visibleText(raw, c.getValue("key").jsonPrimitive.content))
        }
    }

    @Test fun addressChecksMatchTheBackend() {
        for (c in cases.getValue("configs").jsonArray.map { it.jsonObject }) {
            val input = c.getValue("input").jsonObject
            fun s(k: String) = input.getValue(k).jsonPrimitive.content
            val got = try {
                val r = normalizeDirect(s("baseURL"), s("model"), s("apiKey"))
                "${r.endpoint} ${r.model}"
            } catch (e: AiFailure) {
                e.message
            }
            val want = c["error"]?.jsonPrimitive?.content ?: "${c.getValue("endpoint").jsonPrimitive.content} ${c.getValue("model").jsonPrimitive.content}"
            assertEquals("地址 ${s("baseURL")}", want, got)
        }
    }

    // 服务商返回的每种情况：显示过的文字、最后的结果或失败原因，都和后端一样
    @Test fun callsBehaveLikeTheBackend() = runBlocking {
        val backend = DirectBackend(DirectConfig(endpoint, "m1", key), NoPrompts)
        for (c in cases.getValue("http").jsonArray.map { it.jsonObject }) {
            val name = c.getValue("name").jsonPrimitive.content
            reply = Triple(c.getValue("status").jsonPrimitive.int, c.getValue("type").jsonPrimitive.content, c.getValue("body").jsonPrimitive.content)
            val shown = mutableListOf<String>()
            val stream = c.getValue("stream").jsonPrimitive.boolean
            val got = try {
                "text:" + backend.complete(JsonArray(listOf(JsonPrimitive("你好"))), if (stream) { v -> shown.add(v) } else null)
            } catch (e: AiFailure) {
                "error:" + e.message
            }
            val want = c["text"]?.let { "text:" + it.jsonPrimitive.content } ?: ("error:" + c.getValue("error").jsonPrimitive.content)
            assertEquals("情况 $name", want, got)
            assertEquals("情况 $name 显示过的文字", c.getValue("shown").jsonArray.map { it.jsonPrimitive.content }, shown)
        }
        // 请求头带着密钥，请求体里有模型、消息和是否流式
        val (auth, body) = bodies.first()
        assertEquals("Bearer $key", auth)
        val sent = SibianJson.parseToJsonElement(body).jsonObject
        assertEquals("m1", sent.getValue("model").jsonPrimitive.content)
        assertFalse(sent.getValue("stream").jsonPrimitive.boolean)
    }

    @Test fun cannotReachTheProviderSaysWhy() = runBlocking {
        server.stop(0)
        val e = runCatching { DirectBackend(DirectConfig(endpoint, "m1", key), NoPrompts).complete(JsonArray(emptyList()), null) }.exceptionOrNull()
        assertEquals("无法连接 API，请检查地址、网络或服务商状态。为保护密钥，不跟随接口重定向。", e?.message)
    }

    // 生成一次：JS 组装提示词、Kotlin 发请求、JS 清洗结果。密钥从不传进 JS
    @Test fun generateGoesThroughTheSharedPromptsWithoutTheKey() = runBlocking {
        reply = Triple(200, "application/json", """{"choices":[{"message":{"content":"时间也有稀缺性。"},"finish_reason":"stop"}]}""")
        val prompts = FakePrompts()
        val out = DirectBackend(DirectConfig(endpoint, "m1", key), prompts).generate("discuss", JsonObject(mapOf("session" to JsonPrimitive("快照"))), null)
        assertEquals("finalized", out.getValue("by").jsonPrimitive.content)
        assertEquals(listOf("buildRequest", "finalize"), prompts.calls.map { it.first })
        val finalizeArgs = prompts.calls[1].second
        assertEquals("时间也有稀缺性。", finalizeArgs[1].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("m1", finalizeArgs[1].jsonObject.getValue("model").jsonPrimitive.content)
        assertTrue("密钥不能进入 JS", prompts.calls.none { (_, args) -> args.toString().contains(key) })
        // 发给服务商的消息就是 JS 组装出来的
        assertEquals(prompts.messages, SibianJson.parseToJsonElement(bodies.last().second).jsonObject.getValue("messages"))
    }

    @Test fun jsCallsAreQuotedAndParsed() {
        // 参数整体转成 JSON 字符串再作为字面量传进去：引号、换行、反斜杠都不会破坏这行代码
        val args = listOf<JsonElement>(JsonPrimitive("\"); evil(\"\n\\"))
        val code = promptCall("buildRequest", args)
        assertEquals("sibian.run(\"buildRequest\", ${JsonPrimitive(JsonArray(args).toString())})", code)
        assertEquals(JsonPrimitive(1), parsePromptResult("""{"ok":1}"""))
        assertEquals("讨论上下文不完整。", runCatching { parsePromptResult("""{"error":"讨论上下文不完整。"}""") }.exceptionOrNull()?.message)
        assertEquals("生成请求时出错了，请重试。", runCatching { parsePromptResult("not json") }.exceptionOrNull()?.message)
    }

    // 设置页：连接并测试。通过才切到“直接连接”；密钥不进状态和存档
    @Test fun connectingSavesSettingsButKeepsTheKeyOutOfTheState() = runBlocking {
        reply = Triple(200, "application/json", """{"choices":[{"message":{"content":"好"},"finish_reason":"stop"}]}""")
        val store = MemoryDirectStore()
        val l = Learning(AppState(), Ai(demoTypingMs = 0, direct = DirectAccess(store, FakePrompts())))
        val base = endpoint.removeSuffix("/chat/completions")
        assertTrue(l.connectDirect(base, " m1 ", key))
        assertEquals("api", l.state.config.aiMode)
        assertEquals(base, store.load()!!.baseURL)
        assertEquals(key, store.load()!!.key)
        assertTrue(l.state.ui.direct.verified)
        assertEquals("m1", l.state.ui.direct.model)
        val saved = SibianJson.encodeToString(SavedState.serializer(), l.state.saved())
        assertFalse("存档里不能有密钥", saved.contains(key))
        assertFalse("界面状态里不能有密钥", l.state.toString().contains(key))
        assertFalse(store.load().toString().contains(key))

        // 生成走直接连接
        reply = Triple(200, "application/json", """{"choices":[{"message":{"content":"时间也有稀缺性。"},"finish_reason":"stop"}]}""")
        val outcome = Ai(demoTypingMs = 0, direct = DirectAccess(store, FakePrompts())).run("discuss", JsonObject(emptyMap()), Config(aiMode = "api"), null)
        assertEquals("api", (outcome as AiOutcome.Success).source)

        // 密钥错误：设置保留，说明原因，不切换来源
        reply = Triple(401, "application/json", "{}")
        val l2 = Learning(AppState(), Ai(demoTypingMs = 0, direct = DirectAccess(MemoryDirectStore(), FakePrompts())))
        assertFalse(l2.connectDirect(base, "m1", "wrong"))
        assertEquals("API 返回 401：密钥无效或已过期。", l2.state.ui.error)
        assertEquals("demo", l2.state.config.aiMode)
        assertTrue(l2.state.ui.direct.saved)
        assertFalse(l2.state.ui.direct.verified)

        // 删除：设置和密钥都没了，换回演示模式
        l.forgetDirect()
        assertEquals(null, store.load())
        assertEquals("demo", l.state.config.aiMode)
        assertFalse(l.state.ui.direct.saved)
    }

    @Test fun notSetUpSaysSoInsteadOfFallingBack() = runBlocking {
        val outcome = Ai(
            demoTypingMs = 0,
            direct = DirectAccess(MemoryDirectStore(), FakePrompts()),
        ).run("discuss", JsonObject(emptyMap()), Config(aiMode = "api"), null)
        assertEquals("还没有设置直接连接。请在“设置”里填写接口地址、模型和 API Key，或切换到演示模式。", (outcome as AiOutcome.Failed).message)
        val l = Learning(AppState(), Ai(demoTypingMs = 0))
        assertFalse(l.connectDirect("https://api.example.com/v1", "m1", "k"))
        assertEquals("这个版本不能直接连接服务商。请改用自己部署的后端，或切换到演示模式。", l.state.ui.error)
    }

    private object NoPrompts : Prompts {
        override suspend fun call(name: String, vararg args: JsonElement): JsonElement = error("不该调用 JS")
    }

    // 假的共用提示词：记下调用了什么，返回固定的请求和结果
    private class FakePrompts : Prompts {
        val calls = mutableListOf<Pair<String, List<JsonElement>>>()
        val messages = JsonArray(
            listOf(
                buildJsonObject {
                    put("role", "user")
                    put("content", "说说机会成本")
                },
            ),
        )

        override suspend fun call(name: String, vararg args: JsonElement): JsonElement {
            calls += name to args.toList()
            return when (name) {
                "buildRequest" -> buildJsonObject {
                    put("messages", messages)
                    put("output", "text")
                }

                else -> buildJsonObject {
                    put("by", "finalized")
                    put("text", args[1].jsonObject.getValue("text"))
                }
            }
        }
    }
}
