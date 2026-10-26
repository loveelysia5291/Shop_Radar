// Verify the generated local-install Release and copy it under its content hash.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const {yauzl} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core/lib/utilsBundle.js');
const root = path.resolve(__dirname,'..');
const apk = path.join(root,'app/build/outputs/apk/release/app-release.apk');
const mapping = path.join(root,'app/build/outputs/mapping/release/mapping.txt');
const sdk = 'C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0';
const certificate = '2555816082595d99a8f294ac2539300e0d7cad6f50040a37ec19d46c20fdb5cf';
const startup = process.argv.includes('--launch-timing');
const readerFix = process.argv.includes('--reader-fix');
const subscriptionCorners = process.argv.includes('--subscription-corners');
const rutenTime = process.argv.includes('--ruten-time');
const subscriptionList = process.argv.includes('--subscription-list');
const shopAvatar = process.argv.includes('--shop-avatar');
const shopeeLoading = process.argv.includes('--shopee-loading');
const auctionCards = process.argv.includes('--auction-cards');
const searchStreaming = process.argv.includes('--search-streaming');
const localJunit = process.argv.includes('--local-junit');
const resourceShrinking = !process.argv.includes('--without-resource-shrink');
const hash = data => crypto.createHash('sha256').update(data).digest('hex');
const run = (program,args) => execFileSync(program,args,{encoding:'utf8',timeout:60000,maxBuffer:8*1024*1024});

async function packagedEntries() {
  return new Promise((resolve,reject) => yauzl.open(apk,{lazyEntries:true},(error,zip) => {
    if (error) return reject(error);
    const entries = new Map();
    zip.on('error',reject);
    zip.on('end',()=>resolve(entries));
    zip.on('entry',entry=>{
      if (!entry.fileName.startsWith('assets/') && !/^classes\d*\.dex$/.test(entry.fileName)) return zip.readEntry();
      if (entry.uncompressedSize > 32*1024*1024) {zip.close();return reject(Error('Unexpected oversized APK entry'));}
      zip.openReadStream(entry,(error,stream)=>{
        if(error){zip.close();return reject(error);}
        const chunks=[];
        stream.on('error',reject);
        stream.on('data',chunk=>chunks.push(chunk));
        stream.on('end',()=>{entries.set(entry.fileName,Buffer.concat(chunks));zip.readEntry();});
      });
    });
    zip.readEntry();
  }));
}

