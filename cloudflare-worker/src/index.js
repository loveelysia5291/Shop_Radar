/**
 * 購物雷達 (Shop Radar) - Cloudflare Worker 雲端推播與背景監控服務
 * 
 * 安全性與漏洞修復版本：
 * 1. 預設管理員帳號 ADMIN，預設密碼 8 個 0 (00000000)。
 * 2. 首次登入強制彈窗要求修改密碼，未修改前全面鎖定所有管理功能。
 * 3. 密碼零明文儲存：PBKDF2 10 萬次疊代加鹽雜湊 (Salt + PBKDF2-SHA256)。
 * 4. 嚴密漏洞修復：
 *    - XSS 防護：所有前端動態資料經 escapeHtml 轉義。
 *    - SSRF 防護：嚴格限制只允許白名單 HTTPS 網址。
 *    - 時序攻擊防護：採用 timingSafeEqual 比對雜湊。
 *    - 暴力破解防護：密碼錯誤 5 次自動鎖定 15 分鐘。
 * 5. FCM 自動綁定與開箱即用：支援 Android 裝置啟動自動無感註冊與同步。
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
      // 1. 首頁健康檢查
      if (path === "/" || path === "/api/health") {
        return jsonResponse({
          status: "online",
          service: "Shop Radar Cloud Push Server",
          version: "2.1.0",
          server_time: new Date().toISOString(),
          kv_connected: !!env.RADAR_KV,
          security: "PBKDF2-100k-Salted-Strict",
          admin_login_url: "/admin"
        });
      }

      // 2. 視覺化管理員後台網頁
      if (path === "/admin" || path === "/dashboard") {
        return handleAdminDashboardHtml(request, env);
      }

      // 3. 管理員登入 (支援首次初始化與自訂管理員登入，停用預設 ADMIN)
      if (path === "/api/admin/login" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        if (!(await checkAdminRateLimit(env, clientIp))) {
          return jsonResponse({ success: false, message: "登入失敗次數過多，已被安全防護鎖定 15 分鐘" }, 429);
        }

        const body = await request.json().catch(() => ({}));
        const inputUsername = (body.username || "").trim();
        const password = (body.password || "").trim();

        if (!inputUsername || !password) {
          return jsonResponse({ success: false, message: "請輸入管理員帳號與密碼" }, 400);
        }

        const adminAuth = await getOrInitAdminAuth(env);

        // 若輸入預設 ADMIN 帳號
        if (inputUsername.toUpperCase() === "ADMIN") {
          // 若已初始化並停用 ADMIN
          if (adminAuth.adminDisabled) {
            return jsonResponse({
              success: false,
              message: "安全原則：預設 ADMIN 帳號已永久停用！請使用您設定的自訂管理員名稱登入。"
            }, 403);
          }

          // 尚未初始化：驗證預設密碼 00000000
          const { hash } = await hashPassword(password, adminAuth.salt);
          if (!timingSafeEqual(hash, adminAuth.hash)) {
            return jsonResponse({ success: false, message: "管理員密碼錯誤" }, 401);
          }

          await clearAdminRateLimit(env, clientIp);
          const tempToken = "temp_" + crypto.randomUUID();
          await kvPut(env, `admin_temp:${tempToken}`, "require_change", 600); // 10分鐘有效
          return jsonResponse({
            success: true,
            mustChangePassword: true,
            mustSetupNewAdmin: true,
            tempToken,
            message: "首次登入：請設定專屬管理員名稱與密碼，設定完成後將永久停用預設 ADMIN 帳號！"
          });
        }

        // 以自訂管理員名稱登入
        if (inputUsername.toLowerCase() !== adminAuth.username.toLowerCase()) {
          return jsonResponse({ success: false, message: "管理員帳號錯誤" }, 401);
        }

        const { hash } = await hashPassword(password, adminAuth.salt);
        if (!timingSafeEqual(hash, adminAuth.hash)) {
          return jsonResponse({ success: false, message: "管理員密碼錯誤" }, 401);
        }

        await clearAdminRateLimit(env, clientIp);
        const adminToken = "adm_" + crypto.randomUUID();
        await kvPut(env, `admin_token:${adminToken}`, "active", 86400); // 24小時有效
        return jsonResponse({
          success: true,
          mustChangePassword: false,
          token: adminToken,
          username: adminAuth.username
        });
      }

      // 4. 設定 / 修改管理員帳號與密碼 (支援修改管理員名稱並停用預設 ADMIN)
      if (path === "/api/admin/change-password" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { tempToken, adminToken, newUsername, newPassword } = body;

        let isAuthorized = false;
        let isFirstSetup = false;
        if (tempToken) {
          const state = await kvGet(env, `admin_temp:${tempToken}`);
          if (state === "require_change") {
            isAuthorized = true;
            isFirstSetup = true;
          }
        } else if (adminToken) {
          isAuthorized = await verifyAdminToken(adminToken, env);
        }

        if (!isAuthorized) {
          return jsonResponse({ success: false, message: "修改憑證權限已過期，請重新登入" }, 401);
        }

        const cleanUname = (newUsername || "").trim();
        if (isFirstSetup || cleanUname.length > 0) {
          if (cleanUname.toUpperCase() === "ADMIN") {
            return jsonResponse({ success: false, message: "新管理員名稱不能繼續使用預設的 ADMIN！請輸入自訂名稱。" }, 400);
          }
          if (cleanUname.length < 3) {
            return jsonResponse({ success: false, message: "管理員名稱長度需至少 3 碼！" }, 400);
          }
        }

        if (!newPassword || newPassword.length < 6) {
          return jsonResponse({ success: false, message: "新密碼長度需至少 6 位以上！" }, 400);
        }

        if (newPassword === "00000000") {
          return jsonResponse({ success: false, message: "新密碼不能繼續使用預設的 00000000！" }, 400);
        }

        const adminAuth = await getOrInitAdminAuth(env);
        const finalUname = cleanUname || adminAuth.username;

        // 使用 PBKDF2 重新隨機加鹽雜湊
        const { hash, salt } = await hashPassword(newPassword);
        const updatedAuth = {
          username: finalUname,
          hash,
          salt,
          mustChangePassword: false,
          adminDisabled: true, // 核心安全設定：設定完成後永久停用預設 ADMIN 權限
          updatedAt: Date.now()
        };
        await kvPut(env, "system:admin_auth", JSON.stringify(updatedAuth));

        // 註銷臨時權杖並簽發正式 Token
        if (tempToken) await kvDelete(env, `admin_temp:${tempToken}`);
        const finalToken = "adm_" + crypto.randomUUID();
        await kvPut(env, `admin_token:${finalToken}`, "active", 86400);

        return jsonResponse({
          success: true,
          message: `專屬管理員帳號 [${finalUname}] 設定成功！預設 ADMIN 帳號已永久停用。`,
          token: finalToken,
          username: finalUname
        });
      }

      // 後台 API：設定 FCM Server Key (免終端設定，直接網頁管理)
      if (path === "/api/admin/set-fcm-key" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const body = await request.json().catch(() => ({}));
        const key = (body.fcm_server_key || "").trim();
        if (!key) {
          return jsonResponse({ success: false, message: "請輸入有效的 FCM 伺服器金鑰" }, 400);
        }
        await kvPut(env, "config:fcm_server_key", key);
        return jsonResponse({ success: true, message: "FCM 伺服器金鑰已成功儲存！已可正常發送推播。" });
      }

      // 後台 API：取得推播伺服器設定狀態
      if (path === "/api/admin/get-config" && request.method === "GET") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const key = env.FCM_SERVER_KEY || await kvGet(env, "config:fcm_server_key");
        return jsonResponse({
          success: true,
          has_fcm_key: !!key,
          fcm_key_preview: key ? `${key.slice(0, 8)}...${key.slice(-6)}` : ""
        });
      }

      // 5. 後台 API：裝置清單查詢 (需驗證正式管理權杖)
      if (path === "/api/admin/devices" && request.method === "GET") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權或必須先修改預設密碼" }, 401);
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
                email: obj.email || "-",
                role: obj.role || "standard",
                has_fcm: !!obj.fcmToken,
                fcm_preview: obj.fcmToken ? `${obj.fcmToken.slice(0, 8)}...${obj.fcmToken.slice(-6)}` : "",
                last_sync_at: obj.lastSyncAt || 0,
                last_checked_at: obj.lastCheckedAt || 0,
                subscriptions_count: (obj.subscriptions || []).length,
                logistics_count: (obj.logistics || []).length,
                created_at: obj.createdAt || 0,
                is_registered: !!obj.isRegisteredAccount
              });
            } catch (_) {}
          }
        }
        return jsonResponse({ success: true, devices });
      }

      // 6. 後台 API：切換權限 (standard: 30分 / low: 1小時)
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

      // 7. 後台 API：廣播推播
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

      // 8. 人機驗證碼 (CAPTCHA) 生成 API (防止機器人濫發帳號)
      if (path === "/api/auth/captcha" && request.method === "GET") {
        const num1 = Math.floor(Math.random() * 80) + 12; // 12 ~ 91
        const num2 = Math.floor(Math.random() * 50) + 5;  // 5 ~ 54
        const ops = ["+", "-", "×"];
        const op = ops[Math.floor(Math.random() * ops.length)];
        let answer;
        let expression;

        if (op === "+") {
          answer = num1 + num2;
          expression = `${num1} + ${num2}`;
        } else if (op === "-") {
          const max = Math.max(num1, num2);
          const min = Math.min(num1, num2);
          answer = max - min;
          expression = `${max} - ${min}`;
        } else {
          const small1 = Math.floor(Math.random() * 8) + 2; // 2 ~ 9
          const small2 = Math.floor(Math.random() * 8) + 2; // 2 ~ 9
          answer = small1 * small2;
          expression = `${small1} × ${small2}`;
        }

        const captchaId = crypto.randomUUID();
        await kvPut(env, `captcha:${captchaId}`, JSON.stringify({
          answer: String(answer),
          createdAt: Date.now()
        }), 300); // 5 分鐘有效

        return jsonResponse({
          success: true,
          captchaId,
          question: `請計算：${expression} = ?`,
          expression
        });
      }

      // 9. 使用者信箱註冊 (PBKDF2 10萬次加鹽加密)
      if (path === "/api/auth/register" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        if (!(await recordAndCheckRateLimit(env, `reg:${clientIp}`))) {
          return jsonResponse({ success: false, message: "註冊頻率過高，已被安全限制 15 分鐘" }, 429);
        }

        const body = await request.json().catch(() => ({}));
        const { email, username, password, role } = body;

        // 1. 電子信箱驗證
        const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
        if (!email || !emailRegex.test(email.trim())) {
          return jsonResponse({ success: false, message: "請輸入有效的電子信箱格式" }, 400);
        }
        const cleanEmail = email.trim().toLowerCase();

        // 2. 使用者名稱驗證
        const cleanUname = (username || "").trim();
        if (cleanUname.length < 3 || cleanUname.length > 30) {
          return jsonResponse({ success: false, message: "使用者名稱長度需介於 3 至 30 個字元" }, 400);
        }
        if (cleanUname.includes("@")) {
          return jsonResponse({ success: false, message: "使用者名稱不可包含 @" }, 400);
        }

        // 3. 密碼強度驗證
        if (!password || password.length < 6) {
          return jsonResponse({ success: false, message: "密碼長度需至少 6 個字元" }, 400);
        }

        // 4. 唯一性檢查
        const existingEmailUser = await kvGet(env, `email:${cleanEmail}`);
        if (existingEmailUser) {
          return jsonResponse({ success: false, message: "此電子信箱已被註冊，請直接登入或使用忘記密碼" }, 400);
        }
        const existingUser = await kvGet(env, `user:${cleanUname}`);
        if (existingUser) {
          return jsonResponse({ success: false, message: "此使用者名稱已被使用，請換一個名稱" }, 400);
        }

        // 5. PBKDF2 10萬次加鹽加密
        const { hash, salt } = await hashPassword(password);
        const token = crypto.randomUUID();
        const userData = {
          username: cleanUname,
          email: cleanEmail,
          passwordHash: hash,
          passwordSalt: salt,
          role: role === "low" ? "low" : "standard",
          token,
          isRegisteredAccount: true,
          isAnonymous: false,
          createdAt: Date.now(),
          fcmToken: "",
          subscriptions: [],
          auctions: [],
          logistics: []
        };

        await kvPut(env, `user:${cleanUname}`, JSON.stringify(userData));
        await kvPut(env, `email:${cleanEmail}`, cleanUname);
        await kvPut(env, `token:${token}`, cleanUname);
        await recordUserInList(env, cleanUname);

        return jsonResponse({
          success: true,
          message: "註冊成功！已自動登入並可啟用推播",
          token,
          username: cleanUname,
          email: cleanEmail,
          role: userData.role
        });
      }

      // 10. 使用者登入 (支援使用者名稱或信箱登入)
      if (path === "/api/auth/login" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        const body = await request.json().catch(() => ({}));
        const accountInput = (body.username || body.account || "").trim();
        const password = (body.password || "").trim();

        if (!accountInput || !password) {
          return jsonResponse({ success: false, message: "請輸入帳號 (或信箱) 與密碼" }, 400);
        }

        const rateLimitKey = `user:${clientIp}:${accountInput}`;
        const allowed = await recordAndCheckRateLimit(env, rateLimitKey);
        if (!allowed) {
          return jsonResponse({ success: false, message: "登入失敗次數過多，已被安全鎖定 15 分鐘" }, 429);
        }

        let lookupUsername = accountInput;
        if (accountInput.includes("@")) {
          const mappedUser = await kvGet(env, `email:${accountInput.toLowerCase()}`);
          if (!mappedUser) {
            return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);
          }
          lookupUsername = mappedUser;
        }

        const rawUser = await kvGet(env, `user:${lookupUsername}`);
        if (!rawUser) return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);

        const userData = JSON.parse(rawUser);
        let passwordMatches = false;

        if (userData.passwordSalt) {
          const { hash } = await hashPassword(password, userData.passwordSalt);
          passwordMatches = timingSafeEqual(hash, userData.passwordHash);
        } else if (userData.passwordHash) {
          const oldHash = await sha256(password);
          if (timingSafeEqual(oldHash, userData.passwordHash)) {
            passwordMatches = true;
            const upgraded = await hashPassword(password);
            userData.passwordHash = upgraded.hash;
            userData.passwordSalt = upgraded.salt;
          }
        }

        if (!passwordMatches) return jsonResponse({ success: false, message: "帳號或密碼錯誤" }, 401);

        await clearRateLimit(env, rateLimitKey);
        const token = crypto.randomUUID();
        userData.token = token;
        await kvPut(env, `user:${userData.username}`, JSON.stringify(userData));
        await kvPut(env, `token:${token}`, userData.username);

        return jsonResponse({
          success: true,
          message: "登入成功",
          token,
          username: userData.username,
          email: userData.email || "",
          role: userData.role || "standard"
        });
      }

      // 11. 忘記密碼：申請驗證碼 API (免人機驗證)
      if (path === "/api/auth/forgot-password/request" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        if (!(await recordAndCheckRateLimit(env, `forgot:${clientIp}`))) {
          return jsonResponse({ success: false, message: "申請頻率過高，請 15 分鐘後再試" }, 429);
        }

        const body = await request.json().catch(() => ({}));
        const { email } = body;

        const cleanEmail = (email || "").trim().toLowerCase();
        if (!cleanEmail) {
          return jsonResponse({ success: false, message: "請輸入註冊的電子信箱" }, 400);
        }

        const matchedUsername = await kvGet(env, `email:${cleanEmail}`);
        if (!matchedUsername) {
          return jsonResponse({ success: false, message: "此電子信箱尚未註冊任何帳號" }, 404);
        }

        // 產生 6 位數高熵重設驗證碼
        const otpCode = Math.floor(100000 + Math.random() * 900000).toString();
        await kvPut(env, `pwd_reset:${cleanEmail}`, JSON.stringify({
          code: otpCode,
          username: matchedUsername,
          createdAt: Date.now()
        }), 600); // 10 分鐘有效

        return jsonResponse({
          success: true,
          message: "重設驗證碼已生成，有效時間為 10 分鐘",
          email: cleanEmail,
          devCode: otpCode // 支援獨立架設無外送郵件伺服器時直接於前端提示
        });
      }

      // 12. 忘記密碼：驗證並重設密碼 API
      if (path === "/api/auth/forgot-password/reset" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { email, code, newPassword } = body;

        const cleanEmail = (email || "").trim().toLowerCase();
        const cleanCode = (code || "").trim();

        if (!cleanEmail || !cleanCode || !newPassword) {
          return jsonResponse({ success: false, message: "請輸入完整信箱、驗證碼與新密碼" }, 400);
        }

        if (newPassword.length < 6) {
          return jsonResponse({ success: false, message: "新密碼長度需至少 6 個字元" }, 400);
        }

        const resetRaw = await kvGet(env, `pwd_reset:${cleanEmail}`);
        if (!resetRaw) {
          return jsonResponse({ success: false, message: "驗證碼已過期或不存在，請重新申請" }, 400);
        }

        const resetData = JSON.parse(resetRaw);
        if (!timingSafeEqual(cleanCode, resetData.code)) {
          return jsonResponse({ success: false, message: "驗證碼錯誤，請重新輸入" }, 400);
        }

        const userRaw = await kvGet(env, `user:${resetData.username}`);
        if (!userRaw) {
          return jsonResponse({ success: false, message: "找不到該帳號" }, 404);
        }

        const userData = JSON.parse(userRaw);
        const { hash, salt } = await hashPassword(newPassword);
        userData.passwordHash = hash;
        userData.passwordSalt = salt;
        await kvPut(env, `user:${userData.username}`, JSON.stringify(userData));
        await kvDelete(env, `pwd_reset:${cleanEmail}`);

        return jsonResponse({
          success: true,
          message: "密碼已安全重設成功！請使用新密碼登入"
        });
      }

      // 13. 訪客/舊版自動註冊阻擋 (強制要求登入帳號方可啟動推播服務)
      if (path === "/api/device/auto-register" && request.method === "POST") {
        return jsonResponse({
          success: false,
          message: "為確保帳號安全與防濫用，僅限註冊並登入專屬帳號後方可啟用雲端推播與背景監控服務。請在 App 內註冊或登入。"
        }, 403);
      }

      // 11. 身份認證保護
      const user = await authenticate(request, env);
      if (!user) return jsonResponse({ success: false, message: "身份驗證失敗" }, 401);

      // 更新 FCM Token
      if (path === "/api/device/token" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { fcm_token } = body;
        if (!fcm_token) return jsonResponse({ success: false, message: "fcm_token 不可為空" }, 400);

        user.fcmToken = fcm_token;
        user.fcmUpdatedAt = Date.now();
        await kvPut(env, `user:${user.username}`, JSON.stringify(user));
        return jsonResponse({ success: true, message: "FCM Token 已更新綁定" });
      }

      // 同步追蹤項目 (加上安全過濾，杜絕 SSRF)
      if (path === "/api/sync" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { subscriptions, auctions, logistics, fcm_token } = body;

        if (Array.isArray(subscriptions)) {
          user.subscriptions = subscriptions.filter(s => isValidUrl(s?.url));
        }
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

      // 使用者狀態資訊
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

      // 測試推播
      if (path === "/api/test-push" && request.method === "POST") {
        if (!user.fcmToken) return jsonResponse({ success: false, message: "尚未取得 FCM Device Token" }, 400);

        const roleText = (user.role === "low") ? "低權限模式 (每小時巡查)" : "標準權限 (每30分鐘巡查)";
        const pushResult = await sendFcmNotification(env, user.fcmToken, {
          title: "🎉 購物雷達雲端推播測試成功！",
          body: `嗨 ${user.username}，您的手機已連線至 Cloudflare！當前狀態：${roleText}`
        }, { type: "test_push", timestamp: Date.now().toString() });

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

  // 30分鐘排程監控
  async scheduled(event, env, ctx) {
    ctx.waitUntil(runScheduledMonitoring(env));
  }
};

/**
 * 智慧權限分級排程巡查
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
      // 核心規則：僅限已正式註冊並有密碼憑證之登入帳號啟動推播服務，訪客不進行巡查推播
      if (!user.isRegisteredAccount && !user.passwordHash) continue;
      if (!user.fcmToken) continue;

      const role = user.role || "standard";
      const minInterval = (role === "low") ? 55 * 60 * 1000 : 25 * 60 * 1000;
      const lastChecked = user.lastCheckedAt || 0;

      if (now - lastChecked < minInterval) continue;

      user.lastCheckedAt = now;
      await kvPut(env, `user:${username}`, JSON.stringify(user));

      if (Array.isArray(user.subscriptions) && user.subscriptions.length > 0) {
        await checkSubscriptions(env, user);
      }
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
    if (!url || !url.includes("lebay.com.tw") || !isValidUrl(url)) continue;
    try {
      const resp = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0" } });
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
            }, { type: "subscription_update", url: url });
          }
          await kvPut(env, cacheKey, latestTitle);
        }
      }
    } catch (_) {}
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
              }, { type: "logistics_update", tracking_no: no });
              await kvPut(env, cacheKey, latestDesc);
            }
          }
        }
      } catch (_) {}
    }
  }
}

async function sendFcmNotification(env, fcmToken, notification, data = {}) {
  const fcmKey = (await kvGet(env, "config:fcm_server_key")) || env.FCM_SERVER_KEY;
  if (fcmKey) {
    try {
      const payload = {
        to: fcmToken,
        priority: "high",
        notification: { title: notification.title, body: notification.body, sound: "default" },
        data: { ...data, title: notification.title, body: notification.body }
      };
      const resp = await fetch("https://fcm.googleapis.com/fcm/send", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `key=${fcmKey}` },
        body: JSON.stringify(payload)
      });
      return { success: resp.ok, response: await resp.text() };
    } catch (e) {
      return { success: false, error: e.message };
    }
  }
  return { success: false, error: "尚未設定 FCM_SERVER_KEY (請至管理員後台輸入金鑰)" };
}

// ====================== 安全強化函式 ======================

async function getOrInitAdminAuth(env) {
  const raw = await kvGet(env, "system:admin_auth");
  if (raw) {
    try {
      const data = JSON.parse(raw);
      if (typeof data.adminDisabled === "undefined") {
        data.adminDisabled = false;
      }
      return data;
    } catch (_) {}
  }
  // 初始預設密碼 00000000，使用隨機 Salt 與 PBKDF2 加密儲存
  const { hash, salt } = await hashPassword("00000000");
  const initial = {
    username: "ADMIN",
    hash,
    salt,
    mustChangePassword: true,
    adminDisabled: false,
    updatedAt: Date.now()
  };
  await kvPut(env, "system:admin_auth", JSON.stringify(initial));
  return initial;
}

async function hashPassword(password, saltHex = null) {
  const salt = saltHex ? hexToBytes(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const passKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), { name: "PBKDF2" }, false, ["deriveBits"]);
  const derivedBits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: salt, iterations: 100000, hash: "SHA-256" }, passKey, 256);
  return { hash: bytesToHex(new Uint8Array(derivedBits)), salt: bytesToHex(salt) };
}

async function checkAdminRateLimit(env, clientIp) {
  const key = `ratelimit:admin:${clientIp}`;
  const raw = await kvGet(env, key);
  const now = Date.now();
  let count = 1;
  if (raw) {
    try {
      const data = JSON.parse(raw);
      if (now - data.startTime < 15 * 60 * 1000) {
        count = data.count + 1;
        if (count > 5) return false;
      }
    } catch (_) {}
  }
  await kvPut(env, key, JSON.stringify({ count, startTime: now }), 900);
  return true;
}

async function clearAdminRateLimit(env, clientIp) {
  await kvDelete(env, `ratelimit:admin:${clientIp}`);
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
        if (count > 5) return false;
      }
    } catch (_) {}
  }
  await kvPut(env, recordKey, JSON.stringify({ count, startTime: now }), 900);
  return true;
}

async function clearRateLimit(env, key) {
  await kvDelete(env, `ratelimit:${key}`);
}

function isValidUrl(urlString) {
  try {
    const u = new URL(urlString);
    if (u.protocol !== "https:") return false;
    const host = u.hostname.toLowerCase();
    const allowed = ["lebay.com.tw", "spx.tw", "fmec.famiport.com.tw", "eservice.7-11.com.tw", "hilife.com.tw", "ruten.com.tw", "yahoo.com"];
    return allowed.some(h => host === h || host.endsWith("." + h));
  } catch (_) {
    return false;
  }
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) { diff |= a.charCodeAt(i) ^ b.charCodeAt(i); }
  return diff === 0;
}

function bytesToHex(bytes) { return Array.from(bytes).map(b => b.toString(16).padStart(2, "0")).join(""); }
function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) { bytes[i] = parseInt(hex.substr(i * 2, 2), 16); }
  return bytes;
}
async function sha256(str) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(str));
  return bytesToHex(new Uint8Array(buf));
}
function jsonResponse(data, status = 200) { return new Response(JSON.stringify(data), { status, headers: CORS_HEADERS }); }

async function kvGet(env, key) {
  if (env.RADAR_KV) return await env.RADAR_KV.get(key);
  return memoryStore.get(key) || null;
}
async function kvPut(env, key, value, ttl = null) {
  if (env.RADAR_KV) {
    if (ttl) {
      const safeTtl = Math.max(Number(ttl) || 60, 60); // Cloudflare KV 要求 expirationTtl 最低需至少 60 秒
      return await env.RADAR_KV.put(key, value, { expirationTtl: safeTtl });
    }
    return await env.RADAR_KV.put(key, value);
  }
  memoryStore.set(key, value);
}
async function kvDelete(env, key) {
  if (env.RADAR_KV) return await env.RADAR_KV.delete(key);
  memoryStore.delete(key);
}

async function recordUserInList(env, username) {
  const raw = await kvGet(env, "system:all_users");
  let list = [];
  if (raw) { try { list = JSON.parse(raw); } catch (_) {} }
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
  return await verifyAdminToken(token, env);
}

async function verifyAdminToken(token, env) {
  if (!token) return false;
  const status = await kvGet(env, `admin_token:${token}`);
  return status === "active";
}

function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

/**
 * 視覺化管理後台 HTML（內建強制修改密碼彈窗與安全轉義）
 */
