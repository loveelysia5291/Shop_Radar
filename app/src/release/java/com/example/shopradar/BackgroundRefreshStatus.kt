package com.example.shopradar

import android.content.Context

/** Release does not persist diagnostic traces; scheduling and alerts use their own stores. */
@Suppress("UNUSED_PARAMETER")
internal class BackgroundRefreshStatus(context: Context) {
    fun write(target: RefreshTarget, plan: RefreshPlan, run: String, outcome: String,
              notification: String = "", error: String? = null, now: Long = 0L) {}
    fun remove(target: RefreshTarget) {}
    fun worker(started: Boolean) {}
    companion object {
        fun scheduled(prefs: android.content.SharedPreferences, id: String) {}
    }
}
