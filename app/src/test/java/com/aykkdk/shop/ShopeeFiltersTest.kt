package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class ShopeeFiltersTest {
    @Test fun shopTypeSummaryRetainsBothIndependentSelections() {
        assertEquals("不限賣場", ShopeeFilters.shopTypeLabel(false, false))
        assertEquals("蝦皮商城", ShopeeFilters.shopTypeLabel(true, false))
        assertEquals("蝦皮優選", ShopeeFilters.shopTypeLabel(false, true))
        assertEquals("商城＋優選", ShopeeFilters.shopTypeLabel(true, true))
    }
    @Test fun defaultIsTheSitesComprehensiveRanking() {
        assertTrue(ShopeeFilters.parameters("relevancy").isEmpty())
        assertTrue(ShopeeFilters.parameters("unknown").isEmpty())
        assertEquals(5, ShopeeFilters.sorts.size)
        assertEquals("綜合排名", ShopeeFilters.sorts.first().second)
    }
    @Test fun matchesTheFourSuppliedOfficialSortUrls() {
        assertEquals(mapOf("sortBy" to "ctime"), ShopeeFilters.parameters("ctime"))
        assertEquals(mapOf("sortBy" to "sales"), ShopeeFilters.parameters("sales"))
        assertEquals(mapOf("sortBy" to "price", "order" to "asc"), ShopeeFilters.parameters("price_asc"))
        assertEquals(mapOf("sortBy" to "price", "order" to "desc"), ShopeeFilters.parameters("price_desc"))
    }
    @Test fun uncheckedFiltersLeaveTheSiteDefaultUntouched() {
        assertNull(ShopeeFilters.filterOptions())
        assertNull(ShopeeFilters.filterOptions(condition = "unknown"))
        assertEquals(listOf("", "NEW_ITEM", "USED_ITEM"), ShopeeFilters.conditions.map { it.first })
    }
    @Test fun matchesAllThreeSuppliedShopTypeSelections() {
        for ((mall, preferred, expected) in listOf(
            Triple(true, false, listOf("OFFICIAL_MALL")),
            Triple(false, true, listOf("PREFERRED")),
            Triple(true, true, listOf("PREFERRED", "OFFICIAL_MALL"))
        )) {
            val groups = JSONArray(ShopeeFilters.filterOptions(mall = mall, preferred = preferred))
            assertEquals(1, groups.length())
            assertEquals("SHOP_TYPE", groups.getJSONObject(0).getString("group_name"))
            val values = groups.getJSONObject(0).getJSONArray("values")
            assertEquals(expected, (0 until values.length()).map { values.getString(it) })
        }
    }
    @Test fun matchesBothSuppliedConditionSelections() {
        for (condition in listOf("NEW_ITEM", "USED_ITEM")) {
            val groups = JSONArray(ShopeeFilters.filterOptions(condition = condition))
            assertEquals(1, groups.length())
            assertEquals("CONDITION", groups.getJSONObject(0).getString("group_name"))
            assertEquals(listOf(condition), groups.getJSONObject(0).getJSONArray("values").let { values ->
                (0 until values.length()).map { values.getString(it) }
            })
        }
    }
    @Test fun shopAndConditionSelectionsNeverOverwritePricesOrEachOther() {
        for (mall in listOf(false, true)) for (preferred in listOf(false, true)) {
            for (condition in listOf("", "NEW_ITEM", "USED_ITEM")) {
                val groups = JSONArray(ShopeeFilters.filterOptions("100", "10000", mall, preferred, condition))
                val names = (0 until groups.length()).map { groups.getJSONObject(it).getString("group_name") }
                assertEquals(names.size, names.toSet().size)
                assertEquals("PRICE_RANGE", names.first())
                assertEquals("100▶◀10000", groups.getJSONObject(0).getJSONArray("values").getString(0))
                assertEquals(mall || preferred, "SHOP_TYPE" in names)
                assertEquals(condition.isNotEmpty(), "CONDITION" in names)
            }
        }
    }
    @Test fun retainsOneSidedPriceRange() {
        assertEquals("100▶◀undefined", JSONArray(ShopeeFilters.filterOptions(min = "100", mall = true))
            .getJSONObject(0).getJSONArray("values").getString(0))
        assertEquals("undefined▶◀10000", JSONArray(ShopeeFilters.filterOptions(max = "10000", condition = "USED_ITEM"))
            .getJSONObject(0).getJSONArray("values").getString(0))
    }
}
