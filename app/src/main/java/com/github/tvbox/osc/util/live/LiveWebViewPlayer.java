package com.github.tvbox.osc.util.live;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.n2n.RemoteConfig;

import org.json.JSONArray;

import java.net.URLEncoder;

/**
 * 直播网页型源播放器：webview:// / video:// 整页加载，tkp2p:// 走内置 P2P 播放页。
 *
 * - webview:// https://xxx  → 剥前缀，直接 WebView 加载页面（页面自带播放器/P2P 自然生效）
 * - video://   http(s)://xx → 同上（网页型源，不做嗅探，避免签名失效）
 * - tkp2p://   http(s)://xx → 加载 assets/tkp2p/tkp2p.html，由页面内
 *                             p2p-media-loader（HLS）或 media-p2p-engine（MP4 等）建立 P2P 链路
 *
 * WebView 不抢焦点，遥控器按键仍由 LivePlayActivity.dispatchKeyEvent 处理；
 * 频道列表/设置浮层叠加在 WebView 之上，无需额外布局改动。
 */
public class LiveWebViewPlayer {

    private static final String TAG = "LiveWebViewPlayer";

    /**
     * 页面内播放接管脚本（参考 ku9 webview JS 实现）：
     *  - 每 100ms 轮询 <video>，出现即 v.play() 自动起播（15 秒后降为 1 秒轮询，
     *    页面重载/视频元素重建也能自动接管）；
     *  - 强制 v.muted=false、v.volume=1，修复部分源静音打开导致「没声音」；
     *  - hideControls 隐藏 vjs/xgplayer/dplayer 等自带控制条（display:none 而非删除 DOM，
     *    保证 togglePageUi 切回页面 UI 时控件能恢复显示）；
     *  - 把 video 移入全屏黑底容器铺满整屏，修复各源全屏表现不一；
     *  - 内置 window.ku9 桥接桩（getscale/setscale/setvideo/setaudio/setposition/setduration），
     *    ku9 风格脚本可直接复用；
     *  - window.__tvboxToggleUI() 切换全屏容器显隐：隐藏时露出页面原 UI，
     *    可用页面自带的节目单/频道列表，再按一次回到全屏视频。
     */
    private static final String JS_KU9_ENGINE =
            "(function(){"
                    + "if(window.__tvboxKu9)return;window.__tvboxKu9=true;"
                    + "var startTime=Date.now(),videoElement=null,fastPhase=true;"
                    + "window.ku9={getscale:function(){return 0;},setscale:function(){},"
                    + "setposition:function(){},setduration:function(){},setvideo:function(){},setaudio:function(){}};"
                    + "// 要隐藏的控制条选择器（仅隐藏 display，保留 DOM，切回页面 UI 时恢复）"
                    + "var ctrlSelectors=['#control_bar','.vjs-control-bar','xg-controls','.xgplayer-ads',"
                    + "'.xgplayer-controls','.prism-controlbar','.dplayer-controller','.dplayer-menu',"
                    + "'.video-controls','.player-controls','.live-controls','.vjs-big-play-button',"
                    + "'.vjs-poster','.loading-layer','div[class*=\"play-btn\"]','div[class*=\"controls-layer\"]'];"
                    + "var hiddenNodes=[];"
                    + "function hideControls(){"
                    + "for(var i=0;i<ctrlSelectors.length;i++){var es=document.querySelectorAll(ctrlSelectors[i]);"
                    + "for(var j=0;j<es.length;j++){var e=es[j];"
                    + "if(e.style.display!=='none'){hiddenNodes.push({el:e,origDisplay:e.style.display});e.style.display='none';}}}"
                    + "}"
                    + "function showControls(){"
                    + "for(var i=0;i<hiddenNodes.length;i++){try{hiddenNodes[i].el.style.display=hiddenNodes[i].origDisplay||'';}catch(e){}}"
                    + "hiddenNodes=[];"
                    + "}"
                    + "function setupVideo(v){"
                    + "videoElement=v;"
                    + "var c=document.getElementById('video-fullscreen-container');"
                    + "if(!c){c=document.createElement('div');c.id='video-fullscreen-container';"
                    + "c.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100%!important;"
                    + "height:100%!important;z-index:2147483647!important;background:#000!important;overflow:hidden!important;"
                    + "transform:translateZ(0);';document.body.appendChild(c);}"
                    + "while(c.firstChild)c.removeChild(c.firstChild);"
                    + "v.style.cssText='position:absolute!important;top:50%!important;left:50%!important;"
                    + "transform:translate(-50%,-50%)!important;width:100%!important;height:100%!important;"
                    + "object-fit:contain!important;outline:none!important;border:none!important;box-shadow:none!important;';"
                    + "c.appendChild(v);"
                    + "try{v.muted=false;v.volume=1;}catch(e){}"
                    + "try{v.setAttribute('playsinline','');v.removeAttribute('muted');}catch(e){}"
                    + "var vp=document.createElement('meta');vp.name='viewport';"
                    + "vp.content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no';"
                    + "document.head.appendChild(vp);"
                    + "hideControls();"
                    + "}"
                    + "function checkVideo(){"
                    + "try{"
                    + "var vs=document.querySelectorAll('video');"
                    + "if(vs.length==0){if(videoElement&&videoElement.isConnected)return;}"
                    + "var v=vs.length>0?vs[0]:null;"
                    + "if(!v){if(Date.now()-startTime>20000)fastPhase=false;return;}"
                    + "if(v.paused){try{v.play();}catch(e){}}"
                    + "if(v!==videoElement||!v.__tvboxSetup){"
                    + "if(v.readyState>0||Date.now()-startTime>4000){setupVideo(v);v.__tvboxSetup=true;"
                    + "if(v.videoWidth&&v.videoHeight){try{ku9.setvideo(v.videoWidth,v.videoHeight);ku9.setaudio('立体声');}catch(e){}}}"
                    + "}"
                    + "}catch(e){}"
                    + "}"
                    + "// 切换全屏视频容器显隐：显示容器=全屏观看视频，隐藏容器=露出页面原 UI（含节目表/频道列表）"
                    + "window.__tvboxToggleUI=function(){"
                    + "var c=document.getElementById('video-fullscreen-container');"
                    + "if(!c)return 'none';"
                    + "var hide=(c.style.display!=='none');"
                    + "c.style.display=hide?'none':'block';"
                    + "if(hide){showControls();}else{hideControls();}"
                    + "return hide?'pageui':'video';"
                    + "};"
                    + "window.__tvboxPlay=function(){if(videoElement){try{videoElement.play();}catch(e){}}};"
                    + "setInterval(checkVideo,fastPhase?100:1000);"
                    + "setTimeout(function(){fastPhase=false;},15000);"
                    + "})();";

