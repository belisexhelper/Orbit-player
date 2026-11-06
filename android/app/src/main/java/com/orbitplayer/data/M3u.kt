package com.orbitplayer.data

import java.io.BufferedReader

/** Jedna stavka iz M3U liste */
data class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String,
    val logo: String,
    val group: String,
    val catchup: String,
    val catchupDays: Int,
    val catchupSource: String,
    val chno: Int?,
)

object M3uParser {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val videoExt = Regex("""\.(mp4|mkv|avi|mov|wmv|m4v|webm)(\?.*)?$""", RegexOption.IGNORE_CASE)

    /** EPG adresa iz zaglavlja (#EXTM3U url-tvg="...") */
    fun headerEpg(firstLine: String): String =
        attrRegex.findAll(firstLine).firstOrNull { it.groupValues[1].lowercase() in setOf("url-tvg", "x-tvg-url") }
            ?.groupValues?.get(2)?.split(',')?.firstOrNull()?.trim() ?: ""

    /**
     * Čita listu red po red i poziva [onEntry] za svaku stavku.
     * Vraća EPG adresu iz zaglavlja ako postoji.
     */
    fun parse(reader: BufferedReader, onEntry: (M3uEntry) -> Unit): String {
        var epg = ""
        var info: String? = null
        var group: String? = null
        var first = true
        while (true) {
            val raw = reader.readLine() ?: break
            val line = (if (first) raw.removePrefix("﻿") else raw).trim()
            first = false
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> epg = headerEpg(line)
                line.startsWith("#EXTINF") -> {
                    info = line
                    group = null
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // ostale direktive (#EXTVLCOPT, #KODIPROP...) ignorišemo
                info != null -> {
                    onEntry(entry(info, line, group))
                    info = null
                }
            }
        }
        return epg
    }

    private fun entry(info: String, url: String, extGroup: String?): M3uEntry {
        val attrs = HashMap<String, String>()
        attrRegex.findAll(info).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
        // naziv je posle poslednjeg zareza koji nije unutar navodnika
        val name = run {
            var inQuote = false
            var idx = -1
            for (i in info.indices) {
                val c = info[i]
                if (c == '"') inQuote = !inQuote
                if (c == ',' && !inQuote) idx = i
            }
            if (idx >= 0) info.substring(idx + 1).trim() else ""
        }.ifBlank { attrs["tvg-name"] ?: "Bez naziva" }
        return M3uEntry(
            name = name,
            url = url,
            tvgId = attrs["tvg-id"] ?: "",
            logo = attrs["tvg-logo"] ?: "",
            group = attrs["group-title"] ?: extGroup ?: "Ostalo",
            catchup = attrs["catchup"] ?: attrs["catchup-type"] ?: (if (attrs.containsKey("timeshift")) "default" else ""),
            catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.toIntOrNull() ?: 0,
            catchupSource = attrs["catchup-source"] ?: "",
            chno = (attrs["tvg-chno"] ?: attrs["channel-number"])?.toIntOrNull(),
        )
    }

    /** Da li je stavka film/epizoda (VOD), a ne kanal uživo */
    fun isVod(e: M3uEntry): Boolean =
        videoExt.containsMatchIn(e.url) || e.url.contains("/movie/") || e.url.contains("/series/")
}
