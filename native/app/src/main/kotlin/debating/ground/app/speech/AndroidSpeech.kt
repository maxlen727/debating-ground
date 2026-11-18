package debating.ground.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// 朗读和语音输入在安卓上的实现：播放用 MediaPlayer，录音用 AudioRecord，连接后端用 OkHttp 的 WebSocket。
// 流程逻辑在 Voice、Listen 里，这里只管和系统打交道。

/** 播放内存里的一段音频（讯飞返回的 MP3），不写临时文件。 */
class MediaPlayerOut : AudioOut {
    private var player: MediaPlayer? = null

    override fun play(audio: ByteArray, mime: String, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(BytesSource(audio))
            p.setOnCompletionListener {
                if (player === p) onDone()
            }
            p.setOnErrorListener { _, _, _ ->
                if (player === p) onError()
                true
            }
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
        } catch (e: Exception) {
            onError()
        }
    }

    override fun stop() {
        player?.let {
            player = null
            runCatching { it.reset() }
            it.release()
        }
    }
}

private class BytesSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(size, data.size - position.toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun getSize() = data.size.toLong()

    override fun close() {}
}

/** 麦克风：16k 单声道 16 位，每 40ms 一帧。没有录音权限时抛异常（界面先请求权限再开始）。 */
class AudioRecordMic(private val context: Context) : Mic {
    override fun open(onFrame: (ByteArray) -> Unit): () -> Unit {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("需要麦克风权限才能语音输入。")
        }
        val min = AudioRecord.getMinBufferSize(ListenSpec.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            ListenSpec.RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, ListenSpec.FRAME_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("打不开麦克风，请检查是不是被别的 App 占用了。")
        }
        val running = AtomicBoolean(true)
        rec.startRecording()
        thread(name = "sibian-mic", isDaemon = true) {
            val buf = ByteArray(ListenSpec.FRAME_BYTES)
            while (running.get()) {
                var got = 0
                while (running.get() && got < buf.size) {
                    val n = rec.read(buf, got, buf.size - got)
                    if (n <= 0) break
                    got += n
                }
                if (got == buf.size) onFrame(buf.copyOf())
            }
            runCatching { rec.stop() }
            rec.release()
        }
        return { running.set(false) }
    }
}

private val client by lazy { OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build() }
private val main = Handler(Looper.getMainLooper())

/**
 * 连到后端的 /api/stt。后端只接受带 Origin 的连接（为浏览器设计），原生 App 用 https://localhost：
 * 后端的 ALLOWED_ORIGINS 默认就有它（原来的套壳 App 也是这个来源）。访问口令在第一条消息里，不进地址。
 */
class OkHttpListen(private val url: () -> String) : ListenConnect {
    override fun open(handle: (Heard) -> Unit): ListenLink {
        val ws = url().trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/stt"
        val req = Request.Builder().url(ws).header("Origin", APP_ORIGIN).build()
        val socket = client.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    Heard.parse(text)?.let { e -> main.post { handle(e) } }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { handle(Heard.Error("连不上你的后端的语音输入。请确认后端已更新到最新版本。")) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post { handle(Heard.Error("语音输入的连接断开了。")) }
                }
            },
        )
        return object : ListenLink {
            override fun send(text: String) {
                socket.send(text)
            }

            override fun close() {
                socket.close(1000, null)
            }
        }
    }

    companion object {
        const val APP_ORIGIN = "https://localhost"
    }
}

/** 装配安卓上的朗读和语音输入：都走当前连着的后端，地址和口令每次用时现取（设置里改了马上生效）。 */
fun androidSpeech(context: Context, scope: kotlinx.coroutines.CoroutineScope, l: debating.ground.app.flow.Learning): Speech {
    fun remote() = l.state.config.remote
    val voice = Voice(
        scope,
        { text, name ->
            val (audio, mime) = debating.ground.app.ai.RemoteBackend(remote().url, remote().password).tts(text, name)
            val bytes = runCatching { android.util.Base64.decode(audio, android.util.Base64.DEFAULT) }.getOrNull()
                ?: throw debating.ground.app.ai.AiFailure("后端返回的音频无效，请稍后再试。")
            bytes to mime
        },
        MediaPlayerOut(),
        l::toast,
    )
    return Speech(voice, Listen(scope, OkHttpListen { remote().url }, AudioRecordMic(context)) { remote().password })
}