function handleAdminDashboardHtml(request, env) {
  const html = `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>購物雷達 - 雲端管理後台</title>
  <style>
    :root { --bg: #0b1120; --card: #1e293b; --primary: #38bdf8; --border: #334155; --text: #f8fafc; --err: #f87171; }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; min-height: 100vh; }
    .container { max-width: 1000px; margin: 0 auto; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 20px; margin-bottom: 20px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); }
    .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 20px; }
    .stat-box { background: rgba(255,255,255,0.03); border: 1px solid var(--border); border-radius: 12px; padding: 16px; text-align: center; }
    .stat-val { font-size: 32px; font-weight: bold; color: var(--primary); }
    .badge { padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: bold; }
    .badge-30 { background: #064e3b; color: #34d399; }
    .badge-60 { background: #78350f; color: #fbbf24; }
    .btn { background: var(--primary); color: #000; border: none; padding: 8px 16px; border-radius: 8px; font-weight: bold; cursor: pointer; transition: 0.2s; }
    .btn:hover { opacity: 0.9; }
    .input { background: #070d18; border: 1px solid var(--border); color: var(--text); padding: 10px; border-radius: 8px; width: 100%; margin-bottom: 12px; font-size: 14px; }
    table { width: 100%; border-collapse: collapse; margin-top: 14px; }
    th, td { padding: 12px; text-align: left; border-bottom: 1px solid var(--border); font-size: 13px; }
    .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.85); display: flex; align-items: center; justify-content: center; z-index: 9999; }
  </style>
</head>
<body>
  <div class="container">
    <!-- 登入卡片 -->
    <div id="loginSection" class="card" style="max-width: 420px; margin: 80px auto;">
      <h2 style="margin-bottom: 16px; text-align: center; color: var(--primary);">🛡️ 購物雷達管理員登入</h2>
      <p style="color: #94a3b8; font-size: 12px; margin-bottom: 16px; text-align: center;">預設帳號 ADMIN (首次需初始化)，或輸入您設定的專屬管理員帳號</p>
      <input type="text" id="adminUser" class="input" placeholder="管理員帳號 (預設 ADMIN)" value="ADMIN" />
      <input type="password" id="adminPass" class="input" placeholder="管理員密碼 (預設 00000000)" value="00000000" />
      <button class="btn" style="width: 100%; padding: 12px;" onclick="doAdminLogin()">登入管理後台</button>
      <p id="loginErr" style="color: var(--err); font-size: 12px; margin-top: 10px; text-align: center; display: none;"></p>
    </div>

    <!-- 儀表板主要內容 -->
    <div id="dashboardSection" style="display: none;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 10px;">
        <div>
          <h1 style="color: var(--primary); font-size: 22px;">📡 購物雷達 - 雲端推播管理系統</h1>
          <p style="font-size: 12px; color: #94a3b8; margin-top: 4px;">登入身份：<strong id="currentAdminName" style="color: #38bdf8;">ADMIN</strong></p>
        </div>
        <div>
          <button class="btn" style="background:#334155; color:#fff; margin-right: 8px;" onclick="showChangePassModal(false)">修改帳號密碼</button>
          <button class="btn" style="background:#475569; color:#fff;" onclick="logoutAdmin()">登出</button>
        </div>
      </div>

      <div class="stats-grid">
        <div class="stat-box"><div class="stat-val" id="totalDevices">0</div><div style="font-size:12px;color:#94a3b8;">註冊手機總數</div></div>
        <div class="stat-box"><div class="stat-val" id="activeFcm">0</div><div style="font-size:12px;color:#94a3b8;">有效 FCM 推播裝置</div></div>
        <div class="stat-box"><div class="stat-val" id="stdUsers">0</div><div style="font-size:12px;color:#94a3b8;">標準權限 (30分巡查)</div></div>
        <div class="stat-box"><div class="stat-val" id="lowUsers">0</div><div style="font-size:12px;color:#94a3b8;">低權限模式 (60分巡查)</div></div>
      </div>

      <!-- FCM 金鑰即時設定卡片 -->
      <div class="card">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; flex-wrap: wrap; gap: 8px;">
          <h3 style="color: #38bdf8;">🔑 FCM 雲端推播金鑰設定 (免終端指令)</h3>
          <span id="fcmKeyStatus" class="badge" style="background: #78350f; color: #fbbf24;">檢測中...</span>
        </div>
        <p style="font-size: 13px; color: #94a3b8; margin-bottom: 12px; line-height: 19px;">
          請至 Firebase Console (專案設定 -&gt; 雲端傳訊 Cloud Messaging -&gt; 伺服器金鑰 Server Key) 複製並貼上於下方，儲存後立即生效，雲端巡查即可直接推播通知至每台 Android 手機！
        </p>
        <div style="display: flex; gap: 10px;">
          <input type="password" id="fcmKeyInput" class="input" style="flex: 1; margin-bottom: 0;" placeholder="貼上 Firebase 伺服器金鑰 (AAAA...)" />
          <button class="btn" style="height: 42px; white-space: nowrap;" onclick="doSaveFcmKey()">儲存金鑰</button>
        </div>
        <p id="fcmKeyMsg" style="font-size: 12px; margin-top: 8px; display: none;"></p>
      </div>

      <div class="card">
        <h3 style="margin-bottom: 12px;">📢 全域即時廣播推播測試</h3>
        <div style="display: flex; gap: 10px;">
          <input type="text" id="pushTitle" class="input" style="flex: 1;" placeholder="推播標題" />
          <input type="text" id="pushBody" class="input" style="flex: 2;" placeholder="推播內文" />
          <button class="btn" style="height: 42px;" onclick="sendBroadcastPush()">立即廣播</button>
        </div>
      </div>

      <div class="card">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <h3>📱 裝置與權限分級管理</h3>
          <button class="btn" style="background:#334155; color:#fff;" onclick="loadDevices()">重新整理</button>
        </div>
        <table>
          <thead>
            <tr><th>帳號名稱</th><th>電子信箱</th><th>目前巡查頻率</th><th>FCM 推播狀態</th><th>操作</th></tr>
          </thead>
          <tbody id="deviceTableBody"></tbody>
        </table>
      </div>
    </div>
  </div>

  <!-- 強制初始化 / 修改管理員帳密 Modal -->
  <div id="changePassModal" class="modal-overlay" style="display: none;">
    <div class="card" style="width: 100%; max-width: 440px; background: #1e293b;">
      <h3 id="modalTitle" style="color: #fbbf24; margin-bottom: 8px;">⚠️ 安全要求：建立專屬管理員帳號</h3>
      <p id="modalDesc" style="font-size: 13px; color: #94a3b8; margin-bottom: 16px; line-height: 18px;">
        目前檢測為初始預設密碼，為修復預設帳密漏洞，系統要求您立即設定專屬管理員名稱與密碼。設定完成後，預設 ADMIN 帳號將永久停用！
      </p>
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">全新管理員名稱 (不可為 ADMIN)</label>
      <input type="text" id="newAdminUser" class="input" placeholder="請輸入自訂管理員名稱 (至少 3 碼)" />
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">全新密碼 (至少 6 碼，不可為 00000000)</label>
      <input type="password" id="newPass1" class="input" placeholder="請輸入全新密碼" />
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">再次確認密碼</label>
      <input type="password" id="newPass2" class="input" placeholder="再次確認全新密碼" />
      <button class="btn" style="width: 100%; padding: 12px;" onclick="doSubmitNewPassword()">確認儲存並進入後台</button>
      <button id="btnCancelModal" class="btn" style="width: 100%; padding: 10px; margin-top: 8px; background: #334155; color: #fff; display: none;" onclick="closeChangePassModal()">取消</button>
      <p id="changePassErr" style="color: var(--err); font-size: 12px; margin-top: 10px; text-align: center; display: none;"></p>
    </div>
  </div>

  <script>
    let adminToken = localStorage.getItem("radar_admin_token");
    let adminName = localStorage.getItem("radar_admin_name") || "ADMIN";
    let currentTempToken = null;
    let isMandatorySetup = false;

    if (adminToken) { showDashboard(); }

    async function doAdminLogin() {
      const u = document.getElementById("adminUser").value.trim();
      const p = document.getElementById("adminPass").value.trim();
      const err = document.getElementById("loginErr");
      err.style.display = "none";

      try {
        const res = await fetch("/api/admin/login", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: u, password: p })
        });
        const data = await res.json();
        if (data.success) {
          if (data.mustChangePassword || data.mustSetupNewAdmin) {
            currentTempToken = data.tempToken;
            showChangePassModal(true);
          } else {
            adminToken = data.token;
            adminName = data.username || u;
            localStorage.setItem("radar_admin_token", adminToken);
            localStorage.setItem("radar_admin_name", adminName);
            showDashboard();
          }
        } else {
          err.innerText = data.message || "登入失敗";
          err.style.display = "block";
        }
      } catch (e) {
        err.innerText = "連線失敗: " + e.message;
        err.style.display = "block";
      }
    }

    function showChangePassModal(firstTime) {
      isMandatorySetup = !!firstTime;
      document.getElementById("changePassErr").style.display = "none";
      document.getElementById("newPass1").value = "";
      document.getElementById("newPass2").value = "";

      const titleEl = document.getElementById("modalTitle");
      const descEl = document.getElementById("modalDesc");
      const userEl = document.getElementById("newAdminUser");
      const cancelBtn = document.getElementById("btnCancelModal");

      if (isMandatorySetup) {
        titleEl.innerText = "⚠️ 安全要求：建立專屬管理員帳號";
        titleEl.style.color = "#fbbf24";
        descEl.innerText = "目前為初始預設密碼，基於安全原則，請設定專屬管理員名稱與密碼。設定完成後，預設 ADMIN 帳號將永久停用！";
        userEl.value = "";
        cancelBtn.style.display = "none";
      } else {
        titleEl.innerText = "🛠️ 修改管理員帳號與密碼";
        titleEl.style.color = "#38bdf8";
        descEl.innerText = "您可以變更管理員登入名稱或密碼。修改完成後請使用新憑證登入。";
        userEl.value = localStorage.getItem("radar_admin_name") || "";
        cancelBtn.style.display = "block";
      }

      document.getElementById("changePassModal").style.display = "flex";
    }

    function closeChangePassModal() {
      if (!isMandatorySetup) {
        document.getElementById("changePassModal").style.display = "none";
      }
    }

    async function doSubmitNewPassword() {
      const u = document.getElementById("newAdminUser").value.trim();
      const p1 = document.getElementById("newPass1").value.trim();
      const p2 = document.getElementById("newPass2").value.trim();
      const err = document.getElementById("changePassErr");
      err.style.display = "none";

      if (isMandatorySetup || u.length > 0) {
        if (u.toUpperCase() === "ADMIN") {
          err.innerText = "管理員名稱不能為預設的 ADMIN！請輸入專屬名稱。";
          err.style.display = "block";
          return;
        }
        if (u.length < 3) {
          err.innerText = "管理員名稱長度需至少 3 碼！";
          err.style.display = "block";
          return;
        }
      }

      if (p1.length < 6) {
        err.innerText = "新密碼長度需至少 6 碼！";
        err.style.display = "block";
        return;
      }
      if (p1 === "00000000") {
        err.innerText = "新密碼不能繼續使用 8 個 0！";
        err.style.display = "block";
        return;
      }
      if (p1 !== p2) {
        err.innerText = "兩次輸入的密碼不一致！";
        err.style.display = "block";
        return;
      }

      try {
        const payload = {
          tempToken: currentTempToken,
          adminToken: adminToken,
          newUsername: u,
          newPassword: p1
        };
        const res = await fetch("/api/admin/change-password", {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (data.success) {
          alert(data.message || "管理員帳號密碼設定成功！");
          document.getElementById("changePassModal").style.display = "none";
          adminToken = data.token;
          adminName = data.username || u;
          localStorage.setItem("radar_admin_token", adminToken);
          localStorage.setItem("radar_admin_name", adminName);
          currentTempToken = null;
          showDashboard();
        } else {
          err.innerText = data.message || "修改失敗";
          err.style.display = "block";
        }
      } catch (e) {
        err.innerText = "連線失敗: " + e.message;
        err.style.display = "block";
      }
    }

    function logoutAdmin() {
      localStorage.removeItem("radar_admin_token");
      localStorage.removeItem("radar_admin_name");
      adminToken = null;
      currentTempToken = null;
      document.getElementById("dashboardSection").style.display = "none";
      document.getElementById("loginSection").style.display = "block";
    }

    function showDashboard() {
      document.getElementById("loginSection").style.display = "none";
      document.getElementById("dashboardSection").style.display = "block";
      document.getElementById("currentAdminName").innerText = localStorage.getItem("radar_admin_name") || "ADMIN";
      loadConfig();
      loadDevices();
    }

    async function loadConfig() {
      try {
        const res = await fetch("/api/admin/get-config", { headers: { "Authorization": "Bearer " + adminToken } });
        if (res.status === 401) return logoutAdmin();
        const data = await res.json();
        const statusEl = document.getElementById("fcmKeyStatus");
        const inputEl = document.getElementById("fcmKeyInput");

        if (data.has_fcm_key) {
          statusEl.innerText = "● 已設定 (" + (data.fcm_key_preview || "") + ")";
          statusEl.className = "badge badge-30";
          inputEl.placeholder = "已設定金鑰 (" + (data.fcm_key_preview || "") + ")，若要更新請輸入新金鑰";
        } else {
          statusEl.innerText = "○ 尚未設定 (無法發送推播)";
          statusEl.className = "badge badge-60";
          inputEl.placeholder = "貼上 Firebase 伺服器金鑰 (AAAA...)";
        }
      } catch (_) {}
    }

    async function doSaveFcmKey() {
      const key = document.getElementById("fcmKeyInput").value.trim();
      const msg = document.getElementById("fcmKeyMsg");
      msg.style.display = "none";

      if (!key) {
        alert("請輸入有效的 FCM 伺服器金鑰");
        return;
      }

      try {
        const res = await fetch("/api/admin/set-fcm-key", {
          method: "POST", headers: { "Content-Type": "application/json", "Authorization": "Bearer " + adminToken },
          body: JSON.stringify({ fcm_server_key: key })
        });
        const data = await res.json();
        if (data.success) {
          msg.style.color = "#34d399";
          msg.innerText = "✅ " + data.message;
          msg.style.display = "block";
          document.getElementById("fcmKeyInput").value = "";
          loadConfig();
        } else {
          msg.style.color = "var(--err)";
          msg.innerText = "❌ " + (data.message || "儲存失敗");
          msg.style.display = "block";
        }
      } catch (e) {
        msg.style.color = "var(--err)";
        msg.innerText = "❌ 連線錯誤: " + e.message;
        msg.style.display = "block";
      }
    }

    async function loadDevices() {
      const res = await fetch("/api/admin/devices", { headers: { "Authorization": "Bearer " + adminToken } });
      if (res.status === 401) return logoutAdmin();
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
        const safeUsername = escapeHtml(d.username);
        const safeEmail = escapeHtml(d.email || "-");
        tr.innerHTML = \`
          <td><strong>\${safeUsername}</strong></td>
          <td style="color:#94a3b8; font-size:13px;">\${safeEmail}</td>
          <td><span class="badge \${isLow ? 'badge-60' : 'badge-30'}">\${isLow ? '60分鐘 (低權限)' : '30分鐘 (標準)'}</span></td>
          <td>\${d.has_fcm ? '<span style="color:#34d399;">● 已連線</span>' : '<span style="color:#94a3b8;">○ 未連線</span>'}</td>
          <td>
            <button class="btn" style="background:#334155; color:#fff; padding:4px 10px; font-size:12px;" onclick="toggleRole('\${safeUsername}', '\${isLow ? 'standard' : 'low'}')">
              切換為 \${isLow ? '30分鐘' : '1小時'}
            </button>
          </td>
        \`;
        tbody.appendChild(tr);
      });
    }

    function escapeHtml(s) {
      return String(s || "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
    }

    async function toggleRole(username, newRole) {
      await fetch("/api/admin/set-role", {
        method: "POST", headers: { "Content-Type": "application/json", "Authorization": "Bearer " + adminToken },
        body: JSON.stringify({ username, role: newRole })
      });
      loadDevices();
    }

    async function sendBroadcastPush() {
      const title = document.getElementById("pushTitle").value.trim();
      const body = document.getElementById("pushBody").value.trim();
      if (!title || !body) return alert("請輸入標題與內容");
      const res = await fetch("/api/admin/broadcast", {
        method: "POST", headers: { "Content-Type": "application/json", "Authorization": "Bearer " + adminToken },
        body: JSON.stringify({ title, body })
      });
      const data = await res.json();
      alert(data.message || (data.success ? "推播發送成功" : "發送失敗"));
    }
  </script>
</body>
</html>`;
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "X-Content-Type-Options": "nosniff" } });
}
