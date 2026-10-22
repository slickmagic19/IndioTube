package com.indiotube.app

import com.indiotube.app.data.ChannelItem
import com.indiotube.app.data.InnerTube
import com.indiotube.app.data.SearchDuration
import com.indiotube.app.data.SearchEntry
import com.indiotube.app.data.SearchFilter
import com.indiotube.app.data.SearchSort
import com.indiotube.app.data.SearchUploadDate
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.buildSearchParams
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The expected strings were read off the live WEB client (youtube.com/results
// with each filter applied) and then round-tripped through a real anonymous
// search request, so they pin YouTube's actual protobuf encoding.
class SearchParamsTest {

    private val innerTube = InnerTube(OkHttpClient())

    @Test
    fun typeFiltersMatchYouTubeEncoding() {
        assertEquals("EgIQAQ==", buildSearchParams(type = SearchFilter.VIDEOS))
        assertEquals("EgIQAg==", buildSearchParams(type = SearchFilter.CHANNELS))
        assertEquals("EgIQAw==", buildSearchParams(type = SearchFilter.PLAYLISTS))
    }

    @Test
    fun sortEncodingsMatchYouTube() {
        assertEquals("CAE=", buildSearchParams(sort = SearchSort.RATING))
        assertEquals("CAI=", buildSearchParams(sort = SearchSort.UPLOAD_DATE))
        assertEquals("CAM=", buildSearchParams(sort = SearchSort.VIEW_COUNT))
    }

    @Test
    fun uploadDateEncodingsMatchYouTube() {
        assertEquals("EgQIAhAB", buildSearchParams(type = SearchFilter.VIDEOS, uploadDate = SearchUploadDate.TODAY))
        assertEquals("EgQIAxAC", buildSearchParams(type = SearchFilter.CHANNELS, uploadDate = SearchUploadDate.THIS_WEEK))
    }

    // YouTube's wire values are not ordered by length: short=1, long=2,
    // medium=3. Getting this wrong swaps long and medium results.
    @Test
    fun durationEncodingsMatchYouTubeOddOrdering() {
        assertEquals("EgIYAQ==", buildSearchParams(duration = SearchDuration.SHORT))
        assertEquals("EgIYAg==", buildSearchParams(duration = SearchDuration.LONG))
        assertEquals("EgIYAw==", buildSearchParams(duration = SearchDuration.MEDIUM))
    }

    @Test
    fun combinedFiltersChainCorrectly() {
        assertEquals("CAMSAhAB", buildSearchParams(type = SearchFilter.VIDEOS, sort = SearchSort.VIEW_COUNT))
    }

    @Test
    fun noFiltersMeansNoParams() {
        assertNull(buildSearchParams())
    }

    @Test
    fun fallbackHonorsDurationFilters() {
        val channel = ChannelItem("UCtest", "Test Channel")
        val videos = listOf(
            VideoItem("short", durationSec = 90),
            VideoItem("medium", durationSec = 600),
            VideoItem("long", durationSec = 1800),
        )

        fun ids(duration: Int): List<String> = innerTube
            .fallbackSearchEntries(channel, videos, emptyList(), SearchFilter.VIDEOS, duration)
            .filterIsInstance<SearchEntry.Video>()
            .map { it.video.videoId }

        assertEquals(listOf("short"), ids(SearchDuration.SHORT))
        assertEquals(listOf("medium"), ids(SearchDuration.MEDIUM))
        assertEquals(listOf("long"), ids(SearchDuration.LONG))
    }

    @Test
    fun fallbackWithoutMatchesStillShowsTheChannel() {
        val channel = ChannelItem("UCtest", "Test Channel")
        val entries = innerTube.fallbackSearchEntries(
            channel,
            listOf(VideoItem("long", durationSec = 1800)),
            emptyList(),
            SearchFilter.VIDEOS,
            SearchDuration.SHORT,
        )
        assertEquals(1, entries.size)
        assertEquals(channel.channelId, (entries.first() as SearchEntry.Channel).channel.channelId)
    }
}
