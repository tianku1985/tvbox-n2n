package com.github.tvbox.osc.n2n.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import java.util.List;

import wang.switchy.hin2n.Hin2nCore;
import wang.switchy.hin2n.model.EdgeStatus;
import wang.switchy.hin2n.model.N2NSettingInfo;
import wang.switchy.hin2n.service.N2NService;
import wang.switchy.hin2n.storage.db.base.N2NSettingModelDao;
import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

/**
 * hin2n TV 界面共用的 VPN 启动 / 运行状态 / 当前配置切换辅助。
 */
public class Hin2nVpn {

    /** n2n 服务是否处于非断开状态（连接中 / 已连接 / supernode 断开重连中） */
    public static boolean isRunning() {
        if (N2NService.INSTANCE == null) {
            return false;
        }
        EdgeStatus.RunningStatus s = N2NService.INSTANCE.getCurrentStatus();
        return s != null
                && s != EdgeStatus.RunningStatus.DISCONNECT
                && s != EdgeStatus.RunningStatus.FAILED;
    }

    /** 用指定配置启动前台 VpnService（调用前需已完成 VpnService.prepare 授权） */
    public static void start(Context ctx, N2NSettingModel model) {
        if (ctx == null || model == null) {
            return;
        }
        Intent intent = new Intent(ctx.getApplicationContext(), N2NService.class);
        Bundle bundle = new Bundle();
        bundle.putParcelable("n2nSettingInfo", new N2NSettingInfo(model));
        intent.putExtra("Setting", bundle);
        ContextCompat.startForegroundService(ctx.getApplicationContext(), intent);
    }

    public static void stop() {
        if (N2NService.INSTANCE != null) {
            N2NService.INSTANCE.stop(null);
        }
    }

    /**
     * 把指定配置标记为当前配置（DB isSelcected + SP current_setting_id）。
     */
    public static void markSelected(Context ctx, long targetId) {
        N2NSettingModelDao dao = Hin2nCore.settingDao();
        if (dao == null) {
            return;
        }
        List<N2NSettingModel> all = dao.loadAll();
        for (N2NSettingModel m : all) {
            boolean selected = m.getId() != null && m.getId() == targetId;
            if (m.getIsSelcected() != selected) {
                m.setIsSelcected(selected);
                dao.update(m);
            }
        }
        Hin2nCore.setCurrentSettingId(ctx, targetId);
    }
}
