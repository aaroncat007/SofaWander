# 躺著逛街 (Fake GPS) 任務文件

## 專案目標
建立一款 Android 模擬定位 App（無需 root），支援路線規劃、速度/行為模擬、背景執行與路線管理，並可上架 Google Play。

## 技術選型
- 語言：Kotlin
- UI：XML + View（地圖整合穩定）
- 架構：MVVM + Repository
- 地圖：OpenStreetMap + MapLibre
- 資料庫：Room
- 背景執行：Foreground Service + Notification
- 位置模擬：Android Mock Location API
- 匯入匯出：GPX/KML 解析
- 最低版本：minSdk 26 (Android 8.0)

## 核心模組
- `core-engine`：路線演算法、速度/停留/變速、drift/bounce
- `mock-service`：前台服務、Mock Location 發送、背景穩定性
- `data`：Room DB、Route/Favorite/History/Event
- `ui`：Map、Builder、Library、Player、Settings
- `io`：GPX/KML 解析與輸出

## 里程碑與任務

## 進度更新（2026-02-23）
- 修正 MapLibre 11 `Point` 使用方式（改用 `point.latitude/longitude`）
- 修正 `MockLocationService` 中 Room suspend 呼叫（改用 `CoroutineScope(Dispatchers.IO)`）
- 設定 Room `schemaLocation` 並建立 `app/schemas/` 目錄
- `assembleDebug` 已可成功編譯（無 Room schema 警告）

### M1：專案基底與 Mock Location
目標：跑通 Mock Location 與背景服務
- 建立專案結構（MVVM + 分層）
- Mock Location 權限引導與檢測流程
- 前台服務與通知樣式
- 單點座標 Mock 發送器
- 基本設定頁（開發者選項引導）
交付：能在背景穩定推送座標

### M2：地圖與路線建立（點選多點）
目標：建立與管理路線
- MapLibre 地圖顯示
- 點選多點建立路線
- 顯示路線折線與節點
- 儲存路線（Room）
- Route Library（清單、改名、刪除）
交付：可建立/管理路線

### M3：播放控制與速度模式
目標：完整路線播放
- 路線播放流程（開始/暫停/停止）
- 速度模式（步行/慢跑/駕車）
- 路段速度範圍（min/max）
- Loop/往返/閉環
交付：路線可完整播放

### M4：擬真行為
目標：避免機械感
- 隨機速度 + 停留時間
- GPS drift / bounce 模擬
- 位置更新平滑化
交付：自然的移動軌跡

### M5：收藏/歷史/事件
目標：資料管理完整
- Favorites 常用點
- Recent Routes
- 執行歷史記錄
- GPS 事件日誌
交付：完整資料管理

### M6：匯入/匯出與上架準備
目標：上架可用
- GPX/KML 匯入/匯出
- Replay（重播已錄路線）
- 上架合規檢查（隱私政策/用途說明）
交付：Play 上架準備完成

## 資料模型草案
- `Route(id, name, type, points, createdAt)`
- `RoutePoint(lat, lng, altitude?, speedMin?, speedMax?, pauseSeconds?)`
- `RunHistory(id, routeId, startedAt, endedAt, mode, stats)`
- `Favorite(id, name, lat, lng, note)`
- `GpsEvent(id, runId, timestamp, lat, lng, accuracy, speed)`

## 風險與對策
- 背景服務被系統限制
  - 對策：前台服務 + 清楚提示 + 狀態監測
- Mock Location 權限不足
  - 對策：偵測開發者選項與 mock 設定，提供一步一步引導
- 上架合規
  - 對策：隱私政策與功能描述避免誤導
