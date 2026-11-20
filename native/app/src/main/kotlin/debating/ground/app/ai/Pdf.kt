package debating.ground.app.ai

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import debating.ground.app.lan.staticName
import debating.ground.app.lan.webType
import debating.ground.app.ui.screens.unwrapJsString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream

// 读材料的 PDF（#164）：安卓系统自带的 PdfRenderer 只能把页面画成图片，取不出文字。
// 这里在一个看不见的 WebView 里打开 App 打包的网页版 pdf.html，用和网页版同一份代码（src/lib/pdf.ts，pdf.js）取出文字。
// 这个 WebView 只认 App 自己的地址：网页版文件从 assets/web 里给，学习者选的 PDF 从 /pdf-input 给，别的请求一律拒绝，
// 所以 PDF 里的内容碰不到网络。PDF 只在这台手机上解析，不上传。

/** 读一个 PDF：成功时是 {ok: true, title, text, pages, note}，否则是 {ok: false, reason}（src/lib/pdf.ts 的 PdfResult）。 */
fun interface PdfImporter {
    suspend fun read(uri: String): JsonObject
}

/** 和网页版的 PDF_LIMITS.bytes 一样。 */
const val PDF_MAX_BYTES = 30_000_000L

private const val HOST = "appassets.androidplatform.net"
private const val INPUT = "/pdf-input"
private const val TIMEOUT_MS = 120_000L

private fun failed(reason: String) = buildJsonObject {
    put("ok", false)
    put("reason", reason)
}

/** 隐藏页面里的请求怎么回：只给 App 自己的文件。返回 null 表示拒绝。 */
internal fun pdfPageRoute(host: String?, path: String?): String? = when {
    host != HOST || path == null -> null
    path == INPUT -> INPUT
    else -> staticName(path)
}

class WebViewPdf(private val context: Context) : PdfImporter {
    override suspend fun read(uri: String): JsonObject {
        val u = uri.toUri()
        val (name, size) = withContext(Dispatchers.IO) { meta(u) }
        if (size > PDF_MAX_BYTES) return failed("这个 PDF 太大了（超过 30 MB）。可以只把想读的部分复制出来贴上。")
        return withContext(Dispatchers.Main) {
            val view = open(u)
            try {
                withTimeoutOrNull(TIMEOUT_MS) { poll(view, name) } ?: failed("读这个 PDF 太久了，没有读完。可以只把想读的部分复制出来贴上。")
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    private fun meta(u: Uri): Pair<String, Long> {
        var name = "document.pdf"
        var size = -1L
        runCatching {
            context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    @SuppressLint("SetJavaScriptEnabled") // pdf.js 要运行；页面是 App 自己打包的，不注入接口，网络请求全部拒绝
    private fun open(input: Uri): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val route = pdfPageRoute(request.url.host, request.url.path) ?: return refused()
                if (route == INPUT) {
                    val stream = runCatching { context.contentResolver.openInputStream(input) }.getOrNull() ?: return refused()
                    return WebResourceResponse("application/pdf", null, stream)
                }
                val stream = runCatching { context.assets.open("web/$route") }.getOrNull() ?: return refused()
                return WebResourceResponse(webType(route).substringBefore(';'), "utf-8", stream)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        loadUrl("https://$HOST/pdf.html")
    }

    private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private suspend fun eval(view: WebView, js: String): String = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(js) { cont.resumeWith(Result.success(it.orEmpty())) }
    }

    // 页面加载好以后让它开始读，然后每 0.3 秒看一眼结果
    private suspend fun poll(view: WebView, name: String): JsonObject {
        while (eval(view, "typeof window.sibianPdf") != "\"function\"") delay(200)
        eval(view, "window.sibianPdf(${JsonPrimitive(INPUT)}, ${JsonPrimitive(name)})")
        while (true) {
            val raw = unwrapJsString(eval(view, "window.sibianPdfResult ? window.sibianPdfResult.result : ''"))
            if (raw.isNotEmpty()) {
                return runCatching { debating.ground.app.data.SibianJson.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: failed("这个文件打不开，可能不是 PDF 或者已经损坏。")
            }
            delay(300)
        }
    }
}

/** 读到的 PDF 里给界面用的部分。 */
data class PdfPage(val title: String, val text: String, val pages: Int, val note: String)

fun pdfPageOf(o: JsonObject): PdfPage? {
    if (o["ok"]?.jsonPrimitive?.content != "true") return null
    fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    return PdfPage(s("title"), s("text"), (o["pages"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0, s("note"))
}
