package nl.boschtv.tv.data

import nl.boschtv.tv.ui.title
import nl.boschtv.tv.R
import nl.boschtv.tv.ui.Texts
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.boschtv.tv.core.Flags
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.data.db.KIND_MOVIE
import nl.boschtv.tv.data.db.KIND_SERIES
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import kotlin.math.exp

enum class RowStyle { POSTER, WIDE, SERVICES, TOP }

@Immutable
data class HomeRow(
    val id: String,
    val title: String,
    val cards: List<Card>,
    val style: RowStyle = RowStyle.POSTER,
    val services: List<Service> = emptyList(),
    /** Opens the full grid for this row ("See all"). */
    val more: TitleQuery? = null,
)

private data class Season(val id: String, @androidx.annotation.StringRes val title: Int, val from: MonthDay, val to: MonthDay, val query: TitleQuery, val kidsOnly: Boolean = false)

/**
 * Decides what the home screen shows and in which order. Signals: what was watched, liked and saved;
 * the time of year; day and hour; what is new, in cinemas and in the provider's top list.
 */
class Recommender(private val catalog: Catalog, private val library: Library, private val trends: Trends) {

    /** This week's trending titles and their position, refreshed whenever the home rows are built. */
    @Volatile private var hot: Map<String, Int> = emptyMap()


    private val seasons = listOf(
        Season("sint", R.string.row_sinterklaas, MonthDay.of(11, 8), MonthDay.of(12, 5), TitleQuery(flags = Flags.SINTERKLAAS), kidsOnly = true),
        Season("kerst", R.string.row_christmas, MonthDay.of(11, 15), MonthDay.of(12, 31), TitleQuery(flags = Flags.CHRISTMAS)),
        Season("oudnieuw", R.string.row_new_year_eve, MonthDay.of(12, 27), MonthDay.of(12, 31), TitleQuery(genresAll = Genre.COMEDY.bit, sort = Sort.RATING, minRating = 6.5)),
        Season("nieuwjaar", R.string.row_new_year, MonthDay.of(1, 1), MonthDay.of(1, 7), TitleQuery(genresAny = Genre.COMEDY.bit or Genre.FAMILY.bit, sort = Sort.RATING, minRating = 7.0)),
        Season("valentijn", R.string.row_valentine, MonthDay.of(2, 7), MonthDay.of(2, 15), TitleQuery(genresAll = Genre.ROMANCE.bit, sort = Sort.RATING, minRating = 6.0)),
        Season("mei", R.string.row_remembrance, MonthDay.of(4, 27), MonthDay.of(5, 5), TitleQuery(genresAny = Genre.WAR.bit or Genre.HISTORY.bit, sort = Sort.RATING, minRating = 6.5)),
        Season("zomer", R.string.row_summer, MonthDay.of(7, 1), MonthDay.of(8, 31), TitleQuery(kind = KIND_MOVIE, genresAny = Genre.ADVENTURE.bit or Genre.COMEDY.bit, sort = Sort.RATING, minRating = 6.8)),
        Season("halloween", R.string.row_halloween, MonthDay.of(10, 15), MonthDay.of(11, 1), TitleQuery(genresAll = Genre.HORROR.bit, sort = Sort.RATING, minRating = 5.5)),
    )

    private val genrePrior = mapOf(
        Genre.ACTION to .55f, Genre.COMEDY to .55f, Genre.THRILLER to .5f, Genre.DRAMA to .45f, Genre.CRIME to .45f,
        Genre.FAMILY to .4f, Genre.SCIFI to .4f, Genre.DOCUMENTARY to .35f, Genre.ADVENTURE to .35f, Genre.ROMANCE to .3f,
        Genre.FANTASY to .3f, Genre.KIDS to .3f, Genre.HORROR to .25f, Genre.REALITY to .25f, Genre.ANIMATION to .2f,
        Genre.MYSTERY to .2f, Genre.HISTORY to .15f, Genre.WAR to .15f, Genre.MUSIC to .1f, Genre.ANIME to .1f,
        Genre.WESTERN to .05f, Genre.SPORT to .05f,
    )

