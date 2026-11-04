'use strict';
/* =====================================================================
   Search (the magnifier in the top bar): finds installed apps, movies and TV shows
   (Cinemeta), and Live TV channels. Works with the remote (arrows / OK / hold OK)
   and with touch (tap / long-press). Shares globals with index.html, live.js, vod.js.
   ===================================================================== */

const SR = { q: '', rows: [], r: -1, c: {}, t: 0, seq: 0, from: 'grid', res: {}, cache: {},
             recent: readJSON('recentSearches', []), okDown: false, okTimer: 0, okLong: false };

function openSearch() {
  if (mode === 'search') return;
  if (mode === 'top') exitTop();
  if (typeof vTrStop === 'function') vTrStop();
  SR.from = mode === 'menu' || mode === 'settings' ? baseMode() : mode;
  mode = 'search';
  body.classList.add('search-open');
  styleGlass($('#search'));
  if (typeof LV !== 'undefined' && !LV.loaded) { try { lvLoad(); } catch (e) {} }
  const inp = $('#sq');
  inp.value = SR.q;
  SR.r = -1;
  if (SR.q) runSearch(); else srDraw();
  setTimeout(() => { if (mode === 'search') { inp.focus({ preventScroll: true }); inp.select(); } }, 80);
}

/* ---------------------------------------------------------------- voice */

const MIC_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="2.5" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3.5"/></svg>';
function startVoice() {
  if (!B.voiceInput) { toast('Voice search works in the app'); return; }
  try { B.voiceInput('Search TopShelf'); } catch (e) { toast("Voice input isn't available here"); }
}
window.tsVoice = (text, err) => {
  if (err) { toast("Voice input isn't available on this device"); return; }
  if (!text) return;
  if (typeof LK !== 'undefined' && LK.voiceForTv) {        // the phone remote's mic: search on the TV
    LK.voiceForTv = false;
    lkSend({ type: 'text', text, submit: true, search: true }).then(r => { if (r) toast(`Searching on ${lkTvName()}: “${text}”`); });
    return;
  }
  if (mode !== 'search') openSearch();
  const inp = $('#sq');
  inp.value = text;
  runSearch();
  srRemember(text);
  setTimeout(() => { inp.blur(); if (SR.rows.length) { SR.r = 0; srMark(); } }, 120);
};
window.tsVoiceSearch = () => { if (mode !== 'search') openSearch(); setTimeout(startVoice, 150); };

function closeSearch() {
  $('#sq').blur();
  clearTimeout(SR.okTimer);
  body.classList.remove('search-open');
  mode = SR.from && SR.from !== 'search' ? SR.from : baseMode();
  if (isVodTab()) vMark();
  if (tab === 'live') lvDraw();
}
function searchBack() { closeSearch(); }
function searchRefresh() { if (mode === 'search' || body.classList.contains('search-open')) srDraw(); }

(function wireSearch() {
  const inp = $('#sq');
  inp.addEventListener('input', () => { clearTimeout(SR.t); SR.t = setTimeout(runSearch, 300); });
  inp.addEventListener('focus', () => { SR.r = -1; srMark(); });
  $('#sres').addEventListener('scroll', noteScroll, { passive: true });
})();

function srRemember(q) {
  q = (q || '').trim();
  if (q.length < 2) return;
  SR.recent = [q].concat(SR.recent.filter(x => x.toLowerCase() !== q.toLowerCase())).slice(0, 10);
  writeJSON('recentSearches', SR.recent);
}

/* ---------------------------------------------------------------- searching */

