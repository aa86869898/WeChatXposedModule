package com.leshao.v3.ting;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.LogWriter;
import com.leshao.v3.wm.utils.WmReflect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class TingMusicModule {
    private static final String TAG = "TingMusic";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface OnMusicReady { void onReady(String chatUser, TingMusicInfo info); }
    private static OnMusicReady listener;
    public static void setOnMusicReady(OnMusicReady l) { listener = l; }

    private static Activity currentChatting;
    private static String currentUser;
    private static TingMusicInfo pendingMusic;
    private static WindowManager sWM;
    private static TextView ball;

    public static void hook(ClassLoader cl) {
        hookChatWindow(cl);
        hookTingPlayer(cl);
        hookMediaPlayer();
        hookAudioAnchors();
        hookAudioEngine(cl);
        LogWriter.log(TAG, "hook 完成");
    }

    // ==================== 聊天窗口生命周期 ====================
    private static void hookChatWindow(ClassLoader cl) {
        try {
            Class<?> frag = XposedHelpers.findClass("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            Method m0 = frag.getDeclaredMethod("M0");
            XposedBridge.hookMethod(m0, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Activity act = fragmentActivity(p.thisObject);
                    if (act == null) { LogWriter.log(TAG, "M0 未获取到 Activity"); return; }
                    currentChatting = act;
                    String user = WmReflect.getChatUserFromFragment(p.thisObject);
                    if (user == null || user.isEmpty()) user = WmReflect.getCurrentChatUser(act.getIntent());
                    currentUser = user;
                    LogWriter.log(TAG, "聊天窗口打开 user=" + currentUser);
                    MAIN.postDelayed(() -> showBall(act), 300);
                }
            });
            Method o0 = frag.getDeclaredMethod("O0");
            XposedBridge.hookMethod(o0, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    LogWriter.log(TAG, "聊天窗口关闭，移除悬浮球");
                    removeBall();
                }
            });
            LogWriter.log(TAG, "聊天窗口 M0/O0 hook 完成");
        } catch (Throwable t) {
            LogWriter.log(TAG, "聊天窗口 hook 失败: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Activity fragmentActivity(Object fragment) {
        try {
            Object act = XposedHelpers.callMethod(fragment, "getActivity");
            if (act instanceof Activity) return (Activity) act;
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 悬浮球 ====================
    private static void showBall(final Activity act) {
        try {
            if (ball != null) { updateBallState(); return; }
            if (act == null) return;
            sWM = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
            float d = act.getResources().getDisplayMetrics().density;
            int sz = (int) (52 * d);

            ball = new TextView(act);
            ball.setTextColor(Color.WHITE);
            ball.setTextSize(22);
            ball.setGravity(Gravity.CENTER);
            ball.setBackground(makeCircle(0xE607C160));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    sz, sz,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.RIGHT;
            lp.x = (int) (12 * d);
            lp.y = (int) (120 * d);
            sWM.addView(ball, lp);
            ball.setOnTouchListener(makeTouch());
            updateBallState();
            LogWriter.log(TAG, "悬浮球已显示");
        } catch (Throwable t) {
            LogWriter.log(TAG, "悬浮球显示失败: " + t.getMessage());
        }
    }

    private static void updateBallState() {
        if (ball == null) return;
        if (pendingMusic != null) {
            ball.setText("\u2713");
            ball.setBackground(makeCircle(0xE6FA5151));
        } else {
            ball.setText("\u266A");
            ball.setBackground(makeCircle(0xE607C160));
        }
    }

    private static void removeBall() {
        try {
            if (ball != null && sWM != null) { sWM.removeView(ball); }
        } catch (Throwable ignored) {}
        ball = null;
        sWM = null;
    }

    private static GradientDrawable makeCircle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private static View.OnTouchListener makeTouch() {
        return new View.OnTouchListener() {
            float dx, dy, downX, downY;
            long downTime;
            @Override public boolean onTouch(View v, MotionEvent e) {
                WindowManager.LayoutParams wp = (WindowManager.LayoutParams) v.getLayoutParams();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = wp.x - e.getRawX();
                        dy = wp.y - e.getRawY();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        wp.x = (int) (e.getRawX() + dx);
                        wp.y = (int) (e.getRawY() + dy);
                        try { sWM.updateViewLayout(v, wp); } catch (Throwable ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - downTime < 300
                                && Math.abs(e.getRawX() - downX) < 15
                                && Math.abs(e.getRawY() - downY) < 15) {
                            onClickBall();
                        }
                        return true;
                }
                return false;
            }
        };
    }

    private static void onClickBall() {
        if (pendingMusic != null) {
            final TingMusicInfo info = pendingMusic;
            LogWriter.log(TAG, "开始下载: " + info.title + " url=" + info.dataUrl);
            downloadAudio(info.dataUrl, currentChatting, new DownloadCallback() {
                @Override public void onSuccess(String localPath) {
                    info.localPath = localPath;
                    LogWriter.log(TAG, "下载完成: " + localPath);
                    pendingMusic = null;
                    updateBallState();
                    if (currentChatting != null) {
                        Toast.makeText(currentChatting, "已下载: " + info.title + "\n" + localPath,
                                Toast.LENGTH_LONG).show();
                    }
                    if (listener != null) listener.onReady(currentUser, info);
                }
                @Override public void onFail(String err) {
                    LogWriter.log(TAG, "下载失败: " + err);
                    if (currentChatting != null) {
                        Toast.makeText(currentChatting, "下载失败: " + err, Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            try {
                Class<?> ting = XposedHelpers.findClass("com.tencent.mm.plugin.ting.TingFlutterActivity", currentChatting.getClassLoader());
                Intent i = new Intent(currentChatting, ting);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                currentChatting.startActivity(i);
                LogWriter.log(TAG, "已启动听一听");
            } catch (Throwable t) {
                LogWriter.log(TAG, "启动听一听失败: " + t.getMessage());
                if (currentChatting != null) {
                    Toast.makeText(currentChatting, "启动听一听失败", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // ==================== 播放捕获 ====================
    private static final Set<String> dumpedMethods = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void hookTingPlayer(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass("ul4.a9", cl);
            LogWriter.log(TAG, "播放服务类命中: ul4.a9 -> " + svc.getName());
            int hooked = 0;
            for (Method m : svc.getDeclaredMethods()) {
                if (hookOne(svc, m)) hooked++;
            }
            LogWriter.log(TAG, "已 hook ul4.a9 全部方法共 " + hooked + " 个，用于反查");
        } catch (Throwable t) {
            LogWriter.log(TAG, "播放服务类未找到: " + t.getMessage());
        }
    }

    private static boolean hookOne(Class<?> svc, final Method target) {
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String key = target.getName();
                        boolean first = dumpedMethods.add(key);
                        if (!first) return;
                        LogWriter.log(TAG, "[反查] 方法触发: " + key + "(" + target.getParameterCount() + " 参数)");
                        for (int i = 0; i < p.args.length; i++) {
                            Object arg = p.args[i];
                            if (arg == null) continue;
                            TingMusicInfo info = extract(arg);
                            if (info != null && (info.title != null || info.listenId != null)) {
                                pendingMusic = info;
                                LogWriter.log(TAG, "[反查] 捕获音乐: " + info.toString());
                                MAIN.post(() -> updateBallState());
                                dumpTree(arg, "    arg[" + i + "]", 3);
                            } else {
                                dumpObject(arg, "    arg[" + i + "]");
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "捕获处理失败: " + t.getMessage());
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "hook " + target.getName() + " 失败: " + t.getMessage());
            return false;
        }
    }

    // ==================== 信息提取 ====================
    private static TingMusicInfo extract(Object root) {
        // 1) 文档逻辑：root.d() -> 歌曲信息对象
        TingMusicInfo info = extractFromDoc(root);
        if (info != null) return info;
        // 2) 递归扫描兜底
        return extractByScan(root);
    }

    private static TingMusicInfo extractFromDoc(Object root) {
        try {
            Object zr0 = unwrapZr0(root);
            if (zr0 == null) zr0 = root;
            Object w90 = callNoArg(zr0, "d");
            if (w90 == null) return null;
            TingMusicInfo info = buildInfo(w90);
            if (info == null) return null;
            info.srcId = getter(zr0, "b", "getSrcId", "getSourceId");
            return info;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object unwrapZr0(Object root) {
        Object a = callNoArg(root, "a");
        if (a != null) {
            Object i = callNoArg(a, "i");
            if (i != null) return i;
        }
        return root;
    }

    private static TingMusicInfo buildInfo(Object o) {
        String title = getter(o, "getTitle");
        String listenId = getter(o, "getListenId");
        if (title == null && listenId == null) return null;
        TingMusicInfo info = new TingMusicInfo();
        info.title = title;
        info.listenId = listenId;
        info.author = getter(o, "getAuthor", "getSinger", "getArtist", "getSingerName", "c");
        info.cover = getter(o, "getCover", "getCoverUrl", "getThumbUrl", "getAlbumUrl", "f");
        info.type = getInt(o, "e");
        Object h60 = callNoArg(o, "b");
        if (h60 != null) {
            info.dataUrl = getter(h60, "d", "getDataUrl", "getPlayUrl", "getAudioUrl", "getSongUrl");
            info.webUrl = getter(h60, "getUrl", "getWebUrl", "getPageUrl");
            info.bizUsername = getter(h60, "getBizUsername", "getBizUserName");
        }
        if (info.dataUrl == null) {
            Object f90 = callNoArg(o, "g");
            if (f90 != null) info.dataUrl = getter(f90, "getTid");
        }
        if (info.dataUrl == null) {
            info.dataUrl = getter(o, "getDataUrl", "getPlayUrl", "getAudioUrl", "getSongUrl", "getMediaUrl", "getStreamUrl");
        }
        if (info.webUrl == null) info.webUrl = getter(o, "getWebUrl", "getPageUrl", "getUrl");
        if (info.bizUsername == null) info.bizUsername = getter(o, "getBizUsername", "getBizUserName");
        return info;
    }

    private static int getInt(Object o, String... names) {
        if (o == null) return 0;
        for (Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt != int.class && rt != Integer.class) continue;
            for (String n : names) {
                if (m.getName().equals(n)) {
                    try {
                        Object r = m.invoke(o);
                        return r == null ? 0 : ((Number) r).intValue();
                    } catch (Throwable ignored) {}
                }
            }
        }
        return 0;
    }

    private static TingMusicInfo extractByScan(Object root) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object> q = new ArrayDeque<>();
        q.add(root);
        int scanned = 0;
        while (!q.isEmpty() && scanned < 200) {
            Object cur = q.poll();
            if (cur == null) continue;
            if (!visited.add(cur)) continue;
            scanned++;
            TingMusicInfo info = buildInfo(cur);
            if (info != null) return info;
            try {
                for (Method m : cur.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt == void.class || rt.isPrimitive() || rt == String.class) continue;
                    if (m.getDeclaringClass() == Object.class) continue;
                    try {
                        Object r = m.invoke(cur);
                        if (r != null && !visited.contains(r)) q.add(r);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String getter(Object o, String... names) {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            for (String n : names) {
                if (m.getName().equals(n)) {
                    try {
                        Object r = m.invoke(o);
                        return r == null ? null : r.toString();
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static Object callNoArg(Object o, String name) {
        if (o == null) return null;
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                try { return m.invoke(o); } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static void dumpObject(Object o, String prefix) {
        try {
            LogWriter.log(TAG, prefix + " 类=" + o.getClass().getName());
            int printed = 0;
            for (Method m : o.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String decl = m.getDeclaringClass().getName();
                if (decl.startsWith("java.") || decl.startsWith("android.")
                        || decl.startsWith("com.google.protobuf") || decl.startsWith("javax.")) continue;
                Class<?> rt = m.getReturnType();
                if (rt == void.class) continue;
                try {
                    Object r = m.invoke(o);
                    if (rt == String.class || rt.isPrimitive() || Number.class.isAssignableFrom(rt)) {
                        LogWriter.log(TAG, prefix + "  " + m.getName() + "() -> " + (r == null ? "null" : r));
                    } else {
                        LogWriter.log(TAG, prefix + "  " + m.getName() + "() : " + rt.getName() + (r == null ? " =null" : ""));
                    }
                } catch (Throwable ignored) {}
                if (++printed >= 80) break;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, prefix + " dump 失败: " + t.getMessage());
        }
    }

    private static void dumpTree(Object root, String prefix, int depth) {
        dumpObject(root, prefix);
        if (depth <= 1) return;
        int children = 0;
        for (Method m : root.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            Class<?> rt = m.getReturnType();
            if (rt == void.class || rt.isPrimitive() || rt == String.class) continue;
            String decl = m.getDeclaringClass().getName();
            if (decl.startsWith("java.") || decl.startsWith("android.") || decl.startsWith("com.google.protobuf")) continue;
            try {
                Object r = m.invoke(root);
                if (r == null) continue;
                LogWriter.log(TAG, prefix + "  --" + m.getName() + "()--> " + r.getClass().getName());
                dumpTree(r, prefix + "    ", depth - 1);
                if (++children >= 5) break;
            } catch (Throwable ignored) {}
        }
    }

    // ==================== MediaPlayer 反查 ====================
    private static void hookMediaPlayer() {
        try {
            Class<?> mp = Class.forName("android.media.MediaPlayer");
            int hooked = 0;
            for (Method m : mp.getDeclaredMethods()) {
                if (!m.getName().equals("setDataSource")) continue;
                final Method target = m;
                try {
                    XposedBridge.hookMethod(target, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                StringBuilder sb = new StringBuilder("[MediaPlayer] setDataSource(");
                                for (int i = 0; i < p.args.length; i++) {
                                    Object a = p.args[i];
                                    if (i > 0) sb.append(", ");
                                    if (a instanceof String) sb.append("String=").append(a);
                                    else if (a instanceof android.net.Uri) sb.append("Uri=").append(a);
                                    else sb.append(a == null ? "null" : a.getClass().getName());
                                }
                                sb.append(")");
                                LogWriter.log(TAG, sb.toString());
                                if (mediaPlayerStackDumps < 5) {
                                    mediaPlayerStackDumps++;
                                    StackTraceElement[] st = Thread.currentThread().getStackTrace();
                                    StringBuilder stb = new StringBuilder();
                                    for (int i = 0; i < Math.min(15, st.length); i++) stb.append("    ").append(st[i].toString()).append("\n");
                                    LogWriter.log(TAG, "[MediaPlayer] 调用栈:\n" + stb);
                                }
                                for (Object a : p.args) {
                                    if (a instanceof String) {
                                        String url = (String) a;
                                        if (url.startsWith("http")) {
                                            TingMusicInfo info = new TingMusicInfo();
                                            info.title = url;
                                            info.dataUrl = url;
                                            pendingMusic = info;
                                            LogWriter.log(TAG, "[MediaPlayer] 捕获直链: " + url);
                                            MAIN.post(() -> updateBallState());
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                LogWriter.log(TAG, "[MediaPlayer] 处理失败: " + t.getMessage());
                            }
                        }
                    });
                    hooked++;
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "[MediaPlayer] 已 hook setDataSource 共 " + hooked + " 个重载");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[MediaPlayer] hook 失败: " + t.getMessage());
        }
    }

    private static int mediaPlayerStackDumps = 0;

    // ==================== 底层播放锚点反查 ====================
    private static int anchorStackDumps = 0;

    private static void hookAudioAnchors() {
        try {
            Class<?> am = Class.forName("android.media.AudioManager");
            for (Method m : am.getDeclaredMethods()) {
                if (m.getName().equals("requestAudioFocus")) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                LogWriter.log(TAG, "[AudioFocus] requestAudioFocus 触发, result=" + p.getResult());
                                dumpAnchorStack("AudioFocus");
                            }
                        });
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) { LogWriter.log(TAG, "[AudioFocus] hook 失败: " + t.getMessage()); }

        try {
            Class<?> at = Class.forName("android.media.AudioTrack");
            Method play = at.getDeclaredMethod("play");
            XposedBridge.hookMethod(play, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    LogWriter.log(TAG, "[AudioTrack] play 触发");
                    dumpAnchorStack("AudioTrack");
                }
            });
            LogWriter.log(TAG, "[AudioTrack] play 已 hook");
        } catch (Throwable t) { LogWriter.log(TAG, "[AudioTrack] hook 失败: " + t.getMessage()); }
    }

    private static void dumpAnchorStack(String label) {
        if (anchorStackDumps >= 8) return;
        anchorStackDumps++;
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(25, st.length); i++) sb.append("    ").append(st[i].toString()).append("\n");
        LogWriter.log(TAG, "[" + label + "] 调用栈:\n" + sb);
    }

    // ==================== 播放引擎类反查 ====================
    private static void hookAudioEngine(ClassLoader cl) {
        String[] targets = {
                "android.content.BierkontDaedeelt",
                "p35.d",
                "q35.e",
                "c4.g",
                "c4.h"
        };
        for (String name : targets) {
            Class<?> c;
            try {
                c = XposedHelpers.findClass(name, cl);
            } catch (Throwable t) {
                try { c = Class.forName(name); }
                catch (Throwable t2) {
                    LogWriter.log(TAG, "[反查类] 未找到 " + name);
                    continue;
                }
            }
            LogWriter.log(TAG, "[反查类] 命中 " + name + " -> " + c.getName());
            int hooked = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (hookOneReverse(c, m)) hooked++;
            }
            LogWriter.log(TAG, "[反查类] 已 hook " + name + " 共 " + hooked + " 个方法");
        }
    }

    private static boolean hookOneReverse(Class<?> c, final Method target) {
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String key = target.getDeclaringClass().getName() + "." + target.getName();
                        boolean first = dumpedMethods.add(key);
                        if (first) {
                            StringBuilder sig = new StringBuilder();
                            for (Class<?> pt : target.getParameterTypes()) sig.append(pt.getSimpleName()).append(",");
                            LogWriter.log(TAG, "[反查类] 触发: " + key + "(" + sig + ")");
                        }
                        for (int i = 0; i < p.args.length; i++) {
                            Object arg = p.args[i];
                            if (arg == null) continue;
                            if (arg instanceof String) {
                                LogWriter.log(TAG, "    arg[" + i + "] String=" + arg);
                                continue;
                            }
                            if (arg instanceof android.net.Uri) {
                                LogWriter.log(TAG, "    arg[" + i + "] Uri=" + arg);
                                continue;
                            }
                            if (!first) continue;
                            TingMusicInfo info = extract(arg);
                            if (info != null && (info.title != null || info.listenId != null)) {
                                pendingMusic = info;
                                LogWriter.log(TAG, "    arg[" + i + "] 捕获音乐: " + info);
                                MAIN.post(() -> updateBallState());
                                dumpTree(arg, "    arg[" + i + "]", 3);
                            } else {
                                dumpObject(arg, "    arg[" + i + "]");
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[反查类] 处理失败: " + t.getMessage());
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 下载 ====================
    public interface DownloadCallback {
        void onSuccess(String localPath);
        void onFail(String error);
    }

    private static void downloadAudio(final String url, final Context ctx, final DownloadCallback cb) {
        if (url == null || url.isEmpty()) { cb.onFail("音频链接为空"); return; }
        new Thread(() -> {
            String result = null;
            String error = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                int code = conn.getResponseCode();
                if (code != 200) {
                    error = "HTTP " + code;
                } else {
                    File dir = new File(ctx.getCacheDir(), "ting_music");
                    if (!dir.exists()) dir.mkdirs();
                    File file = new File(dir, "ting_" + System.currentTimeMillis() + guessExt(url));
                    InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(file);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    result = file.getAbsolutePath();
                }
                conn.disconnect();
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String r = result, e = error;
            MAIN.post(() -> {
                if (r != null) cb.onSuccess(r); else cb.onFail(e);
            });
        }).start();
    }

    private static String guessExt(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        int dot = path.lastIndexOf('.');
        if (dot > 0) {
            String ext = path.substring(dot);
            if (ext.length() <= 5) return ext;
        }
        return ".mp3";
    }
}
