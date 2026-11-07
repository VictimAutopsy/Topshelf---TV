'use strict';
/* =====================================================================
   Sports tab: your sports and teams, their schedules and live scores (from ESPN's public
   scoreboards), and which of your Live TV channels is showing each game — found from the
   TV guide, from event channels named after the teams, and from the game's TV networks.
   Shares globals with index.html, live.js, search.js, guide.js.
   ===================================================================== */

const ESPN = 'https://site.api.espn.com/apis/site/v2/sports';
const SP_LEAGUES = [
  { id: 'nfl', sport: 'football', league: 'nfl', name: 'NFL', icon: '🏈', days: [-1, 7] },
  { id: 'nba', sport: 'basketball', league: 'nba', name: 'NBA', icon: '🏀', days: [-1, 4] },
  { id: 'mlb', sport: 'baseball', league: 'mlb', name: 'MLB', icon: '⚾', days: [-1, 4] },
  { id: 'nhl', sport: 'hockey', league: 'nhl', name: 'NHL', icon: '🏒', days: [-1, 4] },
  { id: 'wnba', sport: 'basketball', league: 'wnba', name: 'WNBA', icon: '🏀', days: [-1, 4] },
  { id: 'cfb', sport: 'football', league: 'college-football', name: 'College Football', icon: '🏈', days: [-1, 7], q: 'groups=80&limit=400', college: true },
  { id: 'cbb', sport: 'basketball', league: 'mens-college-basketball', name: "Men's College Hoops", icon: '🏀', days: [-1, 3], q: 'groups=50&limit=400', college: true },
  { id: 'mls', sport: 'soccer', league: 'usa.1', name: 'MLS', icon: '⚽', days: [-1, 7] },
  { id: 'epl', sport: 'soccer', league: 'eng.1', name: 'Premier League', icon: '⚽', days: [-1, 7] },
  { id: 'ucl', sport: 'soccer', league: 'uefa.champions', name: 'Champions League', icon: '⚽', days: [-1, 7] },
  { id: 'laliga', sport: 'soccer', league: 'esp.1', name: 'La Liga', icon: '⚽', days: [-1, 7] },
  { id: 'ufc', sport: 'mma', league: 'ufc', name: 'UFC', icon: '🥊', days: [-2, 21], cards: true }
];
const spLeague = id => SP_LEAGUES.find(l => l.id === id);

const SP = {
  prefs: Object.assign({ leagues: [], teams: {} }, readJSON('sports', {})),   // teams: {league: [{id,name,full,logo}]}
  ev: {}, t: {}, busy: {}, teams: {}, match: {}, rows: [], r: 0, cols: {}, timer: 0,
  okDown: false, okTimer: 0, okLong: false, chanKey: '', netIdx: null, byKey: null
};
function spSave() { writeJSON('sports', SP.prefs); }
function spIsFav(lg, id) { return (SP.prefs.teams[lg] || []).some(t => String(t.id) === String(id)); }

/* ---------------------------------------------------------------- data */

function spYmd(d) { return d.getFullYear() + String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0'); }
function spDayOffset(n) { const d = new Date(); d.setHours(12, 0, 0, 0); d.setDate(d.getDate() + n); return d; }

function spEvent(lg, e) {
  const c = (e.competitions && e.competitions[0]) || {};
  const st = ((c.status || e.status || {}).type) || {};
  const teams = (c.competitors || []).map(x => {
    const t = x.team || {};
    const score = x.score == null ? '' : typeof x.score === 'object' ? (x.score.displayValue || '') : String(x.score);
    const rec = x.records && x.records[0] ? x.records[0].summary : x.record && x.record[0] ? x.record[0].displayValue : '';
    return { id: String(t.id || x.id), home: x.homeAway === 'home', name: t.shortDisplayName || t.name || t.displayName || '?',
             color: /^[0-9a-f]{6}$/i.test(t.color || '') ? '#' + t.color : '', alt: /^[0-9a-f]{6}$/i.test(t.alternateColor || '') ? '#' + t.alternateColor : '',
             full: t.displayName || '', nick: t.name || '', loc: t.location || '', abbr: t.abbreviation || '',
             logo: t.logo || (t.logos && t.logos[0] && t.logos[0].href) || '', score, rec: rec || '', winner: !!x.winner };
  }).sort((a, b) => a.home - b.home);                      // away team first, like a TV listing
  const nets = [];
  const addNet = n => { n = String(n || '').trim(); if (n && !nets.includes(n)) nets.push(n); };
  (c.broadcasts || []).forEach(b => { (b.names || []).forEach(addNet); if (b.media) addNet(b.media.shortName); });
  (c.geoBroadcasts || []).forEach(b => { if (b.media) addNet(b.media.shortName); });
  if (!nets.length && c.broadcast) addNet(c.broadcast);
  const sit = c.situation || {};
  return { id: String(e.id), lg: lg.id, lgName: lg.name, at: Date.parse(e.date), name: e.name || '', short: e.shortName || '',
           state: st.state || 'pre', detail: st.shortDetail || '', done: !!st.completed, teams, nets,
           venue: (c.venue && c.venue.fullName) || '',
           red: !!sit.isRedZone, poss: sit.possession ? String(sit.possession) : '', dd: sit.shortDownDistanceText || sit.downDistanceText || '' };
}

/* ESPN lets browsers call it directly; if that's refused, go through the app's proxy with a normal
   browser identity. Errors carry ESPN's own message so problems are visible in the tab. */
async function spGet(url) {
  let err = null;
  try {
    const r = await fetch(url, { cache: 'no-store', credentials: 'omit' });
    if (r.ok) return await r.json();
    err = new Error(`HTTP ${r.status}${await spErrText(r)}`);
  } catch (e) { err = e; }
  try {
    const ua = navigator.userAgent.replace(/\s*TopShelf\/\S+/, '');
    const r = await fetch(proxify(url, ua));
    if (r.ok) return await r.json();
    throw new Error(`HTTP ${r.status}${await spErrText(r)}`);
  } catch (e) { throw (e && /^HTTP/.test(e.message || '')) ? e : (err || e); }
}
async function spErrText(r) {
  try { const t = (await r.text()).replace(/\s+/g, ' ').trim(); const m = t.match(/"(?:message|detail|error)"\s*:\s*"([^"]+)"/i); return t ? ': ' + (m ? m[1] : t).slice(0, 70) : ''; } catch (e) { return ''; }
}
function spScoreUrl(lg, ymd) {
  const qs = [ymd ? 'dates=' + ymd : '', lg.q || ''].filter(Boolean).join('&');
  return `${ESPN}/${lg.sport}/${lg.league}/scoreboard${qs ? '?' + qs : ''}`;
}

/* One request per day (date ranges aren't accepted by every league). While games are live only
   yesterday and today are re-fetched; the rest of the week every 15 minutes. */
/* each league's own logo, from ESPN's answer (UFC, college football… have no fixed address we know) */
SP.lgLogo = readJSON('spLgLogo', {});
function spNoteLogo(id, j) {
  try {
    const ls = ((((j || {}).leagues || [])[0] || {}).logos || []).filter(l => l && l.href);
    if (!ls.length) return;
    const pick = ls.find(l => (l.rel || []).includes('default') && !(l.rel || []).includes('dark')) || ls.find(l => !(l.rel || []).includes('dark')) || ls[0];
    if (pick.href !== SP.lgLogo[id]) { SP.lgLogo[id] = pick.href; writeJSON('spLgLogo', SP.lgLogo); }
  } catch (e) {}
}
async function spLoadLeague(id, force) {
  const lg = spLeague(id);
  if (!lg || SP.busy[id]) return;
  const have = SP.ev[id] && SP.ev[id].length;
  const live = (SP.ev[id] || []).some(e => e.state === 'in');
  const age = Date.now() - (SP.t[id] || 0), fullAge = Date.now() - (SP.tFull ? SP.tFull[id] || 0 : 0);
  if (!force && have && age < (live ? 60000 : 10 * 60000)) return;
  const partial = !force && have && fullAge < 15 * 60000;
  SP.busy[id] = true;
  if (lg.cards) { await spLoadCards(lg); SP.busy[id] = false; return; }
  const days = [];
  for (let n = partial ? -1 : lg.days[0]; n <= (partial ? 0 : lg.days[1]); n++) days.push(n);
  const errs = [];
  const got = await Promise.all(days.map(n => spGet(spScoreUrl(lg, spYmd(spDayOffset(n)))).then(j => { spNoteLogo(id, j); return j.events || []; }).catch(e => { errs.push(e.message || 'error'); return null; })));
  let raw = got.filter(Boolean).flat();
  if (got.every(x => x === null)) {
    try { raw = (await spGet(spScoreUrl(lg, ''))).events || []; errs.length = 0; }    // fall back to ESPN's default "today"
    catch (e) { errs.push(e.message || 'error'); raw = null; }
  }
  if (raw) {
    const fresh = raw.map(e => spEvent(lg, e)).filter(e => e.teams.length === 2 && !isNaN(e.at));
    const byId = new Map((partial ? SP.ev[id] || [] : []).map(e => [e.id, e]));
    for (const e of fresh) byId.set(e.id, e);
    SP.ev[id] = [...byId.values()].sort((a, b) => a.at - b.at);
    SP.t[id] = Date.now();
    if (!partial) { SP.tFull = SP.tFull || {}; SP.tFull[id] = Date.now(); }
    SP.errs = SP.errs || {}; SP.errs[id] = '';
  } else {
    SP.errs = SP.errs || {}; SP.errs[id] = errs[0] || 'offline';
    if (!SP.ev[id]) SP.ev[id] = [];
  }
  SP.busy[id] = false;
}

/* UFC and other fight leagues: one "card" per event (prelims → main event). */
function spEtYmd(ms) {
  try { return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(ms)).replace(/-/g, ''); }
  catch (e) { return spYmd(new Date(ms)); }
}
async function spLoadCards(lg) {
  const id = lg.id, now = Date.now();
  try {
    const base = await spGet(spScoreUrl(lg, ''));
    spNoteLogo(id, base);
    const cal = ((((base.leagues || [])[0] || {}).calendar) || []).filter(c => c && typeof c === 'object' && c.startDate)
      .map(c => ({ at: Date.parse(c.startDate), end: Date.parse(c.endDate || c.startDate) }))
      .filter(c => c.end > now + lg.days[0] * 86400000 && c.at < now + lg.days[1] * 86400000);
    const have = new Set((base.events || []).map(e => spEtYmd(Date.parse(e.date))));
    const dates = [...new Set(cal.map(c => spEtYmd(c.at)))].filter(d => !have.has(d)).slice(0, 6);
    const extra = await Promise.all(dates.map(d => spGet(spScoreUrl(lg, d)).then(j => j.events || []).catch(() => [])));
    const byId = new Map();
    for (const e of (base.events || []).concat(extra.flat())) if (e && e.id) byId.set(String(e.id), e);
    SP.ev[id] = [...byId.values()].map(e => spCard(lg, e)).filter(Boolean)
      .filter(e => e.end > now + lg.days[0] * 86400000 && e.at < now + lg.days[1] * 86400000).sort((a, b) => a.at - b.at);
    SP.t[id] = Date.now(); SP.tFull = SP.tFull || {}; SP.tFull[id] = Date.now();
    SP.errs = SP.errs || {}; SP.errs[id] = '';
  } catch (e) {
    SP.errs = SP.errs || {}; SP.errs[id] = e.message || 'offline';
    if (!SP.ev[id]) SP.ev[id] = [];
  }
}
function spFighter(x) {
  const a = x.athlete || {};
  const full = a.displayName || a.fullName || '?';
  return { id: String(a.id || x.id || full), home: x.order === 2, name: a.shortName || full, full, nick: full.split(' ').slice(-1)[0], loc: '', abbr: '',
           logo: (a.flag && a.flag.href) || '', score: '', rec: x.records && x.records[0] ? x.records[0].summary : '', winner: !!x.winner, color: '', alt: '' };
}
function spCard(lg, e) {
  const comps = (e.competitions || []).slice().sort((a, b) => Date.parse(a.startDate || a.date) - Date.parse(b.startDate || b.date));
  if (!comps.length) return null;
  const nm = String(e.name || '').toLowerCase();
  // the main event: the bout named in the event title, else the last bout of the night
  let main = comps.slice().reverse().find(c => (c.competitors || []).length === 2 && c.competitors.every(x => { const n = ((x.athlete || {}).displayName || '').split(' ').slice(-1)[0].toLowerCase(); return n && nm.includes(n); }));
  if (!main) main = comps[comps.length - 1];
  const st = s => ((s || {}).type || {});
  const states = comps.map(c => st(c.status).state || 'pre');
  const evSt = st(e.status);
  const state = states.includes('in') || (states.includes('post') && states.includes('pre')) ? 'in'
    : states.every(s => s === 'post') ? 'post' : (evSt.state === 'in' ? 'in' : 'pre');
  const cur = comps.find(c => st(c.status).state === 'in');
  const nets = [];
  for (const c of comps) {
    (c.broadcasts || []).forEach(b => (b.names || []).forEach(n => { if (n && !nets.includes(n)) nets.push(n); }));
    (c.geoBroadcasts || []).forEach(b => { const n = b.media && b.media.shortName; if (n && !nets.includes(n)) nets.push(n); });
  }
  const bouts = comps.slice().reverse().slice(0, 8).map(c => {
    const f = (c.competitors || []).map(spFighter);
    const w = f.find(x => x.winner);
    return { a: f[0] ? f[0].full : '?', b: f[1] ? f[1].full : '?', cls: (c.type && (c.type.abbreviation || c.type.text)) || '', done: st(c.status).state === 'post',
             live: st(c.status).state === 'in', result: w ? `${w.full} won${st(c.status).shortDetail && !/final/i.test(st(c.status).shortDetail) ? ' · ' + st(c.status).shortDetail : ''}` : '' };
  });
  const fighters = (main.competitors || []).map(spFighter).sort((a, b) => a.home - b.home);
  if (fighters.length !== 2) return null;
  const at = Date.parse(comps[0].startDate || comps[0].date || e.date);
  const lastAt = Date.parse(comps[comps.length - 1].startDate || comps[comps.length - 1].date || e.date);
  return { id: String(e.id), lg: lg.id, lgName: e.shortName || lg.name, at, mainAt: lastAt, end: lastAt + 3 * 3600000, name: e.name || '', short: e.shortName || lg.name,
           state, detail: state === 'in' ? (cur ? `${st(cur.status).shortDetail || 'Live'} · ${(spFighter(cur.competitors[0]) || {}).nick} vs ${(spFighter(cur.competitors[1]) || {}).nick}` : 'Live') : state === 'post' ? 'Final' : '',
           now: cur ? { vs: `${spFighter(cur.competitors[0]).nick} vs ${spFighter(cur.competitors[1]).nick}`, detail: st(cur.status).shortDetail || 'Live' } : null,
           done: state === 'post', teams: fighters, nets, venue: (main.venue && main.venue.fullName) || '', card: true, bouts };
}

