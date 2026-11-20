package debating.ground.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

// 读材料的链接（#157）：手机自己去取（用学习者自己的网络，比从服务器取更少被拦）。
// 该取哪几个地址、每一跳能不能去、取到的内容能不能用，都交给共用的 JS（shared/pages.ts，打包在 sibian-ai.js 里），
// 和网页版后端是同一份规则；这里只做网络请求。取哪个地址由规则决定，不由模型决定。
// 防 SSRF：除了 JS 里对地址本身的检查，连接时在 DNS 这一步检查解析出的每个 IP（GuardedDns），
// 查过的就是真正连上的地址，防 DNS 重绑定；跳转手动跟，每一跳都检查。

object LinkLimits {
    const val BYTES = 3_000_000L
    const val REDIRECTS = 4
    const val TIMEOUT_MS = 15_000L
}

/** 和 shared/pages.ts 的 FETCH_HEADERS 一样：如实说明是谁在取。 */
private val HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (compatible; SibianReader/1.0; +https://github.com/maxlen727/debating-ground)",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)

/** IPv4 地址是不是本机、局域网或保留地址（和 shared/pages.ts 的 isBlockedIPv4 一样，LinksTest 拿网页版的结果比对）。 */
fun isBlockedIPv4(ip: String): Boolean {
    val p = ip.split('.').takeIf { it.size == 4 }?.map { it.toIntOrNull() ?: return false } ?: return false
    val (a, b, c) = p
    return a == 0 || a == 10 || a == 127 || (a == 100 && b in 64..127) || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) || (a == 192 && b == 0 && c == 0) || (a == 198 && (b == 18 || b == 19)) || a >= 224
}

/** 解析出的地址能不能连。IPv6 按字节判断：::/8（含 ::1 和各种转换地址）、fc00::/7、fe80::/10、组播都不连；IPv4 映射地址按 IPv4 判断。 */
fun isBlockedAddress(a: InetAddress): Boolean = when (a) {
    is Inet4Address -> isBlockedIPv4(a.hostAddress.orEmpty())

    is Inet6Address -> {
        val b = a.address.map { it.toInt() and 0xff }
        val mapped = b.subList(0, 10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff
        when {
            mapped -> isBlockedIPv4(b.subList(12, 16).joinToString("."))
            b[0] == 0 -> true
            b[0] and 0xfe == 0xfc -> true
            b[0] == 0xfe && b[1] and 0xc0 == 0x80 -> true
            b[0] == 0xff -> true
            else -> false
        }
    }

    else -> true
}

class BlockedAddress : IOException("不能读取本机或局域网里的地址。")

/** 解析出的地址里只要有一个是本机、局域网或保留地址就不连。 */
class GuardedDns(private val inner: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = inner.lookup(hostname)
        if (all.isEmpty() || all.any(::isBlockedAddress)) throw BlockedAddress()
        return all
    }
}

