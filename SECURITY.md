# Security

Claude, an AI model made by Anthropic, wrote this app. Treat what's below as a careful self-review, not an independent audit. The player itself has its own: [plexamp-web's SECURITY.md](https://github.com/kamakazedan/plexamp-web/blob/main/SECURITY.md).

## Reporting a problem

Use **Report a vulnerability** in the repository's **Security** tab if it's there: only the owner sees it. Otherwise open an issue, without tokens or logs that contain them. Say which version you're on (**Settings**: long-press the app's icon).

## At a glance

- **Your Plex sign-in** is kept by the web app, in the WebView's storage for your player's address, as a browser keeps it. The app makes it (Plex's PIN sign-in), hands it to the player's page, and forgets it: it's never in the app's own storage, its log or its backups.
- **Downloads** are the one exception: while you have any, the app keeps the player's sign-in to your server (the token it uses there), encrypted with a key the Android Keystore holds, so it can keep them up to date with the player closed. It's gone with the last download, and on signing out.
- **What the app keeps for itself**: your player's address, this phone's id with Plex, the saved copy of the player, and when it was last checked. With downloads: their files, playlists and covers, their settings, a fingerprint of whose they are (not your name), and the sign-in above.
- **Who the app talks to**: your player (its address, to save the player and check it), plex.tv (to sign in), wherever the player shows a cover from (for the lock screen), and, for downloads, your Plex server. The player, in the WebView, talks to whom it would in a browser.
- **No tracking**: no analytics or third-party code. WebView's usage reports to Google and its Safe Browsing look-ups are off.
- **No backups**: nothing goes to Google's cloud backups or to a new phone.
- **Use another player** (Settings) signs you out and forgets everything: the address, the saved copy, downloads, and all the web app kept.

## Safeguards

- **The WebView stays on your player's address.** Anything else opens in your browser, and only when you tapped it. plex.tv's sign-in page always opens in your browser, never in the app.
- **The bridge to the page** (what's playing, saving files, sharing, downloads) is given by WebView only to pages at your player's origin, and only to the main page. It passes on what's playing, files you chose to save, what you chose to share, and what you do in Settings › Downloads; the app sends back only the media buttons and how downloads are getting on.
- **Downloads' sign-in** is encrypted (AES-GCM, with a key the Android Keystore never lets out), in storage that isn't backed up, and only while something's downloaded. Signing out, switching user, another server, removing the last download, or **Use another player** removes it with the downloads. A copy that can't be read (a restored phone, say) is thrown away, and the player gives it again when it opens.
- **Downloads go only to your server**, at the addresses the player uses: over `https://`, whose certificate is checked, or through your player itself (its `pms/`). Never plain `http://` elsewhere: away from home, an address on your home network could be anyone's. Only an answer from the server the player chose (its id) is taken, and redirects aren't followed.
- **Downloaded files** are kept in the app's own storage, which other apps can't read and nothing backs up, and are played only by the player's page, at its own address.
- **The saved player is served as the player served it**, with its Content-Security-Policy and other security headers, at its own address. It's saved only from your player's own address, only once its page is plexamp-web's (not a hotel Wi-Fi's sign-in page, say), and only whole.
- **Only your player's files are saved and served from the copy**: never anything from its service (`api/`), Plex (`pms/`) or the AI proxy (`ollama/`).
- **Plain `http://`** is allowed, as plexamp-web and Plex servers on your own network use it. An address that would send your sign-in over the internet unencrypted asks first. plex.tv is HTTPS only, and only certificates your phone trusts by itself are accepted (not ones added by apps or users).
- **Signing out** in the player also clears the artwork the WebView kept: its addresses have your sign-in in them.
- **Release builds** keep the page's messages out of the phone's log. The app logs no addresses.
- **The release key signs releases, and nothing else.** Only CI's release job reads it, for a version's tag, and that job runs none of the build's code: Gradle and its plugins never see the key. Builds of branches and pull requests are signed with a key made for each run, so none can replace the app. Each release lists its APK's SHA-256 and its signing certificate's.
- **No camera, microphone or location** for the page; no file access beyond the files you choose.

## Remaining risks

- **On `http://`** (as on your own network), your player's traffic, sign-in included, isn't encrypted, as in a browser.
- **The saved player is trusted as your player sent it.** Someone who could change your player's files, or its traffic over `http://`, could change the app's saved copy too, until the next update.
- **The signing key** decides which updates install. Keep it safe (see [README.md](README.md#signing)): anyone with it could make an update that replaces the app and reads what it keeps. Anyone who can push a version tag to the repository can have CI sign a release with it.
- **A phone that's rooted, or has a malicious accessibility app**, can read what any app keeps: downloaded music too, which isn't encrypted.
