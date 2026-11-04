'use strict';
/* =====================================================================
   Opening screen, launch screens, weather, screensaver and the Live TV sleep timer.
   Shares globals with index.html, live.js, vod.js and search.js.
   ===================================================================== */

/* ---------------------------------------------------------------- opening screen
   The page starts with the logo where Android's own splash put it, then lifts it, shows the
   name and a progress line, and fades away once the home screen (and its first photo) is ready. */

const SPLASH = { t0: performance.now(), done: false, photo: false };
function splashFinish() {
  if (SPLASH.done) return;
  const wait = Math.max(0, 1300 - (performance.now() - SPLASH.t0));   // long enough to see it, never longer than needed
  SPLASH.done = true;
  setTimeout(() => {
    const el = $('#splash');
    el.classList.add('out');
    body.classList.remove('booting');
    setTimeout(() => el.remove(), 700);
  }, wait);
}
window.onPhotoShown = () => { if (!SPLASH.photo) { SPLASH.photo = true; splashFinish(); } };
requestAnimationFrame(() => requestAnimationFrame(() => $('#splash').classList.add('go')));
setTimeout(splashFinish, 2600);                                 // photos slow or offline: don't wait for them
window.addEventListener('load', () => { if (photos.length === 0 && body.classList.contains('nophotos')) setTimeout(splashFinish, 1500); });

/* ---------------------------------------------------------------- launch screen
   A short full-screen card while another app (or Nuvio, or YouTube) comes up. */

const LX = { t: 0 };
function showLaunch(o) {
  const el = $('#launch');
  const bg = o.bg || '';
  el.querySelector('.lxbg').style.backgroundImage = bg ? `url("${bg.replace(/"/g, '%22')}")` : '';
  el.classList.toggle('photo', !bg);
  let icon = '';
  if (o.app) {
    const a = o.app;
    icon = `<div class="lxapp" style="background:linear-gradient(160deg, ${shade(a.tint, .18)}, ${shade(a.tint, -.25)})">` +
      (a.mock ? `<b>${esc(a.label.slice(0, 1))}</b>` : `<img src="${esc(iconUrl(a, !!a.banner))}" class="${a.banner ? 'ban' : 'ico'}" onerror="this.remove()">`) + '</div>';
  } else if (o.logo) {
    icon = `<img class="lxlogo" src="${esc(o.logo)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block'"><div class="lxname" style="display:none">${esc(o.title || '')}</div>`;
  } else if (o.title) icon = `<div class="lxname">${esc(o.title)}</div>`;
  el.querySelector('.lxi').innerHTML = icon;
  el.querySelector('.lxt').textContent = o.app ? o.title : '';
  el.querySelector('.lxs').textContent = o.sub || '';
  el.classList.remove('show');
  void el.offsetWidth;
  el.classList.add('show');
  body.classList.add('launching');
  clearTimeout(LX.t);
  LX.t = setTimeout(hideLaunch, o.ms || 6000);
}
function hideLaunch() {
  clearTimeout(LX.t);
  $('#launch').classList.remove('show');
  body.classList.remove('launching');
}
window.onAppPause = () => { if (body.classList.contains('launching')) { clearTimeout(LX.t); LX.t = setTimeout(hideLaunch, 500); } };
window.onAppResume = () => { hideLaunch(); IDLE.last = Date.now(); };
$('#launch').addEventListener('pointerdown', hideLaunch);

/* ---------------------------------------------------------------- weather (open-meteo.com, no account needed) */

