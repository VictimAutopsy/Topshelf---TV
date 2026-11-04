'use strict';
/* =====================================================================
   Phone ↔ TV link. TopShelf on your phone finds TopShelf on your TV over Wi-Fi, pairs with a
   4-digit code shown on the TV, and then works as its remote and keyboard, sends things to watch
   ("Watch on TV"), and keeps favorites, My List, teams and reminders in sync.
   The TV side runs a small server in Android (LinkServer.java); commands arrive in tsLinkCmd().
   ===================================================================== */

const LK = { linked: readJSON('link', null), found: [], reqs: {}, seq: 0, step: 'list', pairDev: null, pairId: '', pairErr: '', busy: false };
ICONS.tv = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="13" rx="2.5"/><path d="M8 21h8"/></svg>';
ICONS.remote = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="7" y="2.5" width="10" height="19" rx="5"/><circle cx="12" cy="8" r="2"/><path d="M12 13.5v.01M12 17v.01"/></svg>';
function lkInfo() { try { return JSON.parse(B.linkInfo ? B.linkInfo() : '{}') || {}; } catch (e) { return {}; } }
function lkOn() { return !!(LK.linked && LK.linked.token) && !IS_TV; }
function lkTvName() { return LK.linked ? LK.linked.name : 'TV'; }

/* ---------------------------------------------------------------- talking to the other device */

window.tsLinkResp = (id, code, body) => {
  const r = LK.reqs[id];
  if (!r) return;
  delete LK.reqs[id];
  let j = {};
  try { j = body ? JSON.parse(body) : {}; } catch (e) { j = { error: body }; }
  if (code === 200) r.res(j); else r.rej(new Error(j.error || (code ? 'HTTP ' + code : "Couldn't reach it")));
};
function lkPost(dev, path, body) {
  return new Promise((res, rej) => {
    if (!B.linkPost) return rej(new Error('Only works in the app'));
    const id = 'r' + (++LK.seq);
    LK.reqs[id] = { res, rej };
    setTimeout(() => { if (LK.reqs[id]) { delete LK.reqs[id]; rej(new Error("Couldn't reach it")); } }, 12000);
    B.linkPost(id, `http://${dev.host}:${dev.port}${path}`, JSON.stringify(body || {}));
  });
}
/* Sends a command to the linked TV. Quiet: errors show as a short message. */
async function lkSend(cmd, quiet) {
  if (!lkOn()) return null;
  try { return await lkPost(LK.linked, '/cmd', Object.assign({ token: LK.linked.token }, cmd)); }
  catch (e) {
    if (/not paired/i.test(e.message)) { LK.linked = null; writeJSON('link', null); body.classList.remove('linked'); toast('The TV forgot this phone — pair again'); }
    else if (!quiet) toast(`Couldn't reach ${lkTvName()}: ${e.message}`);
    return null;
  }
}

/* ---------------------------------------------------------------- TV side: commands from the phone */

window.tsLinkPairCode = (code, name) => {
  const el = $('#lkcode');
  el.innerHTML = `<div class="lkc1">Pair with ${esc(name)}</div><div class="lkc2">${esc(code)}</div><div class="lkc3">Type this code on your phone</div>`;
  styleGlass(el);
  el.classList.add('show');
  clearTimeout(LK.codeT);
  LK.codeT = setTimeout(() => el.classList.remove('show'), 180000);
  if (body.classList.contains('saver')) stopSaver();
};
window.tsLinkPaired = name => {
  $('#lkcode').classList.remove('show');
  toast(`Linked with ${name}`);
  if (mode === 'link') lkDraw();
};

