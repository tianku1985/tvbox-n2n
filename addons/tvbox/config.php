<?php
// ============================================================================
// TVBox 插件配置项（MacCMS10 规范）
// 后台"插件管理 → TVBox → 配置"按钮点击时，根据这里的数组自动生成表单
// get_addon_config('tvbox') 读取 value 值
// ============================================================================

return [

    // ---- 伪静态规则（开启路由模式后生效） ----
    [
        'name'    => 'rewrite',
        'title'   => '伪静态规则',
        'type'    => 'array',
        'content' => [],
        'value'   => [
            'api/vod'    => '/tvbox/vod',
            'api/sites'  => '/tvbox/sites',
            'api/config' => '/tvbox/config',
            'api/live'   => '/tvbox/live',
            'api/ping'   => '/tvbox/ping',
        ],
        'rule'    => '',
        'msg'     => '',
        'tip'     => '开启路由模式后，可以通过 rewrite 里的简化 URL 访问插件',
        'ok'      => '',
        'extend'  => '',
    ],

    // ---- 加密密钥（空=ke 前缀零配置模式） ----
    [
        'name'    => 'secret_key',
        'title'   => '接口加密固定密钥',
        'type'    => 'string',
        'content' => [],
        'value'   => '',
        'rule'    => '',
        'msg'     => '',
        'tip'     => '留空则使用 ke 前缀零配置模式（推荐）。填写则启用固定密钥 AES-128-ECB 加密，客户端需配置相同秘钥。',
        'ok'      => '',
        'extend'  => '',
    ],

    // ---- 插件启用状态 ----
    [
        'name'    => 'api_enable',
        'title'   => '启用对外 API',
        'type'    => 'radio',
        'content' => [
            'on'  => '启用',
            'off' => '关闭',
        ],
        'value'   => 'on',
        'rule'    => 'required',
        'msg'     => '',
        'tip'     => '关闭后 /addons/tvbox/api/* 所有请求直接返回 403',
        'ok'      => '',
        'extend'  => '',
    ],

];
