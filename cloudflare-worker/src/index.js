/**
 * 購物雷達 (Shop Radar) - Cloudflare Worker 雲端推播與背景監控服務 (高安全性與權限分級版)
 * 
 * 核心特色：
 * 1. 安裝即用：支援 Android 裝置自動匿名註冊，免帳密開箱即收到 FCM 推播。
 * 2. 帳密高防護：PBKDF2 10 萬次加鹽雜湊 (Salt + PBKDF2-SHA256) + 登入防爆破鎖定，絕不回傳與明文儲存密碼。
 * 3. 智慧權限分級：
 *    - 標準/管理員帳號：每 30 分鐘背景巡查一次。
 *    - 低權限帳號：每 1 小時 (60 分鐘) 背景巡查一次。
 * 4. 內建 Web 後台：瀏覽網址 /admin 即可進入管理後台，視覺化管理裝置、切換權限與發送全域推播。
 * 5. 全面支援 FCM v1 與 Legacy Server Key 推播。
 */

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "SAMEORIGIN",
  "Referrer-Policy": "strict-origin-when-cross-origin"
};

const memoryStore = new Map();

export default {
  async fetch(request, env, ctx) {
    if (request.method === "OPTIONS") {
      return new Response(null, { headers: CORS_HEADERS });
    }

    const url = new URL(request.url);
    const path = url.pathname;

    try {
      // 1. 首頁與健康檢查
      if (path === "/" || path === "/api/health") {
        return jsonResponse({
          status: "online",
          service: "Shop Radar Cloud Push Server",
          version: "2.0.0",
          server_time: new Date().toISOString(),
          kv_connected: !!env.RADAR_KV,
          security: "PBKDF2-100k-Salted",
          scheduler: "30m (Standard) / 60m (Low Privilege)"
        });
      }

      // 2. 瀏覽特定網頁開啟登入後台 (Web Admin Dashboard)
      if (path === "/admin" || path === "/dashboard") {
        return handleAdminDashboardHtml(request, env);
      }

      // 3. 後台 API：管理者登入
      if (path === "/api/admin/login" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { username, password } = body;
        const adminUser = env.ADMIN_USERNAME || "admin";
        const adminPass = env.ADMIN_PASSWORD || "admin888";

        if (username === adminUser && password === adminPass) {
          const adminToken = "adm_" + crypto.randomUUID();
          await kvPut(env, `admin_token:${adminToken}`, "active", 86400); // 24小時有效
          return jsonResponse({ success: true, token: adminToken });
        } else {
          return jsonResponse({ success: false, message: "管理員帳號或密碼錯誤" }, 401);
        }
      }

      // 4. 後台 API：取得所有裝置清單
      if (path === "/api/admin/devices" && request.method === "GET") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const usersListStr = await kvGet(env, "system:all_users");
        const list = usersListStr ? JSON.parse(usersListStr) : [];
        const devices = [];
        for (const u of list) {
          const raw = await kvGet(env, `user:${u}`);
          if (raw) {
            try {
              const obj = JSON.parse(raw);
              devices.push({
                username: obj.username,
                role: obj.role || "standard",
                has_fcm: !!obj.fcmToken,
                fcm_preview: obj.fcmToken ? `${obj.fcmToken.slice(0, 8)}...${obj.fcmToken.slice(-6)}` : "",
                last_sync_at: obj.lastSyncAt || 0,
                last_checked_at: obj.lastCheckedAt || 0,
                subscriptions_count: (obj.subscriptions || []).length,
                logistics_count: (obj.logistics || []).length,
                created_at: obj.createdAt || 0
              });
            } catch (_) {}
          }
        }
        return jsonResponse({ success: true, devices });
      }

      // 5. 後台 API：修改使用者/裝置權限等級 (standard: 30分, low: 1小時)
      if (path === "/api/admin/set-role" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const body = await request.json().catch(() => ({}));
        const { username, role } = body;
        if (!username || !["standard", "low", "admin"].includes(role)) {
          return jsonResponse({ success: false, message: "參數不正確" }, 400);
        }
        const raw = await kvGet(env, `user:${username}`);
        if (!raw) return jsonResponse({ success: false, message: "找不到該帳號" }, 404);

        const obj = JSON.parse(raw);
        obj.role = role;
        await kvPut(env, `user:${username}`, JSON.stringify(obj));
        return jsonResponse({ success: true, message: `權限已更新為 ${role}` });
      }

      // 6. 後台 API：管理者手動觸發廣播推播
      if (path === "/api/admin/broadcast" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const body = await request.json().catch(() => ({}));
        const { title, body: pushBody, target_username } = body;
        if (!title || !pushBody) {
          return jsonResponse({ success: false, message: "標題與內容不可為空" }, 400);
        }

        let sentCount = 0;
        const usersListStr = await kvGet(env, "system:all_users");
        const list = usersListStr ? JSON.parse(usersListStr) : [];
        for (const u of list) {
          if (target_username && target_username !== u) continue;
          const raw = await kvGet(env, `user:${u}`);
          if (raw) {
            const user = JSON.parse(raw);
            if (user.fcmToken) {
              await sendFcmNotification(env, user.fcmToken, { title, body: pushBody }, { type: "admin_broadcast" });
              sentCount++;
            }
          }
        }
        return jsonResponse({ success: true, message: `已成功發送給 ${sentCount} 台裝置` });
      }

      // 7. 安裝即用：裝置自動開箱註冊 API (Zero-Config Auto Register)
      if (path === "/api/device/auto-register" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { device_id, fcm_token, device_name } = body;
        if (!device_id) {
          return jsonResponse({ success: false, message: "device_id 不可為空" }, 400);
        }

        const username = `dev_${device_id.replace(/[^a-zA-Z0-9]/g, "").slice(0, 16)}`;
        const existingRaw = await kvGet(env, `user:${username}`);
        let userData;

        if (existingRaw) {
          userData = JSON.parse(existingRaw);
          if (fcm_token) userData.fcmToken = fcm_token;
          if (device_name) userData.deviceName = device_name;
        } else {
          const token = crypto.randomUUID();
          userData = {
            username,
            role: "standard", // 新裝置預設為標準權限（每 30 分鐘檢查一次）
            token,
            isAnonymous: true,
            fcmToken: fcm_token || "",
            deviceName: device_name || "Android Device",
            createdAt: Date.now(),
            subscriptions: [],
            auctions: [],
            logistics: []
          };
          await kvPut(env, `token:${token}`, username);
          await recordUserInList(env, username);
        }

        await kvPut(env, `user:${username}`, JSON.stringify(userData));
        return jsonResponse({
          success: true,
          message: "裝置就緒，安裝即用啟用成功",
          token: userData.token,
          username: userData.username,
          role: userData.role || "standard"
        });
      }

      // 8. 帳號註冊（高安全加鹽雜湊 PBKDF2）
      if (path === "/api/auth/register" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { username, password, role } = body;
        if (!username || !password || username.length < 3 || password.length < 4) {
          return jsonResponse({ success: false, message: "帳號需至少3碼，密碼需至少4碼" }, 400);
        }

        const existing = await kvGet(env, `user:${username}`);
        if (existing) {
          return jsonResponse({ success: false, message: "此帳號已被註冊" }, 400);
        }

        const { hash, salt } = await hashPassword(password);
        const token = crypto.randomUUID();
        const userData = {
          username,
          passwordHash: hash,
          passwordSalt: salt,
          role: role === "low" ? "low" : "standard",
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

        return jsonResponse({
          success: true,
          message: "註冊成功",
          token,
          username,
          role: userData.role
        });
      }

      // 9. 帳號登入（具備防暴力密碼破解機制）
      if (path === "/api/auth/login" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { username, password } = body;
        if (!username || !password) {
          return jsonResponse({ success: false, message: "請輸入帳號與密碼" }, 400);
        }

        // 防暴力破解檢測
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        const allowed = await recordAndCheckRateLimit(env, `${clientIp}:${username}`);
        if (!allowed) {
          return jsonResponse({ success: false, message: "登入失敗次數過多，已被安全防護鎖定 15 分鐘" }, 429);
        }

        const rawUser = await kvGet(env, `user:${username}`);
        if (!rawUser) {
          return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);
        }

        const userData = JSON.parse(rawUser);
        let passwordMatches = false;

        if (userData.passwordSalt) {
          const { hash } = await hashPassword(password, userData.passwordSalt);
          passwordMatches = timingSafeEqual(hash, userData.passwordHash);
        } else if (userData.passwordHash) {
          // 相容舊版 SHA256 密碼升級
          const oldHash = await sha256(password);
          if (timingSafeEqual(oldHash, userData.passwordHash)) {
            passwordMatches = true;
            // 自動無縫升級為 PBKDF2
            const upgraded = await hashPassword(password);
            userData.passwordHash = upgraded.hash;
            userData.passwordSalt = upgraded.salt;
          }
        }

        if (!passwordMatches) {
          return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);
        }

        // 登入成功，重置防爆破計數器
        await clearRateLimit(env, `${clientIp}:${username}`);

        const token = crypto.randomUUID();
        userData.token = token;
        await kvPut(env, `user:${username}`, JSON.stringify(userData));
        await kvPut(env, `token:${token}`, username);

        return jsonResponse({
          success: true,
          message: "登入成功",
          token,
          username,
          role: userData.role || "standard"
        });
      }

      // 10. 身分驗證保護過濾
      const user = await authenticate(request, env);
      if (!user) {
        return jsonResponse({ success: false, message: "身份驗證失敗，請重新登入" }, 401);
      }

      // 11. 更新 FCM Token
      if (path === "/api/device/token" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { fcm_token } = body;
        if (!fcm_token) return jsonResponse({ success: false, message: "fcm_token 不可為空" }, 400);

        user.fcmToken = fcm_token;
        user.fcmUpdatedAt = Date.now();
        await kvPut(env, `user:${user.username}`, JSON.stringify(user));
        return jsonResponse({ success: true, message: "FCM Token 已更新綁定" });
      }

      // 12. 雲端同步監控資料
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
          role: user.role || "standard",
          check_frequency: (user.role === "low") ? "每 60 分鐘" : "每 30 分鐘",
          synced_at: user.lastSyncAt,
          counts: {
            subscriptions: (user.subscriptions || []).length,
            auctions: (user.auctions || []).length,
            logistics: (user.logistics || []).length
          }
        });
      }

      // 13. 使用者資訊查詢
      if (path === "/api/user" && request.method === "GET") {
        return jsonResponse({
          success: true,
          username: user.username,
          role: user.role || "standard",
          check_interval_minutes: (user.role === "low") ? 60 : 30,
          has_fcm_token: !!user.fcmToken,
          last_sync_at: user.lastSyncAt || 0,
          counts: {
            subscriptions: (user.subscriptions || []).length,
            auctions: (user.auctions || []).length,
            logistics: (user.logistics || []).length
          }
        });
      }

      // 14. 測試推播
      if (path === "/api/test-push" && request.method === "POST") {
        if (!user.fcmToken) {
          return jsonResponse({ success: false, message: "尚未取得 FCM Device Token" }, 400);
        }

        const roleText = (user.role === "low") ? "低權限模式 (每小時巡查)" : "標準權限 (每30分鐘巡查)";
        const pushResult = await sendFcmNotification(env, user.fcmToken, {
          title: "🎉 購物雷達雲端推播測試成功！",
          body: `嗨 ${user.username}，您的手機已連線至 Cloudflare！當前狀態：${roleText}`
        }, {
          type: "test_push",
          timestamp: Date.now().toString()
        });

        return jsonResponse({
          success: pushResult.success,
          message: pushResult.success ? "推播發送成功！請檢查通知欄" : `推播發送失敗: ${pushResult.error}`,
          details: pushResult
        });
      }

      return jsonResponse({ success: false, message: "Not Found" }, 404);
    } catch (err) {
      return jsonResponse({ success: false, message: "伺服器內部錯誤", error: err.message }, 500);
    }
  },

  // 15. Cron Trigger 排程監控入口（每 30 分鐘自動執行，依權限智慧分級）
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runScheduledMonitoring(env));
  }
};

