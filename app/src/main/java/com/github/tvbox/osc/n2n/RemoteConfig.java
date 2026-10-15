package com.github.tvbox.osc.n2n;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 远程配置接口：App 启动/首页可见时从可配置的 HTTP(S) 接口拉取一份 JSON，
 * 作为公告、首页自定义入口与开机启动的默认来源。
 *
 * JSON 结构（所有字段可选，缺省保持本地现状）：
 * {
 *   "notice": {                                       // 公告：字符串（旧格式）或对象（新格式）
 *     "text": "公告文本",
 *     "position": "bottom",                           // top | bottom
 *     "bgColor": "#CC1B1B1B",                         // 背景颜色（支持 #RRGGBB / #AARRGGBB）
 *     "fontColor": "#FFFFD54F",                       // 字体颜色
 *     "fontSize": 18,                                 // 字体大小（sp）
 *     "alpha": 100,                                   // 整体透明度（0-100）
 *     "duration": 15,                                 // 显示多少秒后自动消失（0 或缺省=一直显示）
 *     "show": { "home": true, "live": true, "vod": true }, // 分别控制首页/直播/点播界面是否显示（默认均显示）
 *     "timeStart": "08:00", "timeEnd": "22:00",       // 显示时间段（超出不显示，可跨零点）
 *     "expire": "2027-12-31",                         // 过期时间（yyyy-MM-dd，过期不显示）
 *     "speed": 60                                     // 滚动速度（px/秒，0 用系统 marquee）
 *   },
 *   "home": {                                         // 首页外观
 *     "bgImage": "https://.../bg.jpg",                // 背景图片 URL（空则保持原样）
 *     "entryBgColor": "#33FFFFFF",                    // 入口图标背景颜色
 *     "iconColor": "#FFFFFFFF",                       // 入口图标着色（空则保持原图）
 *     "textColor": "#FFFFFFFF",                       // 顶栏文字颜色
 *     "alpha": 90                                     // 入口整体透明度（0-100）
 *   },
 *   "tkp2p": {                                        // tkp2p:// 播放配置
 *     "engine": "auto",                               // auto | hls | media
 *     "announce": ["wss://tracker.example.com"],      // p2p-media-loader tracker 列表
 *     "token": "xxx",                                 // SwarmCloud token（可选）
 *     "zone": "cn"                                    // SwarmCloud trackerZone（eu/hk/us/cn）
 *   },
 *   "checkInterval": 30,                              // 定时检查 JSON 接口的间隔（分钟）
 *   "ua": "Mozilla/5.0 ...",                          // 全局 UA：远程配置拉取与网页型直播 WebView 使用
 *   "bootStart": true,                                // 「开机启动」开关的默认值（用户手动改过后不再覆盖）
 *   "api":   "https://.../api.json",                  // 点播接口（仅当用户未在 tvbox 内配置时作为默认值）
 *   "live":  "https://.../live.txt",                  // 直播接口（同上：用户配置过即不再被 JSON 覆盖）
 *   "epg":   "https://.../epg.xml",                   // EPG 接口（同上）
 *   "proxy": "http://127.0.0.1:9978/proxy?do=live",   // 代理地址（同上）
 *   "theme": "violet",                                // 主题：索引 / 中文名 / 英文别名（emerald/ocean/violet/amber…）
 *   "version": "3",                                   // JSON 版本号：与本地不同即触发一次「版本升级」提示
 *   "hin2nEntry": true,                               // 首页是否显示 hin2n 固定入口
 *   "hin2nUrl":  "https://.../hin2n.apk",             // hin2n 未安装时后台下载的地址
 *   "entries": [
 *     {"name":"文件管理", "pkg":"com.android.documentsui",
 *      "icon":"https://.../icon.png", "url":"https://.../app.apk"}
 *   ]                                                 // url 为未安装时后台下载的地址（可选）
 * }
 *
 * 覆盖规则：notice/home/tkp2p/entries/hin2nEntry/hin2nUrl/checkInterval/ua 每次拉取成功即更新；
 * bootStart 只在远端尚未写入过默认值时作为「默认值」写入，之后以用户开关为准；
 * api/live/epg/proxy 仅在用户尚未在 tvbox 内配置（当前值为空）时作为默认值写入，
 * 用户一旦配置过某接口，JSON 即不再覆盖该接口；
 * 远程接口地址支持 http:// 与 https://（未写协议时自动补 http://）；
 * 远程未设置或无法访问时，自动读取 assets/tvbox-config.json 作为本地兜底配置。
 */
