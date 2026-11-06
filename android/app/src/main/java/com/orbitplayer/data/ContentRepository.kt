package com.orbitplayer.data

import android.content.Context
import androidx.room.withTransaction
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.AppSettings
import com.orbitplayer.core.JsonUtil
import com.orbitplayer.core.JsonUtil.objects
import com.orbitplayer.core.JsonUtil.str
import com.orbitplayer.core.Net
import com.orbitplayer.core.Prefs
import com.orbitplayer.data.db.AppDatabase
import com.orbitplayer.data.db.CategoryEntity
import com.orbitplayer.data.db.ChannelEntity
import com.orbitplayer.data.db.PlaylistEntity
import com.orbitplayer.data.db.ProgramEntity
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
import java.io.InputStreamReader
import java.util.UUID

/** Stanje osvežavanja jedne liste, za prikaz u aplikaciji */
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

data class SeriesDetail(val plot: String, val cover: String, val genre: String, val cast: String, val rating: String, val seasons: Map<Int, List<Episode>>)

data class VodDetail(val plot: String, val genre: String, val cast: String, val director: String, val duration: String, val year: String, val backdrop: String, val trailer: String)

class ContentRepository(private val context: Context, val db: AppDatabase, private val prefs: Prefs) {

    private val _settings = MutableStateFlow(computeSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _sync = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val syncStatus: StateFlow<Map<String, SyncStatus>> = _sync.asStateFlow()

    private val syncLock = Mutex()
    private val seriesCache = HashMap<String, SeriesDetail>()

    val playlists = db.playlists().observeAll()

    // ---------------- podešavanja ----------------

    fun serverConfigJson(): JSONObject? = prefs.serverConfig.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun computeSettings(): AppSettings {
        val server = serverConfigJson()?.optJSONObject("settings") ?: JSONObject()
        return AppSettings.from(JsonUtil.merge(server, prefs.localSettingsJson()))
    }

    fun refreshSettings() {
        _settings.value = computeSettings()
    }

    fun setLocalSetting(group: String, key: String, value: Any?) {
        prefs.setLocalSetting(group, key, value)
        refreshSettings()
    }

    // ---------------- liste ----------------

    /** Primenjuje liste koje je poslao server (sa sajta ili iz admin panela). */
    suspend fun applyServerPlaylists(arr: JSONArray) = withContext(Dispatchers.IO) {
        val incoming = arr.objects().mapIndexed { i, o ->
            PlaylistEntity(
                id = o.str("id"),
                name = o.str("name"),
                type = o.str("type"),
                server = o.str("server"),
                username = o.str("username"),
                password = o.str("password"),
                url = o.str("url"),
                epgUrl = o.str("epgUrl"),
                source = "server",
                sort = i,
                updatedAt = o.str("updatedAt"),
            )
        }
        val existing = db.playlists().all().filter { it.source == "server" }.associateBy { it.id }
        val changed = ArrayList<PlaylistEntity>()
        for (p in incoming) {
            val old = existing[p.id]
            if (old == null || old.updatedAt != p.updatedAt || old.server != p.server || old.url != p.url || old.password != p.password) {
                db.playlists().upsert(p.copy(lastSyncAt = 0, lastEpgAt = 0, serverTimezone = old?.serverTimezone ?: ""))
                changed += p
            } else if (old.sort != p.sort || old.name != p.name) {
                db.playlists().upsert(old.copy(sort = p.sort, name = p.name))
            }
        }
        val ids = incoming.map { it.id }.toSet()
        for (old in existing.values) if (old.id !in ids) removePlaylist(old.id)
        val active = prefs.activePlaylistId
        if (active.isBlank() || db.playlists().get(active) == null) {
            prefs.activePlaylistId = db.playlists().all().firstOrNull()?.id ?: ""
        }
        changed
    }

    suspend fun addLocalPlaylist(name: String, type: String, server: String, username: String, password: String, url: String, epgUrl: String): PlaylistEntity {
        val p = PlaylistEntity(
            id = "local-" + UUID.randomUUID().toString(),
            name = name.ifBlank { "Moja lista" },
            type = type,
            server = normalizeServer(server),
            username = username.trim(),
            password = password,
            url = url.trim(),
            epgUrl = epgUrl.trim(),
            source = "local",
        )
        if (type == "xtream") XtreamClient(p.server, p.username, p.password).let { withContext(Dispatchers.IO) { it.account() } }
        db.playlists().upsert(p)
        if (prefs.activePlaylistId.isBlank()) prefs.activePlaylistId = p.id
        return p
    }

    suspend fun removePlaylist(id: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            clearContent(id)
            db.epg().clear(id)
            db.playlists().delete(id)
        }
        if (prefs.activePlaylistId == id) prefs.activePlaylistId = db.playlists().all().firstOrNull()?.id ?: ""
    }

    suspend fun clearAllServerData() = withContext(Dispatchers.IO) {
        for (p in db.playlists().all()) if (p.source == "server") removePlaylist(p.id)
    }

