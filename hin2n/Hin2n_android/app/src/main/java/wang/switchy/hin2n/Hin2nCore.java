package wang.switchy.hin2n;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;

import wang.switchy.hin2n.model.N2NSettingInfo;
import wang.switchy.hin2n.service.N2NService;
import wang.switchy.hin2n.storage.db.base.DaoMaster;
import wang.switchy.hin2n.storage.db.base.DaoSession;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

/**
 * hin2n 模块化后的核心入口（替代原独立 App 的 Hin2nApplication）。
 * <p>
 * 由宿主 App（TVBox）在 Application.onCreate 中调用 {@link #init(Context)} 完成：
 * 1. 逐个加载 n2n 原生库（单个失败不影响启动）；
 * 2. 打开 greenDAO 配置数据库；
 * 3. 创建前台服务通知渠道；
 * 4. 安装 crash.log 未捕获异常落盘（旧 TV 盒子抓 logcat 困难时定位问题）。
 * <p>
 * 配置存储与原 hin2n 完全一致：
 * - greenDAO 数据库 N2N-db（表 N2NSettingList）；
 * - SharedPreferences "Hin2n"：current_setting_id / start_at_boot / hin2n_static_routes / current_log_path。
 */
public class Hin2nCore {

    private static final String TAG = "Hin2nCore";

    /** 与原 hin2n 一致的偏好文件名与键名，保证旧版本升级后配置不丢失 */
    public static final String SP_NAME = "Hin2n";
    public static final String SP_CURRENT_SETTING_ID = "current_setting_id";
    public static final String SP_START_AT_BOOT = "start_at_boot";
    public static final String SP_STATIC_ROUTES = "hin2n_static_routes";
    public static final String SP_CURRENT_LOG_PATH = "current_log_path";

    /**
     * n2n 原生库加载顺序。
     * Android 4.4（API 19）等旧设备上单个库失败会导致整体不可用，
     * 因此逐个加载并单独捕获，只记日志。
     */
    private static final String[] NATIVE_LIBS = {
            "slog",
            "uip",
            "n2n_v2s",
            // n2n_v2 is part of edge_v2 due to dependency on the g_status
            "n2n_v1",
            "edge_v2s",
            "edge_v2",
            "edge_v1",
            "edge_v3",
            "edge_jni",
    };

    private static volatile boolean sLoaded = false;

    private static Context sApp;
    private static DaoMaster.DevOpenHelper sHelper;
    private static SQLiteDatabase sDb;
    private static DaoMaster sDaoMaster;
    private static DaoSession sDaoSession;

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        sApp = context.getApplicationContext();
        loadLibraries();
        safeRun("setDatabase", new Runnable() {
            @Override
            public void run() {
                setDatabase();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            safeRun("notificationChannel", new Runnable() {
                @Override
                public void run() {
                    initNotificationChannel();
                }
            });
        }
    }

    /** 逐个加载 n2n 原生库（幂等） */
    public static void loadLibraries() {
        if (sLoaded) {
            return;
        }
        synchronized (Hin2nCore.class) {
            if (sLoaded) {
                return;
            }
            for (String lib : NATIVE_LIBS) {
                try {
                    System.loadLibrary(lib);
                } catch (Throwable t) {
                    Log.e(TAG, "loadLibrary(" + lib + ") failed", t);
                }
            }
            sLoaded = true;
        }
    }

    public static Context context() {
        return sApp;
    }

    public static DaoSession daoSession() {
        if (sDaoSession == null && sApp != null) {
            synchronized (Hin2nCore.class) {
                if (sDaoSession == null) {
                    setDatabase();
                }
            }
        }
        return sDaoSession;
    }

    private static void setDatabase() {
        if (sApp == null) {
            return;
        }
        sHelper = new DaoMaster.DevOpenHelper(sApp, "N2N-db", null);
        sDb = sHelper.getWritableDatabase();
        sDaoMaster = new DaoMaster(sDb);
        sDaoSession = sDaoMaster.newSession();
    }

    // ---------------- SharedPreferences 便捷访问 ----------------

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
    }

