# Changelog

What changed in each version of plexamp-web for Android, newest first. The
version you're running is in **Settings** (long-press the app's icon).

## 0.2.3 - 2026-11-19

### Added

- **Check now**: tap **Up to date** in Settings › Downloads to check your
  playlists for changes straight away, on the network you allow, or tap the
  message that your server couldn't be reached to try it again now (it needs
  plexamp-web 0.40.2 or later).

### Changed

- **Download progress counts as each playlist does**: Settings › Downloads and
  the notification count every track on the phone ("76 of 78 tracks"), not only
  those downloaded this time ("1 of 3"), so the numbers agree.
- **A playlist's size includes its covers**, as what downloads use does, so the
  two agree.

## 0.2.2 - 2026-11-19

### Fixed

- **A new version of the player shows up without closing the app.** The player
  on screen kept the version it opened with till the app was closed completely
  (Back only hides it, and it keeps running while music plays), so a new version
  saved meanwhile waited unseen. Now the player moves to it as you open the app,
  or as soon as it's saved if you haven't touched anything since. With music
  playing, **Reload** is offered instead, so the music isn't cut off, and the
  next time you open the app without music playing it moves by itself. The
  player brings its queue back, paused where it was.
- **Settings say why a check for a new version failed**: no answer, refused
  (with the code your player, or something in front of it, answered), something
  else answered (a sign-in page put in front of the player, say), and so on.

## 0.2.1 - 2026-11-19

### Changed

- **The app's icon is in Plex's colours**: gold on charcoal, in the same shape
  as before. Its Settings shortcut too.

## 0.2.0 - 2026-11-18

### Added

- **Downloads**: playlists kept on the phone, to play with no connection or
  without using mobile data. **Download** on a playlist's page in the player
  (it needs plexamp-web 0.40.0 or later), or **Settings › Downloads** there,
  with:
  - **Download quality**: Original (your files as they are, to begin with),
    or High, Normal or Data saver (320, 192 or 128 kbps), converted by your
    server.
  - **Download on**: Wi-Fi only (to begin with), or mobile data too.
  - **Play downloads**: Always (to begin with), On mobile data, or Only
    offline. With your server out of reach they play whatever it says.
  - The phone's free space, what downloads use, and each playlist's size. A
    playlist that won't fit is refused, saying how much it needs.
- **Kept up to date** with your server, on the network you allow, every few
  hours and as the player opens, even with the app closed: tracks added to a
  playlist are downloaded, those taken off removed, and a file changed on the
  server downloaded again. A notification shows how it's getting on, and opens
  Settings › Downloads. (The app asks once to show notifications, as the first
  download starts.)
- **Offline**, the player's Playlists show those on the phone, with their
  covers, and play them.
- **Signing out** in the player, switching user, or **Use another player**
  removes downloads, and the sign-in the app kept them up to date with.

## 0.1.0 - 2026-11-18

The first version.

### Added

- **Setting up**: the app asks for your player's address, and checks it's a
  plexamp-web player (following it where it's moved, to https:// say, but never
  from https:// to http://). Where it ends up on another server, or where it
  would send your sign-in over the internet unencrypted, it asks first.
- **Sign in with Plex** on plex.tv, in your phone's browser, so signing in with
  Google, Apple or a password manager works. The sign-in is handed to the
  player, which then asks for your server and library, as it does in a browser.
  **Other ways to sign in** opens the player's own sign-in page, for its
  offline password or a server's token.
- **The player is saved on the phone**, every file of it, and opens from there:
  with no connection, on mobile data without using it for the player, and when
  your player itself can't be reached.
- **New versions on Wi-Fi**: the app checks when it opens, when the phone joins
  Wi-Fi, and every few hours in the background. Only what's changed is
  downloaded, a new version is put in place whole or not at all, and it's used
  the next time the player opens (or straight away, with **Reload**). A file
  the player can do without (hls.js, an icon) that doesn't come because the
  connection dropped is kept as it was.
- **Playing on** with the screen off or in another app, with what's playing on
  the lock screen and in a notification, and controls for headsets, watches and
  cars. Music pauses when headphones are unplugged or a Bluetooth headset or car
  disconnects.
- **Saving playlist files** asks where to save them, and **Share** (Missing
  tracks) opens Android's share sheet.
- **Signing out** in the player brings back the app's sign-in, and forgets the
  artwork the phone kept.
- **Settings** (long-press the app's icon): your player, the saved copy's
  version, checking for an update now, and **Use another player**, which signs
  you out and forgets everything.
