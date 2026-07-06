package com.android_explorer.data.drive

import android.content.Context
import com.android_explorer.data.VolumeStat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Google Drive folder MIME type — everything else is treated as a file. */
const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"

/** A single Drive entry as returned by files.list (backend DTO; mapped to FileItem by DriveBackend). */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: Long,
    val parentId: String?,
) {
    val isFolder: Boolean get() = mimeType == DRIVE_FOLDER_MIME
}

/**
 * Drive REST v3 client over OkHttp. Every call authorizes with a fresh access token from [DriveAuth];
 * a 401 (expired token) is retried once with a new token. Reads: list/download/about. Writes: upload,
 * createFolder, rename, move, trash, copy (Phase 2).
 */
object DriveApi {
    private const val BASE = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
    private val client = OkHttpClient()
    private val JSON = "application/json; charset=UTF-8".toMediaType()

    class NotAuthorizedException : IOException("No Drive access token — reconnect required")

    private const val FILE_FIELDS = "id,name,mimeType,size,modifiedTime,parents"

    // ---- reads ----

    /** List the non-trashed children of [folderId] ("root" for My Drive), following pagination. */
    suspend fun listFolder(context: Context, folderId: String = "root"): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<DriveFile>()
            var pageToken: String? = null
            do {
                val q = "'${folderId}' in parents and trashed = false"
                val url = buildString {
                    append("$BASE/files?")
                    append("q=").append(urlEncode(q))
                    append("&fields=").append(urlEncode("nextPageToken,files($FILE_FIELDS)"))
                    append("&pageSize=1000&orderBy=folder,name")
                    if (pageToken != null) append("&pageToken=").append(urlEncode(pageToken!!))
                }
                val json = execJson(context) { token -> get(url, token) }
                json.optJSONArray("files")?.let { files ->
                    for (i in 0 until files.length()) out += parseFile(files.getJSONObject(i))
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            } while (pageToken != null)
            out
        }

    /** The connected account's email address (Drive `about.user`), or null if unavailable. */
    suspend fun accountEmail(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            execJson(context) { get("$BASE/about?fields=user", it) }
                .optJSONObject("user")?.optString("emailAddress")?.ifEmpty { null }
        }.getOrNull()
    }

    /** Drive storage quota as a [VolumeStat] so the same StorageMeter renders it. */
    suspend fun storageQuota(context: Context): VolumeStat? = withContext(Dispatchers.IO) {
        runCatching {
            val q = execJson(context) { get("$BASE/about?fields=storageQuota", it) }
                .optJSONObject("storageQuota") ?: return@runCatching null
            val usage = q.optString("usage").toLongOrNull() ?: 0L
            val limit = q.optString("limit").toLongOrNull()
            val total = limit ?: usage
            VolumeStat("Google Drive", "", total, (total - usage).coerceAtLeast(0))
        }.getOrNull()
    }

    /** Download the media content of [fileId] to [dest]. Caller supplies a cache/target file. */
    suspend fun download(context: Context, fileId: String, dest: File): Unit =
        withContext(Dispatchers.IO) {
            val token = DriveAuth.accessToken(context) ?: throw NotAuthorizedException()
            val req = get("$BASE/files/$fileId?alt=media", token)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Drive download failed: HTTP ${resp.code}")
                dest.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
            }
        }

    // ---- writes ----

    /** Upload [local] into Drive folder [parentId]; returns the new file id. */
    suspend fun uploadFile(context: Context, local: File, parentId: String, name: String = local.name): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
            val mime = java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(meta.toString().toRequestBody(JSON))
                .addPart(local.asRequestBody(mime.toMediaTypeOrNull()))
                .build()
            execJson(context) { token -> Request.Builder().url(UPLOAD).bearer(token).post(body).build() }
                .getString("id")
        }

    /** Create a folder named [name] under [parentId]; returns the new folder id. */
    suspend fun createFolder(context: Context, name: String, parentId: String): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("name", name).put("mimeType", DRIVE_FOLDER_MIME)
                .put("parents", JSONArray().put(parentId))
            execJson(context) { token ->
                Request.Builder().url("$BASE/files?fields=id").bearer(token)
                    .post(meta.toString().toRequestBody(JSON)).build()
            }.getString("id")
        }

    /** Rename [fileId] to [newName]. */
    suspend fun rename(context: Context, fileId: String, newName: String): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("name", newName).toString().toRequestBody(JSON)
            execJson(context) { token ->
                Request.Builder().url("$BASE/files/$fileId?fields=id").bearer(token).patch(body).build()
            }
        }

    /** Reparent [fileId]: add [addParent], remove [removeParent] (a Drive→Drive move). */
    suspend fun move(context: Context, fileId: String, addParent: String, removeParent: String): Unit =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody(JSON)
            val url = "$BASE/files/$fileId?addParents=${urlEncode(addParent)}" +
                "&removeParents=${urlEncode(removeParent)}&fields=id"
            execJson(context) { token -> Request.Builder().url(url).bearer(token).patch(body).build() }
        }

    /** Move [fileId] to the Drive trash (recoverable "delete"). */
    suspend fun trash(context: Context, fileId: String): Unit =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("trashed", true).toString().toRequestBody(JSON)
            execJson(context) { token ->
                Request.Builder().url("$BASE/files/$fileId?fields=id").bearer(token).patch(body).build()
            }
        }

    /** Server-side copy of a **file** [fileId] into [destParentId]; returns the new id. (Not folders.) */
    suspend fun copyFile(context: Context, fileId: String, destParentId: String, name: String? = null): String =
        withContext(Dispatchers.IO) {
            val meta = JSONObject().put("parents", JSONArray().put(destParentId))
            if (name != null) meta.put("name", name)
            execJson(context) { token ->
                Request.Builder().url("$BASE/files/$fileId/copy?fields=id").bearer(token)
                    .post(meta.toString().toRequestBody(JSON)).build()
            }.getString("id")
        }

    // ---- internals ----

    private fun get(url: String, token: String): Request =
        Request.Builder().url(url).bearer(token).build()

    private fun Request.Builder.bearer(token: String) = header("Authorization", "Bearer $token")

    /** Run an authed request with a one-shot 401 refresh-retry; returns the parsed JSON body ({} if empty). */
    private suspend fun execJson(context: Context, build: (token: String) -> Request): JSONObject =
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                val token = DriveAuth.accessToken(context) ?: throw NotAuthorizedException()
                client.newCall(build(token)).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    when {
                        resp.isSuccessful -> return@withContext if (bodyStr.isEmpty()) JSONObject() else JSONObject(bodyStr)
                        resp.code == 401 && attempt == 0 -> Unit // refresh + retry
                        else -> throw IOException("Drive request failed: HTTP ${resp.code} ${bodyStr.take(200)}")
                    }
                }
            }
            throw NotAuthorizedException()
        }

    private fun parseFile(o: JSONObject): DriveFile {
        val parents = o.optJSONArray("parents")
        return DriveFile(
            id = o.getString("id"),
            name = o.optString("name"),
            mimeType = o.optString("mimeType"),
            size = o.optString("size").toLongOrNull() ?: 0L,
            modifiedTime = parseRfc3339(o.optString("modifiedTime")),
            parentId = if (parents != null && parents.length() > 0) parents.getString(0) else null,
        )
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun parseRfc3339(s: String): Long =
        if (s.isEmpty()) 0L else runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrDefault(0L)
}
