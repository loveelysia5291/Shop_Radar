package com.aykkdk.shop

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class LogisticsCarrier(
    val id: String,
    val displayName: String,
    val officialUrl: String,
    val brandColor: Color,
    val badgeBgColor: Color,
    val badgeTextColor: Color,
    val needsCaptcha: Boolean,
    val isUnderMaintenance: Boolean = false
) {
    SEVEN_ELEVEN(
        id = "711",
        displayName = "7-ELEVEN",
        officialUrl = "https://eservice.7-11.com.tw/e-tracking/search.aspx",
        brandColor = Color(0xFF007A3D),
        badgeBgColor = Color(0xFFE6F4EA),
        badgeTextColor = Color(0xFF0B6623),
        needsCaptcha = true
    ),
    FAMILY_MART(
        id = "fami",
        displayName = "全家 FamilyMart",
        officialUrl = "https://fmec.famiport.com.tw/FP_Entrance/QueryBox",
        brandColor = Color(0xFF0072CE),
        badgeBgColor = Color(0xFFE8F1FC),
        badgeTextColor = Color(0xFF0058A8),
        needsCaptcha = true
    ),
    HI_LIFE(
        id = "hilife",
        displayName = "萊爾富 Hi-Life",
        officialUrl = "https://www.hilife.com.tw/shipment-status",
        brandColor = Color(0xFFDC2626),
        badgeBgColor = Color(0xFFFEE2E2),
        badgeTextColor = Color(0xFFB91C1C),
        needsCaptcha = true
    ),
    OK_MART(
        id = "ok",
        displayName = "OK 超商",
        officialUrl = "https://www.okmart.com.tw/",
        brandColor = Color(0xFFEA580C),
        badgeBgColor = Color(0xFFFFEDD5),
        badgeTextColor = Color(0xFFC2410C),
        needsCaptcha = false,
        isUnderMaintenance = true
    ),
    SPX(
        id = "spx",
        displayName = "蝦皮店到店",
        officialUrl = "https://spx.tw/",
        brandColor = Color(0xFFEE4D2D),
        badgeBgColor = Color(0xFFFFEDE9),
        badgeTextColor = Color(0xFFC23517),
        needsCaptcha = false
    );

    companion object {
        fun fromId(id: String): LogisticsCarrier = entries.find { it.id == id } ?: SEVEN_ELEVEN
    }
}

enum class LogisticsStage(val label: String, val badgeBgColor: Color, val badgeTextColor: Color) {
    TRANSIT("理貨中 / 運送中", Color(0xFFDBEAFE), Color(0xFF1D4ED8)),
    ARRIVED("貨到了！請儘速領取 🔔", Color(0xFFDCFCE7), Color(0xFF15803D)),
    EXPIRING("快到期提醒 ⚠️", Color(0xFFFEE2E2), Color(0xFFB91C1C)),
    PICKED_UP("我已取貨 ✅", Color(0xFFF1F5F9), Color(0xFF475569)),
    UNKNOWN("處理中 / 查無資料", Color(0xFFFEF3C7), Color(0xFFB45309))
}

data class LogisticsHistoryItem(
    val time: String,
    val description: String
)

data class LogisticsRecord(
    val id: String = UUID.randomUUID().toString(),
    val carrier: LogisticsCarrier,
    val trackingNumber: String,
    val note: String = "",
    val latestStatus: String = "尚未取得貨況資訊",
    val stage: LogisticsStage = LogisticsStage.UNKNOWN,
    val storeName: String? = null,
    val expireDateText: String? = null,
    val isPickedUp: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val historyDetails: List<LogisticsHistoryItem> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("carrier", carrier.id)
        put("trackingNumber", trackingNumber)
        put("note", note)
        put("latestStatus", latestStatus)
        put("stage", stage.name)
        put("storeName", storeName ?: "")
        put("expireDateText", expireDateText ?: "")
        put("isPickedUp", isPickedUp)
        put("updatedAtMillis", updatedAtMillis)
        val arr = JSONArray()
        historyDetails.forEach { h ->
            arr.put(JSONObject().apply {
                put("time", h.time)
                put("description", h.description)
            })
        }
        put("historyDetails", arr)
    }

    companion object {
        fun fromJson(json: JSONObject): LogisticsRecord {
            val carrier = LogisticsCarrier.fromId(json.optString("carrier", "711"))
            val stage = runCatching { LogisticsStage.valueOf(json.optString("stage", LogisticsStage.UNKNOWN.name)) }
                .getOrDefault(LogisticsStage.UNKNOWN)
            val historyList = mutableListOf<LogisticsHistoryItem>()
            val arr = json.optJSONArray("historyDetails")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    historyList.add(
                        LogisticsHistoryItem(
                            time = item.optString("time"),
                            description = item.optString("description")
                        )
                    )
                }
            }
            return LogisticsRecord(
                id = json.optString("id", UUID.randomUUID().toString()),
                carrier = carrier,
                trackingNumber = json.optString("trackingNumber", ""),
                note = json.optString("note", ""),
                latestStatus = json.optString("latestStatus", "尚未取得貨況資訊"),
                stage = stage,
                storeName = json.optString("storeName").takeIf { it.isNotBlank() },
                expireDateText = json.optString("expireDateText").takeIf { it.isNotBlank() },
                isPickedUp = json.optBoolean("isPickedUp", false),
                updatedAtMillis = json.optLong("updatedAtMillis", System.currentTimeMillis()),
                historyDetails = historyList
            )
        }
    }
}

class LogisticsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("logistics_records_v1", Context.MODE_PRIVATE)

    fun loadAll(): List<LogisticsRecord> {
        val raw = prefs.getString("records_list", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val list = mutableListOf<LogisticsRecord>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                list.add(LogisticsRecord.fromJson(obj))
            }
            list.sortedByDescending { it.updatedAtMillis }
        }.getOrDefault(emptyList())
    }

    fun save(record: LogisticsRecord) {
        val current = loadAll().toMutableList()
        val index = current.indexOfFirst { it.id == record.id }
        if (index >= 0) {
            current[index] = record
        } else {
            current.add(0, record)
        }
        persist(current)
    }

    fun markPickedUp(id: String, pickedUp: Boolean): LogisticsRecord? {
        val current = loadAll().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return null
        val updated = current[index].copy(
            isPickedUp = pickedUp,
            stage = if (pickedUp) LogisticsStage.PICKED_UP else current[index].stage,
            updatedAtMillis = System.currentTimeMillis()
        )
        current[index] = updated
        persist(current)
        return updated
    }

    fun delete(id: String) {
        val current = loadAll().toMutableList()
        current.removeAll { it.id == id }
        persist(current)
    }

    private fun persist(list: List<LogisticsRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("records_list", arr.toString()).apply()
    }
}

data class CaptchaSession(
    val carrier: LogisticsCarrier,
    val cookieHeader: String,
    val viewstate: String = "",
    val viewstateGenerator: String = "",
    val famiCode: String = "",
    val hilifeToken: String = "",
    val imageBase64: String = "",
    val svgData: String = ""
)

data class LogisticsQueryResult(
    val success: Boolean,
    val statusText: String,
    val stage: LogisticsStage,
    val storeName: String? = null,
    val expireDateText: String? = null,
    val history: List<LogisticsHistoryItem> = emptyList(),
    val errorMessage: String? = null
)

object LogisticsClient {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    suspend fun fetchCaptcha(carrier: LogisticsCarrier): CaptchaSession = withContext(Dispatchers.IO) {
        when (carrier) {
            LogisticsCarrier.SEVEN_ELEVEN -> fetch711Captcha()
            LogisticsCarrier.FAMILY_MART -> fetchFamiCaptcha()
            LogisticsCarrier.HI_LIFE -> fetchHilifeCaptcha()
            else -> CaptchaSession(carrier, "", "", "", "", "", "", "")
        }
    }

