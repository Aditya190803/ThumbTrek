package com.thumbtrek.app.share

import android.content.Context
import android.net.Uri
import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.social.SOURCE_ANDROID
import com.thumbtrek.app.social.pixelsToMicrometres
import com.thumbtrek.app.widget.TrekWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToLong

// Backup and restore of the full history, as one JSON file the user keeps wherever they
// like. Distances are micrometres, the same unit that crosses the network (sync-protocol
// §1), so a backup restores correctly on a phone with a different screen density. The
// browser extension writes the same format with source "web".

const val BACKUP_FORMAT = "thumbtrek-backup"
const val BACKUP_VERSION = 1
const val BACKUP_MIME = "application/json"

/** One day of one source: micrometres per app. */
data class BackupDay(val date: String, val apps: Map<String, Long>)

data class BackupSettings(
    val limitMeters: Float? = null,
    val appLimits: Map<String, Float> = emptyMap(),
    val customApps: Map<String, String> = emptyMap(),
)

data class Backup(
    val source: String,
    val exportedAt: String,
    val days: List<BackupDay>,
    val settings: BackupSettings = BackupSettings(),
)

/** Why a file could not be restored, in words a person can act on. */
class BackupException(message: String) : Exception(message)

private const val MICROMETRES_PER_INCH = 25_400.0

fun micrometresToPixels(um: Long, densityDpi: Int): Long =
    (um / MICROMETRES_PER_INCH * densityDpi).roundToLong()

fun backupOf(
    rows: List<DailyScroll>,
    densityDpi: Int,
    settings: BackupSettings,
    exportedAt: String,
): Backup = Backup(
    source = SOURCE_ANDROID,
    exportedAt = exportedAt,
    days = rows.groupBy { it.date }.toSortedMap().map { (date, dayRows) ->
        BackupDay(
            date,
            dayRows.filter { it.pixels > 0 }
                .associate { it.packageName to pixelsToMicrometres(it.pixels, densityDpi) }
                .toSortedMap(),
        )
    },
    settings = settings,
)

fun Backup.toJson(): String = JSONObject().apply {
    put("format", BACKUP_FORMAT)
    put("version", BACKUP_VERSION)
    put("source", source)
    put("exportedAt", exportedAt)
    put("days", org.json.JSONArray().apply {
        days.forEach { day ->
            put(JSONObject().apply {
                put("date", day.date)
                put("apps", JSONObject().apply { day.apps.forEach { (k, v) -> put(k, v) } })
            })
        }
    })
    put("settings", JSONObject().apply {
        settings.limitMeters?.let { put("limitMeters", it.toDouble()) }
        put("appLimits", JSONObject().apply { settings.appLimits.forEach { (k, v) -> put(k, v.toDouble()) } })
        put("customApps", JSONObject().apply { settings.customApps.forEach { (k, v) -> put(k, v) } })
    })
}.toString(2)

/**
 * Reads a backup file. Strict about what it is and lenient about what is in it: a file
 * from somewhere else is refused with a reason, while one odd row in a real backup is
 * skipped instead of costing the user the other two years.
 */
fun parseBackup(text: String): Backup {
    val json = try {
        JSONObject(text)
    } catch (_: JSONException) {
        throw BackupException("That file is not a ThumbTrek backup.")
    }
    if (json.optString("format") != BACKUP_FORMAT) {
        throw BackupException("That file is not a ThumbTrek backup.")
    }
    if (json.optInt("version", 0) > BACKUP_VERSION) {
        throw BackupException("That backup was made by a newer version of ThumbTrek. Update the app, then try again.")
    }
    val source = json.optString("source")
    if (source != SOURCE_ANDROID) {
        throw BackupException(
            "That backup is from the browser extension. Restore it from the extension's settings page.",
        )
    }
    val days = buildList {
        val array = json.optJSONArray("days") ?: return@buildList
        for (i in 0 until array.length()) {
            val day = array.optJSONObject(i) ?: continue
            val date = day.optString("date")
            if (runCatching { LocalDate.parse(date) }.isFailure) continue
            val apps = day.optJSONObject("apps") ?: continue
            val parsed = apps.keys().asSequence()
                .mapNotNull { key -> apps.optLong(key, -1L).takeIf { it > 0L }?.let { key to it } }
                .toMap()
            if (parsed.isNotEmpty()) add(BackupDay(date, parsed))
        }
    }
    val settings = json.optJSONObject("settings")
    fun floats(name: String): Map<String, Float> = settings?.optJSONObject(name)?.let { obj ->
        obj.keys().asSequence()
            .mapNotNull { k -> obj.optDouble(k).takeIf { it.isFinite() && it > 0 }?.let { k to it.toFloat() } }
            .toMap()
    }.orEmpty()
    return Backup(
        source = source,
        exportedAt = json.optString("exportedAt"),
        days = days,
        settings = BackupSettings(
            limitMeters = settings?.optDouble("limitMeters")?.takeIf { it.isFinite() && it > 0 }?.toFloat(),
            appLimits = floats("appLimits"),
            customApps = settings?.optJSONObject("customApps")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues { it.isNotBlank() }
            }.orEmpty(),
        ),
    )
}

fun Backup.toRows(densityDpi: Int): List<DailyScroll> = days.flatMap { day ->
    day.apps.map { (pkg, um) -> DailyScroll(pkg, day.date, micrometresToPixels(um, densityDpi)) }
}

/** What a restore brought in, for the line the screen shows afterwards. */
data class RestoreResult(val days: Int, val from: String?, val to: String?)

/** Reads and writes backup files through the system file picker's content URIs. */
object BackupStore {

    fun suggestedName(today: LocalDate = LocalDate.now()): String = "thumbtrek-backup-$today.json"

    suspend fun write(context: Context, target: Uri) = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val backup = backupOf(
            rows = ScrollDatabase.get(context).dao().allRows(),
            densityDpi = context.resources.displayMetrics.densityDpi,
            settings = BackupSettings(
                limitMeters = prefs.dailyLimitM.value,
                appLimits = prefs.appLimits.value,
                customApps = prefs.customApps.value,
            ),
            exportedAt = java.time.Instant.now().toString(),
        )
        context.contentResolver.openOutputStream(target, "wt")?.use {
            it.write(backup.toJson().toByteArray())
        } ?: throw BackupException("Could not open that location for writing.")
    }

    /**
     * Merges a backup into this phone. History is merged day by day (see `ScrollDao.restore`).
     * Settings only fill gaps: a limit or an app limit already set here is the user's
     * current choice and a file from last month does not get to overrule it.
     */
    suspend fun restore(context: Context, source: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(source)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw BackupException("Could not open that file.")
        val backup = parseBackup(text)
        if (backup.days.isEmpty()) throw BackupException("That backup has no history in it.")

        val dpi = context.resources.displayMetrics.densityDpi
        ScrollDatabase.get(context).dao().restore(backup.toRows(dpi))

        val prefs = Prefs.get(context)
        backup.settings.customApps.forEach { (pkg, label) ->
            if (pkg !in prefs.customApps.value) prefs.addCustomApp(pkg, label)
        }
        prefs.replaceAppLimits(backup.settings.appLimits + prefs.appLimits.value)
        if (prefs.dailyLimitM.value == null && prefs.baselineSince.value == null) {
            backup.settings.limitMeters?.let { prefs.adoptSharedLimit(it, System.currentTimeMillis()) }
        }
        TrekWidgetProvider.updateAll(context)
        RestoreResult(backup.days.size, backup.days.minOf { it.date }, backup.days.maxOf { it.date })
    }
}
