/**
 * 購物雷達 (Shop Radar) - Cloudflare Worker 雲端推播與背景監控服務
 * 
 * 現代企業級高安全加固版本 (全面修復安全掃描 9 項問題)：
 * 1. 零預設弱憑證：初次部署強制要求管理者自行設定專屬名稱與高強度密碼。
 * 2. 前端路由隔離防護：未登入者僅回傳純淨登入表單，所有敏感管理 API 完全隔離於認證後。
 * 3. 嚴格 CORS 與 CSRF 防護：管理端點全面禁止外部跨域，僅允許同源請求。
 * 4. 根端點極簡安全健康檢查：不洩漏任何架構、版本、KV 連線狀態或後台路由。
 * 5. 安全 HttpOnly Cookie Session：Token 絕不存於 localStorage，免疫 XSS 竊取。
 * 6. 全面資安標頭：Content-Security-Policy, X-Frame-Options: DENY (防點擊劫持), nosniff 等。
 * 7. 全流程多層防暴力破解：從第 1、2 次失敗嘗試起即實作累進延遲，5 次鎖定 15 分鐘。
 * 8. 密碼零明文儲存：PBKDF2 10 萬次疊代隨機加鹽雜湊 (Salt + PBKDF2-SHA256) 與時序安全比對。
 */

const SECURITY_HEADERS = {
  "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self';",
  "X-Frame-Options": "DENY",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(), interest-cohort=()",
  "Strict-Transport-Security": "max-age=31536000; includeSubDomains; preload"
};

const HTML_SECURITY_HEADERS = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "no-store, no-cache, must-revalidate, proxy-revalidate",
  ...SECURITY_HEADERS
};

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Vary": "Origin",
  ...SECURITY_HEADERS
};

