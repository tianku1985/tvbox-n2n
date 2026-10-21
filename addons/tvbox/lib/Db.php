<?php
// ============================================================================
// TVBox 插件 - 数据库访问层（纯 PDO，零框架依赖）
// 自动读取 MacCMS 的 application/database.php，复用其库名与表前缀
// ============================================================================

namespace addons\tvbox\lib;

class Db
{
    /** @var \PDO|null */
    protected static $pdo = null;

    /** @var string 表前缀 */
    protected static $prefix = 'mac_';

    /** @var string MacCMS 根目录 */
    protected static $root = '';

    /** @var string 最近一次错误 */
    public static $error = '';

    /**
     * 初始化连接
     */
    public static function boot($root = null)
    {
        if (self::$pdo !== null) {
            return;
        }
        if ($root !== null) {
            self::$root = rtrim(str_replace('\\', '/', $root), '/') . '/';
        } elseif (self::$root === '') {
            // 未显式传入时，自动推导 MacCMS 根目录（插件目录的上两级）
            if (defined('TVBOX_MAC_ROOT')) {
                self::$root = rtrim(str_replace('\\', '/', TVBOX_MAC_ROOT), '/') . '/';
            } else {
                self::$root = rtrim(str_replace('\\', '/', dirname(dirname(__DIR__))), '/') . '/';
            }
        }
        $cfgFile = self::$root . 'application/database.php';
        $cfg = is_file($cfgFile) ? (array)include $cfgFile : array();

        self::$prefix = isset($cfg['prefix']) && $cfg['prefix'] !== '' ? $cfg['prefix'] : 'mac_';
        $host    = isset($cfg['hostname']) ? $cfg['hostname'] : '127.0.0.1';
        $port    = isset($cfg['hostport']) && $cfg['hostport'] ? $cfg['hostport'] : '3306';
        $name    = isset($cfg['database']) ? $cfg['database'] : 'maccms';
        $user    = isset($cfg['username']) ? $cfg['username'] : 'root';
        $pass    = isset($cfg['password']) ? $cfg['password'] : '';
        $charset = isset($cfg['charset']) && $cfg['charset'] ? $cfg['charset'] : 'utf8';

        $dsn = "mysql:host={$host};port={$port};dbname={$name};charset={$charset}";
        self::$pdo = new \PDO($dsn, $user, $pass, array(
            \PDO::ATTR_ERRMODE            => \PDO::ERRMODE_EXCEPTION,
            \PDO::ATTR_DEFAULT_FETCH_MODE => \PDO::FETCH_ASSOC,
        ));
    }

    /**
     * 获取 PDO
     * @return \PDO
     */
    public static function pdo()
    {
        self::boot();
        return self::$pdo;
    }

    /**
     * 表前缀
     */
    public static function prefix()
    {
        self::boot();
        return self::$prefix;
    }

    /**
     * 完整表名
     */
    public static function table($name)
    {
        return '`' . self::prefix() . $name . '`';
    }

    /**
     * 执行语句
     */
    public static function exec($sql, $params = array())
    {
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute($params);
            return $st->rowCount();
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return 0;
        }
    }

    /**
     * 查询多行
     */
    public static function all($sql, $params = array())
    {
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute($params);
            return $st->fetchAll();
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return array();
        }
    }

    /**
     * 查询单行
     */
    public static function one($sql, $params = array())
    {
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute($params);
            $row = $st->fetch();
            return $row === false ? null : $row;
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return null;
        }
    }

    /**
     * 查询单个值
     */
    public static function value($sql, $params = array(), $default = null)
    {
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute($params);
            $v = $st->fetchColumn();
            return $v === false ? $default : $v;
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return $default;
        }
    }

    /**
     * 插入
     */
    public static function insert($table, array $data)
    {
        $cols = array();
        $marks = array();
        $vals = array();
        foreach ($data as $k => $v) {
            $cols[] = '`' . $k . '`';
            $marks[] = '?';
            $vals[] = $v;
        }
        $sql = 'INSERT INTO ' . self::table($table) . ' (' . implode(',', $cols) . ') VALUES (' . implode(',', $marks) . ')';
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute($vals);
            return (int)self::pdo()->lastInsertId();
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return 0;
        }
    }

    /**
     * 更新
     */
    public static function update($table, array $data, $where, array $whereParams = array())
    {
        $sets = array();
        $vals = array();
        foreach ($data as $k => $v) {
            $sets[] = '`' . $k . '`=?';
            $vals[] = $v;
        }
        $sql = 'UPDATE ' . self::table($table) . ' SET ' . implode(',', $sets) . ' WHERE ' . $where;
        try {
            $st = self::pdo()->prepare($sql);
            $st->execute(array_merge($vals, $whereParams));
            return $st->rowCount();
        } catch (\Throwable $e) {
            self::$error = $e->getMessage();
            return 0;
        }
    }

    /**
     * 删除
     */
    public static function delete($table, $where, array $params = array())
    {
        return self::exec('DELETE FROM ' . self::table($table) . ' WHERE ' . $where, $params);
    }

    /**
     * 是否存在某库表
     */
    public static function tableExists($table)
    {
        $t = self::prefix() . $table;
        $v = self::value('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?', array($t), 0);
        return (int)$v > 0;
    }
}
