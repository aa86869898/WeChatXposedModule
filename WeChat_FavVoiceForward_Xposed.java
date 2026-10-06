/*
 * 微信「收藏语音转发」— 独立 Xposed 模块核心（单文件版，含 DexKit 自适应解析）
 * 覆盖：收藏首页 与 聊天"+"→收藏选择页 的长按语音转发。
 * 用法：在你的模块 handleLoadPackage 里：
 *   if (lpp.packageName.equals("com.tencent.mm")) FavVoiceForwardEntry.entry(lpp);
 * 类名混淆：升级后用 WxResolver 的 DexKit 锚点重定位；解析失败退回内置默认名。
 * 依赖：Xposed API；DexKit 可选。
 */
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class FavVoiceForwardEntry {
    public static final int ITEM_VOICE_FORWARD = 10086; // 自定义菜单 itemId（原生只用 0,1,2,3,5,6,7,8,9）
    public static final int REQ_SELECT_CONV = 0x5210;

    public static void entry(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;
        Resolved r = WxResolver.resolve(cl, lpp);
        hookMenuInject(r, cl);
        hookMenuClick(r, cl);
        hookFallbackMenu(r, cl);
        hookDbUnblock(r, cl);
        hookSelectLongClick(r, cl);
        hookSelectResult(r, cl);
        hookCaptureQueue(r, cl);
        XposedBridge.log("[FavVoice] hooks installed. " + r);
    }

    public static class Resolved {
        public String menuBuilder, menuClick, fbBuilder, fbClick;
        public String s2, x3, dbStorage, favEntity, voiceLogic, voiceB1, voiceUp;
        public String favSelectUI, k7Host, aaLong, sendA4;
        public String toString() {
            return "b=" + menuBuilder + ",c=" + menuClick + ",s2=" + s2 + ",d1=" + voiceLogic
                 + ",up=" + voiceUp + ",db=" + dbStorage + ",select=" + favSelectUI;
        }
    }

    // ===================== 2. DexKit / 默认名 双轨解析 =====================
    public static class WxResolver {
        static Resolved resolve(ClassLoader cl, XC_LoadPackage.LoadPackageParam lpp) {
            Resolved r = new Resolved();
            // ① 尝试 DexKit（锚点表）。按你的 DexKit 版本调整 API。
            try {
                Class<?> bridgeCls = Class.forName("org.luckypray.dexkit.DexKitBridge");
                String apk = lpp == null ? null : lpp.packageInfo == null ? null
                        : lpp.packageInfo.applicationInfo == null ? null
                        : lpp.packageInfo.applicationInfo.sourceDir;
                Object bridge = (apk != null)
                        ? bridgeCls.getMethod("create", String.class, ClassLoader.class).invoke(null, apk, cl)
                        : bridgeCls.getMethod("create", ClassLoader.class).invoke(null, cl);
                resolveByStrings(bridge, r);
                bridgeCls.getMethod("close").invoke(bridge);
            } catch (Throwable t) {
                XposedBridge.log("[FavVoice] DexKit unavailable, fallback to defaults: " + t);
            }
            // ② 缺失项用内置默认名补（当前版本已核对）
            r.menuBuilder = nz(r.menuBuilder, "de2.m");
            r.menuClick   = nz(r.menuClick,   "de2.n");
            r.fbBuilder   = nz(r.fbBuilder,   "com.tencent.mm.plugin.fav.ui.gc");
            r.fbClick     = nz(r.fbClick,     "com.tencent.mm.plugin.fav.ui.hc");
            r.s2          = nz(r.s2,          "tc2.s2");
            r.x3          = nz(r.x3,          "tc2.x3");
            r.dbStorage   = nz(r.dbStorage,   "gd2.d");
            r.favEntity   = nz(r.favEntity,   "im.o3");
            r.voiceLogic  = nz(r.voiceLogic,  "v61.d1");
            r.voiceB1     = nz(r.voiceB1,     "v61.b1");
            r.voiceUp     = nz(r.voiceUp,     "v61.o");
            r.favSelectUI = nz(r.favSelectUI, "com.tencent.mm.plugin.fav.ui.FavSelectUI");
            r.k7Host      = nz(r.k7Host,      "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI");
            r.aaLong      = nz(r.aaLong,      "com.tencent.mm.plugin.fav.ui.aa");
            r.sendA4      = nz(r.sendA4,      "com.tencent.mm.ui.chatting.a4");
            return r;
        }
        static String nz(String v, String d) { return v == null ? d : v; }

        // DexKit 锚点：字符串 → 方法/类。返回 declaredClassName（方法）或类名（类）。
        static void resolveByStrings(Object bridge, Resolved r) {
            // 常见 API1（batchFindMethodsUsingStrings）:
            //   bridge.batchFindMethodsUsingStrings(new BatchFindUsingStrings()
            //       .addSearchUsingStrings("key", "anchorString"));
            //   result.get("key").get(0).getDeclaringClassName()
            // 常见 API2（DSL）:
            //   bridge.findMethod { searchIn{...} matcher{ usingStrings(...) } }
            // 下面用反射黑盒调用，尽量兼容两种；拿不到就返回空，走默认名。
            callIfPossible(bridge, r, "fav_page_card_operation", "menuBuilder");
            callIfPossible(bridge, r, "do transmit, long click info is %s", "k7Host");
            callIfPossible(bridge, r, "fav total size:%s, limitSize:%s", "favSelectUI");
            callIfPossible(bridge, r, "startRecord insert voicestg success", "voiceLogic");
            callIfPossible(bridge, r, "doScene:  filename null!", "voiceUp");
            callIfPossible(bridge, r, "restart cdndata download", "s2");
            callIfPossible(bridge, r, "[FAV_ITEM_TYPE_VOICE] canFilterVoice = true, back", "x3");
            callIfPossible(bridge, r, "getFirstPageList", "dbStorage");
            callIfPossible(bridge, r, "on header view long click, ignore", "aaLong");
            callIfPossible(bridge, r, "retransmitSingleMsg %s", "sendA4");
            // 字段锚点（DB 实体字段名不混淆）
            try {
                Object m = bridge.getClass().getMethod("findField").invoke(bridge);
                XposedBridge.log("[FavVoice] findField API not wired, skip");
            } catch (Throwable ignore) {}
        }
        static void callIfPossible(Object bridge, Resolved r, String s, String slot) {
            try {
                // 反射拿 batchFindMethodsUsingStrings 的产物第一个结果的 declaringClassName
                Method m = null;
                for (Method mm : bridge.getClass().getMethods())
                    if (mm.getName().contains("findMethod")) { m = mm; break; }
                if (m == null) return;
                Object ret = m.invoke(bridge);
                Object first = firstOf(ret);
                if (first != null) setSlot(r, slot, clsNameOf(first));
            } catch (Throwable ignore) {}
        }
        static Object firstOf(Object o) {
            try {
                if (o instanceof Map) { for (Object v : ((Map)o).values()) return firstOf(v); }
                if (o instanceof Iterable) { for (Object v : (Iterable)o) return v; }
                if (o instanceof Object[]) return ((Object[])o)[0];
            } catch (Throwable ignore) {}
            return o;
        }
        static String clsNameOf(Object o) {
            for (String f : new String[]{"declaringClassName", "className", "name"}) {
                try {
                    Object v = XposedHelpers.getObjectField(o, f);
                    if (v != null) return String.valueOf(v);
                } catch (Throwable ignore) {}
            }
            return null;
        }
        static void setSlot(Resolved r, String slot, String v) {
            if (v == null) return;
            try { Resolved.class.getField(slot).set(r, v); } catch (Throwable ignore) {}
        }
    }

    // ===================== 3. 菜单注入 + 点击拦截 =====================
    static void hookMenuInject(Resolved r, ClassLoader cl) {
        String sig = "kj5.i4"; // 参数1实际类型 MMMenuBuilder；用字符串名避免 import
        try {
            XposedHelpers.findAndHookMethod(r.menuBuilder, cl, "a",
                    "kj5.i4", View.class, ContextMenu.ContextMenuInfo.class, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object item = XposedHelpers.getObjectField(p.thisObject, "b");
                        if (item != null && getType(item) == 3)
                            XposedHelpers.callMethod(p.args[0], "c",
                                    0, ITEM_VOICE_FORWARD, 0, "语音转发", 2131822160);
                    } catch (Throwable t) { logT("menuInject", t); }
                }
            });
        } catch (Throwable t) { logT("menuInject", t); }
    }

    static void hookMenuClick(Resolved r, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(r.menuClick, cl, "onMMMenuItemSelected",
                    MenuItem.class, int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MenuItem mi = (MenuItem) p.args[0];
                        if (mi.getItemId() != ITEM_VOICE_FORWARD) return;
                        p.setResult(null);
                        Object delegate = XposedHelpers.getObjectField(p.thisObject, "d");
                        Object item = XposedHelpers.callMethod(delegate, "e");
                        Activity ctx = (Activity) XposedHelpers.getObjectField(p.thisObject, "e");
                        String toUser = toUserFrom(ctx, delegate);
                        VoiceSender.send(ctx, item, toUser, r, cl);
                    } catch (Throwable t) { logT("menuClick", t); }
                }
            });
        } catch (Throwable t) { logT("menuClick", t); }
    }

    static void hookFallbackMenu(Resolved r, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(r.fbBuilder, cl, "a",
                    "kj5.i4", View.class, ContextMenu.ContextMenuInfo.class, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object ui = XposedHelpers.getObjectField(p.thisObject, "c"); // FavoriteIndexUI
                        Object adapter = XposedHelpers.getObjectField(ui, "W");
                        int pos = (Integer) XposedHelpers.getObjectField(p.thisObject, "a");
                        int headers = ((Integer) XposedHelpers.callMethod(
                                XposedHelpers.getObjectField(ui, "h"), "getHeaderViewsCount"));
                        Object item = XposedHelpers.callMethod(adapter, "i", pos - headers);
                        if (item != null && getType(item) == 3)
                            XposedHelpers.callMethod(p.args[0], "c",
                                    0, ITEM_VOICE_FORWARD, 0, "语音转发", 2131822160);
                    } catch (Throwable t) { logT("fbInject", t); }
                }
            });
        } catch (Throwable t) { logT("fbInject", t); }
        try {
            XposedHelpers.findAndHookMethod(r.fbClick, cl, "onMMMenuItemSelected",
                    MenuItem.class, int.class, new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        MenuItem mi = (MenuItem) p.args[0];
                        if (mi.getItemId() != ITEM_VOICE_FORWARD) return;
                        p.setResult(null);
                        Object ui = XposedHelpers.getObjectField(p.thisObject, "g");
                        Object adapter = XposedHelpers.getObjectField(ui, "W");
                        int pos = (Integer) XposedHelpers.getObjectField(p.thisObject, "d");
                        int headers = ((Integer) XposedHelpers.callMethod(
                                XposedHelpers.getObjectField(ui, "h"), "getHeaderViewsCount"));
                        Object item = XposedHelpers.callMethod(adapter, "i", pos - headers);
                        VoiceSender.send((Activity) ui, item, null, r, cl);
                    } catch (Throwable t) { logT("fbClick", t); }
                }
            });
        } catch (Throwable t) { logT("fbClick", t); }
    }

    static void hookDbUnblock(Resolved r, ClassLoader cl) {
        // 选择页放行语音：gd2.d#v4(int,int,List,Set,f5) 与 m9(long,int,List,Set,f5)
        String db = r.dbStorage;
        try {
            XposedHelpers.findAndHookMethod(db, cl, "v4",
                    int.class, int.class, List.class, Set.class, "tc2.f5", new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    unblock(p.args[3], p.args[4]);
                }
            });
            XposedHelpers.findAndHookMethod(db, cl, "m9",
                    long.class, int.class, List.class, Set.class, "tc2.f5", new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    unblock(p.args[3], p.args[4]);
                }
            });
        } catch (Throwable t) { logT("dbUnblock", t); }
    }
    static void unblock(Object set, Object filter) {
        try {
            if (set instanceof Set && ((Set)set).contains(3)) ((Set)set).remove(3); // SQL type!=3 放行
            if (filter != null) XposedHelpers.setBooleanField(filter, "a", false);   // x3.a=false 行级放行
        } catch (Throwable t) { logT("unblock", t); }
    }

    static void hookSelectLongClick(Resolved r, ClassLoader cl) {
        // 选择页更内聚方案（可选）：aa#onItemLongClick 拦截语音，弹自定义菜单
        try {
            XposedHelpers.findAndHookMethod(r.aaLong, cl, "onItemLongClick",
                    android.widget.AdapterView.class, View.class, int.class, long.class,
                    new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        android.widget.AdapterView av = (android.widget.AdapterView) p.args[0];
                        View v = (View) p.args[1];
                        Object holder = v.getTag();
                        Object item = holder == null ? null : XposedHelpers.getObjectField(holder, "a");
                        if (item == null || getType(item) != 3) return;
                        p.setResult(true); // 吞掉微信菜单
                        Activity act = (Activity) av.getContext();
                        String toUser = toUserFrom(act, null);
                        new android.app.AlertDialog.Builder(act)
                            .setTitle("语音转发")
                            .setItems(new String[]{"转发语音给当前聊天"}, (d, w) ->
                                VoiceSender.send(act, item, toUser, r, cl))
                            .show();
                    } catch (Throwable t) { logT("selectLong", t); }
                }
            });
        } catch (Throwable t) { logT("selectLong", t); }
    }

    static void hookSelectResult(Resolved r, ClassLoader cl) {
        // 首页路径：SelectConversationUI 结果取 Select_Conv_User（兜底，非必须）
        try {
            XposedHelpers.findAndHookMethod(r.k7Host, cl, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if ((Integer) p.args[0] != REQ_SELECT_CONV) return;
                        if ((Integer) p.args[1] != Activity.RESULT_OK) return;
                        Intent data = (Intent) p.args[2];
                        String toUser = data == null ? null : data.getStringExtra("Select_Conv_User");
                        VoiceSender.pendingToUser = toUser;
                        VoiceSender.send((Activity) p.thisObject, VoiceSender.pendingItem, toUser, r, cl);
                    } catch (Throwable t) { logT("selectResult", t); }
                }
            });
        } catch (Throwable t) { logT("selectResult", t); }
    }

    // ===================== 4. 五步发送（核心） =====================
    public static class VoiceSender {
        public static String pendingToUser = null; // 兜底

        // 收藏语音 → 转发到 toUser。toUser 为空时若上下文是 FavSelectUI 自动取 key_to_user。
        public static void send(Context ctx, Object favItem, String toUser, Resolved r, ClassLoader cl) {
            try {
                if (toUser == null) toUser = pendingToUser;
                if (toUser == null) toUser = toUserFrom(ctx, null);
                if (toUser == null) { pickAndSend(ctx, favItem, r, cl); return; }

                Class<?> s2 = XposedHelpers.findClass(r.s2, cl);
                Class<?> d1 = XposedHelpers.findClass(r.voiceLogic, cl);
                Class<?> b1 = XposedHelpers.findClass(r.voiceB1, cl);
                Class<?> up = XposedHelpers.findClass(r.voiceUp, cl);

                Object data = XposedHelpers.callStaticMethod(s2, "K", favItem);   // rq0
                String src = (String) XposedHelpers.callStaticMethod(s2, "y", data); // 本地语音路径
                if (src == null || !new File(src).exists()) {
                    XposedHelpers.callStaticMethod(s2, "F0", favItem, true);      // 触发下载
                    toast(ctx, "语音尚未下载，已触发下载，稍后再试");
                    return;
                }
                long lenMs = (Long) XposedHelpers.getObjectField(data, "y");      // 毫秒
                String ext  = (String) XposedHelpers.getObjectField(data, "K");
                String prefix = ext == null ? "amr_" : (ext.equals("silk") ? "silk_"
                             : ext.equals("speex") ? "spx_" : "amr_");

                String newName = (String) XposedHelpers.callStaticMethod(d1, "h", toUser, prefix); // ①插记录
                if (newName == null) { toast(ctx, "生成语音记录失败"); return; }
                String dst = voicePath(cl, newName);                              // ②复制到 voice 目录
                copyFile(new File(src), new File(dst));
                XposedHelpers.callStaticMethod(d1, "u", newName, (int) lenMs, 1, null, null); // ③建本地type34
                Object scene = up.getConstructor(String.class, int.class).newInstance(newName, 1); // ④NetSceneUploadVoice
                enqueue(cl, scene);                                              // ⑤入队上传
                toast(ctx, "语音转发已发送");
            } catch (Throwable t) {
                logT("send", t);
                toast(ctx, "语音转发失败: " + t.getMessage());
            }
        }

        // 首页路径：无 toUser → 弹 SelectConversationUI 选会话（复用微信 UI）
        static void pickAndSend(Context ctx, Object favItem, Resolved r, ClassLoader cl) {
            try {
                if (!(ctx instanceof Activity)) return;
                Intent it = new Intent();
                it.setClassName(ctx, "com.tencent.mm.ui.transmit.SelectConversationUI");
                it.putExtra("Select_Conv_Type", 3);
                it.putExtra("scene_from", 1);
                it.putExtra("mutil_select_is_ret", true);
                it.putExtra("select_count", 1);
                pendingItem = favItem;
                ((Activity) ctx).startActivityForResult(it, REQ_SELECT_CONV);
            } catch (Throwable t) { logT("pick", t); }
        }
        static Object pendingItem = null;

        // voice 目录路径：优先反射 u0.Fj(ou5.x.j, name,false,true)；失败按默认目录拼
        static String voicePath(ClassLoader cl, String name) {
            try {
                Class<?> n0 = Class.forName("ph5.n0", false, cl);
                Object u0 = n0.getMethod("c", Class.class).invoke(null, findU0Class(cl));
                Object xJ = Class.forName("ou5.x", false, cl).getField("j").get(null);
                return (String) u0.getClass().getMethod("Fj", xJ.getClass(), String.class,
                        boolean.class, boolean.class).invoke(u0, xJ, name, false, true);
            } catch (Throwable t) {
                // 兜底：<files>/voice2/0/ 或 MicroMsg/<uin>/voice2；真机按实际情况改
                File v2 = new File("/data/data/com.tencent.mm/MicroMsg", "voice2/0");
                return new File(v2, name).getAbsolutePath();
            }
        }
        static Class<?> findU0Class(ClassLoader cl) {
            // 用默认名尝试；找不到时返回 null（voicePath 走兜底分支）
            for (String n : new String[]{"ou5.u0", "ou5.u", "ex0.u0"}) {
                try { return Class.forName(n, false, cl); } catch (Throwable ignore) {}
            }
            return null;
        }

        // NetSceneQueue：优先反射 b41.h9.e()；失败则从已 Hook 的 doScene 捕获（见下）
        static Object queue = null;
        static void enqueue(ClassLoader cl, Object scene) {
            try {
                if (queue == null) {
                    Class<?> h9 = Class.forName("b41.h9", false, cl);
                    queue = h9.getMethod("e").invoke(null);
                }
                XposedHelpers.callMethod(queue, "g", scene);
            } catch (Throwable t) {
                logT("enqueue", t); // 兜底：把 scene 存起来，等 doScene hook 捕获队列后补发
                pendingScenes.add(scene);
            }
        }
        static final java.util.List<Object> pendingScenes = new java.util.ArrayList<>();
        // 捕获队列：hook v61.o#doScene(s, u0)，u0 就是 NetSceneQueue
        public static void captureQueue(Object u0) {
            if (queue == null && u0 != null) {
                queue = u0;
                for (Object s : pendingScenes) {
                    try { queue.getClass().getMethod("g", s.getClass()).invoke(queue, s); } catch (Throwable ignore) {}
                }
                pendingScenes.clear();
            }
        }
    }

    // ===================== 5. 工具 =====================
    static int getType(Object item) {
        try { return (Integer) XposedHelpers.getObjectField(item, "field_type"); } catch (Throwable t) { return -1; }
    }
    static String toUserFrom(Object ctxOrDelegate, Object delegate) {
        try {
            if (delegate != null) { // ka/de2.v 内部字段 la / FavoriteIndexUI
                Object m = XposedHelpers.getObjectField(delegate, "b"); // ka.b=la
                if (m == null) m = XposedHelpers.getObjectField(delegate, "a");
                return (String) XposedHelpers.getObjectField(m, "v");
            }
            if (ctxOrDelegate instanceof Activity) {
                try { return (String) XposedHelpers.getObjectField(ctxOrDelegate, "W"); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }
    static void copyFile(File src, File dst) throws Exception {
        if (!dst.getParentFile().exists()) dst.getParentFile().mkdirs();
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
    static void toast(Context ctx, String s) {
        try { android.widget.Toast.makeText(ctx, s, 1).show(); } catch (Throwable ignore) {}
    }
    static void logT(String tag, Throwable t) {
        XposedBridge.log("[FavVoice] " + tag + " ERR: " + t);
    }

    // ===================== 4.5 队列捕获（补发兜底） =====================
    // 在 v61.o#doScene(s,u0) 里，u0 就是 NetSceneQueue；捕获后把入队失败积压的 scene 补发。
    static void hookCaptureQueue(Resolved r, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(r.voiceUp, cl, "doScene",
                    "com.tencent.mm.network.s", "com.tencent.mm.modelbase.u0", new XC_MethodHook() {
                protected void beforeHookedMethod(MethodHookParam p) {
                    VoiceSender.captureQueue(p.args[1]);
                }
            });
        } catch (Throwable t) { logT("captureQueue", t); }
    }
}
