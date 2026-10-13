# TVBoxOSC

![Build](https://shields.io/github/actions/workflow/status/o0HalfLife0o/TVBoxOSC/test.yml?branch=master&logo=github&label=Build)
[![Channel](https://img.shields.io/badge/Follow-Telegram-blue.svg?logo=telegram)](https://t.me/TVBoxOSC)
[![Download](https://img.shields.io/github/v/release/o0HalfLife0o/TVBoxOSC?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/o0HalfLife0o/TVBoxOSC/releases/latest) 
[![Total](https://shields.io/github/downloads/o0HalfLife0o/TVBoxOSC/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/o0HalfLife0o/TVBoxOSC/releases)

## Credits
This repo relies on the following third-party projects:
- [CatVodTVOfficial/TVBoxOSC](https://github.com/CatVodTVOfficial/TVBoxOSC)
- [takagen99/Box](https://github.com/takagen99/Box) (Vendored: 258a5fef61578869ae905ca230bdde9e99fc19a8)
- [switch-iot/hin2n](https://github.com/switch-iot/hin2n) (dev 分支，已内化到 `hin2n/`)

## 结构
本仓库直接内置全部源码，GitHub Actions 只编译本仓库内容（不再克隆上游）：
- 根目录：[takagen99/Box](https://github.com/takagen99/Box) 源码（已烘焙首页扩展与远程配置功能）
- `hin2n/`：[switch-iot/hin2n](https://github.com/switch-iot/hin2n) 源码（n2n 子模块已实体化，已烘焙静态路由与 Ping 补丁）

## TVBox 定制功能
- 开机启动开关（「设置 → 设置其他」页「配置地址」下方的「远程配置」卡片）
- 首页自定义入口：远程 JSON 接口可配置多个，点击拉起指定 App；未安装时弹窗提示并给出接口下发的下载地址
- 所有默认配置从 JSON 接口读取（公告 / 入口 / 开机启动默认值 / hin2n 及入口下载地址）
- 首页底部滚动公告（酒店 IPTV 桌面风格）
- 首页固定 hin2n 入口图标（未安装时提示安装）

## hin2n 定制功能
- 配置页新增「静态路由」：支持多条 CIDR（空格分隔），VPN 建立时逐条注入
- 主界面侧边栏新增「Ping」：后台执行 `ping -c 4` 并弹窗展示结果

说明：n2n 与 hin2n 均为 GPL-3.0 协议。
