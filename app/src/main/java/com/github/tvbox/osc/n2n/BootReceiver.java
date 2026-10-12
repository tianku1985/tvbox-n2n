package com.github.tvbox.osc.n2n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启：收到 BOOT_COMPLETED 后，若「开机启动」开关打开（远程配置页，可经远程
 * JSON 接口下发默认值），则拉起首页。
 *
 * 注意：Android 10+ 对后台拉起 Activity 有限制，部分盒子系统（多数 TV 固件对
 * BOOT_COMPLETED 拉起较宽松）可能被拦截，此处做 best-effort。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!RemoteConfig.bootStartEnabled()) {
            return;
        }
        Log.i(TAG, "boot completed, bootStart enabled");
        // 拉起首页
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
}
