package com.aykkdk.shop

import android.content.Context
import androidx.work.*

/** 本機背景通知已強制移除，改由 Cloudflare Worker 經由 FCM 推送。 */
internal object PendingAlertDelivery {
    const val NAME = "radar-pending-alert-delivery"
    fun enqueue(context: Context) {
        // 強制移除 App 自己發送通知：取消任何本地殘留工作
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}

class PendingAlertDeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
