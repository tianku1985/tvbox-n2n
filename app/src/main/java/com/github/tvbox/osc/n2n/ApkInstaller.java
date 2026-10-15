package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.github.tvbox.osc.util.urlhttp.CallBackUtil;
import com.github.tvbox.osc.util.urlhttp.UrlHttpUtil;

import java.io.File;
import java.util.Locale;

/**
 * 远程入口配套的后台 APK 下载安装器：
 * 未安装入口对应的 App 时，直接在 TVBox 内后台下载 APK（不跳浏览器），
 * 下载完成后拉起系统安装界面；Android 8+ 缺少「安装未知应用」授权时先引导开启。
 */
public final class ApkInstaller {

    /** 正在下载中的地址（防止重复点击重复下载） */
    private static String downloadingUrl = null;

    private ApkInstaller() {
    }

    /**
     * 后台下载 APK 并在完成后拉起安装。
     *
     * @param name 入口名称（仅用于提示文案）
     * @param url  APK 下载地址
     */
    public static void downloadAndInstall(final Activity act, String name, final String url) {
        if (act == null || url == null || url.trim().isEmpty()) {
            return;
        }
        final String u = url.trim();
        if (u.equals(downloadingUrl)) {
            Toast.makeText(act, "「" + name + "」正在下载中，请稍候…", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 8+ 需要用户先授予「安装未知应用」权限
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                if (!act.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(act, "请先允许 TVBox 安装未知应用", Toast.LENGTH_LONG).show();
                    try {
                        act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName())));
                    } catch (Throwable ignored) {
                    }
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        File dir = act.getExternalFilesDir("apk");
        if (dir == null) {
            dir = act.getFilesDir();
        }
        final String fileName = "rc_" + Integer.toHexString(u.toLowerCase(Locale.US).hashCode()) + ".apk";
        final File apk = new File(dir, fileName);
        if (apk.exists() && apk.length() > 0 && install(act, apk)) {
            return;   // 已有可安装的缓存，直接装
        }
        downloadingUrl = u;
        Toast.makeText(act, "开始后台下载「" + name + "」…", Toast.LENGTH_SHORT).show();
        UrlHttpUtil.downloadFile(u, new CallBackUtil.CallBackFile(dir.getAbsolutePath(), fileName) {
            private int lastStep = -1;

            @Override
            public void onProgress(float progress, long total) {
                // 每 25% 提示一次，避免刷屏
                int step = (int) (progress / 25);
                if (step > lastStep) {
                    lastStep = step;
                    Toast.makeText(act, "下载中 " + Math.min(100, (int) progress) + "%", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(int code, String errorMessage) {
                downloadingUrl = null;
                Toast.makeText(act, "「" + name + "」下载失败(" + code + ")，请稍后重试", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onResponse(File file) {
                downloadingUrl = null;
                if (file == null || file.length() <= 0) {
                    Toast.makeText(act, "「" + name + "」下载失败，文件无效", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!install(act, file)) {
                    Toast.makeText(act, "无法拉起安装，请手动安装已下载的 APK", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    /** 通过 FileProvider 拉起系统安装界面；成功返回 true */
    private static boolean install(Activity act, File apk) {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= 24) {
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
                it.setDataAndType(uri, "application/vnd.android.package-archive");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                it.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(it);
            return true;
        } catch (Throwable t) {
            apk.delete();   // 安装失败大概率是缓存文件损坏，清掉以便下次重新下载
            return false;
        }
    }
}
