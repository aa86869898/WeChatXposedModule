package com.leshao.v3.hook;

import android.content.SharedPreferences;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微信添加好友来源伪造模块
 *
 * 核心原理：
 *   所有添加好友请求最终都通过 com.tencent.mm.pluginsdk.model.m3 (NetSceneVerifyUser)
 *   发送到服务器。该类的构造函数接受一个 int 类型的 Scene 参数，
 *   最终写入 protobuf 字段 a65.u07.i (LinkedList&lt;Integer&gt;)。
 *
 *   服务器根据 Scene 值决定"添加来源"文案：
 *     3  = QQ号         10 = 搜索手机号     12 = 群聊
 *     14 = 附近的人      15 = QQ(另一方式)   17 = 摇一摇
 *     18 = 扫一扫       30 = 名片推荐       44 = 二维码
 *     48 = 雷达加朋友    52 = 搜索(另一方式)
 */
public final class FakeAddSource {

    private static final String TAG = "FakeAddSource";
    private static final String PREF_KEY_ENABLED = "ls_fake_add_source_enabled";
    private static final String PREF_KEY_SCENE = "ls_fake_add_source_scene";

    private static boolean sEnabled = false;
    private static int sFakeScene = 10;
    private static ClassLoader sCL;

    public static final Map<Integer, String> SCENE_MAP = new LinkedHashMap<>();
    static {
        SCENE_MAP.put(12, "来自QQ好友");
        SCENE_MAP.put(3,  "搜索账号添加");
        SCENE_MAP.put(6,  "来自朋友验证");
        SCENE_MAP.put(14, "群聊");
        SCENE_MAP.put(15, "手机号添加");
        SCENE_MAP.put(30, "通过扫一扫");
    }

    private FakeAddSource() {}

    public static void hook(ClassLoader cl) {
        LogWriter.log(TAG, "===== FakeAddSource.hook() START cl=" + cl + " =====");
        sCL = cl;

        try {
            SharedPreferences p = ContextManager.getPrefs();
            if (p == null) {
                LogWriter.log(TAG, "Config: SharedPreferences 为 null，跳过");
                return;
            }
            sEnabled = p.getBoolean(PREF_KEY_ENABLED, false);
            sFakeScene = p.getInt(PREF_KEY_SCENE, 10);
            LogWriter.log(TAG, "Config: sEnabled=" + sEnabled + " sFakeScene=" + sFakeScene
                    + " (" + sceneName(sFakeScene) + ")  prefFile=" + p.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "Config: 读取 SharedPreferences 失败: " + t.getMessage());
            return;
        }

        // 追踪探针：无论开关状态都注册，用于诊断添加好友链路
        hookAddFriendTrace(cl);

        if (!sEnabled) {
            LogWriter.log(TAG, "功能未启用，跳过注入。需在控制面板开启后重启微信生效");
            LogWriter.log(TAG, "===== FakeAddSource.hook() END (追踪探针已注册) =====");
            return;
        }

        LogWriter.log(TAG, "开始注入, FakeScene=" + sFakeScene + " (" + sceneName(sFakeScene) + ")");

        hookM3Constructors(cl);
        hookRStaticMethod(cl);

        LogWriter.log(TAG, "===== FakeAddSource.hook() END =====");
    }

