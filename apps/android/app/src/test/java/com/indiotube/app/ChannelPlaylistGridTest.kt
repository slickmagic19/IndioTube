package com.indiotube.app

import com.indiotube.app.data.PlaylistItem
import com.indiotube.app.ui.playlistGridColumns
import com.indiotube.app.ui.playlistGridRows
import org.junit.Assert.assertEquals
import org.junit.Test

// The channel Playlists tab is a grid of cards, the way YouTube shows it.
// The old layout put a single small cover in a full-width row, which is what
// made the tab look broken. These tests pin the grid geometry the screen
// builds its rows from; ChannelPlaylistLayoutTest renders them.
class ChannelPlaylistGridTest {

    private fun playlists(count: Int) = (1..count).map {
        PlaylistItem("pl$it", "Playlist $it")
    }

    @Test
    fun columnCountFollowsTheScreenWidth() {
        assertEquals("a narrow screen gets a single column", 1, playlistGridColumns(280f))
        assertEquals("a phone gets two columns", 2, playlistGridColumns(360f))
        assertEquals("two columns up to a small tablet", 2, playlistGridColumns(599f))
        assertEquals("a small tablet gets three", 3, playlistGridColumns(600f))
        assertEquals("a large tablet gets four", 4, playlistGridColumns(840f))
        assertEquals("a desktop window keeps four", 4, playlistGridColumns(1200f))
    }

    @Test
    fun playlistsAreChunkedIntoRows() {
        val rows = playlistGridRows(playlists(5), 2)
        assertEquals(3, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals(2, rows[1].size)
        assertEquals("the last row holds the remainder", 1, rows[2].size)
        assertEquals("pl1", rows[0].first().playlistId)
        assertEquals("pl5", rows[2].first().playlistId)
    }

    @Test
    fun aSingleColumnKeepsOnePlaylistPerRow() {
        val rows = playlistGridRows(playlists(3), 1)
        assertEquals(3, rows.size)
        assertEquals(1, rows[0].size)
    }

    @Test
    fun anEmptyListHasNoRows() {
        assertEquals(emptyList<List<PlaylistItem>>(), playlistGridRows(emptyList(), 2))
    }
}
