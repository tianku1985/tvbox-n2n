package com.github.tvbox.osc.n2n;

import android.content.Context;

import com.orhanobut.hawk.Hawk;

import java.io.Serializable;
import java.util.Locale;
import java.util.Random;

/**
 * n2n 连接配置：保存于 Hawk 本地存储，可序列化随 Intent 传给 VpnService。
 */
public class N2nConfig implements Serializable {

    private static final long serialVersionUID = 1L;
    /** Hawk 存储键名 */
    private static final String CFG_KEY = "n2n_config";

    /** n2n 社区名，同一社区才能互通 */
    public String community = "n2n";
    /** n2n 加密密钥（可为空） */
    public String key = "";
    /** 超级节点地址，格式 host:port */
    public String supernode = "n2n.ntop.org:7777";
    /** 分配的虚拟 IP，网内各端须唯一 */
    public String ip = "10.0.0.2";
    /** 子网掩码 */
    public String mask = "255.255.255.0";
    /** edge 使用的 MAC 地址（空则自动生成本地管理地址） */
    public String mac = "";
    /** 额外路由，格式如 192.168.8.0/24，多个以空格/逗号分隔 */
    public String extraRoute = "";
    /** 隧道 MTU */
    public int mtu = 1400;

    /** 读取配置，首次使用时自动生成默认值并保存 */
    public static N2nConfig load(Context ctx) {
        N2nConfig cfg = Hawk.get(CFG_KEY, (N2nConfig) null);
        if (cfg == null) {
            cfg = new N2nConfig();
            Hawk.put(CFG_KEY, cfg);
        }
        if (cfg.mac == null || cfg.mac.isEmpty()) {
            cfg.mac = randomMac();
            Hawk.put(CFG_KEY, cfg);
        }
        return cfg;
    }

    /** 保存配置 */
    public void save() {
        Hawk.put(CFG_KEY, this);
    }

    /** 生成随机 MAC，固定为本地管理地址(第 2 位比特=1) */
    public static String randomMac() {
        Random rnd = new Random();
        byte[] mac = new byte[6];
        rnd.nextBytes(mac);
        mac[0] = (byte) ((mac[0] & ~0x01) | 0x02);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", mac[i]));
        }
        return sb.toString();
    }

    /** 由掩码点分十进制计算前缀长度 */
    public static int prefixLengthOfMask(String mask) {
        try {
            byte[] b = java.net.InetAddress.getByName(mask).getAddress();
            int bits = 0;
            for (byte x : b) {
                bits += Integer.bitCount(x & 0xFF);
            }
            return bits;
        } catch (Exception e) {
            return 24;
        }
    }
}