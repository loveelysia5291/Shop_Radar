/**
 * 購物雷達 (Shop Radar) - Cloudflare Worker 雲端推播與背景監控服務
 * 
 * 功能：
 * 1. 使用者帳號註冊、登入與 Token 驗證
 * 2. 裝置 FCM Token 綁定與同步
 * 3. 雲端同步監控：訂閱商品/店舖、競標商品、超商物流貨況
 * 4. Cron Trigger 排程監控：在 Cloudflare 邊緣節點 24 小時定時輪詢
 * 5. Firebase Cloud Messaging (FCM) 官方推播發送（支援 FCM v1 API 與 Legacy Server Key）
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Content-Type": "application/json; charset=utf-8"
};

// 記憶體快取（當未綁定 KV 時的相容降級備份）
const memoryStore = new Map();

export default {
  // 1. HTTP 請求入口
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      // 首頁/健康檢查
      if (path === "/" || path === "/api/health") {
        return jsonResponse({
          status: "online",
          service: "Shop Radar Push Server",
          version: "1.0.0",
          server_time: new Date().toISOString(),
          kv_connected: !!env.RADAR_KV
        });
      }

      // 註冊帳號
      if (path === "/api/auth/register" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { username, password } = body;
        if (!username || !password || username.length < 3) {
          return jsonResponse({ success: false, message: "請輸入有效的帳號（至少3字元）與密碼" }, 400);
        }

        const existing = await kvGet(env, `user:${username}`);
        if (existing) {
          return jsonResponse({ success: false, message: "此帳號已被註冊" }, 400);
        }

        const passwordHash = await sha256(password);
        const token = crypto.randomUUID();
        const userData = {
          username,
          passwordHash,
          token,
          createdAt: Date.now(),
          fcmToken: "",
          subscriptions: [],
          auctions: [],
          logistics: []
        };

        await kvPut(env, `user:${username}`, JSON.stringify(userData));
        await kvPut(env, `token:${token}`, username);
        await recordUserInList(env, username);

        return jsonResponse({ success: true, message: "註冊成功", token, username });
      }

      // 登入帳號
      if (path === "/api/auth/login" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { username, password } = body;
        if (!username || !password) {
          return jsonResponse({ success: false, message: "請輸入帳號與密碼" }, 400);
        }

        const rawUser = await kvGet(env, `user:${username}`);
        if (!rawUser) {
          return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);
        }

        const userData = JSON.parse(rawUser);
        const hash = await sha256(password);
        if (userData.passwordHash !== hash) {
          return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);
        }

        const token = crypto.randomUUID();
        userData.token = token;
        await kvPut(env, `user:${username}`, JSON.stringify(userData));
        await kvPut(env, `token:${token}`, username);

        return jsonResponse({ success: true, message: "登入成功", token, username });
      }

      // 身份驗證過濾器
      const user = await authenticate(request, env);
      if (!user) {
        return jsonResponse({ success: false, message: "身份驗證失敗，請重新登入" }, 401);
      }

      // 綁定/更新 FCM Token
      if (path === "/api/device/token" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { fcm_token } = body;
        if (!fcm_token) {
          return jsonResponse({ success: false, message: "fcm_token 不可為空" }, 400);
        }

        user.fcmToken = fcm_token;
        user.fcmUpdatedAt = Date.now();
        await kvPut(env, `user:${user.username}`, JSON.stringify(user));

        return jsonResponse({ success: true, message: "FCM Token 已更新綁定" });
      }

      // 同步監視項目（訂閱、競標、物流）
      if (path === "/api/sync" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { subscriptions, auctions, logistics, fcm_token } = body;

        if (Array.isArray(subscriptions)) user.subscriptions = subscriptions;
        if (Array.isArray(auctions)) user.auctions = auctions;
        if (Array.isArray(logistics)) user.logistics = logistics;
        if (fcm_token) user.fcmToken = fcm_token;

        user.lastSyncAt = Date.now();
        await kvPut(env, `user:${user.username}`, JSON.stringify(user));

        return jsonResponse({
          success: true,
          message: "雲端監控項目同步成功",
          synced_at: user.lastSyncAt,
          counts: {
            subscriptions: (user.subscriptions || []).length,
            auctions: (user.auctions || []).length,
            logistics: (user.logistics || []).length
          }
        });
      }

      // 取得使用者資訊
      if (path === "/api/user" && request.method === "GET") {
        return jsonResponse({
          success: true,
          username: user.username,
          has_fcm_token: !!user.fcmToken,
          last_sync_at: user.lastSyncAt || 0,
          counts: {
            subscriptions: (user.subscriptions || []).length,
            auctions: (user.auctions || []).length,
            logistics: (user.logistics || []).length
          }
        });
      }

      // 測試發送 FCM 推播
      if (path === "/api/test-push" && request.method === "POST") {
        const targetFcmToken = user.fcmToken;
        if (!targetFcmToken) {
          return jsonResponse({ success: false, message: "此帳號尚未綁定 FCM Device Token，請先在 App 取得 Token" }, 400);
        }

        const pushResult = await sendFcmNotification(env, targetFcmToken, {
          title: "🎉 購物雷達雲端推播測試成功！",
          body: `嗨 ${user.username}，您的手機已成功與 Cloudflare 邊緣伺服器連線，24小時自動監控已就緒！`
        }, {
          type: "test_push",
          timestamp: Date.now().toString()
        });

        return jsonResponse({
          success: pushResult.success,
          message: pushResult.success ? "推播發送成功！請檢查手機通知欄" : `推播發送失敗: ${pushResult.error}`,
          details: pushResult
        });
      }

      return jsonResponse({ success: false, message: "Not Found" }, 404);
    } catch (err) {
      return jsonResponse({ success: false, message: "伺服器內部錯誤", error: err.message }, 500);
    }
  },

  // 2. Cron Trigger 排程監控入口（每 10 分鐘自動執行）
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runScheduledMonitoring(env));
  }
};

/**
 * 核心背景排程監控任務
 */
