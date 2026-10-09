# 全 UI 液態玻璃化 + Apple 捲軸 — 移植規格（26.2 為參考實作）

使用者要求（2026-10-05）：Minecraft 裡**每一個 UI 組件**都要用到液態玻璃；捲動頁面的側邊捲軸改成 Apple 風；
遊戲中（進世界後）與遊戲外（標題、選項、載入、選世界、多人…）是同一個整體。啟動器不在範圍內。

參考實作：`versions/mc262`（已部署）。26.3 同步完成（只差 `RenderPipeline` 套件 `com.mojang.renderpearl.api.pipeline`）。
每一項在目標版本要先用 javap 找到**實際的繪製呼叫**（方法名、描述子、貼圖路徑），再寫對應 mixin。

## 驗收清單（每一項都要在該版開發端截圖確認）

| # | 元件 | 26.2 檔案 | 原版長相 | 目標長相 |
|---|---|---|---|---|
| 1 | 清單/多行文字區捲軸 | `AppleScroller` + `ScrollAreaAppleScrollerMixin`；設定外殼的捲軸也改用 `AppleScroller` | 灰色貼圖捲軸＋黑軌 | macOS overlay：閒置 3px 白膠囊（淡暗暈），捲動時出現、停 1 s 後 0.3 s 淡出、游標靠近變 5px 並浮出 glass_btn 膠囊軌道＋內側髮線；拖曳座標沿用原版（1:1）；清單放得下時不畫 |
| 2 | 多行文字區框 | `TextAreaGlassMixin` | 黑底白框 | `GlassSurface.scrim` 4px 圓角，focus 0x4DFFFFFF / 否則 0x2EFFFFFF |
| 3 | 單行輸入框 | `EditBoxFrameGlassMixin`（全部有框的輸入框） | 黑底灰框 | 同 #2 |
| 4 | 清單選中列 | `SelectionGlassMixin`（攔選中框繪製方法 HEAD） | 外框色填滿＋內縮黑底 | glass_btn 膠囊（hotbar 圓角），有焦點 lift .81／否則 .55 |
| 5 | 清單底色帶 | `ListBackgroundGlassMixin` | 暗色/泥土帶 | 全寬清單不畫；寬 < 70% 螢幕的窄清單（資源包兩欄）墊玻璃窗格＋0x30 灰 scrim |
| 6 | 標頭/頁尾分隔線 | `SeparatorHairlineMixin`（攔四張 separator 材質的 blit） | 2px 浮雕雙線 | 0.5px 白髮線，兩端淡出 |
| 7 | 分頁（選單分頁/建立世界/好友） | `TabGlassMixin`、`FriendsTabGlassMixin` | 方形分頁＋選中底線 | 玻璃膠囊；**拿掉底線**；未選 40%、懸停 70%、選中 100%＋lift |
| 8 | 訊息框（FocusableText 類） | `FocusableTextGlassMixin` | 黑底灰框 | 玻璃 plate＋0x38 灰 scrim（白字可讀） |
| 9 | 好友浮層/確認框面板 | `FriendsPanelGlassMixin` | 深灰九宮格 | plate＋0x30 scrim（26.2+ 才有好友系統） |
| 10 | 社交互動玩家列 | `SocialEntryGlassMixin` | 灰色實心塊 | 圓角**灰** scrim 0x40000000（白 scrim 會讓白字看不見） |
| 11 | 檢舉描述框 | `ReportDescriptionGlassMixin` | 黑底白框 | scrim，無外框 |
| 12 | 鐵砧命名欄、附魔台三列 | `ContainerFieldsGlassMixin` | 深色條/棕色條 | 命名欄 scrim；附魔列 glass_btn（懸停 lift，不可用＝淡 scrim） |
| 13 | 指令自動完成 | `SuggestionsGlassMixin` | 黑色列＋點線＋黃字 | 一整塊 plate＋0x78 scrim，選中列 glass_btn 膠囊，黃→白、灰 AAAAAA→E0E0E0，去點線 |
| 14 | 指令用法提示 | `UsageGlassMixin` | 黑條 | 圓角暗 scrim（保留原透明度） |
| 15 | F3 除錯資訊 | `DebugOverlayGlassMixin` | 每行一條灰底 | 每組連續行（空行分組）一張 plate＋0x60 scrim |
| 16 | 字幕 | `SubtitleGlassMixin` | 方形黑框 | 圓角 scrim（原透明度） |
| 17 | 經驗/跳躍/定位條 | `ContextualBarGlassMixin`（依貼圖路徑） | 深色分段條 | 軌道 glass_btn 膠囊；進度圓頭色條：經驗 #30D158、跳躍 #FF9F0A、冷卻 #8E8E93 |
| 18 | 進度視窗分頁/懸停標題框 | `AdvancementSpritesGlassMixin`（依貼圖路徑） | 原版分頁/藍條 | glass_btn（分頁分段控制強調；標題框 lift） |
| 19 | F3+F4 遊戲模式切換器 | `GameModeSwitcherGlassMixin` + `SpritePathGlassMixin` | 深灰面板＋格框 | plate＋scrim；格子淡玻璃、選中 lift |
| 20 | 旁觀者快捷欄 | `SpritePathGlassMixin`（僅旁觀者時） | 原版快捷欄貼圖 | HUD 玻璃條＋選中 lift |
| 21 | 世界難度鎖定鈕 | `LockIconButtonGlassMixin` | 原版鎖圖示 | scrim 圓鈕＋SF lock.fill（琥珀）/lock.open.fill |

