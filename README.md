# TVBoxOSC (tvbox-n2n)

![Build](https://shields.io/github/actions/workflow/status/tianku1985/tvbox-n2n/test.yml?branch=main&logo=github&label=Build)

## Credits
This repo relies on the following third-party projects:
- [CatVodTVOfficial/TVBoxOSC](https://github.com/CatVodTVOfficial/TVBoxOSC)
- [takagen99/Box](https://github.com/takagen99/Box) (Vendored: 258a5fef61578869ae905ca230bdde9e99fc19a8)
- [switch-iot/hin2n](https://github.com/switch-iot/hin2n)（dev 分支，已模块化为内置 library）
- [ntop/n2n](https://github.com/ntop/n2n) / meyerd n2n（native 源码已实体化，GPL-3.0）

## 结构
本仓库直接内置全部源码，GitHub Actions 只编译本仓库内容（不再克隆上游）：
- 根目录：[takagen99/Box](https://github.com/takagen99/Box) 源码
- `hin2n/`：hin2n 源码（n2n 子模块已实体化），以 Gradle library 模块 `:hin2n` 随主工程编译，**不再单独出 APK**

## 定制功能
- **hin2n 组网内置**：首页「历史」入口行的「组网」卡片（与下方列表一起滚动）或顶栏图标进入 TV 引导页，遥控器管理 n2n 配置（28 字段与原版一致）、连接/日志/Ping/开机自连；界面随 TVBox 主题变色
- **配置全部内置 JSON**：所有配置写在 `app/src/main/assets/tvbox-config.json`（支持注释），APK 内自带一份；已去除远程地址配置/网络拉取，需要定制改源码重新编译；开机启动保留（TVBox 自启与 hin2n 自连是两个独立开关）
- 首页自定义入口、底部滚动公告、全局主题/外观、接口默认值均可在 JSON 配置
- 静态路由：组网配置编辑页按「目标网段 网关IP」每行一条填写，VPN 建立时逐条注入
- **Android 4.4 兼容版**：`legacy` flavor（minSdk19），drawable 双版本 + 矢量图转 PNG，修复 4.4 黑屏

## 文档
- [TVBox 使用说明（用户手册）](docs/usage.md)
- [hin2n 使用说明（组网）](docs/hin2n.md)
- [TVBox 开发文档](docs/tvbox-dev.md)
- [hin2n 模块开发文档](docs/hin2n-dev.md)
- [内置配置全量 demo（带注释）](docs/tvbox-config.example.jsonc) / [纯 JSON 版](docs/tvbox-config.example.json)

## 构建
JDK17、Android SDK compileSdk34、NDK 25.2.9519653、CMake 3.22.1：

```powershell
.\gradlew.bat assembleArm64GenericLegacyDebug   # Android 4.4 兼容版
.\gradlew.bat assemblerelease                   # 全部 12 个变体
```

说明：n2n 与 hin2n 均为 GPL-3.0 协议。
