#!/usr/bin/env python3
"""把 n2n edge 集成进已克隆的 TVBoxOSC 源码树。

由 GitHub Actions 工作流在上游源码克隆并修改之后调用：
拷贝 Android 源码到上游工程、按锚点修改 SettingActivity 与 AndroidManifest.xml、
准备的 assets/n2n 目录用于存放后续步骤下载的 edge 二进制。

对 q215613905/TVBoxOS 与 takagen99/Box 均适用（锚点完全一致）。
"""

import shutil
import sys
from pathlib import Path

# 命令行第一个参数：被克隆的上游工程目录（默认 TVBoxOSC）
REPO = Path(sys.argv[1] if len(sys.argv) > 1 else "TVBoxOSC").resolve()
# 本仓库内预置的 Android 源码/资源根目录
SKEL = Path(__file__).resolve().parent / "android" / "app" / "src" / "main"

if not (REPO / "app").exists():
    print(f"错误: {REPO} 不是 TVBoxOSC 检出目录")
    sys.exit(1)


def fail(msg):
    print(f"错误: {msg}")
    sys.exit(1)


def merge_copy(src: Path, dst: Path):
    """把我们的源码/资源文件合并进上游目录(只拷贝不删除)。"""
    for file in src.rglob("*"):
        if file.is_file():
            rel = file.relative_to(src)
            target = dst / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)
    print(f"合并 {src} -> {dst}")


def patch_setting_activity():
    """按锚点在 SettingActivity 中挂载 N2N 入口（import、列表项、Fragment）。"""
    p = REPO / "app" / "src" / "main" / "java" / "com" / "github" / "tvbox" / "osc" / "ui" / "activity" / "SettingActivity.java"
    src = p.read_text(encoding="utf-8")

    # 1) 追加 import：锚点为相邻的 ModelSettingFragment import
    imp = "import com.github.tvbox.osc.ui.fragment.N2nSettingFragment;"
    if "ui.fragment.N2nSettingFragment" not in src:
        if "import com.github.tvbox.osc.ui.fragment.ModelSettingFragment;" not in src:
            fail("SettingActivity: 找不到 ModelSettingFragment import 锚点")
        else:
            src = src.replace(
                "import com.github.tvbox.osc.ui.fragment.ModelSettingFragment;",
                "import com.github.tvbox.osc.ui.fragment.ModelSettingFragment;\n" + imp,
            )
            print("已修改 SettingActivity: 追加 import")

    # 2) 在“设置其他”之后追加“N2N 组网”菜单项
    if 'sortList.add("N2N 组网")' not in src:
        anchor = 'sortList.add("设置其他");'
        if anchor not in src:
            fail("SettingActivity: 找不到 sortList 锚点")
        else:
            src = src.replace(anchor, anchor + '\n        sortList.add("N2N 组网");', 1)
            print("已修改 SettingActivity: 追加入口")

    # 3) 在 Fragment 列表末尾注册 N2N 设置页
    frag = "fragments.add(N2nSettingFragment.newInstance());"
    if frag not in src:
        anchor = "fragments.add(ModelSettingFragment.newInstance());"
        if anchor not in src:
            fail("SettingActivity: 找不到 fragments 锚点")
        else:
            src = src.replace(anchor, anchor + "\n        " + frag, 1)
            print("已修改 SettingActivity: 追加 Fragment")

    # 4) 主界面 N2N 入口图标点击时带 extra=n2n_go，进入设置页后直接定位到 N2N 子页
    #    （N2N 始终追加在 fragments 末尾，故 index=fragments.size()-1）
    if "n2n_go" not in src:
        anchor = "mViewPager.setCurrentItem(0);"
        if anchor not in src:
            fail("SettingActivity: 找不到 setCurrentItem 锚点")
        else:
            src = src.replace(
                anchor,
                "mViewPager.setCurrentItem(getIntent() != null"
                " && getIntent().getBooleanExtra(\"n2n_go\", false)"
                " ? fragments.size() - 1 : 0);",
                1,
            )
            print("已修改 SettingActivity: 支持 n2n_go 直达 N2N 子页")

    p.write_text(src, encoding="utf-8")


