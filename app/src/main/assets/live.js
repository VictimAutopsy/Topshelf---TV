'use strict';
/* =====================================================================
   Live TV tab: categories, a TV guide (EPG) grid with a preview, and a
   full-screen player. Channel/guide data comes from Android (Bridge.live*),
   streams are played with hls.js / mpegts.js through the app's proxy.
   Shares globals with index.html (B, S, $, body, mode, tab, toast, ...).
   ===================================================================== */

const LV_SLOT = 30 * 60000;           // guide columns are half hours
let LV_ROWH = 5.7;                    // rem per channel row (bigger on a phone held upright)
const LV_HEAD = 2.8;                  // rem, time bar height
let LV_CHW = 22;                      // rem, channel column (sticky)
function lvSizes() {
  const p = typeof PORTRAIT !== 'undefined' && PORTRAIT;
  LV_ROWH = p ? 7.4 : 5.7;
  LV_CHW = p ? 18 : 27;                 // (landscape: the channel tiles start at the screen's edge, their contents 5rem in)
  const st = $('#live').style;
  st.setProperty('--lrowh', LV_ROWH + 'rem');
  st.setProperty('--lchw', LV_CHW + 'rem');
}
const LV_SC = 35 / 3600000;           // rem per millisecond (35rem per hour)
const LV_HOURS = 12;                  // how far ahead the guide reaches
const LV_BUF = 6;                     // extra rows rendered above/below the screen

const LV = {
  chans: [], cats: [], cat: 1, catTop: 0,
  list: [], row: 0, t: 0, base: 0, zone: 'guide', rendered: new Map(), catsKey: '',
  favs: new Set(readJSON('favs', [])),
  recent: readJSON('liveRecent', []),       // channel links, most recently watched first
  prog: {},
  playing: null, full: false, previewTimer: null, osdTimer: null,
  loaded: false, refreshing: false, progress: '',
  okTimer: null, okLong: false, okDown: false, tried: null
};

function lvNow() { return Date.now(); }
/* Channel number: the playlist's own (tvg-chno) when it's a real number, else its position. */
function chNum(ch) { return ch.c && /^\d+(\.\d+)?$/.test(String(ch.c).trim()) ? String(ch.c).trim() : String(ch.i + 1); }
function lvFloor(ms) { return Math.floor(ms / LV_SLOT) * LV_SLOT; }

function fmtTime(ms) {
  const d = new Date(ms);
  let h = d.getHours(), m = String(d.getMinutes()).padStart(2, '0');
  if (S.clock === '24') return `${String(h).padStart(2, '0')}:${m}`;
  const ap = h < 12 ? 'AM' : 'PM';
  h = h % 12 || 12;
  return `${h}:${m} ${ap}`;
}
function fmtShort(ms) { return fmtTime(ms).replace(/ (AM|PM)$/, ''); }
function proxify(u, ua, live) {
  return 'https://topshelf.local/proxy?u=' + encodeURIComponent(u) + (ua ? '&ua=' + encodeURIComponent(ua) : '') + (live === 'm' ? '&live=2' : live ? '&live=1' : '');
}
function logoUrl(l) { return !l ? '' : (l.startsWith('https://') ? l : proxify(l)); }

/* ---------------------------------------------------------------- tabs */

/* Content of the tab you switch to rises in (titles first, then the rows). */
function tabIn(t) {
  const sets = { movies: ['#vlabel', '#vhero', '#vrows'], series: ['#vlabel', '#vhero', '#vrows'], sports: ['#sphead', '#sprows'],
                 live: ['#lvlabel', '#lguide'], home: ['#dock', '#dockcells'] }[t] || [];
  sets.forEach((sel, i) => {
    const el = $(sel);
    if (!el) return;
    el.classList.remove('tabin', 'tabin2');
    void el.offsetWidth;
    el.classList.add(i === sets.length - 1 && sets.length > 1 ? 'tabin2' : 'tabin');
    clearTimeout(el._tabinT);
    el._tabinT = setTimeout(() => el.classList.remove('tabin', 'tabin2'), 800);
  });
}

function switchTab(t, keepTop) {
  if (t === tab) { if (!keepTop && mode === 'top') exitTop(); return; }
  const was = tab;
  tab = t;
  body.dataset.theme = t;
  body.classList.toggle('live', t === 'live');
  body.classList.toggle('vod', t === 'movies' || t === 'series');
  body.classList.toggle('sports', t === 'sports');
  if ((was === 'movies' || was === 'series') && !(t === 'movies' || t === 'series')) vLeave();
  if (was === 'sports' && t !== 'sports') spLeave();
  document.querySelectorAll('#tabs .tab').forEach(el => el.classList.toggle('active', el.dataset.tab === t));
  if (t === 'live') {
    body.classList.remove('deep');
    ['lguide', 'lcats'].forEach(id => { const g = document.getElementById(id); if (g) styleGlass(g); });   // panels just became visible
    liveEnter();
  } else {
    if (typeof MV !== 'undefined' && MV.on) mvClose(false);
    lvStop();
    lvSetPlayer('');
    body.classList.remove('lcats-open');
  }
  if (t === 'movies' || t === 'series') { body.classList.remove('deep'); vEnter(t === 'movies' ? 'movie' : 'series'); }
  if (keepTop) { mode = 'top'; } else { mode = baseMode(); }
  drawTop();
  applyFocus();
  if (t === 'live') { lvDraw(); if (!keepTop && LV.zone === 'guide') lvSchedulePreview(); }
  if ((t === 'movies' || t === 'series') && !keepTop) vFocus(false);
  if (t === 'sports') { body.classList.remove('deep'); spEnter(); }
  tabIn(t);
}

/* ---------------------------------------------------------------- data */

function liveEnter() {
  if (!LV.loaded) lvLoad();
  let info = {};
  try { info = JSON.parse(B.liveInfo()); } catch (e) {}
  if (info.m3u && !LV.refreshing && (!LV.chans.length || Date.now() - (info.updated || 0) > 12 * 3600000)) liveRefreshNow(true);
  LV.t = lvNow();
  lvRebase();
  LV.needBuild = true;
}

/* The guide starts half an hour before the current half hour and reaches LV_HOURS ahead. */
function lvRebase() {
  const b = lvFloor(lvNow()) - LV_SLOT;
  if (b === LV.base) return false;
  LV.base = b;
  LV.prog = {};
  LV.needBuild = true;
  return true;
}

