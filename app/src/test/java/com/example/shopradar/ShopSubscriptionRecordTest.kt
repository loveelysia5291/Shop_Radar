package com.example.shopradar

import org.junit.Assert.assertEquals
import org.junit.Test

class ShopSubscriptionRecordTest {
    @Test fun clearingHistoryKeepsSubscriptionAndComparisonBaseline() {
        val key = "https://www.ruten.com.tw/store/tiger0719tw/list?sort=new/dc&p=1"
        val record = clearShopNewItems(key, """{"name":"shop","seen":["item1"],"newItems":[{"url":"item1"}],"newCount":1,"listingCheckpointAt":1234,"rutenCheckpointAt":1200,"checkedAt":2000}""")
        assertEquals(0, record.getJSONArray("newItems").length())
        assertEquals(0, record.getInt("newCount"))
        assertEquals(key, record.getString("url"))
        assertEquals("shop", record.getString("name"))
        assertEquals("RUTEN", record.getString("platform"))
        assertEquals("item1", record.getJSONArray("seen").getString(0))
        assertEquals(1234L, record.getLong("listingCheckpointAt"))
        assertEquals(1200L, record.getLong("rutenCheckpointAt"))
        assertEquals(2000L, record.getLong("checkedAt"))
    }
    @Test fun recoversIdentityFromRefreshRecordWithoutDiscardingHistory() {
        val key = "https://www.ruten.com.tw/store/tiger0719tw/list?sort=new/dc&p=1"
        val record = shopSubscriptionRecord(key, """{"seen":["item1"],"newItems":[{"title":"phone"}]}""")
        assertEquals(key, record.getString("url"))
        assertEquals("RUTEN", record.getString("platform"))
        assertEquals("item1", record.getJSONArray("seen").getString(0))
        assertEquals("phone", record.getJSONArray("newItems").getJSONObject(0).getString("title"))
    }
}
