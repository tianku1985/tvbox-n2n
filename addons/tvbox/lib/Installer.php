<?php
// ============================================================================
// TVBox 插件 - 安装器 / 演示数据安装器
// 1) 建表：读取 install.sql
// 2) 演示数据：11 个分类 + 14 部影片（写入 MacCMS 原生 mac_type / mac_vod）
//    + 站点/直播/解析/规则/广告/flag/ijk/配置
// ============================================================================

namespace addons\tvbox\lib;

class Installer
{
    /** 建表，已存在则跳过 */
    public static function createTables()
    {
        $sqlFile = dirname(__DIR__) . '/install.sql';
        if (!is_file($sqlFile)) {
            return array('ok' => false, 'msg' => 'install.sql 不存在');
        }
        $sql = file_get_contents($sqlFile);
        $sql = str_replace('__PREFIX__', Db::prefix(), $sql);
        $sql = str_replace("\r\n", "\n", $sql);

        $stmts = preg_split('/;\s*\n/', $sql);
        $ok = 0;
        $err = array();
        foreach ($stmts as $stmt) {
            $stmt = trim($stmt);
            if ($stmt === '' || strpos($stmt, '--') === 0 || strpos($stmt, '/*') === 0) {
                continue;
            }
            try {
                Db::pdo()->exec($stmt);
                $ok++;
            } catch (\Throwable $e) {
                $err[] = $e->getMessage();
            }
        }
        return array('ok' => empty($err), 'msg' => "建表完成：{$ok} 条成功" . ($err ? '，失败：' . implode('; ', $err) : ''));
    }

    /**
     * 升级表结构：为已存在的表补齐 TVBox 完整配置所需字段
     * @return array 新增的字段列表
     */
    public static function upgradeTables()
    {
        $adds = array(
            'tvbox_site' => array(
                'jar'        => "varchar(1024) NOT NULL DEFAULT ''",
                'categories' => 'text',
                'style'      => "tinyint(2) unsigned NOT NULL DEFAULT '0'",
                'timeout'    => "smallint(6) unsigned NOT NULL DEFAULT '10'",
            ),
            'tvbox_live' => array(
                'ua'     => "varchar(512) NOT NULL DEFAULT ''",
                'css'    => 'text',
                'ext'    => 'text',
                'groups' => 'text',
            ),
            'tvbox_parse' => array(
                'parse_header' => 'text',
            ),
            'tvbox_rule' => array(
                'rule_filter'  => 'text',
                'rule_exclude' => 'text',
                'rule_script'  => 'text',
            ),
        );

        $done = array();
        foreach ($adds as $table => $cols) {
            if (!Db::tableExists($table)) {
                continue;
            }
            foreach ($cols as $col => $def) {
                $has = Db::one('SHOW COLUMNS FROM ' . Db::table($table) . ' LIKE ?', array($col));
                if ($has) {
                    continue;
                }
                Db::exec('ALTER TABLE ' . Db::table($table) . ' ADD COLUMN `' . $col . '` ' . $def);
                $done[] = $table . '.' . $col;
            }
        }
        return $done;
    }

    /** 是否已安装演示数据 */
    public static function demoInstalled()
    {
        return (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('vod') . ' WHERE vod_id BETWEEN 10001 AND 10099', array(), 0) > 0;
    }

