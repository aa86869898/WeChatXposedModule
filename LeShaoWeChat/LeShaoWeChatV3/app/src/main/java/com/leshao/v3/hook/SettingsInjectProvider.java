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
    private static final String ENTRY_TAG = "leshao_v3_cp_entry";

    private static volatile Application sWxApp;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Set<Integer> sInjected = new HashSet<>();

    // ================================================================
    // ContentProvider lifecycle (module's own process)
    // ================================================================

    @Override
    public boolean onCreate() {
        Context ctx = getContext();
        if (ctx != null) {
            LogWriter.log(TAG, "ContentProvider onCreate in own process");
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String s, String[] sa) {
        return 0;
    }

    // ================================================================
    // Static init for WeChat process (called from handleLoadPackage)
    // ================================================================

    public static void injectIntoWeChat(Application app) {
        if (sWxApp != null) return;
        sWxApp = app;

        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                scheduleInject(activity);
            }

            @Override
            public void onActivityStarted(Activity activity) {}

            @Override
            public void onActivityResumed(Activity activity) {
                scheduleInject(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                sInjected.remove(System.identityHashCode(activity));
            }
        });

        LogWriter.log(TAG, "INIT: ActivityLifecycleCallbacks registered");
    }

    // ================================================================
    // View injection logic
    // ================================================================

    private static void scheduleInject(Activity activity) {
        sHandler.postDelayed(() -> tryInject(activity), 200);
    }

    private static void tryInject(Activity activity) {
        int id = System.identityHashCode(activity);
        if (sInjected.contains(id)) return;

        try {
            View decor = activity.getWindow().getDecorView();
            View searchBox = findSearchBoxByClass(decor);
            if (searchBox == null) {
                searchBox = findSearchBoxByText(decor);
            }
            if (searchBox == null) return;

            ViewGroup parent = (ViewGroup) searchBox.getParent();
            if (parent == null) return;

            for (int i = 0; i < parent.getChildCount(); i++) {
                Object tag = parent.getChildAt(i).getTag();
                if (ENTRY_TAG.equals(tag) || "leshao_v3_entry".equals(tag)) return;
            }

            int insertPos = parent.indexOfChild(searchBox) + 1;
            View entry = buildEntry(activity);
            entry.setTag(ENTRY_TAG);

            parent.addView(entry, insertPos);
            sInjected.add(id);
            LogWriter.log(TAG, "Injected at pos " + insertPos + " in " + parent.getClass().getSimpleName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "tryInject err: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    // ================================================================
    // Find search box: SettingAdditionHeaderSearch
    // ================================================================

    /**
     * Strategy A: look for ViewGroup whose class name contains "Search" or "Header"
     * and has a child text "搜索".
     * SettingAdditionHeaderSearch typically has class name matching this pattern.
     */
    private static View findSearchBoxByClass(View v) {
        if (!(v instanceof ViewGroup)) return null;
        ViewGroup vg = (ViewGroup) v;
        if (hasChildText(vg, "搜索")) {
            String cls = vg.getClass().getName().toLowerCase();
            if (cls.contains("search") || cls.contains("header")) {
                return vg;
            }
        }
        for (int i = 0; i < vg.getChildCount(); i++) {
            View found = findSearchBoxByClass(vg.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Strategy B (fallback): find any ViewGroup with child text "搜索",
     * walk up to find the best container.
     */
    private static View findSearchBoxByText(View root) {
        View textView = findTextNode(root, "搜索");
        if (textView == null) return null;
        return walkUpToSearchContainer(textView);
    }

    private static View findTextNode(View v, String search) {
        if (v instanceof TextView && ((TextView) v).getText().toString().contains(search)) {
            return v;
        }
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View found = findTextNode(((ViewGroup) v).getChildAt(i), search);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View walkUpToSearchContainer(View v) {
        // Walk up to the outermost ViewGroup that still has "搜索" in its subtree
        // SettingAdditionHeaderSearch is the top-level search box container
        View candidate = v;
        ViewGroup parent = (ViewGroup) v.getParent();
        while (parent != null) {
            String cls = parent.getClass().getName().toLowerCase();
            if (cls.contains("search") || cls.contains("setting")) {
                candidate = parent;
            } else if (parent.getChildCount() <= 3 && hasChildText(parent, "搜索")) {
                candidate = parent;
            }
            parent = (ViewGroup) parent.getParent();
        }
        return candidate;
    }

    private static boolean hasChildText(ViewGroup vg, String search) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().contains(search)) {
                return true;
            }
            if (child instanceof ViewGroup && hasChildText((ViewGroup) child, search)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================
    // Entry card
    // ================================================================

    private static View buildEntry(Activity activity) {
        float d = activity.getResources().getDisplayMetrics().density;
        int p16 = (int) (16 * d);
        int p12 = (int) (12 * d);

        LinearLayout entry = new LinearLayout(activity);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(p16, p12, p16, p12);
        entry.setClickable(true);

        android.graphics.drawable.GradientDrawable bg =
            new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{AppColors.accent2(), AppColors.accent()});
        entry.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(p16, p12, p16, 0);
        entry.setLayoutParams(lp);

        entry.setOnClickListener(v -> {
            try { MainActivity.open(activity); }
            catch (Throwable t) {
                LogWriter.log(TAG, "open fail: " + t.getMessage());
            }
        });

        TextView tv = new TextView(activity);
        tv.setText("乐少助手");
        tv.setTextSize(15);
        tv.setTextColor(AppColors.whiteTextOnAccent());
        entry.addView(tv);

        return entry;
    }
}
