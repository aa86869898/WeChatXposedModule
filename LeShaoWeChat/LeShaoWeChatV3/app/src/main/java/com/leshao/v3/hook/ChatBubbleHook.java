package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ImageView;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义聊天气泡替换。
 *
 * <p>v3.0.100 根因修复（依据《微信聊天对话气泡替换精准深挖报告》§0.1）：文本气泡承载视图
 * {@code com.tencent.mm.ui.widget.MMNeat7extView}（继承 {@code NeatTextView extends android.view.View}）
 * 自己<b>重写</b>了 {@code setBackgroundResource(int)} 与 {@code setBackground(Drawable)}，
 * Java 虚分派直接进入其 override，<b>永不落到 {@code android.view.View} 实现</b>。
 * 因此旧实现对基类 {@code View.setBackground*} 的 hook 对文本气泡一次都不触发——这是
 * 「自定义文字消息气泡替换不生效」的真正断点。</p>
 *
 * <p>修复策略（报告 §3 方案 1，唯一全场景可靠）：</p>
 * <ol>
 *   <li>直接对真实类 {@code MMNeat7extView} 的 {@code setBackgroundResource(int)} /
 *       {@code setBackground(Drawable)} 安装 hook；</li>
 *   <li>{@code setBackgroundResource} 的 after 阶段按 resId 白名单（报告 §2：普通/发送中/链接共 6 个）
 *       判定收/发方向，覆盖为用户选定的气泡图片；</li>
 *   <li>自定义图片统一由 {@link BubbleDrawableFactory} 构造成 native 9-patch，
 *       保证 MMNeat7extView 的测量/内边距与拉伸正确；</li>
 *   <li>同时保留基类 / ImageView / ViewHolder 绑定方法 / onLayout 兜底，
 *       覆盖图片、语音等不重写背景方法的容器气泡。</li>
 * </ol>
 */
public final class ChatBubbleHook {

    public static final String TAG = "Bubble";
    public static final String K_ENABLED = "ls_bubble_enabled";
    public static final String K_FROM_PATH = "ls_bubble_from_path";
    public static final String K_TO_PATH = "ls_bubble_to_path";
    /** v3.0.128：气泡内文字颜色（0 = 不修改，保持微信原生）。对方/自己各一份。 */
    public static final String K_FROM_TEXT_COLOR = "ls_bubble_from_text_color";
    public static final String K_TO_TEXT_COLOR = "ls_bubble_to_text_color";

    public static final int KIND_FROM = 0;
    public static final int KIND_TO = 1;

    private static final int REQ_PICK_BUBBLE = 0x7F02;

    private static volatile boolean sEnabled = false;
    private static volatile String sFromPath;
    private static volatile String sToPath;
    private static volatile boolean sHooked = false;
    private static volatile boolean sResultHooked = false;

    // v3.0.115：View 树 dump 仅用于早期定位真实气泡 View，属调试功能。
    // 常开会在每次 RecyclerView.onLayout 遍历并记录整棵 View 树，导致启动卡顿与日志暴涨，默认关闭。
    private static final boolean DEBUG_VIEW_DUMP = false;

    // ==================== v3.0.125 运行时自校准 ====================
    // 用户运行时构建与本模块静态分析的 base.apk 不一致：日志实锤
    //   - MMNeat7extView.setBackgroundResource 全日志 0 触发；
    //   - viewitems.to.b 命中的是 android.widget.TextView(neat=false)；
    //   - resId 2131231925->mh / 2131232060->o_ 均为混淆短名，chatfrom_bg 名字查不到。
    // 因此不再预设白名单，改为在**本机**全量记录所有气泡背景设置点(view类/resId/资源名/坐标/父链)，
    // 导出真实 (resId, viewClass, x) 映射后再固化。CAL_MAX 为去重后最大记录条数(防日志暴涨)。
    private static final boolean CALIBRATE = true;
    private static final int CAL_MAX = 600;
    private static final java.util.Set<String> sCalSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.concurrent.atomic.AtomicInteger sCalCount =
            new java.util.concurrent.atomic.AtomicInteger();

    // 启发式兜底：完全不依赖类名/resId，直接对聊天列表内「背景为九宫格(NinePatch)的气泡容器」
    // 按 x 坐标(右半屏=发出 / 左半屏=收到)替换。开关默认开，可关闭。
    private static final String K_HEURISTIC = "ls_bubble_heuristic";
    private static volatile boolean sHeuristic = true;

    // v3.0.128 气泡内文字颜色（0 = 不修改）。对方/自己各一份，运行时自校准后固化。
    private static volatile int sFromTextColor = 0;
    private static volatile int sToTextColor = 0;

    // 气泡资源 ID（XML 兜底路径用）。优先 getIdentifier 动态解析，失败回退文档已知值
    private static volatile int sFromResId = 2131231925; // chatfrom_bg
    private static volatile int sToResId = 2131232060;   // chatto_bg

    // 气泡图片缓存
    private static volatile Bitmap sFromBmp;
    private static volatile Bitmap sToBmp;
    private static volatile String sFromBmpPath;
    private static volatile String sToBmpPath;

    // 已构建的自定义气泡 Drawable 缓存：缓存 ConstantState，每次使用时 newDrawable() 出新实例，
    // 避免同一 Drawable 实例被大量 View 共享导致 bounds/callback/state 串扰（报告 §0.2）。
    private static final Object sDrawableCache = new Object();
    private static volatile Drawable.ConstantState sFromDrawableState;
    private static volatile Drawable.ConstantState sToDrawableState;
    private static volatile String sFromDrawablePath;
    private static volatile String sToDrawablePath;
    private static volatile boolean sFirstResolverLogged;

    // 微信原始气泡 Drawable 基准
    private static volatile Drawable sFromBaseDrawable;
    private static volatile Drawable sToBaseDrawable;

    // v3.0.94：文本气泡在 setBackgroundResource 替换后可能被微信布局阶段再次覆盖。
    // 记录已替换的气泡 View 与自定义 Drawable，在聊天列表 onLayout 后强制恢复，
    // 解决文本消息（TextView w=0 h=0 时替换）最终仍显示原生气泡的问题。
    private static final java.util.Map<View, Drawable> sBubbleViews =
            new java.util.WeakHashMap<>();