(async()=>{
  const badging=run(path.join(sdk,'aapt2.exe'),['dump','badging',apk]);
  assert.match(badging,/name='com.aykkdk.shop'.*versionCode='28'.*versionName='1.27'/);
  assert.doesNotMatch(badging,/application-debuggable/);
  const manifest=run(path.join(sdk,'aapt2.exe'),['dump','xmltree',apk,'--file','AndroidManifest.xml']);
  assert.doesNotMatch(manifest,/android:debuggable.*(?:=true|0xffffffff)/);
  for(const value of ['android.permission.ACCESS_NETWORK_STATE','android.intent.action.SEND']) assert.ok(manifest.includes(value),value);
  assert.doesNotMatch(manifest,/android:screenOrientation/);
  assert.match(manifest,/android:resizeableActivity.*(?:=true|0xffffffff)/);
  assert.match(manifest,/android:configChanges.*0x0*d80/);
  const signature=run('C:/Program Files/Java/jdk-21.0.10/bin/java.exe',['-jar',path.join(sdk,'lib/apksigner.jar'),'verify','--verbose','--print-certs',apk]);
  assert.ok(signature.includes('certificate SHA-256 digest: '+certificate),'Existing certificate must be retained');
  run(path.join(sdk,'zipalign.exe'),['-c','4',apk]);
  const entries=await packagedEntries();
  for(const name of ['marketplace-html-debug.js','shopee-pre-navigation.js','shopee-public-reader.js','shopee-language.js'])
    assert.ok(!entries.has('assets/'+name),'Debug/removed asset leaked: '+name);
  for(const name of fs.readdirSync(path.join(root,'app/src/debug/assets')))
    assert.ok(!entries.has('assets/'+name),'Debug-only asset leaked: '+name);
  const sources=fs.readdirSync(path.join(root,'app/src/main/assets')).filter(name=>fs.statSync(path.join(root,'app/src/main/assets',name)).isFile());
  for(const name of sources) assert.equal(hash(entries.get('assets/'+name) || Buffer.alloc(0)),hash(fs.readFileSync(path.join(root,'app/src/main/assets',name))),name);
  const dex=[...entries].filter(([name])=>/^classes\d*\.dex$/.test(name)).map(([,data])=>data.toString('utf8')).join('');
  assert.doesNotMatch(dex,/early-4s|HTML 診斷編號|marketplace-html-debug\.js|JavaScript 錯誤分類|Debug 蝦皮搜尋|背景刷新狀態/);
  for(const value of ['Lcom/aykkdk/shop/AutomaticRefreshWorker;','radar-automatic-refresh','automatic_refresh_v1','sleepEnabled','setBlockNetworkImage'])
    assert.ok(dex.includes(value),'Missing current DEX implementation: '+value);
  const mappingText=fs.readFileSync(mapping,'utf8');
  if(rutenTime) assert.ok(mappingText.includes('RutenListingTime'), 'Missing Ruten listing time repair');
  if(subscriptionList) for(const name of ['SubscriptionCardHeading','CardIconButton'])
    assert.ok(mappingText.includes(name), 'Missing current subscription UI: '+name);
  if(shopAvatar) assert.ok(mappingText.includes('ShopSubscriptionCover'), 'Missing persistent store avatar fallback');
  if(shopeeLoading) for(const name of ['readerSession','retryEmptyPage'])
    assert.ok(mappingText.includes(name), 'Missing bounded Shopee/store loading fix: '+name);
  if(auctionCards) assert.ok(mappingText.includes('AuctionCardCover'), 'Missing photo-corner auction actions');
  if(searchStreaming) {
    for(const name of ['SearchPageLoading', 'ProductCardActions', 'SubscriptionNotificationOrder'])
      assert.ok(mappingText.includes(name), 'Missing current streaming/new-items implementation: '+name);
    assert.ok(dex.includes('刷新這一頁'), 'Missing completed-page refresh action');
  }
  if(readerFix) {
    run(process.execPath, [path.join(root, 'work/test-release-reader-dex.cjs')]);
    run(process.execPath, [path.join(root, 'work/test-capture-handler-bytecode.cjs')]);
    for(const value of ['homeLoginConfirmed','homeReturned','completedHome','captureHandler','pageHandler','listingHandler'])
      assert.ok(mappingText.includes(value), 'Missing new Release fix: '+value);
    const resources=run(path.join(sdk,'aapt2.exe'),['dump','resources',apk]);
    for(const name of ['ic_browser_back','ic_browser_refresh']) assert.ok(resources.includes(name),name);
  }
  assert.match(mappingText,/^com\.example\.shopradar\.AutomaticRefreshWorker -> com\.example\.shopradar\.AutomaticRefreshWorker:/m);
  assert.ok(mappingText.includes('MetadataNetworkPolicy'),'Missing new network policy in R8 mapping');
  assert.doesNotMatch(mappingText,/^com\.example\.shopradar\.(?:WebScriptDiagnostics|MarketplaceHtmlDebugSession|CaptureTrace) ->/m);
  if(startup) {
    const mainBlock=manifest.split(/E: activity /).find(block=>block.includes('com.aykkdk.shop.MainActivity'));
    assert.ok(mainBlock);assert.match(mainBlock,/android:theme/);
    const resources=run(path.join(sdk,'aapt2.exe'),['dump','resources',apk]);
    for(const name of ['Theme.ShopRadar.Launch','launch_background','launch_light_bars'])
      assert.ok(resources.includes(name),'Missing launch resource: '+name);
    assert.ok(mappingText.includes('RadarLaunchHost'),'Current startup host missing from R8 mapping');
    assert.ok(mappingText.includes('awaitLaunchDraw'),'First draw startup handoff missing from R8 mapping');
    assert.ok(dex.includes('完成蝦皮驗證'),'Shopee recovery menu missing from release');
  }
  const tests={};
  for(const variant of ['Debug','Release']){
    const directory=localJunit ? path.join(root,'work/search-streaming-unit-results',variant) : path.join(root,'app/build/test-results/test'+variant+'UnitTest');
    const totals={tests:0,failures:0,errors:0};
    for(const file of fs.readdirSync(directory).filter(file=>file.endsWith('.xml'))){
      const suite=fs.readFileSync(path.join(directory,file),'utf8').match(/<testsuite\b[^>]*>/)?.[0] || '';
      for(const key of Object.keys(totals)) totals[key]+=Number(suite.match(new RegExp(` ${key}=["'](\\d+)["']`))?.[1] || 0);
    }
    assert.ok(totals.tests>0);assert.equal(totals.failures+totals.errors,0,variant);
    if(shopAvatar) {
      const avatarTests=fs.readFileSync(path.join(directory,'TEST-com.aykkdk.shop.ShopSubscriptionCoverTest.xml'),'utf8');
      assert.match(avatarTests, /<testsuite\b[^>]* tests=["']6["']/);
    }
    if(searchStreaming) {
      const streaming=fs.readFileSync(path.join(directory,'TEST-com.aykkdk.shop.SearchPageLoadingTest.xml'),'utf8');
      assert.match(streaming, /<testsuite\b[^>]* tests=["']11["']/);
    }
    if(shopeeLoading) for(const [name,count] of [['SearchCaptureSessionTest',4],['ShopPreviewPresentationTest',2]]) {
      const result=fs.readFileSync(path.join(directory,'TEST-com.aykkdk.shop.'+name+'.xml'),'utf8');
      assert.match(result, new RegExp(`<testsuite\\b[^>]* tests=["']${count}["']`));
    }
    if(shopeeLoading) {
      // R8 can fully inline the tiny presentation predicate and omit its class mapping.
      // Check both compiled UI callers, rather than requiring an unshrunk helper class.
      const classes=path.join(root,'app/build/tmp/kotlin-classes',variant.toLowerCase());
      const packageDir=path.join(classes,'com/aykkdk/shop');
      const callers=fs.readdirSync(packageDir).filter(name=>/^MainActivityKt.*\.class$/.test(name) &&
        fs.readFileSync(path.join(packageDir,name)).includes(Buffer.from('ShopPreviewPresentation')));
      assert.ok(callers.length>0, variant+': missing visible-card presentation gate in UI');
      const bytecode=run('C:/Program Files/Java/jdk-21.0.10/bin/javap.exe',
        ['-classpath',classes,'-c','-p',...callers.map(name=>'com.aykkdk.shop.'+name.slice(0,-6))]);
      assert.match(bytecode,/ShopPreviewPresentation\.showResults/);
    }
    tests[variant]=totals;
  }
  const digest=hash(fs.readFileSync(apk));
  const date=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Taipei'}).format(new Date()).replaceAll('-','');
  const name='ShopRadar-1.27-'+(searchStreaming?'search-streaming':auctionCards?'auction-cards':shopeeLoading?'shopee-loading':shopAvatar?'shop-avatar':subscriptionList?'subscription-list':rutenTime?'ruten-time':subscriptionCorners?'subscription-corners':readerFix?'reader-login-fix':startup?'launch-timing':'data-saving')+'-release-'+date+'-'+digest.slice(0,8);
  fs.mkdirSync(path.join(root,'outputs'),{recursive:true});
  const destination=path.join(root,'outputs',name+'.apk');
  fs.copyFileSync(apk,destination);
  fs.copyFileSync(mapping,path.join(root,'outputs',name+'-mapping.txt'));
  const results={apk:destination,bytes:fs.statSync(destination).size,sha256:digest,certificate,codeShrinking:true,resourceShrinking,
    debugDiagnosticsIncluded:false,assetsVerified:sources.length,optimizedReadersVerified:readerFix,
    nativeDeviceVerified:false,testRunner:localJunit?'JUnitCore in-process (Gradle worker socket unavailable)':'Gradle test worker',tests,signature:signature.split('\n').slice(0,5)};
  fs.writeFileSync(path.join(root,'outputs',name+'-validation.json'),JSON.stringify(results,null,2)+'\n');
  console.log(JSON.stringify(results,null,2));
})().catch(error=>{console.error(error);process.exitCode=1;});
