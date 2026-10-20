package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.orhanobut.hawk.Hawk;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 系统桌面（Launcher）切换：把 TVBox 设为系统默认桌面，并在启用时禁用原来的系统桌面。
 *
 * <p>实现策略（按优先级）：</p>
 * <ol>
 *   <li>设备有 root（{@code su} 可用且返回 uid=0）时，直接调用系统服务命令完成替换：
 *       {@code pm disable-user --user 0 <原桌面包>} 禁用原桌面、
 *       {@code pm set-home-activity <本应用包>} 把 TVBox 设为默认桌面；</li>
 *   <li>无 root 时退回系统接口引导：启用 TVBox 的 HOME 组件别名后，
 *       直接以 CATEGORY_HOME 启动唤起系统「选择默认桌面」弹窗（含「始终/仅一次」），
 *       必要时再跳系统「主屏幕」设置页。</li>
 * </ol>
 *
 * <p>所有 root 命令都在后台线程执行且有超时保护（su 弹授权框时不阻塞主线程），
 * 结果通过 {@code runOnUiThread} 回主线程提示。</p>
 */
public class LauncherHelper {

    private static final String TAG = "LauncherHelper";

    /** Hawk 键：被替换（禁用）的原系统桌面包名，取消时用于恢复 */
    public static final String K_STOCK_LAUNCHER = "rc_stock_launcher";

    private static final long TIMEOUT_ROOT_PROBE = 3000L;
    private static final long TIMEOUT_CMD = 6000L;

    /** root 检测结果按进程缓存（每次检测都要起 su 进程，代价高） */
    private static volatile Boolean sRoot = null;

    // ==================== 进程执行（带超时） ====================