    /** @param kind null = films and series mixed. @param service 0 = everything, else that streaming service only. */
    suspend fun home(kind: Int?, service: Long, kids: Boolean = false, now: LocalDateTime = LocalDateTime.now()): List<HomeRow> =
        withContext(Dispatchers.Default) {
            val taste = library.taste()
            // Kids mode: everything is restricted to titles from children's categories; elsewhere those are left out.
            val must = if (kids) Genre.KIDS.bit else 0L
            val base = TitleQuery(kind = kind, services = service, genresAll = must)
            val hub = service != 0L || kids
            val svcName = Service.of(service).firstOrNull()?.label
            val rows = ArrayList<HomeRow>()
            suspend fun add(id: String, title: String, q: TitleQuery, style: RowStyle = RowStyle.POSTER, min: Int = 5, hideWatched: Boolean = false, personal: Boolean = false) {
                // Personal rows take a wider pool and order it by what this household tends to watch.
                var cards = if (personal) rank(catalog.cards(q.copy(limit = q.limit * 3)), taste, now).take(q.limit) else catalog.cards(q)
                if (hideWatched) cards = cards.filter { it.key !in taste.watched }
                cards = cards.filter { it.key !in taste.disliked }
                if (cards.size >= min) rows += HomeRow(id, title, cards, style, more = q.copy(limit = 60))
            }

            // Inside a streaming service: only what you were watching from that service.
            if (!kids) {
                val cont = library.continueWatching().filter { (kind == null || it.kind == kind) && (service == 0L || it.services and service != 0L) }
                if (cont.isNotEmpty()) rows += HomeRow("continue", Texts.get(R.string.row_continue), cont, RowStyle.WIDE)
            }

            // ---- what is hot right now (TMDB), matched against what this line actually has
            val trending = trends.keys("trend")
            hot = trending.withIndex().associate { (i, k) -> k to i }
            suspend fun ranked(keys: List<String>, limit: Int, only: Int? = kind, filter: (Card) -> Boolean = { true }): List<Card> {
                val cards = catalog.byKeys(keys.take(400))
                return keys.asSequence().mapNotNull { cards[it] }
                    .filter { (only == null || it.kind == only) && it.key !in taste.disliked && filter(it) }
                    .filter { if (kids) it.genres and Genre.KIDS.bit != 0L else it.genres and Genre.KIDS.bit == 0L }
                    .take(limit).toList()
            }
            if (trending.isNotEmpty()) {
                // Films and series each get their own top 10 (one list on the Films / Series tabs).
                for ((k, word) in listOf(KIND_MOVIE to "films", KIND_SERIES to "series")) {
                    if (kind != null && kind != k) continue
                    val top = ranked(trending, 10, only = k) { service == 0L || it.services and service != 0L }
                    if (top.size >= 5) rows += HomeRow("top10-$word", if (svcName != null) Texts.get(if (k == KIND_MOVIE) R.string.row_top_movies_on else R.string.row_top_series_on, top.size, svcName) else Texts.get(if (k == KIND_MOVIE) R.string.row_top_movies else R.string.row_top_series, top.size), top, RowStyle.TOP)
                }
            }

            recommended(taste, base, now)?.let { rows += it }

            if (!hub) {
                val mine = library.myList().filter { kind == null || it.kind == kind }
                if (mine.isNotEmpty()) rows += HomeRow("mylist", Texts.get(R.string.row_my_list), mine)
            }

            // Inside a service page: what is new and popular on that service in the Netherlands, per TMDB.
            if (service != 0L) Service.of(service).firstOrNull()?.let { s ->
                val list = ranked(trends.keys("svc:${s.name}"), 40)
                if (list.size >= 6) rows += HomeRow("svcpop", Texts.get(R.string.row_new_popular_on, s.label), list)
            }

            val newLabel = if (svcName != null) Texts.get(R.string.row_new_on, svcName) else Texts.get(R.string.row_new_added)
            // Films: "new" is simply when it was added, so an older film that just arrived still counts.
            // Series: the provider's date also moves with every extra episode, so there a recent release
            // year is required; otherwise long-running shows would look new forever.
            val recent = now.year - 1
            if (kind != KIND_SERIES) add("new-movies", if (kind == null) (if (svcName != null) Texts.get(R.string.row_new_movies_on, svcName) else Texts.get(R.string.row_new_movies)) else newLabel, base.copy(kind = KIND_MOVIE, limit = 40))
            if (kind != KIND_MOVIE) add("new-series", Texts.get(R.string.row_new_series), base.copy(kind = KIND_SERIES, minYear = recent, limit = 40))

            for (s in seasons) if (inSeason(now.toLocalDate(), s) && (kids || !s.kidsOnly)) {
                add(s.id, Texts.get(s.title), s.query.copy(kind = kind ?: s.query.kind, services = service, genresAll = s.query.genresAll or must, limit = 40), personal = true)
            }

            if (!hub && kind == null) {
                val services = catalog.services()
                if (services.size >= 2) rows += HomeRow("services", Texts.get(R.string.row_services), emptyList(), RowStyle.SERVICES, services)
            }

            // A general row: not repeated inside a streaming service's own page or the kids tab.
            if (kind != KIND_SERIES && !hub) add("cinema", Texts.get(R.string.row_cinema), base.copy(kind = KIND_MOVIE, flags = Flags.CINEMA, minYear = now.year - 1, sort = Sort.YEAR, limit = 40), min = 4)
            add("top", if (svcName != null) Texts.get(R.string.row_popular_on, svcName) else Texts.get(R.string.row_popular), base.copy(sort = Sort.TOP, dutchFirst = false, limit = 30), RowStyle.TOP, min = 6)
            add("year", Texts.get(R.string.row_best_of, now.year), base.copy(year = now.year, sort = Sort.RATING, minRating = 6.5, limit = 40), hideWatched = true)

            for ((i, seed) in taste.recent.take(2).withIndex()) {
                val cards = similar(seed, kind, service).filter { it.key !in taste.watched && it.key !in taste.disliked }
                if (cards.size >= 6) rows += HomeRow("because$i", Texts.get(R.string.row_because, seed.title), cards.take(30))
            }

            if (!hub) {
                val services = catalog.services().sortedByDescending { taste.service[it.ordinal] * 2f - it.ordinal * 0.05f }.take(5)
                for (s in services) {
                    val list = ranked(trends.keys("svc:${s.name}"), 30)
                    if (list.size >= 8) rows += HomeRow("svc-${s.name}", Texts.get(R.string.row_new_popular_on, s.label), list)
                    else add("svc-${s.name}", Texts.get(R.string.row_new_on, s.label), base.copy(services = s.bit, minYear = now.year - 1, limit = 30), min = 6, personal = true)
                }
            }

            // Genre rows: taste first, nudged by the hour and day of the week.
            val weekendDay = now.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && now.hour in 7..17
            val late = now.hour >= 21 || now.hour < 3
            val ranked = Genre.entries.filter { it != Genre.SPORT }.sortedByDescending { g ->
                var s = (genrePrior[g] ?: 0f) + taste.genre[g.ordinal] * 1.2f
                if (late && g in setOf(Genre.THRILLER, Genre.HORROR, Genre.CRIME)) s += .2f
                if (weekendDay && g in setOf(Genre.FAMILY, Genre.KIDS, Genre.ANIMATION)) s += .3f
                s
            }
            for (g in ranked) if (g != Genre.KIDS) add("genre-${g.name}", g.title(), base.copy(genresAll = must or g.bit, limit = 40), personal = true)
            add("acclaimed", Texts.get(R.string.row_acclaimed), base.copy(sort = Sort.RATING, minRating = 7.8, limit = 40), hideWatched = true)
            if (!kids) add("genre-SPORT", Genre.SPORT.title(), base.copy(genresAll = Genre.SPORT.bit, limit = 30))
            rows.distinctBy { it.id }
        }

