package com.orbitplayer.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.Net
import com.orbitplayer.core.Store
import com.orbitplayer.data.db.AppDatabase
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.FavoriteEntity
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.data.db.ProgramEntity
import com.orbitplayer.data.db.ProgressEntity
import com.orbitplayer.data.db.SeriesEntity
import com.orbitplayer.data.db.VodEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID

/** Progress of loading one playlist, shown in the app */
data class SyncStatus(val running: Boolean = false, val step: String = "", val error: String = "", val doneAt: Long = 0)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val plot: String,
    val image: String,
    val durationSec: Int,
)

data class SeriesDetail(
    val plot: String,
    val cover: String,
    val backdrop: String,
    val genre: String,
    val cast: String,
    val rating: String,
    val year: String,
    val seasons: Map<Int, List<Episode>>,
)

data class VodDetail(
    val plot: String,
    val genre: String,
    val cast: String,
    val director: String,
    val duration: String,
    val year: String,
    val backdrop: String,
)

/** Input from the "Add playlist" form */
data class PlaylistInput(
    val name: String,
    val type: String, // xtream | m3u
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val includeVod: Boolean = true,
    val includeSeries: Boolean = true,
)

class ContentRepository(private val context: Context, val db: AppDatabase, private val store: Store) {

    private val _sync = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val syncStatus: StateFlow<Map<String, SyncStatus>> = _sync.asStateFlow()

    private val syncLock = Mutex()
    private val seriesCache = HashMap<String, SeriesDetail>()

    val playlists = db.playlists().observeAll()