async function spLoadAll(force) {
  await Promise.all(SP.prefs.leagues.map(id => spLoadLeague(id, force)));
  if (tab === 'sports') spBuild();
  spSchedule();
}
function spSchedule() {
  clearTimeout(SP.timer);
  if (tab !== 'sports') return;
  const live = SP.prefs.leagues.some(id => (SP.ev[id] || []).some(e => e.state === 'in'));
  SP.timer = setTimeout(() => { if (tab === 'sports' && !document.hidden) spLoadAll(false); else spSchedule(); }, live ? 60000 : 5 * 60000);
}

/* Team lists for the picker (cached for a month). */
async function spTeams(id) {
  if (SP.teams[id]) return SP.teams[id];
  if (spLeague(id).cards) return (SP.teams[id] = []);
  const c = readJSON('spTeams.' + id, null);
  if (c && Date.now() - c.t < 30 * 86400000 && c.list && c.list.length) return (SP.teams[id] = c.list);
  const lg = spLeague(id);
  try {
    const j = await spGet(`${ESPN}/${lg.sport}/${lg.league}/teams${lg.college ? '?limit=1000' : ''}`);
    const raw = (((j.sports || [])[0] || {}).leagues || [])[0];
    const list = ((raw && raw.teams) || []).map(x => x.team).filter(Boolean).map(t => ({
      id: String(t.id), name: t.shortDisplayName || t.name || t.displayName, full: t.displayName || t.name || '',
      logo: (t.logos && t.logos[0] && t.logos[0].href) || '' })).sort((a, b) => a.full.localeCompare(b.full));
    if (list.length) writeJSON('spTeams.' + id, { t: Date.now(), list });
    return (SP.teams[id] = list);
  } catch (e) { return []; }
}

/* ---------------------------------------------------------------- matching games to your channels */

const SP_NOISE = new Set(['hd', 'fhd', 'uhd', 'sd', '4k', 'hevc', 'h265', 'east', 'west', 'backup', 'alt', '1080p', '720p', '1080', '720', 'raw', 'vip', 'feed', 'hq', '50fps', '60fps']);
const SP_STREAMING = /^(espn\+|peacock|prime video|amazon prime|apple tv\+?|paramount\+|max|netflix|mlb\.tv|nba league pass|nhl\.tv|nfl\+|youtube|dazn|fubo|mls season pass|espn app|tnt sports app)$/i;
const SP_NET_ALIAS = { fs1: 'foxsports1', fs2: 'foxsports2', nflnet: 'nflnetwork', mlbnet: 'mlbnetwork', nhlnet: 'nhlnetwork', usanet: 'usanetwork',
  cbssn: 'cbssportsnetwork', nbatv: 'nbatv', truetv: 'trutv', bsn: 'bigtennetwork', btn: 'bigtennetwork', secn: 'secnetwork', accn: 'accnetwork', pac12: 'pac12network' };

/* "US: ESPN 2 HD" → "espn2" (country prefix, brackets and quality tags dropped). */
function spCompact(s) {
  let toks = String(s).toLowerCase().replace(/^\s*[a-z]{2,3}\s*[|:]\s*/, '').replace(/^\s*[a-z]{2,3}\s+-\s+/, '')
    .replace(/\(.*?\)|\[.*?\]/g, ' ').replace(/[^a-z0-9+]+/g, ' ').trim().split(' ').filter(Boolean);
  if (toks.length > 1 && /^(us|usa|uk|ca|en)$/.test(toks[0]) && !/^(network|net)$/.test(toks[1])) toks = toks.slice(1);
  return toks.filter(t => !SP_NOISE.has(t)).join('');
}
function spIndex() {
  const key = LV.chans.length + ':' + (LV.chans[0] ? LV.chans[0].u : '') + ':' + Array.from(lvHiddenCats()).join(',');
  if (key === SP.chanKey && SP.netIdx) return;
  SP.chanKey = key;
  const hid = lvHiddenCats();
  SP.netIdx = LV.chans.filter(ch => !hid.has(ch.g)).map(ch => ({ ch, raw: ch.n.toLowerCase() }));
  SP.byKey = new Map();
  for (const { ch } of SP.netIdx) { if (!SP.byKey.has(ch.k)) SP.byKey.set(ch.k, []); SP.byKey.get(ch.k).push(ch); }
  SP.match = {};
}
function spWordRe(w) { return new RegExp('(^|[^a-z0-9])' + w.toLowerCase().replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '([^a-z0-9]|$)'); }
function spMentions(text, t) {
  const words = [t.nick, t.name, t.full].filter(w => w && w.length >= 3);
  return words.some(w => spWordRe(w).test(text));
}
function spNetChans(net) {
  if (SP_STREAMING.test(net.trim())) return [];
  let want = spCompact(net);
  if (SP_NET_ALIAS[want]) want = SP_NET_ALIAS[want];
  if (!want) return [];
  const broadcast = /^(abc|cbs|nbc|fox|cw|pbs|telemundo|univision)$/.test(want);
  const out = [];
  for (const x of SP.netIdx) {
    let c = x.compact;
    if (c === undefined) c = x.compact = spCompact(x.ch.n);
    let cc = c;
    if (!cc.startsWith(want)) { const a = SP_NET_ALIAS[cc]; if (a) cc = a; else continue; }
    if (!cc.startsWith(want)) continue;
    const rem = cc.slice(want.length);
    if (rem === '') out.push([x.ch, 3.5]);
    else if (/^(network|net|sports|sportsnetwork|channel)$/.test(rem)) out.push([x.ch, 3]);
    else if (/^\d+$/.test(rem)) continue;                                  // ESPN vs ESPN2, FS1 vs FS2…
    else if (broadcast && /^\d+[a-z]/.test(rem)) out.push([x.ch, 1.5]);    // a local station: "FOX 5 New York"
    else if (rem.length >= 3) out.push([x.ch, .8]);
  }
  return out;
}

