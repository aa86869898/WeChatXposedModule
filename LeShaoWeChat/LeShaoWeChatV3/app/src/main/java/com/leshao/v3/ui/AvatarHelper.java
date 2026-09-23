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
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.ImageView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.VersionCompat;

import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.io.FilenameFilter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final ExecutorService sIo = Executors.newFixedThreadPool(2);

    private static ClassLoader getWeChatCL() {
        return ContextManager.getClassLoader();
    }

    private static void ensureInit() {
        if (sInited) return;
        synchronized (AvatarHelper.class) {
            if (sInited) return;
            try {
                Context ctx = ContextManager.getAppContext();
                if (ctx == null) return;

                ClassLoader cl = getWeChatCL();
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

        // 数据库.md: 内核已解密句柄 qf5.k0.getPath() 给出 EnMicroMsg.db 路径,
        // 其父目录即账号目录(含 avatar/), 最可靠且不依赖混淆方法名更名。
        dir = tryDbPathDir();
        if (dir != null && testDir(dir)) return dir;

        dir = tryMethodA();
        if (dir != null && testDir(dir)) return dir;

        dir = tryMethodB(ctx);
        if (dir != null && testDir(dir)) return dir;

        dir = tryFallback(ctx);
        if (dir != null && testDir(dir)) return dir;

        return null;
    }

    /**
     * 经内核链路取账号目录:
     * gp0.j1.v(tn3.c4) -> h2.cj() -> ContactStorage(j4).d -> qf5.k0.getPath()
     * getPath() 返回 .../MicroMsg/&lt;hash&gt;/EnMicroMsg.db, 父目录即账号目录。
     */
    private static String tryDbPathDir() {
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return null;
            Class<?> kernel = cl.loadClass("gp0.j1");
            java.lang.reflect.Method v = kernel.getDeclaredMethod("v", Class.class);
            v.setAccessible(true);
            Object plugin = v.invoke(null, cl.loadClass("tn3.c4"));
            if (plugin == null) return null;
            Object storage = plugin.getClass().getMethod("cj").invoke(plugin);
            if (storage == null) return null;
            java.lang.reflect.Field dField = storage.getClass().getDeclaredField("d");
            dField.setAccessible(true);
            Object db = dField.get(storage);
            if (db == null) return null;
            Object path = db.getClass().getMethod("getPath").invoke(db);
            if (!(path instanceof String) || ((String) path).isEmpty()) return null;
            File dbFile = new File((String) path);
            File dir = dbFile.getParentFile();
            if (dir != null && dir.isDirectory()) {
                LogWriter.log(TAG, "DbPath dir: " + dir.getAbsolutePath());
                return dir.getAbsolutePath() + "/";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void initJ1OnMainThread(ClassLoader cl) {
        if (sJ1InitDone) return;
        synchronized (AvatarHelper.class) {
            if (sJ1InitDone) return;
            try {
                // Try DexKit-discovered j1 service class first
                String dexKitJ1 = com.leshao.v3.hook.DexKitHelper.getJ1ServiceClass();
                if (dexKitJ1 != null && !dexKitJ1.isEmpty()) {
                    try {
                        sCachedJ1Class = cl.loadClass(dexKitJ1);
                        for (java.lang.reflect.Method m : sCachedJ1Class.getDeclaredMethods()) {
                            m.setAccessible(true);
                        }
                        LogWriter.log(TAG, "j1 Class from DexKit OK: " + dexKitJ1);
                        sJ1InitDone = true;
                        return;
                    } catch (Throwable ignored) {}
                }
                // Fallback: try candidate list
                String[] j1Candidates = {"gp0.j1.j", "gp0.j1", "hm0.j1"};
                for (String j1Name : j1Candidates) {
                    try {
                        sCachedJ1Class = cl.loadClass(j1Name);
                        for (java.lang.reflect.Method m : sCachedJ1Class.getDeclaredMethods()) {
                            m.setAccessible(true);
                        }
                        LogWriter.log(TAG, "j1 Class cached on main thread OK: " + j1Name);
                        break;
                    } catch (Throwable ignored) {}
                }
                // Register post-scan callback to upgrade to DexKit result if needed
                if (sCachedJ1Class == null || !"gp0.j1.j".equals(sCachedJ1Class.getName())) {
                    com.leshao.v3.hook.DexKitHelper.addPostScanCallback(() -> {
                        String dkJ1 = com.leshao.v3.hook.DexKitHelper.getJ1ServiceClass();
                        if (dkJ1 != null && !dkJ1.isEmpty() && !dkJ1.equals(sCachedJ1Class.getName())) {
                            try {
                                sCachedJ1Class = cl.loadClass(dkJ1);
                                for (java.lang.reflect.Method m : sCachedJ1Class.getDeclaredMethods()) {
                                    m.setAccessible(true);
                                }
                                LogWriter.log(TAG, "j1 Class upgraded from DexKit: " + dkJ1);
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "j1 Class not available: " + e.getMessage());
            }
            sJ1InitDone = true;
        }
    }

    private static String tryMethodA() {
        try {
            if (sCachedJ1Class == null) return null;
            // Try to find a static method that returns an object with a method returning String path
            for (java.lang.reflect.Method uMethod : sCachedJ1Class.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(uMethod.getModifiers())) continue;
                if (uMethod.getParameterCount() != 0) continue;
                try {
                    Object uInstance = uMethod.invoke(null);
                    if (uInstance == null) continue;
                    for (java.lang.reflect.Method hMethod : uInstance.getClass().getDeclaredMethods()) {
                        if (hMethod.getReturnType() == String.class && hMethod.getParameterCount() == 0) {
                            String path = (String) hMethod.invoke(uInstance);
                            if (path != null && !path.isEmpty()) {
                                LogWriter.log(TAG, "MethodA (" + uMethod.getName() + "." + hMethod.getName() + ") OK: " + path);
                                return path;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "MethodA fail: " + e.getMessage());
        }
        return null;
    }

private static String tryMethodB(Context ctx) {
        try {
            ClassLoader cl = getWeChatCL();
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
            String base = "/data/user/" + (Process.myUid() / 100000) + "/com.tencent.mm/MicroMsg/";
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
            ClassLoader cl = getWeChatCL();
            Class<?> y3 = cl.loadClass("y3");
            long uin = (Long) y3.getDeclaredMethod("q0").invoke(null);
            if (uin > 0) return uin;
        } catch (Throwable ignored) {}

        try {
            ClassLoader cl = getWeChatCL();
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

        int target = sizePx > 0 ? sizePx : 80;

        // 1) 微信内存缓存 Bitmap（头像被显示过时最快最准）
        Bitmap mem = getCachedAvatarBitmap(wxid);
        if (mem != null && !mem.isRecycled()) {
            Bitmap round = makeRoundCorner(mem, target);
            sCache.put(wxid, round);
            if (mem != round) mem.recycle();
            return round;
        }

        // 2) 微信头像本地文件路径（无需自己拼 md5 目录）
        Bitmap fromPath = loadFromExactPath(getAvatarLocalPath(wxid), wxid, target);
        if (fromPath != null) return fromPath;

        // 3) 自拼 md5 磁盘路径兜底
        Bitmap fromFile = loadFromLocalFile(wxid, target);
        if (fromFile != null) return fromFile;

        // 4) 头像 URL（CDN）下载兜底：主微信/分身微信统一，不依赖本地缓存
        return loadFromUrl(wxid, target);
    }

    /**
     * 异步加载头像：后台线程执行 IO/网络，完成后切回主线程绑定到 ImageView。
     * fallback 先同步显示（通常是首字母占位图），避免列表项头像空白。
     */
    public static void loadAvatarAsync(final ImageView iv, final String wxid, final int sizePx, final Bitmap fallback) {
        if (iv == null || wxid == null || wxid.isEmpty()) return;
        iv.setTag(wxid);
        if (fallback != null && !fallback.isRecycled()) iv.setImageBitmap(fallback);
        sIo.execute(() -> {
            try {
                Bitmap bm = loadAvatar(wxid, sizePx);
                if (bm != null && !bm.isRecycled()) {
                    sMain.post(() -> {
                        try {
                            if (wxid.equals(iv.getTag())) iv.setImageBitmap(bm);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "sMain setImage fail: " + t.getMessage());
                        }
                    });
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "loadAvatarAsync fail for " + wxid + ": " + t.getMessage());
            }
        });
    }

    private static Bitmap loadFromUrl(String wxid, int target) {
        String url = getAvatarUrl(wxid, false);
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36");
            is = conn.getInputStream();
            Bitmap bm = BitmapFactory.decodeStream(is);
            if (bm != null && !bm.isRecycled()) {
                Bitmap round = makeRoundCorner(bm, target);
                sCache.put(wxid, round);
                if (bm != round) bm.recycle();
                return round;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "loadFromUrl fail for " + wxid + ": " + e.getMessage());
        } finally {
            if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
        }
        return null;
    }

    /**
     * 微信原生头像服务：com.tencent.mm.modelavatar.d1
     * 8.0.78: hj() 方法已不存在，改用 findClass 后反射遍历可用方法
     * 读内存缓存 Bitmap；头像未被 UI 显示过时返回 null。
     */
    public static Bitmap getCachedAvatarBitmap(String wxid) {
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return null;
            Class<?> d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl);
            // 8.0.78: hj() 不存在，尝试查找返回 Bitmap 的静态方法
            for (java.lang.reflect.Method m : d1.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != Bitmap.class) continue;
                if (m.getParameterCount() != 0) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(null);
                    if (r == null) continue;
                    return (Bitmap) XposedHelpers.callMethod(r, "f", wxid, false, 0, null);
                } catch (Throwable ignored) {}
            }
            // 兜底：尝试 hj() 的旧逻辑
            try {
                Object r = XposedHelpers.callStaticMethod(d1, "hj");
                if (r == null) return null;
                return (Bitmap) XposedHelpers.callMethod(r, "f", wxid, false, 0, null);
            } catch (NoSuchMethodError ignored) {}
            return null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "getCachedAvatarBitmap error: " + t.getMessage());
            return null;
        }
    }

    /**
     * 微信头像本地文件路径：com.tencent.mm.modelavatar.d1.ij() -> z -> f(username,false,false)
     * 8.0.78: ij() 可能已更名，用 try/catch 兜底
     */
    public static String getAvatarLocalPath(String wxid) {
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return null;
            Class<?> d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl);
            Object z = null;
            // v955: ij() 在 3180 已不存在(实证 NoSuchMethod), 改为枚举静态无参方法
            // 返回非空非基本类型的候选(头像存储服务), 逐个尝试 f(String,boolean,boolean)
            try {
                z = XposedHelpers.callStaticMethod(d1, "ij");
            } catch (Throwable ignored) {}
            if (z == null) {
                for (java.lang.reflect.Method m : d1.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 0) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt == null || rt.isPrimitive() || rt == String.class || rt == Void.class) continue;
                    try {
                        m.setAccessible(true);
                        Object cand = m.invoke(null);
                        if (cand == null) continue;
                        String p = (String) XposedHelpers.callMethod(cand, "f", wxid, false, false);
                        if (p != null && !p.isEmpty()) return p;
                    } catch (Throwable ignored) {}
                }
                return null;
            }
            String path = (String) XposedHelpers.callMethod(z, "f", wxid, false, false);
            return path;
        } catch (Throwable t) {
            LogWriter.log(TAG, "getAvatarLocalPath error: " + t.getMessage());
            return null;
        }
    }

    /**
     * 微信头像 URL：com.tencent.mm.modelavatar.d1.mj() -> s0 -> x0(username) -> r0
     * 大图 c()，小图 d()。
     * 8.0.78: mj() 可能已更名，用 try/catch 兜底
     */
    public static String getAvatarUrl(String wxid, boolean big) {
        if (wxid == null || wxid.isEmpty()) return null;
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return null;
            Class<?> d1 = XposedHelpers.findClass("com.tencent.mm.modelavatar.d1", cl);

            // 1) 兼容旧版链路: d1.mj() -> s0.x0(username) -> c()/d()
            try {
                Object s0 = XposedHelpers.callStaticMethod(d1, "mj");
                String u = resolveAvatarUrl(s0, wxid, big);
                if (u != null) return u;
            } catch (Throwable ignored) {}

            // 2) 8.0.78 mj() 已更名: 枚举 d1 静态无参方法, 找到能产出 URL 的头像服务
            for (java.lang.reflect.Method m : d1.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                Class<?> rt = m.getReturnType();
                if (rt == null || rt.isPrimitive() || rt == String.class
                        || rt == Void.class || rt == Class.class) continue;
                Object svc;
                try {
                    m.setAccessible(true);
                    svc = m.invoke(null);
                } catch (Throwable t) {
                    continue;
                }
                if (svc == null) continue;
                String u = resolveAvatarUrl(svc, wxid, big);
                if (u != null) return u;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getAvatarUrl error: " + t.getMessage());
        }
        return null;
    }

    /** 在头像服务对象上查找 (String)->URL持有对象 的方法，兼容 x0/f 等更名。 */
    private static String resolveAvatarUrl(Object svc, String wxid, boolean big) {
        if (svc == null) return null;
        for (java.lang.reflect.Method m : svc.getClass().getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != String.class) continue;
            Class<?> rt = m.getReturnType();
            if (rt.isPrimitive() || rt == String.class || rt == Void.class) continue;
            Object holder;
            try {
                m.setAccessible(true);
                holder = m.invoke(svc, wxid);
            } catch (Throwable t) {
                continue;
            }
            if (holder == null) continue;
            String u = callUrlGetter(holder, big);
            if (u != null) return u;
        }
        return null;
    }

    /** 从头像 URL 持有对象取 c()/d()（大图/小图），兼容无参 getter 更名。 */
    private static String callUrlGetter(Object holder, boolean big) {
        String[] names = big ? new String[]{"c", "d", "a", "b", "e"}
                             : new String[]{"d", "c", "a", "b", "e"};
        for (String n : names) {
            try {
                java.lang.reflect.Method g = holder.getClass().getDeclaredMethod(n);
                if (g.getParameterCount() != 0 || g.getReturnType() != String.class) continue;
                g.setAccessible(true);
                Object v = g.invoke(holder);
                if (v instanceof String && !((String) v).isEmpty()) return (String) v;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Bitmap loadFromExactPath(String path, String wxid, int target) {
        if (path == null || path.isEmpty()) return null;
        File f = new File(path);
        if (!f.exists()) return null;
        try {
            Bitmap bm = decodeFile(path, target);
            if (bm != null) {
                Bitmap round = makeRoundCorner(bm, target);
                sCache.put(wxid, round);
                if (bm != round) bm.recycle();
                return round;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "load err for " + wxid + " path=" + path + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * 用微信官方 API 直接把真实头像绑定到 ImageView（跨进程异步加载）。
     * 这是最可靠的头像显示方式，避免依赖磁盘路径/账号目录查找。
     * @return true 表示已交给微信加载；false 表示调用失败，需回退其它方式
     */
    public static boolean bindAvatar(ImageView iv, String wxid) {
        if (iv == null || wxid == null || wxid.isEmpty()) return false;
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return false;

            // 方案1：AnyProcessAvatarAttacher（feature.avatar.s），文档推荐跨进程绑定
            try {
                Class<?> attacher = XposedHelpers.findClass("com.tencent.mm.feature.avatar.s", cl);
                if (tryBind(attacher, iv, wxid, "feature.avatar.s", null)) return true;
            } catch (Throwable e1) {
                LogWriter.log(TAG, "feature.avatar.s 不可用: " + e1.getMessage());
            }

            // 方案2：pluginsdk.ui.a 的静态头像方法
            try {
                Class<?> a = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.a", cl);
                if (tryBind(a, iv, wxid, "pluginsdk.ui.a", null)) return true;
            } catch (Throwable e2) {
                LogWriter.log(TAG, "pluginsdk.ui.a 不可用: " + e2.getMessage());
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "bindAvatar fail for " + wxid + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * 在指定类里寻找「(ImageView 或其父类, String)」的静态头像绑定方法并调用。
     * 优先按 preferredName 精确匹配，其次枚举所有静态方法兜底。
     */
    private static boolean tryBind(Class<?> c, ImageView iv, String wxid, String tag, String preferredName) {
        // 1) 精确方法名 + (ImageView, String)
        if (preferredName != null) {
            if (invokeExact(c, preferredName, ImageView.class, iv, wxid, tag)) return true;
            if (invokeExact(c, preferredName, android.view.View.class, iv, wxid, tag)) return true;
        }
        // 2) 枚举所有静态方法，匹配 (ImageView/View 兼容, String)
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 2) continue;
            if (pts[1] != String.class) continue;
            if (!pts[0].isAssignableFrom(ImageView.class)) continue;
            try {
                m.setAccessible(true);
                m.invoke(null, iv, wxid);
                LogWriter.log(TAG, "bindAvatar OK via " + tag + "." + m.getName()
                        + "(" + pts[0].getSimpleName() + ",String)");
                return true;
            } catch (Throwable inv) {
                LogWriter.log(TAG, "bindAvatar invoke " + tag + "." + m.getName() + " fail: " + inv.getMessage());
            }
        }
        return false;
    }

    private static boolean invokeExact(Class<?> c, String name, Class<?> viewType,
                                       ImageView iv, String wxid, String tag) {
        try {
            java.lang.reflect.Method m = c.getDeclaredMethod(name, viewType, String.class);
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) return false;
            m.setAccessible(true);
            m.invoke(null, iv, wxid);
            LogWriter.log(TAG, "bindAvatar OK via " + tag + "." + name + "("
                    + viewType.getSimpleName() + ",String)");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (Throwable t) {
            LogWriter.log(TAG, "bindAvatar " + tag + "." + name + "(" + viewType.getSimpleName()
                    + ",String) invoke fail: " + t.getMessage());
            return false;
        }
    }

    private static Bitmap loadFromLocalFile(String wxid, int target) {
        String m = md5(wxid);
        if (m.isEmpty()) return null;

        ensureInit();
        if (sAccountDir == null) return null;

        String base = sAccountDir + "avatar/" + m.substring(0, 2) + "/" + m.substring(2, 4) + "/";
        String[] paths = {
            base + "user_" + m + ".png",
            base + "user_" + m + ".jpg",
            base + m + ".png",
            base + m + ".jpg",
            base + "user_" + m + ".hd",
        };

        for (String path : paths) {
            File f = new File(path);
            if (!f.exists()) continue;
            try {
                Bitmap bm = decodeFile(path, target);
                if (bm != null) {
                    Bitmap round = makeRoundCorner(bm, target);
                    sCache.put(wxid, round);
                    if (bm != round) bm.recycle();
                    return round;
                }
            } catch (Throwable e) {
                LogWriter.log(TAG, "load err for " + wxid + " path=" + path + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static Bitmap loadFromWeChatApi(String wxid, int target) {
        try {
            ClassLoader cl = getWeChatCL();
            if (cl == null) return null;

            // com.tencent.mm.pluginsdk.ui.a$b is WeChat's avatar display helper
            Class<?> avatarClass = cl.loadClass("com.tencent.mm.pluginsdk.ui.a$b");
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return null;

            // Try a.b.a(Context, username, ...) -> Drawable
            for (String methodName : new String[]{"a", "b", "c"}) {
                try {
                    for (java.lang.reflect.Method method : avatarClass.getDeclaredMethods()) {
                        if (!method.getName().equals(methodName)) continue;
                        Class<?>[] params = method.getParameterTypes();
                        if (params.length < 2) continue;
                        if (params[0] != Context.class) continue;
                        if (params[1] != String.class) continue;

                        Object[] args = new Object[params.length];
                        args[0] = ctx;
                        args[1] = wxid;
                        for (int i = 2; i < params.length; i++) {
                            if (params[i] == int.class) args[i] = 0;
                            else if (params[i] == boolean.class) args[i] = false;
                            else if (params[i] == float.class) args[i] = 0f;
                            else args[i] = null;
                        }
                        method.setAccessible(true);
                        Object result = method.invoke(null, args);
                        if (result instanceof Bitmap) {
                            Bitmap round = makeRoundCorner((Bitmap) result, target);
                            sCache.put(wxid, round);
                            return round;
                        }
                        if (result instanceof Drawable) {
                            Bitmap bm = drawableToBitmap((Drawable) result, target);
                            if (bm != null) {
                                Bitmap round = makeRoundCorner(bm, target);
                                sCache.put(wxid, round);
                                if (bm != round) bm.recycle();
                                return round;
                            }
                        }
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "WeChat API load fail: " + e.getMessage());
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int size) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        try {
            Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bm);
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
            return bm;
        } catch (Throwable e) { return null; }
    }

    private static Bitmap decodeFile(String path, int target) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        int sample = 1;
        while (opts.outWidth / sample > target * 2 || opts.outHeight / sample > target * 2) {
            sample *= 2;
        }
        opts.inSampleSize = sample;
        opts.inJustDecodeBounds = false;

        return BitmapFactory.decodeFile(path, opts);
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

    /** 首字母占位头像(圆形底色 + 文字), 用于真实头像加载前的兜底显示。 */
    public static Bitmap letterAvatar(String letter, int sizePx) {
        int size = sizePx > 0 ? sizePx : 80;
        if (letter == null || letter.isEmpty()) letter = "?";
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint bg = new Paint();
        bg.setAntiAlias(true);
        bg.setColor(0xFF8A8F8B);
        canvas.drawRoundRect(0, 0, size, size, size / 2f, size / 2f, bg);
        Paint txt = new Paint();
        txt.setAntiAlias(true);
        txt.setColor(0xFFFFFFFF);
        txt.setTextSize(size * 0.45f);
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
        float y = size / 2f - (txt.descent() + txt.ascent()) / 2f;
        canvas.drawText(letter.substring(0, 1).toUpperCase(), size / 2f, y, txt);
        return bm;
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
