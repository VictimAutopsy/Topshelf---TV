# Building and installing TopShelf

An Apple TV–style home screen for Google TV (built for the Google TV Streamer).
A full-screen photo from a subreddit rotates behind a frosted dock of your apps.

## Build it

TopShelf builds without Gradle or Android Studio (Ubuntu/Debian):

    sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-17-jdk
    ./build-local.sh /path/to/android-34/android.jar <versionCode>

The build is signed with a private key that is **not** in this repository (see `build-local.sh`):
clone the private updates repository next to this one, or point `TOPSHELF_KEYSTORE` / `TOPSHELF_KS_PASS` at
the key. Every build has to use that same key, or Android won't install it over the copy on the TV.

## Phones

The same APK runs on an Android phone (landscape). Tap opens, hold opens the app menu,
swipe up/down moves between rows, tap the photo for photo mode, swipe left/right there to browse.

## Install

**Option A: from a computer (adb over Wi-Fi)**

1. On the Streamer: Settings → System → About → click *Android TV OS build* 7 times.
2. Settings → System → Developer options → turn on **USB debugging**.
3. Note the Streamer's IP (Settings → Network & Internet → your Wi-Fi).
4. On the computer:

        adb connect <streamer-ip>:5555      # accept the prompt on the TV
        adb install -r TopShelf.apk

**Option B: no computer**

1. Install **Downloader** (by AFTVnews) from the Play Store on the TV.
2. Settings → Apps → Security & restrictions → Unknown sources → allow Downloader.
3. In Downloader, type the release link above and install.

## Make it the home screen

Pick one:

- **Home-button redirect (reversible, no computer):** Settings → System →
  Accessibility → *TopShelf home button* → On. Pressing Home now opens TopShelf.
  If the toggle is greyed out (Android blocks this for sideloaded apps), run:

        adb shell settings put secure enabled_accessibility_services com.topshelf.launcher/com.topshelf.launcher.HomeRedirectService
        adb shell settings put secure accessibility_enabled 1

- **Replace Google's launcher entirely (cleanest):**

        adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx

  Undo any time with:

        adb shell pm enable com.google.android.apps.tv.launcherx

## Using it

- **Arrows** move, **OK** opens an app.
- **Hold OK** on an app: Move, Move to front, App info, Hide.
- **Up** from the dock: photo mode. Left/right browse photos, OK pauses, Down returns.
- **Back** jumps to the dock; Home always returns to the dock.
- The **gear tile** (last) opens Settings: subreddit (OK to type any), sort,
  how often the photo changes, slow zoom, clock, home-button status, hidden apps,
  Google TV home, Android settings.

## Live TV

The **Live TV** tab (top centre) turns an IPTV M3U playlist into a guide:
categories from the playlist's groups (a slide-out panel: press Left in the guide, or tap
the category button in its top-left corner), a scrolling TV guide from its XMLTV/EPG link
(read from the playlist header, or set your own), a live preview, and full-screen
playback (Up/Down changes channel, OK shows the info banner, Back returns to the guide).
Hold OK on a channel to add it to Favorites; hold OK while watching to open the stream
in another player app (VLC, MX Player…). Set the links in Settings → Live TV playlist / guide.

Streams play inside the app with hls.js and mpegts.js (both Apache-2.0, in `assets/lib/`)
through a small local proxy, so plain-http IPTV servers work. Channels using AC-3/E-AC-3
audio may play without sound in the built-in player; use "another player" for those.

## Movies & TV Shows

