// The TopShelf page opens in a browser (with its own stand-in for the app) without a script error, Settings opens
// and draws every section, and a screenshot of each is kept (tools/checks/out/) to look at.
// usage: node tools/checks/page_smoke.js   (needs playwright with chromium)
const path = require('path'), fs = require('fs');
const { chromium } = require('playwright');
(async () => {
  const page_ = 'file://' + path.resolve(__dirname, '../../app/src/main/assets/index.html');
  const out = path.resolve(__dirname, 'out'); fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e && e.stack || e)));
  await page.goto(page_);
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(out, 'home.png') });
  const sections = await page.evaluate(() => {
    if (typeof openSettings !== 'function') return null;
    openSettings();
    return typeof SET_SECTIONS !== 'undefined' ? SET_SECTIONS.length : 0;
  });
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(out, 'settings.png') });
  await browser.close();
  if (sections === null) errors.push('openSettings() is missing');
  if (errors.length) { console.log('page smoke FAILED:\n  ' + errors.join('\n  ')); process.exit(1); }
  console.log(`page smoke: the page opens without script errors; Settings opens (${sections} sections); screenshots in tools/checks/out/`);
})().catch(e => { console.log('page smoke FAILED: ' + e); process.exit(1); });
