/* Diagnostic report: the TV tests itself in detail — frame times, how long each button press
   takes, what blocks the processor, and (with the browser engine's own profiler) which parts of
   TopShelf's code use the time — then your phone fetches the report over the phone–TV link and
   shares it as a file. */

const DIAG = { running: false, step: '', report: null, startedBy: '' };

function diagProfiler() {
  try { return typeof Profiler === 'function' ? new Profiler({ sampleInterval: 10, maxBufferSize: 30000 }) : null; } catch (e) { return null; }
}
/* sum a profiler trace into the functions that used the time */
function diagProfile(tr) {
  if (!tr || !tr.samples || !tr.samples.length) return null;
  const res = tr.resources || [], frames = tr.frames || [], stacks = tr.stacks || [];
  const fname = f => { const fr = frames[f]; if (!fr) return '?'; const r = fr.resourceId != null ? String(res[fr.resourceId] || '').split('/').pop() : ''; return `${fr.name || '(anonymous)'} ${r}${fr.line != null ? ':' + fr.line : ''}`; };
  const self = {}, incl = {};
  let js = 0, total = 0;
  for (let i = 0; i < tr.samples.length; i++) {
    const s = tr.samples[i], next = tr.samples[i + 1];
    const dt = next ? Math.min(50, next.timestamp - s.timestamp) : 10;
    total += dt;
    if (s.stackId == null) continue;
    js += dt;
    let st = stacks[s.stackId];
    const top = st ? fname(st.frameId) : '?';
    self[top] = (self[top] || 0) + dt;
    const seen = new Set();
    while (st) { const k = fname(st.frameId); if (!seen.has(k)) { incl[k] = (incl[k] || 0) + dt; seen.add(k); } st = st.parentId != null ? stacks[st.parentId] : null; }
  }
  const top = (o, n) => Object.entries(o).sort((a, b) => b[1] - a[1]).slice(0, n).map(([k, v]) => [k, Math.round(v)]);
  return { totalMs: Math.round(total), jsMs: Math.round(js), self: top(self, 18), inclusive: top(incl, 22).filter(([k]) => !/^\(anonymous\) :/.test(k)) };
}