    /** 容器上再叠一层重试：视频元素晚于 DOM 就绪时也能接管 */
    private static final long RETRY_INTERVAL_MS = 1200;
    private static final int RETRY_MAX = 10;

    private WebView webView;
    private View customView;      // H5 全屏回调视图
    private WebChromeClient.CustomViewCallback customCb;
    private Activity owner;

    /** 是否为网页型/P2P 型直播地址 */
    public static boolean isWebUrl(String url) {
        return url != null && (url.startsWith("webview://") || url.startsWith("video://") || url.startsWith("tkp2p://"));
    }

    /** 显示并加载；每次切台重建，避免页面状态残留 */
    public void show(Activity act, String rawUrl) {
        destroy();
        owner = act;
        String target = resolveUrl(rawUrl);
        if (target == null || target.isEmpty()) {
            return;
        }
        webView = new WebView(act);
        initWebView(webView);
        ViewGroup root = act.findViewById(R.id.live_root);
        if (root == null) {
            return;
        }
        // 插在 mVideoView 之上、频道列表/设置浮层之下
        int index = 1;
        if (root.getChildCount() < index) {
            index = root.getChildCount();
        }
        root.addView(webView, index,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.loadUrl(target);
        Log.d(TAG, "load: " + target);
    }

    /** 当前是否处于网页播放态 */
    public boolean isActive() {
        return webView != null;
    }

    /** 停止网页播放并回收（切回流媒体频道时调用） */
    public void destroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.removeAllViews();
                webView.destroy();
            } catch (Throwable ignored) {
            }
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView = null;
        }
        owner = null;
    }

    /** 处理返回键：先退出 H5 全屏，返回 true 表示已消费 */
    public boolean handleBack() {
        if (customView != null) {
            exitCustomView();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------

    /** 剥 scheme 并组装真实加载地址 */
    private static String resolveUrl(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        if (rawUrl.startsWith("webview://")) {
            return rawUrl.substring("webview://".length()).trim();
        }
        if (rawUrl.startsWith("video://")) {
            return rawUrl.substring("video://".length()).trim();
        }
        if (rawUrl.startsWith("tkp2p://")) {
            String src = rawUrl.substring("tkp2p://".length()).trim();
            return buildTkP2pPage(src);
        }
        return rawUrl;
    }

    /** 组装内置 P2P 播放页地址（配置来自远程 JSON 的 tkp2p 段） */
    private static String buildTkP2pPage(String src) {
        StringBuilder sb = new StringBuilder("file:///android_asset/tkp2p/tkp2p.html?src=");
        sb.append(URLEncoder.encode(src));
        try {
            org.json.JSONObject cfg = RemoteConfig.tkP2pStyle();
            // 页面只识别 auto/hls/media；兼容配置里写全称 p2p-media-loader/media-p2p-engine
            String eng = cfg.optString("engine", "auto").trim();
            if ("p2p-media-loader".equalsIgnoreCase(eng)) {
                eng = "hls";
            } else if ("media-p2p-engine".equalsIgnoreCase(eng)) {
                eng = "media";
            }
            if (!eng.isEmpty()) {
                sb.append("&eng=").append(URLEncoder.encode(eng));
            }
            JSONArray ann = cfg.optJSONArray("announce");
            if (ann != null && ann.length() > 0) {
                sb.append("&announce=").append(URLEncoder.encode(ann.toString()));
            }
            String token = cfg.optString("token", "");
            if (!token.isEmpty()) {
                sb.append("&token=").append(URLEncoder.encode(token));
            }
            String zone = cfg.optString("zone", "");
            if (!zone.isEmpty()) {
                sb.append("&zone=").append(URLEncoder.encode(zone));
            }
            // ICE 服务器：标准键 iceServers，兼容简写 ice
            JSONArray ice = cfg.optJSONArray("iceServers");
            if (ice == null) {
                ice = cfg.optJSONArray("ice");
            }
            if (ice != null && ice.length() > 0) {
                sb.append("&ice=").append(URLEncoder.encode(ice.toString()));
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private void initWebView(WebView wv) {
        WebSettings st = wv.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setMediaPlaybackRequiresUserGesture(false);   // 允许自动起播
        st.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        st.setAllowFileAccess(true);
        st.setAllowContentAccess(true);
        // file:// 下放行跨域，保证 hls.js 能直接拉任意 IPTV 源
        try {
            st.setAllowFileAccessFromFileURLs(true);
            st.setAllowUniversalAccessFromFileURLs(true);
        } catch (Throwable ignored) {
        }
        st.setCacheMode(WebSettings.LOAD_DEFAULT);
        // UA：远程 JSON "ua" 字段非空时覆盖默认 UA（部分直播站点按 UA 放流）
        try {
            String ua = com.github.tvbox.osc.n2n.RemoteConfig.ua();
            if (!ua.isEmpty()) {
                st.setUserAgentString(ua);
            }
        } catch (Throwable ignored) {
        }
        // 不抢焦点：遥控器按键仍由 Activity 分发（切台/呼出频道列表）
        wv.setFocusable(false);
        wv.setFocusableInTouchMode(false);
        wv.setHapticFeedbackEnabled(false);
        wv.setBackgroundColor(0xFF000000);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;   // 页面内跳转保持在直播 WebView 中
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                applyKu9Engine(view, 0);
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // H5 video 全屏：自定义视图铺满根布局
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = callback;
                ViewGroup root = owner != null ? owner.findViewById(R.id.live_root) : null;
                if (root != null) {
                    if (webView != null) webView.setVisibility(View.GONE);
                    // 插在 WebView 之上、频道列表/设置浮层之下：H5 全屏时原生列表仍能叠在最上层
                    int idx = webView != null ? root.indexOfChild(webView) + 1 : root.getChildCount();
                    idx = Math.max(0, Math.min(idx, root.getChildCount()));
                    root.addView(view, idx, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.d(TAG, "console: " + cm.message());
                return true;
            }
        });
    }

    /** 页面加载完成后注入 ku9 风格接管脚本；SPA 页面可能在 finished 后才建 video，做多次重试 */
    private void applyKu9Engine(final WebView wv, final int attempt) {
        if (wv == null || attempt > RETRY_MAX) {
            return;
        }
        try {
            wv.evaluateJavascript(JS_KU9_ENGINE, null);
        } catch (Throwable ignored) {
        }
        wv.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (webView == wv) {
                    applyKu9Engine(wv, attempt + 1);
                }
            }
        }, RETRY_INTERVAL_MS);
    }

    /**
     * 切换「页面原 UI / 全屏视频」：隐藏全屏容器时露出页面自带的节目单/频道列表，
     * 供遥控器菜单键调用；返回是否已切换为显示页面 UI。
     */
    public boolean togglePageUi() {
        if (webView == null) {
            return false;
        }
        try {
            final boolean[] pageUi = {false};
            webView.evaluateJavascript("window.__tvboxToggleUI?window.__tvboxToggleUI():'none'",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            pageUi[0] = value != null && value.contains("pageui");
                        }
                    });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void exitCustomView() {
        if (customView != null) {
            ViewGroup root = owner != null ? owner.findViewById(R.id.live_root) : null;
            if (root != null && customView.getParent() == root) {
                root.removeView(customView);
            }
            if (customCb != null) {
                try {
                    customCb.onCustomViewHidden();
                } catch (Throwable ignored) {
                }
            }
            customView = null;
            customCb = null;
            if (webView != null) webView.setVisibility(View.VISIBLE);
        }
    }
}
