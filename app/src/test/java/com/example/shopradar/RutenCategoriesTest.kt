package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class RutenCategoriesTest {
    @Test fun firstBatchIncludesEveryGroupAndChild() {
        assertEquals(24, RutenCategories.groups.size)
        assertEquals(478, RutenCategories.groups.sumOf { it.children.size })
        assertEquals(listOf(22, 15, 20, 29, 27, 10, 40, 24, 7, 18, 10, 34, 11, 5, 14, 30, 16, 23, 17, 32, 16, 31, 14, 13), RutenCategories.groups.map { it.children.size })
        RutenCategories.groups.forEach { group ->
            assertEquals(group.children.size, group.children.map { it.id }.distinct().size)
            assertTrue(group.children.all { it.id.matches(Regex("[0-9]+")) })
        }
    }
    @Test fun idsRetainLeadingZerosAndCrossCategoryLinks() {
        assertEquals("0008", RutenCategories.validId("0008"))
        assertEquals("0002003600020016", RutenCategories.validId("0002003600020016"))
        assertTrue(RutenCategories.groups.first { it.id == "0002" }.children.any { it.id == "00150007" })
        assertNull(RutenCategories.validId("8"))
        assertNull(RutenCategories.validId(""))
        assertNull(RutenCategories.validId("99999999"))
    }
}
