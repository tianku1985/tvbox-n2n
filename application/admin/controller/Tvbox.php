<?php
// ============================================================================
// TVBox 插件 - MacCMS 后台桥接控制器
//
// 这是新增文件（不改任何已有文件），作用：
//   让 /tvbox.php/admin/tvbox/index 这类 MacCMS 原生后台 URL 直接渲染
//   TVBox 管理界面，从而可以在后台左侧菜单里正常打开（不会白屏）
//
// 平台：MacCMS10 / ThinkPHP5
// ============================================================================

namespace app\admin\controller;

class Tvbox extends Base
{
    public function index()
    {
        $this->bridge('index');
    }

    public function site()
    {
        $this->bridge('site');
    }

    public function live()
    {
        $this->bridge('live');
    }

    public function parse()
    {
        $this->bridge('parse');
    }

    public function rule()
    {
        $this->bridge('rule');
    }

    public function ad()
    {
        $this->bridge('ad');
    }

    public function setting()
    {
        $this->bridge('setting');
    }

    public function config()
    {
        $this->bridge('config');
    }

    public function demo()
    {
        $this->bridge('demo');
    }

    public function preview()
    {
        $this->bridge('preview');
    }

    /**
     * 交给 TVBox 插件渲染，并标记为已授权（后台已登录）
     */
    protected function bridge($page)
    {
        $root = dirname(dirname(dirname(__DIR__))) . '/';
        $app  = $root . 'addons/tvbox/app.php';
        if (!is_file($app)) {
            echo 'TVBox 插件文件缺失：addons/tvbox/app.php';
            exit;
        }

        require_once $app;

        $base = '/' . basename(defined('IN_FILE') ? IN_FILE : 'tvbox.php') . '/admin/tvbox/';
        \addons\tvbox\App::run(true, $base);
        exit;
    }
}
