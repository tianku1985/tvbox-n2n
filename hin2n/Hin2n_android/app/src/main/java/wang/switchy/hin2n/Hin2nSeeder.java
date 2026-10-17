package wang.switchy.hin2n;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import wang.switchy.hin2n.storage.db.base.model.N2NSettingModel;

/**
 * 把内置 assets/hin2n-config.json 播种到 hin2n 模块数据库：
 * - configs：首次启动（greenDAO 中一条配置都没有）时插入种子配置；
 * - bootStart / staticRoutes：同样仅在首启 DB 为空时写入，之后一切以用户在界面上的修改为准。
 */
public class Hin2nSeeder {

    private static final String TAG = "Hin2nSeeder";

    public static void seed(Context ctx, String rawJson) {
        if (ctx == null || rawJson == null || rawJson.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject h = new JSONObject(rawJson.trim());
            boolean empty = Hin2nCore.configCount() == 0;

            if (empty && h.has("bootStart")) {
                Hin2nCore.setStartAtBoot(ctx, h.optBoolean("bootStart", false));
            }
            if (empty && h.has("staticRoutes")) {
                Hin2nCore.setStaticRoutes(ctx, h.optString("staticRoutes", ""));
            }

            JSONArray arr = h.optJSONArray("configs");
            if (!empty || arr == null || arr.length() == 0) {
                return;
            }

            List<N2NSettingModel> models = new ArrayList<>();
            int selectedIndex = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                N2NSettingModel m = parseConfig(o, i == 0);
                if (m == null) {
                    continue;
                }
                if (o.optBoolean("selected", false) && selectedIndex < 0) {
                    selectedIndex = models.size();
                }
                models.add(m);
            }
            if (models.isEmpty()) {
                return;
            }
            if (selectedIndex < 0) {
                selectedIndex = 0;
            }
            Long currentId = null;
            for (int i = 0; i < models.size(); i++) {
                N2NSettingModel m = models.get(i);
                m.setIsSelcected(i == selectedIndex);
                long id = Hin2nCore.configInsert(m);
                if (i == selectedIndex) {
                    currentId = id;
                }
            }
            N2NSettingModel selected = models.get(selectedIndex);
            if (selected.getId() != null) {
                currentId = selected.getId();
            }
            if (currentId != null) {
                Hin2nCore.setCurrentSettingId(ctx, currentId);
            }
        } catch (Throwable t) {
            Log.e(TAG, "seed hin2n configs failed", t);
        }
    }

    private static N2NSettingModel parseConfig(JSONObject o, boolean first) {
        try {
            String name = o.optString("name", "").trim();
            if (name.isEmpty()) {
                name = "内置配置" + (first ? "" : String.valueOf(System.currentTimeMillis() % 100000));
            }
            int version = o.optInt("version", 1);
            if (version < 0 || version > 3) {
                version = 1;
            }
            int ipMode = o.has("ipMode") ? o.optInt("ipMode", 0)
                    : (o.optBoolean("fromSupernode", false) ? 1 : 0);
            String ip = o.optString("ip", ipMode == 1 ? "0.0.0.0" : "");
            String netmask = o.optString("netmask", "255.255.255.0");
            String community = o.optString("community", "");
            String password = o.optString("password", "");
            String devDesc = o.optString("devDesc", "");
            String superNode = o.optString("superNode", defaultSuperNode(version));
            String superNodeBackup = o.optString("superNodeBackup", "");
            String macAddr = o.optString("macAddr", "");
            int mtu = o.optInt("mtu", 1328);
            String localIP = o.has("localIP") ? o.optString("localIP", "")
                    : (o.optBoolean("localIpAuto", false) ? "auto" : "");
            int holePunch = o.optInt("holePunchInterval", 20);
            boolean resolve = o.optBoolean("resolveSupernodeIp", false);
            int localPort = o.optInt("localPort", 0);
            boolean allowRouting = o.optBoolean("allowRouting", false);
            boolean dropMulticast = o.has("dropMulticast")
                    ? o.optBoolean("dropMulticast", true)
                    : !o.optBoolean("acceptMulticast", false);
            boolean httpTunnel = o.optBoolean("httpTunnel", false);
            int trace = o.optInt("traceLevel", 2);
            String gateway = o.optString("gatewayIp", "");
            String dns = o.optString("dnsServer", "");
            String enc = o.optString("encryptionMode", "Twofish");
            boolean headerEnc = o.optBoolean("headerEnc", false);
            boolean more = o.optBoolean("moreSettings", false);
            return new N2NSettingModel(null, version, name, ipMode, ip, netmask, community, password,
                    devDesc, superNode, more, superNodeBackup, macAddr, mtu, localIP, holePunch,
                    resolve, localPort, allowRouting, dropMulticast, httpTunnel, trace, false,
                    gateway, dns, enc, headerEnc);
        } catch (Throwable t) {
            Log.e(TAG, "bad hin2n config item", t);
            return null;
        }
    }

    private static String defaultSuperNode(int version) {
        switch (version) {
            case 0:
                return "hin2n.nton.eu.org:10082";
            case 2:
                return "hin2n.nton.eu.org:10088";
            case 3:
                return "supernode.ntop.org:7777";
            case 1:
            default:
                return "hin2n.nton.eu.org:10086";
        }
    }
}