    public static long currentSettingId(Context ctx) {
        return prefs(ctx).getLong(SP_CURRENT_SETTING_ID, -1L);
    }

    public static void setCurrentSettingId(Context ctx, long id) {
        prefs(ctx).edit().putLong(SP_CURRENT_SETTING_ID, id).apply();
    }

    public static boolean startAtBoot(Context ctx) {
        return prefs(ctx).getBoolean(SP_START_AT_BOOT, false);
    }

    public static void setStartAtBoot(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(SP_START_AT_BOOT, on).apply();
    }

    public static String staticRoutes(Context ctx) {
        return prefs(ctx).getString(SP_STATIC_ROUTES, "");
    }

    public static void setStaticRoutes(Context ctx, String routes) {
        prefs(ctx).edit().putString(SP_STATIC_ROUTES, routes == null ? "" : routes.trim()).apply();
    }

    /** 读取当前选中的配置；无配置 / 已被删除时返回 null */
    public static N2NSettingModel currentSetting(Context ctx) {
        long id = currentSettingId(ctx);
        if (id < 0 || sDaoSession == null) {
            return null;
        }
        try {
            return sDaoSession.getN2NSettingModelDao().load(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某 n2n 版本对应的日志文件（与 N2NService 中路径规则一致） */
    public static File logFile(Context ctx, int version) {
        String[] sessions = ctx.getResources().getStringArray(R.array.vpn_session_name);
        String session = (version >= 0 && version < sessions.length) ? sessions[version] : sessions[0];
        File dir = ctx.getExternalFilesDir("log");
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        return new File(dir, session + ".log");
    }

    /**
     * 开机自启启动 VPN 服务（由宿主 BootReceiver 调用）。
     * 条件：开关打开 + 有当前配置 + 用户已授权过 VPN（VpnService.prepare 为空）。
     */
    public static void startOnBoot(Context ctx) {
        try {
            if (ctx == null || !startAtBoot(ctx)) {
                return;
            }
            final Context app = ctx.getApplicationContext();
            if (VpnService.prepare(app) != null) {
                // 用户尚未同意 VPN，开机无法直接运行
                return;
            }
            N2NSettingModel model = currentSetting(app);
            if (model == null) {
                return;
            }
            Intent i = new Intent(app, N2NService.class);
            Bundle bundle = new Bundle();
            bundle.putParcelable("n2nSettingInfo", new N2NSettingInfo(model));
            i.putExtra("Setting", bundle);
            ContextCompat.startForegroundService(app, i);
        } catch (Throwable t) {
            Log.e(TAG, "startOnBoot failed", t);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private static void initNotificationChannel() {
        if (sApp == null) {
            return;
        }
        NotificationManager nm = (NotificationManager) sApp.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String id = sApp.getString(R.string.notification_channel_id_default);
        String name = sApp.getString(R.string.notification_channel_name_default);
        NotificationChannel ch = new NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
        ch.enableLights(true);
        ch.setLightColor(android.graphics.Color.RED);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static void safeRun(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Log.e(TAG, "init " + what + " failed", t);
        }
    }

    /**
     * 旧系统不方便获取 logcat 时，把未捕获崩溃写入应用目录 crash.log，
     * 便于定位「打开黑屏 / 闪退」等问题。由宿主 Application 安装一次。
     */
    public static void installCrashLogger(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    File dir = app.getExternalFilesDir(null);
                    File logFile = new File(dir != null ? dir : app.getFilesDir(), "crash.log");
                    FileWriter writer = new FileWriter(logFile, true);
                    writer.write(new Date().toString() + " thread=" + thread.getName() + "\n");
                    writer.write(Log.getStackTraceString(throwable));
                    writer.write("\n\n");
                    writer.close();
                } catch (Throwable ignored) {
                }
                Log.e(TAG, "uncaught exception", throwable);
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }
}