    /** v3.0.128：记录已替换气泡的方向（收/发），供补盖时同步文字颜色。 */
    private static final java.util.Map<View, Integer> sBubbleKind =
            new java.util.WeakHashMap<>();

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
            synchronized (sDrawableCache) { sFromDrawableState = null; sFromDrawablePath = null; }
        } else {
            sp.edit().putString(K_TO_PATH, path).apply();
            sToPath = path;
            sToBmp = null;
            sToBmpPath = null;
            synchronized (sDrawableCache) { sToDrawableState = null; sToDrawablePath = null; }
        }
        LogWriter.log(TAG, "bubble path kind=" + kind + " -> " + path);
    }

    // ---------------- v3.0.128 气泡内文字颜色 ----------------

    /** 读取气泡内文字颜色（0 = 不修改）。 */
    public static int getTextColor(int kind) {
        if (kind == KIND_FROM) return sFromTextColor;
        return sToTextColor;
    }

    /** 设置气泡内文字颜色（0 = 不修改，恢复微信原生）。 */
    public static void setTextColor(int kind, int color) {
        SharedPreferences sp = safePrefs();
        if (kind == KIND_FROM) {
            sFromTextColor = color;
            if (sp != null) sp.edit().putInt(K_FROM_TEXT_COLOR, color).apply();
        } else {
            sToTextColor = color;
            if (sp != null) sp.edit().putInt(K_TO_TEXT_COLOR, color).apply();
        }
        LogWriter.log(TAG, "bubble text color kind=" + kind + " -> 0x"
                + Integer.toHexString(color));
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
        // 微信部分 Activity 重写 onActivityResult 且不调用 super，仅 hook 基类会丢失回调。
        // 针对性 hook 该 Activity 的实际运行时类（幂等，重复 hook 由 XposedBridge 去重）。
        hookActivityInstanceOnResult(act);
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

    /** hook 指定 Activity 运行时类的 onActivityResult（若该类重写），兜底基类 hook 的失效场景。 */
    private static void hookActivityInstanceOnResult(Activity act) {
        if (act == null) return;
        try {
            Class<?> c = act.getClass();
            while (c != null && c != Activity.class && Activity.class.isAssignableFrom(c)) {
                try {
                    final java.lang.reflect.Method m = c.getDeclaredMethod(
                            "onActivityResult", int.class, int.class, Intent.class);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handleActivityResult(param);
                        }
                    });
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static void handleActivityResult(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
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

    private static void ensureResultHook() {
        if (sResultHooked) return;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handleActivityResult(param);
                }
            });
            sResultHooked = true;
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
            try {
                SharedPreferences sp = safePrefs();
                sHeuristic = sp == null || sp.getBoolean(K_HEURISTIC, true);
                if (sp != null) {
                    sFromTextColor = sp.getInt(K_FROM_TEXT_COLOR, 0);
                    sToTextColor = sp.getInt(K_TO_TEXT_COLOR, 0);
                }
            } catch (Throwable ignored) {}
            resolveBubbleResIds(cl);
            LogWriter.log(TAG, "bubble config enabled=" + sEnabled
                    + " from=" + (sFromPath != null && !sFromPath.isEmpty() ? "SET" : "EMPTY")
                    + " to=" + (sToPath != null && !sToPath.isEmpty() ? "SET" : "EMPTY"));
        } catch (Throwable ignored) {}
        ensureResultHook();
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    // v3.0.95：微信 8.0.78 气泡资源已改名(mh/o_)，且 ke5.a.i 运行时不再调用，
                    // 必须主动加载微信原生气泡 Drawable，否则 constantState 匹配全部失效。
                    ensureBaseDrawables();
                    installBubbleResolver(cl);
                    installBubbleApplyHook(cl);
                    installBackgroundResourceHook(cl);
                    installNeatBackgroundHook(cl);
                    installNeatOnDrawHook(cl);
                    installBackgroundHook(cl);
                    installImageViewHook(cl);
                    installViewitemsToHook(cl);
                    installLinkSubtypeHook(cl);
                    installResourceHelperHook(cl);
                    installResourceGetDrawableHook(cl);
                    installChatListViewHook(cl);
                    installCalibrationHooks(cl);
                    installInflateFallbackHook(cl);
                    installAttachApplyHook(cl);
                    if (DEBUG_VIEW_DUMP) installChattingListCollector(cl);
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

    /** 解析气泡资源 ID：优先反射 R$drawable 字段（R8 不混淆资源字段名），
     *  其次 getIdentifier，最后回退文档已知值。R8 可能把 R 类扁平化为
     *  com$tencent$mm$R$drawable，因此遍历候选类名变体。 */
    private static void resolveBubbleResIds(ClassLoader cl) {
        String[] candidates = {
                "com.tencent.mm.R$drawable",
                "com$tencent$mm$R$drawable",
                "com.tencent.mm.R$drawable"
        };
        for (String cn : candidates) {
            try {
                Class<?> rDrawable = cl.loadClass(cn);
                int from = 0;
                int to = 0;
                try {
                    java.lang.reflect.Field f1 = rDrawable.getDeclaredField("chatfrom_bg");
                    f1.setAccessible(true);
                    from = f1.getInt(null);
                } catch (NoSuchFieldException ignored) {}
                try {
                    java.lang.reflect.Field f2 = rDrawable.getDeclaredField("chatto_bg");
                    f2.setAccessible(true);
                    to = f2.getInt(null);
                } catch (NoSuchFieldException ignored) {}
                if (from == 0 && to == 0) {
                    LogWriter.log(TAG, "R$drawable " + cn + " loaded but no chatfrom/chatto fields");
                    continue;
                }
                if (from != 0) sFromResId = from;
                if (to != 0) sToResId = to;
                LogWriter.log(TAG, "bubble resIds via R$drawable cn=" + cn
                        + " from=" + sFromResId + " to=" + sToResId);
                return;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个候选名
            } catch (Throwable t) {
                LogWriter.log(TAG, "R$drawable resolve err cn=" + cn + ": " + t.getMessage());
            }
        }
        try {
            android.content.res.Resources res = ContextManager.getAppContext().getResources();
            String pkg = ContextManager.getAppContext().getPackageName();
            int from = res.getIdentifier("chatfrom_bg", "drawable", pkg);
            int to = res.getIdentifier("chatto_bg", "drawable", pkg);
            if (from != 0) sFromResId = from;
            if (to != 0) sToResId = to;
            LogWriter.log(TAG, "bubble resIds from=" + sFromResId + " to=" + sToResId
                    + " (getIdentifier from=" + from + " to=" + to + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveBubbleResIds err: " + t.getMessage());
        }
        // 无论成功与否，打印旧值对应的当前资源名，供人工核对锚点。
        try {
            android.content.res.Resources res = ContextManager.getAppContext().getResources();
            for (int id : new int[]{sFromResId, sToResId}) {
                try {
                    String name = res.getResourceEntryName(id);
                    LogWriter.log(TAG, "legacy resId " + id + " -> name=" + name);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "legacy resId " + id + " name err: " + t.getMessage());
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v3.0.95：主动加载微信原生气泡 Drawable（资源 ID 已改名 mh/o_），
     *  供 constantState 匹配识别真实气泡 View。ke5.a.i 在 8.0.78 运行时不再调用，
     *  必须在此兜底，否则 setBackground/onLayout 等替换路径全部失效。 */
    private static void ensureBaseDrawables() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            android.content.res.Resources res = ctx.getResources();
            if (sFromBaseDrawable == null && sFromResId != 0) {
                Drawable d = res.getDrawable(sFromResId);
                if (d != null) {
                    sFromBaseDrawable = d;
                    LogWriter.log(TAG, "baseDrawable loaded from resId=" + sFromResId
                            + " name=" + res.getResourceEntryName(sFromResId));
                }
            }
            if (sToBaseDrawable == null && sToResId != 0) {
                Drawable d = res.getDrawable(sToResId);
                if (d != null) {
                    sToBaseDrawable = d;
                    LogWriter.log(TAG, "baseDrawable loaded to resId=" + sToResId
                            + " name=" + res.getResourceEntryName(sToResId));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureBaseDrawables err: " + t.getMessage());
        }
    }

    /** 方案 C + 方案 1（报告 §0.1 根因）：XML 兜底路径。
     *
     *  <p>关键修正：文本气泡承载视图 {@code MMNeat7extView} 自己重写了
     *  {@code setBackgroundResource(int)}（smali：先 super 再同步内嵌 wrappedTextView 的 padding），
     *  Java 虚分派会直接进入它自己的 override，<b>永不落到 {@code android.view.View} 实现</b>。
     *  因此旧实现 hook {@code View.setBackgroundResource} 对文本气泡 100% 不触发——这是
     *  「文字气泡替换不生效」的真正断点。</p>
     *
     *  <p>修正后：直接对真实类 {@code com.tencent.mm.ui.widget.MMNeat7extView} 的
     *  {@code setBackgroundResource(int)} 安装 hook。textual 气泡的三个设置点
     *  （{@code viewitems.to.b} / {@code hn5.r0.g0} / {@code hn5.s0.k0}）全部调用它，
     *  在 after 阶段按 6 个原始 resId 白名单 → 收/发方向覆盖为用户图片。
     *  同时保留对 {@code android.view.View} 基类的兜底（覆盖图片/语音等不 override 的容器），
     *  两者并存不冲突（对 override 类基类 hook 不触发，不会双替换）。</p> */
    private static void installBackgroundResourceHook(ClassLoader cl) {
        try {
            XC_MethodHook h = createBackgroundResourceHook();
            // 1) 文本气泡真实 override —— 唯一可靠触发点
            Class<?> neat = null;
            try {
                neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            } catch (Throwable ignored) {}
            ClassicSet: {
                if (neat == null) break ClassicSet;
                Method m = null;
                try {
                    m = neat.getDeclaredMethod("setBackgroundResource", int.class);
                } catch (Throwable ignored) {}
                if (m == null) {
                    for (Method mm : neat.getDeclaredMethods()) {
                        if ("setBackgroundResource".equals(mm.getName())
                                && mm.getParameterTypes().length == 1
                                && mm.getParameterTypes()[0] == int.class) {
                            m = mm;
                            break;
                        }
                    }
                }
                if (m != null) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, h);
                    LogWriter.log(TAG, "MMNeat7extView.setBackgroundResource hooked (real text bubble)");
                    break ClassicSet;
                }
                LogWriter.log(TAG, "MMNeat7extView has no setBackgroundResource override");
            }
            // 2) 基类兜底（图片/语音等非 override 容器气泡）
            try {
                Method base = View.class.getMethod("setBackgroundResource", int.class);
                XposedBridge.hookMethod(base, h);
                LogWriter.log(TAG, "View.setBackgroundResource hooked (fallback)");
            } catch (Throwable t) {
                LogWriter.log(TAG, "View.setBackgroundResource hook err: " + t.getMessage());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBackgroundResourceHook err: " + t.getMessage());
        }
    }

    /** 构造 setBackgroundResource(int) 的替换 hook：after 阶段按 resId 白名单映射方向并覆盖背景。 */
    private static XC_MethodHook createBackgroundResourceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    int resId = ((Number) param.args[0]).intValue();
                    int kind = resolveKindByResId(ContextManager.getAppContext(), resId);
                    if (kind < 0) return;
                    View v = (View) param.thisObject;
                    try {
                        if (kind == KIND_FROM && sFromBaseDrawable == null) {
                            Drawable base = v.getResources().getDrawable(resId);
                            if (base != null) sFromBaseDrawable = base;
                        } else if (kind == KIND_TO && sToBaseDrawable == null) {
                            Drawable base = v.getResources().getDrawable(resId);
                            if (base != null) sToBaseDrawable = base;
                        }
                    } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "setBackgroundResource before resId=" + resId
                            + " kind=" + kind + " view=" + v.getClass().getName());
                } catch (Throwable ignored) {}
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    int resId = ((Number) param.args[0]).intValue();
                    int kind = resolveKindByResId(ContextManager.getAppContext(), resId);
                    if (kind < 0) return;
                    View v = (View) param.thisObject;
                    applyBubbleTo(v, kind);
                    int[] loc = {0, 0};
                    try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "setBackgroundResource REPLACE resId=" + resId
                            + " kind=" + kind + " view=" + v.getClass().getName()
                            + " xy=(" + loc[0] + "," + loc[1] + ")"
                            + " w=" + v.getWidth() + " h=" + v.getHeight());
                } catch (Throwable ignored) {}
            }
        };
    }

    /** 方案 1.2（报告 §3.2）：MMNeat7extView 同样重写了 {@code setBackground(Drawable)}，
     *  某些路径（DataBinding / 我们自己的 after 兜底 / X2C）会以 Drawable 形式设置气泡背景，
     *  基类 {@code View.setBackground} hook 对文本气泡同样不触发。此处直接 hook 真实类。
     *  无法用 resId 判定方向时，用 constantState 与已记录的微信原生气泡比对。 */
    private static void installNeatBackgroundHook(ClassLoader cl) {
        Class<?> neat = null;
        try {
            neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
        } catch (Throwable ignored) {}
        if (neat == null) return;
        Method m = null;
        for (Method mm : neat.getDeclaredMethods()) {
            if ("setBackground".equals(mm.getName())
                    && mm.getParameterTypes().length == 1
                    && mm.getParameterTypes()[0] == Drawable.class) {
                m = mm;
                break;
            }
        }
        if (m == null) {
            LogWriter.log(TAG, "MMNeat7extView.setBackground(Drawable) not found");
            return;
        }
        m.setAccessible(true);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                    Drawable d = (Drawable) param.args[0];
                    int kind = matchBaseDrawable(d);
                    if (kind < 0) return;
                    Drawable custom = loadDrawable(kind);
                    if (custom != null) {
                        param.args[0] = custom;
                        rememberBubble((View) param.thisObject, custom);
                        LogWriter.log(TAG, "neat.setBackground REPLACE kind=" + kind
                                + " view=" + param.thisObject.getClass().getName());
                    }
                } catch (Throwable ignored) {}
            }
        });
        LogWriter.log(TAG, "MMNeat7extView.setBackground(Drawable) hooked");
    }

    /** v3.0.120：文字气泡兜底。语音气泡走 AnimImageView 背景/图片生效；
     *  文字气泡（MMNeat7extView）可能自绘气泡而不读取 View background，
     *  导致 setBackground* 全部替换后仍看不到效果。
     *  此处在 onDraw 前主动绘制自定义气泡：若背景已是自定义图则直接绘制，
     *  若背景仍是微信原生气泡则替换后绘制，确保自定义图出现在文字下方。 */
    private static void installNeatOnDrawHook(ClassLoader cl) {
        try {
            Class<?> neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            Method onDraw = null;
            for (Method m : neat.getDeclaredMethods()) {
                if ("onDraw".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == android.graphics.Canvas.class) {
                    onDraw = m;
                    break;
                }
            }
            if (onDraw == null) {
                LogWriter.log(TAG, "MMNeat7extView.onDraw not found");
                return;
            }
            onDraw.setAccessible(true);
            XposedBridge.hookMethod(onDraw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        View v = (View) param.thisObject;
                        android.graphics.Canvas canvas = (android.graphics.Canvas) param.args[0];
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (w <= 0 || h <= 0) return;
                        Drawable bg = v.getBackground();
                        if (isCustomBackground(bg)) {
                            bg.setBounds(0, 0, w, h);
                            bg.draw(canvas);
                            return;
                        }
                        int kind = matchBaseDrawable(bg);
                        if (kind < 0) return;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            custom.setBounds(0, 0, w, h);
                            custom.draw(canvas);
                            v.setBackground(custom);
                            rememberBubble(v, custom);
                            LogWriter.log(TAG, "neat.onDraw REPLACE kind=" + kind
                                    + " parent=" + parentChain(v, 1));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "MMNeat7extView.onDraw hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installNeatOnDrawHook err: " + t.getMessage());
        }
    }

    /** 方案 E：View.setBackground / setBackgroundDrawable 拦截。
     *  聊天 item 复用预构建 View 后可能重新设置气泡背景（不走 ke5.a.i/getDrawable），
     *  在设置点用 ke5.a.i 记录的微信原始 Drawable 的 constantState 识别并替换。
     *  注：此基类 hook 对 override 的 MMNeat7extView 不触发（见 installNeatBackgroundHook）。 */
    private static void installBackgroundHook(ClassLoader cl) {
        try {
            Method setBg = View.class.getMethod("setBackground", Drawable.class);
            Method setBgDrawable = View.class.getMethod("setBackgroundDrawable", Drawable.class);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        // 空 Drawable 不拦：微信 AnimImageView.setType() 复用/空态分支会
                        // setBackgroundDrawable(null) 清空背景，交由 after 无条件补盖。
                        if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        Drawable d = (Drawable) param.args[0];
                        int kind = matchBaseDrawable(d);
                        if (kind < 0) return;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            param.args[0] = custom;
                            LogWriter.log(TAG, "setBackground REPLACE kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        boolean cleared = param.args.length == 0 || param.args[0] == null;
                        if (!cleared) return;
                        if (param.thisObject instanceof View) {
                            reapplyIfBubble((View) param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookMethod(setBg, h);
            XposedBridge.hookMethod(setBgDrawable, h);
            LogWriter.log(TAG, "View.setBackground hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBackgroundHook err: " + t.getMessage());
        }
    }

    /** 方案 G：ImageView 图片路径。8.0.78 聊天消息气泡（尤其自己发出的 chatto_bg）通过
     *  AnimImageView（ImageView 子类）显示，ke5.a.i 加载的 Drawable 最终传给
     *  ImageView.setImageDrawable。在此拦截，按微信原生气泡 constantState 识别替换。 */
    private static void installImageViewHook(ClassLoader cl) {
        try {
            Method setImageDrawable = ImageView.class.getMethod("setImageDrawable", Drawable.class);
            XposedBridge.hookMethod(setImageDrawable, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        Drawable d = (Drawable) param.args[0];
                        int kind = matchBaseDrawable(d);
                        if (kind < 0) return;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            param.args[0] = custom;
                            LogWriter.log(TAG, "setImageDrawable REPLACE kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "ImageView.setImageDrawable hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installImageViewHook err: " + t.getMessage());
        }
        try {
            Method setImageResource = ImageView.class.getMethod("setImageResource", int.class);
            XposedBridge.hookMethod(setImageResource, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            ((ImageView) param.thisObject).setImageDrawable(custom);
                            LogWriter.log(TAG, "setImageResource REPLACE resId=" + resId
                                    + " kind=" + kind);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "ImageView.setImageResource hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installImageViewHook setImageResource err: " + t.getMessage());
        }
    }

/** 文档 §5 方案2：精准 hook 文本气泡 ViewHolder 的静态绑定方法
     *  b(e9, holder, data, Boolean isRecv) —— 普通态 chatfrom_bg/chatto_bg 最终设置点。
     *  v3.0.90：日志 ke5.a.i stack 证实 8.0.78 真实气泡加载路径是 viewitems.mq.b；
     *  v3.0.91：mq.b 签名不满足「静态+首参e9」，故对 mq 类放宽为所有名为 b 的方法，
     *  并用 HookUtil.loadClasses 遍历 Tinker 真实 ClassLoader。 */
    private static void installViewitemsToHook(ClassLoader cl) {
        String[] holderCands = {
                "com.tencent.mm.ui.chatting.viewitems.to",
                "com.tencent.mm.ui.chatting.viewitems.mq"
        };
        int hooked = 0;
        for (String cn : holderCands) {
            for (Class<?> toCls : HookUtil.loadClasses(cl, cn)) {
                for (Method m : toCls.getDeclaredMethods()) {
                    if (!"b".equals(m.getName())) continue;
                    // to 类保持严格（文档签名）；mq 类放宽（日志证实 mq.b 是实际调用点，
                    // 但签名未知——可能非静态/首参非 e9）
                    boolean strict = cn.contains("to");
                    if (strict) {
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 2 || !"e9".equals(pts[0].getSimpleName())) continue;
                    }
                    m.setAccessible(true);
                    final String owner = cn;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                boolean isRecv = true;
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Boolean) {
                                        isRecv = (Boolean) param.args[i];
                                        break;
                                    }
                                }
                                View bubble = null;
                                // v3.0.120：优先遍历 holder 字段找 MMNeat7extView —— 文字气泡真实承载视图。
                                // 此前 findBubbleViewInTree 命中的 android.widget.TextView 并非可见气泡，
                                // 替换后用户看不到效果；而语音气泡(AnimImageView)走 setBackgroundResource 生效。
                                for (Object a : param.args) {
                                    if (a == null) continue;
                                    bubble = findMMNeatTextView(a);
                                    if (bubble != null) break;
                                }
                                if (bubble == null) {
                                    for (Object a : param.args) {
                                        if (a == null) continue;
                                        if (a instanceof View) {
                                            bubble = findBubbleViewInTree((View) a);
                                            if (bubble != null) break;
                                        } else if ("e9".equals(a.getClass().getSimpleName())
                                                || hasFieldType(a)) {
                                            bubble = findBubbleView(a);
                                            if (bubble != null) break;
                                        }
                                    }
                                }
                                if (bubble == null) bubble = findBubbleView(param.thisObject);
                                if (bubble == null) return;
                                // v3.0.100: 方向判定仍以 to.b 自带的 isRecv 布尔为准（语义最准）。
                                // 若该重载无布尔参数，则由 bubble 背景匹配白名单兜底。
                                int kind = isRecv ? KIND_FROM : KIND_TO;
                                if (loadDrawable(kind) != null) {
                                    applyBubbleTo(bubble, kind);
                                    LogWriter.log(TAG, "viewitems.b REPLACE isRecv=" + isRecv
                                            + " kind=" + kind
                                            + " view=" + bubble.getClass().getName()
                                            + " neat=" + bubble.getClass().getName().contains("MMNeat")
                                            + " parent=" + parentChain(bubble, 2));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "viewitems.b hooked " + owner + "." + m.getName()
                            + Arrays.toString(m.getParameterTypes())
                            + " loader=" + HookUtil.loaderName(toCls.getClassLoader()));
                }
            }
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "viewitems.b none found (fallback setBackgroundResource covers)");
        }
    }

    /** v3.0.120：遍历 holder 对象字段，找类型为 MMNeat7extView 的 View —— 文字气泡真实承载视图。 */
    private static View findMMNeatTextView(Object holder) {
        if (holder == null) return null;
        try {
            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        Class<?> ft = f.getType();
                        if (ft == null || !View.class.isAssignableFrom(ft)) continue;
                        boolean typeHits = ft.getName().contains("MMNeat");
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (!(v instanceof View)) continue;
                        View view = (View) v;
                        if (typeHits || view.getClass().getName().contains("MMNeat")) return view;
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 打印 View 的父链（最多 maxLevel 层），用于定位文字气泡真实视图。 */
    private static String parentChain(View v, int maxLevel) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        View cur = v;
        int level = 0;
        while (cur != null && level <= maxLevel) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(cur.getClass().getSimpleName());
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            level++;
        }
        return sb.toString();
    }

    /** 递归在 View 树中查找背景与微信原生气泡匹配的气泡 View。 */
    private static View findBubbleViewInTree(View v) {
        if (v == null) return null;
        try {
            if (matchBaseDrawable(v.getBackground()) >= 0) return v;
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findBubbleViewInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 判断对象是否带 field_type 字段（微信消息/收藏实体特征）。 */
    private static boolean hasFieldType(Object o) {
        if (o == null) return false;
        try {
            XposedHelpers.getIntField(o, "field_type");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文档 §5 方案2：链接/自动识别文本子类型 hn5.r0.g0(收)/hn5.s0.k0(发)，
     *  after 中直接替换 MMNeat7extView 背景。 */
    private static void installLinkSubtypeHook(ClassLoader cl) {
        try {
            Class<?> neatCls = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            String[] inners = {
                    "com.tencent.mm.ui.chatting.viewitems.hn5$r0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5.r0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5$s0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5.s0"
            };
            for (String cn : inners) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 1) continue;
                        if (m.getParameterTypes()[0] != neatCls) continue;
                        m.setAccessible(true);
                        final String owner = cn;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    Object v = param.args[0];
                                    if (!(v instanceof View)) return;
                                    // r0=收(chatfrom), s0=发(chatto)
                                    int kind = m.getName().equals("g0") ? KIND_FROM : KIND_TO;
                                    if (loadDrawable(kind) != null) {
                                        applyBubbleTo((View) v, kind);
                                        LogWriter.log(TAG, "link subtype REPLACE " + owner
                                                + "." + m.getName() + " kind=" + kind);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        LogWriter.log(TAG, "link subtype hooked " + cn + "." + m.getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installLinkSubtypeHook err: " + t.getMessage());
        }
    }

    /** 在 ViewHolder 字段中定位气泡 View：优先背景与 ke5.a.i 记录的微信原生气泡
     *  （constantState）匹配的 View（真实气泡背景就是 chatfrom_bg/chatto_bg），
     *  其次 MMNeat7extView（文本视图本身做气泡背景），再按字段名 b/d/f 兜底。
     *  遍历时打印 View 字段信息，便于下次日志确认微信实际气泡 View 落在哪个字段。 */
    private static View findBubbleView(Object holder) {
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (!(v instanceof View)) continue;
                    View view = (View) v;
                    String cn = view.getClass().getName();
                    String bg = view.getBackground() == null ? "null"
                            : view.getBackground().getClass().getName();
                    LogWriter.log(TAG, "  holderField " + c.getSimpleName() + "." + f.getName()
                            + " view=" + cn + " w=" + view.getWidth() + " h=" + view.getHeight()
                            + " bg=" + bg);
                    if (matchBaseDrawable(view.getBackground()) >= 0) {
                        return view;
                    }
                    if (cn.contains("MMNeat7extView") || cn.contains("MMNeatTextView")
                            || cn.contains("Neat")) {
                        return view;
                    }
                    String fn = f.getName();
                    if (("b".equals(fn) || "d".equals(fn) || "f".equals(fn))
                            && view instanceof android.widget.TextView) {
                        return view;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 与 ke5.a.i 记录的微信原始气泡 Drawable 对比 constantState。
     *  文本气泡背景是 StateListDrawable（selector），内部状态子项含原生气泡资源，
     *  递归匹配子 Drawable，否则文本气泡（selector）会被 setBackground 拦截路径跳过。 */
    private static int matchBaseDrawable(Drawable d) {
        if (d == null) return -1;
        try {
            if (sFromBaseDrawable != null && sameConstant(sFromBaseDrawable, d)) return KIND_FROM;
            if (sToBaseDrawable != null && sameConstant(sToBaseDrawable, d)) return KIND_TO;
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable sld =
                        (android.graphics.drawable.StateListDrawable) d;
                for (int i = 0; i < sld.getStateCount(); i++) {
                    int k = matchBaseDrawable(sld.getStateDrawable(i));
                    if (k >= 0) return k;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean sameConstant(Drawable a, Drawable b) {
        return a.getConstantState() != null && a.getConstantState().equals(b.getConstantState());
    }

    /** 是否为"本模块构建的自定义气泡图"。
     *  v3.0.128：不再把所有 {@link android.graphics.drawable.NinePatchDrawable} 一律视为自定义
     *  （微信原生气泡本身就是 NinePatchDrawable，会被误判从而挡住启发式替换）；
     *  改为 NineSliceDrawable 直接命中 + 用 constantState 与本模块缓存态比对。 */
    private static boolean isCustomBackground(Drawable d) {
        if (d == null) return false;
        if (d instanceof BubbleDrawableFactory.NineSliceDrawable) return true;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                if (sFromDrawableState != null && cs.equals(sFromDrawableState)) return true;
                if (sToDrawableState != null && cs.equals(sToDrawableState)) return true;
                if (cs.getClass().getName().contains("NineSlice")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 方案 D：Resources.getDrawable 加载路径（聊天页加载气泡背景的最通用入口）。
     *  ke5.a.i 只在启动预构建时调用一次，kw5.g.r / setBackgroundResource 在 8.0.78 实测不触发；
     *  微信聊天 item 很可能通过 Resources.getDrawable(resId) 加载气泡九宫格。 */
    private static void installResourceGetDrawableHook(ClassLoader cl) {
        try {
            Class<?> resCls = XposedHelpers.findClass("android.content.res.Resources", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        // 打印资源名 + 调用栈，确认是否为聊天气泡背景加载
                        try {
                            Object ctx = ContextManager.getAppContext();
                            android.content.res.Resources res = (android.content.res.Resources) param.thisObject;
                            String name = res.getResourceEntryName(resId);
                            LogWriter.log(TAG, "getDrawable resId=" + resId + " name=" + name);
                        } catch (Throwable ignored) {}
                        try {
                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                            StringBuilder sb = new StringBuilder("getDrawable stack:");
                            int n = Math.min(st.length, 6);
                            for (int i = 2; i < n; i++) {
                                sb.append("\n  ").append(st[i].getClassName())
                                        .append('.').append(st[i].getMethodName());
                            }
                            LogWriter.log(TAG, sb.toString());
                        } catch (Throwable ignored) {}
                        // 不替换 getDrawable 返回值：ke5.a.i / setBackgroundResource 内部
                        // 会再次加载并设置到真实气泡 View，这里只记录，避免污染 baseDrawable。
                        // 真实替换点：setBackgroundResource / ImageView.setImageDrawable / onLayout。
                    } catch (Throwable ignored) {}
                }
            };
            for (Method m : resCls.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"getDrawable".equals(mn)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == int.class) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, h);
                        LogWriter.log(TAG, "Resources.getDrawable(int) hooked");
                    } catch (Throwable ignored) {}
                } else if (pts.length == 2 && pts[0] == int.class) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, h);
                        LogWriter.log(TAG, "Resources.getDrawable(int,Theme) hooked");
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installResourceGetDrawableHook err: " + t.getMessage());
        }
    }

    /** 方案 B：X2C Drawable 加载路径，hook ke5.a.i(Context,int) 按 resId 替换。 */
    /** 方案 F：聊天列表渲染兜底。微信聊天 item 在 8.0.78 不经过 ke5.a.i/getDrawable/
     *  setBackground 设置气泡背景，而是在 X2C 预构建/代码构建的 item 上直接使用缓存 Drawable。
     *  Hook AbsListView / RecyclerView 的 onLayout，每次布局后遍历 item 树，
     *  凡背景与 ke5.a.i 记录的微信原生气泡 Drawable（constantState）匹配即替换。 */
    private static void installChatListViewHook(ClassLoader cl) {
        try {
            Method onLayout = null;
            for (Method m : AbsListView.class.getDeclaredMethods()) {
                if ("onLayout".equals(m.getName())) { onLayout = m; break; }
            }
            if (onLayout != null) {
                onLayout.setAccessible(true);
                XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!sEnabled) return;
                            replaceMatchingBubbles((View) param.thisObject);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "AbsListView.onLayout hooked");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "AbsListView.onLayout err: " + t.getMessage());
        }
        for (String cn : new String[]{"androidx.recyclerview.widget.RecyclerView",
                "android.support.v7.widget.RecyclerView"}) {
            try {
                Class<?> rv = XposedHelpers.findClass(cn, cl);
                Method onLayout = null;
                for (Method m : rv.getDeclaredMethods()) {
                    if ("onLayout".equals(m.getName())) { onLayout = m; break; }
                }
                if (onLayout != null) {
                    onLayout.setAccessible(true);
                    XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                replaceMatchingBubbles((View) param.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "RecyclerView.onLayout hooked " + cn);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** v3.0.89：收集当前进程当前 UI 的 View 与调用链验证。
     *  日志显示 setBackgroundResource REPLACE 大量命中无关 TextView/AnimImageView
     *  （w=0 h=0），真实气泡 View 尚未定位。此 hook 在微信聊天列表刷新后遍历
     *  View 树，打印所有 View 的类名/宽高/背景，结合 ke5.a.i 调用栈确认真实气泡 View；
     *  ke5.a.i 返回值替换法保留，真实气泡路径确认后再替换。 */
    private static void installChattingListCollector(ClassLoader cl) {
        try {
            List<String> cands = new ArrayList<>();
            cands.add("com.tencent.mm.ui.chatting.ChattingList");
            try {
                List<String> dk = DexKitHelper.findClassesByString(cl, "refreshChattingList");
                for (String cn : dk) if (!cands.contains(cn)) cands.add(cn);
            } catch (Throwable ignored) {}
            int hooked = 0;
            for (String cn : cands) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"refreshChattingList".equals(m.getName())) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    Object thiz = param.thisObject;
                                    if (thiz instanceof View) {
                                        dumpViewTree((View) thiz, "refreshChattingList");
                                    } else {
                                        LogWriter.log(TAG, "refreshChattingList thiz="
                                                + (thiz == null ? "null" : thiz.getClass().getName()));
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        hooked++;
                        LogWriter.log(TAG, "ChattingList.refreshChattingList hooked cls="
                                + c.getName() + " m=" + m);
                    }
                } catch (Throwable ignored) {}
            }
            if (hooked == 0) {
                try {
                    Class<?> rv = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", cl);
                    Method onLayout = null;
                    for (Method m : rv.getDeclaredMethods()) {
                        if ("onLayout".equals(m.getName())) { onLayout = m; break; }
                    }
                    if (onLayout != null) {
                        onLayout.setAccessible(true);
                        XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    dumpViewTree((View) param.thisObject, "RecyclerView.onLayout");
                                } catch (Throwable ignored) {}
                            }
                        });
                        hooked++;
                        LogWriter.log(TAG, "RecyclerView.onLayout dump hooked (fallback)");
                    }
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "ChattingList collector hooks=" + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "installChattingListCollector err: " + t.getMessage());
        }
    }

    /** 递归打印 View 树：类名、宽高、屏幕坐标、背景，用于确认真实气泡 View。 */
    private static void dumpViewTree(View v, String src) {
        if (v == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("view=").append(v.getClass().getName())
                    .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight());
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                sb.append(" xy=").append(loc[0]).append(",").append(loc[1]);
            } catch (Throwable ignored) {}
            Drawable bg = v.getBackground();
            sb.append(" bg=").append(bg == null ? "null" : bg.getClass().getName());
            LogWriter.log(TAG, "chattingView[" + src + "] " + sb);
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpViewTree(g.getChildAt(i), src);
            }
        }
    }

    /** 记录已替换为自定义气泡的 View，供布局完成后强制恢复（文本气泡修复）。 */
    private static void rememberBubble(View v, Drawable custom) {
        if (v == null || custom == null) return;
        synchronized (sBubbleViews) {
            sBubbleViews.put(v, custom);
        }
    }

    /** v3.0.128：记录气泡 View 及其方向，供补盖时同步文字颜色。 */
    private static void rememberBubble(View v, Drawable custom, int kind) {
        if (v == null || custom == null) return;
        synchronized (sBubbleViews) {
            sBubbleViews.put(v, custom);
        }
        try {
            synchronized (sBubbleKind) { sBubbleKind.put(v, kind); }
        } catch (Throwable ignored) {}
    }

    /** 聊天列表布局后，把已记录的气泡 View 背景强制恢复为自定义图。
     *  v3.0.128：改用「背景是否仍为自定义图」判定（constantState 级），避免每帧重复补盖；
     *  恢复时用独立实例并同步文字颜色/内边距。 */
    private static void restoreRecordedBubbles() {
        final java.util.List<View> need = new java.util.ArrayList<>();
        synchronized (sBubbleViews) {
            for (java.util.Iterator<java.util.Map.Entry<View, Drawable>> it =
                 sBubbleViews.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<View, Drawable> e = it.next();
                View v = e.getKey();
                if (v == null) continue;
                try {
                    if (!v.isShown()) continue;
                    if (!isCustomBackground(v.getBackground())) need.add(v);
                } catch (Throwable ignored) {}
            }
        }
        for (View v : need) {
            try {
                int kind = kindOf(v);
                Drawable d = fresh(loadDrawable(kind));
                if (d == null) continue;
                v.setBackground(d);
                rememberBubble(v, d, kind);
                applyTextColor(v, kind);
                syncBubblePadding(v);
                LogWriter.log(TAG, "bubble restore view=" + v.getClass().getName()
                        + " w=" + v.getWidth() + " h=" + v.getHeight());
            } catch (Throwable ignored) {}
        }
    }

    /** 递归遍历 View 树，把背景与微信原生气泡匹配的 View 替换为用户图片。 */
    private static void replaceMatchingBubbles(View v) {
        if (v == null) return;
        restoreRecordedBubbles();
        try {
            int kind = matchBaseDrawable(v.getBackground());
            if (kind >= 0) {
                Drawable custom = loadDrawable(kind);
                if (custom != null) {
                    v.setBackground(custom);
                    LogWriter.log(TAG, "onLayout REPLACE kind=" + kind
                            + " view=" + v.getClass().getName());
                }
            }
        } catch (Throwable ignored) {}
        // v3.0.125 启发式兜底：baseDrawable 不可用(matchBaseDrawable 全失败)时，
        // 按「九宫格背景 + x 坐标」判定收发方向并替换，确保文本气泡也能生效。
        heuristicReplaceOne(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                replaceMatchingBubbles(g.getChildAt(i));
            }
        }
    }

    /** v3.0.91：hook X2C 背景应用点 kw5.i0.f(Context, View, String, Drawable)。
     *  文档证实 gm.g/gm.i 通过 kw5.i0.f 把气泡 Drawable 设置到 MMNeat7extView，
     *  此处直接替换 Drawable 参数，命中真实气泡 View。
     *  ke5.a.i 返回值替换后 custom 不匹配 baseDrawable，此处保持放行；
     *  未走 ke5.a.i 的路径（如直接 getDrawable）在此拦截替换。 */
    private static void installBubbleApplyHook(ClassLoader cl) {
        try {
            boolean hooked = false;
            for (Class<?> c : HookUtil.loadClasses(cl, "kw5.i0")) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (!"f".equals(m.getName()) || pts.length != 4
                            || pts[0] != Context.class || pts[1] != View.class
                            || pts[2] != String.class || pts[3] != Drawable.class) {
                        continue;
                    }
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                Drawable d = (Drawable) param.args[3];
                                int kind = matchBaseDrawable(d);
                                if (kind < 0) return;
                                Drawable custom = loadDrawable(kind);
                                if (custom != null) {
                                    param.args[3] = custom;
                                    LogWriter.log(TAG, "i0.f REPLACE kind=" + kind
                                            + " view=" + param.thisObject.getClass().getName());
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked = true;
                    LogWriter.log(TAG, "bubble apply hooked " + c.getName() + "."
                            + m.getName() + Arrays.toString(pts)
                            + " loader=" + HookUtil.loaderName(c.getClassLoader()));
                }
            }
            if (!hooked) {
                LogWriter.log(TAG, "bubble apply hook none found (kw5.i0.f)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBubbleApplyHook err: " + t.getMessage());
        }
    }

    private static void installResourceHelperHook(ClassLoader cl) {
        try {
            if (!DexKitHelper.isScanComplete()) return;
            List<String> classes = new ArrayList<>();
            try {
                classes.addAll(DexKitHelper.findClassesByString(cl, "MicroMsg.ResourceHelper"));
            } catch (Throwable ignored) {}
            if (!classes.contains("ke5.a")) classes.add("ke5.a");
            for (String cn : classes) {
                java.util.Set<Class<?>> loaded = HookUtil.loadClasses(cl, cn);
                if (loaded.isEmpty()) {
                    try {
                        loaded.add(XposedHelpers.findClass(cn, cl));
                    } catch (Throwable ignored) {}
                }
                for (Class<?> c : loaded) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (!"i".equals(m.getName()) || pts.length != 2
                                || pts[0] != Context.class || pts[1] != int.class) {
                            continue;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    int resId = ((Number) param.args[1]).intValue();
                                    boolean from = resId == sFromResId;
                                    boolean to = resId == sToResId;
                                    if (!from && !to) return;
                                    // 打印资源名 + 调用栈，确认是否为聊天气泡背景加载
                                    try {
                                        Context ctx = (Context) param.args[0];
                                        String name = ctx.getResources().getResourceEntryName(resId);
                                        LogWriter.log(TAG, "ke5.a.i resId=" + resId + " name=" + name);
                                    } catch (Throwable ignored) {}
                                    try {
                                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                        StringBuilder sb = new StringBuilder("ke5.a.i stack:");
                                        int n = Math.min(st.length, 8);
                                        for (int i = 2; i < n; i++) {
                                            sb.append("\n  ").append(st[i].getClassName())
                                                    .append('.').append(st[i].getMethodName());
                                        }
                                        LogWriter.log(TAG, sb.toString());
                                    } catch (Throwable ignored) {}
                                    try {
                                        // 记录微信原始气泡 Drawable（资源缓存实例，用于 setBackground 拦截识别）。
                                        // 此时 getDrawable 不替换，拿到的是微信原始气泡。
                                        Context ctx0 = (Context) param.args[0];
                                        Drawable base = ctx0.getResources().getDrawable(resId);
                                        if (from) {
                                            sFromBaseDrawable = base;
                                        } else if (to) {
                                            sToBaseDrawable = base;
                                        }
                                        LogWriter.log(TAG, "ke5.a.i baseDrawable saved from="
                                                + (from ? "y" : "n") + " to=" + (to ? "y" : "n"));
                                    } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // v3.0.90：ke5.a.i 返回值是气泡 Drawable 的最终加载点
                                // （日志证实 viewitems.mq.b 调用 ke5.a.i），在此替换返回值，
                                // 微信后续不再重新覆盖原始气泡背景。
                                try {
                                    if (!sEnabled) return;
                                    int resId = ((Number) param.args[1]).intValue();
                                    boolean from = resId == sFromResId;
                                    boolean to = resId == sToResId;
                                    if (!from && !to) return;
                                    int kind = from ? KIND_FROM : KIND_TO;
                                    Drawable custom = loadDrawable(kind);
                                    if (custom != null) {
                                        param.setResult(custom);
                                        LogWriter.log(TAG, "ke5.a.i REPLACE RETURN resId=" + resId
                                                + " kind=" + kind);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        LogWriter.log(TAG, "ResourceHelper hooked " + c.getName() + ".i");
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installResourceHelperHook err: " + t.getMessage());
        }
    }

    private static void installBubbleResolver(ClassLoader cl) throws Throwable {
        if (!DexKitHelper.isScanComplete()) {
            throw new IllegalStateException("DexKit scan not ready");
        }
        // 文档第 5 节：search_strings("chatfrom_bg"/"chatto_bg") → X2C 生成类，
        // 其父类才是资源解析包装层（含 r(Context,View,String,String,int)→Drawable）。
        List<String> genClasses = new ArrayList<>();
        for (String kw : new String[]{"chatfrom_bg", "chatto_bg"}) {
            try {
                List<String> sigs = DexKitHelper.findMethodsByString(cl, null, kw);
                for (String sig : sigs) {
                    String cn = classNameOf(sig);
                    if (cn != null && !genClasses.contains(cn)) genClasses.add(cn);
                }
            } catch (Throwable ignored) {}
        }
        if (genClasses.isEmpty()) {
            throw new NoSuchMethodException("no X2C class contains chatfrom_bg/chatto_bg");
        }
        LogWriter.log(TAG, "X2C gen classes=" + genClasses);
        Method target = null;
        Class<?> owner = null;
        for (String cn : genClasses) {
            // v3.0.91：必须用 HookUtil.loadClasses 遍历所有候选 ClassLoader
            //（Tinker DelegateLastClassLoader 才是运行时真实类，单个 cl 会 hook 空）
            for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                LogWriter.log(TAG, "X2C candidate " + cn + " loader="
                        + HookUtil.loaderName(c.getClassLoader()));
                for (Class<?> sup = c.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
                    Method m = findResolver(sup);
                    if (m != null) {
                        target = m;
                        owner = sup;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target != null) break;
        }
        if (target == null) {
            throw new NoSuchMethodException("resolver r(Context,View,String,String,int) not found");
        }
        final Class<?> fOwner = owner;
        LogWriter.log(TAG, "resolver class=" + fOwner.getName()
                + " method=" + target.getName() + Arrays.toString(target.getParameterTypes()));
        target.setAccessible(true);
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String value = (String) param.args[3];
                    int resId = param.args.length > 4 && param.args[4] instanceof Number
                            ? ((Number) param.args[4]).intValue() : 0;
                    boolean isFrom = resId == sFromResId
                            || (value != null && value.contains("chatfrom_bg"));
                    boolean isTo = resId == sToResId
                            || (value != null && value.contains("chatto_bg"));
                    if (isFrom || isTo) {
                        int kind = isFrom ? KIND_FROM : KIND_TO;
                        if (!sEnabled) return;
                        View v0 = param.args.length > 1 && param.args[1] instanceof View
                                ? (View) param.args[1] : null;
                        // 抖动抑制 v3.0.120：背景已是自定义图时仍必须 setResult(custom)，
                        // 阻止原始 r() 继续执行把微信原生气泡流出覆盖我们的替换；
                        // 仅跳过重复 setBackground，避免形成替换风暴。
                        boolean bgAlreadyCustom = v0 != null && isCustomBackground(v0.getBackground());
                        if (!sFirstResolverLogged) {
                            sFirstResolverLogged = true;
                            String path = kind == KIND_FROM ? sFromPath : sToPath;
                            LogWriter.log(TAG, "resolver first hit value=" + value + " resId=" + resId
                                    + " path=" + (path != null ? path : "null"));
                        }
                        Drawable d = loadDrawable(kind);
                        if (d != null) {
                            param.setResult(d);
                            if (v0 != null && !bgAlreadyCustom) {
                                v0.setBackground(d);
                                rememberBubble(v0, d);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static Method findResolver(Class<?> c) {
        if (c == null) return null;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if ("r".equals(m.getName()) && pts.length == 5
                    && pts[0] == Context.class && pts[1] == View.class
                    && pts[2] == String.class && pts[3] == String.class
                    && pts[4] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** 从 "pkg.Cls.method(params)" 解析出声明类全名。 */
    private static String classNameOf(String sig) {
        if (sig == null) return null;
        int p = sig.indexOf('(');
        if (p < 0) p = sig.length();
        String head = sig.substring(0, p);
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(0, dot) : null;
    }

    private static Drawable loadDrawable(int kind) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return null;
        String path = kind == KIND_FROM ? sFromPath : sToPath;
        if (path == null || path.isEmpty()) return null;
        // 路径不变时复用已构建的 Drawable 模板：缓存 ConstantState，每次 newDrawable() 出新实例，
        // 避免列表滚动时反复解码/构造，同时防止多 View 共享同一可变 Drawable。
        synchronized (sDrawableCache) {
            Drawable.ConstantState cached = kind == KIND_FROM ? sFromDrawableState : sToDrawableState;
            String cachedPath = kind == KIND_FROM ? sFromDrawablePath : sToDrawablePath;
            if (cached != null && path.equals(cachedPath)) {
                Drawable d = cached.newDrawable(ctx.getResources());
                if (d != null) { d.mutate(); return d; }
            }
            Drawable built = BubbleDrawableFactory.build(ctx, path);
            if (built == null) {
                LogWriter.log(TAG, "loadDrawable BUILD FAILED kind=" + kind + " path=" + path
                        + " exists=" + new java.io.File(path).exists());
                return null;
            }
            LogWriter.log(TAG, "loadDrawable built kind=" + kind + " cls=" + built.getClass().getName()
                    + " path=" + path + " iw=" + built.getIntrinsicWidth() + "ih=" + built.getIntrinsicHeight());
            Drawable.ConstantState cs = built.getConstantState();
            if (kind == KIND_FROM) {
                sFromDrawableState = cs;
                sFromDrawablePath = path;
            } else {
                sToDrawableState = cs;
                sToDrawablePath = path;
            }
            return built;
        }
    }

    // ==================== v3.0.125 运行时自校准 + 启发式兜底 ====================

    /**
     * 运行时自校准（方案②）：在本机"全量、不白名单"地记录所有气泡背景设置点，
     * 导出真实 (viewClass, resId, name, xy, parent) 映射，供日志人工核对后固化。
     * 覆盖：View.setBackgroundResource/setBackground/setBackgroundDrawable、
     * TextView/ImageView 覆盖点、Resources.getDrawable(int)/(int,Theme)。
     * 记录按签名去重并封顶 CAL_MAX 条，避免日志暴涨。
     */
    private static void installCalibrationHooks(ClassLoader cl) {
        if (!CALIBRATE) return;
        try {
            hookBgSetter(View.class, "setBackgroundResource", int.class, "setBackgroundResource");
            hookBgSetter(View.class, "setBackground", Drawable.class, "setBackground");
            hookBgSetter(View.class, "setBackgroundDrawable", Drawable.class, "setBackgroundDrawable");
            // 用户怀疑漏网点：TextView 老 API（若子类声明才命中，未声明则静默跳过）
            hookBgSetter(android.widget.TextView.class, "setBackgroundDrawable", Drawable.class, "TextView.setBackgroundDrawable");
            hookBgSetter(android.widget.TextView.class, "setBackgroundResource", int.class, "TextView.setBackgroundResource");
            hookBgSetter(ImageView.class, "setImageDrawable", Drawable.class, "setImageDrawable");
            hookBgSetter(ImageView.class, "setImageResource", int.class, "setImageResource");
            installGetDrawableCalibration(cl);
            LogWriter.log(TAG, "calibration hooks installed (CALIBRATE=" + CALIBRATE + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installCalibrationHooks err: " + t.getMessage());
        }
    }

    /** 通用背景设置点校准日志：不判断 resId 是否在白名单，仅按签名去重记录。 */
    private static void hookBgSetter(Class<?> owner, String name, Class<?> argType, String tag) {
        try {
            Method m = null;
            for (Method mm : owner.getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == argType) {
                    m = mm;
                    break;
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof View)) return;
                        View v = (View) param.thisObject;
                        Object a0 = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        int resId = a0 instanceof Number ? ((Number) a0).intValue() : 0;
                        String drawableCls = a0 instanceof Drawable ? a0.getClass().getName() : "-";
                        String key = tag + "|" + v.getClass().getName() + "|" + resId + "|" + drawableCls;
                        if (!sCalSeen.add(key)) return;
                        if (sCalCount.incrementAndGet() > CAL_MAX) return;
                        int[] loc = {0, 0};
                        try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "CAL " + tag
                                + " view=" + v.getClass().getName()
                                + " resId=" + resId + " name=" + (resId != 0 ? entryName(resId) : "-")
                                + " drawable=" + drawableCls
                                + " xy=(" + loc[0] + "," + loc[1] + ") w=" + v.getWidth() + " h=" + v.getHeight()
                                + " chat=" + inChatList(v)
                                + " parent=" + parentChain(v, 2));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookBgSetter " + tag + " err: " + t.getMessage());
        }
    }

    /** 校准 Resources.getDrawable：仅当调用栈含聊天/气泡关键字时记录（避免全 App 刷屏）。 */
    private static void installGetDrawableCalibration(ClassLoader cl) {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Number)) return;
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        boolean chat = false;
                        String caller = "?";
                        int n = Math.min(st.length, 14);
                        for (int i = 2; i < n; i++) {
                            String c = st[i].getClassName();
                            if (i == 2) caller = c + "." + st[i].getMethodName();
                            if (c.contains("chatting") || c.contains("Chatting")
                                    || c.contains("viewitems") || c.contains("Bubble")
                                    || c.contains("bubble")) { chat = true; break; }
                        }
                        if (!chat) return;
                        int resId = ((Number) param.args[0]).intValue();
                        String key = "getDrawable|" + resId;
                        if (!sCalSeen.add(key)) return;
                        if (sCalCount.incrementAndGet() > CAL_MAX) return;
                        LogWriter.log(TAG, "CAL getDrawable resId=" + resId
                                + " name=" + entryName(resId) + " caller=" + caller);
                    } catch (Throwable ignored) {}
                }
            };
            for (Method m : android.content.res.Resources.class.getDeclaredMethods()) {
                if (!"getDrawable".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if ((pts.length == 1 && pts[0] == int.class)
                        || (pts.length == 2 && pts[0] == int.class)) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, h);
                }
            }
            LogWriter.log(TAG, "getDrawable calibration hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installGetDrawableCalibration err: " + t.getMessage());
        }
    }

    /**
     * 方案③ 兜底：hook LayoutInflater.inflate(int, ViewGroup, boolean)。
     * 当父容器是聊天列表容器时，inflate 完成后 post 一次遍历，
     * 对"背景为九宫格的气泡容器"按 x 坐标替换（右半=发出/左半=收到）。
     * 完全不依赖类名/resId，只要气泡可见即命中。
     */
    private static void installInflateFallbackHook(ClassLoader cl) {
        try {
            Method inflate = null;
            for (Method m : android.view.LayoutInflater.class.getDeclaredMethods()) {
                if ("inflate".equals(m.getName())) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 3 && pts[0] == int.class
                            && ViewGroup.class.isAssignableFrom(pts[1]) && pts[2] == boolean.class) {
                        inflate = m;
                        break;
                    }
                }
            }
            if (inflate == null) {
                LogWriter.log(TAG, "inflate fallback: method not found");
                return;
            }
            inflate.setAccessible(true);
            XposedBridge.hookMethod(inflate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || !sHeuristic) return;
                        Object root = param.args != null && param.args.length > 1 ? param.args[1] : null;
                        if (!(root instanceof View) || !isChatContainer((View) root)) return;
                        Object out = param.getResult();
                        if (!(out instanceof View)) return;
                        final View v = (View) out;
                        v.post(() -> {
                            try { heuristicReplaceBubbles(v); } catch (Throwable ignored) {}
                        });
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "LayoutInflater.inflate fallback hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installInflateFallbackHook err: " + t.getMessage());
        }
    }

    /** 递归启发式替换：背景为九宫格(非自定义)的气泡容器按 x 坐标判定收发方向并替换。 */
    private static void heuristicReplaceBubbles(View v) {
        if (v == null) return;
        heuristicReplaceOne(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                heuristicReplaceBubbles(g.getChildAt(i));
            }
        }
    }

    /** 单个 View 的启发式替换（供 onLayout 遍历复用）。 */
    private static void heuristicReplaceOne(View v) {
        if (v == null || !sEnabled || !sHeuristic) return;
        try {
            Drawable bg = v.getBackground();
            if (!isNinePatchLike(bg) || isCustomBackground(bg)) return;
            int w = v.getWidth(), h = v.getHeight();
            if (w <= 0 || h <= 0) return;
            int[] loc = {0, 0};
            try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
            int screenW = 0;
            try { screenW = v.getResources().getDisplayMetrics().widthPixels; } catch (Throwable ignored) {}
            int center = loc[0] + w / 2;
            int kind = (screenW > 0 && center > screenW / 2) ? KIND_TO : KIND_FROM;
            if (loadDrawable(kind) != null) {
                applyBubbleTo(v, kind);
                LogWriter.log(TAG, "heuristic REPLACE kind=" + kind
                        + " view=" + v.getClass().getName()
                        + " center=" + center + "/" + screenW
                        + " parent=" + parentChain(v, 2));
            }
        } catch (Throwable ignored) {}
    }

    // ==================== v3.0.128 统一补盖（根治偶发不渲染） ====================

    /**
     * 从模板 Drawable 派生一个可独立变更的新实例。
     *
     * <p>修复「共享 Drawable 实例 → bounds/callback 互踩 → 偶发不画」：多 View 若共用同一
     * Drawable 实例，滚动时会互相覆盖 setBounds，导致部分气泡不渲染。每次 newDrawable().mutate()
     * 出新实例即可隔离。</p>
     */
    private static Drawable fresh(Drawable template) {
        if (template == null) return null;
        try {
            Drawable.ConstantState cs = template.getConstantState();
            if (cs != null) {
                Drawable d = cs.newDrawable();
                if (d != null) {
                    d.mutate();
                    return d;
                }
            }
        } catch (Throwable ignored) {}
        try { return template.mutate(); } catch (Throwable ignored) {}
        return template;
    }

    /**
     * 统一把某个 View 应用为自定义气泡：设置独立背景实例 + 同步内边距(触发 NeatTextView 重排)
     * + 应用文字颜色 + requestLayout/invalidate。所有替换路径（setBackgroundResource、
     * bind-after、attach-after、onLayout、启发式）都收敛到此处，行为一致。
     */
    private static void applyBubbleTo(View v, int kind) {
        if (v == null || !sEnabled) return;
        try {
            Drawable custom = loadDrawable(kind);
            if (custom == null) return;
            Drawable d = fresh(custom);
            v.setBackground(d);
            rememberBubble(v, d, kind);
            applyTextColor(v, kind);
            syncBubblePadding(v);
            try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** 应用气泡内文字颜色（0 = 不修改）。文本气泡承载视图可能是 MMNeat7extView（非 TextView），
     *  其 setTextColor 会把颜色同步到内层 wrappedTextView。 */
    private static void applyTextColor(View v, int kind) {
        int color = kind == KIND_FROM ? sFromTextColor : sToTextColor;
        if (color == 0 || v == null) return;
        try {
            if (v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextColor(color);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            Method m = v.getClass().getMethod("setTextColor", int.class);
            m.setAccessible(true);
            m.invoke(v, color);
        } catch (Throwable ignored) {}
    }

    /**
     * 重新下发一次 padding（值不变）以触发 {@code MMNeat7extView.setPadding} 重写 →
     * 同步内层 wrappedTextView 的 padding 并重算 Layout 缓存。不复排会导致换了背景后
     * 文字不随气泡伸缩（看着像没渲染/错位）。
     */
    private static void syncBubblePadding(View v) {
        if (v == null) return;
        try {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom());
        } catch (Throwable ignored) {}
    }

    /** 取已记录的气泡方向；未记录时按 x 坐标几何兜底。 */
    private static int kindOf(View v) {
        try {
            Integer k = sBubbleKind.get(v);
            if (k != null) return k;
        } catch (Throwable ignored) {}
        try {
            int[] loc = {0, 0};
            v.getLocationOnScreen(loc);
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            if (screenW > 0 && (loc[0] + v.getWidth() / 2) > screenW / 2) return KIND_TO;
        } catch (Throwable ignored) {}
        return KIND_FROM;
    }

    /**
     * 补盖：微信 {@code AnimImageView.setType()} 在复用/空态分支会调用
     * {@code setBackgroundDrawable(null)} 把气泡清空；此处对"已记录的气泡 View"无条件补回自定义图。
     * 未记录时退化为启发式替换。
     */
    private static void reapplyIfBubble(View v) {
        if (!sEnabled || v == null) return;
        try {
            Drawable recorded = sBubbleViews.get(v);
            if (recorded != null) {
                int kind = kindOf(v);
                Drawable d = fresh(recorded);
                v.setBackground(d);
                rememberBubble(v, d, kind);
                applyTextColor(v, kind);
                syncBubblePadding(v);
                try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}
        heuristicReplaceOne(v);
    }

    /** 对聊天 item 根 View 递归补盖：原生九宫格→替换；已是自定义→补文字色/内边距。 */
    private static void applyToHolder(View item) {
        if (item == null || !sEnabled) return;
        try {
            Drawable bg = item.getBackground();
            if (isCustomBackground(bg)) {
                applyTextColor(item, kindOf(item));
            } else if (isNinePatchLike(bg)) {
                heuristicReplaceOne(item);
            }
        } catch (Throwable ignored) {}
        if (item instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) item;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyToHolder(g.getChildAt(i));
            }
        }
    }

    /**
     * bind/attach-after 补盖兜底（不再依赖 setter 是否触发）：
     * hook {@code View.onAttachedToWindow}，当 View 是聊天列表的直接子项（item 根）时，
     * post 一次对该 item 子树的补盖，覆盖 RecyclerView 视图池命中/复用未 rebind 路径。
     */
    private static void installAttachApplyHook(ClassLoader cl) {
        try {
            Method m = View.class.getDeclaredMethod("onAttachedToWindow");
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        View v = (View) param.thisObject;
                        android.view.ViewParent p = v.getParent();
                        if (!(p instanceof View)) return;
                        if (!isChatContainer((View) p)) return;   // 仅聊天列表的直接子项
                        v.post(() -> {
                            try { applyToHolder(v); } catch (Throwable ignored) {}
                        });
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "onAttachedToWindow apply hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installAttachApplyHook err: " + t.getMessage());
        }
    }

    /** 背景是否为九宫格 Drawable（含 selector 子项）。 */
    private static boolean isNinePatchLike(Drawable d) {
        if (d == null) return false;
        if (d instanceof android.graphics.drawable.NinePatchDrawable) return true;
        if (d instanceof android.graphics.drawable.StateListDrawable) {
            android.graphics.drawable.StateListDrawable sld =
                    (android.graphics.drawable.StateListDrawable) d;
            for (int i = 0; i < sld.getStateCount(); i++) {
                if (isNinePatchLike(sld.getStateDrawable(i))) return true;
            }
        }
        try {
            Drawable.ConstantState cs = d.getConstantState();
            String n = cs == null ? "" : cs.getClass().getName();
            if (n.contains("NinePatch") || n.contains("NineSlice")) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 判断容器是否聊天列表容器（RecyclerView/AbsListView/Chatting*）。 */
    private static boolean isChatContainer(View v) {
        if (v == null) return false;
        String cn = v.getClass().getName();
        return v instanceof android.widget.AbsListView
                || cn.contains("RecyclerView")
                || cn.contains("ChattingList")
                || cn.contains("ChattingUI")
                || cn.contains("Chatting");
    }

    /** 沿父链判断 View 是否位于聊天列表内。 */
    private static boolean inChatList(View v) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < 30) {
            if (isChatContainer(cur)) return true;
            android.view.ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** 资源 ID → 资源名（失败返回 ?）。 */
    private static String entryName(int resId) {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null || resId == 0) return "-";
            return ctx.getResources().getResourceEntryName(resId);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 未解析出 ResourceHelper 时，判断原始气泡 resId 的收/发方向。
     *
     *  <p>报告 §2 白名单（本构建 8.0.78 实测，覆盖普通/发送中/链接三种子场景）：
     *  收到 = {2131231925 普通, 2131231841 发送中, 2131231944 链接}；
     *  发出 = {2131232060 普通, 2131231895 发送中, 2131232070 链接}。
     *  资源名在本 APK 可能被混淆，故先按硬编码 resId 命中，再用 getResourceEntryName 兜底。</p> */
    private static int resolveKindByResId(Context ctx, int resId) {
        if (resId == 0) return -1;
        if (resId == 2131231925 || resId == 2131231841 || resId == 2131231944) return KIND_FROM;
        if (resId == 2131232060 || resId == 2131231895 || resId == 2131232070) return KIND_TO;
        if (resId == sFromResId) return KIND_FROM;
        if (resId == sToResId) return KIND_TO;
        if (ctx == null) return -1;
        try {
            String name = ctx.getResources().getResourceEntryName(resId);
            if (name == null) return -1;
            if (name.contains("chatfrom")) return KIND_FROM;
            if (name.contains("chatto")) return KIND_TO;
        } catch (Throwable ignored) {}
        return -1;
    }
}
