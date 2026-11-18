package debating.ground.app.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 播放一段音频。播完或失败时回调一次（在主线程）。 */
interface AudioOut {
    fun play(audio: ByteArray, mime: String, onDone: () -> Unit, onError: () -> Unit)
    fun stop()
}

/** 合成一段文字：返回音频和它的类型。失败时抛异常，消息给用户看。 */
fun interface Synthesize {
    suspend fun run(text: String, voice: String): Pair<ByteArray, String>
}

/**
 * 朗读（网页版 src/lib/voice.ts）：同一时间只播一条发言，再点同一条就停。
 * 按句子分段请求合成，收到第一段就开始播，按顺序接着播；播当前段时就去取下一段。
 * 请求失败：还没开始播就整条放弃，播到一半就播完已经收到的部分为止。
 */
class Voice(private val scope: CoroutineScope, private val synth: Synthesize, private val out: AudioOut, private val toast: (String) -> Unit) {
    private val _playing = MutableStateFlow<String?>(null)

    /** 正在朗读哪条发言。 */
    val playing: StateFlow<String?> get() = _playing

    private var job: Job? = null
    private val ready = ArrayDeque<Pair<ByteArray, String>>()
    private var sounding = false
    private var allFetched = false

    fun toggle(id: String, text: String, voice: String) {
        if (_playing.value == id) return stop()
        stop()
        val parts = splitForSpeech(text)
        if (parts.isEmpty()) return
        _playing.value = id
        allFetched = false
        job = scope.launch {
            try {
                for (part in parts) {
                    val audio = synth.run(part, voice)
                    if (_playing.value != id) return@launch
                    ready.addLast(audio)
                    pump(id)
                }
                allFetched = true
                pump(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_playing.value != id) return@launch
                toast(e.message?.takeIf { it.isNotEmpty() } ?: "朗读失败，请稍后再试。")
                // 已经在播：播完收到的部分；还没开始：整条放弃
                allFetched = true
                if (!sounding && ready.isEmpty()) stop() else pump(id)
            }
        }
    }

    private fun pump(id: String) {
        if (_playing.value != id || sounding) return
        val next = ready.removeFirstOrNull()
        if (next == null) {
            if (allFetched) stop()
            return
        }
        sounding = true
        out.play(next.first, next.second, onDone = {
            sounding = false
            pump(id)
        }, onError = {
            sounding = false
            if (_playing.value == id) {
                toast("这段音频播放失败，请再点一次朗读。")
                stop()
            }
        })
    }

    fun stop() {
        job?.cancel()
        job = null
        ready.clear()
        sounding = false
        out.stop()
        _playing.value = null
    }
}
