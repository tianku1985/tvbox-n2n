package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.Scroller;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.github.tvbox.osc.R;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * 滚动公告：样式（位置/背景色/字号字色/透明度/显示时间段/过期时间/滚动速度）全部来自
 * 远程 JSON 的 notice 段，可按界面（首页 / 直播 / 点播）分别控制是否显示。
 *
 * 滚动方向为「由右向左」（标准跑马灯方向）。
 * 首页复用布局里已有的 tvNotice；直播/点播播放页则在本页内容层动态挂一个公告条。
 */
public class NoticeBar {

    public static final String SCREEN_HOME = "home";
    public static final String SCREEN_LIVE = "live";
    public static final String SCREEN_VOD = "vod";

    private static final String TAG_PREFIX = "tvbox_notice_bar_";

    private static final Handler H = new Handler(Looper.getMainLooper());
    /** 每屏（home/live/vod）独立状态，避免跨屏互踩和 duration 计时被 ticker 重置 */
    private static final HashMap<String, ScreenState> STATES = new HashMap<>();

    private static final class ScreenState {
        TextView view;
        Runnable speedRun;
        Runnable hideRun;
        /** 当前显示文本，用于判断 ticker 重新渲染是否同一内容 */
        String content;
        /** hideRun 预定执行时间（uptimeMillis），0 表示常显或无隐藏任务 */
        long deadline;
        /** 当前内容已展示到期：同内容再次 render 时保持隐藏，不再重新计时（修复主界面常显） */
        boolean expired;
    }

    private static ScreenState getOrCreate(String screen) {
        ScreenState ss = STATES.get(screen);
        if (ss == null) {
            ss = new ScreenState();
            STATES.put(screen, ss);
        }
        return ss;
    }

    /** 在指定界面渲染公告条（幂等，可重复调用） */
    public static void show(Activity act, String screen) {
        if (act == null) {
            return;
        }
        TextView tv = SCREEN_HOME.equals(screen) ? act.findViewById(R.id.tvNotice) : obtainOverlay(act, screen);
        if (tv == null) {
            return;
        }
        render(act, tv, screen);
    }

    /** 主动隐藏（离开播放页时调用） */
    public static void hide(Activity act, String screen) {
        if (act == null) {
            return;
        }
        ScreenState ss = STATES.get(screen);
        if (ss != null) {
            hideScreen(ss);
            STATES.remove(screen);
        }
        TextView tv = SCREEN_HOME.equals(screen) ? act.findViewById(R.id.tvNotice) : findOverlay(act, screen);
        if (tv != null) {
            tv.setVisibility(View.GONE);
        }
    }

    /** 停止自定义滚动（Activity.onDestroy 调用，避免静态泄漏） */
    public static void stopScroll() {
        for (ScreenState ss : STATES.values()) {
            cancelAutoHide(ss);
            stopSpeedScroll(ss);
        }
    }

    // ==================== 渲染 ====================

