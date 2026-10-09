package com.leshao.v3.hook;

import android.app.Activity;
import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 去你妈的面对面扫码 —— 按《微信相册二维码模拟真实面对面扫码_逆向分析.md》实现。
 *
 * <p>微信「扫一扫 → 从相册选择二维码」的识别结果交付差异只有
 * {@code result_image_source}（1=真实相机 / 2=相册）：真实扫码走 {@code BaseScanUI.h7}
 * （固定 result_image_source=1 + setResult + PublishScanCodeResultEvent），相册路径走
 * {@code i7/j7}（内部结果页，不返回调用方）。因此「模拟真实面对面扫码」= 让相册结果走 h7
 * 真实交付即可。</p>
 *
 * <p>h7 只在 {@code BaseScanUI.M}（= intent 的 {@code key_set_result_after_scan}）为 true 时
 * 才执行 setResult 交付；相册入口由 {@code BaseScanUI.X}（允许相册）+ 相册按钮可见性
 * （{@code r7()(initGalleryButton)}，P=false 显示 / P=true 隐藏）控制。本模块在开关开启时
 * <b>仅针对相册选二维码流程</b>生效，不影响摄像头扫码对返回值的既有行为：</p>
 * <ul>
 *   <li>① hook {@code BaseScanUI.onActivityResult} requestCode==4660（相册返回）：
 *       after 保证 {@code M=true}、{@code X=true} → 相册识别结果走 h7 真实交付；</li>
 *   <li>② hook {@code BaseScanUI.r7}（initGalleryButton）after：置 {@code P=false}
 *       强制显示相册按钮（解锁被禁用的相册入口）。</li>
 * </ul>
 *
 * <p>所有操作 try/catch，类名/字段按文档当前版本混淆名并配 DexKit 字符串锚点动态定位，
 * miss 只 Log 不注入。</p>
 */
public final class FaceScanHook {

    public static final String TAG = "FaceScan";
    public static final String K_ENABLED = "ls_face_scan_fake";

    // ===== 字符串锚点 + 当前版本兜底类名（扫码文档 §3）=====
    private static final String S_KEY_RESULT = "key_set_result_after_scan";
    private static final String S_SCAN_UI = "MicroMsg.ScanUI";
    private static final String S_KEY_EXTRA = "key_scan_result";
    private static final String S_SELECT_GALLERY = "select: [%s]";
    private static final String C_BASE_SCAN_UI = "com.tencent.mm.plugin.scanner.ui.BaseScanUI";

    // v3.0.210：《微信相册二维码模拟真实相机扫码_二次复审.md》决定性字段。
    // qbar_string_scan_source：0=相机(Bundle 默认) / 1=相册(j7 硬编码) / 2|4=长按识别。
    // result_image_source：相册路径 j7 设 2，但 i7 会覆盖回 1 —— 非决定性。
    private static final String K_QBAR_SOURCE = "qbar_string_scan_source";
    private static final String K_RESULT_IMG_SRC = "result_image_source";
    private static final String S_QRCODE_HANDLER = "MicroMsg.QRCodeHandler";
    private static final String S_QBAR_STRING_HANDLER = "MicroMsg.QBarStringHandler";
    private static final String S_DEAL_QBAR = "[handleCode-dealQBarString]";

    private static final String EXTRA_SET_RESULT = "key_set_result_after_scan";
    private static final int REQ_GALLERY = 4660;   // 8192 相册二维码返回码

    private static volatile boolean sEnabled = false;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;

    private FaceScanHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            return sp != null && sp.getBoolean(K_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            ContextManager.getPrefs().edit().putBoolean(K_ENABLED, on).apply();
        } catch (Throwable ignored) {}
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    // ---------------- Hook 安装 ----------------

    public static void hook(final ClassLoader cl) {
        sCl = cl;
        try {
            sEnabled = isEnabled();
        } catch (Throwable ignored) {}
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    installHooks(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "hooks installed attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "install attempt " + attempt + " failed: " + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-face-scan-hook");
        t.setDaemon(true);
        t.start();
    }