const LK_KEYS = { u: 'ArrowUp', d: 'ArrowDown', l: 'ArrowLeft', r: 'ArrowRight', ok: 'Enter', menu: 'ContextMenu' };
function lkKey(k, hold) {
  if (k === 'back') return tsBack();
  if (k === 'home') return tsHome();
  const key = LK_KEYS[k];
  if (!key) return;
  document.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
  setTimeout(() => document.dispatchEvent(new KeyboardEvent('keyup', { key, bubbles: true, cancelable: true })), hold ? 760 : 40);
}
function lkText(text, submit, asSearch) {
  const set = (inp, v) => { inp.value = v; inp.dispatchEvent(new Event('input', { bubbles: true })); };
  if (asSearch || !['search', 'edit', 'spick'].includes(mode)) {
    if (mode !== 'search') { if (mode === 'settings') closeSettings(); if (mode === 'menu') closeMenu(); openSearch(); }
    set($('#sq'), text); runSearch();
    if (submit) { srRemember(text); $('#sq').blur(); if (SR.rows.length) { SR.r = 0; srMark(); } }
    return;
  }
  const inp = mode === 'search' ? $('#sq') : mode === 'spick' ? $('#spq') : $('#edinput');
  if (!inp) return;
  set(inp, text);
  if (mode === 'search') runSearch();
  if (submit) inp.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
}
function lkFindChannel(u, n) {
  if (!LV.loaded) { try { lvLoad(); } catch (e) {} }
  let ch = LV.chans.find(c => c.u === u);
  if (!ch && n) { const k = n.toLowerCase().replace(/[^a-z0-9]/g, ''); ch = LV.chans.find(c => c.n.toLowerCase().replace(/[^a-z0-9]/g, '') === k); }
  return ch;
}
function lkOpen(c) {
  if (body.classList.contains('saver')) stopSaver();
  if (mode === 'search') closeSearch();
  if (mode === 'menu') closeMenu();
  if (mode === 'settings') closeSettings();
  if (mode === 'shelf') exitShelf();
  if (c.what === 'channel') {
    const ch = lkFindChannel(c.u, c.n);
    if (!ch) { toast(`${c.n || 'That channel'} isn't in this TV's playlist`); return; }
    srWatch(ch);
    toast(`${c.from || 'Your phone'} sent ${ch.n}`);
  } else if (c.what === 'title' && c.m) {
    vOpen(c.m);
  } else if (c.what === 'search') {
    lkText(c.q || '', true, true);
  }
}

/* Lists both devices keep: favorites, My List, sports & teams, reminders, recent searches. */
function lkData() {
  return { favs: Array.from(LV.favs), mylist: V.mine, sports: SP.prefs, reminders: REMS.list, recent: SR.recent };
}
function lkMerge(d) {
  if (!d) return lkData();
  (d.favs || []).forEach(u => LV.favs.add(u));
  writeJSON('favs', Array.from(LV.favs));
  if (LV.cats && LV.cats[0]) LV.cats[0].count = LV.favs.size;
  const key = m => m.type + ':' + m.id;
  const have = new Set(V.mine.map(key));
  V.mine = V.mine.concat((d.mylist || []).filter(m => m && m.id && !have.has(key(m)))).slice(0, 200);
  writeJSON('mylist', V.mine);
  if (d.sports) {
    SP.prefs.leagues = SP_LEAGUES.map(l => l.id).filter(id => SP.prefs.leagues.includes(id) || (d.sports.leagues || []).includes(id));
    for (const [lg, list] of Object.entries(d.sports.teams || {})) {
      const mine = SP.prefs.teams[lg] || [];
      SP.prefs.teams[lg] = mine.concat((list || []).filter(t => !mine.some(x => x.id === t.id)));
    }
    spSave();
  }
  const ids = new Set(REMS.list.map(r => r.id));
  const now = Date.now();
  for (const r of d.reminders || []) {
    if (!r || ids.has(r.id) || r.end < now) continue;
    REMS.list.push(r);
    try { B.setReminder && B.setReminder(r.id, r.at, `Starting soon: ${r.title}`, `${r.num} · ${r.n} at ${fmtTime(r.at)}`, r.u); } catch (e) {}
  }
  REMS.list.sort((a, b) => a.at - b.at);
  remSave();
  SR.recent = Array.from(new Set(SR.recent.concat(d.recent || []))).slice(0, 10);
  writeJSON('recentSearches', SR.recent);
  // refresh what's on screen
  if (LV.loaded) { lvBuildList(); LV.needBuild = true; if (tab === 'live') lvDraw(); }
  if (window.tshelfRebuild) tshelfRebuild();
  if (isVodTab() && V.rows.length) { V.rows = []; vEnter(V.type); }
  if (tab === 'sports') spBuild();
  return lkData();
}

