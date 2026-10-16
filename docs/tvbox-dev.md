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
- App 启动 `App.onCreate` 第一步 `RemoteConfig.bootstrap(this)` **同步**读取并应用（文件仅几 KB）。

### 3.2 字段与覆盖规则

| 字段 | 落盘规则 |
|------|----------|
| `notice`（字符串或样式对象）/`home`/`tkp2p`/`entries`/`hin2nEntry`/`checkInterval`/`ua` | 每次启动用内置 JSON 覆盖缓存值 |
| `theme` / `themes` | 每次启动应用（`ThemeManager.updateThemes`）；`theme` 支持序号/中文名/英文 alias |
| `api` / `live` / `epg` / `proxy` | **仅当用户从未在 TVBox 内手动配置过该接口**（Hawk 值为空）时写入默认值；手动配置后永不覆盖，四个接口互不影响 |
| `bootStart`（TVBox 开机自启） | 只作默认值写入**一次**（Hawk `n2n_boot_default_set` 标记），之后以用户开关为准 |
| `hin2n.bootStart` / `hin2n.staticRoutes` / `hin2n.configs` | **仅首启且组网数据库为空时播种一次**（`Hin2nSeeder`），之后完全以组网界面里的用户操作为准 |

`version` / `apkUrl` / `hin2nUrl` 三个旧字段已废弃（远程更新与外部 hin2n APK 下载均不存在）。

### 3.3 关键类（`com.github.tvbox.osc.n2n` 包）

| 类 | 职责 |
|----|------|
| [RemoteConfig.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/RemoteConfig.java) | 读 assets、parseLoose、apply/applyEndpoint；`fetchAuto/fetch/tryPendingUpdate` 保留空壳（立即成功回调），调用方无需改动；同时持有 Hawk 键常量 |
| [Hin2nSeeder.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nSeeder.java) | 首启播种 hin2n 配置；字段别名 `ipMode/fromSupernode`、`localIP/localIpAuto`、`acceptMulticast/dropMulticast`(反义) |
| [Hin2nEntryState.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nEntryState.java) | 常驻 EventBus 订阅 hin2n 5 个运行事件，折叠为 IDLE/CONNECTING/CONNECTED/ERROR 四态，供入口图标染色（拨号中=黄/已连接=绿/失败=红/未拨号=原色）；变化时 post `ChangedEvent` |
| [HomeEntry.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/HomeEntry.java) | 首页：hin2n 固定入口（内置模块直进，`applyStateTint` 染色）、`entries` 第三方 App 入口栏（仅已安装可开，未装静默）、公告条、外观/主题应用；定时 tick 只做界面刷新 |
| [NoticeBar.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/NoticeBar.java) | 公告渲染（样式、时段、过期、滚动速度、home/live/vod 显隐） |
| [BootReceiver.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/BootReceiver.java) | BOOT_COMPLETED：hin2n 自连 + 按开关拉起首页 |

设置页：[ModelSettingFragment.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/fragment/ModelSettingFragment.java) 的 `initRemoteConfig()` 只绑定 `llRcBoot`（Hawk `n2n_boot_start`）；布局 `res/layout/fragment_model.xml` 与 `res/layout-v21/fragment_model.xml` 中的卡片标题为「内置配置」。

## 4. hin2n 入口与 TV 界面

- 首页「历史」入口行新增第 9 张卡片 `tvHin2nHome`（「组网」，图标 `hm_hin2n`），点击进 `Hin2nActivity`；可见性取 `RemoteConfig.hin2nEntryEnabled()`。
- 首页顶栏保留 `tvHin2n` 图标（`HomeEntry` 绑定），同样直接进内置模块，不再做外部包检测/下载。
- 三个 Activity（均 `exported=false`、横屏、无桌面图标）：
  - `n2n.ui.Hin2nActivity` —— 引导页（连接/状态/日志/配置管理/新建/Ping/开机开关）；
  - `n2n.ui.Hin2nConfigListActivity` —— 配置列表；
  - `n2n.ui.Hin2nConfigEditActivity` —— 28 字段表单。
- 界面颜色随 TVBox 主题：布局直接用 `?attr/color_theme*` 与主题 colorAccent；自定义控件样式 `Hin2nLabel/Hin2nInput/Hin2nCheck`（[styles.xml](file:///f:/tvbox-n2n/app/src/main/res/values/styles.xml)），勾选框同时给了 `android:buttonTint` 与 appcompat `buttonTint` 保证 API19 白色着色。

## 5. 首页入口行随内容列表滚动

「我的」页 [fragment_user.xml](file:///f:/tvbox-n2n/app/src/main/res/layout/fragment_user.xml) 根布局是单个 ScrollView，入口行（`tvUserHome` 9 张卡）与其下的 `tvHotListForGrid`、`tvHotListForLine` 是同一个垂直 LinearLayout 的子 View，结构上天然一起滚动；遥控器焦点移动到屏外时，[UserFragment.installScrollFollow()](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/ui/fragment/UserFragment.java) 通过全局焦点监听按焦点 View 的屏幕坐标 `smoothScrollTo`（上下各留 24dp margin），保证 D-pad 浏览时入口行也会被带出/带回视口。

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
