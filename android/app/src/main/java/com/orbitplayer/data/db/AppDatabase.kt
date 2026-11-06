package com.orbitplayer.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Database(
    entities = [
        PlaylistEntity::class, CategoryEntity::class, ChannelEntity::class, VodEntity::class,
        SeriesEntity::class, ProgramEntity::class, FavoriteEntity::class, ProgressEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun content(): ContentDao
    abstract fun epg(): EpgDao
    abstract fun user(): UserDao
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "orbit.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY source DESC, sort, name")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY source DESC, sort, name")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE playlists SET lastSyncAt = :at, lastError = :error WHERE id = :id")
    suspend fun markSynced(id: String, at: Long, error: String)

    @Query("UPDATE playlists SET lastEpgAt = :at WHERE id = :id")
    suspend fun markEpg(id: String, at: Long)

    @Query("UPDATE playlists SET serverTimezone = :tz WHERE id = :id")
    suspend fun setTimezone(id: String, tz: String)
}

@Dao
interface ContentDao {
    // ---------- kategorije ----------
    @Query("SELECT * FROM categories WHERE playlistId = :pid AND kind = :kind ORDER BY sort")
    fun observeCategories(pid: String, kind: String): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(items: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE playlistId = :pid AND kind = :kind")
    suspend fun clearCategories(pid: String, kind: String)

    // ---------- kanali ----------
    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY num, name")
    fun observeChannels(pid: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :pid ORDER BY num, name")
    suspend fun channels(pid: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE `key` = :key")
    suspend fun channel(key: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND num = :num LIMIT 1")
    suspend fun channelByNumber(pid: String, num: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND name LIKE :q ORDER BY num LIMIT 1")
    suspend fun channelByName(pid: String, q: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND name LIKE :q ORDER BY num LIMIT 100")
    suspend fun searchChannels(pid: String, q: String): List<ChannelEntity>

    @Query("SELECT DISTINCT epgKey FROM channels WHERE playlistId = :pid AND epgKey != ''")
    suspend fun epgKeys(pid: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun clearChannels(pid: String)

    // ---------- filmovi ----------
    @Query("SELECT * FROM vod WHERE playlistId = :pid AND (:cat = '' OR categoryId = :cat) ORDER BY added DESC, name LIMIT :limit")
    fun observeVod(pid: String, cat: String, limit: Int): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod WHERE playlistId = :pid AND name LIKE :q ORDER BY name LIMIT 200")
    suspend fun searchVod(pid: String, q: String): List<VodEntity>

    @Query("SELECT * FROM vod WHERE `key` = :key")
    suspend fun vod(key: String): VodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVod(items: List<VodEntity>)

    @Query("DELETE FROM vod WHERE playlistId = :pid")
    suspend fun clearVod(pid: String)

    // ---------- serije ----------
    @Query("SELECT * FROM series WHERE playlistId = :pid AND (:cat = '' OR categoryId = :cat) ORDER BY name LIMIT :limit")
    fun observeSeries(pid: String, cat: String, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :pid AND name LIKE :q ORDER BY name LIMIT 200")
    suspend fun searchSeries(pid: String, q: String): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun series(key: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun clearSeries(pid: String)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg WHERE epgKey = :key AND stop > :from AND start < :to ORDER BY start")
    suspend fun programs(key: String, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM epg WHERE epgKey IN (:keys) AND stop > :from AND start < :to ORDER BY start")
    suspend fun programsFor(keys: List<String>, from: Long, to: Long): List<ProgramEntity>

    @Query("SELECT * FROM epg WHERE epgKey = :key AND start <= :now AND stop > :now LIMIT 1")
    suspend fun current(key: String, now: Long): ProgramEntity?

    @Insert
    suspend fun insert(items: List<ProgramEntity>)

    @Query("DELETE FROM epg WHERE playlistId = :pid")
    suspend fun clear(pid: String)

    @Query("DELETE FROM epg WHERE stop < :before")
    suspend fun deleteOld(before: Long)
}

@Dao
interface UserDao {
    @Query("SELECT * FROM favorites")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Upsert
    suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun removeFavorite(key: String)

    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT :limit")
    fun observeProgress(limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE `key` = :key")
    suspend fun progress(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE seriesKey = :seriesKey")
    suspend fun progressForSeries(seriesKey: String): List<ProgressEntity>

    @Upsert
    suspend fun saveProgress(p: ProgressEntity)

    @Query("DELETE FROM progress WHERE `key` = :key")
    suspend fun deleteProgress(key: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE contentKey = :key LIMIT 1")
    suspend fun byContent(key: String): DownloadEntity?

    @Insert
    suspend fun insert(d: DownloadEntity): Long

    @Upsert
    suspend fun update(d: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}