    // ---------------- playlists ----------------

    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.replace(Regex("/(player_api|get|xmltv)\\.php.*$"), "")
        return s.trimEnd('/')
    }

    /** Checks the input (logs in to Xtream) and saves the playlist. Throws with a readable message. */
    suspend fun savePlaylist(input: PlaylistInput, existingId: String? = null): PlaylistEntity = withContext(Dispatchers.IO) {
        val name = input.name.trim().ifBlank { if (input.type == "xtream") "My playlist" else "M3U playlist" }
        val old = existingId?.let { db.playlists().get(it) }
        var p = PlaylistEntity(
            id = existingId ?: UUID.randomUUID().toString(),
            name = name,
            type = input.type,
            server = if (input.type == "xtream") normalizeServer(input.server) else "",
            username = input.username.trim(),
            password = input.password,
            url = input.url.trim(),
            epgUrl = input.epgUrl.trim(),
            includeVod = input.includeVod,
            includeSeries = input.includeSeries,
            sort = old?.sort ?: db.playlists().all().size,
        )
        if (p.type == "xtream") {
            if (p.server.isBlank() || p.username.isBlank() || p.password.isBlank()) throw IOException("Enter the server address, username and password")
            val acc = xtream(p).account()
            val ui = acc.optJSONObject("user_info")
            p = p.copy(
                expiresAt = (ui?.optString("exp_date")?.toLongOrNull() ?: 0L) * 1000,
                maxConnections = ui?.optString("max_connections")?.toIntOrNull() ?: 0,
                accountStatus = ui?.optString("status") ?: "",
                serverTimezone = acc.optJSONObject("server_info")?.optString("timezone") ?: "",
            )
        } else if (p.url.isBlank()) throw IOException("Enter the playlist address")
        db.playlists().upsert(p)
        if (store.activePlaylistId.isBlank() || db.playlists().get(store.activePlaylistId) == null) store.activePlaylistId = p.id
        p
    }

    suspend fun removePlaylist(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            clearContent(id)
            db.epg().clear(id)
            db.playlists().delete(id)
        }
        if (store.activePlaylistId == id) store.activePlaylistId = db.playlists().all().firstOrNull()?.id ?: ""
    }

    private suspend fun clearContent(pid: String) {
        val c = db.content()
        c.clearChannels(pid)
        c.clearVod(pid)
        c.clearSeries(pid)
        for (k in listOf("live", "vod", "series")) c.clearCategories(pid, k)
    }

    fun xtream(p: PlaylistEntity) = XtreamClient(p.server, p.username, p.password)

    // ---------------- loading content ----------------

    private fun status(id: String, s: SyncStatus) = _sync.update { it + (id to s) }

    /** Refreshes playlists and guides that are older than the refresh interval (or all when [force]). */
    suspend fun syncAll(force: Boolean = false) {
        val s = store.settings.value
        for (p in db.playlists().all()) {
            val stale = System.currentTimeMillis() - p.lastSyncAt > s.playlistRefreshHours * 3600_000L
            if (force || stale || p.lastSyncAt == 0L) syncPlaylist(p.id)
            val fresh = db.playlists().get(p.id) ?: continue
            val epgStale = System.currentTimeMillis() - fresh.lastEpgAt > s.epgRefreshHours * 3600_000L
            if (force || epgStale) syncEpg(p.id)
        }
    }

    suspend fun syncPlaylist(id: String) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val p = db.playlists().get(id) ?: return@withLock
            status(id, SyncStatus(running = true, step = "Loading playlist…"))
            try {
                if (p.type == "xtream") syncXtream(p) else syncM3u(p)
                db.playlists().markSynced(id, System.currentTimeMillis(), "")
                seriesCache.clear()
                status(id, SyncStatus(doneAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                AppLog.e("Playlist ${p.name} failed to load", e)
                db.playlists().markSynced(id, p.lastSyncAt, e.message ?: "Error")
                status(id, SyncStatus(error = e.message ?: "The playlist could not be loaded"))
            }
        }
    }

    private fun isAdult(name: String): Boolean {
        val n = name.lowercase()
        return store.settings.value.adultWords.any { n.contains(it) }
    }

    private suspend fun syncXtream(p: PlaylistEntity) {
        val x = xtream(p)
        val acc = x.account()
        val ui = acc.optJSONObject("user_info")
        db.playlists().setAccount(
            p.id,
            acc.optJSONObject("server_info")?.optString("timezone") ?: p.serverTimezone,
            (ui?.optString("exp_date")?.toLongOrNull() ?: 0L) * 1000,
            ui?.optString("max_connections")?.toIntOrNull() ?: 0,
            ui?.optString("status") ?: "",
        )
        var liveN = 0
        var vodN = 0
        var seriesN = 0
        val kinds = buildList {
            add("live")
            if (p.includeVod) add("vod")
            if (p.includeSeries) add("series")
        }
        for (kind in kinds) {
            status(p.id, SyncStatus(running = true, step = when (kind) { "live" -> "Loading channels…"; "vod" -> "Loading movies…"; else -> "Loading series…" }))
            val cats = runCatching { x.categories(kind) }.getOrElse { emptyList() }
            val adultCats = HashSet<String>()
            val catEntities = cats.mapIndexed { i, (cid, name) ->
                val adult = isAdult(name)
                if (adult) adultCats += cid
                CategoryEntity("${p.id}:$kind:$cid", p.id, kind, cid, name, i, adult)
            }
            when (kind) {
                "live" -> {
                    val list = ArrayList<ChannelEntity>()
                    x.streams(kind) { m ->
                        val sid = m["stream_id"] ?: return@streams
                        val cat = m["category_id"] ?: ""
                        list += ChannelEntity(
                            key = "${p.id}:$sid",
                            playlistId = p.id,
                            streamId = sid,
                            num = m["num"]?.toIntOrNull() ?: (list.size + 1),
                            name = m["name"] ?: "",
                            logo = m["stream_icon"] ?: "",
                            categoryId = cat,
                            url = "",
                            epgKey = m["epg_channel_id"]?.takeIf { it.isNotBlank() }?.let { "${p.id}:${it.lowercase()}" } ?: "",
                            archive = m["tv_archive"] == "1",
                            archiveDays = m["tv_archive_duration"]?.toIntOrNull() ?: 0,
                            adult = cat in adultCats,
                        )
                    }
                    liveN = list.size
                    db.withTransaction {
                        db.content().clearChannels(p.id)
                        db.content().clearCategories(p.id, kind)
                        db.content().insertCategories(catEntities)
                        list.chunked(1000).forEach { db.content().insertChannels(it) }
                    }
                }
                "vod" -> {
                    val list = ArrayList<VodEntity>()
                    x.streams(kind) { m ->
                        val sid = m["stream_id"] ?: return@streams
                        val cat = m["category_id"] ?: ""
                        list += VodEntity(
                            key = "${p.id}:$sid",
                            playlistId = p.id,
                            streamId = sid,
                            name = m["name"] ?: "",
                            poster = m["stream_icon"] ?: "",
                            categoryId = cat,
                            rating = m["rating"] ?: "",
                            ext = m["container_extension"] ?: "mp4",
                            url = "",
                            added = m["added"]?.toLongOrNull() ?: 0,
                            adult = cat in adultCats,
                        )
                    }
                    vodN = list.size
                    db.withTransaction {
                        db.content().clearVod(p.id)
                        db.content().clearCategories(p.id, kind)
                        db.content().insertCategories(catEntities)
                        list.chunked(1000).forEach { db.content().insertVod(it) }
                    }
                }
                else -> {
                    val list = ArrayList<SeriesEntity>()
                    x.streams(kind) { m ->
                        val sid = m["series_id"] ?: return@streams
                        val cat = m["category_id"] ?: ""
                        list += SeriesEntity(
                            key = "${p.id}:$sid",
                            playlistId = p.id,
                            seriesId = sid,
                            name = m["name"] ?: "",
                            cover = m["cover"] ?: "",
                            categoryId = cat,
                            plot = m["plot"] ?: "",
                            rating = m["rating"] ?: "",
                            added = m["last_modified"]?.toLongOrNull() ?: 0,
                            adult = cat in adultCats,
                        )
                    }
                    seriesN = list.size
                    db.withTransaction {
                        db.content().clearSeries(p.id)
                        db.content().clearCategories(p.id, kind)
                        db.content().insertCategories(catEntities)
                        list.chunked(1000).forEach { db.content().insertSeries(it) }
                    }
                }
            }
        }
        if (!p.includeVod) {
            db.content().clearVod(p.id); db.content().clearCategories(p.id, "vod")
        }
        if (!p.includeSeries) {
            db.content().clearSeries(p.id); db.content().clearCategories(p.id, "series")
        }
        db.playlists().setCounts(p.id, liveN, vodN, seriesN)
    }

    private fun openM3u(p: PlaylistEntity): Pair<InputStream, () -> Unit> {
        val url = p.url
        if (url.startsWith("content://") || url.startsWith("file://")) {
            val input = context.contentResolver.openInputStream(Uri.parse(url)) ?: throw IOException("The playlist file can't be opened")
            return input to { input.close() }
        }
        val res = Net.iptv.newCall(Request.Builder().url(url).build()).execute()
        if (!res.isSuccessful) {
            val code = res.code
            res.close()
            throw IOException("The playlist is not available (HTTP $code)")
        }
        return res.body!!.byteStream() to { res.close() }
    }

    private suspend fun syncM3u(p: PlaylistEntity) {
        val channels = ArrayList<ChannelEntity>()
        val vods = ArrayList<VodEntity>()
        val liveGroups = LinkedHashMap<String, Boolean>()
        val vodGroups = LinkedHashMap<String, Boolean>()
        val (input, close) = openM3u(p)
        val headerEpg = try {
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
            M3uParser.parse(reader) { e ->
                val id = Integer.toHexString((e.url + "|" + e.name).hashCode())
                val adult = isAdult(e.group)
                if (M3uParser.isVod(e)) {
                    if (!p.includeVod) return@parse
                    vodGroups.putIfAbsent(e.group, adult)
                    vods += VodEntity("${p.id}:$id", p.id, id, e.name, e.logo, e.group, "", e.url.substringAfterLast('.', "mp4").substringBefore('?').take(5), e.url, 0, adult)
                } else {
                    liveGroups.putIfAbsent(e.group, adult)
                    channels += ChannelEntity(
                        key = "${p.id}:$id",
                        playlistId = p.id,
                        streamId = id,
                        num = e.chno ?: (channels.size + 1),
                        name = e.name,
                        logo = e.logo,
                        categoryId = e.group,
                        url = e.url,
                        epgKey = e.tvgId.takeIf { it.isNotBlank() }?.let { "${p.id}:${it.lowercase()}" } ?: "",
                        archive = e.catchup.isNotBlank() && e.catchupDays > 0,
                        archiveDays = e.catchupDays,
                        catchupSource = e.catchupSource,
                        adult = adult,
                    )
                }
            }
        } finally {
            close()
        }
        if (channels.isEmpty() && vods.isEmpty()) throw IOException("The playlist is empty or not an M3U file")
        if (headerEpg.isNotBlank()) db.playlists().setEpgIfEmpty(p.id, headerEpg)
        db.withTransaction {
            clearContent(p.id)
            db.content().insertCategories(liveGroups.entries.mapIndexed { i, (g, a) -> CategoryEntity("${p.id}:live:$g", p.id, "live", g, g, i, a) })
            db.content().insertCategories(vodGroups.entries.mapIndexed { i, (g, a) -> CategoryEntity("${p.id}:vod:$g", p.id, "vod", g, g, i, a) })
            channels.chunked(1000).forEach { db.content().insertChannels(it) }
            vods.chunked(1000).forEach { db.content().insertVod(it) }
        }
        db.playlists().setCounts(p.id, channels.size, vods.size, 0)
    }

    suspend fun syncEpg(id: String) = withContext(Dispatchers.IO) {
        val p = db.playlists().get(id) ?: return@withContext
        val url = p.epgUrl.ifBlank { if (p.type == "xtream") xtream(p).xmltvUrl() else "" }
        if (url.isBlank()) return@withContext
        val keys = db.content().epgKeys(id)
        if (keys.isEmpty()) return@withContext
        val wanted = keys.map { it.substringAfter(':') }.toHashSet()
        val now = System.currentTimeMillis()
        val pastDays = store.settings.value.epgPastDays
        val from = now - pastDays * 86_400_000L - 3600_000L
        val to = now + 8 * 86_400_000L
        status(id, SyncStatus(running = true, step = "Loading TV guide…"))
        try {
            val batches = ArrayList<List<ProgramEntity>>()
            Net.iptv.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("TV guide is not available (HTTP ${r.code})")
                val batch = ArrayList<ProgramEntity>(2000)
                XmltvParser.parse(r.body!!.byteStream(), wanted, from, to) { pr ->
                    batch += ProgramEntity(epgKey = "$id:${pr.channel}", playlistId = id, start = pr.start, stop = pr.stop, title = pr.title, description = pr.desc)
                    if (batch.size >= 2000) {
                        batches += ArrayList(batch)
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) batches += batch
            }
            db.withTransaction {
                db.epg().clear(id)
                for (b in batches) db.epg().insert(b)
            }
            db.epg().deleteOld(now - (pastDays + 1) * 86_400_000L)
            db.playlists().markEpg(id, System.currentTimeMillis())
            status(id, SyncStatus(doneAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            AppLog.w("TV guide for ${p.name} failed", e)
            status(id, SyncStatus(error = "TV guide: ${e.message}"))
        }
    }

    suspend fun clearEpg() = withContext(Dispatchers.IO) {
        db.epg().clearAll()
        for (p in db.playlists().all()) db.playlists().markEpg(p.id, 0)
    }

    // ---------------- playback addresses ----------------

    suspend fun channelUrl(ch: ChannelEntity): String {
        if (ch.url.isNotBlank()) return ch.url
        val p = db.playlists().get(ch.playlistId) ?: return ""
        return xtream(p).liveUrl(ch.streamId, store.settings.value.streamFormat)
    }

    /** Recording of a past programme (catch-up) */
    suspend fun catchupUrl(ch: ChannelEntity, prog: ProgramEntity): String? = catchupUrlAt(ch, prog.start, prog.stop)

    /** Archive address for the time span [start, stop) of a channel (minute precision) */
    suspend fun catchupUrlAt(ch: ChannelEntity, start: Long, stop: Long): String? {
        if (!ch.archive) return null
        val p = db.playlists().get(ch.playlistId) ?: return null
        val durMin = ((stop - start) / 60_000).toInt().coerceAtLeast(1)
        if (p.type == "xtream") return xtream(p).timeshiftUrl(ch.streamId, start, durMin, p.serverTimezone)
        if (ch.catchupSource.isNotBlank()) {
            val startSec = start / 1000
            val endSec = stop / 1000
            var src = ch.catchupSource
                .replace("{utc}", startSec.toString())
                .replace("\${start}", startSec.toString())
                .replace("{lutc}", (System.currentTimeMillis() / 1000).toString())
                .replace("{utcend}", endSec.toString())
                .replace("\${end}", endSec.toString())
                .replace("{duration}", (durMin * 60).toString())
            if (!src.startsWith("http")) src = ch.url + src
            return src
        }
        val sep = if (ch.url.contains('?')) "&" else "?"
        return "${ch.url}${sep}utc=${start / 1000}&lutc=${System.currentTimeMillis() / 1000}"
    }

    suspend fun vodUrl(v: VodEntity): String {
        if (v.url.isNotBlank()) return v.url
        val p = db.playlists().get(v.playlistId) ?: return ""
        return xtream(p).vodUrl(v.streamId, v.ext)
    }

    suspend fun episodeUrl(playlistId: String, e: Episode): String {
        val p = db.playlists().get(playlistId) ?: return ""
        return xtream(p).episodeUrl(e.id, e.ext)
    }

    // ---------------- details ----------------

    suspend fun seriesDetail(s: SeriesEntity): SeriesDetail = withContext(Dispatchers.IO) {
        seriesCache[s.key]?.let { return@withContext it }
        val p = db.playlists().get(s.playlistId) ?: throw IOException("Playlist not found")
        val json = xtream(p).seriesInfo(s.seriesId)
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        fun addSeason(key: String, arr: JSONArray) {
            val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { e ->
                val ei = e.optJSONObject("info") ?: JSONObject()
                Episode(
                    id = e.optString("id"),
                    season = e.optInt("season", key.toIntOrNull() ?: 1),
                    number = e.optInt("episode_num", 0),
                    title = e.optString("title"),
                    ext = e.optString("container_extension").ifBlank { "mp4" },
                    plot = ei.optString("plot"),
                    image = ei.optString("movie_image"),
                    durationSec = ei.optInt("duration_secs", 0),
                )
            }.sortedBy { it.number }
            seasons[key.toIntOrNull() ?: list.firstOrNull()?.season ?: 1] = list
        }
        when (val eps = json.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { addSeason(k, it) } }
            is JSONArray -> for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { addSeason((i + 1).toString(), it) }
        }
        val d = SeriesDetail(
            plot = info.optString("plot").ifBlank { s.plot },
            cover = info.optString("cover").ifBlank { s.cover },
            backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: "",
            genre = info.optString("genre"),
            cast = info.optString("cast"),
            rating = info.optString("rating").ifBlank { s.rating },
            year = info.optString("releaseDate").take(4).ifBlank { info.optString("year") },
            seasons = seasons,
        )
        seriesCache[s.key] = d
        d
    }

    suspend fun vodDetail(v: VodEntity): VodDetail? = withContext(Dispatchers.IO) {
        val p = db.playlists().get(v.playlistId) ?: return@withContext null
        if (p.type != "xtream") return@withContext null
        runCatching {
            val info = xtream(p).vodInfo(v.streamId).optJSONObject("info") ?: JSONObject()
            VodDetail(
                plot = info.optString("plot").ifBlank { info.optString("description") },
                genre = info.optString("genre"),
                cast = info.optString("cast").ifBlank { info.optString("actors") },
                director = info.optString("director"),
                duration = info.optString("duration"),
                year = info.optString("releasedate").take(4).ifBlank { info.optString("year") },
                backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: "",
            )
        }.getOrNull()
    }

    // ---------------- backup ----------------

    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val o = JSONObject().put("app", "Orbit Player").put("version", 1).put("createdAt", System.currentTimeMillis())
        o.put("playlists", JSONArray(db.playlists().all().map { p ->
            JSONObject().put("id", p.id).put("name", p.name).put("type", p.type).put("server", p.server)
                .put("username", p.username).put("password", p.password).put("url", p.url).put("epgUrl", p.epgUrl)
                .put("includeVod", p.includeVod).put("includeSeries", p.includeSeries).put("sort", p.sort)
        }))
        o.put("favorites", JSONArray(db.user().favorites().map { JSONObject().put("key", it.key).put("kind", it.kind).put("addedAt", it.addedAt) }))
        o.put("progress", JSONArray(db.user().allProgress().map {
            JSONObject().put("key", it.key).put("kind", it.kind).put("title", it.title).put("poster", it.poster).put("url", it.url)
                .put("positionMs", it.positionMs).put("durationMs", it.durationMs).put("updatedAt", it.updatedAt)
                .put("seriesKey", it.seriesKey).put("playlistId", it.playlistId).put("finished", it.finished)
        }))
        o.put("state", store.exportState())
        o.toString(2)
    }

    suspend fun importBackup(text: String): Int = withContext(Dispatchers.IO) {
        val o = JSONObject(text)
        if (o.optString("app") != "Orbit Player") throw IOException("This is not an Orbit Player backup file")
        val pls = o.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until pls.length()) {
            val j = pls.getJSONObject(i)
            db.playlists().upsert(
                PlaylistEntity(
                    id = j.optString("id"), name = j.optString("name"), type = j.optString("type"), server = j.optString("server"),
                    username = j.optString("username"), password = j.optString("password"), url = j.optString("url"),
                    epgUrl = j.optString("epgUrl"), includeVod = j.optBoolean("includeVod", true), includeSeries = j.optBoolean("includeSeries", true),
                    sort = j.optInt("sort"),
                ),
            )
        }
        o.optJSONArray("favorites")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { db.user().addFavorite(FavoriteEntity(it.optString("key"), it.optString("kind"), it.optLong("addedAt"))) }
        }
        o.optJSONArray("progress")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let {
                db.user().saveProgress(
                    ProgressEntity(
                        it.optString("key"), it.optString("kind"), it.optString("title"), it.optString("poster"), it.optString("url"),
                        it.optLong("positionMs"), it.optLong("durationMs"), it.optLong("updatedAt"), it.optString("seriesKey"),
                        it.optString("playlistId"), it.optBoolean("finished"),
                    ),
                )
            }
        }
        o.optJSONObject("state")?.let { store.importState(it) }
        if (store.activePlaylistId.isBlank()) store.activePlaylistId = db.playlists().all().firstOrNull()?.id ?: ""
        pls.length()
    }
}
