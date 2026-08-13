package com.leshao.v3.ai;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;

import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.wm.utils.WmReflect;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ChatHooks {
    private static final String TAG = "ChatHooks";
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static String currentTalker = "";
    private static String lastPreloadedTalker = "";
    private static volatile boolean chatWindowOpen = false;
    private static Activity chatActivity;
    private static ClassLoader sClassLoader;

    public static String currentTalker() { return currentTalker; }
    public static boolean isChatWindowOpen() { return chatWindowOpen; }
    public static Activity currentActivity() { return chatActivity; }

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        sClassLoader = lp.classLoader;
        hookMessageInsert(lp);
        hookChatSession(lp);
        LogWriter.log(TAG, "install 完成: master=" + AiConfig.masterEnabled()
                + " summary=" + AiConfig.summaryEnabled() + " reply=" + AiConfig.replyEnabled());
    }

    private static void hookMessageInsert(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> storage = XposedHelpers.findClass(AiConst.CLS_STORAGE, lp.classLoader);
            Class<?> msgCls = XposedHelpers.findClass(AiConst.CLS_MSG, lp.classLoader);
            LogWriter.log(TAG, "消息入库Hook: storage=" + storage.getName() + " msg=" + msgCls.getName());

            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object msg = param.args[0];
                        if (!AiConfig.masterEnabled() || !AiConfig.replyEnabled()) return;
                        WxReflect.dumpMsgInfoFields(msg);
                        if (!WxReflect.isIncomingText(msg)) return;
                        String talker = WxReflect.talker(msg);
                        String content = WxReflect.content(msg);
                        long time = WxReflect.createTime(msg);
                        LogWriter.log(TAG, "消息入库Hook: 收到文本 talker=" + talker + " 触发推荐回复");
                        ChatMemory.append(talker, new MessageReader.ChatMsg("other", "", content, time));
                        MAIN.post(() -> ReplyFeature.onIncoming(talker, content, lp.classLoader));
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "消息入库Hook: 处理失败: " + t.getMessage());
                    }
                }
            };

            // 实际入库走 I9(e9, ...)（日志 f9.I9 called 2 params），I9 优先，H9 兜底
            Method insert = findInsertMethod(storage, msgCls, AiConst.M_INSERT2);
            if (insert == null) insert = findInsertMethod(storage, msgCls, AiConst.M_INSERT);
            if (insert != null) {
                XposedBridge.hookMethod(insert, hook);
                LogWriter.log(TAG, "消息入库Hook: 挂载完成 (" + insert.getName() + " "
                        + insert.getParameterCount() + " params)");
                return;
            }
            LogWriter.log(TAG, "消息入库Hook: I9/H9 都未找到");
        } catch (Throwable t) {
            LogWriter.log(TAG, "消息入库Hook: 整体失败: " + t.getMessage());
            android.util.Log.e("WxAi", "消息入库 Hook 挂载失败", t);
        }
    }

    private static void hookChatSession(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> base = XposedHelpers.findClass(AiConst.CLS_BASE_FRAGMENT, lp.classLoader);
            LogWriter.log(TAG, "会话Fragment Hook: base=" + base.getName());

            Method onCreate = findMethodInHierarchy(base, "onCreate", Bundle.class);
            if (onCreate != null) {
                XposedBridge.hookMethod(onCreate, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            setTalker(resolveTalkerFromFragment(param.thisObject));
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "onCreate 解析 talker 失败: " + t.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "会话Fragment Hook: onCreate 挂载完成");
            } else {
                LogWriter.log(TAG, "会话Fragment Hook: onCreate 未找到");
            }

            hookChatWindowBall(lp);
        } catch (Throwable t) {
            LogWriter.log(TAG, "会话Fragment Hook: 失败: " + t.getMessage());
        }
    }

    private static final Runnable showBallTask = new Runnable() {
        @Override public void run() {
            if (AiConfig.masterEnabled() && chatActivity != null) {
                FloatingBall.show(chatActivity, reflectClassLoader);
            }
        }
    };
    private static final Runnable hideBallTask = new Runnable() {
        @Override public void run() {
            FloatingBall.hide();
            ReplyBanner.hide();
        }
    };
    private static ClassLoader reflectClassLoader;

    private static void hookChatWindowBall(XC_LoadPackage.LoadPackageParam lp) {
        reflectClassLoader = lp.classLoader;
        try {
            Class<?> frag = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", lp.classLoader);
            Method m0 = frag.getDeclaredMethod("M0");
            XposedBridge.hookMethod(m0, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    chatWindowOpen = true;
                    Activity act = fragmentActivity(param.thisObject);
                    if (act == null) { LogWriter.log(TAG, "悬浮球: M0 未获取到 Activity"); return; }
                    chatActivity = act;
                    setTalker(resolveTalkerFromActivity(act, param.thisObject));
                    LogWriter.log(TAG, "悬浮球: M0 聊天窗口打开 Activity=" + act.getClass().getSimpleName()
                            + " talker=" + currentTalker);
                    MAIN.removeCallbacks(hideBallTask);
                    MAIN.removeCallbacks(showBallTask);
                    MAIN.postDelayed(showBallTask, 300);
                }
            });
            LogWriter.log(TAG, "悬浮球: M0 挂载完成");

            Method o0 = frag.getDeclaredMethod("O0");
            XposedBridge.hookMethod(o0, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    chatWindowOpen = false;
                    LogWriter.log(TAG, "悬浮球: O0 聊天窗口关闭, 延迟移除悬浮球");
                    MAIN.removeCallbacks(showBallTask);
                    MAIN.removeCallbacks(hideBallTask);
                    MAIN.postDelayed(hideBallTask, 800);
                }
            });
            LogWriter.log(TAG, "悬浮球: O0 挂载完成");
        } catch (Throwable t) {
            LogWriter.log(TAG, "悬浮球: ChattingUIFragment M0/O0 hook 失败: " + t.getMessage());
        }
    }

    private static Activity fragmentActivity(Object fragment) {
        try {
            Object act = XposedHelpers.callMethod(fragment, "getActivity");
            if (act instanceof Activity) return (Activity) act;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void preload(String talker) {
        new Thread(() -> {
            try {
                MessageReader reader = new MessageReader(sClassLoader);
                ChatMemory.preload(talker, reader.readRecent(talker, AiConfig.memoryCount()));
            } catch (Throwable ignored) {}
        }).start();
    }

    private static void setTalker(String talker) {
        if (!isValidTalker(talker)) {
            LogWriter.log(TAG, "setTalker: 无效 talker=" + talker + "，忽略");
            return;
        }
        if (!talker.equals(currentTalker)) {
            currentTalker = talker;
            LogWriter.log(TAG, "currentTalker=" + talker);
        }
        if (!talker.equals(lastPreloadedTalker)) {
            lastPreloadedTalker = talker;
            preload(talker);
        }
    }

    private static boolean isValidTalker(String talker) {
        if (talker == null || talker.isEmpty()) return false;
        String self = ModuleConfig.getCurrentWxid();
        if (self != null && !self.isEmpty() && self.equals(talker)) return false;
        return true;
    }

    private static String resolveTalkerFromFragment(Object fragment) {
        try {
            Object args = XposedHelpers.callMethod(fragment, "getArguments");
            if (args instanceof Bundle) {
                String s = ((Bundle) args).getString("Chat_User");
                if (isValidTalker(s)) return s;
            }
        } catch (Throwable ignored) {}
        try {
            Object chatCtx = XposedHelpers.getObjectField(fragment, AiConst.F_CHAT_CONTEXT);
            if (chatCtx != null) {
                String t = (String) XposedHelpers.callMethod(chatCtx, AiConst.M_GET_TALKER_CTX);
                if (isValidTalker(t)) return t;
            }
        } catch (Throwable ignored) {}
        String s = WmReflect.getChatUserFromFragment(fragment);
        if (isValidTalker(s)) return s;
        return "";
    }

    private static String resolveTalkerFromActivity(Activity act, Object fragment) {
        if (act != null) {
            String s = WmReflect.getCurrentChatUser(act.getIntent());
            if (isValidTalker(s)) return s;
        }
        return resolveTalkerFromFragment(fragment);
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Method findInsertMethod(Class<?> clazz, Class<?> msgCls, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0].equals(msgCls)) return m;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    public static void fillInput(Activity act, String text) {
        try {
            Object input = findInputView(act);
            if (input == null) {
                LogWriter.log(TAG, "fillInput: 未找到输入框");
                return;
            }
            LogWriter.log(TAG, "fillInput: 目标=" + input.getClass().getName());
            MAIN.post(() -> reflectSetText(input, text));
        } catch (Throwable t) { android.util.Log.e("WxAi", "填入输入框失败", t); }
    }

    public static void fillAndSend(Activity act, String text) {
        fillInput(act, text);
        MAIN.postDelayed(() -> clickSend(act), 400);
    }

    public static String readInputText(Activity act) {
        if (act == null) return null;
        Object input = findInputView(act);
        if (input == null) {
            LogWriter.log(TAG, "readInputText: 未找到输入框");
            return null;
        }
        String txt = reflectGetText(input);
        LogWriter.log(TAG, "readInputText: 类=" + input.getClass().getName()
                + " 长度=" + (txt == null ? -1 : txt.length()));
        return txt;
    }

    public static Object findInputView(Activity act) {
        if (act == null) return null;
        try {
            android.view.View decor = act.getWindow().getDecorView();
            Object footer = findFooter(act);
            if (footer != null) {
                LogWriter.log(TAG, "findInputView: 找到 ChatFooter=" + footer.getClass().getName());
                // m 字段：MMFlexEditText（真实输入框，非 EditText 子类，需反射读写）
                Object m = getFieldOrNull(footer, "m");
                if (m != null) {
                    LogWriter.log(TAG, "findInputView: m 字段命中=" + m.getClass().getName());
                    return m;
                }
                Object l4 = getFieldOrNull(footer, "l4");
                if (l4 != null) {
                    LogWriter.log(TAG, "findInputView: l4 字段命中=" + l4.getClass().getName());
                    return l4;
                }
                if (footer instanceof android.view.View) {
                    EditText et = findEditTextRecursive((android.view.View) footer);
                    if (et != null) {
                        LogWriter.log(TAG, "findInputView: ChatFooter 内递归命中 " + et.getClass().getName());
                        return et;
                    }
                }
            } else {
                LogWriter.log(TAG, "findInputView: 未找到 ChatFooter（" + AiConst.CLS_CHAT_FOOTER + "）");
            }
            EditText mm = findBestMMEditText(decor);
            if (mm != null) return mm;
            return findEditTextRecursive(decor);
        } catch (Throwable t) {
            LogWriter.log(TAG, "findInputView 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static Object getFieldOrNull(Object obj, String field) {
        try {
            return XposedHelpers.getObjectField(obj, field);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getFieldOrNull: " + field + " 失败 " + t.getClass().getSimpleName());
            return null;
        }
    }

    private static String reflectGetText(Object view) {
        if (view == null) return null;
        if (view instanceof android.widget.EditText) {
            return ((android.widget.EditText) view).getText().toString();
        }
        if (view instanceof android.widget.TextView) {
            CharSequence cs = ((android.widget.TextView) view).getText();
            return cs != null ? cs.toString() : "";
        }
        try {
            Object t = XposedHelpers.callMethod(view, "getText");
            return t != null ? t.toString() : "";
        } catch (Throwable e) {
            LogWriter.log(TAG, "reflectGetText 失败: " + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        return null;
    }

    private static void reflectSetText(Object view, String text) {
        if (view == null) return;
        if (view instanceof android.widget.EditText) {
            ((android.widget.EditText) view).setText(text);
            try { ((android.widget.EditText) view).setSelection(text.length()); } catch (Throwable ignored) {}
            return;
        }
        if (view instanceof android.widget.TextView) {
            ((android.widget.TextView) view).setText(text);
            return;
        }
        try {
            XposedHelpers.callMethod(view, "setText", text);
            LogWriter.log(TAG, "reflectSetText: 成功(String)");
        } catch (Throwable e1) {
            try {
                XposedHelpers.callMethod(view, "setText", (CharSequence) text);
                LogWriter.log(TAG, "reflectSetText: 成功(CharSequence)");
            } catch (Throwable e2) {
                LogWriter.log(TAG, "reflectSetText 失败: " + e2.getClass().getSimpleName() + ":" + e2.getMessage());
            }
        }
    }

    private static EditText findBestMMEditText(android.view.View v) {
        List<EditText> list = new ArrayList<>();
        collectMMEditText(v, list);
        LogWriter.log(TAG, "findBestMMEditText: 共找到 " + list.size() + " 个 MMEditText");
        for (EditText et : list) {
            if (et.getVisibility() == android.view.View.VISIBLE && et.getText().length() > 0) {
                LogWriter.log(TAG, "findBestMMEditText: 选中可见非空, 长度=" + et.getText().length());
                return et;
            }
        }
        for (EditText et : list) {
            if (et.getVisibility() == android.view.View.VISIBLE) return et;
        }
        if (!list.isEmpty()) return list.get(0);
        return null;
    }

    private static void collectMMEditText(android.view.View v, List<EditText> out) {
        if (v instanceof EditText && "com.tencent.mm.ui.widget.MMEditText".equals(v.getClass().getName())) {
            out.add((EditText) v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectMMEditText(g.getChildAt(i), out);
            }
        }
    }

    private static EditText findEditText(Activity act) {
        if (act == null) return null;
        return findEditTextRecursive(act.getWindow().getDecorView());
    }

    private static EditText findEditTextRecursive(android.view.View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText r = findEditTextRecursive(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private static void clickSend(Activity act) {
        try {
            Object footer = findFooter(act);
            if (footer != null) {
                for (String m : new String[]{"send", "D", "H", "G"}) {
                    try { XposedHelpers.callMethod(footer, m); return; }
                    catch (Throwable ignored) {}
                }
            }
            android.view.View send = findSendButton(act.getWindow().getDecorView());
            if (send != null) send.performClick();
        } catch (Throwable t) { android.util.Log.e("WxAi", "自动发送失败", t); }
    }

    private static Object findFooter(Activity act) {
        try {
            ClassLoader cl = act.getClassLoader();
            Class<?> footer = XposedHelpers.findClass(AiConst.CLS_CHAT_FOOTER, cl);
            return findViewByType(act.getWindow().getDecorView(), footer);
        } catch (Throwable t) { return null; }
    }

    private static Object findViewByType(android.view.View v, Class<?> type) {
        if (type.isInstance(v)) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                Object r = findViewByType(g.getChildAt(i), type);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static android.view.View findSendButton(android.view.View v) {
        if (v instanceof android.widget.Button || v instanceof android.widget.ImageButton) {
            CharSequence txt = v.getContentDescription();
            if (txt != null && (txt.toString().contains("发送") || txt.toString().contains("Send")))
                return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.View r = findSendButton(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
