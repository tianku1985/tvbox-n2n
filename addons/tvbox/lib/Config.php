<?php
// ============================================================================
// TVBox 插件 - 配置层
// 1) 顶层配置项（logo/wallpaper/spider/playUrl/notice/home/global/upgrade/
//    suggest/dm/catVodOpenApi/doh/proxy/headers/version/secretKey）逐项可配
// 2) 组装标准 TVBox config.json（sites/lives/parses/flags/rules/ads/ijk + 顶层）
// ============================================================================

namespace addons\tvbox\lib;

class Config
{
    /**
     * 顶层配置项定义
     * group: 分组名   type: text|textarea|json   default: 默认值
     */
    public static function schema()
    {
        return array(
            'logo'      => array('group' => '外观与首页', 'label' => '站点 LOGO 地址', 'type' => 'text', 'default' => '', 'tip' => 'TVBox 首页左上角图标'),
            'wallpaper' => array('group' => '外观与首页', 'label' => '背景壁纸地址', 'type' => 'text', 'default' => '', 'tip' => '首页背景图 URL'),
            'notice'    => array('group' => '外观与首页', 'label' => '公告文字 notice', 'type' => 'textarea', 'default' => '欢迎使用 TVBox × MacCMS 插件', 'tip' => '纯文本，显示在首页公告'),
            'home'      => array('group' => '外观与首页', 'label' => '首页数据 home', 'type' => 'json', 'default' => '{"name":"TVBox 演示","logo":"","bg":""}', 'tip' => 'JSON：name / logo / bg 等'),

            'spider'    => array('group' => '播放器', 'label' => 'Spider 内核地址', 'type' => 'text', 'default' => '', 'tip' => 'jar 包地址，Spider 站点(type=3)必需'),
            'playUrl'   => array('group' => '播放器', 'label' => 'playUrl 播放地址', 'type' => 'text', 'default' => '', 'tip' => '自定义播放器地址，留空用内置'),
            'dm'        => array('group' => '播放器', 'label' => '弹幕配置 dm', 'type' => 'json', 'default' => '{}', 'tip' => 'JSON：danmaku 相关配置'),

            'doh'       => array('group' => '网络', 'label' => 'DoH 列表 doh', 'type' => 'json', 'default' => '[]', 'tip' => 'JSON 数组，DNS over HTTPS 地址'),
            'proxy'     => array('group' => '网络', 'label' => '代理列表 proxy', 'type' => 'json', 'default' => '[]', 'tip' => 'JSON 数组，形如 [{"name":"","host":"","port":0}]'),
            'headers'   => array('group' => '网络', 'label' => '全局请求头 headers', 'type' => 'json', 'default' => '[]', 'tip' => 'JSON 数组，形如 [{"name":"","value":""}]'),

            'global'        => array('group' => '扩展', 'label' => '全局设置 global', 'type' => 'json', 'default' => '{"checkInterval":30,"bootStart":true,"hin2nEntry":true}', 'tip' => 'JSON：checkInterval / bootStart / hin2nEntry'),
            'suggest'       => array('group' => '扩展', 'label' => '搜索建议 suggest', 'type' => 'json', 'default' => '{}', 'tip' => 'JSON：搜索联想配置'),
            'catVodOpenApi' => array('group' => '扩展', 'label' => 'CatVodOpenApi', 'type' => 'json', 'default' => '{}', 'tip' => 'JSON：openApi 配置'),

            'version'   => array('group' => '系统', 'label' => '配置版本号 version', 'type' => 'text', 'default' => '1.0.0', 'tip' => '写入 config.json 的 version 字段'),
            'upgrade'   => array('group' => '系统', 'label' => '升级配置 upgrade', 'type' => 'json', 'default' => '{"version":"1.0.0","url":""}', 'tip' => 'JSON：version / url'),
            'secretKey' => array('group' => '系统', 'label' => '管理密码 secretKey', 'type' => 'text', 'default' => 'tvbox888', 'tip' => '本管理后台登录密码'),
        );
    }

    /** 读取全部顶层配置（缺失的用默认值补齐） */
    public static function all()
    {
        $rows = Db::all('SELECT cfg_key, cfg_val FROM ' . Db::table('tvbox_config'));
        $kv = array();
        foreach ($rows as $r) {
            $kv[$r['cfg_key']] = $r['cfg_val'];
        }
        $out = array();
        foreach (self::schema() as $k => $def) {
            $out[$k] = isset($kv[$k]) && $kv[$k] !== null ? $kv[$k] : $def['default'];
        }
        return $out;
    }

