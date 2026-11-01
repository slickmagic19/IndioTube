package com.indiotube.app.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val upNextRailWidth: Dp get() = (widthDp * 0.32f).coerceIn(360f, 460f).dp

    // Gutters inside a feed cell: the grid already spaces the cards, so the
    // phone card's own 12dp side padding would double up.
    val videoCardInset: Dp get() = if (feedColumns > 1) 8.dp else 12.dp
    val videoCardGap: Dp get() = if (feedColumns > 1) 16.dp else 14.dp

    // A page that is one column of text rows - Settings, You, Downloads, a
    // library list - stops reading as a list and starts reading as a stretched
    // banner once the window is a landscape tablet wide. YouTube caps those
    // pages instead of filling the window, so the column is centred with room
    // either side. Phones and tablet portrait keep the full width.
    val listPageWidth: Dp? get() = if (isTablet && widthDp >= TWO_PANE_MIN_WIDTH_DP) 720.dp else null

    // Search results and playlist rows carry a thumbnail as well, so they can
    // afford to be wider than a page of plain rows.
    val rowPageWidth: Dp? get() = if (isTablet && widthDp >= TWO_PANE_MIN_WIDTH_DP) 900.dp else null

    // Continue watching keeps the phone's card shape on a phone. A tablet row
    // has room for bigger thumbnails, and a fixed width leaves the rest of the
    // pane empty, so the card is a fraction of the window instead.
    val continueCardWidth: Dp
        get() = if (!isTablet) 226.dp else (widthDp * 0.24f).coerceIn(240f, 340f).dp

    // A dialog that stretches across a landscape tablet reads as a band of
    // text rather than a card. Material caps dialogs on phones, so only the
    // tablet asks for a limit.
    val dialogMaxWidth: Dp get() = if (isTablet) 560.dp else Dp.Unspecified

    // The mini player is a bar, not a page. Two title lines are worth the
    // height on a phone, where the thumb is small; a tablet bar stays one
    // line tall so it never eats the content above it.
    val miniPlayerTitleLines: Int get() = if (isTablet) 1 else 2

    // The left rail. The rail is sized by its widest label, so this is only a
    // ceiling: it floors at 112dp so the rail never crowds the content on a
    // narrow tablet, and grows with the window so a large system font scale
    // can spell out "Subscriptions" instead of ellipsizing it. Past 136dp the
    // rail stops reading as a rail and starts reading as a drawer.
    val navRailMinWidth: Dp get() = 88.dp
    val navRailMaxWidth: Dp get() = (widthDp * 0.18f).coerceIn(112f, 136f).dp
}

// The width a page of rows or dialogs is allowed to take, centred in whatever
// room the window gives it. Null means the page fills the window, which is the
// phone behaviour and the tablet portrait behaviour.
@Composable
fun Modifier.tabletPageWidth(cap: Dp?): Modifier = if (cap == null) {
    fillMaxWidth()
} else {
    // Box wraps its content, so the page is centred by the Box it sits in.
    fillMaxHeight().widthIn(max = cap)
}

// Every dialog and sheet shares one width rule so a landscape tablet never
// gets a control that runs from one edge of the screen to the other.
@Composable
fun tabletDialogWidth(): Modifier = Modifier.widthIn(max = rememberWindowLayout().dialogMaxWidth)

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
