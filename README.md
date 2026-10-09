# TVBoxOSC

![Build](https://shields.io/github/actions/workflow/status/o0HalfLife0o/TVBoxOSC/test.yml?branch=master&logo=github&label=Build)
[![Channel](https://img.shields.io/badge/Follow-Telegram-blue.svg?logo=telegram)](https://t.me/TVBoxOSC)
[![Download](https://img.shields.io/github/v/release/o0HalfLife0o/TVBoxOSC?color=orange&logoColor=orange&label=Download&logo=DocuSign)](https://github.com/o0HalfLife0o/TVBoxOSC/releases/latest) 
[![Total](https://shields.io/github/downloads/o0HalfLife0o/TVBoxOSC/total?logo=Bookmeter&label=Counts&logoColor=yellow&color=yellow)](https://github.com/o0HalfLife0o/TVBoxOSC/releases)

## Credits
This repo relies on the following third-party projects:
- [CatVodTVOfficial/TVBoxOSC](https://github.com/CatVodTVOfficial/TVBoxOSC)
- [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) (Updated: ab11d289e09963a9daf65ca7f6b7a9a8cbe184e1)
- [takagen99/Box](https://github.com/takagen99/Box) (Updated: 258a5fef61578869ae905ca230bdde9e99fc19a8)

## N2N 组网
本仓库在构建时会把 [ntop/n2n](https://github.com/ntop/n2n) 的 `edge` 客户端静态打包进 APK（v3.1.1，使用改写的 `tuntap_android.c` 在 Android `VpnService` 提供的 TUN 设备之上模拟 TAP，内置 ARP 缓存与应答），并在「设置 → 设置其他 → N2N 组网」中提供配置与连接界面。

使用说明：
1. 打开 TVBox → 设置 → 设置其他 → N2N 组网
2. 填写服务器（`host:port`）、社区、密钥、虚拟 IP 与子网掩码（各端 IP 需唯一）
3. 点击「连接」，首次会弹出 VPN 授权，允许后即可组网（仅转发虚拟子网，不劫持全部流量）
4. 支持 MAC 自动生成、MTU 与额外路由（如 `192.168.8.0/24`，多个以空格或逗号分隔）

说明：两端需同一社区/密钥，且服务器能公网互通；默认不带 root 依赖。n2n 与 hin2n 均为 GPL-3.0 协议。
