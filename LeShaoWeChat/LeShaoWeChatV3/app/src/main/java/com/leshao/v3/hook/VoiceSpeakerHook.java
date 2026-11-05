package com.leshao.v3.hook;

import android.content.SharedPreferences;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 屏蔽语音消息听筒切换（v3.0.516 新增，v3.0.521 修复）。
 *
 * <p>微信 8.0.79 语音播放链路（逆向确认）：</p>
 * <pre>
 *   com.tencent.mm.ui.chatting.x0  (TAG="MicroMsg.AutoPlay", 单例 x0.a())
 *     x1(boolean isON)  ← SensorController 距离传感器回调
 *       贴近(x1=false) → G(false) + F(true)(wakelock) + K()
 *       远离(x1=true)  → F(z) + K()
 *     G(boolean)  = 仅设置 this.f779w=speakerOn, 日志 "speakerOn has been set %s"
 *     K()         = 日志 "switchSpeaker, isSpeakerOn: %b, isPlaying: %b" → i.n(f779w) 真正切换
 *   AudioDeviceFactory zl.a.a(boolean speakerOn, ...)  日志 "speakerOn: %b, type: %d"
 *     → 创建 AudioTrack/MediaPlayer 时按 speakerOn 选择 STREAM_MUSIC(3)/STREAM_VOICE_CALL(0)
 * </pre>
 *
 * <p>v3.0.520 实测问题：仅强制 {@code G(true)} 时，靠近耳边依然会<b>灭屏</b>
 * （x1(false) 内部 F(true) 获取 PROXIMITY_SCREEN_OFF_WAKE_LOCK，系统级灭屏），
 * 且语音会<b>从头重播</b>（K() 仍会执行播放器切换，切换即重建播放器）。
 * 因此本版把距离传感器回调 {@code x1(boolean)} 的屏蔽升级为<b>主路径</b>：
 * 直接在入口 return，微信完全忽略距离传感器——既不切听筒、不灭屏、也不重建播放器。
 * G(boolean)/AudioDeviceFactory 的强制 true 保留为双保险（防止其他路径把 speakerOn 置回 false）。</p>
 */
public final class VoiceSpeakerHook {

    private static final String TAG = "VoiceSpeakerLock";
    public static final String PREF_ENABLED = "voice_speaker_lock";

    /** x0.G(boolean)：设置 speakerOn 字段时的日志串，方法体唯一锚点。 */
    private static final String ANCHOR_SET =
            "speakerOn has been set %s";
    /** AudioDeviceFactory zl.a.a：创建播放设备时的日志串。 */
    private static final String ANCHOR_FACTORY =
            "speakerOn: %b, type: %d";
    /** 距离传感器回调日志串（主路径，根治灭屏/重头播放）。 */
    private static final String ANCHOR_SENSOR =
            "onSensorEvent, isON:";

    private VoiceSpeakerHook() {
    }

    public static boolean isEnabled() {
        return HookConfig.isEnabled(PREF_ENABLED);
    }

    public static void setEnabled(boolean v) {
        SharedPreferences sp = ContextManager.getPrefs();
        if (sp != null) {
            sp.edit().putBoolean(PREF_ENABLED, v).apply();
        }
    }

    public static void hook(final ClassLoader cl) {
        // 总是安装钩子，开关状态在回调中实时检查（与 VoiceForwardHook 行为一致，
        // 用户切换开关无需重启微信即可生效）。
        Set<String> hooked = new HashSet<>();
        boolean any = false;

        // 主路径：屏蔽距离传感器回调 x1(boolean)——根治切听筒/灭屏/重头播放
        any |= installByAnchor(cl, ANCHOR_SENSOR, new Installer() {
            @Override
            public boolean apply(ClassLoader loader, String cls, String method,
                                 int paramCount, String params, Set<String> hooked) {
                return installBlockSensor(loader, cls, method, hooked);
            }
        }, hooked);

        // 双保险：G(boolean) 强制 speakerOn=true（防止其他路径把 speakerOn 置回 false）
        any |= installByAnchor(cl, ANCHOR_SET, new Installer() {
            @Override
            public boolean apply(ClassLoader loader, String cls, String method,
                                 int paramCount, String params, Set<String> hooked) {
                return installForcedTrue(loader, cls, method, 1, hooked);
            }
        }, hooked);

        // 双保险：AudioDeviceFactory 首参强制 true（播放设备创建时固定扬声器）
        any |= installByAnchor(cl, ANCHOR_FACTORY, new Installer() {
            @Override
            public boolean apply(ClassLoader loader, String cls, String method,
                                 int paramCount, String params, Set<String> hooked) {
                return installFirstBoolTrue(loader, cls, method, hooked);
            }
        }, hooked);

        LogWriter.log(TAG, "hook done, installed=" + any + " hooked=" + hooked.size());
    }

