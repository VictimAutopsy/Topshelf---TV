'use strict';
/* =====================================================================
   What each setting does, for the panel of choices that pops out beside Settings on the TV when OK is pressed
   on a setting with several choices (NativeSheet; nhSheetChoices in nativehome.js):
     about  a sentence or two in plain words (only what the code really does)
     opts   its choices, when they're a fixed list: labels, or [label, note] pairs, or a function giving them
     how    what the remote does on it, when that isn't the usual "◀ ▶ to change"
     pv     'look' shows a small sample of the look
   Keyed by the row's label; SET_INFO_MATCH is for labels that change (a count in them).
   A setting with nothing here still shows its name and what it's set to.
   Every section is written (1.0.410); a new setting gets an entry here too.
   ===================================================================== */
const ON_OFF = ['On', 'Off'];
const SET_INFO = {
  // ---------------------------------------------------------------- Home › Photos
  'Photos from': { about: 'Where the photos behind Home come from: one of the built-in collections, Bing\'s photo of the day, or any subreddit.',
    opts: () => Array.from(new Set(Object.keys(PHOTO_SOURCES).concat([S.sub], S.subs || SUB_PRESETS))).map(srcLabel),
    ordered: true, typeLabel: 'Type a subreddit…' },
  'Reddit account': { about: 'Reddit sometimes refuses to hand photos to visitors who aren\'t signed in. Signing in on Reddit\'s own page lets TopShelf read it as you. TopShelf never sees or keeps your password.',
    how: 'OK to sign in or out' },
  'Show': { about: 'Which of a subreddit\'s posts the photos are taken from.', opts: () => SORTS.map(x => x[1]) },
  'Change photo every': { about: 'How long each photo stays before the next one fades in.', opts: () => EVERY.map(x => x[1]) },
  'Photos': { about: 'How many photos are loaded and where they came from. If loading failed, the reason is in the line at the bottom of the screen.',
    how: 'OK to reload the photos now' },
  'Slow zoom': { about: 'Each photo drifts and zooms slowly while it\'s up, instead of sitting still.', opts: ON_OFF },
  // ---------------------------------------------------------------- Home › Look
  'Look': { about: 'How TopShelf\'s panels, buttons and highlights are drawn on every screen. Home redraws a moment after you choose.',
    opts: () => LOOKS.map(x => [x[1], x[2]]), cur: () => LOOKS.findIndex(x => x[0] === (S.look || 'glass')), pv: 'look', how: 'OK to choose a look' },
  'Home style': { about: 'What Home shows: your apps with the clock and weather, nothing but the photo, or a featured movie, show or game to continue.',
    opts: () => HOME_STYLES.map(x => x[1]) },
  'Menu': { about: 'Where Home, Movies, TV Shows, Sports and Live TV are listed: in a bar across the top, or in a menu down the side.',
    opts: ['Bar across the top', 'Side menu'] },
  'Side menu style': { about: 'The shape of the menu down the side.', opts: () => MENU_STYLES.map(x => x[1]) },
  'Bar style': { about: 'The shape of the bar across the top.', opts: () => BAR_STYLES.map(x => x[1]) },
  'Glass': { about: 'How see-through the panels are in the Glass look. The other looks don\'t use it.',
    opts: ['Liquid', 'Frosted', 'Solid'], cur: () => ['liquid', 'frosted', 'solid'].indexOf(S.glass || 'liquid') },
  'App bar': { about: 'The row of your apps on Home.', opts: ['Shown', 'Hidden'] },
  'Top Shelf': { about: 'A row of wide artwork above your apps: your My List first, then what\'s popular. Choosing one opens it in Nuvio.',
    opts: () => TS_MODES.map(x => x[1]) },
  'Home screen': { about: 'Which version of Home runs. Android is the smooth one; the web page is the older version, kept in case something goes wrong. Changing it reloads TopShelf.',
    opts: ['Android (smooth)', 'Web page (older)'] },
  // ---------------------------------------------------------------- Home › Apps
  'Icon pack': { about: 'Draws your apps\' icons from an icon pack installed on this device. None uses each app\'s own icon.' },
  'Reset custom icons': { about: 'Puts back the original icon for every app you gave your own picture.', how: 'OK to reset' },
  'Reset app order': { about: 'Puts your apps back in their original order.', how: 'OK to reset' },
  // ---------------------------------------------------------------- Home › Clock & weather
  'Clock': { about: 'How the time is shown on Home.', opts: () => CLOCKS.map(x => x[1]) },
  'Home widgets': { about: 'The corner of Home with the time, the weather and what\'s next.', opts: ['Time, weather and what\'s next', 'Just the time', 'Off'] },
  'Weather': { about: 'The place the weather on Home is for. Leave it empty to turn the weather off.', how: 'OK to type a city or ZIP code' },
  'Temperature': { about: 'The unit the weather is shown in.', opts: ['Fahrenheit (°F)', 'Celsius (°C)'] },

  // ================================================================ Movies & TV
  'Movies & TV look': { about: 'How Movies and TV Shows are laid out.', opts: ['Shelves', 'Classic posters'] },
  'Movie & TV poster URL': { about: 'A link to your own source of posters, used in place of the default ones.' },
  'Addons': { about: 'The addons that list titles and find streams for them. Open this to add one, switch one off or remove it.', how: 'OK to open' },
  'Trakt': { about: 'Connects your Trakt account, so what you watch is marked as watched there and you can carry on where you left off on another device.', how: 'OK to open' },
  'Trailer previews': { about: 'Plays a title\'s trailer when you rest on it: inside its poster, or behind the title at the top of the screen.',
    opts: ['In the poster', 'Behind the title', 'Off'] },
  'Trailer starts after': { about: 'How long you rest on a title before its trailer starts.', opts: ['2 seconds', '3 seconds', '5 seconds', '8 seconds', '12 seconds'] },
  'Header trailer': { about: 'When a trailer plays behind the title at the top: the rows slide down to make room for it, or it takes the picture\'s place.',
    opts: ['Rows slide down', 'In place of the picture'] },
  'Trailer sound': { about: 'Whether trailers play with their sound.', opts: ON_OFF },
  'Ambient light on trailers': { about: 'Fills the space around a trailer with light taken from its edges.',
    opts: [['Glow', 'the edge\'s colours'], ['Reflection', 'a blurred mirror of the edge'], ['Off', '']],
    cur: () => S.ambientBars === false ? 2 : S.ambientStyle === 'reflection' ? 1 : 0 },
  'Trailer outro': { about: 'Trailers from YouTube often end with the channel\'s own closing card. TopShelf stops where an outro has been marked; when none is marked, it stops this many seconds before the end.' },
  'Continue watching': { about: 'The movies and episodes you\'ve started, as Home and the tabs list them.', how: 'OK to clear the list' },
  'Next episode': { about: 'When an episode ends: the next one starts by itself, or TopShelf asks first.', opts: ['Automatic', 'Ask each time'] },
  'Subtitle size': { about: 'How big subtitles are in TopShelf\'s player.', opts: ['Small', 'Medium', 'Large', 'Extra large'] },
  'Subtitle background': { about: 'What sits behind subtitles so they stay readable over a bright picture.', opts: ['Dark box', 'Shadow only'] },
  'Match frame rate': { about: 'Switches the TV to the video\'s own frame rate while it plays, so movement is even. The screen may go dark for a moment as it switches.', opts: ON_OFF },
  // ================================================================ Live TV
  'Live TV playlist': { about: 'The link to your service\'s list of channels (an M3U playlist). More than one can be added.' },
  'Xtream login': { about: 'Signs in with your service\'s server, username and password instead of a playlist link.' },
  'Live TV guide (EPG)': { about: 'Where the programme guide comes from. Left empty, the guide your playlist names is used.' },
  'Refresh Live TV': { about: 'Loads your channels and the guide again now.', how: 'OK to refresh' },
  'Channel preview': { about: 'Plays the channel you rest on in the guide, beside its details.', opts: ON_OFF },
  'Pause & rewind': { about: 'Keeps the last 30 minutes of the channel you\'re watching, so you can pause it and go back.' },
  'Turn on to Live TV': { about: 'When the TV turns on: go straight back to the channel you were watching, or always start on Home.' },
  'Live TV plays in': { about: 'Which player shows live channels: TopShelf\'s own, or another app.', opts: ['TopShelf', 'Another app'] },
  'Live TV video': { about: 'How TopShelf\'s own player shows live video. The TV\'s player is the smooth one; the web page is the older way.' },
  'Channel groups': { about: 'Choose which of your playlist\'s groups show in the guide.', how: 'OK to open' },
  'Channel group order': { about: 'The order groups are listed in. To move one, hold OK on it in the guide and choose Move.', how: 'OK puts back the playlist\'s order' },
  'Hidden channels': { about: 'Channels you\'ve hidden from the guide. To hide one, hold OK on it in the guide.' },
  'Reminders': { about: 'The programmes you\'ve asked to be reminded about.', how: 'OK to see them' },
  'Recordings': { about: 'What\'s been recorded, what\'s coming up, and where recordings are kept.', how: 'OK to open' },
  'Google Drive': { about: 'Copies finished recordings to your Google Drive.', how: 'OK to open' },
  // ================================================================ Sports
  'Sports & teams': { about: 'The sports and the teams you follow.', how: 'OK to choose' },
  'Game alerts': { about: 'A card on screen when something happens in one of your teams\' games.', opts: ON_OFF },
  'Red zone alerts': { about: 'An alert when your team gets inside the 20-yard line. Game alerts have to be on.' },
  'Yahoo Fantasy': { about: 'Connects your Yahoo Fantasy Football team, for alerts about your players. If Yahoo refuses access, you can pick your players yourself instead.', how: 'OK to open' },
  'Fantasy players': { about: 'Pick your fantasy players yourself.', how: 'OK to open' },
  'Fantasy scoring': { about: 'How catches score in your league.' },
  // ================================================================ Screen & sleep
  'Screensaver': { about: 'After this long without a button press, Home fades to just the photo and the time. Any button brings everything back.',
    opts: () => SAVER_TIMES.map(x => x[1]) },
  'Bedtime': { about: 'From the time you pick until 6 AM, the screen gets warmer and dimmer, the screensaver starts sooner, and Live TV turns itself off after an hour unless you\'ve set a sleep timer.',
    opts: () => BED_TIMES.map(x => x[1]), cur: () => BED_TIMES.findIndex(x => x[0] === bedStart()) },
  'Picture sharpness': { about: 'How sharply TopShelf draws its own screens. Lower is smoother. Video keeps its full quality either way.',
    opts: () => RSCALES.map(x => x[1]) },
  'Screen': { about: 'What the TV reports as its screen size and refresh rate. There\'s nothing to change here.' },
  // ================================================================ Remote & phone
  'Home button': { about: 'Makes the remote\'s Home button open TopShelf. It works through the Accessibility setting named “TopShelf home button”.', how: 'OK to set it up' },
  'Keep TopShelf ready': { about: 'Keeps TopShelf running in the background, so Home comes straight back.' },
  'Phone & TV link': { about: 'Links TopShelf on your phone to this TV, so the phone can be its remote and set things up. Only phones you\'ve linked are obeyed.', how: 'OK to open' },
  'Ring doorbell': { about: 'Shows a card on the TV when someone rings your Ring doorbell. Your Ring password isn\'t kept.', how: 'OK to open' },
  // ================================================================ System
  'TopShelf updates': { about: 'Checks for a newer TopShelf and installs it.', how: 'OK to check or install' },
  'Go back to the previous version': { about: 'Puts back the version before this one, for when this one is misbehaving.', how: 'OK to go back' },
  'Updates access token': { about: 'The key that lets this TV fetch updates and send its reports.' },
  'Backup & restore': { about: 'Saves all your settings to a file, or brings them back from one.', how: 'OK to open' },
  'Diagnostic report': { about: 'Runs a test of this TV and sends the results, so a problem can be tracked down.', how: 'OK to run it' },
  'Run a self-check': { about: 'TopShelf opens each of its own screens and checks them. It takes about a minute.', how: 'OK to start' },
  'Report a glitch': { about: 'Sends a record of what TopShelf was just doing, with a picture of the screen. Use it straight after something looks or goes wrong.', how: 'OK to send' },
  'Anthropic API key': { about: 'Your own key from console.anthropic.com. With it, Claude answers what you ask with the mic.' },
  'Claude model': { about: 'Which Claude model answers.',
    opts: [['Haiku', 'fastest, simplest answers'], ['Sonnet', 'smart and quick (recommended)'], ['Opus', 'smartest, slower, costs most']] },
  'Voice requests': { about: 'On, you can ask for things in your own words with the mic. Off, the mic only searches.' },
  'Read answers aloud': { about: 'Speaks Claude\'s answers in the TV\'s voice as well as showing them.' },
  'Voice': { about: 'The voice answers are read in.' },
  'Speaking speed': { about: 'How fast answers are read.' },
  'Ask from the remote': { about: 'What the remote\'s ★ button does: ask Claude, or stay Google TV\'s own. Asking Claude needs the Home button set up.' },
  'Show frame rate': { about: 'Shows a small counter of how smoothly TopShelf is drawing.', opts: ON_OFF },
  'Open Google TV home': { about: 'Opens the TV\'s original home screen.', how: 'OK to open' },
  'Android settings': { about: 'Opens the TV\'s own settings.', how: 'OK to open' },
};
/* a section's line in the list on the left, where there's room for about 35 letters (the full line is under its heading) */
const SET_BRIEF = { 'Home': 'Photos, look, apps and clock', 'Movies & TV': 'Sources, trailers and playback', 'Live TV': 'Service, channels and recordings' };
const SET_INFO_MATCH = [
  [/^TMDB key/, { about: 'Your own key from themoviedb.org. With it, Movies and TV Shows have more rows.' }],
  [/hidden app/i, { about: 'Apps you\'ve hidden from Home. This brings all of them back at once.', how: 'OK to show them all' }],
];
/** what the detail panel needs for a row: { about, opts: [{ l, n }], cur, how, pv } */
function setInfoFor(r, value) {
  let x = SET_INFO[r.label];
  if (!x) { const m = SET_INFO_MATCH.find(e => e[0].test(r.label)); x = m ? m[1] : null; }
  const out = { about: '', opts: [], cur: -1, how: '', pv: '', ordered: !!(x && x.ordered), typeLabel: (x && x.typeLabel) || '' };
  if (x) {
    out.about = x.about || ''; out.pv = x.pv || '';
    let o = []; try { o = typeof x.opts === 'function' ? x.opts() : x.opts || []; } catch (e) {}
    out.opts = o.map(e => Array.isArray(e) ? { l: String(e[0]), n: String(e[1] || '') } : { l: String(e), n: '' });
    try { out.cur = typeof x.cur === 'function' ? x.cur() : out.opts.findIndex(e => e.l === value); } catch (e) {}
    // (a value with more after it, like "On · last 30 min" or "Sonnet · smart and quick", still marks its choice)
    if (out.cur < 0) out.cur = out.opts.findIndex(e => value.indexOf(e.l + ' ') === 0);
  }
  out.how = (x && x.how) || (r.edit && !r.cycle ? 'OK to type' : r.cycle ? 'OK to choose' : r.chevron ? 'OK to open' : '');
  return out;
}
