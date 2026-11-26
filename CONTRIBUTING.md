# Working on BoschTV

A step-by-step guide: from a fresh machine to your change running on a TV, plus how the parts fit
together and how to teach the app a new provider, language or streaming service.

## 1. Set up

You need JDK 17 and the Android SDK (platform 35, build-tools, platform-tools, emulator). On macOS:

```bash
brew install openjdk@17 android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools" "emulator" \
           "system-images;android-34;google_atv;arm64-v8a"
```

On Linux or Windows, Android Studio installs the same pieces.

## 2. Build and test

```bash
git clone https://github.com/boschtv/boschtv && cd boschtv
./gradlew :app:testDebugUnitTest      # the rules for reading catalogs (fast, no device)
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Use the **release** build on devices: it is optimised and much faster than the debug build.

### Optional settings: `local.properties`

`local.properties` is never committed. Everything in it is optional; without it the app builds and
works, minus what the setting is for.

| Key | What it does | Without it |
| --- | --- | --- |
| `tmdb.token` | TMDB read-access token (free at themoviedb.org → Settings → API) | No Top 10, trending or TMDB recommendations; "More like this" uses the app's own picks |
| `update.url` | Where installed copies look for new versions (`…/releases/latest/download/update.json`) | No update checks |
| `signing.store`, `signing.password`, `signing.alias` | Release signing key | Your machine's debug key |

## 3. Run it

**Emulator.** Create a Google TV device once, then start it:

```bash
avdmanager create avd -n BoschTV_GoogleTV -k "system-images;android-34;google_atv;arm64-v8a" -d tv_1080p
emulator -avd BoschTV_GoogleTV &
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n nl.boschtv.tv/.MainActivity
```

The arrow keys, Enter and Esc on your keyboard are the remote. To use the phone login from your
computer: `adb forward tcp:18765 tcp:8765`, then open <http://127.0.0.1:18765>.

**Your TV.** `tools/install-tv.sh --build` builds, connects over wifi (it asks for the TV's address and
a pairing code the first time and remembers them), installs and optimises the app.

Many IPTV lines allow **one stream at a time**: close the app on one device before playing on another.

**No subscription?** `tools/demo-provider/serve.py` is a stand-in provider with only freely licensed
films (Blender Studio's open movies, Creative Commons and public-domain titles). Start it and log in
with server `http://10.0.2.2:8089` on the emulator, any username and any password. Screenshots for the
README are taken with it, so they show nothing but open content.

## 4. How it fits together

```
app/src/main/java/nl/boschtv/tv/
  core/      Pure Kotlin, no Android: reading provider names. Language.kt (languages, codes, words),
             Text.kt (labels, title cleaning), Taxonomy.kt (genres, streaming services, categories).
  data/      Sync (catalog download and merge), Catalog (queries), Library (progress, list, taste),
             Recommender (home rows), Trends (TMDB), Epg (guide), Prefs, db/ (Room), xtream/ (API).
  ui/        Home, Browse (Genres), Search, Detail, Settings, Login, shared components, theme.
  player/    PlayerActivity (films, series), LiveActivity (live TV), Timeline (the guide).
  system/    Updater (in-app updates), WatchNext (Google TV home row).
  setup/     The small web page for logging in from a phone.
```

The flow: **Sync** downloads the provider's category lists and stream lists, decides per category
what it means (language, genres, streaming service, flags), merges duplicates into one title per film
or series, and stores everything in a local database. Every screen reads only that database, which is
why it is fast. Details (plot, episodes, genres for titles without one) are fetched later, a few at a
time, because providers throttle.

## 5. Teaching it a provider

Providers name their categories differently. The rules are in `core/`, and the tool below shows how
they read a given provider.

1. **Look at how the app reads it:**

   ```bash
   tools/provider-report.sh
   ```

   It asks for a server, login and home language, fetches only the three category lists, and writes
   `app/build/provider-report.txt`: every category with the language, genres, streaming service and
   flags the app gives it, and every category it leaves out. The login is not stored anywhere.
