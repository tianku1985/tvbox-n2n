package wang.switchy.hin2n.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.VpnService;
import android.os.*;

import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import wang.switchy.hin2n.R;
import wang.switchy.hin2n.event.*;
import wang.switchy.hin2n.model.EdgeCmd;
import wang.switchy.hin2n.model.EdgeStatus;
import wang.switchy.hin2n.model.N2NSettingInfo;
import wang.switchy.hin2n.tool.IOUtils;
import wang.switchy.hin2n.tool.LogFileObserver;
import wang.switchy.hin2n.tool.ThreadUtils;

import static wang.switchy.hin2n.model.EdgeStatus.RunningStatus.DISCONNECT;
import static wang.switchy.hin2n.model.EdgeStatus.RunningStatus.SUPERNODE_DISCONNECT;
import static wang.switchy.hin2n.tool.N2nTools.getIpAddrPrefixLength;
import static wang.switchy.hin2n.tool.N2nTools.getRoute;

/**
 * Created by janiszhang on 2018/4/15.
 */

public class N2NService extends VpnService {

    public static N2NService INSTANCE;

    private ParcelFileDescriptor mParcelFileDescriptor = null;
    private EdgeCmd cmd;
    N2NSettingInfo mN2nSettingInfo = null;

    private EdgeStatus.RunningStatus mLastStatus = DISCONNECT;
    private EdgeStatus.RunningStatus mCurrentStatus = DISCONNECT;

    private static final int sNotificationId = 1;
    private NotificationManager mNotificationManager;
    private boolean mStopInProgress = false;
    private FileObserver mFileObserver;

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    public int EstablishVpnService(String ip, int mask) {

        Builder builder = new Builder()
                .setMtu(mN2nSettingInfo.getMtu())
                .addAddress(ip, mask)
                .addRoute(getRoute(mN2nSettingInfo.getIp(), mask), mask);

        if (!mN2nSettingInfo.getGatewayIp().isEmpty()) {
            /* Route all the internet traffic via n2n. Most specific routes "win" over the system default gateway.
             * See https://github.com/zerotier/ZeroTierOne/issues/178#issuecomment-204599227 */
            builder.addRoute("0.0.0.0", 1);
            builder.addRoute("128.0.0.0", 1);
        }

        // hin2n-patch: 自定义静态路由（SharedPreferences "Hin2n" 键 hin2n_static_routes）
        // 格式: CIDR 网关IP（网关可选），多条空格分隔
        // 带 / 的是 CIDR（如 192.168.8.0/24），不带 / 的是网关 IP（如 10.0.0.13）
        // 例: 192.168.8.0/24 10.0.0.13 192.168.9.0/24 10.0.0.13
        String hin2nStaticRoutes = getSharedPreferences("Hin2n", Context.MODE_PRIVATE).getString("hin2n_static_routes", "");
        if (hin2nStaticRoutes != null && !hin2nStaticRoutes.trim().isEmpty()) {
            String[] hin2nTokens = hin2nStaticRoutes.trim().split("\\s+");
            String hin2nCidr = null;
            String hin2nGateway = null;
            for (int hi = 0; hi <= hin2nTokens.length; hi++) {
                if (hi < hin2nTokens.length && hin2nTokens[hi].contains("/")) {
                    // 先处理上一条
                    if (hin2nCidr != null) {
                        addHin2nRoute(builder, hin2nCidr, hin2nGateway);
                        hin2nGateway = null;
                    }
                    hin2nCidr = hin2nTokens[hi];
                } else if (hi < hin2nTokens.length) {
                    // 不含 / 的是网关 IP
                    hin2nGateway = hin2nTokens[hi];
                } else {
                    // 最后一条
                    if (hin2nCidr != null) {
                        addHin2nRoute(builder, hin2nCidr, hin2nGateway);
                    }
                }
            }
        }

        if (!mN2nSettingInfo.getDnsServer().isEmpty()) {
            Log.d("N2NService", "Using DNS server: " + mN2nSettingInfo.getDnsServer());
            builder.addDnsServer(mN2nSettingInfo.getDnsServer());
        }

        String session = getResources().getStringArray(R.array.vpn_session_name)[mN2nSettingInfo.getVersion()];
        try {
            mParcelFileDescriptor = builder.setSession(session).establish();
        } catch (IllegalArgumentException e) {
            Toast.makeText(INSTANCE, "Parameter is not accepted by the operating system.", Toast.LENGTH_SHORT).show();
            return -1;
        } catch (IllegalStateException e) {
            Toast.makeText(INSTANCE, "Parameter cannot be applied by the operating system.", Toast.LENGTH_SHORT).show();
            return -1;
        }

        if (mParcelFileDescriptor == null) {
            EventBus.getDefault().post(new ErrorEvent());
            return -1;
        }

        return mParcelFileDescriptor.detachFd();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            EventBus.getDefault().post(new ErrorEvent());
            return super.onStartCommand(intent, flags, startId);
        }

