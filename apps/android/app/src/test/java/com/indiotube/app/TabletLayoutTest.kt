package com.indiotube.app

import com.indiotube.app.ui.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The tablet layout is a policy, not a pile of if-statements in each screen.
// These tests pin the breakpoints: a phone must never pick up the rail, a
// landscape tablet must split the watch page, and a portrait tablet must keep
// the player page as one column.
class TabletLayoutTest {

    private fun layout(width: Int, height: Int, smallest: Int) =
        WindowLayout(widthDp = width, heightDp = height, smallestWidthDp = smallest)

    private val phone = layout(411, 891, 411)
    private val phoneLandscape = layout(891, 411, 411)
    private val tabletPortrait = layout(800, 1280, 800)
    private val tabletLandscape = layout(1280, 800, 800)
    private val sevenInchLandscape = layout(1024, 600, 600)
    private val bigTablet = layout(1600, 1000, 1000)

    @Test
    fun aPhoneKeepsTheBottomBarAndOneColumn() {
        assertFalse(phone.isTablet)
        assertFalse(phone.useNavigationRail)
        assertEquals(1, phone.feedColumns)
        assertEquals(1, phoneLandscape.feedColumns)
        assertFalse(phoneLandscape.useNavigationRail)
    }

    @Test
    fun aPhoneTurnedSidewaysStillRotatesIntoFullscreen() {
        assertTrue(phoneLandscape.autoFullscreenInLandscape)
        assertFalse(phoneLandscape.watchTwoPane)
    }

    @Test
    fun aTabletInPortraitUsesTheRailAndTwoColumns() {
        assertTrue(tabletPortrait.useNavigationRail)
        assertEquals(2, tabletPortrait.feedColumns)
        assertFalse("portrait tablets keep one watch column", tabletPortrait.watchTwoPane)
        assertFalse(
            "a tablet rotation must not mean fullscreen",
            tabletPortrait.autoFullscreenInLandscape,
        )
    }

    @Test
    fun aTabletInLandscapeSplitsTheWatchPage() {
        assertTrue(tabletLandscape.useNavigationRail)
        assertEquals(3, tabletLandscape.feedColumns)
        assertTrue(tabletLandscape.watchTwoPane)
        assertFalse(tabletLandscape.autoFullscreenInLandscape)
        assertTrue(sevenInchLandscape.watchTwoPane)
    }

    @Test
    fun aVeryWideTabletGetsAFourthFeedColumn() {
        assertEquals(4, bigTablet.feedColumns)
    }

    @Test
    fun anUpNextRailStaysReadableBesideThePlayer() {
        listOf(tabletLandscape, sevenInchLandscape, bigTablet).forEach { l ->
            assertTrue("${l.widthDp}dp rail is too narrow", l.upNextRailWidth.value >= 360f)
            assertTrue("${l.widthDp}dp rail is too wide", l.upNextRailWidth.value <= 420f)
        }
        assertEquals(360f, sevenInchLandscape.upNextRailWidth.value, 0.01f)
        assertEquals(384f, tabletLandscape.upNextRailWidth.value, 0.01f)
    }

    @Test
    fun gridCellsDropThePhoneCardPadding() {
        assertEquals(12f, phone.videoCardInset.value, 0.01f)
        assertEquals(8f, tabletPortrait.videoCardInset.value, 0.01f)
        assertEquals(8f, tabletLandscape.videoCardInset.value, 0.01f)
    }
}
