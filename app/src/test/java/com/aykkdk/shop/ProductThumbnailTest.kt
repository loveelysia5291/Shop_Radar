package com.aykkdk.shop

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProductThumbnailTest {
    private val url = "https://tw.carousell.com/p/phone-1234567890/"
    private val image = "https://images.example.test/product.jpg"

    @Test fun acceptsOnlySafeHttpsImagesAndResolvesProductRelativeUrls() {
        assertEquals(image, ProductThumbnail.url(image))
        assertEquals("https://tw.carousell.com/media/phone.jpg", ProductThumbnail.url("/media/phone.jpg", url))
        for (raw in listOf("", "null", "http://images.example.test/product.jpg", "javascript:alert(1)",
            "file:///private/image", "data:image/png;base64,secret", "https://user:pass@images.example.test/p.jpg",
            "https://images.example.test:8443/p.jpg")) assertNull(ProductThumbnail.url(raw))
    }

    @Test fun readsStringArrayAndImageObjectWithoutUnboundedRecursion() {
        assertEquals(image, ProductThumbnail.fromJson(image, url))
        assertEquals(image, ProductThumbnail.fromJson(JSONArray().put("http://unsafe.test/img").put(JSONObject().put("contentUrl", image)), url))
        assertEquals(image, ProductThumbnail.fromJson(JSONObject().put("url", image), url))
        var nested: Any = image
        repeat(5) { nested = JSONArray().put(nested) }
        assertNull(ProductThumbnail.fromJson(nested, url))
    }

    @Test fun readsOnlyTheMatchedProductsStructuredThumbnail() {
        val other = JSONObject().put("@type", "Product").put("url", "https://tw.carousell.com/p/other-9876543210/")
            .put("name", "別的商品").put("image", "https://images.example.test/other.jpg")
        val target = JSONObject().put("@type", "Product").put("url", url).put("name", "手機")
            .put("image", JSONArray().put(JSONObject().put("contentUrl", image)))
        fun page(data: Any) = "<script type='application/ld+json'>$data</script>"
        assertEquals(image, PublicProductParser.parse(url, 200, page(JSONArray().put(other).put(target))).image)
        assertNull(PublicProductParser.parse(url, 200, page(other)).image)
    }

    @Test fun unavailableOrBlankRefreshImagesDoNotEraseTheExistingThumbnail() {
        val record = JSONObject().put("url", url).put("image", image)
        for (snapshot in listOf(PublicProductSnapshot(error = "逾時"), PublicProductSnapshot(image = ""),
            PublicProductSnapshot(image = "javascript:alert(1)"))) {
            ProductSubscriptionDecision.apply(record, snapshot, 1)
            assertEquals(image, record.getString("image"))
        }
        val replacement = "https://images.example.test/new.jpg"
        ProductSubscriptionDecision.apply(record, PublicProductSnapshot(image = replacement), 2)
        assertEquals(replacement, record.getString("image"))
    }

    @Test fun rutenThumbnailUsesOnlyTheRequestedApiRecordWithoutPageFallback() {
        val rows = JSONArray().put(JSONObject().put("ProdId", "22640807718410").put("Image", JSONArray().put("https://gcs.rimg.com.tw/g1/product.jpg")))
        assertEquals("https://gcs.rimg.com.tw/g1/product.jpg", RutenProductMetadata.snapshot("https://www.ruten.com.tw/item/22640807718410", rows).image)
        assertNull(RutenProductMetadata.snapshot("https://www.ruten.com.tw/item/22640807718411", rows).image)
    }

    @Test fun yahooPublicItemImagesRequireTheExactItemId() {
        val yahoo = "https://tw.bid.yahoo.com/item/100527914215"
        fun page(id: String) = "<script id='isoredux-data'>" + JSONObject().put("item", JSONObject()
            .put("id", id).put("title", "手機").put("type", 1)
            .put("images", JSONArray().put(JSONObject().put("sm", JSONObject().put("src", image))))) + "</script>"
        assertEquals(image, PublicProductParser.parse(yahoo, 200, page("100527914215")).image)
        assertNull(PublicProductParser.parse(yahoo, 200, page("100527914216")).image)
    }
}
