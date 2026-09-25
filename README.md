# DarkRoot Downloader (Android / Kotlin)

Native port of the Python/Tkinter downloader prototype, now expanded to
handle any link its backend (yt-dlp) supports - not just YouTube. Kotlin +
XML views
(no Compose), Gradle-only, matches your usual AndroidIDE setup.

## How it downloads

Uses [`youtubedl-android`](https://github.com/yausername/youtubedl-android),
a yt-dlp + ffmpeg port for Android. Unlike the Pydroid3 version, ffmpeg is
bundled with the library itself, so there's no "ffmpeg not found" issue -
merging and mp3-equivalent (m4a) extraction just work.

## Where files are saved

- **Android 10+ (API 29+):** saved via `MediaStore.Downloads`, which lands
  in the real public **Downloads** folder - visible in Files/Gallery/any
  other app, no special permission needed.
- **Android 9 and below (API 28-):** written directly to
  `Environment.DIRECTORY_DOWNLOADS`, requesting `WRITE_EXTERNAL_STORAGE`
  at runtime (the app asks for this the first time it opens).

Internally, yt-dlp first downloads into the app's own private working
directory (always writable, zero permissions needed), then the finished
file is copied/moved into the public Downloads location. This mirrors the
"download to a safe scratch spot, then publish" pattern and avoids
permission failures interrupting an in-progress download.

## In-app file list

"Downloaded videos" lists everything the app has saved to Downloads.
Tap an item, then:
- **Open selected video** - launches it in whatever player/app is
  registered for that file type (this works reliably here, unlike the
  Pydroid3 version, because this is a real installed app with proper
  `FileProvider`/`Intent` permissions instead of a sandboxed script).
- **Share selected video** - standard Android share sheet.

## Building via GitHub Actions

1. Push this whole folder to a repo under `darkrootiding-hub` (or wherever
   you keep your other DarkRoot apps).
2. The workflow at `.github/workflows/android-build.yml` runs on every
   push to `main`, or manually via the "Run workflow" button
   (`workflow_dispatch`).
3. It does **not** need a committed Gradle wrapper - it installs Gradle
   8.4 directly on the runner via `gradle/actions/setup-gradle`, sidestepping
   the slow-internet wrapper download problem you hit before. This only
   affects the CI runner; you can still generate a local wrapper in
   AndroidIDE if you want to build on-device too.
4. Grab the built debug APK from the workflow's **Artifacts** section
   (`yt-downloader-debug-apk`).

## Building locally in AndroidIDE

If you want a local Gradle wrapper for AndroidIDE, generate it on-device
(same workaround you used for the FIFA Live app):
```
gradle wrapper --gradle-version 8.4
```

## Package / structure

- `applicationId`: `com.darkroot.ytdownloader`
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`
- AGP 8.1.4, Kotlin 1.9.22, Java 17
- Single Activity (`MainActivity.kt`), single layout (`activity_main.xml`),
  ViewBinding enabled (no `findViewById` boilerplate)

## Supported sites

The download engine (`youtubedl-android`, wrapping yt-dlp) supports
1000+ sites out of the box - YouTube, Vimeo, SoundCloud, TikTok,
Twitter/X, Facebook, Instagram, Twitch, Reddit, and many more. No
per-site code was needed on the Android side; the UI/clipboard-detect
logic was simply broadened from YouTube-only to any pasted link.

## Legal note

Same as the Python version, now covering every platform above: this is
for content you own or have rights to download (your own uploads,
Creative Commons, personal backups where permitted). Respect each
platform's Terms of Service and copyright law in your jurisdiction -
these vary by site, and some platforms restrict downloading more
strictly than others.
