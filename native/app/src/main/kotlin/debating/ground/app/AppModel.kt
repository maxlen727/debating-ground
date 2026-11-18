package debating.ground.app

import android.content.Context
import debating.ground.app.ai.Ai
import debating.ground.app.ai.DirectAccess
import debating.ground.app.ai.KeystoreDirectStore
import debating.ground.app.ai.SandboxPrompts
import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import debating.ground.app.flow.AppState
import debating.ground.app.flow.DirectStatus
import debating.ground.app.flow.Learning
import debating.ground.app.flow.Live
import debating.ground.app.flow.Ui
import debating.ground.app.speech.Speech
import debating.ground.app.speech.androidSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
        val saved = runCatching { loadState(storage.read(), null, System.currentTimeMillis()) }.getOrElse { SavedState() }
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
        )
        this.speech = speech?.invoke(scope, learning)
        // 连着自己的后端：问一下讯飞配好了没有
        if (speech != null) run { refreshRemote() }
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
            instance ?: AppModel(
                context.filesDir,
                // 直接连接的设置加密存在不进备份的目录里；提示词在 JS 沙箱里组装
                direct = DirectAccess(KeystoreDirectStore(context.noBackupFilesDir), SandboxPrompts(context.applicationContext)),
                speech = { scope, l -> androidSpeech(context.applicationContext, scope, l) },
            ).also { instance = it }
        }
    }
}
