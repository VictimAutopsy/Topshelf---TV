'use strict';
/* =====================================================================
   What each setting does, for the detail panel beside Settings on the TV (NativeSheet):
     about  a sentence or two in plain words (only what the code really does)
     opts   its choices, when they're a fixed list: labels, or [label, note] pairs, or a function giving them
     how    what the remote does on it, when that isn't the usual "◀ ▶ to change"
     pv     'look' shows a small sample of the look
   Keyed by the row's label; SET_INFO_MATCH is for labels that change (a count in them).
   A setting with nothing here still shows its name and what it's set to.
   Written so far: Home. The other sections follow, one at a time.
   ===================================================================== */
const ON_OFF = ['On', 'Off'];
const SET_INFO = {
  // ---------------------------------------------------------------- Home › Photos
  'Photos from': { about: 'Where the photos behind Home come from: one of the built-in collections, Bing\'s photo of the day, or any subreddit.',
    opts: () => Array.from(new Set(Object.keys(PHOTO_SOURCES).concat([S.sub], S.subs || SUB_PRESETS))).map(srcLabel),
    how: '◀ ▶ to change · OK to type a subreddit' },
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
};
/* a section's line in the list on the left, where there's room for about 35 letters (the full line is under its heading) */
const SET_BRIEF = { 'Home': 'Photos, look, apps and clock', 'Movies & TV': 'Sources, trailers and playback', 'Live TV': 'Service, channels and recordings' };
const SET_INFO_MATCH = [
  [/hidden app/i, { about: 'Apps you\'ve hidden from Home. This brings all of them back at once.', how: 'OK to show them all' }],
];
/** what the detail panel needs for a row: { about, opts: [{ l, n }], cur, how, pv } */
function setInfoFor(r, value) {
  let x = SET_INFO[r.label];
  if (!x) { const m = SET_INFO_MATCH.find(e => e[0].test(r.label)); x = m ? m[1] : null; }
  const out = { about: '', opts: [], cur: -1, how: '', pv: '' };
  if (x) {
    out.about = x.about || ''; out.pv = x.pv || '';
    let o = []; try { o = typeof x.opts === 'function' ? x.opts() : x.opts || []; } catch (e) {}
    out.opts = o.map(e => Array.isArray(e) ? { l: String(e[0]), n: String(e[1] || '') } : { l: String(e), n: '' });
    try { out.cur = typeof x.cur === 'function' ? x.cur() : out.opts.findIndex(e => e.l === value); } catch (e) {}
  }
  out.how = (x && x.how) || (r.edit && r.cycle ? '◀ ▶ to change · OK to type your own' : r.edit ? 'OK to type' : r.cycle ? '◀ ▶ to change' : r.chevron ? 'OK to open' : '');
  return out;
}
