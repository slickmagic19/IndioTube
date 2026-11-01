package com.indiotube.app

import com.indiotube.app.ui.WindowLayout
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val sixInchTabletPortrait = layout(600, 960, 600)
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
            assertTrue("${l.widthDp}dp rail is too wide", l.upNextRailWidth.value <= 460f)
        }
        assertEquals(360f, sevenInchLandscape.upNextRailWidth.value, 0.01f)
        assertEquals(409.6f, tabletLandscape.upNextRailWidth.value, 0.01f)
        assertEquals(460f, bigTablet.upNextRailWidth.value, 0.01f)
    }

    @Test
    fun gridCellsDropThePhoneCardPadding() {
        assertEquals(12f, phone.videoCardInset.value, 0.01f)
        assertEquals(8f, tabletPortrait.videoCardInset.value, 0.01f)
        assertEquals(8f, tabletLandscape.videoCardInset.value, 0.01f)
    }

    // A page of text rows stretched over a landscape tablet window reads as a
    // banner, so those pages are capped. Portrait and phones still fill.
    @Test
    fun aLandscapeTabletCapsTheSingleColumnPages() {
        assertEquals(720f, tabletLandscape.listPageWidth!!.value, 0.01f)
        assertEquals(900f, tabletLandscape.rowPageWidth!!.value, 0.01f)
        assertNull("tablet portrait has the room", tabletPortrait.listPageWidth)
        assertNull("tablet portrait has the room", tabletPortrait.rowPageWidth)
        assertNull(phone.listPageWidth)
        assertNull(phoneLandscape.rowPageWidth)
    }

    @Test
    fun continueWatchingCardsGrowWithTheWindow() {
        assertEquals(226f, phone.continueCardWidth.value, 0.01f)
        assertEquals(226f, phoneLandscape.continueCardWidth.value, 0.01f)
        assertEquals(240f, tabletPortrait.continueCardWidth.value, 0.01f)
        assertEquals(307.2f, tabletLandscape.continueCardWidth.value, 0.01f)
        assertEquals(340f, bigTablet.continueCardWidth.value, 0.01f)
    }

    @Test
    fun aTabletKeepsDialogsAndSheetsToACard() {
        assertEquals(560f, tabletLandscape.dialogMaxWidth.value, 0.01f)
        assertEquals(560f, tabletPortrait.dialogMaxWidth.value, 0.01f)
        assertEquals(Dp.Unspecified, phone.dialogMaxWidth)
    }

    @Test
    fun theMiniBarStaysOneLineTallOnATablet() {
        assertEquals(2, phone.miniPlayerTitleLines)
        assertEquals(1, tabletPortrait.miniPlayerTitleLines)
        assertEquals(1, tabletLandscape.miniPlayerTitleLines)
    }

    // The rail is sized by its widest label, so the ceiling only has to leave
    // room for that label. It floors on a narrow tablet, where a wide rail
    // would eat the feed, and grows with the window from there.
    @Test
    fun theRailHasRoomForItsLongestLabel() {
        listOf(tabletPortrait, tabletLandscape, bigTablet).forEach { l ->
            assertEquals(88f, l.navRailMinWidth.value, 0.01f)
            assertTrue("${l.widthDp}dp rail max is too tight", l.navRailMaxWidth.value >= 112f)
            assertTrue("${l.widthDp}dp rail max is too loose", l.navRailMaxWidth.value <= 136f)
        }
        assertEquals(112f, sixInchTabletPortrait.navRailMaxWidth.value, 0.01f)
        assertEquals(136f, tabletPortrait.navRailMaxWidth.value, 0.01f)
        assertEquals(136f, tabletLandscape.navRailMaxWidth.value, 0.01f)
    }
}
