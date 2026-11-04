/* Backup & restore (to a file, or straight from your phone to your TV) and Bedtime mode. */

ICONS.save = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12M7 10l5 5 5-5M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>';
ICONS.restore = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21V9M7 14l5-5 5 5M4 7V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2"/></svg>';
ICONS.moon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z"/></svg>';

// things that only make sense on the device they were set up on
const BK_DEVICE_ONLY = ['order', 'hidden', 'icons', 'liveLast', 'reminders', 'mvWarned', 'liveCat'];

function bkData(forOtherDevice) {
  let d;
  try { d = JSON.parse(B.backupData()); } catch (e) { d = null; }
  if (!d || !d.ui) return null;
  if (forOtherDevice) BK_DEVICE_ONLY.forEach(k => delete d.ui[k]);
  return d;
}
function bkSummary(d) {
  const n = k => { try { const v = JSON.parse(d.ui[k] || 'null'); return Array.isArray(v) ? v.length : v && v.teams ? Object.values(v.teams).reduce((a, l) => a + l.length, 0) : 0; } catch (e) { return 0; } };
  const parts = [];
  if (n('favs')) parts.push(`${n('favs')} favorite channels`);
  if (n('mylist')) parts.push(`${n('mylist')} in My List`);
  if (n('sports')) parts.push(`${n('sports')} teams`);
  if (d.m3u) parts.push('Live TV playlist');
  return parts.join(' · ') || 'settings';
}
function bkWhen(d) {
  const t = new Date(d.at || 0);
  return isNaN(t) ? '' : t.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ' ' + fmtTime(t.getTime());
}

function bkSave() {
  const d = bkData(false);
  if (!d) return toast("Couldn't read your settings");
  const r = B.backupSave(JSON.stringify(d, null, 1));
  if (!r || r[0] === '!') return toast(`Couldn't save the backup${r ? ': ' + r.slice(1) : ''}`);
  toast(`Backup saved to ${r.startsWith('/') ? 'TopShelf\'s folder' : r} — it includes your Live TV login, keep it private`);
}

function bkRestorePick() {
  try { B.backupPick(); } catch (e) { toast('Restoring needs the latest TopShelf'); }
}
window.tsBackupFile = (text, how) => {
  if (!text) {
    if (how === 'nopicker') toast('No backup found on this device. Save one first, or send your settings from your phone (Phone & TV link)');
    else if (how === 'unreadable') toast("Couldn't read that file");
    return;
  }
  let d;
  try { d = JSON.parse(text); } catch (e) { d = null; }
  if (!d || d.app !== 'TopShelf' || !d.ui) return toast("That isn't a TopShelf backup");
  bkConfirm(d, how === 'latest' ? 'Latest backup' : 'Backup', false);
};

/* ask before replacing anything */
function bkConfirm(d, label, fromOther) {
  if (mode === 'settings') closeSettings();
  if (mode === 'menu') closeMenu();
  const other = fromOther || (d.tv !== undefined && !!d.tv !== IS_TV);
  if (other) BK_DEVICE_ONLY.forEach(k => delete d.ui[k]);
  showMenu([
    ['restore', 'Replace my settings with these', () => bkApply(d)],
    ['info', bkSummary(d), () => setTimeout(() => bkConfirm(d, label, fromOther), 50)],
    ['hide', 'Cancel', () => {}]
  ], null, `${label} · ${d.device || 'TopShelf'}${bkWhen(d) ? ' · ' + bkWhen(d) : ''}`);
  if (mode === 'menu') $('#menu').classList.add('alert');
}

function bkApply(d) {
  const keepLive = liveInfo();
  if (!B.backupApply(JSON.stringify(d))) return toast("Couldn't restore that backup");
  if (d.m3u && (d.m3u !== keepLive.m3u || (d.epg || '') !== (keepLive.epg || ''))) {
    try { B.liveSetUrls(d.m3u, d.epg || ''); B.liveRefresh && B.liveRefresh(); } catch (e) {}
  }
  toast('Settings restored');
  setTimeout(() => bkReload(), 900);
}

/* phone → TV: send everything over the link; the TV asks before it replaces anything */
async function bkSendToTv() {
  const d = bkData(true);
  if (!d) return;
  const r = await lkSend({ type: 'restore', data: d, from: d.device || 'your phone' });
  if (r) toast(`Check ${lkTvName()} to confirm`);
}
window.bkFromLink = (d, from) => {
  if (!d || !d.ui) return '';
  d.device = from || d.device;
  setTimeout(() => bkConfirm(d, 'Settings from', true), 0);
  return JSON.stringify({ ok: true });
};

function bkReload() { location.reload(); }

function bkMenu() {
  showMenu([
    ['save', 'Save a backup', bkSave],
    ['restore', IS_TV ? 'Restore the latest backup' : 'Restore from a file…', bkRestorePick],
    ...(!IS_TV && typeof lkOn === 'function' && lkOn() ? [['tv', `Copy all settings to ${lkTvName()}`, bkSendToTv]] : [])
  ], null, 'Backup & restore');
}

/* ---------------------------------------------------------------- Bedtime

   From the time you pick until 6 AM: the screen gets warmer and dimmer, the screensaver starts
   sooner, and Live TV turns itself off after an hour unless you've set your own sleep timer. */

const BED_TIMES = [[0, 'Off'], [21, 'From 9 PM'], [22, 'From 10 PM'], [23, 'From 11 PM'], [24, 'From midnight']];
function bedStart() { return S.bedtime || 0; }
function bedNow() {
  const h = bedStart();
  if (!h) return false;
  const now = new Date(), hr = now.getHours() + now.getMinutes() / 60;
  return h === 24 ? hr < 6 : (hr >= h || hr < 6);
}
function bedApply() {
  const on = bedNow();
  if (on === body.classList.contains('bedtime')) return;
  body.classList.toggle('bedtime', on);
  if (on && !document.hidden) toast('Bedtime: warmer, dimmer screen until 6 AM');
}
setInterval(bedApply, 60000);
setTimeout(bedApply, 1500);
document.addEventListener('visibilitychange', () => { if (!document.hidden) bedApply(); });

// the screensaver comes on after 2 minutes at bedtime
(function () {
  const orig = saverMinutes;
  saverMinutes = () => { const m = orig(); return bedNow() && m ? Math.min(m, 2) : m; };
})();

// Live TV: an automatic one-hour sleep timer when you start watching at bedtime
(function () {
  const orig = lvPlay;
  lvPlay = (ch, full) => {
    orig(ch, full);
    if (full && bedNow() && !SLEEP.at && mode === 'player') {
      SLEEP.at = Date.now() + 60 * 60000;
      clearTimeout(SLEEP.t);
      SLEEP.t = setTimeout(sleepNow, 60 * 60000);
      toast("Bedtime: Live TV will stop in 1 hour (hold OK to change)");
    }
  };
})();
