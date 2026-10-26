// Verify the actual Debug APK before publishing a uniquely named local copy.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const {yauzl} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core/lib/utilsBundle.js');
const root = path.resolve(__dirname, '..');
const apk = path.join(root, 'app/build/outputs/apk/debug/app-debug.apk');
const sdk = 'C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0';
const certificate = '2555816082595d99a8f294ac2539300e0d7cad6f50040a37ec19d46c20fdb5cf';
const tablet = process.argv.includes('--tablet-layout');
const recovery = process.argv.includes('--shopee-recovery');
const startup = process.argv.includes('--launch-timing');
const subscriptionCards = process.argv.includes('--subscription-cards');
const noFacebookSearch = process.argv.includes('--no-facebook-search');
const singleLaunch = process.argv.includes('--single-launch');
const yahooConsent = process.argv.includes('--yahoo-consent');
const shopeeAccess = process.argv.includes('--shopee-access');
const subscriptionPolish = process.argv.includes('--subscription-polish');
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const run = (program, args) => execFileSync(program, args, {encoding:'utf8', timeout:60000, maxBuffer:8*1024*1024});
function entries() {
  return new Promise((resolve,reject) => yauzl.open(apk,{lazyEntries:true},(error,zip) => {
    if (error) return reject(error);
    const files = new Map();
    zip.on('error',reject);
    zip.on('end',()=>resolve(files));
    zip.on('entry',entry=>{
      if (!entry.fileName.startsWith('assets/') && !/^classes\d*\.dex$/.test(entry.fileName)) return zip.readEntry();
      // Unshrunk Compose/material-icons DEX is much larger than a Release DEX.
      const maximum = entry.fileName.startsWith('assets/') ? 8*1024*1024 : 128*1024*1024;
      if (entry.uncompressedSize>maximum) {zip.close();return reject(Error('Oversized APK entry: '+entry.fileName));}
      zip.openReadStream(entry,(error,stream)=>{
        if(error){zip.close();return reject(error);}
        const chunks=[];
        stream.on('error',reject);
        stream.on('data',chunk=>chunks.push(chunk));
        stream.on('end',()=>{files.set(entry.fileName,Buffer.concat(chunks));zip.readEntry();});
      });
    });
    zip.readEntry();
  }));
}
(async()=>{
  const badging=run(path.join(sdk,'aapt2.exe'),['dump','badging',apk]);
  assert.match(badging,/name='com.aykkdk.shop'.*versionCode='28'.*versionName='1.27-debug-html'/);
  assert.match(badging,/application-debuggable/);
  const manifest=run(path.join(sdk,'aapt2.exe'),['dump','xmltree',apk,'--file','AndroidManifest.xml']);
  const placeBlock=manifest.split(/E: activity /).find(block=>block.includes('com.aykkdk.shop.FacebookLocationActivity'));
  if (noFacebookSearch) assert.equal(placeBlock, undefined, 'Retired place picker leaked into manifest');
  else {
    assert.ok(placeBlock,'Private official place picker missing from packaged manifest');
    assert.match(placeBlock,/android:exported[^\n]*0x0/);
  }
  assert.doesNotMatch(manifest,/ACCESS_(?:FINE|COARSE|BACKGROUND)_LOCATION/);
  const signature=run('C:/Program Files/Java/jdk-21.0.10/bin/java.exe',['-jar',path.join(sdk,'lib/apksigner.jar'),'verify','--verbose','--print-certs',apk]);
  assert.ok(signature.includes('certificate SHA-256 digest: '+certificate),'Existing install certificate must be retained');
  run(path.join(sdk,'zipalign.exe'),['-c','4',apk]);
  const files=await entries();
  const dex=[...files].filter(([name])=>/^classes\d*\.dex$/.test(name)).map(([,data])=>data.toString('utf8')).join('');
  for(const name of (noFacebookSearch ? ['searchSelection','supportsSearch','facebookLocationId','Lcom/aykkdk/shop/ShopNewItems;'] : ['FacebookLocationActivity','FacebookLocationStore','facebook_locations_v1','facebookPlaceId','facebookLocationId','Lcom/aykkdk/shop/ShopNewItems;']))
    assert.ok(dex.includes(name),'Current implementation missing from APK: '+name);
  if (noFacebookSearch) for (const name of ['FacebookLocationActivity','FacebookLocationStore','FacebookLocationDialog'])
    assert.ok(!dex.includes(name), 'Retired picker leaked into APK: '+name);
  if (tablet) {
    assert.ok(dex.includes('SubscriptionSection'),'Non-overlapping subscription section missing from APK');
    assert.ok(dex.includes('RadarNavigation'),'Bottom navigation missing from APK');
    assert.ok(!dex.includes('sideNavigation'),'Removed tablet sidebar policy leaked into APK');
  }
  if (recovery) {
    for(const name of ['ShopeeRecovery','PageSwipeEdgePolicy','WebScriptDiagnostics','finishAccess','EXTRA_SEARCH_URL'])
      assert.ok(dex.includes(name),'Shopee recovery/back fix missing from APK: '+name);
    const loginBlock=manifest.split(/E: activity /).find(block=>block.includes('com.aykkdk.shop.ShopeeLoginActivity'));
    assert.ok(loginBlock);assert.match(loginBlock,/android:exported[^\n]*0x0/);
  }
  if (startup) {
    for(const name of ['RadarLaunchHost','awaitLaunchDraw'])
      assert.ok(dex.includes(name), 'Startup implementation missing: '+name);
    const mainBlock=manifest.split(/E: activity /).find(block=>block.includes('com.aykkdk.shop.MainActivity'));
    assert.ok(mainBlock);assert.match(mainBlock,/android:theme/);
    const resources=run(path.join(sdk,'aapt2.exe'),['dump','resources',apk]);
    for(const name of ['Theme.ShopRadar.Launch','launch_background','launch_light_bars'])
      assert.ok(resources.includes(name),'Startup resource missing: '+name);
  }
  if (singleLaunch) {
    assert.ok(dex.includes('setOnExitAnimationListener'), 'Native exit gate missing');
    const resources=run(path.join(sdk,'aapt2.exe'),['dump','resources',apk]);
    assert.ok(resources.includes('launch_empty_icon'), 'Background-only native splash missing');
    const empty=run(path.join(sdk,'aapt2.exe'),['dump','xmltree',apk,'--file','res/drawable/launch_empty_icon.xml']);
    assert.match(empty,/fillColor[^\n]*0x0/, 'Native icon must be transparent');
  }
  if (yahooConsent) {
    for(const name of ['YahooConsentPolicy','YahooConsentRetry','awaitYahooRedirect','yahooRedirectStalled','canRetryFrom','CONSENT_REQUIRED'])
      assert.ok(dex.includes(name), 'Yahoo consent recovery missing: '+name);
  }
  if (shopeeAccess) {
    for (const name of ['stopForShopeeAccess','shouldAutoRecover','canResumeSearch','officialRecoveryAttempt','guestEmptyAttempted','searchReturned','cachedUserAgent','ensureSearchHistoryLoaded','restartKeywordCapture'])
      assert.ok(dex.includes(name), 'Current Shopee recovery/optimization missing: '+name);
    assert.ok(files.has('assets/shopee-guest-search-debug.js'), 'Debug experiment missing');
  }
  if (subscriptionCards) {
    for(const name of ['SubscriptionCardCover','ShopSubscriptionCover','subscriptionPhotoColumns'])
      assert.ok(dex.includes(name), 'Subscription image actions missing: '+name);
    const extractor = files.get('assets/extract-products.js')?.toString('utf8') || '';
    for(const marker of ['const yahooStoreCards', 'cardRoot?.querySelectorAll', 'if (!collection.items.size)', 'if (empty && !window.__shopRadarYahooReading)'])
      assert.ok(extractor.includes(marker), 'Yahoo store regression fix missing: '+marker);
  }
  if (subscriptionPolish) {
    for(const name of ['SubscriptionCardBody','RefreshTimeDisplay','rememberRefreshClock','YahooRedirectWait','allowsProductPage','captureHandler','pageHandler','listingHandler'])
      assert.ok(dex.includes(name), 'Subscription polish/redirect handling missing: '+name);
    assert.ok(!dex.includes('stopForYahooConsent'), 'Immediate Yahoo consent abort still packaged');
    assert.ok(!dex.includes('subscriptionCoverInset'), 'Obsolete image corner inset still packaged');
    run(process.execPath, [path.join(root, 'work/test-capture-handler-bytecode.cjs')]);
  }
  assert.ok(files.has('assets/marketplace-html-debug.js'),'Expected Debug diagnostics missing');
  for(const directory of ['app/src/main/assets','app/src/debug/assets']) {
    for(const name of fs.readdirSync(path.join(root,directory)).filter(name=>fs.statSync(path.join(root,directory,name)).isFile())) {
      assert.equal(hash(files.get('assets/'+name)||Buffer.alloc(0)),hash(fs.readFileSync(path.join(root,directory,name))),name);
    }
  }
  const tests={};
  for(const variant of (tablet ? ['Debug'] : ['Debug','Release'])) {
    const directory=path.join(root,'app/build/test-results/test'+variant+'UnitTest');
    const totals={tests:0,failures:0,errors:0};
    for(const file of fs.readdirSync(directory).filter(file=>file.endsWith('.xml'))) {
      const suite=fs.readFileSync(path.join(directory,file),'utf8').match(/<testsuite\b[^>]*>/)?.[0]||'';
      for(const key of Object.keys(totals)) totals[key]+=Number(suite.match(new RegExp(' '+key+'="(\\d+)"'))?.[1]||0);
    }
    assert.ok(totals.tests>0);assert.equal(totals.failures+totals.errors,0,variant);tests[variant]=totals;
  }
  const digest=hash(fs.readFileSync(apk));
  const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Taipei'}).format(new Date()).replaceAll('-','');
  const destination=path.join(root,'outputs','ShopRadar-1.27-'+(subscriptionPolish?'subscription-polish-yahoo':shopeeAccess?'shopee-access-recovery':yahooConsent?'yahoo-consent-transparent-actions':singleLaunch?'single-launch':noFacebookSearch?'no-facebook-search':subscriptionCards?'subscription-cards-yahoo':startup?'launch-timing':recovery?'shopee-recovery':tablet?'tablet-layout':'facebook-location')+'-debug-'+date+'-'+digest.slice(0,8)+'.apk');
  fs.mkdirSync(path.dirname(destination), {recursive: true});
  fs.copyFileSync(apk,destination);
  console.log(JSON.stringify({apk:destination,bytes:fs.statSync(destination).size,sha256:digest,certificate,tests,signature:signature.split('\n').slice(0,5)},null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
