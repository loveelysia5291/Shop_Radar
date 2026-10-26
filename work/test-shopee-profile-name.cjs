const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/extract-shopee-products.js', 'utf8');
function read(path, expected = path, mode = 'title') {
  const name = '💛🧡華心3C代購🧡💛';
  const heading = {isConnected:true, parentElement:null, textContent:name, innerText:name,
    closest:()=>null, getClientRects:()=>[{width:180,height:28}]};
  const context = {URL, console, innerHeight:700, location:new URL('https://shopee.tw'+path),
    __shopRadarShopeeEnvelope:true, __shopRadarExpectedSearchUrl:'https://shopee.tw'+expected,
    __shopRadarShopeeStoreUrl:'https://shopee.tw/z800606z',
    getComputedStyle:()=>({display:'block', visibility:'visible', opacity:'1'}),
    document:{title: mode === 'title' ? name + ', 線上商店 | 蝦皮購物' : '蝦皮購物',
      querySelector:()=>null, querySelectorAll:s=>(mode === 'desktop' && s.includes('h1.section-seller') || mode === 'mobile' && s === 'h1')?[heading]:[],
      scrollingElement:{scrollTop:0,scrollHeight:700},documentElement:{scrollTop:0,scrollHeight:700}}};
  context.window=context; context.top=context;
  return JSON.parse(vm.runInNewContext(source,context));
}
const page = read('/z800606z?page=0&sortBy=ctime');
assert.equal(page.items.length, 0);
assert.equal(page.shopName, '💛🧡華心3C代購🧡💛', 'Name must be delivered even before products exist');
assert.equal(read('/buyer/login').shopName, '');
assert.equal(read('/other_shop').shopName, '');
assert.equal(read('/search?keyword=phone').shopName, '');
assert.equal(read('/z800606z?page=1', '/z800606z?page=0').shopName, '');
for (const mode of ['title']) {
  const url = '/z800606z#product_list';
  assert.equal(read(url, url, mode).shopName, page.shopName);
  assert.equal(read('/buyer/login', '/buyer/login', mode).shopName, '');
  assert.equal(read('/search', '/search', mode).shopName, '');
  assert.equal(read('/other_shop', '/other_shop', mode).shopName, '');
}
assert.equal(read('/z800606z', '/z800606z', 'missing').shopName, '');
for (const mode of ['desktop', 'mobile']) assert.equal(read('/z800606z', '/z800606z', mode).shopName, '', 'Headings must not substitute for the document title');
assert.doesNotMatch(source.slice(source.indexOf('function shopName()'), source.indexOf('function advertisement')), /querySelector|seller-name|shop-profile-name|section-seller-overview-horizontal__portrait-name/);
const native = fs.readFileSync('app/src/main/java/com/example/shopradar/MarketplaceCapture.kt','utf8');
assert.match(native, /SubscriptionIdentity.sameShop\(request.url, envelope.optString\("shopUrl"\)\)/);
assert.match(native, /SubscriptionIdentity.sameShop\(request.url, web.url.orEmpty\(\)\)/);
console.log('PASS: Shopee delivers rendered shop name independently of product cards, with private/wrong/search/stale routes rejected.');