    private static void installHooks(ClassLoader cl) throws Throwable {
        String uiClass = resolveUiClass(cl);
        if (uiClass == null) uiClass = C_BASE_SCAN_UI;
        // ① onActivityResult(4660 相册返回) after：M=true + X=true → 相册结果走 h7 真实交付
        hookGalleryResult(cl, uiClass);
        // ② initGalleryButton（r7）after：P=false 强制显示相册按钮
        hookInitGallery(cl, uiClass);
        // v3.0.210（二次复审）：相册走 i7/j7 内部结果页时，j7 硬编码 qbar_string_scan_source=1(相册)
        // → f74.c.b → v74.v.g(source=1, getA8KeyScene=34) → 业务判定「非相机扫码」拒绝。
        // 三层保险：j7 返回 Bundle 改 1→0 / f74.c.b 入口强改 / v74.v.g 参数 source=0 scene=4。
        hookJ7Bundle(cl, uiClass);
        hookF74Handler(cl);
        hookV74Handler(cl);
        LogWriter.log(TAG, "installHooks done ui=" + uiClass);
    }

    /** 字符串锚点定位 BaseScanUI（后台线程，DexKit 缓存命中直接取）。 */
    private static String resolveUiClass(ClassLoader cl) {
        for (String kw : new String[]{S_KEY_RESULT, S_SCAN_UI, S_KEY_EXTRA, S_SELECT_GALLERY}) {
            try {
                List<String> cs = DexKitHelper.findClassesByString(cl, kw);
                if (cs != null) {
                    for (String c : cs) {
                        if (c.contains("scanner") && c.contains("ScanUI")) return c;
                    }
                    for (String c : cs) {
                        if (c.contains("ScanUI")) return c;
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "resolve '" + kw + "' err: " + t.getMessage());
            }
        }
        return C_BASE_SCAN_UI;
    }

    /** hook BaseScanUI.onActivityResult：requestCode==4660 相册返回 RESULT_OK 后，
     *  仅在调用方明确要求 setResult 时才置 M=true（h7 真实交付开关），否则保持微信原生行为。
     *
     *  <p>关键修复（v3.0.207）：微信「主界面扫一扫」这类入口的 intent <b>不携带</b>
     *  {@code key_set_result_after_scan}，其调用方 LauncherUI 不消费 BaseScanUI 的
     *  setResult —— 若我们不区分强制 M=true，相册识别结果会走 h7 直接 setResult+finish
     *  回到启动方，表现为「未扫到、直接返回微信主页」。因此改为：</p>
     *  <ol>
     *    <li>调用方想要结果（intent extra key_set_result_after_scan==true）→ 置 M=true
     *        让相册识别走 h7（result_image_source=1 真实交付），即「模拟真实面对面扫码」；</li>
     *    <li>调用方未要求结果（主界面扫一扫等）→ 不动 M，微信原生相册识别走 i7/j7
     *        内部结果页正常展示扫码内容。</li>
     *  </ol> */
    private static void hookGalleryResult(ClassLoader cl, String uiClass) {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!sEnabled || !(p.thisObject instanceof Activity)) return;
                try {
                    if (p.args.length < 3) return;
                    int req = ((Number) p.args[0]).intValue();
                    int result = ((Number) p.args[1]).intValue();
                    if (req != REQ_GALLERY || result != Activity.RESULT_OK) return;
                    Object ui = p.thisObject;
                    // X：允许相册选图（文档 §1.2 if(baseScanUI.X) 才走 4660）——恒开，仅解锁入口
                    setBoolField(ui, "X", true);
                    boolean wantResult = false;
                    try {
                        Activity act = (Activity) ui;
                        android.content.Intent in = act.getIntent();
                        wantResult = in != null
                                && in.getBooleanExtra(EXTRA_SET_RESULT, false);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "read key_set_result_after_scan err: " + t.getMessage());
                    }
                    if (wantResult) {
                        // 调用方要求 setResult：强制 M=true -> 相册识别走 h7 真实交付
                        setBoolField(ui, "M", true);
                        LogWriter.log(TAG, "caller wants setResult: M=true -> real h7 delivery");
                    } else {
                        // 主界面扫一扫等入口：保持原生 M，走 i7/j7 内部结果页
                        LogWriter.log(TAG, "no setResult caller: keep native M, internal result page");
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "galleryResult err: " + t.getMessage());
                }
            }
        };
        hookMethodByName(cl, uiClass, "onActivityResult", hook, 3);
    }

    /** initGalleryButton（r7）after：P=false 强制显示相册按钮（文档 §2 方案 D），
     *  并同时置 X=true（允许相册选图）—— 两者配套，否则按钮可见但点击被 X=false 拦在相册分支外。 */
    private static void hookInitGallery(ClassLoader cl, String uiClass) {
        XC_MethodHook after = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!sEnabled) return;
                try {
                    // P=false → galleryButton.setVisibility(0)；P=true → 隐藏
                    setBoolField(p.thisObject, "P", false);
                    // X=true → BaseScanUI$18.onClick 的 if(baseScanUI.X) 放行相册分支(4660)
                    setBoolField(p.thisObject, "X", true);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "set P/X err: " + t.getMessage());
                }
            }
        };
        hookMethodByName(cl, uiClass, "r7", after, -1);
    }

    /** v3.0.210：方案① —— Hook BaseScanUI.j7（static，返回 Bundle），after 改返回 Bundle。
     *  <p>j7 相册出口硬编码 {@code qbar_string_scan_source=1}（相册），此处改回 0（相机）。
     *  {@code result_image_source} 2→1 属双保险（虽 i7 会覆盖回 1）。一处修改即让
     *  f74.c → v74.v 全链路 source/getA8KeyScene/this.p 自动变相机值。</p>
     *  <p>定位：BaseScanUI 类内 findMethod 含 {@code qbar_string_scan_source} 字符串的方法，
     *  返回 Bundle 即 j7（签名 j7(BaseScanUI,String,h3,WxQBarResult,int,ReportInfo)）。</p> */
    private static void hookJ7Bundle(ClassLoader cl, String uiClass) {
        try {
            for (Class<?> c : HookUtil.loadClasses(cl, uiClass)) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getReturnType() != android.os.Bundle.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                            if (!sEnabled) return;
                            try {
                                android.os.Bundle b = (android.os.Bundle) p.getResult();
                                if (b == null) return;
                                boolean wasAlbum = b.getInt(K_QBAR_SOURCE, 0) != 0;
                                b.putInt(K_QBAR_SOURCE, 0);      // 1→0 相册→相机
                                b.putInt(K_RESULT_IMG_SRC, 1);   // 2→1（双保险）
                                if (wasAlbum) {
                                    LogWriter.log(TAG, "j7 Bundle qbar_source->0 img_src->1");
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "j7 bundle err: " + t.getMessage());
                            }
                        }
                    });
                    LogWriter.log(TAG, "hooked j7 bundle " + c.getName() + "." + m.getName());
                    return;
                }
            }
            // 兜底：BaseScanUI 内没找到返回 Bundle 的方法 → 试 findMethodsByString 定位
            List<String> sigs = DexKitHelper.findMethodsByString(cl, uiClass, K_QBAR_SOURCE);
            for (String sig : sigs) {
                LogWriter.log(TAG, "j7 anchor sig: " + sig);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookJ7Bundle err: " + t.getMessage());
        }
    }

    /** v3.0.210：方案② —— Hook f74.c.b(long, Bundle)（QRCodeHandler 内部处理），
     *  before 读 Bundle 前强改 qbar_string_scan_source=0（相机）。兜底链：相册 Bundle
     *  走到这里时 i2 即相册来源，before 改回相机值后下游全自动。
     *
     *  <p>v3.0.211：旧实现硬签名字 b(long, Bundle) 未命中（日志 f74.c.b none）。
     *  改为 findClassesByString(MicroMsg.QRCodeHandler) 定位类后，对该类<b>所有方法</b>
     *  before 识别「含 Bundle 参数且 Bundle 内含 qbar_string_scan_source」即改——
     *  不依赖方法名/签名，内容级定位更抗混淆。</p> */
    private static void hookF74Handler(ClassLoader cl) {
        List<String> classes = null;
        try {
            classes = DexKitHelper.findClassesByString(cl, S_QRCODE_HANDLER);
        } catch (Throwable t) {
            LogWriter.log(TAG, "f74 findClasses err: " + t.getMessage());
        }
        if (classes == null || classes.isEmpty()) {
            LogWriter.log(TAG, "f74.c.b none (QRCodeHandler anchor empty)");
            return;
        }
        boolean hooked = false;
        for (String cn : classes) {
            try {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        boolean hasBundle = false;
                        for (Class<?> p : pts) {
                            if (p == android.os.Bundle.class) { hasBundle = true; break; }
                        }
                        if (!hasBundle) continue;
                        // 方法至少接收 Bundle 即 hook（before 内按内容识别目标 Bundle）
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                if (!sEnabled) return;
                                try {
                                    for (Object a : p.args) {
                                        if (a instanceof android.os.Bundle) {
                                            android.os.Bundle b = (android.os.Bundle) a;
                                            if (!b.containsKey(K_QBAR_SOURCE)) continue;
                                            boolean wasAlbum = b.getInt(K_QBAR_SOURCE, 0) != 0;
                                            b.putInt(K_QBAR_SOURCE, 0);
                                            b.putInt(K_RESULT_IMG_SRC, 1);
                                            if (wasAlbum) LogWriter.log(TAG, "f74 Handler source->0 (Bundle has qbar)");
                                        }
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "f74 before err: " + t.getMessage());
                                }
                            }
                        });
                        LogWriter.log(TAG, "hooked f74 handler(bundle-arg) "
                                + c.getName() + "." + m.getName());
                        hooked = true;
                    }
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "f74 class err: " + t.getMessage());
            }
        }
        if (!hooked) LogWriter.log(TAG, "f74.c.b none (no Bundle-arg method under QRCodeHandler)");
    }

    /** v3.0.210：方案③ —— Hook v74.v.g(...)（QBarStringHandler，16 参），before 改参数。
     *  <p>签名：g(Activity, String, int scanUIScene, int source, int getA8KeyScene, String codeType, ...)
     *  → args[3]=source、args[4]=getA8KeyScene。改为 0/4（相机）。最深一层，兜底前两层未命中。</p>
     *  <p>定位：类含 {@code MicroMsg.QBarStringHandler} / {@code [handleCode-dealQBarString]} 锚点，
     *  方法名 g 且参数 ≥5（含 Activity, String, int, int, int 头 5 个）。</p> */
    private static void hookV74Handler(ClassLoader cl) {
        try {
            List<String> classes = DexKitHelper.findClassesByString(cl, S_QBAR_STRING_HANDLER);
            if (classes == null || classes.isEmpty()) {
                classes = DexKitHelper.findClassesByString(cl, S_DEAL_QBAR);
            }
            boolean hooked = false;
            for (String cn : classes) {
                for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"g".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 5) continue;
                        if (pts[0] != android.app.Activity.class
                                || pts[1] != String.class
                                || pts[2] != int.class
                                || pts[3] != int.class
                                || pts[4] != int.class) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                if (!sEnabled) return;
                                try {
                                    // ★ 群二维码 URL 不修改参数：自动加群依赖微信原生
                                    //   source=4(长按识别)/scene=37(加群) 语义，不能改相机值
                                    Object code = p.args[1];
                                    if (code instanceof String) {
                                        String cs = (String) code;
                                        if (cs.startsWith("https://weixin.qq.com/g/")
                                                || cs.startsWith("weixin://qr/")
                                                || cs.startsWith("wxp://")) {
                                            return;
                                        }
                                    }
                                    int src = ((Number) p.args[3]).intValue();
                                    if (src != 0) {
                                        p.args[3] = 0;   // source=0（相机）
                                        LogWriter.log(TAG, "v74.v.g source " + src + "->0");
                                    }
                                    int scene = ((Number) p.args[4]).intValue();
                                    if (scene != 4) {
                                        p.args[4] = 4;   // getA8KeyScene=4（相机）
                                        LogWriter.log(TAG, "v74.v.g scene " + scene + "->4");
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "v74.v.g err: " + t.getMessage());
                                }
                            }
                        });
                        hooked = true;
                        LogWriter.log(TAG, "hooked v74.v.g " + c.getName() + "." + m.getName());
                    }
                }
            }
            if (!hooked) LogWriter.log(TAG, "v74.v.g none (QBarStringHandler anchor)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookV74Handler err: " + t.getMessage());
        }
    }

    // ---------------- 工具 ----------------

    /** 只按文档给出的字段名（M / X / P）精确设置；找不到即放弃，绝不泛扫误改其它布尔字段。 */
    private static void setBoolField(Object o, String name, boolean val) {
        if (o == null) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.setBoolean(o, val);
                LogWriter.log(TAG, "set " + name + "=" + val + " on " + c.getSimpleName());
                return;
            } catch (Throwable ignored) {}
        }
    }

    private static void hookMethodByName(ClassLoader cl, String clsName, String methodName,
                                         XC_MethodHook hook, int paramCount) {
        try {
            for (Class<?> c : HookUtil.loadClasses(cl, clsName)) {
                boolean any = false;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    if (paramCount >= 0 && m.getParameterTypes().length != paramCount) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, hook);
                    any = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + methodName
                            + " pc=" + m.getParameterTypes().length);
                }
                if (!any) {
                    for (Method m : c.getMethods()) {
                        if (!m.getName().equals(methodName)) continue;
                        if (paramCount >= 0 && m.getParameterTypes().length != paramCount) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, hook);
                        LogWriter.log(TAG, "hooked(inherited) " + c.getName() + "." + methodName);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookMethodByName " + clsName + "." + methodName
                    + " err: " + t.getMessage());
        }
    }
}