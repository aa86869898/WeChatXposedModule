package com.leshao.v3.hook;

import android.os.Handler;
import android.os.Looper;

import com.leshao.v3.LogWriter;
import com.leshao.v3.service.TTSBroadcaster;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class MessageHook {

    private static final String TAG = "MessageHook";
    private static int sCount = 0;
    private static Handler sMainHandler;
    private static java.lang.reflect.Method sTypeMapper;

    public static void hook(ClassLoader cl) {
        sMainHandler = new Handler(Looper.getMainLooper());

        try {
            Class<?> x9Cls = null;
            for (String name : new String[]{"e01.x9", "e02.x9", "e00.x9", "e01.x8", "e01.y9"}) {
                try { x9Cls = cl.loadClass(name); break; } catch (Throwable ignored) {}
            }
            if (x9Cls == null) {
                LogWriter.log(TAG, "FAIL: dispatch class not found");
                return;
            }
            Class<?> e9Cls = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Cls == null) {
                LogWriter.log(TAG, "FAIL: storage class not found");
                return;
            }

            for (java.lang.reflect.Method m : x9Cls.getDeclaredMethods()) {
                if (m.getName().equals("n") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == e9Cls) {
                    XposedBridge.hookMethod(m,
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
                    XposedBridge.hookMethod(m,
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
            int isSend = (int) XposedHelpers.callMethod(e9, "O0");
            String talker = (String) XposedHelpers.callMethod(e9, "N0");
            String content = (String) XposedHelpers.callMethod(e9, "j");

            if (content != null && (content.startsWith("<msgsource")
                || content.startsWith("<pushcontent")))
                return;

            sCount++;
            LogWriter.log(TAG, "#" + sCount
                + " type=" + rawType + "->" + type
                + " isSend=" + isSend
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

    static int mapType(int t) {
        if (t >= 268435456 || t == 74 || t == 83 || t == 84 || t == 87
            || t == 95 || t == 102 || t == 103 || t == 131 || t == 132
            || t == 1048625 || t == 16777265) return 49;
        return t;
    }
}
