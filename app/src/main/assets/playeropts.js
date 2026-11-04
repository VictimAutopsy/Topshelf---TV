/* Live TV player options: audio language, subtitles / closed captions, picture quality and a
   live "stream info" panel (resolution, codecs, bitrate, buffer, dropped frames). */

ICONS.audio = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11"/></svg>';
ICONS.cc = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="14" rx="3"/><path d="M10.5 10.2a2.4 2.4 0 1 0 0 3.6M17 10.2a2.4 2.4 0 1 0 0 3.6"/></svg>';
ICONS.hd = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="5" width="19" height="14" rx="3"/><path d="M7 9v6M11 9v6M7 12h4M14.5 9v6h1.5a3 3 0 0 0 0-6z"/></svg>';
ICONS.stats = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg>';

const PO = { subLang: readJSON('liveSubLang', ''), audLang: readJSON('liveAudLang', ''), stats: false, statsT: 0 };

const LANGS = { en: 'English', eng: 'English', es: 'Spanish', spa: 'Spanish', fr: 'French', fre: 'French', fra: 'French', de: 'German', ger: 'German', deu: 'German',
  it: 'Italian', ita: 'Italian', pt: 'Portuguese', por: 'Portuguese', ar: 'Arabic', ara: 'Arabic', hi: 'Hindi', hin: 'Hindi', ru: 'Russian', rus: 'Russian',
  zh: 'Chinese', chi: 'Chinese', zho: 'Chinese', ja: 'Japanese', jpn: 'Japanese', ko: 'Korean', kor: 'Korean', nl: 'Dutch', dut: 'Dutch', nld: 'Dutch',
  pl: 'Polish', pol: 'Polish', tr: 'Turkish', tur: 'Turkish', sv: 'Swedish', swe: 'Swedish', el: 'Greek', gre: 'Greek', ell: 'Greek', und: '' };
function langName(code, fallback) {
  const c = String(code || '').toLowerCase().split(/[-_]/)[0];
  return LANGS[c] || fallback || (c ? c.toUpperCase() : '');
}

/* ---- audio ---- */
function poAudioTracks() {
  if (lvHls && lvHls.audioTracks && lvHls.audioTracks.length > 1)
    return lvHls.audioTracks.map((t, i) => ({ i, name: t.name && !/^(audio|stereo|default|\d+)$/i.test(t.name) ? t.name : langName(t.lang, `Track ${i + 1}`), lang: t.lang || '', on: i === lvHls.audioTrack }));
  const v = lvVideo();
  if (v.audioTracks && v.audioTracks.length > 1)
    return [...v.audioTracks].map((t, i) => ({ i, name: t.label || langName(t.language, `Track ${i + 1}`), lang: t.language || '', on: t.enabled }));
  return [];
}
function poSetAudio(i) {
  const list = poAudioTracks(), t = list[i];
  if (!t) return;
  if (lvHls && lvHls.audioTracks && lvHls.audioTracks.length > 1) lvHls.audioTrack = i;
  else { const v = lvVideo(); [...v.audioTracks].forEach((a, j) => { a.enabled = j === i; }); }
  PO.audLang = t.lang; writeJSON('liveAudLang', t.lang);
  toast(`Audio: ${t.name}`);
}
function poAudioMenu() {
  const list = poAudioTracks();
  if (!list.length) return showMenu([['info', "This channel has one audio track", () => {}]], null, 'Audio');
  showMenu(list.map(t => [t.on ? 'check' : 'audio', t.name, () => poSetAudio(t.i)]), null, 'Audio');
}

