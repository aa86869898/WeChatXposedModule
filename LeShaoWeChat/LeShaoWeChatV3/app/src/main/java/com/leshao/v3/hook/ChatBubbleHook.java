package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义聊天气泡 —— 文档《修改聊天气泡WeChatChatBubbleReplace.md》方案 A。
 *
 * <p>Hook X2C 资源解析包装层 {@code kw5.g.r(Context, View, String, String value, int resId)}，
 * 按 value 字符串匹配 {@code @drawable/chatfrom_bg}（收到）/ {@code @drawable/chatto_bg}（发出），
 * 返回用户通过系统文件管理器选择的图片。开关在「联系人和群聊」页，设置页分别选择收/发气泡图。</p>
 */
public final class ChatBubbleHook {

    public static final String TAG = "Bubble";
    public static final String K_ENABLED = "ls_bubble_enabled";
    public static final String K_FROM_PATH = "ls_bubble_from_path";
    public static final String K_TO_PATH = "ls_bubble_to_path";

    public static final int KIND_FROM = 0;
    public static final int KIND_TO = 1;

    private static final int REQ_PICK_BUBBLE = 0x7F02;

    private static volatile boolean sEnabled = false;
    private static volatile String sFromPath;
    private static volatile String sToPath;
    private static volatile boolean sHooked = false;
    private static volatile boolean sResultHooked = false;

    // Bitmap 缓存：路径不变时复用，避免每次 setBackground 都解码
    private static volatile Bitmap sFromBmp;
    private static volatile Bitmap sToBmp;
    private static volatile String sFromBmpPath;
    private static volatile String sToBmpPath;

    private static volatile BubblePickCallback sPickCb;
    private static volatile int sPickKind = KIND_FROM;

    public interface BubblePickCallback {
        void onPick(String path);
    }

    private ChatBubbleHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        putBool(K_ENABLED, on);
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    public static String getFromPath() {
        SharedPreferences sp = safePrefs();
        return sp != null ? sp.getString(K_FROM_PATH, null) : sFromPath;
    }

    public static String getToPath() {
        SharedPreferences sp = safePrefs();
        return sp != null ? sp.getString(K_TO_PATH, null) : sToPath;
    }

    public static void setBubblePath(int kind, String path) {
        SharedPreferences sp = safePrefs();
        if (sp == null) return;
        if (kind == KIND_FROM) {
            sp.edit().putString(K_FROM_PATH, path).apply();
            sFromPath = path;
            sFromBmp = null;
            sFromBmpPath = null;
        } else {
            sp.edit().putString(K_TO_PATH, path).apply();
            sToPath = path;
            sToBmp = null;
            sToBmpPath = null;
        }
        LogWriter.log(TAG, "bubble path kind=" + kind + " -> " + path);
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putBool(String k, boolean v) {
        try {
            ContextManager.getPrefs().edit().putBoolean(k, v).apply();
        } catch (Throwable ignored) {}
    }

    // ---------------- 文件选择（系统文件管理器） ----------------

    public static void pickBubbleImage(Activity act, int kind, BubblePickCallback cb) {
        sPickCb = cb;
        sPickKind = kind;
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            act.startActivityForResult(intent, REQ_PICK_BUBBLE);
            LogWriter.log(TAG, "open document picker kind=" + kind);
        } catch (Throwable t) {
            sPickCb = null;
            LogWriter.log(TAG, "open picker FAILED: " + t.getMessage());
        }
    }

    private static void ensureResultHook() {
        if (sResultHooked) return;
        sResultHooked = true;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int requestCode = (int) param.args[0];
                        if (requestCode != REQ_PICK_BUBBLE || sPickCb == null) return;
                        BubblePickCallback cb = sPickCb;
                        sPickCb = null;
                        String path = null;
                        int resultCode = (int) param.args[1];
                        Intent data = (Intent) param.args[2];
                        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                            path = copyUriToBubbleDir((Activity) param.thisObject, data.getData());
                        }
                        cb.onPick(path);
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "onActivityResult hook installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureResultHook err: " + t.getMessage());
        }
    }

    /** 将 content:// URI 复制到 /data/data/<pkg>/files/bubble/ 返回本地路径 */
    private static String copyUriToBubbleDir(Activity act, Uri uri) {
        try {
            String fileName = "bubble_" + sPickKind + "_" + System.currentTimeMillis();
            Cursor cursor = act.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String name = cursor.getString(idx);
                            if (name != null && name.contains(".")) {
                                fileName = "bubble_" + sPickKind + "_" + System.currentTimeMillis()
                                        + name.substring(name.lastIndexOf('.'));
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            File dir = new File(act.getFilesDir(), "bubble");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, fileName);
            InputStream is = act.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { is.close(); } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "bubble saved: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            LogWriter.log(TAG, "copyUriToBubbleDir err: " + t.getMessage());
            return null;
        }
    }

    // ---------------- Hook ----------------

    public static void hook(ClassLoader cl) {
        try {
            sEnabled = isEnabled();
            sFromPath = getFromPath();
            sToPath = getToPath();
        } catch (Throwable ignored) {}
        ensureResultHook();
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    installBubbleResolver(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "bubble resolver hooked attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "bubble install attempt " + attempt + " failed: "
                            + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-bubble-hook");
        t.setDaemon(true);
        t.start();
    }

    private static void installBubbleResolver(ClassLoader cl) {
        Class<?> resCls = XposedHelpers.findClass("kw5.g", cl);
        XposedHelpers.findAndHookMethod(resCls, "r",
                Context.class, View.class, String.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!sEnabled) return;
                            String value = (String) param.args[3];
                            if (value == null) return;
                            Drawable d = null;
                            if ("@drawable/chatfrom_bg".equals(value)) {
                                d = loadDrawable(KIND_FROM);
                            } else if ("@drawable/chatto_bg".equals(value)) {
                                d = loadDrawable(KIND_TO);
                            }
                            if (d != null) param.setResult(d);
                        } catch (Throwable ignored) {}
                    }
                });
    }

    private static Drawable loadDrawable(int kind) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return null;
        String path = kind == KIND_FROM ? sFromPath : sToPath;
        if (path == null || path.isEmpty()) return null;
        Bitmap bmp = getBitmapCached(kind, path);
        if (bmp == null) return null;
        return new BitmapDrawable(ctx.getResources(), bmp);
    }

    private static Bitmap getBitmapCached(int kind, String path) {
        if (kind == KIND_FROM) {
            if (sFromBmpPath == null || !sFromBmpPath.equals(path)) {
                sFromBmp = loadBitmap(path);
                sFromBmpPath = path;
            }
            return sFromBmp;
        } else {
            if (sToBmpPath == null || !sToBmpPath.equals(path)) {
                sToBmp = loadBitmap(path);
                sToBmpPath = path;
            }
            return sToBmp;
        }
    }

    private static Bitmap loadBitmap(String path) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, opt);
            int maxEdge = 1024;
            int sample = 1;
            while (opt.outWidth / sample > maxEdge || opt.outHeight / sample > maxEdge) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o2);
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadBitmap err: " + t.getMessage());
            return null;
        }
    }
}