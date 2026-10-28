package com.android_explorer.data

import android.content.Context
import com.android_explorer.BuildConfig
import com.android_explorer.job.JobCancelledException
import com.android_explorer.job.JobListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A published GitHub release that carries an installable APK. */
data class AppRelease(
    val tag: String,
    val versionCode: Int,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
)

/** Checks the GitHub releases of this repo for a newer APK and downloads it into the cache. */
object AppUpdateRepository {
    private const val LATEST_URL = "https://api.github.com/repos/nivelij/android-explorer/releases/latest"

    private val client = OkHttpClient()

    /**
     * The latest release if it is newer than the running build, else null. Any failure (offline,
     * GitHub's 60/h anonymous rate limit, a release without an APK) also yields null: a launch-time
     * check has nothing useful to tell the user about it.
     */
    fun newerRelease(): AppRelease? = runCatching {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-explorer/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            parseRelease(resp.body?.string() ?: return null)
        }
    }.getOrNull()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    private fun parseRelease(json: String): AppRelease? {
        val release = JSONObject(json)
        val tag = release.getString("tag_name")
        val code = versionCodeOf(tag) ?: return null
        val assets = release.getJSONArray("assets")
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        return AppRelease(
            tag = tag,
            versionCode = code,
            apkName = apk.getString("name"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
        )
    }

    /** Streams the release APK into the cache; throws on failure or [JobCancelledException]. */
    fun download(context: Context, release: AppRelease, listener: JobListener): File {
        val dir = downloadDir(context).apply { mkdirs() }
        val dest = File(dir, release.apkName)
        // Only a complete file ever carries the .apk name; a truncated one makes the installer fail
        // with an unhelpful "problem parsing the package".
        val part = File(dir, "${release.apkName}.part")
        try {
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
                val body = resp.body ?: throw IOException("Download failed (empty response)")
                val total = release.apkSize.takeIf { it > 0 } ?: body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    part.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (listener.isCancelled()) throw JobCancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            listener.onProgress(release.apkName, 0, 1, done, total)
                        }
                    }
                }
                if (release.apkSize > 0 && done != release.apkSize) {
                    throw IOException("Download incomplete (${done} of ${release.apkSize} bytes)")
                }
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("Couldn't save the downloaded update")
            return dest
        } finally {
            part.delete()
        }
    }

    /** Drops APKs left over from earlier updates; called once per process start. */
    fun clearDownloads(context: Context) {
        downloadDir(context).deleteRecursively()
    }

    private fun downloadDir(context: Context) = File(context.cacheDir, "updates")
}

/**
 * Maps a release tag to the versionCode CI builds it with (`v1.2.3` -> 10203, see
 * .github/workflows/release.yml), so "newer" means exactly what the package installer will accept
 * as an upgrade. Null for anything that isn't a 1–3 part numeric version.
 */
fun versionCodeOf(tag: String): Int? {
    val parts = tag.trim().removePrefix("v").removePrefix("V").split('.')
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    val (major, minor, patch) = nums + List(3 - nums.size) { 0 }
    return major * 10_000 + minor * 100 + patch
}