function lvLoad() {
  let chans = [];
  try { chans = JSON.parse(B.liveChannels()) || []; } catch (e) {}
  LV.chans = chans;
  const groups = [], seen = new Map();
  const hid = lvHiddenCats();
  for (const c of chans) {
    if (hid.has(c.g)) continue;
    if (!seen.has(c.g)) { seen.set(c.g, 0); groups.push(c.g); }
    seen.set(c.g, seen.get(c.g) + 1);
  }
  LV.cats = [{ name: 'Favorites', fav: true }, { name: 'Recent', recent: true }, { name: 'All channels', all: true }]
    .concat(groups.map(g => ({ name: g, group: g, count: seen.get(g) })));
  LV.cats[2].count = chans.filter(c => !hid.has(c.g)).length;
  const saved = readJSON('liveCat', null);
  const ci = saved ? LV.cats.findIndex(c => c.name === saved) : -1;
  LV.cat = ci >= 0 ? ci : 2;
  lvBuildList();
  const last = readJSON('liveLast', null);
  const li = last ? LV.list.findIndex(c => c.u === last) : -1;
  LV.row = Math.max(0, li);
  LV.prog = {};
  LV.needBuild = true;
  LV.loaded = true;
}

function lvBuildList() {
  const c = LV.cats[LV.cat] || LV.cats[2];
  if (!c) { LV.list = []; return; }
  LV.cats[0].count = LV.favs.size;
  const hid = lvHiddenCats();
  const byU = new Map(LV.chans.map(ch => [ch.u, ch]));
  LV.cats[1].count = LV.recent.filter(u => byU.has(u)).length;
  if (c.fav) LV.list = LV.chans.filter(ch => LV.favs.has(ch.u));
  else if (c.recent) LV.list = LV.recent.map(u => byU.get(u)).filter(Boolean);
  else if (c.all) LV.list = hid.size ? LV.chans.filter(ch => !hid.has(ch.g)) : LV.chans;
  else LV.list = LV.chans.filter(ch => ch.g === c.group);
}

function liveRefreshNow(quiet) {
  let info = {};
  try { info = JSON.parse(B.liveInfo()); } catch (e) {}
  if (!info.m3u) { if (!quiet) toast('Add your playlist link first'); return; }
  LV.refreshing = true;
  LV.progress = 'Loading channels…';
  if (!quiet) toast('Refreshing Live TV…');
  lvDraw();
  try { B.liveRefresh(); } catch (e) { LV.refreshing = false; }
}

window.tsLiveProgress = msg => { LV.progress = msg; if (tab === 'live') lvDrawEmpty(); };
window.tsLiveDone = (ok, status, quiet) => {
  LV.refreshing = false;
  LV.progress = '';
  if (ok) {
    lvLoad();
    if (!quiet) toast('Live TV: ' + status);
  } else if (!quiet) {
    toast('Live TV ' + status.charAt(0).toLowerCase() + status.slice(1));
  }
  if (mode === 'settings') { buildSettings(); drawSettings(); }
  if (tab === 'live') lvDraw();
};

function lvPrograms(keys) {
  const need = (keys || []).filter(k => !(k in LV.prog));
  if (!need.length) return;
  let res = {};
  try { res = JSON.parse(B.livePrograms(JSON.stringify(need), LV.base, LV.base + LV_HOURS * 3600000 + LV_SLOT)); } catch (e) {}
  if (res.__loading) return;                     // the guide is still loading in the background (tsEpgReady redraws)
  for (const k of need) LV.prog[k] = res[k] || [];
}

function lvProgAt(ch, t) {
  const ps = ch ? LV.prog[ch.k] : null;
  if (!ps) return null;
  for (const p of ps) if (p[0] <= t && t < p[1]) return p;
  return null;
}
function lvNextAfter(ch, t) {
  const ps = ch ? LV.prog[ch.k] : null;
  if (!ps) return null;
  for (const p of ps) if (p[0] >= t) return p;
  return null;
}

/* ---------------------------------------------------------------- drawing
   The guide is a real scroll area (native touch scrolling with momentum). Only the rows
   near the screen exist in the page; they're added/removed as you scroll. */

const lvScroller = () => $('#lgscroll');
// Real pixel sizes as laid out (immune to font scaling / zoom differences).
// measured once per layout (reading sizes mid-keypress forced the whole page to re-layout every press)
const LVPX = { k: '', row: 0, head: 0 };
let lvVW = innerWidth + 'x' + innerHeight;
window.addEventListener('resize', () => { lvVW = innerWidth + 'x' + innerHeight; });
function lvPxKey() { return LV.list.length + '|' + lvVW + '|' + LV_ROWH + '|' + remPx(); }   // (reading the window size forced a re-layout)
function lvRowPx() {
  if (LVPX.k !== lvPxKey() || !LVPX.row) { const n = LV.list.length, h = $('#lrows').offsetHeight; LVPX.row = n && h ? h / n : 0; LVPX.head = $('#ltimes').offsetHeight; LVPX.k = lvPxKey(); }
  return LVPX.row || LV_ROWH * remPx();
}
function lvHeadPx() { lvRowPx(); return LVPX.head || LV_HEAD * remPx(); }

function lvDraw() {
  if (tab !== 'live') return;
  if (lvRebase()) LV.needBuild = true;
  lvDrawCats();
  if (lvDrawEmpty()) { $('#lgscroll').style.visibility = 'hidden'; $('#linfo').innerHTML = ''; return; }
  $('#lgscroll').style.visibility = '';
  if (LV.needBuild) lvBuildGuide(); else { lvRenderWindow(); lvMark(); }
  lvDrawInfo();
}

function lvDrawCats() {
  const el = $('#lcatlist');
  const key = LV.cats.map(c => c.name + ':' + c.count).join('|');
  if (key !== LV.catsKey) {
    LV.catsKey = key;
    el.innerHTML = LV.cats.map((c, i) =>
      `<div class="lcat" data-cat="${i}"><span>${c.fav ? '★ ' : ''}${esc(c.name)}</span><b>${c.count != null ? c.count : ''}</b></div>`).join('');
  }
  const kids = el.children;
  for (let i = 0; i < kids.length; i++) {
    kids[i].classList.toggle('cur', i === LV.cat);
    kids[i].classList.toggle('f', i === LV.cat && LV.zone === 'cats' && mode === 'live');
  }
  if (LV.zone === 'cats' && kids[LV.cat]) lvRevealIn(el, kids[LV.cat]);
  $('#lcatmore').style.opacity = 0;
  // the panel is out whenever the categories have focus
  body.classList.toggle('lcats-open', LV.zone === 'cats' && (mode === 'live' || mode === 'settings') && tab === 'live');
  const cb = document.querySelector('.lcb span');
  if (cb && LV.cats[LV.cat]) cb.textContent = LV.cats[LV.cat].name;
}

/* Scroll a list just enough to show an item (with a little breathing room). */
function lvRevealIn(box, item) {
  const pad = item.offsetHeight;
  const top = item.offsetTop, bot = top + item.offsetHeight;
  if (top - pad < box.scrollTop) box.scrollTo({ top: Math.max(0, top - pad), behavior: 'smooth' });
  else if (bot + pad > box.scrollTop + box.clientHeight) box.scrollTo({ top: bot + pad - box.clientHeight, behavior: 'smooth' });
}

