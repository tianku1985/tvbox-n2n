<?php
// ============================================================================
// TVBox 插件 - 后台管理控制器（纯 PHP，内联 HTML，零框架依赖）
// 自带登录保护；若由 MacCMS 后台桥接进入则不重复登录
// ============================================================================

namespace addons\tvbox\controller;

use addons\tvbox\lib\Db;
use addons\tvbox\lib\Installer;

class Admin
{
    /** 链接前缀，由路由层注入 */
    public static $base = '/tvbox_app.php/admin/';

    /** 是否已由 MacCMS 后台授权 */
    public static $trusted = false;

    /** 生成管理页链接 */
    public static function url($page = 'index', array $extra = array())
    {
        $q = $extra ? '?' . http_build_query($extra) : '';
        return self::$base . $page . $q;
    }

    /** 登录密码（取配置 secretKey，默认 tvbox888） */
    protected static function password()
    {
        $v = Db::value('SELECT cfg_val FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array('secretKey'), '');
        return $v !== '' && $v !== null ? $v : 'tvbox888';
    }

    // ------------------------------------------------------------------ 入口
    public static function run($page = 'index')
    {
        if (!self::$trusted) {
            if (!self::checkLogin()) {
                return self::renderLogin();
            }
        }

        $page = preg_replace('/[^a-z]/', '', strtolower($page));
        if ($page === '') {
            $page = 'index';
        }

        // 旧版本数据库自动补齐 TVBox 全量配置所需字段
        try {
            Installer::upgradeTables();
        } catch (\Throwable $e) {
            // 忽略，页面本身可正常使用
        }

        try {
            switch ($page) {
                case 'setting':
                    return Setting::page();
                case 'site':
                    return self::pageSite();
                case 'live':
                    return self::pageLive();
                case 'parse':
                    return self::pageParse();
                case 'rule':
                    return self::pageRule();
                case 'ad':
                    return self::pageAd();
                case 'config':
                    return self::pageConfig();
                case 'demo':
                    return self::pageDemo();
                case 'preview':
                    return self::pagePreview();
                case 'logout':
                    @session_start();
                    $_SESSION['tvbox_admin'] = 0;
                    return self::renderLogin('已退出登录');
                case 'index':
                default:
                    return self::pageIndex();
            }
        } catch (\Throwable $e) {
            echo '<div style="margin:20px;padding:16px;background:#fee;border:1px solid #f99;border-radius:6px;color:#900">';
            echo '页面异常：' . htmlspecialchars($e->getMessage());
            echo '</div>';
        }
    }

    // ------------------------------------------------------------- 登录相关
    protected static function sessionStart()
    {
        if (session_status() !== PHP_SESSION_ACTIVE) {
            @session_start();
        }
    }

    protected static function checkLogin()
    {
        self::sessionStart();
        if (!empty($_POST['tvbox_pwd'])) {
            if (hash_equals(self::password(), (string)$_POST['tvbox_pwd'])) {
                $_SESSION['tvbox_admin'] = 1;
                header('Location: ' . self::url('index'));
                exit;
            }
            return false;
        }
        return !empty($_SESSION['tvbox_admin']);
    }

    protected static function renderLogin($msg = '')
    {
        $err = '';
        if (!empty($_POST['tvbox_pwd'])) {
            $err = '密码错误，请重试。';
        }
        if ($msg) {
            $err = $msg;
        }
        echo self::layout('登录', '
        <div style="max-width:380px;margin:80px auto;background:#fff;border-radius:10px;box-shadow:0 4px 20px rgba(0,0,0,.08);padding:32px">
          <h2 style="margin:0 0 8px;font-size:20px">TVBox 管理后台</h2>
          <p style="color:#888;margin:0 0 20px;font-size:13px">请输入管理密码（默认 tvbox888，可在配置中心修改）</p>
          ' . ($err ? '<p style="color:#d33;font-size:13px">' . htmlspecialchars($err) . '</p>' : '') . '
          <form method="post">
            <input type="password" name="tvbox_pwd" placeholder="管理密码" style="width:100%;padding:10px 12px;border:1px solid #ddd;border-radius:6px;font-size:14px;box-sizing:border-box" autofocus>
            <button type="submit" style="width:100%;margin-top:14px;padding:10px;background:#3b82f6;color:#fff;border:0;border-radius:6px;font-size:14px;cursor:pointer">登录</button>
          </form>
        </div>', false);
        exit;
    }

    // -------------------------------------------------------------- 概览页
    protected static function pageIndex()
    {
        $stats = array(
            array('点播站点', (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_site') . ' WHERE status=1', array(), 0), 'site'),
            array('直播源',   (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_live') . ' WHERE status=1', array(), 0), 'live'),
            array('解析器',   (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_parse') . ' WHERE status=1', array(), 0), 'parse'),
            array('过滤规则', (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_rule') . ' WHERE status=1', array(), 0), 'rule'),
            array('影片总数', (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('vod'), array(), 0), 'preview'),
            array('分类总数', (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('type') . ' WHERE type_mid=1', array(), 0), 'preview'),
        );

        $cards = '';
        foreach ($stats as $s) {
            $cards .= '<a href="' . self::url($s[2]) . '" style="flex:1;min-width:150px;background:#fff;border-radius:10px;padding:18px 20px;text-decoration:none;color:#111;box-shadow:0 2px 10px rgba(0,0,0,.06)">
              <div style="font-size:13px;color:#888">' . $s[0] . '</div>
              <div style="font-size:28px;font-weight:600;margin-top:6px">' . $s[1] . '</div></a>';
        }

        $apiUrl = self::apiUrl('vod');
        $body = '
        <h2 style="margin:0 0 16px;font-size:20px">总览仪表盘</h2>
        <div style="display:flex;gap:14px;flex-wrap:wrap">' . $cards . '</div>

        <div style="background:#fff;border-radius:10px;padding:20px;margin-top:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <h3 style="margin:0 0 12px;font-size:15px">TVBox 接口地址</h3>
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr><td style="padding:8px 0;color:#666;width:140px">点播接口</td><td><code>' . htmlspecialchars($apiUrl) . '</code></td></tr>
            <tr><td style="padding:8px 0;color:#666">多站点配置</td><td><code>' . htmlspecialchars(self::apiUrl('sites')) . '</code></td></tr>
            <tr><td style="padding:8px 0;color:#666">远程配置</td><td><code>' . htmlspecialchars(self::apiUrl('config')) . '</code></td></tr>
            <tr><td style="padding:8px 0;color:#666">直播源</td><td><code>' . htmlspecialchars(self::apiUrl('live')) . '</code></td></tr>
            <tr><td style="padding:8px 0;color:#666">直播 M3U</td><td><code>' . htmlspecialchars(self::apiUrl('live') . '&format=m3u') . '</code></td></tr>
          </table>
          <p style="color:#888;font-size:13px;margin:14px 0 0">在 TVBox / 影视 App 的「配置地址」填入上面的<b>多站点配置</b>或<b>远程配置</b>地址即可。</p>
        </div>

        <div style="background:#fff;border-radius:10px;padding:20px;margin-top:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <h3 style="margin:0 0 12px;font-size:15px">快捷操作</h3>
          <a href="' . self::url('demo') . '" style="display:inline-block;padding:9px 16px;background:#10b981;color:#fff;border-radius:6px;text-decoration:none;font-size:13px;margin-right:10px">安装 / 重置演示数据</a>
          <a href="' . self::url('preview') . '" style="display:inline-block;padding:9px 16px;background:#3b82f6;color:#fff;border-radius:6px;text-decoration:none;font-size:13px">接口预览</a>
        </div>';

        echo self::layout('总览', $body, true);
        exit;
    }

    /** 拼接对外 API 地址 */
    public static function apiUrl($action, array $extra = array())
    {
        $script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '/tvbox_app.php';
        $q = $extra ? '&' . http_build_query($extra) : '';
        return Api::baseUrl() . $script . '/api/' . $action . $q;
    }

    // --------------------------------------------------------------- 站点页
    protected static function pageSite()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            $siteExt    = trim((string)(isset($_POST['site_ext']) ? $_POST['site_ext'] : ''));
            $categories = trim((string)(isset($_POST['categories']) ? $_POST['categories'] : ''));
            if (($siteExt !== '' && json_decode($siteExt, true) === null) || ($categories !== '' && json_decode($categories, true) === null)) {
                $msg = '扩展参数 / 分类白名单不是合法 JSON，未保存';
            }
            $data = array(
                'site_key'     => trim((string)$_POST['site_key']),
                'site_name'    => trim((string)$_POST['site_name']),
                'site_type'    => (int)$_POST['site_type'],
                'site_api'     => trim((string)$_POST['site_api']),
                'site_ext'     => $siteExt === '' ? '{}' : $siteExt,
                'jar'          => trim((string)(isset($_POST['jar']) ? $_POST['jar'] : '')),
                'categories'   => $categories,
                'style'        => (int)(isset($_POST['style']) ? $_POST['style'] : 0),
                'timeout'      => (int)(isset($_POST['timeout']) ? $_POST['timeout'] : 10),
                'searchable'   => isset($_POST['searchable']) ? 1 : 0,
                'quick_search' => isset($_POST['quick_search']) ? 1 : 0,
                'filterable'   => isset($_POST['filterable']) ? 1 : 0,
                'changeable'   => isset($_POST['changeable']) ? 1 : 0,
                'player_type'  => (int)(isset($_POST['player_type']) ? $_POST['player_type'] : -1),
                'sort_order'   => (int)$_POST['sort_order'],
                'status'       => isset($_POST['status']) ? 1 : 0,
            );
            if ($msg !== '') {
                // 校验失败，保留页面
            } elseif ($data['site_key'] === '' || $data['site_name'] === '') {
                $msg = '标识和名称不能为空';
            } elseif (!empty($_POST['id'])) {
                Db::update('tvbox_site', $data, 'id=?', array((int)$_POST['id']));
                $msg = '已更新';
            } else {
                Db::insert('tvbox_site', $data);
                $msg = '已添加';
            }
        }
        if (isset($_GET['del'])) {
            Db::delete('tvbox_site', 'id=?', array((int)$_GET['del']));
            $msg = '已删除';
        }

        $edit = isset($_GET['edit']) ? Db::one('SELECT * FROM ' . Db::table('tvbox_site') . ' WHERE id=?', array((int)$_GET['edit'])) : null;
        $list = Db::all('SELECT * FROM ' . Db::table('tvbox_site') . ' ORDER BY sort_order DESC, id ASC');

        $e = function ($k, $d = '') use ($edit) {
            return $edit && isset($edit[$k]) ? htmlspecialchars((string)$edit[$k]) : $d;
        };
        $ck = function ($k, $d = 1) use ($edit) {
            $v = $edit && isset($edit[$k]) ? (int)$edit[$k] : $d;
            return $v ? ' checked' : '';
        };

        $rows = '';
        foreach ($list as $r) {
            $rows .= '<tr>
              <td>' . (int)$r['id'] . '</td>
              <td>' . htmlspecialchars($r['site_key']) . '</td>
              <td>' . htmlspecialchars($r['site_name']) . '</td>
              <td>' . (int)$r['site_type'] . '</td>
              <td style="max-width:320px;word-break:break-all">' . htmlspecialchars($r['site_api']) . '</td>
              <td>' . ((int)$r['status'] ? '<span style="color:#10b981">启用</span>' : '<span style="color:#999">停用</span>') . '</td>
              <td><a href="' . self::url('site', array('edit' => $r['id'])) . '" style="color:#3b82f6">编辑</a> ·
                  <a href="' . self::url('site', array('del' => $r['id'])) . '" style="color:#ef4444" onclick="return confirm(\'确认删除？\')">删除</a></td>
            </tr>';
        }

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">点播站点管理</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <input type="hidden" name="id" value="' . ($edit ? (int)$edit['id'] : '') . '">
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end">
              <label style="font-size:13px;color:#666">标识<br><input name="site_key" value="' . $e('site_key') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:150px"></label>
              <label style="font-size:13px;color:#666">名称<br><input name="site_name" value="' . $e('site_name') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:160px"></label>
              <label style="font-size:13px;color:#666">类型<br><select name="site_type" style="padding:8px;border:1px solid #ddd;border-radius:6px">
                <option value="1"' . ($edit && (int)$edit['site_type'] === 1 ? ' selected' : '') . '>1 - MacCMS JSON</option>
                <option value="0"' . ($edit && (int)$edit['site_type'] === 0 ? ' selected' : '') . '>0 - XML</option>
                <option value="3"' . ($edit && (int)$edit['site_type'] === 3 ? ' selected' : '') . '>3 - Spider</option>
              </select></label>
              <label style="font-size:13px;color:#666">排序<br><input name="sort_order" value="' . $e('sort_order', '0') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:70px"></label>
              <label style="font-size:13px;color:#666">风格 style<br><input name="style" value="' . $e('style', '0') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:70px"></label>
              <label style="font-size:13px;color:#666">超时<br><input name="timeout" value="' . $e('timeout', '10') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:70px"></label>
              <label style="font-size:13px;color:#666">播放器 playerType<br><input name="player_type" value="' . $e('player_type', '-1') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:80px"></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;margin-top:12px">
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">接口地址 api<br><input name="site_api" value="' . $e('site_api') . '" placeholder="留空表示使用本站 MacCMS 接口" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">Spider jar<br><input name="jar" value="' . $e('jar') . '" placeholder="type=3 时必填" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;margin-top:12px">
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">扩展参数 ext（JSON）<br>
                <textarea name="site_ext" rows="3" spellcheck="false" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('site_ext', '{}') . '</textarea></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">分类白名单 categories（JSON 数组，留空=全部）<br>
                <textarea name="categories" rows="3" spellcheck="false" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('categories') . '</textarea></label>
            </div>
            <div style="display:flex;gap:18px;flex-wrap:wrap;align-items:center;margin-top:14px;font-size:13px;color:#666">
              <label><input type="checkbox" name="searchable"' . $ck('searchable') . '> 可搜索 searchable</label>
              <label><input type="checkbox" name="quick_search"' . $ck('quick_search') . '> 快速搜索 quickSearch</label>
              <label><input type="checkbox" name="filterable"' . $ck('filterable') . '> 可筛选 filterable</label>
              <label><input type="checkbox" name="changeable"' . $ck('changeable') . '> 可切换线路 changeable</label>
              <label><input type="checkbox" name="status"' . $ck('status') . '> 启用</label>
            </div>
            <div style="margin-top:14px">
              <button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer">' . ($edit ? '保存修改' : '添加站点') . '</button>
              ' . ($edit ? '<a href="' . self::url('site') . '" style="padding:9px 14px;color:#666;text-decoration:none;font-size:13px">取消</a>' : '') . '
            </div>
          </form>
        </div>
        <div style="background:#fff;border-radius:10px;padding:8px;margin-top:16px;box-shadow:0 2px 10px rgba(0,0,0,.06);overflow:auto">
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr style="background:#f8fafc"><th style="padding:10px;text-align:left">ID</th><th style="text-align:left">标识</th><th style="text-align:left">名称</th><th style="text-align:left">类型</th><th style="text-align:left">接口</th><th style="text-align:left">状态</th><th style="text-align:left">操作</th></tr>
            ' . ($rows ? $rows : '<tr><td colspan="7" style="padding:20px;text-align:center;color:#999">暂无数据，请先添加或安装演示数据</td></tr>') . '
          </table>
        </div>';

        echo self::layout('点播站点', $body, true);
        exit;
    }

    // ------------------------------------------------------------- 直播源页
    protected static function pageLive()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            $ext    = trim((string)(isset($_POST['ext']) ? $_POST['ext'] : ''));
            $groups = trim((string)(isset($_POST['groups']) ? $_POST['groups'] : ''));
            if (($ext !== '' && json_decode($ext, true) === null) || ($groups !== '' && json_decode($groups, true) === null)) {
                $msg = '扩展参数 / 频道分组不是合法 JSON，未保存';
            }
            $data = array(
                'live_name'   => trim((string)$_POST['live_name']),
                'live_type'   => (int)$_POST['live_type'],
                'live_url'    => trim((string)$_POST['live_url']),
                'live_epg'    => trim((string)(isset($_POST['live_epg']) ? $_POST['live_epg'] : '')),
                'live_logo'   => trim((string)(isset($_POST['live_logo']) ? $_POST['live_logo'] : '')),
                'live_header' => '{}',
                'ua'          => trim((string)(isset($_POST['ua']) ? $_POST['ua'] : '')),
                'css'         => trim((string)(isset($_POST['css']) ? $_POST['css'] : '')),
                'ext'         => $ext === '' ? '{}' : $ext,
                'groups'      => $groups,
                'timeout'     => (int)(isset($_POST['timeout']) ? $_POST['timeout'] : 10),
                'player_type' => (int)(isset($_POST['player_type']) ? $_POST['player_type'] : -1),
                'sort_order'  => (int)$_POST['sort_order'],
                'status'      => isset($_POST['status']) ? 1 : 0,
            );
            if ($msg !== '') {
                // 校验失败，保留页面
            } elseif ($data['live_name'] === '' || $data['live_url'] === '') {
                $msg = '名称和地址不能为空';
            } elseif (!empty($_POST['id'])) {
                Db::update('tvbox_live', $data, 'id=?', array((int)$_POST['id']));
                $msg = '已更新';
            } else {
                Db::insert('tvbox_live', $data);
                $msg = '已添加';
            }
        }
        if (isset($_GET['del'])) {
            Db::delete('tvbox_live', 'id=?', array((int)$_GET['del']));
            $msg = '已删除';
        }

        $edit = isset($_GET['edit']) ? Db::one('SELECT * FROM ' . Db::table('tvbox_live') . ' WHERE id=?', array((int)$_GET['edit'])) : null;
        $list = Db::all('SELECT * FROM ' . Db::table('tvbox_live') . ' ORDER BY sort_order DESC, id ASC');
        $e = function ($k, $d = '') use ($edit) {
            return $edit && isset($edit[$k]) ? htmlspecialchars((string)$edit[$k]) : $d;
        };
        $ck = function ($k, $d = 1) use ($edit) {
            $v = $edit && isset($edit[$k]) ? (int)$edit[$k] : $d;
            return $v ? ' checked' : '';
        };

        $rows = '';
        foreach ($list as $r) {
            $rows .= '<tr><td>' . (int)$r['id'] . '</td><td>' . htmlspecialchars($r['live_name']) . '</td><td>' . (int)$r['live_type'] . '</td>
              <td style="max-width:340px;word-break:break-all">' . htmlspecialchars($r['live_url']) . '</td>
              <td>' . ((int)$r['status'] ? '<span style="color:#10b981">启用</span>' : '停用') . '</td>
              <td><a href="' . self::url('live', array('edit' => $r['id'])) . '" style="color:#3b82f6">编辑</a> ·
              <a href="' . self::url('live', array('del' => $r['id'])) . '" style="color:#ef4444" onclick="return confirm(\'确认删除？\')">删除</a></td></tr>';
        }

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">直播源管理</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <input type="hidden" name="id" value="' . ($edit ? (int)$edit['id'] : '') . '">
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end">
              <label style="font-size:13px;color:#666">名称<br><input name="live_name" value="' . $e('live_name') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:180px"></label>
              <label style="font-size:13px;color:#666">类型<br><select name="live_type" style="padding:8px;border:1px solid #ddd;border-radius:6px">
                <option value="0"' . ($edit && (int)$edit['live_type'] === 0 ? ' selected' : '') . '>0 - M3U</option>
                <option value="1"' . ($edit && (int)$edit['live_type'] === 1 ? ' selected' : '') . '>1 - TXT</option>
                <option value="2"' . ($edit && (int)$edit['live_type'] === 2 ? ' selected' : '') . '>2 - Spider</option>
              </select></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:300px">地址<br><input name="live_url" value="' . $e('live_url') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;margin-top:12px">
              <label style="font-size:13px;color:#666;flex:1;min-width:240px">EPG<br><input name="live_epg" value="' . $e('live_epg') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:240px">台标 logo<br><input name="live_logo" value="' . $e('live_logo') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:240px">User-Agent ua<br><input name="ua" value="' . $e('ua') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;margin-top:12px">
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">频道样式 css<br><textarea name="css" rows="2" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('css') . '</textarea></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">频道分组 groups（JSON 数组）<br><textarea name="groups" rows="2" spellcheck="false" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('groups') . '</textarea></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">扩展参数 ext（JSON）<br><textarea name="ext" rows="2" spellcheck="false" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('ext', '{}') . '</textarea></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;margin-top:12px">
              <label style="font-size:13px;color:#666">超时<br><input name="timeout" value="' . $e('timeout', '10') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:60px"></label>
              <label style="font-size:13px;color:#666">播放器 playerType<br><input name="player_type" value="' . $e('player_type', '-1') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:80px"></label>
              <label style="font-size:13px;color:#666">排序<br><input name="sort_order" value="' . $e('sort_order', '0') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:60px"></label>
              <label style="font-size:13px;color:#666"><input type="checkbox" name="status"' . $ck('status') . '> 启用</label>
            </div>
            <div style="margin-top:14px">
              <button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer">' . ($edit ? '保存修改' : '添加直播源') . '</button>
              ' . ($edit ? '<a href="' . self::url('live') . '" style="padding:9px 14px;color:#666;text-decoration:none;font-size:13px">取消</a>' : '') . '
            </div>
          </form>
        </div>
        <div style="background:#fff;border-radius:10px;padding:8px;margin-top:16px;box-shadow:0 2px 10px rgba(0,0,0,.06);overflow:auto">
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr style="background:#f8fafc"><th style="padding:10px;text-align:left">ID</th><th style="text-align:left">名称</th><th style="text-align:left">类型</th><th style="text-align:left">地址</th><th style="text-align:left">状态</th><th style="text-align:left">操作</th></tr>
            ' . ($rows ? $rows : '<tr><td colspan="6" style="padding:20px;text-align:center;color:#999">暂无数据</td></tr>') . '
          </table>
        </div>';
        echo self::layout('直播源', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 解析器页
    protected static function pageParse()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            $header = trim((string)(isset($_POST['parse_header']) ? $_POST['parse_header'] : ''));
            if ($header !== '' && json_decode($header, true) === null) {
                $msg = '解析请求头不是合法 JSON，未保存';
            }
            $data = array(
                'parse_name'   => trim((string)$_POST['parse_name']),
                'parse_type'   => (int)$_POST['parse_type'],
                'parse_url'    => trim((string)$_POST['parse_url']),
                'parse_flag'   => trim((string)(isset($_POST['parse_flag']) ? $_POST['parse_flag'] : '')),
                'parse_ext'    => '{}',
                'parse_header' => $header,
                'sort_order'   => (int)$_POST['sort_order'],
                'status'       => isset($_POST['status']) ? 1 : 0,
            );
            if ($msg !== '') {
                // 校验失败，保留页面
            } elseif ($data['parse_name'] === '' || $data['parse_url'] === '') {
                $msg = '名称和地址不能为空';
            } elseif (!empty($_POST['id'])) {
                Db::update('tvbox_parse', $data, 'id=?', array((int)$_POST['id']));
                $msg = '已更新';
            } else {
                Db::insert('tvbox_parse', $data);
                $msg = '已添加';
            }
        }
        if (isset($_GET['del'])) {
            Db::delete('tvbox_parse', 'id=?', array((int)$_GET['del']));
            $msg = '已删除';
        }

        $edit = isset($_GET['edit']) ? Db::one('SELECT * FROM ' . Db::table('tvbox_parse') . ' WHERE id=?', array((int)$_GET['edit'])) : null;
        $list = Db::all('SELECT * FROM ' . Db::table('tvbox_parse') . ' ORDER BY sort_order DESC, id ASC');
        $e = function ($k, $d = '') use ($edit) {
            return $edit && isset($edit[$k]) ? htmlspecialchars((string)$edit[$k]) : $d;
        };
        $ck = function ($k, $d = 1) use ($edit) {
            $v = $edit && isset($edit[$k]) ? (int)$edit[$k] : $d;
            return $v ? ' checked' : '';
        };

        $rows = '';
        foreach ($list as $r) {
            $rows .= '<tr><td>' . (int)$r['id'] . '</td><td>' . htmlspecialchars($r['parse_name']) . '</td><td>' . (int)$r['parse_type'] . '</td>
              <td style="max-width:300px;word-break:break-all">' . htmlspecialchars($r['parse_url']) . '</td><td>' . htmlspecialchars($r['parse_flag']) . '</td>
              <td><a href="' . self::url('parse', array('edit' => $r['id'])) . '" style="color:#3b82f6">编辑</a> ·
              <a href="' . self::url('parse', array('del' => $r['id'])) . '" style="color:#ef4444" onclick="return confirm(\'确认删除？\')">删除</a></td></tr>';
        }

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">VIP 解析器</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <input type="hidden" name="id" value="' . ($edit ? (int)$edit['id'] : '') . '">
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end">
              <label style="font-size:13px;color:#666">名称<br><input name="parse_name" value="' . $e('parse_name') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:150px"></label>
              <label style="font-size:13px;color:#666">类型<br><select name="parse_type" style="padding:8px;border:1px solid #ddd;border-radius:6px">
                <option value="0"' . ($edit && (int)$edit['parse_type'] === 0 ? ' selected' : '') . '>0 - JSON</option>
                <option value="1"' . ($edit && (int)$edit['parse_type'] === 1 ? ' selected' : '') . '>1 - WebView</option>
              </select></label>
              <label style="font-size:13px;color:#666;flex:1;min-width:280px">解析地址<br><input name="parse_url" value="' . $e('parse_url') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
            </div>
            <div style="display:flex;gap:12px;flex-wrap:wrap;align-items:flex-end;margin-top:12px">
              <label style="font-size:13px;color:#666;flex:1;min-width:220px">适用 flag（逗号分隔）<br><input name="parse_flag" value="' . $e('parse_flag') . '" placeholder="qq,iqiyi" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
              <label style="font-size:13px;color:#666">排序<br><input name="sort_order" value="' . $e('sort_order', '0') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:60px"></label>
              <label style="font-size:13px;color:#666"><input type="checkbox" name="status"' . $ck('status') . '> 启用</label>
            </div>
            <label style="font-size:13px;color:#666;display:block;margin-top:12px">解析请求头 header（JSON 对象，如 {"User-Agent":"Mozilla/5.0"}）<br>
              <textarea name="parse_header" rows="3" spellcheck="false" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">' . $e('parse_header') . '</textarea></label>
            <div style="margin-top:14px">
              <button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer">' . ($edit ? '保存修改' : '添加解析器') . '</button>
              ' . ($edit ? '<a href="' . self::url('parse') . '" style="padding:9px 14px;color:#666;text-decoration:none;font-size:13px">取消</a>' : '') . '
            </div>
          </form>
        </div>
        <div style="background:#fff;border-radius:10px;padding:8px;margin-top:16px;box-shadow:0 2px 10px rgba(0,0,0,.06);overflow:auto">
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr style="background:#f8fafc"><th style="padding:10px;text-align:left">ID</th><th style="text-align:left">名称</th><th style="text-align:left">类型</th><th style="text-align:left">地址</th><th style="text-align:left">flag</th><th style="text-align:left">操作</th></tr>
            ' . ($rows ? $rows : '<tr><td colspan="6" style="padding:20px;text-align:center;color:#999">暂无数据</td></tr>') . '
          </table>
        </div>';
        echo self::layout('VIP 解析器', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 规则页
    protected static function pageRule()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            $hosts = array_values(array_filter(array_map('trim', preg_split('/[\s,]+/', (string)$_POST['rule_hosts']))));
            $regex = array_values(array_filter(array_map('trim', preg_split('/\r?\n/', (string)$_POST['rule_regex']))));
            $toLines = function ($k) {
                $v = isset($_POST[$k]) ? (string)$_POST[$k] : '';
                return array_values(array_filter(array_map('trim', preg_split('/\r?\n/', $v))));
            };
            $data = array(
                'rule_name'    => trim((string)$_POST['rule_name']),
                'rule_hosts'   => json_encode($hosts, JSON_UNESCAPED_UNICODE),
                'rule_regex'   => json_encode($regex, JSON_UNESCAPED_UNICODE),
                'rule_filter'  => json_encode($toLines('rule_filter'), JSON_UNESCAPED_UNICODE),
                'rule_exclude' => json_encode($toLines('rule_exclude'), JSON_UNESCAPED_UNICODE),
                'rule_script'  => json_encode($toLines('rule_script'), JSON_UNESCAPED_UNICODE),
                'sort_order'   => (int)$_POST['sort_order'],
                'status'       => isset($_POST['status']) ? 1 : 0,
            );
            if ($data['rule_name'] === '') {
                $msg = '规则名不能为空';
            } elseif (!empty($_POST['id'])) {
                Db::update('tvbox_rule', $data, 'id=?', array((int)$_POST['id']));
                $msg = '已更新';
            } else {
                Db::insert('tvbox_rule', $data);
                $msg = '已添加';
            }
        }
        if (isset($_GET['del'])) {
            Db::delete('tvbox_rule', 'id=?', array((int)$_GET['del']));
            $msg = '已删除';
        }

        $edit = isset($_GET['edit']) ? Db::one('SELECT * FROM ' . Db::table('tvbox_rule') . ' WHERE id=?', array((int)$_GET['edit'])) : null;
        $list = Db::all('SELECT * FROM ' . Db::table('tvbox_rule') . ' ORDER BY sort_order DESC, id ASC');
        $e = function ($k, $d = '') use ($edit) {
            return $edit && isset($edit[$k]) ? htmlspecialchars((string)$edit[$k]) : $d;
        };
        $ck = function ($k, $d = 1) use ($edit) {
            $v = $edit && isset($edit[$k]) ? (int)$edit[$k] : $d;
            return $v ? ' checked' : '';
        };
        $hostsText = $edit ? implode(', ', json_decode($edit['rule_hosts'], true) ?: array()) : '';
        $regexText = $edit ? implode("\n", json_decode($edit['rule_regex'], true) ?: array()) : '';
        $lines = function ($col) use ($edit) {
            return $edit && isset($edit[$col]) ? implode("\n", json_decode($edit[$col], true) ?: array()) : '';
        };

        $rows = '';
        foreach ($list as $r) {
            $rows .= '<tr><td>' . (int)$r['id'] . '</td><td>' . htmlspecialchars($r['rule_name']) . '</td>
              <td style="max-width:260px;word-break:break-all">' . htmlspecialchars(implode(', ', json_decode($r['rule_hosts'], true) ?: array())) . '</td>
              <td><a href="' . self::url('rule', array('edit' => $r['id'])) . '" style="color:#3b82f6">编辑</a> ·
              <a href="' . self::url('rule', array('del' => $r['id'])) . '" style="color:#ef4444" onclick="return confirm(\'确认删除？\')">删除</a></td></tr>';
        }

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">M3U8 过滤规则</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <input type="hidden" name="id" value="' . ($edit ? (int)$edit['id'] : '') . '">
            <label style="font-size:13px;color:#666;display:block;margin-bottom:10px">规则名称<br>
              <input name="rule_name" value="' . $e('rule_name') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:320px"></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:10px">Host 列表（逗号或空格分隔）<br>
              <input name="rule_hosts" value="' . htmlspecialchars($hostsText) . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box"></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:10px">正则（每行一条）<br>
              <textarea name="rule_regex" rows="4" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($regexText) . '</textarea></label>
            <div style="display:flex;gap:12px;flex-wrap:wrap;margin-bottom:10px">
              <label style="flex:1;min-width:260px;font-size:13px;color:#666">过滤 filter（每行一条）<br>
                <textarea name="rule_filter" rows="3" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($lines('rule_filter')) . '</textarea></label>
              <label style="flex:1;min-width:260px;font-size:13px;color:#666">排除 exclude（每行一条）<br>
                <textarea name="rule_exclude" rows="3" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($lines('rule_exclude')) . '</textarea></label>
              <label style="flex:1;min-width:260px;font-size:13px;color:#666">脚本 script（每行一条）<br>
                <textarea name="rule_script" rows="3" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($lines('rule_script')) . '</textarea></label>
            </div>
            <label style="font-size:13px;color:#666">排序 <input name="sort_order" value="' . $e('sort_order', '0') . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:60px"></label>
            <label style="font-size:13px;color:#666;margin-left:12px"><input type="checkbox" name="status"' . $ck('status') . '> 启用</label>
            <button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer;margin-left:10px">' . ($edit ? '保存修改' : '添加规则') . '</button>
            ' . ($edit ? '<a href="' . self::url('rule') . '" style="margin-left:10px;color:#666;text-decoration:none;font-size:13px">取消</a>' : '') . '
          </form>
        </div>
        <div style="background:#fff;border-radius:10px;padding:8px;margin-top:16px;box-shadow:0 2px 10px rgba(0,0,0,.06);overflow:auto">
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr style="background:#f8fafc"><th style="padding:10px;text-align:left">ID</th><th style="text-align:left">名称</th><th style="text-align:left">Hosts</th><th style="text-align:left">操作</th></tr>
            ' . ($rows ? $rows : '<tr><td colspan="4" style="padding:20px;text-align:center;color:#999">暂无数据</td></tr>') . '
          </table>
        </div>';
        echo self::layout('过滤规则', $body, true);
        exit;
    }

    // ---------------------------------------------------------- 广告 / Flag
    protected static function pageAd()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            $ads = array_values(array_filter(array_map('trim', preg_split('/[\s,]+/', (string)$_POST['ads']))));
            Db::exec('DELETE FROM ' . Db::table('tvbox_ad'));
            foreach ($ads as $i => $h) {
                Db::insert('tvbox_ad', array('ad_host' => $h, 'sort_order' => 100 - $i, 'status' => 1));
            }
            $flags = array_values(array_filter(array_map('trim', preg_split('/[\s,]+/', (string)$_POST['flags']))));
            Db::exec('DELETE FROM ' . Db::table('tvbox_flag'));
            foreach ($flags as $i => $f) {
                Db::insert('tvbox_flag', array('flag_text' => $f, 'sort_order' => 100 - $i, 'status' => 1));
            }
            $msg = '已保存';
        }

        $ads = Db::all('SELECT ad_host FROM ' . Db::table('tvbox_ad') . ' ORDER BY sort_order DESC');
        $flags = Db::all('SELECT flag_text FROM ' . Db::table('tvbox_flag') . ' ORDER BY sort_order DESC');
        $adText = implode("\n", array_map(function ($r) {
            return $r['ad_host'];
        }, $ads));
        $flagText = implode("\n", array_map(function ($r) {
            return $r['flag_text'];
        }, $flags));

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">广告拦截 / VIP Flag</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <div style="display:flex;gap:20px;flex-wrap:wrap">
              <label style="flex:1;min-width:280px;font-size:13px;color:#666">广告域名（每行一个）<br>
                <textarea name="ads" rows="8" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($adText) . '</textarea></label>
              <label style="flex:1;min-width:280px;font-size:13px;color:#666">VIP Flag（每行一个）<br>
                <textarea name="flags" rows="8" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($flagText) . '</textarea></label>
            </div>
            <button type="submit" style="margin-top:14px;padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer">保存</button>
          </form>
        </div>';
        echo self::layout('广告 / Flag', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 配置页
    protected static function pageConfig()
    {
        $msg = '';
        if (isset($_POST['act']) && $_POST['act'] === 'save') {
            foreach (array('global', 'notice', 'home', 'upgrade', 'secretKey') as $k) {
                if (!isset($_POST[$k])) {
                    continue;
                }
                $val = trim((string)$_POST[$k]);
                if ($val !== '' && $k !== 'secretKey' && json_decode($val, true) === null) {
                    $msg = $k . ' 不是合法 JSON';
                    continue;
                }
                $type = $k === 'secretKey' ? 3 : 1;
                $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array($k), 0);
                if ($exists) {
                    Db::update('tvbox_config', array('cfg_val' => $val, 'cfg_type' => $type, 'updated_at' => date('Y-m-d H:i:s')), 'cfg_key=?', array($k));
                } else {
                    Db::insert('tvbox_config', array('cfg_key' => $k, 'cfg_val' => $val, 'cfg_type' => $type, 'remark' => ''));
                }
            }
            if ($msg === '') {
                $msg = '配置已保存';
            }
        }

        $get = function ($k, $d = '') {
            $v = Db::value('SELECT cfg_val FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array($k), '');
            return $v === '' || $v === null ? $d : $v;
        };

        $pretty = function ($json) {
            $d = json_decode($json, true);
            return $d === null ? $json : json_encode($d, JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
        };

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">配置中心</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <form method="post">
            <input type="hidden" name="act" value="save">
            <label style="font-size:13px;color:#666;display:block;margin-bottom:14px">全局设置 global（JSON）<br>
              <textarea name="global" rows="6" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($pretty($get('global', '{"checkInterval":30,"bootStart":true,"hin2nEntry":true}'))) . '</textarea></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:14px">公告 notice（JSON 数组）<br>
              <textarea name="notice" rows="5" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($pretty($get('notice', '[{"text":"欢迎使用 TVBox","url":""}]'))) . '</textarea></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:14px">首页外观 home（JSON）<br>
              <textarea name="home" rows="4" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($pretty($get('home', '{"name":"TVBox 演示","logo":"","bg":""}'))) . '</textarea></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:14px">升级配置 upgrade（JSON）<br>
              <textarea name="upgrade" rows="3" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:monospace">' . htmlspecialchars($pretty($get('upgrade', '{"version":"1.0.0","url":""}'))) . '</textarea></label>
            <label style="font-size:13px;color:#666;display:block;margin-bottom:14px">管理密码 secretKey<br>
              <input name="secretKey" value="' . htmlspecialchars($get('secretKey', 'tvbox888')) . '" style="padding:8px;border:1px solid #ddd;border-radius:6px;width:320px"></label>
            <button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer">保存配置</button>
          </form>
        </div>';
        echo self::layout('配置中心', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 演示页
    protected static function pageDemo()
    {
        $msg = '';
        $log = array();
        if (isset($_POST['act']) && $_POST['act'] === 'install') {
            $res = Installer::installDemo(true);
            $log = isset($res['log']) ? $res['log'] : array();
            $msg = '演示数据安装完成';
        }

        $logHtml = '';
        if ($log) {
            $logHtml = '<ul style="font-size:13px;color:#333;line-height:1.9;margin:12px 0 0 0;padding-left:20px">';
            foreach ($log as $l) {
                $logHtml .= '<li>' . htmlspecialchars($l) . '</li>';
            }
            $logHtml .= '</ul>';
        }

        $body = self::msg($msg) . '
        <h2 style="margin:0 0 16px;font-size:20px">演示数据</h2>
        <div style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">
          <p style="font-size:13px;color:#666;margin:0 0 16px;line-height:1.8">
            一键安装完整演示数据（可重复执行，已存在的数据会自动跳过）：<br>
            · 11 个分类（type_id 101-111）<br>
            · 14 部影片（vod_id 10001-10014，可直接在点播接口里看到）<br>
            · 5 个点播站点、3 个直播源、3 个解析器、1 条过滤规则、3 条广告域名、5 个 flag、3 组 IJK 参数、5 项配置
          </p>
          <form method="post">
            <input type="hidden" name="act" value="install">
            <button type="submit" style="padding:10px 20px;background:#10b981;color:#fff;border:0;border-radius:6px;cursor:pointer;font-size:14px">立即安装演示数据</button>
          </form>
          ' . $logHtml . '
        </div>';
        echo self::layout('演示数据', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 预览页
    protected static function pagePreview()
    {
        $apis = array(
            array('Ping 心跳检测', self::apiUrl('ping'), '确认接口是否可用'),
            array('分类列表', self::apiUrl('vod', array('ac' => 'class')), 'TVBox 分类 JSON'),
            array('影片列表', self::apiUrl('vod', array('ac' => 'list', 'limit' => 10)), 'TVBox 列表 JSON'),
            array('影片详情', self::apiUrl('vod', array('ac' => 'detail', 'ids' => '10001')), 'TVBox 详情 JSON'),
            array('搜索影片', self::apiUrl('vod', array('ac' => 'search', 'wd' => '流浪')), '按关键字搜索'),
            array('多站点配置', self::apiUrl('sites'), 'TVBox 多站点 JSON（配置地址填这个）'),
            array('远程配置', self::apiUrl('config'), 'TVBox 远程配置 JSON'),
            array('直播源 JSON', self::apiUrl('live'), '直播源列表'),
            array('直播源 M3U', self::apiUrl('live', array('format' => 'm3u')), 'M3U 格式直播源'),
        );

        $rows = '';
        foreach ($apis as $a) {
            $rows .= '<tr><td style="padding:10px;font-weight:500">' . htmlspecialchars($a[0]) . '</td>
              <td style="padding:10px;word-break:break-all"><a href="' . htmlspecialchars($a[1]) . '" target="_blank" style="color:#3b82f6;font-family:monospace;font-size:12px">' . htmlspecialchars($a[1]) . '</a></td>
              <td style="padding:10px;color:#888;font-size:12px">' . htmlspecialchars($a[2]) . '</td></tr>';
        }

        $body = '
        <h2 style="margin:0 0 16px;font-size:20px">接口预览</h2>
        <div style="background:#fff;border-radius:10px;padding:8px;box-shadow:0 2px 10px rgba(0,0,0,.06);overflow:auto">
          <table style="width:100%;border-collapse:collapse;font-size:13px">
            <tr style="background:#f8fafc"><th style="padding:10px;text-align:left;width:140px">接口</th><th style="padding:10px;text-align:left">地址（点击直接访问）</th><th style="padding:10px;text-align:left;width:220px">说明</th></tr>
            ' . $rows . '
          </table>
        </div>
        <div style="background:#fff;border-radius:10px;padding:20px;margin-top:16px;box-shadow:0 2px 10px rgba(0,0,0,.06);font-size:13px;color:#666;line-height:1.9">
          <b style="color:#111">TVBox 配置方式</b><br>
          1. 打开 TVBox / 影视 App → 设置 → 配置地址<br>
          2. 粘贴上面的「多站点配置」地址：<code style="background:#f3f4f6;padding:2px 6px;border-radius:4px">' . htmlspecialchars(self::apiUrl('sites')) . '</code><br>
          3. 确认后即可看到演示数据中的 14 部影片
        </div>';
        echo self::layout('接口预览', $body, true);
        exit;
    }

    // -------------------------------------------------------------- 布局
    protected static function msg($msg)
    {
        if (!$msg) {
            return '';
        }
        return '<div style="margin-bottom:14px;padding:10px 14px;background:#ecfdf5;border:1px solid #a7f3d0;color:#047857;border-radius:6px;font-size:13px">' . htmlspecialchars($msg) . '</div>';
    }

    public static function layout($title, $body, $withNav = true)
    {
        $nav = '';
        if ($withNav) {
            $items = array(
                'index'   => '总览',
                'site'    => '点播站点',
                'live'    => '直播源',
                'parse'   => '解析器',
                'rule'    => '过滤规则',
                'ad'      => '广告/Flag',
                'setting' => 'TVBox 配置',
                'config'  => '配置中心',
                'demo'    => '演示数据',
                'preview' => '接口预览',
            );
            $cur = isset($_GET['r']) ? $_GET['r'] : '';
            $nav = '<div style="background:#111827;padding:0 20px;display:flex;align-items:center;height:52px">'
                 . '<span style="color:#fff;font-weight:600;margin-right:24px;font-size:15px">TVBox 管理</span>';
            foreach ($items as $k => $label) {
                $nav .= '<a href="' . self::url($k) . '" style="color:#d1d5db;text-decoration:none;font-size:13px;padding:0 12px;line-height:52px">' . $label . '</a>';
            }
            $nav .= '<a href="' . self::url('logout') . '" style="margin-left:auto;color:#9ca3af;text-decoration:none;font-size:13px">退出</a></div>';
        }
        return '<!DOCTYPE html><html lang="zh-cn"><head><meta charset="utf-8">'
            . '<meta name="viewport" content="width=device-width,initial-scale=1">'
            . '<title>' . htmlspecialchars($title) . ' - TVBox</title></head>'
            . '<body style="margin:0;background:#f3f4f6;font-family:-apple-system,BlinkMacSystemFont,\'Segoe UI\',Roboto,\'Helvetica Neue\',Arial,\'PingFang SC\',\'Microsoft YaHei\',sans-serif">'
            . $nav
            . '<div style="padding:24px' . ($withNav ? '' : ';padding-top:0') . '">' . $body . '</div>'
            . '</body></html>';
    }
}