### 第二批（使用者 2026-10-05，26.2/26.3/1.21.1 已完成並部署）

| # | 元件 | 26.2 檔案 | 1.21.1 檔案（Yarn 參考） | 目標長相 |
|---|---|---|---|---|
| 22 | 選單分頁列（建立世界 遊戲/世界/更多、影像設定分頁） | `SegmentedTabBarMixin`＋`TabNavigationBarAccessor`＋`SegmentedTabs`；`TabGlassMixin` 見 `SegmentedTabs.active` 只畫懸停微光 | `SegmentedTabNavMixin`（`TabNavigationWidget.render` HEAD）＋`TabNavAccessor`＋`SegmentedTabs`；`TabButtonGlassMixin` 同旗標 | **一個**容器膠囊（alpha .6）＋滑動選中 pill（lift .81，指數 ease tau 0.075s，`double[]` 狀態）。**必須重排頁籤**：原版把頁籤平鋪整寬→膠囊超寬。每個頁籤寬＝字寬＋18px×2、間距 2px、整組置中（setX/setWidth，點擊自動跟） |
| 23 | 進度視窗＝創造背包同款 | `AdvancementsGlassMixin`（面板延伸）＋`AdvancementSpritesGlassMixin`（4-int `blitSprite(RP,Id,IIII)`） | `AdvancementsGlassMixin`（`drawAdvancementTree` HEAD 直接讀 tabs）＋`AllGlassSpriteMixin` 的 `advancements/tab_` 分支 | 有頁籤的邊把玻璃面板延伸 28（＝一個頁籤深度，創造 `GlassTabs.BAND`），頁籤列成為同一張 sheet 的帶；**只在 tabs.size()>1 時延伸**（原版單頁籤不畫頁籤列）。頁籤磚全部不畫，只有選中頁籤畫 inset pill（距 3px，朝視窗那側留 7px）。圖示照常畫。懸停標題框維持原版（可讀性） |
| 24 | 設定面板（S1mp1e 設定）大尺寸圓角封頂 | `S1mp1eConfigScreen` 的 `GlassWidgets.panel(..., Math.min(14, side*0.0475), ...)` | 共用 `GlassWidgets.panel`：shader 半徑＝minHalf×0.5×corner → `corner=min(0.19, 56/minSide)`；scrim `min(14, side*0.0475)` | 小視窗不變，全螢幕不再過圓（封頂 14px） |
| 25 | 捲軸兩端內縮避開玻璃圓角 | `AppleScroller.END_INSET=8` | 同 | 軌道兩端各內縮 `min(8, 高×0.15)`，knob 位置按比例映射到縮短後的軌道 |

