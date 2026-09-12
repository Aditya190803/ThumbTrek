package com.thumbtrek.app

import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.social.BATCH_LIMIT
import com.thumbtrek.app.social.LeaderboardEntry
import com.thumbtrek.app.social.MAX_APP_KEYS
import com.thumbtrek.app.social.Period
import com.thumbtrek.app.social.SocialRepository
import com.thumbtrek.app.social.SOURCE_ANDROID
import com.thumbtrek.app.social.SourceTotals
import com.thumbtrek.app.social.combine
import com.thumbtrek.app.social.dayLedgers
import com.thumbtrek.app.social.dirtyDays
import com.thumbtrek.app.social.mergeOrRecreate
import com.thumbtrek.app.social.micrometresToMeters
import com.thumbtrek.app.social.parseSources
import com.thumbtrek.app.social.pixelsToMicrometres
import com.thumbtrek.app.social.rankUm
import com.thumbtrek.app.social.sourceLabel
import com.thumbtrek.app.social.syncedAgo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** The client half of docs/sync-protocol.md: the unit, the rollup, and what gets pushed. */
class SyncModelTest {

    // --- §1 the unit ---------------------------------------------------------------

    @Test
    fun `pixels to micrometres follows the contract formula`() {
        // 420 px at 420 dpi = exactly 1 inch = 25400 µm.
        assertEquals(25_400L, pixelsToMicrometres(420, 420))
        assertEquals(0L, pixelsToMicrometres(0, 420))
        // round(), not truncate: 1 px at 420 dpi is 60.476 µm.
        assertEquals(60L, pixelsToMicrometres(1, 420))
    }

    @Test
    fun `the same physical travel scores the same on any density`() {
        // Ten inches of thumb, on a dense phone and a coarse one.
        assertEquals(pixelsToMicrometres(5_600, 560), pixelsToMicrometres(3_200, 320))
        assertEquals(254_000L, pixelsToMicrometres(5_600, 560))
        // The bug this replaces: on raw pixels the dense phone scored 1.75x for the same trek.
        assertTrue(5_600L > 3_200L)
    }

    @Test
    fun `a broken density publishes zero rather than infinity`() {
        // Infinity through roundToLong() would land at the top of the global board.
        assertEquals(0L, pixelsToMicrometres(1_000, 0))
        assertEquals(0L, pixelsToMicrometres(1_000, -160))
    }

    @Test
    fun `micrometres convert back to metres`() {
        assertEquals(1.0, micrometresToMeters(1_000_000), 1e-9)
        assertEquals(0.0254, micrometresToMeters(25_400), 1e-9)
    }

    // --- §1 back-compat: the Um / Pixels ranking fallback ----------------------------

    @Test
    fun `rows with um ignore the legacy pixel count`() {
        assertEquals(500L, rankUm(um = 500, legacyPixels = 999_999, densityDpi = 420))
    }

    @Test
    fun `a legitimate zero um is not mistaken for a missing field`() {
        // The one that would bite: a user with no distance this week must read as 0,
        // not silently fall back to a stale pixel count.
        assertEquals(0L, rankUm(um = 0, legacyPixels = 999_999, densityDpi = 420))
    }

    @Test
    fun `rows with no um fall back to pixels at the local density`() {
        // An old client's row keeps roughly the position it always had instead of vanishing.
        assertEquals(25_400L, rankUm(um = null, legacyPixels = 420, densityDpi = 420))
        assertEquals(0L, rankUm(um = null, legacyPixels = 0, densityDpi = 420))
    }

    // --- §3 the rollup ---------------------------------------------------------------

    private val week = "2026-W37"
    private val month = "2026-09"
    private val lastWeek = "2026-W36"
    private val lastMonth = "2026-08"

    private fun totals(
        source: String,
        weekUm: Long,
        monthUm: Long,
        totalUm: Long,
        weekKey: String? = week,
        monthKey: String? = month,
    ) = SourceTotals(source, weekKey, weekUm, monthKey, monthUm, totalUm)

    @Test
    fun `combined totals are the sum of every source`() {
        val combined = combine(
            listOf(
                totals(SOURCE_ANDROID, 10, 100, 1_000),
                totals("web", 5, 50, 500),
            ),
            week,
            month,
        )
        assertEquals(15L, combined.weekUm)
        assertEquals(150L, combined.monthUm)
        assertEquals(1_500L, combined.totalUm)
    }

