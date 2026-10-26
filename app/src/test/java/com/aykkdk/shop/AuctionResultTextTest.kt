package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class AuctionResultTextTest {
    @Test fun anotherWinnerUsesPublicAccountId() {
        assertEquals("得標者：Y9300266938", AuctionResultText.winner(listOf("Y9300266938"), 3, "another", true))
    }
    @Test fun ownWinnerUsesExactUnmaskedId() {
        assertEquals("你已得標", AuctionResultText.winner(listOf("Y9300266938"), 3, " y9300266938 ", true))
    }
    @Test fun nicknamesAndMaskedIdsDoNotMeanOwnWin() {
        assertEquals("得標者：Y9300266938", AuctionResultText.winner(listOf("Y9300266938"), 3, "同名暱稱", true))
        assertEquals("得標者：Y93***", AuctionResultText.winner(listOf("Y93***"), 3, "Y93***", true))
        assertEquals("得標者：Y93...", AuctionResultText.winner(listOf("Y93..."), 3, "Y93...", true))
    }
    @Test fun staleLeadingSnapshotNeverClaimsAWin() {
        assertEquals("正在確認得標者…", AuctionResultText.winner(listOf("Y9300266938"), 3, "Y9300266938", false))
    }
    @Test fun noBidsAndUndisclosedWinnerAreDistinct() {
        assertEquals("無人出價", AuctionResultText.winner(emptyList(), 0, "me", true))
        assertEquals("得標者：平台未公開", AuctionResultText.winner(emptyList(), 2, "me", true))
    }
    @Test fun multipleWinnersAreNotCollapsed() {
        assertEquals("你已得標；其他得標者：Y2", AuctionResultText.winner(listOf(" Y1 ", "Y2", "y1"), 2, "Y1", true))
        assertEquals("得標者：Y1、Y2", AuctionResultText.winner(listOf("Y1", "Y2"), 2, "", true))
    }
    @Test fun productHistoryPreservesServerConfirmedAuctionResultFields() {
        val product = Product("手機競標", 100, Market.YAHOO.label, "https://tw.bid.yahoo.com/item/101764768005",
            isAuction = true, auctionEndsAt = 1000, highestBidder = "Y1", auctionServerAt = 2000, auctionBidCount = 4)
        assertEquals(product, Product.parse(product.json()))
    }
    @Test fun oldProductCachesDoNotPretendFinalResultWasVerified() {
        val product = Product("舊商品", 100, Market.YAHOO.label, "https://tw.bid.yahoo.com/item/101764768005", isAuction = true)
        val json = product.json().apply { remove("auctionServerAt"); remove("auctionBidCount") }
        val restored = Product.parse(json)!!
        assertEquals(0L, restored.auctionServerAt)
        assertEquals(-1, restored.auctionBidCount)
    }
}