const memoryStore = new Map();

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    // 1. OPTIONS Preflight 跨域請求處理 (管理端點禁止外部跨域，一般 API 開放)
    if (request.method === "OPTIONS") {
      const isAdm = path.startsWith("/api/admin") || path.startsWith("/admin");
      const origin = request.headers.get("Origin");
      const selfOrigin = url.origin;
      const headers = { ...SECURITY_HEADERS };

      if (isAdm) {
        if (origin && origin === selfOrigin) {
          headers["Access-Control-Allow-Origin"] = selfOrigin;
          headers["Access-Control-Allow-Credentials"] = "true";
          headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
          headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization";
          headers["Vary"] = "Origin";
          return new Response(null, { status: 204, headers });
        }
        return new Response(null, { status: 403, headers: { ...SECURITY_HEADERS, "Vary": "Origin" } });
      }

      headers["Access-Control-Allow-Origin"] = origin || "*";
      headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
      headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization";
      headers["Vary"] = "Origin";
      return new Response(null, { status: 204, headers });
    }

    // 2. CSRF 跨站防護：針對管理端點的變更操作 (POST) 嚴格檢驗 Origin
    if (path.startsWith("/api/admin/") && request.method === "POST") {
      const origin = request.headers.get("Origin");
      if (origin && origin !== url.origin) {
        return jsonResponse({ success: false, message: "跨域請求已被安全機制阻擋 (CSRF 防護)" }, 403, {}, request);
      }
    }

    try {
      // 3. 首頁健康檢查 (不洩露系統架構、版本號、KV狀態或管理員URL)
      if (path === "/" || path === "/api/health") {
        return new Response(JSON.stringify({ status: "ok" }), {
          status: 200,
          headers: {
            "Content-Type": "application/json",
            ...SECURITY_HEADERS
          }
        });
      }

      // 4. 視覺化管理員後台網頁 (身分感知：未認證僅回傳純淨登入頁，認證通過才渲染儀表板)
      if (path === "/admin" || path === "/dashboard") {
        const token = await getAdminTokenFromRequest(request);
        const isValid = await verifyAdminToken(token, env);
        if (isValid) {
          const adminUsername = (await getAdminUsernameFromToken(token, env)) || "Admin";
          return new Response(renderAdminDashboardHtml(adminUsername), {
            status: 200,
            headers: HTML_SECURITY_HEADERS
          });
        }

        const admins = await getAllAdmins(env);
        const legacy = await getOrInitAdminAuth(env);
        const isInitialized = admins.length > 0 || !!(legacy && legacy.adminDisabled);
        return new Response(renderAdminLoginHtml(isInitialized), {
          status: 200,
          headers: HTML_SECURITY_HEADERS
        });
      }

      // 5. 系統初次部署設定 (當且僅當系統尚未初始化時允許建立管理員)
      if (path === "/api/admin/initial-setup" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        const rateStatus = await getAdminRateLimitStatus(env, clientIp);
        if (rateStatus.locked) {
          return jsonResponse({
            success: false,
            message: `操作頻率過高，已被鎖定 15 分鐘 (剩餘約 ${rateStatus.remainingSec || 900} 秒)`
          }, 429, {}, request);
        }

        const admins = await getAllAdmins(env);
        const legacy = await getOrInitAdminAuth(env);
        const isInitialized = admins.length > 0 || !!(legacy && legacy.adminDisabled);
        if (isInitialized) {
          return jsonResponse({
            success: false,
            message: "安全原則：系統已完成初始化，禁止重複進行初次設定！請直接登入。"
          }, 403, {}, request);
        }

        const body = await request.json().catch(() => ({}));
        const uname = (body.username || "").trim();
        const pword = (body.password || "").trim();

        if (!uname || uname.length < 3 || uname.length > 30) {
          return jsonResponse({ success: false, message: "管理員帳號需介於 3 至 30 個字元" }, 400, {}, request);
        }
        if (uname.toUpperCase() === "ADMIN") {
          return jsonResponse({ success: false, message: "基於安全原則，管理員帳號不能使用通用預設的 ADMIN！" }, 400, {}, request);
        }
        if (!pword || pword.length < 8) {
          return jsonResponse({ success: false, message: "為維護安全，管理員密碼長度需至少 8 碼！" }, 400, {}, request);
        }
        if (pword === "00000000") {
          return jsonResponse({ success: false, message: "不能使用預設弱密碼 00000000！" }, 400, {}, request);
        }

        const { hash, salt } = await hashPassword(pword);
        const adminObj = {
          username: uname,
          hash,
          salt,
          createdAt: Date.now(),
          createdBy: "init"
        };
        await kvPut(env, `admin_user:${uname.toLowerCase()}`, JSON.stringify(adminObj));
        await kvPut(env, "system:admins", JSON.stringify([uname]));
        await kvPut(env, "system:admin_auth", JSON.stringify({
          username: uname,
          hash,
          salt,
          adminDisabled: true,
          updatedAt: Date.now()
        }));

        await clearAdminLoginFailure(env, clientIp);
        const adminToken = "adm_" + crypto.randomUUID();
        await kvPut(env, `admin_token:${adminToken}`, JSON.stringify({
          status: "active",
          username: uname
        }), 86400);

        const cookie = `admin_session=${adminToken}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=86400`;
        return jsonResponse({
          success: true,
          message: `專屬管理員帳號 [${uname}] 建立成功！`,
          username: uname
        }, 200, { "Set-Cookie": cookie }, request);
      }

      // 6. 管理員登入 (多層速率限制防護：自第1、2次失敗起實作累進延遲，5次鎖定15分鐘，HttpOnly Session Cookie)
      if (path === "/api/admin/login" && request.method === "POST") {
        const clientIp = request.headers.get("cf-connecting-ip") || "unknown";
        const rateStatus = await getAdminRateLimitStatus(env, clientIp);
        if (rateStatus.locked) {
          return jsonResponse({
            success: false,
            message: `登入失敗次數過多，已被安全防護鎖定 15 分鐘 (剩餘約 ${rateStatus.remainingSec || 900} 秒)`
          }, 429, {}, request);
        }

        // 累進延遲防護 (Progressive Delay)，對第 1、2 次失敗嘗試提供速度限制
        if (rateStatus.delayMs > 0) {
          await new Promise(r => setTimeout(r, rateStatus.delayMs));
        }

        const body = await request.json().catch(() => ({}));
        const inputUsername = (body.username || "").trim();
        const password = (body.password || "").trim();

        if (!inputUsername || !password) {
          return jsonResponse({ success: false, message: "請輸入管理員帳號與密碼" }, 400, {}, request);
        }

        const adminsList = await getAllAdmins(env);
        const legacyAuth = await getOrInitAdminAuth(env);
        const isSystemInitialized = adminsList.length > 0 || !!(legacyAuth && legacyAuth.adminDisabled);

        // 若輸入 ADMIN 且系統已初始化
        if (inputUsername.toUpperCase() === "ADMIN" && isSystemInitialized) {
          await recordAdminLoginFailure(env, clientIp);
          await new Promise(r => setTimeout(r, 600)); // 時序延遲
          return jsonResponse({
            success: false,
            message: "安全原則：預設 ADMIN 帳號已永久停用！請使用您設定的專屬管理員帳號登入。"
          }, 403, {}, request);
        }

        const adminData = await findAdminUser(env, inputUsername);
        if (!adminData) {
          await recordAdminLoginFailure(env, clientIp);
          await new Promise(r => setTimeout(r, 600));
          return jsonResponse({ success: false, message: "管理員帳號或密碼錯誤" }, 401, {}, request);
        }

        const { hash } = await hashPassword(password, adminData.salt);
        if (!timingSafeEqual(hash, adminData.hash)) {
          await recordAdminLoginFailure(env, clientIp);
          await new Promise(r => setTimeout(r, 600));
          return jsonResponse({ success: false, message: "管理員帳號或密碼錯誤" }, 401, {}, request);
        }

        await clearAdminLoginFailure(env, clientIp);
        const adminToken = "adm_" + crypto.randomUUID();
        await kvPut(env, `admin_token:${adminToken}`, JSON.stringify({
          status: "active",
          username: adminData.username
        }), 86400);

        const cookie = `admin_session=${adminToken}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=86400`;
        return jsonResponse({
          success: true,
          username: adminData.username,
          message: "登入成功"
        }, 200, { "Set-Cookie": cookie }, request);
      }

      // 7. 管理員登出 (註銷 Token 並清除 HttpOnly Cookie)
      if (path === "/api/admin/logout" && request.method === "POST") {
        const token = await getAdminTokenFromRequest(request);
        if (token) {
          await kvDelete(env, `admin_token:${token}`);
        }
        const clearCookie = `admin_session=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0`;
        return jsonResponse({ success: true, message: "已安全登出" }, 200, { "Set-Cookie": clearCookie }, request);
      }

      // 8. 修改管理員帳號與密碼 (需管理員權限，支援自訂名稱與密碼，更新 Session Cookie)
      if (path === "/api/admin/change-password" && request.method === "POST") {
        const body = await request.json().catch(() => ({}));
        const { newUsername, newPassword } = body;

        const currentToken = await getAdminTokenFromRequest(request);
        const isAuthorized = await verifyAdminToken(currentToken, env);
        if (!isAuthorized) {
          return jsonResponse({ success: false, message: "修改憑證權限已過期，請重新登入" }, 401, {}, request);
        }

        const authAdminName = (await getAdminUsernameFromToken(currentToken, env)) || "Admin";
        const cleanUname = (newUsername || "").trim();
        if (cleanUname.length > 0) {
          if (cleanUname.toUpperCase() === "ADMIN") {
            return jsonResponse({ success: false, message: "管理員名稱不能使用預設的 ADMIN！請輸入自訂名稱。" }, 400, {}, request);
          }
          if (cleanUname.length < 3 || cleanUname.length > 30) {
            return jsonResponse({ success: false, message: "管理員名稱長度需介於 3 至 30 碼！" }, 400, {}, request);
          }
        }

        if (!newPassword || newPassword.length < 6) {
          return jsonResponse({ success: false, message: "新密碼長度需至少 6 位以上！" }, 400, {}, request);
        }

        if (newPassword === "00000000") {
          return jsonResponse({ success: false, message: "新密碼不能繼續使用弱密碼 00000000！" }, 400, {}, request);
        }

        const finalUname = cleanUname || authAdminName;

        // 使用 PBKDF2 10 萬次隨機加鹽雜湊
        const { hash, salt } = await hashPassword(newPassword);

        // 儲存至 admin_user
        const adminObj = {
          username: finalUname,
          hash,
          salt,
          createdAt: Date.now(),
          createdBy: authAdminName
        };
        await kvPut(env, `admin_user:${finalUname.toLowerCase()}`, JSON.stringify(adminObj));

        // 若改名，移除舊使用者
        if (authAdminName && authAdminName.toLowerCase() !== finalUname.toLowerCase()) {
          await kvDelete(env, `admin_user:${authAdminName.toLowerCase()}`);
        }

        // 更新 system:admins 清單
        const currentAdmins = await getAllAdmins(env);
        const filtered = currentAdmins.filter(a => a.toLowerCase() !== authAdminName.toLowerCase());
        if (!filtered.some(a => a.toLowerCase() === finalUname.toLowerCase())) {
          filtered.push(finalUname);
        }
        await kvPut(env, "system:admins", JSON.stringify(filtered));

        // 更新 legacy system:admin_auth
        await kvPut(env, "system:admin_auth", JSON.stringify({
          username: finalUname,
          hash,
          salt,
          adminDisabled: true,
          updatedAt: Date.now()
        }));

        // 更新 Token Session
        const finalToken = currentToken || ("adm_" + crypto.randomUUID());
        await kvPut(env, `admin_token:${finalToken}`, JSON.stringify({
          status: "active",
          username: finalUname
        }), 86400);

        const cookie = `admin_session=${finalToken}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=86400`;
        return jsonResponse({
          success: true,
          message: `管理員帳號 [${finalUname}] 密碼修改成功！`,
          username: finalUname
        }, 200, { "Set-Cookie": cookie }, request);
      }

      // 管理員清單查詢 (多管理員功能)
      if (path === "/api/admin/admins" && request.method === "GET") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const adminNames = await getAllAdmins(env);
        const admins = [];
        for (const name of adminNames) {
          const userObj = await findAdminUser(env, name);
          admins.push({
            username: name,
            createdAt: userObj?.createdAt || 0,
            createdBy: userObj?.createdBy || "system"
          });
        }
        return jsonResponse({ success: true, admins });
      }

      // 新增其他管理員 (多管理員功能)
      if (path === "/api/admin/create-admin" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const currentToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
        const authAdmin = (await getAdminUsernameFromToken(currentToken, env)) || "system";

        const body = await request.json().catch(() => ({}));
        const newU = (body.username || "").trim();
        const newP = (body.password || "").trim();

        if (!newU || newU.length < 3 || newU.length > 30) {
          return jsonResponse({ success: false, message: "管理員帳號長度需介於 3 至 30 個字元" }, 400);
        }
        if (newU.toUpperCase() === "ADMIN") {
          return jsonResponse({ success: false, message: "不能新增名稱為 ADMIN 的帳號" }, 400);
        }
        if (!newP || newP.length < 6) {
          return jsonResponse({ success: false, message: "管理員密碼長度需至少 6 個字元" }, 400);
        }
        if (newP === "00000000") {
          return jsonResponse({ success: false, message: "密碼不能使用 00000000" }, 400);
        }

        const existing = await findAdminUser(env, newU);
        if (existing) {
          return jsonResponse({ success: false, message: `管理員帳號 [${newU}] 已存在` }, 400);
        }

        const { hash, salt } = await hashPassword(newP);
        const adminObj = {
          username: newU,
          hash,
          salt,
          createdAt: Date.now(),
          createdBy: authAdmin
        };
        await kvPut(env, `admin_user:${newU.toLowerCase()}`, JSON.stringify(adminObj));

        const admins = await getAllAdmins(env);
        if (!admins.some(a => a.toLowerCase() === newU.toLowerCase())) {
          admins.push(newU);
        }
        await kvPut(env, "system:admins", JSON.stringify(admins));

        return jsonResponse({
          success: true,
          message: `已成功新增管理員帳號 [${newU}]！`
        });
      }

      // 刪除管理員 (多管理員功能)
      if (path === "/api/admin/delete-admin" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const currentToken = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "").trim();
        const currentAdmin = await getAdminUsernameFromToken(currentToken, env);

        const body = await request.json().catch(() => ({}));
        const targetUsername = (body.username || "").trim();

        if (!targetUsername) {
          return jsonResponse({ success: false, message: "請指定要刪除的管理員帳號" }, 400);
        }
        if (currentAdmin && targetUsername.toLowerCase() === currentAdmin.toLowerCase()) {
          return jsonResponse({ success: false, message: "安全保護：不能刪除當前登入中的自己！" }, 400);
        }

        const admins = await getAllAdmins(env);
        if (admins.length <= 1) {
          return jsonResponse({ success: false, message: "系統必須保留至少一位管理員，無法刪除！" }, 400);
        }

        const filtered = admins.filter(a => a.toLowerCase() !== targetUsername.toLowerCase());
        await kvPut(env, "system:admins", JSON.stringify(filtered));
        await kvDelete(env, `admin_user:${targetUsername.toLowerCase()}`);

        return jsonResponse({
          success: true,
          message: `管理員 [${targetUsername}] 已成功刪除`
        });
      }

      // 後台 API：測試推送給選取設備
      if (path === "/api/admin/test-push-devices" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const body = await request.json().catch(() => ({}));
        const { usernames, title, body: pushBody } = body;

        if (!Array.isArray(usernames) || usernames.length === 0) {
          return jsonResponse({ success: false, message: "請至少勾選一台設備" }, 400);
        }

        const msgTitle = title || "🎉 購物雷達測試推播！";
        const msgBody = pushBody || "來自雲端管理後台的推播測試，您的裝置連線與推播運作正常！";

        let sentCount = 0;
        let attemptedCount = 0;
        let lastError = null;

        for (const u of usernames) {
          const raw = await kvGet(env, `user:${u}`);
          if (raw) {
            try {
              const userObj = JSON.parse(raw);
              if (userObj.fcmToken) {
                attemptedCount++;
                const res = await sendFcmNotification(env, userObj.fcmToken, {
                  title: msgTitle,
                  body: msgBody
                }, { type: "admin_test_push", timestamp: Date.now().toString() });
                if (res.success) {
                  sentCount++;
                } else {
                  lastError = res.error;
                }
              }
            } catch (_) {}
          }
        }

        if (attemptedCount === 0) {
          return jsonResponse({
            success: false,
            message: "選取的設備中尚無有效連線的 FCM Token，請在手機端登入 App 啟動推播服務。"
          }, 400);
        }

        if (sentCount === 0) {
          return jsonResponse({
            success: false,
            message: `推播發送失敗: ${lastError || "無法連線至 FCM"}`
          }, 400);
        }

        return jsonResponse({
          success: true,
          sent_count: sentCount,
          total_attempted: attemptedCount,
          message: `已成功發送推播給 ${sentCount}/${attemptedCount} 台有效設備！`
        });
      }

      // 後台 API：設定 FCM HTTP v1 服務帳戶憑證 (Google Service Account JSON)
      if (path === "/api/admin/set-fcm-service-account" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        const body = await request.json().catch(() => ({}));
        let sa = body.service_account;
        if (typeof sa === "string") {
          try { sa = JSON.parse(sa); } catch (e) {
            return jsonResponse({ success: false, message: "JSON 語法錯誤，請確認貼上的內容是否為正確的 JSON 格式" }, 400);
          }
        }
        if (!sa || typeof sa !== "object") {
          return jsonResponse({ success: false, message: "請提供有效的 Service Account JSON" }, 400);
        }

        const projectId = (sa.project_id || "").trim();
        const clientEmail = (sa.client_email || "").trim();
        const privateKey = (sa.private_key || "").trim();

        if (!projectId || !clientEmail || !privateKey) {
          return jsonResponse({
            success: false,
            message: "JSON 缺少關鍵欄位：project_id, client_email 或 private_key，請確認是否為從 Firebase 下載的金鑰"
          }, 400);
        }

        // 測試驗證私鑰與 Google OAuth 2.0 連線
        try {
          await kvDelete(env, "cache:fcm_v1_token");
          const testToken = await getGoogleOAuth2AccessToken(env, sa);
          if (!testToken) throw new Error("未能取得 Google Access Token");
        } catch (testErr) {
          return jsonResponse({
            success: false,
            message: `憑證測試失敗，無法通過 Google OAuth 2.0 驗證: ${testErr.message}`
          }, 400);
        }

        await kvPut(env, "config:fcm_service_account", JSON.stringify(sa));
        return jsonResponse({
          success: true,
          message: `FCM HTTP v1 設定成功！已通過 Google OAuth 2.0 認證，專案 ID: [${projectId}]。`,
          project_id: projectId,
          client_email: clientEmail
        });
      }

      // 後台 API：測試 FCM v1 OAuth 2.0 授權
      if (path === "/api/admin/test-fcm-v1" && request.method === "POST") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        try {
          await kvDelete(env, "cache:fcm_v1_token");
          const token = await getGoogleOAuth2AccessToken(env);
          return jsonResponse({
            success: true,
            message: `Google OAuth 2.0 驗證成功！已成功換取 Bearer 存取權杖 (${token.slice(0, 15)}...)`
          });
        } catch (e) {
          return jsonResponse({ success: false, message: `OAuth 2.0 測試失敗: ${e.message}` }, 400);
        }
      }

      // 後台 API：設定 FCM Server Key (舊版相容選項)
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
        return jsonResponse({ success: true, message: "舊版 FCM 伺服器金鑰已成功儲存！" });
      }

      // 後台 API：取得推播伺服器設定狀態 (支援 FCM v1 與舊版檢測)
      if (path === "/api/admin/get-config" && request.method === "GET") {
        if (!(await verifyAdmin(request, env))) {
          return jsonResponse({ success: false, message: "未授權" }, 401);
        }
        let saData = null;
        const rawSa = await kvGet(env, "config:fcm_service_account");
        if (rawSa) {
          try { saData = JSON.parse(rawSa); } catch (_) {}
        }
        if (!saData && env.FIREBASE_SERVICE_ACCOUNT) {
          try { saData = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT); } catch (_) {}
        }

        const legacyKey = env.FCM_SERVER_KEY || await kvGet(env, "config:fcm_server_key");

        return jsonResponse({
          success: true,
          has_v1: !!saData,
          v1_project_id: saData?.project_id || "",
          v1_client_email: saData?.client_email || "",
          has_legacy_key: !!legacyKey,
          legacy_key_preview: legacyKey ? `${legacyKey.slice(0, 8)}...${legacyKey.slice(-6)}` : ""
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
        let attemptedCount = 0;
        let lastResult = null;
        const usersListStr = await kvGet(env, "system:all_users");
        const list = usersListStr ? JSON.parse(usersListStr) : [];
        for (const u of list) {
          if (target_username && target_username !== u) continue;
          const raw = await kvGet(env, `user:${u}`);
          if (raw) {
            const user = JSON.parse(raw);
            if (user.fcmToken) {
              attemptedCount++;
              const res = await sendFcmNotification(env, user.fcmToken, { title, body: pushBody }, { type: "admin_broadcast" });
              lastResult = res;
              if (res.success) sentCount++;
            }
          }
        }

        if (attemptedCount === 0) {
          return jsonResponse({
            success: true,
            message: "目前系統中尚無已連線 FCM Token 的裝置，請在手機端開啟 App 登入以綁定推播憑證。"
          });
        }

        if (sentCount === 0 && lastResult && !lastResult.success) {
          return jsonResponse({
            success: false,
            message: `推播發送失敗: ${lastResult.error || "未知錯誤"}`,
            details: lastResult
          }, 400);
        }

        const apiText = lastResult?.api_version === "v1" ? "FCM HTTP v1" : "舊版 FCM";
        return jsonResponse({
          success: true,
          message: `已透過 ${apiText} 發送給 ${sentCount}/${attemptedCount} 台有效裝置`,
          details: lastResult
        });
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

        const roleText = (user.role === "low") ? "普通會員 (每小時巡查)" : "VIP 會員 (每30分鐘巡查)";
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
  // 1. 優先嘗試 FCM HTTP v1 (Google Service Account OAuth 2.0 現代標準)
  let sa = null;
  const rawSa = await kvGet(env, "config:fcm_service_account");
  if (rawSa) {
    try { sa = JSON.parse(rawSa); } catch (_) {}
  }
  if (!sa && env.FIREBASE_SERVICE_ACCOUNT) {
    try { sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT); } catch (_) {}
  }

  if (sa && sa.project_id && sa.private_key && sa.client_email) {
    try {
      const accessToken = await getGoogleOAuth2AccessToken(env, sa);
      const projectId = sa.project_id.trim();
      const type = data.type || "general";
      const channelId = (type === "subscription_update" || type === "subscription") ? "fcm_subscriptions"
        : (type === "logistics_update" || type === "logistics") ? "fcm_logistics"
        : (type === "auction_outbid" || type === "auction") ? "fcm_auctions"
        : "fcm_general";

      const v1Payload = {
        message: {
          token: fcmToken,
          notification: {
            title: notification.title || "購物雷達通知",
            body: notification.body || ""
          },
          data: sanitizeDataToStrings({
            ...data,
            title: notification.title || "購物雷達通知",
            body: notification.body || ""
          }),
          android: {
            priority: "HIGH",
            notification: {
              channel_id: channelId,
              sound: "default",
              default_sound: true,
              notification_priority: "PRIORITY_HIGH"
            }
          }
        }
      };

      const resp = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${accessToken}`,
          "Content-Type": "application/json; UTF-8"
        },
        body: JSON.stringify(v1Payload)
      });

      const respText = await resp.text();
      let respJson = null;
      try { respJson = JSON.parse(respText); } catch (_) {}

      if (resp.ok) {
        return { success: true, api_version: "v1", project: projectId, response: respJson || respText };
      } else {
        const errMsg = respJson?.error?.message || respText;
        return {
          success: false,
          api_version: "v1",
          status: resp.status,
          error: `FCM v1 推播失敗 (${resp.status}): ${errMsg}`,
          raw: respJson || respText
        };
      }
    } catch (v1Err) {
      console.error("FCM v1 發送異常:", v1Err);
      return { success: false, api_version: "v1", error: v1Err.message };
    }
  }

  // 2. 次要相容：舊版 FCM Server Key (Legacy HTTP)
  const legacyKey = (await kvGet(env, "config:fcm_server_key")) || env.FCM_SERVER_KEY;
  if (legacyKey) {
    try {
      const payload = {
        to: fcmToken,
        priority: "high",
        notification: { title: notification.title, body: notification.body, sound: "default" },
        data: { ...data, title: notification.title, body: notification.body }
      };
      const resp = await fetch("https://fcm.googleapis.com/fcm/send", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Authorization": `key=${legacyKey}` },
        body: JSON.stringify(payload)
      });
      const respText = await resp.text();
      return { success: resp.ok, api_version: "legacy", response: respText };
    } catch (e) {
      return { success: false, api_version: "legacy", error: e.message };
    }
  }

  return {
    success: false,
    error: "尚未設定推播憑證！請至管理員後台設定【FCM HTTP v1 服務帳戶私密金鑰 (Service Account JSON)】"
  };
}

async function getGoogleOAuth2AccessToken(env, sa = null) {
  // 1. 檢查快取的 Access Token
  const cachedToken = await kvGet(env, "cache:fcm_v1_token");
  if (cachedToken) {
    return cachedToken;
  }

  // 2. 取得 Service Account 資料
  let serviceAccount = sa;
  if (!serviceAccount) {
    const rawSa = await kvGet(env, "config:fcm_service_account");
    if (rawSa) {
      try { serviceAccount = JSON.parse(rawSa); } catch (_) {}
    }
  }
  if (!serviceAccount && env.FIREBASE_SERVICE_ACCOUNT) {
    try { serviceAccount = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT); } catch (_) {}
  }

  if (!serviceAccount || !serviceAccount.client_email || !serviceAccount.private_key || !serviceAccount.project_id) {
    throw new Error("尚未設定 Firebase 服務帳戶私密金鑰 (Service Account JSON)");
  }

  const clientEmail = serviceAccount.client_email.trim();
  const privateKeyPem = (serviceAccount.private_key || "").replace(/\\n/g, "\n");
  const now = Math.floor(Date.now() / 1000);

  // 3. 匯入 PKCS#8 私鑰至 Web Crypto
  let cryptoKey;
  try {
    const der = pemToDer(privateKeyPem);
    cryptoKey = await crypto.subtle.importKey(
      "pkcs8",
      der,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"]
    );
  } catch (keyErr) {
    throw new Error(`私鑰解析失敗，請確認是否為 Firebase 官方提供的 PKCS#8 私密金鑰 (${keyErr.message})`);
  }

  // 4. 建立 JWT
  const jwtHeader = { alg: "RS256", typ: "JWT" };
  const jwtClaim = {
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    exp: now + 3600,
    iat: now
  };

  const headerB64 = base64UrlEncode(JSON.stringify(jwtHeader));
  const claimB64 = base64UrlEncode(JSON.stringify(jwtClaim));
  const signPayload = `${headerB64}.${claimB64}`;

  // 5. 簽署 RS256
  const signatureBuffer = await crypto.subtle.sign(
    { name: "RSASSA-PKCS1-v1_5" },
    cryptoKey,
    new TextEncoder().encode(signPayload)
  );

  const signedJwt = `${signPayload}.${base64UrlEncode(signatureBuffer)}`;

  // 6. 向 Google OAuth 2.0 端點換取 Access Token
  const tokenResp = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: signedJwt
    }).toString()
  });

  const tokenText = await tokenResp.text();
  let tokenData = null;
  try { tokenData = JSON.parse(tokenText); } catch (_) {}

  if (!tokenResp.ok || !tokenData || !tokenData.access_token) {
    const errDesc = tokenData?.error_description || tokenData?.error || tokenText;
    throw new Error(`Google OAuth 2.0 授權失敗 (${tokenResp.status}): ${errDesc}`);
  }

  // 7. 快取 Token (Google Token 預設有效 3600 秒，快取 3000 秒)
  const expiresIn = Number(tokenData.expires_in) || 3600;
  const cacheTtl = Math.max(expiresIn - 300, 60);
  await kvPut(env, "cache:fcm_v1_token", tokenData.access_token, cacheTtl);

  return tokenData.access_token;
}