public class RemoteConfig {

    /** Hawk 键：接口地址（远程配置页可改）、缓存与开关 */
    public static final String K_URL = "rc_url";
    public static final String K_NOTICE = "rc_notice";
    public static final String K_NOTICE_STYLE = "rc_notice_style";  // 公告样式 JSON（position/颜色/时段/速度等）
    public static final String K_HOME_STYLE = "rc_home_style";      // 首页外观 JSON（背景图/入口配色等）
    public static final String K_TKP2P = "rc_tkp2p";                // tkp2p:// 播放配置 JSON
    public static final String K_CHECK_INTERVAL = "rc_check_interval"; // 定时检查间隔（分钟）
    public static final String K_UA = "rc_ua";                     // 全局 UA（远程拉取 / 网页直播使用）
    public static final String K_ENTRIES = "rc_entries";       // JSON 数组字符串
    public static final String K_HIN2N = "rc_hin2n_entry";
    public static final String K_HIN2N_URL = "rc_hin2n_url";   // hin2n 未安装时的下载地址
    public static final String K_BOOT = "n2n_boot_start";      // 开机启动开关（用户值优先）
    public static final String K_BOOT_REMOTE_SET = "n2n_boot_remote_set"; // 远端默认值是否已写入过
    public static final String K_VERSION = "rc_version";       // 远程 JSON 版本号（用于版本检查升级）

    /** 远程「未设置 / 无法访问」时读取的本地兜底配置（app/src/main/assets/ 下） */
    private static final String LOCAL_ASSET = "tvbox-config.json";

    /** 两次自动拉取的最小间隔（毫秒），避免每次 onResume 都打接口 */
    private static final long FETCH_INTERVAL = 5 * 60 * 1000L;
    private static long lastFetch = 0L;
    private static boolean fetching = false;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 单个自定义入口 */
    public static class Entry {
        public String name;
        public String pkg;
        public String icon;
        public String url;   // 未安装时的下载地址（可选）
    }

    public interface Callback {
        /** 拉取并应用完成后在主线程回调（无论成功与否；success 区分） */
        void onDone(boolean success);
    }

    public static String url() {
        return Hawk.get(K_URL, "");
    }

    public static String notice() {
        return Hawk.get(K_NOTICE, "");
    }

