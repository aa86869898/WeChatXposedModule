package com.leshao.v3.hook;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义聊天气泡替换。
 *
 * <p>v3.0.100 根因修复（依据《微信聊天对话气泡替换精准深挖报告》§0.1）：文本气泡承载视图
 * {@code com.tencent.mm.ui.widget.MMNeat7extView}（继承 {@code NeatTextView extends android.view.View}）
 * 自己<b>重写</b>了 {@code setBackgroundResource(int)} 与 {@code setBackground(Drawable)}，
 * Java 虚分派直接进入其 override，<b>永不落到 {@code android.view.View} 实现</b>。
 * 因此旧实现对基类 {@code View.setBackground*} 的 hook 对文本气泡一次都不触发——这是
 * 「自定义文字消息气泡替换不生效」的真正断点。</p>
 *
 * <p>修复策略（报告 §3 方案 1，唯一全场景可靠）：</p>
 * <ol>
 *   <li>直接对真实类 {@code MMNeat7extView} 的 {@code setBackgroundResource(int)} /
 *       {@code setBackground(Drawable)} 安装 hook；</li>
 *   <li>{@code setBackgroundResource} 的 after 阶段按 resId 白名单（报告 §2：普通/发送中/链接共 6 个）
 *       判定收/发方向，覆盖为用户选定的气泡图片；</li>
 *   <li>自定义图片统一由 {@link BubbleDrawableFactory} 构造成 native 9-patch，
 *       保证 MMNeat7extView 的测量/内边距与拉伸正确；</li>
 *   <li>同时保留基类 / ImageView / ViewHolder 绑定方法 / onLayout 兜底，
 *       覆盖图片、语音等不重写背景方法的容器气泡。</li>
 * </ol>
 */
public final class ChatBubbleHook {

    public static final String TAG = "Bubble";
    public static final String K_ENABLED = "ls_bubble_enabled";
    public static final String K_FROM_PATH = "ls_bubble_from_path";
    public static final String K_TO_PATH = "ls_bubble_to_path";
    /** v3.0.128：气泡内文字颜色（0 = 不修改，保持微信原生）。对方/自己各一份。 */
    public static final String K_FROM_TEXT_COLOR = "ls_bubble_from_text_color";
    public static final String K_TO_TEXT_COLOR = "ls_bubble_to_text_color";
    /** v3.0.150（《新聊天气泡替换.md》v8 §12.3）：聊天记录内时间分隔条文字颜色（0 = 不修改）。 */
    public static final String K_TIME_TEXT_COLOR = "ls_bubble_time_text_color";
    /** v3.0.150（《新聊天气泡替换.md》v8 §12.5）：群聊成员昵称文字颜色（0 = 不修改）。 */
    public static final String K_NICK_TEXT_COLOR = "ls_bubble_nick_text_color";
    /** h0.timeTV（0x7f0a10b0）时间分隔条 id，to.a findViewById 实证。 */
    public static final int ID_TIME_TV = 2131366064;
    /** h0.userTV（0x7f0a10be）群聊成员昵称 id，to.a findViewById 实证。 */
    public static final int ID_NICK_TV = 2131366078;
    /** v3.0.161（mq.b Smali 实证）：语音 holder 字段表 —— mq.e 自己侧 AnimImageView、mq.u 对方侧第二个
     *  AnimImageView、mq.D TextView(StateListDrawable 备用承载)。方向唯一可靠源是 mq.b 的 z/z2。 */
    public static final int ID_MQ_E = 2131366091;
    public static final int ID_MQ_U = 2131366096;
    public static final int ID_MQ_D = 2131365816;

    public static final int KIND_FROM = 0;
    public static final int KIND_TO = 1;

    private static final int REQ_PICK_BUBBLE = 0x7F02;

    private static volatile boolean sEnabled = false;
    private static volatile String sFromPath;
    private static volatile String sToPath;
    private static volatile boolean sHooked = false;
    private static volatile boolean sResultHooked = false;

    // v3.0.115：View 树 dump 仅用于早期定位真实气泡 View，属调试功能。
    // 常开会在每次 RecyclerView.onLayout 遍历并记录整棵 View 树，导致启动卡顿与日志暴涨，默认关闭。
    private static final boolean DEBUG_VIEW_DUMP = false;

    // ==================== v3.0.125 运行时自校准 ====================
    // 用户运行时构建与本模块静态分析的 base.apk 不一致：日志实锤
    //   - MMNeat7extView.setBackgroundResource 全日志 0 触发；
    //   - viewitems.to.b 命中的是 android.widget.TextView(neat=false)；
    //   - resId 2131231925->mh / 2131232060->o_ 均为混淆短名，chatfrom_bg 名字查不到。
    // 因此不再预设白名单，改为在**本机**全量记录所有气泡背景设置点(view类/resId/资源名/坐标/父链)，
    // 导出真实 (resId, viewClass, x) 映射后再固化。CAL_MAX 为去重后最大记录条数(防日志暴涨)。
    // v3.0.142：校准完成，关闭运行时自校准日志（此前 CALIBRATE=true 使每个 getDrawable/
// setBackground 都刷屏日志，真机性能受损）。核心替换逻辑不依赖校准开关。
    private static final boolean CALIBRATE = false;
    private static final int CAL_MAX = 600;
    private static final java.util.Set<String> sCalSeen =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final java.util.concurrent.atomic.AtomicInteger sCalCount =
            new java.util.concurrent.atomic.AtomicInteger();

