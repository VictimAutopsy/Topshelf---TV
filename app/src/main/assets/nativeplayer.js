/* Live TV through the TV's own video player.
   The page's stream player (mpegts.js / hls.js) unpacks the stream on the same thread that draws
   the guide — a third of the TV's processor while a channel plays. On the TV, channels now play in
   Android's video player instead (NativePlayer.java), which works on its own threads and hands the
   picture straight to the graphics chip:
   - the small preview is drawn on top of the page, in the preview's rounded box;
   - full screen is drawn under the page, which turns see-through there, so the channel banner,
     channel list, menus and stream info appear over the picture exactly as before.
   If a channel won't play that way, it plays in the page's player as before (and that channel
   stays there for the rest of the session). */

const NV = {
  ok: false, id: 0, active: false, ready: false, kind: '', url: '', ch: null,
  bad: new Set(), fails: 0, wins: 0, lastErr: '', sig: '', w: 0, h: 0, cues: '', timer: 0, place: 0
};
try { NV.ok = IS_TV && !PORTRAIT && !!B.nvOk && B.nvOk() && S.liveEngine !== 'web'; } catch (e) { NV.ok = false; }

function nvUsable(ch, kind) {
  return NV.ok && S.liveEngine !== 'web' && !NV.bad.has(ch.u) && !body.classList.contains('pip') && NV.fails < 4;
}

/* ---- start / stop (wrapping the page's player) ---- */
(function () {
  const start0 = lvStart, stop0 = lvStop;
  lvStart = function (ch, kind, url) {
    if (!nvUsable(ch, kind)) return start0(ch, kind, url);
    const sameCh = LV.playing && LV.playing.u === ch.u;
    const wasTuning = $('#player').classList.contains('tuning');
    NV.restart = NV.active;                 // switching channel: the TV's player swaps streams itself
    lvStop();
    NV.restart = false;
    LV.playing = ch;
    LV.tried = kind;
    url = url || ch.u;
    const p = $('#player');
    p.classList.add('loading', 'nv');
    if (!sameCh || wasTuning) lvTuneCard(ch);
    $('#perr').textContent = '';
    NV.id++; NV.active = true; NV.ready = false; NV.kind = kind; NV.url = url; NV.ch = ch; NV.w = NV.h = 0;
    nvCues('');
    try { B.nvPlay(NV.id, url, ch.ua || '', kind === 'hls' ? 'hls' : 'prog'); }
    catch (e) { NV.active = false; p.classList.remove('nv'); return start0(ch, kind, url); }
    NV.sig = '';
    nvPlace();
    clearTimeout(NV.timer);
    const id = NV.id;
    NV.timer = setTimeout(() => { if (NV.id === id && NV.active && !NV.ready) nvFail('no picture after 12 s'); }, 12000);
  };
  lvStop = function () {
    nvStop();
    return stop0();
  };
})();

function nvStop() {
  clearTimeout(NV.timer);
  if (!NV.active) return;
  NV.active = false; NV.ready = false; NV.ch = null;
  nvCues('');
  if (NV.restart) return;
  try { B.nvStop(); } catch (e) {}
  $('#player').classList.remove('nv', 'nvready');
  body.classList.remove('nvfs');
  clearTimeout(NV.place);
  nvHole(null); nvStill(false);
  NV.sig = '';
}

/* the native player couldn't play it: the page's player takes over for this channel */
function nvFail(msg) {
  const ch = NV.ch, kind = NV.kind, url = NV.url;
  NV.lastErr = String(msg || '').slice(0, 120);
  NV.fails++;
  if (ch) NV.bad.add(ch.u);
  nvStop();
  if (ch && LV.playing && LV.playing.u === ch.u) {
    LV.playing = null;
    lvStart(ch, kind, url);
  }
}

