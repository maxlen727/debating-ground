package debating.ground.app.update

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

// 检查更新：问 GitHub 上最新的正式版（Releases 的 latest），和这台手机上装的版本比一比。
// 只读一个公开的接口，不带任何用户数据；下载交给浏览器（系统会接着问要不要安装），App 不需要“安装未知应用”的权限。

const val RELEASES_API = "https://api.github.com/repos/maxlen727/debating-ground/releases/latest"

/** 一个正式版：版本号、更新说明、安装包和发布页的地址。 */
data class Release(val version: String, val notes: String, val apkUrl: String?, val pageUrl: String)

/** 比较的结果：有新版本 / 已经是最新 / 装的是预览版或开发版（比正式版新或者没法比）。 */
sealed interface Verdict {
    data class Newer(val release: Release) : Verdict

    data class UpToDate(val release: Release) : Verdict

    data class Preview(val release: Release) : Verdict
}

// 1.2.3 → [1, 2, 3]；带后缀的（-preview.5、-pr.3、-dev）只取前面的数字
private fun numbers(v: String): List<Int> = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

/** a 比 b 新返回正数，旧返回负数，一样返回 0（只看数字部分）。 */
fun compareVersions(a: String, b: String): Int {
    val x = numbers(a)
    val y = numbers(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** 装的版本和最新正式版比：正式版更新就提示；数字一样但装的是预览版、开发版，说明它已经比正式版新。 */
fun verdictOf(installed: String, release: Release): Verdict {
    val d = compareVersions(release.version, installed)
    return when {
        d > 0 -> Verdict.Newer(release)
        installed.contains('-') -> Verdict.Preview(release)
        else -> Verdict.UpToDate(release)
    }
}

/** 读 GitHub Releases 的 latest：找不到版本号就当作读不懂，返回 null。 */
fun parseRelease(body: String): Release? {
    val o = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val tag = str("tag_name") ?: return null
    val apk = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstNotNullOfOrNull { a ->
        val name = (a["name"] as? JsonPrimitive)?.content.orEmpty()
        (a["browser_download_url"] as? JsonPrimitive)?.content?.takeIf { name.endsWith(".apk") }
    }
    return Release(tag.removePrefix("v"), str("body").orEmpty().trim(), apk, str("html_url") ?: "https://github.com/maxlen727/debating-ground/releases")
}

/** 问一次最新正式版。网络不通、GitHub 限流时抛出带原因的错误。 */
suspend fun fetchLatest(client: OkHttpClient = defaultClient): Release = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(RELEASES_API)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "sibian-android")
        .build()
    client.newCall(req).execute().use { res ->
        if (res.code == 403 || res.code == 429) error("GitHub 暂时限制了查询次数，过一会儿再试。")
        if (!res.isSuccessful) error("没能查到最新版本（HTTP ${res.code}），稍后再试。")
        parseRelease(res.body?.string().orEmpty()) ?: error("没能看懂 GitHub 返回的版本信息，稍后再试。")
    }
}

private val defaultClient by lazy { OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build() }

/** 这台手机上装的版本号。 */
fun installedVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.0.0"

// 启动时自动检查：这台设备自己的设置，默认关闭；打开后一天最多查一次
object UpdatePrefs {
    private const val FILE = "updates"
    private const val DAY = 86_400_000L

    fun auto(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("auto", false)

    fun setAuto(context: Context, on: Boolean) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean("auto", on) }

    /** 打开了自动检查、离上次查已经超过一天：该查了（同时记下这次的时间）。 */
    fun due(context: Context, now: Long): Boolean {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.getBoolean("auto", false) || now - p.getLong("last", 0) < DAY) return false
        p.edit { putLong("last", now) }
        return true
    }
}
