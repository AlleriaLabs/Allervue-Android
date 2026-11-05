# Allervue Android
Allervue is an Android Jellyfin client for the Alleria Labs media ecosystem, with a custom gesture-based media player.
Server is built in (Api.kt, `SERVER`) and hidden from the login screen.

Build: push to GitHub and run the "Build APK" workflow (see .github/workflows/build.yml),
or open in Android Studio and Build > Build APK(s).

Player: swipe left/right half for brightness/volume, swipe sideways to seek, double-tap sides for ±10s,
pinch to zoom, long-press for 2x, lock button, PiP with Home, resume + progress sync. Episode navigation is shown in the player header; Skip Intro appears when Jellyfin has intro segment markers for the episode.

## Discovery and categories
- Home keeps the featured hero carousel and adds Continue Watching, separate Popular Movies / Popular Series rows, and personalized picks.
- Movies can be filtered by the genres present in the Jellyfin library.
- TV Shows has All, Anime, and TV Series filters. Anime classification uses an `Anime` genre or tag in Jellyfin metadata; add that tag/genre to anime libraries or items for reliable grouping.
- Skip Intro uses Jellyfin Media Segments. It requires Jellyfin 10.10+ and a segment provider/plugin that has generated Intro markers; without markers the button stays hidden.

## Settings
- App settings (⚙ on home and login screens): edit the server URL, test the connection, reset to default.
- Player settings (⚙ in the player): quality Original/480p/720p/1080p/2K/4K and audio Original/Stereo/Mono/5.1/7.1.
  Any non-Original choice uses Jellyfin HLS transcoding; choices are remembered.
