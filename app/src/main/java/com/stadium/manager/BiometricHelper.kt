package com.stadium.manager

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Fingerprint login.
 * The username + password are encrypted with an AES key that lives inside the phone's secure
 * hardware (Android Keystore). The key only works right after a successful fingerprint scan,
 * and it is destroyed automatically if a new fingerprint is added to the phone.
 */
class BiometricHelper(private val act: FragmentActivity) {

    companion object {
        private const val KEY_ALIAS = "stadium_bio_key"
        private const val PREFS = "stadium_bio"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }

    private val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(): JSONObject {
        val code = BiometricManager.from(act).canAuthenticate(BIOMETRIC_STRONG)
        val msg = when (code) {
            BiometricManager.BIOMETRIC_SUCCESS -> "ok"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "هذا الهاتف لا يحتوي على مستشعر بصمة."
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "مستشعر البصمة غير متاح حالياً، حاول لاحقاً."
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "لا توجد بصمة مسجّلة في الهاتف. أضف بصمتك من إعدادات الهاتف (الأمان) أولاً."
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "يلزم تحديث أمني للهاتف لاستخدام البصمة."
            else -> "البصمة غير مدعومة على هذا الهاتف."
        }
        return JSONObject()
            .put("available", code == BiometricManager.BIOMETRIC_SUCCESS)
            .put("code", code)
            .put("msg", msg)
            .put("noneEnrolled", code == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
            .put("enrolled", prefs.contains("ct") && keyExists())
            .put("user", prefs.getString("user", "") ?: "")
    }

    fun clear() {
        prefs.edit().clear().apply()
        try { keyStore().deleteEntry(KEY_ALIAS) } catch (e: Exception) { /* ignore */ }
    }

    fun enroll(username: String, password: String, cb: (JSONObject) -> Unit) {
        val st = status()
        if (!st.getBoolean("available")) { cb(err("unavailable", st.getString("msg"))); return }
        val cipher = try {
            clear()
            Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, createKey()) }
        } catch (e: Exception) {
            cb(err("error", "تعذّر تجهيز مفتاح البصمة: " + (e.message ?: ""))); return
        }
        prompt(
            "تفعيل الدخول بالبصمة",
            "ضع إصبعك على المستشعر لتأكيد التفعيل للحساب: $username",
            "إلغاء",
            cipher,
            onOk = { c ->
                try {
                    val payload = JSONObject().put("u", username).put("p", password).toString()
                    val ct = c.doFinal(payload.toByteArray(Charsets.UTF_8))
                    prefs.edit()
                        .putString("user", username)
                        .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                        .putString("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                        .apply()
                    cb(JSONObject().put("ok", true).put("user", username))
                } catch (e: Exception) {
                    clear(); cb(err("error", "تعذّر حفظ البصمة: " + (e.message ?: "")))
                }
            },
            onFail = { code, msg -> clear(); cb(err(code, msg)) }
        )
    }

    fun login(cb: (JSONObject) -> Unit) {
        val st = status()
        if (!st.getBoolean("available")) { cb(err("unavailable", st.getString("msg"))); return }
        val ivB64 = prefs.getString("iv", null)
        val ctB64 = prefs.getString("ct", null)
        if (ivB64 == null || ctB64 == null) { cb(err("not_enrolled", "الدخول بالبصمة غير مفعّل على هذا الهاتف.")); return }
        val cipher = try {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: run { clear(); cb(err("invalidated", "تم إلغاء البصمة المحفوظة. سجّل الدخول بكلمة المرور ثم فعّل البصمة من جديد.")); return }
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            clear(); cb(err("invalidated", "تمت إضافة أو حذف بصمة في الهاتف، لذلك أُلغي الدخول بالبصمة للأمان. سجّل الدخول بكلمة المرور ثم فعّلها من جديد.")); return
        } catch (e: Exception) {
            clear(); cb(err("invalidated", "تعذّر قراءة البصمة المحفوظة. سجّل الدخول بكلمة المرور ثم فعّل البصمة من جديد.")); return
        }
        prompt(
            "الدخول بالبصمة",
            "الحساب: " + (prefs.getString("user", "") ?: ""),
            "استخدام كلمة المرور",
            cipher,
            onOk = { c ->
                try {
                    val plain = String(c.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
                    val o = JSONObject(plain)
                    cb(JSONObject().put("ok", true).put("u", o.getString("u")).put("p", o.getString("p")))
                } catch (e: Exception) {
                    clear(); cb(err("invalidated", "تعذّر فك البيانات المحفوظة. سجّل الدخول بكلمة المرور ثم فعّل البصمة من جديد."))
                }
            },
            onFail = { code, msg -> cb(err(code, msg)) }
        )
    }

    // ------------------------------------------------------------------
    private fun prompt(
        title: String, subtitle: String, negative: String, cipher: Cipher,
        onOk: (Cipher) -> Unit, onFail: (String, String) -> Unit
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        val prompt = BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) onFail("error", "تعذّر التحقق من البصمة.") else onOk(c)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onFail("cancel", "")
                    BiometricPrompt.ERROR_LOCKOUT ->
                        onFail("lockout", "محاولات كثيرة خاطئة. انتظر 30 ثانية أو استخدم كلمة المرور.")
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                        onFail("lockout", "تم قفل البصمة مؤقتاً. افتح قفل الهاتف بالرمز ثم حاول مجدداً، أو استخدم كلمة المرور.")
                    else -> onFail("error", errString.toString())
                }
            }
            // onAuthenticationFailed: the system dialog already says "not recognised" and lets the user retry.
        })
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keyExists(): Boolean = try { keyStore().containsAlias(KEY_ALIAS) } catch (e: Exception) { false }

    private fun createKey(): SecretKey {
        val b = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(b.build())
        return kg.generateKey()
    }

    private fun err(code: String, msg: String) = JSONObject().put("ok", false).put("code", code).put("msg", msg)
}
