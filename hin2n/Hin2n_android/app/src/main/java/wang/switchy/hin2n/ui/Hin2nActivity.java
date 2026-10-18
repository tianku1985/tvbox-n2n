package wang.switchy.hin2n.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import wang.switchy.hin2n.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

import wang.switchy.hin2n.Hin2nCore;
import wang.switchy.hin2n.event.ConnectingEvent;
import wang.switchy.hin2n.event.ErrorEvent;
import wang.switchy.hin2n.event.StartEvent;
import wang.switchy.hin2n.event.StopEvent;
import wang.switchy.hin2n.event.SupernodeDisconnectEvent;
import wang.switchy.hin2n.model.EdgeStatus;
import wang.switchy.hin2n.model.N2NSettingInfo;
import wang.switchy.hin2n.service.N2NService;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;
import wang.switchy.hin2n.tool.IOUtils;

/**
 * hin2n TV 引导页：
 * - 大按钮连接 / 断开当前 n2n 配置；
 * - 进入配置管理（列表 / 新建 / 编辑）；
 * - 开机启动开关（勾选需先进行系统 VPN 授权）；
 * - Ping 测试、运行日志轮询。
 * 独立 App 版：固定深色背景 + 白字，无 TVBox 主题依赖。
 */
public class Hin2nActivity extends BaseActivity implements View.OnClickListener {