        Bundle setting = intent.getBundleExtra("Setting");
        mN2nSettingInfo = setting.getParcelable("n2nSettingInfo");

        int vpnServiceFd = -1;
        if (mN2nSettingInfo.getIpMode() == 0) {
            vpnServiceFd = EstablishVpnService(mN2nSettingInfo.getIp(), getIpAddrPrefixLength(mN2nSettingInfo.getNetmask()));
            if (vpnServiceFd < 0) {
                return super.onStartCommand(intent, flags, startId);
            }
        }

        String session = getResources().getStringArray(R.array.vpn_session_name)[mN2nSettingInfo.getVersion()];
        cmd = new EdgeCmd(mN2nSettingInfo, vpnServiceFd, getExternalFilesDir("log") + "/" + session + ".log");
        mFileObserver = new LogFileObserver(cmd.logPath);
        mFileObserver.stopWatching();
        IOUtils.clearLogTxt(cmd.logPath);
        mFileObserver.startWatching();
        try {
            if (!startEdge(cmd)) {
                EventBus.getDefault().post(new ErrorEvent());
            }
        } catch (Exception e) {
            EventBus.getDefault().post(new ErrorEvent());
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 通知渠道由 Hin2nCore.init 统一创建（R.string.notification_channel_id_default）
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pendingIntent = i != null
                    ? PendingIntent.getActivity(this, 0, i, flags)
                    : null;
            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, getString(R.string.notification_channel_id_default))
                    .setTicker("Nature")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                    .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                    .setContentTitle("hin2n")
                    .setContentText("hin2n service is running")
                    .setContentIntent(pendingIntent);
            Notification notification = notificationBuilder.build();
            notification.flags |= Notification.FLAG_NO_CLEAR;
            startForeground(1, notification);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    public boolean isStopInProgress() {
        return (mStopInProgress);
    }

