package com.orbitplayer.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A source: Xtream Codes account or M3U address/file */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // xtream | m3u
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val url: String = "", // M3U address or content:// / file path of a local file
    val epgUrl: String = "",
    val includeVod: Boolean = true,
    val includeSeries: Boolean = true,
    val sort: Int = 0,
    val lastSyncAt: Long = 0,
    val lastEpgAt: Long = 0,
    val lastError: String = "",
    val serverTimezone: String = "",
    val expiresAt: Long = 0, // 0 = unknown / never
    val maxConnections: Int = 0,
    val accountStatus: String = "",
    val liveCount: Int = 0,
    val vodCount: Int = 0,
    val seriesCount: Int = 0,
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
    val url: String, // M3U: full address; Xtream: empty (built on demand)
    val epgKey: String, // playlistId:tvg-id (lower case)
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
    @ColumnInfo(defaultValue = "0") val added: Long = 0,
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
    @PrimaryKey val key: String, // channel / movie / series key
    val kind: String, // live | vod | series
    val addedAt: Long,
)

/** Watch position for "Continue watching" */
@Entity(tableName = "progress", indices = [Index("updatedAt")])
data class ProgressEntity(
    @PrimaryKey val key: String, // vod:<key> or ep:<playlistId>:<episodeId>
    val kind: String, // vod | episode
    val title: String,
    val poster: String,
    val url: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val seriesKey: String = "",
    val playlistId: String = "",
    val finished: Boolean = false,
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
    val subtitlePaths: String = "", // paths separated by \n
    val systemId: Long = -1,
    val status: String = "queued", // queued | running | done | failed | paused
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val error: String = "",
)
