package com.thumbtrek.app.social

import com.thumbtrek.app.data.DailyScroll
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The wire model of `docs/sync-protocol.md` — the contract the Android app, the browser
 * extension and the web dashboard all implement.
 *
 * Everything in this file is deliberately plain Kotlin: no Firestore types, no Android
 * types. It is the half of syncing that has arithmetic worth testing, and
 * [SocialRepository] is the half that talks to the network. The shapes are spelled out
 * rather than inferred because two other clients are being written against the same
 * document layout, and a quiet deviation here reads as missing data over there.
 */

/** This client's slug (contract §2). Never contains a `.`: it sits inside field paths. */
const val SOURCE_ANDROID = "android"

/** The browser extension's slug. This app never writes it — it only reads and labels it. */
const val SOURCE_WEB = "web"

/** Firestore caps a `WriteBatch` at 500 operations. */
const val BATCH_LIMIT = 500

/** Contract §3: `apps` is capped at 64 keys, and `firestore.rules` enforces it. */
const val MAX_APP_KEYS = 64

private const val MICROMETRES_PER_INCH = 25_400.0
private const val MICROMETRES_PER_METRE = 1_000_000.0

// ---------------------------------------------------------------------------------------
// The unit
// ---------------------------------------------------------------------------------------

/**
 * Contract §1: `round(pixels / densityDpi * 25400)`.
 *
 * Micrometres, integer, because pixels are not comparable between clients — a 560dpi
 * phone logs 1.75× the pixels of a 320dpi phone for the same physical thumb travel, and a
 * browser's CSS pixel is a third thing again. Integers because Firestore sorts them
 * exactly; a metre is still 1e6 µm, nowhere near a precision limit.
 *
 * The `densityDpi <= 0` guard is not paranoia about real devices — it stops a corrupt or
 * stubbed metric turning into `Infinity`, which [roundToLong] would happily publish as
 * `Long.MAX_VALUE` at the top of the global board.
 */
fun pixelsToMicrometres(pixels: Long, densityDpi: Int): Long =
    if (densityDpi <= 0) 0L
    else (pixels.toDouble() / densityDpi * MICROMETRES_PER_INCH).roundToLong()

fun micrometresToMeters(um: Long): Double = um / MICROMETRES_PER_METRE

/**
 * The figure a board row ranks on: `*Um` when the row carries one, otherwise the legacy
 * `*Pixels` count read through *this* device's density (contract §1, back-compat).
 *
 * There is no honest conversion for a legacy row — it was measured on a phone whose
 * density the board never recorded. Reading it at the local density is exactly what the
 * pre-µm board did when it rendered every row through `pixelsToMeters(…, dpi)`, so an old
 * client keeps roughly the position it has always had instead of sinking to zero and
 * vanishing mid-migration. A row that has `*Um` never consults `*Pixels`, including when
 * its `*Um` is a legitimate zero.
 */
fun rankUm(um: Long?, legacyPixels: Long, densityDpi: Int): Long =
    um ?: pixelsToMicrometres(legacyPixels, densityDpi)

// ---------------------------------------------------------------------------------------
// The board row's per-source split
// ---------------------------------------------------------------------------------------

/**
 * One client's contribution to `users/{uid}.sources` (contract §3).
 *
 * Each source carries its *own* [weekKey] / [monthKey]. The row-level keys cannot do this
 * job once a row has several writers: the phone syncing in W37 stamps the row W37 while
 * `sources.web.weekUm` may still hold W36's distance. Null means the writer never stamped
 * one — see [combine].
 */
data class SourceTotals(
    val source: String,
    val weekKey: String? = null,
    val weekUm: Long = 0L,
    val monthKey: String? = null,
    val monthUm: Long = 0L,
    val totalUm: Long = 0L,
    /** Epoch millis, or null when the writer never stamped one. */
    val updatedAt: Long? = null,
)

/** The three combined figures the board actually ranks on. */
data class CombinedTotals(
    val weekUm: Long = 0L,
    val monthUm: Long = 0L,
    val totalUm: Long = 0L,
)

