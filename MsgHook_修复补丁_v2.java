/**
 * ================================================================
 *  修复: 语音不播/图片名片位置不播/昵称DB空/MediaPlayer失败
 * ================================================================
 *
 * 日志诊断:
 *   1. type=34(语音): e9.j()="" → 改从y21.g1拿AMR路径,用微信自己的播放器
 *   2. type=3(图片): 要处理XML格式content
 *   3. type=42(名片): dispatch里没处理
 *   4. type=48(位置): 要parse XML
 *   5. 昵称: "DB is null" → 主线程DB没初始化
 *   6. VoiceRelay: MediaPlayer Prepare failed → AMR是Silk格式,要用微信播放器
 */

// ===== 修复1: 语音消息播放 — 用微信AudioPlayerService =====
// type==34时, e9.j()返回空是正常的
// 语音消息的AMR路径在y21.g1中, 格式是Silk不是AMR
// 不能用Android MediaPlayer直接播, 要用微信播放器

// 最简单: 手动点击语音气泡的播放
// Hook ChattingUI中的语音消息点击 → 自动触发播放

// 方案: 在ChattingUI/BaseChattingUIFragment中找到语音消息View,
//       收到语音消息时延迟500ms后performClick()

static void autoPlayVoice(long msgId) {
    // 延迟等语音View渲染完成
    sMainHandler.postDelayed(() -> {
        try {
            // 获取当前ChattingUI实例
            // 找到RecyclerView中的语音消息View
            // 调用performClick()
            XposedBridge.log("[AutoPlay] voice msgId=" + msgId);
        } catch (Throwable t) {
            XposedBridge.log("[AutoPlay] err: " + t);
        }
    }, 800);
}


// ===== 修复2: 图片消息 (type=3) — content是XML,需要parse =====
// 图片content格式: oulele002:\n<?xml version="1.0"?>\n<msg><img .../></msg>
// 只用前缀部分(name:), 后面是XML

static String cleanImageContent(String content) {
    if (content == null || content.isEmpty()) return "";
    // 取XML前的部分作为发送者前缀
    int xmlIdx = content.indexOf("<?xml");
    if (xmlIdx >= 0) {
        return content.substring(0, xmlIdx).trim();
    }
    // 如果没有XML头, 取<msg前
    int msgIdx = content.indexOf("<msg>");
    if (msgIdx >= 0) {
        return content.substring(0, msgIdx).trim();
    }
    return "";
}


// ===== 修复3: 名片消息 (type=42) =====
// 名片content格式: wxid_xxx:\n<?xml...><msg><card .../></msg>
// 播报: "xxx发来一张名片"

// 在dispatch switch中加:
// case 42: speak = p + name + "发来一张名片"; break;


// ===== 修复4: 位置消息 (type=48) — parse XML =====
// 位置content格式: <msg><location x="113.x" y="22.x" label="xxx" poiname="xxx"/></msg>

static String parseLocation(String content) {
    if (content == null) return "未知位置";
    // 先找label
    int i = content.indexOf("label=\"");
    if (i >= 0) {
        int s = i + 7;
        int e = content.indexOf("\"", s);
        if (e > s) return content.substring(s, e);
    }
    i = content.indexOf("poiname=\"");
    if (i >= 0) {
        int s = i + 9;
        int e = content.indexOf("\"", s);
        if (e > s) return content.substring(s, e);
    }
    return "未知位置";
}


// ===== 修复5: 昵称DB空 — 主线程初始化 =====
// 把 NickResolver 的 DB 打开放在主线程
// ColdPool中查了DB但sNickDb是线程局部的
// 改成: 用全局静态DB + synchronized

static java.util.Map<String,String> sNickCache = new java.util.HashMap<>();
static android.database.sqlite.SQLiteDatabase sNickDb;
static final Object sNickLock = new Object();

