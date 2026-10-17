package com.geek.umbra.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 小块机密的本机存储 —— 对应 iOS 的 Keychain（`AfterFirstUnlockThisDeviceOnly`）。
 *
 * 做法：Android Keystore 里一把不出硬件的 AES-256-GCM 密钥，把值加密后放进一个专用的 SharedPreferences。
 * - 不用 androidx.security-crypto：它已经废弃，且 EncryptedSharedPreferences 在部分机型上解密失败会整份丢。
 * - 用 SharedPreferences 不用 DataStore：HTTP 拦截器要同步读令牌，而 iOS 的约定是「每次真读、不做内存缓存」。
 * - 这个文件在 `data_extraction_rules.xml` 里排除出备份 —— 对应 ThisDeviceOnly：换机恢复备份不该带走登录态。
 * 密钥丢失（用户清了凭据 / 系统升级坏了 Keystore）时读出 null，等于「没登录」，不崩。
 */
class SecureStore(context: Context) {
    private val sp = context.getSharedPreferences("umbra_secure", Context.MODE_PRIVATE)

    fun get(account: String): String? {
        val raw = sp.getString(account, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(account: String, value: String?) {
        if (value == null) {
            sp.edit().remove(account).commit(); return
        }
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
        // commit 而不是 apply：登录成功紧接着就要用这把令牌发请求，异步写会有一瞬间读不到。
        sp.edit().putString(account, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    companion object {
        private const val ALIAS = "umbra.secure.v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        const val SESSION_TOKEN = "session-token"
        const val OIDC_REFRESH = "oidc-refresh-token"
        const val OIDC_CLIENT = "oidc-client"
    }
}

/** 会话令牌三件套。清会话令牌时刷新令牌与 IdP 配置一起清（iOS umbra-ios#90）。 */
class SessionStore(private val secure: SecureStore) {
    var token: String
        get() = secure.get(SecureStore.SESSION_TOKEN).orEmpty()
        set(v) {
            if (v.isEmpty()) {
                secure.set(SecureStore.SESSION_TOKEN, null)
                secure.set(SecureStore.OIDC_REFRESH, null)
                secure.set(SecureStore.OIDC_CLIENT, null)
            } else secure.set(SecureStore.SESSION_TOKEN, v)
        }

    var refreshToken: String?
        get() = secure.get(SecureStore.OIDC_REFRESH)
        set(v) = secure.set(SecureStore.OIDC_REFRESH, v)

    /** 签发刷新令牌的那份 IdP 配置（JSON）。续期只用它，不用之后 /auth/config 下发的。 */
    var oidcClient: String?
        get() = secure.get(SecureStore.OIDC_CLIENT)
        set(v) = secure.set(SecureStore.OIDC_CLIENT, v)
}
