# 躺著逛街 (Sofa Wander)

一款 Android 路線模擬定位 App（無需 root）。支援在地圖上點選建立路線、速度/停留/擬真行為設定、背景執行與資料管理，並可匯入/匯出 GPX/KML。

## 功能特色
- 地圖點選多點建立路線、顯示節點與折線
- 路線管理：儲存、改名、刪除、最近紀錄
- 速度模式：步行/慢跑/駕車，支援 min/max 與隨機速度
- 停留時間、Loop/往返
- 擬真行為：drift / bounce / 平滑化
- 背景執行（Foreground Service）
- 匯入/匯出 GPX / KML
- 收藏常用位置、GPS 事件記錄

## 技術選型
- 語言：Kotlin
- UI：XML + View
- 地圖：MapLibre (OpenStreetMap)
- 資料庫：Room
- 背景服務：Foreground Service

## 環境需求
- Android Studio (建議最新穩定版)
- Android SDK
- JDK 17
- minSdk 26 / targetSdk 34

## 專案結構
- `app/src/main/java/com/sofawander/app/MainActivity.kt`：主要 UI 與地圖操作
- `app/src/main/java/com/sofawander/app/MockLocationService.kt`：Mock Location 前台服務
- `app/src/main/java/com/sofawander/app/data/`：Room DB、Entities、DAOs
- `app/src/main/java/com/sofawander/app/data/RouteFileIO.kt`：GPX/KML 匯入匯出
- `app/src/main/res/layout/`：XML 版面

## 開發與執行
1. 使用 Android Studio 開啟專案
2. 確認 `local.properties` 指向你的 SDK
3. 以 Debug 方式執行

或使用命令列：

```bash
./gradlew assembleDebug
```

## 使用前設定（必要）
需要在手機中開啟「開發者選項」，並將本 App 設為「模擬位置應用程式」。
App 內也提供導引提示。

## 資料與隱私
- App 本身不會上傳個人定位資料
- 詳細內容請參考 `PRIVACY_POLICY.md`

## 文件
- `TASKS.md`：任務與里程碑
- `PLAY_STORE.md`：上架說明素材
- `PRIVACY_POLICY.md` / `privacy_policy.html`：隱私政策
- `support.html`：客服支援頁

## 授權
此專案為內部開發用途，如需授權或商用請聯繫專案擁有者。
