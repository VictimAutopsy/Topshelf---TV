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
3. Pull both repositories, then take the version number to build from `"next"` in `../topshelf-updates/latest.json`
   (older files without it: `"code"` + 1). Each publish also uses the number after it, for the safety net below.
4. Build: `bash build-local.sh /path/to/android.jar <nextCode>`. It finds the key in `../topshelf-updates/signing/`.
5. Commit and push the source here, then publish: `tools/publish-update.sh "what's new, in plain words"`
   (copies the APK and `latest.json` into the updates repository and pushes it; the TV offers the update).
   Set `TOPSHELF_SDK=/path/to/android.jar` when publishing: the script then also rebuilds the previous version with
   the next number as `TopShelf-fallback.apk` (the safety net: TopShelf goes back to it by itself if a new version
   keeps crashing as it starts, and from Settings › Updates › Go back to the previous version).
6. TopShelf sends a daily flight-recorder summary to `reports/flight/<day>-tv.json` in the updates repository
   (timings, buffering, smoothness, memory, crashes, which screens are used; never what's watched). Read those
   before and after a change to see what it did.
   Since 1.0.390 it also has `slowFrames`: for each screen, which part of making a frame took longest when one was
   slow ("title page · drawing", "… · the app was busy with something else"), and frames over 0.1 s are in a
   report's events as "slow frame: …". Use it to find what to fix before changing draw code for speed.

Before publishing, run the checks (GitHub runs them on every push too, see .github/workflows/checks.yml):
`bash tools/checks/run-all.sh /path/to/android.jar` — compiles, every page script parses, every setting is in a
Settings section, report scrubbing removes keys and logins, and the page opens without a script error.

Quick compile check without building:
`javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath $SDK -d /tmp/c -cp app/libs/media3-classes.jar $(find app/src/main/java -name '*.java')`

## Code rules

- Java 8 source: no lambdas (anonymous inner classes), minSdk 26 (nothing newer without a version check).
- Sizes are in rem: R = min(width/120, height/67.5); helpers `h.px()`, `h.at()`, `h.text(typeface, sizeRem, color)`.
- Colours, small text sizes and timings come from the shared steps in `Ui.java` (`Ui.WHITE_60`, `Ui.TEXT_M`, `Ui.SLIDE`…): use the
  nearest step, not a new number. `python3 tools/tokens.py <file.java>` swaps a file's literals for them. Every screen
  has been through it (1.0.386); text drawn on a canvas (Sports, Standings, parts of the title page) still has its own sizes.
- Settings on the TV (NativeSheet) is Chad's design: the sections in full down the left with the chosen one's settings
  beside them. OK on a setting with three or more choices pops a panel out on the right (the sections shrink to icons)
  with what it does and its choices: ▲▼ and OK choose, Back closes. Nothing changes with ◀ ▶ on a TV (he didn't want
  to step through choices sideways); two choices flip on OK. The panel's words come from `assets/setinfo.js`: only
  write what the code really does, and give a new setting an entry there too.
- The flight recorder only counts a frame as late when it's in a run of frames (the one before it under 60 ms earlier):
  lone redraws report long times that aren't stutter. Numbers from before 1.0.410 over-count for that reason.
- On a phone (`h.phone`, `!act.isTv()`) TopShelf is a standalone app for movies, shows, sports and Live TV, not a launcher:
  no apps, app bar or All apps, and upright the tabs are always a full-width bar along the bottom (`h.BOT` rem to keep
  clear above it, `h.TOP` rem for the camera cutout). Movies and TV Shows slide freely under a finger there (`NativeHome.Free`: `touchDown`, `drag`, `dragEnd`, implemented by `NativeVod` and `TitleView`; screens without it still get the remote's steps from `NativeHome.touch`), and upright the screen is 46 rem wide. A phone is browsed upright only (Chad chose it); a movie, episode or channel full screen turns it sideways (`h.videoTurn`), and Home is not made again for that turn. The phone's Home is a `NativeVod` page too (`vodH`, `home` flag: featured title with Watch now / More details, Continue watching, rows of movies and shows), not the photo Home. A phone never hands a title to Nuvio or Stremio (Chad: it should work like the TV, with his debrid addon): it keeps its own addons, added from Settings › Addons › Add addons, which opens the addons page in the phone's browser from its own LinkServer (`/addons?here=1`). Being redone for phones area by area (1.0.406: step 4 of 7, title pages slide freely but their canvas text is still TV-sized; next: Live TV, Sports/Search/Settings, the player); the
  TV's screens must not change with it.
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
