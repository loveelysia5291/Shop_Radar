package com.example.shopradar

/** Only the root search screen can arm double-back; use a monotonic clock. */
internal class RootBackPolicy {
    enum class Action { SEARCH, PROMPT, BACKGROUND }
    private var firstBackAt: Long? = null

    fun reset() { firstBackAt = null }

    fun back(page: Int, now: Long): Action {
        if (page != 0) {
            reset()
            return Action.SEARCH
        }
        val first = firstBackAt
        if (first != null && now >= first && now - first <= WINDOW_MILLIS) {
            reset()
            return Action.BACKGROUND
        }
        firstBackAt = now
        return Action.PROMPT
    }

    companion object { const val WINDOW_MILLIS = 2_000L }
}