window.tsLinkCmd = json => {
  let c = {};
  try { c = JSON.parse(json); } catch (e) { return ''; }
  IDLE.last = Date.now();
  switch (c.type) {
    case 'key': lkKey(c.k, c.hold); return '';
    case 'text': lkText(c.text || '', !!c.submit, !!c.search); return '';
    case 'open': lkOpen(c); return '';
    case 'diag': case 'diagget': return window.diagLinkCmd ? diagLinkCmd(c) : '';
    case 'apps': case 'icon': return window.icLinkCmd ? icLinkCmd(c) : '';
    case 'restore': return window.bkFromLink ? bkFromLink(c.data, c.from) : '';
    case 'sync': { const out = lkMerge(c.data); if (!c.quiet) toast(`Synced with ${c.from || 'your phone'}`); return JSON.stringify({ data: out }); }
    case 'hello': return JSON.stringify({ ok: true, tab, mode });
    case 'ghtoken': return window.updLinkCmd ? updLinkCmd(c) : '';
    case 'trakt': { let url = ''; try { url = B.traktSetupUrl ? B.traktSetupUrl() : ''; } catch (e) {} return JSON.stringify({ url }); }
    case 'addons': { let url = ''; try { url = B.addonsPhoneUrl ? B.addonsPhoneUrl() : ''; } catch (e) {} return JSON.stringify({ url }); }
    case 'glitch': { try { if (B.reportGlitch) B.reportGlitch('sent from ' + (c.from || 'the phone')); } catch (e) {} return JSON.stringify({ ok: !!B.reportGlitch }); }
    case 'rec': { try { return B.recCmd ? B.recCmd(JSON.stringify(c)) : JSON.stringify({ error: 'This TV needs an update for recordings' }); } catch (e) { return JSON.stringify({ error: String(e) }); } }
    case 'drive': { let url = ''; try { url = B.driveSetupUrl ? B.driveSetupUrl() : ''; if (url && B.driveSetup) B.driveSetup(); } catch (e) {} return JSON.stringify({ url }); }
    case 'fyrow': { let row = []; try { row = JSON.parse(B.fantasyRow ? B.fantasyRow() : '[]') || []; } catch (e) {} return JSON.stringify({ row }); }
    case 'fantasy': { let url = ''; try { url = c.page === 'players' ? (B.fantasyPlayersUrl ? B.fantasyPlayersUrl() : '') : B.fantasySetupUrl ? B.fantasySetupUrl() : ''; } catch (e) {} return JSON.stringify({ url, status: (() => { try { return JSON.parse(B.fantasyStatus()); } catch (e) { return {}; } })() }); }
  }
  return '';
};

/* ---------------------------------------------------------------- phone side: finding & pairing */