function lvDrawEmpty() {
  const e = $('#lempty');
  let info = {};
  try { info = JSON.parse(B.liveInfo()); } catch (err) {}
  let msg = '';
  if (LV.refreshing && !LV.chans.length) msg = `<div class="spin"></div>${esc(LV.progress || 'Loading channels…')}`;
  else if (!info.m3u) msg = `<b>Add your IPTV playlist</b>Open Settings (the gear, top right) → <i>Live TV playlist</i> and paste your M3U link.<br>Press OK to open Settings now.`;
  else if (!LV.chans.length) msg = `<b>No channels yet</b>${esc(info.status || 'Press OK to load your playlist.')}`;
  else if (!LV.list.length) msg = LV.cats[LV.cat] && LV.cats[LV.cat].fav
    ? `<b>No favorites yet</b>In the guide, hold OK (or hold your finger) on a channel and choose Add to Favorites.`
    : LV.cats[LV.cat] && LV.cats[LV.cat].recent ? `<b>Nothing watched yet</b>Channels you watch show up here.` : `<b>Nothing in this category</b>`;
  e.innerHTML = msg;
  e.style.display = msg ? 'flex' : 'none';
  return !!msg;
}

function lvBuildGuide() {
  LV.needBuild = false;
  lvSizes();
  const W = LV_CHW + LV_HOURS * 35;
  $('#lgin').style.width = W + 'rem';
  const now = lvNow();
  const today = new Date().toDateString();
  let th = `<div class="lday"><div class="lcb"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"><path d="M4 7h16M4 12h16M4 17h10"/></svg><span></span></div></div>`;
  for (let t = LV.base; t < LV.base + LV_HOURS * 3600000; t += LV_SLOT) {
    const lab = new Date(t).toDateString() !== today ? new Date(t).toLocaleDateString(undefined, { weekday: 'short' }) + ' ' + fmtTime(t) : fmtTime(t);
    th += `<span class="lslot" style="left:${LV_CHW + (t - LV.base) * LV_SC}rem">${lab}</span>`;
  }
  $('#ltimes').innerHTML = th;
  const cb = document.querySelector('.lcb span');
  if (cb && LV.cats[LV.cat]) cb.textContent = LV.cats[LV.cat].name;
  const rows = $('#lrows');
  rows.style.height = (LV.list.length * LV_ROWH) + 'rem';
  rows.style.width = (LV_HOURS * 35) + 'rem';
  $('#lchcol').style.height = (LV.list.length * LV_ROWH) + 'rem';
  for (const el of LV.rendered.values()) lvDropRow(el);
  LV.rendered = new Map();
  lvPlaceNow();
  // start at the current half hour, with the focused channel in view
  const sc = lvScroller(), r = remPx();
  sc.scrollLeft = (lvFloor(now) - LV.base) * LV_SC * r;
  sc.scrollTop = Math.max(0, (LV.row - 1) * lvRowPx());
  lvRenderWindow();
  lvMark();
}

function lvPlaceNow() {
  const n = $('#lnow');
  const x = (lvNow() - LV.base) * LV_SC;
  n.style.left = x + 'rem';
  n.style.display = x >= 0 && x <= LV_HOURS * 35 ? 'block' : 'none';
  // "Now" in the time bar (instead of a line through every row)
  let c = document.getElementById('lnowchip');
  if (!c) { c = document.createElement('span'); c.id = 'lnowchip'; c.textContent = 'Now'; }
  if (c.parentNode !== $('#ltimes')) $('#ltimes').appendChild(c);
  c.style.left = (LV_CHW + x) + 'rem';
  c.style.display = n.style.display;
  // times the chip would sit on aren't shown (about 5rem wide each)
  document.querySelectorAll('#ltimes .lslot').forEach(el => {
    const l = parseFloat(el.style.left) || 0, cx = LV_CHW + x;
    el.style.visibility = l < cx + 2.8 && l + 5 > cx - 2.8 ? 'hidden' : '';
  });
}

function lvRenderWindow() {
  const sc = lvScroller(), rowPx = lvRowPx();
  if (!sc.clientHeight) return;
  LV.lastTop = sc.scrollTop;
  const y = Math.max(0, sc.scrollTop - lvHeadPx());
  const first = Math.max(0, Math.floor(y / rowPx) - LV_BUF);
  const last = Math.min(LV.list.length, Math.ceil((y + sc.clientHeight) / rowPx) + LV_BUF);
  for (const [i, el] of LV.rendered) if (i < first || i >= last) { lvDropRow(el); LV.rendered.delete(i); }
  const need = [];
  for (let i = first; i < last; i++) if (!LV.rendered.has(i)) need.push(i);
  if (!need.length) return;
  lvPrograms(need.map(i => LV.list[i].k));
  const frag = document.createDocumentFragment(), cfrag = document.createDocumentFragment();
  for (const i of need) {
    const el = document.createElement('div');
    el.className = 'lrow';
    el.dataset.row = i;
    el.style.top = (i * LV_ROWH) + 'rem';
    el.innerHTML = lvRowHtml(i);
    const ce = document.createElement('div');       // the channel's name cell lives in the pinned column
    ce.className = 'lch';
    ce.dataset.row = i;
    ce.style.top = (i * LV_ROWH) + 'rem';
    ce.innerHTML = lvChHtml(i);
    el._ch = ce;
    LV.rendered.set(i, el);
    frag.appendChild(el);
    cfrag.appendChild(ce);
  }
  $('#lrows').appendChild(frag);
  $('#lchcol').appendChild(cfrag);
  lvMark();
  lvSlideTitles();
}

function lvDropRow(el) { el.remove(); if (el._ch) el._ch.remove(); }

/* Long shows that started off-screen: slide their title into view (done when scrolling settles,
   instead of pinning every title, which overwhelms phone GPUs). */
function lvSlideTitles() {
  const sc = lvScroller(), r = remPx();
  const T = LV.base + sc.scrollLeft / (LV_SC * r);
  for (const el of LV.rendered.values()) {
    for (const c of el.children[0] ? el.children[0].children : []) {
      const x = c.firstChild;
      if (!x) continue;
      const s = Math.max(+c.dataset.s, LV.base), w = parseFloat(c.style.width) || c.offsetWidth / r;   // the width is written on the cell: no measuring
      const shift = T > s ? Math.min((T - s) * LV_SC, Math.max(0, w - 9)) : 0;
      const v = shift > .2 ? `translateX(${shift.toFixed(2)}rem)` : '';
      if (x.style.transform !== v) x.style.transform = v;
    }
  }
}

function lvChHtml(i) {
  const ch = LV.list[i];
  const num = chNum(ch);
  const fav = LV.favs.has(ch.u) ? '<em>★</em>' : '';
  return `<span class="lnum">${esc(String(num))}</span>` +
    (ch.l ? `<img class="llogo" loading="lazy" decoding="async" src="${esc(logoUrl(ch.l))}" onerror="this.style.visibility='hidden'">` : `<span class="llogo ph">${esc(ch.n.slice(0, 1))}</span>`) +
    `<span class="lname">${esc(ch.n)}</span>${fav}`;
}

