package com.aykkdk.shop

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
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
            .remove("token")
            .remove("user_role")
            .remove("last_sync_time")
            .apply()
    }

    /**
     * 安裝即用：開箱自動裝置連線，無需手動填寫帳號密碼即可接收推播
     */
    suspend fun ensureDeviceRegistered(appContext: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = getServerUrl()
            val deviceId = getDeviceId()
            val fcm = getFcmToken()
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

            val url = URL("$endpoint/api/device/auto-register")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("fcm_token", fcm)
                put("device_name", deviceName)
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val token = json.optString("token")
                val uname = json.optString("username")
                val role = json.optString("role", "standard")

                if (getToken().isBlank()) {
                    prefs.edit()
                        .putString("username", uname)
                        .putString("token", token)
                        .putString("user_role", role)
                        .apply()
                }
                "裝置連線就緒 ($uname)"
            } else {
                throw Exception("自動連線失敗 ($status)")
            }
        }
    }

    suspend fun login(username: String, pass: String, serverUrl: String? = null): Result<String> = withContext(Dispatchers.IO) {
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
                put("username", username.trim())
                put("password", pass.trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val token = json.optString("token")
                val uname = json.optString("username", username)
                val role = json.optString("role", "standard")
                serverUrl?.let { setServerUrl(it) }
                prefs.edit()
                    .putString("username", uname)
                    .putString("token", token)
                    .putString("user_role", role)
                    .apply()

                val fcm = getFcmToken()
                if (fcm.isNotBlank()) uploadFcmToken(fcm)
                "登入成功"
            } else {
                throw Exception(json.optString("message", "登入失敗，請確認帳號密碼"))
            }
        }
    }

    suspend fun register(username: String, pass: String, serverUrl: String? = null, role: String = "standard"): Result<String> = withContext(Dispatchers.IO) {
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
                put("username", username.trim())
                put("password", pass.trim())
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
                val resRole = json.optString("role", role)
                serverUrl?.let { setServerUrl(it) }
                prefs.edit()
                    .putString("username", uname)
                    .putString("token", token)
                    .putString("user_role", resRole)
                    .apply()

                val fcm = getFcmToken()
                if (fcm.isNotBlank()) uploadFcmToken(fcm)
                "註冊成功並已登入"
            } else {
                throw Exception(json.optString("message", "註冊失敗"))
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
            val token = getToken()
            if (token.isBlank()) {
                // 若無 token，先嘗試自動開箱連線
                ensureDeviceRegistered(appContext)
            }
            val activeToken = getToken()
            if (activeToken.isBlank()) throw Exception("請先連線至伺服器")

            // 1. 訂閱項目
            val subsArray = JSONArray()
            val prodStore = ProductSubscriptionStore(appContext)
            prodStore.list().forEach { p ->
                subsArray.put(JSONObject().apply {
                    put("url", p.optString("url"))
                    put("title", p.optString("title"))
                    put("shop", p.optString("shop"))
                    put("price", p.optLong("price", 0L))
                })
            }
            val shopPrefs = appContext.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE)
            shopPrefs.all.keys.forEach { shopUrl ->
                subsArray.put(JSONObject().apply {
                    put("url", shopUrl)
                    put("title", "訂閱賣場")
                    put("shop", shopUrl)
                })
            }

            // 2. 競標項目
            val auctionsArray = JSONArray()
            val auctionStore = AuctionStore(appContext)
            auctionStore.list().forEach { a ->
                auctionsArray.put(JSONObject().apply {
                    put("url", a.optString("url"))
                    put("title", a.optString("title"))
                })
            }

            // 3. 物流包裹
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

            val payload = JSONObject().apply {
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
