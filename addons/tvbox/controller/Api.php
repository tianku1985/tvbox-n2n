<?php
// ============================================================================
// TVBox 插件 - 对外 API 控制器（纯 PHP，零框架依赖）
// 输出统一 JSON，任何异常都转成 JSON 错误，绝不白屏
// ============================================================================

namespace addons\tvbox\controller;

use addons\tvbox\lib\Db;
use addons\tvbox\lib\Config;

class Api
{
    /** 输出 JSON 并结束 */
    public static function json($data, $code = 200)
    {
        if (!headers_sent()) {
            http_response_code($code);
            header('Content-Type: application/json; charset=utf-8');
            header('Access-Control-Allow-Origin: *');
            header('Cache-Control: no-cache');
        }
        echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    /** 统一错误 */
    public static function fail($msg, $code = 0)
    {
        self::json(array('code' => $code, 'msg' => $msg, 'list' => array()));
    }

    /**
     * 入口分发
     * @param string $action ping|vod|sites|config|live
     */
    public static function run($action)
    {
        try {
            switch ($action) {
                case 'ping':
                    return self::ping();
                case 'vod':
                    return self::vod();
                case 'sites':
                case 'config':
                    return self::sites();
                case 'live':
                    return self::live();
                default:
                    self::fail('未知接口：' . $action);
            }
        } catch (\Throwable $e) {
            self::json(array(
                'code' => 0,
                'msg'  => '接口异常：' . $e->getMessage(),
                'list' => array(),
            ));
        }
    }

    // ---------------------------------------------------------------- ping
    public static function ping()
    {
        self::json(array(
            'code' => 1,
            'msg'  => 'pong',
            'time' => time(),
            'name' => 'TVBox for MacCMS',
        ));
    }

    // ---------------------------------------------------------------- vod
    public static function vod()
    {
        $ac = isset($_GET['ac']) && $_GET['ac'] !== '' ? strtolower(trim($_GET['ac'])) : 'list';
        if ($ac === 'videolist') {
            $ac = 'detail';
        }

        switch ($ac) {
            case 'class':
            case 'classes':
                return self::vodClass();
            case 'search':
                return self::vodSearch();
            case 'detail':
                return self::vodDetail();
            case 'list':
            default:
                return self::vodList();
        }
    }

    /** 分类列表 */
    public static function vodClass()
    {
        $rows = Db::all(
            'SELECT type_id, type_name, type_pid, type_sort FROM ' . Db::table('type') .
            ' WHERE type_mid=1 AND type_status=1 ORDER BY type_pid ASC, type_sort ASC, type_id ASC'
        );

        $result = array();
        foreach ($rows as $r) {
            if ((int)$r['type_pid'] !== 0) {
                continue;
            }
            $children = array();
            foreach ($rows as $c) {
                if ((int)$c['type_pid'] === (int)$r['type_id']) {
                    $children[] = array('type_id' => (int)$c['type_id'], 'type_name' => $c['type_name']);
                }
            }
            $result[] = array(
                'type_id'   => (int)$r['type_id'],
                'type_name' => $r['type_name'],
                'type_pid'  => 0,
                'children'  => $children,
            );
        }

        self::json(array(
            'code'      => 1,
            'msg'       => 'ok',
            'page'      => 1,
            'pagecount' => 1,
            'limit'     => (string)count($result),
            'total'     => count($result),
            'class'     => $result,
            'list'      => $result,
        ));
    }

    /** 影片列表 */
    public static function vodList()
    {
        $page  = max(1, (int)(isset($_GET['page']) ? $_GET['page'] : 1));
        $limit = (int)(isset($_GET['limit']) ? $_GET['limit'] : 20);
        $limit = $limit < 1 ? 20 : ($limit > 100 ? 100 : $limit);
        $tid   = (int)(isset($_GET['t']) ? $_GET['t'] : (isset($_GET['tid']) ? $_GET['tid'] : 0));

        $where  = 'vod_status=1';
        $params = array();
        if ($tid > 0) {
            $where .= ' AND (type_id=? OR type_id_1=?)';
            $params[] = $tid;
            $params[] = $tid;
        }

        $total  = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('vod') . ' WHERE ' . $where, $params, 0);
        $offset = ($page - 1) * $limit;
        $rows   = Db::all(
            'SELECT * FROM ' . Db::table('vod') . ' WHERE ' . $where .
            ' ORDER BY vod_time DESC, vod_id DESC LIMIT ' . $offset . ',' . $limit,
            $params
        );

        self::json(array(
            'code'      => 1,
            'msg'       => 'ok',
            'page'      => $page,
            'pagecount' => (int)ceil($total / $limit),
            'limit'     => (string)$limit,
            'total'     => $total,
            'list'      => self::formatList($rows),
        ));
    }

