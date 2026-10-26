package com.example.shopradar

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

class AuctionStore(private val context: Context) {
    val prefs = context.getSharedPreferences("auctions_v2", Context.MODE_PRIVATE)
    fun list(): List<JSONObject> = synchronized(lock) {
        val a = JSONArray(prefs.getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun username(market: Market = Market.YAHOO) = prefs.getString(if (market == Market.RUTEN) "rutenUsername" else "username", "").orEmpty()
    fun accountFor(url: String) = username(Market.fromPublicProductUrl(url) ?: Market.YAHOO)
    fun username(value: String, market: Market = Market.YAHOO) = synchronized(lock) {
        val items = list().onEach {
            if (Market.fromPublicProductUrl(it.optString("url")) != market) return@onEach
            if (it.optString("displayState") !in setOf("ended", "cancelled"))
                it.put("state", "unknown").put("displayState", "unknown")
            AuctionAlertPolicy.reset(it, value)
            it.put("error", "")
            cancelOutbidNotification(context, it.optString("url"))
        }
        prefs.edit().putString(if (market == Market.RUTEN) "rutenUsername" else "username", value.trim()).putString("items", JSONArray(items).toString()).commit()
    }
    fun add(url: String) = synchronized(lock) {
        val items = list().toMutableList()
        require(items.none { SubscriptionIdentity.sameProduct(it.optString("url"), url) }) { SubscriptionIdentity.DUPLICATE_MESSAGE }
        require(items.size < 30) { "最多追蹤 30 筆競拍。" }
        items.add(JSONObject().put("url", url).put("title", "等待取得商品資料").put("state", "unknown")
            .put("trackingId", UUID.randomUUID().toString()).put("alertsEnabled", true))
        save(items)
    }
    fun remove(url: String) {
        synchronized(lock) {
            val items = list()
            ImageCachePolicy.removeProducts(context, listOf(url), items.filter { it.optString("url") == url }.map { it.optString("image") })
            save(items.filter { it.optString("url") != url })
            cancelOutbidNotification(context, url)
        }
        AutomaticRefreshStore(context).configure(RefreshTarget(RefreshKind.AUCTION, url), null)
    }
    fun update(url: String, action: (JSONObject) -> Unit) = synchronized(lock) {
        val items = list()
        items.firstOrNull { it.optString("url") == url }?.let(action)
        save(items)
    }
    fun alertsEnabled(url: String, enabled: Boolean) = update(url) {
        it.put("alertsEnabled", enabled)
        if (!enabled) { AuctionAlertPolicy.clearPending(it); cancelOutbidNotification(context, url) }
    }
    fun acknowledgeAlert(url: String, token: String) = update(url) {
        if (AuctionAlertPolicy.acknowledge(it, token)) cancelOutbidNotification(context, url)
    }
    internal fun deliverPending(url: String, action: (AuctionAlertNotice) -> Boolean) = synchronized(lock) {
        val items = list()
        val item = items.firstOrNull { it.optString("url") == url } ?: return@synchronized false
        if (item.optBoolean("isAdult") || !AdultVisibility.visible(item) || !AuctionAlertPolicy.needsSystemNotification(item)) return@synchronized false
        val notice = AuctionAlertPolicy.pending(item) ?: return@synchronized false
        if (AdultVisibility.adultText(notice.title)) return@synchronized false
        if (!action(notice)) return@synchronized false
        AuctionAlertPolicy.systemNotificationSent(item, notice.token)
        save(items)
        true
    }
    private fun save(items: List<JSONObject>) { prefs.edit().putString("items", JSONArray(items).toString()).commit() }
    companion object { private val lock = Any() }
}

class AuctionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // Drain persisted jobs from older versions without reviving the retired refresh path.
    override suspend fun doWork(): Result = Result.success()
}

internal object AuctionRefresher {
    private val networkSlots = Semaphore(3)
    private val active = mutableSetOf<String>()

