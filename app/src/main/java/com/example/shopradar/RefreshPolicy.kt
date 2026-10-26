package com.example.shopradar

import java.util.Calendar

internal enum class RefreshKind { SHOP, PRODUCT, AUCTION }
internal data class RefreshTarget(val kind: RefreshKind, val url: String) {
    val key get() = "${kind.name}|$url"
}

internal object RefreshSchedulePolicy {
    // Migrate requests to network-constrained scheduling without consuming offline retries.
    fun tag(generation: Long) = "radar-refresh-generation-v3-$generation"
    fun needsReplacement(generation: Long, unfinishedTags: List<Set<String>>) =
        unfinishedTags.none { tag(generation) in it }
    fun retryRead(error: String?): Boolean = error != null &&
        !Regex("HTTP|驗證|登入|同意|SSL|安全連線", RegexOption.IGNORE_CASE).containsMatchIn(error)
}

/** A retry belongs to one interval, never to an unlimited reconnect loop. */
internal data class RefreshPlan(
    val intervalMinutes: Int,
    val cycleAt: Long,
    val dueAt: Long = cycleAt,
    val retry: Int = 0,
    val revision: String = ""
) {
    init { require(intervalMinutes in 15..10080); require(retry in 0..3) }
    val intervalMillis get() = intervalMinutes * 60_000L
    fun nextCycle(now: Long): RefreshPlan {
        val periods = ((now - cycleAt).coerceAtLeast(0) / intervalMillis) + 1
        val next = cycleAt + periods * intervalMillis
        return copy(cycleAt = next, dueAt = next, retry = 0)
    }
    fun disconnected(now: Long): RefreshPlan =
        if (retry < 3 && now + RETRY_MILLIS < cycleAt + intervalMillis)
            copy(dueAt = now + RETRY_MILLIS, retry = retry + 1)
        else nextCycle(now)

    companion object { const val RETRY_MILLIS = 30_000L }
}

/** Daily local-time sleep window. The end is exclusive; midnight crossings are supported. */
internal data class RefreshSleep(val enabled: Boolean = false, val start: Int = 23 * 60, val end: Int = 7 * 60) {
    init { require(start in 0..1439 && end in 0..1439) }
    fun coversMinute(minute: Int): Boolean = enabled && when {
        start == end -> true
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }
    fun contains(at: Long): Boolean = coversMinute(Calendar.getInstance().apply { timeInMillis = at }.let {
        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
    })
    fun nextAwake(at: Long): Long {
        if (!contains(at)) return at
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (start == end || start > end && minute >= start) calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, end / 60)
        calendar.set(Calendar.MINUTE, end % 60)
        calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    fun nextSleep(at: Long): Long? {
        if (!enabled) return null
        if (contains(at)) return at
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        calendar.set(Calendar.HOUR_OF_DAY, start / 60)
        calendar.set(Calendar.MINUTE, start % 60)
        calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        if (calendar.timeInMillis <= at) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }
}