    /** 搜索 */
    public static function vodSearch()
    {
        $wd    = trim((string)(isset($_GET['wd']) ? $_GET['wd'] : (isset($_GET['keyword']) ? $_GET['keyword'] : '')));
        $page  = max(1, (int)(isset($_GET['page']) ? $_GET['page'] : 1));
        $limit = (int)(isset($_GET['limit']) ? $_GET['limit'] : 20);
        $limit = $limit < 1 ? 20 : ($limit > 100 ? 100 : $limit);

        if ($wd === '') {
            self::json(array('code' => 1, 'msg' => 'ok', 'page' => 1, 'pagecount' => 0, 'limit' => (string)$limit, 'total' => 0, 'list' => array()));
        }

        $like   = '%' . $wd . '%';
        $where  = 'vod_status=1 AND (vod_name LIKE ? OR vod_sub LIKE ? OR vod_actor LIKE ?)';
        $params = array($like, $like, $like);

        $total  = (int)Db::value('SELECT COUNT(*) FROM ' . Db::table('vod') . ' WHERE ' . $where, $params, 0);
        $offset = ($page - 1) * $limit;
        $rows   = Db::all(
            'SELECT * FROM ' . Db::table('vod') . ' WHERE ' . $where .
            ' ORDER BY vod_time DESC, vod_id DESC LIMIT ' . $offset . ',' . $limit,
            $params
        );

        self::json(array(
            'code'      => 1,
            'msg'       => 'ok',
            'page'      => $page,
            'pagecount' => (int)ceil($total / $limit),
            'limit'     => (string)$limit,
            'total'     => $total,
            'list'      => self::formatList($rows),
        ));
    }

    /** 详情 */
    public static function vodDetail()
    {
        $ids = isset($_GET['ids']) ? $_GET['ids'] : (isset($_GET['id']) ? $_GET['id'] : '');
        $ids = trim((string)$ids);
        if ($ids === '') {
            return self::vodList();
        }
        $arr = array_filter(array_map('intval', explode(',', $ids)));
        if (!$arr) {
            self::fail('ids 参数无效');
        }
        $in   = implode(',', $arr);
        $rows = Db::all('SELECT * FROM ' . Db::table('vod') . ' WHERE vod_id IN (' . $in . ')');

        self::json(array(
            'code'      => 1,
            'msg'       => 'ok',
            'page'      => 1,
            'pagecount' => 1,
            'limit'     => (string)count($rows),
            'total'     => count($rows),
            'list'      => self::formatList($rows, true),
        ));
    }

    /** 格式化影片数据 */
    protected static function formatList($rows, $detail = false)
    {
        $map = array();
        foreach (Db::all('SELECT type_id, type_name FROM ' . Db::table('type')) as $t) {
            $map[(int)$t['type_id']] = $t['type_name'];
        }

        $list = array();
        foreach ($rows as $r) {
            $item = array(
                'vod_id'        => (int)$r['vod_id'],
                'vod_name'      => $r['vod_name'],
                'vod_sub'       => isset($r['vod_sub']) ? $r['vod_sub'] : '',
                'type_id'       => (int)$r['type_id'],
                'type_name'     => isset($map[(int)$r['type_id']]) ? $map[(int)$r['type_id']] : '',
                'vod_pic'       => $r['vod_pic'],
                'vod_pic_thumb' => isset($r['vod_pic_thumb']) ? $r['vod_pic_thumb'] : '',
                'vod_remarks'   => $r['vod_remarks'],
                'vod_year'      => isset($r['vod_year']) ? $r['vod_year'] : '',
                'vod_area'      => isset($r['vod_area']) ? $r['vod_area'] : '',
                'vod_lang'      => isset($r['vod_lang']) ? $r['vod_lang'] : '',
                'vod_actor'     => isset($r['vod_actor']) ? $r['vod_actor'] : '',
                'vod_director'  => isset($r['vod_director']) ? $r['vod_director'] : '',
                'vod_score'     => isset($r['vod_score']) ? (string)$r['vod_score'] : '0.0',
                'vod_class'     => isset($r['vod_class']) ? $r['vod_class'] : '',
                'vod_time'      => date('Y-m-d H:i:s', (int)$r['vod_time']),
            );
            if ($detail) {
                $item['vod_content']   = isset($r['vod_content']) ? $r['vod_content'] : '';
                $item['vod_play_from'] = isset($r['vod_play_from']) ? $r['vod_play_from'] : '';
                $item['vod_play_url']  = isset($r['vod_play_url']) ? $r['vod_play_url'] : '';
                $item['vod_pubdate']   = isset($r['vod_pubdate']) ? $r['vod_pubdate'] : '';
            }
            $list[] = $item;
        }
        return $list;
    }

    // ---------------------------------------------------------------- 配置
    /**
     * 输出完整 TVBox config.json
     * 同时作为 /api/sites 与 /api/config 两个地址使用
     */
    public static function sites()
    {
        self::json(Config::build());
    }

    // ---------------------------------------------------------------- live
    public static function live()
    {
        $format = isset($_GET['format']) ? strtolower(trim($_GET['format'])) : 'json';
        $lives  = Config::lives();

        if ($format === 'm3u' || $format === 'm3u8') {
            if (!headers_sent()) {
                header('Content-Type: application/vnd.apple.mpegurl; charset=utf-8');
            }
            echo "#EXTM3U\n";
            foreach ($lives as $l) {
                echo '#EXTINF:-1 tvg-name="' . $l['name'] . '" tvg-logo="' . (isset($l['logo']) ? $l['logo'] : '') . '",' . $l['name'] . "\n";
                echo $l['url'] . "\n";
            }
            exit;
        }

        self::json(array('code' => 1, 'msg' => 'ok', 'lives' => $lives, 'list' => $lives));
    }

    // ---------------------------------------------------------------- 工具
    /** 当前站点根地址 */
    public static function baseUrl()
    {
        return Config::host();
    }
}
