/* The Home button helper. Google TV has no "default home app" setting, so TopShelf catches the
   Home button with an accessibility service. Android 13+ blocks that for apps installed from a
   file (the switch flips straight back off) until it's allowed once — this explains how. */

const SHEET = { btns: [], sel: 0, from: 'grid' };

function sheetOpen(title, html, buttons) {
  if (mode === 'menu') closeMenu();
  if (mode === 'settings') closeSettings();
  SHEET.from = mode === 'sheet' ? SHEET.from : mode;
  SHEET.btns = buttons;
  SHEET.sel = 0;
  $('#sheet .shh').textContent = title;
  $('#sheet .shb').innerHTML = html;
  $('#sheet .shbtns').innerHTML = buttons.map((b, i) => `<div class="shbtn${i === 0 ? ' f' : ''}" data-i="${i}">${esc(b[0])}</div>`).join('');
  mode = 'sheet';
  body.classList.add('sheet-open');
  styleGlass($('#sheet'));
  $('#sheet .shb').scrollTop = 0;
}
function sheetClose() {
  body.classList.remove('sheet-open');
  mode = SHEET.from && SHEET.from !== 'sheet' ? SHEET.from : baseMode();
  applyFocus();
}
function sheetMark() { $('#sheet').querySelectorAll('.shbtn').forEach((el, i) => el.classList.toggle('f', i === SHEET.sel)); }
function sheetKey(k, e) {
  if (!k) return;
  e.preventDefault();
  if (k === 'back') return sheetClose();
  if (k === 'ok') { if (e.repeat) return; const b = SHEET.btns[SHEET.sel]; sheetClose(); if (b && b[1]) b[1](); return; }
  const box = $('#sheet .shb');
  if (k === 'l' && SHEET.sel > 0) SHEET.sel--;
  else if (k === 'r' && SHEET.sel < SHEET.btns.length - 1) SHEET.sel++;
  else if (k === 'u') box.scrollBy({ top: -remPx() * 6, behavior: 'smooth' });
  else if (k === 'd') box.scrollBy({ top: remPx() * 6, behavior: 'smooth' });
  sheetMark();
}
function sheetTap(t) {
  const b = t.closest('.shbtn');
  if (b) { const x = SHEET.btns[+b.dataset.i]; sheetClose(); if (x && x[1]) x[1](); return; }
  if (!t.closest('#sheet')) sheetClose();
}

/* ---------------------------------------------------------------- Home button */

function homeBlock() { try { return JSON.parse(B.homeBlock ? B.homeBlock() : '{}') || {}; } catch (e) { return {}; } }

function homeFixSheet(afterTry) {
  const hb = homeBlock();
  const pkg = hb.pkg || 'com.topshelf.launcher', svc = hb.svc || pkg + '/' + pkg + '.HomeRedirectService';
  const ip = hb.ip || 'TV-IP-ADDRESS';
  const code = s => `<code>${esc(s)}</code>`;
  sheetOpen(afterTry ? "The Home button switch didn't stay on" : 'Android is blocking the Home button switch',
    `<p>Because TopShelf was installed from a file instead of the Play Store, Android 13 and later won't let it turn on
     the accessibility switch that catches the Home button — it flips straight back off. It only needs to be allowed once.</p>
     <h5>Fix from a computer (one time)</h5>
     <ol>
       <li>On the TV: <b>Settings → System → Developer options</b> → turn on <b>USB debugging</b>.</li>
       <li>On a computer on the same Wi-Fi with ADB (Android platform-tools):<br>${code(`adb connect ${ip}`)}<br>
           and allow the prompt that appears on the TV.</li>
       <li>Then run:<br>${code(`adb shell appops set ${pkg} ACCESS_RESTRICTED_SETTINGS allow`)}</li>
       <li>Come back here: <b>Settings → Home button</b>, and turn on <b>TopShelf home button</b>. It will stay on now.</li>
     </ol>
     <p class="shsm">If "adb connect" is refused: <b>Developer options → Wireless debugging → Pair device with pairing code</b>, then
     ${code('adb pair IP:PORT CODE')} and ${code('adb connect IP:PORT')} with the numbers the TV shows.</p>
     <p class="shsm">Or skip the switch entirely — this turns the Home button on directly:<br>
     ${code(`adb shell settings put secure enabled_accessibility_services ${svc}`)}<br>${code('adb shell settings put secure accessibility_enabled 1')}</p>
     <p class="shsm">Installing updates with ${code('adb install -r TopShelf.apk')} also avoids the block. No computer? An ADB app on your phone can run the same commands.</p>`,
    [...(B.homeWatch ? [['Set up without a computer', () => setTimeout(homeSetupSheet, 50)]] : []),
     ['Open Accessibility', () => { homeTried(); B.openAccessibilitySettings(); }],
     ['Open App info', () => { toast('Look in ⋮ for “Allow restricted settings”, if your TV has it'); B.openAppInfo && B.openAppInfo(); }],
     ['Close', null]]);
}

/* ---------------------------------------------------------------- no computer needed:
   two permissions from the TV's own Settings, then a small watcher brings TopShelf back
   whenever Google's home screen opens (HomeWatchService). */

function homeWatch() { try { return JSON.parse(B.homeWatch ? B.homeWatch() : '{}') || {}; } catch (e) { return {}; } }

