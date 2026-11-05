package com.leshao.v3.hook;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.ai.hook.wechat.GroupMemberNames;
import com.leshao.ai.hook.wechat.GroupMsgParser;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.utils.WmReflect;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * v3.0.163：群聊消息「群主/管理员/成员」头衔标签 —— 依据
 * 《微信群头衔显示完整逆向分析报告（二次核查版）》。
 *
 * <p>要点（与报告对应）：</p>
 * <ul>
 *   <li>数据：{@code com.tencent.mm.storage.ChatRoom}（{@code com.tencent.mm.storage.a.a}）群信息 +
 *       {@code com.tencent.mm.storage.MMChatRoomMemberInfo} 成员角色；角色标识
 *       {@code role/memberRole/title} 字符串字段；群主字段 {@code field_roomowner}。</li>
 *   <li>UI：群聊消息列表项绑定后，在昵称行（{@code h0.userTV}）注入头衔标签
 *       {@code TextView}，按三角色独立背景色/文字色渲染（{@code setBackgroundColor / setTextColor}）。</li>
 *   <li>角色判定：发送者 == {@code field_roomowner} → 群主；命中管理员名单 → 管理员；
 *       其余 → 成员。</li>
 * </ul>
 */
public final class GroupTitleTagHook {

    private static final String TAG = "GroupTitleTag";

    public static final String K_ENABLED = "ls_group_title_tag_enabled";
    // 群主
    public static final String K_OWNER_BG = "ls_group_title_owner_bg";
    public static final String K_OWNER_TEXT = "ls_group_title_owner_text";
    // 管理员
    public static final String K_ADMIN_BG = "ls_group_title_admin_bg";
    public static final String K_ADMIN_TEXT = "ls_group_title_admin_text";
    // 成员
    public static final String K_MEMBER_BG = "ls_group_title_member_bg";
    public static final String K_MEMBER_TEXT = "ls_group_title_member_text";
    /** 真机自校准：已知群主 wxid（非空时打印群信息字段，用于定位群主/管理员字段） */
    public static final String K_CALIB_WXID = "ls_group_title_calib_wxid";

    private static final String TAG_KEY = "leshao_group_title_tag";
    /** 已内嵌标签的 userTV → role（防重复插入，WeakHashMap 防泄漏） */
    private static final java.util.Map<TextView, String> sAppliedRole =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 群聊昵称行 h0.userTV id（ChatBubbleHook ID_NICK_TV 同源实证）。 */
    private static final int ID_NICK_TV = 2131366078;

    private static volatile boolean sEnabled = false;
    private static volatile int sOwnerBg = 0, sOwnerText = 0;
    private static volatile String sCalibWxid = "";
    private static volatile int sAdminBg = 0, sAdminText = 0;
    private static volatile int sMemberBg = 0, sMemberText = 0;
    private static volatile boolean sHooked = false;
    private static volatile ClassLoader sCl;

    private GroupTitleTagHook() {}

    // ================================================================
    // 配置
    // ================================================================

    private static SharedPreferences prefs() {
        return ContextManager.getPrefs();
    }

    public static boolean isEnabled() {
        SharedPreferences sp = prefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        SharedPreferences sp = prefs();
        if (sp != null) sp.edit().putBoolean(K_ENABLED, on).apply();
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled " + on);
    }

    public static int getOwnerBg() { return sOwnerBg; }
    public static int getOwnerText() { return sOwnerText; }
    public static int getAdminBg() { return sAdminBg; }
    public static int getAdminText() { return sAdminText; }
    public static int getMemberBg() { return sMemberBg; }
    public static int getMemberText() { return sMemberText; }

