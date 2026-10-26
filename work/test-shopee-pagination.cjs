const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const source = fs.readFileSync('app/src/main/assets/extract-shopee-products.js', 'utf8');
const capture = fs.readFileSync('app/src/main/java/com/example/shopradar/MarketplaceCapture.kt', 'utf8');
const main = fs.readFileSync('app/src/main/java/com/example/shopradar/MainActivity.kt', 'utf8');
const market = fs.readFileSync('app/src/main/java/com/example/shopradar/Market.kt', 'utf8');
const card = id => `<a href="/product/100/${id}"><h3>Phone ${id}</h3><span>$${id}</span></a>`;
(async () => {
  const browser = await chromium.launch({channel:'msedge', headless:true});
  try {
    const page = await browser.newPage({viewport:{width:392,height:816}});
    await page.route('**/*', route => route.fulfill({contentType:'text/html', body:'<main></main>'}));
    const url = 'https://shopee.tw/search?keyword=phone&page=1&sortBy=sales';
    await page.goto(url);
    await page.evaluate(url => {
      window.__shopRadarShopeeEnvelope = true;
      window.__shopRadarExpectedSearchUrl = url;
      window.__shopRadarPreviousPageUrls = [100,101,102,103].map(id=>`https://shopee.tw/product/100/${id}`);
      document.querySelector('main').innerHTML = '<span class="shopee-mini-page-controller__current">1</span><span class="shopee-mini-page-controller__total">9</span><ul class="shopee-search-item-result__items"></ul>';
    }, url);
    const fill = async (count, start=200) => page.evaluate(({count,start}) => {
      const create = id => `<a href="/product/100/${id}"><h3>Phone ${id}</h3><span>$${id}</span></a>`;
      document.querySelector('ul').innerHTML = Array.from({length:20},(_,i)=>`<li class="shopee-search-item-result__item" style="min-height:140px">${i<count?create(start+i):'<div data-testid="loading-skeleton">Loading</div>'}</li>`).join('');
    }, {count,start});
    const read = async () => JSON.parse(await page.evaluate(source));
    await fill(4,100);
    let result=await read();
    assert.equal(result.state.pageVerified,false);
    assert.equal(result.items.length,0,'URL page=1 cannot authorize rendered page 1');
    await page.evaluate(()=>document.querySelector('span').textContent='2');
    result=await read();
    assert.equal(result.state.previousPageOnly,true);
    assert.equal(result.items.length,0,'Even without an old page label, an all-old preview is rejected');
    await fill(4);
    result=await read();
    assert.equal(result.items.length,4);
    assert.equal(result.state.slots,20);
    assert.equal(result.state.totalPages,9,'Read the official page limit instead of offering fictitious pages');
    assert.equal(result.state.complete,false,'Four valid products plus skeleton rows are unfinished');
    await fill(16);
    result=await read();
    assert.equal(result.items.length,16);
    assert.equal(result.state.complete,false,'Sixteen is not a cap or a finish signal');
    await fill(20);
    await page.evaluate(html=>document.querySelector('main').insertAdjacentHTML('beforeend',`<aside>${html}</aside>`),card(999));
    result=await read();
    assert.equal(result.items.length,20);
    assert.equal(result.state.complete,true);
    assert.equal(result.state.atBottom,false,'Rendered cards alone do not prove every lazy row was visited');
    assert.ok(result.items.every(p=>!p.url.endsWith('/999')),'Outside recommendations do not belong to the numbered page');
    await page.evaluate(()=>document.querySelector('ul').append(document.querySelector('li').cloneNode(true)));
    result=await read();
    assert.equal(result.state.slots,21);
    assert.equal(result.items.length,20);
    assert.equal(result.state.complete,true,'Duplicate variant slots do not make a complete page look unfinished');
    await page.evaluate(()=>window.__shopRadarExpectedSearchUrl='https://shopee.tw/search?keyword=phone&page=1&sortBy=ctime');
    result=await read();
    assert.equal(result.state.requestMatches,false);
    assert.equal(result.items.length,0,'A stale different sort is not accepted');
    await page.evaluate(() => {
      const filtered = new URL(location.href);
      filtered.searchParams.set('fe_filter_options', JSON.stringify([
        {group_name:'SHOP_TYPE', values:['PREFERRED','OFFICIAL_MALL']},
        {group_name:'CONDITION', values:['USED_ITEM']}
      ]));
      window.__shopRadarExpectedSearchUrl = filtered.href;
    });
    result=await read();
    assert.equal(result.state.requestMatches,false);
    assert.equal(result.items.length,0,'Cards from an unfiltered route must not satisfy the filtered request');
    await page.evaluate(()=>history.replaceState(null,'',window.__shopRadarExpectedSearchUrl));
    result=await read();
    assert.equal(result.state.requestMatches,true);
    assert.equal(result.items.length,20,'Combined shop and condition filters remain accepted on the requested page');
    await page.evaluate(()=>window.__shopRadarExpectedSearchUrl=location.href);
    await page.evaluate(source);
    const before=await page.evaluate(()=>scrollY);
    await page.evaluate(()=>window.__shopRadarShopeeScroll());
    const after=await page.evaluate(()=>scrollY);
    assert.ok(after>before && after-before<=816,'Scroll visits rows in viewport-sized steps, not the footer');
    assert.match(capture,/pageCompletion.ready\(signature, shopeePageReady, now\)/);
    assert.match(capture,/shopeePageReady = state.optBoolean\("atBottom"\)/);
    assert.doesNotMatch(capture,/CaptureBudgets\.publishShopee/);
    assert.match(main,/stagedProducts\.removeAll \{ it\.platform == Market\.SHOPEE\.label \}/);
    assert.match(main,/shopeeSort = appliedShopeeSort/);
    for (const field of ['Mall', 'Preferred', 'Condition']) {
      assert.match(main, new RegExp(`shopee${field} = appliedShopee${field}`), 'Paging uses the applied filter, not the form draft');
      assert.equal((main.match(new RegExp(`appliedShopee${field} = shopee${field}`, 'g')) || []).length, 2,
        'New search and retry both snapshot the selected filter');
    }
    assert.match(market,/ShopeeFilters\.filterOptions\(min, max, shopeeMall, shopeePreferred, shopeeCondition\)/);
    assert.match(main,/CategoryDropdown\("商品狀況", ShopeeFilters/);
    assert.match(main,/CategoryDropdown\("排序", ShopeeFilters/);
    assert.match(main,/currentPage < pageCount/);
    assert.match(main,/else if \(page != appliedPage\) previousShopeePageUrls/);
    assert.match(market,/ShopeeFilters\.parameters\(shopeeSort\)/);
    assert.doesNotMatch(main,/snapshotFlow \{ stagedProducts\.isNotEmpty\(\) \}/);
    await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
    assert.equal((await read()).state.atBottom,true,'Completion requires reaching the current product grid bottom');
    console.log('PASS: old-page previews rejected; 4 -> 16 -> 20 lazy slots; only primary search list; complete page requires bottom; incremental scroll; applied sorting retained when paging.');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
