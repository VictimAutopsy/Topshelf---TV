/* Performance: the same look with far less work for the TV's graphics chip.

   1. The photo's slow zoom moves a fraction of a pixel per frame, yet every frame it moved forced
      the glass panels (dock, tabs, top bar) to be re-blurred and re-refracted. On TVs it now moves
      in tiny sub-pixel steps four times a second (indistinguishable at this speed) and holds still while you're
      pressing buttons, so the chip's time goes to your navigation.
   2. Screens and panels that are faded out are taken out of drawing entirely (visibility:hidden),
      so the compositor stops carrying a dozen invisible full-screen layers.
   (perf.css: background animations only run while their screen is showing, and the loading bars
   animate without re-doing page layout every frame.) */

const PERF = { lastKey: 0, managed: [], hidden: new Set() };
if (IS_TV) body.classList.add('tvperf');
window.addEventListener('keydown', () => { PERF.lastKey = performance.now(); }, true);

/* ---- 1. stepped slow zoom (TV, landscape) ---- */
function kbTick() {
  if (!body.classList.contains('tvperf') || PORTRAIT) return;
  const now = performance.now();
  const hold = document.hidden || body.classList.contains('still') || body.classList.contains('deep') ||
               body.classList.contains('paused') || now - PERF.lastKey < 1500 ||
               (body.dataset.theme !== 'home' && !body.classList.contains('shelf'));   // the photo is only on Home
  document.querySelectorAll('.bgimg').forEach(el => {
    if (!el.classList.contains('kb')) {
      if (el._kb) { el._kb = null; el._tf = ''; el.style.transform = ''; }
      return;
    }
    const sig = el.style.backgroundImage + '|' + el.style.getPropertyValue('--kx') + '|' + el.style.getPropertyValue('--ky');
    if (!el._kb || el._kb.sig !== sig) el._kb = { sig, t: 0, last: now };
    const k = el._kb;
    if (!hold) k.t += Math.min(500, now - k.last);
    k.last = now;
    const dur = (parseFloat(el.style.getPropertyValue('--kbdur')) || 40) * 1000;
    const p = Math.min(1, k.t / dur);
    const kx = parseFloat(el.style.getPropertyValue('--kx')), ky = parseFloat(el.style.getPropertyValue('--ky'));
    const tf = `scale(${(1 + 0.09 * p).toFixed(5)}) translate(${((isNaN(kx) ? -1 : kx) * p).toFixed(4)}%, ${((isNaN(ky) ? -1 : ky) * p).toFixed(4)}%)`;
    if (tf !== el._tf) { el._tf = tf; el.style.transform = tf; }
  });
}
setInterval(kbTick, 250);

/* ---- the "All apps" hint: one bounce every few seconds instead of an always-running animation
   (it sits on the glass, so every frame it moved re-blurred the glass behind it) ---- */
setInterval(() => {
  const h = $('#hint');
  if (!h || document.hidden || parseFloat(getComputedStyle(h).opacity) < 0.5 || performance.now() - PERF.lastKey < 1500) return;
  h.classList.remove('nudge'); void h.offsetWidth; h.classList.add('nudge');
}, 3600);

/* ---- 2. put faded-out layers to sleep ---- */
function perfShouldShow(el) {
  // showing, or fading / sliding in? (cheapest readings first; animations only looked at when needed)
  const cs = getComputedStyle(el);
  const visible = parseFloat(cs.opacity) > 0.001 || cs.pointerEvents !== 'none';
  let anims = null;
  const moving = () => (anims || (anims = el.getAnimations())).some(a => a.playState === 'running' && /opacity|translate|transform|left|right|top|bottom/.test(a.transitionProperty || ''));
  let show = visible || moving();
  if (show && visible && el.classList.contains('glass') && !moving()) {
    // a glass panel parked off screen (Settings waits beside the screen) still costs its blur
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.height > 0 && (r.right <= 0 || r.bottom <= 0 || r.left >= innerWidth || r.top >= innerHeight)) show = false;
  }
  return show;
}
/* asleep: not drawn (visibility) and its insides skipped by style and layout work (content-visibility),
   so a change anywhere on the page no longer re-styles every hidden screen */
