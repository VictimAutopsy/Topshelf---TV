/* Multiview: up to four Live TV channels on screen at once. The highlighted one has the sound.
   Each channel is its own stream, so IPTV plans that allow only one or two connections at a time
   will refuse the extra ones — the tile says so instead of failing silently. */

const MV = { tiles: [], f: 0, on: false, okDown: false, okLong: false, okTimer: 0, warned: false };
const MV_MAX = 4;
ICONS.grid = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="8" height="7" rx="1.5"/><rect x="13" y="4" width="8" height="7" rx="1.5"/><rect x="3" y="13" width="8" height="7" rx="1.5"/><rect x="13" y="13" width="8" height="7" rx="1.5"/></svg>';
ICONS.swap = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 8h13l-3.5-3.5M20 16H7l3.5 3.5"/></svg>';
ICONS.close = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 6l12 12M18 6L6 18"/></svg>';

function mvActive() { return MV.on; }

function mvOpen(chs, pickMore) {
  chs = (chs || []).filter(Boolean).filter((c, i, a) => a.findIndex(x => x.u === c.u) === i).slice(0, MV_MAX);
  if (!chs.length) return;
  if (LV.zap) lvZapClose();
  lvStop();
  LV.full = false;
  MV.on = true; MV.tiles = []; MV.f = 0;
  mode = 'multi';
  $('#osd').classList.remove('show');
  $('#player').classList.add('mv');
  lvSetPlayer('fs');
  body.classList.add('mv-on');
  chs.forEach(ch => MV.tiles.push(mvTile(ch)));
  mvLayout();
  mvAudio();
  if (!MV.warned && !readJSON('mvWarned', false)) {
    MV.warned = true; writeJSON('mvWarned', true);
    toast('Multiview plays each channel separately — some IPTV plans only allow 1–2 at once');
  }
  if (pickMore && MV.tiles.length < MV_MAX) setTimeout(() => { if (MV.on) mvPick(MV.tiles.length); }, 350);
}

/* add a channel (from the guide, search or the player menu) */
function mvAdd(ch) {
  if (!ch) return;
  if (!MV.on) {
    const cur = LV.playing && LV.playing.u !== ch.u ? LV.playing : (LV.lastFull && LV.lastFull.u !== ch.u ? LV.lastFull : null);
    if (tab !== 'live') switchTab('live');
    mvOpen(cur ? [cur, ch] : [ch], !cur);
    return;
  }
  if (MV.tiles.some(t => t.ch.u === ch.u)) { MV.f = MV.tiles.findIndex(t => t.ch.u === ch.u); mvAudio(); return; }
  if (MV.tiles.length >= MV_MAX) { toast('Multiview shows up to 4 channels — remove one first'); return; }
  MV.tiles.push(mvTile(ch));
  MV.f = MV.tiles.length - 1;
  mvLayout(); mvAudio();
}

function mvClose(keep) {
  const keepCh = keep && MV.tiles[MV.f] ? MV.tiles[MV.f].ch : null;
  if (LV.zap) lvZapClose();
  MV.tiles.forEach(mvStopTile);
  MV.tiles = []; MV.on = false;
  $('#mvgrid').innerHTML = '';
  $('#player').classList.remove('mv');
  body.classList.remove('mv-on');
  try { B.liveStopStreams(); } catch (e) {}
  if (keepCh) {
    // carry on watching the highlighted channel full screen
    const i = LV.list.findIndex(c => c.u === keepCh.u);
    if (i >= 0) LV.row = i;
    LV.full = false; mode = 'live';
    lvPlay(keepCh, true);
  } else {
    mode = 'live';
    lvSetPlayer('');
    lvDraw();
  }
}

/* ---------------------------------------------------------------- tiles */

function mvTile(ch) {
  const el = document.createElement('div');
  el.className = 'mvt loading';
  el.innerHTML = `<video playsinline muted></video><div class="mvl"></div><div class="mvs"><i></i></div><div class="mve"></div><div class="mvsnd">${MV_SND}</div>`;
  $('#mvgrid').appendChild(el);
  const t = { ch, el, v: el.querySelector('video'), hls: null, mts: null, kind: '' };
  t.v.addEventListener('playing', () => { el.classList.remove('loading'); el.querySelector('.mve').textContent = ''; });
  t.v.addEventListener('waiting', () => el.classList.add('loading'));
  t.v.addEventListener('error', () => { if (t.hls || t.mts || t.v.getAttribute('src')) mvFail(t, 'decode error'); });
  mvLabel(t);
  mvStartTile(t, lvKind(ch.u));
  return t;
}
const MV_SND = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/></svg>';

function mvLabel(t) {
  const ch = t.ch;
  lvPrograms([ch.k]);
  const p = lvProgAt(ch, lvNow());
  t.el.querySelector('.mvl').innerHTML =
    (ch.l ? `<img src="${esc(logoUrl(ch.l))}" onerror="this.style.display='none'">` : '') +
    `<span><b>${esc(ch.n)}</b>${p && p[2] ? `<i>${esc(p[2])}</i>` : ''}</span>`;
}