window.tsNative = (id, ev, data) => {
  if (id === -1 && ev === 'broken') {                    // the video library itself failed: page player from now on
    NV.ok = false; NV.lastErr = 'broken: ' + String(data).slice(0, 160);
    if (NV.active) nvFail(data);
    return;
  }
  if (id !== NV.id || !NV.active) return;
  const p = $('#player');
  if (ev === 'ready') {
    clearTimeout(NV.timer);
    NV.ready = true; NV.fails = 0; NV.wins++;
    p.classList.remove('loading', 'tuning');
    p.classList.add('nvready');
    $('#perr').textContent = '';
    NV.sig = ''; nvPlace();
  } else if (ev === 'playing') {
    p.classList.remove('loading');
  } else if (ev === 'buffering') {
    if (NV.ready) p.classList.add('loading');
  } else if (ev === 'size') {
    const m = String(data).split('x'); NV.w = +m[0] || 0; NV.h = +m[1] || 0;
  } else if (ev === 'tracks') {
    nvApplySubs();
  } else if (ev === 'cues') {
    nvCues(data);
  } else if (ev === 'stall') {
    // the TV's player is trying another way of decoding; give it time to show a picture again
    NV.stalls = (NV.stalls || 0) + 1; NV.lastStall = String(data).slice(0, 400);
    NV.ready = false; NV.sig = ''; nvPlace();
    p.classList.add('loading');
    clearTimeout(NV.timer);
    const sid = NV.id;
    NV.timer = setTimeout(() => { if (NV.id === sid && NV.active && !NV.ready) nvFail('no picture after switching decoder'); }, 12000);
  } else if (ev === 'strategy') {
    NV.strategy = data;
  } else if (ev === 'error') {
    nvFail(data);
  }
};

/* ---- where the picture goes ---- */
function nvPvRect() {
  // the preview is the full-size player scaled down by a transform (live.css): work out its box from the
  // same numbers rather than measuring mid-animation
  const p = $('#player'), rem = remPx();
  const sw = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--sw')) || innerWidth / rem;
  const k = 25 / sw;
  const st = $('#stage').getBoundingClientRect();
  return { x: st.left + p.offsetLeft + sw * rem - 30.6 * rem, y: st.top + p.offsetTop + 10.8 * rem, w: p.offsetWidth * k, h: p.offsetHeight * k, r: 1.6 * rem };
}
function nvCovered() {
  // things the page draws above the preview
  return body.classList.contains('shelf') || body.classList.contains('saver') || mode === 'launch' ||
         ['settings-open', 'search-open', 'link-open', 'sheet-open', 'spick-open', 'icpick-open'].some(c => body.classList.contains(c)) ||
         mode === 'settings' || mode === 'sheet' || mode === 'search' || mode === 'icpick' || mode === 'spick' ||
         $('#launch').classList.contains('show') || $('#lkcode').classList.contains('show') || tab !== 'live' && !LV.full;
}
/* The picture sits under the page; the page opens a rounded "window" over the preview by clipping the
   layers that would cover it (only those that actually overlap it). */
const NV_KEEP = new Set(['player', 'toast', 'lkcode', 'launch', 'perfhud']);
function nvHole(r) {
  for (const el of NV.holed || []) el.style.clipPath = '';
  NV.holed = [];
  body.classList.toggle('nvhole', !!r);
  if (!r) return;
  const st = $('#stage').getBoundingClientRect();
  const m = remPx() * 8;                    // shadows reach past their boxes
  const x0 = r.x - m, y0 = r.y - m, x1 = r.x + r.w + m, y1 = r.y + r.h + m;
  for (const el of $('#stage').children) {
    if (NV_KEEP.has(el.id) || el.tagName === 'SCRIPT') continue;
    const b = el.getBoundingClientRect();
    if (!b.width || !b.height || b.right <= x0 || b.left >= x1 || b.bottom <= y0 || b.top >= y1) continue;
    const x = r.x - b.left, y = r.y - b.top, w = r.w, h = r.h, q = Math.min(r.r, w / 2, h / 2), f = n => n.toFixed(1);
    const o = remPx() * 12, W = f(b.width + o), H = f(b.height + o);
    el.style.clipPath = `path(evenodd, "M${f(-o)} ${f(-o)}H${W}V${H}H${f(-o)}Z M${f(x + q)} ${f(y)}H${f(x + w - q)}A${f(q)} ${f(q)} 0 0 1 ${f(x + w)} ${f(y + q)}V${f(y + h - q)}A${f(q)} ${f(q)} 0 0 1 ${f(x + w - q)} ${f(y + h)}H${f(x + q)}A${f(q)} ${f(q)} 0 0 1 ${f(x)} ${f(y + h - q)}V${f(y + q)}A${f(q)} ${f(q)} 0 0 1 ${f(x + q)} ${f(y)}Z")`;
    NV.holed.push(el);
  }
}
/* while the player zooms between preview and full screen, the page shows a still of the picture */
function nvStill(on) {
  const p = $('#player');
  if (!on) { p.style.backgroundImage = ''; p.classList.remove('nvstill'); return; }
  let img = ''; try { img = B.nvSnapshot(); } catch (e) {}
  if (img) { p.style.backgroundImage = `url("${img}")`; p.classList.add('nvstill'); }
}
function nvPlace(anim) {
  if (!NV.active) return;
  const p = $('#player'), d = devicePixelRatio || 1;
  const fs = p.classList.contains('fs'), pv = p.classList.contains('pv');
  const want = fs ? 'fs' : pv && !nvCovered() ? 'pv' : 'hide';
  const sig = want + '|' + NV.ready + '|' + innerWidth + 'x' + innerHeight;
  if (sig === NV.sig && !anim) return;
  const was = NV.sig.split('|')[0], wasReady = NV.sig.split('|')[1] === 'true';
  NV.sig = sig;
  clearTimeout(NV.place);
  const apply = () => {
    nvStill(false);
    if (want === 'fs') {
      nvHole(null);
      body.classList.add('nvfs');
      B.nvLayout('fs', 0, 0, innerWidth * d, innerHeight * d, 0, 0);
    } else if (want === 'pv') {
      body.classList.remove('nvfs');
      const r = nvPvRect();
      B.nvLayout('pv', r.x * d, r.y * d, r.w * d, r.h * d, r.r * d, 0);
      nvHole(NV.ready ? r : null);
    } else {
      body.classList.remove('nvfs');
      nvHole(null);
      B.nvLayout('hide', 0, 0, 0, 0, 0, 0);
    }
  };
  try {
    if (NV.ready && wasReady && ((was === 'pv' && want === 'fs') || (was === 'fs' && want === 'pv'))) {
      // zooming: a still of the picture rides along in the page's player, then the live picture takes over
      nvStill(true);
      nvHole(null);
      body.classList.remove('nvfs');
      NV.place = setTimeout(apply, 470);
    } else apply();
  } catch (e) {}
}
(function () {
  const set0 = lvSetPlayer;
  lvSetPlayer = function (cls) { const r = set0(cls); nvPlace(); return r; };
  // cheap watch for anything else that covers or uncovers the preview (no layout reads)
  setInterval(() => { if (NV.active) nvPlace(); }, 300);
  addEventListener('resize', () => { NV.sig = ''; nvPlace(); });
})();

