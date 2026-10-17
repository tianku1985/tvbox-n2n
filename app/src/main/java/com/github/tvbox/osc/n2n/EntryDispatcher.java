package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.AppsActivity;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.CustomVodActivity;
import com.github.tvbox.osc.ui.activity.DriveActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.LivePlayActivity;
import com.github.tvbox.osc.ui.activity.PushActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.activity.VodActivity;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 自定义入口统一分发：顶栏（HomeEntry）与首页历史一行（UserFragment）共用。
 * 入口类型见 {@link RemoteConfig.Entry}：
 *   page    打开 TVBox 界面（target 指定界面名）
 *   feature 内置功能（target 指定功能名）
 *   app     打开其他 App（pkg），未安装且配置 url 时走后台下载安装管线
 *   vod     打开自定义点播列表界面（target=数据接口地址，格式同点播 api json）
 */
public class EntryDispatcher {

    public static void open(Activity act, RemoteConfig.Entry e) {
        if (act == null || e == null) {
            return;
        }
        try {
            String type = e.type == null ? "" : e.type;
            String target = e.target == null ? "" : e.target;
            if ("page".equals(type)) {
                openPage(act, target);
            } else if ("feature".equals(type)) {
                runFeature(act, target);
            } else if ("vod".equals(type)) {
                Intent it = new Intent(act, CustomVodActivity.class);
                it.putExtra("name", e.name == null ? "" : e.name);
                it.putExtra("url", target.isEmpty() ? (e.url == null ? "" : e.url) : target);
                String theme = e.theme == null ? "" : e.theme.trim();
                it.putExtra("theme", theme);
                if (!theme.isEmpty()) {
                    // 独立主题：本界面及其播放页使用入口 "theme" 指定主题（主题索引/中文名/英文别名），
                    // 不影响全局主题；退出本界面时由 CustomVodActivity 撤销
                    com.github.tvbox.osc.n2n.ThemeManager.pushOverride(theme);
                    com.github.tvbox.osc.base.BaseActivity.resetWallpaper();
                }
                act.startActivity(it);
            } else {
                // 默认 app 类型
                openApp(act, e);
            }
        } catch (Throwable ignored) {
        }
    }

    /** page：打开 TVBox 内置界面 */
    private static void openPage(Activity act, String target) {
        Class<?> cls = null;
        if ("vod".equals(target)) {
            cls = VodActivity.class;
        } else if ("live".equals(target)) {
            cls = LivePlayActivity.class;
        } else if ("search".equals(target)) {
            cls = SearchActivity.class;
        } else if ("fastsearch".equals(target)) {
            cls = FastSearchActivity.class;
        } else if ("history".equals(target)) {
            cls = HistoryActivity.class;
        } else if ("collect".equals(target)) {
            cls = CollectActivity.class;
        } else if ("push".equals(target)) {
            cls = PushActivity.class;
        } else if ("setting".equals(target)) {
            cls = SettingActivity.class;
        } else if ("drive".equals(target)) {
            cls = DriveActivity.class;
        } else if ("apps".equals(target)) {
            cls = AppsActivity.class;
        } else if ("hin2n".equals(target)) {
            // hin2n 已拆分为独立 App（wang.switchy.hin2n）：已安装直接拉起，
            // 未安装且 JSON 配置了 hin2n.apkUrl 时走后台下载安装管线
            RemoteConfig.Entry e = new RemoteConfig.Entry();
            e.type = "app";
            e.name = "组网";
            e.pkg = "wang.switchy.hin2n";
            e.url = RemoteConfig.hin2nApkUrl();
            openApp(act, e);
            return;
        }
        if (cls != null) {
            act.startActivity(new Intent(act, cls));
        }
    }