function perfSleep(el, on) {
  if (on) { PERF.hidden.add(el); el.style.visibility = 'hidden'; }
  else { PERF.hidden.delete(el); el.style.visibility = ''; }
}
function perfSettle(el) {
  if (perfShouldShow(el)) { if (PERF.hidden.has(el)) perfSleep(el, false); return; }
  if (!PERF.hidden.has(el)) perfSleep(el, true);
}
function perfWake(recs) {
  // panels whose own class changed, or everything when the page's state (body) changed
  const all = !recs || recs.some(r => r.target === body);
  const targets = all ? PERF.managed : [...new Set(recs.map(r => r.target))];
  // checked just after the next frame, when reading styles is free (checking right away forced the
  // whole page to be re-styled in the middle of a button press); a panel fading in starts one frame later
  const hiddenNow = targets.filter(el => PERF.hidden.has(el));
  if (hiddenNow.length) requestAnimationFrame(() => setTimeout(() => { for (const el of hiddenNow) if (PERF.hidden.has(el) && perfShouldShow(el)) perfSleep(el, false); }, 0));
  for (const el of targets) PERF.pending.add(el);
  clearTimeout(PERF.t);
  PERF.stateAt = Date.now();
  const settle = () => {
    if (performance.now() - PERF.lastKey < 1800) { PERF.t = setTimeout(settle, 900); return; }
    const list = [...PERF.pending]; PERF.pending.clear(); list.forEach(perfSettle);
  };
  PERF.t = setTimeout(settle, 1900);   // after fades finish
}
function perfManage() {
  const set = new Set();
  const add = el => { if (el && el.nodeType === 1 && el.id !== 'splash' && el.id !== 'hint' && el.id !== 'perfhud') set.add(el); };
  [...$('#stage').children].forEach(add);
  document.querySelectorAll('#themebg .th, .glass, #player, #sports, #tshelf, #ticker, #menu, #launch').forEach(add);
  PERF.managed = [...set];
  PERF.pending = new Set();
  const mo = new MutationObserver(perfWake);
  mo.observe(body, { attributes: true, attributeFilter: ['class', 'data-theme'] });
  // only the panels that show and hide themselves with their own class are watched individually
  // (watching every panel's class cost more on each button press than hiding them saved)
  ['player', 'sports', 'launch', 'menu', 'toast', 'osd', 'lkcode', 'pausebadge'].forEach(id => { const el = document.getElementById(id); if (el && set.has(el)) mo.observe(el, { attributes: true, attributeFilter: ['class'] }); });
  setTimeout(() => PERF.managed.forEach(perfSettle), 3000);
  // safety net for anything that becomes visible some other way (a cheap opacity reading only)
  setInterval(() => {
    if (document.hidden || performance.now() - PERF.lastKey < 2000) return;
    for (const el of PERF.hidden) if (parseFloat(getComputedStyle(el).opacity) > 0.001) perfSleep(el, false);
  }, 5000);
}
perfManage();

/* ---------------------------------------------------------------- frame-rate meter & speed test
   Settings → Speed test: a 30-second run on this TV (idle, moving around Home, Movies and Live TV,
   and the same with each Glass style), with the results on screen — so we can see what's slow. */

const HUD = { on: false, raf: 0, times: [] };
function hudStart() {
  if (HUD.raf) return;
  const el = $('#perfhud');
  el.classList.add('on');
  let last = performance.now(), acc = [], shown = performance.now();
  const f = t => {
    acc.push(t - last); last = t;
    if (t - shown > 500) {
      const n = acc.length, sum = acc.reduce((a, b) => a + b, 0), worst = Math.max(...acc);
      el.textContent = `${Math.round(n * 1000 / sum)} fps · worst ${Math.round(worst)} ms`;
      el.classList.toggle('bad', n * 1000 / sum < 40);
      acc = []; shown = t;
    }
    HUD.raf = requestAnimationFrame(f);
  };
  HUD.raf = requestAnimationFrame(f);
}
function hudStop() { cancelAnimationFrame(HUD.raf); HUD.raf = 0; $('#perfhud').classList.remove('on'); }
function hudToggle() { HUD.on = !HUD.on; if (HUD.on) hudStart(); else hudStop(); }