function lvRowHtml(i) {
  const ch = LV.list[i], now = lvNow(), base = LV.base, end = base + LV_HOURS * 3600000;
  const ps = (LV.prog[ch.k] || []).filter(p => p[1] > base && p[0] < end);
  let cells = '';
  const cell = (s, e, inner, cls, extra) => {
    const a = Math.max(s, base), b = Math.min(e, end);
    return `<div class="lp${cls}" data-s="${s}" style="left:${((a - base) * LV_SC).toFixed(2)}rem;width:${((b - a) * LV_SC - .4).toFixed(2)}rem"><div class="lpx">${inner}</div>${extra || ''}</div>`;
  };
  if (!ps.length) {
    for (let t = base; t < end; t += 2 * 3600000) cells += cell(t, t + 2 * 3600000, `<div class="lpt">${esc(ch.n)}</div><div class="lpm">No guide information</div>`, ' none');
  } else {
    for (const p of ps) {
      const past = p[1] <= now ? ' past' : (p[0] <= now ? ' live' : '');
      const w = (Math.min(p[1], end) - Math.max(p[0], base)) * LV_SC;
      const bell = typeof remHas === 'function' && remHas(ch.u, p[0]) ? BELL_SVG : '';
      // on now: how far into it we are, as a thin bar along the bottom (like the hero's)
      const bar = past === ' live' ? `<i class="lpp" style="width:${Math.max(2, Math.min(100, (now - p[0]) / (p[1] - p[0]) * 100)).toFixed(1)}%"></i>` : '';
      cells += cell(p[0], p[1], w > 4 ? `<div class="lpt">${bell}${esc(p[2] || 'Untitled')}</div><div class="lpm">${fmtShort(p[0])} – ${fmtShort(p[1])}</div>` : (bell ? `<div class="lpt">${bell}</div>` : ''), past, bar);
    }
  }
  return `<div class="lps">${cells}</div>`;
}

/* Focus and "playing" marks, without rebuilding rows. */
function lvMark() {
  const focusGuide = LV.zone === 'guide' && mode === 'live';
  for (const [i, el] of LV.rendered) {
    const ch = LV.list[i];
    el.classList.toggle('cur', i === LV.row);
    el.classList.toggle('on', !!(LV.playing && ch && LV.playing.u === ch.u));
    if (el._ch) { el._ch.classList.toggle('cur', i === LV.row); el._ch.classList.toggle('on', !!(LV.playing && ch && LV.playing.u === ch.u)); }
    const old = el.querySelector('.lp.f');
    let want = null;
    if (focusGuide && i === LV.row) {
      const p = lvProgAt(ch, LV.t);
      want = p ? el.querySelector(`.lp[data-s="${p[0]}"]`) : null;
      if (!want) {   // no programme at the cursor: focus the block nearest to it
        let best = null, bd = Infinity;
        el.querySelectorAll('.lp').forEach(c => { const d = Math.abs(+c.dataset.s - LV.t); if (d < bd) { bd = d; best = c; } });
        want = best;
      }
    }
    if (old !== want) { if (old) old.classList.remove('f'); if (want) want.classList.add('f'); }
  }
}

/* Keep the focused channel and programme on screen (remote control). */
function lvReveal() {
  const sc = lvScroller(), r = remPx(), rowPx = lvRowPx(), head = lvHeadPx();
  let y = sc.scrollTop, x = sc.scrollLeft;
  const top = LV.row * rowPx, bot = top + rowPx;           // within the rows area
  if (top < y + rowPx * .6) y = Math.max(0, top - rowPx);
  else if (bot + head > y + sc.clientHeight - rowPx * .6) y = bot + head + rowPx - sc.clientHeight;
  const chw = LV_CHW * r, slot = LV_SLOT * LV_SC * r;
  const p = lvProgAt(LV.list[LV.row], LV.t);
  // a show that's already on keeps the view at "now" rather than jumping back to when it began
  const cs = (Math.max(p ? Math.max(p[0], lvFloor(LV.t)) : LV.t, LV.base) - LV.base) * LV_SC * r;
  const ce = p ? (Math.min(p[1], LV.base + LV_HOURS * 3600000) - LV.base) * LV_SC * r : cs + slot;
  const viewW = sc.clientWidth - chw;
  if (cs < x) x = Math.max(0, cs - slot * .5);
  else if (Math.min(ce, cs + viewW * .8) > x + viewW) x = Math.min(ce, cs + viewW * .8) - viewW + slot * .3;
  if (Math.abs(y - sc.scrollTop) > 1 || Math.abs(x - sc.scrollLeft) > 1) sc.scrollTo({ top: y, left: x, behavior: 'smooth' });
}

function lvDrawInfo() {
  const ch = LV.list[LV.row];
  if (!ch) { $('#linfo').innerHTML = ''; return; }
  lvPrograms([ch.k]);
  const p = lvProgAt(ch, LV.t);
  const now = lvNow();
  const num = chNum(ch);
  let when = '';
  if (p) {
    if (p[0] <= now && now < p[1]) when = `${fmtTime(p[0])} – ${fmtTime(p[1])} · ${Math.max(1, Math.round((p[1] - now) / 60000))} min left`;
    else if (p[0] > now) when = `${fmtTime(p[0])} – ${fmtTime(p[1])} · starts in ${lvDur(p[0] - now)}`;
    else when = `${fmtTime(p[0])} – ${fmtTime(p[1])}`;
  }
  const prog = p && p[0] <= now && now < p[1] ? (now - p[0]) / (p[1] - p[0]) : -1;
  const prev = S.livePreview !== false && S.livePlayer !== 'external';
  const touch = typeof V !== 'undefined' && V.touch;
  const playingThis = LV.playing && LV.playing.u === ch.u;
  const hint = touch ? (prev ? (playingThis ? 'Tap · Full screen  ·  hold · More' : 'Tap · Preview  ·  tap again · Full screen  ·  hold · More') : 'Tap · Watch  ·  hold · More')
    : (prev ? (playingThis ? 'OK · Full screen  ·  hold OK · More' : 'OK · Preview  ·  OK again · Full screen  ·  hold OK · More') : 'OK · Watch  ·  hold OK · More');
  $('#linfo').innerHTML =
    `<div class="lic">${prog >= 0 ? '<b class="lon">● ON NOW</b>' : ''}${esc(String(num))}  ·  ${esc(ch.n)}${ch.g ? `<span>${esc(ch.g)}</span>` : ''}</div>` +
    `<div class="lit">${esc(p ? (p[2] || 'Untitled') : ch.n)}</div>` +
    `<div class="lim">${esc(when || 'No guide information')}</div>` +
    (prog >= 0 ? `<div class="lbar"><i style="width:${(prog * 100).toFixed(1)}%"></i></div>` : '') +
    `<div class="lid">${esc(p ? p[3] : '')}</div>` +
    `<div class="lih">${hint}</div>`;
}

