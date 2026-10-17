// 保险箱用到的平台件：小偏好、Secret Key 的本机存储、生物识别保护的主密码、敏感剪贴板。
// 对应 iOS 的 VaultKeychain（VaultCore.swift:129）、UmbraBiometricStore（VaultBiometrics.swift）、
// UmbraClipboard（VaultKit.swift:300）、UmbraVaultSession 里那几个 UserDefaults 键。
//
// 存储一律落独立的 SharedPreferences "umbra_vault" —— 它已在 backup_rules / data_extraction_rules 里排除出备份，
// 对应 iOS 的 ThisDeviceOnly：换机恢复备份不该带走 Secret Key 和保险箱设置。
package com.geek.umbra.feature.vault

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.geek.umbra.app.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 保险箱的小偏好。键名照搬 iOS（`umbra.vault.*`）。 */
object VaultPrefs {
    const val LOCK_MIN = "umbra.vault.lockMin"
    const val MASK = "umbra.vault.mask"
    const val FACE_ID = "umbra.vault.faceID"
    const val LOCK_NOTE_SILENCED = "umbra.vault.lockNoteSilenced"
    const val FACE_ID_OFFERED = "umbra.vault.faceIDOffered"
    const val SK_CONFIRMED = "umbra.vault.skConfirmed"
    const val HAS_BIO_CRED = "umbra.vault.hasBioCred"
    const val SK_VALUE = "umbra.vault.secretKey"
    const val BIO_VALUE = "umbra.vault.masterPassword"

    val sp: SharedPreferences by lazy { AppGraph.context.getSharedPreferences("umbra_vault", Context.MODE_PRIVATE) }
}

/**
 * Keystore 包裹的一小段明文（Secret Key 用）。一把不出硬件、**不要求认证**的 AES-GCM 钥匙；
 * 钥匙丢了（用户清了凭据 / 系统升级坏了 Keystore）读出 null，等于「这台设备上没有 Secret Key」，下次让他再输一遍。
 */
