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
    private volatile long startedAt;                   // edge 启动时间（用于区分"连接中/已连接"）

    // 最近一次失败原因。用【静态】字段：服务在早期失败会立即 stopSelf 清空 instance，
    // 若错误存在实例字段里会随之丢失，页面又退化成"未连接"而无从排查。
    private static volatile String sLastError = "";

    /** 是否正在运行（供设置页状态展示） */
    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    /** 连接状态文字：未连接 / 已断开（原因） / 连接中… / 已连接 */
    public static String stateText() {
        N2nVpnService s = instance;
        if (s == null || !s.running) {
            return (sLastError == null || sLastError.isEmpty())
                    ? "未连接" : "已断开（" + sLastError + "）";
        }
        Process p = s.edgeProcess;
        if (p == null || !p.isAlive()) {
            return "连接中…";
        }
        // edge 存活超过 3 秒视为连接成功（静态 IP 已配置到 TUN 上）
        return System.currentTimeMillis() - s.startedAt < 3000 ? "连接中…" : "已连接";
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
        sLastError = "";                               // 用户主动断开，清除旧的失败原因
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

    /** 日志文件（Java 阶段诊断 + edge 输出共用同一文件） */
    private File logFile() {
        File dir = new File(getFilesDir(), BIN_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "edge.log");
    }

    /** 追加一行带时间戳的 Java 层阶段诊断（即使 edge 没启动，失败原因也有据可查） */
    private void diag(String msg) {
        java.io.FileWriter w = null;
        try {
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date());
            w = new java.io.FileWriter(logFile(), true);
            w.write("[" + ts + "] " + msg + "\n");
        } catch (IOException e) {
            Log.e(TAG, "diag write failed: " + msg, e);
        } finally {
            closeQuietly(w);
        }
    }

    /** 建立 VpnService、释放二进制并拉起 edge 进程 */
    private void startVpn(N2nConfig cfg) {
        if (running) {
            stopVpn(false);                            // 重复启动时先停掉旧会话
        }

        // 新一轮连接：清空旧日志与错误，立即开始阶段记录（早于任何可能的 return）
        sLastError = "";
        try {
            new FileOutputStream(logFile()).close();
        } catch (IOException e) {
            Log.e(TAG, "reset log failed", e);
        }
        diag("==== 开始建立 n2n 连接 ====");
        diag("设备 ABI=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
                + " Android API=" + Build.VERSION.SDK_INT);

        if (Build.VERSION.SDK_INT < 21) {
            fail("VpnService 需要 Android 5.0 (API 21) 以上");
            return;
        }

        File bin = prepareBinary();
        if (bin == null) {
            // 具体原因已由 prepareBinary/pickAsset 写入日志与 sLastError
            stopVpn(true);
            return;
        }
        diag("edge 二进制就绪: " + bin.getAbsolutePath() + " (" + bin.length() + " bytes)");

        // 尽早进入前台，避免 startForegroundService 未及时 startForeground 导致 ANR
        startForeground(NOTIFY_ID, buildNotification(cfg));

        try {
            Builder b = new Builder();
            b.setSession("n2n-" + cfg.community);
            b.setMtu(cfg.mtu);
            int prefix = N2nConfig.prefixLengthOfMask(cfg.mask);
            diag("配置 TUN: address=" + cfg.ip + "/" + prefix + " mtu=" + cfg.mtu);
            b.addAddress(InetAddress.getByName(cfg.ip), prefix);
            // Android addRoute() 要求路由目标的主机位必须清零（必须是网络地址），
            // 否则抛 IllegalArgumentException("Bad address")——如 10.0.0.233/24
            // 会被拒，必须转成 10.0.0.0/24 再添加路由。
            InetAddress netAddr = maskToNetwork(cfg.ip, prefix);
            diag("路由: " + netAddr.getHostAddress() + "/" + prefix);
            b.addRoute(netAddr, prefix);
            List<String[]> routes = parseRoutes(cfg.extraRoute);
            for (String[] r : routes) {
                int p = Integer.parseInt(r[1]);
                InetAddress re = maskToNetwork(r[0], p);
                diag("额外路由: " + re.getHostAddress() + "/" + p);
                b.addRoute(re, p);
            }
            vpnFd = b.establish();
        } catch (Exception e) {
            Log.e(TAG, "vpn establish failed", e);
            vpnFd = null;
            fail("VPN 接口建立异常: " + e.getMessage());
            return;
        }
        if (vpnFd == null) {
            fail("VPN 接口建立失败（establish 返回 null，可能被系统拒绝或参数非法）");
            return;
        }
        diag("TUN 建立成功");

        // 把 TUN fd 传给子进程：getFd() 取得 fd 号（PFD 仍持有所有权，由 stopVpn 统一 close），
        // 再用 fcntl(F_SETFD, 0) 清除 FD_CLOEXEC，使 exec 后的 edge 子进程能继承该 fd；
        // 子进程通过环境变量 N2N_TUN_FD 得知 fd 号。注意 Android 的 Os.dup/close 只有
        // FileDescriptor 版本，不能对 detachFd 出来的裸 int fd 直接 dup/close（编译不过）。
        try {
            tunFd = vpnFd.getFd();
            Os.fcntlInt(vpnFd.getFileDescriptor(), OsConstants.F_SETFD, 0);
        } catch (Exception e) {
            Log.e(TAG, "tun fd setup failed", e);
            fail("TUN fd 设置失败: " + e.getMessage());
            return;
        }
        diag("TUN fd=" + tunFd + " 已清除 FD_CLOEXEC，可被子进程继承");

        String[] command = buildCommand(cfg, bin);
        diag("启动命令: " + joinArgs(command));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            // edge 的 stdout/stderr 追加到同一日志，接在 Java 阶段诊断之后
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()));
        } catch (Exception e) {
            Log.e(TAG, "log setup failed", e);
            diag("警告: edge 输出重定向失败: " + e.getMessage());
        }
        pb.environment().put("N2N_TUN_FD", String.valueOf(tunFd));
        try {
            edgeProcess = pb.start();
        } catch (IOException e) {
            Log.e(TAG, "edge start failed", e);
            edgeProcess = null;
            fail("edge 启动失败: " + e.getMessage());
            return;
        }

        startedAt = System.currentTimeMillis();
        running = true;
        diag("edge 进程已拉起，等待连接 supernode " + cfg.supernode);
        trackProcess(edgeProcess);
    }

    /** 记录失败原因（同时写状态字段与日志），随后由调用方执行 stopVpn(true) */
    private void fail(String reason) {
        sLastError = reason;
        diag("失败: " + reason);
        stopVpn(true);
    }

    /** 拼接命令行参数（仅用于日志展示） */
    private static String joinArgs(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
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
                        sLastError = "edge 已退出(rc=" + rc + ")";
                        diag("edge 进程退出，返回码 rc=" + rc + "（详见上方 edge 输出）");
                        stopVpn(true);
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }, "n2n-wait").start();
    }

    /** 组装 edge 命令行参数；密钥/MAC 为空时不传对应参数（edge 不接受空值参数） */
    private String[] buildCommand(N2nConfig cfg, File bin) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-c");
        cmd.add(cfg.community);
        if (cfg.key != null && !cfg.key.isEmpty()) {
            cmd.add("-k");
            cmd.add(cfg.key);
        }
        cmd.add("-a");
        cmd.add(cfg.ip);
        cmd.add("-s");
        cmd.add(cfg.mask);
        cmd.add("-l");
        cmd.add(cfg.supernode);
        if (cfg.mac != null && !cfg.mac.isEmpty()) {
            cmd.add("-m");
            cmd.add(cfg.mac);
        }
        cmd.add("-M");
        cmd.add(String.valueOf(cfg.mtu));
        cmd.add("-d");
        cmd.add("n2n0");
        cmd.add("-f");
        return cmd.toArray(new String[0]);
    }

    /** 读取 edge 运行日志尾部（最多 maxChars 字符），供页面「运行日志」展示 */
    public static String logTail(Context ctx, int maxChars) {
        File f = new File(ctx.getFilesDir() + File.separator + BIN_DIR, "edge.log");
        if (!f.exists() || f.length() == 0) {
            return "（暂无日志，请先连接一次）";
        }
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long skip = Math.max(0, len - maxChars);
            byte[] buf = new byte[(int) (len - skip)];
            raf.seek(skip);
            raf.readFully(buf);
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "（日志读取失败: " + e.getMessage() + "）";
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /** 按前缀长度清零主机位，得到网络地址（10.0.0.233/24 → 10.0.0.0/24） */
    private static InetAddress maskToNetwork(String ip, int prefix)
            throws java.net.UnknownHostException {
        byte[] b = InetAddress.getByName(ip).getAddress();
        for (int i = prefix / 8; i < b.length; i++) {
            if (i == prefix / 8 && prefix % 8 != 0) {
                b[i] &= (byte) (0xFF << (8 - prefix % 8));
            } else {
                b[i] = 0;
            }
        }
        return InetAddress.getByAddress(b);
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

    /**
     * 定位可执行 edge：
     * Android 10+(API 29) 优先用安装时解压到 nativeLibraryDir 的 liblegedge.so
     * （W^X 限制下 filesDir 二进制无法执行，该目录由系统解压、只读且可执行）；
     * 不存在或低版本时，再从 assets 释放到 files/n2n/edge。
     */
    private File prepareBinary() {
        if (Build.VERSION.SDK_INT >= 29) {
            File nl = new File(getApplicationInfo().nativeLibraryDir, "liblegedge.so");
            if (nl.exists()) {
                diag("使用 nativeLibraryDir 内 edge: " + nl.getAbsolutePath()
                        + " (" + nl.length() + " bytes)");
                return nl;
            }
            diag("nativeLibraryDir 未找到 liblegedge.so，回退从 assets 释放");
        }
        return extractBinary();
    }

    /** 把匹配当前 CPU 架构的 edge 二进制从 assets 释放到 files/n2n/ 并 chmod 可执行 */
    private File extractBinary() {
        File dir = new File(getFilesDir(), BIN_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            sLastError = "无法创建工作目录 " + dir.getAbsolutePath();
            diag("失败: mkdirs " + dir.getAbsolutePath());
            return null;
        }
        String assetName = pickAsset();
        if (assetName == null) {
            sLastError = "没有匹配当前架构的 edge（当前 ABI="
                    + java.util.Arrays.toString(Build.SUPPORTED_ABIS) + "）";
            diag("失败: assets/n2n 内无匹配二进制，设备 ABI="
                    + java.util.Arrays.toString(Build.SUPPORTED_ABIS));
            return null;
        }
        diag("匹配到 assets/n2n/" + assetName);
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
            sLastError = "释放 edge 失败: " + e.getMessage();
            diag("失败: 释放二进制异常: " + e);
            return null;
        } finally {
            closeQuietly(is);
            closeQuietly(fos);
        }
        if (!out.setExecutable(true, false) || !out.setReadable(true, false)) {
            Log.e(TAG, "chmod failed");
            sLastError = "edge 赋可执行权限失败";
            diag("失败: chmod +x " + out.getAbsolutePath());
            return null;
        }
        return out;
    }

    /** 返回当前设备架构对应的 assets 内二进制文件名；无匹配则返回 null */
    private String pickAsset() {
        String[] abis = Build.SUPPORTED_ABIS;
        for (String abi : abis) {
            String candidate;
            if (abi.equals("arm64-v8a")) {
                candidate = "edge-arm64-v8a";
            } else if (abi.equals("armeabi-v7a")) {
                candidate = "edge-armeabi-v7a";
            } else if (abi.equals("x86_64")) {
                candidate = "edge-x86_64";
            } else if (abi.equals("x86")) {
                candidate = "edge-x86";
            } else {
                continue;                            // 其他 ABI 未提供
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