# Hin2n <img height="24" src="doc/pic/logo.png">

[README](README.md) | [中文文档](README_zh.md)

n2n 是一个支持内网穿透 p2p 的 VPN 项目，最初由 ntop.org 大神 `Luca Deri` <deri@ntop.org>，`Richard Andrews` <andrews@ntop.org> 开发并开源的项目，后由 [`meyerd`](https://github.com/meyerd) 大神继续做优化工作。我们的目的是为 n2n 提供 **手机版本** 的支持。

[![gradle](https://img.shields.io/badge/gradle-2.14.1-green.svg?style=plastic)](https://docs.gradle.org/2.14.1/userguide/userguide.html)
[![API](https://img.shields.io/badge/API-15%2B-green.svg?style=plastic)](https://android-arsenal.com/api?level=15)
[![license](https://img.shields.io/github/license/switch-iot/hin2n.svg?style=plastic)](https://www.gnu.org/licenses/gpl-3.0)
[![GitHub release](https://img.shields.io/github/release/switch-iot/hin2n/all.svg?style=plastic)](https://github.com/switch-iot/hin2n/releases)
[![Github All Releases](https://img.shields.io/github/downloads/switch-iot/hin2n/total.svg?style=plastic)](https://github.com/switch-iot/hin2n/releases)
[![Travis branch](https://img.shields.io/travis/switch-iot/hin2n/dev_android.svg?style=plastic)](https://travis-ci.org/switch-iot/hin2n)

原版的 n2n 支持很多平台，包括 Windows，Linux，OSX，BSD，OpenWrt，Raspberry Pie 等，唯独缺少对手机（非root）的支持。因此，我们开发了 Hin2n 项目。

### Hin2n 是什么

- Hin2n 是支持 n2n 协议的手机 VPN 软件

- 该 APP 不需要 root 手机

- 该 APP 暂时只支持安卓手机，后续会发开 iPhone 版本

- 该项目现处于持续开发阶段，后续会提供更完善的功能

- 该项目现已支持全部 v1/v2s/v2/v3 协议

### Hin2n 最新版本 [CHANGELOG](Hin2n_android/CHANGELOG_zh)

Hin2n 最新版本可在 [Release 地址](https://github.com/switch-iot/hin2n/releases) 下载。

### Hin2n 开发计划

详细开发计划请见 [`Projects`](https://github.com/switch-iot/hin2n/projects)。
大家如果有新需求和想法，任何意见建议均可提交在 [Issues](https://github.com/switch-iot/hin2n/issues) 中，我们将会酌情安排开发计划。您的关注就是我们的动力。

### 技术原理

- VPNService

> Hin2n 基于安卓原生提供的 VPNService，通过 VPNService 建立 tun 虚拟网卡，与 supernode 和 edge 通讯。

- tun2tap

> 安卓上层仅支持建立 tun 虚拟网卡，仅是 TCP/IP 网络层，而 n2n 协议依赖 tap 虚拟网卡，需要对数据链路层的支持，因此我们模拟了数据链路层，并实现了 ARP 协议。

- n2n protocol

> Hin2n 对 n2n 协议的支持是采用 jni 的方式，native 方法可以尽量复用原 n2n 项目的代码。

## n2n 协议版本

n2n 项目现有四个主流版本

- ntop.org 大神们维护的 v1 版本，不再更新，项目地址：[github.com/switch-iot/n2n\_meyerd/v1](https://github.com/switch-iot/n2n_meyerd/tree/master/n2n_v1)

- meyerd 大神维护的 v2s 版本，不再更新，项目地址：[github.com/switch-iot/n2n\_meyerd/v2s](https://github.com/switch-iot/n2n_meyerd/tree/master/n2n_v2)

- ntop.org 大神们维护的 v2 版本，已被封存，项目地址：[github.com/ntop/n2n/v2.8\_r540](https://github.com/ntop/n2n/tree/2.8-stable)

- ntop.org 大神们维护的 v3 版本，正在更新，项目地址：[github.com/ntop/n2n](https://github.com/ntop/n2n)

### 关于 v2s 版本

v2s 版本是N2N交流QQ群(5804301)中对 meyerd 大神维护的v2版本(又称v2.1)的命名，即v2升级版，该版本与 ntop.org 大神们维护的v2版本并不互通，为避免混淆，群友们对该项目另行命名。

## 项目开发/编译说明

### Hin2n 在 Linux 系统下的编译方法

你需要先安装好 Java 和 Android SDK。

- `git clone https://github.com/switch-iot/hin2n.git --depth=1 --recurse-submodules` # 下载源码

- `cd hin2n/Hin2n_android` # Hin2n\_android 目录即是 hin2n 项目安卓源码目录

- `./gradlew assemble` # 开始编译（你也可以使用 `./gradlew assembleNormalAllarchDebug` 来编译其中一个文件）。如果你要使用 Android Studio 来编译，请选择 Import Project，选择 Hin2n\_android 目录，然后再选择编译 `app`。编译好的文件在 `hin2n/Hin2n_android/app/build/outputs/apk/`

- 切换分支时，需要执行 `git submodule update` 来同步 submodule 的代码

### Windows 系统下的编译方法

你需要先安装好 Java 和 Android SDK，并设置 git 兼容符号链接。

- 打开 `gpedit.msc`，将当前账户加入设置 `计算机配置/Windows 设置/安全设置/本地策略/用户权限分配/创建符号链接` 中。

- 或以管理员用户运行 `git-cmd`，并执行下述命令

- `git clone -c core.symlinks=true https://github.com/switch-iot/hin2n.git --depth=1 --recurse-submodules && cd hin2n && link.bat`

- `cd Hin2n_android` # Hin2n\_android 目录即是 hin2n 项目安卓源码目录

- `gradlew assemble` # 开始编译（你也可以使用 `gradlew assembleNormalAllarchDebug` 来编译其中一个文件）。如果你要使用 Android Studio 来编译，请选择 Import Project，选择 Hin2n\_android 目录，然后再选择编译 `app`。编译好的文件在 `hin2n\Hin2n_android\app\build\outputs\apk\`

- 切换分支时，需要执行 `git submodule update` 来同步 submodule 的代码

### 关于开源协议

该项目以 [`GPLv3`](LICENSE) 协议进行开源，与 n2n 原有开源协议保持一致，也希望大家支持并遵守本项目的开源协议。

## 为 Hin2n 做贡献

Hin2n 是一个免费且开源的 n2n 项目，我们欢迎任何人为其开发和进步贡献力量。

- 在使用过程中出现任何问题，可以通过 [`Issues`](https://github.com/switch-iot/hin2n/issues) 来反馈

- Bug的修复可以直接提交 `Pull Request` 到 `android_dev`分支

- 如果是增加新的功能特性，请先创建一个 [`Issues`](https://github.com/switch-iot/hin2n/issues) 并做简单描述以及大致的实现方法，提议被采纳后，就可以创建一个实现新特性的 Pull Request

- 欢迎对说明文档做出改善，帮助更多的人使用 Hin2n，特别是英文文档

- 如果您觉得 Hin2n 对您有帮助，欢迎您关注该项目，并给项目点个 `Star`！

### 鸣谢

- [`zhangbz`](https://github.com/zhangbz)

- [`emanuele-f`](https://github.com/emanuele-f)

- [`ozyb`](https://github.com/ozyb)

- [`lucktu`](https://github.com/lucktu)

- 感谢他们的无私奉献，同时也感谢广大网友对 Hin2n 项目的支持。

## 交流群

- Hin2n交流群：769731491 (QQ群号)

- N2N交流群：256572040 (QQ群号)

<br />

你的问题不是缺网关配置，而是 hin2n 的两个关键开关没开。hin2n 配置页本身就有以下选项：

## 需要在配置页设置的

配置项 设置值 作用 允许路由 (allowRouting) ✅ 勾选 允许 edge 转发非本机 IP 的包（等价 n2n`-r` ）。 不开启则所有目的地不是 10.0.0.114 的包都被丢弃 丢弃组播 (dropMuticast) ❌ 关闭 让 ARP 广播帧能跨越 n2n 隧道（等价 n2n`-E` ）。 开启则 ARP 被丢弃，跨子网无法解析 MAC 静态路由 `192.168.8.0/24` 我们已添加的补丁，把匹配该网段的流量导入 TUN 网关 IP (gatewayIp) 留空 这是"全局代理"模式（把 0.0.0.0/1 + 128.0.0.0/1 都导入 n2n），不是你要的

代码在 N2NService.java L76-81 ：gatewayIp 非空时添加`0.0.0.0/1` +`128.0.0.0/1` 抢占默认路由，是给"全部流量走 n2n"的场景用的，不适用于你的跨子网场景。

## 对端也需要设置

对端配置 设置值 hin2n 的 allowRouting ✅ 勾选 hin2n 的 dropMuticast ❌ 关闭 操作系统 IP 转发 开启（Linux:`sysctl -w net.ipv4.ip_forward=1` ） 对端在 192.168.8.0/24 有 IP 有网卡直接连该网段

## 流量路径

Plain Text

1
2
3
4
5
6
7

`本机 10.0.0.114 → VpnService addRoute("192.168.8.0",24) → 包进 TUN → edge 读到包，allowRouting=1 允许转发 → 通过 n2n 隧道发给对端 10.0.0.13 → 对端 edge 收到，allowRouting=1 允许转发 → 对端内核 ip_forward=1 → 从物理网卡发出 → 192.168.8.1 收到并回复`

简言之： 勾选 allowRouting + 关闭 dropMuticast ，静态路由填`192.168.8.0/24` ，gatewayIp 留空。对端也要做同样配置。
