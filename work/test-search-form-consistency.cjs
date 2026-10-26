const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/aykkdk/shop/';
const main = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const start = main.indexOf('0 -> Box(modifier, contentAlignment = Alignment.TopCenter)');
const button = main.indexOf('if (!fetching) Button(onClick =', start);
assert.ok(start >= 0 && button > start);
const form = main.slice(start, button);
const low = form.indexOf('OutlinedTextField(min,');
const high = form.indexOf('OutlinedTextField(max,');
assert.ok(low >= 0 && high > low, 'Shared price row is minimum left, maximum right');
assert.equal((form.match(/OutlinedTextField\(min,/g) || []).length, 1);
assert.equal((form.match(/OutlinedTextField\(max,/g) || []).length, 1);
for (const platform of ['CAROUSELL', 'BBOBO', 'SHOPEE', 'RUTEN', 'YAHOO']) {
  const position = form.indexOf(`if (selected == Market.${platform}.label) item`);
  assert.ok(position >= 0 && position < low, `${platform}: all platform controls precede prices`);
}
assert.doesNotMatch(form.slice(low), /CategoryDropdown|FilterDropdown|CategorySelectors|YahooFilterControls|selected ==/,
  'No platform can put extra controls between prices and Search; IOPEN uses the same final row');
assert.match(form.slice(high), /\}\s*\}\s*item \{\s*$/, 'Price row directly precedes the Search action item');
assert.equal((form.match(/it\.filter\(Char::isDigit\)\.take\(9\)/g) || []).length, 2, 'Keep numeric price bounds');
assert.match(form, /CategoryDropdown\("排序", carousellSort,\s*SearchHistoryCriteria\.CAROUSELL_SORTS\.map \{ it to it \}/);
assert.doesNotMatch(form, /sortExpanded|Text\(carousellSort, maxLines = 2\)/, 'Carousell no longer has a different unlabelled button');
function before(source, first, second, message) {
  const a = source.indexOf(first);
  const b = source.indexOf(second);
  assert.ok(a >= 0 && b > a, message);
}
const carousell = form.slice(form.indexOf('if (selected == Market.CAROUSELL.label)'), form.indexOf('if (selected == Market.BBOBO.label)'));
before(carousell, 'CategoryDropdown("排序"', 'CategorySelectors(Market.CAROUSELL', 'Carousell sorting precedes categories');
const yahooForm = form.slice(form.indexOf('if (selected == Market.YAHOO.label)'), low);
before(yahooForm, 'YahooFilterControls(', 'CategorySelectors(Market.YAHOO', 'Yahoo sorting precedes categories too');
const shopee = form.slice(form.indexOf('if (selected == Market.SHOPEE.label)'), form.indexOf('if (selected == Market.RUTEN.label)'));
before(shopee, 'CategoryDropdown("排序"', 'CategoryDropdown("商品狀況"', 'Shopee sorting stays left');
before(shopee, 'CategoryDropdown("商品狀況"', 'FilterDropdown("賣場類型"', 'Shop types follow sorting/condition');
assert.match(shopee, /shopeeMall = !shopeeMall/);
assert.match(shopee, /shopeePreferred = !shopeePreferred/);
const yahoo = main.slice(main.indexOf('private fun YahooFilterControls'), main.indexOf('private fun ShopSubscriptions'));
before(yahoo, 'CategoryDropdown("排序"', 'CategoryDropdown("狀態"', 'Sort stays left on both Yahoo and Shopee');
assert.match(yahoo, /YahooFilters.sorts, Modifier.weight\(1f\), onSelect = onSort/);
assert.match(yahoo, /YahooFilters.states, Modifier.weight\(1f\), onSelect = onState/);
const category = main.slice(main.indexOf('private fun CategorySelectors'), main.indexOf('private fun YahooFilterControls'));
before(category, 'CategoryDropdown("大分類"', 'CategoryDropdown("小分類"', 'Parent category stays left');
assert.match(category, /請先選擇前項/);
assert.match(category, /enabled = main\?\.children\?\.isNotEmpty\(\) == true/);
for (const name of ['CategoryDropdown', 'FilterDropdown']) {
  assert.match(main, new RegExp(`private fun ${name}\\([\\s\\S]*?compact: Boolean = true`), `${name}: common compact style`);
}
assert.match(category, /Arrangement.spacedBy\(8.dp\)/);
assert.match(yahoo, /Arrangement.spacedBy\(8.dp\)/);
assert.match(main.slice(button), /min.isNotBlank\(\) && max.isNotBlank\(\) && min.toLong\(\) > max.toLong\(\)/, 'Price validation remains');
console.log('PASS: every platform uses prices immediately above Search, consistent sort/status and category order, shared compact dropdowns, unchanged filter bindings and price validation. Device visual QA remains required.');
