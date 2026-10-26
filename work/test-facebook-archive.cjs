const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const read = require('./read-diagnostic-zip.cjs');
(async () => {
  const entries = read(process.argv[2] || 'D:/download/ShopRadar-facebook-html-1790995802342.zip');
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage();
    let safeHtml = '';
    // Do not execute archived Facebook scripts, connect to Facebook, or reload pixels.
    await page.route('**/*', route => route.request().isNavigationRequest()
      ? route.fulfill({contentType:'text/html; charset=utf-8', body:safeHtml}) : route.abort());
    const extract = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js', 'utf8') + ';\n' +
      fs.readFileSync('app/src/main/assets/extract-facebook.js', 'utf8');
    const resolve = fs.readFileSync('app/src/main/assets/resolve-facebook-card.js', 'utf8');
    for (const name of ['early-4s.html', 'failure.html']) {
      safeHtml = await page.evaluate(html => {
        const doc = new DOMParser().parseFromString(html, 'text/html');
        doc.querySelectorAll('script,iframe,link,meta,form,input,textarea').forEach(n => n.remove());
        doc.querySelectorAll('*').forEach(n => n.getAttributeNames().filter(a => /^on/i.test(a)).forEach(a => n.removeAttribute(a)));
        return doc.documentElement.outerHTML;
      }, entries.get(name));
      const source = 'https://www.facebook.com/marketplace/114987875182625/search?query=phone&minPrice=1&maxPrice=2000&radius=250';
      await page.goto(source);
      const realCards = await page.locator('[data-mcomponent="ServerImageArea"][aria-label*="for sale"],[data-mcomponent="ImageArea"][aria-label*="for sale"]').count();
      const result = JSON.parse(await page.evaluate(extract));
      assert.equal(result.state, 'ready');
      assert.equal(result.items.length, Math.min(realCards, 80));
      assert.equal(result.actionCandidates, result.items.length);
      const phone = result.items.find(x => x.title === 'Htc u20 256g 手機');
      assert(phone); assert.equal(phone.price, 2000);
      assert.match(phone.image, /^https:\/\/scontent\./);
      assert(!phone.title.includes('for sale'));
      assert.equal(new Set(result.items.map(x => x.url)).size, result.items.length);
      for (const item of result.items) {
        const u = new URL(item.url);
        assert.equal(u.href.split('#')[0], source);
        assert.match(u.hash, /^#shopradar-fb-card=/);
        assert.equal(item.listedAt, '');
      }
      assert(result.items.some(x => x.price === 0), 'Free listing must be retained');
      // Simulate only the matching public card's normal click/navigation handler.
      const target = JSON.parse(decodeURIComponent(new URL(phone.url).hash.slice('#shopradar-fb-card='.length)));
      await page.evaluate(target => { window.__shopRadarFacebookCard = {...target, image:'/different-photo.jpg'}; window.clicks = 0; }, target);
      assert.equal(await page.evaluate(resolve), 'loading', 'Different photo must never open a similarly named listing');
      assert.equal(await page.evaluate(() => window.__shopRadarFacebookCardClicked), undefined);
      await page.evaluate(target => {
        window.__shopRadarFacebookCard = target;
        window.clicks = 0;
        for (const area of document.querySelectorAll('[data-mcomponent="ServerImageArea"][aria-label],[data-mcomponent="ImageArea"][aria-label]')) {
          const card = area.closest('[data-action-id][data-type="container"]');
          if (area.getAttribute('aria-label') === target.label) card.addEventListener('click', () => {
            window.clicks++; history.pushState({}, '', '/marketplace/item/1735314001032854/');
          });
          else card?.addEventListener('click', () => { throw new Error('Wrong card clicked'); });
        }
      }, target);
      assert.equal(await page.evaluate(resolve), 'clicked');
      assert.equal(await page.evaluate(resolve), 'https://www.facebook.com/marketplace/item/1735314001032854/');
      assert.equal(await page.evaluate(() => window.clicks), 1);
      console.log(`PASS ${name}: ${realCards} real DIV cards; ${result.items.length} extracted, prices/images/free listing preserved; exact card click and real item URL resolution.`);
    }
    const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
    assert.match(manifest, /FacebookCardActivity" android:exported="false"/);
    console.log('PASS: resolver is private; original location, radius and price parameters are retained.');
  } finally { await browser.close(); }
})().catch(e => {console.error(e.message); process.exitCode = 1;});
