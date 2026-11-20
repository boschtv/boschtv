package nl.boschtv.tv.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import nl.boschtv.tv.R
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Language
import nl.boschtv.tv.data.Sort

/**
 * The app's texts in the TV's language, for code outside the screens (home rows, status messages,
 * errors). Screens use stringResource. The texts themselves are in res/values/strings.xml (English)
 * and res/values-<language>/strings.xml (translations).
 */
object Texts {
    private lateinit var res: Resources

    fun init(resources: Resources) { res = resources }

    fun get(@StringRes id: Int, vararg args: Any): String = res.getString(id, *args)
    fun plural(@PluralsRes id: Int, n: Int): String = res.getQuantityString(id, n, n)
}

/** Genre names as shown, in the TV's language. */
fun Genre.title(): String = Texts.get(when (this) {
    Genre.ACTION -> R.string.genre_ACTION
    Genre.ADVENTURE -> R.string.genre_ADVENTURE
    Genre.COMEDY -> R.string.genre_COMEDY
    Genre.DRAMA -> R.string.genre_DRAMA
    Genre.THRILLER -> R.string.genre_THRILLER
    Genre.CRIME -> R.string.genre_CRIME
    Genre.ROMANCE -> R.string.genre_ROMANCE
    Genre.SCIFI -> R.string.genre_SCIFI
    Genre.FANTASY -> R.string.genre_FANTASY
    Genre.HORROR -> R.string.genre_HORROR
    Genre.MYSTERY -> R.string.genre_MYSTERY
    Genre.FAMILY -> R.string.genre_FAMILY
    Genre.KIDS -> R.string.genre_KIDS
    Genre.ANIMATION -> R.string.genre_ANIMATION
    Genre.ANIME -> R.string.genre_ANIME
    Genre.DOCUMENTARY -> R.string.genre_DOCUMENTARY
    Genre.HISTORY -> R.string.genre_HISTORY
    Genre.WAR -> R.string.genre_WAR
    Genre.WESTERN -> R.string.genre_WESTERN
    Genre.MUSIC -> R.string.genre_MUSIC
    Genre.REALITY -> R.string.genre_REALITY
    Genre.SPORT -> R.string.genre_SPORT
})

/** "Comedy, Romance": the first few genres of a title. */
fun genreLine(mask: Long, max: Int = 3): String = Genre.of(mask).take(max).joinToString(", ") { it.title() }

/** Language names as shown; the ones that cannot be chosen keep their English name. */
fun Language.title(): String = when (this) {
    Language.DUTCH -> R.string.lang_DUTCH
    Language.FLEMISH -> R.string.lang_FLEMISH
    Language.ENGLISH -> R.string.lang_ENGLISH
    Language.GERMAN -> R.string.lang_GERMAN
    Language.FRENCH -> R.string.lang_FRENCH
    Language.SPANISH -> R.string.lang_SPANISH
    Language.ITALIAN -> R.string.lang_ITALIAN
    Language.PORTUGUESE -> R.string.lang_PORTUGUESE
    Language.POLISH -> R.string.lang_POLISH
    Language.TURKISH -> R.string.lang_TURKISH
    Language.ARABIC -> R.string.lang_ARABIC
    else -> null
}?.let { Texts.get(it) } ?: label

fun Sort.title(): String = Texts.get(when (this) {
    Sort.NEW -> R.string.sort_new
    Sort.RATING -> R.string.sort_rating
    Sort.YEAR -> R.string.sort_year
    Sort.AZ -> R.string.sort_az
    Sort.TOP -> R.string.sort_top
})

/** Version buttons on a film: stored as short tokens ("en", "dub", "4K"), shown in the TV's language. */
fun versionLabel(token: String): String = when (token) {
    "original", "Origineel" -> Texts.get(R.string.version_original)
    "en", "Engels", "Engels gesproken" -> Texts.get(R.string.version_english)
    "dub", "Nederlands gesproken" -> Texts.get(R.string.version_dubbed)
    "other", "Andere versie" -> Texts.get(R.string.version_other)
    else -> token   // quality names (4K, Dolby Vision, HEVC) read the same everywhere
}
