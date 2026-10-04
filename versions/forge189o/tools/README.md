# forge189o 產生工具

`libs/s1forge-lib-*.jar`（Forge 1.8.9 函式庫，重映射成 named／intermediary）與
`src/main/resources/s1forge/`（捐贈成員 zip、存取放寬、SRG→執行期名稱表、Forge 資源清單）、
`src/main/java/dev/s1mp1e/forge/mixin/donor/Donor*.java`（mixin 目標清單）都由這裡的工具產生。
libs/ 不進版控（含 Forge 二進位），要建置先跑一次 `rebuild.sh`。

原本放在 `C:/Temp/s1port/forgetool`，腳本裡的路徑以那裡為準。

| 檔案 | 用途 |
|------|------|
| `gen_tiny.py` | Forge 的 `joined.srg`（notch→SRG）＋Loom 的 Feather 對照 → `forge-srg.tiny`（official/srg/intermediary/named） |
| `gen_srg2rt.py` | 產生 `srg2rt-<ns>.txt`：C（MCP 類→執行期）、M（SRG 成員→執行期）、S（非 func_ 形式的 SRG 名，依擁有者＋描述子）、A（intermediary 歧義） |
| `DonorGen.java` | 比對 Forge binpatched jar 與原版：Forge 新增的成員／介面／欄位初始化片段 → 捐贈 zip；Forge 自己的類別做 FML 執行期轉換（ClassFixer）、字串常數換名 → 函式庫 jar |
| `ListDonors.java`、`targets.py` | 捐贈類別＋forge_at.cfg 涉及的類別 → mixin 目標（分類別／介面，剔除伺服器專用） |
| `Collide.java` | 找 Feather named 撞名（Forge 新增成員和原版 Feather 名同名同描述子 → 在 `gradle/feather-overrides.tiny` 把原版那邊改名 `xxxVanilla`） |
| `LinkCheck.java` | 列出 Forge 引用、但原版沒有的成員 |
| `package.sh` | 打包進 libs/ 與 resources/，刪掉 overlay.txt 列的類別（改由 src/ 的改寫版提供） |
| `rebuild.sh` | 全部重建 |
| `runf.ps1`、`cmpshots.py` | dev 執行（`-Test itemscroller,events`、`-With189o -Shot`）與 DevShot 截圖比對 |

`tool.tiny` ＝ Loom 的 `mappings.tiny`（official→intermediary→named）加上：Feather 撞名的原版成員改名
（MinecartEntity.getMaxSpeed、MinecraftServer.worldTickTimes、ChunkHolder.players、IdRegistry.getId）、
以及 binpatched 裡 13 個 Forge 新增內部類別依外層類別補的對照。
