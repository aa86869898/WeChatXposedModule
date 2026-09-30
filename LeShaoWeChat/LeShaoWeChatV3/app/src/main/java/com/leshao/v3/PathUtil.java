package com.leshao.v3;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;

public class PathUtil {

    public static File getLeshaoRootDir(Context wxContext) {
        int userId = getMyUserId();
        File baseDir;
        if (userId <= 0) {
            // v1131: userId 反射失败(-1)或主用户(0)时, 用进程自身 Context 的 filesDir
            // (Context 路径本身已按 user 隔离, 避免拼出 /data/user/-1 非法路径)
            baseDir = wxContext.getFilesDir();
        } else {
            baseDir = wxContext.getCacheDir();
        }
        File rootDir = new File(baseDir, "leshao_v3");
        ensureDir(rootDir);
        return rootDir;
    }

    public static File getLeshaoRootDir() {
        int userId = getMyUserId();
        File baseDir;
        if (userId <= 0) {
            // v1131: -1 表示反射失败, 无 Context 时退化到 user0 路径(调用方 LogWriter 会在 -1 时跳过写入)
            baseDir = new File("/data/data/com.tencent.mm/files");
        } else {
            baseDir = new File("/data/user/" + userId + "/com.tencent.mm/cache");
        }
        File rootDir = new File(baseDir, "leshao_v3");
        ensureDir(rootDir);
        return rootDir;
    }

    private static void ensureDir(File dir) {
        try {
            if (!dir.exists()) {
                dir.mkdirs();
                XposedBridge.log("LeShaoV3: PathUtil mkdirs OK: " + dir.getAbsolutePath());
            }
        } catch (Exception e) {
            XposedBridge.log("LeShaoV3: PathUtil mkdirs FAIL: " + e.getMessage());
        }
    }

    public static File getLogDirectory(Context wxContext) {
        File dir = new File(getLeshaoRootDir(wxContext), "log");
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Exception ignored) {}
        return dir;
    }

    public static int getMyUserId() {
        try {
            Class<?> cls = Class.forName("android.os.UserHandle");
            Method m = cls.getMethod("myUserId");
            return (int) m.invoke(null);
        } catch (Exception e) {
            // v1131: 反射失败返回 -1(此前返回 0 会误导调用方写入 user0 目录)
            return -1;
        }
    }
}