const WX = { data: null, t: 0, busy: false };
const WX_CODES = [
  [[0], 'Clear', 'sun'], [[1, 2], 'Partly cloudy', 'partly'], [[3], 'Cloudy', 'cloud'], [[45, 48], 'Fog', 'fog'],
  [[51, 53, 55, 56, 57], 'Drizzle', 'rain'], [[61, 63, 65, 66, 67, 80, 81, 82], 'Rain', 'rain'],
  [[71, 73, 75, 77, 85, 86], 'Snow', 'snow'], [[95, 96, 99], 'Thunderstorms', 'storm']
];
const WX_ICONS = {
  sun: '<circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/>',
  partly: '<path d="M8 3.5v1.6M3.6 8H2M4.9 4.9l1.1 1.1M12.4 6.2a4 4 0 0 0-7.3 3"/><path d="M7.5 20h10a4 4 0 0 0 .4-8 6 6 0 0 0-11.6 1.6A3.2 3.2 0 0 0 7.5 20z"/>',
  cloud: '<path d="M7 19h10.5a4.5 4.5 0 0 0 .5-9 6.5 6.5 0 0 0-12.6 1.7A3.7 3.7 0 0 0 7 19z"/>',
  fog: '<path d="M4 9h16M2.5 13h19M5 17h14M8 21h8"/>',
  rain: '<path d="M7 15h10.5a4.5 4.5 0 0 0 .5-9 6.5 6.5 0 0 0-12.6 1.7A3.7 3.7 0 0 0 7 15z"/><path d="M8 18l-1 2.5M12.5 18l-1 2.5M17 18l-1 2.5"/>',
  snow: '<path d="M7 14h10.5a4.5 4.5 0 0 0 .5-9 6.5 6.5 0 0 0-12.6 1.7A3.7 3.7 0 0 0 7 14z"/><path d="M8 18h.01M12 20h.01M16 18h.01M10 22h.01M14 22h.01"/>',
  storm: '<path d="M7 14h10.5a4.5 4.5 0 0 0 .5-9 6.5 6.5 0 0 0-12.6 1.7A3.7 3.7 0 0 0 7 14z"/><path d="M12.5 15l-2.5 4h3.5l-2 3.5"/>'
};
function wxInfo(code, day) {
  const e = WX_CODES.find(c => c[0].includes(code)) || WX_CODES[2];
  const icon = !day && (e[2] === 'sun' || e[2] === 'partly') ? 'moon' : e[2];
  return { label: e[1], svg: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${WX_ICONS[icon]}</svg>` };
}
function wxDefaultUnit() { return /^en-(US|LR)|-(US|BS|BZ|KY|PW)$/i.test(navigator.language || 'en-US') ? 'f' : 'c'; }

async function wxSetPlace(v) {
  v = (v || '').trim();
  if (!v) { S.wxQuery = ''; S.wxName = ''; S.wxLat = S.wxLon = null; WX.data = null; save(); wxDraw(); toast('Weather turned off'); refreshSettings(); return; }
  toast(`Looking up “${v}”…`);
  try {
    const zip = /^\d{5}(-\d{4})?$/.test(v);
    const q = zip ? v.slice(0, 5) : v.split(',')[0].trim();
    const r = await fetch(proxify(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(q)}&count=10&language=en&format=json${zip ? '&countryCode=US' : ''}`));
    const j = await r.json();
    let list = (j && j.results) || [];
    const hint = v.includes(',') ? v.split(',').slice(1).join(',').trim().toLowerCase() : '';
    if (hint) list = list.filter(p => [p.admin1, p.country, p.country_code].some(x => x && (x.toLowerCase() === hint || x.toLowerCase().startsWith(hint)))).concat(list);
    const p = list[0];
    if (!p) { toast(`Couldn't find “${v}”`); return; }
    S.wxQuery = v; S.wxLat = p.latitude; S.wxLon = p.longitude;
    S.wxName = p.name + (p.admin1 && p.admin1 !== p.name ? ', ' + p.admin1 : p.country ? ', ' + p.country : '');
    if (!S.wxUnit) S.wxUnit = wxDefaultUnit();
    save();
    toast(`Weather for ${S.wxName}`);
    refreshSettings();
    wxFetch(true);
  } catch (e) { toast("Couldn't reach the weather service"); }
}

async function wxFetch(force) {
  if (S.wxLat == null || WX.busy) { wxDraw(); return; }
  if (!force && WX.data && Date.now() - WX.t < 20 * 60000) return;
  WX.busy = true;
  try {
    const unit = S.wxUnit === 'c' ? 'celsius' : 'fahrenheit';
    const r = await fetch(proxify(`https://api.open-meteo.com/v1/forecast?latitude=${S.wxLat}&longitude=${S.wxLon}` +
      `&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&temperature_unit=${unit}&timezone=auto&forecast_days=1`));
    const j = await r.json();
    if (j && j.current) {
      WX.data = { temp: Math.round(j.current.temperature_2m), code: j.current.weather_code, day: !!j.current.is_day,
                  hi: j.daily ? Math.round(j.daily.temperature_2m_max[0]) : null, lo: j.daily ? Math.round(j.daily.temperature_2m_min[0]) : null };
      WX.t = Date.now();
    }
  } catch (e) {}
  WX.busy = false;
  wxDraw();
}
function wxDraw() {
  const el = $('#wx');
  const d = S.wxLat != null ? WX.data : null;
  el.innerHTML = d ? wxInfo(d.code, d.day).svg + `<span>${d.temp}°</span>` : '';
  el.style.display = d ? '' : 'none';
  tick();
}
/* " · 72° Partly cloudy" for the big clock's date line */
window.wxLine = () => {
  const d = S.wxLat != null ? WX.data : null;
  if (!d) return '';
  return `  ·  ${d.temp}° ${wxInfo(d.code, d.day).label}` + (d.hi != null ? ` · H ${d.hi}° L ${d.lo}°` : '');
};
wxFetch(false);
setInterval(() => { if (!document.hidden) wxFetch(false); }, 5 * 60000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) wxFetch(false); });

/* ---------------------------------------------------------------- screensaver
   After a while with no remote or touch input, the home screen eases into photo mode with
   the time. Any key or touch brings everything back. */