    /**
     * 安装完整演示数据
     */
    public static function installDemo($force = false)
    {
        $log = array();
        self::createTables();
        $log[] = '数据表检查完成';
        $up = self::upgradeTables();
        if ($up) {
            $log[] = '补全字段：' . implode(', ', $up);
        }

        // ---------------- 11 个分类 ----------------
        $types = array(
            101 => '电影',
            102 => '电视剧',
            103 => '综艺',
            104 => '动漫',
            105 => '纪录片',
            106 => '短剧',
            107 => '体育',
            108 => '教育',
            109 => '音乐',
            110 => '游戏',
            111 => '其他',
        );
        foreach ($types as $tid => $tname) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('type') . ' WHERE type_id=?', array($tid), 0);
            if ($exists) {
                continue;
            }
            Db::insert('type', array(
                'type_id'     => $tid,
                'type_name'   => $tname,
                'type_en'     => 'tvbox' . $tid,
                'type_sort'   => $tid - 100,
                'type_mid'    => 1,
                'type_pid'    => 0,
                'type_status' => 1,
                'type_key'    => $tname,
                'type_des'    => $tname . ' - TVBox 演示分类',
                'type_title'  => $tname,
                'type_extend' => '',
            ));
        }
        $log[] = '分类：11 个（type_id 101-111）';

        // ---------------- 14 部影片 ----------------
        $stream = 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8';
        $vods = array(
            array('流浪地球2', 101, '2023', '中国大陆', 'HD国语', 8.7, '科幻 / 冒险'),
            array('满江红', 101, '2023', '中国大陆', 'HD国语', 7.2, '悬疑 / 喜剧'),
            array('狂飙', 102, '2023', '中国大陆', '全39集', 9.1, '犯罪 / 剧情'),
            array('三体', 102, '2023', '中国大陆', '全30集', 8.6, '科幻 / 悬疑'),
            array('奔跑吧', 103, '2024', '中国大陆', '更新至第8期', 7.8, '真人秀'),
            array('中国好声音', 103, '2023', '中国大陆', '全12期', 7.1, '音乐 / 选秀'),
            array('斗罗大陆', 104, '2024', '中国大陆', '更新至第260集', 8.9, '热血 / 玄幻'),
            array('间谍过家家', 104, '2024', '日本', '更新至第25集', 9.0, '搞笑 / 治愈'),
            array('地球脉动III', 105, '2023', '英国', '全8集', 9.6, '自然 / 生态'),
            array('舌尖上的中国', 105, '2023', '中国大陆', '全8集', 9.3, '美食 / 人文'),
            array('闪婚后大佬她又美又飒', 106, '2024', '中国大陆', '全80集', 7.5, '短剧 / 言情'),
            array('网球王子', 107, '2024', '日本', '全178集', 8.4, '运动 / 热血'),
            array('Python 零基础入门', 108, '2024', '中国大陆', '全50讲', 9.2, '编程 / 教育'),
            array('周杰伦无与伦比演唱会', 109, '2024', '中国台湾', 'HD', 9.4, '音乐 / 现场'),
        );

        $inserted = 0;
        $vid = 10001;
        foreach ($vods as $v) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('vod') . ' WHERE vod_id=?', array($vid), 0);
            if ($exists) {
                $vid++;
                continue;
            }
            list($name, $tid, $year, $area, $remarks, $score, $class) = $v;
            $playUrl = '第1集$' . $stream . '#第2集$' . $stream;
            // vod_letter 为 char(1)，只能放 ASCII 字母，中文名统一用 Z
            $letter = preg_match('/^[A-Za-z]/', $name) ? strtoupper($name[0]) : 'Z';
            $newId = Db::insert('vod', array(
                'vod_id'            => $vid,
                'type_id'           => $tid,
                'type_id_1'         => $tid,
                'vod_name'          => $name,
                'vod_sub'           => '',
                'vod_en'            => 'tvbox' . $vid,
                'vod_status'        => 1,
                'vod_letter'        => $letter,
                'vod_class'         => $class,
                'vod_pic'           => 'https://picsum.photos/seed/tvbox' . $vid . '/300/400',
                'vod_pic_thumb'     => '',
                'vod_actor'         => '演示演员',
                'vod_director'      => '演示导演',
                'vod_remarks'       => $remarks,
                'vod_area'          => $area,
                'vod_lang'          => '国语',
                'vod_year'          => $year,
                'vod_score'         => $score,
                'vod_score_all'     => 100,
                'vod_score_num'     => 12,
                'vod_hits'          => 1000 + $vid,
                'vod_time'          => time(),
                'vod_time_add'      => time(),
                'vod_isend'         => 1,
                'vod_play_from'     => 'tvbox',
                'vod_play_server'   => '',
                'vod_play_note'     => '',
                'vod_play_url'      => $playUrl,
                'vod_down_from'     => '',
                'vod_down_server'   => '',
                'vod_down_note'     => '',
                'vod_down_url'      => '',
                'vod_plot'          => 0,
                'vod_plot_name'     => '',
                'vod_plot_detail'   => '',
                'vod_content'       => '<p>' . $name . '（TVBox 演示数据，可随时删除）。</p>',
            ));
            if ($newId > 0) {
                $inserted++;
            } else {
                $log[] = '影片插入失败：' . $name . ' - ' . Db::$error;
            }
            $vid++;
        }
        $log[] = "影片：{$inserted} 部（vod_id 10001+）";

        // ---------------- 点播站点 ----------------
        $sites = array(
            array('tvbox_local', '本站 MacCMS 接口', 1, '', '本站原生 JSON 接口', 1),
            array('tvbox_demo_json', '演示 JSON 站点', 1, 'https://json.heimuer.xyz/api.php/provide/vod/', '公共演示源', 2),
            array('tvbox_demo_json2', '演示 JSON 站点 2', 1, 'https://www.360zy.com/api.php/provide/vod/', '公共演示源', 3),
            array('tvbox_spider', '演示 Spider 源', 3, 'https://example.com/spider.json', '需要 Spider 内核', 4),
            array('tvbox_xml', '演示 XML 站点', 0, 'https://example.com/api.php/provide/vod/?ac=list', 'XML 列表源', 5),
        );
        $siteCount = 0;
        foreach ($sites as $s) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_site') . ' WHERE site_key=?', array($s[0]), 0);
            if ($exists) {
                continue;
            }
            Db::insert('tvbox_site', array(
                'site_key'     => $s[0],
                'site_name'    => $s[1],
                'site_type'    => $s[2],
                'site_api'     => $s[3],
                'site_ext'     => '{}',
                'searchable'   => 1,
                'quick_search' => $s[2] === 1 ? 1 : 0,
                'filterable'   => 1,
                'changeable'   => 1,
                'player_type'  => -1,
                'sort_order'   => $s[5],
                'hidden'       => 0,
                'status'       => 1,
            ));
            $siteCount++;
        }
        $log[] = "点播站点：{$siteCount} 个";

        // ---------------- 直播源 ----------------
        $lives = array(
            array('演示直播源（M3U）', 0, 'https://iptv-org.github.io/iptv/categories/movies.m3u'),
            array('演示直播源（TXT）', 1, 'https://example.com/live.txt'),
            array('演示直播源（Spider）', 2, 'https://example.com/live.json'),
        );
        $liveCount = 0;
        foreach ($lives as $i => $l) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_live') . ' WHERE live_name=?', array($l[0]), 0);
            if ($exists) {
                continue;
            }
            Db::insert('tvbox_live', array(
                'live_name'   => $l[0],
                'live_type'   => $l[1],
                'live_url'    => $l[2],
                'live_epg'    => '',
                'live_logo'   => '',
                'live_header' => '{}',
                'timeout'     => 10,
                'player_type' => -1,
                'sort_order'  => 10 - $i,
                'status'      => 1,
            ));
            $liveCount++;
        }
        $log[] = "直播源：{$liveCount} 个";

        // ---------------- VIP 解析器 ----------------
        $parses = array(
            array('演示解析-1', 0, 'https://jx.example.com/?url=', 'qq,iqiyi,youku'),
            array('演示解析-2', 1, 'https://jx2.example.com/?url=', 'mgtv'),
            array('演示解析-3', 0, 'https://jx3.example.com/?url=', ''),
        );
        $parseCount = 0;
        foreach ($parses as $i => $p) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_parse') . ' WHERE parse_name=?', array($p[0]), 0);
            if ($exists) {
                continue;
            }
            Db::insert('tvbox_parse', array(
                'parse_name' => $p[0],
                'parse_type' => $p[1],
                'parse_url'  => $p[2],
                'parse_flag' => $p[3],
                'parse_ext'  => '{}',
                'sort_order' => 10 - $i,
                'status'     => 1,
            ));
            $parseCount++;
        }
        $log[] = "解析器：{$parseCount} 个";

        // ---------------- 过滤规则 ----------------
        if ((int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_rule'), array(), 0) === 0) {
            Db::insert('tvbox_rule', array(
                'rule_name'  => '演示规则-过滤广告域名',
                'rule_hosts' => json_encode(array('ads.example.com', 'stat.example.com')),
                'rule_regex' => json_encode(array('#EXT-X-DISCONTINUITY', 'ad_?\\d+')),
                'sort_order' => 10,
                'status'     => 1,
            ));
            $log[] = '过滤规则：1 条';
        }

        // ---------------- 广告 & Flag ----------------
        if ((int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_ad'), array(), 0) === 0) {
            foreach (array('ads.example.com', 'stat.example.com', 'promo.example.com') as $i => $h) {
                Db::insert('tvbox_ad', array('ad_host' => $h, 'sort_order' => 10 - $i, 'status' => 1));
            }
            $log[] = '广告拦截域名：3 条';
        }
        if ((int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_flag'), array(), 0) === 0) {
            foreach (array('qq', 'iqiyi', 'youku', 'mgtv', 'bilibili') as $i => $f) {
                Db::insert('tvbox_flag', array('flag_text' => $f, 'sort_order' => 100 - $i, 'status' => 1));
            }
            $log[] = 'VIP flag：5 条';
        }

        // ---------------- IJK 参数 ----------------
        if ((int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_ijk'), array(), 0) === 0) {
            $ijks = array(
                array('软解码', 1, 'iformat', '0'),
                array('硬解码', 2, 'mediacodec', '1'),
                array('低延迟', 4, 'framepack', '1'),
            );
            foreach ($ijks as $i => $j) {
                Db::insert('tvbox_ijk', array(
                    'group_name' => $j[0],
                    'category'   => $j[1],
                    'opt_name'   => $j[2],
                    'opt_value'  => $j[3],
                    'sort_order' => 10 - $i,
                    'status'     => 1,
                ));
            }
            $log[] = 'IJK 参数：3 条';
        }

        // ---------------- 配置中心 ----------------
        $cfgs = array(
            array('global', array('checkInterval' => 30, 'bootStart' => true, 'hin2nEntry' => true), '全局设置'),
            array('notice', array(array('text' => '欢迎使用 TVBox × MacCMS 插件', 'url' => '')), '公告'),
            array('home', array('name' => 'TVBox 演示', 'logo' => '', 'bg' => ''), '首页外观'),
            array('upgrade', array('version' => '1.0.0', 'url' => ''), '升级配置'),
            array('secretKey', 'tvbox888', '接口密钥（管理后台登录密码）'),
        );
        $cfgCount = 0;
        foreach ($cfgs as $c) {
            $exists = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('tvbox_config') . ' WHERE cfg_key=?', array($c[0]), 0);
            $val = is_array($c[1]) ? json_encode($c[1], JSON_UNESCAPED_UNICODE) : (string)$c[1];
            $type = is_array($c[1]) ? 1 : 3;
            if ($exists) {
                continue;
            }
            Db::insert('tvbox_config', array(
                'cfg_key'  => $c[0],
                'cfg_val'  => $val,
                'cfg_type' => $type,
                'remark'   => $c[2],
            ));
            $cfgCount++;
        }
        $log[] = "配置项：{$cfgCount} 条";

        return array('ok' => true, 'log' => $log);
    }
}
