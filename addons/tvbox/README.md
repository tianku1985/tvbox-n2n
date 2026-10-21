# TVBox for MacCMS —— 独立插件

零侵入：**不修改任何 MacCMS 核心文件**，删掉两个文件即完全恢复。

---

## 一、安装（2 步）

1. 把 `addons/tvbox/` 整个目录复制到 MacCMS 根目录的 `addons/` 下
2. 把 `tvbox_app.php` 复制到 MacCMS 根目录（与 `api.php` 同级）

完成。**不需要执行 SQL**，首次访问会自动建表。

> 可选：把 `application/admin/controller/Tvbox.php` 一起复制，即可在 MacCMS 后台左侧菜单里
> 直接打开插件（见第六节）。

---

## 二、管理后台

访问：

```
http://你的域名/tvbox_app.php/admin/index
```

- 默认密码：`tvbox888`（可在「配置中心」修改）
- 若不支持 PATH_INFO，用：`http://你的域名/tvbox_app.php?r=admin/index`

后台功能：

| 菜单 | 说明 |
|------|------|
| 总览 | 数据统计 + 所有接口地址一览 |
| 点播站点 | 增删改查（标识/名称/类型/接口/jar/分类/风格/超时/playerType/开关） |
| 直播源 | 增删改查（M3U / TXT / Spider + EPG/logo/ua/css/groups/ext） |
| 解析器 | VIP 解析地址管理（含 flag 与请求头 header） |
| 过滤规则 | M3U8 Host / 正则 / filter / exclude / script |
| 广告/Flag | 广告域名拦截、VIP flag 列表 |
| TVBox 配置 | config.json 全部顶层配置项逐项编辑（按分组单独保存）+ ijk 参数 |
| 配置中心 | global / notice / home / upgrade / 管理密码 |
| 演示数据 | 一键安装完整演示数据 |
| 接口预览 | 所有接口地址，点击即可访问 |

---

## 三、TVBox 接口

| 接口 | 地址 |
|------|------|
| 心跳 | `/tvbox_app.php/api/ping` |
| 分类 | `/tvbox_app.php/api/vod?ac=class` |
| 列表 | `/tvbox_app.php/api/vod?ac=list&page=1&limit=20&t=101` |
| 详情 | `/tvbox_app.php/api/vod?ac=detail&ids=10001` |
| 搜索 | `/tvbox_app.php/api/vod?ac=search&wd=流浪` |
| 多站点配置 | `/tvbox_app.php/api/sites` ← **TVBox 配置地址填这个** |
| 远程配置 | `/tvbox_app.php/api/config` |
| 直播 JSON | `/tvbox_app.php/api/live` |
| 直播 M3U | `/tvbox_app.php/api/live?format=m3u` |

无 PATH_INFO 时统一改成 `?r=api/xxx`，例如 `/tvbox_app.php?r=api/sites`。

### TVBox 客户端配置

打开 TVBox / 影视 App → 设置 → 配置地址，粘贴：

```
http://你的域名/tvbox_app.php/api/sites
```

---

## 四、演示数据

后台「演示数据」页点击「立即安装演示数据」，或直接访问该页触发。包含：

- **11 个分类**：电影 / 电视剧 / 综艺 / 动漫 / 纪录片 / 短剧 / 体育 / 教育 / 音乐 / 游戏 / 其他（`type_id` 101-111）
- **14 部影片**：`vod_id` 10001-10014，写入 MacCMS 原生 `mac_vod` 表，带海报、评分、播放地址
- **5 个点播站点**、**3 个直播源**、**3 个解析器**
- **1 条过滤规则**、**3 条广告域名**、**5 个 VIP flag**、**3 组 IJK 参数**、**5 项配置**

可重复执行，已存在的数据自动跳过。

---

## 五、安装的数据库表（前缀沿用 MacCMS，默认 `mac_`）

`tvbox_config` `tvbox_site` `tvbox_live` `tvbox_parse` `tvbox_rule` `tvbox_flag` `tvbox_ad` `tvbox_ijk`

---

## 六、在 MacCMS 后台菜单里打开（可选）

复制 `application/admin/controller/Tvbox.php` 后，访问 MacCMS 后台即会多出「TVBox 插件」菜单，
点击直接进入本插件后台（复用 MacCMS 登录态，无需再输密码）。

该操作**只新增一个控制器文件**，不修改 MacCMS 任何已有文件；不想要时删掉即可。

---

## 七、目录结构

```
addons/tvbox/
├── app.php               独立路由分发（零框架依赖）
├── Tvbox.php             MacCMS 插件主类（可被插件管理识别）
├── info.ini              插件信息
├── install.sql           建表语句
├── config.php            插件配置项
├── lib/Db.php            PDO 封装（自动读取 application/database.php）
├── lib/Installer.php     建表 + 演示数据
└── controller/
    ├── Api.php           对外 JSON 接口
    └── Admin.php         后台管理页面（内联 HTML）
tvbox_app.php             根目录独立入口
application/admin/controller/Tvbox.php   可选：后台菜单桥接
```

---

## 八、常见问题

**Q：打开是空白 / 500？**
访问 `/tvbox_app.php/api/ping`，若返回 JSON 说明插件正常；否则检查 `addons/tvbox/` 是否完整。

**Q：接口返回「数据库连接失败」？**
检查 MacCMS 根目录 `application/database.php` 的账号密码是否正确。

**Q：TVBox 里看不到影片？**
先在后台「演示数据」页安装演示数据，再访问 `/tvbox_app.php/api/vod?ac=list` 确认有数据。

**Q：想彻底卸载？**
删除 `addons/tvbox/`、`tvbox_app.php`、`application/admin/controller/Tvbox.php`，
再执行 `DROP TABLE mac_tvbox_config, mac_tvbox_site, ...`（可选，不影响 MacCMS 运行）。
