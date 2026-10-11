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
    private ParcelFileDescriptor dupFd;                 // fcntl 不可用时 Os.dup 出的可继承副本，stopVpn/换址关闭
    private volatile boolean running;                  // 当前是否处于运行状态
    private volatile long startedAt;                   // edge 启动时间（用于区分"连接中/已连接"）

    // 本轮连接实际使用的配置（自动获取 IP 模式下与持久化的 cfg 不同；NAK 换址时变化）
    private N2nConfig activeCfg;
    private File edgeBin;                               // 已释放就绪的 edge 可执行文件（换址复用）
    private String attemptIp;                          // 本轮 edge 实际绑定的虚拟 IP
    private String attemptMac;                         // 本轮 edge 实际使用的 MAC
    private int attemptSeq;                            // 自动选址尝试序号（0 = 首次）
    private final Object rewireLock = new Object();    // NAK 自动换址串行化
    private static final int MAX_ATTEMPTS = 8;         // 自动选址最多尝试次数

    // 最近一次失败原因。用【静态】字段：服务在早期失败会立即 stopSelf 清空 instance，
    // 若错误存在实例字段里会随之丢失，页面又退化成"未连接"而无从排查。
    private static volatile String sLastError = "";
    // edge 是否已收到超级节点的 REGISTER_SUPER_ACK（日志标记 [OK] edge <<<）。
    // 不能只凭"edge 存活 N 秒"判定成功：被 NAK 拒绝时 edge 会一直重试且永不退出。
    private static volatile boolean sRegistered = false;
    // edge 侧最新告警（默认日志级别可见的关键英文标记 → 中文提示），供状态行展示
    private static volatile String sEdgeWarn = "";

    /** 是否正在运行（供设置页状态展示） */
    public static boolean isRunning() {
        return instance != null && instance.running;
    }

    /** 本轮实际分配到的虚拟 IP（自动模式下非用户填写值；未运行时返回 null） */
    public static String currentIp() {
        N2nVpnService s = instance;
        return (s != null && s.running) ? s.attemptIp : null;
    }

    /** 连接状态文字：未连接 / 已断开（原因） / 连接中（原因） / 已连接 */
    public static String stateText() {
        N2nVpnService s = instance;
        if (s == null || !s.running) {
            return (sLastError == null || sLastError.isEmpty())
                    ? "未连接" : "已断开（" + sLastError + "）";
        }
        if (sRegistered) {
            return "已连接";
        }
        // edge 活着但一直注册不上时，把超级节点的拒绝原因直接显示在状态行
        if (!sEdgeWarn.isEmpty() && System.currentTimeMillis() - s.startedAt > 6000) {
            return "连接中（" + sEdgeWarn + "）";
        }
        return "连接中…";
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
        installCrashDump();
    }

    /**
     * 进程崩溃时把完整异常栈写进 edge.log。
     * 之前日志停在「TUN 建立成功」后应用就闪退，Java 层 catch 只接 Exception，
     * Error/其它 Throwable 直接穿透导致闪退且毫无痕迹——有了这个转储，
     * 下次复现直接在设置页「运行日志」里就能看到崩溃栈。
     */
    private void installCrashDump() {
        final Thread.UncaughtExceptionHandler prev =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    diag("!!!! 未捕获崩溃 thread=" + t.getName() + " !!!!");
                    diag(sw.toString());
                } catch (Throwable ignored) {
                    // 转储失败也不能影响原崩溃流程
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            Object cfg = intent.getSerializableExtra("cfg");
            if (cfg instanceof N2nConfig) {
                try {
                    startVpn((N2nConfig) cfg);         // 解析出配置并建立 VPN
                } catch (Throwable t) {
                    // 兜底：任何 Throwable 都先落日志再断开，避免“闪退无痕迹”
                    Log.e(TAG, "startVpn crashed", t);
                    sLastError = "连接异常: " + t;
                    diag("startVpn 未捕获异常: " + Log.getStackTraceString(t));
                    stopVpn(true);
                }
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
        sRegistered = false;
        sEdgeWarn = "";
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
        edgeBin = bin;
        diag("edge 二进制就绪: " + bin.getAbsolutePath() + " (" + bin.length() + " bytes)");

        activeCfg = cfg;
        attemptSeq = 0;
        // 自动获取：首次即在网段内随机选址；手动：直接使用配置 IP
        attemptIp = cfg.autoIp ? N2nConfig.randomIpInSubnet(cfg.ip, cfg.mask) : cfg.ip;
        attemptMac = cfg.mac;
        if (cfg.autoIp) {
            diag("IP 获取方式: 自动，首次选址 " + attemptIp);
        }

        // 尽早进入前台，避免 startForegroundService 未及时 startForeground 导致 ANR
        startForeground(NOTIFY_ID, buildNotification(cfg));

        if (!bringUpEdge()) {
            return;                                     // 失败原因已写入日志/状态
        }
    }

    /**
     * 用当前 attemptIp/attemptMac 建立 TUN 并拉起 edge。首次连接与 NAK 自动换址共用。
     * 成功返回 true；失败已调用 fail()/stopVpn()，返回 false。
     */
    private boolean bringUpEdge() {
        N2nConfig cfg = activeCfg;
        // 换址重连前先拆旧链路（首次进来时没有旧资源，方法内部均做 null 判断）
        teardownEdge();

        try {
            Builder b = new Builder();
            b.setSession("n2n-" + cfg.community);
            b.setMtu(cfg.mtu);
            int prefix = N2nConfig.prefixLengthOfMask(cfg.mask);
            diag("配置 TUN: address=" + attemptIp + "/" + prefix + " mtu=" + cfg.mtu
                    + (attemptSeq > 0 ? "（第 " + (attemptSeq + 1) + " 次尝试）" : ""));
            b.addAddress(InetAddress.getByName(attemptIp), prefix);
            // Android addRoute() 要求路由目标的主机位必须清零（必须是网络地址），
            // 否则抛 IllegalArgumentException("Bad address")——如 10.0.0.233/24
            // 会被拒，必须转成 10.0.0.0/24 再添加路由。
            InetAddress netAddr = maskToNetwork(attemptIp, prefix);
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
        } catch (Throwable e) {
            Log.e(TAG, "vpn establish failed", e);
            vpnFd = null;
            fail("VPN 接口建立异常: " + e);
            return false;
        }
        if (vpnFd == null) {
            fail("VPN 接口建立失败（establish 返回 null，可能被系统拒绝或参数非法）");
            return false;
        }
        diag("TUN 建立成功");

        // 把 TUN fd 传给子进程，子进程通过环境变量 N2N_TUN_FD 得知 fd 号。
        // 关键是让 fd 能被 exec 后的 edge 继承（即不能带 FD_CLOEXEC）。
        // 注意 Android 的 Os.dup/close 只有 FileDescriptor 版本，没有 int 重载。
        // 此处必须 catch Throwable：NoSuchMethodError 属于 Error，旧代码只 catch
        // Exception，导致部分 ROM（Os 类无 fcntlInt 方法）在「TUN 建立成功」后直接闪退。
        try {
            tunFd = vpnFd.getFd();
            diag("取得 TUN fd=" + tunFd);
            tunFd = makeFdInheritable();
        } catch (Throwable t) {
            Log.e(TAG, "tun fd setup failed", t);
            fail("TUN fd 设置失败: " + t);
            return false;
        }

        String[] command;
        try {
            command = buildCommand(cfg, edgeBin, attemptIp, attemptMac);
        } catch (Throwable t) {
            Log.e(TAG, "build command failed", t);
            fail("拼装 edge 命令失败: " + t);
            return false;
        }
        diag("启动命令: " + joinArgs(command));
        ProcessBuilder pb;
        try {
            pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // edge 的 stdout/stderr 追加到同一日志，接在 Java 阶段诊断之后
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile()));
            pb.environment().put("N2N_TUN_FD", String.valueOf(tunFd));
        } catch (Throwable t) {
            Log.e(TAG, "process builder setup failed", t);
            fail("edge 进程准备失败: " + t);
            return false;
        }
        Process proc;
        try {
            proc = pb.start();
        } catch (Throwable e) {
            // 注意不只是 IOException：ProcessBuilder.start 还可能抛 SecurityException 等
            // RuntimeException（不捕获会直接闪退且日志停在「启动命令」）
            Log.e(TAG, "edge start failed", e);
            edgeProcess = null;
            fail("edge 启动失败: " + e);
            return false;
        }

        edgeProcess = proc;
        startedAt = System.currentTimeMillis();
        running = true;
        diag("edge 进程已拉起，等待连接 supernode " + cfg.supernode);
        trackProcess(proc);
        watchEdgeLog(proc);
        return true;
    }

    /** 拆除当前 edge 进程与 TUN（换址重连/停止时调用），各项资源均允许为空 */
    private void teardownEdge() {
        Process p = edgeProcess;
        if (p != null) {
            edgeProcess = null;
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
            }
        }
        if (dupFd != null) {
            try {
                dupFd.close();
            } catch (IOException ignored) {
            }
            dupFd = null;
        }
        if (vpnFd != null) {
            try {
                vpnFd.close();
            } catch (IOException ignored) {
            }
            vpnFd = null;
        }
        tunFd = -1;
    }

    /** 记录失败原因（同时写状态字段与日志），随后由调用方执行 stopVpn(true) */
    private void fail(String reason) {
        sLastError = reason;
        diag("失败: " + reason);
        stopVpn(true);
    }

    /**
     * 返回可被 exec 后子进程继承的 TUN fd 号（需无 FD_CLOEXEC）。三级策略：
     * 1. fcntl(F_SETFD, 0) 清除 FD_CLOEXEC —— 部分 ROM 的 android.system.Os 没有
     *    fcntlInt 方法，调用直接抛 NoSuchMethodError（属 Error）；
     * 2. 回退 ParcelFileDescriptor.dup(FileDescriptor) 复制一份 —— POSIX 规定 dup 出的
     *    新 fd 不带 FD_CLOEXEC，副本存入 dupFd 由 stopVpn 统一关闭，
     *    原 vpnFd 仍保持 TUN 打开；
     * 3. 都失败则直接用原 fd（若系统本就没设 CLOEXEC 依然可用），并在日志标注风险。
     */
    private int makeFdInheritable() throws Exception {
        java.io.FileDescriptor raw = vpnFd.getFileDescriptor();
        try {
            Os.fcntlInt(raw, OsConstants.F_SETFD, 0);
            diag("已通过 fcntl 清除 FD_CLOEXEC，子进程可继承");
            return vpnFd.getFd();
        } catch (Throwable t) {
            diag("fcntl 清除 FD_CLOEXEC 失败: " + t + "，改用 ParcelFileDescriptor.dup 复制");
        }
        try {
            // PFD.dup(FileDescriptor) 内部走 Os.dup 复制原 fd，POSIX 规定 dup 出的新 fd
            // 不带 FD_CLOEXEC（注意 fromFd 的参数是 int 而非 FileDescriptor，编译不过）
            dupFd = ParcelFileDescriptor.dup(raw);
            int fd = dupFd.getFd();
            diag("dup 得到可继承 TUN fd=" + fd + "，子进程将使用该 fd");
            return fd;
        } catch (Throwable t) {
            diag("dup TUN fd 失败: " + t + "，回退原 fd（若系统未设 CLOEXEC 仍可继承）");
            return vpnFd.getFd();
        }
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
                        diag("trackProcess: edge 进程退出 rc=" + rc
                                + "（running=" + running + "），触发 stopVpn");
                        sLastError = "edge 已退出(rc=" + rc + ")";
                        stopVpn(true);
                    } else {
                        diag("trackProcess: edge 进程退出 rc=" + rc
                                + "，但已非当前进程（换址重建中），忽略");
                    }
                } catch (InterruptedException e) {
                    // ignore
                }
            }
        }, "n2n-wait").start();
    }

    /**
     * 轮询 edge.log 识别真实注册状态。edge 被超级节点以 NAK 拒绝时不会退出，
     * 而是每 4 秒重试一次，旧逻辑仅凭"进程存活 N 秒"就显示"已连接"，会一直误报。
     * 匹配默认日志级别（TRACE_NORMAL/ERROR）即可见的标记：
     *   成功：[OK] edge <<< ================ >>> supernode（首次收到 REGISTER_SUPER_ACK）
     *   拒绝：authentication error, MAC or IP address already in use ...
     *   无响应：supernode not responding, now trying
     * 自动获取 IP 模式下，连续 2 条 NAK 即自动换址（必要时换 MAC）重建链路。
     */
    private void watchEdgeLog(final Process process) {
        // 每个 watcher 与一次 bringUpEdge 一一对应，只统计本 attempt 启动后的新增日志，
        // 避免把上一轮 attempt 的 NAK 行计入本轮。
        final long baseOffset = logFile().length();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int nakCount = 0;
                while (running && process.isAlive()) {
                    try {
                        Thread.sleep(2000);
                        File f = logFile();
                        if (!f.exists()) {
                            continue;
                        }
                        long len = f.length();
                        if (len <= baseOffset) {
                            continue;
                        }
                        long avail = len - baseOffset;
                        int take = (int) Math.min(avail, 64 * 1024);
                        byte[] buf = new byte[take];
                        java.io.FileInputStream in = new java.io.FileInputStream(f);
                        long skipped = 0;
                        long wantSkip = len - take;
                        while (wantSkip - skipped > 0) {
                            long s = in.skip(wantSkip - skipped);
                            if (s <= 0) break;
                            skipped += s;
                        }
                        int off = 0;
                        while (off < take) {
                            int r = in.read(buf, off, take - off);
                            if (r < 0) break;
                            off += r;
                        }
                        in.close();
                        String tail = new String(buf, 0, off, "UTF-8");
                        if (tail.contains("[OK] edge <<<")) {
                            if (!sRegistered) {
                                diag("watchEdgeLog 检测到 REGISTER_SUPER_ACK（[OK] edge <<<），标记已连接");
                            }
                            sRegistered = true;
                            sEdgeWarn = "";
                            continue;
                        }
                        if (sRegistered) {
                            continue;
                        }
                        if (tail.contains("MAC or IP address already in use")) {
                            nakCount += countOccur(tail, "MAC or IP address already in use");
                            if (activeCfg != null && activeCfg.autoIp) {
                                // 自动模式：约 8 秒内两条 NAK 即换址，避免在死地址上空等
                                if (nakCount >= 2) {
                                    nakCount = 0;
                                    if (process == edgeProcess) {
                                        rewireAfterNak();
                                        return;                // 本 watcher 随旧进程结束
                                    }
                                }
                            } else if (sEdgeWarn.isEmpty()
                                    || sEdgeWarn.startsWith("自动选址")) {
                                sEdgeWarn = "IP/MAC被占用，请更换虚拟IP";
                            }
                        } else if (sEdgeWarn.isEmpty()
                                && tail.contains("supernode not responding")) {
                            sEdgeWarn = "超级节点无响应";
                        }
                    } catch (Throwable ignored) {
                        // 日志读取异常不影响连接主流程
                    }
                }
            }
        }, "n2n-edge-log");
        t.setDaemon(true);
        t.start();
    }

    private static int countOccur(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /**
     * NAK 自动换址：杀掉当前 edge、关闭 TUN，换一个同网段随机 IP（第 4 次起连 MAC
     * 一起换，规避 MAC 维度的残留/冲突），再用新身份重建链路。依据服务端 3.1.1
     * update_edge()：simple_id 社区中全新 MAC + 空闲 IP 必然注册成功。
     */
    private void rewireAfterNak() {
        synchronized (rewireLock) {
            if (!running || sRegistered || edgeProcess == null
                    || activeCfg == null || !activeCfg.autoIp) {
                return;
            }
            if (attemptSeq + 1 >= MAX_ATTEMPTS) {
                fail("自动获取 IP 失败：连续 " + MAX_ATTEMPTS + " 个地址均被超级节点拒绝，"
                        + "请检查社区名/密钥是否正确，或改用手动指定 IP");
                return;
            }
            attemptSeq++;
            if (attemptSeq >= 3) {
                attemptMac = N2nConfig.randomMac();
                diag("连续多次被拒，本轮连 MAC 一并更换为 " + attemptMac);
            }
            String nextIp = attemptIp;
            for (int i = 0; i < 10 && nextIp.equals(attemptIp); i++) {
                nextIp = N2nConfig.randomIpInSubnet(activeCfg.ip, activeCfg.mask);
            }
            diag("虚拟 IP 被超级节点拒绝（MAC or IP already in use），自动更换 "
                    + attemptIp + " -> " + nextIp + "（第 " + (attemptSeq + 1) + "/"
                    + MAX_ATTEMPTS + " 次尝试）");
            attemptIp = nextIp;
            sRegistered = false;
            sEdgeWarn = "自动选址中(" + (attemptSeq + 1) + "/" + MAX_ATTEMPTS + ")";
            if (bringUpEdge()) {
                // 换址成功后刷新常驻通知中的 IP 显示
                startForeground(NOTIFY_ID, buildNotification(activeCfg));
            }
        }
    }

    /** 组装 edge 命令行参数；密钥/MAC 为空时不传对应参数（edge 不接受空值参数） */
    private String[] buildCommand(N2nConfig cfg, File bin, String ip, String mac) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        cmd.add("-c");
        cmd.add(cfg.community);
        if (cfg.key != null && !cfg.key.isEmpty()) {
            cmd.add("-k");
            cmd.add(cfg.key);
        }
        // 掩码必须用 -a <ip>/<prefix> 的 CIDR 形式传入：n2n 3.1.1 的 setOption 没有
        // -s 分支（getopt 能识别 s: 但 switch 里无 case 's'），传 -s 会走 default
        // 返回 2 → help(2) 打印用法后直接 exit(0)，表现为「edge 进程退出 rc=0」
        // 且从未真正启动（日志止于「启动命令」，其后只有用法文本）。
        cmd.add("-a");
        cmd.add(ip + "/" + N2nConfig.prefixLengthOfMask(cfg.mask));
        cmd.add("-l");
        cmd.add(cfg.supernode);
        if (mac != null && !mac.isEmpty()) {
            cmd.add("-m");
            cmd.add(mac);
        }
        cmd.add("-M");
        cmd.add(String.valueOf(cfg.mtu));
        cmd.add("-d");
        cmd.add("n2n0");
        cmd.add("-f");
        // -E 必须显式传入：n2n 3.1.1 默认 drop_multicast=1（edge_utils.c
        // edge_init_conf_defaults），在 edge_read_from_tap 与 RX 路径上把所有
        // 广播/组播以太网帧（含 ARP 请求）直接丢弃。Android TUN 是三层设备，
        // 内核不发 ARP，本项目的 tuntap_android.c 靠广播帧模拟 ARP——不开 -E
        // 时 ARP 双向皆死（Tx「dropping Tx multicast」/Rx「dropping RX
        // multicast」），表现为注册成功但 ping 不通任何对端。
        cmd.add("-E");
        // n2n 3.1.1 启动时若系统存在 n2n/nobody 用户，会默认以其 uid/gid 作为降权目标
        // （Android 上 getpwnam("nobody") 命中，uid/gid=9999），随后 setgid(9999) 因无
        // CAP_SETGID 必然失败 → "unable to drop privileges" → exit(1)，edge 秒退(rc=1)。
        // 显式传 -u 0 -g 0 使 userid/groupid 归零，整个降权分支被跳过，edge 继续以
        // 应用自身 uid 运行；此前日志里 setuid(0) 的 "unable to become root" ERROR
        // 只是探测失败，无害可忽略。
        cmd.add("-u");
        cmd.add("0");
        cmd.add("-g");
        cmd.add("0");
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
                .setContentText("community: " + cfg.community + " ip: " + attemptIp)
                .setOngoing(true)
                .setContentIntent(pi);
        return nb.build();
    }

    /** 停止并清理：结束 edge 进程、关闭 TUN；selfStop 为 true 时同时结束服务自身 */
    public void stopVpn(boolean selfStop) {
        if (!running && !selfStop) {
            return;
        }
        diag("stopVpn 被调用（selfStop=" + selfStop + "），running=" + running
                + "，sRegistered=" + sRegistered);
        running = false;
        teardownEdge();
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
        diag("onDestroy: 服务被销毁，清理资源");
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