    private suspend fun clearContent(pid: String) {
        val c = db.content()
        c.clearChannels(pid)
        c.clearVod(pid)
        c.clearSeries(pid)
        c.clearCategories(pid, "live")
        c.clearCategories(pid, "vod")
        c.clearCategories(pid, "series")
    }

    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.replace(Regex("/(player_api|get|xmltv)\\.php.*$"), "")
        return s.trimEnd('/')
    }

    fun xtream(p: PlaylistEntity) = XtreamClient(p.server, p.username, p.password)

    // ---------------- osvežavanje sadržaja ----------------

    private fun status(id: String, s: SyncStatus) = _sync.update { it + (id to s) }

    /** Osvežava liste kojima je isteklo vreme (ili sve ako je force). */
    suspend fun syncAll(force: Boolean = false) {
        val s = settings.value
        for (p in db.playlists().all()) {
            val stale = System.currentTimeMillis() - p.lastSyncAt > s.playlistRefreshHours * 3600_000L
            if (force || stale || p.lastSyncAt == 0L) syncPlaylist(p.id)
            val epgStale = System.currentTimeMillis() - (db.playlists().get(p.id)?.lastEpgAt ?: 0) > s.epgRefreshHours * 3600_000L
            if (force || epgStale) syncEpg(p.id)
        }
    }

    suspend fun syncPlaylist(id: String) = withContext(Dispatchers.IO) {
        syncLock.withLock {
            val p = db.playlists().get(id) ?: return@withLock
            status(id, SyncStatus(running = true, step = "Učitavam listu…"))
            try {
                if (p.type == "xtream") syncXtream(p) else syncM3u(p)
                db.playlists().markSynced(id, System.currentTimeMillis(), "")
                seriesCache.clear()
                status(id, SyncStatus(doneAt = System.currentTimeMillis()))
            } catch (e: Exception) {
                AppLog.e("Osvežavanje liste ${p.name} nije uspelo", e, "sync")
                db.playlists().markSynced(id, p.lastSyncAt, e.message ?: "Greška")
                status(id, SyncStatus(error = e.message ?: "Greška pri učitavanju liste"))
            }
        }
    }

    private fun isAdult(name: String): Boolean {
        val n = name.lowercase()
        return settings.value.adultKeywords.any { it.isNotEmpty() && n.contains(it) }
    }

    private suspend fun syncXtream(p: PlaylistEntity) {
        val x = xtream(p)
        val acc = x.account()
        acc.optJSONObject("server_info")?.optString("timezone")?.takeIf { it.isNotBlank() }?.let { db.playlists().setTimezone(p.id, it) }

        for (kind in listOf("live", "vod", "series")) {
            status(p.id, SyncStatus(running = true, step = when (kind) { "live" -> "Učitavam kanale…"; "vod" -> "Učitavam filmove…"; else -> "Učitavam serije…" }))
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
                            adult = cat in adultCats,
                        )
                    }
                    db.withTransaction {
                        db.content().clearSeries(p.id)
                        db.content().clearCategories(p.id, kind)
                        db.content().insertCategories(catEntities)
                        list.chunked(1000).forEach { db.content().insertSeries(it) }
                    }
                }
            }
        }
    }

    private suspend fun syncM3u(p: PlaylistEntity) {
        val channels = ArrayList<ChannelEntity>()
        val vods = ArrayList<VodEntity>()
        val liveGroups = LinkedHashMap<String, Boolean>()
        val vodGroups = LinkedHashMap<String, Boolean>()
        val res = Net.iptv.newCall(Request.Builder().url(p.url).build()).execute()
        var headerEpg = ""
        res.use { r ->
            if (!r.isSuccessful) throw IOException("Lista nije dostupna (HTTP ${r.code})")
            val reader = BufferedReader(InputStreamReader(r.body!!.byteStream(), Charsets.UTF_8), 64 * 1024)
            headerEpg = M3uParser.parse(reader) { e ->
                val id = Integer.toHexString((e.url + "|" + e.name).hashCode())
                val adult = isAdult(e.group)
                if (M3uParser.isVod(e)) {
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
        }
        if (channels.isEmpty() && vods.isEmpty()) throw IOException("Lista je prazna ili nije M3U")
        if (p.epgUrl.isBlank() && headerEpg.isNotBlank()) db.playlists().upsert(p.copy(epgUrl = headerEpg))
        db.withTransaction {
            clearContent(p.id)
            db.content().insertCategories(liveGroups.entries.mapIndexed { i, (g, a) -> CategoryEntity("${p.id}:live:$g", p.id, "live", g, g, i, a) })
            db.content().insertCategories(vodGroups.entries.mapIndexed { i, (g, a) -> CategoryEntity("${p.id}:vod:$g", p.id, "vod", g, g, i, a) })
            channels.chunked(1000).forEach { db.content().insertChannels(it) }
            vods.chunked(1000).forEach { db.content().insertVod(it) }
        }
    }

    suspend fun syncEpg(id: String) = withContext(Dispatchers.IO) {
        val p = db.playlists().get(id) ?: return@withContext
        val url = p.epgUrl.ifBlank { if (p.type == "xtream") xtream(p).xmltvUrl() else "" }
        if (url.isBlank()) return@withContext
        val keys = db.content().epgKeys(id)
        if (keys.isEmpty()) return@withContext
        val wanted = keys.map { it.substringAfter(':') }.toHashSet()
        val now = System.currentTimeMillis()
        val from = now - settings.value.epgDaysBack * 86_400_000L - 3600_000L
        val to = now + 8 * 86_400_000L
        status(id, SyncStatus(running = true, step = "Učitavam TV vodič…"))
        try {
            val res = Net.iptv.newCall(Request.Builder().url(url).build()).execute()
            res.use { r ->
                if (!r.isSuccessful) throw IOException("TV vodič nije dostupan (HTTP ${r.code})")
                db.epg().clear(id)
                val batch = ArrayList<ProgramEntity>(2000)
                val pending = ArrayList<List<ProgramEntity>>()
                XmltvParser.parse(r.body!!.byteStream(), wanted, from, to) { pr ->
                    batch += ProgramEntity(epgKey = "$id:${pr.channel}", playlistId = id, start = pr.start, stop = pr.stop, title = pr.title, description = pr.desc)
                    if (batch.size >= 2000) {
                        pending += ArrayList(batch)
                        batch.clear()
                    }
                }
                if (batch.isNotEmpty()) pending += batch
                for (b in pending) db.epg().insert(b)
            }
            db.epg().deleteOld(now - (settings.value.epgDaysBack + 1) * 86_400_000L)
            db.playlists().markEpg(id, System.currentTimeMillis())
            status(id, SyncStatus(doneAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            AppLog.w("TV vodič za ${p.name} nije učitan", e)
            status(id, SyncStatus(error = "TV vodič: ${e.message}"))
        }
    }

    // ---------------- adrese za puštanje ----------------

    suspend fun channelUrl(ch: ChannelEntity): String {
        if (ch.url.isNotBlank()) return ch.url
        val p = db.playlists().get(ch.playlistId) ?: return ""
        return xtream(p).liveUrl(ch.streamId, prefs.liveFormat)
    }

    /** Adresa snimka iz arhive za emisiju koja je već prošla */
    suspend fun catchupUrl(ch: ChannelEntity, prog: ProgramEntity): String? {
        if (!ch.archive) return null
        val p = db.playlists().get(ch.playlistId) ?: return null
        val durMin = ((prog.stop - prog.start) / 60_000).toInt().coerceAtLeast(1)
        if (p.type == "xtream") return xtream(p).timeshiftUrl(ch.streamId, prog.start, durMin, p.serverTimezone)
        if (ch.catchupSource.isNotBlank()) {
            val startSec = prog.start / 1000
            val endSec = prog.stop / 1000
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
        // podrazumevani „shift” format
        val sep = if (ch.url.contains('?')) "&" else "?"
        return "${ch.url}${sep}utc=${prog.start / 1000}&lutc=${System.currentTimeMillis() / 1000}"
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

    // ---------------- detalji ----------------

    suspend fun seriesDetail(s: SeriesEntity): SeriesDetail = withContext(Dispatchers.IO) {
        seriesCache[s.key]?.let { return@withContext it }
        val p = db.playlists().get(s.playlistId) ?: throw IOException("Lista nije pronađena")
        val json = xtream(p).seriesInfo(s.seriesId)
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, List<Episode>>()
        val eps = json.opt("episodes")
        fun addSeason(key: String, arr: JSONArray) {
            val list = arr.objects().map { e ->
                val ei = e.optJSONObject("info") ?: JSONObject()
                Episode(
                    id = e.str("id"),
                    season = e.optInt("season", key.toIntOrNull() ?: 1),
                    number = e.optInt("episode_num", 0),
                    title = e.str("title"),
                    ext = e.str("container_extension").ifBlank { "mp4" },
                    plot = ei.str("plot"),
                    image = ei.str("movie_image"),
                    durationSec = ei.optInt("duration_secs", 0),
                )
            }.sortedBy { it.number }
            seasons[key.toIntOrNull() ?: list.firstOrNull()?.season ?: 1] = list
        }
        when (eps) {
            is JSONObject -> eps.keys().forEach { k -> eps.optJSONArray(k)?.let { addSeason(k, it) } }
            is JSONArray -> {
                // ređi format: niz nizova
                for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { addSeason((i + 1).toString(), it) }
            }
        }
        val d = SeriesDetail(
            plot = info.str("plot").ifBlank { s.plot },
            cover = info.str("cover").ifBlank { s.cover },
            genre = info.str("genre"),
            cast = info.str("cast"),
            rating = info.str("rating").ifBlank { s.rating },
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
            val backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: ""
            VodDetail(
                plot = info.str("plot").ifBlank { info.str("description") },
                genre = info.str("genre"),
                cast = info.str("cast").ifBlank { info.str("actors") },
                director = info.str("director"),
                duration = info.str("duration"),
                year = info.str("releasedate").take(4).ifBlank { info.str("year") },
                backdrop = backdrop,
                trailer = info.str("youtube_trailer"),
            )
        }.getOrNull()
    }
}
