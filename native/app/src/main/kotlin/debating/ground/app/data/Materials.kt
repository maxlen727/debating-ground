package debating.ground.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// 读材料（#144、#154）：完整的材料只存在这台设备上（App 的私有文件，一份一个文件），
// 不进存档、导出和讨论记录；讨论里只带目录和与眼下话题相关的几节。和网页版 src/lib/materials.ts（IndexedDB）对应。

@Serializable
data class Material(
    val id: String,
    val title: String,
    val sections: List<MaterialSection>,
    val created: Long = 0,
    /** 从哪里来：链接地址，或者 PDF 的文件名（#166）。 */
    val source: String? = null,
)

/** 材料列表里的一行：不带原文。 */
data class MaterialMeta(val id: String, val title: String, val created: Long, val sections: Int)

fun Material.meta() = MaterialMeta(id, title, created, sections.size)

interface MaterialStore {
    suspend fun save(m: Material)

    suspend fun get(id: String): Material?

    suspend fun delete(id: String)

    /** 最近的材料（新的在前）。 */
    suspend fun list(): List<MaterialMeta>
}

/** 测试和预览用：只在内存里。 */
class MemoryMaterials : MaterialStore {
    private val all = ConcurrentHashMap<String, Material>()

    override suspend fun save(m: Material) {
        all[m.id] = m
    }

    override suspend fun get(id: String) = all[id]

    override suspend fun delete(id: String) {
        all.remove(id)
    }

    override suspend fun list() = all.values.map { it.meta() }.sortedByDescending { it.created }
}

/**
 * 存在 [dir] 里，一份材料一个 JSON 文件。[dir] 用 noBackupFilesDir 下的目录：材料可能很长，也可能是不该外传的内容，不进系统备份。
 * 读不懂的文件当作不存在。
 */
class FileMaterials(private val dir: File) : MaterialStore {
    // id 只认字母、数字、- 和 _：别的字符（比如 ../）一律当作不存在，不会读写到目录外面
    private fun file(id: String) = id.takeIf {
        it.isNotEmpty() && it.length <= 64 &&
            it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' }
    }
        ?.let { File(dir, "$it.json") }

    private fun read(f: File) = runCatching { SibianJson.decodeFromString(Material.serializer(), f.readText()) }.getOrNull()

    override suspend fun save(m: Material) = withContext(Dispatchers.IO) {
        val f = file(m.id) ?: return@withContext
        dir.mkdirs()
        // 先写临时文件再改名：写到一半被杀掉也不会留下半个文件
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(SibianJson.encodeToString(Material.serializer(), m))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    override suspend fun get(id: String) = withContext(Dispatchers.IO) { file(id)?.takeIf { it.exists() }?.let(::read) }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { file(id)?.delete() }
    }

    override suspend fun list() = withContext(Dispatchers.IO) {
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull(::read).map { it.meta() }.sortedByDescending { it.created }
    }
}
