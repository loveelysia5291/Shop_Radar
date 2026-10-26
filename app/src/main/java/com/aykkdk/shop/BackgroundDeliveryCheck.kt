package com.aykkdk.shop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Local health only: no account, product, URL, HTML or credentials. */
internal object BackgroundDeliveryCheck {
    private const val NAME = "radar-background-notification-test"
    fun prefs(context: Context) = context.getSharedPreferences("background_delivery_health_v1", 0)
    fun worker(context: Context, run: String, started: Boolean) = synchronized(this) {
        val saved = prefs(context)
        if (started) saved.edit().putString("run", run).putLong("startedAt", System.currentTimeMillis()).apply()
        else if (saved.getString("run", null) == run)
            saved.edit().putLong("finishedAt", System.currentTimeMillis()).apply()
    }
    fun delivery(context: Context, result: String) {
        if (result in setOf("notification_sent", "notification_blocked", "notification_failed"))
            prefs(context).edit().putString("delivery", result).putLong("deliveryAt", System.currentTimeMillis()).apply()
    }
    fun check(context: Context, state: String) {
        prefs(context).edit().putString("check", state).putLong("checkAt", System.currentTimeMillis()).apply()
    }
    fun checkText(value: String?): String = when (value) {
        "reading" -> "讀取中"
        "complete" -> "已完成"
        "pending" -> "已保存部分資料，等待下次接續"
        "failed" -> "未讀取完成，資料及待送提醒仍保留"
        "network" -> "等待網路連線"
        "sleeping" -> "睡眠時段暫停"
        "interrupted" -> "系統或設定變更中止工作"
        "delivery_schedule_failed" -> "通知已保留，但獨立發送排程建立失敗"
        else -> "尚無紀錄"
    }
    fun schedule(context: Context, delaySeconds: Long = 20) {
        val now = System.currentTimeMillis()
        prefs(context).edit().putString("probe", "queued").putLong("probeAt", now)
            .putLong("probeDueAt", now + delaySeconds * 1000).remove("probeStartedAt").remove("probeFinishedAt").commit()
        WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackgroundNotificationTestWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build())
    }
    fun deliveryText(value: String?): String = when (value) {
        "queued" -> "已排程，請切到背景；系統可能延後執行"
        "reading" -> "Android 正在執行背景測試"
        "notification_sent" -> "已交給 Android 通知系統"
        "notification_blocked" -> "通知權限或頻道已關閉"
        "notification_failed" -> "未完成發送，請檢查通知設定"
        else -> "尚無紀錄"
    }
    fun statusText(context: Context): String {
        val saved = prefs(context)
        val schedules = AutomaticRefreshStore(context)
        val now = System.currentTimeMillis()
        val count = schedules.entries().count { schedules.exists(it.first) }
        val power = context.getSystemService(PowerManager::class.java)
        val started = saved.getLong("startedAt", 0)
        val restricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
        return "自動刷新項目：$count\n" +
            (if (count == 0) "尚未啟用自動刷新，不會在背景檢查商品。\n" else "") +
            "商品通知：${if (RefreshNotificationAccess.enabled(context, "subscription-updates")) "已開啟" else "未開啟"}\n" +
            "最近背景檢查：${if (started > 0) RefreshTimeDisplay.text(started, now) else "尚未執行"}\n" +
            "最近商品檢查：${checkText(saved.getString("check", null))}\n" +
            "最近發送：${deliveryText(saved.getString("delivery", null))}\n" +
            "背景通知測試：${deliveryText(saved.getString("probe", null))}\n" +
            "測試排程：${BackgroundProbeTiming.text(saved.getLong("probeDueAt", 0), saved.getLong("probeStartedAt", 0), now)}\n" +
            "系統背景限制：${if (restricted) "已限制，請檢查應用程式電池設定" else "未回報限制（不代表沒有小米額外限制）"}\n" +
            "電池最佳化：${if (power.isIgnoringBatteryOptimizations(context.packageName)) "已豁免" else "受系統管理"}\n" +
            "睡眠模式：${if (schedules.sleep().contains(now)) "生效中" else "未生效"}；" +
            "省電：${if (power.isPowerSaveMode) "開啟" else "關閉"}；待機：${if (power.isDeviceIdleMode) "是" else "否"}"
    }

    suspend fun scheduleText(context: Context): String = withContext(Dispatchers.IO) {
        try {
            val jobs = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(AutomaticRefreshScheduler.NAME).get(2, TimeUnit.SECONDS)
            val active = jobs.filterNot { it.state.isFinished }
            val delivery = WorkManager.getInstance(context).getWorkInfosForUniqueWork(PendingAlertDelivery.NAME).get(2, TimeUnit.SECONDS)
            "Android 抓取排程：" + when {
                active.any { it.state == WorkInfo.State.RUNNING } -> "正在執行"
                active.any { it.state == WorkInfo.State.ENQUEUED } -> "已排入，等待時間／網路／系統允許"
                active.any { it.state == WorkInfo.State.BLOCKED } -> "等待前一個工作完成"
                else -> "目前沒有待執行工作"
            } + "\n獨立通知排程：" + when {
                delivery.any { it.state == WorkInfo.State.RUNNING } -> "正在發送已保存提醒（不需網路）"
                delivery.any { !it.state.isFinished } -> "等待 Android 執行（不需網路）"
                else -> "目前沒有待執行工作"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { "Android 排程：暫時無法取得狀態" }
    }
}

/** Explicit user test of OS scheduling + notification only; never reads inventory. */
class BackgroundNotificationTestWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = BackgroundDeliveryCheck.prefs(applicationContext)
        prefs.edit().putString("probe", "reading").putLong("probeStartedAt", System.currentTimeMillis()).commit()
        val outcome = runCatching {
            RefreshNotificationAccess.createChannels(applicationContext)
            if (!RefreshNotificationAccess.enabled(applicationContext, RefreshNotificationAccess.CHANNEL_SUBSCRIPTIONS)) "notification_blocked"
            else {
                val intent = Intent(applicationContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val pending = PendingIntent.getActivity(applicationContext, 304127, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(applicationContext, RefreshNotificationAccess.CHANNEL_SUBSCRIPTIONS)
                    .setSmallIcon(R.drawable.ic_notification).setContentTitle("背景通知測試")
                    .setContentText("Android 已執行背景工作並送出通知。")
                    .setContentIntent(pending).setAutoCancel(true).build()
                NotificationManagerCompat.from(applicationContext).notify("background-test", 304127, notification)
                "notification_sent"
            }
        }.getOrDefault("notification_failed")
        prefs.edit().putString("probe", outcome).putLong("probeFinishedAt", System.currentTimeMillis()).commit()
        return Result.success()
    }
}
