package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;

import com.leshao.v3.model.ModuleConfig;
import com.leshao.v3.ContextManager;

/**
 * [功能8] 聊天界面自定义 — 生产级完整实现
 * =======================================
 * 
 * ChattingUIFragment关键方法:
 *   setMMTitle(String)                 设置标题
 *   setMMSubTitle(String)              设置副标题
 *   dealContentView(View)              处理内容View
 *   getLayoutView() → View            获取根布局
 *   
 * 消息气泡渲染:
 *   nw1.t2 (Adapter) → getView() → 创建每个消息Item
 *   每条消息包含: 头像、昵称、气泡(ViewGroup)、内容
 *   
 * 可自定义:
 *   1. 聊天背景(颜色/图片)
 *   2. 气泡颜色(发送/接收分别设置)
 *   3. 气泡圆角大小
 *   4. 标题栏颜色
 *   5. 隐藏/显示昵称
 */
public class ChatUICustom {

    private static int bgColor = 0;
    private static int sendBubbleColor = 0;
    private static int recvBubbleColor = 0;
    private static int bubbleRadius = 0;
    private static int titleColor = 0;
    private static boolean hideNickname = false;
    private static volatile boolean sEnabled = true;

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        XposedBridge.log("[ChatUICustom] hook() ENTER sEnabled=" + sEnabled);
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        XposedBridge.log("[ChatUICustom] chatUICustomEnabled=" + config.chatUICustomEnabled);
        if (!sEnabled || !config.chatUICustomEnabled) return;
        XposedBridge.log("[ChatUICustom] 开始安装hooks...");
        hookChattingFragment(cl);
        hookBubbles(cl);
        XposedBridge.log("[ChatUICustom] hooks安装完成");
    }

    private static void reloadConfig() {
        bgColor = (int) HookConfig.getLong("chat_bg_color", 0);
        sendBubbleColor = HookConfig.getInt("chat_bubble_send", 0);
        recvBubbleColor = HookConfig.getInt("chat_bubble_recv", 0);
        bubbleRadius = HookConfig.getInt("chat_bubble_radius", 0);
        titleColor = HookConfig.getInt("chat_title_color", 0);
        hideNickname = HookConfig.getInt("chat_hide_nickname", 0) == 1;
    }

    private static void hookChattingFragment(ClassLoader cl) {
        try {
            Class<?> chattingUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.chatting.ChattingUIFragment", cl);

            XposedBridge.hookAllMethods(chattingUI, "dealContentView",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    reloadConfig();
                    if (bgColor == 0) return;
                    View contentView = (View) param.args[0];
                    if (contentView != null) {
                        contentView.setBackgroundColor(bgColor);
                    }
                }
            });

            XposedBridge.hookAllMethods(chattingUI, "getLayoutView",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    reloadConfig();
                    if (bgColor == 0) return;
                    View layoutView = (View) param.getResult();
                    if (layoutView != null) {
                        setChatAreaBackground(layoutView);
                    }
                }
            });

            XposedBridge.hookAllMethods(chattingUI, "setMMTitle",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    reloadConfig();
                    if (titleColor == 0) return;
                    try {
                        android.app.Activity act = getActivity(param.thisObject);
                        if (act != null && act.getActionBar() != null) {
                            act.getActionBar().setBackgroundDrawable(
                                    new ColorDrawable(titleColor));
                        }
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.log("[ChatUI] ChattingUIFragment hooks已安装 (背景/标题)");
        } catch (Throwable t) {
            XposedBridge.log("[ChatUI] ChattingUIFragment hook失败: " + t.getMessage());
        }
    }

    private static void hookBubbles(ClassLoader cl) {
        try {
            Class<?> adapter = VersionCompat.findAdapterClass(cl);
            if (adapter == null) return;

            XposedBridge.hookAllMethods(adapter, "getView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        reloadConfig();
                        int position = (Integer) param.args[0];
                        View itemView = (View) param.getResult();
                        if (itemView == null) return;

                        Object msgInfo = XposedHelpers.callMethod(
                                param.thisObject, "getItem", position);
                        if (msgInfo == null) return;

                        boolean isSend = isSentMessage(msgInfo);

                        if (sendBubbleColor != 0 || recvBubbleColor != 0) {
                            int color = isSend ? sendBubbleColor : recvBubbleColor;
                            if (color != 0) applyBubbleStyle(itemView, color, bubbleRadius);
                        }

                        if (hideNickname) hideNicknameInItem(itemView);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[ChatUI] 气泡Hook已安装");
        } catch (Throwable t) {
            XposedBridge.log("[ChatUI] 气泡Hook失败: " + t.getMessage());
        }
    }

    private static void applyBubbleStyle(View itemView, int color, int radius) {
        findAndStyleBubble(itemView, color, radius);
    }

    private static void findAndStyleBubble(View v, int color, int radius) {
        if (v instanceof ViewGroup && !(v instanceof TextView)) {
            ViewGroup vg = (ViewGroup) v;
            if (vg.getBackground() != null && hasTextChild(vg)) {
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(color);
                if (radius > 0) gd.setCornerRadius(radius);
                else gd.setCornerRadius(dpToPx(v.getContext(), 8));
                v.setBackground(gd);
                return;
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                findAndStyleBubble(vg.getChildAt(i), color, radius);
            }
        }
    }

    private static boolean hasTextChild(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            if (vg.getChildAt(i) instanceof TextView) return true;
        }
        return false;
    }

    private static void hideNicknameInItem(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence text = tv.getText();
            if (text != null && text.length() >= 1 && text.length() <= 25
                    && !text.toString().contains(":") && !text.toString().contains("：")) {
                tv.setVisibility(View.GONE);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                hideNicknameInItem(vg.getChildAt(i));
            }
        }
    }

    private static void setChatAreaBackground(View layoutView) {
        if (layoutView instanceof android.widget.ListView) {
            ((android.widget.ListView) layoutView).setBackgroundColor(bgColor);
        } else if (layoutView instanceof androidx.recyclerview.widget.RecyclerView) {
            layoutView.setBackgroundColor(bgColor);
        }
        if (layoutView instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) layoutView;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child instanceof android.widget.ListView) {
                    child.setBackgroundColor(bgColor);
                } else if (child instanceof androidx.recyclerview.widget.RecyclerView) {
                    child.setBackgroundColor(bgColor);
                } else if (child instanceof ViewGroup) {
                    setChatAreaBackground(child);
                }
            }
        }
    }

    private static boolean isSentMessage(Object msgInfo) {
        try {
            return (Boolean) XposedHelpers.callMethod(msgInfo, "G1");
        } catch (Throwable t) { return false; }
    }

    private static android.app.Activity getActivity(Object fragment) {
        try {
            return (android.app.Activity) XposedHelpers.callMethod(fragment, "getActivity");
        } catch (Throwable t) { return null; }
    }

    private static String colorStr(int color) {
        return color == 0 ? "默认" : "#" + Integer.toHexString(color).toUpperCase();
    }

    private static int dpToPx(android.content.Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
