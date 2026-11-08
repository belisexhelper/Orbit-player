package com.orbitplayer.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.orbitplayer.core.AppLog
import com.orbitplayer.core.Net
import com.orbitplayer.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * Channel logos for channels whose playlist has none (or a broken one). Sources, best first:
 *  1. the TV guide's own channel icons (matched by guide id)
 *  2. the tv-logo/tv-logos collection on GitHub (good quality, many ex-YU channels)
 *  3. the iptv-org channel database
 * Name matches prefer the country of the channel's group (SRBIJA → RS, HRVATSKA → HR …), else the
 * playlist's main region. Results are kept per channel name.
 */
object LogoFinder {
    private const val BASE = "https://raw.githubusercontent.com/iptv-org/database/master/data"
    private const val MAX_AGE_MS = 7 * 86_400_000L
    private const val TVLOGOS_TREE = "https://api.github.com/repos/tv-logo/tv-logos/git/trees/main?recursive=1"
    private const val TVLOGOS_RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/"

    /** Guide icons of a playlist (tvg-id → address), saved when the guide is loaded */
    fun saveGuideIcons(context: Context, playlistId: String, icons: Map<String, String>) {
        if (icons.isEmpty()) return
        runCatching {
            File(context.filesDir, "guide-icons-${playlistId.hashCode()}.json").writeText(JSONObject(icons as Map<*, *>).toString())
            File(context.filesDir, "logo-fallbacks.sig").delete() // match again with the new icons
        }
    }

