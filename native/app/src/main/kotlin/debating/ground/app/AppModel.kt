package debating.ground.app

import debating.ground.app.ai.Ai
import debating.ground.app.data.SavedState
import debating.ground.app.data.SibianJson
import debating.ground.app.data.Storage
import debating.ground.app.data.loadState
import debating.ground.app.flow.AppState
import debating.ground.app.flow.Learning
import debating.ground.app.flow.Live
import debating.ground.app.flow.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

// 界面和学习流程之间的一层：持有状态，状态一变就在后台写存档，启动时读回。
// 流程代码（flow/）不碰界面和存储，界面只读状态、调用这里的操作。
class AppModel(dir: File, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)) {
    private val storage = Storage(dir)

    // 写存档放在单独的线程：不卡界面，而且一次只写一份，后写的不会被先写的覆盖
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "sibian-save").apply { isDaemon = true } }

    private val _state: MutableStateFlow<AppState>
    val state: StateFlow<AppState> get() = _state

    private val _live = MutableStateFlow<Live?>(null)

    /** 正在生成的文字：在网络线程上更新，StateFlow 本身是线程安全的。 */
    val live: StateFlow<Live?> get() = _live

    val learning: Learning

    init {
        val saved = runCatching { loadState(storage.read(), null, System.currentTimeMillis()) }.getOrElse { SavedState() }
        val initial = AppState(saved.config, saved.plan, saved.session, saved.library, Ui(screen = saved.screen))
        _state = MutableStateFlow(initial)
        learning = Learning(
            initial,
            Ai(),
            onChange = { st ->
                _state.value = st
                save(st)
            },
            onLive = { _live.value = it },
        )
    }

    private fun save(st: AppState) {
        val text = SibianJson.encodeToString(SavedState.serializer(), st.saved())
        writer.execute { runCatching { storage.write(text) } }
    }

    /** 在界面的协程里跑一个操作（发言、准备……）。 */
    fun run(block: suspend Learning.() -> Unit) {
        scope.launch { learning.block() }
    }

    companion object {
        @Volatile private var instance: AppModel? = null

        // 整个 App 只有一份：转屏、深色模式切换时 Activity 会重建，状态和正在进行的请求不能丢
        fun get(dir: File): AppModel = instance ?: synchronized(this) { instance ?: AppModel(dir).also { instance = it } }
    }
}
