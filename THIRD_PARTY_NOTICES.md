# Third-party notices

BoschTV's own code is under the [MIT license](LICENSE). It is built on the following work by others,
used under their licenses.

## Libraries

All under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) unless noted.

| Library | Version | By |
| --- | --- | --- |
| Kotlin standard library | 2.0.21 | JetBrains |
| kotlinx.coroutines | 1.9.0 | JetBrains |
| AndroidX Core, Activity, Lifecycle | 1.13.1, 1.9.3, 2.8.7 | The Android Open Source Project |
| Jetpack Compose (UI, Foundation, Material icons) | BOM 2024.12.01 | The Android Open Source Project |
| Compose for TV (tv-material) | 1.0.0 | The Android Open Source Project |
| Media3 / ExoPlayer (player, HLS, UI) | 1.5.1 | The Android Open Source Project |
| Room | 2.6.1 | The Android Open Source Project |
| DataStore | 1.1.1 | The Android Open Source Project |
| WorkManager | 2.10.0 | The Android Open Source Project |
| TV Provider | 1.1.0-alpha01 | The Android Open Source Project |
| Profile Installer | 1.4.1 | The Android Open Source Project |
| OkHttp | 4.12.0 | Square, Inc. |
| Moshi | 1.15.1 | Square, Inc. |
| Coil (compose, svg) | 2.7.0 | Coil Contributors |
| ZXing core (QR codes) | 3.5.3 | ZXing authors |
| JUnit (tests only, [EPL 1.0](https://www.eclipse.org/legal/epl-v10.html)) | 4.13.2 | JUnit team |

The material icons in the app come from Google's Material Symbols (Apache License 2.0).

## Font

**Geist** by Vercel, in `app/src/main/res/font/geist.ttf`, under the
[SIL Open Font License 1.1](licenses/Geist-OFL.txt).

## Profile avatars

The illustrated avatars in `app/src/main/assets/avatars/` were made with [DiceBear](https://www.dicebear.com)
(code under the MIT license) by `tools/avatars/generate.mjs`. The designs:

- **Bottts** by Pablo Stanley ([bottts.com](https://bottts.com/)): free for personal and commercial use.
- **Avataaars** by Pablo Stanley ([avataaars.com](https://avataaars.com/)): free for personal and commercial use.
- **Fun Emoji** by Davis Uche, under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Generated from the
  original design; not changed otherwise.
- **Pixel Art** by DiceBear, under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

The full license texts are in `licenses/dicebear-*.txt`. The app also names the designers in Settings.

## Data and services

- **TMDB.** Posters, trending lists, recommendations and streaming-service logos use the TMDB API.
  This product uses the TMDB API but is not endorsed or certified by TMDB. Use is subject to the
  [TMDB API terms](https://www.themoviedb.org/api-terms-of-use).
- **JustWatch.** Which titles are on which streaming service, and the services' logos, are
  watch-provider data that TMDB gets from JustWatch.
- **TheIntroDB and IntroDB.** "Skip intro", "Skip recap" and the next-episode countdown use community
  timestamps from [TheIntroDB](https://theintrodb.org) (by TMDB id) and [IntroDB](https://introdb.app)
  (by IMDb id, looked up through TMDB), combined so that one source's mistake does not skip any story.
- **Your IPTV provider.** BoschTV contains no content; everything it shows comes from the
  subscription you log in with.

## Trademarks

The names and logos of streaming services (Netflix, Prime Video, Disney+, Videoland, HBO Max, Apple TV,
SkyShowtime, Viaplay, NPO Start, NLZIET, Paramount+, Discovery+, Pathé Thuis, Hulu, Peacock, Streamz,
VTM GO, VRT MAX, BBC iPlayer, Canal+, KIJK, MGM+, Crunchyroll, National Geographic) are trademarks of
their respective owners. BoschTV shows them only to label which service a title belongs to and is not
affiliated with, endorsed by or sponsored by any of them. The logos are not part of this repository:
the app loads them from TMDB's watch-provider data (by JustWatch) at runtime.