    private fun fetch711Captcha(): CaptchaSession {
        val url = URL("https://eservice.7-11.com.tw/e-tracking/search.aspx")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.instanceFollowRedirects = true

        val cookies = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") } ?: ""
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val vs = Regex("id=\"__VIEWSTATE\"\\s+value=\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val vsg = Regex("id=\"__VIEWSTATEGENERATOR\"\\s+value=\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val ts = Regex("src=\"(?:ValidateImage|checkcode)\\.aspx\\?([^\"']+)\"").find(html)?.groupValues?.get(1).orEmpty()

        var imgBase64 = ""
        if (ts.isNotBlank()) {
            val imgUrl = URL("https://eservice.7-11.com.tw/e-tracking/ValidateImage.aspx?$ts")
            val imgConn = imgUrl.openConnection() as HttpURLConnection
            imgConn.requestMethod = "GET"
            imgConn.setRequestProperty("User-Agent", USER_AGENT)
            imgConn.setRequestProperty("Referer", "https://eservice.7-11.com.tw/e-tracking/search.aspx")
            if (cookies.isNotBlank()) imgConn.setRequestProperty("Cookie", cookies)
            imgConn.connectTimeout = 8000
            imgConn.readTimeout = 8000
            val bytes = imgConn.inputStream.use { it.readBytes() }
            imgConn.disconnect()
            imgBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        return CaptchaSession(
            carrier = LogisticsCarrier.SEVEN_ELEVEN,
            cookieHeader = cookies,
            viewstate = vs,
            viewstateGenerator = vsg,
            imageBase64 = imgBase64
        )
    }

    private fun fetchFamiCaptcha(): CaptchaSession {
        val url = URL("https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx/GetVerificationCode")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Referer", "https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        conn.outputStream.use { it.write("{}".toByteArray()) }
        val cookies = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") } ?: ""
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(resp)
        val inner = JSONObject(json.optString("d", "{}"))
        val code = inner.optString("Code", "")

        var imgBase64 = ""
        if (code.isNotBlank()) {
            val encCode = URLEncoder.encode(code, "UTF-8")
            val imgUrl = URL("https://ecfme.fme.com.tw/FMEDCFPWebV2_II/CodeHandler.ashx?Code=$encCode")
            val imgConn = imgUrl.openConnection() as HttpURLConnection
            imgConn.requestMethod = "GET"
            imgConn.setRequestProperty("User-Agent", USER_AGENT)
            imgConn.setRequestProperty("Referer", "https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx")
            if (cookies.isNotBlank()) imgConn.setRequestProperty("Cookie", cookies)
            imgConn.connectTimeout = 8000
            imgConn.readTimeout = 8000
            val bytes = imgConn.inputStream.use { it.readBytes() }
            imgConn.disconnect()
            imgBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }

        return CaptchaSession(
            carrier = LogisticsCarrier.FAMILY_MART,
            cookieHeader = cookies,
            famiCode = code,
            imageBase64 = imgBase64
        )
    }

    private fun fetchHilifeCaptcha(): CaptchaSession {
        return runCatching {
            val url = URL("https://www.hilife.com.tw/webapi/api/Captcha/Generate")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", "https://www.hilife.com.tw/shipment-status")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val cookies = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(";") } ?: ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val data = json.optJSONObject("Data")
            val svg = data?.optString("Svg").orEmpty()
            val token = data?.optString("Token").orEmpty()

            CaptchaSession(
                carrier = LogisticsCarrier.HI_LIFE,
                cookieHeader = cookies,
                hilifeToken = token,
                svgData = svg
            )
        }.getOrElse {
            CaptchaSession(
                carrier = LogisticsCarrier.HI_LIFE,
                cookieHeader = "",
                hilifeToken = "",
                svgData = ""
            )
        }
    }

    suspend fun query(
        carrier: LogisticsCarrier,
        trackingNumber: String,
        captchaCode: String = "",
        session: CaptchaSession? = null
    ): LogisticsQueryResult = withContext(Dispatchers.IO) {
        val cleanNumber = trackingNumber.trim()
        if (cleanNumber.isBlank()) {
            return@withContext LogisticsQueryResult(
                success = false,
                statusText = "請輸入有效的物流單號",
                stage = LogisticsStage.UNKNOWN,
                errorMessage = "物流單號不可為空"
            )
        }

        when (carrier) {
            LogisticsCarrier.SPX -> querySpx(cleanNumber)
            LogisticsCarrier.SEVEN_ELEVEN -> query711(cleanNumber, captchaCode.trim(), session)
            LogisticsCarrier.FAMILY_MART -> queryFami(cleanNumber, captchaCode.trim(), session)
            LogisticsCarrier.HI_LIFE -> queryHilife(cleanNumber, captchaCode.trim(), session)
            LogisticsCarrier.OK_MART -> LogisticsQueryResult(
                success = true,
                statusText = "OK 超商系統官方維護中，已保存此單號至您的包裹追蹤清單。",
                stage = LogisticsStage.UNKNOWN
            )
        }
    }

    private fun querySpx(trackingNumber: String): LogisticsQueryResult {
        val enc = URLEncoder.encode(trackingNumber, "UTF-8")
        val url = URL("https://spx.tw/api/v2/fleet_order/tracking/search?sls_tracking_number=$enc")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Referer", "https://spx.tw/")
        conn.setRequestProperty("Accept", "application/json, text/plain, */*")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(text)
        val data = json.optJSONObject("data")
        val records = data?.optJSONArray("records")
        if (records == null || records.length() == 0) {
            return LogisticsQueryResult(
                success = false,
                statusText = "查無此單號貨況或尚未交寄",
                stage = LogisticsStage.UNKNOWN,
                errorMessage = "查無包裹資訊，請確認單號是否正確"
            )
        }

        val history = mutableListOf<LogisticsHistoryItem>()
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN)
        for (i in 0 until records.length()) {
            val rec = records.getJSONObject(i)
            val timeSec = rec.optLong("status_time", 0L)
            val timeStr = if (timeSec > 0) sdf.format(Date(timeSec * 1000L)) else ""
            val desc = rec.optString("status_description", "")
            if (desc.isNotBlank()) {
                history.add(LogisticsHistoryItem(timeStr, desc))
            }
        }

        val latest = history.firstOrNull()?.description ?: "運送中"
        val stage = analyzeStage(latest)
        val storeMatch = Regex("【([^】]+)】").find(latest)?.groupValues?.get(1)

        return LogisticsQueryResult(
            success = true,
            statusText = latest,
            stage = stage,
            storeName = storeMatch,
            history = history
        )
    }

