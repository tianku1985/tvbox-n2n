package com.github.tvbox.osc.ui.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.n2n.N2nConfig;
import com.github.tvbox.osc.n2n.N2nVpnService;

/**
 * N2N 设置页：展示连接状态与各项配置，支持修改配置、连接/断开 VPN。
 * 首次连接时会请求 VPN 授权，授权通过后启动 N2nVpnService。
 */
public class N2nSettingFragment extends BaseLazyFragment {

    private static final int REQUEST_VPN = 301;   // VPN 授权请求码
    private static final int MSG_REFRESH = 401;   // 状态刷新消息

    // 各设置项的字段编号，用于区分点击/回显
    private static final int FIELD_COMMUNITY = 1;
    private static final int FIELD_KEY = 2;
    private static final int FIELD_SUPERNODE = 3;
    private static final int FIELD_IP = 4;
    private static final int FIELD_MASK = 5;
    private static final int FIELD_MTU = 6;
    private static final int FIELD_ROUTE = 7;

    // 界面上的各类文本控件
    private TextView tvStatus;
    private TextView tvToggle;
    private TextView tvCommunity;
    private TextView tvKey;
    private TextView tvSupernode;
    private TextView tvIp;
    private TextView tvMask;
    private TextView tvMtu;
    private TextView tvRoute;
    private TextView tvMac;
    private TextView tvPing;
    private TextView tvLog;

    /** 当前配置（Hawk 持久化） */
    private N2nConfig cfg;

    /** 创建 Fragment 实例（供 SettingActivity 调用） */
    public static N2nSettingFragment newInstance() {
        return new N2nSettingFragment();
    }

