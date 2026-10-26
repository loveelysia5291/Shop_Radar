const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/extract-products.js', 'utf8');
const code = source.slice(source.indexOf('  function pageShopName('), source.indexOf('  function isAvatar('));
function name(path, headings, cardName = '') {
  const location = new URL('https://tw.carousell.com' + path);
  return vm.runInNewContext(code + '\npageShopName(card)', {
    host: 'tw.carousell.com', location, pageUrl: location, yahooStore: false,
    document: {
      querySelectorAll: selector => { assert.equal(selector, 'h1'); return headings.map(textContent => ({textContent})); },
      querySelector: () => null
    },
    card: {querySelector: () => cardName ? {textContent: cardName} : null}
  });
}
const displayName = '二手不退換高標勿買 急單看關於我';
assert.equal(name('/u/2hanhandgood2/', [displayName, displayName]), displayName);
assert.equal(name('/u/small09/', ['David']), 'David');
assert.equal(name('/2hanhandgood2/', [displayName]), displayName);
assert.equal(name('/u/2hanhandgood2/', [], 'another listing seller'), '');
assert.equal(name('/u/2hanhandgood2/', ['店家甲', '店家乙']), '');
assert.equal(name('/search/phone/', ['商品搜尋'], '商品賣家'), '商品賣家');
assert.equal(name('/p/phone-1234567890/', ['手機標題'], '商品賣家'), '商品賣家');
console.log('PASS: Carousell profile uses distinct rendered h1, not account/OG title; search and product headings never become store names.');