    public boolean stop(final Runnable onStopCallback) {
        if (isStopInProgress()) {
            Toast.makeText(getApplicationContext(), "a stop command is already in progress", Toast.LENGTH_SHORT).show();
            return (false);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            stopForeground(true);

        ThreadUtils.cachedThreadExecutor(new Runnable() {
            @Override
            public void run() {
                /* Blocking call */
                stopEdge();
                mStopInProgress = true;
                ThreadUtils.mainThreadExecutor(new Runnable() {
                    @Override
                    public void run() {
                        mLastStatus = mCurrentStatus = DISCONNECT;
                        showOrRemoveNotification(CMD_REMOVE_NOTIFICATION);

                        try {
                            if (mParcelFileDescriptor != null) {
                                mParcelFileDescriptor.close();
                                mParcelFileDescriptor = null;
                            }
                        } catch (IOException e) {
                            EventBus.getDefault().post(new ErrorEvent());
                            return;
                        }

                        EventBus.getDefault().post(new StopEvent());
                        mStopInProgress = false;
                        if(mFileObserver != null){
                            mFileObserver.stopWatching();  //清除日志文件会导致FileObserver失效，要先stop再start
                        }
                        if (onStopCallback != null)
                            onStopCallback.run();

                    }
                });
            }
        });
        return (true);
    }

    // hin2n-patch: 添加静态路由到 VpnService builder
    private void addHin2nRoute(Builder builder, String cidr, String gateway) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length == 2) {
                builder.addRoute(parts[0], Integer.parseInt(parts[1]));
                String msg = "add route: " + cidr;
                if (gateway != null && !gateway.isEmpty()) {
                    msg += " via " + gateway;
                    builder.addRoute(gateway, 32);
                }
                Log.d("N2NService", msg);
            }
        } catch (Exception e) {
            Log.e("N2NService", "invalid route: " + cidr, e);
        }
    }

    @Override
    public void onRevoke() {
        super.onRevoke();
        stop(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stop(null);
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
        }
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    public native boolean startEdge(EdgeCmd cmd);

    public native void stopEdge();

    public native EdgeStatus getEdgeStatus();

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLogChangeEvent(final LogChangeEvent event) {
        EdgeStatus status = getEdgeStatus();
        Log.d("status",status.runningStatus.name());
        reportEdgeStatus(status);
    }


    public void reportEdgeStatus(EdgeStatus status) {
        mLastStatus = mCurrentStatus;
        mCurrentStatus = status.runningStatus;

        if (mLastStatus == mCurrentStatus) {
            return;
        }

        switch (status.runningStatus) {
//            case CONNECTING:
            case CONNECTED:
                EventBus.getDefault().post(new StartEvent());
                if (mLastStatus == SUPERNODE_DISCONNECT) {
                    showOrRemoveNotification(CMD_UPDATE_NOTIFICATION);
                }
                break;
            case SUPERNODE_DISCONNECT:
                showOrRemoveNotification(CMD_ADD_NOTIFICATION);
                EventBus.getDefault().post(new SupernodeDisconnectEvent());
                break;
            case DISCONNECT:
                EventBus.getDefault().post(new StopEvent());
                if (mLastStatus == SUPERNODE_DISCONNECT) {
                    showOrRemoveNotification(CMD_REMOVE_NOTIFICATION);
                }
                break;
            case FAILED:
                EventBus.getDefault().post(new StopEvent());
                if (mLastStatus == SUPERNODE_DISCONNECT) {
                    showOrRemoveNotification(CMD_REMOVE_NOTIFICATION);
                }
                break;
            default:
                break;
        }
    }

    public EdgeStatus.RunningStatus getCurrentStatus() {
        return mCurrentStatus;
    }

    private static final int CMD_REMOVE_NOTIFICATION = 0;
    private static final int CMD_ADD_NOTIFICATION = 1;
    private static final int CMD_UPDATE_NOTIFICATION = 2;

    //supernode连接断开 supernode连接恢复 连接断开/失败--清除通知栏
    private void showOrRemoveNotification(int cmd) {
        switch (cmd) {
            case CMD_REMOVE_NOTIFICATION:
                if (mNotificationManager == null) {
                    mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                }
                mNotificationManager.cancel(sNotificationId);
                break;
            case CMD_ADD_NOTIFICATION:
                Intent mainIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent mainPendingIntent = mainIntent != null
                        ? PendingIntent.getActivity(this, 0, mainIntent, piFlags)
                        : null;

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, getString(R.string.notification_channel_id_default))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_state_supernode_diconnect))
                        .setColor(ContextCompat.getColor(this, R.color.colorSupernodeDisconnect))
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.notify_disconnect))
                        .setFullScreenIntent(null, false)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setAutoCancel(false)
                        .setContentIntent(mainPendingIntent);

                Notification notification = builder.build();
                notification.flags |= Notification.FLAG_NO_CLEAR;
                if (mNotificationManager == null) {
                    mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                }
                mNotificationManager.notify(sNotificationId, notification);
                break;
            case CMD_UPDATE_NOTIFICATION:
                Intent mainIntent1 = getPackageManager().getLaunchIntentForPackage(getPackageName());
                int piFlags1 = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent mainPendingIntent1 = mainIntent1 != null
                        ? PendingIntent.getActivity(this, 0, mainIntent1, piFlags1)
                        : null;

                NotificationCompat.Builder builder2 = new NotificationCompat.Builder(this, getString(R.string.notification_channel_id_default))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                        .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(getString(R.string.notify_reconnected))
                        .setFullScreenIntent(null, false)
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .setAutoCancel(true)
                        .setContentIntent(mainPendingIntent1);

                Notification notification2 = builder2.build();
                if (mNotificationManager == null) {
                    mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                }
                mNotificationManager.notify(sNotificationId, notification2);
                break;
            default:
                break;
        }
    }
}
