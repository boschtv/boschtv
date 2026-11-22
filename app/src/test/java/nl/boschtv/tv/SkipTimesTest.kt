package nl.boschtv.tv

import com.squareup.moshi.JsonReader
import nl.boschtv.tv.core.asObj
import nl.boschtv.tv.data.SkipTimes
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TheIntroDB answers (taken from the real API) and what the player makes of them. */
class SkipTimesTest {
    private val skips = SkipTimes(OkHttpClient())
    private fun json(s: String) = JsonReader.of(Buffer().writeUtf8(s)).readJsonValue().asObj()!!

    @Test fun keepsAgreedIntroAndDropsImplausibleCredits() {
        // Stranger Things 1x2: a well-supported intro, and a credits entry in the middle of the opening.
        val o = json("""{"group_duration_ms":3384000,
            "intro":[{"start_ms":274662,"end_ms":324128,"confidence":0.85,"submission_count":12}],
            "recap":[{"start_ms":0,"end_ms":0,"confidence":0.6,"submission_count":1}],
            "credits":[{"start_ms":275985,"end_ms":324887,"confidence":0.5,"submission_count":3}]}""")
        val s = skips.segments(o, duration = 3_384_000)
        assertEquals(274662L..324128L, s.intro)
        assertNull("0-0 means: no recap", s.recap)
        assertNull("credits in the first minutes are not credits", s.creditsAt)
    }

    @Test fun countsCreditsBackFromTheEnd() {
        // Measured on a 50:00 release, credits 3 minutes before its end; this file runs 48:00.
        val o = json("""{"group_duration_ms":3000000,"credits":[{"start_ms":2820000,"end_ms":null,"confidence":0.9,"submission_count":5}]}""")
        assertEquals(2_880_000L - 180_000L, skips.segments(o, duration = 2_880_000).creditsAt)
    }

    @Test fun ignoresLoneUncertainSubmissions() {
        val o = json("""{"intro":[{"start_ms":60000,"end_ms":120000,"confidence":0.3,"submission_count":1}]}""")
        assertNull(skips.segments(o, duration = 2_700_000).intro)
    }
}
