package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.github.tvbox.osc.R;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 首页扩展：hin2n 固定入口 + 远程配置的自定义入口栏 + 底部滚动公告 + 远程外观下发。
 *
 * HomeActivity 只需在 onCreate 绑定视图后与 onResume 中各调一行 refresh(this)，
 * 视图查找、点击事件、远程拉取、定时刷新与外观渲染全部内聚在本类，补丁锚点最少。
 * 所有视图缺失（非 takagen99 布局）时静默跳过，不影响原逻辑。
 *
 * 公告条渲染与滚动（含首页/直播/点播三处显示开关、由右向左方向）统一由 {@link NoticeBar} 负责；
 * 远程 JSON 的 notice/home 样式字段见 RemoteConfig 类注释；
 * hin2n 组网已拆分为独立 App（包名 wang.switchy.hin2n），顶栏 tvHin2n 与历史行 tvHin2nHome
 * 点击统一走 EntryDispatcher：已安装直接拉起，未安装且配置 hin2n.apkUrl 时后台下载安装。
 */
public class HomeEntry {

    private static final Handler TICKER = new Handler(Looper.getMainLooper());
    private static Runnable tickRun = null;

    /** 绑定并刷新首页扩展区；可在 onCreate/onResume 重复调用（幂等） */
    public static void refresh(final Activity act) {
        if (act == null) {
            return;
        }
        // ---- hin2n 固定入口（独立 App：已安装拉起，未安装走下载安装管线） ----
        ImageView tvHin2n = act.findViewById(R.id.tvHin2n);
        if (tvHin2n != null) {
            tvHin2n.setVisibility(RemoteConfig.hin2nEntryEnabled() ? View.VISIBLE : View.GONE);
            tvHin2n.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    RemoteConfig.Entry e = new RemoteConfig.Entry();
                    e.type = "page";
                    e.name = "组网";
                    e.target = "hin2n";
                    EntryDispatcher.open(act, e);
                }
            });
        }
        // ---- 公告/入口/外观：先用缓存渲染，再后台拉取最新配置 ----
        populate(act);
        RemoteConfig.fetchAuto(act, new RemoteConfig.Callback() {
            @Override
            public void onDone(boolean success) {
                if (!act.isFinishing() && !act.isDestroyed()) {
                    try { populate(act); } catch (Throwable ignored) {}
                }
            }
        });
        // ---- 定时检查 JSON 接口并刷新显示内容 ----
        startTicker(act);
    }

    /** 停止定时刷新与公告滚动（HomeActivity.onDestroy 调用，避免静态泄漏） */
    public static void stopTicker() {
        if (tickRun != null) {
            TICKER.removeCallbacks(tickRun);
            tickRun = null;
        }
        NoticeBar.stopScroll();
    }

    /** 按当前缓存的公告/入口/外观配置重建视图 */
    private static void populate(Activity act) {
        applyBackground(act);
        applyHomeStyle(act);
        NoticeBar.show(act, NoticeBar.SCREEN_HOME);
        // 自定义入口栏：按远程 JSON 动态生成图标
        LinearLayout bar = act.findViewById(R.id.entryBar);
        if (bar == null) {
            return;
        }
        bar.removeAllViews();
        // topEntries 配置存在时整体替换顶栏：隐藏全部内置固定图标，顺序/内容/图标全按 JSON
        List<RemoteConfig.Entry> top = RemoteConfig.topEntries();
        if (top != null) {
            int[] fixedIds = {R.id.tvWifi, R.id.tvFind, R.id.tvStyle, R.id.tvTheme,
                    R.id.tvDrawer, R.id.tvN2n, R.id.tvHin2n};
            for (int id : fixedIds) {
                View v = act.findViewById(id);
                if (v != null) {
                    v.setVisibility(View.GONE);
                }
            }
            buildTopEntries(act, bar, top);
            applyHomeStyleToEntries(act);
            return;
        }
        // 旧版 "entries"：仅 app 类型，点击拉起对应 App（未安装且配 url 时后台下载安装）
        List<RemoteConfig.Entry> entries = RemoteConfig.entries();
        buildTopEntries(act, bar, entries);
        // 外观：入口配色对动态生成的图标同样生效
        applyHomeStyleToEntries(act);
    }

    /** 顶栏动态入口渲染：图标按钮（vs_50），点击统一走 EntryDispatcher */
    private static void buildTopEntries(final Activity act, LinearLayout bar, List<RemoteConfig.Entry> entries) {
        int size = act.getResources().getDimensionPixelSize(R.dimen.vs_50);
        int margin = act.getResources().getDimensionPixelSize(R.dimen.vs_15);
        int pad = act.getResources().getDimensionPixelSize(R.dimen.vs_10);
        for (final RemoteConfig.Entry e : entries) {
            ImageView iv = new ImageView(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(margin);
            lp.setMarginEnd(margin);
            iv.setLayoutParams(lp);
            iv.setPadding(pad, pad, pad, pad);
            iv.setBackgroundResource(R.drawable.button_dialog_vod);
            iv.setClickable(true);
            iv.setFocusable(true);
            iv.setContentDescription(e.name);
            EntryDispatcher.loadIcon(act, iv, e);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EntryDispatcher.open(act, e);
                }
            });
            bar.addView(iv);
        }
    }

    // ==================== 首页外观渲染（home JSON） ====================

    /**
     * 远程背景图应用到内容层（android.R.id.content）背景。
     * BaseActivity.onCreate 统一调用，因此背景图会在「所有页面」生效，而不仅限于首页。
     * 取值优先级：home.bgImage（全局固定）> 当前主题 themes[].bgImages/bgImage（一个或多个，
     * 多张随机选一张后按主题保持稳定）> 都不配置时保持各页面原样（主题渐变背景）。
     */
    public static void applyBackground(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        JSONObject home = RemoteConfig.homeStyle();
        String bg = home != null ? home.optString("bgImage", "").trim() : "";
        if (bg.isEmpty()) {
            try {
                String themed = ThemeManager.currentBgImage();
                bg = themed != null ? themed : "";
            } catch (Throwable ignored) {
            }
        }
        if (bg.isEmpty()) {
            return;
        }
        final View content = act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        try {
            Glide.with(act).load(bg).into(new SimpleTarget<Drawable>(
                    com.bumptech.glide.request.target.Target.SIZE_ORIGINAL,
                    com.bumptech.glide.request.target.Target.SIZE_ORIGINAL) {
                @Override
                public void onResourceReady(Drawable resource, Transition<? super Drawable> transition) {
                    if (act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    try { content.setBackground(resource); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /** 应用首页外观：入口背景色 / 图标色 / 文字色 / 入口透明度（背景图见 {@link #applyBackground}） */
    private static void applyHomeStyle(Activity act) {
        JSONObject home = RemoteConfig.homeStyle();
        if (home == null || home.length() == 0) {
            return;
        }
        // 顶栏文字颜色
        int textColor = parseColor(home.optString("textColor", ""));
        if (textColor != 0) {
            TextView tvName = act.findViewById(R.id.tvName);
            TextView tvDate = act.findViewById(R.id.tvDate);
            if (tvName != null) {
                tvName.setTextColor(textColor);
            }
            if (tvDate != null) {
                tvDate.setTextColor(textColor);
            }
        }
        applyHomeStyleToEntries(act);
    }

    /** 入口（固定 + 动态）背景色 / 图标着色 / 整体透明度 */
    private static void applyHomeStyleToEntries(Activity act) {
        JSONObject home = RemoteConfig.homeStyle();
        if (home == null || home.length() == 0) {
            return;
        }
        int entryBg = parseColor(home.optString("entryBgColor", ""));
        int iconColor = parseColor(home.optString("iconColor", ""));
        float alpha = -1f;
        try {
            if (home.has("alpha")) {
                alpha = Math.max(0, Math.min(100, home.optInt("alpha", 100))) / 100f;
            }
        } catch (Throwable ignored) {
        }
        int[] ids = {R.id.tvWifi, R.id.tvFind, R.id.tvStyle, R.id.tvTheme, R.id.tvDrawer, R.id.tvN2n, R.id.tvHin2n};
        for (int id : ids) {
            applyEntryStyle((View) act.findViewById(id), entryBg, iconColor, alpha);
        }
        LinearLayout bar = act.findViewById(R.id.entryBar);
        if (bar != null) {
            for (int i = 0; i < bar.getChildCount(); i++) {
                applyEntryStyle(bar.getChildAt(i), entryBg, iconColor, alpha);
            }
        }
    }

    private static void applyEntryStyle(View v, int entryBg, int iconColor, float alpha) {
        if (v == null) {
            return;
        }
        if (entryBg != 0) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(entryBg);
            gd.setCornerRadius(dp2px(v, 8));
            v.setBackground(gd);
        }
        if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            if (iconColor != 0) {
                iv.setColorFilter(iconColor);
            } else {
                iv.clearColorFilter();
            }
        }
        if (alpha >= 0f) {
            v.setAlpha(alpha);
        }
    }

    // ==================== 定时检查 JSON 接口 ====================

    /** 按 checkInterval 分钟定时拉取远程配置并重建显示内容（WeakReference 防泄漏） */
    private static void startTicker(final Activity act) {
        stopTicker();
        final WeakReference<Activity> ref = new WeakReference<>(act);
        tickRun = new Runnable() {
            @Override
            public void run() {
                final Activity a = ref.get();
                if (a == null || a.isFinishing() || a.isDestroyed()) {
                    tickRun = null;
                    return;
                }
                try {
                    RemoteConfig.fetchAuto(a, new RemoteConfig.Callback() {
                        @Override
                        public void onDone(boolean success) {
                            Activity a2 = ref.get();
                            if (a2 != null && !a2.isFinishing() && !a2.isDestroyed()) {
                                try { populate(a2); } catch (Throwable ignored) {}
                            }
                        }
                    });
                } catch (Throwable ignored) {}
                if (tickRun != null) {
                    TICKER.postDelayed(this, RemoteConfig.checkIntervalMinutes() * 60000L);
                }
            }
        };
        TICKER.postDelayed(tickRun, RemoteConfig.checkIntervalMinutes() * 60000L);
    }

    // ==================== 工具 ====================

    /** 输入框背景色（home.inputBgColor）；未配置时保持原样。设置页输入框与弹窗输入框调用 */
    public static void styleInput(View v) {
        if (v == null) {
            return;
        }
        int c = parseColor(RemoteConfig.homeStyle().optString("inputBgColor", ""));
        if (c != 0) {
            v.setBackgroundColor(c);
        }
    }

    /** 按钮背景色（home.buttonBgColor）；未配置时保持原样。设置页按钮调用 */
    public static void styleButton(View v) {
        if (v == null) {
            return;
        }
        int c = parseColor(RemoteConfig.homeStyle().optString("buttonBgColor", ""));
        if (c != 0) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(c);
            gd.setCornerRadius(dp2px(v, 6));
            v.setBackground(gd);
        }
    }

    /** 解析 #RRGGBB / #AARRGGBB；非法返回 0 */
    public static int parseColor(String s) {
        try {
            if (s == null) {
                return 0;
            }
            s = s.trim();
            if (s.isEmpty()) {
                return 0;
            }
            long v = Long.parseLong(s.replace("#", ""), 16);
            if (s.length() == 7) {
                v |= 0xFF000000L;
            }
            return (int) v;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static float dp2px(View v, float dp) {
        return dp * v.getResources().getDisplayMetrics().density;
    }
}
