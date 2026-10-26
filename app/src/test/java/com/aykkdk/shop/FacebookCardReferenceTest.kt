package com.aykkdk.shop

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class FacebookCardReferenceTest {
    private val source = "https://www.facebook.com/marketplace/114987875182625/search?minPrice=100&maxPrice=10000&query=computer&radius=250"
    private val label = "Phone + 手機 for sale - Used - Good - NT$2,000 in Taipei"
    private fun ref(url: String = source, value: String = label) = url + "#shopradar-fb-card=" +
        URLEncoder.encode(JSONObject().put("label", value).put("image", "/v/t39/phone.jpg").toString(), "UTF-8")

    @Test fun retainsExactSearchParametersAndDoesNotInventProductIds() {
        val parsed = FacebookCardReference.parse(ref())!!
        assertEquals(source, parsed.source)
        assertEquals(label, parsed.label)
        assertEquals("/v/t39/phone.jpg", parsed.image)
        assertNull(Market.fromPublicProductUrl(ref()))
    }
    @Test fun renderedCardIsAcceptedAsResultButNotAsDirectSubscriptionUrl() {
        val product = Product.parse(JSONObject().put("platform", "Facebook").put("title", "Phone")
            .put("price", 0).put("url", ref()))!!
        assertEquals(ref(), product.url)
        assertEquals(0L, product.price)
        assertNull(Market.FACEBOOK.normalizePublicProductUrl(product.url))
    }
    @Test fun realItemLinksRemainCanonicalProducts() {
        val url = "https://m.facebook.com/marketplace/item/1735314001032854/?ref=search"
        assertNull(FacebookCardReference.parse(url))
        assertNotNull(Product.parse(JSONObject().put("platform", "Facebook").put("title", "Phone").put("price", 2000).put("url", url)))
    }
    @Test fun rejectsLoginExternalHostsAndNonListingActions() {
        for (source in listOf("https://www.facebook.com/login", "https://www.facebook.com/settings",
            "https://www.facebook.com.evil.test/marketplace/search", "http://www.facebook.com/marketplace/search",
            "https://user:password@www.facebook.com/marketplace/search", "https://www.facebook.com:444/marketplace/search"))
            assertNull(FacebookCardReference.parse(ref(source)))
        assertNull(FacebookCardReference.parse(ref(value = "Delete account")))
    }
    @Test fun rejectsMalformedAndOversizedReferences() {
        assertNull(FacebookCardReference.parse(source + "#shopradar-fb-card=%not-json"))
        assertNull(FacebookCardReference.parse(ref(value = "a".repeat(2500) + " for sale - NT$1")))
    }
}