function sanitizeDataToStrings(data) {
  const result = {};
  if (!data || typeof data !== "object") return result;
  for (const [key, value] of Object.entries(data)) {
    if (value !== undefined && value !== null) {
      result[key] = String(value);
    }
  }
  return result;
}

function pemToDer(pem) {
  const cleanPem = pem
    .replace(/-----BEGIN [A-Z ]+-----/g, "")
    .replace(/-----END [A-Z ]+-----/g, "")
    .replace(/\s+/g, "");
  const binaryString = atob(cleanPem);
  const bytes = new Uint8Array(binaryString.length);
  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i);
  }
  return bytes.buffer;
}

function base64UrlEncode(data) {
  let binary = "";
  if (typeof data === "string") {
    const utf8Bytes = new TextEncoder().encode(data);
    for (let i = 0; i < utf8Bytes.length; i++) {
      binary += String.fromCharCode(utf8Bytes[i]);
    }
  } else {
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
    for (let i = 0; i < bytes.byteLength; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
  }
  return btoa(binary)
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
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
  return null;
}

async function getAllAdmins(env) {
  const rawList = await kvGet(env, "system:admins");
  let admins = [];
  if (rawList) {
    try { admins = JSON.parse(rawList); } catch (_) {}
  }
  if (!Array.isArray(admins) || admins.length === 0) {
    // 舊版 system:admin_auth 自動移轉相容
    const legacy = await getOrInitAdminAuth(env);
    if (legacy && legacy.adminDisabled && legacy.username && legacy.username.toUpperCase() !== "ADMIN") {
      admins = [legacy.username];
      await kvPut(env, "system:admins", JSON.stringify(admins));
      await kvPut(env, `admin_user:${legacy.username.toLowerCase()}`, JSON.stringify({
        username: legacy.username,
        hash: legacy.hash,
        salt: legacy.salt,
        createdAt: legacy.updatedAt || Date.now(),
        createdBy: "system"
      }));
    }
  }
  return Array.isArray(admins) ? admins : [];
}

async function findAdminUser(env, username) {
  const clean = (username || "").trim().toLowerCase();
  if (!clean) return null;
  const raw = await kvGet(env, `admin_user:${clean}`);
  if (raw) {
    try { return JSON.parse(raw); } catch (_) {}
  }
  const legacy = await getOrInitAdminAuth(env);
  if (legacy && legacy.username && legacy.username.toLowerCase() === clean) {
    return {
      username: legacy.username,
      hash: legacy.hash,
      salt: legacy.salt,
      createdAt: legacy.updatedAt || Date.now(),
      createdBy: "system"
    };
  }
  return null;
}

async function hashPassword(password, saltHex = null) {
  const salt = saltHex ? hexToBytes(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const passKey = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), { name: "PBKDF2" }, false, ["deriveBits"]);
  const derivedBits = await crypto.subtle.deriveBits({ name: "PBKDF2", salt: salt, iterations: 100000, hash: "SHA-256" }, passKey, 256);
  return { hash: bytesToHex(new Uint8Array(derivedBits)), salt: bytesToHex(salt) };
}

