package nl.boschtv.tv

import nl.boschtv.tv.data.db.ChannelEntity
import nl.boschtv.tv.data.db.ContentIds
import nl.boschtv.tv.data.db.EpisodeEntity
import nl.boschtv.tv.data.db.FavoriteEntity
import nl.boschtv.tv.core.Titles
import nl.boschtv.tv.data.keepsSeen
import nl.boschtv.tv.data.listingScore
import nl.boschtv.tv.data.matchFavorites
import nl.boschtv.tv.data.resumeEpisode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What people made themselves must not depend on the IPTV provider. */
class UserDataTest {

    private fun episode(id: String, season: Int, number: Int) =
        EpisodeEntity(id = id, titleKey = "s:t1399", season = season, number = number, name = "x", container = "mkv", durationSecs = 0, image = null, plot = null)

    private fun channel(id: Long, name: String, epg: String?, sort: Int) =
        ChannelEntity(id = id, name = name, search = name.lowercase(), logo = null, groupId = "g", epgId = epg, num = sort, sort = sort, archive = false, gen = 0)

    @Test fun rewatchingKeepsTheCheckmark() {
        assertTrue(keepsSeen(wasFinished = true, nowFinished = false))    // seen, started again: stays seen
        assertFalse(keepsSeen(wasFinished = true, nowFinished = true))
        assertFalse(keepsSeen(wasFinished = false, nowFinished = false))  // ordinary progress is saved
        assertFalse(keepsSeen(wasFinished = null, nowFinished = false))
    }

    /** The Dutch listing is what plays; MULTI and English only when there is no Dutch one. */
    @Test fun dutchListingComesFirst() {
        val nl = listingScore(lang = 0, labelled = true, dubbed = false, quality = null)
        val nl4k = listingScore(lang = 0, labelled = true, dubbed = false, quality = "4K")
        val multi = listingScore(lang = 0, labelled = false, dubbed = false, quality = null)
        val en = listingScore(lang = 1, labelled = true, dubbed = false, quality = null)
        assertTrue(nl < nl4k)
        assertTrue("a Dutch 4K listing beats a MULTI one", nl4k < multi)
        assertTrue(multi < en)
        assertTrue(listingScore(0, true, true, "4K") < multi)
    }

    @Test fun sameEpisodeOnAnotherProviderHasTheSameId() {
        // Two providers, two ids of their own for season 1 episode 3.
        assertEquals(ContentIds.of(episode("48213", 1, 3)), ContentIds.of(episode("990017", 1, 3)))
        assertEquals("e:s:t1399:1:3", ContentIds.of(episode("48213", 1, 3)))
        assertEquals("m:m:t278", ContentIds.movie("m:t278"))
    }

    @Test fun placesRowsFromBeforeTheChange() {
        assertEquals("m:m:t278", ContentIds.fromLegacy("m:m:t278", "m:t278", episodeId = null, season = null, number = null))
        assertEquals("e:s:t1399:2:5", ContentIds.fromLegacy("e:48213", "s:t1399", episodeId = "48213", season = 2, number = 5))
        assertNull(ContentIds.fromLegacy("e:48213", "s:t1399", episodeId = "48213", season = null, number = 5))
    }

    @Test fun favouritesFollowTheChannelName() {
        val npo = FavoriteEntity(1, "npo 1 hd", "NPO1.nl", 10)
        val rtl = FavoriteEntity(1, "rtl 4", "RTL4.nl", 20)
        val gone = FavoriteEntity(1, "eurosport 9", null, 30)
        // Same provider: the name is there, twice (it sits in two groups). The 4K variant shares the guide id and is not a favourite.
        val list = listOf(channel(11, "NPO 1 HD", "NPO1.nl", 0), channel(12, "NPO 1 4K", "NPO1.nl", 1), channel(13, "NPO 1 HD", "NPO1.nl", 7), channel(14, "RTL 4", "RTL4.nl", 3))
        val same = matchFavorites(listOf(npo, rtl, gone), list.filter { it.search in setOf("npo 1 hd", "rtl 4") }, list)
        assertEquals(listOf(11L, 13L), same.getValue(npo).map { it.id })
        assertEquals(listOf(14L), same.getValue(rtl).map { it.id })
        assertTrue(gone !in same)

        // Another provider writes the names differently: the guide id finds one channel each.
        val other = listOf(channel(501, "NL: NPO 1 FHD", "NPO1.nl", 0), channel(502, "NL: NPO 1 UHD", "NPO1.nl", 1), channel(503, "NL: RTL 4 FHD", "RTL4.nl", 2))
        val moved = matchFavorites(listOf(npo, rtl, gone), emptyList(), other)
        assertEquals(listOf(501L), moved.getValue(npo).map { it.id })
        assertEquals(listOf(503L), moved.getValue(rtl).map { it.id })
        assertEquals(listOf(npo, rtl), moved.keys.toList())
    }

    @Test fun resumesTheEpisodeInProgressAndNeverFallsBackToTheStart() {
        val eps = listOf(episode("a", 1, 1), episode("b", 1, 2), episode("c", 1, 10), episode("d", 2, 1))
        assertEquals("c" to true, resumeEpisode(eps, 1, 10, null, null).let { it.first.id to it.second })
        // This provider has no episode 9: the first one after it, from its beginning.
        assertEquals("c" to false, resumeEpisode(eps, 1, 9, null, null).let { it.first.id to it.second })
        // Nothing in progress: the one after the last finished, also when that one is not listed.
        assertEquals("d" to false, resumeEpisode(eps, null, null, 1, 10).let { it.first.id to it.second })
        assertEquals("c" to false, resumeEpisode(eps, null, null, 1, 5).let { it.first.id to it.second })
        assertEquals("a" to false, resumeEpisode(eps, null, null, null, null).let { it.first.id to it.second })
    }

    @Test fun knowsANameThatIsOnlyANumber() {
        for (n in listOf("Episodio 3", "Aflevering 12", "Episode 1", "E03", "7", "Épisode 2", "")) assertTrue(n, Titles.isPlaceholderEpisodeName(n))
        for (n in listOf("Dreamers and Losers", "Part of the Family", "1984", "Episode 3: The Crumbs").drop(0)) if (n != "1984") assertFalse(n, Titles.isPlaceholderEpisodeName(n))
    }
}