/* measure frame times for ms while doing something */
function perfFrames(ms, during) {
  return new Promise(res => {
    const ts = [];
    let t0 = 0;
    const f = t => {
      if (!t0) t0 = t;
      ts.push(t);
      if (t - t0 < ms) requestAnimationFrame(f);
      else {
        const d = ts.slice(1).map((x, i) => x - ts[i]).sort((a, b) => a - b);
        const fps = d.length * 1000 / (ts[ts.length - 1] - ts[0] || 1);
        res({ fps, p95: d[Math.floor(d.length * 0.95)] || 0, worst: d[d.length - 1] || 0, long: d.filter(x => x > 50).length });
      }
    };
    requestAnimationFrame(f);
    if (during) during();
  });
}
function perfPress(key) {
  const t0 = performance.now();
  document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
  document.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true }));
  PERF.keyMs.push(performance.now() - t0);
}
PERF.keyMs = []; PERF.longMs = 0;
try { new PerformanceObserver(l => { for (const e of l.getEntries()) PERF.longMs += e.duration; }).observe({ type: 'longtask', buffered: false }); } catch (e) {}
function perfSteps(keys, gap) { keys.forEach((k, i) => setTimeout(() => perfPress(k), i * gap)); }
const perfWait = ms => new Promise(r => setTimeout(r, ms));

