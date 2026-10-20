package com.github.tvbox.osc.n2n;

import com.orhanobut.hawk.Encryption;

/**
 * 不做加解密的 Hawk 加密实现：本地 JSON 配置文件保持明文，便于直接查看与手工修改。
 * 配置只落在 app 私有目录（filesDir），不对外暴露。
 */
public class PlainEncryption implements Encryption {

    @Override
    public boolean init() {
        return true;
    }

    @Override
    public String encrypt(String key, String value) {
        return value;
    }

    @Override
    public String decrypt(String key, String value) {
        return value;
    }
}
