package debating.ground.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

// 读回存档时先检查结构。逐行对应网页版的 src/lib/restore.ts 和 src/store.ts 的 load()，
// 输出和网页版完全一致（FixturesTest 用网页版生成的标准答案逐字比对）。
// 存档可能来自旧版本（缺字段），也可能写到一半被打断：缺的补上默认值，坏到没法用的那一块才丢掉。
// 原则：知识卡片能救就救（宁可补空字段，也不整张丢掉）；计划和进行中的讨论坏了就作废，不让界面一打开就崩溃。

/** 存档的键名，和网页版 localStorage 里的一样。 */
const val STORE_KEY = "sibian-v3"
const val OLD_STORE_KEY = "sibian-v2"

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject

private fun JsonElement?.isStr() = this is JsonPrimitive && this.isString

private fun str(v: JsonElement?, fallback: String = ""): JsonPrimitive = if (v.isStr()) v as JsonPrimitive else JsonPrimitive(fallback)

// JSON 里的数字（不是字符串、不是 true/false/null）；对应 typeof v === 'number' && Number.isFinite(v)
private fun JsonElement?.number(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }

private fun num(v: JsonElement?, fallback: Long = 0): JsonElement = if (v.number() != null) v!! else JsonPrimitive(fallback)

private fun list(v: JsonElement?): List<JsonElement> = (v as? JsonArray) ?: emptyList()

private fun strings(v: JsonElement?) = JsonArray(list(v).filter { it.isStr() })

private fun objects(v: JsonElement?) = list(v).filterIsInstance<JsonObject>()

private val MODE_IDS = setOf("socratic", "roundtable", "teach", "debate", "canvas")

private fun JsonElement?.isMode() = isStr() && (this as JsonPrimitive).content in MODE_IDS

private fun JsonElement?.text() = if (isStr()) (this as JsonPrimitive).content else null

private fun sourceOf(v: JsonElement?) = JsonPrimitive(if (v.text() == "api") "api" else "demo")

// 一个角色至少要有 id 和名字
private fun personasOf(v: JsonElement?) = objects(v).filter { it["id"].isStr() && it["name"].isStr() }

fun restoreLibrary(v: JsonElement?, now: Long): JsonArray = JsonArray(
    objects(v).filter { it["id"].text().orEmpty().isNotEmpty() }.map { c ->
        JsonObject(
            c + mapOf(
                "concept" to str(c["concept"]),
                "topic" to str(c["topic"]),
                "oneLine" to str(c["oneLine"]),
                "sessionId" to str(c["sessionId"]),
                "mode" to (if (c["mode"].isMode()) c["mode"]!! else JsonPrimitive("socratic")),
                "created" to num(c["created"], now),
                "level" to num(c["level"]),
                "due" to num(c["due"]),
                // 卡片来自复盘，只有这两种来源
                "source" to sourceOf(c["source"]),
                "points" to JsonArray(objects(c["points"])),
                "misconceptions" to JsonArray(objects(c["misconceptions"])),
                "quiz" to JsonArray(objects(c["quiz"]).filter { it["options"] is JsonArray }),
                "reviews" to JsonArray(objects(c["reviews"])),
            ),
        )
    },
)

fun restorePlan(v: JsonElement?): JsonElement {
    val p = v.obj() ?: return JsonNull
    if (!p["mode"].isMode() || !p["concept"].isStr() || !p["topic"].isStr()) return JsonNull
    if (p["keyPoints"] !is JsonArray || p["personas"] !is JsonArray) return JsonNull
    val personas = personasOf(p["personas"])
    // 讨论类模式至少要有一个角色，否则开始讨论时没有人开场
    if (p["mode"].text() != "debate" && personas.isEmpty()) return JsonNull
    return JsonObject(
        p + mapOf(
            "keyPoints" to strings(p["keyPoints"]),
            "personas" to JsonArray(personas),
            "opening" to str(p["opening"]),
            "source" to sourceOf(p["source"]),
            "previous" to (p["previous"].obj() ?: JsonNull),
        ),
    )
}