/* Channels that should be showing this game, best first: [{ch, score, why, prog}] */
function spMatch(ev) {
  const m = SP.match[ev.id];
  if (m && Date.now() - m.t < 10 * 60000) return m.list;
  if (typeof LV === 'undefined') return [];
  if (!LV.loaded) { try { lvLoad(); } catch (e) {} }
  if (!LV.chans.length) return [];
  spIndex();
  const out = new Map();
  const add = (ch, score, why, prog) => { const o = out.get(ch.u); if (!o || o.score < score) out.set(ch.u, { ch, score, why, prog }); };
  if (ev.card) {
    // fight night: guide listings that say "UFC" around the card, UFC / PPV event channels, then networks
    const num = (ev.short.match(/\d{3}/) || [''])[0];
    const names = ev.teams.map(t => t.nick.toLowerCase()).filter(n => n.length > 2);
    const strong = t => (num && t.includes(num)) || names.some(n => t.includes(n));
    if (B.liveSearchPrograms) {
      let rows = [];
      try { rows = JSON.parse(B.liveSearchPrograms('UFC', ev.at - 2 * 3600000, (ev.mainAt || ev.at) + 4 * 3600000)) || []; } catch (e) {}
      for (const [key, s, e2, title] of rows) {
        if (s > (ev.mainAt || ev.at) + 30 * 60000 || e2 < ev.at) continue;
        for (const ch of SP.byKey.get(key) || []) add(ch, strong(String(title).toLowerCase()) ? 5 : 3, 'guide', [s, e2, title]);
      }
    }
    if (Math.abs(ev.at - Date.now()) < 24 * 3600000) for (const x of SP.netIdx) {
      if (!/(^|[^a-z])ufc([^a-z]|$)/.test(x.raw) && !/(^|[^a-z])ppv([^a-z]|$)/.test(x.raw)) continue;
      add(x.ch, strong(x.raw) ? 4 : /ufc/.test(x.raw) && !/fight ?pass/.test(x.raw) ? 2.2 : 1.3, 'event');
    }
    for (const net of ev.nets) for (const [ch, sc] of spNetChans(net)) add(ch, sc, 'network');
    let list = [...out.values()].sort((a, b) => b.score - a.score);
    if (list.some(x => x.score >= 1.2)) list = list.filter(x => x.score >= 1.2);
    SP.match[ev.id] = { t: Date.now(), list: list.slice(0, 6) };
    return SP.match[ev.id].list;
  }
  // 1. the TV guide: a programme around kick-off that names the teams
  if (B.liveSearchPrograms) {
    for (const t of ev.teams) {
      const q = t.nick && t.nick.length >= 3 ? t.nick : t.name;
      if (!q || q.length < 3) continue;
      let rows = [];
      try { rows = JSON.parse(B.liveSearchPrograms(q, ev.at - 3 * 3600000, ev.at + 4 * 3600000)) || []; } catch (e) {}
      for (const [key, s, e, title] of rows) {
        if (s > ev.at + 45 * 60000 || e < ev.at + 10 * 60000) continue;   // must be on around the start
        const tl = String(title).toLowerCase();
        const both = ev.teams.every(x => spMentions(tl, x));
        if (!both && !spMentions(tl, t)) continue;
        for (const ch of SP.byKey.get(key) || []) add(ch, both ? 5 : 3, 'guide', [s, e, title]);
      }
    }
  }
  // 2. event channels named after the game ("NFL 03: Bills vs Jets") — they're renamed daily, so only near game time
  const soon = Math.abs(ev.at - Date.now()) < 12 * 3600000;
  if (soon) for (const x of SP.netIdx) {
    const hits = ev.teams.filter(t => spMentions(x.raw, t)).length;
    if (hits === 2) add(x.ch, 4, 'event');
    else if (hits === 1 && /(^|[^a-z])(vs?|@|at)([^a-z]|$)/.test(x.raw)) add(x.ch, 2.5, 'event');
  }
  // 3. the game's TV networks
  for (const net of ev.nets) for (const [ch, sc] of spNetChans(net)) add(ch, sc, 'network');
  let list = [...out.values()].sort((a, b) => b.score - a.score || (LV.favs.has(b.ch.u) - LV.favs.has(a.ch.u)));
  if (list.some(x => x.score >= 1.2)) list = list.filter(x => x.score >= 1.2);
  list = list.slice(0, 6);
  SP.match[ev.id] = { t: Date.now(), list };
  return list;
}

/* ---------------------------------------------------------------- rows */

function spWhen(ev) {
  if (ev.state === 'in') return 'LIVE · ' + ev.detail;
  if (ev.state === 'post') return ev.detail || 'Final';
  if (/postponed|canceled|cancelled|delayed|suspended/i.test(ev.detail)) return ev.detail;
  return remWhen(ev.at);
}
function spFavGame(ev) { return !!ev.card || ev.teams.some(t => spIsFav(ev.lg, t.id)); }   // follow UFC = follow every card

function spBuildRows() {
  const all = SP.prefs.leagues.flatMap(id => SP.ev[id] || []);
  const rows = [];
  const now = Date.now(), dayStart = new Date(); dayStart.setHours(0, 0, 0, 0);
  const yest = dayStart.getTime() - 86400000, dayEnd = dayStart.getTime() + 86400000;
  const live = all.filter(e => e.state === 'in').sort((a, b) => spFavGame(b) - spFavGame(a) || a.at - b.at);
  if (live.length) rows.push({ id: 'live', title: 'Live now', items: live.slice(0, 30) });
  const anyFav = Object.values(SP.prefs.teams).some(l => l.length) || SP.prefs.leagues.some(id => spLeague(id).cards);
  const mine = all.filter(spFavGame).filter(e => e.state !== 'post' || e.at > yest)
    .sort((a, b) => ({ in: 0, pre: 1, post: 2 }[a.state] - { in: 0, pre: 1, post: 2 }[b.state]) || (a.state === 'post' ? b.at - a.at : a.at - b.at));
  rows.push({ id: 'mine', title: anyFav ? 'My teams' : 'My teams · pick your teams', items: mine.slice(0, 30).concat([{ edit: true }]) });
  for (const id of SP.prefs.leagues) {
    const lg = spLeague(id), evs = SP.ev[id] || [];
    let items = evs.filter(e => e.at >= dayStart.getTime() && e.at < dayEnd);
    let title = `${lg.name} · Today`;
    if (!items.length) {
      items = evs.filter(e => e.state !== 'post').slice(0, 20);
      title = `${lg.name} · Coming up`;
    }
    if (!items.length) items = evs.filter(e => e.at > yest);
    if (!items.length) { rows.push({ id: 'lg-' + id, title: lg.name, items: [], note: SP.busy[id] ? 'Loading…' : (SP.errs && SP.errs[id]) ? `Couldn't load (${SP.errs[id]})` : 'No games in the next few days' }); continue; }
    rows.push({ id: 'lg-' + id, title, items: items.sort((a, b) => ({ in: 0, pre: 1, post: 2 }[a.state] - { in: 0, pre: 1, post: 2 }[b.state]) || a.at - b.at).slice(0, 40) });
  }
  return rows;
}

/* ---------------------------------------------------------------- the screen (design A, as on the TV)

   Tabs (Live Now · Today · My Teams · Fantasy) pick which games show; a scoreboard up top for the game
   you're on (the best live one to start with) with your fantasy matchup beside it; the leagues as a
   row of badges that pick Today's league; then the games as glass cards with the teams' colours.
   A phone held upright gets the same pieces stacked, and the games as a list. */

const SP_LG_LOGO = { nfl: 'https://a.espncdn.com/i/teamlogos/leagues/500/nfl.png', nba: 'https://a.espncdn.com/i/teamlogos/leagues/500/nba.png',
  mlb: 'https://a.espncdn.com/i/teamlogos/leagues/500/mlb.png', nhl: 'https://a.espncdn.com/i/teamlogos/leagues/500/nhl.png',
  wnba: 'https://a.espncdn.com/i/teamlogos/leagues/500/wnba.png', epl: 'https://a.espncdn.com/i/leaguelogos/soccer/500/23.png',
  laliga: 'https://a.espncdn.com/i/leaguelogos/soccer/500/15.png', mls: 'https://a.espncdn.com/i/leaguelogos/soccer/500/19.png',
  ucl: 'https://a.espncdn.com/i/leaguelogos/soccer/500/2.png' };
Object.assign(SP, { tab: '', lgSel: 0, feat: '', zone: 'grid', fy: [], fyAt: 0, fyAsk: 0 });

/* your fantasy team: from this device, or (on a phone) from the TV it's linked with, where it's set up */
function spFyRow() {
  try { const l = B.fantasyRow ? JSON.parse(B.fantasyRow() || '[]') : []; if (l && l.length) { SP.fy = l; return l; } } catch (e) {}
  if (typeof lkOn === 'function' && lkOn() && Date.now() - SP.fyAsk > 30000) {
    SP.fyAsk = Date.now();
    lkSend({ type: 'fyrow' }, true).then(r => {
      const l = r && Array.isArray(r.row) ? r.row : null;
      if (!l) return;
      const was = JSON.stringify(SP.fy);
      SP.fy = l; SP.fyAt = Date.now();
      if (tab === 'sports' && JSON.stringify(l) !== was) spBuild();
    });
  }
  return SP.fy || [];
}
function spDark(hex, f) { return `color-mix(in srgb, ${hex || '#3a4a66'} ${Math.round((f || .4) * 100)}%, #06070c)`; }
function spLogo(u) { return u && u.includes('espncdn.com') && u.includes('/500/') ? u.replace('/500/', '/500-dark/') : u; }
function spImg(u, cls) { return u ? `<img class="${cls || ''}" src="${esc(spLogo(u))}" data-alt="${esc(u)}" loading="lazy" onerror="if(this.dataset.alt&&this.src!==this.dataset.alt){this.src=this.dataset.alt;this.dataset.alt=''}else this.style.visibility='hidden'">` : ''; }
function spPlayersIn() {
  const m = {};
  for (const x of SP.fy || []) if (x && x.gameId && !x.bench && !x.fyTeam && !x.fyEdit) m[x.gameId] = (m[x.gameId] || 0) + 1;
  return m;
}
function spGameById(id) {
  for (const l of Object.values(SP.ev || {})) { const e = (l || []).find(x => String(x.id) === String(id)); if (e) return e; }
  return null;
}
/** a live game (your teams' first), else the next one coming up */
function spBest(sets) {
  for (const r of [sets.live, sets.mine]) if (r) { const g = r.items.find(e => !e.edit && e.teams); if (g) return g; }
  for (const r of sets.lgs) { const g = r.items.find(e => !e.edit && e.teams && e.state !== 'post'); if (g) return g; }
  for (const r of sets.lgs) { const g = r.items.find(e => !e.edit && e.teams); if (g) return g; }
  return null;
}

