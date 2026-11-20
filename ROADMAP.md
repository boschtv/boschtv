# Roadmap

Where BoschTV is heading. Concrete bugs and small improvements live in
[GitHub Issues](https://github.com/boschtv/boschtv/issues); this page is about direction.
Suggestions are welcome as an issue.

## Now

- **English first, translations next.** The app's own texts move to translation files, with English as
  the base language and the TV's language used when a translation exists. Dutch is the first translation.
- **More providers.** Check the catalogs of more IPTV providers with `tools/provider-report.sh` and add
  their naming to the rules and tests.
- **M3U playlists.** Besides the Xtream Codes login: plain M3U playlist links, with an XMLTV guide.

## Next

- **Profiles.** Separate history, list and recommendations per person on one TV.
- **More live TV.** Catch-up for earlier programmes, pause and rewind live, search in the guide.
- **Previews while skipping.** The picture follows when holding left or right in a film.

## Later: other platforms

BoschTV is built for Android TV first. The plan for phones, computers and other TVs is one shared core
with a thin app per platform, so every platform reads catalogs the same way and gets the same fixes.

**1. Shared core (Kotlin Multiplatform).** The parts that do not draw anything move to a `shared`
module that compiles for Android, iOS, macOS, Windows and Linux:

- catalog reading: languages, genres, services and cinema/top lists (`core/` is already plain Kotlin),
- the Xtream client and the merging of titles,
- the recommendations and home rows,
- progress, My List and taste,
- the TMDB lists.

Android-only libraries in these parts get multiplatform equivalents: Room (KMP), DataStore (KMP),
Ktor or OkHttp, and the existing tests run on every target. The Android TV app keeps working exactly as
it does now; that is the condition for merging this step.

**2. Screens per platform family.** Compose Multiplatform shares most of the screens. Each platform gets
its own layout and navigation:

| Platform | Screens | Playback | Distribution |
| --- | --- | --- | --- |
| Android TV / Google TV | today's app | Media3 | GitHub releases, Downloader |
| Android phone and tablet | touch layout | Media3 | GitHub releases |
| macOS, Windows, Linux | mouse and keyboard layout | libmpv or VLC | GitHub releases |
| iPhone, iPad, Apple TV | touch / remote layout | VLCKit (Apple's player cannot play most IPTV formats) | needs an Apple developer account |

Playback stays native per platform behind one small player interface, because no single player
handles IPTV streams well everywhere.

**3. Order.** Android phone first, since it is the cheapest (same language, same player). Then desktop,
which is easy to test and needs no store. Apple's platforms come last: they need a paid developer
account and their own review.

**4. Sync between devices (optional).** Watch history and My List shared across devices once there is
more than one. Privacy-first: off by default, and a self-hostable backend rather than a required account.

Help with any of these steps is welcome: open an issue first, so the work can be split up.

## Out of scope

- **Content.** BoschTV will never include, link to or recommend a provider.
- **Web version.** Browsers cannot play most IPTV streams.
