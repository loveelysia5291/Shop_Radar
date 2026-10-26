const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const helpers = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js','utf8');
const detail = fs.readFileSync('app/src/main/assets/extract-facebook-listing-time.js','utf8');
const extract = helpers + ';\n' + fs.readFileSync('app/src/main/assets/extract-facebook.js','utf8');
const fixture = fs.readFileSync('work/fixtures/facebook-public-computer.html','utf8');
const url = 'https://www.facebook.com/marketplace/item/1120292097099668/';
const source = 'https://www.facebook.com/marketplace/114987875182625/search?minPrice=100&maxPrice=10000&sortBy=distance_ascend&query=%E9%9B%BB%E8%85%A6&exact=false&radius=250';
const expected = {url,title:'電腦主機含電腦螢幕'};
let checks=0;
const equal=(a,b,message)=>{assert.equal(a,b,message);checks++;};
(async()=>{
  const browser=await chromium.launch({channel:'msedge',headless:true});
  try {
    const page=await browser.newPage({viewport:{width:392,height:816}});
    // All HTML and images are local fixtures; no authenticated/real network requests.
    let html=fixture;
    await page.route('**/*',route=>route.fulfill({contentType:'text/html; charset=utf-8',body:html}));
    const load=async(body,at=url)=>{html=body;await page.goto(at);};
    const read=async(target=expected)=>{
      await page.evaluate(target=>{window.__shopRadarFacebookListing=target;},target);
      return JSON.parse(await page.evaluate(helpers+';\n'+detail));
    };
    await load(fixture);
    let result=await read();
    equal(result.url,url);equal(result.state,'ready');equal(result.listedAt,'6天前');
    assert.ok(Math.abs(Date.now()-result.listedAtEpochMillis-6*86400000)<5000);checks++;
    // The same public header is parsed on narrow phones and landscape tablets.
    for(const width of [360,851,1280]){
      await page.setViewportSize({width,height:816});
      equal((await read()).listedAt,'6天前');
    }
    for(const [line,date] of [
      ['約58分鐘前於屏東市, 屏東縣上架','約58分鐘前'],
      ['約一小時前於屏東市上架','約一小時前'],
      ['約一天前上架','約一天前'],
      ['Listed about 2 hours ago in Pingtung','約2 小時前'],
      ['上架：2026/01/02','2026-01-02'],
      ['上架時間：約一小時前','約一小時前'],
      ['上架於 約58分鐘前','約58分鐘前'],
      ['昨天購買的手機',''],
      ['Last active 2 hours ago',''],
      ['更新：58分鐘前',''],
      ['售出：2026/01/01',''],
      ['','']
    ]){
      await load('<main><div><h1>'+expected.title+'</h1><span>'+line+'</span></div><section><a href="/marketplace/item/1863651624626014/">別的商品 約58分鐘前上架</a></section></main>');
      equal((await read()).listedAt||'',date,'Only a listing-labelled primary-header date may be used: '+line);
    }
    // Date-only text in the title or a seller panel is not listing evidence.
    await load('<main><div><h1>約一小時前</h1><p>NT$3,000</p></div><p>約一天前上架</p></main>');
    equal((await read({url,title:'約一小時前'})).listedAt||'','','Do not borrow a date outside the primary header');
    await load('<main><div><h1><span>上架：約一小時前</span></h1><p>NT$3,000</p></div></main>');
    equal((await read({url,title:'上架：約一小時前'})).listedAt||'','','A date-like span inside the product title is not listing evidence');
    await load(fixture.replace('<abbr aria-label="6天前"><span>6天前</span></abbr>',''));
    equal((await read()).listedAt||'','','Missing primary time cannot borrow recommendation/seller dates');
    await load(fixture.replace('1120292097099668/"></head>','1863651624626014/"></head>'));
    equal((await read()).state,'unmatched','Wrong canonical is never the requested item');
    await load(fixture,'https://www.facebook.com/marketplace/item/1863651624626014/');
    equal((await read()).state,'unmatched','Reject a redirect to another listing');
    await load(fixture);
    equal((await read({...expected,title:'不同商品'})).state,'loading','A matching primary title is required');
    await load(fixture.replace('aria-hidden="false"','aria-hidden="true"'));
    equal((await read()).state,'loading','Do not use a hidden heading');
    await load(fixture.replace('class="xyamay9 xv54qhq x18d9i69 xf7dkkf"','style="display:none"'));
    equal((await read()).state,'loading','A hidden ancestor must not make a heading/date readable');
    await load(fixture.replace('<abbr aria-label="6天前">','<abbr style="display:none" aria-label="6天前">'));
    equal((await read()).listedAt||'','','A hidden time cannot be borrowed through its visible parent');
    // Search layout selected by the user: real href with price/title/location.
    const card='<a href="'+url+'?ref=search&referral_code=null" aria-label="電腦主機含電腦螢幕，NT$3,000，屏東縣，商品1120292097099668">'+
      '<div><img src="https://scontent.example.test/computer.jpg"><div>NT$3,000</div><div aria-hidden="true">電腦主機含電腦螢幕</div><div aria-hidden="true">屏東縣</div></div></a>';
    await load(card,source);
    result=JSON.parse(await page.evaluate(extract));
    equal(result.items[0].url,url,'Real href is immediately usable, without a card-click resolver');
    equal(result.items[0].title,expected.title);equal(result.items[0].price,3000);equal(result.items[0].listedAt,'');
    await load(card.replaceAll(expected.title,'約一小時前'),source);
    result=JSON.parse(await page.evaluate(extract));
    equal(result.items[0].listedAt,'','A search title that looks like a date is not the listing time');
    const main=fs.readFileSync('app/src/main/java/com/aykkdk/shop/MainActivity.kt','utf8');
    const details=main.slice(main.indexOf('private fun openListingDetails('));
    assert.doesNotMatch(details,/FacebookCardActivity|loadUrl|evaluateJavascript/);checks++;
    assert.match(details,/openUri\(actual\)/);checks++;
    assert.match(main,/FacebookListingDates\(visibleProducts, resultListState, enabled = !fetching\)/);checks++;
    const readers=fs.readFileSync('app/src/main/java/com/aykkdk/shop/FacebookListingDates.kt','utf8');
    assert.match(readers,/product.url in onScreen/);checks++;
    assert.match(readers,/take\(FacebookListingMetadata.MAX_READERS\)/);checks++;
    assert.match(readers,/Lifecycle.State.RESUMED/);checks++;
    assert.match(readers,/reader.close\(\)/);checks++;
    assert.doesNotMatch(readers,/getCookie\(|startHtmlCapture|JavascriptInterface|fetch\(/);checks++;
    console.log('PASS: '+checks+' assertions; observed public detail header; relative/absolute listing dates; no seller/recommendation/title dates; direct href; no on-click network resolution; visible-only bounded foreground readers.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
