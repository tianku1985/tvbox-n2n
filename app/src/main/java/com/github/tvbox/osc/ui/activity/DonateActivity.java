package com.github.tvbox.osc.ui.activity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.n2n.RemoteConfig;

import org.json.JSONObject;

import java.io.InputStream;

/**
 * 捐助界面：显示本项目详细介绍与自定义图片/二维码。
 * 内容由内置/远程 JSON 的 "donate" 段配置：
 *   "donate": {
 *     "title": "支持我们",                  // 顶栏标题，空=默认「支持我们」
 *     "desc":  "项目介绍……",                // 详细介绍（支持 \n 多行），空=用内置默认介绍
 *     "image": "https://.../qrcode.png"     // 自定义图片/二维码：
 *   }                                       //   http(s) URL / assets/xxx.png / drawable 名；空=不显示图片
 * 入口：json 入口配置 {"name":"捐助","type":"page","target":"donate","icon":"hm_fav"}。
 */
public class DonateActivity extends BaseActivity {

    /** 内置默认项目介绍（donate.desc 未配置时显示） */
    private static final String DEFAULT_DESC =
            "TVBox-n2n 是一款基于 TVBox 深度定制的安卓电视播放桌面，集成点播、直播、"
                    + "EPG 节目单与 n2n 异地组网能力，支持 json/jsonc 配置驱动、主题切换、"
                    + "自定义入口、远程配置下发与接口加密下发。\n\n"
                    + "如果本项目对你有帮助，欢迎扫码捐助支持持续开发与维护，感谢支持！";

    private TextView tvName;
    private TextView tvDonateDesc;
    private ImageView ivDonateQr;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_donate;
    }

    @Override
    protected void init() {
        tvName = findViewById(R.id.tvName);
        tvDonateDesc = findViewById(R.id.tvDonateDesc);
        ivDonateQr = findViewById(R.id.ivDonateQr);
        findViewById(R.id.tvBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bindConfig();
    }

    /** 读取 json "donate" 段并填充界面（desc 空用内置介绍，image 空不显示图片） */
    private void bindConfig() {
        String title = "";
        String desc = "";
        String image = "";
        try {
            JSONObject dn = RemoteConfig.donate();
            if (dn != null) {
                title = dn.optString("title", "").trim();
                desc = dn.optString("desc", "").trim();
                image = dn.optString("image", "").trim();
            }
        } catch (Throwable ignored) {
        }
        tvName.setText(title.isEmpty() ? "支持我们" : title);
        tvDonateDesc.setText(desc.isEmpty() ? DEFAULT_DESC : desc.replace("\\n", "\n"));
        loadDonateImage(image);
    }

    /** 图片三态加载：http(s) 走 Glide；assets/ 路径走 BitmapFactory；其余按 drawable 名解析 */
    private void loadDonateImage(String image) {
        if (TextUtils.isEmpty(image)) {
            ivDonateQr.setVisibility(View.GONE);
            return;
        }
        try {
            if (image.startsWith("http://") || image.startsWith("https://")) {
                ivDonateQr.setVisibility(View.VISIBLE);
                Glide.with(this).load(image).into(ivDonateQr);
                return;
            }
            if (image.startsWith("assets/")) {
                InputStream in = null;
                try {
                    in = getAssets().open(image.substring("assets/".length()));
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        ivDonateQr.setImageBitmap(bmp);
                        ivDonateQr.setVisibility(View.VISIBLE);
                        return;
                    }
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            int resId = getResources().getIdentifier(image, "drawable", getPackageName());
            if (resId != 0) {
                ivDonateQr.setImageResource(resId);
                ivDonateQr.setVisibility(View.VISIBLE);
                return;
            }
        } catch (Throwable ignored) {
        }
        ivDonateQr.setVisibility(View.GONE);
    }
}
