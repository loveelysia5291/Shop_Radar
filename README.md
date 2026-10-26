# 購物雷達 (ShopRadar)

台灣主流拍賣與二手市集多平台聚合搜尋、店家新品訂閱、降價追蹤與即時競標超標提醒的原生 Android 應用程式。基於 Jetpack Compose (Material 3) 打造，提供流暢的瀏覽手勢、現代化液態視覺風格與完整的背景推播機制。

---

## 支援平台

App 支援 7 大主流電商與拍賣平台之公開資訊檢索與追蹤：

- **蝦皮購物 (Shopee)**：支援官方登入狀態整合、商品搜尋、關鍵字排序與店家公開商品頁讀取。
- **露天拍賣 (Ruten)**：支援搜尋端點直連、分類瀏覽、最新排序、商品日期提取與競標追蹤。
- **旋轉拍賣 (Carousell)**：支援即時商品搜尋、分類檢索、分享短網址解析與公開賣家個人頁新品監控。
- **Yahoo 拍賣 (Yahoo Bid)**：支援即時商品搜尋、買家出價紀錄比對、即時競拍倒數與超標推播。
- **Facebook Marketplace**：支援公開商品搜尋、地區設定（以台北 250km 為基準）、價格過濾與公開賣場連結解析。
- **跳蚤本舖 (Bobo Mall)**：支援官方即時分類搜尋、全台門市據點篩選與上架日期提取。
- **7-ELEVEN iOPEN Mall**：支援官方搜尋與商品清單讀取。

---

## 核心功能

### 1. 跨平台多商城搜尋
- **一鍵聚合搜尋**：同時向多個商城發起即時檢索，並以卡片清單流式呈現，省去在各個平台切換比價的繁瑣操作。
- **彈性條件篩選**：支援自訂金額上限與下限、最新排序、價位排序、商城專區以及各平台專屬分類。
- **搜尋歷史與快速搜尋**：自動記錄近期搜尋關鍵字與篩選條件，支援一鍵點擊重搜或單筆/批次清除。

### 2. 店家新品訂閱 (Shop Subscriptions)
- **賣場追蹤**：輸入或分享支援平台的公開店家網址即可快速加入追蹤名單。
- **新品自動比對**：首次加入自動建立庫存基準線，後續刷新自動比對新上架商品，精確標示新進物品並提供快速瀏覽清單。
- **一鍵全覽與清理**：提供店家新品專屬展示頁面，支援單一店家所有新進品項一次檢視與清空已讀標記。

### 3. 單品降價與售出追蹤 (Product Subscriptions)
- **單一商品監控**：貼上單一商品連結即可追蹤該商品的價格變動與供貨庫存狀態。
- **降價與售出通知**：背景檢測到商品降價或庫存變動時即刻發送系統推播通知；點擊通知可直接開啟商品頁面。

### 4. 競拍即時追蹤與超標警示 (Auction Live Tracker)
- **即時競標看板**：追蹤 Yahoo 拍賣與露天拍賣的進行中競標，即時展示目前出價、出價次數、最高出價者與結標倒數時間。
- **超標即刻通報**：在使用者出價領先後若被其他買家超過，前景每 20 秒自動比對，發現被超過時即刻彈出警示視窗並發送高優先級通知，助您即時補標。

### 5. 背景定時刷新與通知系統 (Background Refresh & Notifications)
- **WorkManager 低功耗排程**：支援為個別店家、商品與競拍自訂 15 ～ 10080 分鐘的背景刷新週期，僅在連網狀態下執行，省電且不耗費多餘效能。
- **獨立通知派發機制**：即使在網路不穩或限制環境下，已落盤的待發送提醒會由獨立 Worker 保留並有限重試，確保重要降價與超標提醒不遺漏。
- **睡眠時段（Sleep Mode）**：可自訂每日夜間睡眠時段（如 23:00 ～ 07:00），在該區間內暫停背景刷新與通知喚醒。
- **原生通知渠道**：提供「訂閱更新（`subscription-updates`）」與「競拍超標提醒（`auction-outbid`）」獨立渠道，適配 Android 12 至 15 以及各大 OEM（如小米 HyperOS / MIUI、三星 One UI）之背景限制防護。

### 6. 隱私與敏感內容保護 (Adult & Privacy Filter)
- 內建敏感字詞與成人商品自動偵測，可於選單中進行成人模式上鎖或解鎖，鎖定狀態下自動隱藏該類店家與商品卡片，且不會在系統通知中呈現敏感文字。