    private fun guideIcons(context: Context, playlistId: String): Map<String, String> = runCatching {
        val o = JSONObject(File(context.filesDir, "guide-icons-${playlistId.hashCode()}.json").readText())
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrElse { emptyMap() }

    /** channel name (as in the playlist, and cleaned) → logo address. Read by ChannelLogo. */
    var logos by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    private val lock = Mutex()

    fun forName(name: String): String? = logos[key(name)]

    private fun key(name: String) = name.trim().lowercase()

    /** Loads the saved results (fast, at start-up) */
    suspend fun loadSaved(context: Context) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, "logo-fallbacks.json")
        if (!f.exists()) return@withContext
        runCatching {
            val o = JSONObject(f.readText())
            logos = o.keys().asSequence().associateWith { o.getString(it) }
        }
    }

    /** Matches all channels of all playlists against the logo database and saves the results */
    suspend fun refresh(context: Context, db: AppDatabase) = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val playlists = db.playlists().all()
                val signature = "v${com.orbitplayer.BuildConfig.VERSION_CODE}|" + playlists.joinToString("|") { "${it.id}:${it.lastSyncAt}:${it.liveCount}" }
                val sigFile = File(context.filesDir, "logo-fallbacks.sig")
                val dbFile = File(File(context.filesDir, "logodb"), "channels.csv")
                val fresh = dbFile.exists() && System.currentTimeMillis() - dbFile.lastModified() < MAX_AGE_MS
                if (fresh && sigFile.exists() && sigFile.readText() == signature && logos.isNotEmpty()) return@withContext
                val dir = File(context.filesDir, "logodb").apply { mkdirs() }
                val channelsCsv = download(File(dir, "channels.csv"), "$BASE/channels.csv") ?: return@withContext
                val logosCsv = download(File(dir, "logos.csv"), "$BASE/logos.csv") ?: return@withContext
                val tvTree = download(File(dir, "tvlogos.json"), TVLOGOS_TREE, accept = "application/vnd.github+json")
                val index = Index.build(channelsCsv, logosCsv, tvTree)
                val result = HashMap<String, String>()
                for (p in playlists) {
                    val groupNames = db.content().categories(p.id, "live").associate { it.categoryId to it.name }
                    val chans = db.content().channels(p.id)
                    val guide = guideIcons(context, p.id)
                    // 1st pass: candidates; countries of clear matches vote for the playlist's main region
                    val found = chans.map { ch -> ch to index.candidates(ch.epgKey.substringAfter(':', ""), ch.name, groupNames[ch.categoryId] ?: "") }
                    val votes = HashMap<String, Int>()
                    for ((_, m) in found) {
                        val countries = m.cands.map { it.second }.toSet()
                        val c = m.hint?.takeIf { it in countries } ?: countries.singleOrNull()
                        if (c != null) votes[c] = (votes[c] ?: 0) + 1
                    }
                    val ranked = votes.entries.sortedByDescending { it.value }.map { it.key }
                    // 2nd pass: the guide's icon; else by the channel's own country, else the playlist's most
                    // common one; among equals the better source
                    for ((ch, m) in found) {
                        val url = guide[ch.epgKey.substringAfter(':', "")] ?: m.direct ?: m.cands.let { c ->
                            if (c.isEmpty()) null
                            else {
                                val hints = hintCodes(m.hint)
                                c.minByOrNull { e ->
                                    val countryScore = hints.indexOf(e.second).takeIf { it >= 0 }
                                        ?: ranked.indexOf(e.second).takeIf { it >= 0 }?.plus(10) ?: 99
                                    countryScore * 10 + e.third
                                }?.let { index.logo(it.first) }
                            }
                        } ?: continue
                        result[key(ch.name)] = url
                        result[key(com.orbitplayer.core.ChannelNames.clean(ch.name))] = url
                    }
                }
                logos = result
                File(context.filesDir, "logo-fallbacks.json").writeText(JSONObject(result as Map<*, *>).toString())
                sigFile.writeText(signature)
                AppLog.w("Logo finder: ${result.size} names matched")
            } catch (t: Throwable) {
                AppLog.w("Logo finder failed: ${t.message}")
            }
        }
    }

    private fun download(target: File, url: String, accept: String? = null): File? {
        if (target.exists() && System.currentTimeMillis() - target.lastModified() < MAX_AGE_MS && target.length() > 10_000) return target
        return runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "OrbitPlayer").apply { if (accept != null) header("Accept", accept) }.build()
            Net.iptv.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val tmp = File(target.path + ".tmp")
                r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
            target
        }.getOrElse {
            AppLog.w("Logo database download failed: ${it.message}")
            target.takeIf { f -> f.exists() } // an older copy is better than nothing
        }
    }

    private class Index(
        private val byId: Map<String, String>, // lowercase id → id
        private val byName: Map<String, List<Triple<String, String, Int>>>, // normalized name → (id, country, source rank)
        private val logoOf: Map<String, String>, // id → logo
    ) {
        class Match(val direct: String?, val cands: List<Triple<String, String, Int>>, val hint: String?)

        fun logo(id: String): String? = logoOf[id]

        fun candidates(tvgId: String, name: String, group: String): Match {
            byId[tvgId.lowercase()]?.let { id -> logoOf[id]?.let { return Match(it, emptyList(), null) } }
            val hint = countryHint(name, group)
            for (n in nameVariants(name)) {
                val withLogo = (byName[n] ?: continue).filter { logoOf.containsKey(it.first) }
                if (withLogo.isNotEmpty()) return Match(null, withLogo, hint)
            }
            return Match(null, emptyList(), hint)
        }

        companion object {
            fun build(channels: File, logos: File, tvTree: File?): Index {
                val byId = HashMap<String, String>()
                val byName = HashMap<String, MutableList<Triple<String, String, Int>>>()
                val logoOf = HashMap<String, String>()
                // tv-logos: countries/<country>/<name>-<cc>.png
                if (tvTree != null) runCatching {
                    val tree = JSONObject(tvTree.readText()).getJSONArray("tree")
                    for (i in 0 until tree.length()) {
                        val path = tree.getJSONObject(i).optString("path")
                        if (!path.startsWith("countries/") || !path.endsWith(".png", true)) continue
                        val parts = path.split('/')
                        if (parts.size != 3) continue
                        val country = folderCountry(parts[1])
                        var file = parts[2].substringBeforeLast('.').lowercase()
                        if ("mosaic" in file) continue
                        file = file.replace(Regex("-[a-z]{2,3}$"), "") // country suffix
                        val icon = file.endsWith("-icon") || "-icon-" in file
                        file = file.replace("-icon", "").replace("-hz", "")
                        val id = "tvlogos:$path"
                        logoOf[id] = TVLOGOS_RAW + path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                        for (v in nameVariants(file.replace('-', ' '))) {
                            if (v.length < 2) continue
                            byName.getOrPut(v) { ArrayList(2) }.add(Triple(id, country, if (icon) 1 else 0))
                        }
                    }
                }
                channels.bufferedReader().useLines { lines ->
                    var header: List<String>? = null
                    for (line in lines) {
                        val f = parseCsv(line)
                        if (header == null) { header = f; continue }
                        val h = header!!
                        fun col(n: String) = h.indexOf(n).let { i -> if (i in f.indices) f[i] else "" }
                        val id = col("id")
                        if (id.isBlank() || col("closed").isNotBlank()) continue
                        val country = col("country")
                        byId[id.lowercase()] = id
                        val names = listOf(col("name")) + col("alt_names").split(';')
                        for (nm in names) for (v in nameVariants(nm)) {
                            if (v.length < 2) continue
                            byName.getOrPut(v) { ArrayList(2) }.add(Triple(id, country, 2))
                        }
                    }
                }
                // best logo per channel: main feed, in use, not SVG, widest
                val best = HashMap<String, Pair<Int, String>>()
                logos.bufferedReader().useLines { lines ->
                    var header: List<String>? = null
                    for (line in lines) {
                        val f = parseCsv(line)
                        if (header == null) { header = f; continue }
                        val h = header!!
                        fun col(n: String) = h.indexOf(n).let { i -> if (i in f.indices) f[i] else "" }
                        val id = col("channel")
                        val url = col("url")
                        if (id.isBlank() || url.isBlank()) continue
                        val format = col("format").uppercase()
                        if (format == "SVG" || url.endsWith(".svg", true)) continue
                        var score = col("width").toIntOrNull() ?: 0
                        if (col("feed").isNotBlank()) score -= 10_000
                        if (col("in_use").equals("FALSE", true)) score -= 20_000
                        val cur = best[id]
                        if (cur == null || score > cur.first) best[id] = score to url
                    }
                }
                for ((id, b) in best) logoOf[id] = b.second
                return Index(byId, byName, logoOf)
            }
        }
    }

    // ---------------- names ----------------

    private val quality = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|1080p?|720p?|50fps|60fps|hdr|raw|backup|l2|l3|vip|\+1)\b""")
    private val prefixSep = Regex("""^\s*[\[(|]?\s*([a-z\-]{2,6})\s*[\])|:]+\s*""")

    /** Normalized forms of a channel name: without country prefix, quality tags, accents and spaces */
    fun nameVariants(raw: String): List<String> {
        var n = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        n = n.replace("đ", "dj")
        repeat(2) { n = prefixSep.replace(n, "") }
        n = quality.replace(n, " ")
        val base = n.replace(Regex("[^a-z0-9]+"), "")
        if (base.isEmpty()) return emptyList()
        val out = linkedSetOf(base)
        if (base.endsWith("tv") && base.length > 4) out += base.removeSuffix("tv")
        if (base.startsWith("tv") && base.length > 4) out += base.removePrefix("tv")
        if (!base.endsWith("tv")) out += base + "tv"
        // providers write "Arena 1 Premium" and "Arena Sport 1 Premium" for the same channel
        for (v in out.toList()) {
            if ("arenasport" in v) out += v.replace("arenasport", "arena")
            else if ("arena" in v) out += v.replace("arena", "arenasport")
        }
        return out.toList()
    }

    private val countryWords = listOf(
        "RS" to listOf("srbija", "serbia", "srb", "rs"),
        "HR" to listOf("hrvatska", "croatia", "hr", "cro"),
        "BA" to listOf("bosna", "bosnia", "bih", "ba"),
        "ME" to listOf("crna gora", "montenegro", "cg", "mne", "me"),
        "SI" to listOf("slovenija", "slovenia", "slo", "si"),
        "MK" to listOf("makedonija", "macedonia", "mk"),
        "UK" to listOf("uk", "united kingdom", "gb"),
        "US" to listOf("usa", "us"),
        "DE" to listOf("germany", "deutschland", "de"),
    )

    /** Where to look when the channel's own country has no logo (ex-YU channels are often listed under Serbia/Croatia) */
    private fun hintCodes(hint: String?): List<String> = when (hint) {
        null -> emptyList()
        "BA" -> listOf("BA", "RS", "HR")
        "ME" -> listOf("ME", "RS")
        "MK" -> listOf("MK", "RS")
        "SI" -> listOf("SI", "HR")
        else -> listOf(hint)
    }

    private fun folderCountry(folder: String): String = when (folder) {
        "serbia" -> "RS"
        "croatia" -> "HR"
        "slovenia" -> "SI"
        "united-kingdom" -> "UK"
        "united-states" -> "US"
        "germany" -> "DE"
        "austria" -> "AT"
        "switzerland" -> "CH"
        "albania" -> "AL"
        "bulgaria" -> "BG"
        "hungary" -> "HU"
        "romania" -> "RO"
        "greece" -> "GR"
        "turkey" -> "TR"
        else -> folder.uppercase()
    }

    private fun countryHint(name: String, group: String): String? {
        val prefix = Regex("""^\s*[\[(|]?\s*([A-Za-z]{2,6})\s*[\])|:]""").find(name)?.groupValues?.get(1)?.lowercase()
        val g = group.lowercase()
        for ((code, words) in countryWords) {
            if (prefix != null && prefix in words) return code
        }
        for ((code, words) in countryWords) {
            if (words.any { w -> w.length > 3 && g.contains(w) }) return code
        }
        return null
    }

    private fun parseCsv(line: String): List<String> {
        val out = ArrayList<String>(12)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
