package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class BackgroundSettingsGuideTest {
    @Test fun xiaomiFamilyIsCaseInsensitiveWithoutAssumingEveryManufacturerUsesMiui() {
        for (brand in listOf("Xiaomi", "REDMI", "POCO")) {
            assertTrue(BackgroundSettingsGuide.xiaomi(brand))
            assertTrue(BackgroundSettingsGuide.instructions(brand).contains("背景自啟動"))
        }
        for (brand in listOf("samsung", "Google", "unknown", "")) assertFalse(BackgroundSettingsGuide.xiaomi(brand))
    }
    @Test fun otherManufacturersHaveRelevantGuidanceAndUnknownDevicesHaveAFallback() {
        assertTrue(BackgroundSettingsGuide.instructions("SAMSUNG").contains("深度休眠"))
        for (brand in listOf("oppo", "oneplus", "realme", "vivo", "iqoo", "Google", "HONOR", ""))
            assertTrue(BackgroundSettingsGuide.instructions(brand).contains("背景"))
    }
    @Test fun guidanceDoesNotAskUsersToDisableBatteryManagement() {
        for (brand in listOf("Xiaomi", "REDMI", "POCO", "Samsung", "OPPO", "OnePlus", "realme", "vivo", "iqoo", "Google", "")) {
            val instructions = BackgroundSettingsGuide.instructions(brand)
            assertFalse(instructions.contains("設為「無限制」"))
            assertFalse(instructions.contains("永不自動休眠"))
            assertFalse(instructions.contains("允許背景電池"))
            assertTrue(instructions.contains("電池"))
        }
    }
}
