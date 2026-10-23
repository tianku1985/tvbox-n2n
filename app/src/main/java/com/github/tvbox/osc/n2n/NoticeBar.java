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
        /** 正在滚动的内容文本：周期 render 时用于判断是否需要重启滚动（避免跑马灯位置被重置） */
        String scrollText;
        /** 正在使用的滚动速度（像素/秒），与 scrollText 一起作为「是否需要重启滚动」的判断依据 */
        int speed;
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

    /**
     * 渲染公告条（幂等，可重复调用）。
     *
     * 修复要点（2026-10-22）：
     *  1. 字号统一：首页 tvNotice 用 @dimen/ts_24（mm 单位），直播/点播动态叠加层原本用
     *     setTextSize(24)（sp 单位），两者在同一设备上渲染大小不同。现叠加层默认字号改用
     *     与首页相同的 ts_24 尺寸（getDimensionPixelSize），保证三处字号一致；JSON fontSize
     *     非零时仍以 JSON 为准（sp 单位）。
     *  2. duration 到期不消失：原代码在做过期/时间段判断之前就执行 tv.setVisibility(VISIBLE)，
     *     导致即使已到期也会先闪显再隐藏；更严重的是周期 render（onResume/远程配置回调）
     *     会在 hideRun 把视图置 GONE 后立刻又置 VISIBLE，造成「永不消失」。
     *     修复：把 setVisibility(VISIBLE) 移到所有「应隐藏」判断之后，只有确实需要显示
     *     时才置 VISIBLE；同内容到期后保持 expired=true，后续 render 直接 return 不触显。
     */
    private static void render(final Activity act, final TextView tv, String screen) {
        final ScreenState ss = getOrCreate(screen);
        ss.view = tv;
        String notice = RemoteConfig.notice();
        JSONObject st = RemoteConfig.noticeStyle();
        // 空内容 / 该界面未开启 → 隐藏
        if (notice == null || notice.trim().isEmpty() || !enabledFor(screen, st)) {
            hideScreen(ss);
            ss.content = null; // 清除内容标记，下次有内容时按新内容重新计时（避免同文本回归时被误判过期）
            return;
        }
        // 过期时间（yyyy-MM-dd，当天结束前仍显示，过期不显示）
        String expire = st.optString("expire", "").trim();
        if (!expire.isEmpty()) {
            try {
                Date exp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expire);
                if (exp != null && new Date().after(new Date(exp.getTime() + 24L * 3600 * 1000 - 1))) {
                    hideScreen(ss);
                    ss.content = null;
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
                    ss.content = null;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        final String text = notice.trim();
        final long now = SystemClock.uptimeMillis();
        final int duration = st.optInt("duration", 0);
        // —— duration 计时判断（必须在 setVisibility(VISIBLE) 之前，避免到期后被重新置可见）——
        if (duration > 0) {
            if (!text.equals(ss.content)) {
                // 新内容：重新计时，清除过期标记
                ss.content = text;
                ss.deadline = now + duration * 1000L;
                ss.expired = false;
            } else if (ss.expired || ss.deadline <= 0) {
                // 同一内容已展示到期：保持隐藏，绝不重新显示
                hideScreen(ss);
                return;
            }
            long remain = ss.deadline - now;
            if (remain <= 0) {
                // 正好到期：标记过期并隐藏
                ss.expired = true;
                hideScreen(ss);
                return;
            }
            // 未到期：（重新）挂载一次性隐藏任务，用剩余时间而不是 duration，避免周期 render 延长显示
            cancelAutoHide(ss);
            final TextView ftv = tv;
            ss.hideRun = new Runnable() {
                @Override
                public void run() {
                    ss.hideRun = null;
                    ss.deadline = 0;
                    ss.expired = true;
                    if (ftv != null) {
                        ftv.setVisibility(View.GONE);
                    }
                    stopSpeedScroll(ss);
                }
            };
            H.postDelayed(ss.hideRun, remain);
        } else {
            // duration=0：常显，清除过期标记与隐藏任务
            cancelAutoHide(ss);
            ss.deadline = 0;
            ss.content = text;
            ss.expired = false;
        }
        // —— 到此处说明公告需要显示，统一应用样式后再置可见 ——
        applyPosition(tv, st.optString("position", "bottom").trim());
        // 背景色 / 字体颜色
        int bg = HomeEntry.parseColor(st.optString("bgColor", ""));
        if (bg != 0) {
            tv.setBackgroundColor(bg);
        }
        int fontColor = HomeEntry.parseColor(st.optString("fontColor", ""));
        if (fontColor != 0) {
            tv.setTextColor(fontColor);
        }
        // 字体大小：JSON 配了 fontSize(>0) 用 JSON（sp）；否则统一用首页同款 ts_24（mm→px），
        // 保证首页/直播/点播三处字号一致。
        int fontSize = st.optInt("fontSize", 0);
        if (fontSize > 0) {
            tv.setTextSize(fontSize);
        } else {
            try {
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                        tv.getResources().getDimensionPixelSize(R.dimen.ts_24));
            } catch (Throwable ignored) {
                tv.setTextSize(24);
            }
        }
        try {
            if (st.has("alpha")) {
                tv.setAlpha(Math.max(0, Math.min(100, st.optInt("alpha", 100))) / 100f);
            }
        } catch (Throwable ignored) {
        }
        // 滚动速度：speed>0 用自定义平滑滚动（由右向左）；否则回退系统 marquee。
        // 周期 render（首页定时 ticker / 远程配置回调）会反复进来，若文本与速度都没变，
        // 不能重启滚动，否则每次刷新都会把跑马灯位置重置到起点（视觉上「一卡一卡」）。
        int speed = st.optInt("speed", 0);
        boolean sameScrolling = ss.speedRun != null && ss.speed == speed && text.equals(ss.scrollText);
        if (speed > 0) {
            tv.setSelected(false);
            tv.setEllipsize(null);
            if (!sameScrolling) {
                startSpeedScroll(act, tv, text, speed, ss);
                ss.scrollText = text;
                ss.speed = speed;
            }
        } else {
            ss.scrollText = null;
            ss.speed = 0;
            stopSpeedScroll(ss);
            tv.setScroller(null);
            tv.scrollTo(0, 0);
            tv.setTranslationX(0f);
            tv.setText(text);
            try {
                tv.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            } catch (Throwable ignored) {
            }
            tv.setSelected(true);
        }
        // 最后再置可见：确保到期/禁用的公告不会闪显
        tv.setVisibility(View.VISIBLE);
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
        // 默认字号与首页 tvNotice 一致（@dimen/ts_24），避免首页/直播/点播三处渲染大小不一；
        // render() 中若 JSON 配了 fontSize 会再覆盖。
        try {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    act.getResources().getDimensionPixelSize(R.dimen.ts_24));
        } catch (Throwable ignored) {
            tv.setTextSize(24);
        }
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
