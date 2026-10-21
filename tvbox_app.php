<?php
// ============================================================================
// TVBox for MacCMS —— 独立入口文件（放在 MacCMS 根目录，与 api.php 同级）
//
// 不修改任何 MacCMS 核心文件，删掉本文件即完全恢复
//
// API：
//   /tvbox_app.php/api/ping
//   /tvbox_app.php/api/vod?ac=list
//   /tvbox_app.php/api/sites            ← TVBox 配置地址填这个
//   /tvbox_app.php/api/config
//   /tvbox_app.php/api/live?format=m3u
//
// 管理后台：
//   /tvbox_app.php/admin/index          ← 默认密码 tvbox888
//
// 若服务器不支持 PATH_INFO，改用查询串模式：
//   /tvbox_app.php?r=api/sites
//   /tvbox_app.php?r=admin/index
// ============================================================================

if (version_compare(PHP_VERSION, '5.5.0', '<')) {
    die('PHP 版本需要 >= 5.5');
}

@ini_set('display_errors', '0');
error_reporting(E_ALL & ~E_NOTICE & ~E_DEPRECATED);

require __DIR__ . '/addons/tvbox/app.php';

\addons\tvbox\App::run(false);
