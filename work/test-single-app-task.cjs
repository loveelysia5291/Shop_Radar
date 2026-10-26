const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = 'app/src/main/';
const manifestPaths = [root + 'AndroidManifest.xml',
  'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml',
  'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'];
for (const path of manifestPaths) {
  const xml = fs.readFileSync(path, 'utf8');
  const activities = [...xml.matchAll(/<activity\b([^>]*)(?:\/>|>([\s\S]*?)<\/activity>)/g)];
  const main = activities.filter(match => /android:name="(?:\.MainActivity|com\.example\.shopradar\.MainActivity)"/.test(match[1]));
  assert.equal(main.length, 1, path + ': exactly one main entry');
  assert.match(main[0][1], /android:launchMode="singleTask"/);
  assert.match(main[0][1], /android:documentLaunchMode="never"/);
  assert.match(main[0][2], /android\.intent\.action\.MAIN/);
  assert.match(main[0][2], /android\.intent\.action\.SEND/);
  assert.doesNotMatch(xml, /android:(?:taskAffinity|process)=|android:launchMode="(?:singleInstance|singleInstancePerTask)"/);
}
for (const file of ['AuctionTracker.kt', 'RefreshRunner.kt']) {
  const source = fs.readFileSync(root + 'java/com/example/shopradar/' + file, 'utf8');
  assert.match(source, /Intent\(context, MainActivity::class\.java\)[\s\S]{0,160}\.addFlags\(Intent\.FLAG_ACTIVITY_NEW_TASK or Intent\.FLAG_ACTIVITY_CLEAR_TOP or Intent\.FLAG_ACTIVITY_SINGLE_TOP\)/);
  assert.doesNotMatch(source, /FLAG_ACTIVITY_(?:MULTIPLE_TASK|NEW_DOCUMENT|CLEAR_TASK)/);
}
const main = fs.readFileSync(root + 'java/com/example/shopradar/MainActivity.kt', 'utf8');
assert.match(main, /override fun onNewIntent\(intent: Intent\)[\s\S]{0,100}setIntent\(intent\)/);
assert.match(main, /override fun onNewIntent\(intent: Intent\)[\s\S]{0,250}receiveShare\(intent\)/);
assert.match(main, /override fun onNewIntent\(intent: Intent\)[\s\S]{0,200}openAuctions\+\+/);
assert.doesNotMatch(main, /finishAndRemoveTask|killProcess|exitProcess/);
console.log('PASS: source and both merged manifests reuse one main task; notification flags agree; existing share/auction intents are handled without killing refresh work. Actual Android Recents behavior still requires device QA.');