function lvDur(ms) {
  const m = Math.round(ms / 60000);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60 ? (m % 60) + ' min' : ''}`.trim();
}

let lvScrollRaf = 0;
(function wireGuideScroll() {
  const sc = lvScroller();
  let settle = 0;
  sc.addEventListener('scroll', () => {
    noteScroll();
    clearTimeout(settle);
    settle = setTimeout(() => { lvRenderWindow(); lvSlideTitles(); }, 150);   // and once more when it comes to rest
    if (lvScrollRaf) return;
    lvScrollRaf = requestAnimationFrame(() => { lvScrollRaf = 0; lvRenderWindow(); });
  }, { passive: true });
  window.addEventListener('resize', () => { if (tab === 'live') requestAnimationFrame(lvRenderWindow); });
  setInterval(() => { if (tab === 'live' && mode === 'live' && sc.scrollTop !== LV.lastTop) lvRenderWindow(); }, 250);
  $('#lcatlist').addEventListener('scroll', noteScroll, { passive: true });
})();

/* ---------------------------------------------------------------- remote keys */

function liveKeyDown(k, e) {
  if (mode === 'player') return lvPlayerKey(k, e);
  if (k === 'ok') {
    if (LV.zone === 'guide' && LV.list.length) {
      if (LV.okDown) return;
      LV.okDown = true; LV.okLong = false;
      LV.okTimer = setTimeout(() => { LV.okLong = true; LV.okDown = false; lvMenu(LV.row); }, 600);
      return;
    }
    if (LV.zone === 'cats' && LV.cats[LV.cat] && LV.cats[LV.cat].group) {
      if (LV.okDown) return;
      LV.okDown = true; LV.okLong = false;
      LV.okTimer = setTimeout(() => { LV.okLong = true; LV.okDown = false; lvCatMenu(LV.cat); }, 600);
      return;
    }
    if (e && e.repeat) return;
    return lvOk();
  }
  const now = performance.now();
  if (e && e.repeat && now - (LV.lastKey || 0) < 90) return;
  LV.lastKey = now;
  if (LV.zone === 'cats') lvCatsKey(k); else lvGuideKey(k);
}

function liveKeyUp(k) {
  if (mode === 'player') return;
  if (k !== 'ok' || !LV.okDown) return;
  LV.okDown = false;
  clearTimeout(LV.okTimer);
  if (!LV.okLong) lvOk();
}

function lvOk() {
  if (lvDrawEmpty()) {
    let info = {};
    try { info = JSON.parse(B.liveInfo()); } catch (e) {}
    if (!info.m3u) { openSettings(); lvFocusSettingsRow('playlist'); }
    else if (!LV.chans.length) liveRefreshNow();
    else if (LV.zone === 'guide') { LV.zone = 'cats'; lvDraw(); }
    return;
  }
  if (LV.zone === 'cats') { LV.zone = 'guide'; lvDraw(); lvSchedulePreview(); return; }
  const ch = LV.list[LV.row];
  if (ch) lvPlay(ch, true);
}

function lvCatsKey(k) {
  if (k === 'u') {
    if (LV.cat === 0) return;
    LV.cat--; lvCatChanged();
  } else if (k === 'd') {
    if (LV.cat < LV.cats.length - 1) { LV.cat++; lvCatChanged(); }
  } else if (k === 'r') {
    if (LV.list.length) { LV.zone = 'guide'; lvDraw(); lvReveal(); lvSchedulePreview(); }
  }
}

function lvCatChanged() {
  lvBuildList();
  LV.row = 0;
  LV.t = lvNow();
  LV.needBuild = true;
  writeJSON('liveCat', LV.cats[LV.cat].name);
  clearTimeout(LV.catTimer);
  lvDrawCats();
  // rebuilding the guide is the expensive part: wait until the remote/finger settles
  LV.catTimer = setTimeout(() => lvDraw(), 120);
}

function lvGuideKey(k) {
  if (!LV.list.length) { if (k === 'l') { LV.zone = 'cats'; lvDraw(); } else if (k === 'u') enterTop(); return; }
  const ch = LV.list[LV.row];
  const now = lvNow();
  const p = lvProgAt(ch, LV.t);
  if (k === 'u') {
    if (LV.row === 0) return enterTop();
    LV.row--;
    lvAfterRowMove();
  } else if (k === 'd') {
    if (LV.row < LV.list.length - 1) { LV.row++; lvAfterRowMove(); }
  } else if (k === 'r') {
    let nt = p ? p[1] : LV.t + LV_SLOT;
    const nx = lvNextAfter(ch, LV.t + 1);
    if (!p && nx && nx[0] < nt) nt = nx[0];
    if (nt >= LV.base + LV_HOURS * 3600000) return;
    LV.t = nt;
    lvMark(); lvDrawInfo(); lvReveal();
  } else if (k === 'l') {
    const curStart = p ? p[0] : LV.t;
    if (curStart <= now) { LV.zone = 'cats'; LV.t = now; lvDraw(); lvReveal(); return; }
    LV.t = Math.max(now, curStart - 1);
    const pp = lvProgAt(ch, LV.t);
    if (pp) LV.t = Math.max(pp[0], now);
    lvMark(); lvDrawInfo(); lvReveal();
  }
}

function lvAfterRowMove() {
  const now = lvNow();
  if (LV.t < now) LV.t = now;
  lvReveal();          // reads positions first, while the layout is still clean…
  lvRenderWindow();
  lvMark();            // …then changes things, so nothing has to be re-measured mid-press
  lvDrawInfo();
  lvSchedulePreview();
}

function lvToggleFav(row) {
  const ch = LV.list[row];
  if (!ch) return;
  if (LV.favs.has(ch.u)) { LV.favs.delete(ch.u); toast(`Removed ${ch.n} from Favorites`); }
  else { LV.favs.add(ch.u); toast(`Added ${ch.n} to Favorites`); }
  writeJSON('favs', Array.from(LV.favs));
  const viewingFavs = LV.cats[LV.cat] && LV.cats[LV.cat].fav;
  LV.cats[0].count = LV.favs.size;
  if (viewingFavs) { lvBuildList(); LV.needBuild = true; }
  else { const el = LV.rendered.get(row); if (el && el._ch) el._ch.innerHTML = lvChHtml(row); }
  lvDraw();
}

/* Hold OK / long-press on a channel. */
function lvMenu(row, anchorEl) {
  const ch = LV.list[row];
  if (!ch) return;
  const cellEl = anchorEl && anchorEl.closest ? anchorEl.closest('.lp') : null;
  if (cellEl && cellEl.dataset.s) LV.t = Math.max(lvNow(), +cellEl.dataset.s);
  LV.row = row; LV.zone = 'guide';
  lvMark(); lvDrawInfo();
  const fav = LV.favs.has(ch.u);
  const pr = lvProgAt(ch, LV.t);
  const future = pr && pr[0] > lvNow();
  const el = anchorEl ? (anchorEl.closest('.lp, .lch') || anchorEl) : (document.querySelector('.lrow.cur .lp.f') || (LV.rendered.get(row) || {})._ch);
  showMenu([
    ...(future ? [remHas(ch.u, pr[0]) ? ['bell', 'Cancel reminder', () => remRemove(remId(ch.u, pr[0]))]
                                      : ['bell', `Remind me at ${fmtTime(pr[0])}`, () => remAdd(ch, pr)]] : []),
    ['play', future ? 'Watch this channel now' : 'Watch', () => lvPlay(LV.list[row] || ch, true)],
    ['grid', LV.playing && LV.playing.u !== ch.u ? `Multiview with ${LV.playing.n}` : 'Watch in Multiview', () => mvAdd(ch)],
    ...(typeof lkMenuItem === 'function' ? lkMenuItem('Watch', { type: 'open', what: 'channel', u: ch.u, n: ch.n }) : []),
    ...(typeof lkRecItems === 'function' ? lkRecItems(ch, pr) : []),
    [fav ? 'check' : 'star', fav ? 'Remove from Favorites' : 'Add to Favorites', () => lvToggleFav(LV.list.indexOf(ch))],
    ['ext', 'Open in another player', () => B.openExternal(ch.u, ch.n)]
  ], el, future ? `${pr[2] || ch.n} · ${ch.n}` : ch.n);
}

function liveBack() {
  if (mode === 'player' && LV.zap) return lvZapClose();
  if (mode === 'player') return lvExitFull();
  if (LV.zone === 'cats' && LV.list.length) { LV.zone = 'guide'; lvDraw(); return; }   // close the panel
  switchTab('home');
}

function lvFocusSettingsRow(id) {
  if (typeof settingsShowRow === 'function') return settingsShowRow(id);
  const i = sRows.findIndex(r => r.id === id);
  if (i >= 0) { sSel = i; drawSettings(); }
}

/* ---------------------------------------------------------------- player */

let lvHls = null, lvMts = null;
const lvVideo = () => $('#video');

function lvSetPlayer(cls) {
  const p = $('#player');
  p.classList.toggle('pv', cls === 'pv');
  p.classList.toggle('fs', cls === 'fs');
  body.classList.toggle('playing-fs', cls === 'fs');
  body.classList.toggle('has-pv', cls === 'pv');
  const fs = cls === 'fs';
  if (fs !== LV.wasFs) {
    LV.wasFs = fs;
    try { B.setVideoLandscape && B.setVideoLandscape(fs); } catch (e) {}   // phones turn sideways for full screen
  }
  lvPipReady();
}
/* Leaving the app while a channel plays full screen floats it in a small window (phones). */
function lvPipReady() {
  const ready = !!(LV.playing && LV.full && mode === 'player');
  if (ready === LV.pipReady) return;
  LV.pipReady = ready;
  try { B.setPip && B.setPip(ready); } catch (e) {}
}
window.tsPip = on => {
  body.classList.toggle('pip', !!on);
  if (on) $('#osd').classList.remove('show');
};

function lvStop() {
  clearTimeout(LV.previewTimer);
  if (lvHls) { try { lvHls.destroy(); } catch (e) {} lvHls = null; }
  if (lvMts) { try { lvMts.pause(); lvMts.unload(); lvMts.detachMediaElement(); lvMts.destroy(); } catch (e) {} lvMts = null; }
  const v = lvVideo();
  try { v.pause(); v.removeAttribute('src'); v.load(); } catch (e) {}
  try { B.liveStopStreams(); } catch (e) {}
  LV.playing = null;
  $('#perr').textContent = '';
  $('#player').classList.remove('loading', 'tuning');
}

function lvSchedulePreview() {
  clearTimeout(LV.previewTimer);
  if (S.livePlayer === 'external' || S.livePreview === false) return;
  const ch = LV.list[LV.row];
  if (!ch || (LV.playing && LV.playing.u === ch.u)) return;
  LV.previewTimer = setTimeout(() => { if (tab === 'live' && mode === 'live') lvPlay(ch, false); }, 1200);
}

function lvKind(url) {
  const u = url.toLowerCase().split('?')[0];
  if (u.endsWith('.m3u8') || u.endsWith('.m3u')) return 'hls';
  if (/\.(mp4|webm|mkv|mov)$/.test(u)) return 'file';
  return 'ts';
}

function lvPlay(ch, full) {
  if (full && S.livePlayer === 'external') {
    lvStop(); lvSetPlayer('');
    lvAddRecent(ch);
    writeJSON('liveLast', ch.u);
    B.openExternal(ch.u, ch.n);
    lvDraw();
    return;
  }
  const before = LV.playing;
  if (!LV.playing || LV.playing.u !== ch.u) lvStart(ch, lvKind(ch.u));
  writeJSON('liveLast', ch.u);
  if (full) {
    if (LV.full && before && before.u !== ch.u) LV.prevCh = before;
    else if (!LV.full && LV.lastFull && LV.lastFull.u !== ch.u) LV.prevCh = LV.lastFull;
    LV.lastFull = ch;
    lvAddRecent(ch);
    LV.full = true;
    mode = 'player';
    lvSetPlayer('fs');
    lvShowOsd();
  } else {
    lvSetPlayer('pv');
  }
  lvDraw();
}

function lvAddRecent(ch) {
  LV.recent = [ch.u].concat(LV.recent.filter(u => u !== ch.u)).slice(0, 20);
  writeJSON('liveRecent', LV.recent);
  if (LV.cats[1] && LV.cats[1].recent) LV.cats[1].count = LV.recent.length;
}

/* The tune-in card shown until a channel's picture arrives: logo, number and name, what's on. */
function lvTuneCard(ch) {
  const t = $('#ptune');
  lvRebase();
  lvPrograms([ch.k]);
  const pr = lvProgAt(ch, lvNow());
  t.querySelector('.ptl').innerHTML = (ch.l ? `<img src="${esc(logoUrl(ch.l))}" onerror="this.style.display='none';if(this.nextElementSibling)this.nextElementSibling.style.display='grid'">` : '') +
    `<b${ch.l ? ' style="display:none"' : ''}>${esc(ch.n.slice(0, 1))}</b>`;
  t.querySelector('.ptc').textContent = `${chNum(ch)} · ${ch.n}`;
  t.querySelector('.ptp').textContent = pr ? (pr[2] || '') : '';
  $('#player').classList.add('tuning');
}

/* The page's own players for a channel (hls.js, mpegts.js: about 900 KB of script) are read the first time a
   channel plays in the page, not every time TopShelf starts: on the TV the app's own player plays channels, so
   they were read at every start for nothing. */
let LV_LIBS = null;
function lvLibs() {
  if (window.Hls && window.mpegts) return Promise.resolve();
  if (LV_LIBS) return LV_LIBS;
  const add = src => new Promise(res => { const s = document.createElement('script'); s.src = src; s.onload = s.onerror = () => res(); document.head.appendChild(s); });
  LV_LIBS = Promise.all([window.Hls ? null : add('lib/hls.min.js'), window.mpegts ? null : add('lib/mpegts.js')]);
  return LV_LIBS;
}
function lvStart(ch, kind, url, libsAsked) {
  if (!libsAsked && ((kind === 'hls' && !window.Hls) || (kind === 'ts' && !window.mpegts))) { lvLibs().then(() => lvStart(ch, kind, url, true)); return; }
  const sameCh = LV.playing && LV.playing.u === ch.u;
  const wasTuning = $('#player').classList.contains('tuning');      // a retry keeps the card up
  lvStop();
  LV.playing = ch;
  LV.tried = kind;
  url = url || ch.u;
  const v = lvVideo();
  const p = $('#player');
  p.classList.add('loading');
  if (!sameCh || wasTuning) lvTuneCard(ch);
  $('#perr').textContent = '';
  const fail = msg => lvFail(ch, kind, msg);

  if (kind === 'hls' && window.Hls && Hls.isSupported()) {
    lvHls = new Hls({
      xhrSetup: (xhr, u) => { xhr.open('GET', proxify(u, ch.ua), true); },
      lowLatencyMode: false, liveSyncDurationCount: 3, liveMaxLatencyDurationCount: 10,
      maxBufferLength: 40, maxMaxBufferLength: 90, backBufferLength: 20, enableWorker: true,
      fragLoadingMaxRetry: 6, manifestLoadingMaxRetry: 4, levelLoadingMaxRetry: 4
    });
    let recovered = 0;
    lvHls.on(Hls.Events.ERROR, (ev, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && recovered++ < 2) { lvHls.recoverMediaError(); return; }
      if (d.type === Hls.ErrorTypes.NETWORK_ERROR && recovered++ < 2) { setTimeout(() => lvHls && lvHls.startLoad(), 1500); return; }
      fail(d.details || 'stream error');
    });
    lvHls.loadSource(url);
    lvHls.attachMedia(v);
  } else if (kind === 'ts' && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
    lvMts = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: proxify(url, ch.ua, true) },
      { enableWorker: false, lazyLoad: false, enableStashBuffer: true, stashInitialSize: 1024 * 1024,
        liveBufferLatencyChasing: false, autoCleanupSourceBuffer: true,
        autoCleanupMaxBackwardDuration: 60, autoCleanupMinBackwardDuration: 30 });
    lvMts.on(mpegts.Events.ERROR, (type, detail) => fail(detail || type));
    lvMts.attachMediaElement(v);
    lvMts.load();
  } else {
    v.src = proxify(url, ch.ua, true);
  }
  v.muted = false;
  const pr = v.play();
  if (pr && pr.catch) pr.catch(() => {});
}

function lvFail(ch, kind, msg) {
  if (!LV.playing || LV.playing.u !== ch.u || kind !== LV.tried) return;   // stale event from an earlier attempt
  // Xtream-style servers usually offer the same channel as HLS: try that before giving up.
  if (kind === 'ts') {
    const alt = /\.ts(\?|$)/i.test(ch.u) ? ch.u.replace(/\.ts(\?|$)/i, '.m3u8$1') : ch.u + '.m3u8';
    lvStart(ch, 'hls', alt);
    return;
  }
  if (kind === 'hls' && lvKind(ch.u) === 'hls') { lvStart(ch, 'file'); return; }
  $('#player').classList.remove('loading', 'tuning');
  $('#perr').innerHTML = `<b>Couldn't play ${esc(ch.n)}</b>${esc(String(msg).slice(0, 80))}<br>Hold OK (or press and hold) to open it in another player app`;
}