function spCardHtml(ev, r, c, withMatch, mine) {
  if (ev.edit) return `<div class="gc gedit" data-r="${r}" data-c="${c}"><b>＋</b><span>${Object.values(SP.prefs.teams).some(l => l.length) ? 'Edit sports & teams' : 'Pick your teams'}</span><div class="rim"></div></div>`;
  if (ev.fy) return spFyCardHtml(ev, r, c);
  const live = ev.state === 'in', post = ev.state === 'post';
  const m = withMatch && !post ? spMatch(ev) : null;
  let tv = '';
  if (m && m.length) tv = `<span class="gtv on">${TV_SVG}${esc(m[0].ch.n)}</span>`;
  else if (ev.nets.length && !post) tv = `<span class="gtv">${TV_SVG}${esc(ev.nets.slice(0, 3).join(' · '))}</span>`;
  else if (ev.card && ev.bouts && ev.bouts[0]) tv = `<span class="gtv">Main event${ev.bouts[0].cls ? ' · ' + esc(ev.bouts[0].cls) : ''}</span>`;
  const bell = !post && m && m.length && remHas(m[0].ch.u, ev.at) ? BELL_SVG : '';
  const c1 = ev.card ? '#b3261e' : ev.teams[0].color || '#3a4a66', c2 = ev.card ? '#0a84ff' : ev.teams[1].color || '#3a4a66';
  const n = mine && mine[ev.id];
  const lead = i => { const a = +ev.teams[i].score, b = +ev.teams[1 - i].score; return isNaN(a) || isNaN(b) || a >= b; };
  const when = live ? `<span class="glive">LIVE</span>${esc(ev.detail || '')}` : bell + esc(spWhen(ev));
  return `<div class="gc${live ? ' live' : ''}${post ? ' post' : ''}${ev.card ? ' fc' : ''}" data-r="${r}" data-c="${c}" data-id="${esc(ev.id)}" style="--c1:${c1};--c2:${c2}">` +
    `<div class="gch"><span class="gcl">${esc(ev.lgName)}</span><span class="gcs">${when}</span></div>` +
    ev.teams.map((t, i) => {
      const dim = (post && !t.winner && !ev.card) || (live && !lead(i));
      const right = !ev.card && (live || post) && t.score !== '' ? `<b>${esc(t.score)}</b>` : ev.card && post && t.winner ? '<b class="gw">W</b>' : `<span class="gcr">${esc(t.rec)}</span>`;
      return `<div class="gct${dim ? ' dim' : ''}${spIsFav(ev.lg, t.id) ? ' fav' : ''}"><span class="gclg">${spImg(t.logo)}</span><span class="gcn">${esc(t.name)}</span>${right}</div>`;
    }).join('') +
    `<div class="gcb">${tv}${n ? `<span class="gmine">★ ${n} of your players</span>` : ''}</div><div class="rim"></div></div>`;
}
const TV_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="13" rx="2.5"/><path d="M8 21h8"/></svg>';
const SP_POS = { QB: '#ff5a5f', RB: '#30d158', WR: '#0a84ff', TE: '#ff9f0a', K: '#bf5af2' };

/** a fantasy player's card: position, name, their game and score, their line so far (or injury) */
function spFyCardHtml(it, r, c) {
  if (it.fyEdit) return `<div class="gc gedit gfy" data-r="${r}" data-c="${c}"><b>＋</b><span>${esc(it.label || 'Pick my players')}</span><div class="rim"></div></div>`;
  if (it.fyTeam) {
    const pl = it.playing || 0, left = it.left || 0;
    const st = (pl ? pl + ' playing now' : '') + (pl && left ? ' · ' : '') + (left ? left + ' yet to play' : '') + (!pl && !left ? 'All done this week' : '');
    return `<div class="gc gfyt${pl ? ' live' : ''}" data-r="${r}" data-c="${c}"><div class="gch"><span class="gcl">${esc(it.name || 'My team')}</span><span class="gcs">${it.week ? 'WEEK ' + it.week : ''}</span></div>` +
      `<div class="gfyp"><b>${esc(it.pts || '0')}</b> pts</div>${it.proj ? `<div class="gfyj">Projected ${esc(it.proj)}</div>` : ''}<div class="gcb">${pl ? '<i class="ldot"></i>' : ''}${esc(st)}${it.scoring ? ' · ' + esc(it.scoring) : ''}</div><div class="rim"></div></div>`;
  }
  const ts = it.teams || [], mineT = ts.find(t => t.abbr === it.abbr) || {};
  const live = it.state === 'in', post = it.state === 'post', pc = SP_POS[it.pos] || '#8e8e93';
  const st = it.status || '', bad = st === 'O' || /^IR/.test(st) || st === 'D' || st === 'SUSP';
  let foot = '';
  if (st && !(live && it.line)) foot = `<span class="gfyst${bad ? ' bad' : ''}">${esc(it.statusFull || st)}${it.note ? ' · ' + esc(it.note) : ''}</span>`;
  else if (it.line) foot = `<span class="gfyl">${esc(it.line)}</span>`;
  else if (it.last) foot = `<span class="gfylast">Last: ${esc(it.last)}</span>`;
  const game = it.opp ? `${esc(it.opp)}${it.score ? '&nbsp;&nbsp;' + esc(it.score) : ''}` : 'No game this week';
  const when = !it.opp ? '' : live ? `<span class="gfyw live"><i class="ldot"></i>${esc(String(it.when || '').replace(/^LIVE · /, ''))}</span>` : `<span class="gfyw${post ? ' post' : ''}">${esc(it.when || '')}${it.tv && !post ? ' · ' + esc(it.tv) : ''}</span>`;
  return `<div class="gc gfy${live ? ' live' : ''}${it.bench ? ' bench' : ''}" data-r="${r}" data-c="${c}" style="--c1:${mineT.color || '#3a4a66'}">` +
    (it.photo ? `<img class="gfyph" src="${esc(it.photo)}" loading="lazy" onerror="this.remove()">` : mineT.logo ? `<img class="gfyph lg" src="${esc(spLogo(mineT.logo))}" loading="lazy" onerror="this.remove()">` : '') +
    `<div class="gch"><span class="gfypos" style="--pc:${pc}">${esc(it.pos || '')}</span><span class="gcl">${esc(it.abbr || '')}${it.bench ? ' · BENCH' : ''}</span>` +
    (it.pts ? `<span class="gfypts${live ? ' live' : ''}">${esc(it.pts)}${it.proj ? `<small>proj ${esc(it.proj)}</small>` : ''}</span>` : it.proj ? `<span class="gfypts proj">proj ${esc(it.proj)}</span>` : '') + `</div>` +
    `<div class="gfyn">${esc(it.name)}</div><div class="gfyg">${game}</div>${when}<div class="gcb">${foot}</div><div class="rim"></div></div>`;
}

/** the big scoreboard: logos, names and records either side of a big score, the clock, and Watch */
function spHeroHtml(ev, mine) {
  if (!ev) return '';
  const live = ev.state === 'in', post = ev.state === 'post', fight = !!ev.card;
  const c1 = fight ? '#b3261e' : ev.teams[0].color || '#3a4a66', c2 = fight ? '#0a84ff' : ev.teams[1].color || '#3a4a66';
  const m = post ? [] : spMatch(ev);
  const tv = m.length ? m[0].ch.n : ev.nets.length && !post ? ev.nets.slice(0, 3).join(' · ') : '';
  const n = mine && mine[ev.id];
  const extra = fight && ev.bouts && ev.bouts[0] ? 'Main event' + (ev.bouts[0].cls ? ' · ' + ev.bouts[0].cls : '') + (ev.bouts.length > 1 ? ` · ${ev.bouts.length - 1} more fight${ev.bouts.length > 2 ? 's' : ''}` : '') : '';
  const info = extra || (n ? `★ ${n} of your fantasy players in this game` : '');
  const top = esc(ev.lgName) + (ev.venue && !live ? ' · ' + esc(ev.venue) : '') + ' · ' + esc(spWhen(ev).replace(/^LIVE · /, ''));
  const side = (t, i) => `<div class="sht${i ? ' h' : ''}"><span class="shlg">${spImg(t.logo)}</span><div class="shnm"><b>${esc(t.name)}${spIsFav(ev.lg, t.id) ? ' <i>★</i>' : ''}</b>${t.rec ? `<small>${esc(t.rec)}</small>` : ''}</div></div>`;
  const mid = (live || post) && !fight && ev.teams[0].score !== ''
    ? `<div class="shsc"><b>${esc(ev.teams[0].score)}&nbsp;–&nbsp;${esc(ev.teams[1].score)}</b>${post ? '<small>FINAL</small>' : ''}</div>`
    : `<div class="shsc pre"><b>${fight ? 'VS' : esc(spWhen(ev))}</b></div>`;
  return `<div class="sphero${live ? ' live' : ''}" data-act="hero" style="--c1:${c1};--c2:${c2}">` +
    `<div class="shtop">${live ? '<span class="glive">LIVE</span>' : ''}<span>${top}</span></div>` +
    `<div class="shmid">${side(ev.teams[0], 0)}${mid}${side(ev.teams[1], 1)}</div>` +
    `<div class="shfoot"><span class="shinfo${n && !extra ? ' mine' : ''}">${esc(info)}</span>${tv ? `<span class="shwatch${m.length ? ' on' : ''}">${m.length ? '▶&nbsp; Watch on ' : 'On '}${esc(tv)}</span>` : ''}</div><div class="rim"></div></div>`;
}

/** your fantasy team beside the scoreboard: points so far, the projection, who's playing, your top scorers */
function spFyPanelHtml(fy) {
  const team = fy.find(x => x.fyTeam);
  if (!team) return `<div class="spfy" data-act="fy"><div class="fyk">MY FANTASY</div><div class="fyempty">Pick your players to see their points here.</div><div class="rim"></div></div>`;
  const pl = fy.filter(x => !x.fyTeam && !x.fyEdit && !x.bench).sort((a, b) => (+b.pts || -1) - (+a.pts || -1)).slice(0, 3);
  const p = team.playing || 0, left = team.left || 0;
  const st = (p ? p + ' playing now' : '') + (p && left ? ' · ' : '') + (left ? left + ' still to play' : '') + (!p && !left ? 'All done this week' : '');
  const short = nm => { const i = nm.indexOf(' '); return i > 0 ? nm[0] + '. ' + nm.slice(i + 1) : nm; };
  return `<div class="spfy" data-act="fy"><div class="fyk">MY FANTASY${team.week ? ' · WEEK ' + team.week : ''}</div><div class="fyn">${esc(team.name || 'My team')}</div>` +
    `<div class="fyrow"><div class="fypts"><b>${esc(team.pts || '0')}</b> pts</div>${team.proj ? `<div class="fyproj">Projected ${esc(team.proj)}</div>` : ''}</div>` +
    `<div class="fyst">${p ? '<i class="ldot"></i>' : ''}${esc(st)}</div>` +
    `<div class="fychips">${pl.map(x => `<span>${esc(short(x.name))} <b>${esc(x.pts || '—')}</b></span>`).join('')}</div><div class="rim"></div></div>`;
}

