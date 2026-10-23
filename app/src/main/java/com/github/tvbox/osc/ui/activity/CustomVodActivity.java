package com.github.tvbox.osc.ui.activity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SelectDialogAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.dialog.SelectDialog;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 自定义点播列表界面：由 JSON 入口（type=vod）打开，显示名称自定义。
 * 数据接口格式与主点播 api json 完全一致（取其中 sites 数组），但仅用于本界面：
 * 站点以 "cv_" 前缀临时注入源表、当前源仅切换内存值（不写 Hawk），
 * 退出本界面时移除注入源并恢复原当前源，app 其它配置不受影响。
 *
 * Intent extras：name=显示名称；url=数据接口地址（http(s):// / assets/xxx.json / 绝对路径）。
 */
public class CustomVodActivity extends BaseActivity {

    private LinearLayout contentLayout;
    private TextView tvName;
    private TextView tvDate;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private View currentView = null;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;

    /** 入口传入：显示名称 / 数据接口地址 */
    private String entryName = "";
    private String entryUrl = "";
    /** 本界面注入的 cv_ 源（切源对话框候选） */
    private final List<SourceBean> customSources = new ArrayList<>();
    /** 进入本界面前的当前源（退出时恢复） */
    private SourceBean prevHome = null;
    /** 原当前源是否已恢复（onPause 先于 HomeActivity.onResume 执行，必须提前恢复） */
    private boolean homeRestored = false;

