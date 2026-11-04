'use strict';
/* =====================================================================
   Movies & TV Shows tabs: browse rows (Popular, New releases, Top rated, genres) from
   Cinemeta, a free public movie/TV database. Choosing a poster opens that title in Nuvio
   (or Stremio); holding OK / long-pressing a poster plays its trailer.
   Shares globals with index.html / live.js (B, S, $, body, mode, tab, toast, proxify, ...).
   ===================================================================== */

const CINEMETA = 'https://v3-cinemeta.strem.io';
const V_GENRES = {
  movie: ['Action', 'Comedy', 'Drama', 'Sci-Fi', 'Thriller', 'Horror', 'Animation', 'Romance', 'Crime', 'Family', 'Documentary'],
  series: ['Drama', 'Comedy', 'Crime', 'Sci-Fi', 'Animation', 'Reality-TV', 'Documentary', 'Mystery', 'Action', 'Family']
};

const V = { type: 'movie', rows: [], r: 0, cols: {}, cat: {}, heroT: 0, mine: readJSON('mylist', []) };

/* Poster sizes (rem): bigger posters on a phone held upright. */
function vDims() {
  return typeof PORTRAIT !== 'undefined' && PORTRAIT ? { pad: 3.5, w: 15, gap: 1.6, xw: 40 } : { pad: 5, w: 11, gap: 1.4, xw: 29.3 };
}

/* ---------------------------------------------------------------- helpers */

function vSlim(m) {
  const yt = (m.trailerStreams && m.trailerStreams[0] && m.trailerStreams[0].ytId) || (m.trailers && m.trailers[0] && m.trailers[0].source) || '';
  return {
    id: m.id || m.imdb_id, tmdb: m.moviedb_id || '', type: m.type, name: m.name, poster: m.poster || '', background: m.background || '', logo: m.logo || '',
    year: m.releaseInfo || m.year || '', rating: m.imdbRating || '', runtime: m.runtime || '',
    genres: (m.genres || m.genre || []).slice(0, 3), desc: String(m.description || '').slice(0, 420), yt
  };
}
async function vFetch(url) {
  const r = await fetch(proxify(url));
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.json();
}

/* ---------------------------------------------------------------- rows */

function vRowDefs(type) {
  const y = new Date().getFullYear();
  const defs = [];
  if (V.mine.some(m => m.type === type)) defs.push({ id: 'mine', title: 'My List', mine: true });
  defs.push(
    { id: 'pop', title: 'Popular', url: `${CINEMETA}/catalog/${type}/top.json` },
    { id: 'new', title: type === 'movie' ? 'New releases' : 'New this year', url: `${CINEMETA}/catalog/${type}/year/genre=${y}.json` },
    { id: 'top', title: 'Top rated', url: `${CINEMETA}/catalog/${type}/imdbRating.json` });
  for (const g of V_GENRES[type]) defs.push({ id: 'g-' + g, title: g, url: `${CINEMETA}/catalog/${type}/top/genre=${encodeURIComponent(g)}.json` });
  return defs;
}

function vEnter(type) {
  if (V.type !== type || !V.rows.length) { V.type = type; V.r = 0; V.cols = {}; vBuildRows(); }
  $('#vod').classList.add('on');
  $('#vlabel').textContent = type === 'series' ? 'TV Shows' : 'Movies';
  requestAnimationFrame(() => vHero());
}
function vLeave() { vTrStop(); $('#vod').classList.remove('on'); }
function vRefreshPosters() { V.rows.forEach((r, i) => { if (r.items && r.items.length) vRenderRow(i); }); }

function vBuildRows(keepDefs) {
  if (!keepDefs) V.rows = vRowDefs(V.type).map(d => Object.assign({ items: null, err: '' }, d));
  const wrap = $('#vrows');
  wrap.innerHTML = V.rows.map((r, i) =>
    `<div class="vrow" data-r="${i}"><div class="vrt">${esc(r.title)}</div><div class="vstrip" data-r="${i}"><div class="vsin">${'<div class="vp sk"></div>'.repeat(9)}</div></div></div>`).join('');
  wrap.scrollTop = 0;
  // load rows as they come near the screen
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) vLoadRow(+e.target.dataset.r); }), { root: wrap, rootMargin: '60% 0px' });
  wrap.querySelectorAll('.vrow').forEach(el => io.observe(el));
  wrap.querySelectorAll('.vstrip').forEach(el => el.addEventListener('scroll', noteScroll, { passive: true }));
  if (keepDefs) return;
  vLoadRow(0); vLoadRow(1); vLoadRow(2);
  vFocus(false);
}

async function vLoadRow(i) {
  const r = V.rows[i];
  if (!r || r.items || r.loading) return;
  if (r.mine) { r.items = V.mine.filter(m => m.type === V.type); vRenderRow(i); if (i === V.r) vFocus(false); return; }
  r.loading = true;
  try {
    let c = V.cat[r.url];
    if (!c || Date.now() - c.t > 6 * 3600000) {
      const j = await vFetch(r.url);
      c = V.cat[r.url] = { t: Date.now(), metas: (j.metas || []).filter(m => m.poster).map(m => vSlim(Object.assign({ type: V.type }, m))) };
    }
    r.items = c.metas;
  } catch (e) {
    r.err = e.message || 'offline';
    r.items = [];
  }
  r.loading = false;
  if (V.rows[i] === r) { vRenderRow(i); if (i === V.r) vFocus(false); }
}

