package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Progress;
import com.lzy.okgo.model.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * APK 后台下载安装管线（秒开酒店 IPTV 桌面风格）：
 * 入口配置的 App 未安装时点击 → 后台下载 APK 到 getExternalFilesDir("apk")，
 * 同时在界面顶部显示下载进度条，下载完成后调起系统安装。
 * Android 8+ 先引导「允许安装未知来源」；FileProvider 提供 content:// Uri。
 *
 * 同一地址并发去重；进度条挂在发起下载的 Activity 内容层顶部，切页不中断下载。
 */
public class ApkDownloader {

    private static final String TAG_OVERLAY = "tvbox_apk_dl_overlay";
    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 正在下载的地址（去重） */
    private static final Set<String> DOWNLOADING = new HashSet<>();
    private static WeakReference<Activity> sAct = new WeakReference<>(null);
    /** 应用上下文：发起下载的 Activity 已销毁时仍可调起安装（配合 NEW_TASK） */
    private static WeakReference<Context> sApp = new WeakReference<>(null);
    /** 已下载完成、等待「未知来源」授权后安装的 APK（从系统设置返回时重试） */
    private static volatile File sPendingInstall = null;

    public interface Listener {
        void onDone(File apk, boolean success, String msg);
    }

    /** 入口点击：App 未安装时调用。url 为空仅提示；否则开始/复用下载。 */
    public static void handle(final Activity act, final RemoteConfig.Entry e) {
        if (act == null || e == null) {
            return;
        }
        final String url = e.url == null ? "" : e.url.trim();
        final String name = (e.name == null || e.name.isEmpty()) ? "应用" : e.name;
        if (url.isEmpty()) {
            Toast.makeText(act, "「" + name + "」未安装", Toast.LENGTH_SHORT).show();
            return;
        }
        sAct = new WeakReference<>(act);
        download(act, url, name, null);
    }

    /** 通用下载入口（远程升级复用）：显示顶部进度，完成后回调 */
    public static void download(final Activity act, final String url, final String name, final Listener listener) {
        if (act == null || url == null || url.isEmpty()) {
            return;
        }
        if (DOWNLOADING.contains(url)) {
            Toast.makeText(act, "「" + name + "」正在下载中", Toast.LENGTH_SHORT).show();
            return;
        }
        DOWNLOADING.add(url);
        sAct = new WeakReference<>(act);
        sApp = new WeakReference<>(act.getApplicationContext());
        showOverlay(act, name, 0);
        OkGo.<File>get(url).tag(url).execute(new AbsCallback<File>() {
            @Override
            public File convertResponse(okhttp3.Response response) throws Throwable {
                File dir = act.getExternalFilesDir("apk");
                if (dir == null) {
                    dir = act.getFilesDir();
                }
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File apk = new File(dir, "dl_" + Math.abs(url.hashCode()) + ".apk");
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    in = response.body().byteStream();
                    fos = new FileOutputStream(apk);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                    fos.flush();
                    return apk;
                } finally {
                    try {
                        if (in != null) in.close();
                    } catch (Throwable ignored) {
                    }
                    try {
                        if (fos != null) fos.close();
                    } catch (Throwable ignored) {
                    }
                }
            }

            @Override
            public void downloadProgress(Progress progress) {
                if (progress != null && progress.totalSize > 0) {
                    int pct = (int) (progress.currentSize * 100 / progress.totalSize);
                    Activity a = sAct.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        showOverlay(a, name, pct);
                    }
                }
            }

            @Override
            public void onSuccess(Response<File> response) {
                DOWNLOADING.remove(url);
                hideOverlay();
                File apk = response.body();
                if (apk != null && apk.exists()) {
                    if (listener != null) {
                        listener.onDone(apk, true, "");
                    } else {
                        Activity a = sAct.get();
                        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                            install(a, apk);
                        } else {
                            installWithAppContext(apk);
                        }
                    }
                } else {
                    fail(url, name, "下载文件为空", listener);
                }
            }

