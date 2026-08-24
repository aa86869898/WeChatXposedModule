package com.leshao.v3;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import com.leshao.v3.ContextManager;
import com.leshao.v3.db.VoiceHistoryDbHelper;
import com.leshao.v3.hook.TtsVoiceSender;
import com.leshao.v3.ting.TingMusicModule;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.CandyUi;
import com.leshao.v3.wm.utils.WmPrefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
        for (String className : targets) {
            Class<?> c = findClassIfExists(className, cl);
            if (c == null) continue;
            Method m = findMethodInHierarchy(c, "onActivityResult", int.class, int.class, Intent.class);
            if (m == null) continue;
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
            LogWriter.log(TAG, "onActivityResult hooked on " + c.getSimpleName());
            anyHooked = true;
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
            LogWriter.log(TAG, "onActivityResult err: " + t.getMessage());
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
            showPanel(v);
            return true;
        });
        btnView.setOnTouchListener((v, e) -> {
            if (e.getAction() == 0) v.getParent().requestDisallowInterceptTouchEvent(true);
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
        if (popupWindow != null) {
            if (popupWindow.isShowing()) popupWindow.dismiss();
            removeLayoutListener();
        }

        Context ctx = anchor.getContext();

        int cardBg = AppColors.card();
        int p6 = dp(ctx, 6);
        int p8 = dp(ctx, 8);
        int p10 = dp(ctx, 10);
        int p12 = dp(ctx, 12);

        // 主容器（垂直）
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(cardBg);
        root.setPadding(p8, dp(ctx, 4), p8, p8);

        // 标题栏（横跨，居中）
        TextView titleBar = new TextView(ctx);
        titleBar.setText("乐少音频转语音助手支持在线点歌");
        titleBar.setTextSize(13);
        titleBar.setTextColor(AppColors.text1());
        titleBar.setGravity(Gravity.CENTER);
        titleBar.setPadding(0, dp(ctx, 3), 0, p6);
        GradientDrawable titleBg = new GradientDrawable();
        titleBg.setColor(AppColors.inputBg());
        titleBg.setCornerRadius(p6);
        titleBar.setBackground(titleBg);
        root.addView(titleBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 主体（水平）
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body);

        // 左侧功能列表
        LinearLayout leftList = new LinearLayout(ctx);
        leftList.setOrientation(LinearLayout.VERTICAL);
        body.addView(leftList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 右侧容器
        FrameLayout rightContainer = new FrameLayout(ctx);
        LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(dp(ctx, 300),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rcLp.leftMargin = p8;
        body.addView(rightContainer, rcLp);

        // 子面板
        View audioPanel = createAudioToVoicePanel(ctx);
        View searchPanel = createSearchToVoicePanel(ctx);
        rightContainer.addView(audioPanel);
        rightContainer.addView(searchPanel);
        searchPanel.setVisibility(View.GONE);

        // 左侧按钮
        final TextView audioBtn = makeSideButton(ctx, "高音质转换", "", true);
        final TextView searchBtn = makeSideButton(ctx, "自动点歌", "", false);
        leftList.addView(audioBtn);
        leftList.addView(searchBtn);

        audioBtn.setOnClickListener(v -> switchPanel(audioBtn, searchBtn, audioPanel, searchPanel));
        searchBtn.setOnClickListener(v -> switchPanel(searchBtn, audioBtn, searchPanel, audioPanel));

        // PopupWindow
        popupWindow = new PopupWindow(root, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(0));
        popupWindow.setElevation(dp(ctx, 8));
        popupWindow.setOutsideTouchable(true);
        popupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = Math.max(0, loc[0] - p8);
        int y = Math.max(0, loc[1] - dp(ctx, 340));
        popupWindow.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
        popupWindow.setOnDismissListener(() -> removeLayoutListener());

        sLayoutAnchor = anchor;
        sLayoutListener = () -> {
            if (popupWindow == null || !popupWindow.isShowing()) return;
            int[] newLoc = new int[2];
            anchor.getLocationOnScreen(newLoc);
            int ny = Math.max(0, newLoc[1] - dp(ctx, 340));
            popupWindow.update(newLoc[0] - p8, ny, -1, -1, true);
        };
        anchor.getViewTreeObserver().addOnGlobalLayoutListener(sLayoutListener);
    }

    private static void removeLayoutListener() {
        if (sLayoutListener != null && sLayoutAnchor != null) {
            try { sLayoutAnchor.getViewTreeObserver().removeOnGlobalLayoutListener(sLayoutListener); } catch (Throwable ignored) {}
        }
        sLayoutListener = null;
        sLayoutAnchor = null;
    }

    private static TextView makeSideButton(Context ctx, String title, String sub, boolean selected) {
        TextView btn = new TextView(ctx);
        btn.setText((sub == null || sub.isEmpty()) ? title : title + "\n" + sub);
        btn.setTextSize(12);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(ctx, 4), dp(ctx, 14), dp(ctx, 4), dp(ctx, 14));
        btn.setTextColor(selected ? AppColors.WHITE_TEXT : AppColors.text1());
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? AppColors.accent() : AppColors.inputBg());
        bg.setCornerRadius(dp(ctx, 8));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 6);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static void switchPanel(TextView selectedBtn, TextView otherBtn, View showPanel, View hidePanel) {
        selectedBtn.setTextColor(AppColors.WHITE_TEXT);
        ((GradientDrawable) selectedBtn.getBackground()).setColor(AppColors.accent());
        otherBtn.setTextColor(AppColors.text1());
        ((GradientDrawable) otherBtn.getBackground()).setColor(AppColors.inputBg());
        showPanel.setVisibility(View.VISIBLE);
        hidePanel.setVisibility(View.GONE);
    }

    private static View createAudioToVoicePanel(Context ctx) {
        int text1 = AppColors.text1();
        int text2 = AppColors.text2();
        int accent = AppColors.accent();
        int divider = AppColors.divider();
        int whiteOnAccent = AppColors.WHITE_TEXT;

        int p6 = dp(ctx, 6);
        int p8 = dp(ctx, 8);
        int p10 = dp(ctx, 10);
        int p12 = dp(ctx, 12);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);

        // 文件选择行: [路径文本] [选择]
        LinearLayout fileRow = new LinearLayout(ctx);
        fileRow.setOrientation(LinearLayout.HORIZONTAL);
        fileRow.setPadding(0, 0, 0, p8);

        final TextView pathText = new TextView(ctx);
        pathText.setTextSize(12);
        pathText.setTextColor(text1);
        pathText.setPadding(p8, p8, p8, p8);
        pathText.setBackgroundColor(AppColors.inputBg());
        pathText.setText(sLastPickedPath != null ? sLastPickedPath : "未选择文件");
        pathText.setMaxLines(1);
        pathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams pathLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fileRow.addView(pathText, pathLp);

        TextView browseBtn = new TextView(ctx);
        browseBtn.setText("选择");
        browseBtn.setTextSize(12);
        browseBtn.setTextColor(whiteOnAccent);
        browseBtn.setGravity(Gravity.CENTER);
        browseBtn.        setPadding(dp(ctx, 4), p8, dp(ctx, 4), p8);
        GradientDrawable browseBg = new GradientDrawable();
        browseBg.setColor(accent);
        browseBg.setCornerRadius(p6);
        browseBtn.setBackground(browseBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = p8;
        fileRow.addView(browseBtn, btnLp);
        panel.addView(fileRow);

        // 已选文件名显示
        final TextView fileNameTv = new TextView(ctx);
        fileNameTv.setTextSize(11);
        fileNameTv.setTextColor(text2);
        fileNameTv.setPadding(0, 0, 0, p6);
        fileNameTv.setVisibility(View.GONE);
        panel.addView(fileNameTv);

        // 音频切割 / 历史记录 按钮行
        LinearLayout btnRow = new LinearLayout(ctx);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 0, 0, p8);

        final TextView cutBtn = new TextView(ctx);
        cutBtn.setText("音频切割");
        cutBtn.setTextSize(12);
        cutBtn.setTextColor(accent);
        cutBtn.setGravity(Gravity.CENTER);
        cutBtn.setPadding(dp(ctx, 4), p6, dp(ctx, 4), p6);
        GradientDrawable cutBg = new GradientDrawable();
        cutBg.setStroke(dp(ctx, 1), accent);
        cutBg.setCornerRadius(p6);
        cutBtn.setBackground(cutBg);
        cutBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams cutLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cutLp.rightMargin = p6;
        btnRow.addView(cutBtn, cutLp);

        final TextView historyBtn = new TextView(ctx);
        historyBtn.setText("历史记录");
        historyBtn.setTextSize(12);
        historyBtn.setTextColor(text2);
        historyBtn.setGravity(Gravity.CENTER);
        historyBtn.setPadding(dp(ctx, 4), p6, dp(ctx, 4), p6);
        GradientDrawable hBg = new GradientDrawable();
        hBg.setColor(AppColors.inputBg());
        hBg.setStroke(dp(ctx, 1), divider);
        hBg.setCornerRadius(p6);
        historyBtn.setBackground(hBg);
        LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        histLp.leftMargin = p6;
        btnRow.addView(historyBtn, histLp);
        panel.addView(btnRow);

        // 进度条
        final ProgressBar progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(-1, dp(ctx, 4));
        pbLp.bottomMargin = p8;
        panel.addView(progressBar, pbLp);

        // 进度文字
        final TextView progressText = new TextView(ctx);
        progressText.setTextSize(11);
        progressText.setTextColor(text2);
        progressText.setGravity(Gravity.CENTER);
        progressText.setVisibility(View.GONE);
        progressText.setPadding(0, 0, 0, p6);
        panel.addView(progressText);

        // 转码按钮
        final TextView convertBtn = new TextView(ctx);
        convertBtn.setText("转码");
        convertBtn.setTextSize(13);
        convertBtn.setTextColor(whiteOnAccent);
        convertBtn.setGravity(Gravity.CENTER);
        convertBtn.setPadding(dp(ctx, 4), p8, dp(ctx, 4), p8);
        GradientDrawable cvtBg = new GradientDrawable();
        cvtBg.setColor(accent);
        cvtBg.setCornerRadius(p6);
        convertBtn.setBackground(cvtBg);
        panel.addView(convertBtn);

        // 事件绑定
        browseBtn.setOnClickListener(v -> {
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
                act.startActivityForResult(intent, REQ_PICK_MP3);
            } catch (Throwable t) {
                Toast.makeText(ctx, "打开文件选择器失败", Toast.LENGTH_SHORT).show();
            }
        });

        cutBtn.setOnClickListener(cutV -> {
            String mp3Path = pathText.getText().toString().trim();
            if (mp3Path.isEmpty() || mp3Path.equals("未选择文件")) {
                Toast.makeText(ctx, "请先选择MP3文件", Toast.LENGTH_SHORT).show();
                return;
            }
            showCutDialog(ctx, mp3Path, fileNameTv);
        });

        historyBtn.setOnClickListener(histV -> showHistoryDialog(ctx));

        convertBtn.setOnClickListener(v -> {
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

            final android.app.AlertDialog[] cfgDlgHolder = new android.app.AlertDialog[1];
            android.app.AlertDialog cfgDlg = new android.app.AlertDialog.Builder(ctx)
                .setTitle("音频转语音设置")
                .setView(createConfigView(ctx))
                .setPositiveButton("开始转换", (dialog, which) -> {
                    String splitStr = ((EditText) cfgDlgHolder[0].findViewById(android.R.id.text1)).getText().toString().trim();
                    String durStr = ((EditText) cfgDlgHolder[0].findViewById(android.R.id.text2)).getText().toString().trim();
                    if (splitStr == null) splitStr = "0";
                    if (durStr == null) durStr = "1";
                    int splitSeconds = parseIntSafe(splitStr, 0);
                    int fakeDurationSec = parseIntSafe(durStr, 1);
                    if (fakeDurationSec < 1) fakeDurationSec = 1;
                    if (fakeDurationSec > 60) fakeDurationSec = 60;
                    final int finalSplit = splitSeconds;
                    final int finalFakeMs = fakeDurationSec * 1000;
                    final float cutBegin = sCutBeginSec;
                    final float cutEnd = sCutEndSec;
                    sCutBeginSec = 0;
                    sCutEndSec = 0;

                    new Thread(() -> {
                        transferAndReport(ctx, mp3Path, finalTalker, finalSplit, finalFakeMs, cutBegin, cutEnd);
                    }, "leshao-mp3-send").start();
                })
                .setNegativeButton("取消", (dialog, which) -> {
                    sCutBeginSec = 0;
                    sCutEndSec = 0;
                })
                .create();
            cfgDlgHolder[0] = cfgDlg;
            cfgDlg.show();
            themeAlertDialog(cfgDlg);
        });

        // 文件选择回调更新显示
        final ViewTreeObserver.OnGlobalLayoutListener updateFileName = () -> {
            String name = new java.io.File(pathText.getText().toString().trim()).getName();
            if (!name.isEmpty() && !name.equals("未选择文件")) {
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
        };
        pathText.addTextChangedListener(new android.text.TextWatcher() {
            public void afterTextChanged(android.text.Editable s) { updateFileName.onGlobalLayout(); }
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });
        updateFileName.onGlobalLayout();

        return panel;
    }

    private static View createSearchToVoicePanel(Context ctx) {
        int text1 = AppColors.text1();
        int text2 = AppColors.text2();
        int accent = AppColors.accent();
        int whiteOnAccent = AppColors.WHITE_TEXT;

        int p6 = dp(ctx, 6);
        int p8 = dp(ctx, 8);
        int p10 = dp(ctx, 10);

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);

        // 点歌功能（搜索框上方）
        LinearLayout orderSection = new LinearLayout(ctx);
        orderSection.setOrientation(LinearLayout.VERTICAL);
        orderSection.setPadding(0, 0, 0, p8);

        LinearLayout swRow = new LinearLayout(ctx);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView cardLbl = new TextView(ctx);
        cardLbl.setText("卡片点歌");
        cardLbl.setTextSize(12);
        cardLbl.setTextColor(text1);
        swRow.addView(cardLbl);

        Switch cardSw = CandyUi.newSwitch(ctx);
        cardSw.setChecked(WmPrefs.isCardOrder());
        cardSw.setOnCheckedChangeListener((b, checked) -> WmPrefs.set("card_order", checked));
        LinearLayout.LayoutParams cardSwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardSwLp.leftMargin = dp(ctx, 4);
        cardSwLp.rightMargin = dp(ctx, 12);
        swRow.addView(cardSw, cardSwLp);

        TextView voiceLbl = new TextView(ctx);
        voiceLbl.setText("语音点歌");
        voiceLbl.setTextSize(12);
        voiceLbl.setTextColor(text1);
        swRow.addView(voiceLbl);

        Switch voiceSw = CandyUi.newSwitch(ctx);
        voiceSw.setChecked(WmPrefs.isVoiceOrder());
        voiceSw.setOnCheckedChangeListener((b, checked) -> WmPrefs.set("voice_order", checked));
        LinearLayout.LayoutParams voiceSwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voiceSwLp.leftMargin = dp(ctx, 4);
        swRow.addView(voiceSw, voiceSwLp);

        orderSection.addView(swRow);

        // 点歌记录按钮
        TextView recordBtn = new TextView(ctx);
        recordBtn.setText("点歌记录");
        recordBtn.setTextSize(12);
        recordBtn.setTextColor(AppColors.accent());
        recordBtn.setGravity(Gravity.CENTER);
        recordBtn.setPadding(dp(ctx, 4), p6, dp(ctx, 4), p6);
        GradientDrawable recBg = new GradientDrawable();
        recBg.setStroke(dp(ctx, 1), AppColors.accent());
        recBg.setCornerRadius(p6);
        recordBtn.setBackground(recBg);
        recordBtn.setOnClickListener(v -> Toast.makeText(ctx, "点歌记录功能开发中", Toast.LENGTH_SHORT).show());
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.topMargin = p6;
        orderSection.addView(recordBtn, recLp);

        panel.addView(orderSection);

        // 搜索行
        LinearLayout searchRow = new LinearLayout(ctx);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(0, 0, 0, p8);

        final EditText input = new EditText(ctx);
        input.setHint("搜索歌曲");
        input.setTextColor(text1);
        input.setHintTextColor(text2);
        input.setSingleLine(true);
        input.setBackgroundColor(AppColors.inputBg());
        input.setPadding(p8, p8, p8, p8);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        searchRow.addView(input, inputLp);

        TextView searchBtn = new TextView(ctx);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(12);
        searchBtn.setTextColor(whiteOnAccent);
        searchBtn.setGravity(Gravity.CENTER);
        searchBtn.        setPadding(dp(ctx, 4), p8, dp(ctx, 4), p8);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(accent);
        sbg.setCornerRadius(p6);
        searchBtn.setBackground(sbg);
        LinearLayout.LayoutParams searchBtnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchBtnLp.leftMargin = p8;
        searchRow.addView(searchBtn, searchBtnLp);
        panel.addView(searchRow);

        // 结果列表（高度自适应：空时收起，有内容时向上扩展）
        ScrollView scroll = new ScrollView(ctx);
        final LinearLayout resultList = new LinearLayout(ctx);
        resultList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(resultList);
        final LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, 0);
        panel.addView(scroll, scrollLp);

        searchBtn.setOnClickListener(v -> {
            String kw = input.getText().toString().trim();
            if (kw.isEmpty()) {
                Toast.makeText(ctx, "请输入关键词", Toast.LENGTH_SHORT).show();
                return;
            }
            resultList.removeAllViews();
            TextView tip = new TextView(ctx);
            tip.setText("搜索中...");
            tip.setTextSize(12);
            tip.setTextColor(text2);
            resultList.addView(tip);
            resizeResultScroll(scrollLp, scroll, 1);
            TingMusicModule.searchForPanel(kw, new TingMusicModule.PanelSearchCallback() {
                @Override public void onResult(List<Map<String, String>> songs) {
                    resultList.removeAllViews();
                    if (songs == null || songs.isEmpty()) {
                        TextView t = new TextView(ctx);
                        t.setText("无结果");
                        t.setTextSize(12);
                        t.setTextColor(text2);
                        resultList.addView(t);
                        resizeResultScroll(scrollLp, scroll, 1);
                        return;
                    }
                    for (final Map<String, String> s : songs) {
                        resultList.addView(makeSongRow(ctx, s));
                    }
                    resizeResultScroll(scrollLp, scroll, songs.size());
                }
                @Override public void onFail(String err) {
                    resultList.removeAllViews();
                    TextView t = new TextView(ctx);
                    t.setText("搜索失败: " + err);
                    t.setTextSize(12);
                    t.setTextColor(0xFFFF8888);
                    resultList.addView(t);
                    resizeResultScroll(scrollLp, scroll, 1);
                }
            });
        });

        return panel;
    }

    private static void resizeResultScroll(LinearLayout.LayoutParams lp, ScrollView scroll, int rows) {
        float d = scroll.getContext().getResources().getDisplayMetrics().density;
        int rowH = (int) (56 * d);
        int maxH = (int) (330 * d);
        int h = rows <= 0 ? 0 : Math.min(rows * rowH, maxH);
        lp.height = h;
        scroll.setLayoutParams(lp);
    }

    private static View makeSongRow(Context ctx, final Map<String, String> s) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int)(4*d), (int)(8*d), (int)(4*d), (int)(8*d));

        ImageView cover = new ImageView(ctx);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(0x22000000);
        cbg.setCornerRadius(8);
        cover.setBackground(cbg);
        int coverSize = (int)(38*d);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(coverSize, coverSize);
        row.addView(cover, cp);

        LinearLayout mid = new LinearLayout(ctx);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = (int)(10*d);
        row.addView(mid, mp);

        TextView name = new TextView(ctx);
        name.setText(s.get("songName") == null ? "" : s.get("songName"));
        name.setTextSize(14);
        name.setTextColor(AppColors.text1());
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(name);

        TextView singerTv = new TextView(ctx);
        singerTv.setText(s.get("singer") == null ? "" : s.get("singer"));
        singerTv.setTextSize(12);
        singerTv.setTextColor(AppColors.text3());
        singerTv.setSingleLine(true);
        singerTv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mid.addView(singerTv);

        TextView dur = new TextView(ctx);
        int durSec = parseIntSafe(s.get("duration"), 0);
        dur.setText(durSec > 0 ? formatSec(durSec) : "");
        dur.setTextSize(12);
        dur.setTextColor(AppColors.text3());
        row.addView(dur);

        row.setOnClickListener(v -> {
            TingMusicModule.sendSongForPanel(
                    s.get("songName"), s.get("singer"), s.get("dataUrl"),
                    s.get("appid"), s.get("webUrl"), s.get("coverUrl"),
                    s.get("mid"), s.get("lyric"));
            Toast.makeText(ctx, "已发送: " + s.get("songName"), Toast.LENGTH_SHORT).show();
            if (popupWindow != null) popupWindow.dismiss();
        });

        String coverUrl = s.get("coverUrl");
        if (coverUrl != null && !coverUrl.isEmpty()) {
            loadCover(ctx, coverUrl, cover, coverSize);
        }
        return row;
    }

    private static void loadCover(final Context ctx, final String url, final ImageView view, final int size) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
                conn.connect();
                is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                if (bmp == null) return;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, size, size, true);
                if (scaled != bmp) bmp.recycle();
                final Bitmap fBmp = scaled;
                new Handler(Looper.getMainLooper()).post(() -> {
                    try { view.setImageBitmap(fBmp); } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {
            } finally {
                if (is != null) { try { is.close(); } catch (Throwable ignored) {} }
                if (conn != null) conn.disconnect();
            }
        }, "cflp-cover").start();
    }

    private static String formatSec(float secs) {
        int total = (int) secs;
        return (total / 60) + ":" + String.format(java.util.Locale.US, "%02d", total % 60);
    }

    private static LinearLayout createConfigView(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int p16 = (int)(16 * d);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(p16, p16, p16, 0);

        TextView splitLabel = new TextView(ctx);
        splitLabel.setText("切割时长(秒, 0=不切割)");
        splitLabel.setTextSize(14);
        splitLabel.setTextColor(AppColors.text1());
        layout.addView(splitLabel);

        EditText splitInput = new EditText(ctx);
        splitInput.setId(android.R.id.text1);
        splitInput.setText("0");
        splitInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        splitInput.setTextColor(AppColors.text1());
        splitInput.setBackgroundColor(AppColors.inputBg());
        splitInput.setPadding(p16, (int)(10*d), p16, (int)(10*d));
        layout.addView(splitInput);

        View space = new View(ctx);
        space.setLayoutParams(new LinearLayout.LayoutParams(-1, (int)(12*d)));
        layout.addView(space);

        TextView durLabel = new TextView(ctx);
        durLabel.setText("误报时长(秒, 1-60)");
        durLabel.setTextSize(14);
        durLabel.setTextColor(AppColors.text1());
        layout.addView(durLabel);

        EditText durInput = new EditText(ctx);
        durInput.setId(android.R.id.text2);
        durInput.setText("1");
        durInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        durInput.setTextColor(AppColors.text1());
        durInput.setBackgroundColor(AppColors.inputBg());
        durInput.setPadding(p16, (int)(10*d), p16, (int)(10*d));
        layout.addView(durInput);

        return layout;
    }

    private static void transferAndReport(Context ctx, String mp3Path, String talker,
            int splitSeconds, int fakeDurationMs, float cutBeginSec, float cutEndSec) {
        final android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        final float d = ctx.getResources().getDisplayMetrics().density;

        final android.widget.ProgressBar[] barHolder = new android.widget.ProgressBar[1];
        final TextView[] pctHolder = new TextView[1];
        final TextView[] labelHolder = new TextView[1];
        final android.app.AlertDialog[] dlgHolder = new android.app.AlertDialog[1];

        h.post(() -> {
            try {
                LinearLayout pv = createProgressView(ctx, d);
                barHolder[0] = (android.widget.ProgressBar) pv.getChildAt(0);
                pctHolder[0] = (TextView) pv.getChildAt(1);
                labelHolder[0] = (TextView) pv.getChildAt(2);

                dlgHolder[0] = new android.app.AlertDialog.Builder(ctx)
                    .setView(pv)
                    .setCancelable(false)
                    .create();
                dlgHolder[0].show();
                themeAlertDialog(dlgHolder[0]);
            } catch (Throwable ignored) {}
        });

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
                                android.widget.ProgressBar bar = barHolder[0];
                                TextView pct = pctHolder[0];
                                TextView label = labelHolder[0];
                                if (bar == null) return;
                                if (total == 100) {
                                    bar.setIndeterminate(false);
                                    bar.setMax(100);
                                    bar.setProgress(Math.min(current, 99));
                                    pct.setText("正在转码 " + Math.min(current, 99) + "%");
                                    pct.setVisibility(View.VISIBLE);
                                    label.setText("");
                                } else if (total <= 1) {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(bar.getMax());
                                    pct.setText("转码完成 即将发送");
                                    pct.setVisibility(View.VISIBLE);
                                    label.setText("");
                                } else {
                                    bar.setIndeterminate(false);
                                    bar.setMax(total);
                                    bar.setProgress(current);
                                    int percent = total > 0 ? current * 100 / total : 0;
                                    pct.setText(percent + "%");
                                    pct.setVisibility(View.VISIBLE);
                                    label.setText("发送中 (" + current + "/" + total + "段)");
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
                try {
                    android.app.AlertDialog dlg = dlgHolder[0];
                    if (dlg != null) dlg.dismiss();
                } catch (Throwable ignored) {}
                Toast.makeText(ctx, finalOk ? "语音已发送" : "发送失败", Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable t) {
            h.post(() -> {
                try {
                    android.app.AlertDialog dlg = dlgHolder[0];
                    if (dlg != null) dlg.dismiss();
                } catch (Throwable ignored) {}
                Toast.makeText(ctx, "转换失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    private static String getTalker(Context ctx) {
        // Primary: ChatFooter.d (confirmed: public String, holds userName)
        if (sLastChatFooter != null) {
            try {
                Field f = findField(sLastChatFooter.getClass(), "d");
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(sLastChatFooter);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s != null && !s.isEmpty() && !s.equals("not_set")) {
                            LogWriter.log(TAG, "talker from ChatFooter.d: " + s);
                            return s;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Fallback: ChattingUI.onResume cache
        if (sCurrentTalker != null && !sCurrentTalker.isEmpty()) {
            return sCurrentTalker;
        }

        // Fallback: scan Activity fields
        return getTalkerFromActivity(ctx);
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
                    if (s != null && !s.isEmpty() && !s.equals("not_set")) {
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
        startInput.setBackgroundColor(AppColors.inputBg());
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
        endInput.setBackgroundColor(AppColors.inputBg());
        endInput.setPadding(p12, p8, p12, p8);
        root.addView(endInput);

        View gap2 = new View(ctx);
        gap2.setLayoutParams(new LinearLayout.LayoutParams(-1, p12));
        root.addView(gap2);

        // 试听滑块
        final SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(totalInt);
        seekBar.setProgress(0);
        root.addView(seekBar);

        // 设为起点 / 设为终点 按钮行
        LinearLayout markRow = new LinearLayout(ctx);
        markRow.setOrientation(LinearLayout.HORIZONTAL);
        markRow.setPadding(0, p8, 0, 0);

        final Button markStartBtn = new Button(ctx);
        markStartBtn.setText("设为起点");
        markStartBtn.setTextSize(11);
        markStartBtn.setAllCaps(false);
        markStartBtn.setTextColor(text2);
        GradientDrawable msBg = new GradientDrawable();
        msBg.setStroke((int) d, divider);
        msBg.setCornerRadius((int)(4 * d));
        markStartBtn.setBackground(msBg);
        markStartBtn.setPadding(p8, p8, p8, p8);
        LinearLayout.LayoutParams msLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        msLp.rightMargin = (int)(4 * d);
        markRow.addView(markStartBtn, msLp);

        final Button markEndBtn = new Button(ctx);
        markEndBtn.setText("设为终点");
        markEndBtn.setTextSize(11);
        markEndBtn.setAllCaps(false);
        markEndBtn.setTextColor(text2);
        GradientDrawable meBg = new GradientDrawable();
        meBg.setStroke((int) d, divider);
        meBg.setCornerRadius((int)(4 * d));
        markEndBtn.setBackground(meBg);
        markEndBtn.setPadding(p8, p8, p8, p8);
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
        final Button playBtn = new Button(ctx);
        playBtn.setText("播放");
        playBtn.setTextSize(12);
        playBtn.setAllCaps(false);
        playBtn.setTextColor(AppColors.WHITE_TEXT);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setColor(accent);
        playBg.setCornerRadius((int)(6 * d));
        playBtn.setBackground(playBg);
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
                        seekBar.setProgress(pos);
                        seekTime.setText(formatSec(pos) + " / " + formatSec(totalSecs));
                        h.postDelayed(this, 200);
                    } catch (Throwable ignored) {}
                }
            }
        };

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    seekTime.setText(formatSec(progress) + " / " + formatSec(totalSecs));
                    try {
                        if (playerHolder[0] != null) {
                            playerHolder[0].seekTo(progress * 1000);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
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
                    transferAndReport(ctx, mp3Path, fTalker, 0, 1000, fBegin, fEnd);
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

            for (final VoiceHistoryDbHelper.VoiceHistoryItem item : items) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, p8, 0, p8);
                row.setGravity(Gravity.CENTER_VERTICAL);

                LinearLayout info = new LinearLayout(ctx);
                info.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

                boolean fileExists = new java.io.File(item.filePath).exists();

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
                    TextView reuseBtn = new TextView(ctx);
                    reuseBtn.setText("发送");
                    reuseBtn.setTextSize(11);
                    reuseBtn.setTextColor(accent);
                    reuseBtn.setPadding(p8, p8, p8, p8);
                    reuseBtn.setOnClickListener(reuseV -> {
                        dismissHistoryDialog();
                        String talker = item.talker;
                        final String historyFilePath = item.filePath;
                        sCutBeginSec = 0;
                        sCutEndSec = 0;
                        final android.app.AlertDialog[] cfgDlgHolder = new android.app.AlertDialog[1];
                        android.app.AlertDialog historyCfgDlg = new android.app.AlertDialog.Builder(ctx)
                            .setTitle("音频转语音设置")
                            .setView(createConfigView(ctx))
                            .setPositiveButton("开始转换", (dlg, which) -> {
                                String splitStr = ((EditText) cfgDlgHolder[0].findViewById(android.R.id.text1)).getText().toString().trim();
                                String durStr = ((EditText) cfgDlgHolder[0].findViewById(android.R.id.text2)).getText().toString().trim();
                                if (splitStr == null) splitStr = "0";
                                if (durStr == null) durStr = "1";
                                final int splitSeconds = parseIntSafe(splitStr, 0);
                                int rawFakeMs = parseIntSafe(durStr, 1) * 1000;
                                final int fakeMs = Math.max(1000, Math.min(60000, rawFakeMs));
                                new Thread(() -> transferAndReport(ctx, historyFilePath, talker, splitSeconds, fakeMs, 0, 0), "leshao-mp3-send").start();
                            })
                            .setNegativeButton("取消", null)
                            .create();
                        cfgDlgHolder[0] = historyCfgDlg;
                        historyCfgDlg.show();
                        themeAlertDialog(historyCfgDlg);
                    });
                    row.addView(reuseBtn);
                }

                TextView delBtn = new TextView(ctx);
                delBtn.setText("删除");
                delBtn.setTextSize(11);
                delBtn.setTextColor(AppColors.text3());
                delBtn.setPadding(p8, p8, p8, p8);
                delBtn.setOnClickListener(delV -> {
                    db.deleteById(item.id);
                    dismissHistoryDialog();
                    if (popupWindow != null) popupWindow.dismiss();
                    showHistoryDialog(ctx);
                });
                row.addView(delBtn);

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
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清空历史");
            clearBtn.setTextSize(13);
            clearBtn.setTextColor(AppColors.text3());
            clearBtn.setGravity(Gravity.CENTER);
            clearBtn.setPadding(0, p8, 0, p8);
            clearBtn.setOnClickListener(clearV -> {
                db.clearAll();
                dismissHistoryDialog();
                if (popupWindow != null) popupWindow.dismiss();
                showHistoryDialog(ctx);
            });
            root.addView(clearBtn);
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle("历史记录")
            .setView(scroll)
            .setPositiveButton("关闭", null)
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

    private static LinearLayout createProgressView(Context ctx, float d) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding((int)(16 * d), (int)(10 * d), (int)(16 * d), (int)(10 * d));
        root.setMinimumWidth((int)(240 * d));

        android.widget.ProgressBar bar = new android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        int barH = (int)(4 * d);
        bar.setLayoutParams(new LinearLayout.LayoutParams(-1, barH));
        root.addView(bar);

        TextView pct = new TextView(ctx);
        pct.setTextSize(24);
        pct.setTextColor(AppColors.accent());
        pct.setTypeface(null, android.graphics.Typeface.BOLD);
        pct.setGravity(Gravity.CENTER);
        pct.setPadding(0, (int)(6 * d), 0, 0);
        root.addView(pct);

        TextView label = new TextView(ctx);
        label.setText("正在解码音频...");
        label.setTextSize(14);
        label.setTextColor(AppColors.text2());
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, (int)(4 * d), 0, 0);
        root.addView(label);

        return root;
    }

    private static void themeAlertDialog(android.app.AlertDialog dialog) {
        try {
            android.view.Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(AppColors.card()));
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
