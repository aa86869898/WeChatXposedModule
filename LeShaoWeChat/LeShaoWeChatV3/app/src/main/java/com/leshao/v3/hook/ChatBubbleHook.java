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

    // 气泡资源 ID（XML 兜底路径用）。优先 getIdentifier 动态解析，失败回退文档已知值
    private static volatile int sFromResId = 2131231925; // chatfrom_bg
    private static volatile int sToResId = 2131232060;   // chatto_bg

    // Bitmap 缓存：路径不变时复用，避免每次 setBackground 都解码
    private static volatile Bitmap sFromBmp;
    private static volatile Bitmap sToBmp;
    private static volatile String sFromBmpPath;
    private static volatile String sToBmpPath;

    // 微信原始气泡 Drawable 基准（ke5.a.i 预构建时记录），用于 View.setBackground 拦截识别
    private static volatile Drawable sFromBaseDrawable;
    private static volatile Drawable sToBaseDrawable;

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
                    installBubbleResolver(cl);
                    installBackgroundResourceHook();
                    installBackgroundHook();
                    installImageViewHook();
                    installViewitemsToHook(cl);
                    installLinkSubtypeHook(cl);
                    installResourceHelperHook(cl);
                    installResourceGetDrawableHook(cl);
                    installChatListViewHook(cl);
                    installChattingListCollector(cl);
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

    /** 方案 C：XML 兜底路径，View.setBackgroundResource 按 resId 替换（覆盖 X2C 关闭场景）。
     *  before 阶段用微信原始气泡资源保存 baseDrawable（setBackgroundResource 参数是 int，
     *  无法替换参数，但保存微信原生气泡 Drawable 后，后续 setBackground/setImageDrawable
     *  可拦截微信的重复覆盖）；after 阶段替换为自定义气泡图。 */
    private static void installBackgroundResourceHook() {
        try {
            Method m = View.class.getMethod("setBackgroundResource", int.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        View v = (View) param.thisObject;
                        try {
                            if (from && sFromBaseDrawable == null) {
                                Drawable base = v.getResources().getDrawable(resId);
                                if (base != null) sFromBaseDrawable = base;
                            } else if (to && sToBaseDrawable == null) {
                                Drawable base = v.getResources().getDrawable(resId);
                                if (base != null) sToBaseDrawable = base;
                            }
                            LogWriter.log(TAG, "setBackgroundResource before resId=" + resId
                                    + " view=" + v.getClass().getName()
                                    + " baseSaved=" + (from ? sFromBaseDrawable != null
                                    : sToBaseDrawable != null));
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        Drawable d = loadDrawable(kind);
                        if (d != null) {
                            View v = (View) param.thisObject;
                            v.setBackground(d);
                            int[] loc = {0, 0};
                            try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                            LogWriter.log(TAG, "setBackgroundResource REPLACE resId=" + resId
                                    + " view=" + v.getClass().getName()
                                    + " xy=(" + loc[0] + "," + loc[1] + ")"
                                    + " w=" + v.getWidth() + " h=" + v.getHeight());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "View.setBackgroundResource hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBackgroundResourceHook err: " + t.getMessage());
        }
    }

    /** 方案 E：View.setBackground / setBackgroundDrawable 拦截。
     *  聊天 item 复用预构建 View 后可能重新设置气泡背景（不走 ke5.a.i/getDrawable），
     *  在设置点用 ke5.a.i 记录的微信原始 Drawable 的 constantState 识别并替换。 */
    private static void installBackgroundHook() {
        try {
            Method setBg = View.class.getMethod("setBackground", Drawable.class);
            Method setBgDrawable = View.class.getMethod("setBackgroundDrawable", Drawable.class);
            XC_MethodHook h = new XC_MethodHook() {
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
                            LogWriter.log(TAG, "setBackground REPLACE kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
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
    private static void installImageViewHook() {
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
     *  v3.0.90：日志 ke5.a.i stack 证实 8.0.78 真实气泡加载路径是 viewitems.mq.b，
     *  但 viewitems.mq.b 的签名并非固定的 (e9,to,gk5.d,Boolean)，因此放宽条件——
     *  hook viewitems.to/viewitems.mq 所有「静态方法名为 b 且首参为 e9」的方法，
     *  after 中从参数推导 isRecv（优先 Boolean 参数，其次 e9.isSend）。 */
    private static void installViewitemsToHook(ClassLoader cl) {
        String[] holderCands = {
                "com.tencent.mm.ui.chatting.viewitems.to",
                "com.tencent.mm.ui.chatting.viewitems.mq"
        };
        int hooked = 0;
        for (String cn : holderCands) {
            try {
                Class<?> toCls = XposedHelpers.findClass(cn, cl);
                for (Method m : toCls.getDeclaredMethods()) {
                    if (!"b".equals(m.getName())) continue;
                    if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 2) continue;
                    if (!"e9".equals(pts[0].getSimpleName())) continue;
                    m.setAccessible(true);
                    final String owner = cn;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                Object holder = param.args.length > 1 ? param.args[1] : null;
                                boolean isRecv = param.args.length > 3
                                        && Boolean.TRUE.equals(param.args[3]);
                                if (param.args.length <= 3 || !(param.args[3] instanceof Boolean)) {
                                    try {
                                        Object msg = param.args[0];
                                        Object isSend = XposedHelpers.callMethod(msg, "z0");
                                        isRecv = !(isSend instanceof Number
                                                && ((Number) isSend).intValue() == 1);
                                    } catch (Throwable ignored) {}
                                }
                                if (holder == null) {
                                    LogWriter.log(TAG, "viewitems.b called isRecv=" + isRecv
                                            + " holder=null");
                                    return;
                                }
                                View bubble = findBubbleView(holder);
                                LogWriter.log(TAG, "viewitems.b called isRecv=" + isRecv
                                        + " holder=" + holder.getClass().getName()
                                        + " bubble=" + (bubble == null ? "null"
                                        : bubble.getClass().getName()));
                                if (bubble == null) return;
                                int kind = isRecv ? KIND_FROM : KIND_TO;
                                Drawable custom = loadDrawable(kind);
                                if (custom != null) {
                                    bubble.setBackground(custom);
                                    LogWriter.log(TAG, "viewitems.b REPLACE isRecv=" + isRecv
                                            + " view=" + bubble.getClass().getName()
                                            + " bg=" + (bubble.getBackground() == null ? "null"
                                            : bubble.getBackground().getClass().getName()));
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "viewitems.b hooked " + owner + "." + m.getName()
                            + Arrays.toString(pts));
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "viewitems.b class " + cn + " err: " + t.getMessage());
            }
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "viewitems.b none found (fallback setBackgroundResource covers)");
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
                                    Drawable custom = loadDrawable(kind);
                                    if (custom != null) {
                                        ((View) v).setBackground(custom);
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

    /** 与 ke5.a.i 记录的微信原始气泡 Drawable 对比 constantState。 */
    private static int matchBaseDrawable(Drawable d) {
        try {
            if (sFromBaseDrawable != null && sameConstant(sFromBaseDrawable, d)) return KIND_FROM;
            if (sToBaseDrawable != null && sameConstant(sToBaseDrawable, d)) return KIND_TO;
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean sameConstant(Drawable a, Drawable b) {
        return a.getConstantState() != null && a.getConstantState().equals(b.getConstantState());
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

    /** 递归遍历 View 树，把背景与微信原生气泡匹配的 View 替换为用户图片。 */
    private static void replaceMatchingBubbles(View v) {
        if (v == null) return;
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
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                replaceMatchingBubbles(g.getChildAt(i));
            }
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
            Class<?> c;
            try {
                c = XposedHelpers.findClass(cn, cl);
            } catch (Throwable t) {
                continue;
            }
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
                        String path = kind == KIND_FROM ? sFromPath : sToPath;
                        LogWriter.log(TAG, "resolver hit value=" + value + " resId=" + resId
                                + " enabled=" + sEnabled
                                + " path=" + (path != null && !path.isEmpty() ? "set" : "EMPTY"));
                        if (!sEnabled) return;
                        Drawable d = loadDrawable(kind);
                        if (d != null) {
                            LogWriter.log(TAG, "resolver REPLACE " + value + " resId=" + resId
                                    + " kind=" + kind);
                            param.setResult(d);
                            View v = param.args.length > 1 && param.args[1] instanceof View
                                    ? (View) param.args[1] : null;
                            if (v != null) {
                                v.setBackground(d);
                                LogWriter.log(TAG, "resolver setBackground view="
                                        + v.getClass().getName());
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