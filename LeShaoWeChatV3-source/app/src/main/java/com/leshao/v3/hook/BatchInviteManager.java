package com.leshao.v3.hook;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContactRepository;
import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.model.ContactCard;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.AvatarHelper;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.ui.WindowLayer;
import com.leshao.v3.ui.widgets.ModernButton;
import com.leshao.v3.ui.widgets.ModernTopBar;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import de.robv.android.xposed.XposedHelpers;

/**
 * 「一键拉群」核心业务：选群 → 逐个邀请 → 随机延迟。
 *
 * <p>逆向依据《微信_一键邀请联系人进多群_逆向分析.md》：</p>
 * <ul>
 *   <li>我的群列表：{@code t73.n.c()}（回落遍历 rcontact 的 {@code LIKE '%@chatroom'}）。</li>
 *   <li>群成员判断：{@code b41.u1.m(room)} 返回成员 username 列表，{@code b41.u1.B(name)}
 *       判断是否群聊。</li>
 *   <li>邀请：{@code ((vf0.e) ph5.n0.c(vf0.e.class)).bj(room).j(room, [target], reason, null)}
 *       得到 Operation，设置回调字段 {@code d} 后调用无参 {@code b()} 立即发送。</li>
 *   <li>随机延迟：每个群发送后 {@code Thread.sleep} 一个 [min,max] 秒区间的随机值。</li>
 * </ul>
 *
 * <p>与文档要求一致，全部通过 {@code getDeclaredMethod + XposedBridge.hookMethod} 无关，
 * 这里只做强反射直调（不 hook 微信方法）。类名均按文档 3180 样本，并给出字符串锚点兜底的
 * 说明性常量。</p>
 */
public final class BatchInviteManager {

    private static final String TAG = "BatchInviteManager";

    // ==================== 文档 3180 样本符号 ====================
    /** RoomServiceFactory 服务接口。 */
    private static final String CLS_ROOM_FACTORY_IF = "vf0.e";
    /** 通用服务定位器：c(Class)->服务。 */
    private static final String CLS_SERVICE_LOCATOR = "ph5.n0";
    /** ChatroomMembersLogic：m(room)=成员列表, B(name)=是否群。 */
    private static final String CLS_CHATROOM_MEMBERS = "b41.u1";
    /** 我的群列表：c()。 */
    private static final String CLS_MY_ROOM_LIST = "t73.n";
    /** 账号工具：u()=我的 wxid。 */
    private static final String CLS_ACCOUNT = "b41.y1";
    /** 邀请链接/系统邀请消息（-2012 需邀请时的降级路径）：c(String,List,String,boolean,String)。 */
    private static final String CLS_INVITE_LINK = "b41.s1";
    /** 邀请链接类锚点：方法体内含该 URL，跨版本可用 DexKit 重新定位。 */
    private static final String ANCHOR_INVITE_LINK = "weixin://findfriend/verifycontact/";

    private static final String TAG_MEMBERS_LOGIC = "MicroMsg.ChatroomMembersLogic";
    private static final String TAG_FTS = "MicroMsg.FTS.FTSApiLogic";
    private static final String TAG_ADD_MEMBER = "MicroMsg.NetSceneAddChatRoomMember";

    private static volatile ClassLoader sCL;
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static final Random sRandom = new Random();

    /** 当前进行中的邀请任务；支持取消，避免 Activity 销毁后仍持续对多个群发送。 */
    private static volatile Thread sInviteThread;
    private static volatile boolean sCancelled;

    /** 每个群的服务端回调结果：room -> errCode（0=成功）。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> sInviteResults =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 已降级发过邀请链接的群（-2012 需邀请）。 */
    private static final java.util.Set<String> sInviteLinkRooms =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private BatchInviteManager() {}

    public static void init(ClassLoader cl) {
        sCL = cl;
        LogWriter.log(TAG, "init cl=" + (cl != null));
    }

