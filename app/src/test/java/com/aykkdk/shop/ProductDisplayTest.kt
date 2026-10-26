package com.aykkdk.shop

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductDisplayTest {
    @Test fun spacesArePreservedButNotCounted() {
        val title = List(30) { "a" }.joinToString(" ")
        assertEquals(title, ProductDisplay.title(title))
        assertEquals(title + "...", ProductDisplay.title(title + "   b"))
    }
    @Test fun unicodeWhitespaceDoesNotConsumeTheLimit() {
        val title = List(30) { "a" }.joinToString("\u00a0\u3000\t\n")
        assertEquals(title, ProductDisplay.title(title))
        assertEquals(title + "...", ProductDisplay.title(title + "\u00a0\u3000b"))
        assertEquals(" \t\n\u3000", ProductDisplay.title(" \t\n\u3000"))
    }
    @Test fun spacedEmojiAreNotSplit() {
        val title = List(30) { "\uD83D\uDE00" }.joinToString(" ")
        assertEquals(title + "...", ProductDisplay.title(title + " \uD83D\uDE00"))
    }
    @Test fun shortTitlesRemainComplete() {
        assertEquals("手機", ProductDisplay.title("手機"))
        assertEquals("機".repeat(30), ProductDisplay.title("機".repeat(30)))
    }
    @Test fun longTitlesDisplayThirtyCharactersAndDots() {
        assertEquals("機".repeat(30) + "...", ProductDisplay.title("機".repeat(31)))
        assertEquals("😀".repeat(30) + "...", ProductDisplay.title("😀".repeat(31)))
    }
}