Two tabs with Apple TV–style rows: Popular, New releases, Top rated and genres (posters and
details from Cinemeta, a free public database). OK (or tap) on a poster opens that title in
Nuvio (nuvio://meta?type=…&id=tt…, sent straight to the Nuvio app) or Stremio; hold OK (or long-press) to play its full trailer.
Resting on a poster widens it into a backdrop card and plays the trailer inside it, muted, through YouTube's embedded player
(Settings → Trailer previews: In the poster / Behind the title / Off; Trailer sound: Off / On). On a phone, the first tap
previews a title and a second tap opens it.
Settings → Movie & TV poster URL takes a poster-service pattern such as BetterPosters'
"Your Poster URL" ({imdb_id}, {tmdb_id} and {type} are filled in), for posters with rating badges.

## Phone extras

- **Upright (portrait) layout:** turn the phone and TopShelf switches to a vertical layout: big clock, three
  columns of apps, a tab bar along the bottom, posters on top of each other, and the Live TV preview above
  the guide. Settings → Screen rotation picks Auto / Portrait / Landscape. Full-screen Live TV turns sideways.
- **Picture-in-picture:** leave the app while a channel plays full screen and it keeps playing in a small window.

## Everywhere (TV and phone)

- **Search** (magnifier, top right): apps, movies, TV shows and Live TV channels, with recent searches.
- **Hold OK / long-press** on a poster: Open in Nuvio, Watch trailer, Add to My List. On a channel: Watch,
  Favorites, Open in another player. On an app: Move, App info, Hide.
- **My List** row on Movies and TV Shows; **Recent** category in Live TV.

## Also

- **Weather** next to the clock (and on the big clock / screensaver): Settings → Weather, type a city or ZIP
  (from open-meteo.com, no account needed). Settings → Temperature switches °F / °C.
- **Screensaver:** after a while with no input the home screen eases into photo mode with the time
  (Settings → Screensaver; 10 minutes by default on TV, off on phones).
- **Sleep timer:** hold OK (or press and hold) while watching Live TV full screen → Sleep timer.
- **Loading screens:** an opening screen, a launch card when opening an app / a title in Nuvio / a trailer,
  a tune-in card while a channel starts, and shimmering placeholders while rows load.

## Live TV extras

- **Reminders:** hold OK (or press and hold) on an upcoming programme → Remind me. A pop-up appears when it's
  about to start (or a notification if TopShelf isn't open; tap it to watch). Settings → Reminders lists them.
- **Guide search:** Search also finds programmes in the guide (On now / start time · channel).
- **Channel list over full-screen video:** Right (or swipe right) while watching; Left (or swipe left) jumps to
  the previous channel.
- **Hide categories:** hold OK on a category → Hide. Settings → Hidden categories brings them back.

## Sports

The **Sports** tab follows the sports and teams you pick (NFL, NBA, MLB, NHL, WNBA, college football and
basketball, MLS, Premier League, Champions League, La Liga): live scores, today's games, and your teams'
upcoming games, from ESPN's public scoreboards (unofficial; no account needed). For each game TopShelf looks
for it in your Live TV: guide listings that name the teams at game time, event channels named after the game,
and the game's TV networks (ESPN, FOX, TNT, local stations…). Select a game to watch it or set a reminder.
Settings → Sports & teams (or the ＋ card) changes your picks.

## Top Shelf

A row of artwork above the dock (My List, then what's popular) that opens titles in Nuvio. On a TV it shows when
Nuvio is selected in the dock (press Up to step into it); on a phone it's always there. Settings → Top Shelf.

## Phone ↔ TV link, voice, game alerts

- **Phone & TV link** (Settings): TopShelf on your phone finds TopShelf on your TV over Wi-Fi; pair with the
  4-digit code the TV shows. The phone then works as the TV's remote (touchpad, Back/Home/More, mic) and
  keyboard, has "Watch on TV" / "Open on TV" in its hold menus, and keeps favorites, My List, teams,
  reminders and recent searches in sync. Only paired devices can control the TV.
- **Voice search:** the mic in Search (or the phone remote's mic, which searches on the TV).
- **Game alerts & score ticker:** a live-score strip on Home while your teams play, "<team> score!" toasts,
  and a heads-up (pop-up, or a notification when TopShelf is closed) a few minutes before their games.
  Settings → Game alerts.

## Project layout

- `app/src/main/assets/index.html` – the whole home screen (HTML/CSS/JS). Edit freely;
  it also runs in a desktop browser with demo apps for quick previews.
- `app/src/main/java/.../MainActivity.java` – full-screen WebView, serves the page and app icons.
- `Bridge.java` – what the page can ask Android for (apps, launching, prefs, photos).
- `Reddit.java` – fetches landscape photos from the subreddit's public JSON.
- `HomeRedirectService.java` – the optional Home-button redirect.