function vRenderRow(i) {
  const r = V.rows[i];
  const strip = document.querySelector(`.vstrip[data-r="${i}"] .vsin`);
  if (!strip) return;
  const redo = i === V.r && VT.key;                 // re-drawing the row that's previewing
  if (redo) vTrStop();
  if (!r.items.length) {
    strip.innerHTML = `<div class="vempty">${r.err ? "Couldn't load this row (" + esc(r.err) + ')' : 'Nothing here yet'}</div>`;
    return;
  }
  strip.innerHTML = r.items.map((m, c) =>
    `<div class="vp" data-r="${i}" data-c="${c}">` +
    (m.poster ? `<img${c < 14 ? '' : ' loading="lazy"'} src="${esc(vPosterUrl(m))}" data-fb="${esc(m.poster)}" onerror="vPosterFail(this)">` : '') +
    `<div class="vpn">${esc(m.name)}</div><div class="rim"></div></div>`).join('');
  vMark();
  if (redo) vTrSchedule();
}

/* ---------------------------------------------------------------- focus & hero */

/* A custom poster service (e.g. BetterPosters' "Poster URL") with {imdb_id} / {tmdb_id} / {type}
   placeholders; falls back to the normal poster if it can't make or load one. */
function vPosterUrl(m) {
  const p = (S.posterUrl || '').trim();
  if (!p || !m.id) return m.poster;
  if (/\{tmdb(_id|Id)?\}/.test(p) && !m.tmdb) return m.poster;
  return p.replace(/\{(imdb_id|imdbId|imdb|id)\}/g, encodeURIComponent(m.id))
          .replace(/\{(tmdb_id|tmdbId|tmdb)\}/g, encodeURIComponent(m.tmdb || ''))
          .replace(/\{type\}/g, m.type === 'series' ? 'series' : 'movie');
}
function vPosterFail(img) {
  const fb = img.dataset.fb;
  if (fb && img.src !== fb) { img.dataset.fb = ''; img.src = fb; } else img.remove();
}

function vCur() { const r = V.rows[V.r]; return r && r.items ? r.items[V.cols[V.r] || 0] : null; }

function vMark() {
  document.querySelectorAll('.vp.f').forEach(el => el.classList.remove('f'));
  if (mode !== 'vod') { vTrStop(); return; }
  const el = document.querySelector(`.vp[data-r="${V.r}"][data-c="${V.cols[V.r] || 0}"]`);
  if (el) el.classList.add('f');
  // rows next to the focus keep their posters ready on the graphics chip; the rest don't each need a layer
  if (V.nearR !== V.r) { V.nearR = V.r; document.querySelectorAll('#vrows .vrow').forEach(rw => rw.classList.toggle('near', Math.abs(+rw.dataset.r - V.r) <= 1)); }
}

/* Row positions and strip widths are measured once (after the rows are built, or the screen changes)
   and scroll positions are tracked as they happen — measuring on every press made the TV re-lay-out
   the whole page each time. */
const VGEO = { key: '', tops: {}, sw: 0 };
function vGeo() {
  const key = innerWidth + 'x' + innerHeight + '|' + document.querySelectorAll('#vrows .vrow').length + '|' + V.type;
  if (VGEO.key !== key) {
    VGEO.key = key; VGEO.tops = {};
    document.querySelectorAll('#vrows .vrow').forEach(rw => { VGEO.tops[rw.dataset.r] = rw.offsetTop; });
    const st = document.querySelector('#vrows .vstrip'); VGEO.sw = st ? st.clientWidth : 0;
  }
  return VGEO;
}
document.addEventListener('scroll', e => { const t = e.target; if (t && t.classList && t.classList.contains('vstrip')) t._sl = t.scrollLeft; }, { capture: true, passive: true });
function vFocus(smooth) {
  const wrap = $('#vrows');
  const row = wrap.querySelector(`.vrow[data-r="${V.r}"]`);
  const geo = vGeo();
  const rowTop = row ? (geo.tops[V.r] != null ? geo.tops[V.r] : row.offsetTop) : 0;
  const strip = document.querySelector(`.vstrip[data-r="${V.r}"]`);
  const el = document.querySelector(`.vp[data-r="${V.r}"][data-c="${V.cols[V.r] || 0}"]`);
  const sl = strip ? (strip._sl != null ? strip._sl : 0) : 0, sw = geo.sw || (strip ? strip.clientWidth : 0);
  vMark();
  if (row) wrap.scrollTo({ top: rowTop - remPx() * .4, behavior: smooth ? 'smooth' : 'auto' });
  vTrStop();
  if (strip && el) {
    const rem = remPx(), pad = rem * vDims().pad;
    const left = vCardLeft(V.cols[V.r] || 0), w = rem * vDims().w;
    if (left < sl + pad || left + w > sl + sw - pad) {
      strip._sl = Math.max(0, left - pad);        // where it's heading (scroll events keep it exact)
      strip.scrollTo({ left: strip._sl, behavior: smooth ? 'smooth' : 'auto' });
    }
  }
  clearTimeout(V.heroT);
  V.heroT = setTimeout(() => vHero(), smooth ? 160 : 0);
  vTrSchedule();
}
// where card c sits when no card is expanded (row padding, card width, gaps)
function vCardLeft(c) { const d = vDims(); return remPx() * (d.pad + c * (d.w + d.gap)); }