async function diagSection(out, label, prep, keys, gap = 380, ms = 3200) {
  DIAG.step = label;
  if (!DIAG.local) $('#perfhud').textContent = 'Diagnostic · ' + label;
  try { if (prep) await prep(); } catch (e) { out.push({ label, error: String(e && e.message || e) }); return; }
  await perfWait(800);
  PERF.keyMs = []; PERF.longMs = 0; DIAG.long = []; DIAG.lat = []; DIAG.loaf = []; DIAG.bakeFlips = 0; const bake0 = BAKE.on;
  const prof = diagProfiler();
  const r = await new Promise(res => {
    const ts = []; let t0 = 0;
    const f = t => { if (!t0) t0 = t; ts.push(t); if (t - t0 < ms) requestAnimationFrame(f); else res(ts); };
    requestAnimationFrame(f);
    if (keys) keys.forEach((k, i) => setTimeout(() => {
      const a = performance.now();
      perfPress(k);
      requestAnimationFrame(() => requestAnimationFrame(t => DIAG.lat.push(t - a)));
    }, i * gap));
  });
  let trace = null;
  if (prof) { try { trace = await prof.stop(); } catch (e) {} }
  const d = r.slice(1).map((x, i) => x - r[i]).sort((a, b) => a - b);
  const q = p => Math.round(d[Math.min(d.length - 1, Math.floor(d.length * p))] || 0);
  const avg = a => a.length ? Math.round(a.reduce((x, y) => x + y, 0) / a.length) : 0;
  const anims = document.getAnimations().filter(a => a.playState === 'running').map(a => {
    const t = a.effect && a.effect.target; return (a.animationName || a.transitionProperty || 'anim') + '@' + (t ? (t.id || (t.className && t.className.baseVal == null ? String(t.className).split(' ')[0] : '') || t.tagName) : '?');
  });
  out.push({
    label, fps: Math.round(d.length * 1000 / ((r[r.length - 1] - r[0]) || 1)), p50: q(.5), p95: q(.95), worst: Math.round(d[d.length - 1] || 0),
    over50: d.filter(x => x > 50).length, over100: d.filter(x => x > 100).length,
    pressMs: avg(PERF.keyMs), pressMax: Math.round(Math.max(0, ...PERF.keyMs)), latency: avg(DIAG.lat), latencyMax: Math.round(Math.max(0, ...DIAG.lat)),
    blockedMs: Math.round(PERF.longMs), longTasks: (DIAG.long || []).length, longest: Math.round(Math.max(0, ...(DIAG.long || []))),
    blockedBy: diagBlockedBy(DIAG.long || []), frames: diagFrames(DIAG.loaf || []),
    glass: (bake0 ? 'baked' : 'live') + (BAKE.on !== bake0 ? '→' + (BAKE.on ? 'baked' : 'live') : '') + (DIAG.bakeFlips ? ' (' + DIAG.bakeFlips + ' switches)' : ''),
    running: [...new Set(anims)].slice(0, 15), profile: diagProfile(trace)
  });
}
try { new PerformanceObserver(l => { for (const e of l.getEntries()) (DIAG.long || (DIAG.long = [])).push({ d: e.duration, n: e.name, c: e.attribution && e.attribution[0] ? (e.attribution[0].containerSrc || e.attribution[0].containerType || '') : '' }); }).observe({ type: 'longtask' }); } catch (e) {}
/* long animation frames: where each slow frame's time went — TopShelf's code (by file), other code
   (the YouTube player and the stream player run on the same thread), the page's style and layout,
   and time no script accounts for (images, the browser itself, other frames on the page) */
