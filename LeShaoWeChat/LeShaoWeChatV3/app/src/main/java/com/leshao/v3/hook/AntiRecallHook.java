package com.leshao.v3.hook;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.service.StatsCollector;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class AntiRecallHook {

    private static final String TAG = "AntiRecallHook";
    private static volatile boolean sEnabled = false;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    /**
     * Hook 消息撤回，记录撤回内容
     */
    public static void hook() {
        if (!ContextManager.isReady()) {
            LogWriter.log(TAG, "hook ABORTED: ContextManager not ready");
            return;
        }

        ClassLoader cl = ContextManager.getClassLoader();

        for (String className : new String[]{
            "com.tencent.mm.modelmulti.p",
            "com.tencent.mm.modelmulti.q",
        }) {
            try {
                Class<?> c = cl.loadClass(className);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length >= 2) {
                        XposedHelpers.findAndHookMethod(className, cl, m.getName(),
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (!sEnabled) return;
                                    try {
                                        Object msgObj = findRecallMsg(param.args);
                                        if (msgObj != null) {
                                            String talker = getField(msgObj, "field_talker", "getTalker");
                                            String content = getField(msgObj, "field_content", "getContent");
                                            if (content != null) {
                                                StatsCollector.recordRecall(talker, content);
                                                LogWriter.log(TAG, "recall intercepted: " + talker);
                                            }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                            });
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    private static Object findRecallMsg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            String cn = arg.getClass().getName();
            if (cn.contains("Recall") || cn.contains("Revoke")) return arg;
        }
        return null;
    }

    private static String getField(Object obj, String... names) {
        for (String n : names) {
            try { Object v = obj.getClass().getDeclaredField(n).get(obj); return v != null ? v.toString() : null; }
            catch (Throwable ignored) {}
            try { Object v = obj.getClass().getMethod(n).invoke(obj); return v != null ? v.toString() : null; }
            catch (Throwable ignored) {}
        }
        return null;
    }
}
