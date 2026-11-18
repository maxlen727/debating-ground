package debating.ground.app

import debating.ground.app.data.SibianJson
import debating.ground.app.speech.AudioOut
import debating.ground.app.speech.Heard
import debating.ground.app.speech.Listen
import debating.ground.app.speech.ListenLink
import debating.ground.app.speech.Phase
import debating.ground.app.speech.Voice
import debating.ground.app.speech.splitForSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// 朗读和语音输入：切分规则和网页版 shared/speech.ts 逐条比对；播放和听写的流程用假的播放器、麦克风和连接
@RunWith(RobolectricTestRunner::class)
class SpeechTest {
    private val now = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun splitsLikeTheWeb() {
        val cases = SibianJson.parseToJsonElement(File(javaClass.classLoader!!.getResource("fixtures/speech-cases.json")!!.toURI()).readText()) as JsonArray
        for (c in cases) {
            val o = c.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            val parts = (o.getValue("parts") as JsonArray).map { it.jsonPrimitive.content }
            assertEquals("「${text.take(20)}」", parts, splitForSpeech(text))
        }
    }

    private class FakeOut : AudioOut {
        val played = mutableListOf<String>()
        var done: (() -> Unit)? = null
        var stopped = 0

        override fun play(audio: ByteArray, mime: String, onDone: () -> Unit, onError: () -> Unit) {
            played += String(audio)
            done = onDone
        }

        override fun stop() {
            stopped++
            done = null
        }
    }

    @Test
    fun playsSegmentsInOrderAndStopsWhenTappedAgain() {
        val out = FakeOut()
        val asked = mutableListOf<String>()
        val voice = Voice(now, { text, v ->
            asked += "$v:$text"
            text.toByteArray() to "audio/mpeg"
        }, out) {}
        val text = "第一句话说得比较长一些。".repeat(20) + "最后一句。"
        voice.toggle("m1", text, "x4_xiaoyan")
        val parts = splitForSpeech(text)
        assertTrue("要分成几段", parts.size > 1)
        assertEquals(parts.map { "x4_xiaoyan:$it" }, asked)
        assertEquals("m1", voice.playing.value)
        // 一段播完接着播下一段
        assertEquals(listOf(parts[0]), out.played)
        out.done!!()
        assertEquals(parts.take(2), out.played)
        // 再点同一条：停
        voice.toggle("m1", text, "x4_xiaoyan")
        assertNull(voice.playing.value)
    }

    @Test
    fun aFailureMidwayPlaysWhatArrivedAndToastsTheReason() {
        val out = FakeOut()
        val toasts = mutableListOf<String>()
        var n = 0
        val voice = Voice(now, { text, _ ->
            if (n++ ==
                0
            ) {
                text.toByteArray() to "audio/mpeg"
            } else {
                throw debating.ground.app.ai.AiFailure("讯飞合成失败：额度不足。")
            }
        }, out) { toasts += it }
        val text = "一句不短的话，用来凑够长度。".repeat(30)
        voice.toggle("m1", text, "v")
        assertEquals(listOf("讯飞合成失败：额度不足。"), toasts)
        assertEquals("已经在播的这段播完", "m1", voice.playing.value)
        out.done!!()
        assertNull("播完就结束", voice.playing.value)
    }

    @Test
    fun aFailureBeforeAnythingPlaysGivesUp() {
        val out = FakeOut()
        val voice = Voice(now, { _, _ -> throw debating.ground.app.ai.AiFailure("朗读文本无效或太长。") }, out) {}
        voice.toggle("m1", "你好。", "v")
        assertNull(voice.playing.value)
        assertTrue(out.played.isEmpty())
    }

    @Test
    fun listeningSendsThePasswordFirstQueuesAudioUntilReadyAndFillsText() {
        val sent = mutableListOf<JsonObject>()
        var handle: ((Heard) -> Unit)? = null
        var closed = false
        var frame: ((ByteArray) -> Unit)? = null
        var micOn = false
        val listen = Listen(
            now,
            { h ->
                handle = h
                object : ListenLink {
                    override fun send(text: String) {
                        sent += SibianJson.parseToJsonElement(text).jsonObject
                    }

                    override fun close() {
                        closed = true
                    }
                }
            },
            { f ->
                frame = f
                micOn = true
                return@Listen { micOn = false }
            },
        ) { "口令" }
        val texts = mutableListOf<String>()
        var ended: String? = "没结束"
        listen.start(text = { texts += it }, end = { ended = it })
        assertEquals(Phase.Starting, listen.phase.value)
        assertEquals(JsonPrimitive("start"), sent[0]["type"])
        assertEquals("访问口令在第一条消息里", JsonPrimitive("口令"), sent[0]["password"])
        // 后端还没准备好：录到的先攒着
        frame!!(ByteArray(1280) { 1 })
        assertEquals(1, sent.size)
        handle!!(Heard.Ready)
        assertEquals(Phase.Listening, listen.phase.value)
        assertEquals(JsonPrimitive("audio"), sent[1]["type"])
        frame!!(ByteArray(1280) { 2 })
        assertEquals(3, sent.size)
        handle!!(Heard.Text("机会成本是", false))
        // 说完了：先关麦克风，再等最后的结果
        listen.stop()
        assertEquals(Phase.Finishing, listen.phase.value)
        assertTrue(!micOn)
        assertEquals(JsonPrimitive("end"), sent.last()["type"])
        handle!!(Heard.Text("机会成本是放弃的最好选择", true))
        assertEquals(listOf("机会成本是", "机会成本是放弃的最好选择"), texts)
        assertNull(listen.phase.value)
        assertNull("正常结束没有错误", ended)
        assertTrue(closed)
    }

    @Test
    fun anErrorFromTheBackendEndsWithItsMessage() {
        var handle: ((Heard) -> Unit)? = null
        val listen = Listen(
            now,
            { h ->
                handle = h
                object : ListenLink {
                    override fun send(text: String) {}
                    override fun close() {}
                }
            },
            { { } },
        ) { "" }
        var ended: String? = null
        listen.start(text = {}, end = { ended = it })
        handle!!(Heard.Error("后端没有配置讯飞语音听写。"))
        assertEquals("后端没有配置讯飞语音听写。", ended)
        assertNull(listen.phase.value)
    }

    @Test
    fun parsesBackendEvents() {
        assertEquals(Heard.Ready, Heard.parse("""{"type":"ready"}"""))
        assertEquals(Heard.Text("你好", true), Heard.parse("""{"type":"text","text":"你好","final":true}"""))
        assertEquals(Heard.Error("出错了"), Heard.parse("""{"type":"error","message":"出错了"}"""))
        assertNull(Heard.parse("不是 JSON"))
    }
}
