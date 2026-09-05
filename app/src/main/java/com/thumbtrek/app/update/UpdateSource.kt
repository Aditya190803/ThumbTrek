package com.thumbtrek.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The network half of the updater: talk to GitHub, bring back a manifest, bring back a
 * verified APK. Deliberately dependency-free — `HttpURLConnection` plus `org.json`, both
 * in the platform, because pulling OkHttp/Retrofit in for two GETs would be silly.
 *
 * Nothing here sends anything about the user. Two anonymous GETs to GitHub, that's it.
 */
object UpdateSource {

    /**
     * Two requests: the Releases API tells us which release is newest and where its
     * assets live, then we fetch the `update.json` asset for the version/hash/notes.
     *
     * Returns null when the repo has no releases yet or the release is missing its
     * manifest asset. Throws [IOException] for anything network-shaped, so the caller
     * can distinguish "nothing to update to" from "we could not look".
     */
    suspend fun fetchLatestManifest(userAgent: String): UpdateManifest? = withContext(Dispatchers.IO) {
        val release = get(UpdateConfig.LATEST_RELEASE_API, userAgent, ACCEPT_GITHUB_JSON)
            ?: return@withContext null

        val assets = JSONObject(release).optJSONArray("assets") ?: return@withContext null
        var manifestUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == UpdateConfig.MANIFEST_ASSET_NAME) {
                manifestUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                break
            }
        }
        // A release without update.json was cut by hand rather than by the workflow. We
        // cannot verify a hash we do not have, so we simply do not offer it.
        val body = manifestUrl?.let { get(it, userAgent, ACCEPT_ANY) } ?: return@withContext null
        UpdateManifest.parse(body)
    }

    /**
     * Streams [manifest]'s APK into [destination], hashing as it goes so the file is
     * never read twice, and fails loudly if the SHA-256 does not match. A mismatch means
     * the bytes are not the ones the workflow signed — the partial file is deleted and
     * nothing is ever handed to PackageInstaller.
     *
     * [onProgress] is called with 0..100 (or -1 when the server sends no Content-Length).
     */
    suspend fun downloadVerified(
        manifest: UpdateManifest,
        destination: File,
        userAgent: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        destination.delete()

        val connection = open(manifest.apkUrl, userAgent, ACCEPT_ANY)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            }
            val declared = connection.contentLengthLong
            if (declared > UpdateConfig.MAX_APK_BYTES) {
                throw IOException("Refusing a $declared byte APK — that is not a ThumbTrek build")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            var lastReported = -1

            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Cancelling the worker (or the user backing out) must stop the
                        // transfer, not keep chewing battery in the background.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded > UpdateConfig.MAX_APK_BYTES) {
                            throw IOException("Download exceeded the size ceiling")
                        }
                        val percent = if (declared > 0) ((downloaded * 100) / declared).toInt() else -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent)
                        }
                    }
                }
            }

            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != manifest.sha256) {
                destination.delete()
                throw IOException("SHA-256 mismatch — refusing to install (expected ${manifest.sha256}, got $actual)")
            }
            destination
        } catch (t: Throwable) {
            destination.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the body, or null on 404 (repo has no releases yet). Throws otherwise. */
    private fun get(url: String, userAgent: String, accept: String): String? {
        val connection = open(url, userAgent, accept)
        try {
            return when (val code = connection.responseCode) {
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> null
                // 403 here is almost always the unauthenticated rate limit. Surfacing it
                // as an IOException keeps it out of the "you are up to date" path.
                else -> throw IOException("GitHub returned HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, userAgent: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            readTimeout = UpdateConfig.READ_TIMEOUT_MS
            instanceFollowRedirects = true // asset URLs redirect to the CDN
            setRequestProperty("Accept", accept)
            // GitHub rejects API calls without a User-Agent.
            setRequestProperty("User-Agent", userAgent)
        }

    private const val ACCEPT_GITHUB_JSON = "application/vnd.github+json"
    private const val ACCEPT_ANY = "*/*"
}
