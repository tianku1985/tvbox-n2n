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
import java.util.ArrayList;
import java.util.List;

/**
 * 内置配置接口：TVBox 的全部可配置项统一来自 APK 内置 assets/tvbox-config.json。
 *
 * 已移除「远程地址配置」：不再有远程 URL、网络拉取与远程 APK 自动更新，
 * 需要远程/定制配置时直接修改源码内 assets/tvbox-config.json 重新编译。
 * 开机启动（TVBox 开机自启 + hin2n 开机自动连 VPN）仍然保留。
 *
 * JSON 结构（所有字段可选，缺省保持本地现状；支持 // 与 /* 注释、尾逗号）：
 * {
 *   "notice": {                                       // 公告：字符串（旧格式）或对象
 *     "text": "公告文本",
 *     "position": "bottom",                           // top | bottom
 *     "bgColor": "#CC1B1B1B", "fontColor": "#FFFFD54F",
 *     "fontSize": 18, "alpha": 100, "duration": 15,
 *     "show": { "home": true, "live": true, "vod": true },
 *     "timeStart": "08:00", "timeEnd": "22:00", "expire": "2027-12-31",
 *     "speed": 60
 *   },
 *   "home": { "bgImage": "...", "entryBgColor": "...", "iconColor": "...",
 *             "textColor": "...", "alpha": 90 },      // 首页外观
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
 *     {"name":"影院","type":"vod","target":"assets/vod.json","icon":"hm_vod"} ],
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
     * App 启动时调用：同步读取内置 assets/tvbox-config.json 并应用。
     * 文件极小（几 KB），同步读取避免后续播种逻辑竞态；读失败静默忽略。
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
                apply(ctx.getApplicationContext(), cfg);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 远程拉取入口已移除：保留方法壳，立即回调成功，避免调用方改动。
     * 需要不同配置时，修改内置 assets/tvbox-config.json 后重新编译。
     */
    public static void fetchAuto(Context ctx, Callback cb) {
        if (cb != null) {
            cb.onDone(true);
        }
    }

    /** 远程拉取入口已移除（原「立即同步」按钮已删除），保留空壳兼容 */
    public static void fetch(Context ctx, Callback cb) {
        if (cb != null) {
            cb.onDone(true);
        }
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

    /** 解析内置配置并落盘。内置 JSON 只提供默认值：用户手动改过的项不被覆盖。 */
    private static void apply(Context ctx, JSONObject j) {
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
        // 否则 demo 里「数组中只放注释示例」剥注释后会被误判为「整体替换成空」，导致图标全消失。
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
        // TVBox 开机自启：内置默认值只写一次，之后以用户开关为准
        if (j.has("bootStart") && !Hawk.get(K_BOOT_DEFAULT_SET, false)) {
            Hawk.put(K_BOOT, j.optBoolean("bootStart", false));
            Hawk.put(K_BOOT_DEFAULT_SET, true);
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
