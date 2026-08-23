package com.leshao.v3.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import com.leshao.v3.ContextManager;
import com.leshao.v3.model.ModuleConfig;

/**
 * [功能33/34/39/41/42] 群聊管理 — 生产级完整实现
 * ==============================================
 * 
 * #33 群成员批量操作   — 批量踢人 + 导出成员列表
 * #34/42 群聊匿名发言  — 修改气泡发送者昵称
 * #39 群成员变更日志   — 踢人/退群/邀请记录
 * #41 群公告已读回执   — 群信息页检查公告
 * #43 自动审核踢人     — 广告关键词检测 + 自动踢人
 * #44 入群欢迎         — 新成员入群自动发送欢迎消息
 */
public class GroupFeatures {

    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static File logFile = new File("/sdcard/LeShaoV3Logs/group_changes.log");
    private static final Object logLock = new Object();
    private static String anonymousName = "匿名群友";
    private static volatile boolean sEnabled = true;
    private static Object sMsgStorage;
    private static final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> violationCount = new ConcurrentHashMap<>();
    // #44 入群欢迎去重: room|排序成员 -> 最近发送时间戳(ms)
    private static final Map<String, Long> sWelcomeDedupe = new ConcurrentHashMap<>();

    public static void setEnabled(boolean enabled) { sEnabled = enabled; }

    public static void hook(ClassLoader cl) {
        ModuleConfig config = ModuleConfig.load(ContextManager.getPrefs());
        if (config == null || !config.groupFeaturesEnabled) return;
        anonymousName = HookConfig.getString("anonymous_name", "匿名群友");

        initMsgStorage(cl);

        if (HookConfig.isEnabled("group_member_log"))  hookMemberLog(cl);
        if (HookConfig.isEnabled("group_announce"))    hookAnnounceRead(cl);
        if (HookConfig.isEnabled("group_batch_op"))    hookBatchOp(cl);

        hookAnonymous(cl);
        hookAutoModeration(cl);
        hookGroupWelcome(cl);
    }

