/* Home is drawn by Android itself on TVs (NativeHome.java): photos, glass, tiles, drawer, Top Shelf,
   photo mode and the screensaver. This page then keeps the rest — the other tabs, Settings, Search,
   the phone link and the icon picker — and tells the native Home who's in charge:
     native  — Home is showing and has the remote (this page is see-through and idle)
     overlay — a panel of this page (Settings, Search…) is open over Home
     web     — another tab is showing */

const NH = { on: false, owner: '' };
try { NH.on = !!(B.nhAvailable && B.nhAvailable()); } catch (e) { NH.on = false; }   // (a phone upright only when Settings › Upright layout says so)

if (NH.on) {
  body.classList.add('nh-on');
  const NATIVE_MODES = ['grid', 'top', 'shelf', 'tshelf', 'move'];

  function nhSync() {
    if (!NH.on) return;
    const onHome = tab === 'home';
    const sheet = (NH.sheet && (mode === 'settings' || mode === 'edit'))           // Settings, Search and the
      || (NH.find === 'search' && mode === 'search') || (NH.find === 'pick' && mode === 'spick')    // team picker drawn by the app
      || (NH.find === 'icon' && mode === 'icpick')
      || (NH.dlg && mode === 'sheet');                                                            // and messages
    const st = !onHome ? 'web' : NATIVE_MODES.includes(mode) || sheet ? 'native' : 'overlay';
    const ticker = st === 'native' && body.classList.contains('has-ticker');
    body.classList.toggle('nhome', onHome && mode !== 'multi');     // (Multiview is still the page's)
    if (st !== NH.owner || ticker !== NH.ticker) {
      NH.owner = st; NH.ticker = ticker;
      try { B.nhState(st + (ticker ? '+t' : '')); } catch (e) {}
      if (st !== 'native' && window.IDLE) IDLE.last = Date.now();
      if (st === 'native' && mode !== 'grid' && !sheet) mode = 'grid';
    }
  }
  window.nhSync = nhSync;
  setInterval(nhSync, 100);
  nhSync();

  // messages for you while the page is hidden show on the native Home
  { const t0 = toast; toast = function (msg) { if (NH.owner === 'native') { try { B.nhToast(String(msg)); } catch (e) {} return; } return t0(msg); }; }   // (shown by the app, whether or not the ticker is up)

  // the page no longer loads or shows Home photos itself
  loadPhotos = function () {};
  showPhoto = function () {};
  { const ss = scheduleSlide; scheduleSlide = function () { clearTimeout(slideTimer); }; }
  window.nhPhotoStatus = s => { photoStatus = s; if (typeof refreshSettings === 'function') refreshSettings(); };
  /* The page's own Home (its app tiles, the photo it showed before the app took over) stays hidden
     behind the app's, but kept its pictures decoded — tens of MB of the TV's memory for nothing. They're
     let go (if the app's Home ever fails, the page reloads with everything). */
  const nhStripPics = () => {
    if (!NH.on) return;
    document.querySelectorAll('#content img, #drawer img, #bg img, #caption img').forEach(i => { if (i.getAttribute('src')) { i.removeAttribute('srcset'); i.removeAttribute('src'); } });
    document.querySelectorAll('.bgimg').forEach(e => { if (e.style.backgroundImage && e.style.backgroundImage !== 'none') e.style.backgroundImage = 'none'; });
    try { if (typeof BAKE !== 'undefined' && BAKE.imgs && BAKE.imgs.clear) BAKE.imgs.clear(); } catch (e) {}
  };
  { const r0 = render; render = function () { const x = r0.apply(this, arguments); nhStripPics(); return x; }; }
  setTimeout(nhStripPics, 2500); setTimeout(nhStripPics, 12000);
  setTimeout(() => { try { photoStatus = B.nhPhotoStatus() || photoStatus; } catch (e) {} }, 8000);

  // changes made in Settings (or the icon picker) reach the native Home
  { const save0 = save; save = function () { save0(); try { B.nhReload('settings'); } catch (e) {} }; }
  { const changed0 = changedPhotos; changedPhotos = function () { changed0(); try { B.nhReload('photos'); } catch (e) {} }; }
  { const w0 = writeJSON; writeJSON = function (k, v) { w0(k, v); if (k === 'order' || k === 'hidden') { try { B.nhReload('apps'); } catch (e) {} } else if (k === 'icons') { try { B.nhReload('icons'); } catch (e) {} } }; }
  // and changes the native Home made are picked up here (Settings shows hidden apps etc.)
  window.nhReloaded = () => { order = readJSON('order', []); hidden = readJSON('hidden', []); buildItems(); render(); };
  window.nhBroken = err => {
    NH.on = false; body.classList.remove('nh-on', 'nhome');
    setTimeout(() => location.reload(), 300);        // the page version of Home, with its photos, takes over
  };

  /* Movies and TV Shows are drawn by the app too: any way of getting to them from here (the tab bar
     on Sports or Live TV, a shortcut) shows Home's layer of the app with that tab */
  NH.vod = !!B.nhTab;
  try { NH.live = !!(B.nhLiveOk && B.nhLiveOk()); } catch (e) { NH.live = false; }
  if (NH.vod && typeof switchTab === 'function') {
    const sw0 = switchTab;
    switchTab = function (id) {
      if (id === 'movies' || id === 'series' || id === 'home' || (id === 'live' && NH.live) || (id === 'sports' && NH.sports)) {
        const args = Array.prototype.slice.call(arguments); args[0] = 'home';
        const keepTop = !!arguments[1];
        args[1] = false;
        const r = sw0.apply(this, args);
        try { if (keepTop && B.nhTabTop) B.nhTabTop(id); else B.nhTab(id); } catch (e) {}
        return r;
      }
      return sw0.apply(this, arguments);
    };
  }

  /* Live TV is drawn by the app too: watching a channel from search, a reminder or the phone link
     happens there, and closing Multiview (still the page's) goes back to it */
  if (NH.live) {
    if (typeof srWatch === 'function') { const w0 = srWatch; srWatch = function (ch) { if (ch && ch.u) { try { B.nhLivePlay(ch.u); return; } catch (e) {} } return w0.apply(this, arguments); }; }
    if (typeof mvClose === 'function') {
      const c0 = mvClose;
      mvClose = function (keep) {
        const keepCh = keep && MV.tiles[MV.f] ? MV.tiles[MV.f].ch : null;
        c0(false);
        if (tab === 'home') { mode = 'grid'; nhSync(); }
        try { if (keepCh) B.nhLivePlay(keepCh.u); else B.nhTab('live'); } catch (e) {}
      };
    }
  }

  /* Sports is drawn by the app too. Its data stays here (ESPN scores, finding each game on your
     channels, alerts, the team picker): this page works out the rows and hands them over. */
  NH.sports = !!B.nhSports && typeof spBuildRows === 'function';
  if (NH.sports) {
    NH.sp = false;
    const teamOut = (ev, t) => ({ name: t.name, full: t.full || t.name, rec: t.rec || '', score: t.score || '', logo: t.logo || '',
      color: t.color || '', winner: !!t.winner, fav: spIsFav(ev.lg, t.id) });
    const itemOut = (ev, rowId, c) => {
      if (ev.edit) return { edit: true, label: Object.values(SP.prefs.teams).some(l => l.length) ? 'Edit sports & teams' : 'Pick your teams', k: 'edit' };
      const post = ev.state === 'post', lg = spLeague(ev.lg) || {};
      const m = !post && (rowId === 'live' || rowId === 'mine' || c < 12) ? spMatch(ev) : null;
      const tv = m && m.length ? m[0].ch.n : ev.nets.length && !post ? ev.nets.slice(0, 3).join(' · ') : '';
      const o = { k: ev.id + ':' + ev.state + ':' + ev.teams.map(t => t.score).join('-') + ':' + spWhen(ev) + ':' + (m && m.length ? 1 : 0),
        lg: ev.lg, lgName: ev.lgName, icon: lg.icon || '', lgLogo: (SP.lgLogo || {})[ev.lg] || '', state: ev.state, when: spWhen(ev), detail: ev.detail || '', at: ev.at,
        card: !!ev.card, venue: ev.venue || '', tv, tvOn: !!(m && m.length), nets: ev.nets.slice(0, 4),
        bell: !post && !!(m && m.length) && remHas(m[0].ch.u, ev.at), teams: ev.teams.map(t => teamOut(ev, t)) };
      if (ev.card && ev.bouts && ev.bouts[0]) o.extra = 'Main event' + (ev.bouts[0].cls ? ' · ' + ev.bouts[0].cls : '') + (ev.bouts.length > 1 ? ' · ' + (ev.bouts.length - 1) + ' more fight' + (ev.bouts.length > 2 ? 's' : '') : '');
      return o;
    };
    window.nhSpPush = () => {
      if (!NH.sp) return;
      let rows;
      if (!SP.prefs.leagues.length) rows = [{ id: 'setup', title: '', items: [{ edit: true }] }];
      else rows = spBuildRows();
      NH.spRows = rows;
      const t = Math.max(0, ...SP.prefs.leagues.map(id => SP.t[id] || 0));
      const failed = SP.prefs.leagues.filter(id => SP.errs && SP.errs[id]).length;
      const live = SP.prefs.leagues.reduce((n, id) => n + (SP.ev[id] || []).filter(e => e.state === 'in').length, 0);
      const data = { setup: !SP.prefs.leagues.length, live,
        updated: t ? 'Updated ' + fmtTime(t) + (failed ? ' · ' + failed + " couldn't load" : '') : SP.prefs.leagues.some(id => SP.busy[id]) ? 'Loading…' : failed ? "Couldn't reach ESPN" : 'Loading…',
        rows: rows.map(r => ({ title: r.title, note: r.note || '', items: r.items.map((ev, c) => itemOut(ev, r.id, c)) })) };
      try { B.nhSports(JSON.stringify(data)); } catch (e) {}
    };
    const loop = () => {
      clearTimeout(NH.spT);
      if (!NH.sp) return;
      const live = SP.prefs.leagues.some(id => (SP.ev[id] || []).some(e => e.state === 'in'));
      NH.spT = setTimeout(() => { spLoadAll(false).then(() => { nhSpPush(); loop(); }); }, live ? 60000 : 5 * 60000);
    };
    window.nhSportsOn = on => {
      NH.sp = !!on;
      if (!on) { clearTimeout(NH.spT); return; }
      nhSpPush();
      spLoadAll(false).then(() => { nhSpPush(); loop(); });
    };
    { const b0 = spBuild; spBuild = function () { if (NH.sp) return nhSpPush(); return b0.apply(this, arguments); }; }
    /* (the app now works out Sports' rows itself: it's told when the team picker changed your sports or teams) */
    { const c0 = spPickClose; spPickClose = function () {
      c0.apply(this, arguments);
      if (B.nhSportsPrefs) { if (tab === 'home') { mode = 'grid'; nhSync(); } try { B.nhSportsPrefs(); } catch (e) {} return; }
      if (NH.sp) { if (tab === 'home') { mode = 'grid'; nhSync(); } nhSpPush(); spLoadAll(false).then(nhSpPush); }
    }; }
    /* OK on a game: the page works out what can be done (watch on which channel, remind me…) and the
       app shows it as its own menu */
    // a fantasy player's game (by ESPN's id): the same menu as the game's own card
    window.nhSpOpenGame = id => {
      let ev = null;
      for (const l of Object.values(SP.ev || {})) { ev = (l || []).find(e => String(e.id) === String(id)); if (ev) break; }
      if (!ev) { toast('Add the NFL in Sports & teams to watch games from here'); return; }
      nhMenuFrom(() => spOpen(ev, null));
    };
    window.nhSpOpen = (r, c) => {
      const row = NH.spRows && NH.spRows[r], ev = row && row.items[c];
      if (!ev) return;
      if (ev.edit) return spPickOpen();
      nhMenuFrom(() => spOpen(ev, null));
    };
  }
  /* Settings is drawn by the app too (a glass sheet on the right). The rows — what each setting is,
     its value and what changing it does — stay here; the app shows them and asks back. */
  NH.sheet = false;
  if (B.nhSheet && typeof openSettings === 'function') {
    const rowOut = r => {
      let v = ''; try { v = typeof r.value === 'function' ? r.value() : ''; } catch (e) {}
      if (r.head) return { head: true, label: r.label, value: '' };
      // (what the detail panel says about it: setinfo.js)
      let inf = {}; try { inf = typeof setInfoFor === 'function' ? setInfoFor(r, v == null ? '' : String(v)) : {}; } catch (e) {}
      return { label: r.label, value: v == null ? '' : String(v), cycle: !!r.cycle, chev: !!r.chevron, gap: !!r.gap,
        about: inf.about || '', opts: inf.opts || [], cur: inf.cur == null ? -1 : inf.cur, how: inf.how || '', pv: inf.pv || '',
        edit: r.edit ? { value: String(r.edit.get() || ''), wide: !!r.edit.wide, type: r.edit.type || '',
          ph: r.edit.ph || (r.id === 'posters' ? 'Paste your Poster URL (with {imdb_id})' : r.edit.wide ? 'Paste link (http://…)' : '') } : null };
    };
    window.nhSheetPush = () => {
      if (!NH.sheet) return;
      const f = document.querySelector('#settings .foot');
      const sub = SET.sub && typeof settingsSubName === 'function' ? settingsSubName() : '';
      const last = SET_SECTIONS.length - 1;
      const data = { title: sub || (SET.sec == null ? 'Settings' : SET_SECTIONS[SET.sec].name), back: sub ? SET_SECTIONS[SET.sec].name : SET.sec == null ? '' : 'Settings',
        foot: f ? f.textContent : '', sel: sSel, rows: sRows.map(rowOut),
        sec: SET.sec == null ? -1 : SET.sec, sub: SET.sub || '',
        about: SET.sec == null || sub ? '' : SET_SECTIONS[SET.sec].about || '',
        sections: SET_SECTIONS.map((x, i) => ({ name: x.name, about: i === last && typeof UPD !== 'undefined' && UPD.latest && UPD.latest.newer ? `Update ${UPD.latest.name} ready` : (typeof SET_BRIEF !== 'undefined' && SET_BRIEF[x.name]) || x.about })) };
      try { B.nhSheet(JSON.stringify(data)); } catch (e) {}
    };
    const os0 = openSettings;
    openSettings = function () {
      const r = os0.apply(this, arguments);
      if (NH.on && tab === 'home') { body.classList.remove('settings-open'); NH.sheet = true; nhSheetPush(); nhSync(); }
      return r;
    };
    const ds0 = drawSettings;
    drawSettings = function () { if (NH.sheet) return nhSheetPush(); return ds0.apply(this, arguments); };
    const cs0 = closeSettings;
    closeSettings = function () {
      const was = NH.sheet;
      NH.sheet = false;
      const r = cs0.apply(this, arguments);
      if (was) { try { B.nhSheetClose(); } catch (e) {} nhSync(); }
      return r;
    };
    window.nhSheetKey = (i, k) => { if (!NH.sheet || mode !== 'settings') return; sSel = Math.max(0, Math.min(i, sRows.length - 1)); settingsKey(k); };
    /* the full-screen Settings: the sections stay listed on the left, and moving through them shows each one's settings on the right */
    window.nhSheetSection = i => {
      if (!NH.sheet || i < 0 || i >= SET_SECTIONS.length) return;
      if (SET.sub === 'groups' && typeof lgDone === 'function') lgDone();
      SET.sub = null; SET.sec = i; sSel = 0;
      buildSettings(); drawSettings();
    };
    /* OK on a setting (TV): its choices, for the panel that pops out. Every setting with choices gets it, two
       choices included (Chad: none of them should change in place). How a choice is then set:
         steps  the choices were listed by stepping through them quietly: so many steps on from what's set
         goto   they can't be stepped through quietly (each step does something real), but setinfo.js names them:
                step on until the setting says the one that was chosen
         go     setinfo.js has its own way of setting it (Picture sharpness: one try, then "Keep this?")
       A setting whose choices aren't known either way does what OK always did. */
    window.nhSheetChoices = i => {
      if (!NH.sheet || mode !== 'settings') return;
      sSel = Math.max(0, Math.min(i, sRows.length - 1));
      const r = sRows[sSel];
      if (!r || !r.cycle) return;
      const val = String(r.value());
      let inf = {}; try { inf = setInfoFor(r, val); } catch (e) {}
      const canon = (inf.opts || []).map(x => x.l), note = l => ((inf.opts || []).find(x => x.l === l) || {}).n || '';
      const says = (v, l) => v === l || v.indexOf(l + ' ') === 0;
      let o = null, opts = null, cur = 0, how = 'steps';
      if (!inf.go) { try { o = settingsChoices(r); } catch (e) {} }
      if (o && o.length >= (r.edit ? 1 : 2)) {
        // (in the list's own order when it's known, not starting from whatever is set)
        // ("On · last 30 min" is its "On")
        const at = canon.map(l => o.findIndex(v => says(v, l)));
        if (canon.length === o.length && at.every(k => k >= 0) && new Set(at).size === o.length) { opts = canon.map((l, j) => ({ l, n: note(l), steps: at[j] })); cur = at.indexOf(0); }
        else { opts = o.map((l, k) => ({ l, n: '', steps: k })); cur = 0; }
      } else if (canon.length >= 2 && canon.some(l => says(val, l))) {
        how = inf.go ? 'go' : 'goto';
        opts = canon.map(l => ({ l, n: note(l), steps: 0 })); cur = canon.findIndex(l => says(val, l));
      }
      if (!opts) { if (r.edit) opts = []; else { settingsKey('ok'); return; } }       // (only typing: the screen starts it itself)
      if (r.edit) opts.push({ l: inf.typeLabel || 'Type your own…', n: '', steps: -2 });
      else if (r.ok && r.okLabel) opts.push({ l: r.okLabel, n: '', steps: -1 });
      NH.choice = { label: r.label, how, opts, cur };
      B.nhSheetChoices(JSON.stringify({ i: sSel, label: r.label, about: inf.about || '', opts, cur }));
    };
    window.nhSheetPick = (i, j) => {
      if (!NH.sheet) return;
      const r = sRows[i], c = NH.choice;
      if (!r || !c || c.label !== r.label || !c.opts[j]) return;
      sSel = i;
      const o = c.opts[j], says = (v, l) => v === l || v.indexOf(l + ' ') === 0;
      if (o.steps === -1) { if (r.ok) r.ok(); }
      else if (o.steps === -2 || j === c.cur) { /* typing is the screen's; the one that's set stays */ }
      else if (c.how === 'go') { try { setInfoFor(r, String(r.value())).go(j, c.cur); } catch (e) {} }
      else if (c.how === 'goto') {
        if (/\bB\.(?!ai)|icPack|renderScale|homeButton|hudToggle|location\.reload|refreshSettings/.test(String(r.cycle))) {
          // (each step is real: step on until it says the one chosen)
          for (let n = 0; n < c.opts.length + 1 && !says(String(r.value()), o.l); n++) r.cycle(1);
        } else {
          // (a list too long to go round: quietly on to the one chosen and a step back, then the last step for real)
          let k = 0;
          settingsQuiet(() => { while (k < 60 && !says(String(r.value()), o.l)) { r.cycle(1); k++; } if (k) r.cycle(-1); });
          if (k) r.cycle(1);
        }
      }
      else {
        if (o.steps > 1) settingsQuiet(() => { for (let n = 0; n < o.steps - 1; n++) r.cycle(1); });
        if (o.steps > 0) r.cycle(1);
      }
      NH.choice = null;
      refreshSettings();
    };
    window.nhSheetEdit = (i, v) => {
      if (!NH.sheet) return;
      const r = sRows[i];
      if (!r || !r.edit) return;
      sSel = i; r.edit.set(v); buildSettings(); drawSettings();
    };
  }

  /* Search and the sports & teams picker are drawn by the app too (a big glass pane with a box at the
     top and rows of results). Finding things stays here: the app shows the rows and asks back. */
  NH.find = false;
  if (B.nhFind && typeof openSearch === 'function') {
    const secOf = (rows, kind) => rows.findIndex(r => r.kind === kind);
    const searchOut = () => {
      const R = SR.res, secs = [];
      const add = (title, kind, items, map, note) => {
        const ri = secOf(SR.rows, kind);
        secs.push({ title, kind, ri, note: note || '', items: items && items.length && ri >= 0 ? items.map(map) : [] });
      };
      let big = '';
      if (!SR.q) {
        if (SR.recent.length) add('Recent searches', 'q', SR.recent, q => ({ t: 'chip', label: q }));
        else big = 'Search your apps, movies, TV shows and Live TV channels.';
      } else {
        const poster = m => ({ t: 'poster', label: m.name, sub: String(m.year || '').slice(0, 9), img: vPosterUrl(m) || '', img2: m.poster || '' });
        if (R.apps && R.apps.length) add('Apps', 'app', R.apps, a => ({ t: 'app', label: a.label, id: a.mock ? '' : a.id, tint: a.tint || '#2a2a2e' }));
        add('Movies', 'movie', R.movie, poster, R.movie === null ? 'Searching…' : '');
        add('TV Shows', 'series', R.series, poster, R.series === null ? 'Searching…' : '');
        // what's on now for every channel found, asked for in one go (not one question per channel)
        if (R.chans && R.chans.length) { try { lvRebase(); lvPrograms(R.chans.map(c => c.k)); } catch (e) {} }
        if (R.chans && R.chans.length) add('Live TV channels', 'chan', R.chans, ch => ({ t: 'chan', label: ch.n + (LV.favs.has(ch.u) ? ' ★' : ''), sub: srChNow(ch) || ch.g || '', img: ch.l || '' }));
        if (R.progs && R.progs.length) add('On Live TV', 'prog', R.progs, x => {
          const now = Date.now(), live = x.p[0] <= now && now < x.p[1];
          return { t: 'chan', label: x.p[2] || 'Untitled', sub: (live ? 'On now' : remWhen(x.p[0])) + ' · ' + x.ch.n, live, bell: remHas(x.ch.u, x.p[0]), img: x.ch.l || '' };
        });
        if (R.movie && R.series && !SR.rows.length) big = `Nothing found for “${SR.q}”.`;
      }
      return { kind: 'search', q: SR.q || $('#sq').value || '', ph: 'Apps, movies, TV shows, channels…', big, start: -1, sections: secs.filter(x => x.items.length || x.note) };
    };
    const pickOut = () => {
      const secs = [], q = PK.q.trim();
      PK.rows.forEach((row, ri) => {
        if (row.kind === 'league') secs.push({ title: 'Your sports', kind: 'league', ri, items: row.items.map(lg => ({ t: 'chip', label: lg.icon + '  ' + lg.name, on: SP.prefs.leagues.includes(lg.id) })) });
        else {
          const id = row.kind.slice(5), lg = spLeague(id);
          secs.push({ title: lg.name + ' teams', kind: row.kind, ri, items: row.items.map(t => ({ t: 'team', label: lg.college ? t.full : t.name, img: t.logo || '', on: spIsFav(id, t.id) })) });
        }
      });
      // leagues without a row of teams (loading, fight cards, colleges before typing)
      for (const id of SP.prefs.leagues) {
        if (PK.rows.some(r => r.kind === 'team:' + id)) continue;
        const lg = spLeague(id);
        const note = lg.cards ? `Following every ${lg.name} fight card` : !SP.teams[id] ? 'Loading teams…' : lg.college && !q ? 'Type a school in the box above to find it' : q ? 'No matching teams' : '';
        if (note) secs.push({ title: lg.name, kind: 'note:' + id, ri: -1, note, items: [] });
      }
      return { kind: 'pick', q: PK.q, ph: 'Find a team', big: SP.prefs.leagues.length ? '' : 'Choose one or more sports above, then pick your teams.', start: 0, sections: secs };
    };
    NH.findPush = () => {
      if (!NH.find) return;
      let d = null;
      try { d = NH.find === 'search' ? searchOut() : pickOut(); } catch (e) { return; }
      try { B.nhFind(JSON.stringify(d)); } catch (e) {}
    };
    const os0 = openSearch;
    openSearch = function () {
      if (!(NH.on && tab === 'home')) return os0.apply(this, arguments);
      $('#sq').disabled = true;                         // (the page's own box must not take the keyboard)
      NH.srNative = true;
      const r = os0.apply(this, arguments);
      body.classList.remove('search-open');
      NH.find = 'search'; NH.findPush(); nhSync();
      return r;
    };
    const cs0 = closeSearch;
    closeSearch = function () {
      const was = NH.find === 'search';
      if (was) NH.find = false;
      const r = cs0.apply(this, arguments);
      if (was) { NH.srNative = false; $('#sq').disabled = false; try { B.nhFindClose(); } catch (e) {} nhSync(); }
      return r;
    };
    const sd0 = srDraw;
    srDraw = function () { const r = sd0.apply(this, arguments); if (NH.find === 'search') NH.findPush(); return r; };
    /* Search is worked out by the app now: opening it, the phone's keyboard and the mic's words go there */
    if (B.nhSearchOpen) {
      NH.natSearch = true;
      const here = () => NH.on && tab === 'home';
      const os1 = openSearch;
      openSearch = function () { if (!here()) return os1.apply(this, arguments); try { B.nhSearchOpen(''); } catch (e) {} };
      const rs1 = runSearch;
      runSearch = function () { if (!here() || NH.find === 'search') return rs1.apply(this, arguments); try { B.nhSearchQuery($('#sq').value || '', false); } catch (e) {} };
      const tv1 = window.tsVoice;
      window.tsVoice = (text, err) => {
        if (err || !text || !here() || (typeof LK !== 'undefined' && LK.voiceForTv)) return tv1(text, err);
        try { B.nhSearchQuery(text, true); } catch (e) {}
      };
      window.tsVoiceSearch = () => { if (!here()) { if (mode !== 'search') openSearch(); setTimeout(startVoice, 150); return; } try { B.nhSearchVoice(); } catch (e) {} };
    }
    if (typeof spPickOpen === 'function') {
      const po0 = spPickOpen;
      spPickOpen = function () {
        if (!(NH.on && tab === 'home')) return po0.apply(this, arguments);
        $('#spq').disabled = true;
        NH.find = 'pick';
        const r = po0.apply(this, arguments);
        body.classList.remove('spick-open');
        NH.findPush(); nhSync();
        return r;
      };
      const pc0 = spPickClose;
      spPickClose = function () {
        const was = NH.find === 'pick';
        if (was) NH.find = false;
        const r = pc0.apply(this, arguments);
        if (was) { $('#spq').disabled = false; try { B.nhFindClose(); } catch (e) {} nhSync(); }
        return r;
      };
      const pd0 = spPickDraw;
      spPickDraw = function () { const r = pd0.apply(this, arguments); if (NH.find === 'pick') NH.findPush(); return r; };
    }
    window.nhFindOk = (ri, c) => {
      if (NH.find === 'icon') { IC.sel = c; icChoose(c); return; }
      if (NH.find === 'search') { SR.r = ri; SR.c[ri] = c; srActivate(ri, c); }
      else if (NH.find === 'pick') { PK.r = ri; PK.c[ri] = c; spPickToggle(ri, c); }
    };
    window.nhFindHold = (ri, c) => {
      if (NH.find === 'search') { SR.r = ri; SR.c[ri] = c; srHold(ri, c); }
      else if (NH.find === 'pick') nhFindOk(ri, c);
    };
    window.nhFindQ = (v, submit) => {
      if (NH.find === 'search') { $('#sq').value = v; runSearch(); if (submit) srRemember(v); }
      else if (NH.find === 'pick') { $('#spq').value = v; PK.q = v; PK.c = {}; spPickDraw(); }
    };
    window.nhFindVoice = () => { if (NH.find === 'search') startVoice(); };
    window.nhFindClose = () => {
      if (NH.find === 'search') closeSearch();
      else if (NH.find === 'pick') spPickClose();
      else if (NH.find === 'icon') {
        if (IC.crop && !IC.send && IC.opts.length) { IC.crop = null; IC.sel = IC.opts.findIndex(o => o.k === 'photo'); icDraw(); return; }
        icClose();
      }
    };
    /* the icon picker: every look for the app, painted the way Home paints tiles */
    if (typeof icOpen === 'function') {
      const lookOf = o => o.k === 'orig' ? null : o.k === 'color' ? { k: 'color', c: o.c } : o.k === 'pack' ? { k: 'pack', p: o.p }
        : o.k === 'image' ? { k: 'image', f: o.f } : o.k === 'crop' ? { k: 'image', f: o.src } : { k: o.k };
      const iconOut = () => {
        const a = IC.a, opts = IC.crop || IC.opts;
        const app = { id: a.id, label: a.label, tint: a.tint || '#2a2a2e', banner: !!a.banner };
        return { kind: 'icon', nobox: true, start: 0, col: IC.sel, big: '', q: '', ph: '',
          sections: [{ title: IC.crop ? `Fit your picture · ${a.label}` : `Icon for ${a.label}`, kind: IC.crop ? 'crop' : 'icon', ri: 0,
            items: opts.map(o => ({ t: 'look', label: o.name, photo: o.k === 'photo', app, look: lookOf(o) })) }] };
      };
      const io0 = icOpen;
      icOpen = function (a) {
        const nativeNow = NH.on && tab === 'home' && NH.owner === 'native';
        const r = io0.apply(this, arguments);
        if (nativeNow && mode === 'icpick') { body.classList.remove('icpick-open'); NH.find = 'icon'; try { B.nhFind(JSON.stringify(iconOut())); } catch (e) {} nhSync(); }
        return r;
      };
      const id0 = icDraw;
      icDraw = function () {
        const r = id0.apply(this, arguments);
        if (NH.find === 'icon') { body.classList.remove('icpick-open'); try { B.nhFind(JSON.stringify(iconOut())); } catch (e) {} }
        return r;
      };
      const ic0 = icClose;
      icClose = function () {
        const was = NH.find === 'icon';
        if (was) NH.find = false;
        const r = ic0.apply(this, arguments);
        if (was) { try { B.nhFindClose(); } catch (e) {} nhSync(); }
        return r;
      };
    }
  }

  /* messages with buttons (an update is ready, speed test results…) are the app's own while it has
     the screen */
  NH.dlg = false;
  if (B.nhDialog && typeof sheetOpen === 'function') {
    const so0 = sheetOpen;
    sheetOpen = function (title, html, buttons) {
      const nativeNow = NH.on && tab === 'home' && (NH.owner === 'native' || NH.sheet || NH.find);
      const r = so0.apply(this, arguments);
      if (nativeNow) {
        body.classList.remove('sheet-open');
        NH.dlg = true;
        // a table in the message is handed over as rows (the app draws it as a table)
        let text = String(html || ''), table = null;
        try {
          const doc = new DOMParser().parseFromString('<div>' + text + '</div>', 'text/html');
          const t = doc.querySelector('table');
          if (t) {
            const trs = Array.from(t.querySelectorAll('tr'));
            const cells = tr => Array.from(tr.children).map(td => td.textContent.trim());
            const head = trs.length && trs[0].querySelector('th') ? cells(trs.shift()) : null;
            table = { head, rows: trs.map(tr => ({ cells: cells(tr), tone: tr.className || '' })) };
            t.remove();
            text = doc.body.firstChild.innerHTML;
          }
        } catch (e) {}
        try { B.nhDialog(JSON.stringify({ title: String(title || ''), html: text, table, buttons: (buttons || []).map(b => String(b[0])) })); } catch (e) {}
        nhSync();
      }
      return r;
    };
    const sc0 = sheetClose;
    sheetClose = function () {
      const was = NH.dlg;
      NH.dlg = false;
      const r = sc0.apply(this, arguments);
      if (was) { try { B.nhDialogClose(); } catch (e) {} nhSync(); }
      return r;
    };
    window.nhDlgPick = i => {
      if (!NH.dlg) return;
      const b = i >= 0 ? SHEET.btns[i] : null;
      sheetClose();
      if (b && b[1]) b[1]();
    };
  }

  /* the TV side of the phone link is just information (and Unlink): the app's own message */
  if (B.nhDialog && typeof openLink === 'function' && typeof IS_TV !== 'undefined' && IS_TV) {
    const ol0 = openLink;
    openLink = function () {
      if (!(NH.on && tab === 'home' && (NH.owner === 'native' || NH.sheet))) return ol0.apply(this, arguments);
      if (mode === 'settings') closeSettings();
      const me = lkInfo(), paired = me.paired || [];
      const html = `<p><b>${esc(me.name || 'This TV')}</b> · ${me.ip ? 'on your Wi-Fi at ' + esc(me.ip) + (me.port && me.port !== 8790 ? ':' + me.port : '') : 'not on Wi-Fi'}</p>` +
        `<p>On your phone, open TopShelf → Settings → <b>Phone &amp; TV link</b>, pick this TV and type the code that appears here.</p>` +
        `<p>${paired.length ? 'Linked phones: ' + esc(paired.join(', ')) : 'No phones linked yet.'}</p>`;
      const btns = [['Done', () => {}]];
      if (paired.length) btns.push(['Unlink all phones', () => { try { B.linkForgetAll(); } catch (e) {} toast('All phones unlinked'); }]);
      sheetOpen('Phone & TV link', html, btns);
    };
  }

  /* a menu the page would have shown, shown by the app instead */
  window.nhMenuFrom = fn => {
    const sm = showMenu; let cap = null;
    showMenu = (entries, anchor, title) => { cap = { entries, title }; };
    try { fn(); } finally { showMenu = sm; }
    if (!cap || !cap.entries.length) return;
    NH.menuActs = cap.entries.map(e => e[2]);
    try { B.nhMenu(JSON.stringify({ title: cap.title || '', labels: cap.entries.map(e => e[1]) })); } catch (e) {}
  };
  /* every menu of this page shown while the app has the screen (a game, a setting's choices…) is the
     app's own menu */
  if (B.nhMenu && typeof showMenu === 'function') {
    const sm0 = showMenu;
    showMenu = function (entries, anchor, title) {
      if (NH.on && NH.owner === 'native' && entries && entries.length) {
        NH.menuActs = entries.map(e => e[2]);
        try { B.nhMenu(JSON.stringify({ title: title ? String(title) : '', labels: entries.map(e => String(e[1])) })); return; } catch (e) {}
      }
      return sm0.apply(this, arguments);
    };
  }
  window.nhMenuPick = i => { const a = NH.menuActs; NH.menuActs = null; if (a && a[i]) setTimeout(a[i], 0); };

  // the test's label (top left) is shown by the app while Home is native
  (function () {
    const hud = document.getElementById('perfhud');
    if (!hud || !B.nhHud) return;
    const send = () => { try { B.nhHud(hud.classList.contains('on') ? (hud.textContent || '').trim() : ''); } catch (e) {} };
    new MutationObserver(send).observe(hud, { attributes: true, attributeFilter: ['class'], childList: true, characterData: true, subtree: true });
  })();

  // the weather in the top bar
  (function () {
    const wx = document.getElementById('wx');
    if (!wx) return;
    const send = () => { try { B.nhWeather((wx.textContent || '').trim()); } catch (e) {} };
    new MutationObserver(send).observe(wx, { childList: true, characterData: true, subtree: true });
    setTimeout(send, 3000);
  })();

  // Top Shelf: the page still fetches what's popular (and My List); the native Home draws it
  function nhShelfPush() {
    if (typeof TS === 'undefined' || !TS.items) return;
    const items = TS.items.map(m => ({ name: m.name || '', img: m.background || m.poster || '', logo: m.logo || '', mine: typeof vInList === 'function' && !!vInList(m) }));
    const head = (typeof V !== 'undefined' && V.mine && V.mine.length) ? 'My List  ·  Popular now' : 'Popular now';
    try { B.nhShelf(JSON.stringify({ head, items })); } catch (e) {}
  }
  if (typeof tshelfDraw === 'function') {
    const d0 = tshelfDraw;
    tshelfDraw = function () { d0(); nhShelfPush(); };
    const load = () => { if (typeof tshelfLoad === 'function' && tshelfSetting() !== 'off') tshelfLoad(true); };
    setTimeout(load, 4000);
    setInterval(load, 3 * 3600000);
  }

  // the page's screensaver stays out of the way while the native Home runs its own
  if (typeof startSaver === 'function') { const s0 = startSaver; startSaver = function () { if (NH.owner === 'native' || NH.owner === 'overlay') return; s0(); }; }

  /* The diagnostic's Home test runs inside the app (the page sleeps while Home shows, so it can't time
     key presses there): it measures still, moving along the dock, the drawer opening and closing and
     moving around in it, and then the drawer again with one piece of the look switched off at a time,
     to show what each piece costs. */
  NH.cbs = {};
  window.nhScriptDone = (id, r) => { const f = NH.cbs[id]; if (f) { delete NH.cbs[id]; f(r); } };
  function nhScript(steps) {
    return new Promise(res => {
      const id = 's' + Date.now();
      NH.cbs[id] = res;
      try { B.nhScript(steps.join(';'), id); } catch (e) { delete NH.cbs[id]; res([]); return; }
      setTimeout(() => { if (NH.cbs[id]) { delete NH.cbs[id]; res([]); } }, 480000);
    });
  }
  function nhHomeSteps(shots) {
    const S = [], k = c => S.push('k' + c), w = ms => S.push('w' + ms);
    const keys = (codes, gap) => codes.forEach(c => { k(c); w(gap); });
    const measure = (label, fn) => { S.push('h' + label); S.push('m+'); fn(); S.push('m-' + label); };
    const R = 22, L = 21, U = 19, D = 20, BACK = 4;
    const toStart = () => { k(BACK); w(300); k(BACK); w(300); keys([L, L, L, L, L, L], 120); w(1500); };
    const dockNav = [R, R, R, L, L, L, R, L];
    const drawerNav = [R, R, L, D, R, U, L, L];
    toStart();
    measure('Home · still', () => w(3000));
    measure('Home · moving', () => { keys(dockNav, 380); w(400); });
    w(800);
    measure('Home · top bar', () => { keys([U, R, R, R, L, L, L, D], 380); w(400); });
    // what the whole screen costs to draw, and how much of that is each layer
    toStart();
    for (const [flags, name] of [
      ['', 'everything'], ['nophoto', 'no photo'], ['nolens', 'no liquid lens'], ['noglassall', 'no glass'],
      ['noscrim', 'no shade'], ['notiles', 'no dock'], ['nobars', 'no top bars'],
      ['nophoto,noglassall,noscrim,notiles,nobars', 'nothing (empty screen)']]) {
      S.push('tredraw' + (flags ? ',' + flags : '')); w(700);
      measure('Home · full redraw · ' + name + ' (test)', () => w(2000));
      S.push('t'); w(300);
    }
    toStart();
    measure('Home · drawer · open and close', () => { keys([D, U, D, U], 1000); w(300); });
    w(800);
    k(D); w(1500);
    measure('Home · drawer · moving', () => { keys(drawerNav, 380); w(400); });
    w(800); S.push('tredraw'); w(700);
    measure('Home · drawer · full redraw (test)', () => w(2000));
    S.push('t'); w(300);
    for (const [flag, name] of [['noshadow', 'no tile shadows'], ['notilt', 'no tilt'], ['noglass', 'no photo behind']]) {
      toStart(); S.push('t' + flag); w(600);
      measure('Home · drawer · open and close · ' + name + ' (test)', () => { keys([D, U, D, U], 1000); w(300); });
      w(600); k(D); w(1500);
      measure('Home · drawer · moving · ' + name + ' (test)', () => { keys(drawerNav, 380); w(400); });
      S.push('t');
    }
    // Movies & TV Shows (drawn by the app)
    toStart();
    measure('Switch to Movies', () => { S.push('vmovies'); w(3000); });
    measure('Movies · moving', () => { keys([D, R, R, R, L, D, R, U], 380); w(400); });
    measure('Movies · moving again (rows loaded)', () => { keys([R, R, L, D, R, U, L, R], 380); w(400); });
    measure('Movies · resting after a move', () => { k(R); w(3200); });
    if (shots) { w(1500); S.push('smovies'); w(9000); S.push('smovies-trailer'); w(900); }
    measure('Switch to TV Shows', () => { S.push('vseries'); w(3000); });
    measure('TV Shows · moving', () => { keys([D, R, R, L, D, R, U, L], 380); w(400); });
    if (shots) { w(2500); S.push('sseries'); w(900); }
    S.push('vhome'); w(1200);
    // Settings, Search and the team picker (drawn by the app)
    measure('Settings · open', () => { S.push('jopenSettings()'); w(1200); });
    measure('Settings · moving', () => { keys([D, D, D, D, U, U, D, D], 300); w(400); });
    measure('Settings · a section', () => { k(23); w(900); keys([D, D, D, U, D], 300); w(400); k(BACK); w(700); });
    if (shots) { S.push('ssettings'); w(900); }
    k(BACK); w(900);
    measure('Search · open and results', () => { S.push('jopenSearch()'); w(1200); S.push("jB.nhSearchQuery('the',true)"); w(2500); });
    k(BACK); w(300);
    measure('Search · moving', () => { keys([D, R, R, R, L, D, R, R, U, L], 320); w(400); });
    if (shots) { S.push('ssearch'); w(900); }
    k(BACK); w(400); k(BACK); w(900);
    S.push('vhome'); w(1000);
    // Sports (drawn by the app)
    if (NH.sports) {
      measure('Switch to Sports', () => { S.push('vsports'); w(3500); });
      measure('Sports · moving', () => { keys([R, R, R, L, D, R, R, U, L, D], 380); w(400); });
      measure('Sports · resting after a move', () => { k(R); w(2000); });
      if (shots) { w(1500); S.push('ssports'); w(900); }
      k(BACK); w(400);
      S.push('vhome'); w(1200);
    }
    // Live TV (drawn by the app)
    if (NH.live) {
      measure('Switch to Live TV', () => { S.push('vlive'); w(3000); });
      measure('Live TV · moving', () => { keys([D, D, D, U, R, R, L, L], 380); w(400); });
      if (shots) { S.push('slive-guide'); w(900); }
      measure('Live TV · categories', () => { k(L); w(700); keys([D, D, D, D, U, D, D, U, U, U], 300); w(500); });
      if (shots) { S.push('slive-cats'); w(900); }
      k(R); w(900);
      k(23); w(1500);                                           // OK: the channel plays in the preview
      measure('Live TV · preview playing, still', () => { w(4500); });
      if (shots) { S.push('slive'); w(900); }
      measure('Live TV · moving, preview playing', () => { keys([D, U, D, U, R, L], 450); w(400); });
      k(23); w(800);                                            // OK on the previewing channel: full screen
      measure('Live TV · full screen, changing channel', () => { w(2500); keys([D, U], 2500); w(500); });
      if (shots) { S.push('slive-full'); w(900); }
      S.push('p');
      k(BACK); w(800);
      S.push('vhome'); w(1200);
    }
    /* Memory over time: the same trip through every tab, three times. If graphics memory keeps
       climbing from one round to the next something isn't being let go; if it levels off, it's caches. */
    for (let n = 1; n <= 3; n++) {
      measure('Memory · round ' + n + ' through every tab', () => {
        S.push('vmovies'); w(2600); keys([D, R, R, U], 350); w(600);
        S.push('vseries'); w(2600); keys([R, R, L], 350); w(600);
        if (NH.sports) { S.push('vsports'); w(2600); }
        if (NH.live) { S.push('vlive'); w(2600); }
        S.push('vhome'); w(5500);
      });
    }
    if (shots) {
      toStart(); S.push('shome'); w(900);
      k(R); w(1800); S.push('shome-2nd-app'); w(900);
      k(D); w(1800); S.push('sdrawer'); w(900);
      k(U); w(1500); k(U); w(1500); S.push('sup-from-dock'); w(900);
      k(U); w(1200); S.push('stop-bar'); w(900);
      keys([R, R, R, R, R], 250); w(1000); S.push('stop-search'); w(900);
      k(R); k(R); w(1000); S.push('stop-settings'); w(900);
    }
    toStart();
    return S;
  }
  if (typeof diagSection === 'function') {
    const ds0 = diagSection;
    diagSection = async function (out, label, prep, keys, gap = 380, ms = 3200) {
      if (NH.vod && /^(Movies|Switch to Movies)/.test(label)) return;   // measured in the app's own run below
      if (NH.live && /^(Live TV|Switch to Live TV)/.test(label)) return;
      if (!/^Home/.test(label)) return ds0.apply(this, arguments);
      if (label !== 'Home · still') return;                  // the rest of Home is measured in the one run below
      try { if (prep) await prep(); } catch (e) {}
      await perfWait(800);
      const shots = !!(B.nhShot && typeof updReady === 'function' && updReady());
      const r = await nhScript(nhHomeSteps(shots));
      if (!r.length) out.push({ label: 'Home (native)', error: 'the Home test did not report back' });
      r.forEach(x => out.push(x));
    };
  }
}

/* if the native Home failed last time, send what happened (through your GitHub repository) */
setTimeout(() => {
  try {
    const info = JSON.parse(B.nhInfo ? B.nhInfo() : '{}');
    if (info.err && typeof updReady === 'function' && updReady()) {
      const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
      B.updUpload(`reports/${stamp}-${(B.getVersion && B.getVersion()) || 'dev'}-nativehome-error.txt`, info.err, 'Native Home error');
      B.nhClearErr();
    }
  } catch (e) {}
}, 30000);
