/* TopShelf updates and diagnostic reports through your private GitHub repository.
   With an access token (limited to that one repository) TopShelf:
   - checks for a newer version a little after starting and every few hours, and installs it when
     you choose (Android shows one confirmation);
   - uploads each diagnostic report there automatically.
   The token is typed on the phone (Settings → TopShelf updates) and sent to the TV over the link. */

const UPD = { info: {}, latest: null, manual: false, notified: readJSON('updNotified', 0), lastCheck: 0 };

function updInfo() { try { UPD.info = JSON.parse(B.updInfo ? B.updInfo() : '{}') || {}; } catch (e) { UPD.info = {}; } return UPD.info; }
function updReady() { return !!(B.updInfo && updInfo().token); }

function updCheck(manual) {
  if (!updReady()) { if (manual) updSetupSheet(); return; }
  UPD.manual = !!manual;
  UPD.lastCheck = Date.now();
  if (manual) toast('Checking for updates…');
  B.updCheck();
}
function updInstall() {
  if (!UPD.latest || !UPD.latest.newer) return updCheck(true);
  toast(`Getting TopShelf ${UPD.latest.name || ''}…`);
  B.updInstall(UPD.latest.apk || 'TopShelf.apk');
}

window.tsUpdate = (state, pct, msg) => {
  if (state === 'check') {
    let r = {}; try { r = JSON.parse(msg); } catch (e) {}
    if (!r.ok) { if (UPD.manual) toast(`Couldn't check for updates: ${r.error || 'no answer'}`); UPD.manual = false; return; }
    UPD.latest = r;
    if (r.newer) {
      if (UPD.manual || UPD.notified !== r.code) {
        UPD.notified = r.code; writeJSON('updNotified', r.code);
        let watching = false; try { watching = !!(B.isWatching && B.isWatching()); } catch (e) {}
        if (IS_TV && (UPD.manual || (mode === 'grid' && !watching))) sheetOpen(`TopShelf ${r.name} is ready`, `<p class="shsm">${esc(r.notes || 'A new version is waiting.')}</p><p class="shsm">Installing takes about half a minute; TopShelf opens again when it's done (if it doesn't, press Home).</p>`,
          [['Install now', updInstall], ['Later', null]]);
        else toast(`TopShelf ${r.name} is ready to install — Settings → TopShelf updates`);
      }
    } else if (UPD.manual) toast(`TopShelf is up to date (${(B.getVersion && B.getVersion()) || ''})`);
    UPD.manual = false;
    if (mode === 'settings' && typeof drawSettings === 'function') drawSettings();
  } else if (state === 'download') {
    toast(`Downloading the update… ${pct}%`);
  } else if (state === 'install') {
    toast('Installing — confirm on the screen that appears');
  } else if (state === 'permission') {
    sheetOpen('One-time permission', `<p class="shsm">Android needs your OK for TopShelf to install its own updates. In the screen that just opened, turn on <b>TopShelf</b>, then come back and choose Install again.</p>`,
      [['Install again', updInstall], ['Open that screen', () => B.updPermission()], ['Later', null]]);
  } else if (state === 'error') {
    toast(`Update didn't finish: ${msg}`);
  } else if (state === 'uploaded') {
    if (UPD.uploadNote) toast('Report sent to Claude');
    UPD.uploadNote = false;
  } else if (state === 'uploadError') {
    toast(`Couldn't send the report: ${msg}`);
  }
};

function updSetupSheet() {
  sheetOpen('TopShelf updates', IS_TV
    ? `<p class="shsm">Updates and reports go through your private GitHub repository <b>${esc(updInfo().repo || '')}</b>. On your phone, open TopShelf → Settings → <b>Updates access token</b>, paste the token, and it's sent to this TV.</p>`
    : `<p class="shsm">On github.com: Settings → Developer settings → Personal access tokens → <b>Fine-grained tokens</b> → Generate new token. Repository access: <b>Only select repositories</b> → <b>${esc(updInfo().repo || '')}</b>. Permissions → Repository permissions → <b>Contents: Read and write</b>. Generate, copy it, then paste it into Settings → <b>Updates access token</b> here.</p>`,
    [['OK', null]]);
}

/* save the token here and, from the phone, on the TV too */
async function updSetToken(v) {
  const t = String(v || '').trim();
  B.updSetToken(t, null);
  if (!t) return toast('Updates access token removed');
  if (!IS_TV && typeof lkOn === 'function' && lkOn()) {
    const r = await lkSend({ type: 'ghtoken', token: t });
    toast(r ? `Token saved here and on ${lkTvName()}` : 'Token saved on this phone (the TV didn\'t answer)');
  } else toast('Token saved');
  setTimeout(() => updCheck(true), 600);
}
function updLinkCmd(c) {
  if (!c.token) return '';
  B.updSetToken(String(c.token).trim(), null);
  toast('Updates access token received from your phone');
  setTimeout(() => updCheck(true), 800);
  return JSON.stringify({ ok: true });
}

/* reports: every diagnostic is also uploaded */
function updUploadReport(rep, by) {
  if (!rep || !updReady()) return;
  const stamp = new Date().toISOString().slice(0, 16).replace(/[:T]/g, '-');
  const ver = rep.version || 'dev';
  UPD.uploadNote = true;
  B.updUpload(`reports/${stamp}-${ver}-${IS_TV ? 'tv' : 'phone'}.json`, JSON.stringify(rep, null, 1), `Diagnostic ${ver} (${by || (IS_TV ? 'TV' : 'phone')})`);
}
(function () {
  if (typeof diagRun !== 'function') return;
  const run0 = diagRun;
  diagRun = async function (...a) { const r = await run0.apply(this, a); try { updUploadReport(r); } catch (e) {} return r; };
})();

/* check a little after start, then every few hours */
setTimeout(() => updCheck(false), 25000);
try { if (window.TopShelf && TopShelf.pageAlive && typeof S !== 'undefined' && typeof openSettings === 'function') TopShelf.pageAlive(); } catch (e) {}
setInterval(() => { if (Date.now() - UPD.lastCheck > 4 * 3600000) updCheck(false); }, 15 * 60000);
