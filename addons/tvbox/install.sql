-- ============================================================================
-- TVBox for MacCMS 插件数据表
-- __PREFIX__ 会被自动替换为 MacCMS 的表前缀
-- 所有表均为 CREATE TABLE IF NOT EXISTS，可重复执行
-- ============================================================================

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_config` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `cfg_key` varchar(50) NOT NULL DEFAULT '' COMMENT '配置键',
  `cfg_val` mediumtext COMMENT '配置值(JSON)',
  `cfg_type` tinyint(1) unsigned NOT NULL DEFAULT '1' COMMENT '1=JSON 2=数组 3=字符串',
  `remark` varchar(255) NOT NULL DEFAULT '' COMMENT '备注',
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `cfg_key` (`cfg_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox 配置';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_site` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `site_key` varchar(60) NOT NULL DEFAULT '' COMMENT '站点标识',
  `site_name` varchar(80) NOT NULL DEFAULT '' COMMENT '站点名称',
  `site_type` tinyint(2) unsigned NOT NULL DEFAULT '1' COMMENT '0=XML 1=JSON 3=Spider',
  `site_api` varchar(1024) NOT NULL DEFAULT '' COMMENT '接口地址',
  `site_ext` text COMMENT '扩展参数(JSON)',
  `jar` varchar(1024) NOT NULL DEFAULT '' COMMENT 'Spider jar 地址',
  `categories` text COMMENT '分类白名单(JSON)',
  `style` tinyint(2) unsigned NOT NULL DEFAULT '0' COMMENT '站点风格 0=默认',
  `timeout` smallint(6) unsigned NOT NULL DEFAULT '10' COMMENT '超时秒数',
  `searchable` tinyint(1) unsigned NOT NULL DEFAULT '1',
  `quick_search` tinyint(1) unsigned NOT NULL DEFAULT '1',
  `filterable` tinyint(1) unsigned NOT NULL DEFAULT '1',
  `changeable` tinyint(1) unsigned NOT NULL DEFAULT '1',
  `player_type` tinyint(2) NOT NULL DEFAULT '-1',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `hidden` tinyint(1) unsigned NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `site_key` (`site_key`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox 点播站点';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_live` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `live_name` varchar(80) NOT NULL DEFAULT '' COMMENT '直播源名称',
  `live_type` tinyint(2) unsigned NOT NULL DEFAULT '0' COMMENT '0=M3U 1=TXT 2=Spider',
  `live_url` varchar(1024) NOT NULL DEFAULT '' COMMENT '直播源地址',
  `live_epg` varchar(1024) NOT NULL DEFAULT '' COMMENT 'EPG 地址',
  `live_logo` varchar(1024) NOT NULL DEFAULT '' COMMENT '台标',
  `live_header` text COMMENT '请求头(JSON)',
  `ua` varchar(512) NOT NULL DEFAULT '' COMMENT 'User-Agent',
  `css` text COMMENT '频道样式 css',
  `ext` text COMMENT '扩展参数(JSON)',
  `groups` text COMMENT '频道分组(JSON)',
  `timeout` smallint(6) unsigned NOT NULL DEFAULT '10',
  `player_type` tinyint(2) NOT NULL DEFAULT '-1',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox 直播源';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_parse` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `parse_name` varchar(80) NOT NULL DEFAULT '' COMMENT '解析名称',
  `parse_type` tinyint(2) unsigned NOT NULL DEFAULT '0' COMMENT '0=JSON 1=WebView',
  `parse_url` varchar(1024) NOT NULL DEFAULT '' COMMENT '解析地址',
  `parse_flag` varchar(255) NOT NULL DEFAULT '' COMMENT '适用 flag，逗号分隔',
  `parse_ext` text COMMENT '扩展(JSON)',
  `parse_header` text COMMENT '解析请求头(JSON)',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox VIP 解析器';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_rule` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `rule_name` varchar(80) NOT NULL DEFAULT '' COMMENT '规则名称',
  `rule_hosts` text COMMENT 'Host 列表(JSON)',
  `rule_regex` text COMMENT '正则列表(JSON)',
  `rule_filter` text COMMENT '过滤规则(JSON)',
  `rule_exclude` text COMMENT '排除规则(JSON)',
  `rule_script` text COMMENT '脚本规则(JSON)',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox 过滤规则';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_flag` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `flag_text` varchar(60) NOT NULL DEFAULT '' COMMENT 'flag 文本',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox VIP flag';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_ad` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `ad_host` varchar(255) NOT NULL DEFAULT '' COMMENT '广告域名',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox 广告拦截域名';

CREATE TABLE IF NOT EXISTS `__PREFIX__tvbox_ijk` (
  `id` int(10) unsigned NOT NULL AUTO_INCREMENT,
  `group_name` varchar(60) NOT NULL DEFAULT '' COMMENT '分组名称',
  `category` tinyint(2) unsigned NOT NULL DEFAULT '1' COMMENT '1=format 2=codec 4=player',
  `opt_name` varchar(60) NOT NULL DEFAULT '' COMMENT '参数名',
  `opt_value` varchar(255) NOT NULL DEFAULT '' COMMENT '参数值',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` tinyint(1) unsigned NOT NULL DEFAULT '1',
  PRIMARY KEY (`id`),
  KEY `status` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='TVBox IJK 参数';
