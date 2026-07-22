package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.MenuItem;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class VoiceForwardHook {

    private static final String TAG = "VoiceFwd";
    private static final String VOICE_SIGNATURE = "chat_voice_msg_menu_hover";

    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = false;

    private static final ThreadLocal<Object> sVoiceMsg = new ThreadLocal<>();
    private static volatile Activity sChatAct;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        if (!ContextManager.isReady()) { LogWriter.log(TAG, "not ready"); return; }

        ClassLoader cl = ContextManager.getClassLoader();
        hookVoiceItemR(cl);
        hookMenuClicks(cl);
        hookChattingUI(cl);

        sHooked = true;
        LogWriter.log(TAG, "hooks installed");
    }

    private static void hookVoiceItemR(ClassLoader cl) {
        for (String name : new String[]{
            "com.tencent.mm.ui.chatting.viewitems.dq",
            "com.tencent.mm.ui.chatting.viewitems.wp",
        }) {
            try {
                Class<?> cls = cl.loadClass(name);
                cls.getDeclaredMethod("R",
                    cl.loadClass("kc5.g4"), android.view.View.class, cl.loadClass("ye5.d"));
                XposedBridge.hookAllMethods(cls, "R", new VoiceItemRHook());
                LogWriter.log(TAG, "hooked " + name);
                return;
            } catch (Throwable ignored) {}
        }
        scanViewitems(cl);
    }

    private static void scanViewitems(ClassLoader cl) {
        for (char c1 = 'a'; c1 <= 'z'; c1++) {
            for (char c2 = 'a'; c2 <= 'z'; c2++) {
                String name = "com.tencent.mm.ui.chatting.viewitems." + c1 + c2;
                try {
                    Class<?> cls = cl.loadClass(name);
                    cls.getDeclaredMethod("R",
                        cl.loadClass("kc5.g4"), android.view.View.class, cl.loadClass("ye5.d"));
                    if (hasVoiceSig(cls)) {
                        XposedBridge.hookAllMethods(cls, "R", new VoiceItemRHook());
                        LogWriter.log(TAG, "scan found: " + name);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean hasVoiceSig(Class<?> cls) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                try {
                    String val = (String) f.get(null);
                    if (val != null && val.contains(VOICE_SIGNATURE)) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static void hookMenuClicks(ClassLoader cl) {
        try {
            Class<?> u6 = XposedHelpers.findClass("com.tencent.mm.ui.tools.u6", cl);
            XposedBridge.hookAllMethods(u6, "a", new MenuClickHook(cl));
            LogWriter.log(TAG, "menu click hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "menu click hook fail: " + t.getMessage());
        }
    }

    private static void doVoiceForward(Context ctx, Object msg) {
        try {
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

            LogWriter.log(TAG, "forward voice: talker=" + talker + " msgId=" + msgId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "forward err: " + t.getClass().getSimpleName()
                + " " + t.getMessage());
        }
    }

    private static void hookChattingUI(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUI", cl);
            XposedBridge.hookAllMethods(chattingUI, "onResume", new ChattingUIHook(false));
            XposedBridge.hookAllMethods(chattingUI, "onPause", new ChattingUIHook(true));
        } catch (Throwable ignored) {}
    }

    static class VoiceItemRHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!sEnabled) return;
            try {
                Object ye5d = param.args[2];
                if (ye5d == null) return;
                Object dg5a = XposedHelpers.getObjectField(ye5d, "d");
                Object msg = XposedHelpers.getObjectField(dg5a, "b");
                if (msg != null) sVoiceMsg.set(msg);
            } catch (Throwable ignored) {}
        }
    }

    static class MenuClickHook extends XC_MethodHook {
        private final ClassLoader mCl;

        MenuClickHook(ClassLoader cl) { mCl = cl; }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!sEnabled) return;
            try {
                Object orig = XposedHelpers.getObjectField(param.thisObject, "g");
                if (orig == null) return;

                Class<?> t4 = XposedHelpers.findClass("kc5.t4", mCl);
                Object proxy = Proxy.newProxyInstance(mCl, new Class[]{t4},
                    new MenuClickInterceptor(orig, param.thisObject, mCl));
                XposedHelpers.setObjectField(param.thisObject, "g", proxy);
            } catch (Throwable ignored) {}
        }
    }

    static class MenuClickInterceptor implements InvocationHandler {
        private final Object mOrig, mU6;
        private final ClassLoader mCl;

        MenuClickInterceptor(Object o, Object u, ClassLoader c) {
            mOrig = o; mU6 = u; mCl = c;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length >= 1 && args[0] instanceof MenuItem) {
                MenuItem item = (MenuItem) args[0];
                CharSequence title = item.getTitle();
                String titleStr = title != null ? title.toString() : "";

                if (titleStr.contains("转发")) {
                    Object msg = sVoiceMsg.get();
                    if (msg != null) {
                        sVoiceMsg.remove();
                        doVoiceForward((Context) XposedHelpers.getObjectField(mU6, "d"), msg);
                        try {
                            Object dlg = XposedHelpers.getObjectField(mU6, "e");
                            if (dlg != null) XposedHelpers.callMethod(dlg, "dismiss");
                        } catch (Throwable ignored) {}
                        return null;
                    }
                }
            }
            if (mOrig != null) return method.invoke(mOrig, args);
            return null;
        }
    }

    static class ChattingUIHook extends XC_MethodHook {
        private final boolean mIsPause;

        ChattingUIHook(boolean isPause) { mIsPause = isPause; }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (mIsPause) {
                if (sChatAct == param.thisObject) sChatAct = null;
            } else {
                sChatAct = (Activity) param.thisObject;
            }
        }
    }
}