function runSearch() {
  clearTimeout(SR.t);
  const q = $('#sq').value.trim();
  SR.q = q;
  const seq = ++SR.seq;
  SR.c = {};
  if (!q) { SR.res = {}; srDraw(); return; }
  const ql = q.toLowerCase();
  const score = s => { s = s.toLowerCase(); return s.startsWith(ql) ? 0 : s.split(/[\s\-:]+/).some(w => w.startsWith(ql)) ? 1 : 2; };
  const appHits = apps.filter(a => a.label.toLowerCase().includes(ql)).sort((a, b) => score(a.label) - score(b.label) || a.label.localeCompare(b.label)).slice(0, 16);
  const chans = typeof LV !== 'undefined' ? LV.chans : [];
  const hidCats = typeof lvHiddenCats === 'function' ? lvHiddenCats() : new Set();
  const chHits = chans.filter(ch => !hidCats.has(ch.g) && ch.n.toLowerCase().includes(ql)).sort((a, b) => score(a.n) - score(b.n)).slice(0, 40);
  const progHits = q.length >= 2 && typeof guideSearch === 'function' ? guideSearch(q) : [];
  SR.res = { apps: appHits, chans: chHits, progs: progHits, movie: null, series: null };
  if (q.length < 2) { SR.res.movie = []; SR.res.series = []; }
  srDraw();
  if (q.length < 2) return;
  for (const type of ['movie', 'series']) {
    const key = type + ':' + ql;
    const done = list => { SR.cache[key] = list; if (seq !== SR.seq) return; SR.res[type] = list; srDraw(); };
    if (SR.cache[key]) { done(SR.cache[key]); continue; }
    vFetch(`${CINEMETA}/catalog/${type}/top/search=${encodeURIComponent(q)}.json`)
      .then(j => done((j.metas || []).filter(m => m.poster).slice(0, 30).map(m => vSlim(Object.assign({ type }, m)))))
      .catch(() => { if (seq === SR.seq) { SR.res[type] = []; srDraw(); } });
  }
}

/* ---------------------------------------------------------------- drawing */

function srChNow(ch) {
  try {
    lvRebase();
    lvPrograms([ch.k]);
    const p = lvProgAt(ch, lvNow());
    return p ? (p[2] || '') : '';
  } catch (e) { return ''; }
}

