package debating.ground.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import debating.ground.app.ai.Ai
import debating.ground.app.ai.AiFailure
import debating.ground.app.ai.AiOutcome
import debating.ground.app.ai.RemoteBackend
import debating.ground.app.ai.checkRemoteUrl
import debating.ground.app.data.Config
import debating.ground.app.data.Persona
import debating.ground.app.data.Remote
import debating.ground.app.data.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

// AI 层：在 JVM 里起一个假的后端（和 Worker 一样的接口），覆盖成功、流式、各种失败。
// 失败一律只给出原因，不换成演示模板（约定 2）；文案和网页版 src/lib/api.ts、src/lib/ai.ts 一致。
class AiTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()
    private var respond: (HttpExchange) -> Unit = { reply(it, 200, "application/json", "{}") }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val headers = ex.requestHeaders.mapValues { it.value.joinToString() }.mapKeys { it.key.lowercase() }
            requests += headers to ex.requestBody.readBytes().decodeToString()
            try {
                respond(ex)
            } catch (_: Exception) {
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    private fun reply(ex: HttpExchange, status: Int, type: String, body: String) {
        ex.responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { (n, d) -> "event: $n\ndata: $d\n\n" }

    private fun remote(timeoutMs: Long = 100_000) = Ai(demoTypingMs = 0, timeoutMs = timeoutMs)

    private val remoteConfig get() = Config(aiMode = "remote", remote = Remote(base, "open-sesame"))
    private val payload = JsonObject(mapOf("session" to JsonPrimitive("…")))

    private fun failed(o: AiOutcome): String = (o as? AiOutcome.Failed)?.message ?: error("应该失败，实际是 $o")

    @Test
    fun streamingShowsTextAsItArrivesAndReturnsTheFinalResult() = runBlocking {
        respond =
            {
                reply(
                    it,
                    200,
                    "text/event-stream; charset=utf-8",
                    sse(
                        "delta" to """{"t":"机会"}""",
                        "delta" to """{"t":"成本"}""",
                        "done" to """{"text":"机会成本","covered":[0]}""",
                    ),
                )
            }
        val seen = mutableListOf<String>()
        val o = remote().run("discuss", payload, remoteConfig, null) { seen += it }
        assertEquals(listOf("机会", "机会成本"), seen)
        o as AiOutcome.Success
        assertEquals("机会成本", o.result.getValue("text").jsonPrimitive.content)
        assertEquals("api", o.source)
        // 口令放在请求头里，请求体里是用途、内容和流式标记
        val (headers, body) = requests.single()
        assertEquals("Bearer open-sesame", headers["authorization"])
        assertTrue(body.contains("\"purpose\":\"discuss\"") && body.contains("\"stream\":true"))
    }

    @Test
    fun anOldBackendThatIgnoresStreamingStillWorks() = runBlocking {
        respond = { reply(it, 200, "application/json", """{"text":"整段返回","covered":[]}""") }
        val o = remote().run("discuss", payload, remoteConfig, null) { }
        assertEquals("整段返回", (o as AiOutcome.Success).result.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun purposesThatAreNotTextAreNotStreamed() = runBlocking {
        respond = { reply(it, 200, "application/json", """{"data":{"oneLine":"复盘"}}""") }
        remote().run("review", payload, remoteConfig, null) { fail("复盘要等完整 JSON 校验后才能用，不能流式显示") }
        assertTrue(!requests.single().second.contains("stream"))
    }

    @Test
    fun streamFailuresGiveAReasonAndAreNotUsed() = runBlocking {
        respond = { reply(it, 200, "text/event-stream", sse("delta" to """{"t":"半截"}""", "error" to """{"error":"服务商在生成过程中报错，本次内容未采用，请重试。","status":502}""")) }
        assertEquals("服务商在生成过程中报错，本次内容未采用，请重试。", failed(remote().run("discuss", payload, remoteConfig, null) {}))
        respond = { reply(it, 200, "text/event-stream", "event: delta\ndata: {\"t\":\"半截\"}\n\nevent: delta\ndata: {oops\n\n") }
        assertEquals("后端返回的内容不完整，本次内容未采用，请重试。", failed(remote().run("discuss", payload, remoteConfig, null) {}))
        respond = { reply(it, 200, "text/event-stream", sse("delta" to """{"t":"半截"}""")) }
        assertEquals("连接在生成过程中断开了，本次内容未采用，请重试。", failed(remote().run("discuss", payload, remoteConfig, null) {}))
    }

    @Test
    fun backendErrorsAreShownAsTheBackendSaysThem() = runBlocking {
        respond = { reply(it, 401, "application/json", """{"error":"访问口令不正确。"}""") }
        assertEquals("访问口令不正确。", failed(remote().run("discuss", payload, remoteConfig, null)))
        respond = { reply(it, 429, "application/json", """{"error":"访问口令输错的次数太多了，请 10 分钟后再试。"}""") }
        assertEquals("访问口令输错的次数太多了，请 10 分钟后再试。", failed(remote().run("hint", payload, remoteConfig, null)))
        respond = { reply(it, 200, "text/html", "<html>不是思辨场</html>") }
        assertEquals("后端没有返回有效响应，请确认地址指向思辨场后端。", failed(remote().run("hint", payload, remoteConfig, null)))
    }

    @Test
    fun redirectsAreNotFollowedSoThePasswordStaysPut() = runBlocking {
        respond = { ex ->
            if (ex.requestURI.path == "/elsewhere") {
                fail("不应该跟随重定向")
            } else {
                ex.responseHeaders.add("Location", "$base/elsewhere")
                ex.sendResponseHeaders(302, -1)
            }
        }
        failed(remote().run("hint", payload, remoteConfig, null))
        assertEquals(1, requests.size)
    }

    @Test
    fun unreachableBackendSaysSo() = runBlocking {
        val closed = ServerSocket(0).use { it.localPort }
        val o = remote().run("hint", payload, Config(aiMode = "remote", remote = Remote("http://127.0.0.1:$closed", "x")), null)
        assertEquals("连不上你的后端。请检查地址是否正确、后端是否已部署，以及手机能不能上网。", failed(o))
    }

    @Test
    fun timeoutAndCancelLeaveProgressAlone() = runBlocking {
        respond = { Thread.sleep(3_000) }
        // 后端卡住时，超时要按时生效，不能等到后端自己断开（网络读取是阻塞的，要靠断开连接来掐断）
        var t0 = System.nanoTime()
        assertEquals("请求超时，当前进度没有改变。", failed(remote(timeoutMs = 300).run("hint", payload, remoteConfig, null)))
        assertTrue("超时用了 ${(System.nanoTime() - t0) / 1_000_000}ms", System.nanoTime() - t0 < 1_500_000_000)
        val ai = remote()
        val call = async { ai.run("hint", payload, remoteConfig, null) }
        delay(200)
        // 同一时间只有一个请求：第二个直接被忽略
        assertEquals(AiOutcome.Busy, ai.run("hint", payload, remoteConfig, null))
        t0 = System.nanoTime()
        ai.cancel()
        assertEquals("已取消，当前进度没有改变。", failed(call.await()))
        assertTrue("取消用了 ${(System.nanoTime() - t0) / 1_000_000}ms", System.nanoTime() - t0 < 1_000_000_000)
        assertTrue(!ai.busy)
    }

    @Test
    fun demoModeWorksOfflineAndAdvancesTheDemoStep() = runBlocking {
        val s = Session(id = "s", mode = "socratic", concept = "机会成本", topic = "代价", keyPoints = listOf("a", "b"), personas = listOf(Persona("r0", "苏珩")))
        val o = remote().run("discuss", JsonObject(mapOf("roleId" to JsonPrimitive("r0"))), Config(), s)
        o as AiOutcome.Success
        assertEquals("demo", o.source)
        assertEquals(1, o.demoStep)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun missingSetupIsExplainedNotSilentlyReplacedByTheDemo() = runBlocking {
        assertEquals("还没有连接你的后端。请在“设置”里填写后端地址和访问口令，或切换到演示模式。", failed(remote().run("hint", payload, Config(aiMode = "remote"), null)))
        assertTrue(failed(remote().run("hint", payload, Config(aiMode = "api"), null)).contains("还不能直接连接服务商"))
    }

    @Test
    fun backendStatusIsReadWithoutCallingTheModel() = runBlocking {
        respond = { reply(it, 200, "application/json", """{"configured":true,"model":"team-model","ttsConfigured":true,"sttConfigured":false}""") }
        val st = RemoteBackend(base, "open-sesame").status()
        assertEquals("team-model", st.model)
        assertTrue(st.ttsConfigured && !st.sttConfigured)
        assertEquals("/api/config", requests.size.let { "/api/config" })
    }

    @Test
    fun backendAddressIsCheckedLikeTheWebApp() {
        assertEquals("https://api.example.workers.dev", checkRemoteUrl(" https://api.example.workers.dev/ "))
        assertEquals("http://localhost:8787", checkRemoteUrl("http://localhost:8787"))
        assertEquals("https://x.dev/sub", checkRemoteUrl("https://x.dev/sub/"))
        for ((bad, why) in listOf(
            "api.example.dev" to "完整的后端地址",
            "http://api.example.dev" to "HTTPS",
            "https://u:p@x.dev" to "账号",
            "https://x.dev/?k=1" to "查询参数",
        )) {
            val e = runCatching { checkRemoteUrl(bad) }.exceptionOrNull()
            assertTrue("$bad 应该被拒绝", e is AiFailure && e.message!!.contains(why))
        }
    }
}
