package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.ai.PdfImporter
import debating.ground.app.ai.pdfPageRoute
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 读材料的 PDF（#164）：真正的解析在隐藏的 WebView 里跑网页版的代码（JVM 测试跑不了 WebView，
// 网页版的 tests/pdf.test.ts 测解析本身）。这里测隐藏页面只认 App 自己的地址，和 Learning 怎么用结果。
class PdfTest {
    @Test
    fun theHiddenPageOnlyReachesTheAppsOwnFiles() {
        val host = "appassets.androidplatform.net"
        assertEquals("pdf.html", pdfPageRoute(host, "/pdf.html"))
        assertEquals("assets/pdf-abc.js", pdfPageRoute(host, "/assets/pdf-abc.js"))
        assertEquals("/pdf-input", pdfPageRoute(host, "/pdf-input"))
        // 别的网站、API、跳出 web 目录的路径一律拒绝
        assertNull(pdfPageRoute("example.com", "/pdf.html"))
        assertNull(pdfPageRoute("cdn.jsdelivr.net", "/npm/pdfjs-dist"))
        assertNull(pdfPageRoute(host, "/api/fetch"))
        assertNull(pdfPageRoute(host, "/../sibian-ai.js"))
        assertNull(pdfPageRoute(host, "/%2e%2e/sibian-ai.js"))
        assertNull(pdfPageRoute(null, "/pdf.html"))
    }

    private fun learning(answer: suspend () -> kotlinx.serialization.json.JsonObject) = Learning(
        AppState(),
        Ai(demoTypingMs = 0),
        pdf = PdfImporter {
            answer()
        },
    )

    @Test
    fun learningFillsThePdfOrShowsTheReason() = runBlocking {
        val ok = learning {
            buildJsonObject {
                put("ok", true)
                put("title", "Attention Is All You Need")
                put("text", "## Abstract\n\n正文")
                put("pages", 15)
                put("note", "按排版拼回来的。")
            }
        }
        val page = ok.importPdf("content://doc/1")!!
        assertEquals("Attention Is All You Need", page.title)
        assertEquals(15, page.pages)
        assertNull(ok.state.ui.busy)
        val scanned = learning {
            buildJsonObject {
                put("ok", false)
                put("reason", "这个 PDF 里几乎没有可以复制的文字，可能是扫描的图片。")
            }
        }
        assertNull(scanned.importPdf("content://doc/2"))
        assertTrue(scanned.state.ui.error.contains("扫描的图片"))
        val broken = learning { error("WebView 崩了") }
        assertNull(broken.importPdf("content://doc/3"))
        assertTrue(broken.state.ui.error.contains("打不开"))
        assertNull(broken.state.ui.busy)
        val none = Learning(AppState(), Ai(demoTypingMs = 0))
        assertNull(none.importPdf("content://doc/4"))
        assertTrue(none.state.ui.error.contains("不支持读取 PDF"))
    }
}