/* ---- subtitles & closed captions ---- */
function poTextTracks() {
  return [...lvVideo().textTracks].filter(t => (t.kind === 'subtitles' || t.kind === 'captions') && !/^metadata/.test(t.kind));
}
function poSubName(t, i) {
  const base = t.label && !/^(subtitles|captions|\d+)$/i.test(t.label) ? t.label : langName(t.language, t.kind === 'captions' ? `CC${i + 1}` : `Subtitles ${i + 1}`);
  return t.kind === 'captions' && !/cc/i.test(base) ? base + ' (CC)' : base;
}
function poSetSub(i) {
  const tracks = poTextTracks();
  tracks.forEach((t, j) => { t.mode = j === i ? 'showing' : 'disabled'; });
  if (lvHls) { try { lvHls.subtitleDisplay = i >= 0; } catch (e) {} }
  const t = tracks[i];
  PO.subLang = t ? (t.language || t.label || 'on') : '';
  writeJSON('liveSubLang', PO.subLang);
  toast(t ? `Subtitles: ${poSubName(t, i)}` : 'Subtitles off');
}
function poSubMenu() {
  const tracks = poTextTracks();
  if (!tracks.length) return showMenu([['info', "No subtitles or captions on this channel right now", () => {}]], null, 'Subtitles & captions');
  const on = tracks.findIndex(t => t.mode === 'showing');
  showMenu([[on < 0 ? 'check' : 'hide', 'Off', () => poSetSub(-1)]].concat(tracks.map((t, i) => [i === on ? 'check' : 'cc', poSubName(t, i), () => poSetSub(i)])), null, 'Subtitles & captions');
}
// re-apply the last choice when a new channel's tracks show up
(function () {
  const v = lvVideo();
  const apply = () => {
    if (!PO.subLang) return;
    const tracks = poTextTracks();
    const i = tracks.findIndex(t => (t.language || t.label || 'on') === PO.subLang);
    const j = i >= 0 ? i : (PO.subLang === 'on' ? 0 : -1);
    if (j >= 0 && tracks[j].mode !== 'showing') tracks.forEach((t, k) => { t.mode = k === j ? 'showing' : 'disabled'; });
  };
  v.textTracks.addEventListener('addtrack', () => setTimeout(apply, 50));
  PO.applySubs = apply;
})();

/* ---- picture quality (HLS channels that offer several) ---- */
function poLevels() {
  if (!lvHls || !lvHls.levels || lvHls.levels.length < 2) return [];
  return lvHls.levels.map((l, i) => ({ i, h: l.height || 0, br: l.bitrate || 0 })).sort((a, b) => b.h - a.h || b.br - a.br);
}
function poLevelName(l) { return (l.h ? l.h + 'p' : 'Level') + (l.br ? ` · ${(l.br / 1e6).toFixed(1)} Mbps` : ''); }
function poQualityMenu() {
  const ls = poLevels();
  if (!ls.length) return showMenu([['info', 'This channel comes in one quality', () => {}]], null, 'Quality');
  const auto = lvHls.autoLevelEnabled;
  showMenu([[auto ? 'check' : 'hd', `Auto${auto && lvHls.levels[lvHls.currentLevel] ? ' · now ' + (lvHls.levels[lvHls.currentLevel].height || '?') + 'p' : ''}`, () => { lvHls.currentLevel = -1; toast('Quality: Auto'); }]]
    .concat(ls.map(l => [!auto && lvHls.currentLevel === l.i ? 'check' : 'hd', poLevelName(l), () => { lvHls.currentLevel = l.i; toast('Quality: ' + poLevelName(l)); }])), null, 'Quality');
}

