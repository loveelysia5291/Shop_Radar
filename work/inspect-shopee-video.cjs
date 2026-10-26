// Local video inspection only: no archived HTML or external page is executed.
const fs = require('node:fs');
const {chromium} = require('C:/Users/Elysia/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const videoPath = 'C:/Users/Elysia/Documents/AnLink/Screenshots/2026-10-3 20-51-53.mp4';
(async () => {
  const browser = await chromium.launch({channel: 'msedge', headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 960, height: 1600}});
    await page.route('**/*', route => route.abort());
    const videoUrl = 'data:video/mp4;base64,' + fs.readFileSync(videoPath).toString('base64');
    await page.setContent('<body style="margin:0;background:white"><video muted preload="auto" style="display:none" src="' + videoUrl + '"></video><canvas></canvas></body>');
    await page.waitForFunction(() => document.querySelector('video').readyState >= 2, {timeout: 15000});
    const meta = await page.evaluate(() => { const v = document.querySelector('video'); return {duration: v.duration, width: v.videoWidth, height: v.videoHeight}; });
    console.log(JSON.stringify(meta));
    const seconds = [0, 2, 4, 7, 10, 14, 18, 22, 26].filter(t => t < meta.duration - 0.05);
    if (seconds.at(-1) < meta.duration - 2) seconds.push(meta.duration - 0.2);
    await page.evaluate(({seconds}) => {
      const v = document.querySelector('video'), c = document.querySelector('canvas');
      const w = 300, h = Math.round(w * v.videoHeight / v.videoWidth);
      c.width = w * 3; c.height = (h + 32) * Math.ceil(seconds.length / 3);
      c.style.width = c.width + 'px'; c.style.height = c.height + 'px';
      const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
    }, {seconds});
    for (let i = 0; i < seconds.length; i++) {
      await page.evaluate(async ({time, index}) => {
        const v = document.querySelector('video'), c = document.querySelector('canvas');
        if (Math.abs(v.currentTime - time) > 0.01) {
          await new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('Video seek timed out')), 6000);
            v.addEventListener('seeked', () => { clearTimeout(timeout); resolve(); }, {once: true});
            v.currentTime = time;
          });
        }
        const w = 300, h = Math.round(w * v.videoHeight / v.videoWidth);
        const x = (index % 3) * w, y = Math.floor(index / 3) * (h + 32), ctx = c.getContext('2d');
        ctx.fillStyle = '#111'; ctx.font = '18px sans-serif'; ctx.fillText(time.toFixed(1) + ' s', x + 10, y + 23);
        ctx.drawImage(v, x, y + 32, w, h);
      }, {time: seconds[i], index: i});
    }
    await page.locator('canvas').screenshot({path: 'work/shopee-video-contact-sheet.png'});
    console.log('Contact sheet: work/shopee-video-contact-sheet.png; frames=' + seconds.length);
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
