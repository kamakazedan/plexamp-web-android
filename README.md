# plexamp-web for Android

An Android app for [plexamp-web](https://github.com/kamakazedan/plexamp-web): your player, saved on your phone, so it opens with or without a connection, and keeps playing with the screen off.

> [!IMPORTANT]
> **Claude, an AI model made by Anthropic, wrote all of this project's code**, at the direction of the repository's owner. None of it was written by hand, and it hasn't been independently audited. Read [SECURITY.md](SECURITY.md) and review the code before you rely on it.

> Unofficial project. Not made, endorsed or supported by Plex.

## What it does

1. **Asks for your player's address**, the one you open plexamp-web at, and checks it's a plexamp-web player.
2. **Sign in with Plex**, on plex.tv in your phone's browser (so Google, Apple and password managers work), then come back to the app.
3. **Saves the player on the phone and opens it.** You choose your server and library, as in a browser.

From then on:

- **It opens from the phone**: with no connection, without using mobile data for the player, and when your player itself can't be reached (your Plex server still has to be, to play).
- **New versions come on Wi-Fi**: it checks when it opens, when the phone joins Wi-Fi, and every few hours. A new version is used as you open the app (with music playing, **Reload** offers it instead, so the music isn't cut off).
- **It plays on** with the screen off or in another app, with the lock screen, the notification, headsets, watches and cars showing and controlling what's playing. Unplugging headphones (or a Bluetooth headset or car going) pauses it. (This needs the player's **Media keys and lock screen** setting, which is on to begin with.)
- **Downloads**: playlists kept on the phone, to play with no connection or without using mobile data. **Download** on a playlist's page in the player, or **Settings › Downloads** there, which has the quality, Wi-Fi only or mobile data too, when to play them, and the space they take. They're kept up to date with your server on the network you allow, even with the app closed, each playlist showing what's happening to it (checking for changes, downloading, and how far, or waiting for Wi-Fi), and **Up to date** there checks now. Each server's are kept apart: switch to another server and the first one's stay on the phone, marked with that server's name, and still play from it; they're kept up to date again once you're back on it. While music plays, downloads go more gently, to leave the phone's Wi-Fi (which Bluetooth shares) to the music.
- **Updates**: the player's **Settings › About** shows the app's version too, and **Check for updates** checks both: the player on your server (a new version opens straight away, unless music's playing) and the app on GitHub (a newer one can be downloaded from there). The app asks GitHub only when you tap it.
- **Saving playlist files** and **sharing** work as in a browser.
- **Signing out** in the player brings back the app's sign-in.

**Settings**: long-press the app's icon, then **Settings**. It shows your player, the saved copy's version (with **Check for an update**, and why the last check failed where it did), and **Use another player**, which signs you out and forgets everything, downloads included.

## Install

Download the APK from the newest of the [Releases](../../releases) (under **Assets**) on your phone, and open it. Allow your browser or files app to install apps when asked. Each release lists the APK's SHA-256, and its signing certificate's, to check it by.

An APK installs over an earlier one only if both were signed with the same key: see [Signing](#signing). The APKs each run of the [Build workflow](../../actions/workflows/build.yml) keeps (its **Artifacts**) are for testing: each is signed with a key made for that run, so it installs only where the app isn't installed already.

| | |
| --- | --- |
| Android | 8.0 or later |
| plexamp-web | Any version: the app saves whichever your player has |
| Android System WebView | Kept up to date by Google Play. Without its newer features, everything but the lock screen controls and saving files still works. |

## How it works

The player runs in a WebView at its own address, so the web app works exactly as it does in a browser, with its own sign-in and settings.

- **The player's own files** (its page, scripts, styles and icons) come from the copy saved on the phone. The app finds them as a browser does: from the page, each script's imports, the styles and the manifest, and paths the scripts load themselves.
- **Everything else** goes to the network, as in a browser: the player's service (`api/`), Plex (through `pms/` or directly), and artwork.
- **A new version** is saved alongside the old one, file by file (only what's changed is downloaded), and put in place in one step once every file it needs has come. The page on screen keeps the version it opened with till the app's next opened with no music playing (or **Reload**), and is then reloaded onto the new one: the player brings its queue back, paused where it was.
- **Signing in**: the app makes a Plex PIN, you claim it on plex.tv, and the token it gets is handed straight to the player's page, where the web app keeps it. The app itself doesn't store it (but see Downloads, below).
- **What's playing**: the web app's Media Session (the same that gives browsers their media keys) is passed to Android's.
- **Downloads**: the player gives the app its server's addresses and its sign-in to the server each time it opens (and as it switches servers). While something's downloaded from that server, the app keeps them, encrypted, and downloads in the background with Android's WorkManager: each playlist as the server has it now, each track as a file (or converted, at a lower quality), and its album's cover, at a megabyte a second at most while music plays. Each server's downloads are kept apart (`downloads/servers/<its id>/`): only the server the player's on is kept up to date, and its sign-in is the only one kept. The player plays them from the app, at the player's own address (`__plexamp-web-android/downloads/`, with `?server=` for another server's), as Settings › Downloads says; the lock screen shows a cover from there from the phone too.
- **What the app relies on in the web app** (where it keeps the sign-in, its pages, how it saves files, what it tells the app for downloads) is listed in plexamp-web's [docs/android-app.md](https://github.com/kamakazedan/plexamp-web/blob/main/docs/android-app.md), and plexamp-web's CI fails where one changes.

## Building

Android Studio (open the folder), or:

```sh
./gradlew assembleRelease     # app/build/outputs/apk/release/
./gradlew testDebugUnitTest   # the tests
```

It needs JDK 17 or later and the Android SDK (platform 36). CI builds every push and pull request, runs the tests and lint, and keeps the APK as an artifact, for testing. A tag like `v0.1.0` publishes a release: the APK, signed with the release key, with its SHA-256 and its changelog entry. The tag has to match the version in `app/build.gradle.kts`, which has to match the newest entry in [CHANGELOG.md](CHANGELOG.md). Push the tag (`git tag v0.1.0` then `git push origin v0.1.0`), or make the release on the [Releases](../../releases) page (**Draft a new release**, a new tag like `v0.1.0`, **Publish release**): CI adds the APK to it a few minutes later.

One test saves the real plexamp-web's files as the app does, from a checkout of it: CI sets `PLEXAMP_WEB_DIR` to its `web/` folder. Locally:

```sh
PLEXAMP_WEB_DIR=../plexamp-web/web ./gradlew testDebugUnitTest
```

### Signing

Android only installs an update signed with the same key as the app already installed. CI signs releases with the release key, and nothing else: only its release job reads the key, for a version's tag, and that job runs none of the build's code. Keep its secrets in an environment named `release` (**Settings › Environments**) that only tags like `v*` may use (its **Deployment branches and tags**), so nothing else can read them. The repository's own secrets (**Settings › Secrets and variables › Actions**) work too. It reads these:

| Secret | What it is |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The keystore, base64-encoded on one line (below) |
| `ANDROID_KEYSTORE_PASSWORD` | Its password |
| `ANDROID_KEY_ALIAS` | The key's alias: `plexamp-web`, made as below |
| `ANDROID_KEY_PASSWORD` | The same password: keytool gives the key the keystore's |

To make one, outside the repository's folder, with the `keytool` from JDK 17 or later:

```sh
cd ~
keytool -genkeypair -keystore plexamp-web-release.jks -storetype PKCS12 -alias plexamp-web \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=plexamp-web-android"
base64 -w0 plexamp-web-release.jks          # Linux: prints the line for ANDROID_KEYSTORE_BASE64
base64 -i plexamp-web-release.jks | pbcopy   # macOS: copies it instead
```

On Windows, `keytool` usually isn't on the PATH, so use Android Studio's own (this is where Android Studio installs by default). In PowerShell:

```powershell
cd $HOME
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -keystore plexamp-web-release.jks -storetype PKCS12 -alias plexamp-web -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=plexamp-web-android"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\plexamp-web-release.jks")) | Set-Clipboard
```

`keytool` asks for one password, twice: it's both the keystore's and the key's, so it goes in both password secrets. The `pbcopy` and `Set-Clipboard` lines copy the base64 straight to the clipboard, so no copy of it is left in a file. (`.gitignore` keeps keystores and `.b64` files out of the repository, but it's safest to keep them out of its folder too.)

Keep a copy somewhere safe, such as a password manager: an app signed with another key can only be installed after uninstalling this one, which signs you out. Without the secrets, a tag's release stops before publishing anything. (`ANDROID_KEY_ALIAS` can be a variable rather than a secret, so its words aren't hidden in the logs, or left out where the keystore has one key. `ANDROID_KEY_PASSWORD` can be left out where it's the keystore's password.)

## The code

```
app/src/main/java/io/github/kamakazedan/plexampweb/
  PlexampWebApp.kt       the app: its parts, and forgetting a player
  Prefs.kt               what the app keeps for itself (never a Plex token)
  setup/                 the player's address: tidying it, and checking it's plexamp-web
  auth/PlexSignIn.kt     Plex's PIN sign-in
  gui/                   the saved copy of the player: finding its files (GuiRefs), saving them
                         (GuiUpdater, GuiStore), and when (GuiUpdates, GuiUpdateWorker)
  player/                the WebView: its files from the saved copy (GuiServer), where it may go
                         (PlayerClient), handing the sign-in over (Handoff), and the bridge to the page
  media/                 what's playing, as Android's media session, and the playback service
  downloads/             playlists kept on the phone: keeping them up to date (DownloadSync, DownloadWorker,
                         gently while music plays: Pacer), what's kept (DownloadStore, each server's apart:
                         DownloadShelves), the server (PlexClient), the page's questions (DownloadBridge)
                         and their files (DownloadServer), and the sign-in, encrypted (TokenVault)
  ui/                    the screens: MainActivity (setting up, and the player) and Settings
  net/                   requests, and whether the phone's on Wi-Fi
app/src/main/assets/page-bridge.js   the page's end of the bridge
app/src/test/                         the tests
```

## Licence

MIT: see [LICENSE](LICENSE).