const SAVER_TIMES = [[0, 'Off'], [2, 'After 2 minutes'], [5, 'After 5 minutes'], [10, 'After 10 minutes'], [20, 'After 20 minutes'], [30, 'After 30 minutes']];
const IDLE = { last: Date.now() };
function saverMinutes() { return S.saver != null ? S.saver : (IS_TV ? 10 : 0); }
function saverOn() { return body.classList.contains('saver'); }
function startSaver() {
  if (!photos.length || body.classList.contains('nophotos')) return;
  if (mode === 'top') exitTop();
  if (typeof vTrStop === 'function') vTrStop();
  if (tab === 'live' && typeof lvStop === 'function') { lvStop(); lvSetPlayer(''); }
  if (paused) togglePause();
  enterShelf();
  if (mode === 'shelf') body.classList.add('saver');
}
function stopSaver() {
  body.classList.remove('saver');
  IDLE.last = Date.now();
  if (mode === 'shelf') exitShelf();
  if (tab === 'live' && typeof lvSchedulePreview === 'function' && LV.zone === 'guide') lvSchedulePreview();
}
setInterval(() => {
  const m = saverMinutes();
  if (!m || saverOn() || document.hidden) return;
  if (!['grid', 'vod', 'live', 'top', 'sports'].includes(mode)) return;             // never over video, menus, settings or typing
  try { if (B.videoBusy && B.videoBusy()) { IDLE.last = Date.now(); return; } } catch (e) {}   // (nor over the app's own player)
  if (Date.now() - IDLE.last > m * 60000) startSaver();
}, 10000);
// any input wakes it (and is swallowed, so the first press doesn't also do something)
window.addEventListener('keydown', e => {
  IDLE.last = Date.now();
  if (saverOn()) { e.preventDefault(); e.stopImmediatePropagation(); stopSaver(); }
}, true);
window.addEventListener('pointerdown', e => {
  IDLE.last = Date.now();
  if (saverOn()) { e.stopPropagation(); stopSaver(); }
}, true);
window.tsSaverNow = startSaver;   // for testing

/* ---------------------------------------------------------------- Live TV sleep timer */

const SLEEP = { at: 0, t: 0 };
ICONS.timer = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13.5" r="7.5"/><path d="M12 9.5v4l2.5 2M9.5 2.5h5"/></svg>';
function sleepLeft() { return SLEEP.at ? Math.max(0, Math.round((SLEEP.at - Date.now()) / 60000)) : 0; }
function sleepLabel() { return SLEEP.at ? `Sleep timer · ${sleepLeft()} min left` : 'Sleep timer'; }
function setSleep(min) {
  clearTimeout(SLEEP.t);
  SLEEP.at = min ? Date.now() + min * 60000 : 0;
  if (min) SLEEP.t = setTimeout(sleepNow, min * 60000);
  toast(min ? `Live TV will stop in ${min >= 60 ? (min / 60) + (min === 60 ? ' hour' : ' hours') : min + ' minutes'}` : 'Sleep timer off');
  if (mode === 'player' && typeof lvShowOsd === 'function') lvShowOsd();
}
function sleepNow() {
  SLEEP.at = 0;
  if (typeof lvStop === 'function') {
    lvStop();
    if (mode === 'player') lvExitFull();
    lvSetPlayer('');
  }
  if (tab !== 'home') switchTab('home');
  toast('Sleep timer: Live TV stopped');
}
function sleepMenu() {
  const opts = [[15, '15 minutes'], [30, '30 minutes'], [60, '1 hour'], [90, '1½ hours'], [120, '2 hours']];
  showMenu((SLEEP.at ? [['hide', 'Turn off sleep timer', () => setSleep(0)]] : []).concat(opts.map(([m, t]) => ['timer', t, () => setSleep(m)])),
    null, SLEEP.at ? sleepLabel() : 'Stop Live TV after…');
}
/* Hold OK (or long-press) while watching full screen. */
function lvPlayerMenu() {
  const ch = LV.playing;
  if (!ch) return;
  const fav = LV.favs.has(ch.u);
  showMenu([
    [fav ? 'check' : 'star', fav ? 'Remove from Favorites' : 'Add to Favorites', () => { const i = LV.list.findIndex(c => c.u === ch.u); if (i >= 0) lvToggleFav(i); else srToggleFav(ch); lvShowOsd(); }],
    ...(typeof poMenuItems === 'function' ? poMenuItems() : []),
    ['grid', 'Multiview · add channels', () => mvOpen([ch], true)],
    ['timer', sleepLabel(), () => setTimeout(sleepMenu, 50)],
    ...(typeof lkMenuItem === 'function' ? lkMenuItem('Watch', { type: 'open', what: 'channel', u: ch.u, n: ch.n }) : []),
    ['ext', 'Open in another player', () => B.openExternal(ch.u, ch.n)]
  ], null, ch.n);
}
