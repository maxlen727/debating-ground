package debating.ground.app.data

import java.io.File

// 本地存储：一份 JSON 文件，内容和网页版写进 localStorage 的 sibian-v3 一样。
// 写入先写到临时文件再改名替换：写到一半断电或被杀掉，原来的存档还在，不会被写坏。
class Storage(private val dir: File) {
    private val file get() = File(dir, "$STORE_KEY.json")

    fun write(text: String) {
        dir.mkdirs()
        val tmp = File(dir, "$STORE_KEY.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 有的文件系统不能直接覆盖：先删再改名（这一瞬间只剩临时文件，下次读不到会从临时文件恢复）
            file.delete()
            check(tmp.renameTo(file)) { "存档没能写入" }
        }
    }

    /** 读回存档：主文件不在、但上次替换到一半留下了临时文件时，用临时文件。 */
    fun read(): String? = file.takeIf { it.exists() }?.readText() ?: File(dir, "$STORE_KEY.json.tmp").takeIf { it.exists() }?.readText()
}
