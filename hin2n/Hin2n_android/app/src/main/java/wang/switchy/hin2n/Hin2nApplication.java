package wang.switchy.hin2n;

import android.app.Application;

public class Hin2nApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Hin2nCore.init(this);
        Hin2nCore.installCrashLogger(this);
        Hin2nSeeder.seed(this, readAssetText("hin2n-config.json"));
    }

    private String readAssetText(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            in.close();
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
