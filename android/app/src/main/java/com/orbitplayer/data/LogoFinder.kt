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
 * Channel logos for channels whose playlist has none (or a broken one), from the public
 * iptv-org channel database. Channels are matched by their guide id, else by name, preferring the
 * country of the channel's group (SRBIJA → RS, HRVATSKA → HR …). Results are kept per channel name.
 */
object LogoFinder {
    private const val BASE = "https://raw.githubusercontent.com/iptv-org/database/master/data"
    private const val MAX_AGE_MS = 7 * 86_400_000L

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
                val signature = playlists.joinToString("|") { "${it.id}:${it.lastSyncAt}:${it.liveCount}" }
                val sigFile = File(context.filesDir, "logo-fallbacks.sig")
                val dbFile = File(File(context.filesDir, "logodb"), "channels.csv")
                val fresh = dbFile.exists() && System.currentTimeMillis() - dbFile.lastModified() < MAX_AGE_MS
                if (fresh && sigFile.exists() && sigFile.readText() == signature && logos.isNotEmpty()) return@withContext
                val dir = File(context.filesDir, "logodb").apply { mkdirs() }
                val channelsCsv = download(File(dir, "channels.csv"), "$BASE/channels.csv") ?: return@withContext
                val logosCsv = download(File(dir, "logos.csv"), "$BASE/logos.csv") ?: return@withContext
                val index = Index.build(channelsCsv, logosCsv)
                val result = HashMap<String, String>()
                for (p in playlists) {
                    val groupNames = db.content().categories(p.id, "live").associate { it.categoryId to it.name }
                    val chans = db.content().channels(p.id)
                    // 1st pass: candidates; countries of clear matches vote for the playlist's main region
                    val found = chans.map { ch -> ch to index.candidates(ch.epgKey.substringAfter(':', ""), ch.name, groupNames[ch.categoryId] ?: "") }
                    val votes = HashMap<String, Int>()
                    for ((_, m) in found) {
                        val countries = m.cands.map { it.second }.toSet()
                        val c = m.hint?.takeIf { it in countries } ?: countries.singleOrNull()
                        if (c != null) votes[c] = (votes[c] ?: 0) + 1
                    }
                    val ranked = votes.entries.sortedByDescending { it.value }.map { it.key }
                    // 2nd pass: pick by the channel's own country, else the playlist's most common one
                    for ((ch, m) in found) {
                        val url = m.direct ?: m.cands.let { c ->
                            if (c.isEmpty()) null
                            else (c.firstOrNull { it.second == m.hint } ?: ranked.firstNotNullOfOrNull { r -> c.firstOrNull { it.second == r } } ?: c.first())
                                .let { index.logo(it.first) }
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

    private fun download(target: File, url: String): File? {
        if (target.exists() && System.currentTimeMillis() - target.lastModified() < MAX_AGE_MS && target.length() > 10_000) return target
        return runCatching {
            Net.iptv.newCall(Request.Builder().url(url).build()).execute().use { r ->
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
        private val byName: Map<String, List<Pair<String, String>>>, // normalized name → (id, country)
        private val logoOf: Map<String, String>, // id → logo
    ) {
        class Match(val direct: String?, val cands: List<Pair<String, String>>, val hint: String?)

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
            fun build(channels: File, logos: File): Index {
                val byId = HashMap<String, String>()
                val byName = HashMap<String, MutableList<Pair<String, String>>>()
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
                            byName.getOrPut(v) { ArrayList(2) }.add(id to country)
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
                return Index(byId, byName, best.mapValues { it.value.second })
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
