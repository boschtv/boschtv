package nl.boschtv.tv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

const val ROW_COLUMNS =
    "`key`, kind, name, year, poster, COALESCE(dBackdrop, pBackdrop) AS backdrop, rating, genres, services, " +
        "flags, lang, addedTs, substr(COALESCE(lPlot, dPlot, pPlot), 1, 360) AS plot"

data class EnrichTarget(val key: String, val sourceId: Long)

@Dao
interface CatalogDao {
    @Upsert(entity = TitleEntity::class) suspend fun upsertTitles(items: List<TitleCore>)
    @Query("DELETE FROM titles WHERE gen != :gen") suspend fun pruneTitles(gen: Long)
    @Query("UPDATE titles SET genres = pGenres | dGenres") suspend fun refreshGenres()
    @Query("SELECT COUNT(*) FROM titles") suspend fun titleCount(): Int
    @Query("SELECT COUNT(DISTINCT gen) FROM titles") suspend fun generations(): Int
    @Query("UPDATE titles SET lPlot = :plot, lPlotLocale = :locale WHERE `key` = :key")
    suspend fun setLocalPlot(key: String, plot: String?, locale: String)
    /** Share of stored titles of [kind] without a poster, in percent; null when there are none yet. */
    @Query("SELECT CASE WHEN COUNT(*) = 0 THEN NULL ELSE SUM(CASE WHEN poster IS NULL OR poster = '' THEN 1 ELSE 0 END) * 100 / COUNT(*) END FROM titles WHERE kind = :kind")
    suspend fun missingPosterPercent(kind: Int): Int?

    @RawQuery suspend fun rows(query: SupportSQLiteQuery): List<TitleRow>
    @RawQuery suspend fun count(query: SupportSQLiteQuery): Int

    @Query("SELECT * FROM titles WHERE `key` = :key") suspend fun title(key: String): TitleEntity?
    @Query("SELECT $ROW_COLUMNS FROM titles WHERE `key` IN (:keys)") suspend fun rowsByKeys(keys: List<String>): List<TitleRow>
    @Query("SELECT services FROM titles WHERE services != 0") suspend fun serviceMasks(): List<Long>

    @Query("SELECT `key`, sourceId FROM titles WHERE kind = 0 AND enriched = 0 ORDER BY lang, addedTs DESC LIMIT :limit")
    suspend fun toEnrich(limit: Int): List<EnrichTarget>

    @Query(
        "UPDATE titles SET dPlot = COALESCE(:plot, dPlot), dBackdrop = COALESCE(:backdrop, dBackdrop), dGenres = :genres, " +
            "genres = pGenres | :genres, durationSecs = :durationSecs, actors = :actors, director = :director, " +
            "trailer = :trailer, enriched = 1, detailAt = :at WHERE `key` = :key"
    )
    suspend fun setDetail(
        key: String, plot: String?, backdrop: String?, genres: Long, durationSecs: Long,
        actors: String?, director: String?, trailer: String?, at: Long,
    )

    @Query("UPDATE titles SET enriched = 2 WHERE `key` = :key") suspend fun markEnrichFailed(key: String)
    @Query("UPDATE titles SET enriched = 0 WHERE enriched = 2") suspend fun retryFailedEnrichment()
    @Query("UPDATE titles SET detailAt = :at WHERE `key` = :key") suspend fun touchDetail(key: String, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertEpisodes(items: List<EpisodeEntity>)
    @Query("DELETE FROM episodes WHERE titleKey = :key") suspend fun deleteEpisodes(key: String)
    @Query("SELECT * FROM episodes WHERE titleKey = :key ORDER BY season, number") suspend fun episodes(key: String): List<EpisodeEntity>
    @Query("SELECT * FROM episodes WHERE id = :id") suspend fun episode(id: String): EpisodeEntity?
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertChannels(items: List<ChannelEntity>)
    @Query("DELETE FROM channels WHERE gen != :gen") suspend fun pruneChannels(gen: Long)
    @Query("DELETE FROM channel_groups") suspend fun clearGroups()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertGroups(items: List<ChannelGroupEntity>)

    @Query("SELECT * FROM channel_groups ORDER BY sort") suspend fun groups(): List<ChannelGroupEntity>
    @Query("SELECT * FROM channels WHERE groupId = :groupId ORDER BY num") suspend fun byGroup(groupId: String): List<ChannelEntity>
    @Query("SELECT * FROM channels ORDER BY sort") suspend fun all(): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: Long): ChannelEntity?
    @Query("SELECT COUNT(*) FROM channels") suspend fun count(): Int
    @Query("SELECT * FROM channels WHERE search LIKE '%' || :q || '%' ORDER BY sort LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT c.* FROM channels c JOIN favorites f ON f.channelId = c.id ORDER BY f.addedAt")
    suspend fun favorites(): List<ChannelEntity>
    @Query("SELECT channelId FROM favorites") fun favoriteIds(): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addFavorite(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE channelId = :id") suspend fun removeFavorite(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProgrammes(items: List<ProgrammeEntity>)
    @Query("DELETE FROM programmes WHERE channelId = :id") suspend fun clearProgrammes(id: Long)
    @Query("DELETE FROM programmes WHERE stop < :before") suspend fun pruneProgrammes(before: Long)
    @Query("SELECT * FROM programmes WHERE channelId = :id AND stop > :from ORDER BY start LIMIT :limit")
    suspend fun programmes(id: Long, from: Long, limit: Int): List<ProgrammeEntity>
}

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(p: ProgressEntity)
    @Query("SELECT * FROM progress WHERE contentId = :id") suspend fun progress(id: String): ProgressEntity?
    @Query("SELECT * FROM progress WHERE titleKey = :key") suspend fun progressOf(key: String): List<ProgressEntity>
    @Query("SELECT * FROM progress ORDER BY updatedAt DESC LIMIT :limit") suspend fun recentProgress(limit: Int): List<ProgressEntity>
    @Query("DELETE FROM progress WHERE titleKey = :key") suspend fun clearProgress(key: String)
    @Query("DELETE FROM progress") suspend fun clearAllProgress()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addToList(e: MyListEntity)
    @Query("DELETE FROM my_list WHERE titleKey = :key") suspend fun removeFromList(key: String)
    @Query("SELECT titleKey FROM my_list ORDER BY addedAt DESC") suspend fun myList(): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM my_list WHERE titleKey = :key)") suspend fun inList(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun rate(e: RatingEntity)
    @Query("DELETE FROM ratings WHERE titleKey = :key") suspend fun unrate(key: String)
    @Query("SELECT * FROM ratings") suspend fun ratings(): List<RatingEntity>
    @Query("SELECT * FROM ratings WHERE titleKey = :key") suspend fun rating(key: String): RatingEntity?
}

@Dao
interface TrendDao {
    @Query("DELETE FROM trending WHERE listId = :listId") suspend fun clear(listId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(items: List<TrendEntity>)
    @Query("SELECT titleKey FROM trending WHERE listId = :listId ORDER BY rank") suspend fun keys(listId: String): List<String>
}
