package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.ContextMenu;
import android.view.MenuItem;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class VoiceForwardHook {

    private static final String TAG = "VoiceFwd";
    private static volatile boolean sHooked = false;
    private static volatile boolean sEnabled = false;
    private static volatile Activity sChatAct;
    private static volatile Object sPendingMsg;
    private static volatile String sPendingTalker;

    public static void setEnabled(boolean v) {
        sEnabled = v;
        LogWriter.log(TAG, "setEnabled=" + v);
    }

    public static void hook() {
        if (sHooked) return;
        ClassLoader cl = ContextManager.getClassLoader();
        if (cl == null) { LogWriter.log(TAG, "cl not ready"); return; }

        hookChattingUIContextMenu(cl);

        hookBaseChattingUIFragment(cl);

        sHooked = true;
        LogWriter.log(TAG, "hooks installed (A+B+C scheme)");
    }

    // ===== A方案: Hook ChattingUI context menu =====
    private static void hookChattingUIContextMenu(ClassLoader cl) {
        try {
            Class<?> chattingUI = cl.loadClass("com.tencent.mm.ui.chatting.ChattingUI");

            XposedBridge.hookAllMethods(chattingUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sChatAct = (Activity) param.thisObject;
                }
            });

            XposedBridge.hookAllMethods(chattingUI, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sChatAct == param.thisObject) sChatAct = null;
                }
            });

            // A-scheme: removed per fix5 (findMethodExact failing, C-scheme covers it)

            XposedBridge.hookAllMethods(chattingUI, "onContextItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sEnabled) return;
                    try {
                        MenuItem item = (MenuItem) param.args[0];
                        String title = item.getTitle() != null ? item.getTitle().toString() : "";
                        if (!"语音转发".equals(title)) return;

                        executeForward();
                        param.setResult(true);
                        LogWriter.log(TAG, "A-scheme: voice fwd triggered via menu");
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "A-scheme menu click err: " + t.getMessage());
                    }
                }
            });

        } catch (Throwable t) {
            LogWriter.log(TAG, "A-scheme failed: " + t.getMessage());
        }
    }

    // ===== C方案: Hook BaseChattingUIFragment RecyclerView长按 =====
    private static void hookBaseChattingUIFragment(ClassLoader cl) {
        try {
            Class<?> fragmentCls = cl.loadClass("com.tencent.mm.ui.chatting.BaseChattingUIFragment");
            XposedHelpers.findAndHookMethod(fragmentCls, "onCreateView",
                android.view.LayoutInflater.class,
                android.view.ViewGroup.class,
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            android.view.View root = (android.view.View) param.getResult();
                            if (root == null) return;
                            setLongClickListener(root);
                        } catch (Throwable ignored) {}
                    }
                });
            LogWriter.log(TAG, "C-scheme: BaseChattingUIFragment hook OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "C-scheme: " + t.getMessage());
        }
    }

    private static void setLongClickListener(android.view.View view) {
        if (view instanceof android.widget.ListView) {
            ((android.widget.ListView) view).setOnItemLongClickListener(
                (parent, v, position, id) -> {
                    if (!sEnabled) return false;
                    try {
                        tryCaptureVoiceMsg(v);
                        if (sPendingMsg != null) {
                            LogWriter.log(TAG, "C-scheme: captured via long click, pos=" + position);
                        }
                    } catch (Throwable ignored) {}
                    return false;
                });
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                setLongClickListener(vg.getChildAt(i));
            }
        }
    }

    private static void tryCaptureVoiceMsg(android.view.View v) {
        sPendingMsg = null;
        sPendingTalker = null;
        if (v == null) return;

        try {
            Object tag = v.getTag();
            if (tag == null) return;

            try {
                long msgId = XposedHelpers.getLongField(tag, "field_msgId");
                String talker = (String) XposedHelpers.getObjectField(tag, "field_talker");
                if (talker != null && msgId != 0) {
                    sPendingMsg = tag;
                    sPendingTalker = talker;
                    return;
                }
            } catch (Throwable ignored) {}

            try {
                long msgId = (long) XposedHelpers.callMethod(tag, "getMsgId");
                sPendingMsg = tag;
                sPendingTalker = "";
                return;
            } catch (Throwable ignored) {}

            for (java.lang.reflect.Field f : tag.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(tag);
                    if (val instanceof Long && (Long) val > 0 && f.getName().contains("Id")) {
                        sPendingMsg = tag;
                        sPendingTalker = "";
                        return;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ===== B方案: Direct forwarding =====
    private static void executeForward() {
        if (!sEnabled) return;

        try {
            Object msg = sPendingMsg;
            String talker = sPendingTalker;
            sPendingMsg = null;
            sPendingTalker = null;

            if (msg == null) {
                LogWriter.log(TAG, "B-scheme: no msg captured");
                return;
            }

            long msgId = extractMsgId(msg);
            String extTalker = extractTalker(msg);
            if (talker == null || talker.isEmpty()) talker = extTalker;
            if (talker == null || talker.isEmpty()) talker = "";

            LogWriter.log(TAG, "B-scheme: fwd voice msgId=" + msgId + " talker=" + talker);

            Context ctx = sChatAct;
            if (ctx == null) ctx = ContextManager.getAppContext();
            if (ctx == null) { LogWriter.log(TAG, "B-scheme: no context"); return; }

            ClassLoader cl = ctx.getClassLoader();

            Class<?> forwardUI = null;
            for (String name : new String[]{
                "com.tencent.mm.ui.transmit.SelectConversationUI",
                "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            }) {
                try {
                    forwardUI = cl.loadClass(name);
                    break;
                } catch (Throwable ignored) {}
            }

            if (forwardUI == null) {
                LogWriter.log(TAG, "B-scheme: no forwarding UI class found");
                return;
            }

            Intent intent = new Intent(ctx, forwardUI);
            intent.putExtra("Retr_Msg_content", talker);
            intent.putExtra("Retr_Msg_Type", 1);
            intent.putExtra("Retr_Msg_Id", msgId);
            intent.putExtra("Retr_Msg_Img_Type", 0);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);

            LogWriter.log(TAG, "B-scheme: started " + forwardUI.getSimpleName());

        } catch (Throwable t) {
            LogWriter.log(TAG, "B-scheme err: " + t.getClass().getSimpleName()
                + " " + t.getMessage());
        }
    }

    private static long extractMsgId(Object msg) {
        try {
            return (long) XposedHelpers.callMethod(msg, "getMsgId");
        } catch (Throwable t1) {
            try {
                return XposedHelpers.getLongField(msg, "field_msgId");
            } catch (Throwable t2) {
                try {
                    return XposedHelpers.getLongField(msg, "msgId");
                } catch (Throwable t3) {
                    return 0;
                }
            }
        }
    }

    private static String extractTalker(Object msg) {
        try {
            return (String) XposedHelpers.callMethod(msg, "N0");
        } catch (Throwable t1) {
            try {
                return (String) XposedHelpers.getObjectField(msg, "field_talker");
            } catch (Throwable t2) {
                try {
                    return (String) XposedHelpers.getObjectField(msg, "talker");
                } catch (Throwable t3) {
                    return "";
                }
            }
        }
    }
}
