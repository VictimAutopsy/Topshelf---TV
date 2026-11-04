'use strict';
/* =====================================================================
   Live TV extras: programme reminders, hidden categories, the channel list over
   full-screen video, and "previous channel". Shares globals with live.js etc.
   ===================================================================== */

const BELL_SVG = '<svg class="lbell" viewBox="0 0 24 24" fill="currentColor"><path d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22zm7-6V11a7 7 0 0 0-5.5-6.84V3.5a1.5 1.5 0 0 0-3 0v.66A7 7 0 0 0 5 11v5l-1.7 1.7a.8.8 0 0 0 .56 1.3h16.28a.8.8 0 0 0 .56-1.3z"/></svg>';
ICONS.bell = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 16v-5a6 6 0 1 0-12 0v5l-2 2h16zM10 21h4"/></svg>';

function lvChanByUrl(u) {
  if (!LV.loaded) { try { lvLoad(); } catch (e) {} }
  return LV.chans.find(c => c.u === u) || null;
}

/* ---------------------------------------------------------------- reminders */

const REMS = { list: readJSON('reminders', []), shown: new Set() };
function remId(u, start) { let h = 0; const s = u + '|' + start; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0; return 'r' + (h >>> 0).toString(36); }
function remHas(u, start) { return REMS.list.some(r => r.u === u && r.at === start); }
function remSave() { writeJSON('reminders', REMS.list); }
function remRedraw() {
  if (tab === 'live' && typeof lvDraw === 'function') { for (const el of LV.rendered.values()) lvDropRow(el); LV.rendered = new Map(); lvRenderWindow(); lvMark(); }
  if (typeof searchRefresh === 'function') searchRefresh();
}
function remAdd(ch, p) {
  const r = { id: remId(ch.u, p[0]), u: ch.u, n: ch.n, num: chNum(ch), at: p[0], end: p[1], title: p[2] || 'Untitled' };
  if (remHas(r.u, r.at)) return;
  REMS.list = REMS.list.concat([r]).sort((a, b) => a.at - b.at);
  remSave();
  try { B.askNotifications && B.askNotifications(); } catch (e) {}
  try { B.setReminder && B.setReminder(r.id, r.at, `Starting soon: ${r.title}`, `${r.num} · ${r.n} at ${fmtTime(r.at)}`, r.u); } catch (e) {}
  toast(`We'll remind you when “${r.title}” starts (${fmtTime(r.at)})`);
  remRedraw();
}
function remRemove(id, quiet) {
  const r = REMS.list.find(x => x.id === id);
  REMS.list = REMS.list.filter(x => x.id !== id);
  remSave();
  try { B.cancelReminder && B.cancelReminder(id); } catch (e) {}
  if (r && !quiet) toast(`Reminder for “${r.title}” canceled`);
  remRedraw();
}
/* Every so often: drop old reminders, and pop up the ones about to start (while TopShelf is open). */
function remTick() {
  const now = Date.now();
  const old = REMS.list.filter(r => r.end < now);
  if (old.length) { REMS.list = REMS.list.filter(r => r.end >= now); remSave(); }
  if (document.hidden || mode === 'edit' || mode === 'menu') return;
  const due = REMS.list.find(r => r.at - now < 90000 && !REMS.shown.has(r.id));
  if (!due) return;
  REMS.shown.add(due.id);
  const startsIn = Math.round((due.at - now) / 60000);
  showMenu([
    ['play', 'Watch now', () => { remRemove(due.id, true); remWatch(due.u); }],
    ['hide', 'Dismiss', () => remRemove(due.id, true)]
  ], null, `${startsIn > 0 ? `In ${startsIn} min` : 'Starting now'} · ${due.title} · ${due.num} ${due.n}`);
  if (mode === 'menu') $('#menu').classList.add('alert');
}
function remWatch(u) {
  const ch = lvChanByUrl(u);
  if (!ch) { toast("That channel isn't in your playlist any more"); return; }
  if (mode === 'search') closeSearch();
  if (mode === 'settings') closeSettings();
  if (mode === 'shelf') exitShelf();
  srWatch(ch);
}
// re-arm alarms (they don't survive a restart) and keep checking
(function remStart() {
  const now = Date.now();
  REMS.list = REMS.list.filter(r => r.end >= now);
  remSave();
  for (const r of REMS.list) if (r.at > now) { try { B.setReminder(r.id, r.at, `Starting soon: ${r.title}`, `${r.num} · ${r.n} at ${fmtTime(r.at)}`, r.u); } catch (e) {} }
  setInterval(remTick, 15000);
  setTimeout(remTick, 4000);
})();
/* A reminder notification was tapped. */
window.tsLaunchChannel = () => {
  let u = '';
  try { u = B.takeLaunchChannel ? B.takeLaunchChannel() : ''; } catch (e) {}
  if (!u) return;
  REMS.list.filter(r => r.u === u && r.at - Date.now() < 10 * 60000).forEach(r => remRemove(r.id, true));
  setTimeout(() => remWatch(u), body.classList.contains('booting') ? 1600 : 0);
};
setTimeout(window.tsLaunchChannel, 300);

