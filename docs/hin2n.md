# hin2n 打包集成

hin2n 源码（[switch-iot/hin2n](https://github.com/switch-iot/hin2n) dev 分支）已内化到本仓库 `hin2n/` 目录：

- 原 git 子模块（n2n_meyerd / n2n_ntop_v2 / n2n_ntop_v3）已按锁定 commit 实体化到
  `Hin2n_android/app/src/main/cpp/` 下（n2n_v1 / n2n_v2s / n2n_v2 / n2n_v3），
  不再依赖 `git clone --recursive`。
- 以下功能补丁已直接烘焙进源码：
  - 配置页新增「静态路由」（`activity_add_item.xml` + `SettingDetailsActivity`），
    多条 CIDR 空格分隔，存 SharedPreferences `Hin2n/hin2n_static_routes`；
    `N2NService` 在 VPN establish 前逐条 `addRoute`。
  - 主界面侧边栏新增「Ping」（`activity_main.xml` / `activity_main_land.xml` +
    `MainActivity`），后台执行 `ping -c 4 -W 2` 并弹窗展示结果。
  - `app/build.gradle` release 签名改读 `RELEASE_STORE_FILE` /
    `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`
    环境变量。

## 触发方式

Actions 页面选择 **hin2n** → Run workflow（仅手动触发）。
工作流直接用仓内 `hin2n/Hin2n_android` 构建，不再克隆上游。

## APK 下载

- Artifacts：`hin2n-apk`
- Release：tag 为 `hin2n-<run_number>`
