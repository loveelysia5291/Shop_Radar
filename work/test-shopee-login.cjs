const fs = require('node:fs');
const assert = require('node:assert/strict');
const read = name => fs.readFileSync(`app/src/main/java/com/example/shopradar/${name}.kt`, 'utf8');
const login = read('ShopeeLoginActivity');
const capture = read('MarketplaceCapture');
const config = read('ShopeeWebConfiguration');
const main = read('MainActivity');
const oldBrowser = read('MarketWebActivity');
const assets = read('CaptureSupport');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml', 'utf8');

// The native login view must be genuinely visible and measured before first navigation.
assert.match(login, /setContentView\(root\)/);
assert.match(login, /web\.doOnLayout/);
assert.ok(login.indexOf('setContentView(root)') < login.indexOf('web.doOnLayout'));
assert.match(login, /MATCH_PARENT, 0, 1f/);
assert.match(login, /isFocusableInTouchMode = true/);
assert.match(login, /WindowInsetsCompat\.Type\.ime\(\)/);
assert.match(login, /CookieManager\.getInstance\(\)\.flush\(\)/);
assert.match(login, /restoreState\(state\)/);
assert.doesNotMatch(login, /evaluateJavascript|addJavascriptInterface|CaptureAssets|CaptureDiagnostics|\.alpha\(|postDelayed|clearCache|removeAllCookies|\.proceed\(/);
assert.match(login, /handler\.cancel\(\)/);
assert.match(login, /officialPage\(request\.url\.toString\(\)\)\) return false/);
assert.match(manifest, /\.ShopeeLoginActivity[\s\S]*?android:exported="false"[\s\S]*?android:hardwareAccelerated="true"/);

// Sign-in and search share the native provider UA and cookie jar, with no UA spoof/reload loop.
assert.match(config, /WebSettings\.getDefaultUserAgent\(context\)/);
assert.match(config, /domStorageEnabled = true/);
assert.match(config, /javaScriptEnabled = true/);
assert.match(config, /setAcceptThirdPartyCookies\(web, true\)/);
assert.doesNotMatch(config, /userAgentString.*replace|X11|Version\/4|Linux x86_64|setDesktop|setWebContentsDebuggingEnabled/);
assert.match(capture, /ShopeeWebConfiguration\.configure\(this, context\)/);
assert.match(capture, /!ShopeeWebConfiguration\.canSearch\(\)/);
assert.match(main, /selected == Market\.SHOPEE\.label && !ShopeeWebConfiguration\.canSearch\(\)/);
assert.match(main, /Intent\(context, ShopeeLoginActivity::class\.java\)/);
assert.match(main, /if \(shopeeSignedIn\) "蝦皮登出" else "登入蝦皮"/);
assert.match(main, /LaunchedEffect\(foreground, menu\)/);
assert.match(main, /ShopeeWebConfiguration\.logout\(\)/);
assert.match(main, /ShopeeWebConfiguration\.recovery\?\.let/);
assert.match(main, /完成蝦皮驗證/);
assert.match(main, /\.putExtra\(ShopeeLoginActivity\.EXTRA_URL, recovery\.officialUrl\)/);
assert.match(login, /initialUrl = recovery\?\.officialUrl \?: ShopeeLoginPolicy\.LOGIN_URL/);
assert.match(login, /if \(!recoveryFlow && !homeReturned && !searchReturned && webAlive && web\.canGoBack\(\)\) web\.goBack\(\) else complete\(\)/);
assert.match(login, /toolbarIcon\(R.drawable.ic_browser_back, "返回搜尋"/);
assert.match(login, /toolbarIcon\(R.drawable.ic_browser_refresh, "刷新"/);
assert.match(login, /if \(webAlive\) web.reload\(\)/);
assert.match(capture, /requireRecovery\(url, request\.url\)/);
assert.match(capture, /accessRequired = true/);
assert.match(capture, /if \(!accessRequired\) diagnostics\.step/);
assert.match(capture, /diagnostics\.finishAccess\(report\)/);
const html = fs.readFileSync('app/src/debug/java/com/example/shopradar/MarketplaceHtmlDebugSession.kt', 'utf8');
const access = html.slice(html.indexOf('fun finishAccess('), html.indexOf('private fun publish('));
assert.doesNotMatch(access, /take\(|evaluateJavascript/);
assert.match(access, /publish\(report, callback\)/);
assert.doesNotMatch(config, /putString|putExtra|writeText/); // Recovery URLs stay memory-only.
assert.match(config, /jar\.setCookie\(origin, cookie\)/);
assert.match(config, /withContext\(Dispatchers\.IO\) \{ jar\.flush\(\) \}/);
assert.doesNotMatch(config, /removeAllCookies|removeSessionCookies|WebStorage|deleteAllData/);
assert.match(capture, /doOnLayout \{[\s\S]*?width > 0 && height > 0[\s\S]*?loadUrl\(request\.url\)/);
assert.match(oldBrowser, /market == Market\.SHOPEE && !searching/);
assert.match(oldBrowser, /ShopeeLoginActivity::class\.java/);

for (const source of [capture, assets, oldBrowser]) {
  assert.doesNotMatch(source, /ShopeeNavigationGuard|ShopeePublicPageSession|SearchNavigationPolicy|prepare-shopee|guard-shopee|checkLoginFields|switchLoginMode|history\.(?:replaceState|pushState)/);
}
for (const asset of ['guard-shopee-navigation.js', 'prepare-shopee-language.js', 'prepare-shopee-public-page.js']) {
  assert.equal(fs.existsSync(`app/src/main/assets/${asset}`), false);
}
assert.match(assets, /Market\.SHOPEE -> "extract-shopee-products\.js"/);
console.log('PASS: measured native sign-in/search; shared UA/cookies; validated memory-only official recovery entry even with cookies; access is not timeout; no scripts/HTML in sign-in or verification; scoped logout. Native device/server verification still required.');