try { new PerformanceObserver(l => { if (DIAG.loaf) for (const e of l.getEntries()) DIAG.loaf.push(e); }).observe({ type: 'long-animation-frame' }); } catch (e) {}
function diagBlockedBy(list) {
  const o = {};
  for (const e of list) { const k = (e.n || '?') + (e.c && !/^(window|)$/.test(e.c) ? ' ' + String(e.c).replace(/^https?:\/\//, '').slice(0, 40) : ''); o[k] = Math.round((o[k] || 0) + e.d); }
  return o;
}
function diagFrames(list) {
  if (!list.length) return null;
  let total = 0, script = 0, forced = 0, render = 0, layout = 0;
  const by = {};
  for (const e of list) {
    const end = e.startTime + e.duration;
    total += e.duration;
    if (e.styleAndLayoutStart) layout += end - e.styleAndLayoutStart;
    if (e.renderStart) render += (e.styleAndLayoutStart || end) - e.renderStart;
    for (const s of e.scripts || []) {
      script += s.duration; forced += s.forcedStyleAndLayoutDuration || 0;
      const src = String(s.sourceURL || '').split('?')[0];
      const f = src ? (src.startsWith(location.origin) || !/^https?:/.test(src) ? src.split('/').pop() : src.replace(/^https?:\/\//, '').split('/')[0]) : (s.invokerType || '?');
      const k = f + (s.sourceFunctionName ? ' ' + s.sourceFunctionName : '');
      by[k] = (by[k] || 0) + s.duration;
    }
  }
  const r = Math.round;
  return { slowFrames: list.length, ms: r(total), scriptMs: r(script), forcedLayoutMs: r(forced), rafMs: r(render), styleLayoutPaintMs: r(layout),
           unaccountedMs: r(Math.max(0, total - script - render - layout)),
           scripts: Object.entries(by).sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, v]) => [k, r(v)]) };
}

function diagBridgeMs(fn, n = 5) {
  const t0 = performance.now();
  try { for (let i = 0; i < n; i++) fn(); } catch (e) { return -1; }
  return +((performance.now() - t0) / n).toFixed(2);
}

async function diagRun(by) {
  if (DIAG.running || PERF.testing) return;
  DIAG.running = true; DIAG.report = null; DIAG.startedBy = by || 'tv'; DIAG.local = false;
  PERF.testing = true;
  if (mode === 'sheet') sheetClose();
  if (mode === 'settings') closeSettings();
  if (mode === 'menu') closeMenu();
  if (mode === 'player' && typeof lvExitFull === 'function') lvExitFull();
  const keep = { glass: S.glass, tab, preview: S.livePreview, motion: S.motion };
  const hud = $('#perfhud'); hud.classList.add('on');
  const out = [];
  const glass = g => () => { S.glass = g; applyGlassMode(); };
  const homeNav = ['ArrowRight', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowLeft', 'ArrowLeft', 'ArrowRight', 'ArrowLeft'];
  const t0 = Date.now();
  try {
    await diagSection(out, 'Home · still', async () => { switchTab('home'); focus = 0; applyFocus(); await perfWait(1500); });
    await diagSection(out, 'Home · moving', async () => { await perfWait(1200); }, homeNav);
    await diagSection(out, 'Home · moving · shadows snap (test)', async () => { body.classList.add('ab-snap'); }, homeNav);
    body.classList.remove('ab-snap');
    await diagSection(out, 'Home · moving · live glass', async () => { PERF.testing = 'live'; bakeOff(); }, homeNav);
    PERF.testing = true;
    await diagSection(out, 'Home · moving · Solid glass', glass('solid'), homeNav);
    glass(keep.glass)();
    await diagSection(out, 'Switch to Movies', async () => { setTimeout(() => switchTab('movies'), 900); }, null, 0, 3000);
    await diagSection(out, 'Movies · moving', async () => { await perfWait(1500); }, ['ArrowDown', 'ArrowRight', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowRight', 'ArrowUp']);
    await diagSection(out, 'Movies · moving again (rows loaded)', null, ['ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowRight']);
    await diagSection(out, 'Movies · resting after a move', async () => { perfPress('ArrowRight'); }, null);
    await diagSection(out, 'Movies · resting · trailers off (test)', async () => { keep.trailers = S.trailers; S.trailers = 'off'; if (typeof vTrStop === 'function') vTrStop(); perfPress('ArrowLeft'); }, null);
    S.trailers = keep.trailers;
    await diagSection(out, 'Switch to Live TV', async () => { setTimeout(() => switchTab('live'), 900); }, null, 0, 3500);
    await diagSection(out, 'Live TV · moving, no preview', async () => { S.livePreview = false; if (typeof lvStop === 'function') { lvStop(); lvSetPlayer(''); } await perfWait(800); }, ['ArrowDown', 'ArrowDown', 'ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowUp', 'ArrowUp'], 420);
    S.livePreview = keep.preview;
    await diagSection(out, 'Live TV · preview playing, still', async () => { const ch = LV.list[LV.row]; if (ch) lvPlay(ch, false); await perfWait(4000); });
    if (typeof NV !== 'undefined' && NV.ok) {
      await diagSection(out, 'Live TV · preview, page player (comparison)', async () => { keep.engine = S.liveEngine; S.liveEngine = 'web'; const ch = LV.playing || LV.list[LV.row]; lvStop(); if (ch) lvPlay(ch, false); await perfWait(4000); });
      S.liveEngine = keep.engine;
      { const ch = LV.playing || LV.list[LV.row]; lvStop(); if (ch) lvPlay(ch, false); await perfWait(3000); }
    }
    await diagSection(out, 'Live TV · moving, preview playing', null, ['ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowUp'], 450);
    if (S.livePlayer !== 'external')
      await diagSection(out, 'Live TV · full screen, changing channel', async () => { const ch = LV.list[LV.row]; if (ch) lvPlay(ch, true); await perfWait(4000); }, ['ArrowDown', 'ArrowDown'], 1500, 4000);
    if (mode === 'player') lvExitFull();
    if (typeof lvStop === 'function') { lvStop(); lvSetPlayer(''); }
    if (typeof NH === 'undefined' || !NH.sports)   // (Sports drawn by the app is measured with the app's own steps)
      await diagSection(out, 'Sports · moving', async () => { switchTab('sports'); await perfWait(2500); }, ['ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowRight']);
  } catch (e) { out.push({ label: 'error', error: String(e && e.stack || e).slice(0, 400) }); }
  S.glass = keep.glass; S.livePreview = keep.preview; if ('trailers' in keep) S.trailers = keep.trailers; if ('engine' in keep) S.liveEngine = keep.engine; body.classList.remove('ab-snap'); save(); applyGlassMode();
  switchTab(keep.tab === 'live' ? 'home' : keep.tab);
  hud.classList.remove('on');
  PERF.testing = false;

  // what the TV and the page look like
  let disp = '', ver = '';
  try { disp = B.displayInfo(); } catch (e) {}
  try { ver = B.getVersion(); } catch (e) {}
  const imgs = [...document.images].filter(i => i.complete && i.naturalWidth);
  const bgEl = document.querySelector('.bgimg.show');
  let bgSize = '';
  if (bgEl) { const m = (bgEl.style.backgroundImage || '').match(/url\("?(.*?)"?\)$/); const im = m && BAKE.imgs.get(m[1]); bgSize = im ? im.naturalWidth + 'x' + im.naturalHeight : '?'; }
  const keys20 = (LV.list || []).slice(0, 20).map(c => c.k);
  DIAG.report = {
    app: 'TopShelf diagnostic', version: ver, at: new Date().toString(), tookSec: Math.round((Date.now() - t0) / 1000), startedBy: DIAG.startedBy,
    device: { screen: disp, page: `${innerWidth}x${innerHeight}@${devicePixelRatio}`, ua: navigator.userAgent, cores: navigator.hardwareConcurrency, memGB: navigator.deviceMemory || null,
              jsHeapMB: performance.memory ? Math.round(performance.memory.usedJSHeapSize / 1048576) : null, profiler: typeof Profiler === 'function' },
    settings: { glass: S.glass, zoom: S.motion, trailers: S.trailers || 'card', topShelf: S.topShelf || 'focus', livePreview: S.livePreview !== false, livePlayer: S.livePlayer || 'builtin', saver: S.saver, bedtime: S.bedtime || 0, tvperf: body.classList.contains('tvperf'), baked: BAKE.on, bakes: BAKE.count || 0,
              colourWorker: typeof VAMB !== 'undefined' ? (VAMB.w ? 'on' : 'off') + '/' + (VAMB.fails || 0) : '?', trailerReuse: typeof VT_REUSE !== 'undefined' ? VT_REUSE : '?', renderScale: B.getRenderScale ? B.getRenderScale() : 1 },
    page: { elements: document.getElementsByTagName('*').length, images: imgs.length, imageMegapixels: +(imgs.reduce((a, i) => a + i.naturalWidth * i.naturalHeight, 0) / 1e6).toFixed(1), photo: bgSize,
            channels: (LV.chans || []).length, listed: (LV.list || []).length, guideRowsDrawn: LV.rendered ? LV.rendered.size : 0, programmesCached: Object.keys(LV.prog || {}).length,
            sleeping: PERF.hidden ? PERF.hidden.size : 0 },
    bridgeMs: { getPref: diagBridgeMs(() => B.getPref('settings')), livePrograms20: diagBridgeMs(() => B.livePrograms(JSON.stringify(keys20), LV.base || Date.now(), (LV.base || Date.now()) + 3 * 3600000), 2), homeStatus: diagBridgeMs(() => B.homeStatus()) },
    native: typeof nvDiag === 'function' ? nvDiag() : null,
    deeper: (() => { try { return B.nhDiagExtra ? JSON.parse(B.nhDiagExtra()) : null; } catch (e) { return { error: String(e) }; } })(),
    sections: out
  };
  try { B.setPref('lastDiag', JSON.stringify(DIAG.report)); } catch (e) {}
  DIAG.running = false;
  if (DIAG.startedBy === 'tv') diagShow();
  else toast('Diagnostic finished — your phone is fetching the report');
  return DIAG.report;
}

function diagShow() {
  const r = DIAG.report;
  if (!r) return;
  /* each step: how long frames took (95% of them were at least this quick), the slowest one, and how
     long the graphics chip needed per frame (steps drawn by the app) */
  const row = s => {
    if (s.error) return `<tr class="slow"><td>${esc(s.label)}</td><td>error</td><td></td><td></td></tr>`;
    if (s.info !== undefined && s.fps === undefined) return '';
    const nat = / \(native\)$/.test(s.label);
    const label = s.label.replace(/ \(native\)$/, '');
    const ft = nat ? s.p95 : (s.fps > 0 ? Math.round(1000 / s.fps) : null);
    const tone = ft == null ? '' : ft <= 17 ? 'good' : ft <= 34 ? 'ok' : 'slow';
    return `<tr class="${tone}"><td>${esc(label)}</td><td>${ft == null ? '–' : ft + ' ms'}</td><td>${s.worst != null ? s.worst + ' ms' : '–'}</td><td>${s.gpu95 != null ? s.gpu95 + ' ms' : '–'}</td></tr>`;
  };
  sheetOpen('Diagnostic report',
    `<p class="shsm">Green is smooth (a frame every 16 ms), orange has small hitches, red is slow. The full report is ready for your phone: on the phone, <b>Settings → TV diagnostic report</b> fetches it and lets you send it.</p>
     <table class="pt"><tr><th></th><th>frame time</th><th>slowest</th><th>graphics</th></tr>${r.sections.map(row).join('')}</table>`,
    [['Close', null]]);
}

/* ---- TV side of the phone link ---- */
window.diagLinkCmd = c => {
  if (c.type === 'diag') {
    if (!DIAG.running) setTimeout(() => diagRun(c.from || 'phone'), 300);
    return JSON.stringify({ ok: true, running: true });
  }
  if (c.type === 'diagget') {
    if (DIAG.running) return JSON.stringify({ state: 'running', step: DIAG.step });
    let rep = DIAG.report;
    if (!rep) { try { rep = JSON.parse(B.getPref('lastDiag') || 'null'); } catch (e) {} }
    return JSON.stringify(rep ? { state: 'done', report: rep } : { state: 'none' });
  }
  return '';
};

/* ---- phone side ---- */
async function diagFromPhone(useLast) {
  if (!lkOn()) return toast('Link your phone and TV first (Settings → Phone & TV link)');
  if (!useLast) {
    const r = await lkSend({ type: 'diag' });
    if (!r) return;
    toast(`Testing on ${lkTvName()} — about 1½ minutes. Don't touch the TV remote.`);
    await perfWait(8000);
  }
  for (let i = 0; i < 60; i++) {
    const r = await lkSend({ type: 'diagget' }, true);
    if (r && r.state === 'done' && r.report) {
      const name = `TopShelf-diagnostic-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-')}.txt`;
      const where = B.saveReport ? B.saveReport(name, JSON.stringify(r.report, null, 1)) : '!';
      toast(where && where[0] !== '!' ? 'Report saved — pick Claude in the share menu, or attach it from Downloads/TopShelf' : "Couldn't save the report");
      return;
    }
    if (r && r.state === 'none') return toast('No report on the TV yet');
    if (r && r.step && i % 3 === 0) toast(`TV: ${r.step}…`);
    await perfWait(4000);
  }
  toast("The TV didn't finish — try again");
}
