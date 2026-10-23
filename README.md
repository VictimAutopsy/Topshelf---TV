# TopShelf

TopShelf is a personal home-screen (launcher) app for my Google TV Streamer. It's a hobby project, built and used by me in my own home. It isn't sold, has no ads, and isn't published in any app store. I install it on my own TV myself.

## What it does

- A clean home screen for the apps on my TV, with photo backgrounds
- A Live TV guide and player for my own IPTV subscription, with pause and rewind, and recording
- Sports scores and live game stats for the teams I follow
- Yahoo Fantasy Football alerts for my own team (see below)

## How it uses the Yahoo Fantasy Sports API

TopShelf would use the API read-only, for my own Yahoo account only:

- It reads the teams in my leagues, my roster, and my weekly matchup.
- During NFL games, it shows short on-screen alerts on my TV when one of my players scores, makes a big play or gets injured, and shows the final results.
- It's only shown on my TV while TopShelf is on screen.

It doesn't:

- write to Yahoo or make roster moves
- access anyone else's account
- share, sell, store or display Yahoo data anywhere other than my own TV

The data is refreshed every few minutes during games and every few hours otherwise. Sign-in uses OAuth 2.0, and the sign-in tokens are stored only on the TV.

Fantasy data provided by Yahoo Fantasy.

## Users

One household: me.

## Contact

Through this GitHub account (@VictimAutopsy).
