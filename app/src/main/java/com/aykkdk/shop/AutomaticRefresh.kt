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
    fun configure(target: RefreshTarget, intervalMinutes: Int?) {
        synchronized(lock) {
            val editor = prefs.edit().putLong("generation", generation() + 1)
            if (intervalMinutes == null) editor.remove(target.key) else {
                require(intervalMinutes in 15..10080)
                val at = System.currentTimeMillis() + intervalMinutes * 60_000L
                editor.putString(target.key, encode(RefreshPlan(intervalMinutes, at, revision = UUID.randomUUID().toString())))
            }
            editor.commit()
        }
        if (intervalMinutes == null) BackgroundRefreshStatus(app).remove(target)
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
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}

/** One batched, non-expedited job. No service, exact alarm, reconnect receiver or idle polling. */
internal object AutomaticRefreshScheduler {
    internal const val NAME = "radar-automatic-refresh"
    private const val WATCHDOG = "radar-refresh-schedule-watchdog"
    suspend fun initialize(context: Context) = withContext(Dispatchers.IO) {
        val store = AutomaticRefreshStore(context)
        val manager = WorkManager.getInstance(context)
        if (store.entries().any { store.exists(it.first) }) PendingAlertDelivery.enqueue(context)
        if (!store.prefs.getBoolean("legacyCancelled", false)) {
            manager.cancelUniqueWork("auction-periodic")
            manager.cancelUniqueWork("auction-refresh")
            store.prefs.edit().putBoolean("legacyCancelled", true).commit()
        }
        val generation = store.generation()
        val active = runCatching { manager.getWorkInfosForUniqueWork(NAME).get(3, TimeUnit.SECONDS)
            .filterNot { it.state.isFinished }.map { it.tags } }.getOrNull()
        if (generation != store.generation()) return@withContext // A settings change already enqueued its replacement.
        // A stale-generation worker exits without chaining its next job. KEEP
        // used to retain it forever even when enabled plans had no valid worker.
        // Recover the persisted schedule, not an on-start refresh of every item.
        enqueue(context, if (active != null && RefreshSchedulePolicy.needsReplacement(generation, active))
            ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, generation)
    }
    fun replace(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)
    fun continueAfter(context: Context, generation: Long) {
        enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE, generation)
    }
    private fun enqueue(context: Context, policy: ExistingWorkPolicy, expectedGeneration: Long? = null) = synchronized(AutomaticRefreshStore.lock) {
        val store = AutomaticRefreshStore(context)
        val generation = store.generation()
        if (expectedGeneration != null && expectedGeneration != generation) return@synchronized
        val manager = WorkManager.getInstance(context)
        val earliest = store.entries().filter { store.exists(it.first) }.minOfOrNull { it.second.dueAt }
        if (earliest == null) {
            if (policy == ExistingWorkPolicy.REPLACE) manager.cancelUniqueWork(NAME)
            manager.cancelUniqueWork(WATCHDOG)
            return@synchronized
        }
        // Independent persisted safety net: a broken one-time chain must not need
        // an Activity launch to recover. This checks the schedule, not every item.
        manager.enqueueUniquePeriodicWork(WATCHDOG, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshScheduleWatchdog>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).build())
        val now = System.currentTimeMillis()
        val at = store.sleep().nextAwake(maxOf(earliest, now))
        val request = OneTimeWorkRequestBuilder<AutomaticRefreshWorker>()
            .setInputData(workDataOf("generation" to generation))
            .setInitialDelay((at - now).coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(RefreshSchedulePolicy.tag(generation))
            .addTag(NAME).build()
        manager.enqueueUniqueWork(NAME, policy, request)
        // KEEP may retain a previously enqueued job; it must not replace the
        // diagnostic ID with a request WorkManager never accepted.
        if (policy != ExistingWorkPolicy.KEEP) BackgroundRefreshStatus.scheduled(store.prefs, request.id.toString())
    }
}

class AutomaticRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = AutomaticRefreshStore(applicationContext)
        val generation = inputData.getLong("generation", -1)
        if (generation != store.generation()) return@withContext Result.success()
        val status = BackgroundRefreshStatus(applicationContext)
        status.worker(started = true)
        BackgroundDeliveryCheck.worker(applicationContext, id.toString(), started = true)
        var completed = false
        try {
            BackgroundAlertDelivery.drainEnabled(applicationContext)
            // Bound each wake-up, and serialize readers to avoid concurrent WebViews.
            val due = store.entries().filter { it.second.dueAt <= System.currentTimeMillis() }.sortedBy { it.second.dueAt }.take(3)
            for ((target, plan) in due) {
                currentCoroutineContext().ensureActive()
                if (generation != store.generation()) break
                val now = System.currentTimeMillis()
                status.write(target, plan, id.toString(), "reading", now = now)
                BackgroundDeliveryCheck.check(applicationContext, "reading")
                if (!store.exists(target) || store.sleep().contains(now)) {
                    status.write(target, plan, id.toString(), "sleeping")
                    BackgroundDeliveryCheck.check(applicationContext, "sleeping")
                    store.finish(target, plan, plan.nextCycle(now)); continue
                }
                // Already persisted local alerts do not need a network connection.
                val deliveredBefore = runCatching {
                    BackgroundAlertDelivery.drain(applicationContext, target) { store.allowed(target, plan) }
                }.getOrDefault("notification_failed")
                if (!RefreshNetwork.connected(applicationContext)) {
                    BackgroundDeliveryCheck.check(applicationContext, "network")
                    status.write(target, plan, id.toString(), "retry", error = "網路未連線；本輪最多重試三次。")
                    store.finish(target, plan, plan.disconnected(now)); continue
                }
                try {
                    val untilSleep = store.sleep().nextSleep(now)?.let { (it - now).coerceAtLeast(1) } ?: 120_000L
                    val result = withTimeoutOrNull(minOf(120_000L, untilSleep)) {
                        RefreshRunner.refresh(applicationContext, target, background = true) { store.allowed(target, plan) }
                    } ?: RefreshResult(error = "背景刷新逾時；保留原有資料與待送提醒。")
                    currentCoroutineContext().ensureActive()
                    val deliveredAfter = BackgroundAlertDelivery.drain(applicationContext, target) { store.allowed(target, plan) }
                    val delivery = if (deliveredBefore == "notification_sent" && deliveredAfter == "no_updates") deliveredBefore else deliveredAfter
                    status.write(target, plan, id.toString(), if (result.error != null) "failed" else if (result.pending) "pending" else "complete", delivery, result.error)
                    BackgroundDeliveryCheck.check(applicationContext, if (result.error != null) "failed" else if (result.pending) "pending" else "complete")
                    val after = System.currentTimeMillis()
                    store.finish(target, plan, if (!RefreshNetwork.connected(applicationContext) ||
                        RefreshSchedulePolicy.retryRead(result.error) || result.pending) plan.disconnected(after) else plan.nextCycle(after))
                } catch (cancelled: CancellationException) {
                    status.write(target, plan, id.toString(), "interrupted", error = "系統或設定變更中止本次工作；待送提醒仍保留。")
                    BackgroundDeliveryCheck.check(applicationContext, "interrupted")
                    throw cancelled
                }
                catch (_: Exception) {
                    status.write(target, plan, id.toString(), "failed", error = "背景刷新未完成；保留原有資料與待送提醒。")
                    BackgroundDeliveryCheck.check(applicationContext, "failed")
                    val after = System.currentTimeMillis()
                    store.finish(target, plan, plan.disconnected(after))
                }
            }
            completed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        } finally {
            status.worker(started = false)
            BackgroundDeliveryCheck.worker(applicationContext, id.toString(), started = false)
            if (completed && !isStopped) AutomaticRefreshScheduler.continueAfter(applicationContext, generation)
        }
        Result.success()
    }
}

class RefreshScheduleWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AutomaticRefreshScheduler.initialize(applicationContext)
        // No WebView/network required, including when the next check is days away.
        BackgroundAlertDelivery.drainEnabled(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
