package com.aykkdk.shop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RefreshResult(val alerts: List<ProductSubscriptionAlert> = emptyList(), val error: String? = null, val pending: Boolean = false)

/** Manual and automatic checks share exactly the same parsers and retained data. */
internal object RefreshRunner {
    private val readerLock = Mutex()
    fun subscriptions(context: Context): List<RefreshTarget> =
        context.getSharedPreferences("shop_subscriptions_v2", 0).all.keys
            .filter { Market.fromPublicUrl(it) != null }.map { RefreshTarget(RefreshKind.SHOP, it) } +
            ProductSubscriptionStore(context).list().map { RefreshTarget(RefreshKind.PRODUCT, it.optString("url")) }

    suspend fun refresh(context: Context, target: RefreshTarget, background: Boolean = false, allowed: () -> Boolean = { true }): RefreshResult = readerLock.withLock {
        if (!allowed()) return@withLock RefreshResult()
        val run = java.util.UUID.randomUUID().toString()
        recordAttempt(context, target, run, allowed)
        val result = try { withTimeoutOrNull(120_000) {
            val job = currentCoroutineContext()[Job]
            val permitted = { job?.isActive != false && allowed() }
            when (target.kind) {
                RefreshKind.PRODUCT -> {
                    val store = ProductSubscriptionStore(context)
                    val alerts = store.refreshAll(setOf(target.url), retainAlerts = background, allowed = permitted)
                    val error = store.prefs.getString(target.url, null)?.let { JSONObject(it).optString("lastCheckError").ifBlank { null } }
                    RefreshResult(alerts, error)
                }
                RefreshKind.AUCTION -> RefreshResult(error = AuctionRefresher.refresh(context, setOf(target.url), notifySystem = !background, backgroundDelivery = background, allowed = permitted).firstOrNull())
                RefreshKind.SHOP -> refreshShop(context, target.url, background, permitted)
            }
        } ?: RefreshResult(error = "刷新逾時；保留原有資料與比對基準。")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { RefreshResult(error = if (error is IllegalStateException || error is IllegalArgumentException)
            error.message?.replace(Regex("https?://\\S+"), "[網址已隱藏]")?.take(240)?.takeIf { it.isNotBlank() }
                ?: "暫時無法刷新；保留原有資料與比對基準。"
            else "暫時無法刷新；保留原有資料與比對基準。") }
        if (allowed()) recordError(context, target, run, result, allowed)
        result
    }

    private fun recordAttempt(context: Context, target: RefreshTarget, run: String, allowed: () -> Boolean) {
        if (target.kind == RefreshKind.AUCTION) return
        val prefs = context.getSharedPreferences(if (target.kind == RefreshKind.SHOP) "shop_subscriptions_v2" else "product_subscriptions_v1", 0)
        synchronized(SubscriptionMutations.lock) {
            if (!allowed()) return
            val raw = prefs.getString(target.url, null) ?: return
            val item = JSONObject(raw)
            RefreshAttemptRecord.begin(item, run, System.currentTimeMillis())
            prefs.edit().putString(target.url, item.toString()).commit()
        }
    }

    private fun recordError(context: Context, target: RefreshTarget, run: String, result: RefreshResult, allowed: () -> Boolean) {
        val name = when (target.kind) {
            RefreshKind.SHOP -> "shop_subscriptions_v2"
            RefreshKind.PRODUCT -> "product_subscriptions_v1"
            RefreshKind.AUCTION -> return // AuctionRefresher already retains per-auction errors.
        }
        val prefs = context.getSharedPreferences(name, 0)
        synchronized(SubscriptionMutations.lock) {
            if (!allowed()) return
            val raw = prefs.getString(target.url, null) ?: return
            val item = JSONObject(raw)
            if (!RefreshAttemptRecord.finish(item, run, result, System.currentTimeMillis())) return
            prefs.edit().putString(target.url, item.toString()).commit()
        }
    }

    private suspend fun refreshShop(context: Context, url: String, background: Boolean, allowed: () -> Boolean): RefreshResult {
        val prefs = context.getSharedPreferences("shop_subscriptions_v2", 0)
        if (!prefs.contains(url) || !allowed()) return RefreshResult()
        val saved = shopSubscriptionRecord(url, prefs.getString(url, null))
        val market = Market.fromSubscription(saved.optString("platform"), url) ?: return RefreshResult(error = "無法辨識店家平台。")
        fun saveName(name: String) = synchronized(SubscriptionMutations.lock) {
            if (allowed() && prefs.contains(url)) {
                val latest = shopSubscriptionRecord(url, prefs.getString(url, null))
                if (latest.optString("name") != name) {
                    latest.put("name", name)
                    prefs.edit().putString(url, latest.toString()).commit()
                }
            }
        }
        // Existing names do not need a second HTTP page on every scheduled check.
        val needsName = PublicShopMetadata.needsRead(url, saved.optString("name"), market)
        (if (needsName) PublicShopMetadata.read(url) else null)?.let { name ->
            synchronized(SubscriptionMutations.lock) {
                if (allowed() && prefs.contains(url)) {
                    val latest = shopSubscriptionRecord(url, prefs.getString(url, null))
                    latest.put("name", name)
                    prefs.edit().putString(url, latest.toString()).commit()
                }
            }
        }
        val checkpoint = saved.optLong("listingCheckpointAt", saved.optLong("rutenCheckpointAt"))
        val seen = saved.optJSONArray("seen")?.let { data -> (0 until data.length()).map { data.optString(it) }.toSet() }.orEmpty()
        val previous = saved.optJSONObject("refreshScan")?.takeIf { it.optLong("checkpoint") == checkpoint }
        val pending = previous?.optJSONArray("products")?.let { data ->
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(Product::parse) }
        }.orEmpty()
        var state = SubscriptionNewItemsPaging(checkpoint, seen).copy(collected = pending)
        var page = previous?.optInt("page", 1)?.coerceIn(1, 50) ?: 1
        val initialize = !saved.has("seen") && checkpoint == 0L
        val alerts = mutableListOf<ProductSubscriptionAlert>()
        fun saveCover(products: List<Product>) {
            synchronized(SubscriptionMutations.lock) {
                if (!allowed() || !prefs.contains(url)) return
                val latest = shopSubscriptionRecord(url, prefs.getString(url, null))
                val profileName = if (market == Market.CAROUSELL) products.map { it.shop.trim() }
                    .filter { it.isNotBlank() }.distinct().singleOrNull() else null
                val nameChanged = profileName != null && latest.optString("name") != profileName
                if (nameChanged) latest.put("name", profileName)
                val coverChanged = ShopSubscriptionCover.update(latest, products)
                if (coverChanged || nameChanged)
                    prefs.edit().putString(url, latest.toString()).commit()
            }
        }
        fun persistScan(found: List<Product>, complete: Boolean, nextPage: Int): Boolean {
            val update = synchronized(SubscriptionMutations.lock) {
                if (!allowed()) return false
                val data = ShopRefreshUpdate.build(url, prefs.getString(url, null), market, checkpoint, seen,
                    found, state.collected, initialize, complete, nextPage, System.currentTimeMillis()) ?: return false
                if (background) BackgroundAlertJournal.append(data.record, data.alerts)
                if (!prefs.edit().putString(url, data.record.toString()).commit()) return false
                data
            }
            if (background && update.alerts.isNotEmpty()) PendingAlertDelivery.enqueue(context)
            alerts.addAll(update.alerts)
            return true
        }
        var pageNotified = false
        fun saveProgress(products: List<Product>) {
            val found = ShopCheckpoint.freeze(products, System.currentTimeMillis())
            saveCover(found)
            if (initialize || !allowed()) return
            val before = state.collected.size
            state = state.observe(found)
            if (state.collected.size <= before || !persistScan(found, complete = false, nextPage = page)) return
            // Save each confirmed prefix before a later unknown date, timeout or OS stop.
            // Keep this source page as the retry position until the final callback.
            if (background && !pageNotified) {
                pageNotified = BackgroundAlertDelivery.drain(context, RefreshTarget(RefreshKind.SHOP, url), allowed) == "notification_sent"
            }
        }
        // Continue long stores over later intervals without rereading the first pages
        // or moving the watermark before an equal/older listing has actually appeared.
        repeat(3) {
            currentCoroutineContext().ensureActive()
            if (!allowed() || !prefs.contains(url)) return RefreshResult(alerts)
            val previousCount = state.collected.size
            pageNotified = false
            val read = readShopPage(context, SearchCapture(market, market.shopPage(url, page), page,
                loadMore = market == Market.CAROUSELL && page > 1, moreClicks = page - 1,
                subscriptionCheckpoint = checkpoint, subscriptionSeen = seen,
                scanSubscription = !initialize, backgroundRead = true), ::saveProgress, ::saveName)
            val found = read.products
            saveProgress(found)
            if (found.isEmpty() && !read.exhausted) return RefreshResult(alerts, "未取得可讀商品；保留比對基準。")
            state = state.observe(found)
            if (read.exhausted) state = state.copy(reachedOld = true)
            if (state.awaitingTime) return RefreshResult(alerts, "未確認商品上架時間；保留比對基準。")
            val complete = initialize || state.reachedOld
            if (!complete && (page >= 50 || state.collected.size == previousCount && page > 1))
                return RefreshResult(alerts, "尚未讀到舊商品；保留比對基準。")
            currentCoroutineContext().ensureActive()
            if (!allowed() || !prefs.contains(url)) return RefreshResult(alerts)
            if (!persistScan(found, complete, page + 1)) return RefreshResult(alerts)
            // Publish a confirmed page immediately, not after every remaining page.
            if (background) BackgroundAlertDelivery.drain(context, RefreshTarget(RefreshKind.SHOP, url), allowed)
            if (complete) return RefreshResult(alerts)
            page++
        }
        return RefreshResult(alerts, pending = true)
    }