window.tsLinkFound = json => {
  let d;
  try { d = JSON.parse(json); } catch (e) { return; }
  if (!d.host) return;
  const i = LK.found.findIndex(x => x.name === d.name);
  if (i >= 0) LK.found[i] = d; else LK.found.push(d);
  // a linked TV that moved to a new address: follow it
  if (LK.linked && LK.linked.name === d.name && (LK.linked.host !== d.host || LK.linked.port !== d.port)) {
    LK.linked.host = d.host; LK.linked.port = d.port; writeJSON('link', LK.linked);
  }
  if (mode === 'link') lkDraw();
};
function lkSearch() {
  LK.found = [];
  try { B.linkDiscover && B.linkDiscover(); } catch (e) {}
  clearTimeout(LK.discT);
  LK.discT = setTimeout(() => { try { B.linkStopDiscover && B.linkStopDiscover(); } catch (e) {} LK.searched = true; if (mode === 'link') lkDraw(); }, 15000);
}
async function lkStartPair(dev) {
  LK.pairDev = dev; LK.pairErr = ''; LK.busy = true; LK.step = 'code'; lkDraw();
  try {
    const r = await lkPost(dev, '/pair/start', { name: lkInfo().name || 'phone' });
    LK.pairId = r.id;
    if (r.name) dev.name = r.name;
  } catch (e) { LK.pairErr = `Couldn't reach ${dev.name || dev.host}: ${e.message}`; LK.step = 'list'; }
  LK.busy = false;
  lkDraw();
  setTimeout(() => { const i = $('#lkpin'); if (i) i.focus({ preventScroll: true }); }, 60);
}
async function lkFinishPair(code) {
  const dev = LK.pairDev;
  if (!dev) return;
  LK.busy = true; lkDraw();
  try {
    const r = await lkPost(dev, '/pair/finish', { id: LK.pairId, code });
    LK.linked = { name: r.name || dev.name, host: dev.host, port: dev.port, token: r.token };
    writeJSON('link', LK.linked);
    body.classList.add('linked');
    LK.step = 'remote';
    toast(`Linked with ${LK.linked.name}`);
    lkSync(true);
  } catch (e) { LK.pairErr = e.message === 'wrong code' ? 'That code didn’t match — check the TV and try again' : e.message; }
  LK.busy = false;
  lkDraw();
}
async function lkSync(quiet) {
  const r = await lkSend({ type: 'sync', data: lkData(), quiet: !!quiet }, quiet);
  if (r && r.data) { lkMerge(r.data); if (!quiet) toast(`Favorites, My List, teams and reminders synced with ${lkTvName()}`); }
}
function lkUnlink() {
  if (LK.linked) lkPost(LK.linked, '/unpair', { token: LK.linked.token }).catch(() => {});
  LK.linked = null; writeJSON('link', null);
  body.classList.remove('linked');
  LK.step = 'list'; lkSearch(); lkDraw();
}
/* "Watch on TV" entries for the hold menus (phone only, once linked). */
function lkMenuItem(label, cmd) { return lkOn() ? [['tv', `${label} on ${lkTvName()}`, () => lkSend(cmd).then(r => { if (r) toast(`Sent to ${lkTvName()}`); })]] : []; }

/* ---------------------------------------------------------------- the panel */

function openLink() {
  if (mode === 'top') exitTop();
  if (mode === 'settings') closeSettings();
  LK.from = mode === 'menu' ? baseMode() : mode;
  mode = 'link';
  body.classList.add('link-open');
  styleGlass($('#link'));
  LK.step = lkOn() ? 'remote' : 'list';
  if (!IS_TV && !lkOn()) lkSearch();
  if (lkOn()) lkSend({ type: 'hello' }, true);
  lkDraw();
}
function closeLink() {
  const i = document.activeElement; if (i && i.blur) i.blur();
  body.classList.remove('link-open');
  mode = LK.from && LK.from !== 'link' ? LK.from : baseMode();
  try { B.linkStopDiscover && B.linkStopDiscover(); } catch (e) {}
  if (isVodTab()) vMark();
  if (tab === 'live') lvDraw();
}

