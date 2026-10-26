package com.example.shopradar

/** Activity-owned mapping; an old composition cannot erase a newer binding. */
internal class ScreenBackBinding(private val enableCallback: (Boolean) -> Unit) {
    private var owner: Any? = null
    private var action: (() -> Unit)? = null
    fun bind(token: Any, enabled: Boolean, onBack: () -> Unit) {
        owner = token
        action = if (enabled) onBack else null
        enableCallback(action != null)
    }
    fun clear(token: Any) {
        if (owner !== token) return
        owner = null
        action = null
        enableCallback(false)
    }
    fun dispatch(): Boolean {
        val current = action ?: return false
        current()
        return true
    }
}
