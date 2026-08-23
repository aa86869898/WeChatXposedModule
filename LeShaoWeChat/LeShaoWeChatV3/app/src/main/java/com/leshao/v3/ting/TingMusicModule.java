package com.leshao.v3.ting;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.leshao.v3.ContextManager;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ai.AiConst;
import com.leshao.v3.ai.WxReflect;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.service.MessageHandler;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.wm.utils.WmReflect;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static Context appContext;
    private static String lastAudioFile;
    private static volatile Object sendMusicToChatApi;
    private static volatile Object flutterCgiHostApi;
    private static ClassLoader wechatCl;
    private static LinearLayout searchPanel;
    private static ScrollView searchScroll;
    private static String lastKeyword;
    private static int lastOffset;

    // ==================== 自动点歌 ====================
    private static final int ORDER_AUTO = 0;
    private static final int ORDER_CARD = 1;
    private static final int ORDER_VOICE = 2;
    private static final long ORDER_DEDUP_WINDOW_MS = 10_000L;
    private static final java.util.Map<String, Long> orderDedup = new java.util.concurrent.ConcurrentHashMap<>();

    public static void hook(ClassLoader cl) {
        wechatCl = cl;
        hookChatWindow(cl);
        hookPigeonCompanions(cl);
        hookOrderCommand(cl);
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
                }
            });
            Method o0 = frag.getDeclaredMethod("O0");
            XposedBridge.hookMethod(o0, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    LogWriter.log(TAG, "聊天窗口关闭");
                    removeBall();
                    hideSearchPanel();
                    currentChatting = null;
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
            appContext = act.getApplicationContext();
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
            DownloadCallback cb = new DownloadCallback() {
                @Override public void onSuccess(String localPath) {
                    info.localPath = localPath;
                    LogWriter.log(TAG, "下载完成: " + localPath);
                    pendingMusic = null;
                    updateBallState();
                    if (currentChatting != null) {
                        Toast.makeText(toastContext(), "已下载: " + info.title + "\n" + localPath,
                                Toast.LENGTH_LONG).show();
                    }
                    if (listener != null) listener.onReady(currentUser, info);
                }
                @Override public void onFail(String err) {
                    LogWriter.log(TAG, "下载失败: " + err);
                    if (currentChatting != null) {
                        Toast.makeText(toastContext(), "下载失败: " + err, Toast.LENGTH_SHORT).show();
                    }
                }
            };
            if (info.dataUrl != null && !info.dataUrl.isEmpty()) {
                LogWriter.log(TAG, "开始下载: " + info.title + " url=" + info.dataUrl);
                downloadAudio(info.dataUrl, currentChatting, cb);
            } else {
                LogWriter.log(TAG, "从缓存查找音频: " + info.title + " listenId=" + info.listenId);
                findAndCopyCached(info, cb);
            }
        } else {
            showSearchPanel();
        }
    }

    // ==================== 自建搜索 ====================
    private static class Song {
        String songName;
        String singer;
        String dataUrl;
        String appid;
        String webUrl;
        String coverUrl;
        String mid;
        String lyric;
        int duration;
    }

    private interface SearchCallback {
        void onResult(List<Song> songs);
        void onFail(String err);
    }

    private static void searchMusic(final String keyword, final int offset, final SearchCallback cb) {
        try {
            if (keyword == null || keyword.trim().isEmpty()) { cb.onFail("关键词为空"); return; }
            final Object api = flutterCgiHostApi;
            if (api == null) { cb.onFail("CGI api 未捕获，请先进入一次听一听"); return; }
            Method send = null;
            for (Method m : api.getClass().getMethods()) {
                if (m.getName().equals("send") && m.getParameterCount() == 2) { send = m; break; }
            }
            if (send == null) { cb.onFail("未找到 send 方法"); return; }
            final Class<?> cbType = send.getParameterTypes()[1];

            byte[] reqData = buildSearchRequest(keyword.trim(), offset);
            Class<?> reqCls = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiRequest", wechatCl);
            Constructor<?> reqCtor = reqCls.getConstructor(long.class, String.class, byte[].class, boolean.class, long.class);
            Object req = reqCtor.newInstance(4114L, "/cgi-bin/micromsg-bin/musiclivesearch", reqData, false, 0L);
            LogWriter.log(TAG, "[搜索] 请求 " + keyword + " data=" + toUtf8(reqData, 200));

            final AtomicBoolean called = new AtomicBoolean(false);
            MAIN.postDelayed(() -> {
                if (!called.getAndSet(true)) {
                    LogWriter.log(TAG, "[搜索] 超时: " + keyword);
                    cb.onFail("搜索超时，请重新进入听一听后再试");
                }
            }, 15000);

            Object callback = Proxy.newProxyInstance(wechatCl, new Class<?>[]{cbType}, new InvocationHandler() {
                @Override public Object invoke(Object proxy, Method method, Object[] args) {
                    if (called.getAndSet(true)) return null;
                    try {
                        if (args == null || args.length == 0 || args[0] == null) {
                            MAIN.post(() -> cb.onFail("回调无数据"));
                            return null;
                        }
                        Object resp = args[0];
                        LogWriter.log(TAG, "[搜索] 回调类型=" + resp.getClass().getName());
                        Object inner = unwrapResult(resp, cb);
                        if (inner == null) return null;
                        LogWriter.log(TAG, "[搜索] 解包后类型=" + inner.getClass().getName());
                        byte[] data = (byte[]) inner.getClass().getMethod("getData").invoke(inner);
                        LogWriter.log(TAG, "[搜索] 响应 data=" + (data == null ? 0 : data.length) + "B");
                        if (data != null && data.length > 0) {
                            LogWriter.log(TAG, "[搜索] 响应utf8: " + toUtf8(data, 20000));
                            int n = Math.min(data.length, 4096);
                            StringBuilder hex = new StringBuilder("[搜索] 响应hex: ");
                            for (int i = 0; i < n; i++) hex.append(String.format("%02X", data[i]));
                            if (data.length > n) hex.append("...");
                            LogWriter.log(TAG, hex.toString());
                        }
                        List<Song> songs = parseSearchResponse(data);
                        LogWriter.log(TAG, "[搜索] 解析到 " + songs.size() + " 首歌");
                        MAIN.post(() -> cb.onResult(songs));
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[搜索] 回调解析失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
                        MAIN.post(() -> cb.onFail("解析失败: " + t.getMessage()));
                    }
                    return null;
                }
            });
            send.invoke(api, req, callback);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[搜索] 异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
            cb.onFail(t.getMessage());
        }
    }

    private static Object unwrapResult(final Object resp, final SearchCallback cb) {
        try {
            if (resp == null) return null;
            String cn = resp.getClass().getName();
            if (!cn.equals("kotlin.Result")) {
                return resp;
            }
            java.lang.reflect.Field valueField = resp.getClass().getDeclaredField("value");
            valueField.setAccessible(true);
            Object value = valueField.get(resp);
            LogWriter.log(TAG, "[搜索] Result.value 类型=" + (value == null ? "null" : value.getClass().getName()));
            if (value == null) {
                MAIN.post(() -> cb.onFail("CGI 返回空"));
                return null;
            }
            if (value.getClass().getName().contains("Failure")) {
                java.lang.reflect.Field exField = value.getClass().getDeclaredField("exception");
                exField.setAccessible(true);
                Object ex = exField.get(value);
                LogWriter.log(TAG, "[搜索] CGI异常: " + (ex == null ? "null" : ex.toString()));
                MAIN.post(() -> cb.onFail("CGI异常: " + (ex == null ? "未知" : ex.toString())));
                return null;
            }
            return value;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[搜索] 解包失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return resp;
        }
    }

    private static byte[] buildSearchRequest(String keyword, int offset) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            writeField(out, 2, keyword.getBytes(StandardCharsets.UTF_8));
            writeVarintField(out, 3, offset);
            writeField(out, 4, String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static void writeField(java.io.ByteArrayOutputStream out, int field, byte[] value) throws Exception {
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        out.write(value);
    }

    private static void writeVarintField(java.io.ByteArrayOutputStream out, int field, int value) throws Exception {
        writeVarint(out, field << 3);
        writeVarint(out, value);
    }

    private static void writeVarint(java.io.ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static List<Song> parseSearchResponse(byte[] d) {
        List<Song> songs = new ArrayList<>();
        if (d == null || d.length == 0) return songs;
        try {
            int[] pos = {0};
            while (pos[0] < d.length) {
                int[] f = readField(d, pos);
                if (f[0] == 0) break;
                if (f[1] == 2) {
                    int len = readVarint(d, pos);
                    if (pos[0] + len > d.length) break;
                    byte[] sub = new byte[len];
                    System.arraycopy(d, pos[0], sub, 0, len);
                    pos[0] += len;
                    if (f[0] == 2) parseEntry(sub, songs);
                } else {
                    skipWire(d, pos, f[1]);
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[搜索] 响应解析异常: " + t.getMessage());
        }
        return songs;
    }

    private static void parseEntry(byte[] d, List<Song> songs) {
        try {
            int[] pos = {0};
            while (pos[0] < d.length) {
                int[] f = readField(d, pos);
                if (f[0] == 0) break;
                if (f[1] == 2 && f[0] == 2) {
                    int len = readVarint(d, pos);
                    if (pos[0] + len > d.length) break;
                    byte[] sub = new byte[len];
                    System.arraycopy(d, pos[0], sub, 0, len);
                    pos[0] += len;
                    parseSongItem(sub, songs);
                } else {
                    skipWire(d, pos, f[1]);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void parseSongItem(byte[] d, List<Song> songs) {
        try {
            Song s = new Song();
            int[] pos = {0};
            while (pos[0] < d.length) {
                int[] f = readField(d, pos);
                if (f[0] == 0) break;
                if (f[1] == 2 && f[0] == 2) {
                    int len = readVarint(d, pos);
                    if (pos[0] + len > d.length) break;
                    byte[] sub = new byte[len];
                    System.arraycopy(d, pos[0], sub, 0, len);
                    pos[0] += len;
                    parseSongDetail(sub, s);
                } else {
                    skipWire(d, pos, f[1]);
                }
            }
            if (s.songName != null || s.dataUrl != null) songs.add(s);
        } catch (Throwable ignored) {}
    }

    private static void parseSongDetail(byte[] d, Song s) {
        try {
            int[] pos = {0};
            while (pos[0] < d.length) {
                int[] f = readField(d, pos);
                if (f[0] == 0) break;
                if (f[1] == 2) {
                    int len = readVarint(d, pos);
                    if (pos[0] + len > d.length) break;
                    String v = new String(d, pos[0], len, StandardCharsets.UTF_8);
                    pos[0] += len;
                    switch (f[0]) {
                        case 1: s.songName = v; break;
                        case 2: s.singer = v; break;
                        case 3: s.dataUrl = v; break;
                        case 4: s.appid = v; break;
                        case 5: s.webUrl = v; break;
                        case 6: s.coverUrl = v; break;
                        case 9: s.mid = v; break;
                        case 13: s.lyric = v; break;
                    }
                } else if (f[1] == 0) {
                    long v = readVarint(d, pos);
                    LogWriter.log(TAG, "[搜索] songDetail varint field=" + f[0] + " val=" + v);
                    if (f[0] == 7 && s.duration == 0) s.duration = (int) (v / 1000);
                } else {
                    skipWire(d, pos, f[1]);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int readVarint(byte[] d, int[] pos) {
        int result = 0, shift = 0;
        while (pos[0] < d.length) {
            byte b = d[pos[0]++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int[] readField(byte[] d, int[] pos) {
        if (pos[0] >= d.length) return new int[]{0, 0};
        int tag = readVarint(d, pos);
        return new int[]{tag >>> 3, tag & 7};
    }

    private static void skipWire(byte[] d, int[] pos, int wt) {
        if (wt == 0) readVarint(d, pos);
        else if (wt == 1) pos[0] += 8;
        else if (wt == 2) { int len = readVarint(d, pos); pos[0] += len; }
        else if (wt == 5) pos[0] += 4;
    }

    // ==================== 供外部面板调用的公开接口 ====================
    public interface PanelSearchCallback {
        void onResult(java.util.List<java.util.Map<String, String>> songs);
        void onFail(String err);
    }

    public static void searchForPanel(String keyword, final PanelSearchCallback cb) {
        searchMusic(keyword, 0, new SearchCallback() {
            @Override public void onResult(List<Song> songs) {
                final java.util.List<java.util.Map<String, String>> list = new java.util.ArrayList<>();
                if (songs != null) {
                    for (Song s : songs) {
                        java.util.Map<String, String> m = new java.util.HashMap<>();
                        m.put("songName", nvl(s.songName));
                        m.put("singer", nvl(s.singer));
                        m.put("dataUrl", nvl(s.dataUrl));
                        m.put("appid", nvl(s.appid));
                        m.put("webUrl", nvl(s.webUrl));
                        m.put("coverUrl", nvl(s.coverUrl));
                        m.put("mid", nvl(s.mid));
                        m.put("lyric", nvl(s.lyric));
                        m.put("duration", String.valueOf(s.duration));
                        list.add(m);
                    }
                }
                MAIN.post(() -> cb.onResult(list));
            }
            @Override public void onFail(String err) {
                MAIN.post(() -> cb.onFail(err));
            }
        });
    }

    public static void sendSongForPanel(String songName, String singer, String dataUrl,
            String appid, String webUrl, String coverUrl, String mid, String lyric) {
        Song s = new Song();
        s.songName = songName;
        s.singer = singer;
        s.dataUrl = dataUrl;
        s.appid = appid;
        s.webUrl = webUrl;
        s.coverUrl = coverUrl;
        s.mid = mid;
        s.lyric = lyric;
        sendMusic(s);
    }

    // ==================== 自动点歌：消息指令监听 ====================

    private static void hookOrderCommand(ClassLoader cl) {
        try {
            Class<?> storage = XposedHelpers.findClass(AiConst.CLS_STORAGE, cl);
            Class<?> msgCls = XposedHelpers.findClass(AiConst.CLS_MSG, cl);
            Method insert = findInsertMethod(storage, msgCls, AiConst.M_INSERT2);
            if (insert == null) insert = findInsertMethod(storage, msgCls, AiConst.M_INSERT);
            if (insert == null) {
                LogWriter.log(TAG, "[点歌] 未找到消息入库方法");
                return;
            }
            XposedBridge.hookMethod(insert, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object msg = p.args[0];
                        if (msg == null || !WxReflect.isIncomingText(msg)) return;
                        final String talker = WxReflect.talker(msg);
                        final String content = WxReflect.content(msg);
                        MAIN.post(() -> handleOrderCommand(talker, content));
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[点歌] 处理失败: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "[点歌] 指令监听已挂载 (" + insert.getName() + " " + insert.getParameterCount() + " params)");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[点歌] 指令监听挂载失败: " + t.getMessage());
        }
    }

    private static Method findInsertMethod(Class<?> clazz, Class<?> msgCls, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            for (Method m : cur.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length >= 1 && pts[0].equals(msgCls)) return m;
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static void handleOrderCommand(String talker, String content) {
        if (talker == null || content == null) return;
        if (talker.endsWith("@chatroom")) {
            content = MessageHandler.removeSenderPrefix(content);
        }
        String c = content.trim();
        int mode;
        String keyword;
        int idx;
        if ((idx = c.indexOf("卡片点歌")) >= 0) { mode = ORDER_CARD; keyword = c.substring(idx + "卡片点歌".length()).trim(); }
        else if ((idx = c.indexOf("语音点歌")) >= 0) { mode = ORDER_VOICE; keyword = c.substring(idx + "语音点歌".length()).trim(); }
        else if ((idx = c.indexOf("点歌")) >= 0) { mode = ORDER_AUTO; keyword = c.substring(idx + "点歌".length()).trim(); }
        else return;
        int nl = keyword.indexOf('\n');
        if (nl >= 0) keyword = keyword.substring(0, nl).trim();
        if (keyword.isEmpty()) {
            LogWriter.log(TAG, "[点歌] 歌名为空，忽略: " + c);
            return;
        }
        if (keyword.length() > 30) {
            LogWriter.log(TAG, "[点歌] 歌名过长，忽略: " + keyword);
            return;
        }
        long now = System.currentTimeMillis();
        String key = talker + "|" + c;
        Long last = orderDedup.get(key);
        if (last != null && now - last < ORDER_DEDUP_WINDOW_MS) {
            LogWriter.log(TAG, "[点歌] 重复指令，忽略: " + c);
            return;
        }
        orderDedup.put(key, now);
        LogWriter.log(TAG, "[点歌] 收到指令 mode=" + mode + " keyword=" + keyword + " talker=" + talker);
        orderSong(talker, keyword, mode);
    }

    private static void orderSong(final String talker, final String keyword, final int mode) {
        currentUser = talker;
        searchMusic(keyword, 0, new SearchCallback() {
            @Override public void onResult(List<Song> songs) {
                if (songs == null || songs.isEmpty()) {
                    LogWriter.log(TAG, "[点歌] 无结果: " + keyword);
                    return;
                }
                Song first = songs.get(0);
                boolean card, voice;
                if (mode == ORDER_CARD) { card = true; voice = false; }
                else if (mode == ORDER_VOICE) { card = false; voice = true; }
                else { card = false; voice = true; }
                LogWriter.log(TAG, "[点歌] 命中 " + first.songName + " - " + first.singer
                        + " dataUrl=" + (first.dataUrl != null) + " card=" + card + " voice=" + voice);
                if (card) orderSendCard(first);
                if (voice) orderSendVoice(first, talker);
            }
            @Override public void onFail(String err) {
                LogWriter.log(TAG, "[点歌] 搜索失败: " + err);
            }
        });
    }

    private static void orderSendCard(Song s) {
        TtsVoiceSender.sOrderCardSentAt = System.currentTimeMillis();
        sendMusic(s);
    }

    private static void orderSendVoice(final Song s, final String talker) {
        if (s.dataUrl == null || s.dataUrl.isEmpty()) {
            LogWriter.log(TAG, "[点歌] 无音频直链，跳过语音: " + s.songName);
            return;
        }
        final Context ctx = appContext != null ? appContext
                : (currentChatting != null ? currentChatting : ContextManager.getAppContext());
        if (ctx == null) {
            LogWriter.log(TAG, "[点歌] 无上下文，跳过语音");
            return;
        }
        LogWriter.log(TAG, "[点歌] 开始下载语音: " + s.songName);
        downloadAudio(s.dataUrl, ctx, new DownloadCallback() {
            @Override public void onSuccess(final String localPath) {
                LogWriter.log(TAG, "[点歌] 语音下载完成: " + localPath);
                new Thread(() -> {
                    try {
                        boolean ok = TtsVoiceSender.sendMp3Voice(talker, localPath);
                        LogWriter.log(TAG, "[点歌] 语音发送 " + (ok ? "成功" : "失败") + ": " + s.songName);
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[点歌] 语音发送异常: " + t.getMessage());
                    }
                }, "leshao-voice-send").start();
            }
            @Override public void onFail(String error) {
                LogWriter.log(TAG, "[点歌] 语音下载失败: " + error);
            }
        });
    }

    private static Context toastContext() {
        if (currentChatting != null) return currentChatting;
        if (appContext != null) return appContext;
        return ContextManager.getAppContext();
    }

    private static void sendMusic(Song s) {
        try {
            if (sendMusicViaK0(s)) {
                MAIN.post(() -> Toast.makeText(toastContext(), "已发送: " + s.songName + " - " + s.singer, Toast.LENGTH_SHORT).show());
                return;
            }
            Object api = sendMusicToChatApi;
            if (api == null) {
                api = tryCreateBn3O();
            }
            if (api == null) {
                MAIN.post(() -> Toast.makeText(toastContext(), "发送失败，未捕获发送入口", Toast.LENGTH_SHORT).show());
                return;
            }
            Method send = null;
            for (Method m : api.getClass().getMethods()) {
                if (m.getName().equals("sendMusicToChat") && m.getParameterCount() == 1) { send = m; break; }
            }
            if (send == null) {
                MAIN.post(() -> Toast.makeText(toastContext(), "未找到 sendMusicToChat", Toast.LENGTH_SHORT).show());
                return;
            }
            Class<?> reqCls = XposedHelpers.findClass("com.tencent.pigeon.flutter_music_chat.SendMusicRequest", wechatCl);
            Constructor<?> ctor = reqCls.getConstructor(String.class, String.class, String.class, String.class,
                    String.class, String.class, String.class, String.class, String.class, Long.class);
            Object req = ctor.newInstance(null, s.songName, s.singer, s.dataUrl, s.appid, s.webUrl,
                    s.coverUrl, s.mid, s.lyric, null);
            LogWriter.log(TAG, "[发送] 走 bn3.o " + s.songName + " - " + s.singer);
            send.invoke(api, req);
            MAIN.post(() -> Toast.makeText(toastContext(), "已发送: " + s.songName + " - " + s.singer, Toast.LENGTH_SHORT).show());
        } catch (Throwable t) {
            LogWriter.log(TAG, "[发送] 异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
            final String err = t.getMessage();
            MAIN.post(() -> Toast.makeText(toastContext(), "发送失败: " + err, Toast.LENGTH_LONG).show());
        }
    }

    private static boolean sendMusicViaK0(Song s) {
        try {
            Class<?> rCls = XposedHelpers.findClass("pt0.r", wechatCl);
            Class<?> pieceCls = XposedHelpers.findClass("com.tencent.mm.pluginsdk.ui.tools.i0", wechatCl);
            Class<?> baseCls = XposedHelpers.findClass("pt0.i", wechatCl);
            Class<?> k0Cls = XposedHelpers.findClass("com.tencent.mm.pluginsdk.model.app.k0", wechatCl);

            String toUser = currentUser;
            if (toUser == null || toUser.isEmpty()) {
                toUser = resolveCurrentUser();
            }
            if (toUser == null || toUser.isEmpty()) {
                LogWriter.log(TAG, "[发送k0] 无法获取目标会话");
                return false;
            }
            LogWriter.log(TAG, "[发送k0] 目标会话=" + toUser + " 歌=" + s.songName);

            Object appmsg = rCls.getDeclaredConstructor().newInstance();
            setPubField(appmsg, "i", 76);
            setPubField(appmsg, "f", nvl(s.songName));
            setPubField(appmsg, "g", nvl(s.singer));
            setPubField(appmsg, "k", nvl(s.webUrl));
            setPubField(appmsg, "l", "");
            setPubField(appmsg, "V", nvl(s.dataUrl));
            setPubField(appmsg, "W", "");
            setPubField(appmsg, "d", nvl(s.appid));

            Object piece = pieceCls.getDeclaredConstructor().newInstance();
            setPubField(piece, "b", nvl(s.coverUrl));
            setPubField(piece, "c", nvl(s.lyric));
            setPubField(piece, "p", nvl(s.mid));
            setPubField(piece, "h", nvl(s.singer));
            setPubField(piece, "f", nvl(s.coverUrl));

            Method attach = findMethod(rCls, "f", 1, baseCls);
            if (attach == null) {
                LogWriter.log(TAG, "[发送k0] 未找到 f(piece) 方法");
                return false;
            }
            attach.invoke(appmsg, piece);

            Object payload = appmsg;
            Method copy = findStaticMethod(rCls, "i", 1, rCls);
            if (copy != null) {
                payload = copy.invoke(null, appmsg);
            }

            Method send = k0Cls.getMethod("I", rCls, String.class, String.class, String.class, String.class, byte[].class);
            String source = readPubField(appmsg, "H");
            Object ret = send.invoke(null, payload, nvl(s.appid), source, toUser, "", null);
            LogWriter.log(TAG, "[发送k0] 完成 ret=" + ret);
            return true;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[发送k0] 异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return false;
        }
    }

    private static String resolveCurrentUser() {
        try {
            Class<?> pa5n0 = XposedHelpers.findClass("pa5.n0", wechatCl);
            Class<?> ja0i0 = XposedHelpers.findClass("ja0.i0", wechatCl);
            Method c = pa5n0.getMethod("c", Class.class);
            Object mgr = c.invoke(null, ja0i0);
            if (mgr == null) return null;
            Object v0 = readPubField(mgr, "e");
            if (v0 == null) return null;
            return readPubField(v0, "a");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[发送k0] 解析会话异常: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static void setPubField(Object obj, String name, Object val) {
        try {
            java.lang.reflect.Field f = climbField(obj.getClass(), name);
            if (f == null) { LogWriter.log(TAG, "[发送k0] 设字段 " + name + " 未找到"); return; }
            f.setAccessible(true);
            if (val instanceof Integer) f.setInt(obj, (Integer) val);
            else f.set(obj, val);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[发送k0] 设字段 " + name + " 失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static String readPubField(Object obj, String name) {
        try {
            java.lang.reflect.Field f = climbField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            Object v = f.get(obj);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static java.lang.reflect.Field climbField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> c, String name, int paramCount, Class<?> paramType) {
        try { return c.getMethod(name, paramType); } catch (Throwable ignored) {}
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    private static Method findStaticMethod(Class<?> c, String name, int paramCount, Class<?> paramType) {
        try {
            Method m = c.getMethod(name, paramType);
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) return m;
        } catch (Throwable ignored) {}
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) return m;
        }
        return null;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static Object tryCreateBn3O() {
        try {
            Class<?> c = XposedHelpers.findClass("bn3.o", wechatCl);
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 0) {
                    ctor.setAccessible(true);
                    Object inst = ctor.newInstance();
                    LogWriter.log(TAG, "[发送] 已 new bn3.o() 无参实例");
                    return inst;
                }
            }
            LogWriter.log(TAG, "[发送] bn3.o 无无参构造");
            return null;
        } catch (Throwable t) {
            LogWriter.log(TAG, "[发送] 创建 bn3.o 失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    // ==================== 搜索面板 UI ====================
    private static void showSearchPanel() {
        try {
            if (searchPanel != null) { hideSearchPanel(); }
            if (currentChatting == null) return;
            if (sWM == null) sWM = (WindowManager) currentChatting.getSystemService(Context.WINDOW_SERVICE);
            float d = currentChatting.getResources().getDisplayMetrics().density;

            LinearLayout panel = new LinearLayout(currentChatting);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundColor(AppColors.card());
            int pad = (int) (14 * d);
            panel.setPadding(pad, pad, pad, pad);

            LinearLayout row = new LinearLayout(currentChatting);
            row.setOrientation(LinearLayout.HORIZONTAL);
            final EditText input = new EditText(currentChatting);
            input.setHint("搜索歌曲");
            input.setTextColor(AppColors.text1());
            input.setHintTextColor(AppColors.text3());
            input.setSingleLine(true);
            input.setBackgroundColor(AppColors.inputBg());
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, (int) (46 * d), 1.0f);
            input.setLayoutParams(ip);

            TextView btn = new TextView(currentChatting);
            btn.setText("搜索");
            btn.setTextColor(AppColors.whiteTextOnAccent());
            btn.setGravity(Gravity.CENTER);
            btn.setPadding((int) (14 * d), 0, (int) (14 * d), 0);
            btn.setBackground(makeRound(AppColors.accent()));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (46 * d));
            bp.leftMargin = (int) (8 * d);
            btn.setLayoutParams(bp);

            TextView close = new TextView(currentChatting);
            close.setText("×");
            close.setTextSize(24);
            close.setTextColor(AppColors.text1());
            close.setGravity(Gravity.CENTER);
            close.setPadding((int) (10 * d), 0, (int) (10 * d), 0);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (46 * d));
            cp.leftMargin = (int) (8 * d);
            close.setLayoutParams(cp);

            row.addView(input);
            row.addView(btn);
            row.addView(close);
            panel.addView(row);

            searchScroll = new ScrollView(currentChatting);
            LinearLayout list = new LinearLayout(currentChatting);
            list.setOrientation(LinearLayout.VERTICAL);
            searchScroll.addView(list);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0);
            sp.topMargin = (int) (8 * d);
            searchScroll.setLayoutParams(sp);
            panel.addView(searchScroll);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (300 * d), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (120 * d);

            sWM.addView(panel, lp);
            searchPanel = panel;

            btn.setOnClickListener(v -> doSearch(input.getText().toString().trim()));
            close.setOnClickListener(v -> hideSearchPanel());

            input.setFocusable(true);
            input.setFocusableInTouchMode(true);
            input.requestFocus();
            MAIN.postDelayed(() -> {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) currentChatting.getSystemService(Context.INPUT_METHOD_SERVICE);
                    imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } catch (Throwable ignored) {}
            }, 250);
        } catch (Throwable t) {
            LogWriter.log(TAG, "搜索面板显示失败: " + t.getMessage());
        }
    }

    private static void resizeResultList(int rows) {
        try {
            if (searchScroll == null || currentChatting == null) return;
            float d = currentChatting.getResources().getDisplayMetrics().density;
            int rowH = (int) (44 * d);
            int max = (int) (380 * d);
            int h = rows <= 0 ? 0 : Math.min(rows * rowH, max);
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) searchScroll.getLayoutParams();
            sp.height = h;
            searchScroll.setLayoutParams(sp);
        } catch (Throwable ignored) {}
    }

    private static void doSearch(final String kw) {
        if (searchPanel == null) return;
        if (searchScroll == null) return;
        lastKeyword = kw;
        lastOffset = 0;
        LinearLayout list = (LinearLayout) searchScroll.getChildAt(0);
        list.removeAllViews();
        TextView tip = new TextView(searchScroll.getContext());
        tip.setText("搜索中...");
        tip.setTextColor(AppColors.text3());
        tip.setTextSize(14);
        list.addView(tip);
        resizeResultList(1);
        LogWriter.log(TAG, "[搜索] 关键词=" + kw);
        searchMusic(kw, 0, new SearchCallback() {
            @Override public void onResult(List<Song> songs) {
                if (searchPanel == null || searchScroll == null) return;
                LinearLayout l = (LinearLayout) searchScroll.getChildAt(0);
                l.removeAllViews();
                if (songs == null || songs.isEmpty()) {
                    TextView t = new TextView(searchScroll.getContext());
                    t.setText("无结果");
                    t.setTextColor(AppColors.text3());
                    t.setTextSize(14);
                    l.addView(t);
                    resizeResultList(1);
                    return;
                }
                for (final Song s : songs) {
                    l.addView(makeSongRow(s));
                }
                int count = songs.size();
                if (count >= 10) l.addView(makeLoadMoreButton());
                resizeResultList(count + 1);
            }
            @Override public void onFail(String err) {
                if (searchPanel == null || searchScroll == null) return;
                LinearLayout l = (LinearLayout) searchScroll.getChildAt(0);
                l.removeAllViews();
                TextView t = new TextView(searchScroll.getContext());
                t.setText("搜索失败: " + err);
                t.setTextColor(0xFFFF8888);
                t.setTextSize(13);
                l.addView(t);
                resizeResultList(1);
            }
        });
    }

    private static void loadMore() {
        if (searchPanel == null || searchScroll == null) return;
        if (lastKeyword == null || lastKeyword.trim().isEmpty()) return;
        LinearLayout list = (LinearLayout) searchScroll.getChildAt(0);
        TextView tip = new TextView(searchScroll.getContext());
        tip.setText("加载中...");
        tip.setTextColor(AppColors.text3());
        tip.setTextSize(14);
        list.addView(tip);
        int nextOffset = lastOffset + 10;
        LogWriter.log(TAG, "[搜索] 加载更多 offset=" + nextOffset);
        searchMusic(lastKeyword, nextOffset, new SearchCallback() {
            @Override public void onResult(List<Song> songs) {
                if (searchPanel == null || searchScroll == null) return;
                LinearLayout l = (LinearLayout) searchScroll.getChildAt(0);
                l.removeViewAt(l.getChildCount() - 1);
                if (songs == null || songs.isEmpty()) {
                    TextView t = new TextView(searchScroll.getContext());
                    t.setText("没有更多了");
                    t.setTextColor(AppColors.text3());
                    t.setTextSize(14);
                    l.addView(t);
                    resizeResultList(l.getChildCount());
                    return;
                }
                lastOffset = nextOffset;
                for (final Song s : songs) {
                    l.addView(makeSongRow(s));
                }
                if (songs.size() >= 10) l.addView(makeLoadMoreButton());
                resizeResultList(l.getChildCount());
            }
            @Override public void onFail(String err) {
                if (searchPanel == null || searchScroll == null) return;
                LinearLayout l = (LinearLayout) searchScroll.getChildAt(0);
                l.removeViewAt(l.getChildCount() - 1);
                TextView t = new TextView(searchScroll.getContext());
                t.setText("加载失败: " + err);
                t.setTextColor(0xFFFF8888);
                t.setTextSize(13);
                l.addView(t);
                resizeResultList(l.getChildCount());
            }
        });
    }

    private static TextView makeLoadMoreButton() {
        TextView tv = new TextView(currentChatting);
        tv.setText("加载更多");
        tv.setTextColor(AppColors.accent());
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, (int) (10 * currentChatting.getResources().getDisplayMetrics().density), 0,
                (int) (10 * currentChatting.getResources().getDisplayMetrics().density));
        tv.setOnClickListener(v -> loadMore());
        return tv;
    }

    private static LinearLayout makeSongRow(final Song s) {
        float d = currentChatting.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(currentChatting);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (4 * d), (int) (8 * d), (int) (4 * d), (int) (8 * d));

        int coverSize = (int) (42 * d);
        ImageView cover = new ImageView(currentChatting);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackground(makeRound(0x22000000));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(coverSize, coverSize);
        cover.setLayoutParams(cp);
        row.addView(cover);

        LinearLayout mid = new LinearLayout(currentChatting);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        mp.leftMargin = (int) (10 * d);
        mid.setLayoutParams(mp);

        TextView name = new TextView(currentChatting);
        name.setText(s.songName == null ? "" : s.songName);
        name.setTextColor(AppColors.text1());
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);

        TextView singerTv = new TextView(currentChatting);
        singerTv.setText(s.singer == null ? "" : s.singer);
        singerTv.setTextColor(AppColors.text3());
        singerTv.setTextSize(12);
        singerTv.setSingleLine(true);
        singerTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(singerTv);

        row.addView(mid);

        TextView dur = new TextView(currentChatting);
        dur.setText(s.duration > 0 ? formatDuration(s.duration) : "");
        dur.setTextColor(AppColors.text3());
        dur.setTextSize(12);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dp.leftMargin = (int) (8 * d);
        dur.setLayoutParams(dp);
        row.addView(dur);

        row.setOnClickListener(v -> {
            hideSearchPanel();
            sendMusic(s);
        });

        if (s.coverUrl != null && !s.coverUrl.isEmpty()) {
            loadCover(s.coverUrl, cover, coverSize);
        }
        return row;
    }

    private static String formatDuration(int sec) {
        if (sec <= 0) return "";
        int m = sec / 60;
        int ss = sec % 60;
        return m + ":" + String.format(java.util.Locale.US, "%02d", ss);
    }

    private static void loadCover(final String url, final ImageView view, final int size) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                    conn.connect();
                    InputStream is = conn.getInputStream();
                    Bitmap bmp = BitmapFactory.decodeStream(is);
                    is.close();
                    if (bmp == null) return;
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
                    if (scaled != bmp) bmp.recycle();
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            try { view.setImageBitmap(scaled); } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable t) {
                    LogWriter.log(TAG, "封面加载失败: " + t.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "ting-cover").start();
    }

    private static GradientDrawable makeRound(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(12);
        return g;
    }

    private static void hideSearchPanel() {
        try {
            if (searchPanel != null && sWM != null) {
                sWM.removeView(searchPanel);
            }
        } catch (Throwable ignored) {}
        searchPanel = null;
        searchScroll = null;
    }

    // ==================== 播放捕获 ====================
    private static final Set<String> dumpedMethods = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static void hookTingPlayer(ClassLoader cl) {
        try {
            Class<?> svc = XposedHelpers.findClass("ul4.a9", cl);
            LogWriter.log(TAG, "播放服务类命中: ul4.a9 -> " + svc.getName());
            int hooked = 0;
            for (Method m : svc.getDeclaredMethods()) {
                LogWriter.log(TAG, "[播放服务] 方法签名 " + sig(m));
                if (hookOne(svc, m)) hooked++;
            }
            LogWriter.log(TAG, "已 hook ul4.a9 全部方法共 " + hooked + " 个，用于反查");
        } catch (Throwable t) {
            LogWriter.log(TAG, "播放服务类未找到: " + t.getMessage());
        }
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append("(");
        for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getSimpleName()).append(",");
        sb.append(")");
        return sb.toString();
    }

    private static String fullSig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append("(");
        for (Class<?> pt : m.getParameterTypes()) sb.append(pt.getName()).append(",");
        sb.append(")");
        return sb.toString();
    }

    private static void hookE61Xa(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("e61.xa", cl);
            LogWriter.log(TAG, "[e61.xa] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[e61.xa] 方法 " + fullSig(m));
            }
            Class<?> sup = c.getSuperclass();
            while (sup != null && sup != Object.class) {
                for (Method m : sup.getDeclaredMethods()) {
                    LogWriter.log(TAG, "[e61.xa] 父类方法 " + fullSig(m));
                }
                sup = sup.getSuperclass();
            }
            for (Class<?> ifc : c.getInterfaces()) {
                for (Method m : ifc.getMethods()) {
                    LogWriter.log(TAG, "[e61.xa] 接口方法 " + fullSig(m));
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[e61.xa] 未找到: " + t.getMessage());
        }
    }

    private static void hookCgiCallbackType(ClassLoader cl) {
        try {
            Class<?> host = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiHost", cl);
            for (Method m : host.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FCGI] 完整签名 " + fullSig(m));
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt.getName().contains("FlutterCgi") || pt.getName().contains("pigeon")) continue;
                    LogWriter.log(TAG, "[CB] 回调类型 " + pt.getName() + " isInterface=" + pt.isInterface());
                    for (Method cm : pt.getMethods()) {
                        LogWriter.log(TAG, "[CB]   方法 " + fullSig(cm));
                    }
                    if (pt.isInterface()) {
                        for (Class<?> sup : pt.getInterfaces()) {
                            for (Method cm : sup.getMethods()) {
                                LogWriter.log(TAG, "[CB]   父接口方法 " + fullSig(cm));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[CB] 回调类型解析失败: " + t.getMessage());
        }
    }

    private static boolean hookOne(Class<?> svc, final Method target) {
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        String key = target.getName();
                        boolean first = dumpedMethods.add(key);
                        if (first) {
                            LogWriter.log(TAG, "[反查] 方法触发: " + key + "(" + target.getParameterCount() + " 参数)");
                        }
                        for (int i = 0; i < p.args.length; i++) {
                            Object arg = p.args[i];
                            if (arg == null) continue;
                            if (arg instanceof String) {
                                LogWriter.log(TAG, "[反查] " + target.getName() + " arg[" + i + "] String=" + arg);
                                continue;
                            }
                            if (arg instanceof Integer || arg instanceof Long || arg instanceof Float) {
                                LogWriter.log(TAG, "[反查] " + target.getName() + " arg[" + i + "] = " + arg);
                                continue;
                            }
                            TingMusicInfo info = extract(arg);
                            if (info != null && (info.title != null || info.listenId != null)) {
                                pendingMusic = info;
                                LogWriter.log(TAG, "[反查] 捕获音乐[" + target.getName() + " arg" + i + "]: " + info.toString());
                                MAIN.post(() -> updateBallState());
                                if (first) dumpTree(arg, "    arg[" + i + "]", 4);
                            } else if (first) {
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
        if (info.dataUrl == null || info.dataUrl.isEmpty()) {
            LogWriter.log(TAG, "[w90] 展开子对象 类=" + o.getClass().getName());
            dumpTree(o, "      [w90]", 2);
        }
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
            try {
                Method tj = o.getClass().getMethod("toJSON");
                Object json = tj.invoke(o);
                if (json != null) {
                    String js = json.toString();
                    if (js.length() > 3000) js = js.substring(0, 3000);
                    LogWriter.log(TAG, prefix + "  toJSON() -> " + js);
                }
            } catch (Throwable ignored) {}
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
                "android.net.Paitp",
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
                LogWriter.log(TAG, "[反查类] 方法签名 " + sig(m));
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
                            StackTraceElement[] st = Thread.currentThread().getStackTrace();
                            StringBuilder stb = new StringBuilder("[反查类] 调用栈:");
                            int cnt = 0;
                            for (StackTraceElement e : st) {
                                String cn = e.getClassName();
                                if (cn.startsWith("com.leshao.v3") || cn.startsWith("de.robv.android.xposed")
                                        || cn.startsWith("dalvik.") || cn.startsWith("java.lang")
                                        || cn.startsWith("java.util") || cn.startsWith("java.lang.reflect")) continue;
                                if (cnt >= 30) break;
                                if (cnt > 0) stb.append(" <- ");
                                stb.append(cn).append(".").append(e.getMethodName());
                                cnt++;
                            }
                            LogWriter.log(TAG, stb.toString());
                        }
                        for (int i = 0; i < p.args.length; i++) {
                            Object arg = p.args[i];
                            if (arg == null) continue;
                            if (arg instanceof String) {
                                if (first) LogWriter.log(TAG, "    arg[" + i + "] String=" + arg);
                                continue;
                            }
                            if (arg instanceof android.net.Uri) {
                                if (first) LogWriter.log(TAG, "    arg[" + i + "] Uri=" + arg);
                                continue;
                            }
                            TingMusicInfo info = extract(arg);
                            if (info != null && (info.title != null || info.listenId != null)) {
                                pendingMusic = info;
                                LogWriter.log(TAG, "    arg[" + i + "] 捕获音乐[" + target.getDeclaringClass().getSimpleName() + "." + target.getName() + "]: " + info);
                                MAIN.post(() -> updateBallState());
                                if (first) dumpTree(arg, "    arg[" + i + "]", 4);
                            } else if (first) {
                                dumpObject(arg, "    arg[" + i + "]");
                                dumpTree(arg, "    arg[" + i + "]", 3);
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

    // ==================== 网络层反查 ====================
    private static final Set<String> dumpedUrls = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void hookNetwork(ClassLoader cl) {
        try {
            Class<?> u = Class.forName("java.net.URL");
            for (java.lang.reflect.Constructor<?> c : u.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            for (Object a : p.args) if (a instanceof String) logUrl((String) a);
                        }
                    });
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "[网络] java.net.URL 构造已 hook");
        } catch (Throwable t) { LogWriter.log(TAG, "[网络] URL hook 失败: " + t.getMessage()); }

        String[] candidates = { "okhttp3.Request$Builder", "okhttp3.HttpUrl", "okhttp3.Request" };
        for (String name : candidates) {
            try {
                Class<?> c = XposedHelpers.findClass(name, cl);
                int hooked = 0;
                for (Method m : c.getDeclaredMethods()) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                for (Object a : p.args) if (a instanceof String) logUrl((String) a);
                            }
                        });
                        hooked++;
                    } catch (Throwable ignored) {}
                }
                LogWriter.log(TAG, "[网络] 命中 " + name + " 共 hook " + hooked + " 方法");
            } catch (Throwable t) {
                LogWriter.log(TAG, "[网络] 未找到 " + name);
            }
        }
    }

    private static void logUrl(String u) {
        if (u == null || !u.startsWith("http")) return;
        String low = u.toLowerCase();
        boolean audio = low.contains("music") || low.contains("m4a") || low.contains("mp3")
                || low.contains("stream") || low.contains("audio") || low.contains("songmid")
                || low.contains("qy.qq.com") || low.contains("qqmusic") || low.contains(".flac")
                || low.contains("ting") || low.contains("search") || low.contains("song")
                || low.contains("cgi") || low.contains("listen");
        if (!audio) return;
        if (dumpedUrls.add(u)) {
            LogWriter.log(TAG, "[URL] " + u);
        }
    }

    // ==================== 文件写入反查 ====================
    private static final Set<String> dumpedFiles = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static void hookFileWrite() {
        try {
            Class<?> file = Class.forName("java.io.File");
            for (java.lang.reflect.Constructor<?> c : file.getDeclaredConstructors()) {
                try {
                    XposedBridge.hookMethod(c, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            for (Object a : p.args) {
                                if (a instanceof String) logFile((String) a);
                                else if (a instanceof File) logFile(((File) a).getAbsolutePath());
                            }
                        }
                    });
                } catch (Throwable ignored) {}
            }
            LogWriter.log(TAG, "[文件] File 构造已 hook");
        } catch (Throwable t) { LogWriter.log(TAG, "[文件] File hook 失败: " + t.getMessage()); }
    }

    private static void logFile(String path) {
        if (path == null) return;
        String low = path.toLowerCase();
        boolean hit = low.contains("ting") || low.endsWith(".m4a") || low.endsWith(".mp3")
                || low.endsWith(".aac") || low.endsWith(".flac") || low.endsWith(".ogg")
                || low.contains("music") || low.contains("audio") || low.contains("cache");
        if (!hit) return;
        if (dumpedFiles.add(path)) {
            long size = -1;
            try { size = new File(path).length(); } catch (Throwable ignored) {}
            LogWriter.log(TAG, "[文件] " + path + " size=" + size);
        }
        if (low.contains("resourceloader") && low.endsWith(".mp4")) {
            lastAudioFile = path;
            LogWriter.log(TAG, "[音频] 记录最新音频文件: " + path);
            if (pendingMusic == null) {
                TingMusicInfo info = new TingMusicInfo();
                info.title = "听一听歌曲";
                info.listenId = extractIdFromPath(path);
                pendingMusic = info;
                LogWriter.log(TAG, "[音频] 元数据未捕获, 设置占位歌曲 listenId=" + info.listenId);
                MAIN.post(() -> updateBallState());
            }
        }
    }

    private static String extractIdFromPath(String path) {
        try {
            String name = path.substring(path.lastIndexOf('/') + 1);
            String[] parts = name.split("_");
            if (parts.length >= 3) return parts[2];
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 听一听入口反查 ====================
    private static void hookUuJ(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("uu.j", cl);
            LogWriter.log(TAG, "[uu.j] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[uu.j] 方法签名 " + sig(m));
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            StringBuilder sb = new StringBuilder("[uu.j] 构造(");
                            for (Object a : p.args) {
                                if (a == null) { sb.append("null,"); continue; }
                                if (a instanceof String) sb.append("String=").append(a);
                                else sb.append(a.getClass().getSimpleName());
                                sb.append(",");
                            }
                            sb.append(") 栈=").append(stackTrace());
                            LogWriter.log(TAG, sb.toString());
                        } catch (Throwable ignored) {}
                    }
                });
            }
            for (final Method m : c.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String key = m.getName();
                            boolean first = dumpedMethods.add("uu.j." + key);
                            if (first) {
                                LogWriter.log(TAG, "[uu.j] 触发: " + key + "(" + m.getParameterCount() + " 参数)");
                                if (key.equals("i") || key.equals("B") || key.equals("D")) {
                                    dumpFields(p.thisObject, "    [uu.j] this." + key);
                                }
                            }
                            for (int i = 0; i < p.args.length; i++) {
                                Object arg = p.args[i];
                                if (arg == null) continue;
                                if (arg instanceof String) {
                                    LogWriter.log(TAG, "[uu.j] " + key + " arg[" + i + "] String=" + arg);
                                } else if (arg instanceof Integer || arg instanceof Long
                                        || arg instanceof Float || arg instanceof Boolean) {
                                    LogWriter.log(TAG, "[uu.j] " + key + " arg[" + i + "] = " + arg);
                                } else if (first) {
                                    dumpObject(arg, "    " + key + " arg[" + i + "]");
                                    if ((key.equals("B") || key.equals("D")) && i == 0) {
                                        dumpFields(arg, "    [uu.j] " + key + ".arg0字段");
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "[uu.j] 已 hook " + c.getDeclaredMethods().length + " 个方法");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[uu.j] 类未找到: " + t.getMessage());
        }
    }

    private static String stackTrace() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = Math.min(st.length, 45);
        for (int i = 0; i < n; i++) {
            String cn = st[i].getClassName();
            if (cn.startsWith("de.robv.android.xposed") || cn.startsWith("com.leshao")
                    || cn.startsWith("java.lang.Thread")) continue;
            sb.append(cn).append(".").append(st[i].getMethodName());
            if (i < n - 1) sb.append(" <- ");
        }
        return sb.toString();
    }

    private static void hookBasicMessageChannel(ClassLoader cl) {
        try {
            Class<?> mc = XposedHelpers.findClass("io.flutter.plugin.common.BasicMessageChannel", cl);
            for (final Constructor<?> ctor : mc.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            StringBuilder sb = new StringBuilder("[BMC] 构造(");
                            for (Object a : p.args) {
                                if (a instanceof String) sb.append("String=").append(a);
                                else if (a == null) sb.append("null");
                                else sb.append(a.getClass().getSimpleName());
                                sb.append(",");
                            }
                            sb.append(")");
                            LogWriter.log(TAG, sb.toString());
                        } catch (Throwable ignored) {}
                    }
                });
            }
            for (final Method m : mc.getDeclaredMethods()) {
                if (!m.getName().equals("setMessageHandler")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String ch = null;
                            for (java.lang.reflect.Field f : mc.getDeclaredFields()) {
                                if (f.getType() == String.class) {
                                    f.setAccessible(true);
                                    Object v = f.get(p.thisObject);
                                    if (v instanceof String) { ch = (String) v; break; }
                                }
                            }
                            LogWriter.log(TAG, "[BMC] setMessageHandler channel=" + ch);
                            if (p.args.length > 0 && p.args[0] != null) {
                                dumpObject(p.args[0], "    [BMC] handler");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "[BMC] BasicMessageChannel hook 已注册");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[BMC] hook 失败: " + t.getMessage());
        }
    }

    private static void hookFlutterSendMusicToChat(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.flutter_music_chat.FlutterSendMusicToChatAction", cl);
            LogWriter.log(TAG, "[FSMC] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FSMC] 方法 " + sig(m));
            }
            for (final Method m : c.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String key = m.getName();
                            LogWriter.log(TAG, "[FSMC] 触发: " + key + "(" + m.getParameterCount() + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) continue;
                                if (a instanceof String) LogWriter.log(TAG, "[FSMC] " + key + " arg[" + i + "] String=" + a);
                                else if (a instanceof Number || a instanceof Boolean) LogWriter.log(TAG, "[FSMC] " + key + " arg[" + i + "] = " + a);
                                else dumpObject(a, "    [FSMC] " + key + " arg[" + i + "]");
                            }
                            if (p.thisObject != null && !(p.thisObject instanceof Class)) {
                                dumpFields(p.thisObject, "    [FSMC] this." + key);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FSMC] 类未找到: " + t.getMessage());
        }
    }

    private static void hookVuF(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("vu.f", cl);
            LogWriter.log(TAG, "[vu.f] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[vu.f] 方法 " + sig(m));
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            StringBuilder sb = new StringBuilder("[vu.f] 构造(");
                            for (Object a : p.args) {
                                if (a instanceof String) sb.append("String=").append(a);
                                else if (a == null) sb.append("null");
                                else sb.append(a.getClass().getSimpleName());
                                sb.append(",");
                            }
                            sb.append(") 栈=").append(stackTrace());
                            LogWriter.log(TAG, sb.toString());
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) continue;
                                if (a instanceof String) continue;
                                dumpFields(a, "    [vu.f] 构造arg[" + i + "]字段");
                                dumpObject(a, "    [vu.f] 构造arg[" + i + "]");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[vu.f] 类未找到: " + t.getMessage());
        }
    }

    private static void hookSendMusicRequest(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.flutter_music_chat.SendMusicRequest", cl);
            LogWriter.log(TAG, "[SMR] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[SMR] 方法 " + sig(m));
            }
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                StringBuilder sb = new StringBuilder("[SMR] 构造器(");
                for (Class<?> pt : ctor.getParameterTypes()) sb.append(pt.getSimpleName()).append(",");
                sb.append(")");
                LogWriter.log(TAG, sb.toString());
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                LogWriter.log(TAG, "[SMR] 字段 " + f.getType().getSimpleName() + " " + f.getName());
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[SMR] 构造触发(" + p.args.length + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) { LogWriter.log(TAG, "[SMR] 构造arg[" + i + "] = null"); continue; }
                                LogWriter.log(TAG, "[SMR] 构造arg[" + i + "] (" + a.getClass().getSimpleName() + ") = " + a);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[SMR] 类未找到: " + t.getMessage());
        }
    }

    private static void hookK0(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.mm.pluginsdk.model.app.k0", cl);
            LogWriter.log(TAG, "[k0] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[k0] 方法 " + sig(m));
            }
            final String[] target = {"I", "J", "K", "L", "S", "e"};
            for (final Method m : c.getDeclaredMethods()) {
                boolean hit = false;
                for (String t : target) if (m.getName().equals(t)) { hit = true; break; }
                if (!hit) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[k0] 触发 " + m.getName() + "(" + m.getParameterCount() + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) { LogWriter.log(TAG, "[k0] " + m.getName() + " arg[" + i + "] = null"); continue; }
                                if (a instanceof String || a instanceof Number || a instanceof Boolean) {
                                    LogWriter.log(TAG, "[k0] " + m.getName() + " arg[" + i + "] = " + a);
                                    continue;
                                }
                                LogWriter.log(TAG, "[k0] " + m.getName() + " arg[" + i + "] 类=" + a.getClass().getName());
                                dumpFields(a, "    [k0] " + m.getName() + " arg[" + i + "]字段");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "[k0] 已 hook");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[k0] 类未找到: " + t.getMessage());
        }
    }

    private static void hookBn3O(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("bn3.o", cl);
            LogWriter.log(TAG, "[bn3.o] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[bn3.o] 方法 " + sig(m));
            }
            for (Constructor<?> ctor : c.getDeclaredConstructors()) {
                StringBuilder sb = new StringBuilder("[bn3.o] 构造器(");
                for (Class<?> pt : ctor.getParameterTypes()) sb.append(pt.getSimpleName()).append(",");
                sb.append(")");
                LogWriter.log(TAG, sb.toString());
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                LogWriter.log(TAG, "[bn3.o] 字段 " + f.getType().getSimpleName() + " " + f.getName());
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[bn3.o] 构造触发(" + p.args.length + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) { LogWriter.log(TAG, "[bn3.o] 构造arg[" + i + "] = null"); continue; }
                                if (a instanceof String || a instanceof Number || a instanceof Boolean) {
                                    LogWriter.log(TAG, "[bn3.o] 构造arg[" + i + "] = " + a);
                                    continue;
                                }
                                dumpObject(a, "    [bn3.o] 构造arg[" + i + "]");
                            }
                            dumpFields(p.thisObject, "    [bn3.o] 构造后实例");
                        } catch (Throwable ignored) {}
                    }
                });
            }
            for (final Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("sendMusicToChat")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[bn3.o] sendMusicToChat 触发(" + m.getParameterCount() + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) { LogWriter.log(TAG, "[bn3.o] arg[" + i + "] = null"); continue; }
                                if (a instanceof String) { LogWriter.log(TAG, "[bn3.o] arg[" + i + "] String=" + a); continue; }
                                if (a instanceof Number || a instanceof Boolean) { LogWriter.log(TAG, "[bn3.o] arg[" + i + "] = " + a); continue; }
                                dumpFields(a, "    [bn3.o] arg[" + i + "]字段");
                                dumpObject(a, "    [bn3.o] arg[" + i + "]");
                            }
                            if (p.thisObject != null) {
                                dumpFields(p.thisObject, "    [bn3.o] this");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[bn3.o] 类未找到: " + t.getMessage());
        }
    }

    private static void hookFlutterCgiHost(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiHost", cl);
            LogWriter.log(TAG, "[FCGI] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FCGI] 方法 " + sig(m));
            }
            for (final Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String key = m.getName();
                            LogWriter.log(TAG, "[FCGI] 触发: " + key + "(" + m.getParameterCount() + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) { LogWriter.log(TAG, "[FCGI] " + key + " arg[" + i + "] = null"); continue; }
                                if (a instanceof String) { LogWriter.log(TAG, "[FCGI] " + key + " arg[" + i + "] String=" + a); continue; }
                                if (a instanceof Number || a instanceof Boolean) { LogWriter.log(TAG, "[FCGI] " + key + " arg[" + i + "] = " + a); continue; }
                                dumpFields(a, "    [FCGI] " + key + " arg[" + i + "]字段");
                                dumpObject(a, "    [FCGI] " + key + " arg[" + i + "]");
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
            hookFlutterCgiRequest(cl);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FCGI] 类未找到: " + t.getMessage());
        }
    }

    private static void hookFlutterCgiRequest(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiRequest", cl);
            LogWriter.log(TAG, "[FCGIR] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FCGIR] 方法 " + sig(m));
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[FCGIR] 构造后: " + p.thisObject.getClass().getName());
                            dumpFields(p.thisObject, "    [FCGIR] 实例字段");
                            dumpObject(p.thisObject, "    [FCGIR] 实例");
                            dumpDataBytes(p.thisObject, "    [FCGIR] data字节");
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FCGIR] 类未找到: " + t.getMessage());
        }
    }

    private static void hookPigeonCompanions(ClassLoader cl) {
        hookCompanion(cl, "com.tencent.pigeon.flutter_music_chat.FlutterSendMusicToChatAction$Companion", "FSMC-CMP", 0);
        hookCompanion(cl, "com.tencent.pigeon.mm_foundation.FlutterCgiHost$Companion", "FCGI-CMP", 1);
    }

    private static void hookCompanion(ClassLoader cl, String name, final String tag, final int which) {
        try {
            Class<?> comp = XposedHelpers.findClass(name, cl);
            LogWriter.log(TAG, "[" + tag + "] 命中: " + comp.getName());
            for (Method m : comp.getDeclaredMethods()) {
                LogWriter.log(TAG, "[" + tag + "] 方法 " + sig(m));
            }
            for (final Method m : comp.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[" + tag + "] " + m.getName() + " 触发(" + m.getParameterCount() + "参数)");
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (m.getName().equals("setUp") && i == 1) {
                                    if (a == null) {
                                        LogWriter.log(TAG, "[" + tag + "]   arg[" + i + "] = null，保留旧api引用");
                                        continue;
                                    }
                                    String cn = a.getClass().getName();
                                    LogWriter.log(TAG, "[" + tag + "]   arg[" + i + "] 类=" + cn);
                                    if (which == 0) sendMusicToChatApi = a;
                                    else flutterCgiHostApi = a;
                                    dumpFields(a, "    [" + tag + "] api字段");
                                    dumpObject(a, "    [" + tag + "] api");
                                    continue;
                                }
                                if (a == null) continue;
                                String cn = a.getClass().getName();
                                LogWriter.log(TAG, "[" + tag + "]   arg[" + i + "] 类=" + cn);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[" + tag + "] 未找到: " + t.getMessage());
        }
    }

    private static void hookFlutterCgiHostCodec(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiHostCodec", cl);
            LogWriter.log(TAG, "[FCGIC] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FCGIC] 方法 " + sig(m));
            }
            for (final Method m : c.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            String name = m.getName();
                            for (int i = 0; i < p.args.length; i++) {
                                Object a = p.args[i];
                                if (a == null) continue;
                                if (a instanceof byte[]) {
                                    byte[] b = (byte[]) a;
                                    LogWriter.log(TAG, "[FCGIC] " + name + " arg[" + i + "] byte[" + b.length + "] " + toUtf8(b, 300));
                                } else if (a instanceof String || a instanceof Number || a instanceof Boolean) {
                                    LogWriter.log(TAG, "[FCGIC] " + name + " arg[" + i + "] " + a);
                                } else {
                                    dumpFields(a, "    [FCGIC] " + name + " arg[" + i + "]字段");
                                    dumpObject(a, "    [FCGIC] " + name + " arg[" + i + "]");
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FCGIC] 类未找到: " + t.getMessage());
        }
    }

    private static void hookFlutterCgiResponse(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.tencent.pigeon.mm_foundation.FlutterCgiResponse", cl);
            LogWriter.log(TAG, "[FCGIRR] 类命中: " + c.getName());
            for (Method m : c.getDeclaredMethods()) {
                LogWriter.log(TAG, "[FCGIRR] 方法 " + sig(m));
            }
            for (final Constructor<?> ctor : c.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            LogWriter.log(TAG, "[FCGIRR] 构造后: " + p.thisObject.getClass().getName());
                            dumpFields(p.thisObject, "    [FCGIRR] 实例字段");
                            dumpObject(p.thisObject, "    [FCGIRR] 实例");
                            dumpDataBytes(p.thisObject, "    [FCGIRR] data字节");
                        } catch (Throwable ignored) {}
                    }
                });
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "[FCGIRR] 类未找到: " + t.getMessage());
        }
    }

    private static String toUtf8(byte[] b, int max) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length && i < max; i++) {
                sb.append((char) (b[i] & 0xFF));
            }
            String s = sb.toString().replace('\n', ' ').replace('\r', ' ');
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void dumpDataBytes(Object o, String prefix) {
        try {
            Method getData = o.getClass().getMethod("getData");
            Object d = getData.invoke(o);
            if (!(d instanceof byte[])) { LogWriter.log(TAG, prefix + " getData非byte[]: " + (d == null ? "null" : d.getClass().getName())); return; }
            byte[] b = (byte[]) d;
            if (b.length == 0) { LogWriter.log(TAG, prefix + " 空"); return; }
            LogWriter.log(TAG, prefix + " utf8[" + b.length + "]: " + toUtf8(b, 20000));
            StringBuilder hex = new StringBuilder(prefix + " hex[" + b.length + "]: ");
            int n = Math.min(b.length, 2048);
            for (int i = 0; i < n; i++) hex.append(String.format("%02X", b[i]));
            if (b.length > n) hex.append("...");
            LogWriter.log(TAG, hex.toString());
        } catch (Throwable t) {
            LogWriter.log(TAG, prefix + " 解码失败: " + t.getMessage());
        }
    }

    private static void hookFlutterChannel(ClassLoader cl) {
        try {
            Class<?> mc = XposedHelpers.findClass("io.flutter.plugin.common.MethodChannel", cl);
            for (Method m : mc.getDeclaredMethods()) {
                if (!m.getName().equals("invokeMethod")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            if (p.args.length < 1 || !(p.args[0] instanceof String)) return;
                            String method = (String) p.args[0];
                            String channel = readChannelName(p.thisObject);
                            Object args = p.args.length >= 2 ? p.args[1] : null;
                            String as = args == null ? "null" : String.valueOf(args);
                            if (as.length() > 800) as = as.substring(0, 800);
                            LogWriter.log(TAG, "[Flutter] channel=" + channel + " method=" + method + " args=" + as);
                        } catch (Throwable ignored) {}
                    }
                });
            }
            LogWriter.log(TAG, "[Flutter] MethodChannel.invokeMethod hook 已注册, 方法数=" + mc.getDeclaredMethods().length);
        } catch (Throwable t) {
            LogWriter.log(TAG, "[Flutter] hook 失败: " + t.getMessage());
        }
    }

    private static String readChannelName(Object channel) {
        try {
            for (java.lang.reflect.Field f : channel.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object v = f.get(channel);
                    if (v != null) return (String) v;
                }
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    private static void hookTingEntry(ClassLoader cl) {
        try {
            Class<?> ting = XposedHelpers.findClass("com.tencent.mm.plugin.ting.TingFlutterActivity", cl);
            XposedBridge.hookAllMethods(ting, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.thisObject instanceof Activity)) return;
                        Intent it = ((Activity) p.thisObject).getIntent();
                        LogWriter.log(TAG, "[Ting入口] onCreate 触发 " + dumpIntent(it));
                        android.os.Bundle b = it.getExtras();
                        if (b != null) {
                            for (String k : b.keySet()) {
                                Object v = b.get(k);
                                if (v == null) continue;
                                String cn = v.getClass().getName();
                                if (cn.contains("FlutterPageInfo") || cn.contains("FlutterPageStyle")) {
                                    dumpFields(v, "    [" + k + "]");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "[Ting入口] onCreate 日志失败: " + t.getMessage());
                    }
                }
            });
            LogWriter.log(TAG, "[Ting入口] TingFlutterActivity.onCreate hook 已注册");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[Ting入口] TingFlutterActivity 未找到: " + t.getMessage());
        }

        try {
            XposedBridge.hookAllMethods(android.app.Activity.class, "startActivity", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length < 1 || !(p.args[0] instanceof Intent)) return;
                        Intent i = (Intent) p.args[0];
                        android.content.ComponentName cn = i.getComponent();
                        if (cn == null) return;
                        String cls = cn.getClassName();
                        if (cls == null) return;
                        String low = cls.toLowerCase();
                        if (low.contains("ting") || low.contains("music")) {
                            LogWriter.log(TAG, "[Ting入口] startActivity from="
                                    + p.thisObject.getClass().getSimpleName()
                                    + " -> " + cls + " " + dumpIntent(i));
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "[Ting入口] Activity.startActivity hook 已注册");
        } catch (Throwable t) {
            LogWriter.log(TAG, "[Ting入口] startActivity hook 失败: " + t.getMessage());
        }
    }

    private static String dumpIntent(Intent it) {
        if (it == null) return "intent=null";
        try {
            StringBuilder sb = new StringBuilder();
            android.content.ComponentName cn = it.getComponent();
            sb.append("component=").append(cn == null ? "null" : cn.flattenToShortString());
            sb.append(" flags=").append(it.getFlags());
            android.os.Bundle b = it.getExtras();
            if (b != null && !b.isEmpty()) {
                sb.append(" extras={");
                for (String k : b.keySet()) {
                    Object v = b.get(k);
                    String vs = v == null ? "null" : String.valueOf(v);
                    if (vs.length() > 150) vs = vs.substring(0, 150) + "...";
                    sb.append(k).append("=").append(vs).append("; ");
                }
                sb.append("}");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "dump失败: " + t.getMessage();
        }
    }

    private static void dumpFields(Object o, String prefix) {
        try {
            Class<?> c = o.getClass();
            LogWriter.log(TAG, prefix + " 类=" + c.getName());
            int level = 0;
            while (c != null && c != Object.class && level < 4) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        String vs = v == null ? "null" : String.valueOf(v);
                        if (vs.length() > 200) vs = vs.substring(0, 200) + "...";
                        LogWriter.log(TAG, prefix + " 字段 " + f.getName() + " = " + vs
                                + " (" + (v == null ? "null" : v.getClass().getSimpleName()) + ")");
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
                level++;
            }
            for (java.lang.reflect.Constructor<?> ctor : o.getClass().getDeclaredConstructors()) {
                StringBuilder sb = new StringBuilder(prefix + " 构造 " + o.getClass().getSimpleName() + "(");
                for (Class<?> pt : ctor.getParameterTypes()) sb.append(pt.getSimpleName()).append(",");
                sb.append(")");
                LogWriter.log(TAG, sb.toString());
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, prefix + " dumpFields 失败: " + t.getMessage());
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

    private static void findAndCopyCached(final TingMusicInfo info, final DownloadCallback cb) {
        new Thread(() -> {
            String result = null;
            String error = "缓存中未找到对应音频，请先完整播放该歌曲";
            try {
                File src = null;
                if (lastAudioFile != null) {
                    File f = new File(lastAudioFile);
                    if (f.exists() && f.length() > 0) src = f;
                }
                if (src == null) src = findCachedAudio(info.listenId);
                if (src != null && src.length() > 0) {
                    Context ctx = appContext != null ? appContext : currentChatting;
                    if (ctx == null) {
                        error = "无可用上下文";
                    } else {
                        File dir = new File(ctx.getCacheDir(), "ting_music");
                        if (!dir.exists()) dir.mkdirs();
                        File dst = new File(dir, "ting_" + System.currentTimeMillis() + guessExt(src.getName()));
                        copyFile(src, dst);
                        LogWriter.log(TAG, "[缓存] 源文件 " + src.getAbsolutePath() + " 大小 " + src.length());
                        result = dst.getAbsolutePath();
                    }
                }
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final String r = result, e = error;
            MAIN.post(() -> {
                if (r != null) cb.onSuccess(r); else cb.onFail(e);
            });
        }).start();
    }

    private static File findCachedAudio(String listenId) {
        if (listenId == null || listenId.isEmpty()) return null;
        String[] dirs = {
                "/data/data/com.tencent.mm/cache/resourceLoader",
                "/data/data/com.tencent.mm/cache/TingAudioCache",
                "/data/data/com.tencent.mm/cache/tingCache"
        };
        File best = null;
        for (String d : dirs) {
            File dir = new File(d);
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (!f.getName().contains(listenId)) continue;
                String low = f.getName().toLowerCase();
                boolean audio = low.endsWith(".mp4") || low.endsWith(".mp3")
                        || low.endsWith(".m4a") || low.endsWith(".aac")
                        || low.endsWith(".flac") || low.endsWith(".ogg")
                        || low.endsWith(".player");
                if (!audio) continue;
                if (best == null || f.length() > best.length()) best = f;
            }
        }
        return best;
    }

    private static void copyFile(File src, File dst) throws Exception {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.close();
        in.close();
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
