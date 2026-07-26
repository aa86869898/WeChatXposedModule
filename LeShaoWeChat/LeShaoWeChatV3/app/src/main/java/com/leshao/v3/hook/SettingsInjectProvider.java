package com.leshao.v3.hook;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leshao.v3.BuildConfig;
import com.leshao.v3.LogWriter;
import com.leshao.v3.ui.AppColors;
import com.leshao.v3.ui.MainActivity;

import java.util.HashSet;
import java.util.Set;

public class SettingsInjectProvider extends ContentProvider {

    private static final String TAG = "SettingsInject";
    private static final String ENTRY_TAG = "leshao_v3_section";

    private static volatile Application sWxApp;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Set<Integer> sInjected = new HashSet<>();

    @Override
    public boolean onCreate() {
        try {
            Context ctx = getContext();
            if (ctx != null) LogWriter.log(TAG, "ContentProvider onCreate");
        } catch (Throwable ignored) {}
        return true;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] sa, String so) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] sa) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] sa) { return 0; }

    public static void injectIntoWeChat(Application app) {
        try {
            if (sWxApp != null) return;
            sWxApp = app;

            app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivityResumed(Activity a) { scheduleInject(a); }
                @Override public void onActivityPaused(Activity a) {}
                @Override public void onActivityStopped(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                @Override public void onActivityDestroyed(Activity a) {
                    try { sInjected.remove(System.identityHashCode(a)); } catch (Throwable ignored) {}
                }
            });
            LogWriter.log(TAG, "INIT: registered");
        } catch (Throwable t) {
            LogWriter.log(TAG, "INIT err: " + t.getMessage());
        }
    }

    private static void scheduleInject(Activity activity) {
        try {
            sHandler.postDelayed(() -> tryInject(activity), 300);
        } catch (Throwable ignored) {}
    }

    private static void tryInject(Activity activity) {
        int id = System.identityHashCode(activity);
        if (sInjected.contains(id)) return;
        try {
            if (activity.getWindow() == null) return;
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;

            View target = findTextView((ViewGroup) decor, "账号");
            if (target == null) target = findTextView((ViewGroup) decor, "个人资料");
            if (target == null) target = findTextView((ViewGroup) decor, "通用");
            if (target == null) return;

            ViewGroup listParent = walkUpToLinearLayout(target);
            if (listParent == null) return;

            int insertPos = findInsertPosition(listParent, target);

            for (int i = 0; i < listParent.getChildCount(); i++) {
                Object tag = listParent.getChildAt(i).getTag();
                if (ENTRY_TAG.equals(tag)) return;
            }

            float d = activity.getResources().getDisplayMetrics().density;
            View section = buildPluginSection(activity, d);
            section.setTag(ENTRY_TAG);

            listParent.addView(section, insertPos);
            sInjected.add(id);
            LogWriter.log(TAG, "Plugin section injected at pos " + insertPos
                + " in " + listParent.getClass().getSimpleName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static ViewGroup walkUpToLinearLayout(View v) {
        ViewGroup p = v.getParent() instanceof ViewGroup ? (ViewGroup) v.getParent() : null;
        while (p != null) {
            if (p instanceof LinearLayout && p.getChildCount() >= 2) return p;
            if (p.getParent() instanceof ViewGroup) p = (ViewGroup) p.getParent();
            else break;
        }
        return null;
    }

    private static int findInsertPosition(ViewGroup parent, View target) {
        View v = target;
        while (v != null && v.getParent() != parent && v.getParent() instanceof ViewGroup) {
            v = (ViewGroup) v.getParent();
        }
        if (v != null && v.getParent() == parent) {
            return parent.indexOfChild(v);
        }
        return 0;
    }

    private static View findTextView(ViewGroup vg, String search) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString();
                if (text != null && text.equals(search)) return child;
            }
            if (child instanceof ViewGroup) {
                View found = findTextView((ViewGroup) child, search);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View buildPluginSection(Activity activity, float d) {
        Context ctx = activity;

        // 外层容器，透明无背景，紧贴设置列表风格
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding((int)(16 * d), (int)(4 * d), (int)(16 * d), (int)(4 * d));
        container.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        container.setClickable(true);
        container.setFocusable(true);
        container.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        // 标题行: 插件  +  版本号(右)
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, (int)(2 * d));
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        TextView pluginLabel = new TextView(ctx);
        pluginLabel.setText("插件");
        pluginLabel.setTextSize(13);
        pluginLabel.setTextColor(AppColors.text2());
        headerRow.addView(pluginLabel);

        // 占位撑开
        View spacer = new View(ctx);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1.0f));
        headerRow.addView(spacer);

        String ver = "1.2.106";
        try { ver = com.leshao.v3.BuildConfig.VERSION_NAME; } catch (Throwable ignored) {}
        TextView verView = new TextView(ctx);
        verView.setText(ver);
        verView.setTextSize(11);
        verView.setTextColor(AppColors.text2());
        headerRow.addView(verView);
        container.addView(headerRow);

        // 入口行: 七彩霓虹粗体 "乐少助手"
        TextView title = new TextView(ctx) {
            @Override
            protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                if (w > 0) {
                    getPaint().setShader(new android.graphics.LinearGradient(
                        0, 0, w, 0,
                        new int[]{0xFFFF6BD6, 0xFFC44DFF, 0xFF6B9DFF,
                                  0xFF4DFFC4, 0xFFFFC44D, 0xFFFF4D6B, 0xFFFF6B9D},
                        null, android.graphics.Shader.TileMode.CLAMP));
                }
            }
        };
        title.setText("乐少助手");
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, 0);
        title.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        container.addView(title);

        container.setOnClickListener(v -> {
            try { MainActivity.open(activity); }
            catch (Throwable t) { LogWriter.log(TAG, "open: " + t.getMessage()); }
        });

        return container;
    }
}
