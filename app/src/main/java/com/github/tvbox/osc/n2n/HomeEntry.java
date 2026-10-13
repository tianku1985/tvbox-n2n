package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;

import java.util.List;

/**
 * 首页扩展：hin2n 固定入口 + 远程配置的自定义入口栏 + 底部滚动公告。
 *
 * HomeActivity 只需在 onCreate 绑定视图后与 onResume 中各调一行 refresh(this)，
 * 视图查找、点击事件、远程拉取与重建全部内聚在本类，补丁锚点最少。
 * 所有视图缺失（非 takagen99 布局）时静默跳过，不影响原逻辑。
 */
public class HomeEntry {

    /** hin2n 包名（switch-iot/hin2n，默认 applicationId） */
    public static final String HIN2N_PKG = "wang.switchy.hin2n";

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
                    launchApp(act, HIN2N_PKG, "hin2n", RemoteConfig.hin2nUrl());
                }
            });
        }
        // ---- 公告与自定义入口：先用缓存渲染，再后台拉取最新配置 ----
        populate(act);
        RemoteConfig.fetchAuto(act, new RemoteConfig.Callback() {
            @Override
            public void onDone(boolean success) {
                if (!act.isFinishing()) {
                    populate(act);
                }
            }
        });
    }

    /** 按当前缓存的公告/入口配置重建视图 */
    private static void populate(Activity act) {
        // 底部滚动公告（酒店 IPTV 风格）：无公告时隐藏
        TextView tvNotice = act.findViewById(R.id.tvNotice);
        if (tvNotice != null) {
            String notice = RemoteConfig.notice();
            if (notice == null || notice.trim().isEmpty()) {
                tvNotice.setVisibility(View.GONE);
            } else {
                tvNotice.setText(notice.trim());
                tvNotice.setVisibility(View.VISIBLE);
                tvNotice.setSelected(true);   // 选中态才会触发 marquee 滚动
            }
        }
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
    }

    /** 按包名拉起第三方 App；未安装则弹窗提示并从接口给出的下载地址引导安装 */
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

    /** 未安装提示：展示下载地址，并提供「立即下载」直接跳转 */
    private static void showInstallDialog(final Activity act, String name, final String url) {
        boolean hasUrl = url != null && !url.trim().isEmpty();
        AlertDialog.Builder b = new AlertDialog.Builder(act);
        b.setTitle("未安装「" + name + "」");
        if (hasUrl) {
            b.setMessage("是否前往下载安装？\n\n下载地址：\n" + url.trim());
            b.setPositiveButton("立即下载", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openUrl(act, url.trim());
                }
            });
        } else {
            b.setMessage("未安装「" + name + "」。\n请在「设置 → 配置地址」下方的远程配置接口中补充下载地址。");
        }
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 用系统浏览器/应用商店打开下载地址；无可用应用时再回退为文本提示 */
    private static void openUrl(Activity act, String url) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
        } catch (Throwable t) {
            Toast.makeText(act, "无法打开下载页，请手动访问：\n" + url, Toast.LENGTH_LONG).show();
        }
    }
}
