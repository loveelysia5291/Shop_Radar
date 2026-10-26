const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/extract-products.js', 'utf8');
const start = source.indexOf('  function yahooDetail(');
const end = source.indexOf('  // Carousell', start);
const context = vm.createContext({URL, meaningfulTitle: value => value || ''});
vm.runInContext(source.slice(start, end), context);
const url = 'https://tw.bid.yahoo.com/item/101764768005';
function detail(item, serverTs = 1800000000000) {
  return context.yahooDetail('<script id="isoredux-data">' + JSON.stringify({item, page: {serverTs}}) + '</script>', url);
}
const item = {id: '101764768005', type: 2, title: 'Phone', bidInfo: {
  currentPrice: 1200, endTime: 1800000000, totalBids: 2,
  highestBidders: [{ecid: 'Y123***', nickname: '同名暱稱'}]
}};
const result = detail(item);
assert.equal(result.price, 1200);
assert.equal(result.auctionEndsAt, 1800000000000);
assert.equal(result.highestBidder, 'Y123***');
assert.equal(result.auctionPriceKnown, true);
assert.equal(result.auctionServerAt, 1800000000000);
assert.equal(result.auctionBidCount, 2);
assert.equal(detail(item, 1800000000).auctionServerAt, 1800000000000);
assert.equal(detail(item, null).auctionServerAt, 0);
assert.equal(detail({...item, bidInfo: {...item.bidInfo, highestBidders: [{nickname: 'Y123***'}]}}).highestBidder, '');
assert.equal(detail({...item, bidInfo: {...item.bidInfo, highestBidders: [{ecid: 'Y1234', nickname: '同名'}]}}).highestBidder, 'Y1234');
assert.equal(detail({...item, id: '999999999'}).isAuction, undefined);
assert.equal(detail({...item, bidInfo: {...item.bidInfo, highestBidders: []}}).highestBidder, '');
assert.equal(detail({...item, bidInfo: {totalBids: 0, currentPrice: null}}).auctionPriceKnown, false);
assert.equal(detail({...item, bidInfo: {totalBids: 0}}).highestBidder, '尚無人出價');
console.log('PASS: Yahoo current bid; account IDs not nicknames; masked IDs; server/end timestamps; bid count; missing fields; item identity.');