/* ---- stream info panel ---- */
function poStatsToggle(on) {
  PO.stats = on == null ? !PO.stats : on;
  $('#player').classList.toggle('stats', PO.stats);
  clearInterval(PO.statsT);
  if (PO.stats) { poStatsDraw(); PO.statsT = setInterval(poStatsDraw, 1000); }
}
function poStatsDraw() {
  const el = $('#pstats');
  if (!LV.playing || !PO.stats) { el.innerHTML = ''; return; }
  const v = lvVideo(), rows = [];
  const add = (k, val) => { if (val !== '' && val != null) rows.push(`<div><span>${esc(k)}</span><b>${esc(String(val))}</b></div>`); };
  const kind = lvHls ? 'HLS' : lvMts ? 'MPEG-TS' : v.currentSrc ? 'Direct' : '—';
  add('Channel', LV.playing.n);
  add('Stream', kind + (LV.tried === 'hls' && lvKind(LV.playing.u) !== 'hls' ? ' (fallback)' : ''));
  add('Resolution', v.videoWidth ? `${v.videoWidth}×${v.videoHeight}` : 'waiting…');
  if (lvHls) {
    const l = lvHls.levels && lvHls.levels[lvHls.currentLevel];
    if (l) {
      add('Codecs', [l.videoCodec, l.audioCodec].filter(Boolean).join(' / '));
      if (l.frameRate) add('Frame rate', `${Math.round(l.frameRate)} fps`);
      add('Bitrate', l.bitrate ? `${(l.bitrate / 1e6).toFixed(2)} Mbps` : '');
    }
    if (lvHls.bandwidthEstimate) add('Connection', `${(lvHls.bandwidthEstimate / 1e6).toFixed(1)} Mbps`);
    if (lvHls.levels && lvHls.levels.length > 1) add('Quality', lvHls.autoLevelEnabled ? `Auto (${lvHls.levels.length} levels)` : 'Fixed');
  } else if (lvMts) {
    const mi = lvMts.mediaInfo || {}, si = lvMts.statisticsInfo || {};
    add('Codecs', [mi.videoCodec, mi.audioCodec].filter(Boolean).join(' / '));
    if (mi.fps) add('Frame rate', `${Math.round(mi.fps)} fps`);
    if (si.speed) add('Speed', `${(si.speed * 8 / 1000).toFixed(2)} Mbps`);
  }
  try {
    const b = v.buffered;
    if (b.length) add('Buffer', `${Math.max(0, b.end(b.length - 1) - v.currentTime).toFixed(1)} s`);
  } catch (e) {}
  try {
    const q = v.getVideoPlaybackQuality && v.getVideoPlaybackQuality();
    if (q) add('Dropped frames', `${q.droppedVideoFrames} of ${q.totalVideoFrames}`);
  } catch (e) {}
  const aud = poAudioTracks().find(t => t.on);
  if (aud) add('Audio', aud.name);
  const sub = poTextTracks().find(t => t.mode === 'showing');
  if (sub) add('Subtitles', poSubName(sub, 0));
  el.innerHTML = `<h4>Stream info</h4>${rows.join('')}`;
}

/* entries for the full-screen player's menu */
function poMenuItems() {
  const out = [];
  const aud = poAudioTracks();
  if (aud.length) out.push(['audio', `Audio · ${(aud.find(t => t.on) || aud[0]).name}`, () => setTimeout(poAudioMenu, 50)]);
  const subs = poTextTracks(), on = subs.find(t => t.mode === 'showing');
  out.push(['cc', `Subtitles · ${on ? poSubName(on, subs.indexOf(on)) : subs.length ? 'Off' : 'none'}`, () => setTimeout(poSubMenu, 50)]);
  if (poLevels().length) out.push(['hd', `Quality · ${lvHls.autoLevelEnabled ? 'Auto' : poLevelName(poLevels().find(l => l.i === lvHls.currentLevel) || {})}`, () => setTimeout(poQualityMenu, 50)]);
  out.push(['stats', PO.stats ? 'Hide stream info' : 'Stream info', () => poStatsToggle()]);
  return out;
}

// HLS audio: keep the language you picked last time
setInterval(() => {
  if (!lvHls || !PO.audLang || !lvHls.audioTracks || lvHls.audioTracks.length < 2) return;
  const cur = lvHls.audioTracks[lvHls.audioTrack];
  if (cur && cur.lang === PO.audLang) return;
  const i = lvHls.audioTracks.findIndex(t => t.lang === PO.audLang);
  if (i >= 0 && !lvHls.__audSet) { lvHls.__audSet = true; lvHls.audioTrack = i; }
}, 2000);
