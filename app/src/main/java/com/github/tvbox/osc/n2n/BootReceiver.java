package com.github.tvbox.osc.n2n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（BOOT_COMPLETED），保留两个相互独立的开关：
 * 1. hin2n 引导页里的「开机启动」（SP "Hin2n"/start_at_boot）：开机自动连接 n2n VPN；
 * 2. 设置页的 TVBox「开机启动」（RemoteConfig.bootStartEnabled）：开机拉起首页。
 *
 * 注意：Android 10+ 对后台拉起 Activity 有限制，部分 TV 固件可能拦截，best-effort。
 * hin2n 连接走前台 VpnService，未授予过 VPN 权限时直接跳过。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        final android.content.BroadcastReceiver.PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1) hin2n 开机自动连 VPN（自带开关与权限判断）
                    try {
                        wang.switchy.hin2n.Hin2nCore.init(ctx.getApplicationContext());
                        wang.switchy.hin2n.Hin2nCore.startOnBoot(ctx.getApplicationContext());
                    } catch (Throwable t) {
                        Log.w(TAG, "hin2n startOnBoot failed: " + t);
                    }
                    // 2) TVBox 开机启动拉起首页
                    if (RemoteConfig.bootStartEnabled()) {
                        Log.i(TAG, "boot completed, bootStart enabled");
                        try {
                            Intent home = new Intent();
                            home.setClassName(ctx.getPackageName(),
                                    "com.github.tvbox.osc.ui.activity.HomeActivity");
                            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            ctx.startActivity(home);
                        } catch (Throwable t) {
                            Log.w(TAG, "start HomeActivity failed: " + t);
                        }
                    }
                } finally {
                    pr.finish();
                }
            }
        }, "tvbox-boot").start();
    }
}
