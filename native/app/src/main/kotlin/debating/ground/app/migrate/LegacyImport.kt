package debating.ground.app.migrate

import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import java.io.File

// 从套壳版（Capacitor）搬知识库：旧 App 的数据在 WebView 的 localStorage 里（来源 https://localhost），
// 原生版用同一个包名升级上来以后，那份数据还在手机上。第一次启动时读出来，经过和网页版一样的结构检查，写进原生存储。
// 规则（#104）：原生存储里已经有数据就不搬、不覆盖；读成功才记下“已搬过”，失败了下次启动再试；旧数据从不清除。

/** 旧 App 留下的两份数据（都可能没有）。 */
data class LegacyData(val v3: String?, val v2: String?)

/** 读旧数据的地方。返回 null 表示这次没读成（下次再试），不是“没有数据”。 */
fun interface LegacySource {
    suspend fun read(): LegacyData?
}

/** 搬了什么：[cards] 是搬过来的知识卡片数，告诉用户一声。 */
data class Imported(val state: SavedState, val cards: Int)

class LegacyImport(private val dir: File, private val source: LegacySource, private val now: () -> Long = System::currentTimeMillis) {
    private val storage = Storage(dir)
    private val marker get() = File(dir, "legacy-imported")

    /** 还要不要搬：没搬过，而且原生存储里还没有数据。 */
    fun pending(): Boolean = !marker.exists() && storage.read() == null

    suspend fun run(): Imported? {
        if (marker.exists()) return null
        if (storage.read() != null) {
            mark()
            return null
        }
        val data = source.read() ?: return null
        if (data.v3 == null && data.v2 == null) {
            // 旧 App 里什么都没有（或者本来就是新装的）：以后不用再找了
            mark()
            return null
        }
        val saved = loadState(data.v3, data.v2, now())
        // 套壳版里没有“本机服务”；原生版的 "api" 是直接连接，要重新填密钥，先回到演示模式，免得一打开就报错
        val fixed = if (saved.config.aiMode == "api") saved.copy(config = saved.config.copy(aiMode = "demo")) else saved
        storage.write(SibianJson.encodeToString(SavedState.serializer(), fixed))
        mark()
        return Imported(fixed, fixed.library.size)
    }

    private fun mark() {
        dir.mkdirs()
        marker.writeText("1")
    }
}