    /**
     * 执行命令并读取输出：读线程超时未结束则销毁进程并返回 null
     * （典型场景是 su 弹出 root 授权框，必须避免无限等待）。
     */
    private static String execWithOutput(String[] argv, long timeoutMs) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(argv);
            final InputStream is = process.getInputStream();
            final StringBuilder sb = new StringBuilder();
            final Process proc = process;
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(is));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (br != null) {
                            try {
                                br.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }, "launcher-exec");
            reader.setDaemon(true);
            reader.start();
            reader.join(timeoutMs);
            if (reader.isAlive()) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
                Log.i(TAG, "exec timeout: " + TextUtils.join(" ", argv));
                return null;
            }
            try {
                proc.waitFor();
            } catch (Throwable ignored) {
            }
            return sb.toString();
        } catch (Throwable th) {
            return null;
        }
    }

    /** 设备是否具备 root（su 可执行且返回 uid=0） */
    public static boolean hasRoot() {
        Boolean cached = sRoot;
        if (cached != null) {
            return cached;
        }
        String out = execWithOutput(new String[]{"su", "-c", "id"}, TIMEOUT_ROOT_PROBE);
        boolean root = out != null && out.contains("uid=0");
        sRoot = root;
        Log.i(TAG, "hasRoot = " + root);
        return root;
    }

    /**
     * 执行一条系统命令：优先 root（{@code su -c}；超时视为失败），
     * 再退普通 shell（系统签名/平台权限构建下也可能具备 pm 权限）。
     */
    public static boolean exec(String cmd) {
        if (execWithOutput(new String[]{"su", "-c", cmd}, TIMEOUT_CMD) != null) {
            return true;
        }
        return execWithOutput(new String[]{"sh", "-c", cmd}, TIMEOUT_CMD) != null;
    }

    // ==================== 桌面解析 ====================

    /**
     * 取「原来的系统桌面」包名：优先当前默认桌面（排除自身），
     * 否则从所有 CATEGORY_HOME 候选中取优先级最高且非自身的一个。
     * 注意需在启用本应用 HOME 别名之前调用，避免把自己算进去（本应用 HomeActivity 是 LAUNCHER，
     * 只有别名带 CATEGORY_HOME，所以别名未启用时不会命中自身）。
     */
    public static String currentStockLauncher(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String self = ctx.getPackageName();
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);

            ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (def != null && def.activityInfo != null && !self.equals(def.activityInfo.packageName)) {
                return def.activityInfo.packageName;
            }
            List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
            String best = null;
            int bestPriority = Integer.MIN_VALUE;
            for (ResolveInfo ri : list) {
                if (ri == null || ri.activityInfo == null || self.equals(ri.activityInfo.packageName)) {
                    continue;
                }
                if (ri.priority > bestPriority) {
                    bestPriority = ri.priority;
                    best = ri.activityInfo.packageName;
                }
            }
            return best;
        } catch (Throwable th) {
            Log.i(TAG, "currentStockLauncher failed: " + th.getMessage());
            return null;
        }
    }

    // ==================== 设为 / 取消系统桌面 ====================

    /**
     * 后台执行「设为系统桌面」的核心逻辑（不含任何 UI 操作）：
     * 记录原桌面 → 启用本应用 HOME 别名 → 有 root 时禁用原桌面并把本应用设为默认桌面。
     *
     * @return true 表示已通过 root 系统服务自动完成
     */
    public static boolean applyAsDefault(Context ctx) {
        if (ctx == null) {
            return false;
        }
        // 先取原桌面（此时本应用 HOME 别名尚未启用，不会取到自己）
        String stock = currentStockLauncher(ctx);
        if (stock != null && !stock.isEmpty()) {
            Hawk.put(K_STOCK_LAUNCHER, stock);
        }
        // 启用 HOME 别名（无 root 时也能让 TVBox 出现在系统桌面弹窗里）
        RemoteConfig.applyLauncherComponent(ctx);
        if (!hasRoot()) {
            return false;
        }
        if (stock != null && !stock.isEmpty()) {
            exec("pm disable-user --user 0 " + stock);
        }
        exec("pm set-home-activity " + ctx.getPackageName());
        Log.i(TAG, "root launcher switch done, stock=" + stock);
        return true;
    }

    /**
     * UI 线程调用（设置页开关打开）：后台完成 root 替换；无 root 或未生效时唤起系统默认桌面弹窗。
     */
    public static void setAsDefaultLauncher(final Activity act) {
        if (act == null) {
            return;
        }
        final Context ctx = act.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean auto = applyAsDefault(ctx);
                post(act, new Runnable() {
                    @Override
                    public void run() {
                        if (auto) {
                            Toast.makeText(ctx, "已禁用原来的系统桌面并把 TVBox 设为默认桌面",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            // 无 root：唤起系统「选择默认桌面」弹窗（需选「始终」）
                            RemoteConfig.promptChooseLauncher(act);
                            Toast.makeText(ctx, "请在系统弹窗中选择 TVBox 并选「始终」",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "set-default-launcher").start();
    }

    /**
     * UI 线程调用（设置页开关关闭）：后台重新启用之前被禁用的原桌面并设回默认，再停用本应用别名。
     */
    public static void restoreStockLauncher(final Activity act) {
        if (act == null) {
            return;
        }
        final Context ctx = act.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String stock = Hawk.get(K_STOCK_LAUNCHER, "");
                boolean restored = false;
                if (stock != null && !stock.isEmpty() && hasRoot()) {
                    exec("pm enable --user 0 " + stock);
                    exec("pm set-home-activity " + stock);
                    restored = true;
                }
                Hawk.delete(K_STOCK_LAUNCHER);
                RemoteConfig.applyLauncherComponent(ctx);
                final boolean done = restored;
                post(act, new Runnable() {
                    @Override
                    public void run() {
                        if (done) {
                            Toast.makeText(ctx, "已恢复原来的系统桌面", Toast.LENGTH_LONG).show();
                        } else {
                            RemoteConfig.promptChooseLauncher(act);
                            Toast.makeText(ctx, "请在系统弹窗中选回原来的桌面", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "restore-launcher").start();
    }

    /** 回主线程（Activity 已销毁则跳过） */
    private static void post(final Activity act, final Runnable r) {
        try {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (act.isFinishing()) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 17 && act.isDestroyed()) {
                        return;
                    }
                    r.run();
                }
            });
        } catch (Throwable ignored) {
        }
    }
}