let vHeroTok = 0;
function vHero(meta) {
  const m = meta || vCur();
  const h = $('#vhero');
  if (!m) { h.innerHTML = ''; h.dataset.key = ''; return; }
  const tok = ++vHeroTok;
  if (m.logo && !vHero.ok.has(m.logo)) {                     // fetch the logo first, then swap
    const im = new Image();
    const go = () => { vHero.ok.add(m.logo); if (tok === vHeroTok) vHeroDraw(m, h); };
    im.onload = go; im.onerror = go;
    setTimeout(go, 450);
    im.src = m.logo;
    vBackdrop(m.background || m.poster || '');
    return;
  }
  vHeroDraw(m, h);
}
vHero.ok = new Set();
function vHeroDraw(m, h) {
  const key = m.type + m.id + (V.touch ? 't' : 'k') + vInList(m);
  if (h.dataset.key === key) { vBackdrop(m.background || m.poster || ''); return; }
  h.dataset.key = key;
  const line = [String(m.year || '').slice(0, 9), m.rating ? '★ ' + m.rating : '', m.runtime, (m.genres || []).join(', ')].filter(Boolean).join('  ·  ');
  h.innerHTML = (m.logo ? `<img class="vlogo" src="${esc(m.logo)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block'"><div class="vtitle" style="display:none">${esc(m.name)}</div>` : `<div class="vtitle">${esc(m.name)}</div>`) +
    `<div class="vmeta">${vInList(m) ? '<span class="vchip">✓ My List</span>' : ''}<span>${esc(line)}</span></div><div class="vdesc">${esc(m.desc || '')}</div>` +
    `<div class="vhint">${V.touch ? (vTrMode() !== 'off' ? 'Tap again · Open in Nuvio' : 'Tap · Open in Nuvio') + '  ·  Hold · More'
                                  : 'OK · Open in Nuvio  ·  hold OK · More'}</div>`;
  // fade the title block up (an animation restart that doesn't force a page re-layout)
  try { if (h._in) h._in.cancel(); h._in = h.animate([{ opacity: .7, translate: '0 .35rem' }, { opacity: 1, translate: '0 0' }], { duration: 320, easing: 'cubic-bezier(.2,.8,.2,1)' }); } catch (e) {}
  vBackdrop(m.background || m.poster || '');
}

/* Backdrop crossfade: the new picture fades in ON TOP of the old one, which stays fully visible
   underneath until it's covered (fading both at once let the dark theme show through = flicker).
   While you're scrolling quickly it waits for focus to rest before changing. */
let vBgWant = null, vBgTop = -1, vBgT = 0, vBgRest = 0, vBgLast = 0;
function vBackdrop(url) {
  if (url === vBgWant) return;
  vBgWant = url;
  clearTimeout(vBgRest);
  const quick = performance.now() - vBgLast < 450;          // still moving: wait until focus settles
  vBgLast = performance.now();
  vBgRest = setTimeout(() => vBackdropNow(url), quick ? 380 : 0);
}
function vBackdropNow(url) {
  const layers = [...document.querySelectorAll('#vbg .vbgi')];
  if (!url) { layers.forEach(l => { l.classList.remove('show'); l.style.transition = 'opacity .5s'; l.style.opacity = '0'; }); vBgTop = -1; return; }
  const src = proxify(url);                                   // same-origin copy, so its colours can be read
  const img = new Image();
  const ready = (im, used) => {
    if (vBgWant !== url) return;                              // focus moved on while this loaded
    const next = layers[vBgTop === 0 ? 1 : 0], under = vBgTop >= 0 ? layers[vBgTop] : null;
    next.style.transition = 'none';
    next.style.opacity = '0';
    next.style.backgroundImage = `url("${used.replace(/"/g, '%22')}")`;
    next.style.zIndex = 2;
    next.classList.add('show');
    if (under) under.style.zIndex = 1;
    vBgTop = layers.indexOf(next);
    const retire = () => {                                    // fully covered now: retire the old one quietly
      clearTimeout(vBgT);
      if (under && layers[vBgTop] !== under && under !== next) { under.style.transition = 'none'; under.style.opacity = '0'; under.classList.remove('show'); }
    };
    next.ontransitionend = e => { if (e.propertyName === 'opacity' && layers[vBgTop] === next) retire(); };
    setTimeout(() => {                                        // next frame: fade the new picture in
      if (layers[vBgTop] !== next) return;
      next.style.transition = 'opacity .9s ease';
      next.style.opacity = '1';
    }, 20);
    clearTimeout(vBgT);
    vBgT = setTimeout(() => { if (layers[vBgTop] === next) retire(); }, 2500);   // in case the end event never comes
    vAmbientFromUrl(used, im);
  };
  img.onload = () => ready(img, src);
  img.onerror = () => { if (vBgWant === url && src !== url) { const i2 = new Image(); i2.onload = () => ready(i2, url); i2.src = url; } };
  img.src = src;
}

/* Ambient light: the backdrop's main colour glows softly behind the rows, like a TV bias light. */
/* The title's colour is worked out in a background worker: it fetches the (already cached) picture,
   shrinks and reads it there, and posts back one colour — nothing touches the TV's main thread. */
