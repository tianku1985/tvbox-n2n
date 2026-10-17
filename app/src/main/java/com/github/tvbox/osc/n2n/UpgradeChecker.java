package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.pm.PackageInfo;

import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

/**
 * TVBox 远程升级（秒开酒店 IPTV 桌面风格）：
 * 内置 JSON 的 "upgrade" 段配置升级接口：
 *   "upgrade": {
 *     "checkUrl": "https://example.com/tvbox-version.json",  // 远程版本信息地址（优先）
 *     "versionCode": 2, "versionName": "1.0.2",              // 或内联直接给出（无 checkUrl 时）
 *     "url": "https://example.com/tvbox.apk",                // 新版 APK 下载地址
 *     "force": false,                                        // 强制升级（不显示取消按钮）
 *     "notes": "更新内容"                                     // 可选
 *   }
 * checkUrl 返回同结构 json。检测到 versionCode 大于当前版本时弹窗提示，
 * 确认后走 ApkDownloader 后台下载（顶部进度条）并调起安装。
 * 每个 versionCode 每进程只提示一次。
 */
public class UpgradeChecker {

    /** 本进程已提示过的版本标识（versionCode/versionName 组合） */
    private static final Set<String> SHOWN = new HashSet<>();
    private static WeakReference<Activity> sAct = new WeakReference<>(null);

    /** HomeActivity 启动后调用（幂等，每版本每进程一次） */
    public static void check(final Activity act) {
        if (act == null || act.isFinishing() || act.isDestroyed()) {
            return;
        }
        JSONObject up = RemoteConfig.upgrade();
        if (up == null) {
            return;
        }
        String checkUrl = up.optString("checkUrl", "").trim();
        if (!checkUrl.isEmpty()) {
            sAct = new WeakReference<>(act);
            OkGo.<String>get(checkUrl).execute(new AbsCallback<String>() {
                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    if (response.body() != null) {
                        return response.body().string();
                    }
                    throw new IllegalStateException("网络请求错误");
                }

                @Override
                public void onSuccess(Response<String> response) {
                    try {
                        Activity a = sAct.get();
                        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                            maybePrompt(a, new JSONObject(response.body()));
                        }
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                }
            });
        } else {
            // 内联模式：配置段里直接给出 versionCode/url
            maybePrompt(act, up);
        }
    }

    /** 版本比较与提示弹窗 */
    private static void maybePrompt(final Activity act, JSONObject up) {
        try {
            int remoteCode = up.optInt("versionCode", 0);
            String remoteName = up.optString("versionName", "").trim();
            String apkUrl = up.optString("url", "").trim();
            if (apkUrl.isEmpty()) {
                return;
            }
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            int localCode = pi.versionCode;
            String localName = pi.versionName != null ? pi.versionName : "";

            boolean needUpgrade = false;
            if (remoteCode > 0 && localCode > 0) {
                needUpgrade = remoteCode > localCode;
            }
            // versionName 兜底：versionCode 相等/缺失时，versionName 语义更大也提示
            if (!needUpgrade && !remoteName.isEmpty() && !localName.isEmpty()) {
                needUpgrade = compareVersionName(remoteName, localName) > 0;
            }
            String shownKey = remoteCode + "/" + remoteName;
            if (!needUpgrade || SHOWN.contains(shownKey)) {
                return;
            }
            SHOWN.add(shownKey);
            final String versionName = up.optString("versionName", "").trim();
            final boolean force = up.optBoolean("force", false);
            String notes = up.optString("notes", "").trim();
            StringBuilder msg = new StringBuilder("发现新版本");
            if (!versionName.isEmpty()) {
                msg.append(" ").append(versionName);
            }
            if (!notes.isEmpty()) {
                msg.append("\n\n").append(notes);
            }
            final TipDialog[] holder = new TipDialog[1];
            holder[0] = new TipDialog(act, msg.toString(), "立即升级", force ? "" : "取消", new TipDialog.OnListener() {
                @Override
                public void left() {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                    String name = "TVBox" + (versionName.isEmpty() ? "" : "-" + versionName);
                    ApkDownloader.download(act, apkUrl, name, new ApkDownloader.Listener() {
                        @Override
                        public void onDone(File apk, boolean success, String msg) {
                            if (success && apk != null) {
                                ApkDownloader.install(act, apk);
                            }
                        }
                    });
                }

                @Override
                public void right() {
                    if (holder[0] != null) {
                        holder[0].dismiss();
                    }
                }

                @Override
                public void cancel() {
                }
            });
            // 强制升级：屏蔽返回键与点击外部取消
            holder[0].setCancelable(!force);
            holder[0].show();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 点分版本号比较（如 "1.0.2" vs "1.0.10"）。
     * 忽略非数字段，位数不足补 0；返回 >0 表示 v1 更新。
     */
    private static int compareVersionName(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";
        String[] a1 = v1.split("\\.");
        String[] a2 = v2.split("\\.");
        int len = Math.max(a1.length, a2.length);
        for (int i = 0; i < len; i++) {
            long n1 = i < a1.length ? parseLongSafe(a1[i]) : 0;
            long n2 = i < a2.length ? parseLongSafe(a2[i]) : 0;
            if (n1 != n2) {
                return n1 > n2 ? 1 : -1;
            }
        }
        return 0;
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else {
                break;
            }
        }
        if (sb.length() == 0) return 0;
        try {
            return Long.parseLong(sb.toString());
        } catch (Throwable t) {
            return 0;
        }
    }
}
