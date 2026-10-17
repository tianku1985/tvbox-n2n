package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.n2n.ui.Hin2nActivity;
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
            cls = Hin2nActivity.class;
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
}
