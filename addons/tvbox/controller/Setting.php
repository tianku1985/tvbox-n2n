<?php
// ============================================================================
// TVBox 插件 - 配置项管理页
// 把 TVBox config.json 的每个顶层配置项独立为一个可编辑字段（按分组单独保存），
// 并提供 ijk 参数（tvbox_ijk）的 JSON 编辑器。
// ============================================================================

namespace addons\tvbox\controller;

use addons\tvbox\lib\Db;
use addons\tvbox\lib\Config;

class Setting
{
    /** 分组顺序 */
    protected static $groupOrder = array('外观与首页', '播放器', '网络', '扩展', '系统');

    // ------------------------------------------------------------------ 入口
    public static function page()
    {
        $msg = '';
        $act = isset($_POST['act']) ? (string)$_POST['act'] : '';

        if ($act === 'save') {
            $msg = Config::save($_POST);
        } elseif ($act === 'save_ijk') {
            $msg = self::saveIjk(isset($_POST['ijk']) ? (string)$_POST['ijk'] : '');
        }

        echo Admin::layout('TVBox 配置', self::body($msg), true);
        exit;
    }

    // ------------------------------------------------------------------ 页面
    protected static function body($msg)
    {
        $all    = Config::all();
        $schema = Config::schema();

        // 按分组归集
        $groups = array();
        foreach ($schema as $key => $def) {
            $groups[$def['group']][$key] = $def;
        }

        $html = '';
        if ($msg !== '') {
            $html .= '<div style="margin-bottom:14px;padding:10px 14px;background:#ecfdf5;border:1px solid #a7f3d0;color:#047857;border-radius:6px;font-size:13px">'
                   . htmlspecialchars($msg) . '</div>';
        }

        $html .= '<h2 style="margin:0 0 6px;font-size:20px">TVBox 配置项</h2>'
               . '<p style="color:#888;font-size:13px;margin:0 0 18px">'
               . '每一项都可单独修改并单独保存；类型为 JSON 的项必须填写合法 JSON（保存时自动校验）。'
               . '</p>';

        $order = self::$groupOrder;
        foreach ($groups as $gname => $items) {
            if (!in_array($gname, $order, true)) {
                $order[] = $gname;
            }
        }

        foreach ($order as $gname) {
            if (empty($groups[$gname])) {
                continue;
            }
            $html .= '<form method="post" style="background:#fff;border-radius:10px;padding:20px;margin-bottom:16px;box-shadow:0 2px 10px rgba(0,0,0,.06)">'
                   . '<input type="hidden" name="act" value="save">'
                   . '<h3 style="margin:0 0 14px;font-size:15px;color:#111">' . htmlspecialchars($gname) . '</h3>';

            foreach ($groups[$gname] as $key => $def) {
                $val = isset($all[$key]) ? $all[$key] : $def['default'];
                if ($key === 'notice') {
                    // 兼容历史数据里的 JSON 数组公告，编辑时显示为纯文本
                    $d = json_decode((string)$val, true);
                    if (is_array($d)) {
                        $texts = array();
                        foreach ($d as $n) {
                            if (is_array($n) && isset($n['text'])) {
                                $texts[] = (string)$n['text'];
                            } elseif (is_string($n)) {
                                $texts[] = $n;
                            }
                        }
                        $val = implode("\n", $texts);
                    }
                }
                if ($def['type'] === 'json') {
                    $val = self::pretty($val);
                }
                $label = htmlspecialchars($def['label']) . ' <code style="color:#9ca3af;font-size:12px">' . htmlspecialchars($key) . '</code>';
                $tip   = isset($def['tip']) && $def['tip'] !== ''
                       ? '<div style="color:#9ca3af;font-size:12px;margin-top:4px">' . htmlspecialchars($def['tip']) . '</div>'
                       : '';

                $html .= '<label style="display:block;margin-bottom:16px;font-size:13px;color:#666">' . $label . '<br>';
                if ($def['type'] === 'text') {
                    $html .= '<input name="' . htmlspecialchars($key) . '" value="' . htmlspecialchars((string)$val) . '"'
                           . ' style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box">';
                } elseif ($def['type'] === 'textarea') {
                    $html .= '<textarea name="' . htmlspecialchars($key) . '" rows="3"'
                           . ' style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box">'
                           . htmlspecialchars((string)$val) . '</textarea>';
                } else {
                    $html .= '<textarea name="' . htmlspecialchars($key) . '" rows="6" spellcheck="false"'
                           . ' style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">'
                           . htmlspecialchars((string)$val) . '</textarea>';
                }
                $html .= $tip . '</label>';
            }

            $html .= '<button type="submit" style="padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer;font-size:13px">保存本组</button></form>';
        }

        // ---------------------------------------------------------- IJK 参数
        $ijkJson = self::pretty(json_encode(Config::ijk(), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
        $html .= '<form method="post" style="background:#fff;border-radius:10px;padding:20px;margin-bottom:16px;box-shadow:0 2px 10px rgba(0,0,0,.06)">'
               . '<input type="hidden" name="act" value="save_ijk">'
               . '<h3 style="margin:0 0 6px;font-size:15px;color:#111">IJK 参数 <code style="color:#9ca3af;font-size:12px">ijk</code></h3>'
               . '<p style="color:#9ca3af;font-size:12px;margin:0 0 10px">JSON 数组，元素形如 {"group":"软解码","format":[{"name":"iformat","value":"0"}],"codec":[],"player":[]}</p>'
               . '<textarea name="ijk" rows="10" spellcheck="false"'
               . ' style="padding:8px;border:1px solid #ddd;border-radius:6px;width:100%;box-sizing:border-box;font-family:Consolas,Monaco,monospace;font-size:12px">'
               . htmlspecialchars($ijkJson) . '</textarea>'
               . '<button type="submit" style="margin-top:12px;padding:9px 18px;background:#3b82f6;color:#fff;border:0;border-radius:6px;cursor:pointer;font-size:13px">保存 IJK 参数</button></form>';

        // ---------------------------------------------------------- 完整预览
        $raw = json_encode(Config::build(), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
        $html .= '<details style="background:#fff;border-radius:10px;padding:20px;box-shadow:0 2px 10px rgba(0,0,0,.06)">'
               . '<summary style="cursor:pointer;font-size:15px;font-weight:600">当前生成的 config.json（点击展开）</summary>'
               . '<pre style="margin:14px 0 0;padding:14px;background:#0f172a;color:#e2e8f0;border-radius:8px;overflow:auto;font-size:12px;line-height:1.6">'
               . htmlspecialchars($raw) . '</pre></details>';

        return $html;
    }

    // -------------------------------------------------------------- 工具
    /** JSON 美化（非法 JSON 原样返回） */
    protected static function pretty($json)
    {
        $json = (string)$json;
        if (trim($json) === '') {
            return '';
        }
        $d = json_decode($json, true);
        if ($d === null) {
            return $json;
        }
        return json_encode($d, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_PRETTY_PRINT);
    }

    /** 保存 IJK 参数（整体覆盖 tvbox_ijk） */
    protected static function saveIjk($raw)
    {
        $arr = json_decode($raw, true);
        if (!is_array($arr)) {
            return 'IJK 参数不是合法 JSON 数组，未保存';
        }

        Db::exec('DELETE FROM ' . Db::table('tvbox_ijk'));
        $n = 0;
        foreach ($arr as $i => $g) {
            if (!is_array($g) || empty($g['group'])) {
                continue;
            }
            foreach (array('format' => 1, 'codec' => 2, 'player' => 4) as $cat => $code) {
                if (empty($g[$cat]) || !is_array($g[$cat])) {
                    continue;
                }
                foreach ($g[$cat] as $opt) {
                    if (!is_array($opt) || !isset($opt['name'])) {
                        continue;
                    }
                    Db::insert('tvbox_ijk', array(
                        'group_name' => (string)$g['group'],
                        'category'   => $code,
                        'opt_name'   => (string)$opt['name'],
                        'opt_value'  => isset($opt['value']) ? (string)$opt['value'] : '',
                        'sort_order' => 100 - (int)$i,
                        'status'     => 1,
                    ));
                    $n++;
                }
            }
        }
        return 'IJK 参数已保存（' . $n . ' 条）';
    }
}