            @Override
            public void onError(Response<File> response) {
                DOWNLOADING.remove(url);
                hideOverlay();
                Throwable ex = response.getException();
                fail(url, name, ex != null ? ex.getMessage() : "网络错误", listener);
            }
        });
    }

    private static void fail(String url, String name, String msg, Listener listener) {
        Activity a = sAct.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            Toast.makeText(a, "「" + name + "」下载失败：" + msg, Toast.LENGTH_SHORT).show();
        }
        if (listener != null) {
            listener.onDone(null, false, msg);
        }
    }

    /** 调起系统安装；Android 8+ 先检查未知来源安装权限 */
    public static void install(final Activity act, final File apk) {
        if (act == null || apk == null || !apk.exists()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !act.getPackageManager().canRequestPackageInstalls()) {
                // 记住待安装文件，用户从系统设置返回后由 resumePendingInstall 自动重试
                sPendingInstall = apk;
                Toast.makeText(act, "请允许 TVBox 安装应用", Toast.LENGTH_LONG).show();
                act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                return;
            }
            startInstallIntent(act, apk);
        } catch (Throwable t) {
            Toast.makeText(act, "安装失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Activity onResume 调用：若此前因「未知来源」权限跳了系统设置，
     * 用户授权返回后自动重新调起安装；未授权则继续等待。
     */
    public static void resumePendingInstall(Activity act) {
        File apk = sPendingInstall;
        if (act == null || apk == null) {
            return;
        }
        if (!apk.exists()) {
            sPendingInstall = null;
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || act.getPackageManager().canRequestPackageInstalls()) {
            sPendingInstall = null;
            install(act, apk);
        }
    }

    /** Activity 不可用时（已切到后台/销毁）用应用上下文调起安装 */
    private static void installWithAppContext(File apk) {
        Context app = sApp.get();
        if (app == null || apk == null || !apk.exists()) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !app.getPackageManager().canRequestPackageInstalls()) {
                // 没有界面可引导授权：记录待安装文件，下次进首页 onResume 时再引导
                sPendingInstall = apk;
                return;
            }
            startInstallIntent(app, apk);
        } catch (Throwable ignored) {
        }
    }

    private static void startInstallIntent(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(it);
    }

    // ==================== 顶部下载进度条 ====================

    private static void showOverlay(Activity act, String name, int percent) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        View exist = content.findViewWithTag(TAG_OVERLAY);
        if (exist instanceof FrameLayout) {
            updateOverlay((FrameLayout) exist, name, percent);
            return;
        }
        // 顶部整宽进度条：背景条 + 进度 + 文字
        FrameLayout bar = new FrameLayout(act);
        bar.setTag(TAG_OVERLAY);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 28));
        lp.gravity = Gravity.TOP;
        bar.setLayoutParams(lp);
        bar.setBackgroundColor(0xCC1B1B1B);

        ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(percent);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pb.setLayoutParams(plp);
        bar.addView(pb);

        TextView tv = new TextView(act);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setSingleLine(true);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        tv.setLayoutParams(tlp);
        bar.addView(tv);

        content.addView(bar);
        updateOverlay(bar, name, percent);
    }

    private static void updateOverlay(FrameLayout bar, String name, int percent) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c instanceof ProgressBar) {
                ((ProgressBar) c).setProgress(percent);
            } else if (c instanceof TextView) {
                ((TextView) c).setText(percent >= 100
                        ? "「" + name + "」下载完成，准备安装…"
                        : "正在下载「" + name + "」 " + percent + "%");
            }
        }
    }

    private static void hideOverlay() {
        final Activity act = sAct.get();
        if (act == null) {
            return;
        }
        H.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                ViewGroup content = act.findViewById(android.R.id.content);
                if (content == null) {
                    return;
                }
                View exist = content.findViewWithTag(TAG_OVERLAY);
                if (exist != null) {
                    content.removeView(exist);
                }
            }
        }, 1200);
    }

    private static int dp(Activity act, int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
