package debating.ground.app

import debating.ground.app.update.Release
import debating.ground.app.update.Verdict
import debating.ground.app.update.compareVersions
import debating.ground.app.update.parseRelease
import debating.ground.app.update.verdictOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 检查更新：版本号比较、和最新正式版比的结论、读 GitHub Releases 的返回
class UpdatesTest {
    @Test
    fun versionsCompareByNumbers() {
        assertTrue(compareVersions("0.11.0", "0.10.0") > 0)
        assertTrue(compareVersions("v0.10.2", "0.10.10") < 0)
        assertEquals(0, compareVersions("0.10.0", "0.10.0-preview.123"))
        assertTrue(compareVersions("1.0", "0.99.9") > 0)
    }

    @Test
    fun verdictTellsNewerUpToDateAndPreview() {
        val r = Release("0.11.0", "", null, "page")
        assertTrue(verdictOf("0.10.0", r) is Verdict.Newer)
        assertTrue(verdictOf("0.11.0", r) is Verdict.UpToDate)
        assertTrue(verdictOf("0.11.0-preview.200", r) is Verdict.Preview)
        assertTrue(verdictOf("0.12.0", r) is Verdict.UpToDate)
        // 旧的预览版也会提示新的正式版
        assertTrue(verdictOf("0.10.0-preview.5", r) is Verdict.Newer)
    }

    @Test
    fun parsesTheLatestRelease() {
        val body = """{"tag_name":"v0.11.0","body":" 更新说明 ","html_url":"https://github.com/x/releases/tag/v0.11.0",
            "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},{"name":"sibian-0.11.0.apk","browser_download_url":"https://x/sibian-0.11.0.apk"}]}"""
        val r = parseRelease(body)!!
        assertEquals("0.11.0", r.version)
        assertEquals("更新说明", r.notes)
        assertEquals("https://x/sibian-0.11.0.apk", r.apkUrl)
        assertNull(parseRelease("""{"message":"Not Found"}"""))
        assertNull(parseRelease("不是 JSON"))
        assertNull(parseRelease("""{"tag_name":"v1.0.0","assets":[]}""")!!.apkUrl)
    }
}
