package com.example.shopradar

import android.content.Context
import org.json.JSONObject

/** Debug-only bounded last-run records; not compiled into Release. */
internal class BackgroundRefreshStatus(context: Context) {
    val prefs = context.applicationContext.getSharedPreferences("background_refresh_status_v1", 0)
    private fun key(target: RefreshTarget) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(target.key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun read(target: RefreshTarget): JSONObject? = prefs.getString(key(target), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun write(target: RefreshTarget, plan: RefreshPlan, run: String, outcome: String, notification: String = "",
              error: String? = null, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val previous = read(target)
        if (outcome != "reading" && previous?.optString("run") != run) return@synchronized
        val record = if (outcome == "reading") JSONObject().put("startedAt", now) else previous ?: JSONObject()
        record.put("run", run).put("revision", plan.revision).put("outcome", outcome)
            .put("notification", notification).put("error", error.orEmpty().take(240))
        if (outcome != "reading") record.put("finishedAt", now)
        prefs.edit().putString(key(target), record.toString()).commit()
    }
    fun remove(target: RefreshTarget) { prefs.edit().remove(key(target)).commit() }
    fun worker(started: Boolean) {
        prefs.edit().putLong(if (started) "workerStartedAt" else "workerFinishedAt", System.currentTimeMillis()).commit()
    }
    companion object {
        private val lock = Any()
        fun scheduled(prefs: android.content.SharedPreferences, id: String) {
            prefs.edit().putString("scheduledWorkId", id).commit()
        }
    }
}
