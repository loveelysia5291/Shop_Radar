package com.example.shopradar

import org.junit.Assert.*
import org.junit.Test

class MetadataNetworkPolicyTest {
    @Test fun metadataReadersSkipOnlyUnneededMediaAndFonts() {
        for (extension in listOf("jpg", "jpeg", "png", "webp", "avif", "gif", "svg", "mp4", "webm", "mp3", "woff", "woff2", "ttf"))
            assertTrue(extension, MetadataNetworkPolicy.blocks("https://cdn.example/image.$extension?signature=unchanged"))
        assertTrue(MetadataNetworkPolicy.blocks("https://cdn.example/font.WOFF2"))
        assertTrue(MetadataNetworkPolicy.blocks("https://down-tw.img.susercontent.com/file/opaque-image-id"))
        assertFalse(MetadataNetworkPolicy.blocks("https://down-tw.img.susercontent.com/assets/application.js"))
    }
    @Test fun htmlScriptsStylesApisAndSignInRemainAllowed() {
        for (url in listOf("https://shopee.tw/product/123/456", "https://shopee.tw/buyer/login",
            "https://www.facebook.com/marketplace/item/123456789/", "https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1234",
            "https://cdn.example/app.js?name=image.webp", "https://cdn.example/layout.css", "https://shopee.tw/api/v4/item/get",
            "https://cdn.example/image?format=webp", "https://cdn.example/font.woff2.js"))
            assertFalse(url, MetadataNetworkPolicy.blocks(url))
    }
    @Test fun malformedOrNonNetworkUrlsAreNotIntercepted() {
        for (url in listOf("invalid url", "data:image/png;base64,AA", "file:///font.woff2", "about:blank"))
            assertFalse(MetadataNetworkPolicy.blocks(url))
    }
    @Test fun mainDocumentIsNeverReplaced() {
        assertNull(MetadataNetworkPolicy.response("https://cdn.example/image.jpg", mainFrame = true))
        assertNull(MetadataNetworkPolicy.response("https://shopee.tw/api/v4/item/get", mainFrame = false))
    }
}