const VAMB = { w: null, seq: 0 };
function vAmbientWorker() {
  if (VAMB.w !== null) return VAMB.w;
  try {
    const src = `onmessage = async e => {
      try {
        let bm = e.data.bm;
        if (!bm) { const r = await fetch(e.data.url); bm = await createImageBitmap(await r.blob(), { resizeWidth: 24, resizeHeight: 14, resizeQuality: 'low' }); }
        const c = new OffscreenCanvas(24, 14), x = c.getContext('2d', { willReadFrequently: true });
        x.drawImage(bm, 0, 0); const d = x.getImageData(0, 0, 24, 14).data;
        postMessage({ id: e.data.id, d: Array.from(d) });
      } catch (err) { postMessage({ id: e.data.id, err: String(err) }); }
    };`;
    VAMB.w = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
    VAMB.w.onmessage = e => {
      if (e.data.id !== VAMB.seq) return;
      clearTimeout(VAMB.t);
      if (e.data.d) { VAMB.fails = 0; vAmbientApply(e.data.d); }
      else if (VAMB.img) { VAMB.fails = (VAMB.fails || 0) + 1; vAmbient(VAMB.img); if (VAMB.fails > 2) VAMB.w = false; }   // the worker can't reach it here: old way
    };
  } catch (e) { VAMB.w = false; }
  return VAMB.w;
}
function vAmbientFromUrl(url, img) {
  const w = typeof OffscreenCanvas !== 'undefined' && vAmbientWorker();
  if (w && url) {
    VAMB.img = img;
    const id = ++VAMB.seq;
    // the picture is shrunk to 24×14 off the main thread, then read inside the worker
    createImageBitmap(img, { resizeWidth: 24, resizeHeight: 14, resizeQuality: 'low' })
      .then(bm => { if (VAMB.seq === id) w.postMessage({ id, bm }, [bm]); })
      .catch(() => { if (VAMB.seq === id) w.postMessage({ id, url: new URL(url, location.href).href }); });
    clearTimeout(VAMB.t);
    VAMB.t = setTimeout(() => {                                   // no answer: do it the old way (and stop using the worker if it keeps happening)
      if (VAMB.seq !== id) return;
      VAMB.seq++; VAMB.fails = (VAMB.fails || 0) + 1; if (VAMB.fails > 2) VAMB.w = false;
      vAmbient(img);
    }, 2000);
    return;
  }
  vAmbient(img);
}
function vAmbientApply(d) {
  let r = 0, g = 0, b = 0, wsum = 0;
  for (let i = 0; i < d.length; i += 4) {
    const R = d[i], G = d[i + 1], Bb = d[i + 2];
    const mx = Math.max(R, G, Bb), mn = Math.min(R, G, Bb);
    const wt = 0.15 + (mx - mn) / 255 * (mx / 255) * 3;
    r += R * wt; g += G * wt; b += Bb * wt; wsum += wt;
  }
  r /= wsum; g /= wsum; b /= wsum;
  const mx = Math.max(r, g, b, 1), k = Math.min(2.2, 210 / mx);
  const col = `${Math.round(r * k)}, ${Math.round(g * k)}, ${Math.round(b * k)}`;
  if (col === vAmbient.last) return;
  vAmbient.last = col;
  const L = [...document.querySelectorAll('#vbg .vamb')];
  const on = L.find(l => l.classList.contains('on')), nx = on === L[0] ? L[1] : L[0];
  nx.style.background = `radial-gradient(75% 55% at 50% 108%, rgba(${col}, .34), transparent 72%), radial-gradient(35% 45% at 88% 22%, rgba(${col}, .14), transparent 70%)`;
  nx.classList.add('on');
  if (on) on.classList.remove('on');
}
function vAmbient(img) {
  // shrink the picture off the main thread first (drawing the full picture here stalled the remote)
  if (window.createImageBitmap && !img.__small) {
    createImageBitmap(img, { resizeWidth: 24, resizeHeight: 14, resizeQuality: 'low' })
      .then(bm => { bm.__small = true; vAmbient(bm); }).catch(() => { img.__small = true; vAmbient(img); });
    return;
  }
  try {
    const c = vAmbient.c || (vAmbient.c = document.createElement('canvas'));
    c.width = 24; c.height = 14;
    const x = c.getContext('2d', { willReadFrequently: true });
    x.drawImage(img, 0, 0, 24, 14);
    const d = x.getImageData(0, 0, 24, 14).data;
    let r = 0, g = 0, b = 0, w = 0;
    for (let i = 0; i < d.length; i += 4) {
      const R = d[i], G = d[i + 1], Bb = d[i + 2];
      const mx = Math.max(R, G, Bb), mn = Math.min(R, G, Bb);
      const wt = 0.15 + (mx - mn) / 255 * (mx / 255) * 3;        // favour bright, colourful pixels
      r += R * wt; g += G * wt; b += Bb * wt; w += wt;
    }
    r /= w; g /= w; b /= w;
    const mx = Math.max(r, g, b, 1), k = Math.min(2.2, 210 / mx);   // lift it to a glowing tone
    const col = `${Math.round(r * k)}, ${Math.round(g * k)}, ${Math.round(b * k)}`;
    if (col === vAmbient.last) return;
    vAmbient.last = col;
    const L = [...document.querySelectorAll('#vbg .vamb')];
    const on = L.find(l => l.classList.contains('on')), nx = on === L[0] ? L[1] : L[0];
    nx.style.background = `radial-gradient(75% 55% at 50% 108%, rgba(${col}, .34), transparent 72%), radial-gradient(35% 45% at 88% 22%, rgba(${col}, .14), transparent 70%)`;
    nx.classList.add('on');
    if (on) on.classList.remove('on');
  } catch (e) { /* picture from another site without permission: keep the theme's own glow */ }
}

/* ---------------------------------------------------------------- actions */

function vOpen(m) {
  if (!m || !m.id) return;
  vTrStop();
  const via = B.openInStremioApp(m.type, m.id, '');
  if (!via) { toast('Nuvio or Stremio isn\'t installed'); return; }
  const app = via.replace(/\s+v?\d[\w.\-]*(\s*\(.*\))?$/, '').replace(/\s*\(no title links\)$/, '');
  if (typeof showLaunch === 'function') showLaunch({ bg: m.background || m.poster, logo: m.logo, title: m.name, sub: `Opening in ${app}…` });
  else toast(`Opening “${m.name}” in ${via}…`);
}
function vTrailer(m) {
  if (!m) return;
  vTrStop();
  if (!m.yt) { toast('No trailer for this title'); return; }
  if (typeof showLaunch === 'function') showLaunch({ bg: m.background || m.poster, logo: m.logo, title: m.name, sub: 'Opening the trailer in YouTube…' });
  B.openTrailer(m.yt);
}

