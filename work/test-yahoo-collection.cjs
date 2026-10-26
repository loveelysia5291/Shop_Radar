const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => route.fulfill({contentType:'text/html', body:'<main></main>'}));
    await page.goto('https://tw.bid.yahoo.com/search/auction/product?p=phone');
    const script = fs.readFileSync('app/src/main/assets/extract-products.js', 'utf8');
    await page.evaluate(() => {
      window.deliveries = [];
      window.ShopRadarYahoo = {deliver: json => window.deliveries.push(JSON.parse(json))};
      window.fetch = async () => ({ok: true, text: async () => ''});
      window.addCards = (from, to) => {
        for(let i=from;i<to;i++) document.querySelector('main').insertAdjacentHTML('beforeend',
          `<article><a href="/item/${101234567890+i}"><h3>${i===0?'Y8050216629':i===1?'近全新':'手機 '+i}</h3><span>NT$1,000</span></a></article>`);
      };
      window.addCards(0,7);
    });
    for(let i=0;i<4;i++) await page.evaluate(script);
    assert.equal(await page.evaluate(() => window.deliveries.length), 0);
    await page.evaluate(() => window.addCards(7,18));
    for(let i=0;i<6;i++) await page.evaluate(script);
    await page.waitForFunction(() => window.deliveries.length === 1);
    const products = await page.evaluate(() => window.deliveries[0]);
    assert.equal(products.length,18);
    assert.equal(products[0].title,'商品名稱待確認');
    assert.equal(products[1].title,'商品名稱待確認');
    await page.evaluate(() => {
      document.querySelector('main').innerHTML='';
      delete window.__shopRadarYahooCollection; delete window.__shopRadarYahooReading;
      window.deliveries=[];
    });
    for(let i=0;i<12;i++) await page.evaluate(script);
    assert.deepEqual(await page.evaluate(() => window.deliveries),[]);
    assert.equal(await page.evaluate(() => Boolean(window.__shopRadarYahooReading)),false);
    console.log('PASS: 7 lazy cards grow to 18 without truncation; seller/condition labels never become titles; unconfirmed empty pages keep polling until the native limit.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
