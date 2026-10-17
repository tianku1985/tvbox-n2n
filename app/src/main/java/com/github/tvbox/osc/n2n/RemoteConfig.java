package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * 内置配置接口：TVBox 的全部可配置项统一来自 APK 内置 assets/tvbox-config.json。
 *
 * 远程配置：若内置配置指定了 remoteConfigUrl，启动时会异步拉取该远程 json，
 * 覆盖「除 settings（设置页）和 hin2n（n2n 组网）之外」的所有段；
 * 设置页与 n2n 的配置永远只读写本地内置 json，不被远程覆盖。
 * remoteConfigUrl 留空或远程无法访问时，全部使用内置 json（含上次成功拉取的远程缓存兜底）。
 * 开机启动（TVBox 开机自启 + hin2n 开机自动连 VPN）仍然保留。
 *
 * JSON 结构（所有字段可选，缺省保持本地现状；支持 // 与 /* 注释、尾逗号）：
 * {
 *   "remoteConfigUrl": "https://.../config.json",     // 远程配置地址（空=不启用远程）
 *   "notice": {                                       // 公告：字符串（旧格式）或对象
 *     "text": "公告文本",
 *     "position": "bottom",                           // top | bottom
 *     "bgColor": "#CC1B1B1B", "fontColor": "#FFFFD54F",
 *     "fontSize": 18, "alpha": 100, "duration": 15,
 *     "show": { "home": true, "live": true, "vod": true },
 *     "timeStart": "08:00", "timeEnd": "22:00", "expire": "2027-12-31",
 *     "speed": 60
 *   },
 *   "home": { "bgImage": "...", "sourceName": "...", "entryBgColor": "...", "iconColor": "...",
 *             "textColor": "...", "alpha": 90, "inputBgColor": "...", "buttonBgColor": "..." },
 *             // sourceName=首页左上角默认文字（用户未配置点播接口时显示）
 *   "tkp2p": { "engine": "auto|hls|media", "announce": [...], "token": "",
 *              "zone": "cn", "iceServers": [...] },   // iceServers 也可简写 ice
 *   "checkInterval": 30,                              // 配置检查间隔（分钟，本地模式下列出兼容）
 *   "ua": "Mozilla/5.0 ...",                          // 全局 UA
 *   "bootStart": true,                                // TVBox 开机自启（用户手动改过后不覆盖）
 *   "api": "...", "live": "...", "epg": "...", "proxy": "...",
 *   "theme": "violet",                                // 主题：索引 / 中文名 / 英文别名
 *   "themes": [ {"name":"红色","alias":"red","style":0,"color":"#D81F26",
 *                "dark":"#8C1418","bgStart":"#3D0A0D","bgEnd":"#D81F26",
 *                "bgImage":"url" 或 "bgImages":["url1","url2"]} ],
 *   "upgrade": {                                      // TVBox APK 自检升级（可被酒店桌面定制使用）
 *     "checkUrl": "https://.../version.json",         // 远程版本信息地址（优先），或直接内联下列字段
 *     "versionCode": 2, "versionName": "1.0.2", "url": "https://.../x.apk",
 *     "force": false, "notes": "更新内容" },
 *   "hin2nEntry": true,                               // 首页是否显示 hin2n 固定入口
 *   "hin2n": {                                        // hin2n 组网模块配置（仅首启 DB 为空时播种）
 *     "bootStart": false,                             // 开机自动连接 n2n
 *     "staticRoutes": "192.168.8.0/24 10.0.0.13",
 *     "configs": [ { ... 28 个字段，见 docs/hin2n.md ... } ]
 *   },
 *   "entries": [ {"name":"文件管理","pkg":"...","icon":"...","url":"..."} ],
 *   "topEntries": [                               // 顶栏整体替换（缺省=内置默认顶栏）
 *     {"name":"搜索","type":"page","target":"search","icon":"hm_search"},
 *     {"name":"切源","type":"feature","target":"siteSwitch","icon":"hm_drawer"},
 *     {"name":"文件","type":"app","pkg":"com.x","icon":"","url":"http://.../a.apk"},
 *     {"name":"影院","type":"vod","target":"assets/vod.json","icon":"hm_vod","theme":"1"} ],
 *   "homeEntries": [ ...同 topEntries... ]        // 历史一行整体替换
 * }
 *
 * 入口类型：page=打开 TVBox 界面（target: vod/live/search/history/collect/push/
 * setting/drive/hin2n/apps/fastsearch）；feature=内置功能（target: siteSwitch/
 * themeSwitch/styleSwitch/clearCache/reloadHome/wifi/date）；app=打开其他 App
 * （pkg，未安装且配 url 时后台下载安装）；vod=打开自定义点播列表界面（target=
 * 数据接口，格式同点播 api json 但仅供该界面，不影响 app 其它配置）。
 *
 * 覆盖规则：notice/home/tkp2p/entries/themes/hin2nEntry/checkInterval/ua 每次启动应用；
 * bootStart 只作为默认值写入一次；api/live/epg/proxy 仅在用户未配置时作为默认值。
 */
