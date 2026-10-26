const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const source = fs.readFileSync('app/src/debug/assets/shopee-guest-search-debug.js', 'utf8');
const release = fs.readFileSync('app/src/release/java/com/example/shopradar/ShopeeGuestSearch.kt', 'utf8');
const mainUi = fs.readFileSync('app/src/main/java/com/example/shopradar/MainActivity.kt', 'utf8');
const commonUi = fs.readFileSync('app/src/main/java/com/example/shopradar/RefreshSettingsUi.kt', 'utf8');
for (const path of ['app/src/main/assets/shopee-guest-search-debug.js',
  'app/src/release/assets/shopee-guest-search-debug.js',
  'app/src/main/java/com/example/shopradar/BackgroundRefreshStatus.kt']) assert.ok(!fs.existsSync(path));
assert.doesNotMatch(source.replace(/^\s*\/\/.*$/gm, ''), /document\.cookie|localStorage|sessionStorage|fetch\s*\(|XMLHttpRequest|history\.|location\.(?:assign|replace)|\.click\s*\(/);
assert.doesNotMatch(release, /assets\.open|CaptureAssets\.read|evaluateJavascript|java\.net\.URI/);
assert.doesNotMatch(mainUi + commonUi, /Text\("背景刷新狀態"\)|OutlinedIconButton/);
const releaseStatus = fs.readFileSync('app/src/release/java/com/example/shopradar/BackgroundRefreshStatus.kt', 'utf8');
assert.doesNotMatch(releaseStatus, /getSharedPreferences|\.commit\(|\.apply\(|WorkManager/);
const card = '<a href="/product/123/456"><img alt="商品"/><span>公開手機商品 NT$100</span></a>';
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage();
    let fixture = '', requests = 0;
    await page.route('**/*', route => { requests++; return route.fulfill({contentType: 'text/html; charset=utf-8', body: fixture}); });
    async function check(body, hidden, url = 'https://shopee.tw/search?keyword=phone') {
      fixture = '<html><head><meta charset="utf-8"></head><body style="overflow:hidden">' + body + '</body></html>';
      await page.goto(url);
      const before = requests;
      await page.evaluate(source);
      await page.evaluate(source); // Idempotent and no extra requests.
      assert.equal(requests, before);
      assert.equal(await page.locator('#modal').evaluate(e => e.hidden), hidden,
        JSON.stringify(await page.evaluate(() => ({url: location.href, body: document.body.innerHTML}))));
    }
    const modal = text => '<div id="modal" role="dialog">' + text + '</div>';
    await check(card + modal('登入或註冊以繼續'), true);
    assert.equal(await page.locator('a[href="/product/123/456"]').count(), 1);
    await check(modal('登入以繼續'), false); // No public data; do not hide the cause.
    await check(card + modal('登入帳號以完成驗證'), false);
    await check(card + modal('Log in / captcha verification'), false);
    await check(card + modal('登入前請選擇語言 language'), false);
    await check(card + modal('登入<input type="password"/>'), false);
    await check(card + modal('登入<iframe></iframe>'), false);
    await check(card + modal('登入'), false, 'https://shopee.tw/buyer/login');
    await check(card + modal('登入'), false, 'https://shopee.tw/verify/traffic/error');
    await check(card + modal('登入'), false, 'https://shopee.tw.evil.test/search');
    await check('<a href="https://evil.test/product/123/456"><img/>假商品</a>' + modal('登入'), false);
    console.log('Debug guest-search fixtures passed; Release has no experiment asset or status persistence.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