fun restoreSession(v: JsonElement?, now: Long): JsonElement {
    val s = v.obj() ?: return JsonNull
    if (!s["id"].isStr() || !s["mode"].isMode() || !s["concept"].isStr() || !s["topic"].isStr()) return JsonNull
    if (s["entries"] !is JsonArray || s["keyPoints"] !is JsonArray) return JsonNull
    val entries = objects(s["entries"]).filter {
        it["id"].isStr() && it["kind"].text() in setOf("msg", "sys", "reflect") && it["text"].isStr()
    }
    val personas = personasOf(s["personas"])
    val turns = objects(s["turns"]).filter { it["role"] is JsonObject }
    val finished = (s["finished"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
    // 单人讨论没有角色、辩论没有赛程，都没法继续（多人房间的状态以后端为准，重连后会补上）
    val truthyFinished = truthy(s["finished"])
    if (s["room"] !is JsonObject && !truthyFinished && (if (s["mode"].text() == "debate") turns.isEmpty() else personas.isEmpty())) return JsonNull
    val review = s["review"].obj()?.takeIf { it["points"] is JsonArray }
    val out = s.toMutableMap()
    out += mapOf(
        "entries" to JsonArray(entries),
        "personas" to JsonArray(personas),
        "turns" to JsonArray(turns),
        "keyPoints" to strings(s["keyPoints"]),
        "covered" to JsonArray(list(s["covered"]).filter { it.number() != null }),
        "seats" to JsonArray(objects(s["seats"])),
        "stages" to JsonArray(objects(s["stages"])),
        "created" to num(s["created"], now),
        "learnerTurns" to num(s["learnerTurns"]),
        "nextRole" to num(s["nextRole"]),
        "demoStep" to num(s["demoStep"]),
        "cursor" to num(s["cursor"]),
        "finished" to JsonPrimitive(finished),
        "draft" to str(s["draft"]),
        "previous" to (s["previous"].obj() ?: JsonNull),
        "review" to (review ?: JsonNull),
    )
    if (s["branches"] is JsonArray) out["branches"] = JsonArray(objects(s["branches"]))
    if (out["room"] !is JsonObject) out.remove("room")
    if (out["branches"] !is JsonArray) out.remove("branches")
    return JsonObject(out)
}

// JavaScript 里的“真值”：用在 !v.finished 这类判断上
private fun truthy(v: JsonElement?): Boolean = when (v) {
    null, JsonNull -> false
    is JsonPrimitive -> if (v.isString) v.content.isNotEmpty() else v.content != "false" && v.number() != 0.0 && v.number()?.isNaN() != true
    else -> true
}

// 设置逐项检查：某一项的形状不对就用默认值，其余照旧
fun restoreConfig(v: JsonElement?, base: JsonObject): JsonObject {
    val c = v.obj() ?: return base
    fun seatsOk(s: JsonElement?) = s is JsonArray && s.isNotEmpty() && s.all { it is JsonObject && it["type"].isStr() && it["bg"].isStr() }
    val seats = c["seats"].obj()?.takeIf { seatsOk(it["A"]) && seatsOk(it["B"]) } ?: base.getValue("seats")
    val counts = c["counts"].obj()?.takeIf { o ->
        listOf(o["A"], o["B"]).all { n -> n.number()?.let { it >= 1 && it <= 4 } == true }
    } ?: base.getValue("counts")
    val stages = (c["stages"] as? JsonArray)?.takeIf { a ->
        a.isNotEmpty() && a.all { it is JsonObject && it["name"].isStr() && it["kind"].isStr() && it["rounds"].number() != null }
    } ?: base.getValue("stages")
    val remote = c["remote"].obj()?.takeIf { it["url"].isStr() && it["password"].isStr() }
        ?.let { JsonObject(mapOf("url" to it.getValue("url"), "password" to it.getValue("password"))) }
        ?: base.getValue("remote")
    val turnSeconds = c["turnSeconds"].number()?.takeIf { it in setOf(0.0, 30.0, 60.0, 90.0, 120.0) }
    return buildJsonObject {
        put("concept", str(c["concept"], base.getValue("concept").text().orEmpty()))
        put("mode", if (c["mode"].isMode()) c.getValue("mode") else base.getValue("mode"))
        put("aiMode", if (c["aiMode"].text() in setOf("api", "remote")) c.getValue("aiMode") else JsonPrimitive("demo"))
        put("remote", remote)
        put("name", str(c["name"]))
        put("counts", counts)
        put("seats", seats)
        put("stages", stages)
        put("turnSeconds", if (turnSeconds != null) c.getValue("turnSeconds") else base.getValue("turnSeconds"))
        put("voiceName", str(c["voiceName"], base.getValue("voiceName").text().orEmpty()))
    }
}

/**
 * 读回整份存档：对应网页版 store.ts 的 load()。
 * [saved] 是 sibian-v3 的内容，[oldSaved] 是更早的 sibian-v2（只取知识卡片）；读坏了就从头开始。
 * 返回和网页版一样的 { config, plan, session, library, screen }。
 */
fun restoreSaved(saved: String?, oldSaved: String?, now: Long, json: Json = SibianJson): JsonObject {
    val base = defaultConfigJson(json)
    fun fresh(library: JsonArray = JsonArray(emptyList())) = buildJsonObject {
        put("config", base)
        put("plan", JsonNull)
        put("session", JsonNull)
        put("library", library)
        put("screen", "learn")
    }
    return runCatching {
        val root = saved?.let { json.parseToJsonElement(it) }.obj()
        if (root != null) {
            val s = root.toMutableMap()
            // 反向讲解并进了苏格拉底追问（“换我来讲”）：选着它的设置改过来，按它准备的计划作废；进行中的讨论和知识卡片照旧
            s["config"].obj()?.takeIf { it["mode"].text() == "teach" }?.let { s["config"] = JsonObject(it + ("mode" to JsonPrimitive("socratic"))) }
            s["plan"].obj()?.takeIf { it["mode"].text() == "teach" }?.let { s["plan"] = JsonNull }
            val session = restoreSession(s["session"], now)
            // 讨论还没结束才回到讨论页（restoreSession 已经把 finished 规整成 true/false）
            val resumable = session is JsonObject && (session["finished"] as JsonPrimitive).content != "true"
            buildJsonObject {
                put("config", restoreConfig(s["config"], base))
                put("plan", restorePlan(s["plan"]))
                put("session", session)
                put("library", restoreLibrary(s["library"], now))
                put("screen", if (s["screen"].text() == "session" && resumable) "session" else "learn")
            }
        } else {
            val old = oldSaved?.let { json.parseToJsonElement(it) }.obj()
            if (old != null && old["library"] is JsonArray) fresh(restoreLibrary(old["library"], now)) else fresh()
        }
    }.getOrElse { fresh() }
}
