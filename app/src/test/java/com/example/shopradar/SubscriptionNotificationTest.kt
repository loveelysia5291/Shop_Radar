package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionNotificationTest {
    private val shopUrl = "https://www.ruten.com.tw/store/fixture/list?sort=new%2Fdc&p=1"
    private val shop = RefreshTarget(RefreshKind.SHOP, shopUrl)
    private fun item(id: Int) = Product("手機", 100, Market.RUTEN.label, "https://www.ruten.com.tw/item/2264080771841$id",
        shop = "測試店家", listedAtEpochMillis = id * 100L)
    private fun alert(id: Int) = ProductSubscriptionAlert("測試店家", "手機", "新品", productUrl = item(id).url)

    @Test fun shopNotificationsUseSellerAndDistinctItemCountEvenWithIdenticalTitles() {
        val content = SubscriptionNotificationText.build(shop, listOf(alert(1), alert(2), alert(1)))!!
        assertEquals("測試店家", content.title)
        assertEquals("上架了 2 件物品，快來看看吧", content.text)
    }
    @Test fun successiveSourcePagesReportWholeCurrentScanNotJustLastPage() {
        val saved = JSONObject().put("name", "測試店家")
        ShopNewItems.merge(saved, listOf(item(3), item(2), item(1)))
        assertEquals("上架了 3 件物品，快來看看吧",
            SubscriptionNotificationText.build(shop, listOf(alert(1)), saved)!!.text)
    }
    @Test fun blockedOlderBacklogIsNotLostWhenNextCheckHasAdditionalItems() {
        val saved = JSONObject().put("name", "測試店家")
        ShopNewItems.merge(saved, listOf(item(2)))
        assertEquals("上架了 2 件物品，快來看看吧",
            SubscriptionNotificationText.build(shop, listOf(alert(1), alert(2)), saved)!!.text)
    }
    @Test fun markedAdultItemsNeverAppearOrContributeToNotificationCounts() {
        assertNull(SubscriptionNotificationText.build(shop, listOf(alert(1).copy(isAdult = true))))
        val saved = JSONObject().put("name", "成人用品")
        ShopNewItems.merge(saved, listOf(item(1), item(2).copy(isAdult = true)))
        val content = SubscriptionNotificationText.build(shop, listOf(alert(1)), saved)!!
        assertEquals("測試店家", content.title)
        assertEquals("上架了 1 件物品，快來看看吧", content.text)
    }
    @Test fun journalKeepsTwoSameNamedProductsAndTheirIdentityAcrossRestart() {
        val saved = JSONObject()
        BackgroundAlertJournal.append(saved, listOf(alert(1), alert(2), alert(1)))
        val restored = JSONObject(saved.toString())
        assertEquals(2, BackgroundAlertJournal.rows(restored).size)
        assertEquals(listOf(alert(1), alert(2)), BackgroundAlertJournal.alerts(restored))
    }
    @Test fun legacyAlertsRemainReadableAndDoNotDoubleCountCurrentMatchingTitles() {
        val saved = JSONObject().put("name", "測試店家")
        ShopNewItems.merge(saved, listOf(item(1)))
        val legacy = ProductSubscriptionAlert("測試店家", "手機", "新品")
        assertEquals("上架了 1 件物品，快來看看吧", SubscriptionNotificationText.build(shop, listOf(legacy), saved)!!.text)
    }
    @Test fun productPriceOrStockUpdatesAreNotMisreportedAsSellerAddingItems() {
        val target = RefreshTarget(RefreshKind.PRODUCT, item(1).url)
        val result = SubscriptionNotificationText.build(target, listOf(alert(1).copy(kind = "降價")))!!
        assertEquals("訂閱有更新", result.title)
        assertTrue(result.text.contains("降價"))
        assertFalse(result.text.contains("添加了"))
    }
    @Test fun onlySupportedOfficialNotificationTargetsAreAccepted() {
        assertEquals(shop, SubscriptionNotificationRoute.parse("SHOP", shopUrl)!!.target)
        assertEquals(RefreshKind.PRODUCT, SubscriptionNotificationRoute.parse("PRODUCT", item(1).url)!!.target.kind)
        listOf("https://evil.test/store/fixture", "http://www.ruten.com.tw/store/fixture", "javascript:alert(1)",
            "https://www.ruten.com.tw.evil.test/store/fixture").forEach { assertNull(SubscriptionNotificationRoute.parse("SHOP", it)) }
        assertNull(SubscriptionNotificationRoute.parse("PRODUCT", shopUrl))
        assertNull(SubscriptionNotificationRoute.parse("AUCTION", item(1).url))
        assertNull(SubscriptionNotificationRoute.parse("unknown", shopUrl))
        assertNull(SubscriptionNotificationRoute.parse("SHOP", null))
    }
    @Test fun repeatedTapHasANewEventIdButStableDistinctPendingIntentIdentity() {
        assertNotEquals(SubscriptionNotificationRoute.parse("SHOP", shopUrl)!!.id,
            SubscriptionNotificationRoute.parse("SHOP", shopUrl)!!.id)
        assertEquals(SubscriptionNotificationRoute.identity(shop), SubscriptionNotificationRoute.identity(shop.copy()))
        assertNotEquals(SubscriptionNotificationRoute.identity(shop), SubscriptionNotificationRoute.identity(shop.copy(kind = RefreshKind.PRODUCT)))
    }
    @Test fun equalJavaHashCodesCannotMakeDifferentShopsOpenEachOthersPages() {
        val a = shop.copy(url = "https://www.ruten.com.tw/store/Aa/list")
        val b = shop.copy(url = "https://www.ruten.com.tw/store/BB/list")
        assertEquals(a.key.hashCode(), b.key.hashCode())
        assertNotEquals(SubscriptionNotificationRoute.identity(a), SubscriptionNotificationRoute.identity(b))
    }
}