/* ---------------------------------------------------------------- remote keys */

function vodKeyDown(k, e) {
  const r = V.rows[V.r];
  const n = r && r.items ? r.items.length : 0;
  const c = V.cols[V.r] || 0;
  if (k === 'u') { if (V.r === 0) return enterTop(); V.r--; vLoadRow(V.r); vFocus(true); }
  else if (k === 'd') { if (V.r < V.rows.length - 1) { V.r++; vLoadRow(V.r); vLoadRow(V.r + 1); vFocus(true); } }
  else if (k === 'l') { if (c > 0) { V.cols[V.r] = c - 1; vFocus(true); } }
  else if (k === 'r') { if (c < n - 1) { V.cols[V.r] = c + 1; vFocus(true); } }
  else if (k === 'ok') {
    // short press opens in Nuvio, long press plays the trailer
    if ((e && e.repeat) || V.okHold) return;
    const m = vCur();
    if (!m) return;
    V.okHold = setTimeout(() => { V.okHold = 'long'; vMenu(m, vTrEl()); }, 600);
    const up = ev => {
      if ((KEYMAP[ev.key] || (ev.keyCode === 23 ? 'ok' : null)) !== 'ok') return;
      document.removeEventListener('keyup', up, true);
      const long = V.okHold === 'long';
      clearTimeout(V.okHold); V.okHold = null;
      if (!long) vOpen(m);
    };
    document.addEventListener('keyup', up, true);
  }
}

function vodBack() {
  if (V.r > 0) { V.r = 0; vFocus(true); return; }
  switchTab('home');
}

/* ---------------------------------------------------------------- touch */

function vPosterAt(t) {
  const p = t && t.closest ? t.closest('.vp') : null;
  if (!p) return null;
  const same = +p.dataset.r === V.r && +p.dataset.c === (V.cols[V.r] || 0);
  V.r = +p.dataset.r; V.cols[V.r] = +p.dataset.c;
  vMark(); vHero();
  if (!same) vTrStop();
  if (!same || !VT.key) vTrSchedule();
  return vCur();
}
function vodTap(t) {
  const p = t && t.closest ? t.closest('.vp') : null;
  if (!p) return;
  // with previews on, the first tap picks the title (and starts its preview), a second tap opens it
  const picked = p.classList.contains('f');
  const m = vPosterAt(t);
  if (m && (picked || vTrMode() === 'off')) vOpen(m);
}
function vodHold(t) { const m = vPosterAt(t); if (m) { vMenu(m, t.closest('.vp')); return true; } return false; }

/* ---------------------------------------------------------------- My List & the hold menu */

function vInList(m) { return !!m && V.mine.some(x => x.id === m.id && x.type === m.type); }
function vToggleList(m) {
  if (!m) return;
  const had = vInList(m);
  V.mine = had ? V.mine.filter(x => !(x.id === m.id && x.type === m.type)) : [Object.assign({}, m)].concat(V.mine).slice(0, 200);
  writeJSON('mylist', V.mine);
  toast(had ? `Removed “${m.name}” from My List` : `Added “${m.name}” to My List`);
  if (typeof searchRefresh === 'function') searchRefresh();
  if (window.tshelfRebuild) tshelfRebuild();
  if (m.type !== V.type || !V.rows.length) return;
  // rebuild the rows, keeping your place
  const curId = V.rows[V.r] && V.rows[V.r].id, col = V.cols[V.r] || 0, keepCols = {};
  V.rows.forEach((r, i) => { keepCols[r.id] = V.cols[i] || 0; });
  vTrStop();
  const top = $('#vrows').scrollTop;
  V.rows = vRowDefs(V.type).map(d => Object.assign({ items: null, err: '' }, d));
  vBuildRows(true);
  V.cols = {};
  V.rows.forEach((r, i) => { if (keepCols[r.id]) V.cols[i] = keepCols[r.id]; });
  let i = V.rows.findIndex(r => r.id === curId);
  if (i < 0) i = 0;
  V.r = i;
  if (V.rows[i] && V.rows[i].mine) V.cols[i] = Math.min(col, Math.max(0, V.mine.filter(x => x.type === V.type).length - 1));
  V.rows.forEach((r, j) => { if (Math.abs(j - i) <= 2) vLoadRow(j); });
  $('#vrows').scrollTop = top;
  vFocus(false);
}
function vMenu(m, anchor) {
  if (!m) return;
  vTrStop();
  const inList = vInList(m);
  showMenu([
    ['open', 'Open in Nuvio', () => vOpen(m)],
    ...(typeof lkMenuItem === 'function' ? lkMenuItem('Open', { type: 'open', what: 'title', m }) : []),
    ...(m.yt ? [['play', 'Watch trailer', () => vTrailer(m)]] : []),
    [inList ? 'check' : 'add', inList ? 'Remove from My List' : 'Add to My List', () => vToggleList(m)]
  ], anchor, m.name);
}

/* The screen turned (phone): re-measure posters and the guide. */
window.onTurn = () => {
  vTrStop();
  if (isVodTab()) { vFocus(false); vHero(); }
  if (tab === 'live' && typeof lvDraw === 'function') { LV.needBuild = true; lvDraw(); }
};

/* ---------------------------------------------------------------- trailer previews
   Resting on a title expands its poster into a wide card with the backdrop, then plays the
   YouTube trailer in it (or behind the title, per Settings → Trailer previews). Uses YouTube's
   official embedded player, muted unless Settings → Trailer sound is on. */

const VT = { t: 0, t2: 0, t3: 0, key: '', host: null, player: null };
let vYTp = null;

