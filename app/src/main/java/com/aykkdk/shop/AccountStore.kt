package com.aykkdk.shop

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
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
    val checkFrequency: String = "每 1 小時",
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

    init {
        // 若存在舊版自動註冊之裝置臨時帳號，立即清除，嚴格禁止 App 自行為使用者建立帳號
        if (prefs.getString("username", "")?.startsWith("dev_") == true) {
            prefs.edit().remove("username").remove("token").remove("user_role").apply()
        }
    }

    companion object {
        val DEFAULT_SERVER_URL: String get() = CloudEndpointSecurity.getServerUrl()

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
    fun isLoggedIn(): Boolean = getUsername().isNotBlank() && !getUsername().startsWith("dev_") && getToken().isNotBlank()
    fun getRole(): String = prefs.getString("user_role", "standard").orEmpty()
    fun isVip(): Boolean {
        val r = getRole()
        return r == "standard" || r == "vip" || r == "admin"
    }
    fun getDefaultRefreshInterval(): Int = if (isVip()) 10 else 90

    fun getProductLimit(): Int = prefs.getInt("product_limit", 2)
    fun setProductLimit(limit: Int) {
        prefs.edit().putInt("product_limit", limit).apply()
    }

    fun getServerUrl(): String {
        val enc = prefs.getString("enc_server_url", null)
        if (!enc.isNullOrBlank()) {
            val decrypted = CloudEndpointSecurity.decryptCustomUrl(enc)
            if (!decrypted.isNullOrBlank()) return decrypted.trimEnd('/')
        }
        val raw = prefs.getString("server_url", null)
        if (!raw.isNullOrBlank()) {
            setServerUrl(raw)
            return raw.trim().trimEnd('/')
        }
        return DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isBlank() || cleaned == DEFAULT_SERVER_URL) {
            prefs.edit().remove("enc_server_url").remove("server_url").apply()
        } else {
            val enc = CloudEndpointSecurity.encryptCustomUrl(cleaned)
            prefs.edit().putString("enc_server_url", enc).remove("server_url").apply()
        }
    }

    fun getFcmToken(): String = prefs.getString("fcm_token", "").orEmpty()

    fun saveFcmToken(token: String) {
        if (token.isBlank()) return
        prefs.edit().putString("fcm_token", token).apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong("last_sync_time", 0L)

    fun getDeviceId(): String {
        val saved = prefs.getString("unique_device_id", null)
        if (!saved.isNullOrBlank()) return saved
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) { null }
        val finalId = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            UUID.randomUUID().toString().replace("-", "")
        }
        prefs.edit().putString("unique_device_id", finalId).apply()
        return finalId
    }

    fun isMaintenanceMode(): Boolean = prefs.getBoolean("server_maintenance", false)
    fun getMaintenanceMessage(): String = prefs.getString("server_maintenance_message", "").orEmpty()
    fun setMaintenanceMode(enabled: Boolean, message: String = "") {
        prefs.edit()
            .putBoolean("server_maintenance", enabled)
            .putString("server_maintenance_message", message)
            .apply()
    }

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
     * 從雲端還原此使用者的所有訂閱商品、店家與競拍項目 (登入後自動執行)
     */
    suspend fun restoreFromCloud(appContext: Context): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isLoggedIn()) throw Exception("未登入帳號")
            val activeToken = getToken()
            val url = URL("${getServerUrl()}/api/sync")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $activeToken")
            conn.connectTimeout = 12000
            conn.readTimeout = 12000

            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            val isMaint = status == 503 || json.optBoolean("maintenance", false) || json.optString("code") == "MAINTENANCE"
            if (isMaint) {
                val mMsg = json.optString("message", "伺服器系統維護中，暫停同步")
                setMaintenanceMode(true, mMsg)
                throw Exception(mMsg)
            } else {
                setMaintenanceMode(false)
            }
            if (json.optBoolean("success", false)) {
                var vault = json.optJSONObject("vault") ?: JSONObject()
                if (json.optBoolean("encrypted", false) && json.has("payload")) {
                    val decrypted = VaultCrypto.decrypt(json.getString("payload"))
                    if (!decrypted.isNullOrBlank()) {
                        runCatching { vault = JSONObject(decrypted) }
                    }
                }
                val products = vault.optJSONArray("products") ?: JSONArray()
                val stores = vault.optJSONArray("stores") ?: JSONArray()
                val auctions = vault.optJSONArray("auctions") ?: JSONArray()

                val prodStore = ProductSubscriptionStore(appContext)
                for (i in 0 until products.length()) {
                    val p = products.optJSONObject(i) ?: continue
                    val pUrl = p.optString("url")
                    if (pUrl.isNotBlank() && !prodStore.contains(pUrl)) {
                        val product = Product(
                            title = p.optString("title", "訂閱商品"),
                            price = p.optLong("price", 0L),
                            platform = p.optString("platform", Market.fromPublicProductUrl(pUrl)?.label.orEmpty()),
                            url = pUrl,
                            image = p.optString("image", ""),
                            shop = p.optString("shop", "")
                        )
                        runCatching { prodStore.add(product) }
                    }
                }

                val shopPrefs = appContext.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE)
                for (i in 0 until stores.length()) {
                    val s = stores.optJSONObject(i) ?: continue
                    val sUrl = s.optString("url")
                    if (sUrl.isNotBlank()) {
                        val existing = shopPrefs.getString(sUrl, null)
                        val item = if (existing != null) JSONObject(existing) else JSONObject()
                        item.put("url", sUrl)
                            .put("name", s.optString("name", s.optString("title", "訂閱賣場")))
                            .put("platform", s.optString("platform", Market.fromSubscription("", sUrl)?.name ?: "SHOPEE"))
                        shopPrefs.edit().putString(sUrl, item.toString()).apply()
                    }
                }

                val auctionStore = AuctionStore(appContext)
                for (i in 0 until auctions.length()) {
                    val a = auctions.optJSONObject(i) ?: continue
                    val aUrl = a.optString("url")
                    if (aUrl.isNotBlank() && auctionStore.list().none { it.optString("url") == aUrl }) {
                        runCatching {
                            auctionStore.add(aUrl)
                            if (a.has("title")) auctionStore.update(aUrl) { it.put("title", a.optString("title")) }
                        }
                    }
                }

                val role = json.optString("role", getRole())
                if (json.has("limits")) {
                    val lim = json.optJSONObject("limits")
                    if (lim != null && lim.has("products")) {
                        setProductLimit(lim.optInt("products", 2))
                    }
                }
                prefs.edit()
                    .putLong("last_sync_time", System.currentTimeMillis())
                    .putString("user_role", role)
                    .apply()

                SyncResult(
                    success = true,
                    message = "雲端同步還原成功！",
                    role = role,
                    subscriptionCount = products.length() + stores.length(),
                    auctionCount = auctions.length()
                )
            } else {
                throw Exception(json.optString("message", "伺服器同步還原失敗 ($status)"))
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
                put("deviceId", getDeviceId())
                if (captchaId.isNotBlank()) put("captchaId", captchaId)
                if (captchaAnswer.isNotBlank()) put("captchaAnswer", captchaAnswer.trim())
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
                val resRole = json.optString("role", "vip")
                serverUrl?.let { setServerUrl(it) }
                prefs.edit()
                    .putString("username", uname)
                    .putString("email", resEmail)
                    .putString("token", token)
                    .putString("user_role", resRole)
                    .apply()

                val fcm = getFcmToken()
                if (fcm.isNotBlank()) uploadFcmToken(fcm)
                json.optString("message", "註冊成功！已贈送 7 天 VIP 權限")
            } else {
                throw Exception(json.optString("message", "註冊失敗 ($status)"))
            }
        }
    }

    /**
     * 忘記密碼：申請發送 6 碼驗證碼至信箱
     */
    suspend fun requestPasswordReset(
        email: String,
        serverUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
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
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                json.optString("message", "驗證碼已發送至您的電子信箱，請查收並輸入！")
            } else {
                throw Exception(json.optString("message", "申請重設密碼失敗 ($status)"))
            }
        }
    }

    /**
     * 忘記密碼：向管理員求助（用於電子郵件無法寄送時，管理員可在後台直接修改密碼）
     */
    suspend fun requestAdminPasswordReset(
        email: String,
        serverUrl: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = (serverUrl ?: getServerUrl()).trimEnd('/')
            val url = URL("$endpoint/api/auth/forgot-password/admin-help")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("email", email.trim())
            }.toString()

            conn.outputStream.use { it.write(payload.toByteArray()) }
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                json.optString("message", "已向管理員送出求助！管理員在收到求助後會為您重設密碼。")
            } else {
                throw Exception(json.optString("message", "向管理員求助失敗 ($status)"))
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

    suspend fun fetchUserProfile(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = getToken()
            if (token.isBlank()) return@runCatching getRole()
            val url = URL("${getServerUrl()}/api/user")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            val json = JSONObject(text.ifBlank { "{}" })
            if (json.optBoolean("success", false)) {
                val newRole = json.optString("role", getRole())
                prefs.edit().putString("user_role", newRole).apply()
                newRole
            } else {
                getRole()
            }
        }
    }

    suspend fun syncToCloud(appContext: Context): Result<SyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isLoggedIn()) {
                throw Exception("尚未登入專屬帳號，無法同步至雲端")
            }
            if (isMaintenanceMode()) {
                throw Exception("伺服器維護中，暫停同步回傳")
            }
            runCatching { fetchUserProfile() }
            val activeToken = getToken()

            // 1. 商品訂閱項目
            val prodsArray = JSONArray()
            val prodStore = ProductSubscriptionStore(appContext)
            prodStore.list().forEach { p ->
                prodsArray.put(JSONObject().apply {
                    put("url", p.optString("url"))
                    put("title", p.optString("title"))
                    put("shop", p.optString("shop"))
                    put("platform", p.optString("platform"))
                    put("price", p.optLong("price", 0L))
                    put("status", p.optString("status", "unknown"))
                })
            }

            // 2. 店家賣場訂閱
            val storesArray = JSONArray()
            val shopPrefs = appContext.getSharedPreferences("shop_subscriptions_v2", Context.MODE_PRIVATE)
            shopPrefs.all.keys.forEach { shopUrl ->
                val raw = shopPrefs.getString(shopUrl, null)
                val itemObj = if (raw != null) runCatching { JSONObject(raw) }.getOrNull() else null
                val name = itemObj?.optString("name")?.ifBlank { null }
                    ?: itemObj?.optString("title")?.ifBlank { null }
                    ?: Market.fromPublicUrl(shopUrl)?.let { "${it.label}賣場" }
                    ?: "訂閱賣場"
                val platform = itemObj?.optString("platform")?.ifBlank { null }
                    ?: Market.fromPublicUrl(shopUrl)?.name
                    ?: "UNKNOWN"
                val seenArr = itemObj?.optJSONArray("seen")
                val lastSeen = if (seenArr != null && seenArr.length() > 0) seenArr.optString(0) else ""

                storesArray.put(JSONObject().apply {
                    put("url", shopUrl)
                    put("title", name)
                    put("shop", name)
                    put("platform", platform)
                    put("type", "store")
                    put("last_seen", lastSeen)
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

            // 合併相容 subscriptions
            val subsArray = JSONArray().apply {
                for (i in 0 until prodsArray.length()) put(prodsArray.get(i))
                for (i in 0 until storesArray.length()) put(storesArray.get(i))
            }

            // 完整原始訂閱資料結構 (端到端加密前)
            val rawVault = JSONObject().apply {
                put("products", prodsArray)
                put("stores", storesArray)
                put("subscriptions", subsArray)
                put("auctions", auctionsArray)
            }.toString()

            // 執行客戶端端到端 AES-256-GCM 加密，確保網路傳輸全程加密，中間人與代理無法外洩任何商品與賣場
            val encryptedVault = VaultCrypto.encrypt(rawVault)
            val payload = if (encryptedVault != null) {
                JSONObject().apply {
                    put("encrypted", true)
                    put("payload", encryptedVault)
                    put("products_count", prodsArray.length())
                    put("stores_count", storesArray.length())
                    put("auctions_count", auctionsArray.length())
                    put("subscriptions_count", subsArray.length())
                    put("fcm_token", getFcmToken())
                }.toString()
            } else {
                JSONObject().apply {
                    put("products", prodsArray)
                    put("stores", storesArray)
                    put("subscriptions", subsArray)
                    put("auctions", auctionsArray)
                    put("fcm_token", getFcmToken())
                }.toString()
            }

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
            val isMaint = status == 503 || json.optBoolean("maintenance", false) || json.optString("code") == "MAINTENANCE"
            if (isMaint) {
                val mMsg = json.optString("message", "伺服器系統維護中，暫停同步")
                setMaintenanceMode(true, mMsg)
                throw Exception(mMsg)
            } else {
                setMaintenanceMode(false)
            }
            if (json.optBoolean("success", false)) {
                val now = System.currentTimeMillis()
                val role = json.optString("role", getRole())
                val freq = json.optString("check_frequency", if (role == "low") "每 1.5 小時" else "每 10 分鐘")
                if (json.has("limits")) {
                    val lim = json.optJSONObject("limits")
                    if (lim != null && lim.has("products")) {
                        setProductLimit(lim.optInt("products", 2))
                    }
                }
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
                    logisticsCount = 0
                )
            } else {
                throw Exception(json.optString("message", "伺服器同步失敗 ($status)"))
            }
        }
    }

    fun triggerCloudSync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (isLoggedIn() && !isMaintenanceMode()) {
                    syncToCloud(context.applicationContext)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun checkServerStatus(appContext: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${getServerUrl()}/api/system/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            val status = conn.responseCode
            val text = (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()

            val json = JSONObject(text.ifBlank { "{}" })
            val maintenance = status == 503 || json.optBoolean("maintenance", false) || json.optString("code") == "MAINTENANCE"
            val msg = json.optString("message", if (maintenance) "伺服器維護中" else "")
            val wasMaint = isMaintenanceMode()
            setMaintenanceMode(maintenance, msg)
            if (wasMaint && !maintenance) {
                // 發現伺服器已恢復正常！立即觸發推送
                triggerCloudSync(appContext)
            }
            !maintenance
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