    private static void hookM3Constructors(ClassLoader cl) {
        try {
            Class<?> m3Class = cl.loadClass("com.tencent.mm.pluginsdk.model.m3");
            int totalCtors = m3Class.getDeclaredConstructors().length;
            LogWriter.log(TAG, "hookM3: 类已找到 class=" + m3Class.getName() + " constructors=" + totalCtors);

            // Hook 1: 4参构造函数 (int,String,String,int)
            // 覆盖: 雷达加好友、聊天窗点击添加、搜索添加 等
            try {
                java.lang.reflect.Constructor<?> ctor4 = m3Class.getDeclaredConstructor(
                        int.class, String.class, String.class, int.class);
                XposedBridge.hookMethod(ctor4, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            int original = (Integer) param.args[3];
                            if (original == sFakeScene) return;
                            param.args[3] = sFakeScene;
                            logSceneChange(original, sFakeScene, "m3 4参构造 args[3]");
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "hookM3(4参): 异常 " + t.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "hookM3: 4参构造 Hook 成功");
            } catch (NoSuchMethodException e) {
                LogWriter.log(TAG, "hookM3: 4参构造不存在（非致命）: " + e.getMessage());
            }

            // Hook 2: 多用户构造函数 (>=8 args)
            // args[1]和args[2]都是List, args[2]是sceneList
            // 11参构造器 args[3] 是AntispamTicket列表，改scene时一起清掉
            XposedBridge.hookAllConstructors(m3Class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object[] args = param.args;
                        if (args == null || args.length < 8) return;
                        if (!(args[2] instanceof List)) return;

                        List<?> list = (List<?>) args[2];
                        if (list.isEmpty() || !(list.get(0) instanceof Integer)) return;

                        int original = (Integer) list.get(0);
                        if (original == sFakeScene) return;

                        List<Integer> newList = new ArrayList<>();
                        newList.add(sFakeScene);
                        param.args[2] = newList;
                        logSceneChange(original, sFakeScene, "m3 多用户构造 args[2]");

                        if (args.length >= 11 && args[3] instanceof List) {
                            args[3] = new ArrayList<>();
                            LogWriter.log(TAG, "hookM3(多用户): AntispamTicket 已清空");
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "hookM3(多用户): 异常 " + t.getMessage());
                    }
                }
            });

            LogWriter.log(TAG, "hookM3: 精准Hook注册成功 (4参单用户 + >=8参多用户)");
        } catch (ClassNotFoundException e) {
            LogWriter.log(TAG, "hookM3: m3 类未找到（微信版本不兼容）: " + e.getMessage());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookM3: 挂钩失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void hookRStaticMethod(ClassLoader cl) {
        try {
            LogWriter.log(TAG, "hookR: 开始查找 h55.r");
            Class<?> rClass = cl.loadClass("h55.r");
            String[] methodNames = {"l", "m", "n", "o", "p", "q", "r"};
            int hooked = 0;

            for (String mn : methodNames) {
                try {
                    for (java.lang.reflect.Method m : rClass.getDeclaredMethods()) {
                        if (!m.getName().equals(mn)) continue;
                        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length < 2) continue;

                        final String methodSig = mn + "("
                                + (pts.length > 0 ? pts[0].getSimpleName() : "")
                                + (pts.length > 1 ? "," + pts[1].getSimpleName() : "")
                                + (pts.length > 2 ? "," + pts[2].getSimpleName() : "") + ")";

                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                                        int original = (Integer) param.args[1];
                                        if (original != sFakeScene) {
                                            param.args[1] = sFakeScene;
                                            logSceneChange(original, sFakeScene, "h55.r 备份路径 " + methodSig);
                                        }
                                    }
                                } catch (Throwable t) {
                                    LogWriter.log(TAG, "hookR: 挂钩回调异常: " + t.getMessage());
                                }
                            }
                        });

                        hooked++;
                        LogWriter.log(TAG, "hookR: 挂钩 " + methodSig + " 成功");
                    }
                } catch (Throwable t) {
                    LogWriter.log(TAG, "hookR: 方法 " + mn + " 挂钩失败: " + t.getMessage());
                }
            }

            if (hooked > 0) {
                LogWriter.log(TAG, "hookR: h55.r 备份路径 共挂钩 " + hooked + " 个方法");
            } else {
                LogWriter.log(TAG, "hookR: h55.r 未找到匹配的静态方法（非致命）");
            }
        } catch (ClassNotFoundException e) {
            LogWriter.log(TAG, "hookR: h55.r 类未找到（非致命）: " + e.getMessage());
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookR: 挂钩失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static void hookAddFriendTrace(ClassLoader cl) {
        // 0. 框架级 Hook: Activity.startActivity — 发现实际验证UI类名
        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "startActivity", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 1 || !(param.args[0] instanceof android.content.Intent)) return;
                        android.content.Intent i = (android.content.Intent) param.args[0];
                        android.content.ComponentName cn = i.getComponent();
                        if (cn == null) return;
                        String targetClass = cn.getClassName();
                        if (targetClass == null) return;
                        if (targetClass.contains("ContactInfoUI")) {
                            android.os.Bundle b = i.getExtras();
                            if (b != null && sEnabled) {
                                int origScene = b.getInt("Contact_Scene", -1);
                                if (origScene > 0 && origScene != sFakeScene) {
                                    i.removeExtra("AntispamTicket");
                                    i.putExtra("Contact_Scene", sFakeScene);
                                }
                            }
                        }

                        if (targetClass.contains("SayHiWithSnsPermissionUI")) {
                            android.os.Bundle b = i.getExtras();
                            if (b != null && sEnabled) {
                                int origScene = b.getInt("Contact_Scene", -1);
                                if (origScene > 0 && origScene != sFakeScene) {
                                    i.removeExtra("AntispamTicket");
                                    i.putExtra("Contact_Scene", sFakeScene);
                                }
                            }
                        }

                        // 只记录可能相关的页面
                        if (targetClass.contains("contact") || targetClass.contains("Contact")
                                || targetClass.contains("SayHi") || targetClass.contains("sayhi")
                                || targetClass.contains("Add") || targetClass.contains("verify")
                                || targetClass.contains("Verify") || targetClass.contains("friend")
                                || targetClass.contains("Friend") || targetClass.contains("profile")
                                || targetClass.contains("Profile") || targetClass.contains("subapp")) {
                            android.os.Bundle b = i.getExtras();
                            StringBuilder sb = new StringBuilder("[Activity 跳转] ")
                                    .append(param.thisObject.getClass().getSimpleName())
                                    .append(" -> ").append(targetClass);
                            if (b != null) {
                                sb.append(" extras={");
                                for (String k : b.keySet()) {
                                    Object v = b.get(k);
                                    sb.append(k).append("=").append(v);
                                    if (v != null) sb.append("(").append(v.getClass().getSimpleName()).append(")");
                                    sb.append("; ");
                                }
                                sb.append("}");
                            }
                            LogWriter.log(TAG, sb.toString());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            LogWriter.log(TAG, "Activity.startActivity 框架级 Hook 注册成功");
        } catch (Throwable t) {
            LogWriter.log(TAG, "Activity.startActivity Hook 失败: " + t.getMessage());
        }

        // 0.5. Hook m3 构造器 — 始终记录完整参数签名（用于诊断添加请求）
        try {
            Class<?> m3 = cl.loadClass("com.tencent.mm.pluginsdk.model.m3");
            XposedBridge.hookAllConstructors(m3, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder("m3构造[");
                    Object[] args = param.args;
                    sb.append(args != null ? args.length : 0).append("]={");
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(i).append(":");
                            if (args[i] == null) {
                                sb.append("null");
                            } else {
                                sb.append(args[i].getClass().getSimpleName());
                                if (args[i] instanceof Integer) sb.append("=").append(args[i]);
                                else if (args[i] instanceof String) sb.append("=\"").append(args[i]).append("\"");
                                else if (args[i] instanceof java.util.List) {
                                    java.util.List<?> l = (java.util.List<?>) args[i];
                                    sb.append("[size=").append(l.size());
                                    if (!l.isEmpty()) sb.append(",0=").append(l.get(0));
                                    sb.append("]");
                                }
                            }
                        }
                    }
                    sb.append("}");
                    LogWriter.log(TAG, sb.toString());
                }
            });
            LogWriter.log(TAG, "m3 构造器追踪 Hook 注册成功");
        } catch (Throwable t) {
            LogWriter.log(TAG, "m3 构造器追踪 失败: " + t.getMessage());
        }

        // 1. Hook SayHiWithSnsPermissionUI — 8.0.76 验证页面
        try {
            Class<?> sayHiUI = cl.loadClass("com.tencent.mm.plugin.profile.ui.SayHiWithSnsPermissionUI");
            XposedBridge.hookAllMethods(sayHiUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "SayHiSnsPermUI.onCreate 触发");
                }
            });
            XposedBridge.hookAllMethods(sayHiUI, "onDestroy", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "SayHiSnsPermUI.onDestroy 触发 — 验证页面关闭");
                }
            });
            XposedBridge.hookAllMethods(sayHiUI, "onSceneEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int errType = param.args.length > 0 && param.args[0] instanceof Integer ? (Integer) param.args[0] : -1;
                        int errCode = param.args.length > 1 && param.args[1] instanceof Integer ? (Integer) param.args[1] : -1;
                        String errMsg = param.args.length > 2 && param.args[2] != null ? String.valueOf(param.args[2]) : "null";
                        String sceneClass = param.args.length > 3 && param.args[3] != null ? param.args[3].getClass().getName() : "null";
                        LogWriter.log(TAG, "SayHiSnsPermUI.onSceneEnd: errType=" + errType + " errCode=" + errCode
                                + " errMsg=" + errMsg + " sceneClass=" + sceneClass);
                        if (param.args.length > 3 && param.args[3] != null) {
                            dumpSceneFields(param.args[3]);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "SayHiSnsPermUI.onSceneEnd 异常: " + t.getMessage());
                    }
                }
            });
            // 枚举所有方法并 Hook 可疑的发送相关方法
            StringBuilder methodList = new StringBuilder();
            for (java.lang.reflect.Method m : sayHiUI.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                    String mn = m.getName();
                    methodList.append(mn).append("(").append(m.getParameterTypes().length).append(") ");
                    // 只 Hook 名字里含 send/verify/ok/confirm/commit/submit 的方法
                    String lower = mn.toLowerCase();
                    if (lower.contains("send") || lower.contains("verify") || lower.contains("confirm")
                            || lower.contains("commit") || lower.contains("submit") || lower.contains("ok")) {
                        final String methodName = mn;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                LogWriter.log(TAG, "SayHiSnsPermUI." + methodName + "() 触发");
                            }
                        });
                    }
                }
            }
            LogWriter.log(TAG, "SayHiSnsPermUI 方法列表: " + methodList.toString().trim());
            LogWriter.log(TAG, "SayHiSnsPermUI 追踪 Hook 注册成功");
        } catch (Throwable t) {
            LogWriter.log(TAG, "SayHiSnsPermUI 追踪 Hook 失败: " + t.getMessage());
        }

        // 2. Hook 旧的 SayHiEditUI 作为兜底
        try {
            Class<?> sayHiUI = cl.loadClass("com.tencent.mm.ui.contact.SayHiEditUI");
            XposedBridge.hookAllMethods(sayHiUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "SayHiEditUI.onCreate 触发（兜底）");
                }
            });
            LogWriter.log(TAG, "SayHiEditUI 兜底 Hook 注册成功");
        } catch (Throwable t) {
            LogWriter.log(TAG, "SayHiEditUI 类未找到（正常）: " + t.getMessage());
        }

        // 4. 扫描 pluginsdk.model 包下所有类构造（发现新的场景类）
        try {
            // 包扫描方式：枚举已知候选类
            String[] candidatePkg = {"com.tencent.mm.pluginsdk.model"};
            for (String pkg : candidatePkg) {
                try {
                    Class<?>[] candidates = {cl.loadClass(pkg + ".m1"), cl.loadClass(pkg + ".m2"),
                            cl.loadClass(pkg + ".m4"), cl.loadClass(pkg + ".m5"), cl.loadClass(pkg + ".m6"),
                            cl.loadClass(pkg + ".m7"), cl.loadClass(pkg + ".m8"), cl.loadClass(pkg + ".m9"),
                            cl.loadClass(pkg + ".n1"), cl.loadClass(pkg + ".n2"), cl.loadClass(pkg + ".n3"),
                            cl.loadClass(pkg + ".n4"), cl.loadClass(pkg + ".n5"), cl.loadClass(pkg + ".n6"),
                            cl.loadClass(pkg + ".n7"), cl.loadClass(pkg + ".n8"), cl.loadClass(pkg + ".n9")};
                    for (final Class<?> c : candidates) {
                        try {
                            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    StringBuilder sb = new StringBuilder(c.getSimpleName() + " 构造: args[");
                                    if (param.args != null) {
                                        sb.append(param.args.length).append("]={");
                                        for (int i = 0; i < param.args.length; i++) {
                                            if (i > 0) sb.append(",");
                                            sb.append(i).append(":").append(param.args[i] == null ? "null" : param.args[i].getClass().getSimpleName());
                                            if (param.args[i] instanceof String) sb.append("=\"").append(param.args[i]).append("\"");
                                        }
                                        sb.append("}");
                                    } else {
                                        sb.append("0]={}");
                                    }
                                    LogWriter.log(TAG, "[构造器 追踪] " + sb.toString());
                                }
                            });
                        } catch (NoClassDefFoundError ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            LogWriter.log(TAG, "pluginsdk.model 扫描 Hook 注册完成");
        } catch (Throwable t) {
            LogWriter.log(TAG, "pluginsdk.model 扫描失败: " + t.getMessage());
        }

        // 5. Hook ContactInfoUI.D2 — 追踪查看联系人时是否触发了添加流程
        try {
            Class<?> contactInfoUI = cl.loadClass("com.tencent.mm.plugin.profile.ui.ContactInfoUI");
            XposedBridge.hookAllMethods(contactInfoUI, "D2", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    LogWriter.log(TAG, "ContactInfoUI.D2() 联系人详情已加载");
                }
            });
            LogWriter.log(TAG, "ContactInfoUI.D2 追踪 Hook 注册成功");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ContactInfoUI 追踪 Hook 失败: " + t.getMessage());
        }
    }

    private static void dumpSceneFields(Object scene) {
        try {
            Class<?> c = scene.getClass();
            StringBuilder sb = new StringBuilder("  场景字段 [").append(c.getName()).append("]: ");
            int count = 0;
            while (c != null && c != Object.class && count < 3) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(scene);
                        sb.append(f.getName()).append("=").append(v);
                        if (v instanceof Integer) sb.append("(int)");
                        if (v instanceof String) sb.append("(str)");
                        sb.append("; ");
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
                count++;
            }
            LogWriter.log(TAG, sb.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, "dumpSceneFields 异常: " + t.getMessage());
        }
    }

    private static void logSceneChange(int from, int to, String path) {
        LogWriter.log(TAG,
            "[来源伪造] " + path + ": " +
            sceneName(from) + "(" + from + ") --> " +
            sceneName(to) + "(" + to + ")");
    }

    public static String sceneName(int scene) {
        String name = SCENE_MAP.get(scene);
        return name != null ? name : "未知";
    }

    public static boolean isEnabled() { return sEnabled; }
    public static int getFakeScene() { return sFakeScene; }
}
