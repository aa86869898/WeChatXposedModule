package com.leshao.v3.wm;

import android.app.Activity;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.hook.WmChatHook;
import com.leshao.v3.wm.hook.WmGroupHook;
import com.leshao.v3.wm.hook.WmHomeHook;
import com.leshao.v3.wm.hook.WmMsgHook;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.wm.utils.WmReflect;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信大师完整注入入口 — 复刻自微信大师 MainHook
 * 注入: 聊天⚡🛡 | 群详情入口 | 主页+菜单 | 消息列表
 * 注意: 本环境 R8 改写 XposedHelpers，varargs findAndHookMethod 不可用，
 *       统一使用 findClass + getDeclaredMethod + XposedBridge.hookMethod 模式。
 */
public class WmEntry {

    private static final String TAG = "WmEntry";

    public static void injectAll(ClassLoader cl) {
        WmPrefs.init();
        WmChatHook.initOnAppStart(cl);
        try {
            injectChatWindow(cl);
            injectGroupInfo(cl);
            LogWriter.log(TAG, "inject all OK (⚡🛡🏠💬)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "inject err: " + t.getMessage());
        }
    }

    // ===== 聊天窗口 ⚡🛡 =====
    static void injectChatWindow(ClassLoader cl) {
        try {
            Class<?> frag = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            Method m0 = frag.getDeclaredMethod("M0");
            XposedBridge.hookMethod(m0, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity act = (Activity) XposedHelpers.callMethod(p.thisObject, "getActivity");
                        String user = null;
                        try {
                            user = WmReflect.getCurrentChatUser(act.getIntent());
                        } catch (Exception ignored) {}
                        if (user == null || user.isEmpty()) {
                            user = WmReflect.getChatUserFromFragment(p.thisObject);
                        }
                        if (user == null || user.isEmpty()) {
                            // 诊断：intent 实际携带的 key
                            try {
                                android.os.Bundle b = act.getIntent().getExtras();
                                if (b != null) {
                                    StringBuilder sb = new StringBuilder("chat intent keys:");
                                    for (String k : b.keySet()) {
                                        Object v = b.get(k);
                                        sb.append(" ").append(k).append("=")
                                                .append(v == null ? "null" : v.getClass().getSimpleName());
                                    }
                                    LogWriter.log(TAG, sb.toString());
                                }
                            } catch (Exception ignored2) {}
                            return;
                        }
                        final String fUser = user;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            // 聊天页只显示⚡入口，群管理功能合并进⚡面板
                            WmChatHook.showTitleBtn(act, cl, fUser);
                        }, 600);
                    } catch (Exception e) {
                        LogWriter.log(TAG, "chat window err: " + e.getMessage());
                    }
                }
            });
            Method o0 = frag.getDeclaredMethod("O0");
            XposedBridge.hookMethod(o0, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    WmChatHook.dismissTitleBtn();
                    WmGroupHook.dismissGroupBtn();
                }
            });
            LogWriter.log(TAG, "✓ chat window (M0/O0)");
        } catch (Exception e) {
            LogWriter.log(TAG, "✗ chat:" + e.getMessage());
        }
    }

    // ===== 群详情页入口 =====
    static void injectGroupInfo(ClassLoader cl) {
        // 浮动按钮和标题栏注入已移除——与 ChatroomInfoUI 原生 "+" 按钮冲突导致闪退
    }

    /** 在群详情页的 Toolbar/ActionBar 中添加 🛡 按钮 */
    static void tryAddToolbarBtn(Activity act, ClassLoader cl, String room) {
        if (act == null || room == null || !WmReflect.isChatRoom(cl, room)) return;
        if (com.leshao.v3.service.ActivationManager.isCurrentUserBlocked()) return;
        try {
            android.view.ViewGroup decor = (android.view.ViewGroup)
                    act.getWindow().getDecorView();
            android.view.View toolbar = findToolbar(decor);
            if (toolbar instanceof android.view.ViewGroup) {
                android.widget.Button btn = new android.widget.Button(act);
                btn.setText("乐少");
                btn.setTextSize(12);
                btn.setTextColor(android.graphics.Color.WHITE);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                bg.setCornerRadius(dp(act, 4));
                bg.setColor(AppColors.accent());
                btn.setBackground(bg);
                btn.setPadding(dp(act, 8), dp(act, 4), dp(act, 8), dp(act, 4));

                android.view.ViewGroup.LayoutParams existing = btn.getLayoutParams();
                android.view.ViewGroup.MarginLayoutParams lp;
                if (existing instanceof android.view.ViewGroup.MarginLayoutParams) {
                    lp = (android.view.ViewGroup.MarginLayoutParams) existing;
                } else {
                    lp = new android.view.ViewGroup.MarginLayoutParams(
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                lp.height = dp(act, 32);
                lp.rightMargin = dp(act, 8);

                btn.setOnClickListener(v -> WmGroupHook.showGroupPanel(act, cl, room));
                ((android.view.ViewGroup) toolbar).addView(btn, lp);
                LogWriter.log(TAG, "✓ 群详情标题栏按钮已添加");
            }
        } catch (Exception e) {
            LogWriter.log(TAG, "添加标题栏按钮失败: " + e.getMessage());
        }
    }

    /** 在 View 树中递归查找 Toolbar */
    static android.view.View findToolbar(android.view.View root) {
        if (root == null) return null;
        String cls = root.getClass().getName().toLowerCase();
        if (cls.contains("toolbar") || cls.contains("actionbar")) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.View r = findToolbar(vg.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    static int dp(Activity act, int d) {
        return (int) (d * act.getResources().getDisplayMetrics().density);
    }

    // ===== 主页+菜单 =====
    static void injectHomePlusMenu(ClassLoader cl) {
        try {
            Class<?> home = XposedHelpers.findClass("com.tencent.mm.ui.HomeUI", cl);
            Method o = home.getDeclaredMethod("o");
            XposedBridge.hookMethod(o, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Activity act = (Activity) XposedHelpers.getObjectField(p.thisObject, "q");
                        if (act != null) WmHomeHook.injectMenu(act, cl);
                    } catch (Exception ignored) {}
                }
            });
            LogWriter.log(TAG, "✓ 主页+菜单");
        } catch (Exception e) {
            LogWriter.log(TAG, "✗ 主页+:" + e.getMessage());
        }
    }
}
