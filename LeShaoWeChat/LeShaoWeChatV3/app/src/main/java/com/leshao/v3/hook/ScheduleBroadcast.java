package com.leshao.v3.hook;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 定时消息群发 - WeChat 8.0.76 全功能实现 (55项)
 *
 * 微信内部API (8.0.76反编译验证):
 *   e01.d9.b().u()                  -> f9 实例 (MsgInfoStorage)
 *   f9.H9(e9) -> long               -> 插入消息(自动分配msgId)
 *   f9.Ra(long,e9) -> int           -> 更新消息
 *   e9.A1(int)->void                 -> setType
 *   e9.X0(String)->void              -> setContent
 *   e9.d1(String)->void              -> setContent(AppMsg XML)
 *   e9.e1(long)->void                -> setCreateTime
 *   e9.k1(int)->void                 -> setIsSend (1=发送)
 *   e9.j1(String)->void              -> setImgPath
 *   e9.G1()->boolean                 -> getIsSend
 *   y21.x0.g(String,String)->String  -> 创建语音w0
 *   y21.x0.t(String,int,int,e9)->bool-> 语音写DB
 *   y21.p0.kj()->tl.q0              -> SceneVoice服务
 *   tl.q0.e()->void                  -> 刷新播放列表
 *   qh3.u0.Mj(y,String,boolean)->String -> 获取源文件完整路径
 *   qh3.u0.Nj(y,String,boolean,boolean)->String->获取目标文件路径
 *   lin5.y.j -> y                    -> 语音上下文对象
 */
public class ScheduleBroadcast {

    private static final String TAG = "Schedule";