/**
 * 智慧權限分級排程監控任務
 * - 標準/管理員：每 30 分鐘執行
 * - 低權限帳號：每 60 分鐘執行一次（跳過間隔不足 55 分鐘的輪次）
 */
async function runScheduledMonitoring(env) {
  const usersListStr = await kvGet(env, "system:all_users");
  if (!usersListStr) return;

  const usernames = JSON.parse(usersListStr);
  const now = Date.now();

  for (const username of usernames) {
    try {
      const userRaw = await kvGet(env, `user:${username}`);
      if (!userRaw) continue;
      const user = JSON.parse(userRaw);
      if (!user.fcmToken) continue;

      const role = user.role || "standard";
      const minInterval = (role === "low") ? 55 * 60 * 1000 : 25 * 60 * 1000;
      const lastChecked = user.lastCheckedAt || 0;

      // 若未達該等級的檢查頻率，跳過本次檢查
      if (now - lastChecked < minInterval) {
        continue;
      }

      user.lastCheckedAt = now;
      await kvPut(env, `user:${username}`, JSON.stringify(user));

      // 執行商品訂閱巡查（跳蚤本舖）
      if (Array.isArray(user.subscriptions) && user.subscriptions.length > 0) {
        await checkSubscriptions(env, user);
      }

      // 執行包裹貨況巡查
      if (Array.isArray(user.logistics) && user.logistics.length > 0) {
        await checkLogistics(env, user);
      }
    } catch (e) {
      console.error(`Error monitoring user ${username}:`, e);
    }
  }
}

