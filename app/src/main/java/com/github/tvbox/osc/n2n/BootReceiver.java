package com.github.tvbox.osc.n2n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（BOOT_COMPLETED）：设置页的 TVBox「开机启动」（RemoteConfig.bootStartEnabled）
 * 开启时开机拉起首页。
 *
 * 注意：Android 10+ 对后台拉起 Activity 有限制，部分 TV 固件可能拦截，best-effort。
 * hin2n 组网已拆分为独立 App（包名 wang.switchy.hin2n），其开机自连由该 App 自身处理。
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
                    // TVBox 开机启动拉起首页
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
