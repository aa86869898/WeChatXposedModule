package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.Set;
import java.util.HashSet;

/**
 * 自动通过好友申请 — 生产级完整实现
 * ================================
 * 
 * 好友验证链路:
 *   收到好友申请 → 通讯录红点 → 新的朋友列表
 *   → SayHiEditUI → 显示验证消息
 *   → "通过验证"按钮 → 发送通过请求
 * 
 * SayHiEditUI (com.tencent.mm.ui.contact.SayHiEditUI):
 *   N6(SayHiEditUI) → void  可能是通过验证的逻辑
 *   onSceneEnd(int,int,String,m1) → void  网络回调
 *   initView() → void  初始化UI
 * 
 * addcontact分支(e01.u.q7()):
 *   收到sysmsg.addcontact → 处理好友验证
 *   j4Var4.h = g(j4Var4.h)  设置验证内容
 *   t0 a = s0.a(1)           获取处理策略
 *   a.k(p0Var)                处理验证消息
 */
public class FriendRequestHook {

    private static final Set<String> autoPassList = new HashSet<>();
    private static boolean autoPassAll = false;
    private static boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        try {
            android.content.SharedPreferences p = com.leshao.v3.ContextManager.getPrefs();
            if (p != null) sEnabled = p.getBoolean("ls_auto_accept_friend", true);
        } catch (Throwable ignored) {}

        if (!sEnabled) return;
        autoPassAll = HookConfig.getInt("auto_pass_all", 0) == 1;

        String list = HookConfig.getString("auto_pass_list", "");
        if (list != null && !list.isEmpty()) {
            for (String s : list.split(",")) autoPassList.add(s.trim());
        }

        hookSayHiUI(cl);
        hookAddContactReceive(cl);
    }

    private static void hookSayHiUI(ClassLoader cl) {
        try {
            Class<?> sayHiUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.contact.SayHiEditUI", cl);

            XposedBridge.hookAllMethods(sayHiUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!autoPassAll && autoPassList.isEmpty()) return;

                    try {
                        android.app.Activity act = (android.app.Activity) param.thisObject;
                        android.content.Intent intent = act.getIntent();
                        String fromUser = intent.getStringExtra("Contact_User");
                        
                        if (fromUser != null) {
                            if (autoPassAll || autoPassList.contains(fromUser)) {
                                XposedBridge.log("[FriendReq] 自动通过: " + fromUser);
                                autoPassVerify(param.thisObject);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[FriendReq] 异常: " + t.getMessage());
                    }
                }
            });

            XposedBridge.hookAllMethods(sayHiUI, "N6", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        XposedBridge.log("[FriendReq] 验证已通过");
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });

            XposedBridge.hookAllMethods(sayHiUI, "onSceneEnd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        int errType = (Integer) param.args[0];
                                        int errCode = (Integer) param.args[1];
                                        if (errType == 0 && errCode == 0) {
                                            XposedBridge.log("[FriendReq] 好友添加成功");
                                        } else {
                                            XposedBridge.log("[FriendReq] 添加失败: " + param.args[2]);
                                        }
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });

            XposedBridge.log("[FriendReq] SayHiEditUI Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[FriendReq] SayHiEditUI失败: " + t.getMessage());
        }
    }

    private static void hookAddContactReceive(ClassLoader cl) {
        try {
            Class<?> e01u = XposedHelpers.findClass("e01.u", cl);

            XposedBridge.hookAllMethods(e01u, "q7", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        String type = (String) param.args[0];
                                        if ("addcontact".equals(type)) {
                                            java.util.Map map = (java.util.Map) param.args[1];
                                            if (map != null) {
                                                String content = (String) map.get(".sysmsg.addcontact.content");
                                                XposedBridge.log("[FriendReq] 收到好友申请: " + content);
                                            }
                                        }
                    } catch (Throwable e) {
                        de.robv.android.xposed.XposedBridge.log("LeShaoV3 cb err: " + e);
                    }
                }
            });
            XposedBridge.log("[FriendReq] addcontact监听 Hook完成");
        } catch (Throwable t) {
            XposedBridge.log("[FriendReq] addcontact失败: " + t.getMessage());
        }
    }

    private static void autoPassVerify(Object activity) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> {
                    try {
                        android.view.View root = ((android.app.Activity) activity)
                                .getWindow().getDecorView();
                        android.view.View btn = findPassButton(root);
                        if (btn != null) {
                            btn.performClick();
                            XposedBridge.log("[FriendReq] 已点击通过验证");
                        } else {
                            try {
                                XposedHelpers.callMethod(activity, "N6");
                                XposedBridge.log("[FriendReq] 已调用N6()通过验证");
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("[FriendReq] 自动通过失败: " + t.getMessage());
                    }
                }, 500);
    }

    private static android.view.View findPassButton(android.view.View v) {
        if (v instanceof android.widget.Button || v instanceof android.widget.TextView) {
            CharSequence text = v instanceof android.widget.Button
                    ? ((android.widget.Button) v).getText()
                    : ((android.widget.TextView) v).getText();
            if (text != null) {
                String s = text.toString();
                if (s.contains("通过") || s.contains("接受") || s.contains("添加")) {
                    return v;
                }
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.View found = findPassButton(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