    /** 公告样式 JSON（无则返回空对象）；字段见类注释 notice 部分 */
    public static JSONObject noticeStyle() {
        try {
            return new JSONObject(Hawk.get(K_NOTICE_STYLE, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 首页外观 JSON（无则返回空对象）；字段见类注释 home 部分 */
    public static JSONObject homeStyle() {
        try {
            return new JSONObject(Hawk.get(K_HOME_STYLE, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** tkp2p:// 播放配置 JSON（无则返回空对象）；字段见类注释 tkp2p 部分 */
    public static JSONObject tkP2pStyle() {
        try {
            return new JSONObject(Hawk.get(K_TKP2P, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** 定时检查 JSON 接口的间隔（分钟，最小 1，默认 30） */
    public static int checkIntervalMinutes() {
        return Math.max(1, Hawk.get(K_CHECK_INTERVAL, 30));
    }

    /** 全局 UA（远程 JSON "ua" 字段）；为空时调用方使用内置浏览器 UA */
    public static String ua() {
        String u = Hawk.get(K_UA, "");
        return u == null ? "" : u.trim();
    }

    public static boolean hin2nEntryEnabled() {
        return Hawk.get(K_HIN2N, true);
    }

    /** hin2n 未安装时提示的下载地址（远端下发，可为空） */
    public static String hin2nUrl() {
        return Hawk.get(K_HIN2N_URL, "");
    }

    public static boolean bootStartEnabled() {
        return Hawk.get(K_BOOT, false);
    }

    /** 读取已缓存的自定义入口列表 */
    public static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        String raw = Hawk.get(K_ENTRIES, "");
        if (raw.isEmpty()) {
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
                e.pkg = o.optString("pkg", "");
                e.icon = o.optString("icon", "");
                e.url = o.optString("url", "");
                if (!e.pkg.isEmpty()) {
                    list.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** App 启动时调用：远程已配置则异步拉取（失败自动回退本地），未配置则直接读本地兜底 JSON */
    public static void bootstrap(Context ctx) {
        if (ctx == null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        if (url().trim().isEmpty()) {
            loadLocal(app);
        } else {
            fetch(app, null);
        }
    }

    /** 远程 JSON 当前版本号（无则为空） */
    public static String version() {
        return Hawk.get(K_VERSION, "");
    }

    /** 限频自动拉取：距上次成功/尝试不足 FETCH_INTERVAL 时直接返回 */
    public static void fetchAuto(Context ctx, Callback cb) {
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < FETCH_INTERVAL) {
            if (cb != null) {
                cb.onDone(true);
            }
            return;
        }
        fetch(ctx, cb);
    }

    /** 立即拉取（「立即同步」按钮用，force 不受限频影响） */
    public static void fetch(final Context ctx, final Callback cb) {
        final String raw = url().trim();
        final Context app = ctx.getApplicationContext();
        if (raw.isEmpty()) {
            // 远程未设置：读取本地兜底 JSON
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = loadLocal(app);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (cb != null) {
                                cb.onDone(ok);
                            }
                        }
                    });
                }
            }, "remote-config-local").start();
            return;
        }
        // 未显式写协议时补 http://；http:// 与 https:// 均原样支持
        final String url = (raw.startsWith("http://") || raw.startsWith("https://")) ? raw : "http://" + raw;
        fetching = true;
        lastFetch = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setUseCaches(false);
                    conn.setInstanceFollowRedirects(true);
                    // UA：远程 JSON 配置了 ua 则用它，否则统一带浏览器 UA（部分托管拒绝非浏览器请求）
                    String ua = ua();
                    conn.setRequestProperty("User-Agent", ua.isEmpty()
                            ? "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.77 Safari/537.36"
                            : ua);
                    conn.setRequestProperty("Accept", "*/*");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        in.close();
                        apply(app, parseLoose(new String(bos.toByteArray(), "UTF-8")));
                        ok = true;
                    }
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
                if (!ok) {
                    // 远程无法访问：回退本地兜底 JSON
                    loadLocal(app);
                }
                fetching = false;
                final boolean fOk = ok;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onDone(fOk);
                        }
                    }
                });
            }
        }, "remote-config").start();
    }