function mvStartTile(t, kind, url) {
  mvStopStream(t);
  t.kind = kind;
  url = url || t.ch.u;
  const ch = t.ch, v = t.v;
  t.el.classList.add('loading');
  const fail = msg => mvFail(t, msg, kind);
  if (kind === 'hls' && window.Hls && Hls.isSupported()) {
    t.hls = new Hls({
      xhrSetup: (xhr, u) => { xhr.open('GET', proxify(u, ch.ua), true); },
      capLevelToPlayerSize: true, maxBufferLength: 20, maxMaxBufferLength: 40, backBufferLength: 10,
      liveSyncDurationCount: 3, enableWorker: true, fragLoadingMaxRetry: 4, manifestLoadingMaxRetry: 3, levelLoadingMaxRetry: 3
    });
    let rec = 0;
    t.hls.on(Hls.Events.ERROR, (ev, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && rec++ < 2) { t.hls.recoverMediaError(); return; }
      if (d.type === Hls.ErrorTypes.NETWORK_ERROR && rec++ < 2) { setTimeout(() => t.hls && t.hls.startLoad(), 1500); return; }
      fail(d.details || 'stream error');
    });
    t.hls.loadSource(url);
    t.hls.attachMedia(v);
  } else if (kind === 'ts' && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
    // 'm' = a shared live stream: it must not cut off the other tiles' connections
    t.mts = mpegts.createPlayer({ type: 'mpegts', isLive: true, url: proxify(url, ch.ua, 'm') },
      { enableWorker: false, lazyLoad: false, enableStashBuffer: true, stashInitialSize: 512 * 1024,
        liveBufferLatencyChasing: false, autoCleanupSourceBuffer: true, autoCleanupMaxBackwardDuration: 30, autoCleanupMinBackwardDuration: 15 });
    t.mts.on(mpegts.Events.ERROR, (type, detail) => fail(detail || type));
    t.mts.attachMediaElement(v);
    t.mts.load();
  } else {
    v.src = proxify(url, ch.ua, 'm');
  }
  const pr = v.play();
  if (pr && pr.catch) pr.catch(() => {});
}

function mvFail(t, msg, kind) {
  if (!MV.tiles.includes(t) || (kind && kind !== t.kind)) return;
  if (t.kind === 'ts') {
    const alt = /\.ts(\?|$)/i.test(t.ch.u) ? t.ch.u.replace(/\.ts(\?|$)/i, '.m3u8$1') : t.ch.u + '.m3u8';
    return mvStartTile(t, 'hls', alt);
  }
  if (t.kind === 'hls' && lvKind(t.ch.u) === 'hls') return mvStartTile(t, 'file');
  mvStopStream(t);
  t.el.classList.remove('loading');
  const many = MV.tiles.length > 1;
  t.el.querySelector('.mve').innerHTML = `<b>Couldn't play this channel</b>${many ? 'Your IPTV plan may limit how many channels can play at once' : esc(String(msg).slice(0, 60))}`;
}

function mvStopStream(t) {
  if (t.hls) { try { t.hls.destroy(); } catch (e) {} t.hls = null; }
  if (t.mts) { try { t.mts.pause(); t.mts.unload(); t.mts.detachMediaElement(); t.mts.destroy(); } catch (e) {} t.mts = null; }
  try { t.v.pause(); t.v.removeAttribute('src'); t.v.load(); } catch (e) {}
}
function mvStopTile(t) { mvStopStream(t); t.el.remove(); }

function mvLayout() {
  const g = $('#mvgrid'), n = MV.tiles.length;
  g.className = 'n' + n;
  let add = g.querySelector('.mvadd');
  if (n < MV_MAX) {
    if (!add) {
      add = document.createElement('div');
      add.className = 'mvadd';
      add.innerHTML = `<b>＋</b><span>Add a channel</span>`;
    }
    g.appendChild(add);           // always last
  } else if (add) add.remove();
  MV.tiles.forEach((t, i) => { t.el.dataset.i = i; g.insertBefore(t.el, add && add.parentNode ? add : null); });
  if (MV.f > n) MV.f = n - 1;
  mvMark();
}
function mvSlots() { return MV.tiles.length + (MV.tiles.length < MV_MAX ? 1 : 0); }
function mvSlotEl(i) { return i < MV.tiles.length ? MV.tiles[i].el : $('#mvgrid .mvadd'); }

function mvMark() {
  MV.tiles.forEach((t, i) => t.el.classList.toggle('f', i === MV.f));
  const add = $('#mvgrid .mvadd');
  if (add) add.classList.toggle('f', MV.f === MV.tiles.length);
}
/* the highlighted channel has the sound */
function mvAudio() {
  const a = Math.min(MV.f, MV.tiles.length - 1);
  MV.tiles.forEach((t, i) => { t.v.muted = i !== a; t.el.classList.toggle('snd', i === a); });
  mvMark();
}