function remMenu() {
  if (!REMS.list.length) { toast('No reminders set'); return; }
  showMenu(REMS.list.slice(0, 8).map(r => ['bell', `${remWhen(r.at)} · ${r.title} (${r.n})`, () => setTimeout(() => showMenu([
    ['play', 'Watch this channel', () => { closeSettings(); remWatch(r.u); }],
    ['hide', 'Cancel reminder', () => { remRemove(r.id); refreshSettings(); }]
  ], null, r.title), 50)]), null, 'Reminders');
}
function remWhen(t) {
  const d = new Date(t), today = new Date();
  const tomorrow = new Date(); tomorrow.setDate(today.getDate() + 1);
  const day = d.toDateString() === today.toDateString() ? 'Today' : d.toDateString() === tomorrow.toDateString() ? 'Tomorrow' : d.toLocaleDateString(undefined, { weekday: 'short' });
  return `${day} ${fmtTime(t)}`;
}

/* ---------------------------------------------------------------- guide search (used by search.js) */

function guideSearch(q) {
  if (!B.liveSearchPrograms || !LV.chans.length) return [];
  let rows = [];
  try { rows = JSON.parse(B.liveSearchPrograms(q, Date.now(), Date.now() + 7 * 86400000)) || []; } catch (e) { return []; }
  const hid = lvHiddenCats();
  const byKey = new Map();
  for (const ch of LV.chans) {
    if (hid.has(ch.g)) continue;
    const cur = byKey.get(ch.k);
    if (!cur || (LV.favs.has(ch.u) && !LV.favs.has(cur.u))) byKey.set(ch.k, ch);
  }
  return rows.map(r => ({ ch: byKey.get(r[0]), p: [r[1], r[2], r[3], r[4]] })).filter(x => x.ch).slice(0, 40);
}

/* ---------------------------------------------------------------- hidden categories */

function lvHiddenCats() { return new Set(readJSON('liveHiddenCats', [])); }
function lvSetHidden(set) {
  writeJSON('liveHiddenCats', Array.from(set));
  const cur = LV.cats[LV.cat] && LV.cats[LV.cat].name;
  lvLoad();
  const i = LV.cats.findIndex(c => c.name === cur);
  LV.cat = i >= 0 ? i : LV.cats.findIndex(c => c.all);
  lvBuildList();
  LV.row = Math.min(LV.row, Math.max(0, LV.list.length - 1));
  LV.needBuild = true;
  LV.catsKey = '';
  if (tab === 'live') lvDraw();
}
function lvCatMenu(i) {
  const c = LV.cats[i];
  if (!c || !c.group) return;
  const el = document.querySelector(`.lcat[data-cat="${i}"]`);
  showMenu([['hide', 'Hide this category', () => {
    const h = lvHiddenCats(); h.add(c.group); lvSetHidden(h);
    toast(`“${c.name}” hidden · Settings → Hidden categories brings it back`);
  }]], el, `${c.name} · ${c.count} channels`);
}
/* Settings › Live TV › Channel groups: every group of the playlist with Shown / Hidden, a box to
   narrow the list, and Hide all / Show all / Show only these for whatever the box matches. Changes are
   saved right away; the guide is rebuilt once, when you leave this page. */
