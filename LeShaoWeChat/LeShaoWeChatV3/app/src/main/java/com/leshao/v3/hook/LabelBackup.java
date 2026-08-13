package com.leshao.v3.hook;

import android.content.Context;
import android.os.Environment;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.model.LabelInfo;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LabelBackup {

    private static final String TAG = "LabelBackup";
    private static final String BACKUP_DIR = "WxGroupBackup";
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static ScheduledExecutorService sScheduler;

    public static String exportToJson(Context ctx) {
        try {
            List<LabelInfo> labels = ChatGroupHook.getAllLabelsWithTemp();
            File dir = getBackupDir();
            String filename = "labels_" + sdf.format(new Date()) + ".json";
            File file = new File(dir, filename);
            StringBuilder json = new StringBuilder("[\n");
            for (int i = 0; i < labels.size(); i++) {
                LabelInfo l = labels.get(i);
                json.append("  {\"labelId\":").append(l.labelId).append(",\"labelName\":\"")
                    .append(escape(l.labelName)).append("\",\"labelPYFull\":\"").append(escape(l.labelPYFull))
                    .append("\",\"labelPYShort\":\"").append(escape(l.labelPYShort))
                    .append("\",\"createTime\":").append(l.createTime).append(",\"isTemporary\":").append(l.isTemporary)
                    .append(",\"contacts\":[");
                for (int j = 0; j < l.contacts.size(); j++) { if (j > 0) json.append(","); json.append("\"").append(escape(l.contacts.get(j))).append("\""); }
                json.append("]}");
                if (i < labels.size() - 1) json.append(",");
                json.append("\n");
            }
            json.append("]\n");
            try (FileWriter fw = new FileWriter(file)) { fw.write(json.toString()); }
            GroupConfigManager.setLastBackupPath(file.getAbsolutePath());
            GroupConfigManager.setLastBackupTime(System.currentTimeMillis());
            LogWriter.log(TAG, "导出成功: " + file.getAbsolutePath() + " (" + labels.size() + " 标签)");
            return file.getAbsolutePath();
        } catch (Throwable e) { LogWriter.log(TAG, "导出失败: " + e.getMessage()); return null; }
    }

    public static int importFromJson(String filePath, boolean overwrite) {
        int count = 0;
        try {
            File file = new File(filePath);
            if (!file.exists()) return 0;
            String content = readFile(file);
            for (String part : content.split("\\},\\s*\\{")) {
                String name = extractJsonValue(part, "labelName");
                if (name == null || name.isEmpty()) continue;
                LabelInfo exist = ChatGroupHook.getLabelByName(name);
                int importedLabelId;
                if (exist != null) {
                    if (!overwrite) continue;
                    ChatGroupHook.renameLabel(String.valueOf(exist.labelId), name);
                    importedLabelId = exist.labelId;
                } else {
                    LabelInfo created = ChatGroupHook.createLabel(name);
                    if (created == null) continue;
                    importedLabelId = created.labelId;
                }
                List<String> contacts = extractJsonArray(part, "contacts");
                if (contacts != null && !contacts.isEmpty()) {
                    for (String c : contacts) {
                        if (c != null && !c.isEmpty()) ChatGroupHook.addLabelToContact(c, importedLabelId);
                    }
                }
                count++;
            }
            LogWriter.log(TAG, "导入完成: " + count + " 标签 from " + filePath);
        } catch (Throwable e) { LogWriter.log(TAG, "导入失败: " + e.getMessage()); }
        return count;
    }

    public static void scheduleAutoBackup(Context ctx) {
        cancelScheduler();
        if (!GroupConfigManager.isAutoBackupEnabled()) return;
        int intervalHours = GroupConfigManager.getBackupIntervalHours();
        if (intervalHours <= 0) intervalHours = 24;
        sScheduler = Executors.newSingleThreadScheduledExecutor();
        sScheduler.scheduleAtFixedRate(() -> {
            try {
                String path = exportToJson(ctx);
                if (path != null) LogWriter.log(TAG, "自动备份完成: " + path);
            } catch (Throwable e) { LogWriter.log(TAG, "自动备份出错: " + e.getMessage()); }
        }, 1, intervalHours, TimeUnit.HOURS);
        LogWriter.log(TAG, "自动备份已调度，间隔 " + intervalHours + " 小时");
    }

    public static void backupIfNeeded(Context ctx) {
        if (!GroupConfigManager.isAutoBackupEnabled()) return;
        long lastBackup = GroupConfigManager.getLastBackupTime();
        int intervalHours = GroupConfigManager.getBackupIntervalHours();
        if (intervalHours <= 0) intervalHours = 24;
        long elapsedMs = System.currentTimeMillis() - lastBackup;
        if (elapsedMs < intervalHours * 3600000L) return;
        try {
            String path = exportToJson(ctx);
            if (path != null) LogWriter.log(TAG, "按需备份完成: " + path);
        } catch (Throwable e) { LogWriter.log(TAG, "按需备份出错: " + e.getMessage()); }
    }

    private static void cancelScheduler() {
        if (sScheduler != null) { try { sScheduler.shutdownNow(); } catch (Throwable ignored) {} sScheduler = null; }
    }

    public static List<File> listBackups() {
        List<File> files = new ArrayList<>();
        File dir = getBackupDir();
        File[] list = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (list != null) { Arrays.sort(list, (a,b) -> Long.compare(b.lastModified(), a.lastModified())); files.addAll(Arrays.asList(list)); }
        return files;
    }

    public static void deleteBackup(String path) { try { new File(path).delete(); } catch (Throwable ignored) {} }

    private static File getBackupDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), BACKUP_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String escape(String s) { if (s == null) return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t"); }

    private static String readFile(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) { String line; while ((line = br.readLine()) != null) sb.append(line).append("\n"); }
        return sb.toString();
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) { search = "\"" + key + "\":"; idx = json.indexOf(search); if (idx < 0) return null; int numEnd = json.indexOf(",", idx); if (numEnd < 0) numEnd = json.indexOf("}", idx); return json.substring(idx + search.length(), numEnd).trim(); }
        int start = idx + search.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end).replace("\\\"","\"").replace("\\n","\n");
    }

    private static List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        try {
            String search = "\"" + key + "\":[";
            int idx = json.indexOf(search);
            if (idx < 0) return result;
            int start = idx + search.length();
            int depth = 1;
            int pos = start;
            while (pos < json.length() && depth > 0) {
                char c = json.charAt(pos);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                pos++;
            }
            String arr = json.substring(start, pos - 1).trim();
            if (!arr.isEmpty()) {
                int i = 0;
                while (i < arr.length()) {
                    if (arr.charAt(i) == ',') { i++; continue; }
                    if (arr.charAt(i) == '"') {
                        int j = arr.indexOf("\"", i + 1);
                        if (j < 0) break;
                        String val = arr.substring(i + 1, j);
                        result.add(val.replace("\\\"","\""));
                        i = j + 1;
                    } else i++;
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }
}
