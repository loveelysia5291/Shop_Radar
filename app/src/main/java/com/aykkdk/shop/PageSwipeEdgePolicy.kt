package com.aykkdk.shop

/** Leave the system's back-gesture edges untouched, including OEMs reporting zero insets. */
internal object PageSwipeEdgePolicy {
    fun canStart(x: Float, width: Float, left: Float, right: Float, fallback: Float): Boolean {
        if (listOf(x, width, left, right, fallback).any { !it.isFinite() } ||
            width <= 0f || left < 0f || right < 0f || fallback < 0f) return false
        return x > maxOf(left, fallback) && x < width - maxOf(right, fallback)
    }
}
