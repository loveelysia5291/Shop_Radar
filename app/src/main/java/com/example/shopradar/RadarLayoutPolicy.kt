package com.example.shopradar

/** Use the space allocated to the component, not a device/tablet identifier. */
internal object RadarLayoutPolicy {
    const val FORM_MAX_WIDTH = 720
    const val CONTENT_MAX_WIDTH = 1600
    const val CARD_ACTION_SIZE = 48

    fun inlineCardHeader(widthDp: Float, actionCount: Int, platform: Boolean, fontScale: Float = 1f): Boolean {
        if (!widthDp.isFinite() || widthDp <= 0f || actionCount !in 0..3) return false
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
        val minimum = 96f * scale + actionCount * CARD_ACTION_SIZE +
            (if (actionCount > 0) 8f else 0f) + (if (platform) 80f * scale + 8f else 0f)
        return widthDp >= minimum
    }

    fun photoColumns(availableWidthDp: Float, fontScale: Float = 1f): Int {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
        // Two columns on normal phones; keep large text and narrow split windows usable.
        val maxColumns = if (availableWidthDp < 600f) 2 else 4
        return ((availableWidthDp + 12f) / (138f * scale + 12f)).toInt().coerceIn(1, maxColumns)
    }

    fun cardColumns(availableWidthDp: Float, fontScale: Float = 1f): Int {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
        val scale = if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f
        val minimumWidth = 360f * scale
        return ((availableWidthDp + 12f) / (minimumWidth + 12f)).toInt().coerceIn(1, 3)
    }

    fun subscriptionPhotoColumns(availableWidthDp: Float, fontScale: Float = 1f): Int {
        var columns = photoColumns(availableWidthDp, fontScale)
        // Three image actions must fit without reducing their 48dp touch targets.
        while (columns > 1 && (availableWidthDp - (columns - 1) * 12f) / columns < 3 * CARD_ACTION_SIZE) columns--
        return columns
    }

}
