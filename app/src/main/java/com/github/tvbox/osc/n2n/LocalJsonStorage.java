package com.github.tvbox.osc.n2n;

import android.content.Context;
import android.util.Log;

import com.orhanobut.hawk.Storage;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Hawk 的存储后端实现：把全部配置项读写到本地 JSON 文件
 * （{@code filesDir/tvbox-config-local.json}），替代默认的 SharedPreferences/SQLite 存储。
 *
 * <p>这样 TVBox 的所有配置（接口地址、主题、通知、入口行、开关等）都集中在一个人可读的
 * JSON 文件里，可直接查看/修改/下发；配合 assets 内置默认 JSON 与远程 JSON 覆盖。</p>
 *
 * <p>文件格式：每个键保存为 {@code {"t":"<类型信息>","v":"<Hawk 序列化正文>"}}，
 * 其中 Hawk 原始序列化串形如 {@code keyClazz#valueClazz#dataTypeV@cipherText}，
 * 这里把 {@code V} 之前的部分存进 {@code t}、之后的正文存进 {@code v}，读取时原样拼回，
 * 保证与 Hawk 默认存储的行为完全一致（二进制安全、类型不丢失）。</p>
 *
 * <p>线程安全：内部维护内存缓存 + 同步写文件；读操作命中缓存（比 SharedPreferences 更快）。</p>
 */
public class LocalJsonStorage implements Storage {

    private static final String TAG = "LocalJsonStorage";

    /** 本地配置文件名（位于 app 私有 files 目录） */
    public static final String FILE_NAME = "tvbox-config-local.json";

    private static final String K_TYPE = "t";
    private static final String K_VALUE = "v";
    private static final String K_RAW = "__raw";

    private static final char SERIAL_DELIMITER = '@';
    private static final char SERIAL_VERSION = 'V';

    private final File file;
    private final Object lock = new Object();
    private JSONObject data = new JSONObject();
    private boolean loaded = false;

    public LocalJsonStorage(Context context) {
        file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /** 当前本地配置文件（便于外部查看/导出） */
    public File getFile() {
        return file;
    }

    // ==================== Storage 实现 ====================

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(String key) {
        if (key == null) {
            return null;
        }
        synchronized (lock) {
            ensureLoaded();
            JSONObject entry = data.optJSONObject(key);
            if (entry == null) {
                return null;
            }
            String raw = entry.optString(K_RAW, "");
            if (!raw.isEmpty()) {
                return (T) raw;
            }
            if (!entry.has(K_TYPE) || !entry.has(K_VALUE)) {
                return null;
            }
            return (T) (entry.optString(K_TYPE, "") + SERIAL_VERSION + SERIAL_DELIMITER
                    + entry.optString(K_VALUE, ""));
        }
    }

    @Override
    public <T> boolean put(String key, T value) {
        if (key == null || value == null) {
            return false;
        }
        String serializedText = String.valueOf(value);
        synchronized (lock) {
            ensureLoaded();
            JSONObject entry = new JSONObject();
            try {
                int at = serializedText.indexOf(SERIAL_DELIMITER);
                if (at > 0 && serializedText.charAt(at - 1) == SERIAL_VERSION) {
                    entry.put(K_TYPE, serializedText.substring(0, at - 1));
                    entry.put(K_VALUE, serializedText.substring(at + 1));
                } else {
                    entry.put(K_RAW, serializedText);
                }
                data.put(key, entry);
            } catch (Throwable th) {
                try {
                    entry = new JSONObject();
                    entry.put(K_RAW, serializedText);
                    data.put(key, entry);
                } catch (Throwable ignored) {
                    return false;
                }
            }
            return flush();
        }
    }

    @Override
    public boolean delete(String key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            ensureLoaded();
            if (!data.has(key)) {
                return false;
            }
            data.remove(key);
            return flush();
        }
    }

    @Override
    public boolean deleteAll() {
        synchronized (lock) {
            ensureLoaded();
            data = new JSONObject();
            return flush();
        }
    }

    @Override
    public long count() {
        synchronized (lock) {
            ensureLoaded();
            return data.length();
        }
    }

    @Override
    public boolean contains(String key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            ensureLoaded();
            return data.has(key);
        }
    }

    // ==================== 文件读写 ====================

    /** 首次访问时从本地 JSON 文件加载（容错：支持注释、BOM、尾逗号） */
    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (!file.exists()) {
                return;
            }
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(file);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = fis.read(buf)) > 0) {
                    bos.write(buf, 0, len);
                }
                String raw = bos.toString("UTF-8");
                if (raw != null && raw.trim().length() > 0) {
                    data = RemoteConfig.parseLoose(raw);
                }
            } finally {
                if (fis != null) {
                    try {
                        fis.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "load local config failed: " + th.getMessage());
            data = new JSONObject();
        }
    }

    /** 内存缓存落盘（工作线程同步执行，配置项数量少、文件很小） */
    private boolean flush() {
        FileOutputStream fos = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            fos = new FileOutputStream(file);
            fos.write(data.toString(2).getBytes("UTF-8"));
            fos.flush();
            return true;
        } catch (Throwable th) {
            Log.i(TAG, "flush local config failed: " + th.getMessage());
            return false;
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
