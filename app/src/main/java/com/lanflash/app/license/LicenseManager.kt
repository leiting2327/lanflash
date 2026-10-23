package com.lanflash.app.license

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.lanflash.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LicenseInfo(val plan: String, val expiresAt: Long)

/**
 * Online activation with a locally verifiable signed token.
 *
 * Token format: LF1.<base64url(payload)>.<base64url(hmac_sha256)>
 * The signature is checked on-device so the app still works offline for the
 * remaining lifetime of a previously issued token.
 */
class LicenseManager(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("lanflash_license", Context.MODE_PRIVATE)

    val apiBase: String = BuildConfig.API_BASE_URL.trimEnd('/')
    val configured: Boolean = BuildConfig.LICENSE_SECRET.isNotBlank()

    val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val raw = "$androidId|${ctx.packageName}"
        val md = MessageDigest.getInstance("SHA-256")
        md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun cached(): LicenseInfo? {
        val token = prefs.getString("token", null) ?: return null
        return verify(token)
    }

    fun verify(token: String): LicenseInfo? {
        if (BuildConfig.LICENSE_SECRET.isBlank()) return null
        val parts = token.split(".")
        if (parts.size != 3 || parts[0] != "LF1") return null
        val payloadBytes = decode(parts[1]) ?: return null
        val signature = decode(parts[2]) ?: return null
        val expected = hmac("LF1.${parts[1]}")
        if (!MessageDigest.isEqual(expected, signature)) return null
        val json = try {
            JSONObject(String(payloadBytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (json.optString("did") != deviceId) return null
        val exp = json.optLong("exp", 0)
        if (exp * 1000L <= System.currentTimeMillis()) return null
        return LicenseInfo(json.optString("plan", "pro"), exp * 1000L)
    }

    @Throws(Exception::class)
    fun activate(code: String): LicenseInfo {
        if (!configured) throw IllegalStateException("未配置授权服务")
        val trimmed = code.trim().uppercase()
        if (trimmed.isEmpty()) throw IllegalArgumentException("请输入激活码")

        val body = JSONObject()
            .put("code", trimmed)
            .put("deviceId", deviceId)
            .put("platform", "android")
            .put("version", BuildConfig.VERSION_NAME)
            .toString()

        val conn = (URL("$apiBase/api/v1/activate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "LanFlash-Android/${BuildConfig.VERSION_NAME}")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }

            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "激活失败（HTTP $status）"))
            }
            val token = json.optString("token")
            if (token.isBlank()) throw IllegalStateException("服务端未返回授权令牌")
            val info = verify(token) ?: throw IllegalStateException("授权令牌校验失败，请联系客服")
            prefs.edit().putString("token", token).apply()
            return info
        } finally {
            conn.disconnect()
        }
    }

    fun clear() {
        prefs.edit().remove("token").apply()
    }

    fun expiresText(expiresAt: Long): String {
        val leftDays = ((expiresAt - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
        return "剩余 $leftDays 天"
    }

    private fun hmac(input: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.LICENSE_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(input.toByteArray(Charsets.UTF_8))
    }

    private fun decode(s: String): ByteArray? = try {
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } catch (_: Exception) {
        null
    }
}
