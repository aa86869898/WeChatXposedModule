package com.leshao.v3;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.widget.TextView;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;

/**
 * 模块图标加载工具
 * 从模块 APK (com.leshao.v3) 加载 drawable 资源，供 hook 注入微信进程时使用。
 * 四级回退定位模块 APK：
 * 1. /proc/self/maps 解析（不限制 .apk 后缀，Xposed 可能只映射 .dex）
 * 2. PackageManager.getApplicationInfo（最直接的系统 API）
 * 3. pm path 命令
 * 4. 扫描 /data/app 目录
 */
public final class IconLoader {

    public static final String MODULE_PKG = "com.leshao.v3";

    public static final int IC_VOICE_FORWARD = 0;
    public static final int IC_VOICE_LIST    = 1;
    public static final int IC_SCHEDULE_SEND = 2;
    public static final int IC_LESHAO_GROUP  = 3;
    public static final int IC_LESHAO_ICON   = 4;

    private static String sModuleApkPath;
    private static Resources sModuleRes;

    @Keep
    private static final int[] KEEP_ICONS = {
        R.drawable.ic_voice_forward,
        R.drawable.ic_voice_list,
        R.drawable.ic_schedule_send,
        R.drawable.ic_leshao_group,
        R.drawable.ic_leshao_icon
    };

    private IconLoader() {
    }

    /**
     * 四级回退定位模块 APK 路径
     */
    private static synchronized String moduleApkPath() {
        if (sModuleApkPath != null) return sModuleApkPath;

        // 方法1: /proc/self/maps（不限制 .apk 后缀）
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            int matchedCount = 0;
            while ((line = reader.readLine()) != null) {
                if (line.contains(MODULE_PKG)) {
                    matchedCount++;
                    int idx = line.indexOf('/');
                    if (idx > 0) {
                        String path = line.substring(idx).trim();
                        LogWriter.log("IconLoader", "maps[" + matchedCount + "]=" + path);
                        if (path.endsWith(".apk")) {
                            sModuleApkPath = path;
                            break;
                        }
                    }
                }
            }
            reader.close();
            LogWriter.log("IconLoader", "maps total matching lines=" + matchedCount);
        } catch (Throwable t) {
            LogWriter.log("IconLoader", "maps fail: " + t);
        }