    private fun score(c: Card, taste: Taste, now: LocalDateTime): Float {
        val ageDays = ((System.currentTimeMillis() / 1000 - c.addedTs) / 86_400.0).coerceAtLeast(0.0)
        val jitter = ((c.key.hashCode() * 31 + now.dayOfYear) and 0xFF) / 255f * 0.3f
        return taste.genreScore(c.genres) * 1.4f + taste.serviceScore(c.services) * 0.4f +
            (c.rating / 10.0).toFloat() * 0.7f + exp(-ageDays / 120.0).toFloat() * 0.9f +
            // Trending this week counts most, more the higher it stands.
            (hot[c.key]?.let { 1.0f - it / 200f } ?: 0f) +
            // In the provider's current top list or in cinemas now: popular at this moment, not all-time.
            (if (c.flags and Flags.TOP != 0) 0.6f else 0f) + (if (c.flags and Flags.CINEMA != 0) 0.3f else 0f) +
            (if (c.lang == 0) 0.3f else 0f) + jitter
    }

    /** Orders a pool by taste, rating and freshness, with a small daily shuffle so rows do not go stale. */
    private fun rank(cards: List<Card>, taste: Taste, now: LocalDateTime): List<Card> =
        cards.filter { it.key !in taste.watched }.sortedByDescending { score(it, taste, now) }