(function wireVideo() {
  const v = lvVideo();
  ['playing', 'canplay'].forEach(ev => v.addEventListener(ev, () => { $('#player').classList.remove('loading'); $('#perr').textContent = ''; }));
  v.addEventListener('playing', () => $('#player').classList.remove('tuning'));
  v.addEventListener('waiting', () => $('#player').classList.add('loading'));
  v.addEventListener('error', () => { if (LV.playing && v.getAttribute('src') !== null || lvHls || lvMts) lvFail(LV.playing, LV.tried, 'decode error'); });
})();

function lvExitFull() {
  if (LV.zap) lvZapClose();
  LV.full = false;
  mode = 'live';
  if (LV.cats[LV.cat] && LV.cats[LV.cat].recent) { const u = LV.playing && LV.playing.u; lvBuildList(); LV.needBuild = true; const i = LV.list.findIndex(c => c.u === u); if (i >= 0) LV.row = i; }
  LV.zone = 'guide';
  lvSetPlayer(LV.playing ? 'pv' : '');
  $('#osd').classList.remove('show');
  lvDraw();
  lvReveal();
}

function lvZap(d) {
  if (!LV.list.length) return;
  if (LV.playing) LV.prevCh = LV.playing;
  LV.row = (LV.row + d + LV.list.length) % LV.list.length;
  LV.t = lvNow();
  lvPlay(LV.list[LV.row], true);
}

