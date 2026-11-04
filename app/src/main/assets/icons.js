/* Custom app icons. Hold an app → Change icon: built-in looks, a color, an icon pack's icon, or a
   picture of your own (on a TV without a photo picker, send it from your linked phone). */

ICONS.brush = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.5 4.5l5 5L10 19H5v-5z"/><path d="M12.5 6.5l5 5"/></svg>';

let ICN = readJSON('icons', {});
const IC_COLORS = [['#3a3a40', 'Graphite'], ['#0a5cd6', 'Blue'], ['#c62828', 'Red'], ['#1f8a4c', 'Green'], ['#6a3fc8', 'Purple'], ['#e0701c', 'Orange'], ['#d6336c', 'Pink'], ['#0f8b8d', 'Teal']];
const IC = { a: null, opts: [], sel: 0, from: 'grid', crop: null };
let IC_PACKS = null;

function icPacks() {
  if (IC_PACKS) return IC_PACKS;
  try { IC_PACKS = JSON.parse(B.iconPacks ? B.iconPacks() : '[]') || []; } catch (e) { IC_PACKS = []; }
  return IC_PACKS;
}
function icPackUrl(pack, a) { return 'https://topshelf.local/packicon/' + encodeURIComponent(pack) + '/' + encodeURIComponent(a.id); }
function icSave() { writeJSON('icons', ICN); }

/* which look an app has: its own choice, else the icon pack chosen in Settings (apps without a TV banner) */
function icFor(a) {
  const c = ICN[a.id];
  if (c) return c;
  if (S.iconPack && !a.banner && !a.mock && icPacks().some(p => p.pkg === S.iconPack)) return { k: 'pack', p: S.iconPack, auto: true };
  return null;
}

/* Draws a custom look into a tile. Returns false to fall back to the normal banner/icon. */
function customTile(tile, a, look) {
  const c = look || icFor(a);
  if (!c || c.k === 'orig') return false;
  const tint = c.c || a.tint || '#2a2a2e';
  const grad = col => `linear-gradient(160deg, ${shade(col, .18)}, ${shade(col, -.25)})`;
  const icon = (src, fallback) => {
    const img = new Image();
    img.className = 'ico'; img.decoding = 'async'; img.src = src;
    img.onerror = () => { if (fallback && img.src !== fallback) img.src = fallback; else img.remove(); };
    return img;
  };
  const own = a.mock ? '' : iconUrl(a, false);
  if (c.k === 'image') {
    const img = new Image();
    img.className = 'banner'; img.decoding = 'async';
    img.src = c.f.startsWith('data:') ? c.f : 'https://topshelf.local/customicon/' + encodeURIComponent(c.f);
    img.onerror = () => { img.remove(); tile.style.background = grad(tint); if (own) tile.prepend(icon(own)); };
    tile.appendChild(img);
    return true;
  }
  if (c.k === 'color' || c.k === 'pack') {
    tile.style.background = grad(c.k === 'pack' && !c.c ? (a.banner ? '#2a2a30' : tint) : tint);
    if (c.k === 'pack') { tile.prepend(icon(icPackUrl(c.p, a), own)); return true; }
    if (own) tile.prepend(icon(own)); else tile.insertAdjacentHTML('afterbegin', `<div class="glyph">${esc(a.label.slice(0, 1))}</div>`);
    return true;
  }
  if (c.k === 'frost') {
    tile.style.background = grad(tint);
    tile.classList.add('ic-frost');
    if (own) {
      const bg = new Image(); bg.className = 'icbg'; bg.src = own; bg.onerror = () => bg.remove();
      tile.append(bg, icon(own));
    } else tile.insertAdjacentHTML('afterbegin', `<div class="glyph">${esc(a.label.slice(0, 1))}</div>`);
    return true;
  }
  if (c.k === 'name') {
    tile.style.background = grad(tint);
    tile.classList.add('ic-name');
    tile.insertAdjacentHTML('afterbegin', (own ? `<img class="icsm" src="${esc(own)}" onerror="this.remove()">` : '') + `<div class="icword">${esc(a.label)}</div>`);
    return true;
  }
  return false;
}