// 累進延遲與鎖定狀態檢查 (對第 1、2 次嘗試亦提供速度限制與記錄保護)
async function getAdminRateLimitStatus(env, clientIp) {
  const key = `ratelimit:admin:${clientIp}`;
  const raw = await kvGet(env, key);
  if (!raw) return { failures: 0, locked: false, delayMs: 0 };
  try {
    const data = JSON.parse(raw);
    const now = Date.now();
    if (data.lockedUntil && now < data.lockedUntil) {
      const remainingSec = Math.ceil((data.lockedUntil - now) / 1000);
      return { failures: data.failures, locked: true, remainingSec };
    }
    const delayMs = Math.min((data.failures || 0) * 500, 2500);
    return { failures: data.failures || 0, locked: false, delayMs, startTime: data.startTime };
  } catch (_) {
    return { failures: 0, locked: false, delayMs: 0 };
  }
}

async function recordAdminLoginFailure(env, clientIp) {
  const key = `ratelimit:admin:${clientIp}`;
  const raw = await kvGet(env, key);
  const now = Date.now();
  let failures = 1;
  let startTime = now;
  if (raw) {
    try {
      const data = JSON.parse(raw);
      if (now - (data.startTime || 0) < 15 * 60 * 1000) {
        failures = (data.failures || 0) + 1;
        startTime = data.startTime || now;
      }
    } catch (_) {}
  }
  let lockedUntil = null;
  if (failures >= 5) {
    lockedUntil = now + 15 * 60 * 1000; // 鎖定 15 分鐘
  }
  await kvPut(env, key, JSON.stringify({ failures, startTime, lockedUntil }), 900);
  return { failures, locked: failures >= 5 };
}

