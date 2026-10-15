package com.github.tvbox.osc.n2n;

import android.app.Activity;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import java.util.Locale;

/**
 * 主题管理：集中维护全部配色主题，支持一键切换与 JSON 下发指定主题。
 * 每个主题都有对应的渐变背景色，切换主题时背景也自动切换。
 *
 * - {@link #apply(Activity)} 在 Activity 创建时按当前索引套用主题（BaseActivity 调用）
 * - {@link #cycle()} 一键切到下一个主题（首页主题图标调用）
 * - {@link #indexFromConfig(String)} 解析远程 JSON 的 theme 字段（支持索引/中文名/英文名）
 */
public class ThemeManager {

    /** 主题中文显示名（颜色名，顺序即索引，新增主题请同时补充 styles.xml 中的 style） */
    public static final String[] NAMES = {
            "红色", "蓝色", "深蓝", "橙色", "黄色", "品红", "粉色",
            "翠绿", "青色", "紫色", "琥珀", "靛蓝", "玫红", "石墨"
    };

    /** 与 NAMES 一一对应的主题资源 */
    private static final int[] STYLES = {
            R.style.RedTheme, R.style.BlueTheme, R.style.DarkBlueTheme, R.style.OrangeTheme,
            R.style.YellowTheme, R.style.MagentaTheme, R.style.PinkTheme,
            R.style.EmeraldTheme, R.style.TealTheme, R.style.VioletTheme, R.style.AmberTheme,
            R.style.IndigoTheme, R.style.RoseTheme, R.style.GraphiteTheme
    };

    /** 英文别名（JSON 里 theme 可写英文 key） */
    private static final String[] ALIASES = {
            "red", "blue", "darkblue", "orange", "yellow", "magenta", "pink",
            "emerald", "teal", "violet", "amber", "indigo", "rose", "graphite"
    };

    /**
     * 每个主题对应的渐变背景颜色对（深色到浅色）。
     * 用于 BaseActivity.changeWallpaper() 动态生成 GradientDrawable 作为 window 背景。
     * 格式：[startColor, endColor]
     */
    public static final int[][] BG_PAIRS = {
            {0xFF3D0A0D, 0xFFD81F26},  // 红色 Red
            {0xFF0A3D5C, 0xFF18A2E7},  // 蓝色 Blue
            {0xFF001B40, 0xFF004B93},  // 深蓝 DarkBlue
            {0xFF4A2210, 0xFFFF7439},  // 橙色 Orange
            {0xFF4A3D1C, 0xFFFFD55E},  // 黄色 Yellow
            {0xFF4A0D22, 0xFFFF2F70},  // 品红 Magenta
            {0xFF4A3044, 0xFFFD9BDB},  // 粉色 Pink
            {0xFF0A3D2B, 0xFF00C08B},  // 翠绿 Emerald
            {0xFF0A3D3D, 0xFF17B9B0},  // 青色 Teal
            {0xFF3A2760, 0xFF9A6BF2},  // 紫色 Violet
            {0xFF4A320B, 0xFFF5A524},  // 琥珀 Amber
            {0xFF222250, 0xFF5B5BD6},  // 靛蓝 Indigo
            {0xFF4A0F1F, 0xFFE11D48},  // 玫红 Rose
            {0xFF2A2E38, 0xFF6B7280}   // 石墨 Graphite
    };

    public static int count() {
        return NAMES.length;
    }

    public static int index() {
        return clamp(Hawk.get(HawkConfig.THEME_SELECT, 0));
    }

    /** 在 Activity 创建时套用当前主题 */
    public static void apply(Activity act) {
        if (act == null) {
            return;
        }
        try {
            act.setTheme(STYLES[index()]);
        } catch (Throwable ignored) {
        }
    }

    /** 一键切换到下一个主题，返回切换后的索引 */
    public static int cycle() {
        int next = (index() + 1) % count();
        Hawk.put(HawkConfig.THEME_SELECT, next);
        return next;
    }

    public static String name(int index) {
        int i = clamp(index);
        return NAMES[i];
    }

    /** 获取指定主题的渐变背景色对 */
    public static int[] bgPair(int index) {
        int i = clamp(index);
        return BG_PAIRS[i];
    }

    /** 获取当前主题的渐变背景色对 */
    public static int[] currentBgPair() {
        return bgPair(index());
    }

    /** 解析远程 JSON 的 theme 字段：数字索引 / 中文名 / 英文别名，无法识别返回 -1 */
    public static int indexFromConfig(String theme) {
        if (theme == null) {
            return -1;
        }
        String t = theme.trim();
        if (t.isEmpty()) {
            return -1;
        }
        try {
            int idx = Integer.parseInt(t);
            return (idx >= 0 && idx < count()) ? idx : -1;
        } catch (Throwable ignored) {
        }
        String lower = t.toLowerCase(Locale.US);
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(t) || ALIASES[i].equals(lower)) {
                return i;
            }
        }
        return -1;
    }

    private static int clamp(int i) {
        if (i < 0 || i >= count()) {
            return 0;
        }
        return i;
    }
}
