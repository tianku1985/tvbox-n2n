package com.github.tvbox.osc.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Resources;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.DiffUtil;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.dialog.TipDialog;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.FileUtils;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.LOG;
import com.github.tvbox.osc.util.MD5;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.TvRecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 启动桌面：仅保留首页内容（UserFragment），分类/影片网格已迁移至独立点播界面 VodActivity
 */
public class HomeActivity extends BaseActivity {

    // takagen99: Added to allow read string
    private static Resources res;

    /** 内部重启标记：主题一键切换等自重启时置 true，onDestroy 跳过 appExit 杀进程 */
    private static boolean sSkipAppExit = false;

    /** 点播界面切源后返回主界面时置位：onResume 检测并强制刷新首页数据 */
    public static boolean sRefreshOnResume = false;

    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvName;
    private ImageView tvWifi;
    private ImageView tvFind;
    private ImageView tvStyle;
    private ImageView tvDraw;
    private ImageView tvN2n;
    private TextView tvDate;
    private SourceViewModel sourceViewModel;
    private UserFragment userFragment;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat")
			//修改时间分隔符
            SimpleDateFormat timeFormat = new SimpleDateFormat(getString(R.string.hm_date1) + " | " + getString(R.string.hm_date2));
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        // takagen99: Added to allow read string
        res = getResources();

