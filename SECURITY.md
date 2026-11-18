# Security

Claude, an AI model made by Anthropic, wrote this app. Treat what's below as a careful self-review, not an independent audit. The player itself has its own: [plexamp-web's SECURITY.md](https://github.com/kamakazedan/plexamp-web/blob/main/SECURITY.md).

## Reporting a problem

Use **Report a vulnerability** in the repository's **Security** tab if it's there: only the owner sees it. Otherwise open an issue, without tokens or logs that contain them. Say which version you're on (**Settings**: long-press the app's icon).

## At a glance

- **Your Plex sign-in** is kept by the web app, in the WebView's storage for your player's address, as a browser keeps it. The app makes it (Plex's PIN sign-in), hands it to the player's page, and forgets it: it's never in the app's own storage, its log or its backups.
- **What the app keeps for itself**: your player's address, this phone's id with Plex, the saved copy of the player, and when it was last checked.
- **Who the app talks to**: your player (its address, to save the player and check it), plex.tv (to sign in), and wherever the player shows a cover from (for the lock screen). The player, in the WebView, talks to whom it would in a browser.
- **No tracking**: no analytics or third-party code. WebView's usage reports to Google and its Safe Browsing look-ups are off.
- **No backups**: nothing goes to Google's cloud backups or to a new phone.
- **Use another player** (Settings) signs you out and forgets everything: the address, the saved copy, and all the web app kept.

## Safeguards

- **The WebView stays on your player's address.** Anything else opens in your browser, and only when you tapped it. plex.tv's sign-in page always opens in your browser, never in the app.
- **The bridge to the page** (what's playing, saving files, sharing) is given by WebView only to pages at your player's origin, and only to the main page. It passes on what's playing, files you chose to save, and what you chose to share; the app sends back only the media buttons.
- **The saved player is served as the player served it**, with its Content-Security-Policy and other security headers, at its own address. It's saved only from your player's own address, only once its page is plexamp-web's (not a hotel Wi-Fi's sign-in page, say), and only whole.
- **Only your player's files are saved and served from the copy**: never anything from its service (`api/`), Plex (`pms/`) or the AI proxy (`ollama/`).
- **Plain `http://`** is allowed, as plexamp-web and Plex servers on your own network use it. An address that would send your sign-in over the internet unencrypted asks first. plex.tv is HTTPS only, and only certificates your phone trusts by itself are accepted (not ones added by apps or users).
- **Signing out** in the player also clears the artwork the WebView kept: its addresses have your sign-in in them.
- **Release builds** keep the page's messages out of the phone's log. The app logs no addresses.
- **No camera, microphone or location** for the page; no file access beyond the files you choose.

## Remaining risks

- **On `http://`** (as on your own network), your player's traffic, sign-in included, isn't encrypted, as in a browser.
- **The saved player is trusted as your player sent it.** Someone who could change your player's files, or its traffic over `http://`, could change the app's saved copy too, until the next update.
- **The signing key** decides which updates install. Keep it safe (see [README.md](README.md#signing)): anyone with it could make an update that replaces the app and reads what it keeps.
- **A phone that's rooted, or has a malicious accessibility app**, can read what any app keeps.
