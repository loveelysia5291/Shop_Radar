// Minimal rendered-DOM model for the exact booth/article regression; no browser dependency.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/extract-products.js', 'utf8');
function fixture({scan = false, checkpoint = 0, seen = [], infinite = false} = {}) {
  const deliveries = [], previews = [], fetched = [];
  const scope = {tagName:'ARTICLE', parentElement:null, innerText:'全部商品', rows:[],
    querySelectorAll: selector => selector === 'a[href]' ? scope.rows.map(x=>x.link) : [],
    querySelector: () => null, getClientRects:()=>[{}], scrollIntoView() {}};
  const context = {URL, Map, Set, console, AbortController, setTimeout, clearTimeout,
    location:new URL('https://tw.bid.yahoo.com/booth/Y2369592298?sort=-ptime&pg=1'),
    document:{querySelector: selector => selector.includes('boothListings__') ? scope : null,
      querySelectorAll:()=>{throw Error('Must not scan promotional/homepage links');}},
    innerHeight:600, scrollBy(){},
    ShopRadarYahoo:{deliver:json=>deliveries.push(JSON.parse(json)), preview:json=>previews.push(JSON.parse(json))},
    __shopRadarScanSubscription:scan, __shopRadarSubscriptionCheckpoint:checkpoint,
    __shopRadarSubscriptionSeen:seen,
    __shopRadarInfiniteSearch:infinite,
    fetch:async url=>{fetched.push(url);const id=new URL(url).pathname.split('/')[2];
      return {ok:true,text:async()=>`<script id="isoredux-data">${JSON.stringify({item:{id,title:'手機 '+id,
        startTime:1791000000,price:id.endsWith('1')?1000:2000,type:1,images:[]}})}</script>`};}};
  context.window=context;
  const session=vm.createContext(context);
  function add(id, price, title = '手機 '+id) {
    const image={src:'https://img.yec.tw/'+id+'.jpg',currentSrc:'',alt:title,className:'',getAttribute:()=>null};
    const label={textContent:title};
    const priceNode={textContent:'$'+price,getAttribute:()=>null};
    const card={tagName:'DIV',parentElement:scope,innerText:title+'\n$'+price,
      querySelector:selector=>selector==='img'?image:selector.includes('price')?priceNode:selector.includes('title')?label:null,
      querySelectorAll:selector=>selector==='a[href]'?[link]:selector==='img'?[image]:[],getClientRects:()=>[{}]};
    const link={href:'https://tw.bid.yahoo.com/item/'+id,tagName:'A',parentElement:card,
      innerText:title,textContent:title,closest:()=>scope,getAttribute:()=>null,
      querySelector:selector=>selector==='img'?image:null,
      querySelectorAll:selector=>selector==='img'?[image]:selector.includes('title')?[label]:[],getClientRects:()=>[{}]};
    scope.rows.push({link,card});
    scope.innerText += '\n' + card.innerText;
    scope.querySelector = selector => scope.rows[0]?.card.querySelector(selector) || null;
  }
  return {deliveries,previews,fetched,context,scope,add,poll:()=>vm.runInContext(source,session)};
}
(async()=>{
  for (const count of [1, 15, 50]) {
    const f = fixture({infinite: true});
    for(let n=0;n<count;n++) f.add(String(101234567800+n), 1000);
    const cards = JSON.parse(f.poll());
    assert.equal(cards.length, count);
    await new Promise(setImmediate);
    assert.equal(f.fetched.length, 0, 'Ordinary search cards never wait for or request full detail pages for optional dates');
  }
  const repair = fixture({infinite: true});
  repair.add('101234567891',1000,'Y2369592298');
  for(let n=0;n<10;n++) repair.poll();
  assert.equal(repair.fetched.length,1,'One ambiguous-name repair slot, never duplicate jobs during repeated polling');
  await new Promise(setImmediate);
  assert.equal(JSON.parse(repair.poll())[0].title,'手機 101234567891');
  assert.equal(repair.fetched.length,1,'Public repair metadata is cached');
  for(const scan of [false,true]) {
    const f=fixture({scan});
    for(let n=0;n<25;n++) f.poll();
    assert.equal(f.deliveries.length,0,'Loading/empty DOM must not seal the bridge');
    assert.equal(f.context.__shopRadarYahooReading,undefined);
    f.add('101234567891',1000); f.add('101234567892',2000);
    for(let n=0;n<10;n++) f.poll();
    await new Promise(setImmediate);
    assert.equal(f.deliveries.length,1);
    assert.equal(f.deliveries[0].length,2);
    assert.deepEqual(f.deliveries[0].map(p=>p.price),[1000,2000]);
    assert.ok(f.deliveries[0].every(p=>p.shop==='Y2369592298' && p.title.startsWith('手機')));
    assert.ok(f.deliveries[0][0].image.endsWith('101234567891.jpg'));
    assert.ok(f.deliveries[0][1].image.endsWith('101234567892.jpg'));
  }
  const old=fixture({scan:true, checkpoint:1791000001000});
  old.add('101234567891',1000);old.add('101234567892',2000);
  old.poll();old.poll();await new Promise(setImmediate);
  assert.equal(old.fetched.length,1,'An old first listing stops after one detail read');
  assert.equal(old.deliveries[0].length,1);
  const empty=fixture({scan:true});
  empty.scope.innerText='店家還沒有上架商品喔！';
  empty.poll();empty.poll();
  assert.deepEqual(empty.deliveries,[[]],'Only an explicit site empty-state completes empty');
  console.log('PASS: delayed Yahoo store list recovers, outer article cannot merge prices/images, promotion scope excluded, first old listing costs one read, explicit empty state delivered once.');
})().catch(e=>{console.error(e);process.exitCode=1;});