function srDraw() {
  const box = $('#sres');
  const R = SR.res, rows = [];
  let html = '';
  const sec = (title, kind, items, itemHtml, note) => {
    if (!items || !items.length) {
      if (note === 'loading') html += `<div class="ssec"><div class="sst">${esc(title)}</div><div class="sstrip">${'<div class="si sp sk"><div class="spi"></div><div class="spn">&nbsp;</div></div>'.repeat(7)}</div></div>`;
      else if (note) html += `<div class="ssec"><div class="sst">${esc(title)}</div><div class="smsg">${note}</div></div>`;
      return;
    }
    const r = rows.length;
    rows.push({ kind, items });
    html += `<div class="ssec" data-r="${r}"><div class="sst">${esc(title)}</div><div class="sstrip" data-r="${r}">` +
      items.map((it, c) => itemHtml(it, r, c)).join('') + '</div></div>';
  };
  if (!SR.q) {
    sec('Recent searches', 'q', SR.recent, (q, r, c) => `<div class="si sqc" data-r="${r}" data-c="${c}">${esc(q)}</div>`);
    if (!SR.recent.length) html += `<div class="smsg big">Search your apps, movies, TV shows and Live TV channels.</div>`;
  } else {
    sec('Apps', 'app', R.apps, (a, r, c) =>
      `<div class="si sapp" data-r="${r}" data-c="${c}"><div class="sapic" style="background:linear-gradient(160deg, ${shade(a.tint, .18)}, ${shade(a.tint, -.25)})">` +
      (a.mock ? `<b>${esc(a.label.slice(0, 1))}</b>` : `<img src="${esc(iconUrl(a, false))}" onerror="this.style.display='none';if(this.nextElementSibling)this.nextElementSibling.style.display='block'"><b style="display:none">${esc(a.label.slice(0, 1))}</b>`) +
      `</div><div class="sal">${esc(a.label)}</div></div>`);
    const poster = (m, r, c) => `<div class="si sp" data-r="${r}" data-c="${c}"><div class="spi">` +
      `<img loading="lazy" src="${esc(vPosterUrl(m))}" data-fb="${esc(m.poster)}" onerror="vPosterFail(this)"></div>` +
      `<div class="spn">${esc(m.name)}</div><div class="spy">${esc(String(m.year || '').slice(0, 9))}</div></div>`;
    sec('Movies', 'movie', R.movie, poster, R.movie === null ? 'loading' : '');
    sec('TV Shows', 'series', R.series, poster, R.series === null ? 'loading' : '');
    sec('Live TV channels', 'chan', R.chans, (ch, r, c) =>
      `<div class="si sch" data-r="${r}" data-c="${c}">` +
      (ch.l ? `<img src="${esc(logoUrl(ch.l))}" onerror="this.style.visibility='hidden'">` : `<i>${esc(ch.n.slice(0, 1))}</i>`) +
      `<div><b>${esc(ch.n)}${LV.favs.has(ch.u) ? ' ★' : ''}</b><span>${esc(srChNow(ch) || ch.g || '')}</span></div></div>`);
    sec('On Live TV', 'prog', R.progs, (x, r, c) => {
      const now = Date.now(), live = x.p[0] <= now && now < x.p[1];
      const when = live ? `<span class="slive">On now</span>` : esc(remWhen(x.p[0]));
      return `<div class="si sch spg" data-r="${r}" data-c="${c}">` +
        (x.ch.l ? `<img src="${esc(logoUrl(x.ch.l))}" onerror="this.style.visibility='hidden'">` : `<i>${esc(x.ch.n.slice(0, 1))}</i>`) +
        `<div><b>${remHas(x.ch.u, x.p[0]) ? BELL_SVG : ''}${esc(x.p[2] || 'Untitled')}</b><span>${when} · ${esc(x.ch.n)}</span></div></div>`;
    });
    const nothing = R.movie && R.series && !rows.length;
    if (nothing) html += `<div class="smsg big">Nothing found for “${esc(SR.q)}”.</div>`;
  }
  // keep the remote's place when results arrive
  const cur = SR.rows[SR.r];
  const curKind = cur && cur.kind;
  SR.rows = rows;
  /* when the app draws Search itself this page stays hidden: it keeps the items (menus are placed
     by them) but not their pictures, which were being downloaded and decoded a second time here */
  if (typeof NH !== 'undefined' && NH.srNative) html = html.replace(/<img\b[^>]*>/g, '');
  box.innerHTML = html;
  box.querySelectorAll('.sstrip').forEach(el => el.addEventListener('scroll', noteScroll, { passive: true }));
  if (SR.r >= 0) {
    const i = rows.findIndex(r => r.kind === curKind);
    SR.r = i >= 0 ? i : Math.min(SR.r, rows.length - 1);
  }
  srMark();
}

function srItemEl(r, c) { return document.querySelector(`#sres .si[data-r="${r}"][data-c="${c}"]`); }

function srMark() {
  document.querySelectorAll('#sres .si.f').forEach(el => el.classList.remove('f'));
  $('#shead').classList.toggle('f', SR.r === -1 && mode === 'search');
  $('#smic').classList.toggle('f', SR.r === -2 && mode === 'search');
  if (SR.r < 0 || !SR.rows[SR.r]) return;
  const c = Math.min(SR.c[SR.r] || 0, SR.rows[SR.r].items.length - 1);
  const el = srItemEl(SR.r, c);
  if (!el) return;
  el.classList.add('f');
  // keep it on screen
  const strip = el.parentElement, rem = remPx();
  if (el.offsetLeft < strip.scrollLeft + rem * 2 || el.offsetLeft + el.offsetWidth > strip.scrollLeft + strip.clientWidth - rem * 2)
    strip.scrollTo({ left: Math.max(0, el.offsetLeft - rem * 2.6), behavior: 'smooth' });
  const sec = strip.parentElement, box = $('#sres');
  if (sec.offsetTop < box.scrollTop) box.scrollTo({ top: sec.offsetTop - rem, behavior: 'smooth' });
  else if (sec.offsetTop + sec.offsetHeight > box.scrollTop + box.clientHeight) box.scrollTo({ top: sec.offsetTop + sec.offsetHeight - box.clientHeight + rem, behavior: 'smooth' });
}

