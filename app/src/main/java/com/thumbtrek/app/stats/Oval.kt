package com.thumbtrek.app.stats

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong

// The lap oval, as numbers (DESIGN.md, "The lap oval"). Pure Kotlin with no Android or
// Compose imports, so the rules every client has to agree on (the scale, the 80% and
// over-limit states, the "Other" lane) are unit-tested. ui/LapOval.kt does the drawing.

/** One lap of a running track. Laps are always counted against this, limit or no limit. */
const val LAP_METERS = 400.0

/** Lanes 2 to 5. Lane 1 is the total. */
const val MAX_APP_LANES = 4

/** From this share of the limit the total lane turns to the warning colour. */
const val OVAL_WARN_SHARE = 0.8

enum class OvalState { OPEN, UNDER, NEAR, OVER }

/** One lane: [lane] is 1 for the total, 2 to 5 for apps. [fraction] is its share of a lap. */
data class OvalLane(
    val lane: Int,
    val name: String,
    val meters: Double,
    val fraction: Float,
    val isOther: Boolean = false,
)

data class OvalModel(
    /** What one full lap of any lane stands for, in metres. */
    val scaleMeters: Double,
    val state: OvalState,
    /** The state in words, or null when there is no limit to judge against. */
    val stateText: String?,
    /** The infield's caption line. */
    val caption: String,
    /** Lane 1 (the total) followed by the app lanes. */
    val lanes: List<OvalLane>,
)

/**
 * Distances as the oval writes them: whole metres under a kilometre, two fixed decimals
 * above it ("2.00 km"), so a caption keeps its width as the number moves.
 */
fun ovalDistance(meters: Double): String {
    val rounded = meters.coerceAtLeast(0.0).roundToLong()
    return if (rounded < 1000) "$rounded m" else String.format(Locale.US, "%.2f km", rounded / 1000.0)
}

/** "4.6 laps". One decimal, always. */
fun lapsText(meters: Double): String =
    String.format(Locale.US, "%.1f laps", meters.coerceAtLeast(0.0) / LAP_METERS)

/**
 * With a limit a lap is the limit. Without one it is the next whole 400 m above today's
 * total, never less than 400 m.
 */
fun lapScale(totalMeters: Double, limitMeters: Double?): Double {
    if (limitMeters != null && limitMeters > 0.0) return limitMeters
    return maxOf(LAP_METERS, ceil(totalMeters.coerceAtLeast(0.0) / LAP_METERS) * LAP_METERS)
}

/**
 * The app lanes: the busiest first, at most four. With more than four apps the first three
 * keep their own lanes and everything else is summed into the last lane as "Other".
 */
fun appLanes(apps: List<Pair<String, Double>>): List<Triple<String, Double, Boolean>> {
    val ranked = apps
        .filter { it.first.isNotBlank() && it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
    if (ranked.size <= MAX_APP_LANES) return ranked.map { Triple(it.first, it.second, false) }
    val kept = ranked.take(MAX_APP_LANES - 1).map { Triple(it.first, it.second, false) }
    val rest = ranked.drop(MAX_APP_LANES - 1).sumOf { it.second }
    return kept + Triple("Other", rest, true)
}

private fun share(value: Double, scale: Double): Float =
    if (scale <= 0.0) 0f else (value / scale).coerceIn(0.0, 1.0).toFloat()

/** Everything the oval and its text rows need for one day. */
fun ovalModel(
    totalMeters: Double,
    apps: List<Pair<String, Double>> = emptyList(),
    limitMeters: Double? = null,
): OvalModel {
    val total = totalMeters.coerceAtLeast(0.0)
    val limit = limitMeters?.takeIf { it > 0.0 }
    val scale = lapScale(total, limit)

    var state = OvalState.OPEN
    var stateText: String? = null
    var caption = "${lapsText(total)}, no limit"
    var totalFraction = share(total, scale)

    if (limit != null) {
        // Compared in whole metres, the same figures the caption prints, so a day that
        // reads "0 m left" is never also called over the limit.
        val shown = total.roundToLong()
        val cap = limit.roundToLong()
        if (shown > cap) {
            state = OvalState.OVER
            stateText = "Over the limit"
            totalFraction = 1f
            caption = "${ovalDistance((shown - cap).toDouble())} over ${ovalDistance(cap.toDouble())}"
        } else {
            val near = total >= limit * OVAL_WARN_SHARE
            state = if (near) OvalState.NEAR else OvalState.UNDER
            stateText = if (near) "Close to the limit" else "Under the limit"
            caption = "${ovalDistance((cap - shown).toDouble())} left of ${ovalDistance(cap.toDouble())}"
        }
    }

    val lanes = buildList {
        add(OvalLane(1, "Total", total, totalFraction))
        appLanes(apps).forEachIndexed { index, (name, meters, other) ->
            add(OvalLane(index + 2, name, meters, share(meters, scale), other))
        }
    }
    return OvalModel(scale, state, stateText, caption, lanes)
}

/** The oval in words, for screen readers. */
fun ovalDescription(model: OvalModel): String {
    val lanes = model.lanes.joinToString(". ") { "Lane ${it.lane}, ${it.name}, ${ovalDistance(it.meters)}" }
    return "Today drawn as a running track. ${model.caption}. $lanes."
}
