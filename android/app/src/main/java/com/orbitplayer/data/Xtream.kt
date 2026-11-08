package com.orbitplayer.data

import android.util.JsonReader
import android.util.JsonToken
import com.orbitplayer.core.Net
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Klijent za Xtream Codes API (player_api.php). */
class XtreamClient(server: String, private val username: String, private val password: String) {
    val server: String = server.trimEnd('/')
    private val u = enc(username)
    private val p = enc(password)

    private fun api(action: String? = null, extra: String = ""): String =
        "$server/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "") + extra

    fun account(): JSONObject {
        val text = Net.getString(Net.iptv, api())
        val json = runCatching { JSONObject(text) }.getOrElse { throw IOException("Server nije vratio Xtream odgovor") }
        val ui = json.optJSONObject("user_info") ?: throw IOException("Neispravan odgovor servera")
        if (ui.optInt("auth", 0) != 1) throw IOException("Pogrešno korisničko ime ili lozinka")
        return json
    }

    /** Kategorije: lista parova (id, naziv) */
    fun categories(kind: String): List<Pair<String, String>> {
        val action = when (kind) {
            "live" -> "get_live_categories"
            "vod" -> "get_vod_categories"
            else -> "get_series_categories"
        }
        val out = ArrayList<Pair<String, String>>()
        streamArray(api(action)) { m -> out += (m["category_id"] ?: "") to (m["category_name"] ?: "") }
        return out
    }

    /** Čita velike liste bez učitavanja celog JSON-a u memoriju. */
    fun streams(kind: String, onItem: (Map<String, String>) -> Unit) {
        val action = when (kind) {
            "live" -> "get_live_streams"
            "vod" -> "get_vod_streams"
            else -> "get_series"
        }
        streamArray(api(action), onItem)
    }

    fun seriesInfo(seriesId: String): JSONObject = JSONObject(Net.getString(Net.iptv, api("get_series_info", "&series_id=${enc(seriesId)}")))

    fun vodInfo(vodId: String): JSONObject = JSONObject(Net.getString(Net.iptv, api("get_vod_info", "&vod_id=${enc(vodId)}")))

    fun liveUrl(streamId: String, format: String): String = "$server/live/$u/$p/$streamId.${if (format == "m3u8") "m3u8" else "ts"}"

    fun vodUrl(streamId: String, ext: String): String = "$server/movie/$u/$p/$streamId.${ext.ifBlank { "mp4" }}"

    fun episodeUrl(episodeId: String, ext: String): String = "$server/series/$u/$p/$episodeId.${ext.ifBlank { "mp4" }}"

    fun xmltvUrl(): String = "$server/xmltv.php?username=$u&password=$p"

    /**
     * Full guide of one channel (also the days still in the archive, which the XMLTV file often leaves out).
     * Titles and descriptions come base64 encoded. Returns (start, stop, title, description).
     */
    fun channelGuide(streamId: String): List<Array<Any>> {
        val json = JSONObject(Net.getString(Net.iptv, api("get_simple_data_table", "&stream_id=${enc(streamId)}")))
        val arr = json.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<Array<Any>>(arr.length())
        fun b64(s: String): String = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optString("start_timestamp").toLongOrNull()?.times(1000) ?: continue
            val stop = (o.optString("stop_timestamp").toLongOrNull() ?: o.optString("end_timestamp").toLongOrNull())?.times(1000) ?: continue
            if (stop <= start) continue
            out += arrayOf(start, stop, b64(o.optString("title")).trim(), b64(o.optString("description")).trim())
        }
        return out
    }

    /**
     * Snimak iz arhive (catch-up). Xtream očekuje početak u vremenskoj zoni servera.
     */
    fun timeshiftUrl(streamId: String, startMs: Long, durationMin: Int, serverTz: String): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        f.timeZone = if (serverTz.isNotBlank()) TimeZone.getTimeZone(serverTz) else TimeZone.getDefault()
        return "$server/timeshift/$u/$p/$durationMin/${f.format(Date(startMs))}/$streamId.ts"
    }

    private fun streamArray(url: String, onItem: (Map<String, String>) -> Unit) {
        val res = Net.iptv.newCall(Request.Builder().url(url).build()).execute()
        res.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: return
            JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                when (reader.peek()) {
                    JsonToken.BEGIN_ARRAY -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlatObject(reader)) else reader.skipValue()
                        }
                        reader.endArray()
                    }
                    JsonToken.BEGIN_OBJECT -> {
                        // neki serveri vraćaju objekat {"0": {...}, "1": {...}} ili {} kada je prazno
                        reader.beginObject()
                        while (reader.hasNext()) {
                            reader.nextName()
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlatObject(reader)) else reader.skipValue()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
        }
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** Čita objekat i zadržava samo jednostavne vrednosti (tekst, broj, da/ne). */
        fun readFlatObject(reader: JsonReader): Map<String, String> {
            val m = HashMap<String, String>(24)
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                when (reader.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> m[name] = reader.nextString()
                    JsonToken.BOOLEAN -> m[name] = if (reader.nextBoolean()) "1" else "0"
                    JsonToken.NULL -> reader.nextNull()
                    JsonToken.BEGIN_ARRAY -> {
                        // npr. backdrop_path: uzmi prvu tekst vrednost
                        reader.beginArray()
                        var first: String? = null
                        while (reader.hasNext()) {
                            if (first == null && reader.peek() == JsonToken.STRING) first = reader.nextString() else reader.skipValue()
                        }
                        reader.endArray()
                        if (first != null) m[name] = first
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return m
        }
    }
}