public class RemoteConfig {

    /** Hawk 键：缓存与开关（K_URL / hin2nUrl / 远程更新相关键已随远程功能移除） */
    public static final String K_NOTICE = "rc_notice";
    public static final String K_NOTICE_STYLE = "rc_notice_style";
    public static final String K_HOME_STYLE = "rc_home_style";
    public static final String K_TKP2P = "rc_tkp2p";
    public static final String K_CHECK_INTERVAL = "rc_check_interval";
    public static final String K_UA = "rc_ua";
    public static final String K_ENTRIES = "rc_entries";
    public static final String K_TOP_ENTRIES = "rc_top_entries";
    public static final String K_HOME_ENTRIES = "rc_home_entries";
    public static final String K_UPGRADE = "rc_upgrade";
    public static final String K_HIN2N = "rc_hin2n_entry";
    public static final String K_BOOT = "n2n_boot_start";      // TVBox 开机自启开关（用户值优先）
    public static final String K_BOOT_DEFAULT_SET = "n2n_boot_default_set"; // 内置默认值是否已写入过
    /** 最近一次成功拉取的远程配置原文（断网/下次启动兜底） */
    public static final String K_REMOTE_RAW = "rc_remote_raw";

    /** 内置配置（app/src/main/assets/ 下，随 APK 打包） */
    private static final String LOCAL_ASSET = "tvbox-config.json";

    /** 最近一次加载的内置 JSON（hin2n 播种等逻辑读取） */
    private static volatile JSONObject sConfig = null;

    /** 单个自定义入口 */
    public static class Entry {
        public String name;
        public String type;    // page=打开 TVBox 界面 | feature=内置功能 | app=打开其他 App | vod=自定义点播界面
        public String target;  // page: vod/live/search/history/collect/push/setting/drive/hin2n/apps/fastsearch
                               // feature: siteSwitch/themeSwitch/styleSwitch/clearCache/reloadHome/wifi/date
                               // vod: 数据接口地址（http(s):// 或 assets/xxx.json），格式同点播 api json 但仅供新界面
        public String pkg;     // app: 包名
        public String icon;    // 图标：http(s) URL 或内置 drawable 名（如 hm_search），空=兜底图标
        public String url;     // app 未安装时的 APK 下载地址（可选）
        public String theme;   // vod: 自定义点播界面独立主题（主题索引/中文名/英文别名），空=跟随全局主题
    }

    public interface Callback {
        void onDone(boolean success);
    }

    /** 最近一次加载的内置配置原始 JSON（无则空对象，不为 null） */
    public static JSONObject raw() {
        return sConfig != null ? sConfig : new JSONObject();
    }

    public static String notice() {
        return Hawk.get(K_NOTICE, "");
    }