| 26 | 遊戲外選單背景＋清單泥土帶（1.20.4 以前才有） | —（26.2/1.21.1 原版已是模糊全景） | — | 遊戲外（沒有世界）的畫面不再畫原版泥土材質背景，改成**標題畫面的全景（cubemap）＋模糊**（S1mp1e 已有 `MenuBackdrop`/`menu_blur.fsh` 可用），與 26.2/1.21.1 一致；**全寬清單**不畫深色泥土帶、上下泥土條與陰影漸層（窄清單照 #5 墊玻璃窗格）。遊戲內（有世界）維持原本做法。要確認玻璃仍折射到新背景（擷取時機在背景畫完之後）。 |

**舊版（Yarn/MCP）以 1.21.1 為參考**，不是 26.2：1.21.1 是立即模式 GL（`ctx.draw()` 先 flush、`AllGlass` 自動 bake 矩陣＋關深度測試）、mixin 套件 `dev.s1mp1e.glass.mixin`、註冊在 `s1mp1e.mixins.json`、`DevAudit.java`（Yarn 版）接在 DevShot `P_AUDIT`。
- 1.20.1 及以前沒有 GUI sprite（`drawGuiTexture` 是 1.20.2 才有）：分頁、進度分頁、勾選框等都是 `drawTexture(大圖, u, v…)`，要依 **貼圖＋uv** 判斷，或直接攔 `AdvancementTabType.drawBackground(ctx,x,y,selected,index)` 這類有 `selected` 參數的方法。
- `TabNavigationWidget`（#22）1.19.4 才有；1.19.2 以前建立世界沒有分頁列 → #22 不適用。
- 1.21.1 陷阱：`AdvancementTabType` 是 package-private，不能具名、enum switch 會「ordinal inaccessible」編譯錯；`.name()` 在 intermediary 執行期不可靠 → `((Enum<?>)(Object) t.getType()).ordinal()`（0 ABOVE/1 BELOW/2 LEFT/3 RIGHT）。
- 26.x 陷阱：`extractRenderState` HEAD 時 tabs/selectedTab 還沒就緒；改在 `extractWindow` HEAD 量測、下一幀用。
- **#26 掛鉤點（1.19.x 以前沒有 `Screen.renderWithTooltip`）**：不可掛 `Screen.render` HEAD——選擇世界/多人/資源包等畫面先畫清單、最後才呼叫 `super.render`，背景會蓋掉清單內容；標題畫面裡的 Realms 通知子畫面也走 `Screen.render`，會把標題糊掉。正解：在 `GameRenderer.render(FJZ)V` 呼叫 `currentScreen.render` 之前畫背景，排除 TitleScreen，有世界時不畫。窄清單玻璃窗格要在清單 render HEAD 畫（RETURN 會蓋住項目）。**審 #26 一定要確認清單內容、搜尋框、窄窗格都還在，不能只看「沒有泥土」。**
- 1.19.x 以前（DrawableHelper 時代）：繼承 `DrawableHelper` 的類別呼叫 `fill`/`drawTexture` 時，INVOKE 的擁有者是**該類別自己**，不是 DrawableHelper——@Redirect/@WrapOperation 的 target 要寫子類別擁有者，否則注入不到或崩潰。
- 窄清單玻璃窗格（#5）一律用 **hotbar 圓角**：面板預設圓角是短邊比例，全螢幕時會變超圓（使用者 2026-10-07 在 26.2 Mod Menu 指出）。26.x 用 `GlassSurface.plate(g,…,opacity,radiusPx)`。
- 第三方 Mod Menu 自己畫選中列（`ModListWidget.drawSelectionHighlight`：灰框＋黑底），要另外換成同款玻璃選中膠囊（26.x：`compat/modmenu/ModMenuSelectionGlassMixin`，`liquidglass.compat.mixins.json`，@Pseudo）。
- 稽核「崩潰」不要直接歸類為原生 GPU 崩潰，先查日誌（1.20.1 曾把 mod 的 ClassCastException 誤判成 GPU 問題）。