    // ==================== v3.0.132 三层门控（《聊天气泡修复文档》） ====================
    // 第一层：聊天 item 根 keyed tag（0x7f0a103c = 2131365948）+ ChattingItem 基类 b0，
    // 沿父链检查，非聊天 item 一律短路（主页会话列表/输入框/表情面板无此 tag）。
    private static final int TAG_ITEM = 0x7f0a103c;
    private static volatile Class<?> sItemCls;
    private static volatile ClassLoader sHookCl;
    // 第二层：BUBBLE 捕获表 —— 只记录微信亲自贴过气泡的 View（viewitems.to.b 的 holder.b、
    // AnimImageView.setType），重盖/补色/换背景只动这张表里的 View，彻底删除几何启发式。
    private static final java.util.Map<View, Boolean> sBubble = new java.util.WeakHashMap<>();
    /** v3.0.136：findBubbleViewInTree 单次调用预算（防遍历整棵 View 树卡死主线程）。 */
    private static int sTreeVisitCount = 0;
    /** v3.0.155：语音气泡替换后延迟校验（每个 View 只记一次，定位"替换后又被盖回默认"）。 */
    private static final java.util.Map<View, Boolean> sAnimVerifyDone = new java.util.WeakHashMap<>();
    /** v3.0.158：文本气泡 setBackgroundResource 路径替换后延迟校验（每个 View 只记一次）。 */
    private static final java.util.Set<View> sBgVerify =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<View, Boolean>());

    // v3.0.128 气泡内文字颜色（0 = 不修改）。对方/自己各一份，运行时自校准后固化。
    private static volatile int sFromTextColor = 0;
    private static volatile int sToTextColor = 0;
    // v3.0.150 聊天时间线 / 群聊昵称颜色（0 = 不修改，保持微信原生）。
    private static volatile int sTimeTextColor = 0;
    private static volatile int sNickTextColor = 0;

    // 气泡资源 ID（XML 兜底路径用）。优先 getIdentifier 动态解析，失败回退文档已知值
    private static volatile int sFromResId = 2131231925; // chatfrom_bg
    private static volatile int sToResId = 2131232060;   // chatto_bg

    // 气泡图片缓存
    private static volatile Bitmap sFromBmp;
    private static volatile Bitmap sToBmp;
    private static volatile String sFromBmpPath;
    private static volatile String sToBmpPath;

    // 已构建的自定义气泡 Drawable 缓存：缓存 ConstantState，每次使用时 newDrawable() 出新实例，
    // 避免同一 Drawable 实例被大量 View 共享导致 bounds/callback/state 串扰（报告 §0.2）。
    private static final Object sDrawableCache = new Object();
    private static volatile Drawable.ConstantState sFromDrawableState;
    private static volatile Drawable.ConstantState sToDrawableState;
    private static volatile String sFromDrawablePath;
    private static volatile String sToDrawablePath;
    private static volatile boolean sFirstResolverLogged;

    // 微信原始气泡 Drawable 基准
    private static volatile Drawable sFromBaseDrawable;
    private static volatile Drawable sToBaseDrawable;

    // v3.0.135：微信 8.0.78 文字气泡实际使用的资源可能与 chatfrom_bg/chatto_bg 不同
    // （X2C 布局中 MMNeat7extView 背景为 StateListDrawable，日志证实其 constantState
    //  与 res.getDrawable(2131231925/2131232060) 不匹配）。扩展加载相邻候选资源
    //  （mi/ob/链接/发送中等），供 constantState 匹配识别。
    private static final int[] BASE_FROM_CANDIDATE_IDS = {
            2131231925, 2131231926, 2131231944, 2131231841
    };
    private static final int[] BASE_TO_CANDIDATE_IDS = {
            2131232060, 2131232062, 2131232070, 2131231895
    };
    private static final java.util.List<Drawable> sExtraFromBaseDrawables =
            new java.util.ArrayList<>(4);
    private static final java.util.List<Drawable> sExtraToBaseDrawables =
            new java.util.ArrayList<>(4);

    // v3.0.94：文本气泡在 setBackgroundResource 替换后可能被微信布局阶段再次覆盖。
    // 记录已替换的气泡 View 与自定义 Drawable，在聊天列表 onLayout 后强制恢复，
    // 解决文本消息（TextView w=0 h=0 时替换）最终仍显示原生气泡的问题。
    private static final java.util.Map<View, Drawable> sBubbleViews =
            new java.util.WeakHashMap<>();

    /** v3.0.128：记录已替换气泡的方向（收/发），供补盖时同步文字颜色。 */
    private static final java.util.Map<View, Integer> sBubbleKind =
            new java.util.WeakHashMap<>();

    private static volatile BubblePickCallback sPickCb;
    private static volatile int sPickKind = KIND_FROM;

    public interface BubblePickCallback {
        void onPick(String path);
    }

    private ChatBubbleHook() {}

    // ---------------- 配置 ----------------

    public static boolean isEnabled() {
        SharedPreferences sp = safePrefs();
        return sp != null && sp.getBoolean(K_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        putBool(K_ENABLED, on);
        sEnabled = on;
        LogWriter.log(TAG, "setEnabled=" + on);
    }

    public static String getFromPath() {
        SharedPreferences sp = safePrefs();
        return sp != null ? sp.getString(K_FROM_PATH, null) : sFromPath;
    }

    public static String getToPath() {
        SharedPreferences sp = safePrefs();
        return sp != null ? sp.getString(K_TO_PATH, null) : sToPath;
    }

    public static void setBubblePath(int kind, String path) {
        SharedPreferences sp = safePrefs();
        if (sp == null) return;
        if (kind == KIND_FROM) {
            sp.edit().putString(K_FROM_PATH, path).apply();
            sFromPath = path;
            sFromBmp = null;
            sFromBmpPath = null;
            synchronized (sDrawableCache) { sFromDrawableState = null; sFromDrawablePath = null; }
        } else {
            sp.edit().putString(K_TO_PATH, path).apply();
            sToPath = path;
            sToBmp = null;
            sToBmpPath = null;
            synchronized (sDrawableCache) { sToDrawableState = null; sToDrawablePath = null; }
        }
        LogWriter.log(TAG, "bubble path kind=" + kind + " -> " + path);
    }

    // ---------------- v3.0.128 气泡内文字颜色 ----------------

    /** 读取气泡内文字颜色（0 = 不修改）。 */
    public static int getTextColor(int kind) {
        if (kind == KIND_FROM) return sFromTextColor;
        return sToTextColor;
    }

    /** 设置气泡内文字颜色（0 = 不修改，恢复微信原生）。v3.0.166：写后立即刷新已渲染视图。 */
    public static void setTextColor(int kind, int color) {
        SharedPreferences sp = safePrefs();
        if (kind == KIND_FROM) {
            sFromTextColor = color;
            if (sp != null) sp.edit().putInt(K_FROM_TEXT_COLOR, color).apply();
        } else {
            sToTextColor = color;
            if (sp != null) sp.edit().putInt(K_TO_TEXT_COLOR, color).apply();
        }
        LogWriter.log(TAG, "bubble text color kind=" + kind + " -> 0x"
                + Integer.toHexString(color));
        refreshRenderedColors();
    }

    // ---------------- v3.0.150 聊天时间线 / 群聊昵称颜色 ----------------

    /** 读取聊天记录内时间分隔条文字颜色（0 = 不修改）。 */
    public static int getTimeTextColor() { return sTimeTextColor; }

    /** 设置聊天记录内时间分隔条文字颜色（0 = 不修改，恢复微信原生）。v3.0.166：写后立即刷新。 */
    public static void setTimeTextColor(int color) {
        sTimeTextColor = color;
        SharedPreferences sp = safePrefs();
        if (sp != null) sp.edit().putInt(K_TIME_TEXT_COLOR, color).apply();
        LogWriter.log(TAG, "time text color -> 0x" + Integer.toHexString(color));
        refreshRenderedColors();
    }

    /** 读取群聊成员昵称文字颜色（0 = 不修改）。 */
    public static int getNickTextColor() { return sNickTextColor; }

    /** 设置群聊成员昵称文字颜色（0 = 不修改，恢复微信原生）。v3.0.166：写后立即刷新。 */
    public static void setNickTextColor(int color) {
        sNickTextColor = color;
        SharedPreferences sp = safePrefs();
        if (sp != null) sp.edit().putInt(K_NICK_TEXT_COLOR, color).apply();
        LogWriter.log(TAG, "nick text color -> 0x" + Integer.toHexString(color));
        refreshRenderedColors();
    }

    /**
     * v3.0.166：取色实时生效 —— 遍历当前已 attach 的聊天列表 View 树，重刷
     * 时间线文字 / 群聊昵称 / 气泡文字色 / 语音气泡内容色，无需重新进入聊天窗口。
     * 幂等：未渲染/无目标 id 的视图自然跳过。
     */
    public static void refreshRenderedColors() {
        try {
            android.app.Activity act = com.leshao.v3.MainHook.currentActivity();
            View root = null;
            if (act != null) {
                View decor = act.getWindow().getDecorView();
                if (decor != null) root = decor;
            }
            // 1) 已登记气泡（含语音）逐个重刷文字色
            synchronized (sBubble) {
                for (java.util.Map.Entry<View, Boolean> e : sBubble.entrySet()) {
                    View v = e.getKey();
                    if (v == null || !v.isShown()) continue;
                    boolean recv = Boolean.TRUE.equals(e.getValue());
                    int kind = recv ? KIND_FROM : KIND_TO;
                    if (isAnimImage(v) || v.getId() == ID_MQ_E || v.getId() == ID_MQ_U
                            || v.getId() == ID_MQ_D) {
                        applyVoiceBubble(v, recv, false);
                        applyVoiceContentColor(v, kind);
                    } else {
                        applyBubbleTo(v, kind);
                    }
                }
            }
            // 2) 时间线 / 昵称：从 Activity 根遍历整棵 View 树
            if (root != null) {
                walkAndRepaint(root, 0);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "refreshRenderedColors err: " + t.getMessage());
        }
    }

    /** 深度遍历 View 树，凡命中 ID_TIME_TV / ID_NICK_TV 的 TextView 立即改写用户色。 */
    private static void walkAndRepaint(View v, int depth) {
        if (v == null || depth > 24) return;
        try {
            int id = v.getId();
            if (id == ID_TIME_TV && sTimeTextColor != 0 && v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextColor(sTimeTextColor);
            } else if (id == ID_NICK_TV && sNickTextColor != 0 && v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextColor(sNickTextColor);
            }
        } catch (Throwable ignored) {}
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                walkAndRepaint(g.getChildAt(i), depth + 1);
            }
        }
    }

    /** 语音气泡内内容（播放图标/时长等）改色：v3.0.166（任务2）。
     *  在语音气泡视图上，遍历其直接子树找图标 ImageView 与时长 TextView，
     *  用气泡方向对应的自定义文字色（未设置时用白字，避免黑色看不清）。 */
    private static void applyVoiceContentColor(View bubbleView, int kind) {
        if (bubbleView == null) return;
        int color = kind == KIND_FROM ? sFromTextColor : sToTextColor;
        if (color == 0) color = 0xFFFFFFFF;
        try {
            // 语音气泡根是 AnimImageView（mq.e/mq.u）或 TextView（mq.D）。
            // 真实布局里时长/图标是 AnimImageView 的兄弟视图，先找其父容器。
            View container = bubbleView;
            Object parent = bubbleView.getParent();
            if (parent instanceof ViewGroup) container = (ViewGroup) parent;
            repaintVoiceContent(container, color, bubbleView, 0);
        } catch (Throwable ignored) {}
    }

    private static void repaintVoiceContent(View v, int color, View skip, int depth) {
        if (v == null || depth > 8 || v == skip) return;
        if (v instanceof android.widget.ImageView) {
            try {
                android.widget.ImageView iv = (android.widget.ImageView) v;
                if (isAnimImage(iv)) return; // 气泡背景自身不改
                iv.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Throwable ignored) {}
            return;
        }
        if (v instanceof android.widget.TextView) {
            try {
                android.widget.TextView tv = (android.widget.TextView) v;
                if (tv.getId() == ID_TIME_TV || tv.getId() == ID_NICK_TV) return;
                tv.setTextColor(color);
            } catch (Throwable ignored) {}
            return;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                repaintVoiceContent(g.getChildAt(i), color, skip, depth + 1);
            }
        }
    }

    private static SharedPreferences safePrefs() {
        try {
            return ContextManager.getPrefs();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putBool(String k, boolean v) {
        try {
            ContextManager.getPrefs().edit().putBoolean(k, v).apply();
        } catch (Throwable ignored) {}
    }

    // ---------------- 文件选择（系统文件管理器） ----------------

    public static void pickBubbleImage(Activity act, int kind, BubblePickCallback cb) {
        sPickCb = cb;
        sPickKind = kind;
        // 微信部分 Activity 重写 onActivityResult 且不调用 super，仅 hook 基类会丢失回调。
        // 针对性 hook 该 Activity 的实际运行时类（幂等，重复 hook 由 XposedBridge 去重）。
        hookActivityInstanceOnResult(act);
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            act.startActivityForResult(intent, REQ_PICK_BUBBLE);
            LogWriter.log(TAG, "open document picker kind=" + kind);
        } catch (Throwable t) {
            sPickCb = null;
            LogWriter.log(TAG, "open picker FAILED: " + t.getMessage());
        }
    }

    /** hook 指定 Activity 运行时类的 onActivityResult（若该类重写），兜底基类 hook 的失效场景。 */
    private static void hookActivityInstanceOnResult(Activity act) {
        if (act == null) return;
        try {
            Class<?> c = act.getClass();
            while (c != null && c != Activity.class && Activity.class.isAssignableFrom(c)) {
                try {
                    final java.lang.reflect.Method m = c.getDeclaredMethod(
                            "onActivityResult", int.class, int.class, Intent.class);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            handleActivityResult(param);
                        }
                    });
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {}
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static void handleActivityResult(de.robv.android.xposed.XC_MethodHook.MethodHookParam param) {
        try {
            int requestCode = (int) param.args[0];
            if (requestCode != REQ_PICK_BUBBLE || sPickCb == null) return;
            BubblePickCallback cb = sPickCb;
            sPickCb = null;
            String path = null;
            int resultCode = (int) param.args[1];
            Intent data = (Intent) param.args[2];
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                path = copyUriToBubbleDir((Activity) param.thisObject, data.getData());
            }
            cb.onPick(path);
        } catch (Throwable ignored) {}
    }

    private static void ensureResultHook() {
        if (sResultHooked) return;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onActivityResult", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handleActivityResult(param);
                }
            });
            sResultHooked = true;
            LogWriter.log(TAG, "onActivityResult hook installed");
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureResultHook err: " + t.getMessage());
        }
    }

    /** 将 content:// URI 复制到 /data/data/<pkg>/files/bubble/ 返回本地路径 */
    private static String copyUriToBubbleDir(Activity act, Uri uri) {
        try {
            String fileName = "bubble_" + sPickKind + "_" + System.currentTimeMillis();
            Cursor cursor = act.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String name = cursor.getString(idx);
                            if (name != null && name.contains(".")) {
                                fileName = "bubble_" + sPickKind + "_" + System.currentTimeMillis()
                                        + name.substring(name.lastIndexOf('.'));
                            }
                        }
                    }
                } finally {
                    cursor.close();
                }
            }
            File dir = new File(act.getFilesDir(), "bubble");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, fileName);
            InputStream is = act.getContentResolver().openInputStream(uri);
            if (is == null) return null;
            FileOutputStream fos = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                try { fos.close(); } catch (Throwable ignored) {}
                try { is.close(); } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "bubble saved: " + out.getAbsolutePath());
            return out.getAbsolutePath();
        } catch (Throwable t) {
            LogWriter.log(TAG, "copyUriToBubbleDir err: " + t.getMessage());
            return null;
        }
    }

    // ---------------- Hook ----------------

    public static void hook(ClassLoader cl) {
        try {
            sHookCl = cl;
            sEnabled = isEnabled();
            sFromPath = getFromPath();
            sToPath = getToPath();
            try {
                SharedPreferences sp = safePrefs();
                if (sp != null) {
                    sFromTextColor = sp.getInt(K_FROM_TEXT_COLOR, 0);
                    sToTextColor = sp.getInt(K_TO_TEXT_COLOR, 0);
                    sTimeTextColor = sp.getInt(K_TIME_TEXT_COLOR, 0);
                    sNickTextColor = sp.getInt(K_NICK_TEXT_COLOR, 0);
                }
            } catch (Throwable ignored) {}
            resolveBubbleResIds(cl);
            LogWriter.log(TAG, "bubble config enabled=" + sEnabled
                    + " from=" + (sFromPath != null && !sFromPath.isEmpty() ? "SET" : "EMPTY")
                    + " to=" + (sToPath != null && !sToPath.isEmpty() ? "SET" : "EMPTY"));
        } catch (Throwable ignored) {}
        ensureResultHook();
        if (sHooked) return;
        Thread t = new Thread(() -> {
            for (int attempt = 0; attempt < 10 && !sHooked; attempt++) {
                try {
                    // v3.0.95：微信 8.0.78 气泡资源已改名(mh/o_)，且 ke5.a.i 运行时不再调用，
                    // 必须主动加载微信原生气泡 Drawable，否则 constantState 匹配全部失效。
                    ensureBaseDrawables();
                    // v3.0.174：X2C resolver 独立 try-catch——3180 上 chatfrom_bg/chatto_bg
                    // 字符串已从方法中移除（DexKit 返回 0），此方案失败不再阻断其余全部方案。
                    try {
                        installBubbleResolver(cl);
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "bubble X2C resolver skipped: " + e.getMessage());
                    }
                    installBubbleApplyHook(cl);
                    installBackgroundResourceHook(cl);
                    installNeatBackgroundHook(cl);
                    installNeatOnDrawHook(cl);
                    installBackgroundHook(cl);
                    installImageViewHook(cl);
                    installViewitemsToHook(cl);
                    installLinkSubtypeHook(cl);
                    installResourceHelperHook(cl);
                    installResourceGetDrawableHook(cl);
                    installChatListViewHook(cl);
                    installCalibrationHooks(cl);
                    installAnimImageViewHook(cl);
                    installTextColorForceHook(cl);
                    installAttachApplyHook(cl);
                    if (DEBUG_VIEW_DUMP) installChattingListCollector(cl);
                    sHooked = true;
                    LogWriter.log(TAG, "bubble resolver hooked attempt=" + attempt);
                    return;
                } catch (Throwable e) {
                    LogWriter.log(TAG, "bubble install attempt " + attempt + " failed: "
                            + e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "leshao-bubble-hook");
        t.setDaemon(true);
        t.start();
    }

    /** 解析气泡资源 ID：优先反射 R$drawable 字段（R8 不混淆资源字段名），
     *  其次 getIdentifier，最后回退文档已知值。R8 可能把 R 类扁平化为
     *  com$tencent$mm$R$drawable，因此遍历候选类名变体。 */
    private static void resolveBubbleResIds(ClassLoader cl) {
        String[] candidates = {
                "com.tencent.mm.R$drawable",
                "com$tencent$mm$R$drawable",
                "com.tencent.mm.R$drawable"
        };
        for (String cn : candidates) {
            try {
                Class<?> rDrawable = cl.loadClass(cn);
                int from = 0;
                int to = 0;
                try {
                    java.lang.reflect.Field f1 = rDrawable.getDeclaredField("chatfrom_bg");
                    f1.setAccessible(true);
                    from = f1.getInt(null);
                } catch (NoSuchFieldException ignored) {}
                try {
                    java.lang.reflect.Field f2 = rDrawable.getDeclaredField("chatto_bg");
                    f2.setAccessible(true);
                    to = f2.getInt(null);
                } catch (NoSuchFieldException ignored) {}
                if (from == 0 && to == 0) {
                    LogWriter.log(TAG, "R$drawable " + cn + " loaded but no chatfrom/chatto fields");
                    continue;
                }
                if (from != 0) sFromResId = from;
                if (to != 0) sToResId = to;
                LogWriter.log(TAG, "bubble resIds via R$drawable cn=" + cn
                        + " from=" + sFromResId + " to=" + sToResId);
                return;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个候选名
            } catch (Throwable t) {
                LogWriter.log(TAG, "R$drawable resolve err cn=" + cn + ": " + t.getMessage());
            }
        }
        try {
            android.content.res.Resources res = ContextManager.getAppContext().getResources();
            String pkg = ContextManager.getAppContext().getPackageName();
            int from = res.getIdentifier("chatfrom_bg", "drawable", pkg);
            int to = res.getIdentifier("chatto_bg", "drawable", pkg);
            if (from != 0) sFromResId = from;
            if (to != 0) sToResId = to;
            LogWriter.log(TAG, "bubble resIds from=" + sFromResId + " to=" + sToResId
                    + " (getIdentifier from=" + from + " to=" + to + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "resolveBubbleResIds err: " + t.getMessage());
        }
        // 无论成功与否，打印旧值对应的当前资源名，供人工核对锚点。
        try {
            android.content.res.Resources res = ContextManager.getAppContext().getResources();
            for (int id : new int[]{sFromResId, sToResId}) {
                try {
                    String name = res.getResourceEntryName(id);
                    LogWriter.log(TAG, "legacy resId " + id + " -> name=" + name);
                } catch (Throwable t) {
                    LogWriter.log(TAG, "legacy resId " + id + " name err: " + t.getMessage());
                }
            }
        } catch (Throwable ignored) {}
    }

    /** v3.0.95：主动加载微信原生气泡 Drawable（资源 ID 已改名 mh/o_），
     *  供 constantState 匹配识别真实气泡 View。ke5.a.i 在 8.0.78 运行时不再调用，
     *  必须在此兜底，否则 setBackground/onLayout 等替换路径全部失效。 */
    private static void ensureBaseDrawables() {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null) return;
            android.content.res.Resources res = ctx.getResources();
            if (sFromBaseDrawable == null && sFromResId != 0) {
                Drawable d = res.getDrawable(sFromResId);
                if (d != null) {
                    sFromBaseDrawable = d;
                    LogWriter.log(TAG, "baseDrawable loaded from resId=" + sFromResId
                            + " name=" + res.getResourceEntryName(sFromResId)
                            + " cls=" + d.getClass().getName());
                }
            }
            if (sToBaseDrawable == null && sToResId != 0) {
                Drawable d = res.getDrawable(sToResId);
                if (d != null) {
                    sToBaseDrawable = d;
                    LogWriter.log(TAG, "baseDrawable loaded to resId=" + sToResId
                            + " name=" + res.getResourceEntryName(sToResId)
                            + " cls=" + d.getClass().getName());
                }
            }
            // v3.0.135：加载候选气泡资源（含子项），扩大 constantState 匹配集
            if (sExtraFromBaseDrawables.isEmpty()) {
                for (int id : BASE_FROM_CANDIDATE_IDS) {
                    try {
                        Drawable d = res.getDrawable(id);
                        if (d != null) {
                            sExtraFromBaseDrawables.add(d);
                            LogWriter.log(TAG, "baseCandidate from resId=" + id
                                    + " name=" + res.getResourceEntryName(id)
                                    + " cls=" + d.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (sExtraToBaseDrawables.isEmpty()) {
                for (int id : BASE_TO_CANDIDATE_IDS) {
                    try {
                        Drawable d = res.getDrawable(id);
                        if (d != null) {
                            sExtraToBaseDrawables.add(d);
                            LogWriter.log(TAG, "baseCandidate to resId=" + id
                                    + " name=" + res.getResourceEntryName(id)
                                    + " cls=" + d.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "ensureBaseDrawables err: " + t.getMessage());
        }
    }

    /** 方案 C + 方案 1（报告 §0.1 根因）：XML 兜底路径。
     *
     *  <p>关键修正：文本气泡承载视图 {@code MMNeat7extView} 自己重写了
     *  {@code setBackgroundResource(int)}（smali：先 super 再同步内嵌 wrappedTextView 的 padding），
     *  Java 虚分派会直接进入它自己的 override，<b>永不落到 {@code android.view.View} 实现</b>。
     *  因此旧实现 hook {@code View.setBackgroundResource} 对文本气泡 100% 不触发——这是
     *  「文字气泡替换不生效」的真正断点。</p>
     *
     *  <p>修正后：直接对真实类 {@code com.tencent.mm.ui.widget.MMNeat7extView} 的
     *  {@code setBackgroundResource(int)} 安装 hook。textual 气泡的三个设置点
     *  （{@code viewitems.to.b} / {@code hn5.r0.g0} / {@code hn5.s0.k0}）全部调用它，
     *  在 after 阶段按 6 个原始 resId 白名单 → 收/发方向覆盖为用户图片。
     *  同时保留对 {@code android.view.View} 基类的兜底（覆盖图片/语音等不 override 的容器），
     *  两者并存不冲突（对 override 类基类 hook 不触发，不会双替换）。</p> */
    private static void installBackgroundResourceHook(ClassLoader cl) {
        try {
            XC_MethodHook h = createBackgroundResourceHook();
            // 1) 文本气泡真实 override —— 唯一可靠触发点
            Class<?> neat = null;
            try {
                neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            } catch (Throwable ignored) {}
            ClassicSet: {
                if (neat == null) break ClassicSet;
                Method m = null;
                try {
                    m = neat.getDeclaredMethod("setBackgroundResource", int.class);
                } catch (Throwable ignored) {}
                if (m == null) {
                    for (Method mm : neat.getDeclaredMethods()) {
                        if ("setBackgroundResource".equals(mm.getName())
                                && mm.getParameterTypes().length == 1
                                && mm.getParameterTypes()[0] == int.class) {
                            m = mm;
                            break;
                        }
                    }
                }
                if (m != null) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, h);
                    LogWriter.log(TAG, "MMNeat7extView.setBackgroundResource hooked (real text bubble)");
                    break ClassicSet;
                }
                LogWriter.log(TAG, "MMNeat7extView has no setBackgroundResource override");
            }
            // 2) 基类兜底（图片/语音等非 override 容器气泡）
            try {
                Method base = View.class.getMethod("setBackgroundResource", int.class);
                XposedBridge.hookMethod(base, h);
                LogWriter.log(TAG, "View.setBackgroundResource hooked (fallback)");
            } catch (Throwable t) {
                LogWriter.log(TAG, "View.setBackgroundResource hook err: " + t.getMessage());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBackgroundResourceHook err: " + t.getMessage());
        }
    }

    /** 构造 setBackgroundResource(int) 的替换 hook：after 阶段按 resId 白名单映射方向并覆盖背景。
     *  v3.0.131：仅在聊天列表内的视图生效，防止微信输入框/发现页/我的/设置等复用
     *  chatfrom_bg/chatto_bg 资源的界面被误渲染成自定义气泡。 */
    private static XC_MethodHook createBackgroundResourceHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    int resId = ((Number) param.args[0]).intValue();
                    int kind = resolveKindByResId(ContextManager.getAppContext(), resId);
                    if (kind < 0) return;
                    View v = (View) param.thisObject;
                    if (!isBubbleContext(v)) return; // v3.0.131: 非聊天列表不处理
                    try {
                        if (kind == KIND_FROM && sFromBaseDrawable == null) {
                            Drawable base = v.getResources().getDrawable(resId);
                            if (base != null) sFromBaseDrawable = base;
                        } else if (kind == KIND_TO && sToBaseDrawable == null) {
                            Drawable base = v.getResources().getDrawable(resId);
                            if (base != null) sToBaseDrawable = base;
                        }
                    } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "setBackgroundResource before resId=" + resId
                            + " kind=" + kind + " view=" + v.getClass().getName());
                } catch (Throwable ignored) {}
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    int resId = ((Number) param.args[0]).intValue();
                    int kind = resolveKindByResId(ContextManager.getAppContext(), resId);
                    if (kind < 0) return;
                    View v = (View) param.thisObject;
                    if (!shouldReplaceBg(v, v.getBackground())) return; // v3.0.142（v7 §11）：时间条/系统提示过滤
                    applyBubbleTo(v, kind);
                    // v3.0.158：本路径此前不登记 sBubble，导致 onAttachedToWindow 补盖永不触发，
                    // 若微信在本 hook 之后再次覆盖背景则无法恢复。这里同步登记，纳入补盖范围。
                    synchronized (sBubble) { sBubble.put(v, kind == KIND_FROM); }
                    int[] loc = {0, 0};
                    try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                    LogWriter.log(TAG, "setBackgroundResource REPLACE resId=" + resId
                            + " kind=" + kind + " view=" + v.getClass().getName()
                            + " xy=(" + loc[0] + "," + loc[1] + ")"
                            + " w=" + v.getWidth() + " h=" + v.getHeight());
                    // v3.0.158 诊断：布局后回读最终背景，确认替换未被微信随后覆盖。
                    final View fv = v;
                    final int fkind = kind;
                    if (sBgVerify.add(fv)) {
                        fv.postDelayed(() -> {
                            try {
                                Drawable cur = fv.getBackground();
                                LogWriter.log(TAG, "bubble VERIFY view=" + fv.getClass().getSimpleName()
                                        + " kind=" + fkind
                                        + " custom=" + isCustomBackground(cur)
                                        + " bg=" + (cur == null ? "null" : cur.getClass().getName())
                                        + " w=" + fv.getWidth() + " h=" + fv.getHeight()
                                        + " vis=" + fv.getVisibility());
                            } catch (Throwable ignored) {}
                        }, 400);
                    }
                } catch (Throwable ignored) {}
            }
        };
    }

    /** 方案 1.2（报告 §3.2）：MMNeat7extView 同样重写了 {@code setBackground(Drawable)}，
     *  某些路径（DataBinding / 我们自己的 after 兜底 / X2C）会以 Drawable 形式设置气泡背景，
     *  基类 {@code View.setBackground} hook 对文本气泡同样不触发。此处直接 hook 真实类。
     *  无法用 resId 判定方向时，用 constantState 与已记录的微信原生气泡比对。 */
    private static void installNeatBackgroundHook(ClassLoader cl) {
        Class<?> neat = null;
        try {
            neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
        } catch (Throwable ignored) {}
        if (neat == null) return;
        Method m = null;
        for (Method mm : neat.getDeclaredMethods()) {
            if ("setBackground".equals(mm.getName())
                    && mm.getParameterTypes().length == 1
                    && mm.getParameterTypes()[0] == Drawable.class) {
                m = mm;
                break;
            }
        }
        if (m == null) {
            LogWriter.log(TAG, "MMNeat7extView.setBackground(Drawable) not found");
            return;
        }
        m.setAccessible(true);
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                    if (!(param.thisObject instanceof View)) return;
                    View neatV = (View) param.thisObject;
                    if (!isBubbleContext(neatV)) return; // v3.0.131
                    Drawable d = (Drawable) param.args[0];
                    if (isCustomBackground(d)) return; // 已是自定义气泡
                    // v3.0.142（v7 §11）：时间条/系统提示/jh 非内容视图过滤
                    if (!shouldReplace(neatV, d)) return;
                    int kind = matchBaseDrawable(d);
                    if (kind < 0) {
                        // v3.0.138（v6 文档 §10）：普通态文本气泡唯一来源是 XML android:background，
                        // inflate 时经 MMNeat7extView.setBackground 落入；该 Drawable 无法反推 resId、
                        // constantState 与白名单资源不匹配（此前 UNMATCHED 根因）。MMNeat7extView 是
                        // 聊天文本专用视图，直接按方向替换为自定义气泡，不再依赖白名单匹配。
                        kind = kindOf(neatV);
                        if (loadDrawable(kind) == null) return;
                        LogWriter.log(TAG, "neat.setBackground FALLBACK kind=" + kind
                                + " view=" + neatV.getClass().getName());
                    }
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            Drawable freshD = fresh(custom);
                            param.args[0] = freshD;
                            rememberBubble(neatV, freshD, kind);
                            // v3.0.134: X2C 预构建路径（item attach 前）捕获进 BUBBLE 表，
                            // attach 后自动补盖，避免复用未 rebind 导致文本气泡保持原生背景。
                            synchronized (sBubble) {
                                sBubble.put(neatV, kind == KIND_FROM);
                            }
                            LogWriter.log(TAG, "neat.setBackground REPLACE kind=" + kind
                                    + " view=" + neatV.getClass().getName());
                        }
                } catch (Throwable ignored) {}
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!sEnabled) return;
                    if (!(param.thisObject instanceof View)) return;
                    View v = (View) param.thisObject;
                    if (!isBubbleContext(v)) return; // v3.0.131
                    boolean cleared = param.args.length == 0 || param.args[0] == null;
                    if (cleared) {
                        reapplyIfBubble(v);          // setBackground(null) 清空 → 补盖
                    } else {
                        applyTextColor(v, kindOf(v)); // 背景已设 → 同步文字色
                    }
                } catch (Throwable ignored) {}
            }
        });
        LogWriter.log(TAG, "MMNeat7extView.setBackground(Drawable) hooked");
    }

    /** v3.0.120：文字气泡兜底。语音气泡走 AnimImageView 背景/图片生效；
     *  文字气泡（MMNeat7extView）可能自绘气泡而不读取 View background，
     *  导致 setBackground* 全部替换后仍看不到效果。
     *  此处在 onDraw 前主动绘制自定义气泡：若背景已是自定义图则直接绘制，
     *  若背景仍是微信原生气泡则替换后绘制，确保自定义图出现在文字下方。 */
    private static void installNeatOnDrawHook(ClassLoader cl) {
        try {
            Class<?> neat = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            Method onDraw = null;
            for (Method m : neat.getDeclaredMethods()) {
                if ("onDraw".equals(m.getName()) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == android.graphics.Canvas.class) {
                    onDraw = m;
                    break;
                }
            }
            if (onDraw == null) {
                LogWriter.log(TAG, "MMNeat7extView.onDraw not found");
                return;
            }
            onDraw.setAccessible(true);
            XposedBridge.hookMethod(onDraw, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        View v = (View) param.thisObject;
                        if (!isBubbleContext(v)) return; // v3.0.131
                        if (!shouldReplace(v)) return; // v3.0.142（v7 §11）：时间条/系统提示过滤
                        android.graphics.Canvas canvas = (android.graphics.Canvas) param.args[0];
                        int w = v.getWidth();
                        int h = v.getHeight();
                        if (w <= 0 || h <= 0) return;
                        Drawable bg = v.getBackground();
                        if (isCustomBackground(bg)) {
                            bg.setBounds(0, 0, w, h);
                            bg.draw(canvas);
                            return;
                        }
                        int kind = matchBaseDrawable(bg);
                        if (kind < 0) {
                            // v3.0.138（v6 文档 §10）：普通态文本气泡背景来自 XML，无法反查 resId。
                            // MMNeat7extView 是聊天文本专用视图，onDraw 兜底直接按方向替换。
                            kind = kindOf(v);
                            if (loadDrawable(kind) == null) return;
                            LogWriter.log(TAG, "neat.onDraw FALLBACK kind=" + kind);
                        }
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            custom.setBounds(0, 0, w, h);
                            custom.draw(canvas);
                            v.setBackground(custom);
                            rememberBubble(v, custom, kind);
                            synchronized (sBubble) { sBubble.put(v, kind == KIND_FROM); }
                            LogWriter.log(TAG, "neat.onDraw REPLACE kind=" + kind
                                    + " parent=" + parentChain(v, 1));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "MMNeat7extView.onDraw hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installNeatOnDrawHook err: " + t.getMessage());
        }
    }

    /** 方案 E：View.setBackground / setBackgroundDrawable 拦截。
     *  聊天 item 复用预构建 View 后可能重新设置气泡背景（不走 ke5.a.i/getDrawable），
     *  在设置点用 ke5.a.i 记录的微信原始 Drawable 的 constantState 识别并替换。
     *  注：此基类 hook 对 override 的 MMNeat7extView 不触发（见 installNeatBackgroundHook）。 */
    private static void installBackgroundHook(ClassLoader cl) {
        try {
            Method setBg = View.class.getMethod("setBackground", Drawable.class);
            Method setBgDrawable = View.class.getMethod("setBackgroundDrawable", Drawable.class);
            XC_MethodHook h = new XC_MethodHook() {
@Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        // 空 Drawable 不拦：微信 AnimImageView.setType() 复用/空态分支会
                        // setBackgroundDrawable(null) 清空背景，交由 after 无条件补盖。
                        if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        if (!(param.thisObject instanceof View)) return;
                        View bv = (View) param.thisObject;
                        if (!isBubbleContext(bv)) return; // v3.0.131
                        Drawable d = (Drawable) param.args[0];
                        if (!shouldReplaceBg(bv, d)) return; // v3.0.142（v7 §11）：时间条/系统提示过滤（语音/图片气泡 AnimImageView 一并放行）
                        int kind = matchBaseDrawable(d);
                        if (kind < 0) {
                            // v3.0.138：MVVM 视图（ChattingUrlMvvmView 等）气泡背景是 AppMsg 资源
                            // 的 selector，constantState 可能不匹配；聊天 MVVM 视图专属聊天，按方向替换。
                            if (!isChatMvvmView((View) param.thisObject)) {
                                // v3.0.137：已被自定义气泡替换的背景不视为 UNMATCHED，避免误报刷屏。
                                if (!isCustomBackground(d)) {
                                    debugDumpUnmatchedTextBubble((View) param.thisObject, d);
                                }
                                return;
                            }
                            kind = kindOf((View) param.thisObject);
                            if (loadDrawable(kind) == null) return;
                            LogWriter.log(TAG, "setBackground MVVM FALLBACK kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
                        }
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            param.args[0] = custom;
                            // v3.0.134: X2C 预构建路径的 MMNeat7extView 在此捕获进 BUBBLE 表，
                            // attach 后 onAttachedToWindow 会自动补盖，覆盖复用未 rebind 场景。
                            View tv = (View) param.thisObject;
                            if (isChatTextBubble(tv)) {
                                rememberBubble(tv, custom, kind);
                                synchronized (sBubble) { sBubble.put(tv, kind == KIND_FROM); }
                            }
                            LogWriter.log(TAG, "setBackground REPLACE kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }

@Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        boolean cleared = param.args.length == 0 || param.args[0] == null;
                        if (!cleared) return;
                        if (param.thisObject instanceof View) {
                            if (!isBubbleContext((View) param.thisObject)) return; // v3.0.131
                            reapplyIfBubble((View) param.thisObject);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookMethod(setBg, h);
            XposedBridge.hookMethod(setBgDrawable, h);
            LogWriter.log(TAG, "View.setBackground hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBackgroundHook err: " + t.getMessage());
        }
    }

    /** 方案 G：ImageView 图片路径。8.0.78 聊天消息气泡（尤其自己发出的 chatto_bg）通过
     *  AnimImageView（ImageView 子类）显示，ke5.a.i 加载的 Drawable 最终传给
     *  ImageView.setImageDrawable。在此拦截，按微信原生气泡 constantState 识别替换。 */
    private static void installImageViewHook(ClassLoader cl) {
        try {
            Method setImageDrawable = ImageView.class.getMethod("setImageDrawable", Drawable.class);
            XposedBridge.hookMethod(setImageDrawable, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        if (param.args.length == 0 || !(param.args[0] instanceof Drawable)) return;
                        if (!(param.thisObject instanceof View)) return;
                        View iv = (View) param.thisObject;
                        Drawable d = (Drawable) param.args[0];
                        if (!shouldReplaceBg(iv, d)) return; // v3.0.142（v7 §11）：时间条/系统提示过滤
                        int kind = matchBaseDrawable(d);
                        if (kind < 0) return;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            param.args[0] = custom;
                            LogWriter.log(TAG, "setImageDrawable REPLACE kind=" + kind
                                    + " view=" + param.thisObject.getClass().getName());
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "ImageView.setImageDrawable hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installImageViewHook err: " + t.getMessage());
        }
        try {
            Method setImageResource = ImageView.class.getMethod("setImageResource", int.class);
            XposedBridge.hookMethod(setImageResource, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        if (!(param.thisObject instanceof View)) return;
                        View iv = (View) param.thisObject;
                        if (!isBubbleContext(iv)) return; // v3.0.131
                        if (sIdBlack.contains(iv.getId())) return; // v3.0.142（v7 §11）
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        Drawable custom = loadDrawable(kind);
                        if (custom != null) {
                            ((ImageView) param.thisObject).setImageDrawable(custom);
                            LogWriter.log(TAG, "setImageResource REPLACE resId=" + resId
                                    + " kind=" + kind);
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "ImageView.setImageResource hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installImageViewHook setImageResource err: " + t.getMessage());
        }
    }

    /** v3.0.161：语音气泡统一补盖。mq.e/mq.u 是 AnimImageView（含 forceVisible，防 GONE 态不渲染）；
     *  mq.D 是 TextView 备用承载，只贴背景不动可见性。 */
    private static void applyVoiceBubble(View v, boolean recv, boolean forceVisible) {
        try {
            if (!sEnabled) return;
            int kind = recv ? KIND_FROM : KIND_TO;
            Drawable custom = loadDrawable(kind);
            if (custom == null) return;
            Drawable d = fresh(custom);
            v.setBackground(d);
            rememberBubble(v, d, kind);
            applyDrawablePadding(v, d);
            syncBubblePadding(v);
            if (forceVisible) {
                try { if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE); } catch (Throwable ignored) {}
            }
            try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean isAnimImage(View v) {
        return v != null && v.getClass().getName().contains("AnimImageView");
    }

    /** v3.0.132（《聊天气泡修复文档》）：语音气泡捕获点。
     *  hook {@code com.tencent.mm.ui.base.AnimImageView.setType(int)}：
     *  字段 {@code e} 即 isRecv（方向直供），after 把 view 收进 BUBBLE 表。
     *  同时处理三个分支（Smali 实证，否则会把我们的替换盖回去）：
     *  <ul>
     *    <li>i==2 → setBackgroundResource(2131100638 对方 / 2131100639 自己)，resId 已进白名单；</li>
     *    <li>i==3 → setBackgroundDrawable(null)，before 不拦 null（故意清空），after 由补盖恢复。</li>
     *  </ul>
     *  <p>v3.0.161：方向改由 mq.b(View,boolean,boolean) 的 z 登记（字段 e 在 3180 不可靠），
     *  setType 内只回读 sBubble 表；type==3 清背景态不再就地贴，延后到布局稳定后补盖。</p> */
    private static void installAnimImageViewHook(ClassLoader cl) {
        try {
            Class<?> anim = XposedHelpers.findClass("com.tencent.mm.ui.base.AnimImageView", cl);
            Method m = null;
            for (Method mm : anim.getDeclaredMethods()) {
                if ("setType".equals(mm.getName()) && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == int.class) {
                    m = mm;
                    break;
                }
            }
            if (m == null) {
                LogWriter.log(TAG, "AnimImageView.setType not found");
                return;
            }
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        View v = (View) param.thisObject;
                        // v3.0.162（《新WeChatChatBubbleReplace.md》§14）：setType 只对 mq.b 已登记的
                        // 真语音气泡生效；录音面板麦克风等 AnimImageView（ChatFooter LinearLayout 76x76）
                        // 绝不在表内 —— 不再「就地处决式入表」，从源头杜绝把聊天气泡贴到麦克风上。
                        if (sIdBlack.contains(v.getId())) return; // v3.0.142（v7 §11）：时间条/系统提示过滤
                        Boolean stored;
                        synchronized (sBubble) { stored = sBubble.get(v); }
                        if (stored == null) return;   // 未登记（含录音麦克风）一律不碰、不入表
                        boolean recv = stored;
                        int type = ((Number) param.args[0]).intValue();
                        LogWriter.log(TAG, "AnimImageView.setType type=" + type + " isRecv=" + recv
                                + " view=" + v.getClass().getName()
                                + " attached=" + v.isAttachedToWindow()
                                + " parent=" + (v.getParent() == null ? "null" : v.getParent().getClass().getName()));
                        if (type == 3) {
                            // §14：type==3 = 微信主动清背景态（播放中/复用清理）。就地 REAPPLY 会与
                            // 微信录音/复用状态机冲突（录音完成发送后微信不再补 setType(1)），
                            // 且长按说话时录音麦克风也走 type=3。此处不贴、不 REAPPLY，
                            // 交给延迟补盖（onLayout 兜底在混淆容器下可能不触发）。
                            LogWriter.log(TAG, "AnimImageView.setType type=3 clear(skip) view="
                                    + v.getClass().getName());
                            scheduleVoiceCover();
                            return;
                        }
                        applyVoiceBubble(v, recv, true);
                        if (loadDrawable(recv ? KIND_FROM : KIND_TO) != null) {
                            LogWriter.log(TAG, "AnimImageView.setType REPLACE kind="
                                    + (recv ? KIND_FROM : KIND_TO)
                                    + " view=" + v.getClass().getName());
                        }
                        // v3.0.155：布局完成后校验一次最终背景，定位"替换后又被盖回默认/未生效"。
                        synchronized (sAnimVerifyDone) {
                            if (sAnimVerifyDone.put(v, Boolean.TRUE) == null) {
                                final View fv = v;
                                v.postDelayed(() -> {
                                    try {
                                        Drawable cur = fv.getBackground();
                                        LogWriter.log(TAG, "AnimImageView VERIFY bg="
                                                + (cur == null ? "null" : cur.getClass().getName())
                                                + " custom=" + isCustomBackground(cur)
                                                + " w=" + fv.getWidth() + " h=" + fv.getHeight()
                                                + " vis=" + fv.getVisibility()
                                                + " attached=" + fv.isAttachedToWindow()
                                                + " parent=" + (fv.getParent() == null
                                                    ? "null" : fv.getParent().getClass().getName()));
                                    } catch (Throwable ignored) {}
                                }, 400);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "AnimImageView.setType hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installAnimImageViewHook err: " + t.getMessage());
        }
    }

/** 文档 §5 方案2：精准 hook 文本气泡 ViewHolder 的静态绑定方法
     *  b(e9, holder, data, Boolean isRecv) —— 普通态 chatfrom_bg/chatto_bg 最终设置点。
     *  v3.0.90：日志 ke5.a.i stack 证实 8.0.78 真实气泡加载路径是 viewitems.mq.b；
     *  v3.0.91：mq.b 签名不满足「静态+首参e9」，故对 mq 类放宽为所有名为 b 的方法，
     *  并用 HookUtil.loadClasses 遍历 Tinker 真实 ClassLoader。 */
    private static void installViewitemsToHook(ClassLoader cl) {
        String[] holderCands = {
                "com.tencent.mm.ui.chatting.viewitems.to",
                "com.tencent.mm.ui.chatting.viewitems.mq"
        };
        int hooked = 0;
        for (String cn : holderCands) {
            // v3.0.137：仅 to 类(文字)排除 View 参数重载 —— v3.0.135 卡死根因是 to 类某 View 参数
            // 重载在非聊天场景被调用，findBubbleViewInTree 遍历整棵 View 树。
            // mq 类(语音)必须保留全部 b 方法：mq.b(View,boolean,boolean) 是语音消息绑定主路径，
            // v3.0.136 误过滤导致语音气泡失效（实测语音消息不起作用）。
            boolean strictViewFilter = cn.contains(".to");
            for (Class<?> toCls : HookUtil.loadClasses(cl, cn)) {
                for (Method m : toCls.getDeclaredMethods()) {
                    if (!"b".equals(m.getName())) continue;
                    Class<?>[] pts = m.getParameterTypes();
                    if (strictViewFilter) {
                        boolean hasViewParam = false;
                        for (Class<?> pt : pts) {
                            if (View.class.isAssignableFrom(pt)) {
                                hasViewParam = true;
                                break;
                            }
                        }
                        if (hasViewParam) continue;
                    }
                    m.setAccessible(true);
                    final String owner = cn;
                    final String methodSig = m.getName() + Arrays.toString(pts);
                    // v3.0.161：mq.b(View,boolean,boolean) —— 语音消息绑定主路径（z=isRecv 对方, z2=isGroup）
                    final boolean voiceBinder = !strictViewFilter && pts.length == 3
                            && pts[0] == View.class
                            && (pts[1] == Boolean.class || pts[1] == boolean.class)
                            && (pts[2] == Boolean.class || pts[2] == boolean.class);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                // v3.0.150（v8 §12.3/§12.5）：聊天时间线/群聊昵称颜色，独立于气泡替换。
                                // 只在聊天 item 绑定点(本 after)内执行，天然限定在聊天窗口。
                                applyTimeNickColor(param.args);
                                // v3.0.161：语音消息精确路径 —— 方向唯一可靠源是 mq.b 的 z 参数；
                                // holder 真实字段 mq.e/mq.u(AnimImageView)+mq.D(TextView 备用承载)
                                // 全部收录 BUBBLE 表并按方向贴自定义气泡，绕开几何启发式命中不可见视图。
                                if (voiceBinder) {
                                    try {
                                        View root = (View) param.args[0];
                                        boolean recv = Boolean.TRUE.equals(param.args[1]);
                                        int found = 0;
                                        for (int id : new int[]{ID_MQ_E, ID_MQ_U, ID_MQ_D}) {
                                            View av = root.findViewById(id);
                                            if (av == null) continue;
                                            synchronized (sBubble) { sBubble.put(av, recv); }
                                            applyVoiceBubble(av, recv, isAnimImage(av));
                                            // v3.0.166（任务2）：语音气泡内内容改色跟随方向
                                            applyVoiceContentColor(av, recv ? KIND_FROM : KIND_TO);
                                            found++;
                                        }
                                        LogWriter.log(TAG, "voice mq.b isRecv=" + recv
                                                + " isGroup=" + param.args[2] + " captured=" + found
                                                + " root=" + root.getClass().getSimpleName());
                                    } catch (Throwable ignored) {}
                                    return;
                                }
                                // v3.0.142（v7 §11.3）：消息类型过滤 —— 系统提示消息 type=10000、
                                // 时间消息等不走气泡替换，仅处理文本消息 type=1。
                                int msgType = -1;
                                for (Object a : param.args) {
                                    if (a == null) continue;
                                    if ("e9".equals(a.getClass().getSimpleName())) {
                                        try {
                                            msgType = ((Number) XposedHelpers.callMethod(a, "getType")).intValue();
                                        } catch (Throwable ignored) {}
                                        break;
                                    }
                                }
                                if (msgType != -1 && msgType != 1) return; // 仅当能确认非文本消息时跳过
                                // v3.0.136：预算保护 —— 防止遍历意外超时阻塞主线程（30ms）
                                long start = android.os.SystemClock.uptimeMillis();
                                sTreeVisitCount = 0;
                                boolean isRecv = true;
                                for (int i = 0; i < param.args.length; i++) {
                                    if (param.args[i] instanceof Boolean) {
                                        isRecv = (Boolean) param.args[i];
                                        break;
                                    }
                                }
                                // v3.0.136：触发日志（供下版修复定位文字消息真实绑定路径）
                                LogWriter.log(TAG, "viewitems.b CALL " + owner + "." + methodSig
                                        + " isRecv=" + isRecv + " args=" + param.args.length
                                        + " msgType=" + msgType);
                                // v3.0.132: BUBBLE 捕获 —— 微信亲自贴过气泡的 View 收进表（holder.b 字段）
                                for (Object a : param.args) {
                                    if (a == null) continue;
                                    if (a instanceof View || a instanceof Boolean
                                            || "e9".equals(a.getClass().getSimpleName())) continue;
                                    try {
                                        Object b = XposedHelpers.getObjectField(a, "b");
                                        if (b instanceof View && isReplaceableContentView((View) b)) {
                                            synchronized (sBubble) { sBubble.put((View) b, isRecv); }
                                            break;
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                View bubble = null;
                                // v3.0.120：优先遍历 holder 字段找 MMNeat7extView —— 文字气泡真实承载视图。
                                // 此前 findBubbleViewInTree 命中的 android.widget.TextView 并非可见气泡，
                                // 替换后用户看不到效果；而语音气泡(AnimImageView)走 setBackgroundResource 生效。
                                for (Object a : param.args) {
                                    if (a == null) continue;
                                    bubble = findMMNeatTextView(a, start);
                                    if (bubble != null) break;
                                }
                                if (bubble == null) {
                                    for (Object a : param.args) {
                                        if (a == null) continue;
                                        if (a instanceof View) {
                                            bubble = findBubbleViewInTree((View) a, 0, start);
                                            if (bubble != null) break;
                                        } else if ("e9".equals(a.getClass().getSimpleName())
                                                || hasFieldType(a)) {
                                            bubble = findBubbleView(a, start);
                                            if (bubble != null) break;
                                        }
                                    }
                                }
                                if (bubble == null) bubble = findBubbleView(param.thisObject, start);
                                if (bubble == null) return;
                                // v3.0.142（v7 §11）：时间条/系统提示/非内容视图不替换
                                if (!isReplaceableContentView(bubble)) return;
                                // v3.0.100: 方向判定仍以 to.b 自带的 isRecv 布尔为准（语义最准）。
                                // 若该重载无布尔参数，则由 bubble 背景匹配白名单兜底。
                                int kind = isRecv ? KIND_FROM : KIND_TO;
                                // v3.0.131: 无自定义气泡图时仍可仅应用文字颜色
                                int color = kind == KIND_FROM ? sFromTextColor : sToTextColor;
                                if (loadDrawable(kind) != null || color != 0) {
                                    applyBubbleTo(bubble, kind);
                                    if (loadDrawable(kind) != null) {
                                        LogWriter.log(TAG, "viewitems.b REPLACE isRecv=" + isRecv
                                                + " kind=" + kind
                                                + " view=" + bubble.getClass().getName()
                                                + " neat=" + bubble.getClass().getName().contains("MMNeat")
                                                + " parent=" + parentChain(bubble, 2));
                                    } else {
                                        LogWriter.log(TAG, "viewitems.b COLORONLY isRecv=" + isRecv
                                                + " kind=" + kind
                                                + " view=" + bubble.getClass().getName());
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked++;
                    LogWriter.log(TAG, "viewitems.b hooked " + owner + "." + methodSig
                            + " loader=" + HookUtil.loaderName(toCls.getClassLoader()));
                }
            }
        }
        if (hooked == 0) {
            LogWriter.log(TAG, "viewitems.b none found (fallback setBackgroundResource covers)");
        }
    }

    // ---------------- v3.0.150 聊天时间线 / 群聊昵称颜色（《新聊天气泡替换.md》v8 §12.3/§12.5） ----------------
    // 时间线 = h0.timeTV(0x7f0a10b0=2131366064)，昵称 = h0.userTV(0x7f0a10be=2131366078)。
    // 在 viewitems.b after 内执行：天然限定聊天 item，无需再走 inChatItem 门。
    private static void applyTimeNickColor(Object[] args) {
        int tColor = sTimeTextColor;
        int nColor = sNickTextColor;
        if (tColor == 0 && nColor == 0) return;
        long start = android.os.SystemClock.uptimeMillis();
        View root = null;
        // 优先使用参数中直接的 View（mq.b(View,boolean,boolean) 等）。
        for (Object a : args) {
            if (a instanceof View) { root = (View) a; break; }
        }
        // 无直接 View 时，用参数对象内 find 到的气泡 TextView 上溯 item 根。
        if (root == null) {
            View leaf = null;
            for (Object a : args) {
                if (a == null) continue;
                leaf = findMMNeatTextView(a, start);
                if (leaf != null) break;
            }
            if (leaf == null) {
                for (Object a : args) {
                    if (a == null) continue;
                    leaf = findBubbleView(a, start);
                    if (leaf != null) break;
                }
            }
            if (leaf != null) root = findItemRoot(leaf, start);
        }
        if (root == null) return;
        try {
            final android.widget.TextView tv;
            final android.widget.TextView nv;
            {
                View t = root.findViewById(ID_TIME_TV);
                tv = (t instanceof android.widget.TextView) ? (android.widget.TextView) t : null;
                View n = root.findViewById(ID_NICK_TV);
                nv = (n instanceof android.widget.TextView) ? (android.widget.TextView) n : null;
            }
            if (tv != null && tColor != 0) {
                tv.setTextColor(tColor);
                LogWriter.log(TAG, "timeTV color 0x" + Integer.toHexString(tColor));
            }
            if (nv != null && nColor != 0) {
                nv.setTextColor(nColor);
                LogWriter.log(TAG, "userTV color 0x" + Integer.toHexString(nColor));
            }
            // v3.0.158 诊断：延迟回读最终文字色，判断是被微信随后覆盖(值回退)还是视图不可见(值保留)。
            final int ft = tColor, fn = nColor;
            root.postDelayed(() -> {
                try {
                    if (tv != null && ft != 0) {
                        LogWriter.log(TAG, "timeTV VERIFY cur=0x" + Integer.toHexString(tv.getCurrentTextColor())
                                + " want=0x" + Integer.toHexString(ft)
                                + " vis=" + tv.getVisibility() + " w=" + tv.getWidth());
                    }
                    if (nv != null && fn != 0) {
                        LogWriter.log(TAG, "userTV VERIFY cur=0x" + Integer.toHexString(nv.getCurrentTextColor())
                                + " want=0x" + Integer.toHexString(fn)
                                + " vis=" + nv.getVisibility() + " w=" + nv.getWidth());
                    }
                } catch (Throwable ignored) {}
            }, 500);
        } catch (Throwable ignored) {}
    }

    /** 从气泡叶子向上爬父链，返回第一个含时间线/昵称 id 的 item 根（限 8 层）。 */
    private static View findItemRoot(View leaf, long start) {
        View cur = leaf;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (android.os.SystemClock.uptimeMillis() - start > 30L) return null;
            if (cur.findViewById(ID_TIME_TV) != null || cur.findViewById(ID_NICK_TV) != null) return cur;
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return null;
    }

    /** v3.0.120：遍历 holder 对象字段，找类型为 MMNeat7extView 的 View —— 文字气泡真实承载视图。 */
    private static View findMMNeatTextView(Object holder, long start) {
        if (holder == null) return null;
        try {
            Class<?> c = holder.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    // v3.0.136：预算保护
                    if (android.os.SystemClock.uptimeMillis() - start > 30L) return null;
                    try {
                        Class<?> ft = f.getType();
                        if (ft == null || !View.class.isAssignableFrom(ft)) continue;
                        boolean typeHits = ft.getName().contains("MMNeat");
                        f.setAccessible(true);
                        Object v = f.get(holder);
                        if (!(v instanceof View)) continue;
                        View view = (View) v;
                        if (typeHits || view.getClass().getName().contains("MMNeat")) return view;
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 打印 View 的父链（最多 maxLevel 层），用于定位文字气泡真实视图。 */
    private static String parentChain(View v, int maxLevel) {
        if (v == null) return "";
        StringBuilder sb = new StringBuilder();
        View cur = v;
        int level = 0;
        while (cur != null && level <= maxLevel) {
            if (sb.length() > 0) sb.append(" -> ");
            sb.append(cur.getClass().getSimpleName());
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            level++;
        }
        return sb.toString();
    }

    /** 递归在 View 树中查找背景与微信原生气泡匹配的气泡 View。
     *  v3.0.136：限制深度(4层)/节点数(200)/耗时(30ms)，防止遍历整棵 View 树卡死主线程。 */
    private static View findBubbleViewInTree(View v, int depth, long start) {
        if (v == null) return null;
        if (depth > 4) return null;
        if (sTreeVisitCount >= 200) return null;
        sTreeVisitCount++;
        if (android.os.SystemClock.uptimeMillis() - start > 30L) return null;
        try {
            if (matchBaseDrawable(v.getBackground()) >= 0) return v;
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findBubbleViewInTree(g.getChildAt(i), depth + 1, start);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 判断对象是否带 field_type 字段（微信消息/收藏实体特征）。 */
    private static boolean hasFieldType(Object o) {
        if (o == null) return false;
        try {
            XposedHelpers.getIntField(o, "field_type");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文档 §5 方案2：链接/自动识别文本子类型 hn5.r0.g0(收)/hn5.s0.k0(发)，
     *  after 中直接替换 MMNeat7extView 背景。 */
    private static void installLinkSubtypeHook(ClassLoader cl) {
        try {
            Class<?> neatCls = XposedHelpers.findClass("com.tencent.mm.ui.widget.MMNeat7extView", cl);
            String[] inners = {
                    "com.tencent.mm.ui.chatting.viewitems.hn5$r0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5.r0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5$s0",
                    "com.tencent.mm.ui.chatting.viewitems.hn5.s0"
            };
            for (String cn : inners) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 1) continue;
                        if (m.getParameterTypes()[0] != neatCls) continue;
                        m.setAccessible(true);
                        final String owner = cn;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    Object v = param.args[0];
                                    if (!(v instanceof View)) return;
                                    // r0=收(chatfrom), s0=发(chatto)
                                    int kind = m.getName().equals("g0") ? KIND_FROM : KIND_TO;
                                    // v3.0.131: 无自定义气泡图时仍可仅应用文字颜色
                                    int color = kind == KIND_FROM ? sFromTextColor : sToTextColor;
                                    if (loadDrawable(kind) != null || color != 0) {
                                        applyBubbleTo((View) v, kind);
                                        if (loadDrawable(kind) != null) {
                                            LogWriter.log(TAG, "link subtype REPLACE " + owner
                                                    + "." + m.getName() + " kind=" + kind);
                                        } else {
                                            LogWriter.log(TAG, "link subtype COLORONLY " + owner
                                                    + "." + m.getName() + " kind=" + kind);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        LogWriter.log(TAG, "link subtype hooked " + cn + "." + m.getName());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installLinkSubtypeHook err: " + t.getMessage());
        }
    }

    /** 在 ViewHolder 字段中定位气泡 View：优先背景与 ke5.a.i 记录的微信原生气泡
     *  （constantState）匹配的 View（真实气泡背景就是 chatfrom_bg/chatto_bg），
     *  其次 MMNeat7extView（文本视图本身做气泡背景），再按字段名 b/d/f 兜底。
     *  遍历时打印 View 字段信息，便于下次日志确认微信实际气泡 View 落在哪个字段。 */
    private static View findBubbleView(Object holder, long start) {
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                // v3.0.136：预算保护
                if (android.os.SystemClock.uptimeMillis() - start > 30L) return null;
                try {
                    f.setAccessible(true);
                    Object v = f.get(holder);
                    if (!(v instanceof View)) continue;
                    View view = (View) v;
                    String cn = view.getClass().getName();
                    String bg = view.getBackground() == null ? "null"
                            : view.getBackground().getClass().getName();
                    LogWriter.log(TAG, "  holderField " + c.getSimpleName() + "." + f.getName()
                            + " view=" + cn + " w=" + view.getWidth() + " h=" + view.getHeight()
                            + " bg=" + bg);
                    if (matchBaseDrawable(view.getBackground()) >= 0) {
                        return view;
                    }
                    if (cn.contains("MMNeat7extView") || cn.contains("MMNeatTextView")
                            || cn.contains("Neat")) {
                        return view;
                    }
                    String fn = f.getName();
                    if (("b".equals(fn) || "d".equals(fn) || "f".equals(fn))
                            && view instanceof android.widget.TextView) {
                        return view;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** 与 ke5.a.i 记录的微信原始气泡 Drawable 对比 constantState。
     *  文本气泡背景是 StateListDrawable（selector），内部状态子项含原生气泡资源，
     *  递归匹配子 Drawable，否则文本气泡（selector）会被 setBackground 拦截路径跳过。 */
    private static int matchBaseDrawable(Drawable d) {
        if (d == null) return -1;
        try {
            if (sFromBaseDrawable != null && sameConstant(sFromBaseDrawable, d)) return KIND_FROM;
            if (sToBaseDrawable != null && sameConstant(sToBaseDrawable, d)) return KIND_TO;
            // v3.0.135：候选气泡资源匹配（文字气泡实际资源可能非 chatfrom_bg/chatto_bg）
            for (Drawable cand : sExtraFromBaseDrawables) {
                if (sameConstant(cand, d)) return KIND_FROM;
            }
            for (Drawable cand : sExtraToBaseDrawables) {
                if (sameConstant(cand, d)) return KIND_TO;
            }
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable sld =
                        (android.graphics.drawable.StateListDrawable) d;
                for (int i = 0; i < sld.getStateCount(); i++) {
                    int k = matchBaseDrawable(sld.getStateDrawable(i));
                    if (k >= 0) return k;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean sameConstant(Drawable a, Drawable b) {
        return a.getConstantState() != null && a.getConstantState().equals(b.getConstantState());
    }

    /** v3.0.135：文字消息气泡 setBackground 未被 constantState 匹配时输出诊断信息
     *  （drawable 结构 + baseDrawable 类型 + 调用栈），用于定位 8.0.78 文字气泡真实资源/绑定路径。
     *  仅聊天文本视图触发、按 key 去重、封顶 30 条，避免刷屏。 */
    private static final java.util.Set<String> sUnmatchedDumped = new java.util.HashSet<>();

    private static void debugDumpUnmatchedTextBubble(View v, Drawable d) {
        try {
            if (!CALIBRATE) return;
            String vn = v.getClass().getName();
            if (!vn.contains("Neat") && !isChatMvvmView(v)) return; // v3.0.138: MVVM 视图一并诊断
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            String caller = st.length > 4 ? st[4].getClassName() + "." + st[4].getMethodName() : "?";
            String key = vn + "|" + d.getClass().getName() + "|" + caller;
            synchronized (sUnmatchedDumped) {
                if (!sUnmatchedDumped.add(key)) return;
                if (sUnmatchedDumped.size() > 30) return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("UNMATCHED textBubble view=").append(vn)
              .append(" drawable=").append(d.getClass().getName());
            try {
                if (d.getConstantState() != null) {
                    sb.append(" cs=").append(d.getConstantState().getClass().getName());
                }
            } catch (Throwable ignored) {}
            if (d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable sld =
                        (android.graphics.drawable.StateListDrawable) d;
                sb.append(" states=").append(sld.getStateCount());
                for (int i = 0; i < sld.getStateCount() && i < 6; i++) {
                    Drawable sub = sld.getStateDrawable(i);
                    sb.append(" [").append(i).append("]=")
                      .append(sub == null ? "null" : sub.getClass().getName());
                    if (sub != null && sub.getConstantState() != null) {
                        sb.append("@").append(Integer.toHexString(
                                System.identityHashCode(sub.getConstantState())));
                    }
                }
            }
            sb.append(" sFrom=").append(sFromBaseDrawable == null ? "null" :
                    sFromBaseDrawable.getClass().getName() + "@" + Integer.toHexString(
                            System.identityHashCode(sFromBaseDrawable.getConstantState())));
            sb.append(" sTo=").append(sToBaseDrawable == null ? "null" :
                    sToBaseDrawable.getClass().getName() + "@" + Integer.toHexString(
                            System.identityHashCode(sToBaseDrawable.getConstantState())));
            sb.append(" chat=").append(inChatItem(v))
              .append(" parent=").append(parentChain(v, 2));
            LogWriter.log(TAG, sb.toString());
            int n = Math.min(st.length, 15);
            for (int i = 2; i < n; i++) {
                LogWriter.log(TAG, "  at " + st[i].getClassName() + "." + st[i].getMethodName()
                        + (st[i].getLineNumber() > 0 ? ":" + st[i].getLineNumber() : ""));
            }
        } catch (Throwable ignored) {}
    }

    /** 是否为"本模块构建的自定义气泡图"。
     *  v3.0.128：不再把所有 {@link android.graphics.drawable.NinePatchDrawable} 一律视为自定义
     *  （微信原生气泡本身就是 NinePatchDrawable，会被误判从而挡住启发式替换）；
     *  改为 NineSliceDrawable 直接命中 + 用 constantState 与本模块缓存态比对。 */
    private static boolean isCustomBackground(Drawable d) {
        if (d == null) return false;
        if (d instanceof BubbleDrawableFactory.NineSliceDrawable) return true;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                if (sFromDrawableState != null && cs.equals(sFromDrawableState)) return true;
                if (sToDrawableState != null && cs.equals(sToDrawableState)) return true;
                if (cs.getClass().getName().contains("NineSlice")) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 方案 D：Resources.getDrawable 加载路径（聊天页加载气泡背景的最通用入口）。
     *  ke5.a.i 只在启动预构建时调用一次，kw5.g.r / setBackgroundResource 在 8.0.78 实测不触发；
     *  微信聊天 item 很可能通过 Resources.getDrawable(resId) 加载气泡九宫格。 */
    private static void installResourceGetDrawableHook(ClassLoader cl) {
        try {
            Class<?> resCls = XposedHelpers.findClass("android.content.res.Resources", cl);
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        int resId = ((Number) param.args[0]).intValue();
                        boolean from = resId == sFromResId;
                        boolean to = resId == sToResId;
                        if (!from && !to) return;
                        int kind = from ? KIND_FROM : KIND_TO;
                        // 打印资源名 + 调用栈，确认是否为聊天气泡背景加载
                        try {
                            Object ctx = ContextManager.getAppContext();
                            android.content.res.Resources res = (android.content.res.Resources) param.thisObject;
                            String name = res.getResourceEntryName(resId);
                            LogWriter.log(TAG, "getDrawable resId=" + resId + " name=" + name);
                        } catch (Throwable ignored) {}
                        try {
                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                            StringBuilder sb = new StringBuilder("getDrawable stack:");
                            int n = Math.min(st.length, 6);
                            for (int i = 2; i < n; i++) {
                                sb.append("\n  ").append(st[i].getClassName())
                                        .append('.').append(st[i].getMethodName());
                            }
                            LogWriter.log(TAG, sb.toString());
                        } catch (Throwable ignored) {}
                        // 不替换 getDrawable 返回值：ke5.a.i / setBackgroundResource 内部
                        // 会再次加载并设置到真实气泡 View，这里只记录，避免污染 baseDrawable。
                        // 真实替换点：setBackgroundResource / ImageView.setImageDrawable / onLayout。
                    } catch (Throwable ignored) {}
                }
            };
            for (Method m : resCls.getDeclaredMethods()) {
                String mn = m.getName();
                if (!"getDrawable".equals(mn)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0] == int.class) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, h);
                        LogWriter.log(TAG, "Resources.getDrawable(int) hooked");
                    } catch (Throwable ignored) {}
                } else if (pts.length == 2 && pts[0] == int.class) {
                    try {
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, h);
                        LogWriter.log(TAG, "Resources.getDrawable(int,Theme) hooked");
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installResourceGetDrawableHook err: " + t.getMessage());
        }
    }

    /** v3.0.158：强制时间线/群昵称文字色。
     *  applyTimeNickColor 在 bind 早期套色后，微信随后会再次调用 setTextColor 覆盖回默认
     *  （v3.0.158 日志 timeTV VERIFY cur=0x4dffffff want=0xff76ff03）。此 hook 直接拦截
     *  TextView.setTextColor，凡作用在 ID_TIME_TV / ID_NICK_TV 上的调用一律改写成用户设定色，
     *  保证「最后一次写」也是用户色。 */
    private static void installTextColorForceHook(ClassLoader cl) {
        try {
            Method m = android.widget.TextView.class.getDeclaredMethod("setTextColor", int.class);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length == 0) return;
                        Object o = p.thisObject;
                        if (!(o instanceof android.widget.TextView)) return;
                        int id = ((android.widget.TextView) o).getId();
                        if (id == ID_TIME_TV && sTimeTextColor != 0) {
                            p.args[0] = sTimeTextColor;
                        } else if (id == ID_NICK_TV && sNickTextColor != 0) {
                            p.args[0] = sNickTextColor;
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "TextView.setTextColor(int) forced hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installTextColorForceHook(int) err: " + t.getMessage());
        }
        try {
            Method m2 = android.widget.TextView.class.getDeclaredMethod(
                    "setTextColor", android.content.res.ColorStateList.class);
            XposedBridge.hookMethod(m2, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args == null || p.args.length == 0) return;
                        Object o = p.thisObject;
                        if (!(o instanceof android.widget.TextView)) return;
                        int id = ((android.widget.TextView) o).getId();
                        int c = 0;
                        if (id == ID_TIME_TV) c = sTimeTextColor;
                        else if (id == ID_NICK_TV) c = sNickTextColor;
                        if (c != 0) p.args[0] = android.content.res.ColorStateList.valueOf(c);
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "TextView.setTextColor(ColorStateList) forced hook ok");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installTextColorForceHook(CSL) err: " + t.getMessage());
        }
    }

    /** 方案 B：X2C Drawable 加载路径，hook ke5.a.i(Context,int) 按 resId 替换。 */
    /** 方案 F：聊天列表渲染兜底。微信聊天 item 在 8.0.78 不经过 ke5.a.i/getDrawable/
     *  setBackground 设置气泡背景，而是在 X2C 预构建/代码构建的 item 上直接使用缓存 Drawable。
     *  Hook AbsListView / RecyclerView 的 onLayout，每次布局后遍历 item 树，
     *  凡背景与 ke5.a.i 记录的微信原生气泡 Drawable（constantState）匹配即替换。 */
    private static void installChatListViewHook(ClassLoader cl) {
        try {
            Method onLayout = null;
            for (Method m : AbsListView.class.getDeclaredMethods()) {
                if ("onLayout".equals(m.getName())) { onLayout = m; break; }
            }
            if (onLayout != null) {
                onLayout.setAccessible(true);
                XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!sEnabled) return;
                            // v3.0.162：restoreRecordedBubbles 只看 BUBBLE 登记表（安全，无容器名依赖），
                            // 混淆容器 isChatContainer 失配时也能补盖；递归遍历才要求聊天容器。
                            restoreRecordedBubbles();
                            if (!isChatContainer((View) param.thisObject)) return;
                            replaceMatchingBubbles((View) param.thisObject);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "AbsListView.onLayout hooked");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "AbsListView.onLayout err: " + t.getMessage());
        }
        for (String cn : new String[]{"androidx.recyclerview.widget.RecyclerView",
                "android.support.v7.widget.RecyclerView"}) {
            try {
                Class<?> rv = XposedHelpers.findClass(cn, cl);
                Method onLayout = null;
                for (Method m : rv.getDeclaredMethods()) {
                    if ("onLayout".equals(m.getName())) { onLayout = m; break; }
                }
                if (onLayout != null) {
                    onLayout.setAccessible(true);
                    XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                // v3.0.162：同 AbsListView 路径 —— 表驱动 restore 无条件执行，
                                // 递归遍历仅限聊天容器。
                                restoreRecordedBubbles();
                                if (!isChatContainer((View) param.thisObject)) return;
                                replaceMatchingBubbles((View) param.thisObject);
                            } catch (Throwable ignored) {}
                        }
                    });
                    LogWriter.log(TAG, "RecyclerView.onLayout hooked " + cn);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** v3.0.89：收集当前进程当前 UI 的 View 与调用链验证。
     *  日志显示 setBackgroundResource REPLACE 大量命中无关 TextView/AnimImageView
     *  （w=0 h=0），真实气泡 View 尚未定位。此 hook 在微信聊天列表刷新后遍历
     *  View 树，打印所有 View 的类名/宽高/背景，结合 ke5.a.i 调用栈确认真实气泡 View；
     *  ke5.a.i 返回值替换法保留，真实气泡路径确认后再替换。 */
    private static void installChattingListCollector(ClassLoader cl) {
        try {
            List<String> cands = new ArrayList<>();
            cands.add("com.tencent.mm.ui.chatting.ChattingList");
            try {
                List<String> dk = DexKitHelper.findClassesByString(cl, "refreshChattingList");
                for (String cn : dk) if (!cands.contains(cn)) cands.add(cn);
            } catch (Throwable ignored) {}
            int hooked = 0;
            for (String cn : cands) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, cl);
                    for (Method m : c.getDeclaredMethods()) {
                        if (!"refreshChattingList".equals(m.getName())) continue;
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    Object thiz = param.thisObject;
                                    if (thiz instanceof View) {
                                        dumpViewTree((View) thiz, "refreshChattingList");
                                    } else {
                                        LogWriter.log(TAG, "refreshChattingList thiz="
                                                + (thiz == null ? "null" : thiz.getClass().getName()));
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        hooked++;
                        LogWriter.log(TAG, "ChattingList.refreshChattingList hooked cls="
                                + c.getName() + " m=" + m);
                    }
                } catch (Throwable ignored) {}
            }
            if (hooked == 0) {
                try {
                    Class<?> rv = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", cl);
                    Method onLayout = null;
                    for (Method m : rv.getDeclaredMethods()) {
                        if ("onLayout".equals(m.getName())) { onLayout = m; break; }
                    }
                    if (onLayout != null) {
                        onLayout.setAccessible(true);
                        XposedBridge.hookMethod(onLayout, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    dumpViewTree((View) param.thisObject, "RecyclerView.onLayout");
                                } catch (Throwable ignored) {}
                            }
                        });
                        hooked++;
                        LogWriter.log(TAG, "RecyclerView.onLayout dump hooked (fallback)");
                    }
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "ChattingList collector hooks=" + hooked);
        } catch (Throwable t) {
            LogWriter.log(TAG, "installChattingListCollector err: " + t.getMessage());
        }
    }

    /** 递归打印 View 树：类名、宽高、屏幕坐标、背景，用于确认真实气泡 View。 */
    private static void dumpViewTree(View v, String src) {
        if (v == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("view=").append(v.getClass().getName())
                    .append(" w=").append(v.getWidth()).append(" h=").append(v.getHeight());
            try {
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                sb.append(" xy=").append(loc[0]).append(",").append(loc[1]);
            } catch (Throwable ignored) {}
            Drawable bg = v.getBackground();
            sb.append(" bg=").append(bg == null ? "null" : bg.getClass().getName());
            LogWriter.log(TAG, "chattingView[" + src + "] " + sb);
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpViewTree(g.getChildAt(i), src);
            }
        }
    }

    /** 记录已替换为自定义气泡的 View，供布局完成后强制恢复（文本气泡修复）。 */
    private static void rememberBubble(View v, Drawable custom) {
        if (v == null || custom == null) return;
        synchronized (sBubbleViews) {
            sBubbleViews.put(v, custom);
        }
    }

    /** v3.0.128：记录气泡 View 及其方向，供补盖时同步文字颜色。 */
    private static void rememberBubble(View v, Drawable custom, int kind) {
        if (v == null || custom == null) return;
        synchronized (sBubbleViews) {
            sBubbleViews.put(v, custom);
        }
        try {
            synchronized (sBubbleKind) { sBubbleKind.put(v, kind); }
        } catch (Throwable ignored) {}
    }

    /** v3.0.162（《新WeChatChatBubbleReplace.md》§14 step 2）：把 BUBBLE 表内 attached 的语音气泡
     *  补盖。微信录音/播放会走 setType(3) 清背景（type==3 分支已不就地 REPLACE），
     *  这里统一按登记方向强制恢复并设置 VISIBLE —— 长按说话后气泡不再消失。
     *  v3.0.162 纠偏：此函数只遍历 BUBBLE 登记表（bubol 的 AnimImageView/ID_MQ_E/U/D 专表），
     *  不再要求 isReplaceableContentView（该函数排除了 AnimImageView，塞了会把语音气泡全滤掉）。
     *  v3.0.163（用户反馈「初始不渲染，上滑后渲染」）：初始 attach 后 AnimImageView 处于
     *  vis=8(GONE)（日志 bubble VERIFY AnimImageView w=254 h=127 vis=8 custom=true），且背景已是
     *  custom —— 旧逻辑遇「已贴 custom」直接短路，GONE 态永远得不到 forceVisible，直到滚动
     *  recycle 触发 setType/mq.b 重绑才变 VISIBLE。现改为：只要 isVoiceImageView 且 attached，
     *  要么 GONE（无条件补盖强制 VISIBLE），要么背景已被微信清（补盖），已可见且 custom 才跳过。 */
    private static void coverVoiceBubblesOnLayout() {
        final java.util.List<View> need = new java.util.ArrayList<>();
        synchronized (sBubble) {
            for (java.util.Map.Entry<View, Boolean> e : sBubble.entrySet()) {
                View v = e.getKey();
                if (v == null) continue;
                try {
                    if (!v.isAttachedToWindow()) continue;
                    if (!isVoiceImageView(v)) continue;
                    boolean gone = v.getVisibility() != View.VISIBLE;
                    boolean customBg = isCustomBackground(v.getBackground());
                    if (!gone && customBg) continue;   // 已可见且已贴 custom —— 真无需处理
                    need.add(v);                       // 其余：GONE→补盖强 VISIBLE；背景被清→重贴
                } catch (Throwable ignored) {}
            }
        }
        for (View v : need) {
            try {
                Boolean r;
                synchronized (sBubble) { r = sBubble.get(v); }
                if (r == null) continue;
                applyVoiceBubble(v, r, true);
                LogWriter.log(TAG, "voice cover onLayout kind=" + (r ? KIND_FROM : KIND_TO)
                        + " view=" + v.getClass().getName()
                        + " w=" + v.getWidth() + " h=" + v.getHeight()
                        + " vis=" + v.getVisibility());
            } catch (Throwable ignored) {}
        }
    }

    /** 语音气泡承载视图判定：仅 AnimImageView（mq.e/mq.u 真承载）。
     *  v3.0.162 纠偏：此前把 ID_MQ_D（mq.D TextView 备用承载）也纳入，cover 会给 mq.D 贴气泡并
     *  forceVisible，导致 AnimImageView 被挤成 w=0 h=0 vis=8（GONE），语音气泡整体不渲染。
     *  按《新WeChatChatBubbleReplace.md》§13.3：真承载 = mq.e(2131366091)/mq.u(2131366096)
     *  两个 AnimImageView；mq.D(2131365816) 是 TextView 备用承载，不参与补盖。 */
    private static boolean isVoiceImageView(View v) {
        return isAnimImage(v);
    }

    /** v3.0.162：setType(3) 清背景后，若 onLayout 未触发（混淆容器 isChatContainer 失配），
     *  延迟一帧主动补盖全部已登记语音气泡，保证长按说话时气泡持续显示。 */
    private static final AtomicBoolean sVoiceCoverScheduled = new AtomicBoolean(false);
    private static void scheduleVoiceCover() {
        if (!sVoiceCoverScheduled.compareAndSet(false, true)) return;
        try {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                sVoiceCoverScheduled.set(false);
                try {
                    if (!sEnabled) return;
                    coverVoiceBubblesOnLayout();
                } catch (Throwable ignored) {}
            }, 120);
        } catch (Throwable ignored) {
            sVoiceCoverScheduled.set(false);
        }
    }

    /** 聊天列表布局后，把已记录的气泡 View 背景强制恢复为自定义图。
     *  v3.0.128：改用「背景是否仍为自定义图」判定（constantState 级），避免每帧重复补盖；
     *  恢复时用独立实例并同步文字颜色/内边距。 */
    private static void restoreRecordedBubbles() {
        coverVoiceBubblesOnLayout();
        final java.util.List<View> need = new java.util.ArrayList<>();
        synchronized (sBubbleViews) {
            for (java.util.Iterator<java.util.Map.Entry<View, Drawable>> it =
                 sBubbleViews.entrySet().iterator(); it.hasNext(); ) {
                java.util.Map.Entry<View, Drawable> e = it.next();
                View v = e.getKey();
                if (v == null) continue;
                try {
                    if (!v.isShown()) continue;
                    if (!inChatItem(v) && !isChatTextBubble(v)) continue; // v3.0.134: MMNeat 文本气泡放行
                    if (!isReplaceableContentView(v)) continue; // v3.0.142（v7 §11）：时间条/系统提示过滤
                    if (!isCustomBackground(v.getBackground())) need.add(v);
                } catch (Throwable ignored) {}
            }
        }
        for (View v : need) {
            try {
                int kind = kindOf(v);
                Drawable d = fresh(loadDrawable(kind));
                if (d == null) continue;
                v.setBackground(d);
                rememberBubble(v, d, kind);
                applyDrawablePadding(v, d);
                applyTextColor(v, kind);
                syncBubblePadding(v);
                LogWriter.log(TAG, "bubble restore view=" + v.getClass().getName()
                        + " w=" + v.getWidth() + " h=" + v.getHeight());
            } catch (Throwable ignored) {}
        }
    }

    /** 递归遍历 View 树，把背景与微信原生气泡匹配的 View 替换为用户图片。 */
    private static void replaceMatchingBubbles(View v) {
        if (v == null) return;
        restoreRecordedBubbles();
        try {
            int kind = matchBaseDrawable(v.getBackground());
            if (kind >= 0) {
                // v3.0.132: 第一层门控 —— 只有聊天 item 内的 view 才允许替换
                // v3.0.134: MMNeat7extView（聊天文本专用）在 X2C 预构建/复用路径放行
                if (!inChatItem(v) && !isChatTextBubble(v)) return;
                // v3.0.142（v7 §11）：时间条/系统提示/非内容视图过滤
                if (!shouldReplaceBg(v, v.getBackground())) return;
                Drawable custom = loadDrawable(kind);
                if (custom != null) {
                    v.setBackground(custom);
                    LogWriter.log(TAG, "onLayout REPLACE kind=" + kind
                            + " view=" + v.getClass().getName());
                }
            }
        } catch (Throwable ignored) {}
        // v3.0.132: 已删除几何启发式（《聊天气泡修复文档》），仅保留微信原生气泡 constantState 匹配。
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                replaceMatchingBubbles(g.getChildAt(i));
            }
        }
    }

    /** v3.0.91：hook X2C 背景应用点 kw5.i0.f(Context, View, String, Drawable)。
     *  文档证实 gm.g/gm.i 通过 kw5.i0.f 把气泡 Drawable 设置到 MMNeat7extView，
     *  此处直接替换 Drawable 参数，命中真实气泡 View。
     *  ke5.a.i 返回值替换后 custom 不匹配 baseDrawable，此处保持放行；
     *  未走 ke5.a.i 的路径（如直接 getDrawable）在此拦截替换。 */
    private static void installBubbleApplyHook(ClassLoader cl) {
        try {
            boolean hooked = false;
            for (Class<?> c : HookUtil.loadClasses(cl, "kw5.i0")) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (!"f".equals(m.getName()) || pts.length != 4
                            || pts[0] != Context.class || pts[1] != View.class
                            || pts[2] != String.class || pts[3] != Drawable.class) {
                        continue;
                    }
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!sEnabled) return;
                                Drawable d = (Drawable) param.args[3];
                                if (param.args.length < 2 || !(param.args[1] instanceof View)) return;
                                if (!shouldReplaceBg((View) param.args[1], d)) return; // v3.0.142（v7 §11）
                                int kind = matchBaseDrawable(d);
                                if (kind < 0) return;
                                Drawable custom = loadDrawable(kind);
                                if (custom != null) {
                                    param.args[3] = custom;
                                    LogWriter.log(TAG, "i0.f REPLACE kind=" + kind
                                            + " view=" + param.thisObject.getClass().getName());
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                    hooked = true;
                    LogWriter.log(TAG, "bubble apply hooked " + c.getName() + "."
                            + m.getName() + Arrays.toString(pts)
                            + " loader=" + HookUtil.loaderName(c.getClassLoader()));
                }
            }
            if (!hooked) {
                LogWriter.log(TAG, "bubble apply hook none found (kw5.i0.f)");
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installBubbleApplyHook err: " + t.getMessage());
        }
    }

    private static void installResourceHelperHook(ClassLoader cl) {
        try {
            if (!DexKitHelper.isScanComplete()) return;
            List<String> classes = new ArrayList<>();
            try {
                classes.addAll(DexKitHelper.findClassesByString(cl, "MicroMsg.ResourceHelper"));
            } catch (Throwable ignored) {}
            if (!classes.contains("ke5.a")) classes.add("ke5.a");
            for (String cn : classes) {
                java.util.Set<Class<?>> loaded = HookUtil.loadClasses(cl, cn);
                if (loaded.isEmpty()) {
                    try {
                        loaded.add(XposedHelpers.findClass(cn, cl));
                    } catch (Throwable ignored) {}
                }
                for (Class<?> c : loaded) {
                    for (Method m : c.getDeclaredMethods()) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (!"i".equals(m.getName()) || pts.length != 2
                                || pts[0] != Context.class || pts[1] != int.class) {
                            continue;
                        }
                        m.setAccessible(true);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!sEnabled) return;
                                    int resId = ((Number) param.args[1]).intValue();
                                    boolean from = resId == sFromResId;
                                    boolean to = resId == sToResId;
                                    if (!from && !to) return;
                                    // 打印资源名 + 调用栈，确认是否为聊天气泡背景加载
                                    try {
                                        Context ctx = (Context) param.args[0];
                                        String name = ctx.getResources().getResourceEntryName(resId);
                                        LogWriter.log(TAG, "ke5.a.i resId=" + resId + " name=" + name);
                                    } catch (Throwable ignored) {}
                                    try {
                                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                        StringBuilder sb = new StringBuilder("ke5.a.i stack:");
                                        int n = Math.min(st.length, 8);
                                        for (int i = 2; i < n; i++) {
                                            sb.append("\n  ").append(st[i].getClassName())
                                                    .append('.').append(st[i].getMethodName());
                                        }
                                        LogWriter.log(TAG, sb.toString());
                                    } catch (Throwable ignored) {}
                                    try {
                                        // 记录微信原始气泡 Drawable（资源缓存实例，用于 setBackground 拦截识别）。
                                        // 此时 getDrawable 不替换，拿到的是微信原始气泡。
                                        Context ctx0 = (Context) param.args[0];
                                        Drawable base = ctx0.getResources().getDrawable(resId);
                                        if (from) {
                                            sFromBaseDrawable = base;
                                        } else if (to) {
                                            sToBaseDrawable = base;
                                        }
                                        LogWriter.log(TAG, "ke5.a.i baseDrawable saved from="
                                                + (from ? "y" : "n") + " to=" + (to ? "y" : "n"));
                                    } catch (Throwable ignored) {}
                                } catch (Throwable ignored) {}
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                // v3.0.90：ke5.a.i 返回值是气泡 Drawable 的最终加载点
                                // （日志证实 viewitems.mq.b 调用 ke5.a.i），在此替换返回值，
                                // 微信后续不再重新覆盖原始气泡背景。
                                try {
                                    if (!sEnabled) return;
                                    int resId = ((Number) param.args[1]).intValue();
                                    boolean from = resId == sFromResId;
                                    boolean to = resId == sToResId;
                                    if (!from && !to) return;
                                    if (!isChatStack()) return; // v3.0.131: 非聊天调用不替换返回值
                                    int kind = from ? KIND_FROM : KIND_TO;
                                    Drawable custom = loadDrawable(kind);
                                    if (custom != null) {
                                        param.setResult(custom);
                                        LogWriter.log(TAG, "ke5.a.i REPLACE RETURN resId=" + resId
                                                + " kind=" + kind);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        LogWriter.log(TAG, "ResourceHelper hooked " + c.getName() + ".i");
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "installResourceHelperHook err: " + t.getMessage());
        }
    }

    private static void installBubbleResolver(ClassLoader cl) throws Throwable {
        // v3.0.170：首启首次全量扫描完成前直接等待（后台线程最多 30s），
        // 避免 2s×10 次盲等导致聊天窗口气泡在扫描完成前用默认样式。
        DexKitHelper.waitForFullScanIfScheduled();
        if (!DexKitHelper.isScanComplete()) {
            throw new IllegalStateException("DexKit scan not ready");
        }
        // 文档第 5 节：search_strings("chatfrom_bg"/"chatto_bg") → X2C 生成类，
        // 其父类才是资源解析包装层（含 r(Context,View,String,String,int)→Drawable）。
        List<String> genClasses = new ArrayList<>();
        for (String kw : new String[]{"chatfrom_bg", "chatto_bg"}) {
            try {
                List<String> sigs = DexKitHelper.findMethodsByString(cl, null, kw);
                for (String sig : sigs) {
                    String cn = classNameOf(sig);
                    if (cn != null && !genClasses.contains(cn)) genClasses.add(cn);
                }
            } catch (Throwable ignored) {}
        }
        if (genClasses.isEmpty()) {
            throw new NoSuchMethodException("no X2C class contains chatfrom_bg/chatto_bg");
        }
        LogWriter.log(TAG, "X2C gen classes=" + genClasses);
        Method target = null;
        Class<?> owner = null;
        for (String cn : genClasses) {
            // v3.0.91：必须用 HookUtil.loadClasses 遍历所有候选 ClassLoader
            //（Tinker DelegateLastClassLoader 才是运行时真实类，单个 cl 会 hook 空）
            for (Class<?> c : HookUtil.loadClasses(cl, cn)) {
                LogWriter.log(TAG, "X2C candidate " + cn + " loader="
                        + HookUtil.loaderName(c.getClassLoader()));
                for (Class<?> sup = c.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
                    Method m = findResolver(sup);
                    if (m != null) {
                        target = m;
                        owner = sup;
                        break;
                    }
                }
                if (target != null) break;
            }
            if (target != null) break;
        }
        if (target == null) {
            throw new NoSuchMethodException("resolver r(Context,View,String,String,int) not found");
        }
        final Class<?> fOwner = owner;
        LogWriter.log(TAG, "resolver class=" + fOwner.getName()
                + " method=" + target.getName() + Arrays.toString(target.getParameterTypes()));
        target.setAccessible(true);
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String value = (String) param.args[3];
                    int resId = param.args.length > 4 && param.args[4] instanceof Number
                            ? ((Number) param.args[4]).intValue() : 0;
                    boolean isFrom = resId == sFromResId
                            || (value != null && value.contains("chatfrom_bg"));
                    boolean isTo = resId == sToResId
                            || (value != null && value.contains("chatto_bg"));
                    if (isFrom || isTo) {
                        int kind = isFrom ? KIND_FROM : KIND_TO;
                        if (!sEnabled) return;
                        View v0 = param.args.length > 1 && param.args[1] instanceof View
                                ? (View) param.args[1] : null;
                        // 抖动抑制 v3.0.120：背景已是自定义图时仍必须 setResult(custom)，
                        // 阻止原始 r() 继续执行把微信原生气泡流出覆盖我们的替换；
                        // 仅跳过重复 setBackground，避免形成替换风暴。
                        boolean bgAlreadyCustom = v0 != null && isCustomBackground(v0.getBackground());
                        if (!sFirstResolverLogged) {
                            sFirstResolverLogged = true;
                            String path = kind == KIND_FROM ? sFromPath : sToPath;
                            LogWriter.log(TAG, "resolver first hit value=" + value + " resId=" + resId
                                    + " path=" + (path != null ? path : "null"));
                        }
                        Drawable d = loadDrawable(kind);
                        if (d != null) {
                            param.setResult(d);
                            if (v0 != null && !bgAlreadyCustom) {
                                v0.setBackground(d);
                                rememberBubble(v0, d);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    private static Method findResolver(Class<?> c) {
        if (c == null) return null;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] pts = m.getParameterTypes();
            if ("r".equals(m.getName()) && pts.length == 5
                    && pts[0] == Context.class && pts[1] == View.class
                    && pts[2] == String.class && pts[3] == String.class
                    && pts[4] == int.class) {
                return m;
            }
        }
        return null;
    }

    /** 从 "pkg.Cls.method(params)" 解析出声明类全名。 */
    private static String classNameOf(String sig) {
        if (sig == null) return null;
        int p = sig.indexOf('(');
        if (p < 0) p = sig.length();
        String head = sig.substring(0, p);
        int dot = head.lastIndexOf('.');
        return dot > 0 ? head.substring(0, dot) : null;
    }

    private static Drawable loadDrawable(int kind) {
        Context ctx = ContextManager.getAppContext();
        if (ctx == null) return null;
        String path = kind == KIND_FROM ? sFromPath : sToPath;
        if (path == null || path.isEmpty()) return null;
        // 路径不变时复用已构建的 Drawable 模板：缓存 ConstantState，每次 newDrawable() 出新实例，
        // 避免列表滚动时反复解码/构造，同时防止多 View 共享同一可变 Drawable。
        synchronized (sDrawableCache) {
            Drawable.ConstantState cached = kind == KIND_FROM ? sFromDrawableState : sToDrawableState;
            String cachedPath = kind == KIND_FROM ? sFromDrawablePath : sToDrawablePath;
            if (cached != null && path.equals(cachedPath)) {
                Drawable d = cached.newDrawable(ctx.getResources());
                if (d != null) { d.mutate(); return d; }
            }
            Drawable built = BubbleDrawableFactory.build(ctx, path);
            if (built == null) {
                LogWriter.log(TAG, "loadDrawable BUILD FAILED kind=" + kind + " path=" + path
                        + " exists=" + new java.io.File(path).exists());
                return null;
            }
            LogWriter.log(TAG, "loadDrawable built kind=" + kind + " cls=" + built.getClass().getName()
                    + " path=" + path + " iw=" + built.getIntrinsicWidth() + "ih=" + built.getIntrinsicHeight());
            Drawable.ConstantState cs = built.getConstantState();
            if (kind == KIND_FROM) {
                sFromDrawableState = cs;
                sFromDrawablePath = path;
            } else {
                sToDrawableState = cs;
                sToDrawablePath = path;
            }
            return built;
        }
    }

    // ==================== v3.0.125 运行时自校准 + 启发式兜底 ====================

    /**
     * 运行时自校准（方案②）：在本机"全量、不白名单"地记录所有气泡背景设置点，
     * 导出真实 (viewClass, resId, name, xy, parent) 映射，供日志人工核对后固化。
     * 覆盖：View.setBackgroundResource/setBackground/setBackgroundDrawable、
     * TextView/ImageView 覆盖点、Resources.getDrawable(int)/(int,Theme)。
     * 记录按签名去重并封顶 CAL_MAX 条，避免日志暴涨。
     */
    private static void installCalibrationHooks(ClassLoader cl) {
        if (!CALIBRATE) return;
        try {
            hookBgSetter(View.class, "setBackgroundResource", int.class, "setBackgroundResource");
            hookBgSetter(View.class, "setBackground", Drawable.class, "setBackground");
            hookBgSetter(View.class, "setBackgroundDrawable", Drawable.class, "setBackgroundDrawable");
            // 用户怀疑漏网点：TextView 老 API（若子类声明才命中，未声明则静默跳过）
            hookBgSetter(android.widget.TextView.class, "setBackgroundDrawable", Drawable.class, "TextView.setBackgroundDrawable");
            hookBgSetter(android.widget.TextView.class, "setBackgroundResource", int.class, "TextView.setBackgroundResource");
            hookBgSetter(ImageView.class, "setImageDrawable", Drawable.class, "setImageDrawable");
            hookBgSetter(ImageView.class, "setImageResource", int.class, "setImageResource");
            installGetDrawableCalibration(cl);
            LogWriter.log(TAG, "calibration hooks installed (CALIBRATE=" + CALIBRATE + ")");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installCalibrationHooks err: " + t.getMessage());
        }
    }

    /** 通用背景设置点校准日志：不判断 resId 是否在白名单，仅按签名去重记录。 */
    private static void hookBgSetter(Class<?> owner, String name, Class<?> argType, String tag) {
        try {
            Method m = null;
            for (Method mm : owner.getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == argType) {
                    m = mm;
                    break;
                }
            }
            if (m == null) return;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof View)) return;
                        View v = (View) param.thisObject;
                        Object a0 = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        int resId = a0 instanceof Number ? ((Number) a0).intValue() : 0;
                        String drawableCls = a0 instanceof Drawable ? a0.getClass().getName() : "-";
                        String key = tag + "|" + v.getClass().getName() + "|" + resId + "|" + drawableCls;
                        if (!sCalSeen.add(key)) return;
                        if (sCalCount.incrementAndGet() > CAL_MAX) return;
                        int[] loc = {0, 0};
                        try { v.getLocationOnScreen(loc); } catch (Throwable ignored) {}
                        LogWriter.log(TAG, "CAL " + tag
                                + " view=" + v.getClass().getName()
                                + " resId=" + resId + " name=" + (resId != 0 ? entryName(resId) : "-")
                                + " drawable=" + drawableCls
                                + " xy=(" + loc[0] + "," + loc[1] + ") w=" + v.getWidth() + " h=" + v.getHeight()
                                + " chat=" + inChatItem(v)
                                + " parent=" + parentChain(v, 2));
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookBgSetter " + tag + " err: " + t.getMessage());
        }
    }

    /** 校准 Resources.getDrawable：仅当调用栈含聊天/气泡关键字时记录（避免全 App 刷屏）。 */
    private static void installGetDrawableCalibration(ClassLoader cl) {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Number)) return;
                        StackTraceElement[] st = Thread.currentThread().getStackTrace();
                        boolean chat = false;
                        String caller = "?";
                        int n = Math.min(st.length, 14);
                        for (int i = 2; i < n; i++) {
                            String c = st[i].getClassName();
                            if (i == 2) caller = c + "." + st[i].getMethodName();
                            if (c.contains("chatting") || c.contains("Chatting")
                                    || c.contains("viewitems") || c.contains("Bubble")
                                    || c.contains("bubble")) { chat = true; break; }
                        }
                        if (!chat) return;
                        int resId = ((Number) param.args[0]).intValue();
                        String key = "getDrawable|" + resId;
                        if (!sCalSeen.add(key)) return;
                        if (sCalCount.incrementAndGet() > CAL_MAX) return;
                        LogWriter.log(TAG, "CAL getDrawable resId=" + resId
                                + " name=" + entryName(resId) + " caller=" + caller);
                    } catch (Throwable ignored) {}
                }
            };
            for (Method m : android.content.res.Resources.class.getDeclaredMethods()) {
                if (!"getDrawable".equals(m.getName())) continue;
                Class<?>[] pts = m.getParameterTypes();
                if ((pts.length == 1 && pts[0] == int.class)
                        || (pts.length == 2 && pts[0] == int.class)) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, h);
                }
            }
            LogWriter.log(TAG, "getDrawable calibration hooked");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installGetDrawableCalibration err: " + t.getMessage());
        }
    }

    /**
     * 方案③ 兜底：hook LayoutInflater.inflate(int, ViewGroup, boolean)。
     * v3.0.132 已按《聊天气泡修复文档》删除 —— 几何启发式是主页/输入框误伤元凶。
     */

    // ==================== v3.0.128 统一补盖（根治偶发不渲染） ====================

    /**
     * 从模板 Drawable 派生一个可独立变更的新实例。
     *
     * <p>修复「共享 Drawable 实例 → bounds/callback 互踩 → 偶发不画」：多 View 若共用同一
     * Drawable 实例，滚动时会互相覆盖 setBounds，导致部分气泡不渲染。每次 newDrawable().mutate()
     * 出新实例即可隔离。</p>
     */
    private static Drawable fresh(Drawable template) {
        if (template == null) return null;
        try {
            Drawable.ConstantState cs = template.getConstantState();
            if (cs != null) {
                Drawable d = cs.newDrawable();
                if (d != null) {
                    d.mutate();
                    return d;
                }
            }
        } catch (Throwable ignored) {}
        try { return template.mutate(); } catch (Throwable ignored) {}
        return template;
    }

    /**
     * 统一把某个 View 应用为自定义气泡：设置独立背景实例 + 同步内边距(触发 NeatTextView 重排)
     * + 应用文字颜色 + requestLayout/invalidate。所有替换路径（setBackgroundResource、
     * bind-after、attach-after、onLayout、启发式）都收敛到此处，行为一致。
     */
    private static void applyBubbleTo(View v, int kind) {
        if (v == null || !sEnabled) return;
        if (sIdBlack.contains(v.getId())) return; // v3.0.142（v7 §11）：时间条/系统提示 id 黑名单
        try {
            Drawable custom = loadDrawable(kind);
            if (custom != null) {
                Drawable d = fresh(custom);
                v.setBackground(d);
                rememberBubble(v, d, kind);
                applyDrawablePadding(v, d);
                syncBubblePadding(v);
                try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
            }
            // v3.0.131: 文字颜色独立于气泡图片生效（未设置图片时也应能修改文字颜色）
            applyTextColor(v, kind);
        } catch (Throwable ignored) {}
    }

    /** 应用气泡内文字颜色（0 = 不修改）。文本气泡承载视图可能是 MMNeat7extView（非 TextView），
     *  其 setTextColor 会把颜色同步到内层 wrappedTextView。
     *  v3.0.131：若目标视图是容器（如 TextView 气泡内嵌文本视图），递归对子树内可见的
     *  TextView 一并设置，确保颜色真正落到显示文字的视图上。 */
    private static void applyTextColor(View v, int kind) {
        int color = kind == KIND_FROM ? sFromTextColor : sToTextColor;
        if (color == 0 || v == null) return;
        applyTextColorInner(v, color, 0);
    }

    private static void applyTextColorInner(View v, int color, int depth) {
        if (v == null || depth > 6) return;
        try {
            if (v instanceof android.widget.TextView) {
                ((android.widget.TextView) v).setTextColor(color);
                return; // TextView 无子视图
            }
        } catch (Throwable ignored) {}
        try {
            Method m = v.getClass().getMethod("setTextColor", int.class);
            m.setAccessible(true);
            m.invoke(v, color);
            return;
        } catch (Throwable ignored) {}
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyTextColorInner(g.getChildAt(i), color, depth + 1);
            }
        }
    }

    /**
     * v3.0.141：用自定义气泡 Drawable 的 {@link Drawable#getPadding(Rect)} 作为 View 的内容内边距。
     * 微信原生气泡由 NinePatchDrawable 的 padding 指定文字内容区；而自定义气泡若是普通 png
     * （NineSliceDrawable）无 padding → 替换后文字贴边/压到圆角上。此处显式把 drawable
     * 的内容边距应用到 View，文字自动缩进到气泡安全区。
     */
    private static void applyDrawablePadding(View v, Drawable d) {
        if (v == null || d == null) return;
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            if (d.getPadding(r)
                    && (r.left != 0 || r.top != 0 || r.right != 0 || r.bottom != 0)) {
                v.setPadding(r.left, r.top, r.right, r.bottom);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 重新下发一次 padding（值不变）以触发 {@code MMNeat7extView.setPadding} 重写 →
     * 同步内层 wrappedTextView 的 padding 并重算 Layout 缓存。不复排会导致换了背景后
     * 文字不随气泡伸缩（看着像没渲染/错位）。
     */
    private static void syncBubblePadding(View v) {
        if (v == null) return;
        try {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom());
        } catch (Throwable ignored) {}
    }

    /** v3.0.138：布局完成后的几何方向判定（x 偏右=自己发出）。不可用返回 -1。 */
    private static int guessKindByLayout(View v) {
        if (v == null) return -1;
        try {
            int w = v.getWidth();
            if (w <= 0) return -1;
            int[] loc = {0, 0};
            v.getLocationOnScreen(loc);
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            if (screenW <= 0 || loc[0] + w / 2 <= 0) return -1;
            return (loc[0] + w / 2) > screenW / 2 ? KIND_TO : KIND_FROM;
        } catch (Throwable ignored) {}
        return -1;
    }

    /** 取已记录的气泡方向；优先 BUBBLE 捕获表（微信直供 isRecv），其次已记录 kind，最后 x 坐标几何兜底。 */
    private static int kindOf(View v) {
        try {
            Integer k = sBubbleKind.get(v);
            if (k != null) return k;
        } catch (Throwable ignored) {}
        try {
            Boolean recv = sBubble.get(v);
            if (recv != null) return recv ? KIND_FROM : KIND_TO;
        } catch (Throwable ignored) {}
        try {
            int[] loc = {0, 0};
            v.getLocationOnScreen(loc);
            int screenW = v.getResources().getDisplayMetrics().widthPixels;
            if (screenW > 0 && (loc[0] + v.getWidth() / 2) > screenW / 2) return KIND_TO;
        } catch (Throwable ignored) {}
        return KIND_FROM;
    }

    /**
     * 补盖：微信 {@code AnimImageView.setType()} 在复用/空态分支会调用
     * {@code setBackgroundDrawable(null)} 把气泡清空；此处对"已记录的气泡 View"无条件补回自定义图。
     * v3.0.132：仅 BUBBLE 捕获表（微信亲自贴过气泡的 View）内的 view 会被补盖，不再启发式。
     */
    private static void reapplyIfBubble(View v) {
        if (!sEnabled || v == null) return;
        if (sIdBlack.contains(v.getId())) return; // v3.0.142（v7 §11）
        try {
            Drawable recorded = sBubbleViews.get(v);
            if (recorded != null) {
                int kind = kindOf(v);
                Drawable d = fresh(recorded);
                v.setBackground(d);
                rememberBubble(v, d, kind);
                applyDrawablePadding(v, d);
                applyTextColor(v, kind);
                syncBubblePadding(v);
                try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
                return;
            }
        } catch (Throwable ignored) {}
        // v3.0.132: 未记录自定义图时，只有 BUBBLE 表内的 view 才按方向补盖
        Boolean recv = sBubble.get(v);
        if (recv == null) return;
        int kind = recv ? KIND_FROM : KIND_TO;
        Drawable d = fresh(loadDrawable(kind));
        if (d == null) return;
        v.setBackground(d);
        rememberBubble(v, d, kind);
        applyDrawablePadding(v, d);
        applyTextColor(v, kind);
        syncBubblePadding(v);
        try { v.requestLayout(); v.invalidate(); } catch (Throwable ignored) {}
    }

    /** v3.0.132（《聊天气泡修复文档》第二层）：重盖只走 BUBBLE 表。
     *  hook {@code View.onAttachedToWindow}，仅当该 View 在 BUBBLE 捕获表内（微信亲自贴过气泡）
     *  才 post 补盖；主页/输入框/表情面板不在表内，天然 0 误伤。 */
    private static void installAttachApplyHook(ClassLoader cl) {
        try {
            Method m = View.class.getDeclaredMethod("onAttachedToWindow");
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled) return;
                        View v = (View) param.thisObject;
                        Boolean recv = sBubble.get(v);
                        if (recv == null) return;   // 只动真气泡，别的不碰
                        final View fv = v;
                        final boolean isRecv = recv;
                        fv.post(() -> {
                            try {
                                final boolean pathVoice = isAnimImage(fv)
                                        || fv.getId() == ID_MQ_E || fv.getId() == ID_MQ_U || fv.getId() == ID_MQ_D;
                                int kind;
                                if (pathVoice) {
                                    // v3.0.161：语音视图方向以 mq.b 登记的表为准（几何猜向在 GONE/w=0 时不可靠），
                                    // forceVisible 防 GONE 态不渲染。
                                    kind = isRecv ? KIND_FROM : KIND_TO;
                                    applyVoiceBubble(fv, isRecv, isAnimImage(fv));
                                } else {
                                    // v3.0.162（§14）：方向唯一来源 = to.b/mq.b 的 isRecv 布尔（BUBBLE 表），
                                    // 几何猜向在方向错配时反而更不可靠，直接按登记的可读 recv 覆盖。
                                    kind = isRecv ? KIND_FROM : KIND_TO;
                                    applyBubbleTo(fv, kind);
                                }
                                LogWriter.log(TAG, "attach BUBBLE apply isRecv=" + isRecv
                                        + " kind=" + kind
                                        + " view=" + fv.getClass().getName());
                            } catch (Throwable ignored) {}
                        });
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "onAttachedToWindow apply hooked (BUBBLE)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "installAttachApplyHook err: " + t.getMessage());
        }
    }

    /** 判断容器是否聊天列表容器（RecyclerView/AbsListView/Chatting*）。 */
    private static boolean isChatContainer(View v) {
        if (v == null) return false;
        String cn = v.getClass().getName();
        return v instanceof android.widget.AbsListView
                || cn.contains("RecyclerView")
                || cn.contains("ChattingList")
                || cn.contains("ChattingUI")
                || cn.contains("Chatting");
    }

    /** v3.0.132（《聊天气泡修复文档》第一层）：沿祖先链检查聊天 item 根 keyed tag。
     *  微信聊天 item 根上有全 App 独有的 {@code setTag(0x7f0a103c, ChattingItem)}，
     *  主页会话列表 / ChatFooter 输入框 / 表情面板等均无此 tag，一道祖先链检查全局挡死。 */
    private static boolean inChatItem(View v) {
        if (v == null) return false;
        if (sItemCls == null) {
            try {
                // b0 类可能只在 Tinker DelegateLastClassLoader 下（MEMORY: Tinker 热修复），
                // 用 HookUtil 遍历候选 CL 加载，避免 PathClassLoader 平行副本加载失败。
                for (Class<?> c : HookUtil.loadClasses(sHookCl, "com.tencent.mm.ui.chatting.viewitems.b0")) {
                    sItemCls = c;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        View p = v;
        int depth = 0;
        while (p != null && depth++ < 30) {
            try {
                Object t = p.getTag(TAG_ITEM);
                if (t != null && sItemCls != null && sItemCls.isInstance(t)) return true;
            } catch (Throwable ignored) {}
            android.view.ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /** 沿父链判断 View 是否位于聊天列表内。 */
    private static boolean inChatList(View v) {
        View cur = v;
        int depth = 0;
        while (cur != null && depth++ < 30) {
            if (isChatContainer(cur)) return true;
            android.view.ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }

    /** v3.0.132（《聊天气泡修复文档》）：综合上下文判断，严格走聊天 item tag 门控。
     *  <p>主页会话列表 / ChatFooter / 表情面板的 TextView + StateListDrawable 无
     *  0x7f0a103c tag，在此直接短路，杜绝几何启发式误伤。</p>
     *  <p>v3.0.134：MMNeat7extView 是微信聊天文本专用视图（主页/输入框不使用），
     *  X2C 预构建/RecyclerView 复用路径会在 item attach 前就调用
     *  {@code setBackground(原生气泡)}，此时 inChatItem 找不到 tag 导致文本气泡完全不替换。
     *  对 MMNeat7extView 放行（后续仍有 matchBaseDrawable/resId 白名单二次校验，
     *  主页会话列表/输入框背景不是原生气泡，不会被误伤）。</p>
     *  <p>v3.0.138：MVVM 文本/链接视图（com.tencent.mm.ui.chatting.viewitems.mvvmview.*）
     *  同样在绑定早期设置气泡背景（inChatItem=false），一并放行，类名含 chatting 包名专属聊天。</p> */
    private static boolean isBubbleContext(View v) {
        if (inChatItem(v)) return true;
        if (isChatTextBubble(v)) return true;
        if (isChatMvvmView(v)) return true; // v3.0.138
        return false;
    }

    /** v3.0.134：是否聊天文本气泡专用视图（MMNeat7extView / MMNeatTextView）。 */
    private static boolean isChatTextBubble(View v) {
        if (v == null) return false;
        String cn = v.getClass().getName();
        return cn.contains("MMNeat7extView") || cn.contains("MMNeatTextView")
                || cn.contains("Neat");
    }

    /** v3.0.138：是否聊天 MVVM 文本/链接视图（com.tencent.mm.ui.chatting.viewitems.mvvmview.*）。
     *  微信 8.0.78 文字/链接消息经 MVVM 视图渲染，气泡背景在绑定早期（inChatItem=false）设置。 */
    private static boolean isChatMvvmView(View v) {
        if (v == null) return false;
        String cn = v.getClass().getName();
        return cn.contains("chatting") && cn.contains("MvvmView");
    }

    // ==================== v7 过滤：时间文字 / 系统提示（防过度渲染） ====================

    /** v3.0.142（v7 文档 §11.1）：jh 容器内非内容视图 / 时间条 id 黑名单。
     *  命中这些 id 的视图一律不替换，即使背景是 9-patch。 */
    private static final java.util.Set<Integer> sIdBlack = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    2131366078,  // chatting_time_tv 时间分隔条（本构建实证）
                    0x7f0a1075,  // jh 内“展开”TextView
                    0x7f0a0fc3,  // jh 内历史消息提示
                    0x7f0a0f6c,  // jh 内多选 CheckBox
                    0x7f0a0f74   // jh inflate 的内容根
            ));

    /** v7 §11.2-2：视图身份过滤：MMNeat7extView（聊天文本专用）或带 viewitems.ap tag 的内容 ITV。
     *  时间条 X2CTextView / 系统提示 TextView / 展开/历史提示/CheckBox 均不满足。 */
    private static boolean isContentTv(View v) {
        if (v == null) return false;
        String cn = v.getClass().getName();
        if (cn != null && (cn.contains("MMNeat7extView") || cn.contains("MMNeatTextView"))) return true;
        Object tag = v.getTag();
        if (tag == null) return false;
        String tcn = tag.getClass().getName();
        return tcn != null && tcn.endsWith("viewitems.ap");
    }

    /** v7 §11.2-4：背景类型兜底：气泡 = NinePatch/StateListDrawable；时间/提示 = 无背景或 GradientDrawable。 */
    private static boolean bubbleBg(Drawable bg) {
        return bg instanceof android.graphics.drawable.NinePatchDrawable
                || bg instanceof android.graphics.drawable.StateListDrawable;
    }

    /** v7 §11.2 组合过滤（背景仍为原生时使用，bg 传入即将设置/已设置的原生气泡）。
     *  v3.0.142 补充：聊天 MVVM 视图（ChattingUrlMvvmView 等）是 8.0.78 文本/链接消息的真实
     *  气泡承载视图（v6 文档 §10），同样放行；时间条/系统提示 item 不是 MVVM 视图，不受影响。 */
    private static boolean shouldReplace(View v, Drawable bg) {
        if (!isBubbleContext(v)) return false;
        if (!isChatMvvmView(v) && !isContentTv(v)) return false;
        if (sIdBlack.contains(v.getId())) return false;
        return bubbleBg(bg);
    }

    /** 组合过滤（取 v 当前背景；用于背景已设置好的路径，如 onDraw/setBackgroundResource after）。 */
    private static boolean shouldReplace(View v) {
        return v != null && shouldReplace(v, v.getBackground());
    }

    /** v3.0.142（v7 §11）：通用背景设置点过滤（View.setBackground/Drawable、ImageView、kw5.i0.f 等）。
     *  不要求 isContentTv —— 语音/图片气泡承载视图是 AnimImageView（非 MMNeat7extView、无 ap tag），
     *  只按「聊天上下文 + id 非黑名单 + 背景为气泡类型」过滤，时间条/系统提示仍会被挡下。 */
    private static boolean shouldReplaceBg(View v, Drawable bg) {
        // v3.0.155（《新WeChatChatBubbleReplace.md》§4.3）：语音/图片 anim 气泡在 mq.b 绑定早期
        // 尚未 attach，inChatItem 走 tag 祖先链找不到聊天 item → isBubbleContext 返回 false，
        // 导致 AnimImageView.setType i==1 分支的 setBackgroundDrawable(ke5.a.i) 直改路径被挡。
        // AnimImageView 是语音/图片气泡专属视图类（§10.4：以视图类区分，不会误伤其他 resId），
        // 故对其放行 isBubbleContext，仍保留 id 黑名单 + 气泡背景类型(bubbleBg)双重约束。
        boolean animImg = v != null && v.getClass().getName().contains("AnimImageView");
        if (!isBubbleContext(v) && !animImg) return false;
        if (sIdBlack.contains(v.getId())) return false;
        return bubbleBg(bg);
    }

    /** 补盖/恢复路径用的轻量过滤：只做视图身份 + id 黑名单，不检查背景类型
     *  （此时背景可能是自定义图或已被清空 null）。MVVM 气泡视图同样放行。 */
    private static boolean isReplaceableContentView(View v) {
        if (v == null) return false;
        if (sIdBlack.contains(v.getId())) return false;
        return isChatMvvmView(v) || isContentTv(v);
    }

    /** 资源 ID → 资源名（失败返回 ?）。 */
    private static String entryName(int resId) {
        try {
            Context ctx = ContextManager.getAppContext();
            if (ctx == null || resId == 0) return "-";
            return ctx.getResources().getResourceEntryName(resId);
        } catch (Throwable t) {
            return "?";
        }
    }

    /** v3.0.131: 判断当前调用栈是否来自聊天/朋友圈消息渲染路径。
     *  用于 ke5.a.i 等全局资源入口的返回值替换过滤，避免非聊天界面被误渲染。 */
    private static boolean isChatStack() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            int n = Math.min(st.length, 20);
            for (int i = 2; i < n; i++) {
                String c = st[i].getClassName();
                if (c == null) continue;
                if (c.contains("chatting") || c.contains("Chatting")
                        || c.contains("viewitems") || c.contains("sns")
                        || c.contains("Sns") || c.contains("timeline")
                        || c.contains("Timeline")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** 未解析出 ResourceHelper 时，判断原始气泡 resId 的收/发方向。
     *
     *  <p>报告 §2 白名单（本构建 8.0.78 实测，覆盖普通/发送中/链接三种子场景）：
     *  收到 = {2131231925 普通, 2131231841 发送中, 2131231944 链接}；
     *  发出 = {2131232060 普通, 2131231895 发送中, 2131232070 链接}。
     *  资源名在本 APK 可能被混淆，故先按硬编码 resId 命中，再用 getResourceEntryName 兜底。</p> */
    private static int resolveKindByResId(Context ctx, int resId) {
        if (resId == 0) return -1;
        if (resId == 2131231925 || resId == 2131231841 || resId == 2131231944
                || resId == 2131231853) return KIND_FROM; // 2131231853=AppMsg chat_from_mask_bg(v6 §3)
        if (resId == 2131232060 || resId == 2131231895 || resId == 2131232070
                || resId == 2131232062) return KIND_TO;   // 2131232062=AppMsg chatto_bg_app(v6 §3)
        // v3.0.132（《聊天气泡修复文档》）：AnimImageView.setType i==2 分支语音气泡收发态
        if (resId == 2131100638) return KIND_FROM; // 对方发送态
        if (resId == 2131100639) return KIND_TO;   // 自己发送态
        if (resId == sFromResId) return KIND_FROM;
        if (resId == sToResId) return KIND_TO;
        if (ctx == null) return -1;
        try {
            String name = ctx.getResources().getResourceEntryName(resId);
            if (name == null) return -1;
            if (name.contains("chatfrom")) return KIND_FROM;
            if (name.contains("chatto")) return KIND_TO;
        } catch (Throwable ignored) {}
        return -1;
    }
}