document.addEventListener('pointerdown', e => { if (e.pointerType === 'touch' && !V.touch) { V.touch = true; if (mode === 'vod') vHero(); } }, true);
/* A trailer behind the title (Settings → Header trailer): the rows slide down out of its way, and the
   first press (or touch) only brings them back; or ("fit") it plays in a box above the rows. */
function vTrFitClass() { body.classList.toggle('trfit', S.trailerFit === 'fit'); }
vTrFitClass();
function vTrTuck() {
  const on = $('#vtrbg').classList.contains('play') && S.trailerFit !== 'fit' && !VT.untucked && mode === 'vod' && !body.classList.contains('portrait');
  body.classList.toggle('trtuck', on);
}
new MutationObserver(vTrTuck).observe($('#vtrbg'), { attributes: true, attributeFilter: ['class'] });
let vTrSwallow = '';
const vTrUntuck = e => {
  if (!body.classList.contains('trtuck')) return;
  VT.untucked = true;
  body.classList.remove('trtuck');
  if (e.type === 'keydown') vTrSwallow = e.key;
  e.preventDefault(); e.stopImmediatePropagation();
};
document.addEventListener('keydown', vTrUntuck, true);
document.addEventListener('pointerdown', vTrUntuck, true);
document.addEventListener('keyup', e => { if (vTrSwallow && e.key === vTrSwallow) { vTrSwallow = ''; e.preventDefault(); e.stopImmediatePropagation(); } }, true);
document.addEventListener('keydown', () => { if (V.touch) { V.touch = false; if (mode === 'vod') vHero(); } }, true);
window.addEventListener('resize', () => { if (VT.host) vTrSize(VT.host); });

function vTrMode() { return S.trailers || 'card'; }
function vTrEl() { return document.querySelector(`.vp[data-r="${V.r}"][data-c="${V.cols[V.r] || 0}"]`); }

function vYT() {
  if (window.YT && window.YT.Player) return Promise.resolve(window.YT);
  if (!vYTp) vYTp = new Promise((res, rej) => {
    const prev = window.onYouTubeIframeAPIReady;
    window.onYouTubeIframeAPIReady = () => { if (prev) try { prev(); } catch (e) {} res(window.YT); };
    const sc = document.createElement('script');
    sc.src = 'https://www.youtube.com/iframe_api';
    sc.onerror = () => { vYTp = null; sc.remove(); rej(new Error('YouTube unavailable')); };
    document.head.appendChild(sc);
  });
  return vYTp;
}

function vTrStop() {
  clearTimeout(VT.t); clearTimeout(VT.t2); clearTimeout(VT.t3); clearTimeout(VT.tp);
  VT.key = '';
  VT.untucked = false;
  VT.want = null;
  vTrKillVid();
  const p = VT.player, h = VT.host;
  VT.player = null; VT.host = null;
  if (h) h.classList.remove('play');
  if (VT.shared && p === VT.shared.player) {
    // one player is kept and reused: pause it and park it out of sight (starting a new YouTube
    // player costs the TV a second or more, and it runs on the same thread as TopShelf)
    try { p.mute(); p.pauseVideo(); } catch (e) {}
    try { if (VT.shared.wrap.parentNode !== vTrPark()) vTrPark().moveBefore(VT.shared.wrap, null); } catch (e) {}
    if (h && !h.contains(VT.shared.wrap)) h.innerHTML = '';
  } else if (p) {
    try { p.mute(); p.pauseVideo(); } catch (e) {}
    setTimeout(() => { try { p.destroy(); } catch (e) {} if (h && VT.host !== h) h.innerHTML = ''; }, 500);
  } else if (h) h.innerHTML = '';
  document.querySelectorAll('.vp.x').forEach(el => el.classList.remove('x'));
}

function vTrSchedule() {
  clearTimeout(VT.t);
  if (vTrMode() === 'off' || mode !== 'vod') return;
  // only once you've rested on a title long enough to read about it (Settings → Trailer starts after)
  VT.t = setTimeout(vTrStart, Math.max(1, S.trailerDelay || 5) * 1000);
  clearTimeout(VT.tp);
  VT.tp = setTimeout(vTrPrefetch, 2500);
}

function vTrStart() {
  const m = vCur();
  if (!m || mode !== 'vod' || !$('#vod').classList.contains('on')) return;
  const how = vTrMode();
  if (how === 'off') return;
  const key = V.type + ':' + V.r + ':' + (V.cols[V.r] || 0) + ':' + m.id;
  VT.key = key;
  if (how === 'bg') {
    if (!m.yt) return;
    VT.host = $('#vtrbg');
    VT.t2 = setTimeout(() => vTrPlay(m, VT.host, key), 0);
    return;
  }
  const el = vTrEl();
  const bg = m.background || '';
  if (!el || !bg) return;
  let x = el.querySelector('.vpx');
  if (!x) {
    x = document.createElement('div');
    x.className = 'vpx';
    x.innerHTML = '<div class="vpxb"></div><div class="vtr"></div><div class="vpxg"></div>' +
      (m.logo ? `<img class="vpxl" src="${esc(m.logo)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block'"><div class="vpxt" style="display:none">${esc(m.name)}</div>`
              : `<div class="vpxt">${esc(m.name)}</div>`);
    el.insertBefore(x, el.querySelector('.rim'));
  }
  VT.host = x.querySelector('.vtr');
  const img = new Image();
  let done = false;
  const grow = () => {
    if (done || VT.key !== key) return;
    done = true;
    x.querySelector('.vpxb').style.backgroundImage = `url("${bg.replace(/"/g, '%22')}")`;
    el.classList.add('x');
    vTrFit(el);
    if (m.yt) VT.t2 = setTimeout(() => vTrPlay(m, VT.host, key), 600);
  };
  img.onload = grow; img.onerror = grow;
  img.src = bg;
  VT.t3 = setTimeout(grow, 1500);
}

