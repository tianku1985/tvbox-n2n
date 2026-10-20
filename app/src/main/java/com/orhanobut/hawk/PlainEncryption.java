package com.orhanobut.hawk;

/**
 * 不做加解密的 Hawk 加密实现：本地 JSON 配置文件保持明文，便于直接查看与手工修改。
 * Hawk 2.0.1 的 Encryption 接口是包私有的，因此本类必须放在 com.orhanobut.hawk 包内。
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