        EventBus.getDefault().register(this);
        // 远程控制/局域网 Web 服务端口绑定与 AndServer 路由扫描较慢，移到后台线程，不阻塞首页首帧
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ControlManager.get().startServer();
                } catch (Throwable ignored) {
                }
                try {
                    App.startWebserver();
                } catch (Throwable ignored) {
                }
            }
        }, "home-servers").start();
        initView();
        initViewModel();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    // takagen99: Added to allow read string
    public static Resources getRes() {
        return res;
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.tvName = findViewById(R.id.tvName);
        this.tvWifi = findViewById(R.id.tvWifi);
        this.tvFind = findViewById(R.id.tvFind);
        this.tvStyle = findViewById(R.id.tvStyle);
        this.tvDraw = findViewById(R.id.tvDrawer);
        this.tvN2n = findViewById(R.id.tvN2n);
        // 首页扩展：hin2n 入口 / 自定义入口栏 / 滚动公告
        com.github.tvbox.osc.n2n.HomeEntry.refresh(this);
        this.tvDate = findViewById(R.id.tvDate);
        this.contentLayout = findViewById(R.id.contentLayout);
        // 首页左上角名称：仅作为标题展示，不再作为「切换点播源」入口。
        // 2026-10-23 按需求取消该入口及其焦点：TV 遥控器上下左右不会再落到这里，
        // 切源统一走 MENU 键 / 设置页；长按清缓存也一并移除（避免误触导致重载）。
        tvName.setClickable(false);
        tvName.setFocusable(false);
        tvName.setFocusableInTouchMode(false);
        tvName.setOnClickListener(null);
        tvName.setOnLongClickListener(null);
        // Button : Wifi >> Go into Android Wifi Settings -------------
        tvWifi.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }catch (Exception ignored){
                }
            }
        });
        // Button : Search --------------------------------------------
        tvFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SearchActivity.class);
            }
        });
        // Button : Style --------------------------------------------
        tvStyle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    Hawk.put(HawkConfig.HOME_REC_STYLE, !Hawk.get(HawkConfig.HOME_REC_STYLE, false));
                    if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
                        UserFragment.tvHotListForGrid.setVisibility(View.VISIBLE);
                        UserFragment.tvHotListForLine.setVisibility(View.GONE);
                        Toast.makeText(HomeActivity.this, getString(R.string.hm_style_grid), Toast.LENGTH_SHORT).show();
                        tvStyle.setImageResource(R.drawable.hm_up_down);
                    } else {
                        UserFragment.tvHotListForGrid.setVisibility(View.GONE);
                        UserFragment.tvHotListForLine.setVisibility(View.VISIBLE);
                        Toast.makeText(HomeActivity.this, getString(R.string.hm_style_line), Toast.LENGTH_SHORT).show();
                        tvStyle.setImageResource(R.drawable.hm_left_right);
                    }
                } catch (Exception ex) {
                }
            }
        });
        // Button : Drawer >> To go into App Drawer -------------------
        tvDraw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(AppsActivity.class);
            }
        });
        // Button : Settings >> To go into Settings --------------------
        // 远程配置入口（收藏/应用与设置之间），直达设置页「设置其他」的远程配置卡片
        tvN2n.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent n2nIntent = new Intent(HomeActivity.this, SettingActivity.class);
                startActivity(n2nIntent);
            }
        });
        // Button : Theme >> 一键切换主题配色（远程 JSON 也可下发默认主题）
        findViewById(R.id.tvTheme).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                switchTheme(HomeActivity.this, useCacheConfig);
            }
        });
        // Button : Date >> Go into Android Date Settings --------------
        tvDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
            }
        });
        // 中间一行入口（历史/点播/直播/…）与下方内容区立即默认加载：
        // 不再等待点播接口的 sortResult 回来才创建 UserFragment，
        // 这样未配置点播接口（仅直播）或点播接口加载失败时，入口行也和顶部一行一样立即可用；
        // 点播数据回来后由 sortResult 观察者调用 UserFragment.updateHomeRec 仅更新推荐数据。
        try {
            userFragment = UserFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.homeFragmentContainer, userFragment)
                    .commitNowAllowingStateLoss();
        } catch (Throwable th) {
            th.printStackTrace();
        }
        setLoadSir(this.contentLayout);
        // 立即切成成功态：保证 LoadSir 注册后内容区（含「历史」入口行）从首帧就可见，
        // 与顶部标题行同一时机加载；后续数据通过 UserFragment 的观察者增量刷新。
        showSuccess();
    }

    //站点切换
    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
    }

    /**
     * 一键切换主题（顶栏 tvTheme 与 JSON feature=themeSwitch 入口共用）。
     * recreate()/finish 重启会触发 onDestroy 的 appExit 杀进程（表现为"切主题=退出应用"），
     * 内部重启前置 sSkipAppExit，让本次 onDestroy 跳过杀进程，实现平滑换肤。
     * 切主题只刷新界面 UI、不刷新数据：useCache 强制为 true，点播接口配置与 spider jar
     * 均走本地缓存，homeContent 命中 SourceViewModel.sortCache 内存缓存（进程未杀，缓存仍在）。
     */
    public static void switchTheme(Activity act, boolean useCache) {
        int idx = com.github.tvbox.osc.n2n.ThemeManager.cycle();
        Toast.makeText(act, "主题：" + com.github.tvbox.osc.n2n.ThemeManager.name(idx), Toast.LENGTH_SHORT).show();
        sSkipAppExit = true;
        com.github.tvbox.osc.base.BaseActivity.resetWallpaper();
        Intent it = new Intent(act, HomeActivity.class);
        it.putExtra("useCache", true);
        act.finish();
        act.startActivity(it);
        act.overridePendingTransition(0, 0);
    }

    /**
     * 清理缓存并重载首页（tvName 长按与 JSON feature=clearCache 入口共用）：
     * 清缓存目录 → 删除当前源 csp jar 缓存 → reloadHome。
     */
    public void clearHomeCache() {
        File dir = getCacheDir();
        FileUtils.recursiveDelete(dir);
        dir = getExternalCacheDir();
        FileUtils.recursiveDelete(dir);
        Toast.makeText(HomeActivity.this, getString(R.string.hm_cache_del), Toast.LENGTH_SHORT).show();
        if (dataInitOk && jarInitOk) {
            String cspCachePath = FileUtils.getFilePath() + "/csp/";
            String jar = ApiConfig.get().getHomeSourceBean().getJar();
            String jarUrl = !jar.isEmpty() ? jar : ApiConfig.get().getSpider();
            File cspCacheDir = new File(cspCachePath + MD5.string2MD5(jarUrl) + ".jar");
            if (!cspCacheDir.exists()) {
                reloadHome();
                return;
            }
            new Thread(() -> {
                try {
                    FileUtils.deleteFile(cspCacheDir);
                    ApiConfig.get().clearJarLoader();
                    reloadHome();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    public static boolean reHome(Context appContext) {
        Intent intent = new Intent(appContext, HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        appContext.startActivity(intent);
        return true;
    }

    private boolean skipNextUpdate = false;
    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                if (skipNextUpdate) {
                    skipNextUpdate = false;
                    return;
                }
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                try {
                    if (userFragment == null) {
                        // 兼容路径：initView 中的即时创建失败时，仍在此处兜底创建
                        userFragment = UserFragment.newInstance(absXml == null ? null : absXml.videoList);
                        getSupportFragmentManager().beginTransaction()
                                .replace(R.id.homeFragmentContainer, userFragment)
                                .commitNowAllowingStateLoss();
                    } else {
                        // 中间一行入口已在 initView 默认加载，这里只把点播推荐数据喂给已存在的
                        // UserFragment（更新「站点推荐」列表），不重建入口行，避免换源时入口闪烁重排
                        userFragment.updateHomeRec(absXml == null ? null : absXml.videoList);
                    }
                    showSuccess();
                    // takagen99 : Switch to show / hide source title
                    applySourceName();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /**
     * 左上角名称显示规则（2026-10-22 起固定为纯标题）：
     * 始终显示内置 json「home.sourceName」配置的文字，不再随点播接口/当前源切换而变化；
     * sourceName 为空时回退显示 app 名称。
     * 自 2026-10-23 起该标题已取消点击与焦点，仅作展示（切源入口从首页移除）。
     */
    private void applySourceName() {
        String sn = com.github.tvbox.osc.n2n.RemoteConfig.homeSourceName();
        if (sn.isEmpty()) {
            tvName.setText(R.string.app_name);
        } else {
            tvName.setText(sn);
        }
        tvName.clearAnimation();
    }

    private boolean dataInitOk = false;
    private boolean jarInitOk = false;

    // takagen99 : Switch to show / hide source title
    boolean HomeShow = Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false);

    // takagen99 : Check if network is available
    boolean isNetworkAvailable() {
        ConnectivityManager cm
                = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = cm.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }

    private void initData() {
        // takagen99: If network available, check connected Wifi or Lan
        // 网络状态瞬时变化时 getActiveNetworkInfo() 可能为 null（弱网/切换瞬间），需空值保护
        try {
            if (isNetworkAvailable()) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                if (ni != null) {
                    if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                        tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_wifi));
                    } else if (ni.getType() == ConnectivityManager.TYPE_MOBILE) {
                        tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_mobile));
                    } else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                        tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_lan));
                    }
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }

        // takagen99: Set Style either Grid or Line
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvStyle.setImageResource(R.drawable.hm_up_down);
        } else {
            tvStyle.setImageResource(R.drawable.hm_left_right);
        }

        if (dataInitOk && jarInitOk) {
            boolean forceSortRefresh = getIntent() != null && getIntent().getBooleanExtra("forceSortRefresh", false);
            // 未配置点播接口（仅直播）时 homeSourceBean 为空壳、key 为 null：
            // 直接通知观察者空数据即可（UserFragment 已默认加载，入口行立即可用），
            // 不再触发任何点播网络请求
            String homeKey = ApiConfig.get().getHomeSourceBean().getKey();
            if (homeKey == null || homeKey.isEmpty()) {
                sourceViewModel.sortResult.postValue(null);
            } else {
                sourceViewModel.getSort(homeKey, forceSortRefresh);
            }
            if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                LOG.e("有");
            } else {
                LOG.e("无");
            }
            if (Hawk.get(HawkConfig.HOME_DEFAULT_SHOW, false)) {
                jumpActivity(LivePlayActivity.class);
            }
            return;
        }
        // 首帧不再用 LoadSir 遮住内容区（原 tvNameAnimation + showLoading）：
        // 顶部标题行、扩展入口栏、中间「历史/点播/直播/...」入口行、公告条都在启动时一起渲染，
        // 与顶部行加载方式一致；点播接口数据到达后由 UserFragment 观察者增量刷新推荐列表，
        // 不再出现「顶部已显示、历史一行要等接口加载完才出现」的先后差。
        tvName.clearAnimation();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                // 原「加载成功」Toast 统一替换为可配置秒数的顶部通知（见 showLoadSuccessNotice）
                                initData();
                            }
                        }, 50);
                    }

                    @Override
                    public void retry() {

                    }

                    @Override
                    public void error(String msg) {
                        jarInitOk = true;
                        dataInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if ("".equals(msg))
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_notok), Toast.LENGTH_SHORT).show();
                                else
                                    Toast.makeText(HomeActivity.this, msg, Toast.LENGTH_SHORT).show();
                                initData();
                            }
                        },50);
                    }
                });
            }
            return;
        }
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            TipDialog dialog = null;

            @Override
            public void retry() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                    }
                });
            }

            @Override
            public void success() {
                dataInitOk = true;
                if (ApiConfig.get().getSpider().isEmpty()) {
                    jarInitOk = true;
                }
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        initData();
                        // 点播接口（配置）加载成功通知：秒数由 json "loadSuccessDuration" 配置，
                        // 到点自动关闭；0 表示不显示。仅在本次真正走了接口加载（非缓存）时提示，
                        // 与原先「加载成功」Toast 的触发时机保持一致，避免每次启动都打扰用户。
                        if (!useCacheConfig) {
                            showLoadSuccessNotice();
                        }
                        showWarningTextOnce();
                        // 远程升级检查：延迟片刻，避免与免责声明弹窗叠加
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!isFinishing() && !isDestroyed()) {
                                    com.github.tvbox.osc.n2n.UpgradeChecker.check(HomeActivity.this);
                                }
                            }
                        }, 3000);
                    }
                }, 50);
            }

            @Override
            public void error(String msg) {
                if (msg.equalsIgnoreCase("-1")) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            dataInitOk = true;
                            jarInitOk = true;
                            initData();
                        }
                    });
                    return;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed())
                            return;
                        if (dialog == null)
                            dialog = new TipDialog(HomeActivity.this, msg, getString(R.string.hm_retry), getString(R.string.hm_cancel), new TipDialog.OnListener() {
                                @Override
                                public void left() {
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void right() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }

                                @Override
                                public void cancel() {
                                    dataInitOk = true;
                                    jarInitOk = true;
                                    mHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            initData();
                                            dialog.hide();
                                        }
                                    });
                                }
                            });
                        if (!dialog.isShowing())
                            dialog.show();
                    }
                });
            }
        }, this);
    }

    /** 免责声明是否已在本进程内弹过（每次启动只弹一次） */
    private static boolean sWarningShown = false;

    /** 免责声明弹窗自动关闭倒计时任务（null=当前没有进行中的倒计时） */
    private Runnable mWarningTick;

    /**
     * 点播接口配置的 warningText 生效：配置加载成功后弹一次免责声明。
     *
     * <p>2026-10-23 需求 6：弹窗支持「自定义秒数后自动关闭」——秒数取自 json 顶层
     * {@code warningAutoClose}（RemoteConfig.warningAutoClose）：</p>
     * <ul>
     *   <li>0（默认）：保持原行为，必须点「确定」才关闭；</li>
     *   <li>N&gt;0：确定按钮显示「确定 (Ns)」并每秒递减，到点自动关闭，不需要任何按键；
     *       适合无人值守的电视/机顶盒，避免免责声明长期挡住首页。</li>
     * </ul>
     */
    private void showWarningTextOnce() {
        if (sWarningShown || isFinishing() || isDestroyed()) return;
        String warningText = Hawk.get(HawkConfig.WARNING_TEXT, "");
        if (warningText == null || warningText.trim().isEmpty()) return;
        sWarningShown = true;
        try {
            final TipDialog[] holder = new TipDialog[1];
            holder[0] = new TipDialog(HomeActivity.this, warningText, getString(R.string.hm_ok), "", new TipDialog.OnListener() {
                @Override
                public void left() {
                    if (holder[0] != null) holder[0].dismiss();
                }

                @Override
                public void right() {
                }

                @Override
                public void cancel() {
                }
            });
            // 弹窗被手动确认/取消/系统关闭时，立即停止倒计时，避免残留任务继续跑
            holder[0].setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(android.content.DialogInterface dialog) {
                    stopWarningCountdown();
                }
            });
            holder[0].show();
            // 自定义秒数自动关闭：倒计时显示在「确定」按钮上，到点自动 dismiss
            final int autoClose = com.github.tvbox.osc.n2n.RemoteConfig.warningAutoClose();
            if (autoClose > 0) {
                final android.widget.TextView leftBtn = holder[0].findViewById(R.id.leftBtn);
                final int[] leftSec = {autoClose};
                updateWarningButton(leftBtn, autoClose);
                mWarningTick = new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed() || !holder[0].isShowing()) {
                            return;
                        }
                        leftSec[0]--;
                        if (leftSec[0] <= 0) {
                            holder[0].dismiss();
                            return;
                        }
                        updateWarningButton(leftBtn, leftSec[0]);
                        mHandler.postDelayed(this, 1000);
                    }
                };
                mHandler.postDelayed(mWarningTick, 1000);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    /** 刷新免责声明「确定」按钮上的剩余秒数：确定 (Ns) */
    private void updateWarningButton(android.widget.TextView btn, int seconds) {
        if (btn != null) {
            btn.setText(getString(R.string.hm_ok) + " (" + seconds + "s)");
        }
    }

    /** 停止免责声明倒计时（幂等） */
    private void stopWarningCountdown() {
        if (mWarningTick != null) {
            mHandler.removeCallbacks(mWarningTick);
            mWarningTick = null;
        }
    }

    @Override
    public void onBackPressed() {
        //打断加载
        if(isLoading()){
            refreshEmpty();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        // 首页热门列表已滚动时，先回到顶部
        if (UserFragment.tvHotListForGrid != null && UserFragment.tvHotListForGrid.canScrollVertically(-1)) {
            UserFragment.tvHotListForGrid.scrollToPosition(0);
            return;
        }

        doExit();
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            EventBus.getDefault().unregister(this);
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        com.github.tvbox.osc.n2n.HomeEntry.refresh(this);
        // APK 下载完成但缺「未知来源」权限、从系统设置返回后自动继续安装
        com.github.tvbox.osc.n2n.ApkDownloader.resumePendingInstall(this);

        // takagen99 : Switch to show / hide source title
        applySourceName();

        // takagen99: Icon Placement（JSON topEntries 整体替换顶栏时，固定图标显隐由 HomeEntry 全权控制）
        if (com.github.tvbox.osc.n2n.RemoteConfig.topEntries() == null) {
            if (Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
                tvFind.setVisibility(View.VISIBLE);
            } else {
                tvFind.setVisibility(View.GONE);
            }
        }
        mHandler.post(mRunnable);

        // 点播界面切换源后返回：重建首页并强制刷新当前源数据
        if (sRefreshOnResume) {
            sRefreshOnResume = false;
            reloadHome();
        }

        // 「设为系统桌面」开关已开但还不是默认桌面时，每进程弹一次系统桌面选择器
        com.github.tvbox.osc.n2n.RemoteConfig.ensureDefaultLauncher(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 页面不可见时先移除浮层通知与倒计时：onPause 会清空 Handler 队列，
        // 若只清队列不移除视图，返回首页后顶部提示会残留不再消失。
        hideLoadSuccessNotice();
        stopWarningCountdown();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_PUSH_URL) {
            if (ApiConfig.get().getSource("push_agent") != null) {
                Intent newIntent = new Intent(mContext, DetailActivity.class);
                newIntent.putExtra("id", (String) event.obj);
                newIntent.putExtra("sourceKey", "push_agent");
                newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                HomeActivity.this.startActivity(newIntent);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                showSiteSwitch();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onDestroy() {
        // 先移除未执行完的加载成功通知倒计时，避免 Activity 销毁后 Runnable 仍持有引用
        hideLoadSuccessNotice();
        // 免责声明自动关闭倒计时同样需要停止
        stopWarningCountdown();
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (sSkipAppExit) {
            // 内部重启（主题一键切换）：保留进程，由新实例接管
            sSkipAppExit = false;
        } else {
            AppManager.getInstance().appExit(0);
            ControlManager.get().stopServer();
        }
        com.github.tvbox.osc.n2n.HomeEntry.stopTicker();
    }

    // Site Switch on Home Button（JSON feature=siteSwitch 入口也调用，需 public）
    public void showSiteSwitch() {
        List<SourceBean> sites = new ArrayList<>();
        for (SourceBean sb : ApiConfig.get().getSourceBeanList()) {
            if (sb.getHide() == 0) sites.add(sb);
        }
        if (sites.size() > 0) {
            SelectDialog<SourceBean> dialog = new SelectDialog<>(HomeActivity.this);

            // Multi Column Selection
            int spanCount = (int) Math.floor(sites.size() / 10);
            if (spanCount <= 1) spanCount = 1;
            if (spanCount >= 3) spanCount = 3;

            TvRecyclerView tvRecyclerView = dialog.findViewById(R.id.list);
            tvRecyclerView.setLayoutManager(new V7GridLayoutManager(dialog.getContext(), spanCount));
            ConstraintLayout cl_root = dialog.findViewById(R.id.cl_root);
            ViewGroup.LayoutParams clp = cl_root.getLayoutParams();
            if (spanCount != 1) {
                clp.width = AutoSizeUtils.mm2px(dialog.getContext(), 400 + 260 * (spanCount - 1));
            }

            dialog.setTip(getString(R.string.dia_source));
            dialog.setAdapter(tvRecyclerView, new SelectDialogAdapter.SelectDialogInterface<SourceBean>() {
                @Override
                public void click(SourceBean value, int pos) {
                    ApiConfig.get().setSourceBean(value);
                    reloadHome();
                }

                @Override
                public String getDisplay(SourceBean val) {
                    return val.getName();
                }
            }, new DiffUtil.ItemCallback<SourceBean>() {
                @Override
                public boolean areItemsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem == newItem;
                }

                @Override
                public boolean areContentsTheSame(@NonNull @NotNull SourceBean oldItem, @NonNull @NotNull SourceBean newItem) {
                    return oldItem.getKey().equals(newItem.getKey());
                }
            }, sites, sites.indexOf(ApiConfig.get().getHomeSourceBean()));
            dialog.show();
        }
    }

    /** 重新加载首页（JSON feature=reloadHome 入口也调用，需 public） */
    public void reloadHome() {
        sSkipAppExit = true;
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        // 切换源 / 配置重载后重建首页：强制重新拉取当前源 homeContent，绕过 sortCache
        bundle.putBoolean("forceSortRefresh", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
        finish();
    }

    private void refreshEmpty() {
        skipNextUpdate = true;
        showSuccess();
        tvName.clearAnimation();
    }

    // ==================== 点播接口加载成功通知（可配置自动关闭秒数） ====================

    /** 当前显示中的「点播接口加载成功」通知，null 表示未显示 */
    private TextView mLoadSuccessTip;
    /** 通知剩余显示秒数：每秒递减，减到 0 自动从界面上移除 */
    private int mLoadSuccessLeft = 0;

    /**
     * 显示点播接口加载成功通知（2026-10-23 需求 6）。
     *
     * <p>行为：</p>
     * <ul>
     *   <li>秒数取自 json 顶层 {@code loadSuccessDuration}（RemoteConfig.loadSuccessDuration）：
     *       0=不显示，默认 3 秒，最大 60 秒；</li>
     *   <li>通知为非模态浮层，{@code focusable=false / clickable=false}，不抢遥控器焦点，
     *       到点自动移除，不需要用户按键确认（替代原来的系统 Toast 与转圈提示）；</li>
     *   <li>文本每秒刷新为「点播接口加载成功 · Ns」，让用户明确知道何时自动关闭；</li>
     *   <li>重复调用会先移除旧通知再重新计时，避免多层叠加。</li>
     * </ul>
     */
    private void showLoadSuccessNotice() {
        final int seconds = com.github.tvbox.osc.n2n.RemoteConfig.loadSuccessDuration();
        if (seconds <= 0 || isFinishing() || isDestroyed()) {
            return;
        }
        hideLoadSuccessNotice();
        ViewGroup root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        TextView tip = new TextView(this);
        tip.setTextColor(0xFFFFFFFF);
        tip.setBackgroundColor(0xCC1A1A1A);
        tip.setGravity(android.view.Gravity.CENTER);
        tip.setFocusable(false);
        tip.setClickable(false);
        tip.setPadding(res.getDimensionPixelSize(R.dimen.vs_25), res.getDimensionPixelSize(R.dimen.vs_12),
                res.getDimensionPixelSize(R.dimen.vs_25), res.getDimensionPixelSize(R.dimen.vs_12));
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                res.getDimensionPixelSize(R.dimen.ts_20));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 顶部居中、位于标题栏下方，避免遮挡左上角标题与右上角图标
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = res.getDimensionPixelSize(R.dimen.vs_90);
        tip.setLayoutParams(lp);
        root.addView(tip);
        mLoadSuccessTip = tip;
        mLoadSuccessLeft = seconds;
        updateLoadSuccessNoticeText(seconds);
        // 先显示完整秒数，1 秒后再开始递减
        mHandler.postDelayed(mLoadSuccessTick, 1000);
    }

    /** 通知倒计时任务：每秒刷新文本，归零时移除通知 */
    private final Runnable mLoadSuccessTick = new Runnable() {
        @Override
        public void run() {
            if (mLoadSuccessTip == null) {
                return;
            }
            mLoadSuccessLeft--;
            if (mLoadSuccessLeft <= 0) {
                hideLoadSuccessNotice();
                return;
            }
            updateLoadSuccessNoticeText(mLoadSuccessLeft);
            mHandler.postDelayed(this, 1000);
        }
    };

    /** 刷新通知文本：「点播接口加载成功 · Ns」 */
    private void updateLoadSuccessNoticeText(int seconds) {
        if (mLoadSuccessTip != null) {
            mLoadSuccessTip.setText(getString(R.string.load_success_notice, seconds));
        }
    }

    /** 立即移除通知并取消倒计时（幂等） */
    private void hideLoadSuccessNotice() {
        mHandler.removeCallbacks(mLoadSuccessTick);
        mLoadSuccessLeft = 0;
        if (mLoadSuccessTip != null) {
            ViewGroup parent = (ViewGroup) mLoadSuccessTip.getParent();
            if (parent != null) {
                parent.removeView(mLoadSuccessTip);
            }
            mLoadSuccessTip = null;
        }
    }

}
