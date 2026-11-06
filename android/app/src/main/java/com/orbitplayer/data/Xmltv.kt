package com.orbitplayer.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class XmltvProgram(val channel: String, val start: Long, val stop: Long, val title: String, val desc: String)

/** Čitač XMLTV TV vodiča. Radi u strimu, pa podržava i vodiče od više stotina MB. */
object XmltvParser {

    /** Raspakuje gzip ako je fajl kompresovan (prepoznaje se po prva dva bajta). */
    fun maybeGunzip(input: InputStream): InputStream {
        val bis = BufferedInputStream(input, 64 * 1024)
        bis.mark(4)
        val b1 = bis.read()
        val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis, 64 * 1024) else bis
    }

    /**
     * @param wanted kanali koje lista ima (tvg-id malim slovima); ostali se preskaču
     * @param from/to prozor vremena koji čuvamo
     */
    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long, onProgram: (XmltvProgram) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(maybeGunzip(input), null)
        // XMLTV povremeno koristi „display-name” za id — mapa display-name → id
        val nameToId = HashMap<String, String>()
        var currentChannelId: String? = null

        var event = parser.eventType
        var inProgramme = false
        var channel = ""
        var start = 0L
        var stop = 0L
        var title = StringBuilder()
        var desc = StringBuilder()
        var field = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "channel" -> currentChannelId = parser.getAttributeValue(null, "id")?.lowercase()
                    "display-name" -> if (currentChannelId != null) field = "display-name"
                    "programme" -> {
                        val ch = (parser.getAttributeValue(null, "channel") ?: "").lowercase()
                        channel = ch
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        inProgramme = (ch in wanted || nameToId[ch]?.let { it in wanted } == true) && stop > from && start < to
                        title = StringBuilder()
                        desc = StringBuilder()
                    }
                    "title" -> if (inProgramme && title.isEmpty()) field = "title"
                    "desc" -> if (inProgramme && desc.isEmpty()) field = "desc"
                }
                XmlPullParser.TEXT -> when (field) {
                    "title" -> title.append(parser.text)
                    "desc" -> desc.append(parser.text)
                    "display-name" -> currentChannelId?.let { id -> parser.text?.trim()?.lowercase()?.let { if (it.isNotEmpty()) nameToId[it] = id } }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc", "display-name" -> field = ""
                    "channel" -> currentChannelId = null
                    "programme" -> {
                        if (inProgramme && start > 0 && stop > start) {
                            val id = if (channel in wanted) channel else nameToId[channel] ?: channel
                            onProgram(XmltvProgram(id, start, stop, title.toString().trim(), desc.toString().trim().take(1500)))
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }
    }

    private val formats = object : ThreadLocal<List<SimpleDateFormat>>() {
        override fun initialValue(): List<SimpleDateFormat> = listOf(
            SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
            SimpleDateFormat("yyyyMMddHHmmssZ", Locale.US),
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        )
    }

    fun parseTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val s = raw.trim()
        for (f in formats.get()!!) {
            runCatching { return f.parse(s)!!.time }
        }
        return 0
    }
}
