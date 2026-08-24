package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import com.leshao.v3.Logger;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;
import android.media.MediaRecorder;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.HashSet;

/**
 * [功能34/38] 通话录音 + 自动接听 — 修正版
 * =========================================
 * 
 * 正确的Hook点（经深度逆向验证）:
 *   widget.k.g()    → 计时器启动 = 通话已接通 → 启动录音
 *   model.c0.d()    → 模型层结束通话 → 停止录音
 *   widget.k.d()    → 设置视频小窗View，不是接听
 *   widget.k.s1()   → 空方法
 *   widget.k.k()    → 构造函数，不是挂断
 * 
 * #34 通话录音: Hook widget.k.g() → MediaRecorder
 * #38 自动接听: Hook CheckVoipCSIsStartedEvent
 */
public class CallFeatures {

    private static volatile boolean sEnabled = true;
    private static MediaRecorder recorder = null;
    private static boolean isRecording = false;
    private static String currentCaller = "";
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
    public static final Set<String> autoAnswerList = new HashSet<>();

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        if (!sEnabled) return;
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.callFeaturesEnabled) return;
        loadAutoAnswerList();
        hookCallRecord(cl);
        hookAutoAnswer(cl);
        Logger.i("[CallFeatures] 通话录音+自动接听 Hook完成");
    }

    private static void loadAutoAnswerList() {
        String saved = com.leshao.v3.ContextManager.getPrefs()
                .getString("ls_call_auto_list", "");
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                if (!s.trim().isEmpty()) autoAnswerList.add(s.trim());
            }
        }
    }

    // ═══════════════════════════════════════════
    // #34 通话录音
    // widget.k.g() → 计时器启动=通话已连接
    // model.c0.d() → 模型层结束通话
    // ═══════════════════════════════════════════
    private static void hookCallRecord(ClassLoader cl) {
        try {
            Class<?> voipWidget = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.voip.widget.k", cl);

            XposedBridge.hookAllMethods(voipWidget, "g", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int status = XposedHelpers.getIntField(param.thisObject, "i");
                        if (status == 6 || status == 7 || status == 260 || status == 261) {
                            if (!isRecording) {
                                try {
                                    Object talker = XposedHelpers.getObjectField(
                                            param.thisObject, "m");
                                    if (talker != null) {
                                        currentCaller = (String) XposedHelpers.callMethod(
                                                talker, "d1");
                                    }
                                } catch (Throwable ignored) {
                                    currentCaller = "unknown";
                                }
                                startRecording();
                            }
                        }
                    } catch (Throwable t) {
                        Logger.e("[CallRecord] 异常: " + t.getMessage());
                    }
                }
            });
            Logger.i("[CallRecord] widget.k.g() Hook完成");
        } catch (Throwable t) {
            Logger.w("[CallRecord] widget.k失败: " + t.getMessage());
        }

        try {
            Class<?> voipModel = XposedHelpers.findClass(
                    "com.tencent.mm.plugin.voip.model.c0", cl);

            XposedBridge.hookAllMethods(voipModel, "d", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        stopRecording();
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            Logger.i("[CallRecord] model.c0.d() Hook完成");
        } catch (Throwable t) {
            Logger.w("[CallRecord] model.c0失败: " + t.getMessage());
        }
    }

    private static void startRecording() {
        if (isRecording) return;
        try {
            File dir = new File("/sdcard/LeShaoV3Logs/calls/");
            dir.mkdirs();
            String fileName = "call_" + sdf.format(new Date()) + "_" +
                    currentCaller.replace("@", "_").replace("/", "_") + ".amr";
            File outFile = new File(dir, fileName);

            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            isRecording = true;

            Logger.i("[CallRecord] 录音开始: " + outFile.getName());
        } catch (Throwable t) {
            Logger.e("[CallRecord] 录音启动失败: " + t.getMessage());
            recorder = null;
        }
    }

    private static void stopRecording() {
        if (!isRecording || recorder == null) return;
        try {
            recorder.stop();
            recorder.release();
            Logger.i("[CallRecord] 录音已停止");
        } catch (Throwable t) {
            Logger.e("[CallRecord] 停止失败: " + t.getMessage());
        } finally {
            recorder = null;
            isRecording = false;
        }
    }

    // ═══════════════════════════════════════════
    // #38 自动接听
    // ═══════════════════════════════════════════
    private static void hookAutoAnswer(ClassLoader cl) {
        try {
            Class<?> checkEvent = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.CheckVoipCSIsStartedEvent", cl);

            XposedBridge.hookAllMethods(checkEvent, "callback", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (autoAnswerList.isEmpty()) return;
                    try {
                        Object event = param.args[0];
                        Object data = XposedHelpers.getObjectField(event, "data");
                        if (data == null) return;

                        String caller = "";
                        try {
                            caller = (String) XposedHelpers.getObjectField(data, "talker");
                        } catch (Throwable ignored) {}

                        if (caller != null && autoAnswerList.contains(caller)) {
                            Logger.i("[AutoAnswer] 自动接听白名单来电: " + caller);
                            performAutoAnswer();
                        }
                    } catch (Throwable ignored) {}
                }
            });
            Logger.i("[AutoAnswer] CheckVoipCSIsStartedEvent Hook完成");
        } catch (Throwable t) {
            Logger.w("[AutoAnswer] Hook失败: " + t.getMessage());
        }
    }

    private static void performAutoAnswer() {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(new Runnable() {
            public void run() {
                try {
                    Logger.i("[AutoAnswer] 执行自动接听...");
                    tryAccept();
                } catch (Throwable t) {
                    Logger.e("[AutoAnswer] 执行失败: " + t.getMessage());
                }
            }
        }, 2000);
    }

    /**
     * 真实接听(反编译确认):
     *   底层方法 h2.a(boolean onlyAudio, boolean isVideo) — "accept onlyAudio:"
     *   入口 d0.h()(视频) / d0.j()->d0.g0()(语音) / d0.D(int callType)(模拟, callType==1 语音)
     * 多路尝试: 任一成功即返回; 全部失败则等 signature_dump.txt 确认 static/实例。
     */
    private static void tryAccept() {
        ClassLoader cl = ContextManager.getClassLoader();
        boolean answered = false;

        // 方案1: 底层接听 h2.a(onlyAudio=true, isVideo=false) = 语音接听
        Class<?> h2 = VersionCompat.findVoipAcceptClass(cl);
        if (h2 != null) {
            try {
                XposedHelpers.callStaticMethod(h2, "a", true, false);
                Logger.i("[AutoAnswer] 成功: h2.a(true,false) 语音接听");
                answered = true;
            } catch (Throwable t) {
                Logger.w("[AutoAnswer] h2.a 静态调用失败(可能为实例方法): " + t.getMessage());
            }
        } else {
            Logger.w("[AutoAnswer] 未找到 h2 类");
        }

        // 方案2: 入口 d0.g0() 语音
        if (!answered) {
            Class<?> d0 = VersionCompat.findVoipEntryClass(cl);
            if (d0 != null) {
                try {
                    XposedHelpers.callStaticMethod(d0, "g0");
                    Logger.i("[AutoAnswer] 成功: d0.g0() 语音接听");
                    answered = true;
                } catch (Throwable t) {
                    Logger.w("[AutoAnswer] d0.g0() 失败: " + t.getMessage());
                }
                // 方案3: d0.D(1) 模拟语音接听
                if (!answered) {
                    try {
                        XposedHelpers.callStaticMethod(d0, "D", 1);
                        Logger.i("[AutoAnswer] 成功: d0.D(1) 语音接听");
                        answered = true;
                    } catch (Throwable t) {
                        Logger.w("[AutoAnswer] d0.D(1) 失败: " + t.getMessage());
                    }
                }
            } else {
                Logger.w("[AutoAnswer] 未找到 d0 入口类");
            }
        }

        if (!answered) {
            Logger.w("[AutoAnswer] 全部接听方案失败, 待 signature_dump 确认 h2/d0 静态或实例方法");
        }
    }
}
