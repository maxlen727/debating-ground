package debating.ground.app.migrate

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// 在套壳版用过的同一个来源（https://localhost）里打开一个只读的小页面，读出 localStorage 里的旧数据。
// 页面内容由这里直接给出，不访问网络；页面只读不写，旧数据原样留在手机上。
private const val PAGE = """<!doctype html><meta charset="utf-8"><script>
try { SibianLegacy.done(localStorage.getItem('sibian-v3'), localStorage.getItem('sibian-v2')); }
catch (e) { SibianLegacy.fail(); }
</script>"""

class WebViewLegacySource(private val context: Context, private val timeoutMs: Long = 10_000) : LegacySource {
    // 只在这个固定的本地页面里开 JS，用来读 localStorage；页面不加载任何外部内容
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun read(): LegacyData? = withContext(Dispatchers.Main) {
        var view: WebView? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val main = Handler(Looper.getMainLooper())
                    val wv = WebView(context.applicationContext)
                    view = wv
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun done(v3: String?, v2: String?) = main.post { if (cont.isActive) cont.resume(LegacyData(v3, v2)) }

                            @JavascriptInterface
                            fun fail() = main.post { if (cont.isActive) cont.resume(null) }
                        },
                        "SibianLegacy",
                    )
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            // 只回应 https://localhost 这一个页面；别的请求一律给空，不出网
                            return if (url.scheme == "https" && url.host == "localhost") {
                                WebResourceResponse("text/html", "utf-8", PAGE.byteInputStream())
                            } else {
                                WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
                            }
                        }
                    }
                    wv.loadUrl("https://localhost/__sibian_legacy.html")
                }
            }
        } finally {
            view?.destroy()
        }
    }
}
