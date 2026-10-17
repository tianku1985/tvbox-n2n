package wang.switchy.hin2n.ui;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import wang.switchy.hin2n.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import wang.switchy.hin2n.Hin2nCore;
import wang.switchy.hin2n.event.ErrorEvent;
import wang.switchy.hin2n.event.StartEvent;
import wang.switchy.hin2n.model.EdgeCmd;
import wang.switchy.hin2n.model.N2NSettingInfo;
import wang.switchy.hin2n.service.N2NService;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

/**
 * hin2n 配置编辑（TV），完整复刻原 hin2n SettingDetailsActivity：
 * 28 个字段、版本联动显隐与默认 supernode 切换、参数校验、重名自动加 (i)、
 * 首条配置自动选中、修改运行中当前配置时询问是否重连。
 */
public class Hin2nConfigEditActivity extends BaseActivity implements View.OnClickListener {

    public static final int TYPE_ADD = 0;
    public static final int TYPE_MODIFY = 1;
    private static final int REQ_VPN_RECONNECT = 0x1401;

    private static final String DEFAULT_SN_V1 = "hin2n.nton.eu.org:10082";
    private static final String DEFAULT_SN_V2 = "hin2n.nton.eu.org:10086";
    private static final String DEFAULT_SN_V2S = "hin2n.nton.eu.org:10088";
    private static final String DEFAULT_SN_V3 = "supernode.ntop.org:7777";
    private static final String DEFAULT_NETMASK = "255.255.255.0";

    private int mType = TYPE_ADD;
    private long mSaveId = 0L;
    private N2NSettingModel mModel;
    private boolean mReconnecting = false;

