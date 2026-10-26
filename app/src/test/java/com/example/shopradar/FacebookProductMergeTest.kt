package com.example.shopradar

import org.json.JSONObject
import java.net.URLEncoder
import org.junit.Assert.*
import org.junit.Test

class FacebookProductMergeTest {
    private val url = "https://www.facebook.com/marketplace/item/1863651624626014/"
    private val card = "https://www.facebook.com/marketplace/114987875182625/search?query=phone&radius=250#shopradar-fb-card=" +
        URLEncoder.encode(JSONObject().put("label", "Phone for sale - NT$1,000 in Taipei").put("image", "/phone.jpg").toString(), "UTF-8")
    private fun item(date: String = "", epoch: Long = 0) = Product("Phone", 1000, Market.FACEBOOK.label, url,
        image = "https://example.test/phone.jpg", listedAt = date, listedAtEpochMillis = epoch)

    @Test fun suppliedItemLinkRemainsARealDirectUrl() {
        assertEquals(url, Market.FACEBOOK.normalizePublicProductUrl(url + "?ref=search&referral_code=null&referral_story_type=post&__tn__=!%3AD"))
        assertNull(FacebookCardReference.parse(url))
    }
    @Test fun laterEmptyPollDoesNotRemoveTimeOrImage() {
        val old = item("約一小時前", 1700000000000)
        val result = FacebookProductMerge.merge(old, item().copy(image = ""))
        assertEquals(old.listedAt, result.listedAt)
        assertEquals(old.listedAtEpochMillis, result.listedAtEpochMillis)
        assertEquals(old.image, result.image)
    }
    @Test fun relativeTimeDoesNotMoveForwardOnEveryPoll() {
        val old = item("約58分鐘前", 1700000000000)
        assertEquals(old.listedAtEpochMillis, FacebookProductMerge.merge(old, item(old.listedAt, old.listedAtEpochMillis + 1000)).listedAtEpochMillis)
    }
    @Test fun newlyAvailableDateIsAccepted() {
        val dated = item("約一天前", 1700000000000)
        assertEquals(dated, FacebookProductMerge.merge(item(), dated))
        assertEquals(dated, FacebookProductMerge.merge(null, dated))
    }
    @Test fun cacheAcceptsOnlyRealItemUrlsForValidReferences() {
        FacebookResolvedLinks.remember(card, url + "?ref=search")
        assertEquals(url, FacebookResolvedLinks.find(card))
        for (bad in listOf("https://scontent.example/1863651624626014.jpg", "https://www.facebook.com.evil.test/marketplace/item/1863651624626014/",
            "https://www.facebook.com/login", "https://www.facebook.com/marketplace/search")) {
            FacebookResolvedLinks.remember(card, bad)
            assertEquals(url, FacebookResolvedLinks.find(card))
        }
    }
}
