const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage();
    const body = Array.from({length:8}, (_,i) => `<article><a href="/item/10123456789${i}"><h3>手機 ${i}</h3><span>NT$1,000</span></a></article>`).join('');
    await page.route('**/*', route => route.fulfill({contentType:'text/html', body}));
    await page.goto('https://tw.bid.yahoo.com/search/auction/product?cid=23960');
    let finish;
    const delivered = new Promise(resolve => { finish=resolve; });
    await page.exposeFunction('deliverFixture', finish);
    await page.evaluate(() => {
      window.ShopRadarYahoo = {deliver: json => window.deliverFixture(json)};
      window.activeFixture = 0; window.maxFixture = 0;
      window.fetch = (url, options) => new Promise((resolve, reject) => {
        window.maxFixture = Math.max(window.maxFixture, ++window.activeFixture);
        // Deliberately hang every detail request until the scraper aborts it.
        options.signal.addEventListener('abort', () => { window.activeFixture--; reject(new Error('fixture timeout')); }, {once:true});
      });
    });
    const source = fs.readFileSync('app/src/main/assets/extract-products.js','utf8');
    const start = Date.now();
    for (let i = 0; i < 12; i++) await page.evaluate(source);
    await page.evaluate(source); // Duplicate polling must not start duplicate jobs.
    const json = await Promise.race([delivered, new Promise((_, reject) => setTimeout(() => reject(new Error('delivery timeout')), 16000))]);
    assert.equal(JSON.parse(json).length,8);
    assert.equal(await page.evaluate(() => window.maxFixture),4);
    assert.ok(Date.now()-start < 16000);
    assert.ok(JSON.parse(json).every(item => item.listedAt === ''));
    console.log('PASS: Yahoo retains all 8 cards on slow details, limits requests to 4, delivers once without fabricated dates.');
  } finally { await browser.close(); }
})().catch(error => {console.error(error); process.exitCode=1;});
