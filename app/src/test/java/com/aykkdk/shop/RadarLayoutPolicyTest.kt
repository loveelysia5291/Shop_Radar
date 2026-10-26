package com.aykkdk.shop

import org.junit.Assert.*
import org.junit.Test

class RadarLayoutPolicyTest {
    @Test fun productHeaderReservesEqualActionSlotsAndReadableTitle() {
        assertEquals(48, RadarLayoutPolicy.CARD_ACTION_SIZE)
        assertFalse(RadarLayoutPolicy.inlineCardHeader(247f, 3, false))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(248f, 3, false))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(328f, 3, false))
    }

    @Test fun shopHeaderAlsoReservesPlatformAndSpacing() {
        assertFalse(RadarLayoutPolicy.inlineCardHeader(287f, 2, true))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(288f, 2, true))
        assertFalse(RadarLayoutPolicy.inlineCardHeader(151f, 1, false))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(152f, 1, false))
    }

    @Test fun narrowPhotoCardsAndLargeTextUseSeparateHeaderRows() {
        assertFalse(RadarLayoutPolicy.inlineCardHeader(164f, 3, false))
        assertFalse(RadarLayoutPolicy.inlineCardHeader(144f, 2, true))
        assertFalse(RadarLayoutPolicy.inlineCardHeader(328f, 3, false, 2f))
        assertFalse(RadarLayoutPolicy.inlineCardHeader(328f, 2, true, 1.5f))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(640f, 3, false, 2f))
    }

    @Test fun invalidHeaderConstraintsCannotSelectInlineLayout() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(RadarLayoutPolicy.inlineCardHeader(width, 3, false))
        }
        assertFalse(RadarLayoutPolicy.inlineCardHeader(640f, -1, false))
        assertFalse(RadarLayoutPolicy.inlineCardHeader(640f, 4, false))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(248f, 3, false, Float.NaN))
        assertTrue(RadarLayoutPolicy.inlineCardHeader(248f, 3, false, 0.5f))
    }

    @Test fun photoCardsUseTwoColumnsOnNormalPhones() {
        for (width in listOf(288f, 328f, 360f, 398f, 568f)) {
            assertEquals(2, RadarLayoutPolicy.photoColumns(width))
        }
        assertEquals(1, RadarLayoutPolicy.photoColumns(240f))
    }

    @Test fun photoCardsAdaptToTabletWidthAndLargeText() {
        assertEquals(4, RadarLayoutPolicy.photoColumns(992f))
        assertEquals(4, RadarLayoutPolicy.photoColumns(1600f))
        assertEquals(1, RadarLayoutPolicy.photoColumns(328f, 1.5f))
        assertEquals(1, RadarLayoutPolicy.photoColumns(360f, 2f))
        assertEquals(3, RadarLayoutPolicy.photoColumns(992f, 2f))
    }

    @Test fun invalidPhotoConstraintsRemainSafe() {
        for (width in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1, RadarLayoutPolicy.photoColumns(width))
        }
        assertEquals(2, RadarLayoutPolicy.photoColumns(360f, Float.NaN))
    }

    @Test fun phonesKeepOneColumn() {
        listOf(320f, 360f, 392f, 600f).forEach { width ->
            assertEquals(1, RadarLayoutPolicy.cardColumns(width))
        }
    }

    @Test fun columnBreakpointsAccountForSpacing() {
        assertEquals(1, RadarLayoutPolicy.cardColumns(731f))
        assertEquals(2, RadarLayoutPolicy.cardColumns(732f))
        assertEquals(2, RadarLayoutPolicy.cardColumns(1103f))
        assertEquals(3, RadarLayoutPolicy.cardColumns(1104f))
        assertEquals(3, RadarLayoutPolicy.cardColumns(2000f))
    }

    @Test fun resizedWindowsUseOnlyAvailableContentWidth() {
        listOf(992f to 2, 480f to 1, 1248f to 3, 700f to 1).forEach { (width, columns) ->
            assertEquals(columns, RadarLayoutPolicy.cardColumns(width))
        }
    }

    @Test fun enlargedTextGetsWiderCards() {
        assertEquals(2, RadarLayoutPolicy.cardColumns(1248f, 1.5f))
        assertEquals(1, RadarLayoutPolicy.cardColumns(992f, 2f))
        assertEquals(3, RadarLayoutPolicy.cardColumns(1248f, 0.85f))
    }

    @Test fun tabletGridsUseFullPagerWidthWithoutASidebar() {
        // The bottom bar takes height, never an 80dp slice of the content width.
        assertEquals(3, RadarLayoutPolicy.cardColumns(1280f - 32f))
        assertEquals(2, RadarLayoutPolicy.cardColumns(1024f - 32f))
        assertEquals(2, RadarLayoutPolicy.cardColumns(800f - 32f))
        assertEquals(1, RadarLayoutPolicy.cardColumns(600f - 32f))
    }

    @Test fun tabletSplitWindowsAndLargeTextReduceColumns() {
        assertEquals(1, RadarLayoutPolicy.cardColumns(1280f - 32f, 2f))
        assertEquals(1, RadarLayoutPolicy.cardColumns(640f - 32f, 1.5f))
        assertEquals(2, RadarLayoutPolicy.cardColumns(1280f - 32f, 1.5f))
        assertEquals(1, RadarLayoutPolicy.photoColumns(320f - 32f, 2f))
    }

    @Test fun invalidConstraintsAreSafe() {
        listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(1, RadarLayoutPolicy.cardColumns(it))
        }
        assertEquals(2, RadarLayoutPolicy.cardColumns(992f, Float.NaN))
    }
    @Test fun subscriptionImageControlsNeverOverlapAcrossGridBreakpoints() {
        for (width in listOf(288f, 300f, 312f, 328f, 360f, 600f, 992f, 1600f)) {
            val columns = RadarLayoutPolicy.subscriptionPhotoColumns(width)
            val card = (width - (columns - 1) * 12f) / columns
            assertTrue(card >= 144f)
        }
        assertEquals(1, RadarLayoutPolicy.subscriptionPhotoColumns(288f))
        assertEquals(2, RadarLayoutPolicy.subscriptionPhotoColumns(312f))
        assertEquals(2, RadarLayoutPolicy.photoColumns(288f)) // Search layout is unchanged.
    }
}