        // 方法2: PackageManager.getApplicationInfo
        if (sModuleApkPath == null) {
            try {
                Context ctx = ContextManager.getAppContext();
                if (ctx != null) {
                    ApplicationInfo ai = ctx.getPackageManager()
                            .getApplicationInfo(MODULE_PKG, 0);
                    if (ai != null && ai.sourceDir != null) {
                        sModuleApkPath = ai.sourceDir;
                        LogWriter.log("IconLoader", "pmApi found: " + sModuleApkPath);
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                LogWriter.log("IconLoader", "pmApi NameNotFound: " + MODULE_PKG);
            } catch (Throwable t) {
                LogWriter.log("IconLoader", "pmApi fail: " + t);
            }
        }

        // 方法3: pm path 命令
        if (sModuleApkPath == null) {
            try {
                Process p = Runtime.getRuntime().exec("pm path " + MODULE_PKG);
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                reader.close();
                BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                String errLine = errReader.readLine();
                errReader.close();
                p.waitFor();
                int exitCode = p.exitValue();
                LogWriter.log("IconLoader", "pmPath exit=" + exitCode + " stdout=" + line + " stderr=" + errLine);
                if (line != null && line.startsWith("package:")) {
                    sModuleApkPath = line.substring("package:".length()).trim();
                }
            } catch (Throwable t) {
                LogWriter.log("IconLoader", "pmPath exec fail: " + t);
            }
        }

        // 方法4: 扫描 /data/app
        if (sModuleApkPath == null) {
            try {
                scanDir(new File("/data/app"));
            } catch (Throwable t) {
                LogWriter.log("IconLoader", "scan fail: " + t);
            }
        }

        LogWriter.log("IconLoader", "moduleApkPath=" + sModuleApkPath);
        return sModuleApkPath;
    }

    private static void scanDir(File dir) {
        if (sModuleApkPath != null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().contains(MODULE_PKG)) {
                    File baseApk = new File(f, "base.apk");
                    if (baseApk.exists()) { sModuleApkPath = baseApk.getAbsolutePath(); return; }
                }
                scanDir(f);
                if (sModuleApkPath != null) return;
            }
        }
    }

    /** 基于模块 APK 构建 Resources（不依赖 PackageManager） */
    public static synchronized Resources moduleResources(Context hostCtx) {
        if (sModuleRes != null) return sModuleRes;
        try {
            String apk = moduleApkPath();
            if (apk == null) return null;
            java.lang.reflect.Constructor<AssetManager> ctor = AssetManager.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            AssetManager am = ctor.newInstance();
            java.lang.reflect.Method addPath = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
            addPath.setAccessible(true);
            int cookie = (int) addPath.invoke(am, apk);
            if (cookie == 0) {
                LogWriter.log("IconLoader", "addAssetPath returned 0: " + apk);
                return null;
            }
            DisplayMetrics dm = hostCtx != null
                    ? hostCtx.getResources().getDisplayMetrics()
                    : new DisplayMetrics();
            Configuration cfg = hostCtx != null
                    ? hostCtx.getResources().getConfiguration()
                    : new Configuration();
            sModuleRes = new Resources(am, dm, cfg);
            return sModuleRes;
        } catch (Throwable t) {
            LogWriter.log("IconLoader", "moduleResources fail: " + t);
            return null;
        }
    }

    /** 按 id 加载图标 drawable（缩放到目标 dp 尺寸）
     * 优先从模块 APK 资源加载，APK 不可用时回退到内嵌 Base64 数据 */
    public static Drawable load(Context hostCtx, int iconId, int sizeDp) {
        // 先尝试从模块 APK 资源加载
        Resources res = moduleResources(hostCtx);
        if (res != null) {
            try {
                int resId = getResId(res, iconId);
                if (resId != 0) {
                    Drawable d = res.getDrawable(resId);
                    if (d != null) {
                        float density = hostCtx != null
                                ? hostCtx.getResources().getDisplayMetrics().density
                                : res.getDisplayMetrics().density;
                        int px = (int) (sizeDp * density + 0.5f);
                        d.setBounds(0, 0, px, px);
                        return d;
                    }
                }
            } catch (Throwable t) {
                LogWriter.log("IconLoader", "res load fail id=" + iconId + " : " + t);
            }
        }
        // 回退：从内嵌 Base64 数据加载
        Drawable d = IconData.getDrawable(iconId);
        if (d != null) {
            float density = hostCtx != null
                    ? hostCtx.getResources().getDisplayMetrics().density : 3.0f;
            int px = (int) (sizeDp * density + 0.5f);
            d.setBounds(0, 0, px, px);
            LogWriter.log("IconLoader", "loaded from IconData fallback id=" + iconId);
            return d;
        }
        LogWriter.log("IconLoader", "load FAILED id=" + iconId);
        return null;
    }

    /** 设置 TextView 左图标（图标与文字之间留一个空格宽度） */
    public static void setCompoundLeft(TextView tv, Drawable d) {
        if (tv == null || d == null) return;
        try {
            tv.setCompoundDrawables(d, null, null, null);
            float spaceW = tv.getPaint().measureText(" ");
            tv.setCompoundDrawablePadding((int) (spaceW + 0.5f));
        } catch (Throwable ignored) {}
    }

    /** 根据图标 id 返回模块 drawable 资源 id */
    private static int getResId(Resources res, int iconId) {
        try {
            switch (iconId) {
                case IC_VOICE_FORWARD:
                    return res.getIdentifier("ic_voice_forward", "drawable", MODULE_PKG);
                case IC_VOICE_LIST:
                    return res.getIdentifier("ic_voice_list", "drawable", MODULE_PKG);
                case IC_SCHEDULE_SEND:
                    return res.getIdentifier("ic_schedule_send", "drawable", MODULE_PKG);
                case IC_LESHAO_GROUP:
                    return res.getIdentifier("ic_leshao_group", "drawable", MODULE_PKG);
                case IC_LESHAO_ICON:
                    return res.getIdentifier("ic_leshao_icon", "drawable", MODULE_PKG);
                default:
                    return 0;
            }
        } catch (Throwable t) {
            LogWriter.log("IconLoader", "getResId fail: " + t);
            return 0;
        }
    }
}