function lkDraw() {
  const me = lkInfo();
  let h = '';
  if (IS_TV || LK.step === 'about') {
    // this device: how to link a phone to it
    h += `<div class="lkhero">${ICONS.tv}<div><b>${esc(me.name || 'This TV')}</b><span>${me.ip ? `On your Wi-Fi at ${esc(me.ip)}${me.port && me.port !== 8790 ? ':' + me.port : ''}` : 'Not on Wi-Fi'}</span></div></div>` +
      `<div class="lkp">On your phone, open TopShelf → Settings → <b>Phone &amp; TV link</b>, pick this TV and type the code that appears here.</div>` +
      `<div class="lkp dim">${(me.paired || []).length ? `Linked phones: ${esc(me.paired.join(', '))}` : 'No phones linked yet.'}</div>` +
      ((me.paired || []).length ? `<div class="lkbtns"><div class="lkb" data-a="forget">Unlink all phones</div></div>` : '');
    if (!IS_TV) h += `<div class="lkbtns"><div class="lkb" data-a="back">‹ Back</div></div>`;
  } else if (LK.step === 'remote' && lkOn()) {
    h += `<div class="lkhero">${ICONS.tv}<div><b>${esc(lkTvName())}</b><span>Connected · controls go to the TV</span></div><div class="lkb sm" data-a="sync">Sync lists</div><div class="lkb sm" data-a="unlink">Unlink</div></div>` +
      `<div id="lkpad"><span>Swipe to move · tap to select · hold for more</span></div>` +
      `<div class="lkkeys"><div class="lkb" data-a="k:back">‹ Back</div><div class="lkb" data-a="k:home">Home</div><div class="lkb" data-a="k:hold">More</div><div class="lkb mic" data-a="mic">${MIC_SVG}</div></div>` +
      `<div class="lktype"><input id="lktxt" type="text" placeholder="Type on the TV (search, links, codes…)" autocapitalize="off" autocomplete="off" spellcheck="false"><div class="lkb" data-a="send">Go</div></div>`;
  } else if (LK.step === 'code' && LK.pairDev) {
    h += `<div class="lkhero">${ICONS.tv}<div><b>${esc(LK.pairDev.name || LK.pairDev.host)}</b><span>${LK.busy ? 'Asking the TV for a code…' : 'A 4-digit code is now on the TV'}</span></div></div>` +
      `<div class="lktype"><input id="lkpin" type="tel" inputmode="numeric" maxlength="4" placeholder="Code on the TV"><div class="lkb" data-a="pair">Pair</div></div>` +
      (LK.pairErr ? `<div class="lkp err">${esc(LK.pairErr)}</div>` : '') +
      `<div class="lkbtns"><div class="lkb" data-a="list">‹ Pick another TV</div></div>`;
  } else {
    h += `<div class="lkp">Make sure TopShelf is open on your TV and both are on the same Wi-Fi.</div>`;
    h += LK.found.length ? `<div class="lklist">${LK.found.map((d, i) => `<div class="lkdev" data-i="${i}">${ICONS.tv}<b>${esc(d.name)}</b><span>${esc(d.host)}</span></div>`).join('')}</div>`
                         : `<div class="lkp dim">${LK.searched ? 'No TVs found yet.' : '<span class="spin sm"></span> Looking for TopShelf on your Wi-Fi…'}</div>`;
    h += `<div class="lktype"><input id="lkip" type="text" inputmode="decimal" placeholder="Or type the TV's address (shown on the TV)" autocapitalize="off"><div class="lkb" data-a="ip">Connect</div></div>` +
      (LK.pairErr ? `<div class="lkp err">${esc(LK.pairErr)}</div>` : '') +
      `<div class="lkbtns"><div class="lkb" data-a="rescan">Search again</div><div class="lkb" data-a="about">This device</div></div>`;
  }
  $('#lkbody').innerHTML = h;
  const pad = $('#lkpad');
  if (pad) lkWirePad(pad);
  const txt = $('#lktxt');
  if (txt) {
    txt.addEventListener('input', () => { clearTimeout(LK.txtT); LK.txtT = setTimeout(() => lkSend({ type: 'text', text: txt.value }, true), 280); });
    txt.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); clearTimeout(LK.txtT); lkSend({ type: 'text', text: txt.value, submit: true }); txt.blur(); } });
  }
  const pin = $('#lkpin');
  if (pin) pin.addEventListener('input', () => { if (/^\d{4}$/.test(pin.value)) lkFinishPair(pin.value); });
}

/* The touchpad: swipe = arrows (one step per ~3rem of travel), tap = OK, hold = hold OK. */
function lkWirePad(pad) {
  let st = null;
  const step = () => remPx() * 3.2;
  const send = k => { lkSend({ type: 'key', k }, true); try { B.haptic && B.haptic(); } catch (e) {} pad.classList.remove('k-u', 'k-d', 'k-l', 'k-r'); void pad.offsetWidth; pad.classList.add('k-' + k); };
  pad.addEventListener('pointerdown', e => {
    st = { x: e.clientX, y: e.clientY, ax: e.clientX, ay: e.clientY, moved: false, t0: e.timeStamp };
    clearTimeout(LK.holdT);
    LK.holdT = setTimeout(() => { if (st && !st.moved) { try { B.haptic && B.haptic(); } catch (e2) {} pad.classList.add('holding'); } }, 550);
  });
  pad.addEventListener('pointermove', e => {
    if (!st) return;
    if (!st.moved && Math.hypot(e.clientX - st.x, e.clientY - st.y) > remPx() * 1.4) { st.moved = true; clearTimeout(LK.holdT); pad.classList.remove('holding'); }
    const dx = e.clientX - st.ax, dy = e.clientY - st.ay, s = step();
    if (Math.abs(dx) >= s || Math.abs(dy) >= s) {
      if (Math.abs(dx) > Math.abs(dy)) { send(dx > 0 ? 'r' : 'l'); st.ax += Math.sign(dx) * s; st.ay = e.clientY; }
      else { send(dy > 0 ? 'd' : 'u'); st.ay += Math.sign(dy) * s; st.ax = e.clientX; }
    }
  });
  // decided on release, from the finger's own timing: still + 0.55 s or more = hold, otherwise a tap
  const end = e => {
    clearTimeout(LK.holdT);
    pad.classList.remove('holding');
    if (st && !st.moved) {
      if (e && e.timeStamp - st.t0 >= 550) { lkSend({ type: 'key', k: 'ok', hold: true }, true); pad.classList.remove('k-ok'); void pad.offsetWidth; pad.classList.add('k-ok'); }
      else send('ok');
    }
    st = null;
  };
  pad.addEventListener('pointerup', end);
  pad.addEventListener('pointercancel', () => { clearTimeout(LK.holdT); st = null; });
}

