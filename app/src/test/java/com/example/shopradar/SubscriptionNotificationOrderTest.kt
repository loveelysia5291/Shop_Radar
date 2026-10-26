package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionNotificationOrderTest {
    private val now = 1_800_000_000_000L
    private val target = RefreshTarget(RefreshKind.SHOP, "https://www.ruten.com.tw/store/fixture/list")
    private fun item(id: Int, time: Long) = Product("商品$id", 100, Market.RUTEN.label,
        "https://www.ruten.com.tw/item/2264080771841$id", shop = "店家", listedAtEpochMillis = time)
    private fun alert(product: Product) = ProductSubscriptionAlert(product.shop, product.title, "新品", product.isAdult, product.url)
    private fun saved(vararg products: Product) = JSONObject().also { ShopNewItems.merge(it, products.toList()) }

    @Test fun todaySellerPrecedesYesterdaySellerEvenIfYesterdayIsDeliveredLater() {
        val a = item(1, now - 86_400_000)
        val b = item(2, now - 60_000)
        val first = SubscriptionNotificationOrder.eventTime(listOf(alert(a)), saved(a).put("checkedAt", now), now)
        val second = SubscriptionNotificationOrder.eventTime(listOf(alert(b)), saved(b).put("checkedAt", now - 5000), now)
        assertEquals(a.listedAtEpochMillis, first)
        assertEquals(b.listedAtEpochMillis, second)
        assertTrue(SubscriptionNotificationOrder.sortKey(second, target.copy(url = "B")) < SubscriptionNotificationOrder.sortKey(first, target.copy(url = "A")))
    }

    @Test fun unrelatedRetainedHistoryDoesNotMoveOldPendingNotificationUp() {
        val old = item(1, now - 86_400_000)
        val unrelated = item(2, now - 1000)
        val record = saved(old, unrelated)
        ShopNewItems.merge(record, emptyList())
        assertEquals(old.listedAtEpochMillis, SubscriptionNotificationOrder.eventTime(listOf(alert(old)), record, now))
    }

    @Test fun wholeCurrentScanUsesNewestIncludedItemNotLastSourcePage() {
        val a = item(1, now - 86_400_000)
        val b = item(2, now - 1000)
        assertEquals(b.listedAtEpochMillis, SubscriptionNotificationOrder.eventTime(listOf(alert(a)), saved(a, b), now))
    }

    @Test fun unknownListingTimeUsesFirstSavedDiscoveryNotDeliveryOrLastRefresh() {
        val product = item(1, 0)
        val record = saved(product)
        BackgroundAlertJournal.append(record, listOf(alert(product)), now - 86_400_000)
        BackgroundAlertJournal.append(record, listOf(alert(product)), now - 1000)
        val restored = JSONObject(record.toString()).put("checkedAt", now)
        assertEquals(now - 86_400_000, SubscriptionNotificationOrder.eventTime(listOf(alert(product)), restored, now))
        assertEquals(1, BackgroundAlertJournal.rows(restored).size)
    }

    @Test fun futureAndHiddenDatesCannotPromoteANotification() {
        val product = item(1, now + 1000)
        val hidden = item(2, now - 1000).copy(isAdult = true)
        val record = saved(product, hidden)
        BackgroundAlertJournal.append(record, listOf(alert(product)), now - 10000)
        assertEquals(now - 10000, SubscriptionNotificationOrder.eventTime(listOf(alert(product), alert(hidden)), record, now))
    }

    @Test fun legacyJournalsUseSavedCheckIfNoDiscoveryDateExists() {
        val product = item(1, 0)
        val record = saved(product).put("checkedAt", now - 86_400_000)
        assertEquals(now - 86_400_000, SubscriptionNotificationOrder.eventTime(listOf(alert(product)), record, now))
        assertEquals(now, SubscriptionNotificationOrder.eventTime(listOf(alert(product)), null, now))
    }

    @Test fun sortKeysAreFixedWidthDescendingAndDeterministicForTies() {
        val times = listOf(0L, 9L, 10L, now, Long.MAX_VALUE)
        val expected = times.sortedDescending()
        assertEquals(expected, times.sortedBy { SubscriptionNotificationOrder.sortKey(it, target) })
        assertEquals(SubscriptionNotificationOrder.sortKey(now, target), SubscriptionNotificationOrder.sortKey(now, target.copy()))
        assertNotEquals(SubscriptionNotificationOrder.sortKey(now, target), SubscriptionNotificationOrder.sortKey(now, target.copy(url = "B")))
    }
}
