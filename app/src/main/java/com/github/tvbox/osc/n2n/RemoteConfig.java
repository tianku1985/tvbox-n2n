package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
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
 *   "useRemoteConfig": false,                         // true 且远程可访问时：远程覆盖设置页全部项/
 *                                                     //   bootStart/setAsLauncher/api/live/epg/proxy
 *   "bootStart": true,                                // TVBox 开机自启：默认开启；本地/远程 json 写 false 可禁用
 *   "setAsLauncher": false,                           // 设为系统桌面（有 root 时禁用原桌面并设为默认桌面）
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
 *   "hin2n": { "apkUrl": "https://.../hin2n.apk" },   // 独立组网 App：未安装时的 APK 下载地址（可选）
 *   "secretKey": "",                                  // 接口固定密钥模式的秘钥覆盖（空=用 ConfigCrypto.SECRET_KEY）
 *   "donate": { "title": "支持我们", "desc": "...",    // 捐助界面：标题/介绍/二维码图片
 *               "image": "https://.../qr.png" },      //   image 支持 http(s) / assets/路径 / drawable 名
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
 * setting/drive/hin2n/apps/fastsearch/system=安卓系统设置/donate=捐助界面）；
 * feature=内置功能（target: siteSwitch/themeSwitch/styleSwitch/clearCache/reloadHome/
 * wifi/date）；app=打开其他 App（pkg，未安装且配 url 时后台下载安装）；vod=打开自定义
 * 点播列表界面（target=数据接口，格式同点播 api json 但仅供该界面，不影响 app 其它配置）。
 *
 * 接口加密（ConfigCrypto）：本地内置 assets 与所有远程接收接口（远程配置/点播/直播/
 * EPG/自定义点播/升级检查）支持密文下发——内容是 json 直接解析，不是 json 自动解密后
 * 加载（ke 前缀模式兼容 PHP：ke=substr(md5(...),13) 前缀 + AES-128-ECB hex；固定密钥
 * 模式用 "secretKey" 或 ConfigCrypto.SECRET_KEY）。
 * 接口本机参数（DeviceParams）：所有远程接口请求自动在 URL 拼接 mac/ip/model/brand/
 * device/sdk/release/app 参数，服务端可按机下发。
 *
 * 覆盖规则：notice/home/tkp2p/entries/themes/hin2nEntry/checkInterval/ua/secretKey/
 * donate 每次启动应用；bootStart 只作为默认值写入一次；api/live/epg/proxy 仅在用户
 * 未配置时作为默认值。setAsLauncher 出厂默认开启（含老设备一次性重放），用户可关。
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
    public static final String K_LAUNCHER = "rc_set_launcher";            // 设为系统桌面开关（用户值优先）
    public static final String K_LAUNCHER_DEFAULT_SET = "rc_set_launcher_default_set"; // 内置默认值是否已写入过
    /** 设为桌面默认值第二轮重放守卫：setAsLauncher 出厂默认改 true 后，对老设备也补写一次 */
    public static final String K_LAUNCHER_DEFAULT_SET_V2 = "rc_set_launcher_default_set_v2";
    /** 接口固定密钥模式的秘钥覆盖值（json "secretKey"；空=用 ConfigCrypto.SECRET_KEY） */
    public static final String K_SECRET = "rc_secret_key";
    /** 捐助界面配置（json "donate" 段原文） */
    public static final String K_DONATE = "rc_donate";
    /** 最近一次成功拉取的远程配置原文（断网/下次启动兜底；存解密后的明文） */
    public static final String K_REMOTE_RAW = "rc_remote_raw";

    /**
     * Launcher 别名类名后缀（清单中为 {@code android:name=".LauncherAlias"}）。
     * 同时保留源码包名展开方式作为兜底候选。
     */
    private static final String LAUNCHER_ALIAS_SUFFIX = ".LauncherAlias";
    private static final String LAUNCHER_ALIAS_FIXED = "com.github.tvbox.osc.LauncherAlias";

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

    /** 接口固定密钥模式的秘钥覆盖（json "secretKey"；空=用 ConfigCrypto.SECRET_KEY 常量） */
    public static String secretKey() {
        String k = Hawk.get(K_SECRET, "");
        return k == null ? "" : k.trim();
    }

    /** 捐助界面配置（json "donate" 段：{"title":"","desc":"","image":""}），未配置返回 null */
    public static JSONObject donate() {
        String raw = Hawk.get(K_DONATE, "");
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 独立 hin2n 组网 App 的 APK 下载地址（内置 JSON "hin2n" 段的 "apkUrl" 字段）。
     * 组网入口点击时：已安装直接拉起；未安装且此处非空时走后台下载安装管线。
     */
    public static String hin2nApkUrl() {
        try {
            JSONObject h = raw().optJSONObject("hin2n");
            return h == null ? "" : h.optString("apkUrl", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean bootStartEnabled() {
        // 默认开启：随开机自动启动 TVBox；JSON（内置或远程）显式配置 "bootStart": false 时禁用
        return Hawk.get(K_BOOT, true);
    }

    /** 是否使用远程配置覆盖本地设置（内置 JSON "useRemoteConfig" 字段，远程可访问时才生效） */
    public static boolean useRemoteConfig() {
        try {
            return sConfig != null && sConfig.optBoolean("useRemoteConfig", false);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 设为系统桌面 ====================

    /** 设为系统桌面开关（JSON "setAsLauncher" 默认值 + 设置页用户开关；出厂默认开启） */
    public static boolean launcherEnabled() {
        return Hawk.get(K_LAUNCHER, true);
    }

    /** 设置页切换「设为系统桌面」：写入开关并同步 Launcher 别名状态 */
    public static void setLauncherEnabled(Context ctx, boolean on) {
        Hawk.put(K_LAUNCHER, on);
        applyLauncherComponent(ctx);
    }

    /**
     * 按当前开关启用/停用 Launcher 别名。
     * 启用后 TVBox 出现在系统桌面选择器；停用后 Android 自动清除本应用默认桌面资格，
     * 系统回退到原桌面（或弹出选择器让用户确认）。
     *
     * <p>组件名兼容两种展开方式（清单里写的是 {@code android:name=".LauncherAlias"}）：
     * 清单合并既可能按源码包名展开为 {@code com.github.tvbox.osc.LauncherAlias}，
     * 也可能按 applicationId 展开为 {@code <包名>.LauncherAlias}。
     * 这里两个候选都试，且先用 getActivityInfo 确认组件确实存在再设置，
     * 避免 mutate 不存在的组件导致「设为桌面」静默失效。</p>
     */
    public static void applyLauncherComponent(Context ctx) {
        if (ctx == null) {
            return;
        }
        int state = launcherEnabled()
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        String[] candidates = new String[]{
                ctx.getPackageName() + LAUNCHER_ALIAS_SUFFIX,
                LAUNCHER_ALIAS_FIXED
        };
        for (String alias : candidates) {
            try {
                PackageManager pm = ctx.getPackageManager();
                ComponentName cn = new ComponentName(ctx.getPackageName(), alias);
                // 组件必须已在清单中声明，否则设置会抛异常（getActivityInfo 可命中停用组件）
                pm.getActivityInfo(cn, 0);
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP);
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    /** 当前是否已是系统默认桌面 */
    public static boolean isDefaultLauncher(Context ctx) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && ctx.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 每进程最多自动弹一次桌面选择器（开关已开但还不是默认桌面时） */
    private static boolean sLauncherPrompted = false;

    /** 主界面 onResume 调用：开关已开但未设为默认桌面时，root 自动接管，否则弹一次系统桌面选择器 */
    public static void ensureDefaultLauncher(Activity act) {
        if (act == null || sLauncherPrompted) {
            return;
        }
        if (!launcherEnabled() || isDefaultLauncher(act)) {
            return;
        }
        sLauncherPrompted = true;
        if (LauncherHelper.hasRoot()) {
            // 有 root：直接调用系统服务禁用原桌面并设为默认桌面，不再打扰用户
            LauncherHelper.setAsDefaultLauncher(act);
        } else {
            promptChooseLauncher(act);
        }
    }

    /**
     * 唤起系统「选择/设置默认桌面」入口。
     *
     * <p>两个关键点：</p>
     * <ul>
     *   <li>不能用 {@code Intent.createChooser}：它只会让用户挑一个应用「打开一次」，
     *       不写入默认桌面，按 HOME 仍回原桌面——这也是「设为桌面无效」的原因之一；</li>
     *   <li>若设备已存在默认桌面，直接启动 CATEGORY_HOME 只会把原桌面拉起来（同样看着像没反应），
     *       所以此时应跳系统「主屏幕/默认桌面」设置页让用户显式切换；
     *       当前没有默认桌面时，直接启动 HOME intent 才会弹出带「始终/仅一次」的系统弹窗。</li>
     * </ul>
     */
    public static void promptChooseLauncher(Activity act) {
        if (act == null || act.isFinishing() || (Build.VERSION.SDK_INT >= 17 && act.isDestroyed())) {
            return;
        }
        boolean hasDefault = hasOtherDefaultLauncher(act);
        if (hasDefault && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && startSafe(act, new Intent(Settings.ACTION_HOME_SETTINGS))) {
            return;
        }
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startSafe(act, home)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            startSafe(act, new Intent(Settings.ACTION_HOME_SETTINGS));
        }
    }

    private static boolean startSafe(Activity act, Intent intent) {
        try {
            act.startActivity(intent);
            return true;
        } catch (Throwable th) {
            Log.i("RemoteConfig", "start " + intent.getAction() + " failed: " + th.getMessage());
            return false;
        }
    }

    /** 设备上是否已存在「非本应用」的默认桌面 */
    private static boolean hasOtherDefaultLauncher(Context ctx) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null
                    && !ctx.getPackageName().equals(ri.activityInfo.packageName);
        } catch (Throwable ignored) {
            return false;
        }
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
                // 内置配置允许明文或 ke 前缀模式密文（ConfigCrypto 自动识别）；
                // 注意：assets 禁止用固定密钥模式加密——此时 secretKey 尚未写入 Hawk
                String raw = ConfigCrypto.maybeDecrypt(new String(data, "UTF-8"));
                JSONObject cfg = parseLoose(raw);
                sConfig = cfg;
                apply(ctx.getApplicationContext(), cfg, false);
                // 每次启动同步「设为系统桌面」开关到 Launcher 别名状态
                applyLauncherComponent(ctx.getApplicationContext());

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
            // 请求附加本机参数（mac/ip/机型/系统版本等），服务端可按机下发
            String raw = httpGet(DeviceParams.append(url), RemoteConfig.ua());
            if (raw == null || raw.isEmpty()) {
                if (cb != null) cb.onDone(false);
                return false;
            }
            // 是 json 直接解析，不是 json 先解密后加载（ke 前缀/固定密钥两种模式自动识别）
            raw = ConfigCrypto.maybeDecrypt(raw);
            JSONObject remote = parseLoose(raw);
            apply(app, remote, true);
            // 缓存存解密后的明文：断网兜底无需再解密，与旧版本明文缓存格式兼容
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
    static JSONObject parseLoose(String raw) throws Exception {
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
     * @param remoteOnly true=来自远程配置：默认只覆盖非设置(settings)/非n2n(hin2n)段，
     *                   且不覆盖 TVBox 开机自启(bootStart)/设为桌面(setAsLauncher)；
     *                   但内置 JSON "useRemoteConfig"=true 且远程可访问时，远程强制覆盖
     *                   settings/bootStart/setAsLauncher 与接口地址等用户配置。
     *                   false=内置配置，全部段应用（设置项按 putIfAbsent 语义写入默认值）。
     */
    private static void apply(Context ctx, JSONObject j, boolean remoteOnly) {
        // 远程覆盖模式：内置 JSON 开启 useRemoteConfig 且本次远程拉取成功（走到这里即可访问）
        boolean forceRemote = remoteOnly && useRemoteConfig();
        // 接口秘钥（固定密钥模式）：本地/远程均可下发，每次启动应用；空=用 ConfigCrypto.SECRET_KEY
        if (j.has("secretKey")) {
            Hawk.put(K_SECRET, j.optString("secretKey", "").trim());
        }
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
        // 捐助界面配置：本地/远程均可下发；空对象或 null 时清除（界面用代码内置介绍）
        if (j.has("donate")) {
            JSONObject dn = j.optJSONObject("donate");
            if (dn != null && dn.length() > 0) {
                Hawk.put(K_DONATE, dn.toString());
            } else {
                Hawk.delete(K_DONATE);
            }
        }
        if (j.has("hin2nEntry")) {
            Hawk.put(K_HIN2N, j.optBoolean("hin2nEntry", true));
        }
        JSONArray themes = j.optJSONArray("themes");
        if (themes != null && themes.length() > 0) {
            ThemeManager.updateThemes(themes);
        }
        // 开机启动：默认开启（bootStartEnabled 缺省 true）。远程 json 只要显式写了 bootStart
        // 就以远程为准并每次拉取同步——满足「本地/远程 json 未禁用则默认开机启动」；
        // 远程未声明该字段时保留用户在设置页的选择。
        if (remoteOnly && j.has("bootStart")) {
            Hawk.put(K_BOOT, j.optBoolean("bootStart", true));
            Hawk.put(K_BOOT_DEFAULT_SET, true);
        }
        // 以下四项属于用户本地设置，默认远程配置不覆盖：
        //   - bootStart（TVBox 开机自启，设置页项）
        //   - setAsLauncher（设为系统桌面，设置页项）
        //   - settings（设置页所有项）
        //   - hin2n（n2n 组网模块配置）
        // 例外：内置 JSON "useRemoteConfig"=true 且远程可访问（forceRemote）时，远程强制覆盖。
        if (!remoteOnly) {
            if (j.has("bootStart") && !Hawk.get(K_BOOT_DEFAULT_SET, false)) {
                Hawk.put(K_BOOT, j.optBoolean("bootStart", false));
                Hawk.put(K_BOOT_DEFAULT_SET, true);
            }
            if (j.has("setAsLauncher") && !Hawk.get(K_LAUNCHER_DEFAULT_SET, false)) {
                Hawk.put(K_LAUNCHER, j.optBoolean("setAsLauncher", true));
                Hawk.put(K_LAUNCHER_DEFAULT_SET, true);
                Hawk.put(K_LAUNCHER_DEFAULT_SET_V2, true);
            } else if (j.has("setAsLauncher") && !Hawk.get(K_LAUNCHER_DEFAULT_SET_V2, false)) {
                // 出厂默认改为「开启」后的第二轮重放：老设备（旧守卫已置位）也补写一次默认值，
                // 使「默认设为系统桌面」全量生效；用户在设置页关闭后此守卫已置位，不再回写
                Hawk.put(K_LAUNCHER, j.optBoolean("setAsLauncher", true));
                Hawk.put(K_LAUNCHER_DEFAULT_SET_V2, true);
            }
            JSONObject st = j.optJSONObject("settings");
            if (st != null) {
                applySettingsDefaults(st, false);
            }
        } else if (forceRemote) {
            // 远程覆盖模式：强制覆盖用户已修改的设置
            if (j.has("bootStart")) {
                Hawk.put(K_BOOT, j.optBoolean("bootStart", false));
            }
            if (j.has("setAsLauncher")) {
                Hawk.put(K_LAUNCHER, j.optBoolean("setAsLauncher", false));
                applyLauncherComponent(ctx);
            }
            JSONObject st = j.optJSONObject("settings");
            if (st != null) {
                applySettingsDefaults(st, true);
            }
        }
        applyEndpoint(ctx, j, "api", HawkConfig.API_URL, "点播接口", forceRemote);
        applyEndpoint(ctx, j, "live", HawkConfig.LIVE_URL, "直播接口", forceRemote);
        applyEndpoint(ctx, j, "epg", HawkConfig.EPG_URL, "EPG 接口", forceRemote);
        applyEndpoint(ctx, j, "proxy", HawkConfig.PROXY_SERVER, "代理地址", forceRemote);
        if (j.has("theme")) {
            int ti = ThemeManager.indexFromConfig(j.optString("theme", ""));
            if (ti >= 0) {
                Hawk.put(HawkConfig.THEME_SELECT, ti);
            }
        }
    }

    /**
     * 设置页默认值（JSON "settings" 段 → Hawk）。键名与含义见 assets/tvbox-config.json 注释。
     * force=false 为 putIfAbsent 语义：用户在设置页修改过（Hawk 已有该键）后，JSON 不再覆盖；
     * force=true（useRemoteConfig 开启且远程可访问）时强制覆盖用户值。
     */
    private static void applySettingsDefaults(JSONObject st, boolean force) {
        // ---- 首页选项 ----
        putBool(st, "homeShowSource", HawkConfig.HOME_SHOW_SOURCE, force);   // 数据源名称显示
        putBool(st, "homeSearchPosition", HawkConfig.HOME_SEARCH_POSITION, force); // 搜索按钮位置
        putBool(st, "homeMenuPosition", HawkConfig.HOME_MENU_POSITION, force);     // 设置按钮位置
        putInt(st, "homeRec", HawkConfig.HOME_REC, force);                   // 首页推荐: 0=豆瓣 1=站点 2=历史
        putInt(st, "homeNum", HawkConfig.HOME_NUM, force);                   // 历史条数档位 0-4
        putBool(st, "homeRecStyle", HawkConfig.HOME_REC_STYLE, force);       // 首页默认多行（网格）
        putBool(st, "homeDefaultShow", HawkConfig.HOME_DEFAULT_SHOW, force); // 启动直接进直播
        // ---- 播放器选项 ----
        putBool(st, "showPreview", HawkConfig.SHOW_PREVIEW, force);          // 窗口预览
        putInt(st, "playScale", HawkConfig.PLAY_SCALE, force);               // 画面缩放 0-5
        putInt(st, "backgroundPlayType", HawkConfig.BACKGROUND_PLAY_TYPE, force); // 后台播放 0/1/2
        putInt(st, "playType", HawkConfig.PLAY_TYPE, force);                 // 播放器类型
        putStr(st, "ijkCodec", HawkConfig.IJK_CODEC, force);                 // IJK 解码方式
        putBool(st, "ijkCachePlay", HawkConfig.IJK_CACHE_PLAY, force);       // IJK 边播边缓存
        putInt(st, "exoRenderer", HawkConfig.EXO_RENDERER, force);           // Exo 扩展渲染器索引
        putInt(st, "exoRendererMode", HawkConfig.EXO_RENDERER_MODE, force);  // Exo 渲染器模式索引
        putInt(st, "vodPlayerPreferred", HawkConfig.VOD_PLAYER_PREFERRED, force); // 点播首选播放器索引
        putInt(st, "playRender", HawkConfig.PLAY_RENDER, force);             // 渲染方式 0=TextureView 1=SurfaceView
        putBool(st, "videoPurify", HawkConfig.VIDEO_PURIFY, force);          // 视频去广告（嗅探净化）
        // ---- 系统选项 ----
        putInt(st, "homeLocale", HawkConfig.HOME_LOCALE, force);             // 语言 0=中文 1=英文
        putInt(st, "themeSelect", HawkConfig.THEME_SELECT, force);           // 主题索引（缺省值）
        putInt(st, "searchView", HawkConfig.SEARCH_VIEW, force);             // 搜索展示 0=文字 1=缩略图
        putBool(st, "parseWebview", HawkConfig.PARSE_WEBVIEW, force);        // 嗅探内核
        putInt(st, "dohUrl", HawkConfig.DOH_URL, force);                     // 安全 DNS
        putBool(st, "fastSearchMode", HawkConfig.FAST_SEARCH_MODE, force);   // 聚合搜索（快搜）
        putBool(st, "debugOpen", HawkConfig.DEBUG_OPEN, force);              // 调试模式开关
    }

    private static void putBool(JSONObject st, String key, String hawkKey, boolean force) {
        try {
            if (st.has(key) && (force || !Hawk.contains(hawkKey))) {
                Hawk.put(hawkKey, st.optBoolean(key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putInt(JSONObject st, String key, String hawkKey, boolean force) {
        try {
            if (st.has(key) && (force || !Hawk.contains(hawkKey))) {
                Hawk.put(hawkKey, st.optInt(key));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putStr(JSONObject st, String key, String hawkKey, boolean force) {
        try {
            if (st.has(key) && (force || !Hawk.contains(hawkKey))) {
                Hawk.put(hawkKey, st.optString(key, ""));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 仅当用户尚未配置时把 JSON 中的地址作为默认值写入；用户配置过后不再覆盖。
     * force=true（useRemoteConfig 开启且远程可访问）时强制覆盖用户值。
     */
    private static void applyEndpoint(final Context app, JSONObject j, String key, String hawkKey, String label, boolean force) {
        if (!j.has(key)) {
            return;
        }
        String v = j.optString(key, "").trim();
        if (v.isEmpty()) {
            return;
        }
        if (!force) {
            String cur = Hawk.get(hawkKey, "");
            if (cur != null && !cur.trim().isEmpty()) {
                return;
            }
        }
        Hawk.put(hawkKey, v);
        toast(app, label + (force ? "已按远程配置更新，重启后生效" : "已从内置配置读取，重启后生效"));
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