    private static final int REQ_VPN_CONNECT = 0x1201;
    private static final int REQ_VPN_BOOT = 0x1202;
    private static final long LOG_INTERVAL_MS = 1500L;
    /** ping 主机名校验（防注入；实际 exec 不经 shell，这里仍做白名单约束） */
    private static final Pattern PING_HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,64}");

    private TextView tvStatus;
    private TextView btnConnect;
    private TextView btnBoot;
    private TextView tvCurrent;
    private TextView tvLog;
    private ScrollView svLog;

    private final Handler mHandler = new Handler();
    private String mLastLog = "";
    /** 开机启动开关在 VPN 授权返回前的暂存（同意授权才真正写 SP） */
    private boolean mBootPending = false;

    private final Runnable mLogTask = new Runnable() {
        @Override
        public void run() {
            refreshLog();
            mHandler.postDelayed(this, LOG_INTERVAL_MS);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_hin2n;
    }

    @Override
    protected void init() {
        tvStatus = findViewById(R.id.tvHin2nStatus);
        btnConnect = findViewById(R.id.btnHin2nConnect);
        btnBoot = findViewById(R.id.btnHin2nBoot);
        tvCurrent = findViewById(R.id.tvHin2nCurrent);
        tvLog = findViewById(R.id.tvHin2nLog);
        svLog = findViewById(R.id.svHin2nLog);

        findViewById(R.id.btnHin2nList).setOnClickListener(this);
        findViewById(R.id.btnHin2nAdd).setOnClickListener(this);
        findViewById(R.id.btnHin2nPing).setOnClickListener(this);
        findViewById(R.id.btnHin2nClearLog).setOnClickListener(this);
        findViewById(R.id.btnHin2nBack).setOnClickListener(this);
        btnConnect.setOnClickListener(this);
        btnBoot.setOnClickListener(this);

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        // TV 进入页面后默认聚焦「连接」按钮
        btnConnect.post(new Runnable() {
            @Override
            public void run() {
                btnConnect.requestFocus();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrent();
        refreshStatus(currentStatus());
        refreshBoot();
        mHandler.removeCallbacks(mLogTask);
        mHandler.post(mLogTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mLogTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
        mHandler.removeCallbacks(mLogTask);
    }

    private EdgeStatus.RunningStatus currentStatus() {
        if (N2NService.INSTANCE != null) {
            EdgeStatus.RunningStatus s = N2NService.INSTANCE.getCurrentStatus();
            if (s != null) {
                return s;
            }
        }
        return EdgeStatus.RunningStatus.DISCONNECT;
    }

    private void refreshCurrent() {
        N2NSettingModel model = Hin2nCore.currentSetting(this);
        tvCurrent.setText("当前配置：" + (model == null ? "无（请先到「配置管理」新建）" : model.getName()));
    }

    private void refreshBoot() {
        btnBoot.setText("开机启动：" + (Hin2nCore.startAtBoot(this) ? "开" : "关"));
    }

    private void refreshStatus(EdgeStatus.RunningStatus status) {
        switch (status) {
            case CONNECTING:
                tvStatus.setText("状态：连接中…");
                btnConnect.setText("连接中…");
                break;
            case CONNECTED:
                tvStatus.setText("状态：已连接");
                btnConnect.setText("断开");
                break;
            case SUPERNODE_DISCONNECT:
                tvStatus.setText("状态：与 supernode 断开，尝试重连中…");
                btnConnect.setText("断开");
                break;
            case FAILED:
                tvStatus.setText("状态：连接失败");
                btnConnect.setText("连接");
                break;
            case DISCONNECT:
            default:
                tvStatus.setText("状态：未连接");
                btnConnect.setText("连接");
                break;
        }
    }

    private void refreshLog() {
        try {
            N2NSettingModel model = Hin2nCore.currentSetting(this);
            if (model == null) {
                return;
            }
            File f = Hin2nCore.logFile(this, model.getVersion());
            if (!f.exists()) {
                return;
            }
            String text = IOUtils.readTxtLimit(f.getAbsolutePath(), 16 * 1024);
            if (text != null && !text.equals(mLastLog)) {
                mLastLog = text;
                tvLog.setText(text);
                svLog.post(new Runnable() {
                    @Override
                    public void run() {
                        svLog.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnHin2nConnect) {
            toggleConnect();
        } else if (id == R.id.btnHin2nList) {
            startActivity(new Intent(this, Hin2nConfigListActivity.class));
        } else if (id == R.id.btnHin2nAdd) {
            startActivity(new Intent(this, Hin2nConfigEditActivity.class)
                    .putExtra("type", Hin2nConfigEditActivity.TYPE_ADD));
        } else if (id == R.id.btnHin2nPing) {
            showPingDialog();
        } else if (id == R.id.btnHin2nBoot) {
            toggleBoot();
        } else if (id == R.id.btnHin2nClearLog) {
            clearLogs();
        } else if (id == R.id.btnHin2nBack) {
            finish();
        }
    }

    private void toggleConnect() {
        EdgeStatus.RunningStatus status = currentStatus();
        if (status == EdgeStatus.RunningStatus.CONNECTED
                || status == EdgeStatus.RunningStatus.CONNECTING
                || status == EdgeStatus.RunningStatus.SUPERNODE_DISCONNECT) {
            if (N2NService.INSTANCE != null) {
                N2NService.INSTANCE.stop(null);
            }
            return;
        }
        N2NSettingModel model = Hin2nCore.currentSetting(this);
        if (model == null) {
            Toast.makeText(this, "请先在「配置管理」中新建并选择一条配置", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Hin2nConfigListActivity.class));
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            startActivityForResult(prepare, REQ_VPN_CONNECT);
        } else {
            startEdge(model);
        }
    }

    private void startEdge(N2NSettingModel model) {
        try {
            Intent intent = new Intent(this, N2NService.class);
            Bundle bundle = new Bundle();
            bundle.putParcelable("n2nSettingInfo", new N2NSettingInfo(model));
            intent.putExtra("Setting", bundle);
            androidx.core.content.ContextCompat.startForegroundService(this, intent);
            refreshStatus(EdgeStatus.RunningStatus.CONNECTING);
        } catch (Throwable t) {
            Toast.makeText(this, "启动 n2n 服务失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void toggleBoot() {
        boolean on = !Hin2nCore.startAtBoot(this);
        if (!on) {
            Hin2nCore.setStartAtBoot(this, false);
            refreshBoot();
            return;
        }
        // 与原 hin2n 行为一致：开启开机启动前必须先完成一次系统 VPN 授权
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            mBootPending = true;
            startActivityForResult(prepare, REQ_VPN_BOOT);
        } else {
            Hin2nCore.setStartAtBoot(this, true);
            refreshBoot();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_CONNECT) {
            if (resultCode == Activity.RESULT_OK) {
                N2NSettingModel model = Hin2nCore.currentSetting(this);
                if (model != null) {
                    startEdge(model);
                }
            } else {
                Toast.makeText(this, "未获得 VPN 授权，连接已取消", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_VPN_BOOT) {
            if (resultCode == Activity.RESULT_OK && mBootPending) {
                Hin2nCore.setStartAtBoot(this, true);
                Toast.makeText(this, "已开启开机启动", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未获得 VPN 授权，开机启动保持关闭", Toast.LENGTH_SHORT).show();
            }
            mBootPending = false;
            refreshBoot();
        }
    }

    private void clearLogs() {
        try {
            N2NSettingModel model = Hin2nCore.currentSetting(this);
            if (model != null) {
                File f = Hin2nCore.logFile(this, model.getVersion());
                if (f.exists()) {
                    IOUtils.clearLogTxt(f.getAbsolutePath());
                }
            }
            mLastLog = "";
            tvLog.setText("日志已清空");
        } catch (Throwable ignored) {
        }
    }

    private void showPingDialog() {
        final EditText et = new EditText(this);
        et.setHint("主机名或 IP，如 10.0.0.1");
        et.setSingleLine(true);
        // 独立 App 固定深色输入框 + 白字
        int bg = 0xFF2A2A2A;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(bg);
        float radius = 10 * getResources().getDisplayMetrics().density;
        gd.setCornerRadius(radius);
        et.setBackground(gd);
        et.setTextColor(0xFFFFFFFF);
        et.setHintTextColor(0x99FFFFFF);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, pad / 2);
        new AlertDialog.Builder(this)
                .setTitle("Ping 测试")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String host = et.getText().toString().trim();
                        if (!PING_HOST.matcher(host).matches()) {
                            Toast.makeText(Hin2nActivity.this, "主机名仅允许字母、数字、点、短横线", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        doPing(host);
                    }
                })
                .show();
    }

    private void doPing(final String host) {
        tvStatus.setText("状态：Ping " + host + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                BufferedReader reader = null;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"ping", "-c", "4", "-W", "2", host});
                    reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    p.waitFor();
                    if (sb.length() == 0) {
                        sb.append("无输出（设备可能不支持 ping 命令）");
                    }
                } catch (final Throwable t) {
                    sb.setLength(0);
                    sb.append("Ping 失败：").append(t.getMessage());
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus(currentStatus());
                        new AlertDialog.Builder(Hin2nActivity.this)
                                .setTitle("Ping " + host)
                                .setMessage(sb.toString())
                                .setPositiveButton("关闭", null)
                                .show();
                    }
                });
            }
        }).start();
    }

    // ---------------- n2n 运行事件（EventBus 主线程） ----------------

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConnecting(ConnectingEvent event) {
        refreshStatus(EdgeStatus.RunningStatus.CONNECTING);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStart(StartEvent event) {
        refreshStatus(EdgeStatus.RunningStatus.CONNECTED);
        Toast.makeText(this, "n2n 已连接", Toast.LENGTH_SHORT).show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStop(StopEvent event) {
        refreshStatus(EdgeStatus.RunningStatus.DISCONNECT);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSupernodeDisconnect(SupernodeDisconnectEvent event) {
        refreshStatus(EdgeStatus.RunningStatus.SUPERNODE_DISCONNECT);
        Toast.makeText(this, "已断开与 supernode 的连接，稍后尝试重连", Toast.LENGTH_SHORT).show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onError(ErrorEvent event) {
        refreshStatus(EdgeStatus.RunningStatus.FAILED);
        Toast.makeText(this, "连接 N2N 网络失败", Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
