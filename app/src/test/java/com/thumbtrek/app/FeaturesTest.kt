package com.thumbtrek.app

import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.share.BackupException
import com.thumbtrek.app.share.BackupSettings
import com.thumbtrek.app.share.backupOf
import com.thumbtrek.app.share.micrometresToPixels
import com.thumbtrek.app.share.parseBackup
import com.thumbtrek.app.share.toJson
import com.thumbtrek.app.share.toRows
import com.thumbtrek.app.social.Challenge
import com.thumbtrek.app.social.ChallengeKind
import com.thumbtrek.app.social.Group
import com.thumbtrek.app.social.GroupProgress
import com.thumbtrek.app.social.challengeProblem
import com.thumbtrek.app.social.groupStandings
import com.thumbtrek.app.social.groupWindow
import com.thumbtrek.app.social.newGroupCode
import com.thumbtrek.app.social.pixelsToMicrometres
import com.thumbtrek.app.social.windowUm
import com.thumbtrek.app.stats.LimitSync
import com.thumbtrek.app.stats.ReportSpan
import com.thumbtrek.app.stats.SharedLimit
import com.thumbtrek.app.stats.appLimitCaption
import com.thumbtrek.app.stats.appLimitStatuses
import com.thumbtrek.app.stats.appNudgesDue
import com.thumbtrek.app.stats.reconcileLimit
import com.thumbtrek.app.stats.report
import com.thumbtrek.app.stats.reportSummary
import com.thumbtrek.app.stats.reportTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Shared limit, per-app limits, reports, groups and backup: the rules without a device. */
class FeaturesTest {

    // --- shared limit ----------------------------------------------------------------

    @Test
    fun theNewestLimitEditWins() {
        val local = SharedLimit(limitUm = 500_000_000, setAt = 2_000)
        assertEquals(LimitSync.Push(local), reconcileLimit(local, null))
        assertEquals(LimitSync.Push(local), reconcileLimit(local, SharedLimit(300_000_000, 1_000)))
        val newer = SharedLimit(300_000_000, 3_000)
        assertEquals(LimitSync.Adopt(newer), reconcileLimit(local, newer))
        assertEquals(LimitSync.InSync, reconcileLimit(local, local.copy()))
    }

    @Test
    fun aTieKeepsTheServersCopySoTwoDevicesNeverPingPong() {
        val local = SharedLimit(500_000_000, 2_000)
        val remote = SharedLimit(0, 2_000)
        assertEquals(LimitSync.Adopt(remote), reconcileLimit(local, remote))
    }

    // --- per-app limits ---------------------------------------------------------------

    @Test
    fun appLimitsAreJudgedLikeTheDailyOne() {
        val statuses = appLimitStatuses(
            todayMeters = mapOf("ig" to 640.0, "yt" to 90.0),
            limits = mapOf("ig" to 500f, "yt" to 100f, "x" to 50f),
        )
        assertEquals(listOf("ig", "yt", "x"), statuses.map { it.key })
        assertTrue(statuses[0].over)
        assertEquals(2, statuses[0].level)
        assertEquals(1, statuses[1].level)
        assertEquals(0, statuses[2].level)
        assertEquals("640 m of 500 m", appLimitCaption(640.0, 500.0))
        assertEquals("640 m", appLimitCaption(640.0, null))
    }

    @Test
    fun eachAppNudgeLevelSpeaksOnce() {
        val statuses = appLimitStatuses(mapOf("ig" to 640.0, "yt" to 90.0), mapOf("ig" to 500f, "yt" to 100f))
        assertEquals(listOf("ig", "yt"), appNudgesDue(statuses, emptyMap()).map { it.key })
        // A warning already sent does not repeat, but a breach after it still gets its line.
        assertEquals(listOf("ig"), appNudgesDue(statuses, mapOf("ig" to 1, "yt" to 1)).map { it.key })
        assertTrue(appNudgesDue(statuses, mapOf("ig" to 2, "yt" to 1)).isEmpty())
    }

    @Test
    fun appLimitsSurviveTheirEncoding() {
        val limits = mapOf("com.instagram.android" to 250f, "com.reddit.frontpage" to 80.5f)
        assertEquals(limits, Prefs.decodeAppLimits(Prefs.encodeAppLimits(limits)))
        assertTrue(Prefs.decodeAppLimits(null).isEmpty())
        assertTrue(Prefs.decodeAppLimits("garbage\n=4\nx=y").isEmpty())
    }

    // --- reports ----------------------------------------------------------------------

    private val sunday = LocalDate.of(2026, 11, 15)
    private val history = mapOf(
        // Previous week
        LocalDate.of(2026, 11, 3) to mapOf("ig" to 400.0, "yt" to 100.0),
        LocalDate.of(2026, 11, 6) to mapOf("ig" to 500.0),
        // This week, Monday 9th to Sunday 15th
        LocalDate.of(2026, 11, 9) to mapOf("ig" to 300.0, "yt" to 200.0),
        LocalDate.of(2026, 11, 11) to mapOf("ig" to 100.0),
        LocalDate.of(2026, 11, 14) to mapOf("yt" to 900.0, "x" to 0.0),
    )

