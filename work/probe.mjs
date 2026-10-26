import fs from 'node:fs';
const urls = [
 'https://tw.bid.yahoo.com/item/101764768005',
 'https://www.ruten.com.tw/search/%E6%89%8B%E6%A9%9F/?location=tw&isnew=0&cateid=0021&sort=new%2Fdc&prc.now=-3000',
 'https://tw.carousell.com/search/%E9%9B%B6%E4%BB%B6%E6%A9%9F?price_end=3000&sort_by=3'
];
for (const [i,url] of urls.entries()) {
 try {
  const r = await fetch(url,{signal:AbortSignal.timeout(20000)});
  const html = await r.text();
  fs.writeFileSync(`work/page-${i}.html`,html);
  console.log(i, r.status,r.url,html.length);
  console.log(html.match(/<script[^>]*>/g)?.slice(-12));
  if(i === 0) {
   const p=html.indexOf('"bidInfo"'); console.log(html.slice(p-100,p+1800));
   console.log(html.match(/.{0,70}(?:highestBidder|bidHistory|bidder|winningBid|bidRecord).{0,180}/gi)?.slice(0,20));
  }
 } catch(e) {console.log(String(e));}
}
