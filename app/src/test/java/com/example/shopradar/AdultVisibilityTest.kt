package com.example.shopradar

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AdultVisibilityTest {
    @After fun reset() { AdultVisibility.lock() }
    @Test fun exactSecretIsConsumedAndLockResetsSession() {
        AdultVisibility.lock()
        assertFalse(AdultVisibility.unlocked)
        assertFalse(AdultVisibility.consumeInput("114514191981"))
        assertFalse(AdultVisibility.consumeInput("手機1145141919810"))
        assertTrue(AdultVisibility.consumeInput("1145141919810"))
        assertTrue(AdultVisibility.unlocked)
        AdultVisibility.lock()
        assertFalse(AdultVisibility.unlocked)
    }
    @Test fun adultCrossLinksHiddenButOrdinaryClothingRemains() {
        assertTrue(AdultVisibility.adultCategory(Market.RUTEN, "00250011"))
        assertTrue(AdultVisibility.adultCategory(Market.RUTEN, "00020052"))
        assertTrue(AdultVisibility.adultCategory(Market.YAHOO, "41179373"))
        assertTrue(AdultVisibility.adultCategory(Market.YAHOO, "53686009"))
        assertFalse(AdultVisibility.adultCategory(Market.RUTEN, "00020053", "角色扮演"))
        assertFalse(AdultVisibility.adultText("成人運動服與口罩"))
        assertFalse(AdultVisibility.adultText("iPhone 18 手機"))
    }
    @Test fun neutralTitlesKeepAdultClassificationInHistory() {
        AdultVisibility.lock()
        val product = Product("收藏品", 100, "露天", "https://www.ruten.com.tw/item/22639751952652/", isAdult = true)
        val restored = Product.parse(product.json())!!
        assertFalse(AdultVisibility.visible(restored))
        assertFalse(AdultVisibility.visible(product.json()))
        assertFalse(AdultVisibility.visible(product.copy(isAdult = false, title = "18禁玩具公仔")))
        AdultVisibility.consumeInput("1145141919810")
        assertTrue(AdultVisibility.visible(restored))
        AdultVisibility.lock()
        assertFalse(AdultVisibility.visible(restored))
    }
}