def patch_home_entry():
    """在主界面顶栏『收藏/应用(tvDrawer)』与『设置(tvMenu)』两个图标之间插入 N2N 组网
    入口。仅 takagen99/Box 的主界面有该图标栏；q215613905/TVBoxOS 无对应锚点时
    打印跳过（不 fail），其入口仍在 设置页左侧菜单的「N2N 组网」。"""
    # ---- 布局：tvMenu 图标前插入 tvN2n 图标（尺寸/边距/背景与 tvMenu 一致） ----
    layout = (REPO / "app" / "src" / "main" / "res" / "layout" / "activity_home.xml")
    if layout.exists():
        src = layout.read_text(encoding="utf-8")
        if "@+id/tvN2n" not in src:
            anchor_layout = '        <ImageView\n            android:id="@+id/tvMenu"'
            if anchor_layout in src:
                icon = (
                    '        <ImageView\n'
                    '            android:id="@+id/tvN2n"\n'
                    '            android:layout_width="@dimen/vs_50"\n'
                    '            android:layout_height="@dimen/vs_50"\n'
                    '            android:layout_marginStart="@dimen/vs_15"\n'
                    '            android:layout_marginLeft="@dimen/vs_15"\n'
                    '            android:layout_marginEnd="@dimen/vs_15"\n'
                    '            android:layout_marginRight="@dimen/vs_15"\n'
                    '            android:background="@drawable/button_dialog_vod"\n'
                    '            android:clickable="true"\n'
                    '            android:contentDescription="N2N 组网"\n'
                    '            android:focusable="true"\n'
                    '            android:padding="@dimen/vs_10"\n'
                    '            android:src="@drawable/ic_n2n" />\n\n'
                )
                src = src.replace(anchor_layout, icon + anchor_layout, 1)
                layout.write_text(src, encoding="utf-8")
                print("已修改 activity_home.xml: 收藏与设置之间插入 N2N 入口图标")
            else:
                print("activity_home.xml: 无 tvMenu 锚点，跳过主界面 N2N 入口")

    # ---- HomeActivity：字段、绑定、点击跳转（带 n2n_go extra） ----
    home = (REPO / "app" / "src" / "main" / "java" / "com" / "github"
            / "tvbox" / "osc" / "ui" / "activity" / "HomeActivity.java")
    if home.exists():
        src = home.read_text(encoding="utf-8")
        if "tvN2n" not in src:
            changed = False
            anchor_field = "private ImageView tvMenu;"
            if anchor_field in src:
                src = src.replace(anchor_field,
                                  anchor_field + "\n    private ImageView tvN2n;", 1)
                changed = True
            anchor_bind = "this.tvMenu = findViewById(R.id.tvMenu);"
            if anchor_bind in src:
                src = src.replace(
                    anchor_bind,
                    anchor_bind + "\n        this.tvN2n = findViewById(R.id.tvN2n);",
                    1,
                )
                changed = True
            anchor_click = "tvMenu.setOnClickListener(new View.OnClickListener() {"
            click_block = (
                "        // N2N 组网入口（收藏/应用与设置之间），直达设置页 N2N 子页\n"
                "        tvN2n.setOnClickListener(new View.OnClickListener() {\n"
                "            @Override\n"
                "            public void onClick(View view) {\n"
                "                Intent n2nIntent = new Intent(HomeActivity.this, SettingActivity.class);\n"
                "                n2nIntent.putExtra(\"n2n_go\", true);\n"
                "                startActivity(n2nIntent);\n"
                "            }\n"
                "        });\n"
            )
            if anchor_click in src:
                src = src.replace(anchor_click, click_block + anchor_click, 1)
                changed = True
            if changed:
                home.write_text(src, encoding="utf-8")
                print("已修改 HomeActivity: 绑定 N2N 入口点击事件")