function spBuild() {
  const box = $('#sprows');
  const head = $('#sphead');
  if (!head.querySelector('#sptabs')) head.insertAdjacentHTML('beforeend', '<div id="sptabs"></div>');
  if (!SP.prefs.leagues.length) {
    SP.rows = [{ id: 'setup', title: '', items: [{ edit: true }] }];
    head.querySelector('#sptabs').innerHTML = '';
    head.querySelector('.spt').innerHTML = 'Sports';
    box.innerHTML = `<div class="spsetup"><div class="spbig">Follow your teams</div><div class="spsub">Pick the sports and teams you follow. TopShelf shows their games, live scores, and which of your Live TV channels is showing each one.</div>` +
      `<div class="sprow" data-r="0"><div class="spstrip"><div class="sprin">${spCardHtml({ edit: true }, 0, 0)}</div></div></div></div>`;
    SP.r = 0; SP.cols = {};
    $('#sports').style.removeProperty('--b1'); $('#sports').style.removeProperty('--b2');
    spMark();
    return;
  }
  const all = spBuildRows(), fy = spFyRow();
  const sets = { all, live: all.find(r => r.id === 'live'), mine: all.find(r => r.id === 'mine'), lgs: all.filter(r => r.id.startsWith('lg-')) };
  SP.sets = sets;
  const tabs = [];
  if (sets.live) tabs.push('Live Now');
  tabs.push('Today');
  if (sets.mine) tabs.push('My Teams');
  if (fy.length) tabs.push('Fantasy');
  if (!tabs.includes(SP.tab) || !SP.tabPicked) SP.tab = tabs[0];        // (Live Now first, until you pick a tab)
  if (SP.lgSel >= sets.lgs.length) SP.lgSel = 0;
  // the games the tab (and league) picks: the one row on screen
  const prevId = (() => { const e = spCur(); return e ? (e.id || e.k || '') : ''; })();
  let row;
  if (SP.tab === 'Live Now') row = Object.assign({}, sets.live, { title: 'Live now' });
  else if (SP.tab === 'My Teams') row = Object.assign({}, sets.mine, { title: 'My teams' });
  else if (SP.tab === 'Fantasy') row = { id: 'fy', title: 'My fantasy team', items: fy };
  else row = sets.lgs[SP.lgSel] || { id: 'today', title: 'Today', items: [], note: 'Pick your sports and teams' };
  if (SP.rowId !== row.id) { SP.cols = {}; SP.rowId = row.id; }
  SP.rows = [row]; SP.r = 0;
  const ci = prevId ? row.items.findIndex(e => (e.id || e.k) === prevId) : -1;
  SP.cols[0] = ci >= 0 ? ci : Math.min(SP.cols[0] || 0, Math.max(0, row.items.length - 1));
  // the scoreboard's game: the one you picked (while it's still on), or the best live one
  let feat = SP.feat ? spGameById(SP.feat) : null;
  if (!feat) { feat = spBest(sets); SP.feat = feat ? feat.id : ''; }
  const mine = spPlayersIn();
  const live = all.reduce((n, r) => r.id.startsWith('lg-') ? n + r.items.filter(e => e.state === 'in').length : n, 0);
  head.querySelector('.spt').innerHTML = 'Sports' + (live ? `<span class="splive">● ${live} LIVE</span>` : '');
  const t = Math.max(0, ...SP.prefs.leagues.map(id => SP.t[id] || 0));
  const failed = SP.prefs.leagues.filter(id => SP.errs && SP.errs[id]).length;
  const upd = t ? `Updated ${fmtTime(t)}` + (failed ? ` · ${failed} couldn't load` : '') : SP.prefs.leagues.some(id => SP.busy[id]) ? 'Loading…' : failed ? "Couldn't reach ESPN" : 'Loading…';
  head.querySelector('#sptabs').innerHTML = tabs.map(x => `<span class="sptab${x === SP.tab ? ' on' : ''}${x === SP.tab && SP.zone === 'tabs' ? ' f' : ''}" data-tab="${esc(x)}">${esc(x)}</span>`).join('') + `<span class="spupd">${esc(upd)}</span>`;
  // the backdrop is lit in the scoreboard game's colours
  const sp = $('#sports');
  if (feat && feat.teams) { sp.style.setProperty('--b1', feat.card ? '#b3261e' : feat.teams[0].color || '#0b3a52'); sp.style.setProperty('--b2', feat.card ? '#0a84ff' : feat.teams[1].color || '#5a1020'); }
  const games = row.items.filter(e => e.teams && !e.fy).length, liveN = row.items.filter(e => e.state === 'in').length;
  const top = box.scrollTop, left = (box.querySelector('.spstrip') || {}).scrollLeft || 0;
  box.innerHTML =
    `<div class="sptop${fy.length ? ' withfy' : ''}">${spHeroHtml(feat, mine)}${fy.length ? spFyPanelHtml(fy) : ''}</div>` +
    (sets.lgs.length ? `<div class="sprail"><div class="sprailin">${sets.lgs.map((r, i) => {
      const lg = spLeague(r.id.slice(3)) || {}, on = SP.tab === 'Today' && i === SP.lgSel, lgo = SP.lgLogo[lg.id] || SP_LG_LOGO[lg.id];
      return `<span class="spbadge${on ? ' on' : ''}${SP.zone === 'rail' && i === SP.lgSel ? ' f' : ''}" data-lg="${i}"><i class="${lgo ? '' : 'emo'}">${lgo ? `<img src="${esc(lgo)}" onerror="this.replaceWith(document.createTextNode('${lg.icon || ''}'))">` : esc(lg.icon || lg.name.slice(0, 3))}</i><span>${esc(lg.name)}</span></span>`;
    }).join('')}</div></div>` : '') +
    `<div class="sprow" data-r="0"><div class="sprt">${esc(row.title)}${games ? `<small>${games} game${games === 1 ? '' : 's'}${liveN && liveN < games ? ' · ' + liveN + ' live' : ''}</small>` : ''}</div>` +
    (row.items.length ? `<div class="spstrip"><div class="sprin">${row.items.map((ev, c) => spCardHtml(ev, 0, c, row.id === 'live' || row.id === 'mine' || c < 12, mine)).join('')}</div></div>`
                      : `<div class="spnote">${esc(row.note || (SP.tab === 'Today' ? 'Pick your sports and teams' : 'Nothing here right now'))}</div>`) + '</div>';
  box.scrollTop = top;
  const strip = box.querySelector('.spstrip');
  if (strip) { if (left) strip.scrollLeft = left; strip.addEventListener('scroll', noteScroll, { passive: true }); }
  const rail = box.querySelector('.sprailin');
  if (rail) rail.addEventListener('scroll', noteScroll, { passive: true });
  spMark();
}

/* ---------------------------------------------------------------- focus */

