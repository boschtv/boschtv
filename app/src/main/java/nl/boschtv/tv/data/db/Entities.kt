package nl.boschtv.tv.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val KIND_MOVIE = 0
const val KIND_SERIES = 1

/**
 * One film or series, de-duplicated across every provider category it appeared in. The provider's
 * own categories are reduced to [genres], [services] and [flags]; they are never shown as-is.
 */
@Entity(
    tableName = "titles",
    indices = [
        Index(value = ["lang", "addedTs"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index(value = ["lang", "rating"], orders = [Index.Order.ASC, Index.Order.DESC]),
        Index("addedTs"), Index("rating"), Index("topRank"), Index("search"), Index("gen"),
    ],
)
data class TitleEntity(
    @PrimaryKey val key: String,          // "m:t<tmdb>" | "m:n:<name>:<year>" | "s:t<tmdb>" | "s:n:<name>:<year>": the same on every provider
    val kind: Int,
    val sourceId: Long,                   // stream id (film) or series id of the preferred version
    val name: String,
    val search: String,
    val year: Int?,
    val poster: String?,
    val container: String?,
    val addedTs: Long,
    val rating: Double,
    val lang: Int,                        // 0 = Dutch/Flemish version available, 1 = English only
    val pGenres: Long,
    val services: Long,
    val flags: Int,
    val topRank: Int,                     // position in the provider's Top list, 0 = not in it
    val alts: String?,                    // other versions: "streamId;ext;label" per line
    val pPlot: String?,
    val pBackdrop: String?,
    val gen: Long,
    // ---- filled in later from the provider's detail call; untouched by catalog sync ----
    @ColumnInfo(defaultValue = "0") val genres: Long = 0,
    @ColumnInfo(defaultValue = "0") val dGenres: Long = 0,
    val dPlot: String? = null,
    // ---- the description in the viewer's language from TMDB, and which language that is ----
    val lPlot: String? = null,
    val lPlotLocale: String? = null,
    val dBackdrop: String? = null,
    @ColumnInfo(defaultValue = "0") val durationSecs: Long = 0,
    val actors: String? = null,
    val director: String? = null,
    val trailer: String? = null,
    @ColumnInfo(defaultValue = "0") val enriched: Int = 0,
    @ColumnInfo(defaultValue = "0") val detailAt: Long = 0,
)

/** The columns a catalog sync owns. Upserting this leaves the detail columns of [TitleEntity] alone. */
data class TitleCore(
    val key: String,
    val kind: Int,
    val sourceId: Long,
    val name: String,
    val search: String,
    val year: Int?,
    val poster: String?,
    val container: String?,
    val addedTs: Long,
    val rating: Double,
    val lang: Int,
    val pGenres: Long,
    val services: Long,
    val flags: Int,
    val topRank: Int,
    val alts: String?,
    val pPlot: String?,
    val pBackdrop: String?,
    val gen: Long,
)

/** Light projection used for every row, grid and hero. */
data class TitleRow(
    val key: String,
    val kind: Int,
    val name: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val genres: Long,
    val services: Long,
    val flags: Int,
    val lang: Int,
    val addedTs: Long,
    val plot: String?,
)

@Entity(tableName = "episodes", indices = [Index("titleKey")])
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val titleKey: String,
    val season: Int,
    val number: Int,
    val name: String,
    val container: String?,
    val durationSecs: Long,
    val image: String?,
    val plot: String?,
)

@Entity(tableName = "channels", indices = [Index("groupId", "num"), Index("search"), Index("gen")])
data class ChannelEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val search: String,
    val logo: String?,
    val groupId: String,
    val epgId: String?,
    val num: Int,                         // position inside its group, 1-based (NPO 1 = 1 …)
    val sort: Int,                        // global order
    val archive: Boolean,
    val gen: Long,
)

@Entity(tableName = "channel_groups")
data class ChannelGroupEntity(@PrimaryKey val id: String, val name: String, val sort: Int)

@Entity(tableName = "programmes", primaryKeys = ["channelId", "start"], indices = [Index("channelId", "stop")])
data class ProgrammeEntity(
    val channelId: Long,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/** A programme found in the guide, with the channel it is on. */
data class GuideHit(val channelId: Long, val channelName: String, val logo: String?, val start: Long, val stop: Long, val title: String)

/** Ranked lists from TMDB: "trend" (this week, everything) and "svc:<SERVICE>" (new and popular on a service in NL). */
@Entity(tableName = "trending", primaryKeys = ["listId", "rank"])
data class TrendEntity(val listId: String, val rank: Int, val titleKey: String)