    @Test
    fun `an unknown source still counts toward the combined total`() {
        // A fourth client shipping later must not read as zero on a phone that predates it.
        val combined = combine(
            listOf(totals(SOURCE_ANDROID, 10, 0, 0), totals("watch", 7, 0, 0)),
            week,
            month,
        )
        assertEquals(17L, combined.weekUm)
    }

    @Test
    fun `a source that stopped syncing contributes nothing to this week`() {
        // The leaderboard that pays out for stopping: without the per-source key, the
        // extension's W36 figure would keep padding W37 forever.
        val stale = totals(
            "web", weekUm = 9_000, monthUm = 90_000, totalUm = 900_000,
            weekKey = lastWeek, monthKey = lastMonth,
        )
        val combined = combine(listOf(stale), week, month)
        assertEquals(0L, combined.weekUm)
        assertEquals(0L, combined.monthUm)
        // All-time has no key to go stale — the distance was really travelled.
        assertEquals(900_000L, combined.totalUm)
    }

    @Test
    fun `a mixed fresh and stale row sums only the fresh halves`() {
        val combined = combine(
            listOf(
                totals(SOURCE_ANDROID, 10, 100, 1_000),
                totals(
                    "web", weekUm = 5, monthUm = 50, totalUm = 500,
                    // Stale week, current month: a client last seen earlier this month.
                    weekKey = lastWeek, monthKey = month,
                ),
            ),
            week,
            month,
        )
        assertEquals(10L, combined.weekUm)
        assertEquals(150L, combined.monthUm)
        assertEquals(1_500L, combined.totalUm)
    }

    @Test
    fun `a source with no period key at all counts as stale`() {
        // Only an entry written before the rule existed. Reading it as current is the one
        // mistake that inflates a live board.
        val keyless = totals("web", 5, 50, 500, weekKey = null, monthKey = null)
        val combined = combine(listOf(keyless), week, month)
        assertEquals(0L, combined.weekUm)
        assertEquals(0L, combined.monthUm)
        assertEquals(500L, combined.totalUm)
    }

    @Test
    fun `no sources rolls up to zero, not to a crash`() {
        assertEquals(0L, combine(emptyList(), week, month).totalUm)
    }

    @Test
    fun `sources parse defensively and sort by slug`() {
        val parsed = parseSources(
            mapOf(
                "web" to mapOf("weekKey" to week, "weekUm" to 5L, "updatedAt" to Date(1_000L)),
                SOURCE_ANDROID to mapOf(
                    "weekKey" to week,
                    "weekUm" to 10L,
                    "monthKey" to month,
                    "monthUm" to 20L,
                    "totalUm" to 30L,
                    "updatedAt" to 2_000L,
                ),
                "junk" to "not a map",
            ),
        )
        assertEquals(listOf(SOURCE_ANDROID, "web"), parsed.map { it.source })
        assertEquals(10L, parsed[0].weekUm)
        assertEquals(week, parsed[0].weekKey)
        assertEquals(2_000L, parsed[0].updatedAt)
        // Absent fields read as zero / null, so a half-written entry degrades, not throws.
        assertEquals(0L, parsed[1].monthUm)
        assertNull(parsed[1].monthKey)
        assertEquals(1_000L, parsed[1].updatedAt)
        assertEquals(15L, combine(parsed, week, month).weekUm)
    }

    @Test
    fun `a missing sources map is not an error`() {
        assertEquals(emptyList<SourceTotals>(), parseSources(null))
    }

    @Test
    fun `source labels name the clients a user recognises`() {
        assertEquals("Phone", sourceLabel(SOURCE_ANDROID))
        assertEquals("Browser", sourceLabel("web"))
        assertEquals("Watch", sourceLabel("watch"))
    }

    // --- §3 writing a row that may have been deleted ---------------------------------

    /** Stands in for `validScore()`: the post-merge document must carry the identity. */
    private class FakeBoardRow(private var exists: Boolean) {
        val writes = mutableListOf<Map<String, Any>>()

        fun set(payload: Map<String, Any>) {
            writes += payload
            val merged = if (exists) IDENTITY + payload else payload
            if (!IDENTITY.keys.all { it in merged }) throw PermissionDenied()
            exists = true
        }

        companion object {
            val IDENTITY: Map<String, Any> = mapOf(
                "displayName" to "Trekker",
                "photoUrl" to "",
                "friendCode" to "ABCD1234",
                "weekKey" to "2026-W36",
                "monthKey" to "2026-09",
            )
        }
    }