    private RadioGroup rgVersion;
    private TextView tvTitle;
    private EditText etName, etIp, etNetmask, etCommunity, etPassword, etSuperNode, etBackup, etDevDesc;
    private EditText etMac, etMtu, etLocalIp, etHolePunch, etLocalPort, etGateway, etDns, etStaticRoutes;
    private CheckBox cbIpMode, cbMore, cbLocalIpAuto, cbResolve, cbAllowRouting, cbAcceptMulticast, cbHeaderEnc;
    private LinearLayout llIp, llNetmask, llPassword, llBackup, llDevDesc, llMore;
    private LinearLayout llLocalIp, llHolePunch, llGateway, llDns, llEnc;
    private Spinner spTrace, spEnc;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_hin2n_edit;
    }

    @Override
    protected void init() {
        mType = getIntent().getIntExtra("type", TYPE_ADD);
        bindViews();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
        if (mType == TYPE_MODIFY) {
            mSaveId = getIntent().getLongExtra("saveId", 0L);
            mModel = Hin2nCore.configById(mSaveId);
            if (mModel == null) {
                Toast.makeText(this, "配置不存在", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            tvTitle.setText("修改配置");
            fillModify();
        } else {
            tvTitle.setText("新建配置");
            fillDefaults();
        }
        updateVersionGroupCheck(rgVersion.getCheckedRadioButtonId());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }

    private void bindViews() {
        tvTitle = findViewById(R.id.tvHin2nEditTitle);
        rgVersion = findViewById(R.id.rgHin2nVersion);
        etName = findViewById(R.id.etHin2nName);
        cbIpMode = findViewById(R.id.cbHin2nIpMode);
        llIp = findViewById(R.id.llHin2nIp);
        etIp = findViewById(R.id.etHin2nIp);
        llNetmask = findViewById(R.id.llHin2nNetmask);
        etNetmask = findViewById(R.id.etHin2nNetmask);
        etCommunity = findViewById(R.id.etHin2nCommunity);
        llPassword = findViewById(R.id.llHin2nPassword);
        etPassword = findViewById(R.id.etHin2nPassword);
        etSuperNode = findViewById(R.id.etHin2nSuperNode);
        llBackup = findViewById(R.id.llHin2nBackup);
        etBackup = findViewById(R.id.etHin2nSuperNodeBackup);
        llDevDesc = findViewById(R.id.llHin2nDevDesc);
        etDevDesc = findViewById(R.id.etHin2nDevDesc);
        cbMore = findViewById(R.id.cbHin2nMore);
        llMore = findViewById(R.id.llHin2nMore);
        etMac = findViewById(R.id.etHin2nMacAddr);
        etMtu = findViewById(R.id.etHin2nMtu);
        llLocalIp = findViewById(R.id.llHin2nLocalIp);
        cbLocalIpAuto = findViewById(R.id.cbHin2nLocalIpAuto);
        etLocalIp = findViewById(R.id.etHin2nLocalIp);
        llHolePunch = findViewById(R.id.llHin2nHolePunch);
        etHolePunch = findViewById(R.id.etHin2nHolePunch);
        etLocalPort = findViewById(R.id.etHin2nLocalPort);
        cbResolve = findViewById(R.id.cbHin2nResolve);
        cbAllowRouting = findViewById(R.id.cbHin2nAllowRouting);
        cbAcceptMulticast = findViewById(R.id.cbHin2nAcceptMulticast);
        llGateway = findViewById(R.id.llHin2nGateway);
        etGateway = findViewById(R.id.etHin2nGatewayIp);
        llDns = findViewById(R.id.llHin2nDns);
        etDns = findViewById(R.id.etHin2nDnsServer);
        llEnc = findViewById(R.id.llHin2nEnc);
        spEnc = findViewById(R.id.spHin2nEnc);
        cbHeaderEnc = findViewById(R.id.cbHin2nHeaderEnc);
        spTrace = findViewById(R.id.spHin2nTrace);
        etStaticRoutes = findViewById(R.id.etHin2nStaticRoutes);

        rgVersion.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (mType == TYPE_ADD) {
                    spEnc.setSelection(checkedId == R.id.rbHin2nV3 ? 1 : 0);
                }
                updateVersionGroupCheck(checkedId);
            }
        });
        cbIpMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                etIp.setText("0.0.0.0");
                etNetmask.setText(DEFAULT_NETMASK);
                llIp.setVisibility(View.GONE);
                llNetmask.setVisibility(View.GONE);
            } else {
                llIp.setVisibility(View.VISIBLE);
                llNetmask.setVisibility(View.VISIBLE);
            }
        });
        cbMore.setOnCheckedChangeListener((buttonView, isChecked) ->
                llMore.setVisibility(isChecked ? View.VISIBLE : View.GONE));
        cbLocalIpAuto.setOnCheckedChangeListener((buttonView, isChecked) ->
                etLocalIp.setEnabled(!isChecked));
        findViewById(R.id.btnHin2nRandomMac).setOnClickListener(this);
        findViewById(R.id.btnHin2nSave).setOnClickListener(this);
        findViewById(R.id.btnHin2nCancel).setOnClickListener(this);

        etStaticRoutes.setText(Hin2nCore.staticRoutes(this));
    }

    // ---------------- 默认值 / 回填 ----------------

    private void fillDefaults() {
        // v2 已在布局中默认选中
        etIp.setText("10.0.0.8");   // 预填合法 IP，保证遥控器用户直接保存即可成功
        etSuperNode.setText(DEFAULT_SN_V2);
        etNetmask.setText(DEFAULT_NETMASK);
        etMtu.setText("1328");
        etHolePunch.setText("20");
        etLocalPort.setText("0");
        etMac.setText(EdgeCmd.getRandomMac());
        cbAcceptMulticast.setChecked(false); // 默认 dropMulticast=true
        spTrace.setSelection(2);            // 默认 tracelevel 3 → 下标 2
        spEnc.setSelection(0);              // Twofish
    }

    private void fillModify() {
        switch (mModel.getVersion()) {
            case 0: rgVersion.check(R.id.rbHin2nV1); break;
            case 1: rgVersion.check(R.id.rbHin2nV2); break;
            case 2: rgVersion.check(R.id.rbHin2nV2s); break;
            case 3: rgVersion.check(R.id.rbHin2nV3); break;
            default: break;
        }
        etName.setText(mModel.getName());
        cbIpMode.setChecked(mModel.getIpMode() == 1);
        etIp.setText(mModel.getIp());
        etNetmask.setText(mModel.getNetmask());
        etCommunity.setText(mModel.getCommunity());
        etPassword.setText(mModel.getPassword());
        etDevDesc.setText(mModel.getDevDesc());
        etSuperNode.setText(mModel.getSuperNode());
        etBackup.setText(mModel.getSuperNodeBackup());
        etMac.setText(mModel.getMacAddr());
        etMtu.setText(String.valueOf(mModel.getMtu()));
        if ("auto".equals(mModel.getLocalIP())) {
            cbLocalIpAuto.setChecked(true);
            etLocalIp.setEnabled(false);
        } else {
            cbLocalIpAuto.setChecked(false);
            etLocalIp.setText(mModel.getLocalIP());
        }
        etHolePunch.setText(String.valueOf(mModel.getHolePunchInterval()));
        cbResolve.setChecked(mModel.getResoveSupernodeIP());
        etLocalPort.setText(String.valueOf(mModel.getLocalPort()));
        cbAllowRouting.setChecked(mModel.getAllowRouting());
        cbHeaderEnc.setChecked(mModel.getHeaderEnc());
        cbAcceptMulticast.setChecked(!mModel.getDropMuticast());
        spTrace.setSelection(mModel.getTraceLevel());
        etGateway.setText(mModel.getGatewayIp());
        etDns.setText(mModel.getDnsServer());
        String enc = mModel.getEncryptionMode();
        String[] modes = {"Twofish", "AES-CBC", "ChaCha20", "Speck-CTR"};
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(enc)) {
                spEnc.setSelection(i);
                break;
            }
        }
    }

    /** 与原 hin2n 完全一致的版本联动（字段显隐 + 默认 supernode 自动替换） */
    private void updateVersionGroupCheck(int checkedId) {
        if (checkedId == R.id.rbHin2nV1) {
            cbIpMode.setVisibility(View.GONE);
            cbIpMode.setChecked(false);
            llDevDesc.setVisibility(View.GONE);
            llBackup.setVisibility(View.GONE);
            cbAcceptMulticast.setVisibility(View.GONE);
            llHolePunch.setVisibility(View.GONE);
            llLocalIp.setVisibility(View.GONE);
            llGateway.setVisibility(View.GONE);
            llDns.setVisibility(View.GONE);
            cbResolve.setVisibility(View.VISIBLE);
            llEnc.setVisibility(View.GONE);
            cbHeaderEnc.setVisibility(View.GONE);
            llPassword.setVisibility(View.GONE);
            if (isDefaultSupernode(text(etSuperNode))) {
                etSuperNode.setText(DEFAULT_SN_V1);
            }
        } else if (checkedId == R.id.rbHin2nV2) {
            cbIpMode.setVisibility(View.GONE);
            cbIpMode.setChecked(false);
            llDevDesc.setVisibility(View.GONE);
            llBackup.setVisibility(View.VISIBLE);
            cbAcceptMulticast.setVisibility(View.VISIBLE);
            llHolePunch.setVisibility(View.GONE);
            llLocalIp.setVisibility(View.GONE);
            llGateway.setVisibility(View.VISIBLE);
            llDns.setVisibility(View.VISIBLE);
            cbResolve.setVisibility(View.GONE);
            llEnc.setVisibility(View.VISIBLE);
            cbHeaderEnc.setVisibility(View.VISIBLE);
            llPassword.setVisibility(View.VISIBLE);
            if (isDefaultSupernode(text(etSuperNode))) {
                etSuperNode.setText(DEFAULT_SN_V2);
            }
        } else if (checkedId == R.id.rbHin2nV2s) {
            llDevDesc.setVisibility(View.GONE);
            cbIpMode.setVisibility(View.GONE);
            cbIpMode.setChecked(false);
            llBackup.setVisibility(View.VISIBLE);
            cbAcceptMulticast.setVisibility(View.VISIBLE);
            llHolePunch.setVisibility(View.VISIBLE);
            llLocalIp.setVisibility(View.VISIBLE);
            llGateway.setVisibility(View.GONE);
            llDns.setVisibility(View.GONE);
            cbResolve.setVisibility(View.VISIBLE);
            llEnc.setVisibility(View.GONE);
            cbHeaderEnc.setVisibility(View.GONE);
            llPassword.setVisibility(View.VISIBLE);
            if (isDefaultSupernode(text(etSuperNode))) {
                etSuperNode.setText(DEFAULT_SN_V2S);
            }
        } else if (checkedId == R.id.rbHin2nV3) {
            llDevDesc.setVisibility(View.VISIBLE);
            cbIpMode.setVisibility(View.VISIBLE);
            cbIpMode.setChecked(mType == TYPE_MODIFY && mModel != null && mModel.getIpMode() == 1);
            llBackup.setVisibility(View.VISIBLE);
            cbAcceptMulticast.setVisibility(View.VISIBLE);
            llHolePunch.setVisibility(View.GONE);
            llLocalIp.setVisibility(View.GONE);
            llGateway.setVisibility(View.VISIBLE);
            llDns.setVisibility(View.VISIBLE);
            cbResolve.setVisibility(View.GONE);
            llEnc.setVisibility(View.VISIBLE);
            cbHeaderEnc.setVisibility(View.VISIBLE);
            llPassword.setVisibility(View.VISIBLE);
            if (isDefaultSupernode(text(etSuperNode))) {
                etSuperNode.setText(DEFAULT_SN_V3);
            }
        }
    }

    private boolean isDefaultSupernode(String s) {
        return TextUtils.isEmpty(s)
                || DEFAULT_SN_V1.equals(s) || DEFAULT_SN_V2.equals(s)
                || DEFAULT_SN_V2S.equals(s) || DEFAULT_SN_V3.equals(s);
    }

    // ---------------- 保存 ----------------

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btnHin2nRandomMac) {
            etMac.setText(EdgeCmd.getRandomMac());
        } else if (id == R.id.btnHin2nSave) {
            saveOrModify(true);
        } else if (id == R.id.btnHin2nCancel) {
            finish();
        }
    }

    private void saveOrModify(boolean isSaveButton) {
        Hin2nCore.setStaticRoutes(this, text(etStaticRoutes));
        String error = checkValues();
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }

        int version = getN2nVersion();
        String name = text(etName);

        if (mType == TYPE_ADD) {
            // 重名自动加 (i)
            String original = name;
            int i = 0;
            while (Hin2nCore.configByName(name) != null) {
                i++;
                name = original + "(" + i + ")";
            }
            boolean hasSelected = false;
            for (N2NSettingModel m : Hin2nCore.configList()) {
                if (m.getIsSelcected()) {
                    hasSelected = true;
                    break;
                }
            }

            N2NSettingModel model = buildModel(null, version, name, !hasSelected);
            long id = Hin2nCore.configInsert(model);
            if (id < 0) {
                Toast.makeText(this, "保存失败：存储不可用", Toast.LENGTH_LONG).show();
                return;
            }
            if (!hasSelected) {
                Hin2nCore.setCurrentSettingId(this, id);
            }
            Toast.makeText(this, "添加成功！", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            String original = name;
            int i = 0;
            N2NSettingModel dup = Hin2nCore.configByName(name);
            while (dup != null) {
                if (dup.getId() != null && dup.getId() == mSaveId) {
                    break;
                }
                i++;
                name = original + "(" + i + ")";
                dup = Hin2nCore.configByName(name);
            }
            boolean selected = mModel != null && mModel.getIsSelcected();
            N2NSettingModel updated = buildModel(mSaveId, version, name, selected);
            Hin2nCore.configUpdate(updated);
            mModel = updated;

            boolean runningCurrent = Hin2nVpn.isRunning()
                    && Hin2nCore.currentSettingId(this) == mSaveId;
            if (runningCurrent) {
                new AlertDialog.Builder(this)
                        .setTitle("提示")
                        .setMessage("修改此配置并重新连接 n2n 网络？")
                        .setNegativeButton("否", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Toast.makeText(Hin2nConfigEditActivity.this, "保存成功！", Toast.LENGTH_SHORT).show();
                                finish();
                            }
                        })
                        .setPositiveButton("是", (dialog, which) -> {
                            N2NService.INSTANCE.stop(() -> {
                                Intent prepare = VpnService.prepare(Hin2nConfigEditActivity.this);
                                if (prepare != null) {
                                    startActivityForResult(prepare, REQ_VPN_RECONNECT);
                                } else {
                                    startReconnect();
                                }
                            });
                        })
                        .show();
            } else {
                Toast.makeText(this, "保存成功！", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startReconnect() {
        mReconnecting = true;
        Hin2nVpn.start(this, mModel);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_RECONNECT) {
            if (resultCode == Activity.RESULT_OK) {
                startReconnect();
            } else {
                Toast.makeText(this, "未获得 VPN 授权，重连已取消", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStartEvent(StartEvent event) {
        if (mReconnecting) {
            Toast.makeText(this, "修改成功，已重新连接", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onErrorEvent(ErrorEvent event) {
        if (mReconnecting) {
            mReconnecting = false;
            Toast.makeText(this, "重连失败，请检查参数与日志", Toast.LENGTH_LONG).show();
        }
    }

    private N2NSettingModel buildModel(Long id, int version, String name, boolean selected) {
        int mtu = parseInt(text(etMtu), 1328);
        int holePunch = parseInt(text(etHolePunch), 20);
        int localPort = parseInt(text(etLocalPort), 0);
        return new N2NSettingModel(
                id, version, name, cbIpMode.isChecked() ? 1 : 0,
                text(etIp), text(etNetmask),
                text(etCommunity), text(etPassword), text(etDevDesc),
                text(etSuperNode), cbMore.isChecked(),
                text(etBackup), text(etMac), mtu,
                cbLocalIpAuto.isChecked() ? "auto" : text(etLocalIp),
                holePunch, cbResolve.isChecked(), localPort,
                cbAllowRouting.isChecked(), !cbAcceptMulticast.isChecked(), false,
                spTrace.getSelectedItemPosition(), selected,
                text(etGateway), text(etDns),
                spEnc.getSelectedItem() == null ? "Twofish" : spEnc.getSelectedItem().toString(),
                cbHeaderEnc.isChecked());
    }

    /** 校验规则与原 hin2n 一致；返回 null 表示通过，否则返回中文错误提示 */
    private String checkValues() {
        int ver = getN2nVersion();
        if (TextUtils.isEmpty(etName.getText())) {
            return "请填写名称";
        }
        if (TextUtils.isEmpty(etCommunity.getText())) {
            return "请填写 Community（工作组）";
        }
        if (TextUtils.isEmpty(etSuperNode.getText())) {
            return "请填写 Supernode（主机:端口）";
        }
        if (!EdgeCmd.checkSupernode(text(etSuperNode))) {
            return "Supernode 格式不正确，应为 主机:端口（如 h.example.com:10086）";
        }
        if (!EdgeCmd.checkCommunity(text(etCommunity))) {
            return "Community 长度需在 1-20 之间";
        }
        if (!cbIpMode.isChecked()) {
            if (TextUtils.isEmpty(etIp.getText())) {
                return "请填写 IP 地址（或勾选「从 supernode 获取 IP」）";
            }
            if (!EdgeCmd.checkIPV4(text(etIp))) {
                return "IP 地址格式不正确";
            }
        }
        String mask = text(etNetmask).isEmpty() ? DEFAULT_NETMASK : text(etNetmask);
        if (!EdgeCmd.checkIPV4Mask(mask)) {
            return "子网掩码格式不正确";
        }
        if (!text(etGateway).isEmpty() && !EdgeCmd.checkIPV4(text(etGateway))) {
            return "网关 IP 格式不正确";
        }
        if (!text(etDns).isEmpty() && !EdgeCmd.checkIPV4(text(etDns))) {
            return "DNS 服务器格式不正确";
        }
        // 备用 supernode：v2 / v2s
        if ((ver == 1 || ver == 2) && !text(etBackup).isEmpty()
                && !EdgeCmd.checkSupernode(text(etBackup))) {
            cbMore.setChecked(true);
            return "备用 Supernode 格式不正确";
        }
        if (!text(etMtu).isEmpty() && !EdgeCmd.checkMtu(parseInt(text(etMtu), -1))) {
            cbMore.setChecked(true);
            return "MTU 需在 46-1500 之间";
        }
        if (ver == 2) {
            if (!text(etHolePunch).isEmpty()
                    && !EdgeCmd.checkInt(parseInt(text(etHolePunch), -1), 10, 120)) {
                cbMore.setChecked(true);
                return "打洞间隔需在 10-120 秒之间";
            }
            if (!cbLocalIpAuto.isChecked() && !text(etLocalIp).isEmpty()
                    && !EdgeCmd.checkIPV4(text(etLocalIp))) {
                cbMore.setChecked(true);
                return "本地 IP 格式不正确";
            }
        }
        if (!text(etLocalPort).isEmpty()
                && !EdgeCmd.checkInt(parseInt(text(etLocalPort), -1), 0, 65535)) {
            cbMore.setChecked(true);
            return "本地端口需在 0-65535 之间";
        }
        if (!text(etMac).isEmpty() && !EdgeCmd.checkMacAddr(text(etMac))) {
            cbMore.setChecked(true);
            return "MAC 地址格式不正确（xx:xx:xx:xx:xx:xx，或留空）";
        }
        return null;
    }

    private int getN2nVersion() {
        int id = rgVersion.getCheckedRadioButtonId();
        if (id == R.id.rbHin2nV1) return 0;
        if (id == R.id.rbHin2nV2) return 1;
        if (id == R.id.rbHin2nV2s) return 2;
        if (id == R.id.rbHin2nV3) return 3;
        return -1;
    }

    private static String text(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
