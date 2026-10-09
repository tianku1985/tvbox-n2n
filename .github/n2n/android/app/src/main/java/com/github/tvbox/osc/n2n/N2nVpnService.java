package com.github.tvbox.osc.n2n;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * n2n VPN 服务：
 * 1. 建立 VpnService TUN 设备，配置好 IP 与路由；
 * 2. 把打包在 assets 里的 edge 二进制释放到 files 目录并赋予可执行权限；
 * 3. 启动 edge 进程，并把 TUN 的文件描述符号通过环境变量 N2N_TUN_FD 传给子进程
 *    （配合原生 tuntap_android.c 覆盖 tuntap_open 直接使用该 fd）。
 *
 * 说明：不能使用 ProcessBuilder.Redirect.from(FileDescriptor)——Android/OpenJDK
 * 的 Redirect 只有 from/to/appendTo(File) 重载，没有 FileDescriptor 版本。
 * 正确做法是用 ParcelFileDescriptor.getFd() 取 fd 号，再用
 * Os.fcntlInt(fd, F_SETFD, 0) 清除 FD_CLOEXEC 使子进程可继承（注意 Android 的
 * Os.dup/close 只有 FileDescriptor 版本，不能对 detachFd 出来的裸 int fd 直接
 * dup/close），最后把 fd 号经环境变量告知子进程（与 Xray/AeroVPN 一致）。
 */
public class N2nVpnService extends VpnService {

    private static final String TAG = "N2nVpnService";
    private static final String CHANNEL_ID = "n2n";   // 通知渠道 ID
    private static final int NOTIFY_ID = 0x2E2E;      // 前告常驻通知 ID
    private static final String BIN_DIR = "n2n";      // files 目录下的子目录
    private static final String BIN_NAME = "edge";    // edge 二进制文件名

    private static N2nVpnService instance;            // 供静态方法获取当前实例

    private Process edgeProcess;                       // edge 子进程
    private ParcelFileDescriptor vpnFd;                // VpnService 建立的 TUN 文件描述符
    private int tunFd = -1;                             // TUN fd 号（已清除 FD_CLOEXEC，可被子进程继承）
    private volatile boolean running;                  // 当前是否处于运行状态

