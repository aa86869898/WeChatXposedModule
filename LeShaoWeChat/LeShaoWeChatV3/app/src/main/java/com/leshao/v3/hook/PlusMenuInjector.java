package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.LogWriter;
import com.leshao.v3.ContextManager;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.utils.WmPrefs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信主界面右上角「+」菜单注入 —— 一键免打扰 / 一键解除免打扰。
 *
 * <p>依据《微信右上角加号注入WeChat_PlusMenu_Injection.md》方案 D：
 * 不修改微信任何数据结构，只在 {@code rg}(BaseAdapter, 菜单适配器) 末尾追加两项自绘 item，
 * 并在 {@code wg}(菜单控制器).onItemClick 的 Before 阶段消费掉这两个 position 的点击。</p>
 *
 * <p>动态定位：先用高辨识度锚点字符串经 DexKitHelper 找到控制器类 {@code com.tencent.mm.ui.wg}，
 * 再从控制器字段反推 BaseAdapter 子类（{@code com.tencent.mm.ui.rg}）与 SparseArray 字段，
 * 全程不硬编码混淆类名，换版本时只需更新 {@link #CONTROLLER_ANCHOR}。</p>
 */
public final class PlusMenuInjector {

    private static final String TAG = "PlusMenuInjector";

    /** 控制器 onItemClick 唯一命中的高辨识度锚点（见文档 §5.1）。 */
    private static final String CONTROLLER_ANCHOR = "PlusSubMenuHelper";

    /** 兜底锚点：锚点字符串扫描失败时直接尝试的控制器类名（文档 §6.3 锚点表）。 */
    private static final String[] CONTROLLER_FALLBACK = {
            "com.tencent.mm.ui.wg"
    };

    /** 追加项在 adapter 中的 position 锚点：真实菜单数量 + 偏移。 */
    private static final String KEY_ORIG_COUNT = "leshao_plus_orig_count";

    /** 追加项文案（顺序即 position 增量）。 */
    private static final String TITLE_MUTE = "一键免打扰";
    private static final String TITLE_UNMUTE = "一键解除免打扰";
    private static final String[] TITLES = { TITLE_MUTE, TITLE_UNMUTE };

    private static volatile boolean sInstalled;
    private static volatile boolean sResolving;

    private static Class<?> sControllerClass;
    private static Class<?> sAdapterClass;
    private static String sAdapterFieldName = "r";
    private static String sSparseArrayFieldName = "s";

    // v3.0.126：视觉对齐微信原生「+」菜单项。首次拿到任意原生 item view 后缓存其
    // 文本颜色/字号/图标色，套用到自绘项，保证与微信原生一致（深浅色主题自适应）。
    private static volatile int sNativeTextColor = 0;
    private static volatile int sNativeIconColor = 0;
    private static volatile float sNativeTextSizePx = 0f;
    private static volatile boolean sTemplateLogged;

    private static final Handler sH = new Handler(Looper.getMainLooper());

    private PlusMenuInjector() {}

    // ================================================================
    // 入口
    // ================================================================

    public static boolean isInstalled() { return sInstalled; }

    public static void hook(final ClassLoader cl) {
        if (sInstalled || sResolving) return;
        sResolving = true;
        WmPrefs.ensureInit();

        // DexKit 全量搜索不可在主线程执行（冷启动阻塞），后台解析。
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 8 && !sInstalled; attempt++) {
                try {
                    ClassLoader useCL = pickRealClassLoader(cl, attempt);
                    if (resolve(useCL)) {
                        install(useCL);
                        sInstalled = true;
                        LogWriter.log(TAG, "installed via "
                                + (useCL == null ? "null" : useCL.getClass().getSimpleName())
                                + " controller=" + sControllerClass.getName()
                                + " adapter=" + sAdapterClass.getName());
                        return;
                    }
                } catch (Throwable e) {
                    LogWriter.log(TAG, "resolve attempt " + attempt + " err: " + e);
                }
                try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
            }
            if (!sInstalled) LogWriter.log(TAG, "unsupported version, plus menu injection off");
        }, "ls-plusmenu-resolve");
        t.setDaemon(true);
        t.start();
    }

    private static ClassLoader pickRealClassLoader(ClassLoader base, int attempt) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(base);
            if (tk != null) return tk;
        } catch (Throwable ignored) {}
        try {
            ClassLoader cm = ContextManager.getTinkerClassLoader();
            if (cm != null) return cm;
        } catch (Throwable ignored) {}
        if (attempt >= 2) {
            try {
                ClassLoader cm = ContextManager.getClassLoader();
                if (cm != null) return cm;
            } catch (Throwable ignored) {}
        }
        return base;
    }

    // ================================================================
    // 动态定位（文档 §5.1）
    // ================================================================

    private static boolean resolve(ClassLoader cl) {
        if (cl == null) return false;
        Class<?> controller = null;

        // 1) DexKit 锚点字符串 → 候选类，取 implements OnItemClickListener 且含 BaseAdapter 字段者
        try {
            List<String> candidates = DexKitHelper.findClassesByString(cl, CONTROLLER_ANCHOR);
            LogWriter.log(TAG, "anchor candidates(" + (candidates == null ? 0 : candidates.size()) + ")="
                    + (candidates == null ? "null" : candidates));
            if (candidates != null) {
                Class<?> relax = null;
                for (String cn : candidates) {
                    Class<?> c = tryFindClass(cn, cl);
                    if (c == null) { LogWriter.log(TAG, "  cand " + cn + " -> load fail"); continue; }
                    boolean shape = isControllerShape(c);
                    boolean loose = hasSparseAndAdapter(c);
                    LogWriter.log(TAG, "  cand " + cn + " -> shape=" + shape + " loose=" + loose
                            + " itf=" + java.util.Arrays.toString(c.getInterfaces())
                            + " fields=" + fieldTypes(c));
                    if (shape) { controller = c; break; }
                    if (relax == null && loose) relax = c; // 次选：同时持有 SparseArray + BaseAdapter 字段
                }
                if (controller == null && relax != null) {
                    LogWriter.log(TAG, "使用宽松判据命中控制器 " + relax.getName());
                    controller = relax;
                }
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "anchor scan err: " + e);
        }

        // 2) 兜底：锚点表类名
        if (controller == null) {
            for (String cn : CONTROLLER_FALLBACK) {
                Class<?> c = tryFindClass(cn, cl);
                LogWriter.log(TAG, "  fallback " + cn + " -> " + (c == null ? "load fail" : "shape=" + isControllerShape(c)));
                if (isControllerShape(c)) { controller = c; break; }
            }
        }
        if (controller == null) return false;

        // 3) 控制器字段（含父类）反推 adapter 类 + SparseArray 字段
        Class<?> adapter = null;
        String adapterField = null;
        String sparseField = null;
        for (Class<?> k = controller; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (adapter == null
                        && BaseAdapter.class.isAssignableFrom(ft)
                        && !android.widget.ArrayAdapter.class.equals(ft)) {
                    adapter = ft;
                    adapterField = f.getName();
                }
                if (sparseField == null
                        && android.util.SparseArray.class.isAssignableFrom(ft)) {
                    sparseField = f.getName();
                }
            }
        }
        if (adapter == null) return false;

        sControllerClass = controller;
        sAdapterClass = adapter;
        sAdapterFieldName = adapterField != null ? adapterField : "r";
        sSparseArrayFieldName = sparseField != null ? sparseField : "s";
        return true;
    }

    /** 诊断用：列出类自身与父类的字段类型。 */
    private static String fieldTypes(Class<?> c) {
        StringBuilder sb = new StringBuilder("[");
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                sb.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(',');
            }
            if (sb.length() > 240) break;
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append(']').toString();
    }

    /** 是否（沿继承链）实现了 OnItemClickListener。 */
    private static boolean implementsOnItemClick(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (android.widget.AdapterView.OnItemClickListener.class.equals(i)) return true;
            }
        }
        return false;
    }

    /** 宽松判据：同时持有 SparseArray 字段与 BaseAdapter 子类字段（沿继承链）。 */
    private static boolean hasSparseAndAdapter(Class<?> c) {
        if (c == null) return false;
        boolean sparse = false, adapter = false;
        try {
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (android.util.SparseArray.class.isAssignableFrom(ft)) sparse = true;
                    if (BaseAdapter.class.isAssignableFrom(ft)
                            && !android.widget.ArrayAdapter.class.equals(ft)) adapter = true;
                }
            }
        } catch (Throwable ignored) {}
        return sparse && adapter;
    }

    /** 控制器结构特征：OnItemClickListener 且存在 BaseAdapter 子类字段（均沿继承链查找）。 */
    private static boolean isControllerShape(Class<?> c) {
        if (c == null) return false;
        try {
            if (!implementsOnItemClick(c)) return false;
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (BaseAdapter.class.isAssignableFrom(f.getType())
                            && !android.widget.ArrayAdapter.class.equals(f.getType())) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Class<?> tryFindClass(String name, ClassLoader cl) {
        if (name == null) return null;
        try { return XposedHelpers.findClass(name, cl); } catch (Throwable t) { return null; }
    }

    // ================================================================
    // 安装 Hook（文档 §5.2 / §5.3）
    // ================================================================

    private static void install(final ClassLoader cl) {
        // 注意：本环境 R8 改写 XposedHelpers 的 varargs findAndHookMethod，
        // 统一走 getDeclaredMethod + XposedBridge.hookMethod（见 HookUtil 注释）。

        // getCount → 真实数量 + TITLES.length
        hookMethod(sAdapterClass, "getCount", new Class<?>[0], new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object r = param.getResult();
                int orig = (r instanceof Integer) ? (Integer) r : 0;
                XposedHelpers.setAdditionalInstanceField(param.thisObject, KEY_ORIG_COUNT, orig);
                param.setResult(orig + TITLES.length);
            }
        });

        // getView → position >= 真实数量时返回自绘 item
        hookMethod(sAdapterClass, "getView",
                new Class<?>[]{ int.class, View.class, ViewGroup.class }, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int position = (int) param.args[0];
                        int orig = readOrigCount(param.thisObject);
                        // orig<=0 视为未知（getCount 尚未写入），一律放行，避免顶替微信原生项。
                        if (orig <= 0 || position < orig) return;

                        int idx = position - orig;
                        if (idx < 0 || idx >= TITLES.length) return;

                        ViewGroup parent = (ViewGroup) param.args[2];
                        Context ctx = parent != null ? parent.getContext()
                                : (Context) XposedHelpers.getObjectField(param.thisObject, "t");
                        param.setResult(buildItem(ctx, TITLES[idx], idx == 0));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 缓存原生 item 作为视觉模板：读取其文本颜色/字号/图标色，
                        // 使自绘项与微信原生完全一致（含深色/浅色主题）。
                        try {
                            int position = (int) param.args[0];
                            int orig = readOrigCount(param.thisObject);
                            if (orig > 0 && position >= orig) return; // 只采样原生项
                            Object r = param.getResult();
                            if (r instanceof View) captureTemplate((View) r);
                        } catch (Throwable ignored) {}
                    }
                });

        // onItemClick Before → 消费追加项，关弹窗，执行免打扰
        hookMethod(sControllerClass, "onItemClick",
                new Class<?>[]{ android.widget.AdapterView.class, View.class, int.class, long.class },
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int position = (int) param.args[2];
                        int orig = resolveRealCount(param.thisObject);
                        // orig<=0 视为未知，一律放行，避免把微信原生项当成追加项消费。
                        if (orig <= 0 || position < orig) return;

                        int idx = position - orig;
                        if (idx < 0 || idx >= TITLES.length) return;

                        // 1) 首行消费，阻止微信执行（否则 s.get(pos)=null → NPE）
                        param.setResult(null);
                        // 2) 关闭菜单（父类 hd.a()，与原生行为一致）
                        dismiss(param.thisObject);
                        // 3) 执行批量免打扰
                        final boolean mute = (idx == 0);
                        final Activity host = hostActivity(param.args[0]);
                        runMute(host, mute);
                    }
                });
    }

    /** R8 安全的 hook：沿继承链找方法后 XposedBridge.hookMethod。 */
    private static void hookMethod(Class<?> c, String name, Class<?>[] params, XC_MethodHook hook) {
        if (c == null) return;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, params);
                m.setAccessible(true);
                XposedBridge.hookMethod(m, hook);
                LogWriter.log(TAG, "hooked " + c.getSimpleName() + "#" + name);
                return;
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "hook miss " + c.getName() + "#" + name);
    }

    private static int readOrigCount(Object adapter) {
        try {
            Object v = XposedHelpers.getAdditionalInstanceField(adapter, KEY_ORIG_COUNT);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 计算真实菜单数量：优先 adapter 缓存，其次反射 SparseArray.size()。 */
    private static int resolveRealCount(Object controller) {
        try {
            Object adapter = XposedHelpers.getObjectField(controller, sAdapterFieldName);
            if (adapter != null) {
                int c = readOrigCount(adapter);
                if (c > 0) return c;
            }
        } catch (Throwable ignored) {}
        try {
            Object sp = XposedHelpers.getObjectField(controller, sSparseArrayFieldName);
            if (sp instanceof android.util.SparseArray) {
                return ((android.util.SparseArray<?>) sp).size();
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 关闭微信「+」菜单：调用父类 hd 的 a()（见文档 §2.2 / §5.3）。 */
    private static void dismiss(Object controller) {
        for (Class<?> k = controller.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod("a");
                m.setAccessible(true);
                m.invoke(controller);
                return;
            } catch (Throwable ignored) {}
        }
        try { XposedHelpers.callMethod(controller, "onDismiss"); } catch (Throwable ignored) {}
    }

    private static Activity hostActivity(Object adapterView) {
        try {
            if (adapterView instanceof View) {
                Context c = ((View) adapterView).getContext();
                while (c instanceof android.content.ContextWrapper) {
                    if (c instanceof Activity) return (Activity) c;
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ================================================================
    // 免打扰执行（复用 GroupMuteHook）
    // ================================================================

    private static void runMute(final Activity host, final boolean mute) {
        new Thread(() -> {
            try {
                final GroupMuteHook.MuteResult r = GroupMuteHook.muteAllGroups(mute);
                final String msg;
                if (!r.ready) {
                    msg = "免打扰逻辑初始化失败，请稍后重试";
                } else if (r.total <= 0) {
                    msg = "未找到可操作的群聊";
                } else {
                    msg = (mute ? "已免打扰 " : "已解除免打扰 ") + r.ok + "/" + r.total + " 个群";
                }
                sH.post(() -> {
                    try {
                        Context ctx = host != null ? host.getApplicationContext()
                                : ContextManager.getAppContext();
                        if (ctx != null) {
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
                        }
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable e) {
                LogWriter.log(TAG, "runMute err: " + e);
            }
        }, "leshao-plusmenu-mute").start();
    }

    // ================================================================
    // 视觉对齐：从原生 item 采样颜色/字号
    // ================================================================

    /** 采样微信原生菜单项的文本颜色、字号与图标色，供自绘项复用。 */
    private static void captureTemplate(View nativeItem) {
        if (nativeItem == null) return;
        try {
            TextView tv = findFirstTextView(nativeItem);
            if (tv != null) {
                int c = tv.getCurrentTextColor();
                if (c != 0) sNativeTextColor = c;
                float px = tv.getTextSize();
                if (px > 0) sNativeTextSizePx = px;
            }
            int iconColor = 0;
            ImageView iv = findFirstImageView(nativeItem);
            if (iv != null) {
                try {
                    android.content.res.ColorStateList tint = iv.getImageTintList();
                    if (tint != null) iconColor = tint.getDefaultColor();
                } catch (Throwable ignored) {}
                if (iconColor == 0) iconColor = sampleDrawableColor(iv.getDrawable());
            }
            if (iconColor == 0 && tv != null) iconColor = tv.getCurrentTextColor();
            if (iconColor != 0) sNativeIconColor = iconColor;
            if (!sTemplateLogged) {
                sTemplateLogged = true;
                LogWriter.log(TAG, "native template: textColor=#" + Integer.toHexString(sNativeTextColor)
                        + " textSizePx=" + sNativeTextSizePx + " iconColor=#" + Integer.toHexString(sNativeIconColor)
                        + " item=" + nativeItem.getClass().getName());
            }
        } catch (Throwable ignored) {}
    }

    private static TextView findFirstTextView(View v) {
        if (v == null) return null;
        if (v instanceof TextView) return (TextView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findFirstTextView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private static ImageView findFirstImageView(View v) {
        if (v == null) return null;
        if (v instanceof ImageView) return (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView t = findFirstImageView(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    /** 从 Drawable 采样主色（对单色图标取不透明像素均值），用于无 tint 的场景。 */
    private static int sampleDrawableColor(Drawable d) {
        if (d == null) return 0;
        try {
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 48;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 48;
            if (w > 128) w = 128;
            if (h > 128) h = 128;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int y = 0; y < h; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    int px = bmp.getPixel(x, y);
                    int a = (px >>> 24) & 0xff;
                    if (a > 200) {
                        r += (px >> 16) & 0xff;
                        g += (px >> 8) & 0xff;
                        b += px & 0xff;
                        n++;
                    }
                }
            }
            if (n > 0) return 0xff000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        } catch (Throwable ignored) {}
        return 0;
    }

    // ================================================================
    // 自绘菜单项（文档 §5.2）
    // ================================================================

    private static View buildItem(Context ctx, String title, boolean mute) {
        float d = ctx.getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setLayoutParams(new AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, (int) (56 * d)));
        int pad = (int) (16 * d);
        root.setPadding(pad, 0, pad, 0);
        root.setBackgroundColor(Color.TRANSPARENT);

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(new BitmapDrawable(ctx.getResources(), drawIcon(mute)));
        icon.setLayoutParams(new LinearLayout.LayoutParams((int) (24 * d), (int) (24 * d)));
        root.addView(icon);

        TextView tv = new TextView(ctx);
        tv.setText(title);
        // 字号/颜色优先对齐微信原生 item 采样值，未采到再回退合理默认。
        if (sNativeTextSizePx > 0) {
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sNativeTextSizePx);
        } else {
            tv.setTextSize(15);
        }
        tv.setTextColor(sNativeTextColor != 0 ? sNativeTextColor : Color.parseColor("#1A1A1A"));
        tv.setTypeface(Typeface.DEFAULT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart((int) (16 * d));
        tv.setLayoutParams(tp);
        root.addView(tv);

        return root;
    }

    /** 程序化绘制图标（不依赖模块资源 id，避免 resources.arsc 缺失导致崩溃）。
     *  颜色与微信原生菜单项一致（采样自原生 item；未采到则回退模块主色）。 */
    private static Bitmap drawIcon(boolean bellOn) {
        int size = 96;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = sNativeIconColor != 0 ? sNativeIconColor : AppColors.primary();
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(size * 0.07f);

        // 铃铛：外弧 + 底座
        RectF body = new RectF(size * 0.28f, size * 0.22f, size * 0.72f, size * 0.66f);
        cv.drawArc(body, 180f, 180f, false, p);
        cv.drawLine(size * 0.28f, size * 0.44f, size * 0.26f, size * 0.72f, p);
        cv.drawLine(size * 0.72f, size * 0.44f, size * 0.74f, size * 0.72f, p);
        cv.drawLine(size * 0.24f, size * 0.72f, size * 0.76f, size * 0.72f, p);

        p.setStyle(Paint.Style.FILL);
        cv.drawCircle(size * 0.5f, size * 0.82f, size * 0.06f, p);

        if (bellOn) {
            // 免打扰：斜杠（与铃铛同色，保持整体图标颜色与微信原生一致）
            p.setColor(color);
            p.setStrokeWidth(size * 0.09f);
            p.setStyle(Paint.Style.STROKE);
            cv.drawLine(size * 0.2f, size * 0.2f, size * 0.8f, size * 0.8f, p);
        }
        return bmp;
    }
}