function spCur() { const r = SP.rows[SP.r]; return r && r.items[SP.cols[SP.r] || 0]; }
function spMark() {
  document.querySelectorAll('#sprows .gc.f, #sprows .sphero.f, #sprows .spbadge.f, #sptabs .sptab.f').forEach(el => el.classList.remove('f'));
  if (mode !== 'sports') return;
  const box = $('#sprows'), rem = remPx();
  if (SP.zone === 'tabs') { const t = document.querySelector('#sptabs .sptab.on'); if (t) t.classList.add('f'); box.scrollTo({ top: 0, behavior: 'smooth' }); return; }
  if (SP.zone === 'hero') { const h = box.querySelector('.sphero'); if (h) h.classList.add('f'); box.scrollTo({ top: 0, behavior: 'smooth' }); return; }
  if (SP.zone === 'rail') { const b = box.querySelectorAll('.spbadge')[SP.lgSel]; if (b) { b.classList.add('f'); b.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' }); } return; }
  const el = document.querySelector(`#sprows .gc[data-r="${SP.r}"][data-c="${SP.cols[SP.r] || 0}"]`);
  if (!el) return;
  el.classList.add('f');
  const strip = el.closest('.spstrip');
  if (strip && strip.scrollWidth > strip.clientWidth + 2 && (el.offsetLeft < strip.scrollLeft + rem * 3 || el.offsetLeft + el.offsetWidth > strip.scrollLeft + strip.clientWidth - rem * 3))
    strip.scrollTo({ left: Math.max(0, el.offsetLeft - rem * 3), behavior: 'smooth' });
  const r = el.getBoundingClientRect(), b = box.getBoundingClientRect();
  if (r.top < b.top + rem || r.bottom > b.bottom - rem) box.scrollBy({ top: r.top < b.top + rem ? r.top - b.top - rem * 2 : r.bottom - b.bottom + rem * 2, behavior: 'smooth' });
  // the scoreboard follows the game you're on (a fantasy player's card shows their game)
  const cur = spCur();
  const g = cur && cur.teams && !cur.fy ? cur : cur && cur.fy && cur.gameId ? spGameById(cur.gameId) : null;
  if (g && g.id !== SP.feat) { SP.feat = g.id; clearTimeout(SP.heroT); SP.heroT = setTimeout(spHeroOnly, 140); }
}
/** just the scoreboard (and the backdrop's colours), when the game on it changes */
function spHeroOnly() {
  const top = $('#sprows .sptop'), g = spGameById(SP.feat);
  if (!top || !g) return;
  const old = top.querySelector('.sphero');
  const tmp = document.createElement('div'); tmp.innerHTML = spHeroHtml(g, spPlayersIn());
  const nu = tmp.firstChild;
  if (old) old.replaceWith(nu); else top.prepend(nu);
  nu.classList.add('in');
  const sp = $('#sports');
  sp.style.setProperty('--b1', g.card ? '#b3261e' : g.teams[0].color || '#0b3a52'); sp.style.setProperty('--b2', g.card ? '#0a84ff' : g.teams[1].color || '#5a1020');
}

function spEnter() {
  $('#sports').classList.add('on');
  SP.zone = 'grid';
  spBuild();
  spLoadAll(false).then(() => { if (tab === 'sports') spBuild(); });
  setTimeout(() => { if (tab === 'sports') spBuild(); }, 50);
  clearInterval(SP.fyTick);
  SP.fyTick = setInterval(() => { if (tab !== 'sports') { clearInterval(SP.fyTick); return; } const was = JSON.stringify(SP.fy); spFyRow(); if (JSON.stringify(SP.fy) !== was) spBuild(); }, 15000);
}
function spLeave() { $('#sports').classList.remove('on'); clearTimeout(SP.timer); clearInterval(SP.fyTick); }

/** OK on a fantasy card: your team / pick your players / the player's game */
async function spFyOk(it, anchor) {
  if (!it) return;
  if (it.fyEdit || it.fyTeam) {
    if (it.fyTeam && it.pts && !it.fyEdit) return;
    if (B.fantasyPlayers && IS_TV) { B.fantasyPlayers(); return; }
    if (typeof lkOn === 'function' && lkOn()) {
      const r = await lkSend({ type: 'fantasy', page: 'players' });
      if (r && r.url) { if (B.openWeb) B.openWeb(r.url); else toast('Open ' + r.url + ' in your browser'); }
      return;
    }
    toast('Fantasy is set up on your TV (Settings › Sports)');
    return;
  }
  const g = it.gameId ? spGameById(it.gameId) : null;
  if (g) spOpen(g, anchor); else toast(it.gameId ? 'Add the NFL in Sports & teams to watch games from here' : it.name + ' has no game this week');
}
function spOk(anchor) {
  const cur = spCur();
  if (!cur) { if (SP.tab === 'Today') spPickOpen(); return; }
  if (cur.fy) return spFyOk(cur, anchor);
  spOpen(cur, anchor);
}

/* ---------------------------------------------------------------- actions */

function spWatch(ch) { if (typeof srWatch === 'function') srWatch(ch); }
function spOpen(ev, anchor) {
  if (!ev) return;
  if (ev.edit) return spPickOpen();
  if (ev.card) return spOpenCard(ev, anchor);
  const away = ev.teams[0], home = ev.teams[1];
  const title = `${away.name} ${ev.state === 'pre' ? '@' : away.score + ' –'} ${ev.state === 'pre' ? '' : home.score + ' '}${home.name} · ${spWhen(ev)}`.replace(/\s+/g, ' ');
  const m = ev.state === 'post' ? [] : spMatch(ev);
  const items = [];
  for (const x of m) items.push(['play', `Watch on ${x.ch.n}${x.why === 'guide' ? '  ·  in the guide' : ''}`, () => spWatch(x.ch)]);
  if (m.length && typeof lkMenuItem === 'function') items.push(...lkMenuItem(`Watch ${m[0].ch.n}`, { type: 'open', what: 'channel', u: m[0].ch.u, n: m[0].ch.n }));
  if (ev.state === 'pre' && m.length) {
    const best = m[0].ch;
    items.push(remHas(best.u, ev.at) ? ['bell', 'Cancel reminder', () => { remRemove(remId(best.u, ev.at)); spBuild(); }]
                                     : ['bell', `Remind me at ${fmtTime(ev.at)}`, () => { remAdd(best, [ev.at, ev.at + 3 * 3600000, `${away.name} @ ${home.name}`]); spBuild(); }]);
  }
  if (!m.length && ev.state !== 'post') items.push(['info', ev.nets.length ? `Not found in your channels · TV: ${ev.nets.join(', ')}` : 'No TV listing yet', () => {}]);
  if (ev.state === 'post') items.push(['info', `Final${ev.venue ? ' · ' + ev.venue : ''}`, () => {}]);
  showMenu(items, anchor, title);
}

function spMainEvent(ev) { return `${ev.teams[0].full} vs ${ev.teams[1].full}`; }
function spOpenCard(ev, anchor) {
  const m = ev.state === 'post' ? [] : spMatch(ev);
  const items = [];
  for (const x of m) items.push(['play', `Watch on ${x.ch.n}${x.why === 'guide' ? '  ·  in the guide' : ''}`, () => spWatch(x.ch)]);
  if (m.length && typeof lkMenuItem === 'function') items.push(...lkMenuItem(`Watch ${m[0].ch.n}`, { type: 'open', what: 'channel', u: m[0].ch.u, n: m[0].ch.n }));
  if (ev.state === 'pre' && m.length) {
    const best = m[0].ch;
    items.push(remHas(best.u, ev.at) ? ['bell', 'Cancel reminder', () => { remRemove(remId(best.u, ev.at)); spBuild(); }]
                                     : ['bell', `Remind me at ${fmtTime(ev.at)}`, () => { remAdd(best, [ev.at, ev.end, ev.name || ev.short]); spBuild(); }]);
  }
  if (!m.length && ev.state !== 'post') items.push(['info', ev.nets.length ? `Not found in your channels · ${ev.nets.join(', ')}` : 'No TV listing yet', () => {}]);
  // the fight card itself, main event first
  ev.bouts.forEach((b, i) => items.push(['info',
    `${i === 0 ? 'Main event' : b.cls || 'Bout'}${i === 0 && b.cls ? ' · ' + b.cls : ''}:  ${b.a} vs ${b.b}` + (b.live ? '  ·  LIVE' : b.result ? `  ·  ${b.result}` : ''), () => {}]));
  if (ev.venue) items.push(['info', ev.venue, () => {}]);
  showMenu(items, anchor, `${spLeague(ev.lg).icon} ${ev.name || ev.short} · ${spWhen(ev)}`);
}

/* remote: tabs ▸ scoreboard ▸ league badges ▸ the games */
function spSetTab(t) { SP.tabPicked = true; if (t === SP.tab) return; SP.tab = t; spBuild(); }
function spKeyDown(k, e) {
  if (!k) return;
  e.preventDefault();
  if (k === 'back') return spBack();
  if (k === 'ok') {
    if (e.repeat || SP.okDown) return;
    SP.okDown = true; SP.okLong = false;
    return;
  }
  const tabs = [...document.querySelectorAll('#sptabs .sptab')].map(x => x.dataset.tab);
  const nLg = (SP.sets && SP.sets.lgs.length) || 0, hero = !!$('#sprows .sphero');
  const row = SP.rows[SP.r], n = row ? row.items.length : 0, c = SP.cols[SP.r] || 0;
  if (SP.zone === 'tabs') {
    const i = tabs.indexOf(SP.tab);
    if (k === 'l' && i > 0) spSetTab(tabs[i - 1]);
    else if (k === 'r' && i < tabs.length - 1) spSetTab(tabs[i + 1]);
    else if (k === 'u') return enterTop();
    else if (k === 'd') SP.zone = hero ? 'hero' : nLg ? 'rail' : 'grid';
  } else if (SP.zone === 'hero') {
    if (k === 'u') SP.zone = 'tabs';
    else if (k === 'd') SP.zone = nLg ? 'rail' : 'grid';
  } else if (SP.zone === 'rail') {
    if (k === 'l' && SP.lgSel > 0) { SP.lgSel--; SP.tab = 'Today'; SP.tabPicked = true; spBuild(); }
    else if (k === 'r' && SP.lgSel < nLg - 1) { SP.lgSel++; SP.tab = 'Today'; SP.tabPicked = true; spBuild(); }
    else if (k === 'u') SP.zone = hero ? 'hero' : 'tabs';
    else if (k === 'd') SP.zone = 'grid';
  } else {
    // the games: across in a row; in a list (a phone held upright), up and down
    const list = body.classList.contains('portrait');
    if (k === 'u') { if (list && c > 0) SP.cols[SP.r] = c - 1; else SP.zone = nLg ? 'rail' : hero ? 'hero' : 'tabs'; }
    else if (k === 'd') { if (list && c < n - 1) SP.cols[SP.r] = c + 1; }
    else if (k === 'l') { if (c > 0) SP.cols[SP.r] = c - 1; }
    else if (k === 'r') { if (c < n - 1) SP.cols[SP.r] = c + 1; }
  }
  if (SP.zone === 'tabs' || SP.zone === 'rail') { document.querySelectorAll('#sptabs .sptab').forEach(x => x.classList.toggle('f', SP.zone === 'tabs' && x.dataset.tab === SP.tab)); }
  spMark();
}
function spKeyUp(k) {
  if (k !== 'ok' || !SP.okDown) return;
  SP.okDown = false;
  if (SP.zone === 'tabs' || SP.zone === 'rail') { SP.zone = 'grid'; spMark(); return; }
  if (SP.zone === 'hero') { const g = spGameById(SP.feat); if (g) spOpen(g, $('#sprows .sphero')); return; }
  spOk(document.querySelector('#sprows .gc.f'));
}
function spBack() {
  if (SP.zone !== 'grid') { SP.zone = 'grid'; spMark(); return; }
  const tabs = [...document.querySelectorAll('#sptabs .sptab')].map(x => x.dataset.tab);
  if (tabs.length && SP.tab !== tabs[0]) { spSetTab(tabs[0]); return; }
  if ($('#sprows').scrollTop > 10) { $('#sprows').scrollTo({ top: 0, behavior: 'smooth' }); return; }
  switchTab('home');
}
/* touch */
function spAt(t) {
  const g = t && t.closest ? t.closest('#sprows .gc') : null;
  if (!g) return null;
  SP.zone = 'grid';
  SP.r = +g.dataset.r; SP.cols[SP.r] = +g.dataset.c;
  return g;
}
function spTap(t) {
  if (!t || !t.closest) return;
  const tb = t.closest('#sptabs .sptab');
  if (tb) { SP.zone = 'grid'; spSetTab(tb.dataset.tab); return; }
  const bd = t.closest('#sprows .spbadge');
  if (bd) { SP.zone = 'grid'; SP.lgSel = +bd.dataset.lg; SP.tab = 'Today'; SP.tabPicked = true; spBuild(); return; }
  const hero = t.closest('#sprows .sphero');
  if (hero) { const g = spGameById(SP.feat); if (g) spOpen(g, hero); return; }
  if (t.closest('#sprows .spfy')) { if (SP.tab !== 'Fantasy') spSetTab('Fantasy'); else spFyOk(SP.fy.find(x => x.fyTeam) || SP.fy[0], t.closest('.spfy')); return; }
  const g = spAt(t);
  if (g) { spMark(); spOk(g); }
}

/* ---------------------------------------------------------------- picker (sports & teams) */

const PK = { r: -1, c: {}, rows: [], q: '', t: 0 };
function spPickOpen() {
  if (mode === 'top') exitTop();
  PK.from = mode === 'menu' ? baseMode() : mode;
  mode = 'spick';
  body.classList.add('spick-open');
  styleGlass($('#spick'));
  PK.q = ''; $('#spq').value = '';
  PK.r = 0; PK.c = {};
  spPickDraw();
  SP.prefs.leagues.forEach(id => spTeams(id).then(() => { if (mode === 'spick') spPickDraw(); }));
}
function spPickClose() {
  $('#spq').blur();
  body.classList.remove('spick-open');
  mode = PK.from && PK.from !== 'spick' ? PK.from : baseMode();
  spSave();
  SP.match = {};
  if (tab === 'sports') { spBuild(); spLoadAll(false); }
  refreshSettings();
}

function spPickDraw() {
  const q = PK.q.trim().toLowerCase();
  const rows = [];
  let h = '';
  const sec = (title, kind, items, html, note) => {
    if (!items.length) { if (note) h += `<div class="ssec"><div class="sst">${esc(title)}</div><div class="smsg">${note}</div></div>`; return; }
    const r = rows.length;
    rows.push({ kind, items });
    h += `<div class="ssec" data-r="${r}"><div class="sst">${esc(title)}</div><div class="sstrip">${items.map((it, c) => html(it, r, c)).join('')}</div></div>`;
  };
  sec('Your sports', 'league', SP_LEAGUES, (lg, r, c) =>
    `<div class="si spc${SP.prefs.leagues.includes(lg.id) ? ' on' : ''}" data-r="${r}" data-c="${c}"><span>${lg.icon}</span>${esc(lg.name)}<em>✓</em></div>`);
  for (const id of SP.prefs.leagues) {
    const lg = spLeague(id), favs = SP.prefs.teams[id] || [];
    const all = SP.teams[id];
    if (lg.cards) { h += `<div class="ssec"><div class="sst">${esc(lg.name)}</div><div class="smsg">${lg.icon} Following every ${esc(lg.name)} fight card — numbered events and Fight Nights show up in My teams, the score ticker and game alerts.</div></div>`; continue; }
    if (!all) { h += `<div class="ssec"><div class="sst">${esc(lg.name)} teams</div><div class="smsg">Loading teams…</div></div>`; continue; }
    let list = all;
    if (q) list = all.filter(t => (t.full + ' ' + t.name).toLowerCase().includes(q));
    else if (lg.college) list = all.filter(t => favs.some(f => f.id === t.id));
    list = list.slice().sort((a, b) => spIsFav(id, b.id) - spIsFav(id, a.id)).slice(0, lg.college ? 60 : 80);
    sec(`${lg.name} teams`, 'team:' + id, list, (t, r, c) =>
      `<div class="si stc${spIsFav(id, t.id) ? ' on' : ''}" data-r="${r}" data-c="${c}">` +
      (t.logo ? `<img src="${esc(t.logo)}" loading="lazy" onerror="this.style.visibility='hidden'">` : '<i></i>') +
      `<span>${esc(lg.college ? t.full : t.name)}</span><em>✓</em></div>`,
      lg.college && !q ? 'Type a school in the box above to find it' : q ? 'No matching teams' : '');
  }
  if (!SP.prefs.leagues.length) h += '<div class="smsg big">Choose one or more sports above, then pick your teams.</div>';
  PK.rows = rows;
  $('#spres').innerHTML = h;
  $('#spres').querySelectorAll('.sstrip').forEach(el => el.addEventListener('scroll', noteScroll, { passive: true }));
  if (PK.r >= rows.length) PK.r = rows.length - 1;
  spPickMark();
}
function spPickMark() {
  document.querySelectorAll('#spres .si.f').forEach(el => el.classList.remove('f'));
  $('#spickhead').classList.toggle('f', PK.r < 0 && mode === 'spick');
  if (PK.r < 0 || !PK.rows[PK.r]) return;
  const c = Math.min(PK.c[PK.r] || 0, PK.rows[PK.r].items.length - 1);
  const el = document.querySelector(`#spres .si[data-r="${PK.r}"][data-c="${c}"]`);
  if (!el) return;
  el.classList.add('f');
  const strip = el.parentElement, rem = remPx(), box = $('#spres'), sec = strip.parentElement;
  if (el.offsetLeft < strip.scrollLeft + rem * 2 || el.offsetLeft + el.offsetWidth > strip.scrollLeft + strip.clientWidth - rem * 2)
    strip.scrollTo({ left: Math.max(0, el.offsetLeft - rem * 2.6), behavior: 'smooth' });
  if (sec.offsetTop < box.scrollTop || sec.offsetTop + sec.offsetHeight > box.scrollTop + box.clientHeight)
    box.scrollTo({ top: Math.max(0, sec.offsetTop - rem), behavior: 'smooth' });
}
function spPickToggle(r, c) {
  const row = PK.rows[r];
  if (!row) return;
  const it = row.items[c];
  if (row.kind === 'league') {
    const L = SP.prefs.leagues;
    SP.prefs.leagues = L.includes(it.id) ? L.filter(x => x !== it.id) : SP_LEAGUES.map(l => l.id).filter(id => id === it.id || L.includes(id));
    if (SP.prefs.leagues.includes(it.id)) spTeams(it.id).then(() => { if (mode === 'spick') spPickDraw(); });
  } else {
    const id = row.kind.slice(5);
    const favs = SP.prefs.teams[id] || [];
    SP.prefs.teams[id] = favs.some(f => f.id === it.id) ? favs.filter(f => f.id !== it.id) : favs.concat([{ id: it.id, name: it.name, full: it.full, logo: it.logo }]);
  }
  spSave();
  spPickDraw();
}
(function wirePicker() {
  const inp = $('#spq');
  inp.addEventListener('input', () => { clearTimeout(PK.t); PK.t = setTimeout(() => { PK.q = inp.value; PK.c = {}; spPickDraw(); }, 200); });
  inp.addEventListener('focus', () => { PK.r = -1; spPickMark(); });
  $('#spres').addEventListener('scroll', noteScroll, { passive: true });
})();
function spPickKey(k, e) {
  const inp = $('#spq');
  if (PK.r < 0) {
    if (e.key === 'ArrowDown' || e.key === 'Enter') { e.preventDefault(); inp.blur(); if (PK.rows.length) { PK.r = 0; spPickMark(); } }
    else if (e.key === 'Escape') { e.preventDefault(); spPickClose(); }
    else if (document.activeElement !== inp && k === 'back') { e.preventDefault(); spPickClose(); }
    else if (document.activeElement !== inp && k === 'ok') { e.preventDefault(); inp.focus({ preventScroll: true }); }
    return;
  }
  if (!k) return;
  e.preventDefault();
  if (k === 'back') return spPickClose();
  if (k === 'ok') { if (!e.repeat) spPickToggle(PK.r, PK.c[PK.r] || 0); return; }
  const row = PK.rows[PK.r], n = row ? row.items.length : 0, c = PK.c[PK.r] || 0;
  if (k === 'u') { if (PK.r === 0) { PK.r = -1; spPickMark(); inp.focus({ preventScroll: true }); return; } PK.r--; }
  else if (k === 'd') { if (PK.r < PK.rows.length - 1) PK.r++; }
  else if (k === 'l') { if (c > 0) PK.c[PK.r] = c - 1; }
  else if (k === 'r') { if (c < n - 1) PK.c[PK.r] = c + 1; }
  spPickMark();
}
function spPickTap(t) {
  if (t.closest('#spdone') || !t.closest('#spick')) return spPickClose();
  if (t.closest('#spickhead')) { $('#spq').focus({ preventScroll: true }); return; }
  const el = t.closest('#spres .si');
  if (!el) { $('#spq').blur(); return; }
  $('#spq').blur();
  PK.r = +el.dataset.r; PK.c[PK.r] = +el.dataset.c;
  spPickToggle(PK.r, PK.c[PK.r]);
}


/* ---------------------------------------------------------------- your teams, everywhere:
   a live score ticker on Home, a heads-up when one of your teams scores, and an alert
   (pop-up, or a notification when TopShelf isn't open) shortly before their games start. */

const TK = { items: [], i: 0, t: 0, scores: readJSON('spScores', {}), alerted: new Set(readJSON('spAlerted', [])) };
function spFavLeagues() { return SP.prefs.leagues.filter(id => (SP.prefs.teams[id] || []).length || (spLeague(id) || {}).cards); }

/* Where TopShelf is, as the app sees it: 'away' (not on screen), 'home', 'live' (Live TV) or 'quiet'
   (Movies, TV Shows, Sports, a film or episode playing: no ticker, no score pop-ups). Without the
   app's own Home it's just whether this page is on screen. */
function spWhere() {
  let w = '';
  try { w = B.nhWhere ? String(B.nhWhere() || '') : ''; } catch (e) {}
  return w || (document.hidden ? 'away' : 'home');
}
TK.sig = {}; TK.hold = 0;
const SP_FLASH_MS = 9000;
/* what's worth a look on Live TV: a score, kick-off, a quarter / half / period ending, the final,
   (fight night) the next bout starting */
function spSig(e) {
  const brk = e.card ? (e.now ? e.now.vs : '') : /^(end|half|final|start|ot\b|overtime)/i.test(e.detail || '') ? e.detail : '';
  return { st: e.state, sc: e.teams.map(t => t.score).join('-'), brk };
}

async function spTick() {
  const lgs = spFavLeagues();
  if (!lgs.length || spWhere() === 'away') { spTicker([]); return; }
  await Promise.all(lgs.map(id => spLoadLeague(id, false)));
  const now = Date.now();
  const mine = lgs.flatMap(id => SP.ev[id] || []).filter(spFavGame);
  // ticker: live games, games starting within 30 minutes, finals from the last 90 minutes
  const show = mine.filter(e => e.state === 'in' || (e.state === 'pre' && e.at - now < 30 * 60000 && e.at - now > -30 * 60000) || (e.state === 'post' && now - e.at < 4.5 * 3600000 && now - (SP.endSeen[e.id] || now) < 90 * 60000));
  mine.filter(e => e.state === 'post' && !SP.endSeen[e.id]).forEach(e => { SP.endSeen[e.id] = now; });
  show.sort((a, b) => ({ in: 0, pre: 1, post: 2 }[a.state] - { in: 0, pre: 1, post: 2 }[b.state]) || a.at - b.at);
  // what changed since the last look (nothing the first time a game's seen)
  let flash = null;
  const watching = spWatchingId();       // (the game you're watching: you've just seen it happen)
  for (const e of show) {
    const s = spSig(e), p = TK.sig[e.id];
    if (p && !flash && String(e.id) !== watching && (p.st !== s.st || p.sc !== s.sc || (s.brk && p.brk !== s.brk))) flash = e;
    TK.sig[e.id] = s;
  }
  if (flash) { TK.i = show.indexOf(flash); TK.hold = now + SP_FLASH_MS; }
  spTicker(show);
  // on Live TV the ticker isn't kept up: it shows for a few seconds when something happens
  if (flash && spWhere() === 'live') { try { B.nhTickerFlash && B.nhTickerFlash(SP_FLASH_MS); } catch (e) {} }
  spScoreAlerts(mine);
  spGameAlerts(mine);
  if (tab === 'sports') spBuild();
}
SP.endSeen = {};

function spTickerText(e) {
  const [a, h] = e.teams;
  const lg = spLeague(e.lg);
  if (e.card) return `<span class="tki">${lg ? lg.icon : ''}</span><b>${esc(e.short)}</b>` +
    `<span class="tkm">${esc(e.state === 'in' && e.now ? e.now.vs : e.state === 'post' ? (e.bouts[0] && e.bouts[0].result) || spMainEvent(e) : spMainEvent(e))}</span>` +
    `<span class="tkw${e.state === 'in' ? ' live' : ''}">${esc(e.state === 'in' ? (e.now ? e.now.detail : 'Live') : e.state === 'post' ? 'Final' : 'Starts ' + fmtTime(e.at))}</span>`;
  const score = e.state === 'pre' ? `${a.name} @ ${h.name}` : `${a.name} ${a.score} – ${h.score} ${h.name}`;
  const when = e.state === 'in' ? e.detail : e.state === 'post' ? 'Final' : `Starts ${fmtTime(e.at)}`;
  return `<span class="tki">${lg ? lg.icon : ''}</span>` +
    (a.logo ? `<img src="${esc(a.logo)}">` : '') + `<b>${esc(score)}</b>` + (h.logo ? `<img src="${esc(h.logo)}">` : '') +
    `<span class="tkw${e.state === 'in' ? ' live' : ''}">${esc(when)}</span>`;
}
function spTicker(list) {
  TK.items = list;
  const el = $('#ticker');
  body.classList.toggle('has-ticker', list.length > 0);
  clearInterval(TK.t);
  if (!list.length) { el.innerHTML = ''; return; }
  TK.i = Math.min(TK.i, list.length - 1);
  const draw = () => {
    const e = TK.items[TK.i % TK.items.length];
    if (!e) return;
    el.innerHTML = `<div class="tkr">${spTickerText(e)}</div>` + (TK.items.length > 1 ? `<i class="tkn">${(TK.i % TK.items.length) + 1}/${TK.items.length}</i>` : '');
    styleGlass(el);
  };
  draw();
  if (list.length > 1) TK.t = setInterval(() => { if (Date.now() < TK.hold) return; TK.i++; draw(); }, 6000);   // (held on a game that just changed)
}
$('#ticker').addEventListener('pointerup', () => { if (TK.items.length && tab === 'home' && mode === 'grid') switchTab('sports'); });

/* "Rams score!" — when one of your teams' scores goes up while you're in TopShelf. */
function spWatchingId() { try { return B.liveWatchingGame ? String(B.liveWatchingGame() || '') : ''; } catch (e) { return ''; } }
/* the app's sports notification at the top middle (the page's own menu / toast when the app's Home isn't on) */
function spAlertNative(o, okFn) {
  try {
    if (typeof NH !== 'undefined' && NH.on && B.nhGameAlert) { window.nhAlertOk = okFn || null; B.nhGameAlert(JSON.stringify(o)); return true; }
  } catch (e) {}
  return false;
}
const spEnd = t => ({ abbr: t.abbr || t.name || '', color: t.color || '', logo: t.logo || '' });
function spScoreAlerts(mine) {
  let changed = false;
  const watching = spWatchingId();       // (no "score!" for the game you're watching)
  const where = spWhere();               // (on Live TV the ticker shows the score instead; nothing over Movies, TV Shows or a film)
  for (const e of mine) {
    if ((e.state !== 'in' && e.state !== 'post') || e.card) continue;
    const key = e.id, cur = e.teams.map(t => +t.score || 0), prev = TK.scores[key];
    if (prev && e.state === 'in') {
      e.teams.forEach((t, i) => {
        if (cur[i] > prev[i] && spIsFav(e.lg, t.id) && S.gameAlerts !== false && where === 'home' && String(e.id) !== watching) {
          const m = spMatch(e)[0], a = e.teams[0], b = e.teams[1];
          const shown = spAlertNative({ kind: 'score', kicker: `${spLeague(e.lg).name.toUpperCase()}  ·  ${String(t.name).toUpperCase()} SCORE`,
            headline: `${a.abbr || a.name}  ${cur[0]}  –  ${cur[1]}  ${b.abbr || b.name}`, detail: e.detail || '', teams: [spEnd(a), spEnd(b)],
            hint: m ? `OK  ·  Watch on ${m.ch.n}` : '' }, m ? () => spWatch(m.ch) : null);
          if (!shown) toast(`${spLeague(e.lg).icon} ${t.name} score! ${a.name} ${cur[0]} – ${cur[1]} ${b.name}`);
        }
      });
    }
    spRedZone(e, cur, watching, where);
    if (!prev || prev[0] !== cur[0] || prev[1] !== cur[1]) { TK.scores[key] = cur; changed = true; }
  }
  if (changed) {
    const keep = {}; mine.forEach(e => { if (TK.scores[e.id]) keep[e.id] = TK.scores[e.id]; });
    TK.scores = keep; writeJSON('spScores', keep);
  }
}

/* Red zone: one of your teams has the ball inside the 20 (football) — once per drive, with OK to switch to
   the game. A drive is the game, the team with the ball and the score (a score or a turnover starts a new one). */
const SP_RED = new Set();
function spRedZone(e, cur, watching, where) {
  if (e.state !== 'in' || !e.red || !e.poss || S.gameAlerts === false || S.redZone === false) return;
  const t = e.teams.find(x => x.id === e.poss);
  if (!t || !spIsFav(e.lg, t.id) || String(e.id) === watching || where !== 'home') return;
  const key = `${e.id}:${e.poss}:${cur.join('-')}`;
  if (SP_RED.has(key)) return;
  SP_RED.add(key);
  const m = spMatch(e)[0], a = e.teams[0], b = e.teams[1];
  const shown = spAlertNative({ kind: 'red', kicker: `${spLeague(e.lg).name.toUpperCase()}  ·  RED ZONE`, kickerColor: '#FF5A4E',
    headline: `${t.name} inside the 20`, detail: `${a.abbr || a.name} ${cur[0]} – ${cur[1]} ${b.abbr || b.name}${e.dd ? '  ·  ' + e.dd : ''}${e.detail ? '  ·  ' + e.detail : ''}`,
    teams: [spEnd(a), spEnd(b)], hint: m ? `OK  ·  Watch on ${m.ch.n}` : '' }, m ? () => spWatch(m.ch) : null);
  if (!shown) toast(`🏈 ${t.name} in the red zone · ${a.name} ${cur[0]} – ${cur[1]} ${b.name}`);
}

/* A few minutes before your team plays: a pop-up with Watch buttons (and a notification if
   TopShelf isn't on screen — scheduled with Android so it arrives even when closed). */
function spGameAlerts(mine) {
  if (S.gameAlerts === false) return;
  const now = Date.now(), watching = spWatchingId();
  for (const e of mine) {
    if (e.state !== 'pre' || e.at - now > 24 * 3600000 || e.at < now - 10 * 60000) continue;
    const best = spMatch(e)[0];
    const fav = e.teams.find(t => spIsFav(e.lg, t.id)) || e.teams[1];
    const title = e.card ? `${e.short} starts soon` : `${fav.name} ${spLeague(e.lg).name} game starts soon`;
    const vs = e.card ? spMainEvent(e) : `${e.teams[0].name} @ ${e.teams[1].name}`;
    const text = `${vs} · ${fmtTime(e.at)}${best ? ' on ' + best.ch.n : e.nets.length ? ' · ' + e.nets[0] : ''}`;
    try { B.setReminder && B.setReminder('g' + e.id, e.at - 3 * 60000, title, text, best ? best.ch.u : ''); } catch (x) {}
    const where = spWhere();
    if (e.at - now <= 5 * 60000 && !TK.alerted.has(e.id) && (where === 'home' || where === 'live') && !['edit', 'menu', 'player'].includes(mode) && String(e.id) !== watching) {
      TK.alerted.add(e.id);
      writeJSON('spAlerted', Array.from(TK.alerted).slice(-60));
      const m = spMatch(e), lgn = spLeague(e.lg).name;
      const shown = spAlertNative({ kind: 'soon', kicker: `${lgn.toUpperCase()}  ·  STARTING SOON`,
        headline: e.card ? e.short : `${e.teams[0].name} @ ${e.teams[1].name}`,
        detail: `${fmtTime(e.at)}${m.length ? ' · on ' + m[0].ch.n : e.nets.length ? ' · ' + e.nets.slice(0, 2).join(', ') : ''}`,
        teams: e.card ? null : e.teams.map(spEnd), word: e.card ? lgn.toUpperCase() : '',
        hint: m.length ? `OK  ·  Watch on ${m[0].ch.n}` : 'OK  ·  See it in Sports' }, m.length ? () => spWatch(m[0].ch) : () => switchTab('sports'));
      if (!shown) showMenu(m.slice(0, 3).map(x => ['play', `Watch on ${x.ch.n}`, () => spWatch(x.ch)])
        .concat(m.length ? [] : [['open', `See it in Sports${e.nets.length ? ' · TV: ' + e.nets.slice(0, 2).join(', ') : ''}`, () => switchTab('sports')]])
        .concat([['hide', 'Dismiss', () => {}]]), null, `${spLeague(e.lg).icon} Starting soon · ${e.card ? e.short + ' · ' : ''}${vs} · ${fmtTime(e.at)}`);
      if (!shown && mode === 'menu') $('#menu').classList.add('alert');
    }
  }
}

// your teams are checked in the background while TopShelf is open: every minute while one of
// their games is live (or about to start), otherwise every 10 minutes
(function spBackground() {
  const loop = () => {
    const now = Date.now();
    const busy = spFavLeagues().some(id => (SP.ev[id] || []).some(e => spFavGame(e) && (e.state === 'in' || (e.state === 'pre' && e.at - now < 20 * 60000))));
    spTick().finally(() => setTimeout(loop, busy ? 60000 : 10 * 60000));
  };
  setTimeout(loop, 6000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) spTick(); });
})();
window.spTick = spTick;