const LG = { q: '', dirty: false, hid: new Set() };
function lgGroups() {
  if (typeof LV !== 'undefined' && !LV.loaded) { try { lvLoad(); } catch (e) {} }
  const n = new Map();
  for (const c of (LV.chans || [])) n.set(c.g, (n.get(c.g) || 0) + 1);
  return Array.from(n, ([g, count]) => ({ g, count }));
}
function lgMatch(g) { const q = LG.q.trim().toLowerCase(); return !q || q.split(/\s*,\s*/).filter(Boolean).some(w => g.toLowerCase().includes(w)); }
function lgSave(set) { LG.hid = set; writeJSON('liveHiddenCats', Array.from(set)); LG.dirty = true; }
function lgToggle(g) { const s = new Set(LG.hid); if (s.has(g)) s.delete(g); else s.add(g); lgSave(s); }
function lgRows() {
  const all = lgGroups(), hid = LG.hid = lvHiddenCats();
  const shown = all.filter(x => lgMatch(x.g));
  const what = LG.q.trim() ? 'matching' : '';
  const rows = [
    { label: 'Find groups', value: () => LG.q || `${all.length} groups · ${LG.hid.size} hidden`,
      edit: { get: () => LG.q, set: v => { LG.q = v || ''; }, ph: 'e.g. USA, Sports, UK (commas for more)' } },
    { label: what ? `Hide all matching (${shown.length})` : `Hide all (${all.length})`, value: () => '',
      ok: () => { const s = new Set(LG.hid); shown.forEach(x => s.add(x.g)); lgSave(s); toast(`${shown.length} group${shown.length === 1 ? '' : 's'} hidden`); } },
    { label: what ? `Show all matching (${shown.length})` : 'Show all', value: () => { const n = shown.filter(x => LG.hid.has(x.g)).length; return n ? `${n} hidden` : ''; },
      ok: () => { const s = new Set(LG.hid); shown.forEach(x => s.delete(x.g)); lgSave(s); toast('Groups shown'); } }
  ];
  if (what) rows.push({ label: 'Show only these', value: () => `hide the other ${all.length - shown.length}`,
    ok: () => { const s = new Set(all.filter(x => !lgMatch(x.g)).map(x => x.g)); lgSave(s); toast(`Showing ${shown.length} group${shown.length === 1 ? '' : 's'}`); } });
  if (!shown.length) rows.push({ label: all.length ? 'No groups match' : 'No playlist loaded yet', value: () => '', gap: true, ok: () => {} });
  shown.slice(0, 400).forEach((x, i) => rows.push({ label: x.g, gap: i === 0,
    value: () => (LG.hid.has(x.g) ? 'Hidden' : 'Shown') + ` · ${x.count}`,
    ok: () => lgToggle(x.g), cycle: () => lgToggle(x.g) }));
  return rows;
}
/* leaving the page: the guide (the page's and the app's) picks up the new set of groups once */
function lgDone() {
  if (!LG.dirty) return;
  LG.dirty = false;
  lvSetHidden(lvHiddenCats());
  try { if (B.nhLiveGroups) B.nhLiveGroups(); } catch (e) {}
}

function hiddenCatsMenu() {
  const h = Array.from(lvHiddenCats());
  if (!h.length) { toast('No hidden categories'); return; }
  showMenu([['check', 'Show all categories', () => { lvSetHidden(new Set()); toast('All categories shown'); refreshSettings(); }]]
    .concat(h.slice(0, 9).map(g => ['check', `Show “${g}”`, () => { const s = lvHiddenCats(); s.delete(g); lvSetHidden(s); refreshSettings(); }])), null, 'Hidden categories');
}

function lvHiddenChansMenu() {
  const h = readJSON('liveHiddenChans', []);
  if (!h.length) { toast('No hidden channels · in Live TV, hold OK on a channel › Hide this channel'); return; }
  const done = (list, msg) => { writeJSON('liveHiddenChans', list); try { B.nhLiveGroups && B.nhLiveGroups(); } catch (e) {} refreshSettings(); toast(msg); };
  showMenu([['check', `Show all ${h.length} hidden channels`, () => done([], 'Hidden channels are back')]]
    .concat(h.slice(-8).reverse().map(c => ['check', `Show “${c.n}”`, () => done(readJSON('liveHiddenChans', []).filter(x => x.u !== c.u), `“${c.n}” is back`)])), null, 'Hidden channels');
}

/* ---------------------------------------------------------------- previous channel */

function lvPrevChannel() {
  const p = LV.prevCh;
  if (!p) { lvShowOsd(); toast('No previous channel yet'); return; }
  let i = LV.list.findIndex(c => c.u === p.u);
  if (i < 0) { LV.cat = LV.cats.findIndex(c => c.all); lvBuildList(); LV.needBuild = true; i = LV.list.findIndex(c => c.u === p.u); }
  LV.row = Math.max(0, i);
  LV.t = lvNow();
  lvPlay(LV.list[LV.row] || p, true);
}

/* ---------------------------------------------------------------- channel list over full-screen video */

const ZAP = { sel: 0, t: 0, first: 0, last: -1 };
/* pick (optional): choose a channel for something else (Multiview) instead of tuning to it */
function lvZapOpen(pick, selCh, title) {
  if (!LV.list.length) return;
  LV.zap = true;
  ZAP.pick = typeof pick === 'function' ? pick : null;
  const sel = selCh || LV.playing;
  ZAP.sel = Math.max(0, LV.list.findIndex(c => sel && c.u === sel.u));
  $('#osd').classList.remove('show');
  $('#lzap .lzh').textContent = (title ? title + ' · ' : '') + (LV.cats[LV.cat] ? LV.cats[LV.cat].name : 'Channels');
  $('#lzap .lzhint').textContent = typeof V !== 'undefined' && V.touch ? 'Tap a channel · swipe left to close' : `▲▼ choose · OK ${ZAP.pick ? 'pick' : 'watch'} · ◀ close`;
  ZAP.first = 0; ZAP.last = -1;
  lvZapDraw(true);
  $('#player').classList.add('zap');
  lvZapIdle();
}
function lvZapClose() {
  LV.zap = false;
  ZAP.pick = null;
  clearTimeout(ZAP.t);
  $('#player').classList.remove('zap');
}
function lvZapIdle() { clearTimeout(ZAP.t); ZAP.t = setTimeout(() => { if (LV.zap) lvZapClose(); }, 9000); }

