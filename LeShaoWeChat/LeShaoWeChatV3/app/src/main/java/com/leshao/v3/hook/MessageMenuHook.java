package com.leshao.v3.hook;

import android.view.MenuItem;

import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.utils.WmPrefs;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 聊天消息长按菜单净化（v1110 新增）。
 *
 * 依据《WeChat_LongPressMenu_DeepDive.md》第 8 章：
 *   A5 锚点 "OnCreateContextMMMenux" → 菜单构建方法 o0#a(MMMenu, View, ContextMenu$ContextMenuInfo)。
 * 在构建 after（微信先 clear 再构建，必须 after）遍历 MMMenu 的 List 字段，
 * 按 MenuItem.getTitle() 移除被勾选的原生项，同时把出现过的标题自动收录进配置页。
 *
 * 不写死混淆类名 / 短方法名 / itemId，只认稳定字符串锚点 + 反射签名，跨微信版本自适配。
 * 所有增删幂等（官方 p() 会二次调用构建方法）。
 */
public final class MessageMenuHook {

    private static final String TAG = "MessageMenuHook";
    private static final String ANCHOR = "OnCreateContextMMMenux";

    /** 预置候选（v1124 实测汇总：文字/语音/视频/表情 四类长按菜单全部按钮），其余靠运行时自动收录 */
    public static final String[] PRESET = {
            // 通用
            "复制", "转发", "收藏", "编辑", "删除", "多选", "引用", "置顶",
            "提醒", "翻译", "搜一搜", "连续朗读", "打开",
            // 语音
            "听筒播放", "背景播放", "转文字",
            // 视频
            "静音播放",
            // 表情
            "添加", "相关表情", "合拍", "查看专辑"
    };

    /** 存储分隔符：菜单标题不会含换行 */
    public static final String SEP = "\n";

    private static final int OBSERVE_MAX = 300;

    private static volatile boolean sResolving;
    private static volatile boolean sInstalled;
    private static volatile String sFailReason = "";

    /** 页面注册的「收录变化」回调（内部只持弱引用，避免泄漏 Activity） */
    private static volatile Runnable sObserver;

    /** v1127: 菜单构建 after 外发（供语音转发复用同一锚点，免二次 DexKit 扫描 + 保证真实 CL） */
    public interface BuildListener { void onMenuBuilt(Object menu, Object anchorView); }
    private static volatile BuildListener sBuildListener;
    public static void setBuildListener(BuildListener l) { sBuildListener = l; }

    private MessageMenuHook() {}

    public static void setObserver(Runnable r) { sObserver = r; }
    public static boolean isInstalled() { return sInstalled; }
    public static String failReason() { return sFailReason; }

