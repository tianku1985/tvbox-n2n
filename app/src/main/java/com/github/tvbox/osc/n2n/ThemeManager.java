package com.github.tvbox.osc.n2n;

import android.app.Activity;
import android.util.Log;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主题管理：主题清单（名称/英文别名/主色/深色/渐变背景）全部数据化，
 * 默认内置 14 个颜色主题（与 styles.xml 中 14 个 XML 主题一一对应），
 * 远程/本地 JSON 的 "themes" 数组可以：
 *   1) 通过 "style": <0..13> 或 alias/name 匹配，覆盖内置主题的名称、主色、渐变背景；
 *   2) 追加 style 缺省的自定义主题（颜色任意）。
 *
 * 索引布局保持稳定：0..13 永远是内置主题（可能被改色/改名），14+ 是 JSON 追加的自定义主题，
 * 因此 Hawk 中保存的 THEME_SELECT 整数索引在升级后不会错位。
 *
 * 渲染说明（Android 限制）：布局/drawable 中的 ?attr/color_theme 只能取 XML style 里的颜色，
 * 无法在运行时注入任意色值。因此自定义/被改色主题套用「颜色最接近的内置 XML style」，
 * 保证焦点框等 attr drawable 呈现接近色相；而渐变背景、代码内 getThemeColor() 取色、
 * 图标着色等均使用 JSON 里的精确颜色。
 */
public class ThemeManager {

    public static final String K_THEMES = "rc_themes"; // JSON 主题定义数组（远程/本地配置写入）

    /** 单个主题定义 */
    public static class Theme {
        public String name;
        public String alias;
        public int color;     // 主色 color_theme（ARGB）
        public int dark;      // 深色基色（color_theme_dark_70 的 RGB 部分，ARGB）
        public int bgStart;   // 渐变背景起点（深色）
        public int bgEnd;     // 渐变背景终点（浅色/主色）
        public int styleRes;  // 内置 XML 主题资源 id；自定义主题为 -1
        public int builtin;   // 内置序号 0..13；自定义为 -1
        public String[] bgImages; // 背景图片 URL 列表（一个或多个；空=用渐变背景）

        boolean isCustom() {
            return builtin < 0;
        }
    }

    // ---------------- 内置默认值（与 res/values/styles.xml 严格对应，勿随意改顺序） ----------------

    private static final String[] BUILTIN_NAMES = {
            "红色", "蓝色", "深蓝", "橙色", "黄色", "品红", "粉色",
            "翠绿", "青色", "紫色", "琥珀", "靛蓝", "玫红", "石墨"
    };

    private static final String[] BUILTIN_ALIASES = {
            "red", "blue", "darkblue", "orange", "yellow", "magenta", "pink",
            "emerald", "teal", "violet", "amber", "indigo", "rose", "graphite"
    };

    private static final int[] STYLES = {
            R.style.RedTheme, R.style.BlueTheme, R.style.DarkBlueTheme, R.style.OrangeTheme,
            R.style.YellowTheme, R.style.MagentaTheme, R.style.PinkTheme,
            R.style.EmeraldTheme, R.style.TealTheme, R.style.VioletTheme, R.style.AmberTheme,
            R.style.IndigoTheme, R.style.RoseTheme, R.style.GraphiteTheme
    };

    /** styles.xml 中 color_theme 的精确颜色 */
    private static final int[] BUILTIN_COLORS = {
            0xFFD81F26, 0xFF18A2E7, 0xFF004B93, 0xFFFF7439, 0xFFFFD55E, 0xFFFF2F70, 0xFFFD9BDB,
            0xFF00C08B, 0xFF17B9B0, 0xFF9A6BF2, 0xFFF5A524, 0xFF5B5BD6, 0xFFE11D48, 0xFF6B7280
    };

    /** styles.xml 中 color_theme_dark_70 的 RGB 基色 */
    private static final int[] BUILTIN_DARK = {
            0xFF8C1418, 0xFF106D9C, 0xFF002547, 0xFFB35127, 0xFFB39542, 0xFFB3204E, 0xFFB06B98,
            0xFF007A58, 0xFF0E7A74, 0xFF6747A8, 0xFFA86E12, 0xFF2E2E90, 0xFF700E24, 0xFF3D4250
    };