function lvPlayerKey(k, e) {
  if (LV.zap) return lvZapKey(k, e);
  if (k === 'r') return lvZapOpen();
  if (k === 'l') return lvPrevChannel();
  if (k === 'u') return lvZap(-1);
  if (k === 'd') return lvZap(1);
  if (k === 'ok') {
    if (LV.okDown) return;
    LV.okDown = true; LV.okLong = false;
    LV.okTimer = setTimeout(() => {
      LV.okLong = true;
      if (LV.playing) lvPlayerMenu();
    }, 650);
    const up = ev => {
      if ((KEYMAP[ev.key] || (ev.keyCode === 23 ? 'ok' : null)) !== 'ok') return;
      document.removeEventListener('keyup', up, true);
      LV.okDown = false;
      clearTimeout(LV.okTimer);
      if (!LV.okLong) lvToggleOsd();
    };
    document.addEventListener('keyup', up, true);
    return;
  }
  lvShowOsd();
}

function lvToggleOsd() {
  const o = $('#osd');
  if (o.classList.contains('show')) { o.classList.remove('show'); clearTimeout(LV.osdTimer); }
  else lvShowOsd();
}

function lvShowOsd() {
  const ch = LV.playing;
  if (!ch) return;
  lvRebase();
  lvPrograms([ch.k]);
  const now = lvNow();
  const p = lvProgAt(ch, now), n = lvNextAfter(ch, p ? p[1] : now);
  const num = chNum(ch);
  const prog = p ? (now - p[0]) / (p[1] - p[0]) : -1;
  const o = $('#osd');
  o.innerHTML =
    (ch.l ? `<img class="ologo" src="${esc(logoUrl(ch.l))}" onerror="this.style.visibility='hidden'">` : `<div class="ologo ph">${esc(ch.n.slice(0, 1))}</div>`) +
    `<div class="obody"><div class="oc">${esc(String(num))} · ${esc(ch.n)}${LV.favs.has(ch.u) ? ' ★' : ''}${typeof SLEEP !== 'undefined' && SLEEP.at ? ` <span class="osl">Sleep in ${sleepLeft()} min</span>` : ''}</div>` +
    `<div class="ot">${esc(p ? (p[2] || 'Untitled') : 'No guide information')}</div>` +
    (p ? `<div class="om">${fmtTime(p[0])} – ${fmtTime(p[1])} · ${Math.max(1, Math.round((p[1] - now) / 60000))} min left</div><div class="lbar"><i style="width:${(prog * 100).toFixed(1)}%"></i></div>` : '') +
    (n ? `<div class="onext">Next · ${fmtTime(n[0])} ${esc(n[2] || '')}</div>` : '') +
    `</div><div class="ohint">${typeof V !== 'undefined' && V.touch ? 'Swipe up/down to change channel · hold for more · Back for guide' : '▲▼ change channel · hold OK for more · Back for guide'}</div>`;
  styleGlass(o);
  o.classList.add('show');
  clearTimeout(LV.osdTimer);
  LV.osdTimer = setTimeout(() => o.classList.remove('show'), 5000);
}

