'use strict';
/* =====================================================================
   Top Shelf: a row of wide artwork above the dock, like Apple TV's. It shows your
   My List first, then what's popular, and opens titles in Nuvio. By default it appears
   when Nuvio (or Stremio) is focused in the dock on a TV, and always on a phone.
   ===================================================================== */

const TS = { items: [], sel: 0, t: 0, busy: false, okDown: false, okTimer: 0, okLong: false, key: '' };
const TS_MODES = [['focus', 'When Nuvio is selected'], ['always', 'Always'], ['off', 'Off']];
function tshelfSetting() { return S.topShelf || (IS_TV ? 'focus' : 'always'); }
function tshelfApp(a) { return !!a && !a.special && /nuvio|stremio/i.test(a.label); }

function tshelfWanted() {
  if (tab !== 'home' || body.classList.contains('deep') || mode === 'shelf' || body.classList.contains('saver')) return false;
  const s = tshelfSetting();
  if (s === 'off') return false;
  const m = mode === 'menu' ? menuFrom : mode;
  if (m === 'tshelf') return true;
  if (m !== 'grid') return false;
  return s === 'always' || (focus < COLS && tshelfApp(items[focus]));
}
function tshelfUpdate() {
  const want = tshelfWanted();
  if (want !== body.classList.contains('tshelf-on')) body.classList.toggle('tshelf-on', want);
  if (want) tshelfLoad();
  tshelfMark();
}
window.tshelfUpdate = tshelfUpdate;

/* ---------------------------------------------------------------- content */

async function tshelfLoad(force) {
  const fresh = Date.now() - TS.t < 3 * 3600000;
  if (TS.busy || (fresh && !force)) { if (!TS.items.length || force) tshelfDraw(); return; }
  TS.busy = true;
  const pop = {};
  for (const type of ['movie', 'series']) {
    const url = `${CINEMETA}/catalog/${type}/top.json`;
    try {
      let c = V.cat[url];
      if (!c || Date.now() - c.t > 6 * 3600000) {
        const j = await vFetch(url);
        c = V.cat[url] = { t: Date.now(), metas: (j.metas || []).filter(m => m.poster).map(m => vSlim(Object.assign({ type }, m))) };
      }
      pop[type] = c.metas;
    } catch (e) { pop[type] = []; }
  }
  TS.pop = pop;
  TS.t = (pop.movie.length || pop.series.length) ? Date.now() : 0;
  TS.busy = false;
  tshelfDraw();
}

function tshelfDraw() {
  const pop = TS.pop || { movie: [], series: [] };
  const mine = V.mine.slice(0, 12);
  const seen = new Set(mine.map(m => m.type + m.id));
  const mixed = [];
  for (let i = 0; i < 12; i++) for (const t of ['movie', 'series']) { const m = pop[t][i]; if (m && !seen.has(m.type + m.id)) { seen.add(m.type + m.id); mixed.push(m); } }
  const list = mine.concat(mixed).filter(m => m.background || m.poster).slice(0, 24);
  const key = list.map(m => m.id).join(',');
  const head = $('#tshelf .tsh');
  head.textContent = mine.length ? 'My List  ·  Popular now' : 'Popular now';
  if (key === TS.key && TS.items.length) return;
  TS.key = key;
  TS.items = list;
  TS.sel = Math.min(TS.sel, Math.max(0, list.length - 1));
  $('#tshelf .tsin').innerHTML = list.length ? list.map((m, i) =>
    `<div class="tsc" data-i="${i}"><img class="tsb" loading="lazy" src="${esc(m.background || m.poster)}" onerror="this.style.visibility='hidden'">` +
    (m.logo ? `<img class="tsl" loading="lazy" src="${esc(m.logo)}" onerror="this.style.display='none';this.nextElementSibling.style.display='block'"><div class="tsn" style="display:none">${esc(m.name)}</div>`
            : `<div class="tsn">${esc(m.name)}</div>`) +
    `${vInList(m) ? '<i class="tsm">✓</i>' : ''}<div class="rim"></div></div>`).join('')
    : '<div class="tsc sk"></div>'.repeat(6);
  tshelfMark();
}
window.tshelfRebuild = () => { TS.key = ''; if (body.classList.contains('tshelf-on')) tshelfDraw(); };

/* ---------------------------------------------------------------- focus (remote) */

function tshelfMark() {
  const els = document.querySelectorAll('#tshelf .tsc');
  els.forEach(el => el.classList.toggle('f', mode === 'tshelf' && +el.dataset.i === TS.sel));
  if (mode !== 'tshelf') return;
  const el = els[TS.sel], strip = $('#tshelf .tss');
  if (!el || !strip) return;
  const pad = remPx() * 3;
  if (el.offsetLeft < strip.scrollLeft + pad || el.offsetLeft + el.offsetWidth > strip.scrollLeft + strip.clientWidth - pad)
    strip.scrollTo({ left: Math.max(0, el.offsetLeft - pad), behavior: 'smooth' });
}
/* Up from the dock steps into the row when it's showing. */
function tshelfEnter() {
  if (!body.classList.contains('tshelf-on') || !TS.items.length) return false;
  mode = 'tshelf';
  applyFocus();
  tshelfMark();
  return true;
}
window.tshelfEnter = tshelfEnter;
function tshelfLeave() { mode = 'grid'; applyFocus(); }

function tshelfKey(k, e) {
  if (!k) return;
  e.preventDefault();
  if (k === 'back') return tshelfLeave();
  const n = TS.items.length;
  if (k === 'ok') {
    if (e.repeat || TS.okDown) return;
    const m = TS.items[TS.sel];
    if (!m) return;
    TS.okDown = true; TS.okLong = false;
    TS.okTimer = setTimeout(() => { TS.okLong = true; TS.okDown = false; vMenu(m, document.querySelectorAll('#tshelf .tsc')[TS.sel]); }, 600);
    return;
  }
  if (k === 'l') { if (TS.sel > 0) TS.sel--; }
  else if (k === 'r') { if (TS.sel < n - 1) TS.sel++; }
  else if (k === 'd') return tshelfLeave();
  else if (k === 'u') { mode = 'grid'; return enterTop(); }
  tshelfMark();
}
function tshelfKeyUp(k) {
  if (k !== 'ok' || !TS.okDown) return;
  TS.okDown = false;
  clearTimeout(TS.okTimer);
  if (!TS.okLong && TS.items[TS.sel]) vOpen(TS.items[TS.sel]);
}

/* ---------------------------------------------------------------- touch */

function tshelfAt(t) { const c = t && t.closest ? t.closest('.tsc') : null; return c && c.dataset.i != null ? +c.dataset.i : -1; }
function tshelfTap(t) { const i = tshelfAt(t); if (i >= 0 && TS.items[i]) vOpen(TS.items[i]); }
function tshelfHold(t) { const i = tshelfAt(t); if (i < 0 || !TS.items[i]) return false; vMenu(TS.items[i], t.closest('.tsc')); return true; }

$('#tshelf .tss').addEventListener('scroll', noteScroll, { passive: true });
setTimeout(tshelfUpdate, 50);
