package com.github.tvbox.osc.n2n;

import com.github.tvbox.osc.util.AES;

/**
 * 接口密文统一解密：本地内置配置 + 所有远程接收接口（远程配置 / 点播 / 直播 / EPG /
 * 自定义点播 / 升级检查）共用。规则：内容是 json（或 jsonc）直接解析；不是 json
 * 时按密文先解密再加载，解密失败原样返回（由上层解析兜底）。
 *
 * 支持两种密文形态：
 *  ① ke 前缀模式（默认，无需任何配置，对应服务端 PHP）：
 *       $key = md5(time() . "AD80F93B542B" . "8b14ecf6ae028f9b6d25fa547eabad85");
 *       $ke  = substr($key, 13);                       // 19 位小写 hex
 *       $out = $ke . bin2hex(openssl_encrypt($data, 'AES-128-ECB', substr($ke,0,16), OPENSSL_RAW_DATA));
 *     密文 = ke(19 位 [0-9a-f]) + hex(偶数长度) → 总长为奇数且 ≥ 51；
 *     解密取前 19 字符为 ke，前 16 字符为 AES-128-ECB 密钥，解剩余 hex。
 *  ② 固定密钥模式（秘钥自定义）：密文 = bin2hex(AES-128-ECB($data, 秘钥前16字节))
 *     总长为偶数且 ≥ 32。秘钥来源：json "secretKey" 非空优先，否则用下方 SECRET_KEY。
 *
 * ★★★ 秘钥自定义修改位置 ★★★
 * 改下面 SECRET_KEY 常量后重新编译即可（例如 "AD80F93B542B8b14"）。
 * 留空 = 仅支持 ① ke 前缀模式。
 */
public class ConfigCrypto {

    /** 固定密钥模式的秘钥（自定义修改位置）；json "secretKey" 非空时优先于本常量 */
    public static String SECRET_KEY = "";

    /** 入口：raw 是明文直接返回；是密文解密后返回；失败返回原文，永不抛异常 */
    public static String maybeDecrypt(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return raw;
        }
        // 明文形态：json / jsonc（允许 // 与 /* 注释）/ 直播 txt 列表
        char c0 = s.charAt(0);
        if (c0 == '{' || c0 == '[' || c0 == '/' || s.startsWith("#EXTM3U") || s.contains("#genre#")) {
            return raw;
        }
        // TVBox 原生 "2423" CBC 加密格式（ApiConfig.FindResult 自行处理），这里不碰
        if (s.startsWith("2423")) {
            return raw;
        }
        // 密文只可能是纯小写 hex（PHP bin2hex 输出）
        if (!isLowerHex(s) || s.length() < 32) {
            return raw;
        }
        // ① ke 前缀模式：ke(19) + hex(偶数) → 奇数长度且 ≥ 51
        if (s.length() % 2 == 1 && s.length() >= 51) {
            String ke = s.substring(0, 19);
            String plain = AES.ECB(s.substring(19), ke.substring(0, 16));
            if (looksDecrypted(plain)) {
                return plain.trim();
            }
        }
        // ② 固定密钥模式：偶数长度
        String key = secretKey();
        if (s.length() % 2 == 0 && !key.isEmpty()) {
            String plain = AES.ECB(s, key.length() > 16 ? key.substring(0, 16) : key);
            if (looksDecrypted(plain)) {
                return plain.trim();
            }
        }
        return raw;
    }

    /** 有效秘钥：json "secretKey" 覆盖值非空优先，否则 Java 常量 SECRET_KEY */
    private static String secretKey() {
        String k = RemoteConfig.secretKey();
        if (k != null && !k.trim().isEmpty()) {
            return k.trim();
        }
        return SECRET_KEY == null ? "" : SECRET_KEY.trim();
    }

    /** 解密结果形态校验：json 或直播 txt（m3u / genre 列表） */
    private static boolean looksDecrypted(String plain) {
        if (plain == null) {
            return false;
        }
        String t = plain.trim();
        if (t.isEmpty()) {
            return false;
        }
        char c = t.charAt(0);
        return c == '{' || c == '[' || t.startsWith("#EXTM3U") || t.contains("#genre#");
    }

    /** 是否纯小写 hex 串 */
    private static boolean isLowerHex(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