/* ---------------------------------------------------------------- actions */

function srItem(r, c) { const row = SR.rows[r]; return row ? { kind: row.kind, it: row.items[c] } : null; }

function srActivate(r, c) {
  const x = srItem(r, c);
  if (!x || !x.it) return;
  if (x.kind === 'q') { $('#sq').value = x.it; runSearch(); SR.r = -1; srMark(); return; }
  srRemember(SR.q);
  if (x.kind === 'app') {
    closeSearch();
    openApp(x.it);
  } else if (x.kind === 'movie' || x.kind === 'series') {
    vOpen(x.it);
  } else if (x.kind === 'chan') {
    closeSearch();
    srWatch(x.it);
  } else if (x.kind === 'prog') {
    const now = Date.now();
    if (x.it.p[0] <= now) { closeSearch(); srWatch(x.it.ch); }
    else srHold(r, c);                                   // a future show: offer the reminder
  }
}

/* Go to Live TV and play a channel full screen. */
function srWatch(ch) {
  if (tab !== 'live') switchTab('live');
  let i = LV.list.findIndex(c => c.u === ch.u);
  if (i < 0) {
    LV.cat = Math.max(0, LV.cats.findIndex(c => c.all));
    lvBuildList();
    LV.needBuild = true;
    i = Math.max(0, LV.list.findIndex(c => c.u === ch.u));
  }
  LV.row = i; LV.zone = 'guide'; LV.t = lvNow();
  lvPlay(LV.list[i] || ch, true);
}

function srToggleFav(ch) {
  if (LV.favs.has(ch.u)) { LV.favs.delete(ch.u); toast(`Removed ${ch.n} from Favorites`); }
  else { LV.favs.add(ch.u); toast(`Added ${ch.n} to Favorites`); }
  writeJSON('favs', Array.from(LV.favs));
  if (LV.cats[0]) LV.cats[0].count = LV.favs.size;
  if (LV.cats[LV.cat] && LV.cats[LV.cat].fav) lvBuildList();
  LV.needBuild = true;
  srDraw();
}

function srHold(r, c) {
  const x = srItem(r, c);
  if (!x || !x.it) return false;
  const el = srItemEl(r, c);
  if (x.kind === 'movie' || x.kind === 'series') { vMenu(x.it, el); return true; }
  if (x.kind === 'chan') {
    const ch = x.it, fav = LV.favs.has(ch.u);
    showMenu([
      ['play', 'Watch', () => { srRemember(SR.q); closeSearch(); srWatch(ch); }],
      ...(typeof lkMenuItem === 'function' ? lkMenuItem('Watch', { type: 'open', what: 'channel', u: ch.u, n: ch.n }) : []),
      [fav ? 'check' : 'star', fav ? 'Remove from Favorites' : 'Add to Favorites', () => srToggleFav(ch)],
      ['ext', 'Open in another player', () => B.openExternal(ch.u, ch.n)]
    ], el, ch.n);
    return true;
  }
  if (x.kind === 'prog') {
    const { ch, p } = x.it, future = p[0] > Date.now(), has = remHas(ch.u, p[0]);
    showMenu([
      ...(future ? [has ? ['bell', 'Cancel reminder', () => remRemove(remId(ch.u, p[0]))] : ['bell', `Remind me at ${fmtTime(p[0])}`, () => remAdd(ch, p)]] : []),
      ['play', future ? 'Watch this channel now' : 'Watch', () => { srRemember(SR.q); closeSearch(); srWatch(ch); }]
    ], el, `${p[2] || 'Untitled'} · ${ch.n}`);
    return true;
  }
  if (x.kind === 'app') {
    const a = x.it;
    showMenu([
      ['open', 'Open', () => srActivate(r, c)],
      ['info', 'App info', () => B.appInfo(a.id)]
    ], el, a.label);
    return true;
  }
  if (x.kind === 'q') {
    showMenu([['hide', 'Remove from recent searches', () => { SR.recent = SR.recent.filter(q => q !== x.it); writeJSON('recentSearches', SR.recent); srDraw(); }]], el, x.it);
    return true;
  }
  return false;
}