def patch_manifest():
    """在 AndroidManifest.xml 中注册 N2nVpnService（幂等：已存在则跳过）。"""
    p = REPO / "app" / "src" / "main" / "AndroidManifest.xml"
    src = p.read_text(encoding="utf-8")

    if "N2nVpnService" in src:
        print("manifest: 已存在 N2nVpnService, 跳过")
        return

    # 与系统 VpnService 相同组件名+权限，保证被系统识别为 VPN 服务
    service = (
        "        <service\n"
        "            android:name=\".n2n.N2nVpnService\"\n"
        "            android:exported=\"true\"\n"
        "            android:permission=\"android.permission.BIND_VPN_SERVICE\">\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.net.VpnService\" />\n"
        "            </intent-filter>\n"
        "        </service>"
    )
    if "</application>" not in src:
        fail("manifest: 找不到 </application>")
    else:
        src = src.replace("</application>", service + "\n    </application>", 1)
        print("已修改 manifest: 注册 VpnService")

    # 强制安装时解压 native 库：edge 以 liblegedge.so 形式打包，Android 10+ W^X
    # 禁止执行 filesDir 里的二进制，必须从已解压的 nativeLibraryDir 执行。
    # 若上游显式设为 false（库直接留在 APK 内），文件不会落盘，execve 会失败。
    import re
    if re.search(r'<application[^>]*android:extractNativeLibs="false"', src):
        src = re.sub(r'(<application[^>]*?)android:extractNativeLibs="false"',
                     r'android:extractNativeLibs="true"', src, count=1)
        print("manifest: extractNativeLibs false -> true")
    elif not re.search(r'<application[^>]*android:extractNativeLibs=', src):
        src = re.sub(r'<application',
                     '<application\n        android:extractNativeLibs="true"',
                     src, count=1)
        print("manifest: 新增 extractNativeLibs=true")
    p.write_text(src, encoding="utf-8")


def patch_proguard():
    """R8 混淆 keep 规则：保留 n2n 相关类（反射/进程间用到的字段）。"""
    p = REPO / "app" / "proguard-rules.pro"
    if p.exists():
        txt = p.read_text(encoding="utf-8")
        keep = "-keep class com.github.tvbox.osc.n2n.** { *; }"
        if keep not in txt:
            with p.open("a", encoding="utf-8") as fh:
                fh.write("\n# n2n\n" + keep + "\n")
            print("已修改 proguard-rules.pro: 追加混淆保留规则")


def patch_setting_layout():
    """上游把设置页左侧菜单容器设为 gone（仅一页设置内容时无需菜单）。
    注入 N2N 页后需要菜单作为入口，将其改为可见。"""
    p = REPO / "app" / "src" / "main" / "res" / "layout" / "activity_setting.xml"
    if not p.exists():
        fail("activity_setting.xml: 文件不存在")
    src = p.read_text(encoding="utf-8")
    if 'android:id="@+id/mGridView"' not in src:
        fail("activity_setting.xml: 找不到 mGridView")
    if 'android:visibility="gone"' in src:
        src = src.replace('android:visibility="gone"', 'android:visibility="visible"', 1)
        p.write_text(src, encoding="utf-8")
        print("已修改 activity_setting.xml: 显示设置页菜单栏（N2N 入口）")
    else:
        print("activity_setting.xml: 菜单栏已可见，跳过")


def main():
    # 1) 合并源码与资源（只新增/覆盖，不删除上游文件）
    merge_copy(SKEL / "java", REPO / "app" / "src" / "main" / "java")
    merge_copy(SKEL / "res", REPO / "app" / "src" / "main" / "res")
    # 2) 准备 assets/n2n 目录，等待后续工作流步骤下载 edge 二进制放入
    assets = REPO / "app" / "src" / "main" / "assets"
    n2n_assets = assets / "n2n"
    n2n_assets.mkdir(parents=True, exist_ok=True)
    print(f"已准备 {n2n_assets}")

    # 3) 依次打补丁
    patch_setting_activity()
    patch_home_entry()
    patch_setting_layout()
    patch_manifest()
    patch_proguard()
    print("n2n 集成完成")


if __name__ == "__main__":
    main()