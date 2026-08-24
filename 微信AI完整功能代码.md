# 微信 AI 聊天助手 —— LSPosed 模块完整业务代码

> 版本：v1.0 | 目标包：`com.tencent.mm`（微信）
> 已适配当前微信混淆结构（`e9`/`f9`/`fd5.d`/`pa5.n0`）
> 本文档即完整代码，可直接复制到 Android Studio 项目使用。

## 功能清单
1. ✅ 聊天总结（标题栏按钮 + 可视化图表报告）
2. ✅ 来消息推荐回复（顶部横幅，填入/直接发送双模式）
3. ✅ 多轮对话上下文记忆（可配置条数/时长）
4. ✅ 文本润色/语气转换
6. ✅ 情绪分析
7. ✅ 关键词/待办提取
8. ✅ 自定义 Prompt 模板
（❌ 翻译不做）

---

## 一、项目结构

```
WxAiAssistant/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/your/module/
│       │   ├── MainHook.java
│       │   ├── WxConst.java
│       │   ├── ConfigManager.java
│       │   ├── WxReflect.java
│       │   ├── MessageReader.java
│       │   ├── ai/AiClient.java
│       │   ├── ai/ChatMemory.java
│       │   ├── hook/ChatHooks.java
│       │   ├── feature/SummaryFeature.java
│       │   ├── feature/ReplyFeature.java
│       │   ├── feature/AiFeature.java
│       │   └── ui/
│       │       ├── AiControlActivity.java
│       │       ├── FloatingBall.java
│       │       ├── ReplyBanner.java
│       │       └── SummaryReportDialog.java
│       └── res/layout/activity_ai_control.xml
```

---

## 二、app/build.gradle

```groovy
plugins {
    id 'com.android.application'
}

android {
    namespace 'com.your.module'
    compileSdk 34

    defaultConfig {
        applicationId "com.your.module"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly 'de.robv.android.xposed:api:82'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.9.0'
}
```

---

## 三、AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:label="AI聊天助手"
        android:allowBackup="false">

        <activity
            android:name=".ui.AiControlActivity"
            android:exported="true"
            android:theme="@style/Theme.AppCompat.Light">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <meta-data android:name="xposedmodule" android:value="true" />
        <meta-data android:name="xposeddescription" android:value="微信AI聊天助手" />
        <meta-data android:name="xposedminversion" android:value="93" />
        <meta-data android:name="xposedscope" android:value="com.tencent.mm" />
    </application>
</manifest>
```

---

## 四、WxConst.java（微信混淆坐标——版本适配核心）

```java
package com.your.module;

public final class WxConst {
    public static final String WX_PACKAGE = "com.tencent.mm";

    public static final String CLS_MSG = "com.tencent.mm.storage.e9";
    public static final String F_TALKER    = "field_talker";
    public static final String F_CONTENT   = "field_content";
    public static final String F_TYPE      = "field_type";
    public static final String F_IS_SEND   = "field_isSend";
    public static final String F_CREATE    = "field_createTime";
    public static final String F_MSG_ID    = "field_msgId";
    public static final String M_GET_TALKER = "N0";
    public static final String M_GET_CONTENT = "j";

    public static final String CLS_STORAGE = "com.tencent.mm.storage.f9";
    public static final String M_QUERY = "Q1";
    public static final String M_INSERT = "H9";
    public static final String M_INSERT2 = "I9";

    public static final String CLS_KERNEL = "pa5.n0";
    public static final String M_GET_SERVICE = "c";
    public static final String CLS_MSG_SERVICE = "com.tencent.mm.plugin.messenger.foundation.h2";
    public static final String M_GET_STORAGE = "wj";

    public static final String CLS_CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";
    public static final String CLS_BASE_FRAGMENT = "com.tencent.mm.ui.chatting.BaseChattingUIFragment";
    public static final String F_CHAT_CONTEXT = "f";
    public static final String M_GET_TALKER_CTX = "t";

    public static final String CLS_CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter";

    public static final int TYPE_TEXT = 1;
    public static final int TYPE_IMAGE = 3;
    public static final int TYPE_VOICE = 34;
    public static final int TYPE_VIDEO = 43;
    public static final int TYPE_EMOJI = 47;
    public static final int TYPE_LOCATION = 48;
    public static final int TYPE_APPMSG = 49;
    public static final int TYPE_SYSTEM = 10000;

    private WxConst() {}
}
```

---

## 五、ConfigManager.java（配置中心）

```java
package com.your.module;

import android.content.Context;
import android.content.SharedPreferences;

public class ConfigManager {
    private static final String SP = "wx_ai_config";
    private static SharedPreferences sp;

