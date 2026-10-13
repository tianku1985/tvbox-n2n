package com.github.tvbox.osc.n2n;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
 *   "notice":    "首页底部滚动公告文本",
 *   "bootStart": true,                                  // 「开机启动」开关的默认值（用户手动改过后不再覆盖）
 *   "hin2nEntry": true,                                 // 首页是否显示 hin2n 固定入口
 *   "hin2nUrl":  "https://.../hin2n.apk",               // hin2n 未安装时提示的下载地址
 *   "entries": [
 *     {"name":"文件管理", "pkg":"com.android.documentsui",
 *      "icon":"https://.../icon.png", "url":"https://.../app.apk"}
 *   ]                                                   // url 为未安装时提示的下载地址（可选）
 * }
 *
 * 覆盖规则：notice/entries/hin2nEntry/hin2nUrl 每次拉取成功即更新；bootStart 只在
 * 远端尚未写入过默认值时作为「默认值」写入，之后以用户开关为准。
 */
public class RemoteConfig {

    /** Hawk 键：接口地址（远程配置页可改）、缓存与开关 */
    public static final String K_URL = "rc_url";
    public static final String K_NOTICE = "rc_notice";
    public static final String K_ENTRIES = "rc_entries";       // JSON 数组字符串
    public static final String K_HIN2N = "rc_hin2n_entry";
    public static final String K_HIN2N_URL = "rc_hin2n_url";   // hin2n 未安装时的下载地址
    public static final String K_BOOT = "n2n_boot_start";      // 开机启动开关（用户值优先）
    public static final String K_BOOT_REMOTE_SET = "n2n_boot_remote_set"; // 远端默认值是否已写入过

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
        final String url = url().trim();
        if (url.isEmpty()) {
            if (cb != null) {
                cb.onDone(false);
            }
            return;
        }
        fetching = true;
        lastFetch = System.currentTimeMillis();
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setUseCaches(false);
                    conn.setRequestProperty("User-Agent", "TVBox-n2n");
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        in.close();
                        apply(app, new JSONObject(new String(bos.toByteArray(), "UTF-8")));
                        ok = true;
                    }
                    conn.disconnect();
                } catch (Throwable ignored) {
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

    /** 解析并落盘。远端只提供默认值：用户手动改过的项不被覆盖。 */
    private static void apply(Context ctx, JSONObject j) {
        // 公告 / 入口 / hin2n 入口：远端为准，直接覆盖
        if (j.has("notice")) {
            Hawk.put(K_NOTICE, j.optString("notice", ""));
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
    }
}