/* ---- subtitles drawn by the page (same look as before) ---- */
function nvCues(text) {
  NV.cues = text || '';
  const el = $('#nvcues');
  if (!el) return;
  el.innerHTML = NV.cues ? '<span>' + esc(NV.cues) + '</span>' : '';
  el.classList.toggle('on', !!NV.cues);
}

/* ---- audio, subtitles, quality and stream info for native playback ---- */
function nvTracks() { try { return JSON.parse(B.nvTracks() || '{}'); } catch (e) { return {}; } }
function nvApplySubs() {
  if (!PO.subLang) return;
  const tr = nvTracks(), list = tr.text || [];
  if (!list.length || (tr.textOn && list.some(t => t.on))) return;
  const t = list.find(t => (t.lang || t.name || 'on') === PO.subLang) || (PO.subLang === 'on' ? list[0] : null);
  if (t) B.nvSelect('text', t.g, t.t);
}
(function () {
  const aud0 = poAudioTracks, setAud0 = poSetAudio, txt0 = poTextTracks, setSub0 = poSetSub, lev0 = poLevels, qm0 = poQualityMenu, items0 = poMenuItems, stats0 = poStatsDraw;
  poAudioTracks = function () {
    if (!NV.active) return aud0();
    const a = nvTracks().audio || [];
    if (a.length < 2) return [];
    return a.map((t, i) => ({ i, g: t.g, t: t.t, name: t.name && !/^(audio|stereo|default|\d+)$/i.test(t.name) ? t.name : langName(t.lang, `Track ${i + 1}`) + (t.ch > 2 ? ' · surround' : ''), lang: t.lang || '', on: t.on }));
  };
  poSetAudio = function (i) {
    if (!NV.active) return setAud0(i);
    const t = poAudioTracks()[i];
    if (!t) return;
    B.nvSelect('audio', t.g, t.t);
    PO.audLang = t.lang; writeJSON('liveAudLang', t.lang);
    toast(`Audio: ${t.name}`);
  };
  poTextTracks = function () {
    if (!NV.active) return txt0();
    const tr = nvTracks();
    return (tr.text || []).map(t => ({ kind: t.cc ? 'captions' : 'subtitles', language: t.lang, label: t.name, mode: t.on && tr.textOn ? 'showing' : 'disabled', g: t.g, t: t.t }));
  };
  poSetSub = function (i) {
    if (!NV.active) return setSub0(i);
    const tracks = poTextTracks(), t = tracks[i];
    if (t) B.nvSelect('text', t.g, t.t); else B.nvSelect('text', -1, -1);
    PO.subLang = t ? (t.language || t.label || 'on') : '';
    writeJSON('liveSubLang', PO.subLang);
    toast(t ? `Subtitles: ${poSubName(t, i)}` : 'Subtitles off');
  };
  poLevels = function () {
    if (!NV.active) return lev0();
    const v = nvTracks().video || [];
    if (v.length < 2) return [];
    return v.map((t, i) => ({ i, g: t.g, t: t.t, h: t.h || 0, br: t.br || 0, on: t.on })).sort((a, b) => b.h - a.h || b.br - a.br);
  };
  poQualityMenu = function () {
    if (!NV.active) return qm0();
    const ls = poLevels(), auto = nvTracks().auto !== false;
    if (!ls.length) return showMenu([['info', 'This channel comes in one quality', () => {}]], null, 'Quality');
    const cur = ls.find(l => l.on);
    showMenu([[auto ? 'check' : 'hd', `Auto${auto && cur ? ' · now ' + (cur.h || '?') + 'p' : ''}`, () => { B.nvSelect('video', -1, -1); toast('Quality: Auto'); }]]
      .concat(ls.map(l => [!auto && l.on ? 'check' : 'hd', poLevelName(l), () => { B.nvSelect('video', l.g, l.t); toast('Quality: ' + poLevelName(l)); }])), null, 'Quality');
  };
  poMenuItems = function () {
    if (!NV.active) return items0();
    const out = [];
    const aud = poAudioTracks();
    if (aud.length) out.push(['audio', `Audio · ${(aud.find(t => t.on) || aud[0]).name}`, () => setTimeout(poAudioMenu, 50)]);
    const subs = poTextTracks(), on = subs.find(t => t.mode === 'showing');
    out.push(['cc', `Subtitles · ${on ? poSubName(on, subs.indexOf(on)) : subs.length ? 'Off' : 'none'}`, () => setTimeout(poSubMenu, 50)]);
    const ls = poLevels();
    if (ls.length) { const auto = nvTracks().auto !== false, cur = ls.find(l => l.on); out.push(['hd', `Quality · ${auto ? 'Auto' : poLevelName(cur || {})}`, () => setTimeout(poQualityMenu, 50)]); }
    out.push(['stats', PO.stats ? 'Hide stream info' : 'Stream info', () => poStatsToggle()]);
    return out;
  };
  poStatsDraw = function () {
    if (!NV.active) return stats0();
    const el = $('#pstats');
    if (!LV.playing || !PO.stats) { el.innerHTML = ''; return; }
    let s = {}; try { s = JSON.parse(B.nvStats() || '{}'); } catch (e) {}
    const rows = [];
    const add = (k, val) => { if (val !== '' && val != null) rows.push(`<div><span>${esc(k)}</span><b>${esc(String(val))}</b></div>`); };
    add('Channel', LV.playing.n);
    add('Stream', (NV.kind === 'hls' ? 'HLS' : NV.kind === 'file' ? 'Direct' : 'MPEG-TS') + (LV.tried === 'hls' && lvKind(LV.playing.u) !== 'hls' ? ' (fallback)' : ''));
    add('Player', "TV's video player" + (s.decoder ? ` · ${s.decoder}` : '') + (s.strategy ? ` · mode ${s.strategy}` : ''));
    add('Resolution', s.w ? `${s.w}×${s.h}` : 'waiting…');
    add('Codecs', [s.vcodec, s.acodec].filter(Boolean).join(' / '));
    if (s.fps) add('Frame rate', `${Math.round(s.fps)} fps`);
    if (s.br) add('Bitrate', `${(s.br / 1e6).toFixed(2)} Mbps`);
    if (s.bw) add('Connection', `${(s.bw / 1e6).toFixed(1)} Mbps`);
    if (s.buf != null) add('Buffer', `${(+s.buf).toFixed(1)} s`);
    if (s.total) add('Dropped frames', `${s.dropped || 0} of ${s.total}`);
    const aud = poAudioTracks().find(t => t.on);
    if (aud) add('Audio', aud.name);
    const sub = poTextTracks().find(t => t.mode === 'showing');
    if (sub) add('Subtitles', poSubName(sub, 0));
    el.innerHTML = `<h4>Stream info</h4>${rows.join('')}`;
  };
})();

/* for the diagnostic report */
function nvDiag() {
  let info = {}; try { info = JSON.parse(B.nvInfo ? B.nvInfo() : '{}'); } catch (e) {}
  return { on: NV.ok, engine: S.liveEngine || 'tv', lib: info.lib, off: info.off, err: (info.err || '').slice(0, 300), plays: NV.wins, failsInARow: NV.fails, lastErr: NV.lastErr, stalls: NV.stalls || 0, lastStall: NV.lastStall || '', strategyLearned: NV.strategy || '', pageChannels: NV.bad.size };
}
