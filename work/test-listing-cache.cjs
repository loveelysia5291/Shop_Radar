const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/public-listing-time.js', 'utf8');
const helperSource = fs.readFileSync('app/src/main/assets/facebook-public-metadata.js', 'utf8');
let parses = 0;
const callbacks = new Map();
const scripts = [{textContent: JSON.stringify({privateAccount: {token: 'not-retained'}, items: [
  {itemid: 87654321, shopid: 123, ctime: 1767323045},
  {itemid: 87654322, shopid: 123, dateModified: 1767323045}
]})}];
const window = {};
const context = vm.createContext({window, URL, location: new URL('https://shopee.tw/search?keyword=手機'),
  document: {querySelector: () => null, querySelectorAll: () => scripts},
  JSON: {parse: raw => {parses++; return JSON.parse(raw)}},
  MutationObserver: class {constructor(callback){this.callback=callback} observe(node){callbacks.set(node,this.callback)}}});
vm.runInContext(helperSource, context);
const helperIdentity = window.__shopRadarFacebookMetadata;
vm.runInContext(helperSource, context);
assert.equal(window.__shopRadarFacebookMetadata, helperIdentity, 'Helper initialization is once per document');
const read = () => {vm.runInContext(source, context); return window.__shopRadarPublicListingTime.structured('https://shopee.tw/product/123/87654321')};
assert.equal(read().listedAtEpochMillis, 1767323045000);
assert.equal(parses, 1);
for(let n=0;n<10;n++) read();
assert.equal(parses, 1, 'Repeated capture polls reuse public extracted timestamps, not reparsing entire JSON');
assert.equal(window.__shopRadarPublicListingTime.structured('https://shopee.tw/product/124/87654321').listedAt, '');
assert.equal(window.__shopRadarPublicListingTime.structured('https://shopee.tw/product/123/87654322').listedAt, '');
const cache = window.__shopRadarListingScriptCache.get(scripts[0]);
assert.equal('raw' in cache, false);
assert.equal('privateAccount' in cache, false);
assert.equal(JSON.stringify([...cache.dates.values()]).includes('not-retained'), false);
scripts[0].textContent = JSON.stringify({items:[{itemid:87654321,shopid:123,ctime:1767409445}]});
callbacks.get(scripts[0])();
assert.equal(read().listedAtEpochMillis, 1767409445000);
assert.equal(parses, 2, 'Script mutation invalidates cache');
context.location = new URL('https://shopee.tw/search?keyword=電腦');
read(); assert.equal(parses, 3, 'SPA route changes invalidate cache');
scripts.push({textContent:JSON.stringify({itemid:87654323,shopid:123,ctime:1767409445})});
read(); assert.equal(parses, 4, 'New lazy JSON scripts are still parsed');
assert.equal(window.__shopRadarPublicListingTime.structured('https://shopee.tw/product/123/87654323').listedAtEpochMillis, 1767409445000);
console.log('PASS: product-identifying listing metadata cache avoids repeated parsing; mutation/routes/lazy scripts invalidate safely, no session state retained.');
