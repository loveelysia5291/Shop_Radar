package com.example.shopradar

/** Presentation only: keep full product titles in persistence and subscriptions. */
internal object ProductDisplay {
    fun title(value: String): String {
        var offset = 0
        var characters = 0
        var retainedEnd = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) continue
            characters++
            if (characters > 30) return value.substring(0, retainedEnd) + "..."
            retainedEnd = offset
        }
        return value
    }
}