    public static void init(Context ctx) {
        if (sp == null) sp = ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static boolean masterEnabled()     { return get("master", true); }
    public static void setMaster(boolean v)   { put("master", v); }

    public static boolean summaryEnabled()    { return get("summary", true); }
    public static boolean replyEnabled()      { return get("reply", true); }
    public static boolean memoryEnabled()     { return get("memory", true); }
    public static boolean polishEnabled()     { return get("polish", true); }
    public static boolean emotionEnabled()    { return get("emotion", true); }
    public static boolean keywordEnabled()    { return get("keyword", true); }
    public static boolean customPromptEnabled(){ return get("customPrompt", false); }

    public static void setSummary(boolean v)   { put("summary", v); }
    public static void setReply(boolean v)     { put("reply", v); }
    public static void setMemory(boolean v)    { put("memory", v); }
    public static void setPolish(boolean v)    { put("polish", v); }
    public static void setEmotion(boolean v)   { put("emotion", v); }
    public static void setKeyword(boolean v)   { put("keyword", v); }
    public static void setCustomPrompt(boolean v){ put("customPrompt", v); }

    public static int provider()              { return get("provider", 0); }
    public static void setProvider(int v)     { put("provider", v); }

    public static String openaiBaseUrl()      { return getStr("openai_base", "https://api.openai.com"); }
    public static String openaiKey()          { return getStr("openai_key", ""); }
    public static String openaiModel()        { return getStr("openai_model", "gpt-4o-mini"); }
    public static String deepseekBaseUrl()    { return getStr("deepseek_base", "https://api.deepseek.com"); }
    public static String deepseekKey()        { return getStr("deepseek_key", ""); }
    public static String deepseekModel()      { return getStr("deepseek_model", "deepseek-chat"); }

    public static void setOpenaiBaseUrl(String v){ put("openai_base", v); }
    public static void setOpenaiKey(String v)   { put("openai_key", v); }
    public static void setOpenaiModel(String v) { put("openai_model", v); }
    public static void setDeepseekBaseUrl(String v){ put("deepseek_base", v); }
    public static void setDeepseekKey(String v) { put("deepseek_key", v); }
    public static void setDeepseekModel(String v){ put("deepseek_model", v); }

    public static String activeBaseUrl() { return provider() == 0 ? openaiBaseUrl() : deepseekBaseUrl(); }
    public static String activeKey()     { return provider() == 0 ? openaiKey() : deepseekKey(); }
    public static String activeModel()   { return provider() == 0 ? openaiModel() : deepseekModel(); }

    public static int summaryCount()         { return get("summary_count", 200); }
    public static void setSummaryCount(int v){ put("summary_count", v); }

    public static int memoryCount()          { return get("memory_count", 20); }
    public static void setMemoryCount(int v) { put("memory_count", v); }

    public static long memoryDurationMs()    { return get("memory_duration", 30L) * 60_000L; }
    public static void setMemoryDuration(int minutes){ put("memory_duration", (long) minutes); }

    public static int replyMode()            { return get("reply_mode", 0); }
    public static void setReplyMode(int v)   { put("reply_mode", v); }

    public static int replyCount()           { return get("reply_count", 3); }
    public static void setReplyCount(int v)  { put("reply_count", v); }

    public static float temperature()        { return get("temperature", 0.7f); }
    public static void setTemperature(float v){ put("temperature", v); }

    public static String promptSummary()     { return getStr("prompt_summary", DEF_SUMMARY); }
    public static String promptReply()       { return getStr("prompt_reply", DEF_REPLY); }
    public static String promptPolish()      { return getStr("prompt_polish", DEF_POLISH); }
    public static String promptEmotion()     { return getStr("prompt_emotion", DEF_EMOTION); }
    public static String promptKeyword()     { return getStr("prompt_keyword", DEF_KEYWORD); }
    public static void setPromptSummary(String v){ put("prompt_summary", v); }
    public static void setPromptReply(String v)  { put("prompt_reply", v); }
    public static void setPromptPolish(String v) { put("prompt_polish", v); }
    public static void setPromptEmotion(String v){ put("prompt_emotion", v); }
    public static void setPromptKeyword(String v){ put("prompt_keyword", v); }

    public static final String DEF_SUMMARY =
        "你是聊天记录分析助手。请对以下聊天记录做总结，输出格式：\n" +
        "【核心话题】一句话概括\n【要点】\n- 要点1\n- 要点2\n【待办】\n- 待办1\n【情绪基调】正面/中性/负面\n请用简体中文，简洁。";

    public static final String DEF_REPLY =
        "根据以下聊天上下文，生成 %d 条合适的回复话术，语气自然、符合语境，直接输出每句话（每行一条，不要编号、不要解释）。";

    public static final String DEF_POLISH =
        "请把下面这段话润色改写，语气：%s。只输出改写后的文本，不要解释。\n原文：";

    public static final String DEF_EMOTION =
        "分析下面这段聊天内容的情绪倾向，输出：情绪=正面/中性/负面；强度=1-5；一句话理由。\n内容：";

    public static final String DEF_KEYWORD =
        "从下面这段聊天内容中提取关键信息，输出：\n【关键词】k1、k2、k3\n【待办事项】\n- 事项1\n- 事项2\n（没有则输出\"无\"）\n内容：";

    private static boolean get(String k, boolean def){ return sp.getBoolean(k, def); }
    private static int get(String k, int def){ return sp.getInt(k, def); }
    private static long get(String k, long def){ return sp.getLong(k, def); }
    private static float get(String k, float def){ return sp.getFloat(k, def); }
    private static String getStr(String k, String def){ return sp.getString(k, def); }
    private static void put(String k, boolean v){ sp.edit().putBoolean(k, v).apply(); }
    private static void put(String k, int v){ sp.edit().putInt(k, v).apply(); }
    private static void put(String k, long v){ sp.edit().putLong(k, v).apply(); }
    private static void put(String k, float v){ sp.edit().putFloat(k, v).apply(); }
    private static void put(String k, String v){ sp.edit().putString(k, v).apply(); }
}
```

---

## 六、WxReflect.java（微信反射工具）

```java
package com.your.module;

import de.robv.android.xposed.XposedHelpers;
import java.util.List;

public class WxReflect {
    private final ClassLoader cl;
    public WxReflect(ClassLoader cl) { this.cl = cl; }

    public Object getMsgStorage() {
        Class<?> kernel = XposedHelpers.findClass(WxConst.CLS_KERNEL, cl);
        Class<?> svcCls = XposedHelpers.findClass(WxConst.CLS_MSG_SERVICE, cl);
        Object service = XposedHelpers.callStaticMethod(kernel, WxConst.M_GET_SERVICE, svcCls);
        return XposedHelpers.callMethod(service, WxConst.M_GET_STORAGE);
    }

    @SuppressWarnings("unchecked")
    public List<Object> queryMessages(String talker, long createTime, int limit) {
        Object storage = getMsgStorage();
        return (List<Object>) XposedHelpers.callMethod(storage, WxConst.M_QUERY, talker, createTime, limit);
    }

    public static String talker(Object msg) {
        try { Object o = XposedHelpers.getObjectField(msg, WxConst.F_TALKER); if (o != null) return o.toString(); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, WxConst.M_GET_TALKER); } catch (Throwable ignored) {}
        return "";
    }
    public static String content(Object msg) {
        try { Object o = XposedHelpers.getObjectField(msg, WxConst.F_CONTENT); if (o != null) return o.toString(); } catch (Throwable ignored) {}
        try { return (String) XposedHelpers.callMethod(msg, WxConst.M_GET_CONTENT); } catch (Throwable ignored) {}
        return "";
    }
    public static int type(Object msg) {
        try { return XposedHelpers.getIntField(msg, WxConst.F_TYPE); } catch (Throwable ignored) { return -1; }
    }
    public static int isSend(Object msg) {
        try { return XposedHelpers.getIntField(msg, WxConst.F_IS_SEND); } catch (Throwable ignored) { return -1; }
    }
    public static long createTime(Object msg) {
        try { return XposedHelpers.getLongField(msg, WxConst.F_CREATE); } catch (Throwable ignored) { return 0L; }
    }
    public static long msgId(Object msg) {
        try { return XposedHelpers.getLongField(msg, WxConst.F_MSG_ID); } catch (Throwable ignored) { return 0L; }
    }
    public static boolean isIncomingText(Object msg) {
        return type(msg) == WxConst.TYPE_TEXT && isSend(msg) == 0;
    }
}
```

---

## 七、MessageReader.java（消息读取）

```java
package com.your.module;

import java.util.ArrayList;
import java.util.List;

public class MessageReader {
    public static class ChatMsg {
        public final String role;
        public final String name;
        public final String content;
        public final long time;
        public ChatMsg(String role, String name, String content, long time) {
            this.role = role; this.name = name; this.content = content; this.time = time;
        }
    }

    private final WxReflect reflect;
    public MessageReader(WxReflect reflect) { this.reflect = reflect; }