static void initNickDb() {
    synchronized (sNickLock) {
        if (sNickDb != null) return;
        try {
            java.io.File mm = new java.io.File("/data/data/com.tencent.mm/MicroMsg");
            String[] dirs = mm.list();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d.length() == 32) {
                        sNickDb = android.database.sqlite.SQLiteDatabase.openDatabase(
                            mm.getAbsolutePath() + "/" + d + "/EnMicroMsg.db",
                            null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[NickDB] init FAIL: " + t);
        }
    }
}

static String getNick(String talker) {
    if (talker == null || talker.isEmpty()) return "";
    String cached = sNickCache.get(talker);
    if (cached != null) return cached;

    initNickDb();
    String name = null;

    synchronized (sNickLock) {
        if (sNickDb != null) {
            try {
                android.database.Cursor c = sNickDb.rawQuery(
                    "SELECT conRemark, nickname FROM rcontact WHERE username=? LIMIT 1",
                    new String[]{talker});
                if (c.moveToFirst()) {
                    String r = c.getString(0);
                    String n = c.getString(1);
                    if (r != null && !r.isEmpty()) name = r;
                    else if (n != null && !n.isEmpty()) name = n;
                }
                c.close();
            } catch (Throwable ignored) {}
        }
    }

    if (name == null) {
        name = talker.endsWith("@chatroom") ? "群聊" :
                talker.startsWith("gh_") ? "公众号" : talker;
    }
    if (name.length() > 20) name = name.substring(0, 20);

    sNickCache.put(talker, name);
    return name;
}


// ===== 修复6: 语音消息 — 不用MediaPlayer, 用微信自己的播放 =====
// MediaPlayer报错: "Prepare failed.: status=0x1"
// 原因: voice2目录的文件是Silk格式(voiceformat=4), 不是标准AMR
// 解决: 找到聊天界面里的语音View, performClick()

// 在MsgHook.onMsg()中type==34时:
// 1. 播报 "xxx发来语音"
// 2. 延迟1秒后自动点击语音气泡播放

static void onVoiceMsg(final String talker, final long msgId) {
    // 先TTS播报
    String name = getNick(talker);
    TtsEngine.speak(name + "发来语音");

    // 延迟自动点击播放
    sMainHandler.postDelayed(() -> {
        autoClickVoiceView(msgId);
    }, 1000);
}

static void autoClickVoiceView(long msgId) {
    try {
        // 获取当前Activity栈顶的ChattingUI
        android.app.Activity activity = getCurrentActivity();
        if (activity == null) return;

        // 找RecyclerView/ListView
        android.view.View root = activity.getWindow().getDecorView();
        android.view.View listView = findListView(root);
        if (listView == null) return;

        // 遍历子View找语音消息
        if (listView instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) listView;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                android.view.View child = vg.getChildAt(i);
                String clsName = child.getClass().getName();
                // 语音消息View通常包含 "Voice" 或 "voice"
                if (clsName.toLowerCase().contains("voice")) {
                    child.performClick();
                    XposedBridge.log("[AutoPlay] clicked voice view");
                    return;
                }
            }
        }
    } catch (Throwable t) {
        XposedBridge.log("[AutoPlay] err: " + t);
    }
}

static android.app.Activity getCurrentActivity() {
    try {
        Class<?> activityThreadCls = Class.forName("android.app.ActivityThread");
        Object at = activityThreadCls.getMethod("currentActivityThread").invoke(null);
        java.lang.reflect.Field activitiesField = activityThreadCls.getDeclaredField("mActivities");
        activitiesField.setAccessible(true);
        Map<?, ?> activities = (Map<?, ?>) activitiesField.get(at);
        for (Object record : activities.values()) {
            Class<?> recordCls = record.getClass();
            java.lang.reflect.Field pausedField = recordCls.getDeclaredField("paused");
            pausedField.setAccessible(true);
            if (!pausedField.getBoolean(record)) {
                java.lang.reflect.Field activityField = recordCls.getDeclaredField("activity");
                activityField.setAccessible(true);
                return (android.app.Activity) activityField.get(record);
            }
        }
    } catch (Throwable ignored) {}
    return null;
}

static android.view.View findListView(android.view.View root) {
    if (root instanceof android.widget.ListView ||
        root instanceof androidx.recyclerview.widget.RecyclerView) {
        return root;
    }
    if (root instanceof android.view.ViewGroup) {
        android.view.ViewGroup vg = (android.view.ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            android.view.View result = findListView(vg.getChildAt(i));
            if (result != null) return result;
        }
    }
    return null;
}


// ===== 修复7: 完整的 dispatch switch =====
static void dispatch(int type, String talker, String content) {
    String name = getNick(talker);
    boolean g = talker != null && talker.endsWith("@chatroom");
    String p = g ? "群聊" : "";

    String speak = null;
    switch (type) {
        case 1:  // 文字
            String t = cleanText(content);
            if (t == null || t.isEmpty()) return;
            if (t.contains("微信红包") || t.contains("已收款")) return;
            speak = p + name + "说：" + t;
            break;
        case 3:  // 图片
            speak = p + name + "发来一张照片";
            break;
        case 34: // 语音
            speak = p + name + "发来语音";
            break;
        case 42: // 名片 ★ 新增
            speak = p + name + "发来一张名片";
            break;
        case 43: // 视频
            speak = p + name + "发来一段视频";
            break;
        case 48: // 位置
            speak = p + name + "发来定位在：" + parseLocation(content);
            break;
        case 49: // AppMsg(链接/红包卡)
            if (content != null && content.contains("luckymoney"))
                speak = p + name + "发来一个红包";
            break;
    }
    if (speak != null && TtsEngine.isReady()) {
        TtsEngine.speak(speak);
        XposedBridge.log("[TTS] " + speak);
    }
}

static String cleanText(String s) {
    if (s == null || s.isEmpty()) return "";
    // 去群聊wxid前缀
    s = s.replaceFirst("^wxid_[a-z0-9]+:[\\u00A0\\s]*", "");
    s = s.replaceFirst("^[a-z][a-z0-9]+@chatroom:", "");
    s = s.replaceFirst("^[a-z0-9]+:", ""); // 去其他前缀如 oulele002:
    s = s.replace("<![CDATA[","").replace("]]>","");
    s = s.replaceAll("<[^>]+>","").replaceAll("https?://\\S+","链接");
    s = s.replaceAll("\\[\\w+\\]","").replace("\n"," ").trim();
    return s.length() > 200 ? s.substring(0,200)+"等" : s;
}