/**
 * Contract §3 step 2: the combined figure is the sum over *every* source, not just the
 * ones this build knows about. An unrecognised slug still counts, so a fourth client
 * shipping later does not read as zero on a phone that predates it.
 *
 * A source contributes its `weekUm` only when its own key matches [weekKey], and zero
 * otherwise — the same rule the board row already applies at row level, pushed down one
 * layer. Without it a client that stopped syncing last week keeps padding this week's
 * combined figure: a leaderboard that pays out for stopping. `totalUm` is all-time and
 * has no key to go stale.
 *
 * A source with no key at all counts as stale rather than as grandfathered-fresh. It can
 * only be an entry written before this rule existed, and reading it as current is the one
 * mistake that inflates a live board; reading it as stale merely understates a figure that
 * its own client corrects on its next sync.
 */
fun combine(
    sources: Collection<SourceTotals>,
    weekKey: String,
    monthKey: String,
): CombinedTotals = CombinedTotals(
    weekUm = sources.sumOf { it.weekUmIn(weekKey) },
    monthUm = sources.sumOf { it.monthUmIn(monthKey) },
    totalUm = sources.sumOf { it.totalUm },
)

/** This source's contribution to [weekKey]: zero once its own key has gone stale. */
fun SourceTotals.weekUmIn(weekKey: String): Long = if (this.weekKey == weekKey) weekUm else 0L

/** This source's contribution to [monthKey]: zero once its own key has gone stale. */
fun SourceTotals.monthUmIn(monthKey: String): Long =
    if (this.monthKey == monthKey) monthUm else 0L

/**
 * Reads the `sources` map back off a board row. Field-by-field and untyped for the same
 * reason the leaderboard parser is: a half-written entry from another client must degrade
 * to zero, never crash the screen.
 *
 * Sorted by slug so the UI's ordering is stable between syncs rather than following
 * whatever order Firestore handed the map back in.
 */
fun parseSources(raw: Map<*, *>?): List<SourceTotals> {
    if (raw == null) return emptyList()
    return raw.entries
        .mapNotNull { (key, value) ->
            val source = key as? String ?: return@mapNotNull null
            val fields = value as? Map<*, *> ?: return@mapNotNull null
            SourceTotals(
                source = source,
                weekKey = fields["weekKey"] as? String,
                weekUm = asLong(fields["weekUm"]),
                monthKey = fields["monthKey"] as? String,
                monthUm = asLong(fields["monthUm"]),
                totalUm = asLong(fields["totalUm"]),
                updatedAt = asEpochMillis(fields["updatedAt"]),
            )
        }
        .sortedBy { it.source }
}

private fun asLong(value: Any?): Long = (value as? Number)?.toLong() ?: 0L

/** Firestore `Timestamp`s are flattened to [Date] by the repository before they get here. */
private fun asEpochMillis(value: Any?): Long? = when (value) {
    is Date -> value.time
    is Number -> value.toLong()
    else -> null
}

/** What a source calls itself on screen. Unknown slugs show as themselves, not "Other". */
fun sourceLabel(source: String): String = when (source) {
    SOURCE_ANDROID -> "Phone"
    SOURCE_WEB -> "Browser"
    else -> source.replaceFirstChar { it.uppercase(Locale.US) }
}

// ---------------------------------------------------------------------------------------
// Writing the board row
// ---------------------------------------------------------------------------------------

/**
 * Contract §3, "the first write of a row must be complete": `validScore()` asserts
 * `displayName`, `photoUrl`, `friendCode`, `weekKey` and `monthKey` are strings, and a
 * merging write is evaluated against the *post-merge* document. A merge carrying only
 * `sources.android` — or only the combined totals — therefore succeeds against an existing
 * row and is rejected against a missing one.
 *
 * The row can go missing under us at any moment now that the browser extension can opt out
 * too, and an opt-out there deletes `users/{uid}` wholesale. So a rejected partial merge is
 * retried once with [identity] folded in, which recreates the row rather than surfacing
 * "permission denied" to someone who has done nothing wrong.
 *
 * [rejected] is injected rather than matching on `FirebaseFirestoreException` here so this
 * file stays free of Firestore types and this decision stays unit-testable. Firestore gives
 * no detail about *which* rules clause failed, so every rejection of a partial merge is read
 * as "the row is gone". If it genuinely was a permissions problem the retry fails the same
 * way and the original error reaches the user as before.
 */