async function speedTest() {
  if (PERF.testing) return;
  PERF.testing = true;
  if (mode === 'sheet') sheetClose();
  if (mode === 'settings') closeSettings();
  if (mode === 'menu') closeMenu();
  const keepGlass = S.glass, keepTab = tab;
  const hud = $('#perfhud');
  hud.classList.add('on');
  const say = t => { hud.textContent = 'Speed test · ' + t; };
  const out = [];
  const run = async (label, prep, keys, gap = 260, ms = 2600) => {
    say(label);
    if (prep) await prep();
    await perfWait(700);
    PERF.keyMs = []; PERF.longMs = 0;
    const r = await perfFrames(ms, keys ? () => perfSteps(keys, gap) : null);
    r.key = PERF.keyMs.length ? PERF.keyMs.reduce((a, b) => a + b, 0) / PERF.keyMs.length : 0;
    r.busy = PERF.longMs;
    out.push([label, r]);
  };
  const glass = g => async () => { S.glass = g; applyGlassMode(); };
  const nav = ['ArrowRight', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowLeft', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'ArrowLeft'];
  try {
    await run('Home · still', async () => { switchTab('home'); focus = 0; applyFocus(); await perfWait(1200); });
    await run('Home · moving around' + (BAKE.on ? ' (baked glass)' : ''), async () => { await perfWait(800); }, nav);
    await run('Home · moving · live glass', async () => { PERF.testing = 'live'; bakeOff(); }, nav);
    PERF.testing = true;
    await run('Home · moving · Frosted glass', glass('frosted'), nav);
    await run('Home · moving · Solid', glass('solid'), nav);
    await glass(keepGlass)();
    await run('Movies · moving around', async () => { switchTab('movies'); await perfWait(2500); }, ['ArrowDown', 'ArrowRight', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowRight']);
    await run('Live TV · moving around', async () => { switchTab('live'); await perfWait(2500); }, ['ArrowDown', 'ArrowDown', 'ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowUp'], 350);
    await run('Live TV · preview playing, still', async () => { const ch = LV.list[LV.row]; if (ch && S.livePreview !== false) lvPlay(ch, false); await perfWait(3500); });
    await run('Live TV · moving, preview playing', null, ['ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowUp', 'ArrowDown', 'ArrowUp'], 400);
    await run('Sports · moving around', async () => { if (typeof lvStop === 'function') { lvStop(); lvSetPlayer(''); } switchTab('sports'); await perfWait(2000); }, ['ArrowRight', 'ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowRight']);
  } catch (e) { out.push(['error: ' + e.message, { fps: 0, p95: 0, worst: 0, long: 0 }]); }
  S.glass = keepGlass; save(); applyGlassMode();
  switchTab(keepTab === 'live' ? 'home' : keepTab);
  hud.classList.remove('on');
  PERF.testing = false;
  let disp = ''; try { disp = B.displayInfo(); } catch (e) {}
  const chrome = (navigator.userAgent.match(/Chrome\/([\d.]+)/) || [])[1] || '?';
  const grade = r => r.fps >= 50 ? 'good' : r.fps >= 30 ? 'ok' : 'slow';
  sheetOpen('Speed test results',
    `<p class="shsm">Take a photo of this screen and send it over — it shows exactly what's slow on this TV.</p>
     <table class="pt"><tr><th></th><th>frames/s</th><th>slowest</th><th>hitches</th><th>per press</th><th>blocked</th></tr>` +
    out.map(([l, r]) => `<tr class="${grade(r)}"><td>${esc(l)}</td><td>${r.fps.toFixed(0)}</td><td>${Math.round(r.worst)} ms</td><td>${r.long}</td><td>${r.key ? Math.round(r.key) + ' ms' : '–'}</td><td>${Math.round(r.busy || 0)} ms</td></tr>`).join('') +
    `</table><p class="shsm">Screen ${esc(disp)} · page ${innerWidth}×${innerHeight} @${devicePixelRatio} · WebView ${esc(chrome)} · ${navigator.hardwareConcurrency || '?'} cores${navigator.deviceMemory ? ' · ' + navigator.deviceMemory + ' GB' : ''} · glass ${esc(S.glass)} · zoom ${S.motion ? 'on' : 'off'} · bakes ${BAKE.count || 0} · TopShelf ${esc((() => { try { return B.getVersion(); } catch (e) { return '?'; } })())}</p>`,
    [['Close', null], ['Run again', () => setTimeout(speedTest, 300)]]);
}

/* ---------------------------------------------------------------- baked glass (TVs, Home)
   The dock, tabs and top bar are glass over the photo. With live glass, every frame of every
   focus move re-blurs (and for Liquid, re-refracts) the photo behind them. While the photo is still,
   the result is the same every time — so on Home it's drawn once into a canvas behind each panel
   (same blur, same lens, same tint) and only redrawn when the photo takes one of its zoom steps.
   Anything unusual (photo changing, drawer or a panel open) switches back to live glass. */

const BAKE = { ids: ['dock', 'tabs', 'topbar'], imgs: new Map(), on: false, busy: false, lastSig: '' };
function bakeImg(url) {
  let im = BAKE.imgs.get(url);
  if (!im) {
    im = new Image(); im.decoding = 'async'; im.referrerPolicy = 'no-referrer'; im.src = url;
    if (BAKE.imgs.size > 4) BAKE.imgs.delete(BAKE.imgs.keys().next().value);
    BAKE.imgs.set(url, im);
  }
  return im.complete && im.naturalWidth ? im : null;
}
function bakeEligible(now) {
  if (!body.classList.contains('tvperf') || PORTRAIT || body.dataset.theme !== 'home' || S.glass === 'solid' || PERF.testing === 'live') return false;
  for (const c of ['deep', 'settings-open', 'search-open', 'shelf', 'nophotos', 'saver', 'booting', 'spick-open', 'link-open'])
    if (body.classList.contains(c)) return false;
  if (document.querySelectorAll('.bgimg.show').length !== 1) return false;
  // (no style readings here — they forced the page to be re-styled twice a second; timings instead)
  // (the timing rule only decides when to start baking: a class change that doesn't touch what's
  // behind the glass — the top shelf appearing as you move between apps — no longer switches it off)
  if (now) return true;
  if (Date.now() - (PERF.photoAt || 0) < 2000 || Date.now() - (PERF.stateAt || 0) < 900) return false;   // a photo or the page is still fading
  return true;
}
function bakeOff() {
  if (!BAKE.on) return;
  BAKE.on = false; if (typeof DIAG !== "undefined") DIAG.bakeFlips = (DIAG.bakeFlips || 0) + 1;
  BAKE.ids.forEach(id => { const el = $('#' + id); if (el) el.classList.remove('baked'); });
}
function bakeCanvas(el) {
  let c = el.querySelector(':scope > canvas.gbake');
  if (!c) { c = document.createElement('canvas'); c.className = 'gbake'; el.prepend(c); }
  return c;
}
function bakeDraw() {
  if (!bakeEligible(BAKE.on)) { if (!bakeEligible(true)) bakeOff(); return; }
  const layer = document.querySelector('.bgimg.show');
  const m = (layer.style.backgroundImage || '').match(/url\("?(.*?)"?\)$/);
  if (!m) { bakeOff(); return; }
  const im = bakeImg(m[1].replace(/%22/g, '"'));
  if (!im) { bakeOff(); setTimeout(bakeDraw, 400); return; }
  const R = layer.getBoundingClientRect();                      // the photo layer as drawn (zoom included)
  const cover = Math.max(R.width / im.naturalWidth, R.height / im.naturalHeight);
  const iw = im.naturalWidth * cover, ih = im.naturalHeight * cover;
  const ix = R.left + (R.width - iw) / 2, iy = R.top + (R.height - ih) / 2;
  const sr = $('#scrim').getBoundingClientRect(), scrimOp = parseFloat(getComputedStyle($('#scrim')).opacity);
  const rem = remPx();
  for (const id of BAKE.ids) {
    const el = $('#' + id);
    if (!el) continue;
    const r = el.getBoundingClientRect();
    const w = Math.round(r.width), h = Math.round(r.height);
    if (!w || !h) continue;
    const blurRem = parseFloat(el.dataset.blur || '.4');
    const lens = S.glass === 'liquid' && el.dataset.lens !== '0' && document.getElementById('lq-' + id);
    const filt = lens ? `url(#lq-${id}) blur(${(blurRem * rem).toFixed(1)}px) saturate(1.9) brightness(1.06)`
                      : `blur(${(Math.max(blurRem, 1.4) * rem).toFixed(1)}px) saturate(1.7)`;
    const M = Math.round(rem * 5);                              // margin so blur/lens edges have photo to pull from
    // 1. what's behind the panel: the photo, then the darkening gradient over it
    const off = BAKE['off_' + id] || (BAKE['off_' + id] = document.createElement('canvas'));
    if (off.width !== w + 2 * M || off.height !== h + 2 * M) { off.width = w + 2 * M; off.height = h + 2 * M; }
    const o = off.getContext('2d');
    o.clearRect(0, 0, off.width, off.height);
    o.drawImage(im, ix - r.left + M, iy - r.top + M, iw, ih);
    if (scrimOp > 0.01) {
      const g = o.createLinearGradient(0, sr.top - r.top + M, 0, sr.bottom - r.top + M);
      [[0, .42], [.16, 0], [.38, 0], [.62, .08], [1, .45]].forEach(([p, a]) => g.addColorStop(p, `rgba(0,0,0,${a * scrimOp})`));
      o.fillStyle = g; o.fillRect(0, 0, off.width, off.height);
    }
    // 2. the glass itself: same filter the live glass uses, then the panel's tint on top
    const c = bakeCanvas(el);
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
    const x = c.getContext('2d');
    x.clearRect(0, 0, w, h);
    x.save(); x.filter = filt; x.drawImage(off, -M, -M); x.restore();
    const tint = el.style.background || el.dataset.tint;
    if (tint && !/gradient|url/.test(tint)) { x.fillStyle = tint; x.fillRect(0, 0, w, h); }
  }
  if (!BAKE.on) { BAKE.on = true; if (typeof DIAG !== 'undefined') DIAG.bakeFlips = (DIAG.bakeFlips || 0) + 1; BAKE.ids.forEach(id => { const el = $('#' + id); if (el) el.classList.add('baked'); }); }
}
// redraw when the photo steps, and check now and then whether baking still applies
(function () {
  let sig = '';
  setInterval(() => {
    if (!body.classList.contains('tvperf') || performance.now() - PERF.lastKey < 1500) return;
    const l = document.querySelector('.bgimg.show');
    const s = [l && l._tf, l && l.style.backgroundImage, S.glass, innerWidth, innerHeight].join('|');
    if (s === sig && BAKE.on) return;
    if (!BAKE.on && !bakeEligible()) return;
    sig = s;
    BAKE.count = (BAKE.count || 0) + 1;
    try { bakeDraw(); } catch (e) { bakeOff(); }
  }, 500);   // the glass is blurred: following the photo twice a second is indistinguishable
  // the moment something changes (a panel opening, the photo starting to fade), go live again
  new MutationObserver(() => { if (BAKE.on && !bakeEligible(true)) bakeOff(); }).observe(body, { attributes: true, attributeFilter: ['class', 'data-theme'] });
  document.querySelectorAll('.bgimg').forEach(el => new MutationObserver(() => { if (BAKE.on) bakeOff(); }).observe(el, { attributes: true, attributeFilter: ['class'] }));
})();

/* ---------------------------------------------------------------- Home: lights that follow focus
   The rim of light around the glass turns toward the focused app, and a soft glow slides along the
   dock. Both used to be re-painted every frame for half a second after each press — on every glass
   panel, hidden ones included. Now only the three panels on screen turn their rim, and the glow is a
   separate layer that slides (the graphics chip moves it; nothing is re-painted). */
moveLight = function () {
  const col = focus % COLS, n = Math.max(1, COLS - 1);
  const la = (100 + col * 88 / n) + 'deg';
  for (const id of ['dock', 'tabs', 'topbar']) { const g = document.getElementById(id); if (g) g.style.setProperty('--la', la); }
  const fx = (10 + col * 80 / n) / 100;
  const dockW = SW - 2 * DRAWER_X;                            // the dock's width in rem
  $('#dock').style.setProperty('--glowx', (fx * dockW - 30).toFixed(2) + 'rem');
};
try { moveLight(); } catch (e) {}

/* ---------------------------------------------------------------- the Home photo slideshow only runs on Home
   (it kept loading and fading new photos behind Movies and Live TV, where they can't be seen) */
(function () {
  const ss0 = scheduleSlide;
  scheduleSlide = function () {
    if (typeof tab !== 'undefined' && tab !== 'home' && !body.classList.contains('shelf')) { clearTimeout(slideTimer); return; }
    ss0();
  };
  const st0 = switchTab;
  switchTab = function (t, keepTop) { const r = st0(t, keepTop); if (t === 'home') scheduleSlide(); return r; };
})();

// when the Home photo changes (baked glass waits for its fade to finish)
(function () { const prev = window.onPhotoShown; window.onPhotoShown = () => { PERF.photoAt = Date.now(); if (prev) prev(); }; })();

/* ---------------------------------------------------------------- Picture sharpness (TV)
   TopShelf can draw itself at a lower resolution and let the TV enlarge it. Every layer, blur and
   animation then costs the graphics chip a half (720p) to two thirds (900p) of the work. Text and
   edges get slightly softer. A new choice reverts by itself after 15 seconds unless you keep it. */
const RSCALES = [[1, 'Sharpest (1080p)'], [0.8334, 'Balanced (900p)'], [0.6667, 'Smoothest (720p)']];
function renderScaleName(v) { const r = RSCALES.find(x => Math.abs(x[0] - v) < 0.02); return r ? r[1] : 'Sharpest (1080p)'; }
function renderScaleStep(d) {
  const cur = B.getRenderScale();
  let i = RSCALES.findIndex(x => Math.abs(x[0] - cur) < 0.02); if (i < 0) i = 0;
  const next = RSCALES[(i + d + RSCALES.length) % RSCALES.length];
  B.tryRenderScale(next[0]);
  setTimeout(() => sheetOpen('Keep this sharpness?', `<p class="shsm">TopShelf is now drawn at <b>${esc(next[1])}</b>. If you don't choose, it goes back in 15 seconds.</p>`,
    [['Keep', () => { B.keepRenderScale(); toast('Picture sharpness: ' + next[1]); }], ['Go back', () => B.tryRenderScale(cur) || B.keepRenderScale()]]), 400);
}
window.tsRenderScale = st => { if (st === 'reverted') { if (mode === 'sheet') sheetClose(); toast('Picture sharpness went back'); } };
