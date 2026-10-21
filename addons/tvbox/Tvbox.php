<?php
// ============================================================================
// TVBox for MacCMS —— 插件主类
//
// 说明：真正的业务代码在 app.php + lib/ + controller/ 中，完全不依赖 ThinkPHP，
//      本文件只用于让 MacCMS 的「插件管理」能识别本插件，并在点击安装时建表。
//      即使本文件不被加载，插件（tvbox_app.php 独立入口）依然完整可用。
// ============================================================================

namespace addons\tvbox;

class Tvbox extends \think\Addons
{
    /**
     * 安装：建表 + 写入演示数据
     */
    public function install()
    {
        $this->setDemo();
        return true;
    }

    /**
     * 卸载：保留数据，仅提示
     */
    public function uninstall()
    {
        return true;
    }

    protected function setDemo()
    {
        require_once __DIR__ . '/lib/Db.php';
        require_once __DIR__ . '/lib/Installer.php';
        try {
            lib\Db::boot(dirname(dirname(__DIR__)) . '/');
            lib\Installer::installDemo(false);
        } catch (\Throwable $e) {
            // 安装失败不影响插件主体，用户可在后台「演示数据」页重试
        }
    }
}
