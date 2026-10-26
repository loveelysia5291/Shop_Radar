const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/aykkdk/shop/';
const main = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const ui = fs.readFileSync(root + 'SearchHistoryUi.kt', 'utf8');
const store = fs.readFileSync(root + 'SearchHistoryStore.kt', 'utf8');
const save = main.slice(main.indexOf('fun saveSearchHistory()'), main.indexOf('fun restoreSearchHistory('));
const restore = main.slice(main.indexOf('fun restoreSearchHistory('), main.indexOf('LaunchedEffect(AdultVisibility.unlocked)'));
for (const platform of ['SHOPEE', 'CAROUSELL', 'YAHOO', 'RUTEN', 'FACEBOOK', 'BBOBO', 'IOPEN']) {
  assert.ok(save.includes(`Market.${platform} ->`), `save ${platform}`);
  assert.ok(restore.includes(`Market.${platform} ->`), `restore ${platform}`);
}
assert.match(save, /SearchHistory\.record\(searchHistoryEntries, criteria\)/);
assert.match(save, /searchHistoryStore\.save\(searchHistoryEntries\)/);
assert.match(restore, /criteria\.market != historyMarket/);
assert.match(restore, /criteria\.visible\(AdultVisibility\.unlocked\)/);
assert.match(restore, /!criteria.market.supportsSearch/);
assert.match(save, /Market.FACEBOOK -> SearchHistoryCriteria/);
assert.match(restore, /Market.FACEBOOK -> facebookSort = criteria.sort/);
for (const value of ['keyword', 'min', 'max', 'sort', 'condition', 'mall', 'preferred', 'mainCategory', 'category', 'yahooState', 'boboMall']) {
  assert.ok(restore.includes(`criteria.${value}`), `restore ${value}`);
}
assert.doesNotMatch(restore, /dispatch\+\+|searchToken\+\+|queue =|appliedQuery =|Market\.\w+\.search\(/);
assert.equal((main.match(/saveSearchHistory\(\)/g) || []).length, 2, 'declaration + explicit new-search button only');
assert.match(main, /else -> \{\s*saveSearchHistory\(\)\s*searchToken\+\+/);
const field = main.indexOf('BasicTextField(query');
const history = main.indexOf('SearchHistoryDropdown(historyMarket');
assert.ok(history > field && history < main.indexOf('if (selected == Market.CAROUSELL.label) item', field));
assert.match(main, /SearchHistory\.forMarket\(searchHistoryEntries, historyMarket, AdultVisibility\.unlocked\)/);
assert.match(main, /DropdownMenuItem\(text = \{ Text\("清除歷史紀錄"\)/);
assert.match(main, /searchHistoryEntries = if \(clearSearchHistory\) emptyList\(\)/);
assert.match(main, /SearchHistory\.delete\(searchHistoryEntries, it.id\)/);
assert.match(main, /!searchHistoryExpanded && deleteSearchHistory == null && !clearSearchHistory/);
assert.match(ui, /Icons.Default.ArrowDropDown/);
assert.match(ui, /Icons.Default.Close/);
assert.doesNotMatch(ui, /HorizontalDivider\(|Divider\(|BorderStroke\(/, 'Search history rows have no separator lines');
assert.doesNotMatch(ui, /Text\("\$\{market.label\}搜尋紀錄"/);
assert.match(ui, /if \(entries.isEmpty\(\)\) Text\("尚無搜尋紀錄"/);
assert.match(main, /Modifier.weight\(1f\).padding\(start = 16.dp, end = 12.dp\), decorationBox/);
assert.match(main, /Text\("商品名稱", Modifier.padding\(start = 2.dp\)/);
assert.match(ui, /確認刪除紀錄？/);
assert.match(ui, /確認清除歷史紀錄？/);
assert.match(ui, /dismissButton.*取消/);
assert.match(ui, /heightIn\(max = 420.dp\)/);
assert.match(store, /getSharedPreferences\("search_history_v1", Context.MODE_PRIVATE\)/);
assert.match(store, /if \(entries.isEmpty\(\)\) prefs.edit\(\).remove\("records"\).apply\(\)/);
assert.doesNotMatch(store, /Cookie|SubscriptionStore|startActivity|WebView/);
console.log('PASS: seven platforms including restored Facebook keep isolated histories, filters, and confirmed delete/clear.');
