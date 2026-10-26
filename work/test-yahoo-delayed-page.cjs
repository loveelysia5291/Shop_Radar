const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
(async () => {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page=await browser.newPage();
    await page.route('**/*',route=>route.fulfill({contentType:'text/html',body:'<main></main>'}));
    await page.goto('https://tw.bid.yahoo.com/search/auction/product?p=fixture');
    const source=fs.readFileSync('app/src/main/assets/extract-products.js','utf8');
    await page.evaluate(()=>{
      window.__shopRadarQuickSearch=true;
      window.__shopRadarCaptureStarted=Date.now()-6000;
      window.deliveries=[];
      window.previews=[];
      window.ShopRadarYahoo={deliver:json=>window.deliveries.push(JSON.parse(json)),preview:json=>window.previews.push(JSON.parse(json))};
      window.fetch=async()=>({ok:true,text:async()=>''});
    });
    for(let i=0;i<4;i++) await page.evaluate(source);
    assert.equal(await page.evaluate(()=>window.deliveries.length),0,'An empty 6s loading page must not seal the bridge');
    await page.evaluate(()=>{
      document.querySelector('main').innerHTML=Array.from({length:18},(_,i)=>`<article><a href="/item/${101234567890+i}"><h3>Phone ${i}</h3><span>NT$1,000</span></a></article>`).join('');
    });
    for(let i=0;i<5;i++) await page.evaluate(source);
    await page.waitForFunction(()=>window.deliveries.length===1);
    assert.equal(await page.evaluate(()=>window.deliveries[0].length),18);
    assert.ok(await page.evaluate(()=>window.previews.some(items=>items.length===18)));
    assert.ok(await page.evaluate(()=>window.deliveries[0].every(item=>item.listedAt==='')),'Missing dates are not fabricated');
    console.log('PASS: Yahoo first cards arriving after 6 seconds are retained and delivered once, not lost to the former 4.5-second empty bridge.');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
