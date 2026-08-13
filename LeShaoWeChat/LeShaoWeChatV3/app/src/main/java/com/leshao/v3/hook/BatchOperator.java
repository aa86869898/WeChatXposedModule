package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.model.LabelInfo;

import java.util.*;

public class BatchOperator {

    private static final String TAG = "BatchOperator";

    public static List<LabelInfo> batchCreate(List<String> names) {
        List<LabelInfo> created = new ArrayList<>();
        for (String name : names) { LabelInfo l = ChatGroupHook.createLabel(name.trim()); if (l != null) created.add(l); }
        LogWriter.log(TAG, "批量创建 " + created.size() + "/" + names.size() + " 标签");
        return created;
    }

    public static int batchDelete(List<String> labelIds) { int n = 0; for (String id : labelIds) if (ChatGroupHook.deleteLabel(id)) n++; return n; }

    public static boolean mergeLabels(int sourceId, int targetId) {
        try {
            List<String> contacts = ChatGroupHook.getContactsByLabelId(sourceId);
            int moved = 0, failed = 0;
            for (String u : contacts) {
                if (ChatGroupHook.addLabelToContact(u, targetId)) moved++;
                else failed++;
            }
            if (failed > 0) {
                LogWriter.log(TAG, "合并标签 " + sourceId + "->" + targetId + " 部分失败: " + moved + "/" + contacts.size() + " 成功");
                if (moved == 0) return false;
            }
            ChatGroupHook.deleteLabel(String.valueOf(sourceId));
            LogWriter.log(TAG, "合并标签 " + sourceId + "->" + targetId + " (" + moved + " 联系人)");
            return true;
        } catch (Throwable e) { LogWriter.log(TAG, "merge: " + e.getMessage()); return false; }
    }

    public static int clearLabelContacts(int labelId) {
        List<String> contacts = ChatGroupHook.getContactsByLabelId(labelId);
        int n = 0;
        for (String u : contacts) if (ChatGroupHook.removeLabelFromContact(u, labelId)) n++;
        LogWriter.log(TAG, "清空标签 " + labelId + " -> " + n + " 联系人");
        return n;
    }

    public static Map<String, Object> getStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        List<LabelInfo> all = ChatGroupHook.getAllLabelsWithTemp();
        stats.put("totalLabels", all.size());
        int tempCount = 0, totalContacts = 0, maxContacts = 0;
        String maxLabel = "";
        for (LabelInfo l : all) {
            if (l.isTemporary) tempCount++;
            int c = l.contacts.size();
            totalContacts += c;
            if (c > maxContacts) { maxContacts = c; maxLabel = l.labelName; }
        }
        stats.put("nonTempLabels", all.size() - tempCount);
        stats.put("temporaryLabels", tempCount);
        stats.put("totalLabelAssignments", totalContacts);
        stats.put("maxContactLabel", maxLabel + " (" + maxContacts + "人)");
        stats.put("autoRules", AutoGroupEngine.getRuleCount());
        return stats;
    }
}
