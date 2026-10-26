import fs from 'node:fs';
const u=new URL('https://rtapi.ruten.com.tw/api/search/v4/index.php/core/prod');
u.search=new URLSearchParams({q:'手機',sort:'new/dc',offset:'1',limit:'20','prc.now':'-3000',location:'tw',isnew:'0',cateid:'0021'});
const r=await fetch(u,{signal:AbortSignal.timeout(15000)});const data=await r.json();
fs.writeFileSync('work/ruten-results.json',JSON.stringify(data));
console.log(r.status, JSON.stringify(data).slice(0,1800));
const ids=(data.Rows||[]).slice(0,20).map(x=>x.Id);
if(ids.length) {
 const d=await fetch('https://rtapi.ruten.com.tw/api/prod/v3/index.php/prod?id='+ids.join(','),{signal:AbortSignal.timeout(15000)});
 const t=await d.text(); fs.writeFileSync('work/ruten-products.json',t);console.log(d.status,t.slice(0,2500));
}