    /** 读单项 */
    public static function get($key)
    {
        $s = self::schema();
        $def = isset($s[$key]) ? $s[$key]['default'] : '';
        $v = Db::value('SELECT cfg_val FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array($key), null);
        return $v === null ? $def : $v;
    }

    /** 写单项 */
    public static function set($key, $val)
    {
        $val = (string)$val;
        $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array($key), 0);
        if ($exists) {
            return Db::update('tvbox_config', array('cfg_val' => $val, 'updated_at' => date('Y-m-d H:i:s')), 'cfg_key=?', array($key));
        }
        return Db::insert('tvbox_config', array(
            'cfg_key'    => $key,
            'cfg_val'    => $val,
            'cfg_type'   => 1,
            'remark'     => '',
            'created_at' => date('Y-m-d H:i:s'),
            'updated_at' => date('Y-m-d H:i:s'),
        ));
    }

    /**
     * 保存提交的顶层配置
     * @return string 提示信息
     */
    public static function save(array $post)
    {
        $err = array();
        foreach (self::schema() as $k => $def) {
            if (!isset($post[$k])) {
                continue;
            }
            $val = trim((string)$post[$k]);
            if ($def['type'] === 'json' && $val !== '') {
                if (json_decode($val, true) === null) {
                    $err[] = $def['label'];
                    continue;
                }
            }
            self::set($k, $val);
        }
        if ($err) {
            return '已保存，但以下项不是合法 JSON，已跳过：' . implode('、', $err);
        }
        return '配置已保存';
    }

    // ------------------------------------------------------------------ 组装
    /** 组装标准 TVBox config.json */
    public static function build()
    {
        $c = self::all();
        $cfg = array();

        // 字符串项
        foreach (array('spider', 'wallpaper', 'logo', 'playUrl', 'version') as $k) {
            $v = trim((string)$c[$k]);
            if ($v !== '') {
                $cfg[$k] = $v;
            }
        }
        // 公告：兼容历史数据里的 [{"text":"...","url":"..."}] 结构，统一输出纯文本
        $notice = trim((string)$c['notice']);
        if ($notice !== '') {
            $d = json_decode($notice, true);
            if (is_array($d)) {
                $texts = array();
                foreach ($d as $n) {
                    if (is_array($n) && isset($n['text'])) {
                        $texts[] = (string)$n['text'];
                    } elseif (is_string($n)) {
                        $texts[] = $n;
                    }
                }
                $notice = implode("\n", $texts);
            }
            if ($notice !== '') {
                $cfg['notice'] = $notice;
            }
        }
        // JSON 项
        foreach (array('home', 'global', 'upgrade', 'suggest', 'dm', 'catVodOpenApi', 'doh', 'proxy', 'headers') as $k) {
            $v = trim((string)$c[$k]);
            if ($v === '') {
                continue;
            }
            $d = json_decode($v, true);
            if ($d !== null) {
                $cfg[$k] = $d;
            }
        }

        $cfg['sites']  = self::sites();
        $cfg['lives']  = self::lives();
        $cfg['parses'] = self::parses();
        $cfg['flags']  = self::flags();
        $cfg['rules']  = self::rules();
        $cfg['ads']    = self::ads();
        $cfg['ijk']    = self::ijk();

        return $cfg;
    }

    /** 点播站点 */
    public static function sites()
    {
        $rows = Db::all('SELECT * FROM ' . Db::table('tvbox_site') . ' WHERE status=1 AND hidden=0 ORDER BY sort_order DESC, id ASC');
        $out = array();
        foreach ($rows as $r) {
            $api = $r['site_api'];
            if ($api === '' || $api === '/') {
                $api = self::host() . '/api.php/api/vod';
            }
            $item = array(
                'key'  => $r['site_key'],
                'name' => $r['site_name'],
                'type' => (int)$r['site_type'],
                'api'  => $api,
            );
            if (!empty($r['jar'])) {
                $item['jar'] = $r['jar'];
            }
            if (isset($r['site_ext']) && trim((string)$r['site_ext']) !== '' && trim((string)$r['site_ext']) !== '{}') {
                $item['ext'] = $r['site_ext'];
            }
            if (isset($r['categories']) && trim((string)$r['categories']) !== '' && trim((string)$r['categories']) !== '[]') {
                $decoded = json_decode($r['categories'], true);
                $item['categories'] = $decoded === null ? $r['categories'] : $decoded;
            }
            if ((int)$r['style'] > 0) {
                $item['style'] = (int)$r['style'];
            }
            $item['playerType']  = (int)$r['player_type'];
            $item['searchable']  = (int)$r['searchable'];
            $item['quickSearch'] = (int)$r['quick_search'];
            $item['filterable']  = (int)$r['filterable'];
            $item['changeable']  = (int)$r['changeable'];
            $out[] = $item;
        }
        return $out;
    }

