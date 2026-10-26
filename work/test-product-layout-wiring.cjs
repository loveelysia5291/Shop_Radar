const fs = require('fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/example/shopradar/';
const main = fs.readFileSync(root + 'MainActivity.kt', 'utf8');
const ui = fs.readFileSync(root + 'ProductLayoutUi.kt', 'utf8');
const cover = fs.readFileSync(root + 'SubscriptionCardCover.kt', 'utf8');
const body = fs.readFileSync(root + 'SubscriptionCardBody.kt', 'utf8');
assert.match(ui, /getBoolean\("photo_grid", false\)/);
assert.match(ui, /putBoolean\("photo_grid", grid\)\.apply\(\)/);
assert.match(ui, /registerOnSharedPreferenceChangeListener/);
assert.match(ui, /unregisterOnSharedPreferenceChangeListener/);
assert.doesNotMatch(ui, /loadUrl|SearchCapture|SubscriptionRefreshQueue|RutenSearch|fetch\(/);
assert.match(ui, /切換為單欄商品佈局/);
assert.match(ui, /切換為雙欄圖片佈局/);
assert.match(ui, /Modifier\.size\(48\.dp\)/);
assert.match(ui, /ProductThumbnail\.url\(image\)/);
assert.equal((main.match(/rememberProductLayout\(\)/g) || []).length, 1, 'Only search reads layout preference');
assert.equal((main.match(/ProductLayoutToggle\(layout\)/g) || []).length, 1, 'Only search exposes layout toggle');
assert.doesNotMatch(main, /ProductLayoutToggle\(productLayout\)/);
const search = main.slice(main.indexOf('private fun SearchResultsPage'), main.indexOf('private data class CategoryOption'));
assert.match(search, /rememberProductLayout\(\)/);
assert.match(search, /ProductLayoutToggle\(layout\)/);
assert.match(search, /fixedColumns = if \(layout.photoGrid\) 2 else 1/);
assert.match(search, /ProductCard\([^\n]+photoGrid = layout.photoGrid, inlineActions = true\)/);
for (const [name, next] of [['AuctionScreen', 'private fun ProductCard'],
    ['ShopSubscriptions', 'private fun subscriptionPlatform'],
    ['SubscriptionNewItemsPage', 'private fun SubscriptionProductsPage'],
    ['SubscriptionProductsPage', 'private fun dateText']]) {
    const page = main.slice(main.indexOf('private fun ' + name), main.indexOf(next));
    assert.match(page, /fixedColumns = 2/, name + ' is always two columns including tablets');
    assert.doesNotMatch(page, /rememberProductLayout|ProductLayoutToggle|layout\.photoGrid/, name);
    assert.match(page, /AdultVisibility\.visible/, name);
    if (name.startsWith('Subscription')) assert.match(page, /photoGrid = true/);
}
assert.match(main, /AuctionCardCover\(target, reading,[\s\S]*?ProductCover\(entry.optString\("image"\), "競拍商品", cover\)/);
assert.match(fs.readFileSync(root + 'AuctionCardCover.kt', 'utf8'), /Modifier.fillMaxWidth\(\).aspectRatio\(1f\)/);
assert.doesNotMatch(main, /"競拍商品", Modifier.size\(100.dp\)/);
assert.match(cover, /if \(separateClose\) Modifier.fillMaxWidth\(\).aspectRatio\(1f\)/);
assert.match(cover, /else Modifier.fillMaxWidth\(\).height\(144.dp\)/, 'Very narrow fixed two-column cards retain safe stacked controls');
assert.match(cover, /image\(Modifier.fillMaxSize\(\)\)/);
assert.doesNotMatch(cover, /photoGrid|heading|heightIn\(min = 96.dp\)/);
assert.match(body, /SubscriptionCardHeading\(eyebrow, title, menuButton\)/);
assert.doesNotMatch(body, /minLines = 3, maxLines = 3/);
assert.match(body, /minLines = 2, maxLines = 2/);
assert.match(body, /repeat\(metadata.size\)/);
assert.match(body, /if \(status.isNotBlank\(\)\) Text/);
assert.doesNotMatch(body, /photoGrid|bodyMedium/);
assert.match(body, /onClickLabel = "查看完整刷新狀態"/);
assert.match(main, /SubscriptionThumbnail\(item, cover\)/);
assert.match(main, /ShopSubscriptionCover\.update\(record, ShopNewItems\.read\(record\)\)/);
assert.match(main, /val shopImage = ProductThumbnail\.url\(shop\.optString\("image"\)\)\.orEmpty\(\)/);
assert.doesNotMatch(main, /history\.firstOrNull \{ ProductThumbnail\.url\(it\.image\) != null \}/);
console.log('PASS: fixed two-column photo subscriptions and auctions including tablets; search alone switches one/two columns with persistent preference; store avatars persisted, narrow controls and full error access retained. Device layout QA remains required.');
