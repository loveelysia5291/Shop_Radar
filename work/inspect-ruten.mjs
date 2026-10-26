import fs from 'node:fs';
const html=fs.readFileSync('work/page-1.html','utf8');
console.log(html.match(/<script[^>]*>[\s\S]*?<\/script>/g)?.filter(t=>!t.includes('src=')).map(x=>x.slice(0,2500)));
const script=await (await fetch('https://www.ruten.com.tw/assets/desktop-search.1666259d.js')).text();
fs.writeFileSync('work/ruten-search.js',script);
console.log(script.match(/.{0,90}(?:prod_name|item-name|prod_price|item\/show|search\/v3|api\.ruten|searchapi|rtapi|ProdName).{0,160}/g)?.slice(0,20));
