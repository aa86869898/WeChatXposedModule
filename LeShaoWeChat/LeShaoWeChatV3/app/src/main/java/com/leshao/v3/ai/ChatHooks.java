package com.leshao.v3.ai;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.RedPacketHook;
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
    private static int lastDiagType = -99;
    private static int lastDiagSend = -99;
    private static volatile Activity sResumedActivity;
    private static volatile boolean sChatUiResumed;

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
                        if (msg == null) return;
                        int ty = WxReflect.type(msg);
                        // 诊断: 对红包/转账特征消息打印类型码（用于校准）
                        String diagContent = null;
                        try { diagContent = WxReflect.content(msg); } catch (Throwable ignored) {}
                        if (RedPacketHook.looksLikeMoneyMessage(diagContent)) {
                            String dt = diagContent == null ? "" : diagContent;
                            LogWriter.log(TAG, "入库Hook [RP-DIAG] type=" + ty
                                + " talker=" + WxReflect.talker(msg)
                                + " content=" + (dt.length() > 200 ? dt.substring(0, 200) : dt));
                        }
                        // 红包/转账检测: 复用本 hook, 独立于 AI 开关
                        if (RedPacketHook.isRedPacketType(ty) || RedPacketHook.isTransferType(ty)) {
                            String talker = WxReflect.talker(msg);
                            String content = WxReflect.content(msg);
                            long msgId = WxReflect.msgId(msg);
                            RedPacketHook.onIncomingMessage(ty, talker, content, msgId);
                        }
                        if (!AiConfig.masterEnabled() || !AiConfig.replyEnabled()) return;
                        WxReflect.dumpMsgInfoFields(msg);
                        if (!WxReflect.isIncomingText(msg)) {
                            int sd = WxReflect.isSend(msg);
                            if (ty != lastDiagType || sd != lastDiagSend) {
                                lastDiagType = ty;
                                lastDiagSend = sd;
                                LogWriter.log(TAG, "消息入库Hook: 非纯文本不触发 type=" + ty + " isSend=" + sd);
                            }
                            return;
                        }
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

    private static boolean sWindowFocused = false;
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
    private static final Runnable closeWindowTask = new Runnable() {
        @Override public void run() {
            chatWindowOpen = false;
            LogWriter.log(TAG, "悬浮球: 窗口关闭确认(延迟)");
        }
    };

    /** 当前聊天是否处于打开状态（fragment 聊天 / 独立 ChattingUI 活动） */
    private static boolean isChatOpen() {
        if (sChatUiResumed) return true;
        Activity act = sResumedActivity;
        if (act == null) return false;
        String name = act.getClass().getName();
        if (name.equals("com.tencent.mm.ui.chatting.ChattingUI")) return true;
        if (name.equals("com.tencent.mm.ui.LauncherUI")) {
            return isChatFragmentVisible(act);
        }
        return false;
    }

    /** 轮询 reconciler：悬浮球显示状态与聊天开/关状态对齐，兜底修复卡住/不显示 */
    private static final Runnable reconcileTask = new Runnable() {
        @Override public void run() {
            try {
                if (sResumedActivity == null) {
                    // No active WeChat activity, skip polling until resumed
                    return;
                }
                boolean chatOpen = isChatOpen();
                boolean shown = FloatingBall.isShowing();
                if (chatOpen && !shown && AiConfig.masterEnabled() && sWindowFocused) {
                    Activity act = sResumedActivity;
                    if (act != null) {
                        chatActivity = act;
                        chatWindowOpen = true;
                        FloatingBall.show(act, reflectClassLoader);
                        LogWriter.log(TAG, "悬浮球: reconciler show (chat open)");
                    }
                } else if (!chatOpen && shown) {
                    chatWindowOpen = false;
                    FloatingBall.hide();
                    ReplyBanner.hide();
                    LogWriter.log(TAG, "悬浮球: reconciler hide (chat closed)");
                }
            } catch (Throwable ignored) {}
            MAIN.postDelayed(reconcileTask, 400);
        }
    };
    private static ClassLoader reflectClassLoader;

    private static void hookChatWindowBall(XC_LoadPackage.LoadPackageParam lp) {
        reflectClassLoader = lp.classLoader;
        try {
            // 窗口焦点追踪：只在窗口获得焦点后才允许显示悬浮球，避免过渡态 addView 崩溃
            XposedBridge.hookAllMethods(Activity.class, "onWindowFocusChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        boolean focused = (boolean) p.args[0];
                        sWindowFocused = focused;
                        Activity act = (Activity) p.thisObject;
                        String cls = act.getClass().getName();
                        if (focused && (cls.equals("com.tencent.mm.ui.LauncherUI")
                                || cls.equals("com.tencent.mm.ui.chatting.ChattingUI"))) {
                            // 窗口获得焦点后，若聊天仍打开，尝试显示悬浮球
                            if (chatWindowOpen && !FloatingBall.isShowing() && chatActivity != null) {
                                MAIN.removeCallbacks(showBallTask);
                                MAIN.postDelayed(showBallTask, 200);
                                LogWriter.log(TAG, "悬浮球: onWindowFocusChanged 触发 showBallTask");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "悬浮球: Activity.onWindowFocusChanged 已挂载");

            // 返回主页时关闭悬浮球，返回聊天窗口时重新显示
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        sResumedActivity = (Activity) p.thisObject;
                        String clsName = p.thisObject.getClass().getName();
                        if (clsName.equals("com.tencent.mm.ui.LauncherUI")) {
                            // 8.0.49 聊天窗口是 LauncherUI 内的 fragment：
                            // 从详情页(ChatroomInfoUI)返回时 LauncherUI.onResume 也会触发，
                            // 此时若 ChattingUIFragment 仍在显示，应重新显示悬浮球而非隐藏
                            Activity act = (Activity) p.thisObject;
                            String chatUser = findChatUserFromActivity(act);
                            boolean chatVisible = isChatFragmentVisible(act);
                            LogWriter.log(TAG, "悬浮球: LauncherUI.onResume chatVisible=" + chatVisible
                                + " user=" + chatUser + " focused=" + sWindowFocused);
                            if (chatVisible && chatUser != null && !chatUser.isEmpty()) {
                                chatActivity = act;
                                chatWindowOpen = true;
                                setTalker(chatUser);
                                MAIN.removeCallbacks(hideBallTask);
                                MAIN.removeCallbacks(showBallTask);
                                MAIN.removeCallbacks(closeWindowTask);
                                // 窗口已焦点则直接显示，否则等 onWindowFocusChanged
                                if (sWindowFocused) {
                                    MAIN.postDelayed(showBallTask, 300);
                                } else {
                                    LogWriter.log(TAG, "悬浮球: 窗口未焦点，延迟到 onWindowFocusChanged 显示");
                                }
                                return;
                            }
                            // 真正回到主页: 隐藏悬浮球
                            MAIN.removeCallbacks(showBallTask);
                            MAIN.removeCallbacks(closeWindowTask);
                            MAIN.postDelayed(closeWindowTask, 300);
                            FloatingBall.hide();
                            ReplyBanner.hide();
                            chatActivity = null;
                        } else if (clsName.equals("com.tencent.mm.chatroom.ui.ChatroomInfoUI")) {
                            // 聊天详情页: 隐藏 AI 悬浮球（返回聊天窗口时重新显示）
                            MAIN.removeCallbacks(showBallTask);
                            FloatingBall.hide();
                            ReplyBanner.hide();
                        } else if (clsName.equals("com.tencent.mm.ui.chatting.ChattingUI")) {
                            Activity act = (Activity) p.thisObject;
                            String user = findChatUserFromActivity(act);
                            LogWriter.log(TAG, "悬浮球: ChattingUI.onResume user=" + user
                                + " focused=" + sWindowFocused);
                            if (user == null || user.isEmpty()) return;
                            chatActivity = act;
                            chatWindowOpen = true;
                            setTalker(user);
                            MAIN.removeCallbacks(hideBallTask);
                            MAIN.removeCallbacks(showBallTask);
                            MAIN.removeCallbacks(closeWindowTask);
                            if (sWindowFocused) {
                                MAIN.postDelayed(showBallTask, 300);
                            } else {
                                LogWriter.log(TAG, "悬浮球: 窗口未焦点，延迟到 onWindowFocusChanged 显示");
                            }
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "Activity.onResume err: " + e);
                    }
                }
            });
            LogWriter.log(TAG, "悬浮球: Activity.onResume 已挂载");

            // 悬浮球卡住根因修复：任意 Activity 暂停（Home/back/详情页覆盖）立即隐藏。
            // 8.0.49 聊天是 LauncherUI 内 fragment，返回主列表不触发任何 onResume 变更，
            // 因此仅靠 onResume 分支无法隐藏，onPause + 轮询 reconciler 双保险。
            try {
                XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.thisObject == sResumedActivity) sResumedActivity = null;
                            FloatingBall.hide();
                            ReplyBanner.hide();
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "悬浮球: Activity.onPause 已挂载");
            } catch (Throwable e) {
                LogWriter.log(TAG, "Activity.onPause hook err: " + e.getMessage());
            }

            // ChattingUI 独立 Activity 模式（8.0.49 很少用，保留兼容）：onResume/onPause 标记聊天状态
            try {
                Class<?> chatUi = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", lp.classLoader);
                XposedBridge.hookAllMethods(chatUi, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { sChatUiResumed = true; }
                });
                XposedBridge.hookAllMethods(chatUi, "onPause", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        sChatUiResumed = false;
                        FloatingBall.hide();
                    }
                });
                LogWriter.log(TAG, "悬浮球: ChattingUI onResume/onPause 已挂载");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUI hook err: " + e.getMessage());
            }

            // 轮询 reconciler：每 400ms 将悬浮球显示状态与"聊天是否打开"对齐。
            // 修复①返回主列表后悬浮球卡住关不掉；修复②第二次进聊天不显示悬浮球。
            MAIN.removeCallbacks(reconcileTask);
            MAIN.postDelayed(reconcileTask, 400);
            LogWriter.log(TAG, "悬浮球: reconciler 已启动");

            // 8.0.49 关键: 聊天窗口是 LauncherUI 内的 fragment。
            // hook fragment 可见性生命周期(onResume/onHiddenChanged/setUserVisibleHint)，
            // 每次进入聊天窗口都会触发，MMEditText onAttachedToWindow 只触发一次。
            hookChatFragmentVisibility(lp);

            Class<?> frag = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", lp.classLoader);
            Method m0 = frag.getDeclaredMethod("M0");
            XposedBridge.hookMethod(m0, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                                        chatWindowOpen = true;
                                        Activity act = fragmentActivity(param.thisObject);
                                        if (act == null) { LogWriter.log(TAG, "悬浮球: M0 未获取到 Activity"); return; }
                                        chatActivity = act;
                                        setTalker(resolveTalkerFromActivity(act, param.thisObject));
                                        LogWriter.log(TAG, "悬浮球: M0 聊天窗口打开 Activity=" + act.getClass().getSimpleName()
                                                + " talker=" + currentTalker);
                                        MAIN.removeCallbacks(hideBallTask);
                                        MAIN.removeCallbacks(showBallTask);
                                        MAIN.removeCallbacks(closeWindowTask);
                                        MAIN.postDelayed(showBallTask, 300);
                                        final String openedTalker = currentTalker;
                                        MAIN.postDelayed(() -> ReplyFeature.onSessionOpened(openedTalker, lp.classLoader), 2000);
                    } catch (Throwable e) {
                        LogWriter.log("ChatHooks", "cb err: " + e);
                    }
                }
            });
            LogWriter.log(TAG, "悬浮球: M0 挂载完成");

            Method o0 = frag.getDeclaredMethod("O0");
            XposedBridge.hookMethod(o0, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                                        LogWriter.log(TAG, "悬浮球: O0 聊天窗口关闭, 延迟确认");
                                        MAIN.removeCallbacks(showBallTask);
                                        MAIN.removeCallbacks(closeWindowTask);
                                        MAIN.postDelayed(closeWindowTask, 300);
                                        MAIN.removeCallbacks(hideBallTask);
                                        MAIN.postDelayed(hideBallTask, 800);
                                        chatActivity = null;
                    } catch (Throwable e) {
                        LogWriter.log("ChatHooks", "cb err: " + e);
                    }
                }
            });
            LogWriter.log(TAG, "悬浮球: O0 挂载完成");

            // Fallback: M0/O0 在 8.0.50 不可靠，用 MMEditText onAttachedToWindow 检测
            try {
                XposedBridge.hookAllMethods(android.view.View.class, "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            android.view.View v = (android.view.View) p.thisObject;
                            if (!"com.tencent.mm.ui.widget.MMEditText".equals(v.getClass().getName())) return;
                            Activity act = (Activity) v.getContext();
                            if (act == null) return;
                            String user = findChatUserFromActivity(act);
                            LogWriter.log(TAG, "悬浮球: MMEditText attached talker=" + user);
                            if (user == null || user.isEmpty()) return;
                            chatWindowOpen = true;
                            chatActivity = act;
                            setTalker(user);
                            MAIN.removeCallbacks(hideBallTask);
                            MAIN.removeCallbacks(showBallTask);
                            MAIN.removeCallbacks(closeWindowTask);
                            MAIN.postDelayed(showBallTask, 300);
                            final String openedTalker = currentTalker;
                            MAIN.postDelayed(() -> ReplyFeature.onSessionOpened(openedTalker, lp.classLoader), 2000);
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "MMEditText detect err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "悬浮球: MMEditText onAttachedToWindow 兜底已挂载");
            } catch (Throwable e) {
                LogWriter.log(TAG, "MMEditText hook err: " + e.getMessage());
            }

            // 离开聊天窗口时关闭悬浮球
            try {
                XposedBridge.hookAllMethods(android.view.View.class, "onDetachedFromWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            android.view.View v = (android.view.View) p.thisObject;
                            if (!"com.tencent.mm.ui.widget.MMEditText".equals(v.getClass().getName())) return;
                            LogWriter.log(TAG, "悬浮球: MMEditText detached, 关闭窗口");
                            MAIN.removeCallbacks(showBallTask);
                            MAIN.removeCallbacks(closeWindowTask);
                            MAIN.postDelayed(closeWindowTask, 300);
                            MAIN.removeCallbacks(hideBallTask);
                            MAIN.postDelayed(hideBallTask, 800);
                            chatActivity = null;
                        } catch (Throwable e) {
                            LogWriter.log(TAG, "MMEditText detach err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "悬浮球: MMEditText onDetachedFromWindow 已挂载");
            } catch (Throwable e) {
                LogWriter.log(TAG, "MMEditText detach hook err: " + e.getMessage());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "悬浮球: ChattingUIFragment M0/O0 hook 失败: " + t.getMessage());
        }
    }

    private static void hookChatFragmentVisibility(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> fragCls = XposedHelpers.findClass(
                "com.tencent.mm.ui.chatting.ChattingUIFragment", lp.classLoader);
            final Runnable openRunnable = new Runnable() {
                @Override public void run() {
                    try {
                        Object frag = ChatHooks.sLastVisibleFragment;
                        if (frag == null) return;
                        Activity act = fragmentActivity(frag);
                        if (act == null) return;
                        chatWindowOpen = true;
                        chatActivity = act;
                        String user = resolveTalkerFromFragment(frag);
                        LogWriter.log(TAG, "悬浮球: fragment可见 talker=" + user);
                        if (user == null || user.isEmpty()) return;
                        setTalker(user);
                        MAIN.removeCallbacks(hideBallTask);
                        MAIN.removeCallbacks(showBallTask);
                        MAIN.removeCallbacks(closeWindowTask);
                        MAIN.postDelayed(showBallTask, 300);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "frag visible err: " + e);
                    }
                }
            };
            hookFragMethodIfPresent(fragCls, "onHiddenChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        boolean hidden = (Boolean) p.args[0];
                        LogWriter.log(TAG, "悬浮球: onHiddenChanged hidden=" + hidden);
                        if (hidden) {
                            MAIN.removeCallbacks(showBallTask);
                            FloatingBall.hide();
                        } else {
                            sLastVisibleFragment = p.thisObject;
                            MAIN.post(openRunnable);
                        }
                    } catch (Throwable e) { LogWriter.log(TAG, "onHiddenChanged err: " + e); }
                }
            });
            hookFragMethodIfPresent(fragCls, "setUserVisibleHint", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        boolean visible = (Boolean) p.args[0];
                        LogWriter.log(TAG, "悬浮球: setUserVisibleHint visible=" + visible);
                        if (visible) {
                            sLastVisibleFragment = p.thisObject;
                            MAIN.post(openRunnable);
                        }
                    } catch (Throwable e) { LogWriter.log(TAG, "setUserVisibleHint err: " + e); }
                }
            });
            hookFragMethodIfPresent(fragCls, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        sLastVisibleFragment = p.thisObject;
                        MAIN.post(openRunnable);
                    } catch (Throwable e) { LogWriter.log(TAG, "frag onResume err: " + e); }
                }
            });
            LogWriter.log(TAG, "悬浮球: ChattingUIFragment 可见性 hook 已挂载");
        } catch (Throwable e) {
            LogWriter.log(TAG, "fragment visibility hook err: " + e.getMessage());
        }
    }

    private static void hookFragMethodIfPresent(Class<?> fragCls, String name, XC_MethodHook hook) {
        try {
            java.lang.reflect.Method m = null;
            Class<?> cur = fragCls;
            while (cur != null && cur != Object.class) {
                for (java.lang.reflect.Method dm : cur.getDeclaredMethods()) {
                    if (dm.getName().equals(name)) {
                        m = dm;
                        break;
                    }
                }
                if (m != null) break;
                cur = cur.getSuperclass();
            }
            if (m == null) return;
            XposedBridge.hookMethod(m, hook);
            LogWriter.log(TAG, "悬浮球: fragment " + name + " hook 已挂载(" + m.getDeclaringClass().getSimpleName() + ")");
        } catch (Throwable e) {
            LogWriter.log(TAG, "fragment " + name + " hook err: " + e.getMessage());
        }
    }

    private static volatile Object sLastVisibleFragment;

    private static boolean isChatFragmentVisible(Activity act) {
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm == null) return false;
            java.util.List fragments = (java.util.List) XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null) return false;
            for (Object f : fragments) {
                if (!"com.tencent.mm.ui.chatting.ChattingUIFragment".equals(f.getClass().getName())) continue;
                try {
                    Object v = XposedHelpers.callMethod(f, "isVisible");
                    if (v instanceof Boolean && (Boolean) v) return true;
                } catch (Throwable ignored) {}
                try {
                    Object hidden = XposedHelpers.callMethod(f, "isHidden");
                    if (hidden instanceof Boolean && !(Boolean) hidden) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Activity fragmentActivity(Object fragment) {
        try {
            Object act = XposedHelpers.callMethod(fragment, "getActivity");
            if (act instanceof Activity) return (Activity) act;
        } catch (Throwable ignored) {}
        return null;
    }

    private static String findChatUserFromActivity(Activity act) {
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm == null) return "";
            java.util.List fragments = (java.util.List) XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null) return "";
            for (Object f : fragments) {
                if (!"com.tencent.mm.ui.chatting.ChattingUIFragment".equals(f.getClass().getName())) continue;
                try {
                    Object args = XposedHelpers.callMethod(f, "getArguments");
                    if (args instanceof android.os.Bundle) {
                        String u = ((android.os.Bundle) args).getString("Chat_User");
                        if (u != null && !u.isEmpty()) return u;
                    }
                } catch (Throwable ignored) {}
                String u = WmReflect.getChatUserFromFragment(f);
                if (u != null) return u;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatUser: " + e.getMessage());
        }
        return "";
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
        try {
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
        } catch (Throwable t) {
            LogWriter.log(TAG, "setTalker err: " + t);
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
                // 1) m 字段 MMFlexEditText：仅当文本非空才采用，否则回退其内部输入框
                Object m = getFieldOrNull(footer, "m");
                if (m != null) {
                    String t = reflectGetText(m);
                    if (t != null && !t.isEmpty()) {
                        LogWriter.log(TAG, "findInputView: m 字段非空(长度=" + t.length() + ")，采用 " + m.getClass().getName());
                        return m;
                    }
                    LogWriter.log(TAG, "findInputView: m 字段文本为空，查找其内部输入框");
                    Object inner = findInnerEditText(m);
                    if (inner != null) {
                        LogWriter.log(TAG, "findInputView: m 内部命中 " + inner.getClass().getName());
                        return inner;
                    }
                }
                // 2) l4 字段
                Object l4 = getFieldOrNull(footer, "l4");
                if (l4 != null) {
                    String t = reflectGetText(l4);
                    if (t != null && !t.isEmpty()) {
                        LogWriter.log(TAG, "findInputView: l4 字段非空，采用 " + l4.getClass().getName());
                        return l4;
                    }
                }
                // 3) ChatFooter 内遍历，选可见且非空 EditText
                if (footer instanceof android.view.View) {
                    Object best = findBestVisibleEditText((android.view.View) footer);
                    if (best != null) {
                        LogWriter.log(TAG, "findInputView: ChatFooter 内最佳 " + best.getClass().getName());
                        return best;
                    }
                }
            } else {
                LogWriter.log(TAG, "findInputView: 未找到 ChatFooter（" + AiConst.CLS_CHAT_FOOTER + "）");
            }
            // 4) 全局兜底
            Object best = findBestVisibleEditText(decor);
            if (best != null) return best;
            return findEditTextRecursive(decor);
        } catch (Throwable t) {
            LogWriter.log(TAG, "findInputView 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private static Object findInnerEditText(Object m) {
        if (m instanceof android.view.View) {
            Object best = findBestVisibleEditText((android.view.View) m);
            if (best != null) return best;
        }
        return null;
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
            if (t != null) {
                String s = t.toString();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "reflectGetText getText 失败: " + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
        // getText 为空：MMFlexEditText 真实文本可能在内部 EditText
        if (view instanceof android.view.View) {
            Object inner = findBestVisibleEditText((android.view.View) view);
            if (inner instanceof EditText) {
                String s = ((EditText) inner).getText().toString();
                LogWriter.log(TAG, "reflectGetText: 内部 EditText 长度=" + s.length());
                return s;
            }
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

    private static Object findBestVisibleEditText(android.view.View v) {
        List<EditText> list = new ArrayList<>();
        collectAllEditText(v, list);
        LogWriter.log(TAG, "findBestVisibleEditText: 共 " + list.size() + " 个 EditText");
        for (EditText et : list) {
            if (et.getVisibility() == android.view.View.VISIBLE && et.getText().length() > 0) {
                LogWriter.log(TAG, "findBestVisibleEditText: 可见非空 长度=" + et.getText().length()
                        + " " + et.getClass().getName());
                return et;
            }
        }
        for (EditText et : list) {
            if (et.getVisibility() == android.view.View.VISIBLE) {
                LogWriter.log(TAG, "findBestVisibleEditText: 可见(空) " + et.getClass().getName());
                return et;
            }
        }
        if (!list.isEmpty()) return list.get(0);
        return null;
    }

    private static void collectAllEditText(android.view.View v, List<EditText> out) {
        if (v instanceof EditText) {
            out.add((EditText) v);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAllEditText(g.getChildAt(i), out);
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