    /** 取色器写入后由 UI 调用刷新。0 = 恢复微信原生。 */
    public static void setColors(int ownerBg, int ownerText, int adminBg, int adminText,
                                 int memberBg, int memberText) {
        SharedPreferences sp = prefs();
        if (sp != null) {
            sp.edit()
                    .putInt(K_OWNER_BG, ownerBg).putInt(K_OWNER_TEXT, ownerText)
                    .putInt(K_ADMIN_BG, adminBg).putInt(K_ADMIN_TEXT, adminText)
                    .putInt(K_MEMBER_BG, memberBg).putInt(K_MEMBER_TEXT, memberText)
                    .apply();
        }
        sOwnerBg = ownerBg; sOwnerText = ownerText;
        sAdminBg = adminBg; sAdminText = adminText;
        sMemberBg = memberBg; sMemberText = memberText;
        LogWriter.log(TAG, "setColors owner=" + Integer.toHexString(ownerBg)
                + "/" + Integer.toHexString(ownerText)
                + " admin=" + Integer.toHexString(adminBg)
                + "/" + Integer.toHexString(adminText)
                + " member=" + Integer.toHexString(memberBg)
                + "/" + Integer.toHexString(memberText));
        // v3.0.166：取色实时生效 —— 遍历已渲染 item 里的头衔标签重刷配色，无需重新进入聊天
        refreshRenderedTags();
    }

    /** v3.0.166：遍历当前聊天 Activity View 树，对已注入的头衔标签按最新配色重刷。 */
    public static void refreshRenderedTags() {
        try {
            android.app.Activity act = com.leshao.v3.MainHook.currentActivity();
            if (act == null) return;
            View decor = act.getWindow().getDecorView();
            if (decor == null) return;
            walkTags(decor, 0);
        } catch (Throwable ignored) {}
    }

    private static void walkTags(View v, int depth) {
        if (v == null || depth > 24) return;
        try {
            if (TAG_KEY.equals(v.getTag())) {
                if (v instanceof TextView) {
                    String role = roleOfTag(v);
                    if (role != null) applyRoleStyle((TextView) v, role);
                }
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkTags(g.getChildAt(i), depth + 1);
            }
        }
    }

