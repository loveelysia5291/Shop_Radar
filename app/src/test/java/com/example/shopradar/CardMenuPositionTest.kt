package com.example.shopradar

import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test

class CardMenuPositionTest {
    @Test fun leftAndRightCardsNeverPlaceMenusOverTheirNeighbour() {
        for (left in listOf(16, 208)) {
            val card = IntRect(left, 100, left + 176, 680)
            val size = IntSize(164, 300)
            for (direction in LayoutDirection.values()) {
                val pos = CardMenuPosition(card, 6, false).calculatePosition(IntRect(left + 128, 106, left + 176, 154), IntSize(400, 800), direction, size)
                assertTrue(pos.x >= card.left)
                assertTrue(pos.x + size.width <= card.right)
                assertEquals(154, pos.y)
            }
        }
    }
    @Test fun headingMenuClampsUpToFitInsideCard() {
        val pos = CardMenuPosition(IntRect(16, 100, 192, 620), 6, true)
            .calculatePosition(IntRect(26, 420, 182, 480), IntSize(400, 800), LayoutDirection.Ltr, IntSize(164, 260))
        assertEquals(354, pos.y)
        assertEquals(22, pos.x)
    }
    @Test fun partlyScrolledCardClampsToVisibleWindow() {
        val pos = CardMenuPosition(IntRect(16, -100, 192, 360), 6, false)
            .calculatePosition(IntRect(140, -100, 188, -52), IntSize(400, 800), LayoutDirection.Ltr, IntSize(164, 250))
        assertEquals(0, pos.y)
    }
}
