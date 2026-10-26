const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');

(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const source = fs.readFileSync('app/src/main/assets/extract-products.js', 'utf8');
    for (const firstOld of [true, false]) {
      const page = await browser.newPage();
      const html = Array.from({length: 10}, (_, i) => `<article><a href="/item/${101234567890 + i}"><h3>手機 ${i}</h3><span>NT$100</span></a></article>`).join('');
      await page.route('**/*', route => route.fulfill({contentType: 'text/html', body: html}));
      await page.goto('https://tw.bid.yahoo.com/store/fixture?sort=-ptime');
      let finish;
      const delivered = new Promise(resolve => { finish = resolve; });
      await page.exposeFunction('fixtureDelivery', finish);
      await page.evaluate(firstOld => {
        const baseline = Date.parse('2026-10-02T11:00:00+08:00');
        window.__shopRadarScanSubscription = true;
        window.__shopRadarSubscriptionCheckpoint = baseline;
        window.__shopRadarSubscriptionSeen = [];
        window.fixtureCalls = [];
        window.ShopRadarYahoo = {deliver: json => window.fixtureDelivery(json), preview: () => {}};
        window.fetch = async url => {
          const id = Number(new URL(url).pathname.match(/\d+/)[0]);
          const index = id - 101234567890;
          window.fixtureCalls.push(id);
          const timestamp = firstOld && index === 0 || !firstOld && index === 1 ? baseline : baseline + 3600000;
          return {ok: true, text: async () => '<script id="isoredux-data">' + JSON.stringify({item: {
            id: String(id), title: '手機 ' + index, price: 100, startTime: timestamp / 1000
          }}) + '</script>'};
        };
      }, firstOld);
      for (let i = 0; i < 3; i++) await page.evaluate(source);
      let timer;
      const json = await Promise.race([delivered, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('Subscription delivery did not finish')), 5000);
      })]).finally(() => clearTimeout(timer));
      const items = JSON.parse(json);
      const calls = await page.evaluate(() => window.fixtureCalls);
      if (firstOld) {
        assert.equal(calls.length, 1, 'Only the first old item may request metadata');
        assert.equal(items.length, 1);
        assert.equal(items[0].listedAtEpochMillis, Date.parse('2026-10-02T11:00:00+08:00'));
      } else {
        assert.equal(items[0].url, 'https://tw.bid.yahoo.com/item/101234567890');
        assert.equal(items[1].url, 'https://tw.bid.yahoo.com/item/101234567891', 'Do not sort an old boundary behind other cards');
        assert.ok(calls.length <= 5, 'Stop scheduling once an old item is confirmed; allow bounded in-flight requests');
      }
      await page.close();
    }
    for (const sort of ['-ptime', 'ptime']) {
      const page = await browser.newPage({timezoneId: 'Asia/Taipei'});
      const dates = [1790892000, 1790978400, 1790805600];
      const html = dates.map((_, i) => `<article><a href="/item/${101234567890 + i}"><h3>商品 ${i}</h3><span>NT$100</span></a></article>`).join('');
      await page.route('**/*', route => route.fulfill({contentType: 'text/html', body: html}));
      await page.goto(`https://tw.bid.yahoo.com/search/auction/product?p=fixture&sort=${sort}`);
      let finish;
      const delivered = new Promise(resolve => { finish = resolve; });
      await page.exposeFunction('fixtureSorted', finish);
      await page.evaluate(dates => {
        window.__shopRadarQuickSearch = true;
        window.ShopRadarYahoo = {deliver: json => window.fixtureSorted(json), preview: () => {}};
        window.fetch = async url => {
          const id = Number(new URL(url).pathname.match(/\d+/)[0]);
          const index = id - 101234567890;
          return {ok: true, text: async () => '<script id="isoredux-data">' + JSON.stringify({item: {
            id: String(id), title: `商品 ${index}`, price: 100, startTime: dates[index]
          }}) + '</script>'};
        };
      }, dates);
      // The native reader continues polling a small lazy list until stable;
      // one preview with three cards no longer closes collection immediately.
      for (let i = 0; i < 12; i++) await page.evaluate(source);
      let timer;
      const json = await Promise.race([delivered, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('Yahoo sorted fixture timed out')), 6000);
      })]).finally(() => clearTimeout(timer));
      const times = JSON.parse(json).map(item => item.listedAtEpochMillis);
      const expected = dates.map(value => value * 1000).sort((a, b) => sort === '-ptime' ? b - a : a - b);
      assert.deepEqual(times, expected);
      await page.close();
    }
    const main = fs.readFileSync('app/src/main/java/com/example/shopradar/MainActivity.kt', 'utf8');
    assert.match(main, /5000L - \(android\.os\.SystemClock\.elapsedRealtime\(\) - newItemsPageStartedAt\)/);
    assert.match(main, /subscriptionShopSearching && !showNewItemsPage/);
    assert.match(main, /onProgress = \{ found -> observeSubscriptionProgress\(capture, found\) \}/);
    console.log('PASS: first-old Yahoo fetch reads one detail; new-first preserves boundary order; -ptime is newest-first; five-second paging and modal suppression are wired.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
