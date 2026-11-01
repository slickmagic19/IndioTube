package com.indiotube.app

import com.indiotube.app.data.STATS_DAY_CAP
import com.indiotube.app.data.VideoItem
import com.indiotube.app.data.WatchStats
import com.indiotube.app.data.WatchStatsStore
import com.indiotube.app.data.averageActiveDayMs
import com.indiotube.app.data.dayInitial
import com.indiotube.app.data.dayKey
import com.indiotube.app.data.formatWatchTime
import com.indiotube.app.data.lastDayKeys
import com.indiotube.app.data.lastDays
import com.indiotube.app.data.plusSession
import com.indiotube.app.data.plusWatch
import com.indiotube.app.data.todayMs
import com.indiotube.app.data.topChannels
import com.indiotube.app.data.watchStreak
import com.indiotube.app.data.weekMs
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The statistics page is only as good as the ledger behind it, so the
// arithmetic (days, streaks, totals, saved time) is pinned here without a
// player, a file or a device clock in the way.
class WatchStatsTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun atNoon(day: String): Long =
        LocalDate.parse(day).atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun WatchStats.watch(day: String, ms: Long, channel: String? = "ch1"): WatchStats =
        plusWatch(ms = ms, day = day, channelId = channel, channelName = channel)

    @Test
    fun aTickLandsInTheTotalTheDayAndTheChannel() {
        val stats = WatchStats().watch("2026-10-30", 60_000).watch("2026-10-30", 30_000)

        assertEquals(90_000, stats.totalMs)
        assertEquals(mapOf("2026-10-30" to 90_000L), stats.byDay)
        assertEquals("ch1", stats.byChannel.getValue("ch1").name)
        assertEquals(90_000, stats.byChannel.getValue("ch1").ms)
        assertEquals(1, stats.activeDays)
        assertEquals("2026-10-30", stats.firstDay)
    }

    @Test
    fun aZeroOrNegativeTickIsIgnored() {
        val stats = WatchStats().watch("2026-10-30", 0).watch("2026-10-30", -500)

        assertEquals(0, stats.totalMs)
        assertTrue(stats.byDay.isEmpty())
        // A ledger that nothing was watched on has no first day to show.
        assertNull(stats.firstDay)
    }

    @Test
    fun aVideoWithoutAChannelStillCountsTowardsTheTotal() {
        val stats = WatchStats().plusWatch(
            ms = 45_000,
            day = "2026-10-30",
            channelId = null,
            channelName = null,
        )

        assertEquals(45_000, stats.totalMs)
        assertTrue(stats.byChannel.isEmpty())
    }

    @Test
    fun aChannelKeepsItsNameWhenALaterTickHasNone() {
        val stats = WatchStats().plusWatch(30_000, "2026-10-30", "ch1", "Indio Dev")
            .plusWatch(20_000, "2026-10-30", "ch1", null)

        assertEquals("Indio Dev", stats.byChannel.getValue("ch1").name)
        assertEquals(50_000, stats.byChannel.getValue("ch1").ms)
    }

    @Test
    fun savedTimeIsAccumulatedAlongsideTheWatchTime() {
        val stats = WatchStats()
            .plusWatch(60_000, "2026-10-30", "ch1", "Chan", saved = 30_000)
            .plusWatch(60_000, "2026-10-30", "ch1", "Chan", saved = -5)

        assertEquals(120_000, stats.totalMs)
        assertEquals("a negative saving is not subtracted", 30_000, stats.savedMs)
    }

    @Test
    fun sessionsKeepTheCountAndTheLongestStretch() {
        val stats = WatchStats()
            .plusSession(120_000)
            .plusSession(45_000)
            .plusSession(0)

        assertEquals(2, stats.sessions)
        assertEquals(120_000, stats.longestSessionMs)
    }

    @Test
    fun theStreakCountsConsecutiveDaysBackFromToday() {
        val stats = WatchStats()
            .watch("2026-10-30", 10_000)
            .watch("2026-10-31", 10_000)
            .watch("2026-11-01", 10_000)

        assertEquals(3, watchStreak(stats, atNoon("2026-11-01"), utc))
    }

    @Test
    fun aDayWithNoPlaybackYetKeepsYesterdaysStreakAlive() {
        val stats = WatchStats().watch("2026-10-30", 10_000).watch("2026-10-31", 10_000)

        assertEquals(2, watchStreak(stats, atNoon("2026-11-01"), utc))
    }

    @Test
    fun aMissedDayBreaksTheStreak() {
        val stats = WatchStats()
            .watch("2026-10-28", 10_000)
            .watch("2026-10-29", 10_000)
            .watch("2026-10-31", 10_000)

        assertEquals(1, watchStreak(stats, atNoon("2026-11-01"), utc))
    }

    @Test
    fun anEmptyLedgerHasNoStreak() {
        assertEquals(0, watchStreak(WatchStats(), atNoon("2026-11-01"), utc))
    }

    @Test
    fun theWeekIsSevenDaysOldestFirst() {
        val stats = WatchStats().watch("2026-10-26", 5_000).watch("2026-11-01", 7_000)

        val week = lastDays(stats, 7, atNoon("2026-11-01"), utc)

        assertEquals(7, week.size)
        assertEquals("2026-10-26", week.first().first)
        assertEquals("2026-11-01", week.last().first)
        assertEquals(5_000, week.first().second)
        assertEquals(0, week[1].second)
        assertEquals(7_000, week.last().second)
        assertEquals(12_000, weekMs(stats, atNoon("2026-11-01"), utc))
        assertEquals(7_000, todayMs(stats, atNoon("2026-11-01"), utc))
    }

    @Test
    fun dayKeysAreLocalCalendarDays() {
        val noon = atNoon("2026-11-01")
        val utcKey = dayKey(noon, utc)
        val farEastKey = dayKey(noon, ZoneId.of("Pacific/Kiritimati"))

        assertEquals("2026-11-01", utcKey)
        assertTrue("the same instant can be a different day elsewhere", farEastKey != utcKey)
        assertEquals(listOf("2026-10-31", "2026-11-01"), lastDayKeys(2, noon, utc))
        assertEquals(emptyList<String>(), lastDayKeys(0, noon, utc))
    }

    @Test
    fun theAverageUsesOnlyDaysWithPlayback() {
        val stats = WatchStats().watch("2026-10-30", 60_000).watch("2026-11-01", 180_000)

        assertEquals(2, stats.activeDays)
        assertEquals(120_000, averageActiveDayMs(stats))
        assertEquals(0, averageActiveDayMs(WatchStats()))
    }

    @Test
    fun topChannelsSortsByTimeAndCanBeLimited() {
        val stats = WatchStats()
            .watch("2026-10-30", 10_000, "small")
            .watch("2026-10-30", 300_000, "big")
            .watch("2026-10-30", 90_000, "middle")

        val top = topChannels(stats, 2)

        assertEquals(listOf("big", "middle"), top.map { it.name })
        assertEquals(3, topChannels(stats, 10).size)
    }

    @Test
    fun theDayListIsCappedSoTheFileStaysSmall() {
        var stats = WatchStats()
        var day = LocalDate.parse("2025-01-01")
        repeat(STATS_DAY_CAP + 5) {
            stats = stats.watch(day.toString(), 1_000)
            day = day.plusDays(1)
        }

        assertEquals(STATS_DAY_CAP, stats.byDay.size)
        // The cap keeps the newest days, which is what the chart reads.
        assertEquals(day.minusDays(1).toString(), stats.byDay.keys.max())
    }

    @Test
    fun watchTimeReadsLikeATimeNotMilliseconds() {
        assertEquals("0m", formatWatchTime(0))
        assertEquals("<1m", formatWatchTime(20_000))
        assertEquals("1m", formatWatchTime(60_000))
        assertEquals("1h 5m", formatWatchTime(3_900_000))
        assertEquals("3h", formatWatchTime(3 * 3_600_000))
    }

    @Test
    fun theChartLabelsAreWeekdayInitials() {
        assertEquals("M", dayInitial("2026-11-02"))
        assertEquals("S", dayInitial("2026-11-01"))
        assertEquals("?", dayInitial("not a day"))
    }

    // ---- the per-account store ------------------------------------------------

    private fun tempDir(): File = Files.createTempDirectory("indiotube-stats").toFile()

    private fun waitFor(file: File, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (file.exists() && file.length() > 0L) return true
            Thread.sleep(20)
        }
        return file.exists()
    }

    private val video = VideoItem("v1", "A video", channelId = "ch1", channelName = "Indio Dev")

    @Test
    fun theStoreWritesWhatItTrackedAndReadsItBack() {
        val dir = tempDir()
        val scope = CoroutineScope(Dispatchers.IO)
        val store = WatchStatsStore(dir, scope)
        store.switchAccount("a")
        store.addWatchTime(90_000, video, speed = 1f)
        store.endSession(90_000)

        assertTrue("the ledger is written to disk", waitFor(File(dir, "stats_a.json")))

        val reloaded = WatchStatsStore(dir, scope)
        reloaded.switchAccount("a")
        assertEquals(90_000, reloaded.stats.value.totalMs)
        assertEquals(1, reloaded.stats.value.sessions)
        assertEquals("Indio Dev", reloaded.stats.value.byChannel.getValue("ch1").name)
    }

    @Test
    fun eachAccountHasItsOwnLedger() {
        val dir = tempDir()
        val scope = CoroutineScope(Dispatchers.IO)
        val store = WatchStatsStore(dir, scope)
        store.switchAccount("a")
        store.addWatchTime(60_000, video, speed = 1f)
        assertEquals(60_000, store.stats.value.totalMs)

        store.switchAccount("b")

        assertEquals(0, store.stats.value.totalMs)
        assertEquals(0, store.stats.value.byDay.size)
    }

    @Test
    fun clearingWipesTheLedger() {
        val dir = tempDir()
        val store = WatchStatsStore(dir, CoroutineScope(Dispatchers.IO))
        store.switchAccount("a")
        store.addWatchTime(60_000, video, speed = 2f)
        store.endSession(60_000)

        store.clear()

        assertEquals(0, store.stats.value.totalMs)
        assertEquals(0, store.stats.value.savedMs)
        assertEquals(0, store.stats.value.sessions)
    }

    @Test
    fun aCorruptedFileStartsAFreshLedgerInsteadOfCrashing() {
        val dir = tempDir()
        File(dir, "stats_a.json").writeText("{not json")
        val store = WatchStatsStore(dir, CoroutineScope(Dispatchers.IO))

        store.switchAccount("a")

        assertEquals(0, store.stats.value.totalMs)
        assertNull(store.stats.value.firstDay)
    }
}
