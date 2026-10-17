package wang.switchy.hin2n.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * hin2n 独立 App 内部使用的最小 Activity 基类：
 * 仅提供布局模板（getLayoutResID/init）、mContext 与固定主题色，无 TVBox 依赖。
 */
public abstract class BaseActivity extends AppCompatActivity {

    protected Context mContext;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getLayoutResID());
        mContext = this;
        init();
    }

    protected abstract int getLayoutResID();

    protected abstract void init();

    protected void jumpActivity(Class<? extends BaseActivity> clazz) {
        startActivity(new Intent(mContext, clazz));
    }

    /** 固定主题色（原 TVBox 随主题变化，独立 App 使用固定蓝） */
    public int getThemeColor() {
        return 0xFF2196F3;
    }
}
