# hin2n 模块开发文档

> hin2n（基于 [switch-iot/hin2n](https://github.com/switch-iot/hin2n) dev 分支，n2n 子模块实体化）在本仓库中已从「TVBox 内置 library」拆分为**独立可安装 App `:hin2n`**（applicationId `wang.switchy.hin2n`），由独立的 GitHub Actions 工作流 `hin2n.yml` 构建并发布到固定 tag `hin2n-latest`；TVBox 主工程（`:app`）不再依赖 `:hin2n`，通过 Intent/包名拉起或按 `hin2n.apkUrl` 下载安装。
> 用户使用说明见 [hin2n.md](file:///f:/tvbox-n2n/docs/hin2n.md)。

---

## 1. 模块定位与分层规则

```
┌─────────────────────────────── :app (TVBox, com.github.tvbox.osc.tk) ───────────────────────────────┐
│  n2n/ 包：TV 引导页 UI、播种、开机广播、RemoteConfig 内置配置、首页入口                              │
│      │  通过 PackageManager/Intent 检测并拉起独立 hin2n App；未安装时按 hin2n.apkUrl 下载            │
└──────┼──────────────────────────────────────────────────────────────────────────────────────────────┘
       │ （无编译期依赖，完全解耦）
┌──────▼────────────────────────────── :hin2n (wang.switchy.hin2n, com.android.application) ──────────┐
│  Hin2nCore（门面/初始化/SP/DB/开机自连） · N2NService（VpnService） · n2n native(.so via CMake)       │
│  model(N2NSettingModel/N2NSettingInfo/EdgeCmd/EdgeStatus) · storage(greenDAO) · tool · event(EventBus)│
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**硬性规则**：

1. `:hin2n` **不能引用任何 `com.github.tvbox.**` 类**（依赖方向单向，且两者无模块依赖）。它保留原 hin2n 的包名 `wang.switchy.hin2n`，资源 `R` 也走该包。
2. `appcompat` 在模块中是 `implementation 'androidx.appcompat:appcompat:1.6.1'`（独立 App，自行携带依赖）。
3. 模块有独立 `applicationId "wang.switchy.hin2n"` 与条件化签名配置（`project.hasProperty('RELEASE_STORE_FILE')` 时注入）；TVBox 主工程的 12 个变体不再与本模块联动。
4. 原独立 App 的组件（Umeng/Bugly 统计、应用更新、dataBinding、BaseRecyclerViewAdapterHelper）已整体删除；TV 引导页 Hin2nActivity 作为唯一入口 Activity。

## 2. 目录结构

```
hin2n/Hin2n_android/app/
├── build.gradle                       # com.android.library，compileSdk34/minSdk19，
│                                      #   ndkVersion 25.2.9519653，CMake 3.22.1
├── CMakeLists.txt                     # 编译 4 版本 n2n edge + 链接预编译 OpenSSL
└── src/main/
    ├── AndroidManifest.xml            # 仅 7 条权限 + N2NService（BIND_VPN_SERVICE + VpnService filter）
    ├── jniLibs/<abi>/libcrypto.so     # 预编译 OpenSSL（arm64-v8a/armeabi-v7a/x86/x86_64）
    ├── cpp/
    │   ├── n2n_v1/  n2n_v2s/  n2n_v2/  n2n_v3/      # 实体化的 n2n C 源码（见 §6）
    │   ├── slog/  tun2tap/  uip/
    │   └── edge_jni.c                                 # JNI 桥：startEdge/stopEdge/getEdgeStatus
    ├── java/wang/switchy/hin2n/
    │   ├── Hin2nCore.java             # 门面：init/loadLibraries/DAO/SP/currentSetting/startOnBoot/崩溃日志
    │   ├── service/N2NService.java    # VpnService：建隧道、静态路由注入、通知、状态事件
    │   ├── model/                     # EdgeCmd（参数拼装/校验）、EdgeStatus、N2NSettingInfo(Parcelable)
    │   ├── storage/db/base/           # greenDAO 实体与生成代码（model/N2NSettingModel 28 字段实体、
    │   │                               #   DaoMaster/DaoSession/N2NSettingModelDao，已实体化）
    │   ├── event/                     # EventBus 事件：Start/Stop/Error/SupernodeDisconnect/LogChange…
    │   └── tool/                      # IOUtils、N2nTools、ThreadUtils、LogFileObserver
    └── res/                           # strings/colors/arrays(vpn_session_name, encryption_modes)、
                                       #   mipmap(ic_launcher + 4 个通知状态图)
```

app 侧集成代码：

| 文件 | 职责 |
|------|------|
| [App.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/base/App.java) | `onCreate`：`RemoteConfig.bootstrap` → `Hin2nCore.init` + `installCrashLogger` → `Hin2nSeeder.seed` |
| [Hin2nCore.java](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/java/wang/switchy/hin2n/Hin2nCore.java) | 模块门面：库初始化、native 库加载、DAO 会话、SP 常量、当前配置、开机自连、崩溃落盘 |
| [Hin2nSeeder.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/Hin2nSeeder.java) | 首启（`dao.count()==0`）把内置 JSON 的 `hin2n` 段播种进 greenDAO 与 SP |
| [n2n/ui/Hin2nActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ui/Hin2nActivity.java) | TV 引导页：连接/状态/日志/配置管理/新建/Ping/开机开关 |
| [n2n/ui/Hin2nConfigListActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ui/Hin2nConfigListActivity.java) | 配置列表（选中/编辑/删除） |
| [n2n/ui/Hin2nConfigEditActivity.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/ui/Hin2nConfigEditActivity.java) | 28 字段编辑表单 + 静态路由（`Hin2nCheck/Hin2nInput/Hin2nLabel` 样式随主题） |
| [BootReceiver.java](file:///f:/tvbox-n2n/app/src/main/java/com/github/tvbox/osc/n2n/BootReceiver.java) | `BOOT_COMPLETED`：先 hin2n 自连，再按 TVBox 开关拉起首页 |

布局：`activity_hin2n.xml`、`activity_hin2n_list.xml`（+列表项 `item_hin2n_config.xml`）、`activity_hin2n_edit.xml`，shape/button 资源统一前缀 `hin2n_`/`shape_hin2n_`。

## 3. 运行时数据流

```
点「连接」
  Hin2nActivity → VpnService.prepare()（首次弹授权）
                → startForegroundService(N2NService, extra=N2NSettingInfo)
N2NService.onStartCommand
  → Builder() 建 VpnService 隧道（地址/路由/静态路由/DNS，见 §4）
  → EdgeCmd(info, fd, logPath) 拼命令行参数 + LogFileObserver 监听日志
  → native startEdge(cmd)：加载 edge_v<ver>.so，JNI 起 edge 线程
状态回传：native → EdgeStatus → EventBus(主线程) → 引导页刷新 UI/通知栏
```

- `N2NService.INSTANCE`：单例引用，UI 直接调 `stop(Runnable)`（回调在主线程 executor 执行）、`getCurrentStatus()`。
- 状态五值：`DISCONNECT / CONNECTING / CONNECTED / SUPERNODE_DISCONNECT / FAILED`。
- 日志：`getExternalFilesDir("log")/<vpn_session>.log`，`IOUtils.readTxt(LIMIT)` 给 UI 分页读。

## 4. 关键定制点（相对上游 hin2n）

1. **静态路由注入**（[N2NService.java](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/java/wang/switchy/hin2n/service/N2NService.java) `EstablishVpnService`）：读 SP `Hin2n/hin2n_static_routes`，按空白（空格/换行）分词，含 `/` 的是 CIDR、其后是可选网关 IP，逐条 `Builder.addRoute`；`gatewayIp` 非空时加 `0.0.0.0/1`、`128.0.0.0/1` 走全局。
2. **默认超节点**：编辑页超节点留空时由 `Hin2nSeeder`/UI 按版本补：v1 `hin2n.nton.eu.org:10082`，v2 `:10086`，v2s `:10088`，v3 `supernode.ntop.org:7777`。
3. **PendingIntent 标志**：API23+ 一律带 `FLAG_IMMUTABLE`，通知渠道由 `Hin2nCore.init` 预建（id 取 `R.string.notification_channel_id_default`），适配 Android 13/14。
4. **启动 Intent**：通知栏/开机均用 `getLaunchIntentForPackage(getPackageName())`，不再硬编码 MainActivity。
5. **开机自连**：`Hin2nCore.startOnBoot(ctx)`——开关 `Hin2n/start_at_boot` 打开且存在选中配置且此前已授权 VPN 时，由 `BootReceiver` 直接起服务（不依赖拉起页面）。

## 5. 内置 JSON 播种（出厂配置）

hin2n 独立 App 的出厂配置位于 `hin2n/Hin2n_android/app/src/main/assets/hin2n-config.json`
（不再是 tvbox 的 tvbox-config.json——拆分后组网配置完全由 hin2n App 自维护）：

```jsonc
{
  "bootStart": false,             // 开机自动连接（SP start_at_boot）
  "staticRoutes": "",             // "192.168.8.0/24 10.0.0.13 ..."
  "configs": [ { /* 28 字段 */ } ]
}
```

- **仅首启播种一次**（数据库为空时）；卸载重装/清除数据后重新播种；用户后续修改永不被覆盖。
- 字段别名（`Hin2nSeeder.parseConfig` 兼容）：
  - `ipMode: 1` ≡ `"fromSupernode": true`；
  - `"localIP": "auto"` ≡ `"localIpAuto": true`；
  - `"acceptMulticast": false` ≡ `"dropMulticast": true`（反义）。
- 28 字段构造顺序必须与 `N2NSettingModel` 构造器（L248 起）/`N2NSettingInfo(Model)` 严格一致，改字段时三处同步。

## 6. n2n native 来源与构建

| 目录 | 来源 | commit |
|------|------|--------|
| `cpp/n2n_v1/` | meyerd/n2n_v1 | `f9308ec` |
| `cpp/n2n_v2s/` | meyerd/n2n_v2 | `f9308ec` |
| `cpp/n2n_v2/` | ntop/n2n（v2 tag，仅 include/src/COPYING） | `53afd3c` |
| `cpp/n2n_v3/` | ntop/n2n（v3.1.1，仅 include/src/COPYING） | `0177e14` |

- CMake 按所选版本编译对应 edge 源码 + `edge_jni.c`，链接 `src/main/jniLibs/${ANDROID_ABI}/libcrypto.so`（预编译 OpenSSL，头文件在 cpp include 路径）。
- 工具链固定：**NDK `25.2.9519653`（r25b）+ CMake `3.22.1`**，模块 build.gradle 与 CI 都写死，不要随意升级（n2n v1 老代码对新 Clang 敏感）。
- 产物 `libedge_v1.so / libedge_v2.so / libedge_v2s.so / libedge_v3.so` 由 `System.loadLibrary` 按配置版本加载；app 只过滤 `armeabi-v7a`、`arm64-v8a` 两个 ABI（x86/x86_64 的预编译库存档保留，便于模拟器调试时改 abiFilters）。

## 7. 本地构建

### 7.1 环境

- JDK 17（Gradle 7.5 / AGP 7.4.2；不要用 JDK 21 跑 Gradle）
- Android SDK：platforms `android-34`
- NDK `25.2.9519653`、CMake `3.22.1`（SDK Manager 安装同名版本）
- Python 3.8+（仅 python flavor 需要）

### 7.2 常用命令

```powershell
# 仓库根目录执行（不是 hin2n/Hin2n_android！模块由根 settings.gradle 以 projectDir 引入）
.\gradlew.bat :hin2n:assembleDebug                         # 只编模块（产出 aar）
.\gradlew.bat :hin2n:assembleDebug -Phin2nOnly             # 同上且跳过 :app/:quickjs/:pyramid 配置（CI 同款）
.\gradlew.bat assembleArm64GenericLegacyDebug              # Android 4.4 兼容版（arm64）
.\gradlew.bat assembleArmeabiGenericNormalRelease          # 常规 armv7 release
.\gradlew.bat assemblerelease                              # CI 同款：全部 12 个变体
```

变体命名：`<abi><brand><mode>`，abi∈{armeabi, arm64}，brand∈{generic, hisense}，mode∈{normal, python, legacy}。
APK 名 `TVBox_<buildType>-<flavors>.apk`（normal 在文件名中显示为 java）。

只想验证 Java/资源而不装 NDK 时可只跑资源与编译任务：

```powershell
.\gradlew.bat :app:processArm64LegacyDebugResources :app:compileArm64LegacyDebugJavaWithJavac
```

### 7.3 greenDAO

DAO 生成代码已实体化在 `storage/db/base/`，**不要加 greendao gradle 插件**，运行时依赖 `org.greenrobot:greendao:3.2.0` 即可。确需重新生成时用独立的 greendao-generator 工程跑一次再覆盖。

## 8. CI

hin2n 已拆分为独立 App，拥有独立工作流：

- [.github/workflows/hin2n.yml](file:///f:/tvbox-n2n/.github/workflows/hin2n.yml) 是 hin2n 唯一构建入口。`push` 到 `main` 且改动 `hin2n/**` 或该工作流文件时触发，也可手动触发；setup-java 17 → Install NDK and CMake（`ndk;25.2.9519653`、`cmake;3.22.1`）→ `./gradlew :hin2n:assembleRelease -Phin2nOnly`（settings.gradle 据此只配置 `:hin2n`，跳过 `:app`/`:quickjs`/`:pyramid`——`:pyramid` 的 Chaquopy 评估需读根目录 `local.properties`，独立构建环境没有该文件）→ 产物 `hin2n_<tag>.apk` 上传 Artifact，同时以固定名 `hin2n-latest.apk` 发布到固定 tag `hin2n-latest` 的 Release（`allowUpdates: true`）。
- [.github/workflows/test.yml](file:///f:/tvbox-n2n/.github/workflows/test.yml) 只构建 `:app`（TVBox），不再安装 NDK/CMake，也不再处理 hin2n 的签名与 APK。
- CI 失败排查顺序：先看日志尾部脚本汇总的 `error:` 行 → native 失败看 `externalNativeBuild...` 任务的 CMake/Clang 报错 → kapt 偶发 runner 故障直接 rerun。

## 9. Android 4.4（API19）兼容要点

- 模块 minSdk 19；`appcompat` 用 `implementation 1.6.1`（独立 App，自行携带运行时）。
- app 侧所有 drawable 采用 **drawable/（固定色兜底，禁 `?attr/`、禁 ripple）+ drawable-v21/（主题色/ripple 原版）双版本**；legacy 变体还开了 `vectorDrawables.generatedDensities` 生成 PNG。详见 [tvbox-dev.md](file:///f:/tvbox-n2n/docs/tvbox-dev.md) §7。
- hin2n 自己的资源（shape_hin2n_* 等）在创建时就只用固定色/`@color`，不带主题属性引用。
- 通知/PendingIntent 按版本分支，API23 才加 `FLAG_IMMUTABLE`。

## 10. 协议

GPL-3.0（n2n、hin2n 原生要求）。
