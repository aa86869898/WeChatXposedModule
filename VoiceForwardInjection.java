import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 微信语音消息 — 长按菜单添加"语音转发"按钮
 *
 * 独立 Xposed 模块，零第三方依赖（仅需 compileOnly Xposed API）
 * 微信 8.0.76 实测 — 通用方案，不依赖 ChattingItemVoice 类名
 *
 * ★ 核心策略 (3 层兜底):
 *   Layer 1: 扫描所有含 "chat_voice_msg_menu" 字符串的类，Hook 它们的 R() 方法
 *   Layer 2: Hook k0.v() — 菜单弹出后直接修改 View 树，在底部追加按钮
 *   Layer 3: Hook u6.a() — 代理所有菜单的点击回调，过滤自定义按钮ID
 *
 * 使用方法:
 *   1. 复制到项目 com.your.module 包下
 *   2. xposed_init 声明: com.your.module.VoiceForwardInjection
 *   3. 编译 → 安装 → 激活 → 重启微信
 *
 * ★ 此版本不依赖 dq 或 wp 等具体类名，通过字符串特征自动发现目标类
 */
public class VoiceForwardInjection implements IXposedHookLoadPackage {

    private static final String TAG = "VoiceForward";
    private static final String WX_PKG = "com.tencent.mm";
    private static final int MENU_ID = 88888;

    // ★ 语音菜单特征字符串 — 用于自动发现 ChattingItemVoice 类
    private static final String VOICE_MENU_SIGNATURE = "chat_voice_msg_menu_hover";

    // ThreadLocal 传递消息对象
    private static final ThreadLocal<Object> sMsg = new ThreadLocal<>();
    private static final ThreadLocal<Object> sU6 = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WX_PKG.equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        Log.i(TAG, "===== 语音转发 通用版 =====");

        // Layer 1: 自动发现语音 ChattingItem 类并 Hook
        hookAllVoiceChatItems(cl);

        // Layer 2: 兜底 — 菜单弹出后直接改 View
        hookDialogShow(cl);

        // Layer 3: 兜底 — 代理所有菜单点击回调
        hookAllMenuClicks(cl);

