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
  beside them. OK on any setting with choices (two included) pops a panel out on the right (the sections shrink to
  icons) with what it does and its choices: ▲▼ and OK choose, Back closes. Nothing changes with ◀ ▶ or in place on a
  TV (he didn't want to step through choices). The panel's words come from `assets/setinfo.js`: only write what the
  code really does, and give a new setting an entry there too — one whose `cycle` calls `B.` needs its `opts` listed
  there, or it can't get the panel (see `nhSheetChoices` in nativehome.js for how a choice is set).
- The flight recorder only counts a frame as late when it's in a run of frames (the one before it under 60 ms earlier):
  lone redraws report long times that aren't stutter. Numbers from before 1.0.410 over-count for that reason.
- On a phone (`h.phone`, `!act.isTv()`) TopShelf is a standalone app for movies, shows, sports and Live TV, not a launcher:
  no apps, app bar or All apps, and upright the tabs are always a full-width bar along the bottom (`h.BOT` rem to keep
  clear above it, `h.TOP` rem for the camera cutout). Movies and TV Shows slide freely under a finger there (`NativeHome.Free`: `touchDown`, `drag`, `dragEnd`, implemented by `NativeVod` and `TitleView`; screens without it still get the remote's steps from `NativeHome.touch`), and upright the screen is 46 rem wide. A phone is browsed upright only (Chad chose it); a movie, episode or channel full screen turns it sideways (`h.videoTurn`), and Home is not made again for that turn. The phone's Home is a `NativeVod` page too (`vodH`, `home` flag: featured title with Watch now / More details, Continue watching, rows of movies and shows), not the photo Home. A phone never hands a title to Nuvio or Stremio (Chad: it should work like the TV, with his debrid addon): it keeps its own addons, added from Settings › Addons › Add addons, which opens the addons page in the phone's browser from its own LinkServer (`/addons?here=1`). An upright phone's Live TV has no time grid (Chad's call, 1.0.446; the TV keeps its traditional guide): under the preview (which starts below the camera cutout, `PV_TOP`) the groups are a row of chips (`ChipsView`: tap opens one, hold is its menu) and the channels an "on now" list (`NowView`: number, logo, the show with how far through it is, what's next or the live score; a tap plays it in the preview and again goes full screen, hold is the channel's menu, the arrow at a row's end opens what's on later there, and a tap on a later show is its menu for a reminder or recording). `NativeLive.nowList` switches it; the grid is still made and kept hidden, and the list follows it through `GuideView`'s `invalidate`/`build`/`reveal`/`setVisibility`. The phone's tabs are one flat dark bar flush with the bottom edge, the open tab white under a line of the look's highlight (`NativeHome.ThumbBar`, picked from the same sketch). Home's remote, Search and Settings buttons there are plain icons at the top right on the featured picture over a soft shade, with no bar, weather or clock (he picked it from a sketch, 1.0.450; the rounded bar looked bad to him). Watching a channel full screen there, `NativeLive.fullDrag` takes every slide as one thing (1.0.444): on the timeline it scrubs and jumps on letting go (a tap there jumps too), up/down is the next/previous channel, from the left brings the channel list, to the left the channel before; nothing changes channel while the toolbar's up. The channel list and Recordings choose what's under the finger (`ZapView.rowAt`, `RecView.itemAt`) and Recordings slides freely. Sports there has a page of its own (`NativeSports.PhoneView`, 1.0.454; Chad picked it from a sketch): below the camera cutout the tabs sit on top (sliding sideways; no score strip), and under them one page slides up and down: the best game as a compact scoreboard with Watch on… and Game page buttons (`paintFeatP`), your teams as a sideways row of small cards (`paintTeamP`), the leagues as sliding chips, and the games as a list, one a row (`paintRowP`; fantasy players stay as cards, two across). Its pictures are painted off the main thread and kept with the TV's (`pic`). The TV's pieces are still made under it but not painted; the remote's ring is hidden. Standings and a game's page have phone layouts too (1.0.464, picked from a sketch): both draw at 1.3× the phone's rem (their own `R`), Standings with the leagues as sliding chips, the views as one full-width switch, one conference at a time as tabs (`pcf`, `allPanes`), a sliding table (it takes Sports' slides: `drag`/`dragEnd`) and a two-line foot; a tap there chooses a team and a tap on the chosen one opens its page (1.0.474, picked from a sketch: `drawTeamPhone` — the team across the top in its colour with the back arrow, logo large and centred, record, place and W/L squares; the leaders as three small cards; the season as one list by month (`seasonRow`), the next game marked with a Watch button, months before the last couple of results folded into an "Earlier this season" row; it slides (`tpY`), a tap on a game is the TV's OK); `GameView` is a `Free` with a back bar, the compact scoreboard (`headerPhone`) and full-width buttons (`buttonsPhone`, `tapAt`). Redone for phones area by area (all 7 steps done, 1.0.474: Sports done, a team's page from Standings included; Live TV done bar multiview, which is still the page's; title pages' smallest canvas text is a size up on a phone (`Panel.ph`, floor 1.3 rem) and a person's page slides freely too (1.0.462); Search done (1.0.468, picked from a sketch: `NativeFind` is a `Free` there — below the cutout a back arrow, the box with a clear × and a highlight line while typing, the mic; the results slide up and down and poster rows sideways; channels and shows are lists, one a row (`phoneRow`), shows split into On now / Later by `NativeSearch.phoneProgs`, a later show's bell sets its reminder (`NativeSearch.bell`); the finger that puts the keyboard away goes on to slide or tap); Settings done (1.0.470, picked from a sketch: `NativeSheet` with `phn` is a page of its own and a `Free` — a back arrow and the name below the cutout, the sections as a list with icons and a waiting update as a card, a section's settings as plain rows; a setting with choices raises a sheet from the bottom (`phoneChoices`, same `nhSheetChoices`/`nhSheetPick` as the TV's panel; a setting's own menu goes there too). The page leaves out TV-only rows on a phone (`PHONE_HIDE` in index.html: Home's photos, TV layout, widgets, weather, screensaver, bedtime) and sections left empty); the player done (1.0.472, picked from a sketch: sideways, `Bar.drawPhone` lays the standard controls over the whole screen — back arrow, Subtitles/Audio/More along the top, back 10/play/ahead 30 in the middle, the timeline and Episodes/From the start/Next episode along the foot, places in `Bar.hits`; `VodPlayer.phoneTap`: a tap on the picture shows or hides the controls (Skip intro is tapped itself), two quick taps at a side jump back 10 s / ahead 30 s; `VodPlayer` is a `Free` so dragging along the timeline scrubs)); the
  TV's screens must not change with it.
- While something loads, show its shape, not "Loading…": `Skel` (a row of placeholder cards as a view, or
  `Skel.block()` with `Skel.tone()` on a canvas, asking for the next frame while it loads). No emoji on screen: they
  come in the TV's own cartoon style. Draw a `HomeIcons` icon instead (`HomeIcons.at()` on a canvas,
  `HomeIcons.before()` in a TextView); ⚠ and ☁ are written with `\uFE0E` after them so they stay plain text signs.
- The guide's GAME tag (Chad picked it from mock-ups, 1.0.432): a show carrying one of his teams' games gets a small blue tag
  before its title (filled while the game is on, an outline before), and while it's on the tile's second line is the
  score and period in place of the times. `SportsData.guideGames` finds the shows (same matching as Sports' "Watch on…"),
  `NativeLive.askGames` asks every 30 s while the guide is up. He likes the guide's layout and look as they are: he turned
  down a picture-first redesign and three new looks.
- Game and fantasy alerts (`SportsAlert`, 1.0.442) are one small bar, 3.9 rem tall, in the chosen look: a tag naming what
  happened (the look's highlight; On Air's red bar with the slanted end), logos, the score or matchup, a short detail,
  "OK · Watch". Chad uses the On Air look (he calls it the news theme) and turned down bigger cards and a glass pill as
  blocking too much and not fitting it. Where it shows is his setting, Settings › Sports › Alert position (`S.alertPlace`):
  top right (default) or bottom middle. Mock up anything on screen in the look he uses.
- Holding Back on Live TV's guide (0.6 s) goes full screen on the channel in the preview, or the highlighted one (Chad asked,
  1.0.444): there Back acts on release (`NativeHome.backHold`, `NativeLive.holdBack`); a short press is Back as before.
- On the TV a channel going full screen from the guide's preview grows out of it, and Back shrinks it into the preview
  before the guide returns (Chad asked, 1.0.458): `NativePlayer.zoom` moves and scales the full-screen picture only (its
  surface is never resized), `NativeLive.exitFullZoom` / `leaving`. Not on a phone (it turns sideways), nor for a
  recording, a rewound channel or the external player.
- A live channel standing still with plenty downloaded (`NativePlayer.frozenCheck`): unless its sound is Dolby it goes to
  the live point (`seekToDefaultPosition`, no restart) twice in two minutes, then starts again the same way; the sound
  steps (and sound off for a day) are only for Dolby sound (1.0.476, after Comedy Central froze on Chad and stalls went
  from 4 to 100 a day). The error tries reset after a quiet minute. The flight report counts each kind ("live freeze ·
  went to the live point", "live · fell behind the stream", "live picture stopped · …"). Stream info's decoding ways,
  last stall and last error are the channel on screen's.
- Changing live channel keeps the player (1.0.478, Chad picked it): `NativePlayer.canKeep` puts the next channel in the same
  player with its decoder kept (foreground mode) when the decoding way and sound setting match; otherwise, after an error or
  the software decoder, a new one is made as before. No picture 4 s after the video arrives → made again (`keptCheck`); twice
  in a day turns keeping off for a day (pref `nvKeepOff`). `LiveWarm` reaches the highlighted channel's server ahead (a HEAD
  for "/", never a stream: Chad's provider allows one stream at a time, so nothing may open a second one) when Live TV opens,
  the guide moves, or the full-screen bar or channel list comes up. The flight report splits each start: "live start ·
  server answered" (or "· reached ahead"), "· video arriving", "· decoder ready", "· decoder setup took", plus counts of
  "player kept" / "new player" and failed requests before the picture. Compare those before changing anything else here.
- Freezes: `FreezeWatch` notes the main thread's stack when it hasn't answered for 3 s (prefs `lastFreeze`, a `freeze:` event),
  and the crash report carries it plus the main thread's part of Android's trace (`MainActivity.traceOf`; it used to be cut
  before any thread).
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