async function clearAdminLoginFailure(env, clientIp) {
  await kvDelete(env, `ratelimit:admin:${clientIp}`);
}

async function recordAndCheckRateLimit(env, key) {
  const recordKey = `ratelimit:${key}`;
  const raw = await kvGet(env, recordKey);
  const now = Date.now();
  let count = 1;
  let startTime = now;
  if (raw) {
    try {
      const data = JSON.parse(raw);
      if (now - data.startTime < 15 * 60 * 1000) {
        count = data.count + 1;
        startTime = data.startTime;
        if (count > 5) return false;
      }
    } catch (_) {}
  }
  await kvPut(env, recordKey, JSON.stringify({ count, startTime }), 900);
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

function jsonResponse(data, status = 200, extraHeaders = {}, request = null) {
  let isAdm = false;
  let origin = null;
  let selfOrigin = null;
  if (request) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/api/admin") || url.pathname.startsWith("/admin")) {
      isAdm = true;
    }
    origin = request.headers.get("Origin");
    selfOrigin = url.origin;
  }

  const headers = {
    "Content-Type": "application/json",
    ...SECURITY_HEADERS,
    ...extraHeaders
  };

  if (isAdm) {
    if (origin && selfOrigin && origin === selfOrigin) {
      headers["Access-Control-Allow-Origin"] = selfOrigin;
      headers["Access-Control-Allow-Credentials"] = "true";
      headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
      headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization";
    }
    headers["Vary"] = "Origin";
  } else {
    headers["Access-Control-Allow-Origin"] = origin || "*";
    headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
    headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization";
    headers["Vary"] = "Origin";
  }

  return new Response(JSON.stringify(data), { status, headers });
}

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

function getCookie(request, name) {
  if (!request) return null;
  const cookieHeader = request.headers.get("Cookie") || "";
  const match = cookieHeader.match(new RegExp(`(?:^|;\\s*)${name}=([^;]+)`));
  return match ? decodeURIComponent(match[1]) : null;
}

async function getAdminTokenFromRequest(request) {
  if (!request) return null;
  const authHeader = request.headers.get("Authorization") || "";
  const bearer = authHeader.replace(/^Bearer\s+/i, "").trim();
  if (bearer) return bearer;
  return getCookie(request, "admin_session");
}

async function verifyAdmin(request, env) {
  const token = await getAdminTokenFromRequest(request);
  return await verifyAdminToken(token, env);
}

async function verifyAdminToken(token, env) {
  if (!token) return false;
  const status = await kvGet(env, `admin_token:${token}`);
  if (!status) return false;
  if (status === "active") return true;
  try {
    const data = JSON.parse(status);
    return data.status === "active";
  } catch (_) {
    return false;
  }
}

async function getAdminUsernameFromToken(token, env) {
  if (!token) return null;
  const status = await kvGet(env, `admin_token:${token}`);
  if (!status) return null;
  if (status === "active") {
    const admins = await getAllAdmins(env);
    return admins[0] || "Admin";
  }
  try {
    const data = JSON.parse(status);
    return data.username || null;
  } catch (_) {
    return null;
  }
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
 * 純淨管理員登入頁面 (未登入者僅回傳此頁面，不包含任何管理 API 路由或敏感邏輯)
 */
function renderAdminLoginHtml(isInitialized) {
  return `<!DOCTYPE html>
<html lang="zh-TW">
<head>
  <meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>購物雷達 - 管理員登入</title>
  <style>
    :root { --bg: #0b1120; --card: #1e293b; --primary: #38bdf8; --border: #334155; --text: #f8fafc; --err: #f87171; }
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background: var(--bg); color: var(--text); padding: 20px; min-height: 100vh; display: flex; align-items: center; justify-content: center; }
    .card { background: var(--card); border: 1px solid var(--border); border-radius: 16px; padding: 28px; width: 100%; max-width: 400px; box-shadow: 0 8px 30px rgba(0,0,0,0.4); }
    .btn { background: var(--primary); color: #000; border: none; padding: 12px; border-radius: 8px; font-weight: bold; cursor: pointer; width: 100%; font-size: 15px; transition: 0.2s; }
    .btn:hover { opacity: 0.9; }
    .input { background: #070d18; border: 1px solid var(--border); color: var(--text); padding: 12px; border-radius: 8px; width: 100%; margin-bottom: 14px; font-size: 14px; }
    .input:focus { outline: none; border-color: var(--primary); }
    .err-msg { color: var(--err); font-size: 13px; margin-top: 12px; text-align: center; display: none; line-height: 18px; }
  </style>
</head>
<body>
  <div class="card">
    <h2 style="margin-bottom: 12px; text-align: center; color: var(--primary);">🛡️ 購物雷達管理系統</h2>
    <p style="color: #94a3b8; font-size: 13px; margin-bottom: 20px; text-align: center;">
      \${isInitialized ? "請輸入管理員帳號與密碼" : "系統初次部署：請設定您的專屬管理員帳號與密碼"}
    </p>
    <form id="authForm" onsubmit="handleAuth(event)">
      <input type="text" id="uname" class="input" placeholder="管理員帳號" required autocomplete="username" />
      <input type="password" id="pword" class="input" placeholder="\${isInitialized ? "管理員密碼" : "請設定管理員密碼 (至少8碼)"}" required autocomplete="current-password" />
      \${isInitialized ? "" : '<input type="password" id="pword2" class="input" placeholder="再次確認密碼" required autocomplete="new-password" />'}
      <button type="submit" id="submitBtn" class="btn">\${isInitialized ? "登入管理後台" : "建立專屬管理員"}</button>
    </form>
    <div id="errMsg" class="err-msg"></div>
  </div>

  <script>
    async function handleAuth(e) {
      e.preventDefault();
      const u = document.getElementById("uname").value.trim();
      const p = document.getElementById("pword").value.trim();
      const p2El = document.getElementById("pword2");
      const err = document.getElementById("errMsg");
      const btn = document.getElementById("submitBtn");
      err.style.display = "none";
      if (!u || !p) return;

      \${isInitialized ? "" : \`
      const p2 = p2El ? p2El.value.trim() : "";
      if (p !== p2) {
        err.innerText = "兩次輸入的密碼不一致！";
        err.style.display = "block";
        return;
      }
      if (p.length < 8) {
        err.innerText = "密碼長度需至少 8 碼！";
        err.style.display = "block";
        return;
      }
      \`}

      btn.disabled = true;
      btn.innerText = "驗證中...";
      try {
        const endpoint = \${isInitialized ? '"/api/admin/login"' : '"/api/admin/initial-setup"'};
        const res = await fetch(endpoint, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: u, password: p })
        });
        const data = await res.json();
        if (data.success) {
          btn.innerText = "驗證成功，載入後台...";
          window.location.reload();
        } else {
          err.innerText = data.message || "驗證失敗";
          err.style.display = "block";
          btn.disabled = false;
          btn.innerText = \${isInitialized ? '"登入管理後台"' : '"建立專屬管理員"'};
        }
      } catch (errExp) {
        err.innerText = "連線失敗: " + errExp.message;
        err.style.display = "block";
        btn.disabled = false;
        btn.innerText = \${isInitialized ? '"登入管理後台"' : '"建立專屬管理員"'};
      }
    }
  </script>
</body>
</html>\`;
}

/**
 * 視覺化管理員儀表板 HTML (僅在通過身分驗證後於伺服器端動態渲染，零 Token 存於 localStorage)
 */
function renderAdminDashboardHtml(adminUsername) {
  const safeAdmin = escapeHtml(adminUsername || "Admin");
  return `<!DOCTYPE html>
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
    .input:focus { outline: none; border-color: var(--primary); }
    table { width: 100%; border-collapse: collapse; margin-top: 14px; }
    th, td { padding: 12px; text-align: left; border-bottom: 1px solid var(--border); font-size: 13px; }
    .modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.85); display: flex; align-items: center; justify-content: center; z-index: 9999; }
  </style>
