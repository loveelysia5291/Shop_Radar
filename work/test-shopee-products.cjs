const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const source = fs.readFileSync('app/src/main/assets/extract-shopee-products.js', 'utf8');
const program = source.replace(/^\s*\/\/.*$/gm, '');
const observedPcCard = fs.readFileSync('work/fixtures/shopee-pc-public-card.html', 'utf8');
assert.doesNotMatch(program, /document\.cookie|localStorage|sessionStorage|fetch\s*\(|XMLHttpRequest|history\.|location\.(?:assign|replace)|\.click\s*\(|\.remove\s*\(/);
assert.doesNotMatch(program, /application\/ld\+json|__NEXT_DATA__|__INITIAL_STATE__|__SSR/);
const image = item => `https://images.fixture.test/product-${item}.jpg`;
const card = (item, options = {}) => `<article class="fixture-card">
  <a href="${options.url || `/product/100/${item}`}"${options.label ? ` aria-label="${options.label}"` : ''}>
    ${options.picture || `<img src="${image(item)}" width="96" height="96" alt="${options.alt || ''}">`}
    ${options.title === null ? '' : `<h3 data-testid="item-title">${options.title || `測試商品 ${item}`}</h3>`}
  </a>${options.beforePrice || ''}<div data-testid="current-price">${options.price ?? '$1,299'}</div>${options.after || ''}
</article>`;

(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true, timeout: 20000});
  try {
    const context = await browser.newContext();
    let fixture = '', requests = [], assertions = 0, fixtureCount = 0;
    await context.route('**/*', route => {
      requests.push({url: route.request().url(), type: route.request().resourceType()});
      if (route.request().resourceType() === 'image') return route.fulfill({contentType: 'image/svg+xml',
        body: '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><rect width="96" height="96" fill="gray"/></svg>'});
      return route.fulfill({contentType: 'text/html; charset=utf-8', body: fixture});
    });
    const page = await context.newPage();
    const run = async (html, url = 'https://shopee.tw/search?keyword=fixture') => {
      fixture = '<!doctype html><main>' + html + '</main>';
      requests = [];
      await page.goto(url);
      // Reading layout can make Chromium discover its own lazy images. Only
      // navigation/data requests would be an extractor side effect; the code
      // itself is also checked above for request/session APIs.
      const dataRequests = () => requests.filter(request => request.type !== 'image').length;
      const before = dataRequests();
      const result = await page.evaluate(source);
      const products = JSON.parse(result);
      assert.equal(await page.evaluate(() => window.__shopRadarPublicCardCount), products.length, 'Count means complete, deduplicated public cards');
      assert.equal(dataRequests(), before, 'The parser makes no navigation or data requests');
      assertions += 2;
      const stats = await page.evaluate(() => window.__shopRadarPublicCardStats);
      assert.equal(stats.parsed, products.length);
      assert.equal(stats.candidates, stats.parsed + stats.missingTitle + stats.missingPrice);
      assertions += 2;
      if (process.argv.includes('--progress') && ++fixtureCount % 20 === 0) console.log(`Checked ${fixtureCount} local fixtures`);
      return products;
    };
    const check = (actual, expected, reason) => { assert.deepEqual(actual, expected, reason); assertions++; };

    let products = await run(card(200, {title: 'iPhone 13 128GB', after: '<time>2026-09-20</time><span class="seller-name">小明賣場</span>'}));
    check(products.length, 1);
    check(products[0], {platform: '蝦皮', title: 'iPhone 13 128GB', price: 1299, url: 'https://shopee.tw/product/100/200',
      image: image(200), listedAt: '2026-09-20', shop: '', isAdvertisement: false, isAdult: false});
    check(Object.keys(products[0]).some(key => /stock|status|availability|listedAtEpoch/.test(key)), false, 'No invented dates or availability');
    products = await run(`<article><a href="/product/100/290"><img src="${image(290)}" width="96" height="96" alt="粗略名稱"><span>$100</span></a>
      <h3 data-testid="item-title">卡片實際商品名称</h3><time>2 小時前</time><span>Sponsored</span></article>`);
    check([products[0].title, products[0].listedAt, products[0].isAdvertisement], ['卡片實際商品名称', '2 小時前', true], 'A known single-product card includes metadata outside its link');

    products = await run('<div class="unknown-layout"><a href="/product/100/291"><img src="' + image(291) + '" width="96" height="96" alt="Generic Camera"><span>$100</span></a>' +
      '<h3 data-testid="item-title">真正相機名稱</h3><time>3 小時前</time><span>Sponsored</span></div>');
    check([products[0].title, products[0].listedAt, products[0].isAdvertisement], ['真正相機名稱', '3 小時前', true],
      'Unclassified single-product parents outrank the image-link alt and retain sibling metadata');

    for (const host of ['shopee.tw', 'www.shopee.tw', 'm.shopee.tw']) {
      products = await run(card(200, {url: `https://${host}/手機-i.100.200?utm_source=test#tracking`}) + card(200), `https://${host}/search`);
      check(products.length, 1, 'Slug, tracking and mobile copies deduplicate');
      check(products[0].url, 'https://shopee.tw/product/100/200');
    }
    for (const path of ['/shop/100', '/shop/100/search', '/search/']) check((await run(card(200), 'https://shopee.tw' + path)).length, 1);
    const badLinks = ['https://shopee.tw.evil.test/product/100/200', 'http://shopee.tw/product/100/200',
      'https://user:password@shopee.tw/product/100/200', 'https://shopee.tw:8443/product/100/200',
      '/product/100/200/settings', '/nested/product/100/200', '/nested/手機-i.100.200',
      '/product/0/200', '/product/100/200oops', '/product/100/200%2Fsettings', '/buyer/login?next=/product/100/200'];
    for (const url of badLinks) check((await run(card(200, {url}))).length, 0, 'Reject ambiguous or untrusted product URLs');

    products = await run('<article>' + card(201, {title: '第一件手機', price: '$100'}) + card(202, {title: '第二件手機', price: '$200'}) + '</article>');
    check(products.map(item => [item.title, item.price, item.image]), [['第一件手機', 100, image(201)], ['第二件手機', 200, image(202)]], 'Nested article never shares fields between products');
    check((await run(`<article><a href="/product/100/201"><img src="${image(201)}" width="96" height="96"><h3>第一件</h3></a>
      <a href="/product/100/202"><img src="${image(202)}" width="96" height="96"><h3>第二件</h3></a><div>$100</div></article>`)).length,
      0, 'A price outside two sibling product links has no safe owner');

    products = await run(card(203, {title: '型號 2026 手機', price: '$499', beforePrice: '<del class="old-price">$9,999</del><span class="sold">已售 800</span><span class="shipping">運費 $60</span>'}));
    check(products[0].price, 499, 'Strike-through, sales and shipping do not replace current price');
    products = await run(card(204, {price: '<span style="text-decoration:line-through">$999</span><span>$399</span>'}));
    check(products[0].price, 399, 'Current price remains readable inside a former/current price wrapper');
    products = await run(card(205, {price: '<span class="price__original">$999</span><span>$299</span>'}));
    check(products[0].price, 299, 'Former-price class is ignored even without line-through');
    products = await run(card(213, {price: '<span class="originalPrice">$999</span><span>$249</span>'}));
    check(products[0].price, 249, 'Camel-case former-price metadata is ignored');
    products = await run(card(206, {price: '<span>$</span><span>1,099</span>', title: '螢幕 27 吋'}));
    check(products[0].price, 1099, 'Split currency and grouped number retain their ownership');
    check((await run(card(207, {price: '型號 2026'}))).length, 0, 'Model digits are not a price');
    check((await run(card(208, {price: '已售 500'}))).length, 0, 'Sold count is not a price');
    products = await run(card(209, {price: '$100 - $200'}));
    check(products[0].price, 100, 'Use the displayed lower price in a variant range');
    products = await run(card(210, {price: '1299'}));
    check(products[0].price, 1299, 'A rendered semantic price can omit the currency sign');
    products = await run(card(211, {price: '$0'}));
    check(products[0].price, 0, 'A displayed zero price remains valid');
    check((await run(card(212, {price: '$-10'}))).length, 0);

    products = await run(card(220, {title: '正確商品名稱', beforePrice: '<h3 class="seller-name">賣家頭像</h3><span>已售 999</span>',
      picture: `<img class="avatar" src="https://images.fixture.test/avatar.jpg" width="32" height="32" alt="賣家頭像"><img src="${image(220)}" width="96" height="96">`}));
    check([products[0].title, products[0].image], ['正確商品名稱', image(220)], 'Seller metadata never becomes product fields');
    products = await run(card(225, {title: '真正商品標題', picture: `<img class="sellerAvatar" src="https://images.fixture.test/avatar.jpg" width="32" height="32">
      <h3 data-testid="sellerName">錯誤賣家名稱</h3><h3 class="shopName">錯誤賣場名稱</h3><h3 class="storeTitle">錯誤店舖名稱</h3><img src="${image(225)}" width="96" height="96">`}));
    check([products[0].title, products[0].image], ['真正商品標題', image(225)], 'Camel-case seller/shop/store metadata never wins title selection');
    products = await run(card(221, {title: null, label: '手機 iPhone 13', alt: '商品圖片'}));
    check(products[0].title, '手機 iPhone 13', 'Public accessible listing name is a valid fallback');
    products = await run(card(222, {title: null, alt: '無線鍵盤'}));
    check(products[0].title, '無線鍵盤');
    for (const title of ['$1299', '已售 500', '500 sold', '賣家', 'avatar', '2026', '3 小時前', '商品圖片']) {
      check((await run(card(223, {title, alt: ''}))).length, 0, 'Metadata-only cards have no product title');
    }
    products = await run(card(224, {title: '贈品卡 $100 面額'}));
    check(products[0].title, '贈品卡 $100 面額', 'A semantic product title may contain its face value');

    products = await run('<div data-sqe="item"><a href="/手機-i.15651109.50757132344?extraParams=fixture"><img width="96" height="96" ' +
      'src="https://down-tw.img.susercontent.com/file/tw-11134258-81zte-micvdy6qdaf777.webp">' +
      '<div data-sqe="name">新款 i17 Pro Max 5G 手機</div><span data-sqe="price"><span>$</span><span>1,999</span></span></a>' +
      '<div data-sqe="sold">已售 600</div><div data-sqe="seller-name">不能變成商品名稱</div></div>');
    check([products[0].title, products[0].price, products[0].url],
      ['新款 i17 Pro Max 5G 手機', 1999, 'https://shopee.tw/product/15651109/50757132344'],
      'Synthetic semantic card uses the supplied public slug/image format, never a hardcoded production result');
    await run(card(285, {title: null, alt: '', price: '$100'}) + card(286, {price: '沒有价格'}));
    check(await page.evaluate(() => window.__shopRadarPublicCardStats),
      {routeAllowed: true, candidates: 2, missingTitle: 1, missingPrice: 1, parsed: 0}, 'Failure counters identify the missing field without storing content');

    products = await run(card(230, {picture: `<img src="https://images.fixture.test/placeholder.png" data-src="${image(230)}" width="96" height="96">`}));
    check(products[0].image, image(230), 'A placeholder does not outrank a lazy product image');
    products = await run(card(231, {picture: `<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" data-lazy-src="${image(231)}" width="96" height="96">`}));
    check(products[0].image, image(231), 'Lazy image remains usable behind a one-pixel source');
    products = await run(card(232, {picture: '<img src="https://images.fixture.test/placeholder.png" data-srcset="https://images.fixture.test/small.jpg 320w, https://images.fixture.test/large.jpg 960w" width="96" height="96">'}));
    check(products[0].image, 'https://images.fixture.test/large.jpg', 'Choose largest declared lazy srcset candidate');
    products = await run(card(233, {picture: '<picture><source srcset="https://images.fixture.test/picture.jpg 2x"><img src="https://images.fixture.test/placeholder.png" width="96" height="96"></picture>'}));
    check(products[0].image, 'https://images.fixture.test/picture.jpg');
    products = await run(card(234, {picture: '<img src="https://images.fixture.test/placeholder.png" width="96" height="96">'}));
    check([products.length, products[0].image], [1, ''], 'Missing product images do not discard a named, priced public card');
    products = await run(card(235, {picture: '<img class="avatar" src="https://images.fixture.test/avatar.jpg" width="96" height="96">'}));
    check([products.length, products[0].image], [1, ''], 'An avatar cannot substitute for the missing product image');

    check((await run('<div style="display:none">' + card(240) + '</div>')).length, 0);
    check((await run('<div style="opacity:0">' + card(240) + '</div>')).length, 0);
    check((await run('<div aria-hidden="true">' + card(240) + '</div>')).length, 0);
    check((await run('<div role="dialog">' + card(240) + '<input type="password"></div>')).length, 0);
    check((await run('<script type="application/ld+json">{"@type":"Product","name":"Hidden","offers":{"price":1}}</script>')).length, 0, 'Hidden structured data is not a public card');

    for (const url of ['https://shopee.tw/buyer/login', 'https://shopee.tw/verify/traffic/error', 'https://shopee.tw/cart',
      'https://shopee.tw/checkout', 'https://shopee.tw/user/account', 'https://shopee.tw/api/v4/search/search_items',
      'https://shopee.tw/unknown/private/route', 'https://shopee.tw/product/100/200', 'https://shopee.tw.evil.test/search',
      'http://shopee.tw/search', 'https://shopee.tw:8443/search']) check((await run(card(250), url)).length, 0, 'Only known official public listing routes are in scope');
    await run(card(250));
    const frameReady = page.waitForEvent('framenavigated', {predicate: candidate => candidate !== page.mainFrame()});
    await page.evaluate(() => { const frame = document.createElement('iframe'); frame.src = '/search'; document.body.append(frame); });
    const frame = await frameReady;
    await frame.waitForLoadState();
    check(JSON.parse(await frame.evaluate(source)), [], 'Never read cards in an iframe');

    products = await run(Array.from({length: 275}, (_, index) => card(1000 + index)).join(''));
    check(products.length, 275, 'Rendered products are not capped at a small arbitrary count');
    check(products.every(product => product.listedAt === ''), true, 'Missing listing times remain missing');
    products = await run(card(280, {after: '<span>Sponsored</span>'}));
    check(products[0].isAdvertisement, true, 'Advertisement label is read from public card text');
    check(JSON.parse(await page.evaluate(source)).length, 1, 'Repeated reads are independent and idempotent');
    products = await run(observedPcCard);
    check(products.length, 1, 'The observed PC anchor has display: contents, not a hidden product');
    check([products[0].title, products[0].price, products[0].url],
      ['原廠公司貨 iPhone 18 Pro max 256GB 手機 6.9吋 智慧型手機 i18手機 廠商補貼優惠手機方案', 4900, 'https://shopee.tw/product/1564591373/55217901436']);
    check(products[0].image.includes('tw-11134207-820le-mt6gw00zjnd1c2'), true, 'The real product picture wins, not the free-shipping overlay');
    check(products[0].image.includes('tw-11134258'), false);
    check(await page.locator('a.contents').evaluate(el => [getComputedStyle(el).display, el.getClientRects().length]),
      ['contents', 0], 'Regression fixture preserves the exact cause of the previous missing results');
    check((await run('<div style="display:none">' + observedPcCard + '</div>')).length, 0);
    check((await run('<div style="opacity:0">' + observedPcCard + '</div>')).length, 0);
    check((await run('<div aria-hidden="true">' + observedPcCard + '</div>')).length, 0);
    check((await run('<div style="display:contents"><a href="/product/100/300" style="display:contents"><div style="display:none"><h3>隱藏的手機</h3><span>$4900</span></div></a></div>')).length,
      0, 'A contents wrapper cannot make hidden children visible');
    check((await run('<article><a href="/product/100/300" style="display:block;width:0;height:0"><div><h3>不應出現</h3><span>$100</span></div></a></article>')).length,
      0, 'Only explicit display: contents may inherit a rendered child box');
    products = await run(card(300, {title: null, label: 'View product: 手機 iPhone 18', alt: '商品圖片'}));
    check(products[0].title, '手機 iPhone 18', 'Do not display the accessibility action prefix in product names');
    products = await run(card(301, {picture: '<img alt="custom-overlay" src="https://images.fixture.test/promotion.webp" width="96" height="96">' +
      `<img src="${image(301)}" width="96" height="96">`}));
    check(products[0].image, image(301), 'A shipping overlay preceding the real picture never wins');
    products = await run(card(302, {picture: '<div aria-label="image overlay,src:fixture"><img src="https://images.fixture.test/promotion.webp" width="96" height="96"></div>' +
      '<img alt="flag-label" src="https://images.fixture.test/mall.png" width="96" height="96"><img alt="rating-star-full" src="https://images.fixture.test/star.svg" width="96" height="96"><img alt="location-icon" src="https://images.fixture.test/location.svg" width="96" height="96">'}));
    check(products[0].image, '', 'Missing main pictures do not fall back to badges, rating or location icons');
    products = await run(Array.from({length:31}, (_, index) => observedPcCard.replaceAll('1564591373.55217901436', `1564591373.${55217901436 + index}`)).join(''));
    check(products.length, 31, 'The new PC card layout is read without imposing a 15-item cap');
    console.log(`PASS: ${assertions} assertions; canonical identity, exact hosts/routes, single-product boundaries, title/price/image ownership, visibility, missing metadata and uncapped rendered cards. All browser requests were fulfilled locally.`);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
