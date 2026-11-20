package debating.ground.app

import com.sun.net.httpserver.HttpServer
import debating.ground.app.ai.Ai
import debating.ground.app.ai.GuardedDns
import debating.ground.app.ai.LinkFetcher
import debating.ground.app.ai.LinkReader
import debating.ground.app.ai.Prompts
import debating.ground.app.ai.decodeBody
import debating.ground.app.ai.isBlockedAddress
import debating.ground.app.ai.isBlockedIPv4
import debating.ground.app.ai.linkClient
import debating.ground.app.data.SibianJson
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import debating.ground.app.ui.screens.browsable
import debating.ground.app.ui.screens.unwrapJsString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress

// 读材料的链接（#157）：手机自己取。地址检查和内容判断在共用的 JS 里（tests/native-ai.test.ts 检查），
// 这里检查 Kotlin 这边：DNS 这一步拦下本机和局域网地址、跳转每一跳都交给 JS 检查、大小上限、编码、试几个地址的顺序。
class LinksTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val port get() = server.address.port
    private val hits = mutableListOf<String>()

    init {
        fun route(path: String, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) = server.createContext(path) { ex ->
            hits += ex.requestURI.path
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
            ex.close()
        }
        route("/start", 302, ByteArray(0), mapOf("Location" to "/page"))
        route("/page", 200, "<p>正文</p>".toByteArray(), mapOf("Content-Type" to "text/html; charset=utf-8"))
        route("/gbk", 200, byteArrayOf(0xc4.toByte(), 0xe3.toByte(), 0xba.toByte(), 0xc3.toByte()), mapOf("Content-Type" to "text/plain; charset=gbk"))
        route("/away", 302, ByteArray(0), mapOf("Location" to "http://evil.test/"))
        route("/big", 200, ByteArray(3_000_001) { 'x'.code.toByte() }, mapOf("Content-Type" to "text/html"))
        route("/loop", 302, ByteArray(0), mapOf("Location" to "/loop"))
        route("/missing", 404, "no".toByteArray(), mapOf("Content-Type" to "text/html"))
        server.start()
    }

    @After fun stop() = server.stop(0)

    // 测试里的网站：a.test 指向本机的测试服务（不经过 GuardedDns）
    private val testDns = object : Dns {
        override fun lookup(hostname: String) = if (hostname ==
            "a.test"
        ) {
            listOf(InetAddress.getLoopbackAddress())
        } else {
            throw java.net.UnknownHostException(hostname)
        }
    }

    /** 假的共用 JS：按给定的计划；evil 开头的地址不让去；readFetched 原样交回取到的内容，404 当作“试下一个”。 */
    private class FakeJs(private val plan: JsonElement) : Prompts {
        val calls = mutableListOf<String>()

        override suspend fun call(name: String, vararg args: JsonElement): JsonElement {
            calls += name
            return when (name) {
                "linkPlan" -> plan

                "checkTarget" -> if (args[0].jsonPrimitive.content.contains("evil")) JsonPrimitive("不能读取本机或局域网里的地址。") else JsonNull

                "readFetched" -> {
                    val f = args[1].jsonObject
                    if (f.getValue("status").jsonPrimitive.int == 404) {
                        JsonNull
                    } else {
                        buildJsonObject {
                            put("ok", true)
                            put("title", "t")
                            put("text", f.getValue("body").jsonPrimitive.content)
                            put("url", f.getValue("url").jsonPrimitive.content)
                            put("kind", args[0].jsonObject.getValue("kind").jsonPrimitive.content)
                            put("complete", true)
                            put("note", "")
                        }
                    }
                }

                else -> error(name)
            }
        }
    }

    private fun plan(vararg paths: Pair<String, String>) = JsonArray(
        paths.map { (path, kind) ->
            buildJsonObject {
                put("url", "http://a.test:$port$path")
                put("kind", kind)
            }
        },
    )

    private fun read(vararg paths: Pair<String, String>) = runBlocking { LinkReader(FakeJs(plan(*paths)), linkClient(testDns)).read("link") }

    private fun JsonObject.s(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun followsRedirectsAndDecodesTheDeclaredCharset() {
        val r = read("/start" to "page")
        assertEquals("<p>正文</p>", r.s("text"))
        assertEquals("http://a.test:$port/page", r.s("url"))
        assertEquals(listOf("/start", "/page"), hits)
        assertEquals("你好", read("/gbk" to "page").s("text"))
    }

    @Test
    fun refusesRedirectsToBlockedAddressesTooManyHopsAndHugePages() {
        assertEquals("网站跳转到了不能读取的地址：不能读取本机或局域网里的地址。", read("/away" to "page").s("reason"))
        assertEquals("这个链接跳转了太多次，没能读取。", read("/loop" to "page").s("reason"))
        assertTrue(read("/big" to "page").s("reason").contains("3 MB"))
    }

    @Test
    fun anArxivPaperWithoutHtmlFallsBackToTheNextCandidate() {
        val r = read("/missing" to "arxiv-html", "/page" to "arxiv-abstract")
        assertEquals("arxiv-abstract", r.s("kind"))
        assertEquals(listOf("/missing", "/page"), hits)
    }

    @Test
    fun aRefusedLinkIsReportedWithoutAnyRequest() = runBlocking {
        val js = FakeJs(JsonPrimitive("不能读取本机或局域网里的地址。"))
        val r = LinkReader(js, linkClient(testDns)).read("http://192.168.1.1/")
        assertFalse(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals(listOf("linkPlan"), js.calls)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun theDnsStepRefusesLoopbackAndLanAddresses() = runBlocking {
        // 域名解析到本机：连接前就拦下（防 DNS 重绑定：查过的地址就是要连的地址）
        val guarded = linkClient(GuardedDns(testDns))
        val r = LinkReader(FakeJs(plan("/page" to "page")), guarded).read("link")
        assertEquals("不能读取本机或局域网里的地址。", r.s("reason"))
        assertTrue(hits.isEmpty())
        for (ip in listOf("::1", "::", "fd00::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:192.168.1.1", "64:ff9b::7f00:1")) {
            assertTrue(ip, isBlockedAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("2606:4700::1111", "::ffff:8.8.8.8", "1.1.1.1")) assertFalse(ip, isBlockedAddress(InetAddress.getByName(ip)))
    }

    @Test
    fun ipv4RulesMatchTheWebApp() {
        val cases = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/link-cases.json")!!.toURI()).readText())
            .jsonObject.getValue("ipv4").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 30)
        for (c in cases) assertEquals(c.s("ip"), c.getValue("blocked").jsonPrimitive.boolean, isBlockedIPv4(c.s("ip")))
    }

    @Test
    fun metaCharsetIsUsedWhenTheHeaderHasNone() {
        val html = "<meta charset=\"gbk\"><p>".toByteArray() + byteArrayOf(0xc4.toByte(), 0xe3.toByte())
        assertTrue(decodeBody(html, "text/html").endsWith("你"))
        assertEquals("好", decodeBody("好".toByteArray(), "text/html; charset=no-such-charset"))
    }

    private fun fetcher(answer: () -> JsonObject) = object : LinkFetcher {
        override suspend fun read(link: String) = answer()

        override suspend fun fromPage(url: String, html: String) = answer()
    }

    // 内置浏览器（#160）：把浏览器里已经显示出来的页面交给同样的规则
    @Test
    fun aPageFromTheBuiltInBrowserGoesThroughTheSameRules() = runBlocking {
        val js = FakeJs(JsonNull)
        val r = LinkReader(js, linkClient(testDns)).fromPage("https://zhuanlan.zhihu.com/p/1", "<p>登录后看到的正文</p>")
        assertEquals("<p>登录后看到的正文</p>", r.s("text"))
        assertEquals("page", r.s("kind"))
        assertEquals(listOf("readFetched"), js.calls)
        assertTrue(hits.isEmpty())
        val big = LinkReader(js, linkClient(testDns)).fromPage("https://x.test/", "x".repeat(3_000_001))
        assertTrue(big.s("reason").contains("3 MB"))
        val l = Learning(AppState(), Ai(demoTypingMs = 0), links = LinkReader(js, linkClient(testDns)))
        assertEquals("<p>登录后看到的正文</p>", l.usePage("https://zhuanlan.zhihu.com/p/1", "<p>登录后看到的正文</p>")!!.text)
        assertNull(l.state.ui.busy)
        assertTrue(browsable("https://a.test/") && browsable("HTTP://a.test/"))
        for (u in listOf("intent://x#Intent;end", "file:///sdcard/a", "javascript:alert(1)", "content://x")) assertFalse(u, browsable(u))
        assertEquals("<html>\"引号\"</html>", unwrapJsString("\"<html>\\\"引号\\\"</html>\""))
        assertEquals("", unwrapJsString("null"))
    }

    @Test
    fun learningFillsThePageOrShowsTheReason() = runBlocking {
        val ok = fetcher {
            buildJsonObject {
                put("ok", true)
                put("title", "Attention")
                put("text", "## 摘要\n\n短")
                put("complete", false)
                put("note", "只拿到了摘要。")
            }
        }
        val l = Learning(AppState(), Ai(demoTypingMs = 0), links = ok)
        val page = l.fetchLink("arXiv:1706.03762")!!
        assertEquals("Attention", page.title)
        assertFalse(page.complete)
        assertNull(l.state.ui.busy)
        val bad = Learning(
            AppState(),
            Ai(demoTypingMs = 0),
            links = fetcher {
                buildJsonObject {
                    put("ok", false)
                    put("reason", "这个网站拦住了自动读取（HTTP 403）。")
                }
            },
        )
        assertNull(bad.fetchLink("https://zhuanlan.zhihu.com/p/1"))
        assertEquals("这个网站拦住了自动读取（HTTP 403）。", bad.state.ui.error)
        // 设备不支持 JS 沙箱：直说
        val none = Learning(AppState(), Ai(demoTypingMs = 0))
        assertNull(none.fetchLink("https://example.com"))
        assertTrue(none.state.ui.error.contains("不支持读取链接"))
        assertNull(Learning(AppState(), Ai(demoTypingMs = 0), links = ok).fetchLink("  "))
    }
}