    private static void render(final Activity act, final TextView tv, String screen) {
        final ScreenState ss = getOrCreate(screen);
        ss.view = tv;
        String notice = RemoteConfig.notice();
        JSONObject st = RemoteConfig.noticeStyle();
        if (notice == null || notice.trim().isEmpty() || !enabledFor(screen, st)) {
            hideScreen(ss);
            return;
        }
        // 过期时间（yyyy-MM-dd，过期不显示）
        String expire = st.optString("expire", "").trim();
        if (!expire.isEmpty()) {
            try {
                Date exp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expire);
                if (exp != null && new Date().after(new Date(exp.getTime() + 24L * 3600 * 1000 - 1))) {
                    hideScreen(ss);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // 显示时间段（HH:mm，可跨零点）
        String timeStart = st.optString("timeStart", "").trim();
        String timeEnd = st.optString("timeEnd", "").trim();
        if (!timeStart.isEmpty() && !timeEnd.isEmpty()) {
            try {
                SimpleDateFormat hf = new SimpleDateFormat("HH:mm", Locale.US);
                int now = toMinute(hf.format(new Date()));
                int s = toMinute(timeStart);
                int e = toMinute(timeEnd);
                boolean in = (s <= e) ? (now >= s && now <= e) : (now >= s || now <= e);
                if (!in) {
                    hideScreen(ss);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        final String text = notice.trim();
        tv.setText(text);
        // 位置：首页为 ConstraintLayout，直播/点播叠加层为 FrameLayout
        applyPosition(tv, st.optString("position", "bottom").trim());
        // 背景色 / 字体颜色 / 字体大小 / 透明度
        int bg = HomeEntry.parseColor(st.optString("bgColor", ""));
        if (bg != 0) {
            tv.setBackgroundColor(bg);
        }
        int fontColor = HomeEntry.parseColor(st.optString("fontColor", ""));
        if (fontColor != 0) {
            tv.setTextColor(fontColor);
        }
        int fontSize = st.optInt("fontSize", 0);
        if (fontSize > 0) {
            tv.setTextSize(fontSize);
        }
        try {
            if (st.has("alpha")) {
                tv.setAlpha(Math.max(0, Math.min(100, st.optInt("alpha", 100))) / 100f);
            }
        } catch (Throwable ignored) {
        }
        tv.setVisibility(View.VISIBLE);
        // 滚动速度：speed>0 用自定义平滑滚动（由右向左）；否则回退系统 marquee
        int speed = st.optInt("speed", 0);
        if (speed > 0) {
            tv.setSelected(false);
            tv.setEllipsize(null);
            startSpeedScroll(act, tv, text, speed, ss);
        } else {
            stopSpeedScroll(ss);
            tv.setScroller(null);
            tv.scrollTo(0, 0);
            tv.setTranslationX(0f);
            tv.setText(text);   // 恢复原始文本（自定义滚动会写入重复拼接后的长文本）
            try {
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            } catch (Throwable ignored) {
            }
            tv.setSelected(true);   // 选中态才会触发 marquee 滚动
        }
        // duration：从「该内容首次展示」起计时；ticker 周期性重渲染同一内容时不重置计时，
        // 已到期的立即隐藏并保持（expired 防止主界面周期 render 重新武装 deadline 导致永不消失）。
        int duration = st.optInt("duration", 0);
        if (duration > 0) {
            long now = SystemClock.uptimeMillis();
            if (!text.equals(ss.content)) {
                // 新内容：重新计时并清除过期标记
                ss.content = text;
                ss.deadline = now + duration * 1000L;
                ss.expired = false;
            } else if (ss.expired || ss.deadline <= 0) {
                // 同一内容已展示到期：保持隐藏，不再重新显示
                ss.expired = true;
                hideScreen(ss);
                return;
            }
            long remain = ss.deadline - now;
            if (remain <= 0) {
                ss.expired = true;
                hideScreen(ss);
                return;
            }
            cancelAutoHide(ss);
            final TextView ftv = tv;
            ss.hideRun = new Runnable() {
                @Override
                public void run() {
                    ss.hideRun = null;
                    ss.deadline = 0;
                    ss.expired = true;
                    ftv.setVisibility(View.GONE);
                    stopSpeedScroll(ss);
                }
            };
            H.postDelayed(ss.hideRun, remain);
        } else {
            cancelAutoHide(ss);
            ss.deadline = 0;
            ss.content = text;
            ss.expired = false;
        }
    }

    private static void hideScreen(ScreenState ss) {
        cancelAutoHide(ss);
        stopSpeedScroll(ss);
        ss.deadline = 0;
        if (ss.view != null) {
            ss.view.setVisibility(View.GONE);
        }
    }

    private static void cancelAutoHide(ScreenState ss) {
        if (ss.hideRun != null) {
            H.removeCallbacks(ss.hideRun);
            ss.hideRun = null;
        }
    }

    private static void applyPosition(TextView tv, String position) {
        boolean top = "top".equals(position);
        try {
            if (tv.getLayoutParams() instanceof ConstraintLayout.LayoutParams) {
                ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) tv.getLayoutParams();
                int topMargin = tv.getResources().getDimensionPixelSize(R.dimen.vs_20);
                int bottomMargin = tv.getResources().getDimensionPixelSize(R.dimen.vs_10);
                if (top) {
                    lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET;
                    lp.topMargin = topMargin;
                    lp.bottomMargin = 0;
                } else {
                    lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
                    lp.topMargin = 0;
                    lp.bottomMargin = bottomMargin;
                }
                tv.setLayoutParams(lp);
            } else if (tv.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tv.getLayoutParams();
                lp.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
                tv.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 显示开关 ====================

    /** 该界面是否显示公告：读取 notice.show.home/live/vod，默认三个界面均显示 */
    private static boolean enabledFor(String screen, JSONObject st) {
        boolean def = true;
        try {
            JSONObject show = st.optJSONObject("show");
            if (show != null && show.has(screen)) {
                return show.optBoolean(screen, def);
            }
            String camel = "showOn" + Character.toUpperCase(screen.charAt(0)) + screen.substring(1);
            if (st.has(camel)) {
                return st.optBoolean(camel, def);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    // ==================== 动态叠加层（直播/点播） ====================

    private static TextView findOverlay(Activity act, String screen) {
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) {
            return null;
        }
        View exist = content.findViewWithTag(TAG_PREFIX + screen);
        return exist instanceof TextView ? (TextView) exist : null;
    }

    private static TextView obtainOverlay(Activity act, String screen) {
        TextView exist = findOverlay(act, screen);
        if (exist != null) {
            return exist;
        }
        ViewGroup content = act.findViewById(android.R.id.content);
        if (content == null) {
            return null;
        }
        TextView tv = new TextView(act);
        tv.setTag(TAG_PREFIX + screen);
        // 公告条占满屏幕整宽（无左右边距）
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                act.getResources().getDimensionPixelSize(R.dimen.vs_40));
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = act.getResources().getDimensionPixelSize(R.dimen.vs_10);
        tv.setLayoutParams(lp);
        int padH = act.getResources().getDimensionPixelSize(R.dimen.vs_15);
        tv.setPadding(padH, 0, padH, 0);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tv.setMarqueeRepeatLimit(-1);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setTextColor(0xFFFFD54F);
        tv.setBackgroundColor(0x66000000);
        tv.setTextSize(24);
        tv.setFocusable(false);
        tv.setClickable(false);
        tv.setVisibility(View.GONE);
        content.addView(tv);
        return tv;
    }

    // ==================== 自定义速度滚动（由右向左） ====================

    private static void startSpeedScroll(final Activity act, final TextView tv, final String text, final int speedPxPerSec, final ScreenState ss) {
        stopSpeedScroll(ss);
        tv.setScroller(null);
        tv.scrollTo(0, 0);
        tv.setTranslationX(0f);
        tv.setText(text);
        tv.postDelayed(new Runnable() {
            @Override
            public void run() {
                int viewWidth = tv.getWidth();
                if (viewWidth <= 0) {
                    if (act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    tv.postDelayed(this, 200);
                    return;
                }
                // 重复周期（文本 + 间隔）不小于一个屏宽：任意时刻屏内都有文字，不会只剩背景
                String unit = text + "        ";
                float unitWidth = tv.getPaint().measureText(unit);
                if (unitWidth <= 0) {
                    return;
                }
                int copies = 1;
                while (unitWidth * copies < viewWidth + unitWidth) {
                    copies++;
                }
                StringBuilder sb = new StringBuilder(unit.length() * copies);
                for (int i = 0; i < copies; i++) {
                    sb.append(unit);
                }
                tv.setText(sb.toString());
                // 由右向左：每个周期平移一个 periodPx，回到起点时内容与终点重合，无缝衔接
                final int periodPx = (int) Math.ceil(unitWidth);
                final int duration = (int) (periodPx / (float) Math.max(1, speedPxPerSec) * 1000f);
                final Scroller scroller = new Scroller(act, new LinearInterpolator());
                tv.setScroller(scroller);
                tv.scrollTo(0, 0);
                scroller.startScroll(0, 0, periodPx, 0, Math.max(1, duration));
                ss.speedRun = new Runnable() {
                    @Override
                    public void run() {
                        if (ss.speedRun == null) {
                            return;
                        }
                        if (scroller.isFinished()) {
                            tv.scrollTo(0, 0);
                            scroller.startScroll(0, 0, periodPx, 0, Math.max(1, duration));
                        }
                        H.postDelayed(ss.speedRun, 300);
                    }
                };
                H.postDelayed(ss.speedRun, 300);
            }
        }, 60);
    }

    private static void stopSpeedScroll(ScreenState ss) {
        if (ss.speedRun != null) {
            H.removeCallbacks(ss.speedRun);
            ss.speedRun = null;
        }
    }

    private static int toMinute(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
    }
}