    /** 取消进行中的批量邀请（已发送的群不受影响，仅停止后续）。 */
    public static void cancelInvite() {
        sCancelled = true;
        Thread t = sInviteThread;
        if (t != null) t.interrupt();
        LogWriter.log(TAG, "cancelInvite requested");
    }

    public static boolean isInviting() {
        Thread t = sInviteThread;
        return t != null && t.isAlive();
    }

    /**
     * 服务端风控回执检测：收到「由于账号安全原因…无法加入当前群聊」等文案时，
     * 立即暂停当前批量邀请队列，避免继续撞墙加重风控/封禁（一键拉群文档明确建议）。
     * 由 MessageHook 在收到 type=10000 系统消息时调用，可安全在任意线程调用。
     */
    public static void notifyRiskControl(String content) {
        if (content == null) return;
        if (!content.contains("由于账号安全原因") && !content.contains("无法加入当前群聊")) return;
        if (sInviteThread == null) return;
        if (!sCancelled) {
            sCancelled = true;
            LogWriter.log(TAG, "检测到服务端风控回执，暂停批量邀请队列: " + content);
        }
    }

    // ==================== 数据积木 ====================

    private static Class<?> cls(String name) throws ClassNotFoundException {
        // 微信热修复下真实类由 Tinker CL 加载，逐份候选 CL 解析，避免单一 CL 抛 ClassNotFound。
        ClassLoader base = sCL != null ? sCL : ContextManager.getClassLoader();
        for (ClassLoader loader : HookUtil.candidateLoaders(base)) {
            try {
                return XposedHelpers.findClass(name, loader);
            } catch (Throwable ignored) {}
        }
        // 最后兜底：直接尝试
        ClassLoader cl = sCL != null ? sCL : ContextManager.getClassLoader();
        return XposedHelpers.findClass(name, cl);
    }

    /** 是否群聊：b41.u1.B(name)。 */
    public static boolean isRoom(String username) {
        if (username == null || username.isEmpty()) return false;
        try {
            return (Boolean) XposedHelpers.callStaticMethod(cls(CLS_CHATROOM_MEMBERS), "B", username);
        } catch (Throwable t) {
            return username.endsWith("@chatroom") || username.endsWith("@im.chatroom");
        }
    }

    /** 我的所有群：t73.n.c()；失败回落 rcontact 数据。 */
    @SuppressWarnings("unchecked")
    public static List<String> loadMyRooms() {
        try {
            Object r = XposedHelpers.callStaticMethod(cls(CLS_MY_ROOM_LIST), "c");
            if (r instanceof List) {
                List<String> out = new ArrayList<>();
                for (Object o : (List<Object>) r) {
                    if (o instanceof String && !((String) o).isEmpty()) out.add((String) o);
                }
                if (!out.isEmpty()) return out;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "loadMyRooms via t73.n.c failed: " + t);
        }
        // 回落：模块已缓存的群列表（ContactRepository 从 rcontact 读出）
        List<String> fallback = new ArrayList<>();
        try {
            List<ContactCard> groups = ContactRepository.getGroups();
            if (groups != null) {
                for (ContactCard c : groups) {
                    if (c != null && c.username != null && !c.username.isEmpty()) fallback.add(c.username);
                }
            }
        } catch (Throwable ignored) {}
        LogWriter.log(TAG, "loadMyRooms fallback=" + fallback.size());
        return fallback;
    }

    /** 群全部成员 username：b41.u1.m(room)。 */
    @SuppressWarnings("unchecked")
    public static List<String> membersOf(String room) {
        try {
            Object r = XposedHelpers.callStaticMethod(cls(CLS_CHATROOM_MEMBERS), "m", room);
            if (r instanceof List) return (List<String>) r;
        } catch (Throwable t) {
            LogWriter.log(TAG, "membersOf failed room=" + room + " : " + t);
        }
        return Collections.emptyList();
    }

