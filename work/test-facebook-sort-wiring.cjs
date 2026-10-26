const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/example/shopradar/';
const main = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const market = fs.readFileSync(root + 'Market.kt', 'utf8');
const location = fs.readFileSync(root + 'FacebookLocation.kt', 'utf8');
assert.doesNotMatch(main, /facebookSort by rememberSaveable|appliedFacebookSort|selected == Market.FACEBOOK.label\) item/);
// Preserve the legacy URL formatter and listing-time display for stored records.
assert.match(market, /FacebookLocation.searchUrl\(keyword, min, max, facebookSort, facebookLocationId\)/);
assert.match(location, /"sortBy" to FacebookFilters.sort\(sort\)/);
assert.match(location, /DEFAULT_ID = "114987875182625"/);
assert.match(location, /"radius" to "250"/);
assert.match(location, /"exact" to "false"/);
assert.match(main, /ListingTimeDisplay.text\(product.platform, product.listedAt, product.listedAtEpochMillis\)/);
assert.match(main, /ListingTimeDisplay.text\(item.optString\("platform"\), item.optString\("listedAt"\), item.optLong\("listedAtEpochMillis"\)\)/);
console.log('PASS: retired Facebook sorting UI stays absent; legacy URL compatibility and listing-date presentation retained.');
