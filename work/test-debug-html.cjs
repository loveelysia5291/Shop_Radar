const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const asset = 'app/src/debug/assets/marketplace-html-debug.js';
const source = fs.readFileSync(asset, 'utf8');
assert.ok(!fs.existsSync('app/src/main/assets/marketplace-html-debug.js'));
assert.ok(!fs.existsSync('app/src/release/assets/marketplace-html-debug.js'));
assert.ok(!fs.existsSync('app/src/main/java/com/example/shopradar/MarketplaceHtmlDebugSession.kt'));
assert.ok(!fs.existsSync('app/src/main/java/com/example/shopradar/CaptureDiagnostics.kt'));
assert.doesNotMatch(source, /document\.cookie|localStorage|sessionStorage|fetch\(|XMLHttpRequest/);
for (const name of ['CaptureDiagnostics.kt', 'CaptureDebugUi.kt']) {
  const release = fs.readFileSync('app/src/release/java/com/example/shopradar/' + name, 'utf8');
  assert.doesNotMatch(release, /outerHTML|evaluateJavascript|MarketplaceHtmlDebugSession|ZipOutputStream|writeText|assets\.open/);
}
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage();
    await page.route('**/*', r => r.fulfill({contentType: 'text/html', body: '<html><head><title>Marketplace fixture</title></head><body><input type="password" value="PRIVATE-PASSWORD"><input type="hidden" value="PRIVATE-FORM-TOKEN"><textarea>PRIVATE-TYPED-TEXT</textarea><div contenteditable="true">PRIVATE-EDITED-TEXT</div><a href="/marketplace/item/123456789/">Phone</a><script type="application/json">{"marketplace_listing_title":"Phone","price":100}</script></body></html>'}));
    await page.goto('https://www.facebook.com/marketplace/114987875182625/search?query=fixture&radius=250');
    const result = JSON.parse(await page.evaluate(source));
    assert.equal(result.marketplaceItemAnchors, 1);
    assert.equal(result.passwordFields, 1);
    assert.equal(result.truncated, false);
    for (const privateValue of ['PRIVATE-PASSWORD', 'PRIVATE-FORM-TOKEN', 'PRIVATE-TYPED-TEXT', 'PRIVATE-EDITED-TEXT']) {
      assert.ok(!result.html.includes(privateValue), 'Remove form values from detached export clone');
    }
    assert.ok(result.html.includes('marketplace_listing_title'), 'Keep hydration JSON needed for parsing diagnosis');
    assert.equal(await page.locator('input[type=password]').inputValue(), 'PRIVATE-PASSWORD', 'Never mutate live page');
    await page.evaluate(() => { document.body.append(document.createTextNode('X'.repeat(5 * 1024 * 1024))); });
    const limited = JSON.parse(await page.evaluate(source));
    assert.equal(limited.truncated, true);
    assert.equal(limited.html.length, 4 * 1024 * 1024);
    await page.goto('https://shopee.tw/search?keyword=fixture');
    await page.evaluate(() => { document.body.innerHTML = '<a href="/product/123/456">Phone</a><a href="/Phone-i.123.456">Phone again</a><a href="https://evil.test/product/999/888">Not a product</a><img src="photo.jpg"><input type="password" value="PRIVATE-PASSWORD"><script type="application/json">{"itemid":456}</script>'; });
    const shopee = JSON.parse(await page.evaluate(source));
    assert.equal(shopee.shopeeProductLinks, 2);
    assert.equal(shopee.captureState, 'product_links_present');
    assert.equal(shopee.readyState, 'complete');
    assert.equal(shopee.imageCount, 1);
    assert.equal(shopee.scriptCount, 1);
    assert.equal(shopee.languageConfirmAttempts, 0);
    assert.equal(shopee.languageConfirmState, 'not_attempted');
    assert.ok(!shopee.html.includes('PRIVATE-PASSWORD'));
    await page.evaluate(() => { window.__shopRadarLanguageConfirmAttempts = 2; window.__shopRadarLanguageState = 'confirmed'; });
    const language = JSON.parse(await page.evaluate(source));
    assert.equal(language.languageConfirmAttempts, 2);
    assert.equal(language.languageConfirmState, 'confirmed');
    await page.evaluate(() => { document.body.innerHTML = '<main>loading</main>'; });
    const empty = JSON.parse(await page.evaluate(source));
    assert.equal(empty.shopeeProductLinks, 0);
    assert.equal(empty.captureState, 'no_product_links');
    for (const url of ['https://shopee.tw/buyer/login', 'https://shopee.tw/verify/captcha', 'https://shopee.tw.evil.test/search', 'https://www.facebook.com/login/']) {
      await page.goto(url);
      const refused = JSON.parse(await page.evaluate(source));
      assert.equal(refused.html, '');
      assert.equal(refused.error, 'not_public_search_page');
    }
    console.log('PASS: FB/Shopee debug-only HTML; real page counters; no auth-page export; form redaction without live mutation; size cap.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