    /**
     * 计算「候选群」= 我的群中，成员列表不含任一目标的群。
     *
     * @param targets 目标好友 username 列表
     */
    public static List<String> candidateGroups(List<String> targets) {
        List<String> result = new ArrayList<>();
        List<String> rooms = loadMyRooms();
        for (String room : rooms) {
            List<String> members = membersOf(room);
            boolean containsAny = false;
            if (targets != null && members != null) {
                for (String t : targets) {
                    if (t != null && members.contains(t)) { containsAny = true; break; }
                }
            }
            if (!containsAny) result.add(room);
        }
        LogWriter.log(TAG, "candidateGroups: rooms=" + rooms.size() + " candidates=" + result.size()
                + " targets=" + (targets == null ? 0 : targets.size()));
        return result;
    }

    // ==================== 群选择弹窗 ====================

    /**
     * 弹出群列表多选（仅候选群），确认后立即开始邀请。
     *
     * @param act       宿主 Activity
     * @param targets   目标好友 wxid 集合
     * @param display   目标好友展示串（仅用于提示）
     */
    public static void showGroupPicker(final Activity act, final List<String> targets, final String display) {
        if (act == null || act.isFinishing()) return;
        if (targets == null || targets.isEmpty()) {
            toast(act, "请先选择要拉入的好友");
            return;
        }
        final AlertDialog loading = new AlertDialog.Builder(act)
                .setTitle("一键拉群")
                .setMessage("正在加载可拉入的群聊…")
                .setCancelable(true)
                .create();
        try { loading.show(); } catch (Throwable ignored) {}

        new Thread(() -> {
            final List<String> candidates = candidateGroups(targets);
            sMain.post(() -> {
                try { loading.dismiss(); } catch (Throwable ignored) {}
                if (candidates.isEmpty()) {
                    toast(act, "没有可拉入的群聊（目标已在所有群里，或群列表读取失败）");
                    return;
                }
                buildGroupDialog(act, targets, display, candidates);
            });
        }, "leshao-invite-loadgroups").start();
    }

    private static void buildGroupDialog(final Activity act, final List<String> targets,
                                         final String display, final List<String> candidates) {
        final int p16 = dp(act, 16), p12 = dp(act, 12), p8 = dp(act, 8), p4 = dp(act, 4);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(act, WindowLayer.depth()));
        InsetsUtil.clipRounded(root);
        CandyUi.elevate(root);

        final ModernTopBar topBar = new ModernTopBar(act, "选择要拉入的群", false, null);
        root.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(act);
        hint.setText("好友：" + (display == null || display.isEmpty() ? targets.size() + " 人" : display));
        hint.setTextSize(12);
        hint.setTextColor(AppColors.onSurfaceVariant());
        hint.setPadding(p16, 0, p16, p8);
        root.addView(hint);

        EditText search = new EditText(act);
        search.setHint("搜索群聊…");
        search.setHintTextColor(AppColors.onSurfaceVariant());
        search.setTextSize(14);
        search.setTextColor(AppColors.onSurface());
        search.setSingleLine(true);
        search.setPadding(p16, dp(act, 10), p16, dp(act, 10));
        search.setBackground(CandyUi.inputBg(act));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.setMargins(p12, 0, p12, p8);
        root.addView(search, slp);

