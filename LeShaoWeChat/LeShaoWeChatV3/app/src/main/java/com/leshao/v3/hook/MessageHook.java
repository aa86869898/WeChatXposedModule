package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static java.lang.reflect.Method sTypeMapper;

    public static void hook(ClassLoader cl) {
        sMainHandler = new Handler(Looper.getMainLooper());

        try {
            Class<?> x9Cls = cl.loadClass("e01.x9");
            Class<?> e9Cls = cl.loadClass("com.tencent.mm.storage.e9");

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    Class<?> p0Cls = m.getParameterTypes()[1];
                    XposedHelpers.findAndHookMethod(x9Cls, "n", e9Cls, p0Cls,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0]);
                            }
                        });
                    LogWriter.log(TAG, "n(e9,p0) OK");
                    break;
                }
            }

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("C") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedHelpers.findAndHookMethod(x9Cls, "C", e9Cls,
                        new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                onMessage(p.args[0]);
                            }
                        });
                    LogWriter.log(TAG, "C(e9) OK");
                    break;
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "FAIL: " + t);
        }
    }

    static void onMessage(Object e9) {
        try {
            int rawType = (int) XposedHelpers.callMethod(e9, "getType");
            int type = mapType(rawType);
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = (String) XposedHelpers.callMethod(e9, "j");

            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")))
                return;

            sCount++;
            LogWriter.log(TAG, "#" + sCount
                + " type=" + rawType + "->" + type
                + " isSend=" + (int) XposedHelpers.callMethod(e9, "O0")
                + " talker=" + trunc(talker, 20)
                + " content=" + trunc(content, 40));

            final int fType = type;
            final String fTalker = talker;
            final String fContent = content;
            sMainHandler.post(() -> {
                try {
                    TTSBroadcaster.handleMessageRaw(fType, fTalker, fContent);
                } catch (Throwable e) {
                    LogWriter.log("TTS", "err: " + e.getMessage());
                }
            });

        } catch (Throwable t) {
            LogWriter.log(TAG, "err: " + t);
        }
    }

    static String trunc(String s, int m) {
        return s == null ? "" : s.length() > m ? s.substring(0, m) + "..." : s;
    }

    static int mapType(int rawType) {
        if ((rawType & 0xFFFFFF00) == 0) return rawType;
        return rawType & 0xFF;
    }
}