    /** 直播源 */
    public static function lives()
    {
        $rows = Db::all('SELECT * FROM ' . Db::table('tvbox_live') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC');
        $out = array();
        foreach ($rows as $r) {
            $item = array(
                'name' => $r['live_name'],
                'type' => (int)$r['live_type'],
                'url'  => $r['live_url'],
            );
            if (!empty($r['live_epg'])) {
                $item['epg'] = $r['live_epg'];
            }
            if (!empty($r['live_logo'])) {
                $item['logo'] = $r['live_logo'];
            }
            if (isset($r['ua']) && trim((string)$r['ua']) !== '') {
                $item['ua'] = $r['ua'];
            }
            if (isset($r['css']) && trim((string)$r['css']) !== '') {
                $item['css'] = $r['css'];
            }
            if (isset($r['groups']) && trim((string)$r['groups']) !== '') {
                $d = json_decode($r['groups'], true);
                $item['groups'] = $d === null ? $r['groups'] : $d;
            }
            if (isset($r['ext']) && trim((string)$r['ext']) !== '' && trim((string)$r['ext']) !== '{}') {
                $item['ext'] = $r['ext'];
            }
            $item['playerType'] = (int)$r['player_type'];
            $out[] = $item;
        }
        return $out;
    }

    /** 解析器 */
    public static function parses()
    {
        $rows = Db::all('SELECT * FROM ' . Db::table('tvbox_parse') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC');
        $out = array();
        foreach ($rows as $r) {
            $ext = array();
            $flags = array_values(array_filter(array_map('trim', explode(',', (string)$r['parse_flag']))));
            if ($flags) {
                $ext['flag'] = $flags;
            }
            if (isset($r['parse_header']) && trim((string)$r['parse_header']) !== '') {
                $h = json_decode($r['parse_header'], true);
                if (is_array($h) && $h) {
                    $ext['header'] = $h;
                }
            }
            $item = array(
                'name' => $r['parse_name'],
                'type' => (int)$r['parse_type'],
                'url'  => $r['parse_url'],
            );
            if ($ext) {
                $item['ext'] = $ext;
            }
            $out[] = $item;
        }
        return $out;
    }

    /** VIP flag */
    public static function flags()
    {
        $out = array();
        foreach (Db::all('SELECT flag_text FROM ' . Db::table('tvbox_flag') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC') as $r) {
            if (trim($r['flag_text']) !== '') {
                $out[] = $r['flag_text'];
            }
        }
        return $out;
    }

    /** 过滤规则 */
    public static function rules()
    {
        $out = array();
        foreach (Db::all('SELECT * FROM ' . Db::table('tvbox_rule') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC') as $r) {
            $item = array('name' => $r['rule_name']);
            foreach (array('hosts' => 'rule_hosts', 'regex' => 'rule_regex', 'filter' => 'rule_filter', 'exclude' => 'rule_exclude', 'script' => 'rule_script') as $k => $col) {
                if (!isset($r[$col]) || trim((string)$r[$col]) === '') {
                    continue;
                }
                $d = json_decode($r[$col], true);
                if (is_array($d) && $d) {
                    $item[$k] = $d;
                }
            }
            $out[] = $item;
        }
        return $out;
    }

    /** 广告拦截域名 */
    public static function ads()
    {
        $out = array();
        foreach (Db::all('SELECT ad_host FROM ' . Db::table('tvbox_ad') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC') as $r) {
            if (trim($r['ad_host']) !== '') {
                $out[] = $r['ad_host'];
            }
        }
        return $out;
    }

    /** IJK 参数组 */
    public static function ijk()
    {
        $groups = array();
        foreach (Db::all('SELECT * FROM ' . Db::table('tvbox_ijk') . ' WHERE status=1 ORDER BY sort_order DESC, id ASC') as $j) {
            $g = $j['group_name'];
            if (!isset($groups[$g])) {
                $groups[$g] = array('group' => $g, 'format' => array(), 'codec' => array(), 'player' => array());
            }
            $cat = (int)$j['category'] === 2 ? 'codec' : ((int)$j['category'] === 4 ? 'player' : 'format');
            $groups[$g][$cat][] = array('name' => $j['opt_name'], 'value' => $j['opt_value']);
        }
        $out = array();
        foreach ($groups as $g) {
            $item = array('group' => $g['group']);
            foreach (array('format', 'codec', 'player') as $c) {
                if ($g[$c]) {
                    $item[$c] = $g[$c];
                }
            }
            $out[] = $item;
        }
        return $out;
    }

    /** 当前站点根地址 */
    public static function host()
    {
        $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
        $host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : '127.0.0.1';
        return $scheme . '://' . $host;
    }
}