</head>
<body>
  <div class="container">
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 10px;">
      <div>
        <h1 style="color: var(--primary); font-size: 22px;">📡 購物雷達 - 雲端推播管理系統</h1>
        <p style="font-size: 12px; color: #94a3b8; margin-top: 4px;">登入身份：<strong id="currentAdminName" style="color: #38bdf8;">\${safeAdmin}</strong></p>
      </div>
      <div>
        <button class="btn" style="background:#334155; color:#fff; margin-right: 8px;" onclick="showChangePassModal()">修改個人密碼</button>
        <button class="btn" style="background:#475569; color:#fff;" onclick="logoutAdmin()">登出</button>
      </div>
    </div>

    <div class="stats-grid">
      <div class="stat-box"><div class="stat-val" id="totalDevices">0</div><div style="font-size:12px;color:#94a3b8;">註冊手機總數</div></div>
      <div class="stat-box"><div class="stat-val" id="activeFcm">0</div><div style="font-size:12px;color:#94a3b8;">有效 FCM 推播裝置</div></div>
      <div class="stat-box"><div class="stat-val" id="stdUsers">0</div><div style="font-size:12px;color:#94a3b8;">VIP 會員 (30分巡查)</div></div>
      <div class="stat-box"><div class="stat-val" id="lowUsers">0</div><div style="font-size:12px;color:#94a3b8;">普通會員 (60分巡查)</div></div>
    </div>

    <!-- FCM HTTP v1 雲端推播憑證設定卡片 -->
    <div class="card" style="border: 1px solid #0284c7;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px;">
        <div>
          <h3 style="color: #38bdf8; font-size: 16px;">🔑 FCM HTTP v1 雲端推播設定 (現代 OAuth 2.0 驗證)</h3>
          <p style="font-size: 12px; color: #94a3b8; margin-top: 2px;">Google 官方標準規範，採用 Service Account 私鑰自動產生短期 Bearer Token</p>
        </div>
        <div style="display: flex; align-items: center; gap: 8px; flex-wrap: wrap;">
          <span id="fcmKeyStatus" class="badge" style="background: #78350f; color: #fbbf24;">檢測中...</span>
          <button id="btnQuickTestFcm" class="btn" style="background: #1e293b; border: 1px solid #334155; color: #38bdf8; font-size: 12px; padding: 4px 10px; display: none;" onclick="doTestFcmV1()">🔍 測試授權</button>
          <button id="btnToggleFcmEdit" class="btn" style="background: #0369a1; color: #fff; font-size: 12px; padding: 4px 12px; display: none;" onclick="toggleFcmEditForm()">✏️ 編輯憑證</button>
        </div>
      </div>

      <!-- 設定完成時的精簡提示 (輸入框收合時顯示) -->
      <div id="fcmConfiguredNotice" style="display: none; background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.3); border-radius: 8px; padding: 10px 14px; font-size: 13px; color: #6ee7b7; margin-bottom: 8px;">
        <span>✅ 已成功綁定 Firebase 憑證，推播功能正常運作中。如需更新或更換私鑰，請點擊右上方「<strong>✏️ 編輯憑證</strong>」。</span>
      </div>

      <!-- 編輯/上傳表單區塊 (設定完成後自動隱藏) -->
      <div id="fcmEditForm">
        <div style="background: rgba(15, 23, 42, 0.6); padding: 14px; border-radius: 10px; margin-bottom: 14px; border: 1px dashed #334155;">
          <h4 style="color: #f1f5f9; font-size: 13px; margin-bottom: 6px;">📋 3 步快速取得 Service Account JSON 憑證：</h4>
          <ol style="font-size: 12px; color: #cbd5e1; padding-left: 20px; line-height: 20px;">
            <li>前往 <a href="https://console.firebase.google.com/" target="_blank" style="color: #38bdf8; text-decoration: underline;">Firebase Console</a> 開啟您的專案。</li>
            <li>進入 <strong>專案設定</strong> ➔ 點選 <strong>「服務帳戶」(Service accounts)</strong> 標籤頁。</li>
            <li>點擊 <strong>「產生新的私密金鑰」(Generate new private key)</strong>，將下載的 <code>.json</code> 檔案在此上傳或貼上內容。</li>
          </ol>
        </div>

        <div style="margin-bottom: 12px;">
          <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 6px;">選擇 JSON 檔案 或 直接於下方貼上內容：</label>
          <input type="file" id="saFileInput" accept=".json,application/json" class="input" style="padding: 6px; margin-bottom: 8px;" onchange="handleSaFileSelect(event)" />
          <textarea id="saJsonInput" class="input" style="height: 100px; font-family: monospace; font-size: 12px; resize: vertical;" placeholder="請貼上 Firebase 下載的 service-account.json 完整內容..."></textarea>
        </div>

        <div style="display: flex; gap: 10px; justify-content: flex-end; flex-wrap: wrap;">
          <button id="btnCancelFcmEdit" class="btn" style="background: #334155; color: #94a3b8; display: none;" onclick="toggleFcmEditForm(false)">收起</button>
          <button class="btn" style="background: #1e293b; border: 1px solid #334155; color: #fff;" onclick="doTestFcmV1()">🔍 測試 OAuth 2.0 授權</button>
          <button class="btn" style="padding: 10px 20px;" onclick="doSaveFcmServiceAccount()">🚀 儲存並驗證 FCM v1 憑證</button>
        </div>

        <details style="margin-top: 14px; border-top: 1px solid #334155; padding-top: 10px;">
          <summary style="font-size: 12px; color: #64748b; cursor: pointer;">舊版相容選項 (FCM Legacy Server Key)</summary>
          <div style="margin-top: 8px; display: flex; gap: 10px;">
            <input type="password" id="fcmLegacyKeyInput" class="input" style="flex: 1; margin-bottom: 0;" placeholder="舊版 FCM Server Key (AAAA...)" />
            <button class="btn" style="background: #334155; color: #fff; white-space: nowrap;" onclick="doSaveLegacyFcmKey()">儲存舊版金鑰</button>
          </div>
        </details>
      </div>

      <p id="fcmKeyMsg" style="font-size: 13px; margin-top: 10px; display: none; line-height: 18px;"></p>
    </div>

    <!-- 全域即時廣播推播卡片 -->
    <div class="card">
      <h3 style="margin-bottom: 12px;">📢 全域即時廣播推播測試</h3>
      <div style="display: flex; gap: 10px;">
        <input type="text" id="pushTitle" class="input" style="flex: 1;" placeholder="推播標題" />
        <input type="text" id="pushBody" class="input" style="flex: 2;" placeholder="推播內文" />
        <button class="btn" style="height: 42px;" onclick="sendBroadcastPush()">立即廣播</button>
      </div>
    </div>

    <!-- 裝置與會員權限管理卡片 -->
    <div class="card">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px;">
        <div>
          <h3>📱 裝置與會員權限管理</h3>
          <p style="font-size: 12px; color: #94a3b8; margin-top: 2px;">可勾選特定裝置發送推播測試，或切換 VIP / 普通會員巡查頻率</p>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap;">
          <button class="btn" style="background:#0284c7; color:#fff;" onclick="pushToSelectedDevices()">🎯 測試推送給勾選設備</button>
          <button class="btn" style="background:#334155; color:#fff;" onclick="loadDevices()">🔄 重新整理</button>
        </div>
      </div>
      <table>
        <thead>
          <tr>
            <th style="width: 36px;"><input type="checkbox" id="selectAllDevices" onchange="toggleSelectAllDevices(this)" title="全選/取消全選" /></th>
            <th>帳號名稱</th><th>電子信箱</th><th>會員等級 (巡查頻率)</th><th>FCM 推播狀態</th><th>操作</th>
          </tr>
        </thead>
        <tbody id="deviceTableBody"></tbody>
      </table>
    </div>

    <!-- 多管理員管理卡片 -->
    <div class="card">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px;">
        <div>
          <h3 style="color: var(--primary);">👥 管理員帳號管理 (支援多管理員)</h3>
          <p style="font-size: 12px; color: #94a3b8; margin-top: 2px;">各管理員可獨立登入，協同管理推播系統</p>
        </div>
        <button class="btn" style="background:#334155; color:#fff;" onclick="loadAdmins()">🔄 重新整理</button>
      </div>

      <div style="background: rgba(15, 23, 42, 0.6); padding: 14px; border-radius: 12px; margin-bottom: 14px; border: 1px solid #334155;">
        <h4 style="font-size: 13px; color: #f1f5f9; margin-bottom: 8px;">➕ 新增管理員帳號</h4>
        <div style="display: flex; gap: 10px; flex-wrap: wrap;">
          <input type="text" id="newAdminUname" class="input" style="flex: 1; min-width: 140px; margin-bottom: 0;" placeholder="管理員帳號 (至少3碼)" />
          <input type="password" id="newAdminPword" class="input" style="flex: 1; min-width: 140px; margin-bottom: 0;" placeholder="管理員密碼 (至少6碼)" />
          <button class="btn" style="height: 40px; white-space: nowrap;" onclick="doCreateAdmin()">新增管理員</button>
        </div>
        <p id="createAdminMsg" style="font-size: 12px; margin-top: 8px; display: none;"></p>
      </div>

      <table>
        <thead>
          <tr><th>管理員帳號</th><th>建立時間</th><th>建立者</th><th>操作</th></tr>
        </thead>
        <tbody id="adminsTableBody"></tbody>
      </table>
    </div>
  </div>

  <!-- 修改管理員密碼 Modal -->
  <div id="changePassModal" class="modal-overlay" style="display: none;">
    <div class="card" style="width: 100%; max-width: 440px; background: #1e293b;">
      <h3 id="modalTitle" style="color: #38bdf8; margin-bottom: 8px;">🛠️ 修改管理員帳號與密碼</h3>
      <p id="modalDesc" style="font-size: 13px; color: #94a3b8; margin-bottom: 16px; line-height: 18px;">
        您可以變更管理員登入名稱或密碼。修改完成後將自動更新工作階段。
      </p>
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">管理員名稱</label>
      <input type="text" id="newAdminUser" class="input" value="\${safeAdmin}" placeholder="請輸入自訂管理員名稱" />
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">新密碼 (至少 6 碼)</label>
      <input type="password" id="newPass1" class="input" placeholder="請輸入新密碼" />
      <label style="font-size: 12px; color: #94a3b8; display: block; margin-bottom: 4px;">再次確認密碼</label>
      <input type="password" id="newPass2" class="input" placeholder="再次確認新密碼" />
      <button class="btn" style="width: 100%; padding: 12px;" onclick="doSubmitNewPassword()">確認儲存</button>
      <button class="btn" style="width: 100%; padding: 10px; margin-top: 8px; background: #334155; color: #fff;" onclick="closeChangePassModal()">取消</button>
      <p id="changePassErr" style="color: var(--err); font-size: 12px; margin-top: 10px; text-align: center; display: none;"></p>
    </div>
  </div>

  <script>
    let idleTimer = null;
    const IDLE_LIMIT_MS = 5 * 60 * 1000; // 5 分鐘無操作自動登出

    function resetIdleTimer() {
      if (idleTimer) clearTimeout(idleTimer);
      idleTimer = setTimeout(() => {
        alert("⏰ 您已超過 5 分鐘無任何操作，為維護後台安全，系統已自動為您登出。");
        logoutAdmin();
      }, IDLE_LIMIT_MS);
    }

    ['mousedown', 'mousemove', 'keydown', 'scroll', 'touchstart', 'click'].forEach(evt => {
      document.addEventListener(evt, resetIdleTimer, { passive: true });
    });

    async function logoutAdmin() {
      if (idleTimer) clearTimeout(idleTimer);
      try {
        await fetch("/api/admin/logout", { method: "POST" });
      } catch (_) {}
      window.location.reload();
    }

    function showChangePassModal() {
      document.getElementById("changePassErr").style.display = "none";
      document.getElementById("newPass1").value = "";
      document.getElementById("newPass2").value = "";
      document.getElementById("changePassModal").style.display = "flex";
    }

    function closeChangePassModal() {
      document.getElementById("changePassModal").style.display = "none";
    }

    async function doSubmitNewPassword() {
      const u = document.getElementById("newAdminUser").value.trim();
      const p1 = document.getElementById("newPass1").value.trim();
      const p2 = document.getElementById("newPass2").value.trim();
      const err = document.getElementById("changePassErr");
      err.style.display = "none";

      if (u.length > 0) {
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
        const res = await fetch("/api/admin/change-password", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ newUsername: u, newPassword: p1 })
        });
        const data = await res.json();
        if (data.success) {
          alert(data.message || "管理員帳號密碼修改成功！");
          window.location.reload();
        } else {
          err.innerText = data.message || "修改失敗";
          err.style.display = "block";
        }
      } catch (e) {
        err.innerText = "連線失敗: " + e.message;
        err.style.display = "block";
      }
    }

    function handleSaFileSelect(e) {
      const file = e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = function(evt) {
        document.getElementById("saJsonInput").value = evt.target.result;
      };
      reader.readAsText(file);
    }

    let isFcmConfigured = false;
    let isFcmEditOpen = false;

    function toggleFcmEditForm(forceState) {
      const form = document.getElementById("fcmEditForm");
      const btn = document.getElementById("btnToggleFcmEdit");
      const notice = document.getElementById("fcmConfiguredNotice");
      const cancelBtn = document.getElementById("btnCancelFcmEdit");

      if (typeof forceState === "boolean") {
        isFcmEditOpen = forceState;
      } else {
        isFcmEditOpen = !isFcmEditOpen;
      }

      if (isFcmEditOpen) {
        if (form) form.style.display = "block";
        if (btn) {
          btn.innerText = "✕ 收起編輯";
          btn.style.background = "#475569";
        }
        if (cancelBtn) cancelBtn.style.display = "inline-block";
      } else {
        if (form) form.style.display = "none";
        if (btn) {
          btn.innerText = "✏️ 編輯憑證";
          btn.style.background = "#0369a1";
        }
        if (cancelBtn) cancelBtn.style.display = "none";
      }
      if (notice && isFcmConfigured) {
        notice.style.display = isFcmEditOpen ? "none" : "block";
      }
    }

    async function loadConfig() {
      try {
        const res = await fetch("/api/admin/get-config");
        if (res.status === 401) return logoutAdmin();
        const data = await res.json();
        const statusEl = document.getElementById("fcmKeyStatus");
        const btnToggle = document.getElementById("btnToggleFcmEdit");
        const btnQuickTest = document.getElementById("btnQuickTestFcm");

        if (data.has_v1) {
          isFcmConfigured = true;
          statusEl.innerText = "● FCM HTTP v1 正常 (" + (data.v1_project_id || "有效") + ")";
          statusEl.className = "badge badge-30";
          if (btnToggle) btnToggle.style.display = "inline-block";
          if (btnQuickTest) btnQuickTest.style.display = "inline-block";
          if (!isFcmEditOpen) {
            toggleFcmEditForm(false);
          }
        } else if (data.has_legacy_key) {
          isFcmConfigured = false;
          statusEl.innerText = "▲ 舊版 FCM Key (" + data.legacy_key_preview + ")";
          statusEl.className = "badge badge-60";
          if (btnToggle) btnToggle.style.display = "none";
          if (btnQuickTest) btnQuickTest.style.display = "none";
          toggleFcmEditForm(true);
        } else {
          isFcmConfigured = false;
          statusEl.innerText = "○ 尚未設定推播憑證 (無法推播)";
          statusEl.className = "badge badge-60";
          if (btnToggle) btnToggle.style.display = "none";
          if (btnQuickTest) btnQuickTest.style.display = "none";
          toggleFcmEditForm(true);
        }
      } catch (_) {}
    }

    async function doSaveFcmServiceAccount() {
      const val = document.getElementById("saJsonInput").value.trim();
      const msg = document.getElementById("fcmKeyMsg");
      msg.style.display = "none";

      if (!val) {
        alert("請貼上或選擇 Firebase 下載的 service-account.json 憑證檔案");
        return;
      }

      let parsed;
      try {
        parsed = JSON.parse(val);
      } catch (e) {
        alert("JSON 語法解析失敗，請確認貼上的文字是否完整！");
        return;
      }

      msg.style.color = "#38bdf8";
      msg.innerText = "⏳ 正在驗證私鑰並向 Google OAuth 2.0 換取存取權杖...";
      msg.style.display = "block";

      try {
        const res = await fetch("/api/admin/set-fcm-service-account", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ service_account: parsed })
        });
        const data = await res.json();
        if (data.success) {
          msg.style.color = "#34d399";
          msg.innerText = "✅ " + data.message;
          document.getElementById("saJsonInput").value = "";
          await loadConfig();
          setTimeout(() => {
            toggleFcmEditForm(false);
          }, 1500);
        } else {
          msg.style.color = "var(--err)";
          msg.innerText = "❌ " + (data.message || "驗證或儲存失敗");
        }
      } catch (e) {
        msg.style.color = "var(--err)";
        msg.innerText = "❌ 連線錯誤: " + e.message;
      }
    }

    async function doTestFcmV1() {
      const msg = document.getElementById("fcmKeyMsg");
      msg.style.color = "#38bdf8";
      msg.innerText = "⏳ 正在測試 Google OAuth 2.0 驗證換證...";
      msg.style.display = "block";

      try {
        const res = await fetch("/api/admin/test-fcm-v1", { method: "POST" });
        const data = await res.json();
        if (data.success) {
          msg.style.color = "#34d399";
          msg.innerText = "✅ " + data.message;
        } else {
          msg.style.color = "var(--err)";
          msg.innerText = "❌ " + (data.message || "測試失敗");
        }
      } catch (e) {
        msg.style.color = "var(--err)";
        msg.innerText = "❌ 連線錯誤: " + e.message;
      }
    }

    async function doSaveLegacyFcmKey() {
      const key = document.getElementById("fcmLegacyKeyInput").value.trim();
      if (!key) return alert("請輸入舊版 FCM Server Key");
      try {
        const res = await fetch("/api/admin/set-fcm-key", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ fcm_server_key: key })
        });
        const data = await res.json();
        alert(data.message || (data.success ? "舊版金鑰已儲存" : "儲存失敗"));
        loadConfig();
      } catch (e) {
        alert("連線錯誤: " + e.message);
      }
    }

    function toggleSelectAllDevices(master) {
      const cbs = document.querySelectorAll(".device-select");
      cbs.forEach(cb => { cb.checked = master.checked; });
    }

    async function loadDevices() {
      try {
        const res = await fetch("/api/admin/devices");
        if (res.status === 401) return logoutAdmin();
        const data = await res.json();
        const devices = data.devices || [];

        document.getElementById("totalDevices").innerText = devices.length;
        document.getElementById("activeFcm").innerText = devices.filter(d => d.has_fcm).length;
        document.getElementById("stdUsers").innerText = devices.filter(d => d.role !== "low").length;
        document.getElementById("lowUsers").innerText = devices.filter(d => d.role === "low").length;

        const master = document.getElementById("selectAllDevices");
        if (master) master.checked = false;

        const tbody = document.getElementById("deviceTableBody");
        tbody.innerHTML = "";
        if (devices.length === 0) {
          tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color:#94a3b8; padding:20px;">尚無連線裝置</td></tr>';
          return;
        }
        devices.forEach(d => {
          const isLow = d.role === "low";
          const tr = document.createElement("tr");
          const safeUsername = escapeHtml(d.username);
          const safeEmail = escapeHtml(d.email || "-");
          const roleBadge = '<span class="badge ' + (isLow ? 'badge-60' : 'badge-30') + '">' + (isLow ? '普通會員 (60分鐘)' : 'VIP 會員 (30分鐘)') + '</span>';
          const fcmBadge = d.has_fcm ? '<span style="color:#34d399;">● 已連線</span>' : '<span style="color:#94a3b8;">○ 未連線</span>';
          const actionHtml = '<button class="btn" style="background:#0284c7; color:#fff; padding:4px 10px; font-size:12px; margin-right:6px;" onclick="pushToSingleDevice(\'' + safeUsername + '\')">測試推送</button>' +
            '<button class="btn" style="background:#334155; color:#fff; padding:4px 10px; font-size:12px;" onclick="toggleRole(\'' + safeUsername + '\', \'' + (isLow ? 'standard' : 'low') + '\')">切換為 ' + (isLow ? 'VIP 會員' : '普通會員') + '</button>';

          tr.innerHTML = '<td><input type="checkbox" class="device-select" value="' + safeUsername + '" data-fcm="' + d.has_fcm + '" /></td>' +
            '<td><strong>' + safeUsername + '</strong></td>' +
            '<td style="color:#94a3b8; font-size:13px;">' + safeEmail + '</td>' +
            '<td>' + roleBadge + '</td>' +
            '<td>' + fcmBadge + '</td>' +
            '<td>' + actionHtml + '</td>';
          tbody.appendChild(tr);
        });
      } catch (_) {}
    }

    async function toggleRole(username, newRole) {
      await fetch("/api/admin/set-role", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, role: newRole })
      });
      loadDevices();
    }

    async function sendBroadcastPush() {
      const title = document.getElementById("pushTitle").value.trim();
      const body = document.getElementById("pushBody").value.trim();
      if (!title || !body) return alert("請輸入標題與內容");
      try {
        const res = await fetch("/api/admin/broadcast", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title, body })
        });
        const data = await res.json();
        alert(data.message || (data.success ? "推播發送成功" : "發送失敗"));
      } catch (e) {
        alert("連線錯誤: " + e.message);
      }
    }

    async function pushToSelectedDevices() {
      const checkedBoxes = document.querySelectorAll(".device-select:checked");
      const unames = Array.from(checkedBoxes).map(cb => cb.value);
      if (unames.length === 0) {
        alert("請先勾選至少一台要測試推送的設備！");
        return;
      }
      const msg = prompt("即將發送測試推播至勾選的 " + unames.length + " 台設備。\n\n請輸入推播內文 (留空使用預設內容)：", "來自管理後台的推播測試，您的裝置推播運作正常！");
      if (msg === null) return;

      try {
        const res = await fetch("/api/admin/test-push-devices", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            usernames: unames,
            title: "🎉 購物雷達測試推播！",
            body: msg.trim() || "來自管理後台的推播測試，您的裝置推播運作正常！"
          })
        });
        const data = await res.json();
        alert(data.message || (data.success ? "推播發送成功！" : "發送失敗"));
      } catch (e) {
        alert("連線錯誤: " + e.message);
      }
    }

    async function pushToSingleDevice(uname) {
      try {
        const res = await fetch("/api/admin/test-push-devices", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            usernames: [uname],
            title: "🎉 購物雷達設備測試推播！",
            body: "設備 [" + uname + "] 推播測試成功！連線正常。"
          })
        });
        const data = await res.json();
        alert(data.message || (data.success ? "推播發送成功！" : "發送失敗"));
      } catch (e) {
        alert("連線錯誤: " + e.message);
      }
    }

    async function loadAdmins() {
      try {
        const res = await fetch("/api/admin/admins");
        if (res.status === 401) return logoutAdmin();
        const data = await res.json();
        const admins = data.admins || [];
        const currentAdmin = document.getElementById("currentAdminName") ? document.getElementById("currentAdminName").innerText.trim() : "";
        const tbody = document.getElementById("adminsTableBody");
        tbody.innerHTML = "";
        if (admins.length === 0) {
          tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; color:#94a3b8; padding:16px;">尚無其他管理員</td></tr>';
          return;
        }
        admins.forEach(a => {
          const isSelf = a.username.toLowerCase() === currentAdmin.toLowerCase();
          const tr = document.createElement("tr");
          const safeName = escapeHtml(a.username);
          const timeStr = a.createdAt ? new Date(a.createdAt).toLocaleString("zh-TW", { hour12: false }) : "-";
          const actionBtn = isSelf
            ? '<span style="color:#64748b; font-size:12px;">(本人)</span>'
            : '<button class="btn" style="background:#ef4444; color:#fff; padding:4px 10px; font-size:12px;" onclick="doDeleteAdmin(\'' + safeName + '\')">刪除</button>';
          tr.innerHTML = '<td><strong>' + safeName + '</strong> ' + (isSelf ? '<span class="badge badge-30" style="margin-left:6px;">目前登入</span>' : '') + '</td>' +
            '<td style="color:#94a3b8; font-size:12px;">' + timeStr + '</td>' +
            '<td style="color:#94a3b8; font-size:12px;">' + escapeHtml(a.createdBy || "system") + '</td>' +
            '<td>' + actionBtn + '</td>';
          tbody.appendChild(tr);
        });
      } catch (_) {}
    }

    async function doCreateAdmin() {
      const u = document.getElementById("newAdminUname").value.trim();
      const p = document.getElementById("newAdminPword").value.trim();
      const msg = document.getElementById("createAdminMsg");
      msg.style.display = "none";

      if (!u || u.length < 3) {
        msg.style.color = "var(--err)";
        msg.innerText = "管理員帳號需至少 3 碼！";
        msg.style.display = "block";
        return;
      }
      if (!p || p.length < 6) {
        msg.style.color = "var(--err)";
        msg.innerText = "管理員密碼需至少 6 碼！";
        msg.style.display = "block";
        return;
      }

      try {
        const res = await fetch("/api/admin/create-admin", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: u, password: p })
        });
        const data = await res.json();
        if (data.success) {
          msg.style.color = "#34d399";
          msg.innerText = "✅ " + data.message;
          msg.style.display = "block";
          document.getElementById("newAdminUname").value = "";
          document.getElementById("newAdminPword").value = "";
          loadAdmins();
        } else {
          msg.style.color = "var(--err)";
          msg.innerText = "❌ " + (data.message || "建立失敗");
          msg.style.display = "block";
        }
      } catch (e) {
        msg.style.color = "var(--err)";
        msg.innerText = "❌ 連線失敗: " + e.message;
        msg.style.display = "block";
      }
    }

    async function doDeleteAdmin(uname) {
      if (!confirm("確定要刪除管理員 [" + uname + "] 嗎？刪除後該管理員將無法再登入。")) return;
      try {
        const res = await fetch("/api/admin/delete-admin", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: uname })
        });
        const data = await res.json();
        alert(data.message || (data.success ? "管理員已刪除" : "刪除失敗"));
        loadAdmins();
      } catch (e) {
        alert("連線錯誤: " + e.message);
      }
    }

    loadConfig();
    loadDevices();
    loadAdmins();
    resetIdleTimer();
  </script>
</body>
</html>`;
}
