package com.github.tvbox.osc.base;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.PermissionChecker;
import com.blankj.utilcode.util.ActivityUtils;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.callback.EmptyCallback;
import com.github.tvbox.osc.callback.LoadingCallback;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LocaleHelper;
import com.kingja.loadsir.callback.Callback;
import com.kingja.loadsir.core.LoadService;
import com.kingja.loadsir.core.LoadSir;
import com.orhanobut.hawk.Hawk;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import io.github.inflationx.viewpump.ViewPumpContextWrapper;
import me.jessyan.autosize.AutoSizeCompat;
import me.jessyan.autosize.internal.CustomAdapt;
import xyz.doikki.videoplayer.util.CutoutUtil;

/**
 * @author pj567
 * @date :2020/12/17
 * @description:
 */
public abstract class BaseActivity extends AppCompatActivity implements CustomAdapt {
    protected Context mContext;
    private LoadService mLoadService;

    private static float screenRatio = -100.0f;

    // takagen99 : Fix for Locale change not persist on higher Android version
    @Override
    protected void attachBaseContext(Context base) {
        Context newBase = base;
        if (App.viewPump != null) {
            newBase = ViewPumpContextWrapper.wrap(base, App.viewPump);
        }

        if (Hawk.get(HawkConfig.HOME_LOCALE, 0) == 0) {
            super.attachBaseContext(LocaleHelper.onAttach(newBase, "zh"));
        } else {
            super.attachBaseContext(LocaleHelper.onAttach(newBase, ""));
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            if (screenRatio < 0) {
                DisplayMetrics dm = new DisplayMetrics();
                getWindowManager().getDefaultDisplay().getMetrics(dm);
                int screenWidth = dm.widthPixels;
                int screenHeight = dm.heightPixels;
                screenRatio = (float) Math.max(screenWidth, screenHeight) / (float) Math.min(screenWidth, screenHeight);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }

        // takagen99 : Set Theme Color（主题索引与配色集中在 ThemeManager，支持 JSON 下发与一键切换）
        com.github.tvbox.osc.n2n.ThemeManager.apply(this);
        // Android 4.4：把 drawable/ 里硬编码的主题蓝就地替换为当前主题色（API>=21 走 ?attr 无需处理）
        com.github.tvbox.osc.n2n.ThemeDrawableFix.apply(this);

        super.onCreate(savedInstanceState);
        setContentView(getLayoutResID());
        mContext = this;
        CutoutUtil.adaptCutoutAboveAndroidP(mContext, true);//设置刘海
        // 触屏/鼠标设备适配：允许触摸改变焦点（touchscreenBlocksFocus=false），
        // 这样平板/手机上点击按钮时焦点能正确移动，配合 focusableInTouchMode 即可显示焦点高亮。
        // TV 盒子（无触屏）不受影响——此属性仅在有触摸事件时才生效。
        enableTouchFocusIfNeeded();
        AppManager.getInstance().addActivity(this);
        init();
        // 远程 JSON 的 home.bgImage 对所有页面统一生效（未配置时本方法内部直接跳过）
        try {
            com.github.tvbox.osc.n2n.HomeEntry.applyBackground(this);
        } catch (Throwable ignored) {
        }
        setScreenOn();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI(true);
        changeWallpaper(false);
        // 远程配置在后台拉到新版本时可能没有前台页面，回到任意页面后补触发下载安装
        try {
            com.github.tvbox.osc.n2n.RemoteConfig.tryPendingUpdate(this);
        } catch (Throwable ignored) {
        }
    }

    // takagen99 : Check for Gesture or 3-Buttons NavBar
    // 0 : 3-Button NavBar
    // 1 : 2-Button NavBar (Android P)
    // 2 : Gesture full screen
    public static int isEdgeToEdgeEnabled(Context context) {
        Resources resources = context.getResources();
        int resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (resourceId > 0) {
            return resources.getInteger(resourceId);
        }
        return 0;
    }

    public void hideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            //    uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            //    uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    public void vidHideSysBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiOptions = getWindow().getDecorView().getSystemUiVisibility();
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiOptions |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiOptions |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(uiOptions);
        }
    }

    public void hideSystemUI(boolean shownavbar) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            uiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            uiVisibility |= View.SYSTEM_UI_FLAG_LOW_PROFILE;
            uiVisibility |= View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiVisibility |= View.SYSTEM_UI_FLAG_IMMERSIVE;
            uiVisibility |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            if (!shownavbar) {
                uiVisibility |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
                uiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            }
            getWindow().getDecorView().setSystemUiVisibility(uiVisibility);
            // set content behind navigation bar