    public List<ChatMsg> readRecent(String talker, int limit) {
        List<Object> msgs = reflect.queryMessages(talker, System.currentTimeMillis(), limit);
        List<ChatMsg> out = new ArrayList<>();
        if (msgs == null) return out;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Object m = msgs.get(i);
            int t = WxReflect.type(m);
            if (t != WxConst.TYPE_TEXT) continue;
            int send = WxReflect.isSend(m);
            String content = WxReflect.content(m);
            if (content == null || content.isEmpty()) continue;
            String role = send == 1 ? "me" : (send == 2 ? "system" : "other");
            out.add(new ChatMsg(role, "", content, WxReflect.createTime(m)));
        }
        return out;
    }

    public static String toDialogText(List<ChatMsg> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMsg m : msgs) {
            String who = m.role.equals("me") ? "我" : (m.role.equals("system") ? "系统" : "对方");
            sb.append(who).append("：").append(m.content).append('\n');
        }
        return sb.toString();
    }
}
```

---

## 八、ai/AiClient.java（OpenAI/DeepSeek 客户端）

```java
package com.your.module.ai;

import com.your.module.ConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiClient {
    public interface Callback { void onResult(String text); void onError(String msg); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    public static class ChatMessage {
        public final String role;
        public final String content;
        public ChatMessage(String role, String content) { this.role = role; this.content = content; }
    }

    public static void chatAsync(String system, List<ChatMessage> msgs, Callback cb) {
        POOL.execute(() -> {
            try { cb.onResult(chatSync(system, msgs)); }
            catch (Exception e) { cb.onError(e.getMessage()); }
        });
    }

    public static String chatSync(String system, List<ChatMessage> msgs) throws Exception {
        String base = ConfigManager.activeBaseUrl().replaceAll("/+$", "");
        String url = base + "/v1/chat/completions";

        JSONArray arr = new JSONArray();
        if (system != null && !system.isEmpty()) {
            arr.put(new JSONObject().put("role", "system").put("content", system));
        }
        for (ChatMessage m : msgs) {
            arr.put(new JSONObject().put("role", m.role).put("content", m.content));
        }

        JSONObject body = new JSONObject();
        body.put("model", ConfigManager.activeModel());
        body.put("messages", arr);
        body.put("temperature", ConfigManager.temperature());

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + ConfigManager.activeKey());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        conn.disconnect();

        if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + sb);

        JSONObject resp = new JSONObject(sb.toString());
        return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
    }
}
```

---

## 九、ai/ChatMemory.java（多轮记忆）

```java
package com.your.module.ai;

import com.your.module.ConfigManager;
import com.your.module.MessageReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ChatMemory {
    private static final Map<String, Deque<MessageReader.ChatMsg>> MEM = new ConcurrentHashMap<>();

    public static void append(String talker, MessageReader.ChatMsg msg) {
        if (!ConfigManager.memoryEnabled()) return;
        Deque<MessageReader.ChatMsg> q = MEM.computeIfAbsent(talker, k -> new ArrayDeque<>());
        synchronized (q) { q.addLast(msg); evict(q); }
    }

    public static void preload(String talker, List<MessageReader.ChatMsg> msgs) {
        if (!ConfigManager.memoryEnabled()) return;
        Deque<MessageReader.ChatMsg> q = MEM.computeIfAbsent(talker, k -> new ArrayDeque<>());
        synchronized (q) { q.clear(); q.addAll(msgs); evict(q); }
    }

    public static List<MessageReader.ChatMsg> get(String talker) {
        Deque<MessageReader.ChatMsg> q = MEM.get(talker);
        if (q == null) return new ArrayList<>();
        synchronized (q) { return new ArrayList<>(q); }
    }

    public static void clear(String talker) { MEM.remove(talker); }
    public static void clearAll() { MEM.clear(); }

    private static void evict(Deque<MessageReader.ChatMsg> q) {
        int maxCount = ConfigManager.memoryCount();
        long maxDur = ConfigManager.memoryDurationMs();
        long now = System.currentTimeMillis();
        while (q.size() > maxCount) q.removeFirst();
        while (!q.isEmpty() && now - q.peekFirst().time > maxDur) q.removeFirst();
    }
}
```

---

## 十、hook/ChatHooks.java（Hook 挂载）

```java
package com.your.module.hook;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;

