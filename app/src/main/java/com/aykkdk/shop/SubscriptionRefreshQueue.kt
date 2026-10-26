package com.aykkdk.shop

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only explicitly added/clicked targets are refreshed; app startup does not refresh subscriptions. */
internal object SubscriptionRefreshQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    val running = active.asStateFlow()

    fun request(context: Context, target: RefreshTarget) {
        val app = context.applicationContext
        synchronized(lock) {
            if (target.key in jobs) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    RefreshRunner.refresh(app, target)
                } finally {
                    synchronized(lock) {
                        jobs.remove(target.key)
                        active.value = jobs.keys.toSet()
                    }
                }
            }
            jobs[target.key] = job
            active.value = jobs.keys.toSet()
            job.start()
        }
    }
}
