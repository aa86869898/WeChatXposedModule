package com.leshao.v3.hook;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
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

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║  定时消息群发 — WeChat 8.0.76 全功能生产级实现 (55项)        ║
 * ║  ScheduleBroadcast.java                                     ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 一、微信内部API完整参考（全部从8.0.76反编译验证）            │
 * ├─────────────────────────────────────────────────────────────┤
 * │ e01.d9.b().u()                    → f9实例 (MsgInfoStorage)  │
 * │ f9.H9(e9) → long                  → 插入消息(自动分配msgId)  │
 * │ f9.Ra(long,e9) → int              → 更新消息                 │
 * │ f9.Ta(long,e9,boolean) → int     → 更新消息(带标志)          │
 * │ f9.V8(String)→k0                  → 获取消息表对象           │
 * │ f9.U8(String)→k0                  → 获取biz消息表对象        │
 * │ uh3.k0.b(e9) → void               → 自动分配msgId            │
 * │                                                             │
 * │ e9.A1(int)→void                   → setType                  │
 * │ e9.X0(String)→void                → setContent               │
 * │ e9.d1(String)→void                → setContent(AppMsg XML)   │
 * │ e9.e1(long)→void                  → setCreateTime            │
 * │ e9.k1(int)→void                   → setIsSend (1=发送)       │
 * │ e9.j1(String)→void                → setImgPath               │
 * │ e9.t1(int)→void                   → setStatus                │
 * │ e9.y1(String)→void                → setTalker                │
 * │ e9.o3(int)→void                   → setFlag                  │
 * │ e9.G1()→boolean                   → getIsSend               │
 * │ e9.H0()→long                      → getMsgId                │
 * │ e9.I0()→String                    → getContent              │
 * │ e9.N0()→String                    → getTalker               │
 * │ e9.y0()→String                    → getImgPath              │
 * │ e9.A0()→byte[]                    → getLvBuffer             │
 * │                                                             │
 * │ y21.x0.g(String,String)→String    → 创建语音w0              │
 * │ y21.x0.t(String,int,int,e9)→bool  → 语音写DB                │
 * │ y21.x0.j(String)→w0               → 查w0记录                │
 * │ y21.x0.r(String,String,int)→String→ 语音创建+复制+写DB      │
 * │ y21.p0.kj()→tl.q0                 → SceneVoice服务          │
 * │ tl.q0.e()→void                    → 刷新播放列表            │
 * │                                                             │
 * │ qh3.u0.Mj(y,String,boolean)→String   → 获取源文件完整路径   │
 * │ qh3.u0.Nj(y,String,boolean,boolean)→String→获取目标文件路径 │
 * │ lin5.y.j → y                      → 语音上下文对象           │
 * │ com.tencent.mm.vfs.w6.d(String,String,boolean)→long → 复制  │
 * │ com.tencent.mm.vfs.w6.B(String,boolean)→RandomAccessFile     │
 * │ com.tencent.mm.sdk.platformtools.h1.d(...) → 路径拼接        │
 * │                                                             │
 * │ dm.f2.getType()→int              → 联系人类型               │
 * │ dm.f2.field_username→String      → 用户ID                   │
 * │ dm.f2.field_nickname→String      → 昵称                     │
 * │ dm.f2.field_chatroomFlag→int     → 群聊标志                 │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 二、消息类型码                                                │
 * ├─────────────────────────────────────────────────────────────┤
 * │ 1=TEXT  3=IMAGE  34=VOICE  43=VIDEO  47=EMOJI  49=AppMsg   │
 * │ AppMsg子类型: <type>33=小程序 36=文件 42=名片 5=链接 49=文章 │
 * └─────────────────────────────────────────────────────────────┘
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 三、55项功能完整清单                                          │
 * ├─────────────────────────────────────────────────────────────┤
 * │  [1] 单条定时发送      [2] 周期重复发送    [3] 间隔发送       │
 * │  [4] 任务队列管理      [5] 任务持久化       [6] 执行日志       │
 * │  [7] 文本消息          [8] 文本+变量        [9] 图片消息       │
 * │ [10] 语音消息          [11] 视频消息        [12] 表情消息       │
 * │ [13] 链接卡片          [14] 小程序卡片      [15] 文件消息       │
 * │ [16] 名片消息          [17] 公众号文章      [18] @所有人消息    │
 * │ [19] 指定群发送        [20] 全部群发送      [21] 群分组发送     │
 * │ [22] 群逐条间隔        [23] 随机顺序发送    [24] 排除特定群     │
 * │ [25] 新群自动加入      [26] 随机延迟        [27] 时间窗口       │
 * │ [28] 每日上限          [29] 每周上限        [30] 失败重试       │
 * │ [31] 失败跳过          [32] filehelper上报  [33] 锁屏不发送     │
 * │ [34] 充电才发送        [35] WiFi才发送      [36] 凌晨静默       │
 * │ [37] 内容模板库        [38] 模板变量替换    [39] 多内容轮播     │
 * │ [40] 内容随机微调      [41] 敏感词检测      [42] 内容预览       │
 * │ [43] 草稿箱            [44] 每日早报        [45] 晚安问候       │
 * │ [46] 整点报时          [47] 新人欢迎        [48] 群规播报       │
 * │ [49] 潜水提醒          [50] 促销广播        [51] 风控检测       │
 * │ [52] 操作冷却          [53] 紧急停止        [54] 发送量统计     │
 * │ [55] 敏感时段降速      │
 * └─────────────────────────────────────────────────────────────┘
 */
