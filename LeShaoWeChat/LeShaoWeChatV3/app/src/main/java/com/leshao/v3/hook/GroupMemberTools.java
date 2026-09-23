/*
 * ============================================================================
 *  功能: 群聊详情页(ChatroomInfoUI) 成员改动记录查询工具
 *  参照: 一键导入导出聊天记录.zip / wx_group_member_tools_java_src.java
 *        成员改动记录查询.zip / DexKitResolver.java
 *  说明: 在群聊详情页【群成员网格下方、群聊名称上方】注入「群成员管理」入口,
 *        点击弹出成员列表(头像+显示名+群昵称+微信昵称+修改时间),
 *        支持查看大头像 / 修改我的群昵称(原生同步) / 设置本地显示昵称 / 进入资料页。
 *        修改时间记录每成员本地自定义昵称的最后修改时间戳。
 * ============================================================================
 */
package com.leshao.v3.hook;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class GroupMemberTools {

    private static final String TAG = "GroupMemberTools";

    /* ================== 版本适配配置区(混淆类名随版本变化, 升级用 DexKitResolver 方案替换) ================== */
    private static String CLS_CHATROOM_INFO_UI    = "com.tencent.mm.chatroom.ui.ChatroomInfoUI";
    private static String CLS_BUTTON_PREF         = "com.tencent.mm.ui.base.preference.ButtonPreference";
    private static String CLS_PREF_SCREEN         = "com.tencent.mm.ui.base.preference.g0";
    private static String CLS_PREF_CLICK_LISTENER = "com.tencent.mm.ui.base.preference.p0";
    private static String CLS_CONTACT_LIST_PREF   = "com.tencent.mm.pluginsdk.ui.applet.ContactListExpandPreference";
    private static String CLS_J1                  = "gp0.j1";                      // ServiceManager
    private static String CLS_N0                  = "ph5.n0";                      // 服务获取 n0.c(Class)
    private static String CLS_Q02_F               = "q02.f";                       // ChatroomMembersService
    private static String CLS_A3                  = "com.tencent.mm.storage.a3";   // ChatroomMembersStorage
    private static String CLS_IM_Y1               = "im.y1";                       // chatroom 表(群聊信息)
    private static String CLS_C4                  = "tn3.c4";                      // IMService
    private static String CLS_J4                  = "com.tencent.mm.storage.j4";   // ContactStorage
    private static String CLS_ROOM_SVC            = "vf0.e";                       // 房间服务接口
    private static String CLS_ROOM_FACTORY        = "pe5.f";                       // RoomFactory
    private static String CLS_SCENE_CB            = "qe5.b";                       // 场景回调接口 a(int,int,String,qe5.b)
    private static String CLS_ZO5_A               = "zo5.a";                       // 头像加载器
    private static String CLS_B41_Y1              = "b41.y1";                      // 当前用户 b41.y1.u()

    private static final String KEY_BTN = "leshao_member_manager_btn";
    private static final String PREF_NAME = "leshao_group_member_tools";

    private static Context appCtx;
    private static ClassLoader sCL;
    private static volatile boolean sEnabled = true;
    private static Handler sMain = new Handler(Looper.getMainLooper());

    public static void setEnabled(boolean en) { sEnabled = en; }
    public static boolean isEnabled() { return sEnabled; }

    /** 初始化: 先尝试 DexKit 动态解析混淆类名(失败回退硬编码), 再执行 hook。 */
    public static void init(ClassLoader cl) {
        try {
            GroupMemberResolver.resolve(cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolve err: " + t.getMessage());
        }
    }

    /** DexKit 解析结果回填(由 GroupMemberResolver 调用), 解析成功则覆盖混淆类名。 */
    public static void applyResolve(GroupMemberResolver.ResolveResult r) {
        if (r == null) return;
        if (r.chatroomInfoUI != null && r.chatroomInfoUI.length() > 0) CLS_CHATROOM_INFO_UI = r.chatroomInfoUI;
        if (r.prefScreen != null && r.prefScreen.length() > 0) CLS_PREF_SCREEN = r.prefScreen;
        if (r.imY1 != null && r.imY1.length() > 0) CLS_IM_Y1 = r.imY1;
        if (r.a3 != null && r.a3.length() > 0) CLS_A3 = r.a3;
        if (r.j4 != null && r.j4.length() > 0) CLS_J4 = r.j4;
        if (r.roomSvc != null && r.roomSvc.length() > 0) CLS_ROOM_SVC = r.roomSvc;
        if (r.roomFactory != null && r.roomFactory.length() > 0) CLS_ROOM_FACTORY = r.roomFactory;
        if (r.sceneCb != null && r.sceneCb.length() > 0) CLS_SCENE_CB = r.sceneCb;
        if (r.zo5a != null && r.zo5a.length() > 0) CLS_ZO5_A = r.zo5a;
        if (r.b41Y1 != null && r.b41Y1.length() > 0) CLS_B41_Y1 = r.b41Y1;
        LogWriter.log(TAG, "applyResolve: ui=" + CLS_CHATROOM_INFO_UI + " y1=" + CLS_IM_Y1
            + " a3=" + CLS_A3 + " j4=" + CLS_J4);
    }

    public static void hook(ClassLoader cl) {
        sCL = cl;
        try {
            SharedPreferences sp = ContextManager.getPrefs();
            if (sp != null) sEnabled = sp.getBoolean("ls_group_member_tools_enabled", true);
        } catch (Throwable t) {
            LogWriter.log(TAG, "读配置失败: " + t.getMessage());
        }
        LogWriter.log(TAG, "hook() 开始, enabled=" + sEnabled);
        if (!sEnabled) return;

        // ① 注入按钮: ChatroomInfoUI.initView() 完成后
        try {
            XposedBridge.hookAllMethods(load(CLS_CHATROOM_INFO_UI), "initView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context act = (Context) param.thisObject;
                        if (appCtx == null) appCtx = act.getApplicationContext();
                        injectButton(act);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "inject err: " + t);
                    }
                }
            });
            LogWriter.log(TAG, "ChatroomInfoUI.initView hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook initView failed: " + t);
        }

        // ② 显示层实时替换: 成员网格绑定完成后, 把匹配的自定义昵称替换显示
        try {
            XposedBridge.hookAllMethods(load(CLS_CONTACT_LIST_PREF), "B", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            applyCustomNickname((View) param.args[0]);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            LogWriter.log(TAG, "ContactListExpandPreference.B hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook ContactListExpandPreference.B failed: " + t);
        }
    }

    private static Class<?> load(String name) {
        return XposedHelpers.findClass(name, sCL);
    }

    /** 按方法参数签名反射调用(名称已混淆漂移, 兼容 J/P/S/K 等短名与标准 setXxx 全名) */
    private static boolean invokeBySig(Object obj, String logical, Object[] args) {
        String[] candidates = new String[]{logical, shortName(logical)};
        for (String name : candidates) {
            try {
                for (Method m : obj.getClass().getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != args.length) continue;
                    boolean okSig = true;
                    for (int i = 0; i < args.length; i++) {
                        Class<?> p = pts[i];
                        if (p.isPrimitive()) {
                            if (!primitiveMatch(p, args[i])) { okSig = false; break; }
                        } else if (args[i] != null && !p.isAssignableFrom(args[i].getClass())
                                && !interfaceMatch(p, args[i])) {
                            okSig = false; break;
                        }
                    }
                    if (okSig) {
                        m.setAccessible(true);
                        m.invoke(obj, args);
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        LogWriter.log(TAG, "invokeBySig " + logical + " NOT matched on " + obj.getClass().getName());
        return false;
    }

    /** setKey -> K, setTitle -> P, setSummary -> S, setOnPreferenceClickListener -> K */
    private static String shortName(String logical) {
        switch (logical) {
            case "setKey": return "J";
            case "setTitle": return "P";
            case "setSummary": return "S";
            case "setOnPreferenceClickListener": return "K";
            default: return "";
        }
    }

    private static boolean primitiveMatch(Class<?> p, Object v) {
        if (v == null) return !p.isPrimitive();
        if (p == int.class) return v instanceof Integer;
        if (p == boolean.class) return v instanceof Boolean;
        if (p == long.class) return v instanceof Long;
        if (p == float.class) return v instanceof Float;
        if (p == double.class) return v instanceof Double;
        return false;
    }

    private static boolean interfaceMatch(Class<?> p, Object v) {
        return p.isInstance(v);
    }

    /* ================== ① 注入按钮(群成员下方、群聊名称上方) ================== */
    private static void injectButton(Context activity) {
        try {
            Object screen = XposedHelpers.callMethod(activity, "getPreferenceScreen");
            if (screen == null) return;

            Object roomIdObj = XposedHelpers.getObjectField(activity, "C");
            final String roomId = roomIdObj == null ? "" : roomIdObj.toString();
            if (roomId.length() == 0) return;

            Object btn = load(CLS_BUTTON_PREF)
                    .getConstructor(Context.class, android.util.AttributeSet.class)
                    .newInstance(activity, null);
            // 8.0.78 混淆方法名漂移: J/P/S/K 可能变化,
            // 改为按"方法参数签名"反射调用(setKey(String)/setTitle(String)/setSummary(String)/setOnPreferenceClickListener(接口))
            invokeBySig(btn, "setKey", new Object[]{KEY_BTN});                              // J
            invokeBySig(btn, "setTitle", new Object[]{"◆ 群成员昵称/头像管理"});              // P
            invokeBySig(btn, "setSummary", new Object[]{"成员改动记录 · 修改昵称/查看头像"});   // S

            // 点击监听: 动态代理 p0 接口(a(Preference,Object)Z)
            // 不依赖方法名"a"——8.0.78 接口方法名可能漂移, 改为按签名匹配:
            // 返回 boolean 且首个参数为 Preference 基类(或 2 参)的方法即视为点击回调
            final Class<?> listenerIface = load(CLS_PREF_CLICK_LISTENER);
            Object listener = Proxy.newProxyInstance(sCL, new Class<?>[]{listenerIface},
                    (proxy, method, args) -> {
                        if ("a".equals(method.getName())
                                || (method.getReturnType() == boolean.class
                                && method.getParameterTypes().length >= 1
                                && method.getParameterTypes()[0].isInstance(btn))) {
                            try {
                                showMemberManager(activity, roomId);
                                return Boolean.TRUE;
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "click err " + t);
                            }
                            return Boolean.FALSE;
                        }
                        return defaultReturn(method);
                    });
            invokeBySig(btn, "setOnPreferenceClickListener", new Object[]{listener}); // K

            // 插入到 room_name 之前(即群聊名称上方、群成员网格下方)
            int pos = (Integer) XposedHelpers.callMethod(screen, "m", "room_name");
            if (pos < 0) pos = 0;
            XposedHelpers.callMethod(screen, "d", btn, pos);
            // 关键: PreferenceScreen 插入后需刷新 ListView, 否则文字不显示/点击无效
            try {
                XposedHelpers.callMethod(screen, "notifyDataSetChanged");
            } catch (Throwable ignored) {}
            LogWriter.log(TAG, "injected at pos=" + pos + " room=" + roomId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "injectButton err: " + t);
        }
    }

    /* ================== ② 群成员管理对话框 ================== */
    private static void showMemberManager(final Context ctx, final String roomId) {
        // 群聊信息
        Object roomInfo = getRoomInfo(roomId);
        String roomName = "";
        String myRoomNick = "";
        long roomModify = 0L;
        if (roomInfo != null) {
            roomName = str(getField(roomInfo, "field_chatroomname"));
            myRoomNick = str(getField(roomInfo, "field_selfDisplayName"));
            roomModify = getLongField(roomInfo, "field_modifytime");
        }

        // 群成员列表
        List<String> members = getMembers(roomId);
        if (members == null || members.isEmpty()) {
            toast(ctx, "无群成员数据");
            return;
        }

        // 组装成员项
        final List<MemberItem> items = new ArrayList<>();
        for (String u : members) {
            items.add(buildItem(u, roomInfo));
        }

        // UI
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView head = new TextView(ctx);
        head.setText("群名:" + roomName + "  群资料修改:" + fmt(roomModify)
                + "\n我的群昵称:" + myRoomNick + "  成员:" + items.size());
        head.setTextSize(13);
        head.setTextColor(AppColors.onSurface());
        root.addView(head);

        ListView list = new ListView(ctx);
        list.setDivider(new ColorDrawable(AppColors.outlineVariant()));
        list.setDividerHeight(1);
        final MemberAdapter adapter = new MemberAdapter(ctx, items);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(360)));

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("成员改动记录查询")
                .setView(root)
                .setNegativeButton("关闭", null)
                .create();
        list.setOnItemClickListener((p, v, pos, id) ->
                onMemberClick(ctx, items.get(pos), roomId, adapter));
        dlg.show();
    }

    private static void onMemberClick(final Context ctx, final MemberItem m, final String roomId,
                                      final MemberAdapter adapter) {
        String[] ops = {
                "查看大头像",
                "修改「我的群昵称」(微信原生同步)",
                "设置/修改该成员显示昵称(本地+修改时间)",
                "进入成员资料页(查看/改备注)"
        };
        AlertDialog dlgOps = new AlertDialog.Builder(ctx)
                .setTitle(safe(m.display))
                .setItems(ops, (d, w) -> {
                    try {
                        switch (w) {
                            case 0: viewAvatar(ctx, m); break;
                            case 1: modifyMyRoomNick(ctx, roomId); break;
                            case 2: editCustomNick(ctx, m, adapter); break;
                            case 3: openContactInfo(ctx, m, roomId); break;
                            default: break;
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "op err " + t);
                    }
                })
                .create();
        dlgOps.show();
    }

    /* ---------- 修改「我的群昵称」(微信原生协议, 可同步服务器) ---------- */
    private static void modifyMyRoomNick(final Context ctx, final String roomId) {
        final EditText et = new EditText(ctx);
        AlertDialog dlgNick = new AlertDialog.Builder(ctx)
                .setTitle("修改我的群昵称")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    final String nick = et.getText().toString().trim();
                    if (nick.length() == 0) {
                        toast(ctx, "昵称不能为空");
                        return;
                    }
                    new Thread(() -> {
                        try {
                            // 服务: n0.c(vf0.e.class)
                            Object svc = callStatic(CLS_N0, "c", load(CLS_ROOM_SVC));
                            // RoomFactory: svc.bj(roomId)
                            Object factory = XposedHelpers.callMethod(svc, "bj", roomId);
                            // 场景: factory.n(roomId, 当前用户, 新昵称)
                            String self = str(callStatic(CLS_B41_Y1, "u"));
                            final Object scene = XposedHelpers.callMethod(factory, "n", roomId, self, nick);
                            // 回调: 动态代理 qe5.b(a(int,int,String,qe5.b)V)
                            final Class<?> cbIface = load(CLS_SCENE_CB);
                            Object cb = Proxy.newProxyInstance(sCL, new Class<?>[]{cbIface},
                                    (proxy, method, args) -> {
                                        if ("a".equals(method.getName())) {
                                            int errType = args.length > 0 && args[0] != null ? (Integer) args[0] : -1;
                                            int errCode = args.length > 1 && args[1] != null ? (Integer) args[1] : -1;
                                            LogWriter.log(TAG, "onSceneEnd errType=" + errType + " errCode=" + errCode);
                                            if (errType == 0 && errCode == 0) {
                                                saveSelfModify(roomId, nick, System.currentTimeMillis());
                                                toastOnMain(ctx, "群昵称修改成功，修改时间:" + fmt(System.currentTimeMillis()));
                                            } else {
                                                toastOnMain(ctx, "群昵称修改失败 code=" + errCode);
                                            }
                                        }
                                        return null;
                                    });
                            XposedHelpers.setObjectField(scene, "d", cb);
                            // 发送场景: c(Context, 确认标题, 确认按钮文案, 是否确认, 是否取消, 取消回调)
                            XposedHelpers.callMethod(scene, "c", ctx, "提示", "确定", false, false, null);
                        } catch (Throwable t) {
                            LogWriter.log(TAG, "modifyMyRoomNick err " + t);
                            toastOnMain(ctx, "调用失败:" + t);
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .create();
        dlgNick.show();
    }

    /* ---------- 设置本地自定义昵称 + 修改时间 ---------- */
    private static void editCustomNick(final Context ctx, final MemberItem m, final MemberAdapter adapter) {
        final EditText et = new EditText(ctx);
        et.setHint("留空表示清除自定义昵称");
        et.setText(safe(m.custom));
        AlertDialog dlgEdit = new AlertDialog.Builder(ctx)
                .setTitle("设置显示昵称（本地，实时替换）")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    long now = System.currentTimeMillis();
                    saveLocal(m.username, v, now);
                    m.custom = v;
                    m.lastModify = now;
                    m.display = buildDisplay(m);
                    adapter.notifyDataSetChanged();
                    toast(ctx, "已保存，修改时间：" + fmt(now));
                })
                .setNegativeButton("取消", null)
                .create();
        dlgEdit.show();
    }

    /* ---------- 查看大头像 ---------- */
    private static void viewAvatar(Context ctx, MemberItem m) {
        AlertDialog dlg = new AlertDialog.Builder(ctx).create();
        ImageView iv = new ImageView(ctx);
        int size = dp(220);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        try {
            callStatic(CLS_ZO5_A, "b", iv, m.username);
        } catch (Throwable t) {
            LogWriter.log(TAG, "avatar err " + t);
        }
        dlg.setTitle(safe(m.display));
        dlg.setView(iv);
        dlg.setButton(AlertDialog.BUTTON_NEGATIVE, "关闭", (d, w) -> dlg.dismiss());
        dlg.show();
    }

    /* ---------- 进入成员资料页(大头像/改备注) ---------- */
    private static void openContactInfo(Context ctx, MemberItem m, String roomId) {
        try {
            Intent it = new Intent();
            it.setClassName(ctx, "com.tencent.mm.plugin.profile.ui.ContactInfoUI");
            it.putExtra("Contact_User", m.username);
            it.putExtra("Contact_RemarkName", safe(m.remark));
            it.putExtra("Contact_RoomNickname", safe(m.roomNick));
            it.putExtra("Contact_Nick", safe(m.wxNick));
            it.putExtra("Contact_RoomMember", true);
            it.putExtra("room_name", roomId);
            it.putExtra("Contact_Scene", 14);
            it.putExtra("Contact_ChatRoomId", roomId);
            it.putExtra("CONTACT_INFO_UI_SOURCE", 8);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Throwable t) {
            LogWriter.log(TAG, "openContactInfo err " + t);
            toast(ctx, "打开资料页失败");
        }
    }

    /* ================== 数据获取 ================== */

    /* 群聊信息 im.y1 */
    private static Object getRoomInfo(String roomId) {
        try {
            Object svc = callStatic(CLS_J1, "v", load(CLS_Q02_F));
            Object a3 = XposedHelpers.callMethod(svc, "a");
            return XposedHelpers.callMethod(a3, "t1", roomId);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getRoomInfo " + t);
            return null;
        }
    }

    /* 群成员列表 List<String> */
    private static List<String> getMembers(String roomId) {
        try {
            Object svc = callStatic(CLS_J1, "v", load(CLS_Q02_F));
            Object a3 = XposedHelpers.callMethod(svc, "a");
            Object list = XposedHelpers.callMethod(a3, "K1", roomId);
            if (list instanceof List) {
                List<String> r = new ArrayList<>();
                for (Object o : (List<?>) list) {
                    if (o != null) r.add(o.toString());
                }
                return r;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "getMembers " + t);
        }
        return null;
    }

    /* 构建成员项(显示名 = 自定义 > 备注 > 群昵称 > 微信昵称) */
    private static MemberItem buildItem(String username, Object roomInfo) {
        MemberItem m = new MemberItem();
        m.username = username;
        // Contact(备注/微信昵称)
        try {
            Object svc = callStatic(CLS_J1, "v", load(CLS_C4));
            Object stg = XposedHelpers.callMethod(svc, "cj");
            Object contact = XposedHelpers.callMethod(stg, "n", username, true);
            if (contact == null) {
                // 8.0.78 ContactStorage(v7) 取联系人实际是单参 m(String)
                contact = XposedHelpers.callMethod(stg, "m", username);
            }
            if (contact != null) {
                m.wxNick = str(XposedHelpers.callMethod(contact, "M0"));
                m.remark = str(XposedHelpers.callMethod(contact, "r0"));
            }
        } catch (Throwable t) {
            // 忽略: 个别成员可能无 Contact
        }
        // 群内昵称(so.b.e)
        if (roomInfo != null) {
            try {
                m.roomNick = str(XposedHelpers.callMethod(roomInfo, "x0", username));
            } catch (Throwable t) {
                // 忽略
            }
        }
        // 本地自定义昵称 + 修改时间
        MemberLocal local = loadLocal(username);
        m.custom = local.nick;
        m.lastModify = local.time;
        m.display = buildDisplay(m);
        return m;
    }

    private static String buildDisplay(MemberItem m) {
        if (m.custom != null && m.custom.length() > 0) return m.custom;
        if (m.remark != null && m.remark.length() > 0) return m.remark;
        if (m.roomNick != null && m.roomNick.length() > 0) return m.roomNick;
        return safe(m.wxNick);
    }

    /* ================== 显示层实时替换 ================== */
    private static void applyCustomNickname(View root) {
        if (root == null) return;
        List<TextView> tvs = new ArrayList<>();
        collectTextViews(root, tvs);
        for (TextView tv : tvs) {
            try {
                CharSequence cur = tv.getText();
                if (cur == null || cur.length() == 0) continue;
                String curStr = cur.toString();
                MemberLocal local = loadLocalByDisplay(curStr);
                if (local != null && local.nick != null && !local.nick.equals(curStr)) {
                    tv.setText(local.nick);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectTextViews(View v, List<TextView> out) {
        if (v instanceof TextView) {
            out.add((TextView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTextViews(vg.getChildAt(i), out);
            }
        }
    }

    /* ================== 本地存储(自定义昵称 + 修改时间) ================== */
    private static SharedPreferences prefs() {
        Context c = appCtx != null ? appCtx : ContextManager.getAppContext();
        return c.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static MemberLocal loadLocal(String username) {
        MemberLocal r = new MemberLocal();
        if (appCtx == null || username == null) return r;
        SharedPreferences sp = prefs();
        r.nick = sp.getString(username + "_nick", "");
        r.time = sp.getLong(username + "_time", 0L);
        return r;
    }

    /* 通过当前显示名反查本地自定义(用于显示层替换) */
    private static MemberLocal loadLocalByDisplay(String display) {
        if (appCtx == null || display == null) return null;
        SharedPreferences sp = prefs();
        java.util.Map<String, ?> all = sp.getAll();
        for (String k : all.keySet()) {
            if (k.endsWith("_nick")) {
                Object v = all.get(k);
                if (v != null && display.equals(v.toString())) {
                    MemberLocal ml = new MemberLocal();
                    ml.nick = v.toString();
                    ml.time = sp.getLong(k.substring(0, k.length() - 5) + "_time", 0L);
                    return ml;
                }
            }
        }
        return null;
    }

    private static void saveLocal(String username, String nick, long time) {
        if (appCtx == null) return;
        SharedPreferences.Editor e = prefs().edit();
        if (nick == null || nick.length() == 0) {
            e.remove(username + "_nick").remove(username + "_time");
        } else {
            e.putString(username + "_nick", nick).putLong(username + "_time", time);
        }
        e.apply();
    }

    private static void saveSelfModify(String roomId, String nick, long time) {
        if (appCtx == null) return;
        prefs().edit().putString(roomId + ":self_nick", nick)
                .putLong(roomId + ":self_time", time).apply();
    }

    /* ================== 工具方法 ================== */
    private static Object callStatic(String clazz, String method, Object... args) throws Throwable {
        return XposedHelpers.callStaticMethod(load(clazz), method, args);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static Object getField(Object o, String f) {
        try {
            return XposedHelpers.getObjectField(o, f);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long getLongField(Object o, String f) {
        try {
            Object v = XposedHelpers.getObjectField(o, f);
            return v instanceof Long ? (Long) v : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(long t) {
        if (t <= 0) return "未修改";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private static int dp(int v) {
        if (appCtx == null) return v;
        return (int) (v * appCtx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void toast(Context ctx, String s) {
        try {
            Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static void toastOnMain(final Context ctx, final String s) {
        try {
            sMain.post(() -> toast(ctx, s));
        } catch (Throwable ignored) {
        }
    }

    private static Object defaultReturn(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) return Boolean.FALSE;
        if (rt == int.class) return 0;
        if (rt == long.class) return 0L;
        if (rt == float.class) return 0f;
        if (rt == double.class) return 0d;
        return null;
    }

    /* ================== 数据结构 ================== */
    static class MemberItem {
        String username;
        String wxNick = "";
        String remark = "";
        String roomNick = "";
        String custom = "";
        String display = "";
        long lastModify = 0L;
    }

    static class MemberLocal {
        String nick = "";
        long time = 0L;
    }

    /* 成员列表适配器 */
    static class MemberAdapter extends BaseAdapter {
        final Context ctx;
        final List<MemberItem> items;

        MemberAdapter(Context ctx, List<MemberItem> items) {
            this.ctx = ctx;
            this.items = items;
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int i) {
            return items.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int i, View cv, ViewGroup p) {
            MemberItem m = items.get(i);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));

            ImageView avatar = new ImageView(ctx);
            int a = dp(40);
            avatar.setLayoutParams(new LinearLayout.LayoutParams(a, a));
            try {
                callStatic(CLS_ZO5_A, "b", avatar, m.username);
            } catch (Throwable ignored) {
            }
            row.addView(avatar);

            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(10), 0, 0, 0);

            TextView t1 = new TextView(ctx);
            t1.setText(safe(m.display));
            t1.setTextSize(15);
            t1.setTextColor(AppColors.onSurface());
            t1.setSingleLine(true);
            col.addView(t1);

            TextView t2 = new TextView(ctx);
            StringBuilder sub = new StringBuilder();
            if (m.custom != null && m.custom.length() > 0) sub.append("自定义:").append(m.custom).append(' ');
            if (m.roomNick != null && m.roomNick.length() > 0) sub.append("群昵称:").append(m.roomNick).append(' ');
            if (m.wxNick != null && m.wxNick.length() > 0) sub.append("微信:").append(m.wxNick).append(' ');
            sub.append("修改:").append(fmt(m.lastModify));
            t2.setText(sub.toString());
            t2.setTextSize(11);
            t2.setTextColor(AppColors.onSurfaceVariant());
            t2.setSingleLine(true);
            col.addView(t2);

            row.addView(col, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }
}