/* redraw one app everywhere it appears */
function icRefresh(id) {
  const el = cellEls.get(id);
  if (el) { el.remove(); cellEls.delete(id); }
  render();
  if (window.tshelfUpdate) tshelfUpdate();
}

/* ---------------------------------------------------------------- the picker */

function icOptions(a) {
  const o = [{ k: 'orig', name: a.banner ? 'Original banner' : 'Original' }, { k: 'frost', name: 'Frosted' }, { k: 'name', name: 'App name' }];
  IC_COLORS.forEach(([c, n]) => o.push({ k: 'color', c, name: n }));
  if (!a.mock) icPacks().forEach(p => o.push({ k: 'pack', p: p.pkg, name: p.label }));
  o.push({ k: 'photo', name: IS_TV ? 'Your own picture' : 'Choose a photo…' });
  return o;
}
function icOpen(a) {
  if (!a || a.special) return;
  if (mode === 'menu') closeMenu();
  IC.a = a; IC.crop = null;
  IC.from = mode;
  IC.opts = icOptions(a);
  const cur = ICN[a.id];
  IC.sel = Math.max(0, cur ? IC.opts.findIndex(o => o.k === cur.k && (o.c || '') === (cur.c || '') && (o.p || '') === (cur.p || '')) : 0);
  if (cur && cur.k === 'image') IC.opts.splice(1, 0, { k: 'image', f: cur.f, name: 'Your picture' }), IC.sel = 1;
  mode = 'icpick';
  body.classList.add('icpick-open');
  styleGlass($('#icpick'));
  $('#icdone').style.display = IS_TV ? 'none' : '';
  icDraw();
}
function icClose() {
  IC.send = null;
  body.classList.remove('icpick-open');
  mode = IC.from && IC.from !== 'icpick' && IC.from !== 'menu' ? IC.from : baseMode();
  IC.crop = null;
  applyFocus();
}
function icPreview(a, opt) {
  const tile = document.createElement('div');
  tile.className = 'tile';
  if (opt.k === 'photo') {
    tile.classList.add('ic-photo');
    tile.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="3"/><circle cx="9" cy="10" r="1.8"/><path d="M21 16l-5-5-9 9"/></svg>`;
  } else if (opt.k === 'crop') {
    tile.innerHTML = `<img class="banner" src="${opt.src}">`;
  } else if (!customTile(tile, a, opt)) {
    if (a.banner && !a.mock) tile.innerHTML = `<img class="banner" src="${esc(iconUrl(a, true))}">`;
    else fillIcon(tile, a);
  }
  tile.insertAdjacentHTML('beforeend', '<div class="rim"></div>');
  return tile;
}
function icDraw() {
  const a = IC.a, opts = IC.crop ? IC.crop : IC.opts;
  $('#ictitle').textContent = IC.crop ? `Fit your picture · ${a.label}` : `Icon for ${a.label}`;
  const strip = $('#icstrip');
  strip.innerHTML = '';
  opts.forEach((o, i) => {
    const w = document.createElement('div');
    w.className = 'icopt' + (i === IC.sel ? ' f' : '');
    w.dataset.i = i;
    w.appendChild(icPreview(a, o));
    w.insertAdjacentHTML('beforeend', `<span>${esc(o.name)}</span>`);
    strip.appendChild(w);
  });
  $('#ichint').textContent = typeof V !== 'undefined' && V.touch ? 'Tap a look to use it' : '◀ ▶ choose · OK use it · Back cancel';
  icMark(true);
}
function icMark(instant) {
  const strip = $('#icstrip');
  strip.querySelectorAll('.icopt').forEach((el, i) => el.classList.toggle('f', i === IC.sel));
  const f = strip.querySelector('.icopt.f');
  if (f) {
    const left = f.offsetLeft - strip.clientWidth / 2 + f.offsetWidth / 2;
    strip.scrollTo({ left: Math.max(0, left), behavior: instant ? 'auto' : 'smooth' });
  }
}
function icChoose(i) {
  const a = IC.a, o = (IC.crop || IC.opts)[i];
  if (!o) return;
  if (o.k === 'photo') return icPhoto();
  if (o.k === 'crop') return icUseCrop(o);
  if (o.k === 'orig') { delete ICN[a.id]; try { B.iconDelete(a.id); } catch (e) {} }
  else if (o.k === 'image') { icClose(); return; }
  else {
    if (ICN[a.id] && ICN[a.id].k === 'image') { try { B.iconDelete(a.id); } catch (e) {} }
    ICN[a.id] = o.k === 'color' ? { k: 'color', c: o.c } : o.k === 'pack' ? { k: 'pack', p: o.p } : { k: o.k };
  }
  icSave();
  icClose();
  icRefresh(a.id);
  toast(o.k === 'orig' ? `${a.label}: original icon` : `${a.label}: ${o.name}`);
}

/* ---- your own picture ---- */
function icPhoto() {
  try { B.iconPickImage(); }
  catch (e) { tsIconPicked(null, 'nopicker'); }
}
window.tsIconPicked = (url, why) => {
  if (!url) {
    if (why === 'nopicker') toast(typeof lkInfo === 'function' ? 'No photo picker here — on your phone open Settings → TV app icons to send a picture' : 'No photo picker on this device');
    else if (why === 'unreadable') toast("Couldn't open that picture");
    return;
  }
  icCrops(url).then(list => {
    if (!list) return toast("Couldn't open that picture");
    if (mode !== 'icpick') { mode = 'icpick'; body.classList.add('icpick-open'); }
    IC.crop = list; IC.sel = 0;
    icDraw();
  });
};
/* A picture fitted to the tile a few ways: filled (centred, or nudged to either end) or whole with a blurred backdrop. */
function icCrops(url) {
  return new Promise(res => {
    const im = new Image();
    im.onload = () => {
      const W = 640, H = 360, out = [];
      const draw = (fn) => { const c = document.createElement('canvas'); c.width = W; c.height = H; const x = c.getContext('2d'); fn(x); return c.toDataURL('image/jpeg', 0.88); };
      const iw = im.naturalWidth, ih = im.naturalHeight;
      if (!iw || !ih) return res(null);
      const s = Math.max(W / iw, H / ih), dw = iw * s, dh = ih * s;
      const wide = dw - W > 2, tall = dh - H > 2;
      const fill = t => draw(x => x.drawImage(im, wide ? (W - dw) * t : (W - dw) / 2, tall ? (H - dh) * t : (H - dh) / 2, dw, dh));
      out.push({ k: 'crop', name: 'Fill', src: fill(0.5) });
      if (wide || tall) {
        out.push({ k: 'crop', name: wide ? 'Fill · left' : 'Fill · top', src: fill(0) });
        out.push({ k: 'crop', name: wide ? 'Fill · right' : 'Fill · bottom', src: fill(1) });
      }
      out.push({ k: 'crop', name: 'Whole picture', src: draw(x => {
        x.filter = 'blur(28px) brightness(.7)'; x.drawImage(im, (W - dw * 1.15) / 2, (H - dh * 1.15) / 2, dw * 1.15, dh * 1.15);
        x.filter = 'none';
        const f = Math.min(W / iw, H / ih) * 0.92, fw = iw * f, fh = ih * f;
        x.drawImage(im, (W - fw) / 2, (H - fh) / 2, fw, fh);
      }) });
      res(out);
    };
    im.onerror = () => res(null);
    im.src = url;
  });
}
function icUseCrop(o) {
  const a = IC.a;
  if (IC.send) { const send = IC.send; IC.send = null; icClose(); return send(o.src); }
  const f = B.iconSave(a.id, o.src);
  if (!f) return toast("Couldn't save the picture");
  ICN[a.id] = { k: 'image', f };
  icSave(); icClose(); icRefresh(a.id);
  toast(`${a.label}: your picture`);
}

/* ---- remote & touch ---- */
function icKey(k, e) {
  if (!k) return;
  e.preventDefault();
  const n = (IC.crop || IC.opts).length;
  if (k === 'back') { if (IC.crop && !IC.send && IC.opts.length) { IC.crop = null; IC.sel = IC.opts.findIndex(o => o.k === 'photo'); icDraw(); return; } return icClose(); }
  if (k === 'ok') { if (!e.repeat) icChoose(IC.sel); return; }
  if (k === 'l' && IC.sel > 0) IC.sel--;
  else if (k === 'r' && IC.sel < n - 1) IC.sel++;
  else if (k === 'u' || k === 'd') return;
  icMark(false);
}
function icTap(t) {
  if (t.closest('#icdone') || !t.closest('#icpick')) return icClose();
  const o = t.closest('.icopt');
  if (!o) return;
  IC.sel = +o.dataset.i; icMark(false);
  icChoose(IC.sel);
}

/* ---------------------------------------------------------------- phone → TV: send a picture for one of the TV's apps */

async function icTvApps() {
  const r = await lkSend({ type: 'apps' });
  if (!r || !r.apps) return;
  icTvMenu(r.apps, 0);
}
function icTvMenu(apps, page) {
  const per = 9, list = apps.slice(page * per, page * per + per);
  showMenu(list.map(x => ['brush', x.label, () => icTvPick(x)])
    .concat(apps.length > (page + 1) * per ? [['open', 'More apps…', () => setTimeout(() => icTvMenu(apps, page + 1), 50)]] : []),
    null, `${lkTvName()} · choose an app`);
}
function icTvPick(x) {
  // pretend the TV's app is here so the fitting step can preview it, then send the result over
  IC.a = { id: x.id, label: x.label, banner: true, mock: true, tint: '#2a2a30' };
  IC.from = baseMode(); IC.opts = [];
  IC.send = async src => {
    const r = await lkSend({ type: 'icon', id: x.id, img: src });
    if (r && r.ok) toast(`New icon for ${x.label} on ${lkTvName()}`);
  };
  icPhoto();
}
/* TV side of the link */
window.icLinkCmd = c => {
  if (c.type === 'apps') return JSON.stringify({ apps: items.filter(a => !a.special).slice(0, 60).map(a => ({ id: a.id, label: a.label })) });
  if (c.type === 'icon') {
    const a = items.find(x => x.id === c.id) || apps.find(x => x.id === c.id);
    if (!a || !c.img) return JSON.stringify({ ok: false });
    const f = B.iconSave(a.id, c.img);
    if (!f) return JSON.stringify({ ok: false });
    ICN[a.id] = { k: 'image', f };
    icSave();
    setTimeout(() => { icRefresh(a.id); toast(`New icon for ${a.label} from ${c.from || 'your phone'}`); }, 0);
    return JSON.stringify({ ok: true });
  }
  return '';
};

/* settings helpers */
function icPackLabel() {
  const p = icPacks().find(x => x.pkg === S.iconPack);
  return p ? p.label : icPacks().length ? 'None' : 'None installed';
}
function icPackCycle(d) {
  const list = [['']].concat(icPacks().map(p => [p.pkg]));
  if (list.length < 2) return toast('Install an icon pack (one made for Nova or other launchers) to use it here');
  S.iconPack = step(list, S.iconPack || '', d);
  save();
  for (const [id, el] of cellEls) { el.remove(); cellEls.delete(id); }
  render();
}
function icResetAll() {
  const n = Object.keys(ICN).length;
  if (!n) return toast('No custom icons');
  ICN = {}; icSave();
  try { B.iconDeleteAll(); } catch (e) {}
  for (const [id, el] of cellEls) { el.remove(); cellEls.delete(id); }
  render();
  toast(`${n} icon${n > 1 ? 's' : ''} back to normal`);
}