    /** feature：内置功能 */
    private static void runFeature(Activity act, String target) {
        if ("siteSwitch".equals(target)) {
            if (act instanceof HomeActivity) {
                ((HomeActivity) act).showSiteSwitch();
            }
        } else if ("themeSwitch".equals(target)) {
            if (act instanceof HomeActivity) {
                HomeActivity.switchTheme(act, true);
            }
        } else if ("styleSwitch".equals(target)) {
            boolean grid = !Hawk.get(HawkConfig.HOME_REC_STYLE, false);
            Hawk.put(HawkConfig.HOME_REC_STYLE, grid);
            try {
                if (UserFragment.tvHotListForGrid != null && UserFragment.tvHotListForLine != null) {
                    UserFragment.tvHotListForGrid.setVisibility(grid ? android.view.View.VISIBLE : android.view.View.GONE);
                    UserFragment.tvHotListForLine.setVisibility(grid ? android.view.View.GONE : android.view.View.VISIBLE);
                }
            } catch (Throwable ignored) {
            }
            Toast.makeText(act, grid ? R.string.hm_style_grid : R.string.hm_style_line, Toast.LENGTH_SHORT).show();
            // 重建入口，使 style 图标状态同步（网格/列表图标自动切换）
            HomeEntry.refresh(act);
        } else if ("clearCache".equals(target)) {
            if (act instanceof HomeActivity) {
                ((HomeActivity) act).clearHomeCache();
            }
        } else if ("reloadHome".equals(target)) {
            if (act instanceof HomeActivity) {
                ((HomeActivity) act).reloadHome();
            }
        } else if ("wifi".equals(target)) {
            try {
                act.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Throwable ignored) {
            }
        } else if ("date".equals(target)) {
            try {
                act.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    /** app：已安装直接拉起；未安装且配置 url 时后台下载并安装（秒开酒店桌面风格） */
    private static void openApp(Activity act, RemoteConfig.Entry e) {
        if (e.pkg == null || e.pkg.isEmpty()) {
            return;
        }
        try {
            Intent it = act.getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (it != null) {
                act.startActivity(it);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 未安装：走下载安装管线（顶部进度条 + 完成后安装）
        ApkDownloader.handle(act, e);
    }

    /**
     * 入口图标加载：http(s) URL 走 Glide；否则按内置 drawable 名解析
     * （如 hm_search / hm_vod / ic_theme）；都失败时用应用抽屉图标兜底。
     */
    public static void loadIcon(Context ctx, ImageView iv, String icon) {
        if (iv == null) {
            return;
        }
        try {
            if (icon != null && (icon.startsWith("http://") || icon.startsWith("https://"))) {
                Glide.with(ctx).load(icon).into(iv);
                return;
            }
            if (icon != null && !icon.isEmpty()) {
                int resId = ctx.getResources().getIdentifier(icon, "drawable", ctx.getPackageName());
                if (resId != 0) {
                    iv.setImageResource(resId);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        iv.setImageResource(R.drawable.hm_drawer);
    }

    /** 按 Entry 加载图标：icon 留空时对 wifi/styleSwitch 入口自动选择状态图标 */
    public static void loadIcon(Context ctx, ImageView iv, RemoteConfig.Entry e) {
        loadIcon(ctx, iv, resolveIcon(ctx, e));
    }

    /** 是否为 hin2n 入口（组网：内置 page 入口或指向独立 hin2n App 的 app 入口） */
    public static boolean isHin2nEntry(RemoteConfig.Entry e) {
        return e != null && (("page".equals(e.type) && "hin2n".equals(e.target))
                || ("app".equals(e.type) && e.pkg != null && e.pkg.startsWith("wang.switchy.hin2n")));
    }

    /**
     * 解析入口图标名：显式配置优先；留空时部分内置功能入口自动按状态选图——
     * wifi 按当前网络类型（hm_wifi/hm_mobile/hm_lan/hm_wifi_no），
     * styleSwitch 按当前单双排状态（hm_up_down/hm_left_right）。
     */
    public static String resolveIcon(Context ctx, RemoteConfig.Entry e) {
        if (e == null) {
            return "";
        }
        if (e.icon != null && !e.icon.isEmpty()) {
            return e.icon;
        }
        if ("feature".equals(e.type) && "wifi".equals(e.target)) {
            return wifiIcon(ctx);
        }
        if ("feature".equals(e.type) && "styleSwitch".equals(e.target)) {
            return Hawk.get(HawkConfig.HOME_REC_STYLE, false) ? "hm_up_down" : "hm_left_right";
        }
        return "";
    }

    /** 按当前网络连接类型返回顶栏网络图标名（无连接=hm_wifi_no） */
    private static String wifiIcon(Context ctx) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            if (info == null || !info.isConnectedOrConnecting()) {
                return "hm_wifi_no";
            }
            if (info.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                return "hm_wifi";
            }
            if (info.getType() == android.net.ConnectivityManager.TYPE_MOBILE) {
                return "hm_mobile";
            }
            if (info.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) {
                return "hm_lan";
            }
        } catch (Throwable ignored) {
        }
        return "hm_wifi_no";
    }
}