    private suspend fun readShopPage(context: Context, request: SearchCapture, onProgress: (List<Product>) -> Unit, onShopName: (String) -> Unit): ShopPageRead {
        if (request.market == Market.RUTEN) return RutenShopReader.read(request)
        return withContext(Dispatchers.Main.immediate) {
            var controller: HiddenCaptureController? = null
            val readerJob = SupervisorJob(currentCoroutineContext()[Job])
            val readerScope = CoroutineScope(Dispatchers.Main.immediate + readerJob)
            try {
                withTimeout(70_000) {
                    suspendCancellableCoroutine { continuation ->
                        controller = HiddenCaptureController(context.applicationContext, request, readerScope,
                            { found -> if (continuation.isActive) continuation.resume(ShopPageRead(
                                ShopCheckpoint.freeze(found, System.currentTimeMillis()),
                                exhausted = request.market == Market.YAHOO && found.isEmpty())) },
                            { error -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error)) },
                            { _, _ -> }, onProgress, onShopName = onShopName)
                        // No Activity/window is opened. The renderer exists only for this bounded read.
                        val metrics = context.resources.displayMetrics
                        controller!!.web.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels.coerceAtLeast(360), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels.coerceAtLeast(640), View.MeasureSpec.EXACTLY))
                        controller!!.web.layout(0, 0, controller!!.web.measuredWidth, controller!!.web.measuredHeight)
                    }
                }
            } finally { readerJob.cancel(); controller?.close() }
        }
    }
}