// scroll the row so the widened card is fully on screen
function vTrFit(el) {
  const strip = el.closest('.vstrip');
  if (!strip) return;
  const rem = remPx(), pad = rem * 2.5;
  const left = vCardLeft(+el.dataset.c), w = rem * vDims().xw;
  const view = strip.clientWidth;
  let to = strip.scrollLeft;
  if (left + w > to + view - pad) to = left + w - view + pad;
  if (left < to + pad) to = left - pad;
  if (Math.abs(to - strip.scrollLeft) > 2) strip.scrollTo({ left: Math.max(0, to), behavior: 'smooth' });
}

// cover-crop the 16:9 video to its frame, a little oversized so YouTube's title bar and logo sit off-frame
function vTrSize(host) {
  const f = host.querySelector('iframe');
  const w = host.querySelector('.vtrw');
  if (w) w.style.cssText = 'position:absolute;inset:0';
  if (!f) return;
  const W = host.offsetWidth, H = host.offsetHeight;
  if (!W || !H) return;
  const s = Math.max(W / 16, H / 9) * 1.2;
  f.style.cssText = `position:absolute;width:${16 * s}px;height:${9 * s}px;left:${(W - 16 * s) / 2}px;top:${(H - 9 * s) / 2}px;border:0;pointer-events:none`;
}

function vTrPark() {
  let p = document.getElementById('vtrpark');
  if (!p) { p = document.createElement('div'); p.id = 'vtrpark'; p.style.cssText = 'position:fixed;left:-4000px;top:0;width:640px;height:360px;visibility:hidden;pointer-events:none'; document.body.appendChild(p); }
  return p;
}
const VT_REUSE = typeof Element !== 'undefined' && typeof Element.prototype.moveBefore === 'function';

/* Like Nuvio (and the TV): the trailer's video comes straight from YouTube and plays in a plain
   video element here, shown from its first frame — no waiting for the embedded player's title bar
   and logo to go. If that can't be done, YouTube's embedded player is used as before. */
function vTrMax() { return vTrMode() === 'bg' ? 1080 : 720; }
function vTrPlay(m, host, key) {
  if (VT.key !== key || !host) return;
  if (!B.trailerSource || VT.noDirect) { vTrPlayYT(m, host, key); return; }
  VT.want = { yt: m.yt, key, host, m };
  try { B.trailerSource(m.yt, vTrMax(), !!S.trailerSound); } catch (e) { VT.want = null; vTrPlayYT(m, host, key); }
}
window.tsTrailerSrc = (yt, j) => {
  const w = VT.want;
  if (!w || w.yt !== yt) return;
  VT.want = null;
  if (VT.key !== w.key) return;
  let s = null; try { s = JSON.parse(j); } catch (e) {}
  if (!s || !s.ok) { vTrPlayYT(w.m, w.host, w.key); return; }
  const h = w.host;
  if (VT.shared && h.contains(VT.shared.wrap)) { try { vTrPark().moveBefore(VT.shared.wrap, null); } catch (e) {} }
  h.innerHTML = '';
  const v = document.createElement('video');
  v.muted = true; v.playsInline = true; v.autoplay = true; v.preload = 'auto';
  v.setAttribute('playsinline', ''); v.setAttribute('muted', '');
  v.disableRemotePlayback = true;
  v.src = s.v;
  const a = s.a ? new Audio() : null;
  if (a) { a.preload = 'auto'; a.src = s.a; }
  h.appendChild(v);
  const vid = VT.vid = { v, a, host: h, sync: 0 };
  const shown = () => { if (VT.vid === vid && VT.key === w.key) h.classList.add('play'); };
  let first = true;
  v.addEventListener('playing', () => {
    if (VT.vid !== vid) return;
    if (first) { first = false; setTimeout(shown, 150); if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(shown); }
    if (a) { try { if (Math.abs(a.currentTime - v.currentTime) > .25) a.currentTime = v.currentTime; } catch (e) {} a.play().catch(() => {}); }
  });
  if (a) {
    // the sound is its own stream here: kept with the picture
    v.addEventListener('waiting', () => a.pause());
    v.addEventListener('pause', () => a.pause());
    vid.sync = setInterval(() => { try { if (!v.paused && Math.abs(a.currentTime - v.currentTime) > .3) a.currentTime = v.currentTime; } catch (e) {} }, 1000);
  }
  v.addEventListener('ended', () => { if (VT.vid === vid) h.classList.remove('play'); if (a) a.pause(); });
  v.addEventListener('error', () => {
    if (VT.vid !== vid) return;
    vTrKillVid();
    try { B.trailerFailed(yt); } catch (e) {}
    if (VT.key === w.key) vTrPlayYT(w.m, h, w.key);
  });
  v.play().catch(() => {});
};
/** stops the video element and lets go of its decoder */
function vTrKillVid() {
  const d = VT.vid;
  VT.vid = null;
  if (!d) return;
  clearInterval(d.sync);
  for (const x of [d.v, d.a]) if (x) { try { x.pause(); x.removeAttribute('src'); x.load(); } catch (e) {} }
  try { d.v.remove(); } catch (e) {}
}
// resting on a title: its trailer is looked up before the preview starts
function vTrPrefetch() {
  const m = vCur();
  if (m && m.yt && B.trailerPrefetch && !VT.noDirect) try { B.trailerPrefetch(m.yt, vTrMax(), !!S.trailerSound); } catch (e) {}
}