    public static JSONObject noticeStyle() {
        try {
            return new JSONObject(Hawk.get(K_NOTICE_STYLE, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static JSONObject homeStyle() {
        try {
            return new JSONObject(Hawk.get(K_HOME_STYLE, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /**
     * 首页左上角源名按钮的默认显示文字（home 段 "sourceName" 字段）。
     * 仅当用户尚未配置点播接口（api_url 为空）或当前源未就绪时作为兜底显示；
     * 用户配置过点播接口后，左上角显示当前源名称。
     */
    public static String homeSourceName() {
        String sn = homeStyle().optString("sourceName", "");
        return sn == null ? "" : sn.trim();
    }

    public static JSONObject tkP2pStyle() {
        try {
            return new JSONObject(Hawk.get(K_TKP2P, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static int checkIntervalMinutes() {
        return Math.max(1, Hawk.get(K_CHECK_INTERVAL, 30));
    }

    public static String ua() {
        String u = Hawk.get(K_UA, "");
        return u == null ? "" : u.trim();
    }

    public static boolean hin2nEntryEnabled() {
        return Hawk.get(K_HIN2N, true);
    }

    public static boolean bootStartEnabled() {
        return Hawk.get(K_BOOT, false);
    }

    /** 读取已缓存的自定义入口列表（旧版 "entries"，仅 app 类型，顶栏兜底） */
    public static List<Entry> entries() {
        return parseEntries(Hawk.get(K_ENTRIES, ""));
    }

    /**
     * 顶栏入口配置（"topEntries"）：返回 null 表示未配置（保持内置默认顶栏）；
     * 非 null（可为空列表）表示整体替换顶栏图标，顺序/内容/图标全部按 JSON。
     */
    public static List<Entry> topEntries() {
        String raw = Hawk.get(K_TOP_ENTRIES, null);
        return raw == null ? null : parseEntries(raw);
    }

    /** 首页历史一行入口配置（"homeEntries"）：null=保持内置默认行；非 null=整体替换 */
    public static List<Entry> homeEntries() {
        String raw = Hawk.get(K_HOME_ENTRIES, null);
        return raw == null ? null : parseEntries(raw);
    }

    /** 远程升级配置（"upgrade" 段）：未配置返回 null */
    public static JSONObject upgrade() {
        String raw = Hawk.get(K_UPGRADE, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Entry> parseEntries(String raw) {
        List<Entry> list = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Entry e = new Entry();
                e.name = o.optString("name", "");
                e.type = o.optString("type", "").trim();
                e.target = o.optString("target", "").trim();
                e.pkg = o.optString("pkg", "");
                e.icon = o.optString("icon", "");
                e.url = o.optString("url", "");
                e.theme = o.optString("theme", "").trim();
                if (e.type.isEmpty()) {
                    // 兼容旧格式：有 pkg 即 app 入口
                    e.type = e.pkg.isEmpty() ? "page" : "app";
                }
                if (e.name.isEmpty()) {
                    e.name = e.pkg;
                }
                list.add(e);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /**
     * App 启动时调用：同步读取内置 assets/tvbox-config.json 并应用（含 settings 与 hin2n 段）。
     * 若内置配置指定了 remoteConfigUrl，另起后台线程拉取远程 json，覆盖除 settings/hin2n 之外的所有段。
     */
    public static void bootstrap(Context ctx) {
        if (ctx == null) {
            return;
        }
        try {
            byte[] data = readAsset(ctx.getApplicationContext(), LOCAL_ASSET);
            if (data != null) {
                JSONObject cfg = parseLoose(new String(data, "UTF-8"));
                sConfig = cfg;
                apply(ctx.getApplicationContext(), cfg, false);

                // 远程接口：拉取成功后覆盖非设置/非n2n段；失败则保持内置
                String remoteUrl = cfg.optString("remoteConfigUrl", "").trim();
                if (!remoteUrl.isEmpty()) {
                    fetchRemoteAsync(ctx.getApplicationContext(), remoteUrl);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 后台线程拉取远程 json 并应用（非 settings/hin2n 段） */
    private static void fetchRemoteAsync(final Context app, final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                fetchAndApply(app, url, null);
            }
        }, "rc-remote-fetch").start();
    }

    /** 同步拉取远程 json 并应用；cb 可为空 */
    private static boolean fetchAndApply(Context app, String url, Callback cb) {
        try {
            String raw = httpGet(url, RemoteConfig.ua());
            if (raw == null || raw.isEmpty()) {
                if (cb != null) cb.onDone(false);
                return false;
            }
            JSONObject remote = parseLoose(raw);
            apply(app, remote, true);
            Hawk.put(K_REMOTE_RAW, raw);
            if (cb != null) cb.onDone(true);
            return true;
        } catch (Throwable t) {
            // 网络失败/解析失败：用上次缓存的远程配置兜底（若有）
            try {
                String cached = Hawk.get(K_REMOTE_RAW, "");
                if (cached != null && !cached.isEmpty()) {
                    JSONObject remote = parseLoose(cached);
                    apply(app, remote, true);
                }
            } catch (Throwable ignored) {
            }
            if (cb != null) cb.onDone(false);
            return false;
        }
    }

    /** 简单 HttpURLConnection GET（带 UA，超时 8s） */
    private static String httpGet(String urlStr, String ua) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", ua == null || ua.isEmpty()
                    ? "Mozilla/5.0 (Linux; Android) TVBox" : ua);
            conn.setRequestProperty("Accept", "application/json,*/*");
            int code = conn.getResponseCode();
            in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignored) {} }
            if (conn != null) { conn.disconnect(); }
        }
    }

    /**
     * 远程拉取入口：从内置配置的 remoteConfigUrl 拉取并应用（非 settings/hin2n 段）。
     * 未配置 remoteConfigUrl 时直接回调成功（保持内置）。
     */
    public static void fetchAuto(Context ctx, Callback cb) {
        String url = remoteConfigUrl(ctx);
        if (url.isEmpty()) {
            if (cb != null) cb.onDone(true);
            return;
        }
        fetchAndApply(ctx.getApplicationContext(), url, cb);
    }

    /** 同步拉取指定远程地址并应用 */
    public static void fetch(Context ctx, Callback cb) {
        fetchAuto(ctx, cb);
    }

    /** 内置配置中的远程 json 地址 */
    public static String remoteConfigUrl(Context ctx) {
        try {
            if (sConfig != null && sConfig.has("remoteConfigUrl")) {
                return sConfig.optString("remoteConfigUrl", "").trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 远程 APK 更新入口已移除：BaseActivity.onResume 仍会调用，这里空实现 */
    public static void tryPendingUpdate(Activity act) {
    }

    private static byte[] readAsset(Context app, String name) {
        InputStream in = null;
        try {
            in = app.getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 宽容解析：除标准 JSON 外，还兼容 jsonc 写法 ——
     * 去掉 UTF-8 BOM、剥除 // 行注释与 /* 块注释（字符串内不剥，URL 不受影响）、容忍尾逗号。
     */
    private static JSONObject parseLoose(String raw) throws Exception {
        if (raw == null) {
            throw new Exception("empty config");
        }
        String s = raw;
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
                continue;
            }
            if (c == '/' && i + 1 < len && s.charAt(i + 1) == '/') {
                while (i < len && s.charAt(i) != '\n') {
                    i++;
                }
                if (i < len) {
                    sb.append('\n');
                }
                continue;
            }
            if (c == '/' && i + 1 < len && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            sb.append(c);
        }
        String out = sb.toString().replaceAll(",(\\s*[\\]}])", "$1");
        return new JSONObject(out);
    }

    /**
     * 解析配置并落盘。
     * @param remoteOnly true=来自远程配置，只覆盖非设置(settings)/非n2n(hin2n)段，
     *                   且不覆盖 TVBox 开机自启(bootStart，属于设置页项)；
     *                   false=内置配置，全部段应用。
     */
    private static void apply(Context ctx, JSONObject j, boolean remoteOnly) {
        if (j.has("notice")) {
            Object n = j.opt("notice");
            if (n instanceof JSONObject) {
                JSONObject st = (JSONObject) n;
                Hawk.put(K_NOTICE_STYLE, st.toString());
                Hawk.put(K_NOTICE, st.optString("text", ""));
            } else {
                Hawk.put(K_NOTICE, j.optString("notice", ""));
                Hawk.put(K_NOTICE_STYLE, "");
            }
        }
        if (j.has("home") && j.optJSONObject("home") != null) {
            Hawk.put(K_HOME_STYLE, j.optJSONObject("home").toString());
        }
        if (j.has("tkp2p") && j.optJSONObject("tkp2p") != null) {
            Hawk.put(K_TKP2P, j.optJSONObject("tkp2p").toString());
        }
        if (j.has("checkInterval")) {
            Hawk.put(K_CHECK_INTERVAL, Math.max(1, j.optInt("checkInterval", 30)));
        }
        if (j.has("ua")) {
            Hawk.put(K_UA, j.optString("ua", "").trim());
        }
        if (j.has("entries")) {
            Hawk.put(K_ENTRIES, j.optJSONArray("entries") != null
                    ? j.optJSONArray("entries").toString() : "");
        }
        // 注意：空数组与「不写该字段」等价 = 保持内置默认 UI。
        JSONArray topArr = j.optJSONArray("topEntries");
        if (topArr != null && topArr.length() > 0) {
            Hawk.put(K_TOP_ENTRIES, topArr.toString());
        } else {
            Hawk.delete(K_TOP_ENTRIES);
        }
        JSONArray homeArr = j.optJSONArray("homeEntries");
        if (homeArr != null && homeArr.length() > 0) {
            Hawk.put(K_HOME_ENTRIES, homeArr.toString());
        } else {
            Hawk.delete(K_HOME_ENTRIES);
        }
        if (j.has("upgrade") && j.optJSONObject("upgrade") != null) {
            Hawk.put(K_UPGRADE, j.optJSONObject("upgrade").toString());
        }
        if (j.has("hin2nEntry")) {
            Hawk.put(K_HIN2N, j.optBoolean("hin2nEntry", true));
        }
        JSONArray themes = j.optJSONArray("themes");
        if (themes != null && themes.length() > 0) {
            ThemeManager.updateThemes(themes);
        }
        // 以下三项属于用户本地设置，远程配置不覆盖：
        //   - bootStart（TVBox 开机自启，设置页项）
        //   - settings（设置页所有项）
        //   - hin2n（n2n 组网模块配置）
        if (!remoteOnly) {
            if (j.has("bootStart") && !Hawk.get(K_BOOT_DEFAULT_SET, false)) {
                Hawk.put(K_BOOT, j.optBoolean("bootStart", false));
                Hawk.put(K_BOOT_DEFAULT_SET, true);
            }
            JSONObject st = j.optJSONObject("settings");
            if (st != null) {
                applySettingsDefaults(st);
            }
        }
        applyEndpoint(ctx, j, "api", HawkConfig.API_URL, "点播接口");
        applyEndpoint(ctx, j, "live", HawkConfig.LIVE_URL, "直播接口");
        applyEndpoint(ctx, j, "epg", HawkConfig.EPG_URL, "EPG 接口");
        applyEndpoint(ctx, j, "proxy", HawkConfig.PROXY_SERVER, "代理地址");
        if (j.has("theme")) {
            int ti = ThemeManager.indexFromConfig(j.optString("theme", ""));
            if (ti >= 0) {
                Hawk.put(HawkConfig.THEME_SELECT, ti);
            }
        }
    }

    /**
     * 设置页默认值（JSON "settings" 段 → Hawk）。键名与含义见 assets/tvbox-config.json 注释。
     * 全部为 putIfAbsent 语义：用户在设置页修改过（Hawk 已有该键）后，JSON 不再覆盖。
     */
    private static void applySettingsDefaults(JSONObject st) {
        // ---- 首页选项 ----
        putBool(st, "homeShowSource", HawkConfig.HOME_SHOW_SOURCE);   // 数据源名称显示
        putBool(st, "homeSearchPosition", HawkConfig.HOME_SEARCH_POSITION); // 搜索按钮位置
        putBool(st, "homeMenuPosition", HawkConfig.HOME_MENU_POSITION);     // 设置按钮位置
        putInt(st, "homeRec", HawkConfig.HOME_REC);                   // 首页推荐: 0=豆瓣 1=站点 2=历史
        putInt(st, "homeNum", HawkConfig.HOME_NUM);                   // 历史条数档位 0-4
        putBool(st, "homeRecStyle", HawkConfig.HOME_REC_STYLE);       // 首页默认多行（网格）
        putBool(st, "homeDefaultShow", HawkConfig.HOME_DEFAULT_SHOW); // 启动直接进直播
        // ---- 播放器选项 ----
        putBool(st, "showPreview", HawkConfig.SHOW_PREVIEW);          // 窗口预览
        putInt(st, "playScale", HawkConfig.PLAY_SCALE);               // 画面缩放 0-5
        putInt(st, "backgroundPlayType", HawkConfig.BACKGROUND_PLAY_TYPE); // 后台播放 0/1/2
        putInt(st, "playType", HawkConfig.PLAY_TYPE);                 // 播放器类型
        putStr(st, "ijkCodec", HawkConfig.IJK_CODEC);                 // IJK 解码方式
        putBool(st, "ijkCachePlay", HawkConfig.IJK_CACHE_PLAY);       // IJK 边播边缓存
        putInt(st, "exoRenderer", HawkConfig.EXO_RENDERER);           // Exo 扩展渲染器索引
        putInt(st, "exoRendererMode", HawkConfig.EXO_RENDERER_MODE);  // Exo 渲染器模式索引
        putInt(st, "vodPlayerPreferred", HawkConfig.VOD_PLAYER_PREFERRED); // 点播首选播放器索引
        putInt(st, "playRender", HawkConfig.PLAY_RENDER);             // 渲染方式 0=TextureView 1=SurfaceView
        putBool(st, "videoPurify", HawkConfig.VIDEO_PURIFY);          // 视频去广告（嗅探净化）
        // ---- 系统选项 ----
        putInt(st, "homeLocale", HawkConfig.HOME_LOCALE);             // 语言 0=中文 1=英文
        putInt(st, "themeSelect", HawkConfig.THEME_SELECT);           // 主题索引（缺省值）
        putInt(st, "searchView", HawkConfig.SEARCH_VIEW);             // 搜索展示 0=文字 1=缩略图
        putBool(st, "parseWebview", HawkConfig.PARSE_WEBVIEW);        // 嗅探内核
        putInt(st, "dohUrl", HawkConfig.DOH_URL);                     // 安全 DNS
        putBool(st, "fastSearchMode", HawkConfig.FAST_SEARCH_MODE);   // 聚合搜索（快搜）
        putBool(st, "debugOpen", HawkConfig.DEBUG_OPEN);              // 调试模式开关
    }

    private static void putBool(JSONObject st, String key, String hawkKey) {
        try {
            if (st.has(key) && !Hawk.contains(hawkKey)) {
                Hawk.put(hawkKey, st.optBoolean(key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putInt(JSONObject st, String key, String hawkKey) {
        try {
            if (st.has(key) && !Hawk.contains(hawkKey)) {
                Hawk.put(hawkKey, st.optInt(key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putStr(JSONObject st, String key, String hawkKey) {
        try {
            if (st.has(key) && !Hawk.contains(hawkKey)) {
                Hawk.put(hawkKey, st.optString(key, ""));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 仅当用户尚未配置时把 JSON 中的地址作为默认值写入；用户配置过后不再覆盖。 */
    private static void applyEndpoint(final Context app, JSONObject j, String key, String hawkKey, String label) {
        if (!j.has(key)) {
            return;
        }
        String v = j.optString(key, "").trim();
        if (v.isEmpty()) {
            return;
        }
        String cur = Hawk.get(hawkKey, "");
        if (cur != null && !cur.trim().isEmpty()) {
            return;
        }
        Hawk.put(hawkKey, v);
        toast(app, label + "已从内置配置读取，重启后生效");
    }

    private static void toast(final Context app, final String msg) {
        if (app == null) {
            return;
        }
        try {
            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