internal object SubscriptionRefreshNotifications {
    fun show(context: Context, target: RefreshTarget, alerts: List<ProductSubscriptionAlert>, record: JSONObject? = null): Boolean {
        val content = SubscriptionNotificationText.build(target, alerts, record) ?: return true
        val channel = RefreshNotificationAccess.CHANNEL_SUBSCRIPTIONS
        RefreshNotificationAccess.createChannels(context)
        if (!RefreshNotificationAccess.enabled(context, channel)) return false
        val id = target.key.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(SubscriptionNotificationRoute.ACTION)
            .setData(android.net.Uri.parse(SubscriptionNotificationRoute.identity(target)))
            .putExtra(SubscriptionNotificationRoute.EXTRA_KIND, target.kind.name)
            .putExtra(SubscriptionNotificationRoute.EXTRA_URL, target.url)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(content.title)
            .setContentText(content.text).setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(pending).setAutoCancel(true)
        if (target.kind == RefreshKind.SHOP) {
            val eventAt = SubscriptionNotificationOrder.eventTime(alerts, record, System.currentTimeMillis())
            builder.setWhen(eventAt).setShowWhen(true).setSortKey(SubscriptionNotificationOrder.sortKey(eventAt, target))
        }
        return try {
            NotificationManagerCompat.from(context).notify("subscription-updates", id, builder.build())
            true
        } catch (_: SecurityException) { false /* Keep the journal for a later permitted background delivery. */ }
    }
}
