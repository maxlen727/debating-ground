package debating.ground.app

import android.content.Context
import debating.ground.app.ai.Ai
import debating.ground.app.ai.DirectAccess
import debating.ground.app.ai.KeystoreDirectStore
import debating.ground.app.ai.SandboxPrompts
import debating.ground.app.data.CARDS_STORE_KEY
import debating.ground.app.data.FileMaterials
import debating.ground.app.data.MaterialStore
import debating.ground.app.data.MemoryMaterials
import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import debating.ground.app.flow.AppState
import debating.ground.app.flow.DirectStatus
import debating.ground.app.flow.Learning
import debating.ground.app.flow.Live
import debating.ground.app.flow.Ui
import debating.ground.app.lan.AndroidLan
import debating.ground.app.lan.LanRooms
import debating.ground.app.room.OkHttpRoomDialer
import debating.ground.app.room.RoomDialer
import debating.ground.app.speech.Heard
import debating.ground.app.speech.ListenConnect
import debating.ground.app.speech.ListenLink
import debating.ground.app.speech.Speech
import debating.ground.app.speech.androidSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

// 界面和学习流程之间的一层：持有状态，状态一变就在后台写存档，启动时读回。
// 流程代码（flow/）不碰界面和存储，界面只读状态、调用这里的操作。
class AppModel(
    dir: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    direct: DirectAccess? = null,
    speech: ((CoroutineScope, Learning) -> Speech)? = null,
    roomDialer: RoomDialer? = null,
    lan: LanRooms? = null,
    /** 读材料：完整的材料存在哪里（测试里在内存）。 */
    materials: MaterialStore = MemoryMaterials(),
) {
    private val storage = Storage(dir)

    // 写存档放在单独的线程：不卡界面，而且一次只写一份，后写的不会被先写的覆盖
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sibian-save").apply { isDaemon = true } }

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState> get() = _state

    private val _live = MutableStateFlow<Live?>(null)

    /** 正在生成的文字：在网络线程上更新，StateFlow 本身是线程安全的。 */
    val live: StateFlow<Live?> get() = _live

    val learning: Learning

    /** 朗读和语音输入（只在真机上有；测试里是 null）。能不能用还要看后端配没配讯飞：learning.voiceReady()。 */
    val speech: Speech?

    init {
        val saved = runCatching { loadState(storage.read(), storage.read(CARDS_STORE_KEY), null, System.currentTimeMillis()) }.getOrElse { SavedState() }
        val initial = AppState(saved.config, saved.plan, saved.session, saved.library, Ui(screen = saved.screen, direct = DirectStatus.of(direct?.settings())))
        _state = MutableStateFlow(initial)
        learning = Learning(
            initial,
            Ai(direct = direct),
            onChange = { st ->
                _state.value = st
                save(st)
            },
            onLive = { _live.value = it },
            roomDialer = roomDialer,
            launch = { block -> scope.launch { block() } },
            lan = lan,
            materialStore = materials,
        )
        this.speech = speech?.invoke(scope, learning)
        wireRoomSpeech()
        // 上次在多人房间里、房间还没关：重连
        learning.rooms.resume()
        // 连着自己的后端：问一下讯飞配好了没有
        if (speech != null) run { refreshRemote() }
    }

    // 语音输入在房间里经房间连接转发，识别结果从房间回来
    private var roomHeard: ((Heard) -> Unit)? = null

    /** 房间里的语音输入：录音帧包成 { type: 'listen' } 发给房间（shared/room.ts）。 */
    val roomListen = ListenConnect { handle ->
        roomHeard = handle
        object : ListenLink {
            override fun send(text: String) {
                val o = runCatching { SibianJson.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return
                val op = (o["type"] as? JsonPrimitive)?.content ?: return
                learning.rooms.listen(op, (o["audio"] as? JsonPrimitive)?.content)
            }

            override fun close() {
                roomHeard = null
            }
        }
    }

    // 房间里的朗读：音频由后端推过来；后端拒绝时按钮恢复原样
    private fun wireRoomSpeech() {
        val sp = speech ?: return
        learning.rooms.onHeard = { e -> roomHeard?.invoke(e) }
        learning.rooms.onAudio = { a ->
            val bytes = runCatching { android.util.Base64.decode(a.audio, android.util.Base64.DEFAULT) }.getOrNull()
            if (bytes != null) sp.voice.push(a.entryId, a.seq, a.last, bytes, a.mime) else sp.voice.failed()
        }
        learning.rooms.onRefused = { sp.voice.failed() }
    }

    // 打字时每个字都会改状态：序列化也放到写存档的线程上，并且只写最新的一份，前面没来得及写的直接跳过
    private val pending = AtomicReference<AppState?>(null)

    private fun save(st: AppState) {
        if (pending.getAndSet(st) != null) return
        writer.execute {
            val latest = pending.getAndSet(null) ?: return@execute
            runCatching { storage.write(SibianJson.encodeToString(SavedState.serializer(), latest.saved())) }
        }
    }

    /** 在界面的协程里跑一个操作（发言、准备……）。 */
    fun run(block: suspend Learning.() -> Unit) {
        scope.launch { learning.block() }
    }

    companion object {
        @Volatile private var instance: AppModel? = null

        // 整个 App 只有一份：转屏、深色模式切换时 Activity 会重建，状态和正在进行的请求不能丢

        /** 已经读好的那一份（转屏、深色模式切换时 Activity 重建，直接用）。 */
        fun loaded(): AppModel? = instance

        fun get(context: Context): AppModel = instance ?: synchronized(this) {
            // 直接连接的设置加密存在不进备份的目录里；提示词在 JS 沙箱里组装
            val directAccess = DirectAccess(KeystoreDirectStore(context.noBackupFilesDir), SandboxPrompts(context.applicationContext))
            instance ?: AppModel(
                context.filesDir,
                direct = directAccess,
                speech = { scope, l -> androidSpeech(context.applicationContext, scope, l) },
                roomDialer = OkHttpRoomDialer(),
                lan = AndroidLan(context.applicationContext, directAccess),
                // 材料可能很长，也可能是不该外传的内容：放在不进系统备份的目录（#154）
                materials = FileMaterials(File(context.noBackupFilesDir, "materials")),
            ).also { instance = it }
        }
    }
}
