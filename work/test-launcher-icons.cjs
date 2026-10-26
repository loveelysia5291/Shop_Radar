const fs = require('node:fs');
const assert = require('node:assert/strict');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = 'app/src/main/res/';
// Verify every direct Compose drawable reference across all version-qualified
// resources. A launcher icon can compile correctly but still crash painterResource.
const sourceRoot = 'app/src/main/java/com/aykkdk/shop/';
for (const sourceFile of fs.readdirSync(sourceRoot).filter(name => name.endsWith('.kt'))) {
  const source = fs.readFileSync(sourceRoot + sourceFile, 'utf8');
  for (const [, name] of source.matchAll(/painterResource\(R\.drawable\.(\w+)\)/g)) {
    const variants = fs.readdirSync(root).filter(dir => dir.startsWith('drawable'))
      .map(dir => root + dir + '/' + name + '.xml').filter(path => fs.existsSync(path));
    assert.ok(variants.length > 0, `${sourceFile}: drawable ${name} exists`);
    for (const variant of variants) {
      const xml = fs.readFileSync(variant, 'utf8').replace(/<\?xml[\s\S]*?\?>|<!--[\s\S]*?-->/g, '').trim();
      assert.match(xml, /^<vector\b/, `${sourceFile}: ${variant} must be a VectorDrawable, not an adaptive icon`);
    }
  }
}
for (const name of ['ic_launcher', 'ic_launcher_round']) {
  for (const api of [31, 33]) {
    const icon = fs.readFileSync(root + `drawable-v${api}/${name}.xml`, 'utf8');
    assert.match(icon, /<background android:drawable="@color\/shopradar_icon_monet_background"/);
    assert.match(icon, /<foreground android:drawable="@drawable\/ic_launcher_monet_foreground"/);
    assert.doesNotMatch(icon, /@color\/shopradar_icon_background|@drawable\/ic_launcher_foreground"/,
      `API ${api}: Settings/non-themed icon must not revert to the fixed purple palette`);
  }
  assert.match(fs.readFileSync(root + `drawable-v33/${name}.xml`, 'utf8'), /<monochrome android:drawable="@drawable\/ic_launcher_monochrome"/);
  assert.doesNotMatch(fs.readFileSync(root + `drawable-v26/${name}.xml`, 'utf8'), /system_accent|monochrome/);
}
assert.match(fs.readFileSync(root+'values-v31/icon_colors.xml','utf8'), /@android:color\/system_accent1_200/);
assert.match(fs.readFileSync(root+'values-v31/icon_colors.xml','utf8'), /@android:color\/system_accent1_800/);
assert.match(fs.readFileSync(root+'values-night-v31/icon_colors.xml','utf8'), /@android:color\/system_accent1_300/);
assert.match(fs.readFileSync(root+'values-night-v31/icon_colors.xml','utf8'), /@android:color\/system_accent1_900/);
const monochrome = fs.readFileSync(root+'drawable/ic_launcher_monochrome.xml','utf8');
assert.match(monochrome, /fillType="evenOdd"/);
assert.doesNotMatch(monochrome, /fillColor="#FFFFFF"/);
const foreground = fs.readFileSync(root+'drawable-v31/ic_launcher_monet_foreground.xml','utf8');
assert.match(foreground, /android:tint="@color\/shopradar_icon_monet_foreground"/);
const colored = fs.readFileSync(root+'drawable/ic_launcher_foreground.xml','utf8');
const brand = fs.readFileSync(root+'drawable/ic_brand_mark.xml','utf8');
const launch = fs.readFileSync(sourceRoot+'LaunchAnimation.kt','utf8');
assert.match(launch, /painterResource\(R\.drawable\.ic_brand_mark\)/);
assert.doesNotMatch(launch, /\bText\(|Modifier\.size\(96\.dp\)/, 'Splash has no visible app-name label or small legacy logo');
assert.match(launch, /minOf\(maxWidth, maxHeight\)/, 'Splash fits portrait and landscape windows');
assert.match(launch, /coerceAtMost\(shortSide \* 0\.8f\)/, 'Tiny split windows do not clip the logo');
assert.match(launch, /ANIMATOR_DURATION_SCALE/);
assert.match(launch, /scale.animateTo\(1f, tween\(360\)\)/, 'Keep the short entrance duration');
assert.doesNotMatch(launch, /delay\(/, 'Branding must not add a fixed startup hold');
for (const art of [monochrome, foreground, colored, brand]) {
  assert.match(art, /M54,27A27,27 0,1 1,54 81A27,27 0,1 1,54 27Z/, 'Reticle is a complete circle');
  assert.match(art, /M54,23V33M54,75V85M23,54H33M75,54H85/, 'Four symmetric cardinal ticks stay inside the circular safe zone');
  assert.match(art, /M42\.5,46H65\.5/, 'Shopping bag has a wide mouth');
  assert.match(art, /M47,46V45C47,41\.13 50\.13,38 54,38/, 'Shopping bag has a clear rounded handle');
  assert.doesNotMatch(art, /75\.7|78,28\.5|V73|V74/, 'No diagonal sweep or tall old bag');
}
assert.match(brand, /android:viewportWidth="66"/);
assert.match(brand, /android:translateX="-21" android:translateY="-21"/, 'Splash crops the transparent adaptive safe-zone padding');
const paths = xml => [...xml.matchAll(/<path\s+([\s\S]*?)\/>/g)].map(m => {
  const attrs = [...m[1].matchAll(/android:(\w+)="([^"]*)"/g)];
  const keys = {fillColor:'fill',strokeColor:'stroke',strokeWidth:'stroke-width',strokeLineCap:'stroke-linecap',fillType:'fill-rule',pathData:'d'};
  return '<path '+attrs.map(([,k,v])=>`${keys[k]}="${v==='#000000'?'currentColor':v==='#00000000'?'none':v}"`).join(' ')+'/>';
}).join('');
assert.equal(paths(monochrome),paths(foreground), 'Android 12 and 13 retain the same silhouette');
assert.equal(paths(brand),paths(colored), 'Splash and regular launcher share identical artwork');
if (process.argv.includes('--resources-only')) {
 console.log('PASS: all Compose drawable references are vector-only on every API; launcher Monet resources remain intact.');
 process.exit(0);
}
(async()=>{
 const browser=await chromium.launch({channel:'msedge',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:840,height:580}, deviceScaleFactor:1});
  await page.setContent(`<body style="margin:0;background:#f2f3f5;font:14px sans-serif;display:flex;gap:36px;padding:14px 24px">
    <div style="background:#10141e;width:300px;height:532px;border-radius:28px;display:grid;place-items:center;flex-shrink:0">
      <svg role="img" aria-label="Splash logo" width="138" height="138" viewBox="21 21 66 66">${paths(colored)}</svg>
    </div>
    <div style="flex:1;padding-top:12px"><h2>Vector artwork preview</h2><p>Centered launch / complete circular reticle / compact bag</p>
      <div style="display:flex;gap:24px;margin:28px 0"><div style="text-align:center"><div style="border-radius:28px;background:#F3F0FF;width:120px;height:120px"><svg viewBox="18 18 72 72">${paths(colored)}</svg></div><p>Adaptive mask preview</p></div>
      <div style="text-align:center"><div style="border-radius:50%;background:#F3F0FF;width:120px;height:120px"><svg viewBox="18 18 72 72">${paths(colored)}</svg></div><p>Round mask preview</p></div></div>
      <div style="display:flex;gap:18px">${[['12+ blue light','#b0c6ff','#10316c'],['13+ blue dark','#8dabf4','#002257'],['13+ green dark','#8fd6af','#003824']].map(([title,bg,ink])=>`<div style="text-align:center"><div style="border-radius:28px;background:${bg};color:${ink};width:108px;height:108px"><svg viewBox="18 18 72 72">${paths(monochrome)}</svg></div><p>${title}</p></div>`).join('')}</div>
      <div style="background:#F3F0FF;width:48px;height:48px;border-radius:12px"><svg viewBox="18 18 72 72">${paths(colored)}</svg></div><p>48px readability</p>
    </div></body>`);
  await page.screenshot({path:'work/launcher-icons-preview.png'});
  console.log('PASS: launcher API qualifiers, round icons, wallpaper palette aliases, alpha-only silhouette and visual preview.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
