package com.github.tvbox.osc.ui.fragment;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.n2n.RemoteConfig;
import com.orhanobut.hawk.Hawk;

/**
 * 远程配置页：配置接口地址 / 立即同步 / 开机启动。
 * 公告、首页自定义入口等默认值均从 JSON 接口读取（见 RemoteConfig）。
 */
public class ConfigFragment extends BaseLazyFragment {

    private TextView tvBoot;
    private TextView tvUrl;
    private TextView tvSync;

    /** 创建 Fragment 实例（供 SettingActivity 调用） */
    public static ConfigFragment newInstance() {
        return new ConfigFragment();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_config;
    }

    @Override
    protected void init() {
        tvBoot = findViewById(R.id.tv_cfg_boot);
        tvUrl = findViewById(R.id.tv_cfg_url);
        tvSync = findViewById(R.id.tv_cfg_sync);

        // 开机启动开关（默认值可由远程 JSON 下发，用户点击后以用户选择为准）
        findViewById(R.id.ll_cfg_boot).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = !RemoteConfig.bootStartEnabled();
                Hawk.put(RemoteConfig.K_BOOT, now);
                refreshCfg();
                Toast.makeText(mContext, now ? "已开启：开机后自动启动" : "已关闭开机启动",
                        Toast.LENGTH_SHORT).show();
            }
        });
        // 配置接口地址
        findViewById(R.id.ll_cfg_url).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText et = new EditText(mContext);
                et.setText(RemoteConfig.url());
                et.setSelection(et.getText().length());
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                new AlertDialog.Builder(mContext)
                        .setTitle("远程配置接口地址（JSON）")
                        .setView(et)
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String u = et.getText() == null ? "" : et.getText().toString().trim();
                                Hawk.put(RemoteConfig.K_URL, u);
                                refreshCfg();
                                if (!u.isEmpty()) {
                                    doSync();   // 保存后立即拉一次
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        // 立即同步远程配置
        findViewById(R.id.ll_cfg_sync).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSync();
            }
        });

        refreshCfg();
    }

    /** 把配置刷新到界面文字 */
    private void refreshCfg() {
        if (tvBoot == null) {
            return;
        }
        tvBoot.setText(RemoteConfig.bootStartEnabled() ? "开" : "关");
        String u = RemoteConfig.url();
        tvUrl.setText(u.isEmpty() ? "未设置" : u);
    }

    /** 立即从远程配置接口同步（公告/首页入口等），结果回显到行尾 */
    private void doSync() {
        if (RemoteConfig.url().isEmpty()) {
            Toast.makeText(mContext, "请先设置「配置接口」地址", Toast.LENGTH_SHORT).show();
            return;
        }
        tvSync.setText("同步中…");
        RemoteConfig.fetch(mContext, new RemoteConfig.Callback() {
            @Override
            public void onDone(boolean success) {
                if (tvSync == null) {
                    return;
                }
                refreshCfg();
                tvSync.setText(success ? "同步成功" : "同步失败（检查接口地址/网络）");
                Toast.makeText(mContext, success ? "远程配置已更新" : "远程配置拉取失败",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
