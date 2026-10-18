package wang.switchy.hin2n.ui;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import wang.switchy.hin2n.R;

import java.util.ArrayList;
import java.util.List;

import wang.switchy.hin2n.Hin2nCore;
import wang.switchy.hin2n.service.N2NService;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

/**
 * hin2n 配置管理（TV）：
 * 点击整行 = 切换当前配置（运行中会询问是否用新配置重连）；
 * 行内按键：编辑 / 复制 / 删除；顶部可新建配置。
 */
public class Hin2nConfigListActivity extends BaseActivity {

    private static final int REQ_VPN_SWITCH = 0x1301;

    private RecyclerView mRecyclerView;
    private TextView mEmpty;
    private ConfigAdapter mAdapter;
    /** 切换配置重连流程中，VPN 授权完成后要连接的配置 id */
    private long mPendingStartId = -1L;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_hin2n_list;
    }

    @Override
    protected void init() {
        mRecyclerView = findViewById(R.id.rvHin2nList);
        mEmpty = findViewById(R.id.tvHin2nListEmpty);
        findViewById(R.id.btnHin2nListAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Hin2nConfigListActivity.this, Hin2nConfigEditActivity.class)
                        .putExtra("type", Hin2nConfigEditActivity.TYPE_ADD));
            }
        });
        findViewById(R.id.btnHin2nListBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new ConfigAdapter();
        mRecyclerView.setAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<N2NSettingModel> list = Hin2nCore.configList();
        mEmpty.setText(Hin2nCore.dbError() != null
                ? "数据库异常，已启用备用存储，配置可正常保存使用"
                : "暂无配置，按「新建配置」创建第一条 n2n 配置");
        mEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        mAdapter.setData(list);
        // 空列表时遥控器焦点落到「新建配置」（列表不可见，无焦点目标）
        if (list.isEmpty()) {
            findViewById(R.id.btnHin2nListAdd).requestFocus();
        }
    }

    // ---------------- 行操作 ----------------

    private void onSelect(final N2NSettingModel model) {
        if (model == null || model.getId() == null) {
            return;
        }
        long currentId = Hin2nCore.currentSettingId(this);
        if (model.getId() == currentId) {
            Toast.makeText(this, "「" + model.getName() + "」已是当前配置", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Hin2nVpn.isRunning()) {
            new AlertDialog.Builder(this)
                    .setTitle("切换配置")
                    .setMessage("n2n 正在运行，是否断开当前网络并使用「" + model.getName() + "」重新连接？")
                    .setNegativeButton("否", null)
                    .setPositiveButton("是", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Hin2nVpn.markSelected(Hin2nConfigListActivity.this, model.getId());
                            reconnectWithPrepare(model.getId());
                        }
                    })
                    .show();
        } else {
            Hin2nVpn.markSelected(this, model.getId());
            Toast.makeText(this, "已切换到「" + model.getName() + "」", Toast.LENGTH_SHORT).show();
            reload();
        }
    }

    private void reconnectWithPrepare(long id) {
        N2NSettingModel model = Hin2nCore.configById(id);
        if (model == null) {
            reload();
            return;
        }
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) {
            mPendingStartId = id;
            startActivityForResult(prepare, REQ_VPN_SWITCH);
        } else {
            stopThenStart(model);
            reload();
        }
    }

    /** 等旧 edge 完全停止的回调里再启动新配置（避免运行态互斥） */
    private void stopThenStart(final N2NSettingModel target) {
        if (N2NService.INSTANCE != null) {
            N2NService.INSTANCE.stop(new Runnable() {
                @Override
                public void run() {
                    Hin2nVpn.start(Hin2nConfigListActivity.this, target);
                }
            });
        } else {
            Hin2nVpn.start(this, target);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN_SWITCH) {
            long id = mPendingStartId;
            mPendingStartId = -1L;
            if (resultCode == RESULT_OK && id >= 0) {
                N2NSettingModel model = Hin2nCore.configById(id);
                if (model != null) {
                    stopThenStart(model);
                }
            } else {
                Toast.makeText(this, "未获得 VPN 授权，已取消重连", Toast.LENGTH_SHORT).show();
            }
            reload();
        }
    }

    private void onEdit(N2NSettingModel model) {
        startActivity(new Intent(this, Hin2nConfigEditActivity.class)
                .putExtra("type", Hin2nConfigEditActivity.TYPE_MODIFY)
                .putExtra("saveId", model.getId()));
    }

    private void onCopy(final N2NSettingModel model) {
        try {
            String base = model.getName() + "(1)";
            int i = 1;
            while (Hin2nCore.configByName(base) != null) {
                i++;
                base = model.getName() + "(" + i + ")";
            }
            N2NSettingModel copy = new N2NSettingModel(
                    null, model.getVersion(), base, model.getIpMode(), model.getIp(), model.getNetmask(),
                    model.getCommunity(), model.getPassword(), model.getDevDesc(), model.getSuperNode(),
                    model.getMoreSettings(), model.getSuperNodeBackup(), model.getMacAddr(), model.getMtu(),
                    model.getLocalIP(), model.getHolePunchInterval(), model.getResoveSupernodeIP(),
                    model.getLocalPort(), model.getAllowRouting(), model.getDropMuticast(), model.getUseHttpTunnel(),
                    model.getTraceLevel(), false, model.getGatewayIp(), model.getDnsServer(),
                    model.getEncryptionMode(), model.getHeaderEnc());
            Hin2nCore.configInsert(copy);
            Toast.makeText(this, "已复制为「" + base + "」", Toast.LENGTH_SHORT).show();
            reload();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onDelete(final N2NSettingModel model) {
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除「" + model.getName() + "」？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        boolean isCurrent = model.getId() != null
                                && model.getId() == Hin2nCore.currentSettingId(Hin2nConfigListActivity.this);
                        if (isCurrent && Hin2nVpn.isRunning()) {
                            Hin2nVpn.stop();
                        }
                        if (model.getId() != null) {
                            Hin2nCore.configDelete(model.getId());
                        }
                        if (isCurrent) {
                            List<N2NSettingModel> rest = Hin2nCore.configList();
                            if (!rest.isEmpty()) {
                                Hin2nVpn.markSelected(Hin2nConfigListActivity.this, rest.get(0).getId());
                            } else {
                                Hin2nCore.setCurrentSettingId(Hin2nConfigListActivity.this, -1L);
                            }
                        }
                        Toast.makeText(Hin2nConfigListActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .show();
    }

    // ---------------- RecyclerView 适配器 ----------------

    private class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.Holder> {
        private final List<N2NSettingModel> mData = new ArrayList<>();
        private long mCurrentId = -1L;

        void setData(List<N2NSettingModel> list) {
            mData.clear();
            mData.addAll(list);
            mCurrentId = Hin2nCore.currentSettingId(Hin2nConfigListActivity.this);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hin2n_config, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final N2NSettingModel model = mData.get(position);
            holder.name.setText(model.getName());
            String[] ver = {"v1", "v2", "v2s", "v3"};
            String v = model.getVersion() >= 0 && model.getVersion() < ver.length ? ver[model.getVersion()] : "?";
            holder.detail.setText(v + "　" + model.getSuperNode() + "　" + model.getCommunity());
            holder.selected.setVisibility(model.getId() != null && model.getId() == mCurrentId ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onSelect(model);
                }
            });
            holder.edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onEdit(model);
                }
            });
            holder.copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onCopy(model);
                }
            });
            holder.delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onDelete(model);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            TextView name, detail, selected, edit, copy, delete;

            Holder(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tvHin2nItemName);
                detail = itemView.findViewById(R.id.tvHin2nItemDetail);
                selected = itemView.findViewById(R.id.tvHin2nItemSelected);
                edit = itemView.findViewById(R.id.btnHin2nItemEdit);
                copy = itemView.findViewById(R.id.btnHin2nItemCopy);
                delete = itemView.findViewById(R.id.btnHin2nItemDelete);
            }
        }
    }
}