/* Stall watchdog: hop over small gaps in the buffer, and reconnect if a stream freezes. */
const lvWatch = { t: -1, since: 0, restarts: 0, url: null };
setInterval(() => {
  const v = lvVideo(), ch = LV.playing;
  if (!ch || v.paused && v.readyState >= 3 || typeof NV !== 'undefined' && NV.active) { lvWatch.t = -1; return; }   // (the TV's player watches itself)
  if (lvWatch.url !== ch.u) { lvWatch.url = ch.u; lvWatch.restarts = 0; }
  const b = v.buffered;
  if (b.length) {
    const end = b.end(b.length - 1);
    // playhead sitting before buffered data (a gap), or far behind live: jump forward
    let inRange = false;
    for (let i = 0; i < b.length; i++) if (v.currentTime >= b.start(i) - .1 && v.currentTime <= b.end(i)) inRange = true;
    if ((!inRange && end - v.currentTime > 1) || end - v.currentTime > 30) { try { v.currentTime = Math.max(b.start(b.length - 1), end - 2); } catch (e) {} }
  }
  if (v.currentTime !== lvWatch.t) { lvWatch.t = v.currentTime; lvWatch.since = Date.now(); return; }
  if (Date.now() - lvWatch.since > 12000 && lvWatch.restarts < 3 && !$('#perr').textContent) {
    lvWatch.restarts++;
    lvWatch.since = Date.now();
    lvStart(ch, LV.tried === 'hls' && lvKind(ch.u) !== 'hls' ? 'hls' : lvKind(ch.u),
            LV.tried === 'hls' && lvKind(ch.u) !== 'hls' ? (/\.ts(\?|$)/i.test(ch.u) ? ch.u.replace(/\.ts(\?|$)/i, '.m3u8$1') : ch.u + '.m3u8') : undefined);
    if (v.play) { const pr = v.play(); if (pr && pr.catch) pr.catch(() => {}); }
  }
}, 2000);

window.livePause = () => { if (tab === 'live') { lvStop(); if (mode === 'player') lvExitFull(); lvSetPlayer(''); } body.classList.remove('pip'); };

/* ---------------------------------------------------------------- touch */

function liveTap(t) {
  if (mode === 'multi') { mvTap(t); return true; }
  if (mode === 'player') {
    if (LV.zap) { lvZapTap(t); return true; }
    if (t.closest('#osd')) return true;
    lvToggleOsd();
    return true;
  }
  if (t.closest('#player.pv')) { if (LV.playing) lvPlay(LV.playing, true); return true; }
  const cat = t.closest('.lcat');
  if (cat) { LV.cat = +cat.dataset.cat; LV.zone = 'guide'; lvCatChanged(); return true; }
  if (t.closest('.lcb')) { LV.zone = 'cats'; lvDrawCats(); return true; }
  if (body.classList.contains('lcats-open') && !t.closest('#lcats')) { LV.zone = 'guide'; lvDrawCats(); lvMark(); return true; }
  const row = t.closest('.lrow, .lch');
  if (row) {
    const i = +row.dataset.row;
    const cell = t.closest('.lp');
    const ct = cell && cell.dataset.s ? Math.max(lvNow(), +cell.dataset.s) : lvNow();
    const wasFocused = i === LV.row && LV.zone === 'guide' && (!cell || lvProgAt(LV.list[i], LV.t) === lvProgAt(LV.list[i], ct));
    LV.row = i; LV.zone = 'guide'; LV.t = ct;
    if (wasFocused) lvPlay(LV.list[i], true);
    else { lvMark(); lvDrawInfo(); lvDrawCats(); lvSchedulePreview(); }
    return true;
  }
  if (t.closest('#lempty')) { lvOk(); return true; }
  return true;
}

function liveSwipe(dir) {
  // Lists scroll natively; swipes only matter on the full-screen player (change channel).
  if (mode === 'multi') { if (LV.zap && dir === 'l') lvZapClose(); return true; }
  if (mode === 'player') {
    if (LV.zap) { if (dir === 'l') lvZapClose(); return true; }
    if (dir === 'u') lvZap(1); else if (dir === 'd') lvZap(-1);
    else if (dir === 'r') lvZapOpen();
    else if (dir === 'l') lvPrevChannel();
  }
  return true;
}

function liveRowOf(t) {
  const r = t && t.closest ? t.closest('.lrow, .lch') : null;
  return r ? +r.dataset.row : -1;
}

/* keep the guide's "now" line and progress bars moving */
setInterval(() => {
  if (tab !== 'live' || mode !== 'live') return;
  if (lvRebase()) { lvDraw(); return; }
  lvPlaceNow(); lvDrawInfo();
}, 30000);

/* the guide finished loading in the background */
window.tsEpgReady = () => {
  LV.prog = {};
  if (typeof tab !== 'undefined' && tab === 'live') { lvDraw(); if (mode === 'player' && typeof lvShowOsd === 'function' && $('#osd').classList.contains('show')) lvShowOsd(); }
};
/* the channel list is read in a quiet moment after start-up, so opening Live TV doesn't wait for it */
setTimeout(() => {
  const go = () => { try { if (!LV.loaded && liveInfo().m3u) lvLoad(); } catch (e) {} };
  if (window.requestIdleCallback) requestIdleCallback(go, { timeout: 8000 }); else go();
}, 9000);
