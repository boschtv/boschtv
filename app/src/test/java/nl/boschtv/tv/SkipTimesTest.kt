package nl.boschtv.tv

import com.squareup.moshi.JsonReader
import nl.boschtv.tv.core.asObj
import nl.boschtv.tv.data.SkipTimes
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Answers from TheIntroDB and IntroDB (taken from the real APIs) and what the player makes of them. */
class SkipTimesTest {
    private val skips = SkipTimes(OkHttpClient(), tmdbToken = "")
    private fun json(s: String) = JsonReader.of(Buffer().writeUtf8(s)).readJsonValue().asObj()!!

    @Test fun keepsAgreedIntroAndDropsImplausibleCredits() {
        // Stranger Things 1x2: a well-supported intro, and a credits entry in the middle of the opening.
        val o = json("""{"group_duration_ms":3384000,
            "intro":[{"start_ms":274662,"end_ms":324128,"confidence":0.85,"submission_count":12}],
            "recap":[{"start_ms":0,"end_ms":0,"confidence":0.6,"submission_count":1}],
            "credits":[{"start_ms":275985,"end_ms":324887,"confidence":0.5,"submission_count":3}]}""")
        val s = skips.segments(o, null, duration = 3_384_000)
        assertEquals(274662L..324128L, s.intro)
        assertNull("0-0 means: no recap", s.recap)
        assertNull("credits in the first minutes are not credits", s.creditsAt)
    }

    @Test fun countsCreditsBackFromTheEnd() {
        // Measured on a 50:00 release, credits 3 minutes before its end; this file runs 48:00.
        val o = json("""{"group_duration_ms":3000000,"credits":[{"start_ms":2820000,"end_ms":null,"confidence":0.9,"submission_count":5}]}""")
        assertEquals(2_880_000L - 180_000L, skips.segments(o, null, duration = 2_880_000).creditsAt)
    }

    @Test fun latestCreditsStartWinsAcrossSources() {
        // Landman 1x8: TheIntroDB says 53:17 (1:37 before the end), IntroDB says 54:08 to 54:57 (49 s).
        // The viewer saw the credits at about 54:00 of 54:53: the later one is right.
        val a = json("""{"group_duration_ms":3294000,"credits":[{"start_ms":3197194,"end_ms":null,"confidence":0.99,"submission_count":6}]}""")
        val b = json("""{"outro":{"start_ms":3248370,"end_ms":3297000,"confidence":1,"submission_count":1}}""")
        assertEquals(3_293_000L - 48_630L, skips.segments(a, b, duration = 3_293_000).creditsAt)
    }

    @Test fun twoAgreeingSourcesBeatTheSeriesPattern() {
        // Severance 1x1: both databases say 1:35 before the end, while its neighbours run about 1:10.
        val a = json("""{"group_duration_ms":3420000,"credits":[{"start_ms":3325000,"end_ms":null,"confidence":0.9,"submission_count":7}]}""")
        val b = json("""{"outro":{"start_ms":3338000,"end_ms":3434000,"confidence":1,"submission_count":1}}""")
        assertEquals(3_420_000L - 95_000L, skips.segments(a, b, duration = 3_420_000, typicalTail = 68_000L).creditsAt)
    }

    @Test fun seriesPatternOverridesAStrayEpisode() {
        // Landman 1x8 again, TheIntroDB only: its neighbours sit at 53-54 s before the end.
        val typical = skips.typicalOf(listOf(53_000L, 54_000L, 62_000L))
        assertEquals(54_000L, typical)
        val o = json("""{"group_duration_ms":3294000,"credits":[{"start_ms":3197194,"end_ms":null,"confidence":0.99,"submission_count":6}]}""")
        assertEquals(3_293_000L - 54_000L, skips.segments(o, null, duration = 3_293_000, typicalTail = typical).creditsAt)
        // The pattern only ever moves credits later: an episode whose own credits are shorter keeps them.
        val shortOwn = json("""{"group_duration_ms":3000000,"credits":[{"start_ms":2970000,"end_ms":null,"confidence":0.9,"submission_count":5}]}""")
        assertEquals(3_000_000L - 30_000L, skips.segments(shortOwn, null, duration = 3_000_000, typicalTail = 60_000L).creditsAt)
        // Severance-like: credits genuinely vary per episode, so no pattern.
        assertNull(skips.typicalOf(listOf(68_000L, 95_000L, 72_000L)))
    }

    @Test fun acceptsALoneIntroWithTheSeriesLength() {
        // Landman 1x9: one submission (confidence 0.6), 5:03-6:08; the series' intro is 1:05-1:06 every episode.
        val o = json("""{"intro":[{"start_ms":303000,"end_ms":368000,"confidence":0.6,"submission_count":1}]}""")
        assertEquals(303000L..368000L, skips.segments(o, null, duration = 2_917_000, typicalIntroLength = 66_000L).intro)
        val odd = json("""{"intro":[{"start_ms":303000,"end_ms":420000,"confidence":0.6,"submission_count":1}]}""")
        assertNull(skips.segments(odd, null, duration = 2_917_000, typicalIntroLength = 66_000L).intro)
    }

    @Test fun sourcesThatAgreeConfirmAnIntro() {
        // Landman 1x9 in both databases, one submission each, two seconds apart: confirmed.
        val a = json("""{"intro":[{"start_ms":303000,"end_ms":368000,"confidence":0.6,"submission_count":1}]}""")
        val b = json("""{"intro":{"start_ms":303000,"end_ms":370000,"confidence":1,"submission_count":1}}""")
        assertNull("one unconfirmed submission alone is not enough", skips.segments(a, null, duration = 2_917_000).intro)
        // TheIntroDB's lone entry is not "agreed", so it only counts through the other source agreeing.
        assertEquals(303000L..370000L, skips.segments(json("{}"), b, duration = 2_917_000, typicalIntroLength = 66_000L).intro)
    }

    @Test fun sourcesThatDisagreeGiveNoButtonUnlessOneIsClearlyBacked() {
        // Severance 1x8: TheIntroDB 4:30-5:49 (6 people) against IntroDB 3:19-4:39 (2 people).
        val a = json("""{"intro":[{"start_ms":270000,"end_ms":349000,"confidence":0.85,"submission_count":6}]}""")
        val b = json("""{"intro":{"start_ms":199000,"end_ms":279000,"confidence":1,"submission_count":2}}""")
        assertEquals(270000L..349000L, skips.segments(a, b, duration = 3_000_000).intro)
        // Two people against two: no button.
        val a2 = json("""{"intro":[{"start_ms":270000,"end_ms":349000,"confidence":0.85,"submission_count":2}]}""")
        assertNull(skips.segments(a2, b, duration = 3_000_000).intro)
    }

    @Test fun ignoresLoneUncertainSubmissions() {
        val o = json("""{"intro":[{"start_ms":60000,"end_ms":120000,"confidence":0.3,"submission_count":1}]}""")
        assertNull(skips.segments(o, null, duration = 2_700_000).intro)
    }
}
