package com.example.shopradar

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class FacebookLocationTest {
    private val id = "108803922476002"
    @Test fun suppliedIdAndOfficialPlaceSearchRoutesAreAccepted() {
        assertEquals(id, FacebookLocation.fromInput(id))
        for (host in listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com")) {
            for (tail in listOf("", "/", "/search", "/search/?query=手機&radius=250")) {
                assertEquals(id, FacebookLocation.fromUrl("https://$host/marketplace/$id$tail"))
            }
        }
    }
    @Test fun unsafeNonLocationAndItemUrlsAreRejected() {
        for (url in listOf("http://www.facebook.com/marketplace/$id", "https://www.facebook.com.evil.test/marketplace/$id",
            "https://user@www.facebook.com/marketplace/$id", "https://www.facebook.com:443/marketplace/$id",
            "https://www.facebook.com/marketplace/item/$id", "https://www.facebook.com/marketplace/$id/item/123456",
            "https://www.facebook.com/marketplace/taipei", "https://www.facebook.com/login?next=/marketplace/$id",
            "https://www.facebook.com/marketplace/%31${id.drop(1)}")) assertNull(url, FacebookLocation.fromUrl(url))
        for (value in listOf("123", "0$id", "9".repeat(21), "$id&sortBy=price", "台北市", "-123456")) {
            assertNull(value, FacebookLocation.validId(value))
        }
    }
    @Test fun browseAllowsOnlyHttpsOfficialFacebookAndNoGps() {
        assertTrue(FacebookLocation.browserAllowed("https://www.facebook.com/login"))
        assertTrue(FacebookLocation.marketplacePage("https://www.facebook.com/marketplace/$id/search"))
        assertFalse(FacebookLocation.marketplacePage("https://www.facebook.com/login"))
        assertFalse(FacebookLocation.browserAllowed("intent://facebook.com"))
        assertFalse(FacebookLocation.browserAllowed("https://evil.test/"))
    }
    @Test fun unmodifiedStartingLocationAndLoginRedirectCannotBecomeANewPlace() {
        assertNull(FacebookLocation.changedId(id, "https://www.facebook.com/marketplace/$id/search?query=新地名"))
        assertNull(FacebookLocation.changedId(id, "https://www.facebook.com/login?next=/marketplace/999999"))
        assertEquals("123456789012345", FacebookLocation.changedId(id, "https://www.facebook.com/marketplace/123456789012345/search"))
    }
    @Test fun selectedPlaceChangesOnlyLocationAndPreservesFiltersAndEncoding() {
        val uri = URI(FacebookLocation.searchUrl("手機 & 筆電", "100", "10000", "price_ascend", id))
        assertEquals("/marketplace/$id/search", uri.path)
        val fields = uri.rawQuery.split('&').associate { part ->
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
        assertEquals(mapOf("minPrice" to "100", "maxPrice" to "10000", "sortBy" to "price_ascend", "query" to "手機 & 筆電",
            "exact" to "false", "radius" to "250"), fields)
    }
    @Test fun defaultLocationPricesAndSortRemainBackwardsCompatible() {
        val url = FacebookLocation.searchUrl("", "", "", "unknown", "not-an-id")
        assertEquals("/marketplace/${FacebookLocation.DEFAULT_ID}/search", URI(url).path)
        assertTrue(url.contains("minPrice=1&maxPrice=2000"))
        assertTrue(url.contains("sortBy=distance_ascend"))
        assertEquals("位置 $id", FacebookLocation.label(FacebookLocation.defaultPlace.name, id))
    }
    @Test fun namesAreSearchedLocallyWithTaiAliasesAndAmbiguousPlacesStayDistinct() {
        val records = listOf(FacebookPlace(id, "臺北市"), FacebookPlace("123456789012345", "台北市"), FacebookPlace("999999999999999", "高雄市"))
        assertEquals(records.take(2), FacebookLocation.candidates(records, " 台北 "))
        assertEquals(listOf(records.first()), FacebookLocation.candidates(records, id))
        assertTrue(FacebookLocation.candidates(records, "未知地區").isEmpty())
    }
    @Test fun placeCacheIsBoundedPersistsAndUsesIdToDeduplicate() {
        var records = emptyList<FacebookPlace>()
        repeat(60) { records = FacebookLocation.remember(records, FacebookPlace((100000 + it).toString(), "城市$it")) }
        assertEquals(50, records.size)
        assertEquals("城市59", records.first().name)
        records = FacebookLocation.remember(records, records.last().copy(name = "重新確認地名"))
        assertEquals(50, records.size)
        assertEquals("重新確認地名", records.first().name)
        assertEquals(records, FacebookLocation.decode(FacebookLocation.encode(records)))
    }
    @Test fun brokenOversizedCacheAndInvalidIdsAreNotUsable() {
        for (raw in listOf(null, "", "broken", "{}", "x".repeat(32769))) assertTrue(FacebookLocation.decode(raw).isEmpty())
        assertTrue(FacebookLocation.decode("[{\"id\":\"bad\",\"name\":\"假地名\"}]").isEmpty())
        assertEquals("台北市", FacebookLocation.label("台北\n市", id))
        assertEquals(80, FacebookLocation.label("a".repeat(100), id).length)
    }
    @Test fun facebookHistoryKeepsPlaceAndSeparatesIdenticalSearchesInDifferentPlaces() {
        val first = SearchHistoryCriteria(Market.FACEBOOK, "手機", "100", "1000", sort = "creation_time_descend",
            facebookLocationId = id, facebookLocationName = "測試地點")
        val second = first.copy(facebookLocationId = "123456789012345", facebookLocationName = "另一地點")
        val saved = SearchHistory.record(SearchHistory.record(emptyList(), first), second)
        assertEquals(2, saved.size)
        assertEquals(saved, SearchHistory.decode(SearchHistory.encode(saved)))
        assertEquals("最新 · 價格 100–1000", first.summary())
    }
    @Test fun oldHistoryWithoutLocationFieldsUsesOriginalDefault() {
        val saved = SearchHistory.record(emptyList(), SearchHistoryCriteria(Market.FACEBOOK, "手機"))
        val json = JSONObject(SearchHistory.encode(saved))
        json.getJSONArray("entries").getJSONObject(0).apply { remove("facebookLocationId"); remove("facebookLocationName") }
        val restored = SearchHistory.decode(json.toString()).single().criteria
        assertEquals(FacebookLocation.DEFAULT_ID, restored.facebookLocationId)
        assertEquals(FacebookLocation.defaultPlace.name, restored.facebookLocationName)
    }
    @Test fun anotherPlatformCannotInheritFacebookPlace() {
        val mixed = SearchHistoryCriteria(Market.SHOPEE, "手機", facebookLocationId = id, facebookLocationName = "測試地點").normalized()
        assertEquals(FacebookLocation.DEFAULT_ID, mixed.facebookLocationId)
        assertEquals(FacebookLocation.defaultPlace.name, mixed.facebookLocationName)
        val invalid = mixed.copy(market = Market.FACEBOOK, facebookLocationId = "bad", facebookLocationName = "錯誤地名").normalized()
        assertEquals(FacebookLocation.defaultPlace.name, invalid.facebookLocationName)
    }
}
