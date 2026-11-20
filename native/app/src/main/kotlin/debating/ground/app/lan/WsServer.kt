package debating.ground.app.lan

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// 局域网房间（#148）用的最小 WebSocket 服务端（RFC 6455），和电脑上 server/wsserver.ts 做的事一样：
// 只认 GET /api/rooms/<id>/ws 的升级请求，只收文本帧、关闭、ping，消息最大 64 KB。不提供别的接口。
// 不加依赖（Ktor、NanoHTTPD 这些都比要用的大得多），每个连接一个线程。

/** 一条连接：发文字、关掉。 */
interface WsConn {
    fun send(text: String)

    fun close()
}

/** 一条连接上发生的事。 */
interface WsHandler {
    fun onOpen(conn: WsConn)

    fun onMessage(text: String)

    fun onClose()
}

private const val INFO_PAGE =
    "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>思辨场局域网房间</title>" +
        "<p style=\"font:17px/1.7 system-ui;margin:24px\">这是思辨场的局域网房间，开在同学的手机上。<br>" +
        "打开思辨场 App，在学习页点“加入同学的讨论”，把这个链接贴进去就能加入。</p>"

private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val MAX_MESSAGE = 64 * 1024
private const val MAX_HEADER = 8 * 1024

/** [route] 按请求路径给出处理者；返回 null 时回 404。 */
class WsServer(private val route: (path: String) -> WsHandler?) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "sibian-lan").apply { isDaemon = true } }

    val port get() = server?.localPort ?: -1

    /** 开始监听。[bind] 是 null 时监听所有网卡（局域网），测试里传回环地址。 */
    fun start(port: Int, bind: InetAddress? = null): Int {
        val s = ServerSocket(port, 50, bind)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { serve(socket) }
            }
        }
        return s.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        socket.use {
            socket.soTimeout = 0
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val path = lines.first().split(" ").getOrNull(1).orEmpty()
            val headers = lines.drop(1).mapNotNull { l ->
                l.indexOf(':').takeIf { it > 0 }?.let {
                    l.substring(0, it).trim().lowercase() to
                        l.substring(it + 1).trim()
                }
            }.toMap()
            val key = headers["sec-websocket-key"]
            val handler = if (lines.first().startsWith("GET ") && headers["upgrade"]?.lowercase() == "websocket" && key != null) route(path) else null
            if (handler == null) {
                // 有人在浏览器里打开了邀请链接：告诉他用 App 加入（手机上不提供网页版）
                if (lines.first().startsWith("GET / ")) {
                    val page = INFO_PAGE.toByteArray(Charsets.UTF_8)
                    out.write(
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    out.write(page)
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
                return
            }
            val accept = base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            out.flush()
            val conn = Conn(socket, out)
            handler.onOpen(conn)
            try {
                frames(input, conn, handler)
            } finally {
                conn.closed.set(true)
                handler.onClose()
            }
        }
    }

    // 请求头读到空行为止；太长就不要了
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) return buf.toString().trimEnd()
        }
        return null
    }

    private fun frames(input: InputStream, conn: Conn, handler: WsHandler) {
        val pieces = java.io.ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) return
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            // 客户端发来的帧必须带掩码
            if (b1 and 0x80 == 0) return
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or input.read().toLong() }
            }
            if (len < 0 || len > MAX_MESSAGE) return
            val mask = ByteArray(4).also { if (input.readNBytesCompat(it) < 4) return }
            val payload = ByteArray(len.toInt()).also { if (input.readNBytesCompat(it) < it.size) return }
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (opcode) {
                0x8 -> {
                    conn.frame(0x8, ByteArray(0))
                    return
                }

                0x9 -> conn.frame(0xA, payload)

                0xA -> Unit

                0x1, 0x0 -> {
                    pieces.write(payload)
                    if (pieces.size() > MAX_MESSAGE) return
                    if (fin) {
                        val text = pieces.toString(Charsets.UTF_8.name())
                        pieces.reset()
                        handler.onMessage(text)
                    }
                }

                else -> return
            }
        }
    }

    private class Conn(private val socket: Socket, private val out: OutputStream) : WsConn {
        val closed = AtomicBoolean(false)

        fun frame(opcode: Int, payload: ByteArray) {
            if (closed.get()) return
            val head = java.io.ByteArrayOutputStream()
            head.write(0x80 or opcode)
            when {
                payload.size < 126 -> head.write(payload.size)

                payload.size < 65536 -> {
                    head.write(126)
                    head.write(payload.size shr 8)
                    head.write(payload.size and 0xff)
                }

                else -> {
                    head.write(127)
                    for (i in 7 downTo 0) head.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt())
                }
            }
            synchronized(this) {
                runCatching {
                    out.write(head.toByteArray())
                    out.write(payload)
                    out.flush()
                }
            }
        }

        override fun send(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

        override fun close() {
            frame(0x8, byteArrayOf(0x03, 0xe8.toByte()))
            closed.set(true)
            runCatching { socket.close() }
        }
    }
}

// InputStream.readNBytes 要 Android 13；自己读满
private fun InputStream.readNBytesCompat(b: ByteArray): Int {
    var n = 0
    while (n < b.size) {
        val r = read(b, n, b.size - n)
        if (r < 0) break
        n += r
    }
    return n
}

// java.util.Base64 要 Android 8；握手只需要编码 20 个字节
private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

internal fun base64(b: ByteArray): String = buildString {
    var i = 0
    while (i < b.size) {
        val n = ((b[i].toInt() and 0xff) shl 16) or ((b.getOrElse(i + 1) { 0 }.toInt() and 0xff) shl 8) or (b.getOrElse(i + 2) { 0 }.toInt() and 0xff)
        append(B64[(n shr 18) and 63])
        append(B64[(n shr 12) and 63])
        append(if (i + 1 < b.size) B64[(n shr 6) and 63] else '=')
        append(if (i + 2 < b.size) B64[n and 63] else '=')
        i += 3
    }
}