    private fun query711(trackingNumber: String, captchaCode: String, session: CaptchaSession?): LogisticsQueryResult {
        if (session == null || session.cookieHeader.isBlank()) {
            return LogisticsQueryResult(false, "驗證碼過期，請重新整理驗證碼", LogisticsStage.UNKNOWN, errorMessage = "請重新整理驗證碼")
        }
        val url = URL("https://eservice.7-11.com.tw/e-tracking/search.aspx")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", "https://eservice.7-11.com.tw/e-tracking/search.aspx")
        conn.setRequestProperty("Cookie", session.cookieHeader)
        conn.connectTimeout = 12000
        conn.readTimeout = 12000

        val encVs = URLEncoder.encode(session.viewstate, "UTF-8")
        val encVsg = URLEncoder.encode(session.viewstateGenerator, "UTF-8")
        val encNum = URLEncoder.encode(trackingNumber, "UTF-8")
        val encCode = URLEncoder.encode(captchaCode, "UTF-8")

        val postData = "__EVENTTARGET=&__EVENTARGUMENT=" +
                "&__VIEWSTATE=$encVs&__VIEWSTATEGENERATOR=$encVsg" +
                "&txtProductNum=$encNum&tb_OrderNo=$encNum" +
                "&tbChkCode=$encCode&tb_CheckCode=$encCode" +
                "&aaa=&btn_Submit=查詢"

        conn.outputStream.use { it.write(postData.toByteArray()) }
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        if (html.contains("驗證碼錯誤") || html.contains("驗證碼輸入錯誤")) {
            return LogisticsQueryResult(false, "驗證碼錯誤，請重新整理並輸入", LogisticsStage.UNKNOWN, errorMessage = "驗證碼輸入不正確")
        }

        val cleanHtml = html.replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>"), "")

        // 1. 抓取取貨門市 (7-11 取貨門市格式: 198129 崇倫門市 (門市地址))
        val storeMatch = Regex("(?:取貨門市|門市名稱)[^<]*</div>\\s*<div[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
            .find(cleanHtml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            ?: Regex("(?:取貨門市|門市名稱)[：:\\s]*([^<\\r\\n]+)").find(cleanHtml)?.groupValues?.get(1)?.trim()

        // 2. 抓取取件期限
        val expireMatch = Regex("(?:取件期限|領取期限)[^<]*</div>\\s*<div[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE)
            .find(cleanHtml)?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            ?: Regex("(?:取件期限|領取期限)[：:\\s]*([\\d/\\-]+)").find(cleanHtml)?.groupValues?.get(1)?.trim()

        // 3. 抓取貨態歷程清單 (表格: 時間 / 貨態 / 詳細說明)
        val history = mutableListOf<LogisticsHistoryItem>()
        val rowRegex = Regex("<tr[^>]*>\\s*<td[^>]*>([\\s\\S]*?)</td>\\s*<td[^>]*>([\\s\\S]*?)</td>\\s*<td[^>]*>([\\s\\S]*?)</td>\\s*</tr>", RegexOption.IGNORE_CASE)
        rowRegex.findAll(cleanHtml).forEach { m ->
            val time = m.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            val status = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            val detail = m.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
            if (time.isNotBlank() && (status.isNotBlank() || detail.isNotBlank()) && !time.contains("時間")) {
                val fullDesc = if (status.isNotBlank() && detail.isNotBlank() && status != detail) "$status ($detail)" else status.ifBlank { detail }
                history.add(LogisticsHistoryItem(time, fullDesc))
            }
        }

        // 4. 最新狀態判定
        val statusText = history.firstOrNull()?.description
            ?: Regex("(?:貨態|包裹狀態|最新狀態)[：:\\s]*([^<\\r\\n]+)").find(cleanHtml)?.groupValues?.get(1)?.trim()
            ?: if (cleanHtml.contains("查無")) "查無此單號資料或尚未建檔" else "包裹處理中"

        val stage = analyzeStage(statusText, expireMatch)

        return LogisticsQueryResult(
            success = true,
            statusText = statusText,
            stage = stage,
            storeName = storeMatch,
            expireDateText = expireMatch,
            history = history
        )
    }

    private fun queryFami(trackingNumber: String, captchaCode: String, session: CaptchaSession?): LogisticsQueryResult {
        if (session == null || session.famiCode.isBlank()) {
            return LogisticsQueryResult(false, "驗證碼已過期，請重新整理驗證碼", LogisticsStage.UNKNOWN, errorMessage = "請重新整理驗證碼")
        }

        // 1. Check verification code
        val chkUrl = URL("https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx/ChkVerificationCode")
        val chkConn = chkUrl.openConnection() as HttpURLConnection
        chkConn.requestMethod = "POST"
        chkConn.doOutput = true
        chkConn.setRequestProperty("User-Agent", USER_AGENT)
        chkConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        chkConn.setRequestProperty("Referer", "https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx")
        if (session.cookieHeader.isNotBlank()) chkConn.setRequestProperty("Cookie", session.cookieHeader)
        chkConn.connectTimeout = 8000
        chkConn.readTimeout = 8000

        val chkBody = JSONObject().apply {
            put("P_CODE", captchaCode)
            put("P_VCODE", session.famiCode)
        }.toString()
        chkConn.outputStream.use { it.write(chkBody.toByteArray()) }

        val chkResp = chkConn.inputStream.bufferedReader().use { it.readText() }
        chkConn.disconnect()

        val chkJson = JSONObject(chkResp)
        val returnObj = JSONObject(chkJson.optString("d", "{}"))
        if (returnObj.optString("success") != "1") {
            val msg = returnObj.optString("msg", "驗證碼不正確").replace("証", "證")
            return LogisticsQueryResult(false, msg, LogisticsStage.UNKNOWN, errorMessage = msg)
        }

        // 2. Query list
        val listUrl = URL("https://ecfme.fme.com.tw/FMEDCFPWebV2_II/list.aspx")
        val listConn = listUrl.openConnection() as HttpURLConnection
        listConn.requestMethod = "POST"
        listConn.doOutput = true
        listConn.setRequestProperty("User-Agent", USER_AGENT)
        listConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        listConn.setRequestProperty("Referer", "https://ecfme.fme.com.tw/FMEDCFPWebV2_II/index.aspx")
        if (session.cookieHeader.isNotBlank()) listConn.setRequestProperty("Cookie", session.cookieHeader)
        listConn.connectTimeout = 10000
        listConn.readTimeout = 10000

        val listBody = "ORDER_NO=" + URLEncoder.encode(trackingNumber, "UTF-8")
        listConn.outputStream.use { it.write(listBody.toByteArray()) }

        val listHtml = listConn.inputStream.bufferedReader().use { it.readText() }
        listConn.disconnect()

        val cleanHtml = listHtml.replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>"), "")
        val statusCandidates = listOf(
            Regex("ORDERMESSAGE[\"':\\s]*[\"']?([^\"'<]+)").find(listHtml)?.groupValues?.get(1),
            Regex("<span class=\"blue-note\">([^<]+)</span>").find(cleanHtml)?.groupValues?.get(1),
            Regex("<span class=\"red-note\">([^<]+)</span>").find(cleanHtml)?.groupValues?.get(1),
            Regex("(包裹配達取件門市|商品配送中|已交寄|查無訂單資料)").find(cleanHtml)?.groupValues?.get(1)
        ).filterNotNull().filter { it.isNotBlank() }

        val statusText = statusCandidates.firstOrNull() ?: if (cleanHtml.contains("查無")) "查無此訂單包裹資料" else "包裹處理中"
        val storeMatch = Regex("(?:取件門市|店舖名稱|取件店舖)[：:\\s]*([^<\\r\\n]+)").find(cleanHtml)?.groupValues?.get(1)?.trim()
        val stage = analyzeStage(statusText)

        return LogisticsQueryResult(
            success = true,
            statusText = statusText,
            stage = stage,
            storeName = storeMatch
        )
    }

    private fun queryHilife(trackingNumber: String, captchaCode: String, session: CaptchaSession?): LogisticsQueryResult {
        return runCatching {
            val url = URL("https://www.hilife.com.tw/webapi/api/Shipment/GetShipmentStatus")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Referer", "https://www.hilife.com.tw/shipment-status")
            if (session != null && session.cookieHeader.isNotBlank()) {
                conn.setRequestProperty("Cookie", session.cookieHeader)
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val postPayload = JSONObject().apply {
                put("order_no", trackingNumber)
                put("captcha", captchaCode)
            }.toString()

            conn.outputStream.use { it.write(postPayload.toByteArray()) }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(resp)
            if (json.optInt("Code", 0) != 200) {
                val err = json.optString("Message", "查詢失敗，請檢查單號或驗證碼")
                return LogisticsQueryResult(false, err, LogisticsStage.UNKNOWN, errorMessage = err)
            }

            val data = json.optJSONObject("Data")
            val store = data?.optString("PickupStore").takeIf { !it.isNullOrBlank() }
            val timelineArr = data?.optJSONArray("StatusTimeline")
            val history = mutableListOf<LogisticsHistoryItem>()
            var latestStatusName = "運送中"

            if (timelineArr != null && timelineArr.length() > 0) {
                for (i in 0 until timelineArr.length()) {
                    val stepObj = timelineArr.getJSONObject(i)
                    val sName = stepObj.optString("StatusName")
                    val sTime = stepObj.optString("StatusTime")
                    if (sName.isNotBlank()) {
                        history.add(LogisticsHistoryItem(sTime, sName))
                        latestStatusName = sName
                    }
                }
            }

            val stage = analyzeStage(latestStatusName)

            LogisticsQueryResult(
                success = true,
                statusText = latestStatusName,
                stage = stage,
                storeName = store,
                history = history
            )
        }.getOrElse {
            LogisticsQueryResult(
                success = true,
                statusText = "已登記萊爾富包裹 $trackingNumber，可點擊「開啟官方頁面」查看最新明細。",
                stage = LogisticsStage.TRANSIT
            )
        }
    }

    private fun analyzeStage(text: String, expireDateText: String? = null): LogisticsStage {
        val lower = text.lowercase()
        if (lower.contains("已取") || lower.contains("取件完成") || lower.contains("領取完成")) {
            return LogisticsStage.PICKED_UP
        }
        if (lower.contains("退回") || lower.contains("快到期") || lower.contains("逾期") || lower.contains("即將退")) {
            return LogisticsStage.EXPIRING
        }
        if (expireDateText != null) {
            runCatching {
                val sdf = SimpleDateFormat("yyyy/MM/dd", Locale.TAIWAN)
                val expire = sdf.parse(expireDateText.trim())
                if (expire != null) {
                    val diffDays = (expire.time - System.currentTimeMillis()) / (24 * 60 * 60 * 1000L)
                    if (diffDays in 0..2) return LogisticsStage.EXPIRING
                }
            }
        }
        if (lower.contains("到達") || lower.contains("配達") || lower.contains("可取件") || lower.contains("待取件") ||
            lower.contains("送達門市") || lower.contains("貨到了") || lower.contains("已到店") || lower.contains("送達")) {
            return LogisticsStage.ARRIVED
        }
        if (lower.contains("理貨") || lower.contains("運送") || lower.contains("配送") || lower.contains("出貨") ||
            lower.contains("物流中心") || lower.contains("交寄") || lower.contains("離開門市") || lower.contains("寄件成立")) {
            return LogisticsStage.TRANSIT
        }
        return LogisticsStage.UNKNOWN
    }
}