function vTrPlayYT(m, host, key) {
  if (VT.key !== key || !host) return;
  if (VT_REUSE && VT.shared && !VT.shared.ready) {
    // the player is still starting up: move it here; it plays this title once it's ready
    try { host.innerHTML = ''; host.moveBefore(VT.shared.wrap, null); VT.player = VT.shared.player; VT.cur = { key, host, muted: !S.trailerSound, yt: m.yt }; vTrSize(host); return; }
    catch (e) { VT.shared = null; }
  }
  if (VT_REUSE && VT.shared && VT.shared.ready) {
    // move the existing player into this card (moveBefore keeps it running) and switch the video
    const sh = VT.shared;
    try {
      host.innerHTML = '';
      host.moveBefore(sh.wrap, null);
      VT.player = sh.player; VT.cur = { key, host, muted: !S.trailerSound };
      vTrSize(host);
      sh.player.loadVideoById({ videoId: m.yt });
      if (VT.cur.muted) sh.player.mute(); else sh.player.unMute();
      VT.t3 = setTimeout(() => { if (VT.key === key && !host.classList.contains('play')) try { sh.player.mute(); sh.player.playVideo(); } catch (err) {} }, 4000);
      return;
    } catch (e) { VT.shared = null; }
  }
  vYT().then(YT => {
    if (VT.key !== key) return;
    if (VT_REUSE) {
      // the first trailer creates the player that every later one reuses
      host.innerHTML = '';
      const wrap = document.createElement('div');
      wrap.className = 'vtrw';
      wrap.innerHTML = '<div></div>';
      host.appendChild(wrap);
      const muted = !S.trailerSound;
      VT.cur = { key, host, muted, yt: m.yt };
      const W = host.offsetWidth || 640, H = host.offsetHeight || 360;
      const sh = VT.shared = { wrap, player: null, ready: false, firstYt: m.yt };
      sh.player = VT.player = new YT.Player(wrap.firstChild, {
        host: 'https://www.youtube-nocookie.com', videoId: m.yt, width: W, height: H,
        playerVars: { autoplay: 1, mute: muted ? 1 : 0, controls: 0, disablekb: 1, fs: 0, iv_load_policy: 3, modestbranding: 1,
                      playsinline: 1, rel: 0, cc_load_policy: 0, origin: location.origin },
        events: {
          onReady: e => {
            sh.ready = true;
            const c = VT.cur;
            if (!c || VT.key !== c.key) { try { e.target.mute(); e.target.pauseVideo(); } catch (err) {} return; }
            vTrSize(c.host);
            try { if (c.muted) e.target.mute(); else e.target.unMute(); if (c.yt && c.yt !== sh.firstYt) e.target.loadVideoById({ videoId: c.yt }); else e.target.playVideo(); } catch (err) {}
            VT.t3 = setTimeout(() => { if (VT.cur === c && VT.key === c.key && !c.host.classList.contains('play')) try { e.target.mute(); e.target.playVideo(); } catch (err) {} }, 4000);
          },
          onStateChange: e => {
            const c = VT.cur;
            if (!c || VT.key !== c.key) { if (e.data === 1) try { e.target.pauseVideo(); } catch (err) {} return; }
            if (e.data === 1) { vTrSize(c.host); setTimeout(() => { if (VT.cur === c && VT.key === c.key) { vTrSize(c.host); c.host.classList.add('play'); } }, 400); }
            else if (e.data === 0) c.host.classList.remove('play');
          },
          onError: () => { const c = VT.cur; if (c) c.host.classList.remove('play'); }
        }
      });
      const f = wrap.querySelector('iframe');
      if (f) { f.setAttribute('allow', 'autoplay; encrypted-media'); f.tabIndex = -1; }
      vTrSize(host);
      return;
    }
    host.innerHTML = '<div></div>';
    const muted = !S.trailerSound;
    const W = host.offsetWidth || 640, H = host.offsetHeight || 360;
    VT.player = new YT.Player(host.firstChild, {
      host: 'https://www.youtube-nocookie.com', videoId: m.yt, width: W, height: H,
      playerVars: { autoplay: 1, mute: muted ? 1 : 0, controls: 0, disablekb: 1, fs: 0, iv_load_policy: 3, modestbranding: 1,
                    playsinline: 1, rel: 0, cc_load_policy: 0, origin: location.origin },
      events: {
        onReady: e => {
          if (VT.key !== key) return;
          vTrSize(host);
          try { if (muted) e.target.mute(); else e.target.unMute(); e.target.playVideo(); } catch (err) {}
          // if it won't start with sound, try again muted
          VT.t3 = setTimeout(() => { if (VT.key === key && !host.classList.contains('play')) try { e.target.mute(); e.target.playVideo(); } catch (err) {} }, 4000);
        },
        onStateChange: e => {
          if (VT.key !== key) return;
          if (e.data === 1) { vTrSize(host); setTimeout(() => { if (VT.key === key) { vTrSize(host); host.classList.add('play'); } }, 400); }   // past YouTube's start-up overlay
          else if (e.data === 0) host.classList.remove('play');                                          // ended: back to the still
        },
        onError: () => host.classList.remove('play')
      }
    });
    const f = host.querySelector('iframe');
    if (f) { f.setAttribute('allow', 'autoplay; encrypted-media'); f.tabIndex = -1; }
    vTrSize(host);
  }).catch(() => {});
}

// start the colour worker while the TV is idle (starting it on the first visit to Movies cost a sixth of a second)
setTimeout(() => {
  const go = () => { if (typeof OffscreenCanvas !== 'undefined' && VAMB.w === null) vAmbientWorker(); };
  if (window.requestIdleCallback) requestIdleCallback(go, { timeout: 5000 }); else go();
}, 12000);
