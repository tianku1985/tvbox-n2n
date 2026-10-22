package com.github.tvbox.osc.util;

import com.github.tvbox.osc.base.App;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 直播 EPG 节目单本地磁盘缓存。
 *
 * 设计目标（对应需求「EPG 一次请求最近几天节目表缓存到本地，降低 EPG 服务端压力」）：
 *  1. 按「频道 + 日期」缓存节目单，App 重启后仍然有效，同一天内同一频道同一日期不重复请求网络；
 *  2. 支持服务端「一次请求返回多天」的响应格式（条目带 date 字段），客户端按天拆分落盘；
 *  3. 有效期规则：历史日期（昨天及更早）的节目单不会再变，永久有效；
 *     今天及未来日期的节目单可能被服务端调整，仅「拉取当天」内有效，跨天自动失效重新拉取。
 *
 * 存储结构：filesDir/epg_cache/ 下每频道一个文件，文件名 = MD5(频道名).json，内容：
 * {"days":{"2026-10-21":{"fetch":"2026-10-22","items":[{"start":"08:00","end":"09:00","title":"..."}]}}}
 *
 * 线程安全：所有公开方法 synchronized（直播界面切台/预取可能在工作线程并发调用）。
 */
public class EpgCache {

    /** 缓存目录名（应用私有文件目录下） */
    private static final String DIR_NAME = "epg_cache";
    /** 缓存保留的历史天数（超过该天数的旧日期在下次写入时清理，防止无限膨胀） */
    private static final int KEEP_DAYS = 10;

    private static final TimeZone TZ = TimeZone.getTimeZone("GMT+8:00");

    /** 获取缓存目录（不存在则创建；失败返回 null） */
    private static File dir() {
        try {
            File d = new File(App.getInstance().getFilesDir(), DIR_NAME);
            if (!d.exists() && !d.mkdirs()) {
                return null;
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 频道对应的缓存文件（频道名做 MD5 避免中文/特殊字符文件名校验问题） */
    private static File fileFor(String channel) {
        File dir = dirFor();
        if (dir == null) {
            return null;
        }
        return new File(dir, MD5.encode(channel) + ".json");
    }

    private static File dirFor() {
        return dir();
    }

    /** 今天日期串（GMT+8，yyyy-MM-dd；与服务端 EPG 日期口径一致） */
    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
        f.setTimeZone(TZ);
        return f.format(new Date());
    }

    /**
     * 读取某频道某日期的节目单缓存。
     *
     * @param channel 频道名
     * @param date    目标日期（yyyy-MM-dd）
     * @return 命中的节目单 JSON 数组（元素 {start,end,title}）；未命中/已失效/异常返回 null
     */
    public static synchronized JSONArray get(String channel, String date) {
        if (channel == null || channel.isEmpty() || date == null || date.isEmpty()) {
            return null;
        }
        File f = fileFor(channel);
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readFile(f));
            JSONObject days = root.optJSONObject("days");
            if (days == null) {
                return null;
            }
            JSONObject day = days.optJSONObject(date);
            if (day == null) {
                return null;
            }
            String now = today();
            if (date.compareTo(now) < 0) {
                // 历史日期：节目单不会再变，永久有效
                return day.optJSONArray("items");
            }
            // 今天/未来日期：仅拉取当天内有效（跨天可能改版，需重新拉取）
            if (now.equals(day.optString("fetch", ""))) {
                return day.optJSONArray("items");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写入某频道某日期的节目单缓存（含当天日期戳，供有效期判断）。
     * 写入时顺带清理超过 KEEP_DAYS 天的旧日期，控制文件体积。
     *
     * @param channel 频道名
     * @param date    节目单所属日期（yyyy-MM-dd）
     * @param items   节目单 JSON 数组（元素 {start,end,title}）；null/空数组也会记录（表示当日无节目，
     *                避免对「无节目」频道当天反复请求）
     */
    public static synchronized void put(String channel, String date, JSONArray items) {
        if (channel == null || channel.isEmpty() || date == null || date.isEmpty()) {
            return;
        }
        File f = fileFor(channel);
        if (f == null) {
            return;
        }
        try {
            JSONObject root;
            if (f.exists()) {
                try {
                    root = new JSONObject(readFile(f));
                } catch (Throwable t) {
                    root = new JSONObject();
                }
            } else {
                root = new JSONObject();
            }
            JSONObject days = root.optJSONObject("days");
            if (days == null) {
                days = new JSONObject();
                root.put("days", days);
            }
            JSONObject day = new JSONObject();
            day.put("fetch", today());
            day.put("items", items == null ? new JSONArray() : items);
            days.put(date, day);
            prune(days);
            writeFile(f, root.toString());
        } catch (Throwable ignored) {
        }
    }

    /** 清理超过 KEEP_DAYS 天的旧日期缓存（yyyy-MM-dd 字典序可直接比较） */
    private static void prune(JSONObject days) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH);
            f.setTimeZone(TZ);
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -KEEP_DAYS);
            String oldest = f.format(cal.getTime());
            java.util.Iterator<String> it = days.keys();
            java.util.List<String> del = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String k = it.next();
                if (k.compareTo(oldest) < 0) {
                    del.add(k);
                }
            }
            for (String k : del) {
                days.remove(k);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