/* Only the channels around the selection are drawn (playlists can hold thousands). */
function lvZapDraw(center) {
  const box = $('#lzlist');
  const n = LV.list.length, span = 60;
  if (center || ZAP.sel < ZAP.first + 8 || ZAP.sel > ZAP.last - 8) {
    ZAP.first = Math.max(0, ZAP.sel - span / 2);
    ZAP.last = Math.min(n - 1, ZAP.first + span);
    lvRebase();
    lvPrograms(LV.list.slice(ZAP.first, ZAP.last + 1).map(c => c.k));
    const now = lvNow();
    let h = ZAP.first > 0 ? '<div class="lzmore">⌃</div>' : '';
    for (let i = ZAP.first; i <= ZAP.last; i++) {
      const ch = LV.list[i], p = lvProgAt(ch, now);
      const prog = p ? (now - p[0]) / (p[1] - p[0]) : -1;
      h += `<div class="lzr" data-i="${i}"><span class="lzn">${esc(chNum(ch))}</span>` +
        (ch.l ? `<img class="lzl" loading="lazy" src="${esc(logoUrl(ch.l))}" onerror="this.style.visibility='hidden'">` : `<span class="lzl ph">${esc(ch.n.slice(0, 1))}</span>`) +
        `<span class="lzb"><b>${esc(ch.n)}${LV.favs.has(ch.u) ? ' ★' : ''}</b><i>${esc(p ? (p[2] || '') : '')}</i>` +
        (prog >= 0 ? `<em><u style="width:${(prog * 100).toFixed(0)}%"></u></em>` : '') + '</span></div>';
    }
    if (ZAP.last < n - 1) h += '<div class="lzmore">⌄</div>';
    box.innerHTML = h;
  }
  box.querySelectorAll('.lzr').forEach(el => {
    el.classList.toggle('f', +el.dataset.i === ZAP.sel);
    el.classList.toggle('on', !!(LV.playing && LV.list[+el.dataset.i] && LV.list[+el.dataset.i].u === LV.playing.u));
  });
  const f = box.querySelector('.lzr.f');
  if (f) {
    const top = f.offsetTop - box.clientHeight / 2 + f.offsetHeight / 2;
    box.scrollTo({ top: Math.max(0, top), behavior: center ? 'auto' : 'smooth' });
  }
}
function lvZapKey(k, e) {
  lvZapIdle();
  const n = LV.list.length;
  if (k === 'u') { ZAP.sel = (ZAP.sel - 1 + n) % n; lvZapDraw(ZAP.sel === n - 1); }
  else if (k === 'd') { ZAP.sel = (ZAP.sel + 1) % n; lvZapDraw(ZAP.sel === 0); }
  else if (k === 'l' || k === 'back') lvZapClose();
  else if (k === 'ok') {
    if (e && e.repeat) return;
    const ch = LV.list[ZAP.sel];
    // swallow the key-up so it doesn't also toggle the info banner
    const up = ev => { if ((KEYMAP[ev.key] || (ev.keyCode === 23 ? 'ok' : null)) === 'ok') { document.removeEventListener('keyup', up, true); ev.stopImmediatePropagation(); } };
    document.addEventListener('keyup', up, true);
    const pick = ZAP.pick;
    lvZapClose();
    if (pick) { if (ch) pick(ch); return; }
    if (ch && (!LV.playing || LV.playing.u !== ch.u)) { LV.row = ZAP.sel; LV.t = lvNow(); lvPlay(ch, true); }
  }
}
function lvZapTap(t) {
  const r = t.closest('.lzr');
  if (!r) { if (!t.closest('#lzap')) lvZapClose(); return; }
  const i = +r.dataset.i, ch = LV.list[i];
  const pick = ZAP.pick;
  lvZapClose();
  if (pick) { if (ch) pick(ch); return; }
  if (ch && (!LV.playing || LV.playing.u !== ch.u)) { LV.row = i; LV.t = lvNow(); lvPlay(ch, true); }
}
$('#lzlist').addEventListener('scroll', () => { noteScroll(); if (LV.zap) lvZapIdle(); }, { passive: true });
