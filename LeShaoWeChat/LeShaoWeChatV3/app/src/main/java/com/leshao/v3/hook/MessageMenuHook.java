package com.leshao.v3.hook;

import android.view.MenuItem;
import android.view.View;

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
 * 聊天消息长按菜单净化（v1110 新增，v3.0.170 重构）。
 *
 * <p>依据《WeChat_ChatLongPress_Menu_DeepDive.md》：
 * <ul>
 *   <li><b>A 层（构建期删项）</b>：锚点 "OnCreateContextMMMenux" → 菜单构建器
 *       {@code com.tencent.mm.ui.chatting.viewitems.o0#a(MMMenu, View, ContextMenuInfo)}。
 *       after 遍历 MMMenu 的 List 字段（deepdive A2：{@code d}），按“按钮名开关 + 标题黑名单”移除。</li>
 *   <li><b>B 层（点击期拦截）</b>：hook {@code viewitems.r0.onMMMenuItemSelected(MenuItem,int)}
 *       唯一收口，命中已关按钮 → {@code setResult(null)} 跳过。</li>
 *   <li><b>C 层（全局长按熄菜）</b>：hook {@code viewitems.m0.g(View)} before → 菜单不弹。</li>
 * </ul>
 *
 * <p>按钮开关（v3.0.170）：文档第 3 章每一个按钮都是独立开关，存于
 * {@link WmPrefs#getMsgMenuBtnHidden()}（\n 分隔的按钮名）。按钮判定 = itemId 白名单 +
 * 标题关键词双保险（文档 §3.1：ID 黑名单 + 标题黑名单）。旧的标题黑名单
 * {@link WmPrefs#getMsgMenuHidden()} 继续生效，自动收录 {@link WmPrefs#getMsgMenuObserved()} 保留。
 *
 * <p>不写死混淆类名 / 短方法名 / itemId，只认稳定字符串锚点 + 反射签名，跨微信版本自适配。
 * 所有增删幂等（官方 p() 会二次调用构建方法）。
 */
public final class MessageMenuHook {

    private static final String TAG = "MessageMenuHook";
    private static final String ANCHOR = "OnCreateContextMMMenux";

    /** 文档按钮定义：语义名 + itemId 集 + 标题关键词集。 */
    public static final class Button {
        public final String name;
        public final int[] ids;
        public final String[] titles;

        Button(String name, int[] ids, String[] titles) {
            this.name = name;
            this.ids = ids;
            this.titles = titles;
        }

        public boolean matchId(int id) {
            if (ids == null) return false;
            for (int x : ids) if (x == id) return true;
            return false;
        }

        public boolean matchTitle(String title) {
            if (title == null || titles == null) return false;
            for (String k : titles) {
                if (k == null || k.isEmpty()) continue;
                if (title.equals(k)) return true;
                if (k.length() >= 2 && title.contains(k)) return true;
            }
            return false;
        }
    }

    /** 文档第 3 章全表按钮。名称即配置页开关文案。 */
    public static final Button[] BUTTONS = {
            new Button("复制", new int[]{102, 136, 141}, new String[]{"复制"}),
            new Button("转发", new int[]{103, 108, 110, 111, 129, 139, 140, 142}, new String[]{"转发"}),
            new Button("收藏", new int[]{116, 126, 143}, new String[]{"收藏"}),
            new Button("删除", new int[]{100}, new String[]{"删除"}),
            new Button("多选", new int[]{122}, new String[]{"多选"}),
            new Button("引用", new int[]{135}, new String[]{"引用", "引用了"}),
            new Button("提醒", new int[]{134}, new String[]{"提醒"}),
            new Button("翻译", new int[]{124, 125, 163, 164}, new String[]{"翻译", "全文翻译"}),
            new Button("搜一搜", new int[]{4, 137, 170}, new String[]{"搜一搜"}),
            new Button("连续播报", new int[]{175}, new String[]{"连续播报", "连续朗读", "朗读"}),
            new Button("打开", new int[]{150, 171}, new String[]{"打开"}),
            new Button("静音播放", null, new String[]{"静音播放", "扬声器播放", "听筒播放", "背景播放"}),
            new Button("相关表情", new int[]{123}, new String[]{"相关表情"}),
            new Button("查看专属", new int[]{173, 174, 183, 184}, new String[]{"查看专属", "查看原文", "元宝"}),
            new Button("编辑", new int[]{151}, new String[]{"编辑"}),
            new Button("语音转文字", new int[]{119, 120, 121}, new String[]{"转文字", "取消转文字", "语音转文字"}),
            new Button("识图", new int[]{152, 185}, new String[]{"识图", "问问小微", "提取文字"}),
            new Button("语音分享", new int[]{165, 181}, new String[]{"来源分享"}),
            new Button("直播分享", new int[]{179}, new String[]{"直播分享"}),
            new Button("调试", new int[]{138}, new String[]{"调试"}),
            new Button("添加", null, new String[]{"添加"}),
            new Button("合拍", null, new String[]{"合拍"}),
            new Button("查看专辑", null, new String[]{"查看专辑"})
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
        // v3.0.174（3180 审计 §2）：锚点串 OnCreateContextMMMenux 在 3180 已从 o0.a 方法中移除，
        // 字符串搜索返回 0。改为权威类名 com.tencent.mm.ui.chatting.viewitems.o0 直接加载，
        // 字符串搜索降级为兜底（跨版本自适配仍保留）。
        List<String> classCands = new ArrayList<>();
        classCands.add("com.tencent.mm.ui.chatting.viewitems.o0");
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, ANCHOR);
            if (sigs != null && !sigs.isEmpty()) {
                for (String sig : sigs) {
                    String[] parsed = parseSig(sig);
                    if (parsed == null) continue;
                    if (!classCands.contains(parsed[0])) classCands.add(parsed[0]);
                }
            }
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "anchor candidates=" + classCands.size() + " classes=" + classCands
                + " loader=" + (cl == null ? "null" : cl.getClass().getName()));
        if (classCands.isEmpty()) { sFailReason = "anchor not found"; return false; }

        boolean installed = false;
        for (final String className : classCands) {
            Class<?> builder;
            try {
                builder = Class.forName(className, false, cl);
            } catch (Throwable e) {
                LogWriter.log(TAG, "load class fail " + className + " -> " + e);
                continue;
            }
            final Method target = findBuilder(builder, "a");
            if (target == null) {
                LogWriter.log(TAG, "no 3-arg ContextMenuInfo method 'a' in " + className
                        + " methods=" + methodList(builder));
                continue;
            }
            final Field listField = findListField(target.getParameterTypes()[0]);
            if (listField == null) {
                LogWriter.log(TAG, "no List field in menu " + target.getParameterTypes()[0].getName()
                        + " fields=" + fieldList(target.getParameterTypes()[0]));
                continue;
            }

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
            installed = true;
            LogWriter.log(TAG, "target " + className + ".a"
                    + " menu=" + target.getParameterTypes()[0].getName()
                    + " menuImplementsContextMenu=" + android.view.ContextMenu.class.isAssignableFrom(target.getParameterTypes()[0])
                    + " listField=" + listField.getName());
            break;
        }

        if (!installed) return false;

        // A 层已装；B/C 层尽力而为，失败不阻断安装
        hookClickFunnel(cl);
        hookDisableAll(cl);
        return true;
    }

    private static String methodList(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Method m : c.getDeclaredMethods()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(m.getName()).append("(").append(m.getParameterTypes().length).append(")");
        }
        return sb.toString();
    }

    private static String fieldList(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (Field f : c.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(f.getName()).append(":").append(f.getType().getSimpleName());
        }
        return sb.toString();
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

    /** 在构建类里找方法：v3.0.166（文档 §8）—— 优先 3 参 + 第2参 View + 第3参 ContextMenuInfo。
     *  MMMenu 参数(第1参)是实现 android.view.ContextMenu 的 kj5.i4，不参与签名比对。 */
    private static Method findBuilder(Class<?> builder, String name) {
        Method strict = null;
        Method fallback = null;
        for (Method m : builder.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] pts = m.getParameterTypes();
            if (pts.length != 3) continue;
            boolean ctxMenuInfo = android.view.ContextMenu.ContextMenuInfo.class.isAssignableFrom(pts[2]);
            boolean hasView = View.class.isAssignableFrom(pts[1]) || View.class.isAssignableFrom(pts[2]);
            if (ctxMenuInfo && hasView) {
                if (strict == null) strict = m;
            }
            if (ctxMenuInfo) {
                if (fallback == null) fallback = m;
            }
        }
        if (strict != null) return strict;
        if (fallback != null) return fallback;
        // 文档 §8 兜底：o0 只有 <init> 与 a 两个方法，a 是唯一 3 参方法时直接取它
        for (Method m : builder.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length == 3) return m;
        }
        return null;
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
    // B 层：点击收口 r0.onMMMenuItemSelected 拦截
    // ================================================================
    private static void hookClickFunnel(ClassLoader cl) {
        try {
            Class<?> r0 = Class.forName("com.tencent.mm.ui.chatting.viewitems.r0", false, cl);
            Method funnel = null;
            for (Method m : r0.getDeclaredMethods()) {
                if (!m.getName().equals("onMMMenuItemSelected")) continue;
                if (m.getParameterCount() != 2) continue;
                if (MenuItem.class.isAssignableFrom(m.getParameterTypes()[0])
                        && int.class == m.getParameterTypes()[1]) {
                    funnel = m;
                    break;
                }
            }
            if (funnel == null) {
                LogWriter.log(TAG, "B-layer: no onMMMenuItemSelected(MenuItem,int) in r0");
                return;
            }
            funnel.setAccessible(true);
            final Method finalFunnel = funnel;
            XposedBridge.hookMethod(funnel, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!WmPrefs.isMsgMenuEnabled()) return;
                        if (p.args.length < 1 || p.args[0] == null) return;
                        if (btnDisabledFor(p.args[0])) p.setResult(null);
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "B-layer: hooked r0." + finalFunnel.getName()
                    + "(" + finalFunnel.getParameterTypes().length + ")");
        } catch (Throwable e) {
            LogWriter.log(TAG, "B-layer: skip (" + e + ")");
        }
    }

    // ================================================================
    // C 层：全局长按熄菜 m0.g(View) before
    // ================================================================
    private static void hookDisableAll(ClassLoader cl) {
        try {
            Class<?> m0 = Class.forName("com.tencent.mm.ui.chatting.viewitems.m0", false, cl);
            Method g = null;
            for (Method m : m0.getDeclaredMethods()) {
                if (!m.getName().equals("g")) continue;
                if (m.getParameterCount() == 1 && View.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    g = m;
                    break;
                }
            }
            if (g == null) {
                LogWriter.log(TAG, "C-layer: no g(View) in m0");
                return;
            }
            g.setAccessible(true);
            final Method finalG = g;
            XposedBridge.hookMethod(g, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (WmPrefs.isMsgMenuEnabled() && WmPrefs.isMsgMenuAllOff()) {
                            p.setResult(null);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "C-layer: hooked m0.g(View)");
        } catch (Throwable e) {
            LogWriter.log(TAG, "C-layer: skip (" + e + ")");
        }
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
        Set<String> btnHidden = parseSet(WmPrefs.getMsgMenuBtnHidden());
        Set<String> observed = parseSet(WmPrefs.getMsgMenuObserved());

        int titled = 0, removable = 0;
        for (Object o : list) {
            if (titleOf(o) != null) titled++;
            if (shouldRemove(o, hidden, btnHidden)) removable++;
        }
        // 至少留 1 项：微信在 count==0 时菜单不弹（deepdive 2.2）
        boolean canRemove = WmPrefs.isMsgMenuEnabled() && titled > 0 && removable < titled;

        boolean changed = false;
        for (Iterator<Object> it = list.iterator(); it.hasNext();) {
            Object o = it.next();
            String s = titleOf(o);
            if (s != null && observed.add(s)) changed = true;
            if (canRemove && shouldRemove(o, hidden, btnHidden)) it.remove();
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

    /** 命中任一关闭条件即移除：文档按钮开关（ID+标题）或旧标题黑名单。 */
    private static boolean shouldRemove(Object o, Set<String> hiddenTitles, Set<String> btnHidden) {
        String btn = btnNameFor(o);
        if (btn != null && btnHidden.contains(btn)) return true;
        String title = titleOf(o);
        if (title != null && isHidden(title, hiddenTitles)) return true;
        return false;
    }

    /** 判断菜单项对应的文档按钮是否已被关闭（B 层用）。 */
    private static boolean btnDisabledFor(Object item) {
        String btn = btnNameFor(item);
        if (btn == null) return false;
        return parseSet(WmPrefs.getMsgMenuBtnHidden()).contains(btn);
    }

    /** 映射菜单项 → 文档按钮名；先标题后 ID（标题是用户看到的真相）。 */
    static String btnNameFor(Object o) {
        String title = titleOf(o);
        if (title != null) {
            for (Button b : BUTTONS) if (b.matchTitle(title)) return b.name;
        }
        int id = -1;
        try {
            if (o instanceof MenuItem) {
                id = ((MenuItem) o).getItemId();
            } else {
                Method m = o.getClass().getMethod("getItemId");
                Object r = m.invoke(o);
                if (r instanceof Number) id = ((Number) r).intValue();
            }
        } catch (Throwable ignored) {}
        if (id >= 0) {
            for (Button b : BUTTONS) if (b.matchId(id)) return b.name;
        }
        return null;
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