    // ================================================================
    // 入口：异步解析（严禁在 handleLoadPackage 同步跑 DexKit，280MB 会卡启动）
    // ================================================================
    public static void hook(final ClassLoader cl) {
        if (sInstalled || sResolving) return;
        sResolving = true;
        WmPrefs.ensureInit();
        // v1122: 真实类由 Tinker/DelegateLastClassLoader 加载, 该 CL 在 handleLoadPackage 时通常尚未就绪,
        //        必须在后台反复重新获取, 拿到真实 CL 后再 hook, 否则挂在平行副本类上零捕获。
        // v1125: 按钮已全部收录并固化进 PRESET, 关闭逐次诊断日志与逐次重试刷屏, 仅保留成功/最终失败各一条。
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 8 && !sInstalled; attempt++) {
                    ClassLoader useCL = pickRealClassLoader(cl, attempt);
                    try {
                        if (resolveAndInstall(useCL)) {
                            sInstalled = true;
                            LogWriter.log(TAG, "installed (anchor=" + ANCHOR + ") via "
                                    + (useCL == null ? "null" : useCL.getClass().getSimpleName()));
                            return;
                        }
                    } catch (Throwable e) {
                        sFailReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    try { Thread.sleep(2000L); } catch (InterruptedException ignored) {}
                }
                if (!sInstalled) {
                    LogWriter.log(TAG, "unsupported version, feature off (" + sFailReason + ")");
                }
            }
        }, "ls-msgmenu-resolve");
        t.setDaemon(true);
        t.start();
    }

    /** 取当前可用的真实(Tinker)类加载器; 若干次仍拿不到时退回普通 CL 兜底(分身环境常见)。 */
    private static ClassLoader pickRealClassLoader(ClassLoader base, int attempt) {
        try {
            ClassLoader tk = VersionCompat.findTinkerClassLoader(base);
            if (isRealLoader(tk)) return tk;
        } catch (Throwable ignored) {}
        try {
            ClassLoader cm = com.leshao.v3.ContextManager.getTinkerClassLoader();
            if (isRealLoader(cm)) return cm;
        } catch (Throwable ignored) {}
        // 分身等非 Tinker 环境取不到 DelegateLastClassLoader, 不能一直返回 null,
        // 否则 DexKit 无 CL 可扫 → "anchor not found"。第 2 次起退回普通 CL。
        if (attempt >= 2) {
            try {
                ClassLoader cm = com.leshao.v3.ContextManager.getClassLoader();
                if (cm != null) return cm;
            } catch (Throwable ignored) {}
            return base;
        }
        return null;
    }

    private static boolean isRealLoader(ClassLoader cl) {
        if (cl == null) return false;
        String n = cl.getClass().getName();
        return n.contains("DelegateLastClassLoader") || n.contains("Tinker")
                || n.contains("IncrementalClassLoader");
    }

    private static boolean resolveAndInstall(ClassLoader cl) throws Exception {
        List<String> sigs = DexKitHelper.findMethodsByString(cl, null, ANCHOR);
        if (sigs == null || sigs.isEmpty()) { sFailReason = "anchor not found"; return false; }

        for (String sig : sigs) {
            String[] parsed = parseSig(sig);
            if (parsed == null) continue;
            final String className = parsed[0];
            final String methodName = parsed[1];
            try {
                Class<?> builder = Class.forName(className, false, cl);
                Method target = findBuilder(builder, methodName);
                if (target == null) continue;
                final Field listField = findListField(target.getParameterTypes()[0]);
                if (listField == null) continue;

                XposedBridge.hookMethod(target, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try { onMenuBuilt(p.args[0], listField); }
                        catch (Throwable e) { LogWriter.log(TAG, "after err: " + e); }
                        BuildListener l = sBuildListener;
                        if (l != null) {
                            try { l.onMenuBuilt(p.args[0], p.args.length > 1 ? p.args[1] : null); }
                            catch (Throwable e) { LogWriter.log(TAG, "buildListener err: " + e); }
                        }
                    }
                });
                LogWriter.log(TAG, "target " + className + "." + methodName
                        + " menu=" + target.getParameterTypes()[0].getName()
                        + " listField=" + listField.getName());
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static String[] parseSig(String sig) {
        if (sig == null) return null;
        int lp = sig.indexOf('(');
        if (lp <= 0) return null;
        String head = sig.substring(0, lp);
        int dot = head.lastIndexOf('.');
        if (dot <= 0 || dot >= head.length() - 1) return null;
        return new String[]{ head.substring(0, dot), head.substring(dot + 1) };
    }

    /** 在构建类里找 (…, …) 3 参、第 3 参为 ContextMenuInfo 的方法 */
    private static Method findBuilder(Class<?> builder, String name) {
        Method fallback = null;
        for (Method m : builder.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 3) continue;
            if (android.view.ContextMenu.ContextMenuInfo.class.isAssignableFrom(pts[2])) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    /** 取 MMMenu 里承载 MenuItem 的 List 字段（deepdive A2：字段 d，类型 java.util.List） */
    private static Field findListField(Class<?> menuCls) {
        Field pick = null;
        Field named = null;
        for (Field f : menuCls.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (!List.class.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); } catch (Throwable ignored) {}
            if (pick == null) pick = f;
            if ("d".equals(f.getName())) named = f;
        }
        return named != null ? named : pick;
    }

    // ================================================================
    // 菜单构建后：收录 + 移除（幂等）
    // ================================================================
    @SuppressWarnings("unchecked")
    private static void onMenuBuilt(Object menu, Field listField) {
        if (menu == null || listField == null) return;
        Object raw;
        try { raw = listField.get(menu); } catch (Throwable e) { return; }
        if (!(raw instanceof List)) return;
        List<Object> list = (List<Object>) raw;
        if (list.isEmpty()) return;

        WmPrefs.ensureInit();
        Set<String> hidden = parseSet(WmPrefs.getMsgMenuHidden());
        Set<String> observed = parseSet(WmPrefs.getMsgMenuObserved());

        int titled = 0, removable = 0;
        for (Object o : list) {
            String s = titleOf(o);
            if (s == null) continue;
            titled++;
            if (isHidden(s, hidden)) removable++;
        }
        // 至少留 1 项：微信在 count==0 时菜单不弹（deepdive 2.2）
        boolean canRemove = WmPrefs.isMsgMenuEnabled() && titled > 0 && removable < titled;

        boolean changed = false;
        for (Iterator<Object> it = list.iterator(); it.hasNext();) {
            Object o = it.next();
            String s = titleOf(o);
            if (s == null) continue;
            if (observed.add(s)) changed = true;
            if (canRemove && isHidden(s, hidden)) it.remove();
        }

        if (changed) {
            if (observed.size() > OBSERVE_MAX) {
                List<String> arr = new ArrayList<>(observed);
                observed = new LinkedHashSet<>(arr.subList(arr.size() - OBSERVE_MAX, arr.size()));
            }
            WmPrefs.setMsgMenuObserved(join(observed));
            notifyObserver();
        }
    }

    private static String titleOf(Object o) {
        if (o == null) return null;
        if (o instanceof MenuItem) {
            try {
                CharSequence t = ((MenuItem) o).getTitle();
                String s = t == null ? null : t.toString().trim();
                if (s != null && !s.isEmpty()) return s;
            } catch (Throwable ignored) {}
        }
        // v1120 兜底: 不依赖 instanceof MenuItem, 反射调 getTitle()
        try {
            Method m = o.getClass().getMethod("getTitle");
            Object t = m.invoke(o);
            String s = t == null ? null : t.toString().trim();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    /** v1123: 包含匹配 —— 勾选「朗读」也能命中实际标题「连续朗读」 */
    private static boolean isHidden(String title, Set<String> hidden) {
        if (title == null) return false;
        for (String h : hidden) {
            if (h == null || h.isEmpty()) continue;
            if (title.equals(h)) return true;
            if (h.length() >= 2 && title.contains(h)) return true;
        }
        return false;
    }

    private static void notifyObserver() {
        final Runnable r = sObserver;
        if (r == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    // ================================================================
    // 集合 <-> 字符串
    // ================================================================
    public static Set<String> parseSet(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split(SEP)) {
            String s = p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        if (set != null) {
            for (String s : set) {
                if (s == null || s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(SEP);
                sb.append(s);
            }
        }
        return sb.toString();
    }
}