        final LinearLayout listRoot = new LinearLayout(act);
        listRoot.setOrientation(LinearLayout.VERTICAL);
        listRoot.setPadding(p12, 0, p12, 0);
        ScrollView sv = new ScrollView(act);
        sv.addView(listRoot);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        final java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            try {
                listRoot.removeAllViews();
                String f = search.getText().toString().toLowerCase().trim();
                int shown = 0;
                for (String room : candidates) {
                    String name = roomDisplayName(room);
                    if (!f.isEmpty() && !name.toLowerCase().contains(f) && !room.toLowerCase().contains(f)) continue;
                    listRoot.addView(buildGroupRow(act, room, name, selected.contains(room), () -> {
                        if (selected.contains(room)) selected.remove(room);
                        else selected.add(room);
                        refresh[0].run();
                    }));
                    shown++;
                }
                if (shown == 0) {
                    TextView empty = new TextView(act);
                    empty.setText("无匹配群聊");
                    empty.setTextSize(14);
                    empty.setTextColor(AppColors.onSurfaceVariant());
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, dp(act, 40), 0, 0);
                    listRoot.addView(empty);
                }
                topBar.setTitle("已选 " + selected.size() + " 个群");
            } catch (Throwable t) {
                LogWriter.log(TAG, "refresh group list err: " + t);
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh[0].run(); }
        });
        refresh[0].run();

        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(p16, p8, p16, p12);
        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = p4;
        blp.rightMargin = p4;

        final ModernButton cancel = new ModernButton(act, "取消", ModernButton.STYLE_GHOST);
        final ModernButton selectAll = new ModernButton(act, "全选", ModernButton.STYLE_TEXT);
        final ModernButton confirm = new ModernButton(act, "确认邀请", ModernButton.STYLE_PRIMARY);
        btns.addView(cancel, blp);
        btns.addView(space(act, p16));
        btns.addView(selectAll, blp);
        btns.addView(space(act, p16));
        btns.addView(confirm, blp);
        bottom.addView(btns);
        root.addView(bottom);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setView(root).setCancelable(true).create();

        cancel.setOnClickListener(v -> dialog.dismiss());
        selectAll.setOnClickListener(v -> {
            String f = search.getText().toString().toLowerCase().trim();
            List<String> visible = new ArrayList<>();
            for (String room : candidates) {
                String name = roomDisplayName(room);
                if (!f.isEmpty() && !name.toLowerCase().contains(f) && !room.toLowerCase().contains(f)) continue;
                visible.add(room);
            }
            boolean all = !visible.isEmpty() && selected.containsAll(visible);
            if (all) selected.removeAll(visible);
            else selected.addAll(visible);
            refresh[0].run();
        });
        confirm.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                Toast.makeText(act, "请至少选择一个群聊", Toast.LENGTH_SHORT).show();
                return;
            }
            final List<String> chosen = new ArrayList<>(selected);
            dialog.dismiss();
            startInvite(act, chosen, targets);
        });

        InsetsUtil.transparentWindow(dialog);
        dialog.show();
        WindowLayer.track(dialog.getWindow());
    }

    // ==================== 邀请执行 ====================

    /** 展示 → 后台线程逐群邀请，随机延迟。 */
    public static void startInvite(final Activity act, final List<String> rooms, final List<String> targets) {
        final int delayMin = BatchInviteConfig.getDelayMin();
        final int delayMax = Math.max(delayMin, BatchInviteConfig.getDelayMax());
        LogWriter.log(TAG, "startInvite rooms=" + rooms.size() + " targets=" + targets.size()
                + " delay=" + delayMin + "~" + delayMax + "s");
        if (isInviting()) {
            LogWriter.log(TAG, "startInvite: 已有任务进行中，取消旧任务");
            cancelInvite();
        }
        sCancelled = false;
        sInviteResults.clear();
        sInviteLinkRooms.clear();
        Thread worker = new Thread(() -> {
            int sent = 0, sendFail = 0, cancelled = 0;
            for (int i = 0; i < rooms.size(); i++) {
                if (sCancelled || Thread.currentThread().isInterrupted()) {
                    cancelled = rooms.size() - i;
                    break;
                }
                String room = rooms.get(i);
                try {
                    boolean r = inviteToRoom(room, targets);
                    if (r) sent++; else sendFail++;
                } catch (Throwable t) {
                    sendFail++;
                    LogWriter.log(TAG, "invite room failed: " + room + " : " + t);
                }
                // 最后一个群之后不再等待
                if (i < rooms.size() - 1) {
                    int delaySec = delayMin;
                    if (delayMax > delayMin) delaySec = delayMin + sRandom.nextInt(delayMax - delayMin + 1);
                    LogWriter.log(TAG, "随机延迟 " + delaySec + "s before next room");
                    if (!sleepInterruptibly(delaySec * 1000L)) {
                        cancelled = rooms.size() - 1 - i;
                        break;
                    }
                }
            }
            // 等待服务端异步回调（最多 ~2s），统计真实成功/失败
            long waitUntil = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < waitUntil && sInviteResults.size() < sent) {
                try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
            }
            int accepted = 0, rejected = 0, needInvite = 0;
            for (Integer code : sInviteResults.values()) {
                if (code != null && code == 0) accepted++;
                else {
                    rejected++;
                    if (code != null && code == -2012) needInvite++;
                }
            }
            // -2012(Need invite) 不是"发送失败"，而是服务端已受理请求、但该群开启
            // 「群主/被邀请人确认」或目标非好友强校验，需要二次确认。单独归类，避免误导用户。
            final int realReject = Math.max(0, rejected - needInvite);
            final int fsent = sent, fsendFail = sendFail, fcancel = cancelled;
            final int faccept = accepted, freject = realReject, fneed = needInvite;
            final int flink = sInviteLinkRooms.size();
            sInviteThread = null;
            sMain.post(() -> {
                if (act != null && !act.isFinishing()) {
                    StringBuilder msg = new StringBuilder();
                    if (faccept > 0) msg.append("成功拉入 ").append(faccept).append(" 个群");
                    if (freject > 0) {
                        if (msg.length() > 0) msg.append("，");
                        msg.append("失败 ").append(freject).append(" 个群");
                    }
                    if (fneed > 0) {
                        if (msg.length() > 0) msg.append("，");
                        if (flink > 0) {
                            msg.append("已发送邀请链接 ").append(flink).append(" 个群（对方确认后进群）");
                        } else {
                            msg.append("已发出邀请请求 ").append(fneed).append(" 个群（需对方/群主确认）");
                        }
                    }
                    if (fsendFail > 0) {
                        if (msg.length() > 0) msg.append("，");
                        msg.append("发送失败 ").append(fsendFail).append(" 个");
                    }
                    if (fcancel > 0) {
                        if (msg.length() > 0) msg.append("，");
                        msg.append("已取消 ").append(fcancel).append(" 个");
                    }
                    if (msg.length() == 0) msg.append("未发起任何邀请");
                    Toast.makeText(act, msg.toString(), Toast.LENGTH_LONG).show();
                }
            });
            LogWriter.log(TAG, "startInvite done sent=" + fsent + " sendFail=" + fsendFail
                    + " accept=" + faccept + " reject=" + freject + " needInvite=" + fneed
                    + " inviteLink=" + flink + " cancel=" + fcancel);
        }, "leshao-batch-invite");
        worker.setDaemon(true);
        sInviteThread = worker;
        worker.start();
    }

    /** 可中断的毫秒级休眠，返回 false 表示被取消。 */
    private static boolean sleepInterruptibly(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (true) {
            if (sCancelled || Thread.currentThread().isInterrupted()) return false;
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) return true;
            try {
                Thread.sleep(Math.min(remain, 500L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * 对单个群发起邀请。
     * <pre>
     *   factory       = ph5.n0.c(vf0.e.class)
     *   roomOp        = factory.bj(room)               // 按 @后缀选 chatroom/openIM
     *   Operation op  = roomOp.j(room, targets, "", null)
     *   op.d = 回调(qe5.b)
     *   op.b()                                         // 无 UI 立即发送
     * </pre>
     */
    public static boolean inviteToRoom(String room, List<String> targets) {
        try {
            Class<?> factoryIf = cls(CLS_ROOM_FACTORY_IF);
            ClassLoader cl = factoryIf.getClassLoader();
            Object factory = XposedHelpers.callStaticMethod(cls(CLS_SERVICE_LOCATOR), "c", factoryIf);
            if (factory == null) {
                LogWriter.log(TAG, "inviteToRoom: factory(null) room=" + room);
                return false;
            }
            Object roomOp = XposedHelpers.callMethod(factory, "bj", room);
            if (roomOp == null) {
                LogWriter.log(TAG, "inviteToRoom: roomOp(null) room=" + room);
                return false;
            }
            Object op = XposedHelpers.callMethod(roomOp, "j",
                    room, new ArrayList<>(targets), BatchInviteConfig.getReason(), null);
            if (op == null) {
                LogWriter.log(TAG, "inviteToRoom: op(null) room=" + room);
                return false;
            }
            // 结果回调：字段 d = qe5.b
            try {
                Class<?> cbIf = findRoomCallbackInterface(cl);
                if (cbIf != null) {
                    Object cb = Proxy.newProxyInstance(cl, new Class[]{cbIf},
                            (proxy, method, args) -> {
                                if ("a".equals(method.getName()) && args != null && args.length >= 3) {
                                    int errType = args[0] instanceof Integer ? (Integer) args[0] : -1;
                                    int errCode = args[1] instanceof Integer ? (Integer) args[1] : -1;
                                    String errMsg = args[2] instanceof String ? (String) args[2] : "";
                                    sInviteResults.put(room, errCode);
                                    LogWriter.log(TAG, "invite callback room=" + room
                                            + " errType=" + errType + " errCode=" + errCode + " errMsg=" + errMsg);
                                    // -2012(Need invite)：该群开启「邀请确认」，强拉被拒。
                                    // 降级走微信正规路径：以本账号在群里发 type=10000 系统邀请链接，对方点确认即可进群。
                                    if (errCode == -2012
                                            || (errMsg != null && errMsg.toLowerCase().contains("need invite"))) {
                                        boolean linkSent = sendInviteLink(room, targets);
                                        if (linkSent) sInviteLinkRooms.add(room);
                                        LogWriter.log(TAG, "invite -2012 -> 邀请链接 linkSent=" + linkSent
                                                + " room=" + room);
                                    }
                                }
                                return null;
                            });
                    XposedHelpers.setObjectField(op, "d", cb);
                }
            } catch (Throwable t) {
                LogWriter.log(TAG, "set callback field d failed: " + t);
            }
            // b() = 无 UI 直接发送（注意与字段 b 区分，这里是方法）
            XposedHelpers.callMethod(op, "b");
            LogWriter.log(TAG, "inviteToRoom sent room=" + room + " targets=" + targets.size()
                    + " reason=\"" + BatchInviteConfig.getReason() + "\" list=" + targets);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "inviteToRoom err room=" + room + " : " + t);
            return false;
        }
    }

    /**
     * -2012 降级：发送群邀请链接（type=10000 系统消息，本账号发出，对方点确认后进群）。
     * <pre>
     *   b41.s1.c(String room, List targets, String tpl, boolean, String url)
     *   url = "weixin://findfriend/verifycontact/" + room + "/"
     * </pre>
     * 这是微信自身「需邀请」群的正规路径，可绕过 addchatroommember 的权限拒绝
     * （但绕不过账号级风控）。
     */
    private static boolean sendInviteLink(String room, List<String> targets) {
        try {
            ClassLoader cl = sCL != null ? sCL : BatchInviteManager.class.getClassLoader();
            Class<?> c = resolveInviteLinkClass(cl);
            if (c == null) {
                LogWriter.log(TAG, "sendInviteLink: invite-link class not found");
                return false;
            }
            String tpl = BatchInviteConfig.getReason();
            if (tpl == null || tpl.isEmpty()) tpl = "%s 邀请你加入群聊";
            String url = ANCHOR_INVITE_LINK + room + "/";
            XposedHelpers.callStaticMethod(c, "c",
                    room, new ArrayList<>(targets), tpl, Boolean.TRUE, url);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "sendInviteLink err room=" + room + " : " + t);
            return false;
        }
    }

    /** 解析邀请链接类 b41.s1（硬编码优先，DexKit 锚点兜底）。 */
    private static Class<?> resolveInviteLinkClass(ClassLoader cl) {
        try {
            return XposedHelpers.findClass(CLS_INVITE_LINK, cl);
        } catch (Throwable ignored) {}
        try {
            List<String> cands = DexKitHelper.findClassesByString(cl, ANCHOR_INVITE_LINK);
            for (String cn : cands) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getMethods()) {
                        if ("c".equals(m.getName()) && m.getParameterTypes().length == 5) return c;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 解析房间结果回调接口 qe5.b（含 DexKit 兜底，避免硬编码失效）。 */
    private static Class<?> findRoomCallbackInterface(ClassLoader cl) {
        try {
            return XposedHelpers.findClass("qe5.b", cl);
        } catch (Throwable ignored) {}
        try {
            List<String> cands = DexKitHelper.findClassesByString(cl, TAG_ADD_MEMBER);
            for (String cn : cands) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getMethods()) {
                        if ("a".equals(m.getName()) && m.getParameterTypes().length == 4) return c;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 群展示名：优先模块缓存的会话昵称，回退 nickname，最后 username。 */
    public static String roomDisplayName(String room) {
        try {
            ContactCard c = ContactRepository.findByUsername(room);
            if (c != null) {
                String n = c.displayName();
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {}
        return room;
    }

    // ==================== UI 辅助 ====================

    private static View buildGroupRow(Activity act, final String room, String name,
                                      boolean checked, Runnable onToggle) {
        int p12 = dp(act, 12), p8 = dp(act, 8);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(p12, dp(act, 7), p12, dp(act, 7));
        row.setBackground(CandyUi.rowPressBg(act));

        ImageView cb = new ImageView(act);
        cb.setImageDrawable(makeCheckbox(act, checked));
        cb.setScaleType(ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(dp(act, 28), dp(act, 28));
        cblp.setMargins(0, 0, p8, 0);
        row.addView(cb, cblp);

        int avatarSize = dp(act, 40);
        ImageView avatar = new ImageView(act);
        Bitmap fallback = letterAvatar(act, name.substring(0, Math.min(1, name.length())), avatarSize);
        AvatarHelper.loadAvatarAsync(avatar, room, avatarSize, fallback);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        alp.setMargins(0, 0, p12, 0);
        row.addView(avatar, alp);

        LinearLayout textCol = new LinearLayout(act);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(act);
        tv.setText(name);
        tv.setTextSize(14);
        tv.setTextColor(AppColors.onSurface());
        tv.setSingleLine(true);
        textCol.addView(tv);
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1f));

        CandyUi.ripple(row, AppColors.SHAPE_MD_DP);
        row.setOnClickListener(v -> onToggle.run());
        return row;
    }

    private static Drawable makeCheckbox(Activity act, boolean checked) {
        int size = dp(act, 22);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        if (checked) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setShader(new android.graphics.LinearGradient(0, 0, size, size,
                    AppColors.gradientColors(), null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, fill);
            Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);
            check.setColor(AppColors.whiteTextOnAccent());
            check.setStrokeWidth(dp(act, 2.2f));
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawLine(size * 0.32f, size * 0.52f, size * 0.46f, size * 0.66f, check);
            canvas.drawLine(size * 0.46f, size * 0.66f, size * 0.72f, size * 0.35f, check);
        } else {
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(AppColors.outline());
            stroke.setStrokeWidth(dp(act, 2));
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1, stroke);
        }
        return new BitmapDrawable(act.getResources(), bm);
    }

    private static Bitmap letterAvatar(Activity act, String letter, int size) {
        Paint paint = new Paint();
        paint.setColor(AppColors.onSecondaryContainer());
        paint.setTextSize(size * 0.45f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint bgPaint = new Paint();
        bgPaint.setColor(AppColors.secondaryContainer());
        canvas.drawRoundRect(0, 0, size, size, size / 2f, size / 2f, bgPaint);
        float y = size / 2f - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(letter == null || letter.isEmpty() ? "群" : letter, size / 2f, y, paint);
        return bm;
    }

    private static View space(Activity act, int w) {
        View v = new View(act);
        v.setLayoutParams(new ViewGroup.LayoutParams(w, 1));
        return v;
    }

    private static void toast(final Activity act, final String msg) {
        sMain.post(() -> {
            try {
                if (act != null && !act.isFinishing()) Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private static int dp(Activity act, float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressWarnings("unused")
    private static boolean isDarkMode(Activity act) {
        return (act.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
