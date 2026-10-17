package wang.switchy.hin2n;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 开机自启（BOOT_COMPLETED）：若用户在组网页开启了「开机启动」，
 * 系统启动后自动初始化并连接 n2n VPN（未授予 VPN 权限时由 startOnBoot 内部安全跳过）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Hin2nBootReceiver";

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Hin2nCore.init(ctx.getApplicationContext());
                    Hin2nCore.startOnBoot(ctx.getApplicationContext());
                } catch (Throwable t) {
                    Log.w(TAG, "hin2n startOnBoot failed: " + t);
                } finally {
                    pr.finish();
                }
            }
        }, "hin2n-boot").start();
    }
}
