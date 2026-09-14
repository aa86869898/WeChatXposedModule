package com.leshao.v3.wm;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.hook.WmChatHook;
import com.leshao.v3.wm.hook.WmGroupHook;
import com.leshao.v3.wm.utils.WmPrefs;
import com.leshao.v3.wm.utils.WmReflect;

import java.util.List;

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
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static Runnable sPendingShow;
    private static volatile Activity sResumedActivity;
    private static boolean sReconcileStarted;

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
            Class<?> chatClass = null;
            try {
                chatClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
            } catch (Throwable ignored) {}
            if (chatClass == null) {
                LogWriter.log(TAG, "\u2717 chat: ChattingUI not found");
                return;
            }
            LogWriter.log(TAG, "chat class: " + chatClass.getName());
            // 诊断：打印类层次
            Class<?> cur = chatClass;
            StringBuilder hier = new StringBuilder("hierarchy: ");
            while (cur != null) { hier.append(cur.getSimpleName()).append(" < "); cur = cur.getSuperclass(); }
            LogWriter.log(TAG, hier.toString());

            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        sResumedActivity = (Activity) p.thisObject;
                        String clsName = p.thisObject.getClass().getName();
                        LogWriter.log(TAG, "onResume: " + clsName);
                        if (clsName.equals("com.tencent.mm.ui.LauncherUI")) {
                            // 返回主页，关闭悬浮球（MMEditText detach 不触发，微信只隐藏视图）
                            WmChatHook.dismissTitleBtn();
                            WmGroupHook.dismissGroupBtn();
                        } else if (clsName.equals("com.tencent.mm.ui.chatting.ChattingUI")) {
                            handleChatResume(p.thisObject, cl);
                        }
                    } catch (Exception e) {
                        LogWriter.log(TAG, "chat window err: " + e.getMessage());
                    }
                }
            });

            // ChattingUI 自身的 onCreate/onResume（子类重写版本，二次进入时 MMEditText 复用不重新 attach，
            // 必须由这些生命周期兜底触发悬浮球重显）
            try {
                XposedBridge.hookAllMethods(chatClass, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "ChattingUI.onCreate()");
                            handleChatResume(p.thisObject, cl);
                        } catch (Exception e) {
                            LogWriter.log(TAG, "ChattingUI.onCreate err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onCreate)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUI.onCreate hook err: " + e.getMessage());
            }
            try {
                XposedBridge.hookAllMethods(chatClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "ChattingUI.onResume()");
                            handleChatResume(p.thisObject, cl);
                        } catch (Exception e) {
                            LogWriter.log(TAG, "ChattingUI.onResume err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onResume)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUI.onResume hook err: " + e.getMessage());
            }

            // ChattingUIFragment lifecycle: M0=open, O0=close
            try {
                Class<?> fragClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
                java.lang.reflect.Method m0 = fragClass.getDeclaredMethod("M0");
                XposedBridge.hookMethod(m0, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            Object frag = p.thisObject;
                            LogWriter.log(TAG, "ChattingUIFragment.M0() open");
                            handleChatResume(frag, cl);
                        } catch (Exception e) {
                            LogWriter.log(TAG, "M0 err: " + e.getMessage());
                        }
                    }
                });
                java.lang.reflect.Method o0 = fragClass.getDeclaredMethod("O0");
                XposedBridge.hookMethod(o0, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "ChattingUIFragment.O0() close");
                            WmChatHook.dismissTitleBtn();
                            WmGroupHook.dismissGroupBtn();
                        } catch (Throwable e) {
                            LogWriter.log("WmEntry", "O0 err: " + e);
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (ChattingUIFragment M0/O0)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUIFragment hook err: " + e.getMessage());
            }

            // BaseChattingUIFragment lifecycle fallback: hook onCreate/onResume
            try {
                Class<?> baseFrag = XposedHelpers.findClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment", cl);
                XposedBridge.hookAllMethods(baseFrag, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        LogWriter.log(TAG, "BaseChattingUIFragment.onCreate()");
                    }
                });
                XposedBridge.hookAllMethods(baseFrag, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "BaseChattingUIFragment.onResume() -> show balls");
                            handleChatResume(p.thisObject, cl);
                        } catch (Exception e) {
                            LogWriter.log(TAG, "baseFrag onResume err: " + e.getMessage());
                        }
                    }
                });
                XposedBridge.hookAllMethods(baseFrag, "onPause", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        LogWriter.log(TAG, "BaseChattingUIFragment.onPause()");
                        WmChatHook.dismissTitleBtn();
                        WmGroupHook.dismissGroupBtn();
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (BaseChattingUIFragment lifecycle)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "BaseChattingUIFragment hook err: " + e.getMessage());
            }

            // ChattingUIFragment 自身生命周期：二次进入(fragment 复用)时 MMEditText 不会重新 attach、
            // M0 不再调用，必须由 onHiddenChanged/setUserVisibleHint/onResume 兜底重显悬浮球
            try {
                Class<?> fragClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
                hookChatFragMethod(fragClass, "onHiddenChanged", new Class<?>[]{boolean.class}, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            boolean hidden = (Boolean) p.args[0];
                            LogWriter.log(TAG, "ChattingUIFragment.onHiddenChanged hidden=" + hidden);
                            if (hidden) {
                                WmChatHook.dismissTitleBtn();
                                WmGroupHook.dismissGroupBtn();
                            } else {
                                handleChatResume(p.thisObject, cl);
                            }
                        } catch (Throwable e) { LogWriter.log(TAG, "onHiddenChanged err: " + e.getMessage()); }
                    }
                });
                hookChatFragMethod(fragClass, "setUserVisibleHint", new Class<?>[]{boolean.class}, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            boolean visible = (Boolean) p.args[0];
                            LogWriter.log(TAG, "ChattingUIFragment.setUserVisibleHint visible=" + visible);
                            if (visible) handleChatResume(p.thisObject, cl);
                        } catch (Throwable e) { LogWriter.log(TAG, "setUserVisibleHint err: " + e.getMessage()); }
                    }
                });
                hookChatFragMethod(fragClass, "onResume", new Class<?>[0], new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "ChattingUIFragment.onResume()");
                            handleChatResume(p.thisObject, cl);
                        } catch (Throwable e) { LogWriter.log(TAG, "ChattingUIFragment.onResume err: " + e.getMessage()); }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (ChattingUIFragment lifecycle)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "ChattingUIFragment lifecycle hook err: " + e.getMessage());
            }

            // Fallback: 通过 MMEditText onAttachedToWindow 检测进入聊天窗口
            // ChattingUIFragment.M0/O0 和 BaseChattingUIFragment 生命周期在 8.0.50 不可靠
            try {
                XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            View v = (View) p.thisObject;
                            if (!"com.tencent.mm.ui.widget.MMEditText".equals(v.getClass().getName())) return;
                            LogWriter.log(TAG, "chat detected via MMEditText attached");
                            Activity act = (Activity) v.getContext();
                            if (act == null) return;
                            String user = findChatUserFromActivity(act);
                            LogWriter.log(TAG, "chat detected user=" + user);
                            if (user != null && !user.isEmpty()) {
                                final String fUser = user;
                                final Activity fAct = act;
                                final ClassLoader fCl = cl;
                                if (sPendingShow != null) {
                                    sHandler.removeCallbacks(sPendingShow);
                                }
                                sPendingShow = () -> {
                                    WmChatHook.showTitleBtn(fAct, fCl, fUser);
                                    sPendingShow = null;
                                };
                                sHandler.postDelayed(sPendingShow, 600);
                            }
                        } catch (Exception e) {
                            LogWriter.log(TAG, "MMEditText detect err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (MMEditText onAttachedToWindow)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "MMEditText hook err: " + e.getMessage());
            }

            // 从会话列表进入第二个聊天窗口时：ChattingUI 为复用 Activity，MMEditText 不会重新 attach，
            // 通过 onNewIntent 检测切换会话并重显悬浮球
            try {
                Class<?> chatUiClass = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUI", cl);
                XposedBridge.hookAllMethods(chatUiClass, "onNewIntent", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "ChattingUI.onNewIntent");
                            handleChatResume(p.thisObject, cl);
                        } catch (Exception e) {
                            LogWriter.log(TAG, "onNewIntent err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window (ChattingUI.onNewIntent)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "onNewIntent hook err: " + e.getMessage());
            }

            // 离开聊天窗口时关闭悬浮球（MMEditText detach）
            try {
                XposedBridge.hookAllMethods(View.class, "onDetachedFromWindow", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            View v = (View) p.thisObject;
                            if (!"com.tencent.mm.ui.widget.MMEditText".equals(v.getClass().getName())) return;
                            LogWriter.log(TAG, "chat closed via MMEditText detached");
                            WmChatHook.dismissTitleBtn();
                            WmGroupHook.dismissGroupBtn();
                        } catch (Exception e) {
                            LogWriter.log(TAG, "MMEditText detach err: " + e.getMessage());
                        }
                    }
                });
                LogWriter.log(TAG, "\u2713 chat window dismiss (MMEditText onDetachedFromWindow)");
            } catch (Throwable e) {
                LogWriter.log(TAG, "MMEditText detach hook err: " + e.getMessage());
            }

            // 轮询 reconciler：不依赖任何 hook 触发。二次进入(fragment 复用)时 MMEditText 不重 attach、
            // M0/onResume 可能不触发，每 400ms 检查 LauncherUI 内聊天 fragment 可见性来对齐 ⚡ 显示/隐藏
            if (!sReconcileStarted) {
                sReconcileStarted = true;
                final ClassLoader fCl = cl;
                sHandler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (sResumedActivity == null) return;
                        try { reconcileChatBall(fCl); } catch (Throwable ignored) {}
                        sHandler.postDelayed(this, 400);
                    }
                }, 400);
                LogWriter.log(TAG, "✓ chat window (reconcile poll started)");
            }
        } catch (Exception e) {
            LogWriter.log(TAG, "\u2717 chat:" + e.getMessage());
        }
    }

    /** 轮询对齐 ⚡ 悬浮球与聊天窗口状态（幂等，不依赖 fragment/生命周期 hook） */
    private static void reconcileChatBall(ClassLoader cl) {
        Activity act = sResumedActivity;
        if (act == null || act.isFinishing()) return;
        String clsName = act.getClass().getName();
        if (!"com.tencent.mm.ui.LauncherUI".equals(clsName)
                && !"com.tencent.mm.ui.chatting.ChattingUI".equals(clsName)) return;
        String user = findChatUserFromActivity(act);
        boolean visible = isChattingFragmentVisible(act);
        if (visible && user != null && !user.isEmpty()) {
            WmChatHook.showTitleBtn(act, cl, user);
        } else {
            WmChatHook.dismissTitleBtn();
        }
    }

    /** 判断 LauncherUI 内 ChattingUIFragment 是否真正显示（getView().isShown 优先，避免恢复态误判） */
    private static boolean isChattingFragmentVisible(Activity act) {
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm == null) return false;
            List fragments = (List) XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null) return false;
            for (Object f : fragments) {
                if (!"com.tencent.mm.ui.chatting.ChattingUIFragment".equals(f.getClass().getName())) continue;
                try {
                    Object fv = XposedHelpers.callMethod(f, "getView");
                    if (fv instanceof View) {
                        View v = (View) fv;
                        if (v.isShown()) return true;
                    }
                } catch (Throwable ignored) {}
                try {
                    Object v = XposedHelpers.callMethod(f, "isVisible");
                    if (v instanceof Boolean && (Boolean) v) return true;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 沿类链查找与参数签名匹配的方法并 hook（不存在则静默跳过，兼容不同微信版本） */
    private static void hookChatFragMethod(Class<?> fragCls, String name, Class<?>[] paramTypes, XC_MethodHook hook) {
        try {
            java.lang.reflect.Method m = null;
            Class<?> cur = fragCls;
            while (cur != null && cur != Object.class) {
                try {
                    m = cur.getDeclaredMethod(name, paramTypes);
                    break;
                } catch (NoSuchMethodException e) {
                    cur = cur.getSuperclass();
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, hook);
            LogWriter.log(TAG, "\u2713 fragment " + name + " hook (" + m.getDeclaringClass().getSimpleName() + ")");
        } catch (Throwable e) {
            LogWriter.log(TAG, "fragment " + name + " hook err: " + e.getMessage());
        }
    }

    private static void handleChatResume(Object obj, ClassLoader cl) {
        Activity act = (obj instanceof Activity) ? (Activity) obj
            : (Activity) XposedHelpers.callMethod(obj, "getActivity");
        if (act == null) { LogWriter.log(TAG, "handleChatResume: act null"); return; }
        String user = null;
        try {
            user = WmReflect.getCurrentChatUser(act.getIntent());
        } catch (Exception ignored) {}
        if (user == null || user.isEmpty()) {
            // 8.0.49 聊天窗口为 LauncherUI 内嵌 fragment：Activity intent 无 Chat_User，
            // 从 fragment arguments 兜底解析
            user = findChatUserFromActivity(act);
        }
        LogWriter.log(TAG, "handleChatResume user=" + user + " act=" + act.getClass().getSimpleName());
        if (user == null || user.isEmpty()) {
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
        final Activity fAct = act;
        final ClassLoader fCl = cl;
        // 去重：多路径（onCreate/onResume/M0/MMEditText attach/onNewIntent）并发触发时只保留最后一次
        if (sPendingShow != null) {
            sHandler.removeCallbacks(sPendingShow);
        }
        sPendingShow = () -> {
            sPendingShow = null;
            WmChatHook.showTitleBtn(fAct, fCl, fUser);
        };
        sHandler.postDelayed(sPendingShow, 600);
    }

    /** 从 Activity 的 FragmentManager 中查找 ChattingUIFragment 并获取聊天对象 */
    private static String findChatUserFromActivity(Activity act) {
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm == null) return null;
            List fragments = (List) XposedHelpers.callMethod(fm, "getFragments");
            if (fragments == null) return null;
            for (Object f : fragments) {
                if (!"com.tencent.mm.ui.chatting.ChattingUIFragment".equals(f.getClass().getName())) continue;
                // 1) getArguments
                try {
                    Object args = XposedHelpers.callMethod(f, "getArguments");
                    if (args instanceof android.os.Bundle) {
                        String u = ((android.os.Bundle) args).getString("Chat_User");
                        if (u != null && !u.isEmpty()) return u;
                    }
                } catch (Throwable ignored) {}
                // 2) scan fields
                String u = WmReflect.getChatUserFromFragment(f);
                if (u != null) return u;
            }
        } catch (Throwable e) {
            LogWriter.log(TAG, "findChatUserFromActivity err: " + e.getMessage());
        }
        return null;
    }

    // ===== 群详情页入口 =====
    // 用户要求: 聊天详情页不显示悬浮球。群管理功能已集成在聊天窗口 ⚡ 面板的"乐少群管理"区域，
    // 因此不再在 ChatroomInfoUI 注入 🛡 悬浮球，仅保留日志观测。
    static void injectGroupInfo(ClassLoader cl) {
        try {
            XposedHelpers.findClass("com.tencent.mm.chatroom.ui.ChatroomInfoUI", cl);
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String clsName = p.thisObject.getClass().getName();
                        if (!"com.tencent.mm.chatroom.ui.ChatroomInfoUI".equals(clsName)) return;
                        Activity act = (Activity) p.thisObject;
                        String room = null;
                        try {
                            room = act.getIntent().getStringExtra("Chatroom_Name");
                        } catch (Exception ignored) {}
                        if (room == null || room.isEmpty()) {
                            room = WmReflect.getCurrentChatUser(act.getIntent());
                        }
                        LogWriter.log(TAG, "ChatroomInfoUI.onResume room=" + room + " (详情页悬浮球已移除)");
                    } catch (Exception e) {
                        LogWriter.log(TAG, "group info err: " + e.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "\u2713 群详情入口");
        } catch (Exception e) {
            LogWriter.log(TAG, "\u2717 群详情:" + e.getMessage());
        }
    }
}
