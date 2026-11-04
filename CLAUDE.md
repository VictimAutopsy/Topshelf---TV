# TopShelf: notes for working on it

TopShelf is Chad's own Android TV launcher for his Google TV Streamer (Java views + a WebView page), plus the same
APK on his phone. He tests changes on the TV and sends "running reports" (diagnostics) back.

## Repositories

- **Topshelf---TV** (this one, public): the full source. Nothing private ever goes here.
- **TopShelf-Updates** (private): `TopShelf.apk` + `latest.json` (what the TV's updater installs), `reports/`
  (diagnostic reports and screenshots the TV uploads), and `signing/` (the signing key and its password).
  Clone it next to this one: `../topshelf-updates`.

## One copy of the code (always)

GitHub is the only real copy. Several chats work on TopShelf, and an older chat may still have its own stale copy.
- **Before changing anything:** `git pull --rebase origin main` here, and `git -C ../topshelf-updates pull --rebase`
  (or clone both fresh). Never build from a copy that isn't up to date with GitHub.
- **Before building:** pull again (another chat may have pushed meanwhile), merge, and build from that.
- `tools/publish-update.sh` refuses to publish when the source has uncommitted changes, isn't pushed, is behind
  GitHub, or when the version isn't newer than the published one. Don't work around it: pull, rebuild, push, publish.

## Build and publish

1. Get an `android.jar` for API 34 (for example `android-34/android.jar` from github.com/Sable/android-platforms).
2. Tools: `sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk` (and node, for the JS check).
3. Pull both repositories, then find the last published version: `"code"` in `../topshelf-updates/latest.json`.
   Use the next number.
4. Build: `bash build-local.sh /path/to/android.jar <nextCode>`. It finds the key in `../topshelf-updates/signing/`.
5. Commit and push the source here, then publish: `tools/publish-update.sh "what's new, in plain words"`
   (copies the APK and `latest.json` into the updates repository and pushes it; the TV offers the update).

Quick compile check without building:
`javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $SDK -d /tmp/c -cp app/libs/media3-classes.jar $(find app/src/main/java -name '*.java')`

## Code rules

- Java 8 source: no lambdas (anonymous inner classes), minSdk 26 (nothing newer without a version check).
- Sizes are in rem: R = min(width/120, height/67.5); helpers `h.px()`, `h.at()`, `h.text(typeface, sizeRem, color)`.
- Diagnostics: `Diag.ev("...")`. Lines are scrubbed of keys and logins, but don't log full stream or addon links anyway.

## Working with Chad

- Present new ideas to him first and build only the ones he picks. Bug fixes and things he asked for, just do.
- He cares more about features and how they work than about new looks. Don't restyle things he didn't ask about.
- After a change: build, publish, and tell him briefly what changed and that it isn't tested on the TV yet.

## Security and privacy (always)

- Never commit the signing key, passwords, tokens, API keys, debrid keys, IPTV logins or personal details.
  The key lives only in the private updates repository.
- Debrid keys in addon links and IPTV usernames/passwords stay out of logs and reports.
- Passwords for outside services (Ring, Stremio, Reddit, Trakt) are never stored; only the tokens those services
  hand back are, on the TV. Ring's password passes through its setup page once.
- The phone link only obeys paired devices; setup pages need the one-time key from the TV's QR code.
- Page content from outside (addons, IPTV, EPG, ESPN, Reddit) is always escaped with `esc()` before it goes in HTML.
- No torrent engine, no recommending piracy addons, no use of Nuvio's private backend.
- Don't redraw brand logos (official logos from TMDB/ESPN are fine); designs don't imitate other companies' branding.
