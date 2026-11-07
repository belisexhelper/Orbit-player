package com.orbitplayer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.Net
import com.orbitplayer.data.db.AppDatabase
import com.orbitplayer.data.db.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Preuzimanje filmova i epizoda za gledanje bez interneta (sa prevodima).
 * Koristi sistemski DownloadManager: nastavlja posle prekida i prikazuje obaveštenje.
 */
class Downloads(private val context: Context, private val db: AppDatabase) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    val all = db.downloads().observeAll()

    fun folder(): File = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Orbit").apply { mkdirs() }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9čćšđžČĆŠĐŽ._ -]"), "").trim().take(60).ifBlank { "video" }

    suspend fun existing(contentKey: String): DownloadEntity? = db.downloads().byContent(contentKey)

    suspend fun enqueue(contentKey: String, kind: String, title: String, poster: String, url: String, ext: String): DownloadEntity = withContext(Dispatchers.IO) {
        db.downloads().byContent(contentKey)?.let { if (it.status != "failed") return@withContext it else delete(it.id) }
        val name = "${safeName(title)}-${System.currentTimeMillis() % 100000}.${ext.ifBlank { "mp4" }}"
        val file = File(folder(), name)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Downloading for offline viewing")
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "Orbit/$name")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .addRequestHeader("User-Agent", Net.USER_AGENT)
        @Suppress("DEPRECATION")
        req.setAllowedOverMetered(true).setAllowedOverRoaming(false).setVisibleInDownloadsUi(false)
        val sysId = dm.enqueue(req)
        val entity = DownloadEntity(contentKey = contentKey, kind = kind, title = title, poster = poster, sourceUrl = url, filePath = file.absolutePath, systemId = sysId, status = "running")
        val id = db.downloads().insert(entity)
        entity.copy(id = id)
    }

    /** Osvežava napredak iz sistemskog DownloadManager-a */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        for (d in db.downloads().all()) {
            if (d.status == "done" || d.status == "failed" || d.systemId < 0) continue
            val c = dm.query(DownloadManager.Query().setFilterById(d.systemId)) ?: continue
            c.use { cur ->
                if (!cur.moveToFirst()) {
                    db.downloads().update(d.copy(status = "failed", error = "Download was cancelled"))
                    return@use
                }
                val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val status = when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> "done"
                    DownloadManager.STATUS_FAILED -> "failed"
                    DownloadManager.STATUS_PAUSED -> "paused"
                    DownloadManager.STATUS_PENDING -> "queued"
                    else -> "running"
                }
                val err = if (status == "failed") "Error $reason" else ""
                db.downloads().update(d.copy(status = status, sizeBytes = total.coerceAtLeast(0), downloadedBytes = done.coerceAtLeast(0), error = err))
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val d = db.downloads().get(id) ?: return@withContext
        if (d.systemId >= 0) runCatching { dm.remove(d.systemId) }
        runCatching { File(d.filePath).delete() }
        d.subtitlePaths.lines().filter { it.isNotBlank() }.forEach { runCatching { File(it).delete() } }
        db.downloads().delete(id)
    }

    /** Dodaje prevod (SRT/VTT/ASS) iz fajla ili sa adrese preuzetom videu */
    suspend fun addSubtitle(id: Long, source: Uri?, url: String?, language: String): File = withContext(Dispatchers.IO) {
        val d = db.downloads().get(id) ?: throw IOException("Download not found")
        val ext = guessSubExt(source?.lastPathSegment ?: url ?: "")
        val base = File(d.filePath).nameWithoutExtension
        val target = File(folder(), "$base.${language.ifBlank { "sub" }}.${System.currentTimeMillis() % 1000}.$ext")
        if (source != null) {
            context.contentResolver.openInputStream(source)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("The file cannot be opened")
        } else if (!url.isNullOrBlank()) {
            Net.iptv.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) throw IOException("Subtitle is not available (HTTP ${res.code})")
                target.outputStream().use { out -> res.body!!.byteStream().copyTo(out) }
            }
        } else throw IOException("Choose a file or enter the subtitle address")
        val paths = (d.subtitlePaths.lines().filter { it.isNotBlank() } + target.absolutePath).joinToString("\n")
        db.downloads().update(d.copy(subtitlePaths = paths))
        AppLog.d("Prevod dodat: ${target.name}")
        target
    }

    companion object {
        fun guessSubExt(name: String): String {
            val n = name.lowercase().substringBefore('?')
            return when {
                n.endsWith(".vtt") -> "vtt"
                n.endsWith(".ass") || n.endsWith(".ssa") -> "ass"
                n.endsWith(".ttml") || n.endsWith(".xml") -> "ttml"
                else -> "srt"
            }
        }

        fun subtitleMime(path: String): String = when (guessSubExt(path)) {
            "vtt" -> "text/vtt"
            "ass" -> "text/x-ssa"
            "ttml" -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
    }
}
