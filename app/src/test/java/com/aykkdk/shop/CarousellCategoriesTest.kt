package com.aykkdk.shop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarousellCategoriesTest {
    @Test
    fun categoriesKeepCleanNewestFirstOfficialPaths() {
        val mobile = CarousellCategories.groups.first { it.name == "手機及配件" }
        val phones = mobile.children.first { it.name == "手機" }

        assertEquals("https://tw.carousell.com/categories/mobile-phones-gadgets-1091/mobile-phones-6406/?sort_by=3", phones.url)
        assertFalse(phones.url.contains("t-id="))
        assertTrue(CarousellCategories.groups.first { it.name == "免費物品" }.children.isEmpty())
        assertEquals("https://tw.carousell.com/categories/community-26/?sort_by=3", CarousellCategories.groups.last().url)
    }
}
