package debating.ground.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import debating.ground.app.data.SibianJson
import debating.ground.app.ui.BusyStrip
import debating.ground.app.ui.ErrorBanner
import debating.ground.app.ui.FinePrint
import debating.ground.app.ui.PrimaryButton
import debating.ground.app.ui.TopBar
import debating.ground.app.ui.WithFloatingDock
import kotlinx.serialization.json.JsonPrimitive

// 读材料的内置浏览器（#160）：反爬严格、要登录、正文要等网页加载后才出现的网站（知乎、微信公众号、一些期刊），
// 让学习者在 App 里自己打开、登录、过验证，然后点“用这一页”：把浏览器里已经显示出来的页面交给共用的规则提取正文。
// 只是一个浏览器：不往网页里注入任何接口（没有 addJavascriptInterface），不读本地文件，只走 http/https；
// 登录状态和普通浏览器一样留在这台手机的 App 里。

/** 只在 http/https 之间跳转；别的协议（intent:、file: 之类）一律不开。 */
fun browsable(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/** WebView 的 evaluateJavascript 把结果包成 JSON 字符串交回来。 */
fun unwrapJsString(raw: String?): String =
    runCatching { (SibianJson.parseToJsonElement(raw ?: "") as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull().orEmpty()

@SuppressLint("SetJavaScriptEnabled") // 很多网站的正文要靠脚本才显示出来；不注入任何接口，网页碰不到 App
@Composable
fun InAppBrowser(
    start: String,
    busy: String?,
    error: String,
    onClearError: () -> Unit,
    onCancel: () -> Unit,
    onUse: (url: String, html: String) -> Unit,
    onClose: () -> Unit,
) {
    var view by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(start) }
    var loading by remember { mutableStateOf(true) }
    // 返回键：网页能后退就后退，退到头再关掉浏览器
    BackHandler { if (view?.canGoBack() == true) view?.goBack() else onClose() }
    DisposableEffect(Unit) {
        onDispose {
            view?.run {
                stopLoading()
                destroy()
            }
        }
    }
    WithFloatingDock(dock = {
        ErrorBanner(error, onClearError)
        BusyStrip(busy, onCancel)
        PrimaryButton(
            if (loading) "用这一页（还在加载）" else "用这一页",
            {
                view?.evaluateJavascript("document.documentElement.outerHTML") { raw -> onUse(url, unwrapJsString(raw)) }
            },
            Modifier.fillMaxWidth(),
            enabled = busy == null && view != null,
        )
    }) { bottom ->
        Column(Modifier.fillMaxSize()) {
            TopBar(title.ifEmpty { "在 App 里打开" }, url, onBack = onClose)
            FinePrint(
                "需要登录或验证就在这里完成，看到正文后点“用这一页”。登录只留在这台手机的 App 里。",
                Modifier.padding(horizontal = 16.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = bottom)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.setSupportMultipleWindows(false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = !browsable(request.url.toString())

                                override fun onPageStarted(v: WebView, u: String, favicon: Bitmap?) {
                                    url = u
                                    loading = true
                                }

                                override fun onPageFinished(v: WebView, u: String) {
                                    url = u
                                    title = v.title.orEmpty().takeIf { it != u }.orEmpty()
                                    loading = false
                                }
                            }
                            if (browsable(start)) loadUrl(start)
                            view = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