    /** 是否正在运行（供设置页状态展示） */
    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    /** 启动 VPN 服务（API 26+ 使用 startForegroundService） */
    public static void start(Context ctx, N2nConfig cfg) {
        Intent it = new Intent(ctx, N2nVpnService.class);
        it.putExtra("cfg", cfg);                       // 把配置随 Intent 传给服务
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(it);
        } else {
            ctx.startService(it);
        }
    }

    /** 停止并清理 VPN 与 edge 进程 */
    public static void stopAll() {
        if (instance != null) {
            instance.stopVpn(true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;                               // 记录实例，供 stopAll/isRunning 使用
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Object cfg = intent.getSerializableExtra("cfg");
            if (cfg instanceof N2nConfig) {
                startVpn((N2nConfig) cfg);             // 解析出配置并建立 VPN
            }
        }
        return START_NOT_STICKY;                        // 被系统杀掉后不自动重启
    }

    /** 建立 VpnService、释放二进制并拉起 edge 进程 */
    private void startVpn(N2nConfig cfg) {
        if (running) {
            stopVpn(false);                            // 重复启动时先停掉旧会话
        }
        if (Build.VERSION.SDK_INT < 21) {
            Log.e(TAG, "VpnService requires API 21+");
            stopVpn(true);
            return;
        }

        File bin = prepareBinary();
        if (bin == null) {
            Log.e(TAG, "edge binary not found");
            stopVpn(true);
            return;
        }

        // 尽早进入前台，避免 startForegroundService 未及时 startForeground 导致 ANR
        startForeground(NOTIFY_ID, buildNotification(cfg));

        try {
            Builder b = new Builder();
            b.setSession("n2n-" + cfg.community);
            b.setMtu(cfg.mtu);
            int prefix = N2nConfig.prefixLengthOfMask(cfg.mask);
            b.addAddress(InetAddress.getByName(cfg.ip), prefix);
            b.addRoute(InetAddress.getByName(cfg.ip), prefix);
            List<String[]> routes = parseRoutes(cfg.extraRoute);
            for (String[] r : routes) {
                b.addRoute(InetAddress.getByName(r[0]), Integer.parseInt(r[1]));
            }
            vpnFd = b.establish();
        } catch (Exception e) {
            Log.e(TAG, "vpn establish failed", e);
            vpnFd = null;
        }
        if (vpnFd == null) {
            stopVpn(true);
            return;
        }

        // 把 TUN fd 传给子进程：getFd() 取得 fd 号（PFD 仍持有所有权，由 stopVpn 统一 close），
        // 再用 fcntl(F_SETFD, 0) 清除 FD_CLOEXEC，使 exec 后的 edge 子进程能继承该 fd；
        // 子进程通过环境变量 N2N_TUN_FD 得知 fd 号。注意 Android 的 Os.dup/close 只有
        // FileDescriptor 版本，不能对 detachFd 出来的裸 int fd 直接 dup/close（编译不过）。
        try {
            tunFd = vpnFd.getFd();
            Os.fcntlInt(vpnFd.getFileDescriptor(), OsConstants.F_SETFD, 0);
        } catch (Exception e) {
            Log.e(TAG, "tun fd setup failed", e);
            stopVpn(true);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(buildCommand(cfg, bin));
        pb.redirectErrorStream(true);
        try {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(getFilesDir() + File.separator + BIN_DIR, "edge.log")));
        } catch (Exception e) {
            Log.e(TAG, "log setup failed", e);
        }
        pb.environment().put("N2N_TUN_FD", String.valueOf(tunFd));
        try {
            edgeProcess = pb.start();
        } catch (IOException e) {
            Log.e(TAG, "edge start failed", e);
            edgeProcess = null;
            stopVpn(true);
            return;
        }

        running = true;
        trackProcess(edgeProcess);
    }

    /** 当 edge 进程意外退出时自动断开并清理 */
    private void trackProcess(final Process process) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int rc = process.waitFor();
                    Log.i(TAG, "edge exited rc=" + rc);
                    if (running && process == edgeProcess) {
                        stopVpn(true);
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }, "n2n-wait").start();
    }

    /** 组装 edge 命令行参数 */
    private String[] buildCommand(N2nConfig cfg, File bin) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-c");
        cmd.add(cfg.community);
        cmd.add("-k");
        cmd.add(cfg.key);
        cmd.add("-a");
        cmd.add(cfg.ip);
        cmd.add("-s");
        cmd.add(cfg.mask);
        cmd.add("-l");
        cmd.add(cfg.supernode);
        cmd.add("-m");
        cmd.add(cfg.mac);
        cmd.add("-M");
        cmd.add(String.valueOf(cfg.mtu));
        cmd.add("-d");
        cmd.add("n2n0");
        cmd.add("-f");
        return cmd.toArray(new String[0]);
    }

    /** 解析额外路由："a.b.c.d/前缀" 或 "a.b.c.d"(视为 /32)，支持空格/逗号分隔 */
    private List<String[]> parseRoutes(String extra) {
        List<String[]> out = new ArrayList<>();
        if (extra == null) {
            return out;
        }
        for (String token : extra.split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split("[/ ]+");
            if (parts.length == 1) {
                out.add(new String[]{parts[0], "32"});
            } else if (parts.length == 2) {
                out.add(new String[]{parts[0], parts[1]});
            }
        }
        return out;
    }

    /** 把匹配当前 CPU 架构的 edge 二进制从 assets 释放到 files/n2n/ 并 chmod 可执行 */
    private File prepareBinary() {
        File dir = new File(getFilesDir(), BIN_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        String assetName = pickAsset();
        if (assetName == null) {
            return null;
        }
        File out = new File(dir, BIN_NAME);
        InputStream is = null;
        FileOutputStream fos = null;
        try {
            is = getAssets().open("n2n/" + assetName);
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            fos.flush();
        } catch (IOException e) {
            Log.e(TAG, "extract binary failed", e);
            return null;
        } finally {
            closeQuietly(is);
            closeQuietly(fos);
        }
        if (!out.setExecutable(true, false) || !out.setReadable(true, false)) {
            Log.e(TAG, "chmod failed");
            return null;
        }
        return out;
    }

    /** 返回当前设备架构对应的 assets 内二进制文件名；无匹配则返回 null */
    private String pickAsset() {
        if (Build.VERSION.SDK_INT < 21) {
            // 极老设备：尝试兼容的 32 位 x86/arm 取舍（这类设备基本不考虑 64 位）
            try {
                getAssets().open("n2n/edge-armeabi-v7a").close();
                return "edge-armeabi-v7a";
            } catch (IOException e) {
                return null;
            }
        }
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String candidate;
            if (abi.equals("arm64-v8a")) {
                candidate = "edge-arm64-v8a";
            } else if (abi.equals("armeabi-v7a")) {
                candidate = "edge-armeabi-v7a";
            } else {
                continue;                            // 本仓库只打包了这两个 ABI
            }
            try {
                getAssets().open("n2n/" + candidate).close();
                return candidate;
            } catch (IOException e) {
                // 该架构二进制不存在则继续尝试下一个
            }
        }
        return null;
    }

    /** 构造常驻前台通知（点击后回到主界面） */
    private Notification buildNotification(N2nConfig cfg) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "n2n", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(channel);
        }
        // 两仓均无 MainActivity，主界面类为 HomeActivity
        Intent content = new Intent(this, com.github.tvbox.osc.ui.activity.HomeActivity.class);
        content.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, content, flags);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            nb = new Notification.Builder(this, CHANNEL_ID);
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(android.R.mipmap.sym_def_app_icon)
                .setContentTitle("n2n")
                .setContentText("community: " + cfg.community + " ip: " + cfg.ip)
                .setOngoing(true)
                .setContentIntent(pi);
        return nb.build();
    }

    /** 停止并清理：结束 edge 进程、关闭 TUN；selfStop 为 true 时同时结束服务自身 */
    public void stopVpn(boolean selfStop) {
        running = false;
        if (edgeProcess != null) {
            edgeProcess.destroyForcibly();           // 强杀 edge 子进程
            edgeProcess = null;
        }
        if (vpnFd != null) {
            try {
                vpnFd.close();                       // 关闭 TUN，内核自动回收虚拟网卡/路由
            } catch (IOException e) {
                // ignore
            }
            vpnFd = null;
        }
        if (tunFd >= 0) {
            tunFd = -1;                          // fd 号仅供环境变量使用，TUN fd 由上方 vpnFd.close() 统一关闭
        }
        if (selfStop) {
            stopForeground(true);
            stopSelf();
        }
    }

    /** 用户在系统设置中撤销 VPN 授权时回调，立即断开 */
    @Override
    public void onRevoke() {
        stopVpn(true);
    }

    @Override
    public void onDestroy() {
        stopVpn(false);
        instance = null;                             // 清除单例引用
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;                                 // 本服务不需绑定 IPC
    }

    /** 静默关闭可关闭对象，避免 finally 中二次抛出异常 */
    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}