    private final Runnable mRunnable = new Runnable() {
        @SuppressLint({"DefaultLocale", "SetTextI18n"})
        @Override
        public void run() {
            Date date = new Date();
            @SuppressLint("SimpleDateFormat")
            SimpleDateFormat timeFormat = new SimpleDateFormat(getString(R.string.hm_date1) + " | " + getString(R.string.hm_date2));
            tvDate.setText(timeFormat.format(date));
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_vod;
    }

    @Override
    protected void init() {
        Intent it = getIntent();
        if (it != null) {
            entryName = it.getStringExtra("name") == null ? "" : it.getStringExtra("name");
            entryUrl = it.getStringExtra("url") == null ? "" : it.getStringExtra("url");
        }
        initView();
        initViewModel();
        loadCustomConfig();
    }

    private void initView() {
        this.tvName = findViewById(R.id.tvName);
        this.tvDate = findViewById(R.id.tvDate);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridViewCategory);
        this.mViewPager = findViewById(R.id.mViewPager);
        if (!entryName.isEmpty()) {
            tvName.setText(entryName);
        }
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !CustomVodActivity.this.isDownOrUp) {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(false);
                    textView.setTextColor(CustomVodActivity.this.getResources().getColor(R.color.color_FFFFFF_70));
                    textView.invalidate();
                    view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    CustomVodActivity.this.currentView = view;
                    CustomVodActivity.this.isDownOrUp = false;
                    CustomVodActivity.this.sortChange = true;
                    view.animate().scaleX(1.1f).scaleY(1.1f).setDuration(250).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(CustomVodActivity.this.getResources().getColor(R.color.color_FFFFFF));
                    textView.invalidate();
                    if (position == -1) {
                        position = 0;
                        CustomVodActivity.this.mGridView.setSelection(0);
                    }
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (null != sortData && !sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    CustomVodActivity.this.sortFocusView = view;
                    CustomVodActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {
                        ((GridFragment) baseLazyFragment).showFilter();
                    }
                }
            }
        });
        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if ((baseLazyFragment instanceof GridFragment)) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (!(baseLazyFragment instanceof GridFragment)) {
                    return false;
                }
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });
        // 返回：回到主界面（不退出 App）
        findViewById(R.id.tvBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });
        // 界面名：点击在本界面的 cv_ 源之间切换 / 长按重新加载
        tvName.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FastClickCheckUtil.check(view);
                showSiteSwitch();
            }
        });
        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                reloadHome();
                return true;
            }
        });
        // 搜索
        ImageView tvFind = findViewById(R.id.tvFind);
        tvFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                jumpActivity(SearchActivity.class);
            }
        });
        setLoadSir(this.contentLayout);
        showLoading();
        com.github.tvbox.osc.n2n.NoticeBar.show(this, com.github.tvbox.osc.n2n.NoticeBar.SCREEN_VOD);
    }

    private void initViewModel() {
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                try {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String key = ApiConfig.get().getHomeSourceBean() != null ? ApiConfig.get().getHomeSourceBean().getKey() : "";
                    if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
                        sortAdapter.setNewData(DefaultConfig.adjustSort(key, absXml.classes.sortList, false));
                    } else {
                        sortAdapter.setNewData(DefaultConfig.adjustSort(key, new ArrayList<>(), false));
                    }
                    initViewPager();
                } catch (Throwable t) {
                    // 切换源过程中的任何异常都不允许崩进程
                    showEmpty();
                }
            }
        });
    }

    private void initViewPager() {
        fragments.clear();
        currentSelected = 0;
        sortFocused = 0;
        if (sortAdapter.getData().size() > 0) {
            for (MovieSort.SortData data : sortAdapter.getData()) {
                fragments.add(GridFragment.newInstance(data));
            }
            pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
            try {
                Field field = ViewPager.class.getDeclaredField("mScroller");
                field.setAccessible(true);
                FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
                field.set(mViewPager, scroller);
                scroller.setmDuration(300);
            } catch (Exception e) {
            }
            mViewPager.setPageTransformer(true, new DefaultTransformer());
            mViewPager.setAdapter(pageAdapter);
            mViewPager.setCurrentItem(currentSelected, false);
            showSuccess();
        } else {
            showEmpty();
        }
    }

    // ==================== 自定义数据源加载 ====================

    private void loadCustomConfig() {
        if (entryUrl.isEmpty()) {
            Toast.makeText(mContext, "该入口未配置数据接口", Toast.LENGTH_SHORT).show();
            showEmpty();
            return;
        }
        if (entryUrl.startsWith("http://") || entryUrl.startsWith("https://")) {
            // 请求附加本机参数（mac/ip/机型/系统版本等），服务端可按机下发
            OkGo.<String>get(com.github.tvbox.osc.n2n.DeviceParams.append(entryUrl)).execute(new AbsCallback<String>() {
                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    if (response.body() != null) {
                        return response.body().string();
                    }
                    throw new IllegalStateException("网络请求错误");
                }

                @Override
                public void onSuccess(Response<String> response) {
                    applyCustomJson(response.body());
                }

                @Override
                public void onError(Response<String> response) {
                    super.onError(response);
                    Toast.makeText(mContext, "数据接口加载失败", Toast.LENGTH_SHORT).show();
                    showEmpty();
                }
            });
        } else {
            // 本地：assets/xxx.json、file:// 或绝对路径
            String json = readLocal(entryUrl);
            if (json != null) {
                applyCustomJson(json);
            } else {
                Toast.makeText(mContext, "数据接口读取失败", Toast.LENGTH_SHORT).show();
                showEmpty();
            }
        }
    }

    private String readLocal(String url) {
        InputStream in = null;
        try {
            String u = url;
            if (u.startsWith("assets/")) {
                in = getAssets().open(u.substring("assets/".length()));
            } else if (u.startsWith("file://")) {
                in = new FileInputStream(u.substring("file://".length()));
            } else if (u.startsWith("/")) {
                in = new FileInputStream(u);
            } else {
                in = getAssets().open(u);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 解析数据接口 json：注入 cv_ 源并加载第一个可用源的分类 */
    private void applyCustomJson(String json) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // 是 json 直接解析，不是 json 先解密后加载（ke 前缀/固定密钥两种模式自动识别）
        json = com.github.tvbox.osc.n2n.ConfigCrypto.maybeDecrypt(json);
        customSources.clear();
        customSources.addAll(ApiConfig.get().injectCustomSources(json));
        SourceBean first = null;
        for (SourceBean sb : customSources) {
            if (sb.getHide() == 0) {
                first = sb;
                break;
            }
        }
        if (first == null) {
            Toast.makeText(mContext, "数据接口无可用站点", Toast.LENGTH_SHORT).show();
            showEmpty();
            return;
        }
        // 保存原当前源，仅内存切换（不写 Hawk，退出本界面即恢复）
        prevHome = ApiConfig.get().getHomeSourceBean();
        ApiConfig.get().setHomeSourceTemp(first);
        sourceViewModel.getSort(first.getKey(), true);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                }
            }
        }
    };

    private void showFilterIcon(int count) {
        boolean activated = count > 0;
        currentView.findViewById(R.id.tvFilter).setVisibility(View.VISIBLE);
        ImageView imgView = currentView.findViewById(R.id.tvFilter);
        imgView.setColorFilter(activated ? this.getThemeColor() : Color.WHITE);
    }

    @Override
    public void onBackPressed() {
        if (isLoading()) {
            showSuccess();
            return;
        }
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }
        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            if (grid.restoreView()) {
                return;
            }
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            } else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else {
            doExit();
        }
    }

    private void doExit() {
        if (System.currentTimeMillis() - mExitTime < 2000) {
            finish();
        } else {
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, getString(R.string.hm_exit), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mRunnable);
        com.github.tvbox.osc.n2n.NoticeBar.show(this, com.github.tvbox.osc.n2n.NoticeBar.SCREEN_VOD);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
        // 关键时序：A.onResume 在 B.onDestroy 之前执行。若等 onDestroy 才恢复当前源，
        // 主界面 HomeActivity.onResume 已先读到 cv_ 临时源名并显示在左上角。
        // 所以必须在真正离开本界面（isFinishing）时于 onPause 内立即恢复 + 撤销独立主题。
        if (isFinishing() && !homeRestored) {
            restoreHomeSource();
            com.github.tvbox.osc.n2n.ThemeManager.popOverride();
            BaseActivity.resetWallpaper();
        }
    }

    /** 恢复进入本界面前的当前源（幂等；同时移除本界面注入的 cv_ 源） */
    private void restoreHomeSource() {
        if (homeRestored) {
            return;
        }
        homeRestored = true;
        try {
            ApiConfig.get().removeCustomSources();
            if (prevHome != null && prevHome.getKey() != null
                    && ApiConfig.get().getSource(prevHome.getKey()) != null) {
                ApiConfig.get().setHomeSourceTemp(prevHome);
            } else {
                List<SourceBean> all = ApiConfig.get().getSourceBeanList();
                for (SourceBean sb : all) {
                    if (sb.getHide() == 0) {
                        ApiConfig.get().setHomeSourceTemp(sb);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        com.github.tvbox.osc.n2n.NoticeBar.hide(this, com.github.tvbox.osc.n2n.NoticeBar.SCREEN_VOD);
        // 兜底：onPause 未恢复（如系统直接销毁）时在此恢复
        restoreHomeSource();
        com.github.tvbox.osc.n2n.ThemeManager.popOverride();
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

    /** 站点切换：仅在本界面注入的 cv_ 源之间切换（不动主配置） */
    void showSiteSwitch() {
        List<SourceBean> sites = new ArrayList<>();
        for (SourceBean sb : customSources) {
            if (sb.getHide() == 0) sites.add(sb);
        }
        if (sites.size() > 0) {
            SelectDialog<SourceBean> dialog = new SelectDialog<>(CustomVodActivity.this);
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
                    ApiConfig.get().setHomeSourceTemp(value);
                    // 反馈 + 强制刷新列表（getSort force=true 绕过 homeContent 缓存）
                    Toast.makeText(mContext, getString(R.string.dia_source) + "：" + value.getName(), Toast.LENGTH_SHORT).show();
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

    /** 重新加载当前 cv_ 源分类（强制绕过 homeContent 缓存） */
    void reloadHome() {
        showLoading();
        currentSelected = 0;
        sortFocused = 0;
        String key = ApiConfig.get().getHomeSourceBean() != null ? ApiConfig.get().getHomeSourceBean().getKey() : "";
        // 取消其它 cv_ 源仍在途的 homeContent 请求，避免旧响应后到达覆盖新源列表
        try {
            for (SourceBean sb : customSources) {
                if (sb.getKey() != null && !sb.getKey().equals(key)) {
                    OkGo.getInstance().cancelTag(sb.getKey() + "_sort");
                }
            }
        } catch (Throwable ignored) {
        }
        sourceViewModel.getSort(key, true);
    }
}