    private fun inSeason(d: LocalDate, s: Season): Boolean {
        val md = MonthDay.from(d)
        return !md.isBefore(s.from) && !md.isAfter(s.to)
    }

    /** Personal row: scored on taste, freshness, rating and a daily shuffle so it never looks stale. */
    private suspend fun recommended(taste: Taste, base: TitleQuery, now: LocalDateTime): HomeRow? {
        if (!taste.hasHistory) return null
        val top = Genre.entries.sortedByDescending { taste.genre[it.ordinal] }.filter { taste.genre[it.ordinal] > 0.15f && it != Genre.KIDS }.take(4)
        if (top.isEmpty()) return null
        val pool = LinkedHashMap<String, Card>()
        for (g in top) {
            catalog.cards(base.copy(genresAll = base.genresAll or g.bit, limit = 70)).forEach { pool[it.key] = it }
            catalog.cards(base.copy(genresAll = base.genresAll or g.bit, sort = Sort.RATING, minRating = 6.5, limit = 70)).forEach { pool[it.key] = it }
        }
        catalog.cards(base.copy(limit = 80)).forEach { pool[it.key] = it }
        val nowSec = System.currentTimeMillis() / 1000
        val day = now.dayOfYear
        val picks = pool.values
            .filter { it.key !in taste.watched && it.key !in taste.disliked }
            .map { c ->
                val ageDays = ((nowSec - c.addedTs) / 86_400.0).coerceAtLeast(0.0)
                val jitter = ((c.key.hashCode() * 31 + day) and 0xFF) / 255f * 0.3f
                val score = taste.genreScore(c.genres) * 1.4f + taste.serviceScore(c.services) * 0.4f +
                    (c.rating / 10.0).toFloat() * 0.7f + exp(-ageDays / 120.0).toFloat() * 0.5f +
                    (if (c.lang == 0) 0.3f else 0f) + jitter
                c to score
            }
            .sortedByDescending { it.second }.take(36).map { it.first }
        return if (picks.size >= 6) HomeRow("foryou", Texts.get(R.string.row_for_you), picks) else null
    }

    /**
     * "More like this": TMDB's recommendations for the title, limited to what this line has, topped up
     * with our own genre-based picks when TMDB knows too few that are in the catalog.
     */
    suspend fun similar(seed: Card, kind: Int? = null, service: Long = 0): List<Card> {
        val seedIsKids = seed.genres and Genre.KIDS.bit != 0L
        val keys = trends.recommendations(seed.key)
        val found = catalog.byKeys(keys)
        val fromTmdb = keys.mapNotNull { found[it] }.filter {
            (kind == null || it.kind == kind) && (service == 0L || it.services and service != 0L) &&
                (seedIsKids || it.genres and Genre.KIDS.bit == 0L)
        }
        if (fromTmdb.size >= 12) return fromTmdb.take(30)
        val seen = fromTmdb.mapTo(HashSet()) { it.key }
        return (fromTmdb + ownSimilar(seed, kind, service).filter { it.key !in seen }).take(30)
    }

    /** Our own fallback: titles sharing the most genres with [seed], from the same era. */
    private suspend fun ownSimilar(seed: Card, kind: Int?, service: Long): List<Card> {
        if (seed.genres == 0L) return emptyList()
        val pool = catalog.cards(TitleQuery(kind = kind, services = service, genresAny = seed.genres, sort = Sort.RATING, minRating = 5.5, limit = 240)) +
            catalog.cards(TitleQuery(kind = kind, services = service, genresAny = seed.genres, limit = 120))
        return pool.asSequence().filter { it.key != seed.key }.distinctBy { it.key }
            .map { c ->
                val shared = java.lang.Long.bitCount(c.genres and seed.genres)
                val extra = java.lang.Long.bitCount(c.genres and seed.genres.inv())
                // Same era matters: a 2026 epic is more like other recent epics than like one from 1982.
                val gap = if (c.year != null && seed.year != null) kotlin.math.abs(c.year - seed.year).coerceAtMost(30) else 15
                val era = (1f - gap / 30f) * 2f
                val sameService = if (c.services and seed.services != 0L) .5f else 0f
                c to (shared * 2f - extra * 0.5f + era + sameService + (if (c.kind == seed.kind) 1f else 0f) +
                    (c.rating / 10).toFloat() + (if (c.lang == 0) .5f else 0f))
            }
            .sortedByDescending { it.second }.map { it.first }.take(30).toList()
    }
}
