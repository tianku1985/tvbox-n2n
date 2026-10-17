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

    /** 本进程已提示过的版本号 */
    private static final Set<Integer> SHOWN = new HashSet<>();
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
            String apkUrl = up.optString("url", "").trim();
            if (remoteCode <= 0 || apkUrl.isEmpty()) {
                return;
            }
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            if (remoteCode <= pi.versionCode || SHOWN.contains(remoteCode)) {
                return;
            }
            SHOWN.add(remoteCode);
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
            holder[0].show();
        } catch (Throwable ignored) {
        }
    }
}