import com.your.module.ConfigManager;
import com.your.module.MessageReader;
import com.your.module.WxConst;
import com.your.module.WxReflect;
import com.your.module.ai.ChatMemory;
import com.your.module.feature.ReplyFeature;
import com.your.module.feature.SummaryFeature;
import com.your.module.ui.FloatingBall;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ChatHooks {
    public static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WxReflect reflect;
    private static String currentTalker = "";

    public static String currentTalker() { return currentTalker; }

    public static void install(XC_LoadPackage.LoadPackageParam lp) {
        reflect = new WxReflect(lp.classLoader);
        hookMessageInsert(lp);
        hookChatSession(lp);
        hookChattingUI(lp);
        installFloatingBall(lp);
    }

    private static void hookMessageInsert(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> storage = XposedHelpers.findClass(WxConst.CLS_STORAGE, lp.classLoader);
        Class<?> msgCls = XposedHelpers.findClass(WxConst.CLS_MSG, lp.classLoader);

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ConfigManager.masterEnabled() || !ConfigManager.replyEnabled()) return;
                Object msg = param.args[0];
                if (!WxReflect.isIncomingText(msg)) return;
                String talker = WxReflect.talker(msg);
                String content = WxReflect.content(msg);
                long time = WxReflect.createTime(msg);

                ChatMemory.append(talker, new MessageReader.ChatMsg("other", "", content, time));
                MAIN.post(() -> ReplyFeature.onIncoming(talker, content, lp.classLoader));
            }
        };

        try { XposedHelpers.findAndHookMethod(storage, WxConst.M_INSERT, msgCls, hook); }
        catch (Throwable t1) {
            try {
                XposedHelpers.findAndHookMethod(storage, WxConst.M_INSERT2, msgCls, boolean.class, hook);
            } catch (Throwable t2) {
                android.util.Log.e("WxAi", "消息入库 Hook 失败", t2);
            }
        }
    }

    private static void hookChatSession(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> base = XposedHelpers.findClass(WxConst.CLS_BASE_FRAGMENT, lp.classLoader);
        XposedHelpers.findAndHookMethod(base, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object chatCtx = XposedHelpers.getObjectField(param.thisObject, WxConst.F_CHAT_CONTEXT);
                    if (chatCtx == null) return;
                    String talker = (String) XposedHelpers.callMethod(chatCtx, WxConst.M_GET_TALKER_CTX);
                    if (talker != null && !talker.isEmpty()) {
                        currentTalker = talker;
                        MessageReader reader = new MessageReader(reflect);
                        ChatMemory.preload(talker, reader.readRecent(talker, ConfigManager.memoryCount()));
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static void hookChattingUI(XC_LoadPackage.LoadPackageParam lp) {
        Class<?> ui = XposedHelpers.findClass(WxConst.CLS_CHATTING_UI, lp.classLoader);
        XposedHelpers.findAndHookMethod(ui, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!ConfigManager.masterEnabled() || !ConfigManager.summaryEnabled()) return;
                Activity act = (Activity) param.thisObject;
                MAIN.postDelayed(() -> SummaryFeature.injectSummaryButton(act, lp.classLoader), 300);
            }
        });
    }

    private static void installFloatingBall(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> launcher = XposedHelpers.findClass("com.tencent.mm.ui.LauncherUI", lp.classLoader);
            XposedHelpers.findAndHookMethod(launcher, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!ConfigManager.masterEnabled()) return;
                    Activity act = (Activity) param.thisObject;
                    MAIN.post(() -> FloatingBall.show(act, lp.classLoader));
                }
            });
        } catch (Throwable t) { android.util.Log.e("WxAi", "悬浮球挂载失败", t); }
    }

    public static void fillInput(Activity act, String text) {
        try {
            EditText et = findEditText(act);
            if (et != null) {
                MAIN.post(() -> { et.setText(text); et.setSelection(text.length()); });
            }
        } catch (Throwable t) { android.util.Log.e("WxAi", "填入输入框失败", t); }
    }

    public static void fillAndSend(Activity act, String text) {
        fillInput(act, text);
        MAIN.postDelayed(() -> clickSend(act), 400);
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
            Class<?> footer = XposedHelpers.findClass(WxConst.CLS_CHAT_FOOTER, cl);
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
```

---

## 十一、feature/SummaryFeature.java（聊天总结 + 图表报告）

```java
package com.your.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.your.module.ConfigManager;
import com.your.module.MessageReader;
import com.your.module.WxReflect;
import com.your.module.ai.AiClient;
import com.your.module.hook.ChatHooks;
import com.your.module.ui.SummaryReportDialog;

import java.util.ArrayList;
import java.util.List;

public class SummaryFeature {

    public static void injectSummaryButton(Activity act, ClassLoader cl) {
        try {
            if (act == null) return;
            View root = act.getWindow().getDecorView();
            ViewGroup titleBar = findTitleBar(root);
            if (titleBar == null) return;
            if (root.getTag(0x7f000001) != null) return;
            root.setTag(0x7f000001, Boolean.TRUE);

            TextView btn = new TextView(act);
            btn.setText("🤖总结");
            btn.setTextColor(Color.WHITE);
            btn.setTextSize(14);
            btn.setPadding(20, 8, 20, 8);
            btn.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.END | Gravity.CENTER_VERTICAL);
            lp.rightMargin = 8;
            btn.setOnClickListener(v -> doSummary(act, cl));
            if (titleBar instanceof FrameLayout) titleBar.addView(btn, lp);
            else titleBar.addView(btn);
        } catch (Throwable t) {
            android.util.Log.e("WxAi", "注入总结按钮失败", t);
        }
    }

    public static void doSummary(Activity act, ClassLoader cl) {
        String talker = ChatHooks.currentTalker();
        if (talker.isEmpty()) {
            android.widget.Toast.makeText(act, "未获取到当前会话", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.Toast.makeText(act, "正在生成总结…", android.widget.Toast.LENGTH_SHORT).show();

        WxReflect reflect = new WxReflect(cl);
        MessageReader reader = new MessageReader(reflect);
        List<MessageReader.ChatMsg> msgs = reader.readRecent(talker, ConfigManager.summaryCount());
        if (msgs.isEmpty()) {
            android.widget.Toast.makeText(act, "暂无文本消息可总结", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String dialogText = MessageReader.toDialogText(msgs);
        final SummaryStats stats = SummaryStats.build(msgs);

        List<AiClient.ChatMessage> req = new ArrayList<>();
        req.add(new AiClient.ChatMessage("user", dialogText));
        AiClient.chatAsync(ConfigManager.promptSummary(), req, new AiClient.Callback() {
            @Override public void onResult(String text) {
                ChatHooks.MAIN.post(() -> SummaryReportDialog.show(act, text, stats));
            }
            @Override public void onError(String msg) {
                ChatHooks.MAIN.post(() -> android.widget.Toast.makeText(act, "总结失败: " + msg, android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    public static class SummaryStats {
        public int total, meCount, otherCount;
        public int[] hourDist = new int[24];

        public static SummaryStats build(List<MessageReader.ChatMsg> msgs) {
            SummaryStats s = new SummaryStats();
            s.total = msgs.size();
            java.util.Calendar c = java.util.Calendar.getInstance();
            for (MessageReader.ChatMsg m : msgs) {
                if (m.role.equals("me")) s.meCount++; else s.otherCount++;
                c.setTimeInMillis(m.time);
                s.hourDist[c.get(java.util.Calendar.HOUR_OF_DAY)]++;
            }
            return s;
        }
    }

    private static ViewGroup findTitleBar(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            String name = v.getClass().getName();
            if (name.contains("ActionBar") || name.contains("MMTitle")
                    || name.contains("Actionbar") || name.contains("TitleBar")) return g;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup r = findTitleBar(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
```

---

## 十二、feature/ReplyFeature.java（推荐回复）

```java
package com.your.module.feature;

import android.app.Activity;

import com.your.module.ConfigManager;
import com.your.module.MessageReader;
import com.your.module.ai.AiClient;
import com.your.module.ai.ChatMemory;
import com.your.module.hook.ChatHooks;
import com.your.module.ui.ReplyBanner;

import java.util.ArrayList;
import java.util.List;

public class ReplyFeature {
    private static String lastKey = "";

    public static void onIncoming(String talker, String content, ClassLoader cl) {
        if (content == null || content.isEmpty()) return;
        String key = talker + "|" + content.hashCode();
        if (key.equals(lastKey)) return;
        lastKey = key;

        List<MessageReader.ChatMsg> mem = ChatMemory.get(talker);
        StringBuilder ctx = new StringBuilder();
        for (MessageReader.ChatMsg m : mem) {
            String who = m.role.equals("me") ? "我" : "对方";
            ctx.append(who).append("：").append(m.content).append('\n');
        }

        String prompt = String.format(ConfigManager.promptReply(), ConfigManager.replyCount());
        List<AiClient.ChatMessage> req = new ArrayList<>();
        req.add(new AiClient.ChatMessage("user", ctx.toString()));

        AiClient.chatAsync(prompt, req, new AiClient.Callback() {
            @Override public void onResult(String text) {
                List<String> replies = parseReplies(text, ConfigManager.replyCount());
                Activity act = currentActivity();
                ChatHooks.MAIN.post(() -> ReplyBanner.show(act, replies, chosen -> {
                    if (ConfigManager.replyMode() == 1) {
                        ChatHooks.fillAndSend(act, chosen);
                    } else {
                        ChatHooks.fillInput(act, chosen);
                    }
                    ChatMemory.append(talker, new MessageReader.ChatMsg("me", "", chosen, System.currentTimeMillis()));
                }));
            }
            @Override public void onError(String msg) {
                android.util.Log.e("WxAi", "推荐回复失败: " + msg);
            }
        });
    }

    private static List<String> parseReplies(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\n")) {
            line = line.trim();
            line = line.replaceFirst("^[0-9]+[.、)）]\\s*", "");
            if (!line.isEmpty()) out.add(line);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) f.get(thread);
            for (Object o : map.values()) {
                java.lang.reflect.Field rec = o.getClass().getDeclaredField("activity");
                rec.setAccessible(true);
                Activity a = (Activity) rec.get(o);
                if (a != null && !a.isFinishing()) return a;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
```

---

## 十三、feature/AiFeature.java（润色/情绪/关键词/自定义Prompt）

```java
package com.your.module.feature;

import android.app.Activity;
import android.widget.EditText;
import android.widget.Toast;

import com.your.module.ConfigManager;
import com.your.module.ai.AiClient;
import com.your.module.hook.ChatHooks;

import java.util.ArrayList;
import java.util.List;

public class AiFeature {
    public enum Action { POLISH, EMOTION, KEYWORD, CUSTOM }

    public static void process(Activity act, Action action, String extra) {
        if (!ConfigManager.masterEnabled()) return;
        String input = getInputText(act);
        if (input == null || input.trim().isEmpty()) {
            Toast.makeText(act, "输入框为空", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (action) {
            case POLISH:
                if (!ConfigManager.polishEnabled()) { Toast.makeText(act, "润色功能未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, String.format(ConfigManager.promptPolish(), extra == null ? "自然" : extra) + input, true);
                break;
            case EMOTION:
                if (!ConfigManager.emotionEnabled()) { Toast.makeText(act, "情绪分析未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, ConfigManager.promptEmotion() + input, false);
                break;
            case KEYWORD:
                if (!ConfigManager.keywordEnabled()) { Toast.makeText(act, "关键词提取未开启", Toast.LENGTH_SHORT).show(); return; }
                run(act, ConfigManager.promptKeyword() + input, false);
                break;
            case CUSTOM:
                if (!ConfigManager.customPromptEnabled() || extra == null) {
                    Toast.makeText(act, "自定义 Prompt 未配置", Toast.LENGTH_SHORT).show();
                    return;
                }
                run(act, extra + "\n" + input, false);
                break;
        }
    }

    private static void run(Activity act, String prompt, boolean replaceInput) {
        Toast.makeText(act, "AI 处理中…", Toast.LENGTH_SHORT).show();
        List<AiClient.ChatMessage> req = new ArrayList<>();
        req.add(new AiClient.ChatMessage("user", prompt));
        AiClient.chatAsync(null, req, new AiClient.Callback() {
            @Override public void onResult(String text) {
                ChatHooks.MAIN.post(() -> {
                    if (replaceInput) ChatHooks.fillInput(act, text);
                    else showResultDialog(act, text);
                });
            }
            @Override public void onError(String msg) {
                ChatHooks.MAIN.post(() -> Toast.makeText(act, "失败: " + msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private static void showResultDialog(Activity act, String text) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(act);
        b.setTitle("AI 结果");
        b.setMessage(text);
        b.setPositiveButton("复制", (d, w) -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    act.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ai", text));
            Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
        });
        b.setNegativeButton("关闭", null);
        b.show();
    }

    private static String getInputText(Activity act) {
        try {
            android.view.View root = act.getWindow().getDecorView();
            EditText et = findEdit(root);
            return et != null ? et.getText().toString() : null;
        } catch (Throwable t) { return null; }
    }

    private static EditText findEdit(android.view.View v) {
        if (v instanceof EditText) return (EditText) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText r = findEdit(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
```

---

## 十四、ui/ReplyBanner.java（顶部横幅）

```java
package com.your.module.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class ReplyBanner {
    public interface OnPick { void onPick(String text); }

    public static void show(Activity act, List<String> replies, OnPick pick) {
        if (act == null || replies == null || replies.isEmpty()) return;

        ViewGroup root = act.getWindow().getDecorView().findViewById(android.R.id.content);
        View old = root.findViewWithTag("wx_ai_banner");
        if (old != null) root.removeView(old);

        LinearLayout banner = new LinearLayout(act);
        banner.setTag("wx_ai_banner");
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(16, 12, 16, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F5336D7B"));
        banner.setBackground(bg);

        TextView title = new TextView(act);
        title.setText("💡 推荐回复（点击使用）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        banner.addView(title);

        HorizontalScrollView hsv = new HorizontalScrollView(act);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);

        for (String r : replies) {
            TextView chip = new TextView(act);
            chip.setText(r);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(14);
            chip.setPadding(24, 14, 24, 14);
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(Color.parseColor("#FFFFFF"));
            cb.setAlpha(40);
            cb.setCornerRadius(60);
            chip.setBackground(cb);
            chip.setOnClickListener(v -> {
                dismiss(root);
                if (pick != null) pick.onPick(r);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 16;
            chips.addView(chip, lp);
        }
        hsv.addView(chips);
        banner.addView(hsv);

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        flp.topMargin = statusBarHeight(act);
        root.addView(banner, flp);

        banner.postDelayed(() -> dismiss(root), 15_000);
    }

    private static void dismiss(ViewGroup root) {
        View old = root.findViewWithTag("wx_ai_banner");
        if (old != null) root.removeView(old);
    }

    private static int statusBarHeight(Activity act) {
        int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? act.getResources().getDimensionPixelSize(id) : 0;
    }
}
```

---

## 十五、ui/FloatingBall.java（悬浮球 + 快捷菜单）

```java
package com.your.module.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.your.module.feature.AiFeature;

public class FloatingBall {
    private static View ball;
    private static LinearLayout menu;

    public static void show(Activity act, ClassLoader cl) {
        ViewGroup root = act.getWindow().getDecorView().findViewById(android.R.id.content);
        if (root.findViewWithTag("wx_ai_ball") != null) return;

        ball = makeBall(act);
        ball.setTag("wx_ai_ball");

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(120, 120, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = 16;
        root.addView(ball, lp);

        ball.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = v.getX() - e.getRawX();
                        dy = v.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.animate().x(e.getRawX() + dx).y(e.getRawY() + dy).setDuration(0).start();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() + dx - v.getX()) < 5) toggleMenu(act, root);
                        return true;
                }
                return false;
            }
        });
    }

    private static View makeBall(Activity act) {
        TextView b = new TextView(act);
        b.setText("AI");
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#FF576B95"));
        bg.setShape(GradientDrawable.OVAL);
        b.setBackground(bg);
        b.setAlpha(0.9f);
        return b;
    }

    private static void toggleMenu(Activity act, ViewGroup root) {
        if (menu != null && menu.getParent() != null) {
            root.removeView(menu); menu = null; return;
        }
        menu = new LinearLayout(act);
        menu.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0222222"));
        bg.setCornerRadius(20);
        menu.setBackground(bg);
        menu.setPadding(8, 8, 8, 8);

        addItem(act, menu, "📊 聊天总结", () -> {
            try {
                Class<?> f = Class.forName("com.your.module.feature.SummaryFeature");
                f.getMethod("doSummary", Activity.class, ClassLoader.class).invoke(null, act, act.getClassLoader());
            } catch (Throwable t) { android.util.Log.e("WxAi", "总结", t); }
        });
        addItem(act, menu, "✍️ 润色", () -> AiFeature.process(act, AiFeature.Action.POLISH, "自然"));
        addItem(act, menu, "🎭 情绪分析", () -> AiFeature.process(act, AiFeature.Action.EMOTION, null));
        addItem(act, menu, "🔑 关键词/待办", () -> AiFeature.process(act, AiFeature.Action.KEYWORD, null));
        addItem(act, menu, "⚙️ 控制面板", () -> {
            Intent i = new Intent(act, AiControlActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = 16;
        lp.bottomMargin = 200;
        root.addView(menu, lp);
    }

    private static void addItem(Activity act, LinearLayout menu, String label, Runnable r) {
        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(15);
        tv.setPadding(40, 24, 40, 24);
        tv.setOnClickListener(v -> { if (menu.getParent() != null) ((ViewGroup) menu.getParent()).removeView(menu); menu = null; r.run(); });
        menu.addView(tv);
    }
}
```

---

## 十六、ui/SummaryReportDialog.java（图表报告）

```java
package com.your.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.your.module.feature.SummaryFeature;

public class SummaryReportDialog {

    public static void show(Activity act, String summaryText, SummaryFeature.SummaryStats stats) {
        Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 80, 40, 40);
        root.setBackgroundColor(Color.parseColor("#FF111827"));

        TextView title = new TextView(act);
        title.setText("📊 聊天总结报告");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(card(act, "💬 总消息数", String.valueOf(stats.total)));
        root.addView(card(act, "👤 我 / 对方", stats.meCount + " / " + stats.otherCount));

        TextView hTitle = new TextView(act);
        hTitle.setText("⏰ 24小时活跃分布");
        hTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        hTitle.setTextSize(14);
        hTitle.setPadding(0, 24, 0, 8);
        root.addView(hTitle);
        root.addView(new BarChartView(act, stats.hourDist));

        TextView pTitle = new TextView(act);
        pTitle.setText("🎯 发言占比");
        pTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        pTitle.setTextSize(14);
        pTitle.setPadding(0, 24, 0, 8);
        root.addView(pTitle);
        root.addView(new PieChartView(act, stats.meCount, stats.otherCount));

        TextView sTitle = new TextView(act);
        sTitle.setText("📝 AI 总结");
        sTitle.setTextColor(Color.parseColor("#FF9CA3AF"));
        sTitle.setTextSize(14);
        sTitle.setPadding(0, 24, 0, 8);
        root.addView(sTitle);

        TextView body = new TextView(act);
        body.setText(summaryText);
        body.setTextColor(Color.WHITE);
        body.setTextSize(15);
        body.setLineSpacing(6, 1.1f);
        root.addView(body);

        TextView close = new TextView(act);
        close.setText("关闭");
        close.setTextColor(Color.WHITE);
        close.setTextSize(16);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, 40, 0, 20);
        close.setOnClickListener(v -> d.dismiss());
        root.addView(close);

        scroll.addView(root);
        d.setContentView(scroll);
        d.show();
    }

    private static View card(Activity act, String label, String value) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setPadding(24, 20, 24, 20);
        c.setBackgroundColor(Color.parseColor("#FF1F2937"));
        TextView l = new TextView(act);
        l.setText(label);
        l.setTextColor(Color.parseColor("#FF9CA3AF"));
        l.setTextSize(15);
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextColor(Color.parseColor("#FF60A5FA"));
        v.setTextSize(18);
        v.setGravity(Gravity.END);
        c.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        c.setLayoutParams(lp);
        return c;
    }

    static class BarChartView extends View {
        final int[] data;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public BarChartView(Activity a, int[] d) { super(a); this.data = d; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            int max = 1;
            for (int x : data) max = Math.max(max, x);
            float bw = w / 24f;
            for (int i = 0; i < 24; i++) {
                float bh = (data[i] / (float) max) * (h - 20);
                paint.setColor(Color.parseColor("#FF3B82F6"));
                c.drawRect(i * bw + 2, h - bh, (i + 1) * bw - 2, h, paint);
            }
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(View.MeasureSpec.getSize(w), 320);
        }
    }

    static class PieChartView extends View {
        final int a, b;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        public PieChartView(Activity act, int a, int b) { super(act); this.a = a; this.b = b; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int total = Math.max(1, a + b);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - 20;
            float sweepA = a * 360f / total;
            paint.setColor(Color.parseColor("#FF60A5FA"));
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90, sweepA, true, paint);
            paint.setColor(Color.parseColor("#FF34D399"));
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90 + sweepA, 360 - sweepA, true, paint);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(View.MeasureSpec.getSize(w), 320);
        }
    }
}
```

---

## 十七、ui/AiControlActivity.java（控制面板）

```java
package com.your.module.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.your.module.ConfigManager;

public class AiControlActivity extends AppCompatActivity {

    private CheckBox cbMaster, cbSummary, cbReply, cbMemory, cbPolish, cbEmotion, cbKeyword, cbCustomPrompt;
    private RadioButton rbOpenai, rbDeepseek;
    private EditText etOpenaiBase, etOpenaiKey, etOpenaiModel;
    private EditText etDeepseekBase, etDeepseekKey, etDeepseekModel;
    private EditText etPromptSummary, etPromptReply, etPromptPolish, etPromptEmotion, etPromptKeyword;
    private SeekBar sbSummaryCount, sbMemoryCount, sbMemoryDuration, sbReplyCount;
    private TextView tvSummaryCount, tvMemoryCount, tvMemoryDuration, tvReplyCount;
    private RadioButton rbFill, rbSend;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_ai_control);
        bind();
        load();
        wire();
    }

    private void bind() {
        cbMaster = findViewById(R.id.cb_master);
        cbSummary = findViewById(R.id.cb_summary);
        cbReply = findViewById(R.id.cb_reply);
        cbMemory = findViewById(R.id.cb_memory);
        cbPolish = findViewById(R.id.cb_polish);
        cbEmotion = findViewById(R.id.cb_emotion);
        cbKeyword = findViewById(R.id.cb_keyword);
        cbCustomPrompt = findViewById(R.id.cb_custom_prompt);
        rbOpenai = findViewById(R.id.rb_openai);
        rbDeepseek = findViewById(R.id.rb_deepseek);
        etOpenaiBase = findViewById(R.id.et_openai_base);
        etOpenaiKey = findViewById(R.id.et_openai_key);
        etOpenaiModel = findViewById(R.id.et_openai_model);
        etDeepseekBase = findViewById(R.id.et_deepseek_base);
        etDeepseekKey = findViewById(R.id.et_deepseek_key);
        etDeepseekModel = findViewById(R.id.et_deepseek_model);
        etPromptSummary = findViewById(R.id.et_prompt_summary);
        etPromptReply = findViewById(R.id.et_prompt_reply);
        etPromptPolish = findViewById(R.id.et_prompt_polish);
        etPromptEmotion = findViewById(R.id.et_prompt_emotion);
        etPromptKeyword = findViewById(R.id.et_prompt_keyword);
        sbSummaryCount = findViewById(R.id.sb_summary_count);
        sbMemoryCount = findViewById(R.id.sb_memory_count);
        sbMemoryDuration = findViewById(R.id.sb_memory_duration);
        sbReplyCount = findViewById(R.id.sb_reply_count);
        tvSummaryCount = findViewById(R.id.tv_summary_count);
        tvMemoryCount = findViewById(R.id.tv_memory_count);
        tvMemoryDuration = findViewById(R.id.tv_memory_duration);
        tvReplyCount = findViewById(R.id.tv_reply_count);
        rbFill = findViewById(R.id.rb_fill);
        rbSend = findViewById(R.id.rb_send);
    }

    private void load() {
        cbMaster.setChecked(ConfigManager.masterEnabled());
        cbSummary.setChecked(ConfigManager.summaryEnabled());
        cbReply.setChecked(ConfigManager.replyEnabled());
        cbMemory.setChecked(ConfigManager.memoryEnabled());
        cbPolish.setChecked(ConfigManager.polishEnabled());
        cbEmotion.setChecked(ConfigManager.emotionEnabled());
        cbKeyword.setChecked(ConfigManager.keywordEnabled());
        cbCustomPrompt.setChecked(ConfigManager.customPromptEnabled());
        if (ConfigManager.provider() == 0) rbOpenai.setChecked(true); else rbDeepseek.setChecked(true);
        etOpenaiBase.setText(ConfigManager.openaiBaseUrl());
        etOpenaiKey.setText(ConfigManager.openaiKey());
        etOpenaiModel.setText(ConfigManager.openaiModel());
        etDeepseekBase.setText(ConfigManager.deepseekBaseUrl());
        etDeepseekKey.setText(ConfigManager.deepseekKey());
        etDeepseekModel.setText(ConfigManager.deepseekModel());
        etPromptSummary.setText(ConfigManager.promptSummary());
        etPromptReply.setText(ConfigManager.promptReply());
        etPromptPolish.setText(ConfigManager.promptPolish());
        etPromptEmotion.setText(ConfigManager.promptEmotion());
        etPromptKeyword.setText(ConfigManager.promptKeyword());
        sbSummaryCount.setProgress(ConfigManager.summaryCount());
        sbMemoryCount.setProgress(ConfigManager.memoryCount());
        sbMemoryDuration.setProgress((int)(ConfigManager.memoryDurationMs() / 60_000L));
        sbReplyCount.setProgress(ConfigManager.replyCount());
        if (ConfigManager.replyMode() == 1) rbSend.setChecked(true); else rbFill.setChecked(true);
        refreshLabels();
    }

    private void wire() {
        cbMaster.setOnCheckedChangeListener((v, c) -> ConfigManager.setMaster(c));
        cbSummary.setOnCheckedChangeListener((v, c) -> ConfigManager.setSummary(c));
        cbReply.setOnCheckedChangeListener((v, c) -> ConfigManager.setReply(c));
        cbMemory.setOnCheckedChangeListener((v, c) -> ConfigManager.setMemory(c));
        cbPolish.setOnCheckedChangeListener((v, c) -> ConfigManager.setPolish(c));
        cbEmotion.setOnCheckedChangeListener((v, c) -> ConfigManager.setEmotion(c));
        cbKeyword.setOnCheckedChangeListener((v, c) -> ConfigManager.setKeyword(c));
        cbCustomPrompt.setOnCheckedChangeListener((v, c) -> ConfigManager.setCustomPrompt(c));

        rbOpenai.setOnCheckedChangeListener((v, c) -> { if (c) ConfigManager.setProvider(0); });
        rbDeepseek.setOnCheckedChangeListener((v, c) -> { if (c) ConfigManager.setProvider(1); });
        rbFill.setOnCheckedChangeListener((v, c) -> { if (c) ConfigManager.setReplyMode(0); });
        rbSend.setOnCheckedChangeListener((v, c) -> { if (c) ConfigManager.setReplyMode(1); });

        SeekBar.OnSeekBarChangeListener l1 = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { refreshLabels(); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { saveSliders(); }
        };
        sbSummaryCount.setOnSeekBarChangeListener(l1);
        sbMemoryCount.setOnSeekBarChangeListener(l1);
        sbMemoryDuration.setOnSeekBarChangeListener(l1);
        sbReplyCount.setOnSeekBarChangeListener(l1);

        Button save = findViewById(R.id.btn_save);
        save.setOnClickListener(v -> {
            ConfigManager.setOpenaiBaseUrl(etOpenaiBase.getText().toString());
            ConfigManager.setOpenaiKey(etOpenaiKey.getText().toString());
            ConfigManager.setOpenaiModel(etOpenaiModel.getText().toString());
            ConfigManager.setDeepseekBaseUrl(etDeepseekBase.getText().toString());
            ConfigManager.setDeepseekKey(etDeepseekKey.getText().toString());
            ConfigManager.setDeepseekModel(etDeepseekModel.getText().toString());
            ConfigManager.setPromptSummary(etPromptSummary.getText().toString());
            ConfigManager.setPromptReply(etPromptReply.getText().toString());
            ConfigManager.setPromptPolish(etPromptPolish.getText().toString());
            ConfigManager.setPromptEmotion(etPromptEmotion.getText().toString());
            ConfigManager.setPromptKeyword(etPromptKeyword.getText().toString());
            saveSliders();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });
    }

    private void saveSliders() {
        ConfigManager.setSummaryCount(sbSummaryCount.getProgress());
        ConfigManager.setMemoryCount(sbMemoryCount.getProgress());
        ConfigManager.setMemoryDuration(sbMemoryDuration.getProgress());
        ConfigManager.setReplyCount(sbReplyCount.getProgress());
    }

    private void refreshLabels() {
        tvSummaryCount.setText("总结抓取条数：" + sbSummaryCount.getProgress() + " 条");
        tvMemoryCount.setText("记忆条数：" + sbMemoryCount.getProgress() + " 条");
        tvMemoryDuration.setText("记忆时长：" + sbMemoryDuration.getProgress() + " 分钟");
        tvReplyCount.setText("推荐回复条数：" + sbReplyCount.getProgress() + " 条");
    }
}
```

---

## 十八、res/layout/activity_ai_control.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true">

    <LinearLayout
        android:orientation="vertical"
        android:padding="20dp"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">

        <TextView android:text="AI 聊天助手控制面板"
            android:textSize="20sp" android:textStyle="bold"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <CheckBox android:id="@+id/cb_master" android:text="总开关（启用所有 AI 功能）"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <TextView android:text="── 功能开关 ──" android:layout_marginTop="12dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_summary" android:text="聊天总结" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_reply" android:text="来消息推荐回复" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_memory" android:text="多轮上下文记忆" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_polish" android:text="文本润色" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_emotion" android:text="情绪分析" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_keyword" android:text="关键词/待办提取" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <CheckBox android:id="@+id/cb_custom_prompt" android:text="自定义 Prompt 模板" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <TextView android:text="── AI 后端 ──" android:layout_marginTop="12dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <RadioButton android:id="@+id/rb_openai" android:text="OpenAI" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_openai_base" android:hint="OpenAI Base URL" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_openai_key" android:hint="OpenAI API Key" android:inputType="textPassword" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_openai_model" android:hint="OpenAI 模型名" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <RadioButton android:id="@+id/rb_deepseek" android:text="DeepSeek" android:layout_marginTop="8dp" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_deepseek_base" android:hint="DeepSeek Base URL" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_deepseek_key" android:hint="DeepSeek API Key" android:inputType="textPassword" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_deepseek_model" android:hint="DeepSeek 模型名" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <TextView android:text="── AI 参数 ──" android:layout_marginTop="12dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <TextView android:id="@+id/tv_summary_count" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <SeekBar android:id="@+id/sb_summary_count" android:max="500" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <TextView android:id="@+id/tv_memory_count" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <SeekBar android:id="@+id/sb_memory_count" android:max="100" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <TextView android:id="@+id/tv_memory_duration" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <SeekBar android:id="@+id/sb_memory_duration" android:max="120" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <TextView android:id="@+id/tv_reply_count" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <SeekBar android:id="@+id/sb_reply_count" android:max="6" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <TextView android:text="── 回复模式 ──" android:layout_marginTop="12dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <RadioButton android:id="@+id/rb_fill" android:text="仅填入输入框" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <RadioButton android:id="@+id/rb_send" android:text="直接发送" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <TextView android:text="── 自定义 Prompt 模板 ──" android:layout_marginTop="12dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_prompt_summary" android:hint="总结 Prompt" android:minLines="2" android:gravity="top" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_prompt_reply" android:hint="推荐回复 Prompt（%d 会被替换为条数）" android:minLines="2" android:gravity="top" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_prompt_polish" android:hint="润色 Prompt（%s 会被替换为语气）" android:minLines="2" android:gravity="top" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_prompt_emotion" android:hint="情绪分析 Prompt" android:minLines="2" android:gravity="top" android:layout_width="match_parent" android:layout_height="wrap_content"/>
        <EditText android:id="@+id/et_prompt_keyword" android:hint="关键词提取 Prompt" android:minLines="2" android:gravity="top" android:layout_width="match_parent" android:layout_height="wrap_content"/>

        <Button android:id="@+id/btn_save" android:text="保存全部配置" android:layout_marginTop="16dp"
            android:layout_width="match_parent" android:layout_height="wrap_content"/>
    </LinearLayout>
</ScrollView>
```

---

## 十九、MainHook.java（模块入口）

```java
package com.your.module;

import android.app.Application;
import android.content.Context;

import com.your.module.hook.ChatHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!WxConst.WX_PACKAGE.equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ConfigManager.init((Context) param.args[0]);
            }
        });

        ChatHooks.install(lpparam);

        android.util.Log.i("WxAi", "微信 AI 聊天助手已加载");
    }
}
```

---

## 二十、集成到你现有模块的步骤

1. 复制上述所有 `.java` 到你的模块 `src/main/java/com/your/module/` 下，包名改成你自己的。
2. 合并 `xposedmodule` 等 meta-data 到你的 `AndroidManifest.xml`，注册 `AiControlActivity`。
3. 在你现有面板加入口跳转 `AiControlActivity`，或把 `activity_ai_control.xml` 的控件搬进你的面板页。
4. 依赖：`compileOnly 'de.robv.android.xposed:api:82'` + appcompat。
5. LSPosed 勾选微信 `com.tencent.mm`，重启微信。

---

## 二十一、逆向坐标对照表（微信升级后核对）

| 用途 | 当前坐标 | 说明 |
|---|---|---|
| 消息类 | `com.tencent.mm.storage.e9` | 继承 `dm.c8` |
| 会话ID字段 | `field_talker` | String |
| 内容字段 | `field_content` | String |
| 类型字段 | `field_type` | 1=文本 |
| 方向字段 | `field_isSend` | 0=收 1=发 |
| 时间字段 | `field_createTime` | long(ms) |
| 存储类 | `com.tencent.mm.storage.f9` | MsgInfoStorage |
| 查询方法 | `Q1(talker, time, limit)` | 返回 List，倒序 |
| 入库钩子 | `H9(e9)` / `I9(e9, boolean)` | 来消息必经 |
| 服务定位 | `pa5.n0.c(Class)` | 内核 ServiceManager |
| 存储服务接口 | `...foundation.h2` | `.wj()` 返回 f9 |
| 聊天Fragment | `...chatting.BaseChattingUIFragment` | 字段 `f`=ChatContext |
| 取当前talker | `fd5.d.t()` | ChatContext 方法 |
| 输入框 | `...pluginsdk.ui.chat.ChatFooter` | 找 EditText 填入 |

> 微信混淆随版本变化，功能失效时用 LSPilot 的 search_classes/decompile_method 重新定位上表坐标。

---

## 二十二、常见问题

**Q1 填入输入框没反应？** 微信输入框是 MMEditText（EditText 子类），findEditTextRecursive 已用 instanceof EditText 兜底。若不行，检查是否在聊天页、ChatFooter 是否创建。

**Q2 直接发送失败？** clickSend 依赖 ChatFooter 发送方法名（send/D/H/G 遍历）或发送按钮 contentDescription。真机调试时打印 ChatFooter 方法列表补充即可。

**Q3 AI 请求失败？** 检查 Base URL（如 https://api.deepseek.com，不要带 /v1 结尾）、Key、网络。

**Q4 总结按钮没出现？** 标题栏类名匹配（ActionBar/MMTitle）可能不匹配，用 LSPilot read_layout/view_strings 确认标题栏类名，或用悬浮球菜单里的「聊天总结」入口。
