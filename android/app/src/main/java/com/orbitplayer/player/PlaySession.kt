package com.orbitplayer.player

import android.content.Context
import android.content.Intent
import com.orbitplayer.data.db.ChannelEntity

data class SubtitleSource(val uri: String, val label: String, val language: String = "", val mime: String = "")

/** Šta se pušta */
data class PlayItem(
    val kind: String, // live | vod | episode | catchup | local
    val title: String,
    val url: String = "", // za live se računa iz kanala
    val poster: String = "",
    val channel: ChannelEntity? = null,
    val progressKey: String = "", // ključ za „nastavi gledanje”
    val seriesKey: String = "",
    val playlistId: String = "",
    val subtitles: List<SubtitleSource> = emptyList(),
    val downloadId: Long = -1,
    val subtitle: String = "", // drugi red naslova (npr. emisija ili S01E02)
)

/**
 * Red za puštanje. Za kanale: lista kanala iz grupe (za prebacivanje gore/dole).
 * Za epizode: sve epizode sezone (za automatski prelaz na sledeću).
 */
object PlaySession {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0

    val current: PlayItem? get() = items.getOrNull(index)

    fun start(context: Context, items: List<PlayItem>, index: Int) {
        this.items = items
        this.index = index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun play(context: Context, item: PlayItem) = start(context, listOf(item), 0)
}
