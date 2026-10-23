package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
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
        /** 滚动代次：每次启动/停止滚动自增，用于让上一次遗留的延时任务失效 */
        int scrollToken;
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
     * 渲染公告条（幂等，可反复调用：首帧、onResume、远程配置回调、首页定时 ticker 都会进来）。
     *
     * <p>2026-10-23 重写要点：</p>
     * <ol>
     *   <li><b>统一字号</b>：首页 tvNotice 用的是布局 {@code @dimen/ts_24}（设计稿 mm 单位换算出的
     *       px），而直播/点播的叠加层以前写死 {@code setTextSize(24)}（sp），同一设备上明显一大一小。
     *       现在未配置 JSON fontSize 时，三处界面统一使用 ts_24 的像素值；配置了 fontSize 时
     *       三处统一按 sp 应用，保证视觉完全一致。</li>
     *   <li><b>duration 到期不消失</b>：旧实现先 {@code setVisibility(VISIBLE)} 再做到期判断，
     *       而首页 ticker / onResume / 远程配置回调会周期性重新 render；只要有一次 render 在
     *       hideRun 之后执行，就会把已隐藏的公告重新点亮，表现为「设了 10 秒却一直显示」。
     *       现在所有「应隐藏」的判断都放在置可见之前，并且同一内容到期后置 expired=true，
     *       后续 render 直接保持隐藏，只有公告文本变化才重新计时。</li>
     *   <li><b>滚动不重排</b>：周期 render 时若文本与速度都没变，不重启跑马灯，避免每次刷新
     *       都把滚动位置拉回起点。</li>
     * </ol>
     */
    private static void render(final Activity act, final TextView tv, String screen) {
        final ScreenState ss = getOrCreate(screen);
        ss.view = tv;
        String notice = RemoteConfig.notice();
        JSONObject st = RemoteConfig.noticeStyle();

        // ① 空内容 / 当前界面未开启显示 → 隐藏，并清空内容标记（下次出现时重新计时）
        if (notice == null || notice.trim().isEmpty() || !enabledFor(screen, st)) {
            ss.content = null;
            hideScreen(ss);
            return;
        }
        // ② 过期时间（yyyy-MM-dd，当天结束前仍显示）
        String expire = st.optString("expire", "").trim();
        if (!expire.isEmpty()) {
            try {
                Date exp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(expire);
                if (exp != null && new Date().after(new Date(exp.getTime() + 24L * 3600 * 1000 - 1))) {
                    ss.content = null;
                    hideScreen(ss);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        // ③ 每日显示时间段（HH:mm，支持跨零点）
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
                    ss.content = null;
                    hideScreen(ss);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        final String text = notice.trim();
        final long now = SystemClock.uptimeMillis();
        final int duration = st.optInt("duration", 0);

        // ④ duration 计时判断：必须在「置可见」之前完成全部到期判断
        if (duration > 0) {
            if (!text.equals(ss.content)) {
                // 公告文本变化：按新内容重新计时，并清除过期标记
                ss.content = text;
                ss.deadline = now + duration * 1000L;
                ss.expired = false;
            } else if (ss.expired) {
                // 同一内容已展示到期：后续任何 render 都保持隐藏，绝不重新显示
                hideScreen(ss);
                return;
            } else if (ss.deadline <= 0) {
                // 之前是常显（duration=0）或配置刚由 0 改为 N 秒：按当前时间重新起算，
                // 否则 deadline 一直是 0 会导致公告始终判定为「已到期」而再也显示不出来
                ss.deadline = now + duration * 1000L;
            }
            if (ss.deadline - now <= 0) {
                // 计时刚好走完：标记过期并隐藏
                ss.expired = true;
                hideScreen(ss);
                return;
            }
        } else {
            // duration=0：常显，清除过期标记与隐藏任务
            cancelAutoHide(ss);
            ss.deadline = 0;
            ss.content = text;
            ss.expired = false;
        }

        // ⑤ 应用样式（三处界面共用同一套尺寸/颜色逻辑）
        // 位置：首页为 ConstraintLayout，直播/点播叠加层为 FrameLayout
        applyPosition(tv, st.optString("position", "bottom").trim());
        int bg = HomeEntry.parseColor(st.optString("bgColor", ""));
        if (bg != 0) {
            tv.setBackgroundColor(bg);
        }
        int fontColor = HomeEntry.parseColor(st.optString("fontColor", ""));
        if (fontColor != 0) {
            tv.setTextColor(fontColor);
        }
        applyFontSize(tv, st);
        try {
            if (st.has("alpha")) {
                tv.setAlpha(Math.max(0, Math.min(100, st.optInt("alpha", 100))) / 100f);
            }
        } catch (Throwable ignored) {
        }

        // ⑥ 滚动：speed>0 用自定义平滑滚动（由右向左）；否则回退系统 marquee。
        // 周期 render 时若文本与速度均未变化则保持当前滚动，避免位置被重置。
        int speed = st.optInt("speed", 0);
        if (speed > 0) {
            tv.setSelected(false);
            tv.setEllipsize(null);
            boolean sameScrolling = ss.speedRun != null && ss.speed == speed && text.equals(ss.scrollText);
            if (!sameScrolling) {
                ss.scrollText = text;
                ss.speed = speed;
                startSpeedScroll(act, tv, text, speed, ss);
            }
        } else {
            ss.scrollText = null;
            ss.speed = 0;
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

        // ⑦ 全部检查通过，确实需要显示时才置可见
        tv.setVisibility(View.VISIBLE);

        // ⑧ 重新挂一次性自动隐藏任务：使用「剩余时间」而不是完整 duration，周期 render 不会延长时间
        if (duration > 0) {
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
            H.postDelayed(ss.hideRun, Math.max(1, ss.deadline - now));
        }
    }

    /**
     * 应用公告字号：三处界面（首页布局 TextView / 直播与点播动态叠加层）统一规则。
     * JSON fontSize>0 时按 sp 应用（三处一致）；未配置时使用首页同款 ts_24 像素值，
     * 修复叠加层写死 24sp 导致与首页文字大小不一致的问题。
     */
    private static void applyFontSize(TextView tv, JSONObject st) {
        int fontSize = st.optInt("fontSize", 0);
        if (fontSize > 0) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        } else {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    tv.getResources().getDimensionPixelSize(R.dimen.ts_24));
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
        // 默认字号与首页 tvNotice 保持一致（@dimen/ts_24，mm→px）：旧实现写死 24sp，
        // 与首页布局字体单位不同，导致同一台设备上三处公告文字大小不一致。
        // render() 中若 JSON 配了 fontSize，会再按 sp 统一覆盖三处界面。
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                act.getResources().getDimensionPixelSize(R.dimen.ts_24));
        tv.setFocusable(false);
        tv.setClickable(false);
        tv.setVisibility(View.GONE);
        content.addView(tv);
        return tv;
    }

    // ==================== 自定义速度滚动（由右向左） ====================

    /**
     * 启动「由右向左」的自定义平滑滚动。
     *
     * <p>实现方式：把原文重复拼接若干份（保证任意时刻屏内都有文字），再用 Scroller 平移一个
     * 周期后归零重来，形成无缝循环。像素速度由 JSON speed 配置（像素/秒）。</p>
     *
     * <p>防串扰：每次启动/停止都会让 scrollToken 自增，旧的 60ms 延时任务与旧的 300ms 循环
     * 任务（通过 speedRun 身份比对）都会失效，避免快速换内容时出现多条滚动循环叠加。</p>
     */
    private static void startSpeedScroll(final Activity act, final TextView tv, final String text, final int speedPxPerSec, final ScreenState ss) {
        stopSpeedScroll(ss);
        tv.setScroller(null);
        tv.scrollTo(0, 0);
        tv.setTranslationX(0f);
        tv.setText(text);
        final int token = ++ss.scrollToken;
        tv.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 期间又启动了新的滚动：本次任务作废
                if (ss.scrollToken != token) {
                    return;
                }
                // 页面已销毁：不再操作旧 View，避免无意义的重排/泄漏
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                int viewWidth = tv.getWidth();
                if (viewWidth <= 0) {
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
                // 用局部变量持有自身引用：只有它仍是当前 speedRun 时才继续循环，
                // 防止上一段滚动被替换后旧循环仍在后台重复发送任务（多循环叠加导致滚动抖动）
                final Runnable loop = new Runnable() {
                    @Override
                    public void run() {
                        if (ss.speedRun != this) {
                            return;
                        }
                        if (scroller.isFinished()) {
                            tv.scrollTo(0, 0);
                            scroller.startScroll(0, 0, periodPx, 0, Math.max(1, duration));
                        }
                        H.postDelayed(this, 300);
                    }
                };
                ss.speedRun = loop;
                H.postDelayed(loop, 300);
            }
        }, 60);
    }

    private static void stopSpeedScroll(ScreenState ss) {
        ss.scrollToken++;   // 让尚未执行的延时任务作废
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