/* ---------------------------------------------------------------- remote */

function searchKeyDown(k, e) {
  const inp = $('#sq');
  if (SR.r === -2) {                                // on the mic button
    if (!k) return;
    e.preventDefault();
    if (k === 'ok') { if (!e.repeat) startVoice(); }
    else if (k === 'd' || k === 'l') { SR.r = -1; srMark(); inp.focus({ preventScroll: true }); }
    else if (k === 'back') closeSearch();
    return;
  }
  if (SR.r < 0) {                                   // typing in the box
    if (e.key === 'ArrowDown' || ((e.key === 'Enter' || e.keyCode === 66) && !e.isComposing)) {
      e.preventDefault();
      if (e.key !== 'ArrowDown') { runSearch(); srRemember(inp.value); }
      if (SR.rows.length) { inp.blur(); SR.r = 0; srMark(); }
      else if (e.key !== 'ArrowDown') inp.blur();
    } else if (e.key === 'ArrowUp') { e.preventDefault(); inp.blur(); SR.r = -2; srMark(); }
    else if (e.key === 'Escape') { e.preventDefault(); closeSearch(); }
    else if (document.activeElement !== inp && k === 'ok') { e.preventDefault(); inp.focus({ preventScroll: true }); }
    else if (document.activeElement !== inp && k === 'back') { e.preventDefault(); closeSearch(); }
    return;
  }
  if (!k) return;
  e.preventDefault();
  if (k === 'back') return closeSearch();
  if (k === 'ok') {
    if (e.repeat || SR.okDown) return;
    SR.okDown = true; SR.okLong = false;
    const r = SR.r, c = SR.c[SR.r] || 0;
    SR.okTimer = setTimeout(() => { SR.okLong = true; SR.okDown = false; srHold(r, c); }, 600);
    return;
  }
  const row = SR.rows[SR.r];
  const n = row ? row.items.length : 0, c = SR.c[SR.r] || 0;
  if (k === 'u') {
    if (SR.r === 0) { SR.r = -1; srMark(); inp.focus({ preventScroll: true }); return; }
    SR.r--;
  } else if (k === 'd') { if (SR.r < SR.rows.length - 1) SR.r++; }
  else if (k === 'l') { if (c > 0) SR.c[SR.r] = c - 1; }
  else if (k === 'r') { if (c < n - 1) SR.c[SR.r] = c + 1; }
  srMark();
}

function searchKeyUp(k) {
  if (k !== 'ok' || !SR.okDown) return;
  SR.okDown = false;
  clearTimeout(SR.okTimer);
  if (!SR.okLong && SR.r >= 0) srActivate(SR.r, SR.c[SR.r] || 0);
}

/* ---------------------------------------------------------------- touch */

function srAt(t) {
  const el = t && t.closest ? t.closest('#sres .si') : null;
  if (!el) return null;
  const r = +el.dataset.r, c = +el.dataset.c;
  SR.r = r; SR.c[r] = c;
  return [r, c];
}
function searchTap(t) {
  if (t.closest('#sclose')) return closeSearch();
  if (t.closest('#smic')) { $('#sq').blur(); return startVoice(); }
  if (!t.closest('#search')) return closeSearch();
  if (t.closest('#shead')) { $('#sq').focus({ preventScroll: true }); return; }
  const rc = srAt(t);
  if (!rc) { $('#sq').blur(); return; }
  $('#sq').blur();
  srMark();
  srActivate(rc[0], rc[1]);
}
function searchHold(t) {
  const rc = srAt(t);
  if (!rc) return false;
  srMark();
  return srHold(rc[0], rc[1]);
}