    /** 读取 assets 下的本地兜底 JSON 并应用 */
    private static boolean loadLocal(Context app) {
        try {
            InputStream in = app.getAssets().open(LOCAL_ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            apply(app, parseLoose(new String(bos.toByteArray(), "UTF-8")));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 宽容解析：除标准 JSON 外，还兼容 jsonc 写法 ——
     * 去掉 UTF-8 BOM、剥除 // 行注释与 /* 块注释（字符串内不剥，URL 不受影响）、容忍尾逗号。
     * 这样直接把带注释的参考配置（docs/tvbox-config.example.jsonc）发布到接口也能正常同步。
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
        // 容忍尾逗号：],} 前的逗号去掉
        String out = sb.toString().replaceAll(",(\\s*[\\]}])", "$1");
        return new JSONObject(out);
    }

    /** 解析并落盘。远端只提供默认值：用户手动改过的项不被覆盖。 */
    private static void apply(Context ctx, JSONObject j) {
        // 公告：支持字符串（旧格式）与对象（新样式格式）
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
        // 首页外观 / tkp2p 播放配置
        if (j.has("home") && j.optJSONObject("home") != null) {
            Hawk.put(K_HOME_STYLE, j.optJSONObject("home").toString());
        }
        if (j.has("tkp2p") && j.optJSONObject("tkp2p") != null) {
            Hawk.put(K_TKP2P, j.optJSONObject("tkp2p").toString());
        }
        // 定时检查间隔（分钟）
        if (j.has("checkInterval")) {
            Hawk.put(K_CHECK_INTERVAL, Math.max(1, j.optInt("checkInterval", 30)));
        }
        // 全局 UA（远程配置拉取与网页型直播 WebView 使用）
        if (j.has("ua")) {
            Hawk.put(K_UA, j.optString("ua", "").trim());
        }
        if (j.has("entries")) {
            Hawk.put(K_ENTRIES, j.optJSONArray("entries") != null
                    ? j.optJSONArray("entries").toString() : "");
        }
        if (j.has("hin2nEntry")) {
            Hawk.put(K_HIN2N, j.optBoolean("hin2nEntry", true));
        }
        if (j.has("hin2nUrl")) {
            Hawk.put(K_HIN2N_URL, j.optString("hin2nUrl", ""));
        }
        // 开机启动：远端默认值只写一次，之后以用户开关为准
        if (j.has("bootStart") && !Hawk.get(K_BOOT_REMOTE_SET, false)) {
            Hawk.put(K_BOOT, j.optBoolean("bootStart", false));
            Hawk.put(K_BOOT_REMOTE_SET, true);
        }
        // 直播 / 点播接口 / EPG / 代理：仅当用户未在 tvbox 内配置（当前值为空）时才以 JSON 为默认值写入；
        // 用户一旦配置过某接口，JSON 即不再覆盖该接口
        applyEndpoint(ctx, j, "api", HawkConfig.API_URL, "点播接口");
        applyEndpoint(ctx, j, "live", HawkConfig.LIVE_URL, "直播接口");
        applyEndpoint(ctx, j, "epg", HawkConfig.EPG_URL, "EPG 接口");
        applyEndpoint(ctx, j, "proxy", HawkConfig.PROXY_SERVER, "代理地址");
        // 主题：支持索引 / 中文名 / 英文别名（如 "violet"、"紫罗兰"、9）
        if (j.has("theme")) {
            int ti = ThemeManager.indexFromConfig(j.optString("theme", ""));
            if (ti >= 0) {
                Hawk.put(HawkConfig.THEME_SELECT, ti);
            }
        }
        // JSON 版本检查升级：远端 version 与本地不一致即视为一次升级
        String ver = j.optString("version", "").trim();
        if (!ver.isEmpty()) {
            String old = Hawk.get(K_VERSION, "");
            if (!ver.equals(old)) {
                Hawk.put(K_VERSION, ver);
                if (!old.isEmpty()) {
                    toastLater(ctx, "远程配置已升级至 v" + ver);
                }
            }
        }
    }

    /**
     * 仅当用户尚未配置（Hawk 当前值为空）时把 JSON 中的地址作为默认值写入；
     * 用户已在 tvbox 内配置过的接口一律不再覆盖。首次写入时提示重启生效。
     */
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
            // 用户已配置：JSON 不再接管该接口
            return;
        }
        Hawk.put(hawkKey, v);
        toastLater(app, label + "已从远程配置读取，重启后生效");
    }

    private static void toastLater(final Context app, final String msg) {
        if (app == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
