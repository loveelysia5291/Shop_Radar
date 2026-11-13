package com.aykkdk.shop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class AutomaticRefreshStore(context: Context) {
    private val app = context.applicationContext
    val prefs = app.getSharedPreferences("automatic_refresh_v1", Context.MODE_PRIVATE)
    fun generation() = prefs.getLong("generation", 0)
    fun sleep() = RefreshSleep(prefs.getBoolean("sleepEnabled", false),
        prefs.getInt("sleepStart", 23 * 60), prefs.getInt("sleepEnd", 7 * 60))
    fun plan(target: RefreshTarget): RefreshPlan? = decode(prefs.getString(target.key, null))
    fun entries(): List<Pair<RefreshTarget, RefreshPlan>> = prefs.all.mapNotNull { (key, raw) ->
        val kind = runCatching { RefreshKind.valueOf(key.substringBefore('|')) }.getOrNull() ?: return@mapNotNull null
        val plan = decode(raw as? String) ?: return@mapNotNull null
        RefreshTarget(kind, key.substringAfter('|')) to plan
    }
    fun isDisabled(target: RefreshTarget): Boolean =
        prefs.getStringSet("disabled_targets", emptySet())?.contains(target.key) == true
    fun configure(target: RefreshTarget, intervalMinutes: Int?) {
        synchronized(lock) {
            val disabled = (prefs.getStringSet("disabled_targets", emptySet()) ?: emptySet()).toMutableSet()
            val editor = prefs.edit().putLong("generation", generation() + 1)
            if (intervalMinutes == null) {
                editor.remove(target.key)
                disabled.add(target.key)
            } else {
                require(intervalMinutes in 15..10080)
                val at = System.currentTimeMillis() + intervalMinutes * 60_000L
                editor.putString(target.key, encode(RefreshPlan(intervalMinutes, at, revision = UUID.randomUUID().toString())))
                disabled.remove(target.key)
            }
            editor.putStringSet("disabled_targets", disabled)
            editor.commit()
        }
        if (intervalMinutes == null) BackgroundRefreshStatus(app).remove(target)
        AutomaticRefreshScheduler.replace(app)
    }
    fun remove(target: RefreshTarget) {
        synchronized(lock) {
            val disabled = (prefs.getStringSet("disabled_targets", emptySet()) ?: emptySet()).toMutableSet()
            disabled.remove(target.key)
            prefs.edit()
                .remove(target.key)
                .putStringSet("disabled_targets", disabled)
                .putLong("generation", generation() + 1)
                .commit()
        }
        BackgroundRefreshStatus(app).remove(target)
        AutomaticRefreshScheduler.replace(app)
    }
    fun setSleep(value: RefreshSleep) {
        synchronized(lock) {
            prefs.edit().putBoolean("sleepEnabled", value.enabled).putInt("sleepStart", value.start)
                .putInt("sleepEnd", value.end).putLong("generation", generation() + 1).commit()
        }
        AutomaticRefreshScheduler.replace(app)
    }
    fun exists(target: RefreshTarget): Boolean = Market.fromPublicUrl(target.url) != null && when (target.kind) {
        RefreshKind.SHOP -> app.getSharedPreferences("shop_subscriptions_v2", 0).contains(target.url)
        RefreshKind.PRODUCT -> app.getSharedPreferences("product_subscriptions_v1", 0).contains(target.url)
        RefreshKind.AUCTION -> AuctionStore(app).list().any { it.optString("url") == target.url }
    }
    fun allowed(target: RefreshTarget, expected: RefreshPlan): Boolean =
        plan(target)?.revision == expected.revision && exists(target) && !sleep().contains(System.currentTimeMillis())
    fun finish(target: RefreshTarget, expected: RefreshPlan, next: RefreshPlan) = synchronized(lock) {
        if (plan(target)?.revision == expected.revision) {
            if (exists(target)) prefs.edit().putString(target.key, encode(next)).commit()
            else prefs.edit().remove(target.key).commit()
        }
    }
    private fun decode(raw: String?): RefreshPlan? = runCatching {
        val item = JSONObject(raw ?: return null)
        RefreshPlan(item.getInt("interval"), item.getLong("cycleAt"), item.getLong("dueAt"), item.optInt("retry"), item.getString("revision"))
    }.getOrNull()
    private fun encode(plan: RefreshPlan) = JSONObject().put("interval", plan.intervalMinutes)
        .put("cycleAt", plan.cycleAt).put("dueAt", plan.dueAt).put("retry", plan.retry).put("revision", plan.revision).toString()
    companion object { internal val lock = Any() }
}

internal object RefreshNetwork {
    fun connected(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)
}

/** 
 * 強制移除 App 自己發送通知：
 * 原本地端 WorkManager 背景排程已停用，改由 Cloudflare Worker 全球邊緣節點 24 小時定時輪詢，
 * 並透過 Google FCM 即時推播喚醒手機。
 */
internal object AutomaticRefreshScheduler {
    internal const val NAME = "radar-automatic-refresh"
    private const val WATCHDOG = "radar-refresh-schedule-watchdog"

    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        val manager = WorkManager.getInstance(context)
        runCatching {
            manager.cancelAllWork()
            manager.cancelUniqueWork(NAME)
            manager.cancelUniqueWork(WATCHDOG)
            manager.cancelUniqueWork("auction-periodic")
            manager.cancelUniqueWork("auction-refresh")
            manager.cancelUniqueWork(PendingAlertDelivery.NAME)
        }
    }

    fun replace(context: Context) {
        runCatching {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(NAME)
            manager.cancelUniqueWork(WATCHDOG)
        }
    }

    fun continueAfter(context: Context, generation: Long) {
        runCatching {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(NAME)
            manager.cancelUniqueWork(WATCHDOG)
        }
    }
}

class AutomaticRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class RefreshScheduleWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}
