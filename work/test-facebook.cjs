const fs = require('node:fs');
const assert = require('node:assert/strict');
const { chromium } = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage(); let html = '';
    await page.route('**/*', route => route.fulfill({contentType:'text/html; charset=utf-8', body:html}));
    const script = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js','utf8') + ';\n' +
      fs.readFileSync('app/src/main/assets/extract-facebook.js','utf8');
    // Label format copied from the observed anonymous Marketplace search page.
    html = '<div><a href="/marketplace/item/1735314001032854/" aria-label="Htc u20 256g 手機，NT$2,000，高雄市，商品1735314001032854"><img src="https://scontent.example/item.webp"></a></div>' +
      '<div><a href="https://m.facebook.com/marketplace/item/1578422633274293/" aria-label="近全新 三星S23+ 256G，NT$8,800，高雄市，商品1578422633274293"><img src="https://scontent.example/two.webp"></a></div>';
    await page.goto('https://www.facebook.com/marketplace/175509949157212/search/?query=%E6%89%8B%E6%A9%9F');
    let result = JSON.parse(await page.evaluate(script));
    assert.equal(result.state, 'ready'); assert.equal(result.candidateCount, 2); assert.equal(result.items.length, 2);
    assert.deepEqual(result.items.map(x => [x.title, x.price]), [['Htc u20 256g 手機',2000],['近全新 三星S23+ 256G',8800]]);
    assert.equal(result.items[1].url, 'https://www.facebook.com/marketplace/item/1578422633274293/');
    assert.match(result.items[0].image, /scontent/);
    html = '<div><button aria-label="關閉">關閉</button><form id="login_popup_cta_form"><input type="password"></form></div>';
    await page.goto('https://www.facebook.com/marketplace/175509949157212/search/'); result = JSON.parse(await page.evaluate(script));
    assert.equal(result.state,'overlay_dismissed');
    console.log('PASS: observed Facebook aria-label cards, first-party mobile item URLs, images, and dismissible public overlay.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
