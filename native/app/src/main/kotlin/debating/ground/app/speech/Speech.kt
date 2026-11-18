package debating.ground.app.speech

// 朗读和语音输入共用的规则，和网页版 shared/speech.ts 一致（切分由 FixturesTest 拿网页版的结果逐条比对）。

const val SPEECH_SEGMENT = 300 // 每段最多这么多字（讯飞单次合成有上限）
const val SPEECH_MAX = 4000 // 一次朗读最多这么多字

// JavaScript 正则里 \s 认的空白：Java 的 \s 只认 ASCII，这里列全
private val SPACE = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

// 用字符码写：直接写成转义会被格式化工具换成看不见的字符
private val TRIM = intArrayOf(0x09, 0x0A, 0x0D, 0x0B, 0x0C, 0x20, 0xA0, 0x3000, 0xFEFF).map { it.toChar() }.toSet()
private val STOP = Regex("[。！？!?；;\\n]")

/** 按句子切成小段：第一段合成好就能开始播。按“字”（码位）算长度，和网页版的 Array.from 一样。 */
fun splitForSpeech(text: String, max: Int = SPEECH_SEGMENT): List<String> {
    val norm = text.replace(SPACE, " ").trim { it in TRIM }
    val chars = norm.codePoints().toArray().take(SPEECH_MAX).map { String(Character.toChars(it)) }
    val out = mutableListOf<String>()
    val cur = mutableListOf<String>()
    fun flush() {
        val s = cur.joinToString("").trim()
        if (s.isNotEmpty()) out.add(s)
        cur.clear()
    }
    for (ch in chars) {
        cur.add(ch)
        // 句末标点处断开；实在太长就在上限处硬断
        if ((cur.size >= max * 0.6 && STOP.matches(ch)) || cur.size >= max) flush()
    }
    flush()
    return out
}

/** 语音输入：16k 单声道 16 位 PCM，每 40ms 一帧（1280 字节）。 */
object ListenSpec {
    const val RATE = 16_000
    const val FRAME_BYTES = 1280
    const val MAX_MS = 60_000L // 讯飞单次最长 60 秒
}

/** 朗读和语音输入放在一起，由 AppModel 持有。 */
class Speech(val voice: Voice, val listen: Listen)
