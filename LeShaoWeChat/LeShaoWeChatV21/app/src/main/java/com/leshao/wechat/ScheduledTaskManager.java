package com.leshao.wechat;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class ScheduledTaskManager {
    private static Context ctx;
    private static final String ACTION = "com.leshao.wechat.TASK_TRIGGER";
    private static final String TASK_IDS_KEY = "ls_task_ids";
    private static final String TASK_PREFIX = "ls_task_";

    public static void init(Context c) { ctx = c; }

    public static String scheduleTask(long triggerTimeMs, String text, Set<String> targets, long interval, int repeatType) {
        String id = "task_" + System.currentTimeMillis();
        JSONObject t = new JSONObject();
        try {
            t.put("id", id); t.put("triggerTime", triggerTimeMs); t.put("text", text);
            t.put("interval", interval); t.put("repeatType", repeatType);
            JSONArray arr = new JSONArray(); for (String wxid : targets) arr.put(wxid);
            t.put("targets", arr.toString());
            String key = TASK_PREFIX + id;
            ModuleSettings.putStr(key, t.toString());
            // Add to ID list
            String idsStr = ModuleSettings.getStr(TASK_IDS_KEY, "[]");
            JSONArray idsArr = new JSONArray(idsStr);
            idsArr.put(id);
            ModuleSettings.putStr(TASK_IDS_KEY, idsArr.toString());
            Utils.flog("ScheduledTask: 创建任务 " + id + " trigger=" + triggerTimeMs);
        } catch (Throwable e) { Utils.flog("ScheduledTask scheduleTask异常: " + e.getMessage()); }
        return id;
    }

    public static void cancelTask(String id) {
        String key = TASK_PREFIX + id;
        ModuleSettings.putStr(key, "");
        // Remove from ID list
        try {
            String idsStr = ModuleSettings.getStr(TASK_IDS_KEY, "[]");
            JSONArray idsArr = new JSONArray(idsStr);
            JSONArray newArr = new JSONArray();
            for (int i = 0; i < idsArr.length(); i++) {
                String eid = idsArr.optString(i);
                if (!id.equals(eid)) newArr.put(eid);
            }
            ModuleSettings.putStr(TASK_IDS_KEY, newArr.toString());
        } catch (Throwable e) { Utils.flog("ScheduledTask cancelTask异常: " + e.getMessage()); }
    }

    public static List<String> getPendingTasks() {
        List<String> list = new ArrayList<>();
        try {
            String idsStr = ModuleSettings.getStr(TASK_IDS_KEY, "[]");
            JSONArray idsArr = new JSONArray(idsStr);
            for (int i = 0; i < idsArr.length(); i++) {
                String id = idsArr.optString(i);
                if (id == null || id.isEmpty()) continue;
                String v = ModuleSettings.getStr(TASK_PREFIX + id, null);
                if (v != null && !v.isEmpty()) list.add(v);
            }
        } catch (Throwable e) { Utils.flog("ScheduledTask getPendingTasks异常: " + e.getMessage()); }
        return list;
    }

    public static void checkAndExecute() {
        long now = System.currentTimeMillis();
        List<String> tasks = getPendingTasks();
        Utils.flog("ScheduledTask: checkAndExecute 待执行任务数=" + tasks.size());
        List<String> expiredIds = new ArrayList<>();
        for (String t : tasks) {
            try {
                JSONObject j = new JSONObject(t);
                long trigger = j.optLong("triggerTime", 0);
                String id = j.optString("id", "");
                if (trigger > 0 && trigger <= now) {
                    String text = j.optString("text", "");
                    String targetsStr = j.optString("targets", "[]");
                    long interval = j.optLong("interval", 3000);
                    JSONArray arr = new JSONArray(targetsStr);
                    Set<String> targets = new HashSet<>();
                    for (int i = 0; i < arr.length(); i++) targets.add(arr.optString(i));
                    MassMessenger.executeSend(0, text, targets, interval);
                    // Handle repeat
                    long repeatInterval = j.optLong("interval", 0);
                    int repeatType = j.optInt("repeatType", 0);
                    if (repeatType > 0 && repeatInterval > 0) {
                        long nextTrigger = trigger + repeatInterval;
                        j.put("triggerTime", nextTrigger);
                        ModuleSettings.putStr(TASK_PREFIX + id, j.toString());
                        Utils.flog("ScheduledTask: 任务 " + id + " 重复, next=" + nextTrigger);
                    } else {
                        expiredIds.add(id);
                    }
                }
            } catch (Throwable e) { Utils.flog("ScheduledTask checkAndExecute异常: " + e.getMessage()); }
        }
        // Clean expired non-repeating tasks
        for (String eid : expiredIds) cancelTask(eid);
    }
}
