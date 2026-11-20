# Roadmap

What exists, what is open, and what comes next, in the order we intend to do it.
Tick items off as they land.

## Where we are

Chromecast / Android TV app, version 2.1.0.

- [x] Login on the TV or from a phone (QR code + setup page on the local network)
- [x] Catalog sync on login, on launch when stale, and in the background
- [x] One merged catalog: Dutch first, English as fallback, duplicates and qualities merged
- [x] Provider categories reduced to genres, streaming services, cinema / popular / seasonal flags
- [x] Home, Films, Series with rows ordered by taste, time of day and season
- [x] Streaming-service pages with each service's app icon (23 of 24; National Geographic uses its logo)
- [x] Genres page with kind, sort, year and combinable genre filters
- [x] Kids tab; children's titles kept out of everything else
- [x] Search over the whole catalog with on-screen keyboard and voice
- [x] Detail pages with seasons, episodes, watched marks, "Meer zoals dit"
- [x] Player: resume, next episode with countdown, subtitles / audio, instant accelerating skip
- [x] Live TV: last channel, zapping, number keys, guide with schedule, favourites
- [x] Live TV: watch the current programme from its beginning (catch-up)
- [x] Trending from TMDB: top 10 films and series this week, top 10 and "new and popular" per service
- [x] Hover preview in the hero (30 seconds, with sound)
- [x] "Meer zoals dit" from TMDB recommendations, with our own picks as fallback
- [x] One stream at a time: players release their connection when left
- [x] Install script for the Chromecast (`tools/install-tv.sh`)
- [x] Timeline TV guide: channels down the side, time along the top, a line at "now"
- [x] Streaming-service switcher from the logo
- [x] In-app updates from public releases; first install via Downloader
- [x] Home language setting (any of 11 languages, English fallback, the rest filtered out)
- [x] Providers that label languages differently, or not at all; `tools/provider-report.sh` to check one

## 1. Finish the Chromecast app

Small, known gaps. Do these before starting on other devices.

- [ ] Confirm on the TV: no black screen after skipping in a film
- [ ] Confirm on the TV: moving up into the streaming-service row is not skipped
- [ ] Dutch descriptions from TMDB where the provider only has English
- [ ] Guide data for 4K / 8K channel copies that have no HD twin with a guide (MTV 4K, Viaplay TV 4K)
- [ ] Live info banner overlapping a channel's own subtitles
- [ ] Settings switch for hover previews and their sound
- [ ] Server timezone for catch-up read from the provider instead of assuming Europe/Amsterdam
- [ ] Back on the home page jumps to the top when scrolled far down (first attempt lost focus, removed)

## 1b. Open source

- [ ] App texts translatable (now Dutch in the code): move them to string resources, add English first,
      then follow the home language
- [ ] Release signing key (private, backed up) instead of the build machine's debug key
- [ ] Check a few other providers with `tools/provider-report.sh` and add their formats to the tests
- [ ] First-run choice of language on the login screen, if the TV-language default turns out wrong too often

## 2. Shared core (Kotlin Multiplatform)

Prerequisite for every other device. Risky for the existing app, so on its own branch
and merged only when the Chromecast build is verified unchanged.

- [ ] Split the project: `shared` module (catalog, merge rules, recommender, trends, progress) + `androidtv` app
- [ ] Replace Android-only libraries in the core with multiplatform ones (database, settings, networking, images)
- [ ] Unit tests for the core run on all targets
- [ ] Chromecast build behaves exactly as before

## 3. Mac app

First new device: it can be built and tested on the development machine, with no signing hurdles.

- [ ] Desktop app on the shared core (Compose Multiplatform)
- [ ] Playback through VLC's or mpv's library (plays MKV and the provider's audio formats)
- [ ] Mouse and keyboard layout for home, detail, search, genres, live TV
- [ ] Decide: self-contained player, or "browse here, play in VLC / IINA" as a first version

## 4. Cloud sync

Makes devices agree on progress. Needed as soon as there is a second device.

- [ ] Choose the provider: Firebase (official library is Android-only; needs a community
      multiplatform library) versus Supabase or similar. Compare before building.
- [ ] Sync watch progress, "Mijn lijst", thumbs
- [ ] Link a device with a short code shown on screen
- [ ] Local storage stays the source of speed; the app keeps working offline
- [ ] Decision needed: viewing history will be stored at a third party

## 5. iPhone app

- [ ] Touch layout on the shared core
- [ ] VLC-based playback (Apple's player does not handle MKV)
- [ ] Decision needed: Apple developer account ($99 / year); without it the app expires every 7 days
- [ ] Optional: iPad layout, AirPlay

## 6. Larger features

Any device, after the above or in between when wanted.

- [ ] Profiles: separate progress, list and recommendations per person
- [ ] Search in the TV guide ("PSV", "F1") that jumps to channel and time
      (needs the provider's full guide, one 71 MB file, downloaded and indexed in the background)
- [ ] Catch-up for earlier programmes, not only the one that is on now
- [ ] Pause and rewind live TV
- [ ] Picture follows while skipping (the paused frame jumps along as you hold left / right);
      only after skipping is confirmed stable on the Chromecast. Real thumbnail strips are not
      possible: the provider supplies none and the line allows one connection.
- [ ] DTS audio (needs an extra decoder library that is not mainstream; decide before adding)
- [ ] Android phone app (touch layout on the same code; low effort once the core is shared)

## Parked

Looked at, not doing for now.

- **Skip intro.** No data to do it properly: the provider's files carry no intro markers
  (9 of 10 popular series had no chapters at all), and audio fingerprinting needs a server.
  A fixed "+90 sec" button was rejected as guessing.
- **"Verder kijken" on the Google TV home screen.** Publishing works (Settings shows the number of
  titles published, with complete entries and the permission granted), but Google TV only shows
  that row for services it lists itself; a sideloaded app cannot be added.
- **Web version.** Browsers cannot play most of the catalog and block the provider's plain-http
  streams; it would need a relay server, which is neither free nor on-device.
- **React Native / Electron.** Possible, but the logic would exist twice and playback has the
  same limits as the web.

## Constraints to keep in mind

- The line allows **one stream at a time**. A second device playing means the first one stops.
- The provider throttles when hit too often: background detail fetching is one request per 1.5 s,
  200 per app start.
- Secrets stay out of git: the TMDB token lives in `local.properties`.
