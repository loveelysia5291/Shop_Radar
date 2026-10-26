package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class PublicShopMetadataTest {
    @Test fun shopeeMobileStoreTitleWorksWithoutDesktopHeadingOrOgUrl() {
        val url = "https://shopee.tw/z800606z#product_list"
        val title = "<title>💛🧡華心3C代購🧡💛, 線上商店 | 蝦皮購物</title>"
        assertEquals("💛🧡華心3C代購🧡💛", PublicShopMetadata.parse(url, title))
        assertNull(PublicShopMetadata.parse(url, "<title>登入 | 蝦皮購物</title>"))
        assertNull(PublicShopMetadata.parse("https://shopee.tw/buyer/login", title))
        assertNull(PublicShopMetadata.parse(url, "<meta property='og:url' content='https://shopee.tw/other_shop'>" + title))
    }
    @Test fun shopeeUsesOnlyDocumentTitleAndRetriesOldAccountName() {
        val url = "https://shopee.tw/z800606z"
        assertEquals("💛🧡華心3C代購🧡💛", PublicShopMetadata.parse(url,
            "<title>💛🧡華心3C代購🧡💛, 線上商店 | 蝦皮購物</title>" +
                "<meta property='og:url' content='$url'><meta property='og:title' content='其他名稱'>" +
                "<h1 class='section-seller-overview-horizontal__portrait-name'>其他名稱</h1>"))
        assertNull(PublicShopMetadata.parse(url, "<meta property='og:url' content='$url'>" +
            "<meta property='og:title' content='華心, 線上商店 | 蝦皮購物'><h1>華心</h1>"))
        assertTrue(PublicShopMetadata.needsRead(url, "z800606z", Market.SHOPEE))
        assertFalse(PublicShopMetadata.needsRead(url, "💛🧡華心3C代購🧡💛", Market.SHOPEE))
    }
    private val profile = "https://tw.carousell.com/u/2hanhandgood2/"
    private val profileMeta = "<meta property='og:url' content='$profile'><meta property='og:title' content='2hanhandgood2在旋轉拍賣上'>"

    @Test fun carousellUsesObservedDisplayHeadingNotSharingTitle() {
        val name = "二手不退換高標勿買 急單看關於我"
        assertEquals(name, PublicShopMetadata.parse(profile, profileMeta +
            "<h1 class='D_ajg'>$name</h1><h1 class='D_bph'>$name</h1>"))
        assertNull(PublicShopMetadata.parse(profile, profileMeta))
    }
    @Test fun profileIdentityAndAmbiguousHeadingsAreRejected() {
        assertNull(PublicShopMetadata.parse("https://tw.carousell.com/u/small09/", profileMeta + "<h1>David</h1>"))
        assertNull(PublicShopMetadata.parse(profile, profileMeta + "<h1>甲店</h1><h1>乙店</h1>"))
        assertNull(PublicShopMetadata.parse(profile, profileMeta + "<h1>Access denied</h1>"))
    }
    @Test fun profileHeadingIgnoresScriptsAndDecodesPublicText() {
        assertEquals("甲 & 乙 😀", PublicShopMetadata.parse(profile, profileMeta +
            "<script>const fake='<h1>假店</h1>'</script><!-- <h1>假店</h1> --><h1>甲 &amp; <span>乙</span>&nbsp;&#x1F600;</h1>"))
    }
    @Test fun legacyUsernameNamesAreRetriedWithoutRereadingGoodDisplayNames() {
        assertTrue(PublicShopMetadata.needsRead(profile, "2hanhandgood2", Market.CAROUSELL))
        assertTrue(PublicShopMetadata.needsRead(profile, "2hanhandgood2在旋轉拍賣上", Market.CAROUSELL))
        assertFalse(PublicShopMetadata.needsRead(profile, "二手不退換高標勿買 急單看關於我", Market.CAROUSELL))
    }
    @Test fun observedUsernameStoreCanProvideNameWithoutProductCards() {
        assertEquals("Tanyulin704080", PublicShopMetadata.parse("https://shopee.tw/tanyulin704080?sortBy=ctime",
            "<meta property='og:url' content='https://shopee.tw/tanyulin704080'><title>Tanyulin704080, 線上商店 | 蝦皮購物</title>"))
    }
    @Test fun wrongShopLoginAndProductMetadataAreRejected() {
        val html = "<meta property='og:url' content='https://shopee.tw/other_shop'><meta property='og:title' content='別的店家 | 蝦皮購物'>"
        assertNull(PublicShopMetadata.parse("https://shopee.tw/tanyulin704080", html))
        assertNull(PublicShopMetadata.parse("https://shopee.tw/buyer/login", html))
        assertNull(PublicShopMetadata.parse("https://shopee.tw/product/1/2", html))
    }
}
