package com.github.tvbox.osc.ui.activity;

import android.Manifest;
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
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
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
        ControlManager.get().startServer();
        App.startWebserver();
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
        // Button : TVBOX >> Click to switch data source / Longclick to clear cache & refresh --
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FastClickCheckUtil.check(v);
                showSiteSwitch();
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                File dir = getCacheDir();
                FileUtils.recursiveDelete(dir);
                dir = getExternalCacheDir();
                FileUtils.recursiveDelete(dir);
                Toast.makeText(HomeActivity.this, getString(R.string.hm_cache_del), Toast.LENGTH_SHORT).show();
                if(dataInitOk && jarInitOk){
                    String cspCachePath = FileUtils.getFilePath()+"/csp/";
                    String jar=ApiConfig.get().getHomeSourceBean().getJar();
                    String jarUrl=!jar.isEmpty()?jar:ApiConfig.get().getSpider();
                    File cspCacheDir = new File(cspCachePath + MD5.string2MD5(jarUrl)+".jar");
                    if (!cspCacheDir.exists()){
                        reloadHome();
                        return true;
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
                return true;
            }
        });
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
                int idx = com.github.tvbox.osc.n2n.ThemeManager.cycle();
                Toast.makeText(HomeActivity.this,
                        "主题：" + com.github.tvbox.osc.n2n.ThemeManager.name(idx), Toast.LENGTH_SHORT).show();
                // recreate()/finish 重启会触发 onDestroy 的 appExit 杀进程（表现为"切主题=退出应用"），
                // 内部重启前置 sSkipAppExit，让本次 onDestroy 跳过杀进程，实现平滑换肤
                sSkipAppExit = true;
                com.github.tvbox.osc.base.BaseActivity.resetWallpaper();
                Intent it = new Intent(HomeActivity.this, HomeActivity.class);
                it.putExtra("useCache", useCacheConfig);
                finish();
                startActivity(it);
                overridePendingTransition(0, 0);
            }
        });
        // Button : Date >> Go into Android Date Settings --------------
        tvDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(Settings.ACTION_DATE_SETTINGS));
            }
        });
        setLoadSir(this.contentLayout);
    }

    //站点切换
    public static void homeRecf() {
        int homeRec = Hawk.get(HawkConfig.HOME_REC, -1);
        int limit = 2;
        if (homeRec == limit) homeRec = -1;
        homeRec++;
        Hawk.put(HawkConfig.HOME_REC, homeRec);
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
                if (userFragment == null) {
                    userFragment = UserFragment.newInstance(absXml == null ? null : absXml.videoList);
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.homeFragmentContainer, userFragment)
                            .commitNowAllowingStateLoss();
                }
                showSuccess();
                // takagen99 : Switch to show / hide source title
                SourceBean home = ApiConfig.get().getHomeSourceBean();
                if (HomeShow) {
                    if (home != null && home.getName() != null && !home.getName().isEmpty()) tvName.setText(home.getName());
                        tvName.clearAnimation();
                }
            }
        });
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
        if (isNetworkAvailable()) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_WIFI) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_wifi));
            } else if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_MOBILE) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_mobile));
            } else if (cm.getActiveNetworkInfo().getType() == ConnectivityManager.TYPE_ETHERNET) {
                tvWifi.setImageDrawable(res.getDrawable(R.drawable.hm_lan));
            }
        }

        // takagen99: Set Style either Grid or Line
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvStyle.setImageResource(R.drawable.hm_up_down);
        } else {
            tvStyle.setImageResource(R.drawable.hm_left_right);
        }

        if (dataInitOk && jarInitOk) {
            sourceViewModel.getSort(ApiConfig.get().getHomeSourceBean().getKey());
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
        tvNameAnimation();
        showLoading();
        if (dataInitOk && !jarInitOk) {
            if (!ApiConfig.get().getSpider().isEmpty()) {
                ApiConfig.get().loadJar(useCacheConfig, ApiConfig.get().getSpider(), new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        jarInitOk = true;
                        mHandler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!useCacheConfig) {
                                    Toast.makeText(HomeActivity.this, getString(R.string.hm_ok), Toast.LENGTH_SHORT).show();
                                }
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

        // takagen99 : Switch to show / hide source title
        SourceBean home = ApiConfig.get().getHomeSourceBean();
        if (Hawk.get(HawkConfig.HOME_SHOW_SOURCE, false)) {
            if (home != null && home.getName() != null && !home.getName().isEmpty()) {
                tvName.setText(home.getName());
                tvName.clearAnimation();
            }
        } else {
            tvName.setText(R.string.app_name);
        }

        // takagen99: Icon Placement
        if (Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvFind.setVisibility(View.VISIBLE);
        } else {
            tvFind.setVisibility(View.GONE);
        }
        mHandler.post(mRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
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

    // Site Switch on Home Button
    void showSiteSwitch() {
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

    void reloadHome() {
        sSkipAppExit = true;
        Intent intent = new Intent(getApplicationContext(), HomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Bundle bundle = new Bundle();
        bundle.putBoolean("useCache", true);
        intent.putExtras(bundle);
        HomeActivity.this.startActivity(intent);
        finish();
    }

    private void refreshEmpty() {
        skipNextUpdate = true;
        showSuccess();
        tvName.clearAnimation();
    }

    private void tvNameAnimation()
    {
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }

}