/* move to the nearest tile in that direction */
function mvMove(k) {
  const cur = mvSlotEl(MV.f);
  if (!cur) return;
  const a = cur.getBoundingClientRect(), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
  let best = -1, bestD = Infinity;
  for (let i = 0; i < mvSlots(); i++) {
    if (i === MV.f) continue;
    const b = mvSlotEl(i).getBoundingClientRect(), bx = b.left + b.width / 2, by = b.top + b.height / 2;
    const dx = bx - ax, dy = by - ay;
    const ok = k === 'l' ? dx < -4 : k === 'r' ? dx > 4 : k === 'u' ? dy < -4 : dy > 4;
    if (!ok) continue;
    const d = (k === 'l' || k === 'r') ? Math.abs(dx) + Math.abs(dy) * 2 : Math.abs(dy) + Math.abs(dx) * 2;
    if (d < bestD) { bestD = d; best = i; }
  }
  if (best < 0) return;
  MV.f = best;
  if (best < MV.tiles.length) mvAudio(); else mvMark();
}

function mvFull(i) {
  const t = MV.tiles[i];
  if (!t) return;
  MV.f = i;
  mvClose(true);
}

function mvRemove(i) {
  const t = MV.tiles[i];
  if (!t) return;
  mvStopTile(t);
  MV.tiles.splice(i, 1);
  if (MV.tiles.length === 1) { MV.f = 0; return mvClose(true); }     // one left: just watch it
  if (!MV.tiles.length) return mvClose(false);
  MV.f = Math.min(MV.f, MV.tiles.length - 1);
  mvLayout(); mvAudio();
}

/* choose a channel for slot i (a new tile, or replacing one) from the channel list */
function mvPick(i) {
  if (!LV.list.length) { toast('Open a channel category first'); return; }
  const was = MV.tiles[i] ? MV.tiles[i].ch : null;
  lvZapOpen(ch => {
    if (!MV.on) return;
    if (was) {
      if (MV.tiles.some(t => t.ch.u === ch.u)) return;
      const t = MV.tiles[i];
      t.ch = ch; mvLabel(t);
      t.el.querySelector('.mve').textContent = '';
      mvStartTile(t, lvKind(ch.u));
      MV.f = i; mvAudio();
    } else mvAdd(ch);
  }, was || (MV.tiles[MV.f] || {}).ch, was ? 'Change channel' : 'Add to Multiview');
}

function mvTileMenu(i, anchor) {
  const t = MV.tiles[i];
  if (!t) return mvPick(MV.tiles.length);
  // (focus and sound move only when you pick something: a hold that turns out to be a tap stays a tap)
  showMenu([
    ['play', 'Watch full screen', () => mvFull(i)],
    ['swap', 'Change channel', () => setTimeout(() => mvPick(i), 50)],
    ...(MV.tiles.length < MV_MAX ? [['add', 'Add a channel', () => setTimeout(() => mvPick(MV.tiles.length), 50)]] : []),
    ['hide', 'Remove from Multiview', () => mvRemove(i)],
    ['close', 'Close Multiview', () => mvClose(false)]
  ], anchor || null, `Multiview · ${t.ch.n}`);
}

/* ---------------------------------------------------------------- remote & touch */

function mvKeyDown(k, e) {
  if (LV.zap) return lvZapKey(k, e);
  if (!k) return;
  if (k === 'ok') {
    if (e.repeat || MV.okDown) return;
    MV.okDown = true; MV.okLong = false;
    MV.okTimer = setTimeout(() => { MV.okLong = true; MV.okDown = false; if (MV.f < MV.tiles.length) mvTileMenu(MV.f, mvSlotEl(MV.f)); }, 650);
    return;
  }
  if (k === 'back') return mvBack();
  if ('lrud'.includes(k)) mvMove(k);
}
function mvKeyUp(k) {
  if (k !== 'ok' || !MV.okDown) return;
  MV.okDown = false;
  clearTimeout(MV.okTimer);
  if (MV.okLong) return;
  if (MV.f >= MV.tiles.length) mvPick(MV.tiles.length);
  else mvFull(MV.f);
}
function mvBack() {
  if (LV.zap) return lvZapClose();
  mvClose(true);
}
function mvTap(t) {
  if (LV.zap) return lvZapTap(t);
  if (t.closest('.mvadd')) return mvPick(MV.tiles.length);
  const el = t.closest('.mvt');
  if (!el) return;
  const i = +el.dataset.i;
  if (i === MV.f) return mvFull(i);       // tap the one with sound again = full screen
  MV.f = i; mvAudio();
}
function mvHold(t) {
  const el = t && t.closest ? t.closest('.mvt') : null;
  if (!el) return false;
  mvTileMenu(+el.dataset.i, el);
  return true;
}

// pausing the app (Home, screen off) stops every tile
(function () {
  const prev = window.livePause;
  window.livePause = () => { if (MV.on) { MV.tiles.forEach(mvStopStream); mvClose(false); } if (prev) prev(); };
})();
// keep the "now playing" labels fresh
setInterval(() => { if (MV.on) MV.tiles.forEach(mvLabel); }, 60000);
