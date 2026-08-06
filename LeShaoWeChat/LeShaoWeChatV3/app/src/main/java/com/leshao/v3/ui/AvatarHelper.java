package com.leshao.v3.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FilenameFilter;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class AvatarHelper {

    private static final String TAG = "AvatarHelper";
    private static String sAccountDir;
    private static volatile boolean sInited = false;
    private static volatile Class<?> sCachedJ1Class = null;
    private static volatile boolean sJ1InitDone = false;

    private static final int MAX_CACHE = 80;
    private static final Map<String, Bitmap> sCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    private static void ensureInit() {
        if (sInited) return;
        synchronized (AvatarHelper.class) {
            if (sInited) return;
            try {
                Context ctx = ContextManager.getAppContext();
                if (ctx == null) return;

                ClassLoader cl = ContextManager.getClassLoader();
                if (cl != null) initJ1OnMainThread(cl);

                sAccountDir = findAccountDir(ctx);
                if (sAccountDir != null && !sAccountDir.isEmpty()) {
                    if (!sAccountDir.endsWith("/")) sAccountDir += "/";
                    sInited = true;
                    LogWriter.log(TAG, "init OK, dir=" + sAccountDir);
                } else {
                    LogWriter.log(TAG, "init ERR: cannot find account dir");
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "init err: " + e.getMessage());
            }
        }
    }

    private static String findAccountDir(Context ctx) {
        String dir;

        dir = tryMethodA();
        if (dir != null && testDir(dir)) return dir;

        dir = tryMethodB(ctx);
        if (dir != null && testDir(dir)) return dir;

        dir = tryFallback(ctx);
        if (dir != null && testDir(dir)) return dir;

        return null;
    }

    private static void initJ1OnMainThread(ClassLoader cl) {
        if (sJ1InitDone) return;
        synchronized (AvatarHelper.class) {
            if (sJ1InitDone) return;
            try {
                sCachedJ1Class = cl.loadClass("j1");
                sCachedJ1Class.getDeclaredMethod("u").setAccessible(true);
                sCachedJ1Class.getDeclaredMethod("h").setAccessible(true);
                LogWriter.log(TAG, "j1 Class cached on main thread OK");
            } catch (Throwable e) {
                LogWriter.log(TAG, "j1 Class not available: " + e.getMessage());
            }
            sJ1InitDone = true;
        }
    }

    private static String tryMethodA() {
        try {
            if (sCachedJ1Class == null) return null;
            Object uInstance = sCachedJ1Class.getDeclaredMethod("u").invoke(null);
            String path = (String) sCachedJ1Class.getDeclaredMethod("h").invoke(uInstance);
            if (path != null && !path.isEmpty()) {
                LogWriter.log(TAG, "MethodA (j1.u().h()) OK: " + path);
                return path;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "MethodA fail: " + e.getMessage());
        }
        return null;
    }

    private static String tryMethodB(Context ctx) {
        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> mp0b = cl.loadClass("mp0.b");
            String base = (String) mp0b.getDeclaredMethod("X").invoke(null);
            if (base == null || base.isEmpty()) return null;
            if (!base.endsWith("/")) base += "/";

            long uin = getUin(ctx);
            if (uin <= 0) return null;

            String hash;
            try {
                Class<?> hm0b0 = cl.loadClass("hm0.b0");
                hash = (String) hm0b0.getDeclaredMethod("e", int.class).invoke(null, (int) uin);
            } catch (Throwable e) {
                hash = md5("mm" + uin);
            }

            String path = base + "MicroMsg/" + hash + "/";
            LogWriter.log(TAG, "MethodB: " + path);
            return path;
        } catch (Throwable e) {
            LogWriter.log(TAG, "MethodB fail: " + e.getMessage());
        }
        return null;
    }

    private static String tryFallback(Context ctx) {
        try {
            String base = "/data/data/com.tencent.mm/MicroMsg/";
            File microMsgDir = new File(base);
            if (!microMsgDir.exists() || !microMsgDir.isDirectory()) return null;

            File[] subdirs = microMsgDir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.length() == 32 && new File(dir, name).isDirectory();
                }
            });
            if (subdirs == null) return null;

            for (File sub : subdirs) {
                File avatarDir = new File(sub, "avatar");
                if (avatarDir.exists() && avatarDir.isDirectory()) {
                    String path = sub.getAbsolutePath() + "/";
                    LogWriter.log(TAG, "Fallback found: " + path);
                    return path;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "Fallback fail: " + e.getMessage());
        }
        return null;
    }

    private static boolean testDir(String dir) {
        if (dir == null || dir.isEmpty()) return false;
        File avatarDir = new File(dir + "avatar");
        return avatarDir.exists() && avatarDir.isDirectory();
    }

    private static long getUin(Context ctx) {
        try {
            Object uv = ctx.getSharedPreferences("system_config_prefs", 0)
                .getAll().get("default_uin");
            if (uv != null) return Long.parseLong(uv.toString());
        } catch (Throwable ignored) {}

        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> y3 = cl.loadClass("y3");
            long uin = (Long) y3.getDeclaredMethod("q0").invoke(null);
            if (uin > 0) return uin;
        } catch (Throwable ignored) {}

        try {
            ClassLoader cl = ContextManager.getClassLoader();
            Class<?> y3 = cl.loadClass("y3");
            Object userInfo = y3.getDeclaredMethod("E0").invoke(null);
            long uin = (Long) userInfo.getClass().getDeclaredField("b").get(userInfo);
            if (uin > 0) return uin;
        } catch (Throwable ignored) {}

        return 0;
    }

    public static String getAvatarPath(String wxid) {
        ensureInit();
        if (sAccountDir == null || wxid == null || wxid.isEmpty()) return null;
        String m = md5(wxid);
        if (m.isEmpty()) return null;
        return sAccountDir + "avatar/" + m.substring(0, 2) + "/" + m.substring(2, 4) + "/user_" + m + ".png";
    }

    public static Bitmap loadAvatar(String wxid, int sizePx) {
        if (wxid == null || wxid.isEmpty()) return null;

        Bitmap cached = sCache.get(wxid);
        if (cached != null && !cached.isRecycled()) return cached;

        String path = getAvatarPath(wxid);
        if (path == null) return null;
        File f = new File(path);
        if (!f.exists()) return null;

        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opts);

            int sample = 1;
            int target = sizePx > 0 ? sizePx : 80;
            while (opts.outWidth / sample > target * 2 || opts.outHeight / sample > target * 2) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            opts.inJustDecodeBounds = false;

            Bitmap bm = BitmapFactory.decodeFile(path, opts);
            if (bm != null) {
                Bitmap round = makeRoundCorner(bm, target);
                sCache.put(wxid, round);
                if (bm != round) bm.recycle();
                return round;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "load err for " + wxid + ": " + e.getMessage());
        }
        return null;
    }

    public static Bitmap makeRoundCorner(Bitmap source, int size) {
        if (source == null) return null;
        try {
            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            Rect rect = new Rect(0, 0, size, size);
            RectF rectF = new RectF(rect);
            canvas.drawRoundRect(rectF, size / 2f, size / 2f, paint);
            paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
            canvas.drawBitmap(source, null, rect, paint);
            return output;
        } catch (Throwable e) { return source; }
    }

    public static void clearCache() {
        sCache.clear();
    }

    public static void resetInit() {
        sInited = false;
        sAccountDir = null;
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }
}
