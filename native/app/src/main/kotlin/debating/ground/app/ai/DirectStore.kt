package debating.ground.app.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import debating.ground.app.data.SibianJson
import kotlinx.serialization.Serializable
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 直接连接的设置。[key] 是 API Key：只在内存和加密文件里，不进存档、导出、日志和错误信息。 */
@Serializable
data class DirectSettings(val baseURL: String, val model: String, val key: String) {
    // 万一被打印出来（调试、崩溃信息），也不带出密钥
    override fun toString() = "DirectSettings(baseURL=$baseURL, model=$model, key=***)"
}

interface DirectStore {
    fun load(): DirectSettings?

    fun save(settings: DirectSettings)

    fun clear()
}

/** 测试用：只在内存里。 */
class MemoryDirectStore(private var value: DirectSettings? = null) : DirectStore {
    override fun load() = value

    override fun save(settings: DirectSettings) {
        value = settings
    }

    override fun clear() {
        value = null
    }
}

/**
 * 用 Android Keystore 里的 AES 密钥（不能导出，卸载 App 就没了）加密保存，文件放在 noBackupFilesDir：
 * 不进系统备份、不随知识库迁移。读不出来（密钥被系统清掉、文件坏了）就当作没设置，请用户重新填，不报错崩溃。
 */
class KeystoreDirectStore(private val dir: File) : DirectStore {
    private val file get() = File(dir, "direct.bin")

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun load(): DirectSettings? = runCatching {
        val bytes = file.takeIf { it.exists() }?.readBytes() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV)))
        SibianJson.decodeFromString(DirectSettings.serializer(), cipher.doFinal(bytes, IV, bytes.size - IV).decodeToString())
    }.getOrNull()

    override fun save(settings: DirectSettings) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(SibianJson.encodeToString(DirectSettings.serializer(), settings).encodeToByteArray())
        // 先写临时文件再改名：写到一半断电也不会留下半截文件
        dir.mkdirs()
        val tmp = File(dir, "direct.bin.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override fun clear() {
        file.delete()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    private companion object {
        const val ALIAS = "sibian-direct"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
