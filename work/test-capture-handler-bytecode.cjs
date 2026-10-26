// Kotlin receiver resolution must be checked in the compiled artifact: View.handler is nullable before attachment.
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
for (const variant of ['debug', 'release']) {
  const directory = path.join(root, 'app/build/tmp/kotlin-classes', variant);
  const packageDirectory = path.join(directory, 'com/example/shopradar');
  const classes = fs.readdirSync(packageDirectory)
    .filter(name => /^(HiddenCaptureController|MarketWebActivity|FacebookListingDateReader)(?:\$.*)?\.class$/.test(name))
    .map(name => 'com.example.shopradar.' + name.slice(0, -6));
  if (classes.length < 3) throw Error(variant + ': reader classes missing');
  const bytecode = execFileSync('C:/Program Files/Java/jdk-21.0.10/bin/javap.exe',
    ['-classpath', directory, '-c', '-p', ...classes], {encoding: 'utf8', timeout: 60_000, maxBuffer: 16 * 1024 * 1024});
  if (/android\/(?:webkit\/WebView|view\/View)\.getHandler/.test(bytecode))
    throw Error(variant + ': compiled reader still uses the unattached View handler');
  if (!/Field captureHandler:Landroid\/os\/Handler;/.test(bytecode) || !/Field pageHandler:Landroid\/os\/Handler;/.test(bytecode) || !/Field listingHandler:Landroid\/os\/Handler;/.test(bytecode))
    throw Error(variant + ': owned main-thread handlers missing');
  if (!/android\/os\/Handler\.post:/.test(bytecode) || !/android\/os\/Handler\.postDelayed:/.test(bytecode))
    throw Error(variant + ': scheduling paths were removed rather than fixed');
  console.log('PASS: ' + variant + ' compiled reader/controller and callbacks use owned handlers; no View.getHandler() calls.');
}
