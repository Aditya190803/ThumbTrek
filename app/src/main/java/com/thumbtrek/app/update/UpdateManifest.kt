package com.thumbtrek.app.update

import org.json.JSONObject

/**
 * The `update.json` the release workflow publishes alongside each APK.
 *
 * Parsed with `org.json` (in the platform since forever) rather than a serialization
 * library — this is one small object and the app has no JSON dependency otherwise.
 *
 * ```json
 * {
 *   "versionCode": 3,
 *   "versionName": "0.2.0",
 *   "apkUrl": "https://github.com/<owner>/<repo>/releases/download/v0.2.0/thumbtrek-0.2.0.apk",
 *   "sha256": "9f2c…",
 *   "sizeBytes": 8123456,
 *   "notes": "…release notes…",
 *   "publishedAt": "2026-09-05T12:00:00Z"
 * }
 * ```
 */
data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    /** Lowercase hex SHA-256 of the APK. Non-negotiable: no hash, no install. */
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
    val publishedAt: String,
) {
    /**
     * Round-trips back to the same shape [parse] reads, so the last-seen manifest can be
     * cached in SharedPreferences and restored on a cold start.
     */
    fun toJson(): String = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("apkUrl", apkUrl)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("notes", notes)
        .put("publishedAt", publishedAt)
        .toString()

    companion object {

        /**
         * Returns null rather than throwing on anything malformed — a broken release
         * asset should read as "no update available", never as a crash in a Worker.
         */
        fun parse(json: String): UpdateManifest? = runCatching {
            val o = JSONObject(json)
            val versionCode = o.optLong("versionCode", -1L)
            val versionName = o.optString("versionName").orEmpty()
            val apkUrl = o.optString("apkUrl").orEmpty()
            val sha256 = o.optString("sha256").orEmpty().lowercase()

            // Every one of these is load-bearing. A manifest missing the hash, or pointing
            // anywhere other than github.com, is treated as no manifest at all.
            if (versionCode <= 0L || versionName.isBlank()) return null
            if (!apkUrl.startsWith("https://github.com/")) return null
            if (!sha256.matches(Regex("[0-9a-f]{64}"))) return null

            UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = apkUrl,
                sha256 = sha256,
                sizeBytes = o.optLong("sizeBytes", 0L),
                notes = o.optString("notes").orEmpty().trim(),
                publishedAt = o.optString("publishedAt").orEmpty(),
            )
        }.getOrNull()
    }
}
