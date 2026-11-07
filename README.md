# Orbit Player

IPTV player for Android TV and phones/tablets. One app, two layouts:

- **TV** – side menu, channel list with groups, full-screen player with quick channel list, TV guide grid, catch-up, movies and series.
- **Phone / tablet** – home screen with tiles, live channels with mini player, TV guide, movies, series, offline downloads, picture-in-picture.

Orbit Player does not provide any content. Users add their own Xtream Codes account or M3U playlist.

## Features

- Xtream Codes and M3U (address or file), XMLTV guide
- Favorites, recently watched, hidden groups/channels, parental PIN
- Catch-up (archive) from the guide
- Movies and series with resume, "continue watching", subtitles (built-in, from file or from a URL)
- Offline downloads with subtitles (phone/tablet)
- Speed test and diagnostics
- Backup and restore of settings and playlists

## Build

Builds run on GitHub Actions (`.github/workflows/build.yml`). The newest APK is always attached to the
`latest-build` release. See [docs/BUILD.md](docs/BUILD.md) for local builds and release signing.
