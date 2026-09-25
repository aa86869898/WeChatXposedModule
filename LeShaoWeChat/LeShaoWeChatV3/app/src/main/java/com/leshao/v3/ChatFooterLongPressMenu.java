package com.leshao.v3;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import android.widget.SeekBar;
import com.leshao.v3.ContextManager;
import com.leshao.v3.db.VoiceHistoryDbHelper;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.ui.InsetsUtil;
import com.leshao.v3.wm.utils.WmPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class ChatFooterLongPressMenu {

    private static final String TAG = "CFLPMenu";
    private static final int REQ_PICK_MP3 = 9998;
    private static final Set<View> injectedViews = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static PopupWindow popupWindow;
    private static ClassLoader sClassLoader;
    private static volatile String sCurrentTalker;
    private static Object sLastChatFooter;
    private static TextView sTargetPathText;
    private static String sLastPickedPath;
    private static ViewTreeObserver.OnGlobalLayoutListener sLayoutListener;
    private static View sLayoutAnchor;
    private static AlertDialog sHistoryDialog;
    private static float sCutBeginSec;
    private static float sCutEndSec;
    private static PopupWindow sProgressPopup;
    private static ProgressBar sProgressBar;
    private static TextView sProgressPct;
    private static TextView sProgressLabel;

    // v966: 处理选项播放图标
    private static MediaPlayer sPanelPlayer;
    private static ImageView sPanelPlayBtn;
    private static String sPanelPlayingPath;
    private static ImageView sHistPlayBtn;
    // v966: 历史记录勾选状态
    private static final java.util.Set<Long> sHistorySelected = new java.util.HashSet<>();
    private static final Map<Long, CheckBox> sHistoryChecks = new HashMap<>();
    private static TextView sHistorySelectAllBtn;
    private static boolean sHistoryAllSelected;

    // v966: 自绘图标类型
    private static final int GLYPH_PLAY = 0;
    private static final int GLYPH_PAUSE = 1;
    private static final int GLYPH_SEND = 2;
    private static final int GLYPH_DELETE = 3;

    public static void hook(ClassLoader cl) {
        sClassLoader = cl;
        LogWriter.log(TAG, "hook entry");
        try {
            Class<?> chatFooterClass = findClassIfExists("com.tencent.mm.pluginsdk.ui.chat.ChatFooter", cl);
            if (chatFooterClass == null) {
                LogWriter.log(TAG, "ChatFooter class not found");
                return;
            }
            LogWriter.log(TAG, "ChatFooter found: " + chatFooterClass.getName());

            if (!hookMethodX(chatFooterClass)) {
                hookConstructors(chatFooterClass);
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "Fatal: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }

        hookChattingUI(cl);
        hookActivityResult(cl);
    }

    private static void hookChattingUI(ClassLoader cl) {
        try {
            Class<?> chattingUIClass = findClassIfExists("com.tencent.mm.ui.chatting.ChattingUI", cl);
            if (chattingUIClass == null) {
                chattingUIClass = findClassIfExists("com.tencent.mm.ui.chatting.ChattingUIFragment", cl);
            }
            if (chattingUIClass == null) {
                LogWriter.log(TAG, "ChattingUI not found");
                return;
            }

            Method onResume = findMethodInHierarchy(chattingUIClass, "onResume");
            if (onResume == null) {
                LogWriter.log(TAG, "ChattingUI.onResume not found");
                return;
            }

            XposedBridge.hookMethod(onResume, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object obj = p.thisObject;
                        String[] fieldNames = {"talker", "mTalker", "nPf", "hHF"};
                        for (String fn : fieldNames) {
                            Field f = findField(obj.getClass(), fn);
                            if (f == null) continue;
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val instanceof String) {
                                String s = (String) val;
                                if (s != null && !s.isEmpty() && !s.equals("not_set")) {
                                    sCurrentTalker = s;
                                    LogWriter.log(TAG, "talker captured via onResume: " + s);
                                    return;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "ChattingUI.onResume hooked OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "hookChattingUI err: " + t.getMessage());
        }
    }

    private static void hookActivityResult(ClassLoader cl) {
        String[] targets = {"com.tencent.mm.ui.LauncherUI", "com.tencent.mm.ui.chatting.ChattingUI"};
        boolean anyHooked = false;
        java.util.Set<Method> hookedMethods = new java.util.HashSet<>();
        for (String className : targets) {
            Class<?> c = findClassIfExists(className, cl);
            if (c == null) continue;
            // v1024: 仅 hook 第一个声明方法不可靠 —— 微信基类可能在中间层 override
            // onActivityResult 且不调用 super, 导致 hook 的父类方法永不执行(v1023 实测
            // startActivityForResult 发出后零回调)。改为 hook 继承链每一层的声明方法。
            int layer = 0;
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                Method m = null;
                try { m = cur.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class); }
                catch (NoSuchMethodException ignored) {}
                if (m != null && hookedMethods.add(m)) {
                    final String layerCls = cur.getName();
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                onActivityResultHook(p);
                            } catch (Throwable e) {
                                LogWriter.log("CFLPMenu", "cb err: " + e);
                            }
                        }
                    });
                    LogWriter.log(TAG, "onActivityResult hooked on " + c.getSimpleName()
                            + " layer=" + layer + " decl=" + layerCls);
                    anyHooked = true;
                }
                cur = cur.getSuperclass();
                layer++;
            }
        }
        // v1024 兜底: hook framework Activity.onActivityResult —— 任何微信 Activity 收到
        // 结果都会沿继承链到达(除非某层 override 且不调 super, 此时上面逐层 hook 已覆盖)。
        try {
            Method base = Activity.class.getDeclaredMethod(
                    "onActivityResult", int.class, int.class, Intent.class);
            if (hookedMethods.add(base)) {
                XposedBridge.hookMethod(base, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            int requestCode = (int) p.args[0];
                            if (requestCode != REQ_PICK_MP3) return;
                            onActivityResultHook(p);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "onActivityResult framework Activity base hooked");
                anyHooked = true;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "framework Activity base hook err: " + t.getMessage());
        }
        // v1024 最强兜底: hook framework Activity.dispatchActivityResult —— 系统分发结果的
        // 入口, 早于微信任何 onActivityResult override, 即使微信某层 override 不调 super 也能捕获。
        try {
            for (Method dm : Activity.class.getDeclaredMethods()) {
                if (!"dispatchActivityResult".equals(dm.getName())) continue;
                Class<?>[] pts = dm.getParameterTypes();
                int reqIdx = -1, resIdx = -1, dataIdx = -1;
                for (int i = 0; i < pts.length; i++) {
                    if (pts[i] == int.class && reqIdx < 0) reqIdx = i;
                    else if (pts[i] == int.class && resIdx < 0) resIdx = i;
                    else if (pts[i] == Intent.class) dataIdx = i;
                }
                if (reqIdx < 0 || resIdx < 0 || dataIdx < 0) continue;
                if (!hookedMethods.add(dm)) continue;
                final int ri = reqIdx, si = resIdx, di = dataIdx;
                XposedBridge.hookMethod(dm, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            int requestCode = (int) p.args[ri];
                            if (requestCode != REQ_PICK_MP3) return;
                            int resultCode = (int) p.args[si];
                            Intent data = (Intent) p.args[di];
                            LogWriter.log(TAG, "dispatchActivityResult recv: req=" + requestCode
                                    + " result=" + resultCode + " this="
                                    + (p.thisObject != null ? p.thisObject.getClass().getSimpleName() : "null"));
                            handleResult(requestCode, resultCode, data);
                        } catch (Throwable ignored) {}
                    }
                });
                LogWriter.log(TAG, "dispatchActivityResult hooked (" + pts.length + " params)");
                anyHooked = true;
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "dispatchActivityResult hook err: " + t.getMessage());
        }
        if (!anyHooked) {
            LogWriter.log(TAG, "onActivityResult NOT hooked on any target class");
        }
    }

    private static void onActivityResultHook(XC_MethodHook.MethodHookParam p) {
        try {
            int requestCode = (int) p.args[0];
            int resultCode = (int) p.args[1];
            Intent data = (Intent) p.args[2];
            // v1023: 记录所有到达 onActivityResult 的请求, 用于诊断回调链路
            LogWriter.log(TAG, "onActivityResult recv: req=" + requestCode
                    + " result=" + resultCode + " data=" + (data != null ? data.getData() : "null")
                    + " this=" + (p.thisObject != null ? p.thisObject.getClass().getSimpleName() : "null"));
            handleResult(requestCode, resultCode, data);
        } catch (Throwable t) {
            LogWriter.log(TAG, "onActivityResult err: " + t.getMessage());
        }
    }

    /** v1024: 统一结果处理入口(供 onActivityResult 与 dispatchActivityResult 两个捕获点复用) */
    private static void handleResult(int requestCode, int resultCode, Intent data) {
        try {
            if (requestCode != REQ_PICK_MP3 || resultCode != Activity.RESULT_OK || data == null) return;

            Uri uri = data.getData();
            if (uri == null) return;

            Context ctx = null;
            if (sTargetPathText != null) {
                ctx = sTargetPathText.getContext();
            }
            if (ctx == null) {
                try {
                    ctx = ContextManager.getAppContext();
                } catch (Throwable ignored) {}
            }
            if (ctx == null) return;

            String path = resolveUri(ctx, uri);
            if (path != null) {
                sLastPickedPath = path;
                if (sTargetPathText != null) {
                    sTargetPathText.setText(path);
                }
                LogWriter.log(TAG, "file picked: " + path);
            } else {
                Toast.makeText(ctx, "无法读取该文件，请重试", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            LogWriter.log(TAG, "handleResult err: " + t.getMessage());
        }
    }

    private static String resolveUri(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex("_data");
                        if (idx >= 0) {
                            String p = cursor.getString(idx);
                            if (p != null && new File(p).exists()) return p;
                        }
                        idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) {
                            String name = cursor.getString(idx);
                            if (name != null) {
                                InputStream is = null;
                                FileOutputStream fos = null;
                                try {
                                    is = ctx.getContentResolver().openInputStream(uri);
                                    if (is != null) {
                                        File tmp = new File(ctx.getCacheDir(), name);
                                        fos = new FileOutputStream(tmp);
                                        byte[] buf = new byte[8192];
                                        int n;
                                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                                        return tmp.getAbsolutePath();
                                    }
                                } finally {
                                    try { if (fos != null) fos.close(); } catch (Throwable ignored) {}
                                    try { if (is != null) is.close(); } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {} finally {
                    cursor.close();
                }
            }
        }
        return null;
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static boolean hookMethodX(Class<?> clazz) {
        try {
            Method method = clazz.getDeclaredMethod("x", View.class, String.class);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                                        String s = (String) p.args[1];
                                        if ("chat_left_side_audio_btn".equals(s) || "chat_left_side_keyboard_btn".equals(s)) {
                                            sLastChatFooter = p.thisObject;
                                            inject((View) p.args[0]);
                                        }
                    } catch (Throwable e) {
                        LogWriter.log("CFLPMenu", "cb err: " + e);
                    }
                }
            });
            LogWriter.log(TAG, "[x] hooked OK");
            return true;
        } catch (NoSuchMethodException e) {
            LogWriter.log(TAG, "[x] not found, fallback to constructors");
            return false;
        }
    }

    private static void hookConstructors(Class<?> clazz) {
        XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Field f = findField(p.thisObject.getClass(), "q");
                        if (f != null) {
                            f.setAccessible(true);
                            View v = (View) f.get(p.thisObject);
                            sLastChatFooter = p.thisObject;
                            inject(v);
                        }
                    } catch (Throwable t) {
                        LogWriter.log(TAG, "ctor inject err: " + t.getMessage());
                    }
                }, 800);
            }
        });
        LogWriter.log(TAG, "constructors hooked");
    }

    private static void inject(View btnView) {
        if (!WmPrefs.isLongPressMenu()) return;
        if (btnView == null) return;
        synchronized (injectedViews) {
            if (injectedViews.contains(btnView)) return;
            injectedViews.add(btnView);
        }
        LogWriter.log(TAG, "Inject: " + btnView.getClass().getSimpleName());
        btnView.setOnLongClickListener(v -> {
            // v960: 长按弹面板异常必须兜底(展示/构建失败不得闪退)
            try {
                showPanel(v);
            } catch (Throwable t) {
                LogWriter.log(TAG, "long click err: " + t.getMessage());
            }
            return true;
        });
        btnView.setOnTouchListener((v, e) -> {
            // v960: v.getParent() 未判空在触摸时 NPE 闪退
            try {
                if (e.getAction() == 0 && v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    private static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    @SuppressLint("RtlHardcoded")
    public static void showPanelStatic(View anchor) {
        showPanel(anchor);
    }

    @SuppressLint("RtlHardcoded")
    private static void showPanel(View anchor) {
        if (!WmPrefs.isLongPressMenu()) return;
        // v961: 全量兜底 —— v960 只包了 showAtLocation, 构建/PopupWindow 构造阶段的
        // IllegalStateException(child already has a parent)仍会冒泡到按钮点击, 表现为"点了没反应"
        try {
            showPanelInner(anchor);
        } catch (Throwable t) {
            LogWriter.log(TAG, "showPanel err: " + android.util.Log.getStackTraceString(t));
            try {
                if (popupWindow != null && popupWindow.isShowing()) popupWindow.dismiss();
            } catch (Throwable ignored) {}
            popupWindow = null;
            removeLayoutListener();
        }
    }

    private static void showPanelInner(View anchor) {
        if (popupWindow != null) {
            try {
                if (popupWindow.isShowing()) popupWindow.dismiss();
            } catch (Throwable ignored) {}
            popupWindow = null;
            removeLayoutListener();
        }

        Context ctx = anchor.getContext();

        int cardBg = AppColors.card();
        int p6 = dp(ctx, 6);
        int p8 = dp(ctx, 8);
        int p10 = dp(ctx, 10);
        int p12 = dp(ctx, 12);

        // 主容器（垂直）— v955 M3: 28dp extra-large 圆角对话框
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(CandyUi.dialogBg(ctx));
        root.setPadding(p8, dp(ctx, 4), p8, p8);
        InsetsUtil.padTop(root, InsetsUtil.topInset(ctx, anchor));
        InsetsUtil.padBottom(root, InsetsUtil.bottomInset(ctx, anchor));
        InsetsUtil.clipRounded(root);

        // 标题栏（横跨，居中）— v955 M3: 20sp onSurface 粗体
        TextView titleBar = new TextView(ctx);
        titleBar.setText("乐少音频转语音助手");
        titleBar.setTextSize(18);
        titleBar.setTextColor(AppColors.onSurface());
        titleBar.setTypeface(null, android.graphics.Typeface.BOLD);
        titleBar.setGravity(Gravity.CENTER);
        titleBar.setPadding(0, dp(ctx, 6), 0, p8);
        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 音频转语音 单面板（取消左侧分类/右侧面板方式）
        // v1023: 面板重新打开时清空上次选择的音频文件(需重新选择)
        sLastPickedPath = null;
        View audioPanel = createAudioToVoicePanel(ctx);
        root.addView(audioPanel);

        // PopupWindow
        // 宽度固定为屏宽 85% (放大), 高度自适应
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int panelW = (int) (dm.widthPixels * 0.85f);
        popupWindow = new PopupWindow(root, panelW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(0));
        popupWindow.setElevation(dp(ctx, 8));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // v961: 屏幕正中居中显示(Gravity.CENTER), 不再跟随按钮定位;
        // showAtLocation 的 token 失效/子view冲突等异常已在 showPanel 全量兜底
        try {
            popupWindow.showAtLocation(anchor, Gravity.CENTER, 0, 0);
            popupWindow.setOnDismissListener(() -> {
                // v966: 面板关闭时停止试播并释放播放器
                stopPanelPlayback();
                sPanelPlayBtn = null;
                removeLayoutListener();
            });
        } catch (Throwable t) {
            LogWriter.log(TAG, "showAtLocation FAILED: " + android.util.Log.getStackTraceString(t));
            try {
                if (popupWindow != null && popupWindow.isShowing()) popupWindow.dismiss();
            } catch (Throwable ignored) {}
            popupWindow = null;
            removeLayoutListener();
            return;
        }
    }

    private static void removeLayoutListener() {
        if (sLayoutListener != null && sLayoutAnchor != null) {
            try { sLayoutAnchor.getViewTreeObserver().removeOnGlobalLayoutListener(sLayoutListener); } catch (Throwable ignored) {}
        }
        sLayoutListener = null;
        sLayoutAnchor = null;
    }

    /** v966: 历史记录底部操作栏按钮等分布局参数 */
    private static LinearLayout.LayoutParams barLp(Context ctx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(ctx, 4);
        lp.rightMargin = dp(ctx, 4);
        return lp;
    }

    /** v966: 自绘单色矢量小图标(播放/暂停/发送/删除) */
    private static android.graphics.Bitmap makeGlyphIcon(Context ctx, int type, int sizeDp, int color) {
        int size = Math.max(dp(ctx, sizeDp), 1);
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        float w = size, h = size;
        switch (type) {
            case GLYPH_PLAY: {
                p.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path tri = new android.graphics.Path();
                tri.moveTo(w * 0.28f, h * 0.18f);
                tri.lineTo(w * 0.84f, h * 0.50f);
                tri.lineTo(w * 0.28f, h * 0.82f);
                tri.close();
                c.drawPath(tri, p);
                break;
            }
            case GLYPH_PAUSE: {
                p.setStyle(android.graphics.Paint.Style.FILL);
                c.drawRoundRect(new android.graphics.RectF(w * 0.22f, h * 0.18f, w * 0.40f, h * 0.82f), 2, 2, p);
                c.drawRoundRect(new android.graphics.RectF(w * 0.60f, h * 0.18f, w * 0.78f, h * 0.82f), 2, 2, p);
                break;
            }
            case GLYPH_SEND: {
                p.setStyle(android.graphics.Paint.Style.FILL);
                android.graphics.Path plane = new android.graphics.Path();
                plane.moveTo(w * 0.08f, h * 0.52f);
                plane.lineTo(w * 0.92f, h * 0.12f);
                plane.lineTo(w * 0.56f, h * 0.90f);
                plane.lineTo(w * 0.42f, h * 0.62f);
                plane.close();
                c.drawPath(plane, p);
                p.setColor(0x88000000 | (color & 0x00FFFFFF));
                android.graphics.Path fold = new android.graphics.Path();
                fold.moveTo(w * 0.42f, h * 0.62f);
                fold.lineTo(w * 0.92f, h * 0.12f);
                fold.lineTo(w * 0.50f, h * 0.74f);
                fold.close();
                c.drawPath(fold, p);
                break;
            }
            case GLYPH_DELETE: {
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(size * 0.09f, 1f));
                p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                c.drawLine(w * 0.14f, h * 0.24f, w * 0.86f, h * 0.24f, p);
                c.drawLine(w * 0.36f, h * 0.13f, w * 0.64f, h * 0.13f, p);
                c.drawRoundRect(new android.graphics.RectF(w * 0.24f, h * 0.32f, w * 0.76f, h * 0.88f), 3, 3, p);
                c.drawLine(w * 0.40f, h * 0.46f, w * 0.40f, h * 0.74f, p);
                c.drawLine(w * 0.60f, h * 0.46f, w * 0.60f, h * 0.74f, p);
                break;
            }
        }
        return bmp;
    }

    /** v966: 开始播放指定音频(自动停止现有播放); 返回是否成功 */
    private static boolean startPanelPlayback(Context ctx, String path) {
        stopPanelPlayback();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(path);
            mp.setOnCompletionListener(m -> stopPanelPlayback());
            mp.prepare();
            mp.start();
            sPanelPlayer = mp;
            sPanelPlayingPath = path;
            if (sPanelPlayBtn != null) {
                sPanelPlayBtn.setImageBitmap(makeGlyphIcon(ctx, GLYPH_PAUSE, 20, AppColors.accent()));
            }
            return true;
        } catch (Throwable t) {
            stopPanelPlayback();
            Toast.makeText(ctx, "播放失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** v966: 主面板播放图标点击(播放/暂停当前选中音频) */
    private static void togglePanelPlayback(Context ctx, String path) {
        if (sPanelPlayer != null && path != null && path.equals(sPanelPlayingPath)) {
            stopPanelPlayback();
            return;
        }
        startPanelPlayback(ctx, path);
    }

    /** v966: 停止试播并恢复播放图标(其它操作按钮点击时调用) */
    private static void stopPanelPlayback() {
        if (sPanelPlayer != null) {
            try { sPanelPlayer.stop(); } catch (Throwable ignored) {}
            try { sPanelPlayer.release(); } catch (Throwable ignored) {}
            sPanelPlayer = null;
        }
        sPanelPlayingPath = null;
        if (sPanelPlayBtn != null) {
            sPanelPlayBtn.setImageBitmap(makeGlyphIcon(
                    sPanelPlayBtn.getContext(), GLYPH_PLAY, 20, AppColors.accent()));
        }
        if (sHistPlayBtn != null) {
            sHistPlayBtn.setImageBitmap(makeGlyphIcon(
                    sHistPlayBtn.getContext(), GLYPH_PLAY, 18, AppColors.accent()));
            sHistPlayBtn = null;
        }
    }

    private static View createAudioToVoicePanel(Context ctx) {
        int text1 = AppColors.text1();
        int text2 = AppColors.text2();
        int accent = AppColors.accent();

        int p6 = dp(ctx, 6);
        int p8 = dp(ctx, 8);
        int p10 = dp(ctx, 10);
        int p12 = dp(ctx, 12);

        // v955 M3 重排: 分区卡片结构(文件→选项→转换)
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x00000000);

        // ===== 分区1: 音频文件(卡片) =====
        panel.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "请选择需要转换的音频文件"));
        LinearLayout cardFile = com.leshao.v3.ui.widgets.M3Page.card(ctx);

        // 文件选择行: [路径文本] [选择]
        LinearLayout fileRow = new LinearLayout(ctx);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setPadding(p12, p10, p12, p8);

        final TextView pathText = new TextView(ctx);
        pathText.setTextSize(12);
        pathText.setTextColor(text1);
        pathText.setPadding(p8, p8, p8, p8);
        pathText.setBackground(CandyUi.inputBg(ctx));
        pathText.setText(sLastPickedPath != null ? sLastPickedPath : "未选择文件");
        pathText.setMaxLines(1);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fileRow.addView(pathText, pathLp);

        TextView browseBtn = new TextView(ctx);
        browseBtn.setText("选择");
        browseBtn.setTextSize(12);
        browseBtn.setTextColor(AppColors.textOnPrimary());
        browseBtn.setGravity(Gravity.CENTER);
        browseBtn.        setPadding(dp(ctx, 4), p8, dp(ctx, 4), p8);
        GradientDrawable browseBg = new GradientDrawable();
        browseBg.setColor(AppColors.primary());
        browseBg.setCornerRadius(dp(ctx, 20));
        browseBtn.setBackground(browseBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = p8;
        fileRow.addView(browseBtn, btnLp);
        // v962 修复: 原 487 行 panel.addView(fileRow) 与 489 行 cardFile.addView(fileRow)
        // 对同一 view 重复 addView, 第二次必抛 IllegalStateException(child already has a
        // parent), 面板构建中断 → 语音按钮点击无反应(实测 v961 日志 16 次)。fileRow 只属 cardFile。
        cardFile.addView(fileRow);

        // 已选文件名显示
        final TextView fileNameTv = new TextView(ctx);
        fileNameTv.setTextSize(13);
        fileNameTv.setTextColor(text2);
        fileNameTv.setPadding(p12, 0, p12, p10);
        fileNameTv.setVisibility(View.GONE);
        cardFile.addView(fileNameTv);
        panel.addView(cardFile);

        // ===== 分区2: 处理选项(卡片) =====
        panel.addView(new com.leshao.v3.ui.widgets.SectionHeader(ctx, "处理选项"));
        LinearLayout cardOpt = com.leshao.v3.ui.widgets.M3Page.card(ctx);

        // 音频切割 / 历史记录 按钮行
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(p12, p10, p12, p8);

        final TextView cutBtn = new TextView(ctx);
        cutBtn.setText("音频切割");
        cutBtn.setTextSize(12);
        cutBtn.setTextColor(accent);
        cutBtn.setGravity(Gravity.CENTER);
        cutBtn.setPadding(dp(ctx, 4), p6, dp(ctx, 4), p6);
        GradientDrawable cutBg = new GradientDrawable();
        cutBg.setStroke(dp(ctx, 1), AppColors.outline());
        cutBg.setCornerRadius(dp(ctx, 20));
        cutBg.setColor(0x00000000);
        cutBtn.setBackground(cutBg);
        cutBtn.setTextColor(AppColors.primary());
        cutBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams cutLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cutLp.rightMargin = p6;
        btnRow.addView(cutBtn, cutLp);

        // v966: 播放图标(位于音频切割与历史记录之间, 点击试播当前选中音频, 再点暂停)
        final ImageView playIcon = new ImageView(ctx);
        playIcon.setImageBitmap(makeGlyphIcon(ctx, GLYPH_PLAY, 20, AppColors.accent()));
        playIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playIcon.setPadding(dp(ctx, 8), p6, dp(ctx, 8), p6);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(AppColors.surfaceContainerHigh());
        playBg.setStroke(dp(ctx, 1), AppColors.outlineVariant());
        playBg.setCornerRadius(dp(ctx, 20));
        playIcon.setBackground(playBg);
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32));
        playLp.leftMargin = p6;
        playLp.rightMargin = p6;
        btnRow.addView(playIcon, playLp);
        sPanelPlayBtn = playIcon;
        playIcon.setOnClickListener(pv -> {
            String p = pathText.getText().toString().trim();
            if (p.isEmpty() || p.equals("未选择文件")) {
                Toast.makeText(ctx, "请先选择需要转换的音频文件", Toast.LENGTH_SHORT).show();
                return;
            }
            togglePanelPlayback(ctx, p);
        });

        final TextView historyBtn = new TextView(ctx);
        historyBtn.setText("历史记录");
        historyBtn.setTextSize(12);
        historyBtn.setTextColor(text2);
        historyBtn.setGravity(Gravity.CENTER);
        historyBtn.setPadding(dp(ctx, 4), p6, dp(ctx, 4), p6);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(AppColors.surfaceContainerHigh());
        hBg.setStroke(dp(ctx, 1), AppColors.outlineVariant());
        hBg.setCornerRadius(dp(ctx, 20));
        historyBtn.setBackground(hBg);
        LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        histLp.leftMargin = p6;
        btnRow.addView(historyBtn, histLp);
        cardOpt.addView(btnRow);
        cardOpt.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));

        // v968: 误报语音时长(0-60 秒, 默认 1 秒) —— 发出的语音气泡所显示时长
        LinearLayout durTail = new LinearLayout(ctx);
        durTail.setOrientation(LinearLayout.HORIZONTAL);
        durTail.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText etFakeDur =
                com.leshao.v3.ui.widgets.M3Page.input(ctx, "1");
        etFakeDur.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etFakeDur.setGravity(Gravity.CENTER);
        etFakeDur.setText(String.valueOf(WmPrefs.getInt("voice_fake_duration_sec", 1)));
        etFakeDur.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 56),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        durTail.addView(etFakeDur);
        TextView durUnit = new TextView(ctx);
        durUnit.setText(" 秒");
        durUnit.setTextSize(14);
        durUnit.setTextColor(text2);
        durTail.addView(durUnit);
        etFakeDur.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int v = Integer.parseInt(s.toString().trim());
                    if (v < 0) v = 0;
                    if (v > 60) v = 60;
                    WmPrefs.setInt("voice_fake_duration_sec", v);
                } catch (Throwable ignored) {}
            }
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
        });
        cardOpt.addView(new com.leshao.v3.ui.widgets.SettingRow(ctx, "⏱", "误报语音时长",
                "0-60 秒, 默认 1 秒(语音气泡显示时长)").tail(durTail));
        cardOpt.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));

        // 人声增强: 必须用 SettingRow.switchOn 把开关放右侧, 禁止自定义按钮
        cardOpt.addView(new com.leshao.v3.ui.widgets.SettingRow(ctx, "🎙", "人声增强",
                "开启=人声增强链; 关闭=原音还原(默认)")
                .switchOn(WmPrefs.get("voice_enhance", false), (b, checked) -> {
                    LogWriter.log(TAG, "click: 人声增强 -> " + checked);
                    WmPrefs.set("voice_enhance", checked);
                }));
        cardOpt.addView(com.leshao.v3.ui.widgets.M3Page.divider(ctx));

        // 进度条(归入选项卡片, M3 主色)
        final ProgressBar progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(ctx, 6));
        pbLp.setMargins(p12, p8, p12, 0);
        cardOpt.addView(progressBar, pbLp);

        // 进度文字
        final TextView progressText = new TextView(ctx);
        progressText.setTextSize(13);
        progressText.setTextColor(text2);
        progressText.setGravity(Gravity.CENTER);
        progressText.setVisibility(View.GONE);
        progressText.setPadding(p12, p6, p12, p10);
        cardOpt.addView(progressText);
        panel.addView(cardOpt);

        // ===== 分区3: 转换(独立卡片 + M3 filled 按钮) =====
        // v966: 删除「开始转换」标题及小字, 仅保留按钮(按钮右侧带发送图标)
        LinearLayout cardConv = com.leshao.v3.ui.widgets.M3Page.card(ctx);
        LinearLayout convRow = new LinearLayout(ctx);
        convRow.setOrientation(LinearLayout.HORIZONTAL);
        convRow.setPadding(p12, p10, p12, p10);

        // 转码按钮
        // v955 M3: 转换按钮改 ModernButton filled(全圆角主色, 40dp 高)
        final TextView convertBtn = new TextView(ctx);
        convertBtn.setText("开始转换");
        convertBtn.setTextSize(15);
        convertBtn.setTextColor(AppColors.textOnPrimary());
        convertBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        convertBtn.setGravity(Gravity.CENTER);
        convertBtn.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        GradientDrawable cvtBg = new GradientDrawable();
        cvtBg.setColor(AppColors.primary());
        cvtBg.setCornerRadius(dp(ctx, 20));
        convertBtn.setBackground(cvtBg);
        // v966: 按钮文字右侧加发送图标
        try {
            android.graphics.drawable.Drawable sendIc = new android.graphics.drawable.BitmapDrawable(
                    ctx.getResources(), makeGlyphIcon(ctx, GLYPH_SEND, 16, AppColors.textOnPrimary()));
            sendIc.setBounds(0, 0, dp(ctx, 16), dp(ctx, 16));
            convertBtn.setCompoundDrawables(null, null, sendIc, null);
            convertBtn.setCompoundDrawablePadding(dp(ctx, 6));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams cvtLp = new LinearLayout.LayoutParams(-1, dp(ctx, 44));
        convertBtn.setLayoutParams(cvtLp);
        convRow.addView(convertBtn);
        cardConv.addView(convRow);
        panel.addView(cardConv);

        // 事件绑定
        browseBtn.setOnClickListener(v -> {
            stopPanelPlayback();
            Activity act = getActivityFromContext(ctx);
            if (act == null) {
                Toast.makeText(ctx, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                return;
            }
            sTargetPathText = pathText;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
                    "audio/ogg", "audio/mp4", "audio/aac", "audio/flac",
                    "audio/x-ms-wma", "audio/opus"
            });
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            try {
                LogWriter.log(TAG, "startActivityForResult from " + act.getClass().getName()
                        + " req=" + REQ_PICK_MP3);
                act.startActivityForResult(intent, REQ_PICK_MP3);
            } catch (Throwable t) {
                LogWriter.log(TAG, "open picker FAILED: " + t);
                Toast.makeText(ctx, "打开文件选择器失败", Toast.LENGTH_SHORT).show();
            }
        });

        cutBtn.setOnClickListener(cutV -> {
            stopPanelPlayback();
            String mp3Path = pathText.getText().toString().trim();
            if (mp3Path.isEmpty() || mp3Path.equals("未选择文件")) {
                Toast.makeText(ctx, "请先选择MP3文件", Toast.LENGTH_SHORT).show();
                return;
            }
            showCutDialog(ctx, mp3Path, fileNameTv);
        });

        historyBtn.setOnClickListener(histV -> {
            stopPanelPlayback();
            showHistoryDialog(ctx);
        });

        convertBtn.setOnClickListener(v -> {
            stopPanelPlayback();
            final String mp3Path = pathText.getText().toString().trim();
            if (mp3Path.isEmpty() || mp3Path.equals("未选择文件")) {
                Toast.makeText(ctx, "请先选择MP3文件", Toast.LENGTH_SHORT).show();
                return;
            }
            String cachedTalker = sCurrentTalker;
            String talker = (cachedTalker != null && !cachedTalker.isEmpty())
                    ? cachedTalker : getTalker(ctx);
            if (talker == null || talker.isEmpty()) {
                Toast.makeText(ctx, "无法获取当前聊天对象", Toast.LENGTH_SHORT).show();
                return;
            }
            final String finalTalker = talker;

            if (popupWindow != null) popupWindow.dismiss();

            LogWriter.log(TAG, "click: 开始转换 talker=" + finalTalker + " path=" + mp3Path);

            // 直接转换: 先弹进度窗, 再后台转码发送
            final float cutBegin = sCutBeginSec;
            final float cutEnd = sCutEndSec;
            sCutBeginSec = 0;
            sCutEndSec = 0;

            new Thread(() -> {
                transferAndReport(ctx, mp3Path, finalTalker, 0, fakeVoiceDurationMs(), cutBegin, cutEnd);
            }, "leshao-mp3-send").start();
        });

        // 文件选择回调更新显示
        final ViewTreeObserver.OnGlobalLayoutListener updateFileName = () -> {
            String name = new java.io.File(pathText.getText().toString().trim()).getName();
            boolean hasFile = !name.isEmpty() && !name.equals("未选择文件");
            if (hasFile) {
                String label = "已选: " + name;
                if (sCutBeginSec > 0 || sCutEndSec > 0) {
                    label += " (裁剪 " + formatSec(sCutBeginSec) + "-" + formatSec(sCutEndSec) + ")";
                }
                fileNameTv.setText(label);
                fileNameTv.setVisibility(View.VISIBLE);
                cutBtn.setVisibility(View.VISIBLE);
            } else {
                cutBtn.setVisibility(View.GONE);
            }
            // v967: 未选音频文件前不显示播放图标
            playIcon.setVisibility(hasFile ? View.VISIBLE : View.GONE);
        };
        pathText.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) { updateFileName.onGlobalLayout(); }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
        updateFileName.onGlobalLayout();

        return panel;
    }

    /** v968: 误报语音时长(毫秒) —— 取「误报语音时长」设置(0-60 秒), 默认 1 秒 */
    private static int fakeVoiceDurationMs() {
        int sec = WmPrefs.getInt("voice_fake_duration_sec", 1);
        if (sec < 0) sec = 0;
        if (sec > 60) sec = 60;
        return sec * 1000;
    }

    private static String formatSec(float secs) {
        int total = (int) secs;
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
    }

    private static void transferAndReport(Context ctx, String mp3Path, String talker,
            int splitSeconds, int fakeDurationMs, float cutBeginSec, float cutEndSec) {
        final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        final CountDownLatch shown = new CountDownLatch(1);
        h.post(() -> {
            showConvertProgress(ctx);
            shown.countDown();
        });
        try { shown.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        try {
            VoiceHistoryDbHelper db = null;
            try {
                android.content.Context appCtx = ContextManager.getAppContext();
                if (appCtx != null) db = VoiceHistoryDbHelper.getInstance(appCtx);
            } catch (Throwable ignored) {}
            final VoiceHistoryDbHelper finalDb = db;

            float begin = cutBeginSec;
            float end = cutEndSec;

            boolean ok = TtsVoiceSender.sendMp3Voice(talker, mp3Path, splitSeconds, fakeDurationMs,
                begin, end, new TtsVoiceSender.VoiceSendCallback() {
                    @Override
                    public void onProgress(int current, int total) {
                        h.post(() -> {
                            try {
                                ProgressBar bar = sProgressBar;
                                TextView pct = sProgressPct;
                                TextView label = sProgressLabel;
                                if (bar == null) return;
                                if (total == 100) {
                                    bar.setIndeterminate(false);
                                    bar.setMax(100);
                                    bar.setProgress(Math.min(current, 99));
                                    if (pct != null) {
                                        pct.setText("正在转码 " + Math.min(current, 99) + "%");
                                        pct.setVisibility(View.VISIBLE);
                                    }
                                    if (label != null) label.setText("");
                                } else if (total <= 1) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(bar.getMax());
                                    if (pct != null) {
                                        pct.setText("转码完成 即将发送");
                                        pct.setVisibility(View.VISIBLE);
                                    }
                                    if (label != null) label.setText("");
                                } else {
                                    bar.setIndeterminate(false);
                                    bar.setMax(total);
                                    bar.setProgress(current);
                                    int percent = total > 0 ? current * 100 / total : 0;
                                    if (pct != null) {
                                        pct.setText(percent + "%");
                                        pct.setVisibility(View.VISIBLE);
                                    }
                                    if (label != null) label.setText("发送中 (" + current + "/" + total + "段)");
                                }
                            } catch (Throwable ignored) {}
                        });
                    }
                });

            if (finalDb != null) {
                try {
                    finalDb.deleteExpired(System.currentTimeMillis() - 30L * 86400000L);
                } catch (Throwable ignored) {}
            }

            final boolean finalOk = ok;
            h.post(() -> {
                dismissConvertProgress();
                Toast.makeText(ctx, finalOk ? "语音已发送" : "发送失败", Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable t) {
            h.post(() -> {
                dismissConvertProgress();
                Toast.makeText(ctx, "转换失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    private static String getTalker(Context ctx) {
        // Primary: ChatFooter.d (输入栏绑定的当前实时会话, v923/v924 发送对象正确依赖此项)
        if (sLastChatFooter != null) {
            try {
                Field f = findField(sLastChatFooter.getClass(), "d");
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(sLastChatFooter);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (isValidTalker(s)) {
                            sCurrentTalker = s;
                            LogWriter.log(TAG, "talker from ChatFooter.d: " + s);
                            return s;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: ChattingUI.onResume cache
        if (isValidTalker(sCurrentTalker)) {
            return sCurrentTalker;
        }

        // Fallback: 当前 Activity intent/fragment 实时解析 (仅作兜底, 不可优先于 ChatFooter.d,
        // 否则 activity intent 残留旧会话会覆盖真实目标)
        Activity act = getActivityFromContext(ctx);
        if (act != null) {
            String s = resolveTalkerFromActivity(act);
            if (isValidTalker(s)) {
                sCurrentTalker = s;
                LogWriter.log(TAG, "talker from activity: " + s);
                return s;
            }
        }

        // Fallback: scan Activity fields
        return getTalkerFromActivity(ctx);
    }

    /** 实时从当前 Activity 的 intent (Chat_User 等 key) 与 ChattingUI Fragment 解析真实会话 */
    private static String resolveTalkerFromActivity(Activity act) {
        try {
            Intent it = act.getIntent();
            if (it != null) {
                String[] keys = { "Chat_User", "Chatroom_Name", "contact_username",
                        "username", "Openim_User", "Contact_User", "Chat_User_To",
                        "talker", "Talker" };
                for (String k : keys) {
                    try {
                        String v = it.getStringExtra(k);
                        if (isValidTalker(v)) return v;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try {
            Object fm = XposedHelpers.callMethod(act, "getSupportFragmentManager");
            if (fm != null) {
                List<?> frags = (List<?>) XposedHelpers.callMethod(fm, "getFragments");
                if (frags != null) {
                    for (Object f : frags) {
                        if (f == null) continue;
                        String cls = f.getClass().getName();
                        if (!cls.contains("ChattingUI")) continue;
                        try {
                            Object args = XposedHelpers.callMethod(f, "getArguments");
                            if (args instanceof android.os.Bundle) {
                                String u = ((android.os.Bundle) args).getString("Chat_User");
                                if (isValidTalker(u)) return u;
                            }
                        } catch (Throwable ignored) {}
                        try {
                            for (Field fl : f.getClass().getDeclaredFields()) {
                                if (fl.getType() != String.class) continue;
                                fl.setAccessible(true);
                                Object v = fl.get(f);
                                if (v instanceof String && isValidTalker((String) v)) return (String) v;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判定字符串是否为合法的微信会话对象 (排除内部假用户/模板账号) */
    private static boolean isValidTalker(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty() || "not_set".equals(s)) return false;
        // 内部假用户/品牌模板消息等, 直接排除
        if (s.contains("fakeuser") || s.contains("TemplateMsg") || s.contains("@bbn") ) return false;
        return s.startsWith("wxid_") || s.startsWith("gh_")
                || s.endsWith("@chatroom") || s.endsWith("@im.chatroom")
                || s.endsWith("@openim") || s.endsWith("@qqim")
                || s.matches("\\d{5,}");
    }

    private static String getTalkerFromActivity(Context ctx) {
        try {
            Activity act = getActivityFromContext(ctx);
            if (act == null) return null;
            return getTalkerFromObject(act);
        } catch (Throwable t) {
            LogWriter.log(TAG, "getTalkerFromActivity err: " + t.getMessage());
            return null;
        }
    }

    private static String getTalkerFromObject(Object obj) {
        String[] fieldNames = {"talker", "mTalker", "nPf", "hHF", "cXU", "aUa", "talkerName",
                "username", "mUsername", "userName", "contactName", "mContactName", "toUser",
                "toUsername", "chatUser", "chatUsername", "conversationUser", "mConvUser"};
        for (String fn : fieldNames) {
            Field f = findField(obj.getClass(), fn);
            if (f == null) continue;
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val instanceof String) {
                    String s = (String) val;
                    if (isValidTalker(s)) {
                        LogWriter.log(TAG, "getTalker: found via " + fn + "=" + s);
                        return s;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static float parseFloatSafe(String s, float defaultVal) {
        try { return Float.parseFloat(s); } catch (Throwable ignored) { return defaultVal; }
    }

    private static int parseIntSafe(String s, int defaultVal) {
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return defaultVal; }
    }

    private static void showCutDialog(Context ctx, String mp3Path, final TextView fileNameTv) {
        long durationMs = 0;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(mp3Path);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) durationMs = Long.parseLong(dur);
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法读取音频时长", Toast.LENGTH_SHORT).show();
            return;
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }

        if (durationMs <= 0) {
            Toast.makeText(ctx, "音频时长为0", Toast.LENGTH_SHORT).show();
            return;
        }

        final float totalSecs = durationMs / 1000f;
        final int totalInt = (int) totalSecs;

        float d = ctx.getResources().getDisplayMetrics().density;
        int p12 = (int)(12 * d);
        int p8 = (int)(8 * d);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p12, p12, p12, 0);

        int text1 = AppColors.text1();
        int text2 = AppColors.text2();
        int accent = AppColors.accent();
        int divider = AppColors.divider();

        TextView durTv = new TextView(ctx);
        durTv.setText("总时长: " + formatSec(totalSecs) + " (" + totalInt + "秒)");
        durTv.setTextSize(12);
        durTv.setTextColor(text2);
        durTv.setPadding(0, 0, 0, p12);
        root.addView(durTv);

        // 起始时间
        TextView startLabel = new TextView(ctx);
        startLabel.setText("起始时间 (秒)");
        startLabel.setTextSize(12);
        startLabel.setTextColor(text1);
        root.addView(startLabel);

        final EditText startInput = new EditText(ctx);
        startInput.setText("0");
        startInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        startInput.setTextColor(text1);
        startInput.setBackground(CandyUi.inputBg(ctx));
        startInput.setPadding(p12, p8, p12, p8);
        root.addView(startInput);

        View gap1 = new View(ctx);
        gap1.setLayoutParams(new LinearLayout.LayoutParams(-1, p8));
        root.addView(gap1);

        // 结束时间
        TextView endLabel = new TextView(ctx);
        endLabel.setText("结束时间 (秒, 0=到末尾)");
        endLabel.setTextSize(12);
        endLabel.setTextColor(text1);
        root.addView(endLabel);

        final EditText endInput = new EditText(ctx);
        endInput.setText("0");
        endInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        endInput.setTextColor(text1);
        endInput.setBackground(CandyUi.inputBg(ctx));
        endInput.setPadding(p12, p8, p12, p8);
        root.addView(endInput);

        View gap2 = new View(ctx);
        gap2.setLayoutParams(new LinearLayout.LayoutParams(-1, p12));
        root.addView(gap2);

        // 试听滑块
        final SeekBar seekBar = com.leshao.v3.ui.widgets.M3Page.slider(ctx);
        final int seekMax = Math.max(1, totalInt);
        seekBar.setMax(seekMax);
        seekBar.setProgress(0);
        root.addView(seekBar);

        // 设为起点 / 设为终点 按钮行
        LinearLayout markRow = new LinearLayout(ctx);
        markRow.setOrientation(LinearLayout.HORIZONTAL);
        markRow.setPadding(0, p8, 0, 0);

        final com.leshao.v3.ui.widgets.ModernButton markStartBtn =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "设为起点",
                        com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        msLp.rightMargin = (int)(4 * d);
        markRow.addView(markStartBtn, msLp);

        final com.leshao.v3.ui.widgets.ModernButton markEndBtn =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "设为终点",
                        com.leshao.v3.ui.widgets.ModernButton.STYLE_GHOST);
        LinearLayout.LayoutParams meLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        meLp.leftMargin = (int)(4 * d);
        markRow.addView(markEndBtn, meLp);
        root.addView(markRow);

        markStartBtn.setOnClickListener(msv -> {
            int pos = seekBar.getProgress();
            startInput.setText(String.valueOf(pos));
            Toast.makeText(ctx, "起点已设为 " + formatSec(pos), Toast.LENGTH_SHORT).show();
        });
        markEndBtn.setOnClickListener(mev -> {
            int pos = seekBar.getProgress();
            endInput.setText(String.valueOf(pos));
            Toast.makeText(ctx, "终点已设为 " + formatSec(pos), Toast.LENGTH_SHORT).show();
        });

        final TextView seekTime = new TextView(ctx);
        seekTime.setText("0:00 / " + formatSec(totalSecs));
        seekTime.setTextSize(11);
        seekTime.setTextColor(text2);
        seekTime.setGravity(Gravity.CENTER);
        seekTime.setPadding(0, p8, 0, p8);
        root.addView(seekTime);

        // 播放/暂停按钮
        final com.leshao.v3.ui.widgets.ModernButton playBtn =
                new com.leshao.v3.ui.widgets.ModernButton(ctx, "播放",
                        com.leshao.v3.ui.widgets.ModernButton.STYLE_PRIMARY);
        root.addView(playBtn);

        // MediaPlayer
        final MediaPlayer[] playerHolder = new MediaPlayer[1];
        final boolean[] isPlaying = {false};
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable seekUpdater = new Runnable() {
            @Override
            public void run() {
                if (playerHolder[0] != null && isPlaying[0]) {
                    try {
                        int pos = playerHolder[0].getCurrentPosition() / 1000;
                        seekBar.setProgress(Math.max(0, Math.min(seekMax, pos)));
                        seekTime.setText(formatSec(pos) + " / " + formatSec(totalSecs));
                        h.postDelayed(this, 200);
                    } catch (Throwable ignored) {}
                }
            }
        };

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    seekTime.setText(formatSec(progress) + " / " + formatSec(totalSecs));
                    try {
                        if (playerHolder[0] != null) {
                            playerHolder[0].seekTo(progress * 1000);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        playBtn.setOnClickListener(pv -> {
            if (isPlaying[0]) {
                try { if (playerHolder[0] != null) playerHolder[0].pause(); } catch (Throwable ignored) {}
                isPlaying[0] = false;
                playBtn.setText("播放");
                return;
            }

            try {
                if (playerHolder[0] == null) {
                    MediaPlayer mp = new MediaPlayer();
                    mp.setDataSource(mp3Path);
                    playBtn.setEnabled(false);
                    playBtn.setText("加载中...");
                    mp.setOnPreparedListener(preparedMp -> {
                        playBtn.setEnabled(true);
                        playBtn.setText("暂停");
                        preparedMp.start();
                        isPlaying[0] = true;
                        h.post(seekUpdater);
                    });
                    mp.setOnCompletionListener(m -> {
                        isPlaying[0] = false;
                        playBtn.setText("播放");
                        seekBar.setProgress(0);
                        seekTime.setText("0:00 / " + formatSec(totalSecs));
                    });
                    mp.setOnErrorListener((mpErr, what, extra) -> {
                        playBtn.setEnabled(true);
                        playBtn.setText("播放");
                        Toast.makeText(ctx, "播放失败", Toast.LENGTH_SHORT).show();
                        return true;
                    });
                    mp.prepareAsync();
                    playerHolder[0] = mp;
                } else {
                    playerHolder[0].start();
                    isPlaying[0] = true;
                    playBtn.setText("暂停");
                    h.post(seekUpdater);
                }
            } catch (Throwable t) {
                playBtn.setEnabled(true);
                playBtn.setText("播放");
                Toast.makeText(ctx, "播放失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle("音频切割")
            .setView(root)
            .setPositiveButton("确定", (dlg, w) -> {
                try {
                    if (playerHolder[0] != null) {
                        playerHolder[0].stop();
                        playerHolder[0].release();
                        playerHolder[0] = null;
                    }
                    h.removeCallbacks(seekUpdater);
                } catch (Throwable ignored) {}

                float begin = (float) parseIntSafe(startInput.getText().toString().trim(), 0);
                float end = (float) parseIntSafe(endInput.getText().toString().trim(), 0);
                if (begin < 0) begin = 0;
                if (end <= 0) end = totalSecs;
                if (end > totalSecs) end = totalSecs;
                if (begin >= end) {
                    Toast.makeText(ctx, "起始时间必须小于结束时间", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 直接转码发送: 确定即发送
                sCutBeginSec = 0;
                sCutEndSec = 0;
                final float fBegin = begin;
                final float fEnd = end;
                final String fTalker = (sCurrentTalker != null && !sCurrentTalker.isEmpty())
                        ? sCurrentTalker : getTalker(ctx);
                if (fTalker == null || fTalker.isEmpty()) {
                    Toast.makeText(ctx, "无法获取当前聊天对象", Toast.LENGTH_SHORT).show();
                    return;
                }
                new Thread(() -> {
                    transferAndReport(ctx, mp3Path, fTalker, 0, fakeVoiceDurationMs(), fBegin, fEnd);
                }, "leshao-mp3-send").start();
            })
            .setNegativeButton("取消", (dlg, w) -> {
                try {
                    if (playerHolder[0] != null) {
                        playerHolder[0].stop();
                        playerHolder[0].release();
                        playerHolder[0] = null;
                    }
                    h.removeCallbacks(seekUpdater);
                } catch (Throwable ignored) {}
            })
            .setNeutralButton("重置", (dlg, w) -> {
                try {
                    if (playerHolder[0] != null) {
                        playerHolder[0].stop();
                        playerHolder[0].release();
                        playerHolder[0] = null;
                    }
                    h.removeCallbacks(seekUpdater);
                } catch (Throwable ignored) {}
                sCutBeginSec = 0;
                sCutEndSec = 0;
                if (fileNameTv != null) {
                    String name = new java.io.File(mp3Path).getName();
                    fileNameTv.setText("已选: " + name);
                    fileNameTv.setVisibility(View.VISIBLE);
                }
            })
            .create();

        dialog.setOnDismissListener(dlg -> {
            try {
                h.removeCallbacks(seekUpdater);
                if (playerHolder[0] != null) {
                    playerHolder[0].stop();
                    playerHolder[0].release();
                    playerHolder[0] = null;
                }
            } catch (Throwable ignored) {}
        });

        dialog.show();
        themeAlertDialog(dialog);
    }

    private static void showHistoryDialog(Context ctx) {
        VoiceHistoryDbHelper db;
        try {
            android.content.Context appCtx = ContextManager.getAppContext();
            if (appCtx == null) {
                Toast.makeText(ctx, "初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }
            db = VoiceHistoryDbHelper.getInstance(appCtx);
        } catch (Throwable t) {
            Toast.makeText(ctx, "数据库初始化失败", Toast.LENGTH_SHORT).show();
            return;
        }

        java.util.List<VoiceHistoryDbHelper.VoiceHistoryItem> items = db.queryAll();

        float d = ctx.getResources().getDisplayMetrics().density;
        int p12 = (int)(12 * d);
        int p10 = (int)(10 * d);
        int p8 = (int)(8 * d);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p12, p12, p12, 0);
        scroll.addView(root);

        int cardBg = AppColors.card();
        int text1 = AppColors.text1();
        int text2 = AppColors.text2();
        int accent = AppColors.accent();
        int divider = AppColors.divider();

        if (items.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无历史记录");
            empty.setTextSize(14);
            empty.setTextColor(text2);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, p12, 0, p12);
            root.addView(empty);
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault());
            sHistoryChecks.clear();

            for (final VoiceHistoryDbHelper.VoiceHistoryItem item : items) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, p8, 0, p8);
                row.setGravity(Gravity.CENTER_VERTICAL);

                // v966: 勾选区域(行首)
                CheckBox cb = com.leshao.v3.ui.widgets.M3Page.checkBox(ctx);
                cb.setChecked(sHistorySelected.contains(item.id));
                cb.setOnCheckedChangeListener((b, checked) -> {
                    if (checked) sHistorySelected.add(item.id);
                    else sHistorySelected.remove(item.id);
                    sHistoryAllSelected = !items.isEmpty()
                            && sHistorySelected.size() >= items.size();
                    if (sHistorySelectAllBtn != null) {
                        sHistorySelectAllBtn.setText(sHistoryAllSelected ? "全不选" : "全选");
                    }
                });
                row.addView(cb);
                sHistoryChecks.put(item.id, cb);

                LinearLayout info = new LinearLayout(ctx);
                info.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                boolean fileExists = new File(item.filePath).exists();

                TextView nameTv = new TextView(ctx);
                nameTv.setText(item.fileName);
                nameTv.setTextSize(13);
                nameTv.setTextColor(fileExists ? text1 : AppColors.text3());
                nameTv.setMaxLines(1);
                nameTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                info.addView(nameTv);

                TextView metaTv = new TextView(ctx);
                String meta = sdf.format(new java.util.Date(item.createdAt));
                if (!fileExists) meta += " (文件已不存在)";
                metaTv.setText(meta);
                metaTv.setTextSize(11);
                metaTv.setTextColor(text2);
                info.addView(metaTv);

                row.addView(info, infoLp);

                if (fileExists) {
                    // v966: 播放图标
                    final ImageView playIc = new ImageView(ctx);
                    playIc.setImageBitmap(makeGlyphIcon(ctx, GLYPH_PLAY, 18, AppColors.accent()));
                    playIc.setPadding(p8, p8, p8, p8);
                    playIc.setOnClickListener(pv -> {
                        if (sPanelPlayer != null && item.filePath.equals(sPanelPlayingPath)) {
                            stopPanelPlayback();
                            return;
                        }
                        if (startPanelPlayback(ctx, item.filePath)) {
                            playIc.setImageBitmap(makeGlyphIcon(ctx, GLYPH_PAUSE, 18, AppColors.accent()));
                            sHistPlayBtn = playIc;
                        }
                    });
                    row.addView(playIc);

                    // v966: 发送图标(原「发送」文字)
                    ImageView sendIc = new ImageView(ctx);
                    sendIc.setImageBitmap(makeGlyphIcon(ctx, GLYPH_SEND, 18, AppColors.accent()));
                    sendIc.setPadding(p8, p8, p8, p8);
                    sendIc.setOnClickListener(reuseV -> {
                        stopPanelPlayback();
                        dismissHistoryDialog();
                        String talker = item.talker;
                        final String historyFilePath = item.filePath;
                        sCutBeginSec = 0;
                        sCutEndSec = 0;
                        new Thread(() -> transferAndReport(ctx, historyFilePath, talker, 0, fakeVoiceDurationMs(), 0, 0), "leshao-mp3-send").start();
                    });
                    row.addView(sendIc);
                }

                // v966: 删除图标(原「删除」文字)
                ImageView delIc = new ImageView(ctx);
                delIc.setImageBitmap(makeGlyphIcon(ctx, GLYPH_DELETE, 18, 0xFFB3261E));
                delIc.setPadding(p8, p8, p8, p8);
                delIc.setOnClickListener(delV -> {
                    db.deleteById(item.id);
                    sHistorySelected.remove(item.id);
                    dismissHistoryDialog();
                    if (popupWindow != null) popupWindow.dismiss();
                    showHistoryDialog(ctx);
                });
                row.addView(delIc);

                root.addView(row);

                View sep = new View(ctx);
                sep.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
                sep.setBackgroundColor(divider);
                root.addView(sep);
            }
        }

        View btnSep = new View(ctx);
        btnSep.setLayoutParams(new LinearLayout.LayoutParams(-1, p12));
        root.addView(btnSep);

        if (!items.isEmpty()) {
            // v966: 底部操作栏(全选 / 删除 / 发送)
            LinearLayout bar = new LinearLayout(ctx);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);

            sHistorySelectAllBtn = new TextView(ctx);
            sHistorySelectAllBtn.setText(sHistoryAllSelected ? "全不选" : "全选");
            sHistorySelectAllBtn.setTextSize(13);
            sHistorySelectAllBtn.setTextColor(AppColors.primary());
            sHistorySelectAllBtn.setGravity(Gravity.CENTER);
            sHistorySelectAllBtn.setPadding(p8, p10, p8, p10);
            GradientDrawable selBg = new GradientDrawable();
            selBg.setStroke(1, AppColors.outline());
            selBg.setCornerRadius(dp(ctx, 20));
            selBg.setColor(0x00000000);
            sHistorySelectAllBtn.setBackground(selBg);
            sHistorySelectAllBtn.setOnClickListener(selV -> {
                boolean target = !sHistoryAllSelected;
                for (CheckBox c : sHistoryChecks.values()) c.setChecked(target);
            });
            bar.addView(sHistorySelectAllBtn, barLp(ctx));

            TextView barDelBtn = new TextView(ctx);
            barDelBtn.setText("删除");
            barDelBtn.setTextSize(13);
            barDelBtn.setTextColor(0xFFB3261E);
            barDelBtn.setGravity(Gravity.CENTER);
            barDelBtn.setPadding(p8, p10, p8, p10);
            GradientDrawable delBg = new GradientDrawable();
            delBg.setStroke(1, AppColors.outline());
            delBg.setCornerRadius(dp(ctx, 20));
            delBg.setColor(0x00000000);
            barDelBtn.setBackground(delBg);
            barDelBtn.setOnClickListener(dv -> {
                stopPanelPlayback();
                if (sHistorySelected.isEmpty()) {
                    Toast.makeText(ctx, "请先勾选记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (Long id : new ArrayList<>(sHistorySelected)) db.deleteById(id);
                sHistorySelected.clear();
                sHistoryAllSelected = false;
                dismissHistoryDialog();
                if (popupWindow != null) popupWindow.dismiss();
                showHistoryDialog(ctx);
            });
            bar.addView(barDelBtn, barLp(ctx));

            TextView barSendBtn = new TextView(ctx);
            barSendBtn.setText("发送");
            barSendBtn.setTextSize(13);
            barSendBtn.setTextColor(AppColors.textOnPrimary());
            barSendBtn.setGravity(Gravity.CENTER);
            barSendBtn.setPadding(p8, p10, p8, p10);
            GradientDrawable sendBg = new GradientDrawable();
            sendBg.setColor(AppColors.primary());
            sendBg.setCornerRadius(dp(ctx, 20));
            barSendBtn.setBackground(sendBg);
            barSendBtn.setOnClickListener(sv -> {
                stopPanelPlayback();
                if (sHistorySelected.isEmpty()) {
                    Toast.makeText(ctx, "请先勾选记录", Toast.LENGTH_SHORT).show();
                    return;
                }
                List<VoiceHistoryDbHelper.VoiceHistoryItem> valid = new ArrayList<>();
                for (VoiceHistoryDbHelper.VoiceHistoryItem it : items) {
                    if (sHistorySelected.contains(it.id) && new File(it.filePath).exists()) {
                        valid.add(it);
                    }
                }
                if (valid.isEmpty()) {
                    Toast.makeText(ctx, "所选文件已不存在", Toast.LENGTH_SHORT).show();
                    return;
                }
                dismissHistoryDialog();
                if (popupWindow != null) popupWindow.dismiss();
                sHistorySelected.clear();
                sHistoryAllSelected = false;
                new Thread(() -> {
                    for (VoiceHistoryDbHelper.VoiceHistoryItem it : valid) {
                        transferAndReport(ctx, it.filePath, it.talker, 0, fakeVoiceDurationMs(), 0, 0);
                    }
                }, "leshao-mp3-send").start();
            });
            bar.addView(barSendBtn, barLp(ctx));

            root.addView(bar);
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle("历史记录")
            .setView(scroll)
            .setPositiveButton("返回", null)
            .create();

        sHistoryDialog = dialog;
        dialog.setOnDismissListener(dlg -> { if (sHistoryDialog == dialog) sHistoryDialog = null; });
        dialog.show();
        themeAlertDialog(dialog);

        android.view.Window win = dialog.getWindow();
        if (win != null) {
            try {
                android.view.WindowManager.LayoutParams lp = win.getAttributes();
                lp.height = (int)(300 * d);
                win.setAttributes(lp);
            } catch (Throwable ignored) {}
        }
    }

    private static void dismissHistoryDialog() {
        try {
            if (sHistoryDialog != null && sHistoryDialog.isShowing()) {
                sHistoryDialog.dismiss();
            }
        } catch (Throwable ignored) {}
        sHistoryDialog = null;
    }

    private static Activity getActivityFromContext(Context ctx) {
        try {
            Context c = ctx;
            while (c != null) {
                if (c instanceof Activity) return (Activity) c;
                if (c instanceof android.content.ContextWrapper) {
                    c = ((android.content.ContextWrapper) c).getBaseContext();
                } else {
                    break;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    private static Class<?> findClassIfExists(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static void showConvertProgress(Context ctx) {
        try {
            dismissConvertProgress();
            Activity act = getActivityFromContext(ctx);
            View anchor = null;
            if (act != null && act.getWindow() != null) {
                try { anchor = act.getWindow().peekDecorView(); } catch (Throwable ignored) {}
                if (anchor == null) {
                    try { anchor = act.findViewById(android.R.id.content); } catch (Throwable ignored) {}
                }
            }
            if (anchor == null) {
                LogWriter.log(TAG, "showConvertProgress: anchor null");
                return;
            }
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout pv = createProgressView(ctx, d);
            int w = (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.78f);
            PopupWindow pw = new PopupWindow(pv, w, ViewGroup.LayoutParams.WRAP_CONTENT, false);
            pw.setBackgroundDrawable(new ColorDrawable(0));
            try { pw.setElevation(dp(ctx, 8)); } catch (Throwable ignored) {}
            pw.setOutsideTouchable(false);
            pw.setFocusable(false);
            sProgressPopup = pw;
            pw.showAtLocation(anchor, Gravity.CENTER, 0, 0);
            LogWriter.log(TAG, "showConvertProgress OK");
        } catch (Throwable t) {
            LogWriter.log(TAG, "showConvertProgress err: " + android.util.Log.getStackTraceString(t));
        }
    }

    private static void dismissConvertProgress() {
        try {
            PopupWindow pw = sProgressPopup;
            sProgressPopup = null;
            sProgressBar = null;
            sProgressPct = null;
            sProgressLabel = null;
            if (pw != null && pw.isShowing()) pw.dismiss();
        } catch (Throwable ignored) {}
    }

    private static LinearLayout createProgressView(Context ctx, float d) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(CandyUi.dialogBg(ctx));
        InsetsUtil.clipRounded(root);
        root.setPadding((int)(24 * d), (int)(24 * d), (int)(24 * d), (int)(20 * d));
        root.setMinimumWidth((int)(260 * d));

        TextView title = new TextView(ctx);
        title.setText("音频转语音");
        title.setTextSize(18);
        title.setTextColor(AppColors.onSurface());
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, (int)(12 * d));
        root.addView(title);

        ProgressBar bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(100);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(6 * d)));
        root.addView(bar);
        sProgressBar = bar;

        TextView pct = new TextView(ctx);
        pct.setText("准备中");
        pct.setTextSize(28);
        pct.setTextColor(AppColors.primary());
        pct.setTypeface(null, android.graphics.Typeface.BOLD);
        pct.setGravity(Gravity.CENTER);
        pct.setPadding(0, (int)(10 * d), 0, 0);
        root.addView(pct);
        sProgressPct = pct;

        TextView label = new TextView(ctx);
        label.setText("正在解码音频...");
        label.setTextSize(14);
        label.setTextColor(AppColors.onSurfaceVariant());
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, (int)(4 * d), 0, 0);
        root.addView(label);
        sProgressLabel = label;

        return root;
    }

    private static void themeAlertDialog(android.app.AlertDialog dialog) {
        try {
            android.view.Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(CandyUi.dialogBg(dialog.getContext()));
            }
        } catch (Throwable ignored) {}
        try {
            android.widget.Button pos = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
            if (pos != null) pos.setTextColor(AppColors.accent());
        } catch (Throwable ignored) {}
        try {
            android.widget.Button neg = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) neg.setTextColor(AppColors.text2());
        } catch (Throwable ignored) {}
        try {
            android.widget.Button neu = dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL);
            if (neu != null) neu.setTextColor(AppColors.text3());
        } catch (Throwable ignored) {}
    }
}