    // ==================== 安装器 ====================

    /** 强制单参 boolean 方法的第一参数为 true（G 方法）。 */
    private static boolean installForcedTrue(ClassLoader cl, String cls, String method,
                                             int expectParamCount, Set<String> hooked) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> c = loader.loadClass(cls);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(method)) continue;
                    if (m.getParameterCount() != expectParamCount) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 1 || pts[0] != boolean.class) continue;
                    String key = c.getName() + "#" + m.getName() + "#" + m.getParameterCount();
                    if (!hooked.add(key)) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isEnabled()) return;
                            try { param.args[0] = Boolean.TRUE; } catch (Throwable ignored) {}
                        }
                    });
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " (force speakerOn=true)");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "installForcedTrue skip " + cls + ": " + t);
            }
        }
        return ok;
    }

    /** AudioDeviceFactory：首参为 boolean 的方法，强制 true。 */
    private static boolean installFirstBoolTrue(ClassLoader cl, String cls, String method,
                                                Set<String> hooked) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> c = loader.loadClass(cls);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(method)) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length < 1 || pts[0] != boolean.class) continue;
                    String key = c.getName() + "#" + m.getName() + "#" + m.getParameterCount();
                    if (!hooked.add(key)) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isEnabled()) return;
                            try { param.args[0] = Boolean.TRUE; } catch (Throwable ignored) {}
                        }
                    });
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " (AudioDeviceFactory force speaker)");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "installFirstBoolTrue skip " + cls + ": " + t);
            }
        }
        return ok;
    }

    /** 距离传感器回调：直接屏蔽，微信完全不处理。 */
    private static boolean installBlockSensor(ClassLoader cl, String cls, String method,
                                              Set<String> hooked) {
        boolean ok = false;
        for (ClassLoader loader : HookUtil.candidateLoaders(cl)) {
            try {
                Class<?> c = loader.loadClass(cls);
                if (c == null) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(method)) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != 1 || pts[0] != boolean.class) continue;
                    String key = c.getName() + "#" + m.getName() + "#" + m.getParameterCount();
                    if (!hooked.add(key)) continue;
                    m.setAccessible(true);
                    final boolean returnsBool = m.getReturnType() == boolean.class;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isEnabled()) return;
                            // 返回 boolean 的回调给 true（假装"远离"），void 直接吞掉。
                            // 屏蔽后：不切听筒、不获取 proximity wakelock（不灭屏）、不重建播放器。
                            if (returnsBool) {
                                param.setResult(Boolean.TRUE);
                            } else {
                                param.setResult(null);
                            }
                        }
                    });
                    ok = true;
                    LogWriter.log(TAG, "hooked " + c.getName() + "." + m.getName()
                            + " (sensor callback blocked)");
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "installBlockSensor skip " + cls + ": " + t);
            }
        }
        return ok;
    }

    // ==================== 锚点工具 ====================

    private interface Installer {
        boolean apply(ClassLoader loader, String cls, String method, int paramCount,
                      String params, Set<String> hooked);
    }

    private static boolean installByAnchor(ClassLoader cl, String anchor,
                                           Installer inst, Set<String> hooked) {
        boolean any = false;
        try {
            List<String> sigs = DexKitHelper.findMethodsByString(cl, null, anchor);
            if (sigs == null || sigs.isEmpty()) {
                LogWriter.log(TAG, "anchor no hit: " + anchor);
                return false;
            }
            for (String sig : sigs) {
                if (sig == null) continue;
                MethodInfo mi = parseSig(sig);
                if (mi == null) {
                    LogWriter.log(TAG, "anchor unparseable: " + sig);
                    continue;
                }
                any |= inst.apply(cl, mi.clsName, mi.methodName, mi.paramCount, mi.params, hooked);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "anchor err (" + anchor + "): " + t);
        }
        return any;
    }

    private static MethodInfo parseSig(String sig) {
        int p = sig.indexOf('(');
        if (p <= 0) return null;
        String decl = sig.substring(0, p);
        int dot = decl.lastIndexOf('.');
        if (dot <= 0) return null;
        String cls = decl.substring(0, dot);
        String name = decl.substring(dot + 1);
        String params = sig.substring(p + 1, sig.length() - 1);
        int pc = params.isEmpty() ? 0 : params.split(",").length;
        return new MethodInfo(cls, name, pc, params);
    }

    private static final class MethodInfo {
        final String clsName;
        final String methodName;
        final int paramCount;
        final String params;

        MethodInfo(String clsName, String methodName, int paramCount, String params) {
            this.clsName = clsName;
            this.methodName = methodName;
            this.paramCount = paramCount;
            this.params = params;
        }
    }
}