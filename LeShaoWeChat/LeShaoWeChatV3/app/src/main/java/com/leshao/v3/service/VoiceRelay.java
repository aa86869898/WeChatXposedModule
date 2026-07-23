package com.leshao.v3.service;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.leshao.v3.LogWriter;

import java.lang.reflect.Field;
import java.util.Map;

public class VoiceRelay {

    private static final String TAG = "VoiceRelay";
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static volatile int sRetryCount = 0;
    private static volatile long sLastVoiceTime = 0;

    public static void process(String talker, int msgType) {
        if (msgType != com.leshao.v3.model.WeChatMessage.TYPE_VOICE) return;

        sLastVoiceTime = System.currentTimeMillis();
        sRetryCount = 0;

        sMainHandler.postDelayed(() -> tryAutoClick(1), 800);
        sMainHandler.postDelayed(() -> tryAutoClick(2), 1800);
        sMainHandler.postDelayed(() -> tryAutoClick(3), 3500);
    }

    private static void tryAutoClick(int attempt) {
        if (sRetryCount > 0) return;

        try {
            Activity act = findChatActivity();
            if (act == null) {
                LogWriter.log(TAG, "retry#" + attempt + ": no chat activity");
                return;
            }

            View root = act.getWindow().getDecorView();
            RecyclerView rv = findRecyclerView(root);
            if (rv == null) {
                LogWriter.log(TAG, "retry#" + attempt + ": no RecyclerView found");
                return;
            }

            int count = rv.getChildCount();
            if (count == 0) {
                LogWriter.log(TAG, "retry#" + attempt + ": RecyclerView empty");
                return;
            }

            View lastItem = rv.getChildAt(count - 1);
            clickAllChildren(lastItem, 0);

            synchronized (VoiceRelay.class) {
                if (sRetryCount == 0) sRetryCount = attempt;
            }
            LogWriter.log(TAG, "retry#" + attempt + ": clicked last item, childCount="
                + count + " root=" + lastItem.getClass().getSimpleName());
        } catch (Throwable t) {
            LogWriter.log(TAG, "retry#" + attempt + " err: " + t.getMessage());
        }
    }

    private static void clickAllChildren(View view, int depth) {
        if (depth > 20) return;

        view.performClick();

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                clickAllChildren(vg.getChildAt(i), depth + 1);
            }
        }
    }

    private static RecyclerView findRecyclerView(View root) {
        if (root instanceof RecyclerView) return (RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RecyclerView result = findRecyclerView(vg.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Activity findChatActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object at = atCls.getMethod("currentActivityThread").invoke(null);
            Field f = atCls.getDeclaredField("mActivities");
            f.setAccessible(true);
            Map<Object, Object> activities = (Map<Object, Object>) f.get(at);

            for (Object record : activities.values()) {
                Field pausedF = record.getClass().getDeclaredField("paused");
                pausedF.setAccessible(true);
                if (!pausedF.getBoolean(record)) {
                    Field activityF = record.getClass().getDeclaredField("activity");
                    activityF.setAccessible(true);
                    Activity act = (Activity) activityF.get(record);
                    View root = act.getWindow().getDecorView();
                    if (findRecyclerView(root) != null) {
                        return act;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