    @Test
    fun aWeeklyReportAddsUpItsOwnWeekOnly() {
        val r = report(ReportSpan.WEEK, history, today = sunday, limitMeters = 500.0)
        assertEquals(LocalDate.of(2026, 11, 9), r.from)
        assertEquals(sunday, r.to)
        assertFalse(r.running)
        assertEquals(1500.0, r.totalMeters, 0.0)
        assertEquals(1000.0, r.previousMeters, 0.0)
        assertEquals(50, r.deltaPercent)
        assertEquals(3, r.activeDays)
        assertEquals(7, r.elapsedDays)
        assertEquals(1500.0 / 7, r.dailyAverageMeters, 1e-9)
        assertEquals(2, r.cleanDays)
        assertEquals(3, r.judgedDays)
        assertEquals(LocalDate.of(2026, 11, 14) to 900.0, r.highest)
        assertEquals(LocalDate.of(2026, 11, 11) to 100.0, r.lowest)
        assertEquals(listOf("yt", "ig"), r.apps.map { it.key })
        assertEquals(100.0, r.apps[0].previousMeters, 0.0)
        assertEquals("9 to 15 Nov", reportTitle(r))
    }

    @Test
    fun aRunningWeekAveragesOverTheDaysSoFar() {
        val r = report(ReportSpan.WEEK, history, today = LocalDate.of(2026, 11, 11))
        assertTrue(r.running)
        assertEquals(3, r.elapsedDays)
        assertEquals(600.0, r.totalMeters, 0.0)
        assertNull(r.cleanDays)
        assertEquals(0, r.judgedDays)
    }

    @Test
    fun daysBeforeTheLimitBeganAreNotJudged() {
        val r = report(
            ReportSpan.WEEK, history, today = sunday,
            limitMeters = 500.0, limitSince = LocalDate.of(2026, 11, 12),
        )
        assertEquals(1, r.judgedDays)
        assertEquals(0, r.cleanDays)
    }

    @Test
    fun lastWeekAndTheMonthAreReachableByOffset() {
        val last = report(ReportSpan.WEEK, history, today = sunday, offset = 1)
        assertEquals(LocalDate.of(2026, 11, 2), last.from)
        assertEquals(LocalDate.of(2026, 11, 8), last.to)
        assertEquals(1000.0, last.totalMeters, 0.0)
        assertNull(last.deltaPercent)

        val month = report(ReportSpan.MONTH, history, today = sunday)
        assertEquals(LocalDate.of(2026, 11, 1), month.from)
        assertTrue(month.running)
        assertEquals(2500.0, month.totalMeters, 0.0)
        assertEquals("November 2026", reportTitle(month))
    }

    @Test
    fun theSummarySentenceReadsLikeAPersonWroteIt() {
        val monday = LocalDate.of(2026, 11, 16)
        val last = report(ReportSpan.WEEK, history, today = monday, offset = 1, limitMeters = 500.0)
        assertEquals(
            "1.5 km last week, 50% more than the week before. 2 of 3 days within your limit.",
            reportSummary(last),
        )
        val empty = report(ReportSpan.WEEK, emptyMap(), today = monday, offset = 1)
        assertEquals("Nothing scrolled last week.", reportSummary(empty))
    }

    // --- groups -----------------------------------------------------------------------

    private val group = Group("g1", "Flatmates", owner = "a", code = "ABCD1234", members = listOf("a", "b", "c"))

    @Test
    fun aGroupWithNoChallengeCountsTheCurrentWeek() {
        val window = groupWindow(group, today = LocalDate.of(2026, 11, 12))
        assertEquals("2026-W46", window.key)
        assertEquals(LocalDate.of(2026, 11, 9), window.from)
        assertEquals(LocalDate.of(2026, 11, 15), window.to)
        assertEquals(ChallengeKind.MOST, window.kind)
    }

