package debating.ground.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import debating.ground.app.ui.AppScaffold
import debating.ground.app.ui.LocalMotion
import debating.ground.app.ui.theme.SibianTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// 没有模拟器时检查界面的办法：在 JVM 里把页面画出来、存成图片，人工看一遍（AGENTS.md：手机宽度、浅色和深色）。
// 同时确认页面能渲染、关键文字在。图片在 app/build/screenshots/，CI 会上传。
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val tmp = org.junit.rules.TemporaryFolder()

    // 每个测试一份新的状态（存档写在临时目录里）。朗读和语音输入用假的播放器、麦克风和连接
    // 存档目录：要预先放好知识库的测试，在用到 model 之前往这里写存档
    private val dir by lazy { tmp.newFolder() }

    private val model by lazy {
        debating.ground.app.AppModel(dir, speech = { scope, _ ->
            val out = object : debating.ground.app.speech.AudioOut {
                override fun play(audio: ByteArray, mime: String, onDone: () -> Unit, onError: () -> Unit) {}
                override fun stop() {}
            }
            debating.ground.app.speech.Speech(
                debating.ground.app.speech.Voice(scope, { _, _ -> ByteArray(0) to "audio/mpeg" }, out) {},
                debating.ground.app.speech.Listen(scope, { error("截图里不连") }, { error("截图里不录") }) { "" },
            )
        }, roomDialer = fakeRoom)
    }

    // 多人房间：假的连接，测试里直接模拟后端发来的消息
    private val fakeRoom = object : debating.ground.app.room.RoomDialer {
        var events: debating.ground.app.room.RoomEvents? = null

        override fun dial(url: String, events: debating.ground.app.room.RoomEvents): debating.ground.app.room.RoomSocket {
            this.events = events
            return object : debating.ground.app.room.RoomSocket {
                override fun send(text: String) = true
                override fun close() {}
            }
        }
    }

    private fun roomView(mode: String, status: String, me: String, host: Boolean) = """
        {"id":"${"r".repeat(22)}","status":"$status","setup":{"mode":"$mode","concept":"机会成本","topic":"周末去打工赚 300 元，真正的代价是什么？",
        "keyPoints":["放弃的最佳选择","包含隐性代价","区别于沉没成本"],"personas":[{"id":"p1","name":"苏珩","title":"追问者","tone":"a"}],"opening":"",
        "seats":[{"id":"A1","team":"A","index":0,"type":"human","name":"正方 1 辩"},{"id":"B1","team":"B","index":0,"type":"ai","name":"反方 1 辩"},{"id":"A2","team":"A","index":1,"type":"peer","name":"正方 2 辩"},{"id":"B2","team":"B","index":1,"type":"ai","name":"反方 2 辩"}],
        "stages":[{"name":"开篇立论","kind":"opening","rounds":1}],
        "turns":[{"role":{"id":"A1","team":"A","index":0,"type":"human"},"action":"立论","stage":0,"round":0},{"role":{"id":"B1","team":"B","index":0,"type":"ai"},"action":"立论","stage":0,"round":0}],"turnSeconds":60},
        "members":[{"id":"host","name":"小明","host":true,"online":true,"seat":"A1"},{"id":"u2","name":"小红","host":false,"online":true,"typing":true},{"id":"u3","name":"阿杰","host":false,"online":false}],
        "entries":[{"id":"s0","kind":"sys","text":"讨论题：周末去打工赚 300 元，真正的代价是什么？"},
        {"id":"m1","kind":"msg","who":"learner","name":"小明","text":"我觉得代价就是那一天本来可以休息。","source":"human","memberId":"host"},
        {"id":"m2","kind":"msg","who":"learner","name":"小红","text":"还有本来可以复习的时间，考试可能会因此少几分。","source":"human","memberId":"u2"},
        {"id":"m3","kind":"msg","who":"p1","name":"苏珩","text":"你们都提到了时间。那如果那天你本来可以去做家教赚 500 元，打工的代价又是什么？","source":"api","model":"m"}],
        "covered":[0],"cursor":0,"speaker":null,"aiBusy":null,"error":"","aiCalls":3,"expiresAt":${System.currentTimeMillis() + 3_600_000},"now":0,"turnStartedAt":0}
    """.trimIndent().let {
        if (status ==
            "lobby"
        ) {
            it.replace("\"entries\":[", "\"entries\":[{\"id\":\"s0\",\"kind\":\"sys\",\"text\":\"讨论题\"}],\"x\":[")
        } else {
            it
        }
    }

    private fun enterRoom(view: String, me: String) {
        model.learning.rooms.openJoin(debating.ground.app.room.inviteLink("https://api.example.dev", "r".repeat(22), "inv"))
        model.learning.rooms.join("小明")
        fakeRoom.events!!.opened()
        fakeRoom.events!!.message("""{"type":"welcome","memberId":"$me","token":"t","room":$view}""")
        compose.waitForIdle()
    }

    @Test
    fun roomRoundtableLive() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        enterRoom(roomView("roundtable", "live", "host", true), "host")
        compose.onNodeWithText("想说就说，大家停下来几秒后 AI 嘉宾会接话").assertExists()
        save("phone-room-live")
    }

    @Test
    fun roomDebateLobbyDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        enterRoom(roomView("debate", "lobby", "u3", false), "u3")
        compose.onNodeWithText("等房主开始讨论……").assertExists()
        save("phone-room-lobby-dark")
    }

    @Test
    fun joinPage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.rooms.openJoin(debating.ground.app.room.inviteLink("https://api.example.dev", "r".repeat(22), "inv"))
        compose.waitForIdle()
        compose.onNodeWithText("同学邀请你一起讨论").assertExists()
        save("phone-join")
    }

    private fun shoot(name: String, dark: Boolean, width: Int = 390, height: Int = 844) {
        compose.setContent {
            Frame(dark, width, height)
        }
        compose.onNodeWithText("今天想弄懂\n什么？").assertIsDisplayed()
        save(name)
    }

    // 截图时背景不漂：画面固定，测试也不会因为一直有动画而等不到空闲
    @Composable
    private fun Frame(dark: Boolean, width: Int, height: Int) {
        CompositionLocalProvider(LocalMotion provides false) {
            SibianTheme(dark = dark) { Box(Modifier.requiredSize(width.dp, height.dp)) { AppScaffold(model) } }
        }
    }

    // 等流程走完：AI 调用在后台线程，结果投递回主线程。Robolectric 下 waitUntil 不处理这些投递，
    // 所以每次先让真实时间过去一点，再让界面空闲一次
    private fun awaitState(timeoutMs: Long = 5_000, done: (debating.ground.app.flow.AppState) -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done(model.state.value)) {
            check(System.currentTimeMillis() < until) { "等了 ${timeoutMs}ms，状态还是 ${model.state.value.ui}" }
            Thread.sleep(30)
            compose.waitForIdle()
        }
        compose.waitForIdle()
    }

    private fun save(name: String) {
        val dir = File(System.getProperty("screenshots.dir")!!).apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun phoneLight() = shoot("phone-light", dark = false)

    // 还没写概念：底部不摆点不了的按钮
    @Test
    fun learnEmpty() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.setConcept("")
        compose.waitForIdle()
        compose.onNodeWithText("开始讨论").assertDoesNotExist()
        save("phone-learn-empty")
    }

    @Test fun phoneDark() = shoot("phone-dark", dark = true)

    @Config(qualifiers = "w320dp-h568dp-xxhdpi")
    @Test
    fun narrowestPhone() = shoot("phone-320", dark = false, width = 320, height = 568)

    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun tabletUsesTheRail() = shoot("tablet-light", dark = false, width = 1280, height = 800)

    // 横着拿的手机：左侧导航，内容铺满右边
    @Config(qualifiers = "w844dp-h390dp-xxhdpi")
    @Test
    fun landscapePhone() = shoot("phone-landscape", dark = false, width = 844, height = 390)

    @Test
    fun tabsSwitchPages() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("今天想弄懂\n什么？").assertIsDisplayed()
        compose.onNodeWithText("知识库").performClick()
        compose.onNodeWithText("知识库还是空的").assertIsDisplayed()
        save("phone-library")
    }

    // 演示模式下准备一场讨论，停在准备页
    @Test
    fun preparePage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("先看题目").performClick()
        awaitState { it.ui.screen == "prepare" }
        compose.onNodeWithText("要弄懂的要点").assertIsDisplayed()
        save("phone-prepare")
    }

    @Test
    fun preparePageDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        model.learning.setMode("roundtable")
        compose.onNodeWithText("先看题目").performClick()
        awaitState { it.ui.screen == "prepare" }
        compose.onNodeWithText("圆桌嘉宾").assertExists()
        save("phone-prepare-roundtable-dark")
    }

    // 演示模式下开始一场讨论，说几句话
    private fun startAndTalk(lines: List<String>) {
        compose.onNodeWithText("开始讨论").performClick()
        awaitState { it.session != null && it.ui.busy == null && it.ui.screen == "session" }
        for (line in lines) {
            val before = model.state.value.session!!.entries.size
            model.run { send(line) }
            awaitState { it.ui.busy == null && it.session!!.entries.size >= before + 2 }
        }
    }

    @Test
    fun discussPage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        startAndTalk(listOf("机会成本就是做一件事放弃的另一件事最好的价值。", "比如我周末去打工，就放弃了在家复习的收获。"))
        compose.onNodeWithText("说说你的想法……").assertIsDisplayed()
        save("phone-discuss")
    }

    // 第三次发言后出现小结卡
    // 后端配好了讯飞：发言旁有“朗读”，输入框旁有麦克风
    @Test
    fun discussWithVoice() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/config") { ex ->
            val body = """{"model":"m","ttsConfigured":true,"sttConfigured":true}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
            ex.close()
        }
        server.start()
        try {
            compose.setContent { Frame(dark = false, width = 390, height = 844) }
            startAndTalk(listOf("机会成本就是放弃的另一件事最好的价值。"))
            model.learning.editConfig { it.copy(aiMode = "remote", remote = debating.ground.app.data.Remote("http://127.0.0.1:${server.address.port}", "pw")) }
            model.run { refreshRemote() }
            awaitState { it.ui.remote.tts && it.ui.remote.stt }
            compose.onNodeWithContentDescription("语音输入").assertIsDisplayed()
            save("phone-discuss-voice")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun discussReflectionDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        startAndTalk(listOf("机会成本是放弃的东西。", "是放弃的选项里最好的那个。", "不只是钱，时间也算。"))
        compose.onNodeWithText("停一下，用自己的话说说").assertExists()
        model.learning.setReflectionDraft(model.state.value.session!!.entries.last().id, "机会成本是选了一样以后，放弃的那些选项里最值钱的一个。")
        compose.waitForIdle()
        save("phone-discuss-reflect-dark")
    }

    // 圆桌：点一条发言，打开追问面板
    @Test
    fun discussQuickSheet() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.setMode("roundtable")
        startAndTalk(listOf("我觉得机会成本只在花钱的时候才有。"))
        val last = model.state.value.session!!.entries.last { it is debating.ground.app.data.ChatMessage && it.human != true }
        model.learning.openSheet(last.id)
        compose.waitForIdle()
        compose.onNodeWithText("追问这句").assertIsDisplayed()
        save("phone-discuss-quick")
    }

    // 平板：讨论页铺满导航右边（讨论页没有导航）
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    @Test
    fun discussTablet() {
        compose.setContent { Frame(dark = false, width = 1280, height = 800) }
        startAndTalk(listOf("机会成本就是做一件事放弃的另一件事最好的价值。"))
        save("tablet-discuss")
    }

    // 知识画布：从 AI 的话里展开一个分支，在分支里聊一句，打开分支图
    @Test
    fun canvasBranchAndGraph() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.setMode("canvas")
        startAndTalk(listOf("少玩了一天"))
        val from = model.state.value.session!!.entries.last { it is debating.ground.app.data.ChatMessage && it.human != true }.id
        model.run { openBranch(from, "稀缺性") }
        awaitState { it.ui.busy == null && it.session!!.at != null && it.session!!.entries.size > 4 }
        compose.onNodeWithText("带回主干").assertExists()
        save("phone-canvas-branch")
        model.learning.openSheet("tree")
        compose.waitForIdle()
        compose.onNodeWithText("分支图").assertIsDisplayed()
        save("phone-canvas-graph")
    }

    // 辩论：台面、赛程条和下一位的空讲台
    @Test
    fun debateBoardDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        model.learning.setMode("debate")
        compose.onNodeWithText("开始讨论").performClick()
        awaitState { it.session != null && it.ui.busy == null && it.ui.screen == "session" }
        model.run { send("我方认为，上大学最大的成本不是学费，而是放弃的四年工资和工作经验。") }
        awaitState { it.ui.busy == null && it.session!!.cursor >= 1 }
        model.run { generateSeat() }
        awaitState { it.ui.busy == null && it.session!!.cursor >= 2 }
        save("phone-debate-dark")
    }

    // 辩论的准备页：席位人数和比赛流程
    @Test
    fun prepareDebate() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        model.learning.setMode("debate")
        compose.onNodeWithText("先看题目").performClick()
        awaitState { it.ui.screen == "prepare" && it.ui.busy == null }
        compose.onNodeWithText("共 ", substring = true).performScrollTo()
        save("phone-prepare-debate")
    }

    // 讨论两句后结束：复盘页
    private fun finishOne(lines: List<String>) {
        startAndTalk(lines)
        model.run { finish() }
        awaitState { it.ui.busy == null && it.session!!.review != null }
    }

    @Test
    fun reviewPage() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        finishOne(listOf("机会成本就是做一件事放弃的另一件事最好的价值。", "比如我周末去打工，就放弃了在家复习的收获。"))
        compose.onNodeWithText("要点掌握情况").assertIsDisplayed()
        save("phone-review")
    }

    // 知识库搜索：6 张卡片，搜“成本”找到概念名里带它的两张
    @Test
    fun librarySearch() {
        val saved = File(javaClass.classLoader!!.getResource("fixtures/rich-library.saved.json")!!.toURI()).readText()
        val state = debating.ground.app.data.SibianJson.parseToJsonElement(saved) as kotlinx.serialization.json.JsonObject
        val card = state.getValue("library").let { it as kotlinx.serialization.json.JsonArray }[0] as kotlinx.serialization.json.JsonObject
        val now = System.currentTimeMillis()
        val concepts = listOf(
            "机会成本" to "放弃的选项里最好的那个的价值",
            "沉没成本" to "已经花掉、收不回来的成本，不该左右以后的决定",
            "边际效用" to "多得到一份时新增的满足",
            "贝叶斯定理" to "按新证据有多意外来更新判断",
            "认知失调" to "想法和行为对不上时改想法求心安",
            "比较优势" to "做相对代价最低的事",
        )
        val cards = concepts.mapIndexed { i, (c, o) ->
            kotlinx.serialization.json.JsonObject(
                card + mapOf(
                    "id" to kotlinx.serialization.json.JsonPrimitive("c$i"),
                    "sessionId" to kotlinx.serialization.json.JsonPrimitive("s$i"),
                    "concept" to kotlinx.serialization.json.JsonPrimitive(c),
                    "oneLine" to kotlinx.serialization.json.JsonPrimitive(o),
                    "due" to kotlinx.serialization.json.JsonPrimitive(now + (i - 1) * 86_400_000L),
                ),
            )
        }
        val seeded = kotlinx.serialization.json.JsonObject(
            state +
                mapOf(
                    "library" to kotlinx.serialization.json.JsonArray(cards),
                    "session" to kotlinx.serialization.json.JsonNull,
                    "screen" to kotlinx.serialization.json.JsonPrimitive("library"),
                ),
        )
        debating.ground.app.data.Storage(dir).write(seeded.toString())
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("知识库").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("搜索知识卡片").performTextInput("成本")
        compose.waitForIdle()
        save("phone-library-search")
        compose.onNodeWithText("找到 2 张卡片").assertExists()
    }

    // 知识库和卡片（深色）：做一道检验题
    @Test
    fun libraryAndCardDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        finishOne(listOf("机会成本就是做一件事放弃的另一件事最好的价值。"))
        model.learning.go("library")
        compose.waitForIdle()
        compose.onNodeWithText("全部 1").performClick()
        save("phone-library-dark")
        val card = model.state.value.library.first()
        model.learning.openCard(card.id)
        if (card.quiz.isNotEmpty()) model.learning.pickAnswer("c:${card.id}", 0, (card.quiz[0].answer + 1) % card.quiz[0].options.size)
        compose.waitForIdle()
        compose.onNodeWithText("这次回顾得怎么样？").assertIsDisplayed()
        save("phone-card-dark")
    }

    @Test
    fun settingsRemote() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("我的后端").performClick()
        compose.onNodeWithText("连接并测试").assertExists()
        save("phone-settings")
    }

    @Test
    fun settingsDirectDark() {
        compose.setContent { Frame(dark = true, width = 390, height = 844) }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("直接连接").performClick()
        compose.onNodeWithText("连接 AI 服务商").assertExists()
        save("phone-settings-direct-dark")
    }

    // 往下滚时底部胶囊收起成当前标签的小圆，点一下展开
    @Test
    fun navCollapsesOnScroll() {
        compose.setContent { Frame(dark = false, width = 390, height = 844) }
        compose.onNodeWithText("怎么讨论").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithText("知识库").assertDoesNotExist()
        save("phone-nav-collapsed")
        compose.onNodeWithContentDescription("学习，展开导航").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("知识库").assertExists()
    }
}