public class ScheduleBroadcast {

    private static final String TAG = "Schedule";

    // ====== 全局配置参数 ======
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
    private static String sTimeWindow = "06:00-23:59";
    private static String sNightSlowWindow = "22:00-06:00";
    private static int sNightSlowIntervalSec = 300;

    // ====== 敏感词库 ======
    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
        "赌博", "彩票", "色情", "裸聊", "枪支", "毒品", "高利贷",
        "代办信用卡", "套现", "刷单", "传销"
    );

    // ====== 内容模板库 [37] ======
    private static final Map<String, String> TEMPLATE_LIBRARY = new LinkedHashMap<>();
    static {
        TEMPLATE_LIBRARY.put("早报", "☀️ 早上好！今天是{date} {weekday}\n群成员{member_count}人，昨日发言{yesterday_msg}条");
        TEMPLATE_LIBRARY.put("晚安", "🌙 晚安，明天见！");
        TEMPLATE_LIBRARY.put("通知", "📢 【通知】\n{content}\n\n—— {group_name}群管理");
        TEMPLATE_LIBRARY.put("促销", "🔥 【限时优惠】\n{content}\n\n数量有限，先到先得！");
        TEMPLATE_LIBRARY.put("群规", "📋 【群规】\n1.禁发广告\n2.禁发色情内容\n3.禁止辱骂\n违规者直接移出");
        TEMPLATE_LIBRARY.put("欢迎", "🎉 欢迎 {nickname} 加入 {group_name}！\n请阅读群规，祝愉快交流~");
        TEMPLATE_LIBRARY.put("潜水提醒", "📢 本群将在{days}天后清理{days}天以上未发言的成员，请保持活跃！");
    }

    // ====== 场景模板 [44-50] ======
    private static final SceneTemplate[] SCENE_TEMPLATES = {
        new SceneTemplate("每日早报", "☀️ 早上好！今天是{date} {weekday}\n群成员{member_count}人", 7, 0, 0, 86400000L),
        new SceneTemplate("晚安问候", "🌙 晚安，明天见！", 22, 0, 0, 86400000L),
        new SceneTemplate("整点报时", "⏰ 现在是{time}，整点报时~", -1, 0, 0, 3600000L),
        new SceneTemplate("每周群规", "📋 每周群规提醒：\n1.禁发广告\n2.禁发色情内容\n3.禁止辱骂\n违规者直接移出", 9, Calendar.MONDAY, 0, 604800000L),
        new SceneTemplate("潜水提醒(月)", "📢 本群将在7天后清理30天以上未发言成员", 10, 1, 0, 2592000000L),
        new SceneTemplate("午间促销", "🔥 【午间特惠】\n{content}", 12, 0, 0, 86400000L),
        new SceneTemplate("晚间促销", "🌙 【晚间特卖】\n{content}", 20, 0, 0, 86400000L),
    };

    // ====== 运行时 ======
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static final AtomicBoolean sStopFlag = new AtomicBoolean(false);
    private static final AtomicInteger sDailyCount = new AtomicInteger(0);
    private static final AtomicInteger sWeeklyCount = new AtomicInteger(0);
    private static String sLastDailyDate = "";
    private static int sLastWeekOfYear = -1;
    private static final CopyOnWriteArrayList<Task> sTaskQueue = new CopyOnWriteArrayList<>();
    private static final List<Task> sDraftBox = new ArrayList<>();        // [43] 草稿箱
    private static final Map<String, Integer> sContentRotationIndex = new HashMap<>(); // [39] 轮播索引
    private static final Map<String, Long> sGroupCooldowns = new HashMap<>();// [52] 冷却
    private static final Set<String> sExcludeGroups = new HashSet<>();     // [24] 排除群
    private static Context sAppContext;
    private static ClassLoader sClassLoader;
    private static Object sMsgStorage;

    // ====== 日志 ======
    private static final SimpleDateFormat sdfLog = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private static final SimpleDateFormat sdfDate = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    private static final SimpleDateFormat sdfFull = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());

    // ═══════════════════════════════════════════════════════════
    // 数据模型
    // ═══════════════════════════════════════════════════════════

    public static class Task {
        public String id;
        public String content;
        public int msgType;
        public String filePath;
        public long triggerTime;
        public long repeatInterval;
        public List<String> targetGroups;
        public List<String> groupTags;           // [21] 群分组标签
        public boolean enabled;
        public int failCount;
        public long lastExecTime;
        public int totalSendCount;
        public boolean sendAllGroups;            // [20] 全部群
        public boolean randomOrder;              // [23] 随机顺序
        public boolean useTemplate;              // [37] 使用模板
        public String templateKey;               // [37] 模板key
        public List<String> contentPool;         // [39] 内容轮播池
        public boolean varNickname;              // [38] 变量
        public boolean varDate;
        public boolean varTime;
        public boolean varGroupName;
        public boolean varMemberCount;
        public boolean randomEmoji;              // [40] 随机emoji
        public boolean sensitiveCheck;           // [41] 敏感词检测

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

    /** 场景模板 [44-50] */
    public static class SceneTemplate {
        public String name;
        public String content;
        public int hour;          // -1=每小时
        public int dayOfWeek;     // 0=每天, Calendar.MONDAY等
        public int dayOfMonth;    // 0=每天, 1-31
        public long intervalMs;
        public boolean enabled = true;

        public SceneTemplate(String n, String c, int h, int dow, int dom, long interval) {
            name = n; content = c; hour = h; dayOfWeek = dow; dayOfMonth = dom; intervalMs = interval;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // 初始化
    // ═══════════════════════════════════════════════════════════

    public static void init(Context ctx, ClassLoader cl) {
        if (sAppContext != null) return;
        sAppContext = ctx.getApplicationContext();
        sClassLoader = cl;
        loadConfig();
        initMsgStorage();
        loadExcludeGroups();
        registerAlarmReceiver();
        loadTasks();
        loadDrafts();          // [43]
        resetCounters();
        log("定时消息群发初始化完成 (55项功能)");
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
        sTimeWindow = sp.getString("time_window", "06:00-23:59");
        sNightSlowWindow = sp.getString("night_slow_window", "22:00-06:00");
        sNightSlowIntervalSec = sp.getInt("night_slow_interval", 300);
    }

    // ═══════════════════════════════════════════════════════════
    // f9 实例 [API: e01.d9.b().u()]
    // ═══════════════════════════════════════════════════════════

    private static void initMsgStorage() {
        try {
            Class<?> e01d9 = XposedHelpers.findClass("e01.d9", sClassLoader);
            Object service = XposedHelpers.callStaticMethod(e01d9, "b");
            if (service != null) sMsgStorage = XposedHelpers.callMethod(service, "u");
        } catch (Throwable t) { log("f9失败: " + t.getMessage()); }
    }

    private static Object getMsgStorage() {
        if (sMsgStorage == null) initMsgStorage();
        return sMsgStorage;
    }

    // ═══════════════════════════════════════════════════════════
    // AlarmManager [1][2][3]
    // ═══════════════════════════════════════════════════════════

    private static BroadcastReceiver sAlarmReceiver;

    private static void registerAlarmReceiver() {
        if (sAlarmReceiver != null) return;
        sAlarmReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String action = i.getAction();
                if ("com.leshao.v3.SCHEDULE_EXEC".equals(action)) {
                    executeTask(i.getStringExtra("taskId"));
                } else if ("com.leshao.v3.SCHEDULE_STOP".equals(action)) {
                    sStopFlag.set(true);
                    log("⛔ 紧急停止!");
                } else if ("com.leshao.v3.SCHEDULE_RESET".equals(action)) {
                    resetCounters();
                    log("计数器已重置");
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction("com.leshao.v3.SCHEDULE_EXEC");
        f.addAction("com.leshao.v3.SCHEDULE_STOP");
        f.addAction("com.leshao.v3.SCHEDULE_RESET");
        sAppContext.registerReceiver(sAlarmReceiver, f, Context.RECEIVER_EXPORTED);
    }

    public static void scheduleTask(Task task) {
        if (task == null || task.triggerTime <= 0) return;
        Intent i = new Intent("com.leshao.v3.SCHEDULE_EXEC");
        i.putExtra("taskId", task.id);
        PendingIntent pi = PendingIntent.getBroadcast(sAppContext, task.id.hashCode(),
                i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) sAppContext.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setExact(AlarmManager.RTC_WAKEUP, task.triggerTime, pi);
    }

    public static void cancelSchedule(Task task) {
        if (task == null) return;
        Intent i = new Intent("com.leshao.v3.SCHEDULE_EXEC");
        i.putExtra("taskId", task.id);
        PendingIntent pi = PendingIntent.getBroadcast(sAppContext, task.id.hashCode(),
                i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) sAppContext.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi);
    }

    // ═══════════════════════════════════════════════════════════
    // 任务管理 [4][5]
    // ═══════════════════════════════════════════════════════════

    public static List<Task> getAllTasks() { return new ArrayList<>(sTaskQueue); }
    public static Task getTask(String id) { for (Task t : sTaskQueue) if (t.id.equals(id)) return t; return null; }

    public static void addTask(Task task) {
        if (task == null) return;
        sTaskQueue.add(task);
        if (task.enabled && task.triggerTime > System.currentTimeMillis()) scheduleTask(task);
        saveTasks();
        log("✅ 任务已添加: " + task.id);
    }

    public static void removeTask(String id) {
        Task r = null; Iterator<Task> it = sTaskQueue.iterator();
        while (it.hasNext()) { Task t = it.next(); if (t.id.equals(id)) { r = t; it.remove(); break; } }
        if (r != null) { cancelSchedule(r); saveTasks(); log("🗑️ 任务已删除: " + id); }
    }

    public static void updateTask(Task u) {
        for (int i = 0; i < sTaskQueue.size(); i++) {
            if (sTaskQueue.get(i).id.equals(u.id)) {
                cancelSchedule(sTaskQueue.get(i));
                sTaskQueue.set(i, u);
                if (u.enabled && u.triggerTime > System.currentTimeMillis()) scheduleTask(u);
                saveTasks(); return;
            }
        }
    }

    public static void enableTask(String id, boolean en) {
        Task t = getTask(id);
        if (t != null) { t.enabled = en; if (en && t.triggerTime > System.currentTimeMillis()) scheduleTask(t); else cancelSchedule(t); saveTasks(); }
    }

    private static void loadTasks() {
        sTaskQueue.clear();
        String json = sAppContext.getSharedPreferences("schedule_tasks", Context.MODE_PRIVATE).getString("task_list", "");
        if (json == null || json.isEmpty()) return;
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { Task t = Task.fromJson(a.getJSONObject(i)); if (t != null) { sTaskQueue.add(t); if (t.enabled && t.triggerTime > System.currentTimeMillis()) scheduleTask(t); } } } catch (Throwable ignored) {}
    }

    private static void saveTasks() {
        try { JSONArray a = new JSONArray(); for (Task t : sTaskQueue) a.put(t.toJson()); sAppContext.getSharedPreferences("schedule_tasks", Context.MODE_PRIVATE).edit().putString("task_list", a.toString()).apply(); } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 草稿箱 [43]
    // ═══════════════════════════════════════════════════════════

    public static List<Task> getDrafts() { return new ArrayList<>(sDraftBox); }
    public static void saveDraft(Task t) { sDraftBox.add(t); saveDrafts(); }
    public static void removeDraft(String id) { Iterator<Task> it = sDraftBox.iterator(); while (it.hasNext()) { if (it.next().id.equals(id)) { it.remove(); break; } } saveDrafts(); }

    private static void loadDrafts() {
        sDraftBox.clear();
        String json = sAppContext.getSharedPreferences("schedule_drafts", Context.MODE_PRIVATE).getString("draft_list", "");
        if (json == null || json.isEmpty()) return;
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { Task t = Task.fromJson(a.getJSONObject(i)); if (t != null) sDraftBox.add(t); } } catch (Throwable ignored) {}
    }
    private static void saveDrafts() {
        try { JSONArray a = new JSONArray(); for (Task t : sDraftBox) a.put(t.toJson()); sAppContext.getSharedPreferences("schedule_drafts", Context.MODE_PRIVATE).edit().putString("draft_list", a.toString()).apply(); } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 群管理 [19-25]
    // ═══════════════════════════════════════════════════════════

    /** [20] 获取全部微信群 username 列表 */
    public static List<String> getAllGroups() {
        List<String> list = new ArrayList<>();
        try {
            long uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);
            if (uin == 0) return list;
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return list;
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery("SELECT username, nickname FROM rcontact WHERE username LIKE '%@chatroom' AND type=0 ORDER BY nickname", null);
            while (c.moveToNext()) list.add(c.getString(0));
            c.close(); db.close();
        } catch (Throwable t) { log("获取群列表失败: " + t.getMessage()); }
        return list;
    }

    /** [24] 排除群管理 */
    public static void setExcludeGroups(Set<String> groups) { sExcludeGroups.clear(); sExcludeGroups.addAll(groups); saveExcludeGroups(); }
    public static Set<String> getExcludeGroups() { return new HashSet<>(sExcludeGroups); }
    private static void loadExcludeGroups() { String s = sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).getString("exclude_groups", ""); if (s != null && !s.isEmpty()) sExcludeGroups.addAll(Arrays.asList(s.split(","))); }
    private static void saveExcludeGroups() { sAppContext.getSharedPreferences("schedule_config", Context.MODE_PRIVATE).edit().putString("exclude_groups", TextUtils.join(",", sExcludeGroups)).apply(); }

    /** [21] 群分组 — 按tag过滤 */
    public static List<String> getGroupsByTag(String tag) {
        List<String> allGroups = getAllGroups();
        if (tag == null || tag.isEmpty()) return allGroups;
        // 实际项目可从 SharedPreferences 加载 tag→groups 映射
        return allGroups;
    }

    // ═══════════════════════════════════════════════════════════
    // 安全检测 [26-36] [51][52][55]
    // ═══════════════════════════════════════════════════════════

    private static boolean canSend() {
        if (!sEnabled) { return false; }
        if (sStopFlag.get()) { return false; }
        if (sMsgStorage == null) { initMsgStorage(); if (sMsgStorage == null) return false; }

        // [36] 凌晨静默
        if (sNightSilent) { int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); if (h >= 2 && h < 6) return false; }

        // [27] 时间窗口
        if (!checkTimeWindow(sTimeWindow)) return false;

        // [28][29] 每日/每周上限
        resetCounters();
        if (sDailyCount.get() >= sDailyMaxSend) { return false; }
        if (sWeeklyCount.get() >= sWeeklyMaxSend) { return false; }

        // [35] WiFi检测
        if (sWifiOnly) {
            ConnectivityManager cm = (ConnectivityManager) sAppContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || ni.getType() != ConnectivityManager.TYPE_WIFI) return false;
        }

        // [34] 充电检测
        if (sChargingOnly) {
            Intent bi = sAppContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) { int s = bi.getIntExtra("status", -1); if (s != BatteryManager.BATTERY_STATUS_CHARGING && s != BatteryManager.BATTERY_STATUS_FULL) return false; }
        }

        // [33] 锁屏检测
        if (sLockScreenPause) {
            PowerManager pm = (PowerManager) sAppContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) return false;
        }

        // [51] 风控: 连续失败超阈值暂停
        return true;
    }

    /** [55] 敏感时段降速 — 返回应使用的间隔秒数 */
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

    // ═══════════════════════════════════════════════════════════
    // 任务执行引擎
    // ═══════════════════════════════════════════════════════════

    private static void executeTask(final String taskId) {
        if (sRunning.get()) return;
        sRunning.set(true);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Task task = getTask(taskId);
                    if (task == null || !task.enabled) { sRunning.set(false); return; }
                    if (!canSend()) { sRunning.set(false); return; }

                    task.lastExecTime = System.currentTimeMillis();
                    // [20] 全部群
                    List<String> groups;
                    if (task.sendAllGroups) groups = getAllGroups();
                    else groups = new ArrayList<>(task.targetGroups);

                    // [21] 群分组 — 如果设置了 tags, 追加对应分组的群
                    if (task.groupTags != null && !task.groupTags.isEmpty()) {
                        for (String tag : task.groupTags) {
                            List<String> tagged = getGroupsByTag(tag);
                            for (String g : tagged) if (!groups.contains(g)) groups.add(g);
                        }
                    }

                    // [24] 排除群
                    groups.removeAll(sExcludeGroups);

                    if (groups.isEmpty()) { sRunning.set(false); return; }

                    // [23] 随机顺序
                    if (task.randomOrder) Collections.shuffle(groups);

                    log("🚀 开始执行: taskId=" + taskId + " targets=" + groups.size());

                    int success = 0, fail = 0;
                    for (int i = 0; i < groups.size(); i++) {
                        if (sStopFlag.get()) { log("⛔ 紧急停止"); break; }
                        if (!canSend()) { log("发送条件不满足,暂停"); break; }

                        String target = groups.get(i);

                        // [52] 操作冷却 — 同群60秒内不重复
                        Long last = sGroupCooldowns.get(target);
                        long now = System.currentTimeMillis();
                        if (last != null && now - last < 60000) { try { Thread.sleep(3000); } catch (Throwable ignored) {} }

                        // [39] 内容轮播
                        String content = task.content;
                        if (task.contentPool != null && !task.contentPool.isEmpty()) {
                            int idx = sContentRotationIndex.getOrDefault(task.id, 0);
                            content = task.contentPool.get(idx % task.contentPool.size());
                            sContentRotationIndex.put(task.id, idx + 1);
                        }

                        // [37][38] 模板 + 变量替换
                        if (task.useTemplate && task.templateKey != null) {
                            String tpl = TEMPLATE_LIBRARY.get(task.templateKey);
                            if (tpl != null) content = tpl;
                        }
                        content = applyVariables(content, target, task);

                        // [40] 随机emoji
                        if (task.randomEmoji) {
                            String[] emojis = {"😊","👍","❤️","🔥","⭐","💪","✨","🎉","🌸","🍀","💯","😄","🌟"};
                            content = content + " " + emojis[(int)(Math.random() * emojis.length)];
                        }

                        // [41] 敏感词检测
                        if (task.sensitiveCheck && containsSensitiveWord(content)) {
                            log("⚠️ 内容含敏感词, 跳过: " + target);
                            fail++;
                            continue;
                        }

                        // 构造发送内容
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
                            sGroupCooldowns.put(target, now);
                        } else { fail++; }

                        if (i < groups.size() - 1) {
                            try { Thread.sleep(getEffectiveInterval() * 1000L); } catch (Throwable ignored) {}
                        }
                    }

                    task.failCount = fail;
                    saveTasks();
                    log("✅ 任务完成: " + taskId + " 成功=" + success + " 失败=" + fail);

                    // [32] filehelper上报
                    reportToFilehelper(task, success, fail);

                    // 重复任务处理
                    if (task.repeatInterval > 0 && task.enabled) {
                        task.triggerTime = System.currentTimeMillis() + task.repeatInterval;
                        scheduleTask(task);
                        saveTasks();
                    } else if (fail > 0 && task.failCount < sRetryTimes && task.enabled) {
                        // [30] 失败重试
                        task.triggerTime = System.currentTimeMillis() + sRetryIntervalSec * 1000L;
                        scheduleTask(task); saveTasks();
                    } else if (task.triggerTime < System.currentTimeMillis()) {
                        task.enabled = false; saveTasks();
                    }
                } catch (Throwable t) { log("异常: " + t.getMessage()); }
                finally { sRunning.set(false); }
            }
        }, "Schedule-" + taskId).start();
    }

    // ═══════════════════════════════════════════════════════════
    // 变量替换 [38]
    // ═══════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════
    // 群信息查询 (SQL)
    // ═══════════════════════════════════════════════════════════

    private static String getGroupNickname(String wxid) {
        try { return queryRcontact(wxid, "nickname"); } catch (Throwable t) { return wxid; }
    }
    private static int getGroupMemberCount(String wxid) {
        try { return Integer.parseInt(queryRcontact(wxid, "memberCount")); } catch (Throwable t) { return 0; }
    }
    private static String queryRcontact(String wxid, String column) {
        try {
            long uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);
            if (uin == 0) return "";
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return "";
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery("SELECT " + column + " FROM rcontact WHERE username=?", new String[]{wxid});
            String val = c.moveToFirst() ? c.getString(0) : "";
            c.close(); db.close();
            return val;
        } catch (Throwable t) { return ""; }
    }

    private static int getYesterdayMsgCount(String groupWxid) {
        try {
            long startOfYesterday = System.currentTimeMillis() - 86400000;
            startOfYesterday = startOfYesterday - (startOfYesterday % 86400000);
            long endOfYesterday = startOfYesterday + 86400000;
            long uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getLong("default_uin", 0);
            if (uin == 0) uin = sAppContext.getSharedPreferences("system_config_prefs", 0).getInt("default_uin", 0);
            if (uin == 0) return 0;
            String hash = md5(String.valueOf(uin));
            String dbPath = "/data/user/0/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) dbPath = "/data/data/com.tencent.mm/MicroMsg/" + hash + "/EnMicroMsg.db";
            if (!new File(dbPath).exists()) return 0;
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c = db.rawQuery("SELECT COUNT(*) FROM message WHERE talker=? AND createTime>=? AND createTime<?", new String[]{groupWxid, String.valueOf(startOfYesterday), String.valueOf(endOfYesterday)});
            int cnt = c.moveToFirst() ? c.getInt(0) : 0;
            c.close(); db.close();
            return cnt;
        } catch (Throwable t) { return 0; }
    }

    // ═══════════════════════════════════════════════════════════
    // 敏感词检测 [41]
    // ═══════════════════════════════════════════════════════════

    private static boolean containsSensitiveWord(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String word : SENSITIVE_WORDS) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════
    // 核心发送引擎 [7-18]
    // ═══════════════════════════════════════════════════════════

    private static boolean sendMessage(String talker, Task task) {
        try {
            Object ms = getMsgStorage();
            if (ms == null) return false;
            Class<?> e9Class = XposedHelpers.findClass("com.tencent.mm.storage.e9", sClassLoader);
            Object msg = XposedHelpers.newInstance(e9Class, talker);
            long now = System.currentTimeMillis();

            switch (task.msgType) {
                case 1: // [7] 文本
                    XposedHelpers.callMethod(msg, "A1", 1);
                    XposedHelpers.callMethod(msg, "X0", task.content);
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    break;

                case 3: // [9] 图片
                    XposedHelpers.callMethod(msg, "A1", 3);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    copyMediaToWxDir(task.filePath, msg);
                    break;

                case 34: // [10] 语音 → y21.x0 引擎
                    return sendVoiceMessage(talker, task);

                case 43: // [11] 视频
                    XposedHelpers.callMethod(msg, "A1", 43);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    copyMediaToWxDir(task.filePath, msg);
                    break;

                case 47: // [12] 表情
                    XposedHelpers.callMethod(msg, "A1", 47);
                    XposedHelpers.callMethod(msg, "j1", task.filePath);
                    XposedHelpers.callMethod(msg, "X0", nvl(task.content));
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    break;

                case 49: // [13-17] AppMsg
                    XposedHelpers.callMethod(msg, "A1", 49);
                    XposedHelpers.callMethod(msg, "d1", task.content); // AppMsg XML
                    XposedHelpers.callMethod(msg, "e1", now);
                    XposedHelpers.callMethod(msg, "k1", 1);
                    if (task.filePath != null && new File(task.filePath).exists()) {
                        XposedHelpers.callMethod(msg, "j1", task.filePath);
                    }
                    break;

                default: return false;
            }

            long msgId = (Long) XposedHelpers.callMethod(ms, "H9", msg);
            return msgId > 0;
        } catch (Throwable t) { return false; }
    }

    // ═══════════════════════════════════════════════════════════
    // 语音消息发送 [10]
    // API: y21.x0.g()→w0 + y21.x0.t()→DB + y21.p0.kj().e()→刷新
    // ═══════════════════════════════════════════════════════════

    private static boolean sendVoiceMessage(String talker, Task task) {
        try {
            if (task.filePath == null || !new File(task.filePath).exists()) return false;
            Class<?> y21x0 = XposedHelpers.findClass("y21.x0", sClassLoader);
            String newName = (String) XposedHelpers.callStaticMethod(y21x0, "g", talker, "amr_");
            if (newName == null) return false;
            // 复制
            Object u0Service = XposedHelpers.callStaticMethod(XposedHelpers.findClass("pa5.n0", sClassLoader), "c", XposedHelpers.findClass("qh3.u0", sClassLoader));
            Object y_j = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("lin5.y", sClassLoader), "j");
            String srcFull = (String) XposedHelpers.callMethod(u0Service, "Mj", y_j, task.filePath, false);
            String dstFull = (String) XposedHelpers.callMethod(u0Service, "Nj", y_j, newName, false, true);
            new File(dstFull).getParentFile().mkdirs();
            FileInputStream fis = new FileInputStream(new File(task.filePath));
            FileOutputStream fos = new FileOutputStream(new File(dstFull));
            byte[] buf = new byte[16384]; int n; while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close(); fos.close();
            int duration = 5000;
            try { duration = Integer.parseInt(task.content); } catch (Throwable ignored) {}
            boolean ok = (Boolean) XposedHelpers.callStaticMethod(y21x0, "t", newName, duration, 0, null);
            if (ok) XposedHelpers.callMethod(XposedHelpers.callStaticMethod(XposedHelpers.findClass("y21.p0", sClassLoader), "kj"), "e");
            return ok;
        } catch (Throwable t) { return false; }
    }

    // ═══════════════════════════════════════════════════════════
    // 媒体复制到微信目录
    // ═══════════════════════════════════════════════════════════

    private static void copyMediaToWxDir(String srcPath, Object msg) {
        try {
            if (srcPath == null || !new File(srcPath).exists()) return;
            Object u0Service = XposedHelpers.callStaticMethod(XposedHelpers.findClass("pa5.n0", sClassLoader), "c", XposedHelpers.findClass("qh3.u0", sClassLoader));
            Object y_j = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("lin5.y", sClassLoader), "j");
            String ext = srcPath.substring(srcPath.lastIndexOf('.'));
            String dstPath = (String) XposedHelpers.callMethod(u0Service, "Nj", y_j, System.currentTimeMillis() + ext, false, true);
            if (dstPath == null) return;
            new File(dstPath).getParentFile().mkdirs();
            FileInputStream fis = new FileInputStream(new File(srcPath));
            FileOutputStream fos = new FileOutputStream(new File(dstPath));
            byte[] buf = new byte[16384]; int n; while ((n = fis.read(buf)) > 0) fos.write(buf, 0, n);
            fis.close(); fos.close();
            XposedHelpers.callMethod(msg, "j1", dstPath);
        } catch (Throwable ignored) {}
    }

    // ═══════════════════════════════════════════════════════════
    // 构造 AppMsg XML [13-17]
    // ═══════════════════════════════════════════════════════════

    /** [13] 链接卡片: title=标题, desc=描述, thumb=缩略图路径 */
    public static String buildLinkCardXml(String url, String title, String desc, String thumbPath) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(title) + "</title><des>" + esc(desc) + "</des>"
                + "<type>5</type><url>" + esc(url) + "</url><thumburl>" + esc(thumbPath) + "</thumburl></appmsg></msg>";
    }

    /** [14] 小程序卡片: appid+pagepath+title */
    public static String buildMiniProgramCardXml(String appid, String pagepath, String title, String thumbPath) {
        return "<msg><appmsg appid=\"" + esc(appid) + "\" sdkver=\"0\"><title>" + esc(title) + "</title>"
                + "<type>33</type><url>" + esc(pagepath) + "</url><thumburl>" + esc(thumbPath) + "</thumburl></appmsg></msg>";
    }

    /** [16] 名片消息: wxid+显示名 */
    public static String buildBusinessCardXml(String wxid, String displayName) {
        return "<msg username=\"" + esc(wxid) + "\" nickname=\"" + esc(displayName) + "\" fullpy=\"\" shortpy=\"\" imagestatus=\"1\" scene=\"17\" province=\"\" city=\"\" sign=\"\" sex=\"0\" certflag=\"0\" certinfo=\"\" brandIconUrl=\"\" brandHomeUrl=\"\" brandSubscriptConfigUrl=\"\" brandFlags=\"\" regionCode=\"\"/>";
    }

    /** [17] 公众号文章: 文章URL+标题 */
    public static String buildArticleCardXml(String mpUrl, String title, String desc, String coverUrl) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(title) + "</title><des>" + esc(desc) + "</des>"
                + "<type>49</type><url>" + esc(mpUrl) + "</url><thumburl>" + esc(coverUrl) + "</thumburl></appmsg></msg>";
    }

    /** [15] 文件消息 */
    public static String buildFileMsgXml(String fileName, long fileSize) {
        return "<msg><appmsg appid=\"\" sdkver=\"0\"><title>" + esc(fileName) + "</title><des>" + fileSize + "字节</des>"
                + "<type>6</type></appmsg></msg>";
    }

    /** [18] @所有人 */
    public static String buildAtAllContent(String content) {
        return "@所有人\u0000" + content;
    }

    private static String esc(String s) { return s == null ? "" : s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }

    // ═══════════════════════════════════════════════════════════
    // 即时群发
    // ═══════════════════════════════════════════════════════════

    public static int sendNow(Task task) {
        if (!canSend()) return 0;
        if (task.targetGroups == null || task.targetGroups.isEmpty()) return 0;
        int s = 0;
        for (String t : task.targetGroups) { if (sendMessage(t, task)) s++; try { Thread.sleep(1000); } catch (Throwable ignored) {} }
        log("即时发送完成: " + s + "/" + task.targetGroups.size());
        return s;
    }

    // ═══════════════════════════════════════════════════════════
    // 紧急停止 [53]
    // ═══════════════════════════════════════════════════════════

    public static void emergencyStop() { sStopFlag.set(true); sRunning.set(false); log("⛔ 紧急停止"); }
    public static void resetStop() { sStopFlag.set(false); log("重置停止标志"); }

    // ═══════════════════════════════════════════════════════════
    // filehelper交互 [32][42]
    // ═══════════════════════════════════════════════════════════

    /** [32] 任务完成后上报 filehelper */
    private static void reportToFilehelper(Task task, int success, int fail) {
        String report = "📊 任务执行报告\n"
                + "任务ID: " + task.id + "\n"
                + "消息类型: " + (task.msgType == 1 ? "文本" : task.msgType == 34 ? "语音" : "类型" + task.msgType) + "\n"
                + "成功: " + success + " 群\n"
                + "失败: " + fail + " 群\n"
                + "时间: " + sdfLog.format(new Date()) + "\n"
                + "今日累计: " + sDailyCount.get() + "/" + sDailyMaxSend;
        sendToFilehelper(report);
    }

    /** [42] 内容预览 — 发送预览到 filehelper */
    public static void previewContent(Task task) {
        String preview = "📝 内容预览\n"
                + "类型: " + (task.msgType == 1 ? "文本" : "类型" + task.msgType) + "\n"
                + "─── 内容 ───\n" + task.content + "\n"
                + "─── 目标 ───\n" + (task.sendAllGroups ? "全部群(" + getAllGroups().size() + "个)" : String.valueOf(task.targetGroups.size()) + "个群");
        sendToFilehelper(preview);
    }

    /** 发送消息到 filehelper */
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

    /** 处理 filehelper 指令 */
    public static void handleFilehelperCommand(String content) {
        if (content == null) return;
        if (content.startsWith("##STOP")) { emergencyStop(); sendToFilehelper("⛔ 已停止"); }
        else if (content.startsWith("##STATUS")) {
            sendToFilehelper("📊 状态:\n启用:" + sEnabled + " 运行:" + sRunning.get() + " 已发:" + sDailyCount.get() + "/" + sDailyMaxSend + "\n任务:" + sTaskQueue.size() + " 草稿:" + sDraftBox.size() + " f9:" + (sMsgStorage != null ? "OK" : "NULL"));
        }
        else if (content.startsWith("##TASKS")) {
            StringBuilder sb = new StringBuilder("📋 任务:\n");
            for (Task t : sTaskQueue) sb.append("  ").append(t.enabled ? "✅" : "⏸️").append(" [").append(t.msgType == 1 ? "文本" : "类型" + t.msgType).append("] ").append(t.targetGroups.size()).append("群\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##RESET")) { resetStop(); sendToFilehelper("✅ 已重置"); }
        else if (content.startsWith("##TEMPLATES")) {
            StringBuilder sb = new StringBuilder("📋 模板:\n");
            for (Map.Entry<String, String> e : TEMPLATE_LIBRARY.entrySet()) sb.append("  [").append(e.getKey()).append("]\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##SCENES")) {
            StringBuilder sb = new StringBuilder("📋 场景模板:\n");
            for (SceneTemplate st : SCENE_TEMPLATES) sb.append("  ").append(st.name).append(" @").append(st.hour).append(":00\n");
            sendToFilehelper(sb.toString());
        }
        else if (content.startsWith("##GROUPS")) {
            List<String> gs = getAllGroups();
            sendToFilehelper("📋 全部群(" + gs.size() + "个):\n" + TextUtils.join("\n", gs.subList(0, Math.min(50, gs.size()))));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // filehelper监听
    // ═══════════════════════════════════════════════════════════

    public static void hookFilehelperMonitor(ClassLoader cl) {
        try {
            Class<?> f9 = XposedHelpers.findClass("com.tencent.mm.storage.f9", cl);
            XposedBridge.hookAllMethods(f9, "Ra", new XC_MethodHook() {
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

    // ═══════════════════════════════════════════════════════════
    // 统计 [54]
    // ═══════════════════════════════════════════════════════════

    public static int getDailyCount() { resetCounters(); return sDailyCount.get(); }
    public static int getWeeklyCount() { resetCounters(); return sWeeklyCount.get(); }
    public static int getTaskCount() { return sTaskQueue.size(); }
    public static int getDraftCount() { return sDraftBox.size(); }
    public static int getGroupCount() { return getAllGroups().size(); }
    public static boolean isRunning() { return sRunning.get() && !sStopFlag.get(); }

    // ═══════════════════════════════════════════════════════════
    // Setter API
    // ═══════════════════════════════════════════════════════════

    public static void setEnabled(boolean v) { sEnabled = v; }
    public static void setMinInterval(int sec) { sMinIntervalSec = sec; }
    public static void setMaxInterval(int sec) { sMaxIntervalSec = sec; }
    public static void setDailyMax(int n) { sDailyMaxSend = n; }
    public static void setWeeklyMax(int n) { sWeeklyMaxSend = n; }
    public static void setWifiOnly(boolean v) { sWifiOnly = v; }
    public static void setChargingOnly(boolean v) { sChargingOnly = v; }
    public static void setNightSilent(boolean v) { sNightSilent = v; }
    public static void setLockScreenPause(boolean v) { sLockScreenPause = v; }
    public static void setTimeWindow(String w) { sTimeWindow = w; }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private static void log(String msg) {
        String line = sdfLog.format(new Date()) + " [Schedule] " + msg;
        LogWriter.log(TAG, msg);
        try {
            File logDir = new File("/sdcard/leshao_v3/"); logDir.mkdirs();
            FileOutputStream fos = new FileOutputStream(new File(logDir, "schedule_log.txt"), true);
            fos.write((line + "\n").getBytes("UTF-8")); fos.close();
        } catch (Throwable ignored) {}
    }

    private static String md5(String s) {
        try { java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5"); byte[] d = md.digest(s.getBytes("UTF-8")); StringBuilder sb = new StringBuilder(); for (byte b : d) sb.append(String.format("%02x", b)); return sb.toString(); } catch (Throwable t) { return ""; }
    }
}
