package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 * 入口未安装 App 时通过 ApkInstaller 后台下载 APK 并拉起安装。
 */
public class HomeEntry {

    /** hin2n 包名（switch-iot/hin2n，默认 applicationId） */
    public static final String HIN2N_PKG = "wang.switchy.hin2n";

    private static final Handler TICKER = new Handler(Looper.getMainLooper());
    private static Runnable tickRun = null;

    /** 绑定并刷新首页扩展区；可在 onCreate/onResume 重复调用（幂等） */
    public static void refresh(final Activity act) {
        if (act == null) {
            return;
        }
        // ---- hin2n 固定入口 ----
        ImageView tvHin2n = act.findViewById(R.id.tvHin2n);
        if (tvHin2n != null) {
            tvHin2n.setVisibility(RemoteConfig.hin2nEntryEnabled() ? View.VISIBLE : View.GONE);
            tvHin2n.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // hin2n 已内化为 TVBox 模块，直接打开 TV 引导页
                    act.startActivity(new Intent(act,
                            com.github.tvbox.osc.n2n.ui.Hin2nActivity.class));
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
        // 自定义入口栏：按远程 JSON 动态生成图标，点击拉起对应 App
        LinearLayout bar = act.findViewById(R.id.entryBar);
        if (bar == null) {
            return;
        }
        bar.removeAllViews();
        List<RemoteConfig.Entry> entries = RemoteConfig.entries();
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
            if (e.icon != null && !e.icon.isEmpty()) {
                Glide.with(act).load(e.icon).into(iv);
            } else {
                iv.setImageResource(R.drawable.hm_drawer);   // 无图标时用应用抽屉图标兜底
            }
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    launchApp(act, e.pkg, e.name, e.url);
                }
            });
            bar.addView(iv);
        }
        // 外观：入口配色对动态生成的图标同样生效
        applyHomeStyleToEntries(act);
    }

    /** 按包名拉起第三方 App；未安装则后台下载 APK 并拉起安装 */
    private static void launchApp(Activity act, String pkg, String name, final String url) {
        try {
            Intent it = act.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                act.startActivity(it);
            } else {
                showInstallDialog(act, name, url);
            }
        } catch (Throwable t) {
            Toast.makeText(act, "打开 " + name + " 失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 未安装提示：有下载地址则后台下载并拉起安装；无地址时提示去远程配置补充 */
    private static void showInstallDialog(final Activity act, String name, final String url) {
        boolean hasUrl = url != null && !url.trim().isEmpty();
        if (hasUrl) {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            b.setTitle("未安装「" + name + "」");
            b.setMessage("是否在后台下载并安装？\n\n下载地址：\n" + url.trim());
            b.setPositiveButton("后台下载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    ApkInstaller.downloadAndInstall(act, name, url.trim());
                }
            });
            b.setNegativeButton("取消", null);
            b.show();
        } else {
            AlertDialog.Builder b = new AlertDialog.Builder(act);
            b.setTitle("未安装「" + name + "」");
            b.setMessage("未安装「" + name + "」。\n请在「设置 → 配置地址」下方的远程配置接口中补充下载地址。");
            b.setNegativeButton("确定", null);
            b.show();
        }
    }

    // ==================== 首页外观渲染（home JSON） ====================

    /**
     * 远程背景图应用到内容层（android.R.id.content）背景。
     * BaseActivity.onCreate 统一调用，因此 JSON 的 home.bgImage 会在「所有页面」生效，
     * 而不仅限于首页；未配置时保持各页面原样。
     */
    public static void applyBackground(Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        JSONObject home = RemoteConfig.homeStyle();
        String bg = home != null ? home.optString("bgImage", "").trim() : "";
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
