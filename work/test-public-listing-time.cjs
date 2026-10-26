const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const asset = name => fs.readFileSync('app/src/main/assets/'+name,'utf8');
const helpers = asset('facebook-public-metadata.js')+';\n'+asset('public-listing-time.js')+';\n';
const shopee = 'https://shopee.tw/product/123/87654321';
const iopen = 'https://mall.iopenmall.tw/017737/index.php?action=product_detail&prod_no=P1773702460748';
const fb = 'https://www.facebook.com/marketplace/item/1120292097099668/';
let checks=0;
function equal(a,b,message){assert.deepEqual(a,b,message);checks++;}
(async()=>{
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage();
    let html='';
    await page.route('**/*',r=>r.fulfill({contentType:'text/html; charset=utf-8',body:html}));
    const load=async(url,body)=>{html=body;await page.goto(url);};
    const date=async(url)=>{await page.evaluate(helpers);return page.evaluate(url=>window.__shopRadarPublicListingTime.forProduct(url,document.querySelector('article')),url);};
    const json=o=>'<script type="application/json">'+JSON.stringify(o)+'</script>';
    await load('https://shopee.tw/search?keyword=手機',json({items:[{item_basic:{itemid:87654321,shopid:123,ctime:1767323045}},{item_basic:{itemid:99999999,shopid:123,ctime:1767409445}}]}));
    equal((await date(shopee)).listedAt,'2026-01-02 11:04:05','Shopee creation time is joined by BOTH shop and item ID');
    equal((await date('https://shopee.tw/product/124/87654321')).listedAt,'','Wrong shop ID cannot borrow time');
    await load(iopen,'<link rel="canonical" href="'+iopen+'">'+json({'@type':'Product',productID:'P1773702460748',datePublished:'2026-01-02T03:04:05Z'}));
    equal((await date(iopen)).listedAt,'2026-01-02 11:04:05','Canonical own-product SKU is accepted');
    for (const bad of [
      {'@type':'Product',url:iopen,dateModified:'2026-01-02'},
      {'@type':'Product',url:iopen,datePublished:'2099-01-01'},
      {'@type':'Product',url:iopen,datePublished:'2026-02-30'},
      {'@type':'Article',url:iopen,datePublished:'2026-01-02'},
      {'@type':'Product',url:iopen,'@id':shopee,datePublished:'2026-01-02'},
      {'@type':'Product',url:iopen.replace('P1773702460748','P9999999999999'),datePublished:'2026-01-02'}
    ]) {
      await load(iopen,json(bad));equal((await date(iopen)).listedAt,'','Invalid/update/review/other-product date must stay empty');
    }
    for (const [body,expected] of [
      ['<p>上架日期：2026/01/02</p>','2026-01-02'],
      ['<time datetime="2026-01-02T03:04:05Z"></time>','2026-01-02 11:04:05'],
      ['<p>上架時間：約一小時前</p>','約一小時前'],
      ['<p>更新：<time datetime="2026-01-02"></time></p>',''],
      ['<h2>上架時間：2026/01/02</h2>',''],
      ['<p style="display:none">上架時間：2026/01/02</p>','']
    ]){await load(iopen,'<article>'+body+'</article>');equal((await date(iopen)).listedAt,expected);}
    // Visible-only product detail readers never take recommendation/seller times.
    await load(shopee,'<main><div><h1>手機</h1><p>上架時間：約58分鐘前</p></div><section>上架時間：2020/01/01</section></main>');
    await page.evaluate(helpers);
    await page.evaluate(url=>window.__shopRadarFacebookListing={url},shopee);
    equal(JSON.parse(await page.evaluate(asset('extract-public-listing-time.js'))).listedAt,'約58分鐘前');
    for (const value of ['6個月','1小時','36秒','44分鐘']) {
      await load(shopee,'<main><div><h1>手機</h1><span data-testid="listing-time">'+value+'</span></div></main>');
      await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url},shopee);
      equal(JSON.parse(await page.evaluate(asset('extract-public-listing-time.js'))).listedAt,value+'前','Explicit mobile listing field accepts omitted 前');
      await load(shopee,'<main><div><h1>手機</h1><span>'+value+'前上線</span><span>加入時間 6個月前</span></div></main>');
      await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url},shopee);
      equal(JSON.parse(await page.evaluate(asset('extract-public-listing-time.js'))).listedAt||'','','Seller activity must not become listing time');
    }
    for (const body of ['<h1 data-testid="listing-time">36秒</h1>',
        '<h1>手機</h1><span style="display:none" data-testid="listing-time">36秒</span>',
        '<h1>手機</h1><span>36秒</span>', '<h1>手機</h1><p>上線：<time>36秒</time></p>',
        '<h1>手機</h1><aside><time>36秒</time></aside>']) {
      await load(shopee,'<main><div>'+body+'</div></main>');
      await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url},shopee);
      equal(JSON.parse(await page.evaluate(asset('extract-public-listing-time.js'))).listedAt||'','','Unscoped/hidden/title/seller dates cannot be borrowed');
    }
    // Facebook's primary heading/price may be in a nested wrapper.
    await load(fb,'<main><div><div><h1>手機</h1><p>NT$500</p></div><p>約一小時前於台北市上架</p></div><section>昨天上架</section></main>');
    await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url,title:'手機'},fb);
    equal(JSON.parse(await page.evaluate(asset('extract-facebook-listing-time.js'))).listedAt,'約一小時前');
    await load(fb,'<main><div><h1>電腦主機含電腦螢幕</h1><span><span class="html-span"><abbr aria-label="1 週前"><span>1 週前</span></abbr></span>於<a href="/marketplace/114987875182625/">屏東市, 屏東縣</a>上架</span></div></main>');
    await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url,title:''},fb);
    equal(JSON.parse(await page.evaluate(asset('extract-facebook-listing-time.js'))).listedAt,'1 週前','Verified subscription detail uses the actual primary header even without a cached title');
    const reader=fs.readFileSync('app/src/main/java/com/example/shopradar/PublicProductWebReader.kt','utf8');
    assert.match(reader,/if \(market == Market.FACEBOOK\) "extract-facebook-listing-time.js" else "extract-public-listing-time.js"/);checks++;
    assert.match(reader,/listedAtEpochMillis = data.optLong/);checks++;
    await load(fb,json({__typename:'MarketplaceListing',id:'1120292097099668',listing_creation_time:1767323045}));
    await page.evaluate(helpers);await page.evaluate(url=>window.__shopRadarFacebookListing={url,title:'手機'},fb);
    equal(JSON.parse(await page.evaluate(asset('extract-facebook-listing-time.js'))).listedAt,'2026-01-02 11:04:05');
    await load('https://shopee.tw/search?keyword=手機','<main><article><a href="'+shopee+'"><h3 data-testid="item-title">手機</h3></a><div data-testid="current-price">$500</div></article></main>'+json({items:[{item_basic:{itemid:87654321,shopid:123,ctime:1767323045}}]}));
    let products=JSON.parse(await page.evaluate(helpers+asset('extract-shopee-products.js')));
    equal(products.length,1);equal([products[0].price,products[0].listedAt],[500,'2026-01-02 11:04:05']);
    await load('https://mall.iopenmall.tw/iopen/index.php?action=store_product_search&prod_keyword=手機','<article><h2><a href="'+iopen+'">手機</a></h2><p>$500</p><p>上架日期：2026/01/02</p></article>');
    products=JSON.parse(await page.evaluate(helpers+asset('extract-extra-markets.js')));
    equal([products.length,products[0].listedAt],[1,'2026-01-02']);
    // Same expression used by the native product WebView must remain executable.
    await load(iopen,'<div><h1>手機</h1><p>上架時間：2026/01/02</p></div>');
    const native='(function(){'+helpers+'window.__shopRadarFacebookListing = '+JSON.stringify({url:iopen,title:''})+';\nvar listingDate = JSON.parse('+asset('extract-public-listing-time.js')+');return JSON.stringify({listedAt:listingDate.listedAt});})()';
    equal(JSON.parse(await page.evaluate(native)).listedAt,'2026-01-02');
    const capture=fs.readFileSync('app/src/main/java/com/example/shopradar/CaptureSupport.kt','utf8');
    assert.match(capture,/Market.FACEBOOK, Market.SHOPEE, Market.IOPEN/);checks++;
    const main=fs.readFileSync('app/src/main/java/com/example/shopradar/MainActivity.kt','utf8');
    assert.doesNotMatch(main,/全部平台|EASYSELL/);checks++;
    console.log('PASS: '+checks+' listing-time, identity, removal and negative-evidence assertions.');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
