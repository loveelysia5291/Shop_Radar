# 購物雷達 (Shop Radar) - Cloudflare Worker 雲端推播架設指南

本專案使用 **Cloudflare Workers** 作為 24 小時全自動背景監控與推播後端伺服器，配合 **Firebase Cloud Messaging (FCM)** 將通知直接送到您的 Android 手機，完全解決 Android 本地後台被系統殺後台、無法按時推播的問題。

---

## 快速架設流程（在瀏覽器 3 分鐘搞定，免裝任何工具）

### 第一步：登入 Cloudflare 並建立 Worker
1. 前往 [Cloudflare 官方網站](https://www.cloudflare.com/zh-tw/) 並登入您的帳號（若無帳號請免費註冊）。
2. 在左側選單點選 **「Workers 和 Pages」 (Workers & Pages)**。
3. 點擊 **「建立應用程式」 (Create application)** -> 選擇 **「建立 Worker」 (Create Worker)**。
4. 名稱可以取名為 `shop-radar-worker`，點擊右下角 **「部署」 (Deploy)**。

---

### 第二步：貼上 Worker 程式碼
1. 部署完成後，點擊畫面右上角的 **「編輯代碼」 (Edit code)**。
2. 將本專案中的 [`cloudflare-worker/src/index.js`](file:///c:/Users/Elysia/Documents/shop_app/new-chat/cloudflare-worker/src/index.js) 檔案內容全部複製，貼入線上編輯器中，覆蓋原本的內容。
3. 點擊右上角 **「部署」 (Save and deploy)**。
4. 此時您的 Worker 就已生效！上方會顯示您的 Worker 網址，格式如：
   `https://shop-radar-worker.<您的子網域>.workers.dev`

---

### 第三步：設定定時排程 (Cron Triggers)
讓 Cloudflare 每 10 分鐘自動替您巡查一次跳蚤本舖與包裹貨況：
1. 回到該 Worker 的管理頁面，點選 **「設定」 (Settings)** 標籤頁。
2. 點選左側 **「觸發程序」 (Triggers)**。
3. 在 **Cron 觸發程序 (Cron Triggers)** 區域點擊 **「新增 Cron 觸發程序」 (Add Cron Trigger)**。
4. 設定 Cron 表達式為 `*/10 * * * *`（每 10 分鐘執行一次），點擊 **「儲存」 (Save)**。

---

### 第四步：綁定 KV 資料表 (持久化儲存)
儲存您的帳號、FCM Token 與追蹤商品：
1. 在 Cloudflare 左側選單點選 **「儲存和資料庫」 (Storage & Databases)** -> **KV**。
2. 點擊 **「建立命名空間」 (Create namespace)**，名稱輸入 `RADAR_KV` 並確認。
3. 回到您的 Worker -> **「設定」 (Settings)** -> **「變數」 (Variables)**。
4. 在 **「KV 命名空間繫結」 (KV Namespace Bindings)** 點擊 **「新增繫結」 (Add binding)**：
   - 變數名稱：輸入 `RADAR_KV`
   - KV 命名空間：選擇剛才建立的 `RADAR_KV`
5. 點擊 **「儲存並部署」 (Save and deploy)**。

---

### 第五步：設定 Firebase (FCM) 金鑰
使 Cloudflare 可以透過 Google FCM 發送通知給您的手機：
1. 前往 [Firebase Console](https://console.firebase.google.com/)。
2. 進入您的專案設定 (Project settings) -> **Cloud Messaging** 標籤頁。
3. 取得 **Server Key (伺服器金鑰)**（或在 服務帳戶 標籤中下載私鑰 JSON）。
4. 在 Worker 的 **「設定」 (Settings)** -> **「變數」 (Variables)** -> **「環境變數」 (Environment Variables)** 點擊 **「新增」 (Add)**：
   - 變數名稱：`FCM_SERVER_KEY`
   - 值：貼入您的 Firebase Server Key
5. 點擊 **「儲存並部署」**。

---

### 第六步：在手機 App 連線
1. 開啟手機上的「購物雷達」App。
2. 點選底部最右側的人頭圖示 **「關於」** 頁面。
3. 點擊 **「登入 / 註冊」**：
   - 伺服器網址：輸入您的 Worker 網址（例如 `https://shop-radar-worker.xxx.workers.dev`）
   - 輸入您想使用的帳號與密碼，點擊「註冊」或「登入」。
4. 登入成功後，點擊 **「一鍵同步至雲端」**，App 會將您關注的跳蚤本舖、商品及包裹單號同步給伺服器。
5. 點擊 **「發送測試推播」**，手機將立刻收到由 Cloudflare 伺服器經由 FCM 推送的測試通知！