function homeSetupSheet() {
  try { B.homeWatchSet && B.homeWatchSet(true); } catch (e) {}      // switched on now; it starts as soon as both are allowed
  const w = homeWatch();
  const ready = w.usage && w.overlay;
  const row = (ok, name, what) => `<li class="${ok ? 'ok' : ''}"><b>${ok ? '✓' : '○'} ${name}</b><br><span>${what}</span></li>`;
  const log = w.log || {};
  const paused = (() => { try { return B.homeStatus() === 'paused'; } catch (e) { return false; } })();
  let status = '';
  if (ready) {
    const seen = (log.seen || []).map(x => esc(x.replace('com.google.android.apps.tv.launcherx', 'Google TV home').replace('com.topshelf.launcher', 'TopShelf').replace('com.spocky.projengmenu', 'Projectivy'))).join('<br>') || 'nothing yet';
    status = `<h5>Status</h5><p>${w.acc ? 'The Accessibility switch is on and doing the job.' : w.running ? (paused ? 'Paused.' : '✓ Watching for the Home button.') : '⚠ Not running yet — open TopShelf again, or restart the TV.'}</p>` +
      (log.lastRedirect ? `<p class="shsm">Last time Google's home appeared, TopShelf ${log.result === 'blocked' ? '<b>was blocked by Android from opening</b>' : log.result === 'opened' ? 'came back ✓' : 'tried to come back'}${log.error ? ' · ' + esc(log.error) : ''}.</p>` : '') +
      `<p class="shsm">Recently opened: <br>${seen}</p>` +
      ((log.seen || []).some(x => /projengmenu/.test(x)) ? `<p class="shsm"><b>Projectivy is also set to take over the Home button.</b> TopShelf now steps in after it,
        but for a clean switch turn off Projectivy's Home button override in its settings (or switch off Projectivy in
        <b>Settings → System → Accessibility</b>).</p>` : '');
  }
  const btns = [];
  if (!w.usage) btns.push(['Allow usage access', () => { HOMEFIX.setup = Date.now(); toast('Find TopShelf in the list and turn it on, then press Back'); B.homeWatchGrant('usage'); }]);
  if (!w.overlay) btns.push(['Allow display over apps', () => { HOMEFIX.setup = Date.now(); toast('Find TopShelf in the list and turn it on, then press Back'); B.homeWatchGrant('overlay'); }]);
  if (ready) btns.push(['Refresh status', () => setTimeout(homeSetupSheet, 50)]);
  if (ready && w.running) btns.push([paused ? 'Resume' : 'Pause', () => { B.setRedirect(paused); toast(paused ? 'Home button on' : 'Home button paused'); }]);
  btns.push(['Accessibility way', () => setTimeout(() => homeFixSheet(false), 50)]);
  btns.push(['Close', null]);
  sheetOpen(ready ? 'Home button' : 'Set up the Home button',
    (ready ? '' : `<p>Google TV doesn't let you choose a home app, so TopShelf watches for Google's home screen and steps in — like
     Projectivy. Android blocks the Accessibility way for apps not installed from the Play Store, so this uses two other
     permissions instead. Turn on both — no computer needed:</p>`) +
    `<ol class="shsteps">
       ${row(w.usage, 'Usage access', 'lets TopShelf notice when Google’s home screen opens')}
       ${row(w.overlay, 'Display over other apps', 'lets TopShelf open itself when that happens')}
     </ol>` + status +
    (ready ? '' : `<p class="shsm">Each button opens the TV's settings page: pick <b>TopShelf</b>, switch it on, then press Back to come here.
     If a page doesn't open, look in <b>Settings → Apps → Special app access</b>.</p>`), btns);
}

/* remember that we sent you to Accessibility; if it still isn't on when you come back, offer the fix */
/* remember that we sent you to Accessibility; if it still isn't on when you come back, offer the fix */
function homeTried() { HOMEFIX.tried = Date.now(); }
const HOMEFIX = { tried: 0, setup: 0 };
(function () {
  const prev = window.onAppResume;
  window.onAppResume = () => {
    if (prev) prev();
    if (HOMEFIX.setup && Date.now() - HOMEFIX.setup < 15 * 60000) { HOMEFIX.setup = 0; setTimeout(homeSetupSheet, 500); return; }
    if (!HOMEFIX.tried || Date.now() - HOMEFIX.tried > 10 * 60000) return;
    HOMEFIX.tried = 0;
    let st = 'off';
    try { st = B.homeStatus(); } catch (e) {}
    if (st === 'off' && IS_TV) setTimeout(() => homeFixSheet(true), 600);
    else if (st === 'on') toast('Home button: TopShelf will open when you press Home');
  };
})();

const homeLabel0 = homeLabel;
homeLabel = function () {
  let st = 'off';
  try { st = B.homeStatus(); } catch (e) {}
  if (st === 'off' && IS_TV) return 'Not set up · no computer needed';
  return homeLabel0();
};

/* replaces Settings → Home button */
homeButton = function () {
  let st = 'off';
  try { st = B.homeStatus(); } catch (e) {}
  if (st === 'default') { toast('TopShelf is already the home app'); return; }
  if (IS_TV && B.homeWatch) return homeSetupSheet();
  if (st === 'off') {
    if (IS_TV && homeBlock().restricted) return homeFixSheet(false);
    toast('Turn on “TopShelf home button” in Accessibility');
    homeTried();
    B.openAccessibilitySettings();
    return;
  }
  B.setRedirect(st !== 'on');
  drawSettings();
};
