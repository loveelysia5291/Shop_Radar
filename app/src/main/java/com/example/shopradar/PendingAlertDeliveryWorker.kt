package com.example.shopradar

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Persist notification delivery separately from network-constrained inventory reads. */
internal object PendingAlertDelivery {
    const val NAME = "radar-pending-alert-delivery"
    fun enqueue(context: Context) {
        // APPEND avoids losing an alert committed just as the previous delivery
        // finishes. An interrupted reader cannot cancel this independent work.
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PendingAlertDeliveryWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES).build())
        }.onFailure { BackgroundDeliveryCheck.check(context, "delivery_schedule_failed") }
    }
}

class PendingAlertDeliveryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val complete = BackgroundAlertDelivery.drainEnabled(applicationContext)
        return if (!complete && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
