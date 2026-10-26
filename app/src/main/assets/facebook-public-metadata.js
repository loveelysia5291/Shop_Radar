// Shared, listing-scoped public DOM helpers. No private APIs or account data.
(() => {
  if (window.__shopRadarFacebookMetadata) return;
  const hosts = new Set(['www.facebook.com', 'm.facebook.com', 'web.facebook.com']);
  const shown = node => {
    for (let current=node; current && current !== document; current=current.parentElement) {
      const style = getComputedStyle(current);
      if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false;
    }
    return true;
  };
  const itemUrl = raw => {
    if (!raw) return '';
    try {
      const url = new URL(raw, location.href);
      if (url.protocol !== 'https:' || url.username || url.password || url.port || !hosts.has(url.hostname)) return '';
      const id = url.pathname.match(/^\/marketplace\/item\/(\d{6,})\/?$/)?.[1];
      return id ? 'https://www.facebook.com/marketplace/item/' + id + '/' : '';
    } catch (_) { return ''; }
  };
  const emptyTime = () => ({listedAt:'', listedAtEpochMillis:0});
  const absoluteTime = value => {
    const text = String(value ?? '').trim();
    let millis = NaN;
    if (/^\d{10}(?:\d{3})?$/.test(text)) millis = Number(text) * (text.length === 10 ? 1000 : 1);
    else if (/^20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(text)) {
      const [y,month,day] = text.slice(0,10).split('-').map(Number);
      const [hour,minute,second] = text.slice(11,19).split(':').map(Number);
      if (month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(y,month,0)).getUTCDate() || hour > 23 || minute > 59 || second > 59) return emptyTime();
      millis = Date.parse(text);
    } else {
      const m = text.match(/^(20\d{2})[-/年](\d{1,2})[-/月](\d{1,2})日?(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
      if (m) {
        const y=Number(m[1]), month=Number(m[2]), day=Number(m[3]), hour=Number(m[4] || 0), minute=Number(m[5] || 0), second=Number(m[6] || 0);
        if (month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(y,month,0)).getUTCDate() || hour > 23 || minute > 59 || second > 59) return emptyTime();
        millis = Date.UTC(y,month-1,day,hour,minute,second) - 8*3600000;
        if (!m[4] && millis >= Date.UTC(2004,0,1) && millis <= Date.now()) return {listedAt:m[1] + '-' + String(month).padStart(2,'0') + '-' + String(day).padStart(2,'0'), listedAtEpochMillis:millis};
      }
    }
    if (!Number.isFinite(millis) || millis < Date.UTC(2004,0,1) || millis > Date.now()+60000) return emptyTime();
    return {listedAt:new Date(millis+8*3600000).toISOString().slice(0,19).replace('T',' '), listedAtEpochMillis:millis};
  };
  const visibleTime = (value, allowBareRelative = false) => {
    let text = String(value || '').replace(/\s+/g,' ').trim();
    if (/更新|降價|上線|在線|活躍|活動時間|updated|last active|last seen|online/i.test(text)) return emptyTime();
    const labelled = /^(?:上架|刊登|發布|发布|Listed|Posted|Published)(?:\s*(?:時間|时间|於|于|on))?\s*[:：]?\s*/i;
    const hasLabel = labelled.test(text);
    if (hasLabel) text = text.replace(labelled,'').split(/\s*[·•]\s*/)[0].trim();
    // Actual desktop detail header: "6天前於屏東市, 屏東縣上架".
    const chineseListing = text.match(/^(.+?前)(?:\s*(?:於|于|在).{1,120})?\s*(?:上架|刊登|發布|发布)$/);
    if (chineseListing) text = chineseListing[1].trim();
    else if (/^(?:listed|posted|published)\b/i.test(String(value || '').trim()))
      text = text.replace(/\s+in\s+.{1,120}$/i,'').trim();
    const absolute = absoluteTime(text);
    if (absolute.listedAt) return absolute;
    // Some Shopee listing fields omit 前. Only accept that form in an explicit
    // product-time field, never arbitrary card text, seller activity or titles.
    if ((allowBareRelative || hasLabel) && /^(?:約|约|大約|大约)?\s*[\d零〇一二三四五六七八九十兩两]+\s*(?:秒|分鐘|分钟|分|個小時|个小时|小時|小时|天|週|周|個月|个月|月|年)$/.test(text)) text += '前';
    if (/^(?:剛剛|刚刚|just now|不到\s*1\s*分鐘前|未滿\s*1\s*分鐘前)$/i.test(text)) return {listedAt:'剛剛',listedAtEpochMillis:Date.now()};
    if (/^(?:昨天|yesterday|今天|today)$/i.test(text)) return {listedAt:/昨天|yesterday/i.test(text)?'昨天':'今天',listedAtEpochMillis:0};
    const number = value => {
      if (/^\d+$/.test(value)) return Number(value);
      const digits = {零:0,〇:0,一:1,二:2,兩:2,两:2,三:3,四:4,五:5,六:6,七:7,八:8,九:9};
      if (value in digits) return digits[value];
      const parts = value.split('十');
      return parts.length === 2 && (parts[0] === '' || parts[0] in digits) && (parts[1] === '' || parts[1] in digits)
        ? (parts[0] ? digits[parts[0]] : 1)*10 + (parts[1] ? digits[parts[1]] : 0) : NaN;
    };
    let m = text.match(/^(約|约|大約|大约)?\s*([\d零〇一二三四五六七八九十兩两]+)\s*(秒|分鐘|分钟|分|個小時|个小时|小時|小时|天|週|周|個月|个月|月|年)前$/);
    const chinese = !!m;
    if (m) m = [m[0],m[2],m[3],m[1] || ''];
    if (!m) {
      const e = text.match(/^(about\s+)?(\d+)\s*(seconds?|minutes?|hours?|days?|weeks?|months?|years?)\s*ago$/i);
      if (e) m = [e[0],e[2],({second:'秒',minute:'分鐘',hour:'小時',day:'天',week:'週',month:'個月',year:'年'})[e[3].toLowerCase().replace(/s$/,'')],e[1]?'約':''];
    }
    const count = m ? number(m[1]) : NaN;
    if (!m || !Number.isFinite(count) || count > 100000) return emptyTime();
    const unit = ({分钟:'分鐘',分:'分鐘',小时:'小時',個小時:'小時',个小时:'小時',周:'週',个月:'個月',月:'個月'})[m[2]] || m[2];
    const scale = ({秒:1000,分鐘:60000,小時:3600000,天:86400000,週:604800000})[unit];
    return {listedAt:chinese ? text : m[3] + count + ' ' + unit + '前',listedAtEpochMillis:scale ? Date.now()-count*scale : 0};
  };
  const cardTime = (card, title = '') => {
    for (const node of card.querySelectorAll('time,abbr,[data-utime],span,div,p,[aria-label],[title]')) {
      const context = (node.parentElement?.innerText || '').trim();
      if (context.length < 160 && /上線|在線|活躍|last active|last seen|online|更新|updated/i.test(context)) continue;
      if (node.matches('time,abbr,[data-utime]')) {
        const date = absoluteTime(node.getAttribute('datetime') || node.getAttribute('data-utime'));
        if (date.listedAt && shown(node)) return date;
      }
      for (const value of [node.getAttribute('aria-label'),node.getAttribute('title'),node.innerText]) {
        if (!value || value.length > 140 || title && value.replace(/\s+/g, ' ').trim() === title.replace(/\s+/g, ' ').trim()) continue;
        const date = visibleTime(value);
        if (date.listedAt && shown(node)) return date;
      }
    }
    return emptyTime();
  };
  const recordTime = record => {
    for (const key of ['listing_creation_time','creation_time','created_time','datePosted','datePublished']) {
      const date = absoluteTime(record[key]);
      if (date.listedAt) return date;
    }
    return emptyTime();
  };
  window.__shopRadarFacebookMetadata = {itemUrl,emptyTime,absoluteTime,visibleTime,cardTime,recordTime,shown};
})()
