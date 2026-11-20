package nl.boschtv.tv

import nl.boschtv.tv.core.Categories
import nl.boschtv.tv.core.ContentPrefs
import nl.boschtv.tv.core.Flags
import nl.boschtv.tv.core.Genre
import nl.boschtv.tv.core.Lang
import nl.boschtv.tv.core.Language
import nl.boschtv.tv.core.ProviderProfile
import nl.boschtv.tv.core.Service
import nl.boschtv.tv.core.Tags
import nl.boschtv.tv.core.Titles
import nl.boschtv.tv.data.xtream.CredentialParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val prefs = ContentPrefs()

    @Test fun cleansProviderTitles() {
        Titles.clean("┃NL┃ Guarding Stars - 2026").let { assertEquals("Guarding Stars", it.name); assertEquals(2026, it.year) }
        Titles.clean("┃EN┃ All Hallows' Eve 2 (2015)").let { assertEquals("All Hallows' Eve 2", it.name); assertEquals(2015, it.year) }
        Titles.clean("┃NL┃ The Gorge 4K - 2025").let { assertEquals("The Gorge", it.name); assertEquals("4K", it.quality); assertEquals(2025, it.year) }
        Titles.clean("┃NL┃ Rumble (NL GESPROKEN)").let { assertEquals("Rumble", it.name); assertTrue(it.dubbed) }
        assertEquals("Marvel's The Punisher", Titles.clean("┃NL┃ Marvel's The Punisher HEVC").name)
        assertEquals("My Youth", Titles.clean("┃NL┃ My Youth [마이 유스]").name)
        assertEquals("Little House on the Prairie (Het kleine huis op de prairie)", Titles.clean("┃NL┃ Little House on the Prairie (Het kleine huis op de prairie)").name)
    }

    @Test fun cleansChannelsAndEpisodes() {
        assertEquals("NPO 1 HD", Titles.channel("┃NL┃ NPO 1 HD  ⏺ʳᵉᶜ"))
        assertTrue(Titles.isDivider("☰☰☰☰ ┃NL┃ JEUGD PAKKET HD ☰☰☰☰"))
        assertFalse(Titles.isDivider("┃NL┃ RTL 4 HD"))
        assertEquals("Aflevering 1", Titles.episode("┃NL┃ The Altruists (2026) - S01E01 - Aflevering 1", 1))
        assertEquals("Aflevering 3", Titles.episode("┃NL┃ Show - S01E03", 3))
    }

    @Test fun readsCategories() {
        Categories.classify("┃NL┃ NETFLIX KIDS", false, prefs).let {
            assertEquals(Lang.HOME, it.lang); assertEquals(Service.NETFLIX.bit, it.services); assertTrue(it.genres and Genre.KIDS.bit != 0L)
        }
        Categories.classify("┃NL┃ DRAMA ┃ ROMANTIEK", false, prefs).let { assertEquals(Genre.DRAMA.bit or Genre.ROMANCE.bit, it.genres) }
        Categories.classify("┃EN┃ THRILLER┃MYSTERY", false, prefs).let { assertEquals(Lang.EN, it.lang); assertEquals(Genre.THRILLER.bit or Genre.MYSTERY.bit, it.genres) }
        assertTrue(Categories.classify("┃NL┃ BIOSCOOP", false, prefs).flags and Flags.CINEMA != 0)
        assertEquals(0, Categories.classify("┃EN┃ NEW RELEASED", false, prefs).flags and Flags.CINEMA)
        assertTrue(Categories.classify("┃NL┃ KERSTFILMS", false, prefs).flags and Flags.CHRISTMAS != 0)
        assertTrue(Categories.classify("┃NL┃ SINTERKLAAS", false, prefs).genres and Genre.KIDS.bit != 0L)
        // Not for us: other languages and the Turkish/Bollywood shelves inside the Dutch section.
        assertNull(Categories.classify("┃TR┃ DIZI", false, prefs).lang)
        assertNull(Categories.classify("┃NL┃ BOLLYWOOD", false, prefs).lang)
        assertNull(Categories.classify("┃NL┃ TURKS NU TE ZIEN", false, prefs).lang)
        assertNull(Categories.classify("┃HINDI┃ MOVIES", false, prefs).lang)
        assertNull(Categories.classify("FOR ADULTS", true, prefs).lang)
        // Live: Dutch always, English only when switched on.
        assertEquals("Jeugd", Categories.classify("┃NL┃ JEUGD | BABY", true, prefs).label)
        assertEquals("Nederland HD", Categories.classify("┃NL┃ NEDERLAND HD | TERUGKIJKEN ⏺", true, prefs).label)
        assertNull(Categories.classify("┃UK┃ SKY CINEMA", true, prefs).lang)
        assertEquals(Lang.EN, Categories.classify("┃UK┃ SKY CINEMA", true, prefs.copy(englishLive = true)).lang)
    }

    @Test fun readsGenreText() {
        assertEquals(Genre.COMEDY.bit or Genre.ROMANCE.bit, Genre.mask("Komedie, Romantiek"))
        assertEquals(Genre.ACTION.bit or Genre.ADVENTURE.bit or Genre.ANIMATION.bit, Genre.mask("Action & Adventure / Animation"))
        assertEquals(Genre.SCIFI.bit or Genre.FANTASY.bit, Genre.mask("Sci-Fi & Fantasy"))
        assertEquals(0L, Genre.mask("Star Wars saga"))
    }

    @Test fun tags() {
        assertEquals("NL", Tags.of("┃NL┃ NETFLIX"))
        assertEquals(Language.ENGLISH, Tags.language(Tags.of("┃USA┃ HBO")))
        assertNull(Tags.language(Tags.of("┃PPV┃ UFC PPV")))
    }

    /** Other providers write labels differently; all of these must be understood. */
    @Test fun readsOtherLabelFormats() {
        assertEquals("NL", Tags.of("NL: Movies 2024"))
        assertEquals("NL", Tags.of("(NL) Kids"))
        assertEquals("NL", Tags.of("|NL| Films"))
        assertEquals("NL", Tags.of("Movies | NL"))
        assertEquals("EN", Tags.of("Drama [EN]"))
        assertEquals("DE", Tags.of("DE: Kino"))
        // Not a label: an abbreviation that happens to come first.
        assertNull(Tags.of("FBI: Most Wanted"))
        assertNull(Tags.of("TV-Series"))
        assertEquals("Most Wanted", Titles.clean("NL: Most Wanted").name)
        // Languages named in words.
        assertEquals(Lang.HOME, Categories.classify("Dutch Movies", false, prefs).lang)
        assertEquals(Lang.HOME, Categories.classify("Nederlandse films", false, prefs).lang)
        assertNull(Categories.classify("German Cinema", false, prefs).lang)
        assertNull(Categories.classify("Turkse series", false, prefs).lang)
        assertNull(Categories.classify("FR: Films", false, prefs).lang)
    }

    /** The viewer's home language decides what stays; English is the fallback; the rest goes. */
    @Test fun followsHomeLanguage() {
        val german = prefs.copy(home = Language.GERMAN)
        assertEquals(Lang.HOME, Categories.classify("┃DE┃ KINO", false, german).lang)
        assertEquals(Lang.EN, Categories.classify("┃EN┃ ACTION", false, german).lang)
        assertNull(Categories.classify("┃NL┃ FILMS", false, german).lang)
        assertNull(Categories.classify("┃BE┃ VLAAMS", false, german).lang)
        val turkish = prefs.copy(home = Language.TURKISH)
        assertEquals(Lang.HOME, Categories.classify("┃TR┃ DIZI", false, turkish).lang)
        assertNull(Categories.classify("┃NL┃ FILMS", false, turkish).lang)
        // Flemish viewers get Dutch titles as their own.
        assertEquals(Lang.HOME, Categories.classify("┃NL┃ FILMS", false, prefs.copy(home = Language.FLEMISH)).lang)
        assertEquals(Lang.REGIONAL, Categories.classify("┃BE┃ VLAAMSE FILMS", false, prefs).lang)
        assertNull(Categories.classify("┃BE┃ VLAAMSE FILMS", false, prefs.copy(belgian = false)).lang)
        // English off: only the home language.
        assertNull(Categories.classify("┃EN┃ ACTION", false, prefs.copy(english = false)).lang)
    }

    /** A provider that hardly labels languages keeps everything instead of ending up empty. */
    @Test fun handlesProvidersWithoutLanguageLabels() {
        val names = listOf("Movies 2024", "Action", "Comedy", "Kids", "Sports", "News", "Documentaries", "Horror", "Netflix Series", "4K")
        val profile = ProviderProfile.of(names)
        assertFalse(profile.labelsLanguages)
        assertEquals(Lang.HOME, Categories.classify("News", true, prefs, profile).lang)
        Categories.classify("Netflix Series", false, prefs, profile).let { assertEquals(Lang.HOME, it.lang); assertFalse(it.labelled); assertEquals(Service.NETFLIX.bit, it.services) }
        // The usual "┃NL┃ …" provider is recognised as labelling.
        assertTrue(ProviderProfile.of(listOf("┃NL┃ FILMS", "┃EN┃ DRAMA", "┃DE┃ KINO", "TOP 100")).labelsLanguages)
    }

    @Test fun parsesPastedLinks() {
        val s = CredentialParser.parse("hier: http://example.com:8080/get.php?username=abc&password=d%40f&type=m3u_plus")!!
        assertEquals("http://example.com:8080", s.host); assertEquals("abc", s.username); assertEquals("d@f", s.password)
        assertEquals("u1", CredentialParser.parse("Server: example.com\nGebruikersnaam: u1\nWachtwoord: p1")!!.username)
        assertNull(CredentialParser.parse("geen gegevens"))
    }
}
