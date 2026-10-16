package wang.switchy.hin2n.tool;


import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;

/**
 * n2n 边缘节点日志文件读取（模块化后不再依赖任何 RecyclerView 适配器，
 * 界面侧可自行用定时器轮询 {@link #readTxtLimit} 刷新日志展示）。
 */
public class IOUtils {

    public static String readTxt(String txtPath) {
        File file = new File(txtPath);
        if (file.isFile() && file.exists()) {
            FileInputStream fileInputStream = null;
            InputStreamReader inputStreamReader = null;
            BufferedReader bufferedReader = null;
            try {
                fileInputStream = new FileInputStream(file);
                inputStreamReader = new InputStreamReader(fileInputStream);
                bufferedReader = new BufferedReader(inputStreamReader);
                StringBuilder stringBuilder = new StringBuilder();
                String text;
                while ((text = bufferedReader.readLine()) != null) {
                    stringBuilder.append(text);
                    stringBuilder.append("\n");
                }
                return stringBuilder.toString();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                close(fileInputStream);
                close(inputStreamReader);
                close(bufferedReader);
            }
        }
        return "";
    }

    /** 读取文件末尾最多 size 字节（日志展示用） */
    public static String readTxtLimit(String txtPath, int size) {
        File file = new File(txtPath);
        if (file.exists() && file.isFile()) {
            RandomAccessFile randomAccessFile = null;
            try {
                randomAccessFile = new RandomAccessFile(file, "r");
                long length = randomAccessFile.length();
                long start = 0;
                if (length > size) {
                    start = length - size;
                }
                randomAccessFile.seek(start);
                StringBuilder stringBuilder = new StringBuilder();
                String text;
                while ((text = randomAccessFile.readLine()) != null) {
                    stringBuilder.append(text);
                    stringBuilder.append("\n");
                }
                return stringBuilder.toString();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                close(randomAccessFile);
            }
        }
        return "";
    }

    public static boolean clearLogTxt(String txtPath) {
        File file = new File(txtPath);
        File fileBak = new File(txtPath + ".bak");
        if (file.exists()) {
            if (fileBak.exists()) {
                fileBak.delete();
            }
            try {
                file.renameTo(fileBak);
                return file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                return file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    public static void close(Closeable closeable) {
        try {
            if (null != closeable) {
                closeable.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
