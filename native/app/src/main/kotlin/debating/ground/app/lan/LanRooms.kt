package debating.ground.app.lan

import android.content.Context
import debating.ground.app.ai.AiFailure
import debating.ground.app.ai.DirectAccess
import debating.ground.app.room.CreatedRoom
import debating.ground.app.room.RoomSetup
import debating.ground.app.room.isPrivateIPv4
import java.net.Inet4Address
import java.net.NetworkInterface

/** 学习流程用到的“手机当局域网房主”（测试里可以换成假的，或者没有）。 */
interface LanRooms {
    /** 这台手机在局域网里的地址（没连 Wi-Fi 时是 null）。 */
    fun address(): String?

    suspend fun create(roomId: String, setup: RoomSetup, hostName: String): CreatedRoom
}

/** 网卡里第一个开着的、局域网（私有）IPv4 地址；Wi-Fi（wlan）优先。不需要额外的权限。 */
fun lanAddress(interfaces: List<NetworkInterface> = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())): String? =
    interfaces
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .map { it.hostAddress.orEmpty() }
        .firstOrNull { isPrivateIPv4(it) }

/** 安卓上的实现：房间规则在 JS 引擎里跑，AI 用房主的“直接连接”。第一次开房间时才启动。 */
class AndroidLan(private val context: Context, private val direct: DirectAccess) : LanRooms {
    private val host by lazy {
        LanHost(SandboxRoomEngine(context), generate = { purpose, payload ->
            val backend = direct.backend() ?: throw AiFailure("房主的直接连接没有设置好，请在“设置”里重新连接。")
            backend.generate(purpose, payload, null)
        })
    }

    override fun address(): String? = lanAddress()

    override suspend fun create(roomId: String, setup: RoomSetup, hostName: String): CreatedRoom = host.create(roomId, setup, hostName)
}

/** 房间号：16 个随机字节，base64url（22 个字符），和 Worker 的 randomToken 一样。 */
fun randomRoomId(random: java.security.SecureRandom = java.security.SecureRandom()): String =
    base64(ByteArray(16).also { random.nextBytes(it) }).trimEnd('=').replace('+', '-').replace('/', '_')
