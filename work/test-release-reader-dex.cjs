// Check the optimized installable artifact, not just unminified unit-test classes.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {yauzl} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core/lib/utilsBundle.js');
const root = path.resolve(__dirname, '..');
const mapping = fs.readFileSync(path.join(root, 'app/build/outputs/mapping/release/mapping.txt'), 'utf8');
const audit = process.argv.includes('--audit-old');
const targets = ['HiddenCaptureController', 'MarketWebActivity', 'FacebookListingDateReader'];
const bridges = {YahooDateBridge: ['deliver', 'preview'], YahooSearchBridge: ['deliver']};
const names = new Map();
for (const line of mapping.split('\n')) {
  const match = line.match(/^com\.example\.shopradar\.(\S+) -> (\S+):$/);
  if (match && [...targets, ...Object.keys(bridges)].some(name => match[1] === name || match[1].startsWith(name + '$')))
    names.set('L' + match[2].replaceAll('.', '/') + ';', match[1]);
}
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'shopradar-release-dex-'));
const annotatedBridges = new Set();
function verifyBridgeAnnotations(dex) {
  const u32 = offset => dex.readUInt32LE(offset);
  const leb = cursor => {
    let value = 0, shift = 0, byte;
    do { byte = dex[cursor.offset++]; value |= (byte & 127) << shift; shift += 7; } while (byte & 128);
    return value >>> 0;
  };
  const string = index => {
    const cursor = {offset: u32(u32(60) + index * 4)}; leb(cursor);
    return dex.toString('utf8', cursor.offset, dex.indexOf(0, cursor.offset));
  };
  const type = index => string(u32(u32(68) + index * 4));
  for (let index = 0; index < u32(96); index++) {
    const offset = u32(100) + index * 32;
    const original = names.get(type(u32(offset)));
    if (!bridges[original]) continue;
    const access = new Map();
    const cursor = {offset: u32(offset + 24)};
    assert.ok(cursor.offset, original + ': missing class data');
    const counts = Array.from({length: 4}, () => leb(cursor));
    for (let field = 0; field < counts[0] + counts[1]; field++) { leb(cursor); leb(cursor); }
    for (const count of counts.slice(2)) {
      let methodIndex = 0;
      for (let method = 0; method < count; method++) {
        methodIndex += leb(cursor); const flags = leb(cursor); leb(cursor);
        access.set(methodIndex, flags);
      }
    }
    const directory = u32(offset + 20);
    assert.ok(directory, original + ': annotation directory stripped');
    const found = new Set();
    for (let method = 0; method < u32(directory + 8); method++) {
      const entry = directory + 16 + u32(directory + 4) * 8 + method * 8;
      const methodIndex = u32(entry), set = u32(entry + 4);
      const methodName = string(u32(u32(92) + methodIndex * 8 + 4));
      for (let annotation = 0; annotation < u32(set); annotation++) {
        const item = u32(set + 4 + annotation * 4);
        const annotationCursor = {offset: item + 1};
        if (dex[item] === 1 && type(leb(annotationCursor)) === 'Landroid/webkit/JavascriptInterface;') {
          assert.ok(access.get(methodIndex) & 1, original + '.' + methodName + ': not public');
          found.add(methodName);
        }
      }
    }
    for (const method of bridges[original]) assert.ok(found.has(method), original + '.' + method + ': runtime annotation missing');
    annotatedBridges.add(original);
  }
}
(async () => {
  const dumps = [];
  await new Promise((resolve, reject) => yauzl.open(path.join(root, 'app/build/outputs/apk/release/app-release.apk'), {lazyEntries: true}, (error, zip) => {
    if (error) return reject(error);
    zip.on('error', reject); zip.on('end', resolve);
    zip.on('entry', entry => {
      if (!/^classes\d*\.dex$/.test(entry.fileName)) return zip.readEntry();
      zip.openReadStream(entry, (error, stream) => {
        if (error) return reject(error);
        const chunks = []; stream.on('error', reject); stream.on('data', chunk => chunks.push(chunk));
        stream.on('end', () => {
          try {
            const data = Buffer.concat(chunks); verifyBridgeAnnotations(data);
            const file = path.join(temp, entry.fileName); fs.writeFileSync(file, data);
            dumps.push(execFileSync('C:/Users/Elysia/AppData/Local/Android/build-tools/37.0.0/dexdump.exe', ['-a', '-d', file],
              {encoding: 'utf8', maxBuffer: 100 * 1024 * 1024, timeout: 60000}));
            zip.readEntry();
          } catch (error) { zip.close(); reject(error); }
        });
      });
    }); zip.readEntry();
  }));
  const seen = new Map(); let unsafe = 0;
  for (const block of dumps.join('\n').split(/(?=Class #\d+)/)) {
    const descriptor = block.match(/Class descriptor\s*:\s*'([^']+)'/)?.[1];
    const original = names.get(descriptor);
    if (!original) continue;
    seen.set(original, block);
    if (targets.some(name => original === name || original.startsWith(name + '$')) &&
        /Landroid\/(?:webkit\/WebView|view\/View);\.getHandler:/.test(block)) {
      unsafe++; console.log('UNSAFE: optimized ' + original + ' calls unattached View.getHandler()');
    }
  }
  for (const name of targets) assert.ok(seen.has(name), 'Reader missing from optimized DEX: ' + name);
  for (const [name, methods] of Object.entries(bridges)) {
    const block = seen.get(name); assert.ok(block, 'Bridge missing: ' + name);
    for (const method of methods) {
      assert.ok(block.includes("'" + method + "'"), 'Renamed bridge method: ' + name + '.' + method);
    }
    assert.ok(annotatedBridges.has(name), 'No annotated methods in optimized bridge: ' + name);
  }
  if (!audit) assert.equal(unsafe, 0, 'Release reader uses nullable View handler');
  console.log('PASS: optimized Release reader classes and Yahoo bridge entry points present; unsafe View handlers=' + unsafe);
})().finally(() => {
  // Only the explicitly created scratch directory and its own DEX files are removed.
  for (const name of fs.readdirSync(temp)) fs.unlinkSync(path.join(temp, name));
  fs.rmdirSync(temp);
}).catch(error => { console.error(error); process.exitCode = 1; });
