const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/java/com/aykkdk/shop/';
const main = fs.readFileSync(root+'MainActivity.kt','utf8');
const policy = fs.readFileSync(root+'RadarLayoutPolicy.kt','utf8');
const navigation = main.slice(main.indexOf('private fun RadarNavigation('), main.indexOf('private data class ShopReadState'));
assert.doesNotMatch(main+'\n'+policy, /sideNavigation|NavigationRail|RadarNavigation\(true|vertical: Boolean/);
assert.match(navigation,/Row\(Modifier\.padding\(6\.dp\)\.fillMaxWidth\(\)/);
assert.match(navigation,/Modifier\.size\(48\.dp\)\.graphicsLayer/,'Touch sizes and tab selection animation preserved');
assert.match(navigation,/animateFloatAsState\(if \(active\) 1\.08f else 1f, tween\(220\)/);
const bottom = main.slice(main.indexOf('}, bottomBar = {'),main.indexOf('val modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)'));
assert.equal((bottom.match(/RadarNavigation\(pager.targetPage\)/g)||[]).length,1);
assert.match(bottom,/Box\(Modifier\.fillMaxWidth\(\)\.imePadding\(\)\.navigationBarsPadding\(\)/);
assert.doesNotMatch(bottom,/if \(|width\(80\.dp\)|Alignment\.CenterStart/,'No width-dependent sidebar');
assert.match(bottom,/HorizontalPager\(pager, modifier = Modifier\.padding\(padding\)\.consumeWindowInsets\(padding\)\.fillMaxSize\(\)/,
  'Pager is measured above the real Scaffold bottom bar; insets are consumed only once');
assert.match(bottom,/pager.animateScrollToPage\(i, animationSpec = tween\(300\)\)/);
assert.match(bottom,/intentionalPageSwipe[\s\S]*?flingBehavior = pageFling, userScrollEnabled = false/);
const section=main.slice(main.indexOf('private fun SubscriptionSection('),main.indexOf('/** One consistent, blocking status surface'));
assert.match(section,/Column\(Modifier\.fillMaxWidth\(\), verticalArrangement = Arrangement\.spacedBy\(12\.dp\)\)/);
assert.match(section,/Text\(title, Modifier\.fillMaxWidth\(\), style = MaterialTheme\.typography\.titleMedium\)/);
assert.match(section,/else Row\(Modifier\.fillMaxWidth\(\), verticalAlignment = Alignment\.CenterVertically\)/);
assert.match(section,/Text\(title, Modifier\.weight\(1f\), style = MaterialTheme\.typography\.titleMedium\)/);
assert.match(section,/if \(emptyMessage != null\) GlassCard\(Modifier\.fillMaxWidth\(\)\)/);
assert.match(section,/Text\(emptyMessage, Modifier\.fillMaxWidth\(\)\.padding\(12\.dp\)\)/);
assert.doesNotMatch(section,/height\(|requiredHeight|offset\(|maxLines|clip\(/,'Headings and empty text must grow/wrap, not overlap or clip');
for(const [title,empty] of [['訂閱店家','shops'],['商品訂閱','productSubscriptions']]) {
  assert.match(main,new RegExp('item\\(span = \\{ GridItemSpan\\(maxLineSpan\\) \\}\\) \\{\\s+SubscriptionSection\\("'+title+'",[^\\n]+takeIf \\{ '+empty+'\\.isEmpty\\(\\) \\}\\)(?: \\{\\s+ProductLayoutToggle\\(layout\\)\\s+\\})?\\s+\\}'),
    'Both section titles/empty states must have exactly one layout root: '+title);
}
assert.match(main,/LazyColumn\(Modifier\.widthIn\(max = RadarLayoutPolicy\.FORM_MAX_WIDTH\.dp\)\.fillMaxSize\(\)/);
assert.match(main,/AdaptiveCardGrid\(modifier,[^\n]*fixedColumns = 2\)/);
assert.match(main,/GridCells.Fixed\(fixedColumns\?\.coerceIn\(1, 2\)/);
assert.equal((main.match(/AdaptiveCardGrid\(modifier, contentPadding = PaddingValues\(top = 8\.dp, bottom = 24\.dp\), fixedColumns = 2\)/g)||[]).length,2,
  'Both auctions and subscriptions stay two columns on tablets');
console.log('PASS: all window sizes use bottom navigation, Scaffold reserves bar/insets, native pager animation unchanged, both empty subscription sections have a wrapping Column with separate title/card space; forms and grids remain scrollable. Tablet device QA remains required.');