suspend fun mergeOrRecreate(
    partial: Map<String, Any>,
    identity: Map<String, Any>,
    rejected: (Throwable) -> Boolean,
    write: suspend (Map<String, Any>) -> Unit,
) {
    try {
        write(partial)
    } catch (e: Exception) {
        if (!rejected(e)) throw e
        write(identity + partial)
    }
}

// ---------------------------------------------------------------------------------------
// The day ledger
// ---------------------------------------------------------------------------------------

/** One `users/{uid}/days/{date}__{source}` document, in µm (contract §3). */
data class DayLedger(
    val date: String,
    val um: Long,
    val apps: Map<String, Long>,
) {
    /**
     * The doc id embeds the source so each document has exactly one writer. `validDay()`
     * in `firestore.rules` re-derives this and rejects a mismatch, which is what stops a
     * client claiming another source's day by choosing a misleading id.
     */
    fun documentId(source: String): String = "${date}__$source"
}

/**
 * Groups the local history into one ledger per day, in µm.
 *
 * `um` is the sum of the day's per-app µm rather than a single conversion of the day's
 * pixels, so `apps` adds up to `um` exactly whenever the breakdown is not truncated. The
 * two differ by well under a micrometre, which is four orders of magnitude below anything
 * a human reads off a chart.
 *
 * Apps that round to zero µm are dropped, and only the 64 largest survive — that is the
 * rules' cap. `um` stays the *whole* day either way: a truncated breakdown must not
 * quietly shrink the day's distance on the dashboard's charts.
 */
fun dayLedgers(rows: List<DailyScroll>, densityDpi: Int): List<DayLedger> =
    rows.groupBy { it.date }
        .map { (date, dayRows) ->
            val byApp = dayRows
                .groupBy { it.packageName }
                .mapValues { (_, appRows) ->
                    pixelsToMicrometres(appRows.sumOf { it.pixels }, densityDpi)
                }
                .filterValues { it > 0L }
            DayLedger(
                date = date,
                um = byApp.values.sum(),
                apps = byApp.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }
                        .thenBy { it.key })
                    .take(MAX_APP_KEYS)
                    .associate { it.key to it.value },
            )
        }
        .sortedBy { it.date }

/**
 * The days whose local total no longer matches what the server was last told.
 *
 * [pushed] is empty on the first sync after opting in, so this returns the whole history —
 * that is the backfill. Afterwards it is normally today plus whatever else moved, which is
 * what keeps a routine sync from re-uploading two years of documents every time.
 *
 * The day's µm total is a sufficient fingerprint: Room rows only ever accumulate, so no
 * per-app figure inside a day can change without changing the day's total too.
 */
fun dirtyDays(ledgers: List<DayLedger>, pushed: Map<String, Long>): List<DayLedger> =
    ledgers.filter { pushed[it.date] != it.um }

// ---------------------------------------------------------------------------------------
// Sync freshness
// ---------------------------------------------------------------------------------------

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/**
 * "just now" / "12 min ago" / "3 h ago" / "5 days ago". Null when [then] is null or zero,
 * so the UI can say "never" in its own words rather than rendering a fake timestamp.
 * Clock skew between two clients can put [then] slightly ahead of [now]; that reads as
 * "just now" rather than a negative age.
 */
fun syncedAgo(then: Long?, now: Long = System.currentTimeMillis()): String? {
    if (then == null || then <= 0L) return null
    val age = now - then
    return when {
        age < 2 * MINUTE -> "just now"
        age < HOUR -> "${age / MINUTE} min ago"
        age < DAY -> "${age / HOUR} h ago"
        age < 2 * DAY -> "yesterday"
        else -> "${age / DAY} days ago"
    }
}
