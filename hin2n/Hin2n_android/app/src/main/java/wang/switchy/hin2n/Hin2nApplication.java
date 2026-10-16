package wang.switchy.hin2n;

import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;


import androidx.multidex.MultiDexApplication;

import wang.switchy.hin2n.storage.db.base.DaoMaster;
import wang.switchy.hin2n.storage.db.base.DaoSession;
import wang.switchy.hin2n.tool.N2nTools;

import com.tencent.bugly.Bugly;
import com.umeng.analytics.MobclickAgent;
import com.umeng.commonsdk.UMConfigure;
import com.umeng.socialize.PlatformConfig;

import java.io.File;
import java.io.FileWriter;
import java.util.Date;


/**
 * Created by janiszhang on 2018/4/19.
 */

public class Hin2nApplication extends MultiDexApplication {

    private static final String TAG = "Hin2nApplication";

    /**
     * n2n 原生库。
     * Android 4.4（API 19）等旧设备上只要有一个库加载失败，静态代码块就会抛 ExceptionInInitializerError，
     * 表现为"打开即闪退"；因此逐个加载并单独捕获异常，单个库失败只记日志，不影响其它库与应用启动。
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

    public Context AppContext;

    private DaoMaster.DevOpenHelper mHelper;
    private SQLiteDatabase db;
    private DaoMaster mDaoMaster;
    private DaoSession mDaoSession;

    static {
        for (String lib : NATIVE_LIBS) {
            try {
                System.loadLibrary(lib);
            } catch (Throwable t) {
                Log.e(TAG, "loadLibrary(" + lib + ") failed", t);
            }
        }
    }

    //静态单例
    public static Hin2nApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        AppContext = this;

        // 数据库与第三方 SDK 初始化在旧系统上易抛异常，全部包裹，避免启动闪退
        safeRun("setDatabase", new Runnable() {
            @Override
            public void run() {
                setDatabase();
            }
        });

        safeRun("umeng", new Runnable() {
            @Override
            public void run() {
                UMConfigure.init(Hin2nApplication.this, N2nTools.getMetaData(Hin2nApplication.this, N2nTools.MetaUmengAppKey),
                        N2nTools.getMetaData(Hin2nApplication.this, N2nTools.MetaUmengChannel), UMConfigure.DEVICE_TYPE_PHONE, "");
                MobclickAgent.setScenarioType(Hin2nApplication.this, MobclickAgent.EScenarioType.E_UM_NORMAL);
            }
        });

        safeRun("bugly", new Runnable() {
            @Override
            public void run() {
                Bugly.init(Hin2nApplication.this, N2nTools.getMetaData(Hin2nApplication.this, N2nTools.MetaBuglyAppId), BuildConfig.DEBUG);
            }
        });

        safeRun("share", new Runnable() {
            @Override
            public void run() {
                initShare();
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

        // 放在最后安装：此时默认异常处理器可能已被 Bugly 接管，链式调用以保留 Bugly 上报
        installCrashLogger();
    }

    private void safeRun(String what, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            Log.e(TAG, "init " + what + " failed", t);
        }
    }

    /**
     * 旧系统上不方便获取 logcat 时，把未捕获的崩溃堆栈写入应用目录下的 crash.log，便于定位"打开闪退"。
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                try {
                    File dir = getExternalFilesDir(null);
                    File logFile = new File(dir != null ? dir : getFilesDir(), "crash.log");
                    FileWriter writer = new FileWriter(logFile, true);
                    writer.write(new Date().toString() + " thread=" + thread.getName() + "\n");
                    writer.write(Log.getStackTraceString(throwable));
                    writer.write("\n\n");
                    writer.close();
                } catch (Throwable ignored) {
                    // 写日志失败不影响原有崩溃处理流程
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

    private void initShare() {
        PlatformConfig.setWeixin(N2nTools.getMetaData(this, N2nTools.MetaShareWxAppId), N2nTools.getMetaData(this, N2nTools.MetaShareWxAppSecret));
    }

    public static Hin2nApplication getInstance() {
        return instance;
    }

    /**
     * 设置greenDao
     */
    private void setDatabase() {
        mHelper = new DaoMaster.DevOpenHelper(this, "N2N-db", null);
        db = mHelper.getWritableDatabase();
        mDaoMaster = new DaoMaster(db);
        mDaoSession = mDaoMaster.newSession();
    }

    public DaoSession getDaoSession() {
        return mDaoSession;
    }

    public SQLiteDatabase getDb() {
        return db;
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void initNotificationChannel() {
        String id = getString(R.string.notification_channel_id_default);
        String name = getString(R.string.notification_channel_name_default);
        createNotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String id, CharSequence name, int importance) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(new NotificationChannel(id, name, importance));
    }
}
