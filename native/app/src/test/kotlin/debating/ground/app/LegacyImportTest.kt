package debating.ground.app

import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.migrate.LegacyData
import debating.ground.app.migrate.LegacyImport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// 从套壳版搬知识库（#104）：旧数据用网页版真实写出来的存档（标准答案里的 *.saved.json）
class LegacyImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("fixtures/$name")!!.toURI()).readText()

    private fun stored(dir: File) = Storage(dir).read()?.let { SibianJson.decodeFromString(SavedState.serializer(), it) }

    @Test fun importsTheOldKnowledgeBaseOnce() = runBlocking {
        val dir = tmp.newFolder()
        var reads = 0
        val import = LegacyImport(dir, {
            reads++
            LegacyData(fixture("rich-library.saved.json"), null)
        }, { 1763366400000 })
        assertTrue(import.pending())
        val result = import.run()!!
        assertTrue(result.cards > 0)
        assertEquals(result.cards, stored(dir)!!.library.size)
        // 搬过了：以后不再读旧数据
        assertFalse(import.pending())
        assertNull(import.run())
        assertEquals(1, reads)
    }

    @Test fun neverOverwritesWhatTheNativeAppAlreadyHas() = runBlocking {
        val dir = tmp.newFolder()
        Storage(dir).write(fixture("socratic-finished.saved.json"))
        val before = Storage(dir).read()
        val import = LegacyImport(dir, { error("原生存储里已经有数据时，不该去读旧数据") })
        assertFalse(import.pending())
        assertNull(import.run())
        assertEquals(before, Storage(dir).read())
    }

    @Test fun aFailedReadIsRetriedNextTime() = runBlocking {
        val dir = tmp.newFolder()
        assertNull(LegacyImport(dir, { null }).run())
        // 没读成：不记“已搬过”，也不写原生存储
        assertTrue(LegacyImport(dir, { null }).pending())
        assertNull(Storage(dir).read())
        // 这期间用户改了设置，原生存档写出来了：只有设置不算数据，下次照样重试
        Storage(dir).write(SibianJson.encodeToString(SavedState.serializer(), SavedState()))
        assertTrue(LegacyImport(dir, { null }).pending())
        val result = LegacyImport(dir, { LegacyData(fixture("rich-library.saved.json"), null) }).run()
        assertTrue(result!!.cards > 0)
    }

    @Test fun nothingToImportIsRememberedWithoutWritingAnything() = runBlocking {
        val dir = tmp.newFolder()
        assertNull(LegacyImport(dir, { LegacyData(null, null) }).run())
        assertFalse(LegacyImport(dir, { error("不该再读") }).pending())
        assertNull(Storage(dir).read())
    }

    // 套壳版没有“本机服务”；原生版的 api 是直接连接，要重新填密钥：先回到演示模式
    @Test fun theLocalServerModeFallsBackToTheDemo() = runBlocking {
        val dir = tmp.newFolder()
        val raw = fixture(
            "socratic-finished.saved.json",
        ).replace("\"aiMode\":\"demo\"", "\"aiMode\":\"api\"").replace("\"aiMode\": \"demo\"", "\"aiMode\": \"api\"")
        assertTrue("标准答案里要有 aiMode 才测得到", raw.contains("\"api\""))
        LegacyImport(dir, { LegacyData(raw, null) }).run()
        assertEquals("demo", stored(dir)!!.config.aiMode)
    }

    // 损坏的旧存档：和网页版一样逐项检查、能留的留下，不会因此崩溃
    @Test fun aDamagedOldSaveIsCheckedLikeTheWebApp() = runBlocking {
        val dir = tmp.newFolder()
        val result = LegacyImport(dir, { LegacyData(fixture("damaged.saved.json"), null) }).run()
        val restored = SibianJson.parseToJsonElement(fixture("damaged.restored.json"))
        assertEquals(result!!.cards, (restored as kotlinx.serialization.json.JsonObject)["library"].let { (it as kotlinx.serialization.json.JsonArray).size })
    }
}
