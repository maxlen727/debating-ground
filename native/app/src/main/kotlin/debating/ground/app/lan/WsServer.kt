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

// 局域网房间（#148）用的最小服务端，和电脑上的 createLanServer（server/server.ts）做的事一样：
// 房间连接是 GET /api/rooms/<id>/ws 的 WebSocket 升级（RFC 6455），只收文本帧、关闭、ping，消息最大 64 KB；
// 其余的 GET 给网页版界面（APK 里打包的 dist/，#151），没装 App 的同学用浏览器打开邀请链接就能加入；
// /api/ 下的别的地址一律 404，不提供别的接口。不加依赖（Ktor、NanoHTTPD 这些都比要用的大得多），每个连接一个线程。

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

// 这个包里没有打包网页版时（本地没先 npm run build）退回这一页
private const val INFO_PAGE =
    "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>思辨场局域网房间</title>" +
        "<p style=\"font:17px/1.7 system-ui;margin:24px\">这是思辨场的局域网房间，开在同学的手机上。<br>" +
        "打开思辨场 App，在学习页点“加入同学的讨论”，把这个链接贴进去就能加入。</p>"

private val TYPES = mapOf(
    "html" to "text/html; charset=utf-8",
    "js" to "text/javascript; charset=utf-8",
    "css" to "text/css; charset=utf-8",
    "json" to "application/json; charset=utf-8",
    "svg" to "image/svg+xml",
    "png" to "image/png",
    "webp" to "image/webp",
    "ico" to "image/x-icon",
    "woff2" to "font/woff2",
    "webmanifest" to "application/manifest+json",
)

/** 请求路径对应的界面文件名（相对 dist/）；不是能给出去的文件就是 null。 */
internal fun staticName(target: String): String? {
    val path = target.substringBefore('?').substringBefore('#')
    if (!path.startsWith("/") || path.startsWith("/api/")) return null
    val name = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
    if (name == "/") return "index.html"
    val rel = name.removePrefix("/")
    // 只认普通的文件名：不许 ..、反斜杠、空段，免得读到 assets/web 以外的东西
    val ok = rel.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.none { c -> c == '\\' || c.code < 0x20 } }
    return rel.takeIf { ok }
}

private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val MAX_MESSAGE = 64 * 1024
private const val MAX_HEADER = 8 * 1024

/** [route] 按请求路径给出房间连接的处理者，返回 null 时回 404；[files] 按文件名（相对 dist/）给出网页版界面的文件。 */
class WsServer(private val route: (path: String) -> WsHandler?, private val files: (name: String) -> ByteArray? = { null }) {
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
                val get = lines.first().startsWith("GET ") || lines.first().startsWith("HEAD ")
                respond(out, if (get) staticName(path) else null, head = lines.first().startsWith("HEAD "))
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

    // 网页版界面：带哈希的 assets/ 永久缓存，其余每次都问；文件不在包里时首页退回说明页
    private fun respond(out: OutputStream, name: String?, head: Boolean) {
        var body = name?.let(files)
        var type = name?.substringAfterLast('.', "")?.let { TYPES[it] } ?: "application/octet-stream"
        if (body == null && name == "index.html") {
            body = INFO_PAGE.toByteArray(Charsets.UTF_8)
            type = TYPES.getValue("html")
        }
        if (body == null) {
            out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val cache = if (name!!.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache"
        out.write(
            (
                "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: $cache\r\n" +
                    "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
        )
        if (!head) out.write(body)
        out.flush()
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
