package nl.boschtv.tv.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One person on this TV. History, My List, thumbs and favourite channels belong to a profile, so each
 * person gets their own "Continue watching" and recommendations. The avatar is an emoji on one of the
 * gradients in `ui/Profiles.kt` ([color] is its index).
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatar: String,
    val color: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Playback position per profile and film or episode. Finished rows are kept: they feed the recommendations.
 *
 * Nothing in the key belongs to the IPTV provider (see [ContentIds]), so the history is still right after
 * a switch to another provider: the video itself is looked up in the catalog at the moment of playing.
 */
@Entity(
    tableName = "progress", primaryKeys = ["profileId", "contentId"],
    indices = [Index("profileId", "titleKey"), Index("profileId", "updatedAt")],
)
data class ProgressEntity(
    val profileId: Long,
    val contentId: String,                // ContentIds.movie / ContentIds.episode
    val titleKey: String,
    val season: Int?,                     // both set for an episode, both null for a film
    val number: Int?,
    val label: String?,                   // "S1 · A2 · Titel"
    val image: String?,
    val streamId: Long?,                  // chosen film version; only used while the catalog still has it
    val container: String?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
) {
    val isEpisode get() = season != null && number != null
}

@Entity(tableName = "my_list", primaryKeys = ["profileId", "titleKey"])
data class MyListEntity(val profileId: Long, val titleKey: String, val addedAt: Long)

/** Thumbs up / down, per profile. */
@Entity(tableName = "ratings", primaryKeys = ["profileId", "titleKey"])
data class RatingEntity(val profileId: Long, val titleKey: String, val liked: Boolean, val at: Long)

/**
 * A live channel starred by one profile, remembered by its name ([channel] is [ChannelEntity.search])
 * and not by the provider's number for it. [epgId] finds it again when another provider names it differently.
 */
@Entity(tableName = "favorites", primaryKeys = ["profileId", "channel"])
data class FavoriteEntity(val profileId: Long, val channel: String, val epgId: String?, val addedAt: Long)

/** How a film or episode is named in the history: the same on every provider. */
object ContentIds {
    fun movie(titleKey: String) = "m:$titleKey"
    fun episode(titleKey: String, season: Int, number: Int) = "e:$titleKey:$season:$number"
    fun of(e: EpisodeEntity) = episode(e.titleKey, e.season, e.number)

    /**
     * A row from before the history was provider-independent, when an episode was stored under the
     * provider's own id for it. Null when the row cannot be placed (an episode without season and number).
     */
    fun fromLegacy(contentId: String, titleKey: String, episodeId: String?, season: Int?, number: Int?): String? = when {
        episodeId == null -> if (contentId.startsWith("m:")) contentId else movie(titleKey)
        season != null && number != null -> episode(titleKey, season, number)
        else -> null
    }
}

/** Everything here belongs to one profile: every query takes its id. */
@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE profileId = :profile AND contentId = :id") suspend fun progress(profile: Long, id: String): ProgressEntity?
    @Query("SELECT * FROM progress WHERE profileId = :profile AND titleKey = :key") suspend fun progressOf(profile: Long, key: String): List<ProgressEntity>
    @Query("SELECT * FROM progress WHERE profileId = :profile ORDER BY updatedAt DESC LIMIT :limit") suspend fun recentProgress(profile: Long, limit: Int): List<ProgressEntity>
    @Query("DELETE FROM progress WHERE profileId = :profile AND titleKey = :key") suspend fun clearProgress(profile: Long, key: String)
    @Query("DELETE FROM progress WHERE profileId = :profile") suspend fun clearAllProgress(profile: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addToList(e: MyListEntity)
    @Query("DELETE FROM my_list WHERE profileId = :profile AND titleKey = :key") suspend fun removeFromList(profile: Long, key: String)
    @Query("SELECT titleKey FROM my_list WHERE profileId = :profile ORDER BY addedAt DESC") suspend fun myList(profile: Long): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE profileId = :profile AND titleKey = :key)") suspend fun inList(profile: Long, key: String): Boolean
    @Query("DELETE FROM my_list WHERE profileId = :profile") suspend fun clearList(profile: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rate(e: RatingEntity)
    @Query("DELETE FROM ratings WHERE profileId = :profile AND titleKey = :key") suspend fun unrate(profile: Long, key: String)
    @Query("SELECT * FROM ratings WHERE profileId = :profile") suspend fun ratings(profile: Long): List<RatingEntity>
    @Query("SELECT * FROM ratings WHERE profileId = :profile AND titleKey = :key") suspend fun rating(profile: Long, key: String): RatingEntity?
    @Query("DELETE FROM ratings WHERE profileId = :profile") suspend fun clearRatings(profile: Long)

    @Query("SELECT * FROM favorites WHERE profileId = :profile ORDER BY addedAt") suspend fun favorites(profile: Long): List<FavoriteEntity>
    @Query("SELECT * FROM favorites WHERE profileId = :profile ORDER BY addedAt") fun observeFavorites(profile: Long): Flow<List<FavoriteEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavorite(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE profileId = :profile AND channel = :channel") suspend fun removeFavorite(profile: Long, channel: String)
    @Query("DELETE FROM favorites WHERE profileId = :profile") suspend fun clearFavorites(profile: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt, id") fun observe(): Flow<List<ProfileEntity>>
    @Query("SELECT id FROM profiles") suspend fun ids(): List<Long>
    @Query("SELECT COUNT(*) FROM profiles") suspend fun count(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(p: ProfileEntity): Long
    @androidx.room.Update suspend fun update(p: ProfileEntity)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun delete(id: Long)
}

/**
 * What the people on this TV made themselves: profiles, history, My List, thumbs, favourite channels.
 *
 * Kept apart from the catalog ([AppDatabase]), which can be thrown away and fetched again at any time.
 * This one cannot: a change to these tables always comes with a written migration (the schema of every
 * version is in `app/schemas`), and nothing here is tied to the IPTV provider or cleared on logging out.
 */
@Database(
    entities = [ProfileEntity::class, ProgressEntity::class, MyListEntity::class, RatingEntity::class, FavoriteEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun profiles(): ProfileDao

    companion object {
        const val NAME = "user.db"

        fun create(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, NAME)
                .addCallback(object : Callback() {
                    // The very first time: bring along what earlier versions kept in the catalog database.
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        runCatching { LegacyImport.run(context.getDatabasePath(AppDatabase.NAME), db) }
                    }
                })
                .build()
    }
}