    /** 主线程轮询：每 2 秒刷新一次连接状态（页面未初始化前不处理，避免 NPE） */
    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_REFRESH && tvStatus != null) {
                refreshStatus();
                sendEmptyMessageDelayed(MSG_REFRESH, 2000);
            }
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_n2n_setting;
    }

    /** 页面初始化：加载配置、绑定控件与点击事件 */
    @Override
    protected void init() {
        cfg = N2nConfig.load(mContext);

        tvStatus = findViewById(R.id.tv_n2n_status);
        tvToggle = findViewById(R.id.tv_n2n_toggle);
        tvCommunity = findViewById(R.id.tv_n2n_community);
        tvKey = findViewById(R.id.tv_n2n_key);
        tvSupernode = findViewById(R.id.tv_n2n_supernode);
        tvIp = findViewById(R.id.tv_n2n_ip);
        tvMask = findViewById(R.id.tv_n2n_mask);
        tvMtu = findViewById(R.id.tv_n2n_mtu);
        tvRoute = findViewById(R.id.tv_n2n_route);
        tvMac = findViewById(R.id.tv_n2n_mac);
        tvPing = findViewById(R.id.tv_n2n_ping);
        tvLog = findViewById(R.id.tv_n2n_log);

        // 『连接/断开』切换行
        findViewById(R.id.ll_n2n_toggle).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle();
            }
        });
        // 各配置行点击后弹出输入框
        findViewById(R.id.ll_n2n_community).setOnClickListener(promptField(FIELD_COMMUNITY, "社区", InputType.TYPE_CLASS_TEXT));
        findViewById(R.id.ll_n2n_key).setOnClickListener(promptField(FIELD_KEY, "密钥", InputType.TYPE_CLASS_TEXT));
        findViewById(R.id.ll_n2n_supernode).setOnClickListener(promptField(FIELD_SUPERNODE, "服务器(host:port)", InputType.TYPE_CLASS_TEXT));
        findViewById(R.id.ll_n2n_ip).setOnClickListener(promptField(FIELD_IP, "虚拟IP", InputType.TYPE_CLASS_TEXT));
        findViewById(R.id.ll_n2n_mask).setOnClickListener(promptField(FIELD_MASK, "子网掩码", InputType.TYPE_CLASS_TEXT));
        findViewById(R.id.ll_n2n_mtu).setOnClickListener(promptField(FIELD_MTU, "MTU", InputType.TYPE_CLASS_NUMBER));
        findViewById(R.id.ll_n2n_route).setOnClickListener(promptField(FIELD_ROUTE, "额外路由(空格或逗号分隔)", InputType.TYPE_CLASS_TEXT));
        // MAC 行点击重新生成
        findViewById(R.id.ll_n2n_mac).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.mac = N2nConfig.randomMac();
                cfg.save();
                tvMac.setText(cfg.mac);
                Toast.makeText(mContext, "MAC 已重新生成", Toast.LENGTH_SHORT).show();
            }
        });
        // Ping 测试行
        findViewById(R.id.ll_n2n_ping).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPingDialog();
            }
        });
        // 运行日志行
        findViewById(R.id.ll_n2n_log).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogDialog();
            }
        });

        refreshCfg();
        refreshStatus();
    }

    /** 连接/断开切换：已在运行则断开，否则先走 VPN 授权流程 */
    private void onToggle() {
        if (N2nVpnService.isRunning()) {
            N2nVpnService.stopAll();
            Toast.makeText(mContext, "n2n 已断开", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        Intent intent = VpnService.prepare(mContext);
        if (intent != null) {
            // 尚未授权：弹出系统授权页
            startActivityForResult(intent, REQUEST_VPN);
        } else {
            doStart();
        }
    }

    /** 校验必填项后启动 VPN */
    private void doStart() {
        if (cfg.supernode == null || cfg.supernode.trim().isEmpty()
                || cfg.community == null || cfg.community.trim().isEmpty()) {
            Toast.makeText(mContext, "请填写服务器和社区", Toast.LENGTH_SHORT).show();
            return;
        }
        cfg.save();
        N2nVpnService.start(mContext, cfg);
        refreshStatus();
    }

    /** 接收 VPN 授权回调结果 */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN && resultCode == Activity.RESULT_OK) {
            doStart();
        }
    }

    /** 生成配置行的点击事件：弹出输入框编辑 */
    private View.OnClickListener promptField(final int field, final String title, final int inputType) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String current = valueOf(field);
                final EditText et = new EditText(mContext);
                et.setText(current);
                et.setSelection(et.getText().length());
                et.setInputType(inputType);
                new AlertDialog.Builder(mContext)
                        .setTitle(title)
                        .setView(et)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String value = et.getText() == null ? "" : et.getText().toString().trim();
                                setField(field, value);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        };
    }

    /** 按字段编号取当前配置值（用于回显输入框） */
    private String valueOf(int field) {
        switch (field) {
            case FIELD_COMMUNITY:
                return cfg.community;
            case FIELD_KEY:
                return cfg.key;
            case FIELD_SUPERNODE:
                return cfg.supernode;
            case FIELD_IP:
                return cfg.ip;
            case FIELD_MASK:
                return cfg.mask;
            case FIELD_MTU:
                return String.valueOf(cfg.mtu);
            case FIELD_ROUTE:
                return cfg.extraRoute;
        }
        return "";
    }

    /** 把输入值写回配置并保存 */
    private void setField(int field, String value) {
        switch (field) {
            case FIELD_COMMUNITY:
                cfg.community = value;
                break;
            case FIELD_KEY:
                cfg.key = value;
                break;
            case FIELD_SUPERNODE:
                cfg.supernode = value;
                break;
            case FIELD_IP:
                cfg.ip = value;
                break;
            case FIELD_MASK:
                cfg.mask = value;
                break;
            case FIELD_MTU:
                try {
                    cfg.mtu = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    // keep old
                }
                break;
            case FIELD_ROUTE:
                cfg.extraRoute = value;
                break;
        }
        cfg.save();
        refreshCfg();
    }

    /** 把配置刷新到界面文字 */
    private void refreshCfg() {
        tvCommunity.setText(cfg.community);
        tvKey.setText(cfg.key);
        tvSupernode.setText(cfg.supernode);
        tvIp.setText(cfg.ip);
        tvMask.setText(cfg.mask);
        tvMtu.setText(String.valueOf(cfg.mtu));
        tvRoute.setText(cfg.extraRoute);
        tvMac.setText(cfg.mac);
    }

    /** 根据连接状态刷新『状态/连接』两行 */
    private void refreshStatus() {
        boolean running = N2nVpnService.isRunning();
        String state = N2nVpnService.stateText();
        if (running && "已连接".equals(state)) {
            // 已连接：展示分配到的虚拟 IP（静态配置即所获 IP）
            tvStatus.setText("已连接  IP: " + cfg.ip);
            tvStatus.setTextColor(0xFF00E676);
        } else {
            tvStatus.setText(state);
            tvStatus.setTextColor(0xFFFFFFFF);
        }
        tvToggle.setText(running ? "断开" : "连接");
    }

    /** Ping 测试：目标默认为服务器地址（去掉端口），可手动输入虚拟网段内其他 IP */
    private void showPingDialog() {
        String defTarget = hostOf(cfg.supernode);
        final EditText et = new EditText(mContext);
        et.setText(defTarget);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(mContext)
                .setTitle("Ping 测试（IP 或域名）")
                .setView(et)
                .setPositiveButton("开始", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String target = et.getText() == null ? "" : et.getText().toString().trim();
                        if (!target.isEmpty()) {
                            pingHost(target);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 取 "host:port" 中的 host 部分（无端口则原样返回） */
    private static String hostOf(String addr) {
        if (addr == null) {
            return "";
        }
        int i = addr.lastIndexOf(':');
        // 端口冒号只有在没有多个冒号（非 IPv6）时才剥离
        return (i > 0 && addr.indexOf(':') == i) ? addr.substring(0, i) : addr;
    }

    /** 后台执行 ping -c 4，结果显示在行尾并弹窗展示完整输出 */
    private void pingHost(final String target) {
        if (tvPing != null) {
            tvPing.setText("测试中…");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String output;
                String summary;
                try {
                    Process p = Runtime.getRuntime().exec(
                            new String[]{"ping", "-c", "4", "-W", "2", target});
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    br.close();
                    p.waitFor();
                    output = sb.toString();
                    summary = summarizePing(output, target);
                } catch (Exception e) {
                    output = "ping 失败: " + e.getMessage();
                    summary = "失败";
                }
                final String fOutput = output;
                final String fSummary = summary;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tvPing != null) {
                            tvPing.setText(fSummary);
                        }
                        showTextDialog("Ping " + target, fOutput);
                    }
                });
            }
        }, "n2n-ping").start();
    }

    /** 从 ping 输出提取一行摘要（延迟或丢包） */
    private static String summarizePing(String output, String target) {
        // 匹配 rtt 统计行: "rtt min/avg/max/mdev = 1.2/3.4/5.6/0.7 ms"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("=\\s*([\\d.]+)/([\\d.]+)/([\\d.]+)/[\\d.]+\\s*ms")
                .matcher(output);
        if (m.find()) {
            return target + "  " + m.group(2) + " ms";
        }
        if (output.contains("100% packet loss") || output.contains("100% loss")) {
            return target + "  不可达";
        }
        if (output.contains("ttl=") || output.contains("TTL=")) {
            return target + "  可达";
        }
        return target + "  无响应";
    }

    /** 运行日志：读取 edge 输出尾部并展示 */
    private void showLogDialog() {
        showTextDialog("edge 运行日志（尾部）", N2nVpnService.logTail(mContext, 8000));
    }

    /** 等宽滚动文本弹窗（日志/ping 输出共用） */
    private void showTextDialog(String title, String content) {
        android.widget.ScrollView sv = new android.widget.ScrollView(mContext);
        TextView tv = new TextView(mContext);
        tv.setText(content);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setPadding(40, 24, 40, 24);
        sv.addView(tv);
        new AlertDialog.Builder(mContext)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    // 页面可见时开启状态轮询，不可见时停止。
    // 注意：ViewPager 会预加载本页，onResume 时 init() 可能尚未执行（懒加载，
    // 控件仍为 null），此时不能启动轮询，否则 refreshStatus() 会 NPE 闪退。
    @Override
    public void onResume() {
        super.onResume();
        if (tvStatus == null) {
            return;
        }
        mHandler.removeMessages(MSG_REFRESH);
        mHandler.sendEmptyMessageDelayed(MSG_REFRESH, 500);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeMessages(MSG_REFRESH);
    }
}