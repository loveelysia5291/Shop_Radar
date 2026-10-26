// Explicit product labels only; listing IDs do not encode the publication date.
(() => {
  window.__shopRadarBoboMetadata = (doc, rawUrl) => {
    const u = new URL(rawUrl, location.href);
    if (!['www.bbbobo.com.tw', 'bbbobo.com.tw'].includes(u.hostname) ||
        !/^\/shop\/(?:\d+|0_1)\/Goods\.asp$/i.test(u.pathname)) return null;
    const gid = u.searchParams.get('GID')?.toUpperCase();
    if (!/^GTT\d{8,}$/.test(gid || '')) return null;
    const text = (doc.body?.innerText || doc.body?.textContent || '')
      .split(/您可能喜歡的商品|購物流程及注意事項/)[0];
    const block = text.match(/商品所在\s*[:：]\s*([\s\S]*?)上架時間\s*[:：]\s*(\d{4}\/\d{1,2}\/\d{1,2})/);
    if (!block) return null;
    const section = block[0];
    if (section.match(/商品編號\s*[:：]\s*(GTT\d{8,})/i)?.[1].toUpperCase() !== gid) return null;
    const shop = section.match(/商品所在\s*[:：]\s*([\s\S]*?)商品分類\s*[:：]/)?.[1].trim() || '';
    const category = section.match(/商品分類\s*[:：]\s*([\s\S]*?)商品編號\s*[:：]/)?.[1].trim() || '';
    const date = block[2], [year, month, day] = date.split('/').map(Number);
    const check = new Date(Date.UTC(year, month - 1, day));
    if (check.getUTCFullYear() !== year || check.getUTCMonth() !== month - 1 || check.getUTCDate() !== day) return null;
    return {shop: shop.slice(0, 120), category: category.slice(0, 120), listedAt: date};
  };
})();