    private static boolean sEnabled = true;
    private static int sMinIntervalSec = 3;
    private static int sMaxIntervalSec = 30;
    private static int sDailyMaxSend = 200;
    private static int sWeeklyMaxSend = 1000;
    private static int sRetryTimes = 3;
    private static int sRetryIntervalSec = 60;
    private static boolean sWifiOnly = false;
    private static boolean sChargingOnly = false;
    private static boolean sNightSilent = true;
    private static boolean sLockScreenPause = true;
    private static String sTimeWindow = "";
    private static String sNightSlowWindow = "22:00-06:00";
    private static int sNightSlowIntervalSec = 300;

    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
        "赌博", "彩票", "色情", "裸聊", "枪支", "毒品", "高利贷",
        "代办信用卡", "套现", "刷单", "传销"
    );

    public static final Map<String, String> TEMPLATE_LIBRARY = new LinkedHashMap<>();
    static {
        TEMPLATE_LIBRARY.put("早报", "早上好！今天是{date} {weekday}\n群成员{member_count}人，昨日发言{yesterday_msg}条");
        TEMPLATE_LIBRARY.put("晚安", "晚安，明天见！");
        TEMPLATE_LIBRARY.put("通知", "【通知】\n{content}\n\n—— {group_name}群管理");
        TEMPLATE_LIBRARY.put("促销", "【限时优惠】\n{content}\n\n数量有限，先到先得！");
        TEMPLATE_LIBRARY.put("群规", "【群规】\n1.禁发广告\n2.禁发色情内容\n3.禁止辱骂\n违规者直接移出");
        TEMPLATE_LIBRARY.put("欢迎", "欢迎 {nickname} 加入 {group_name}！\n请阅读群规，祝愉快交流~");
        TEMPLATE_LIBRARY.put("潜水提醒", "本群将在{days}天后清理{days}天以上未发言的成员，请保持活跃！");
    }

    public static final SceneTemplate[] SCENE_TEMPLATES = {
        new SceneTemplate("每日早报", "早上好！今天是{date} {weekday}\n群成员{member_count}人", 7, 0, 0, 86400000L),
        new SceneTemplate("晚安问候", "晚安，明天见！", 22, 0, 0, 86400000L),
        new SceneTemplate("整点报时", "现在是{time}，整点报时~", -1, 0, 0, 3600000L),
        new SceneTemplate("每周群规", "每周群规提醒：\n1.禁发广告\n2.禁发色情内容\n3.禁止辱骂\n违规者直接移出", 9, Calendar.MONDAY, 0, 604800000L),
        new SceneTemplate("潜水提醒(月)", "本群将在7天后清理30天以上未发言成员", 10, 1, 0, 2592000000L),
        new SceneTemplate("午间促销", "【午间特惠】\n{content}", 12, 0, 0, 86400000L),
        new SceneTemplate("晚间促销", "【晚间特卖】\n{content}", 20, 0, 0, 86400000L),
    };

    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static final AtomicBoolean sStopFlag = new AtomicBoolean(false);
    private static final AtomicInteger sDailyCount = new AtomicInteger(0);
    private static final AtomicInteger sWeeklyCount = new AtomicInteger(0);
    private static String sLastDailyDate = "";
    private static int sLastWeekOfYear = -1;
    private static final CopyOnWriteArrayList<Task> sTaskQueue = new CopyOnWriteArrayList<>();
    private static final List<Task> sDraftBox = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> sContentRotationIndex = new HashMap<>();
    private static final Map<String, AtomicLong> sGroupCooldowns = new HashMap<>();
    private static final Set<String> sExcludeGroups = Collections.synchronizedSet(new HashSet<>());
    private static Context sAppContext;
    private static ClassLoader sClassLoader;
    private static Object sMsgStorage;
    private static Object sCapturedA21q;
    private static Object sCapturedN85r;
    private static Object sN85d0InvokeArg;
    private static Object sN85d0Inst;
    private static Object sCapturedN85z;  // n85.z from normal send
    private static Object sCapturedN85c0;  // n85.c0 Continuation
    private static Object sCapturedScope;  // SequenceLifecycleScope
    private static Object sD85i_cArg;
    private static Object sD85iInst;
    private static Object sD85d_jArg;
    private static Object sD85d_jInst;
    private static volatile Thread sScheduleThread;
    private static final java.util.Set<String> sSendingTasks = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final AtomicBoolean sInitialized = new AtomicBoolean(false);
    private static HandlerThread sHandlerThread;
    private static Handler sHandler;
    private static final SimpleDateFormat sdfDateTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    private static final SimpleDateFormat sdfLog = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());

    // ===== 数据模型 =====

    public static class Task {
        public String id;
        public String content;
        public int msgType;
        public String filePath;
        public long triggerTime;
        public long repeatInterval;
        public List<String> targetGroups;
        public List<String> groupTags;
        public boolean enabled;
        public int failCount;
        public long lastExecTime;
        public int totalSendCount;
        public boolean sendAllGroups;
        public boolean randomOrder;
        public boolean useTemplate;
        public String templateKey;
        public List<String> contentPool;
        public boolean varNickname;
        public boolean varDate;
        public boolean varTime;
        public boolean varGroupName;
        public boolean varMemberCount;
        public boolean randomEmoji;
        public boolean sensitiveCheck;

        public Task() {
            this.id = String.valueOf(System.currentTimeMillis());
            this.msgType = 1;
            this.enabled = true;
            this.targetGroups = new ArrayList<>();
            this.groupTags = new ArrayList<>();
            this.contentPool = new ArrayList<>();
        }

        public JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("id", id);
                j.put("content", nvl(content));
                j.put("msgType", msgType);
                j.put("filePath", nvl(filePath));
                j.put("triggerTime", triggerTime);
                j.put("repeatInterval", repeatInterval);
                j.put("enabled", enabled);
                j.put("failCount", failCount);
                j.put("lastExecTime", lastExecTime);
                j.put("totalSendCount", totalSendCount);
                j.put("sendAllGroups", sendAllGroups);
                j.put("randomOrder", randomOrder);
                j.put("useTemplate", useTemplate);
                j.put("templateKey", nvl(templateKey));
                j.put("varNickname", varNickname);
                j.put("varDate", varDate);
                j.put("varTime", varTime);
                j.put("varGroupName", varGroupName);
                j.put("varMemberCount", varMemberCount);
                j.put("randomEmoji", randomEmoji);
                j.put("sensitiveCheck", sensitiveCheck);
                j.put("targetGroups", listToJson(targetGroups));
                j.put("groupTags", listToJson(groupTags));
                j.put("contentPool", listToJson(contentPool));
                return j;
            } catch (Throwable t) { return new JSONObject(); }
        }

        public static Task fromJson(JSONObject j) {
            try {
                Task t = new Task();
                t.id = j.optString("id", t.id);
                t.content = j.optString("content", "");
                t.msgType = j.optInt("msgType", 1);
                t.filePath = j.optString("filePath", "");
                t.triggerTime = j.optLong("triggerTime", 0);
                t.repeatInterval = j.optLong("repeatInterval", 0);
                t.enabled = j.optBoolean("enabled", true);
                t.failCount = j.optInt("failCount", 0);
                t.lastExecTime = j.optLong("lastExecTime", 0);
                t.totalSendCount = j.optInt("totalSendCount", 0);
                t.sendAllGroups = j.optBoolean("sendAllGroups", false);
                t.randomOrder = j.optBoolean("randomOrder", false);
                t.useTemplate = j.optBoolean("useTemplate", false);
                t.templateKey = j.optString("templateKey", "");
                t.varNickname = j.optBoolean("varNickname", false);
                t.varDate = j.optBoolean("varDate", false);
                t.varTime = j.optBoolean("varTime", false);
                t.varGroupName = j.optBoolean("varGroupName", false);
                t.varMemberCount = j.optBoolean("varMemberCount", false);
                t.randomEmoji = j.optBoolean("randomEmoji", false);
                t.sensitiveCheck = j.optBoolean("sensitiveCheck", false);
                t.targetGroups = jsonToList(j.optJSONArray("targetGroups"));
                t.groupTags = jsonToList(j.optJSONArray("groupTags"));
                t.contentPool = jsonToList(j.optJSONArray("contentPool"));
                return t;
            } catch (Throwable tr) { return null; }
        }
    }

    public static class SceneTemplate {
        public String name;
        public String content;
        public int hour;
        public int dayOfWeek;
        public int dayOfMonth;
        public long intervalMs;
        public boolean enabled = true;

        public SceneTemplate(String n, String c, int h, int dow, int dom, long interval) {
            name = n; content = c; hour = h; dayOfWeek = dow; dayOfMonth = dom; intervalMs = interval;
        }
    }

    // ===== 工具 =====

    private static String nvl(String s) { return s == null ? "" : s; }
    private static JSONArray listToJson(List<String> list) {
        JSONArray a = new JSONArray();
        if (list != null) for (String s : list) a.put(s);
        return a;
    }
    private static List<String> jsonToList(JSONArray a) {
        List<String> list = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) try { list.add(a.getString(i)); } catch (Throwable ignored) {}
        return list;
    }

    // ===== 初始化 =====

    public static void init(Context ctx, ClassLoader cl) {
        if (!sInitialized.compareAndSet(false, true)) return;
        sAppContext = ctx.getApplicationContext();
        sClassLoader = cl;

        log("DIAG: init V3 开始(rv5.t0.d调度)");

        loadConfig();
        initMsgStorage();
        loadExcludeGroups();
        loadTasks();
        loadDrafts();
        loadTemplates();
        loadLogs();
        resetCounters();

        startWxScheduler();

        // ★★ DIAG: hook 所有发消息相关的类
        installDiagHooks();

        log("定时消息群发初始化完成(微信线程池模式), 任务=" + sTaskQueue.size());
    }

    private static void installDiagHooks() {
        // === 完整发送链路追踪 ===
        // 链路: d85.d.j → d85.i.c → n85.d0.invoke → n85.c0.create → invokeSuspend → n85.r → a21.q → a21.q.i → I9 → 联网
        try {
            Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", sClassLoader);
            XposedBridge.hookAllMethods(f9, "H9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("TRACE H9(" + param.args.length + ") p0="
                        + (param.args[0] == null ? "null" : param.args[0].getClass().getSimpleName()));
                }
            });
            XposedBridge.hookAllMethods(f9, "I9", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("TRACE I9(" + param.args.length + ") "
                        + (param.args[0] == null ? "null" : param.args[0].getClass().getSimpleName())
                        + " " + (param.args.length > 1 ? param.args[1] : ""));
                }
            });
            log("TRACE: f9 OK");
        } catch (Throwable t) { log("TRACE: f9 fail: " + t.getMessage()); }

        // d85.d.j — 发送流程最顶层入口(可能在SendBtnMgr或InputController)
        try {
            Class<?> d85d = XposedHelpers.findClass("d85.d", sClassLoader);
            XposedBridge.hookAllMethods(d85d, "j", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sD85d_jInst = param.thisObject;
                    Object[] args = param.args;
                    StringBuilder sb = new StringBuilder("TRACE d85.d.j(").append(args.length).append(")");
                    for (int i = 0; i < args.length; i++)
                        sb.append(" p").append(i).append("=").append(args[i] == null ? "null" : args[i].getClass().getName());
                    if (args.length > 0) sD85d_jArg = args[0];
                    log(sb.toString());
                    printStack("d85.d.j", Thread.currentThread().getStackTrace(), 20);
                }
            });
            log("TRACE: d85.d.j OK");
        } catch (Throwable t) { log("TRACE: d85.d.j fail: " + t.getMessage()); }

        // d85.i.c — 调用 n85.d0.invoke 的中间层
        try {
            Class<?> d85i = XposedHelpers.findClass("d85.i", sClassLoader);
            XposedBridge.hookAllMethods(d85i, "c", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sD85iInst = param.thisObject;
                    Object[] args = param.args;
                    StringBuilder sb = new StringBuilder("TRACE d85.i.c(").append(args.length).append(")");
                    for (int i = 0; i < args.length; i++)
                        sb.append(" p").append(i).append("=").append(args[i] == null ? "null" : args[i].getClass().getName());
                    if (args.length > 0) sD85i_cArg = args[0];
                    log(sb.toString());
                    printStack("d85.i.c", Thread.currentThread().getStackTrace(), 15);
                }
            });
            log("TRACE: d85.i.c OK");
        } catch (Throwable t) { log("TRACE: d85.i.c fail: " + t.getMessage()); }

        // n85.d0 — 核心入口: Function1<MessageObj, Unit>
        try {
            Class<?> n85d0 = XposedHelpers.findClass("n85.d0", sClassLoader);
            XposedBridge.hookAllConstructors(n85d0, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sN85d0Inst = param.thisObject;
                    Object[] args = param.args;
                    StringBuilder sb = new StringBuilder("TRACE n85.d0 ctor(").append(args.length).append(")");
                    for (int i = 0; i < args.length; i++)
                        sb.append(" p").append(i).append("=").append(args[i] == null ? "null" : args[i].getClass().getName());
                    log(sb.toString());
                }
            });
            // Also hook invoke via kotlin Function1 interface
            XposedBridge.hookAllMethods(n85d0, "invoke", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sN85d0Inst = param.thisObject;
                    Object[] args = param.args;
                    if (args.length > 0 && args[0] != null) {
                        sN85d0InvokeArg = args[0];
                        // Capture n85.z if this is a real send (not our trigger)
                        if (args[0].getClass().getName().equals("n85.z")) {
                            sCapturedN85z = args[0];
                        }
                    }
                    StringBuilder sb = new StringBuilder("TRACE n85.d0.invoke(").append(args.length).append(")");
                    for (int i = 0; i < args.length; i++) {
                        sb.append(" p").append(i).append("=");
                        sb.append(args[i] == null ? "null" : args[i].getClass().getName() + "=" + args[i].toString().substring(0, Math.min(80, args[i].toString().length())));
                    }
                    log(sb.toString());
                    printStack("n85.d0.invoke", Thread.currentThread().getStackTrace(), 20);
                }
            });
            log("TRACE: n85.d0 OK methods=" + n85d0.getDeclaredMethods().length
                + " super=" + n85d0.getSuperclass().getName()
                + " ifaces=" + java.util.Arrays.toString(n85d0.getInterfaces()));
        } catch (Throwable t) { log("TRACE: n85.d0 fail: " + t.getMessage()); }

        // n85.c0 — Continuation (协程)
        try {
            Class<?> n85c0 = XposedHelpers.findClass("n85.c0", sClassLoader);
            XposedBridge.hookAllMethods(n85c0, "create", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("TRACE n85.c0.create(" + param.args.length + ")");
                    printStack("n85.c0.create", Thread.currentThread().getStackTrace(), 12);
                }
            });
            XposedBridge.hookAllMethods(n85c0, "invokeSuspend", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("TRACE n85.c0.invokeSuspend(" + param.args.length + ")");
                    printStack("n85.c0.invokeSuspend", Thread.currentThread().getStackTrace(), 12);
                }
            });
            log("TRACE: n85.c0 OK");
        } catch (Throwable t) { log("TRACE: n85.c0 fail: " + t.getMessage()); }

        // n85.r — 发送上下文
        try {
            Class<?> n85r = XposedHelpers.findClass("n85.r", sClassLoader);
            XposedBridge.hookAllConstructors(n85r, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sCapturedN85r = param.thisObject;
                    Object[] args = param.args;
                    StringBuilder sb = new StringBuilder("TRACE n85.r ctor(").append(args.length).append(")");
                    for (int i = 0; i < args.length; i++)
                        sb.append(" p").append(i).append("=").append(args[i] == null ? "null" : args[i].getClass().getName());
                    log(sb.toString());
                }
            });
            log("TRACE: n85.r OK ctors=" + n85r.getDeclaredConstructors().length);
        } catch (Throwable t) { log("TRACE: n85.r fail: " + t.getMessage()); }

        // a21.q — 发送消息逻辑, 构造器 + i方法
        try {
            Class<?> a21q = XposedHelpers.findClass("a21.q", sClassLoader);
            XposedBridge.hookAllConstructors(a21q, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sCapturedA21q = param.thisObject;
                    Object[] args = param.args;
                    if (args.length > 0) sCapturedN85r = args[0];
                    log("TRACE a21.q ctor(" + args.length + ")");
                }
            });
            XposedBridge.hookAllMethods(a21q, "i", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("TRACE a21.q.i(" + param.args.length + ") p0="
                        + (param.args[0] == null ? "null" : param.args[0].getClass().getSimpleName())
                        + " p1=" + (param.args.length > 1 ? (param.args[1] == null ? "null" : param.args[1].getClass().getSimpleName()) : "-")
                        + " p2=" + (param.args.length > 2 ? (param.args[2] == null ? "null" : param.args[2].getClass().getSimpleName()) : "-"));
                }
            });
            log("TRACE: a21.q OK");
        } catch (Throwable t) { log("TRACE: a21.q fail: " + t.getMessage()); }

        // n85.z — 消息payload, n85.d0.invoke的参数
        try {
            Class<?> n85z = XposedHelpers.findClass("n85.z", sClassLoader);
            log("TRACE: n85.z fields:");
            for (java.lang.reflect.Field f : n85z.getDeclaredFields())
                log("  field: " + f.getName() + " " + f.getType().getName());
            log("TRACE: n85.z methods:");
            for (java.lang.reflect.Method m : n85z.getDeclaredMethods()) {
                if (m.getParameterTypes().length <= 2 && m.getReturnType() != void.class) {
                    log("  method: " + m.getName() + "(" + m.getParameterTypes().length + ") -> " + m.getReturnType().getSimpleName());
                }
            }
            log("TRACE: n85.z OK");
        } catch (Throwable t) { log("TRACE: n85.z fail: " + t.getMessage()); }
    }

    private static void printStack(String label, StackTraceElement[] st, int max) {
        StringBuilder sb = new StringBuilder("  ").append(label).append("栈(").append(Thread.currentThread().getName()).append("):");
        for (int i = 3; i < Math.min(st.length, max); i++) {
            String cls = st[i].getClassName();
            if (!cls.startsWith("java.lang.") && !cls.startsWith("android.") && !cls.startsWith("dalvik.")
                && !cls.startsWith("de.robv.android.xposed")) {
                sb.append("\n    ").append(cls).append(".").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
            }
        }
        log(sb.toString());
    }

    private static int safeInt(Object obj, String[] methods) {
        for (String m : methods) {
            try { Object v = XposedHelpers.callMethod(obj, m); return v instanceof Integer ? (Integer) v : -1; } catch (Throwable ignored) {}
        }
        return -1;
    }
    private static String safeStr(Object obj, String[] methods) {
        for (String m : methods) {
            try { Object v = XposedHelpers.callMethod(obj, m); return v == null ? "null" : v.toString(); } catch (Throwable ignored) {}
        }
        return "err";
    }
    private static long safeLong(Object obj, String[] methods) {
        for (String m : methods) {
            try { Object v = XposedHelpers.callMethod(obj, m); return v instanceof Long ? (Long) v : -1; } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static void loadConfig() {
        SharedPreferences sp = sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE);
        sEnabled = sp.getBoolean("enabled", true);
        sMinIntervalSec = sp.getInt("min_interval", 3);
        sMaxIntervalSec = sp.getInt("max_interval", 30);
        sDailyMaxSend = sp.getInt("daily_max", 200);
        sWeeklyMaxSend = sp.getInt("weekly_max", 1000);
        sRetryTimes = sp.getInt("retry_times", 3);
        sRetryIntervalSec = sp.getInt("retry_interval", 60);
        sWifiOnly = sp.getBoolean("wifi_only", false);
        sChargingOnly = sp.getBoolean("charging_only", false);
        sNightSilent = sp.getBoolean("night_silent", true);
        sLockScreenPause = sp.getBoolean("lock_screen_pause", true);
        sTimeWindow = sp.getString("time_window", "");
        sNightSlowWindow = sp.getString("night_slow_window", "22:00-06:00");
        sNightSlowIntervalSec = sp.getInt("night_slow_interval", 300);
    }

    // ===== f9 实例 =====

    private static void initMsgStorage() {
        try {
            Class<?> e01d9 = XposedHelpers.findClass("e01.d9", sClassLoader);
            Object service = XposedHelpers.callStaticMethod(e01d9, "b");
            if (service != null) sMsgStorage = XposedHelpers.callMethod(service, "u");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static Object getMsgStorage() {
        if (sMsgStorage == null) initMsgStorage();
        return sMsgStorage;
    }

    // ===== 微信线程池调度器 (rv5.t0.d) =====

    private static Object sWxThreadPool;
    private static Object sSchedFuture;

    private static void startWxScheduler() {
        try {
            Class<?> t0 = XposedHelpers.findClass("rv5.t0", sClassLoader);
            sWxThreadPool = XposedHelpers.getStaticObjectField(t0, "d");
            if (sWxThreadPool == null) {
                log("rv5.t0.d=null, 回退到HandlerThread");
                fallbackHandlerThread();
                return;
            }
            Runnable r = new Runnable() {
                @Override public void run() {
                    if (sStopFlag.get()) return;
                    checkScheduledTasks();
                }
            };
            sSchedFuture = XposedHelpers.callMethod(sWxThreadPool, "d", r, 1000L, 15000L);
            log("微信线程池调度器启动(rv5.t0.d), 轮询间隔15s");
        } catch (Throwable t) {
            log("微信调度器启动失败: " + t.getMessage() + " -> HandlerThread");
            fallbackHandlerThread();
        }
    }

    private static void checkScheduledTasks() {
        if (sRunning.get()) return;
        long now = System.currentTimeMillis();
        for (Task task : sTaskQueue) {
            if (task.enabled && task.triggerTime > 0 && task.triggerTime <= now) {
                executeTask(task.id);
                break;
            }
        }
    }

    private static void fallbackHandlerThread() {
        if (sHandlerThread != null) return;
        sHandlerThread = new HandlerThread("leshao-scheduler");
        sHandlerThread.start();
        sHandler = new Handler(sHandlerThread.getLooper());
        Runnable r = new Runnable() {
            @Override public void run() {
                if (sStopFlag.get()) return;
                checkScheduledTasks();
                if (sHandler != null) sHandler.postDelayed(this, 15000);
            }
        };
        sHandler.postDelayed(r, 1000);
        log("HandlerThread调度器启动, 轮询间隔15s");
    }

    // ===== 兼容旧接口 (已弃用AlarmManager, 轮询器自动接管) =====

    @Deprecated
    public static void scheduleTask(Task task) { /* 轮询器接管 */ }

    @Deprecated
    public static void cancelSchedule(Task task) { /* 轮询器接管 */ }

    // ===== 任务管理 =====

    public static List<Task> getAllTasks() { return new ArrayList<>(sTaskQueue); }
    public static Task getTask(String id) { for (Task t : sTaskQueue) if (t.id.equals(id)) return t; return null; }

    public static void addTask(Task task) {
        if (task == null) return;
        sTaskQueue.add(task);
        saveTasks();
        log("任务已添加: " + task.id + " 触发=" + sdfDateTime.format(new Date(task.triggerTime)) + " 类型=" + task.msgType + " 目标数=" + (task.targetGroups != null ? task.targetGroups.size() : 0));
    }

    public static void removeTask(String id) {
        Task r = null;
        Iterator<Task> it = sTaskQueue.iterator();
        while (it.hasNext()) { Task t = it.next(); if (t.id.equals(id)) { r = t; it.remove(); break; } }
        if (r != null) { saveTasks(); log("任务已删除: " + id); }
    }

    public static void updateTask(Task u) {
        for (int i = 0; i < sTaskQueue.size(); i++) {
            if (sTaskQueue.get(i).id.equals(u.id)) {
                sTaskQueue.set(i, u);
                saveTasks(); return;
            }
        }
    }

    public static void enableTask(String id, boolean en) {
        Task t = getTask(id);
        if (t != null) { t.enabled = en; saveTasks(); }
    }

    private static void loadTasks() {
        sTaskQueue.clear();
        SharedPreferences sp = sAppContext.getSharedPreferences("schedule_tasks", Context.MODE_PRIVATE);
        String json = sp.getString("task_list", "");
        if (json == null || json.isEmpty()) return;
        try {
            long now = System.currentTimeMillis();
            int lostCount = 0;
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                Task t = Task.fromJson(a.getJSONObject(i));
                if (t != null) {
                    sTaskQueue.add(t);
                    if (t.enabled && t.triggerTime <= now && t.triggerTime > now - 7200000L) {
                        log("补偿执行过期任务: id=" + t.id + " 原定=" + sdfDateTime.format(new Date(t.triggerTime)));
                        executeTask(t.id);
                        lostCount++;
                    } else if (t.enabled && t.triggerTime <= now) {
                        log("任务已过期超2小时, 标记无效: id=" + t.id);
                        t.enabled = false;
                    }
                }
            }
            if (lostCount > 0) { saveTasks(); log("共补偿执行 " + lostCount + " 个过期任务"); }
        } catch (Throwable t) { log("loadTasks异常: " + t.getMessage()); }
    }

    private static void saveTasks() {
        try {
            JSONArray a = new JSONArray();
            for (Task t : sTaskQueue) a.put(t.toJson());
            sAppContext.getSharedPreferences("schedule_tasks", Context.MODE_PRIVATE)
                .edit().putString("task_list", a.toString()).commit();
        } catch (Throwable t) { log("saveTasks失败: " + t.getMessage()); }
    }

    // ===== 草稿箱 =====

    public static List<Task> getDrafts() { return new ArrayList<>(sDraftBox); }
    public static void saveDraft(Task t) { sDraftBox.add(t); saveDrafts(); }
    public static void removeDraft(String id) {
        Iterator<Task> it = sDraftBox.iterator();
        while (it.hasNext()) { if (it.next().id.equals(id)) { it.remove(); break; } }
        saveDrafts();
    }

    private static void loadDrafts() {
        sDraftBox.clear();
        String json = sAppContext.getSharedPreferences("schedule_drafts", Context.MODE_PRIVATE).getString("draft_list", "");
        if (json == null || json.isEmpty()) return;
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { Task t = Task.fromJson(a.getJSONObject(i)); if (t != null) sDraftBox.add(t); } } catch (Throwable ignored) {}
    }
    private static void saveDrafts() {
        try { JSONArray a = new JSONArray(); for (Task t : sDraftBox) a.put(t.toJson()); sAppContext.getSharedPreferences("schedule_drafts", Context.MODE_PRIVATE).edit().putString("draft_list", a.toString()).commit(); } catch (Throwable ignored) {}
    }

    // ===== 群管理 =====

    static List<String> getAllGroups() {
        List<String> list = new ArrayList<>();
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            long uin = getCurrentUin();
            if (uin == 0) return list;
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return list;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT username FROM rcontact WHERE username LIKE '%@chatroom' AND type=0 ORDER BY nickname", null);
            while (c.moveToNext()) list.add(c.getString(0));
        } catch (Throwable t) { log("获取群列表失败: " + t.getMessage()); }
        finally {
            if (c != null && !c.isClosed()) try { c.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
        }
        return list;
    }

    public static void setExcludeGroups(Set<String> groups) { sExcludeGroups.clear(); sExcludeGroups.addAll(groups); saveExcludeGroups(); }
    public static Set<String> getExcludeGroups() { return new HashSet<>(sExcludeGroups); }
    private static void loadExcludeGroups() {
        String s = sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).getString("exclude_groups", "");
        if (s != null && !s.isEmpty()) sExcludeGroups.addAll(Arrays.asList(s.split(",")));
    }
    private static void saveExcludeGroups() {
        sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).edit().putString("exclude_groups", TextUtils.join(",", sExcludeGroups)).commit();
    }

    static List<String> getGroupsByTag(String tag) {
        List<String> allGroups = getAllGroups();
        if (tag == null || tag.isEmpty()) return allGroups;
        return allGroups;
    }

    // ===== 安全检测 =====

    private static boolean canSend() {
        if (!sEnabled) return false;
        if (sStopFlag.get()) return false;
        if (sMsgStorage == null) { initMsgStorage(); if (sMsgStorage == null) return false; }

        if (sNightSilent) { int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); if (h >= 2 && h < 6) return false; }

        if (!checkTimeWindow(sTimeWindow)) return false;

        resetCounters();
        if (sDailyCount.get() >= sDailyMaxSend) return false;
        if (sWeeklyCount.get() >= sWeeklyMaxSend) return false;

        if (sWifiOnly) {
            ConnectivityManager cm = (ConnectivityManager) sAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || ni.getType() != ConnectivityManager.TYPE_WIFI) return false;
        }

        if (sChargingOnly) {
            Intent bi = sAppContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) { int s = bi.getIntExtra("status", -1); if (s != BatteryManager.BATTERY_STATUS_CHARGING && s != BatteryManager.BATTERY_STATUS_FULL) return false; }
        }

        if (sLockScreenPause) {
            PowerManager pm = (PowerManager) sAppContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
        }

        return true;
    }

    private static int getEffectiveInterval() {
        if (checkTimeWindow(sNightSlowWindow)) return sNightSlowIntervalSec;
        return sMinIntervalSec + (int)(Math.random() * (sMaxIntervalSec - sMinIntervalSec));
    }

    private static boolean checkTimeWindow(String window) {
        if (window == null || window.isEmpty()) return true;
        try {
            String[] parts = window.split("-");
            if (parts.length != 2) return true;
            int start = Integer.parseInt(parts[0].split(":")[0]) * 60 + Integer.parseInt(parts[0].split(":")[1]);
            int end = Integer.parseInt(parts[1].split(":")[0]) * 60 + Integer.parseInt(parts[1].split(":")[1]);
            Calendar cal = Calendar.getInstance();
            int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
            if (start <= end) return now >= start && now <= end;
            else return now >= start || now <= end;
        } catch (Throwable t) { return true; }
    }

    private static void resetCounters() {
        String today = sdfDate.format(new Date());
        if (!today.equals(sLastDailyDate)) { sLastDailyDate = today; sDailyCount.set(0); }
        int woy = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR);
        if (woy != sLastWeekOfYear) { sLastWeekOfYear = woy; sWeeklyCount.set(0); }
    }

    // ===== 任务执行引擎 =====

    private static void executeTask(final String taskId) {
        if (sRunning.get()) { log("已有任务执行中, 跳过: " + taskId); return; }
        sRunning.set(true);
        sScheduleThread = new Thread(new Runnable() {
            @Override public void run() {
                PowerManager.WakeLock wl = null;
                try {
                    PowerManager pm = (PowerManager) sAppContext.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LeShao:Schedule-" + taskId);
                        if (wl != null) wl.acquire(600000L);
                    }
                    Task task = getTask(taskId);
                    if (task == null || !task.enabled) { log("任务无效或已停用: " + taskId); return; }
                    if (!canSend()) {
                        log("发送条件不满足: " + taskId);
                        task.triggerTime = System.currentTimeMillis() + 15000;
                        return;
                    }

                    task.lastExecTime = System.currentTimeMillis();
                    List<String> groups;
                    if (task.sendAllGroups) groups = getAllGroups();
                    else groups = new ArrayList<>(task.targetGroups);

                    if (task.groupTags != null && !task.groupTags.isEmpty()) {
                        for (String tag : task.groupTags) {
                            List<String> tagged = getGroupsByTag(tag);
                            for (String g : tagged) if (!groups.contains(g)) groups.add(g);
                        }
                    }

                    groups.removeAll(sExcludeGroups);
                    if (groups.isEmpty()) { return; }

                    if (task.randomOrder) Collections.shuffle(groups);

                    log("开始执行: taskId=" + taskId + " targets=" + groups.size());

                    int success = 0, fail = 0;
                    for (int i = 0; i < groups.size(); i++) {
                        if (sStopFlag.get()) { log("紧急停止"); break; }
                        if (Thread.currentThread().isInterrupted()) { log("线程中断"); break; }
                        if (!canSend()) { log("发送条件不满足,暂停"); break; }

                        String target = groups.get(i);

                        AtomicLong cooldown = sGroupCooldowns.get(target);
                        long now = System.currentTimeMillis();
                        if (cooldown != null && now - cooldown.get() < 60000) {
                            if (!sleepInterruptibly(3000)) break;
                        }

                        String content = task.content;
                        if (task.contentPool != null && !task.contentPool.isEmpty()) {
                            int idx = sContentRotationIndex.getOrDefault(task.id, 0);
                            content = task.contentPool.get(idx % task.contentPool.size());
                            sContentRotationIndex.put(task.id, idx + 1);
                        }

                        if (task.useTemplate && task.templateKey != null) {
                            String tpl = TEMPLATE_LIBRARY.get(task.templateKey);
                            if (tpl != null) content = tpl;
                        }
                        content = applyVariables(content, target, task);

                        if (task.randomEmoji) {
                            String[] emojis = {"[微笑]","[点赞]","[爱心]","[火苗]","[星星]","[强壮]","[闪耀]","[庆祝]","[花开]","[三叶草]","[100]","[哈哈]","[发光]"};
                            content = content + " " + emojis[(int)(Math.random() * emojis.length)];
                        }

                        if (task.sensitiveCheck && containsSensitiveWord(content)) {
                            log("内容含敏感词, 跳过: " + target);
                            fail++;
                            continue;
                        }

                        Task sendTask = new Task();
                        sendTask.msgType = task.msgType;
                        sendTask.content = content;
                        sendTask.filePath = task.filePath;

                        boolean sent = sendMessage(target, sendTask);
                        if (sent) {
                            success++;
                            sDailyCount.incrementAndGet();
                            sWeeklyCount.incrementAndGet();
                            task.totalSendCount++;
                            sGroupCooldowns.put(target, new AtomicLong(now));
                        } else { fail++; }

                        if (i < groups.size() - 1) {
                            if (!sleepInterruptibly(getEffectiveInterval() * 1000L)) break;
                        }
                    }

                    task.failCount = fail;
                    saveTasks();
                    log("任务完成: " + taskId + " 成功=" + success + " 失败=" + fail);

                    reportToFilehelper(task, success, fail);

                    if (task.repeatInterval > 0 && task.enabled) {
                        task.triggerTime = System.currentTimeMillis() + task.repeatInterval;
                        saveTasks();
                    } else if (fail > 0 && task.failCount < sRetryTimes && task.enabled) {
                        task.triggerTime = System.currentTimeMillis() + sRetryIntervalSec * 1000L;
                        saveTasks();
                    } else if (task.triggerTime < System.currentTimeMillis()) {
                        task.enabled = false; saveTasks();
                    }
                } catch (Throwable t) { log("执行异常: " + t.getClass().getSimpleName() + " - " + t.getMessage()); }
                finally {
                    if (wl != null) { try { wl.release(); } catch (Throwable ignored) {} }
                    sRunning.set(false); sScheduleThread = null; }
            }
        }, "Schedule-" + taskId);
        sScheduleThread.start();
    }

    private static boolean sleepInterruptibly(long ms) {
        try {
            Thread.sleep(ms);
            return !sStopFlag.get();
        } catch (InterruptedException e) {
            return false;
        }
    }

    // ===== 变量替换 =====

    private static String applyVariables(String template, String groupWxid, Task task) {
        if (template == null) return "";
        String result = template;
        Calendar cal = Calendar.getInstance();
        if (task.varDate || template.contains("{date}"))
            result = result.replace("{date}", new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));
        if (task.varTime || template.contains("{time}"))
            result = result.replace("{time}", new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        result = result.replace("{weekday}", new String[]{"日","一","二","三","四","五","六"}[cal.get(Calendar.DAY_OF_WEEK)-1]);
        if (template.contains("{group_name}") || task.varGroupName) {
            String gname = getGroupNickname(groupWxid);
            result = result.replace("{group_name}", gname != null ? gname : groupWxid);
        }
        if (template.contains("{member_count}") || task.varMemberCount) {
            int cnt = getGroupMemberCount(groupWxid);
            result = result.replace("{member_count}", String.valueOf(cnt));
        }
        if (template.contains("{yesterday_msg}")) {
            result = result.replace("{yesterday_msg}", String.valueOf(getYesterdayMsgCount(groupWxid)));
        }
        if (template.contains("{nickname}") && task.varNickname) {
            result = result.replace("{nickname}", task.content != null ? task.content : "新成员");
        }
        result = result.replace("{days}", "30");
        result = result.replace("{content}", task.content != null ? task.content : "");
        return result;
    }

    // ===== 群信息查询 (SQL) =====

    private static String getGroupNickname(String wxid) { try { return queryRcontact(wxid, "nickname"); } catch (Throwable t) { return wxid; } }
    private static int getGroupMemberCount(String wxid) {
        try { return Integer.parseInt(queryRcontact(wxid, "memberCount")); } catch (Throwable t) { return 0; }
    }
    private static String queryRcontact(String wxid, String column) {
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            long uin = getCurrentUin();
            if (uin == 0) return "";
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return "";
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT " + column + " FROM rcontact WHERE username=?", new String[]{wxid});
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (Throwable t) { return ""; }
        finally {
            if (c != null && !c.isClosed()) try { c.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private static int getYesterdayMsgCount(String groupWxid) {
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            long startOfYesterday = System.currentTimeMillis() - 86400000;
            startOfYesterday = startOfYesterday - (startOfYesterday % 86400000);
            long endOfYesterday = startOfYesterday + 86400000;
            long uin = getCurrentUin();
            if (uin == 0) return 0;
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return 0;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT COUNT(*) FROM message WHERE talker=? AND createTime>=? AND createTime<?",
                new String[]{groupWxid, String.valueOf(startOfYesterday), String.valueOf(endOfYesterday)});
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable t) { return 0; }
        finally {
            if (c != null && !c.isClosed()) try { c.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    // ===== 敏感词检测 =====

    private static boolean containsSensitiveWord(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String word : SENSITIVE_WORDS) if (lower.contains(word)) return true;
        return false;
    }

    // ===== 核心发送引擎 =====

    static boolean sendMessage(String talker, Task task) {
        try {
            Object ms = getMsgStorage();
            if (ms == null) return false;
            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", sClassLoader);
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            long now = System.currentTimeMillis();

            switch (task.msgType) {
                case 1: // 文本
                    XposedHelpers.callMethod(msg, "A1", 1);
                    XposedHelpers.callMethod(msg, "X0", task.content);
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    try {
                        log("DIAG: 文本 msg type=" + XposedHelpers.callMethod(msg, "B0")
                            + " content=" + XposedHelpers.callMethod(msg, "X1")
                            + " isSend=" + XposedHelpers.callMethod(msg, "G1")
                            + " talker=" + XposedHelpers.callMethod(msg, "L"));
                    } catch (Throwable ignored) {}
                    break;

                case 3: // 图片
                    XposedHelpers.callMethod(msg, "A1", 3);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    copyMediaToWxDir(task.filePath, msg);
                    break;

                case 34: // 语音
                    return sendVoiceMessage(talker, task);

                case 43: // 视频
                    XposedHelpers.callMethod(msg, "A1", 43);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    copyMediaToWxDir(task.filePath, msg);
                    break;

                case 47: // 表情
                    XposedHelpers.callMethod(msg, "A1", 47);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    break;

                case 49: // AppMsg
                    XposedHelpers.callMethod(msg, "A1", 49);
                    XposedHelpers.callMethod(msg, "d1", task.content);
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    if (task.filePath != null && new File(task.filePath).exists()) {
                        XposedHelpers.callMethod(msg, "j1", task.filePath);
                    }
                    break;

                default: return false;
            }

            // 主路径: H9写DB拿到msgId, 然后triggerSend触发联网发送
            long msgId = (Long) XposedHelpers.callMethod(ms, "H9", msg);
            if (msgId > 0) {
                log("sendMessage: H9 OK msgId=" + msgId + " capturedA21q=" + (sCapturedA21q != null));
                triggerSend(msgId, msg, talker);
                return true;
            }
            return false;
        } catch (Throwable t) { return false; }
    }

    // ===== 触发消息真正发送(联网) =====

    private static void triggerSend(long msgId, Object e9msg, String talker) {
        try {
            // 1. 构建 a65.en4 (MsgCommand) — 单条消息体
            Class<?> en4Cls = XposedHelpers.findClass("a65.en4", sClassLoader);
            Object en4 = XposedHelpers.newInstance(en4Cls);

            // 1a. 收件人 ew5
            Class<?> ew5Cls = XposedHelpers.findClass("a65.ew5", sClassLoader);
            Object ew5 = XposedHelpers.newInstance(ew5Cls);
            try { XposedHelpers.setObjectField(ew5, "d", talker); } catch (Throwable ignored) {}
            try { XposedHelpers.setBooleanField(ew5, "e", true); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(en4, "d", ew5); } catch (Throwable ignored) {}

            // 1b. 时间戳 + 类型 + 内容
            try { XposedHelpers.setIntField(en4, "g", (int)(System.currentTimeMillis() / 1000)); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(en4, "f", 1); } catch (Throwable ignored) {}
            try { XposedHelpers.setObjectField(en4, "e", XposedHelpers.callMethod(e9msg, "X1")); } catch (Throwable ignored) {}
            // newmsgid
            try {
                Class<?> y1Cls = XposedHelpers.findClass("y1", sClassLoader);
                long createTime = (Long) XposedHelpers.callMethod(e9msg, "getCreateTime");
                Object y1Val = XposedHelpers.callStaticMethod(y1Cls, "a", talker, createTime);
                int hash = (Integer) XposedHelpers.callMethod(y1Val, "hashCode");
                XposedHelpers.setIntField(en4, "h", hash);
            } catch (Throwable ignored) {}

            // 2. 构建 a65.f16 (NewSendMsgRequest)
            Class<?> f16Cls = XposedHelpers.findClass("a65.f16", sClassLoader);
            Object f16 = XposedHelpers.newInstance(f16Cls);

            // f16.e = LinkedList<en4> — 添加消息
            java.util.List<Object> f16e = (java.util.List<Object>) XposedHelpers.getObjectField(f16, "e");
            if (f16e == null) {
                f16e = new java.util.LinkedList<>();
                try { XposedHelpers.setObjectField(f16, "e", f16e); } catch (Throwable ignored) {}
            }
            f16e.add(en4);
            try { XposedHelpers.setIntField(f16, "d", f16e.size()); } catch (Throwable ignored) {}

            // 3. f16.b() → i (CGI task)
            Object i = XposedHelpers.callMethod(f16, "b");

            // 4. 构建 o (CGI request wrapper)
            Class<?> oCls = XposedHelpers.findClass("com.tencent.mm.modelbase.o", sClassLoader);
            Object o = XposedHelpers.newInstance(oCls);
            // o.c = CGI URL, o.d = CmdID, o.e = RespID, o.f = FuncID
            try { XposedHelpers.setObjectField(o, "c", "/cgi-bin/micromsg-bin/newsendmsg"); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(o, "d", 522); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(o, "e", 237); } catch (Throwable ignored) {}
            try { XposedHelpers.setIntField(o, "f", 1000000237); } catch (Throwable ignored) {}

            // i.p(o) — 设置请求包装
            XposedHelpers.callMethod(i, "p", o);

            // 5. 调用 sm0.h.b(i, null) 联网发送
            Class<?> sm0h = XposedHelpers.findClass("sm0.h", sClassLoader);
            Object result = XposedHelpers.callStaticMethod(sm0h, "b", i, null);
            log("triggerSend: sm0.h.b() OK msgId=" + msgId + " result=" + result);
        } catch (Throwable t) {
            log("triggerSend: a21.b0.vj fail: " + t.getMessage());
            log("triggerSend: 所有方法均失败 msgId=" + msgId);
        }
    }

    // 构建一个空的 Kotlin Continuation<Object>
    private static Object buildEmptyContinuation() {
        try {
            // 方式1: kotlin.coroutines.EmptyCoroutineContext.INSTANCE
            Class<?> emptyCC = XposedHelpers.findClass("kotlin.coroutines.EmptyCoroutineContext", null);
            Object context = XposedHelpers.getStaticObjectField(emptyCC, "INSTANCE");
            // 方式2: 创建 kotlin.coroutines.Continuation 匿名实现
            Class<?> contIFace = XposedHelpers.findClass("kotlin.coroutines.Continuation", null);
            Class<?> safeCont = XposedHelpers.findClass("kotlin.coroutines.SafeContinuation", null);
            if (safeCont != null) {
                // SafeContinuation(result) with RESULT_ATOMIC
                return XposedHelpers.newInstance(safeCont,
                    XposedHelpers.getStaticObjectField(safeCont, "RESUMED"));
            }
        } catch (Throwable t) {
            log("buildEmptyContinuation fail: " + t.getMessage());
        }
        return null;
    }

    // ===== 语音发送 =====

    private static boolean sendVoiceMessage(String talker, Task task) {
        try {
            if (!validateMediaFile(task.filePath)) return false;
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);
            String newName = (String) XposedHelpers.callStaticMethod(y21x0, "g", talker, "amr_");
            if (newName == null) return false;
            Object u0Service = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("pa5.n0", sClassLoader), "c",
                XposedHelpers.findClass("qh3.u0", sClassLoader));
            Object y_j = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("lin5.y", sClassLoader), "j");
            String dstFull = (String) XposedHelpers.callMethod(u0Service,
                "Nj", y_j, newName, false, true);
            if (dstFull == null) return false;
            new File(dstFull).getParentFile().mkdirs();
            copyFile(task.filePath, dstFull);
            int duration = 5000;
            try { duration = Integer.parseInt(task.content); } catch (Throwable ignored) {}
            boolean ok = (Boolean) XposedHelpers.callStaticMethod(y21x0, "t", newName, duration, 0, null);
            if (ok) XposedHelpers.callMethod(
                XposedHelpers.callStaticMethod(XposedHelpers.findClass("y21.p0", sClassLoader), "kj"), "e");
            return ok;
        } catch (Throwable t) { return false; }
    }

    // ===== 媒体复制 =====

    private static boolean validateMediaFile(String path) {
        if (path == null) return false;
        File f = new File(path);
        return f.exists() && f.isFile() && f.length() > 0;
    }

    private static void copyMediaToWxDir(String srcPath, Object msg) {
        try {
            if (!validateMediaFile(srcPath)) return;
            Object u0Service = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("pa5.n0", sClassLoader), "c",
                XposedHelpers.findClass("qh3.u0", sClassLoader));
            Object y_j = XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("lin5.y", sClassLoader), "j");
            String ext = srcPath.substring(srcPath.lastIndexOf('.'));
            String dstPath = (String) XposedHelpers.callMethod(u0Service,
                "Nj", y_j, System.currentTimeMillis() + ext, false, true);
            if (dstPath == null) return;
            new File(dstPath).getParentFile().mkdirs();
            copyFile(srcPath, dstPath);
            XposedHelpers.callMethod(msg, "j1", dstPath);
        } catch (Throwable ignored) {}
    }

    private static void copyFile(String src, String dst) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(new File(src));
            fos = new FileOutputStream(new File(dst));
            byte[] buf = new byte[16384]; int n;
            while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
        } catch (Throwable ignored) {}
        finally {
            if (fis != null) try { fis.close(); } catch (Throwable ignored) {}
            if (fos != null) try { fos.close(); } catch (Throwable ignored) {}
        }
    }

    // ===== 构造 AppMsg XML =====

    public static String buildLinkCardXml(String url, String title, String desc, String thumbPath) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(title) + "</title><des>" + esc(desc) + "</des>"
            + "<type>5</type><url>" + esc(url) + "</url><thumburl>" + esc(thumbPath) + "</thumburl></appmsg></msg>";
    }

    public static String buildMiniProgramCardXml(String appid, String pagepath, String title, String thumbPath) {
        return "<msg><appmsg appid=\"" + esc(appid) + "\" sdkver=\"0\"><title>" + esc(title) + "</title>"
            + "<type>33</type><url>" + esc(pagepath) + "</url><thumburl>" + esc(thumbPath) + "</thumburl></appmsg></msg>";
    }

    public static String buildBusinessCardXml(String wxid, String displayName) {
        return "<msg username=\"" + esc(wxid) + "\" nickname=\"" + esc(displayName) + "\" fullpy=\"\" shortpy=\"\" imagestatus=\"1\" scene=\"17\" province=\"\" city=\"\" sign=\"\" sex=\"0\" certflag=\"0\" certinfo=\"\" brandIconUrl=\"\" brandHomeUrl=\"\" brandSubscriptConfigUrl=\"\" brandFlags=\"\" regionCode=\"\"/>";
    }

    public static String buildArticleCardXml(String mpUrl, String title, String desc, String coverUrl) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(title) + "</title><des>" + esc(desc) + "</des>"
            + "<type>49</type><url>" + esc(mpUrl) + "</url><thumburl>" + esc(coverUrl) + "</thumburl></appmsg></msg>";
    }

    public static String buildFileMsgXml(String fileName, long fileSize) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(fileName) + "</title><des>" + fileSize + "字节</des>"
            + "<type>6</type></appmsg></msg>";
    }

    public static String buildAtAllContent(String content) {
        return "@所有人\u0000" + content;
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    // ===== 即时群发 =====

    public static int sendNow(Task task) {
        if (!canSend()) return 0;
        if (task.targetGroups == null || task.targetGroups.isEmpty()) return 0;
        int s = 0;
        for (String t : task.targetGroups) { if (sendMessage(t, task)) s++; sleepInterruptibly(1000); }
        log("即时发送完成: " + s + "/" + task.targetGroups.size());
        return s;
    }

    // ===== 紧急停止 =====

    public static void emergencyStop() {
        sStopFlag.set(true);
        sRunning.set(false);
        if (sScheduleThread != null) sScheduleThread.interrupt();
        log("紧急停止");
    }
    public static void resetStop() { sStopFlag.set(false); log("重置停止标志"); }

    // ===== filehelper交互 =====

    private static void reportToFilehelper(Task task, int success, int fail) {
        String report = "任务执行报告\n"
                + "任务ID: " + task.id + "\n"
                + "消息类型: " + (task.msgType == 1 ? "文本" : task.msgType == 34 ? "语音" : "类型" + task.msgType) + "\n"
                + "成功: " + success + " 群\n"
                + "失败: " + fail + " 群\n"
                + "时间: " + sdfLog.format(new Date()) + "\n"
                + "今日累计: " + sDailyCount.get() + "/" + sDailyMaxSend;
        sendToFilehelper(report);
    }

    public static void previewContent(Task task) {
        String preview = "内容预览\n"
                + "类型: " + (task.msgType == 1 ? "文本" : "类型" + task.msgType) + "\n"
                + "--- 内容 ---\n" + task.content + "\n"
                + "--- 目标 ---\n" + (task.sendAllGroups ? "全部群(" + getAllGroups().size() + "个)" : String.valueOf(task.targetGroups.size()) + "个群");
        sendToFilehelper(preview);
    }

    private static void sendToFilehelper(String text) {
        try {
            Object ms = getMsgStorage(); if (ms == null) return;
            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", sClassLoader);
            Object msg = XposedHelpers.newInstance(e9Class, "filehelper");
            XposedHelpers.callMethod(msg, "A1", 1);
            XposedHelpers.callMethod(msg, "X0", text);
            XposedHelpers.callMethod(msg, "e1", System.currentTimeMillis());
            XposedHelpers.callMethod(msg, "k1", 1);
            XposedHelpers.callMethod(ms, "H9", msg);
        } catch (Throwable ignored) {}
    }

    public static void handleFilehelperCommand(String content) {
        if (content == null) return;
        if (content.startsWith("##STOP")) { emergencyStop(); sendToFilehelper("已停止"); }
        else if (content.startsWith("##STATUS")) {
            sendToFilehelper("状态:\n启用:" + sEnabled + " 运行:" + sRunning.get() + " 已发:" + sDailyCount.get() + "/" + sDailyMaxSend + "\n任务:" + sTaskQueue.size() + " 草稿:" + sDraftBox.size() + " f9:" + (sMsgStorage != null ? "OK" : "NULL"));
        }
        else if (content.startsWith("##TASKS")) {
            StringBuilder sb = new StringBuilder("任务:\n");
            for (Task t : sTaskQueue) sb.append("  ").append(t.enabled ? "启用" : "暂停").append(" [").append(t.msgType == 1 ? "文本" : "类型" + t.msgType).append("] ").append(t.targetGroups.size()).append("群\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##RESET")) { resetStop(); sendToFilehelper("已重置"); }
        else if (content.startsWith("##TEMPLATES")) {
            StringBuilder sb = new StringBuilder("模板:\n");
            for (Map.Entry<String, String> e : TEMPLATE_LIBRARY.entrySet()) sb.append("  [").append(e.getKey()).append("]\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##SCENES")) {
            StringBuilder sb = new StringBuilder("场景模板:\n");
            for (SceneTemplate st : SCENE_TEMPLATES) sb.append("  ").append(st.name).append(" @").append(st.hour).append(":00\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##GROUPS")) {
            List<String> gs = getAllGroups();
            sendToFilehelper("全部群(" + gs.size() + "个):\n" + TextUtils.join("\n", gs.subList(0, Math.min(50, gs.size()))));
        }
    }

    // ===== filehelper监听 =====

    public static void hookFilehelperMonitor(ClassLoader cl) {
        try {
            Class<?> f9Class = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            XposedBridge.hookAllMethods(f9Class, "Ra", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 2) return;
                        Object msgInfo = param.args[1]; if (msgInfo == null) return;
                        String talker = (String) XposedHelpers.getObjectField(msgInfo, "field_talker");
                        if (!"filehelper".equals(talker)) return;
                        int type = (Integer) XposedHelpers.callMethod(msgInfo, "getType"); if (type != 1) return;
                        boolean isSend = (Boolean) XposedHelpers.callMethod(msgInfo, "G1"); if (isSend) return;
                        String ct = (String) XposedHelpers.getObjectField(msgInfo, "field_content");
                        if (ct != null && ct.startsWith("##")) handleFilehelperCommand(ct);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) { log("监听失败: " + t.getMessage()); }
    }

    // ===== 统计 =====

    public static int getDailyCount() { resetCounters(); return sDailyCount.get(); }
    public static int getWeeklyCount() { resetCounters(); return sWeeklyCount.get(); }
    public static int getTaskCount() { return sTaskQueue.size(); }
    public static int getDraftCount() { return sDraftBox.size(); }
    public static int getGroupCount() { return getAllGroups().size(); }
    public static boolean isRunning() { return sRunning.get() && !sStopFlag.get(); }

    // ===== Setter API =====

    public static void setEnabled(boolean v) { sEnabled = v; saveSettingBool("enabled", v); }
    public static void setMinInterval(int sec) { sMinIntervalSec = sec; saveSettingInt("min_interval", sec); }
    public static void setMaxInterval(int sec) { sMaxIntervalSec = sec; saveSettingInt("max_interval", sec); }
    public static void setDailyMax(int n) { sDailyMaxSend = n; saveSettingInt("daily_max", n); }
    public static void setWeeklyMax(int n) { sWeeklyMaxSend = n; saveSettingInt("weekly_max", n); }
    public static void setWifiOnly(boolean v) { sWifiOnly = v; saveSettingBool("wifi_only", v); }
    public static void setChargingOnly(boolean v) { sChargingOnly = v; saveSettingBool("charging_only", v); }
    public static void setNightSilent(boolean v) { sNightSilent = v; saveSettingBool("night_silent", v); }
    public static void setLockScreenPause(boolean v) { sLockScreenPause = v; saveSettingBool("lock_screen_pause", v); }
    public static void setTimeWindow(String w) { sTimeWindow = w; saveSettingStr("time_window", w); }
    public static boolean isEnabled() { return sEnabled; }

    private static void saveSettingBool(String key, boolean v) {
        if (sAppContext == null) return;
        sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).edit().putBoolean(key, v).commit();
    }
    private static void saveSettingInt(String key, int v) {
        if (sAppContext == null) return;
        sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).edit().putInt(key, v).commit();
    }
    private static void saveSettingStr(String key, String v) {
        if (sAppContext == null) return;
        sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).edit().putString(key, v).commit();
    }

    // ===== 工具 =====

    private static long getCurrentUin() {
        try {
            long uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);
            return uin;
        } catch (Throwable t) { return 0; }
    }

    private static void log(String msg) {
        LogWriter.log(TAG, msg);
    }

    private static String md5(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }

    // ===== 好友列表 =====

    public static List<String> getAllFriends() {
        List<String> list = new ArrayList<>();
        android.database.sqlite.SQLiteDatabase db = null;
        android.database.Cursor c = null;
        try {
            long uin = getCurrentUin();
            if (uin == 0) return list;
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return list;
            db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT username FROM rcontact WHERE type=0 AND username NOT LIKE '%@chatroom' AND username NOT LIKE 'gh_%' AND username NOT LIKE '%@lbsroom' AND username NOT LIKE '%@openim' AND verifyFlag=0 ORDER BY nickname", null);
            while (c.moveToNext()) list.add(c.getString(0));
        } catch (Throwable t) { log("获取好友列表失败: " + t.getMessage()); }
        finally {
            if (c != null && !c.isClosed()) try { c.close(); } catch (Throwable ignored) {}
            if (db != null && db.isOpen()) try { db.close(); } catch (Throwable ignored) {}
        }
        return list;
    }

    // ===== 任务模板管理 =====

    public static class TemplateData {
        public String id;
        public String name;
        public String content;
        public int msgType;
        public String filePath;
        public String targetWxids;
        public String excludeWxids;
        public int channel; // 0=好友 1=群聊 2=朋友圈
        public int minIntervalSec;
        public int maxIntervalSec;
        public int batchSize;
        public int batchIntervalSec;
        public int maxSendCount;
        public int retryTimes;
        public int retryIntervalSec;
        public String repeatMode; // once/daily/weekly/interval_N
        public int hour;
        public int minute;
        public long createTime;
        public boolean randomInterval;
        public String momentsVisible;
        public String momentsLocation;

        public TemplateData() {
            this.id = String.valueOf(System.currentTimeMillis());
            this.msgType = 1;
            this.channel = 1;
            this.minIntervalSec = 5;
            this.maxIntervalSec = 30;
            this.batchSize = 10;
            this.batchIntervalSec = 60;
            this.maxSendCount = 200;
            this.retryTimes = 3;
            this.retryIntervalSec = 60;
            this.repeatMode = "once";
            this.createTime = System.currentTimeMillis();
        }

        public JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("id", id);
                j.put("name", nvl(name));
                j.put("content", nvl(content));
                j.put("msgType", msgType);
                j.put("filePath", nvl(filePath));
                j.put("targetWxids", nvl(targetWxids));
                j.put("excludeWxids", nvl(excludeWxids));
                j.put("channel", channel);
                j.put("minIntervalSec", minIntervalSec);
                j.put("maxIntervalSec", maxIntervalSec);
                j.put("batchSize", batchSize);
                j.put("batchIntervalSec", batchIntervalSec);
                j.put("maxSendCount", maxSendCount);
                j.put("retryTimes", retryTimes);
                j.put("retryIntervalSec", retryIntervalSec);
                j.put("repeatMode", nvl(repeatMode));
                j.put("hour", hour);
                j.put("minute", minute);
                j.put("createTime", createTime);
                j.put("randomInterval", randomInterval);
                j.put("momentsVisible", nvl(momentsVisible));
                j.put("momentsLocation", nvl(momentsLocation));
                return j;
            } catch (Throwable t) { return new JSONObject(); }
        }

        public static TemplateData fromJson(JSONObject j) {
            try {
                TemplateData t = new TemplateData();
                t.id = j.optString("id", t.id);
                t.name = j.optString("name", "");
                t.content = j.optString("content", "");
                t.msgType = j.optInt("msgType", 1);
                t.filePath = j.optString("filePath", "");
                t.targetWxids = j.optString("targetWxids", "");
                t.excludeWxids = j.optString("excludeWxids", "");
                t.channel = j.optInt("channel", 1);
                t.minIntervalSec = j.optInt("minIntervalSec", 5);
                t.maxIntervalSec = j.optInt("maxIntervalSec", 30);
                t.batchSize = j.optInt("batchSize", 10);
                t.batchIntervalSec = j.optInt("batchIntervalSec", 60);
                t.maxSendCount = j.optInt("maxSendCount", 200);
                t.retryTimes = j.optInt("retryTimes", 3);
                t.retryIntervalSec = j.optInt("retryIntervalSec", 60);
                t.repeatMode = j.optString("repeatMode", "once");
                t.hour = j.optInt("hour", 0);
                t.minute = j.optInt("minute", 0);
                t.createTime = j.optLong("createTime", System.currentTimeMillis());
                t.randomInterval = j.optBoolean("randomInterval", false);
                t.momentsVisible = j.optString("momentsVisible", "");
                t.momentsLocation = j.optString("momentsLocation", "");
                return t;
            } catch (Throwable tr) { return null; }
        }
    }

    private static final List<TemplateData> sTemplates = new CopyOnWriteArrayList<>();

    public static List<TemplateData> getAllTemplates() { return new ArrayList<>(sTemplates); }

    public static void saveTemplate(TemplateData tpl) {
        sTemplates.add(tpl);
        persistTemplates();
    }

    public static void updateTemplate(TemplateData tpl) {
        for (int i = 0; i < sTemplates.size(); i++) {
            if (sTemplates.get(i).id.equals(tpl.id)) { sTemplates.set(i, tpl); persistTemplates(); return; }
        }
    }

    public static void deleteTemplate(String id) {
        Iterator<TemplateData> it = sTemplates.iterator();
        while (it.hasNext()) { if (it.next().id.equals(id)) { it.remove(); break; } }
        persistTemplates();
    }

    private static void loadTemplates() {
        sTemplates.clear();
        String json = sAppContext != null ? sAppContext.getSharedPreferences("schedule_templates", Context.MODE_PRIVATE).getString("tpl_list", "") : "";
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                TemplateData t = TemplateData.fromJson(a.getJSONObject(i));
                if (t != null) sTemplates.add(t);
            }
        } catch (Throwable ignored) {}
    }

    private static void persistTemplates() {
        if (sAppContext == null) return;
        try {
            JSONArray a = new JSONArray();
            for (TemplateData t : sTemplates) a.put(t.toJson());
            sAppContext.getSharedPreferences("schedule_templates", Context.MODE_PRIVATE).edit().putString("tpl_list", a.toString()).commit();
        } catch (Throwable ignored) {}
    }

    // ===== 发送日志管理 =====

    public static class SendLogEntry {
        public String logId;
        public String taskId;
        public String taskName;
        public String targetWxid;
        public String targetName;
        public boolean success;
        public String error;
        public long timestamp;
        public String content;

        public SendLogEntry() {
            this.logId = String.valueOf(System.currentTimeMillis()) + "_" + (int)(Math.random() * 10000);
            this.timestamp = System.currentTimeMillis();
        }

        public JSONObject toJson() {
            try {
                JSONObject j = new JSONObject();
                j.put("logId", logId);
                j.put("taskId", nvl(taskId));
                j.put("taskName", nvl(taskName));
                j.put("targetWxid", nvl(targetWxid));
                j.put("targetName", nvl(targetName));
                j.put("success", success);
                j.put("error", nvl(error));
                j.put("timestamp", timestamp);
                j.put("content", nvl(content));
                return j;
            } catch (Throwable t) { return new JSONObject(); }
        }

        public static SendLogEntry fromJson(JSONObject j) {
            try {
                SendLogEntry e = new SendLogEntry();
                e.logId = j.optString("logId", e.logId);
                e.taskId = j.optString("taskId", "");
                e.taskName = j.optString("taskName", "");
                e.targetWxid = j.optString("targetWxid", "");
                e.targetName = j.optString("targetName", "");
                e.success = j.optBoolean("success", false);
                e.error = j.optString("error", "");
                e.timestamp = j.optLong("timestamp", System.currentTimeMillis());
                e.content = j.optString("content", "");
                return e;
            } catch (Throwable tr) { return null; }
        }
    }

    private static final List<SendLogEntry> sSendLogs = new CopyOnWriteArrayList<>();

    public static void addSendLog(SendLogEntry entry) {
        sSendLogs.add(0, entry);
        if (sSendLogs.size() > 1000) {
            while (sSendLogs.size() > 1000) sSendLogs.remove(sSendLogs.size() - 1);
        }
        persistLogs();
    }

    public static List<SendLogEntry> getSendLogs() { return new ArrayList<>(sSendLogs); }

    public static List<SendLogEntry> getFailedLogs() {
        List<SendLogEntry> failed = new ArrayList<>();
        for (SendLogEntry e : sSendLogs) { if (!e.success) failed.add(e); }
        return failed;
    }

    public static void clearSendLogs() { sSendLogs.clear(); persistLogs(); }

    private static void loadLogs() {
        sSendLogs.clear();
        String json = sAppContext != null ? sAppContext.getSharedPreferences("schedule_logs", Context.MODE_PRIVATE).getString("log_list", "") : "";
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                SendLogEntry e = SendLogEntry.fromJson(a.getJSONObject(i));
                if (e != null) sSendLogs.add(e);
            }
        } catch (Throwable ignored) {}
    }

    private static void persistLogs() {
        if (sAppContext == null) return;
        try {
            JSONArray a = new JSONArray();
            for (SendLogEntry e : sSendLogs) a.put(e.toJson());
            sAppContext.getSharedPreferences("schedule_logs", Context.MODE_PRIVATE).edit().putString("log_list", a.toString()).commit();
        } catch (Throwable ignored) {}
    }

    // ===== 克隆任务 =====

    public static Task cloneTask(String taskId) {
        Task src = getTask(taskId);
        if (src == null) return null;
        try {
            Task clone = Task.fromJson(src.toJson());
            clone.id = String.valueOf(System.currentTimeMillis());
            clone.enabled = false;
            clone.totalSendCount = 0;
            clone.failCount = 0;
            clone.lastExecTime = 0;
            clone.triggerTime = src.triggerTime > 0 ? src.triggerTime + 86400000 : System.currentTimeMillis() + 3600000;
            return clone;
        } catch (Throwable t) { return null; }
    }
}
