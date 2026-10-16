package com.github.tvbox.osc.n2n;

import android.graphics.Color;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import wang.switchy.hin2n.event.ConnectingEvent;
import wang.switchy.hin2n.event.ErrorEvent;
import wang.switchy.hin2n.event.StartEvent;
import wang.switchy.hin2n.event.StopEvent;
import wang.switchy.hin2n.event.SupernodeDisconnectEvent;

/**
 * hin2n 入口状态机：订阅 :hin2n 模块 EventBus 的 5 个运行事件，
 * 把当前连接状态折叠为 IDLE / CONNECTING / CONNECTED / ERROR 四态，
 * 供首页顶栏 tvHin2n 图标与历史行 tvHin2nHome 卡片染色。
 *
 * 状态流转：
 *   ConnectingEvent            -> CONNECTING（拨号中）
 *   StartEvent                 -> CONNECTED（已连接）
 *   StopEvent                  -> IDLE（已断开，未拨号）
 *   SupernodeDisconnectEvent   -> ERROR（与超节点断开）
 *   ErrorEvent                 -> ERROR（拨号失败）
 *
 * 注册时机：App.onCreate 调用 {@link #register()}，常驻整个进程；
 * 事件回调在主线程，UI 直接读 {@link #current()} 染色即可。
 */
public final class Hin2nEntryState {

    public enum State {
        /** 未拨号 / 已断开 */ IDLE,
        /** 拨号中 */ CONNECTING,
        /** 已连接 */ CONNECTED,
        /** 拨号失败 / 异常断开 */ ERROR
    }

    /** 状态变化事件（app 内广播，UI 订阅后刷新入口图标颜色） */
    public static class ChangedEvent {
        public final State state;

        public ChangedEvent(State state) {
            this.state = state;
        }
    }

    private static volatile State sState = State.IDLE;
    private static final Object BUS = new Object() {
        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onConnecting(ConnectingEvent e) {
            set(State.CONNECTING);
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onStart(StartEvent e) {
            set(State.CONNECTED);
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onStop(StopEvent e) {
            set(State.IDLE);
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onSupernodeDisconnect(SupernodeDisconnectEvent e) {
            set(State.ERROR);
        }

        @Subscribe(threadMode = ThreadMode.MAIN)
        public void onError(ErrorEvent e) {
            set(State.ERROR);
        }
    };

    private static boolean sRegistered = false;

    private Hin2nEntryState() {
    }

    /** App.onCreate 调一次；重复调用幂等 */
    public static synchronized void register() {
        if (sRegistered) {
            return;
        }
        try {
            EventBus.getDefault().register(BUS);
            sRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    public static State current() {
        return sState;
    }

    private static void set(State s) {
        if (sState == s) {
            return;
        }
        sState = s;
        try {
            EventBus.getDefault().post(new ChangedEvent(s));
        } catch (Throwable ignored) {
        }
    }

    /** 状态对应图标染色；IDLE 返回 0 表示清除 colorFilter 用原色 */
    public static int colorFor(State s) {
        if (s == null) {
            return 0;
        }
        switch (s) {
            case CONNECTING:
                return Color.parseColor("#FFC107"); // 黄：拨号中
            case CONNECTED:
                return Color.parseColor("#4CAF50"); // 绿：已连接
            case ERROR:
                return Color.parseColor("#F44336"); // 红：失败/断开
            case IDLE:
            default:
                return 0;                            // 未拨号：用主题默认色
        }
    }
}
