package nl.boschtv.tv

import nl.boschtv.tv.core.Copies
import nl.boschtv.tv.core.EpisodeCopy
import nl.boschtv.tv.core.FrameRateMeter
import nl.boschtv.tv.core.FrameRates
import nl.boschtv.tv.core.XmlTv
import nl.boschtv.tv.core.XmlTvProgramme
import nl.boschtv.tv.data.SleepTimer
import nl.boschtv.tv.data.xtream.Source
import nl.boschtv.tv.data.xtream.Subscription
import nl.boschtv.tv.data.xtream.Subscriptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTest {

    /* ---------- a lighter copy when an episode arrives too slowly ---------- */

    private val heavy = EpisodeCopy(1, "a", "mkv", 18_586)
    private val medium = EpisodeCopy(2, "b", "mkv", 7_984)
    private val light = EpisodeCopy(3, "c", "mkv", 1_948)

    @Test fun stepsDownToTheBestCopyThatIsClearlyLighter() {
        val all = listOf(heavy, light, medium)
        assertEquals(medium, Copies.lighter(all, heavy, setOf("a")))
        assertEquals(light, Copies.lighter(all, medium, setOf("a", "b")))
        assertNull(Copies.lighter(all, light, setOf("a", "b", "c")))
    }

    @Test fun aCopyOfAboutTheSameWeightIsNoHelp() {
        assertNull(Copies.lighter(listOf(heavy, heavy.copy(listing = 4, id = "d", kbps = 17_000)), heavy, setOf("a")))
    }

    @Test fun neverGoesBackToACopyThatAlreadyStalled() {
        assertEquals(light, Copies.lighter(listOf(heavy, medium, light), heavy, setOf("a", "b")))
    }

    @Test fun triesACopyOfUnknownWeightOnlyWhenNothingElseIsLeft() {
        val unknown = EpisodeCopy(5, "e", "mp4", 0)
        assertEquals(medium, Copies.lighter(listOf(heavy, unknown, medium), heavy, setOf("a")))
        assertEquals(unknown, Copies.lighter(listOf(heavy, unknown), heavy, setOf("a")))
        // The episode's own weight is unknown: the lightest known copy is the safest guess.
        assertEquals(light, Copies.lighter(listOf(unknown, medium, light), unknown, setOf("e")))
    }

    /* ---------- frame rate ---------- */

    @Test fun snapsMeasuredRatesToStandardOnes() {
        assertEquals(23.976f, FrameRates.snap(23.98f))
        assertEquals(25f, FrameRates.snap(25.01f))
        assertEquals(59.94f, FrameRates.snap(59.95f))
        assertNull(FrameRates.snap(27f))
        assertNull(FrameRates.snap(12.5f))
    }

    @Test fun picksARefreshRateThatShowsTheFilmEvenly() {
        val tv = listOf(23.976f, 24f, 25f, 50f, 59.94f, 60f)
        assertEquals(50f, FrameRates.refreshFor(25f, 60f, tv))          // a whole multiple, the higher one
        assertEquals(24f, FrameRates.refreshFor(24f, 60f, tv))
        assertEquals(23.976f, FrameRates.refreshFor(23.976f, 60f, tv))
        assertEquals(59.94f, FrameRates.refreshFor(29.97f, 50f, tv))
        // Already even: nothing to change.
        assertNull(FrameRates.refreshFor(25f, 50f, tv))
        assertNull(FrameRates.refreshFor(30f, 60f, tv))
        assertNull(FrameRates.refreshFor(60f, 60f, tv))
        // A screen that only does 60: nothing fits 25, so it is left alone.
        assertNull(FrameRates.refreshFor(25f, 60f, listOf(60f)))
    }

    @Test fun measuresFrameRateFromPictureTimes() {
        val meter = FrameRateMeter()
        var found: Float? = null
        var t = 0L
        repeat(40) { meter.add(t)?.let { r -> found = r }; t += 40_000 }   // a picture every 40 ms
        assertEquals(25f, found)
        // It answers once per stream, and again after a reset.
        assertNull(meter.add(t))
        meter.reset()
        found = null
        repeat(40) { meter.add(t)?.let { r -> found = r }; t += 41_708 }
        assertEquals(23.976f, found)
    }

    @Test fun aJumpInTheStreamDoesNotSpoilTheMeasurement() {
        val meter = FrameRateMeter()
        var found: Float? = null
        var t = 0L
        repeat(10) { meter.add(t); t += 20_000 }
        t += 5_000_000   // a seek
        repeat(30) { meter.add(t)?.let { r -> found = r }; t += 20_000 }
        assertEquals(50f, found)
    }

    /* ---------- programme guide file ---------- */

    @Test fun readsGuideTimesWithTheirZone() {
        assertEquals(1_795_462_200_000L, XmlTv.time("20261123203000 +0100"))
        assertEquals(1_795_462_200_000L, XmlTv.time("20261123193000 +0000"))
        assertEquals(1_795_462_200_000L, XmlTv.time("20261123193000"))
        assertEquals(1_795_462_200_000L, XmlTv.time("20261123143000 -0500"))
        assertNull(XmlTv.time("soon"))
        assertNull(XmlTv.time(null))
    }

    @Test fun readsProgrammesFromAGuideFile() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="npo1.nl"><display-name>NPO 1</display-name></channel>
              <programme start="20261123200000 +0100" stop="20261123203000 +0100" channel="npo1.nl">
                <title lang="nl">Journaal</title><title lang="en">News</title><desc>Het nieuws van &amp; vandaag.</desc>
              </programme>
              <programme start="20261123203000 +0100" stop="20261123213000 +0100" channel="other.de"><title>Tatort</title></programme>
              <programme start="20261123213000 +0100" stop="20261123213000 +0100" channel="npo1.nl"><title>No length</title></programme>
              <programme start="20261123220000 +0100" stop="20261123230000 +0100" channel="npo1.nl"><desc>No title</desc></programme>
            </tv>"""
        val all = ArrayList<XmlTvProgramme>()
        XmlTv.read(xml.byteInputStream()) { all += it }
        assertEquals(listOf("Journaal", "Tatort"), all.map { it.title })
        assertEquals("Het nieuws van & vandaag.", all[0].description)
        assertEquals(30 * 60_000L, all[0].stop - all[0].start)
        // Only the channels we keep.
        val mine = ArrayList<XmlTvProgramme>()
        XmlTv.read(xml.byteInputStream(), wanted = { it == "npo1.nl" }) { mine += it }
        assertEquals(listOf("Journaal"), mine.map { it.title })
    }

    /* ---------- subscriptions ---------- */

    @Test fun keepsSeveralSubscriptions() {
        val a = Source("http://one.example:8080", "anna", "p w&d=1")
        val b = Source("https://two.example", "bob", "x")
        val (one, idA) = Subscriptions.with(emptyList(), a)
        val (two, idB) = Subscriptions.with(one, b)
        assertEquals(0, idA); assertEquals(1, idB)
        assertEquals(two, Subscriptions.decode(Subscriptions.encode(two)))
        // The same server and user again is a new password, not a second subscription.
        val (three, again) = Subscriptions.with(two, a.copy(password = "new"))
        assertEquals(0, again); assertEquals(2, three.size); assertEquals("new", three.first { it.id == 0 }.source.password)
        // A removed one does not give its number to the next.
        val (four, idC) = Subscriptions.with(two.filter { it.id != 0 }, Source("http://three.example", "c", "c"))
        assertEquals(2, idC); assertEquals(2, four.size)
        assertEquals("one.example:8080 · anna", Subscription(0, a).label)
        assertTrue(Subscriptions.decode(null).isEmpty())
        assertTrue(Subscriptions.decode("rubbish").isEmpty())
    }

    /* ---------- sleep timer ---------- */

    @Test fun sleepTimerStepsUpAndStops() {
        var now = 1_000_000L
        val timer = SleepTimer { now }
        assertEquals(0, timer.minutesLeft()); assertFalse(timer.due())
        timer.cycle(); assertEquals(15, timer.minutesLeft())
        timer.cycle(); assertEquals(30, timer.minutesLeft())
        now += 20 * 60_000L
        assertEquals(10, timer.minutesLeft())
        timer.cycle(); assertEquals(15, timer.minutesLeft())   // the next longer time than what is left
        timer.cycle(); timer.cycle(); timer.cycle(); assertEquals(90, timer.minutesLeft())
        timer.cycle(); assertEquals(0, timer.minutesLeft())    // past the longest: off
        timer.cycle()
        now += 15 * 60_000L - 1; assertFalse(timer.due())
        now += 1; assertTrue(timer.due())
        assertFalse(timer.due()); assertEquals(0, timer.minutesLeft())
    }

    /* ---------- subtitle and audio lists ---------- */

    @Test fun viewersLanguagesHeadTheTrackList() {
        val t = listOf(
            nl.boschtv.tv.core.TrackInfo("ar", null), nl.boschtv.tv.core.TrackInfo("en", "English [SDH]", hearingImpaired = true),
            nl.boschtv.tv.core.TrackInfo("en", "English"), nl.boschtv.tv.core.TrackInfo("de", null),
            nl.boschtv.tv.core.TrackInfo("nl-NL", "Dutch (Forced)", forced = true), nl.boschtv.tv.core.TrackInfo("nl", "Dutch"),
            nl.boschtv.tv.core.TrackInfo("fr", null), nl.boschtv.tv.core.TrackInfo(null, "Commentary"),
        )
        val mine = listOf("nl", "en")
        // Dutch, then English; the plain track before the special ones; the rest as the file has them.
        assertEquals(listOf(5, 4, 2, 1, 0, 3, 6, 7), nl.boschtv.tv.core.TrackList.order(t, mine))
        assertEquals(setOf(0, 3, 6, 7), nl.boschtv.tv.core.TrackList.folded(t, mine))
        // A short list is shown whole, and so is one with nothing in your languages.
        assertTrue(nl.boschtv.tv.core.TrackList.folded(t.take(6), mine).isEmpty())
        assertTrue(nl.boschtv.tv.core.TrackList.folded(t, listOf("ja")).isEmpty())
        assertTrue(nl.boschtv.tv.core.TrackList.hearingImpaired("English [SDH]"))
        assertTrue(nl.boschtv.tv.core.TrackList.forced("Dutch (Forced)"))
        assertFalse(nl.boschtv.tv.core.TrackList.forced("Dutch"))
        assertEquals(listOf("English", "English 2", "Dutch", "English 3"), nl.boschtv.tv.core.TrackList.distinct(listOf("English", "English", "Dutch", "English")))
    }
}
