<?php
// ============================================================================
// TVBox 插件 - 独立路由分发（零 ThinkPHP 依赖）
//
// 访问方式（两种都支持）：
//   /tvbox_app.php/api/ping                 PATH_INFO 模式
//   /tvbox_app.php?r=api/ping               查询串模式（无 pathinfo 时自动用）
//   /tvbox_app.php/admin/site               管理后台
// ============================================================================

namespace addons\tvbox;

use addons\tvbox\lib\Db;
use addons\tvbox\controller\Api;
use addons\tvbox\controller\Admin;

if (!defined('TVBOX_PLUGIN_DIR')) {
    define('TVBOX_PLUGIN_DIR', __DIR__ . '/');
}
// addons/tvbox/ → addons/ → MacCMS 根目录
if (!defined('TVBOX_MAC_ROOT')) {
    define('TVBOX_MAC_ROOT', dirname(dirname(__DIR__)) . '/');
}

require_once TVBOX_PLUGIN_DIR . 'lib/Db.php';
require_once TVBOX_PLUGIN_DIR . 'lib/Config.php';
require_once TVBOX_PLUGIN_DIR . 'lib/Installer.php';
require_once TVBOX_PLUGIN_DIR . 'controller/Api.php';
require_once TVBOX_PLUGIN_DIR . 'controller/Admin.php';
require_once TVBOX_PLUGIN_DIR . 'controller/Setting.php';

class App
{
    /**
     * 启动
     * @param bool        $trusted 是否已被 MacCMS 后台授权（桥接进入时为 true）
     * @param string|null $base    管理页链接前缀
     */
    public static function run($trusted = false, $base = null)
    {
        try {
            Db::boot(TVBOX_MAC_ROOT);
        } catch (\Throwable $e) {
            if (!headers_sent()) {
                header('Content-Type: application/json; charset=utf-8');
            }
            echo json_encode(array(
                'code' => 0,
                'msg'  => '数据库连接失败：' . $e->getMessage() . '，请检查 application/database.php',
            ), JSON_UNESCAPED_UNICODE);
            return;
        }

        $segs = self::segments();
        $mode = isset($segs[0]) ? $segs[0] : '';

        // ---------- API ----------
        if ($mode === 'api') {
            $action = isset($segs[1]) ? $segs[1] : 'ping';
            Api::run($action);
            return;
        }

        // ---------- 管理后台（默认） ----------
        $page = ($mode === 'admin' && isset($segs[1])) ? $segs[1] : ($mode !== 'admin' && $mode !== '' ? $mode : 'index');
        if ($page === '') {
            $page = 'index';
        }

        Admin::$trusted = (bool)$trusted;
        Admin::$base = $base !== null ? $base : self::adminBase();

        // 首次访问自动建表（避免 500）
        try {
            if (!Db::tableExists('tvbox_site')) {
                lib\Installer::createTables();
            }
        } catch (\Throwable $e) {
            // 忽略，页面里会显示具体错误
        }

        Admin::run($page);
    }

    /** 解析路由片段 */
    protected static function segments()
    {
        $path = '';

        if (!empty($_SERVER['PATH_INFO'])) {
            $path = $_SERVER['PATH_INFO'];
        } elseif (isset($_GET['r']) && $_GET['r'] !== '') {
            $path = $_GET['r'];
        } elseif (isset($_GET['m']) && $_GET['m'] !== '') {
            $m = $_GET['m'];
            $a = isset($_GET['a']) ? $_GET['a'] : '';
            $path = $a !== '' ? $m . '/' . $a : $m;
        } elseif (!empty($_SERVER['REQUEST_URI'])) {
            $uri = (string)parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
            $script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '';
            if ($script !== '' && strpos($uri, $script) === 0) {
                $path = substr($uri, strlen($script));
            }
        }

        $path = trim((string)$path, '/');
        $path = preg_replace('/\.(html|htm|json|m3u8?)$/i', '', $path);

        if ($path === '') {
            return array();
        }
        return array_values(array_filter(explode('/', $path), function ($v) {
            return $v !== '';
        }));
    }

    /** 管理页链接前缀 */
    protected static function adminBase()
    {
        $script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '/tvbox_app.php';
        if (!empty($_SERVER['PATH_INFO'])) {
            return rtrim($script, '/') . '/admin/';
        }
        return rtrim($script, '/') . '?r=admin/';
    }
}
