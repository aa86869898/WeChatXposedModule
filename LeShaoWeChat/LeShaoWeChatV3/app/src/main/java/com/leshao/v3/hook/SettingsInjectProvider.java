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

            View personal = findTextView((ViewGroup) decor, "个人资料");
            if (personal == null) personal = findTextView((ViewGroup) decor, "个人信息");
            if (personal == null) personal = findTextView((ViewGroup) decor, "通用");
            if (personal == null) return;

            ViewGroup listParent = walkUpToLinearLayout(personal);
            if (listParent == null) return;

            for (int i = 0; i < listParent.getChildCount(); i++) {
                Object tag = listParent.getChildAt(i).getTag();
                if (ENTRY_TAG.equals(tag)) return;
            }

            float d = activity.getResources().getDisplayMetrics().density;
            View section = buildPluginSection(activity, d);
            section.setTag(ENTRY_TAG);

            listParent.addView(section, 0);
            sInjected.add(id);
            LogWriter.log(TAG, "Plugin section injected in " + listParent.getClass().getSimpleName());
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

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, 0, 0, (int)(16 * d));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
        cardLp.setMargins((int)(16 * d), (int)(12 * d), (int)(16 * d), 0);
        card.setLayoutParams(cardLp);

        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setCornerRadius(10 * d);
        cardBg.setColor(AppColors.card());
        card.setBackground(cardBg);

        TextView header = new TextView(ctx);
        header.setText("插件");
        header.setTextSize(13);
        header.setTextColor(AppColors.text2());
        header.setPadding((int)(16 * d), (int)(14 * d), (int)(16 * d), (int)(8 * d));
        card.addView(header);

        LinearLayout entry = new LinearLayout(ctx);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding((int)(16 * d), (int)(12 * d), (int)(16 * d), (int)(12 * d));
        entry.setClickable(true);
        entry.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

        android.graphics.drawable.GradientDrawable entryBg = new android.graphics.drawable.GradientDrawable();
        entryBg.setCornerRadius(8 * d);
        entryBg.setColor(AppColors.accent());
        entry.setBackground(entryBg);

        entry.setOnClickListener(v -> {
            try { MainActivity.open(activity); }
            catch (Throwable t) { LogWriter.log(TAG, "open: " + t.getMessage()); }
        });

        TextView title = new TextView(ctx);
        title.setText("乐少助手");
        title.setTextSize(15);
        title.setTextColor(AppColors.whiteTextOnAccent());
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        entry.addView(title);

        card.addView(entry);
        return card;
    }
}
