package com.github.tvbox.osc.n2n;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;

import com.github.tvbox.osc.R;

import java.lang.reflect.Field;

/**
 * Android 4.4（API<21）drawable 主题适配。
 *
 * drawable/ 目录（API<21 使用）无法引用 ?attr 主题色（运行时解析不到会直接崩溃），
 * 只能硬编码默认主题蓝 #18A2E7；drawable-v21/ 才用 ?attr/color_theme*。
 * 因此在 API<21 上，Activity 创建时把已加载 drawable 的共享常量状态（ConstantState，
 * 进程级共享，之后 inflate 的列表项/弹窗也复用它）里 RGB=18A2E7 的颜色就地替换为当前
 * 主题色（保留 alpha），让 4.4 下图标背景、焦点框同样随主题切换变色。
 *
 * vector 不在此列：?attr tint 会触发 API19 资源预加载崩溃（见 docs/tvbox-dev.md §7），
 * 保持蓝色兜底（legacy 变体经 generatedDensities 出 PNG）。
 */
public class ThemeDrawableFix {

    /** drawable/ 下硬编码主题蓝 #18A2E7 的非 vector 资源 */
    private static final int[] TINTABLE = new int[]{
            R.drawable.button_detail_all,
            R.drawable.button_detail_collect,
            R.drawable.button_home_sort_focus,
            R.drawable.button_home_select,
            R.drawable.button_dialog_vod,
            R.drawable.item_right_bg,
            R.drawable.play_progress_horizontal,
            R.drawable.play_progress_vertical,
            R.drawable.shape_live_focus,
            R.drawable.shape_player_control_vod_seek_thumb_normal,
            R.drawable.shape_player_control_vod_seek,
            R.drawable.shape_live_select,
            R.drawable.shape_player_control_vod_seek_thumb_press,
            R.drawable.shape_toplayout_tvname,
            R.drawable.shape_speed_hint,
            R.drawable.shape_user_search,
            R.drawable.shape_source_series_focus,
            R.drawable.shape_thumb_radius,
            R.drawable.shape_source_flag_line,
            R.drawable.shape_user_home,
            R.drawable.shape_source_flag_focus,
            R.drawable.shape_user_focus,
            R.drawable.shape_setting_model_focus,
    };

    private static final int BLUE_RGB = 0x18A2E7;

    /** 当前已写入共享状态里的主题色 RGB（初始为 XML 硬编码蓝），用于主题多次切换时连续替换 */
    private static int sAppliedRgb = BLUE_RGB;

    /** 在 Activity setTheme 之后调用（BaseActivity.onCreate） */
    public static void apply(Context ctx) {
        if (Build.VERSION.SDK_INT >= 21 || ctx == null) {
            return;
        }
        int themeRgb = resolveThemeColor(ctx) & 0xFFFFFF;
        if (themeRgb == sAppliedRgb) {
            return;
        }
        Resources res = ctx.getResources();
        for (int id : TINTABLE) {
            try {
                tint(res.getDrawable(id), sAppliedRgb, themeRgb);
            } catch (Throwable ignored) {
            }
        }
        sAppliedRgb = themeRgb;
    }

    private static int resolveThemeColor(Context ctx) {
        try {
            Integer override = ThemeManager.primaryColorOverride();
            if (override != null) {
                return override;
            }
            TypedArray a = ctx.obtainStyledAttributes(R.styleable.themeColor);
            try {
                return a.getColor(R.styleable.themeColor_color_theme, 0xFF000000 | BLUE_RGB);
            } finally {
                a.recycle();
            }
        } catch (Throwable ignored) {
            return 0xFF000000 | BLUE_RGB;
        }
    }

    /** 就地改共享常量状态：故意不 mutate()，让使用该资源的所有现存/未来实例一起变色 */
    private static void tint(Drawable d, int fromRgb, int toRgb) {
        if (d == null) {
            return;
        }
        if (d instanceof GradientDrawable) {
            tintGradient((GradientDrawable) d, fromRgb, toRgb);
            return;
        }
        if (d instanceof DrawableContainer) {
            // StateListDrawable / LevelListDrawable 等：children 在共享常量状态里
            try {
                DrawableContainer.DrawableContainerState st =
                        (DrawableContainer.DrawableContainerState) d.getConstantState();
                if (st != null) {
                    for (Drawable c : st.getChildren()) {
                        tint(c, fromRgb, toRgb);
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }
        // LayerDrawable / ClipDrawable / ScaleDrawable 等：公开访问器要 API23+，4.4 走反射扫描子 drawable
        tintFields(d, fromRgb, toRgb, 0);
    }

    private static void tintGradient(GradientDrawable g, int fromRgb, int toRgb) {
        try {
            Object st = g.getConstantState();
            if (st == null) {
                return;
            }
            Field f = findField(st.getClass(), "mSolidColors");
            if (f == null) {
                return;
            }
            f.setAccessible(true);
            ColorStateList colors = (ColorStateList) f.get(st);
            if (colors == null) {
                return;
            }
            int cur = colors.getDefaultColor();
            if ((cur & 0xFFFFFF) == fromRgb) {
                g.setColor((cur & 0xFF000000) | toRgb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 递归扫描对象中的 Drawable 字段（含数组元素、状态对象），深度限制防循环 */
    private static void tintFields(Object obj, int fromRgb, int toRgb, int depth) {
        if (obj == null || depth > 3) {
            return;
        }
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    Class<?> t = f.getType();
                    f.setAccessible(true);
                    if (Drawable.class.isAssignableFrom(t)) {
                        Drawable inner = (Drawable) f.get(obj);
                        if (inner != null && inner != obj) {
                            tint(inner, fromRgb, toRgb);
                        }
                    } else if (t.isArray() && t.getComponentType() != null && !t.getComponentType().isPrimitive()) {
                        Object arr = f.get(obj);
                        if (arr instanceof Object[]) {
                            for (Object e : (Object[]) arr) {
                                if (e != null && e.getClass().getName().startsWith("android.graphics.drawable")) {
                                    tintFields(e, fromRgb, toRgb, depth + 1);
                                }
                            }
                        }
                    } else if (!t.isPrimitive() && t.getName().startsWith("android.graphics.drawable")) {
                        // 包装类的状态对象（如 ClipDrawable.mClipState）
                        Object inner = f.get(obj);
                        if (inner != null && inner != obj) {
                            tintFields(inner, fromRgb, toRgb, depth + 1);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                k = k.getSuperclass();
            }
        }
        return null;
    }
}
