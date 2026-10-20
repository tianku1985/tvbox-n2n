package com.github.tvbox.osc.n2n;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.github.tvbox.osc.base.App;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 远程接口请求附加本机参数：所有对接本系统的远程接口（远程配置 / 点播 / 直播 /
 * EPG / 自定义点播 / 升级检查）在发起请求前统一调用 {@link #append(String)}，
 * 在 URL 上拼接本机配置信息、系统版本、IP、MAC 等查询参数，服务端可按参数
 * （如 PHP 的 $_GET['mac']）实现按设备下发数据。
 *
 * 附加参数（URLEncoder 编码，已存在的同名参数不重复添加）：
 *   mac     本机 MAC（遍历 NetworkInterface 取第一个非回环接口的硬件地址；
 *           Android 6+ WifiInfo.getMacAddress 已恒返回 02:00:00:00:00:00，不可用；
 *           全部失败时兜底 ANDROID_ID）
 *   ip      本机第一个非回环 IPv4 地址（每次请求现取，跟随网络切换）
 *   model   机型 Build.MODEL
 *   brand   品牌 Build.BRAND
 *   device  设备名 Build.DEVICE
 *   sdk     系统 API 级别 Build.VERSION.SDK_INT
 *   release 系统版本号 Build.VERSION.RELEASE
 *   app     本应用 versionName
 *
 * assets/ 与 file:// 等本地路径不拼接。任何环节失败静默跳过，不影响原请求。
 */
public class DeviceParams {

    private static volatile String sMacCache = null;

    /** 给 url 拼接本机参数；本地路径或异常时原样返回 */
    public static String append(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            return url;
        }
        try {
            StringBuilder sb = new StringBuilder(u);
            sb.append(u.contains("?") ? "&" : "?");
            boolean first = true;
            first = put(sb, first, "mac", mac());
            first = put(sb, first, "ip", localIp());
            first = put(sb, first, "model", Build.MODEL);
            first = put(sb, first, "brand", Build.BRAND);
            first = put(sb, first, "device", Build.DEVICE);
            first = put(sb, first, "sdk", String.valueOf(Build.VERSION.SDK_INT));
            first = put(sb, first, "release", Build.VERSION.RELEASE);
            put(sb, first, "app", appVersion());
            return sb.toString();
        } catch (Throwable t) {
            return url;
        }
    }

    /** 追加一个参数；url 中已存在同名参数则跳过；返回是否仍是首个参数 */
    private static boolean put(StringBuilder sb, boolean first, String key, String value) {
        if (value == null || value.isEmpty()) {
            return first;
        }
        try {
            String base = sb.toString();
            if (base.contains(key + "=")) {
                return first;
            }
            if (!first) {
                sb.append("&");
            }
            sb.append(key).append("=").append(URLEncoder.encode(value, "UTF-8"));
            return false;
        } catch (Throwable t) {
            return first;
        }
    }

    /** 本机 MAC：第一个非回环、非虚拟且有硬件地址的网卡；失败兜底 ANDROID_ID */
    private static String mac() {
        if (sMacCache != null) {
            return sMacCache;
        }
        String mac = "";
        try {
            // 优先常见物理网卡名，保证 TV 盒子上取 eth0/wlan0 而非虚拟网卡
            String[] preferred = {"eth0", "wlan0"};
            for (String name : preferred) {
                mac = macOf(name);
                if (!mac.isEmpty()) {
                    break;
                }
            }
            if (mac.isEmpty()) {
                Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                if (en != null) {
                    for (NetworkInterface ni : Collections.list(en)) {
                        if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                            continue;
                        }
                        mac = macOf(ni.getName());
                        if (!mac.isEmpty()) {
                            break;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (mac.isEmpty()) {
            mac = androidId();
        }
        sMacCache = mac;
        return mac;
    }

    /** 读指定网卡的硬件地址，格式化为冒号分隔大写 */
    private static String macOf(String ifName) {
        try {
            NetworkInterface ni = NetworkInterface.getByName(ifName);
            if (ni == null) {
                return "";
            }
            byte[] hw = ni.getHardwareAddress();
            if (hw == null || hw.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hw.length; i++) {
                if (i > 0) {
                    sb.append(":");
                }
                sb.append(String.format("%02X", hw[i]));
            }
            String mac = sb.toString();
            // 过滤全零与 Android 6+ 的占位地址
            if ("00:00:00:00:00:00".equals(mac) || "02:00:00:00:00:00".equals(mac)) {
                return "";
            }
            return mac;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 本机第一个非回环 IPv4 地址 */
    private static String localIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) {
                return "";
            }
            for (NetworkInterface ni : Collections.list(en)) {
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                List<InetAddress> addrs = Collections.list(ni.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** ANDROID_ID（MAC 获取全失败时的兜底设备标识） */
    private static String androidId() {
        try {
            Context ctx = App.getInstance();
            String id = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return TextUtils.isEmpty(id) ? "" : id;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 本应用 versionName */
    private static String appVersion() {
        try {
            Context ctx = App.getInstance();
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }
}
