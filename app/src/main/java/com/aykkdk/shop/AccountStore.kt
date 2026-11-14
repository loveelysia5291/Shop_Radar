package com.aykkdk.shop

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class SyncResult(
    val success: Boolean,
    val message: String,
    val role: String = "standard",
    val checkFrequency: String = "每 30 分鐘",
    val subscriptionCount: Int = 0,
    val auctionCount: Int = 0,
    val logisticsCount: Int = 0
)

data class CaptchaInfo(
    val captchaId: String,
    val question: String
)

data class ForgotPasswordResponse(
    val success: Boolean,
    val message: String,
    val devCode: String? = null
)

class AccountStore private constructor(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("shop_radar_account_v1", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_SERVER_URL = "https://shop-radar-worker.adobeado909123.workers.dev"

        @Volatile
        private var instance: AccountStore? = null

        fun get(context: Context): AccountStore =
            instance ?: synchronized(this) {
                instance ?: AccountStore(context.applicationContext).also { instance = it }
            }
    }

    fun getUsername(): String = prefs.getString("username", "").orEmpty()
    fun getEmail(): String = prefs.getString("email", "").orEmpty()
    fun getToken(): String = prefs.getString("token", "").orEmpty()
    fun isLoggedIn(): Boolean = getUsername().isNotBlank() && getToken().isNotBlank()
    fun getRole(): String = prefs.getString("user_role", "standard").orEmpty()

    fun getDeviceId(): String {
        var id = prefs.getString("device_id", "")
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun getServerUrl(): String {
        val raw = prefs.getString("server_url", DEFAULT_SERVER_URL).orEmpty().trim()
        return if (raw.isBlank()) DEFAULT_SERVER_URL else raw.trimEnd('/')
    }

    fun setServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs.edit().putString("server_url", cleaned.ifBlank { DEFAULT_SERVER_URL }).apply()
    }

    fun getFcmToken(): String = prefs.getString("fcm_token", "").orEmpty()

    fun saveFcmToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString("fcm_token", token).apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong("last_sync_time", 0L)

    fun logout() {
        prefs.edit()
            .remove("username")
            .remove("email")
            .remove("token")
            .remove("user_role")
            .remove("last_sync_time")
            .apply()
    }

    /**
     * 取得人機驗證碼題目 (避免機器人濫發帳號)
     */
    suspend fun fetchCaptcha(serverUrl: String? = null): Result<CaptchaInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/captcha")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                CaptchaInfo(
                    captchaId = json.optString("captchaId"),
                    question = json.optString("question", "請完成人機驗證")
                )
            } else {
                throw Exception(json.optString("message", "取得人機驗證碼失敗 ($status)"))
            }
        }
    }

    /**
     * 確保裝置已向雲端註冊連線 (若尚未登入專屬帳號，自動以設備 ID 註冊就緒，開箱即用)
     */
    suspend fun ensureDeviceRegistered(appContext: Context): Result<String> = withContext(Dispatchers.IO) {
        if (isLoggedIn()) {
            return@withContext Result.success("已登入專屬帳號 (${getUsername()})")
        }
        runCatching {
            val endpoint = getServerUrl().trimEnd('/')
            val url = URL("$endpoint/api/device/auto-register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("device_id", getDeviceId())
                put("fcm_token", getFcmToken())
                put("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val token = json.optString("token")
                val uname = json.optString("username", "dev_${getDeviceId().take(8)}")
                val role = json.optString("role", "low")
                prefs.edit()
                    .putString("username", uname)
                    .putString("token", token)
                    .putString("user_role", role)
                    .apply()
                "裝置連線就緒 ($uname)"
            } else {
                throw Exception(json.optString("message", "裝置自動註冊失敗 ($status)"))
            }
        }
    }

    suspend fun login(account: String, pass: String, serverUrl: String? = null): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/login")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            // 絕不於本地永久存儲密碼，純內存傳輸
            val payload = JSONObject().apply {
                put("username", account.trim())
                put("password", pass.trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val token = json.optString("token")
                val uname = json.optString("username", account)
                val email = json.optString("email", "")
                val role = json.optString("role", "standard")
                serverUrl?.let { setServerUrl(it) }
                prefs.edit()
                    .putString("username", uname)
                    .putString("email", email)
                    .putString("token", token)
                    .putString("user_role", role)
                    .apply()

                val fcm = getFcmToken()
                if (fcm.isNotBlank()) uploadFcmToken(fcm)
                "登入成功，已啟動雲端推播服務"
            } else {
                throw Exception(json.optString("message", "登入失敗，請確認帳號或密碼"))
            }
        }
    }

    suspend fun register(
        email: String,
        username: String,
        pass: String,
        serverUrl: String? = null,
        role: String = "standard",
        captchaId: String = "",
        captchaAnswer: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("email", email.trim())
                put("username", username.trim())
                put("password", pass.trim())
                if (captchaId.isNotBlank()) put("captchaId", captchaId)
                if (captchaAnswer.isNotBlank()) put("captchaAnswer", captchaAnswer.trim())
                put("role", role)
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val token = json.optString("token")
                val uname = json.optString("username", username)
                val resEmail = json.optString("email", email)
                val resRole = json.optString("role", role)
                serverUrl?.let { setServerUrl(it) }
                prefs.edit()
                    .putString("username", uname)
                    .putString("email", resEmail)
                    .putString("token", token)
                    .putString("user_role", resRole)
                    .apply()

                val fcm = getFcmToken()
                if (fcm.isNotBlank()) uploadFcmToken(fcm)
                "註冊成功並已啟動雲端推播"
            } else {
                throw Exception(json.optString("message", "註冊失敗 ($status)"))
            }
        }
    }

    /**
     * 忘記密碼：申請 6 碼重設驗證碼
     */
    suspend fun requestPasswordReset(
        email: String,
        serverUrl: String? = null,
        captchaId: String = "",
        captchaAnswer: String = ""
    ): Result<ForgotPasswordResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/forgot-password/request")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("email", email.trim())
                if (captchaId.isNotBlank()) put("captchaId", captchaId)
                if (captchaAnswer.isNotBlank()) put("captchaAnswer", captchaAnswer.trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                ForgotPasswordResponse(
                    success = true,
                    message = json.optString("message", "驗證碼已生成"),
                    devCode = json.optString("devCode").takeIf { it.isNotBlank() }
                )
            } else {
                throw Exception(json.optString("message", "申請驗證碼失敗 ($status)"))
            }
        }
    }

    /**
     * 忘記密碼：驗證 6 碼驗證碼並設定全新密碼
     */
    suspend fun resetPassword(
        email: String,
        code: String,
        newPassword: String,
        serverUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/forgot-password/reset")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("email", email.trim())
                put("code", code.trim())
                put("newPassword", newPassword.trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                json.optString("message", "密碼已重設成功！請使用新密碼登入")
            } else {
                throw Exception(json.optString("message", "密碼重設失敗 ($status)"))
            }
        }
    }

    suspend fun uploadFcmToken(fcmToken: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getToken()
            if (token.isBlank()) return@runCatching false
            val url = URL("${getServerUrl()}/api/device/token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val payload = JSONObject().apply {
                put("fcm_token", fcmToken)
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            conn.disconnect()
            status in 200..299
        }
    }

    suspend fun syncToCloud(appContext: Context): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isLoggedIn()) {
                ensureDeviceRegistered(appContext).getOrThrow()
            }
            val activeToken = getToken()

            // 1. 商品訂閱項目
            val prodsArray = JSONArray()
            val prodStore = ProductSubscriptionStore(appContext)
            prodStore.list().forEach { p ->
                prodsArray.put(JSONObject().apply {
                    put("url", p.optString("url"))
                    put("title", p.optString("title"))
                    put("shop", p.optString("shop"))
                    put("price", p.optLong("price", 0L))
                })
            }

            // 2. 店家賣場訂閱
            val storesArray = JSONArray()
            val shopPrefs = appContext.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE)
            shopPrefs.all.keys.forEach { shopUrl ->
                storesArray.put(JSONObject().apply {
                    put("url", shopUrl)
                    put("title", "訂閱賣場")
                    put("shop", shopUrl)
                    put("type", "store")
                })
            }

            // 3. 競標項目
            val auctionsArray = JSONArray()
            val auctionStore = AuctionStore(appContext)
            auctionStore.list().forEach { a ->
                auctionsArray.put(JSONObject().apply {
                    put("url", a.optString("url"))
                    put("title", a.optString("title"))
                })
            }

            // 4. 物流包裹
            val logisticsArray = JSONArray()
            val logStore = LogisticsStore(appContext)
            val parcels = logStore.loadAll()
            parcels.forEach { l ->
                logisticsArray.put(JSONObject().apply {
                    put("carrier", l.carrier.id)
                    put("trackingNumber", l.trackingNumber)
                    put("note", l.note)
                    put("latestStatus", l.latestStatus)
                    put("isPickedUp", l.isPickedUp)
                    put("storeName", l.storeName.orEmpty())
                })
            }

            // 合併相容 subscriptions
            val subsArray = JSONArray().apply {
                for (i in 0 until prodsArray.length()) put(prodsArray.get(i))
                for (i in 0 until storesArray.length()) put(storesArray.get(i))
            }

            val payload = JSONObject().apply {
                put("products", prodsArray)
                put("stores", storesArray)
                put("subscriptions", subsArray)
                put("auctions", auctionsArray)
                put("logistics", logisticsArray)
                put("fcm_token", getFcmToken())
            }.toString()

            val url = URL("${getServerUrl()}/api/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $activeToken")
            conn.connectTimeout = 12000
            conn.readTimeout = 12000

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val now = System.currentTimeMillis()
                val role = json.optString("role", getRole())
                val freq = json.optString("check_frequency", if (role == "low") "每 60 分鐘" else "每 30 分鐘")
                prefs.edit()
                    .putLong("last_sync_time", now)
                    .putString("user_role", role)
                    .apply()

                SyncResult(
                    success = true,
                    message = "同步成功！",
                    role = role,
                    checkFrequency = freq,
                    subscriptionCount = subsArray.length(),
                    auctionCount = auctionsArray.length(),
                    logisticsCount = logisticsArray.length()
                )
            } else {
                throw Exception(json.optString("message", "伺服器同步失敗 ($status)"))
            }
        }
    }

    fun triggerCloudSync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!isLoggedIn()) {
                    ensureDeviceRegistered(context.applicationContext)
                }
                syncToCloud(context.applicationContext)
            } catch (_: Exception) {}
        }
    }

    suspend fun sendTestPush(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getToken()
            if (token.isBlank()) throw Exception("請先連線至伺服器")
            val fcm = getFcmToken()
            if (fcm.isBlank()) throw Exception("尚未取得 FCM Device Token，請稍候重試")

            val url = URL("${getServerUrl()}/api/test-push")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            conn.outputStream.use { it.write("{}".toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                json.optString("message", "推播已發送！請查看手機通知")
            } else {
                throw Exception(json.optString("message", "伺服器發送推播失敗 ($status)"))
            }
        }
    }
}
