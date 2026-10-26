package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class PublicShareLinkResolverTest {
    @Test fun globalProfileRedirectAndDeepviewResolveWithoutLogin() {
        val destination = "https://tw.carousell.com/u/dodonatsu/"
        for (host in listOf("carousell.com", "www.carousell.com", "tw.carousell.com")) {
            assertEquals(destination, PublicShareLinkResolver.resolveWith("https://carousell.app.link/Z5HJgYgNa7b") {
                ShareLinkResponse(307, "https://$host/u/dodonatsu/?utm_source=share-native")
            })
            assertEquals(destination, PublicShareLinkResolver.destinationFromDeepview(
                "<a class='secondary-action' href='https://$host/u/dodonatsu/'>Open</a>"))
        }
        assertNull(PublicShareLinkResolver.canonicalCarousellDestination("https://carousell.com/u/dodonatsu/settings"))
    }
    private val share = "https://carousell.app.link/yGe14iDNU6b"
    private val item = "https://tw.carousell.com/p/1464417552/"

    @Test fun profileSharePreservesItsDestinationNotAnotherAnnotatedShop() {
        val destination = "https://tw.carousell.com/u/small09/"
        assertEquals(destination, PublicShareLinkResolver.resolveWith("https://carousell.app.link/GeyS12Epa7b") {
            ShareLinkResponse(302, destination + "?utm_source=share-copylink")
        })
        assertFalse(SubscriptionIdentity.sameShop(destination, "https://tw.carousell.com/u/2hanhandgood2/"))
    }

    @Test fun observedShopeeShortUrlsResolveToDifferentSubscriptionKindsWithoutFetchingDestination() {
        assertEquals("https://shopee.tw/tanyulin704080", PublicShareLinkResolver.resolveWith("https://tw.shp.ee/repbigFD") {
            ShareLinkResponse(301, "https://shopee.tw/tanyulin704080?uls_trackid=tracking&utm_content=share")
        })
        assertEquals("https://shopee.tw/product/161121855/24672618478", PublicShareLinkResolver.resolveWith("https://tw.shp.ee/XR3xdAaK") {
            ShareLinkResponse(301, "https://shopee.tw/product/161121855/24672618478?d_id=tracking&utm_content=share")
        })
        assertEquals("https://tw.carousell.com/p/1466478450/", PublicShareLinkResolver.resolveWith("https://carousell.app.link/zf5xSsO226b") {
            ShareLinkResponse(307, "https://carousell.com/p/1466478450?_branch_match_id=tracking")
        })
    }
    @Test fun shortenersCannotSwitchPlatformsOrResolvePrivateDestinations() {
        for (target in listOf("https://carousell.app.link/otherToken", "https://shopee.tw/buyer/login",
            "https://shopee.tw/search", "https://tw.shp.ee.evil.test/otherToken", "http://shopee.tw/product/1/2")) {
            var calls = 0
            assertRejected { PublicShareLinkResolver.resolveWith("https://tw.shp.ee/repbigFD") { calls++; ShareLinkResponse(302, target) } }
            assertEquals(1, calls)
        }
    }
    @Test fun shopeePublicShopMetadataCanResolveA200FallbackWithoutExecutingScripts() {
        assertEquals("https://shopee.tw/shop/161121855", PublicShareLinkResolver.destinationFromDeepview(
            "<link rel='canonical' href='https://shopee.tw/shop/161121855?tracking=x'>", Market.SHOPEE))
        assertNull(PublicShareLinkResolver.destinationFromDeepview("<script>location.href='https://shopee.tw/shop/161121855'</script>", Market.SHOPEE))
    }

    @Test fun existingMarketplaceUrlNeverFetches() {
        val raw = " https://m.tw.carousell.com/p/example-1464417552/?ref=share "
        assertEquals(raw.trim(), PublicShareLinkResolver.resolveWith(raw) { error("Must not fetch") })
        assertEquals("https://www.ruten.com.tw/store/example/list",
            PublicShareLinkResolver.resolveWith("https://www.ruten.com.tw/store/example/list") { error("Must not fetch") })
    }

    @Test fun observedBranchBrowserFallbackBecomesCanonicalTaiwanListing() {
        val html = """<a class="action" href="p/1464417552?tracking=app">Open app</a>
            <a class="secondary-action" href="https://carousell.com/p/1464417552?utm_source=share&amp;ref=app">open this link in your browser.</a>
            <script>validateProtocol("https://carousell.com/p/1464417552?tracking=script")</script>"""
        assertEquals(item, PublicShareLinkResolver.resolveWith(share) { ShareLinkResponse(200, body = html) })
    }

    @Test fun publishedMetadataAndQuotedAttributesAreAcceptedWithoutScripts() {
        assertEquals(item, PublicShareLinkResolver.destinationFromDeepview(
            "<A HREF='https://www.carousell.com/p/1464417552?ref=share' CLASS='button secondary-action'>Open</A>"))
        assertEquals(item, PublicShareLinkResolver.destinationFromDeepview(
            "<meta content='https://carousell.com/p/1464417552/' property='og:url'>"))
        assertEquals(item, PublicShareLinkResolver.destinationFromDeepview(
            "<link href='https://carousell.com/p/1464417552/' rel='canonical'>"))
        assertNull(PublicShareLinkResolver.destinationFromDeepview(
            "<script>location.href='https://carousell.com/p/1464417552'</script>"))
        assertNull(PublicShareLinkResolver.destinationFromDeepview(
            "<a href='https://carousell.com/p/1464417552/'>unrelated anchor</a>"))
    }

    @Test fun onlyTheExactHttpsShortenerIsFetched() {
        listOf("http://carousell.app.link/yGe14iDNU6b", "https://carousell.app.link.evil.example/yGe14iDNU6b",
            "https://evil.example/yGe14iDNU6b", "https://127.0.0.1/yGe14iDNU6b",
            "https://account:secret@carousell.app.link/yGe14iDNU6b", "https://carousell.app.link:443/yGe14iDNU6b",
            "https://carousell.app.link/", "315mi1111", "carousell.app.link/yGe14iDNU6b").forEach { raw ->
            assertNull(PublicShareLinkResolver.validatedShareUrl(raw))
            assertRejected { PublicShareLinkResolver.resolveWith(raw) { error("Unsupported input must not fetch") } }
        }
    }

    @Test fun officialDestinationStopsBeforeFetchingTheListing() {
        val requests = mutableListOf<String>()
        assertEquals(item, PublicShareLinkResolver.resolveWith(share) { url ->
            requests += url
            ShareLinkResponse(302, "https://carousell.com/p/1464417552?ref=share")
        })
        assertEquals(listOf(share), requests)
    }

    @Test fun relativeShortenerRedirectsRemainBounded() {
        val requests = mutableListOf<String>()
        assertEquals(item, PublicShareLinkResolver.resolveWith(share) { url ->
            requests += url
            if (requests.size == 1) ShareLinkResponse(302, "/otherShareToken?flow=public")
            else ShareLinkResponse(200, body = "<a class='secondary-action' href='$item'>Open</a>")
        })
        assertEquals(listOf(share, "https://carousell.app.link/otherShareToken?flow=public"), requests)
        var count = 0
        assertRejected {
            PublicShareLinkResolver.resolveWith(share) { ShareLinkResponse(302, "/nextShare${++count}") }
        }
        assertEquals(5, count)
        assertRejected { PublicShareLinkResolver.resolveWith(share) { ShareLinkResponse(302, share) } }
    }

    @Test fun unsafeAndNonListingDestinationsAreRejectedBeforeAnotherRequest() {
        listOf("http://carousell.com/p/1464417552/", "https://carousell.com.evil.example/p/1464417552/",
            "https://user:secret@carousell.com/p/1464417552/", "https://carousell.com:443/p/1464417552/",
            "https://127.0.0.1/p/1464417552/", "https://carousell.com/login", "https://carousell.com/u/login/",
            "https://tw.carousell.com/search/phone", "https://tw.carousell.com/login/",
            "https://tw.carousell.com/categories/", "https://tw.carousell.com/p/../item-1464417552/",
            "https://tw.carousell.com/p/bad%2F1464417552/").forEach { target ->
            assertNull(PublicShareLinkResolver.canonicalCarousellDestination(target))
            var count = 0
            assertRejected { PublicShareLinkResolver.resolveWith(share) { count++; ShareLinkResponse(302, target) } }
            assertEquals(1, count)
        }
    }

    @Test fun publicTaiwanShopLinksAreValidatedButRawUsernamesAreNotExpanded() {
        assertEquals("https://tw.carousell.com/u/example_shop/",
            PublicShareLinkResolver.canonicalCarousellDestination("https://m.tw.carousell.com/u/example_shop/?ref=share"))
        assertEquals("https://tw.carousell.com/example.shop/",
            PublicShareLinkResolver.canonicalCarousellDestination("https://tw.carousell.com/example.shop/?sort_by=3"))
        assertNull(PublicShareLinkResolver.canonicalCarousellDestination("example_shop"))
        assertNull(PublicShareLinkResolver.canonicalCarousellDestination("https://carousell.com/example_shop/"))
    }

    @Test fun contradictoryFallbacksAndOversizedBodiesFailClosed() {
        assertNull(PublicShareLinkResolver.destinationFromDeepview(
            "<a class='secondary-action' href='$item'>Open</a><a class='secondary-action' href='https://carousell.com/p/9999999999'>Other</a>"))
        assertNull(PublicShareLinkResolver.destinationFromDeepview(
            "<a class='secondary-action' href='https://evil.example/p/1464417552'>Open</a><link rel='canonical' href='$item'>"))
        val huge = "x".repeat(PublicShareLinkResolver.MAX_BODY_BYTES + 1)
        assertNull(PublicShareLinkResolver.destinationFromDeepview(huge))
        assertRejected { PublicShareLinkResolver.resolveWith(share) { ShareLinkResponse(200, body = huge) } }
        assertRejected { PublicShareLinkResolver.resolveWith(share) { ShareLinkResponse(403) } }
    }

    @Test fun localAndReservedNetworkAddressesAreRejected() {
        listOf("127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1",
            "100.64.0.1", "0.0.0.0", "192.0.2.1", "198.18.0.1", "203.0.113.1", "224.0.0.1",
            "::1", "fc00::1", "fe80::1", "2001:db8::1").forEach {
            assertFalse(it, PublicShareLinkResolver.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(PublicShareLinkResolver.isPublicAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(PublicShareLinkResolver.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected the link to be rejected")
        } catch (_: IllegalArgumentException) {
            // Policy failures are surfaced to the caller without a subscription.
        }
    }
}