    /** 每个主题默认的渐变背景颜色对（深色到浅色） */
    private static final int[][] BG_PAIRS = {
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

    // ---------------- 运行时主题清单 ----------------

    private static volatile List<Theme> sThemes;

    /** 自定义点播界面独立主题覆盖（JSON 入口 "theme" 字段）；null=无覆盖，全部页面用全局主题 */
    private static volatile Integer sOverrideIdx = null;

    /**
     * 开启主题覆盖：自定义点播入口打开 CustomVodActivity 前调用，
     * 该界面及其打开的播放页在覆盖期间使用独立主题（不影响全局 THEME_SELECT）。
     * 无法识别的值不覆盖。HomeActivity 创建时自动取消覆盖（视为回到 app 全局状态）。
     */
    public static void pushOverride(String theme) {
        int idx = indexFromConfig(theme);
        if (idx >= 0) {
            sOverrideIdx = idx;
        }
    }

    /** 取消主题覆盖（幂等） */
    public static void popOverride() {
        sOverrideIdx = null;
    }

    /** 当前生效主题索引：有独立主题覆盖时返回覆盖值，否则返回全局选择 */
    private static int activeIndex() {
        Integer ov = sOverrideIdx;
        return ov != null ? clamp(ov) : index();
    }

    private static List<Theme> themes() {
        if (sThemes == null) {
            synchronized (ThemeManager.class) {
                if (sThemes == null) {
                    sThemes = buildBuiltins();
                    String raw = Hawk.get(K_THEMES, "");
                    if (raw != null && !raw.isEmpty()) {
                        applyJsonLocked(raw);
                    }
                }
            }
        }
        return sThemes;
    }

    private static List<Theme> buildBuiltins() {
        List<Theme> list = new ArrayList<>();
        for (int i = 0; i < BUILTIN_NAMES.length; i++) {
            Theme t = new Theme();
            t.name = BUILTIN_NAMES[i];
            t.alias = BUILTIN_ALIASES[i];
            t.color = BUILTIN_COLORS[i];
            t.dark = BUILTIN_DARK[i];
            t.bgStart = BG_PAIRS[i][0];
            t.bgEnd = BG_PAIRS[i][1];
            t.styleRes = STYLES[i];
            t.builtin = i;
            list.add(t);
        }
        return list;
    }

    /**
     * 用 JSON 数组重建主题清单：先恢复内置默认，再逐条覆盖/追加。
     * 同时把原始 JSON 落盘（Hawk），下次启动直接生效。
     */
    public static void updateThemes(JSONArray arr) {
        if (arr == null) {
            return;
        }
        synchronized (ThemeManager.class) {
            Hawk.put(K_THEMES, arr.toString());
            sThemes = buildBuiltins();
            applyJsonLocked(arr.toString());
            resetBgPick();
        }
    }

    private static void applyJsonLocked(String raw) {
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "").trim();
                String alias = o.optString("alias", "").trim().toLowerCase(Locale.US);
                int color = parseColor(o.optString("color", ""));
                int dark = parseColor(o.optString("dark", ""));
                int bgS = parseColor(o.optString("bgStart", ""));
                int bgE = parseColor(o.optString("bgEnd", ""));
                int styleIdx = -1;
                if (o.has("style")) {
                    styleIdx = o.optInt("style", -1);
                }
                // 背景图片：bgImages 数组（一个或多个），兼容单字符串 bgImage
                String[] bgImages = parseBgImages(o);

                // 定位要覆盖的内置主题：style 索引优先，其次 alias/name
                int hit = -1;
                if (styleIdx >= 0 && styleIdx < sThemes.size() && sThemes.get(styleIdx).builtin >= 0) {
                    hit = styleIdx;
                } else if (!alias.isEmpty()) {
                    for (int j = 0; j < BUILTIN_ALIASES.length; j++) {
                        if (BUILTIN_ALIASES[j].equals(alias)) {
                            hit = j;
                            break;
                        }
                    }
                }
                if (hit < 0 && !name.isEmpty()) {
                    for (int j = 0; j < BUILTIN_NAMES.length; j++) {
                        if (BUILTIN_NAMES[j].equals(name)) {
                            hit = j;
                            break;
                        }
                    }
                }

                if (hit >= 0) {
                    Theme t = sThemes.get(hit);
                    if (!name.isEmpty()) {
                        t.name = name;
                    }
                    if (!alias.isEmpty()) {
                        t.alias = alias;
                    }
                    if (color != 0) {
                        t.color = 0xFF000000 | color;
                    }
                    if (dark != 0) {
                        t.dark = 0xFF000000 | dark;
                    }
                    if (bgS != 0) {
                        t.bgStart = 0xFF000000 | bgS;
                    }
                    if (bgE != 0) {
                        t.bgEnd = 0xFF000000 | bgE;
                    }
                    // bgEnd 缺省时与主色同步
                    if (bgE == 0 && color != 0) {
                        t.bgEnd = 0xFF000000 | color;
                    }
                    if (bgImages != null) {
                        t.bgImages = bgImages;
                    }
                } else {
                    // 追加自定义主题
                    if (color == 0) {
                        continue;
                    }
                    Theme t = new Theme();
                    t.name = name.isEmpty() ? ("自定义" + (sThemes.size() - BUILTIN_NAMES.length + 1)) : name;
                    t.alias = alias;
                    int argb = 0xFF000000 | color;
                    t.color = argb;
                    t.dark = dark != 0 ? 0xFF000000 | dark : scaleColor(argb, 0.55f);
                    t.bgStart = bgS != 0 ? 0xFF000000 | bgS : scaleColor(argb, 0.24f);
                    t.bgEnd = bgE != 0 ? 0xFF000000 | bgE : argb;
                    t.styleRes = -1;
                    t.builtin = -1;
                    t.bgImages = bgImages;
                    sThemes.add(t);
                }
            }
        } catch (Throwable th) {
            Log.i("ThemeManager", "themes json parse failed: " + th.getMessage());
        }
    }

    // ---------------- 对外查询 ----------------

    public static int count() {
        return themes().size();
    }

    public static int index() {
        return clamp(Hawk.get(HawkConfig.THEME_SELECT, 0));
    }

    private static Theme at(int i) {
        List<Theme> list = themes();
        if (i < 0 || i >= list.size()) {
            return list.get(0);
        }
        return list.get(i);
    }

    /** 在 Activity 创建时套用当前主题（setTheme 必须在 setContentView 前调用） */
    public static void apply(Activity act) {
        if (act == null) {
            return;
        }
        // 回到 app 全局界面（HomeActivity 新建=从桌面重启/重新进入主界面）时取消独立主题覆盖
        if (sOverrideIdx != null && act instanceof com.github.tvbox.osc.ui.activity.HomeActivity) {
            popOverride();
        }
        try {
            act.setTheme(styleToApply(at(activeIndex())));
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
        return at(index).name;
    }

    /** 获取指定主题的渐变背景色对 */
    public static int[] bgPair(int index) {
        Theme t = at(index);
        return new int[]{t.bgStart, t.bgEnd};
    }

    /** 获取当前主题的渐变背景色对 */
    public static int[] currentBgPair() {
        return bgPair(activeIndex());
    }

    /**
     * 代码取色（BaseActivity.getThemeColor）用：当前主题声明的精确主色与所套 XML style 的
     * attr 颜色不一致时（自定义主题 / 被 JSON 改色的内置主题），返回精确色；一致时返回 null，
     * 由调用方继续走 ?attr/color_theme。
     */
    public static Integer primaryColorOverride() {
        try {
            Theme t = at(activeIndex());
            int bound = boundColor(styleToApply(t));
            int exact = 0xFFFFFF & t.color;
            if (bound != exact) {
                return t.color;
            }
        } catch (Throwable ignored) {
        }
        return null;
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
        List<Theme> list = themes();
        for (int i = 0; i < list.size(); i++) {
            Theme th = list.get(i);
            if (th.name.equals(t) || (th.alias != null && th.alias.equals(lower))) {
                return i;
            }
        }
        return -1;
    }

    // ---------------- 内部工具 ----------------

    /** 自定义主题 / 改色主题实际套用的 XML style：取主色 RGB 距离最近的内置 style */
    private static int styleToApply(Theme t) {
        if (t.styleRes != 0 && t.builtin >= 0) {
            // 内置主题：颜色未改时用自身 style；被 JSON 改过色则就近匹配，保证 attr 色相尽量接近
            if ((0xFFFFFF & t.color) == (0xFFFFFF & BUILTIN_COLORS[t.builtin])) {
                return t.styleRes;
            }
        }
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < BUILTIN_COLORS.length; i++) {
            long d = colorDistance(t.color, BUILTIN_COLORS[i]);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return STYLES[best];
    }

    /** 某个 XML style 绑定的内置 attr 主色（用于判断是否需要代码层覆盖取色） */
    private static int boundColor(int styleRes) {
        for (int i = 0; i < STYLES.length; i++) {
            if (STYLES[i] == styleRes) {
                return 0xFFFFFF & BUILTIN_COLORS[i];
            }
        }
        return 0xFFFFFF & BUILTIN_COLORS[0];
    }

    private static long colorDistance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static int scaleColor(int argb, float factor) {
        int a = (argb >> 24) & 0xFF;
        int r = Math.round(((argb >> 16) & 0xFF) * factor);
        int g = Math.round(((argb >> 8) & 0xFF) * factor);
        int b = Math.round((argb & 0xFF) * factor);
        return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** 解析 #RRGGBB / #AARRGGBB；非法返回 0 */
    private static int parseColor(String s) {
        try {
            if (s == null) {
                return 0;
            }
            s = s.trim();
            if (s.isEmpty()) {
                return 0;
            }
            long v = Long.parseLong(s.replace("#", ""), 16);
            if (s.length() <= 7) {
                v |= 0xFF000000L;
            }
            return (int) v;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int clamp(int i) {
        int n = count();
        if (i < 0 || i >= n) {
            return 0;
        }
        return i;
    }

    /**
     * 解析主题背景图片配置："bgImages": ["url1", "url2", ...]（一个或多个），
     * 兼容单字符串 "bgImage": "url"。无配置返回 null（不覆盖已有值）。
     */
    private static String[] parseBgImages(JSONObject o) {
        try {
            JSONArray arr = o.optJSONArray("bgImages");
            if (arr != null) {
                List<String> urls = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String u = arr.optString(i, "").trim();
                    if (!u.isEmpty()) {
                        urls.add(u);
                    }
                }
                return urls.isEmpty() ? null : urls.toArray(new String[0]);
            }
            String single = o.optString("bgImage", "").trim();
            if (!single.isEmpty()) {
                return new String[]{single};
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 每个主题已选定的背景图（多张时随机选一张后保持稳定，避免每次进入页面闪烁换图）
    private static final java.util.Map<Integer, String> sBgPick = new java.util.HashMap<>();

    /**
     * 当前主题应使用的背景图 URL；未配置返回 null（调用方回退渐变背景）。
     * 一个主题配置了多张图时随机选取一张，并在该主题索引下缓存保持稳定。
     */
    public static String currentBgImage() {
        try {
            int idx = activeIndex();
            Theme t = at(idx);
            if (t.bgImages == null || t.bgImages.length == 0) {
                return null;
            }
            if (t.bgImages.length == 1) {
                return t.bgImages[0];
            }
            synchronized (sBgPick) {
                String picked = sBgPick.get(idx);
                if (picked == null) {
                    picked = t.bgImages[new java.util.Random().nextInt(t.bgImages.length)];
                    sBgPick.put(idx, picked);
                }
                return picked;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 主题清单被 JSON 重建后调用，清掉已失效的背景图选取缓存 */
    public static void resetBgPick() {
        synchronized (sBgPick) {
            sBgPick.clear();
        }
    }
}