    @Test
    fun aChallengeOwnsTheWindowBeforeDuringAndAfter() {
        val challenge = Challenge("Quiet week", ChallengeKind.LEAST, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16))
        val g = group.copy(challenge = challenge)
        assertEquals("Starts 10 Nov", groupWindow(g, LocalDate.of(2026, 11, 8)).status)
        assertEquals("4 days left", groupWindow(g, LocalDate.of(2026, 11, 12)).status)
        assertEquals("Last day", groupWindow(g, LocalDate.of(2026, 11, 16)).status)
        assertEquals("Ended 16 Nov", groupWindow(g, LocalDate.of(2026, 11, 20)).status)
        assertEquals("2026-11-10..2026-11-16", groupWindow(g, LocalDate.of(2026, 11, 12)).key)
    }

    @Test
    fun windowDistanceIgnoresDaysOutsideIt() {
        val window = groupWindow(group, today = LocalDate.of(2026, 11, 12))
        val days = mapOf("2026-11-08" to 5L, "2026-11-09" to 10L, "2026-11-15" to 20L, "2026-11-16" to 40L, "junk" to 99L)
        assertEquals(30L, windowUm(days, window))
    }

    @Test
    fun standingsSumSourcesAndIgnoreStaleWindows() {
        val window = groupWindow(group, today = LocalDate.of(2026, 11, 12))
        val progress = listOf(
            GroupProgress("a", "android", "Asha", "", window.key, 300),
            GroupProgress("a", "web", "Asha", "", window.key, 200),
            GroupProgress("b", "android", "Bo", "", "2026-W45", 9_000),
            GroupProgress("c", "android", "Cy", "", window.key, 800),
            GroupProgress("z", "android", "Stranger", "", window.key, 1),
        )
        val board = groupStandings(group, progress, window, myUid = "a")
        assertEquals(listOf("c", "a", "b"), board.map { it.uid })
        assertEquals(500L, board[1].um)
        assertTrue(board[1].isMe)
        // A stale document still names the member, but its number does not count.
        assertEquals("Bo", board[2].name)
        assertNull(board[2].um)
    }

    @Test
    fun inALeastChallengeAnEmptyRowNeverWins() {
        val g = group.copy(
            challenge = Challenge("Quiet", ChallengeKind.LEAST, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16)),
        )
        val window = groupWindow(g, LocalDate.of(2026, 11, 12))
        val progress = listOf(
            GroupProgress("a", "android", "Asha", "", window.key, 300),
            GroupProgress("c", "android", "Cy", "", window.key, 100),
        )
        assertEquals(listOf("c", "a", "b"), groupStandings(g, progress, window, "a").map { it.uid })
    }

    @Test
    fun challengesAreCheckedBeforeTheyAreWritten() {
        val start = LocalDate.of(2026, 11, 10)
        assertNull(challengeProblem("Quiet week", start, start))
        assertTrue(challengeProblem(" ", start, start) != null)
        assertTrue(challengeProblem("Quiet", start, start.minusDays(1)) != null)
        assertTrue(challengeProblem("Quiet", start, start.plusDays(92)) != null)
        assertNull(challengeProblem("Quiet", start, start.plusDays(91)))
    }

    @Test
    fun groupCodesLookLikeFriendCodes() {
        val code = newGroupCode(java.util.Random(7))
        assertEquals(8, code.length)
        assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
    }

    // --- backup -----------------------------------------------------------------------

    private val rows = listOf(
        DailyScroll("com.instagram.android", "2026-11-01", 42_000),
        DailyScroll("com.reddit.frontpage", "2026-11-01", 8_400),
        DailyScroll("com.instagram.android", "2026-11-02", 21_000),
        DailyScroll("com.x.empty", "2026-11-02", 0),
    )
    private val settings = BackupSettings(250f, mapOf("com.instagram.android" to 100f), mapOf("org.app" to "My app"))

    @Test
    fun aBackupRoundTripsThroughJson() {
        val backup = backupOf(rows, densityDpi = 420, settings = settings, exportedAt = "2026-11-15T10:00:00Z")
        val parsed = parseBackup(backup.toJson())
        assertEquals(backup, parsed)
        assertEquals(2, parsed.days.size)
        assertEquals(setOf("com.instagram.android"), parsed.days[1].apps.keys)
        assertEquals(pixelsToMicrometres(42_000, 420), parsed.days[0].apps["com.instagram.android"])
    }

    @Test
    fun restoringOnTheSameDensityGivesBackTheSamePixels() {
        val backup = backupOf(rows, 420, settings, "now")
        val restored = backup.toRows(420).associate { (it.packageName to it.date) to it.pixels }
        assertEquals(42_000L, restored["com.instagram.android" to "2026-11-01"])
        assertEquals(8_400L, restored["com.reddit.frontpage" to "2026-11-01"])
        assertEquals(3, restored.size)
        // A denser screen needs more pixels for the same distance.
        assertEquals(84_000L, micrometresToPixels(pixelsToMicrometres(42_000, 420), 840))
    }

    @Test
    fun filesThatAreNotOursAreRefusedWithAReason() {
        assertThrows(BackupException::class.java) { parseBackup("not json") }
        assertThrows(BackupException::class.java) { parseBackup("""{"format":"something-else"}""") }
        val web = assertThrows(BackupException::class.java) {
            parseBackup("""{"format":"thumbtrek-backup","version":1,"source":"web","days":[]}""")
        }
        assertTrue(web.message!!.contains("extension"))
        val future = assertThrows(BackupException::class.java) {
            parseBackup("""{"format":"thumbtrek-backup","version":99,"source":"android"}""")
        }
        assertTrue(future.message!!.contains("newer"))
    }

    @Test
    fun oneBadRowDoesNotCostTheRestOfTheBackup() {
        val text = """{"format":"thumbtrek-backup","version":1,"source":"android","days":[
            {"date":"not-a-date","apps":{"a":5}},
            {"date":"2026-11-01","apps":{"a":5,"b":-1,"c":"x"}},
            {"date":"2026-11-02"}
        ]}"""
        val backup = parseBackup(text)
        assertEquals(1, backup.days.size)
        assertEquals(mapOf("a" to 5L), backup.days[0].apps)
        assertNull(backup.settings.limitMeters)
    }
}