    private static void initMsgStorage(ClassLoader cl) {
        try {
            Class<?> d9 = VersionCompat.findMsgStorageShortClass(cl);
            if (d9 == null) return;
            Object service = XposedHelpers.callStaticMethod(d9, "b");
            if (service != null) {
                sMsgStorage = XposedHelpers.callMethod(service, "u");
                XposedBridge.log("[Group] f9实例获取成功");
            }
        } catch (Throwable t) {
            XposedBridge.log("[Group] f9实例获取失败: " + t.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════
    // #39 群成员变更日志
    // ══════════════════════════════════════════════════════
    private static void hookMemberLog(ClassLoader cl) {
        // --- REMOVED (踢出) ---
        try {
            Class<?> event = XposedHelpers.findClass(
                    "com.tencent.mm.autogen.events.NetSceneDelChatRoomMemberEvent", cl);
            XposedBridge.hookAllMethods(event, "callback", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object e = param.args[0];
                        Object data = XposedHelpers.getObjectField(e, "data");
                        if (data != null) {
                            String chatroom = "" + XposedHelpers.getObjectField(data, "chatroom");
                            String target = "" + XposedHelpers.getObjectField(data, "username");
                            String operator = getOperatorFromData(data);
                            String msg = "[" + sdf.format(new Date()) + "] [REMOVED] chatroom=" + chatroom + " operator=" + operator + " target=" + target;
                            XposedBridge.log("[Group] " + msg);
                            writeLog(msg);
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}

        // --- ADDED (加入群聊) ---
        addMemberEventHook(cl, "[ADDED]", new String[]{
            "com.tencent.mm.autogen.events.NetSceneAddChatRoomMemberEvent",
            "com.tencent.mm.autogen.events.AddChatRoomMemberEvent",
            "com.tencent.mm.autogen.events.ChatRoomMemberAddEvent",
        });

        // --- LEFT (主动退群) ---
        addMemberEventHook(cl, "[LEFT]", new String[]{
            "com.tencent.mm.autogen.events.NetSceneQuitChatRoomEvent",
            "com.tencent.mm.autogen.events.QuitChatRoomEvent",
            "com.tencent.mm.autogen.events.ChatRoomMemberRemoveEvent",
            "com.tencent.mm.autogen.events.ChatRoomMemberDelEvent",
        });

        // --- INVITED (邀请入群) ---
        hookInviteUI(cl);

        // --- DelChatroomMemberUI 备用监控 ---
        try {
            Class<?> delUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.DelChatroomMemberUI", cl);
            XposedBridge.hookAllMethods(delUI, "R6", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 4) {
                        String chatroom = "" + param.args[0];
                        String who = "" + param.args[3];
                        XposedBridge.log("[Group] 移除中: " + who + " from " + chatroom);
                    }
                }
            });
        } catch (Throwable t) {}
        XposedBridge.log("[Group] #39 成员变更日志完成");
    }

    private static void addMemberEventHook(ClassLoader cl, String eventType, String[] candidates) {
        for (String name : candidates) {
            try {
                Class<?> event = XposedHelpers.findClass(name, cl);
                XposedBridge.hookAllMethods(event, "callback", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object e = param.args[0];
                            Object data = XposedHelpers.getObjectField(e, "data");
                            if (data != null) {
                                String chatroom = "" + XposedHelpers.getObjectField(data, "chatroom");
                                String target = "" + XposedHelpers.getObjectField(data, "username");
                                String operator = getOperatorFromData(data);
                                String msg = "[" + sdf.format(new Date()) + "] " + eventType + " chatroom=" + chatroom + " operator=" + operator + " target=" + target;
                                XposedBridge.log("[Group] " + msg);
                                writeLog(msg);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[Group] " + eventType + " Hook成功: " + name);
                return;
            } catch (Throwable ignored) {}
        }
        XposedBridge.log("[Group] " + eventType + " Hook失败，所有候选类名未找到");
    }

    private static void hookInviteUI(ClassLoader cl) {
        try {
            Class<?> inviteUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.ChatRoomInviteUI", cl);
            XposedBridge.hookAllMethods(inviteUI, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object activity = param.thisObject;
                        String chatroom = tryGetChatroomFromIntent(activity);
                        if (chatroom.isEmpty()) return;
                        java.util.List<String> invitees = tryGetInvitees(activity);
                        if (invitees.isEmpty()) return;
                        String operator = ModuleConfig.getCurrentWxid();
                        for (String target : invitees) {
                            String msg = "[" + sdf.format(new Date()) + "] [INVITED] chatroom=" + chatroom + " operator=" + operator + " target=" + target;
                            XposedBridge.log("[Group] " + msg);
                            writeLog(msg);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Group] [INVITED] ChatRoomInviteUI Hook成功");
        } catch (Throwable t) {
            addMemberEventHook(cl, "[INVITED]", new String[]{
                "com.tencent.mm.autogen.events.InviteChatRoomMemberEvent",
                "com.tencent.mm.autogen.events.NetSceneInviteChatRoomMemberEvent",
                "com.tencent.mm.autogen.events.ChatRoomInviteEvent",
            });
        }
    }

    private static String getOperatorFromData(Object data) {
        for (String field : new String[]{"inviter_username", "operator", "opUsername", "inviter"}) {
            try { return "" + XposedHelpers.getObjectField(data, field); }
            catch (Throwable ignored) {}
        }
        return ModuleConfig.getCurrentWxid();
    }

    private static String tryGetChatroomFromIntent(Object activity) {
        try {
            Object intent = XposedHelpers.callMethod(activity, "getIntent");
            for (String key : new String[]{"Chatroom_Name", "room_name", "chatroom"}) {
                try {
                    String val = (String) XposedHelpers.callMethod(intent, "getStringExtra", key);
                    if (val != null && !val.isEmpty()) return val;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static java.util.List<String> tryGetInvitees(Object activity) {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            Object intent = XposedHelpers.callMethod(activity, "getIntent");
            java.util.ArrayList<String> extras = (java.util.ArrayList<String>)
                    XposedHelpers.callMethod(intent, "getStringArrayListExtra", "Select_Conv_User");
            if (extras != null) list.addAll(extras);
        } catch (Throwable ignored) {}
        return list;
    }

    // ══════════════════════════════════════════════════════
    // #41 群公告已读
    // ══════════════════════════════════════════════════════
    private static void hookAnnounceRead(ClassLoader cl) {
        try {
            Class<?> infoUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.ChatroomInfoUI", cl);
            XposedBridge.hookAllMethods(infoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (String f : new String[]{"mAnnounce", "n", "o", "p"}) {
                            try {
                                Object a = XposedHelpers.getObjectField(param.thisObject, f);
                                if (a != null) {
                                    String text = (String) XposedHelpers.callMethod(a, "getContent");
                                    if (text != null && !text.isEmpty()) {
                                        XposedBridge.log("[Group] 群公告: " +
                                                text.substring(0, Math.min(50, text.length())));
                                        break;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}
    }

    // ══════════════════════════════════════════════════════
    // #33 群成员批量操作
    // ══════════════════════════════════════════════════════
    private static void hookBatchOp(ClassLoader cl) {
        try {
            Class<?> gs = XposedHelpers.findClass(
                    "com.tencent.mm.ui.contact.GroupCardSelectUI", cl);
            XposedBridge.hookAllMethods(gs, "onCreateOptionsMenu", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    android.view.Menu menu = (android.view.Menu) param.args[0];
                    if (menu != null) {
                        menu.add(0, 99990, 0, "导出成员列表");
                        menu.add(0, 99991, 0, "批量踢出");
                        menu.add(0, 99992, 0, "群信息报告");
                        menu.add(0, 99993, 0, "查看群公告");
                    }
                }
            });
            XposedBridge.hookAllMethods(gs, "onOptionsItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    android.view.MenuItem item = (android.view.MenuItem) param.args[0];
                    if (item.getItemId() == 99990) {
                        exportMemberList(param.thisObject);
                        param.setResult(true);
                    } else if (item.getItemId() == 99991) {
                        performBatchKick(param.thisObject);
                        param.setResult(true);
                    } else if (item.getItemId() == 99992) {
                        WxMasterFeatures.exportRoomReport(param.thisObject);
                        param.setResult(true);
                    } else if (item.getItemId() == 99993) {
                        WxMasterFeatures.showRoomNotice(param.thisObject);
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable t) {}

        try {
            Class<?> delUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.DelChatroomMemberUI", cl);
            XposedBridge.hookAllMethods(delUI, "O6", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 4 && param.args[3] instanceof List) {
                            int size = ((List<?>) param.args[3]).size();
                            XposedBridge.log("[Group] 批量删除 " + size + " 人");
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}
        XposedBridge.log("[Group] #33 批量操作完成");
    }

    private static void performBatchKick(Object activity) {
        try {
            android.app.Activity act = (android.app.Activity) activity;
            String chatroom = act.getIntent().getStringExtra("Chatroom_Name");
            if (chatroom == null || chatroom.isEmpty()) {
                Toast.makeText(act, "无法获取群聊ID", Toast.LENGTH_SHORT).show();
                return;
            }

            View root = act.getWindow().getDecorView();
            java.util.List<String> selectedWxids = getSelectedWxids(act, root);

            if (selectedWxids.isEmpty()) {
                Toast.makeText(act, "请先勾选要踢出的成员", Toast.LENGTH_SHORT).show();
                return;
            }

            final String finalChatroom = chatroom;
            final java.util.List<String> finalSelected = selectedWxids;
            final ClassLoader cl = act.getClassLoader();

            new AlertDialog.Builder(act)
                    .setTitle("批量踢出确认")
                    .setMessage("确定要踢出 " + selectedWxids.size() + " 名成员吗？")
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            executeBatchKick(cl, finalChatroom, finalSelected);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log("[Group] 批量踢出失败: " + t.getMessage());
        }
    }

    private static java.util.List<String> getSelectedWxids(android.app.Activity act, View root) {
        java.util.List<String> wxids = new java.util.ArrayList<>();

        for (String field : new String[]{"cne", "oYD", "mSelectedMembers", "selectedUsers"}) {
            try {
                Object val = XposedHelpers.getObjectField(act, field);
                if (val instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) val;
                    for (Object item : list) {
                        String wxid = extractWxid(item);
                        if (wxid != null && !wxid.isEmpty()) wxids.add(wxid);
                    }
                    if (!wxids.isEmpty()) return wxids;
                }
            } catch (Throwable ignored) {}
        }

        java.util.List<String> names = new java.util.ArrayList<>();
        collectCheckedMembers(root, names);
        return names;
    }

    private static void collectCheckedMembers(View v, java.util.List<String> names) {
        if (v instanceof CheckBox && ((CheckBox) v).isChecked()) {
            String name = findMemberNameNear((CheckBox) v);
            if (name != null && !name.isEmpty()) names.add(name);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++)
                collectCheckedMembers(vg.getChildAt(i), names);
        }
    }

    private static String findMemberNameNear(CheckBox cb) {
        View parent = (View) cb.getParent();
        if (parent instanceof ViewGroup) {
            return findFirstNameText((ViewGroup) parent);
        }
        return null;
    }

    private static String findFirstNameText(ViewGroup vg) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence t = ((TextView) child).getText();
                if (t != null && t.length() >= 1 && t.length() <= 30
                        && !t.toString().contains("@")) {
                    return t.toString();
                }
            }
            if (child instanceof ViewGroup) {
                String result = findFirstNameText((ViewGroup) child);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String extractWxid(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) return (String) obj;
        for (String field : new String[]{"field_username", "field_wxid", "field_talker",
                "d1", "d0", "c1", "username", "wxid"}) {
            try { return "" + XposedHelpers.getObjectField(obj, field); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    private static void executeBatchKick(ClassLoader cl, String chatroom,
                                          java.util.List<String> wxids) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            int index = 0;

            @Override
            public void run() {
                if (index >= wxids.size()) return;
                String wxid = wxids.get(index);
                XposedBridge.log("[Group] 批量踢出[" + (index + 1) + "/" + wxids.size() + "]: " + wxid);
                kickMember(cl, chatroom, wxid);
                index++;
                int delay = 100 + new java.util.Random().nextInt(101);
                new Handler(Looper.getMainLooper()).postDelayed(this, delay);
            }
        });
    }

    private static void exportMemberList(Object activity) {
        try {
            View root = ((android.app.Activity) activity).getWindow().getDecorView();
            StringBuilder sb = new StringBuilder();
            collectTexts(root, sb);
            
            File dir = new File("/sdcard/LeShaoV3Logs/");
            dir.mkdirs();
            String chatroom = ((android.app.Activity) activity).getIntent()
                    .getStringExtra("Chatroom_Name");
            if (chatroom == null) chatroom = "group";
            File f = new File(dir, chatroom.replace("@", "_") + "_members.txt");
            FileWriter fw = new FileWriter(f);
            try { fw.write(sb.toString()); } finally { fw.close(); }
            
            Toast.makeText((android.app.Activity) activity,
                    "成员列表已导出: " + f.getName(), Toast.LENGTH_LONG).show();
            XposedBridge.log("[Group] 导出成员: " + f.getName());
        } catch (Throwable t) {
            XposedBridge.log("[Group] 导出失败: " + t.getMessage());
        }
    }

    private static void collectTexts(View v, StringBuilder sb) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && t.length() >= 2 && t.length() <= 30) {
                sb.append(t.toString()).append("\n");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTexts(vg.getChildAt(i), sb);
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // #34/42 群聊匿名发言
    // ══════════════════════════════════════════════════════
    private static void hookAnonymous(ClassLoader cl) {
        try {
            Class<?> adapter = VersionCompat.findAdapterClass(cl);
            if (adapter == null) return;
            XposedBridge.hookAllMethods(adapter, "getView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int pos = (Integer) param.args[0];
                        View itemView = (View) param.getResult();
                        if (itemView == null) return;

                        Object msgInfo = XposedHelpers.callMethod(param.thisObject, "getItem", pos);
                        if (msgInfo == null) return;

                        String talker = "";
                        try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); }
                        catch (Throwable ignored) {}

                        if (talker != null && talker.endsWith("@chatroom")) {
                            anonymize(itemView, anonymousName);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Group] 匿名: " + anonymousName);
        } catch (Throwable t) {}
    }

    private static void anonymize(View v, String name) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            CharSequence t = tv.getText();
            if (t != null) {
                String s = t.toString();
                if (s.length() >= 2 && s.length() <= 25
                        && !s.contains(":") && !s.contains("：")
                        && !s.matches(".*\\d{2}:\\d{2}.*")
                        && !s.contains("撤回") && !s.contains("加入") && !s.contains("退出")) {
                    tv.setText(name);
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) anonymize(vg.getChildAt(i), name);
        }
    }

    // ══════════════════════════════════════════════════════
    // #43 自动审核踢人 — 广告关键词检测 + 自动踢人
    // ══════════════════════════════════════════════════════
    private static void hookAutoModeration(ClassLoader cl) {
        try {
            Class<?> f9 = null;
            for (String name : new String[]{"com.tencent.mm.storage.f9",
                    "com.tencent.mm.storage.g9"}) {
                try { f9 = XposedHelpers.findClass(name, cl); break; }
                catch (Throwable ignored) {}
            }
            if (f9 == null) return;

            XposedBridge.hookAllMethods(f9, "Ra", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        SharedPreferences prefs = ContextManager.getPrefs();
                        if (!prefs.getBoolean("ls_autokick_enabled", false)) return;

                        if (param.args.length < 2) return;
                        Object msgInfo = param.args[1];
                        if (msgInfo == null) return;

                        String talker = "";
                        try { talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker"); }
                        catch (Throwable ignored) {}
                        if (talker == null || !talker.endsWith("@chatroom")) return;

                        if (isSentByMe(msgInfo)) return;

                        String content = "";
                        try { content = (String) XposedHelpers.getObjectField(msgInfo, "field_content"); }
                        catch (Throwable ignored) {}
                        if (content == null || content.isEmpty()) return;

                        String sender = "";
                        try { sender = (String) XposedHelpers.getObjectField(msgInfo, "field_username"); }
                        catch (Throwable ignored) {}
                        if (sender == null || sender.isEmpty()) return;

                        String myWxid = ModuleConfig.getCurrentWxid();
                        if (sender.equals(myWxid)) return;

                        long msgSvrId = 0;
                        try { msgSvrId = XposedHelpers.getLongField(msgInfo, "field_msgSvrId"); }
                        catch (Throwable ignored) {}

                        String dedupKey = talker + "_" + msgSvrId;
                        if (processedMsgIds.contains(dedupKey)) return;
                        processedMsgIds.add(dedupKey);
                        while (processedMsgIds.size() > 500) {
                            processedMsgIds.remove(processedMsgIds.iterator().next());
                        }

                        Set<String> adKeywords = new HashSet<>();
                        Set<String> kickKeywords = new HashSet<>();
                        try {
                            JSONArray adArr = new JSONArray(prefs.getString("ls_ad_keywords", "[]"));
                            for (int i = 0; i < adArr.length(); i++) adKeywords.add(adArr.getString(i));
                        } catch (Throwable ignored) {}
                        try {
                            JSONArray kkArr = new JSONArray(prefs.getString("ls_kick_keywords", "[]"));
                            for (int i = 0; i < kkArr.length(); i++) kickKeywords.add(kkArr.getString(i));
                        } catch (Throwable ignored) {}

                        if (adKeywords.isEmpty() && kickKeywords.isEmpty()) return;

                        XposedBridge.log("[Group] 检测到违规消息: " + sender
                                + " -> " + content.substring(0, Math.min(30, content.length())));

                        // 黑名单: 命中直接踢
                        if (prefs.getBoolean("ls_blacklist_enabled", true)) {
                            Set<String> blacklist = loadBlacklist(prefs);
                            if (blacklist.contains(sender)) {
                                writeLog(sdf.format(new Date()) + " | " + talker + " | " + sender
                                        + " | BLACKLIST_KICK | " + content.substring(0, Math.min(50, content.length())));
                                XposedBridge.log("[Group] 命中黑名单，直接踢出: " + sender);
                                kickMember(cl, talker, sender);
                                return;
                            }
                        }

                        // 踢人关键词: 命中即踢
                        for (String kw : kickKeywords) {
                            if (!kw.isEmpty() && content.contains(kw)) {
                                writeLog(sdf.format(new Date()) + " | " + talker + " | " + sender
                                        + " | KICK_KEYWORD | " + content.substring(0, Math.min(50, content.length())));
                                XposedBridge.log("[Group] 命中踢人关键词[" + kw + "]，直接踢出: " + sender);
                                kickMember(cl, talker, sender);
                                return;
                            }
                        }

                        // 广告关键词: 计数达阈值踢
                        boolean matchedAd = false;
                        for (String kw : adKeywords) {
                            if (!kw.isEmpty() && content.contains(kw)) {
                                matchedAd = true;
                                break;
                            }
                        }
                        if (!matchedAd) return;

                        String violationKey = talker + ":" + sender;

                        int threshold = 3;
                        try { threshold = Integer.parseInt(prefs.getString("ls_kick_threshold", "3")); }
                        catch (Throwable ignored) {}
                        if (threshold <= 0) threshold = 1;

                        int current = violationCount.getOrDefault(violationKey, 0) + 1;
                        violationCount.put(violationKey, current);
                        writeLog(sdf.format(new Date()) + " | " + talker + " | " + sender
                                + " | AD_DETECTED(" + current + "/" + threshold + ") | "
                                + content.substring(0, Math.min(50, content.length())));

                        if (current < threshold) {
                            XposedBridge.log("[Group] 违规 " + current + "/" + threshold + "，暂不踢出: " + sender);
                            return;
                        }

                        violationCount.remove(violationKey);
                        kickMember(cl, talker, sender);
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Group] #43 自动审核完成");
        } catch (Throwable t) {
            XposedBridge.log("[Group] #43 自动审核失败: " + t.getMessage());
        }
    }

    private static boolean isSentByMe(Object msgInfo) {
        try {
            return (Boolean) XposedHelpers.callMethod(msgInfo, "G1");
        } catch (Throwable e1) {
            try {
                int q1 = (Integer) XposedHelpers.callMethod(msgInfo, "Q1");
                return q1 == 1;
            } catch (Throwable e2) {
                return false;
            }
        }
    }

    private static Set<String> loadBlacklist(SharedPreferences prefs) {
        Set<String> blacklist = new HashSet<>();
        try {
            JSONArray blArr = new JSONArray(prefs.getString("ls_blacklist", "[]"));
            for (int i = 0; i < blArr.length(); i++) {
                org.json.JSONObject o = blArr.optJSONObject(i);
                if (o != null) {
                    String w = o.optString("wxid", "");
                    if (!w.isEmpty()) blacklist.add(w);
                } else {
                    String w = blArr.optString(i, "");
                    if (!w.isEmpty()) blacklist.add(w);
                }
            }
        } catch (Throwable ignored) {}
        return blacklist;
    }

    private static void kickMember(ClassLoader cl, String chatroom, String wxid) {
        try {
            String[] candidates = {
                "com.tencent.mm.plugin.chatroom.model.p",
                "com.tencent.mm.plugin.chatroom.model.NetSceneDelChatRoomMember",
                "com.tencent.mm.plugin.chatroom.model.q",
                "com.tencent.mm.plugin.chatroom.model.o",
                "com.tencent.mm.modelmulti.d",
                "com.tencent.mm.modelmulti.e",
            };
            Class<?> sceneClass = null;
            for (String name : candidates) {
                try { sceneClass = XposedHelpers.findClass(name, cl); break; }
                catch (Throwable ignored) {}
            }
            if (sceneClass == null) {
                XposedBridge.log("[Group] 未找到踢人NetScene类，尝试备用方案");
                tryKickViaDelUI(cl, chatroom, wxid);
                return;
            }

            Object scene = XposedHelpers.newInstance(sceneClass, chatroom, wxid);
            if (scene == null) return;
            try {
                XposedHelpers.callMethod(scene, "doScene");
                XposedBridge.log("[Group] 已踢出: " + wxid + " from " + chatroom);
            } catch (Throwable t1) {
                try {
                    Object dispatcher = XposedHelpers.callStaticMethod(sceneClass, "getDispatcher");
                    if (dispatcher != null) {
                        XposedHelpers.callMethod(dispatcher, "send", scene);
                    }
                } catch (Throwable t2) {
                    tryKickViaDelUI(cl, chatroom, wxid);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[Group] 踢人失败: " + t.getMessage());
            tryKickViaDelUI(cl, chatroom, wxid);
        }
    }

    private static void tryKickViaDelUI(ClassLoader cl, String chatroom, String wxid) {
        try {
            Class<?> delUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.DelChatroomMemberUI", cl);
            for (java.lang.reflect.Method m : delUI.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length >= 4
                        && m.getParameterTypes()[0] == String.class
                        && m.getParameterTypes()[m.getParameterTypes().length - 1] == String.class) {
                    m.setAccessible(true);
                    Object[] args = new Object[m.getParameterTypes().length];
                    args[0] = chatroom;
                    args[args.length - 1] = wxid;
                    m.invoke(null, args);
                    XposedBridge.log("[Group] 备用踢人: " + wxid + " from " + chatroom);
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[Group] 备用踢人也失败: " + t.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════
    // #44 入群欢迎 — 新成员入群自动发送欢迎消息
    // ══════════════════════════════════════════════════════
    private static void hookGroupWelcome(ClassLoader cl) {
        // 首选: 反编译确认的原子入口 e01.v1.t(room, members, roomOwner) = syncAddChatroomMember
        if (hookWelcomeViaSyncAdd(cl)) return;
        // 备选1: 事件类
        try {
            String[] eventCandidates = {
                "com.tencent.mm.autogen.events.NetSceneAddChatRoomMemberEvent",
                "com.tencent.mm.autogen.events.AddChatRoomMemberEvent",
                "com.tencent.mm.autogen.events.ChatRoomMemberAddEvent",
            };
            Class<?> addEvent = null;
            for (String name : eventCandidates) {
                try { addEvent = XposedHelpers.findClass(name, cl); break; }
                catch (Throwable ignored) {}
            }
            if (addEvent != null) {
                XposedBridge.hookAllMethods(addEvent, "callback", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object e = param.args[0];
                            Object data = XposedHelpers.getObjectField(e, "data");
                            if (data == null) return;
                            String chatroom = "";
                            try { chatroom = "" + XposedHelpers.getObjectField(data, "chatroom"); }
                            catch (Throwable ignored) {}
                            if (chatroom.isEmpty()) return;

                            SharedPreferences prefs = ContextManager.getPrefs();
                            if (!prefs.getBoolean("ls_welcome_enabled", false)) return;
                            String welcomeMsg = prefs.getString("ls_welcome_msg", "欢迎加入群聊!");
                            if (welcomeMsg.isEmpty()) welcomeMsg = "欢迎加入群聊!";

                            sendWelcome(cl, prefs, chatroom, welcomeMsg);
                            XposedBridge.log("[Group] 欢迎消息已发送: " + chatroom);
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log("[Group] #44 入群欢迎(Event)完成");
                return;
            }
        } catch (Throwable ignored) {}
        // 备选2: ChatroomInfoUI.onResume
        hookWelcomeViaChatroomInfo(cl);
    }

    /**
     * 首选欢迎触发点: hook e01.v1.t(String room, ArrayList<String> members, String roomOwner)。
     * 该方法是 NetSceneAddChatRoomMember 成功后更新本地成员的原子入口(syncAddChatroomMember)，
     * 每次真实成员入群都会调用, 按 room + 排序后的 members 去重(60s 窗口)。
     */
    private static boolean hookWelcomeViaSyncAdd(ClassLoader cl) {
        try {
            Class<?> v1 = VersionCompat.findChatroomMembersLogicClass(cl);
            if (v1 == null) return false;
            XposedBridge.hookAllMethods(v1, "t", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.args[0] instanceof String)) return;
                        String room = (String) param.args[0];
                        if (room.isEmpty()) return;
                        Object membersObj = param.args[1];
                        List<String> members = new ArrayList<>();
                        if (membersObj instanceof List) {
                            for (Object o : (List<?>) membersObj) {
                                if (o instanceof String) members.add((String) o);
                            }
                        }
                        if (members.isEmpty()) return;

                        // 去重: room + 排序成员
                        List<String> sorted = new ArrayList<>(members);
                        Collections.sort(sorted);
                        String key = room + "|" + sorted.toString();
                        long now = System.currentTimeMillis();
                        Long last = sWelcomeDedupe.get(key);
                        if (last != null && now - last < 60000L) return;
                        sWelcomeDedupe.put(key, now);
                        if (sWelcomeDedupe.size() > 500) sWelcomeDedupe.clear();

                        SharedPreferences prefs = ContextManager.getPrefs();
                        if (!prefs.getBoolean("ls_welcome_enabled", false)) return;
                        String welcomeMsg = prefs.getString("ls_welcome_msg", "欢迎加入群聊!");
                        if (welcomeMsg.isEmpty()) welcomeMsg = "欢迎加入群聊!";

                        sendWelcome(cl, prefs, room, welcomeMsg);
                        XposedBridge.log("[Group] 欢迎消息(syncAdd)已发送: " + room + " members=" + members.size());
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log("[Group] #44 入群欢迎(syncAddChatroomMember e01.v1.t)完成");
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[Group] #44 syncAdd hook 失败: " + t.getMessage());
            return false;
        }
    }

    private static void hookWelcomeViaChatroomInfo(ClassLoader cl) {
        try {
            Class<?> infoUI = XposedHelpers.findClass(
                    "com.tencent.mm.chatroom.ui.ChatroomInfoUI", cl);
            XposedBridge.hookAllMethods(infoUI, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        SharedPreferences prefs = ContextManager.getPrefs();
                        if (!prefs.getBoolean("ls_welcome_enabled", false)) return;
                        String chatroom = "";
                        try {
                            Object intent = XposedHelpers.callMethod(
                                    param.thisObject, "getIntent");
                            chatroom = (String) XposedHelpers.callMethod(intent, "getStringExtra",
                                    "Chatroom_Name");
                        } catch (Throwable ignored) {}
                        if (chatroom.isEmpty()) return;

                        String welcomeMsg = prefs.getString("ls_welcome_msg", "欢迎加入群聊!");
                        sendWelcome(cl, prefs, chatroom, welcomeMsg);
                        XposedBridge.log("[Group] 欢迎消息(ChatroomInfoUI)已发送: " + chatroom);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {}
    }

    /**
     * 发送欢迎语, 按 ls_welcome_type 区分类型:
     *  0=文本消息 (已实现)  1=图片消息 (依赖微信签名, 暂回退文本并记日志)
     */
    private static void sendWelcome(ClassLoader cl, SharedPreferences prefs, String talker, String welcomeMsg) {
        int welcomeType = 0;
        try { welcomeType = Integer.parseInt(prefs.getString("ls_welcome_type", "0")); }
        catch (Throwable ignored) {}
        if (welcomeType == 1) {
            XposedBridge.log("[Group] 欢迎语图片类型暂缺签名实现, 回退发送文本: " + talker);
        }
        sendTextMessage(cl, talker, welcomeMsg);
    }

    public static void sendTextMessage(ClassLoader cl, String talker, String text) {
        try {
            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", text);
            XposedHelpers.callMethod(msg, "L1", System.currentTimeMillis());

            if (sMsgStorage != null) {
                long msgId = System.currentTimeMillis();
                XposedHelpers.callMethod(sMsgStorage, "Ra", msgId, msg);
                XposedBridge.log("[Group] 消息已插入DB: " + text.substring(0, Math.min(20, text.length())));
            } else {
                sendViaFooter(cl, talker, text);
            }
        } catch (Throwable t) {
            XposedBridge.log("[Group] 发送失败: " + t.getMessage());
            sendViaFooter(cl, talker, text);
        }
    }

    private static void sendViaFooter(ClassLoader cl, String talker, String text) {
        try {
            Class<?> launcherUI = XposedHelpers.findClass(
                    "com.tencent.mm.ui.LauncherUI", cl);
            Object instance = XposedHelpers.callStaticMethod(launcherUI, "getInstance");
            if (instance == null) return;
            Object fragment = XposedHelpers.callMethod(instance, "getCurrentFragmet");
            if (fragment == null) return;
            Object footer = XposedHelpers.getObjectField(fragment, "mFooter");
            if (footer == null) return;
            String currentTalker = (String) XposedHelpers.callMethod(footer, "getTalkerUserName");
            if (!talker.equals(currentTalker)) {
                XposedBridge.log("[Group] 当前聊天非目标群，无法Footer发送");
                return;
            }

            Class<?> e9Class = VersionCompat.findMsgInfoStorageClass(cl);
            if (e9Class == null) return;
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", text);
            XposedHelpers.callMethod(footer, "F", msg, null);
            XposedBridge.log("[Group] 消息已发送(via Footer): " + text);
        } catch (Throwable t) {}
    }

    private static void writeLog(String msg) {
        synchronized (logLock) {
            try {
                logFile.getParentFile().mkdirs();
                FileWriter fw = new FileWriter(logFile, true);
                try { fw.write(msg + "\n"); } finally { fw.close(); }
            } catch (Throwable ignored) {}
        }
    }
}