function linkTap(t) {
  if (t.closest('#lkclose') || !t.closest('#link')) return closeLink();
  if (t.closest('#lkpad')) return;
  const dev = t.closest('.lkdev');
  if (dev) return lkStartPair(LK.found[+dev.dataset.i]);
  const b = t.closest('.lkb');
  if (!b) return;
  const a = b.dataset.a;
  if (a === 'pair') { const v = ($('#lkpin') || {}).value || ''; if (/^\d{4}$/.test(v)) lkFinishPair(v); else LK.pairErr = 'Enter the 4 digits shown on the TV', lkDraw(); }
  else if (a === 'list') { LK.step = 'list'; LK.pairErr = ''; lkDraw(); }
  else if (a === 'rescan') { LK.searched = false; lkSearch(); lkDraw(); }
  else if (a === 'about') { LK.step = 'about'; lkDraw(); }
  else if (a === 'back') { LK.step = lkOn() ? 'remote' : 'list'; lkDraw(); }
  else if (a === 'forget') { try { B.linkForgetAll(); } catch (e) {} toast('All phones unlinked'); lkDraw(); }
  else if (a === 'ip') {
    const v = (($('#lkip') || {}).value || '').trim().replace(/^https?:\/\//, '');
    const m = v.match(/^([\d.]+)(?::(\d+))?$/);
    if (!m) { LK.pairErr = 'Type the address shown on the TV, like 192.168.1.20'; lkDraw(); return; }
    lkStartPair({ name: '', host: m[1], port: +(m[2] || 8790) });
  }
  else if (a === 'sync') lkSync(false);
  else if (a === 'unlink') lkUnlink();
  else if (a === 'send') { const v = ($('#lktxt') || {}).value || ''; lkSend({ type: 'text', text: v, submit: true }); }
  else if (a === 'mic') { LK.voiceForTv = true; startVoice(); }
  else if (a.startsWith('k:')) { const k = a.slice(2); lkSend({ type: 'key', k: k === 'hold' ? 'ok' : k, hold: k === 'hold' }, true); try { B.haptic && B.haptic(); } catch (e) {} }
}
function linkKey(k, e) {
  const a = document.activeElement;
  if (a && a.tagName === 'INPUT') { if (e.key === 'Escape') { e.preventDefault(); a.blur(); } return; }
  if (!k) return;
  e.preventDefault();
  if (k === 'back') closeLink();
}

/* ---------------------------------------------------------------- start-up */
if (lkOn()) body.classList.add('linked');
// a linked phone keeps the TV's address fresh (it may change) and syncs once at start
if (lkOn()) setTimeout(() => { lkSearch(); setTimeout(() => lkSync(true), 4000); }, 3000);


/* ---------------------------------------------------------------- recordings on the TV (from the phone) */

function lkRecItems(ch, pr) {
  if (IS_TV || !lkOn() || !ch) return [];
  const tv = lkTvName(), now = Date.now();
  if (pr && pr[1] <= now) return [];
  const base = { n: ch.n, u: ch.u, k: ch.k || '', l: ch.l || '', ua: ch.ua || '' };
  const send = async (cmd, done) => { const r = await lkSend(Object.assign({ type: 'rec' }, base, cmd)); if (r && !r.error) toast(done); else if (r && r.error) toast(r.error); };
  const items = [];
  if (pr) {
    items.push(['tv', `● Record “${pr[2] || ch.n}” on ${tv}`, () => send({ op: 'add', s: pr[0], e: pr[1], title: pr[2] || ch.n, desc: pr[3] || '' }, `${tv} will record “${pr[2] || ch.n}”`)]);
    if (pr[2]) {
      items.push(['tv', `● Record all new episodes on ${tv}`, () => send({ op: 'rule', title: pr[2], mode: 'new' }, `${tv} will record new episodes of “${pr[2]}”`)]);
      items.push(['tv', `● Record every airing on ${tv}`, () => send({ op: 'rule', title: pr[2], mode: 'all' }, `${tv} will record every airing of “${pr[2]}”`)]);
    }
  } else {
    items.push(['tv', `● Record the next hour on ${tv}`, () => send({ op: 'add', s: now, e: now + 3600000, title: ch.n }, `${tv} is recording ${ch.n}`)]);
  }
  items.push(['open', `Recordings on ${tv}`, () => lkRecList()]);
  return items;
}

function lkRecWhen(ms) {
  const d = new Date(ms), t = new Date(); const y = new Date(); y.setDate(t.getDate() - 1); const tm = new Date(); tm.setDate(t.getDate() + 1);
  const same = (a, b) => a.toDateString() === b.toDateString();
  const day = same(d, t) ? 'Today' : same(d, tm) ? 'Tomorrow' : same(d, y) ? 'Yesterday' : d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
  return day + ' ' + (typeof fmtTime === 'function' ? fmtTime(ms) : d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }));
}

