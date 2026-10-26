const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync('app/src/main/assets/search-page-navigation.js','utf8');
const url = 'https://mall.iopenmall.tw/search.php?action=search&prod_keyword=phone';
function run({wanted=2, active=false, button=false, missing=false, total='', complete=false}={}) {
  let clicks=0;
  const target={textContent:'2',href:url+'&page=2',getAttribute:()=>null,click(){clicks++;}};
  const controls={textContent:total,querySelectorAll:q=>q.startsWith('[aria-current') ? active ? [target] : [] : [target]};
  const document={readyState:complete?'complete':'loading',querySelectorAll:q=>missing?[]:q==='a[href]'?(button?[]:[target]):[controls]};
  const result=JSON.parse(vm.runInNewContext(source,{URL,Set,document,location:new URL(url),window:{__shopRadarWantedPage:wanted}}));
  return {result,clicks};
}
assert.equal(run({wanted:1}).result.ready,true);
assert.equal(run().result.next,url+'&page=2');
assert.equal(run().result.ready,false,'Do not extract first-page cards before actual navigation');
assert.equal(run({active:true}).result.ready,true);
assert.equal(run({button:true}).clicks,1);
assert.equal(run({button:true}).result.ready,false,'A click alone is not confirmation that the new page is rendered');
assert.equal(run({missing:true}).result.ready,false);
assert.equal(run({missing:true}).result.next,undefined,'Never invent a page URL');
assert.equal(run({wanted:1,total:'共 5 頁'}).result.pages,5,'Discover total pages before scrolling');
assert.equal(run({wanted:5,total:'共 5 頁'}).result.pages,5);
assert.equal(run({wanted:1,missing:true}).result.pages,0,'Loading page with no controls is not evidence of one page');
assert.equal(run({wanted:1,missing:true,complete:true}).result.pages,1);
console.log('PASS: numbered-page URL/button/active-state routing uses observed controls and defers extraction until navigation.');