    private class PermissionDenied : RuntimeException("PERMISSION_DENIED")

    private val combinedOnly: Map<String, Any> = mapOf("weekUm" to 10L, "totalUm" to 10L)

    @Test
    fun `a partial merge against an existing row writes only its own fields`() = runBlocking {
        val row = FakeBoardRow(exists = true)
        mergeOrRecreate(combinedOnly, FakeBoardRow.IDENTITY, { it is PermissionDenied }, row::set)
        assertEquals(1, row.writes.size)
        assertEquals(combinedOnly, row.writes.single())
    }

    @Test
    fun `a partial merge against a deleted row recovers instead of throwing`() = runBlocking {
        // The extension can opt out from its side, which deletes users/{uid} under us.
        val row = FakeBoardRow(exists = false)
        mergeOrRecreate(combinedOnly, FakeBoardRow.IDENTITY, { it is PermissionDenied }, row::set)
        assertEquals(2, row.writes.size)
        val retry = row.writes[1]
        assertTrue(FakeBoardRow.IDENTITY.keys.all { it in retry })
        assertEquals(10L, retry["weekUm"])
    }

    @Test
    fun `a failure that is not a rules rejection is not retried`() {
        val row = FakeBoardRow(exists = true)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                mergeOrRecreate(combinedOnly, FakeBoardRow.IDENTITY, { it is PermissionDenied }) {
                    row.writes += it
                    error("offline")
                }
            }
        }
        assertEquals(1, row.writes.size)
    }

    // --- §3 the day ledger ------------------------------------------------------------

    private fun row(date: String, pkg: String, pixels: Long) = DailyScroll(pkg, date, pixels)

    @Test
    fun `day ledgers group per day and per app in micrometres`() {
        val ledgers = dayLedgers(
            listOf(
                row("2026-09-05", "com.instagram.android", 420),
                row("2026-09-06", "com.instagram.android", 840),
                row("2026-09-06", "com.reddit.frontpage", 420),
            ),
            densityDpi = 420,
        )
        assertEquals(listOf("2026-09-05", "2026-09-06"), ledgers.map { it.date })
        assertEquals(25_400L, ledgers[0].um)
        assertEquals(76_200L, ledgers[1].um)
        assertEquals(
            mapOf("com.instagram.android" to 50_800L, "com.reddit.frontpage" to 25_400L),
            ledgers[1].apps,
        )
    }

    @Test
    fun `the doc id is the composite key the rules re-derive`() {
        val ledger = dayLedgers(listOf(row("2026-09-06", "a", 420)), 420).single()
        // firestore.rules validDay(): dayId == data.date + '__' + data.source
        assertEquals("2026-09-06__android", ledger.documentId(SOURCE_ANDROID))
    }

    @Test
    fun `apps are capped at the rules limit without shrinking the day`() {
        val rows = (1..MAX_APP_KEYS + 6).map { row("2026-09-06", "app$it", it * 420L) }
        val ledger = dayLedgers(rows, densityDpi = 420).single()
        assertEquals(MAX_APP_KEYS, ledger.apps.size)
        // The busiest survive the truncation...
        assertTrue("app70" in ledger.apps)
        assertTrue("app1" !in ledger.apps)
        // ...but `um` is still the whole day, not just the 64 keys that fit.
        assertEquals(rows.sumOf { pixelsToMicrometres(it.pixels, 420) }, ledger.um)
        assertTrue(ledger.um > ledger.apps.values.sum())
    }

    @Test
    fun `apps that round to zero micrometres are dropped`() {
        val ledger = dayLedgers(
            listOf(row("2026-09-06", "loud", 420), row("2026-09-06", "silent", 0)),
            densityDpi = 420,
        ).single()
        assertEquals(setOf("loud"), ledger.apps.keys)
    }

    // --- dirty-day selection -----------------------------------------------------------

    private val threeDays = dayLedgers(
        listOf(
            row("2026-09-04", "a", 420),
            row("2026-09-05", "a", 840),
            row("2026-09-06", "a", 1_260),
        ),
        densityDpi = 420,
    )

    @Test
    fun `the first sync pushes the whole history`() {
        assertEquals(
            listOf("2026-09-04", "2026-09-05", "2026-09-06"),
            dirtyDays(threeDays, emptyMap()).map { it.date },
        )
    }

    @Test
    fun `a routine sync pushes only the days that moved`() {
        val pushed = threeDays.associate { it.date to it.um }
        assertEquals(emptyList<String>(), dirtyDays(threeDays, pushed).map { it.date })

        val movedToday = pushed + ("2026-09-06" to 1L)
        assertEquals(listOf("2026-09-06"), dirtyDays(threeDays, movedToday).map { it.date })

        // A day the server has never heard of is dirty even when older days are clean.
        val missingMiddle = pushed - "2026-09-05"
        assertEquals(listOf("2026-09-05"), dirtyDays(threeDays, missingMiddle).map { it.date })
    }

    @Test
    fun `a two year backfill batches instead of firing a request per day`() {
        val rows = (0 until 730).map { row("2026-%02d-%02d".format(it % 12 + 1, it % 28 + 1), "a", 420L) }
        val pending = dirtyDays(dayLedgers(rows, 420), emptyMap())
        val batches = pending.chunked(BATCH_LIMIT)
        assertTrue(batches.all { it.size <= BATCH_LIMIT })
        assertTrue(batches.size < 5)
    }

    // --- what has already been pushed, across process restarts ---------------------------

    @Test
    fun `the pushed-day marker survives an encode round trip`() {
        val days = mapOf("2026-09-05" to 25_400L, "2026-09-06" to 76_200L)
        assertEquals(days, Prefs.decodeDayUm(Prefs.encodeDayUm(days)))
        assertEquals(emptyMap<String, Long>(), Prefs.decodeDayUm(null))
        // Junk from an older or corrupt write costs a re-upload, never a crash.
        assertEquals(mapOf("2026-09-06" to 1L), Prefs.decodeDayUm("garbage\n2026-09-06=1\n=5\nx="))
    }

    // --- sync freshness ------------------------------------------------------------------

    @Test
    fun `last synced reads as an age, and as nothing before the first sync`() {        val now = 1_000_000_000L
        assertNull(syncedAgo(null, now))
        assertNull(syncedAgo(0L, now))
        assertEquals("just now", syncedAgo(now - 30_000, now))
        assertEquals("12 min ago", syncedAgo(now - 12 * 60_000, now))
        assertEquals("3 h ago", syncedAgo(now - 3 * 3_600_000, now))
        assertEquals("5 days ago", syncedAgo(now - 5 * 86_400_000L, now))
        // Another client's clock running ahead reads as fresh, never as a negative age.
        assertNotNull(syncedAgo(now + 5_000, now))
    }

    // --- friend edge identity ----------------------------------------------------------

    @Test
    fun `request edges carry the writer's real identity within rule limits`() {
        val fields = SocialRepository.requestIdentityFields("Ada Lovelace", "https://pic/x.png")
        assertEquals("Ada Lovelace", fields["name"])
        assertEquals("https://pic/x.png", fields["photo"])
        // Clamped to firestore.rules, blank names fall back — a rule rejection would
        // silently drop the whole request batch.
        val long = SocialRepository.requestIdentityFields("n".repeat(100), "p".repeat(600))
        assertEquals(64, long["name"]!!.length)
        assertEquals(512, long["photo"]!!.length)
        assertEquals("Trekker", SocialRepository.requestIdentityFields("  ", "")["name"])
    }

    @Test
    fun `accept stamps only the other side, preserving the requester's handwriting`() {
        val (own, their) = SocialRepository.acceptEdgePatches("Bob", "https://pic/b.png")
        assertEquals(mapOf("status" to "accepted"), own)
        assertEquals("accepted", their["status"])
        assertEquals("Bob", their["name"])
        assertEquals("https://pic/b.png", their["photo"])
    }

    @Test
    fun `friends board prefers edge identity, global rows untouched`() {
        val row = LeaderboardEntry("u1", "Silent Scroller #0001", "", 10L, 10L, 10L)
        val real = SocialRepository.withEdgeIdentity(row, "Bob", "https://pic/b.png")
        assertEquals("Bob", real.displayName)
        assertEquals("https://pic/b.png", real.photoUrl)
        assertEquals(10L, real.score(Period.WEEK)) // scores never come from the edge
        // Old edges with no identity read through unchanged.
        assertEquals(row, SocialRepository.withEdgeIdentity(row, null, null))
        assertEquals(row, SocialRepository.withEdgeIdentity(row, "  ", "x"))
    }
}
