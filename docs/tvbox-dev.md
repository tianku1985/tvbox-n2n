# TVBox 开发使用文档

> 基于 [takagen99/Box](https://github.com/takagen99/Box)（commit `258a5fe`）内化，已烘焙 5 项定制功能。  
> 仓库地址：`tianku1985/tvbox-n2n`，根目录即 Box 源码。

---

## 1. 仓库结构

```
tvbox-n2n/
├── app/                    # TVBox 主模块（takagen99/Box 源码）
├── pyramid/                # Python 引擎模块（Chaquopy）
├── quickjs/                # QuickJS 引擎模块
├── xwalk/                  # Crosswalk WebView 离线包（4 个 zip，~104MB）
├── gradle/                 # Gradle wrapper
├── hin2n/                  # hin2n 独立 App（见 hin2n-dev.md）
├── docs/                   # 文档
├── .github/workflows/      # CI 工作流
│   ├── test.yml            # TVBox 构建工作流
│   ├── hin2n.yml           # hin2n 构建工作流
│   └── TVBoxOSC.jks        # 签名证书（别名 TVBoxOSC，密码 TVBoxOSC）
├── build.gradle            # 根构建脚本
├── settings.gradle
└── gradle.properties
```

## 2. 定制功能一览

| # | 功能 | 入口 | 涉及文件 |
|---|------|------|----------|
| 1 | 开机启动开关 | 设置 → 远程配置 | `BootReceiver.java` `ConfigFragment.java` |
| 2 | 首页自定义入口 | 首页顶栏 entryBar | `HomeEntry.java` `RemoteConfig.java` `activity_home.xml` |
| 3 | 远程 JSON 配置 | 设置 → 远程配置 | `RemoteConfig.java` `ConfigFragment.java` |
| 4 | 底部滚动公告 | 首页底部 | `HomeEntry.java` `activity_home.xml` |
| 5 | hin2n 固定入口 | 首页顶栏 tvHin2n | `HomeEntry.java` `activity_home.xml` |

## 3. 远程配置接口

### 3.1 JSON 结构

所有字段可选，缺省保持本地现状：

```json
{
  "notice": "首页底部滚动公告文本",
  "bootStart": true,
  "hin2nEntry": true,
  "entries": [
    {
      "name": "hin2n",
      "pkg": "wang.switchy.hin2n",
      "icon": ""
    },
    {
      "name": "文件管理",
      "pkg": "com.android.documentsui",
      "icon": "https://example.com/icon.png"
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `notice` | string | 公告文本，空或缺失则隐藏公告栏 |
| `bootStart` | boolean | 开机启动默认值；仅在远端首次下发时写入，之后以用户开关为准 |
| `hin2nEntry` | boolean | 是否在首页显示 hin2n 入口图标 |
| `entries` | array | 自定义入口列表，每项含 `name`/`pkg`/`icon` |
| `entries[].name` | string | 入口名称（用于图标 description） |
| `entries[].pkg` | string | 目标 App 包名 |
| `entries[].icon` | string | 图标 URL，空则用 `hm_drawer` 兜底图标 |

### 3.2 覆盖规则

- `notice`、`entries`、`hin2nEntry`：每次拉取成功即覆盖
- `bootStart`：仅在远端首次下发时作为默认值写入（`K_BOOT_REMOTE_SET` 标记），之后以用户手动开关为准

### 3.3 拉取时机与限频

- **自动拉取**：`HomeEntry.refresh()` 在 `onCreate`/`onResume` 时触发 `fetchAuto()`，限频 5 分钟
- **手动拉取**：设置 → 远程配置 → 「立即同步」调用 `fetch()`，不受限频

### 3.4 配置接口地址

在 **设置 → 远程配置 → 配置接口** 中输入 JSON URL，保存后自动立即拉取一次。  
地址存储在 Hawk key `rc_url`，默认为空（不拉取）。

## 4. 代码架构

### 4.1 包结构

```
com.github.tvbox.osc.n2n/
├── RemoteConfig.java    # 远程配置：拉取、缓存、读取
├── HomeEntry.java        # 首页扩展：hin2n 入口、自定义入口栏、公告
├── BootReceiver.java    # 开机自启广播接收器
```

### 4.2 关键类说明

#### RemoteConfig

- `K_URL` / `K_NOTICE` / `K_ENTRIES` / `K_HIN2N` / `K_BOOT`：Hawk 键名常量
- `url()` / `notice()` / `hin2nEntryEnabled()` / `bootStartEnabled()`：读取缓存值
- `entries()`：返回 `List<Entry>`，解析 `rc_entries` JSON 数组
- `fetchAuto(ctx, cb)`：限频自动拉取（5 分钟间隔）
- `fetch(ctx, cb)`：立即拉取（不受限频）
- `apply(ctx, json)`：解析 JSON 并落盘（私有方法）

#### HomeEntry

- `HIN2N_PKG = "wang.switchy.hin2n"`：hin2n 包名常量
- `refresh(Activity)`：绑定视图 + 渲染缓存配置 + 后台拉取最新配置；幂等，可在 `onCreate`/`onResume` 重复调用
- `populate(Activity)`：按缓存重建公告与入口栏（私有方法）
- `launchApp(Activity, pkg, name)`：按包名拉起 App（私有方法）

#### BootReceiver

- 监听 `BOOT_COMPLETED`，检查 `RemoteConfig.bootStartEnabled()`，为 true 时拉起 `HomeActivity`
- 在 `AndroidManifest.xml` 中注册

#### ConfigFragment

- `BaseLazyFragment` 子类，布局 `fragment_config`
- 三个配置行：开机启动开关 / 配置接口地址 / 立即同步
- 在 `SettingActivity` 中以 "远程配置" 标签页注册

### 4.3 布局锚点

在 [activity_home.xml](file:///f:/tvbox-n2n/app/src/main/res/layout/activity_home.xml) 中：

| View ID | 用途 |
|---------|------|
| `tvN2n` | 远程配置入口图标（跳转设置页远程配置 Tab） |
| `tvHin2n` | hin2n 固定入口图标 |
| `entryBar` | 自定义入口容器（LinearLayout，HomeEntry 动态填充 ImageView） |
| `tvNotice` | 底部滚动公告（TextView，marquee 模式） |

### 4.4 HomeActivity 集成点

- L103：`private ImageView tvN2n;` 字段声明
- L170：`this.tvN2n = findViewById(R.id.tvN2n);` 绑定
- L172：`HomeEntry.refresh(this);` 首次刷新
- L347：`tvN2n.setOnClickListener(...)` 点击跳转设置页（带 `n2n_go=true` extra 直达远程配置 Tab）
- `onResume`：`super.onResume()` 后调 `HomeEntry.refresh(this)` 刷新

### 4.5 SettingActivity 集成点

- `sortList` 添加 "远程配置"
- `fragments` 添加 `ConfigFragment.newInstance()`
- `setCurrentItem`：当 `n2n_go` extra 为 true 时直达末页（远程配置）

### 4.6 AndroidManifest 注册

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
...
<receiver android:name=".n2n.BootReceiver" android:enabled="true" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## 5. CI 构建

### 5.1 触发方式

| 方式 | 说明 |
|------|------|
| 定时 | 每天 06:59 UTC 自动触发 |
| 手动 | Actions → Test → Run workflow，可勾选「构建后不提交发布新版」 |

### 5.2 构建步骤（test.yml）

1. Checkout（fetch-depth: 0）
2. 打 Tag：`YYYYMMDD-HHMM`
3. Extra Modify：注入版本号、Crosswalk maven 源
4. 压缩源码包：`sourceCode-<tag>.tar.xz`
5. Python 3.8 + 依赖安装
6. pyramid 模块适配（删 buildPython 行、锁 pyquery 2.0.2）
7. 注入签名配置（base64 编码的 signingConfig 块，读 `RELEASE_*` 环境变量）
8. JDK 17 + Gradle 构建：`./gradlew assembleRelease`
9. APK 重命名：`TVBox_takagen99_<tag>.apk`
10. Release 发布（含源码包）+ Artifact 上传
11. Telegram 推送（如配置了 BOT_TOKEN）

### 5.3 产物

- **Artifact**：`takagen99-Box`（含 APK + 源码包 + Release Note）
- **Release**：tag = `<日期时间>`，assets 含 APK 和源码包

### 5.4 签名

- 证书：`.github/workflows/TVBoxOSC.jks`
- 别名：`TVBoxOSC`
- 密码：`TVBoxOSC`
- 环境变量：`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`（在 CI 的 `gradle.properties` 中注入）

## 6. 本地构建

### 6.1 环境要求

- JDK 17（Temurin）
- Android SDK（compileSdk 由 app/build.gradle 指定）
- Python 3.8+（pyramid 模块依赖，需安装 `lxml ujson pyquery requests jsonpath cachetools pycryptodome beautifulsoup4`）

### 6.2 步骤

```bash
cd f:\tvbox-n2n
# 注入签名配置（可选，debug 构建不需要）
# 修改 app/build.gradle 添加 signingConfig，或设置环境变量
./gradlew assembleRelease
```

APK 输出路径：`app/build/outputs/apk/release/`

## 7. ProGuard 规则

`app/proguard-rules.pro` 中已添加：

```
-keep class com.github.tvbox.osc.n2n.** { *; }
```

确保 `RemoteConfig`、`HomeEntry`、`BootReceiver` 等类不被混淆。

## 8. 配置示例

参考 [tvbox-config.example.json](file:///f:/tvbox-n2n/docs/tvbox-config.example.json)。