async function lkRecList() {
  const tv = lkTvName();
  const r = await lkSend({ type: 'rec', op: 'list' });
  if (!r) return;
  if (r.error) { toast(r.error); return; }
  const recs = r.recs || [], rules = r.rules || [];
  const order = { recording: 0, done: 1, failed: 1, scheduled: 2 };
  recs.sort((a, b) => (order[a.status] - order[b.status]) || (a.status === 'scheduled' ? a.start - b.start : b.start - a.start));
  const act = async (cmd, msg) => { const x = await lkSend(Object.assign({ type: 'rec' }, cmd)); if (x && !x.error) { if (msg) toast(msg); } else if (x && x.error) toast(x.error); };
  const items = recs.map(x => {
    const tag = x.status === 'recording' ? '● REC  ' : x.status === 'scheduled' ? '◷ ' : x.status === 'failed' ? '⚠ ' : x.cloud ? '☁ ' : '';
    const label = `${tag}${x.title} · ${x.n} · ${lkRecWhen(x.start)}${x.secs ? ' · ' + Math.max(1, Math.round(x.secs / 60)) + ' min' : ''}`;
    return ['play', label, () => {
      const sub = [];
      if (x.status === 'done' || x.status === 'recording') sub.push(['play', `Play on ${tv}`, () => act({ op: 'play', id: x.id }, `Playing on ${tv}`)]);
      if (x.status === 'recording') sub.push(['hide', 'Stop recording', () => act({ op: 'cancel', id: x.id }, 'Recording stopped')]);
      if (x.status === 'scheduled') sub.push(['hide', 'Cancel this recording', () => act({ op: 'cancel', id: x.id }, 'Cancelled')]);
      if (x.status !== 'scheduled') sub.push(['hide', 'Delete', () => act({ op: 'delete', id: x.id }, 'Deleted')]);
      setTimeout(() => showMenu(sub, null, x.title), 80);
    }];
  });
  rules.forEach(x => items.push(['grid', `Series · ${x.title} · ${x.n} (${x.mode === 'new' ? 'new episodes' : 'every airing'})`, () => {
    setTimeout(() => showMenu([['hide', 'Stop this series', () => act({ op: 'unrule', id: x.id }, 'Series stopped')]], null, x.title), 80);
  }]));
  if (!items.length) items.push(['open', 'Nothing recorded or coming up yet', () => {}]);
  showMenu(items, null, `Recordings on ${tv} · ${r.storage || ''}`);
}
