package com.cmzc.xj_file.app

import android.app.Application
import android.util.Log
import com.cmzc.xj_file.app.core.playback.PlaybackEngine
import com.cmzc.xj_file.app.core.playback.PlaybackService
import com.cmzc.xj_file.app.core.transfer.TransferManager
import com.cmzc.xj_file.app.core.transfer.TransferService
import java.security.Provider
import java.security.Security
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.jce.provider.BouncyCastleProvider

class XjFileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // 1. 先注册我们自己的 MD4 Provider，放到最高优先级（位置 1）
            Security.removeProvider("XjMd4")
            Security.insertProviderAt(Md4Provider(), 1)
            Log.d("XjFileBC", "XjMd4 registered = " + Security.getProvider("XjMd4"))

            // 2. 再注册 BouncyCastle（为 jcifs-ng 提供其它加密算法）
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            Log.d("XjFileBC", "BC registered = " + Security.getProvider("BC"))

            // 3. 验证：确认 getInstance("MD4") 拿到的是我们的 XjMd4
            val f = SecretKeyFactory.getInstance("MD4")
            Log.d("XjFileBC", "MD4 factory provider = " + f.provider.name)
        } catch (t: Throwable) {
            Log.e("XjFileBC", "注册失败", t)
        }

        // 4. 传输前台服务：先建通知频道，再把 Context 交给 TransferManager（它自己拿不到）
        runCatching {
            TransferService.createChannel(this)
            com.cmzc.xj_file.app.core.transfer.ResumeStore.init(this)
            TransferManager.init(this)
        }.onFailure { Log.e("XjFileBC", "传输服务初始化失败", it) }

        // 5. App 级设置（动画 / 下拉刷新 / 外观）。失败也不能让 App 起不来
        runCatching { com.cmzc.xj_file.app.core.settings.AppSettings.init(this) }
            .onFailure { Log.e("XjFileBC", "AppSettings 初始化失败", it) }
        runCatching { com.cmzc.xj_file.app.core.settings.SortSettings.init(this) }
            .onFailure { Log.e("XjFileBC", "SortSettings 初始化失败", it) }

        // 6. Root 环境：没有 root 就静默什么都不做；有 su 才后台申请一次。
        runCatching { com.cmzc.xj_file.app.core.root.RootManager.detectAndRequestAsync() }
            .onFailure { Log.e("XjFileBC", "Root 检测初始化失败", it) }

        // 7. 音频后台播放：全局播放器 + 播放通知频道。
        //    播放器是第一次打开音频页时才真正创建的，这里只存 Application context。
        runCatching {
            PlaybackEngine.init(this)
            PlaybackService.createChannel(this)
        }.onFailure { Log.e("XjFileBC", "音频播放服务初始化失败", it) }
    }
}

/**
 * 只为 MD4 提供一个 SecretKeyFactory 的 Provider。
 * jcifs-ng 调用 SecretKeyFactory.getInstance("MD4") 时会优先找到这里。
 * 内部用 BouncyCastle 的 MD4Digest 算 16 字节摘要。
 */
class Md4Provider : Provider("XjMd4", 1.0, "MD4 provider for jcifs-ng") {
    init {
        put("SecretKeyFactory.MD4", Md4SecretKeyFactory::class.java.name)
    }
}

class Md4SecretKeyFactory : SecretKeyFactorySpi() {
    override fun engineGenerateSecret(keySpec: java.security.spec.KeySpec): SecretKey {
        val raw: ByteArray = when (keySpec) {
            is SecretKeySpec -> keySpec.encoded
            is PBEKeySpec -> {
                // jcifs-ng 用的是 UnicodeLittle 编码，即 UTF-16LE
                val chars = keySpec.password ?: CharArray(0)
                val bytes = ByteArray(chars.size * 2)
                for (i in chars.indices) {
                    bytes[i * 2] = (chars[i].code and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((chars[i].code ushr 8) and 0xFF).toByte()
                }
                bytes
            }
            else -> throw java.security.InvalidKeyException(
                "不支持的 KeySpec: ${keySpec.javaClass.name}"
            )
        }
        val md4 = MD4Digest()
        md4.update(raw, 0, raw.size)
        val out = ByteArray(16)
        md4.doFinal(out, 0)
        return SecretKeySpec(out, "MD4")
    }

    override fun engineGetKeySpec(
        key: SecretKey,
        keySpec: Class<*>
    ): java.security.spec.KeySpec {
        throw java.security.InvalidKeyException("不支持 getKeySpec")
    }

    override fun engineTranslateKey(key: SecretKey): SecretKey = key
}