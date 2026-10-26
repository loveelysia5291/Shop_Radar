const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage();
    await page.route('**/*', r => r.fulfill({contentType:'text/html',body:'<main></main>'}));
    const script = fs.readFileSync('app/src/main/assets/extract-products.js','utf8');
    await page.goto('https://tw.carousell.com/search/phone');
    await page.evaluate(() => {
      window.clicks = 0;
      window.addCard = id => document.querySelector('main').insertAdjacentHTML('beforeend',
        '<article><a href="/p/phone-'+id+'/"><h3>Phone '+id+'</h3><p>NT$100</p></a></article>');
      window.addCard(1234567890);
      const button = document.createElement('button');
      button.textContent = '顯示更多結果';
      button.onclick = () => {
        window.clicks++;
        setTimeout(() => window.addCard(1234567890+window.clicks), 1200);
      };
      document.body.append(button);
      window.__shopRadarLoadMore = true;
      window.__shopRadarMoreTarget = 1;
    });
    for (let i=0;i<6;i++) await page.evaluate(script);
    assert.equal(await page.evaluate(() => window.clicks),1,'Pending loads must not trigger repeated button presses');
    assert.equal(await page.evaluate(() => window.__shopRadarMoreDone),false,'Old cards do not mean continuation has finished');
    await page.waitForFunction(() => document.querySelectorAll('article').length===2);
    let rows = JSON.parse(await page.evaluate(script));
    assert.equal(rows.length,2);
    assert.equal(await page.evaluate(() => window.__shopRadarMoreDone),true);
    await page.evaluate(() => { window.__shopRadarMoreTarget = 2; });
    await page.evaluate(script);
    await page.waitForFunction(() => document.querySelectorAll('article').length===3);
    rows = JSON.parse(await page.evaluate(script));
    assert.equal(rows.length,3);
    assert.equal(await page.evaluate(() => window.clicks),2);
    // No arbitrary 60/240-item cap on the listings actually loaded in Carousell DOM.
    await page.evaluate(() => { for (let i=3;i<260;i++) window.addCard(1234567890+i); });
    assert.equal(JSON.parse(await page.evaluate(script)).length,260);

    const fb = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js','utf8') + ';\n' +
      fs.readFileSync('app/src/main/assets/extract-facebook.js','utf8');
    await page.goto('https://www.facebook.com/marketplace/114987875182625/search?radius=250');
    await page.evaluate(() => {
      document.querySelector('main').innerHTML =
        '<a href="/marketplace/item/12345678901234/"><div>NT$500</div><div>二手電腦</div><img src="https://example.com/item.jpg"></a>';
      const node = document.createElement('script'); node.type = 'application/json';
      node.textContent = JSON.stringify({data:{listing:{id:'23456789012345',marketplace_listing_title:'公開手機',
        listing_price:{amount:'1000',currency:'TWD'},primary_listing_photo:{image:{uri:'https://example.com/phone.jpg'}}}},
        ignored:{id:'34567890123456',marketplace_listing_title:'缺價格',listing_price:{currency:'TWD'}}});
      document.body.append(node);
    });
    const result = JSON.parse(await page.evaluate(fb));
    assert.deepEqual(result.items.map(x=>[x.title,x.price]),[['二手電腦',500],['公開手機',1000]]);
    assert.equal(result.structuredCandidates,2);
    assert.ok(result.scriptCount>0);
    assert.equal(new URL(page.url()).searchParams.get('radius'),'250');
    console.log('PASS: async Carousell continuation waits for new IDs, replay targets, all loaded cards, Facebook price-first and public JSON fallback.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode=1; });
