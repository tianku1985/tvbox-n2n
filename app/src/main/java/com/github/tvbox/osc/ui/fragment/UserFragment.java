package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.n2n.RemoteConfig;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.event.ServerEvent;
import com.github.tvbox.osc.ui.activity.*;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.UA;
import com.github.tvbox.osc.util.ImgUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * @author pj567
 * @date :2021/3/9
 * @description:
 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    private LinearLayout tvDrive;
    private LinearLayout tvLive;
    private LinearLayout tvSearch;
    private LinearLayout tvSetting;
    private LinearLayout tvHistory;
    private LinearLayout tvCollect;
    private LinearLayout tvPush;
    /** hin2n 组网内置模块入口（随入口行一起滚动） */
    private LinearLayout tvHin2nHome;
    public static HomeHotVodAdapter homeHotVodAdapter;
    private List<Movie.Video> homeSourceRec;
    public static TvRecyclerView tvHotListForGrid;
    public static TvRecyclerView tvHotListForLine;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    public static UserFragment newInstance(List<Movie.Video> recVod) {
        return new UserFragment().setArguments(recVod);
    }

    public UserFragment setArguments(List<Movie.Video> recVod) {
        this.homeSourceRec = recVod;
        return this;
    }

    /**
     * 首页中间一行入口已改为默认即时加载（HomeActivity.initView 直接创建本 Fragment），
     * 点播接口的「站点推荐」数据回来后通过本方法仅更新推荐列表，不重建入口行。
     * 仅在首页内容模式为「站点推荐」(HOME_REC==1) 时生效，其余模式（豆瓣热播/观看历史）忽略。
     */
    public void updateHomeRec(List<Movie.Video> recVod) {
        try {
            if (recVod == null) {
                return;
            }
            this.homeSourceRec = recVod;
            if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && homeHotVodAdapter != null) {
                // 站点推荐数据到达：标题同步改为「站点推荐」（首次创建时数据还没回来，
                // 标题会先显示为豆瓣/推荐，这里纠正，避免标题与内容不一致）
                homeHotVodAdapter.setLabel("站点推荐");
                homeHotVodAdapter.setNewData(recVod);
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    @Override
    public void onFragmentResume() {

        // takagen99: Initialize Icon Placement
        if (!Hawk.get(HawkConfig.HOME_SEARCH_POSITION, true)) {
            tvSearch.setVisibility(View.VISIBLE);
        } else {
            tvSearch.setVisibility(View.GONE);
        }
        if (!Hawk.get(HawkConfig.HOME_MENU_POSITION, true)) {
            tvSetting.setVisibility(View.VISIBLE);
        } else {
            tvSetting.setVisibility(View.GONE);
        }

        super.onFragmentResume();
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 2 && homeHotVodAdapter != null) {
            List<VodInfo> allVodRecord = RoomDataManger.getAllVodRecord(20);
            List<Movie.Video> vodList = new ArrayList<>();
            for (VodInfo vodInfo : allVodRecord) {
                Movie.Video vod = new Movie.Video();
                vod.id = vodInfo.id;
                vod.sourceKey = vodInfo.sourceKey;
                vod.name = vodInfo.name;
                vod.pic = vodInfo.pic;
                if (vodInfo.playNote != null && !vodInfo.playNote.isEmpty())
                    vod.note = "上次看到" + vodInfo.playNote;
                vodList.add(vod);
            }
            homeHotVodAdapter.setNewData(vodList);
        }
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }
    private ImgUtil.Style style;
    /** 首页「点播」入口（FongMi 风格，位于直播入口之前） */
    private android.view.View tvVod;
    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        tvDrive = findViewById(R.id.tvDrive);
        tvVod = findViewById(R.id.tvVod);
        tvLive = findViewById(R.id.tvLive);
        tvSearch = findViewById(R.id.tvSearch);
        tvSetting = findViewById(R.id.tvSetting);
        tvCollect = findViewById(R.id.tvFavorite);
        tvHistory = findViewById(R.id.tvHistory);
        tvPush = findViewById(R.id.tvPush);
        tvHin2nHome = findViewById(R.id.tvHin2nHome);
        tvHin2nHome.setVisibility(RemoteConfig.hin2nEntryEnabled() ? View.VISIBLE : View.GONE);
        tvDrive.setOnClickListener(this);
        tvVod.setOnClickListener(this);
        tvLive.setOnClickListener(this);
        tvSearch.setOnClickListener(this);
        tvSetting.setOnClickListener(this);
        tvHistory.setOnClickListener(this);
        tvPush.setOnClickListener(this);
        tvCollect.setOnClickListener(this);
        tvHin2nHome.setOnClickListener(this);
        tvDrive.setOnFocusChangeListener(focusChangeListener);
        tvVod.setOnFocusChangeListener(focusChangeListener);
        tvLive.setOnFocusChangeListener(focusChangeListener);
        tvSearch.setOnFocusChangeListener(focusChangeListener);
        tvSetting.setOnFocusChangeListener(focusChangeListener);
        tvHistory.setOnFocusChangeListener(focusChangeListener);
        tvPush.setOnFocusChangeListener(focusChangeListener);
        tvCollect.setOnFocusChangeListener(focusChangeListener);
        tvHin2nHome.setOnFocusChangeListener(focusChangeListener);
        // JSON "homeEntries" 配置存在时整体替换历史一行入口：内容/顺序/图标全部按 JSON
        rebuildHomeEntriesIfConfigured();
        // 焦点在入口行与下方内容列表之间移动时，外层 ScrollView 跟随滚动，
        // 让「历史」一行入口随内容列表一起上/下滚动（D-pad 上下）。
        installScrollFollow();
        tvHotListForLine = findViewById(R.id.tvHotListForLine);
        tvHotListForGrid = findViewById(R.id.tvHotListForGrid);
        // 多行网格嵌在外层 ScrollView 中且高度为 wrap_content：必须为 false，
        // 否则首次测量时 adapter 为空，网格高度被固定为极小值，数据到达后不再重新测量，
        // 导致网格内部滚动、入口行无法随内容一起滚动（根因）
        tvHotListForGrid.setHasFixedSize(false);
        int spanCount = 5;
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1 && homeSourceRec!=null) {
            style=ImgUtil.initStyle();
        }
        if(style!=null && Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            spanCount=ImgUtil.spanCountByStyle(style,spanCount);
        }
        tvHotListForGrid.setLayoutManager(new V7GridLayoutManager(this.mContext, spanCount));
        // 多行模式：关闭网格列表内部滚动，内容完全展开、由外层 ScrollView 统一滚动，
        // 入口行（历史一行）即可随下方内容一起上/下滚动（与单行模式行为一致）
        tvHotListForGrid.setNestedScrollingEnabled(false);
        // 推荐列表左上角标题：
        //  - 模式 0（豆瓣热播）：json 配置了 home.recMovies 时改叫「推荐」，不再请求豆瓣；
        //  - 模式 1（站点推荐）：有站点推荐数据叫「站点推荐」，没有时回退豆瓣/自定义推荐；
        //  - 模式 2（观看历史）不显示该标题。
        List<Movie.Video> customRec = RemoteConfig.homeRecMovies();
        String tvRate = "";
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 0) {
            tvRate = customRec.isEmpty() ? "豆瓣热播" : "推荐";
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null) {
                tvRate = "站点推荐";
            } else {
                tvRate = customRec.isEmpty() ? "豆瓣热播" : "推荐";
            }
        }
        homeHotVodAdapter = new HomeHotVodAdapter(style, tvRate);
        homeHotVodAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                // 防御：数据刷新瞬间 position 可能已越界/对应项被移除，取不到条目直接忽略点击
                if (vod == null) {
                    return;
                }

                // takagen99: CHeck if in Delete Mode
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2) && HawkConfig.hotVodDelete) {
                    homeHotVodAdapter.remove(position);
                    VodInfo vodInfo = RoomDataManger.getVodInfo(vod.sourceKey, vod.id);
                    // 防御：历史记录可能已被其它页面删除，查不到时只从列表移除，避免空指针
                    if (vodInfo != null) {
                        RoomDataManger.deleteVodRecord(vod.sourceKey, vodInfo);
                    }
                    Toast.makeText(mContext, getString(R.string.hm_hist_del), Toast.LENGTH_SHORT).show();
                } else if (vod.id != null && !vod.id.isEmpty()) {
                    Bundle bundle = new Bundle();
                    bundle.putString("id", vod.id);
                    bundle.putString("sourceKey", vod.sourceKey);
                    if (vod.id.startsWith("msearch:")) {
                        bundle.putString("title", vod.name);
                        jumpActivity(FastSearchActivity.class, bundle);
                    } else {
                        jumpActivity(DetailActivity.class, bundle);
                    }
                } else {
                    Intent newIntent;
                    if(Hawk.get(HawkConfig.FAST_SEARCH_MODE, false)){
                        newIntent = new Intent(mContext, FastSearchActivity.class);
                    }else {
                        newIntent = new Intent(mContext, SearchActivity.class);
                    }
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
            }
        });
        // takagen99 : Long press to trigger Delete Mode for VOD History on Home Page
        homeHotVodAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                if (ApiConfig.get().getSourceBeanList().isEmpty())
                    return false;
                Movie.Video vod = ((Movie.Video) adapter.getItem(position));
                // 防御：长按瞬间列表可能已刷新，取不到条目直接忽略
                if (vod == null) {
                    return false;
                }
                // Additional Check if : Home Rec 0=豆瓣, 1=推荐, 2=历史
                if ((vod.id != null && !vod.id.isEmpty()) && (Hawk.get(HawkConfig.HOME_REC, 0) == 2)) {
                    HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
                    homeHotVodAdapter.notifyDataSetChanged();
                } else {
                    Intent newIntent = new Intent(mContext, FastSearchActivity.class);
                    newIntent.putExtra("title", vod.name);
                    newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    mActivity.startActivity(newIntent);
                }
                return true;
            }
        });
        
        tvHistory.setOnLongClickListener(new View.OnLongClickListener() {
        	@Override
            public boolean onLongClick(View v) {
                HomeActivity.homeRecf();
                return HomeActivity.reHome(mContext);
            }
        });
        
        // Grid View
        tvHotListForGrid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForGrid.setAdapter(homeHotVodAdapter);
        // Line View
        tvHotListForLine.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.2f).scaleY(1.2f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {

            }
        });
        tvHotListForLine.setAdapter(homeHotVodAdapter);

        initHomeHotVod(homeHotVodAdapter);

        // Swifly: Home Style
        if (Hawk.get(HawkConfig.HOME_REC_STYLE, false)) {
            tvHotListForGrid.setVisibility(View.VISIBLE);
            tvHotListForLine.setVisibility(View.GONE);
        } else {
            tvHotListForGrid.setVisibility(View.GONE);
            tvHotListForLine.setVisibility(View.VISIBLE);
        }
    }

    /**
     * JSON "homeEntries" 整体替换历史一行：移除布局内置的固定入口卡片，
     * 按 JSON 数组顺序动态生成相同样式的卡片，点击统一走 EntryDispatcher
     * （page=打开界面 / feature=内置功能 / app=其他 App / vod=自定义点播界面）。
     * 未配置时保持布局原样。
     */
    private void rebuildHomeEntriesIfConfigured() {
        java.util.List<RemoteConfig.Entry> entries = RemoteConfig.homeEntries();
        if (entries == null) {
            return;
        }
        LinearLayout row = findViewById(R.id.tvUserHome);
        if (row == null) {
            return;
        }
        row.removeAllViews();
        android.content.res.Resources res = getResources();
        int h = res.getDimensionPixelSize(R.dimen.vs_100);
        int margin = res.getDimensionPixelSize(R.dimen.vs_5);
        int pad = res.getDimensionPixelSize(R.dimen.vs_15);
        int minW = res.getDimensionPixelSize(R.dimen.vs_150);
        int iconW = res.getDimensionPixelSize(R.dimen.vs_40);
        int iconMargin = res.getDimensionPixelSize(R.dimen.vs_3);
        int tvPad = res.getDimensionPixelSize(R.dimen.vs_3);
        for (final RemoteConfig.Entry e : entries) {
            LinearLayout card = new LinearLayout(mContext);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, h);
            clp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(clp);
            card.setGravity(android.view.Gravity.CENTER);
            card.setMinimumWidth(minW);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setPadding(pad, pad, pad, pad);
            card.setBackgroundResource(R.drawable.shape_user_home);
            card.setClickable(true);
            card.setFocusable(true);
            card.setContentDescription(e.name);

            android.widget.ImageView iv = new android.widget.ImageView(mContext);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconW,
                    LinearLayout.LayoutParams.MATCH_PARENT);
            ilp.setMargins(iconMargin, iconMargin, iconMargin, iconMargin);
            iv.setLayoutParams(ilp);
            iv.setAlpha(0.9f);
            com.github.tvbox.osc.n2n.EntryDispatcher.loadIcon(mContext, iv, e);
            card.addView(iv);

            android.widget.TextView tv = new android.widget.TextView(mContext);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(tlp);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(tvPad, tvPad, tvPad, tvPad);
            tv.setText(e.name);
            tv.setTextColor(0xE6FFFFFF); // color_FFFFFF_90
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    res.getDimensionPixelSize(R.dimen.ts_24));
            tv.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
            tv.setFocusable(false);
            tv.setClickable(false);
            card.addView(tv);

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    FastClickCheckUtil.check(v);
                    com.github.tvbox.osc.n2n.EntryDispatcher.open(mActivity, e);
                }
            });
            // 长按「历史」入口：切换推荐模式并重载首页（与固定卡片行为一致）
            if ("page".equals(e.type) && "history".equals(e.target)) {
                card.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        HomeActivity.homeRecf();
                        return HomeActivity.reHome(mContext);
                    }
                });
            }
            card.setOnFocusChangeListener(focusChangeListener);
            row.addView(card);
        }
    }

    /**
     * 初始化首页推荐列表（首页内容模式由 HawkConfig.HOME_REC 决定）。
     *
     * <p>加载优先级（2026-10-23 需求 5）：</p>
     * <ol>
     *   <li>模式 1 且首页站点已返回推荐数据：直接显示站点推荐；</li>
     *   <li>模式 2：前台 {@link #onFragmentResume()} 已填充观看历史，这里不再处理；</li>
     *   <li>其余情况（豆瓣模式 / 站点推荐回退）：json 配置了 home.recMovies 时直接显示
     *       自定义影片列表，<b>完全不发豆瓣请求</b>；未配置才走原有豆瓣热播（含当日缓存）。</li>
     * </ol>
     */
    private void initHomeHotVod(HomeHotVodAdapter adapter) {
        if (Hawk.get(HawkConfig.HOME_REC, 0) == 1) {
            if (homeSourceRec != null) {
                adapter.setNewData(homeSourceRec);
                return;
            }
            // 站点推荐尚未返回：若有自定义推荐，先顶上，避免空白等待
        } else if (Hawk.get(HawkConfig.HOME_REC, 0) == 2) {
            return;
        }
        // 自定义推荐影片优先：配置后不再请求豆瓣（豆瓣接口在部分网络/风控下会返回空列表）
        List<Movie.Video> customRec = RemoteConfig.homeRecMovies();
        if (!customRec.isEmpty()) {
            adapter.setNewData(customRec);
            return;
        }
        try {
            Calendar cal = Calendar.getInstance();
            int year = cal.get(Calendar.YEAR);
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DATE);
            String today = String.format("%d%d%d", year, month, day);
            String requestDay = Hawk.get("home_hot_day", "");
            if (requestDay.equals(today)) {
                String json = Hawk.get("home_hot", "");
                if (!json.isEmpty()) {
                    adapter.setNewData(loadHots(json));
                    return;
                }
            }
            String doubanHotURL = "https://movie.douban.com/j/new_search_subjects?sort=U&range=0,10&tags=&playable=1&start=0&year_range=" + year + "," + year;
            String userAgent = UA.random();
            OkGo.<String>get(doubanHotURL).headers("User-Agent", userAgent).execute(new AbsCallback<String>() {
                @Override
                public void onSuccess(Response<String> response) {
                    String netJson = response.body();
                    Hawk.put("home_hot_day", today);
                    Hawk.put("home_hot", netJson);
                    mActivity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            adapter.setNewData(loadHots(netJson));
                        }
                    });
                }

                @Override
                public String convertResponse(okhttp3.Response response) throws Throwable {
                    return response.body().string();
                }
            });
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private ArrayList<Movie.Video> loadHots(String json) {
        ArrayList<Movie.Video> result = new ArrayList<>();
        try {
            JsonObject infoJson = new Gson().fromJson(json, JsonObject.class);
            JsonArray array = infoJson.getAsJsonArray("data");
            for (JsonElement ele : array) {
                JsonObject obj = (JsonObject) ele;
                Movie.Video vod = new Movie.Video();
                vod.name = obj.get("title").getAsString();
                vod.note = obj.get("rate").getAsString();
                vod.pic = obj.get("cover").getAsString() + "@User-Agent=" + UA.random() + "@Referer=https://www.douban.com/";                
                result.add(vod);
            }
        } catch (Throwable th) {

        }
        return result;
    }

    /**
     * 首页滚动联动：入口行（历史/点播/直播/…/组网）与下方内容列表同处一个 ScrollView。
     * 横向 TvRecyclerView 内部移动焦点时 ScrollView 默认不会自动滚动，
     * 这里监听全局焦点变化，把新聚焦视图滚动到可视区内（上下留出边距）。
     */
    private void installScrollFollow() {
        View root = getView();
        if (!(root instanceof ScrollView)) {
            return;
        }
        final ScrollView sv = (ScrollView) root;
        final int margin = (int) (24 * getResources().getDisplayMetrics().density);
        sv.getViewTreeObserver().addOnGlobalFocusChangeListener(new ViewTreeObserver.OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                if (newFocus == null || sv.getHeight() <= 0) {
                    return;
                }
                // 只关心本 Fragment 内的视图
                if (!sv.isShown()) {
                    return;
                }
                int[] svPos = new int[2];
                int[] fPos = new int[2];
                sv.getLocationOnScreen(svPos);
                newFocus.getLocationOnScreen(fPos);
                int top = fPos[1] - svPos[1] + sv.getScrollY();
                int bottom = top + newFocus.getHeight();
                int visibleTop = sv.getScrollY();
                int visibleBottom = visibleTop + sv.getHeight();
                if (bottom > visibleBottom - margin) {
                    sv.smoothScrollTo(0, bottom - sv.getHeight() + margin);
                } else if (top < visibleTop + margin) {
                    sv.smoothScrollTo(0, Math.max(0, top - margin));
                }
            }
        });
    }

    private final View.OnFocusChangeListener focusChangeListener = new View.OnFocusChangeListener() {
        @Override
        public void onFocusChange(View v, boolean hasFocus) {
            if (hasFocus)
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            else
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
        }
    };

    @Override
    public void onClick(View v) {

        // takagen99: Remove Delete Mode
        HawkConfig.hotVodDelete = false;

        FastClickCheckUtil.check(v);
        if (v.getId() == R.id.tvVod) {
            // 点播入口：打开独立点播界面（分类/影片网格已自首页迁移）
            jumpActivity(VodActivity.class);
        } else if (v.getId() == R.id.tvLive) {
            jumpActivity(LivePlayActivity.class);
        } else if (v.getId() == R.id.tvSearch) {
            jumpActivity(SearchActivity.class);
        } else if (v.getId() == R.id.tvSetting) {
            jumpActivity(SettingActivity.class);
        } else if (v.getId() == R.id.tvHistory) {
            jumpActivity(HistoryActivity.class);
        } else if (v.getId() == R.id.tvPush) {
            jumpActivity(PushActivity.class);
        } else if (v.getId() == R.id.tvFavorite) {
            jumpActivity(CollectActivity.class);
        } else if (v.getId() == R.id.tvDrive) {
            jumpActivity(DriveActivity.class);
        } else if (v.getId() == R.id.tvHin2nHome) {
            // 组网入口：独立 hin2n App（已安装拉起，未安装走下载安装管线）
            RemoteConfig.Entry e = new RemoteConfig.Entry();
            e.type = "page";
            e.name = "组网";
            e.target = "hin2n";
            com.github.tvbox.osc.n2n.EntryDispatcher.open(mActivity, e);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void server(ServerEvent event) {
        if (event.type == ServerEvent.SERVER_CONNECTION) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }
}
