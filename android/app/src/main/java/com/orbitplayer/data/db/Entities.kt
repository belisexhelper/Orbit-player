package com.orbitplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lista (izvor): Xtream nalog ili M3U adresa. source = "server" (sa panela/sajta) ili "local" (uneto na uređaju). */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // xtream | m3u
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val epgUrl: String = "",
    val source: String = "local",
    val sort: Int = 0,
    val lastSyncAt: Long = 0,
    val lastEpgAt: Long = 0,
    val lastError: String = "",
    /** Vremenska zona servera (Xtream server_info.timezone) — za catch-up */
    val serverTimezone: String = "",
    val updatedAt: String = "",
)

@Entity(tableName = "categories", indices = [Index("playlistId", "kind")])
data class CategoryEntity(
    @PrimaryKey val key: String, // playlistId:kind:categoryId
    val playlistId: String,
    val kind: String, // live | vod | series
    val categoryId: String,
    val name: String,
    val sort: Int,
    val adult: Boolean = false,
)

@Entity(tableName = "channels", indices = [Index("playlistId", "categoryId"), Index("epgKey"), Index("num")])
data class ChannelEntity(
    @PrimaryKey val key: String, // playlistId:streamId
    val playlistId: String,
    val streamId: String,
    val num: Int,
    val name: String,
    val logo: String,
    val categoryId: String,
    val url: String, // za M3U pun URL; za Xtream prazno (gradi se po potrebi)
    val epgKey: String, // playlistId:tvg-id (malim slovima)
    val archive: Boolean,
    val archiveDays: Int,
    val catchupSource: String = "",
    val adult: Boolean = false,
)

@Entity(tableName = "vod", indices = [Index("playlistId", "categoryId"), Index("name")])
data class VodEntity(
    @PrimaryKey val key: String,
    val playlistId: String,
    val streamId: String,
    val name: String,
    val poster: String,
    val categoryId: String,
    val rating: String,
    val ext: String,
    val url: String,
    val added: Long,
    val adult: Boolean = false,
)

@Entity(tableName = "series", indices = [Index("playlistId", "categoryId"), Index("name")])
data class SeriesEntity(
    @PrimaryKey val key: String,
    val playlistId: String,
    val seriesId: String,
    val name: String,
    val cover: String,
    val categoryId: String,
    val plot: String,
    val rating: String,
    val adult: Boolean = false,
)

@Entity(tableName = "epg", indices = [Index("epgKey", "start"), Index("stop")])
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgKey: String,
    val playlistId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String, // ključ kanala/filma/serije
    val kind: String,
    val addedAt: Long,
)

/** Pozicija gledanja za „nastavi gledanje” */
@Entity(tableName = "progress", indices = [Index("updatedAt")])
data class ProgressEntity(
    @PrimaryKey val key: String, // vod:<key> ili ep:<playlistId>:<episodeId>
    val kind: String, // vod | episode
    val title: String,
    val poster: String,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesKey: String = "",
    val playlistId: String = "",
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentKey: String,
    val kind: String, // vod | episode
    val title: String,
    val poster: String,
    val sourceUrl: String,
    val filePath: String,
    val subtitlePaths: String = "", // putanje odvojene sa \n
    val systemId: Long = -1,
    val status: String = "queued", // queued | running | done | failed | paused
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val error: String = "",
)