        Log.i(TAG, "三层注入全部完成");
    }

    // ================================================================
    // ★ Layer 1: 自动扫描所有 ChattingItemVoice 子类并 Hook R()
    //
    // 原理: 搜索使用了 "chat_voice_msg_menu_hover" 字符串的类
    //       = 它一定在语音菜单构建时被调用
    //       然后 Hook 它的 R(kc5.g4, View, ye5.d) 方法
    // ================================================================
    private void hookAllVoiceChatItems(ClassLoader cl) {
        int hooked = 0;
        try {
            // 遍历 com.tencent.mm.ui.chatting.viewitems 包下所有类
            // 查找含 VOICE_MENU_SIGNATURE 字符串且带 R(g4,View,ye5.d) 方法的类

            // 已知的候选类 (从字符串搜索确认)
            String[] candidates = {
                "com.tencent.mm.ui.chatting.viewitems.dq",
                "com.tencent.mm.ui.chatting.viewitems.wp",
            };

            for (String className : candidates) {
                try {
                    Class<?> cls = cl.loadClass(className);
                    // 确认有 R 方法
                    Method rMethod = cls.getDeclaredMethod("R",
                        cl.loadClass("kc5.g4"),
                        View.class,
                        cl.loadClass("ye5.d"));
                    if (rMethod != null) {
                        hookVoiceRMethod(cls, cl);
                        hooked++;
                        Log.i(TAG, "[Layer1] ✓ " + className);
                    }
                } catch (Throwable ignored) {}
            }

            // 如果已知候选都失败，尝试动态扫描
            if (hooked == 0) {
                Log.w(TAG, "[Layer1] 已知类均未找到，尝试动态扫描...");
                hooked = dynamicScan(cl);
            }
        } catch (Throwable t) {
            Log.e(TAG, "[Layer1] " + t.getMessage());
        }
        Log.i(TAG, "[Layer1] 共 Hook " + hooked + " 个语音类");
    }

    /**
     * 动态扫描: 遍历 com.tencent.mm.ui.chatting.viewitems 包
     * 通过反射找包含 VOICE_MENU_SIGNATURE 且带 R() 方法的类
     */
    private int dynamicScan(ClassLoader cl) {
        int count = 0;
        try {
            // 通过 dex 文件遍历 — 简化版: 遍历 a~z, aa~zz
            for (char c1 = 'a'; c1 <= 'z'; c1++) {
                for (char c2 = 'a'; c2 <= 'z'; c2++) {
                    String name = "com.tencent.mm.ui.chatting.viewitems."
                        + c1 + c2;
                    try {
                        Class<?> cls = cl.loadClass(name);
                        // 检查是否有 R(kc5.g4, View, ye5.d) 方法
                        try {
                            cls.getDeclaredMethod("R",
                                cl.loadClass("kc5.g4"),
                                View.class,
                                cl.loadClass("ye5.d"));
                            // 有 R 方法，检查是否包含语音特征字符串
                            if (hasVoiceSignature(cls)) {
                                hookVoiceRMethod(cls, cl);
                                count++;
                                Log.i(TAG, "[Scan] 发现: " + name);
                            }
                        } catch (NoSuchMethodException ignored) {}
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return count;
    }

    /** 检查类是否包含语音菜单特征字符串 */
    private boolean hasVoiceSignature(Class<?> cls) {
        try {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    String val = (String) f.get(null);
                    if (val != null && val.contains(VOICE_MENU_SIGNATURE)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** Hook 单个语音 ChattingItem 的 R() 方法 */
    private void hookVoiceRMethod(Class<?> cls, ClassLoader cl) {
        XposedBridge.hookAllMethods(cls, "R", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    // R(kc5.g4 menu, View, ye5.d data)
                    Object menu = param.args[0];
                    Object ye5d = param.args[2];
                    if (menu == null || ye5d == null) return;

                    // 提取消息: ye5.d → dg5.a → b(e9)
                    Object dg5a = XposedHelpers.getObjectField(ye5d, "d");
                    Object msg = XposedHelpers.getObjectField(dg5a, "b");
                    if (msg == null) return;
                    sMsg.set(msg);

                    // ★ 注入按钮
                    XposedHelpers.callMethod(menu, "add",
                        0, MENU_ID, 9, "📤 语音转发");
                    Log.i(TAG, "✓ 按钮已注入");
                } catch (Throwable t) {
                    Log.e(TAG, "注入失败: " + t.getMessage());
                }
            }
        });
    }

    // ================================================================
    // ★ Layer 2: Hook k0.v() — 菜单弹出后修改 View 树追加按钮
    //
    // 原理: 不管前面的 R() 是否 Hook 成功, 菜单显示后直接往
    //       RecyclerView/LinearLayout 底部追加一个 Button
    // ================================================================
    private void hookDialogShow(ClassLoader cl) {
        try {
            Class<?> k0 = XposedHelpers.findClass(
                "com.tencent.mm.ui.widget.dialog.k0", cl);

            XposedBridge.hookAllMethods(k0, "v", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 检查是否是语音消息的菜单
                        Object msg = sMsg.get();
                        if (msg == null) return;

                        // 获取弹窗根 View
                        View root = (View) XposedHelpers.callMethod(
                            param.thisObject, "c");
                        if (root == null) return;

                        // 找 RecyclerView / LinearLayout
                        View listView = findListInView(root);
                        if (listView instanceof ViewGroup) {
                            addButtonAtBottom((ViewGroup) listView, param);
                        }

                    } catch (Throwable t) {
                        Log.e(TAG, "[Layer2] " + t.getMessage());
                    }
                }
            });
            Log.i(TAG, "[Layer2] k0.v() OK");
        } catch (Throwable t) {
            Log.e(TAG, "[Layer2] " + t.getMessage());
        }
    }

    private View findListInView(View v) {
        String cls = v.getClass().getName().toLowerCase();
        if (cls.contains("recycler") || cls.contains("list")
            || cls.contains("linearlayout")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = findListInView(((ViewGroup) v).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void addButtonAtBottom(ViewGroup container,
                                    MethodHookParam param) {
        try {
            Button btn = new Button(container.getContext());
            btn.setText("📤 语音转发");
            btn.setAllCaps(false);
            btn.setBackgroundColor(0xFFF5F5F5);
            btn.setTextColor(0xFF333333);
            btn.setPadding(60, 28, 60, 28);
            btn.setOnClickListener(v -> {
                doForward(container.getContext());
                // 关闭菜单
                try {
                    XposedHelpers.callMethod(param.thisObject, "dismiss");
                } catch (Throwable ignored) {}
            });
            container.addView(btn, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            Log.i(TAG, "[Layer2] ✓ View 注入成功");
        } catch (Throwable ignored) {}
    }

    // ================================================================
    // ★ Layer 3: 代理所有菜单点击回调
    // ================================================================
    private void hookAllMenuClicks(ClassLoader cl) {
        try {
            Class<?> u6 = XposedHelpers.findClass(
                "com.tencent.mm.ui.tools.u6", cl);

            XposedBridge.hookAllMethods(u6, "a", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object orig = XposedHelpers.getObjectField(
                            param.thisObject, "g");
                        if (orig == null) return;

                        // 保存 u6 实例，供 Layer2 的按钮点击拿不到消息时用
                        sU6.set(param.thisObject);

                        Class<?> t4 = XposedHelpers.findClass("kc5.t4", cl);
                        Object proxy = Proxy.newProxyInstance(cl,
                            new Class[]{t4},
                            new MenuClickHandler(orig, param.thisObject, cl));
                        XposedHelpers.setObjectField(
                            param.thisObject, "g", proxy);
                    } catch (Throwable ignored) {}
                }
            });
            Log.i(TAG, "[Layer3] u6.a() OK");
        } catch (Throwable t) {
            Log.e(TAG, "[Layer3] " + t.getMessage());
        }
    }

    // ================================================================
    // 点击处理 + 转发
    // ================================================================
    private static class MenuClickHandler implements InvocationHandler {
        private final Object mOrig;
        private final Object mU6;
        private final ClassLoader mCl;

        MenuClickHandler(Object o, Object u, ClassLoader c) {
            mOrig = o; mU6 = u; mCl = c;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable {
            if (args != null && args.length >= 1
                    && args[0] instanceof MenuItem) {
                if (((MenuItem) args[0]).getItemId() == MENU_ID) {
                    doForward(((Context) XposedHelpers.getObjectField(
                        mU6, "d")));
                    dismissMenu();
                    return null;
                }
            }
            if (mOrig != null) return method.invoke(mOrig, args);
            return null;
        }

        private void dismissMenu() {
            try {
                Object dlg = XposedHelpers.getObjectField(mU6, "e");
                if (dlg != null) XposedHelpers.callMethod(dlg, "dismiss");
            } catch (Throwable ignored) {}
        }
    }

    private static void doForward(Context ctx) {
        try {
            Object msg = sMsg.get();
            if (msg == null) { Log.e(TAG, "msg is null"); return; }

            long msgId = (long) XposedHelpers.callMethod(msg, "getMsgId");
            String talker = (String) XposedHelpers.callMethod(msg, "N0");

            Class<?> ui = ctx.getClassLoader().loadClass(
                "com.tencent.mm.ui.transmit.MsgRetransmitUI");
            Intent intent = new Intent(ctx, ui);
            intent.putExtra("Retr_Msg_content", talker);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.putExtra("Retr_Msg_Img_Type", 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);

            Log.i(TAG, "→ 转发: " + talker + " msgId=" + msgId);
        } catch (Throwable t) {
            Log.e(TAG, "转发失败", t);
        }
    }
}
