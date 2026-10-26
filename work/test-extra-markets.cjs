const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => route.fulfill({contentType:'text/html', body:'<main></main>'}));
    const script = fs.readFileSync('app/src/main/assets/bobo-metadata.js', 'utf8') + ';\n' + fs.readFileSync('app/src/main/assets/extract-extra-markets.js', 'utf8');
    await page.goto('https://www.bbbobo.com.tw/shop/0/Goods_List.asp');
    await page.setContent(`<div class="pr-info" GID="GTT2610020008652" AID="1"><span class="listimg"><img src="/Goods_IMG/1/GTT2610020008652.jpg"></span><div class="shop-name">土城總舖</div><div class="pr-name" title="手機/Samsung SM-N950F">手機/Samsung SM-N950F</div><span class="pr-price-org-dwon">$1500</span><span class="pr-price-org">$1000</span></div>`);
    let rows = JSON.parse(await page.evaluate(script));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].price, 1000);
    assert.equal(rows[0].shop, '土城總舖');
    assert.equal(rows[0].listedAt, '');
    assert.match(rows[0].url, /AID=1&GID=GTT2610020008652$/);
    await page.goto('https://www.bbbobo.com.tw/shop/0_1/Goods_List.asp');
    await page.setContent(`<div class="pr-info" GID="GTT2606120006790" AID="86"><img src="/Goods_IMG/86/product.jpg"><div class="shop-name">嘉義玉山舖</div><div class="pr-name" title="斜背包">斜背包</div><span class="pr-price-org-dwon">$258</span><span class="pr-price-org">$178</span></div>`);
    rows = JSON.parse(await page.evaluate(script));
    assert.equal(rows[0].price,178);
    assert.equal(rows[0].url,'https://www.bbbobo.com.tw/shop/0_1/Goods.asp?AID=86&GID=GTT2606120006790');
    const detailUrl = 'https://www.bbbobo.com.tw/shop/0/Goods.asp?AID=86&GID=GTT2605190005148';
    const detail = `<ul><li>商品所在： 嘉義玉山舖</li><li>商品分類： 手機通訊</li><li>商品編號： GTT2605190005148</li><li>上架時間： 2026/10/3</li></ul><h2>您可能喜歡的商品</h2><p>上架時間：2020/1/1</p>`;
    assert.deepEqual(await page.evaluate(({detail,detailUrl}) => window.__shopRadarBoboMetadata(new DOMParser().parseFromString(detail,'text/html'), detailUrl), {detail,detailUrl}),
      {shop:'嘉義玉山舖', category:'手機通訊', listedAt:'2026/10/3'});
    assert.deepEqual(await page.evaluate(({detail,detailUrl}) => window.__shopRadarBoboMetadata(new DOMParser().parseFromString(detail,'text/html'), detailUrl.replace('/0/','/0_1/')), {detail,detailUrl}),
      {shop:'嘉義玉山舖', category:'手機通訊', listedAt:'2026/10/3'});
    assert.equal(await page.evaluate(({detail,detailUrl}) => window.__shopRadarBoboMetadata(new DOMParser().parseFromString(detail.replace('2026/10/3','2026/2/30'),'text/html'), detailUrl), {detail,detailUrl}), null);
    assert.equal(await page.evaluate(({detail,detailUrl}) => window.__shopRadarBoboMetadata(new DOMParser().parseFromString(detail,'text/html'), detailUrl.replace('GTT2605190005148','GTT2605190005999')), {detail,detailUrl}), null);
    await page.goto('https://mall.iopenmall.tw/iopen/index.php?action=store_product_search&prod_keyword=黑鯊');
    await page.setContent(`<main><article><a href="/017737/index.php?action=product_detail&prod_no=P1773702460748&ref=search"><img src="/image/product.jpg" alt="黑鯊手機"></a><h2><a href="/017737/index.php?action=product_detail&prod_no=P1773702460748">黑鯊手機</a></h2><p>$1,000 <del>$2,000</del></p></article><article><h2><a href="/017738/index.php?action=product_detail&prod_no=P1773702460749">其他手機</a></h2><p>$9,000</p></article><a href="/iopen/index.php?action=store_product_search&prod_keyword=黑鯊">搜尋</a></main>`);
    rows = JSON.parse(await page.evaluate(script));
    assert.equal(rows.length, 2);
    assert.equal(rows[0].title, '黑鯊手機');
    assert.equal(rows[0].price, 1000);
    assert.equal(rows[0].image, 'https://mall.iopenmall.tw/image/product.jpg');
    assert.equal(rows[0].listedAt, '');
    assert.equal(rows[1].price, 9000);
    assert.equal(await page.evaluate(() => window.__shopRadarVerificationRequired), false);
    await page.setContent('<form><input type="hidden" name="cfi"><div class="h-captcha"></div></form>');
    assert.deepEqual(JSON.parse(await page.evaluate(script)), []);
    assert.equal(await page.evaluate(() => window.__shopRadarVerificationRequired), true);
    await page.setContent('<main><h2><a href="/017737/index.php?action=product_detail&prod_no=P1773702460748">只有名稱</a></h2><h2><a href="/017738/index.php?action=product_detail&prod_no=P1773702460749">另一商品</a></h2><p>$9,000</p></main>');
    assert.deepEqual(JSON.parse(await page.evaluate(script)), [], 'Whole-page prices cannot supply individual item prices');
    await page.goto('https://www.easysell.com.tw/Goods/Search?Key=手機');
    await page.setContent(`<a href="/Goods/Detail?cibSNo=7167624"><img src="/Uploads/product.jpg"><h3>吸盤手機支架</h3><dl><dt>售價：</dt><dd>80 元</dd><dt>分店：</dt><dd>新莊新泰店</dd></dl></a><a href="/Goods/Detail?cibSNo=9999999"><h3>沒有價格</h3></a><a href="https://evil.example/Goods/Detail?cibSNo=8888888"><h3>外部商品</h3></a>`);
    rows = JSON.parse(await page.evaluate(script));
    assert.deepEqual(rows, [], 'Removed platform must not extract products');
    console.log('PASS: Bobo/iOPEN card isolation and verification detection; removed EasySell; no fabricated dates.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