async function checkSubscriptions(env, user) {
  for (const sub of user.subscriptions) {
    const url = sub.url || "";
    if (!url || !url.includes("lebay.com.tw")) continue;

    try {
      const resp = await fetch(url, {
        headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" }
      });
      if (resp.ok) {
        const html = await resp.text();
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

async function checkLogistics(env, user) {
  for (const item of user.logistics) {
    if (item.isPickedUp) continue;
    const no = item.trackingNumber;
    if (!no) continue;

    if (item.carrier === "spx" || no.startsWith("SPX")) {
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
 */
async function sendFcmNotification(env, fcmToken, notification, data = {}) {
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
    error: "尚未設定 FCM_SERVER_KEY 或 FCM_SERVICE_ACCOUNT_JSON 金鑰變數"
  };
}

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
  return tokenJson.access_token;
}

// ====================== 安全強化與輔助函式 ======================

async function hashPassword(password, saltHex = null) {
  const salt = saltHex ? hexToBytes(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const passKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const derivedBits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt: salt,
      iterations: 100000,
      hash: "SHA-256"
    },
    passKey,
    256
  );
  return {
    hash: bytesToHex(new Uint8Array(derivedBits)),
    salt: bytesToHex(salt)
  };
}

async function recordAndCheckRateLimit(env, key) {
  const recordKey = `ratelimit:${key}`;
  const raw = await kvGet(env, recordKey);
  const now = Date.now();
  let count = 1;

  if (raw) {
    try {
      const data = JSON.parse(raw);
      if (now - data.startTime < 15 * 60 * 1000) {
        count = data.count + 1;
        if (count > 5) {
          return false; // 超過 5 次，拒絕登入
        }
      }
    } catch (_) {}
  }
  await kvPut(env, recordKey, JSON.stringify({ count, startTime: now }), 900);
  return true;
}

async function clearRateLimit(env, key) {
  await kvPut(env, `ratelimit:${key}`, JSON.stringify({ count: 0, startTime: 0 }), 10);
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return diff === 0;
}

function bytesToHex(bytes) {
  return Array.from(bytes).map(b => b.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  }
  return bytes;
}

async function sha256(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
  return bytesToHex(new Uint8Array(buf));
}

function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: CORS_HEADERS });
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

async function kvPut(env, key, value, ttl = null) {
  if (env.RADAR_KV) {
    if (ttl) return await env.RADAR_KV.put(key, value, { expirationTtl: ttl });
    return await env.RADAR_KV.put(key, value);
  }
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

async function verifyAdmin(request, env) {
  const authHeader = request.headers.get("Authorization") || "";
  const token = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (!token) return false;
  const status = await kvGet(env, `admin_token:${token}`);
  return status === "active";
}

/**
 * 網頁登入後台 HTML (內建漂亮儀表板)
 */
function handleAdminDashboardHtml(request, env) {
  const html = `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>購物雷達 - 雲端管理後台</title>
  <style>
    :root {
      --bg: #0f172a;
      --card: #1e293b;
      --primary: #38bdf8;
      --primary-hover: #0ea5e9;
      --text: #f8fafc;
      --text-muted: #94a3b8;
      --border: #334155;
      --success: #22c55e;
      --warning: #f59e0b;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; min-height: 100vh; }
    .container { max-width: 1000px; margin: 0 auto; }
    header { display: flex; justify-content: space-between; align-items: center; padding-bottom: 20px; border-bottom: 1px solid var(--border); margin-bottom: 24px; }
    h1 { font-size: 24px; color: var(--primary); display: flex; align-items: center; gap: 10px; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }
    .stat-box { background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 12px; padding: 16px; text-align: center; }
    .stat-val { font-size: 32px; font-weight: bold; color: var(--primary); }
    .stat-label { font-size: 13px; color: var(--text-muted); margin-top: 4px; }
    table { width: 100%; border-collapse: collapse; margin-top: 14px; }
    th, td { padding: 12px; text-align: left; border-bottom: 1px solid var(--border); font-size: 13px; }
    th { color: var(--text-muted); font-weight: 600; }
    .badge { padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: bold; }
    .badge-30 { background: #064e3b; color: #34d399; }
    .badge-60 { background: #78350f; color: #fbbf24; }
    .btn { background: var(--primary); color: #000; border: none; padding: 8px 16px; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; }
    .btn:hover { background: var(--primary-hover); }
    .btn-secondary { background: var(--border); color: var(--text); }
    .btn-secondary:hover { background: #475569; }
    .input { background: #0b1120; border: 1px solid var(--border); color: var(--text); padding: 10px 14px; border-radius: 8px; width: 100%; margin-bottom: 12px; font-size: 14px; }
    .login-box { max-width: 400px; margin: 80px auto; }
  </style>
</head>
<body>
  <div class="container">
    <div id="loginSection" class="card login-box">
      <h2 style="margin-bottom: 16px; text-align: center;">🛡️ 管理員登入</h2>
      <p style="color: var(--text-muted); font-size: 13px; margin-bottom: 16px; text-align: center;">預設帳號 admin，請在環境變數設定 ADMIN_PASSWORD</p>
      <input type="text" id="adminUser" class="input" placeholder="管理員帳號" value="admin" />
      <input type="password" id="adminPass" class="input" placeholder="管理員密碼" />
      <button class="btn" style="width: 100%; padding: 12px;" onclick="doAdminLogin()">登入管理後台</button>
      <p id="loginErr" style="color: #f87171; font-size: 12px; margin-top: 10px; text-align: center; display: none;"></p>
    </div>

    <div id="dashboardSection" style="display: none;">
      <header>
        <h1>📡 購物雷達 - 雲端推播管理系統</h1>
        <button class="btn btn-secondary" onclick="logoutAdmin()">登出</button>
      </header>

      <div class="stats-grid">
        <div class="stat-box"><div class="stat-val" id="totalDevices">0</div><div class="stat-label">註冊裝置/用戶總數</div></div>
        <div class="stat-box"><div class="stat-val" id="activeFcm">0</div><div class="stat-label">有效 FCM 推播裝置</div></div>
        <div class="stat-box"><div class="stat-val" id="stdUsers">0</div><div class="stat-label">標準權限 (30分巡查)</div></div>
        <div class="stat-box"><div class="stat-val" id="lowUsers">0</div><div class="stat-label">低權限模式 (60分巡查)</div></div>
      </div>

      <div class="card">
        <h3 style="margin-bottom: 14px;">📢 全域即時推播測試</h3>
        <div style="display: flex; gap: 10px;">
          <input type="text" id="pushTitle" class="input" style="flex: 1;" placeholder="推播標題 (如：系統公告)" />
          <input type="text" id="pushBody" class="input" style="flex: 2;" placeholder="推播內文 (如：跳蚤本舖後台巡查運作中)" />
          <button class="btn" style="height: 42px;" onclick="sendBroadcastPush()">立即廣播</button>
        </div>
      </div>

      <div class="card">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <h3>📱 裝置與權限分級管理</h3>
          <button class="btn btn-secondary" onclick="loadDevices()">重新整理</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>帳號 / 裝置 ID</th>
              <th>目前巡查權限</th>
              <th>FCM 狀態</th>
              <th>追蹤統計</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody id="deviceTableBody"></tbody>
        </table>
      </div>
    </div>
  </div>

  <script>
    let adminToken = localStorage.getItem("radar_admin_token");
    if (adminToken) { showDashboard(); }

    async function doAdminLogin() {
      const u = document.getElementById("adminUser").value.trim();
      const p = document.getElementById("adminPass").value.trim();
      const err = document.getElementById("loginErr");
      err.style.display = "none";
      try {
        const res = await fetch("/api/admin/login", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: u, password: p })
        });
        const data = await res.json();
        if (data.success) {
          adminToken = data.token;
          localStorage.setItem("radar_admin_token", adminToken);
          showDashboard();
        } else {
          err.innerText = data.message || "登入失敗";
          err.style.display = "block";
        }
      } catch (e) {
        err.innerText = "連線失敗: " + e.message;
        err.style.display = "block";
      }
    }

    function logoutAdmin() {
      localStorage.removeItem("radar_admin_token");
      adminToken = null;
      document.getElementById("dashboardSection").style.display = "none";
      document.getElementById("loginSection").style.display = "block";
    }

    function showDashboard() {
      document.getElementById("loginSection").style.display = "none";
      document.getElementById("dashboardSection").style.display = "block";
      loadDevices();
    }

    async function loadDevices() {
      try {
        const res = await fetch("/api/admin/devices", {
          headers: { "Authorization": "Bearer " + adminToken }
        });
        if (res.status === 401) { logoutAdmin(); return; }
        const data = await res.json();
        const devices = data.devices || [];

        document.getElementById("totalDevices").innerText = devices.length;
        document.getElementById("activeFcm").innerText = devices.filter(d => d.has_fcm).length;
        document.getElementById("stdUsers").innerText = devices.filter(d => d.role !== "low").length;
        document.getElementById("lowUsers").innerText = devices.filter(d => d.role === "low").length;

        const tbody = document.getElementById("deviceTableBody");
        tbody.innerHTML = "";
        devices.forEach(d => {
          const isLow = d.role === "low";
          const tr = document.createElement("tr");
          tr.innerHTML = \`
            <td><strong>\${d.username}</strong></td>
            <td><span class="badge \${isLow ? 'badge-60' : 'badge-30'}">\${isLow ? '60分鐘 (低權限)' : '30分鐘 (標準)'}</span></td>
            <td>\${d.has_fcm ? '<span style="color:#22c55e;">已連線 (' + d.fcm_preview + ')</span>' : '<span style="color:#94a3b8;">未連線</span>'}</td>
            <td>訂閱:\${d.subscriptions_count} / 包裹:\${d.logistics_count}</td>
            <td>
              <button class="btn btn-secondary" style="padding:4px 10px; font-size:11px;" onclick="toggleRole('\${d.username}', '\${isLow ? 'standard' : 'low'}')">
                切換為 \${isLow ? '30分鐘' : '1小時'}
              </button>
            </td>
          \`;
          tbody.appendChild(tr);
        });
      } catch (e) {
        alert("載入裝置失敗: " + e.message);
      }
    }

    async function toggleRole(username, newRole) {
      const res = await fetch("/api/admin/set-role", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": "Bearer " + adminToken },
        body: JSON.stringify({ username, role: newRole })
      });
      const data = await res.json();
      if (data.success) { loadDevices(); } else { alert(data.message); }
    }

    async function sendBroadcastPush() {
      const title = document.getElementById("pushTitle").value.trim();
      const body = document.getElementById("pushBody").value.trim();
      if (!title || !body) return alert("請輸入標題與內容");

      const res = await fetch("/api/admin/broadcast", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": "Bearer " + adminToken },
        body: JSON.stringify({ title, body })
      });
      const data = await res.json();
      alert(data.message || (data.success ? "推播發送成功" : "發送失敗"));
    }
  </script>
</body>
</html>`;

  return new Response(html, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      "X-Frame-Options": "SAMEORIGIN"
    }
  });
}
