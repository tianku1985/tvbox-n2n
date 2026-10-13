# hin2n 开发使用文档

> 基于 [switch-iot/hin2n](https://github.com/switch-iot/hin2n) dev 分支内化，n2n 子模块已实体化。  
> 已烘焙 3 项定制功能。独立 App，包名 `wang.switchy.hin2n`。

---

## 1. 目录结构

```
hin2n/
└── Hin2n_android/
    ├── app/
    │   ├── build.gradle                          # 构建脚本（签名读环境变量）
    │   ├── TVBoxOSC.jks                          # CI 时从 workflows 拷入
    │   └── src/main/
    │       ├── cpp/                              # n2n C 源码（子模块实体化）
    │       │   ├── n2n_v1/                       # meyerd/n2n_v1 (f9308ec)
    │       │   ├── n2n_v2/                       # ntop/n2n v2 (53afd3c)
    │       │   │   ├── include/
    │       │   │   └── src/
    │       │   ├── n2n_v2s/                      # meyerd/n2n_v2 (f9308ec)
    │       │   ├── n2n_v3/                       # ntop/n2n v3 (0177e14)
    │       │   │   ├── include/
    │       │   │   └── src/
    │       │   ├── slog/                         # 简单日志库
    │       │   ├── tun2tap/                      # TUN↔TAP 桥接
    │       │   └── uip/                          # uIP 协议栈
    │       ├── java/wang/switchy/hin2n/
    │       │   ├── activity/
    │       │   │   ├── MainActivity.java         # 主界面（+Ping 补丁）
    │       │   │   └── SettingDetailsActivity.java # 配置页（+静态路由补丁）
    │       │   └── service/
    │       │       └── N2NService.java            # VPN 服务（+静态路由注入补丁）
    │       ├── jniLibs/                          # 预编译 .so（OpenSSL 等）
    │       │   ├── arm64-v8a/
    │       │   ├── armeabi-v7a/
    │       │   ├── x86/
    │       │   └── x86_64/
    │       └── res/layout/
    │           ├── activity_main.xml             # 主界面布局（竖屏，+Ping 项）
    │           ├── activity_main_land.xml        # 主界面布局（横屏，+Ping 项）
    │           └── activity_add_item.xml         # 配置详情页（+静态路由输入框）
    ├── gradle/
    ├── build.gradle
    ├── gradlew
    └── local.properties                          # CI 生成（sdk.dir / ndk.dir）
```

## 2. 子模块来源

原 hin2n 使用 3 个 git 子模块，已按锁定 commit 实体化到 `cpp/` 下，不再依赖 `git clone --recursive`：

| 目录 | 来源仓库 | 锁定 commit |
|------|----------|-------------|
| `cpp/n2n_v1/` | `meyerd/n2n_v1` | `f9308ec` |
| `cpp/n2n_v2s/` | `meyerd/n2n_v2` | `f9308ec` |
| `cpp/n2n_v2/` | `ntop/n2n` (v2 tag) | `53afd3c` |
| `cpp/n2n_v3/` | `ntop/n2n` (v3.1.1 tag) | `0177e14` |

`n2n_v2` 和 `n2n_v3` 只内化了 `include/` + `src/` + `COPYING`（CMakeLists.txt 只引用这两目录）。

## 3. 定制功能

### 3.1 静态路由

**用途**：VPN 建立时自动注入多条静态路由，让指定网段走 VPN 隧道。

**UI**：配置详情页（`activity_add_item.xml`）新增「静态路由」输入框，位于 DNS 服务器行之后。

**输入格式**：多条 CIDR，空格分隔，如 `192.168.0.0/24 10.8.0.0/16`

**存储**：SharedPreferences `Hin2n` → key `hin2n_static_routes`

**涉及文件**：

| 文件 | 修改内容 |
|------|----------|
| [activity_add_item.xml](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/res/layout/activity_add_item.xml) | 新增 `til_static_routes` 输入行，`til_mac_addr` 的 `layout_below` 改为引用它 |
| [SettingDetailsActivity.java](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/java/wang/switchy/hin2n/activity/SettingDetailsActivity.java) | 新增 `mStaticRoutes` 字段 + 绑定 + 回填；`btn_save`/`btn_modify` 保存时 `putString().apply()` |
| [N2NService.java](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/java/wang/switchy/hin2n/service/N2NService.java) | DNS 配置块前插入：读 `hin2n_static_routes`，按空格分割，解析 CIDR，逐条 `builder.addRoute(network, prefix)` |

**注入逻辑**（N2NService）：

```java
// 读取静态路由配置
SharedPreferences sp = getSharedPreferences("Hin2n", MODE_PRIVATE);
String routesStr = sp.getString("hin2n_static_routes", "");
if (!routesStr.isEmpty()) {
    String[] routes = routesStr.trim().split("\\s+");
    for (String route : routes) {
        String[] parts = route.split("/");
        if (parts.length == 2) {
            String network = maskToNetwork(parts[0], Integer.parseInt(parts[1]));
            builder.addRoute(network, Integer.parseInt(parts[1]));
        }
    }
}
```

> **注意**：`VpnService.addRoute()` 要求地址是网络地址（主机位全零），传 host IP 会抛 `IllegalArgumentException: Bad address`。代码中用 `maskToNetwork()` 辅助方法先清零主机位。

### 3.2 Ping 测试

**用途**：从 hin2n 主界面直接 ping 对端 IP，验证 VPN 连通性。

**UI**：主界面侧边栏（`activity_main.xml` / `activity_main_land.xml`）在「关于」项前新增「Ping」菜单项。

**涉及文件**：

| 文件 | 修改内容 |
|------|----------|
| [activity_main.xml](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/res/layout/activity_main.xml) | `rl_about` 前插入 `rl_ping` 菜单项（`icon_update` 图标 + "Ping" 文本） |
| [activity_main_land.xml](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/res/layout/activity_main_land.xml) | 同上 |
| [MainActivity.java](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/src/main/java/wang/switchy/hin2n/activity/MainActivity.java) | `rl_about` 绑定前插 `rl_ping` 绑定；`initLeftMenu()` 前插入 `showPingDialog()` / `doPing()` 两方法 |

**执行逻辑**：

```java
// showPingDialog(): 弹出输入框让用户输入 host
// doPing(host): 后台执行 Runtime.exec("ping -c 4 -W 2 " + host)
//   host 校验：禁空格/;/&/| 防注入
//   结果用 AlertDialog 展示
```

### 3.3 Release 签名

**修改文件**：[app/build.gradle](file:///f:/tvbox-n2n/hin2n/Hin2n_android/app/build.gradle)

原版硬编码签名路径，改为读环境变量：

```groovy
release {
    if (System.getenv("RELEASE_STORE_FILE")) {
        signingConfig signingConfigs.myConfig
    }
}
```

CI 中通过环境变量注入：`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`。

## 4. CI 构建

### 4.1 触发方式

仅手动触发：Actions → hin2n → Run workflow。

### 4.2 构建步骤（hin2n.yml）

1. Checkout
2. JDK 8（AGP 4.0.0 + Gradle 6.3 不兼容高版本 JDK）
3. 安装 Android SDK 组件：`platforms;android-28` `build-tools;28.0.3` `cmake;3.6.4111459` `ndk;21.4.7075529`
4. 设置 `ANDROID_NDK_HOME` / `ANDROID_NDK_ROOT`
5. 准备签名：拷入 `TVBoxOSC.jks`，写 `local.properties`（`sdk.dir` / `ndk.dir`）
6. Gradle 构建：`./gradlew assembleNormalAllarchRelease`
   - flavor: `channel=normal` × `arch=allarch`
   - 环境变量：`UMENG_APPKEY=dummy` `BUGLY_APPID=dummy`（绕过上游门控）
7. 上传 Artifact：`hin2n-apk`
8. 发布 Release：tag = `hin2n-<run_number>`

### 4.3 产物

- **Artifact**：`hin2n-apk`（多架构 APK）
- **Release**：tag = `hin2n-<run_number>`

### 4.4 签名

复用 TVBox 的证书：

| 配置项 | 值 |
|--------|-----|
| 证书文件 | `.github/workflows/TVBoxOSC.jks` |
| 别名 | `TVBoxOSC` |
| 密码 | `TVBoxOSC` |
| 环境变量 | `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` |

## 5. 本地构建

### 5.1 环境要求

- JDK 8（必须，AGP 4.0.0 + Gradle 6.3 不兼容 JDK 11+）
- Android SDK，compileSdk 28
- NDK 21.4.7075529
- CMake 3.6.4111459

### 5.2 步骤

```bash
cd f:\tvbox-n2n\hin2n\Hin2n_android

# 写 local.properties
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties
echo "ndk.dir=<你的 NDK 路径>" >> local.properties

# 设置签名环境变量（可选，debug 构建不需要）
export RELEASE_STORE_FILE=./TVBoxOSC.jks
export RELEASE_STORE_PASSWORD=TVBoxOSC
export RELEASE_KEY_ALIAS=TVBoxOSC
export RELEASE_KEY_PASSWORD=TVBoxOSC

# 构建
./gradlew assembleNormalAllarchRelease
```

APK 输出路径：`app/build/outputs/apk/normal/allarch/release/`

### 5.3 注意事项

- 上游 `build.gradle` 有 `gradle.taskGraph.beforeTask` 门控，要求 `UMENG_APPKEY` 和 `BUGLY_APPID` 非空，本地构建需设置哑值：
  ```bash
  export UMENG_APPKEY=dummy
  export BUGLY_APPID=dummy
  ```
- `local.properties` 必须写 `ndk.dir`，否则 CMake 找不到 NDK
- `pyquery==2.0.2` 锁定仅影响 TVBox 的 pyramid 模块，与 hin2n 无关

## 6. n2n 版本说明

hin2n 编译 4 个 n2n 版本的 C 源码为 .so，通过 JNI 在进程内运行 edge：

| 版本 | 目录 | 协议特点 |
|------|------|----------|
| v1 | `n2n_v1/` | 早期协议，兼容老 supernode |
| v2s | `n2n_v2s/` | meyerd 分支的 v2 变体 |
| v2 | `n2n_v2/` | ntop 官方 v2 |
| v3 | `n2n_v3/` | ntop 官方 v3（3.1.1），当前默认 |

用户在 hin2n 配置页选择 n2n 版本，App 编译对应版本的 .so 并加载。

## 7. 协议

hin2n 和 n2n 均为 GPL-3.0 协议。
