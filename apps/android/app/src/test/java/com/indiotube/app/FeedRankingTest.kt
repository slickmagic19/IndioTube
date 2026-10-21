package com.indiotube.app

import com.indiotube.app.data.VideoItem
import com.indiotube.app.ui.FeedSignals
import com.indiotube.app.ui.rankAndSample
import com.indiotube.app.ui.tokenizeQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Offline unit tests for the local recommender: no network, deterministic
// inputs, verifying the ranking contract the home feed relies on.
class FeedRankingTest {

    private fun video(
        id: String,
        channel: String,
        title: String = "Video $id",
        published: String = "1 day ago",
    ) = VideoItem(
        videoId = id,
        title = title,
        channelId = channel,
        channelName = channel,
        publishedText = published,
    )

    private val emptySignals = FeedSignals(
        affinity = emptyMap(),
        subIds = emptySet(),
        likedChannelIds = emptySet(),
        watchedIds = emptySet(),
        seedIds = emptySet(),
        searchTerms = emptySet(),
    )

    @Test
    fun tokenizeDropsStopWordsAndShortTokens() {
        val tokens = tokenizeQuery("How to build the BEST lofi beats in 2026")
        assertTrue(tokens.contains("build"))
        assertTrue(tokens.contains("lofi"))
        assertTrue(tokens.contains("beats"))
        assertTrue(tokens.contains("2026"))
        assertTrue("stop words removed", "how" !in tokens && "the" !in tokens && "best" !in tokens)
        assertTrue("short tokens removed", "to" !in tokens && "in" !in tokens)
    }

    @Test
    fun everyRefreshProducesADifferentOrder() {
        val pool = (1..40).map { video("v$it", "channel${it % 8}", published = "${it} hours ago") }
        val a = rankAndSample(pool, emptySignals, rotation = 1)
        val b = rankAndSample(pool, emptySignals, rotation = 2)
        assertEquals("same candidates available", 40, pool.size)
        assertTrue("feeds should not be identical", a.map { it.videoId } != b.map { it.videoId })
        assertTrue("first refresh fills the page", a.size >= 20)
        assertTrue("second refresh fills the page", b.size >= 20)
    }

    @Test
    fun membersOnlyVideosAreNeverRecommended() {
        val pool = (1..20).map { video("v$it", "channel${it % 5}") } +
            (21..28).map { video("m$it", "channel${it % 5}").copy(membersOnly = true) }
        val out = rankAndSample(pool, emptySignals, rotation = 4)
        assertTrue("page filled from playable candidates", out.isNotEmpty())
        assertTrue("no members-only recommendation", out.none { it.membersOnly })
    }

    @Test
    fun sameRotationIsStable() {
        val pool = (1..30).map { video("v$it", "channel${it % 6}") }
        val a = rankAndSample(pool, emptySignals, rotation = 7)
        val b = rankAndSample(pool, emptySignals, rotation = 7)
        assertEquals(a.map { it.videoId }, b.map { it.videoId })
    }

    @Test
    fun subscriptionsAreFavoured() {
        // More candidates than slots, and a loose diversity cap, so the test
        // measures the ranking boost rather than the per-channel cap.
        val pool = (1..200).map { video("v$it", "channel${it % 20}") }
        val signals = emptySignals.copy(subIds = setOf("channel0", "channel1"))
        val runs = (1..30).map { rankAndSample(pool, signals, rotation = it, limit = 60, maxPerChannel = 10) }
        val subShare = runs.sumOf { feed -> feed.count { it.channelId in signals.subIds } }.toDouble() /
            runs.sumOf { it.size }
        val baseline = 2.0 / 20.0
        assertTrue("subscribed channels should be over-represented, was $subShare (baseline $baseline)", subShare > baseline * 1.5)
    }

    @Test
    fun watchedVideosAreDemoted() {
        val pool = (1..200).map { video("v$it", "channel${it % 20}") }
        val signals = emptySignals.copy(watchedIds = (1..100).map { "v$it" }.toSet())
        val feed = rankAndSample(pool, signals, rotation = 3, limit = 60)
        val watchedShare = feed.count { it.videoId in signals.watchedIds }.toDouble() / feed.size
        assertTrue("watched videos should be rare, was $watchedShare", watchedShare < 0.25)
    }

