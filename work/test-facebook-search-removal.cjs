// Facebook search is restored; only the location picker remains removed.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/aykkdk/shop/';
const main = fs.readFileSync(root+'MainActivity.kt','utf8');
const history = fs.readFileSync(root+'SearchHistory.kt','utf8');
const market = fs.readFileSync(root+'Market.kt','utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
assert.match(market,/supportsSearch: Boolean get\(\) = true/);
assert.match(market,/searchMarkets: List<Market> = values\(\).filter \{ it.supportsSearch \}/);
assert.match(main,/val selected = Market.searchSelection\(selectedLabel\).label/);
assert.match(main,/Market.searchMarkets.map \{ it.label \}.forEach/);
assert.equal((main.match(/queue = ArrayList\(Market.searchMarkets.filter/g)||[]).length, 2, 'search and retry use available platforms');
assert.match(main,/if \(!market.supportsSearch\) \{\s*stopSearch\(\)/, 'dispatch rejects legacy queued requests');
assert.match(main,/if \(!criteria.market.supportsSearch \|\| criteria.market != historyMarket/);
assert.doesNotMatch(main,/FacebookLocationDialog|FacebookLocationStore|FacebookLocationActivity|showFacebookLocation|appliedFacebookLocation/);
assert.match(main,/if \(selected == Market.FACEBOOK.label\) item/);
assert.match(main,/facebookSort = appliedFacebookSort/);
for (const name of ['FacebookLocationActivity', 'FacebookLocationUi', 'FacebookLocationStore']) {
  assert.equal(fs.existsSync(root+name+'.kt'), false, 'obsolete picker source removed');
  assert.ok(!manifest.includes(name), 'obsolete picker cannot be launched');
}
assert.match(history,/put\("facebookLocationId", c.facebookLocationId\)/);
assert.match(history,/optString\("facebookLocationId", FacebookLocation.DEFAULT_ID\)/);
assert.match(main,/FacebookListingDates\(/, 'subscription date reader retained');
assert.doesNotMatch(main,/facebook_locations_v1|searchHistoryEntries.filter.*FACEBOOK/, 'no automatic data wipe');
assert.doesNotMatch(manifest,/ACCESS_(?:FINE|COARSE|BACKGROUND)_LOCATION/);
console.log('PASS: Facebook search, sorting and history restored; no location picker or location permission; subscriptions retained.');
