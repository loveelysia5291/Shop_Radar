const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const extract = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js','utf8') + ';\n' +
  fs.readFileSync('app/src/main/assets/extract-facebook.js','utf8');
const resolve = fs.readFileSync('app/src/main/assets/resolve-facebook-card.js','utf8');
const source = 'https://www.facebook.com/marketplace/114987875182625/search?query=phone&radius=250';
const url = 'https://www.facebook.com/marketplace/item/1863651624626014/';
const label = 'Phone for sale - Used - NT$1,000 in Taipei';
const image = 'https://scontent.example/phone.jpg';
const card = date => `<div data-action-id="opaque-ui-handle" data-type="container"><div data-mcomponent="ServerImageArea" aria-label="${label}"><img src="${image}"></div><span>${date}</span></div>`;
(async () => {
  const browser = await chromium.launch({channel:'msedge',headless:true});
  try {
    const page = await browser.newPage();
    await page.route('**/*', route => route.fulfill({contentType:'text/html',body:'<main></main>'}));
    const load = async html => { await page.goto(source); await page.evaluate(html => {document.body.innerHTML=html;},html); };
    const read = async () => JSON.parse(await page.evaluate(extract));
    for (const [date,seconds] of [['約一小時前',3600],['約58分鐘前',3480],['約一天前',86400],['約兩小時前',7200],['大約十一分鐘前',660],['58分前',3480]]) {
      await load(card(date)); const item=(await read()).items[0];
      assert.equal(item.listedAt,date);
      assert.ok(Math.abs((Date.now()-item.listedAtEpochMillis)/1000-seconds) < 5);
      assert.match(item.url,/#shopradar-fb-card=/,'Do not invent a listing ID from the UI handle/photo');
    }
    await load(card('Posted about 2 hours ago'));
    assert.equal((await read()).items[0].listedAt,'約2 小時前');
    for (const text of ['剛剛','昨天','今天']) {
      await load(card(text)); assert.equal((await read()).items[0].listedAt,text);
    }
    for (const text of ['', '更新：58分鐘前','Last active 2 hours ago','約一天前購買的手機','售出：2026/01/01','2026/02/31']) {
      await load(card(text)); assert.equal((await read()).items[0].listedAt,'','Missing/update/activity/title/invalid dates are not listing dates');
    }
    await load(card('<time datetime="2026-01-02T03:04:05Z">刊登時間</time>'));
    assert.equal((await read()).items[0].listedAt,'2026-01-02 11:04:05');
    await load(card('<time datetime="2026-01-02T24:00:00Z">刊登時間</time>'));
    assert.equal((await read()).items[0].listedAt,'','Do not silently roll invalid hours into the next day');
    await load(card('上架：2026/01/02'));
    assert.equal((await read()).items[0].listedAt,'2026-01-02');
    await load(`<section><a href="${url}?ref=search&referral_code=null&referral_story_type=post&__tn__=!%3AD" aria-label="Phone，NT$1,000"><img src="${image}"></a><span>約58分鐘前</span></section>`);
    const linked=(await read()).items[0]; assert.equal(linked.url,url); assert.equal(linked.listedAt,'約58分鐘前');
    await load(card('約一天前').replace('data-action-id=',`data-href="${url}?ref=search" data-action-id=`));
    assert.equal((await read()).items[0].url,url,'A supplied real data-href is immediately usable');
    const record={id:'1863651624626014',marketplace_listing_title:'Phone',listing_price:{amount:'1000',currency:'TWD'},primary_listing_photo:{image:{uri:image}},creation_time:1767323045};
    await load(card('')+`<script type="application/json">${JSON.stringify({listing:record,seller:{creation_time:1700000000},updated_time:1700000000})}</script>`);
    const joined=(await read()).items;
    assert.equal(joined.length,1,'Match the same title/price/photo record without duplicating the card');
    assert.equal(joined[0].resolvedUrl,url);
    assert.equal(joined[0].listedAt,'2026-01-02 11:04:05');
    delete record.creation_time;
    await load(`<script type="application/json">${JSON.stringify({...record,updated_time:1767323045,seller:{creation_time:1767323045}})}</script>`);
    assert.equal((await read()).items[0].listedAt,'');
    await load(`<script type="application/json">${JSON.stringify({...record,id:9007199254740992})}</script>`);
    assert.equal((await read()).items.length,0,'Do not build URLs from rounded numeric IDs');
    await load(`<div><a href="${url}" aria-label="Phone，NT$1,000"><img src="${image}"></a></div><div><a href="/marketplace/item/1863651624626022/" aria-label="Other，NT$2,000"><img src="https://scontent.example/other.jpg"></a><span>約一天前</span></div>`);
    const neighbours=(await read()).items;
    assert.equal(neighbours[0].listedAt,''); assert.equal(neighbours[1].listedAt,'約一天前');
    await load(card(''));
    const target=JSON.parse(decodeURIComponent(new URL((await read()).items[0].url).hash.slice('#shopradar-fb-card='.length)));
    await page.evaluate(target=>{window.__shopRadarFacebookCard=target;window.clicks=0;document.querySelector('[data-action-id]').addEventListener('click',()=>window.clicks++);},target);
    assert.equal(await page.evaluate(resolve),'clicked');
    assert.equal(await page.evaluate(resolve),'waiting');
    assert.equal(await page.evaluate(()=>window.clicks),1);
    // Install the legitimate card handler after the first early click, as hydration does.
    await page.evaluate(()=>{window.__shopRadarFacebookCardClickAt=Date.now()-1501;document.querySelector('[data-action-id]').addEventListener('click',()=>history.pushState({},'', '/marketplace/item/1863651624626014/?ref=search'));});
    assert.equal(await page.evaluate(resolve),'clicked');
    assert.equal(await page.evaluate(resolve),url);
    assert.equal(await page.evaluate(()=>window.clicks),2);
    await load(card('')); await page.evaluate(target=>{window.__shopRadarFacebookCard=target;window.clicks=0;document.querySelector('[data-action-id]').addEventListener('click',()=>window.clicks++);},target);
    for(let i=0;i<6;i++){await page.evaluate(()=>{window.__shopRadarFacebookCardClickAt=Date.now()-1501;});await page.evaluate(resolve);}
    assert.equal(await page.evaluate(()=>window.clicks),3,'Bound repeated card clicks');
    const activity=fs.readFileSync('app/src/main/java/com/example/shopradar/FacebookCardActivity.kt','utf8');
    assert.match(activity,/override fun onCreateWindow/);assert.match(activity,/doUpdateVisitedHistory/);
    assert.match(activity,/FacebookResolvedLinks.remember/);assert.match(activity,/disposePopup\(\)/);
    console.log('PASS: supplied real item URL; approximate Chinese dates; ISO/record dates; no seller/update/neighbor dates; direct links and verified JSON cache mapping; delayed-handler retry and 3-click bound; native navigation/window guards present.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
