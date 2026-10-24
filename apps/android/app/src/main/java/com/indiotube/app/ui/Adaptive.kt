package com.indiotube.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Tablet layout policy. YouTube's tablet app is not the phone layout stretched
// over a bigger screen: it swaps the bottom bar for a navigation rail, shows
// the feed in two or three columns and splits the watch page into the video
// with its page on the left and "Up next" in a rail on the right. These rules
// are pure so every breakpoint can be tested without a device.
const val TABLET_MIN_SMALLEST_WIDTH_DP = 600
const val TWO_PANE_MIN_WIDTH_DP = 840

data class WindowLayout(
    val widthDp: Int,
    val heightDp: Int,
    val smallestWidthDp: Int,
) {
    val isLandscape: Boolean get() = widthDp > heightDp

    // smallestScreenWidthDp, not the current width: a phone turned sideways is
    // wide but is still a phone, and must keep the phone layout (and its
    // rotate-to-fullscreen behaviour).
    val isTablet: Boolean get() = smallestWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP

    val useNavigationRail: Boolean get() = isTablet

    // Columns in a feed of 16:9 cards. Phones keep the single column; tablets
    // get two in portrait and three in landscape, with a fourth on very wide
    // screens so the cards do not turn into billboards.
    val feedColumns: Int
        get() = when {
            !isTablet -> 1
            widthDp >= 1400 -> 4
            widthDp >= 900 -> 3
            else -> 2
        }

    // A landscape tablet has room for the watch page's two columns. In portrait
    // the page stays a single scrolling column below the player.
    val watchTwoPane: Boolean
        get() = isTablet && isLandscape && widthDp >= TWO_PANE_MIN_WIDTH_DP

    // Rotating a phone to landscape is YouTube's fullscreen gesture. On a
    // tablet the same rotation has to reveal the two-pane watch page instead.
    val autoFullscreenInLandscape: Boolean get() = isLandscape && !isTablet

    // The right-hand "Up next" rail. Wide enough for a thumbnail row and a
    // readable two-line title, capped so it never squeezes the player.
    val upNextRailWidth: Dp get() = (widthDp * 0.30f).coerceIn(360f, 420f).dp

    // Gutters inside a feed cell: the grid already spaces the cards, so the
    // phone card's own 12dp side padding would double up.
    val videoCardInset: Dp get() = if (feedColumns > 1) 8.dp else 12.dp
    val videoCardGap: Dp get() = if (feedColumns > 1) 16.dp else 14.dp
}

@Composable
fun rememberWindowLayout(): WindowLayout {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        WindowLayout(
            widthDp = configuration.screenWidthDp,
            heightDp = configuration.screenHeightDp,
            smallestWidthDp = configuration.smallestScreenWidthDp,
        )
    }
}