//        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
    }

    public void showSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int uiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            uiVisibility &= ~View.SYSTEM_UI_FLAG_LOW_PROFILE;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_FULLSCREEN;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_IMMERSIVE;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            uiVisibility &= ~View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(uiVisibility);
        }
    }

    @Override
    public Resources getResources() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AutoSizeCompat.autoConvertDensityOfCustomAdapt(super.getResources(), this);
        }
        return super.getResources();
    }

    public boolean hasPermission(String permission) {
        boolean has = true;
        try {
            has = PermissionChecker.checkSelfPermission(this, permission) == PermissionChecker.PERMISSION_GRANTED;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return has;
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void setLoadSir(View view) {
        if (mLoadService == null) {
            mLoadService = LoadSir.getDefault().register(view, new Callback.OnReloadListener() {
                @Override
                public void onReload(View v) {
                }
            });
        }
    }

    protected void showLoading() {
        if (mLoadService != null) {
            mLoadService.showCallback(LoadingCallback.class);
        }
    }

    protected boolean isLoading() {
        if (mLoadService != null && mLoadService.getCurrentCallback() != null) {
            return mLoadService.getCurrentCallback().equals(LoadingCallback.class);
        }
        return false;
    }

    protected void showEmpty() {
        if (null != mLoadService) {
            mLoadService.showCallback(EmptyCallback.class);
        }
    }

    protected void showSuccess() {
        if (null != mLoadService) {
            mLoadService.showSuccess();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppManager.getInstance().finishActivity(this);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz) {
        Intent intent = new Intent(mContext, clazz);
        startActivity(intent);
    }

    public void jumpActivity(Class<? extends BaseActivity> clazz, Bundle bundle) {
    	if (DetailActivity.class.isAssignableFrom(clazz) && Hawk.get(HawkConfig.BACKGROUND_PLAY_TYPE, 0) == 2) {
            //1.重新打开singleTask的页面(关闭小窗) 2.关闭画中画，重进detail再开启画中画会闪退
            ActivityUtils.finishActivity(DetailActivity.class);
        }
        Intent intent = new Intent(mContext, clazz);
        intent.putExtras(bundle);
        startActivity(intent);
    }

    protected String getAssetText(String fileName) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            AssetManager assets = getAssets();
            BufferedReader bf = new BufferedReader(new InputStreamReader(assets.open(fileName)));
            String line;
            while ((line = bf.readLine()) != null) {
                stringBuilder.append(line);
            }
            return stringBuilder.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    @Override
    public float getSizeInDp() {
        return isBaseOnWidth() ? 1280 : 720;
    }

    @Override
    public boolean isBaseOnWidth() {
        return !(screenRatio >= 4.0f);
    }

    public boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    public boolean supportsTouch() {
        return getPackageManager().hasSystemFeature("android.hardware.touchscreen");
    }

    /**
     * 触屏/鼠标设备适配（平板/手机/外接鼠标）。
     * 在支持触屏的设备上：
     *  1. 关闭 touchscreenBlocksFocus：让触摸/鼠标点击能移动焦点（TV 盒子默认开启，触屏设备需关闭）；
     *  2. 遍历内容视图，把所有已设置 clickable/focusable 的视图补上 focusableInTouchMode，
     *     使其在触屏设备上点击时获得焦点并显示高亮态；
     *  3. 不改变纯 TV 设备的行为（无触屏硬件时 supportsTouch=false，直接跳过）。
     * 鼠标在 Android 上等价于触摸事件，因此本方法同时覆盖鼠标支持。
     */
    private void enableTouchFocusIfNeeded() {
        if (!supportsTouch()) {
            return;
        }
        // 关闭 TOUCHSCREEN_BLOCKS_FOCUS 窗口标志：让触摸/鼠标点击能移动焦点
        // （Android 默认开启此标志，TV 盒子无触屏没问题，平板/手机上会导致点击不移动焦点）。
        // 注：Window 类没有公开的 setTouchscreenBlocksFocus 方法，它是 ViewRootImpl 的隐藏 API，
        // 必须通过 WindowManager.LayoutParams.FLAG_TOUCHSCREEN_BLOCKS_FOCUS 配合 setFlags 清除。
        // FLAG_TOUCHSCREEN_BLOCKS_FOCUS = 0x00200000（API 21 引入，legacy 变体 minSdk=19 需版本保护）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                if (getWindow() != null) {
                    // setFlags(flags, mask) 会把 flags 中 mask 为 1 的位置写入窗口参数；
                    // flags=0 会把 mask 对应的位清零，即关闭此标志。
                    getWindow().setFlags(0, LayoutParams.FLAG_TOUCHSCREEN_BLOCKS_FOCUS);
                }
            } catch (Throwable ignored) {
            }
        }
        // 递归给已可点击的视图补上 focusableInTouchMode，使触屏点击有焦点反馈。
        // 注意：init() 中还会动态创建入口卡片等视图，因此除立即执行一次外，
        // 再在布局完成后（content.post）补执行一次，覆盖动态添加的视图。
        try {
            View root = findViewById(android.R.id.content);
            if (root instanceof ViewGroup) {
                final ViewGroup group = (ViewGroup) root;
                applyFocusableInTouchMode(group);
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        applyFocusableInTouchMode(group);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** 递归遍历视图树，为已 clickable+focusable 的视图添加 focusableInTouchMode */
    private void applyFocusableInTouchMode(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (child.isClickable() && child.isFocusable()) {
                child.setFocusableInTouchMode(true);
            }
            if (child instanceof ViewGroup) {
                applyFocusableInTouchMode((ViewGroup) child);
            }
        }
    }

    public void setScreenBrightness(float amt) {
        WindowManager.LayoutParams lparams = getWindow().getAttributes();
        lparams.screenBrightness = amt;
        getWindow().setAttributes(lparams);
    }

    public void setScreenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    public void setScreenOff() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // takagen99: Added Theme Color
    public int getThemeColor() {
        // JSON 自定义/改色主题：优先返回配置里的精确主色（attr drawable 已套最接近的内置 style）
        Integer override = com.github.tvbox.osc.n2n.ThemeManager.primaryColorOverride();
        if (override != null) {
            return override;
        }
        TypedArray a = mContext.obtainStyledAttributes(R.styleable.themeColor);
        int themeColor = a.getColor(R.styleable.themeColor_color_theme, 0);
        return themeColor;
    }

    protected static GradientDrawable globalWp = null;

    /** 重置全局渐变背景缓存，让下次 changeWallpaper 强制重新生成（主题切换时调用） */
    public static void resetWallpaper() {
        globalWp = null;
    }

    public void changeWallpaper(boolean force) {
        if (!force && globalWp != null) {
            try {
                getWindow().setBackgroundDrawable(globalWp);
            } catch (Throwable ignored) {}
            return;
        }
        // 优先使用本地 /wp 文件
        try {
            File wp = new File(getFilesDir().getAbsolutePath() + "/wp");
            if (wp.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                int imageHeight = opts.outHeight;
                int imageWidth = opts.outWidth;
                int picHeight = 720;
                int picWidth = 1080;
                int scaleX = imageWidth / picWidth;
                int scaleY = imageHeight / picHeight;
                int scale = Math.max(Math.max(scaleX, scaleY), 1);
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = scale;
                android.graphics.Bitmap bmp = BitmapFactory.decodeFile(wp.getAbsolutePath(), opts);
                if (bmp != null) {
                    globalWp = new GradientDrawable();
                    // 从 Bitmap 提取主色调作为渐变起点
                    globalWp.setColors(extractGradientColors(bmp));
                    globalWp.setOrientation(GradientDrawable.Orientation.TL_BR);
                    bmp.recycle();
                } else {
                    globalWp = themeGradient();
                }
            } else {
                globalWp = themeGradient();
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            globalWp = themeGradient();
        }
        try {
            getWindow().setBackgroundDrawable(globalWp);
        } catch (Throwable ignored) {}
    }

    /** 根据当前主题生成从深色到浅色的对角渐变背景 */
    private GradientDrawable themeGradient() {
        GradientDrawable gd = new GradientDrawable();
        try {
            int[] pair = com.github.tvbox.osc.n2n.ThemeManager.currentBgPair();
            gd.setColors(pair);
            gd.setOrientation(GradientDrawable.Orientation.TL_BR);
        } catch (Throwable ignored) {}
        return gd;
    }

    /** 从 Bitmap 提取主色调生成渐变（起点深色、终点浅色） */
    private int[] extractGradientColors(android.graphics.Bitmap bmp) {
        try {
            int w = Math.min(bmp.getWidth(), 64);
            int h = Math.min(bmp.getHeight(), 64);
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, false);
            int[] pixels = new int[w * h];
            scaled.getPixels(pixels, 0, w, 0, 0, w, h);
            scaled.recycle();
            long r = 0, g = 0, b = 0, count = 0;
            for (int px : pixels) {
                int pr = (px >> 16) & 0xFF;
                int pg = (px >> 8) & 0xFF;
                int pb = px & 0xFF;
                r += pr; g += pg; b += pb; count++;
            }
            int avgR = (int)(r / count);
            int avgG = (int)(g / count);
            int avgB = (int)(b / count);
            // 起点：主色调的深色版本（0.4倍亮度）
            int darkR = Math.max(0, (int)(avgR * 0.4f));
            int darkG = Math.max(0, (int)(avgG * 0.4f));
            int darkB = Math.max(0, (int)(avgB * 0.4f));
            // 终点：主色调略亮
            int lightR = Math.min(255, (int)(avgR * 1.1f));
            int lightG = Math.min(255, (int)(avgG * 1.1f));
            int lightB = Math.min(255, (int)(avgB * 1.1f));
            return new int[]{0xFF000000 | (darkR << 16) | (darkG << 8) | darkB,
                    0xFF000000 | (lightR << 16) | (lightG << 8) | lightB};
        } catch (Throwable ignored) {}
        return new int[]{0xFF1A1A2E, 0xFF16213E};
    }
}