/* ---------------------------------------------------------------- live stats while watching
   The TV asks which game a channel is showing (spGameFor). Looked for in every league's games that
   are on now (or about to be), using the same matching as the Sports tab: the TV guide, event
   channels named after the teams, and the game's TV networks. Answers B.nhStatsGame(req, json). */
SP.watch = SP.watch || {};      // league → { t, events (as spEvent/spCard give them, plus the raw event) }
async function spWatchLeague(lg) {
  const w = SP.watch[lg.id];
  if (w && Date.now() - w.t < 3 * 60000) return w.events;
  let raw = [];
  try { raw = ((await spGet(spScoreUrl(lg, lg.cards ? '' : spYmd(new Date())))).events) || []; } catch (e) { raw = w ? w.raw : []; }
  const events = raw.map(e => { const ev = lg.cards ? spCard(lg, e) : spEvent(lg, e); if (ev) ev.raw = e; return ev; }).filter(Boolean);
  SP.watch[lg.id] = { t: Date.now(), events, raw };
  return events;
}
async function spGameFor(u, req) {
  let best = null;
  try {
    const now = Date.now();
    const lists = await Promise.all(SP_LEAGUES.map(lg => spWatchLeague(lg).then(evs => ({ lg, evs })).catch(() => ({ lg, evs: [] }))));
    for (const { lg, evs } of lists) for (const ev of evs) {
      const near = ev.state === 'in' || (ev.state === 'pre' && ev.at - now < 45 * 60000) || (ev.state === 'post' && now - (ev.end || ev.at + 4 * 3600000) < 30 * 60000);
      if (!near) continue;
      const m = spMatch(ev).find(x => x.ch.u === u);
      if (!m) continue;
      const score = m.score + (ev.state === 'in' ? 2 : 0);
      if (!best || score > best.score) best = { score, lg, ev };
    }
  } catch (e) {}
  let out = null;
  if (best) {
    const { lg, ev } = best;
    out = { id: ev.id, lg: lg.id, lgName: lg.name, sport: lg.sport, league: lg.league, name: ev.name, short: ev.short, card: !!ev.card, state: ev.state,
            teams: ev.teams.map(t => ({ id: t.id, name: t.name, full: t.full, abbr: t.abbr, color: t.color, score: t.score, rec: t.rec, logo: t.logo })) };
    if (ev.card && ev.raw) {
      // the bouts of the card, latest first, with each fighter's id (the TV fetches their profiles)
      const st = s => ((s || {}).type || {});
      out.bouts = (ev.raw.competitions || []).slice().sort((a, b) => Date.parse(b.startDate || b.date) - Date.parse(a.startDate || a.date)).slice(0, 14).map(c => ({
        id: String(c.id), cls: (c.type && (c.type.abbreviation || c.type.text)) || '', state: st(c.status).state || 'pre', detail: st(c.status).shortDetail || '',
        fighters: (c.competitors || []).map(x => { const a = x.athlete || {}; return { id: String(a.id || ''), name: a.displayName || a.fullName || '?', rec: x.records && x.records[0] ? x.records[0].summary : '', flag: (a.flag && a.flag.href) || '', winner: !!x.winner }; })
      }));
    }
  }
  try { B.nhStatsGame(String(req), out ? JSON.stringify(out) : ''); } catch (e) {}
}
window.spGameFor = spGameFor;

/* Yahoo Fantasy alerts: which of your channels is showing this game (for "OK · Watch") */
async function spChannelForGame(lgId, id, req) {
  let u = '';
  try {
    const lg = spLeague(lgId);
    const evs = lg ? await spWatchLeague(lg) : [];
    const ev = evs.find(e => String(e.id) === String(id));
    if (ev) { const m = spMatch(ev); if (m.length) u = m[0].ch.u; }
  } catch (e) {}
  try { B.fantasyWatch(String(req), u); } catch (e) {}
}
window.spChannelForGame = spChannelForGame;
