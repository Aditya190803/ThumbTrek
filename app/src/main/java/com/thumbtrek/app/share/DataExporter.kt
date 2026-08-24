package com.thumbtrek.app.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dumps every stored row — one per app per day, raw adjusted pixels exactly as the
 * tracker accumulated them — into a CSV in the cache dir and returns a shareable
 * content URI via the existing FileProvider (`cache-path` → `share/`).
 */
object DataExporter {

    private const val FILE_NAME = "thumbtrek_export.csv"

    suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
        val rows = ScrollDatabase.get(context).dao().allRows()
        val labels = Prefs.get(context).customApps.value
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        file.writeText(buildString {
            append("date,app,pixels\n")
            rows.forEach { row ->
                // Labels never contain quotes or commas (PackageManager names), but be
                // strict anyway rather than trusting that forever.
                val name = appName(row.packageName, labels).replace("\"", "\"\"")
                append("${row.date},\"$name\",${row.pixels}\n")
            }
        })
        FileProvider.getUriForFile(context, "com.thumbtrek.app.fileprovider", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