2. **Find what is wrong:** a Dutch category left out, a genre or service not recognised, foreign
   content let in.
3. **Fix the rule** in `core/`:
   - a language code or word → `Language.kt`
   - a label format (`NL » Movies`) → `Tags` in `Text.kt`
   - a genre word (`Komödie`) → `Genre` in `Taxonomy.kt`
   - a streaming service → `Service` in `Taxonomy.kt`
   - cinema, top lists, Christmas → `Flags` in `Taxonomy.kt`
4. **Add the category names to `CoreTest.kt`** with what they should become, so the fix stays fixed.
5. Run `./gradlew :app:testDebugUnitTest` and the report again.

How languages are decided, so you know what to change:

- **Each sync first measures the provider.** If at least 30% of its categories carry a language,
  unlabelled categories count as the home language (films, series) or are left out (live TV). If it
  hardly labels anything, everything is kept.
- **A category's label wins**, unless its name mentions another language: `┃NL┃ TURKS` is Turkish.
- **When a category has no label, each title's own label decides** (`NL: Dune`, `DE: Dune`, and bare
  as `NL Dune` / `DE Dune` when the rest of the name has small letters, so `DE KAMPIOENEN` stays a title).
- **`MULTI` is a label without a language**: stripped from the name, and such a listing only plays when
  the title has no listing in the home language, whatever the quality of either.
- **What stays:** the viewer's home language, English (switchable) and, for Dutch viewers, Flemish
  (switchable). The rest is left out.

## 6. Adding a language or streaming service

**Language:** add an entry to `Language` in `core/Language.kt` with:

- the codes providers use,
- words for it in that language, English and Dutch,
- `selectable = true` if people should be able to choose it,
- the TMDB locale (`de-DE`).

Add a line to `followsHomeLanguage` in `CoreTest.kt`.

**Streaming service:** add an entry to `Service` in `core/Taxonomy.kt` (label, pattern, brand colour).

- Its logo comes from TMDB automatically when TMDB lists the service under a similar name. Where
  TMDB's name differs, add it to `tmdbNames` in `Trends.kt`.
- Add its TMDB provider id to `providers` in `Trends.kt` for its "new and popular" row.
- Never commit logo files. For your own builds you can put one in `app/src/main/assets/icons/`
  (see the README); that folder is ignored by git.

## 7. Releases and updates

Official builds come from this repository's releases:

```bash
tools/release.sh 2.4.0 "What's new (shown in the app)"
```

This raises `versionCode` in `version.properties`, builds and tests, and publishes `boschtv.apk` and
`update.json` as a GitHub release. Installed copies compare `versionCode` and offer the update.

**Forks** publish their own releases the same way: set `RELEASE_REPO=you/your-fork` and point
`update.url` at your fork. Each build only updates installs signed with the same key. Your users
follow your releases, official users follow the official ones, and neither can replace the other's.

## 8. Pull requests

Every change goes through a pull request; nobody pushes to `main` directly.

1. Fork the repository (or make a branch if you are a maintainer) and make your change.
2. Open a pull request against `main` and fill in the template.
3. CI runs the tests, lint and a build. For first-time contributors a maintainer starts it.
4. A maintainer reviews and approves; open review comments must be resolved.
5. The pull request is squashed into one commit on `main`.

Keep pull requests small and about one thing. Run the unit tests and lint before opening one, and
check screen changes on the emulator with the remote: focus must stay visible and never get lost.
New texts go into `res/values/strings.xml` (English); add the translations you can.
Never commit logins, tokens, `local.properties`, provider names or brand logos.

## 9. Translations

The app's texts are in `app/src/main/res/values/strings.xml` (English). To add a language, copy it to
`values-<language code>/strings.xml` (for example `values-de`) and translate the texts; keep the
`%1$s`-style placeholders. The app follows the TV's language automatically.