fun linkClient(dns: Dns = GuardedDns()): OkHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .followRedirects(false)
    .followSslRedirects(false)
    .callTimeout(LinkLimits.TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

/** 读链接：成功时是 {ok: true, title, text, url, kind, complete, note}，否则是 {ok: false, reason}（shared/pages.ts 的 LinkResult）。 */
interface LinkFetcher {
    /** 手机自己去取这个链接。 */
    suspend fun read(link: String): JsonObject

    /** 内置浏览器里已经打开的一页（学习者自己登录、过了验证）：用同样的规则提取正文、判断是不是全文（#160）。 */
    suspend fun fromPage(url: String, html: String): JsonObject
}

/** 内置浏览器交回来的页面最多这么长（和取链接的 3 MB 一样）。 */
const val PAGE_HTML_MAX = 3_000_000

private class LinkFailure(message: String) : Exception(message)

private fun failed(reason: String) = buildJsonObject {
    put("ok", false)
    put("reason", reason)
}

class LinkReader(private val prompts: Prompts, private val client: OkHttpClient = linkClient()) : LinkFetcher {
    override suspend fun fromPage(url: String, html: String): JsonObject {
        if (html.length > PAGE_HTML_MAX) return failed("这一页太大了（超过 3 MB），没法读取。可以把正文复制过来贴上。")
        val candidate = buildJsonObject {
            put("url", url)
            put("kind", "page")
        }
        val page = buildJsonObject {
            put("status", 200)
            put("url", url)
            put("contentType", "text/html")
            put("body", html)
        }
        val r = prompts.call("readFetched", candidate, page)
        return (r as? JsonObject) ?: failed("没能从这一页里读出正文。")
    }

    override suspend fun read(link: String): JsonObject {
        val plan = prompts.call("linkPlan", JsonPrimitive(link))
        if (plan is JsonPrimitive) return failed(plan.content)
        var last = failed("没能读取这个链接。")
        for (c in plan.jsonArray) {
            val o = c.jsonObject
            val fetched = try {
                get(o.getValue("url").jsonPrimitive.content)
            } catch (e: LinkFailure) {
                last = failed(e.message.orEmpty())
                continue
            }
            val r = prompts.call("readFetched", c, fetched)
            if (r is JsonNull) continue
            val res = r.jsonObject
            if (res["ok"]?.jsonPrimitive?.content == "true" || o["kind"]?.jsonPrimitive?.content != "arxiv-html") return res
            last = res
        }
        return last
    }

    private suspend fun check(url: String) {
        val bad = prompts.call("checkTarget", JsonPrimitive(url))
        if (bad is JsonPrimitive && bad.isString) throw LinkFailure(bad.content)
    }

    /** 取一个地址：自己跟着跳转走（最多 4 次，每一跳都检查），有大小和时间上限。返回 shared/pages.ts 的 Fetched。 */
    private suspend fun get(start: String): JsonElement {
        var url = start.toHttpUrlOrNull() ?: throw LinkFailure("这不是一个有效的链接。")
        check(url.toString())
        var hop = 0
        while (true) {
            val req = Request.Builder().url(url).apply { HEADERS.forEach { (k, v) -> header(k, v) } }.get().build()
            val result = withContext(Dispatchers.IO) {
                try {
                    client.newCall(req).execute().use { res ->
                        val location = res.header("Location")
                        if (res.code in 300..399 && location != null) return@withContext Result.Redirect(location)
                        val type = res.header("Content-Type").orEmpty()
                        val source = res.body?.source() ?: return@withContext Result.Page(res.code, type, ByteArray(0))
                        if (source.request(LinkLimits.BYTES + 1)) throw LinkFailure("这个页面太大了（超过 3 MB），没法读取。可以把正文复制过来贴上。")
                        Result.Page(res.code, type, source.buffer.readByteArray())
                    }
                } catch (e: LinkFailure) {
                    throw e
                } catch (e: BlockedAddress) {
                    throw LinkFailure(e.message.orEmpty())
                } catch (e: UnknownHostException) {
                    throw LinkFailure("找不到这个网站，请检查链接是不是写对了。")
                } catch (e: InterruptedIOException) {
                    throw LinkFailure("网站太久没有回应，没能读取。可以稍后再试，或者把正文复制过来贴上。")
                } catch (e: IOException) {
                    // 连接时 DNS 那一步拦下的地址，OkHttp 可能包一层再抛出来
                    if (generateSequence<Throwable>(e) { it.cause }.any { it is BlockedAddress }) throw LinkFailure("不能读取本机或局域网里的地址。")
                    throw LinkFailure("没能连上这个网站。可以稍后再试，或者把正文复制过来贴上。")
                }
            }
            when (result) {
                is Result.Redirect -> {
                    if (hop++ >= LinkLimits.REDIRECTS) throw LinkFailure("这个链接跳转了太多次，没能读取。")
                    url = url.resolve(result.location) ?: throw LinkFailure("网站跳转到了一个无效的地址。")
                    try {
                        check(url.toString())
                    } catch (e: LinkFailure) {
                        throw LinkFailure("网站跳转到了不能读取的地址：" + e.message)
                    }
                }

                is Result.Page -> return buildJsonObject {
                    put("status", result.status)
                    put("url", url.toString())
                    put("contentType", result.type)
                    put("body", decodeBody(result.bytes, result.type))
                }
            }
        }
    }

    private sealed interface Result {
        data class Redirect(val location: String) : Result

        class Page(val status: Int, val type: String, val bytes: ByteArray) : Result
    }
}

/** 按网页声明的编码把字节转成文字（默认 UTF-8），和 shared/pages.ts 的 decodeBody 一样。 */
fun decodeBody(bytes: ByteArray, contentType: String): String {
    val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
    val name = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
        ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        ?: "utf-8"
    val cs = runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    return String(bytes, cs)
}

/** 读到的结果里给界面用的部分。 */
data class LinkPage(val title: String, val text: String, val complete: Boolean, val note: String)

fun linkPageOf(o: JsonObject): LinkPage? {
    if (o["ok"]?.jsonPrimitive?.content != "true") return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return LinkPage(s("title"), s("text"), o["complete"]?.jsonPrimitive?.content == "true", s("note"))
}

/** 没能读取时的原因。 */
fun linkReasonOf(o: JsonObject): String = (o["reason"] as? JsonPrimitive)?.content ?: "没能读取这个链接。"