async function runScheduledMonitoring(env) {
  const usersListStr = await kvGet(env, "system:all_users");
  if (!usersListStr) return;

  const usernames = JSON.parse(usersListStr);
  for (const username of usernames) {
    try {
      const userRaw = await kvGet(env, `user:${username}`);
      if (!userRaw) continue;
      const user = JSON.parse(userRaw);
      if (!user.fcmToken) continue;

      // 1. 監控訂閱清單（例如跳蚤本舖 / 各大平台）
      if (Array.isArray(user.subscriptions) && user.subscriptions.length > 0) {
        await checkSubscriptions(env, user);
      }

      // 2. 監控超商貨況
      if (Array.isArray(user.logistics) && user.logistics.length > 0) {
        await checkLogistics(env, user);
      }
    } catch (e) {
      console.error(`Error monitoring user ${username}:`, e);
    }
  }
}

/**
 * 監控訂閱項目（跳蚤本舖與商品更新）
 */
async function checkSubscriptions(env, user) {
  for (const sub of user.subscriptions) {
    const url = sub.url || "";
    if (!url) continue;

    // 跳蚤本舖特定檢測
    if (url.includes("lebay.com.tw")) {
      try {
        const resp = await fetch(url, {
          headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" }
        });
        if (resp.ok) {
          const html = await resp.text();
          // 比對關鍵商品特徵或商品數量
          const itemsMatch = html.match(/class=["']item-title["'][^>]*>([^<]+)</g);
          if (itemsMatch && itemsMatch.length > 0) {
            const latestTitle = itemsMatch[0].replace(/<[^>]+>/g, "").trim();
            const cacheKey = `cache:sub:${user.username}:${encodeURIComponent(url)}`;
            const lastSeen = await kvGet(env, cacheKey);

            if (lastSeen && lastSeen !== latestTitle) {
              await sendFcmNotification(env, user.fcmToken, {
                title: "🔔【跳蚤本舖】有新上架商品！",
                body: `您關注的賣場有新商品：「${latestTitle}」`
              }, {
                type: "subscription_update",
                url: url
              });
            }
            await kvPut(env, cacheKey, latestTitle);
          }
        }
      } catch (err) {
        console.error("Sub check error:", err);
      }
    }
  }
}

/**
 * 監控超商包裹貨況（免驗證碼的蝦皮店到店與狀態異動通知）
 */
async function checkLogistics(env, user) {
  for (const item of user.logistics) {
    if (item.isPickedUp) continue;
    const carrier = item.carrier;
    const no = item.trackingNumber;
    if (!no) continue;

    // 蝦皮店到店 (SPX) API 雲端直查免驗證碼
    if (carrier === "spx" || (!carrier && no.startsWith("SPX"))) {
      try {
        const resp = await fetch(`https://spx.tw/api/v2/fleet_order/tracking/search?sls_tracking_number=${encodeURIComponent(no)}`, {
          headers: { "User-Agent": "Mozilla/5.0" }
        });
        if (resp.ok) {
          const json = await resp.json();
          const records = json?.data?.records;
          if (records && records.length > 0) {
            const latestDesc = records[0].status_description || "";
            const cacheKey = `cache:logistics:${user.username}:${no}`;
            const lastStatus = await kvGet(env, cacheKey);

            if (latestDesc && latestDesc !== lastStatus) {
              const isArrived = latestDesc.includes("送達") || latestDesc.includes("配達") || latestDesc.includes("待取件");
              await sendFcmNotification(env, user.fcmToken, {
                title: isArrived ? "📦【包裹已到店！】請儘速取件" : "🚚【包裹貨況更新】",
                body: `${item.note ? `[${item.note}] ` : ""}${latestDesc}`
              }, {
                type: "logistics_update",
                tracking_no: no
              });
              await kvPut(env, cacheKey, latestDesc);
            }
          }
        }
      } catch (e) {
        console.error("SPX track error:", e);
      }
    }
  }
}

/**
 * FCM 推播發送模組
 * 支援：
 * 1. FCM HTTP v1 官方 API（推薦，填入 env.FCM_SERVICE_ACCOUNT_JSON）
 * 2. FCM Legacy HTTP API（填入 env.FCM_SERVER_KEY）
 */
async function sendFcmNotification(env, fcmToken, notification, data = {}) {
  // 優先方案 A: FCM Legacy API (設定最簡單，只需一組 Server Key)
  if (env.FCM_SERVER_KEY) {
    try {
      const payload = {
        to: fcmToken,
        priority: "high",
        notification: {
          title: notification.title,
          body: notification.body,
          sound: "default"
        },
        data: {
          ...data,
          title: notification.title,
          body: notification.body
        }
      };

      const resp = await fetch("https://fcm.googleapis.com/fcm/send", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `key=${env.FCM_SERVER_KEY}`
        },
        body: JSON.stringify(payload)
      });

      const resText = await resp.text();
      return { success: resp.ok, response: resText };
    } catch (e) {
      return { success: false, error: e.message };
    }
  }

  // 方案 B: FCM HTTP v1 API (使用 Google Service Account JSON)
  if (env.FCM_SERVICE_ACCOUNT_JSON) {
    try {
      const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON);
      const accessToken = await getGoogleOAuthToken(sa);
      const projectId = sa.project_id || env.FCM_PROJECT_ID;

      const message = {
        message: {
          token: fcmToken,
          notification: {
            title: notification.title,
            body: notification.body
          },
          data: data,
          android: {
            priority: "HIGH",
            notification: {
              sound: "default",
              channel_id: "fcm_general"
            }
          }
        }
      };

      const resp = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${accessToken}`
        },
        body: JSON.stringify(message)
      });

      const resJson = await resp.json();
      return { success: resp.ok, response: resJson };
    } catch (e) {
      return { success: false, error: e.message };
    }
  }

  return {
    success: false,
    error: "尚未設定 FCM 金鑰！請在 Cloudflare Worker 的 Variables 中設定 FCM_SERVER_KEY 或 FCM_SERVICE_ACCOUNT_JSON。"
  };
}

/**
 * 使用 Web Crypto API 生成 Google OAuth 2.0 Access Token（用於 FCM v1）
 */
async function getGoogleOAuthToken(serviceAccount) {
  const iat = Math.floor(Date.now() / 1000);
  const exp = iat + 3600;

  const header = { alg: "RS256", typ: "JWT" };
  const claimSet = {
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp,
    iat
  };

  const encHeader = base64UrlEncode(JSON.stringify(header));
  const encClaim = base64UrlEncode(JSON.stringify(claimSet));
  const unsignedJwt = `${encHeader}.${encClaim}`;

  // 匯入 RSA 私鑰
  const pem = serviceAccount.private_key
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s+/g, "");
  const binaryKey = Uint8Array.from(atob(pem), c => c.charCodeAt(0));

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    binaryKey.buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );

  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsignedJwt)
  );

  const encSignature = base64UrlEncodeBytes(new Uint8Array(signature));
  const signedJwt = `${unsignedJwt}.${encSignature}`;

  const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${signedJwt}`
  });

  const tokenJson = await tokenResp.json();
  if (!tokenJson.access_token) {
    throw new Error(`Google OAuth error: ${JSON.stringify(tokenJson)}`);
  }
  return tokenJson.access_token;
}

// 輔助函式
function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: CORS_HEADERS });
}

async function sha256(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
  return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, "0")).join("");
}

function base64UrlEncode(str) {
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlEncodeBytes(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function kvGet(env, key) {
  if (env.RADAR_KV) return await env.RADAR_KV.get(key);
  return memoryStore.get(key) || null;
}

async function kvPut(env, key, value) {
  if (env.RADAR_KV) return await env.RADAR_KV.put(key, value);
  memoryStore.set(key, value);
}

async function recordUserInList(env, username) {
  const raw = await kvGet(env, "system:all_users");
  let list = [];
  if (raw) {
    try { list = JSON.parse(raw); } catch (_) {}
  }
  if (!list.includes(username)) {
    list.push(username);
    await kvPut(env, "system:all_users", JSON.stringify(list));
  }
}

async function authenticate(request, env) {
  const authHeader = request.headers.get("Authorization") || "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) return null;

  const username = await kvGet(env, `token:${token}`);
  if (!username) return null;

  const rawUser = await kvGet(env, `user:${username}`);
  if (!rawUser) return null;

  try { return JSON.parse(rawUser); } catch (_) { return null; }
}
