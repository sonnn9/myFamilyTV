package com.familytv.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Indexed video. [scanToken] marks rows seen by the latest scan; older rows are removed after it. */
@Entity(tableName = "videos", indices = [Index("sourceKey")])
data class VideoEntity(
    @PrimaryKey val id: String,
    val sourceKey: String,
    val relativePath: String,
    val title: String,
    val uri: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** >0 known, 0 not read yet, -1 could not be read. */
    val durationMs: Long,
    val sidecarUri: String?,
    /** Comma-separated config folder ids / audience keys covering this file. */
    val folderIds: String,
    val audiences: String,
    val scanToken: Long,
)

@Entity(tableName = "progress", indices = [Index("sourceKey")])
data class ProgressEntity(
    @PrimaryKey val videoId: String,
    val sourceKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
)

@Entity(tableName = "favorites", indices = [Index("sourceKey")])
data class FavoriteEntity(
    @PrimaryKey val videoId: String,
    val sourceKey: String,
    val addedAt: Long,
)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM videos WHERE sourceKey = :sourceKey")
    abstract fun observeVideos(sourceKey: String): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE sourceKey = :sourceKey")
    abstract suspend fun videos(sourceKey: String): List<VideoEntity>

    @Upsert
    abstract suspend fun upsertVideos(videos: List<VideoEntity>)

    @Query("DELETE FROM videos WHERE sourceKey = :sourceKey AND scanToken != :scanToken")
    abstract suspend fun deleteNotInScan(sourceKey: String, scanToken: Long): Int

    @Query("DELETE FROM progress WHERE sourceKey = :sourceKey AND videoId NOT IN (SELECT id FROM videos WHERE sourceKey = :sourceKey)")
    abstract suspend fun deleteStaleProgress(sourceKey: String): Int

    @Query("DELETE FROM favorites WHERE sourceKey = :sourceKey AND videoId NOT IN (SELECT id FROM videos WHERE sourceKey = :sourceKey)")
    abstract suspend fun deleteStaleFavorites(sourceKey: String): Int

    @Query("DELETE FROM videos WHERE sourceKey != :sourceKey")
    abstract suspend fun deleteOtherSourceVideos(sourceKey: String)

    @Query("UPDATE videos SET durationMs = :durationMs WHERE id = :id")
    abstract suspend fun setDuration(id: String, durationMs: Long)

    /** Applies a finished scan atomically: upsert what was found, drop what disappeared and
     *  every progress/favorite row that now points to nothing. */
    @Transaction
    open suspend fun applyScan(sourceKey: String, scanToken: Long, videos: List<VideoEntity>): Int {
        videos.chunked(500).forEach { upsertVideos(it) }
        val removed = deleteNotInScan(sourceKey, scanToken)
        deleteStaleProgress(sourceKey)
        deleteStaleFavorites(sourceKey)
        return removed
    }

    @Transaction
    open suspend fun applyDurations(durations: Map<String, Long>) {
        durations.forEach { (id, ms) -> setDuration(id, ms) }
    }

    @Query("SELECT * FROM progress WHERE sourceKey = :sourceKey")
    abstract fun observeProgress(sourceKey: String): Flow<List<ProgressEntity>>

    @Upsert
    abstract suspend fun upsertProgress(progress: ProgressEntity)

    @Query("DELETE FROM progress WHERE sourceKey = :sourceKey")
    abstract suspend fun clearProgress(sourceKey: String)

    @Query("SELECT * FROM favorites WHERE sourceKey = :sourceKey")
    abstract fun observeFavorites(sourceKey: String): Flow<List<FavoriteEntity>>

    @Upsert
    abstract suspend fun upsertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE videoId = :videoId")
    abstract suspend fun deleteFavorite(videoId: String)
}

@Database(
    entities = [VideoEntity::class, ProgressEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "familytv.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