private object VaultWrap {
    private const val ALIAS = "umbra.vault.sk.v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build(),
        )
        return g.generateKey()
    }

    fun seal(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        VaultCrypto.b64(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }.getOrNull()

    fun open(sealed: String): String? = runCatching {
        val b = VaultCrypto.b64Decode(sealed) ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

/**
 * Secret Key 的本机存储（iOS `VaultKeychain`）。
 *
 * iOS 那边有个「钥匙串删 App 不删、重装后要靠 App Group 标记判『这次安装确认过』」的坑；Android 卸载会连 Keystore
 * 钥匙和 SharedPreferences 一起清，坑本身不存在。`confirmed` 标记照搬（判据 [VaultSyncRules.secretKeyOnFile] 两端同一个），
 * 只是在 Android 上它和值总是一起生灭。
 */
object VaultKeychain {
    val confirmed: Boolean get() = VaultPrefs.sp.getBoolean(VaultPrefs.SK_CONFIRMED, false)

    /** 只看「存过没有」，不解密：首页每次画都要问，没必要每次碰 Keystore。 */
    val hasValue: Boolean get() = VaultPrefs.sp.contains(VaultPrefs.SK_VALUE)

    fun save(value: String, confirm: Boolean = true) {
        val sealed = VaultWrap.seal(value) ?: return
        VaultPrefs.sp.edit().putString(VaultPrefs.SK_VALUE, sealed).apply {
            if (confirm) putBoolean(VaultPrefs.SK_CONFIRMED, true)
        }.commit()
    }

    fun load(): String? = VaultPrefs.sp.getString(VaultPrefs.SK_VALUE, null)?.let { VaultWrap.open(it) }

    fun clear() {
        VaultPrefs.sp.edit().remove(VaultPrefs.SK_VALUE).putBoolean(VaultPrefs.SK_CONFIRMED, false).commit()
    }
}

/**
 * 生物识别保护的主密码（iOS `UmbraBiometricStore`）。
 *
 * 「用生物识别解锁」不是「跳过主密码」，而是**用生物识别取回本地保存的主密码**：解密要 AUK，AUK 只能由主密码 + SK 派生。
 * - Keystore 钥匙 `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`：只有刷脸 / 指纹取得出，
 *   重新录入生物数据后钥匙**立刻作废**（= iOS `.biometryCurrentSet`）；
 * - 每次用都要过一次 BiometricPrompt（`CryptoObject`），只认 BIOMETRIC_STRONG，**不许退回设备密码**：设备密码解不开密文；
 * - 关掉开关 / 「忘掉已存的主密码」/「忘掉本机数据」都会清除。
 */
object VaultBiometricStore {
    private const val ALIAS = "umbra.vault.bio.v1"

    enum class Failure(val code: String) {
        /** 用户取消 / 点了「用主密码」—— 之后不要再自动弹。 */
        Cancelled("cancelled"),
        /** 连错太多次被系统锁住。 */
        Lockout("lockout"),
        /** 生物数据变了，钥匙已失效。 */
        Invalidated("invalidated"),
        /** 设备不支持或没录入。 */
        Unavailable("unavailable"),
        /** 其它（没识别通过、超时…）。 */
        Failed("failed");

        /**
         * 文案照 iOS `Failure.message`，把「Face ID / iPhone」换成这台设备上的说法（[VaultBiometricStore.name]）——
         * 在安卓手机上说「Face ID 被系统锁定、去解锁一次 iPhone」是在指错路。
         */
        /** 错误卡标题（设计侧 199 答复第 13 条）。 */
        fun title(name: String): String = when (this) {
            Lockout -> "${name}暂时停用"
            Unavailable -> "${name}用不了"
            else -> "${name}没通过"
        }

        fun message(name: String): String = when (this) {
            Cancelled -> "已取消。点一下识别图标再试一次，或者输入主密码。"
            // 以下几句是设计侧 199 答复第 13 条定稿
            Lockout -> "失败次数过多，系统暂停了识别。用主密码解锁。"
            Invalidated -> "设备的${name}数据变了，需要重新输入一次主密码来重新授权$name。"
            Unavailable -> "这台手机还没录入指纹或面部"
            Failed -> "系统没能识别，可能是没对准或超过了等待时间。点一下识别图标再试一次，或者输入主密码。"
        }
    }

    /** 存过没有的标记。**不查 Keystore 来判断**（判断在每次画锁屏时都要用，碰受保护的钥匙没意义）。 */
    val hasCredential: Boolean get() = VaultPrefs.sp.getBoolean(VaultPrefs.HAS_BIO_CRED, false)

    /** 这台设备此刻能不能用强生物识别。 */
    fun available(ctx: Context = AppGraph.context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 界面上怎么称呼它：一律「生物识别」（设计侧 199 答复第 13 条）。不按硬件分「指纹 / 面容」——
     * 同一台手机两样都有、系统框里用哪个由用户在系统设置里定，App 猜错就是在指错路。
     */
    @Suppress("UNUSED_PARAMETER")
    fun name(ctx: Context = AppGraph.context): String = "生物识别"

    private fun newKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(b.build())
        return g.generateKey()
    }

    /** 写入前要的加密 Cipher（每次开启都换一把新钥匙）。拿不到回 null（设备没设锁屏 / 没录生物识别）。 */
    fun encryptCipher(): Cipher? = runCatching {
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, newKey()) }
    }.getOrNull()

    /** 过了 BiometricPrompt 之后，用那颗已认证的 Cipher 把主密码存下来。调用方**必须**已经验过主密码。 */
    fun save(authedCipher: Cipher, password: String): Boolean = runCatching {
        val ct = authedCipher.doFinal(password.toByteArray(Charsets.UTF_8))
        VaultPrefs.sp.edit().putString(VaultPrefs.BIO_VALUE, VaultCrypto.b64(authedCipher.iv + ct))
            .putBoolean(VaultPrefs.HAS_BIO_CRED, true).commit()
    }.getOrDefault(false)

    sealed interface DecryptInit {
        data class Ready(val cipher: Cipher) : DecryptInit
        data class Fail(val why: Failure) : DecryptInit
    }

    /** 取回之前要的解密 Cipher。钥匙已作废（重新录了生物数据）→ 清掉标记，回 Invalidated。 */
    fun decryptCipher(): DecryptInit {
        val raw = VaultPrefs.sp.getString(VaultPrefs.BIO_VALUE, null)?.let { VaultCrypto.b64Decode(it) }
        if (raw == null || raw.size < 13) { clear(); return DecryptInit.Fail(Failure.Invalidated) }
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = ks.getKey(ALIAS, null) as? SecretKey ?: run { clear(); return DecryptInit.Fail(Failure.Invalidated) }
            DecryptInit.Ready(Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, 12)) })
        } catch (_: KeyPermanentlyInvalidatedException) {
            clear(); DecryptInit.Fail(Failure.Invalidated)
        } catch (_: Exception) {
            clear(); DecryptInit.Fail(Failure.Invalidated)
        }
    }

    /** 用认证过的 Cipher 把主密码解出来。读不出 = 凭证已失效：清掉，回 null。 */
    fun open(authedCipher: Cipher): String? {
        val raw = VaultPrefs.sp.getString(VaultPrefs.BIO_VALUE, null)?.let { VaultCrypto.b64Decode(it) } ?: return null
        return runCatching { String(authedCipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8) }.getOrElse { clear(); null }
    }

    fun clear() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
        VaultPrefs.sp.edit().remove(VaultPrefs.BIO_VALUE).putBoolean(VaultPrefs.HAS_BIO_CRED, false).commit()
    }
}

