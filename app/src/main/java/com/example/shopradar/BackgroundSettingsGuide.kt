package com.example.shopradar

import java.util.Locale

internal object BackgroundSettingsGuide {
    fun xiaomi(manufacturer: String) = manufacturer.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
    fun instructions(manufacturer: String): String = when (manufacturer.lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> "小米／Redmi／POCO：確認通知、背景資料與「背景自啟動」權限。自啟動不等於解除省電限制；如果只在開啟 App 才收到通知，請到應用程式資訊檢查電池／省電設定，是否放寬限制由你決定，可能增加耗電。選單位置依 MIUI／HyperOS 版本而異。"
        "samsung" -> "三星：確認購物雷達的通知與背景資料權限；維持原有電池與休眠設定。系統深度休眠時，本機背景檢查可能延後。"
        "oppo", "oneplus", "realme" -> "OPPO／OnePlus／realme：確認通知與背景資料權限；如系統提供自啟動設定，可檢查背景工作的啟動權限，不需變更電池管理。選項名稱依系統版本而異。"
        "vivo", "iqoo" -> "vivo／iQOO：確認通知與背景資料權限；如有自啟動設定，可檢查背景工作的啟動權限，不需變更電池管理。選項名稱依系統版本而異。"
        else -> "在購物雷達的應用程式資訊中確認背景資料與通知權限，維持系統預設的電池管理。背景工作由 Android 排程，選項名稱依手機廠牌及系統版本而異。"
    }
}