    @Test
    fun completedVideosSinkBelowMerelyWatchedOnes() {
        val pool = (1..200).map { video("v$it", "channel${it % 20}") }
        val watched = (1..100).map { "v$it" }.toSet()
        val completed = (1..50).map { "v$it" }.toSet()
        val signals = emptySignals.copy(watchedIds = watched, completedIds = completed)
        val runs = (1..20).map { rankAndSample(pool, signals, rotation = it, limit = 60) }
        val total = runs.sumOf { it.size }.toDouble()
        val completedShare = runs.sumOf { f -> f.count { it.videoId in completed } } / total
        val watchedOnlyShare = runs.sumOf { f ->
            f.count { it.videoId in watched && it.videoId !in completed }
        } / total
        assertTrue(
            "finished videos must be rarer than unfinished watched ones, " +
                "completed=$completedShare watchedOnly=$watchedOnlyShare",
            completedShare < watchedOnlyShare,
        )
    }

    @Test
    fun searchTermsBoostMatchingTitles() {
        val pool = (1..200).map { video("v$it", "channel${it % 20}", title = "Random clip $it") } +
            (1..20).map { video("s$it", "channelS$it", title = "Lofi beats mix $it") }
        val signals = emptySignals.copy(searchTerms = setOf("lofi", "beats"))
        val feed = rankAndSample(pool, signals, rotation = 2, limit = 60)
        val matching = feed.count { it.title.contains("Lofi", true) }
        assertTrue("searched topics should surface, was $matching of ${feed.size}", matching >= 6)
    }

    @Test
    fun channelDiversityIsCappedAtTheTop() {
        val pool = (1..40).map { video("v$it", "oneChannel") }
        val feed = rankAndSample(pool, emptySignals, rotation = 5)
        assertTrue("the page still fills from the overflow", feed.size >= 20)
        val diverse = (1..40).map { video("v$it", "channel${it % 8}") }
        val mixed = rankAndSample(diverse, emptySignals, rotation = 5, limit = 20)
        val topChannels = mixed.take(16).groupingBy { it.channelId }.eachCount()
        assertTrue("top of the feed stays diverse", topChannels.values.all { it <= 3 })
    }

    @Test
    fun emptyPoolIsSafe() {
        assertTrue(rankAndSample(emptyList(), emptySignals, rotation = 1).isEmpty())
    }

    @Test
    fun notInterestedVideosNeverComeBack() {
        val pool = (1..50).map { video("v$it", "channel${it % 10}") }
        val hidden = setOf("v1", "v2", "v3")
        val signals = emptySignals.copy(hiddenIds = hidden)
        val feed = rankAndSample(pool, signals, rotation = 4, limit = 60, maxPerChannel = 10)
        assertTrue(feed.isNotEmpty())
        assertTrue("hidden videos must be excluded", feed.none { it.videoId in hidden })
    }

    @Test
    fun blockedChannelsAreExcluded() {
        val pool = (1..50).map { video("v$it", "channel${it % 10}") }
        val signals = emptySignals.copy(blockedChannelIds = setOf("channel0"))
        val feed = rankAndSample(pool, signals, rotation = 4, limit = 60, maxPerChannel = 10)
        assertTrue(feed.isNotEmpty())
        assertTrue("blocked channels must be excluded", feed.none { it.channelId == "channel0" })
    }

    @Test
    fun interestedChannelsAreFavoured() {
        val pool = (1..200).map { video("v$it", "channel${it % 20}") }
        val signals = emptySignals.copy(interestedChannelIds = setOf("channel2", "channel3"))
        val runs = (1..30).map { rankAndSample(pool, signals, rotation = it, limit = 60, maxPerChannel = 10) }
        val share = runs.sumOf { feed -> feed.count { it.channelId in signals.interestedChannelIds } }.toDouble() /
            runs.sumOf { it.size }
        val baseline = 2.0 / 20.0
        assertTrue(
            "interested channels should be over-represented, was $share (baseline $baseline)",
            share > baseline * 1.5,
        )
    }
}
