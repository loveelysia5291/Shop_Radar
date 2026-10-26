package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchHistoryTest {
    @Test fun boboFormCategorySurvivesHistoryWithoutLeakingToOtherPlatforms() {
        val criteria = SearchHistoryCriteria(Market.BBOBO, "手機", boboMall = "0_1", category = "手機通訊")
        val saved = SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), criteria))).single().criteria
        assertEquals(criteria, saved)
        assertTrue(saved.summary().contains("手機通訊"))
        assertEquals("", criteria.copy(market = Market.IOPEN).normalized().category)
        assertEquals("", criteria.copy(category = "無效分類").normalized().category)
    }
    private fun shopee(keyword: String = "手機") = SearchHistoryCriteria(Market.SHOPEE, keyword, "1", "1000",
        sort = "ctime", condition = "USED_ITEM", mall = true, preferred = true)

    @Test fun suppliedShopeeExampleSurvivesReopenWithAllFilters() {
        val criteria = shopee()
        val recorded = SearchHistory.record(emptyList(), criteria, 123L)
        val reopened = SearchHistory.decode(SearchHistory.encode(recorded))
        assertEquals(recorded, reopened)
        assertEquals(criteria, reopened.single().criteria)
        assertEquals(123L, reopened.single().savedAt)
        assertEquals("手機", criteria.title)
        assertEquals("最新 · 二手 · 商城＋優選 · 價格 1–1000", criteria.summary())
    }

    @Test fun eachPlatformHasItsOwnHistoryEvenWithTheSameKeyword() {
        var records = emptyList<SearchHistoryEntry>()
        Market.values().forEach { records = SearchHistory.record(records, SearchHistoryCriteria(it, "手機")) }
        Market.values().forEach { market ->
            val visible = SearchHistory.forMarket(records, market, false)
            assertEquals(1, visible.size)
            assertEquals(market, visible.single().criteria.market)
        }
        assertEquals(Market.values().size, SearchHistory.decode(SearchHistory.encode(records)).size)
    }

    @Test fun repeatedIdenticalSearchMovesToFrontWithoutDuplicates() {
        val first = SearchHistory.record(emptyList(), shopee(), 1L)
        val second = SearchHistory.record(first, shopee("筆電"), 2L)
        val third = SearchHistory.record(second, shopee(), 3L)
        assertEquals(2, third.size)
        assertEquals("手機", third.first().criteria.keyword)
        assertEquals(first.single().id, third.first().id)
        assertEquals(3L, third.first().savedAt)
    }

    @Test fun differentPricesSortConditionAndShopTypesAreSeparateSearches() {
        val original = shopee()
        val variants = listOf(original, original.copy(min = "2"), original.copy(max = "2000"),
            original.copy(sort = "sales"), original.copy(condition = "NEW_ITEM"),
            original.copy(mall = false), original.copy(preferred = false))
        var records = emptyList<SearchHistoryEntry>()
        variants.forEach { records = SearchHistory.record(records, it) }
        assertEquals(variants.size, records.size)
    }

    @Test fun allSupportedShopeeAndFacebookSortsRoundTrip() {
        for ((sort, _) in ShopeeFilters.sorts) for ((condition, _) in ShopeeFilters.conditions) {
            val criteria = shopee().copy(sort = sort, condition = condition)
            assertEquals(criteria, SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), criteria))).single().criteria)
        }
        for ((sort, _) in FacebookFilters.sorts) {
            val criteria = SearchHistoryCriteria(Market.FACEBOOK, "電腦", sort = sort)
            assertEquals(criteria, SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), criteria))).single().criteria)
        }
    }

    @Test fun categoriesAndYahooAuctionSettingsAreRestorable() {
        val carousellGroup = CarousellCategories.groups.first { it.children.isNotEmpty() }
        val yahooGroup = YahooCategories.groups.first()
        val criteria = listOf(
            SearchHistoryCriteria(Market.CAROUSELL, sort = "價位低至高", mainCategory = carousellGroup.url,
                category = carousellGroup.children.first().url),
            SearchHistoryCriteria(Market.YAHOO, sort = "etime", yahooState = "2", mainCategory = yahooGroup.cid,
                category = yahooGroup.children.first().cid),
            SearchHistoryCriteria(Market.RUTEN, mainCategory = "0011", category = "00110002"),
            SearchHistoryCriteria(Market.BBOBO, "手機", boboMall = "0_1"))
        criteria.forEach {
            assertEquals(it, SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), it))).single().criteria)
            assertTrue(it.summary().isNotBlank())
            assertTrue(it.title.isNotBlank())
        }
        assertTrue(criteria[1].summary().startsWith("即將結標 · 競標"))
        assertTrue(criteria[2].summary().contains("電腦電子／筆記型電腦"))
        assertTrue(criteria[3].summary().contains("特價商城"))
    }

    @Test fun unrelatedFieldsCannotLeakAcrossPlatforms() {
        val mixed = shopee().copy(mainCategory = "0011", category = "00110002", yahooState = "2", boboMall = "0_1")
        assertEquals(shopee(), mixed.normalized())
        val fb = mixed.copy(market = Market.FACEBOOK, sort = "price_descend").normalized()
        assertEquals(SearchHistoryCriteria(Market.FACEBOOK, "手機", "1", "1000", sort = "price_descend"), fb)
        val ruten = mixed.copy(market = Market.RUTEN).normalized()
        assertEquals(SearchHistoryCriteria(Market.RUTEN, "手機", "1", "1000", mainCategory = "0011", category = "00110002"), ruten)
    }

    @Test fun historySummariesKeepSortBeforeStateAndPriceLastForEveryPlatform() {
        Market.values().forEach { market ->
            val criteria = SearchHistoryCriteria(market, "手機", "1", "1000").normalized()
            assertTrue("${market.name}: price range is the last filter", criteria.summary().endsWith("價格 1–1000"))
        }
        val yahoo = SearchHistoryCriteria(Market.YAHOO, "手機", "1", "1000", sort = "etime", yahooState = "2")
        assertEquals("即將結標 · 競標 · 價格 1–1000", yahoo.summary())
        val reopened = SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), yahoo))).single().criteria
        assertEquals(yahoo, reopened)
        assertEquals(yahoo.summary(), reopened.summary())
    }

    @Test fun sharedPriceSortChoicesKeepAscendingBeforeDescendingWithoutChangingValues() {
        assertEquals(listOf("price_asc", "price_desc"), ShopeeFilters.sorts.map { it.first }.filter { it.startsWith("price_") })
        assertEquals(listOf("price_ascend", "price_descend"), FacebookFilters.sorts.map { it.first }.filter { it.startsWith("price_") })
        assertEquals(listOf("curp", "-curp"), YahooFilters.sorts.map { it.first }.filter { it.endsWith("curp") })
        assertEquals(listOf("價位低至高", "價位高至低"), SearchHistoryCriteria.CAROUSELL_SORTS.filter { it.startsWith("價位") })
        listOf("價位低至高", "價位高至低").forEach { oldValue ->
            val criteria = SearchHistoryCriteria(Market.CAROUSELL, "手機", sort = oldValue)
            val restored = SearchHistory.decode(SearchHistory.encode(SearchHistory.record(emptyList(), criteria))).single().criteria
            assertEquals(criteria, restored)
        }
    }

    @Test fun invalidCategoryCannotBeRestoredAsAnotherParentsChild() {
        val invalid = SearchHistoryCriteria(Market.RUTEN, mainCategory = "0011", category = "00210001")
        assertTrue(SearchHistory.record(emptyList(), invalid).isEmpty())
        val unknown = SearchHistoryCriteria(Market.YAHOO, mainCategory = "unknown", category = "23960")
        assertTrue(SearchHistory.record(emptyList(), unknown).isEmpty())
        assertEquals("relevancy", shopee().copy(sort = "unknown").normalized().sort)
    }

    @Test fun emptyInvalidPricesAndUnlockInputNeverCreateHistory() {
        assertTrue(SearchHistory.record(emptyList(), SearchHistoryCriteria(Market.SHOPEE)).isEmpty())
        assertTrue(SearchHistory.record(emptyList(), shopee().copy(min = "1001")).isEmpty())
        assertTrue(SearchHistory.record(emptyList(), shopee("1145141919810")).isEmpty())
        assertTrue(SearchHistory.record(emptyList(), shopee(" 1145141919810 ")).isEmpty())
        assertEquals("手機", shopee("  手機  ").normalized().keyword)
    }

    @Test fun deletingOneEntryKeepsOtherRecordsAndPlatforms() {
        val first = SearchHistory.record(emptyList(), shopee())
        val all = SearchHistory.record(first, SearchHistoryCriteria(Market.CAROUSELL, "手機"))
        val remaining = SearchHistory.delete(all, first.single().id)
        assertEquals(1, remaining.size)
        assertEquals(Market.CAROUSELL, remaining.single().criteria.market)
        assertEquals(remaining, SearchHistory.decode(SearchHistory.encode(remaining)))
        assertEquals(all, SearchHistory.delete(all, "unknown"))
        assertTrue(SearchHistory.decode(SearchHistory.encode(emptyList())).isEmpty())
    }

    @Test fun capIsPerPlatformAndRetainsMostRecent() {
        var records = SearchHistory.record(emptyList(), SearchHistoryCriteria(Market.CAROUSELL, "不被其他平台擠掉"))
        repeat(SearchHistory.LIMIT_PER_PLATFORM + 10) { records = SearchHistory.record(records, shopee("手機$it")) }
        assertEquals(SearchHistory.LIMIT_PER_PLATFORM, SearchHistory.forMarket(records, Market.SHOPEE, false).size)
        assertEquals(1, SearchHistory.forMarket(records, Market.CAROUSELL, false).size)
        assertEquals("手機59", records.first().criteria.keyword)
        assertFalse(records.any { it.criteria.keyword == "手機0" })
    }

    @Test fun adultHistoriesRelockWithoutRevealingTitlesOrCategories() {
        var records = SearchHistory.record(emptyList(), SearchHistoryCriteria(Market.RUTEN, "成人用品"))
        records = SearchHistory.record(records, SearchHistoryCriteria(Market.RUTEN, mainCategory = "0025", category = "00250005"))
        records = SearchHistory.record(records, SearchHistoryCriteria(Market.RUTEN, "電腦"))
        assertEquals(3, SearchHistory.forMarket(records, Market.RUTEN, true).size)
        assertEquals(listOf("電腦"), SearchHistory.forMarket(records, Market.RUTEN, false).map { it.criteria.title })
        assertEquals(3, SearchHistory.decode(SearchHistory.encode(records)).size)
    }

    @Test fun corruptedUnsupportedAndOversizedDataFailClosed() {
        listOf(null, "", "broken", "[]", "{\"version\":2}", "x".repeat(512 * 1024 + 1)).forEach {
            assertTrue(SearchHistory.decode(it).isEmpty())
        }
        val valid = SearchHistory.record(emptyList(), shopee())
        val root = JSONObject(SearchHistory.encode(valid))
        val good = root.getJSONArray("entries").getJSONObject(0)
        root.put("entries", JSONArray().put("broken").put(JSONObject(good.toString()).put("market", "RETIRED"))
            .put(good).put(good))
        assertEquals(valid, SearchHistory.decode(root.toString()))
    }
}