/**
 * BiometricPrompt 的一层薄封装。负按钮写「用主密码」（不加 DEVICE_CREDENTIAL —— 设备密码解不开密文，
 * 同 iOS 把 localizedFallbackTitle 置空）。回调落在主线程。
 */
object VaultBiometricPrompt {
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        cipher: Cipher?,
        onResult: (Result<Cipher?>) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText("用主密码")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(Result.success(result.cryptoObject?.cipher))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(Result.failure(FailureException(map(errorCode))))
            }
            // onAuthenticationFailed（一次没认出）不回调：系统弹窗还开着，用户可以再试。
        })
        runCatching {
            if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
        }.onFailure { onResult(Result.failure(FailureException(VaultBiometricStore.Failure.Unavailable))) }
    }

    class FailureException(val failure: VaultBiometricStore.Failure) : Exception(failure.code)

    private fun map(code: Int): VaultBiometricStore.Failure = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> VaultBiometricStore.Failure.Cancelled
        BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> VaultBiometricStore.Failure.Lockout
        BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> VaultBiometricStore.Failure.Unavailable
        else -> VaultBiometricStore.Failure.Failed
    }
}

/**
 * 保险箱的剪贴板（iOS `UmbraClipboard`）。**保险箱里的值一律走敏感入口**，不按「是不是密码」分两路 ——
 * 恢复码 / PIN 最常放在「字段」「文本」里，按字段类型放行正好漏掉它们（iOS 审查台账 p1）。
 *
 * - `EXTRA_IS_SENSITIVE`（API 33+；老系统用同名字符串键，系统不认也无害）：系统不在剪贴板预览里显示内容、输入法不把它记进历史；
 * - 60 秒后**若剪贴板仍是我们放的那份**（比 `ClipDescription.getTimestamp()`）才清 —— 用户中途复制了别的东西不能被我们弄没。
 *   iOS 是系统按 `.expirationDate` 清（App 死了照样生效）；Android 没有这个能力，进程被杀那 60 秒内的清除会落空，
 *   这是平台差异（代码里只能做到「进程活着就清」）。
 */
object VaultClipboard {
    /** 敏感值的自动清除时长。吐司文案（「60 秒后自动清除」）说的就是它。 */
    const val TTL_MS = 60_000L

    private val cm: ClipboardManager get() = AppGraph.context.getSystemService(ClipboardManager::class.java)

    fun copySensitive(value: String) {
        val clip = ClipData.newPlainText("Umbra", value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        runCatching { cm.setPrimaryClip(clip) }
        val stamp = currentStamp()
        // 记下「这一刻的剪贴板是保险箱的值」，给常用语的 {clipboard} 判断用（[holdsSensitive]）。存盘不存静态变量：
        // App 被划掉再打开，那份值可能还在剪贴板上。
        VaultPrefs.sp.edit().putLong(SensitiveClip.PREFS_KEY, stamp ?: -1L).apply()
        AppGraph.scope.launch {
            delay(TTL_MS)
            if (stamp != null && currentStamp() == stamp) runCatching { cm.clearPrimaryClip() }
        }
    }

    /** 当前剪贴板那份的时间戳（API 26+ 有）。读描述不读内容，不会触发「已读取剪贴板」的系统提示。 */
    private fun currentStamp(): Long? = runCatching { cm.primaryClipDescription?.timestamp }.getOrNull()

    /**
     * 剪贴板里现在是不是保险箱放进去的那份敏感值（给常用语的 `{clipboard}` 用：是的话展开为空）。
     * 也认**别家**标了 `EXTRA_IS_SENSITIVE` 的（其它密码管理器复制的）。判不准抛 [SensitiveClip.Unjudgeable]，调用方按「是」处理。
     */
    fun holdsSensitive(): Boolean {
        val sp = VaultPrefs.sp
        val rec: Any? = if (sp.contains(SensitiveClip.PREFS_KEY)) sp.all[SensitiveClip.PREFS_KEY] else null
        val desc = runCatching { cm.primaryClipDescription }.getOrNull()
        val foreign = desc?.extras?.let {
            it.getBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", false)
        } ?: false
        val held = SensitiveClip.holds(rec, desc?.timestamp ?: -2L)
        if (rec != null && !held) sp.edit().remove(SensitiveClip.PREFS_KEY).apply()
        return SensitiveClip.sensitive(held, foreign)
    }
}

/** 从 Compose 的 Context 一路找到宿主 Activity（BiometricPrompt 要 FragmentActivity，FLAG_SECURE 要 window）。 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c != null) {
        if (c is FragmentActivity) return c
        c = (c as? android.content.ContextWrapper)?.baseContext
    }
    return null
}