已確認本來就是玻璃（不要重做）：勾選框（`SfIcons` 依貼圖路徑畫 iOS 圓圈＋勾）、書本頁（暖色羊皮紙 scrim 是刻意例外）、
進度視窗外框、容器面板、快捷欄、聊天、toast、名牌、動作列、Boss 條、tab 清單、載入卡。

## 陷阱

- **按鈕子類別（Checkbox、LockIconButton…）裡的 `blitSprite` 不要用 `@Redirect`**：執行期「Scanned 0 target(s)」注入失敗→該畫面打不開。
  改 `@Inject(method = "<名>(<完整描述子>)V", at = @At("HEAD"), cancellable = true)` 自己畫。
- **編譯過 ≠ 能跑**：每個新 mixin 都要讓開發端實際開到那個畫面（mixin 在類別第一次載入時才套用）。完整稽核跑完要 grep `InjectionError`。
- 盤點時 grep 類別名會漏掉「依貼圖路徑」處理的元件（`SfIcons`），也要 grep 貼圖路徑。
- 玻璃 shader 圓角上限＝短邊 1/4，細條（< 10px）做不成膠囊 → 用 glass_btn（`GlassWidgets.capsule`）。
- 不要在「已經是玻璃的內容」後面再墊大玻璃板（使用者否決過 OptionsList 背板）。
- 一次只開一個開發端；不要碰使用者正在跑的遊戲/啟動器；離開世界前先存檔（DevShot 已處理）。
- 部署：`C:\Temp\s1port\deploy_one.ps1 <ver> glass-<v>.jar`（有無遊戲執行的守衛＋自動備份）。
- **1.18.2 以下用 `@Redirect` 攔 `Tessellator.draw()` 丟掉原版四邊形時，必須 `getBuffer().end()` 之後再 `getBuffer().popData()`**。
  `end()` 只是把建好的頂點資料排進佇列，沒取走的話下一個 `Tessellator.draw()` 會先畫到「我們丟掉的那塊」並套上它自己的
  shader，之後每一筆繪製都錯位一格：1.18.2 清單選中列變成帶資源包圖示紋理的褐色方塊、清單圖示全消失、副標題文字變成色塊，
  而且一路影響到後面的畫面。驗收時清單畫面（選擇世界、資源包）要放大看圖示和副標題文字。
- 用 bisect 排查 mixin 時，PowerShell 只排除「一個」名稱會把陣列攤成字串、排除失效 → 一律把逗號字串原樣交給 Python 再切，
  並印出「mixins 118 → 117」確認真的少了。

## 驗證工具（C:\Temp\s1263）

- `audit.ps1 <ver> <tag> [maxAttempts]`：DevAudit 逐一開啟所有 Screen 類別截圖（崩潰自動跳過續跑）；`sheet.py` 拼 3×3 縮圖。
- `audit_only.ps1 <ver> <tag> "Name,Name"`：只跑指定畫面（`S1MP1E_AUDIT_ONLY`）。
- DevAudit 截圖前會自動捲動第一個可捲區、選中第一個清單的一列（讓 #1、#4 入鏡）。
- `runmode.ps1 <ver> <tag> <mode>`：`lists`（遙測頁捲軸 h/i 序列）、`glasshud`（經驗條＋F3）、`hud`（聊天/toast/名牌…）。
- `patch_devtools.py <ver>`：把稽核/拍攝工具的新功能套到別版（26.x 適用；Yarn/MCP 版本要手動改寫）。