### 7. 現代化液態介面 (UI & UX)
- 採用 Jetpack Compose 打造，具備邊緣手勢返回（Predictive Back）、滑動換頁、對話框淡入淡出與卡片選單微動畫。
- 支援「淺色模式」、「深色模式」或「跟隨系統」外觀，全面適配手機與平板多欄寬螢幕佈局。

---

## 技術規格

- **開發語言**：Kotlin 2.0+
- **UI 框架**：Jetpack Compose (Material 3)
- **Application ID / Namespace**：`com.aykkdk.shop`
- **目標 SDK**：Compile SDK 34 / Target SDK 34 / Min SDK 24 (Android 7.0+)
- **背景任務**：AndroidX WorkManager 2.9+
- **圖片快取**：Coil Compose（具備本機磁碟與記憶體過期清理策略）
- **程式碼混淆**：R8（包含資源縮減與反射/Worker 保留規則）

---

## 手動構建說明 (Manual Build)

### 1. 環境需求
- **JDK**：OpenJDK 17 或 21
- **Android SDK**：API Level 34 (Android 14)
- **Build-Tools**：37.0.0
- **Gradle**：8.13 或相容版本

---

### 2. 本地終端機手動構建

在專案根目錄下執行以下指令：

#### A. 構建 Debug 版本 APK
```bash
gradle :app:assembleDebug
```
- 產出檔案路徑：`app/build/outputs/apk/debug/app-debug.apk`

#### B. 執行單元測試
```bash
gradle :app:testDebugUnitTest :app:testReleaseUnitTest
```

#### C. 構建 Release 版本 APK（使用預設 Debug 金鑰測試）
```bash
gradle :app:assembleRelease
```
- 產出檔案路徑：`app/build/outputs/apk/release/app-release.apk`
- 註：未指定正式金鑰時，會自動使用 Debug 簽章供本地安裝驗證。

---

### 3. 本地指定正式簽名構建

若要在本地使用自備的 Keystore 進行正式 Release 簽名，可透過 Gradle 參數傳遞：

```bash
gradle :app:assembleRelease \
  -PreleaseKeystorePath="/path/to/your/release.keystore" \
  -PreleaseKeystorePassword="<儲存庫密碼>" \
  -PreleaseKeyAlias="<金鑰別名>" \
  -PreleaseKeyPassword="<金鑰密碼>"
```

亦可設定環境變數進行構建：
- `RELEASE_KEYSTORE_PATH`：Keystore 檔案路徑
- `RELEASE_KEYSTORE_PASSWORD`：儲存庫密碼
- `RELEASE_KEY_ALIAS`：金鑰別名
- `RELEASE_KEY_PASSWORD`：金鑰密碼

---

## GitHub Actions 自動化構建與簽名

本專案已配置 GitHub Actions 工作流程（`.github/workflows/build.yml`），支援在 GitHub 雲端自動完成 APK 構建與簽名。

### 1. 在 GitHub 設定簽名 Secrets
請至您的 GitHub 倉庫：**Settings** → **Secrets and variables** → **Actions**，新增以下 Repository Secrets：

| Secret 名稱 | 內容說明 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Keystore 檔案（`.jks` 或 `.keystore`）的 Base64 編碼字串 |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore 儲存庫密碼 |
| `RELEASE_KEY_ALIAS` | 金鑰別名 (Key Alias) |
| `RELEASE_KEY_PASSWORD` | 金鑰密碼 (Key Password) |

> **提示：如何將本地 Keystore 轉換為 Base64 字串？**
> - **Windows (PowerShell)**：
>   ```powershell
>   [Convert]::ToBase64String([IO.File]::ReadAllBytes("path\to\my-release-key.jks")) | Set-Clipboard
>   ```
> - **macOS / Linux**：
>   ```bash
>   base64 -w 0 path/to/my-release-key.jks | pbcopy
>   ```

### 2. 觸發構建與取得 APK
- **自動觸發**：當您推送程式碼至 `main` 分支時，GitHub Actions 會自動執行編譯、簽名與驗證。
- **手動觸發**：可至 GitHub 倉庫的 **Actions** 頁籤，選擇 **Build Android APK** 並點擊 **Run workflow**。
- **下載 APK**：構建完成後，可在該次 Workflow 執行的 **Artifacts** 區塊下載已簽名的 `ShopRadar-release` 與 `ShopRadar-debug` 安裝檔。