    private static String roleOfTag(View v) {
        // tag 值为 TAG_KEY 字符串（见 create 处 setTag(TAG_KEY)），角色记录在其子文本上：
        // 应用过样式则 tag.setText(role)。直接复用当前文本作为角色名。
        try {
            CharSequence t = ((TextView) v).getText();
            if (t != null && (t.toString().equals("群主") || t.toString().equals("管理员")
                    || t.toString().equals("成员"))) {
                return t.toString();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ================================================================
    // 入口
    // ================================================================

    public static synchronized void hook(ClassLoader cl) {
        if (sHooked) return;
        sCl = cl;
        SharedPreferences sp = prefs();
        if (sp != null) {
            sEnabled = sp.getBoolean(K_ENABLED, false);
            sOwnerBg = sp.getInt(K_OWNER_BG, 0); sOwnerText = sp.getInt(K_OWNER_TEXT, 0);
            sAdminBg = sp.getInt(K_ADMIN_BG, 0); sAdminText = sp.getInt(K_ADMIN_TEXT, 0);
            sMemberBg = sp.getInt(K_MEMBER_BG, 0); sMemberText = sp.getInt(K_MEMBER_TEXT, 0);
            sCalibWxid = sp.getString(K_CALIB_WXID, "");
        }
        installItemHook(cl);
        GroupTitleTagFix.INSTANCE.hook(cl);   // ★ 权威文档第九节：统一 setText hook
        sHooked = true;
        LogWriter.log(TAG, "hook installed enabled=" + sEnabled);
    }

    /** ★ 权威文档第八节：统一 hook userTV.setText，覆盖所有消息类型 + RecyclerView 复用换绑。
     *  每次 setText 都先清洗旧标签再插入新标签 → 初始/复用/大图返回/重复 setText 都不消失不重复。 */
    private static final ThreadLocal<Boolean> sApplyingLabel =
            new ThreadLocal<Boolean>() { @Override protected Boolean initialValue() { return false; } };

    private static void installNickSetTextHook(ClassLoader cl) {
        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (!sEnabled) return;
                    if (sApplyingLabel.get()) return;                    // 防递归（我们 setText 触发）
                    TextView tv = (TextView) p.thisObject;
                    if (tv == null || tv.getId() != ID_NICK_TV) return;  // 只处理群成员昵称 view
                    String[] rs = extractRoomSender(tv);
                    if (rs == null) return;
                    if (!isChatroom(rs[0])) return;                      // 仅群聊
                    if (isSelfMsg(rs[1], null)) return;                  // 自己的消息跳过
                    String role = resolveRole(rs[0], rs[1]);
                    if (role == null) return;
                    // ★ 第九节：文本已以 " $role " 开头则跳过（防重复）
                    String prefix = " " + role + " ";
                    String cur = tv.getText() == null ? "" : tv.getText().toString();
                    if (cur.startsWith(prefix)) return;
                    // ★ 清洗掉可能残留的旧标签（角色变化时也能纠正）
                    String clean = cur.replaceFirst("^\\s*(群主|管理员|成员)\\s*", "").trim();
                    int bg;
                    if ("群主".equals(role)) { bg = sOwnerBg != 0 ? sOwnerBg : 0xFFE64340; }
                    else if ("管理员".equals(role)) { bg = sAdminBg != 0 ? sAdminBg : 0xFF07C160; }
                    else { bg = sMemberBg != 0 ? sMemberBg : AppColors.outline(); }
                    SpannableStringBuilder sp = new SpannableStringBuilder();
                    SpannableString label = new SpannableString(" " + role + " ");
                    if (bg != 0) {
                        label.setSpan(new BackgroundColorSpan(bg), 0, label.length(), 0);
                        label.setSpan(new ForegroundColorSpan(AppColors.whiteTextOnAccent()), 0, label.length(), 0);
                    }
                    sp.append(label).append(" ").append(clean);
                    sApplyingLabel.set(true);
                    try {
                        tv.setText(sp);
                    } finally {
                        sApplyingLabel.set(false);
                    }
                    tv.setSingleLine(true);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    tv.setGravity(Gravity.CENTER_VERTICAL);
                } catch (Throwable ignored) {}
            }
        });
    }

    /** 从 userTV 沿父链找 itemView → holder → 消息实体 e9 → [room, sender]。 */
    public static String[] extractRoomSender(View tv) {
        try {
            View cur = tv;
            ViewParent vg = tv.getParent();
            int d = 0;
            while (vg instanceof ViewGroup && d++ < 12) {
                ViewGroup g = (ViewGroup) vg;
                String n = g.getClass().getName();
                if (n.contains("RecyclerView") || n.contains("ListView")) break;
                cur = g;
                vg = g.getParent();
            }
            Object holder = cur.getTag();
            if (holder == null) return null;
            Object msg = holder;
            try { msg = XposedHelpers.getObjectField(msg, "i"); } catch (Throwable t) {}
            if (msg != null) { try { msg = XposedHelpers.getObjectField(msg, "d"); } catch (Throwable t) {} }
            if (msg != null) { try { msg = XposedHelpers.getObjectField(msg, "b"); } catch (Throwable t) {} }
            if (msg == null) return null;
            String room = null, sender = null;
            try { room = (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable t) {}
            if (room == null) { try { Object o = XposedHelpers.getObjectField(msg, "field_talker"); if (o instanceof String) room = (String) o; } catch (Throwable t) {} }
            try { sender = (String) XposedHelpers.callMethod(msg, "G0"); } catch (Throwable t) {}
            if (sender == null || sender.isEmpty()) { try { sender = (String) XposedHelpers.callMethod(msg, "j"); } catch (Throwable t) {} }
            if (room == null || sender == null || sender.isEmpty()) return null;
            return new String[]{room, sender};
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * hook 群聊消息 item 绑定路径（viewitems.to / mq 的 b 方法，与 ChatBubbleHook 同源）。
     * 绑定后：取发送者 wxid → 判定角色 → 在昵称行注入头衔标签。
     */
    private static void installItemHook(ClassLoader cl) {
        // 文档 §4 主路径：viewitems.b0.p(h0,d,e9,str) 填充昵称（参数含 e9 消息对象）
        String[] holderCands = {
                "com.tencent.mm.ui.chatting.viewitems.b0",
                "com.tencent.mm.ui.chatting.viewitems.to",
                "com.tencent.mm.ui.chatting.viewitems.mq"
        };
        int hooked = 0;
        for (String cn : holderCands) {
            Set<Class<?>> clsSet = HookUtil.loadClasses(cl, cn);
            if (clsSet.isEmpty()) {
                // v3.0.166 诊断：类未在候选 CL 上解析到 → hook 静默失效的常见原因
                LogWriter.log(TAG, "class not found via any CL: " + cn);
            }
            for (Class<?> toCls : clsSet) {
                for (Method m : toCls.getDeclaredMethods()) {
                    if (!"b".equals(m.getName()) && !"p".equals(m.getName())) continue;
                    // 文档 §4.1：b0.p 是 4 参（h0,d,e9,str）；to/mq 放宽所有 b 方法（ChatBubbleHook 同源）
                    if ("com.tencent.mm.ui.chatting.viewitems.b0".equals(cn) && !"p".equals(m.getName())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                applyGroupTitle(param.args);
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "hooked " + cn + "." + m.getName()
                            + "(" + m.getParameterTypes().length + ")"
                            + " loader=" + HookUtil.loaderName(toCls.getClassLoader()));
                }
            }
        }
        // 兜底：AnimImageView/昵称 setTextColor 亦可作为渲染入口
        LogWriter.log(TAG, "installed item hooks x" + hooked);
    }

    /** 从绑定参数中找群聊消息的根 View 与发送者 wxid，注入头衔标签。 */
    private static void applyGroupTitle(Object[] args) {
        if (args == null || args.length == 0) return;
        long start = android.os.SystemClock.uptimeMillis();
        View root = null;
        for (Object a : args) {
            if (a instanceof View) { root = (View) a; break; }
        }
        // v3.0.166：b0.p(h0,d,e9,str) 首参是 holder h0（无直接 View），
        // 从 holder 反射提取 userTV(nick) 视图，再上溯 item 根。
        if (root == null) {
            for (Object a : args) {
                if (a == null || a instanceof View || a instanceof Boolean) continue;
                View nick = findNickViewInHolder(a);
                if (nick != null) {
                    root = nick;
                    break;
                }
            }
        }
        if (root == null) return;
        // 找昵称行
        View nick = root.findViewById(ID_NICK_TV);
        if (nick == null && root != null) nick = root; // root 即从 holder 提取的 userTV
        if (nick == null) return;
        // 找消息对象 e9 提取发送者与群号
        String sender = null;
        String room = null;
        Object msg = null;
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof View || a instanceof Boolean) continue;
            if ("e9".equals(a.getClass().getSimpleName())) { msg = a; break; }
        }
        // 文档 §4.2：e9.j() 内容/fromusername、e9.N0()=field_talker(群ID)、e9.G0()=解析发送者
        if (msg != null) {
            try { room = (String) XposedHelpers.callMethod(msg, "N0"); } catch (Throwable ignored) {}
            if (room == null) {
                try {
                    Object talker = XposedHelpers.getObjectField(msg, "field_talker");
                    if (talker instanceof String) room = (String) talker;
                } catch (Throwable ignored) {}
            }
            try { sender = (String) XposedHelpers.callMethod(msg, "G0"); } catch (Throwable ignored) {}
            if (sender == null || sender.isEmpty()) {
                try { sender = (String) XposedHelpers.callMethod(msg, "j"); } catch (Throwable ignored) {}
                // j() 可能是群消息 content（格式 <senderWxId>:\n<正文>），解析出发送者
                if (sender != null && room != null && isChatroom(room)) {
                    String[] sp = GroupMsgParser.splitGroupContent(sender, true);
                    if (sp[0] != null && !sp[0].isEmpty()) sender = sp[0];
                }
            }
        }
        if (sender == null || room == null) {
            // 兜底：递归反射提取（消息对象字段名版本差异）
            for (Object a : args) {
                if (a == null || a == msg) continue;
                if (a instanceof View || a instanceof Boolean) continue;
                String[] got = extractTalkerFields(a, 0, new java.util.HashSet<>());
                if (got != null) {
                    if (got[0] != null && room == null) room = got[0];
                    if (got[1] != null && sender == null) sender = got[1];
                }
            }
        }
        if (sender == null) {
            // 兜底：昵称 TextView 文本为发送者昵称，无法反查 wxid —— 跳过（群主字段比对需要 wxid）
            removeTagIfAny(nick);
            return;
        }
        if (room == null) {
            removeTagIfAny(nick);
            return;
        }
        if (!isChatroom(room)) {
            removeTagIfAny(nick);
            return;
        }
        // 文档 §8-5：自己的消息跳过（e9.z0()==1 为自己发出；或 sender == 当前登录 wxid）
        if (isSelfMsg(sender, msg)) {
            removeTagIfAny(nick);
            return;
        }
        String role = resolveRole(room, sender);
        if (role == null) {
            removeTagIfAny(nick);
            return;
        }
        // ★ 第15章：停用旧绑定内 setText 插标签（避免双标签），
        //   但把可靠解析出的角色写入缓存，并调用统一插入入口 refreshLabel 立即纠正误插。
        if (nick instanceof TextView) {
            GroupTitleTagFix.INSTANCE.cacheRole((TextView) nick, role);
            GroupTitleTagFix.INSTANCE.refreshLabel((TextView) nick, role);
        }
        if (android.os.SystemClock.uptimeMillis() - start > 8) {
            LogWriter.log(TAG, "bind hook cached role=" + role + " sender=" + sender + " (唯一插入=refreshLabel)");
        }
    }

    /** 文档 §8-3 复用防串位：本 item 不应显示头衔时，移除昵称行中残留的旧标签。 */
    private static void removeTagIfAny(View nick) {
        if (nick == null) return;
        if (nick instanceof TextView) sAppliedRole.remove(nick);
        try {
            ViewGroup row = findNickRow(nick);
            if (row == null) return;
            TextView tag = findTagInRow(row);
            if (tag != null) row.removeView(tag);
        } catch (Throwable ignored) {}
    }

    /** 是否自己的消息（文档 §8-5）：e9.z0()==1 为自己发出；或 sender 等于当前登录 wxid。 */
    public static boolean isSelfMsg(String sender, Object msg) {
        if (msg != null) {
            try {
                Object z = XposedHelpers.callMethod(msg, "z0");
                if (z instanceof Number && ((Number) z).intValue() == 1) return true;
            } catch (Throwable ignored) {}
        }
        try {
            String my = com.leshao.v3.model.ModuleConfig.getCurrentWxid();
            if (my != null && !my.isEmpty() && my.equals(sender)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /** 在 row 中找已注入的头衔标签（按 tag 幂等）。 */
    private static TextView findTagInRow(ViewGroup row) {
        if (row == null) return null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (TAG_KEY.equals(c.getTag())) return (TextView) c;
        }
        return null;
    }

    /** 深度遍历 holder 对象字段，找 ID_NICK_TV 的 TextView（b0.p 首参 holder 场景）。 */
    private static View findNickViewInHolder(Object o) {
        if (o == null) return null;
        java.util.Set<Object> seen = new java.util.HashSet<>();
        return searchHolder(o, 0, seen);
    }

    private static View searchHolder(Object o, int depth, java.util.Set<Object> seen) {
        if (o == null || depth > 3 || seen.contains(o)) return null;
        if (o instanceof View) {
            if (((View) o).getId() == ID_NICK_TV) return (View) o;
        }
        seen.add(o);
        Class<?> c = o.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v == null) continue;
                    if (v instanceof View && ((View) v).getId() == ID_NICK_TV) return (View) v;
                    if (v instanceof Number || v instanceof Boolean || v instanceof CharSequence) continue;
                    if (v.getClass().getName().startsWith("android.")) continue;
                    View sub = searchHolder(v, depth + 1, seen);
                    if (sub != null) return sub;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 从昵称 view 上溯到包含它的"昵称行"容器。
     * <p>v3.0.167：改找昵称的<b>直接父容器</b>（即昵称行），不再往上翻到整个消息 item 外层，
     * 避免把标签加到消息上方且被外层容器按垂直布局撑满全宽。兼容 LinearLayout/RelativeLayout 等。</p>
     */
    private static ViewGroup findNickRow(View nick) {
        if (nick == null) return null;
        Object parent = nick.getParent();
        if (parent instanceof ViewGroup) return (ViewGroup) parent;
        return null;
    }

    /** 按容器类型生成适合的 WRAP_CONTENT 标签 LayoutParams（margin 在标签左侧，与昵称分隔）。 */
    private static ViewGroup.LayoutParams buildTagParams(ViewGroup row, int marginEndPx) {
        if (row instanceof LinearLayout) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            // 标签在昵称左侧，右侧（与昵称之间）留间距
            lp.setMarginEnd(marginEndPx);
            return lp;
        } else if (row instanceof android.widget.RelativeLayout) {
            android.widget.RelativeLayout.LayoutParams lp = new android.widget.RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(marginEndPx);
            lp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL);
            return lp;
        } else {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 防换行：在 userTV 文本开头内嵌紧凑标签（" $role " 带背景色），并强制 singleLine + 末尾省略。 */
    private static void applyRoleInline(View nick, String role) {
        if (!(nick instanceof TextView)) return;
        TextView userTV = (TextView) nick;
        if (role.equals(sAppliedRole.get(userTV))) return;   // 幂等：已插入同角色则跳过
        int bg;
        if ("群主".equals(role)) { bg = sOwnerBg; }
        else if ("管理员".equals(role)) { bg = sAdminBg; }
        else { bg = sMemberBg; }
        SpannableStringBuilder sp = new SpannableStringBuilder();
        SpannableString label = new SpannableString(" " + role + " ");
        if (bg != 0) {
            label.setSpan(new BackgroundColorSpan(bg), 0, label.length(), 0);
            label.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, label.length(), 0);
        }
        sp.append(label).append(" ").append(userTV.getText());
        userTV.setText(sp);
        userTV.setSingleLine(true);                          // ★ 防换行关键
        userTV.setEllipsize(TextUtils.TruncateAt.END);      // ★ 昵称过长末尾省略
        userTV.setGravity(Gravity.CENTER_VERTICAL);
        sAppliedRole.put(userTV, role);
    }

    /** 按角色应用配色（0 = 恢复微信原生：仅保留文本、清背景）。 */
    private static void applyRoleStyle(TextView tag, String role) {
        int bg, tc;
        if ("群主".equals(role)) { bg = sOwnerBg; tc = sOwnerText; }
        else if ("管理员".equals(role)) { bg = sAdminBg; tc = sAdminText; }
        else { bg = sMemberBg; tc = sMemberText; }
        if (bg == 0 && tc == 0) {
            tag.setVisibility(View.GONE);
            return;
        }
        tag.setVisibility(View.VISIBLE);
        tag.setText(role);
        if (bg != 0) {
            try {
                GradientDrawable gd = (GradientDrawable) tag.getBackground();
                gd.setColor(bg);
            } catch (Throwable ignored) {}
        }
        if (tc != 0) tag.setTextColor(tc);
        // 成员配色若全 0 也隐藏；群主/管理员保持微信原生以外的最小可见
        if (bg == 0) {
            try {
                GradientDrawable gd = (GradientDrawable) tag.getBackground();
                gd.setColor(0x11000000);
            } catch (Throwable ignored) {}
        }
    }

    /** 判定角色：优先文档 API（z2.L0 群主 / z2.E0 管理员），失败再反射兜底。 */
    public static String resolveRole(String room, String sender) {
        // 文档 §3：j1.v(f).a().t1(room) 拿 z2，L0()=群主、E0()=管理员（roomFlag & 2048）
        try {
            String role = GroupMemberNames.roleOf(sender, room);
            if (role != null) return role;
        } catch (Throwable ignored) {}
        // 兜底：反射读群主/管理员字段（旧版类名差异）
        try {
            String owner = WmReflect.getRoomOwner(sCl, room);
            if (owner != null && owner.equals(sender)) return "群主";
        } catch (Throwable ignored) {}
        try {
            List<String> admins = adminList(sCl, room);
            if (admins != null && admins.contains(sender)) return "管理员";
        } catch (Throwable ignored) {}
        return "成员";
    }

    /** 尝试从 ChatRoom 对象读管理员名单（多字段名候选，未知字段不崩）。 */
    private static List<String> adminList(ClassLoader cl, String room) {
        try {
            Object info = WmReflect.getChatroomInfo(cl, room);
            if (info == null) return null;
            String[] cands = {"field_roommanager", "field_admin", "field_roommember",
                    "field_roommanagerlist", "field_room_admins"};
            for (String f : cands) {
                try {
                    Object v = XposedHelpers.getObjectField(info, f);
                    if (v instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> l = (List<String>) v;
                        if (!l.isEmpty()) return l;
                    } else if (v instanceof String && ((String) v).length() > 0) {
                        return java.util.Arrays.asList(((String) v).split(","));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 从消息对象递归提取 {@code [room, sender]}（room 以 @chatroom 结尾，sender 以 wxid_ 开头）。 */
    private static String[] extractTalkerFields(Object o, int depth, java.util.Set<Object> seen) {
        if (o == null || depth > 3 || seen.contains(o)) return null;
        seen.add(o);
        String room = null, sender = null;
        Class<?> c = o.getClass();
        while (c != null && !c.equals(Object.class)) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (room == null && s.endsWith("@chatroom")) room = s;
                        if (sender == null && s.startsWith("wxid_")) sender = s;
                        if (room != null && sender != null) return new String[]{room, sender};
                    } else if (v != null && !(v instanceof Number)
                            && !(v instanceof Boolean) && !(v instanceof CharSequence)
                            && !(v.getClass().getName().startsWith("android."))) {
                        String[] sub = extractTalkerFields(v, depth + 1, seen);
                        if (sub != null) {
                            if (room == null) room = sub[0];
                            if (sender == null) sender = sub[1];
                            if (room != null && sender != null) return new String[]{room, sender};
                        }
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return (room != null || sender != null) ? new String[]{room, sender} : null;
    }

    /** 真机自校准：已知群主 wxid（非空才打印）。 */
    public static String getCalibWxid() { return sCalibWxid; }

    /** 自动校准（无需填 wxid）：打印该群成员对象所有值为 true 的 boolean 字段。 */
    public static void dumpMemberFields(String room, String sender) {
        try {
            Object info = WmReflect.getChatroomInfo(sCl, room);
            if (info == null) return;
            // 在群信息对象字段中找成员列表（List），定位 sender 对应的成员对象
            for (java.lang.reflect.Field f : info.getClass().getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(info);
                    if (v instanceof List) {
                        for (Object item : (List<?>) v) {
                            if (item == null) continue;
                            if (sender.equals(readMemberUsername(item))) {
                                dumpMemberBoolFields(item, sender);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** 读取成员对象 username（候选：P0() / 字段 W / username）。 */
    private static Object readMemberUsername(Object member) {
        try { Object o = XposedHelpers.callMethod(member, "P0"); if (o != null) return o; } catch (Throwable ignored) {}
        try { Object o = XposedHelpers.getObjectField(member, "W"); if (o != null) return o; } catch (Throwable ignored) {}
        try { Object o = XposedHelpers.getObjectField(member, "username"); if (o != null) return o; } catch (Throwable ignored) {}
        return null;
    }

    /** 只打印值为 true 的 boolean 字段：CALIB sender=... BOOL field=true。 */
    private static void dumpMemberBoolFields(Object member, String sender) {
        LogWriter.log(TAG, "CALIB sender=" + sender + " cls=" + member.getClass().getName());
        java.lang.reflect.Field[] fs = member.getClass().getDeclaredFields();
        for (java.lang.reflect.Field f : fs) {
            try {
                f.setAccessible(true);
                if (f.getType() == Boolean.TYPE) {
                    Object val = f.get(member);
                    if (Boolean.TRUE.equals(val)) {
                        LogWriter.log(TAG, "CALIB sender=" + sender + " BOOL " + f.getName() + "=true");
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public static boolean isChatroom(String room) {
        return room != null && (room.endsWith("@chatroom") || room.endsWith("@im.chatroom"));
    }

    private static int dp(View v, int vdp) {
        return (int) (vdp * v.getResources().getDisplayMetrics().density);
    }

    // ---- 供 UI 面板展示默认色（未设置时展示微信原生样式的参考色） ----

    public static boolean anyColorSet() {
        return sOwnerBg != 0 || sOwnerText != 0 || sAdminBg != 0 || sAdminText != 0
                || sMemberBg != 0 || sMemberText != 0;
    }
}