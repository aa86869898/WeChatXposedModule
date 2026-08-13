package com.leshao.v3.hook;

import com.leshao.v3.LogWriter;
import com.leshao.v3.hook.model.AutoRule;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XposedHelpers;

public class AutoGroupEngine {

    private static final String TAG = "AutoGroupEngine";
    private static final List<AutoRule> sRules = new CopyOnWriteArrayList<>();
    private static boolean sRunning = false;

    public static void start() {
        loadRules();
        LogWriter.log(TAG, "引擎启动，已加载 " + sRules.size() + " 条规则");
    }

    public static void subscribeToEvents() {
        EventBus.subscribe(EventBus.Event.LABELS_SYNCED, (event, data) -> {
            LogWriter.log(TAG, "收到 LABELS_SYNCED，执行自动分组");
            runOnce();
        });
        EventBus.subscribe(EventBus.Event.CONTACT_LABEL_CHANGED, (event, data) -> {
            evaluateContact((String) data);
        });
    }

    public static synchronized void addRule(AutoRule rule) { sRules.add(rule); saveRules(); }
    public static synchronized boolean removeRule(int ruleId) { boolean ok = sRules.removeIf(r -> r.ruleId == ruleId); if (ok) saveRules(); return ok; }
    public static List<AutoRule> getRules() { return new ArrayList<>(sRules); }
    public static void setRuleEnabled(int ruleId, boolean enabled) { for (AutoRule r : sRules) { if (r.ruleId == ruleId) { r.enabled = enabled; break; } } saveRules(); }
    public static int getRuleCount() { return sRules.size(); }
    public static int nextRuleId() { int max = 0; for (AutoRule r : sRules) if (r.ruleId > max) max = r.ruleId; return max + 1; }

    public static synchronized void runOnce() {
        if (sRunning) return;
        sRunning = true;
        try {
            int totalMatched = 0;
            for (AutoRule rule : sRules) {
                if (!rule.enabled) continue;
                int matched = executeRule(rule);
                totalMatched += matched;
                rule.matchCount += matched;
                rule.lastRunTime = System.currentTimeMillis();
            }
            saveRules();
            if (totalMatched > 0) LogWriter.log(TAG, "扫描完成，命中 " + totalMatched + " 次");
        } catch (Throwable e) { LogWriter.log(TAG, "runOnce: " + e.getMessage()); }
        finally { sRunning = false; }
    }

    private static int executeRule(AutoRule rule) {
        int matched = 0;
        List<String> matchedUsers = new ArrayList<>();
        try {
            Object st = ChatGroupHook.getContactStorage();
            if (st == null) return 0;
            Set<String> allUsers = getAllContactUsernames();
            for (String username : allUsers) {
                if (matchRule(username, rule)) {
                    ChatGroupHook.addLabelToContact(username, rule.targetLabelId);
                    matchedUsers.add(username);
                    matched++;
                }
            }
        } catch (Throwable e) { LogWriter.log(TAG, "executeRule: " + e.getMessage()); }
        if (matched > 0) EventBus.post(EventBus.Event.AUTO_RULE_EXECUTED, new Object[]{rule, matchedUsers});
        return matched;
    }

    private static void evaluateContact(String username) {
        for (AutoRule rule : sRules) {
            if (!rule.enabled) continue;
            try { if (matchRule(username, rule)) { ChatGroupHook.addLabelToContact(username, rule.targetLabelId); rule.matchCount++; } }
            catch (Throwable ignored) {}
        }
        saveRules();
    }

    private static boolean matchRule(String username, AutoRule rule) {
        try {
            Object st = ChatGroupHook.getContactStorage();
            if (st == null) return false;
            Object contact = XposedHelpers.callMethod(st, "m", username);
            if (contact == null) return false;
            switch (rule.matchType) {
                case NICKNAME_CONTAINS: { String v = getField(contact, "field_nickname"); return v != null && v.contains(rule.matchValue); }
                case REMARK_CONTAINS: { String v = getField(contact, "field_conRemark"); return v != null && v.contains(rule.matchValue); }
                case USERNAME_MATCHES: return username.matches(rule.matchValue);
                case LABEL_NAME_MATCHES: { int[] ids = ChatGroupHook.getLabelIdsByContact(username); for (int id : ids) { com.leshao.v3.hook.model.LabelInfo l = ChatGroupHook.getLabelById(String.valueOf(id)); if (l != null && l.labelName != null && l.labelName.contains(rule.matchValue)) return true; } return false; }
                case IN_CHATROOM: {
                    // Check if contact is a member of the specified chatroom
                    try {
                        String room = rule.matchValue;
                        if (!room.endsWith("@chatroom")) room = room + "@chatroom";
                        // Access contact's chatroom membership via label storage
                        Object s = ChatGroupHook.getContactStorage();
                        Object rContact = XposedHelpers.callMethod(s, "m", room);
                        if (rContact != null) {
                            String members = (String) XposedHelpers.getObjectField(rContact, "field_chatroomMembers");
                            if (members != null && members.contains(username)) return true;
                        }
                    } catch (Throwable e) {
                        LogWriter.log(TAG, "IN_CHATROOM check failed: " + e.getMessage());
                    }
                    return false;
                }
                case REGION_EQUALS: {
                    String v = getField(contact, "field_province");
                    if (v != null && v.equals(rule.matchValue)) return true;
                    v = getField(contact, "field_city");
                    return v != null && v.equals(rule.matchValue);
                }
                default: return false;
            }
        } catch (Throwable e) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getAllContactUsernames() {
        Set<String> users = new LinkedHashSet<>();
        try {
            Object st = ChatGroupHook.getLabelStorage();
            if (st != null) {
                try { XposedHelpers.callMethod(st, "F0"); } catch (Throwable ignored) {}
                HashMap<String, int[]> map = (HashMap<String, int[]>) XposedHelpers.getObjectField(st, "e");
                if (map != null) users.addAll(map.keySet());
            }
        } catch (Throwable e) { LogWriter.log(TAG, "getAllUsers: " + e.getMessage()); }
        return users;
    }

    private static String getField(Object obj, String fieldName) {
        try { Object v = XposedHelpers.getObjectField(obj, fieldName); return v != null ? v.toString() : ""; }
        catch (Throwable e) { return ""; }
    }

    private static void loadRules() {
        try {
            String json = GroupConfigManager.getAutoRulesRaw();
            if (json == null || json.isEmpty()) return;
            sRules.clear();
            for (String part : json.split(";")) {
                if (part.isEmpty()) continue;
                String[] f = part.split("\\|", 7);
                if (f.length < 6) continue;
                AutoRule r = new AutoRule(Integer.parseInt(f[0]), f[1], AutoRule.MatchType.valueOf(f[2]), f[3], Integer.parseInt(f[4]));
                r.enabled = Boolean.parseBoolean(f[5]);
                r.matchCount = f.length >= 7 ? Integer.parseInt(f[6]) : 0;
                sRules.add(r);
            }
        } catch (Throwable e) { LogWriter.log(TAG, "loadRules: " + e.getMessage()); }
    }

    private static void saveRules() {
        StringBuilder sb = new StringBuilder();
        for (AutoRule r : sRules) { if (sb.length() > 0) sb.append(";"); sb.append(r.ruleId).append("|").append(r.ruleName).append("|").append(r.matchType.name()).append("|").append(r.matchValue).append("|").append(r.targetLabelId).append("|").append(r.enabled).append("|").append(r.matchCount); }
        GroupConfigManager.setAutoRulesRaw(sb.toString());
    }
}
