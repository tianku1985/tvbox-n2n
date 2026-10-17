# TVBox 开发使用文档

> 基于 [takagen99/Box](https://github.com/takagen99/Box)（commit `258a5fe`）内化。
> 本仓库的定制重点：**hin2n 组网内置模块化**、**配置全部内置进 APK assets JSON（去除远程地址配置）**、**Android 4.4（API19）兼容**、首页入口行随列表滚动。
> hin2n 模块的细节另见 [hin2n-dev.md](file:///f:/tvbox-n2n/docs/hin2n-dev.md)，配置字段 demo 见 [tvbox-config.example.jsonc](file:///f:/tvbox-n2n/docs/tvbox-config.example.jsonc)。

---

## 1. 仓库结构

```
tvbox-n2n/
├── app/                    # TVBox 主模块（含 n2n/ 集成包、legacy 4.4 变体）
├── hin2n/Hin2n_android/app # hin2n 内置 library（settings.gradle 以 :hin2n 引入）
├── pyramid/                # Python 引擎模块（Chaquopy，仅 python flavor）
├── quickjs/                # QuickJS 引擎模块
├── xwalk/                  # Crosswalk WebView 离线包
├── docs/                   # 文档与配置 demo
├── .github/workflows/
│   ├── test.yml            # 唯一构建工作流（含 NDK/CMake 安装）
│   └── hin2n.yml           # 已停用的旧独立 hin2n 流水线（桩）
└── settings.gradle         # include ':hin2n' + projectDir 指向
```

## 2. 构建变体（flavor）

三维 flavor（`flavorDimensions = ["abi","brand","mode"]`），共 2×2×3 = 12 个变体：

| 维度 | 取值 | 说明 |
|------|------|------|
| abi | `armeabi` / `arm64` | 分别过滤 `armeabi-v7a` / `arm64-v8a` |
| brand | `generic` / `hisense` | 通用包 / 海信定制 applicationId |
| mode | `normal` / `python` / `legacy` | normal=Java 源(minSdk21, appcompat1.7.0)；python=Chaquopy(minSdk21)；**legacy=Android 4.4 兼容(minSdk19, appcompat1.6.1，复用 `src/normal/java`)** |

构建工具：AGP 7.4.2 / Gradle 7.5 / **JDK 17** / compileSdk 34；Kotlin 1.8、kapt（Room 2.6.1）。

```powershell
.\gradlew.bat assembleArm64GenericLegacyDebug   # 4.4 兼容调试包
.\gradlew.bat assemblerelease                   # 全部 release 变体（CI 同款）
```

输出名 `TVBox_<buildType>-<abi>-<brand>-<mode>.apk`（normal 显示为 java）。

## 3. 内置配置（assets JSON，已去远程化）

### 3.1 设计

TVBox 的所有定制配置统一来自 APK 内置文件：
[app/src/main/assets/tvbox-config.json](file:///f:/tvbox-n2n/app/src/main/assets/tvbox-config.json)

- **没有远程地址、没有网络拉取、没有远程 APK 自动更新**。设置页原「远程配置」卡片改为「内置配置」卡片，只剩一个「开机启动」开关；原「配置接口」「同步配置」两行 UI 已删除。
- 需要不同配置（本地定制或"远程"下发）时：**直接改源码里的 assets JSON，重新编译 APK**。
- 文件支持 JSONC：`//` 行注释、`/* */` 块注释、尾逗号、UTF-8 BOM（解析器 `RemoteConfig.parseLoose`，注释不会穿透字符串，URL 安全）。
- App 启动 `App.onCreate` 顺序：`Hawk.init()` → `RemoteConfig.bootstrap(this)` **同步**读取并应用（文件仅几 KB）→ `initParams()` 硬编码默认值兜底（`putDefault` 不覆盖已有值，因此 JSON 的 settings 优先级高于代码兜底）。

### 3.2 字段与覆盖规则

| 字段 | 落盘规则 |
|------|----------|
| `notice`（字符串或样式对象）/`home`/`tkp2p`/`entries`/`hin2nEntry`/`checkInterval`/`ua` | 每次启动用内置 JSON 覆盖缓存值 |
| `theme` / `themes` | 每次启动应用（`ThemeManager.updateThemes`）；`theme` 支持序号/中文名/英文 alias；`themes` 每项可带 `bgImage`（单张）或 `bgImages`（多张，随机选一张并在该主题下按索引缓存保持稳定），配图则所有页面用图，否则用 `bgStart→bgEnd` 渐变 |
| `upgrade` | 每次启动覆盖缓存；`HomeActivity` 进入后由 `UpgradeChecker` 按 `versionCode`/`checkUrl` 决定是否弹窗，每版本每次启动最多提示一次；`force=true` 时弹窗不可取消 |
| `topEntries` / `homeEntries` | 每次启动覆盖缓存；**数组含 ≥1 项才整体替换**顶栏/首页历史行，缺省或空数组 `[]`（含「数组里只有注释」剥注释后为空）= 删除 Hawk 键、保持内置默认 UI |
| `api` / `live` / `epg` / `proxy` | **仅当用户从未在 TVBox 内手动配置过该接口**（Hawk 值为空）时写入默认值；手动配置后永不覆盖，四个接口互不影响 |
| `bootStart`（TVBox 开机自启） | 只作默认值写入**一次**（Hawk `n2n_boot_default_set` 标记），之后以用户开关为准 |
| `settings`（设置页 25 项默认值） | 每项 `putIfAbsent`：**仅当用户从未在设置页改过该项**时写入一次，用户一旦手动更改该项永以用户为准（映射见 `RemoteConfig.applySettingsDefaults`；含 MediaSettingDialog 子项 `IJK_CACHE_PLAY`/`EXO_RENDERER`/`EXO_RENDERER_MODE`/`VOD_PLAYER_PREFERRED` 与 `HOME_DEFAULT_SHOW`/`PLAY_RENDER`/`VIDEO_PURIFY`/`FAST_SEARCH_MODE`/`DEBUG_OPEN`） |
| `hin2n.bootStart` / `hin2n.staticRoutes` / `hin2n.configs` | **仅首启且组网数据库为空时播种一次**（`Hin2nSeeder`），之后完全以组网界面里的用户操作为准 |

`version` / `apkUrl` / `hin2nUrl` 三个旧字段已废弃（远程更新与外部 hin2n APK 下载均不存在）。

### 3.3 关键类（`com.github.tvbox.osc.n2n` 包）

| 类 | 职责 |
|----|------|
| [RemoteConfig.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/RemoteConfig.java) | 读 assets、parseLoose、apply/applyEndpoint/applySettingsDefaults（设置页 25 项 putIfAbsent）；`fetchAuto/fetch/tryPendingUpdate` 保留空壳（立即成功回调），调用方无需改动；同时持有 Hawk 键常量 |
| [Hin2nSeeder.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nSeeder.java) | 首启播种 hin2n 配置；字段别名 `ipMode/fromSupernode`、`localIP/localIpAuto`、`acceptMulticast/dropMulticast`(反义) |
| [Hin2nEntryState.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nEntryState.java) | 常驻 EventBus 订阅 hin2n 5 个运行事件，折叠为 IDLE/CONNECTING/CONNECTED/ERROR 四态，供入口图标染色（拨号中=黄/已连接=绿/失败=红/未拨号=原色）；变化时 post `ChangedEvent` |
| [HomeEntry.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/HomeEntry.java) | 首页：hin2n 固定入口（内置模块直进，`applyStateTint` 染色）、`entries` 第三方 App 入口栏、`topEntries` 顶栏整体替换（隐藏 tvWifi/tvFind/tvStyle/tvTheme/tvDrawer/tvN2n/tvHin2n 七个固定图标后动态建项）、公告条、外观/主题应用、输入框/按钮统一样式（`styleInput/styleButton`）；定时 tick 只做界面刷新 |
| [EntryDispatcher.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/EntryDispatcher.java) | 自定义入口统一分发：`page`（vod/live/search/fastsearch/history/collect/push/setting/drive/apps/hin2n）、`feature`（siteSwitch/themeSwitch/styleSwitch/clearCache/reloadHome/wifi/date）、`vod`（开 CustomVodActivity）、`app`（已安装直开，未装且配 url 走 ApkDownloader）；图标解析（http 图片或内置 drawable 名）也在此 |
| [NoticeBar.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/NoticeBar.java) | 公告渲染（样式、时段、过期、滚动速度、home/live/vod 显隐） |
| [BootReceiver.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/BootReceiver.java) | BOOT_COMPLETED：hin2n 自连 + 按开关拉起首页 |
| [UpgradeChecker.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/UpgradeChecker.java) | 进首页后自检升级（公开入口 `check(activity)`）：内联字段或 `checkUrl` 拉取版本信息 → 版本号比较（每版本每进程只提示一次）→ TipDialog（`force` 时 `setCancelable(false)` 且不显示取消按钮）→ 确认后交 ApkDownloader |
| [ApkDownloader.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ApkDownloader.java) | 统一下载管线：OkHttp 流式下载到 `getExternalFilesDir`，首页顶部进度条；完成后 FileProvider+`ACTION_INSTALL_PACKAGE`/`ACTION_VIEW` 安装；Android 8+ 缺未知来源权限时先存 `sPendingInstall` 跳设置，`HomeActivity.onResume` 调 `resumePendingInstall(activity)` 自动重装；Activity 已销毁时用 application context 兜底 |
| [CustomVodActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/CustomVodActivity.java) | 自定义点播界面（`ui.activity` 包），复用 `R.layout.activity_vod`；打开时把接口 JSON 的 `sites` 以 `cv_` 前缀**仅内存注入** `ApiConfig`（不覆盖用户首页源），退出时 `removeCustomSources` 并恢复原 home 源；支持 `http(s)://`、`assets/`、`file:///绝对路径`（**注意：用标准 Gson 解析，不剥 JSONC 注释**） |

设置页：[ModelSettingFragment.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/fragment/ModelSettingFragment.java) 的 `initRemoteConfig()` 只绑定 `llRcBoot`（Hawk `n2n_boot_start`）；布局 `res/layout/fragment_model.xml` 与 `res/layout-v21/fragment_model.xml` 中的卡片标题为「内置配置」。

### 3.4 自定义入口机制（topEntries / homeEntries / entries）

- 数据模型：`RemoteConfig.Entry`（name/type/target/pkg/icon/url），`RemoteConfig.parseEntries()` 兼容字符串数组等异常输入。
- **出厂 JSON 即显式列出全部内置入口**（topEntries 7 项 / homeEntries 9 项，默认全显）；想关闭某入口 = 从数组删除该条；把字段整个删除或改成空数组 `[]` = 回退代码内置默认 UI。每次启动按 JSON 覆盖 Hawk 缓存。
- 顶栏：`HomeEntry.populate()` 读 `RemoteConfig.topEntries()`——返回 `null` 走内置默认；返回非空（≥1 项）则隐藏 7 个固定图标并按数组顺序动态生成 ImageView 加入顶栏容器，点击统一调 `EntryDispatcher.handle(activity, entry)`。
- 首页历史行：`UserFragment.rebuildHomeEntriesIfConfigured()` 读 `RemoteConfig.homeEntries()`；非空时移除 `tvUserHome` 行内全部默认卡片并按数组重建（含 `vod` 类型）。该行与推荐列表同处一个 ScrollView，焦点跟随滚动逻辑对动态卡片同样生效；重建行内「历史」卡片保留长按（切推荐模式并重载首页）。
- 图标解析 `EntryDispatcher.resolveIcon()`：`icon` 留空时按入口语义自动选图——`wifi` 按当前网络类型（hm_wifi/hm_lan/hm_mobile/hm_wifi_no）、`styleSwitch` 按 `HOME_REC_STYLE`（hm_left_right/hm_up_down），其余用兜底图标；`styleSwitch` 执行后调 `HomeEntry.refresh()` 重建顶栏使图标状态同步。
- hin2n 状态染色对动态入口同样生效：动态生成的组网入口打 `tag="hin2n_state"`，样式应用后由 `HomeEntry.retintStateIcons()` 统一重染（防止 `iconColor` 为空时的 `clearColorFilter` 抹掉状态色），`HomeActivity`/`UserFragment` 收到 `ChangedEvent` 也会遍历重染。
- 旧版 `entries`：仅 `app` 类型、追加式（不隐藏任何东西），顶栏容器内展示；`topEntries` 非空时它不再生效。
- `app` 类型：`PackageManager.getLaunchIntentForPackage(pkg)` 直开；未安装且有 `url` → `ApkDownloader.download()`；图标支持 http(s) URL（OkHttp 加载）或内置 drawable 资源名（`hm_*`、`ic_theme`、`ic_hin2n` 等），空/加载失败有兜底图标。

### 3.5 自定义点播界面（type=vod / cv_ 源注入）

1. 入口 target 为接口地址（http(s)/assets/file），`EntryDispatcher` 把地址放入 intent extra 启动 `CustomVodActivity`；
2. `onCreate` 读取并解析接口 JSON（标准 Gson，与点播 api 同结构），对每个 `SourceBean` 的 key 加 `CUSTOM_SRC_PREFIX = "cv_"`（防与用户源重名冲突），调 `ApiConfig.injectCustomSources(list)` 内存注入，并用 `setHomeSourceTemp(cv源key)` 临时切首页位；
3. 界面本体就是 VodActivity 逻辑复用，仅源集合不同；
4. `onDestroy` 调 `ApiConfig.removeCustomSources()` 清除全部 `cv_` 源并恢复进入前的 home 源 key——**不落盘、不污染用户配置**；进程被杀重启后自然恢复。

### 3.6 升级检查与 APK 下载安装管线

- 触发点：`HomeActivity` 启动后 `UpgradeChecker.check(activity)`（幂等）；版本信息优先 `upgrade.checkUrl` 网络获取（与内置 `upgrade` 段同结构），无 checkUrl 用内联字段；
- 判定：远程 `versionCode > BuildConfig` 版本号且有 `url`，且本次启动未对该版本提示过（进程内标记）；
- 弹窗：TipDialog 显示 versionName + notes；`force=true` 时只有「立即更新」且 `setCancelable(false)`；
- 下载：`ApkDownloader.download(context, url, fileName, listener)` → 下载中在当前 Activity 的 `android.R.id.content` 顶部动态注入 28dp 高的进度条覆盖层（ProgressBar+文字，tag 去重，完成延迟 1.2s 移除），不依赖任何布局 id → 下载完成：
  - 有权限：FileProvider（authority `${applicationId}.fileprovider`，`file_paths.xml` 已声明 external-path `.`）发安装 Intent，Android 7+ 必须走 FileProvider；
  - Android 8+ 无「安装未知应用」权限：缓存 `sPendingInstall`，跳当前包名的 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 设置页，授权返回后 `HomeActivity.onResume → resumePendingInstall` 自动继续安装；
  - 触发下载的 Activity 已销毁：用 `sApp`（application context）直接发安装 Intent 兜底。
- `entries`/`topEntries` 里 app 类型未安装下载复用同一条管线。

### 3.7 tkp2p:// P2P 直播

- 直播源 URL 前缀 `tkp2p://` 时，`LiveWebViewPlayer.buildTkP2pPage()` 生成内置播放页（`assets/tkp2p/`）并加载真实流地址；
- 引擎：`engine` 取值归一化为 `auto/hls/media`（配置里写全称 `p2p-media-loader`→`hls`、`media-p2p-engine`→`media` 兼容）；`announce`（tracker 列表）、`zone`、`token`（SwarmCloud）注入页面；
- ICE：先读 `iceServers`，兼容简写键 `ice`，JSON 数组整体注入 WebRTC 配置。

## 4. hin2n 入口与 TV 界面

- 首页「历史」入口行新增第 9 张卡片 `tvHin2nHome`（「组网」，图标 `hm_hin2n`），点击进 `Hin2nActivity`；可见性取 `RemoteConfig.hin2nEntryEnabled()`。
- 首页顶栏保留 `tvHin2n` 图标（`HomeEntry` 绑定），同样直接进内置模块，不再做外部包检测/下载。
- 三个 Activity（均 `exported=false`、横屏、无桌面图标）：
  - `n2n.ui.Hin2nActivity` —— 引导页（连接/状态/日志/配置管理/新建/Ping/开机开关）；
  - `n2n.ui.Hin2nConfigListActivity` —— 配置列表；
  - `n2n.ui.Hin2nConfigEditActivity` —— 28 字段表单。
- 界面颜色随 TVBox 主题：布局直接用 `?attr/color_theme*` 与主题 colorAccent；自定义控件样式 `Hin2nLabel/Hin2nInput/Hin2nCheck`（[styles.xml](file:///f:/tvbox-n2n/app/src/main/res/values/styles.xml)），勾选框同时给了 `android:buttonTint` 与 appcompat `buttonTint` 保证 API19 白色着色。
- **Ping 弹窗主题适配**（`Hin2nActivity.showPingDialog()`）：AppTheme 是 `Theme.AppCompat.Light` 系，AlertDialog 默认白底，硬编码白色文字会"白上白"不可见。修复：输入框背景用 JSON `home.inputBgColor`（缺省取当前主题深色背景色）画圆角深色底，文字色按背景亮度（0.299R+0.587G+0.114B < 128 判深色）自动取白/深灰，hint 取文字色 60% 透明。
- **「点击连接提示添加配置」自愈**（`Hin2nCore.currentSetting()`）：旧逻辑 SP `current_setting_id` 为 -1（旧版本未写入/配置被删未回写）或 JSON 备用存储模式（`sDaoSession==null`）时直接返回 null。修复：按 id 查不到/未设置时，依次回退「`isSelcected=true` 的配置 → 列表第一条」，并把选中的 id 回写 SP，保证只要配置列表非空就一定能取到当前配置。

## 5. 首页入口行随内容列表滚动 + 左右拖动

「我的」页 [fragment_user.xml](file:///f:/tvbox-n2n/app/src/main/res/layout/fragment_user.xml) 根布局是单个 ScrollView，入口行与其下的 `tvHotListForGrid`、`tvHotListForLine` 是同一个垂直 LinearLayout 的子 View，结构上天然一起滚动；遥控器焦点移动到屏外时，[UserFragment.installScrollFollow()](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/fragment/UserFragment.java) 通过全局焦点监听按焦点 View 的屏幕坐标 `smoothScrollTo`（上下各留 24dp margin），保证 D-pad 浏览时入口行也会被带出/带回视口。

入口行本身（`tvUserHome` 9 张卡 + 可配置动态卡）外层包了 `HorizontalScrollView`（无滚动条、不抢焦点、fillViewport=false）：入口过多超出屏宽时可**左右滑动/焦点右移自动横滚**，不再截断；`findViewById(R.id.tvUserHome)` 仍指向内层 LinearLayout，所有动态建卡逻辑无需改动。

## 5.1 首页冷启动优化

- `Hin2nCore.init()` 中的 9 个 n2n native 库（v1/v2s/v2/v3 两套 ABI）改为**后台线程异步预热**（`hin2n-load-libs` 线程），不再阻塞主线程 Application.onCreate；`N2NService.onCreate()` 里保留一次幂等的同步 `loadLibraries()` 兜底，保证真正拨号时库必然已加载。
- 播放器缓存清理（递归文件 IO）本就放后台线程；主题切换走 `switchTheme(act, useCache=true)`——`ApiConfig.loadConfig/loadJar` 全部命中磁盘缓存 + `SourceViewModel.sortCache` 静态 LRU 内存缓存，**只刷 UI 不重新拉取接口数据**（进程内重启 recreate 数据不丢）。

## 6. 两个开机启动开关

| 开关 | 存储 | 行为 |
|------|------|------|
| TVBox 开机启动 | Hawk `n2n_boot_start`（设置-内置配置卡片切换） | 开机拉起 HomeActivity |
| hin2n 开机启动 | SP `Hin2n/start_at_boot`（引导页切换） | 开机自动连 n2n VpnService（未授权过 VPN 则跳过） |

两者独立；同一个 BootReceiver 顺序执行，全部 best-effort（Android 10+ 后台启动限制）。

## 7. Android 4.4（API19）黑屏修复

根因：API19 在**资源预加载阶段**解析含主题属性引用（`?attr/color_theme*`）的 drawable XML（vector tint、shape solid 等）会直接崩溃 → 黑屏。布局文件里的 `?attr/` 在 View 渲染期解析，不受影响，无需改。

方案（双版本 drawable）：

1. `res/drawable/`：**28 个**含 `?attr/` 的 drawable 全部替换为蓝色主题固定色兜底：
   `color_theme_dark_70→#B3106D9C`、`color_theme_80→#CC18A2E7`、`color_theme_70→#B318A2E7`、`color_theme_50→#8018A2E7`、`color_theme_40→#6618A2E7`、`color_theme→#FF18A2E7`；不含 `<ripple>`/elevation。
2. `res/drawable-v21/`：同名文件保留 `?attr/` + ripple 原版（其中 23 个是从 drawable 补拷的，5 个原本就是 v21 ripple 版，3 个 hin2n 新建），API21+ 继续随主题换色与水波纹。
3. `defaultConfig.vectorDrawables.generatedDensities = ['mdpi','hdpi','xhdpi','xxhdpi']`：为 legacy(minSdk19) 变体把矢量图生成 PNG（normal/python minSdk21 不生成）。
4. legacy 变体 appcompat 锁 1.6.1；`src/legacy/AndroidManifest.xml` 用 `tools:overrideLibrary` 放行 minSdk21 的三方库（nextlib 等）。

新增任何 drawable 时的检查清单：默认 `drawable/` 里**禁止** `?attr/`、`<ripple>`、`<vector>` 的 `android:tint="?attr/…"`；需要主题效果就在 `drawable-v21/` 放同名增强版。

## 8. CI（test.yml）

1. Checkout → 打时间 tag → 版本号/关于框注入、crosswalk maven 源；
2. **Compress Source**：tar 包含 `hin2n/`（内置模块源码必须随包发布，符合 GPL）；
3. Python 3.8 依赖 + pyramid 适配（删 buildPython、锁 pyquery 2.0.2）；
4. 注入签名（TVBoxOSC.jks，base64 配置块）；
5. **setup-java 17 → Install NDK and CMake**：`yes | sdkmanager --licenses` 后安装 `ndk;25.2.9519653`、`cmake;3.22.1`（sdkmanager 需在 JDK17 步骤之后运行）；
6. `./gradlew assemblerelease --build-cache --parallel`，输出 tee 落盘，失败时 grep `error:` 汇总到日志尾部；
7. 产物：Artifact `takagen99-Box`（12 个 APK + 源码包 + Release Note）；定时为每天 06:59 UTC，也可手动触发并勾选「构建后不提交发布新版」。

kapt/runner 偶发抖动先直接 rerun；hin2n native 失败看 `externalNativeBuild*` 任务的 CMake/Clang 错误行。

## 9. 本地构建要点

- JDK17（不要用更高）；SDK 需 platform android-34、build-tools（AGP 默认找 30.0.3，本机可用目录 junction 指向已装的新版）；
- 编含 hin2n 的完整 APK 需要 NDK r25b + CMake 3.22.1；只验证 app 的 Java/资源可单跑 `:app:processXxxResources` / `:app:compileXxxJavaWithJavac`；
- `local.properties` 写 `sdk.dir`（已 gitignore）；
- ProGuard：`-keep class com.github.tvbox.osc.n2n.** { *; }`，另注意 greenDAO/EventBus/native JNI 相关类的 keep 规则。

## 10. 配置示例

- [tvbox-config.example.jsonc](file:///f:/tvbox-n2n/docs/tvbox-config.example.jsonc)：带中文注释的全量 demo（推荐照此修改 assets 文件）
- [tvbox-config.example.json](file:///f:/tvbox-n2n/docs/tvbox-config.example.json)：纯 JSON 版（无注释）
- hin2n 组网使用/字段说明：[hin2n.md](file:///f:/tvbox-n2n/docs/hin2n.md)
