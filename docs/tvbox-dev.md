# TVBox 开发使用文档

> 基于 [takagen99/Box](https://github.com/takagen99/Box)（commit `258a5fe`）内化。
> 本仓库的定制重点：**hin2n 组网内置模块化**、**配置全部内置进 APK assets JSON（去除远程地址配置）**、**接口密文下发（AES-128-ECB）**、**接口请求携带本机参数（mac/ip/机型/系统版本）**、**Android 4.4（API19）兼容**、首页入口行随列表滚动。
> hin2n 模块的细节另见 [hin2n-dev.md](file:///f:/tvbox-n2n/docs/hin2n-dev.md)，配置字段 demo 见 [tvbox-config.example.jsonc](file:///f:/tvbox-n2n/docs/tvbox-config.example.jsonc)。

---

## 1. 仓库结构

```
tvbox-n2n/
├── app/                    # TVBox 主模块（含 n2n/ 集成包、legacy 4.4 变体）
├── hin2n/Hin2n_android/app # hin2n 独立 App（settings.gradle 以 :hin2n 引入，applicationId wang.switchy.hin2n）
├── pyramid/                # Python 引擎模块（Chaquopy，仅 python flavor）
├── quickjs/                # QuickJS 引擎模块
├── xwalk/                  # Crosswalk WebView 离线包
├── docs/                   # 文档与配置 demo
├── .github/workflows/
│   ├── test.yml            # TVBox 主工程构建（只构建 :app，不再安装 NDK/CMake）
│   └── hin2n.yml           # hin2n 独立 App 构建（发布到固定 tag hin2n-latest）
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

- **没有远程地址、没有网络拉取、没有远程 APK 自动更新**。设置页原「远程配置」卡片改为「内置配置」卡片，只剩「开机启动」「设为桌面」两个开关；原「配置接口」「同步配置」两行 UI 已删除。
- 需要不同配置（本地定制或"远程"下发）时：**直接改源码里的 assets JSON，重新编译 APK**。
- 文件支持 JSONC：`//` 行注释、`/* */` 块注释、尾逗号、UTF-8 BOM（解析器 `RemoteConfig.parseLoose`，注释不会穿透字符串，URL 安全）。
- App 启动 `App.onCreate` 顺序：`Hawk.init()` → `RemoteConfig.bootstrap(this)` **同步**读取并应用（文件仅几 KB）→ `initParams()` 硬编码默认值兜底（`putDefault` 不覆盖已有值，因此 JSON 的 settings 优先级高于代码兜底）。
- **assets 文件本身也允许是密文**（仅 ke 前缀模式，见 §3.11）：读文件后先过 `ConfigCrypto.maybeDecrypt` 再 `parseLoose`。

### 3.2 字段与覆盖规则

| 字段 | 落盘规则 |
|------|----------|
| `notice`（字符串或样式对象）/`home`/`tkp2p`/`entries`/`hin2nEntry`/`checkInterval`/`loadSuccessDuration`/`warningAutoClose`/`ua` | 每次启动用内置 JSON 覆盖缓存值 |
| `home.recMovies`（首页自定义推荐） | `home` 段内的数组，每次启动随 `home` 一起覆盖：**数组非空时**首页推荐列表直接显示配置的影片（`name`/`pic`/`note`/`id`/`sourceKey`），**不再请求豆瓣**，标题显示「推荐」；配 `id`+`sourceKey` 点击进详情，只配 `name` 点击按片名搜索；空数组/全注释 = 保持原有豆瓣逻辑 |
| `loadSuccessDuration` / `warningAutoClose` | 前者=点播接口加载成功顶部提示的自动关闭秒数（0=不显示，默认 3，0~60）；后者=免责声明 `warningText` 弹窗的自动关闭秒数（0=不自动关闭，默认 0，0~120，按钮带倒计时） |
| `theme` / `themes` | 每次启动应用（`ThemeManager.updateThemes`）；`theme` 支持序号/中文名/英文 alias；`themes` 每项可带 `bgImage`（单张）或 `bgImages`（多张，随机选一张并在该主题下按索引缓存保持稳定），配图则所有页面用图，否则用 `bgStart→bgEnd` 渐变 |
| `upgrade` | 每次启动覆盖缓存；`HomeActivity` 进入后由 `UpgradeChecker` 按 `versionCode`/`checkUrl` 决定是否弹窗，每版本每次启动最多提示一次；`force=true` 时弹窗不可取消 |
| `topEntries` / `homeEntries` | 每次启动覆盖缓存；**数组含 ≥1 项才整体替换**顶栏/首页历史行，缺省或空数组 `[]`（含「数组里只有注释」剥注释后为空）= 删除 Hawk 键、保持内置默认 UI |
| `api` / `live` / `epg` / `proxy` | **仅当用户从未在 TVBox 内手动配置过该接口**（Hawk 值为空）时写入默认值；手动配置后永不覆盖，四个接口互不影响 |
| `bootStart`（TVBox 开机自启） | 只作默认值写入**一次**（Hawk `n2n_boot_default_set` 标记），之后以设置页开关为准；出厂默认开 |
| `setAsLauncher`（设为系统桌面） | 默认值写入**两轮守卫**：首轮 Hawk `rc_set_launcher_default_set`（老版本已置位则跳过），第二轮 `rc_set_launcher_default_set_v2` 对老设备补写一次（出厂默认改为开启后对存量设备也生效一次）；之后以设置页开关为准 |
| `secretKey`（固定密钥模式秘钥） | 每次启动覆盖 Hawk `rc_secret_key`；非空时**优先于** `ConfigCrypto.SECRET_KEY` Java 常量（见 §3.11） |
| `donate`（捐助界面对象） | 每次启动覆盖 Hawk `rc_donate`；字段缺失或非对象时删除缓存（捐助界面回退内置文案）。子字段：`title`（顶栏标题）/`desc`（介绍，`\n` 换行）/`image`（二维码：http(s)/assets/路径/drawable 名）/`tip`（二维码下方说明文字，空=不显示） |
| `epgDays`（直播 EPG 显示天数） | 每次启动覆盖 Hawk `rc_epg_days`；读取处 clamp 到 1~9（默认 3=昨天/今天/明天），日期列表以今天为中心（见 §3.15） |
| `aliLicenseKey`（阿里云播放器 License） | 每次启动覆盖 Hawk `rc_ali_license`；`PlayerHelper.init()` 反射调 `AliPlayerGlobalSettings.setLicenseKey`，消除 AliyunPlayer 5.5+ 未配 License 时的「配置信息初始化失败，请重启app」提示（不配不影响播放） |
| `settings`（设置页 25 项默认值） | 每项 `putIfAbsent`：**仅当用户从未在设置页改过该项**时写入一次，用户一旦手动更改该项永以用户为准（映射见 `RemoteConfig.applySettingsDefaults`；含 MediaSettingDialog 子项 `IJK_CACHE_PLAY`/`EXO_RENDERER`/`EXO_RENDERER_MODE`/`VOD_PLAYER_PREFERRED` 与 `HOME_DEFAULT_SHOW`/`PLAY_RENDER`/`VIDEO_PURIFY`/`FAST_SEARCH_MODE`/`DEBUG_OPEN`） |
| `hin2n.bootStart` / `hin2n.staticRoutes` / `hin2n.configs` | **仅首启且组网数据库为空时播种一次**（`Hin2nSeeder`），之后完全以组网界面里的用户操作为准 |

`version` / `apkUrl` / `hin2nUrl` 三个旧字段已废弃（远程更新与外部 hin2n APK 下载均不存在）。

**远程配置覆盖（`remoteConfigUrl`）**：内置 json 顶层 `remoteConfigUrl` 非空时，`RemoteConfig.bootstrap` 在应用内置配置后另起后台线程 GET 该地址（URL 经 `DeviceParams.append` 拼接本机参数，见 §3.12），响应先过 `ConfigCrypto.maybeDecrypt`（支持密文下发，见 §3.11）、`parseLoose` 后以 `remoteOnly=true` 调用 `apply`——只覆盖 notice/home/tkp2p/entries/topEntries/homeEntries/upgrade/hin2nEntry/themes/theme/api/live/epg/proxy/checkInterval/loadSuccessDuration/warningAutoClose/ua/**secretKey**/**donate**/**epgDays**/**aliLicenseKey** 段；**跳过 `settings`（设置页）、`bootStart`（TVBox 开机自启）、`setAsLauncher` 以及 `hin2n`（n2n 组网，本来也不走 apply）**。成功时把**解密后的明文**写入 Hawk `rc_remote_raw` 作为断网兜底；远程失败或解析异常时，若 `rc_remote_raw` 有缓存则用缓存兜底，否则完全保持内置。UA 使用内置 `ua` 字段，缺省 `Mozilla/5.0 (Linux; Android) TVBox`，连接/读取超时 8 秒。

**useRemoteConfig 强制覆盖（统一管控模式）**：内置 json 顶层 `useRemoteConfig: true` 且本次远程拉取成功（含 `rc_remote_raw` 缓存兜底应用）时，`apply(remoteOnly=true)` 进入 `forceRemote` 分支：远程 json 强制覆盖 `settings` 全部 25 项（`putBool/putInt/putStr` 跳过 `Hawk.contains` 守卫）、`bootStart`、`setAsLauncher`（并立即 `applyLauncherComponent` 同步别名状态）、`api/live/epg/proxy`（`applyEndpoint` 不再检查用户是否已配置）。`useRemoteConfig=false` 或远程不可访问时行为与上文默认规则完全一致。

### 3.3 关键类（`com.github.tvbox.osc.n2n` 包）

| 类 | 职责 |
|----|------|
| [RemoteConfig.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/RemoteConfig.java) | 读 assets（先 `ConfigCrypto.maybeDecrypt` 再 parseLoose）、apply(app,json,remoteOnly)（remoteOnly=true 时跳过 settings/bootStart/setAsLauncher/hin2n；`useRemoteConfig=true` 且远程可达时进入 forceRemote 强制覆盖分支）；`bootstrap` 同步应用内置 json + `applyLauncherComponent` 同步桌面别名后，若 `remoteConfigUrl` 非空则后台线程拉取（`DeviceParams.append` 拼参数 + 密文自动解密），成功把解密后明文缓存到 Hawk `rc_remote_raw` 兜底；`secretKey()`/`donate()` 暴露秘钥与捐助配置；桌面相关 API：`launcherEnabled/setLauncherEnabled/applyLauncherComponent/isDefaultLauncher/ensureDefaultLauncher/promptChooseLauncher` |
| [ConfigCrypto.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ConfigCrypto.java) | **接口密文统一解密**（§3.11）：`maybeDecrypt(raw)` 是 json/明文原样返回，是密文按两种模式（ke 前缀 / 固定密钥）自动识别解密，失败原样返回永不抛异常；**`SECRET_KEY` 常量 = 固定密钥模式的秘钥自定义修改位置** |
| [DeviceParams.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/DeviceParams.java) | **接口请求本机参数拼接**（§3.12）：`append(url)` 给 http(s) 请求统一拼上 `mac/ip/model/brand/device/sdk/release/app` 查询参数，服务端按 `$_GET['mac']` 等按设备下发 |
| [Hin2nSeeder.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nSeeder.java) | 首启播种 hin2n 配置；字段别名 `ipMode/fromSupernode`、`localIP/localIpAuto`、`acceptMulticast/dropMulticast`(反义) |
| [Hin2nEntryState.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nEntryState.java) | 常驻 EventBus 订阅 hin2n 5 个运行事件，折叠为 IDLE/CONNECTING/CONNECTED/ERROR 四态，供入口图标染色（拨号中=黄/已连接=绿/失败=红/未拨号=原色）；变化时 post `ChangedEvent` |
| [HomeEntry.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/HomeEntry.java) | 首页：hin2n 固定入口（内置模块直进，`applyStateTint` 染色）、`entries` 第三方 App 入口栏、`topEntries` 顶栏整体替换（隐藏 tvWifi/tvFind/tvStyle/tvTheme/tvDrawer/tvN2n/tvHin2n 七个固定图标后动态建项）、公告条、外观/主题应用、输入框/按钮统一样式（`styleInput/styleButton`）；定时 tick 只做界面刷新 |
| [EntryDispatcher.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/EntryDispatcher.java) | 自定义入口统一分发：`page`（vod/live/search/fastsearch/history/collect/push/setting/drive/apps/hin2n/**system**（系统设置）/**donate**（捐助界面））、`feature`（siteSwitch/themeSwitch/styleSwitch/clearCache/reloadHome/wifi/date）、`vod`（开 CustomVodActivity）、`app`（已安装直开，未装且配 url 走 ApkDownloader）；图标解析（http 图片或内置 drawable 名）也在此 |
| [NoticeBar.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/NoticeBar.java) | 公告渲染（样式、时段、过期、滚动速度、home/live/vod 显隐）；**三处字号统一**（未配 `fontSize` 时统一用 `@dimen/ts_24` 的 px，配了则统一按 sp，修复首页与点播/直播文字大小不一致）；`ScreenState.expired` 标记：duration 到期隐藏后同内容的周期性重渲染不再重新武装 deadline（修复主界面 ticker 触发 render 导致公告永不消失）；`scrollToken` + `speedRun` 身份比对避免快速换内容时多条滚动循环叠加 |
| [BootReceiver.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/BootReceiver.java) | BOOT_COMPLETED：hin2n 自连 + 按开关拉起首页 |
| [UpgradeChecker.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/UpgradeChecker.java) | 进首页后自检升级（公开入口 `check(activity)`）：内联字段或 `checkUrl` 拉取版本信息（拼本机参数 + 支持密文响应）→ 版本号比较（每版本每进程只提示一次）→ TipDialog（`force` 时 `setCancelable(false)` 且不显示取消按钮）→ 确认后交 ApkDownloader |
| [ApkDownloader.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ApkDownloader.java) | 统一下载管线：OkHttp 流式下载到 `getExternalFilesDir`，首页顶部进度条；完成后 FileProvider+`ACTION_INSTALL_PACKAGE`/`ACTION_VIEW` 安装；Android 8+ 缺未知来源权限时先存 `sPendingInstall` 跳设置，`HomeActivity.onResume` 调 `resumePendingInstall(activity)` 自动重装；Activity 已销毁时用 application context 兜底 |
| [CustomVodActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/CustomVodActivity.java) | 自定义点播界面（`ui.activity` 包），复用 `R.layout.activity_vod`；打开时把接口 JSON 的 `sites` 以 `cv_` 前缀**仅内存注入** `ApiConfig`（不覆盖用户首页源），退出时 `removeCustomSources` 并恢复原 home 源；支持 `http(s)://`（拼本机参数 + 密文自动解密）、`assets/`、`file:///绝对路径`（本地内容同样先过 `ConfigCrypto.maybeDecrypt`，**注意：之后用标准 Gson 解析，不剥 JSONC 注释**） |
| [DonateActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/DonateActivity.java) | 捐助界面（`ui.activity` 包，`type=page target=donate` 入口打开）：读 `RemoteConfig.donate()` 渲染标题（`title`，空=「支持我们」）、项目详细介绍（`desc`，空=内置介绍文案，支持 `\n` 换行）、自定义图片/收款二维码（`image`：http(s) 走 Glide、`assets/` 路径读包内文件、其余按 drawable 资源名解析；缺失或加载失败时图片区隐藏）、二维码下方说明文字（`tip`，空=隐藏）；布局 [activity_donate.xml](file:///f:/tvbox-n2n/app/src/main/res/layout/activity_donate.xml) 复用主题顶栏样式 |

设置页：[ModelSettingFragment.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/fragment/ModelSettingFragment.java) 的 `initRemoteConfig()` 绑定 `llRcBoot`（开机启动，Hawk `n2n_boot_start`）与 `llRcLauncher`（设为桌面，走 `RemoteConfig.setLauncherEnabled` + `promptChooseLauncher`）；「配置地址」行只保留入口不回显已输入地址（tvApi 已删）。布局 `res/layout/fragment_model.xml` 与 `res/layout-v21/fragment_model.xml` 中的卡片标题为「内置配置」。

### 3.4 自定义入口机制（topEntries / homeEntries / entries）

- 数据模型：`RemoteConfig.Entry`（name/type/target/pkg/icon/url/theme，`theme` 仅 vod 类型），`RemoteConfig.parseEntries()` 兼容字符串数组等异常输入。
- **出厂 JSON 即显式列出全部内置入口**（topEntries 7 项 / homeEntries 11 项，默认全显）；想关闭某入口 = 从数组删除该条；把字段整个删除或改成空数组 `[]` = 回退代码内置默认 UI。每次启动按 JSON 覆盖 Hawk 缓存。
- 顶栏：`HomeEntry.populate()` 读 `RemoteConfig.topEntries()`——返回 `null` 走内置默认；返回非空（≥1 项）则隐藏 7 个固定图标并按数组顺序动态生成 ImageView 加入顶栏容器，点击统一调 `EntryDispatcher.handle(activity, entry)`。
- 首页历史行：`UserFragment.rebuildHomeEntriesIfConfigured()` 读 `RemoteConfig.homeEntries()`；非空时移除 `tvUserHome` 行内全部默认卡片并按数组重建（含 `vod` 类型）。该行与推荐列表同处一个 ScrollView，焦点跟随滚动逻辑对动态卡片同样生效；重建行内「历史」卡片保留长按（切推荐模式并重载首页）。
- `page` 类型 target 全集：`vod`/`live`/`search`/`fastsearch`/`history`/`collect`/`push`/`setting`/`drive`/`apps`/`hin2n`/`system`/`donate`。其中：
  - **`system`**：直接打开安卓系统设置（`Settings.ACTION_SETTINGS`），设备固件裁掉该入口时 Toast 提示「本机无系统设置入口」；用于酒店/门店场景把系统配置入口放进首页。
  - **`donate`**：打开 `DonateActivity` 捐助界面（§3.3），内容来自 json `donate` 段。
- 图标解析 `EntryDispatcher.resolveIcon()`：`icon` 留空时按入口语义自动选图——`wifi` 按当前网络类型（hm_wifi/hm_lan/hm_mobile/hm_wifi_no）、`styleSwitch` 按 `HOME_REC_STYLE`（hm_left_right/hm_up_down），其余用兜底图标；`styleSwitch` 执行后调 `HomeEntry.refresh()` 重建顶栏使图标状态同步。
- hin2n 状态染色对动态入口同样生效：动态生成的组网入口打 `tag="hin2n_state"`，样式应用后由 `HomeEntry.retintStateIcons()` 统一重染（防止 `iconColor` 为空时的 `clearColorFilter` 抹掉状态色），`HomeActivity`/`UserFragment` 收到 `ChangedEvent` 也会遍历重染。
- 旧版 `entries`：仅 `app` 类型、追加式（不隐藏任何东西），顶栏容器内展示；`topEntries` 非空时它不再生效。
- `app` 类型：`PackageManager.getLaunchIntentForPackage(pkg)` 直开；未安装且有 `url` → `ApkDownloader.download()`；图标支持 http(s) URL（OkHttp 加载）或内置 drawable 资源名（`hm_*`、`ic_theme`、`ic_hin2n` 等），空/加载失败有兜底图标。

### 3.5 自定义点播界面（type=vod / cv_ 源注入）

1. 入口 target 为接口地址（http(s)/assets/file），`EntryDispatcher` 把地址放入 intent extra 启动 `CustomVodActivity`；
   - 入口 `theme` 字段非空时：先 `ThemeManager.pushOverride(theme)`（解析为主题索引，无法识别则不生效）+ `BaseActivity.resetWallpaper()`，随后创建的 `CustomVodActivity` 及其播放页（Detail/Play）在覆盖期间使用独立主题（`ThemeManager.apply()/currentBgPair()/currentBgImage()` 均走 `activeIndex()`），全局 `THEME_SELECT` 不受影响；
   - 覆盖撤销时机：`CustomVodActivity.onPause(isFinishing)` 与 `onDestroy` 兜底（见下），另 `ThemeManager.apply()` 检测到 `HomeActivity` 创建时自动 `popOverride()`（视为回到 app 全局状态），三重防泄漏；
2. `onCreate` 读取并解析接口内容（http 请求拼本机参数；任何来源的内容都先过 `ConfigCrypto.maybeDecrypt` 支持密文，之后用标准 Gson 解析，与点播 api 同结构），对每个 `SourceBean` 的 key 加 `CUSTOM_SRC_PREFIX = "cv_"`（防与用户源重名冲突），调 `ApiConfig.injectCustomSources(list)` 内存注入，并用 `setHomeSourceTemp(cv源key)` 临时切首页位；
3. 界面本体就是 VodActivity 逻辑复用，仅源集合不同；切源弹窗选择后 `setHomeSourceTemp` + Toast「切换源：xxx」+ `reloadHome()`（getSort force=true 绕过缓存强制刷新）；
4. **恢复时序（关键）**：Activity finish 时系统顺序是 `CustomVod.onPause → HomeActivity.onResume → CustomVod.onStop → onDestroy`。若等 `onDestroy` 才恢复 home 源，主界面 `onResume` 已先读到 cv_ 临时源并把源名写到左上角（用户报障「影院切源后主屏左上文字变了」）。因此 `restoreHomeSource()`（`removeCustomSources` + `setHomeSourceTemp(prevHome)`，幂等，`homeRestored` 标记）在 `onPause(isFinishing=true)` 内立即执行，`onDestroy` 仅兜底；同时 `popOverride()` + `resetWallpaper()` 让主界面恢复全局主题背景——**不落盘、不污染用户配置**；进程被杀重启后自然恢复。

### 3.6 升级检查与 APK 下载安装管线

- 触发点：`HomeActivity` 启动后 `UpgradeChecker.check(activity)`（幂等）；版本信息优先 `upgrade.checkUrl` 网络获取（URL 拼本机参数、响应支持密文，与内置 `upgrade` 段同结构），无 checkUrl 用内联字段；
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

### 3.8 稳定性修复：进程级崩溃防护与显示规则（2026-10-17）

- **点播/首页 NPE 自动重启**：`ApiConfig.getHomeSourceBean()` 永不返回 null（未就绪时返回 `emptyHome`，key=""），`SourceViewModel.getSort()` 原代码 `getSource(sourceKey).getType()` 在 key 为空/源未加载时直接 NPE → 进程被杀 → 系统自动重启 app（用户表现「点击点播 app 自动重启」）。修复：`getSort()` 开头对 `getSource()` 判空，null 时 `sortResult.postValue(null)` 走正常空态展示。
- **n2n 连接自动重启 + 无日志**：`N2NService.onStartCommand` 链路中 `intent.getBundleExtra` 取值无空判、`vpn_session_name[version]` 无越界保护、native `startEdge()` 只被 `catch(Exception)` 保护（`UnsatisfiedLinkError` 是 Error，会穿透）——任一触发即杀进程，且 edge.log 尚未创建导致「无日志输出」。修复：整链 `try/catch(Throwable)` + `appendEdgeLog()` 把失败原因（含 lib 加载失败提示）写进 edge 日志文件，界面日志页直接可见；`stopEdge()`/`getEdgeStatus()` 同样 Throwable 防护（EventBus 订阅线程崩溃同样会杀进程）。
- **主屏左上源名显示规则**（`HomeActivity.applySourceName()`，2026-10-22 调整）：**固定显示**内置 JSON `home.sourceName` 文字（未配置回退 app 名称），**不再随点播接口切换/换源变化**。`initViewModel` 观察者与 `onResume` 两处统一走该方法。**2026-10-23：左上角名称取消切源入口与焦点**——`tvName` 在布局与代码中均置 `clickable=false / focusable=false`，移除点击/长按监听（切源仍可用 MENU 键或设置页）；首页容器不再用 LoadSir 遮罩初始加载，顶栏、入口行、公告在首帧一起渲染，历史一行不再等点播接口加载完成才出现。
- **全局未捕获异常防护**（2026-10-22）：[App.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/base/App.java) `initCrashHandler()` 在 onCreate 最前面安装 `Thread.setDefaultUncaughtExceptionHandler`——任何线程未捕获异常（含配置接口后主页刷新链路）把堆栈追加写 `filesDir/crash.log`（>512KB 自动重写），后台线程异常尽力 Toast「程序出现异常，即将自动重启」，随后 `killProcess` 自行结束进程，**不交给系统默认 handler，避免弹「tvbox结束运行」**。配套加固：`SourceViewModel.getSort()` type 0/1 的 `onSuccess` 解析（sortXml/sortJson）与 type 3 的 finally 解析块都包了 `try/catch(Throwable)`（解析失败回落 `postValue(null)`）；`HomeActivity` sortResult observer 与 loadConfig error 弹窗加 `isFinishing()/isDestroyed()` 守卫。
- **点播接口通知 / 公告条（2026-10-23）**：① 首页、点播、直播三处公告字号统一（见 §3.3 NoticeBar 行），`duration` 到期后同内容不再重新显示；② 新增顶层 `loadSuccessDuration`：点播接口首次加载成功时在首页顶部显示非模态提示（不抢焦点、带剩余秒数倒计时），到点自动移除，0=不显示（默认 3）；③ 新增顶层 `warningAutoClose`：点播接口 `warningText` 免责声明弹窗支持 N 秒后自动关闭（按钮显示「确定 (Ns)」倒计时），0=保持原行为必须手动确认（默认 0）；④ 新增 `home.recMovies`：首页自定义推荐影片，配置后不再请求豆瓣（见 §3.2）。

### 3.9 设为系统桌面（setAsLauncher / LauncherAlias）

- 清单：[AndroidManifest.xml](file:///f:/tvbox-n2n/app/src/main/AndroidManifest.xml) 内 `HomeActivity` 保持原 `LAUNCHER`/`LEANBACK_LAUNCHER` 过滤器不变；另加默认 `android:enabled="false"` 的 `activity-alias`（`.LauncherAlias`，target=HomeActivity，过滤器含 `HOME`+`DEFAULT`）。
- 开关：`RemoteConfig.applyLauncherComponent(ctx)` 用 `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)` 按 Hawk `rc_set_launcher` 启停别名；`bootstrap` 每次启动同步一次，远程 forceRemote 分支下发 `setAsLauncher` 时也立即同步。
- **出厂默认开启**：`launcherEnabled()` 缺省值为 `true`；json `setAsLauncher` 字段默认值写入走两轮守卫——首轮 `rc_set_launcher_default_set`（兼容旧版本标记），第二轮 `rc_set_launcher_default_set_v2` 对**已安装过的老设备**也补写一次默认值（解决老设备首轮标记已置位导致改默认不生效的问题）；两轮都只写一次，之后用户手动开关永不被覆盖。
- 启用后用户仍需在系统桌面选择器中确认（Android 不允许应用静默设为默认桌面）：`HomeActivity.onResume` 调 `RemoteConfig.ensureDefaultLauncher(this)`，开关已开但非默认桌面时每进程弹一次选择器；设置页切换开关时立即 `promptChooseLauncher`。
- 取消：停用别名后 Android 自动清除本应用的默认桌面资格。**取消时不再静默依赖系统回退**，而是走 [LauncherHelper.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/LauncherHelper.java) `restoreStockLauncher()`：后台枚举全部 `CATEGORY_HOME` 候选桌面（排除自身），主线程弹「选择桌面」列表（SelectDialog，默认选中 Hawk `rc_stock_launcher` 记忆的原桌面）；选定后有 root 时 `pm enable --user 0 <pkg>` + `pm set-home-activity <pkg>` 静默启用并设为默认桌面（开机自动启动），无 root 时用 `pm.getLaunchIntentForPackage` 拉起所选桌面并 Toast 引导选「始终」，兜底系统选择器 `RemoteConfig.promptChooseLauncher`。
- 注意 applicationId 随 flavor 变化（generic=`com.github.tvbox.osc.tk` / hisense=`com.github.hisense.osc.tk`），`ComponentName` 必须用 `ctx.getPackageName()` + 清单展开类名 `com.github.tvbox.osc.LauncherAlias`（别名 `android:name=".LauncherAlias"` 以源码包名展开，与 applicationId 无关）。

### 3.10 点播切源自动返回首页刷新

[VodActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/VodActivity.java) `showSiteSwitch()` 的 SelectDialog 点击回调：`dialog.dismiss()` → Toast「已切换到「xxx」，即将返回主界面刷新数据」→ 置 `HomeActivity.sRefreshOnResume=true` → `mHandler.postDelayed(finish(), 1500)`（带 `isFinishing/isDestroyed` 守卫）。`HomeActivity.onResume` 检测到标志位后调 `reloadHome()`（CLEAR_TASK 内部重启 + `forceSortRefresh=true` 绕过 sortCache 强刷当前源）。

### 3.11 接口加密协议（ConfigCrypto）

**本系统所有接收接口统一支持密文下发**：内容是 json（或 jsonc / 直播 txt）时直接解析，不是时先解密再加载；解密失败原样返回由上层解析兜底，**永不抛异常**。统一入口：`ConfigCrypto.maybeDecrypt(String)`。

#### 密文形态判定规则（按顺序）

1. trim 后以 `{` / `[` / `/` 开头，或含 `#EXTM3U` / `#genre#` → **明文**，原样返回；
2. 以 `2423` 开头 → TVBox 原生 CBC 加密格式（`ApiConfig.FindResult` 自行处理），不碰；
3. 不是纯小写 hex（`[0-9a-f]`）或长度 < 32 → 原样返回；
4. **ke 前缀模式**：总长为**奇数**且 ≥ 51 → `ke = s[0..19]`，`AES-128-ECB(s[19..], key=ke[0..16])`；
5. **固定密钥模式**：总长为**偶数**且秘钥非空 → `AES-128-ECB(s, key=秘钥前16字节)`；
6. 解密结果校验：trim 后以 `{`/`[` 开头或含 `#EXTM3U`/`#genre#` 才认为成功，否则返回原文。

> 判定基于「PHP `bin2hex` 输出必为小写 hex」「ke 19 字符 + 偶数 hex = 奇数总长」两个事实，与明文 json 不会混淆。

#### 秘钥自定义（两个修改位置）

| 位置 | 说明 |
|------|------|
| **Java 常量（编译期，主位置）** | [ConfigCrypto.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ConfigCrypto.java) 顶部 `public static String SECRET_KEY = "";`，填入秘钥（建议 ≥16 字符，实际取前 16 字节作 AES-128 key）后重新编译。**留空 = 仅支持 ke 前缀模式**（ke 前缀模式无需任何配置，密钥内嵌在密文里） |
| **json `secretKey` 字段（运行期覆盖）** | 内置/远程 json 顶层 `"secretKey": "你的秘钥"`，每次启动覆盖 Hawk `rc_secret_key`；**非空时优先于 Java 常量**，可用于不改代码换秘钥或远程统一下发 |

#### 两种模式 vs 服务端实现

**① ke 前缀模式（推荐，对应用户给定 PHP）**——密钥每次随机派生并内嵌在密文头部，App 端零配置：

```php
<?php
// ===== 服务端加密端完整示例（对接方直接使用） =====
function md5Hash($text) { return md5($text); }

function pkcs7Pad($data, $blockSize) {
    $pad = $blockSize - (strlen($data) % $blockSize);
    return $data . str_repeat(chr($pad), $pad);
}

function aesEncrypt($data, $key) {
    $key = substr($key, 0, 16);                       // AES-128 取前 16 字节
    $padded = pkcs7Pad($data, 16);
    return openssl_encrypt($padded, 'AES-128-ECB', $key, OPENSSL_RAW_DATA);
}

// 明文数据：点播 json / 直播 txt / 远程配置 json …原样字符串即可
// 可按 URL 参数（$_GET['mac'] 等，见 §3.12）按设备生成不同数据
$miniData = json_encode($data, JSON_UNESCAPED_UNICODE);

// 生成加密密钥（两个盐值可由对接方自行更换）
$ttime = time();
$key = md5Hash($ttime . "AD80F93B542B" . "8b14ecf6ae028f9b6d25fa547eabad85");
$ke = substr($key, 13);                               // 19 位小写 hex

// 加密输出：ke 前缀 + bin2hex(密文)
$encrypted = aesEncrypt($miniData, $ke);
$output = $ke . bin2hex($encrypted);
echo $output;                                         // 直接输出，Content-Type 任意
```

**② 固定密钥模式**——密钥不传输，两端约定同一秘钥（App 端 `SECRET_KEY` 常量或 json `secretKey`）：

```php
<?php
$SECRET = "AD80F93B542B8b14";   // 与 App 端 SECRET_KEY / json secretKey 一致
$miniData = json_encode($data, JSON_UNESCAPED_UNICODE);
echo bin2hex(aesEncrypt($miniData, $SECRET));   // 纯 hex（偶数长度），无 ke 前缀
```

#### 解密挂载点（7 处，全部 = 先 `maybeDecrypt` 再按原逻辑解析）

| # | 位置 | 接口 |
|---|------|------|
| 1 | `RemoteConfig.bootstrap()` | assets 内置配置 tvbox-config.json（**仅支持 ke 前缀模式**——解密发生在 `secretKey` 写入 Hawk 之前，固定密钥模式还读不到秘钥） |
| 2 | `RemoteConfig.fetchAndApply()` | 远程配置 `remoteConfigUrl`（缓存 `rc_remote_raw` 存**解密后明文**，与旧版缓存格式兼容） |
| 3 | `ApiConfig` FindResult else 分支 | 点播接口（用户/预置 `api` 地址；`2423` 原生 CBC 格式保持原逻辑不受影响） |
| 4 | `CustomVodActivity.applyCustomJson()` | 自定义点播接口（http/assets/file 三种来源统一过解密） |
| 5 | `LivePlayActivity.loadProxyLives()` | 直播接口（解密后交 `TxtSubscribe.parse`，支持 m3u/txt/json） |
| 6 | `LivePlayActivity` EPG 加载 | 节目单接口 |
| 7 | `UpgradeChecker` checkUrl 响应 | 升级检查接口 |

### 3.12 接口请求本机参数（DeviceParams）

**所有远程接口请求发起前统一过 `DeviceParams.append(url)`**，在 URL 查询串上拼接本机信息，服务端按参数（PHP `$_GET['mac']` 等）按设备下发数据：

| 参数 | 取值 |
|------|------|
| `mac` | 本机 MAC：优先 `eth0`/`wlan0`，其次遍历 `NetworkInterface` 第一个非回环/非虚拟/有硬件地址的网卡；格式化为冒号分隔大写；过滤全零与 `02:00:00:00:00:00` 占位地址；全失败兜底 ANDROID_ID；进程内缓存一次（Android 6+ `WifiInfo.getMacAddress` 恒返回占位地址，不可用） |
| `ip` | 本机第一个非回环 IPv4 地址（**每次请求现取**，跟随网络切换） |
| `model` / `brand` / `device` | `Build.MODEL` / `Build.BRAND` / `Build.DEVICE` |
| `sdk` / `release` | `Build.VERSION.SDK_INT` / `Build.VERSION.RELEASE`（系统版本） |
| `app` | 本应用 versionName |

规则：仅 `http(s)://` 拼接（`assets/`、`file://` 等本地路径原样）；URLEncoder 编码；URL 中已存在同名参数（`contains(key + "=")`）不重复添加；任何环节异常静默返回原 URL。

挂载点（6 处）：`RemoteConfig.fetchAndApply`（远程配置）、`ApiConfig.fetchConfig`（点播）、`CustomVodActivity.loadCustomConfig`（自定义点播 http 分支）、`LivePlayActivity.loadProxyLives`（直播）、`LivePlayActivity` EPG、`UpgradeChecker`（升级检查）。

服务端示例（PHP 按 MAC 下发）：

```php
$mac = isset($_GET['mac']) ? $_GET['mac'] : '';
$miniData = getMiniData($mac);   // 按设备查询/生成数据，再走 §3.11 加密输出
```

### 3.13 接口清空保存与直播入口独立加载（2026-10-22）

**根因**：原 [ApiDialog.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/dialog/ApiDialog.java) `inputSubmit` 里有 `if (!newApi.isEmpty())` 拦截点播接口保存 → 清空无法写空串；且 [RemoteConfig.applyEndpoint()](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/RemoteConfig.java) 与 [ApiConfig.parseJson()](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java) 在用户把接口清为空后，下次启动又从内置 JSON 回填旧地址 →「清空保存无效」。

**修复**（`_manual` 标记方案）：
1. `ApiDialog` 三个历史弹窗与 inputSubmit 保存前均调 `markManual(hawkKey)`（Hawk 写 `key + "_manual" = true`），**空串也写入**；
2. `RemoteConfig.applyEndpoint`：非 force 时若 `Hawk.get(hawkKey + "_manual", false)` 直接返回，JSON 永不回填；
3. `ApiConfig.parseJson` 四处 `StringUtils.isBlank(liveURL/epgURL)` 分支追加 `&& !Hawk.get(key + "_manual", false)`，手动清空后 JSON 的直播/EPG 地址不再覆盖，同时把 URL 设为空串，后续不加载。

**直播入口空列表修复**：
- 旧逻辑：只配直播接口、点播接口为空时，`ApiConfig.loadConfig` 直接 error「源地址为空」返回，导致 `liveChannelGroupList` 永远为空，`initLiveChannelList()` 提示「频道列表为空」→ finish。
- 修复：[LivePlayActivity.initLiveChannelList()](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/LivePlayActivity.java) 空列表分支改为：先读 `Hawk.get(HawkConfig.LIVE_URL, "")`，非空则 Base64 包装为 `http://127.0.0.1:9978/proxy?do=live&type=txt&ext=<b64>` 调 `loadProxyLives()` 独立加载直播列表（对齐 FongMi/TV「直播配置独立加载」思想）；仍为空才 toast「频道列表为空」+ finish。

### 3.14 直播 WebView 修复（2026-10-22）

参考 FongMi/TV 直播 WebView 的健壮性做法，重点修复切台闪退与全屏残留：

1. **切台 WebView destroy 顺序**（[LiveWebViewPlayer.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/util/live/LiveWebViewPlayer.java)）：原 `destroy()` 先 `loadUrl("about:blank")` / `destroy()` 再 `removeView` → 触发 `WebView.destroy() called while still attached to window` native 崩溃。修复：先 `exitCustomView()` 移除 H5 全屏残留视图 → `stopLoading()` → **先从 parent removeView** → 再 `loadUrl/removeAllViews/destroy`。
2. **H5 全屏残留**：`onShowCustomView` 把全屏视图挂在 `live_root` 上，切台前若未退出，残留到下一频道。修复：`destroy()` 开头即调 `exitCustomView()`。
3. **TV 适配**：`WebSettings` 新增 `setUseWideViewPort(true)` / `setLoadWithOverviewMode(true)` / `setSupportZoom(false)` / `setBuiltInZoomControls(false)` / `setDisplayZoomControls(false)`，防止页面在电视上缩放异常。
4. **错误回调与 API<24 兼容**：`WebViewClient` 补旧版 `shouldOverrideUrlLoading(WebView, String)`（API<24 不会走 WebResourceRequest 重载）；增加 `onReceivedError` 写日志，方便排查加载失败的网页直播源。

### 3.15 纯直播模式、首页入口即时加载与 EPG 本地缓存（2026-10-22）

**纯直播模式（无点播接口也可用）**：
- 旧逻辑：`ApiConfig.loadConfig` 在点播地址为空时直接 error「源地址为空」弹窗；取消后点直播，`LivePlayerManager.init → PlayerHelper.updateCfg → ApiConfig.getIJKCodec()` 时因 `ijkCodes` 只在点播配置解析成功后才初始化（为 null）→ NPE 闪退。
- 修复：[ApiConfig.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java) 构造函数即写入内置默认 IJK 配置（软解/硬解，`initDefaultIjkCodes()`），`getIJKCodec()` 加防御永不抛异常；`loadConfig` 点播地址为空但直播地址非空时回调 `error("-1")` 静默跳过（[HomeActivity](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/HomeActivity.java) 对 "-1" 直接标记初始化完成不弹窗）；点播/直播都未配置才弹「源地址为空」。点播配置加载成功后 `parseJson` 的 ijk 段整体替换内置默认。

**首页中间一行入口即时加载**：`HomeActivity.initView()` 末尾（setLoadSir 之前）立即 `commitNowAllowingStateLoss` 创建 `UserFragment`，不再等点播配置加载完成；`sortResult` 观察者只调 `UserFragment.updateHomeRec(list)` 增量更新推荐数据，不重建入口行。左上角 `tvName` 固定显示 `home.sourceName`（见 §3.8）。

**EPG 本地缓存与多天下发**（[LivePlayActivity](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/activity/LivePlayActivity.java) + [EpgCache.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/util/EpgCache.java)）：
- 显示天数：json `epgDays`（1~9，默认 3），日期列表以今天为中心生成（`initEpgDateView`），默认选中今天（修复旧版固定 9 天列表却选中索引 1 = 5 天前的问题）；
- 查询顺序：内存 `hsEpg`（key 统一 `频道名_yyyy-MM-dd`，不再用「昨天/星期x」文案防漂移）→ 磁盘 `EpgCache` → 网络；
- 网络请求附加 `days=N` 参数（两种 URL 形态 `?ch=&date=` 与 `{name}/{date}` 模板都附加）；服务端支持时一次返回窗口内多天（条目带 `"date":"yyyy-MM-dd"` 字段），`parseAndCacheEpg()` 按天拆分缓存；旧服务端忽略该参数按原单日格式返回，客户端兼容并触发 `prefetchEpgWindow()` 后台单线程串行预取（300ms 间隔）窗口内其余日期；
- `EpgCache` 存储：`filesDir/epg_cache/MD5(频道名).json`，有效期 = 历史日期永久、今天/未来仅拉取当天内；写入时顺带清理 10 天前旧数据；空结果也缓存（当天不再对无节目频道重复请求）；
- `sEpgFetching` 集合防止同一频道同一日期并发重复请求。

**阿里云播放器 License**：AliyunPlayer SDK 5.5+ 强制校验 License，未配置时 SDK 内部弹「配置信息初始化失败，请重启app」Toast（不影响播放）。json 新增 `aliLicenseKey`，`PlayerHelper.init()` 反射调 `AliPlayerGlobalSettings.setLicenseKey`（反射兼容旧版 SDK 无该类的情况，失败静默忽略）。

**Android 4.4（API 19）兼容**：`M3U8.removeMinorityUrl()` 的 `String.join`（Java 8/API 26+）改为 `TextUtils.join`（视频去广告默认开启，4.4 上原实现必 NoSuchMethodError 闪退）。

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

入口行本身（`tvUserHome` 默认卡 + 可配置动态卡）外层包了 `HorizontalScrollView`（无滚动条、不抢焦点、fillViewport=false）：入口过多超出屏宽时可**左右滑动/焦点右移自动横滚**，不再截断；`findViewById(R.id.tvUserHome)` 仍指向内层 LinearLayout，所有动态建卡逻辑无需改动。

## 5.1 首页冷启动优化

- `Hin2nCore.init()` 中的 9 个 n2n native 库（v1/v2s/v2/v3 两套 ABI）改为**后台线程异步预热**（`hin2n-load-libs` 线程），不再阻塞主线程 Application.onCreate；`N2NService.onCreate()` 里保留一次幂等的同步 `loadLibraries()` 兜底，保证真正拨号时库必然已加载。
- 播放器缓存清理（递归文件 IO）本就放后台线程；主题切换走 `switchTheme(act, useCache=true)`——`ApiConfig.loadConfig/loadJar` 全部命中磁盘缓存 + `SourceViewModel.sortCache` 静态 LRU 内存缓存，**只刷 UI 不重新拉取接口数据**（进程内重启 recreate 数据不丢）。

## 6. 两个开机启动开关

| 开关 | 存储 | 行为 |
|------|------|------|
| TVBox 开机启动 | Hawk `n2n_boot_start`（设置-内置配置卡片切换） | 开机拉起 HomeActivity（出厂默认开） |
| hin2n 开机启动 | SP `Hin2n/start_at_boot`（引导页切换） | 开机自动连 n2n VpnService（未授权过 VPN 则跳过） |

两者独立；同一个 BootReceiver 顺序执行，全部 best-effort（Android 10+ 后台启动限制）。
另有第三个相关开关「设为桌面」（Hawk `rc_set_launcher`，设置-内置配置卡片切换，出厂默认开）：不属于 BootReceiver，机制见 §3.9（activity-alias 启停）；开机自启 + 设为桌面同时打开可实现「开机即进 TVBox 桌面」。

## 7. Android 4.4（API19）黑屏修复

根因：API19 在**资源预加载阶段**解析含主题属性引用（`?attr/color_theme*`）的 drawable XML（vector tint、shape solid 等）会直接崩溃 → 黑屏。布局文件里的 `?attr/` 在 View 渲染期解析，不受影响，无需改。

方案（双版本 drawable）：

1. `res/drawable/`：**28 个**含 `?attr/` 的 drawable 全部替换为蓝色主题固定色兜底：
   `color_theme_dark_70→#B3106D9C`、`color_theme_80→#CC18A2E7`、`color_theme_70→#B318A2E7`、`color_theme_50→#8018A2E7`、`color_theme_40→#6618A2E7`、`color_theme→#FF18A2E7`；不含 `<ripple>`/elevation。
2. `res/drawable-v21/`：同名文件保留 `?attr/` + ripple 原版（其中 23 个是从 drawable 补拷的，5 个原本就是 v21 ripple 版，3 个 hin2n 新建），API21+ 继续随主题换色与水波纹。
3. `defaultConfig.vectorDrawables.generatedDensities = ['mdpi','hdpi','xhdpi','xxhdpi']`：为 legacy(minSdk19) 变体把矢量图生成 PNG（normal/python minSdk21 不生成）。
4. legacy 变体 appcompat 锁 1.6.1；`src/legacy/AndroidManifest.xml` 用 `tools:overrideLibrary` 放行 minSdk21 的三方库（nextlib 等）。

新增任何 drawable 时的检查清单：默认 `drawable/` 里**禁止** `?attr/`、`<ripple>`、`<vector>` 的 `android:tint="?attr/…"`；需要主题效果就在 `drawable-v21/` 放同名增强版。

**4.4 主题换色运行时方案**（2026-10-22，[ThemeDrawableFix.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ThemeDrawableFix.java)）：drawable/ 硬编码蓝色导致 4.4 下不随主题换色，而 `?attr` 又不能写回 XML（预加载崩溃）。修复：API<21 时 `BaseActivity.onCreate`（setTheme 后）调 `ThemeDrawableFix.apply(ctx)`——把 23 个含主题蓝的非 vector drawable 资源（selector/layer-list/shape）的**共享 ConstantState** 里 `RGB=18A2E7` 的颜色**就地替换**为当前主题色（保留 alpha；GradientDrawable 反射 `mSolidColors` 判色后 `setColor`，StateListDrawable/LayerDrawable/ClipDrawable 递归子 drawable）。刻意不 `mutate()`：常量状态进程级共享，之后 inflate 的列表项/弹窗自动跟随；静态 `sAppliedRgb` 记录已写入色，支持主题多次切换连续替换。vector 不在此列（legacy 变体经 generatedDensities 出 PNG / AppCompat 走 VectorDrawableCompat，保持蓝色兜底）。另 `button_dialog_vod.xml` 常态由 80% 蓝改为透明（与 v21 版对齐，修复「图标背景不透明」）。

## 8. CI（test.yml / hin2n.yml）

### test.yml（TVBox 主工程）
1. Checkout → 打时间 tag → 版本号/关于框注入、crosswalk maven 源；
2. **Compress Source**：tar 包含 `hin2n/`（独立 App 源码随包发布，符合 GPL）；
3. Python 3.8 依赖 + pyramid 适配（删 buildPython、锁 pyquery 2.0.2）；
4. 注入签名（TVBoxOSC.jks，base64 配置块）；
5. setup-java 17；
6. `./gradlew :app:assembleRelease --build-cache --parallel --daemon --warning-mode all`，输出 tee 落盘，失败时 grep `error:` 汇总到日志尾部；
7. 产物：Artifact `takagen99-Box`（12 个 APK + 源码包 + Release Note）；定时为每天 06:59 UTC，也可手动触发并勾选「构建后不提交发布新版」。

### hin2n.yml（hin2n 独立 App）
- push 到 `main` 且改动 `hin2n/**` 或该工作流文件时触发，也支持手动触发（含 `donotpublish` 输入）；
- 步骤：Checkout → Build Tag → Release Apk Sign 注入 → setup-java 17 → Install NDK and CMake（`ndk;25.2.9519653`、`cmake;3.22.1`）→ `./gradlew :hin2n:assembleRelease` → 产物 `hin2n_<tag>.apk` + `hin2n-latest.apk` 上传 Artifact → 发布到固定 tag `hin2n-latest` 的 Release（`allowUpdates: true`）。

kapt/runner 偶发抖动先直接 rerun；hin2n native 失败看 `externalNativeBuild*` 任务的 CMake/Clang 错误行。

## 9. 本地构建要点

- JDK17（不要用更高）；SDK 需 platform android-34、build-tools（AGP 默认找 30.0.3，本机可用目录 junction 指向已装的新版）；
- 编含 hin2n 的完整 APK 需要 NDK r25b + CMake 3.22.1；只验证 app 的 Java/资源可单跑 `:app:processXxxResources` / `:app:compileXxxJavaWithJavac`；
- `local.properties` 写 `sdk.dir`（已 gitignore）；
- ProGuard：`-keep class com.github.tvbox.osc.n2n.** { *; }`，另注意 greenDAO/EventBus/native JNI 相关类的 keep 规则。

## 10. 配置示例

- [tvbox-config.example.jsonc](file:///f:/tvbox-n2n/docs/tvbox-config.example.jsonc)：带中文注释的全量 demo（推荐照此修改 assets 文件；含加密下发 secretKey、捐助 donate、系统设置/捐助入口等全部对接字段）
- [tvbox-config.example.json](file:///f:/tvbox-n2n/docs/tvbox-config.example.json)：纯 JSON 版（无注释）
- hin2n 组网使用/字段说明：[hin2n.md](file:///f:/tvbox-n2n/docs/hin2n.md)
