# Changelog

What changed in each version of plexamp-web for Android, newest first. The
version you're running is in **Settings** (long-press the app's icon).

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