    suspend fun refresh(context: Context, urls: Set<String>? = null, notifySystem: Boolean = true,
                        backgroundDelivery: Boolean = false,
                        allowed: () -> Boolean = { true }): List<String> = withContext(Dispatchers.IO) {
        val store = AuctionStore(context)
        coroutineScope {
        store.list().filter { urls == null || it.optString("url") in urls }.map { entry -> async {
            networkSlots.withPermit {
            val url = entry.getString("url")
            currentCoroutineContext().ensureActive()
            if (!allowed() || !synchronized(active) { active.add(url) }) return@withPermit null
            val job = currentCoroutineContext()[Job]
            val permitted = { job?.isActive != false && allowed() }
            val account = store.accountFor(url)
            try {
                val canonical = AuctionParser.canonicalUrl(url)
                val ruten = Market.fromPublicProductUrl(canonical) == Market.RUTEN
                val rutenData = if (ruten) RutenAuctionMetadata.read(RutenProductMetadata.productId(canonical)!!) else null
                val html = if (!ruten) readPublic(canonical) else ""
                val receivedAt = System.currentTimeMillis()
                var snapshot = if (rutenData != null) RutenAuctionMetadata.snapshot(rutenData, RutenProductMetadata.productId(canonical)!!, receivedAt)
                    else AuctionParser.parse(html, canonical.substringAfterLast('/'), receivedAt)
                require(kotlin.math.abs(snapshot.serverAt - receivedAt) < 10 * 60 * 1000) { "網站回傳過期資料或裝置時間不正確，暫不判定超標。" }
                var recordsError = ""
                if (permitted() && account.isNotBlank() && snapshot.bidCount > 0 &&
                    (ruten || BidDecision.state(snapshot, account) !in setOf("ended", "cancelled"))) {
                    try { snapshot = if (rutenData != null) RutenAuctionMetadata.withBidRecords(snapshot, rutenData, RutenProductMetadata.productId(canonical)!!, permitted)
                        else withBidRecords(snapshot, canonical, permitted) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { recordsError = "公開出價紀錄暫時無法讀取；仍可比對已確認的領先狀態。" }
                }
                val checkedAt = System.currentTimeMillis()
                currentCoroutineContext().ensureActive()
                if (!permitted()) return@withPermit null
                var token = ""
                store.update(url) { current ->
                    if (permitted() && store.accountFor(url) == account &&
                        current.optString("trackingId") == entry.optString("trackingId") && checkedAt >= current.optLong("checkedAt")) {
                        ImageCachePolicy.record(context, listOf(Product(snapshot.title, snapshot.price, if (ruten) Market.RUTEN.label else Market.YAHOO.label, url, snapshot.image)))
                        AuctionAlertPolicy.observe(current, snapshot, account, checkedAt)
                        token = AuctionAlertPolicy.pending(current)?.token.orEmpty()
                        current.put("title", snapshot.title).put("price", snapshot.price)
                            .put("endsAt", snapshot.endsAt).put("serverAt", snapshot.serverAt)
                            .put("image", snapshot.image).put("bidderIds", JSONArray(snapshot.bidderIds))
                            .put("bidderNames", JSONArray(snapshot.bidderNames)).put("bidCount", snapshot.bidCount)
                            .put("cancelled", snapshot.cancelled).put("startsAt", snapshot.startsAt)
                            .put("isAdult", current.optBoolean("isAdult") || AdultVisibility.adultText(snapshot.title) ||
                                rutenData?.optJSONObject("data")?.optString("g_class")?.let { AdultVisibility.adultCategory(Market.RUTEN, it) } == true)
                            .put("checkedAt", checkedAt).put("error", "").put("bidRecordsError", recordsError)
                        // The history is needed only for this decision. Persist the cycle, not
                        // hundreds of rows rewritten for every item on a 20-second timer.
                        if (recordsError.isBlank()) current.remove("bidRecords")
                        current.put("bidRecordsCount", snapshot.records.size).put("bidRecordsComplete", snapshot.recordsComplete)
                        if (token.isBlank()) cancelOutbidNotification(context, url)
                    }
                }
                // A foreground read may already have created this loss. Do not
                // discard its background notification merely because observe() is
                // now false; only a delivered/acknowledged token prevents replay.
                if (permitted() && token.isNotBlank() && backgroundDelivery) PendingAlertDelivery.enqueue(context)
                if (permitted() && notifySystem) AuctionNotifications.deliverPending(context, url)
                null
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                if (permitted()) {
                    val message = e.message ?: "無法連線，稍後重試。"
                    store.update(url) { if (permitted() && it.optString("trackingId") == entry.optString("trackingId") &&
                        store.accountFor(url) == account) it.put("error", message).put("attemptedAt", System.currentTimeMillis()) }
                    message
                } else null
            } finally { synchronized(active) { active.remove(url) } }
        } } }.awaitAll().filterNotNull()
        }
    }

    private suspend fun withBidRecords(snapshot: AuctionSnapshot, canonical: String, allowed: () -> Boolean): AuctionSnapshot {
        val records = mutableListOf<AuctionBidRecord>()
        var offset = 0
        // Bounded newest-first history, not an unbounded crawl on a 20-second timer.
        repeat(3) {
            currentCoroutineContext().ensureActive()
            if (!allowed()) throw CancellationException()
            val body = AuctionParser.bidRecordsRequest(canonical.substringAfterLast('/'), offset)
            val page = AuctionParser.parseBidRecords(readPublic("https://tw.bid.yahoo.com/fe/_reservice_/", body, canonical))
            records.addAll(page.records)
            offset += page.count
            if (offset >= page.total) return snapshot.copy(records = records, recordsComplete = true)
            if (page.count == 0) return snapshot.copy(records = records)
        }
        return snapshot.copy(records = records)
    }

    private suspend fun readPublic(url: String, body: String? = null, referer: String? = null): String = runInterruptible(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "ShopRadar/1.1 Android public-auction-monitor")
            connection.setRequestProperty("Cache-Control", "no-cache")
            // Never use CookieManager, Cookie headers or account credentials for these public reads.
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                referer?.let { connection.setRequestProperty("Referer", it) }
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            require(code == 200) { "網站回應 HTTP $code，稍後自動重試。" }
            connection.inputStream.use { it.readBytesLimited(2 * 1024 * 1024).toString(Charsets.UTF_8) }
        } finally { connection.disconnect() }
    }
}

private fun cancelOutbidNotification(context: Context, url: String) {
    NotificationManagerCompat.from(context).cancel(url.hashCode())
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= limit) { "頁面過大，暫停解析。" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal object AuctionNotifications {
    fun deliverPending(context: Context, url: String? = null): Boolean {
        val store = AuctionStore(context)
        var sent = false
        store.list().filter { url == null || it.optString("url") == url }.forEach { item ->
            if (store.deliverPending(item.optString("url")) { notice -> notifyOutbid(context, notice.url, notice.title, notice.price) }) sent = true
        }
        return sent
    }
}

private fun notifyOutbid(context: Context, url: String, title: String, price: Long): Boolean {
    val channel = RefreshNotificationAccess.CHANNEL_AUCTIONS
    RefreshNotificationAccess.createChannels(context)
    if (!RefreshNotificationAccess.enabled(context, channel)) return false
    val intent = Intent(context, MainActivity::class.java).putExtra("openAuctions", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, url.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return try {
        NotificationManagerCompat.from(context).notify(url.hashCode(), NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("競標已被超過：NT$$price")
            .setContentText(title).setContentIntent(pending).setAutoCancel(true).build())
        true
    } catch (_: SecurityException) { false /* The in-app alert remains available. */ }
}
