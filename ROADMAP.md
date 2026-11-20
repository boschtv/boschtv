# Roadmap

Where BoschTV is heading. Concrete bugs and small improvements live in
[GitHub Issues](https://github.com/florisbosch/boschtv/issues); this page is about direction.
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

## Later

- **Other devices.** A shared core (Kotlin Multiplatform) for an Android phone app, a desktop app and an
  iPhone app, all reading catalogs the same way.
- **Sync between devices.** Watch history and lists shared across devices, optional and privacy-first.

## Out of scope

- **Content.** BoschTV will never include, link to or recommend a provider.
- **Web version.** Browsers cannot play most IPTV streams.
